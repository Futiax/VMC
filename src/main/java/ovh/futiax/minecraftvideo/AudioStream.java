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
 *   ffmpeg -v error [-ss &lt;offset&gt;] -i &lt;source&gt; -vn [-af ...] -ac &lt;channels&gt; -ar 48000 -f s16le -
 *
 * i.e. {@code channels}-channel, 48 kHz, signed 16-bit little-endian PCM on
 * stdout — exactly the format Simple Voice Chat expects, in
 * {@value #FRAME_SAMPLES}-sample frames per channel (20 ms). With N &gt; 1
 * channels ffmpeg emits interleaved samples which {@link #nextFrame()} splits
 * back into one {@code short[]} per channel. ffmpeg reads local files and URLs
 * alike. The optional {@code -ss} (before {@code -i} = fast input seek) starts
 * decoding at a given offset, used by {@code /video seek}.
 *
 * <p>Channel-layout handling ({@code -af}):
 * <ul>
 *   <li>mono/stereo output: explicit aresample downmix with
 *       {@code lfe_mix_level} so a 5.1 source's LFE is folded in instead of
 *       silently dropped (swresample's default);</li>
 *   <li>surround (6ch) output from a source with FEWER than 6 channels: a
 *       plain {@code -ac 6} would leave FC/LFE/BL/BR digitally SILENT
 *       (swresample never synthesizes channels — proven with archive.org's
 *       stereo derivative of surroundTest.mp4), so the {@code surround}
 *       filter builds a true 5.1 instead: center from the stereo
 *       correlation, rears from ambience, LFE synthesized by low-pass (real
 *       bass management). The caller supplies the probed source channel
 *       count (see {@link #probeChannels});</li>
 *   <li>surround output from a real 5.1+ source: direct bed mapping, no
 *       filter.</li>
 * </ul>
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

    /**
     * @param sourceChannels channel count of the source's audio stream (from
     *                       {@link #probeChannels}), or -1 when unknown; only
     *                       consulted for the surround upmix decision
     */
    public AudioStream(Logger logger, String ffmpegPath, String source, int channels,
                       long startOffsetMillis, int sourceChannels) throws IOException {
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
        if (channels <= 2) {
            // Explicit resampler stage for the mono/stereo downmix: swresample's
            // default lfe_mix_level is 0, i.e. a 5.1/7.1 source's LFE channel
            // (where films put the booms) is silently DROPPED. Fold it in at
            // ~-3 dB instead. Surround (-ac 6) keeps LFE as its own channel.
            // (out_chlayout needs ffmpeg >= 6.)
            cmd.add("-af");
            cmd.add("aresample=out_chlayout=" + (channels == 1 ? "mono" : "stereo")
                    + ":lfe_mix_level=0.707");
        } else if (channels == 6 && sourceChannels > 0 && sourceChannels < 6) {
            // Real upmix for a <6-channel source in surround mode: a plain
            // -ac 6 leaves FC/LFE/BL/BR digitally SILENT (swresample never
            // synthesizes channels). Normalize to stereo (folding any LFE in),
            // then let the surround filter build a true 5.1: center from the
            // stereo correlation, rears from ambience, LFE synthesized by
            // low-pass — real bass management, so the in-world subwoofer works
            // for plain stereo files too.
            cmd.add("-af");
            cmd.add("aresample=out_chlayout=stereo:lfe_mix_level=0.707,"
                    + "surround=chl_out=5.1");
        }
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
     * Returns the channel count of the source's first audio stream, probed
     * with ffprobe (located next to the configured ffmpeg binary), or -1 when
     * probing fails (no ffprobe, timeout, no audio stream...). -1 makes the
     * surround decode fall back to the plain 5.1 bed mapping — correct for
     * real films, and the pre-upmix status quo for everything else.
     *
     * <p>Blocking (up to ~20 s for a slow URL); call it once per source and
     * cache the result across seek segments.
     */
    public static int probeChannels(Logger logger, String ffmpegPath, String source) {
        String ffprobePath = siblingFfprobe(ffmpegPath);
        Process process = null;
        try {
            process = new ProcessBuilder(ffprobePath, "-v", "error",
                    "-select_streams", "a:0",
                    "-show_entries", "stream=channels",
                    "-of", "csv=p=0",
                    source).redirectErrorStream(true).start();
            // The output is a single tiny line, so no pipe backpressure can
            // block the process: waitFor first, read after.
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                logger.warning("ffprobe timed out probing audio channels of '"
                        + source + "'; assuming a 5.1 source.");
                return -1;
            }
            String out = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            int channels = Integer.parseInt(out.lines().findFirst().orElse("").trim());
            return channels > 0 ? channels : -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly(); // process is non-null: start() succeeded
            return -1;
        } catch (IOException | NumberFormatException e) {
            logger.warning("ffprobe (" + ffprobePath + ") could not probe '" + source
                    + "' (" + e.getMessage() + "); assuming a 5.1 source.");
            return -1;
        }
    }

    /**
     * Derives the ffprobe path from the configured ffmpeg path:
     * {@code ffmpeg -> ffprobe}, {@code /opt/ffmpeg -> /opt/ffprobe},
     * {@code C:\tools\ffmpeg.exe -> C:\tools\ffprobe.exe}. If the file name
     * does not contain "ffmpeg", falls back to "ffprobe" from PATH.
     */
    private static String siblingFfprobe(String ffmpegPath) {
        java.io.File f = new java.io.File(ffmpegPath);
        String name = f.getName();
        if (!name.toLowerCase(java.util.Locale.ROOT).contains("ffmpeg")) {
            return "ffprobe";
        }
        String probeName = name.replaceFirst("(?i)ffmpeg", "ffprobe");
        String parent = f.getParent();
        return parent == null ? probeName : new java.io.File(parent, probeName).getPath();
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
