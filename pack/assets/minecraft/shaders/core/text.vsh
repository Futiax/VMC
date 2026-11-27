#version 330
#extension GL_ARB_separate_shader_objects : require

// MCDATA core shader — copie du text.vsh vanilla 26.3 avec ajout :
// la map balise (reconnue a sa signature sur les 4 premiers texels) est deplacee dans le
// coin bas-gauche de l'ecran, en rendu "near clip" pour passer devant tout le decor.

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 3) in ivec2 UV2;
#endif

uniform sampler2D Sampler0;

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif

layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) flat out int mcdataBeacon;

// Signature MCDATA : 4 texels de couleurs fixes en ligne 0. Les RGB correspondent aux
// map colors 73, 77, 81, 85 (voir DataEncoder.SIGNATURE).
const ivec3 SIG[4] = ivec3[4](ivec3(197, 197, 44), ivec3(109, 176, 21), ivec3(208, 109, 142), ivec3(65, 65, 65));

bool isBeacon() {
    if (textureSize(Sampler0, 0) != ivec2(128, 128)) {
        return false;
    }
    for (int i = 0; i < 4; i++) {
        if (ivec3(round(texelFetch(Sampler0, ivec2(i, 0), 0).rgb * 255.0)) != SIG[i]) {
            return false;
        }
    }
    return true;
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
    texCoord0 = UV0;

    mcdataBeacon = 0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    if (isBeacon()) {
        // Quart bas-gauche de l'ecran, profondeur near-clip (0.15 bloc devant la camera).
        vec4 nearClip = ProjMat * vec4(0.0, 0.0, -0.15, 1.0);
        gl_Position = vec4(vec2(UV0.x - 1.0, -UV0.y) * nearClip.w, nearClip.z, nearClip.w);
        mcdataBeacon = 1;
    }
#endif
}
