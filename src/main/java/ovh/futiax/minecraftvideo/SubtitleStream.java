package ovh.futiax.minecraftvideo;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Extracts ONE embedded subtitle track of a source as SubRip text with a
 * dedicated ffmpeg process and answers "which cue is showing at video time T?".
 * The overlay counterpart of {@link AudioStream}: its own ffmpeg per segment,
 * killed and relaunched at the new offset on {@code /video seek}.
 *
 * <p>ffmpeg is spawned as:
 * <pre>
 *   ffmpeg -v error [-ss &lt;offset&gt;] -copyts -i &lt;source&gt; -map 0:s:&lt;n&gt; -f srt -
 * </pre>
 * i.e. the {@code n}-th subtitle stream transcoded to SRT on stdout. Two
 * deliberate choices about timestamps (validated against ffmpeg 8 with a
 * two-track test MKV):
 * <ul>
 *   <li><b>{@code -copyts}</b> keeps the ORIGINAL cue timestamps. Without it,
 *       ffmpeg's rebasing for text subtitles is unreliable (it does not shift by
 *       the seek amount, and does not reliably drop earlier cues), so cue times
 *       would not match the picture after a seek. With {@code -copyts} the SRT
 *       times ARE absolute video time, and {@link #cueAtVideoMillis} compares
 *       {@link PlaybackSession#getPositionMillis()} directly — no offset math.</li>
 *   <li><b>{@code -ss} before {@code -i}</b> is only a speed hint (skip ahead in
 *       a big container). It may still leak a few pre-offset cues; they simply
 *       are not active at the current position, so they never show.</li>
 * </ul>
 *
 * <p>A daemon reader thread streams stdout through {@link SrtParser} and appends
 * finished cues to a time-ordered list; {@link #cueAtVideoMillis} binary-searches
 * it. So cues become visible as soon as ffmpeg emits them (well ahead of
 * playback for a file source), and the read never blocks playback.
 *
 * <p>The source and index are passed as argv (no shell), so there is no
 * injection surface. Only text codecs are extractable; the caller filters
 * bitmap tracks out via {@link SubtitleTrack#textBased()} before constructing.
 */
public final class SubtitleStream implements Closeable {

    private final Process process;
    private final Thread reader;
    private volatile boolean closed = false;

    /** Cues in start-time order (absolute video millis); guarded by {@code cues}. */
    private final List<SrtParser.Cue> cues = new ArrayList<>();

    /**
     * Starts the extraction ffmpeg for subtitle stream {@code subtitleIndex}
     * (the {@code n} in {@code -map 0:s:n}). {@code startOffsetMillis} is a
     * seek-ahead speed hint only; {@code -copyts} keeps cue times absolute, so
     * lookups need no offset correction.
     */
    public SubtitleStream(Logger logger, String ffmpegPath, String source,
                          int subtitleIndex, long startOffsetMillis) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-v");
        cmd.add("error");
        if (startOffsetMillis > 0) {
            cmd.add("-ss"); // before -i: fast input seek (speed hint; see class doc)
            // Locale.ROOT: a French default locale would format "12,5" and break ffmpeg.
            cmd.add(String.format(Locale.ROOT, "%.3f", startOffsetMillis / 1000.0));
        }
        cmd.add("-copyts");              // keep original (absolute) cue timestamps
        cmd.add("-i");
        cmd.add(source);
        cmd.add("-map");
        cmd.add("0:s:" + subtitleIndex); // the n-th subtitle stream
        cmd.add("-f");
        cmd.add("srt");
        cmd.add("-");                    // stdout
        this.process = new ProcessBuilder(cmd).start();
        this.reader = new Thread(() -> readLoop(logger), "MinecraftVideo-subs-reader");
        this.reader.setDaemon(true);
        this.reader.start();
        // Drain stderr CONCURRENTLY on its own daemon thread: with -v error it
        // is usually tiny, but a source with many decode warnings could fill the
        // ~64 KB stderr pipe and block ffmpeg's stdout writes (deadlock) if we
        // only read stderr after stdout EOF.
        drainStderr(logger);
    }

    /** Reader thread: parse ffmpeg's SRT stdout into cues until EOF/close. */
    private void readLoop(Logger logger) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            SrtParser parser = new SrtParser();
            String line;
            while ((line = in.readLine()) != null) {
                SrtParser.Cue cue = parser.accept(line);
                if (cue != null) {
                    addCue(cue);
                }
            }
            SrtParser.Cue tail = parser.flush(); // last block if not blank-terminated
            if (tail != null) {
                addCue(tail);
            }
        } catch (IOException ignored) {
            // Process killed (seek/stop) or stream closed; nothing to do.
        }
    }

    /** Appends a cue, keeping the list sorted by start time (ffmpeg emits in order). */
    private void addCue(SrtParser.Cue cue) {
        synchronized (cues) {
            // ffmpeg emits SRT in chronological order, so append is almost always
            // already sorted; guard against a rare out-of-order tail cheaply.
            if (!cues.isEmpty() && cue.startMillis() < cues.get(cues.size() - 1).startMillis()) {
                int i = cues.size();
                while (i > 0 && cues.get(i - 1).startMillis() > cue.startMillis()) {
                    i--;
                }
                cues.add(i, cue);
            } else {
                cues.add(cue);
            }
        }
    }

    /**
     * The cue active at absolute video time {@code videoMillis}
     * ({@link PlaybackSession#getPositionMillis()}), or {@code null} if none is
     * showing (a gap between cues, or not extracted yet). Cue times are absolute
     * ({@code -copyts}), so the position is compared directly.
     */
    public String cueAtVideoMillis(long videoMillis) {
        synchronized (cues) {
            // Binary search for the last cue whose start <= videoMillis, range-check.
            int lo = 0;
            int hi = cues.size() - 1;
            int best = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (cues.get(mid).startMillis() <= videoMillis) {
                    best = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            // Overlapping cues are rare but legal; scan back from the last cue
            // that started, in case an earlier (possibly much earlier), longer
            // cue still covers the time while later short ones sorted after it.
            // No fixed cap: a long banner cue followed by many short overlapping
            // ones must not be dropped just because it fell outside a window.
            for (int i = best; i >= 0; i--) {
                SrtParser.Cue c = cues.get(i);
                if (c.startMillis() <= videoMillis && videoMillis < c.endMillis()) {
                    return c.text();
                }
            }
            return null;
        }
    }

    private void drainStderr(Logger logger) {
        Thread thread = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    logger.info("[ffmpeg-subs] " + line);
                }
            } catch (IOException ignored) {
                // Process killed or stream closed.
            }
        }, "MinecraftVideo-subs-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        closed = true;
        process.destroyForcibly();
        reader.interrupt();
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Lists the source's embedded subtitle streams with ffprobe (located next
     * to the configured ffmpeg binary, same derivation as
     * {@link AudioStream#probeChannels}). Returns an EMPTY list when the source
     * was probed and has no subtitle streams, or {@code null} when probing
     * itself failed (see below).
     *
     * <p>Blocking (up to ~20 s for a slow URL); call it off the main thread.
     * The subtitle-relative index of each track (the {@code n} for
     * {@code -map 0:s:n}) is its position in the returned list.
     *
     * <p>Returns {@code null} (NOT an empty list) when probing FAILED — no
     * ffprobe, timeout, or I/O error — so a caller can distinguish "probed, the
     * source has no subtitles" (empty list) from "could not probe, retry later"
     * ({@code null}) instead of caching a transient failure forever.
     */
    public static List<SubtitleTrack> probeTracks(Logger logger, String ffmpegPath, String source) {
        String ffprobePath = siblingFfprobe(ffmpegPath);
        List<SubtitleTrack> tracks = new ArrayList<>();
        Process process = null;
        try {
            // One line per subtitle stream: "codec|language|title" (empty tags
            // stay empty). -select_streams s restricts to subtitle streams, so
            // the row index IS the -map 0:s:n index.
            process = new ProcessBuilder(ffprobePath, "-v", "error",
                    "-select_streams", "s",
                    "-show_entries", "stream=codec_name:stream_tags=language,title",
                    "-of", "csv=p=0",
                    source).redirectErrorStream(true).start();
            // Drain stdout on a separate thread STARTED BEFORE waitFor: a corrupt
            // container (many parse errors, dozens of streams with long titles)
            // can emit more than the ~64 KB OS pipe buffer, and ffprobe would
            // block writing to a full pipe forever if we only read after waitFor
            // — the whole call would then dead-time out at 20 s. Reading in
            // parallel keeps the pipe drained so the process can actually exit.
            final Process proc = process;
            final StringBuilder sb = new StringBuilder();
            Thread drain = new Thread(() -> {
                try {
                    byte[] bytes = proc.getInputStream().readAllBytes();
                    synchronized (sb) {
                        sb.append(new String(bytes, StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                    // Process killed or stream closed; leave what we read.
                }
            }, "MinecraftVideo-ffprobe-reader");
            drain.setDaemon(true);
            drain.start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                logger.warning("ffprobe timed out listing subtitle tracks of '" + source + "'.");
                return null; // probe failed: let the caller retry, do not cache
            }
            drain.join(1000); // pipe is at EOF once the process exited; finish reading
            String out;
            synchronized (sb) {
                out = sb.toString();
            }
            int i = 0;
            for (String line : out.split("\n")) {
                String row = line.strip();
                if (row.isEmpty()) {
                    continue;
                }
                // csv=p=0 => comma-separated codec_name,language,title (missing
                // trailing fields simply absent). Split with a limit so a comma
                // inside a title keeps the rest of the title.
                String[] parts = row.split(",", 3);
                String codec = parts.length > 0 ? parts[0].trim() : "";
                String lang = parts.length > 1 ? parts[1].trim() : "";
                String title = parts.length > 2 ? parts[2].trim() : "";
                tracks.add(new SubtitleTrack(i++, codec, lang, title));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return null; // probe interrupted: failure, not "no tracks"
        } catch (IOException e) {
            logger.warning("ffprobe (" + ffprobePath + ") could not list subtitles of '"
                    + source + "' (" + e.getMessage() + ").");
            return null; // probe failed (e.g. ffprobe missing): let the caller retry
        }
        return tracks;
    }

    /**
     * Derives the ffprobe path from the configured ffmpeg path (same rule as
     * {@link AudioStream}): {@code ffmpeg -> ffprobe}, keeping the directory and
     * any {@code .exe}; falls back to {@code ffprobe} on PATH if the name has no
     * "ffmpeg" in it.
     */
    private static String siblingFfprobe(String ffmpegPath) {
        java.io.File f = new java.io.File(ffmpegPath);
        String name = f.getName();
        if (!name.toLowerCase(Locale.ROOT).contains("ffmpeg")) {
            return "ffprobe";
        }
        String probeName = name.replaceFirst("(?i)ffmpeg", "ffprobe");
        String parent = f.getParent();
        return parent == null ? probeName : new java.io.File(parent, probeName).getPath();
    }
}
