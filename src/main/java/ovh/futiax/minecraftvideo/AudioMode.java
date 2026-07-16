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
 *   <li>{@link #SURROUND} — 6 decoded channels (5.1: FL FR FC LFE BL BR),
 *       mapped onto 6 speakers: front L/R at the screen edges, center at the
 *       screen center, a SUBWOOFER (the LFE channel, boosted +6 dB — film
 *       mixes play LFE hot) at the base of the screen, and rears behind the
 *       audience. A 5.1+ source (including an Atmos bed) maps its bed
 *       directly; a mono/stereo source gets a REAL upmix (ffmpeg
 *       {@code surround} filter: center, rears and LFE are synthesized), so
 *       every speaker plays — see {@link AudioStream}.</li>
 * </ul>
 */
public enum AudioMode {
    MONO(1, 1),
    STEREO(2, 2),
    SURROUND(6, 6);

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
