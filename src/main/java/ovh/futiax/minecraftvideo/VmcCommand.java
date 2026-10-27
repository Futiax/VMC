package ovh.futiax.minecraftvideo;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Reglages GLOBAUX du plugin, persistes dans config.yml et lus au demarrage de la lecture
// suivante. Ils vivaient sous /video option : des reglages de serveur sous la commande qui
// pilote UNE video, c'etait bizarre.
// /vmc                                       affiche les reglages
// /vmc <width> <height> [fps]                taille et fps par defaut de /video play
// /vmc audio <mono|stereo|surround>          mode audio
// /vmc avsync <ms>                           delai de sync A/V
// /vmc render <normal|dither|strict|data>    rendu des couleurs ("dither" = ancien nom)
// /vmc data <wan|lan>                        mur du rendu data (4x2 ou 5x3 maps)
// /vmc sub <size|height|depth> <valeur>      geometrie de l'overlay sous-titres
public final class VmcCommand implements CommandExecutor, TabCompleter {

    private static final List<String> KEYWORDS = List.of("audio", "avsync", "data", "render", "sub");

    private final MinecraftVideoPlugin plugin;

    public VmcCommand(MinecraftVideoPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String key = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (key) {
            case "audio" -> handleAudio(sender, label, args);
            case "avsync" -> handleAvsync(sender, label, args);
            case "render", "dither" -> handleRender(sender, label, args);
            case "data" -> handleData(sender, label, args);
            case "sub", "subs", "subtitle" -> handleSub(sender, label, args);
            default -> handleScreen(sender, label, args);
        }
        return true;
    }

    // /vmc [<width> <height> [fps]] : affiche tout, ou regle et persiste la taille / les fps
    private void handleScreen(CommandSender sender, String label, String[] args) {
        int curW = plugin.getConfig().getInt("default-width", 4);
        int curH = plugin.getConfig().getInt("default-height", 3);
        int curFps = plugin.getConfig().getInt("default-fps", 10);
        String curMode = AudioMode.fromConfig(
                plugin.getConfig().getString("audio-mode", "mono")).configName();
        int curAvsync = plugin.getConfig().getInt("av-sync-delay-ms", 200);

        // en rendu data la taille sauvegardee n'est pas celle de l'ecran : on le dit
        RenderMode.DataWall wall = RenderMode.DataWall.fromConfig(plugin.getConfig());
        String dataNote = RenderMode.fromConfig(plugin.getConfig()) == RenderMode.DATA
                ? " (render data: the screen is always the " + wall.configName() + " wall, "
                        + wall.width() + "x" + wall.height() + " maps)"
                : "";
        if (args.length < 2) {
            sender.sendMessage("[MinecraftVideo] Settings: "
                    + curW + "x" + curH + " maps @ " + curFps + " fps, audio " + curMode
                    + ", avsync " + curAvsync + " ms, render "
                    + RenderMode.fromConfig(plugin.getConfig()).configName() + dataNote);
            sender.sendMessage("Set with: /" + label + " <width> <height> [fps]");
            sender.sendMessage("      or: /" + label + " audio <mono|stereo|surround>");
            sender.sendMessage("      or: /" + label + " avsync <ms>");
            sender.sendMessage("      or: /" + label + " render <normal|dither|strict|data>");
            sender.sendMessage("      or: /" + label + " data <wan|lan>");
            sender.sendMessage("      or: /" + label + " sub <size|height|depth> <value>");
            return;
        }

        int width;
        int height;
        int fps = curFps;
        try {
            width = Integer.parseInt(args[0]);
            height = Integer.parseInt(args[1]);
            if (args.length >= 3) {
                fps = Integer.parseInt(args[2]);
            }
        } catch (NumberFormatException e) {
            sender.sendMessage("<width>, <height> and [fps] must be whole numbers"
                    + " (or use /" + label + " audio|avsync|render|data|sub).");
            return;
        }
        if (width < 1 || width > VideoCommand.MAX_DIMENSION
                || height < 1 || height > VideoCommand.MAX_DIMENSION) {
            sender.sendMessage("Screen size must be between 1x1 and "
                    + VideoCommand.MAX_DIMENSION + "x" + VideoCommand.MAX_DIMENSION + " maps.");
            return;
        }
        if (fps < 1 || fps > VideoCommand.MAX_FPS) {
            sender.sendMessage("fps must be between 1 and " + VideoCommand.MAX_FPS + ".");
            return;
        }

        plugin.getConfig().set("default-width", width);
        plugin.getConfig().set("default-height", height);
        plugin.getConfig().set("default-fps", fps);
        plugin.saveConfig();
        sender.sendMessage("[MinecraftVideo] Saved: " + width + "x" + height
                + " maps @ " + fps + " fps — used by /video play." + dataNote);
    }

    // /vmc audio [mono|stereo|surround] : affiche ou regle/persiste le mode audio
    private void handleAudio(CommandSender sender, String label, String[] args) {
        String cur = AudioMode.fromConfig(
                plugin.getConfig().getString("audio-mode", "mono")).configName();
        if (args.length < 2) {
            sender.sendMessage("[MinecraftVideo] Current audio mode: " + cur);
            sender.sendMessage("Set with: /" + label + " audio <mono|stereo|surround>");
            return;
        }
        String mode = args[1].toLowerCase(Locale.ROOT);
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
                + " Applies to the next /video play.");
    }

    // /vmc render [normal|dither|strict|data] : affiche ou regle/persiste le rendu des couleurs
    // (RenderMode). "dither" reste accepte comme nom du reglage, avec son on/off d'avant :
    // c'etait un booleen, le switch a positions l'a remplace.
    private void handleRender(CommandSender sender, String label, String[] args) {
        RenderMode cur = RenderMode.fromConfig(plugin.getConfig());
        RenderMode.DataWall wall = RenderMode.DataWall.fromConfig(plugin.getConfig());
        if (args.length < 2) {
            sender.sendMessage("[MinecraftVideo] Render mode: " + cur.configName() + ".");
            sender.sendMessage("Set with: /" + label + " render <normal|dither|strict|data>");
            sender.sendMessage("  normal — scaled to the screen; dither — smoother gradients,"
                    + " more bandwidth; strict — pixel-exact (whole-number scale, no blending),"
                    + " for pixel art and QR codes; data — experimental, a "
                    + wall.width() + "x" + wall.height()
                    + " wall that only the VMC resource pack decodes (size: /" + label + " data).");
            return;
        }
        RenderMode mode = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on", "true" -> RenderMode.DITHER;
            case "off", "false" -> RenderMode.NORMAL;
            default -> RenderMode.parse(args[1]);
        };
        if (mode == null) {
            sender.sendMessage("Render mode must be 'normal', 'dither', 'strict' or 'data'.");
            return;
        }
        plugin.getConfig().set("render-mode", mode.configName());
        plugin.getConfig().set("dither", null);     // l'ancien booleen ne doit plus rien dire
        plugin.saveConfig();
        String detail = switch (mode) {
            case NORMAL -> ".";
            case DITHER -> " — smoother gradients; watch /video status for dropped frames.";
            case STRICT -> " — every map pixel is a source pixel: whole-number scale, centred"
                    + " on black. Size the screen so the picture divides exactly.";
            case DATA -> " — EXPERIMENTAL: the picture is compressed into a "
                    + wall.width() + "x" + wall.height() + " wall of colour"
                    + " symbols (always that size, /" + label + " data to change it) that only"
                    + " players with the VMC resource pack (" + wall.effect() + ") can decode;"
                    + " others see noise.";
        };
        sender.sendMessage("[MinecraftVideo] Render mode saved: " + mode.configName() + detail
                + " Applies to the next /video play, /photo or /stream.");
    }

    // /vmc data [wan|lan] : affiche ou regle/persiste le mur du rendu data (RenderMode.DataWall).
    // lan = plus de donnees donc une meilleure image, mais deux fois le debit du wan, et un mur
    // qui ne se lit en 1080p que d'une plage etroite (4,3 a 4,8 blocs en FOV 70)
    private void handleData(CommandSender sender, String label, String[] args) {
        RenderMode.DataWall cur = RenderMode.DataWall.fromConfig(plugin.getConfig());
        if (args.length < 2) {
            sender.sendMessage("[MinecraftVideo] Data wall: " + cur.configName() + " ("
                    + cur.width() + "x" + cur.height() + " maps).");
            sender.sendMessage("Set with: /" + label + " data <wan|lan>");
            sender.sendMessage("  wan — 4x2 maps, an 848x464 picture, up to ~9 Mbit/s per viewer at"
                    + " 10 fps; lan — 5x3 maps, a 1232x720 picture, up to ~19 Mbit/s at 10 fps."
                    + " Only used with /" + label + " render data.");
            return;
        }
        RenderMode.DataWall wall = RenderMode.DataWall.parse(args[1]);
        if (wall == null) {
            sender.sendMessage("Data wall must be 'wan' or 'lan'.");
            return;
        }
        plugin.getConfig().set("data-wall", wall.configName());
        plugin.saveConfig();
        String detail = switch (wall) {
            case WAN -> " — an 848x464 picture, up to ~9 Mbit/s per viewer at 10 fps (18 at 20)."
                    + " Readable from about 1.4 to 3.2 blocks away on a 1080p screen (FOV 70).";
            case LAN -> " — a 1232x720 picture, up to ~19 Mbit/s per viewer at 10 fps (38 at 20)."
                    + " The whole wall must be on screen and readable: on a 1080p screen that is"
                    + " only from about 2.1 to 3.1 blocks away (FOV 70).";
        };
        boolean data = RenderMode.fromConfig(plugin.getConfig()) == RenderMode.DATA;
        sender.sendMessage("[MinecraftVideo] Data wall saved: " + wall.configName() + ", "
                + wall.width() + "x" + wall.height() + " maps decoded by " + wall.effect() + detail
                + (data ? " Applies to the next /video play, /photo or /stream."
                        : " Used once /" + label + " render data is on."));
    }

    // /vmc avsync [ms] : affiche ou regle/persiste le delai de sync A/V (combien de contenu
    // audio est saute pour compenser le buffering du client SVC, cf config.yml)
    private void handleAvsync(CommandSender sender, String label, String[] args) {
        int cur = plugin.getConfig().getInt("av-sync-delay-ms", 200);
        if (args.length < 2) {
            sender.sendMessage("[MinecraftVideo] Current A/V sync delay: " + cur + " ms.");
            sender.sendMessage("Set with: /" + label + " avsync <ms>"
                    + " — sound arrives late -> increase, sound early -> decrease.");
            return;
        }
        int ms;
        try {
            ms = Integer.parseInt(args[1]);
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
                + cur + "). Applies to the next /video play.");
    }

    // /vmc sub <size|height|depth> [valeur] : affiche ou regle/persiste la geometrie de
    // l'overlay sous-titres (cf config.yml). Prend effet au prochain play.
    private void handleSub(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("[MinecraftVideo] Subtitles: size "
                    + plugin.getConfig().getDouble("subtitle-size", 1.0) + ", height "
                    + plugin.getConfig().getDouble("subtitle-height", 0.45) + ", depth "
                    + plugin.getConfig().getDouble("subtitle-depth", 0.05));
            sender.sendMessage("Set with: /" + label + " sub <size|height|depth> <value>");
            return;
        }
        String key = args[1].toLowerCase(Locale.ROOT);
        double value;
        try {
            value = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("<value> must be a number (e.g. 4, 0.1, 0.2).");
            return;
        }
        String configKey;
        double min;
        double max;
        String unit;
        switch (key) {
            case "size" -> { configKey = "subtitle-size"; min = 0.1; max = 20.0; unit = "text scale"; }
            // height/depth sont des positions : le negatif est autorise (sous le bord bas,
            // ou derriere le plan de l'ecran)
            case "height" -> { configKey = "subtitle-height"; min = -20.0; max = 20.0; unit = "blocks above the bottom edge"; }
            case "depth" -> { configKey = "subtitle-depth"; min = -10.0; max = 10.0; unit = "blocks in front of the screen"; }
            default -> {
                sender.sendMessage("Subtitle setting must be 'size', 'height' or 'depth'.");
                return;
            }
        }
        if (value < min || value > max) {
            sender.sendMessage(key + " must be between " + min + " and " + max + ".");
            return;
        }
        plugin.getConfig().set(configKey, value);
        plugin.saveConfig();
        sender.sendMessage("[MinecraftVideo] Subtitle " + key + " saved: " + value
                + " (" + unit + "). Applies to the next /video play.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            // les mots-cles ; la largeur est numerique, rien a proposer
            return startingWith(KEYWORDS, args[0]);
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "audio" -> startingWith(AudioMode.configNames(), args[1]);
                case "render", "dither" -> startingWith(RenderMode.configNames(), args[1]);
                case "data" -> startingWith(RenderMode.DataWall.configNames(), args[1]);
                case "sub" -> startingWith(List.of("size", "height", "depth"), args[1]);
                case "avsync" -> startingWith(List.of("0", "100", "200", "300", "500"), args[1]);
                default -> List.of();
            };
        }
        return List.of();
    }

    private static List<String> startingWith(List<String> values, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String v : values) {
            if (v.startsWith(prefix)) {
                matches.add(v);
            }
        }
        return matches;
    }
}
