#version 330
#extension GL_ARB_separate_shader_objects : require

// TEST du canal de donnees "temps" : verifie que l'UBO Globals (GameTime)
// est bien fourni aux post-effects en 26.3. GameTime = worldAge % 24000 /
// 24000 — il avance de 20/24000 par seconde.
//
// Resultat attendu en jeu :
//   - l'ecran est teinte et la TEINTE FAIT UN CYCLE COMPLET toutes les ~3 s
//     (60 ticks)  -> GameTime marche : le canal serveur->shader est ouvert.
//   - teinte FIGEE (ou magenta pur constant)
//     -> Globals absent des post-effects : plan B a discuter.

#include <minecraft:globals.glsl>

uniform sampler2D InSampler;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0);
    float t = fract(GameTime * 24000.0 / 60.0); // 1 cycle / 60 ticks (3 s)
    vec3 tint = 0.5 + 0.5 * cos(6.2831853 * t + vec3(0.0, 2.094, 4.189));
    fragColor = vec4(mix(color.rgb, tint, 0.6), color.a);
}
