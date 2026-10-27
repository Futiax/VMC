#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_dct.py -- test des targets persistantes (etape 3), ne pas editer.
// les quatre valeurs en chiffres, a gauche de l'ecran, sur la scene

uniform sampler2D Count1Sampler;
uniform sampler2D Count2Sampler;
uniform sampler2D HalfSampler;
uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

const int SC = 4;                 // pixels d'ecran par pixel de police
const int ND = 8;                 // chiffres par nombre
const int FONT[10] = int[10](31599, 11415, 29671, 29647, 23497, 31183, 31215, 29257, 31727, 31695);

uint valueAt(sampler2D s, ivec2 p) {
    uvec4 b = uvec4(round(texelFetch(s, p, 0) * 255.0));
    return b.r | (b.g << 8) | (b.b << 16) | (b.a << 24);
}

// nombre a ND chiffres (zeros compris), coin bas-gauche en o
bool digits(ivec2 p, ivec2 o, uint value) {
    ivec2 q = p - o;
    if (q.x < 0 || q.y < 0 || q.x >= 4 * ND * SC || q.y >= 5 * SC) return false;
    int fx = q.x / SC, fy = q.y / SC;
    int di = fx / 4, cx = fx - di * 4;
    if (cx >= 3) return false;
    for (int k = 0; k < ND - 1 - di; k++) value /= 10u;
    int glyph = FONT[int(value % 10u)];
    return ((glyph >> (14 - ((4 - fy) * 3 + cx))) & 1) == 1;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(SrcSampler, 0);
    vec3 col = texelFetch(SrcSampler, p, 0).rgb;
    uint v[4] = uint[4](valueAt(Count1Sampler, ivec2(0)), valueAt(Count2Sampler, ivec2(0)),
                        valueAt(HalfSampler, ivec2(0)), valueAt(HalfSampler, ivec2(1, 0)));
    vec3 c[4] = vec3[4](vec3(1.0), vec3(1.0, 0.85, 0.1), vec3(0.2, 0.9, 1.0), vec3(1.0, 0.3, 1.0));
    ivec2 o = ivec2(16, size.y / 2 + 16 * SC);
    ivec2 q = p - o;
    if (q.x >= -2 * SC && q.x < (4 * ND + 8) * SC && q.y >= -26 * SC && q.y < 7 * SC) col *= 0.2;
    for (int i = 0; i < 4; i++) {
        ivec2 oi = o - ivec2(0, i * 8 * SC);
        ivec2 r = p - oi;
        if (r.x >= 0 && r.y >= 0 && r.x < 5 * SC && r.y < 5 * SC) col = c[i];
        if (digits(p, oi + ivec2(7 * SC, 0), v[i])) col = c[i];
    }
    fragColor = vec4(col, 1.0);
}
