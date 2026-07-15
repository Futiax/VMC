package ovh.futiax.minecraftvideo;

import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import org.bukkit.World;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Streams a video's audio track into a Simple Voice Chat locational channel
 * anchored at the screen, so nearby players hear it spatialised.
 *
 * Design: a reader thread pulls 960-sample frames from {@link AudioStream}
 * (its own ffmpeg process) into a bounded queue; SVC pulls from that queue via
 * the supplier it calls every 20 ms. The queue decouples ffmpeg I/O from SVC's
 * audio thread — the supplier never blocks on the network. On underrun it emits
 * a silent frame; at EOF it returns {@code null}, which ends playback.
 *
 * Players without Simple Voice Chat installed/connected simply won't hear it.
 */
public final class AudioPlayback {

    /** ~1 s of buffered audio (50 * 20 ms). */
    private static final int QUEUE_CAPACITY = 50;
    private static final short[] SILENCE = new short[AudioStream.FRAME_SAMPLES];

    private final AudioStream stream;
    private final AudioPlayer player;
    private final Logger logger;

    private final BlockingQueue<short[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile boolean eof = false;
    private volatile boolean paused = false;
    private final Thread reader;

    private AudioPlayback(VoicechatServerApi api, ServerLevel level, Position position,
                          int distance, AudioStream stream, Logger logger) {
        this.stream = stream;
        this.logger = logger;

        LocationalAudioChannel channel =
                api.createLocationalAudioChannel(UUID.randomUUID(), level, position);
        if (channel == null) {
            throw new IllegalStateException("SVC returned no locational audio channel");
        }
        channel.setDistance(distance);

        Supplier<short[]> supplier = this::supplyFrame;
        this.player = api.createAudioPlayer(channel, api.createEncoder(), supplier);

        this.reader = new Thread(this::readLoop, "MinecraftVideo-Audio-Reader");
        this.reader.setDaemon(true);
    }

    /**
     * Creates and starts audio playback for a source, anchored at (x,y,z) in
     * the given world. Returns {@code null} if audio could not be started
     * (SVC unavailable, ffmpeg failed) — video playback should continue anyway.
     */
    public static AudioPlayback start(VoicechatServerApi api, World world,
                                      double x, double y, double z, int distance,
                                      String ffmpegPath, String source, Logger logger) {
        AudioStream stream = null;
        try {
            stream = new AudioStream(logger, ffmpegPath, source);
            ServerLevel level = api.fromServerLevel(world);
            Position position = api.createPosition(x, y, z);
            AudioPlayback playback = new AudioPlayback(api, level, position, distance, stream, logger);
            playback.reader.start();
            playback.player.startPlaying();
            return playback;
        } catch (Exception e) {
            logger.warning("Audio playback unavailable: " + e.getMessage());
            if (stream != null) {
                stream.close();
            }
            return null;
        }
    }

    /** Reader thread: pull PCM frames from ffmpeg into the queue until EOF/stop. */
    private void readLoop() {
        try {
            while (!stopped.get()) {
                short[] frame = stream.nextFrame();
                if (frame == null) {
                    eof = true;
                    return;
                }
                // Block until there is room; keeps us ~1 s ahead of playback.
                queue.put(frame);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            eof = true;
            logger.fine("Audio reader ended: " + e.getMessage());
        }
    }

    /**
     * Pauses/resumes audio. While paused the supplier emits silence and does
     * NOT drain the queue, so ffmpeg backpressures (via a full queue) and
     * resume continues from the exact frame where playback stopped, staying in
     * step with the video (which is throttled the same way).
     */
    public void setPaused(boolean value) {
        paused = value;
    }

    /** Called by SVC every 20 ms on its own thread. */
    private short[] supplyFrame() {
        if (stopped.get()) {
            return null;
        }
        if (paused) {
            return SILENCE; // freeze: keep the channel open without draining the queue
        }
        try {
            short[] frame = queue.poll(20, TimeUnit.MILLISECONDS);
            if (frame != null) {
                return frame;
            }
            if (eof && queue.isEmpty()) {
                return null; // real end of audio
            }
            return SILENCE; // transient underrun: keep the channel alive
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Idempotent; stops the SVC player, kills ffmpeg and the reader thread. */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            player.stopPlaying();
        } catch (Exception ignored) {
            // SVC may already have torn the channel down.
        }
        stream.close();
        reader.interrupt();
        queue.clear();
    }
}
