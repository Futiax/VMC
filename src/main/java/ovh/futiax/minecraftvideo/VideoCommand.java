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
 * /video option &lt;width&gt; &lt;height&gt; [fps]  — set the persistent screen options
 * /video option audio &lt;mono|stereo&gt;      — set the persistent audio mode
 * /video play &lt;url-or-path&gt; [w] [h] [fps] — play with those options (args override)
 * /video stop | pause | resume | status
 */
public final class VideoCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("play", "option", "stop", "pause", "resume", "status");

    private static final int MAX_DIMENSION = 16; // maps per axis

    // The client applies map-data packets from its network queue once per client
    // tick (20 TPS), so it can show at most 20 distinct frames/second no matter
    // how fast we send. Anything above 20 just wastes bandwidth and mcmm CPU.
    // (Only relevant to raise if the server runs a custom /tick rate.)
    private static final int MAX_FPS = 20;

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
            case "option", "options" -> handleOption(sender, label, args);
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
        int audioChannels = "stereo".equalsIgnoreCase(
                plugin.getConfig().getString("audio-mode", "mono")) ? 2 : 1;

        PlaybackSession session = new PlaybackSession(plugin, player,
                mcmmPath, palettePath, source, width, height, fps,
                ffmpegPath, audioEnabled, audioDistance, audioChannels);
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

    /**
     * /video option &lt;width&gt; &lt;height&gt; [fps] — set and persist screen options.
     * /video option audio &lt;mono|stereo&gt; — set and persist the audio mode.
     */
    private void handleOption(CommandSender sender, String label, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("audio")) {
            handleAudioOption(sender, label, args);
            return;
        }

        int curW = plugin.getConfig().getInt("default-width", 4);
        int curH = plugin.getConfig().getInt("default-height", 3);
        int curFps = plugin.getConfig().getInt("default-fps", 10);
        String curMode = normalizeMode(plugin.getConfig().getString("audio-mode", "mono"));

        if (args.length < 3) {
            sender.sendMessage("[MinecraftVideo] Current options: "
                    + curW + "x" + curH + " maps @ " + curFps + " fps, audio " + curMode);
            sender.sendMessage("Set with: /" + label + " option <width> <height> [fps]");
            sender.sendMessage("      or: /" + label + " option audio <mono|stereo>");
            return;
        }

        int width;
        int height;
        int fps = curFps;
        try {
            width = Integer.parseInt(args[1]);
            height = Integer.parseInt(args[2]);
            if (args.length >= 4) {
                fps = Integer.parseInt(args[3]);
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("<width>, <height> and [fps] must be whole numbers.");
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

        plugin.getConfig().set("default-width", width);
        plugin.getConfig().set("default-height", height);
        plugin.getConfig().set("default-fps", fps);
        plugin.saveConfig();
        sender.sendMessage("[MinecraftVideo] Options saved: " + width + "x" + height
                + " maps @ " + fps + " fps — used by /" + label + " play.");
    }

    /** /video option audio <mono|stereo> — set and persist the audio mode. */
    private void handleAudioOption(CommandSender sender, String label, String[] args) {
        String cur = normalizeMode(plugin.getConfig().getString("audio-mode", "mono"));
        if (args.length < 3) {
            sender.sendMessage("[MinecraftVideo] Current audio mode: " + cur);
            sender.sendMessage("Set with: /" + label + " option audio <mono|stereo>");
            return;
        }
        String mode = args[2].toLowerCase(Locale.ROOT);
        if (!mode.equals("mono") && !mode.equals("stereo")) {
            sender.sendMessage("Audio mode must be 'mono' or 'stereo'.");
            return;
        }
        plugin.getConfig().set("audio-mode", mode);
        plugin.saveConfig();
        sender.sendMessage("[MinecraftVideo] Audio mode saved: " + mode
                + (mode.equals("stereo")
                        ? " — L/R anchored to the screen edges."
                        : " — single channel at the screen center.")
                + " Applies to the next /" + label + " play.");
    }

    /** Any non-"stereo" value (incl. bad config) reads back as mono. */
    private static String normalizeMode(String mode) {
        return "stereo".equalsIgnoreCase(mode) ? "stereo" : "mono";
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
        sender.sendMessage("  /" + label + " option <width> <height> [fps]  — set screen options");
        sender.sendMessage("  /" + label + " option audio <mono|stereo>  — set audio mode");
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
        // /video option [audio] — suggest the audio keyword (width is numeric).
        if (args.length == 2 && args[0].equalsIgnoreCase("option")
                && "audio".startsWith(args[1].toLowerCase(Locale.ROOT))) {
            return List.of("audio");
        }
        // /video option audio <mono|stereo>
        if (args.length == 3 && args[0].equalsIgnoreCase("option")
                && args[1].equalsIgnoreCase("audio")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String v : List.of("mono", "stereo")) {
                if (v.startsWith(prefix)) {
                    matches.add(v);
                }
            }
            return matches;
        }
        return List.of();
    }
}
