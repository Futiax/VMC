#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_color_id.py -- ne pas editer a la main.
// Passe 3/3 : contour exact de la zone homogene, et son id ecrit dedans.
//
// Deux choses DIFFERENTES ici, et c'est volontaire :
//   - le CONTOUR suit la vraie zone : un pixel est au bord des qu'un de ses 4 voisins porte
//     un autre id. Aucun rapport avec les blocs alignes, aucune passe en plus, 4 taps. C'est
//     le trace exact de la region, courbes et diagonales comprises.
//   - le BLOC ALIGNE calcule par les passes de niveau ne sert plus qu'a PLACER le nombre :
//     c'est un carre garanti homogene, donc un endroit ou les chiffres ne debordent pas de
//     la zone. Le contour, lui, ne lui doit rien.
//
// Le nombre est l'id MAP complet : base = id / 4 (cle de vanilla_map_colors.json),
// nuance = id % 4 dans l'ordre {180, 220, 255, 135}.

uniform sampler2D InSampler;    // donnees : id + niveau
uniform sampler2D SrcSampler;   // image d'origine

layout(location = 0) out vec4 fragColor;

const int MIN_LEVEL = 4;
// Taille du chiffre plafonnee : sinon un bloc de 512 px ecrit un nombre de 512 px.
// Decouple la detection (le bloc) du rendu (l'etiquette).
const int SC_MAX = 8;
// Chiffres 3x5, bit (14 - (ligne * 3 + colonne)), ligne 0 = HAUT.
const int FONT[10] = int[10](31599, 11415, 29671, 29647, 23497, 31183, 31215, 29257, 31727, 31695);

// clamp : au bord de l'ecran le voisin manquant devient le pixel lui-meme, donc egal --
// sinon un cadre parasite ferait le tour de l'ecran.
int idAt(ivec2 p, ivec2 size) {
    return int(round(texelFetch(InSampler, clamp(p, ivec2(0), size - 1), 0).r * 255.0));
}

// Bord de la zone homogene : voisinage a 4, donc un trace fin qui suit les diagonales en
// escalier. Le pixel d'en face est lui aussi marque, ce qui donne un trait de 2 px au total.
bool onEdge(ivec2 p, int id) {
    ivec2 size = textureSize(InSampler, 0);
    return idAt(p + ivec2(1, 0), size) != id
        || idAt(p - ivec2(1, 0), size) != id
        || idAt(p + ivec2(0, 1), size) != id
        || idAt(p - ivec2(0, 1), size) != id;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 src = texelFetch(SrcSampler, p, 0).rgb;
    vec4 d = texelFetch(InSampler, p, 0);
    int id = int(round(d.r * 255.0));
    if (id == 0) {
        fragColor = vec4(src, 1.0);     // pas une couleur map : le monde passe tel quel
        return;
    }
    int level = int(round(d.g * 255.0));
    if (level < MIN_LEVEL) {
        // Trop petit pour un nombre, mais la zone merite quand meme son contour.
        fragColor = vec4(onEdge(p, id) ? src * 0.6 : src, 1.0);
        return;
    }

    int s = 1 << level;
    ivec2 o = (p / s) * s;
    ivec2 q = p - o;                        // coordonnees dans le bloc

    int digits = id < 10 ? 1 : (id < 100 ? 2 : 3);
    int fw = digits * 4 - 1;                // 3 px de glyphe + 1 d'espace, sauf le dernier
    int lim = (s * 85) / 100;
    int sc = clamp(min(lim / fw, lim / 5), 1, SC_MAX);
    ivec2 text = ivec2(fw, 5) * sc;
    ivec2 t = (ivec2(s) - text) / 2;        // texte centre dans le bloc

    vec3 col = src;
    bool lit = false;
    // Test AVANT la division : une division entiere de negatif tronque vers zero, ce
    // qui ferait rentrer dans la boite des pixels qui sont dehors.
    if (q.x >= t.x && q.x < t.x + text.x && q.y >= t.y && q.y < t.y + text.y) {
        int fx = (q.x - t.x) / sc;
        int fy = (q.y - t.y) / sc;
        int di = fx / 4;
        int cx = fx - di * 4;
        if (cx < 3) {
            int power = 1;
            for (int k = 0; k < digits - 1 - di; k++) {
                power *= 10;
            }
            int glyph = FONT[(id / power) % 10];
            // gl_FragCoord.y monte, la police descend : on retourne la ligne.
            lit = ((glyph >> (14 - ((4 - fy) * 3 + cx))) & 1) == 1;
            if (lit) {
                col = dot(src, vec3(0.2126, 0.7152, 0.0722)) > 0.5 ? vec3(0.0) : vec3(1.0);
            }
        }
    }
    if (!lit && onEdge(p, id)) {
        col = src * 0.6;
    }
    fragColor = vec4(col, 1.0);
}
