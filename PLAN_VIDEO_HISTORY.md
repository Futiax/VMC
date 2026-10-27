# Plan : compression vidéo inter-frame pour le mode data

*Révisé le 13/10 après relecture. La version d'origine commençait par « frame complète +
macroblocs SKIP + historique GPU en ping-pong ». Trois points la rendaient inapplicable (voir
« Écarté, et pourquoi »). Nouvel ordre : gagner du débit sans toucher au client, gagner en
qualité par image, vérifier la persistance en jeu, et seulement ensuite les résidus.*

## Contraintes du système

Elles décident de tout le reste :

- **Aucun code client.** Pack de ressources + plugin Paper. Le plugin envoie des paquets et des
  commandes (`/posteffect`), rien d'autre. Un mod client rendrait le mur inutile : on enverrait
  directement du H.264 par un canal plugin.
- **Le post effect tourne à chaque frame de rendu** (60 à 144 fps), la vidéo à 20 fps au plus.
  Tout ce qui ne doit s'appliquer qu'une fois par frame vidéo doit le savoir par lui-même.
- **Le décodeur lit le mur à travers la caméra.** Il rate des frames dès que le mur n'est pas
  lisible : le joueur regarde ailleurs, quelqu'un passe devant, le chat ou la hotbar couvrent le
  mur, F1, F3+T, menu.
- **Aucun retour client → serveur.** Le serveur ne sait jamais ce que le client a décodé.
- **Réseau** (`VirtualScreen`) : les tuiles d'une frame partent dans un bundle (appliquées dans le
  même tick) ; les tuiles inchangées ne sont pas renvoyées ; sous saturation un joueur saute des
  frames (`missed`) puis reçoit le DERNIER contenu des tuiles manquées.
- **Débit fixe par macrobloc** : 496 bits à une place fixe du mur. Les bits d'un macrobloc ne
  peuvent pas servir à un autre.
- **Deux murs** (`use_wall()` dans `gen_dct.py`) : WAN 7×4 (canva 848×464) et LAN 10×6
  (canva 1232×720). Tout ce qui suit vaut pour les deux.

## Références du client Minecraft 26.3

Jar vanilla utilisé :

```text
/home/nath/.local/share/PrismLauncher/libraries/com/mojang/minecraft/26.3/minecraft-26.3-client.jar
```

Le jar ne contient pas les sources Java. Les références ci-dessous sont donc indiquées par chemin de classe, méthode et offsets bytecode obtenus avec `javap`.

| Classe | Méthode | Rôle |
|---|---|---|
| `net/minecraft/client/renderer/PostChain.class` | `addToFrame(...)` | Crée ou importe les targets et câble les passes |
| `net/minecraft/client/renderer/PostChain.class` | `process(...)` | Construit puis exécute le `FrameGraph` |
| `net/minecraft/client/renderer/PostChain.class` | `getOrCreatePersistentTarget(...)` | Conserve ou crée une target persistante |
| `net/minecraft/client/renderer/PostChain.class` | `closePersistentTargets()` | Détruit les targets persistantes |
| `net/minecraft/client/renderer/PostPass.class` | `addToFrame(...)` | Déclare les lectures et écritures d'une passe |
| `net/minecraft/client/renderer/PostChainConfig$InternalTarget.class` | `persistent()` | Active la persistance d'une target JSON |
| `com/mojang/blaze3d/pipeline/RenderTarget.class` | `destroyBuffers()` | Détruit les textures GPU |
| `com/mojang/blaze3d/pipeline/RenderTarget.class` | `copyColorFrom(...)` | Copie une texture couleur vers une autre target |

### `PostChain.addToFrame(...)`

Offsets bytecode importants :

```text
0-22       mise à jour de la projection
52-104     import des targets externes
107-301    création des targets internes ou persistantes
235-274    traitement de persistent = true
277-298    traitement de persistent = false
301-347    ajout des PostPass au FrameGraph
347-404    remplacement des targets externes dans le TargetBundle
```

Pour une target persistante, Minecraft exécute conceptuellement :

```java
RenderTarget target =
    getOrCreatePersistentTarget(id, descriptor);

frameGraph.importExternal(id.toString(), target);
```

Pour une target temporaire :

```java
frameGraph.createInternal(id.toString(), descriptor);
```

### `PostChain.process(...)`

Offsets bytecode :

```text
0-6        création du FrameGraphBuilder
8-22       import de minecraft:main
24-38      appel de addToFrame(...)
40-44      FrameGraphBuilder.execute(...)
```

### `PostChain.getOrCreatePersistentTarget(...)`

Cette méthode conserve les targets dans :

```java
Map<Identifier, RenderTarget> persistentTargets
```

La target est réutilisée tant que ses dimensions ne changent pas. Elle est détruite uniquement lorsque :

- sa taille change ;
- le post-chain est fermé ;
- `closePersistentTargets()` est appelé.

Conséquence : chaque `PostChain` a SES targets persistantes. Deux effets différents ne partagent
pas d'historique.

Le codec de `PostChainConfig$InternalTarget` lit `width`, `height`, `persistent`, `clear_color`.

## Étape 1 : maintien des macroblocs inchangés (débit, serveur seul)

Le serveur garde les symboles PRÉCÉDENTS d'un macrobloc qui a trop peu changé. Le client
redécode ces symboles et retrouve exactement l'image d'avant : c'est le SKIP, sans bit de mode,
sans historique, sans changement du décodeur. Le saut des tuiles inchangées, déjà en place,
évite ensuite de renvoyer les tuiles entièrement maintenues.

```text
pour chaque macrobloc :
    erreur = écart entre la source actuelle et la source du DERNIER ENVOI de ce macrobloc
    si erreur <= seuil : garder les symboles précédents
    sinon             : encoder, et mémoriser cette source comme nouvelle référence
```

Comparer à la source du dernier envoi, pas à la frame précédente : sinon une dérive lente, sous
le seuil à chaque frame, ne serait jamais renvoyée.

Compatible avec tout l'existant : un seek relance `mcmm` (état remis à zéro), un joueur qui
arrive reçoit le mur courant, un joueur saturé reçoit le dernier contenu des tuiles manquées —
toujours de l'intra.

Où :

- `gen_dct.py` : `encode_video(frames, seuil)`, la référence (séquence d'images → séquence de murs) ;
- `mcmm.c` : `data_frame_to_ids()` garde d'une frame à l'autre les symboles et la source de
  référence de chaque macrobloc ; option `--hold <seuil>` ;
- le seuil : à régler sur de vraies vidéos.

Ensuite, possible : renvoyer seulement le rectangle modifié d'une tuile
(`WrapperPlayServerMapData` accepte columns/rows/x/z) au lieu de la tuile entière.

Tests :

- caméra fixe → presque tout maintenu ; changement de plan → tout renvoyé ; petit mouvement →
  seuls les macroblocs touchés ;
- `--mcmm` étendu aux séquences : `mcmm` = Python octet pour octet, frame par frame ;
- mesure : part des tuiles renvoyées sur 2 ou 3 vraies vidéos (plan fixe, film, gameplay
  Minecraft), en WAN et en LAN.

Gain : le débit, sur les plans fixes ou en partie fixes. Rien sur la qualité.

### Fait le 13/10 — résultat : peu de gain, puis RETIRÉ avec l'étape 2

L'étape 2 a retenu la variante (b), incompatible avec le maintien : `--hold`, `data-hold` et
`/vmc hold` n'existent plus depuis le plugin 0.6.9. Ce qui suit reste la mesure qui a décidé.

`mcmm --hold <seuil>` ; plugin 0.6.8 : `data-hold` (défaut 4) et `/vmc hold`. `encode_video()` et
`demo_hold()` dans `gen_dct.py` ; `--mcmm` compare la séquence de test au C octet pour octet sur les
deux murs, et le résultat est identique. Deux précisions de conception :

- un macrobloc maintenu dont toutes les tuiles partent de toute façon est ré-encodé, ce qui ne coûte
  rien ;
- une tuile « part » si un de ses OCTETS change, pas dès qu'un macrobloc qui la touche change :
  un macrobloc à cheval sur deux tuiles peut ne changer que d'un côté.

Mesure avec `--hold-stats` (devenu `python3 gen_dct.py --net-stats <mcmm> <vidéo> [début]`), sur 30 s à 10 fps, débit
compté après zlib niveau 6 (la compression des paquets de Paper) :

| Vidéo | Mur | Tuiles renvoyées (seuil 0 → 4) | Débit à 10 fps (Mbit/s) |
|---|---|---|---|
| Plan fixe (prof face caméra, 480p) | WAN | 58 % → 46 % | 4,7 → 4,2 |
| | LAN | 59 % → 46 % | 9,4 → 8,5 |
| Film (Elephants Dream, 720p) | WAN | 74 % → 68 % | 6,9 → 6,7 |
| | LAN | 72 % → 65 % | 14,4 → 14,0 |
| Minecraft en mouvement (*This is Create*, 480p) | WAN | 100 % → 99,8 % | 10,8 → 10,8 |
| | LAN | 100 % → 99,7 % | 22,2 → 22,1 |

Ce qu'on en tire :

- **Qualité : rien ne bouge au seuil 4** : de 0 à −0,08 dB (PSNR de la MSE moyenne), pire frame
  inchangée. Même au seuil 32, la perte reste de 0,1 à 0,5 dB. Attention à ne pas faire la moyenne
  des PSNR par frame : sur une frame presque noire, passer de 0,04 à 2 de MSE (invisible) « coûte »
  17 dB.
- **Débit : peu de gain.** −11 % sur le plan fixe, −3 % sur le film, rien sur le Minecraft en
  mouvement. Une tuile (1/28 de l'image en WAN) qui contient un mouvement repart entière. Et les
  tuiles maintenues sont les moins chères, des aplats que zlib écrasait déjà : −20 % de tuiles ne
  donne que −11 % de débit.
- **Le vrai débit est la moitié de l'estimation.** WAN : 4,7 à 10,8 Mbit/s à 10 fps, et non ~18.
  LAN : 9,4 à 22, et non ~38. zlib écrase les champs à zéro des images lisses. Les 8 Ko par tuile
  sont le pire cas (symboles aléatoires) ; les images de test donnent de 1,9 à 6,5 Ko. Les textes
  du plugin disent désormais « up to ~18 / ~38 ».
- **Conséquence pour l'étape 2.** Un codage à longueur variable remplit ces zéros de données
  utiles : le mur devient incompressible et le débit monte vers le pire cas. L'étape 2 achète de
  la qualité avec du débit.
- **Rectangle modifié : pas fait.** Le gain est borné pour la même raison : le mouvement occupe
  les tuiles qui changent. Et le ré-encodage gratuit étale les changements sur toute la tuile ; il
  faudrait le restreindre au rectangle.

## Étape 2 : codec à longueur variable (qualité par image)

Mesuré le 10/10 : à taille égale, un vrai JPEG fait +5 dB en moyenne (40,0 contre 34,9 sur les
6 images de test) ; une DST (sinus) fait pire que notre DCT. L'écart vient des cases de taille
fixe (un zéro coûte autant qu'une valeur, tout au-delà du 30e coefficient est jeté) et du budget
fixe par macrobloc.

Proposition : zigzag + suites de zéros + table de Huffman figée dans le shader, et une qualité
globale cherchée par dichotomie. Deux variantes à comparer en numpy avant d'écrire le GLSL :

- **(a)** budget fixe par macrobloc, codage variable à l'intérieur : les emplacements restent
  fixes, donc **compatible avec l'étape 1** ;
- **(b)** tailles variables + index des tailles (~2 % du budget) pour que chaque macrobloc sache
  où il commence : allocation globale, plus de gain attendu, mais **incompatible avec l'étape 1**
  — une taille qui change décale toutes les données suivantes, donc toutes les tuiles changent.

Attendu : +3 à +5 dB (le JPEG, moins le coût de l'index et de la table figée). Le codec reste
intra : aussi robuste qu'aujourd'hui.

Touche : `gen_dct.py` (`fields()`, `encode_values()`, `pack()`, `unpack()`, GLSL de
`vmc_dct_unpack`), `mcmm.c` (`data_encode_mb()`), `vmc_data.h` (généré). Mêmes tests
qu'aujourd'hui : `demo()`, `--gpu`, `--mcmm`.

### Fait le 13/10 — variante (b), au niveau d'un vrai JPEG

**Le prototype** (numpy, depuis supprimé), avec le Huffman standard de JPEG (annexe K). Sur 36
images (les 6 photos de test et 10 frames de chacune des 3 vidéos de l'étape 1), à capacité égale :

| | Actuel | (a) budget par macrobloc | (b) allocation globale | Vrai JPEG |
|---|---|---|---|---|
| PSNR WAN | 38,7 dB | 42,4 | **47,3** | 46,5 |
| PSNR LAN | 40,7 dB | 45,2 | **48,9** | 47,8 |
| Réseau WAN (Ko/frame) | 98 | 101 | 125 | |
| Réseau LAN (Ko/frame) | 202 | 205 | 242 | |

(a) gâche son budget : une image simple ne remplit ses macroblocs qu'à 35-60 %. (b) bat même
libjpeg, car il prend aussi les colonnes et lignes libres du mur (+3 à 4 % de capacité) et n'a pas
d'en-têtes JPEG. Le gain de l'étape 1 étant faible, (b) l'emporte.

**Le format** est décrit en tête de `gen_dct.py`. En résumé : q global sur 8 bits, le plus fin qui
tient (dichotomie ; q = 0 donne s = 0,099, plancher au-delà duquel le DC déborde) ; un index (début
de chaque ligne de macroblocs + longueur de chaque macrobloc, 2,2 % du mur) ; puis des blocs JPEG.
Il n'y a aucune dépendance entre macroblocs : DC du 1er bloc Y absolu, différences ensuite. Une
longueur mal lue ne fausse que la fin de sa ligne. Le flux remplit TOUTE la zone de données
(430×238 en WAN, 622×366 en LAN), et une image simple laisse des zéros que zlib écrase.

**Le décodeur GPU** : words → mbstart → blocks (début des AC et DC de chaque bloc) → coefs (2
coefficients par texel, chaque fragment relit son bloc jusqu'à eux) → rows et idct (IDCT
séparable, intermédiaire en 1/16 sur 16 bits). Il est identique à numpy à chaque étage, sur l'iGPU
comme sur la RX 9070 XT. Décodage sur RX 9070 XT : 0,8 ms (WAN), 0,9 ms (LAN) ; sur l'iGPU du
9800X3D : 4,9 et 10,4 ms (contre 2,7 et 3,5 avant), dominé par la passe coefs. Optimisation
possible si besoin : sauter les coefficients au-delà du dernier non nul de chaque bloc.

**Le codeur C** (mcmm) est identique au Python octet pour octet. La DCT du script suit l'ordre de
sommes de mcmm (`dct8`), car en longueur variable un seul coefficient de travers décale tout le
reste de sa ligne. Encodage : 4,5 ms par frame (WAN), 9,2 ms (LAN), 4 threads.

**Sur les 3 vidéos** (30 s à 10 fps, `--net-stats`), avant → après :

| Vidéo | Mur | PSNR | Débit à 10 fps (Mbit/s) |
|---|---|---|---|
| Plan fixe | WAN | 35,5 → **48,6** dB | 4,7 → 7,9 |
| | LAN | 39,2 → **51,0** | 9,4 → 14,5 |
| Film | WAN | 38,8 → **47,5** | 6,9 → 9,2 |
| | LAN | 40,9 → **48,9** | 14,4 → 17,9 |
| Minecraft en mouvement | WAN | 41,2 → **46,7** | 10,8 → 12,4 |
| | LAN | 43,5 → **48,9** | 22,2 → 22,9 |

Ces vidéos (480p et 720p) tiennent presque toujours au q le plus fin. Si un jour le débit compte
plus que les derniers dB, il suffit de remonter le plancher de q : moins de qualité au-delà de
~45 dB, plus de zéros pour zlib.

## Étape 3 : tester les targets persistantes en jeu

Avant tout résidu, un effet minimal `vmc_persist_test` :

```text
targets : counter (1×1, persistent), tmp (1×1)
passe 1 : counter -> +1 -> tmp
passe 2 : tmp -> copie -> counter
passe 3 : affiche la valeur (dégradé, ou chiffres du HUD)
```

À établir :

- la valeur survit-elle d'une frame à l'autre ?
- la passe 2 survit-elle au FrameGraph, alors que sa sortie n'est plus lue dans la frame ?
- `clear_color` ne s'applique-t-il qu'à la création ?
- que devient la target après F3+T, après `posteffect remove` puis `add`, après un
  redimensionnement de la fenêtre ?
- variante sans copie : une passe qui écrit directement dans la target persistante et fait
  `discard` sur la moitié des pixels — l'autre moitié garde-t-elle sa valeur (pas d'effacement
  avant la passe) ?

## Étape 4 : résidus (seulement si les étapes 1 à 3 le justifient)

Le seul usage qui demande vraiment un historique : `image = précédente + idct(delta)`. Gain
attendu : les parties fixes s'affinent de frame en frame ; faible sous un mouvement de caméra
(pas de compensation de mouvement).

Graphe, dans UNE seule chaîne, sans ping-pong ni code Java :

```text
coefs + history (frame précédente) -> decode -> img (temporaire)
img -> copie -> history (persistante)
img -> draw -> minecraft:main
```

Exigences :

- **numéro de frame par tuile** (quelques cellules dans chaque tuile) et une target d'état
  « dernier numéro appliqué, par tuile » : un delta ne doit s'appliquer qu'une fois, alors que le
  post effect tourne à chaque frame de rendu ;
- **ne jamais écrire l'historique depuis une lecture ratée** : mur détecté ET checksum par
  macrobloc (ou par tuile) ;
- **rafraîchissement intra continu** : quelques tuiles par frame en intra, chaque tuile au moins
  toutes les 1 à 2 s, plutôt qu'une keyframe complète (pic de débit). Le client se rattrape seul
  après n'importe quel raté, puisque le serveur n'en saura rien ;
- **saturation** : un joueur qui a raté des tuiles (`missed`) doit recevoir leur version intra →
  `mcmm` sort, pour chaque tuile modifiée, une version résidu et une version intra, et le plugin
  choisit par joueur ;
- **démarrage** : la target persistante part de `clear_color` → première image entièrement
  intra ; un joueur qui arrive en cours de route reçoit tout en intra (`addViewer` envoie déjà
  toutes les tuiles).

Tests : banc GPU multi-frames (`qr_glcheck.c` qui garde les targets persistantes d'une
exécution de la chaîne à la suivante) ; pertes simulées (tuiles manquées, regard détourné 2 s,
occlusion partielle) → retour à l'image juste en moins d'une période de rafraîchissement.

### Mesuré le 13/10 — pas justifié en l'état

Prototype numpy en flottant, donc borne haute (signalisation, checksums et historique entier
exact non comptés). Mêmes vidéos que l'étape 2, 15 s à 10 fps, mur WAN, plus une photo très
détaillée tenue 3 s. Rafraîchissement intra tournant : 1/30 des macroblocs par frame. Le q est le
plus fin qui tient, comme aujourd'hui.

- SKIP + intra : un macrobloc dont l'erreur n'a pas grossi de plus de 1,2× depuis son dernier
  codage est sauté (longueur 0, décodage idempotent).
- Résidus : par macrobloc, le moins cher entre intra et delta de coefficients ; un delta nul
  n'est pas envoyé.

| Séquence | Intra (actuel) | SKIP + intra | Résidus |
|---|---|---|---|
| Plan fixe | 47,4 dB, 416 kbit/frame | 47,3 dB, 281 (−32 %) | 47,6 dB, 268 (−36 %) |
| Film | 48,2 dB, 431 | 48,2 dB, 374 (−13 %) | 48,3 dB, 344 (−20 %) |
| Minecraft (Create) | 46,6 dB, 624 | 46,6 dB, 621 (0 %) | 46,6 dB, 605 (−3 %) |
| Photo (jacinthes), à 3 s | 25,9 dB, 787 | 31,1 dB, 116 | 31,4 dB, 383 |

Le mur n'est plus le goulot sur de la vraie vidéo. Avec (b), les deux murs tournent au q le plus
fin (q = 0, 47 à 50 dB) en n'utilisant que 44 à 79 % de leur capacité. La qualité bute sur ce
plancher et sur la source (480p par yt-dlp), pas sur les bits. L'inter-frame ne peut donc rien
ajouter en qualité ; il baisse seulement le débit, de 0 à 36 % selon le contenu, et pas du tout
sur du jeu. Le seul cas gagnant est une image si détaillée qu'elle sature le mur : +5 dB en 3 s.

Pour le débit, il existe un levier plus simple, côté serveur seul : un q minimum, où le serveur
s'arrête avant le plus fin. Le flux est donné en kbit par frame ; le débit réseau en vaut environ
2× par fps, car zlib ne compresse pas la duplication 2×2 des modules.

| q minimum | Plan fixe WAN | Film WAN | Create WAN |
|---|---|---|---|
| 0 (actuel) | 47,0 dB, 434 kbit | 49,5 dB, 347 | 47,6 dB, 548 |
| 32 | 45,1 dB, 328 (−24 %) | 47,0 dB, 259 (−25 %) | 45,8 dB, 384 (−30 %) |
| 64 | 42,5 dB, 249 (−43 %) | 44,4 dB, 194 (−44 %) | 43,5 dB, 271 (−51 %) |

En LAN, les proportions sont les mêmes (45 à 46 dB à q ≥ 64).

## Écarté, et pourquoi

| Idée de la version d'origine | Pourquoi |
|---|---|
| Ping-pong `history_a` / `history_b` câblé côté Java/plugin | `PostChain` est du code client ; le plugin tourne sur Paper. Il faudrait un mod, et avec un mod le mur n'a plus de raison d'être. |
| Deux chaînes `vmc_data_even` / `vmc_data_odd` alternées par le plugin | `persistentTargets` est une Map de `PostChain` : chaque chaîne a ses propres targets, l'autre ne voit pas l'historique. Et le serveur ne peut pas basculer d'effet en synchro avec les frames du client. |
| SKIP + historique comme premier prototype | Aucun gain de qualité (emplacements fixes) ; le gain de débit s'obtient côté serveur (étape 1). |
| En-tête de protocole dans la bande du bord | C'est la zone de détection (marge claire autour des repères). S'il faut un en-tête global, la zone de données a 6 colonnes et 6 lignes libres (~3 970 cellules en WAN, ~5 890 en LAN). Les numéros de frame des résidus vont, eux, dans chaque tuile. |
| « Désynchronisation détectée → frame FULL forcée » | Pas de canal client → serveur : remplacé par le rafraîchissement intra continu. |
| Client limité à 20 FPS | Impossible à imposer aux joueurs, n'aligne pas le rendu sur les ticks, et la détection doit tourner à chaque frame (le mur bouge à l'écran avec la tête). |

## Ordre de réalisation

1. Maintien des macroblocs dans `mcmm` (serveur seul) + mesure du débit économisé. **Fait le 13/10** :
   −3 à −11 % de débit sur film et plan fixe, rien sur du mouvement, aucune perte de qualité.
   Retiré avec l'étape 2.
2. Codec à longueur variable : variante (a) ou (b) selon le prototype numpy et selon le gain de
   l'étape 1. **Fait le 13/10 : (b)**, +5 à +13 dB, débit +3 à +68 %.
3. Test des targets persistantes en jeu. Effet `minecraft:vmc_persist_test` prêt le 13/10.
4. Résidus, si les étapes 1 à 3 le justifient. **Mesuré le 13/10 : pas justifié en l'état.**
   Le gain se limite au débit (0 à −36 %) ; pour ça, un q minimum côté serveur fait mieux.
