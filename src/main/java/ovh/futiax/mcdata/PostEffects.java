package ovh.futiax.mcdata;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Pousse les post effects du pack (ex. minecraft:mcdata_probe) par la commande vanilla
// /posteffect (26.3+, cote serveur, par joueur). Le serveur PERSISTE les effets dans le NBT du
// joueur : reset au join, clear au quit. Chaque commande est replanifiee sur le main thread et
// relit l'etat voulu avant de partir, pour qu'un add et un remove rapproches ne partent pas a
// l'envers.
public final class PostEffects {

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final boolean present;                      // commande /posteffect presente (26.3+)
    private final List<String> known;                   // ce que reset/clearAll retirent
    private final CommandSender sender;                 // feedback vanilla avale
    private final Map<UUID, String> applied = new ConcurrentHashMap<>();   // joueur -> effet voulu

    public PostEffects(org.bukkit.plugin.java.JavaPlugin plugin, List<String> known) {
        this.plugin = plugin;
        this.sender = plugin.getServer().createCommandSender(feedback -> { });
        // Paper enregistre les commandes vanilla sous minecraft:<nom>. Absente = serveur < 26.3.
        this.present = plugin.getServer().getCommandMap().getCommand("minecraft:posteffect") != null;
        if (!present) {
            plugin.getLogger().warning("This server has no /posteffect command (Minecraft 26.3+ required):"
                    + " players must apply the post effects themselves.");
        }
        this.known = List.copyOf(known);
    }

    // Un autre effet encore voulu pour ce joueur est retire au passage.
    public void apply(Player player, String effect) {
        if (!present || effect.isEmpty()) {
            return;
        }
        String previous = applied.put(player.getUniqueId(), effect);
        if (effect.equals(previous)) {
            return;
        }
        if (previous != null) {
            dispatch(player, previous, false);
        }
        dispatch(player, effect, true);
    }

    public void clear(Player player) {
        String effect = applied.remove(player.getUniqueId());
        if (effect != null) {
            dispatch(player, effect, false);
        }
    }

    // A la connexion : remove inconditionnel de chaque effet connu (le serveur renvoie au client,
    // apres le PlayerJoinEvent, les effets persistes par une coupure nette).
    public void reset(Player player) {
        if (present) {
            applied.remove(player.getUniqueId());
            for (String effect : known) {
                dispatch(player, effect, false);
            }
        }
    }

    // onDisable, sur le main thread : synchrone, pour tous les joueurs en ligne.
    public void clearAll() {
        if (!present) {
            return;
        }
        applied.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            for (String effect : known) {
                dispatch(player, effect, false);
            }
        }
    }

    // Cible par NOM, pas par UUID : /posteffect prend EntityArgument.players(), et le parseur de
    // selecteur marque un UUID brut includesEntities=true -> ERROR_ONLY_PLAYERS_ALLOWED.
    private void dispatch(Player player, String effect, boolean on) {
        Runnable task = () -> {
            if (!player.isOnline() || effect.equals(applied.get(player.getUniqueId())) != on) {
                return;
            }
            plugin.getServer().dispatchCommand(sender, "minecraft:posteffect " + (on ? "add " : "remove ")
                    + quote(player.getName()) + " " + effect);
        };
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (IllegalStateException | IllegalPluginAccessException e) {
            // plugin en cours de desactivation : clearAll() sur le main thread prend le relais
        }
    }

    private static String quote(String name) {
        if (name.matches("[0-9A-Za-z_.+-]+")) {
            return name;
        }
        return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
