#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par tools/doom/gen_level.py, ne pas editer.
// Decode la balise (coin bas-gauche, ecrite par text.fsh) : le pixel (x, y) de la cible state
// recoit la valeur de 16 bits numero y * 64 + x (4 demi-octets, poids faible d'abord, a partir
// du texel 4), en R (octet bas) et G (octet haut).

uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

const ivec3 NIB[16] = ivec3[16](ivec3(247, 233, 163), ivec3(199, 199, 199), ivec3(255, 0, 0), ivec3(160, 160, 255), ivec3(167, 167, 167), ivec3(0, 124, 0), ivec3(255, 255, 255), ivec3(164, 168, 184), ivec3(151, 109, 77), ivec3(112, 112, 112), ivec3(64, 64, 255), ivec3(143, 119, 72), ivec3(255, 252, 245), ivec3(216, 127, 51), ivec3(178, 76, 216), ivec3(102, 153, 216));

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
    int k = 4 + 4 * (p.y * 64 + p.x);
    int v = 0;
    if (k + 3 < 128 * 128) {
        for (int n = 0; n < 4; n++) {
            v |= nibbleAt(k + n) << (4 * n);
        }
    }
    fragColor = vec4(float(v & 255) / 255.0, float(v >> 8) / 255.0, 0.0, 1.0);
}
