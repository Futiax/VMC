package ovh.futiax.minecraftvideo;

import java.util.List;
import java.util.Locale;

/**
 * Audio channel layout for a playback: how many channels ffmpeg decodes and
 * how many world-anchored SVC "speakers" they are mapped onto.
 *
 * <ul>
 *   <li>{@link #MONO} — 1 decoded channel, 1 speaker at the screen center.</li>
 *   <li>{@link #STEREO} — 2 decoded channels, 2 speakers at the screen's
 *       left/right edges (fixed "cinema" image).</li>
 *   <li>{@link #SURROUND} — 6 decoded channels (ffmpeg {@code -ac 6} = 5.1:
 *       FL FR FC LFE BL BR; ffmpeg up/downmixes any source layout, including
 *       an Atmos bed, to 5.1), mapped onto 5 speakers: front L/R at the screen
 *       edges, center at the screen center (LFE folded in), rears behind the
 *       audience. Stereo sources upmixed by ffmpeg keep the image in FL/FR.</li>
 * </ul>
 */
public enum AudioMode {
    MONO(1, 1),
    STEREO(2, 2),
    SURROUND(6, 5);

    /** Channel count requested from ffmpeg ({@code -ac N}). */
    private final int decodeChannels;
    /** Number of world-anchored SVC channels the decoded audio maps onto. */
    private final int speakers;

    AudioMode(int decodeChannels, int speakers) {
        this.decodeChannels = decodeChannels;
        this.speakers = speakers;
    }

    public int decodeChannels() {
        return decodeChannels;
    }

    public int speakers() {
        return speakers;
    }

    /** Lowercase name used in config.yml and command arguments. */
    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Config names accepted by {@code /video option audio}. */
    public static List<String> configNames() {
        return List.of("mono", "stereo", "surround");
    }

    /** Any unknown value (including a bad config edit) falls back to MONO. */
    public static AudioMode fromConfig(String value) {
        if (value == null) {
            return MONO;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "stereo" -> STEREO;
            case "surround" -> SURROUND;
            default -> MONO;
        };
    }
}
