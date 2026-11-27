"""Lecture d'un WAD Doom : un niveau (lignes, cotes, secteurs, BSP), les textures murales composees de
leurs patchs, les flats, la palette et les colormaps. Aucune dependance hors numpy."""
import struct

import numpy as np


class Wad:
    def __init__(self, path):
        self.data = open(path, "rb").read()
        _, n, off = struct.unpack_from("<4sii", self.data, 0)
        self.lumps = []
        for i in range(n):
            pos, size, name = struct.unpack_from("<ii8s", self.data, off + 16 * i)
            self.lumps.append((name.rstrip(b"\0").decode("ascii").upper(), pos, size))

    def index(self, name, start=0):
        for i in range(start, len(self.lumps)):
            if self.lumps[i][0] == name:
                return i
        raise KeyError(name)

    def lump(self, name):
        # le dernier lump de ce nom l'emporte, comme dans le moteur
        for lname, pos, size in reversed(self.lumps):
            if lname == name:
                return self.data[pos:pos + size]
        raise KeyError(name)

    def at(self, i):
        _, pos, size = self.lumps[i]
        return self.data[pos:pos + size]

    def records(self, raw, fmt):
        size = struct.calcsize(fmt)
        return [struct.unpack_from(fmt, raw, k) for k in range(0, len(raw) - size + 1, size)]

    def level(self, name):
        i = self.index(name)
        lumps = {self.lumps[i + k][0]: self.at(i + k) for k in range(1, 11)}
        txt = lambda b: b.rstrip(b"\0").decode("ascii").upper()
        return {
            "things": self.records(lumps["THINGS"], "<hhhhh"),
            "vertexes": self.records(lumps["VERTEXES"], "<hh"),
            # v1, v2, flags, special, tag, front, back (0xFFFF = aucun)
            "linedefs": self.records(lumps["LINEDEFS"], "<HHhhhHH"),
            "sidedefs": [(x, y, txt(u), txt(lo), txt(m), s) for x, y, u, lo, m, s in
                         self.records(lumps["SIDEDEFS"], "<hh8s8s8sh")],
            "sectors": [(f, c, txt(fp), txt(cp), light, sp, tag) for f, c, fp, cp, light, sp, tag in
                        self.records(lumps["SECTORS"], "<hh8s8shhh")],
            "segs": self.records(lumps["SEGS"], "<HHhHhh"),
            "ssectors": self.records(lumps["SSECTORS"], "<HH"),
            # x, y, dx, dy, bbox droite (4), bbox gauche (4), enfant droit, enfant gauche
            "nodes": self.records(lumps["NODES"], "<hhhh8hHH"),
        }

    def palette(self):
        return np.frombuffer(self.lump("PLAYPAL")[:768], np.uint8).reshape(256, 3)

    def palettes(self):
        """Les 14 palettes de PLAYPAL (0 normale, 1-8 degats, 9-12 bonus, 13 combinaison)."""
        return np.frombuffer(self.lump("PLAYPAL")[:14 * 768], np.uint8).reshape(14, 256, 3)

    def colormaps(self):
        return np.frombuffer(self.lump("COLORMAP")[:32 * 256], np.uint8).reshape(32, 256)

    def patch(self, name):
        return self.patch_data(self.lump(name))[:2]

    def patch_data(self, raw):
        """(indices, opaque, decalage gauche, decalage haut) d'un patch au format colonnes de « posts »."""
        w, h, left, top = struct.unpack_from("<hhhh", raw, 0)
        idx = np.zeros((h, w), np.uint8)
        opaque = np.zeros((h, w), bool)
        for x in range(w):
            p = struct.unpack_from("<i", raw, 8 + 4 * x)[0]
            top_row = -1
            while raw[p] != 0xFF:
                delta, length = raw[p], raw[p + 1]
                top_row = delta + top_row if delta <= top_row else delta     # « tall patches » (DeePsea)
                col = np.frombuffer(raw, np.uint8, length, p + 3)
                y1 = min(top_row + length, h)
                if y1 > top_row:
                    idx[top_row:y1, x] = col[:y1 - top_row]
                    opaque[top_row:y1, x] = True
                p += length + 4
        return idx, opaque, left, top

    def sprites(self):
        """Lumps entre S_START et S_END, dans l'ordre du moteur (lump - firstspritelump)."""
        start, end = self.index("S_START"), self.index("S_END")
        return [self.at(i) for i in range(start + 1, end)]

    def textures(self):
        """nom -> (largeur, hauteur, [(x, y, nom du patch)])."""
        pnames_raw = self.lump("PNAMES")
        pnames = [pnames_raw[4 + 8 * i:12 + 8 * i].rstrip(b"\0").decode("ascii").upper()
                  for i in range(struct.unpack_from("<i", pnames_raw, 0)[0])]
        out = {}
        for lump in ("TEXTURE1", "TEXTURE2"):
            try:
                raw = self.lump(lump)
            except KeyError:
                continue
            n = struct.unpack_from("<i", raw, 0)[0]
            for k in range(n):
                p = struct.unpack_from("<i", raw, 4 + 4 * k)[0]
                name = raw[p:p + 8].rstrip(b"\0").decode("ascii").upper()
                w, h = struct.unpack_from("<hh", raw, p + 12)
                count = struct.unpack_from("<h", raw, p + 20)[0]
                patches = []
                for j in range(count):
                    x, y, pi = struct.unpack_from("<hhh", raw, p + 22 + 10 * j)
                    patches.append((x, y, pnames[pi]))
                out[name] = (w, h, patches)
        return out

    def texture(self, defs, name):
        w, h, patches = defs[name]
        idx = np.zeros((h, w), np.uint8)
        opaque = np.zeros((h, w), bool)
        for ox, oy, pname in patches:
            pidx, pop = self.patch(pname)
            ph, pw = pidx.shape
            # partie du patch qui tombe dans la texture
            x0, y0 = max(ox, 0), max(oy, 0)
            x1, y1 = min(ox + pw, w), min(oy + ph, h)
            if x1 <= x0 or y1 <= y0:
                continue
            src = (slice(y0 - oy, y1 - oy), slice(x0 - ox, x1 - ox))
            m = pop[src]
            idx[y0:y1, x0:x1][m] = pidx[src][m]
            opaque[y0:y1, x0:x1] |= m
        return idx, opaque

    def flat(self, name):
        # les flats sont entre F_START et F_END (et leurs sous-marqueurs) : 64 x 64 octets
        start, end = self.index("F_START"), self.index("F_END")
        for i in range(end - 1, start, -1):
            if self.lumps[i][0] == name and self.lumps[i][2] == 4096:
                return np.frombuffer(self.at(i), np.uint8).reshape(64, 64)
        raise KeyError(name)
