#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_qr_detect.py -- ne pas editer a la main.
// Passe 2/4 : cible 32x32, un fragment par tuile d'ecran. Nombre de candidats,
// barycentre DANS la tuile, module moyen. Transcription de tile().
//
// Sommes en ENTIERS relatives a l'origine de la tuile : un float32 sommant des x absolus
// (~3840) sur des milliers de pixels deborderait sa mantisse et decalerait le barycentre.
// Arrondis en entiers aussi : un float32 tranchait un x,5 exact autrement que numpy.
// Sortie : R, G = barycentre / taille de tuile, B = sqrt(n) * 3.984375, A = module.

uniform sampler2D InSampler;    // cand

layout(location = 0) out vec4 fragColor;

const int TILE_N = 32;
const float CNT_SCALE = 3.984375;

void main() {
    ivec2 t = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InSampler, 0);
    ivec2 ts = (size + TILE_N - 1) / TILE_N;
    ivec2 o = t * ts;
    ivec2 e = min(o + ts, size);
    int n = 0, sx = 0, sy = 0, sm = 0;
    for (int y = o.y; y < e.y; y++) {
        for (int x = o.x; x < e.x; x++) {
            ivec4 c = ivec4(round(texelFetch(InSampler, ivec2(x, y), 0) * 255.0));
            if (c.r > 0) {
                n++;
                sx += x - o.x;
                sy += y - o.y;
                sm += c.b;
            }
        }
    }
    if (n == 0) {
        fragColor = vec4(0.0);
        return;
    }
    // arrondis entiers, comme tile() : un barycentre pile sur x,5 ne doit pas dependre du float
    ivec4 v = ivec4(clamp((2 * sx * 255 + n * ts.x) / (2 * n * ts.x), 0, 255),
                    clamp((2 * sy * 255 + n * ts.y) / (2 * n * ts.y), 0, 255),
                    int(clamp(floor(sqrt(float(n)) * CNT_SCALE + 0.5), 1.0, 255.0)),
                    clamp((2 * sm + n) / (2 * n), 1, 255));
    fragColor = vec4(v) / 255.0;
}
