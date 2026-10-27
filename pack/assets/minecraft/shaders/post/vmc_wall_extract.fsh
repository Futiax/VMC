#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_qr_detect.py -- ne pas editer a la main.
// vmc_data, passe extract : cible 430x238, une cellule de donnees du mur par fragment
// (ligne 0 = HAUT du mur, comme la texture de dct_test). Symbole = la couleur de map au centre
// de la cellule par l'homographie du mur (reperes affines + motifs d'alignement de la bande) :
// recherche binaire de la couleur EXACTE (le cas normal, glow frame de jour), sinon la plus
// proche des 244. Transcription de extract() / symbols_of() de gen_dct.py.

uniform sampler2D FitSampler;
uniform sampler2D PerspSampler;
uniform sampler2D AlignSampler;
uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

const ivec2 DATA0 = ivec2(9, 9);   // premiere cellule de donnees, en modules
const int KEYS[244] = int[244](16640, 22272, 27136, 29214, 31744, 39208, 47922, 55610, 679750, 737862, 855309, 950109, 1005662, 1118481, 1153906, 1207411, 1248008, 1356933, 1381653, 1474182, 1644825, 1707787, 1779806, 1971474, 1985354, 2036237, 2171271, 2372989, 2430480, 2573191, 2628114, 2628632, 2629680, 2631720, 2632470, 2638673, 2647138, 2900377, 2955040, 2960820, 3026992, 3149071, 3175280, 3220254, 3308152, 3361970, 3421236, 3431092, 3482392, 3484480, 3487029, 3488029, 3496300, 3549211, 3554588, 3556123, 3559794, 3618780, 3746083, 3837580, 3866880, 3881289, 3881787, 3940139, 4014144, 4157148, 4198676, 4204078, 4210943, 4233878, 4270878, 4273487, 4276545, 4277796, 4287876, 4399454, 4413519, 4415005, 4418573, 4467993, 4605510, 4667443, 4732196, 4739621, 4741412, 4746392, 4859189, 4882687, 4923160, 4931366, 4935503, 4993571, 4996700, 5000268, 5001770, 5013401, 5120307, 5123641, 5177600, 5182745, 5191976, 5196897, 5197647, 5225655, 5315355, 5329233, 5515305, 5516051, 5526663, 5647422, 5649214, 5658198, 5658721, 5725276, 5783852, 5789784, 5792813, 5795116, 5801146, 5844093, 5862761, 5864743, 5869585, 5979170, 6035741, 6085589, 6170738, 6245189, 6291712, 6315383, 6316128, 6440467, 6560025, 6564384, 6575154, 6579300, 6704179, 6717235, 6724056, 6780213, 6827076, 6896972, 6897227, 6908265, 6966326, 7087140, 7105644, 7157401, 7180417, 7182640, 7188501, 7232853, 7293482, 7340544, 7353911, 7354649, 7367818, 7368816, 7368884, 7488283, 7494733, 7566977, 7625812, 7697781, 7960859, 8008487, 8014168, 8087102, 8205720, 8336979, 8339378, 8365974, 8368696, 8375321, 8405847, 8407901, 8476209, 8543810, 8551254, 8609049, 8662060, 8683048, 8684676, 8724770, 8847360, 8874850, 8881537, 8882055, 8996383, 9060931, 9079516, 9211020, 9277598, 9321518, 9402184, 9474192, 9665649, 9715553, 9787244, 9923917, 9984292, 9993063, 10040115, 10043834, 10066329, 10441252, 10505550, 10514975, 10526975, 10592548, 10692906, 10791096, 10987431, 11164020, 11250603, 11445363, 11577398, 11685080, 11796480, 11835530, 11841964, 11842740, 12217644, 12223780, 12228222, 12398641, 12961068, 13092807, 13659534, 13742497, 14010764, 14142786, 14188339, 14200723, 14417920, 14473683, 14474460, 15066419, 15892389, 16247203, 16445005, 16711680, 16776437, 16777215);   // RGB tries
const int KSYM[244] = int[244](27, 24, 25, 131, 26, 128, 129, 130, 231, 219, 115, 228, 216, 112, 229, 217, 203, 230, 113, 218, 114, 200, 99, 171, 223, 201, 47, 96, 202, 127, 191, 168, 187, 83, 195, 91, 220, 97, 227, 44, 179, 215, 123, 169, 221, 98, 235, 124, 188, 184, 80, 192, 88, 103, 163, 107, 67, 45, 170, 222, 139, 155, 43, 224, 176, 125, 212, 183, 46, 120, 189, 185, 81, 193, 89, 95, 243, 3, 75, 135, 232, 175, 100, 160, 104, 64, 225, 126, 199, 51, 177, 190, 186, 82, 194, 90, 211, 151, 136, 213, 39, 152, 40, 121, 111, 87, 167, 147, 19, 226, 180, 233, 35, 178, 101, 23, 161, 105, 65, 92, 240, 0, 72, 132, 214, 122, 63, 172, 137, 153, 41, 159, 207, 196, 48, 234, 102, 106, 66, 162, 208, 148, 181, 11, 36, 108, 84, 93, 241, 1, 73, 143, 133, 138, 164, 144, 154, 42, 16, 59, 239, 32, 173, 20, 71, 197, 182, 49, 60, 209, 94, 242, 2, 74, 79, 149, 134, 37, 7, 156, 109, 119, 85, 204, 15, 174, 55, 31, 145, 165, 17, 8, 33, 198, 50, 21, 140, 210, 150, 38, 56, 236, 110, 61, 86, 146, 166, 157, 18, 68, 205, 34, 22, 76, 9, 4, 116, 62, 12, 141, 52, 28, 57, 158, 237, 206, 69, 10, 77, 142, 5, 117, 58, 238, 13, 53, 29, 70, 78, 6, 118, 14, 54, 30);
const int PAL[244] = int[244](5864743, 7182640, 8368696, 4415005, 11445363, 14010764, 16247203, 8551254, 9211020, 11250603, 13092807, 6908265, 11796480, 14417920, 16711680, 8847360, 7368884, 9079516, 10526975, 5526663, 7697781, 9474192, 10987431, 5789784, 22272, 27136, 31744, 16640, 11842740, 14474460, 16777215, 8882055, 7566977, 9277598, 10791096, 5658721, 6966326, 8543810, 9923917, 5191976, 5197647, 6316128, 7368816, 3881787, 2960820, 3618780, 4210943, 2171271, 6575154, 8087102, 9402184, 4931366, 11841964, 14473683, 16776437, 8881537, 9984292, 12217644, 14188339, 7488283, 8205720, 10043834, 11685080, 6170738, 4746392, 5801146, 6724056, 3559794, 10592548, 12961068, 15066419, 7960859, 5869585, 7188501, 8375321, 4418573, 11164020, 13659534, 15892389, 8405847, 3487029, 4276545, 5000268, 2631720, 7105644, 8684676, 10066329, 5329233, 3496300, 4287876, 5013401, 2638673, 5844093, 7157401, 8339378, 4399454, 2372989, 2900377, 3361970, 1779806, 4732196, 5783852, 6704179, 3549211, 4741412, 5795116, 6717235, 3556123, 7087140, 8662060, 10040115, 5315355, 1118481, 1381653, 1644825, 855309, 11577398, 14142786, 16445005, 8683048, 4233878, 5225655, 6085589, 3175280, 3431092, 4157148, 4882687, 2573191, 39208, 47922, 55610, 29214, 5979170, 7293482, 8476209, 4467993, 5177600, 6291712, 7340544, 3866880, 9665649, 11835530, 13742497, 7232853, 7354649, 8996383, 10441252, 5516051, 6896972, 8407901, 9787244, 5123641, 5196897, 6315383, 7367818, 3881289, 8609049, 10514975, 12223780, 6440467, 4739621, 5792813, 6780213, 3554588, 7353911, 9060931, 10505550, 5515305, 2628632, 3220254, 3746083, 1971474, 6245189, 7625812, 8874850, 4667443, 4014144, 4935503, 5725276, 3026992, 5649214, 6897227, 8014168, 4204078, 3484480, 4273487, 4996700, 2629680, 3482392, 4270878, 4993571, 2628114, 3488029, 4277796, 5001770, 2632470, 6564384, 8008487, 9321518, 4923160, 1707787, 2036237, 2430480, 1248008, 8724770, 10692906, 12398641, 6560025, 6827076, 8336979, 9715553, 5120307, 4198676, 5182745, 6035741, 3149071, 1005662, 1207411, 1474182, 737862, 2647138, 3308152, 3837580, 1985354, 3940139, 4859189, 5647422, 2955040, 950109, 1153906, 1356933, 679750, 4605510, 5658198, 6579300, 3421236, 9993063, 12228222, 14200723, 7494733, 5862761, 7180417, 8365974, 4413519);      // RGB par symbole

ivec4 fitAt(int i) {
    return ivec4(round(texelFetch(FitSampler, ivec2(i, 0), 0) * 255.0));
}

vec2 point(int i) {
    ivec4 t = fitAt(i);
    return vec2(float(t.r | ((t.b & 15) << 8)), float(t.g | ((t.b >> 4) << 8)));
}

const vec2 SPAN = vec2(439.0, 247.0);   // centres de reperes TL-TR, TL-BL, en modules
const vec2 F0 = vec2(4.5, 4.5);      // centre du repere TL dans la grille
const int NT = 10;                  // motifs cherches par align / pick
const int NA = 7;                       // les NA premiers : motifs d'alignement
const vec2 QA[NT] = vec2[NT](vec2(150.5, 4.5), vec2(297.5, 4.5), vec2(4.5, 128.5), vec2(443.5, 128.5), vec2(150.5, 251.5), vec2(297.5, 251.5), vec2(443.5, 251.5), vec2(4.5, 4.5), vec2(443.5, 4.5), vec2(4.5, 251.5));   // centres (colonne, ligne)
const int RAD[NT] = int[NT](2, 2, 2, 2, 2, 2, 2, 3, 3, 3);   // gabarit (2r+1)^2 : 2 = alignement 5x5, 3 = repere 7x7
const float ALIGN_R = 2.0;
const int ALIGN_K = 16;

// grille (colonne, ligne continues) -> ecran. Homographie qui fixe les 3 reperes :
// P = (T + x U + y V) / (1 + g x + h y), (x, y) = (grille - F0) / SPAN
vec2 hmap(vec2 T, vec2 R, vec2 B, vec2 gh, vec2 gp) {
    vec2 q = (gp - F0) / SPAN;
    vec2 U = (1.0 + gh.x) * R - T;
    vec2 V = (1.0 + gh.y) * B - T;
    return (T + q.x * U + q.y * V) / (1.0 + gh.x * q.x + gh.y * q.y);
}

// H0 : (g, h) mesures sur les reperes par la passe persp ; ok = false si une longueur manque
vec2 ghPersp(out bool ok) {
    ivec4 a = ivec4(round(texelFetch(PerspSampler, ivec2(0, 0), 0) * 255.0));
    ivec4 b = ivec4(round(texelFetch(PerspSampler, ivec2(1, 0), 0) * 255.0));
    int s0 = a.r | (a.g << 8), s1 = a.b | (a.a << 8);
    int s2 = b.r | (b.g << 8), s3 = b.b | (b.a << 8);
    ok = min(min(s0, s1), min(s2, s3)) > 0;
    if (!ok) return vec2(0.0);
    return vec2(sqrt(float(s0) / float(s1)), sqrt(float(s2) / float(s3))) - 1.0;
}

// motif k : centre predit, pas de la fenetre de recherche (px), module local (px)
void alignWindow(vec2 T, vec2 R, vec2 B, vec2 gh, int k, out vec2 p0, out int step,
                 out float mloc) {
    vec2 gp = QA[k];
    p0 = hmap(T, R, B, gh, gp);
    vec2 a = hmap(T, R, B, gh, gp + vec2(0.5, 0.0)) - hmap(T, R, B, gh, gp - vec2(0.5, 0.0));
    vec2 b = hmap(T, R, B, gh, gp + vec2(0.0, 0.5)) - hmap(T, R, B, gh, gp - vec2(0.0, 0.5));
    mloc = max(length(a), length(b));
    step = max(1, int(ceil(ALIGN_R * mloc / float(ALIGN_K))));
}

const float FIT_COND = 0.001;

// centre du motif k en px (1/16 de px dans la cible align) ; ok = false s'il n'a pas ete trouve
vec2 alignAt(int k, out bool ok) {
    ivec4 t = ivec4(round(texelFetch(AlignSampler, ivec2(k, 0), 0) * 255.0));
    int x16 = t.r | (t.g << 8), y16 = t.b | (t.a << 8);
    ok = x16 != 0 || y16 != 0;
    return vec2(float(x16), float(y16)) / 16.0;
}

// reperes affines par align / pick (motifs NA, NA+1, NA+2) : remplacent TL, TR, BL du fit,
// dont la position entiere (barycentre de candidats) peut etre fausse de 0,3 module
void refineFinders(inout vec2 T, inout vec2 R, inout vec2 B) {
    bool ok;
    vec2 a = alignAt(NA, ok);
    if (ok) T = a;
    a = alignAt(NA + 1, ok);
    if (ok) R = a;
    a = alignAt(NA + 2, ok);
    if (ok) B = a;
}

// (g, h) aux moindres carres : chaque motif A trouve en (x, y) donne g x (A - TR) + h y (A - BL)
// = P_affine - A. A defaut H0, a defaut l'affine. Transcription de fit_gh() / homography().
vec2 ghFinal(vec2 T, vec2 R, vec2 B) {
    float m00 = 0.0, m01 = 0.0, m11 = 0.0, r0 = 0.0, r1 = 0.0;
    for (int k = 0; k < NA; k++) {
        bool ok;
        vec2 A = alignAt(k, ok);
        if (!ok) continue;
        vec2 q = (QA[k] - F0) / SPAN;
        vec2 P = T + q.x * (R - T) + q.y * (B - T);
        vec2 a0 = q.x * (A - R), a1 = q.y * (A - B);
        m00 += dot(a0, a0);
        m01 += dot(a0, a1);
        m11 += dot(a1, a1);
        r0 += dot(a0, P - A);
        r1 += dot(a1, P - A);
    }
    float det = m00 * m11 - m01 * m01;
    if (det > FIT_COND * m00 * m11 && det > 0.0) {
        return vec2(m11 * r0 - m01 * r1, m00 * r1 - m01 * r0) / det;
    }
    bool pok;
    return ghPersp(pok);       // (0, 0) si la mesure a echoue : la grille affine
}

// motifs cherches : croix cyan sur ceux trouves, rouge a la position predite des autres
vec3 alignMarks(vec2 pf, vec2 T, vec2 R, vec2 B, vec2 gh, vec3 col) {
    for (int k = 0; k < NT; k++) {
        bool ok;
        vec2 a = alignAt(k, ok);
        vec2 d = abs(pf - (ok ? a : hmap(T, R, B, gh, QA[k])));
        if ((d.x <= 0.5 && d.y <= 6.0) || (d.y <= 0.5 && d.x <= 6.0)) {
            col = ok ? vec3(0.0, 0.9, 1.0) : vec3(1.0, 0.15, 0.1);
        }
    }
    return col;
}

void main() {
    ivec2 c = ivec2(gl_FragCoord.xy);
    fragColor = vec4(0.0, 0.0, 0.0, 1.0);
    if (fitAt(3).a != 255) return;
    vec2 T = point(0), R = point(1), B = point(2);
    refineFinders(T, R, B);
    vec2 q = floor(hmap(T, R, B, ghFinal(T, R, B), vec2(DATA0 + c) + 0.5) + 0.5);
    ivec2 size = textureSize(SrcSampler, 0);
    if (!(q.x >= 0.0 && q.y >= 0.0 && q.x < float(size.x) && q.y < float(size.y))) return;
    ivec3 rgb = ivec3(round(texelFetch(SrcSampler, ivec2(q), 0).rgb * 255.0));
    int key = (rgb.r << 16) | (rgb.g << 8) | rgb.b;
    int sym = -1;
    int lo = 0, hi = 243;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        if (KEYS[mid] == key) {
            sym = KSYM[mid];
            break;
        }
        if (KEYS[mid] < key) lo = mid + 1; else hi = mid - 1;
    }
    if (sym < 0) {                 // pas une couleur de map telle quelle : la plus proche
        int best = 1 << 30;
        for (int i = 0; i < 244; i++) {
            ivec3 p = ivec3(PAL[i] >> 16, (PAL[i] >> 8) & 255, PAL[i] & 255) - rgb;
            int d = p.x * p.x + p.y * p.y + p.z * p.z;
            if (d < best) {
                best = d;
                sym = i;
            }
        }
    }
    fragColor = vec4(float(sym) / 255.0, 0.0, 0.0, 1.0);
}
