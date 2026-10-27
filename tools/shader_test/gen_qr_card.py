#!/usr/bin/env python3
"""Mire de test pour qr_detect : un QR v10 STRUCTURELLEMENT conforme, contenu bidon.

    python3 gen_qr_card.py [--maps 4x3] [--module 5] [--logo ../../logo.png] [--out qr_card.png]

Puis en jeu :  /vmc render strict   et   /photo <chemin du png> 4 3

Pour de la DETECTION seule la geometrie compte : 3 motifs de reperage 7x7 et leurs
separateurs, motifs de synchronisation, 6 motifs d'alignement, module sombre, zone calme.
Les modules de donnees sont pseudo-aleatoires (graine fixe -> reproductible) : un vrai
encodeur (Reed-Solomon + masquage) n'apporterait rien a cette etape, et le decodage sera
de toute facon refait en couleurs.

Le PNG est rendu a la resolution EXACTE de l'ecran vise (128 px par map) avec un module d'un
nombre ENTIER de texels : StillClip et mcmm n'ont alors rien a reechantillonner, et chaque
bord de module tombe sur un bord de texel. Sans ca (384/65 = 5,9 texels par module sur un
4x3) chaque bord serait quantifie a cheval sur deux texels avant meme que le shader regarde.

--logo incruste une image au centre, sur un carre blanc, comme les QR a logo du commerce.
Sur un vrai QR c'est la correction d'erreur qui encaisse la perte ; ici ca sert surtout de
test de faux positifs A L'INTERIEUR du code.
"""
import argparse
import json
import os

import numpy as np

VERSION = 10
N = 4 * VERSION + 17                 # 57 modules
QUIET = 4                            # zone calme normative ; qr_detect en exige au moins 1
ALIGN = (6, 28, 50)                  # centres des motifs d'alignement d'un v10
FINDER_CENTERS = ((3.5, 3.5), (N - 3.5, 3.5), (3.5, N - 3.5))   # (x, y) en modules
LOGO_MODULES = 23                    # carre du logo, + 1 module blanc tout autour
                                     # -> 25^2 / 57^2 = 19 % de la surface


def qr_matrix(seed=0, color_test=False):
    """bool[N, N], True = module sombre, [ligne, colonne]."""
    rng = np.random.default_rng(seed)
    dark = rng.random((N, N)) < 0.5

    # synchronisation : ligne 6 et colonne 6, sombre sur les index pairs
    for i in range(8, N - 8):
        dark[6, i] = dark[i, 6] = (i % 2 == 0)

    # alignement 5x5 (anneau 1 clair), sauf sous les trois reperes. Ceux qui tombent sur
    # les lignes de synchronisation les recouvrent : c'est la norme pour v >= 7.
    for r in ALIGN:
        for c in ALIGN:
            if (r, c) in ((6, 6), (6, N - 7), (N - 7, 6)):
                continue
            for i in range(-2, 3):
                for j in range(-2, 3):
                    dark[r + i, c + j] = max(abs(i), abs(j)) != 1

    # reperes 7x7 (anneau 2 clair) + separateur clair d'un module, poses EN DERNIER pour que
    # rien ne vienne salir le motif que le shader cherche
    for r0, c0 in ((0, 0), (0, N - 7), (N - 7, 0)):
        for i in range(-1, 8):
            for j in range(-1, 8):
                r, c = r0 + i, c0 + j
                if 0 <= r < N and 0 <= c < N:
                    inside = 0 <= i <= 6 and 0 <= j <= 6
                    dark[r, c] = inside and max(abs(i - 3), abs(j - 3)) != 2

    dark[4 * VERSION + 9, 8] = True   # module sombre obligatoire
    if color_test:
        return dark, color_test_modules(dark)
    return dark


def color_test_modules(dark):
    """Retourne une couleur de palette par module de donnees, ligne par ligne."""
    path = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main",
                        "resources", "vanilla_map_colors.json")
    with open(path, encoding="utf-8") as f:
        bases = json.load(f)["colorList"]
    shades = (180, 220, 255, 135)
    colors = []
    for value in bases.values():
        rgb = tuple(int(value[i:i + 2], 16) for i in (1, 3, 5))
        for shade in shades:
            colors.append(tuple(channel * shade // 255 for channel in rgb))
    result = np.zeros((*dark.shape, 3), dtype=np.uint8)
    data = np.argwhere(~reserved_modules())
    for index, (y, x) in enumerate(data):
        result[y, x] = colors[index % len(colors)]
    return result


def reserved_modules():
    """Positions structurelles du QR v10, exclues du dégradé de test."""
    reserved = np.zeros((N, N), dtype=bool)
    for r, c in ((0, 0), (0, N - 7), (N - 7, 0)):
        reserved[max(0, r - 1):min(N, r + 8), max(0, c - 1):min(N, c + 8)] = True
    for r in ALIGN:
        for c in ALIGN:
            if (r, c) in ((6, 6), (6, N - 7), (N - 7, 6)):
                continue
            reserved[r - 2:r + 3, c - 2:c + 3] = True
    reserved[6, 8:N - 8] = True
    reserved[8:N - 8, 6] = True
    reserved[4 * VERSION + 9, 8] = True
    return reserved


def render_card(dark, module, width, height, logo=None, colors=None):
    """-> (image uint8 [h, w, 3], (x, y) du coin du module (0,0) en px).

    Fond blanc, QR centre, zone calme incluse dans le fond."""
    side = (N + 2 * QUIET) * module
    if side > min(width, height):
        raise SystemExit(f"QR de {side} px dans un ecran {width}x{height} : baisse --module")
    img = np.full((height, width, 3), 255, np.uint8)
    qx = (width - N * module) // 2
    qy = (height - N * module) // 2
    big = np.kron(dark, np.ones((module, module), dtype=bool))
    img[qy:qy + N * module, qx:qx + N * module][big] = 0
    if colors is not None:
        for y, x in np.argwhere(np.any(colors != 0, axis=2)):
            img[qy + y * module:qy + (y + 1) * module,
                qx + x * module:qx + (x + 1) * module] = colors[y, x]

    if logo is not None:
        from PIL import Image
        cleared = LOGO_MODULES + 2
        c0 = (N - cleared) // 2
        x, y = qx + c0 * module, qy + c0 * module
        img[y:y + cleared * module, x:x + cleared * module] = 255
        box = LOGO_MODULES * module
        im = Image.open(logo).convert("RGBA").resize((box, box), Image.NEAREST)
        rgba = np.asarray(im).astype(np.float64)
        a = rgba[:, :, 3:4] / 255.0
        x, y = x + module, y + module
        sub = img[y:y + box, x:x + box].astype(np.float64)
        img[y:y + box, x:x + box] = (rgba[:, :, :3] * a + sub * (1 - a)).round().astype(np.uint8)
    return img, (qx, qy)


def main():
    root = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--maps", default="4x3", help="taille de l'ecran en maps, LxH")
    ap.add_argument("--module", type=int, default=0, help="texels par module (0 = le plus grand qui tient)")
    ap.add_argument("--logo", default=None, help="image a incruster au centre")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--color-test", action="store_true",
                    help="remplit les modules de donnees avec le degrade de palette")
    ap.add_argument("--out", default=os.path.join(root, "qr_card.png"))
    a = ap.parse_args()

    mw, mh = (int(v) for v in a.maps.lower().split("x"))
    width, height = 128 * mw, 128 * mh
    module = a.module or min(width, height) // (N + 2 * QUIET)
    matrix = qr_matrix(a.seed, a.color_test)
    if a.color_test:
        dark, colors = matrix
    else:
        dark, colors = matrix, None
    img, (qx, qy) = render_card(dark, module, width, height, a.logo, colors)

    from PIL import Image
    Image.fromarray(img).save(a.out)
    print(f"QR v{VERSION} {N}x{N} modules, {module} texels/module, zone calme {QUIET} -> "
          f"{a.out} ({width}x{height}){' + logo' if a.logo else ''}")
    print(f"en jeu : /vmc render strict   puis   /photo {a.out} {mw} {mh}")


if __name__ == "__main__":
    main()
