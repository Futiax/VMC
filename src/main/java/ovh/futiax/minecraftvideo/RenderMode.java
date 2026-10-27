package ovh.futiax.minecraftvideo;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;

// Comment mcmm transforme l'image source en couleurs de map.
//   NORMAL  mise a l'echelle bicubique de ffmpeg, couleur de palette la plus proche
//   DITHER  idem + tramage Floyd-Steinberg (--dither) : moins de banding, mais le bruit
//           change a chaque frame et le skip des tuiles inchangees ne sert plus a rien
//   STRICT  aucune couleur inventee (--strict) : facteur ENTIER au plus proche voisin, image
//           centree. Pour le pixel art et les QR : en NORMAL, une mire QR affichee a la
//           mauvaise taille passe de 9 a 31 couleurs de palette, des gris a chaque bord de
//           module. Le prix : l'image ne remplit l'ecran que si sa taille tombe pile.
//   DATA    EXPERIMENTAL (--data) : plus une image. mcmm la compresse (DCT 8x8 a debit fixe) en
//           symboles de couleur sur un mur de maps (DataWall), que seul le post effect de ce mur
//           dans le pack VMC sait decoder (canva au centre de l'ecran du joueur). Sans le pack,
//           du bruit.
public enum RenderMode {
    NORMAL(null),
    DITHER("--dither"),
    STRICT("--strict"),
    DATA("--data");

    private final String mcmmFlag;

    RenderMode(String mcmmFlag) {
        this.mcmmFlag = mcmmFlag;
    }

    // null = rien a passer a mcmm
    public String mcmmFlag() {
        return mcmmFlag;
    }

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static List<String> configNames() {
        return List.of("normal", "dither", "strict", "data");
    }

    // null si ce n'est pas un mode : c'est a l'appelant de choisir entre message et repli
    public static RenderMode parse(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "normal" -> NORMAL;
            case "dither" -> DITHER;
            case "strict" -> STRICT;
            case "data" -> DATA;
            default -> null;
        };
    }

    // render-mode, et a defaut l'ancien booleen "dither" : une config deja deployee le garde
    // (saveDefaultConfig n'ecrase rien). isSet et pas getString : getString renverrait le
    // "normal" par defaut du config.yml du jar et masquerait un "dither: true" existant.
    public static RenderMode fromConfig(ConfigurationSection config) {
        if (config.isSet("render-mode")) {
            RenderMode mode = parse(config.getString("render-mode"));
            if (mode != null) {
                return mode;
            }
        }
        return config.getBoolean("dither", false) ? DITHER : NORMAL;
    }

    // Les murs du rendu data (config data-wall, /vmc data). CE SONT ceux de VD_LAYOUTS
    // (vmc_data.h, mcmm) et de WALLS (gen_dct.py, le decodeur du pack) : le decodeur n'existe que
    // pour ces tailles, un post effect par mur, d'ou la taille forcee par PlaybackSession.
    // Un symbole = 1 pixel de map depuis le 16/10 (avant : 2x2, mesure en jeu : meme lecture a
    // moitie de distance, moitie moins d'octets sur le reseau, 4x moins de maps).
    //   WAN  4x2  canva 848x464, jusqu'a ~9 Mbit/s a 10 fps
    //   LAN  5x3  canva 1232x720 (2,25x plus de donnees), jusqu'a ~19 Mbit/s a 10 fps. Le plus grand mur
    //             qui se lit encore en 1080p : il doit tenir EN ENTIER a l'ecran avec >= ~2 px par
    //             symbole, soit de 2,1 a 3,1 blocs en FOV 70 (mesure)
    public enum DataWall {
        WAN(4, 2, "minecraft:vmc_data"),
        LAN(5, 3, "minecraft:vmc_data_lan");

        private final int width;
        private final int height;
        private final String effect;

        DataWall(int width, int height, String effect) {
            this.width = width;
            this.height = height;
            this.effect = effect;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        // post effect client qui decode ce mur, pousse a la place de post-effect (cf PostEffects)
        public String effect() {
            return effect;
        }

        public String configName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static List<String> configNames() {
            return List.of("wan", "lan");
        }

        public static DataWall parse(String value) {
            if (value == null) {
                return null;
            }
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "wan" -> WAN;
                case "lan" -> LAN;
                default -> null;
            };
        }

        // data-wall, WAN a defaut (cle absente ou valeur inconnue)
        public static DataWall fromConfig(ConfigurationSection config) {
            DataWall wall = parse(config.getString("data-wall"));
            return wall != null ? wall : WAN;
        }
    }
}
