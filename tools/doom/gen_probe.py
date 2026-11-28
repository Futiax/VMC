#!/usr/bin/env python3
"""Ecrit les shaders de l'essai /doomprobe dans pack/ : text.vsh et text.fsh (core) et le post effect
doom_probe.

Principe : la map « balise » (DoomProbe.java) commence par 4 texels de signature. text.vsh la
reconnait et deplace son quad sur le quart bas-gauche de l'ecran ; text.fsh y recopie la map pixel
pour pixel (128 x 128, coin bas-gauche de l'image) sans eclairage ni brouillard, et la matrice
ModelViewMat (= rotation de la camera) sur les 9 pixels (128..136, 0). Le post effect lit ces pixels
a des positions fixes : ni detection ni homographie.

Les ids de couleur viennent de DoomProbe.java (seule source), les RGB de vanilla_map_colors.json.

    python3 tools/doom/gen_probe.py            (depuis la racine du depot)
"""
import json
import os
import re

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
SHADERS = os.path.join(ROOT, "pack", "assets", "minecraft", "shaders")
JAVA = os.path.join(ROOT, "src", "main", "java", "ovh", "futiax", "doommc", "DoomProbe.java")
PALETTE = os.path.join(ROOT, "tools", "doom", "vanilla_map_colors.json")
MATRIX_X0 = 128          # 9 pixels de ModelViewMat a droite de la map, ligne 0
MATRIX_CHECK = 90        # canal bleu des pixels de matrice
PROBE_DEPTH = "0.15"     # bloc devant la camera (plan proche du jeu : 0,05)


def java_ids(name):
    src = open(JAVA, encoding="utf-8").read()
    m = re.search(r"static final int\[\] " + name + r" = \{([^}]*)\};", src)
    return [int(v) for v in m.group(1).split(",")]


def map_rgb(color_id):
    """RGB que le client met dans la texture de la map pour cet id (base * nuance // 255)."""
    colors = json.load(open(PALETTE, encoding="utf-8"))["colorList"]
    base, shade = divmod(color_id, 4)
    h = colors[str(base)].lstrip("#")
    mult = (180, 220, 255, 135)[shade]
    return tuple(int(h[i:i + 2], 16) * mult // 255 for i in (0, 2, 4))


def glsl_ivec3s(name, ids):
    rgbs = [map_rgb(i) for i in ids]
    body = ", ".join("ivec3(%d, %d, %d)" % c for c in rgbs)
    return "const ivec3 %s[%d] = ivec3[%d](%s);" % (name, len(ids), len(ids), body)


HEADER = "// GENERE par tools/doom/gen_probe.py (essai /doomprobe), ne pas editer.\n"

TEXT_VSH = """#version 330
#extension GL_ARB_separate_shader_objects : require

{header}// Copie du text.vsh vanilla 26.3 ; ajout : la map balise est deplacee dans le coin bas-gauche.

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 3) in ivec2 UV2;
#endif

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif

layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) flat out int doomProbe;

{sig}

bool isProbe() {{
    if (textureSize(Sampler0, 0) != ivec2(128, 128)) {{
        return false;
    }}
    for (int i = 0; i < {nsig}; i++) {{
        if (ivec3(round(texelFetch(Sampler0, ivec2(i, 0), 0).rgb * 255.0)) != SIG[i]) {{
            return false;
        }}
    }}
    return true;
}}

void main() {{
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
    texCoord0 = UV0;

    doomProbe = 0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    if (isProbe()) {{
        // Quart bas-gauche de l'ecran, u vers la droite et v vers le bas comme vu de face : l'ordre
        // des sommets reste le meme, le quad n'est pas elimine par le culling. Profondeur = celle
        // d'un point a {near} bloc devant la camera, par ProjMat : devant tout le decor quelle que
        // soit la convention (un z fixe tombait au fond avec une profondeur inversee).
        vec4 nearClip = ProjMat * vec4(0.0, 0.0, -{near}, 1.0);
        gl_Position = vec4(vec2(UV0.x - 1.0, -UV0.y) * nearClip.w, nearClip.z, nearClip.w);
        doomProbe = 1;
    }}
#endif
}}
"""

TEXT_FSH = """#version 330
#extension GL_ARB_separate_shader_objects : require

{header}// text.fsh vanilla 26.3, sans brouillard (VMC), et la sortie de la map balise (DOOMMC).

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#endif

layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) flat in int doomProbe;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 calculateFinalColor(vec4 color) {{
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif

    #if !defined(IS_SEE_THROUGH) && !defined(IS_GUI)

    #ifdef OIT_ACCUMULATE
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif

    // VMC : PAS de fog sur le render type text (= les maps en item frame).
    // Vanilla 26.3 a fog_start_distance = 0, donc mix(couleur, FogColor, d/1024)
    // depuis la camera : 0,5 % a 5 blocs suffit a sortir un texel de la palette
    // 8 bits exacte que better_color detecte.
    #endif

    return color;
}}

void main() {{
    if (doomProbe == 1) {{
#if defined(OIT) || defined(OIT_ALPHA_ONLY) || defined(OIT_ACCUMULATE)
        discard;    // la couleur n'arriverait pas telle quelle dans l'image
#else
        // Pixel (x, y) depuis le coin bas-gauche = texel (x, y) de la map ; puis ModelViewMat
        // colonne par colonne, chaque valeur de [-1, 1] sur 16 bits (R poids fort, G poids faible).
        ivec2 p = ivec2(gl_FragCoord.xy);
        if (p.x < 128 && p.y < 128) {{
            fragColor = vec4(texelFetch(Sampler0, p, 0).rgb, 1.0);
        }} else if (p.y == 0 && p.x < {mx0} + 9) {{
            int k = p.x - {mx0};
            float v = ModelViewMat[k / 3][k % 3];
            int u = int(clamp(round((v + 1.0) * 32767.5), 0.0, 65535.0));
            fragColor = vec4(float(u >> 8) / 255.0, float(u & 255) / 255.0, {check}.0 / 255.0, 1.0);
        }} else {{
            discard;
        }}
        return;
#endif
    }}

    #ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
    #else
    vec4 texColor = texture(Sampler0, texCoord0);
    #endif

    vec4 color = texColor * vertexColor * ColorModulator;

    if (color.a < 0.1) {{
        discard;
    }}

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}}
"""

PROBE_FSH = """#version 330
#extension GL_ARB_separate_shader_objects : require

{header}// Lit les pixels ecrits par text.fsh dans le coin bas-gauche et les affiche dessus, sur un
// panneau : pastilles (signature trouvee, matrice lue), compteur, angles client (cyan) et serveur
// (jaune) en degres.

uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

{sig}
{nib}

const int PW = 170, PH = 134;     // le panneau couvre la map et les pixels de matrice
const int SC = 3;                 // pixels d'ecran par pixel de police
const int FONT[10] = int[10](31599, 11415, 29671, 29647, 23497, 31183, 31215, 29257, 31727, 31695);

ivec3 rgbAt(int x, int y) {{
    return ivec3(round(texelFetch(SrcSampler, ivec2(x, y), 0).rgb * 255.0));
}}

int nibbleAt(int x) {{
    ivec3 c = rgbAt(x, 0);
    for (int i = 0; i < 16; i++) {{
        if (c == NIB[i]) {{
            return i;
        }}
    }}
    return -1;
}}

// chiffre ou signe moins (glyph = -1) dans la cellule de coin bas-gauche o
bool glyphAt(ivec2 q, int glyph) {{
    if (q.x < 0 || q.y < 0 || q.x >= 3 * SC || q.y >= 5 * SC) {{
        return false;
    }}
    int cx = q.x / SC, fy = q.y / SC;
    if (glyph < 0) {{
        return fy == 2;
    }}
    return ((FONT[glyph] >> (14 - ((4 - fy) * 3 + cx))) & 1) == 1;
}}

// nombre signe sur nd chiffres (zeros compris), signe dans la premiere cellule
bool number(ivec2 p, ivec2 o, int value, int nd) {{
    ivec2 q = p - o;
    int cell = q.x / (4 * SC);
    if (q.x < 0 || cell > nd) {{
        return false;
    }}
    ivec2 inCell = ivec2(q.x - cell * 4 * SC, q.y);
    if (cell == 0) {{
        return value < 0 && glyphAt(inCell, -1);
    }}
    int v = abs(value);
    for (int k = 0; k < nd - cell; k++) {{
        v /= 10;
    }}
    return glyphAt(inCell, v % 10);
}}

void main() {{
    ivec2 p = ivec2(gl_FragCoord.xy);
    if (p.x >= PW || p.y >= PH) {{
        fragColor = texelFetch(SrcSampler, p, 0);
        return;
    }}

    bool sig = true;
    for (int i = 0; i < {nsig}; i++) {{
        sig = sig && rgbAt(i, 0) == SIG[i];
    }}
    bool words = true;
    int w[3];
    for (int j = 0; j < 3; j++) {{
        uint v = 0u;
        for (int n = 0; n < 8; n++) {{
            int nib = nibbleAt({nsig} + j * 8 + n);
            words = words && nib >= 0;
            v |= uint(max(nib, 0)) << uint(4 * n);
        }}
        w[j] = int(v);
    }}
    bool mat = true;
    float m[9];
    for (int k = 0; k < 9; k++) {{
        ivec3 c = rgbAt({mx0} + k, 0);
        mat = mat && c.b == {check};
        m[k] = float(c.r * 256 + c.g) / 32767.5 - 1.0;
    }}
    // ModelViewMat envoie le monde (relatif a la camera) dans la vue : sa ligne 2 est l'axe arriere de
    // la camera. Convention Minecraft : yaw 0 = sud (+Z), 90 = ouest ; pitch positif = vers le bas.
    vec3 fwd = -normalize(vec3(m[2], m[5], m[8]));
    int cyaw = int(round(degrees(atan(-fwd.x, fwd.z))));
    int cpitch = int(round(degrees(asin(clamp(-fwd.y, -1.0, 1.0)))));

    vec3 col = vec3(0.08);
    const int LH = 6 * SC + 6;
    int top = PH - 6 - 5 * SC;
    if (p.y >= top && p.y < top + 5 * SC) {{
        if (p.x >= 6 && p.x < 6 + 5 * SC) col = sig && words ? vec3(0.1, 0.9, 0.2) : vec3(0.9, 0.1, 0.1);
        if (p.x >= 12 + 5 * SC && p.x < 12 + 10 * SC) col = mat ? vec3(0.1, 0.9, 0.2) : vec3(0.9, 0.1, 0.1);
    }}
    if (number(p, ivec2(6, top - LH), w[0], 9)) col = vec3(1.0);
    if (number(p, ivec2(6, top - 2 * LH), cyaw, 3) || number(p, ivec2(6 + 5 * 4 * SC, top - 2 * LH), cpitch, 2)) col = vec3(0.3, 0.9, 1.0);
    if (number(p, ivec2(6, top - 3 * LH), int(round(float(w[1]) / 100.0)), 3) || number(p, ivec2(6 + 5 * 4 * SC, top - 3 * LH), int(round(float(w[2]) / 100.0)), 2)) col = vec3(1.0, 0.85, 0.2);
    fragColor = vec4(col, 1.0);
}}
"""

EFFECT = {
    "targets": {"out": {}},
    "passes": [
        {"vertex_shader": "minecraft:core/screenquad", "fragment_shader": "minecraft:post/doom_probe",
         "inputs": [{"sampler_name": "Src", "target": "minecraft:main", "bilinear": False}],
         "output": "out"},
        {"vertex_shader": "minecraft:core/screenquad", "fragment_shader": "minecraft:post/blit",
         "inputs": [{"sampler_name": "In", "target": "out", "bilinear": False}],
         "uniforms": {"BlitConfig": [{"name": "ColorModulate", "type": "vec4", "value": [1.0, 1.0, 1.0, 1.0]}]},
         "output": "minecraft:main"},
    ],
}


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print("  ", os.path.relpath(path, ROOT))


def main():
    sig, nib = java_ids("SIGNATURE"), java_ids("NIBBLES")
    rgbs = [map_rgb(i) for i in sig + nib]
    assert len(set(rgbs)) == len(rgbs), "deux ids de la balise ont la meme couleur"
    assert min(sig + nib) >= 4, "ids 0-3 = transparent"
    fmt = dict(header=HEADER, sig=glsl_ivec3s("SIG", sig), nib=glsl_ivec3s("NIB", nib),
               nsig=len(sig), mx0=MATRIX_X0, check=MATRIX_CHECK, near=PROBE_DEPTH)
    write(os.path.join(SHADERS, "core", "text.vsh"), TEXT_VSH.format(**fmt))
    write(os.path.join(SHADERS, "core", "text.fsh"), TEXT_FSH.format(**fmt))
    write(os.path.join(SHADERS, "post", "doom_probe.fsh"), PROBE_FSH.format(**fmt))
    write(os.path.join(ROOT, "pack", "assets", "minecraft", "post_effect", "doom_probe.json"),
          json.dumps(EFFECT, indent=2) + "\n")


if __name__ == "__main__":
    main()
