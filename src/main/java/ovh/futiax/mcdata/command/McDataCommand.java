package ovh.futiax.mcdata.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ovh.futiax.mcdata.McData;
import ovh.futiax.mcdata.poc.Poc;
import ovh.futiax.mcdata.poc.VideoPoc;

import java.util.ArrayList;
import java.util.List;

// /mcdata : point d'entree unique pour tous les POCs.
//   /mcdata list                                  -> liste les POCs et leur etat
//   /mcdata <poc> on|off [player]                 -> demarre/arrete un POC
//   /mcdata <poc> [player]                        -> bascule (toggle)
//   /mcdata stop [player]                         -> arrete tous les POCs d'un joueur
//   /mcdata video play <url> [normal|data] [wan|lan] [player]  -> video avec args
//   /mcdata video stop [player]                   -> arrete la video
public final class McDataCommand implements CommandExecutor, TabCompleter {

    private final McData plugin;

    public McDataCommand(McData plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list" -> {
                sender.sendMessage("[MCDATA] POCs disponibles :");
                for (Poc poc : plugin.getPocRegistry().all()) {
                    String state = "";
                    if (sender instanceof Player p && poc.isRunning(p)) {
                        state = " (actif pour vous)";
                    }
                    sender.sendMessage("  - " + poc.name() + " : " + poc.description() + state);
                }
                return true;
            }
            case "stop" -> {
                Player target = target(sender, args, 1);
                if (target == null) return true;
                plugin.getPocRegistry().stopAll(target);
                sender.sendMessage("[MCDATA] Tous les POCs arretes pour " + target.getName() + ".");
                return true;
            }
            case "video" -> {
                return handleVideo(sender, label, args);
            }
            default -> {
                String pocName = args[0].toLowerCase();
                Poc poc = plugin.getPocRegistry().get(pocName);
                if (poc == null) {
                    sender.sendMessage("[MCDATA] POC inconnu : " + pocName + " (voir /" + label + " list)");
                    return true;
                }
                Player target = target(sender, args, args.length >= 3 ? 2 : 1);
                if (target == null) return true;

                boolean wanted;
                if (args.length >= 2 && (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
                    wanted = args[1].equalsIgnoreCase("on");
                } else {
                    wanted = !poc.isRunning(target);
                }
                if (wanted) {
                    poc.start(target);
                } else {
                    poc.stop(target);
                }
                return true;
            }
        }
    }

    // /mcdata video play <url> [mode] [wall] [player]
    // /mcdata video stop [player]
    // /mcdata video on|off [player]  (ecran normal)
    private boolean handleVideo(CommandSender sender, String label, String[] args) {
        Poc poc = plugin.getPocRegistry().get("video");
        if (poc == null || !(poc instanceof VideoPoc video)) {
            sender.sendMessage("[MCDATA] POC video non enregistre.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("[MCDATA] Usage video :");
            sender.sendMessage("  /" + label + " video play <url> [normal|data] [wan|lan] [joueur]");
            sender.sendMessage("  /" + label + " video stop [joueur]");
            sender.sendMessage("  /" + label + " video on|off [joueur]");
            return true;
        }
        switch (args[1].toLowerCase()) {
            case "play" -> {
                if (args.length < 3) {
                    sender.sendMessage("[MCDATA] Il faut une URL : /" + label + " video play <url> [normal|data] [wan|lan] [joueur]");
                    return true;
                }
                String url = args[2];
                String mode = args.length >= 4 ? args[3] : "normal";
                String wall = args.length >= 5 ? args[4] : "wan";
                Player target = target(sender, args, args.length >= 6 ? 5 : -1);
                if (target == null) return true;
                video.play(target, url, mode, wall);
                return true;
            }
            case "stop" -> {
                Player target = target(sender, args, 2);
                if (target == null) return true;
                video.stop(target);
                return true;
            }
            default -> {
                // on|off ou toggle
                Player target = target(sender, args, args.length >= 3 ? 2 : 1);
                if (target == null) return true;
                boolean wanted = args.length >= 2 && args[1].equalsIgnoreCase("off") ? false
                        : args.length >= 2 && args[1].equalsIgnoreCase("on") ? true
                        : !video.isRunning(target);
                if (wanted) video.start(target); else video.stop(target);
                return true;
            }
        }
    }

    private Player target(CommandSender sender, String[] args, int index) {
        if (index >= 0 && args.length > index) {
            Player p = plugin.getServer().getPlayerExact(args[index]);
            if (p == null) {
                sender.sendMessage("[MCDATA] Joueur introuvable : " + args[index]);
                return null;
            }
            return p;
        }
        if (sender instanceof Player p) {
            return p;
        }
        sender.sendMessage("[MCDATA] La console doit nommer un joueur.");
        return null;
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage("[MCDATA] Usage :");
        sender.sendMessage("  /" + label + " list");
        sender.sendMessage("  /" + label + " <poc> [on|off] [joueur]");
        sender.sendMessage("  /" + label + " video play <url> [normal|data] [wan|lan] [joueur]");
        sender.sendMessage("  /" + label + " video stop [joueur]");
        sender.sendMessage("  /" + label + " stop [joueur]");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("list");
            out.add("stop");
            for (Poc poc : plugin.getPocRegistry().all()) out.add(poc.name());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("video")) {
            out.add("play");
            out.add("stop");
            out.add("on");
            out.add("off");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("video") && args[1].equalsIgnoreCase("play")) {
            out.add("<url>");
        } else if (args.length == 4 && args[0].equalsIgnoreCase("video") && args[1].equalsIgnoreCase("play")) {
            out.add("normal");
            out.add("data");
        } else if (args.length == 5 && args[0].equalsIgnoreCase("video") && args[1].equalsIgnoreCase("play")) {
            out.add("wan");
            out.add("lan");
        } else if (args.length >= 2 && !args[0].equalsIgnoreCase("list") && !args[0].equalsIgnoreCase("stop") && !args[0].equalsIgnoreCase("video")) {
            if (args.length == 2) {
                out.add("on");
                out.add("off");
            }
            for (Player p : plugin.getServer().getOnlinePlayers()) out.add(p.getName());
        }
        String prefix = args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(prefix));
        return out;
    }
}
