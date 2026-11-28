package ovh.futiax.mcdata.poc;

import org.bukkit.entity.Player;

// Un POC (proof of concept) branche sur le canal MCDATA. Chaque POC a un nom unique, un post-effect
// a pousser aux joueurs qui l'utilisent, et demarre/arrete son feed de donnees sur le channel.
//
// Cycle de vie :
//   start(player)  -> enregistre le feed dans le BeaconChannel + applique le post-effect
//   stop(player)   -> retire le feed + clear le post-effect
//   onPlayerJoin   -> si le POC etait actif pour ce joueur avant deco, le relance
//   onPlayerQuit   -> arrete proprement
public interface Poc {

    // Nom unique du POC (ex. "probe", "video", "doom"). Sert de cle dans le channel et les
    // commandes /mcdata <nom>.
    String name();

    // Post-effect a pousser (ex. "minecraft:mcdata_probe"). Vide = aucun.
    String postEffect();

    // Description courte pour /mcdata list.
    String description();

    // Demarre le POC pour ce joueur.
    void start(Player player);

    // Arrete le POC pour ce joueur.
    void stop(Player player);

    // Le POC tourne-t-il pour ce joueur ?
    boolean isRunning(Player player);

    // Relance au join (par defaut : rien, les POCs stateless ne survivent pas a une deco).
    default void onPlayerJoin(Player player) {}

    // Nettoyage au quit (par defaut : stop).
    default void onPlayerQuit(Player player) {
        stop(player);
    }
}
