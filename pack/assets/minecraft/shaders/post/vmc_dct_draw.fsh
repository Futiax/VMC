#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_dct.py -- ne pas editer a la main.
// Passe draw : le canva 848x464 au centre de l'ecran, agrandi pour le remplir. Ailleurs, la scene.

uniform sampler2D ImgSampler;
uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

const ivec2 CANVAS = ivec2(848, 464);

bool canvasAt(ivec2 p, ivec2 size, inout vec3 col) {
    float k = min(float(size.x) / float(CANVAS.x), float(size.y) / float(CANVAS.y));
    vec2 o = (vec2(size) - vec2(CANVAS) * k) * 0.5;
    vec2 q = (vec2(float(p.x), float(size.y - 1 - p.y)) + 0.5 - o) / k;   // depuis le HAUT
    if (!(all(greaterThanEqual(q, vec2(0.0))) && all(lessThan(q, vec2(CANVAS))))) return false;
    vec2 t = q - 0.5;
    ivec2 a = ivec2(floor(t));
    vec2 f = t - vec2(a);
    ivec2 b = min(a + 1, CANVAS - 1);
    a = max(a, ivec2(0));
    vec3 top = mix(texelFetch(ImgSampler, a, 0).rgb, texelFetch(ImgSampler, ivec2(b.x, a.y), 0).rgb, f.x);
    vec3 bot = mix(texelFetch(ImgSampler, ivec2(a.x, b.y), 0).rgb, texelFetch(ImgSampler, b, 0).rgb, f.x);
    col = mix(top, bot, f.y);
    return true;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(SrcSampler, 0);
    vec3 col = texelFetch(SrcSampler, p, 0).rgb;
    canvasAt(p, size, col);
    fragColor = vec4(col, 1.0);
}
