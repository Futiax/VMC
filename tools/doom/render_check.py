#!/usr/bin/env python3
"""Rend une image du post effect doom_view hors jeu, sur le GPU (glcheck.c) : le vrai moteur
(native/build/doommc-engine) joue TICKS ticks serveur avec les BOUTONS donnes, puis sa derniere trame
est mise dans la balise comme le ferait DoomProbe.encode, avec les 9 pixels de ModelViewMat que
text.fsh ecrit pour ce regard.

    python3 tools/doom/render_check.py YAW PITCH sortie.png [TICKS] [BOUTONS] [ARME]

YAW / PITCH : convention Minecraft (yaw 0 = sud, 90 = ouest, pitch positif = vers le bas) ; le
joueur Doom regarde dans la meme direction (yaw 270 = est = angle Doom 0). BOUTONS : masque de
doommc_engine.c (1 avancer, 2 reculer, 4 gauche, 8 droite, 16 tirer, 32 utiliser, 64 courir).
"""
import os
import struct
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_level import DEFAULT_WAD, OUT_H, OUT_W, STATE_W  # noqa: E402
from gen_probe import MATRIX_CHECK, MATRIX_X0, ROOT, java_ids, map_rgb  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
EFFECT_TEX = os.path.join(ROOT, "pack", "assets", "minecraft", "textures", "effect")
ENGINE = os.path.join(ROOT, "native", "build", "doommc-engine")


def model_view(yaw, pitch):
    """Rotation monde -> vue de Minecraft : lignes = droite, haut, arriere de la camera."""
    y, p = np.radians(yaw), np.radians(pitch)
    fwd = np.array([-np.sin(y) * np.cos(p), -np.sin(p), np.cos(y) * np.cos(p)])
    right = np.cross(fwd, [0.0, 1.0, 0.0])
    right /= np.linalg.norm(right)
    up = np.cross(right, fwd)
    return np.array([right, up, -fwd])


def doom_angle(yaw):
    """Meme calcul que DoomProbe.doomAngle : BAM, 0 = est, sens trigonometrique."""
    return int(round(((-90.0 - yaw) % 360.0) / 360.0 * 2 ** 32)) % 2 ** 32


def engine_frame(ticks, buttons, weapon, yaw, wad, extra=(), script=None, slot=0):
    """Trame de la place slot apres ticks ticks ; script : {tick: [lignes J/L]}, les autres places immobiles."""
    with tempfile.TemporaryDirectory() as tmp:
        p = subprocess.Popen([ENGINE, "-iwad", wad, "-warp", "1", "1", "-skill", "3", *extra], cwd=tmp,
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        for t in range(ticks):
            for line in (script or {}).get(t, []):
                p.stdin.write(line.encode() + b"\n")
            p.stdin.write(b"T %d %d %d" % (buttons, weapon, doom_angle(yaw)) + b" 0 0 0" * 3 + b"\n")
            p.stdin.flush()
            frames = {}
            for _ in range(struct.unpack("<H", p.stdout.read(2))[0]):
                s, n = struct.unpack("<HH", p.stdout.read(4))
                frames[s] = list(struct.unpack("<%dH" % n, p.stdout.read(2 * n)))
        p.kill()
    return frames[slot]


def probe_input(w, h, values, yaw, pitch):
    sig, nib = java_ids("SIGNATURE"), java_ids("NIBBLES")
    ids = [nib[0]] * (128 * 128)
    ids[:len(sig)] = sig
    at = len(sig)
    for v in values:
        for n in range(4):
            ids[at] = nib[(v >> (4 * n)) & 15]
            at += 1
    img = np.full((h, w, 4), 90, np.uint8)        # ligne 0 = bas de l'ecran
    img[..., 3] = 255
    rgb = {i: map_rgb(i) for i in set(ids)}
    img[:128, :128, :3] = np.array([rgb[i] for i in ids], np.uint8).reshape(128, 128, 3)
    m = model_view(yaw, pitch)
    for k in range(9):
        u = int(np.clip(round((m[k % 3][k // 3] + 1.0) * 32767.5), 0, 65535))
        img[0, MATRIX_X0 + k, :3] = (u >> 8, u & 255, MATRIX_CHECK)
    return img


def main():
    yaw, pitch = float(sys.argv[1]), float(sys.argv[2])
    out = sys.argv[3]
    ticks = int(sys.argv[4]) if len(sys.argv) > 4 else 1
    buttons = int(sys.argv[5]) if len(sys.argv) > 5 else 0
    weapon = int(sys.argv[6]) if len(sys.argv) > 6 else 0
    w, h = 1280, 720
    values = [0] * 6 + engine_frame(ticks, buttons, weapon, yaw, DEFAULT_WAD)
    with tempfile.TemporaryDirectory() as tmp:
        exe = os.path.join(tmp, "glcheck")
        subprocess.run(["gcc", "-O2", os.path.join(HERE, "glcheck.c"), "-o", exe, "-lEGL", "-lGL"], check=True)
        os.symlink(os.path.join(ROOT, "pack"), os.path.join(tmp, "resourcepack"))
        probe_input(w, h, values, yaw, pitch).tofile(os.path.join(tmp, "main.rgba"))
        chain = ["target state %d %d" % (STATE_W, STATE_W), "target doom %d %d" % (OUT_W, OUT_H)]
        inputs = []
        for name, sampler in (("doom_level", "Level"), ("doom_atlas", "Atlas"), ("doom_colormap", "Colormap")):
            img = np.array(Image.open(os.path.join(EFFECT_TEX, name + ".png")).convert("RGBA"))
            img.tofile(os.path.join(tmp, name + ".rgba"))
            chain.append("image %s %d %d %s" % (name, img.shape[1], img.shape[0], os.path.join(tmp, name + ".rgba")))
            inputs.append("%s=%s" % (sampler, name))
        chain.append("pass doom_decode state Src=minecraft:main")
        chain.append("pass doom_view doom Src=minecraft:main State=state " + " ".join(inputs))
        open(os.path.join(tmp, "chain.txt"), "w").write("\n".join(chain) + "\n")
        os.makedirs(os.path.join(tmp, "out"))
        subprocess.run([exe, str(w), str(h), "main.rgba", "out", "chain.txt"], cwd=tmp, check=True)
        state = np.fromfile(os.path.join(tmp, "out", "state.rgba"), np.uint8).reshape(-1, 4)
        decoded = state[:, 0].astype(int) | (state[:, 1].astype(int) << 8)
        assert list(decoded[:len(values)]) == values, "doom_decode ne relit pas la balise"
        img = np.fromfile(os.path.join(tmp, "out", "doom.rgba"), np.uint8).reshape(OUT_H, OUT_W, 4)
        Image.fromarray(img[::-1, :, :3]).save(out)


if __name__ == "__main__":
    main()
