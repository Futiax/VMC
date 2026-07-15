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
import java.util.logging.Logger;

/**
 * Streams a video's audio track into one or more Simple Voice Chat locational
 * channels anchored in the world, so nearby players hear it spatialised.
 *
 * <p>Mono uses a single channel at the screen center. Stereo uses two channels
 * anchored to the screen's left/right edges: a fixed "cinema" image where the
 * client's own head tracking pans the sound as the viewer moves — like real
 * speakers. A locational channel is broadcast to every nearby player, so N
 * channels cover the whole room, not N per viewer.
 *
 * <p><b>Channel alignment.</b> Each SVC {@link AudioPlayer} pulls on its own
 * thread every 20 ms, so the per-channel suppliers are NOT phase-locked. If each
 * channel had its own queue, their independent underrun decisions could let L and
 * R diverge by whole frames and never re-sync (a race on a frame arriving into an
 * empty queue: one supplier's poll catches it, the other's just timed out to
 * silence). Instead a single queue holds whole de-interleaved frames; channel 0
 * is the "master" that dequeues the next frame and publishes it via
 * {@link #current}, and the other channels mirror that same frame. Every channel
 * therefore emits the same frame index each tick, and underrun/EOF affect them
 * together. The only residual offset is the sub-frame (&lt;20 ms) phase difference
 * between the SVC player threads, which cannot accumulate.
 *
 * <p>Design: a reader thread pulls de-interleaved frames from {@link AudioStream}
 * (its own ffmpeg process) into the bounded queue; the master supplier drains it.
 * The queue decouples ffmpeg I/O from SVC's audio threads — a supplier never
 * blocks on the network. On underrun it emits silence; at EOF it returns
 * {@code null}, ending playback.
 *
 * <p>Players without Simple Voice Chat installed/connected simply won't hear it.
 */
public final class AudioPlayback {

    /** ~1 s of buffered audio (50 * 20 ms). */
    private static final int QUEUE_CAPACITY = 50;
    private static final short[] SILENCE = new short[AudioStream.FRAME_SAMPLES];

    private final AudioStream stream;
    private final AudioPlayer[] players;
    private final Logger logger;

    /** Whole de-interleaved frames: {@code short[channels][FRAME_SAMPLES]}. */
    private final BlockingQueue<short[][]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    /** Frame the master (channel 0) last dequeued; mirrored by the other channels. */
    private volatile short[][] current;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile boolean eof = false;   // reader reached end of ffmpeg output
    private volatile boolean ended = false; // master returned null; slaves must end too
    private volatile boolean paused = false;
    private final Thread reader;

    private AudioPlayback(VoicechatServerApi api, ServerLevel level, Position[] positions,
                          int distance, AudioStream stream, Logger logger) {
        this.stream = stream;
        this.logger = logger;
        int channels = positions.length;

        this.players = new AudioPlayer[channels];
        try {
            for (int ch = 0; ch < channels; ch++) {
                LocationalAudioChannel channel =
                        api.createLocationalAudioChannel(UUID.randomUUID(), level, positions[ch]);
                if (channel == null) {
                    throw new IllegalStateException("SVC returned no locational audio channel");
                }
                channel.setDistance(distance);
                final int idx = ch;
                players[ch] = api.createAudioPlayer(channel, api.createEncoder(),
                        () -> supplyFrame(idx));
            }
        } catch (RuntimeException e) {
            // Partial construction: release the players already built before the
            // failure so we don't leak their encoders (none are startPlaying'd yet).
            for (AudioPlayer p : players) {
                if (p != null) {
                    try {
                        p.stopPlaying();
                    } catch (Exception ignored) {
                        // best-effort
                    }
                }
            }
            throw e;
        }

        this.reader = new Thread(this::readLoop, "MinecraftVideo-Audio-Reader");
        this.reader.setDaemon(true);
    }

    /**
     * Creates and starts audio playback for a source. {@code anchors} holds one
     * {@code {x,y,z}} world position per channel (1 = mono, 2 = stereo L/R).
     * Returns {@code null} if audio could not be started (SVC unavailable, ffmpeg
     * failed) — video playback should continue anyway.
     */
    public static AudioPlayback start(VoicechatServerApi api, World world,
                                      double[][] anchors, int distance,
                                      String ffmpegPath, String source, Logger logger) {
        AudioStream stream = null;
        try {
            int channels = anchors.length;
            stream = new AudioStream(logger, ffmpegPath, source, channels);
            ServerLevel level = api.fromServerLevel(world);
            Position[] positions = new Position[channels];
            for (int i = 0; i < channels; i++) {
                positions[i] = api.createPosition(anchors[i][0], anchors[i][1], anchors[i][2]);
            }
            AudioPlayback playback = new AudioPlayback(api, level, positions, distance, stream, logger);
            playback.reader.start();
            for (AudioPlayer p : playback.players) {
                p.startPlaying();
            }
            return playback;
        } catch (Exception e) {
            logger.warning("Audio playback unavailable: " + e.getMessage());
            if (stream != null) {
                stream.close();
            }
            return null;
        }
    }

    /** Reader thread: pull whole PCM frames from ffmpeg into the queue until EOF/stop. */
    private void readLoop() {
        try {
            while (!stopped.get()) {
                short[][] frame = stream.nextFrame();
                if (frame == null) {
                    eof = true;
                    return;
                }
                // One put per frame (all channels together); blocks when the queue
                // is full, backpressuring ffmpeg and keeping ~1 s buffered.
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
     * Pauses/resumes audio. While paused every supplier emits silence and the
     * master does NOT drain the queue, so ffmpeg backpressures (via a full queue)
     * and resume continues from the exact frame where playback stopped, staying in
     * step with the video (which is throttled the same way).
     */
    public void setPaused(boolean value) {
        paused = value;
    }

    /** Called by SVC every 20 ms per channel, each on its own thread. */
    private short[] supplyFrame(int channel) {
        if (stopped.get()) {
            return null;
        }
        if (paused) {
            return SILENCE; // freeze: keep the channel open without draining
        }
        if (channel == 0) {
            return supplyMaster();
        }
        // Other channels mirror the master's current frame so every channel emits
        // the same frame index; they never touch the queue.
        if (ended) {
            return null;
        }
        short[][] f = current;
        return (f != null && channel < f.length) ? f[channel] : SILENCE;
    }

    /** Channel 0: advances the shared stream, publishing each frame for the mirrors. */
    private short[] supplyMaster() {
        try {
            short[][] frame = queue.poll(20, TimeUnit.MILLISECONDS);
            if (frame != null) {
                current = frame;
                return frame[0];
            }
            if (eof && queue.isEmpty()) {
                ended = true;   // real end of audio: the mirrors end this tick too
                current = null;
                return null;
            }
            current = null;     // transient underrun: mirrors go silent with us
            return SILENCE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Idempotent; stops the SVC players, kills ffmpeg and the reader thread. */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        for (AudioPlayer p : players) {
            try {
                p.stopPlaying();
            } catch (Exception ignored) {
                // SVC may already have torn the channel down.
            }
        }
        stream.close();
        reader.interrupt();
        queue.clear();
    }
}
