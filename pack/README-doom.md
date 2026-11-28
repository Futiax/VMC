# Resource pack DOOMMC

Shaders core et post effects, embarqués dans le jar au build (`make-pack.sh` → `src/main/resources/pack.zip`)
et poussés aux joueurs à la connexion par `ResourcePackService`.

Ces fichiers sont **générés** : ne pas les éditer à la main.
- `tools/doom/gen_probe.py` (`make shaders`) : `core/text.*`, `post/doom_probe.fsh`, `post_effect/doom_probe.json`.
- `tools/doom/gen_level.py` (`make level`, lit le WAD Freedoom) : `textures/effect/doom_*.png`,
  `post/doom_decode.fsh`, `post/doom_view.fsh`, `post_effect/doom_view.json`. Données Freedoom : voir `FREEDOOM-LICENSE.txt`.

Vérifier un rendu hors jeu (vrai moteur, `make engine` avant) :
`python3 tools/doom/render_check.py YAW PITCH sortie.png [TICKS] [BOUTONS]`.
