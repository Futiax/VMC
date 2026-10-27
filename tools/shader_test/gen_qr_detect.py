#!/usr/bin/env python3
"""Effets qr_detect (les 3 reperes d'un QR a l'ecran + le quad), qr_bits (le bit lu sur
chaque module, ecrit dessus) et qr_color (le QR redresse, en couleurs, en haut a gauche).

    python3 gen_qr_detect.py          # demo() d'abord, puis ecrit les shaders SI elle passe
    python3 gen_qr_detect.py --demo   # simulation seule (QR_PREVIEWS=<dossier> : apercus PNG)
    python3 gen_qr_detect.py --gpu    # les .fsh ecrits, executes sur le GPU, contre numpy

POURQUOI une simulation numpy dans le generateur : un fragment shader ne se printf pas. Les
deux defauts qui auraient fait echouer la premiere conception en jeu -- l'inondation de faux
positifs sans QR a l'ecran, et la perte d'un repere sur trois des qu'on regarde de biais --
sont invisibles depuis le GPU et n'ont ete trouves qu'en simulation. Le GLSL ecrit plus bas
est donc une transcription LIGNE A LIGNE des fonctions numpy de ce fichier, memes constantes,
memes arrondis, meme ordre de parcours ; demo() est le garde-fou qui doit passer avant.

Rien n'est decode : qr_detect repond a "le shader voit-il le QR, a quelle distance, sous quel
angle", qr_bits a "lit-il le bon bit sur chaque module", qr_color a "lit-il la bonne couleur".

qr_detect, 5 passes, 4 cibles :
  seed   main -> cand (plein ecran)   plages 1:1:3:1:1 + separateur, par axe
  tile   cand -> tile (32x32)         par tuile : nombre de candidats, barycentre, module
  fit    tile -> fit  (4x1)           3 centres de reperes + statut / motif de rejet
  draw   cand + fit + main -> out     candidats, croix, quad, HUD
  blit   out -> main                  copie vanilla
qr_bits et qr_color : seed, tile, fit, puis l'HOMOGRAPHIE (voir le bloc de commentaires) :
  persp  fit + main -> persp (2x1)    perspective (g, h) mesuree sur les reperes = H0
  align  fit + persp + main -> ascore (198x33)   les 6 motifs d'alignement, 33x33 positions
                                      chacun autour de leur prediction par H0, gabarit 5x5
  pick   ascore -> align (6x1)        centre de chaque motif trouve, au 1/16 de px
qr_bits :
  sample fit/persp/align + main -> bits (57x57)  etat du pixel au centre de chaque module
  bits   bits + ... -> out            0 / 1 / ? dessine dans chaque module, croix, HUD
qr_color :
  flat_draw ... -> out                le QR redresse en 57x57 cases de 8 px, croix, HUD
puis blit.

--gpu execute la chaine de chaque effet TELLE QUE LE JSON LA CABLE (qr_glcheck.c lit la
liste des passes ecrite depuis effects()), et compare chaque cible a numpy.
"""
import math
import os
import sys

import numpy as np

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)

# --- constantes partagees Python / GLSL ---------------------------------------------------

# Test de repere PAR LONGUEURS DE PLAGES (comme zxing), pas par points fixes. La premiere
# version echantillonnait 4 points par cote pour 22 tailles de module : dans les donnees
# ALEATOIRES du QR, a 1,6 ou 2 fois la taille du module, 9 points tombent sur la bonne
# couleur assez souvent pour sortir 40 a 180 faux candidats par frame en simulation --
# assez pour tirer les grappes et rejeter le QR. Mesurer les plages exige qu'elles soient
# CONTINUES et dans le bon rapport, et ne suppose aucune taille : plus de jeu de tailles.
#
# Tout est en ENTIERS (tot = somme des 5 plages = 7 modules) : numpy et GLSL tombent pile
# pareil, sans flottant 32/64 bits pour trancher differemment un cas limite.
RUN_CAP = 96          # plage max (px) ; au-dela on rejette. 1,5 module de demi-coeur a 64
                      # px/module, ou un QR plein ecran en 4K. Borne aussi la boucle GLSL.
CORE_TOL2 = 1         # |coeur - 3m| <= CORE_TOL2 / 2 * m en h et v. zxing tolere 1,5 m :
                      # un 1:1:2:1:1 des donnees passait (vu en simulation, 2 modules sombres)
CORE_TOL2_DIAG = 2    # en diagonale, un candidat decale de 0,5 module en x ET en y raccourcit
                      # le coeur de 1,4 module sur 4,2 : il faut la marge
RING_TOL2 = 1         # |anneau - m| <= RING_TOL2 / 2 * m      (zxing : 0,5)
ANISO_MAX = (5, 2)    # m_h / m_v dans [2/5 ; 5/2] : ~66 degres d'inclinaison
DARK_T = 0.25         # luma < DARK_T  : sombre
LIGHT_T = 0.70        # luma > LIGHT_T : clair ; entre les deux : ni l'un ni l'autre, et ce
                      # pixel fait echouer les DEUX tests -> c'est ce qui tue les faux
                      # positifs du monde (texture moyenne). Bouton de calibration.
TILE_N = 32           # tuiles par axe, cible tile 32x32
CNT_MAX = 4096        # nombre de candidats par tuile code en racine : 8 bits, precision
CNT_SCALE = 255.0 / math.sqrt(CNT_MAX)   # relative ~1 % de 1 a 4096
K_MAX = 8             # grappes examinees ; 56 triangles au plus
CLUSTER_MODULES = 2.0 # rayon d'une grappe (suppression des non-maxima ET agregation)
MAX_TILES = 48        # au-dela : bruit, pas un QR (vrai QR : une vingtaine de tuiles)
MOD_RATIO_MAX = 3.0   # modules des 3 reperes coherents. Perspective REELLE (FOV vertical 70) :
                      # 16 px pres contre 7 loin a 45 deg pour un QR de 12 px/module -- la
                      # limite a 2 rejetait ce vrai triangle
MIN_FILL = 0.1        # une grappe pese au moins MIN_FILL * m^2 candidats : un vrai repere en
                      # donne ~m^2 (un carre de 1 module), un faux motif n'en donne qu'1 ou 2 --
                      # a 45 deg, un faux d'1 pixel battait le vrai repere au score
LEG_RATIO_MAX = 2.5
COS_MAX = 0.6         # angle au coin TL entre 53 et 127 degres
LEG_LO, LEG_HI = 0.5, 2.0   # jambe / (50 * module)
SPAN = 50.0           # centres de reperes a 50 modules (3,5 et 53,5) sur un v10
K = 3.5 / SPAN        # du centre du repere au coin exterieur, en fractions de jambe

LUMA = (0.2126, 0.7152, 0.0722)
FOV_V = 70.0          # reglage FOV de Minecraft (defaut 70), VERTICAL : premier argument de
                      # Matrix4f.setPerspective(fovy, ...) dans Projection.class (jar 26.3)


def fl(x):
    """Arrondi deterministe partage avec le GLSL (round() GLSL est libre sur les .5)."""
    return np.floor(np.asarray(x, dtype=np.float64) + 0.5).astype(np.int64)


# --- passe 1 : seed ------------------------------------------------------------------------

def classify(img):
    """0 = ni sombre ni clair, 1 = sombre, 2 = clair. img : uint8 [H, W, 3]."""
    y = (img.astype(np.float64) / 255.0) @ np.array(LUMA)
    c = np.zeros(y.shape, np.int8)
    c[y < DARK_T] = 1
    c[y > LIGHT_T] = 2
    return c


def left_runs(s):
    """Pour chaque pixel : nombre de pixels consecutifs de meme etat finissant en x, inclus."""
    h, w = s.shape
    idx = np.broadcast_to(np.arange(w), (h, w))
    change = np.ones(s.shape, bool)
    change[:, 1:] = s[:, 1:] != s[:, :-1]
    start = np.maximum.accumulate(np.where(change, idx, 0), axis=1)
    return idx - start + 1


def axis_test(s):
    """Test 1:1:3:1:1 le long des LIGNES de s. -> (ok, tot) ; tot = 7 modules en px.

    Le GLSL marche pixel a pixel depuis le centre : coeur sombre, anneau clair, anneau
    sombre, separateur clair, de chaque cote. Ici les memes plages sortent de left_runs ; la
    colonne de bourrage a 0 joue le hors-ecran (ni sombre ni clair, comme le GLSL)."""
    h, w = s.shape
    sp = np.pad(s, ((0, 0), (1, 1)))
    runs = {-1: left_runs(sp), 1: left_runs(sp[:, ::-1])[:, ::-1]}
    x = np.broadcast_to(np.arange(1, w + 1), (h, w))

    def side(step):
        run = runs[step]
        # clip : une plage d'etat 0 avale le bourrage ; seuls les centres sombres comptent
        st = lambda p: np.take_along_axis(sp, np.clip(p, 0, w + 1), 1)
        rl = lambda p: np.take_along_axis(run, np.clip(p, 0, w + 1), 1)
        c = rl(x) - 1                                # coeur, pixel central exclu
        p = x + step * (c + 1)
        b = np.where(st(p) == 2, rl(p), 0)
        p = p + step * b
        a = np.where(st(p) == 1, rl(p), 0)
        p = p + step * a
        sep = np.where(st(p) == 2, rl(p), 0)
        return c, b, a, sep

    cl, bl, al, sl = side(-1)
    cr, br, ar, sr = side(1)
    core = cl + cr + 1
    tot = core + bl + br + al + ar
    need = np.maximum(1, tot // 14)                  # separateur : au moins un demi-module
    ok = s == 1
    for r in (cl, cr, bl, br, al, ar):
        ok &= r <= RUN_CAP
    for r in (bl, br, al, ar):
        ok &= 2 * np.abs(7 * r - tot) <= RING_TOL2 * tot
    ok &= 2 * np.abs(7 * core - 3 * tot) <= CORE_TOL2 * tot
    ok &= 7 * np.abs(cl - cr) <= tot                 # centre a +-0,5 module pres
    ok &= (sl >= need) & (sr >= need)
    return ok, tot


def walk(cls, x, y, dx, dy, state, limit):
    """Pixels consecutifs d'etat `state` depuis (x, y) inclus, pas (dx, dy), au plus `limit`.
    Hors ecran = arret. C'est la boucle GLSL telle quelle."""
    h, w = cls.shape
    n = 0
    while n < limit and 0 <= x < w and 0 <= y < h and cls[y, x] == state:
        n += 1
        x += dx
        y += dy
    return n


def line_test(cls, x, y, dx, dy, sym, core_tol2):
    """Version pixel a pixel d'axis_test, sur une droite quelconque. -> (ok, tot).

    Sert aux DIAGONALES (contre-controle de zxing) : une droite passant par le centre de trois
    carres concentriques les coupe toujours en 1:1:3:1:1 -- rotation et inclinaison ne
    changent rien, une transformation affine conserve les rapports sur une droite. Un faux
    repere fait de donnees aleatoires (une croix sombre qui passe en h ET en v) echoue la."""
    if cls[y, x] != 1:
        return False, 0
    runs = []
    for sx, sy in ((-dx, -dy), (dx, dy)):
        px, py = x + sx, y + sy
        side = []
        for state in (1, 2, 1):
            n = walk(cls, px, py, sx, sy, state, RUN_CAP + 1)
            if n > RUN_CAP:
                return False, 0
            side.append(n)
            px, py = px + n * sx, py + n * sy
        side.append((px, py))
        runs.append(side)
    (cl, bl, al, pl), (cr, br, ar, pr) = runs
    core = cl + cr + 1
    tot = core + bl + br + al + ar
    for r in (bl, br, al, ar):
        if 2 * abs(7 * r - tot) > RING_TOL2 * tot:
            return False, tot
    if 2 * abs(7 * core - 3 * tot) > core_tol2 * tot:
        return False, tot
    if sym and 7 * abs(cl - cr) > tot:
        return False, tot
    need = max(1, tot // 14)
    if walk(cls, pl[0], pl[1], -dx, -dy, 2, need) < need or walk(cls, pr[0], pr[1], dx, dy, 2, need) < need:
        return False, tot
    return True, tot


def seed(cls):
    """-> (R, G, B) de la cible cand : module horizontal, vertical, moyen (px, 0 = rien)."""
    okh, th = axis_test(cls)
    okv, tv = axis_test(cls.T)
    okv, tv = okv.T, tv.T
    hi, lo = np.maximum(th, tv), np.minimum(th, tv)
    ok = okh & okv & (ANISO_MAX[1] * hi <= ANISO_MAX[0] * lo)
    # diagonales : seulement la ou h et v passent deja (une poignee de pixels), comme en GLSL
    for y, x in zip(*np.nonzero(ok)):
        if not (line_test(cls, x, y, 1, 1, False, CORE_TOL2_DIAG)[0]
                and line_test(cls, x, y, 1, -1, False, CORE_TOL2_DIAG)[0]):
            ok[y, x] = False
    r = np.where(ok, np.clip(fl(th / 7.0), 1, 255), 0)
    g = np.where(ok, np.clip(fl(tv / 7.0), 1, 255), 0)
    b = np.where(ok, np.clip(fl(np.sqrt(th * tv) / 7.0), 1, 255), 0)
    return r, g, b


# --- passe 2 : tile ------------------------------------------------------------------------

def tile_sizes(w, h):
    return -(-w // TILE_N), -(-h // TILE_N)


def tile(cand_r, cand_b):
    """-> int [TILE_N(y), TILE_N(x), 4] : exactement les octets que le GLSL ecrit."""
    h, w = cand_r.shape
    tsx, tsy = tile_sizes(w, h)
    ys, xs = np.nonzero(cand_r > 0)
    tx, ty = xs // tsx, ys // tsy
    n, sx, sy, sm = (np.zeros((TILE_N, TILE_N), np.int64) for _ in range(4))
    # sommes en ENTIERS et relatives a l'origine de la tuile, arrondis en entiers aussi :
    # (2*s*255 + n*ts) // (2*n*ts) = round(s / n / ts * 255). En flottant, un barycentre
    # pile sur x,5 tombait a 2 sur le GPU (float32) et a 3 ici -- vu par --gpu.
    # Pire cas 4K : tuile 120 px, s <= 119 * 8160, *510 = 5e8 < 2^31.
    np.add.at(n, (ty, tx), 1)
    np.add.at(sx, (ty, tx), xs - tx * tsx)
    np.add.at(sy, (ty, tx), ys - ty * tsy)
    np.add.at(sm, (ty, tx), cand_b[ys, xs])
    out = np.zeros((TILE_N, TILE_N, 4), np.int64)
    nz = n > 0
    n1 = np.maximum(n, 1)
    out[..., 0] = np.where(nz, np.clip((2 * sx * 255 + n1 * tsx) // (2 * n1 * tsx), 0, 255), 0)
    out[..., 1] = np.where(nz, np.clip((2 * sy * 255 + n1 * tsy) // (2 * n1 * tsy), 0, 255), 0)
    # CNT_SCALE = 255/64 est exact en float32 : seul n = 1024 tombe pile sur x,5, et la il
    # n'y a aucun arrondi des deux cotes
    out[..., 2] = np.where(nz, np.clip(fl(np.sqrt(n) * CNT_SCALE), 1, 255), 0)
    out[..., 3] = np.where(nz, np.clip((2 * sm + n1) // (2 * n1), 1, 255), 0)
    return out


# --- passe 3 : fit -------------------------------------------------------------------------

def tile_pos(t, tx, ty, tsx, tsy):
    return (tx * tsx + t[0] / 255.0 * tsx, ty * tsy + t[1] / 255.0 * tsy)


def tile_count(t):
    return (t[2] / CNT_SCALE) ** 2


def clusters(tiles, w, h):
    """-> jusqu'a K_MAX grappes (x, y, m, n), par nombre de candidats decroissant.

    Graine = tuile la plus peuplee hors des grappes deja prises (suppression des non-maxima),
    puis barycentre pondere des tuiles a moins de CLUSTER_MODULES modules. Le rayon est PETIT
    expres : une grappe de vrai repere tient dans +-0,5 module, et un rayon de 10 modules
    laissait une fausse grappe a grand module (plus peuplee, le nombre va comme m^2) avaler
    le vrai repere voisin."""
    tsx, tsy = tile_sizes(w, h)
    seeds = []
    for _ in range(K_MAX):
        best, best_cnt = None, 0
        for ty in range(TILE_N):                    # ordre de parcours = ordre GLSL
            for tx in range(TILE_N):
                t = tiles[ty, tx]
                if t[2] == 0:
                    continue
                p = tile_pos(t, tx, ty, tsx, tsy)
                if any(math.dist(p, sp) <= CLUSTER_MODULES * sm for sp, sm in seeds):
                    continue
                if t[2] > best_cnt:
                    best, best_cnt = (p, float(t[3])), int(t[2])
        if best is None:
            break
        seeds.append(best)

    out = []
    for sp, sm in seeds:
        wsum = xsum = ysum = msum = 0.0
        for ty in range(TILE_N):
            for tx in range(TILE_N):
                t = tiles[ty, tx]
                if t[2] == 0:
                    continue
                p = tile_pos(t, tx, ty, tsx, tsy)
                if math.dist(p, sp) <= CLUSTER_MODULES * sm:
                    wt = tile_count(t)
                    wsum += wt
                    xsum += wt * p[0]
                    ysum += wt * p[1]
                    msum += wt * t[3]
        out.append((xsum / wsum, ysum / wsum, msum / wsum, wsum))
    return out


def triple(a, b, c, L=None):
    """-> (score, motif de rejet, (TL, TR, BL)) pour trois grappes (x, y, m).

    Le plus long cote relie TR a BL, le sommet restant est TL -- mais mesure EN MODULES :
    chaque distance est divisee par le module moyen de ses deux bouts. En pixels, la
    perspective trompe : a 45 deg avec le FOV reel, le bord proche TL-TR (829 px, modules de
    16 px) depassait la diagonale TR-BL (795 px, vers un repere lointain de 7 px) ; en
    modules c'est 52 contre 69. Les jambes sont comparees a 50 modules de la meme facon.
    TR et BL se departagent au produit vectoriel : une image n'est jamais miroir, donc
    TL->TR puis TL->BL tourne toujours dans le meme sens (horaire en coords GL, y en haut).
    Jambes comparees a leur portee (L.span_u, L.span_v) : 50 et 50 sur un QR, 439 et 247 sur
    le mur de donnees de gen_dct.py, ou l'ecart de longueur est normal.

    L.tl_by_score (le mur) : TL = le sommet qui donne le meilleur score des trois. Sur un
    rectangle 7x4 la diagonale ne depasse le grand cote que de 15 % (41 % sur un carre) : a
    2,5 px par module, des modules mesures 2 et 3 px suffisaient a la faire perdre."""
    L = L or QR
    pts = ((a[0], a[1]), (b[0], b[1]), (c[0], c[1]))
    ms = (a[2], b[2], c[2])
    mr = max(ms) / min(ms)

    def assess(t, i1, i2):
        tl = pts[t]
        u = (pts[i1][0] - tl[0], pts[i1][1] - tl[1])
        v = (pts[i2][0] - tl[0], pts[i2][1] - tl[1])
        if u[0] * v[1] - u[1] * v[0] > 0:
            i1, i2, u, v = i2, i1, v, u
        lu, lv = math.hypot(*u), math.hypot(*v)
        cos = abs(u[0] * v[0] + u[1] * v[1]) / max(lu * lv, 1e-6)
        mu = lu / ((ms[t] + ms[i1]) / 2.0)            # jambes en modules
        mv = lv / ((ms[t] + ms[i2]) / 2.0)
        ru, rv = mu / L.span_u, mv / L.span_v
        reject = 0
        if mr > MOD_RATIO_MAX:
            reject |= 4
        if max(ru, rv) > LEG_RATIO_MAX * min(ru, rv) or cos > COS_MAX:
            reject |= 8
        if min(ru, rv) < LEG_LO or max(ru, rv) > LEG_HI:
            reject |= 16
        score = abs(math.log(max(ru, 1e-6))) + abs(math.log(max(rv, 1e-6))) + math.log(mr) + cos
        return score, reject, (tl, pts[i1], pts[i2])

    if L.tl_by_score:
        best = None
        for t, i1, i2 in ((0, 1, 2), (1, 0, 2), (2, 0, 1)):
            res = assess(t, i1, i2)
            if best is None or res[0] < best[0]:
                best = res
        return best

    def nd(i, j):
        return math.dist(pts[i], pts[j]) / ((ms[i] + ms[j]) / 2.0)

    d01, d02, d12 = nd(0, 1), nd(0, 2), nd(1, 2)
    if d12 >= d02 and d12 >= d01:
        return assess(0, 1, 2)
    if d02 >= d01:
        return assess(1, 0, 2)
    return assess(2, 0, 1)


def fit(tiles, w, h, L=None):
    """-> liste de 4 texels (R, G, B, A). Les quatre fragments GLSL refont chacun la MEME
    recherche deterministe et ne sortent que leur texel : pas de memoire partagee.

    Le meilleur triangle parmi les K_MAX grappes, pas les 3 plus grosses : les donnees d'un
    QR forment parfois un vrai 1:1:3:1:1 (la simulation en trouve 2 a 5 par mire aleatoire,
    c'est ce que le masquage d'un vrai encodeur minimise sans l'interdire), et une fausse
    grappe a module plus grand est aussi plus peuplee."""
    L = L or QR
    cl = [c for c in clusters(tiles, w, h) if c[3] >= MIN_FILL * c[2] * c[2]]
    best, best_score, fallback, fb_score = None, math.inf, 1, math.inf
    for i in range(len(cl)):
        for j in range(i + 1, len(cl)):
            for k in range(j + 1, len(cl)):
                score, rej, pts = triple(cl[i][:3], cl[j][:3], cl[k][:3], L)
                if rej == 0 and score < best_score:
                    best, best_score = pts, score
                if score < fb_score:
                    fallback, fb_score = rej, score

    non_empty = int((tiles[..., 2] > 0).sum())
    total = sum(tile_count(tiles[ty, tx]) for ty in range(TILE_N) for tx in range(TILE_N)
                if tiles[ty, tx, 2] > 0)
    reject = 0 if best is not None else fallback
    if non_empty > L.max_tiles:
        reject |= 2
    texels = []
    for c in range(3):
        if reject:
            texels.append((0, 0, 0, 0))
            continue
        x = int(np.clip(fl(best[c][0]), 0, 4095))
        y = int(np.clip(fl(best[c][1]), 0, 4095))
        texels.append((x & 255, y & 255, (x >> 8) | ((y >> 8) << 4), 255))
    texels.append((min(non_empty, 255), min(int(total // 16), 255), reject, 255 if reject == 0 else 0))
    return texels


def decode_fit(texels):
    pts = []
    for r, g, b, a in texels[:3]:
        if a == 0:
            pts.append(None)
            continue
        pts.append((float(r | ((b & 15) << 8)), float(g | ((b >> 4) << 8)), a))
    return pts, texels[3]


def quad(pts):
    """q0 coin exterieur TL, q1 TR, q2 BR (estimation affine), q3 BL. pts = TL, TR, BL."""
    corner, e1, e2 = [(p[0], p[1]) for p in pts]
    u = (e1[0] - corner[0], e1[1] - corner[1])
    v = (e2[0] - corner[0], e2[1] - corner[1])
    q0 = (corner[0] - K * (u[0] + v[0]), corner[1] - K * (u[1] + v[1]))
    q1 = (corner[0] + (1 + K) * u[0] - K * v[0], corner[1] + (1 + K) * u[1] - K * v[1])
    q2 = (corner[0] + (1 + K) * (u[0] + v[0]), corner[1] + (1 + K) * (u[1] + v[1]))
    q3 = (corner[0] - K * u[0] + (1 + K) * v[0], corner[1] - K * u[1] + (1 + K) * v[1])
    return q0, q1, q2, q3


def detect(img_gl, L=None):
    """img_gl : image en convention GL (ligne 0 = BAS), comme texelFetch la voit."""
    h, w = img_gl.shape[:2]
    cls = classify(img_gl)
    r, g, b = seed(cls)
    tiles = tile(r, b)
    texels = fit(tiles, w, h, L)
    return cls, (r, g, b), tiles, texels


# --- homographie : perspective mesuree sur les reperes, puis motifs d'alignement ------------
#
# Avec 3 points, la grille ne peut etre qu'AFFINE : exacte de face, fausse des 1 degre
# d'inclinaison (63 a 205 bits faux sur 3249). Or l'ecran est un plan et la camera un stenope
# sans distorsion : l'image du QR est EXACTEMENT une homographie. Ecrite pour fixer les trois
# reperes, il ne lui reste que 2 parametres, la perspective (g, h) :
#     P(x, y) = (T + x U + y V) / (1 + g x + h y),   U = (1 + g) TR - T,   V = (1 + h) BL - T
# avec x, y = (colonne, ligne - 3,5) / 50 : TL en (0, 0), TR en (1, 0), BL en (0, 1).
#
# persp : le long de la droite TL -> TR (l'image de la ligne 3,5 du QR, une droite reste une
#   droite), les reperes TL et TR mesurent chacun 7 modules, et pour cette homographie le
#   rapport de leurs longueurs vaut EXACTEMENT (1 + g)^2 ; idem TL -> BL pour h. Plages
#   1:1:3:1:1 le long de la droite en pas entiers (DDA) : numpy et GLSL au bit pres. Cette H0
#   predit les 6 motifs d'alignement a moins de 0,75 module a 45 degres, la ou l'affine se
#   trompait de 20 a 33 modules.
# align : chaque motif cherche a +-ALIGN_R modules de sa prediction, gabarit 5x5 projete par H0
#   -- juste en echelle ET en angle, quand le gabarit affine avait 1,5x et 21 degres d'ecart
#   au motif (28,6) a 45 degres et perdait contre les donnees d'un QR noir et blanc.
# pick : centre = barycentre de la grappe de score maximal la plus proche de la prediction.
# (g, h) final : moindres carres sur les motifs trouves ; H0 sans motif ; l'affine sans H0.

QR_N = 57             # modules par cote d'un v10 ; reperes centres en 3,5 et 53,5
QR_ALIGN = ((28, 6), (6, 28), (28, 28), (50, 28), (28, 50), (50, 50))   # (colonne, ligne)
ALIGN_R = 2.0         # fenetre : +-2 modules autour de la prediction de H0
ALIGN_K = 16          # demi-grille : 33 x 33 positions par motif, pas entier >= 1 px
ALIGN_W = 2 * ALIGN_K + 1
ALIGN_SMIN = 23       # gabarit : au moins 23 modules sur 25 dans le bon etat
FIT_COND = 1e-3       # (g, h) mal conditionne sous ce sin^2 : (28,28) SEUL est sur la diagonale
                      # TR-BL, ses deux equations n'en font qu'une
FINDER_SMIN = 45      # affinage d'un repere : gabarit 7x7, au moins 45 modules sur 49


class Layout:
    """Geometrie de ce qu'on detecte. QR (ci-dessous) : un QR v10, effets qr_*. Le mur de donnees
    (WALL de gen_dct.py) : reperes a 439 et 247 modules, motifs d'alignement dans la bande de
    bord, et AFFINAGE des reperes (finders) : ses symboles font 1 module, la position entiere
    d'un barycentre de candidats (jusqu'a 0,3 module d'erreur) n'y suffit plus.

    targets = motifs cherches par align / pick, (centre, rayon, score mini) : rayon 2 = motif
    d'alignement 5x5, rayon 3 = repere 7x7. Les n_align premiers servent a (g, h) ; les 3
    suivants, s'il y en a, remplacent TL, TR, BL du fit dans l'homographie finale."""

    def __init__(self, span_u, span_v, f0, aligns, max_tiles, finders=(), tl_by_score=False):
        self.span_u, self.span_v, self.f0, self.max_tiles = span_u, span_v, f0, max_tiles
        self.tl_by_score = tl_by_score
        self.n_align = len(aligns)
        self.targets = [(a, 2, ALIGN_SMIN) for a in aligns] + [(f, 3, FINDER_SMIN) for f in finders]
        self.refine = bool(finders)


QR = Layout(SPAN, SPAN, (3.5, 3.5), tuple((c + 0.5, r + 0.5) for c, r in QR_ALIGN), MAX_TILES)


def cls_at(cls, x, y):
    h, w = cls.shape
    return int(cls[y, x]) if 0 <= x < w and 0 <= y < h else 0


def dda(a, b, k):
    """k-ieme pixel de la droite a -> b (pas = 1 px sur l'axe majeur), arrondi entier exact."""
    dx, dy = b[0] - a[0], b[1] - a[1]
    m = max(abs(dx), abs(dy))
    return a[0] + (2 * k * dx + m) // (2 * m), a[1] + (2 * k * dy + m) // (2 * m)


def finder_span(cls, a, b):
    """Plages 1:1:3:1:1 du repere centre en a, le long de a -> b : 7 modules en pas, 0 = rate."""
    if cls_at(cls, *a) != 1:
        return 0
    r = []
    for sgn in (-1, 1):
        k = sgn
        for state in (1, 2, 1):
            n = 0
            while n < RUN_CAP + 1 and cls_at(cls, *dda(a, b, k)) == state:
                n += 1
                k += sgn
            if n > RUN_CAP:
                return 0
            r.append(n)
    c1, l1, d1, c2, l2, d2 = r
    core = c1 + c2 + 1
    tot = core + l1 + l2 + d1 + d2
    for v in (l1, l2, d1, d2):
        if 2 * abs(7 * v - tot) > RING_TOL2 * tot:
            return 0
    if 2 * abs(7 * core - 3 * tot) > CORE_TOL2_DIAG * tot:
        return 0
    return tot


def persp(cls, fit_texels):
    """-> les 2 texels de la cible persp : longueurs (TL->TR, TR->TL), (TL->BL, BL->TL)."""
    pts, status = decode_fit(fit_texels)
    if status[3] != 255:
        return [(0, 0, 0, 0)] * 2
    tl, tr, bl = [(int(p[0]), int(p[1])) for p in pts]
    s = (finder_span(cls, tl, tr), finder_span(cls, tr, tl),
         finder_span(cls, tl, bl), finder_span(cls, bl, tl))
    return [(s[0] & 255, s[0] >> 8, s[1] & 255, s[1] >> 8),
            (s[2] & 255, s[2] >> 8, s[3] & 255, s[3] >> 8)]


def gh_persp(persp_texels):
    (a, b, c, d), (e, f, g, h) = [tuple(int(v) for v in t) for t in persp_texels]
    s = (a | b << 8, c | d << 8, e | f << 8, g | h << 8)
    if min(s) == 0:
        return None
    return math.sqrt(s[0] / s[1]) - 1.0, math.sqrt(s[2] / s[3]) - 1.0


def hmap(F, gh, gx, gy, L=None):
    """Grille (colonne, ligne continues ; L.f0 = centre du repere TL, 3,5 sur un QR) -> ecran.
    Scalaires ou tableaux numpy."""
    L = L or QR
    (tx, ty), (rx, ry), (bx, by) = F
    g, h = gh
    qx, qy = (gx - L.f0[0]) / L.span_u, (gy - L.f0[1]) / L.span_v
    ux, uy = (1.0 + g) * rx - tx, (1.0 + g) * ry - ty
    vx, vy = (1.0 + h) * bx - tx, (1.0 + h) * by - ty
    w = 1.0 + g * qx + h * qy
    return (tx + qx * ux + qy * vx) / w, (ty + qx * uy + qy * vy) / w


def fit_gh(F, pts, L=None):
    """(g, h) aux moindres carres. Un motif A en (x, y) donne g x (A - TR) + h y (A - BL) =
    P_affine - A, deux equations. None si mal conditionne (aucun motif, ou (28,28) seul)."""
    L = L or QR
    (tx, ty), (rx, ry), (bx, by) = F
    m00 = m01 = m11 = r0 = r1 = 0.0
    for k, (ax, ay) in pts.items():
        if k >= L.n_align:                                 # reperes affines : pas des equations
            continue
        cx, cy = L.targets[k][0]
        qx, qy = (cx - L.f0[0]) / L.span_u, (cy - L.f0[1]) / L.span_v
        px = tx + qx * (rx - tx) + qy * (bx - tx)
        py = ty + qx * (ry - ty) + qy * (by - ty)
        a0x, a0y = qx * (ax - rx), qx * (ay - ry)
        a1x, a1y = qy * (ax - bx), qy * (ay - by)
        m00 += a0x * a0x + a0y * a0y
        m01 += a0x * a1x + a0y * a1y
        m11 += a1x * a1x + a1y * a1y
        r0 += a0x * (px - ax) + a0y * (py - ay)
        r1 += a1x * (px - ax) + a1y * (py - ay)
    det = m00 * m11 - m01 * m01
    if det <= FIT_COND * m00 * m11 or det <= 0.0:
        return None
    return (m11 * r0 - m01 * r1) / det, (m00 * r1 - m01 * r0) / det


def margin(x):
    """Distance de floor(x + 0,5) a son point de bascule : un float32 GPU peut trancher
    autrement en dessous de ~1e-3."""
    return abs((x + 0.5) - math.floor(x + 1.0))


def margin_arr(x):
    with np.errstate(invalid="ignore"):
        return np.abs((x + 0.5) - np.floor(x + 1.0))


def align_window(F, gh, k, L=None):
    """Motif k : -> (centre predit arrondi, pas en px, gabarit [j][i] = decalage entier du
    centre de chaque module (2r+1)x(2r+1), module local en px, marge d'arrondi la plus faible)."""
    L = L or QR
    (gx, gy), rad, _ = L.targets[k]
    p0 = hmap(F, gh, gx, gy, L)
    marg = min(margin(p0[0]), margin(p0[1]))
    d = []
    for j in range(-rad, rad + 1):
        row = []
        for i in range(-rad, rad + 1):
            q = hmap(F, gh, gx + i, gy + j, L)
            row.append((math.floor(q[0] - p0[0] + 0.5), math.floor(q[1] - p0[1] + 0.5)))
            marg = min(marg, margin(q[0] - p0[0]), margin(q[1] - p0[1]))
        d.append(row)
    a, b = hmap(F, gh, gx + 0.5, gy, L), hmap(F, gh, gx - 0.5, gy, L)
    c, e = hmap(F, gh, gx, gy + 0.5, L), hmap(F, gh, gx, gy - 0.5, L)
    mloc = max(math.hypot(a[0] - b[0], a[1] - b[1]), math.hypot(c[0] - e[0], c[1] - e[1]))
    x = ALIGN_R * mloc / ALIGN_K
    marg = min(marg, abs(x - round(x)))                            # ceil bascule aux entiers
    step = max(1, math.ceil(x))
    return (math.floor(p0[0] + 0.5), math.floor(p0[1] + 0.5)), step, d, mloc, marg


def align_scores(cls, fit_texels, persp_texels, L=None):
    """-> int [ALIGN_W, nt * ALIGN_W, 4] : les octets de la cible ascore. Texel (i, j) du bloc
    k = position c0 + (i - K, j - K) * pas, en 12 + 12 bits comme fit ; A = score (25 sur un
    motif d'alignement, 49 sur un repere : anneau r-1 clair, le reste sombre)."""
    L = L or QR
    nt = len(L.targets)
    out = np.zeros((ALIGN_W, nt * ALIGN_W, 4), np.int64)
    pts, status = decode_fit(fit_texels)
    if status[3] != 255:
        return out
    F = [(p[0], p[1]) for p in pts]
    gh0 = gh_persp(persp_texels) or (0.0, 0.0)
    h, w = cls.shape
    n = np.arange(-ALIGN_K, ALIGN_K + 1)
    for k in range(nt):
        rad = L.targets[k][1]
        c0, step, d, _, _ = align_window(F, gh0, k, L)
        cx = np.broadcast_to(c0[0] + n[None, :] * step, (ALIGN_W, ALIGN_W))
        cy = np.broadcast_to(c0[1] + n[:, None] * step, (ALIGN_W, ALIGN_W))
        s = np.zeros((ALIGN_W, ALIGN_W), np.int64)
        for j in range(2 * rad + 1):
            for i in range(2 * rad + 1):
                x, y = cx + d[j][i][0], cy + d[j][i][1]
                ok = (x >= 0) & (y >= 0) & (x < w) & (y < h)
                c = np.zeros(x.shape, np.int64)
                c[ok] = cls[y[ok], x[ok]]
                s += c == (2 if max(abs(i - rad), abs(j - rad)) == rad - 1 else 1)
        s[(cx < 0) | (cy < 0) | (cx >= w) | (cy >= h)] = 0
        px, py = np.clip(cx, 0, 4095), np.clip(cy, 0, 4095)
        blk = out[:, k * ALIGN_W:(k + 1) * ALIGN_W]
        blk[..., 0], blk[..., 1] = px & 255, py & 255
        blk[..., 2], blk[..., 3] = (px >> 8) | ((py >> 8) << 4), s
    return out


def align_pick(scores, fit_texels, persp_texels, L=None):
    """-> les texels de la cible align (un par motif de L.targets) : centre en 1/16 px (R | G << 8, B | A << 8), 0 =
    motif non trouve. Score max ; parmi ses positions la plus proche de la prediction (la
    premiere dans l'ordre de parcours a egalite, comme le GLSL) ; barycentre des positions au
    max a moins d'un module de celle-la -- deux grappes au max ne se moyennent pas."""
    L = L or QR
    out = [(0, 0, 0, 0)] * len(L.targets)
    pts, status = decode_fit(fit_texels)
    if status[3] != 255:
        return out
    F = [(p[0], p[1]) for p in pts]
    gh0 = gh_persp(persp_texels) or (0.0, 0.0)
    jj, ii = np.mgrid[0:ALIGN_W, 0:ALIGN_W]
    for k in range(len(L.targets)):
        _, step, _, mloc, _ = align_window(F, gh0, k, L)
        blk = scores[:, k * ALIGN_W:(k + 1) * ALIGN_W]
        s = blk[..., 3]
        S = int(s.max())
        if S < L.targets[k][2]:
            continue
        dd = (ii - ALIGN_K) ** 2 + (jj - ALIGN_K) ** 2
        i0 = int(np.argmin(np.where(s == S, dd, 1 << 40)))           # ordre ligne par ligne
        bi, bj = i0 % ALIGN_W, i0 // ALIGN_W
        sel = (s == S) & (step * step * ((ii - bi) ** 2 + (jj - bj) ** 2) <= mloc * mloc)
        x = blk[..., 0] | ((blk[..., 2] & 15) << 8)
        y = blk[..., 1] | ((blk[..., 2] >> 4) << 8)
        n, sx, sy = int(sel.sum()), int(x[sel].sum()), int(y[sel].sum())
        x16, y16 = (32 * sx + n) // (2 * n), (32 * sy + n) // (2 * n)
        out[k] = (x16 & 255, x16 >> 8, y16 & 255, y16 >> 8)
    return out


def decode_align(texels):
    out = {}
    for k, t in enumerate(texels):
        x16, y16 = int(t[0]) | int(t[1]) << 8, int(t[2]) | int(t[3]) << 8
        if x16 or y16:
            out[k] = (x16 / 16.0, y16 / 16.0)
    return out


def homography(fit_texels, align_texels, persp_texels, L=None):
    """-> (F, (g, h)) : moindres carres sur les motifs trouves, sinon H0, sinon l'affine. Si
    la geometrie affine ses reperes, TL / TR / BL trouves remplacent ceux du fit."""
    L = L or QR
    pts, _ = decode_fit(fit_texels)
    F = [(p[0], p[1]) for p in pts]
    found = decode_align(align_texels)
    for i in range(3):
        if L.n_align + i in found:
            F[i] = found[L.n_align + i]
    gh = fit_gh(F, found, L)
    return F, gh or gh_persp(persp_texels) or (0.0, 0.0)


def module_pos(fit_texels, align_texels, persp_texels):
    """-> (x, y) float [57, 57] : centre de chaque module a l'ecran (ligne, colonne)."""
    F, gh = homography(fit_texels, align_texels, persp_texels)
    j, i = np.mgrid[0:QR_N, 0:QR_N]
    with np.errstate(all="ignore"):
        return hmap(F, gh, i + 0.5, j + 0.5)


def to_pixel(x, y, w, h):
    """floor(p + 0,5), teste en flottant AVANT la conversion comme le GLSL (NaN = dehors)."""
    qx, qy = np.floor(np.asarray(x) + 0.5), np.floor(np.asarray(y) + 0.5)
    with np.errstate(invalid="ignore"):
        ok = (qx >= 0) & (qy >= 0) & (qx < w) & (qy < h)
    return ok, np.where(ok, qx, 0).astype(np.int64), np.where(ok, qy, 0).astype(np.int64)


def sample_bits(cls, fit_texels, align_texels, persp_texels):
    """-> int [57, 57] (ligne, colonne ; ligne 0 = celle du repere TL) : etat du pixel au
    CENTRE de chaque module par l'homographie, 0/1/2 comme classify ; tout a 0 sans quad."""
    out = np.zeros((QR_N, QR_N), np.int64)
    if decode_fit(fit_texels)[1][3] != 255:
        return out
    h, w = cls.shape
    ok, px, py = to_pixel(*module_pos(fit_texels, align_texels, persp_texels), w, h)
    out[ok] = cls[py[ok], px[ok]]
    return out


def align_all(cls, fit_texels, L=None):
    """Les 3 passes de l'homographie d'un coup : -> (persp, ascore, align)."""
    pt = persp(cls, fit_texels)
    sc = align_scores(cls, fit_texels, pt, L)
    return pt, sc, align_pick(sc, fit_texels, pt, L)


# --- rendu de scene (simulation du client) --------------------------------------------------

def load_palette():
    import json
    cl = json.load(open(os.path.join(ROOT, "..", "..", "src", "main", "resources",
                                     "vanilla_map_colors.json"), encoding="utf-8"))["colorList"]
    cols = []
    for k, h in cl.items():
        r, g, b = (int(h.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4))
        for s in (180, 220, 255, 135):
            cols.append((r * s // 255, g * s // 255, b * s // 255))
    return np.array(sorted(set(cols)), dtype=np.int64)


def quantize(img, pal):
    """Ce que mcmm fait de la photo : chaque couleur vers la plus proche de la palette (sRGB,
    meme metrique que sa LUT). Pas de tramage : /vmc render normal."""
    flat = img.reshape(-1, 3).astype(np.int64)
    uniq, inv = np.unique(flat, axis=0, return_inverse=True)
    d = ((uniq[:, None, :] - pal[None, :, :]) ** 2).sum(-1)
    return pal[d.argmin(1)][inv.ravel()].reshape(img.shape).astype(np.uint8)


def background(kind, w, h, rng):
    if kind == "blocky":                         # monde : blocs de 16 px, teintes moyennes
        tones = np.array([(95, 140, 60), (120, 120, 120), (150, 110, 70), (110, 80, 50),
                          (200, 200, 200), (30, 30, 35), (235, 235, 240), (70, 90, 140)])
        idx = rng.integers(0, len(tones), (h // 16 + 1, w // 16 + 1))
        return np.kron(tones[idx].reshape(idx.shape[0], idx.shape[1], 3).transpose(2, 0, 1),
                       np.ones((16, 16))).transpose(1, 2, 0)[:h, :w].astype(np.uint8)
    if kind == "noise":
        return rng.integers(0, 256, (h, w, 3)).astype(np.uint8)
    if kind == "fine":
        g = rng.integers(0, 256, (h // 4 + 1, w // 4 + 1))
        return np.repeat(np.kron(g, np.ones((4, 4)))[:h, :w, None], 3, 2).astype(np.uint8)
    if kind == "hardbw":                          # pire cas : damier noir/blanc de 4 px
        g = rng.integers(0, 2, (h // 4 + 1, w // 4 + 1)) * 255
        return np.repeat(np.kron(g, np.ones((4, 4)))[:h, :w, None], 3, 2).astype(np.uint8)
    if kind == "concrete":                        # beton noir / beton blanc, blocs de 16
        g = rng.integers(0, 2, (h // 16 + 1, w // 16 + 1))
        g = np.where(g == 1, 207, 8)
        return np.repeat(np.kron(g, np.ones((16, 16)))[:h, :w, None], 3, 2).astype(np.uint8)
    raise ValueError(kind)


def camera(w, h, card_w, card_h, card_module, m_px, roll, tilt):
    """Pinhole, FOV VERTICAL de FOV_V. Le plan de l'ecran est incline (tilt, autour de son axe
    horizontal) puis tourne (roll, autour de l'axe optique), a une distance qui donne m_px
    pixels par module au centre, le long de l'axe non incline. La premiere version prenait les
    70 degres en HORIZONTAL : focale 1371 px au lieu de 771 en 1080p, donc une perspective
    1,8x trop douce sur toutes les scenes inclinees."""
    f = (h / 2) / math.tan(math.radians(FOV_V / 2))
    dist = f * card_module / m_px
    t, r = math.radians(tilt), math.radians(roll)
    rx = np.array([[1, 0, 0], [0, math.cos(t), -math.sin(t)], [0, math.sin(t), math.cos(t)]])
    rz = np.array([[math.cos(r), -math.sin(r), 0], [math.sin(r), math.cos(r), 0], [0, 0, 1]])
    rot = rz @ rx
    a, b, n = rot[:, 0], rot[:, 1], rot[:, 2]
    p0 = np.array([0.0, 0.0, dist])

    def to_screen(cx, cy):                        # coords carte (px, continues) -> ecran
        X = p0 + (cx - card_w / 2) * a + (cy - card_h / 2) * b
        return f * X[0] / X[2] + w / 2, f * X[1] / X[2] + h / 2

    def from_screen(sx, sy):                      # rayon -> plan -> coords carte
        d = np.stack([sx - w / 2, sy - h / 2, np.full_like(sx, f)], -1)
        tt = (p0 @ n) / (d @ n)
        X = d * tt[..., None] - p0
        return X @ a + card_w / 2, X @ b + card_h / 2

    return to_screen, from_screen


def render(card, qxy, module, w, h, m_px, roll, tilt, bg_kind, light, seed_=0):
    """-> (image GL [H, W, 3], verite terrain en coords GL index). Echantillonnage NEAREST :
    c'est le sampler des maps (DynamicTexture, verifie dans le jar 26.3) -- ni mipmap ni
    moyenne, les modules ressortent purs mais de largeur irreguliere a petite echelle."""
    from gen_qr_card import FINDER_CENTERS, N
    rng = np.random.default_rng(seed_)
    img = background(bg_kind, w, h, rng) if bg_kind else np.full((h, w, 3), 128, np.uint8)
    if card is None:                              # scene sans QR : rien a projeter
        return img[::-1].copy(), {"module": [math.inf]}
    ch, cw = card.shape[:2]
    to_s, from_s = camera(w, h, cw, ch, module, m_px, roll, tilt)
    if True:
        sx, sy = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
        cx, cy = from_s(sx, sy)
        inside = (cx >= 0) & (cx < cw) & (cy >= 0) & (cy < ch)
        ix = np.clip(np.floor(cx).astype(int), 0, cw - 1)
        iy = np.clip(np.floor(cy).astype(int), 0, ch - 1)
        lit = np.floor(card[iy, ix].astype(np.float64) * light).astype(np.uint8)
        img = np.where(inside[..., None], lit, img)

    def gl(pt):                                   # continu haut->bas -> index GL bas->haut
        return pt[0] - 0.5, h - 0.5 - pt[1]

    qx, qy = qxy
    truth = {"finders": [gl(to_s(qx + fx * module, qy + fy * module)) for fx, fy in FINDER_CENTERS],
             "corners": [gl(to_s(qx + cx_ * module, qy + cy_ * module))
                         for cx_, cy_ in ((0, 0), (N, 0), (N, N), (0, N))]}
    # module local : moyenne geometrique des pas projetes en x et en y
    def mod_at(fx, fy):
        dx = np.array(to_s(qx + (fx + 0.5) * module, qy + fy * module)) - np.array(to_s(qx + (fx - 0.5) * module, qy + fy * module))
        dy = np.array(to_s(qx + fx * module, qy + (fy + 0.5) * module)) - np.array(to_s(qx + fx * module, qy + (fy - 0.5) * module))
        return math.sqrt(np.linalg.norm(dx) * np.linalg.norm(dy))
    truth["module"] = [mod_at(fx, fy) for fx, fy in FINDER_CENTERS]
    # le coin oppose a TL, souvent le plus petit en perspective, borne ce qu'on peut LIRE
    truth["module_far"] = mod_at(N - 6.5, N - 6.5)
    truth["align"] = [gl(to_s(qx + (c + 0.5) * module, qy + (r + 0.5) * module)) for c, r in QR_ALIGN]
    truth["align_module"] = [mod_at(c + 0.5, r + 0.5) for c, r in QR_ALIGN]
    return img[::-1].copy(), truth


def overlay(img_gl, cand_r, texels):
    """Apercu de ce que dessinera la passe draw (sans le HUD), image remise haut->bas."""
    out = img_gl.copy()
    out[cand_r > 0] = (255, 0, 255)
    pts, status = decode_fit(texels)
    h, w = cand_r.shape
    yy, xx = np.mgrid[0:h, 0:w]
    for p, col in zip(pts, ((255, 40, 40), (40, 255, 40), (60, 120, 255))):
        if p is None:
            continue
        L = 12
        hit = ((np.abs(xx - p[0]) <= 1) & (np.abs(yy - p[1]) <= L)) | ((np.abs(yy - p[1]) <= 1) & (np.abs(xx - p[0]) <= L))
        out[hit] = col
    if status[3] == 255:
        q = quad(pts)
        for (a, b), dashed in (((q[0], q[1]), False), ((q[0], q[3]), False),
                               ((q[1], q[2]), True), ((q[3], q[2]), True)):
            ax, ay = a
            bx, by = b
            px, py = xx - ax, yy - ay
            ex, ey = bx - ax, by - ay
            tt = np.clip((px * ex + py * ey) / max(ex * ex + ey * ey, 1e-6), 0, 1)
            d = np.hypot(px - ex * tt, py - ey * tt)
            on = d <= 1.5
            if dashed:
                on &= (np.floor(tt * math.hypot(ex, ey) / 8) % 2) == 0
            out[on] = (255, 230, 0)
    return out[::-1]


# --- demo : le garde-fou ---------------------------------------------------------------------

def demo(previews=None, quick=False):
    from gen_qr_card import qr_matrix, render_card, reserved_modules, LOGO_MODULES
    pal = load_palette()
    module = 5
    plain, qxy = render_card(qr_matrix(0), module, 512, 384)
    plain = quantize(plain, pal)
    logo_card, _ = render_card(qr_matrix(0), module, 512, 384,
                               os.path.join(ROOT, "..", "..", "logo.png"))
    logo_card = quantize(logo_card, pal)
    # mire couleur : les modules de donnees aux 244 couleurs de palette, deja exactes
    color_dark, color_cols = qr_matrix(0, True)
    color_card, _ = render_card(color_dark, module, 512, 384, colors=color_cols)
    data_mask = ~reserved_modules()
    night = 0.93                                   # glow frame, nuit (block 13 / sky 15)

    def run(card, w, h, m_px, roll, tilt, bg, label, save=False, seed_=0, qmat=None,
            logo=False, colors=False):
        img, truth = render(card, qxy, module, w, h, m_px, roll, tilt, bg, night, seed_)
        cls, (r, g, b), tiles, texels = detect(img)
        pts, status = decode_fit(texels)
        res = {"label": label, "valid": status[3] == 255, "reject": status[2],
               "tiles": status[0], "cands": int((r > 0).sum()), "mmin": min(truth["module"])}
        if card is not None:                       # QR + 1 module de zone calme dans l'ecran ?
            mx = max(truth["module"])
            res["fits"] = all(mx <= x <= w - 1 - mx and mx <= y <= h - 1 - mx
                              for x, y in truth["corners"])
            res["mfar"] = truth["module_far"]
        if card is not None and res["valid"]:
            errs = []
            # ORDONNE : TL, TR, BL doivent tomber chacun sur le sien (le decodage en dependra)
            for (tx, ty), ml, p in zip(truth["finders"], truth["module"], pts):
                errs.append(math.dist((tx, ty), (p[0], p[1])) / ml)
            res["err"] = max(errs)
            q = quad([p for p in pts])
            diag = math.dist(truth["corners"][0], truth["corners"][2])
            res["q2"] = min(math.dist(q[2], c) for c in truth["corners"]) / diag
            pt, _, al = align_all(cls, texels)
            found = decode_align(al)
            res["nalign"] = len(found)
            res["aerr"] = max([math.dist(found[k], truth["align"][k]) / truth["align_module"][k]
                               for k in found] or [0.0])
            res["h0"] = gh_persp(pt) is not None
            keep = np.ones((QR_N, QR_N), bool)
            if logo:                               # le carre du logo n'a pas de modules
                cleared = LOGO_MODULES + 2
                c0 = (QR_N - cleared) // 2
                keep[c0:c0 + cleared, c0:c0 + cleared] = False
            if colors:                             # couleur lue au centre de chaque module
                lit = np.floor(card.astype(np.float64) * night).astype(np.uint8)
                jj, ii = np.mgrid[0:QR_N, 0:QR_N]
                want = lit[qxy[1] + jj * module + module // 2, qxy[0] + ii * module + module // 2]
                ok, px, py = to_pixel(*module_pos(texels, al, pt), w, h)
                got = np.zeros_like(want)
                got[ok] = img[py[ok], px[ok]]
                res["bad"] = int(((got != want).any(-1) & data_mask).sum())
                res["unk"] = int((~ok & data_mask).sum())
            else:                                  # bits : 1 = sombre, 2 = clair
                m = qmat if qmat is not None else qr_matrix(0)
                bits = sample_bits(cls, texels, al, pt)
                res["bad"] = int(((bits != np.where(m, 1, 2)) & keep).sum())
                res["unk"] = int(((bits == 0) & keep).sum())
        if save and previews:
            from PIL import Image
            Image.fromarray(overlay(img, r, texels)).save(os.path.join(previews, label + ".png"))
        return res

    def check(res, must_detect):
        """Detection exigee des 3 px/module aux reperes (QR dans l'ecran) ; LECTURE exigee --
        0 bit, 0 couleur faux -- si le coin lointain fait aussi >= 3 px. En dessous : rapporte."""
        detectable = res["mmin"] >= 3.0 and res["fits"]
        readable = detectable and res["mfar"] >= 3.0
        if (must_detect or detectable) and not (res["valid"] and res.get("err", 99) < 1.0):
            failures.append(res)
        elif readable and res["bad"] != 0:
            failures.append(res)

    failures = []
    rows = []
    # axis_test (vectorise, pour la vitesse) doit etre EXACTEMENT la marche du GLSL
    img, _ = render(plain, qxy, module, 640, 360, 4, 20, 30, "blocky", night)
    cls = classify(img)
    okh, th = axis_test(cls)
    rng = np.random.default_rng(1)
    ys, xs = np.nonzero(cls == 1)
    pick = rng.choice(len(xs), 3000, replace=False)
    pts = list(zip(ys[pick], xs[pick])) + list(zip(*np.nonzero(okh)))
    for y, x in pts:
        ok, tot = line_test(cls, x, y, 1, 0, True, CORE_TOL2)
        assert ok == okh[y, x], (x, y, ok, okh[y, x])
    print(f"  axis_test == line_test sur {len(pts)} pixels ({int(okh.sum())} candidats h)")
    # 16 px/module = le plus gros QR qui tient en 1080p (65 modules avec la zone calme) ;
    # plus pres, il deborde de l'ecran et ne PEUT pas etre vu -- scene rapportee, pas exigee
    # 1 degre suffisait a fausser la grille affine : les petites inclinaisons comptent autant
    sizes = (3, 4, 5.5, 8, 12, 16) if not quick else (4, 8, 12)
    for m_px in sizes:
        for roll in (0, 30, 60):
            for tilt in (0, 1, 4, 15, 30, 45) if not quick else (0, 4, 30, 45):
                label = f"m{m_px}_r{roll}_t{tilt}"
                res = run(plain, 1920, 1080, m_px, roll, tilt, "blocky", label,
                          save=(roll, tilt) in ((0, 0), (30, 45)))
                rows.append(res)
                check(res, False)
    # d'autres QR (donnees aleatoires differentes) : la graine 0 ne doit pas etre un coup de
    # chance -- c'est elle qui avait cache les faux 1:1:2:1:1
    for qs in (1, 2, 3) if not quick else (1,):
        card, _ = render_card(qr_matrix(qs), module, 512, 384)
        card = quantize(card, pal)
        for m_px, roll, tilt in ((3, 15, 40), (5, 45, 20), (8, 70, 45), (11, 10, 30)):
            res = run(card, 1920, 1080, m_px, roll, tilt, "blocky",
                      f"qr{qs}_m{m_px}_r{roll}_t{tilt}", seed_=qs, qmat=qr_matrix(qs))
            rows.append(res)
            check(res, True)
    # mire couleur (gen_qr_card.py --color-test) : la vue a plat de qr_color doit restituer
    # chaque couleur de donnees telle quelle
    for m_px in (4, 8, 12) if not quick else (8,):
        for roll in (0, 30):
            for tilt in (0, 15, 30, 45):
                res = run(color_card, 1920, 1080, m_px, roll, tilt, "blocky",
                          f"couleur_m{m_px}_r{roll}_t{tilt}", colors=True)
                rows.append(res)
                check(res, False)
    # sans QR, plusieurs graines. hardbw = bruit noir/blanc plein ecran au grain d'un module,
    # autrement dit des donnees de QR partout : 3 graines sur 8 y formaient un faux triangle
    # tant que des grappes d'1 ou 2 pixels comptaient comme reperes ; MIN_FILL les ecarte, 0
    # sur 8 depuis, donc exige comme le reste.
    for bs in (0, 1, 2, 3) if not quick else (0,):
        for bg in ("blocky", "noise", "fine", "hardbw", "concrete"):
            res = run(None, 1920, 1080, 8, 0, 0, bg, f"vide_{bg}_{bs}",
                      save=bg == "concrete" and bs == 0, seed_=bs)
            rows.append(res)
            if res["valid"]:
                failures.append(res)
    for (w, h) in ((2560, 1440), (3440, 1440)):
        res = run(plain, w, h, 10, 20, 30, "blocky", f"ecran_{w}x{h}")
        rows.append(res)
        check(res, True)
    # le logo cache le motif (28,28) : l'homographie se passe de lui
    for tilt in (0, 30, 45):
        res = run(logo_card, 1920, 1080, 8, 0, tilt, "blocky", f"logo_m8_t{tilt}",
                  save=tilt == 30, logo=True)
        rows.append(res)
        check(res, True)

    for r_ in rows:
        extra = (f" err {r_['err']:.2f} mod  q2 {100 * r_['q2']:4.1f} %  align {r_['nalign']}/6"
                 f" {'' if r_['h0'] else '(sans H0) '}a {r_['aerr']:.2f}"
                 f"  faux {r_['bad']:4} (? {r_['unk']})") if "err" in r_ else ""
        tag = "" if r_.get("fits", True) else "  (deborde)"
        far = f"/{r_['mfar']:4.1f}" if "mfar" in r_ else "     "
        print(f"  {r_['label']:22} m_min {r_['mmin']:5.1f}{far}  {'OK ' if r_['valid'] else 'rej'}"
              f" (motif {r_['reject']:2})  tuiles {r_['tiles']:3}  cand {r_['cands']:6}{extra}{tag}")
    print(f"\n{len(rows)} scenes, {len(failures)} echec(s) a assurer")
    for f_ in failures:
        print("  ECHEC", f_["label"], f_)
    return not failures


# --- GLSL : transcription des fonctions ci-dessus ------------------------------------------

POST = os.path.join(ROOT, "resourcepack", "assets", "minecraft", "shaders", "post")
EFF = os.path.join(ROOT, "resourcepack", "assets", "minecraft", "post_effect")
HUD_SC = 4            # police 3x5 du HUD agrandie 4x
FLAT_CELL = 8         # qr_color : un module = une case de 8 px...
FLAT_X, FLAT_Y = 8, 36   # ...panneau de 456 px a 8 px du bord gauche, sous le HUD


def glsl_classify(sampler):
    return f"""
const vec3 LUMA = vec3({LUMA[0]}, {LUMA[1]}, {LUMA[2]});
const float DARK_T = {DARK_T};
const float LIGHT_T = {LIGHT_T};

// 0 = ni sombre ni clair, 1 = sombre, 2 = clair. Hors ecran = 0 : texelFetch hors texture
// est indefini, et une plage doit s'arreter au bord comme dans la simulation.
int cls(ivec2 p, ivec2 size) {{
    if (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, size))) return 0;
    float y = dot(texelFetch({sampler}, p, 0).rgb, LUMA);
    return y < DARK_T ? 1 : (y > LIGHT_T ? 2 : 0);
}}
"""


def glsl_head():
    from gen_color_id import EXT
    return EXT + "\n// GENERE par gen_qr_detect.py -- ne pas editer a la main.\n"


# lecture de la cible fit ; suppose FitSampler declare
FITPTS = """
ivec4 fitAt(int i) {
    return ivec4(round(texelFetch(FitSampler, ivec2(i, 0), 0) * 255.0));
}

vec2 point(int i) {
    ivec4 t = fitAt(i);
    return vec2(float(t.r | ((t.b & 15) << 8)), float(t.g | ((t.b >> 4) << 8)));
}
"""


def hud_glsl():
    """HUD commun a qr_detect, qr_bits, qr_color et vmc_data ; suppose FITPTS deja inclus."""
    from gen_color_id import FONT
    hud_y = 5 * HUD_SC
    return f"""const int SC = {HUD_SC};
const int FONT[10] = int[10]({", ".join(str(f) for f in FONT)});

// nombre a 3 chiffres (zeros compris) dont le coin bas-gauche est en o
bool digits3(ivec2 p, ivec2 o, int value) {{
    ivec2 q = p - o;
    if (q.x < 0 || q.y < 0 || q.x >= 12 * SC || q.y >= 5 * SC) return false;
    int fx = q.x / SC, fy = q.y / SC;
    int di = fx / 4, cx = fx - di * 4;
    if (cx >= 3) return false;
    int power = di == 0 ? 100 : (di == 1 ? 10 : 1);
    int glyph = FONT[(value / power) % 10];
    // gl_FragCoord.y monte, la police descend : on retourne la ligne
    return ((glyph >> (14 - ((4 - fy) * 3 + cx))) & 1) == 1;
}}

// HUD en haut a gauche : fond sombre, pastille, motif de rejet / tuiles / candidats / 16
vec3 hud(ivec2 p, ivec2 size, ivec4 st, vec3 col) {{
    ivec2 o = ivec2(8, size.y - 8 - {hud_y});
    ivec2 q = p - o;
    if (q.x >= -4 && q.y >= -4 && q.x < 24 + 3 * 13 * SC && q.y < {hud_y} + 4) {{
        col = col * 0.25;
        if (q.x >= 0 && q.y >= 0 && q.x < {hud_y} && q.y < {hud_y}) {{
            col = st.a == 255 ? vec3(0.1, 0.9, 0.2) : vec3(0.9, 0.15, 0.1);
        }}
        if (digits3(p, o + ivec2(24, 0), st.b) || digits3(p, o + ivec2(24 + 13 * SC, 0), st.r)
                || digits3(p, o + ivec2(24 + 26 * SC, 0), st.g)) {{
            col = vec3(1.0);
        }}
    }}
    return col;
}}
"""


def fit_glsl(L=None):
    L = L or QR
    if L.tl_by_score:
        triple_body = """
// TL = le sommet au meilleur score des trois (le mur : sa diagonale ne depasse le grand cote
// que de 15 %, une erreur de module la faisait perdre). Transcription de triple().
float triple(vec3 a, vec3 b, vec3 c, out int rej, out vec2 tl, out vec2 tr, out vec2 bl) {
    float mr = max(a.z, max(b.z, c.z)) / min(a.z, min(b.z, c.z));
    float best = assess(a, b, c, mr, rej, tl, tr, bl);
    int r2;
    vec2 t2, u2, v2;
    float s = assess(b, a, c, mr, r2, t2, u2, v2);
    if (s < best) {
        best = s; rej = r2; tl = t2; tr = u2; bl = v2;
    }
    s = assess(c, a, b, mr, r2, t2, u2, v2);
    if (s < best) {
        best = s; rej = r2; tl = t2; tr = u2; bl = v2;
    }
    return best;
}
"""
    else:
        triple_body = """
// Le plus long cote EN MODULES relie TR a BL, le sommet restant est TL. Transcription de triple().
float triple(vec3 a, vec3 b, vec3 c, out int rej, out vec2 tl, out vec2 tr, out vec2 bl) {
    float d01 = distance(a.xy, b.xy) / ((a.z + b.z) * 0.5);
    float d02 = distance(a.xy, c.xy) / ((a.z + c.z) * 0.5);
    float d12 = distance(b.xy, c.xy) / ((b.z + c.z) * 0.5);
    float mr = max(a.z, max(b.z, c.z)) / min(a.z, min(b.z, c.z));
    if (d12 >= d02 && d12 >= d01) return assess(a, b, c, mr, rej, tl, tr, bl);
    if (d02 >= d01) return assess(b, a, c, mr, rej, tl, tr, bl);
    return assess(c, a, b, mr, rej, tl, tr, bl);
}
"""
    return glsl_head() + f"""// Passe 3/4 : cible 4x1. Texels 0-2 = reperes TL, TR, BL ; texel 3 = statut.
// Transcription de clusters() / triple() / fit(). Les quatre fragments refont la MEME
// recherche deterministe et ne sortent que leur texel : pas de memoire partagee entre
// fragments, et 4 fragments x ~80 000 iterations reste negligeable.
//
// Texels 0-2 : x, y sur 12 bits (R = x & 255, G = y & 255, B = x >> 8 | (y >> 8) << 4),
// A = 255. Texel 3 : R = tuiles non vides, G = candidats / 16, B = motif de rejet
// (1 < 3 grappes, 2 trop de tuiles = bruit, 4 modules incoherents, 8 jambes inegales ou
// angle, 16 jambe / portee hors plage), A = 255 si le quad est valide.

uniform sampler2D InSampler;    // tile
uniform sampler2D CandSampler;  // cand, lu SEULEMENT pour la taille de l'ecran

layout(location = 0) out vec4 fragColor;

const int TILE_N = {TILE_N};
const float CNT_SCALE = {CNT_SCALE!r};
const int K_MAX = {K_MAX};
const float CLUSTER_MODULES = {CLUSTER_MODULES};
const int MAX_TILES = {L.max_tiles};
const float MOD_RATIO_MAX = {MOD_RATIO_MAX};
const float MIN_FILL = {MIN_FILL};
const float LEG_RATIO_MAX = {LEG_RATIO_MAX};
const float COS_MAX = {COS_MAX};
const float LEG_LO = {LEG_LO};
const float LEG_HI = {LEG_HI};
const float SPAN_U = {L.span_u};
const float SPAN_V = {L.span_v};

ivec4 tileAt(int tx, int ty) {{
    return ivec4(round(texelFetch(InSampler, ivec2(tx, ty), 0) * 255.0));
}}

vec2 tilePos(ivec4 t, int tx, int ty, vec2 ts) {{
    return vec2(float(tx), float(ty)) * ts + vec2(float(t.r), float(t.g)) / 255.0 * ts;
}}

float tileCount(ivec4 t) {{
    float c = float(t.b) / CNT_SCALE;
    return c * c;
}}

// Un choix de TL : TR / BL departages au produit vectoriel -- une image n'est jamais miroir --
// puis jambes en modules (distance / module moyen des deux bouts : en pixels la perspective
// trompe) comparees aux portees. Transcription de triple().assess().
float assess(vec3 t, vec3 e1, vec3 e2, float mr, out int rej, out vec2 tl, out vec2 tr, out vec2 bl) {{
    vec2 u = e1.xy - t.xy, v = e2.xy - t.xy;
    if (u.x * v.y - u.y * v.x > 0.0) {{
        vec3 s = e1; e1 = e2; e2 = s;
        u = e1.xy - t.xy;
        v = e2.xy - t.xy;
    }}
    tl = t.xy;
    tr = e1.xy;
    bl = e2.xy;
    float lu = length(u), lv = length(v);
    float cs = abs(dot(u, v)) / max(lu * lv, 1e-6);
    float mu = lu / ((t.z + e1.z) * 0.5), mv = lv / ((t.z + e2.z) * 0.5);   // jambes en modules
    float ru = mu / SPAN_U, rv = mv / SPAN_V;
    rej = 0;
    if (mr > MOD_RATIO_MAX) rej |= 4;
    if (max(ru, rv) > LEG_RATIO_MAX * min(ru, rv) || cs > COS_MAX) rej |= 8;
    if (min(ru, rv) < LEG_LO || max(ru, rv) > LEG_HI) rej |= 16;
    return abs(log(max(ru, 1e-6))) + abs(log(max(rv, 1e-6))) + log(mr) + cs;
}}
{triple_body}

void main() {{
    int idx = int(gl_FragCoord.x);
    vec2 ts = vec2((textureSize(CandSampler, 0) + TILE_N - 1) / TILE_N);

    // grappes : graine = tuile la plus peuplee hors des grappes deja prises
    vec2 sp[K_MAX];
    float sm[K_MAX];
    int ns = 0;
    for (int it = 0; it < K_MAX; it++) {{
        int bestCnt = 0;
        vec2 bp = vec2(0.0);
        float bm = 0.0;
        for (int ty = 0; ty < TILE_N; ty++) {{
            for (int tx = 0; tx < TILE_N; tx++) {{
                ivec4 t = tileAt(tx, ty);
                if (t.b == 0) continue;
                vec2 p = tilePos(t, tx, ty, ts);
                bool taken = false;
                for (int j = 0; j < ns; j++) {{
                    if (distance(p, sp[j]) <= CLUSTER_MODULES * sm[j]) taken = true;
                }}
                if (!taken && t.b > bestCnt) {{
                    bestCnt = t.b;
                    bp = p;
                    bm = float(t.a);
                }}
            }}
        }}
        if (bestCnt == 0) break;
        sp[ns] = bp;
        sm[ns] = bm;
        ns++;
    }}

    // grappes trop maigres pour un vrai repere ecartees ici, l'ordre des autres conserve
    vec3 cl[K_MAX];
    int nc = 0;
    for (int i = 0; i < ns; i++) {{
        float wsum = 0.0;
        vec3 acc = vec3(0.0);
        for (int ty = 0; ty < TILE_N; ty++) {{
            for (int tx = 0; tx < TILE_N; tx++) {{
                ivec4 t = tileAt(tx, ty);
                if (t.b == 0) continue;
                vec2 p = tilePos(t, tx, ty, ts);
                if (distance(p, sp[i]) <= CLUSTER_MODULES * sm[i]) {{
                    float wt = tileCount(t);
                    wsum += wt;
                    acc += wt * vec3(p, float(t.a));
                }}
            }}
        }}
        vec3 c = acc / wsum;
        if (wsum >= MIN_FILL * c.z * c.z) {{
            cl[nc] = c;
            nc++;
        }}
    }}

    // meilleur triangle valide ; a defaut, le motif de rejet du meilleur triangle tout court
    bool found = false;
    float bestScore = 1e30, fbScore = 1e30;
    int fallback = 1;
    vec2 best[3];
    for (int i = 0; i < nc; i++) {{
        for (int j = i + 1; j < nc; j++) {{
            for (int k = j + 1; k < nc; k++) {{
                int rej;
                vec2 tl, tr, bl;
                float score = triple(cl[i], cl[j], cl[k], rej, tl, tr, bl);
                if (rej == 0 && score < bestScore) {{
                    found = true;
                    bestScore = score;
                    best[0] = tl;
                    best[1] = tr;
                    best[2] = bl;
                }}
                if (score < fbScore) {{
                    fallback = rej;
                    fbScore = score;
                }}
            }}
        }}
    }}

    int nonEmpty = 0;
    float total = 0.0;
    for (int ty = 0; ty < TILE_N; ty++) {{
        for (int tx = 0; tx < TILE_N; tx++) {{
            ivec4 t = tileAt(tx, ty);
            if (t.b > 0) {{
                nonEmpty++;
                total += tileCount(t);
            }}
        }}
    }}
    int reject = found ? 0 : fallback;
    if (nonEmpty > MAX_TILES) reject |= 2;

    if (idx == 3) {{
        fragColor = vec4(float(min(nonEmpty, 255)), min(floor(total / 16.0), 255.0),
                         float(reject), reject == 0 ? 255.0 : 0.0) / 255.0;
        return;
    }}
    if (reject != 0) {{
        fragColor = vec4(0.0);
        return;
    }}
    ivec2 q = clamp(ivec2(floor(best[idx] + 0.5)), ivec2(0), ivec2(4095));
    fragColor = vec4(float(q.x & 255), float(q.y & 255),
                     float((q.x >> 8) | ((q.y >> 8) << 4)), 255.0) / 255.0;
}}
"""


def hom_glsl(L=None):
    """-> (hom_base, hom_final) : l'homographie en GLSL pour la geometrie L. hom_base suppose FITPTS
    et PerspSampler ; hom_final suppose hom_base et AlignSampler."""
    L = L or QR
    qa = ", ".join(f"vec2({c[0]}, {c[1]})" for c, _, _ in L.targets)
    rad = ", ".join(str(r) for _, r, _ in L.targets)
    base = f"""
const vec2 SPAN = vec2({L.span_u}, {L.span_v});   // centres de reperes TL-TR, TL-BL, en modules
const vec2 F0 = vec2({L.f0[0]}, {L.f0[1]});      // centre du repere TL dans la grille
const int NT = {len(L.targets)};                  // motifs cherches par align / pick
const int NA = {L.n_align};                       // les NA premiers : motifs d'alignement
const vec2 QA[NT] = vec2[NT]({qa});   // centres (colonne, ligne)
const int RAD[NT] = int[NT]({rad});   // gabarit (2r+1)^2 : 2 = alignement 5x5, 3 = repere 7x7
const float ALIGN_R = {ALIGN_R};
const int ALIGN_K = {ALIGN_K};

// grille (colonne, ligne continues) -> ecran. Homographie qui fixe les 3 reperes :
// P = (T + x U + y V) / (1 + g x + h y), (x, y) = (grille - F0) / SPAN
vec2 hmap(vec2 T, vec2 R, vec2 B, vec2 gh, vec2 gp) {{
    vec2 q = (gp - F0) / SPAN;
    vec2 U = (1.0 + gh.x) * R - T;
    vec2 V = (1.0 + gh.y) * B - T;
    return (T + q.x * U + q.y * V) / (1.0 + gh.x * q.x + gh.y * q.y);
}}

// H0 : (g, h) mesures sur les reperes par la passe persp ; ok = false si une longueur manque
vec2 ghPersp(out bool ok) {{
    ivec4 a = ivec4(round(texelFetch(PerspSampler, ivec2(0, 0), 0) * 255.0));
    ivec4 b = ivec4(round(texelFetch(PerspSampler, ivec2(1, 0), 0) * 255.0));
    int s0 = a.r | (a.g << 8), s1 = a.b | (a.a << 8);
    int s2 = b.r | (b.g << 8), s3 = b.b | (b.a << 8);
    ok = min(min(s0, s1), min(s2, s3)) > 0;
    if (!ok) return vec2(0.0);
    return vec2(sqrt(float(s0) / float(s1)), sqrt(float(s2) / float(s3))) - 1.0;
}}

// motif k : centre predit, pas de la fenetre de recherche (px), module local (px)
void alignWindow(vec2 T, vec2 R, vec2 B, vec2 gh, int k, out vec2 p0, out int step,
                 out float mloc) {{
    vec2 gp = QA[k];
    p0 = hmap(T, R, B, gh, gp);
    vec2 a = hmap(T, R, B, gh, gp + vec2(0.5, 0.0)) - hmap(T, R, B, gh, gp - vec2(0.5, 0.0));
    vec2 b = hmap(T, R, B, gh, gp + vec2(0.0, 0.5)) - hmap(T, R, B, gh, gp - vec2(0.0, 0.5));
    mloc = max(length(a), length(b));
    step = max(1, int(ceil(ALIGN_R * mloc / float(ALIGN_K))));
}}
"""
    refine = ""
    if L.refine:
        refine = """
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
"""
    final = f"""
const float FIT_COND = {FIT_COND};

// centre du motif k en px (1/16 de px dans la cible align) ; ok = false s'il n'a pas ete trouve
vec2 alignAt(int k, out bool ok) {{
    ivec4 t = ivec4(round(texelFetch(AlignSampler, ivec2(k, 0), 0) * 255.0));
    int x16 = t.r | (t.g << 8), y16 = t.b | (t.a << 8);
    ok = x16 != 0 || y16 != 0;
    return vec2(float(x16), float(y16)) / 16.0;
}}
{refine}
// (g, h) aux moindres carres : chaque motif A trouve en (x, y) donne g x (A - TR) + h y (A - BL)
// = P_affine - A. A defaut H0, a defaut l'affine. Transcription de fit_gh() / homography().
vec2 ghFinal(vec2 T, vec2 R, vec2 B) {{
    float m00 = 0.0, m01 = 0.0, m11 = 0.0, r0 = 0.0, r1 = 0.0;
    for (int k = 0; k < NA; k++) {{
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
    }}
    float det = m00 * m11 - m01 * m01;
    if (det > FIT_COND * m00 * m11 && det > 0.0) {{
        return vec2(m11 * r0 - m01 * r1, m00 * r1 - m01 * r0) / det;
    }}
    bool pok;
    return ghPersp(pok);       // (0, 0) si la mesure a echoue : la grille affine
}}

// motifs cherches : croix cyan sur ceux trouves, rouge a la position predite des autres
vec3 alignMarks(vec2 pf, vec2 T, vec2 R, vec2 B, vec2 gh, vec3 col) {{
    for (int k = 0; k < NT; k++) {{
        bool ok;
        vec2 a = alignAt(k, ok);
        vec2 d = abs(pf - (ok ? a : hmap(T, R, B, gh, QA[k])));
        if ((d.x <= 0.5 && d.y <= 6.0) || (d.y <= 0.5 && d.x <= 6.0)) {{
            col = ok ? vec3(0.0, 0.9, 1.0) : vec3(1.0, 0.15, 0.1);
        }}
    }}
    return col;
}}
"""
    return base, final


def align_glsl(L=None):
    L = L or QR
    return glsl_head() + f"""// Passe align : cible {len(L.targets) * ALIGN_W}x{ALIGN_W}, un bloc de {ALIGN_W}x{ALIGN_W} par motif cherche (k = x / {ALIGN_W}).
// Chaque fragment note UNE position de la fenetre de recherche : c0 + (i, j) * pas, c0 = centre
// predit par H0 (la perspective mesuree par persp), pas tel que la fenetre couvre +-ALIGN_R
// modules. Score = modules du gabarit dans le bon etat (5x5 d'alignement : anneau 1 clair ; 7x7 de
// repere : anneau 2 clair ; le reste sombre), gabarit = les centres de modules PROJETES par H0 : juste en echelle et en
// angle meme a 45 degres. Transcription de align_scores() : position en 12 + 12 bits comme
// fit, A = score (0 hors de l'ecran).

uniform sampler2D FitSampler;
uniform sampler2D PerspSampler;
uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

const int ALIGN_W = {ALIGN_W};
""" + glsl_classify("SrcSampler") + FITPTS + hom_glsl(L)[0] + """
void main() {
    ivec2 f = ivec2(gl_FragCoord.xy);
    int k = f.x / ALIGN_W;
    ivec2 ij = ivec2(f.x - k * ALIGN_W, f.y) - ALIGN_K;
    fragColor = vec4(0.0);
    if (fitAt(3).a != 255) return;
    vec2 T = point(0), R = point(1), B = point(2);
    bool pok;
    vec2 gh = ghPersp(pok);
    vec2 p0;
    int step;
    float mloc;
    alignWindow(T, R, B, gh, k, p0, step, mloc);
    ivec2 c = ivec2(floor(p0 + 0.5)) + ij * step;
    ivec2 size = textureSize(SrcSampler, 0);
    int s = 0;
    if (all(greaterThanEqual(c, ivec2(0))) && all(lessThan(c, size))) {
        int r = RAD[k];
        for (int j = -r; j <= r; j++) {
            for (int i = -r; i <= r; i++) {
                vec2 q = hmap(T, R, B, gh, QA[k] + vec2(float(i), float(j)));
                int want = max(abs(i), abs(j)) == r - 1 ? 2 : 1;
                if (cls(c + ivec2(floor(q - p0 + 0.5)), size) == want) s++;
            }
        }
    }
    ivec2 q = clamp(c, ivec2(0), ivec2(4095));
    fragColor = vec4(float(q.x & 255), float(q.y & 255), float((q.x >> 8) | ((q.y >> 8) << 4)),
                     float(s)) / 255.0;
}
"""


def pick_glsl(L=None):
    L = L or QR
    return glsl_head() + f"""// Passe pick : cible {len(L.targets)}x1, un fragment par motif cherche. Score max de son bloc ; parmi
// les positions au max, la plus proche de la prediction (la premiere dans l'ordre de parcours
// a egalite) ; centre = barycentre des positions au max a moins d'un module de celle-la --
// deux grappes au max ne se moyennent pas. Sous SMIN[k] : motif non trouve (texel nul).
// Sortie : centre en 1/16 de px, x = R | G << 8, y = B | A << 8. Transcription de align_pick().

uniform sampler2D InSampler;    // ascore
uniform sampler2D FitSampler;
uniform sampler2D PerspSampler;

layout(location = 0) out vec4 fragColor;

const int ALIGN_W = {ALIGN_W};
const int SMIN[{len(L.targets)}] = int[{len(L.targets)}]({', '.join(str(m) for _, _, m in L.targets)});
""" + FITPTS + hom_glsl(L)[0] + """
ivec4 scoreAt(int k, int i, int j) {
    return ivec4(round(texelFetch(InSampler, ivec2(k * ALIGN_W + i, j), 0) * 255.0));
}

void main() {
    int k = int(gl_FragCoord.x);
    fragColor = vec4(0.0);
    if (fitAt(3).a != 255) return;
    vec2 T = point(0), R = point(1), B = point(2);
    bool pok;
    vec2 gh = ghPersp(pok);
    vec2 p0;
    int step;
    float mloc;
    alignWindow(T, R, B, gh, k, p0, step, mloc);
    int best = -1, bi = 0, bj = 0, bd = 0;
    for (int j = 0; j < ALIGN_W; j++) {
        for (int i = 0; i < ALIGN_W; i++) {
            int s = scoreAt(k, i, j).a;
            int dd = (i - ALIGN_K) * (i - ALIGN_K) + (j - ALIGN_K) * (j - ALIGN_K);
            if (s > best || (s == best && dd < bd)) {
                best = s;
                bi = i;
                bj = j;
                bd = dd;
            }
        }
    }
    if (best < SMIN[k]) return;
    float lim = mloc * mloc;
    int n = 0, sx = 0, sy = 0;
    for (int j = 0; j < ALIGN_W; j++) {
        for (int i = 0; i < ALIGN_W; i++) {
            ivec4 t = scoreAt(k, i, j);
            int dd = (i - bi) * (i - bi) + (j - bj) * (j - bj);
            if (t.a == best && float(step * step * dd) <= lim) {
                n++;
                sx += t.r | ((t.b & 15) << 8);
                sy += t.g | ((t.b >> 4) << 8);
            }
        }
    }
    int x16 = (32 * sx + n) / (2 * n), y16 = (32 * sy + n) / (2 * n);
    fragColor = vec4(float(x16 & 255), float(x16 >> 8), float(y16 & 255), float(y16 >> 8)) / 255.0;
}
"""


def shaders():
    from gen_color_id import EXT, FONT
    head = EXT + "\n// GENERE par gen_qr_detect.py -- ne pas editer a la main.\n"

    classify_fsh = head + """// qr_classify : ce que le detecteur VOIT. Noir = sombre, blanc = clair, rouge = ni l'un ni
// l'autre (zone morte). Les seuils sont l'hypothese n.1 du detecteur : un ecran QR qui sort
// rouge ici ne donnera aucun candidat dans qr_detect, sans autre symptome.

uniform sampler2D InSampler;

layout(location = 0) out vec4 fragColor;
""" + glsl_classify("InSampler") + """
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int c = cls(p, textureSize(InSampler, 0));
    fragColor = vec4(c == 1 ? vec3(0.0) : (c == 2 ? vec3(1.0) : vec3(1.0, 0.0, 0.0)), 1.0);
}
"""

    seed_fsh = head + f"""// Passe 1/4 : ce pixel est-il au centre d'un motif de reperage ? Plages 1:1:3:1:1 mesurees
// pixel a pixel en horizontal, vertical, puis sur les deux diagonales (contre-controle).
// Transcription de axis_test / line_test / seed : la demo verifie que la version numpy
// vectorisee et cette marche donnent le meme resultat.
//
// Sortie : R = module horizontal, G = vertical, B = moyen (px), 0 = pas candidat.

uniform sampler2D InSampler;

layout(location = 0) out vec4 fragColor;

const int RUN_CAP = {RUN_CAP};
const int RING_TOL2 = {RING_TOL2};
const int CORE_TOL2 = {CORE_TOL2};
const int CORE_TOL2_DIAG = {CORE_TOL2_DIAG};
const int ANISO_NUM = {ANISO_MAX[0]};
const int ANISO_DEN = {ANISO_MAX[1]};
""" + glsl_classify("InSampler") + """
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
"""

    tile_fsh = head + f"""// Passe 2/4 : cible {TILE_N}x{TILE_N}, un fragment par tuile d'ecran. Nombre de candidats,
// barycentre DANS la tuile, module moyen. Transcription de tile().
//
// Sommes en ENTIERS relatives a l'origine de la tuile : un float32 sommant des x absolus
// (~3840) sur des milliers de pixels deborderait sa mantisse et decalerait le barycentre.
// Arrondis en entiers aussi : un float32 tranchait un x,5 exact autrement que numpy.
// Sortie : R, G = barycentre / taille de tuile, B = sqrt(n) * {CNT_SCALE:.6f}, A = module.

uniform sampler2D InSampler;    // cand

layout(location = 0) out vec4 fragColor;

const int TILE_N = {TILE_N};
const float CNT_SCALE = {CNT_SCALE!r};

void main() {{
    ivec2 t = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InSampler, 0);
    ivec2 ts = (size + TILE_N - 1) / TILE_N;
    ivec2 o = t * ts;
    ivec2 e = min(o + ts, size);
    int n = 0, sx = 0, sy = 0, sm = 0;
    for (int y = o.y; y < e.y; y++) {{
        for (int x = o.x; x < e.x; x++) {{
            ivec4 c = ivec4(round(texelFetch(InSampler, ivec2(x, y), 0) * 255.0));
            if (c.r > 0) {{
                n++;
                sx += x - o.x;
                sy += y - o.y;
                sm += c.b;
            }}
        }}
    }}
    if (n == 0) {{
        fragColor = vec4(0.0);
        return;
    }}
    // arrondis entiers, comme tile() : un barycentre pile sur x,5 ne doit pas dependre du float
    ivec4 v = ivec4(clamp((2 * sx * 255 + n * ts.x) / (2 * n * ts.x), 0, 255),
                    clamp((2 * sy * 255 + n * ts.y) / (2 * n * ts.y), 0, 255),
                    int(clamp(floor(sqrt(float(n)) * CNT_SCALE + 0.5), 1.0, 255.0)),
                    clamp((2 * sm + n) / (2 * n), 1, 255));
    fragColor = vec4(v) / 255.0;
}}
"""

    fit_fsh = fit_glsl(QR)

    fitpts = FITPTS
    hud_y = 5 * HUD_SC
    hud = hud_glsl()
    draw_fsh = head + f"""// Passe 4/4 : candidats en magenta, croix sur les reperes (TL rouge, TR vert, BL bleu), quad
// plein sur les trois cotes mesures et en pointilles vers le 4e coin, ESTIME en affine
// (q2 = TL + (1 + K)(u + v)) : trois points ne donnent pas la perspective.
//
// HUD en haut a gauche : pastille verte (quad valide) ou rouge, puis trois nombres :
// motif de rejet, tuiles non vides, candidats / 16. Motifs : 1 < 3 grappes, 2 bruit,
// 4 modules incoherents, 8 jambes inegales ou angle, 16 jambe / 50 modules hors plage.

uniform sampler2D InSampler;    // cand
uniform sampler2D FitSampler;   // fit 4x1
uniform sampler2D SrcSampler;   // image d'origine

layout(location = 0) out vec4 fragColor;

const float K = {K!r};
{fitpts}{hud}
// distance au segment ; along = position le long du segment, en px depuis a
float segDist(vec2 p, vec2 a, vec2 b, out float along) {{
    vec2 e = b - a;
    float l = max(dot(e, e), 1e-6);
    float t = clamp(dot(p - a, e) / l, 0.0, 1.0);
    along = t * sqrt(l);
    return distance(p, a + e * t);
}}

void main() {{
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec2 pf = vec2(p);
    ivec2 size = textureSize(SrcSampler, 0);
    vec3 col = texelFetch(SrcSampler, p, 0).rgb;
    if (texelFetch(InSampler, p, 0).r > 0.0) col = vec3(1.0, 0.0, 1.0);

    ivec4 st = fitAt(3);
    if (st.a == 255) {{
        vec2 tl = point(0), tr = point(1), bl = point(2);
        vec2 u = tr - tl, v = bl - tl;
        vec2 q0 = tl - K * (u + v);
        vec2 q1 = tl + (1.0 + K) * u - K * v;
        vec2 q2 = tl + (1.0 + K) * (u + v);
        vec2 q3 = tl - K * u + (1.0 + K) * v;
        float along;
        if (segDist(pf, q0, q1, along) <= 1.5 || segDist(pf, q0, q3, along) <= 1.5) {{
            col = vec3(1.0, 0.9, 0.0);
        }}
        if ((segDist(pf, q1, q2, along) <= 1.5 && mod(floor(along / 8.0), 2.0) == 0.0)
                || (segDist(pf, q3, q2, along) <= 1.5 && mod(floor(along / 8.0), 2.0) == 0.0)) {{
            col = vec3(1.0, 0.9, 0.0);
        }}
        vec3 cc[3] = vec3[3](vec3(1.0, 0.15, 0.15), vec3(0.15, 1.0, 0.15), vec3(0.25, 0.45, 1.0));
        for (int i = 0; i < 3; i++) {{
            vec2 d = abs(pf - point(i));
            if ((d.x <= 1.0 && d.y <= 12.0) || (d.y <= 1.0 && d.x <= 12.0)) col = cc[i];
        }}
    }}

    fragColor = vec4(hud(p, size, st, col), 1.0);
}}
"""
    # --- homographie (voir le bloc de commentaires de la partie numpy) ---
    hom_base, hom_final = hom_glsl(QR)

    persp_fsh = head + f"""// Passe persp : cible 2x1. La perspective du QR MESUREE sur les reperes. Le long de la droite
// TL -> TR (image de la ligne 3,5 du QR : une droite reste une droite), les reperes TL et TR
// font chacun 7 modules ; pour une homographie qui fixe les 3 reperes, le rapport de leurs
// longueurs vaut exactement (1 + g)^2. Idem TL -> BL pour h. Plages 1:1:3:1:1 le long de la
// droite en pas entiers (DDA) : memes octets que finder_span() / persp().
// Texel 0 : longueurs TL->TR (R | G << 8) et TR->TL (B | A << 8) ; texel 1 : TL->BL et BL->TL
// (en pas de la droite, 7 modules). 0 = plages ratees.

uniform sampler2D FitSampler;
uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

const int RUN_CAP = {RUN_CAP};
const int RING_TOL2 = {RING_TOL2};
const int CORE_TOL2_DIAG = {CORE_TOL2_DIAG};
""" + glsl_classify("SrcSampler") + fitpts + """
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
"""

    align_fsh = align_glsl(QR)

    pick_fsh = pick_glsl(QR)

    sample_fsh = head + """// qr_bits, passe sample : cible 57x57, un fragment par module (x = colonne, y = ligne, ligne 0
// = celle du repere TL). Etat du pixel d'ecran au CENTRE du module, 0/1/2 comme cls(), par
// l'HOMOGRAPHIE : les 3 reperes + (g, h) ajuste sur les motifs d'alignement trouves (H0, puis
// l'affine, a defaut). Transcription de sample_bits().

uniform sampler2D FitSampler;
uniform sampler2D PerspSampler;
uniform sampler2D AlignSampler;
uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;
""" + glsl_classify("SrcSampler") + fitpts + hom_base + hom_final + """
void main() {
    ivec2 m = ivec2(gl_FragCoord.xy);
    fragColor = vec4(0.0);
    if (fitAt(3).a != 255) return;
    vec2 T = point(0), R = point(1), B = point(2);
    vec2 q = floor(hmap(T, R, B, ghFinal(T, R, B), vec2(m) + 0.5) + 0.5);
    ivec2 size = textureSize(SrcSampler, 0);
    // teste en flottant AVANT la conversion : un point a l'infini (ou NaN) n'est pas un entier
    if (!(q.x >= 0.0 && q.y >= 0.0 && q.x < float(size.x) && q.y < float(size.y))) return;
    fragColor = vec4(float(cls(ivec2(q), size)) / 255.0, 0.0, 0.0, 1.0);
}
"""

    qmark = int("111" "001" "010" "000" "010", 2)
    bits_fsh = head + f"""// qr_bits, passe d'affichage : sur chaque module du QR, le bit lu par vmc_qr_sample -- 1 vert
// sur un module clair, 0 rouge sur un sombre, ? jaune ni l'un ni l'autre. Le chiffre est
// dessine DANS le module avec la meme homographie que l'echantillonnage, inversee : s'il
// deborde sur le voisin, c'est la geometrie qui a derive, pas la lecture.
// Croix cyan sur les motifs d'alignement trouves, rouge a la position predite des autres.
// ATTENTION : la norme QR fait l'inverse (module sombre = bit 1). Ici clair = 1, a la demande.

uniform sampler2D InSampler;    // bits 57x57
uniform sampler2D FitSampler;
uniform sampler2D PerspSampler;
uniform sampler2D AlignSampler;
uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

const int QR_N = {QR_N};
const int QMARK = {qmark};
{fitpts}{hud}{hom_base}{hom_final}
void main() {{
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(SrcSampler, 0);
    vec3 col = texelFetch(SrcSampler, p, 0).rgb;
    ivec4 st = fitAt(3);
    if (st.a == 255) {{
        vec2 T = point(0), R = point(1), B = point(2);
        vec2 gh = ghFinal(T, R, B);
        // ecran -> grille : l'homographie inversee. Colonnes de M : (U, g), (V, h), (T, 1)
        mat3 M = mat3(vec3((1.0 + gh.x) * R - T, gh.x), vec3((1.0 + gh.y) * B - T, gh.y),
                      vec3(T, 1.0));
        vec3 r = inverse(M) * vec3(vec2(p), 1.0);
        vec2 uv = r.xy / r.z * SPAN + F0;    // en modules, F0 = centre du repere TL
        if (r.z > 0.0 && all(greaterThanEqual(uv, vec2(0.0))) && all(lessThan(uv, vec2(float(QR_N))))) {{
            ivec2 ij = ivec2(floor(uv));
            vec2 f = uv - vec2(ij);
            int c = int(round(texelFetch(InSampler, ij, 0).r * 255.0));
            // glyphe 3x5 au milieu du module ; f.y descend vers BL comme les lignes de FONT,
            // donc pas de retournement ici, et le chiffre suit la rotation du QR
            int gx = int(floor((f.x - 0.2) / 0.6 * 3.0));
            int gy = int(floor((f.y - 0.1) / 0.8 * 5.0));
            if (gx >= 0 && gx < 3 && gy >= 0 && gy < 5) {{
                int glyph = c == 2 ? FONT[1] : (c == 1 ? FONT[0] : QMARK);
                if (((glyph >> (14 - (gy * 3 + gx))) & 1) == 1) {{
                    col = c == 2 ? vec3(0.1, 0.8, 0.2)
                                 : (c == 1 ? vec3(1.0, 0.2, 0.15) : vec3(1.0, 0.85, 0.0));
                }}
            }}
        }}
        col = alignMarks(vec2(p), T, R, B, gh, col);
    }}
    fragColor = vec4(hud(p, size, st, col), 1.0);
}}
"""

    flat_fsh = head + f"""// qr_color : le QR REDRESSE en haut a gauche, {QR_N} x {QR_N} cases de {FLAT_CELL} px sous le HUD. Chaque
// case = le pixel d'ecran au centre du module, par la meme homographie que qr_bits. Tout est
// LU, reperes et motifs d'alignement compris : s'ils sortent nets, la geometrie est juste.
// Magenta sombre = module hors de l'ecran. Croix comme dans qr_bits.

uniform sampler2D FitSampler;
uniform sampler2D PerspSampler;
uniform sampler2D AlignSampler;
uniform sampler2D SrcSampler;

layout(location = 0) out vec4 fragColor;

const int QR_N = {QR_N};
const int CELL = {FLAT_CELL};
const ivec2 ORIGIN = ivec2({FLAT_X}, {FLAT_Y});   // coin haut-gauche du panneau, compte depuis le HAUT
{fitpts}{hud}{hom_base}{hom_final}
void main() {{
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(SrcSampler, 0);
    vec3 col = texelFetch(SrcSampler, p, 0).rgb;
    ivec4 st = fitAt(3);
    if (st.a == 255) {{
        vec2 T = point(0), R = point(1), B = point(2);
        vec2 gh = ghFinal(T, R, B);
        col = alignMarks(vec2(p), T, R, B, gh, col);
        ivec2 local = ivec2(p.x, size.y - 1 - p.y) - ORIGIN;
        int side = QR_N * CELL;
        if (all(greaterThanEqual(local, ivec2(-1))) && all(lessThanEqual(local, ivec2(side)))) {{
            col = vec3(0.5);                           // cadre d'un pixel
            if (all(greaterThanEqual(local, ivec2(0))) && all(lessThan(local, ivec2(side)))) {{
                // ligne 0 du QR en haut du panneau, comme a l'ecran sans roulis
                vec2 q = floor(hmap(T, R, B, gh, vec2(local / CELL) + 0.5) + 0.5);
                col = q.x >= 0.0 && q.y >= 0.0 && q.x < float(size.x) && q.y < float(size.y)
                    ? texelFetch(SrcSampler, ivec2(q), 0).rgb : vec3(0.35, 0.0, 0.35);
            }}
        }}
    }}
    fragColor = vec4(hud(p, size, st, col), 1.0);
}}
"""
    return {"vmc_qr_classify.fsh": classify_fsh, "vmc_qr_seed.fsh": seed_fsh,
            "vmc_qr_tile.fsh": tile_fsh, "vmc_qr_fit.fsh": fit_fsh, "vmc_qr_draw.fsh": draw_fsh,
            "vmc_qr_persp.fsh": persp_fsh, "vmc_qr_align.fsh": align_fsh,
            "vmc_qr_pick.fsh": pick_fsh, "vmc_qr_sample.fsh": sample_fsh,
            "vmc_qr_bits.fsh": bits_fsh, "vmc_qr_flat_draw.fsh": flat_fsh}


def effects():
    from gen_color_id import SQ

    def inp(name, target):
        return {"sampler_name": name, "target": target, "bilinear": False}

    def ps(fsh, inputs, out):
        return {"vertex_shader": SQ, "fragment_shader": "minecraft:post/" + fsh,
                "inputs": inputs, "output": out}

    # main ne peut pas etre lu et ecrit dans la meme passe : dessin dans "out", puis blit
    blit = {"vertex_shader": SQ, "fragment_shader": "minecraft:post/blit",
            "inputs": [inp("In", "out")],
            "uniforms": {"BlitConfig": [{"name": "ColorModulate", "type": "vec4",
                                         "value": [1.0, 1.0, 1.0, 1.0]}]},
            "output": "minecraft:main"}
    detect_ = {"targets": {"cand": {}, "tile": {"width": TILE_N, "height": TILE_N},
                           "fit": {"width": 4, "height": 1}, "out": {}},
               "passes": [ps("vmc_qr_seed", [inp("In", "minecraft:main")], "cand"),
                          ps("vmc_qr_tile", [inp("In", "cand")], "tile"),
                          ps("vmc_qr_fit", [inp("In", "tile"), inp("Cand", "cand")], "fit"),
                          ps("vmc_qr_draw", [inp("In", "cand"), inp("Fit", "fit"),
                                             inp("Src", "minecraft:main")], "out"),
                          blit]}
    classify_ = {"targets": {"out": {}},
                 "passes": [ps("vmc_qr_classify", [inp("In", "minecraft:main")], "out"), blit]}
    # memes 3 premieres passes que qr_detect, puis l'homographie : perspective mesuree sur les
    # reperes, recherche des 6 motifs d'alignement, choix de leurs centres
    src = inp("Src", "minecraft:main")
    hom_targets = dict(detect_["targets"], persp={"width": 2, "height": 1},
                       ascore={"width": 6 * ALIGN_W, "height": ALIGN_W},
                       align={"width": 6, "height": 1})
    hom_passes = detect_["passes"][:3] + [
        ps("vmc_qr_persp", [inp("Fit", "fit"), src], "persp"),
        ps("vmc_qr_align", [inp("Fit", "fit"), inp("Persp", "persp"), src], "ascore"),
        ps("vmc_qr_pick", [inp("In", "ascore"), inp("Fit", "fit"), inp("Persp", "persp")], "align")]
    hom_in = [inp("Fit", "fit"), inp("Persp", "persp"), inp("Align", "align")]
    # qr_bits : la grille 57x57 et son affichage sur le QR
    bits_ = {"targets": dict(hom_targets, bits={"width": QR_N, "height": QR_N}),
             "passes": hom_passes + [ps("vmc_qr_sample", hom_in + [src], "bits"),
                                     ps("vmc_qr_bits", [inp("In", "bits")] + hom_in + [src], "out"),
                                     blit]}
    # qr_color : le QR redresse, en couleurs, en haut a gauche
    color_ = {"targets": hom_targets,
              "passes": hom_passes + [ps("vmc_qr_flat_draw", hom_in + [src], "out"), blit]}
    return {"qr_detect.json": detect_, "qr_classify.json": classify_, "qr_bits.json": bits_,
            "qr_color.json": color_}


def check_samplers(effs, fshs):
    """La regle du jeu (« Unable to find shader defined uniform ») : chaque sampler declare par
    un .fsh doit etre une entree de la passe. qr_glcheck ne la verifie pas (un sampler non lie
    reste sur l'unite 0, juste par hasard si c'est la 1re entree), d'ou ce controle a l'ecriture."""
    import re
    for ename, eff in effs.items():
        for i, p in enumerate(eff["passes"]):
            body = fshs.get(p["fragment_shader"].split("/", 1)[1] + ".fsh")
            if body is None:
                continue   # shader vanilla (blit)
            want = set(re.findall(r"uniform\s+sampler2D\s+(\w+)Sampler\b", body))
            got = {x["sampler_name"] for x in p["inputs"]}
            assert want == got, f"{ename} passe {i} {p['fragment_shader']} : shader {sorted(want)}, passe {sorted(got)}"


def write():
    import json
    check_samplers(effects(), shaders())
    for name, body in shaders().items():
        with open(os.path.join(POST, name), "w") as f:
            f.write(body)
    for name, eff in effects().items():
        with open(os.path.join(EFF, name), "w") as f:
            json.dump(eff, f, indent=2)
            f.write("\n")
    print(f"OK: {len(shaders())} fsh + {' + '.join(effects())}")


def chain(effect):
    """Le cablage d'un effet, tel que qr_glcheck l'execute (voir son en-tete)."""
    eff = effects()[effect + ".json"]
    lines = [f"target {n} {s.get('width', 0)} {s.get('height', 0)}" for n, s in eff["targets"].items()]
    for p in eff["passes"]:
        fsh = p["fragment_shader"].split("/", 1)[1]
        if fsh != "blit":
            ins = " ".join(f"{i['sampler_name']}={i['target']}" for i in p["inputs"])
            lines.append(f"pass {fsh} {p['output']} {ins}")
    return "\n".join(lines) + "\n"


def run_gpu(exe, img, effect):
    """-> ({cible: octets int [h, w, 4]}, {fsh: ms}, stderr) : un effet sur une image GL."""
    import subprocess
    import tempfile
    h, w = img.shape[:2]
    with tempfile.TemporaryDirectory() as d:
        np.concatenate([img, np.full(img.shape[:2] + (1,), 255, np.uint8)], 2).tofile(
            os.path.join(d, "in.rgba"))
        with open(os.path.join(d, "chain.txt"), "w") as f:
            f.write(chain(effect))
        run = subprocess.run([exe, str(w), str(h), os.path.join(d, "in.rgba"), d,
                              os.path.join(d, "chain.txt")], cwd=ROOT, capture_output=True,
                             text=True, check=True)
        out = {}
        for name, spec in effects()[effect + ".json"]["targets"].items():
            tw, th = spec.get("width", w), spec.get("height", h)
            out[name] = np.fromfile(os.path.join(d, name + ".rgba"), np.uint8).reshape(
                th, tw, 4).astype(np.int64)
    ms = {l.split()[1]: float(l.split()[2]) for l in run.stdout.splitlines() if l.startswith("ms ")}
    return out, ms, run.stderr.strip()


TIE_PX = 1e-2         # GPU float32 contre numpy float64 : en dessous, un arrondi peut basculer


def gpu_check(previews=None):
    """Les .fsh ECRITS tournent sur le GPU (qr_glcheck.c, chaine lue dans effects()) et chaque
    cible est comparee a numpy, CHAQUE ETAGE NOURRI DES SORTIES GPU DE L'ETAGE D'AVANT : un
    ecart ne se propage pas, il designe la passe fautive.

    Octet pour octet partout ou tout est entier (cand, tile, fit, persp). Ailleurs les
    flottants (H0, homographie) passent en float32 sur le GPU : un ecart n'est tolere que si
    numpy y trouve un arrondi a moins de 1e-3 px (align) / TIE_PX (bits, couleurs) de basculer,
    et il est compte a part. Tout autre ecart = DIFF."""
    import subprocess
    from gen_qr_card import qr_matrix, render_card
    exe = os.path.join(ROOT, "qr_glcheck")
    src = os.path.join(ROOT, "qr_glcheck.c")
    if not os.path.exists(exe) or os.path.getmtime(exe) < os.path.getmtime(src):
        subprocess.run(["gcc", "-O2", src, "-o", exe, "-lEGL", "-lGL"], check=True)
    pal = load_palette()
    card, qxy = render_card(qr_matrix(0), 5, 512, 384)
    card = quantize(card, pal)
    logo, _ = render_card(qr_matrix(0), 5, 512, 384, os.path.join(ROOT, "..", "..", "logo.png"))
    logo = quantize(logo, pal)
    dark, cols = qr_matrix(0, True)
    color, _ = render_card(dark, 5, 512, 384, colors=cols)
    ok = True
    scenes = [(card, 1920, 1080, 8, 0, 0, "blocky"), (card, 1920, 1080, 12, 25, 0, "blocky"),
              (card, 1920, 1080, 8, 30, 30, "blocky"), (card, 1920, 1080, 3, 15, 40, "blocky"),
              (card, 2560, 1440, 12, 60, 45, "blocky"), (logo, 1920, 1080, 8, 0, 30, "blocky"),
              (color, 1920, 1080, 8, 0, 30, "blocky"), (color, 1920, 1080, 12, 30, 15, "blocky"),
              (color, 1920, 1080, 4, 60, 45, "blocky"),
              (None, 1920, 1080, 8, 0, 0, "hardbw"), (None, 1920, 1080, 8, 0, 0, "concrete")]
    worst = {}
    jj, ii = np.mgrid[0:ALIGN_W, 0:ALIGN_W]
    for c, w, h, m_px, roll, tilt, bg in scenes:
        name = f"m{m_px}_r{roll}_t{tilt}_{bg} {w}x{h}"
        img, _ = render(c, qxy, 5, w, h, m_px, roll, tilt, bg, 0.93)
        cls, (r, g, b), tiles, texels = detect(img)
        gb, ms_b, gl_name = run_gpu(exe, img, "qr_bits")
        gc, ms_c, _ = run_gpu(exe, img, "qr_color")
        for k_, v in list(ms_b.items()) + list(ms_c.items()):
            worst[k_] = max(worst.get(k_, 0.0), v)
        # entiers : octet pour octet (cand : un pixel pile sur un seuil de luma est tolere)
        y = (img.astype(np.float64) / 255.0) @ np.array(LUMA)
        tie = (np.abs(y - DARK_T) < 1e-6) | (np.abs(y - LIGHT_T) < 1e-6)
        cand = gb["cand"]
        diff = (cand[..., 0] != r) | (cand[..., 1] != g) | (cand[..., 2] != b)
        nd, nt = int(diff.sum()), int((diff & tie).sum())
        td = int((gb["tile"] != tiles).any(-1).sum())
        fit_ = [tuple(int(v) for v in t) for t in gb["fit"][0]]
        fd = sum(fit_[i] != tuple(texels[i]) for i in range(4))
        persp_ = [tuple(int(v) for v in t) for t in gb["persp"][0]]
        pd = int(persp_ != persp(cls, fit_))
        # align : chaque bloc de scores contre numpy (memes fit et persp, ceux du GPU)
        pts, status = decode_fit(fit_)
        F = [(p[0], p[1]) for p in pts] if status[3] == 255 else None
        gh0 = gh_persp(persp_) or (0.0, 0.0)
        sc_np = align_scores(cls, fit_, persp_)
        sc_gpu = gb["ascore"]
        ad = at = 0
        for k in range(6):
            blk = slice(k * ALIGN_W, (k + 1) * ALIGN_W)
            if (sc_np[:, blk] != sc_gpu[:, blk]).any():
                at_k = F is not None and align_window(F, gh0, k)[4] < 1e-3
                at += at_k
                ad += not at_k
        # pick : depuis les scores du GPU
        al_np = align_pick(sc_gpu, fit_, persp_)
        al_gpu = [tuple(int(v) for v in t) for t in gb["align"][0]]
        pkd = pkt = 0
        for k in range(6):
            if al_np[k] == al_gpu[k]:
                continue
            _, step, _, mloc, marg = align_window(F, gh0, k)
            s = sc_gpu[:, k * ALIGN_W:(k + 1) * ALIGN_W, 3]
            dd = (ii - ALIGN_K) ** 2 + (jj - ALIGN_K) ** 2
            i0 = int(np.argmin(np.where(s == s.max(), dd, 1 << 40)))
            rad = step * step * ((ii - i0 % ALIGN_W) ** 2 + (jj - i0 // ALIGN_W) ** 2)
            edge = np.abs(rad[s == s.max()] - mloc * mloc).min() / (mloc * mloc)
            tie_k = min(marg, edge) < 1e-3
            pkt += tie_k
            pkd += not tie_k
        # bits et couleurs : depuis fit / persp / align du GPU
        bd = bt = cd = ct = 0
        if status[3] == 255:
            mx, my = module_pos(fit_, al_gpu, persp_)
            near = (margin_arr(mx) < TIE_PX) | (margin_arr(my) < TIE_PX)
            bdiff = gb["bits"][..., 0] != sample_bits(cls, fit_, al_gpu, persp_)
            bd, bt = int((bdiff & ~near).sum()), int((bdiff & near).sum())
            okp, px, py = to_pixel(mx, my, w, h)
            want = np.where(okp[..., None], img[py, px], np.array([89, 0, 89]))
            jm, im = np.mgrid[0:QR_N, 0:QR_N]
            top = gc["out"][::-1]
            got = top[FLAT_Y + jm * FLAT_CELL + FLAT_CELL // 2,
                      FLAT_X + im * FLAT_CELL + FLAT_CELL // 2, :3]
            cdiff = (got != want).any(-1)
            cd, ct = int((cdiff & ~near).sum()), int((cdiff & near).sum())
        good = nd == nt and td == 0 and fd == 0 and pd == 0 and ad == 0 and pkd == 0 \
            and bd == 0 and cd == 0
        ok &= good
        nal = len(decode_align(al_gpu))
        print(f"  {'OK ' if good else 'DIFF'} {name}: cand {nd} ({nt} seuil), tuiles {td}, "
              f"fit {fd}/4, persp {pd}, scores {ad} ({at} arrondi), centres {pkd} ({pkt} arrondi) "
              f"[{nal}/6 trouves], bits {bd} ({bt}), couleurs {cd} ({ct})")
        if fd:
            print("     gpu ", fit_, "\n     numpy", texels)
        if previews and c is not None:
            from PIL import Image
            Image.fromarray(gb["out"][::-1, :, :3].astype(np.uint8)).save(
                os.path.join(previews, f"gpu_bits_m{m_px}_r{roll}_t{tilt}.png"))
            Image.fromarray(gc["out"][::-1, :, :3].astype(np.uint8)).save(
                os.path.join(previews, f"gpu_color_m{m_px}_r{roll}_t{tilt}.png"))
    print("  pire temps par passe (ms) :", "  ".join(f"{k} {v:.2f}" for k, v in worst.items()))
    print("  GPU :", gl_name.splitlines()[-1] if gl_name else "?")
    return ok


if __name__ == "__main__":
    prev = os.environ.get("QR_PREVIEWS")
    if prev:
        os.makedirs(prev, exist_ok=True)
    if "--gpu" in sys.argv:
        sys.exit(0 if gpu_check(prev) else 1)
    ok = demo(prev, quick="--quick" in sys.argv)
    if not ok:
        sys.exit("demo() en echec : shaders NON ecrits")
    if "--demo" not in sys.argv:
        write()
