package ovh.futiax.minecraftvideo;

import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
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
 * <p>Speaker layouts (see {@link AudioMode}): mono = 1 channel at the screen
 * center; stereo = 2 at the screen's left/right edges; surround = 5 (front L/R
 * at the edges, center at the screen center with the LFE folded in, rear L/R
 * behind the audience). A fixed "cinema" image: the client's own head tracking
 * pans the sound as the viewer moves, and a locational channel is broadcast to
 * every nearby player, so N channels cover the whole room, not N per viewer.
 *
 * <p><b>Channel alignment.</b> Each SVC {@link AudioPlayer} pulls on its own
 * thread every 20 ms, so the per-channel suppliers are NOT phase-locked. If each
 * channel had its own queue, their independent underrun decisions could let the
 * channels diverge by whole frames and never re-sync (a race on a frame arriving
 * into an empty queue: one supplier's poll catches it, another's just timed out
 * to silence). Instead a single queue holds whole speaker-mapped frames; channel
 * 0 is the "master" that dequeues the next frame and publishes it via
 * {@link #current}, and the other channels mirror that same frame. Every channel
 * therefore emits the same frame index each tick, and underrun/EOF affect them
 * together. The only residual offset is the sub-frame (&lt;20 ms) phase
 * difference between the SVC player threads, which cannot accumulate.
 *
 * <p>Lifecycle: the caller creates the {@link AudioStream} itself (possibly well
 * before the screen exists, to pre-warm ffmpeg's network connection and codec
 * init), then calls {@link #create}, {@link #awaitPrimed} and {@link #begin}.
 * From {@code create} on, this class owns the stream and closes it in
 * {@link #stop}. On create failure the caller keeps ownership.
 *
 * <p>Players without Simple Voice Chat installed/connected simply won't hear it.
 */
public final class AudioPlayback {

    /** ~1 s of buffered audio (50 * 20 ms). */
    private static final int QUEUE_CAPACITY = 50;
    private static final short[] SILENCE = new short[AudioStream.FRAME_SAMPLES];

    /**
     * Subwoofer boost: film mixes expect the LFE channel to be played ~+10 dB
     * hot (bass management); +6 dB (x2, saturating) is our compromise against
     * clipping the s16 range.
     */
    private static final int LFE_BOOST = 2;

    private final AudioStream stream;
    private final AudioPlayer[] players;
    private final Logger logger;

    /** Whole speaker-mapped frames: {@code short[speakers][FRAME_SAMPLES]}. */
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
        int speakers = positions.length;

        this.players = new AudioPlayer[speakers];
        try {
            for (int ch = 0; ch < speakers; ch++) {
                LocationalAudioChannel channel =
                        api.createLocationalAudioChannel(UUID.randomUUID(), level, positions[ch]);
                if (channel == null) {
                    throw new IllegalStateException("SVC returned no locational audio channel");
                }
                channel.setDistance(distance);
                final int idx = ch;
                // AUDIO mode, not the default VOIP: VOIP is tuned for speech
                // (high-pass filtering, SILK voice model) and guts music bass.
                players[ch] = api.createAudioPlayer(channel,
                        api.createEncoder(OpusEncoderMode.AUDIO),
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
     * Builds the SVC channels/players around a pre-created (and ideally already
     * pre-warmed) {@link AudioStream} and starts buffering audio. {@code anchors}
     * holds one {@code {x,y,z}} world position per speaker; the decoded channel
     * count comes from the stream itself. Playback does not start until
     * {@link #begin()}.
     *
     * <p>On success this instance owns the stream. On exception the CALLER still
     * owns (and must close) the stream.
     */
    public static AudioPlayback create(VoicechatServerApi api, World world,
                                       double[][] anchors, int distance,
                                       AudioStream stream, Logger logger) {
        ServerLevel level = api.fromServerLevel(world);
        Position[] positions = new Position[anchors.length];
        for (int i = 0; i < anchors.length; i++) {
            positions[i] = api.createPosition(anchors[i][0], anchors[i][1], anchors[i][2]);
        }
        AudioPlayback playback = new AudioPlayback(api, level, positions, distance, stream, logger);
        playback.reader.start();
        return playback;
    }

    /**
     * Whether at least one audio frame is buffered. {@link #begin()} should
     * only be called once primed, so the SVC timeline starts at the stream's
     * first sample instead of leading silence while ffmpeg warms up.
     */
    public boolean isPrimed() {
        return !queue.isEmpty();
    }

    /** Starts the SVC players; audio is audible from this call on. */
    public void begin() {
        for (AudioPlayer p : players) {
            p.startPlaying();
        }
    }

    /**
     * Drops up to {@code n} buffered frames (non-blocking). Used when
     * {@link #begin()} happens later than planned: skipping the equivalent
     * content keeps the audio aligned with the picture instead of starting
     * behind and staying behind.
     *
     * @return how many frames were actually dropped (bounded by the buffer)
     */
    public int skipFrames(int n) {
        int dropped = 0;
        while (dropped < n && queue.poll() != null) {
            dropped++;
        }
        return dropped;
    }

    /** Reader thread: pull PCM frames from ffmpeg into the queue until EOF/stop. */
    private void readLoop() {
        try {
            while (!stopped.get()) {
                short[][] decoded = stream.nextFrame();
                if (decoded == null) {
                    eof = true;
                    return;
                }
                // One put per frame (all speakers together); blocks when the
                // queue is full, backpressuring ffmpeg and keeping ~1 s buffered.
                queue.put(mapToSpeakers(decoded));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            eof = true;
            logger.fine("Audio reader ended: " + e.getMessage());
        }
    }

    /**
     * Maps decoded channels onto speaker channels. Identity for mono/stereo.
     * For 5.1 the order already matches the speakers (FL FR FC LFE BL BR, the
     * LFE being the in-world subwoofer at the screen base); the LFE is boosted
     * +6 dB with saturation, mirroring the bass-management gain real gear
     * applies to that channel.
     */
    private short[][] mapToSpeakers(short[][] decoded) {
        if (decoded.length == 6) {
            short[] lfe = new short[AudioStream.FRAME_SAMPLES];
            for (int i = 0; i < lfe.length; i++) {
                int v = decoded[3][i] * LFE_BOOST;
                lfe[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
            }
            decoded[3] = lfe;
        }
        return decoded;
    }

    /**
     * Pauses/resumes audio. While paused every supplier emits silence and the
     * master does NOT drain the queue, so ffmpeg backpressures (via a full queue)
     * and resume continues from the exact frame where playback stopped, staying
     * in step with the video (which is throttled the same way).
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
