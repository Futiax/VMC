#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_qr_detect.py -- ne pas editer a la main.
// qr_bits, passe d'affichage : sur chaque module du QR, le bit lu par vmc_qr_sample -- 1 vert
// sur un module clair, 0 rouge sur un sombre, ? jaune ni l'un ni l'autre. Le chiffre est
// dessine DANS le module avec la meme homographie que l'echantillonnage, inversee : s'il
// deborde sur le voisin, c'est la geometrie qui a derive, pas la lecture.
// Croix cyan sur les motifs d'alignement trouves, rouge a la position predite des autres.
// ATTENTION : la norme QR fait l'inverse (module sombre = bit 1). Ici clair = 1, a la demande.

uniform sampler2D InSampler;    // bits 57x57
uniform sampler2D FitSampler;
uniform sampler2D PerspSampler;
uniform sampler2D AlignSampler;
uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

const int QR_N = 57;
const int QMARK = 29314;

ivec4 fitAt(int i) {
    return ivec4(round(texelFetch(FitSampler, ivec2(i, 0), 0) * 255.0));
}

vec2 point(int i) {
    ivec4 t = fitAt(i);
    return vec2(float(t.r | ((t.b & 15) << 8)), float(t.g | ((t.b >> 4) << 8)));
}
const int SC = 4;
const int FONT[10] = int[10](31599, 11415, 29671, 29647, 23497, 31183, 31215, 29257, 31727, 31695);

// nombre a 3 chiffres (zeros compris) dont le coin bas-gauche est en o
bool digits3(ivec2 p, ivec2 o, int value) {
    ivec2 q = p - o;
    if (q.x < 0 || q.y < 0 || q.x >= 12 * SC || q.y >= 5 * SC) return false;
    int fx = q.x / SC, fy = q.y / SC;
    int di = fx / 4, cx = fx - di * 4;
    if (cx >= 3) return false;
    int power = di == 0 ? 100 : (di == 1 ? 10 : 1);
    int glyph = FONT[(value / power) % 10];
    // gl_FragCoord.y monte, la police descend : on retourne la ligne
    return ((glyph >> (14 - ((4 - fy) * 3 + cx))) & 1) == 1;
}

// HUD en haut a gauche : fond sombre, pastille, motif de rejet / tuiles / candidats / 16
vec3 hud(ivec2 p, ivec2 size, ivec4 st, vec3 col) {
    ivec2 o = ivec2(8, size.y - 8 - 20);
    ivec2 q = p - o;
    if (q.x >= -4 && q.y >= -4 && q.x < 24 + 3 * 13 * SC && q.y < 20 + 4) {
        col = col * 0.25;
        if (q.x >= 0 && q.y >= 0 && q.x < 20 && q.y < 20) {
            col = st.a == 255 ? vec3(0.1, 0.9, 0.2) : vec3(0.9, 0.15, 0.1);
        }
        if (digits3(p, o + ivec2(24, 0), st.b) || digits3(p, o + ivec2(24 + 13 * SC, 0), st.r)
                || digits3(p, o + ivec2(24 + 26 * SC, 0), st.g)) {
            col = vec3(1.0);
        }
    }
    return col;
}

const vec2 SPAN = vec2(50.0, 50.0);   // centres de reperes TL-TR, TL-BL, en modules
const vec2 F0 = vec2(3.5, 3.5);      // centre du repere TL dans la grille
const int NT = 6;                  // motifs cherches par align / pick
const int NA = 6;                       // les NA premiers : motifs d'alignement
const vec2 QA[NT] = vec2[NT](vec2(28.5, 6.5), vec2(6.5, 28.5), vec2(28.5, 28.5), vec2(50.5, 28.5), vec2(28.5, 50.5), vec2(50.5, 50.5));   // centres (colonne, ligne)
const int RAD[NT] = int[NT](2, 2, 2, 2, 2, 2);   // gabarit (2r+1)^2 : 2 = alignement 5x5, 3 = repere 7x7
const float ALIGN_R = 2.0;
const int ALIGN_K = 16;

// grille (colonne, ligne continues) -> ecran. Homographie qui fixe les 3 reperes :
// P = (T + x U + y V) / (1 + g x + h y), (x, y) = (grille - F0) / SPAN
vec2 hmap(vec2 T, vec2 R, vec2 B, vec2 gh, vec2 gp) {
    vec2 q = (gp - F0) / SPAN;
    vec2 U = (1.0 + gh.x) * R - T;
    vec2 V = (1.0 + gh.y) * B - T;
    return (T + q.x * U + q.y * V) / (1.0 + gh.x * q.x + gh.y * q.y);
}

// H0 : (g, h) mesures sur les reperes par la passe persp ; ok = false si une longueur manque
vec2 ghPersp(out bool ok) {
    ivec4 a = ivec4(round(texelFetch(PerspSampler, ivec2(0, 0), 0) * 255.0));
    ivec4 b = ivec4(round(texelFetch(PerspSampler, ivec2(1, 0), 0) * 255.0));
    int s0 = a.r | (a.g << 8), s1 = a.b | (a.a << 8);
    int s2 = b.r | (b.g << 8), s3 = b.b | (b.a << 8);
    ok = min(min(s0, s1), min(s2, s3)) > 0;
    if (!ok) return vec2(0.0);
    return vec2(sqrt(float(s0) / float(s1)), sqrt(float(s2) / float(s3))) - 1.0;
}

// motif k : centre predit, pas de la fenetre de recherche (px), module local (px)
void alignWindow(vec2 T, vec2 R, vec2 B, vec2 gh, int k, out vec2 p0, out int step,
                 out float mloc) {
    vec2 gp = QA[k];
    p0 = hmap(T, R, B, gh, gp);
    vec2 a = hmap(T, R, B, gh, gp + vec2(0.5, 0.0)) - hmap(T, R, B, gh, gp - vec2(0.5, 0.0));
    vec2 b = hmap(T, R, B, gh, gp + vec2(0.0, 0.5)) - hmap(T, R, B, gh, gp - vec2(0.0, 0.5));
    mloc = max(length(a), length(b));
    step = max(1, int(ceil(ALIGN_R * mloc / float(ALIGN_K))));
}

const float FIT_COND = 0.001;

// centre du motif k en px (1/16 de px dans la cible align) ; ok = false s'il n'a pas ete trouve
vec2 alignAt(int k, out bool ok) {
    ivec4 t = ivec4(round(texelFetch(AlignSampler, ivec2(k, 0), 0) * 255.0));
    int x16 = t.r | (t.g << 8), y16 = t.b | (t.a << 8);
    ok = x16 != 0 || y16 != 0;
    return vec2(float(x16), float(y16)) / 16.0;
}

// (g, h) aux moindres carres : chaque motif A trouve en (x, y) donne g x (A - TR) + h y (A - BL)
// = P_affine - A. A defaut H0, a defaut l'affine. Transcription de fit_gh() / homography().
vec2 ghFinal(vec2 T, vec2 R, vec2 B) {
    float m00 = 0.0, m01 = 0.0, m11 = 0.0, r0 = 0.0, r1 = 0.0;
    for (int k = 0; k < NA; k++) {
        bool ok;
        vec2 A = alignAt(k, ok);
        if (!ok) continue;
        vec2 q = (QA[k] - F0) / SPAN;
        vec2 P = T + q.x * (R - T) + q.y * (B - T);
        vec2 a0 = q.x * (A - R), a1 = q.y * (A - B);
        m00 += dot(a0, a0);
        m01 += dot(a0, a1);
        m11 += dot(a1, a1);
        r0 += dot(a0, P - A);
        r1 += dot(a1, P - A);
    }
    float det = m00 * m11 - m01 * m01;
    if (det > FIT_COND * m00 * m11 && det > 0.0) {
        return vec2(m11 * r0 - m01 * r1, m00 * r1 - m01 * r0) / det;
    }
    bool pok;
    return ghPersp(pok);       // (0, 0) si la mesure a echoue : la grille affine
}

// motifs cherches : croix cyan sur ceux trouves, rouge a la position predite des autres
vec3 alignMarks(vec2 pf, vec2 T, vec2 R, vec2 B, vec2 gh, vec3 col) {
    for (int k = 0; k < NT; k++) {
        bool ok;
        vec2 a = alignAt(k, ok);
        vec2 d = abs(pf - (ok ? a : hmap(T, R, B, gh, QA[k])));
        if ((d.x <= 0.5 && d.y <= 6.0) || (d.y <= 0.5 && d.x <= 6.0)) {
            col = ok ? vec3(0.0, 0.9, 1.0) : vec3(1.0, 0.15, 0.1);
        }
    }
    return col;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(SrcSampler, 0);
    vec3 col = texelFetch(SrcSampler, p, 0).rgb;
    ivec4 st = fitAt(3);
    if (st.a == 255) {
        vec2 T = point(0), R = point(1), B = point(2);
        vec2 gh = ghFinal(T, R, B);
        // ecran -> grille : l'homographie inversee. Colonnes de M : (U, g), (V, h), (T, 1)
        mat3 M = mat3(vec3((1.0 + gh.x) * R - T, gh.x), vec3((1.0 + gh.y) * B - T, gh.y),
                      vec3(T, 1.0));
        vec3 r = inverse(M) * vec3(vec2(p), 1.0);
        vec2 uv = r.xy / r.z * SPAN + F0;    // en modules, F0 = centre du repere TL
        if (r.z > 0.0 && all(greaterThanEqual(uv, vec2(0.0))) && all(lessThan(uv, vec2(float(QR_N))))) {
            ivec2 ij = ivec2(floor(uv));
            vec2 f = uv - vec2(ij);
            int c = int(round(texelFetch(InSampler, ij, 0).r * 255.0));
            // glyphe 3x5 au milieu du module ; f.y descend vers BL comme les lignes de FONT,
            // donc pas de retournement ici, et le chiffre suit la rotation du QR
            int gx = int(floor((f.x - 0.2) / 0.6 * 3.0));
            int gy = int(floor((f.y - 0.1) / 0.8 * 5.0));
            if (gx >= 0 && gx < 3 && gy >= 0 && gy < 5) {
                int glyph = c == 2 ? FONT[1] : (c == 1 ? FONT[0] : QMARK);
                if (((glyph >> (14 - (gy * 3 + gx))) & 1) == 1) {
                    col = c == 2 ? vec3(0.1, 0.8, 0.2)
                                 : (c == 1 ? vec3(1.0, 0.2, 0.15) : vec3(1.0, 0.85, 0.0));
                }
            }
        }
        col = alignMarks(vec2(p), T, R, B, gh, col);
    }
    fragColor = vec4(hud(p, size, st, col), 1.0);
}
