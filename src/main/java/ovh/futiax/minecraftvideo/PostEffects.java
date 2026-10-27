package ovh.futiax.minecraftvideo;

import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

// Pousse le post-effect client (resource pack VMC, ex. minecraft:custom_palette) aux viewers de
// l'ecran par la commande vanilla /posteffect (26.3+) : add quand un joueur devient viewer,
// remove quand il sort du rayon, au stop et a la deconnexion.
//
// Le serveur ne saura JAMAIS si l'effet s'applique vraiment -- Mojang : « post effects exist
// on the client, not the server ». Sans le pack, la commande est un no-op silencieux et le
// joueur voit les teintes vanilla de la palette custom. C'est le trade-off assume du stade 2.
//
// L'effet d'un ecran est choisi a sa construction depuis le rendu de la session (effectFor) :
// post-effect en temps normal, l'effet du mur (DataWall) en rendu data (le decodeur du mur, sans
// lequel l'ecran n'est que du bruit). `applied` retient QUEL effet chaque joueur a recu, donc
// clear() retire le bon meme si le rendu a change depuis.
//
// `applied` est l'etat VOULU par joueur, pas l'etat envoye : chaque commande est planifiee sur
// le main thread (addViewer peut venir du thread de lecture, au spawn) et reverifie l'etat
// voulu au moment de partir. Sans ca, un add en file depuis le thread de lecture suivi d'un
// remove synchrone depuis le main (stop juste apres spawn) partirait a l'envers et laisserait
// l'effet colle sur le joueur.
public final class PostEffects {

    private final MinecraftVideoPlugin plugin;
    private final String configured;                    // post-effect, "" = desactive ou inutilisable
    private final boolean present;                      // commande /posteffect presente (26.3+)
    private final List<String> known;                   // ce que reset/clearAll retirent
    private final CommandSender sender;                 // feedback vanilla avale, sinon une ligne de log par viewer
    private final Map<UUID, String> applied = new ConcurrentHashMap<>();   // joueur -> effet voulu
    private final AtomicBoolean dataWarned = new AtomicBoolean();

    PostEffects(MinecraftVideoPlugin plugin, String configured) {
        this.plugin = plugin;
        String effect = configured == null ? "" : configured.trim();
        this.sender = plugin.getServer().createCommandSender(feedback -> { });
        // Paper enregistre les commandes vanilla sous minecraft:<nom>. Absente = serveur < 26.3.
        this.present = plugin.getServer().getCommandMap().getCommand("minecraft:posteffect") != null;
        // Le feedback de la commande est avale, donc un id mal forme ("Custom_Palette", espace...)
        // ne se verrait jamais : on le refuse ici, une fois, au chargement.
        boolean valid = !effect.isEmpty() && NamespacedKey.fromString(effect) != null;
        if (!effect.isEmpty() && !valid) {
            plugin.getLogger().warning("post-effect '" + effect + "' is not a valid namespaced id"
                    + " (expected something like minecraft:custom_palette); post-effect ignored.");
        } else if (valid && !present) {
            plugin.getLogger().warning("post-effect '" + effect + "' is configured but this server has no"
                    + " /posteffect command (Minecraft 26.3+ required); post effects disabled.");
        }
        this.configured = valid && present ? effect : "";
        Set<String> known = new LinkedHashSet<>();
        if (!this.configured.isEmpty()) {
            known.add(this.configured);
        }
        for (RenderMode.DataWall wall : RenderMode.DataWall.values()) {
            known.add(wall.effect());
        }
        this.known = List.copyOf(known);
    }

    // L'effet a pousser aux viewers d'un ecran de ce rendu, "" = aucun. Le rendu data ne marche
    // pas sans le sien : sans /posteffect la video part quand meme (le joueur peut l'appliquer
    // lui-meme), avec UN warning par demarrage du plugin, pas un par lecture.
    public String effectFor(RenderMode mode, RenderMode.DataWall wall) {
        if (mode != RenderMode.DATA) {
            return configured;
        }
        if (!present && dataWarned.compareAndSet(false, true)) {
            plugin.getLogger().warning("render-mode data: this server has no /posteffect command"
                    + " (Minecraft 26.3+); players must apply the " + wall.effect()
                    + " post effect themselves, or they will only see noise.");
        }
        return present ? wall.effect() : "";
    }

    // effect = effectFor(...) de l'ecran. Un autre effet encore voulu (ne devrait pas arriver :
    // un seul ecran a la fois, et destroy() fait clear) est retire au passage.
    public void apply(Player player, String effect) {
        if (effect.isEmpty()) {
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

    // A la connexion : remove inconditionnel. Le serveur PERSISTE les post-effects dans le NBT du
    // joueur (ServerPlayer, cle post_effects) et renvoie la liste complete au client apres le
    // PlayerJoinEvent : un effet laisse par une coupure nette (crash, kill -9 apres autosave)
    // reviendrait sinon a chaque session, sans video. On retire l'id CONFIGURE et ceux des murs
    // du rendu data (le rendu a pu changer depuis) : trois commandes par join, pas de cout.
    // Limite assumee : un effet persiste sous un ancien post-effect (config changee entre deux
    // demarrages) reste -- `clear` le ferait mais balaierait aussi les effets d'autres
    // plugins/datapacks.
    public void reset(Player player) {
        if (present) {
            applied.remove(player.getUniqueId());
            for (String effect : known) {
                dispatch(player, effect, false);
            }
        }
    }

    // Filet de securite de onDisable : on est sur le main thread et une tache planifiee ne
    // tournerait plus, donc tout part synchrone -- et pour TOUS les joueurs en ligne, pas
    // seulement ceux encore dans `applied` : un remove mis en file par un destroy() du thread de
    // lecture dans le meme tick a deja quitte `applied` et sera annule avec le plugin. Un remove
    // de trop est un no-op cote serveur (List.remove), une commande par joueur et par id connu,
    // une fois.
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
    // selecteur marque un UUID brut includesEntities=true (il pourrait viser n'importe quelle
    // entite) -> ERROR_ONLY_PLAYERS_ALLOWED, avale avec le reste du feedback. Verifie dans le
    // bytecode 26.3 (EntitySelectorParser.parseNameOrUUID / EntityArgument.parse).
    private void dispatch(Player player, String effect, boolean on) {
        Runnable task = () -> {
            // etat voulu relu ici : une commande depassee par l'appel inverse ne part pas. Par
            // id : le remove d'un ancien effet part meme si un autre est voulu entre-temps.
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

    // Un pseudo vanilla passe tel quel dans le lecteur Brigadier ; un pseudo Floodgate avec un
    // espace ou un caractere hors [0-9A-Za-z_.+-] doit etre cite.
    private static String quote(String name) {
        if (name.matches("[0-9A-Za-z_.+-]+")) {
            return name;
        }
        return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
