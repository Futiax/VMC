#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_qr_detect.py -- ne pas editer a la main.
// Passe persp : cible 2x1. La perspective du QR MESUREE sur les reperes. Le long de la droite
// TL -> TR (image de la ligne 3,5 du QR : une droite reste une droite), les reperes TL et TR
// font chacun 7 modules ; pour une homographie qui fixe les 3 reperes, le rapport de leurs
// longueurs vaut exactement (1 + g)^2. Idem TL -> BL pour h. Plages 1:1:3:1:1 le long de la
// droite en pas entiers (DDA) : memes octets que finder_span() / persp().
// Texel 0 : longueurs TL->TR (R | G << 8) et TR->TL (B | A << 8) ; texel 1 : TL->BL et BL->TL
// (en pas de la droite, 7 modules). 0 = plages ratees.

uniform sampler2D FitSampler;
uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

const int RUN_CAP = 96;
const int RING_TOL2 = 1;
const int CORE_TOL2_DIAG = 2;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
const float DARK_T = 0.25;
const float LIGHT_T = 0.7;

// 0 = ni sombre ni clair, 1 = sombre, 2 = clair. Hors ecran = 0 : texelFetch hors texture
// est indefini, et une plage doit s'arreter au bord comme dans la simulation.
int cls(ivec2 p, ivec2 size) {
    if (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, size))) return 0;
    float y = dot(texelFetch(SrcSampler, p, 0).rgb, LUMA);
    return y < DARK_T ? 1 : (y > LIGHT_T ? 2 : 0);
}

ivec4 fitAt(int i) {
    return ivec4(round(texelFetch(FitSampler, ivec2(i, 0), 0) * 255.0));
}

vec2 point(int i) {
    ivec4 t = fitAt(i);
    return vec2(float(t.r | ((t.b & 15) << 8)), float(t.g | ((t.b >> 4) << 8)));
}

// division entiere vers -inf (celle du GLSL tronque vers 0), b > 0
int fdiv(int a, int b) {
    return a >= 0 ? a / b : -((b - 1 - a) / b);
}

// k-ieme pixel de la droite a -> b, un pas = 1 px sur l'axe majeur
ivec2 dda(ivec2 a, ivec2 b, int k) {
    ivec2 d = b - a;
    int m = max(abs(d.x), abs(d.y));
    return a + ivec2(fdiv(2 * k * d.x + m, 2 * m), fdiv(2 * k * d.y + m, 2 * m));
}

int span(ivec2 a, ivec2 b, ivec2 size) {
    if (cls(a, size) != 1) return 0;
    int r[6];                       // coeur, anneau clair, anneau sombre ; cote -1 puis +1
    for (int s = 0; s < 2; s++) {
        int sgn = s == 0 ? -1 : 1;
        int k = sgn;
        for (int st = 0; st < 3; st++) {
            int state = st == 1 ? 2 : 1;
            int n = 0;
            while (n < RUN_CAP + 1 && cls(dda(a, b, k), size) == state) {
                n++;
                k += sgn;
            }
            if (n > RUN_CAP) return 0;
            r[s * 3 + st] = n;
        }
    }
    int core = r[0] + r[3] + 1;
    int tot = core + r[1] + r[2] + r[4] + r[5];
    if (2 * abs(7 * r[1] - tot) > RING_TOL2 * tot || 2 * abs(7 * r[2] - tot) > RING_TOL2 * tot
            || 2 * abs(7 * r[4] - tot) > RING_TOL2 * tot || 2 * abs(7 * r[5] - tot) > RING_TOL2 * tot) {
        return 0;
    }
    if (2 * abs(7 * core - 3 * tot) > CORE_TOL2_DIAG * tot) return 0;
    return tot;
}

void main() {
    int i = int(gl_FragCoord.x);
    fragColor = vec4(0.0);
    if (fitAt(3).a != 255) return;
    ivec2 tl = ivec2(point(0)), e = ivec2(point(i == 0 ? 1 : 2));
    ivec2 size = textureSize(SrcSampler, 0);
    int s0 = span(tl, e, size), s1 = span(e, tl, size);
    fragColor = vec4(float(s0 & 255), float(s0 >> 8), float(s1 & 255), float(s1 >> 8)) / 255.0;
}
