#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_dct.py -- test des targets persistantes (etape 3), ne pas editer.
// cible 2x1 : texel 0 = le compteur ; texel 1 = discard, sauf sur un multiple de 256

uniform sampler2D InSampler;

layout(location = 0) out vec4 fragColor;

uint valueAt(sampler2D s, ivec2 p) {
    uvec4 b = uvec4(round(texelFetch(s, p, 0) * 255.0));
    return b.r | (b.g << 8) | (b.b << 16) | (b.a << 24);
}

void main() {
    uint v = valueAt(InSampler, ivec2(0));
    if (int(gl_FragCoord.x) == 1 && (v & 255u) != 0u) discard;
    fragColor = vec4(uvec4(v, v >> 8, v >> 16, v >> 24) & 255u) / 255.0;
}
