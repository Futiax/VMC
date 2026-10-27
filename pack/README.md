# Resource pack VMC

Post effects client de VMC, embarqués dans le jar au build et poussés aux joueurs à la connexion
par `ResourcePackService`.

Ce dossier **est la source servie**, mais il est lui-même **généré** : les `.fsh` et les JSON de
post effect sortent de `tools/shader_test/gen_dct.py` (et des autres générateurs à côté). Ne les
édite pas à la main — régénère-les, sinon la prochaine passe du générateur écrase tes
modifications.

`build.sh` zippe ce dossier en `src/main/resources/pack.zip` (zip déterministe : mtimes
normalisées et entrées triées, pour que le SHA-1 ne change que si le contenu change — les clients
cachent le pack par son hash). Le zip généré n'est pas à commiter.

## Structure

```
pack/
  pack.mcmeta
  assets/minecraft/post_effect/<nom>.json        <- definitions des post effects
  assets/minecraft/shaders/post/<nom>.fsh        <- les programmes qu'elles referencent
  assets/minecraft/shaders/core/text.fsh         <- override de core shader (voir plus bas)
  assets/minecraft/textures/effect/...
```

### `post_effect` ou `post_effects` ?

Le wiki se contredit : sa section « format » documente `post_effect/` (singulier), son historique
affirme que 1.21.2 l'a renommé en `post_effects/` (pluriel). Le singulier est ce que `gen_dct.py`
écrit et ce qui fonctionnait en jeu, donc c'est la source. **`build.sh` miroite le dossier vers le
pluriel au moment du zip**, pour servir les deux noms sans parier — le miroir est fait au build et
pas ici, afin que le générateur reste la source unique.

## Ids poussés par le serveur

Un nom de fichier qui ne correspond pas à l'id poussé rend `/posteffect add` **silencieusement
no-op** côté client : rien dans les logs, juste du bruit à l'écran.

| Source serveur | Id poussé | Fichier attendu |
|---|---|---|
| `RenderMode.DataWall.WAN` | `minecraft:vmc_data` | `post_effect/vmc_data.json` |
| `RenderMode.DataWall.LAN` | `minecraft:vmc_data_lan` | `post_effect/vmc_data_lan.json` |
| clé `post-effect` du `config.yml` | ce que tu y mets | `post_effect/<ce nom>.json` |

## `shaders/core/text.fsh`

Override de core shader qui **retire le fog du render type text**, parce que le
`fog_start_distance = 0` de vanilla décale les texels hors de la palette 8 bits exacte que le
décodage attend (0,5 % à 5 blocs suffit).

À savoir : le render type text est partagé avec **tout le texte du jeu** — chat, panneaux,
nametags, scoreboard. Cet override change donc leur rendu de fog pour *tous* les joueurs, pas
seulement les spectateurs de l'écran, puisque le pack est désormais poussé automatiquement à la
connexion.

## Versions

`pack.mcmeta` déclare `min_format` **[97, 1]** (= 26.3) et `max_format` **[98, 0]** : un peu de
marge pour un patch 26.3.x, sans ouvrir la porte en grand.

⚠️ La limite haute est un garde-fou, pas une formalité. Le snapshot 2 de 26.4 renomme
`screenquad.vsh` en `screentriangle.vsh`, et les 93 passes d'ici référencent
`minecraft:core/screenquad`. Sur une version qui porte ce renommage, un pack accepté donnerait
des passes en échec — du bruit à l'écran plutôt qu'un refus lisible. Le numéro de format de 26.4
n'est pas connu ici : s'il tombe en 98.x, il faudra redescendre `max_format` à `[97, 1]` ou
générer un variant 26.4.

Si ce dossier ne contient que ce README, le jar part sans pack et le plugin le dit au démarrage :
tout marche sauf `render-mode: data`.
