package ovh.futiax.minecraftvideo;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /video play &lt;url-or-path&gt; [w] [h] [fps]
 * /video stop | pause | resume | status
 */
public final class VideoCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("play", "stop", "pause", "resume", "status");

    private static final int MAX_DIMENSION = 16; // maps per axis
    private static final int MAX_FPS = 60;

    private final MinecraftVideoPlugin plugin;

    public VideoCommand(MinecraftVideoPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "play" -> handlePlay(sender, label, args);
            case "stop" -> handleStop(sender);
            case "pause" -> handlePause(sender);
            case "resume", "unpause" -> handleResume(sender);
            case "status" -> handleStatus(sender);
            default -> sendUsage(sender, label);
        }
        return true;
    }

    private void handlePlay(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can start a video (the screen is placed in front of you).");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /" + label + " play <url-or-path> [w] [h] [fps]");
            return;
        }
        PlaybackSession active = plugin.getActiveSession();
        if (active != null) {
            sender.sendMessage("A video is already playing"
                    + " (started by " + active.getInitiatorName() + ")."
                    + " Use /" + label + " stop first.");
            return;
        }

        // Bukkit does NOT strip quotes: `/video play "http://..."` yields a
        // token that still has the surrounding quotes, which ffmpeg then treats
        // as a bogus filename. Strip them so quoting the URL is harmless.
        String source = stripSurroundingQuotes(args[1]);
        int width = plugin.getConfig().getInt("default-width", 4);
        int height = plugin.getConfig().getInt("default-height", 3);
        int fps = plugin.getConfig().getInt("default-fps", 10);
        try {
            if (args.length >= 3) {
                width = Integer.parseInt(args[2]);
            }
            if (args.length >= 4) {
                height = Integer.parseInt(args[3]);
            }
            if (args.length >= 5) {
                fps = Integer.parseInt(args[4]);
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("[w], [h] and [fps] must be whole numbers.");
            return;
        }
        if (width < 1 || width > MAX_DIMENSION || height < 1 || height > MAX_DIMENSION) {
            sender.sendMessage("Screen size must be between 1x1 and "
                    + MAX_DIMENSION + "x" + MAX_DIMENSION + " maps.");
            return;
        }
        if (fps < 1 || fps > MAX_FPS) {
            sender.sendMessage("fps must be between 1 and " + MAX_FPS + ".");
            return;
        }

        String mcmmPath = plugin.resolveMcmmPath();
        String palettePath = plugin.resolvePalettePath();
        if (mcmmPath == null) {
            sender.sendMessage("mcmm is not available for this platform. Build it from"
                    + " 'c version/' and set mcmm-path in the plugin config.");
            return;
        }
        if (palettePath == null) {
            sender.sendMessage("The color palette is missing. Set palette-path in the plugin config.");
            return;
        }
        String ffmpegPath = plugin.getConfig().getString("ffmpeg-path", "ffmpeg");
        boolean audioEnabled = plugin.getConfig().getBoolean("audio-enabled", true);
        int audioDistance = plugin.getConfig().getInt("audio-distance", 48);

        PlaybackSession session = new PlaybackSession(plugin, player,
                mcmmPath, palettePath, source, width, height, fps,
                ffmpegPath, audioEnabled, audioDistance);
        if (!plugin.trySetActiveSession(session)) {
            sender.sendMessage("A video is already playing. Use /" + label + " stop first.");
            return;
        }
        // Viewers: everyone online right now; late joiners are added by JoinListener.
        session.start(plugin.getServer().getOnlinePlayers());
        sender.sendMessage("[MinecraftVideo] Starting " + source
                + " on a " + width + "x" + height + " screen at " + fps + " fps...");
    }

    /** Removes a matching pair of surrounding single or double quotes. */
    private static String stripSurroundingQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            if ((first == '"' || first == '\'') && s.charAt(s.length() - 1) == first) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private void handleStop(CommandSender sender) {
        PlaybackSession session = plugin.getActiveSession();
        if (session == null) {
            sender.sendMessage("No video is playing.");
            return;
        }
        session.stop();
        sender.sendMessage("[MinecraftVideo] Video stopped.");
    }

    private void handlePause(CommandSender sender) {
        PlaybackSession session = plugin.getActiveSession();
        if (session == null) {
            sender.sendMessage("No video is playing.");
            return;
        }
        if (session.pause()) {
            sender.sendMessage("[MinecraftVideo] Paused.");
        } else {
            sender.sendMessage("Already paused.");
        }
    }

    private void handleResume(CommandSender sender) {
        PlaybackSession session = plugin.getActiveSession();
        if (session == null) {
            sender.sendMessage("No video is playing.");
            return;
        }
        if (session.resume()) {
            sender.sendMessage("[MinecraftVideo] Resumed.");
        } else {
            sender.sendMessage("Not paused.");
        }
    }

    private void handleStatus(CommandSender sender) {
        PlaybackSession session = plugin.getActiveSession();
        if (session == null) {
            sender.sendMessage("No video is playing.");
            return;
        }
        sender.sendMessage("[MinecraftVideo] Status:");
        for (String line : session.describeStatus()) {
            sender.sendMessage("  " + line);
        }
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage("Usage:");
        sender.sendMessage("  /" + label + " play <url-or-path> [w] [h] [fps]");
        sender.sendMessage("  /" + label + " stop | pause | resume | status");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) {
                    matches.add(sub);
                }
            }
            return matches;
        }
        return List.of();
    }
}
