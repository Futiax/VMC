#!/usr/bin/env python3
"""Codec image a longueur variable (un JPEG de base), decode dans un post effect : le mode data.

    python3 gen_dct.py              # demo() et demo_wall() puis ecrit le pack (shaders, effets)
    python3 gen_dct.py --demo       # les demos seules (DCT_PREVIEWS=<dossier> : apercus PNG)
    python3 gen_dct.py --gpu        # les .fsh ecrits, executes sur le GPU, contre numpy
    python3 gen_dct.py --mcmm <exe> # mcmm --data (le codeur C du serveur) contre ce script
    python3 gen_dct.py --net-stats <mcmm> <video> [debut]   # debit reseau et PSNR sur une video

YCbCr, couleur a demi-resolution (4:2:0), DCT 8x8, quantification par les tables JPEG (qualite 50)
multipliees par une echelle s(q) GLOBALE, puis le codage entropique de JPEG : zigzag, paires (zeros
sautes, taille) en Huffman, amplitudes, ZRL, EOB, tables standard de l'annexe K. Etape 2 de
PLAN_VIDEO_HISTORY.md (13/10) : +5 dB sur les photos de test, +8 sur des frames de video, contre
l'ancien format a champs de largeur fixe (un zero y coutait autant qu'une valeur, et rien ne
passait au-dela du 30e coefficient) ; au niveau d'un vrai JPEG de meme taille.

Le flux, bits de poids fort d'abord :
  q          QB_BITS   s(q) = 2^((q - Q_OFF) / Q_DIV), la plus fine qui tient (dichotomie)
  rows[SY]   ROW_BITS  debut de chaque ligne de macroblocs, compte depuis le debut des donnees
  lens[n]    LEN_BITS  longueur de chaque macrobloc (un macrobloc plus long perd ses plus hautes
                       frequences)
  donnees    les macroblocs ligne par ligne ; chacun : blocs Y haut-gauche, haut-droit,
             bas-gauche, bas-droit, puis Cb, Cr ; chaque bloc : DC (categorie en Huffman +
             amplitude ; Y : le 1er absolu, les 3 autres en difference du precedent ; Cb et Cr
             absolus), puis ses AC
  zeros      jusqu'a la capacite du mur : une image simple tient deja au q le plus fin, et zlib
             ecrase ce reste sur le reseau
Aucune dependance entre macroblocs : le shader decode chacun a part, depuis son debut (rows + les
lens de ceux qui le precedent dans sa ligne). Une longueur mal lue ne fausse que la fin de sa ligne.

Transport : un symbole = une des 244 couleurs de map. 4 symboles = un nombre en base 244 (poids
faible d'abord) qui porte 31 bits : bit n du flux = bit 30 - n % 31 du mot n // 31. Les mots
remplissent TOUTE la zone de donnees du mur, ligne par ligne (cible "cells", ligne 0 = haut).

Passes (dct_test : les memes, depuis une texture du pack) :
  words    cells -> words     un mot de 31 bits par texel
  mbstart  words -> mbstart   debut de chaque macrobloc
  blocks   words -> blocks    debut des AC et DC de chaque bloc (relit les blocs d'avant)
  coefs    words -> coefs     2 coefficients quantifies par texel (relit son bloc jusqu'a eux)
  rows     coefs -> rows      IDCT separable, 1re moitie : chaque ligne de frequences, en 1/16
  idct     rows -> img        2e moitie, un fragment par pixel du canva, YCbCr -> RGB
  draw     img + main -> out  le canva au centre, agrandi pour remplir l'ecran
  blit
"""
import math
import os
import sys

import numpy as np

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)

# --- format ---------------------------------------------------------------------------------

ALPHA = 244                       # symboles = couleurs de map valides (ids 4..247)
WORD_SYMS, WORD_BITS = 4, 31      # 244^4 >= 2^31
SLOT = 8                          # le canva compte un macrobloc 16x16 par carre de 8x8 symboles de
                                  # la zone de donnees (SX, SY, WC_, HC_ : use_wall() plus bas)
QB_BITS, Q_OFF, Q_DIV = 8, 107, 32    # q = 0 : s = 0,099, le plus fin : en dessous le DC deborde ses
                                      # 10 bits, et l'image est deja quasi parfaite (~50 dB)
ROW_BITS = 21                     # 2^21 > la capacite du mur lan (1 764 303 bits)
LEN_BITS = 11                     # un macrobloc fait au plus 2047 bits
LEN_MAX = (1 << LEN_BITS) - 1
AMP_MAX = 1023                    # amplitudes des tables standard : 10 bits (les differences de DC 11)
WORDS_W = 512                     # largeur de la cible words

# tables de quantification JPEG (annexe K, qualite 50), en ordre ligne par ligne
QY = np.array([16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
               14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
               18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
               49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99], float)
QC = np.array([17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99,
               24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99] + [99] * 32, float)


def zigzag():
    """-> liste des (u, v) (u = colonne/frequence horizontale) dans l'ordre zigzag JPEG."""
    out = []
    for s in range(15):
        diag = [(s - v, v) for v in range(8) if 0 <= s - v < 8]
        out += diag if s % 2 else diag[::-1]
    return out


ZZ = zigzag()
ZZI = [0] * 64                    # rang zigzag du coefficient (v, u) : ZZI[v * 8 + u]
for _i, (_u, _v) in enumerate(ZZ):
    ZZI[_v * 8 + _u] = _i
QYZ = np.array([QY[v * 8 + u] for u, v in ZZ])      # pas de base, en ordre zigzag
QCZ = np.array([QC[v * 8 + u] for u, v in ZZ])


def scale(q):
    return 2.0 ** ((q - Q_OFF) / Q_DIV)


# Huffman standard de JPEG (annexe K) : nombre de codes de chaque longueur (1 a 16), symboles.
# DC : symbole = categorie (bits de l'amplitude) ; AC : (zeros sautes << 4) | taille, 0x00 = EOB
# (fin du bloc), 0xF0 = ZRL (16 zeros). Ordre des tables partout : dcy, dcc, acy, acc.
HUF = {"dcy": ("00010501010101010100000000000000", "000102030405060708090a0b"),
       "dcc": ("00030101010101010101010000000000", "000102030405060708090a0b"),
       "acy": ("0002010303020403050504040000017d",
               "01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a1617"
               "18191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a73"
               "7475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9"
               "bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9fa"),
       "acc": ("00020102040403040705040400010277",
               "000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e1"
               "25f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a"
               "737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7"
               "b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9fa")}


def huffman(name):
    """Codes canoniques (annexe C) : -> (longueur [256], code [256]) par symbole, 0 si absent."""
    bits, vals = (bytes.fromhex(h) for h in HUF[name])
    length, code = np.zeros(256, np.int64), np.zeros(256, np.int64)
    c = k = 0
    for L in range(1, 17):
        for _ in range(bits[L - 1]):
            length[vals[k]], code[vals[k]] = L, c
            c, k = c + 1, k + 1
        c <<= 1
    return length, code


H = {n: huffman(n) for n in HUF}
HS = {n: [format(int(c), f"0{int(L)}b") if L else "" for L, c in zip(*H[n])] for n in HUF}   # en bits


def huffman_lut(name):
    """Decodage a 16 bits d'avance : -> (symbole [65536], longueur [65536], 0 = code invalide)."""
    length, code = H[name]
    sym, ln = np.zeros(65536, np.int64), np.zeros(65536, np.int64)
    for s in np.nonzero(length)[0]:
        a, w = int(code[s]) << (16 - int(length[s])), 1 << (16 - int(length[s]))
        sym[a:a + w], ln[a:a + w] = s, length[s]
    return sym.tolist(), ln.tolist()


LUT = {n: huffman_lut(n) for n in HUF}


# --- transformees ---------------------------------------------------------------------------

def dct_matrix():
    """D[u, x] = a(u) cos((2x + 1) u pi / 16) : DCT 8 points orthonormale (celle de JPEG)."""
    d = np.zeros((8, 8))
    for u in range(8):
        for x in range(8):
            d[u, x] = math.sqrt((1 if u == 0 else 2) / 8.0) * math.cos((2 * x + 1) * u * math.pi / 16)
    return d


DM = dct_matrix()
# la chroma est evaluee aux positions des pixels : t = x / 2 - 0,25 en echantillons de chroma
DTC = np.array([[math.sqrt((1 if u == 0 else 2) / 8.0) * math.cos((2 * x + 1) * u * math.pi / 16)
                 for x in np.arange(16) / 2.0 - 0.25] for u in range(8)])          # [u, 16]


def rgb2ycc(rgb):
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    y = 0.299 * r + 0.587 * g + 0.114 * b
    cb = -0.168736 * r - 0.331264 * g + 0.5 * b + 128.0
    cr = 0.5 * r - 0.418688 * g - 0.081312 * b + 128.0
    return y, cb, cr


def ycc2rgb(y, cb, cr):
    return np.stack([y + 1.402 * (cr - 128.0),
                     y - 0.344136 * (cb - 128.0) - 0.714136 * (cr - 128.0),
                     y + 1.772 * (cb - 128.0)], -1)


def dct8(b):
    """DCT 8x8 orthonormale de b [..., 8 (y), 8 (x)] -> [..., 8 (v), 8 (u)], sommes dans l'ordre de
    mcmm (y puis x croissants, sans BLAS ni FMA) : le C retombe sur les memes doubles, donc sur les
    memes entiers quantifies -- en longueur variable, UN coefficient de travers decale le reste."""
    t = np.zeros(b.shape)
    for y in range(8):
        t = t + DM[:, y, None] * b[..., y, None, :]
    c = np.zeros(b.shape)
    for x in range(8):
        c = c + t[..., :, x, None] * DM[None, :, x]
    return c


def to_blocks(img):
    """img RGB [HC_, WC_, 3] -> (coefY [n, 4, 8, 8], coefC [n, 2, 8, 8]), macroblocs ligne par
    ligne ; Y : blocs (haut-gauche, haut-droit, bas-gauche, bas-droit), coef[v, u]."""
    y, cb, cr = rgb2ycc(img.astype(np.float64))
    yb = (y - 128.0).reshape(SY, 2, 8, SX, 2, 8).transpose(0, 3, 1, 4, 2, 5).reshape(SY * SX, 4, 8, 8)
    def sub(c):                                        # 4:2:0 : moyenne 2x2
        return c.reshape(HC_ // 2, 2, WC_ // 2, 2).mean((1, 3)) - 128.0
    cbb = sub(cb).reshape(SY, 8, SX, 8).transpose(0, 2, 1, 3).reshape(SY * SX, 8, 8)
    crb = sub(cr).reshape(SY, 8, SX, 8).transpose(0, 2, 1, 3).reshape(SY * SX, 8, 8)
    return dct8(yb), dct8(np.stack([cbb, crb], 1))


def zz_flat(c):
    """[..., 8, 8] (v, u) -> [..., 64] dans l'ordre zigzag."""
    return np.stack([c[..., v, u] for u, v in ZZ], -1)


def unzz(z):
    """[..., 64] zigzag -> [..., 8, 8] (v, u)."""
    return np.asarray(z)[..., ZZI].reshape(np.shape(z)[:-1] + (8, 8))


def fl(x):
    return np.floor(np.asarray(x, np.float64) + 0.5).astype(np.int64)


# --- codeur ---------------------------------------------------------------------------------

def quant(zy, zc, s):
    """Coefficients zigzag -> entiers, au pas Q * s (le DC aussi, comme JPEG), bornes a AMP_MAX."""
    return (np.clip(fl(zy / (QYZ * s)), -AMP_MAX, AMP_MAX), np.clip(fl(zc / (QCZ * s)), -AMP_MAX, AMP_MAX))


def dc_diff(qy):
    """DC des 4 blocs Y : le 1er absolu, les suivants en difference du precedent."""
    d = qy[:, :, 0].copy()
    d[:, 1:] = np.diff(qy[:, :, 0], axis=1)
    return d


def bitlen(a):
    return np.frexp(np.abs(a).astype(np.float64))[1]          # bits de |a|, 0 pour 0


def ac_bits(a, L):
    """a [m, 63] AC en zigzag -> bits [m] : (zeros, taille) + amplitude, ZRL, EOB."""
    nz = a != 0
    pos = np.where(nz, np.arange(1, 64), 0)
    prev = np.zeros_like(pos)
    prev[:, 1:] = np.maximum.accumulate(pos, 1)[:, :-1]
    run = np.arange(1, 64) - prev - 1
    size = bitlen(a)
    b = np.where(nz, L[(run % 16) * 16 + size] + size + (run // 16) * L[0xF0], 0).sum(1)
    return b + np.where(pos.max(1) < 63, L[0x00], 0)


def mb_bits(qy, qc):
    """-> bits [n] du flux de chaque macrobloc."""
    dy, dcb = bitlen(dc_diff(qy)), bitlen(qc[:, :, 0])
    b = (H["dcy"][0][dy] + dy).sum(1) + (H["dcc"][0][dcb] + dcb).sum(1)
    b += ac_bits(qy[:, :, 1:].reshape(-1, 63), H["acy"][0]).reshape(-1, 4).sum(1)
    return b + ac_bits(qc[:, :, 1:].reshape(-1, 63), H["acc"][0]).reshape(-1, 2).sum(1)


def truncate(qy, qc, k):
    """Macrobloc k plus long que LEN_MAX : coupe ses plus hautes frequences (le meme rang zigzag
    dans les 6 blocs, en partant de 63) jusqu'a ce qu'il tienne."""
    for cut in range(63, 0, -1):
        if mb_bits(qy[k:k + 1], qc[k:k + 1])[0] <= LEN_MAX:
            return
        qy[k, :, cut:] = 0
        qc[k, :, cut:] = 0


def amp_str(v, n):
    return format(v if v > 0 else v + (1 << n) - 1, f"0{n}b") if n else ""


def block_str(z, dc, hdc, hac):
    """Un bloc en '0'/'1' : DC (categorie + amplitude), puis (zeros, taille) + amplitude, ZRL, EOB."""
    n = abs(int(dc)).bit_length()
    out = [hdc[n], amp_str(int(dc), n)]
    p = 0
    for i in np.nonzero(z[1:])[0] + 1:
        run, v = int(i) - p - 1, int(z[i])
        s = abs(v).bit_length()
        out += [hac[0xF0]] * (run // 16) + [hac[(run % 16) * 16 + s], amp_str(v, s)]
        p = int(i)
    if p < 63:
        out.append(hac[0x00])
    return "".join(out)


def mb_str(qy, qc, k):
    dy = dc_diff(qy[k:k + 1])[0]
    return "".join([block_str(qy[k, b], dy[b], HS["dcy"], HS["acy"]) for b in range(4)] +
                   [block_str(qc[k, c], qc[k, c, 0], HS["dcc"], HS["acc"]) for c in range(2)])


def bits_to_symbols(bits):
    """bits (multiple de 31) -> symboles, 4 par mot (bit 30 du mot = 1er bit ; poids faible d'abord)."""
    w = (bits.reshape(-1, WORD_BITS).astype(np.int64) << np.arange(WORD_BITS - 1, -1, -1)).sum(1)
    sym = np.zeros((len(w), WORD_SYMS), np.int64)
    for k in range(WORD_SYMS):
        sym[:, k], w = w % ALPHA, w // ALPHA
    return sym.reshape(-1)


def symbols_to_words(sym):
    return (sym.reshape(-1, WORD_SYMS).astype(np.int64) * ALPHA ** np.arange(WORD_SYMS)).sum(1)


def encode(img, info=None):
    """canva RGB uint8 [HC_, WC_, 3] -> symboles de toute la zone de donnees [DH, DW]. info (dict)
    recoit q, les coefficients quantifies et les longueurs, pour les tests."""
    cy, cc = to_blocks(img)
    zy, zc = zz_flat(cy), zz_flat(cc)
    n = SX * SY
    room = CAPACITY - QB_BITS - SY * ROW_BITS - n * LEN_BITS
    def fits(q):
        return np.minimum(mb_bits(*quant(zy, zc, scale(q))), LEN_MAX).sum() <= room
    lo, hi = 0, 255                  # le q le plus fin qui tient ; a 255 tout tient (DC seuls)
    if fits(0):                      # q = 0 d'abord, comme mcmm : la plupart des videos y tiennent
        hi = 0
    while lo < hi:
        mid = (lo + hi) // 2
        if fits(mid):
            hi = mid
        else:
            lo = mid + 1
    qy, qc = quant(zy, zc, scale(lo))
    for k in np.nonzero(mb_bits(qy, qc) > LEN_MAX)[0]:
        truncate(qy, qc, k)
    mbs = [mb_str(qy, qc, k) for k in range(n)]
    lens = np.array([len(m) for m in mbs])
    rows = np.concatenate([[0], np.cumsum(lens.reshape(SY, SX).sum(1))[:-1]])
    head = (format(lo, f"0{QB_BITS}b") + "".join(format(int(r), f"0{ROW_BITS}b") for r in rows)
            + "".join(format(int(x), f"0{LEN_BITS}b") for x in lens))
    flux = np.frombuffer((head + "".join(mbs)).encode(), np.uint8) - 48
    assert len(flux) <= CAPACITY, (len(flux), CAPACITY)
    if info is not None:
        info.update(q=lo, z=np.concatenate([qy, qc], 1), lens=lens, used=len(flux))
    flux = np.concatenate([flux, np.zeros(CAPACITY - len(flux), np.uint8)])
    return bits_to_symbols(flux).reshape(DH, DW)


def fit_canvas(img):
    """Image quelconque -> canva WC_ x HC_ : mise a l'echelle et bandes noires (le serveur le
    fera dans mcmm, comme aujourd'hui pour les maps)."""
    from PIL import Image
    im = Image.fromarray(img) if isinstance(img, np.ndarray) else img
    im = im.convert("RGB")
    k = min(WC_ / im.width, HC_ / im.height)
    w, h = max(1, round(im.width * k)), max(1, round(im.height * k))
    im = im.resize((w, h), Image.LANCZOS)
    out = Image.new("RGB", (WC_, HC_))
    out.paste(im, ((WC_ - w) // 2, (HC_ - h) // 2))
    return np.asarray(out)


# --- decodeur (ce que font les passes GLSL) ---------------------------------------------------

def ext(v, s):
    """Amplitude JPEG : s bits, bit de poids fort a 0 = negatif."""
    return v - (1 << s) + 1 if s and v < (1 << (s - 1)) else v


def parse(cells):
    """Les passes words, mbstart, blocks et coefs en Python : symboles [DH, DW] -> (q, debut de
    chaque macrobloc [n], (debut des AC, DC) de chaque bloc [n, 6, 2], coefficients quantifies
    [n, 6, 64] en zigzag). Un flux faux (symbole mal lu) : le macrobloc s'arrete au premier code
    invalide, la suite de sa ligne repart de l'index."""
    w = symbols_to_words(cells.reshape(-1)) & ((1 << WORD_BITS) - 1)
    bits = ((w[:, None] >> np.arange(WORD_BITS - 1, -1, -1)) & 1).astype(np.uint8).reshape(-1)
    data = np.packbits(bits).tobytes() + bytes(4)

    def get(p, k):                   # k <= 25 bits a partir du bit p
        return (int.from_bytes(data[p >> 3:(p >> 3) + 4], "big") >> (32 - (p & 7) - k)) & ((1 << k) - 1)
    n = SX * SY
    q = get(0, QB_BITS)
    rows = [get(QB_BITS + r * ROW_BITS, ROW_BITS) for r in range(SY)]
    p0 = QB_BITS + SY * ROW_BITS
    lens = [get(p0 + k * LEN_BITS, LEN_BITS) for k in range(n)]
    d0 = p0 + n * LEN_BITS
    starts = np.zeros(n, np.int64)
    blk = np.zeros((n, 6, 2), np.int64)
    z = np.zeros((n, 6, 64), np.int64)
    for my in range(SY):
        p = d0 + rows[my]
        for k in range(my * SX, (my + 1) * SX):
            starts[k], r, dcy = p, p, 0
            p += lens[k]
            try:
                for b in range(6):
                    ds, dl = LUT["dcy" if b < 4 else "dcc"]
                    pk = get(r, 16)
                    if not dl[pk]:
                        raise ValueError
                    cat, r = ds[pk], r + dl[pk]
                    v = ext(get(r, cat), cat) if cat else 0
                    r += cat
                    if b < 4:
                        dcy = v if b == 0 else dcy + v
                    blk[k, b] = (r, dcy if b < 4 else v)
                    z[k, b, 0] = blk[k, b, 1]
                    asy, aln = LUT["acy" if b < 4 else "acc"]
                    i = 1
                    while i < 64:
                        pk = get(r, 16)
                        if not aln[pk]:
                            raise ValueError
                        s, r = asy[pk], r + aln[pk]
                        if s == 0x00:
                            break
                        if s == 0xF0:
                            i += 16
                            continue
                        i += s >> 4
                        if i > 63:
                            raise ValueError
                        z[k, b, i] = ext(get(r, s & 15), s & 15)
                        r, i = r + (s & 15), i + 1
            except ValueError:
                pass
    return q, starts, blk, z


def coefs_target(z):
    """Coefficients [n, 6, 64] zigzag -> la cible coefs en entiers [SY * 8, SX * 24, 2] : le texel
    (24 mx + 4 b + j, 8 my + v) porte les coefficients (v, 2j) et (v, 2j + 1) du bloc b."""
    nat = unzz(z).reshape(SY, SX, 6, 8, 4, 2)
    return nat.transpose(0, 3, 1, 2, 4, 5).reshape(SY * 8, SX * 24, 2)


def rows_target(coefs, q):
    """Passe rows : IDCT, 1re moitie, sur la cible coefs [SY * 8, SX * 24, 2] -> [SY * 4, SX * 64, 2]
    en 1/16. Y : t[v][x] = sum_u c[v][u] QY s DM[u][x] ; chroma : la meme avec QC et DTC (16
    positions de pixels). Texel (64 mx + col, 4 my + r) = (t[2r][x], t[2r + 1][x]) ; col = 8 b + x
    pour le bloc Y b, 32 + 16 c + x pour la chroma c."""
    nat = coefs.reshape(SY, 8, SX, 6, 8).transpose(0, 2, 3, 1, 4)              # [SY, SX, 6, v, u]
    s = scale(q)
    ty = (nat[:, :, :4] * (QY.reshape(8, 8) * s)) @ DM                          # [.., 4, v, x]
    tc = (nat[:, :, 4:] * (QC.reshape(8, 8) * s)) @ DTC                         # [.., 2, v, 16]
    ty = ty.reshape(SY, SX, 4, 4, 2, 8).transpose(0, 3, 1, 2, 5, 4).reshape(SY, 4, SX, 32, 2)
    tc = tc.reshape(SY, SX, 2, 4, 2, 16).transpose(0, 3, 1, 2, 5, 4).reshape(SY, 4, SX, 32, 2)
    t = np.concatenate([ty, tc], 3).reshape(SY * 4, SX * 64, 2)
    return np.clip(fl(t * 16), -32768, 32767)


def idct_target(rows):
    """Passe idct : la cible rows -> canva RGB uint8 [HC_, WC_, 3] (ligne 0 = haut)."""
    t = rows.reshape(SY, 4, SX, 64, 2).transpose(0, 2, 3, 1, 4).reshape(SY, SX, 64, 8) / 16.0
    ty = t[:, :, :32].reshape(SY, SX, 4, 8, 8).transpose(0, 1, 2, 4, 3)          # [.., b, v, x]
    tc = t[:, :, 32:].reshape(SY, SX, 2, 16, 8).transpose(0, 1, 2, 4, 3)         # [.., c, v, x']
    y = (DM.T @ ty).reshape(SY, SX, 2, 2, 8, 8).transpose(0, 1, 2, 4, 3, 5).reshape(SY, SX, 16, 16)
    c = DTC.T @ tc                                                               # [.., c, y', x']
    rgb = ycc2rgb(y + 128.0, c[:, :, 0] + 128.0, c[:, :, 1] + 128.0)
    return np.clip(fl(rgb.transpose(0, 2, 1, 3, 4).reshape(HC_, WC_, 3)), 0, 255).astype(np.uint8)


def decode(cells):
    """Tout le decodeur, en Python : symboles [DH, DW] -> canva."""
    q, _, _, z = parse(cells)
    return idct_target(rows_target(coefs_target(z), q))


def psnr(a, b):
    mse = ((a.astype(np.float64) - b) ** 2).mean()
    return 10 * math.log10(255 ** 2 / max(mse, 1e-9))


# --- images de test ---------------------------------------------------------------------------

TRAIN = ["/usr/share/backgrounds/mendhak-Red_Acer.jpg",
         "/usr/share/backgrounds/mendhak-Bluebells_Suspended_In_Time.jpg",
         "/usr/share/backgrounds/manuej-Vor.jpg",
         "/usr/share/backgrounds/mizuno-as-Winter_Grand_Triangle.jpg",
         os.path.expanduser("~/.local/share/PrismLauncher/instances/26.3/minecraft/screenshots/"
                            "2026-09-15_21.01.39.png"),
         os.path.expanduser("~/.local/share/PrismLauncher/instances/26.3/minecraft/screenshots/"
                            "2026-10-03_23.32.30.png")]


def test_images():
    from PIL import Image
    return [(os.path.basename(p), fit_canvas(Image.open(p))) for p in TRAIN if os.path.exists(p)]


def palette_baseline(img, w=896, h=512):
    """Ce qu'affichent AUJOURD'HUI les memes 28 maps : l'image a 896x512, couleur de palette la
    plus proche (render normal sans tramage). -> PSNR contre la source a cette taille."""
    from PIL import Image
    import gen_qr_detect as G
    src = np.asarray(Image.fromarray(img).resize((w, h), Image.LANCZOS))
    return psnr(G.quantize(src, G.load_palette()), src)


def demo(previews=None):
    """Codeur -> symboles -> decodeur Python, sur les images de test (mur courant) : le flux relu
    doit redonner EXACTEMENT les coefficients du codeur."""
    rows, ok = [], True
    for name, img in test_images():
        info = {}
        cells = encode(img, info)
        q, _, _, z = parse(cells)
        same = q == info["q"] and (z == info["z"]).all()
        out = decode(cells)
        ok &= same and cells.max() < ALPHA
        rows.append((name, psnr(out, img), palette_baseline(img), info["q"], info["used"] / CAPACITY, same))
        if previews:
            from PIL import Image
            Image.fromarray(np.concatenate([img, out], 1)).save(
                os.path.join(previews, f"dct{SFX}_" + os.path.splitext(name)[0] + ".png"))
    print(f"  -- mur {WALL} : canva {WC_}x{HC_}, {SX * SY} macroblocs, zone de donnees {DW}x{DH} = "
          f"{CAPACITY} bits ({CAPACITY / (WC_ * HC_):.2f} bit/pixel)")
    for name, p, base, q, fill, same in rows:
        print(f"  {name:44} {p:5.1f} dB  (palette 896x512 : {base:5.1f})  q {q:3}, mur plein a "
              f"{100 * fill:3.0f} %{'' if same else '  FLUX FAUX'}")
    # garde-fou de regression, pas une cible : les valeurs du 13/10 (moyenne wan 39,8, pire 25,9
    # sur les jacinthes, une photo pleine de detail fin)
    ps = [r[1] for r in rows]
    return ok and sum(ps) / len(ps) >= 39.0 and min(ps) >= 25.0


# --- le mur : comment les symboles sont poses sur les maps (phases 3 a 6) ------------------------
#
# Mur WAN = grille de 448 x 256 modules, un module = un symbole = CELL x CELL pixels de map. CELL = 1
# depuis le 16/10 (4 x 2 maps, la grille centree sur une marge claire de MX0 = 32 pixels a gauche et a
# droite) : mesure en jeu, la lecture ne depend que des pixels d'ecran par module (~1,9 au minimum,
# ~2,3 pour une lecture sure), donc le mur a 1 px se lit comme celui a 2 px (7 x 4 maps) a moitie de
# distance, avec les memes shaders, 2 fois moins d'octets sur le reseau (zlib ne retirait que la
# duplication verticale) et 4 fois moins de maps. Bande de reperes de BAND modules sur les 4 bords (1 calme + 7 repere + 1 separateur),
# tout le reste = la zone de donnees, 430 x 238 cellules : le flux y est range ligne par ligne,
# d'un bord a l'autre. Reperes 7x7 en TL, TR, BL ; motifs d'alignement 5x5 dans la bande (bords et
# coin BR) : la zone de donnees reste un rectangle sans trou. Le symbole s est la couleur de map
# d'id s + 4 (ids 4..247, les 244 couleurs). Le canva (848 x 464) compte un macrobloc 16 x 16 par
# carre de 8 x 8 cellules : 1,96 bit par pixel de canva en moyenne.
#
# Mur LAN = 640 x 384 modules (5 x 3 maps, sans marge), meme construction, canva 1232 x 720. La limite
# n'est plus le reseau mais l'ecran du joueur : le mur doit y tenir EN ENTIER, soit 2,81 px par module
# au mieux en 1080p. Mesure en 1080p (FOV 70) : WAN 1,4 a 3,2 blocs, LAN 2,1 a 3,1.
WALLS = {"wan": (448, 256), "lan": (640, 384)}      # grilles de modules
CELL = 1
BAND = 9
DATA_X0 = DATA_Y0 = BAND
LIGHT_ID, DARK_ID = 34, 207     # (255,255,255) et (19,11,8) : les extremes de la palette vanilla


def use_wall(key):
    """Bascule le module sur un mur de WALLS : geometrie, canva, zone de donnees, suffixe des
    shaders et de l'effet ("" pour wan, minecraft:vmc_data ; "_lan", minecraft:vmc_data_lan). Tout
    le reste (codeur, lecture, GLSL, en-tete C) relit ces globales."""
    global WALL, SFX, WALL_MAPS, WALL_W, WALL_H, GW, GH, SX, SY, WC_, HC_, FINDERS, ALIGNS
    global DW, DH, N_WORDS, CAPACITY, WORDS_H, MX0, MY0
    WALL, SFX = key, "" if key == "wan" else "_" + key
    GW, GH = WALLS[key]
    WALL_MAPS = (-(-GW * CELL // 128), -(-GH * CELL // 128))
    WALL_W, WALL_H = 128 * WALL_MAPS[0], 128 * WALL_MAPS[1]
    MX0, MY0 = (WALL_W - GW * CELL) // 2, (WALL_H - GH * CELL) // 2     # marge claire, en pixels
    DW, DH = GW - 2 * BAND, GH - 2 * BAND
    assert DW * DH % WORD_SYMS == 0                  # des mots entiers, sans reste
    N_WORDS = DW * DH // WORD_SYMS
    CAPACITY = N_WORDS * WORD_BITS
    assert CAPACITY < 1 << ROW_BITS
    WORDS_H = -(-N_WORDS // WORDS_W)
    SX, SY = DW // SLOT, DH // SLOT
    WC_, HC_ = 16 * SX, 16 * SY
    FINDERS = ((4.5, 4.5), (GW - 4.5, 4.5), (4.5, GH - 4.5))      # TL, TR, BL : centres en modules
    ax, my = GW // 3 + 1.5, GH // 2 + 0.5                        # tiers des bords, milieu des cotes
    ALIGNS = ((ax, 4.5), (GW - ax, 4.5), (4.5, my), (GW - 4.5, my),
              (ax, GH - 4.5), (GW - ax, GH - 4.5), (GW - 4.5, GH - 4.5))


use_wall("wan")
assert (SX, SY, DW, DH, ALIGNS[:3]) == (53, 29, 430, 238, ((150.5, 4.5), (297.5, 4.5), (4.5, 128.5)))


def wall_ids(cells):
    """symboles de la zone de donnees [DH, DW] -> ids de couleur de map [WALL_H, WALL_W], ligne 0 =
    haut du mur. Ce que mcmm --data doit sortir octet pour octet (tuiles 128 x 128 ligne par ligne)."""
    g = np.full((GH, GW), LIGHT_ID, np.int64)
    for fx, fy in FINDERS:
        x0, y0 = int(fx - 3.5), int(fy - 3.5)
        for j in range(7):
            for i in range(7):
                g[y0 + j, x0 + i] = DARK_ID if max(abs(i - 3), abs(j - 3)) != 2 else LIGHT_ID
    for ax, ay in ALIGNS:
        x0, y0 = int(ax - 2.5), int(ay - 2.5)
        for j in range(5):
            for i in range(5):
                g[y0 + j, x0 + i] = DARK_ID if max(abs(i - 2), abs(j - 2)) != 1 else LIGHT_ID
    g[DATA_Y0:DATA_Y0 + DH, DATA_X0:DATA_X0 + DW] = cells + 4
    out = np.full((WALL_H, WALL_W), LIGHT_ID, np.int64)
    out[MY0:MY0 + GH * CELL, MX0:MX0 + GW * CELL] = np.kron(g, np.ones((CELL, CELL), np.int64))
    return out


def wall_tiles(ids):
    """ids [WALL_H, WALL_W] -> octets du flux mcmm : tuiles 128x128, ligne de tuiles par ligne."""
    mw, mh = WALL_MAPS
    return ids.reshape(mh, 128, mw, 128).transpose(0, 2, 1, 3).reshape(-1).astype(np.uint8).tobytes()


def cells_of_tiles(buf):
    """Inverse de wall_tiles(wall_ids(cells)) : octets d'une frame mcmm -> symboles [DH, DW]."""
    mw, mh = WALL_MAPS
    ids = np.frombuffer(buf, np.uint8).reshape(mh, mw, 128, 128).transpose(0, 2, 1, 3).reshape(WALL_H, WALL_W)
    g = ids[MY0:MY0 + GH * CELL:CELL, MX0:MX0 + GW * CELL:CELL].astype(np.int64)
    return g[DATA_Y0:DATA_Y0 + DH, DATA_X0:DATA_X0 + DW] - 4


def frame_tiles(cells):
    """symboles -> [tuiles, 16384] : les octets que le plugin compare d'une frame a l'autre."""
    return np.frombuffer(wall_tiles(wall_ids(cells)), np.uint8).reshape(-1, 128 * 128)


def net_stats(exe, video, start=0.0, n=300, every=5):
    """mcmm --data sur une vraie video (mur courant, 10 fps, n frames depuis start) : tuiles
    renvoyees (le plugin saute les tuiles identiques), debit apres zlib niveau 6 (la compression des
    paquets de Paper), PSNR du canva decode contre la source (une frame sur every). PSNR de la MSE
    MOYENNE, pas moyenne des PSNR : sur une frame presque noire, 0,04 -> 2 de MSE (invisible)
    "coute" 17 dB."""
    import subprocess
    import zlib
    w, h = WALL_MAPS
    size = w * h * 16384
    seek = ["-ss", f"{start:.3f}"] if start > 0 else []
    vf = (f"fps=10,scale={WC_}:{HC_}:force_original_aspect_ratio=decrease,"
          f"pad={WC_}:{HC_}:(ow-iw)/2:(oh-ih)/2:black")              # le filtre de mcmm --data
    raw = subprocess.run(["ffmpeg", "-hide_banner", "-v", "error", "-copyts", *seek, "-i", video, "-vf", vf,
                          "-frames:v", str(n), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                         capture_output=True, check=True).stdout
    src = np.frombuffer(raw, np.uint8).reshape(-1, HC_, WC_, 3)
    p = subprocess.Popen([exe, "--stream", "--data", *(["--seek", f"{start:.3f}"] if start > 0 else []),
                          video, str(w), str(h), "10"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    p.stdout.read(16)
    prev, sent, nbytes, mse, same = None, 0, 0, [], True
    for i in range(len(src)):
        buf = p.stdout.read(8 + size)[8:]
        if len(buf) < size:
            break
        tiles = np.frombuffer(buf, np.uint8).reshape(-1, 128 * 128)
        if prev is not None:
            ch = (tiles != prev).any(1)
            sent += int(ch.sum())
            nbytes += sum(len(zlib.compress(t.tobytes(), 6)) for t in tiles[ch])
        if i % every == 0:
            cells = cells_of_tiles(buf)
            same &= bool((cells == encode(src[i])).all())   # meme frame, et le C = ce script
            mse.append(((decode(cells).astype(np.float64) - src[i]) ** 2).mean())
        prev = tiles
    p.kill()
    p.wait()
    m = i
    print(f"  {os.path.basename(video)[:30]:30} mur {WALL} : tuiles renvoyees {100 * sent / (m * w * h):5.1f} %, "
          f"{nbytes * 8 * 10 / m / 1e6:5.1f} Mbit/s a 10 fps, PSNR {10 * math.log10(255 ** 2 / np.mean(mse)):5.2f} dB"
          f" (pire frame {10 * math.log10(255 ** 2 / max(mse)):5.2f}) ; mcmm = Python : {same}")
    return same


def palette_rgb():
    """[256, 3] : RGB que le CLIENT affiche pour chaque id (base * nuance // 255), -1 si invalide."""
    import json
    cl = json.load(open(os.path.join(ROOT, "..", "..", "src", "main", "resources",
                                     "vanilla_map_colors.json"), encoding="utf-8"))["colorList"]
    out = np.full((256, 3), -1, np.int64)
    for k, h in cl.items():
        rgb = [int(h.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4)]
        for s, m in enumerate((180, 220, 255, 135)):
            out[int(k) * 4 + s] = [c * m // 255 for c in rgb]
    assert (out[4:248] >= 0).all() and len({tuple(c) for c in out[4:248]}) == ALPHA
    return out


def write_c_header(path):
    """vmc_data.h pour mcmm --data : le format ci-dessus, GENERE (une seule source de verite)."""
    arr = lambda v: ", ".join(str(int(x)) for x in v)
    farr = lambda v: ", ".join(repr(float(x)) for x in v)
    pts = lambda v: ", ".join("{" + f"{x}, {y}" + "}" for x, y in v)
    rows = []
    for key in WALLS:
        use_wall(key)
        rows.append(f"    {{{WALL_MAPS[0]}, {WALL_MAPS[1]}, {WALL_W}, {WALL_H}, {MX0}, {MY0}, {GW}, {GH}, {DW}, {DH}, {SX}, {SY},"
                    f" {WC_}, {HC_}, {{{pts(FINDERS)}}},\n     {{{pts(ALIGNS)}}}}},   // {key}, minecraft:vmc_data{SFX}")
        n_al = len(ALIGNS)
    use_wall("wan")
    huf = lambda i: ",\n    ".join("{ " + arr(H[n][i]) + " }" for n in HUF)
    with open(path, "w") as f:
        f.write(f"""// GENERE par working_on new/shader_test/gen_dct.py (write_c_header) -- ne pas editer.
// Format du mode --data : un JPEG de base (q global, Huffman standard) range dans un mur de maps,
// {" ou ".join(f"{-(-w * CELL // 128)}x{-(-h * CELL // 128)}" for w, h in WALLS.values())}. Le decodeur est le post effect minecraft:vmc_data(_lan) du pack VMC,
// genere par le meme script. La description complete est en tete de gen_dct.py.
#ifndef VMC_DATA_H
#define VMC_DATA_H

#define VD_CELL {CELL}                // un module / symbole = CELL x CELL pixels de map
#define VD_DATA_X0 {DATA_X0}          // premiere cellule de donnees, en modules
#define VD_DATA_Y0 {DATA_Y0}
#define VD_LIGHT_ID {LIGHT_ID}
#define VD_DARK_ID {DARK_ID}
#define VD_N_FINDERS 3
#define VD_N_ALIGNS {n_al}

// Les murs connus du decodeur (un post effect par mur) : rien d'autre ne se decode.
typedef struct {{
    int maps_x, maps_y;           // en maps
    int wall_w, wall_h;           // en pixels de map
    int x0, y0;                   // marge claire avant la grille, en pixels de map
    int gw, gh;                   // en modules
    int dw, dh;                   // zone de donnees en cellules : le flux, ligne par ligne
    int sx, sy;                   // macroblocs 16x16 du canva
    int canvas_w, canvas_h;       // image encodee (ffmpeg : scale + pad noir)
    // centres en modules continus (le module i couvre [i, i+1)) ; coin haut-gauche = centre - 3,5
    // (repere 7x7, sombre si max(|i-3|,|j-3|) != 2) ou - 2,5 (alignement 5x5, sombre si != 1)
    double finders[VD_N_FINDERS][2];
    double aligns[VD_N_ALIGNS][2];
}} vd_layout;

#define VD_N_LAYOUTS {len(WALLS)}
#define VD_MAX_WALL_W {128 * max(-(-w * CELL // 128) for w, _ in WALLS.values())}
#define VD_MAX_WALL_H {128 * max(-(-h * CELL // 128) for _, h in WALLS.values())}
#define VD_MAX_GW {max(w for w, _ in WALLS.values())}
#define VD_MAX_GH {max(h for _, h in WALLS.values())}
static const vd_layout VD_LAYOUTS[VD_N_LAYOUTS] = {{
{chr(10).join(rows)}
}};

#define VD_ALPHA {ALPHA}              // symbole s = couleur de map d'id s + 4
#define VD_WORD_SYMS {WORD_SYMS}      // 4 symboles = un nombre en base 244 < 2^31, poids faible d'abord
#define VD_WORD_BITS {WORD_BITS}      // bit n du flux = bit 30 - n % 31 du mot n / 31
#define VD_QB_BITS {QB_BITS}            // q global : s(q) = 2^((q - VD_Q_OFF) / VD_Q_DIV), 0 = le plus fin
#define VD_Q_OFF {Q_OFF}
#define VD_Q_DIV {Q_DIV}
#define VD_ROW_BITS {ROW_BITS}          // debut de chaque ligne de macroblocs, depuis le debut des donnees
#define VD_LEN_BITS {LEN_BITS}          // longueur de chaque macrobloc
#define VD_AMP_MAX {AMP_MAX}          // coefficients quantifies bornes a +-VD_AMP_MAX
// ordre zigzag : (u, v), u = frequence horizontale (colonne), coefficient = DCT[v][u]
static const int VD_ZZ_U[64] = {{ {arr(u for u, _ in ZZ)} }};
static const int VD_ZZ_V[64] = {{ {arr(v for _, v in ZZ)} }};
// tables JPEG qualite 50, ligne par ligne : Q[v * 8 + u] ; pas du coefficient = Q * s(q), DC compris
static const double VD_QY[64] = {{ {farr(QY)} }};
static const double VD_QC[64] = {{ {farr(QC)} }};
// Huffman standard de JPEG (annexe K), tables 0 DC Y, 1 DC C, 2 AC Y, 3 AC C : longueur (0 = absent)
// et code de chaque symbole. DC : symbole = categorie ; AC : (zeros << 4) | taille, 0x00 EOB, 0xF0 ZRL.
static const int VD_HUF_LEN[4][256] = {{
    {huf(0)} }};
static const int VD_HUF_CODE[4][256] = {{
    {huf(1)} }};
// Flux, bits de poids fort d'abord : q (VD_QB_BITS) ; sy debuts de ligne (VD_ROW_BITS) ; sx * sy
// longueurs (VD_LEN_BITS, ligne par ligne) ; les macroblocs ; des zeros jusqu'a dw * dh / 4 mots.
// Macrobloc : blocs Y haut-gauche, haut-droit, bas-gauche, bas-droit, Cb, Cr. Bloc : DC (categorie
// Huffman puis amplitude ; Y : le 1er absolu, les autres en difference du precedent ; Cb, Cr
// absolus), puis les AC en zigzag ((zeros, taille) Huffman puis amplitude ; ZRL ; EOB si le
// dernier non nul n'est pas le 63e). Amplitude JPEG : v > 0 tel quel, v < 0 -> v + 2^taille - 1.
// Mot w = symboles 4w..4w+3, cellule c = (VD_DATA_X0 + c % dw, VD_DATA_Y0 + c / dw) du mur.

#endif
""")


# --- lecture du mur a l'ecran (phase 6) -----------------------------------------------------------

def wall_layout():
    import gen_qr_detect as D
    return D.Layout(FINDERS[1][0] - FINDERS[0][0], FINDERS[2][1] - FINDERS[0][1], FINDERS[0], ALIGNS,
                    WALL_MAX_TILES, finders=FINDERS, tl_by_score=True)


# un mur plein de donnees sort quelques faux candidats de repere (27 en simulation a 3 px par
# module, dont les vrais) ; la limite "bruit" de 48 tuiles est celle d'un QR seul a l'ecran
WALL_MAX_TILES = 200


def symbols_of(rgb):
    """RGB entiers [..., 3] -> symbole (id de couleur - 4) : la couleur de map EXACTE si elle y
    est (le cas de jour, glow frame), sinon la plus proche (distance RGB au carre, la premiere a
    egalite). Le GLSL fait pareil : recherche binaire, puis boucle si rien."""
    pal = palette_rgb()[4:4 + ALPHA]
    key = (rgb[..., 0] << 16) | (rgb[..., 1] << 8) | rgb[..., 2]
    pk = (pal[:, 0] << 16) | (pal[:, 1] << 8) | pal[:, 2]
    order = np.argsort(pk)
    i = np.clip(np.searchsorted(pk[order], key), 0, ALPHA - 1)
    exact = pk[order][i] == key
    out = np.where(exact, order[i], 0)
    miss = np.nonzero(~exact)
    if miss[0].size:
        d = ((rgb[miss][:, None, :] - pal[None]) ** 2).sum(-1)
        out[miss] = d.argmin(1)
    return out


def extract(img_gl, fit_texels, align_texels, persp_texels):
    """Image GL (ligne 0 = bas) -> symboles de la zone de donnees [DH, DW] (ligne 0 = haut du mur) :
    le symbole au centre de chaque cellule par l'homographie du mur. Hors ecran / sans mur : 0."""
    import gen_qr_detect as D
    out = np.zeros((DH, DW), np.int64)
    if D.decode_fit(fit_texels)[1][3] != 255:
        return out
    L = wall_layout()
    F, gh = D.homography(fit_texels, align_texels, persp_texels, L)
    cy, cx = np.mgrid[0:DH, 0:DW]
    with np.errstate(all="ignore"):
        x, y = D.hmap(F, gh, DATA_X0 + cx + 0.5, DATA_Y0 + cy + 0.5, L)
    h, w = img_gl.shape[:2]
    ok, px, py = D.to_pixel(x, y, w, h)
    sym = symbols_of(img_gl[py, px].astype(np.int64))
    out[ok] = sym[ok]
    return out


def read_wall(img_gl):
    """Toute la chaine client en numpy : -> (cellules, fit, persp, align)."""
    import gen_qr_detect as D
    L = wall_layout()
    cls, _, _, fit_t = D.detect(img_gl, L)
    pt, _, al = D.align_all(cls, fit_t, L)
    return extract(img_gl, fit_t, al, pt), fit_t, pt, al


def render_wall(cells, w, h, m_px, roll, tilt, light=1.0, bg="blocky", seed_=0):
    """Le mur vu par la camera de gen_qr_detect (FOV vertical 70, echantillonnage NEAREST des
    maps) : m_px = pixels d'ecran par module (CELL x CELL px de map) au centre."""
    import gen_qr_detect as D
    wall = palette_rgb()[wall_ids(cells)[MY0:MY0 + GH * CELL, MX0:MX0 + GW * CELL]].astype(np.uint8)
    img, _ = D.render(wall, (0, 0), CELL, w, h, m_px, roll, tilt, bg, light, seed_)
    return img


def wall_fits(w, h, m_px, roll, tilt):
    """Les 4 coins du mur dans l'ecran (incline, le bord proche grandit et deborde)."""
    import gen_qr_detect as D
    W, H = GW * CELL, GH * CELL                      # la grille seule : la marge peut deborder
    to_s, _ = D.camera(w, h, W, H, CELL, m_px, roll, tilt)
    return all(0 <= x < w and 0 <= y < h for x, y in (to_s(cx, cy) for cx in (0, W) for cy in (0, H)))


# px d'ecran par module essayes, par ecran : de 2,5 (la limite lue en jeu) a ce qui tient encore
DEMO_SIZES = {"wan": ((1920, 1080, (2.5, 3.0, 3.5, 4.0)), (1823, 1394, (3.0, 4.0))),
              "lan": ((1920, 1080, (2.5, 2.7)), (1823, 1394, (2.6, 2.8)), (2560, 1440, (2.6, 3.0, 3.6)))}


def demo_wall():
    """Mur encode -> rendu -> detection -> lecture -> decodage. Exige 0 symbole faux de face
    (les conditions du mode data : joueur aligne, mur entier dans la vue, plein jour)."""
    print(f"  -- mur {WALL} {WALL_MAPS[0]}x{WALL_MAPS[1]}, canva {WC_}x{HC_}")
    rows, ok = [], True
    imgs = test_images()[:3]
    for w, h, sizes in DEMO_SIZES[WALL]:
        for m_px in sizes:
            for roll, tilt in ((0, 0), (1, 0), (0, 3), (2, 6)):
                name, img = imgs[len(rows) % len(imgs)]
                cells = encode(img)
                screen = render_wall(cells, w, h, m_px, roll, tilt)
                got, fit_t, _, al = read_wall(screen)
                bad = int((got != cells).sum())
                found = sum(1 for t in al if any(t))
                fits = wall_fits(w, h, m_px, roll, tilt)
                p = psnr(decode(got), img)
                must = fits and roll <= 1 and tilt <= 3
                ok &= (not must) or bad == 0
                rows.append(f"  {w}x{h} m{m_px} r{roll} t{tilt}: {'mur' if fit_t[3][3] == 255 else 'RIEN'}"
                            f" motifs {found}/10, symboles faux {bad:6}, PSNR {p:5.1f} dB"
                            f"{'' if fits else '  (deborde)'}{'' if must else '  (rapporte)'}")
    print("\n".join(rows))
    return ok


# --- GLSL ---------------------------------------------------------------------------------------

POST = os.path.join(ROOT, "resourcepack", "assets", "minecraft", "shaders", "post")
EFF = os.path.join(ROOT, "resourcepack", "assets", "minecraft", "post_effect")
TEX = os.path.join(ROOT, "resourcepack", "assets", "minecraft", "textures", "effect")
TEST_IMAGE = TRAIN[4]             # ta capture du 15/09 : une image a toi dans le pack de test


def draw(img, screen):
    """Le canva au centre de l'ecran, agrandi pour le REMPLIR : facteur k reel (le plus grand qui
    tient), bilineaire entre les centres des pixels du canva. Un facteur entier laissait le canva
    lan (1232 x 720) a x1 en 1080p, plus petit a l'ecran que le canva wan a x2. screen : GL (ligne
    0 = bas). Le GLSL (CANVAS_GLSL) calcule en float32 : un niveau d'ecart sur un arrondi."""
    h, w = screen.shape[:2]
    k = min(w / WC_, h / HC_)
    ox, oy = (w - WC_ * k) / 2, (h - HC_ * k) / 2
    yy, xx = np.mgrid[0:h, 0:w]
    qx, qy = (xx + 0.5 - ox) / k, ((h - 1 - yy) + 0.5 - oy) / k      # pixels du canva, depuis le haut
    ok = (qx >= 0) & (qy >= 0) & (qx < WC_) & (qy < HC_)
    tx, ty = qx[ok] - 0.5, qy[ok] - 0.5
    ax, ay = np.floor(tx).astype(np.int64), np.floor(ty).astype(np.int64)
    fx, fy = (tx - ax)[:, None], (ty - ay)[:, None]
    bx, by = np.minimum(ax + 1, WC_ - 1), np.minimum(ay + 1, HC_ - 1)
    ax, ay = np.maximum(ax, 0), np.maximum(ay, 0)
    im = img.astype(np.float64)
    top = im[ay, ax] * (1 - fx) + im[ay, bx] * fx
    bot = im[by, ax] * (1 - fx) + im[by, bx] * fx
    out = screen.copy()
    out[ok] = fl(top * (1 - fy) + bot * fy)
    return out


# draw() en GLSL, pour les passes qui declarent ImgSampler et CANVAS : col = le canva si p y tombe
CANVAS_GLSL = """
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
"""


def bits_glsl():
    """Lecture du flux dans la cible words et decodage Huffman (tables standard), pour les passes
    mbstart, blocks, coefs et rows. Transcription de parse()."""
    lim, first, off, vals, voff = [], [], [], [], []
    for name in HUF:                                       # 0 dcy, 1 dcc, 2 acy, 3 acc
        bits, v = (bytes.fromhex(h) for h in HUF[name])
        voff.append(len(vals))
        vals += list(v)
        c = k = 0
        for L in range(1, 17):
            first.append(c)
            off.append(k)
            c, k = c + bits[L - 1], k + bits[L - 1]
            lim.append(c << (16 - L))                      # codes de longueur <= L, cadres a gauche
            c <<= 1
    ints = lambda v: ", ".join(str(int(x)) for x in v)
    return f"""
uniform sampler2D WordsSampler;   // un mot de 31 bits par texel, octets poids faible d'abord

const int WW = {WORDS_W};
const int QB_BITS = {QB_BITS};
const int ROW_BITS = {ROW_BITS};
const int LEN_BITS = {LEN_BITS};
// Huffman canonique (annexe C), tables 0 DC Y, 1 DC C, 2 AC Y, 3 AC C ; indice t * 16 + L - 1
const int LIM[64] = int[64]({ints(lim)});
const int FIRST[64] = int[64]({ints(first)});
const int OFF[64] = int[64]({ints(off)});
const int VOFF[4] = int[4]({ints(voff)});
const int VALS[{len(vals)}] = int[{len(vals)}]({ints(vals)});

uint wordAt(int w) {{
    ivec4 b = ivec4(round(texelFetch(WordsSampler, ivec2(w % WW, w / WW), 0) * 255.0));
    return (uint(b.r) | (uint(b.g) << 8) | (uint(b.b) << 16) | (uint(b.a) << 24)) & 0x7FFFFFFFu;
}}

// k bits (1 a 31) a partir du bit n du flux, poids fort d'abord (bit n = bit 30 - n % 31 du mot n / 31)
uint bitsAt(int n, int k) {{
    int w = n / {WORD_BITS};
    int r = {WORD_BITS} - (n - w * {WORD_BITS});                 // bits restant dans le mot w
    uint v = wordAt(w) & ((1u << uint(r)) - 1u);
    if (r >= k) return v >> uint(r - k);
    return (v << uint(k - r)) | (wordAt(w + 1) >> uint({WORD_BITS} - k + r));
}}

// symbole Huffman de la table t au bit n (n avance) ; -1 si le code n'existe pas
int huff(int t, inout int n) {{
    int p = int(bitsAt(n, 16));
    for (int L = 1; L <= 16; L++) {{
        int i = t * 16 + L - 1;
        if (p < LIM[i]) {{
            n += L;
            return VALS[VOFF[t] + OFF[i] + (p >> (16 - L)) - FIRST[i]];
        }}
    }}
    return -1;
}}

// amplitude JPEG de s bits au bit n (n avance) : bit de poids fort a 0 = negatif
int amp(inout int n, int s) {{
    if (s <= 0) return 0;
    int v = int(bitsAt(n, s));
    n += s;
    return v < (1 << (s - 1)) ? v - (1 << s) + 1 : v;
}}
"""


def shaders():
    from gen_color_id import EXT
    head = EXT + "\n// GENERE par gen_dct.py -- ne pas editer a la main.\n"
    ints = lambda v: ", ".join(str(int(x)) for x in v)
    flts = lambda v: ", ".join(repr(float(x)) for x in v)
    basis = (f"const float DM[64] = float[64]({flts(DM.reshape(-1))});    // a(u) cos((2x + 1) u pi / 16), [u * 8 + x]\n"
             f"const float DTC[128] = float[128]({flts(DTC.reshape(-1))});  // la meme en x = pixel / 2 - 0,25, [u * 16 + pixel]\n")

    words_fsh = head + f"""// vmc_data{SFX}, passe words : cible {WORDS_W}x{WORDS_H}, un mot de 31 bits par texel (octets poids faible
// d'abord dans r, g, b, a). Mot w = les symboles 4w..4w+3 de la zone de donnees ({DW}x{DH}, ligne par
// ligne), chiffres en base 244 poids faible d'abord.

uniform sampler2D CellsSampler;   // un symbole (0..243) par texel, ligne 0 = haut du mur

layout(location = 0) out vec4 fragColor;

const int DW = {DW};
const int NWORDS = {N_WORDS};

void main() {{
    ivec2 f = ivec2(gl_FragCoord.xy);
    int w = f.y * {WORDS_W} + f.x;
    uint v = 0u;
    if (w < NWORDS) {{
        for (int i = 3; i >= 0; i--) {{
            int c = 4 * w + i;
            v = v * {ALPHA}u + uint(round(texelFetch(CellsSampler, ivec2(c % DW, c / DW), 0).r * 255.0));
        }}
    }}
    fragColor = vec4(uvec4(v, v >> 8, v >> 16, v >> 24) & 255u) / 255.0;
}}
"""

    mbstart_fsh = head + f"""// vmc_data{SFX}, passe mbstart : cible {SX}x{SY}, le bit ou commence chaque macrobloc (24 bits dans r, g, b) :
// debut des donnees + debut de sa ligne + longueurs de ceux qui le precedent dans la ligne.

layout(location = 0) out vec4 fragColor;
""" + bits_glsl() + f"""
const int SX = {SX};
const int SY = {SY};

void main() {{
    ivec2 f = ivec2(gl_FragCoord.xy);
    int p0 = QB_BITS + SY * ROW_BITS;
    int n = p0 + SX * SY * LEN_BITS + int(bitsAt(QB_BITS + f.y * ROW_BITS, ROW_BITS));
    for (int x = 0; x < f.x; x++) n += int(bitsAt(p0 + (f.y * SX + x) * LEN_BITS, LEN_BITS));
    fragColor = vec4(float(n & 255), float((n >> 8) & 255), float((n >> 16) & 255), 255.0) / 255.0;
}}
"""

    blocks_fsh = head + """// Passe blocks : cible (SX * 6) x SY, un texel par bloc (Y haut-gauche, haut-droit, bas-gauche,
// bas-droit, Cb, Cr) : le bit ou commencent ses AC (21 bits) et son DC (11 bits, complement a deux),
// mot de 32 bits octets poids faible d'abord. Relit les blocs qui le precedent dans le macrobloc.

uniform sampler2D MbStartSampler;

layout(location = 0) out vec4 fragColor;
""" + bits_glsl() + """
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
"""

    coefs_fsh = head + f"""// Passe coefs : cible (SX * 24) x (SY * 8). Texel (24 mx + 4 b + j, 8 my + v) : les coefficients
// quantifies (v, 2j) et (v, 2j + 1) du bloc b, 16 bits chacun (r, g puis b, a). Relit les AC de son
// bloc jusqu'a eux.

uniform sampler2D BlocksSampler;

layout(location = 0) out vec4 fragColor;
""" + bits_glsl() + f"""
const int ZZI[64] = int[64]({ints(ZZI)});   // rang zigzag de (v, u) : ZZI[v * 8 + u]

void main() {{
    ivec2 f = ivec2(gl_FragCoord.xy);
    int mx = f.x / 24, x = f.x - mx * 24, my = f.y / 8, v = f.y - my * 8;
    int b = x / 4, j = x - b * 4;
    ivec4 e = ivec4(round(texelFetch(BlocksSampler, ivec2(mx * 6 + b, my), 0) * 255.0));
    uint o = uint(e.r) | (uint(e.g) << 8) | (uint(e.b) << 16) | (uint(e.a) << 24);
    int n = int(o >> 11), dc = int(o & 0x7FFu);
    if (dc >= 1024) dc -= 2048;
    int z0 = ZZI[v * 8 + 2 * j], z1 = ZZI[v * 8 + 2 * j + 1];
    int c0 = z0 == 0 ? dc : 0, c1 = 0;
    int t = b < 4 ? 2 : 3;
    int i = 1;
    for (int it = 0; it < 80 && i <= max(z0, z1); it++) {{
        int s = huff(t, n);
        if (s <= 0) break;                           // EOB : le reste du bloc est nul
        if (s == 0xF0) {{
            i += 16;
            continue;
        }}
        i += s >> 4;
        int val = amp(n, s & 15);
        if (i == z0) c0 = val;
        if (i == z1) c1 = val;
        i++;
    }}
    fragColor = vec4(ivec4(c0, c0 >> 8, c1, c1 >> 8) & 255) / 255.0;
}}
"""

    rows_fsh = head + f"""// Passe rows : IDCT separable, 1re moitie. Cible (SX * 64) x (SY * 4) ; texel (64 mx + col, 4 my + r) =
// t[2r][x] et t[2r + 1][x] en 1/16, 16 bits chacun ; t[v][x] = somme sur u de c[v][u] Q[v][u] s B[u][x].
// col = 8 b + x pour le bloc Y b (B = la DCT), 32 + 16 c + x pour la chroma c (B aux 16 pixels).

uniform sampler2D CoefsSampler;

layout(location = 0) out vec4 fragColor;
""" + bits_glsl() + f"""
const float QY[64] = float[64]({flts(QY)});
const float QC[64] = float[64]({flts(QC)});
{basis}
void main() {{
    ivec2 f = ivec2(gl_FragCoord.xy);
    int mx = f.x / 64, col = f.x - mx * 64, my = f.y / 4, r = f.y - my * 4;
    bool luma = col < 32;
    int b = luma ? col / 8 : 4 + (col - 32) / 16;
    int x = luma ? col % 8 : (col - 32) % 16;
    float s = exp2((float(bitsAt(0, QB_BITS)) - {float(Q_OFF)}) / {float(Q_DIV)});
    vec2 t = vec2(0.0);
    for (int h = 0; h < 2; h++) {{
        int v = 2 * r + h;
        for (int u2 = 0; u2 < 4; u2++) {{
            ivec4 e = ivec4(round(texelFetch(CoefsSampler, ivec2(mx * 24 + b * 4 + u2, my * 8 + v), 0) * 255.0));
            ivec2 c = ivec2(e.r | (e.g << 8), e.b | (e.a << 8));
            c -= ivec2(greaterThanEqual(c, ivec2(32768))) * 65536;
            for (int k = 0; k < 2; k++) {{
                int u = 2 * u2 + k;
                t[h] += float(c[k]) * (luma ? QY[v * 8 + u] : QC[v * 8 + u]) * s
                        * (luma ? DM[u * 8 + x] : DTC[u * 16 + x]);
            }}
        }}
    }}
    ivec2 o = ivec2(clamp(floor(t * 16.0 + 0.5), -32768.0, 32767.0));
    fragColor = vec4(ivec4(o.x, o.x >> 8, o.y, o.y >> 8) & 255) / 255.0;
}}
"""

    idct_fsh = head + f"""// Passe idct : cible = le canva (16 SX x 16 SY), un fragment par pixel (ligne 0 = haut). 2e moitie de
// l'IDCT (somme sur v de B[v][y] t[v][x]) pour Y, Cb et Cr, puis YCbCr pleine echelle (JPEG) -> RGB.

uniform sampler2D RowsSampler;

layout(location = 0) out vec4 fragColor;

{basis}
vec2 pairAt(ivec2 mb, int col, int r) {{
    ivec4 e = ivec4(round(texelFetch(RowsSampler, ivec2(mb.x * 64 + col, mb.y * 4 + r), 0) * 255.0));
    ivec2 v = ivec2(e.r | (e.g << 8), e.b | (e.a << 8));
    v -= ivec2(greaterThanEqual(v, ivec2(32768))) * 65536;
    return vec2(v) / 16.0;
}}

void main() {{
    ivec2 f = ivec2(gl_FragCoord.xy);
    ivec2 mb = f / 16, l = f - mb * 16;
    int b = (l.y / 8) * 2 + l.x / 8;
    ivec2 xy = l % 8;
    float y = 0.0;
    for (int r = 0; r < 4; r++) {{
        vec2 t = pairAt(mb, b * 8 + xy.x, r);
        y += DM[2 * r * 8 + xy.y] * t.x + DM[(2 * r + 1) * 8 + xy.y] * t.y;
    }}
    float c[2];
    for (int k = 0; k < 2; k++) {{
        c[k] = 0.0;
        for (int r = 0; r < 4; r++) {{
            vec2 t = pairAt(mb, 32 + k * 16 + l.x, r);
            c[k] += DTC[2 * r * 16 + l.y] * t.x + DTC[(2 * r + 1) * 16 + l.y] * t.y;
        }}
    }}
    y += 128.0;
    vec3 rgb = vec3(y + 1.402 * c[1], y - 0.344136 * c[0] - 0.714136 * c[1], y + 1.772 * c[0]);
    fragColor = vec4(clamp(rgb, 0.0, 255.0) / 255.0, 1.0);
}}
"""

    draw_fsh = head + f"""// Passe draw : le canva {WC_}x{HC_} au centre de l'ecran, agrandi pour le remplir. Ailleurs, la scene.

uniform sampler2D ImgSampler;
uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

const ivec2 CANVAS = ivec2({WC_}, {HC_});
""" + CANVAS_GLSL + """
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(SrcSampler, 0);
    vec3 col = texelFetch(SrcSampler, p, 0).rgb;
    canvasAt(p, size, col);
    fragColor = vec4(col, 1.0);
}
"""
    import gen_qr_detect as D
    L = wall_layout()
    hom_base, hom_final = D.hom_glsl(L)
    pal = palette_rgb()[4:4 + ALPHA]
    pk = [(int(r) << 16) | (int(g) << 8) | int(b) for r, g, b in pal]
    order = sorted(range(ALPHA), key=lambda i: pk[i])

    extract_fsh = D.glsl_head() + f"""// vmc_data{SFX}, passe extract : cible {DW}x{DH}, une cellule de donnees du mur par fragment
// (ligne 0 = HAUT du mur, comme la texture de dct_test). Symbole = la couleur de map au centre
// de la cellule par l'homographie du mur (reperes affines + motifs d'alignement de la bande) :
// recherche binaire de la couleur EXACTE (le cas normal, glow frame de jour), sinon la plus
// proche des 244. Transcription de extract() / symbols_of() de gen_dct.py.

uniform sampler2D FitSampler;
uniform sampler2D PerspSampler;
uniform sampler2D AlignSampler;
uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

const ivec2 DATA0 = ivec2({DATA_X0}, {DATA_Y0});   // premiere cellule de donnees, en modules
const int KEYS[{ALPHA}] = int[{ALPHA}]({", ".join(str(pk[i]) for i in order)});   // RGB tries
const int KSYM[{ALPHA}] = int[{ALPHA}]({", ".join(str(i) for i in order)});
const int PAL[{ALPHA}] = int[{ALPHA}]({", ".join(str(v) for v in pk)});      // RGB par symbole
""" + D.FITPTS + hom_base + hom_final + """
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
    int lo = 0, hi = """ + str(ALPHA - 1) + """;
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
        for (int i = 0; i < """ + str(ALPHA) + """; i++) {
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
"""

    data_draw_fsh = D.glsl_head() + f"""// vmc_data{SFX}, passe draw : le canva {WC_}x{HC_} decode au centre de l'ecran, agrandi pour le
// remplir -- seulement si le mur est detecte. Croix cyan sur les motifs du mur
// trouves (rouge : predit, pas trouve), HUD comme qr_detect (pastille verte = mur detecte).

uniform sampler2D ImgSampler;
uniform sampler2D FitSampler;
uniform sampler2D PerspSampler;
uniform sampler2D AlignSampler;
uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

const ivec2 CANVAS = ivec2({WC_}, {HC_});
""" + CANVAS_GLSL + D.FITPTS + D.hud_glsl() + hom_base + hom_final + """
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(SrcSampler, 0);
    vec3 col = texelFetch(SrcSampler, p, 0).rgb;
    ivec4 st = fitAt(3);
    if (st.a == 255) {
        if (!canvasAt(p, size, col)) {
            // l'homographie seulement HORS du canva (les croix ne se voient que la) : la
            // recalculer sur chaque pixel coutait 5 ms par frame sur un GPU integre
            vec2 T = point(0), R = point(1), B = point(2);
            refineFinders(T, R, B);
            col = alignMarks(vec2(p), T, R, B, ghFinal(T, R, B), col);
        }
    }
    fragColor = vec4(hud(p, size, st, col), 1.0);
}
"""
    out = {"vmc_vlc_blocks.fsh": blocks_fsh, "vmc_vlc_coefs.fsh": coefs_fsh,       # communs aux murs
           "vmc_vlc_rows.fsh": rows_fsh, "vmc_vlc_idct.fsh": idct_fsh,
           f"vmc_vlc_words{SFX}.fsh": words_fsh, f"vmc_vlc_mbstart{SFX}.fsh": mbstart_fsh,
           f"vmc_wall_fit{SFX}.fsh": D.fit_glsl(L), f"vmc_wall_align{SFX}.fsh": D.align_glsl(L),
           f"vmc_wall_pick{SFX}.fsh": D.pick_glsl(L), f"vmc_wall_extract{SFX}.fsh": extract_fsh,
           f"vmc_data_draw{SFX}.fsh": data_draw_fsh}
    if WALL == "wan":
        out["vmc_dct_draw.fsh"] = draw_fsh            # dct_test : l'image fixe du pack, au canva wan
        out.update(persist_shaders())
    return out


# --- etape 3 de PLAN_VIDEO_HISTORY.md : les targets persistantes, en jeu ---------------------------
#
# /posteffect add @s minecraft:vmc_persist_test. Quatre nombres a gauche de l'ecran :
#   blanc    counter1 + 1 : persistante, relue dans la frame apres sa copie
#   jaune    counter2 + 1 : persistante, ecrite par une copie que RIEN ne relit dans la frame
#   cyan     half[0] : persistante, recopie du blanc a chaque frame
#   magenta  half[1] : la meme passe fait discard sauf quand le compteur est multiple de 256
# Tout marche si blanc et jaune montent d'environ un par frame de rendu et que magenta reste sur
# le dernier multiple de 256 franchi. Jaune bloque a 1 : une passe dont la sortie n'est lue qu'a
# la frame suivante est elaguee. Blanc bloque a 1 : rien ne survit d'une frame a l'autre. Magenta
# a 0 entre deux multiples : la target est effacee avant chaque passe.

def persist_shaders():
    from gen_color_id import EXT, FONT
    head = EXT + "\n// GENERE par gen_dct.py -- test des targets persistantes (etape 3), ne pas editer.\n"
    get = """
uint valueAt(sampler2D s, ivec2 p) {
    uvec4 b = uvec4(round(texelFetch(s, p, 0) * 255.0));
    return b.r | (b.g << 8) | (b.b << 16) | (b.a << 24);
}
"""
    put = "fragColor = vec4(uvec4(v, v >> 8, v >> 16, v >> 24) & 255u) / 255.0;"
    return {
        "vmc_persist_inc.fsh": head + """// compteur 32 bits (octets poids faible d'abord) + 1

uniform sampler2D InSampler;

layout(location = 0) out vec4 fragColor;
""" + get + """
void main() {
    uint v = valueAt(InSampler, ivec2(0)) + 1u;
    """ + put + """
}
""",
        "vmc_persist_copy.fsh": head + """// copie texel pour texel

uniform sampler2D InSampler;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0);
}
""",
        "vmc_persist_half.fsh": head + """// cible 2x1 : texel 0 = le compteur ; texel 1 = discard, sauf sur un multiple de 256

uniform sampler2D InSampler;

layout(location = 0) out vec4 fragColor;
""" + get + """
void main() {
    uint v = valueAt(InSampler, ivec2(0));
    if (int(gl_FragCoord.x) == 1 && (v & 255u) != 0u) discard;
    """ + put + """
}
""",
        "vmc_persist_show.fsh": head + f"""// les quatre valeurs en chiffres, a gauche de l'ecran, sur la scene

uniform sampler2D Count1Sampler;
uniform sampler2D Count2Sampler;
uniform sampler2D HalfSampler;
uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

const int SC = 4;                 // pixels d'ecran par pixel de police
const int ND = 8;                 // chiffres par nombre
const int FONT[10] = int[10]({", ".join(str(f) for f in FONT)});
""" + get + """
// nombre a ND chiffres (zeros compris), coin bas-gauche en o
bool digits(ivec2 p, ivec2 o, uint value) {
    ivec2 q = p - o;
    if (q.x < 0 || q.y < 0 || q.x >= 4 * ND * SC || q.y >= 5 * SC) return false;
    int fx = q.x / SC, fy = q.y / SC;
    int di = fx / 4, cx = fx - di * 4;
    if (cx >= 3) return false;
    for (int k = 0; k < ND - 1 - di; k++) value /= 10u;
    int glyph = FONT[int(value % 10u)];
    return ((glyph >> (14 - ((4 - fy) * 3 + cx))) & 1) == 1;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(SrcSampler, 0);
    vec3 col = texelFetch(SrcSampler, p, 0).rgb;
    uint v[4] = uint[4](valueAt(Count1Sampler, ivec2(0)), valueAt(Count2Sampler, ivec2(0)),
                        valueAt(HalfSampler, ivec2(0)), valueAt(HalfSampler, ivec2(1, 0)));
    vec3 c[4] = vec3[4](vec3(1.0), vec3(1.0, 0.85, 0.1), vec3(0.2, 0.9, 1.0), vec3(1.0, 0.3, 1.0));
    ivec2 o = ivec2(16, size.y / 2 + 16 * SC);
    ivec2 q = p - o;
    if (q.x >= -2 * SC && q.x < (4 * ND + 8) * SC && q.y >= -26 * SC && q.y < 7 * SC) col *= 0.2;
    for (int i = 0; i < 4; i++) {
        ivec2 oi = o - ivec2(0, i * 8 * SC);
        ivec2 r = p - oi;
        if (r.x >= 0 && r.y >= 0 && r.x < 5 * SC && r.y < 5 * SC) col = c[i];
        if (digits(p, oi + ivec2(7 * SC, 0), v[i])) col = c[i];
    }
    fragColor = vec4(col, 1.0);
}
"""}


def effects():
    from gen_color_id import SQ
    import gen_qr_detect as D

    def inp(name, target):
        return {"sampler_name": name, "target": target, "bilinear": False}

    def ps(fsh, inputs, out):
        return {"vertex_shader": SQ, "fragment_shader": "minecraft:post/" + fsh,
                "inputs": inputs, "output": out}
    blit = {"vertex_shader": SQ, "fragment_shader": "minecraft:post/blit",
            "inputs": [inp("In", "out")],
            "uniforms": {"BlitConfig": [{"name": "ColorModulate", "type": "vec4",
                                         "value": [1.0, 1.0, 1.0, 1.0]}]},
            "output": "minecraft:main"}
    # le decodeur, commun a dct_test et vmc_data : des symboles (entree cells) au canva (img)
    words = inp("Words", "words")
    decoder = lambda cells: [ps("vmc_vlc_words" + SFX, [cells], "words"),
                             ps("vmc_vlc_mbstart" + SFX, [words], "mbstart"),
                             ps("vmc_vlc_blocks", [inp("MbStart", "mbstart"), words], "blocks"),
                             ps("vmc_vlc_coefs", [inp("Blocks", "blocks"), words], "coefs"),
                             ps("vmc_vlc_rows", [inp("Coefs", "coefs"), words], "rows"),
                             ps("vmc_vlc_idct", [inp("Rows", "rows")], "img")]
    dec_targets = {"words": {"width": WORDS_W, "height": WORDS_H}, "mbstart": {"width": SX, "height": SY},
                   "blocks": {"width": SX * 6, "height": SY}, "coefs": {"width": SX * 24, "height": SY * 8},
                   "rows": {"width": SX * 64, "height": SY * 4}, "img": {"width": WC_, "height": HC_}}
    # dct_test : decode une image fixe livree dans le pack (textures/effect/vmc_dct_test.png),
    # pour valider le decodeur EN JEU sans le mur ni sa lecture
    cells = {"sampler_name": "Cells", "location": "minecraft:vmc_dct_test",
             "width": DW, "height": DH, "bilinear": False}
    test = {"targets": {**dec_targets, "out": {}},
            "passes": decoder(cells) + [
                ps("vmc_dct_draw", [inp("Img", "img"), inp("Src", "minecraft:main")], "out"), blit]}
    # vmc_data : le mode data du plugin. Detection du mur (seed et tile communs avec qr_*, fit a
    # ses portees), perspective, motifs, lecture des symboles, puis le decodeur de dct_test
    L = wall_layout()
    nt = len(L.targets)
    src = inp("Src", "minecraft:main")
    hom_in = [inp("Fit", "fit"), inp("Persp", "persp"), inp("Align", "align")]
    data = {"targets": {"cand": {}, "tile": {"width": 32, "height": 32}, "fit": {"width": 4, "height": 1},
                        "persp": {"width": 2, "height": 1},
                        "ascore": {"width": nt * 33, "height": 33}, "align": {"width": nt, "height": 1},
                        "cells": {"width": DW, "height": DH}, **dec_targets, "out": {}},
            "passes": D.effects()["qr_detect.json"]["passes"][:2] + [   # seed, tile
                       ps("vmc_wall_fit" + SFX, [inp("In", "tile"), inp("Cand", "cand")], "fit"),
                       ps("vmc_qr_persp", [inp("Fit", "fit"), src], "persp"),
                       ps("vmc_wall_align" + SFX, [inp("Fit", "fit"), inp("Persp", "persp"), src], "ascore"),
                       ps("vmc_wall_pick" + SFX, [inp("In", "ascore"), inp("Fit", "fit"), inp("Persp", "persp")],
                          "align"),
                       ps("vmc_wall_extract" + SFX, hom_in + [src], "cells")]
                      + decoder(inp("Cells", "cells")) + [
                       ps("vmc_data_draw" + SFX, [inp("Img", "img")] + hom_in + [src], "out"),
                       blit]}
    out = {"vmc_data" + SFX + ".json": data}
    if WALL == "wan":
        out["dct_test.json"] = test
        one = {"width": 1, "height": 1}
        out["vmc_persist_test.json"] = {
            "targets": {"counter1": {**one, "persistent": True}, "counter2": {**one, "persistent": True},
                        "half": {"width": 2, "height": 1, "persistent": True},
                        "tmp1": dict(one), "tmp2": dict(one), "out": {}},
            "passes": [ps("vmc_persist_inc", [inp("In", "counter1")], "tmp1"),
                       ps("vmc_persist_copy", [inp("In", "tmp1")], "counter1"),
                       ps("vmc_persist_inc", [inp("In", "counter2")], "tmp2"),
                       ps("vmc_persist_copy", [inp("In", "tmp2")], "counter2"),     # relu par personne
                       ps("vmc_persist_half", [inp("In", "tmp1")], "half"),
                       ps("vmc_persist_show", [inp("Count1", "counter1"), inp("Count2", "tmp2"),
                                               inp("Half", "half"), inp("Src", "minecraft:main")], "out"),
                       blit]}
    return out


def test_cells():
    from PIL import Image
    return encode(fit_canvas(Image.open(TEST_IMAGE)))


def write():
    import json
    from PIL import Image
    import gen_qr_detect as D
    shs, effs = {}, {}
    for key in WALLS:
        use_wall(key)
        for name, body in shaders().items():
            assert shs.setdefault(name, body) == body, f"{name} : commun aux murs mais pas identique"
        effs.update(effects())
    use_wall("wan")
    D.check_samplers(effs, {**D.shaders(), **shs})
    for name, body in shs.items():
        with open(os.path.join(POST, name), "w") as f:
            f.write(body)
    for name, eff in effs.items():
        with open(os.path.join(EFF, name), "w") as f:
            json.dump(eff, f, indent=2)
            f.write("\n")
    os.makedirs(TEX, exist_ok=True)
    cells = test_cells()
    rgba = np.zeros(cells.shape + (4,), np.uint8)
    rgba[..., 0], rgba[..., 3] = cells, 255
    Image.fromarray(rgba, "RGBA").save(os.path.join(TEX, "vmc_dct_test.png"))
    print(f"OK: {len(shs)} fsh + {' + '.join(effs)} + textures/effect/vmc_dct_test.png "
          f"({cells.shape[1]}x{cells.shape[0]})")


def chain(effect, images):
    """Cablage d'un effet pour qr_glcheck ; images : {location: (fichier rgba, w, h)} pour les
    entrees texture (textures/effect/*.png en jeu)."""
    eff = effects()[effect + ".json"]
    lines = [f"target {n} {s.get('width', 0)} {s.get('height', 0)}" for n, s in eff["targets"].items()]
    for loc, (path, w, h) in images.items():
        lines.append(f"image {loc.split(':')[1]} {w} {h} {path}")
    for p in eff["passes"]:
        fsh = p["fragment_shader"].split("/", 1)[1]
        if fsh != "blit":
            ins = " ".join(f"{i['sampler_name']}=" + (i["target"] if "target" in i
                           else i["location"].split(":")[1]) for i in p["inputs"])
            lines.append(f"pass {fsh} {p['output']} {ins}")
    return "\n".join(lines) + "\n"


def glcheck():
    """qr_glcheck (EGL sans fenetre), recompile si qr_glcheck.c est plus recent."""
    import subprocess
    exe = os.path.join(ROOT, "qr_glcheck")
    src = os.path.join(ROOT, "qr_glcheck.c")
    if not os.path.exists(exe) or os.path.getmtime(exe) < os.path.getmtime(src):
        subprocess.run(["gcc", "-O2", src, "-o", exe, "-lEGL", "-lGL"], check=True)
    return exe


def u32(t):
    return t[..., 0] | (t[..., 1] << 8) | (t[..., 2] << 16) | (t[..., 3] << 24)


def i16(t):
    """Texel de deux entiers 16 bits (r, g puis b, a) -> [..., 2] signes."""
    v = np.stack([t[..., 0] | (t[..., 1] << 8), t[..., 2] | (t[..., 3] << 8)], -1)
    return np.where(v >= 32768, v - 65536, v)


DEC_TARGETS = lambda: {"words": (WORDS_W, WORDS_H), "mbstart": (SX, SY), "blocks": (SX * 6, SY),
                       "coefs": (SX * 24, SY * 8), "rows": (SX * 64, SY * 4), "img": (WC_, HC_)}


def check_decode(g, cells):
    """Le decodeur du GPU (g : ses cibles relues) contre numpy, depuis les symboles qu'il a lus,
    chaque etage nourri des sorties GPU de l'etage d'avant : words, debuts, blocs et coefs a l'entier
    pres ; rows a 1/16 pres et img a un niveau pres (float32 contre float64, un arrondi peut
    basculer). -> (texte, ok)."""
    wn = np.zeros(WORDS_W * WORDS_H, np.int64)
    wn[:N_WORDS] = symbols_to_words(cells.reshape(-1))
    wd = int((u32(g["words"]).reshape(-1) != wn).sum())
    q, starts, blk, z = parse(cells)
    sd = int((u32(g["mbstart"]) & 0xFFFFFF != starts.reshape(SY, SX)).sum())
    bo = u32(g["blocks"])
    bd = int((np.stack([bo >> 11, ((bo & 0x7FF) ^ 0x400) - 0x400], -1) != blk.reshape(SY, SX * 6, 2)).any(-1).sum())
    cg = i16(g["coefs"])
    cd = int((cg != coefs_target(z)).any(-1).sum())
    rg = i16(g["rows"])
    rdif = np.abs(rg - rows_target(cg, q)).max(-1)
    idif = np.abs(g["img"][..., :3] - idct_target(rg).astype(np.int64)).max(-1)
    ok = wd == 0 and sd == 0 and bd == 0 and cd == 0 and (rdif <= 1).all() and (idif <= 1).all()
    return (f"words {wd}, debuts {sd}, blocs {bd}, coefs {cd}, rows {int((rdif > 0).sum())} a 1/16"
            f" / {int((rdif > 1).sum())} au-dela, img {int((idif > 0).sum())} px a 1 niveau"
            f" / {int((idif > 1).sum())} au-dela"), ok


def run_chain(effect, images, screen):
    """Execute un effet ecrit sur le GPU (qr_glcheck) -> (cibles relues {nom: [h, w, 4]}, temps
    {passe: ms}, nom du GPU)."""
    import subprocess
    import tempfile
    eff = effects()[effect + ".json"]
    h, w = screen.shape[:2]
    with tempfile.TemporaryDirectory() as d:
        np.concatenate([screen, np.full((h, w, 1), 255, np.uint8)], 2).tofile(os.path.join(d, "in.rgba"))
        imgs = {}
        for loc, arr in images.items():
            rgba = np.zeros(arr.shape + (4,), np.uint8)
            rgba[..., 0], rgba[..., 3] = arr, 255
            rgba.tofile(os.path.join(d, loc.split(":")[1] + ".rgba"))
            imgs[loc] = (os.path.join(d, loc.split(":")[1] + ".rgba"), arr.shape[1], arr.shape[0])
        with open(os.path.join(d, "chain.txt"), "w") as f:
            f.write(chain(effect, imgs))
        run = subprocess.run([glcheck(), str(w), str(h), os.path.join(d, "in.rgba"), d,
                              os.path.join(d, "chain.txt")], cwd=ROOT, capture_output=True, text=True, check=True)
        g = {}
        for n, t in eff["targets"].items():
            tw, th = (t.get("width", w), t.get("height", h)) if t else (w, h)
            g[n] = np.fromfile(os.path.join(d, n + ".rgba"), np.uint8).reshape(th, tw, 4).astype(np.int64)
    ms = {l.split()[1]: float(l.split()[2]) for l in run.stdout.splitlines() if l.startswith("ms ")}
    gpu = run.stderr.strip().splitlines()[-1] if run.stderr.strip() else "?"
    return g, ms, gpu


def gpu_check(previews=None):
    """dct_test sur le GPU : le decodeur ecrit (.fsh) contre numpy sur les images de test, draw
    a un niveau pres. Et le PSNR de ce que le GPU affiche, contre la source."""
    ok, worst = True, {}
    for name, img in test_images():
        cells = encode(img)
        screen = np.full((1080, 1920, 3), 40, np.uint8)
        g, ms, gpu = run_chain("dct_test", {"minecraft:vmc_dct_test": cells}, screen)
        for k, v in ms.items():
            worst[k] = max(worst.get(k, 0.0), v)
        text, good = check_decode(g, cells)
        dd = np.abs(g["out"][..., :3] - draw(g["img"][..., :3], screen.astype(np.int64))).max(-1)
        good &= int((dd > 1).sum()) == 0
        ok &= good
        print(f"  {'OK ' if good else 'DIFF'} {name:40} {text}, draw {int((dd > 1).sum())} ;"
              f" GPU {psnr(g['img'][..., :3].astype(np.uint8), img):5.1f} dB")
        if previews:
            from PIL import Image
            Image.fromarray(g["out"][::-1, :, :3].astype(np.uint8)).save(
                os.path.join(previews, "gpu_dct_" + os.path.splitext(name)[0] + ".png"))
    print("  pire temps par passe (ms) :", "  ".join(f"{k} {v:.2f}" for k, v in worst.items()))
    print("  GPU :", gpu)
    return ok


GPU_SCENES = {"wan": ((1920, 1080, 3.0, 0, 0), (1920, 1080, 4.0, 1, 0), (1920, 1080, 2.5, 0, 3),
                      (1823, 1394, 3.5, 0, 0), (1823, 1394, 3.0, 1, 2)),
              "lan": ((1920, 1080, 2.6, 0, 0), (1920, 1080, 2.7, 1, 0), (1823, 1394, 2.7, 0, 0),
                      (2560, 1440, 3.0, 0, 0), (2560, 1440, 3.5, 1, 2))}


def gpu_check_data(previews=None):
    """La chaine vmc_data ECRITE sur le GPU, sur des murs rendus par la camera de simulation.
    Chaque etage contre numpy, nourri des sorties GPU de l'etage d'avant : fit / persp octet pour
    octet, scores et centres des motifs a un arrondi pres (marge < 1e-3), cellules a un arrondi
    pres (le centre de la cellule a moins de TIE_PX px d'une bascule), puis le decodeur
    (check_decode). Et surtout : les symboles lus par le GPU contre ceux de l'encodeur."""
    import gen_qr_detect as D
    L = wall_layout()
    nt = len(L.targets)
    ok, worst = True, {}
    imgs = test_images()[:3]
    print(f"  -- mur {WALL} {WALL_MAPS[0]}x{WALL_MAPS[1]}, canva {WC_}x{HC_}")
    for n, (w, h, m_px, roll, tilt) in enumerate(GPU_SCENES[WALL]):
        name, img = imgs[n % len(imgs)]
        cells = encode(img)
        screen = render_wall(cells, w, h, m_px, roll, tilt)
        g, ms, gpu = run_chain("vmc_data" + SFX, {}, screen)
        for k, v in ms.items():
            worst[k] = max(worst.get(k, 0.0), v)
        tup = lambda a: [tuple(int(v) for v in t) for t in a]
        cls, _, _, fit_np = D.detect(screen, L)
        fit_g, persp_g, align_g = tup(g["fit"][0]), tup(g["persp"][0]), tup(g["align"][0])
        fd = sum(fit_g[i] != tuple(fit_np[i]) for i in range(4))
        pd = int(persp_g != D.persp(cls, fit_g))
        pts, status = D.decode_fit(fit_g)
        F = [(q[0], q[1]) for q in pts] if status[3] == 255 else None
        gh0 = D.gh_persp(persp_g) or (0.0, 0.0)
        sc_np = D.align_scores(cls, fit_g, persp_g, L)
        ad = at = 0
        for k in range(nt):
            blk = slice(k * 33, (k + 1) * 33)
            if (sc_np[:, blk] != g["ascore"][:, blk]).any():
                tie = F is not None and D.align_window(F, gh0, k, L)[4] < 1e-3
                at += tie
                ad += not tie
        al_np = D.align_pick(g["ascore"], fit_g, persp_g, L)
        kd = sum(al_np[k] != align_g[k] for k in range(nt))
        # cellules : depuis fit / persp / align du GPU
        gcells = g["cells"][..., 0]
        cell_np = extract(screen, fit_g, align_g, persp_g)
        cd = ct = 0
        if status[3] == 255:
            Fh, gh = D.homography(fit_g, align_g, persp_g, L)
            cy, cx = np.mgrid[0:DH, 0:DW]
            with np.errstate(all="ignore"):
                x, y = D.hmap(Fh, gh, DATA_X0 + cx + 0.5, DATA_Y0 + cy + 0.5, L)
            near = (D.margin_arr(x) < D.TIE_PX) | (D.margin_arr(y) < D.TIE_PX)
            diff = cell_np != gcells
            cd, ct = int((diff & ~near).sum()), int((diff & near).sum())
        text, dec_ok = check_decode(g, gcells)
        bad_sym = int((gcells != cells).sum())
        good = fd == 0 and pd == 0 and ad == 0 and kd == 0 and cd == 0 and dec_ok and bad_sym == 0
        ok &= good
        print(f"  {'OK ' if good else 'DIFF'} {w}x{h} m{m_px} r{roll} t{tilt}: fit {fd}/4, persp {pd}, scores {ad} "
              f"({at} arrondi), centres {kd}, cellules {cd} ({ct} arrondi), {text} ; symboles lus faux {bad_sym},"
              f" motifs {len(D.decode_align(align_g))}/{nt}, PSNR {psnr(g['img'][..., :3].astype(np.uint8), img):5.1f} dB")
        if previews:
            from PIL import Image
            Image.fromarray(g["out"][::-1, :, :3].astype(np.uint8)).save(
                os.path.join(previews, f"gpu_data{SFX}_{w}x{h}_m{m_px}_r{roll}_t{tilt}.png"))
    print("  pire temps par passe (ms) :", "  ".join(f"{k} {v:.2f}" for k, v in worst.items()))
    print("  GPU :", gpu)
    return ok


def mcmm_check(exe):
    """mcmm --data (le codeur du serveur, en C) contre ce script, octet pour octet sur le mur
    courant : 3 images de test et du bruit, passees en clip png (sans perte) au canva exact.
    En longueur variable, un seul coefficient de travers decale tout le reste de sa ligne : d'ou la
    DCT dans un ordre de sommes fixe (dct8). Pour le mur wan, les references data_ref/ (le format
    du 13/10) doivent aussi tenir."""
    import subprocess
    import tempfile
    from PIL import Image
    w, h = WALL_MAPS
    frames = [img for _, img in test_images()[:3]]
    frames.append(np.random.default_rng(7).integers(0, 256, (HC_, WC_, 3), np.uint8))
    ok = True
    with tempfile.TemporaryDirectory() as d:
        for n, img in enumerate(frames):
            png, clip = os.path.join(d, "f.png"), os.path.join(d, "f.mkv")
            Image.fromarray(img).save(png)
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-loop", "1", "-framerate", "10", "-t", "1",
                            "-i", png, "-c:v", "png", clip], check=True)
            out = subprocess.run([exe, "--stream", "--data", clip, str(w), str(h), "10"],
                                 capture_output=True, check=True).stdout
            got = np.frombuffer(out[24:24 + w * h * 16384], np.uint8)
            want = np.frombuffer(wall_tiles(wall_ids(encode(img))), np.uint8)
            bad = int((got != want).sum()) if got.size == want.size else -1
            ok &= bad == 0
            print(f"  {'OK ' if bad == 0 else 'DIFF'} mur {WALL} image {n} : {bad} octets differents")
    if WALL == "wan":
        for n in range(3):
            ref = os.path.join(ROOT, "data_ref", f"frame{n}")
            img = np.asarray(Image.open(ref + ".png").convert("RGB"))
            same = wall_tiles(wall_ids(encode(img))) == open(ref + ".wall", "rb").read()
            ok &= same
            print(f"  {'OK ' if same else 'DIFF'} data_ref/frame{n}.wall regenere a l'identique")
    return ok


if __name__ == "__main__":
    if "--mcmm" in sys.argv:              # python3 gen_dct.py --mcmm <chemin de mcmm>
        ok = True
        for key in WALLS:
            use_wall(key)
            ok = mcmm_check(sys.argv[sys.argv.index("--mcmm") + 1]) and ok
        sys.exit(0 if ok else 1)
    if "--net-stats" in sys.argv:         # python3 gen_dct.py --net-stats <mcmm> <video> [debut en s]
        i = sys.argv.index("--net-stats")
        ok = True
        for key in WALLS:
            use_wall(key)
            ok = net_stats(sys.argv[i + 1], sys.argv[i + 2], float(sys.argv[i + 3]) if len(sys.argv) > i + 3 else 0.0) and ok
        sys.exit(0 if ok else 1)
    prev = os.environ.get("DCT_PREVIEWS")
    if prev:
        os.makedirs(prev, exist_ok=True)
    if "--gpu" in sys.argv:
        ok = gpu_check(prev)
        for key in WALLS:
            use_wall(key)
            ok = gpu_check_data(prev) and ok
        sys.exit(0 if ok else 1)
    ok = True
    for key in WALLS:
        use_wall(key)
        ok = demo(prev) and ok
        ok = demo_wall() and ok
    use_wall("wan")
    if not ok:
        sys.exit("demo() en echec : shaders NON ecrits")
    if "--demo" not in sys.argv:
        write()
