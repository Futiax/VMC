package ovh.futiax.minecraftvideo;

import java.util.List;
import java.util.Locale;

// Layout de canaux audio : combien de canaux ffmpeg decode et comment ils tombent sur les
// enceintes SVC ancrees au monde.
//   MONO     1 canal, 1 enceinte au centre de l'ecran
//   STEREO   2 canaux, gauche/droite aux bords de l'ecran
//   SURROUND 6 canaux (5.1) repartis autour du public. Passe par le filtre surround de
//            ffmpeg pour upmixer les sources stereo/mono, sinon FC/LFE/BL/BR sont muets.
public enum AudioMode {
    MONO(1, 1),
    STEREO(2, 2),
    SURROUND(6, 6);

    private final int decodeChannels;
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

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static List<String> configNames() {
        return List.of("mono", "stereo", "surround");
    }

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
