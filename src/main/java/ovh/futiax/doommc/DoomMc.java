package ovh.futiax.doommc;

import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import ovh.futiax.mcdata.McData;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

// DOOMMC : Doom dans Minecraft, dessine par les shaders du resource pack a partir de l'etat envoye
// par le serveur. /doomprobe : essai du canal serveur -> shader et de la rotation de la camera lue
// cote client ; /doom : E1M1 joue par le vrai moteur (doomgeneric), un processus par partie (solo,
// coop ou arene, 1 a 4 joueurs : DoomGames).
// Module heberge par MCDATA : getConfig/getLogger/... delegent au plugin hote, host() sert la ou
// Bukkit veut un vrai Plugin. Le resource pack est celui de MCDATA.
public final class DoomMc {

    private final McData host;

    public DoomMc(McData host) {
        this.host = host;
    }

    McData host() { return host; }
    FileConfiguration getConfig() { return host.getConfig(); }
    java.util.logging.Logger getLogger() { return host.getLogger(); }
    Server getServer() { return host.getServer(); }
    File getDataFolder() { return host.getDataFolder(); }
    InputStream getResource(String name) { return host.getResource(name); }
    private PluginCommand getCommand(String name) { return host.getCommand(name); }

    private PostEffects postEffects;
    private DoomProbe doomProbe;
    private DoomGames games;

    public void onEnable() {
        postEffects = new PostEffects(this, List.of(DoomProbe.EFFECT, DoomProbe.VIEW_EFFECT));
        doomProbe = new DoomProbe(this);
        games = new DoomGames(this);
        extractEngine();

        probeCommand();
        PluginCommand doom = getCommand("doom");
        if (doom != null) {
            DoomCommand handler = new DoomCommand(this);
            doom.setExecutor(handler);
            doom.setTabCompleter(handler);
        }
        getServer().getPluginManager().registerEvents(new JoinListener(this), host);
        getServer().getPluginManager().registerEvents(new DoomListener(this), host);
    }

    // Le moteur (make engine) est embarque dans le jar et copie dans le dossier du plugin. Copie
    // puis renommage : on ne reecrit pas un executable en cours d'execution (ETXTBSY).
    private void extractEngine() {
        try (InputStream in = getResource("doommc-engine")) {
            if (in == null) {
                getLogger().warning("This jar was built without the Doom engine (make engine): /doom is unavailable.");
                return;
            }
            File out = engineBinary();
            File tmp = new File(out.getPath() + ".tmp");
            getDataFolder().mkdirs();
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            tmp.setExecutable(true);
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            getLogger().warning("Could not extract the Doom engine: " + e.getMessage());
        }
    }

    private File engineBinary() {
        return new File(getDataFolder(), "doommc-engine");
    }

    // Avertit (sans bloquer) si le WAD n'est pas celui dont le pack a ete tire (level.properties)
    private void checkWad(File wad) {
        try (InputStream in = getResource("level.properties")) {
            if (in == null) {
                return;
            }
            java.util.Properties level = new java.util.Properties();
            level.load(in);
            String expected = level.getProperty("wad-sha1", "");
            java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
            String actual = java.util.HexFormat.of().formatHex(sha1.digest(Files.readAllBytes(wad.toPath())));
            if (!actual.equals(expected)) {
                getLogger().warning("WAD " + wad + " (sha1 " + actual + ") is not the one the resource pack was built from"
                        + " (sha1 " + expected + "): level and sprites will not match.");
            }
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            getLogger().warning("Could not check the WAD: " + e.getMessage());
        }
    }

    // Le WAD doit etre celui dont le pack a ete tire (make level) : memes indices de sprites.
    EngineProcess startEngine(String name, List<String> args) throws IOException {
        File binary = engineBinary();
        if (!binary.canExecute()) {
            throw new IOException("no engine binary at " + binary);
        }
        File wad = new File(getConfig().getString("wad", "/usr/share/games/doom/freedoom1.wad"));
        if (!wad.isFile()) {
            throw new IOException("WAD not found: " + wad + " (config.yml: wad)");
        }
        checkWad(wad);
        return new EngineProcess(binary, wad, new File(new File(getDataFolder(), "engine"), name), name, args);
    }

    // /doomprobe [on|off] [joueur] : bascule (ou on|off), pour soi ou pour un autre joueur (la console
    // doit nommer un joueur) ; quitte la partie de Doom en cours
    private void probeCommand() {
        PluginCommand command = getCommand("doomprobe");
        if (command == null) {
            return;
        }
        command.setExecutor((sender, cmd, label, args) -> {
            Boolean wanted = null;
            Player player = sender instanceof Player self ? self : null;
            for (String arg : args) {
                if (arg.equalsIgnoreCase("on") || arg.equalsIgnoreCase("off")) {
                    wanted = arg.equalsIgnoreCase("on");
                } else if ((player = getServer().getPlayerExact(arg)) == null) {
                    sender.sendMessage("[DOOMMC] No online player named " + arg + ".");
                    return true;
                }
            }
            if (player == null) {
                sender.sendMessage("[DOOMMC] Usage: /" + label + " [on|off] <player>");
                return true;
            }
            if (wanted != null ? wanted : !doomProbe.isRunning(player)) {
                games.leave(player);
                doomProbe.start(player);
                player.sendMessage("[DOOMMC] Probe on: counter and angles in the bottom-left corner. /" + label + " off to stop.");
            } else {
                doomProbe.stop(player);
                player.sendMessage("[DOOMMC] Probe off.");
            }
            return true;
        });
    }

    public void onDisable() {
        if (games != null) {
            games.stopAll();
        }
        if (doomProbe != null) {
            doomProbe.stopAll();
        }
        if (postEffects != null) {
            postEffects.clearAll();
        }
    }

    PostEffects getPostEffects() {
        return postEffects;
    }

    DoomProbe getDoomProbe() {
        return doomProbe;
    }

    DoomGames getGames() {
        return games;
    }
}
