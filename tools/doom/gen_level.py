#!/usr/bin/env python3
"""Ecrit le niveau dans pack/ : trois textures de donnees (textures/effect/doom_level.png,
doom_atlas.png, doom_colormap.png), le shader post/doom_view.fsh et le post effect doom_view.

Le shader lit la position envoyee par le serveur et la rotation de la camera dans les pixels que
text.fsh ecrit au coin bas-gauche (balise de /doomprobe, voir gen_probe.py), puis lance un rayon
par pixel a travers les secteurs : on sort d'un secteur par l'un de ses bords ; un bord sans
secteur derriere, ou dont l'ouverture ne laisse pas passer le rayon, est un mur ; sinon le rayon
continue dans le secteur voisin. Le secteur de depart est trouve par le BSP du niveau.

    python3 tools/doom/gen_level.py [chemin/vers/freedoom1.wad] [E1M1]

Freedoom est sous licence BSD : les donnees extraites peuvent etre dans le pack (voir
pack/FREEDOOM-LICENSE.txt). Jamais un WAD commercial.
"""
import os
import string
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_probe import HEADER, ROOT, glsl_ivec3s, java_ids, write  # noqa: E402
from wad import Wad  # noqa: E402

DEFAULT_WAD = "/usr/share/games/doom/freedoom1.wad"
ASSETS = os.path.join(ROOT, "pack", "assets", "minecraft")
DATA_W = 1024            # largeur de doom_level.png : un entier par texel
ATLAS_W = 4096
STATE_W = 64            # cible « state » : 64 x 64 valeurs de 16 bits decodees de la balise
OUT_W, OUT_H = 640, 360  # resolution du rendu, etiree a l'ecran
SEC_STRIDE, EDGE_STRIDE, NODE_STRIDE, TEX_STRIDE, SPR_STRIDE = 7, 11, 6, 4, 6
NO_TEX, SKY = -1, -2


def pack_atlas(images):
    """Rangement en etageres. images : liste de (indices, opaque). Renvoie l'atlas et les (x, y)."""
    order = sorted(range(len(images)), key=lambda i: -images[i][0].shape[0])
    pos = [None] * len(images)
    x = y = shelf = 0
    for i in order:
        h, w = images[i][0].shape
        if x + w > ATLAS_W:
            x, y, shelf = 0, y + shelf, 0
        pos[i] = (x, y)
        x += w
        shelf = max(shelf, h)
    height = y + shelf
    atlas = np.zeros((height, ATLAS_W, 4), np.uint8)
    atlas[..., 3] = 255
    for (idx, opaque), (x, y) in zip(images, pos):
        h, w = idx.shape
        atlas[y:y + h, x:x + w, 0] = idx
        atlas[y:y + h, x:x + w, 1] = opaque * 255
    return atlas, pos


def build(wad, mapname):
    lv = wad.level(mapname)
    defs = wad.textures()
    sky_name = "SKY%c" % mapname[1] if mapname.startswith("E") else "SKY1"

    names, images = [], []

    def tex_id(name, flat=False):
        if name == "-":
            return NO_TEX
        if flat and name.startswith("F_SKY"):
            return SKY
        key = ("F:" if flat else "W:") + name
        if key not in names:
            if flat:
                f = wad.flat(name)
                images.append((f, np.ones(f.shape, bool)))
            else:
                images.append(wad.texture(defs, name))
            names.append(key)
        return names.index(key)

    sky = tex_id(sky_name)
    verts = lv["vertexes"]
    sides = lv["sidedefs"]
    sectors = lv["sectors"]

    # bords de chaque secteur, orientes pour que le secteur soit a DROITE (comme le cote avant
    # d'une ligne Doom de v1 vers v2) ; le cote arriere donne le bord inverse
    edges = [[] for _ in sectors]
    for v1, v2, flags, _, _, front, back in lv["linedefs"]:
        for side, other, a, b in ((front, back, v1, v2), (back, front, v2, v1)):
            if side == 0xFFFF:
                continue
            xo, yo, upper, lower, mid, sec = sides[side]
            other_sec = sides[other][5] if other != 0xFFFF else -1
            ax, ay = verts[a]
            bx, by = verts[b]
            edges[sec].append([ax, ay, bx - ax, by - ay, other_sec, tex_id(upper), tex_id(lower),
                               tex_id(mid), xo, yo, flags])

    sec_rows, edge_rows = [], []
    for s, (floor, ceil, fpic, cpic, light, _, _) in enumerate(sectors):
        sec_rows.append([floor, ceil, tex_id(fpic, True), tex_id(cpic, True), light,
                         len(edge_rows), len(edges[s])])
        edge_rows.extend(edges[s])

    nodes = [[n[0], n[1], n[2], n[3], n[12], n[13]] for n in lv["nodes"]]
    segs = lv["segs"]
    ss_sector = []
    for count, first in lv["ssectors"]:
        _, _, _, line, direction, _ = segs[first]
        ld = lv["linedefs"][line]
        ss_sector.append(sides[ld[5] if direction == 0 else ld[6]][5])

    # sprites : tous les lumps entre S_START et S_END, indices = ceux du moteur
    ntex = len(images)
    offsets_xy = []
    for raw in wad.sprites():
        idx, opaque, left, top = wad.patch_data(raw)
        images.append((idx, opaque))
        offsets_xy.append((left, top))

    atlas, pos = pack_atlas(images)
    tex_rows = [[x, y, img[0].shape[1], img[0].shape[0]] for (x, y), img in zip(pos[:ntex], images[:ntex])]
    spr_rows = [[x, y, img[0].shape[1], img[0].shape[0], left, top]
                for (x, y), img, (left, top) in zip(pos[ntex:], images[ntex:], offsets_xy)]

    ints, offsets = [], {}
    for key, rows in (("SEC0", sec_rows), ("EDGE0", edge_rows), ("NODE0", nodes),
                      ("SS0", [[s] for s in ss_sector]), ("TEX0", tex_rows),
                      ("SPR0", spr_rows)):
        offsets[key] = len(ints)
        for r in rows:
            ints.extend(r)
    data = np.array(ints, np.int64) + 0x800000
    assert data.min() >= 0 and data.max() < 1 << 24
    rows = -(-len(data) // DATA_W)
    level = np.zeros((rows * DATA_W, 4), np.uint8)
    level[:len(data), 0] = data >> 16
    level[:len(data), 1] = (data >> 8) & 255
    level[:len(data), 2] = data & 255
    level[:, 3] = 255
    level = level.reshape(rows, DATA_W, 4)

    # ligne palette * 32 + niveau de lumiere : RGB de l'indice apres la colormap
    rgb = wad.palettes()[:, wad.colormaps()].reshape(14 * 32, 256, 3)
    colormap = np.dstack([rgb, np.full((14 * 32, 256), 255, np.uint8)])

    start = next(t for t in lv["things"] if t[3] == 1)
    info = dict(offsets, ROOT_NODE=len(nodes) - 1, SKY_TEX=sky, start=start,
                counts=(len(sectors), len(edge_rows), len(nodes), ntex, len(spr_rows)))
    return level, atlas, colormap, info


# Valeurs de 16 bits de la balise (DoomProbe.java : 0-5 ; native/doommc_engine.c : 6 et plus).
STATE = dict(V_X=6, V_Y=8, V_Z=10, V_HEALTH=12, V_ARMOR=13, V_AMMO=14, V_PAL=16, V_PSP=17,
             V_FLASH=21, V_NSEC=25, V_NTH=26, V_FLAGS=27, V_FRAGS=28, V_SEC=29)

DECODE_FSH = string.Template("""#version 330
#extension GL_ARB_separate_shader_objects : require

${header}// Decode la balise (coin bas-gauche, ecrite par text.fsh) : le pixel (x, y) de la cible state
// recoit la valeur de 16 bits numero y * ${state_w} + x (4 demi-octets, poids faible d'abord, a partir
// du texel ${nsig}), en R (octet bas) et G (octet haut).

uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

${nib}

int nibbleAt(int k) {
    ivec3 c = ivec3(round(texelFetch(SrcSampler, ivec2(k % 128, k / 128), 0).rgb * 255.0));
    for (int i = 0; i < 16; i++) {
        if (c == NIB[i]) {
            return i;
        }
    }
    return 0;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int k = ${nsig} + 4 * (p.y * ${state_w} + p.x);
    int v = 0;
    if (k + 3 < 128 * 128) {
        for (int n = 0; n < 4; n++) {
            v |= nibbleAt(k + n) << (4 * n);
        }
    }
    fragColor = vec4(float(v & 255) / 255.0, float(v >> 8) / 255.0, 0.0, 1.0);
}
""")

VIEW_FSH = string.Template("""#version 330
#extension GL_ARB_separate_shader_objects : require

${header}// Dessine le niveau ${mapname} (Freedoom) tel que le moteur du serveur l'envoie (camera, hauteurs
// et lumieres des secteurs, objets, arme, HUD), vu avec la rotation de la camera lue cote client.
// Un rayon par pixel, de secteur en secteur, puis les sprites devant le premier obstacle.

uniform sampler2D SrcSampler;
uniform sampler2D StateSampler;
uniform sampler2D LevelSampler;
uniform sampler2D AtlasSampler;
uniform sampler2D ColormapSampler;

layout(location = 0) out vec4 fragColor;

${sig}

const vec2 OUT_SIZE = vec2(${out_w}.0, ${out_h}.0);
const int DATA_W = ${data_w}, STATE_W = ${state_w};
const int SEC0 = ${SEC0}, EDGE0 = ${EDGE0}, NODE0 = ${NODE0}, SS0 = ${SS0}, TEX0 = ${TEX0}, SPR0 = ${SPR0};
const int SEC_STRIDE = ${sec_stride}, EDGE_STRIDE = ${edge_stride}, NODE_STRIDE = ${node_stride};
const int TEX_STRIDE = ${tex_stride}, SPR_STRIDE = ${spr_stride};
const int ROOT_NODE = ${ROOT_NODE}, SKY_TEX = ${SKY_TEX}, SKY = ${sky}, NO_TEX = ${no_tex};
const int V_X = ${V_X}, V_Y = ${V_Y}, V_Z = ${V_Z}, V_HEALTH = ${V_HEALTH}, V_ARMOR = ${V_ARMOR};
const int V_AMMO = ${V_AMMO}, V_PAL = ${V_PAL}, V_PSP = ${V_PSP}, V_FLASH = ${V_FLASH};
const int V_NSEC = ${V_NSEC}, V_NTH = ${V_NTH}, V_FLAGS = ${V_FLAGS}, V_FRAGS = ${V_FRAGS}, V_SEC = ${V_SEC};
const int NO_LUMP = 65535;
const float TAN_V = 0.75;          // demi-champ vertical : celui de Doom en 4:3, elargi en 16:9
const int MAX_STEPS = 96;          // secteurs traverses par un rayon, au plus
const float SPRITE_REACH = 160.0;  // demi-largeur max d'un sprite (tri rapide)
const int DONTPEGTOP = 8, DONTPEGBOTTOM = 16;
const int SC = 2;                  // pixels par pixel de police du HUD
const int FONT[10] = int[10](31599, 11415, 29671, 29647, 23497, 31183, 31215, 29257, 31727, 31695);

int PAL = 0;      // palette de PLAYPAL (degats, bonus)
int EXTRA = 0;    // lumiere du tir (extralight)

ivec3 rgbAt(int x, int y) {
    return ivec3(round(texelFetch(SrcSampler, ivec2(x, y), 0).rgb * 255.0));
}

// entier signe numero i de doom_level.png (24 bits, decale de 2^23)
int I(int i) {
    ivec3 c = ivec3(round(texelFetch(LevelSampler, ivec2(i % DATA_W, i / DATA_W), 0).rgb * 255.0));
    return ((c.r << 16) | (c.g << 8) | c.b) - 0x800000;
}

// valeur de 16 bits numero i de la balise (cible state), non signee / signee / sur 32 bits
int S(int i) {
    ivec2 c = ivec2(round(texelFetch(StateSampler, ivec2(i % STATE_W, i / STATE_W), 0).rg * 255.0));
    return c.x | (c.y << 8);
}

int Ss(int i) {
    int v = S(i);
    return v >= 32768 ? v - 65536 : v;
}

int S32(int i) {
    return int(uint(S(i)) | (uint(S(i + 1)) << 16u));
}

float secFloor(int s) { return float(Ss(V_SEC + 3 * s)); }
float secCeil(int s) { return float(Ss(V_SEC + 3 * s + 1)); }
int secLight(int s) { return S(V_SEC + 3 * s + 2); }

// R_PointInSubsector de Doom, puis le secteur du sous-secteur
int pointSector(vec2 p) {
    int n = ROOT_NODE;
    for (int k = 0; k < 64 && (n & 0x8000) == 0; k++) {
        int b = NODE0 + n * NODE_STRIDE;
        vec2 r = p - vec2(I(b), I(b + 1));
        bool left = r.y * float(I(b + 2)) >= float(I(b + 3)) * r.x;
        n = I(b + (left ? 5 : 4));
    }
    return I(SS0 + (n & 0x7FFF));
}

// texel (u, v) de la texture id, repetee : (indice de palette, opaque)
ivec2 texel(int id, int u, int v) {
    int b = TEX0 + id * TEX_STRIDE;
    int w = I(b + 2), h = I(b + 3);
    u = ((u % w) + w) % w;
    v = ((v % h) + h) % h;
    return ivec2(round(texelFetch(AtlasSampler, ivec2(I(b) + u, I(b + 1) + v), 0).rg * 255.0));
}

int texHeight(int id) {
    return I(TEX0 + id * TEX_STRIDE + 3);
}

// texel (u, v) du sprite (lump - firstspritelump), sans repetition : y = 0 hors du sprite
ivec2 spriteTexel(int lump, int u, int v) {
    int b = SPR0 + lump * SPR_STRIDE;
    if (u < 0 || v < 0 || u >= I(b + 2) || v >= I(b + 3)) {
        return ivec2(0);
    }
    return ivec2(round(texelFetch(AtlasSampler, ivec2(I(b) + u, I(b + 1) + v), 0).rg * 255.0));
}

vec3 shade(int index, int level) {
    return texelFetch(ColormapSampler, ivec2(index, PAL * 32 + level), 0).rgb;
}

// colormap de Doom : niveau de lumiere du secteur, assombri avec la distance (r_main.c)
int startmap(int light, int contrast) {
    return (15 - clamp((light >> 4) + EXTRA + contrast, 0, 15)) * 4;
}

int wallLevel(int light, int contrast, float depth) {
    int j = min(int(2560.0 / max(depth, 1.0)), 47);
    return clamp(startmap(light, contrast) - j / 2, 0, 31);
}

int flatLevel(int light, float depth) {
    int j = min(int(max(depth, 0.0) / 16.0), 127);
    return clamp(startmap(light, 0) - (160 / (j + 1)) / 2, 0, 31);
}

vec3 sky(vec3 d) {
    int col = int(floor(atan(d.y, d.x) / 6.2831853 * 1024.0));
    float slope = d.z / max(length(d.xy), 1e-4);
    int row = clamp(int(floor(100.0 - slope * 160.0)), 0, texHeight(SKY_TEX) - 1);
    return shade(texel(SKY_TEX, col, row).x, 0);
}

// chiffre ou signe moins (glyph = -1) dans la cellule de coin bas-gauche o
bool glyphAt(ivec2 q, int glyph) {
    if (q.x < 0 || q.y < 0 || q.x >= 3 * SC || q.y >= 5 * SC) {
        return false;
    }
    int cx = q.x / SC, fy = q.y / SC;
    if (glyph < 0) {
        return fy == 2;
    }
    return ((FONT[glyph] >> (14 - ((4 - fy) * 3 + cx))) & 1) == 1;
}

// nombre signe sur nd chiffres, signe dans la premiere cellule
bool number(ivec2 p, ivec2 o, int value, int nd) {
    ivec2 q = p - o;
    int cell = q.x / (4 * SC);
    if (q.x < 0 || cell > nd) {
        return false;
    }
    ivec2 inCell = ivec2(q.x - cell * 4 * SC, q.y);
    if (cell == 0) {
        return value < 0 && glyphAt(inCell, -1);
    }
    int v = abs(value);
    for (int k = 0; k < nd - cell; k++) {
        v /= 10;
    }
    return glyphAt(inCell, v % 10);
}

void main() {
    vec2 uv = gl_FragCoord.xy / OUT_SIZE;
    bool ok = true;
    for (int i = 0; i < ${nsig}; i++) {
        ok = ok && rgbAt(i, 0) == SIG[i];
    }
    mat3 mv;
    for (int k = 0; k < 9; k++) {
        ivec3 c = rgbAt(${mx0} + k, 0);
        ok = ok && c.b == ${check};
        mv[k / 3][k % 3] = float(c.r * 256 + c.g) / 32767.5 - 1.0;
    }
    int nsec = S(V_NSEC);
    if (!ok || nsec == 0) {
        // diagnostic : rouge = balise illisible ; bleu = balise lue mais sans trame du moteur, avec le
        // compteur decode (il doit defiler) et le nombre de valeurs de secteurs recues
        vec3 col = texture(SrcSampler, uv).rgb * (ok ? vec3(0.3, 0.5, 1.0) : vec3(1.0, 0.3, 0.3));
        ivec2 q = ivec2(gl_FragCoord.xy);
        if (ok && (number(q, ivec2(8, ${out_h} - 20), S(0), 5) || number(q, ivec2(8, ${out_h} - 40), nsec, 5))) {
            col = vec3(1.0);
        }
        fragColor = vec4(col, 1.0);
        return;
    }
    PAL = clamp(S(V_PAL), 0, 13);
    EXTRA = S(V_FLAGS) >> 8;

    // rayon : vue -> monde Minecraft (transposee de ModelViewMat) -> Doom (x est, y nord, z haut)
    vec2 screen = vec2(textureSize(SrcSampler, 0));
    float aspect = screen.x / screen.y;
    vec2 ndc = uv * 2.0 - 1.0;
    vec3 w = transpose(mv) * vec3(ndc.x * TAN_V * aspect, ndc.y * TAN_V, -1.0);
    vec3 d = vec3(w.x, -w.z, w.y);
    vec3 f = transpose(mv) * vec3(0.0, 0.0, -1.0);
    vec2 fwd = vec2(f.x, -f.z);
    fwd = length(fwd) > 1e-3 ? normalize(fwd) : normalize(d.xy + 1e-6);

    vec3 o = vec3(vec2(S32(V_X), S32(V_Y)), S32(V_Z)) / 16.0;
    int camSec = pointSector(o.xy);
    int sec = camSec;
    float t = 0.0, tHit = 1e30;
    int crossed = -1;   // bord franchi en dernier : une ligne aux deux cotes dans le meme secteur
    vec3 col = vec3(0.0);
    for (int step = 0; step < MAX_STEPS; step++) {
        int sb = SEC0 + sec * SEC_STRIDE;
        float fl = secFloor(sec), ce = secCeil(sec);
        int light = secLight(sec), first = I(sb + 5), count = I(sb + 6);

        // bord par lequel le rayon sort : traverse de droite a gauche, le plus proche
        float tmin = 1e30;
        int hit = -1;
        for (int k = 0; k < count; k++) {
            int eb = EDGE0 + (first + k) * EDGE_STRIDE;
            vec2 ab = vec2(I(eb + 2), I(eb + 3));
            float den = d.x * ab.y - d.y * ab.x;
            if (den >= 0.0 || eb == crossed) {
                continue;
            }
            vec2 ao = vec2(I(eb), I(eb + 1)) - o.xy;
            float tt = (ao.x * ab.y - ao.y * ab.x) / den;
            float s = (ao.x * d.y - ao.y * d.x) / den;
            if (tt > t - 1e-3 && tt < tmin && s >= -1e-4 && s <= 1.0001) {
                tmin = tt;
                hit = eb;
            }
        }

        // sol ou plafond avant ce bord
        float tp = d.z < 0.0 ? (fl - o.z) / d.z : d.z > 0.0 ? (ce - o.z) / d.z : 1e30;
        if (tp < tmin) {
            vec3 p = o + d * tp;
            int id = I(sb + (d.z < 0.0 ? 2 : 3));
            if (id == SKY) {
                col = sky(d);
            } else {
                tHit = tp;
                if (id >= 0) {
                    col = shade(texel(id, int(floor(p.x)), int(floor(-p.y))).x, flatLevel(light, dot(p.xy - o.xy, fwd)));
                }
            }
            break;
        }
        if (hit < 0) {
            break;      // hors du niveau
        }

        vec3 p = o + d * tmin;
        vec2 a = vec2(I(hit), I(hit + 1));
        vec2 ab = vec2(I(hit + 2), I(hit + 3));
        int other = I(hit + 4), flags = I(hit + 10);
        int u = int(floor(length(p.xy - a))) + I(hit + 8);
        float yoff = float(I(hit + 9));
        int level = wallLevel(light, ab.y == 0.0 ? -1 : ab.x == 0.0 ? 1 : 0, dot(p.xy - o.xy, fwd));

        int id = NO_TEX;
        float top = 0.0;
        if (other < 0) {
            id = I(hit + 7);
            top = (flags & DONTPEGBOTTOM) != 0 ? fl + float(texHeight(max(id, 0))) : ce;
        } else {
            float ofl = secFloor(other), oce = secCeil(other);
            if (p.z < ofl) {
                id = I(hit + 6);
                top = (flags & DONTPEGBOTTOM) != 0 ? ce : ofl;
            } else if (p.z > oce) {
                if (I(sb + 3) == SKY && I(SEC0 + other * SEC_STRIDE + 3) == SKY) {
                    col = sky(d);
                    break;
                }
                id = I(hit + 5);
                top = (flags & DONTPEGTOP) != 0 ? ce : oce + float(texHeight(max(id, 0)));
            } else {
                // ouverture : texture du milieu ajouree (grilles), une seule fois en hauteur
                int mid = I(hit + 7);
                if (mid >= 0) {
                    int h = texHeight(mid);
                    float mtop = (flags & DONTPEGBOTTOM) != 0 ? max(fl, ofl) + float(h) : min(ce, oce);
                    int v = int(floor(mtop - p.z + yoff));
                    if (v >= 0 && v < h) {
                        ivec2 tx = texel(mid, u, v);
                        if (tx.y > 0) {
                            col = shade(tx.x, level);
                            tHit = tmin;
                            break;
                        }
                    }
                }
                sec = other;
                t = tmin;
                crossed = hit;
                continue;
            }
        }
        tHit = tmin;
        if (id >= 0) {
            col = shade(texel(id, u, int(floor(top - p.z + yoff))).x, level);
        }
        break;
    }

    // sprites : plan vertical face a la camera (comme Doom), le plus proche devant l'obstacle
    float dn = dot(d.xy, fwd);
    if (dn > 1e-4) {
        vec2 right = vec2(fwd.y, -fwd.x);
        int base = V_SEC + 3 * nsec, nth = S(V_NTH);
        float best = tHit;
        bool found = false, shadow = false;
        vec3 scol = vec3(0.0);
        for (int i = 0; i < nth; i++) {
            int b = base + 5 * i;
            vec2 th = vec2(Ss(b), Ss(b + 1));
            float ts = dot(th - o.xy, fwd) / dn;
            if (ts <= 0.0 || ts >= best) {
                continue;
            }
            vec3 p = o + d * ts;
            float lat = dot(p.xy - th, right);
            if (abs(lat) > SPRITE_REACH) {
                continue;
            }
            int lump = S(b + 3), fl = S(b + 4);
            int sb = SPR0 + lump * SPR_STRIDE;
            int u = int(floor(lat)) + I(sb + 4);
            int v = int(floor(float(Ss(b + 2) + I(sb + 5)) - p.z));
            if ((fl & 1) != 0) {
                u = I(sb + 2) - 1 - u;
            }
            ivec2 tx = spriteTexel(lump, u, v);
            if (tx.y == 0) {
                continue;
            }
            // couleur des joueurs 2 a 4 : la rampe verte (0x70-0x7f) devient grise, brune ou rouge
            int tr = (fl >> 3) & 3;
            if (tr != 0 && tx.x >= 0x70 && tx.x <= 0x7f) {
                tx.x = (tr == 1 ? 0x60 : tr == 2 ? 0x40 : 0x20) + (tx.x & 15);
            }
            best = ts;
            found = true;
            shadow = (fl & 4) != 0;
            scol = shade(tx.x, (fl & 2) != 0 ? 0 : wallLevel(fl >> 8, 0, ts * dn));
        }
        if (found) {
            col = shadow ? col * 0.4 : scol;
        }
    }

    // arme et eclair du tir, dans l'ecran 320 x 200 de Doom centre en 4:3 (sans barre d'etat)
    vec2 dp = vec2((uv.x - 0.5) * aspect * 0.75 * 320.0 + 160.0, (1.0 - uv.y) * 200.0);
    for (int k = 0; k < 2; k++) {
        int b = k == 0 ? V_PSP : V_FLASH;
        int lump = S(b);
        if (lump == NO_LUMP) {
            continue;
        }
        int sb = SPR0 + lump * SPR_STRIDE;
        ivec2 tx = spriteTexel(lump, int(floor(dp.x)) - Ss(b + 2) + I(sb + 4), int(floor(dp.y)) - Ss(b + 3) + I(sb + 5));
        if (tx.y > 0) {
            col = shade(tx.x, S(b + 1) != 0 ? 0 : max(startmap(secLight(camSec), 0) - 23, 0));
        }
    }

    // HUD : sante (rouge), armure (vert), munitions (jaune)
    ivec2 q = ivec2(gl_FragCoord.xy);
    const int CELL = 4 * SC;
    if (number(q, ivec2(8, 8), Ss(V_HEALTH), 3)) col = vec3(0.95, 0.2, 0.15);
    if (number(q, ivec2(8 + 5 * CELL, 8), Ss(V_ARMOR), 3)) col = vec3(0.3, 0.9, 0.3);
    if (S(V_AMMO) != NO_LUMP && number(q, ivec2(${out_w} - 8 - 4 * CELL, 8), S(V_AMMO), 3)) col = vec3(1.0, 0.85, 0.2);
    // arene (deathmatch) : frags en blanc, en haut a droite
    if ((S(V_FLAGS) & 4) != 0 && number(q, ivec2(${out_w} - 8 - 4 * CELL, ${out_h} - 8 - 5 * SC), Ss(V_FRAGS), 3)) col = vec3(1.0);
    fragColor = vec4(col, 1.0);
}
""")


def effect(sizes):
    tex = lambda sampler, name: {"sampler_name": sampler, "location": "minecraft:" + name,
                                 "width": sizes[name][0], "height": sizes[name][1], "bilinear": False}
    main_in = {"sampler_name": "Src", "target": "minecraft:main", "bilinear": False}
    return {
        "targets": {"state": {"width": STATE_W, "height": STATE_W}, "doom": {"width": OUT_W, "height": OUT_H}},
        "passes": [
            {"vertex_shader": "minecraft:core/screenquad", "fragment_shader": "minecraft:post/doom_decode",
             "inputs": [main_in], "output": "state"},
            {"vertex_shader": "minecraft:core/screenquad", "fragment_shader": "minecraft:post/doom_view",
             "inputs": [main_in, {"sampler_name": "State", "target": "state", "bilinear": False},
                        tex("Level", "doom_level"), tex("Atlas", "doom_atlas"),
                        tex("Colormap", "doom_colormap")],
             "output": "doom"},
            {"vertex_shader": "minecraft:core/screenquad", "fragment_shader": "minecraft:post/blit",
             "inputs": [{"sampler_name": "In", "target": "doom", "bilinear": False}],
             "uniforms": {"BlitConfig": [{"name": "ColorModulate", "type": "vec4",
                                          "value": [1.0, 1.0, 1.0, 1.0]}]},
             "output": "minecraft:main"},
        ],
    }


def main():
    import json
    from gen_probe import MATRIX_CHECK, MATRIX_X0

    wad_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_WAD
    mapname = (sys.argv[2] if len(sys.argv) > 2 else "E1M1").upper()
    level, atlas, colormap, info = build(Wad(wad_path), mapname)
    sizes = {}
    for name, img in (("doom_level", level), ("doom_atlas", atlas), ("doom_colormap", colormap)):
        path = os.path.join(ASSETS, "textures", "effect", name + ".png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        Image.fromarray(img, "RGBA").save(path, optimize=True)
        sizes[name] = (img.shape[1], img.shape[0])
        print("  ", os.path.relpath(path, ROOT), "%dx%d" % sizes[name])
    import hashlib
    sha1 = hashlib.sha1(open(wad_path, "rb").read()).hexdigest()
    res = os.path.join(ROOT, "src", "main", "resources", "level.properties")
    with open(res, "w", encoding="utf-8") as f:
        f.write("# GENERE par tools/doom/gen_level.py : WAD dont le pack est tire\nwad-sha1=%s\nmap=%s\n" % (sha1, mapname))
    print("  ", os.path.relpath(res, ROOT), sha1)
    sig, nib = java_ids("SIGNATURE"), java_ids("NIBBLES")
    header = HEADER.replace("gen_probe.py (essai /doomprobe)", "gen_level.py")
    fmt = dict(STATE, header=header, mapname=mapname, sig=glsl_ivec3s("SIG", sig),
               nib=glsl_ivec3s("NIB", nib), nsig=len(sig), mx0=MATRIX_X0, check=MATRIX_CHECK,
               out_w=OUT_W, out_h=OUT_H, data_w=DATA_W, state_w=STATE_W, sec_stride=SEC_STRIDE,
               edge_stride=EDGE_STRIDE, node_stride=NODE_STRIDE, tex_stride=TEX_STRIDE,
               spr_stride=SPR_STRIDE, sky=SKY, no_tex=NO_TEX,
               **{k: info[k] for k in ("SEC0", "EDGE0", "NODE0", "SS0", "TEX0", "SPR0", "ROOT_NODE", "SKY_TEX")})
    post = os.path.join(ASSETS, "shaders", "post")
    write(os.path.join(post, "doom_decode.fsh"), DECODE_FSH.substitute(fmt))
    write(os.path.join(post, "doom_view.fsh"), VIEW_FSH.substitute(fmt))
    write(os.path.join(ASSETS, "post_effect", "doom_view.json"), json.dumps(effect(sizes), indent=2) + "\n")
    x, y, angle, _, _ = info["start"]
    print("   %s : %d secteurs, %d bords, %d noeuds, %d textures, %d sprites ; depart (%d, %d) angle %d"
          % ((mapname,) + info["counts"] + (x, y, angle)))


if __name__ == "__main__":
    main()
