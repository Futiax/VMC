#!/usr/bin/env python3
"""Genere better_color.fsh + better_color_debug.fsh depuis vanilla_map_colors.json.

Principe : mcmm quantifie la video sur la palette map vanilla (61 couleurs de
base x 4 nuances {180,220,255,135} = 244 RGB 8-bit exacts). Les GLOW item
frames rendent la map fullbright, donc ces valeurs atterrissent TELLES QUELLES
dans le framebuffer. Un post-effect peut alors les detecter par egalite 8-bit
stricte et les remplacer par une "meilleure" couleur — sans mod client, juste
un resource pack.

better_color.fsh       : remap palette -> palette amelioree (boost chroma OKLab
                         + micro-contraste), le reste de l'image passe intact.
better_color_debug.fsh : pixels detectes -> magenta, le reste assombri, pour
                         verifier en jeu la couverture exacte de la detection.

Relancer apres toute modif :  python3 gen_shaders.py
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
PALETTE_JSON = os.path.join(ROOT, "..", "..", "src", "main", "resources",   # shader_test a demenage dans working_on/
                            "vanilla_map_colors.json")
POST_DIR = os.path.join(ROOT, "resourcepack", "assets", "minecraft",
                        "shaders", "post")

# Meme ordre / meme arithmetique entiere que mcmm.c ET que le client Java :
# couleur affichee = floor(base * shade / 255), id = base*4 + s.
SHADES = [180, 220, 255, 135]

CHROMA_BOOST = 1.25   # saturation percue (OKLab) des couleurs remappees
CONTRAST = 1.05       # micro-contraste sur la clarte L


# --- sRGB <-> OKLab (matrices de reference de Bjorn Ottosson) ---------------

def _srgb_to_linear(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _linear_to_srgb(c):
    c = max(0.0, min(1.0, c))
    return 12.92 * c if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055


def _rgb_to_oklab(r8, g8, b8):
    r = _srgb_to_linear(r8 / 255.0)
    g = _srgb_to_linear(g8 / 255.0)
    b = _srgb_to_linear(b8 / 255.0)
    l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
    m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
    s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
    l_, m_, s_ = l ** (1 / 3), m ** (1 / 3), s ** (1 / 3)
    return (0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
            1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
            0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_)


def _oklab_to_rgb(L, a, b):
    l_ = L + 0.3963377774 * a + 0.2158037573 * b
    m_ = L - 0.1055613458 * a - 0.0638541728 * b
    s_ = L - 0.0894841775 * a - 1.2914855480 * b
    l, m, s = l_ ** 3, m_ ** 3, s_ ** 3
    r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
    g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
    bb = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
    return (_linear_to_srgb(r), _linear_to_srgb(g), _linear_to_srgb(bb))


def improve(r8, g8, b8):
    """Couleur 'amelioree' 0..1 : chroma boostee, L legerement contrastee."""
    L, a, b = _rgb_to_oklab(r8, g8, b8)
    L = 0.5 + (L - 0.5) * CONTRAST
    a *= CHROMA_BOOST
    b *= CHROMA_BOOST
    return _oklab_to_rgb(L, a, b)


# --- Palette : 61 bases x 4 nuances, arithmetique entiere du client ---------

def load_bases(path):
    """Palette JSON -> dict id -> (r, g, b) de base."""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    return {int(i): tuple(int(h.lstrip("#")[k:k + 2], 16) for k in (0, 2, 4))
            for i, h in data["colorList"].items()}


def shade(rgb, s):
    """Meme arithmetique entiere que mcmm.c et le client Java."""
    return tuple(min(255, v * s // 255) for v in rgb)


def load_palette():
    bases = load_bases(PALETTE_JSON)
    colors, seen = [], set()
    for idx in sorted(bases):
        for s in SHADES:
            c = shade(bases[idx], s)
            if c not in seen:  # doublons exacts inter-nuances : une seule entree
                seen.add(c)
                colors.append(c)
    return colors


HEADER = """#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par shader_test/gen_shaders.py — ne pas editer a la main.
// {purpose}
// Detection = egalite 8-bit STRICTE avec les {n} couleurs map a l'ecran
// (fullbright / glow frame), par RECHERCHE BINAIRE sur cles packees
// (r<<16|g<<8|b) triees : <=8 iterations par pixel (la boucle lineaire de
// 244 faisait chuter les fps de ~2/3). Un pixel voile par le fog ou assombri
// ne matche plus et reste tel quel (degradation propre).

uniform sampler2D InSampler;

layout(location = 0) out vec4 fragColor;

const int VMC_N = {n};
const int VMC_KEY[VMC_N] = int[VMC_N](
{key_rows}
);

// Renvoie l'index palette de la cle, ou -1.
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
"""

BODY_REMAP = """const vec3 VMC_OUT[VMC_N] = vec3[VMC_N](
{out_rows}
);

void main() {{
    vec4 color = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0);
    ivec3 c = ivec3(round(color.rgb * 255.0));
    int i = vmcFind((c.r << 16) | (c.g << 8) | c.b);
    fragColor = i >= 0 ? vec4(VMC_OUT[i], color.a) : color;
}}
"""

BODY_DEBUG = """
void main() {{
    vec4 color = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0);
    ivec3 c = ivec3(round(color.rgb * 255.0));
    int i = vmcFind((c.r << 16) | (c.g << 8) | c.b);
    fragColor = i >= 0
        ? vec4(1.0, 0.0, 1.0, color.a)        // detecte -> magenta
        : vec4(color.rgb * 0.35, color.a);    // le reste assombri
}}
"""


def rows(items, per_line, fmt):
    out, line = [], []
    for i, it in enumerate(items):
        line.append(fmt(it))
        if len(line) == per_line or i == len(items) - 1:
            out.append("    " + ", ".join(line)
                       + ("," if i != len(items) - 1 else ""))
            line = []
    return "\n".join(out)


def main():
    colors = load_palette()
    # Tri par cle packee pour la recherche binaire ; IN et OUT restent
    # apparies (la "meilleure" couleur suit sa couleur vanilla dans le tri).
    pairs = sorted(((r << 16) | (g << 8) | b, improve(r, g, b))
                   for r, g, b in colors)
    key_rows = rows([p[0] for p in pairs], 8, str)
    out_rows = rows([p[1] for p in pairs], 2,
                    lambda c: f"vec3({c[0]:.6f}, {c[1]:.6f}, {c[2]:.6f})")

    remap = HEADER.format(
        purpose="Remap des couleurs de map vers une palette amelioree "
                "(chroma OKLab +25%).",
        n=len(pairs), key_rows=key_rows) + BODY_REMAP.format(out_rows=out_rows)
    debug = HEADER.format(
        purpose="Variante DEBUG : visualise quels pixels sont detectes "
                "comme couleurs de map.",
        n=len(pairs), key_rows=key_rows) + BODY_DEBUG.format()

    os.makedirs(POST_DIR, exist_ok=True)
    with open(os.path.join(POST_DIR, "better_color.fsh"), "w") as f:
        f.write(remap)
    with open(os.path.join(POST_DIR, "better_color_debug.fsh"), "w") as f:
        f.write(debug)
    print(f"OK: {len(colors)} couleurs (244 - doublons) -> "
          f"better_color.fsh / better_color_debug.fsh")

    # --- Palette custom (voir gen_custom_palette.py) : le shader n'embarque
    # que les slots MODIFIES (identite implicite pour le reste). ---
    custom_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "custom_palette.json")
    if not os.path.exists(custom_path):
        print("(pas de custom_palette.json : custom_palette.fsh non genere)")
        return
    vanilla, custom = load_bases(PALETTE_JSON), load_bases(custom_path)
    cpairs = []
    for i in sorted(vanilla):
        for s in SHADES:
            v, c = shade(vanilla[i], s), shade(custom[i], s)
            if v != c:  # slot inchange -> identite -> inutile dans la table
                cpairs.append((((v[0] << 16) | (v[1] << 8) | v[2]),
                               tuple(x / 255.0 for x in c)))
    cpairs.sort()
    cust = HEADER.format(
        purpose="Affiche la palette CUSTOM (custom_palette.json) a la place "
                "des couleurs vanilla des slots reaffectes — mcmm doit "
                "quantifier sur la MEME palette custom (palette-path).",
        n=len(cpairs),
        key_rows=rows([p[0] for p in cpairs], 8, str),
    ) + BODY_REMAP.format(out_rows=rows(
        [p[1] for p in cpairs], 2,
        lambda c: f"vec3({c[0]:.6f}, {c[1]:.6f}, {c[2]:.6f})"))
    with open(os.path.join(POST_DIR, "custom_palette.fsh"), "w") as f:
        f.write(cust)
    print(f"OK: {len(cpairs)} slots remappes -> custom_palette.fsh")


if __name__ == "__main__":
    main()
