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
 * /video option &lt;width&gt; &lt;height&gt; [fps]     — set the persistent screen options
 * /video option audio &lt;mono|stereo|surround&gt; — set the persistent audio mode
 * /video option avsync &lt;ms&gt;                — set the persistent A/V sync delay
 * /video play &lt;url-or-path&gt; [w] [h] [fps]  — play with those options (args override)
 * /video seek &lt;+s|-s|[hh:]mm:ss&gt;           — skip or jump to a timestamp
 * /video subs &lt;list|off|n&gt;                 — embedded subtitle track overlay
 * /video stop | pause | resume | status
 */
public final class VideoCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("play", "queue", "skip", "option", "seek", "subs", "stop",
                    "pause", "resume", "status");

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
            case "queue" -> handleQueue(sender, label, args);
            case "skip", "next" -> handleSkip(sender);
            case "option", "options" -> handleOption(sender, label, args);
            case "seek" -> handleSeek(sender, label, args);
            case "subs", "subtitles" -> handleSubs(sender, label, args);
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
                    + " Use /" + label + " queue add to queue it, or /"
                    + label + " stop first.");
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
        // Reference the cache for this direct play (released by the session when
        // it ends); undo it if we lose the race to become the active session.
        plugin.getMediaCache().reference(source);
        PlaybackSession session = new PlaybackSession(plugin, player,
                mcmmPath, palettePath, source, width, height, fps,
                plugin.buildAudioSettings());
        if (!plugin.trySetActiveSession(session)) {
            plugin.getMediaCache().release(source);
            sender.sendMessage("A video is already playing. Use /" + label + " stop first.");
            return;
        }
        // Viewers: everyone online right now; late joiners are added by JoinListener.
        session.start(plugin.getServer().getOnlinePlayers());
        sender.sendMessage("[MinecraftVideo] Starting " + source
                + " on a " + width + "x" + height + " screen at " + fps + " fps...");
    }

    /** /video queue [add <src>|list|remove <n>|clear] — manage the playlist. */
    private void handleQueue(CommandSender sender, String label, String[] args) {
        PlaylistManager playlist = plugin.getPlaylist();
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (sub) {
            case "add" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can queue a video (the screen needs a position).");
                    return;
                }
                if (args.length < 3) {
                    sender.sendMessage("Usage: /" + label + " queue add <url-or-path>");
                    return;
                }
                String source = stripSurroundingQuotes(args[2]);
                int position = playlist.add(source, player);
                if (plugin.getActiveSession() == null) {
                    playlist.advance(); // idle: start it right away
                } else {
                    sender.sendMessage("[MinecraftVideo] Queued at position " + position
                            + ": " + PlaylistManager.shorten(source));
                }
            }
            case "list" -> {
                if (playlist.isEmpty()) {
                    sender.sendMessage("[MinecraftVideo] The queue is empty."
                            + " Add with /" + label + " queue add <url-or-path>.");
                    return;
                }
                sender.sendMessage("[MinecraftVideo] Queue (" + playlist.size() + "):");
                for (String line : playlist.describe()) {
                    sender.sendMessage("  " + line);
                }
            }
            case "clear" -> {
                int n = playlist.size();
                playlist.clear();
                sender.sendMessage("[MinecraftVideo] Queue cleared (" + n + " item(s) removed).");
            }
            case "remove" -> {
                int position;
                try {
                    position = args.length >= 3 ? Integer.parseInt(args[2]) : -1;
                } catch (NumberFormatException e) {
                    position = -1;
                }
                if (position < 1) {
                    sender.sendMessage("Usage: /" + label + " queue remove <position>"
                            + " (see /" + label + " queue list)");
                    return;
                }
                PlaylistManager.QueueItem removed = playlist.remove(position);
                sender.sendMessage(removed != null
                        ? "[MinecraftVideo] Removed #" + position + ": "
                                + PlaylistManager.shorten(removed.source())
                        : "No queue item #" + position + " (queue has "
                                + playlist.size() + " item(s)).");
            }
            default -> sender.sendMessage("Usage: /" + label
                    + " queue [add <url-or-path>|list|remove <n>|clear]");
        }
    }

    /** /video skip — end the current video; the next queued item starts. */
    private void handleSkip(CommandSender sender) {
        PlaybackSession session = plugin.getActiveSession();
        if (session == null) {
            sender.sendMessage("No video is playing.");
            return;
        }
        boolean hasNext = !plugin.getPlaylist().isEmpty();
        session.stop(); // clearSession() auto-advances the playlist
        sender.sendMessage(hasNext
                ? "[MinecraftVideo] Skipped — starting the next queued video..."
                : "[MinecraftVideo] Skipped (the queue is empty — stopped).");
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
        if (args.length >= 2 && args[1].equalsIgnoreCase("avsync")) {
            handleAvsyncOption(sender, label, args);
            return;
        }

        int curW = plugin.getConfig().getInt("default-width", 4);
        int curH = plugin.getConfig().getInt("default-height", 3);
        int curFps = plugin.getConfig().getInt("default-fps", 10);
        String curMode = AudioMode.fromConfig(
                plugin.getConfig().getString("audio-mode", "mono")).configName();
        int curAvsync = plugin.getConfig().getInt("av-sync-delay-ms", 200);

        if (args.length < 3) {
            sender.sendMessage("[MinecraftVideo] Current options: "
                    + curW + "x" + curH + " maps @ " + curFps + " fps, audio " + curMode
                    + ", avsync " + curAvsync + " ms");
            sender.sendMessage("Set with: /" + label + " option <width> <height> [fps]");
            sender.sendMessage("      or: /" + label + " option audio <mono|stereo|surround>");
            sender.sendMessage("      or: /" + label + " option avsync <ms>");
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

    /** /video option audio <mono|stereo|surround> — set and persist the audio mode. */
    private void handleAudioOption(CommandSender sender, String label, String[] args) {
        String cur = AudioMode.fromConfig(
                plugin.getConfig().getString("audio-mode", "mono")).configName();
        if (args.length < 3) {
            sender.sendMessage("[MinecraftVideo] Current audio mode: " + cur);
            sender.sendMessage("Set with: /" + label + " option audio <mono|stereo|surround>");
            return;
        }
        String mode = args[2].toLowerCase(Locale.ROOT);
        if (!AudioMode.configNames().contains(mode)) {
            sender.sendMessage("Audio mode must be 'mono', 'stereo' or 'surround'.");
            return;
        }
        plugin.getConfig().set("audio-mode", mode);
        plugin.saveConfig();
        String detail = switch (AudioMode.fromConfig(mode)) {
            case MONO -> " — single channel at the screen center.";
            case STEREO -> " — L/R anchored to the screen edges.";
            case SURROUND -> " — 6 speakers: front L/C/R at the screen,"
                    + " a subwoofer at its base, rears behind the audience.";
        };
        sender.sendMessage("[MinecraftVideo] Audio mode saved: " + mode + detail
                + " Applies to the next /" + label + " play.");
    }

    /**
     * /video option avsync [ms] — show or set/persist the A/V sync delay
     * (how much audio content is skipped to compensate the SVC client's
     * buffering; see config.yml).
     */
    private void handleAvsyncOption(CommandSender sender, String label, String[] args) {
        int cur = plugin.getConfig().getInt("av-sync-delay-ms", 200);
        if (args.length < 3) {
            sender.sendMessage("[MinecraftVideo] Current A/V sync delay: " + cur + " ms.");
            sender.sendMessage("Set with: /" + label + " option avsync <ms>"
                    + " — sound arrives late -> increase, sound early -> decrease.");
            return;
        }
        int ms;
        try {
            ms = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("<ms> must be a whole number of milliseconds (e.g. 200).");
            return;
        }
        if (ms < 0 || ms > 10_000) {
            sender.sendMessage("A/V sync delay must be between 0 and 10000 ms.");
            return;
        }
        plugin.getConfig().set("av-sync-delay-ms", ms);
        plugin.saveConfig();
        sender.sendMessage("[MinecraftVideo] A/V sync delay saved: " + ms + " ms (was "
                + cur + "). Applies to the next /" + label + " play.");
    }

    /** /video seek <+s|-s|[hh:]mm:ss> — relative skip or absolute jump. */
    private void handleSeek(CommandSender sender, String label, String[] args) {
        PlaybackSession session = plugin.getActiveSession();
        if (session == null) {
            sender.sendMessage("No video is playing.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /" + label + " seek <+seconds|-seconds|[hh:]mm:ss>");
            sender.sendMessage("  e.g. /" + label + " seek +10   /" + label + " seek -10   /"
                    + label + " seek 1:23");
            return;
        }
        String arg = args[1];
        long targetMillis;
        try {
            if (arg.startsWith("+") || arg.startsWith("-")) {
                // Relative skip in seconds (sign included).
                targetMillis = session.getPositionMillis() + Long.parseLong(arg) * 1000L;
            } else {
                targetMillis = parseTimestampMillis(arg);
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("Bad time '" + arg + "'. Use +10, -10, 90, 1:30 or 1:02:03.");
            return;
        }
        targetMillis = Math.max(0, targetMillis);
        if (session.seekTo(targetMillis)) {
            sender.sendMessage("[MinecraftVideo] Seeking to "
                    + PlaybackSession.formatTimestamp(targetMillis) + "...");
        } else {
            sender.sendMessage("No video is playing.");
        }
    }

    /**
     * /video subs list — list the source's embedded subtitle tracks.
     * /video subs &lt;n&gt;  — show subtitle track n (as an overlay under the screen).
     * /video subs off  — hide subtitles.
     *
     * <p>{@code list} and {@code <n>} ffprobe the source, which can block for a
     * URL, so the probe runs off the main thread; the resulting action and chat
     * feedback are hopped back onto the main thread.
     */
    private void handleSubs(CommandSender sender, String label, String[] args) {
        PlaybackSession session = plugin.getActiveSession();
        if (session == null) {
            sender.sendMessage("No video is playing.");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        if (sub.equals("off") || sub.equals("none")) {
            sender.sendMessage(session.disableSubtitles()
                    ? "[MinecraftVideo] Subtitles off."
                    : "Subtitles are already off.");
            return;
        }
        if (sub.equals("list")) {
            sender.sendMessage("[MinecraftVideo] Probing subtitle tracks...");
            runAsyncThenSync(session, session::getSubtitleTracks, tracks -> {
                if (tracks.isEmpty()) {
                    sender.sendMessage("[MinecraftVideo] No embedded subtitle tracks"
                            + " (or the source could not be probed).");
                    return;
                }
                sender.sendMessage("[MinecraftVideo] Subtitle tracks (" + tracks.size() + "):");
                for (SubtitleTrack track : tracks) {
                    sender.sendMessage("  " + track.describe());
                }
                sender.sendMessage("Show one with /" + label + " subs <n>, hide with /"
                        + label + " subs off.");
            });
            return;
        }
        // /video subs <n>
        int index;
        try {
            index = Integer.parseInt(sub);
        } catch (NumberFormatException e) {
            sender.sendMessage("Usage: /" + label + " subs <list|off|track-number>");
            return;
        }
        if (index < 0) {
            sender.sendMessage("Subtitle track number must be 0 or greater (see /"
                    + label + " subs list).");
            return;
        }
        sender.sendMessage("[MinecraftVideo] Loading subtitle track " + index + "...");
        runAsyncThenSync(session, session::getSubtitleTracks, tracks -> {
            if (index >= tracks.size()) {
                sender.sendMessage("No subtitle track " + index + " (source has "
                        + tracks.size() + "; see /" + label + " subs list).");
                return;
            }
            SubtitleTrack track = tracks.get(index);
            if (!track.textBased()) {
                sender.sendMessage("Track " + index + " (" + track.codec()
                        + ") is a bitmap subtitle track, which is not supported"
                        + " (only text tracks can be overlaid).");
                return;
            }
            if (!session.setSubtitleTrack(index)) {
                sender.sendMessage("The video is no longer playing.");
                return;
            }
            sender.sendMessage("[MinecraftVideo] Showing subtitles: " + track.describe());
        });
    }

    /**
     * Runs {@code work} on an async thread, then delivers its result to
     * {@code then} on the main thread — but only if {@code session} is still the
     * active one (so a probe of the OLD video can't apply to a NEW one that
     * started meanwhile). Keeps blocking ffprobe off the main thread while the
     * action and chat feedback stay on it.
     */
    private <T> void runAsyncThenSync(PlaybackSession session,
                                      java.util.function.Supplier<T> work,
                                      java.util.function.Consumer<T> then) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            T result = work.get();
            if (!plugin.isEnabled()) {
                return; // plugin disabled while we probed; nothing to deliver
            }
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (plugin.getActiveSession() == session && !session.isStopped()) {
                        then.accept(result);
                    }
                });
            } catch (IllegalStateException | org.bukkit.plugin.IllegalPluginAccessException e) {
                // Disabled between the check and the schedule; the result is moot.
            }
        });
    }

    /** Parses "90", "1:30" or "1:02:03" into millis. */
    private static long parseTimestampMillis(String s) {
        String[] parts = s.split(":");
        if (parts.length > 3 || parts.length == 0) {
            throw new NumberFormatException(s);
        }
        long total = 0;
        for (String part : parts) {
            int v = Integer.parseInt(part);
            if (v < 0 || (parts.length > 1 && part.length() > 2)) {
                throw new NumberFormatException(s);
            }
            total = total * 60 + v;
        }
        return total * 1000L;
    }

    private void handleStop(CommandSender sender) {
        // Empty the queue FIRST so clearSession() has nothing to auto-start:
        // stop means stop, unlike /video skip.
        int dropped = plugin.getPlaylist().size();
        plugin.getPlaylist().clear();
        PlaybackSession session = plugin.getActiveSession();
        if (session == null) {
            sender.sendMessage(dropped > 0
                    ? "[MinecraftVideo] Queue cleared (" + dropped + " item(s))."
                    : "No video is playing.");
            return;
        }
        session.stop();
        sender.sendMessage("[MinecraftVideo] Video stopped."
                + (dropped > 0 ? " Queue cleared (" + dropped + " item(s))." : ""));
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
        sender.sendMessage("  /" + label + " option audio <mono|stereo|surround>  — set audio mode");
        sender.sendMessage("  /" + label + " option avsync <ms>  — tune the A/V sync delay");
        sender.sendMessage("  /" + label + " play <url-or-path> [w] [h] [fps]");
        sender.sendMessage("  /" + label + " queue [add <src>|list|remove <n>|clear]  — playlist");
        sender.sendMessage("  /" + label + " skip  — jump to the next queued video");
        sender.sendMessage("  /" + label + " seek <+s|-s|[hh:]mm:ss>  — skip / jump to timestamp");
        sender.sendMessage("  /" + label + " subs <list|off|n>  — embedded subtitle overlay");
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
        // /video queue <add|list|remove|clear>
        if (args.length == 2 && args[0].equalsIgnoreCase("queue")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String v : List.of("add", "list", "remove", "clear")) {
                if (v.startsWith(prefix)) {
                    matches.add(v);
                }
            }
            return matches;
        }
        // /video option [audio|avsync] — suggest the keywords (width is numeric).
        if (args.length == 2 && args[0].equalsIgnoreCase("option")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String v : List.of("audio", "avsync")) {
                if (v.startsWith(prefix)) {
                    matches.add(v);
                }
            }
            if (!matches.isEmpty()) {
                return matches;
            }
        }
        // /video option audio <mono|stereo|surround>
        if (args.length == 3 && args[0].equalsIgnoreCase("option")
                && args[1].equalsIgnoreCase("audio")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String v : AudioMode.configNames()) {
                if (v.startsWith(prefix)) {
                    matches.add(v);
                }
            }
            return matches;
        }
        // /video option avsync <ms> — suggest common values.
        if (args.length == 3 && args[0].equalsIgnoreCase("option")
                && args[1].equalsIgnoreCase("avsync")) {
            List<String> matches = new ArrayList<>();
            for (String v : List.of("0", "100", "200", "300", "500")) {
                if (v.startsWith(args[2])) {
                    matches.add(v);
                }
            }
            return matches;
        }
        // /video seek <...> — suggest the common skips.
        if (args.length == 2 && args[0].equalsIgnoreCase("seek")) {
            List<String> matches = new ArrayList<>();
            for (String v : List.of("+10", "-10", "0:00")) {
                if (v.startsWith(args[1])) {
                    matches.add(v);
                }
            }
            return matches;
        }
        // /video subs <list|off|n> — track numbers come from /video subs list
        // (probing here would block the main thread), so only the keywords.
        if (args.length == 2 && args[0].equalsIgnoreCase("subs")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String v : List.of("list", "off")) {
                if (v.startsWith(prefix)) {
                    matches.add(v);
                }
            }
            return matches;
        }
        return List.of();
    }
}
