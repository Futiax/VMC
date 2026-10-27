#!/usr/bin/env python3
"""Ecrit une image (ou la 1re frame d'une video) en maps .dat dans un monde 26.3.

    python3 maps_from_source.py <image|video> <palette.json> <first_id>
                                [--grid 3x4] [--mode normal|dither|strict] [--world <monde>]

Format 26.3 (verifie dans le jar client) : data/minecraft/maps/<id>.dat, plus
maps/last_id.dat (cle "map") a la place de map_N.dat / idcounts.dat. Le NBT
"data" est celui de write_map_dat() dans mcmm.c, DataVersion = 26.3 pour ne
traverser aucun datafixer. Ids ecrits ligne par ligne, rangee 0 en haut.
Une image fixe donne 0 frame a mcmm (le filtre fps jette une frame de 0,04 s),
d'ou le clip d'une seconde -- celui de StillClip.java en strict : png dans un mkv,
sans perte et sans redimensionnement (StillClip plafonne a 2048 px hors strict). La premiere version passait par un mp4 libx264 agrandi a 1024 px
de haut, que mcmm reduisait ensuite : deux bicubiques, des gris a chaque bord de
module de la mire QR. --mode strict pour une image pixel-exacte (mcmm --strict).
Monde a re-rentrer apres ecriture.
"""
import argparse
import gzip
import os
import struct
import subprocess
import tempfile

ROOT = os.path.dirname(os.path.abspath(__file__))
MCMM = os.path.join(ROOT, "..", "..", "src", "main", "resources", "natives", "mcmm-linux-x64")
WORLD = os.path.expanduser("~/.local/share/PrismLauncher/instances/26.3/minecraft/saves/New World")
DATA_VERSION = 5023
TILE = 128 * 128


def tag(t, n): return bytes([t]) + struct.pack(">H", len(n)) + n.encode()
def t_int(n, v): return tag(3, n) + struct.pack(">i", v)
def t_byte(n, v): return tag(1, n) + bytes([v])
def t_str(n, s): return tag(8, n) + struct.pack(">H", len(s)) + s.encode()
def t_barr(n, b): return tag(7, n) + struct.pack(">i", len(b)) + b
def root(*inner): return tag(10, "") + b"".join(inner) + b"\x00"


def map_dat(colors):
    data = (t_int("xCenter", 0) + t_int("zCenter", 0)
            + t_byte("trackingPosition", 0) + t_byte("unlimitedTracking", 0)
            + t_str("dimension", "minecraft:overworld")
            + t_byte("locked", 1)                       # jamais redessinee par le jeu
            + t_barr("colors", colors))
    return root(t_int("DataVersion", DATA_VERSION), tag(10, "data") + data + b"\x00")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source"); ap.add_argument("palette"); ap.add_argument("first_id", type=int)
    ap.add_argument("--grid", default="3x4", help="colonnes x lignes de cartes")
    ap.add_argument("--mode", choices=("normal", "dither", "strict"), default="dither")
    ap.add_argument("--world", default=WORLD)
    a = ap.parse_args()
    mw, mh = (int(v) for v in a.grid.lower().split("x"))

    with tempfile.TemporaryDirectory() as tmp:
        clip = os.path.join(tmp, "clip.mkv")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", a.source, "-vf",
                        "trim=end_frame=1,loop=loop=-1:size=1:start=0,fps=2",
                        "-t", "1", "-an", "-c:v", "png", clip], check=True)
        # la palette est copiee : mcmm ecrit son cache .lut A COTE du json qu'on lui donne
        pal = os.path.join(tmp, "palette.json")
        with open(a.palette, "rb") as src, open(pal, "wb") as dst:
            dst.write(src.read())
        cmd = [MCMM, "--stream", "--palette", pal] + ([] if a.mode == "normal" else ["--" + a.mode]) \
            + [clip, str(mw), str(mh), "1"]
        raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    tiles = raw[16 + 8:]                                    # header 16 + PTS s64 (protocole v2)
    if len(tiles) < mw * mh * TILE:
        raise SystemExit(f"mcmm n'a pas sorti de frame ({len(raw)} octets)")

    maps_dir = os.path.join(a.world, "data", "minecraft", "maps")
    os.makedirs(maps_dir, exist_ok=True)
    for i in range(mw * mh):
        with gzip.open(os.path.join(maps_dir, f"{a.first_id + i}.dat"), "wb") as f:
            f.write(map_dat(tiles[i * TILE:(i + 1) * TILE]))
    last = a.first_id + mw * mh - 1
    last_path = os.path.join(maps_dir, "last_id.dat")
    prev = -1
    if os.path.exists(last_path):                           # ne jamais faire reculer le compteur
        blob = gzip.open(last_path).read()
        k = blob.find(b"map")
        prev = struct.unpack(">i", blob[k + 3:k + 7])[0] if k >= 0 else -1
    with gzip.open(last_path, "wb") as f:
        f.write(root(t_int("DataVersion", DATA_VERSION), tag(10, "data") + t_int("map", max(prev, last)) + b"\x00"))
    print(f"ids {a.first_id}..{last} ({mw}x{mh}, rangee 0 en haut) -> {maps_dir}")
    print(f"/give @s filled_map[map_id={a.first_id}]   ...   last_id = {max(prev, last)}")


if __name__ == "__main__":
    main()
