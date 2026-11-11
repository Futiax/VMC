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
// /vmc size <width> <height>                 taille d'ecran par defaut (maps)
// /vmc fps <n>                               fps par defaut, separes de la taille : en rendu data
//                                            la taille est imposee par le mur, seuls les fps se reglent
// /vmc audio <mono|stereo|surround>          mode audio
// /vmc avsync <ms>                           delai de sync A/V
// /vmc render <normal|dither|strict|data>    rendu des couleurs ("dither" = ancien nom)
// /vmc data <wan|lan>                        mur du rendu data (4x2 ou 5x3 maps)
// /vmc sub <size|height|depth> <valeur>      geometrie de l'overlay sous-titres
// /vmc debug <on|off>                        temps de chaque etape dans un CSV, jusqu'au redemarrage
public final class VmcCommand implements CommandExecutor, TabCompleter {

    private static final List<String> KEYWORDS = List.of("audio", "avsync", "daemon", "data", "debug", "fps", "render", "settings-info", "size", "sub");

    private final MinecraftVideoPlugin plugin;

    public VmcCommand(MinecraftVideoPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String key = args.length >= 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (key) {
            case "settings-info", "info" -> handleSettingsInfo(sender);
            case "daemon" -> handleDaemon(sender, label, args);
            case "audio" -> handleAudio(sender, label, args);
            case "avsync" -> handleAvsync(sender, label, args);
            case "render", "dither" -> handleRender(sender, label, args);
            case "data" -> handleData(sender, label, args);
            case "sub", "subs", "subtitle" -> handleSub(sender, label, args);
            case "size" -> handleSize(sender, label, args);
            case "fps" -> handleFps(sender, label, args);
            case "debug" -> handleDebug(sender, label, args);
            default -> showSettings(sender, label);
        }
        if (plugin.isDaemonMode() && plugin.getDaemon() != null && plugin.getDaemon().isRunning()) {
            plugin.getDaemon().syncConfigToDaemon();
        }
        return true;
    }

    // en rendu data la taille sauvegardee n'est pas celle de l'ecran : on le dit
    private String dataSizeNote() {
        RenderMode.DataWall wall = RenderMode.DataWall.fromConfig(plugin.getConfig());
        return RenderMode.fromConfig(plugin.getConfig()) == RenderMode.DATA
                ? " (render data: the screen is always the " + wall.configName() + " wall, "
                        + wall.width() + "x" + wall.height() + " maps)"
                : "";
    }

    // /vmc : affiche tout
    private void showSettings(CommandSender sender, String label) {
        sender.sendMessage("[MinecraftVideo] Settings: "
                + plugin.getConfig().getInt("default-width", 4) + "x"
                + plugin.getConfig().getInt("default-height", 3) + " maps @ "
                + plugin.getConfig().getInt("default-fps", 10) + " fps, audio "
                + AudioMode.fromConfig(plugin.getConfig().getString("audio-mode", "mono")).configName()
                + ", avsync " + plugin.getConfig().getInt("av-sync-delay-ms", 200) + " ms, render "
                + RenderMode.fromConfig(plugin.getConfig()).configName() + dataSizeNote());
        sender.sendMessage("Set with: /" + label + " size <width> <height>");
        sender.sendMessage("      or: /" + label + " fps <1-" + VideoCommand.MAX_FPS + ">");
        sender.sendMessage("      or: /" + label + " audio <mono|stereo|surround>");
        sender.sendMessage("      or: /" + label + " avsync <ms>");
        sender.sendMessage("      or: /" + label + " render <normal|dither|strict|data>");
        sender.sendMessage("      or: /" + label + " data <wan|lan>");
        sender.sendMessage("      or: /" + label + " sub <size|height|depth> <value>");
        sender.sendMessage("      or: /" + label + " debug <on|off>");
        sender.sendMessage("      or: /" + label + " daemon <on|off>");
        sender.sendMessage("      or: /" + label + " settings-info");
    }

    // /vmc settings-info : affiche l'etat detaille de tous les reglages actifs
    private void handleSettingsInfo(CommandSender sender) {
        var cfg = plugin.getConfig();
        RenderMode render = RenderMode.fromConfig(cfg);
        RenderMode.DataWall wall = RenderMode.DataWall.fromConfig(cfg);
        int defW = cfg.getInt("default-width", 4);
        int defH = cfg.getInt("default-height", 3);
        int effW = render == RenderMode.DATA ? wall.width() : defW;
        int effH = render == RenderMode.DATA ? wall.height() : defH;
        int fps = cfg.getInt("default-fps", 10);
        boolean daemonOn = plugin.isDaemonMode();
        boolean daemonAlive = plugin.getDaemon() != null && plugin.getDaemon().isRunning();
        boolean debugOn = plugin.isDebugTimings();
        String audioMode = AudioMode.fromConfig(cfg.getString("audio-mode", "mono")).configName();
        boolean audioEnabled = cfg.getBoolean("audio-enabled", true);
        int avsync = cfg.getInt("av-sync-delay-ms", 200);
        int startDelay = cfg.getInt("audio-start-delay-ms", 1000);
        double subSize = cfg.getDouble("subtitle-size", 1.0);
        double subHeight = cfg.getDouble("subtitle-height", 0.45);
        double subDepth = cfg.getDouble("subtitle-depth", 0.05);
        int viewerRange = cfg.getInt("viewer-range-blocks", 64);
        boolean controlBar = cfg.getBoolean("control-bar-enabled", true);
        String postEffect = cfg.getString("post-effect", "");
        String activeEffect = plugin.getPostEffects().effectFor(render, wall);

        sender.sendMessage("§b=== [MinecraftVideo] Settings Info ===");
        sender.sendMessage("  §7C-Daemon      : §f" + (daemonOn ? "ON" + (daemonAlive ? " (running)" : " (idle)") : "OFF"));
        sender.sendMessage("  §7Debug CSV     : §f" + (debugOn ? "ON (plugins/MinecraftVideo/debug/)" : "OFF"));
        sender.sendMessage("  §7Render mode   : §f" + render.configName()
                + (render == RenderMode.DATA ? " (wall: " + wall.configName() + " " + wall.width() + "x" + wall.height() + ")" : ""));
        sender.sendMessage("  §7Screen size   : §f" + effW + "x" + effH + " maps"
                + (render == RenderMode.DATA ? " (forced by data wall; config default: " + defW + "x" + defH + ")" : ""));
        sender.sendMessage("  §7Framerate     : §f" + fps + " fps (budget " + (1000.0 / fps) + " ms/frame)");
        sender.sendMessage("  §7Post-effect   : §f" + (activeEffect.isEmpty() ? "none" : activeEffect)
                + (postEffect != null && !postEffect.isBlank() ? " (configured: " + postEffect + ")" : ""));
        sender.sendMessage("  §7Viewer range  : §f" + (viewerRange <= 0 ? "unlimited" : viewerRange + " blocks")
                + " | Control bar: " + (controlBar && render != RenderMode.DATA ? "ON" : "OFF"));
        sender.sendMessage("  §7Audio         : §f" + (audioEnabled ? audioMode : "disabled")
                + " (avsync: " + avsync + " ms, start-delay: " + startDelay + " ms)");
        sender.sendMessage("  §7Subtitles     : §fsize=" + subSize + ", height=" + subHeight + ", depth=" + subDepth);
    }

    // /vmc daemon [on|off] : active ou desactive le mode ou le demon C gere toute la session
    private void handleDaemon(CommandSender sender, String label, String[] args) {
        String value = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (!value.equals("on") && !value.equals("off")) {
            sender.sendMessage("[MinecraftVideo] C-Daemon mode is "
                    + (plugin.isDaemonMode() ? "ON" : "OFF")
                    + ". Set with: /" + label + " daemon <on|off>");
            return;
        }
        boolean on = value.equals("on");
        plugin.getConfig().set("c-daemon", on);
        plugin.saveConfig();
        plugin.setDaemonMode(on);
        sender.sendMessage(on
                ? "[MinecraftVideo] C-Daemon mode enabled (mcmm --daemon manages screen, pacing, diff & seek)."
                : "[MinecraftVideo] C-Daemon mode disabled (reverted to Java PlaybackSession).");
    }

    // /vmc debug [on|off] : pas persiste, et pas de fichier tant qu'aucune frame n'est mesuree
    private void handleDebug(CommandSender sender, String label, String[] args) {
        String value = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (!value.equals("on") && !value.equals("off")) {
            sender.sendMessage("[MinecraftVideo] Debug timings are "
                    + (plugin.isDebugTimings() ? "on" : "off") + ". Set with: /" + label + " debug <on|off>");
            return;
        }
        boolean on = value.equals("on");
        plugin.setDebugTimings(on);
        sender.sendMessage(on
                ? "[MinecraftVideo] Debug timings on until the next restart: one CSV per video, starting now, in "
                        + plugin.getDataFolder().toPath().resolve("debug") + "."
                : "[MinecraftVideo] Debug timings off.");
    }

    // /vmc size [<width> <height>] : affiche ou regle/persiste la taille d'ecran par defaut
    private void handleSize(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("[MinecraftVideo] Default screen size: "
                    + plugin.getConfig().getInt("default-width", 4) + "x"
                    + plugin.getConfig().getInt("default-height", 3) + " maps" + dataSizeNote() + ".");
            sender.sendMessage("Set with: /" + label + " size <width> <height>");
            return;
        }
        int width;
        int height;
        try {
            width = Integer.parseInt(args[1]);
            height = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("<width> and <height> must be whole numbers.");
            return;
        }
        if (width < 1 || width > VideoCommand.MAX_DIMENSION
                || height < 1 || height > VideoCommand.MAX_DIMENSION) {
            sender.sendMessage("Screen size must be between 1x1 and "
                    + VideoCommand.MAX_DIMENSION + "x" + VideoCommand.MAX_DIMENSION + " maps.");
            return;
        }
        plugin.getConfig().set("default-width", width);
        plugin.getConfig().set("default-height", height);
        plugin.saveConfig();
        sender.sendMessage("[MinecraftVideo] Default screen size saved: " + width + "x" + height
                + " maps" + dataSizeNote() + ". Applies to the next /video play, /photo or /stream.");
    }

    // /vmc fps [n] : affiche ou regle/persiste les fps par defaut
    private void handleFps(CommandSender sender, String label, String[] args) {
        int cur = plugin.getConfig().getInt("default-fps", 10);
        if (args.length < 2) {
            sender.sendMessage("[MinecraftVideo] Default frame rate: " + cur + " fps.");
            sender.sendMessage("Set with: /" + label + " fps <1-" + VideoCommand.MAX_FPS + ">");
            return;
        }
        int fps;
        try {
            fps = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage("<fps> must be a whole number.");
            return;
        }
        if (fps < 1 || fps > VideoCommand.MAX_FPS) {
            sender.sendMessage("fps must be between 1 and " + VideoCommand.MAX_FPS + ".");
            return;
        }
        plugin.getConfig().set("default-fps", fps);
        plugin.saveConfig();
        sender.sendMessage("[MinecraftVideo] Default frame rate saved: " + fps + " fps (was " + cur
                + "). Bandwidth and server load follow it. Applies to the next /video play or /stream.");
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
    // qui ne se lit en 1080p que d'une plage etroite (2,1 a 3,1 blocs en FOV 70)
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
            return startingWith(KEYWORDS, args[0]);
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "audio" -> startingWith(AudioMode.configNames(), args[1]);
                case "render", "dither" -> startingWith(RenderMode.configNames(), args[1]);
                case "data" -> startingWith(RenderMode.DataWall.configNames(), args[1]);
                case "sub" -> startingWith(List.of("size", "height", "depth"), args[1]);
                case "avsync" -> startingWith(List.of("0", "100", "200", "300", "500"), args[1]);
                case "fps" -> startingWith(List.of("5", "10", "15", "20"), args[1]);
                case "debug", "daemon" -> startingWith(List.of("on", "off"), args[1]);
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
