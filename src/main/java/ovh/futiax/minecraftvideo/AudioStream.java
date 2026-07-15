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
 * hands it out one Simple Voice Chat frame at a time, de-interleaved per channel.
 *
 * ffmpeg is spawned as:
 *
 *   ffmpeg -v error -i &lt;source&gt; -vn -ac &lt;channels&gt; -ar 48000 -f s16le -
 *
 * i.e. {@code channels}-channel, 48 kHz, signed 16-bit little-endian PCM on
 * stdout — exactly the format Simple Voice Chat expects, in
 * {@value #FRAME_SAMPLES}-sample frames per channel (20 ms). With 2 channels
 * ffmpeg emits interleaved L,R,L,R,... which {@link #nextFrame()} splits back
 * into one {@code short[]} per channel. ffmpeg reads local files and URLs alike.
 *
 * The source is passed as a ProcessBuilder argument (argv, no shell), so there
 * is no shell-injection surface here.
 */
public final class AudioStream implements Closeable {

    /** Samples per SVC audio frame, PER CHANNEL: 48 kHz * 20 ms = 960. */
    public static final int FRAME_SAMPLES = 960;

    private final int channels;
    private final int frameBytes; // channels * FRAME_SAMPLES * 2 (s16le)
    private final Process process;
    private final InputStream stdout;

    public AudioStream(Logger logger, String ffmpegPath, String source, int channels)
            throws IOException {
        this.channels = channels;
        this.frameBytes = channels * FRAME_SAMPLES * 2;
        ProcessBuilder builder = new ProcessBuilder(
                ffmpegPath, "-v", "error",
                "-i", source,
                "-vn",                          // no video
                "-ac", Integer.toString(channels), // 1 = mono, 2 = stereo
                "-ar", "48000",                 // 48 kHz
                "-f", "s16le",                  // signed 16-bit little-endian PCM
                "-");                           // stdout
        this.process = builder.start();
        this.stdout = process.getInputStream();
        drainStderr(logger);
    }

    public int getChannels() {
        return channels;
    }

    /**
     * Blocking read of one 20 ms frame (960 samples per channel), de-interleaved.
     *
     * @return {@code short[channels][FRAME_SAMPLES]}, or {@code null} at end of
     *         stream (a short read is treated as EOF).
     */
    public short[][] nextFrame() throws IOException {
        byte[] raw = stdout.readNBytes(frameBytes);
        if (raw.length < frameBytes) {
            return null; // EOF or truncated tail -> end of audio
        }
        short[][] frame = new short[channels][FRAME_SAMPLES];
        for (int s = 0; s < FRAME_SAMPLES; s++) {
            int base = s * channels * 2; // interleaved: all channels of sample s together
            for (int c = 0; c < channels; c++) {
                int off = base + c * 2;
                // little-endian: low byte first
                frame[c][s] = (short) ((raw[off] & 0xFF) | (raw[off + 1] << 8));
            }
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
