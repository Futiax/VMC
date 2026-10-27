#!/usr/bin/env python3
"""Palette map OPTIMISEE pour une image ou une video donnee.

    python3 gen_palette.py <image|video> [-o custom_palette.json] [--iters 40]
                           [--samples 80000] [--frames 24]

Sort un JSON au format vanilla_map_colors.json (61 bases, mcmm applique les 4
nuances {180,220,255,135} tout seul) : a pointer par palette-path dans la config
du plugin, ou par --palette pour un test. Puis `python3 gen_shaders.py <json>`
regenere custom_palette.fsh pour que le client affiche ces couleurs-la.

Modele : la couleur d'ecran d'un id est base*nuance//255 (client + mcmm), donc
une base ne couvre que le rayon noir->base en sRGB, en 4 marches. On fait un
k-means sous cette contrainte : affectation au plus proche des 244 couleurs en
distance sRGB (LA metrique de la LUT de mcmm, pas OKLab, pour que l'optimisation
et la quantification soient d'accord), et mise a jour de chaque base par moindres
carres ponderes par la nuance : base = sum(s*p) / sum(s^2). Init k-means++.

Sans le resource pack, l'image quantifiee sur cette palette s'affiche avec les
couleurs vanilla des memes ids -> on assigne chaque base optimisee a l'id
vanilla le plus proche encore libre, pour que la degradation reste vaguement
lisible. C'est cosmetique, le pack reste obligatoire.
"""
import argparse
import json
import os
import subprocess
import sys

import numpy as np

ROOT = os.path.dirname(os.path.abspath(__file__))
VANILLA = os.path.join(ROOT, "..", "..", "src", "main", "resources", "vanilla_map_colors.json")
SHADES = np.array([180, 220, 255, 135], dtype=np.float64)   # ordre des ids : id = base*4 + nuance
N_BASES = 61


def load_bases(path):
    with open(path, encoding="utf-8") as f:
        cl = json.load(f)["colorList"]
    return np.array([[int(cl[str(i)].lstrip("#")[k:k + 2], 16) for k in (0, 2, 4)]
                     for i in range(1, N_BASES + 1)], dtype=np.float64)


def expand(bases):
    """61 bases -> 244 couleurs d'ecran, meme arithmetique entiere que le client."""
    return np.floor(bases[:, None, :] * SHADES[None, :, None] / 255.0).reshape(-1, 3)


def sample_pixels(source, n_frames, width):
    """Pixels rgb24 d'une image ou de n_frames de la video, reduits a `width` px."""
    dur = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                          "-of", "csv=p=0", source], capture_output=True, text=True).stdout.strip()
    try:
        dur = float(dur)
    except ValueError:
        dur = 0.0
    vf = f"scale={width}:-2"
    if dur > 1.0:                                   # video : n_frames repartis sur la duree
        vf = f"fps={n_frames / dur:.6f}," + vf
    out = subprocess.run(["ffmpeg", "-v", "error", "-i", source, "-vf", vf,
                          "-frames:v", str(n_frames), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                         capture_output=True, check=True).stdout
    px = np.frombuffer(out, dtype=np.uint8).reshape(-1, 3).astype(np.float64)
    return px


def nearest(px, colors):
    """Index (0..243) de la couleur la plus proche pour chaque pixel, par blocs."""
    idx = np.empty(len(px), dtype=np.int64)
    for a in range(0, len(px), 8192):
        d = ((px[a:a + 8192, None, :] - colors[None, :, :]) ** 2).sum(axis=2)
        idx[a:a + 8192] = d.argmin(axis=1)
    return idx


def kmeanspp(px, k, rng):
    bases = [px[rng.integers(len(px))]]
    d2 = ((px - bases[0]) ** 2).sum(axis=1)
    for _ in range(k - 1):
        p = d2 / d2.sum()
        c = px[rng.choice(len(px), p=p)]
        bases.append(c)
        d2 = np.minimum(d2, ((px - c) ** 2).sum(axis=1))
    return np.array(bases)


def optimize(px, iters, rng):
    bases = kmeanspp(px, N_BASES, rng)
    s = SHADES / 255.0
    for it in range(iters):
        colors = expand(bases)
        idx = nearest(px, colors)
        base_of, shade_of = idx // 4, idx % 4
        w = s[shade_of]                                          # poids = nuance du slot choisi
        num = np.zeros((N_BASES, 3)); den = np.zeros(N_BASES)
        np.add.at(num, base_of, px * w[:, None])
        np.add.at(den, base_of, w * w)
        new = bases.copy()
        ok = den > 0
        new[ok] = np.clip(num[ok] / den[ok, None], 0, 255)
        # base vide : on la ressemme sur le pixel le plus mal servi
        if (~ok).any():
            err = ((px - colors[idx]) ** 2).sum(axis=1)
            worst = np.argsort(err)[::-1]
            for b, p in zip(np.where(~ok)[0], worst):
                new[b] = px[p]
        moved = np.abs(new - bases).max()
        bases = new
        if moved < 0.5:
            break
    return np.round(bases)


def mean_err(px, bases):
    colors = expand(bases)
    return np.sqrt(((px - colors[nearest(px, colors)]) ** 2).sum(axis=1)).mean()


def assign_to_vanilla_ids(bases, vanilla):
    """Permutation : chaque base optimisee prend l'id vanilla le plus proche encore libre."""
    d = ((bases[:, None, :] - vanilla[None, :, :]) ** 2).sum(axis=2)
    order = np.dstack(np.unravel_index(np.argsort(d, axis=None), d.shape))[0]
    out = np.zeros_like(bases); used_b, used_v = set(), set()
    for b, v in order:
        if b in used_b or v in used_v:
            continue
        out[v] = bases[b]; used_b.add(b); used_v.add(v)
        if len(used_b) == N_BASES:
            break
    return out


def write_palette(path, bases, source, err_v, err_o):
    doc = {
        # ATTENTION parseur mcmm : jamais de diese dans _comment (pris pour une couleur d'id 0)
        "_comment": (f"Palette optimisee par gen_palette.py pour {os.path.basename(source)} : "
                     f"erreur sRGB moyenne {err_v:.1f} (vanilla) -> {err_o:.1f}. Meme format que "
                     "vanilla_map_colors.json, 61 bases, nuances appliquees par mcmm. Sans le "
                     "resource pack VMC les couleurs affichees sont celles des ids vanilla."),
        "colorList": {str(i + 1): "#%02x%02x%02x" % tuple(int(v) for v in bases[i])
                      for i in range(N_BASES)},
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=4)


def preview(px_img, shape, vanilla, bases, path):
    """[source | quantifie vanilla | quantifie optimise] pour juger a l'oeil, sans dither."""
    h, w = shape
    panels = [px_img]
    for b in (vanilla, bases):
        c = expand(b)
        panels.append(c[nearest(px_img, c)])
    img = np.concatenate([p.reshape(h, w, 3) for p in panels], axis=1).astype(np.uint8)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
                    "-s", f"{img.shape[1]}x{h}", "-i", "-", path], input=img.tobytes(), check=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source")
    ap.add_argument("-o", "--out", default=os.path.join(ROOT, "custom_palette.json"))
    ap.add_argument("--iters", type=int, default=40)
    ap.add_argument("--samples", type=int, default=80000)
    ap.add_argument("--frames", type=int, default=24)
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()

    rng = np.random.default_rng(a.seed)
    px_all = sample_pixels(a.source, a.frames, 256)
    px = px_all[rng.choice(len(px_all), min(a.samples, len(px_all)), replace=False)]
    vanilla = load_bases(VANILLA)

    bases = optimize(px, a.iters, rng)
    bases = assign_to_vanilla_ids(bases, vanilla)
    err_v, err_o = mean_err(px, vanilla), mean_err(px, bases)
    write_palette(a.out, bases, a.source, err_v, err_o)
    print(f"{len(px)} pixels, erreur sRGB moyenne : vanilla {err_v:.1f} -> optimisee {err_o:.1f} "
          f"(-{100 * (1 - err_o / err_v):.0f} %)")
    print(f"-> {a.out}")

    # apercu sur la premiere frame seule
    w = 256
    first = px_all[: (len(px_all) // w) * w]
    h = len(first) // w
    n = h * w
    # une seule frame : la premiere (les frames se suivent dans px_all)
    frame_h = h if h <= 1024 else 1024
    pv = os.path.splitext(a.out)[0] + "_preview.png"
    preview(first[: frame_h * w], (frame_h, w), vanilla, bases, pv)
    print(f"-> {pv}  (source | vanilla | optimisee, sans dither)")


if __name__ == "__main__":
    main()
