package ovh.futiax.minecraftvideo;

import java.util.Locale;
import java.util.Set;

/**
 * One embedded subtitle stream of a source, as reported by ffprobe: its
 * subtitle-relative index (the {@code n} in {@code -map 0:s:n}), codec, and
 * optional language/title tags.
 *
 * <p>Only text-based codecs can be rendered as an in-world overlay: they carry
 * plain text that ffmpeg can transcode to SRT. Bitmap codecs (PGS/VOBSUB/DVB)
 * are pre-rendered images with no text to extract, so they are flagged
 * {@link #textBased() unsupported} and {@code /video subs <n>} refuses them.
 */
public record SubtitleTrack(int index, String codec, String language, String title) {

    /**
     * Image-based subtitle codecs ffprobe may report. These have no extractable
     * text (they are bitmaps burned over the frame), so the TextDisplay overlay
     * cannot show them; we detect and refuse them cleanly instead of launching
     * an ffmpeg that would only error out.
     */
    private static final Set<String> BITMAP_CODECS = Set.of(
            "hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle",
            "dvbsub", "pgssub", "xsub");

    /** Whether this track carries extractable text (so it can be overlaid). */
    public boolean textBased() {
        return codec != null && !BITMAP_CODECS.contains(codec.toLowerCase(Locale.ROOT));
    }

    /**
     * A one-line description for {@code /video subs list}, e.g.
     * {@code "0: subrip [fre] \"Full subtitles\""} or, for a bitmap track,
     * {@code "2: hdmv_pgs_subtitle [eng] (bitmap track, not supported)"}.
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(index).append(": ").append(codec != null ? codec : "?");
        if (language != null && !language.isBlank()) {
            sb.append(" [").append(language).append(']');
        }
        if (title != null && !title.isBlank()) {
            sb.append(" \"").append(title).append('"');
        }
        if (!textBased()) {
            sb.append(" (bitmap track, not supported)");
        }
        return sb.toString();
    }
}
