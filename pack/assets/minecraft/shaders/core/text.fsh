#version 330
#extension GL_ARB_separate_shader_objects : require

// MCDATA core shader — text.fsh vanilla 26.3, sans brouillard (le fog vanilla 26.3 a
// fog_start_distance = 0 et altere meme les texels de la palette exacte), avec la sortie
// de la map balise : recopie pixel-par-pixel dans le coin bas-gauche + ModelViewMat en ligne 0
// apres les 128 premiers pixels (rotation camera lue cote client).

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#endif

layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) flat in int mcdataBeacon;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif
    return color;
}

void main() {
    if (mcdataBeacon == 1) {
#if defined(OIT) || defined(OIT_ALPHA_ONLY) || defined(OIT_ACCUMULATE)
        discard;
#else
        // Pixel (x, y) depuis le coin bas-gauche = texel (x, y) de la map ; puis ModelViewMat
        // colonne par colonne, chaque valeur de [-1, 1] sur 16 bits (R poids fort, G poids faible).
        ivec2 p = ivec2(gl_FragCoord.xy);
        if (p.x < 128 && p.y < 128) {
            fragColor = vec4(texelFetch(Sampler0, p, 0).rgb, 1.0);
        } else if (p.y == 0 && p.x < 128 + 9) {
            int k = p.x - 128;
            float v = ModelViewMat[k / 3][k % 3];
            int u = int(clamp(round((v + 1.0) * 32767.5), 0.0, 65535.0));
            fragColor = vec4(float(u >> 8) / 255.0, float(u & 255) / 255.0, 90.0 / 255.0, 1.0);
        } else {
            discard;
        }
        return;
#endif
    }

    #ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
    #else
    vec4 texColor = texture(Sampler0, texCoord0);
    #endif

    vec4 color = texColor * vertexColor * ColorModulator;

    if (color.a < 0.1) {
        discard;
    }

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
