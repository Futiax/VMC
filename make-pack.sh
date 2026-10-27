#!/usr/bin/env bash
# Zippe pack/ en src/main/resources/pack.zip, le resource pack que le jar embarque et que
# ResourcePackService pousse aux joueurs.
#
# Appele par ./build.sh ET par la CI (.github/workflows/build.yml) : les deux doivent produire
# le MEME zip, donc la logique vit ici et nulle part ailleurs. La CI lance `mvn package`
# directement, et sans cette etape le jar de release partirait sans pack.
#
# Zip DETERMINISTE, et il faut les trois :
#   -X                 pas de metadonnees d'extension (uid/gid, dates supplementaires)
#   entrees triees     l'ordre de find n'est pas garanti d'un systeme a l'autre
#   mtimes normalisees  sinon un clone frais (la CI en fait un a chaque run) redate tout et
#                      change le hash. On zippe depuis une COPIE, les sources ne sont pas touchees.
# Le SHA-1 du zip est ce que le client utilise pour valider son telechargement : s'il bouge a
# contenu identique, tout le monde re-telecharge pour rien.
#
# Le dossier des post effects est miroite en post_effect/ ET post_effects/ : le wiki se
# contredit sur le nom (sa section format dit le singulier, que gen_dct.py ecrit et qui tournait
# en jeu ; son historique dit que 1.21.2 l'a renomme au pluriel). Un dossier inconnu est ignore
# par le client, ca coute ~50 Ko, et ca evite de parier. Le miroir est fait ICI et pas dans
# pack/ pour que gen_dct.py reste la source unique : une copie commitee deviendrait
# silencieusement perimee a la prochaine regeneration.
set -euo pipefail
cd "$(dirname "$0")"

PACK_RES=src/main/resources/pack.zip
rm -f "$PACK_RES"

if [ ! -d pack ] || [ -z "$(find pack -type f ! -name 'README*' -print -quit)" ]; then
    echo "pack: aucun contenu dans pack/, le jar partira sans resource pack"
    exit 0
fi

command -v zip >/dev/null || { echo "pack: 'zip' introuvable, impossible de construire le pack" >&2; exit 1; }

staged=$(mktemp -d)
trap 'rm -rf "$staged"' EXIT

( cd pack && find . -type f ! -name 'README*' -exec cp --parents {} "$staged/" \; )

if [ -d "$staged/assets/minecraft/post_effect" ]; then
    cp -r "$staged/assets/minecraft/post_effect" "$staged/assets/minecraft/post_effects"
    echo "pack: post_effect/ miroite vers post_effects/ ($(find "$staged/assets/minecraft/post_effects" -type f | wc -l) fichiers)"
fi

find "$staged" -exec touch -t 198001010000 {} +
( cd "$staged" && find . -type f | LC_ALL=C sort | zip -q -X -9 "$OLDPWD/$PACK_RES" -@ )

echo "pack: $PACK_RES ($(find "$staged" -type f | wc -l) fichiers, sha1 $(sha1sum "$PACK_RES" | cut -d' ' -f1))"
