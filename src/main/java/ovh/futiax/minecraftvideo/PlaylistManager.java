package ovh.futiax.minecraftvideo;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// File d'attente : un FIFO de sources qui attendent derriere la lecture en cours.
// MAIN THREAD UNIQUEMENT (commandes + taches du scheduler), le seul point d'entree
// cross-thread est scheduleAdvance() qui saute sur le main thread.
//
// Enchainement auto : MinecraftVideoPlugin.clearSession appelle scheduleAdvance() des que la
// session active se termine, peu importe la raison (EOF normal, erreur, /video stop, /video
// skip). "stop" vide la file AVANT de stopper pour que stop stoppe vraiment ; "skip" et l'EOF
// la laissent intacte et l'item suivant demarre.
//
// Un item en file reprend l'ancre de la session qui jouait au moment du add, comme ca l'ecran
// ne bouge pas entre deux episodes ; un item mis en file a vide prend la position de celui qui
// l'ajoute (meme semantique que /video play). Taille d'ecran, fps et audio sont lus dans les
// options persistees au DEMARRAGE de l'item, comme un /video play sans arguments.
public final class PlaylistManager {

    public record QueueItem(String source, UUID initiatorId, String initiatorName,
                            Location anchor) {}      // quoi jouer, ou, et qui a demande

    private final MinecraftVideoPlugin plugin;
    private final List<QueueItem> queue = new ArrayList<>();

    public PlaylistManager(MinecraftVideoPlugin plugin) {
        this.plugin = plugin;
    }

    // ajoute une source, renvoie sa position dans la file (base 1)
    public int add(String source, Player initiator) {
        PlaybackSession active = plugin.getActiveSession();
        Location anchor = active != null ? active.getAnchor()
                : initiator.getLocation().clone();
        queue.add(new QueueItem(source, initiator.getUniqueId(),
                initiator.getName(), anchor));
        // Une reference de cache par occurrence en file : le fichier survit tant qu'une
        // occurrence est encore en file ou en lecture. La reference est TRANSFEREE a la
        // session quand advance() joue l'item (c'est la session qui release).
        plugin.getMediaCache().reference(source);
        return queue.size();
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public void clear() {
        for (QueueItem item : queue) {      // release avant de tout jeter
            plugin.getMediaCache().release(item.source());
        }
        queue.clear();
    }

    // retire la position (base 1), renvoie l'item retire ou null
    public QueueItem remove(int position) {
        if (position < 1 || position > queue.size()) {
            return null;
        }
        QueueItem removed = queue.remove(position - 1);
        plugin.getMediaCache().release(removed.source()); // occurrence jetee sans avoir joue
        return removed;
    }

    // listing numerote pour /video queue list
    public List<String> describe() {
        List<String> lines = new ArrayList<>(queue.size());
        for (int i = 0; i < queue.size(); i++) {
            QueueItem item = queue.get(i);
            lines.add((i + 1) + ". " + shorten(item.source())
                    + " (queued by " + item.initiatorName() + ")");
        }
        return lines;
    }

    static String shorten(String source) {
        return source.length() <= 60 ? source
                : "..." + source.substring(source.length() - 57);
    }

    // Saute sur le main thread et lance l'item suivant si rien ne joue.
    // Appelable depuis n'importe quel thread, no-op pendant le disable du plugin.
    public void scheduleAdvance() {
        if (queue.isEmpty() || !plugin.isEnabled()) {
            return; // isEmpty est un pre-check racy, advance() reverifie sur le main
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, this::advance);
        } catch (IllegalStateException | IllegalPluginAccessException e) {
            // plugin en train de se desactiver entre le check et le schedule, rien a lancer
        }
    }

    // Demarre l'item suivant si on est idle. Main thread uniquement.
    public void advance() {
        if (queue.isEmpty() || plugin.getActiveSession() != null) {
            return;
        }
        // Le remove(0) transfere la reference de cache de cette occurrence a la session qui
        // va la jouer (la session release en fin de vie). Sortir d'ici sans avoir lance de
        // session, c'est donc devoir s'occuper de la ref soi-meme : la lacher (mcmm manquant)
        // ou la laisser suivre l'item qu'on remet en file (course perdue).
        QueueItem item = queue.remove(0);
        String mcmmPath = plugin.resolveMcmmPath();
        String palettePath = plugin.resolvePalettePath();
        if (mcmmPath == null || palettePath == null) {
            plugin.getLogger().warning("Skipping queued video (mcmm or palette missing): "
                    + item.source());
            plugin.getMediaCache().release(item.source()); // pas joue -> on lache la ref
            advance(); // au suivant
            return;
        }
        int w = plugin.getConfig().getInt("default-width", 4);
        int h = plugin.getConfig().getInt("default-height", 3);
        int fps = plugin.getConfig().getInt("default-fps", 10);
        PlaybackSession session = new PlaybackSession(plugin, item.initiatorId(),
                item.initiatorName(), item.anchor().clone(), mcmmPath, palettePath,
                item.source(), w, h, fps, plugin.buildAudioSettings());
        if (!plugin.trySetActiveSession(session)) {
            queue.add(0, item); // perdu la course contre un /video play manuel, on remet en file
            return;
        }
        session.start(plugin.getServer().getOnlinePlayers());
        Player initiator = plugin.getServer().getPlayer(item.initiatorId());
        if (initiator != null) {
            initiator.sendMessage("[MinecraftVideo] Now playing from the queue: "
                    + shorten(item.source())
                    + (queue.isEmpty() ? "" : " (" + queue.size() + " more queued)"));
        }
    }
}
