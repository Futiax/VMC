#version 330
#extension GL_ARB_separate_shader_objects : require

// GENERE par gen_dct.py -- ne pas editer a la main.
// Passe blocks : cible (SX * 6) x SY, un texel par bloc (Y haut-gauche, haut-droit, bas-gauche,
// bas-droit, Cb, Cr) : le bit ou commencent ses AC (21 bits) et son DC (11 bits, complement a deux),
// mot de 32 bits octets poids faible d'abord. Relit les blocs qui le precedent dans le macrobloc.

uniform sampler2D MbStartSampler;

layout(location = 0) out vec4 fragColor;

uniform sampler2D WordsSampler;   // un mot de 31 bits par texel, octets poids faible d'abord

const int WW = 512;
const int QB_BITS = 8;
const int ROW_BITS = 21;
const int LEN_BITS = 11;
// Huffman canonique (annexe C), tables 0 DC Y, 1 DC C, 2 AC Y, 3 AC C ; indice t * 16 + L - 1
const int LIM[64] = int[64](0, 16384, 57344, 61440, 63488, 64512, 65024, 65280, 65408, 65408, 65408, 65408, 65408, 65408, 65408, 65408, 0, 49152, 57344, 61440, 63488, 64512, 65024, 65280, 65408, 65472, 65504, 65504, 65504, 65504, 65504, 65504, 0, 32768, 40960, 53248, 59392, 61440, 63488, 64256, 64896, 65216, 65344, 65408, 65408, 65408, 65410, 65535, 0, 32768, 40960, 49152, 57344, 61440, 62976, 64000, 64896, 65216, 65344, 65408, 65408, 65412, 65416, 65535);
const int FIRST[64] = int[64](0, 0, 2, 14, 30, 62, 126, 254, 510, 1022, 2044, 4088, 8176, 16352, 32704, 65408, 0, 0, 6, 14, 30, 62, 126, 254, 510, 1022, 2046, 4094, 8188, 16376, 32752, 65504, 0, 0, 4, 10, 26, 58, 120, 248, 502, 1014, 2038, 4084, 8176, 16352, 32704, 65410, 0, 0, 4, 10, 24, 56, 120, 246, 500, 1014, 2038, 4084, 8176, 16352, 32706, 65416);
const int OFF[64] = int[64](0, 0, 1, 6, 7, 8, 9, 10, 11, 12, 12, 12, 12, 12, 12, 12, 0, 0, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 12, 12, 12, 12, 0, 0, 2, 3, 6, 9, 11, 15, 18, 23, 28, 32, 36, 36, 36, 37, 0, 0, 2, 3, 5, 9, 13, 16, 20, 27, 32, 36, 40, 40, 41, 43);
const int VOFF[4] = int[4](0, 12, 24, 186);
const int VALS[348] = int[348](0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 1, 2, 3, 0, 4, 17, 5, 18, 33, 49, 65, 6, 19, 81, 97, 7, 34, 113, 20, 50, 129, 145, 161, 8, 35, 66, 177, 193, 21, 82, 209, 240, 36, 51, 98, 114, 130, 9, 10, 22, 23, 24, 25, 26, 37, 38, 39, 40, 41, 42, 52, 53, 54, 55, 56, 57, 58, 67, 68, 69, 70, 71, 72, 73, 74, 83, 84, 85, 86, 87, 88, 89, 90, 99, 100, 101, 102, 103, 104, 105, 106, 115, 116, 117, 118, 119, 120, 121, 122, 131, 132, 133, 134, 135, 136, 137, 138, 146, 147, 148, 149, 150, 151, 152, 153, 154, 162, 163, 164, 165, 166, 167, 168, 169, 170, 178, 179, 180, 181, 182, 183, 184, 185, 186, 194, 195, 196, 197, 198, 199, 200, 201, 202, 210, 211, 212, 213, 214, 215, 216, 217, 218, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250, 0, 1, 2, 3, 17, 4, 5, 33, 49, 6, 18, 65, 81, 7, 97, 113, 19, 34, 50, 129, 8, 20, 66, 145, 161, 177, 193, 9, 35, 51, 82, 240, 21, 98, 114, 209, 10, 22, 36, 52, 225, 37, 241, 23, 24, 25, 26, 38, 39, 40, 41, 42, 53, 54, 55, 56, 57, 58, 67, 68, 69, 70, 71, 72, 73, 74, 83, 84, 85, 86, 87, 88, 89, 90, 99, 100, 101, 102, 103, 104, 105, 106, 115, 116, 117, 118, 119, 120, 121, 122, 130, 131, 132, 133, 134, 135, 136, 137, 138, 146, 147, 148, 149, 150, 151, 152, 153, 154, 162, 163, 164, 165, 166, 167, 168, 169, 170, 178, 179, 180, 181, 182, 183, 184, 185, 186, 194, 195, 196, 197, 198, 199, 200, 201, 202, 210, 211, 212, 213, 214, 215, 216, 217, 218, 226, 227, 228, 229, 230, 231, 232, 233, 234, 242, 243, 244, 245, 246, 247, 248, 249, 250);

uint wordAt(int w) {
    ivec4 b = ivec4(round(texelFetch(WordsSampler, ivec2(w % WW, w / WW), 0) * 255.0));
    return (uint(b.r) | (uint(b.g) << 8) | (uint(b.b) << 16) | (uint(b.a) << 24)) & 0x7FFFFFFFu;
}

// k bits (1 a 31) a partir du bit n du flux, poids fort d'abord (bit n = bit 30 - n % 31 du mot n / 31)
uint bitsAt(int n, int k) {
    int w = n / 31;
    int r = 31 - (n - w * 31);                 // bits restant dans le mot w
    uint v = wordAt(w) & ((1u << uint(r)) - 1u);
    if (r >= k) return v >> uint(r - k);
    return (v << uint(k - r)) | (wordAt(w + 1) >> uint(31 - k + r));
}

// symbole Huffman de la table t au bit n (n avance) ; -1 si le code n'existe pas
int huff(int t, inout int n) {
    int p = int(bitsAt(n, 16));
    for (int L = 1; L <= 16; L++) {
        int i = t * 16 + L - 1;
        if (p < LIM[i]) {
            n += L;
            return VALS[VOFF[t] + OFF[i] + (p >> (16 - L)) - FIRST[i]];
        }
    }
    return -1;
}

// amplitude JPEG de s bits au bit n (n avance) : bit de poids fort a 0 = negatif
int amp(inout int n, int s) {
    if (s <= 0) return 0;
    int v = int(bitsAt(n, s));
    n += s;
    return v < (1 << (s - 1)) ? v - (1 << s) + 1 : v;
}

void skipAC(int t, inout int n) {
    int i = 1;
    for (int it = 0; it < 80 && i < 64; it++) {
        int s = huff(t, n);
        if (s <= 0) return;                          // EOB, ou code invalide
        if (s == 0xF0) {
            i += 16;
            continue;
        }
        i += (s >> 4) + 1;
        n += s & 15;
    }
}

void main() {
    ivec2 f = ivec2(gl_FragCoord.xy);
    int mx = f.x / 6, b = f.x - mx * 6;
    ivec3 m = ivec3(round(texelFetch(MbStartSampler, ivec2(mx, f.y), 0).rgb * 255.0));
    int n = m.r | (m.g << 8) | (m.b << 16);
    int dcy = 0, dc = 0;
    for (int j = 0; j < 6; j++) {
        int cat = huff(j < 4 ? 0 : 1, n);
        int v = amp(n, cat);
        if (j < 4) dcy = j == 0 ? v : dcy + v;      // Y : le 1er absolu, puis des differences
        dc = j < 4 ? dcy : v;
        if (j == b) break;
        skipAC(j < 4 ? 2 : 3, n);
    }
    uint o = (uint(n) << 11) | (uint(dc) & 0x7FFu);
    fragColor = vec4(uvec4(o, o >> 8, o >> 16, o >> 24) & 255u) / 255.0;
}
