#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_qr_detect.py -- ne pas editer a la main.
// qr_bits, passe sample : cible 57x57, un fragment par module (x = colonne, y = ligne, ligne 0
// = celle du repere TL). Etat du pixel d'ecran au CENTRE du module, 0/1/2 comme cls(), par
// l'HOMOGRAPHIE : les 3 reperes + (g, h) ajuste sur les motifs d'alignement trouves (H0, puis
// l'affine, a defaut). Transcription de sample_bits().

uniform sampler2D FitSampler;
uniform sampler2D PerspSampler;
uniform sampler2D AlignSampler;
uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

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
    ivec2 m = ivec2(gl_FragCoord.xy);
    fragColor = vec4(0.0);
    if (fitAt(3).a != 255) return;
    vec2 T = point(0), R = point(1), B = point(2);
    vec2 q = floor(hmap(T, R, B, ghFinal(T, R, B), vec2(m) + 0.5) + 0.5);
    ivec2 size = textureSize(SrcSampler, 0);
    // teste en flottant AVANT la conversion : un point a l'infini (ou NaN) n'est pas un entier
    if (!(q.x >= 0.0 && q.y >= 0.0 && q.x < float(size.x) && q.y < float(size.y))) return;
    fragColor = vec4(float(cls(ivec2(q), size)) / 255.0, 0.0, 0.0, 1.0);
}
