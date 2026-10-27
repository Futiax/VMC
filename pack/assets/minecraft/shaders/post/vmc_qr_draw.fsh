#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_qr_detect.py -- ne pas editer a la main.
// Passe 4/4 : candidats en magenta, croix sur les reperes (TL rouge, TR vert, BL bleu), quad
// plein sur les trois cotes mesures et en pointilles vers le 4e coin, ESTIME en affine
// (q2 = TL + (1 + K)(u + v)) : trois points ne donnent pas la perspective.
//
// HUD en haut a gauche : pastille verte (quad valide) ou rouge, puis trois nombres :
// motif de rejet, tuiles non vides, candidats / 16. Motifs : 1 < 3 grappes, 2 bruit,
// 4 modules incoherents, 8 jambes inegales ou angle, 16 jambe / 50 modules hors plage.

uniform sampler2D InSampler;    // cand
uniform sampler2D FitSampler;   // fit 4x1
uniform sampler2D SrcSampler;   // image d'origine

layout(location = 0) out vec4 fragColor;

const float K = 0.07;

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

// distance au segment ; along = position le long du segment, en px depuis a
float segDist(vec2 p, vec2 a, vec2 b, out float along) {
    vec2 e = b - a;
    float l = max(dot(e, e), 1e-6);
    float t = clamp(dot(p - a, e) / l, 0.0, 1.0);
    along = t * sqrt(l);
    return distance(p, a + e * t);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec2 pf = vec2(p);
    ivec2 size = textureSize(SrcSampler, 0);
    vec3 col = texelFetch(SrcSampler, p, 0).rgb;
    if (texelFetch(InSampler, p, 0).r > 0.0) col = vec3(1.0, 0.0, 1.0);

    ivec4 st = fitAt(3);
    if (st.a == 255) {
        vec2 tl = point(0), tr = point(1), bl = point(2);
        vec2 u = tr - tl, v = bl - tl;
        vec2 q0 = tl - K * (u + v);
        vec2 q1 = tl + (1.0 + K) * u - K * v;
        vec2 q2 = tl + (1.0 + K) * (u + v);
        vec2 q3 = tl - K * u + (1.0 + K) * v;
        float along;
        if (segDist(pf, q0, q1, along) <= 1.5 || segDist(pf, q0, q3, along) <= 1.5) {
            col = vec3(1.0, 0.9, 0.0);
        }
        if ((segDist(pf, q1, q2, along) <= 1.5 && mod(floor(along / 8.0), 2.0) == 0.0)
                || (segDist(pf, q3, q2, along) <= 1.5 && mod(floor(along / 8.0), 2.0) == 0.0)) {
            col = vec3(1.0, 0.9, 0.0);
        }
        vec3 cc[3] = vec3[3](vec3(1.0, 0.15, 0.15), vec3(0.15, 1.0, 0.15), vec3(0.25, 0.45, 1.0));
        for (int i = 0; i < 3; i++) {
            vec2 d = abs(pf - point(i));
            if ((d.x <= 1.0 && d.y <= 12.0) || (d.y <= 1.0 && d.x <= 12.0)) col = cc[i];
        }
    }

    fragColor = vec4(hud(p, size, st, col), 1.0);
}
