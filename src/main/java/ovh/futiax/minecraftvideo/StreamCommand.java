package ovh.futiax.minecraftvideo;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.List;

// /stream <url> | delay <s> | stop | status
//
// Volontairement pauvre : un direct N'EST PAS une video avec une option en plus. Pas de file
// visible (elle existe, mais c'est la plomberie de LiveStream : les tranches y passent toutes
// seules), pas de seek (naviguer dans un direct n'a pas de sens, et les tranches passees sont
// deja effacees). Tout le reste -- taille d'ecran, fps, dither, audio, sous-titres -- vient des
// memes options que /video, parce que c'est la meme lecture derriere.
public final class StreamCommand implements CommandExecutor, TabCompleter {

    private static final int MIN_DELAY = 5;     // en dessous, ffmpeg coupe a peine plus vite
                                                // que le raccord ne coute
    private static final int MAX_DELAY = 600;

    private final MinecraftVideoPlugin plugin;

    public StreamCommand(MinecraftVideoPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("[MinecraftVideo] /stream <url> | delay <s> | stop | status");
            return true;
        }
        String first = args[0].toLowerCase(java.util.Locale.ROOT);
        if (first.equals("delay")) {
            return delay(sender, args);
        }
        if (first.equals("stop")) {
            if (!plugin.isLive()) {
                sender.sendMessage("[MinecraftVideo] Aucun direct en cours.");
                return true;
            }
            plugin.stopLiveStream(null);
            sender.sendMessage("[MinecraftVideo] Direct arrete.");
            return true;
        }
        if (first.equals("status")) {
            LiveStream live = plugin.getLiveStream();
            if (live == null) {
                sender.sendMessage("[MinecraftVideo] Aucun direct en cours.");
                return true;
            }
            live.describe().forEach(line -> sender.sendMessage("[MinecraftVideo] " + line));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[MinecraftVideo] Seul un joueur peut lancer un direct"
                    + " (l'ecran se pose a sa position).");
            return true;
        }
        start(player, args[0]);
        return true;
    }

    // Duree d'une tranche = le seul reglage du direct, et il coupe dans les deux sens : long =
    // plus de retard sur le direct, court = un raccord (donc un trou de son) toutes les N
    // secondes. Persiste comme les options de /video, et s'applique au PROCHAIN /stream : le
    // -segment_time est fixe au demarrage de ffmpeg, le changer a chaud voudrait dire relancer
    // le decoupage, ce qui coute exactement un /stream stop suivi d'un /stream <url>.
    private boolean delay(CommandSender sender, String[] args) {
        int current = plugin.getConfig().getInt("stream-segment-seconds", 30);
        if (args.length < 2) {
            sender.sendMessage("[MinecraftVideo] Tranches de " + current
                    + " s. Usage: /stream delay <secondes>");
            return true;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage("[MinecraftVideo] '" + args[1] + "' n'est pas un nombre.");
            return true;
        }
        if (seconds < MIN_DELAY || seconds > MAX_DELAY) {
            sender.sendMessage("[MinecraftVideo] Duree hors limites (" + MIN_DELAY + " a "
                    + MAX_DELAY + " s).");
            return true;
        }
        plugin.getConfig().set("stream-segment-seconds", seconds);
        plugin.saveConfig();
        sender.sendMessage("[MinecraftVideo] Tranches de " + seconds + " s"
                + (seconds < 15 ? " (court : un raccord toutes les " + seconds
                        + " s, ca risque de hacher)" : "") + ".");
        if (plugin.isLive()) {
            sender.sendMessage("[MinecraftVideo] Le direct en cours garde ses tranches de "
                    + current + " s ; relance-le pour appliquer.");
        }
        return true;
    }

    private void start(Player player, String source) {
        LiveStream live = new LiveStream(plugin, source, player);
        player.sendMessage("[MinecraftVideo] Resolution du direct... (tranches de "
                + live.getSegmentSeconds() + " s, donc autant de retard sur le direct)");
        // yt-dlp bloque : on resout a cote, on revient sur le main pour demarrer ffmpeg.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String mediaUrl;
            try {
                mediaUrl = plugin.getYtDlpResolver().handles(source)
                        ? plugin.getYtDlpResolver().resolveLive(source)
                        : source;   // deja un manifeste HLS ou un flux que ffmpeg sait ouvrir
            } catch (IOException e) {
                // Le detail part au log seulement : meme raison que MediaCache, ne pas offrir
                // d'oracle sur ce que le serveur arrive a joindre.
                plugin.getLogger().warning("Resolution du direct '" + source + "' echouee: "
                        + e.getMessage());
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage("[MinecraftVideo] Impossible de lire ce direct"
                                + " (voir le log serveur)."));
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    plugin.startLiveStream(live, mediaUrl);
                    player.sendMessage("[MinecraftVideo] Direct lance: "
                            + PlaylistManager.shorten(source));
                } catch (IOException e) {
                    plugin.getLogger().warning("Demarrage du decoupage echoue: " + e.getMessage());
                    player.sendMessage("[MinecraftVideo] Impossible de demarrer le decoupage"
                            + " (ffmpeg-path ?).");
                }
            });
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias,
            String[] args) {
        if (args.length == 1) {
            return List.of("delay", "stop", "status");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("delay")) {
            return List.of("15", "30", "60");
        }
        return List.of();
    }
}
