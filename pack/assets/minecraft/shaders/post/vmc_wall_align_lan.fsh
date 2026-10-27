#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_qr_detect.py -- ne pas editer a la main.
// Passe align : cible 330x33, un bloc de 33x33 par motif cherche (k = x / 33).
// Chaque fragment note UNE position de la fenetre de recherche : c0 + (i, j) * pas, c0 = centre
// predit par H0 (la perspective mesuree par persp), pas tel que la fenetre couvre +-ALIGN_R
// modules. Score = modules du gabarit dans le bon etat (5x5 d'alignement : anneau 1 clair ; 7x7 de
// repere : anneau 2 clair ; le reste sombre), gabarit = les centres de modules PROJETES par H0 : juste en echelle et en
// angle meme a 45 degres. Transcription de align_scores() : position en 12 + 12 bits comme
// fit, A = score (0 hors de l'ecran).

uniform sampler2D FitSampler;
uniform sampler2D PerspSampler;
uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

const int ALIGN_W = 33;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float DARK_T = 0.25;
const float LIGHT_T = 0.7;

// 0 = ni sombre ni clair, 1 = sombre, 2 = clair. Hors ecran = 0 : texelFetch hors texture
// est indefini, et une plage doit s'arreter au bord comme dans la simulation.
int cls(ivec2 p, ivec2 size) {
    if (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, size))) return 0;
    float y = dot(texelFetch(SrcSampler, p, 0).rgb, LUMA);
    return y < DARK_T ? 1 : (y > LIGHT_T ? 2 : 0);
}

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

void main() {
    ivec2 f = ivec2(gl_FragCoord.xy);
    int k = f.x / ALIGN_W;
    ivec2 ij = ivec2(f.x - k * ALIGN_W, f.y) - ALIGN_K;
    fragColor = vec4(0.0);
    if (fitAt(3).a != 255) return;
    vec2 T = point(0), R = point(1), B = point(2);
    bool pok;
    vec2 gh = ghPersp(pok);
    vec2 p0;
    int step;
    float mloc;
    alignWindow(T, R, B, gh, k, p0, step, mloc);
    ivec2 c = ivec2(floor(p0 + 0.5)) + ij * step;
    ivec2 size = textureSize(SrcSampler, 0);
    int s = 0;
    if (all(greaterThanEqual(c, ivec2(0))) && all(lessThan(c, size))) {
        int r = RAD[k];
        for (int j = -r; j <= r; j++) {
            for (int i = -r; i <= r; i++) {
                vec2 q = hmap(T, R, B, gh, QA[k] + vec2(float(i), float(j)));
                int want = max(abs(i), abs(j)) == r - 1 ? 2 : 1;
                if (cls(c + ivec2(floor(q - p0 + 0.5)), size) == want) s++;
            }
        }
    }
    ivec2 q = clamp(c, ivec2(0), ivec2(4095));
    fragColor = vec4(float(q.x & 255), float(q.y & 255), float((q.x >> 8) | ((q.y >> 8) << 4)),
                     float(s)) / 255.0;
}
