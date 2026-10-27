#!/usr/bin/env python3
"""Murs du mode data en maps .dat pour le monde solo, avec une VRAIE image encodee dedans : si
le post effect affiche l'image proprement, la lecture marche (un symbole faux decale la suite de
sa ligne de macroblocs = bandes de bruit), pas seulement la detection des bords.

    python3 data_maps.py [image] [--world <monde>]

Les murs de gen_dct.WALLS (1 px de map par module depuis le 16/10), meme image, un datapack pour
les poser (frames invisibles, tag vmc) :
  ids 100-107  WAN 4x2 maps   /function vmc:wan   effet minecraft:vmc_data
  ids 140-154  LAN 5x3 maps   /function vmc:lan   effet minecraft:vmc_data_lan
  /function vmc:clear retire tous les murs poses.
Chaque fonction pose le coin BAS-GAUCHE du mur sur ta position, le mur s'etend vers l'est (+x)
et vers le haut, face au sud : place-toi au sud (z plus grand) et regarde vers le nord."""
import argparse
import gzip
import json
import os
import struct


import gen_dct as G
from maps_from_source import DATA_VERSION, WORLD, map_dat, root, t_int, tag

WALLS = (("wan", 100), ("lan", 140))


def place(name, first, mw, mh):
    """mcfunction : frames du mur, ligne 0 = haut, coin bas-gauche a la position du joueur."""
    lines = []
    for r in range(mh):
        for c in range(mw):
            lines.append(f'summon minecraft:glow_item_frame ~{c} ~{mh - 1 - r} ~ {{Facing:3b,Fixed:1b,Invisible:1b,'
                         f'Tags:["vmc"],Item:{{id:"minecraft:filled_map",count:1,'
                         f'components:{{"minecraft:map_id":{first + r * mw + c}}}}}}}')
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("image", nargs="?", default=G.TRAIN[0])
    ap.add_argument("--world", default=WORLD)
    a = ap.parse_args()
    from PIL import Image
    maps_dir = os.path.join(a.world, "data", "minecraft", "maps")
    fn_dir = os.path.join(a.world, "datapacks", "vmc_walls", "data", "vmc", "function")
    os.makedirs(maps_dir, exist_ok=True)
    os.makedirs(fn_dir, exist_ok=True)
    last = 0
    for name, first in WALLS:
        G.use_wall(name)
        cells = G.encode(G.fit_canvas(Image.open(a.image)))
        tiles = G.frame_tiles(cells)
        assert (G.cells_of_tiles(tiles.tobytes()) == cells).all()     # le mur se relit tel quel
        mw, mh = G.WALL_MAPS
        for i, t in enumerate(tiles):
            with gzip.open(os.path.join(maps_dir, f"{first + i}.dat"), "wb") as f:
                f.write(map_dat(t.tobytes()))
        with open(os.path.join(fn_dir, f"{name}.mcfunction"), "w") as f:
            f.write(place(name, first, mw, mh))
        last = max(last, first + len(tiles) - 1)
        print(f"  {name}: ids {first}..{first + len(tiles) - 1} ({mw}x{mh} maps), effet "
              f"minecraft:vmc_data{G.SFX}  ->  /function vmc:{name}")
    with open(os.path.join(fn_dir, "clear.mcfunction"), "w") as f:
        f.write("kill @e[type=minecraft:glow_item_frame,tag=vmc]\n")
    with open(os.path.join(a.world, "datapacks", "vmc_walls", "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"description": "VMC : murs de test du mode data",
                            "min_format": [121, 0], "max_format": [121, 0]}}, f)
    last_path = os.path.join(maps_dir, "last_id.dat")
    prev = -1
    if os.path.exists(last_path):                           # ne jamais faire reculer le compteur
        blob = gzip.open(last_path).read()
        k = blob.find(b"map")
        prev = struct.unpack(">i", blob[k + 3:k + 7])[0] if k >= 0 else -1
    with gzip.open(last_path, "wb") as f:
        f.write(root(t_int("DataVersion", DATA_VERSION), tag(10, "data") + t_int("map", max(prev, last)) + b"\x00"))
    print(f"image : {a.image} ; last_id = {max(prev, last)} ; monde a re-rentrer")


if __name__ == "__main__":
    main()
