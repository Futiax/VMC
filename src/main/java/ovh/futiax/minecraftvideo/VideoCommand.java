package ovh.futiax.minecraftvideo;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

// Les reglages globaux (taille et fps par defaut, audio, sync, rendu, sous-titres) sont sous
// /vmc (VmcCommand) ; /video ne pilote que la lecture.
// /video play <url-ou-chemin> [w] [h] [fps] joue avec les reglages de /vmc (les args priment)
// /video seek <+s|-s|[hh:]mm:ss>            saute ou va a un timecode
// /video subs <list|off|n>                  overlay d'une piste de sous-titres embarquee
// /video stop | pause | resume | status
public final class VideoCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("play", "queue", "skip", "seek", "subs", "stop",
                    "pause", "resume", "status");

    static final int MAX_DIMENSION = 16; // maps par axe

    // Le client applique les paquets map-data de sa file reseau une fois par tick client
    // (20 TPS), donc il ne peut afficher que 20 images distinctes par seconde quelle que soit
    // notre vitesse d'envoi. Au dela de 20 on gaspille juste de la bande passante et du CPU
    // mcmm. (A ne remonter que si le serveur tourne avec un /tick rate custom.)
    static final int MAX_FPS = 20;

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
            // ancien emplacement des reglages : on dit ou ils sont, sans les executer ici
            case "option", "options" -> sender.sendMessage("[MinecraftVideo] Settings moved to /vmc"
                    + (args.length > 1
                            ? ": /vmc " + String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                            : " (try /vmc)."));
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
        String busy = plugin.busyReason();
        if (busy != null) {
            sender.sendMessage("Cannot start: " + busy + "."
                    + " Use /" + label + " queue add to queue it, or /"
                    + label + " stop first.");
            return;
        }

        // Bukkit ne vire PAS les guillemets : /video play "http://..." donne un token qui les
        // garde, et ffmpeg le prend alors pour un nom de fichier bidon. On les enleve pour que
        // mettre l'URL entre guillemets soit sans consequence.
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
        // en rendu data la taille est de toute facon forcee au mur data (PlaybackSession) : ne
        // pas refuser une taille qui sera ignoree
        boolean data = RenderMode.fromConfig(plugin.getConfig()) == RenderMode.DATA;
        if (!data && (width < 1 || width > MAX_DIMENSION || height < 1 || height > MAX_DIMENSION)) {
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
        // Reference de cache pour cette lecture directe (relachee par la session a sa fin) ;
        // on la defait si on perd la course pour devenir la session active.
        plugin.getMediaCache().reference(source);
        PlaybackSession session = new PlaybackSession(plugin, player,
                mcmmPath, palettePath, source, width, height, fps,
                plugin.buildAudioSettings());
        if (!plugin.trySetActiveSession(session)) {
            plugin.getMediaCache().release(source);
            sender.sendMessage("A video is already playing. Use /" + label + " stop first.");
            return;
        }
        // viewers = tout le monde en ligne maintenant, les late joiners passent par JoinListener
        session.start(plugin.getServer().getOnlinePlayers());
        sender.sendMessage("[MinecraftVideo] Starting " + source + " on a "
                + session.getScreenWidth() + "x" + session.getScreenHeight()
                + " screen at " + fps + " fps...");
    }

    // /video queue [add <src>|list|remove <n>|clear] : gestion de la file
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
                    playlist.advance(); // rien ne joue : on lance tout de suite
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

    // /video skip : termine la video en cours, l'item suivant de la file demarre
    private void handleSkip(CommandSender sender) {
        PlaybackSession session = plugin.getActiveSession();
        if (session == null) {
            sender.sendMessage("No video is playing.");
            return;
        }
        boolean hasNext = !plugin.getPlaylist().isEmpty();
        session.stop(); // clearSession() enchaine la file tout seul
        sender.sendMessage(hasNext
                ? "[MinecraftVideo] Skipped — starting the next queued video..."
                : "[MinecraftVideo] Skipped (the queue is empty — stopped).");
    }

    // enleve une paire de guillemets (simples ou doubles) qui entoure la chaine
    static String stripSurroundingQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            if ((first == '"' || first == '\'') && s.charAt(s.length() - 1) == first) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    // /video seek <+s|-s|[hh:]mm:ss> : saut relatif ou absolu
    private void handleSeek(CommandSender sender, String label, String[] args) {
        PlaybackSession session = plugin.getActiveSession();
        if (session == null) {
            sender.sendMessage("No video is playing.");
            return;
        }
        // Un direct ne se navigue pas : ce qui joue est une tranche, et les precedentes
        // sont deja effacees du disque.
        if (plugin.isLive()) {
            sender.sendMessage("Cannot seek a live stream.");
            return;
        }
        // Une photo non plus : son thread de lecture est gare, personne ne consommerait le
        // pendingSeek et le joueur lirait "Seeking to ..." sans que rien ne bouge.
        if (session.isStill()) {
            sender.sendMessage("Cannot seek a photo.");
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
                // saut relatif en secondes, signe compris
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

    // /video subs list : liste les pistes de sous-titres embarquees de la source
    // /video subs <n>  : affiche la piste n (en overlay sur le bas de l'ecran)
    // /video subs off  : coupe les sous-titres
    //
    // list et <n> font un ffprobe de la source, qui peut bloquer sur une URL, donc le sondage
    // part hors main thread ; l'action et le message de retour reviennent sur le main.
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

    // Lance work sur un thread async, puis livre son resultat a then sur le main thread, mais
    // SEULEMENT si session est toujours la session active (comme ca un sondage de l'ANCIENNE
    // video ne peut pas s'appliquer a une NOUVELLE qui aurait demarre entre temps). Le ffprobe
    // bloquant reste hors du main thread, l'action et le message restent dessus.
    private <T> void runAsyncThenSync(PlaybackSession session,
                                      java.util.function.Supplier<T> work,
                                      java.util.function.Consumer<T> then) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            T result = work.get();
            if (!plugin.isEnabled()) {
                return; // plugin desactive pendant le sondage, rien a livrer
            }
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (plugin.getActiveSession() == session && !session.isStopped()) {
                        then.accept(result);
                    }
                });
            } catch (IllegalStateException | org.bukkit.plugin.IllegalPluginAccessException e) {
                // desactive entre le check et le schedule, le resultat ne sert plus a rien
            }
        });
    }

    // parse "90", "1:30" ou "1:02:03" en millis
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
        // On vide la file D'ABORD pour que clearSession() n'ait rien a relancer :
        // stop veut dire stop, contrairement a /video skip.
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
        if (session.isStill()) {
            sender.sendMessage("A photo has nothing to pause.");
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
        if (session.isStill()) {
            sender.sendMessage("A photo has nothing to resume.");
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
        sender.sendMessage("  /" + label + " queue [add <src>|list|remove <n>|clear]  — playlist");
        sender.sendMessage("  /" + label + " skip  — jump to the next queued video");
        sender.sendMessage("  /" + label + " seek <+s|-s|[hh:]mm:ss>  — skip / jump to timestamp");
        sender.sendMessage("  /" + label + " subs <list|off|n>  — embedded subtitle overlay");
        sender.sendMessage("  /" + label + " stop | pause | resume | status");
        sender.sendMessage("Settings (default size, audio, sync, render, subtitles): /vmc");
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
        // /video seek <...> : les sauts les plus courants
        if (args.length == 2 && args[0].equalsIgnoreCase("seek")) {
            List<String> matches = new ArrayList<>();
            for (String v : List.of("+10", "-10", "0:00")) {
                if (v.startsWith(args[1])) {
                    matches.add(v);
                }
            }
            return matches;
        }
        // /video subs <list|off|n> : les numeros de piste viennent de /video subs list (sonder
        // ici bloquerait le main thread), donc uniquement les mots-cles
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
