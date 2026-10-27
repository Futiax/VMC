package ovh.futiax.minecraftvideo;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

// /photo <url-ou-chemin> [w] [h] | stop
//
// Une photo EST une lecture : meme ecran, memes viewers, meme culling, meme slot de session
// active -- la seule difference est qu'elle ne se termine jamais toute seule. D'ou l'absence
// de pause/resume/seek ici : il n'y a rien a piloter, et /video stop l'arrete aussi.
//
// Les formats acceptes sont ceux de ffmpeg (png, jpg, webp, gif...) ; une VIDEO donnee ici
// affiche sa premiere image.
public final class PhotoCommand implements CommandExecutor, TabCompleter {

    private final MinecraftVideoPlugin plugin;

    public PhotoCommand(MinecraftVideoPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("[MinecraftVideo] /" + label + " <url-or-path> [w] [h] | stop");
            return true;
        }
        if (args[0].equalsIgnoreCase("stop")) {
            // Stop veut dire stop : on vide la file et on coupe un direct AVANT d'arreter la
            // session, sinon clearSession enchaine sur l'element suivant (ou le direct se
            // recree au prochain poll) et le "Stopped." serait un mensonge.
            int dropped = plugin.getPlaylist().size();
            plugin.getPlaylist().clear();
            if (plugin.isLive()) {
                plugin.stopLiveStream(null);
            }
            PlaybackSession active = plugin.getActiveSession();
            if (active != null) {
                active.stop();
            }
            sender.sendMessage(active != null || dropped > 0
                    ? "[MinecraftVideo] Stopped."
                            + (dropped > 0 ? " Queue cleared (" + dropped + " item(s))." : "")
                    : "[MinecraftVideo] Nothing is displayed.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[MinecraftVideo] Only a player can display a photo"
                    + " (the screen is placed in front of you).");
            return true;
        }
        String busy = plugin.busyReason();
        if (busy != null) {
            sender.sendMessage("[MinecraftVideo] Cannot display: " + busy
                    + ". Use /" + label + " stop first.");
            return true;
        }

        // Bukkit ne vire PAS les guillemets : /photo "http://..." garde un token avec, et
        // ffmpeg le prend pour un nom de fichier bidon. Meme traitement que /video play.
        String source = VideoCommand.stripSurroundingQuotes(args[0]);
        int width = plugin.getConfig().getInt("default-width", 4);
        int height = plugin.getConfig().getInt("default-height", 3);
        try {
            if (args.length >= 2) {
                width = Integer.parseInt(args[1]);
            }
            if (args.length >= 3) {
                height = Integer.parseInt(args[2]);
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("[MinecraftVideo] [w] and [h] must be whole numbers.");
            return true;
        }
        // en rendu data la taille est forcee au mur data (PlaybackSession) : pas de refus inutile
        if (RenderMode.fromConfig(plugin.getConfig()) != RenderMode.DATA
                && (width < 1 || width > VideoCommand.MAX_DIMENSION
                        || height < 1 || height > VideoCommand.MAX_DIMENSION)) {
            sender.sendMessage("[MinecraftVideo] Screen size must be between 1x1 and "
                    + VideoCommand.MAX_DIMENSION + "x" + VideoCommand.MAX_DIMENSION + " maps.");
            return true;
        }

        String mcmmPath = plugin.resolveMcmmPath();
        String palettePath = plugin.resolvePalettePath();
        if (mcmmPath == null || palettePath == null) {
            sender.sendMessage("[MinecraftVideo] mcmm or the palette is unavailable"
                    + " (see the server log).");
            return true;
        }

        // Reference de cache pour cette occurrence, relachee par la session a sa fin ; on la
        // defait tout de suite si on perd la course pour le slot de session active.
        plugin.getMediaCache().reference(source);
        PlaybackSession session = PlaybackSession.photo(plugin, player,
                mcmmPath, palettePath, source, width, height);
        if (!plugin.trySetActiveSession(session)) {
            plugin.getMediaCache().release(source);
            sender.sendMessage("[MinecraftVideo] Something is already playing.");
            return true;
        }
        session.start(plugin.getServer().getOnlinePlayers());
        sender.sendMessage("[MinecraftVideo] Displaying " + PlaylistManager.shorten(source)
                + " on a " + session.getScreenWidth() + "x" + session.getScreenHeight()
                + " screen...");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String label, String[] args) {
        if (args.length == 1) {
            String start = args[0].toLowerCase(Locale.ROOT);
            return "stop".startsWith(start) ? List.of("stop") : List.of();
        }
        return List.of();
    }
}
