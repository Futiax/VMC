package ovh.futiax.minecraftvideo;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Decodes a video/audio source to raw PCM with its own ffmpeg process and
 * hands it out one Simple Voice Chat frame at a time.
 *
 * ffmpeg is spawned as:
 *
 *   ffmpeg -v error -i &lt;source&gt; -vn -ac 1 -ar 48000 -f s16le -
 *
 * i.e. mono, 48 kHz, signed 16-bit little-endian PCM on stdout — exactly the
 * format Simple Voice Chat expects, in {@value #FRAME_SAMPLES}-sample frames
 * (20 ms). ffmpeg reads local files and URLs alike.
 *
 * The source is passed as a ProcessBuilder argument (argv, no shell), so there
 * is no shell-injection surface here.
 */
public final class AudioStream implements Closeable {

    /** Samples per SVC audio frame: 48 kHz * 20 ms = 960. */
    public static final int FRAME_SAMPLES = 960;

    private static final int FRAME_BYTES = FRAME_SAMPLES * 2; // s16le

    private final Process process;
    private final InputStream stdout;

    public AudioStream(Logger logger, String ffmpegPath, String source) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                ffmpegPath, "-v", "error",
                "-i", source,
                "-vn",             // no video
                "-ac", "1",        // mono
                "-ar", "48000",    // 48 kHz
                "-f", "s16le",     // signed 16-bit little-endian PCM
                "-");              // stdout
        this.process = builder.start();
        this.stdout = process.getInputStream();
        drainStderr(logger);
    }

    /**
     * Blocking read of one 960-sample PCM frame.
     *
     * @return a 960-length {@code short[]}, or {@code null} at end of stream
     *         (a short read is treated as EOF).
     */
    public short[] nextFrame() throws IOException {
        byte[] raw = stdout.readNBytes(FRAME_BYTES);
        if (raw.length < FRAME_BYTES) {
            return null; // EOF or truncated tail -> end of audio
        }
        short[] frame = new short[FRAME_SAMPLES];
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            // little-endian: low byte first
            frame[i] = (short) ((raw[i * 2] & 0xFF) | (raw[i * 2 + 1] << 8));
        }
        return frame;
    }

    private void drainStderr(Logger logger) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("[ffmpeg-audio] " + line);
                }
            } catch (IOException ignored) {
                // Process killed or stream closed.
            }
        }, "MinecraftVideo-audio-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        process.destroyForcibly();
        try {
            stdout.close();
        } catch (IOException ignored) {
            // Best-effort.
        }
    }
}
