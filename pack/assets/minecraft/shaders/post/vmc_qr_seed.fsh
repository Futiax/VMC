#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_qr_detect.py -- ne pas editer a la main.
// Passe 1/4 : ce pixel est-il au centre d'un motif de reperage ? Plages 1:1:3:1:1 mesurees
// pixel a pixel en horizontal, vertical, puis sur les deux diagonales (contre-controle).
// Transcription de axis_test / line_test / seed : la demo verifie que la version numpy
// vectorisee et cette marche donnent le meme resultat.
//
// Sortie : R = module horizontal, G = vertical, B = moyen (px), 0 = pas candidat.

uniform sampler2D InSampler;

layout(location = 0) out vec4 fragColor;

const int RUN_CAP = 96;
const int RING_TOL2 = 1;
const int CORE_TOL2 = 1;
const int CORE_TOL2_DIAG = 2;
const int ANISO_NUM = 5;
const int ANISO_DEN = 2;

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

int walk(ivec2 p, ivec2 d, int state, int limit, ivec2 size) {
    int n = 0;
    while (n < limit && cls(p, size) == state) {
        n++;
        p += d;
    }
    return n;
}

// tot = 7 modules en pas de marche. Tout en entiers : meme verdict que numpy, au bit pres.
bool lineTest(ivec2 p, ivec2 d, bool sym, int coreTol2, ivec2 size, out int tot) {
    tot = 0;
    int r[6];                       // coeur, anneau clair, anneau sombre ; gauche puis droite
    ivec2 ends[2];
    for (int s = 0; s < 2; s++) {
        ivec2 sd = s == 0 ? -d : d;
        ivec2 q = p + sd;
        for (int k = 0; k < 3; k++) {
            int n = walk(q, sd, k == 1 ? 2 : 1, RUN_CAP + 1, size);
            if (n > RUN_CAP) return false;
            r[s * 3 + k] = n;
            q += n * sd;
        }
        ends[s] = q;
    }
    int core = r[0] + r[3] + 1;
    tot = core + r[1] + r[2] + r[4] + r[5];
    if (2 * abs(7 * r[1] - tot) > RING_TOL2 * tot || 2 * abs(7 * r[2] - tot) > RING_TOL2 * tot
            || 2 * abs(7 * r[4] - tot) > RING_TOL2 * tot || 2 * abs(7 * r[5] - tot) > RING_TOL2 * tot) {
        return false;
    }
    if (2 * abs(7 * core - 3 * tot) > coreTol2 * tot) return false;
    if (sym && 7 * abs(r[0] - r[3]) > tot) return false;
    int need = max(1, tot / 14);    // separateur : au moins un demi-module clair
    return walk(ends[0], -d, 2, need, size) >= need && walk(ends[1], d, 2, need, size) >= need;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InSampler, 0);
    fragColor = vec4(0.0);
    // un pixel non sombre ne coute qu'un fetch ; un sombre au milieu d'un aplat sombre en
    // coute RUN_CAP + 1 avant le rejet -- c'est le pire cas de la passe
    if (cls(p, size) != 1) return;
    int th, tv, td;
    if (!lineTest(p, ivec2(1, 0), true, CORE_TOL2, size, th)) return;
    if (!lineTest(p, ivec2(0, 1), true, CORE_TOL2, size, tv)) return;
    if (ANISO_DEN * max(th, tv) > ANISO_NUM * min(th, tv)) return;
    if (!lineTest(p, ivec2(1, 1), false, CORE_TOL2_DIAG, size, td)) return;
    if (!lineTest(p, ivec2(1, -1), false, CORE_TOL2_DIAG, size, td)) return;
    float mh = clamp(floor(float(th) / 7.0 + 0.5), 1.0, 255.0);
    float mv = clamp(floor(float(tv) / 7.0 + 0.5), 1.0, 255.0);
    float mb = clamp(floor(sqrt(float(th * tv)) / 7.0 + 0.5), 1.0, 255.0);
    fragColor = vec4(mh, mv, mb, 255.0) / 255.0;
}
