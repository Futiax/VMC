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
 *   ffmpeg -v error [-ss &lt;offset&gt;] -i &lt;source&gt; -vn -ac &lt;channels&gt; -ar 48000 -f s16le -
 *
 * i.e. {@code channels}-channel, 48 kHz, signed 16-bit little-endian PCM on
 * stdout — exactly the format Simple Voice Chat expects, in
 * {@value #FRAME_SAMPLES}-sample frames per channel (20 ms). With N &gt; 1
 * channels ffmpeg emits interleaved samples which {@link #nextFrame()} splits
 * back into one {@code short[]} per channel. ffmpeg reads local files and URLs
 * alike. The optional {@code -ss} (before {@code -i} = fast input seek) starts
 * decoding at a given offset, used by {@code /video seek}.
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

    public AudioStream(Logger logger, String ffmpegPath, String source, int channels,
                       long startOffsetMillis) throws IOException {
        this.channels = channels;
        this.frameBytes = channels * FRAME_SAMPLES * 2;
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-v");
        cmd.add("error");
        if (startOffsetMillis > 0) {
            cmd.add("-ss"); // before -i: fast input seek
            // Locale.ROOT: a French default locale would format "12,5" and break ffmpeg.
            cmd.add(String.format(java.util.Locale.ROOT, "%.3f", startOffsetMillis / 1000.0));
        }
        cmd.add("-i");
        cmd.add(source);
        cmd.add("-vn");                            // no video
        cmd.add("-ac");
        cmd.add(Integer.toString(channels));       // 1 = mono, 2 = stereo, 6 = 5.1
        cmd.add("-ar");
        cmd.add("48000");                          // 48 kHz
        cmd.add("-f");
        cmd.add("s16le");                          // signed 16-bit little-endian PCM
        cmd.add("-");                              // stdout
        this.process = new ProcessBuilder(cmd).start();
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
