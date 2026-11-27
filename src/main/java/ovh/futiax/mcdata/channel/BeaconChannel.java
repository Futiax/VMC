package ovh.futiax.mcdata.channel;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import org.bukkit.Location;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Gere une balise par joueur et la boucle d'envoi periodique. Chaque POC actif enregistre un
// fournisseur de payload (valeurs 16 bits) ; a chaque tick, le channel concatene les payloads
// de tous les POCs actifs et envoie la map-data.
//
// Un seul channel par joueur : la balise est unique, les POCs partagent le payload.
public final class BeaconChannel {

    // Un POC actif sur un joueur : fournit son payload a chaque tick.
    public interface Feed {
        // Renvoie les valeurs 16 bits a ecrire dans la balise (apres le header).
        int[] payload(int counter);
    }

    private record Session(Beacon.Handle beacon, BukkitTask task, Map<String, Feed> feeds) {}

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final long periodTicks;

    public BeaconChannel(org.bukkit.plugin.java.JavaPlugin plugin, int ratePerSecond) {
        this.plugin = plugin;
        this.periodTicks = Math.max(1, 20L / Math.max(1, ratePerSecond));
    }

    public boolean isActive(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    // Demarre le channel pour un joueur (ou ne fait rien s'il tourne deja).
    public synchronized void start(Player player) {
        if (sessions.containsKey(player.getUniqueId())) {
            return;
        }
        Beacon.Handle beacon = Beacon.attach(player);
        Map<String, Feed> feeds = new HashMap<>();
        int[] counter = {0};
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                stop(player);
                return;
            }
            int[] payload = collectPayload(feeds, counter[0]);
            Location loc = player.getLocation();
            byte[] pixels = DataEncoder.encode(counter[0], loc.getYaw(), loc.getPitch(), payload);
            Beacon.send(player, beacon, pixels);
            counter[0]++;
        }, 0L, periodTicks);
        sessions.put(player.getUniqueId(), new Session(beacon, task, feeds));
    }

    // Arrete le channel et detache la balise.
    public synchronized void stop(Player player) {
        Session s = sessions.remove(player.getUniqueId());
        if (s == null) {
            return;
        }
        s.task().cancel();
        Beacon.detach(player, s.beacon());
    }

    public synchronized void stopAll() {
        for (UUID id : List.copyOf(sessions.keySet())) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                stop(player);
            } else {
                sessions.remove(id).task().cancel();
            }
        }
    }

    // Enregistre un feed (POC) pour un joueur. Demarre le channel si necessaire.
    public synchronized void addFeed(Player player, String pocName, Feed feed) {
        start(player);
        sessions.get(player.getUniqueId()).feeds().put(pocName, feed);
    }

    // Retire un feed. Si plus aucun feed, arrete le channel.
    public synchronized void removeFeed(Player player, String pocName) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) {
            return;
        }
        s.feeds().remove(pocName);
        if (s.feeds().isEmpty()) {
            stop(player);
        }
    }

    public synchronized boolean hasFeed(Player player, String pocName) {
        Session s = sessions.get(player.getUniqueId());
        return s != null && s.feeds().containsKey(pocName);
    }

    private static int[] collectPayload(Map<String, Feed> feeds, int counter) {
        int total = 0;
        int[][] parts = new int[feeds.size()][];
        int i = 0;
        for (Feed f : feeds.values()) {
            parts[i] = f.payload(counter);
            total += parts[i].length;
            i++;
        }
        int[] out = new int[total];
        int at = 0;
        for (int[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
