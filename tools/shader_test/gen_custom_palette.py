#!/usr/bin/env python3
"""Genere custom_palette.json : la palette vanilla avec 7 slots redondants
reaffectes a une rampe chair/beige.

Analyse (OKLab, 21/07/2026) qui a guide les choix :
- Tons chair les moins couverts par la palette vanilla : peau pale 0.052
  (quantifiee vers un GRIS -> visages delaves), medium 0.042, claire 0.030.
- Slots quasi doublons (dist OKLab vers leur plus proche voisin) :
  14 fffcf5 ~ 8 ffffff (0.013), 9 a4a8b8 ~ 6 a7a7a7 (0.024),
  50 8e3c2e ~ 28 993333 (0.028), 41 677535 ~ 27 667f33 (0.031),
  13 8f7748 ~ 10 976d4d (0.033), 45 575c5c ~ 59 646464 (0.034),
  18 e5e533 ~ 30 faee4d (0.041).
La structure base x nuances {180,220,255,135} est CONSERVEE : mcmm lit ce
fichier tel quel via --palette (zero patch), et chaque base couvre 4 niveaux
de luminosite de sa teinte. La base = la version la plus CLAIRE de la teinte.

Relancer apres modif :  python3 gen_custom_palette.py  puis  gen_shaders.py
"""
import json
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
VANILLA = os.path.join(ROOT, "..", "src", "main", "resources",
                       "vanilla_map_colors.json")
OUT = os.path.join(ROOT, "custom_palette.json")

# Slots liberes -> nouvelles bases (rampe chair/beige, la nuance x135..x255
# de chaque base etend la couverture vers le sombre automatiquement).
OVERRIDES = {
    14: "#f8d5b8",  # peau pale chaude   (etait blanc-casse ~ blanc 8)
    9:  "#eab08a",  # peau claire peche  (etait gris bleute ~ gris 6)
    50: "#c98a62",  # peau caramel       (etait brique ~ brique 28)
    41: "#e6c4a5",  # beige clair        (etait olive ~ olive 27)
    13: "#9c7a5a",  # brun dore          (etait brun ~ brun 10)
    45: "#d9a08a",  # rose-terre         (etait gris fonce ~ gris 59)
    18: "#f2e0c8",  # ivoire chaud       (etait jaune ~ jaune 30)
}


def main():
    with open(VANILLA, encoding="utf-8") as f:
        base = json.load(f)["colorList"]
    merged = {i: OVERRIDES.get(int(i), h) for i, h in base.items()}
    # NOTE : pas de caractere diese dans le commentaire — le parseur minimal
    # de mcmm prendrait la ligne pour une entree de couleur d'id 0.
    doc = {
        "_comment": "Palette custom VMC : vanilla + 7 slots redondants "
                    "reaffectes a une rampe chair/beige (ids 9, 13, 14, 18, "
                    "41, 45, 50). Meme format que vanilla_map_colors.json ; "
                    "a pointer par palette-path dans config.yml. Les clients "
                    "SANS le resource pack VMC verront les couleurs vanilla "
                    "de ces ids (teintes fausses sur ces slots).",
        "colorList": merged,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=4)
        f.write("\n")
    print(f"OK: custom_palette.json ({len(OVERRIDES)} slots reaffectes)")


if __name__ == "__main__":
    main()
