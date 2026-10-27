#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_qr_detect.py -- ne pas editer a la main.
// Passe pick : cible 10x1, un fragment par motif cherche. Score max de son bloc ; parmi
// les positions au max, la plus proche de la prediction (la premiere dans l'ordre de parcours
// a egalite) ; centre = barycentre des positions au max a moins d'un module de celle-la --
// deux grappes au max ne se moyennent pas. Sous SMIN[k] : motif non trouve (texel nul).
// Sortie : centre en 1/16 de px, x = R | G << 8, y = B | A << 8. Transcription de align_pick().

uniform sampler2D InSampler;    // ascore
uniform sampler2D FitSampler;
uniform sampler2D PerspSampler;

layout(location = 0) out vec4 fragColor;

const int ALIGN_W = 33;
const int SMIN[10] = int[10](23, 23, 23, 23, 23, 23, 23, 45, 45, 45);

ivec4 fitAt(int i) {
    return ivec4(round(texelFetch(FitSampler, ivec2(i, 0), 0) * 255.0));
}

vec2 point(int i) {
    ivec4 t = fitAt(i);
    return vec2(float(t.r | ((t.b & 15) << 8)), float(t.g | ((t.b >> 4) << 8)));
}

const vec2 SPAN = vec2(631.0, 375.0);   // centres de reperes TL-TR, TL-BL, en modules
const vec2 F0 = vec2(4.5, 4.5);      // centre du repere TL dans la grille
const int NT = 10;                  // motifs cherches par align / pick
const int NA = 7;                       // les NA premiers : motifs d'alignement
const vec2 QA[NT] = vec2[NT](vec2(214.5, 4.5), vec2(425.5, 4.5), vec2(4.5, 192.5), vec2(635.5, 192.5), vec2(214.5, 379.5), vec2(425.5, 379.5), vec2(635.5, 379.5), vec2(4.5, 4.5), vec2(635.5, 4.5), vec2(4.5, 379.5));   // centres (colonne, ligne)
const int RAD[NT] = int[NT](2, 2, 2, 2, 2, 2, 2, 3, 3, 3);   // gabarit (2r+1)^2 : 2 = alignement 5x5, 3 = repere 7x7
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

ivec4 scoreAt(int k, int i, int j) {
    return ivec4(round(texelFetch(InSampler, ivec2(k * ALIGN_W + i, j), 0) * 255.0));
}

void main() {
    int k = int(gl_FragCoord.x);
    fragColor = vec4(0.0);
    if (fitAt(3).a != 255) return;
    vec2 T = point(0), R = point(1), B = point(2);
    bool pok;
    vec2 gh = ghPersp(pok);
    vec2 p0;
    int step;
    float mloc;
    alignWindow(T, R, B, gh, k, p0, step, mloc);
    int best = -1, bi = 0, bj = 0, bd = 0;
    for (int j = 0; j < ALIGN_W; j++) {
        for (int i = 0; i < ALIGN_W; i++) {
            int s = scoreAt(k, i, j).a;
            int dd = (i - ALIGN_K) * (i - ALIGN_K) + (j - ALIGN_K) * (j - ALIGN_K);
            if (s > best || (s == best && dd < bd)) {
                best = s;
                bi = i;
                bj = j;
                bd = dd;
            }
        }
    }
    if (best < SMIN[k]) return;
    float lim = mloc * mloc;
    int n = 0, sx = 0, sy = 0;
    for (int j = 0; j < ALIGN_W; j++) {
        for (int i = 0; i < ALIGN_W; i++) {
            ivec4 t = scoreAt(k, i, j);
            int dd = (i - bi) * (i - bi) + (j - bj) * (j - bj);
            if (t.a == best && float(step * step * dd) <= lim) {
                n++;
                sx += t.r | ((t.b & 15) << 8);
                sy += t.g | ((t.b >> 4) << 8);
            }
        }
    }
    int x16 = (32 * sx + n) / (2 * n), y16 = (32 * sy + n) / (2 * n);
    fragColor = vec4(float(x16 & 255), float(x16 >> 8), float(y16 & 255), float(y16 >> 8)) / 255.0;
}
