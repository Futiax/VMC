package ovh.futiax.mcdata.channel;

import java.util.Arrays;

// Encodage des donnees dans une map 128x128 vanilla. Chaque texel porte un demi-octet (4 bits)
// choisi parmi 16 couleurs de map vanilla fixes (NIBBLES), poids faible d'abord.
//
// Layout de la map :
//   texels 0-3   : signature (4 couleurs fixes, reconnues par le shader core text.vsh)
//   texels 4-11  : compteur (uint32, 8 nibbles)
//   texels 12-19 : yaw serveur en centiemes de degre, normalise [-180, 180) (int32 signe)
//   texels 20-27 : pitch serveur en centiemes de degre (int32 signe)
//   texels 28+   : payload libre (4 nibbles = 16 bits par valeur)
//
// Le shader text.fsh recopie la map pixel-par-pixel dans le coin bas-gauche de l'ecran,
// et le post-effect la lit a des positions fixes.
public final class DataEncoder {

    private DataEncoder() {}

    // Ids de couleur de map (base * 4 + nuance). Le shader core relit ces tableaux pour
    // ecrire les RGB correspondants : les garder sur une ligne chacun.
    public static final int[] SIGNATURE = {73, 77, 81, 85};
    public static final int[] NIBBLES = {10, 14, 18, 22, 26, 30, 34, 38, 42, 46, 50, 54, 58, 62, 66, 70};

    public static final int HEADER_WORDS = 3;          // counter, yaw, pitch
    public static final int PAYLOAD_START = SIGNATURE.length + HEADER_WORDS * 8;  // texel 28

    // Construit l'image 128x128 de la balise.
    public static byte[] encode(int counter, float yaw, float pitch, int[] payload) {
        byte[] pixels = new byte[128 * 128];
        Arrays.fill(pixels, (byte) NIBBLES[0]);
        for (int i = 0; i < SIGNATURE.length; i++) {
            pixels[i] = (byte) SIGNATURE[i];
        }
        int normalizedYaw = Math.round((((yaw % 360f) + 540f) % 360f - 180f) * 100f);
        int[] words = {counter, normalizedYaw, Math.round(pitch * 100f)};
        int at = SIGNATURE.length;
        for (int word : words) {
            for (int n = 0; n < 8; n++) {
                pixels[at++] = (byte) NIBBLES[(word >>> (4 * n)) & 15];
            }
        }
        for (int value : payload) {
            if (at + 4 > pixels.length) {
                break;
            }
            for (int n = 0; n < 4; n++) {
                pixels[at++] = (byte) NIBBLES[(value >>> (4 * n)) & 15];
            }
        }
        return pixels;
    }

    // Ecrit une valeur 16 bits non signee a partir du texel `at`, renvoie le texel suivant.
    public static int putU16(byte[] pixels, int at, int value) {
        for (int n = 0; n < 4; n++) {
            pixels[at++] = (byte) NIBBLES[(value >>> (4 * n)) & 15];
        }
        return at;
    }

    // Ecrit une valeur 32 bits (4 octets = 8 nibbles), poids faible d'abord.
    public static int putU32(byte[] pixels, int at, int value) {
        for (int n = 0; n < 8; n++) {
            pixels[at++] = (byte) NIBBLES[(value >>> (4 * n)) & 15];
        }
        return at;
    }
}
