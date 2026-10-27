#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_dct.py -- ne pas editer a la main.
// vmc_data_lan, passe words : cible 512x112, un mot de 31 bits par texel (octets poids faible
// d'abord dans r, g, b, a). Mot w = les symboles 4w..4w+3 de la zone de donnees (622x366, ligne par
// ligne), chiffres en base 244 poids faible d'abord.

uniform sampler2D CellsSampler;   // un symbole (0..243) par texel, ligne 0 = haut du mur

layout(location = 0) out vec4 fragColor;

const int DW = 622;
const int NWORDS = 56913;

void main() {
    ivec2 f = ivec2(gl_FragCoord.xy);
    int w = f.y * 512 + f.x;
    uint v = 0u;
    if (w < NWORDS) {
        for (int i = 3; i >= 0; i--) {
            int c = 4 * w + i;
            v = v * 244u + uint(round(texelFetch(CellsSampler, ivec2(c % DW, c / DW), 0).r * 255.0));
        }
    }
    fragColor = vec4(uvec4(v, v >> 8, v >> 16, v >> 24) & 255u) / 255.0;
}
