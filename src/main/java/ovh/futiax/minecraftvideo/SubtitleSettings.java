package ovh.futiax.minecraftvideo;

// Geometrie de l'overlay sous-titres, reglee par /video option sub ... et persistee dans
// config.yml. Lue au build de l'ecran donc un changement s'applique au prochain /video play
// (comme les options de taille d'ecran et d'audio).
//   scale            = multiplicateur de taille du texte (subtitle-size)
//   heightAboveEdge  = blocs au dessus du bord bas de l'ecran (subtitle-height), la control
//                      bar rajoute son propre degagement par dessus si elle est la
//   depthInFront     = blocs devant la surface (subtitle-depth), 0 = pile sur le plan,
//                      une petite valeur evite le z-fighting
public record SubtitleSettings(float scale, double heightAboveEdge, double depthInFront) {
}
