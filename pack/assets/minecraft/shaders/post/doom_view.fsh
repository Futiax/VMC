#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par tools/doom/gen_level.py, ne pas editer.
// Dessine le niveau E1M1 (Freedoom) tel que le moteur du serveur l'envoie (camera, hauteurs
// et lumieres des secteurs, objets, arme, HUD), vu avec la rotation de la camera lue cote client.
// Un rayon par pixel, de secteur en secteur, puis les sprites devant le premier obstacle.

uniform sampler2D SrcSampler;
uniform sampler2D StateSampler;
uniform sampler2D LevelSampler;
uniform sampler2D AtlasSampler;
uniform sampler2D ColormapSampler;

layout(location = 0) out vec4 fragColor;

const ivec3 SIG[4] = ivec3[4](ivec3(197, 197, 44), ivec3(109, 176, 21), ivec3(208, 109, 142), ivec3(65, 65, 65));

const vec2 OUT_SIZE = vec2(640.0, 360.0);
const int DATA_W = 1024, STATE_W = 64;
const int SEC0 = 0, EDGE0 = 1274, NODE0 = 21393, SS0 = 25479, TEX0 = 26161, SPR0 = 26789;
const int SEC_STRIDE = 7, EDGE_STRIDE = 11, NODE_STRIDE = 6;
const int TEX_STRIDE = 4, SPR_STRIDE = 6;
const int ROOT_NODE = 680, SKY_TEX = 0, SKY = -2, NO_TEX = -1;
const int V_X = 6, V_Y = 8, V_Z = 10, V_HEALTH = 12, V_ARMOR = 13;
const int V_AMMO = 14, V_PAL = 16, V_PSP = 17, V_FLASH = 21;
const int V_NSEC = 25, V_NTH = 26, V_FLAGS = 27, V_FRAGS = 28, V_SEC = 29;
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
    for (int i = 0; i < 4; i++) {
        ok = ok && rgbAt(i, 0) == SIG[i];
    }
    mat3 mv;
    for (int k = 0; k < 9; k++) {
        ivec3 c = rgbAt(128 + k, 0);
        ok = ok && c.b == 90;
        mv[k / 3][k % 3] = float(c.r * 256 + c.g) / 32767.5 - 1.0;
    }
    int nsec = S(V_NSEC);
    if (!ok || nsec == 0) {
        // diagnostic : rouge = balise illisible ; bleu = balise lue mais sans trame du moteur, avec le
        // compteur decode (il doit defiler) et le nombre de valeurs de secteurs recues
        vec3 col = texture(SrcSampler, uv).rgb * (ok ? vec3(0.3, 0.5, 1.0) : vec3(1.0, 0.3, 0.3));
        ivec2 q = ivec2(gl_FragCoord.xy);
        if (ok && (number(q, ivec2(8, 360 - 20), S(0), 5) || number(q, ivec2(8, 360 - 40), nsec, 5))) {
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
    if (S(V_AMMO) != NO_LUMP && number(q, ivec2(640 - 8 - 4 * CELL, 8), S(V_AMMO), 3)) col = vec3(1.0, 0.85, 0.2);
    // arene (deathmatch) : frags en blanc, en haut a droite
    if ((S(V_FLAGS) & 4) != 0 && number(q, ivec2(640 - 8 - 4 * CELL, 360 - 8 - 5 * SC), Ss(V_FRAGS), 3)) col = vec3(1.0);
    fragColor = vec4(col, 1.0);
}
