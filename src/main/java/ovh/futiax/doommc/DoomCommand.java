package ovh.futiax.doommc;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// /doom                         bascule une partie solo pour soi
// /doom [on|off] [joueur]       solo pour un joueur, ou le sort de sa partie
// /doom coop|arena [joueurs...] nouvelle partie a plusieurs (soi d'abord si on est un joueur, 4 au plus)
// /doom join <hote> [joueur]    rejoint la partie de l'hote (coop ou arene)
// /doom leave [joueur]          quitte la partie
final class DoomCommand implements TabExecutor {

    private static final String CONTROLS = "Move: movement keys, sprint = run. Fire: jump or left click."
            + " Use (doors, switches): sneak or right click. Weapons: hotbar slots 1-7. /doom leave to quit.";
    private static final List<String> WORDS = List.of("on", "off", "coop", "arena", "join", "leave");

    private final DoomMc plugin;

    DoomCommand(DoomMc plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        DoomGames games = plugin.getGames();
        Player self = sender instanceof Player p ? p : null;
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        List<Player> named = new ArrayList<>();
        for (int i = WORDS.contains(sub) ? 1 : 0; i < args.length; i++) {
            Player p = plugin.getServer().getPlayerExact(args[i]);
            if (p == null) {
                sender.sendMessage("[DOOMMC] No online player named " + args[i] + ".");
                return true;
            }
            if (!named.contains(p)) {
                named.add(p);
            }
        }
        switch (sub) {
            case "coop", "arena" -> {
                List<Player> players = new ArrayList<>();
                if (self != null) {
                    players.add(self);
                }
                for (Player p : named) {
                    if (!players.contains(p)) {
                        players.add(p);
                    }
                }
                if (players.isEmpty()) {
                    return usage(sender, label);
                }
                if (players.size() > EngineProcess.SLOTS) {
                    sender.sendMessage("[DOOMMC] 4 players at most.");
                    return true;
                }
                DoomGames.Mode mode = sub.equals("coop") ? DoomGames.Mode.COOP : DoomGames.Mode.ARENA;
                if (start(sender, mode, players)) {
                    String host = players.get(0).getName();
                    for (Player p : players) {
                        p.sendMessage("[DOOMMC] " + (mode == DoomGames.Mode.COOP ? "Coop" : "Arena (deathmatch, no monsters)")
                                + " game started. Others can join with /doom join " + host + ". " + CONTROLS);
                    }
                }
            }
            case "join" -> {
                Player host = named.isEmpty() ? null : named.get(0);
                Player player = named.size() > 1 ? named.get(1) : self;
                if (host == null || player == null) {
                    return usage(sender, label);
                }
                String error = games.join(host, player);
                if (error != null) {
                    sender.sendMessage("[DOOMMC] " + error);
                } else {
                    player.sendMessage("[DOOMMC] Joined " + host.getName() + "'s game. " + CONTROLS);
                }
            }
            case "leave", "off" -> {
                Player player = named.isEmpty() ? self : named.get(0);
                if (player == null) {
                    return usage(sender, label);
                }
                games.leave(player);
                player.sendMessage("[DOOMMC] Doom off.");
            }
            default -> {    // "", "on" ou un nom : solo
                Player player = named.isEmpty() ? self : named.get(0);
                if (player == null) {
                    return usage(sender, label);
                }
                if (!sub.equals("on") && games.isPlaying(player)) {
                    games.leave(player);
                    player.sendMessage("[DOOMMC] Doom off.");
                } else if (start(sender, DoomGames.Mode.SOLO, List.of(player))) {
                    player.sendMessage("[DOOMMC] Doom on (E1M1, solo). " + CONTROLS);
                }
            }
        }
        return true;
    }

    private boolean start(CommandSender sender, DoomGames.Mode mode, List<Player> players) {
        try {
            plugin.getGames().create(mode, players);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Doom engine failed to start: " + e.getMessage());
            sender.sendMessage("[DOOMMC] The Doom engine could not start (see the server log).");
            return false;
        }
    }

    private static boolean usage(CommandSender sender, String label) {
        sender.sendMessage("[DOOMMC] /" + label + " [on|off] [player] | /" + label + " coop|arena [players...]"
                + " | /" + label + " join <host> [player] | /" + label + " leave [player]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String w : WORDS) {
                if (w.startsWith(prefix)) {
                    out.add(w);
                }
            }
        }
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(p.getName());
            }
        }
        return out;
    }
}
