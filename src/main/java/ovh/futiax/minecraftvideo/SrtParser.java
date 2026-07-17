package ovh.futiax.minecraftvideo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Incremental parser for the SubRip (SRT) text that {@code ffmpeg -f srt}
 * writes. It is fed one line at a time (as ffmpeg's stdout is read) and emits a
 * {@link Cue} whenever a complete block has been seen, so subtitles can be
 * consumed while the source is still being read.
 *
 * <p>An SRT block is:
 * <pre>
 *   1                                  (an optional numeric index)
 *   00:00:20,000 --&gt; 00:00:24,400      (start --&gt; end, optional trailing coords)
 *   First line of text
 *   Second line of text
 *                                      (blank line terminates the block)
 * </pre>
 *
 * <p>Robustness: a UTF-8 BOM on the first line is stripped, CR (from CRLF) is
 * trimmed off every line, the leading index line is optional (some muxers omit
 * it), inline markup ({@code <i> <b> <u> <font ...>} and closing tags) is
 * removed, and SubRip's {@code {\anX}}/{@code {\pos(...)}} ASS-style override
 * braces are dropped. Malformed timestamp lines make the current block be
 * discarded rather than throwing.
 *
 * <p>Not thread-safe; {@link SubtitleStream} drives it from its single reader
 * thread and copies out immutable {@link Cue}s.
 */
public final class SrtParser {

    /** One subtitle cue: [startMillis, endMillis) of stream-local time + text. */
    public record Cue(long startMillis, long endMillis, String text) {}

    /** hh:mm:ss,mmm --> hh:mm:ss,mmm (comma or dot for the millis separator). */
    private static final Pattern TIMECODE = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*"
                    + "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})");
    /** Inline HTML-ish tags SRT allows: <i> </i> <b> <font color=...> etc. */
    private static final Pattern TAGS = Pattern.compile("</?[a-zA-Z][^>]*>");
    /** ASS/SSA override blocks that leak into SRT, e.g. {\an8} or {\pos(1,2)}. */
    private static final Pattern OVERRIDE_BRACES = Pattern.compile("\\{[^}]*}");

    private boolean bomStripped = false;
    private long pendingStart = -1;
    private long pendingEnd = -1;
    private final List<String> pendingText = new ArrayList<>();

    /**
     * Feeds one raw line (without its line terminator). Returns a completed
     * {@link Cue} when this line closes a block (a blank line), otherwise
     * {@code null}. Call {@link #flush()} at end of stream to emit a trailing
     * block that was not terminated by a blank line.
     */
    public Cue accept(String rawLine) {
        String line = rawLine;
        if (!bomStripped) {
            // Strip a UTF-8 BOM that ffmpeg may put on the very first byte.
            if (!line.isEmpty() && line.charAt(0) == '﻿') {
                line = line.substring(1);
            }
            bomStripped = true;
        }
        // Trim a trailing CR from CRLF line endings.
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
            line = line.substring(0, line.length() - 1);
        }

        if (line.isBlank()) {
            return flush(); // blank line: end of the current block
        }

        // Only treat a timecode line as the START of a new cue when no cue is
        // currently accumulating its timecodes (pendingStart < 0). Once a cue's
        // timecodes are pending, blocks are separated by a blank line, so a line
        // that merely CONTAINS a timecode-looking substring (a subtitle quoting
        // "00:00:01,000 --> 00:00:02,000", or a garbage track) is that cue's
        // text — not a new header. find() over the whole line would otherwise
        // silently drop the real cue by clearing its text and timecodes.
        if (pendingStart < 0) {
            Matcher m = TIMECODE.matcher(line);
            if (m.find()) {
                // A timecode line starts a cue; the (optional) index line before
                // it was ignored because no timecode was pending, so any prior
                // stray text is dropped by resetting the block.
                pendingText.clear();
                pendingStart = toMillis(m.group(1), m.group(2), m.group(3), m.group(4));
                pendingEnd = toMillis(m.group(5), m.group(6), m.group(7), m.group(8));
                return null;
            }
            // No timecode pending and this isn't one: a leading index line (or
            // stray text before the first cue); drop it.
            return null;
        }

        // A cue's timecodes are pending: everything up to the next blank line is
        // its text (this also naturally skipped the leading numeric index line,
        // which arrived while pendingStart was still < 0).
        String cleaned = clean(line);
        if (!cleaned.isEmpty()) {
            pendingText.add(cleaned);
        }
        return null;
    }

    /**
     * Emits the block accumulated so far (if it has a valid timecode and text)
     * and resets for the next one. Call at EOF to flush a final unterminated
     * block. Returns {@code null} when there is nothing to emit.
     */
    public Cue flush() {
        Cue cue = null;
        if (pendingStart >= 0 && pendingEnd > pendingStart && !pendingText.isEmpty()) {
            cue = new Cue(pendingStart, pendingEnd, String.join("\n", pendingText));
        }
        pendingStart = -1;
        pendingEnd = -1;
        pendingText.clear();
        return cue;
    }

    /** Removes inline markup and ASS override braces from one text line. */
    private static String clean(String line) {
        String s = OVERRIDE_BRACES.matcher(line).replaceAll("");
        s = TAGS.matcher(s).replaceAll("");
        return s.strip();
    }

    private static long toMillis(String h, String m, String s, String millis) {
        // millis may be 1-3 digits; right-pad to exactly 3 (SubRip uses 3, but
        // some muxers emit fewer, e.g. "20,5" meaning 500 ms).
        String ms = (millis + "000").substring(0, 3);
        return (Long.parseLong(h) * 3600L + Long.parseLong(m) * 60L + Long.parseLong(s)) * 1000L
                + Long.parseLong(ms);
    }
}
