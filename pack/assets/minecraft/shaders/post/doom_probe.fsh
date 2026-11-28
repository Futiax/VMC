#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par tools/doom/gen_probe.py (essai /doomprobe), ne pas editer.
// Lit les pixels ecrits par text.fsh dans le coin bas-gauche et les affiche dessus, sur un
// panneau : pastilles (signature trouvee, matrice lue), compteur, angles client (cyan) et serveur
// (jaune) en degres.

uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

const ivec3 SIG[4] = ivec3[4](ivec3(197, 197, 44), ivec3(109, 176, 21), ivec3(208, 109, 142), ivec3(65, 65, 65));
const ivec3 NIB[16] = ivec3[16](ivec3(247, 233, 163), ivec3(199, 199, 199), ivec3(255, 0, 0), ivec3(160, 160, 255), ivec3(167, 167, 167), ivec3(0, 124, 0), ivec3(255, 255, 255), ivec3(164, 168, 184), ivec3(151, 109, 77), ivec3(112, 112, 112), ivec3(64, 64, 255), ivec3(143, 119, 72), ivec3(255, 252, 245), ivec3(216, 127, 51), ivec3(178, 76, 216), ivec3(102, 153, 216));

const int PW = 170, PH = 134;     // le panneau couvre la map et les pixels de matrice
const int SC = 3;                 // pixels d'ecran par pixel de police
const int FONT[10] = int[10](31599, 11415, 29671, 29647, 23497, 31183, 31215, 29257, 31727, 31695);

ivec3 rgbAt(int x, int y) {
    return ivec3(round(texelFetch(SrcSampler, ivec2(x, y), 0).rgb * 255.0));
}

int nibbleAt(int x) {
    ivec3 c = rgbAt(x, 0);
    for (int i = 0; i < 16; i++) {
        if (c == NIB[i]) {
            return i;
        }
    }
    return -1;
}

// chiffre ou signe moins (glyph = -1) dans la cellule de coin bas-gauche o
bool glyphAt(ivec2 q, int glyph) {
    if (q.x < 0 || q.y < 0 || q.x >= 3 * SC || q.y >= 5 * SC) {
        return false;
    }
    int cx = q.x / SC, fy = q.y / SC;
    if (glyph < 0) {
        return fy == 2;
    }
    return ((FONT[glyph] >> (14 - ((4 - fy) * 3 + cx))) & 1) == 1;
}

// nombre signe sur nd chiffres (zeros compris), signe dans la premiere cellule
bool number(ivec2 p, ivec2 o, int value, int nd) {
    ivec2 q = p - o;
    int cell = q.x / (4 * SC);
    if (q.x < 0 || cell > nd) {
        return false;
    }
    ivec2 inCell = ivec2(q.x - cell * 4 * SC, q.y);
    if (cell == 0) {
        return value < 0 && glyphAt(inCell, -1);
    }
    int v = abs(value);
    for (int k = 0; k < nd - cell; k++) {
        v /= 10;
    }
    return glyphAt(inCell, v % 10);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    if (p.x >= PW || p.y >= PH) {
        fragColor = texelFetch(SrcSampler, p, 0);
        return;
    }

    bool sig = true;
    for (int i = 0; i < 4; i++) {
        sig = sig && rgbAt(i, 0) == SIG[i];
    }
    bool words = true;
    int w[3];
    for (int j = 0; j < 3; j++) {
        uint v = 0u;
        for (int n = 0; n < 8; n++) {
            int nib = nibbleAt(4 + j * 8 + n);
            words = words && nib >= 0;
            v |= uint(max(nib, 0)) << uint(4 * n);
        }
        w[j] = int(v);
    }
    bool mat = true;
    float m[9];
    for (int k = 0; k < 9; k++) {
        ivec3 c = rgbAt(128 + k, 0);
        mat = mat && c.b == 90;
        m[k] = float(c.r * 256 + c.g) / 32767.5 - 1.0;
    }
    // ModelViewMat envoie le monde (relatif a la camera) dans la vue : sa ligne 2 est l'axe arriere de
    // la camera. Convention Minecraft : yaw 0 = sud (+Z), 90 = ouest ; pitch positif = vers le bas.
    vec3 fwd = -normalize(vec3(m[2], m[5], m[8]));
    int cyaw = int(round(degrees(atan(-fwd.x, fwd.z))));
    int cpitch = int(round(degrees(asin(clamp(-fwd.y, -1.0, 1.0)))));

    vec3 col = vec3(0.08);
    const int LH = 6 * SC + 6;
    int top = PH - 6 - 5 * SC;
    if (p.y >= top && p.y < top + 5 * SC) {
        if (p.x >= 6 && p.x < 6 + 5 * SC) col = sig && words ? vec3(0.1, 0.9, 0.2) : vec3(0.9, 0.1, 0.1);
        if (p.x >= 12 + 5 * SC && p.x < 12 + 10 * SC) col = mat ? vec3(0.1, 0.9, 0.2) : vec3(0.9, 0.1, 0.1);
    }
    if (number(p, ivec2(6, top - LH), w[0], 9)) col = vec3(1.0);
    if (number(p, ivec2(6, top - 2 * LH), cyaw, 3) || number(p, ivec2(6 + 5 * 4 * SC, top - 2 * LH), cpitch, 2)) col = vec3(0.3, 0.9, 1.0);
    if (number(p, ivec2(6, top - 3 * LH), int(round(float(w[1]) / 100.0)), 3) || number(p, ivec2(6 + 5 * 4 * SC, top - 3 * LH), int(round(float(w[2]) / 100.0)), 2)) col = vec3(1.0, 0.85, 0.2);
    fragColor = vec4(col, 1.0);
}
