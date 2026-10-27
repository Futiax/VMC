#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_qr_detect.py -- ne pas editer a la main.
// qr_classify : ce que le detecteur VOIT. Noir = sombre, blanc = clair, rouge = ni l'un ni
// l'autre (zone morte). Les seuils sont l'hypothese n.1 du detecteur : un ecran QR qui sort
// rouge ici ne donnera aucun candidat dans qr_detect, sans autre symptome.

uniform sampler2D InSampler;

layout(location = 0) out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float DARK_T = 0.25;
const float LIGHT_T = 0.7;

// 0 = ni sombre ni clair, 1 = sombre, 2 = clair. Hors ecran = 0 : texelFetch hors texture
// est indefini, et une plage doit s'arreter au bord comme dans la simulation.
int cls(ivec2 p, ivec2 size) {
    if (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, size))) return 0;
    float y = dot(texelFetch(InSampler, p, 0).rgb, LUMA);
    return y < DARK_T ? 1 : (y > LIGHT_T ? 2 : 0);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int c = cls(p, textureSize(InSampler, 0));
    fragColor = vec4(c == 1 ? vec3(0.0) : (c == 2 ? vec3(1.0) : vec3(1.0, 0.0, 0.0)), 1.0);
}
