#!/usr/bin/env python3
"""Genere l'effet de debug color_id : ecrit l'id de couleur map dans chaque aplat.

    python3 gen_color_id.py            # -> 3 .fsh + post_effect/color_id.json

En jeu : /posteffect add @s minecraft:color_id  (puis remove pour l'enlever).
Chaque aplat de couleur recoit le nombre stocke dans la carte a cet endroit,
c'est-a-dire base * 4 + nuance -- base = id / 4 (la cle de vanilla_map_colors.json),
nuance = id % 4 dans l'ordre {180, 220, 255, 135}.

NE PAS cumuler avec custom_palette : celui-la remplace les couleurs a l'ecran, donc
la detection par egalite stricte ne retomberait plus sur rien.
"""
import json
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
POST = os.path.join(ROOT, "resourcepack", "assets", "minecraft", "shaders", "post")
EFF = os.path.join(ROOT, "resourcepack", "assets", "minecraft", "post_effect")
VANILLA = os.path.join(ROOT, "..", "..", "src", "main", "resources", "vanilla_map_colors.json")

SHADES = (180, 220, 255, 135)
LMAX = 9           # bloc homogene detecte jusqu'a 2^9 = 512 px
MIN_LEVEL = 4      # sous 16 px un nombre a 3 chiffres n'est plus lisible
SC_MAX = 8         # chiffre plafonne a 5*8 px de haut
# Chiffres 3x5, bit (14 - (ligne * 3 + colonne)) allume, ligne 0 = HAUT.
FONT = [31599, 11415, 29671, 29647, 23497, 31183, 31215, 29257, 31727, 31695]
EXT = "#version 330\n#extension GL_ARB_separate_shader_objects : require\n"
SQ = "minecraft:core/screenquad"


def palette_pairs():
    """[(cle rgb packee, id map)] trie. L'id map = base * 4 + nuance, jamais 0."""
    cl = json.load(open(VANILLA, encoding="utf-8"))["colorList"]
    seen = {}
    for k, h in cl.items():
        base = int(k)
        r, g, b = (int(h.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4))
        for s, m in enumerate(SHADES):
            c = (r * m // 255, g * m // 255, b * m // 255)
            seen.setdefault((c[0] << 16) | (c[1] << 8) | c[2], base * 4 + s)
    return sorted(seen.items())


def rows(vals, per):
    return ",\n".join("    " + ", ".join(str(v) for v in vals[i:i + per])
                      for i in range(0, len(vals), per))


def main():
    pairs = palette_pairs()
    n = len(pairs)

    seed = EXT + f"""
// GENERE par gen_color_id.py -- ne pas editer a la main.
// Passe 1/3 : identifie la couleur map sous chaque pixel. Meme detection que
// better_color (egalite 8 bits stricte, recherche binaire sur cles packees
// r<<16|g<<8|b triees), mais on sort l'ID stocke dans la carte.
//
// Sortie encodee en RGBA8, relue par les passes suivantes :
//   R = id de couleur map, 0 = pas une couleur map (le monde autour)
//       -- l'id 0 n'existe pas : la palette part de la base 1, donc id >= 4.
//   G = plus grand niveau de bloc homogene connu (rempli par les passes de niveau)
//   B = 1 tant que le bloc courant est homogene, 0 des qu'il ne l'est plus

uniform sampler2D InSampler;

layout(location = 0) out vec4 fragColor;

const int VMC_N = {n};
const int VMC_KEY[VMC_N] = int[VMC_N](
{rows([p[0] for p in pairs], 8)}
);
const int VMC_ID[VMC_N] = int[VMC_N](
{rows([p[1] for p in pairs], 16)}
);

int vmcFind(int key) {{
    int lo = 0, hi = VMC_N - 1;
    while (lo <= hi) {{
        int mid = (lo + hi) >> 1;
        int k = VMC_KEY[mid];
        if (k == key) return mid;
        if (k < key) lo = mid + 1; else hi = mid - 1;
    }}
    return -1;
}}

void main() {{
    ivec3 c = ivec3(round(texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0).rgb * 255.0));
    int i = vmcFind((c.r << 16) | (c.g << 8) | c.b);
    fragColor = vec4(i >= 0 ? float(VMC_ID[i]) / 255.0 : 0.0, 0.0, i >= 0 ? 1.0 : 0.0, 1.0);
}}
"""

    level = EXT + """
// GENERE par gen_color_id.py -- ne pas editer a la main.
// Passe 2/3, rejouee une fois par niveau (Level = 1..9) : grossit le carre homogene.
//
// POURQUOI des blocs ALIGNES et pas le plus gros carre homogene au sens strict : un
// bloc aligne de 2^L px est homogene SSI ses 4 sous-blocs de 2^(L-1) le sont et
// portent le meme id. C'est une recurrence a 4 taps par passe, et surtout tous les
// pixels d'un meme bloc en tirent la MEME reponse -- donc aucune propagation n'est
// necessaire, chaque pixel sait seul dans quel carre il est et ou en est le centre.
// La version exacte demanderait une transformee de distance de Chebyshev suivie d'un
// jump flooding pour diffuser l'argmax, soit ~35 passes au lieu de 12.
//
// PRIX ASSUME : le carre est cale sur une grille, donc au pire deux fois plus petit
// que l'optimal, et une grande zone plate recoit une etiquette par bloc au lieu d'une
// seule. Pour lire des ids c'est plutot un avantage : le nombre est lisible partout.

uniform sampler2D InSampler;

layout(std140) uniform LevelConfig {
    int Level;
};

layout(location = 0) out vec4 fragColor;

int idAt(ivec2 p) {
    return int(round(texelFetch(InSampler, p, 0).r * 255.0));
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InSampler, 0);
    int s = 1 << Level;
    int sub = s >> 1;
    ivec2 o = (p / s) * s;              // origine du bloc aligne qui me contient

    // Les 4 sous-blocs, lus a leur origine : B et G sont constants sur tout un
    // sous-bloc, donc un tap par sous-bloc suffit. clamp() parce qu'un bloc de bord
    // deborde -- ce cas est de toute facon rejete par `inside` juste apres.
    ivec2 c0 = clamp(o,                     ivec2(0), size - 1);
    ivec2 c1 = clamp(o + ivec2(sub, 0),     ivec2(0), size - 1);
    ivec2 c2 = clamp(o + ivec2(0, sub),     ivec2(0), size - 1);
    ivec2 c3 = clamp(o + ivec2(sub, sub),   ivec2(0), size - 1);

    bool allUniform = texelFetch(InSampler, c0, 0).b > 0.5
                   && texelFetch(InSampler, c1, 0).b > 0.5
                   && texelFetch(InSampler, c2, 0).b > 0.5
                   && texelFetch(InSampler, c3, 0).b > 0.5;
    int id0 = idAt(c0);
    bool sameId = id0 == idAt(c1) && id0 == idAt(c2) && id0 == idAt(c3);
    // Un bloc qui sort de l'ecran est refuse, sinon l'etiquette serait coupee au bord.
    bool inside = o.x + s <= size.x && o.y + s <= size.y;

    bool ok = allUniform && sameId && inside;
    vec4 me = texelFetch(InSampler, p, 0);
    // G ne recule jamais : chaque pixel garde le plus grand niveau ou SON bloc tenait.
    fragColor = vec4(me.r, ok ? float(Level) / 255.0 : me.g, ok ? 1.0 : 0.0, 1.0);
}
"""

    draw = EXT + f"""
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
// nuance = id % 4 dans l'ordre {{180, 220, 255, 135}}.

uniform sampler2D InSampler;    // donnees : id + niveau
uniform sampler2D SrcSampler;   // image d'origine

layout(location = 0) out vec4 fragColor;

const int MIN_LEVEL = {MIN_LEVEL};
// Taille du chiffre plafonnee : sinon un bloc de 512 px ecrit un nombre de 512 px.
// Decouple la detection (le bloc) du rendu (l'etiquette).
const int SC_MAX = {SC_MAX};
// Chiffres 3x5, bit (14 - (ligne * 3 + colonne)), ligne 0 = HAUT.
const int FONT[10] = int[10]({", ".join(str(f) for f in FONT)});

// clamp : au bord de l'ecran le voisin manquant devient le pixel lui-meme, donc egal --
// sinon un cadre parasite ferait le tour de l'ecran.
int idAt(ivec2 p, ivec2 size) {{
    return int(round(texelFetch(InSampler, clamp(p, ivec2(0), size - 1), 0).r * 255.0));
}}

// Bord de la zone homogene : voisinage a 4, donc un trace fin qui suit les diagonales en
// escalier. Le pixel d'en face est lui aussi marque, ce qui donne un trait de 2 px au total.
bool onEdge(ivec2 p, int id) {{
    ivec2 size = textureSize(InSampler, 0);
    return idAt(p + ivec2(1, 0), size) != id
        || idAt(p - ivec2(1, 0), size) != id
        || idAt(p + ivec2(0, 1), size) != id
        || idAt(p - ivec2(0, 1), size) != id;
}}

void main() {{
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 src = texelFetch(SrcSampler, p, 0).rgb;
    vec4 d = texelFetch(InSampler, p, 0);
    int id = int(round(d.r * 255.0));
    if (id == 0) {{
        fragColor = vec4(src, 1.0);     // pas une couleur map : le monde passe tel quel
        return;
    }}
    int level = int(round(d.g * 255.0));
    if (level < MIN_LEVEL) {{
        // Trop petit pour un nombre, mais la zone merite quand meme son contour.
        fragColor = vec4(onEdge(p, id) ? src * 0.6 : src, 1.0);
        return;
    }}

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
    if (q.x >= t.x && q.x < t.x + text.x && q.y >= t.y && q.y < t.y + text.y) {{
        int fx = (q.x - t.x) / sc;
        int fy = (q.y - t.y) / sc;
        int di = fx / 4;
        int cx = fx - di * 4;
        if (cx < 3) {{
            int power = 1;
            for (int k = 0; k < digits - 1 - di; k++) {{
                power *= 10;
            }}
            int glyph = FONT[(id / power) % 10];
            // gl_FragCoord.y monte, la police descend : on retourne la ligne.
            lit = ((glyph >> (14 - ((4 - fy) * 3 + cx))) & 1) == 1;
            if (lit) {{
                col = dot(src, vec3(0.2126, 0.7152, 0.0722)) > 0.5 ? vec3(0.0) : vec3(1.0);
            }}
        }}
    }}
    if (!lit && onEdge(p, id)) {{
        col = src * 0.6;
    }}
    fragColor = vec4(col, 1.0);
}}
"""

    for name, body in (("vmc_id_seed.fsh", seed), ("vmc_id_level.fsh", level),
                       ("vmc_id_draw.fsh", draw)):
        with open(os.path.join(POST, name), "w") as f:
            f.write(body.lstrip("\n"))

    passes = [{"vertex_shader": SQ, "fragment_shader": "minecraft:post/vmc_id_seed",
               "inputs": [{"sampler_name": "In", "target": "minecraft:main", "bilinear": False}],
               "output": "a"}]
    for lvl in range(1, LMAX + 1):
        src, dst = ("a", "b") if lvl % 2 == 1 else ("b", "a")
        passes.append({"vertex_shader": SQ, "fragment_shader": "minecraft:post/vmc_id_level",
                       "inputs": [{"sampler_name": "In", "target": src, "bilinear": False}],
                       "uniforms": {"LevelConfig": [{"name": "Level", "type": "int",
                                                     "value": lvl}]},
                       "output": dst})
    data = "b" if LMAX % 2 == 1 else "a"
    free = "a" if data == "b" else "b"
    # On n'ecrit pas dans main en lisant main (interdit dans une meme passe) : le dessin
    # part dans le buffer libre, un blit vanilla le recopie ensuite.
    passes.append({"vertex_shader": SQ, "fragment_shader": "minecraft:post/vmc_id_draw",
                   "inputs": [{"sampler_name": "In", "target": data, "bilinear": False},
                              {"sampler_name": "Src", "target": "minecraft:main",
                               "bilinear": False}],
                   "output": free})
    passes.append({"vertex_shader": SQ, "fragment_shader": "minecraft:post/blit",
                   "inputs": [{"sampler_name": "In", "target": free, "bilinear": False}],
                   "uniforms": {"BlitConfig": [{"name": "ColorModulate", "type": "vec4",
                                                "value": [1.0, 1.0, 1.0, 1.0]}]},
                   "output": "minecraft:main"})

    path = os.path.join(EFF, "color_id.json")
    with open(path, "w") as f:
        json.dump({"targets": {"a": {}, "b": {}}, "passes": passes}, f, indent=2)
        f.write("\n")
    print(f"OK: {n} couleurs (ids {pairs[0][1]}..{max(p[1] for p in pairs)}), "
          f"{len(passes)} passes -> color_id.json + 3 fsh")


if __name__ == "__main__":
    main()
