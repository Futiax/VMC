package ovh.futiax.minecraftvideo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

// Sort le binaire mcmm et la palette de couleurs du jar vers le dossier du plugin au premier
// demarrage (ou apres une mise a jour), pour qu'une install fraiche marche sans que l'admin
// aille poser les fichiers a la main.
//
// Le binaire natif depend de la plateforme : le jar peut contenir natives/mcmm-<os>-<arch>
// (avec .exe sous Windows). Si rien ne matche, getMcmmPath() renvoie null et l'admin doit
// compiler mcmm lui-meme et pointer mcmm-path dessus.
//
// ffmpeg n'est PAS embarque (taille + redistribution (L)GPL), il doit etre dans le PATH.
public final class NativeInstaller {

    private static final String PALETTE_RESOURCE = "vanilla_map_colors.json";
    private static final String VERSION_MARKER = ".installed-version";

    private final MinecraftVideoPlugin plugin;
    private Path mcmmPath;     // null si pas de binaire embarque pour cette plateforme
    private Path palettePath;  // null si la ressource palette manque

    public NativeInstaller(MinecraftVideoPlugin plugin) {
        this.plugin = plugin;
    }

    // Extrait ce qu'il faut. A appeler une fois a l'enable.
    public void install() {
        Path dataFolder = plugin.getDataFolder().toPath();
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not create data folder: " + e.getMessage());
            return;
        }

        boolean fresh = needsExtraction(dataFolder);

        Path palette = dataFolder.resolve("vanilla_map_colors.json");
        if (extractResource(PALETTE_RESOURCE, palette, fresh, false)) {
            palettePath = palette;
        } else if (Files.exists(palette)) {
            palettePath = palette;
        }

        String resource = mcmmResourceName();
        if (resource == null) {
            plugin.getLogger().warning("Unsupported platform for a bundled mcmm ("
                    + System.getProperty("os.name") + " / " + System.getProperty("os.arch")
                    + "); set mcmm-path in config.yml to a binary you built.");
        } else {
            boolean windows = resource.endsWith(".exe");
            Path target = dataFolder.resolve(windows ? "mcmm.exe" : "mcmm");
            if (extractResource(resource, target, fresh, !windows)) {
                mcmmPath = target;
            } else if (Files.exists(target)) {
                mcmmPath = target;
            } else {
                plugin.getLogger().warning("No bundled mcmm binary for this platform ("
                        + resource + " not found in the jar); set mcmm-path in config.yml.");
            }
        }

        if (fresh) {
            writeVersionMarker(dataFolder);
        }
    }

    public Path getMcmmPath() {         // null si rien de dispo
        return mcmmPath;
    }

    public Path getPalettePath() {      // idem
        return palettePath;
    }

    // On re-extrait quand la version du plugin a change (ou install fraiche).
    private boolean needsExtraction(Path dataFolder) {
        Path marker = dataFolder.resolve(VERSION_MARKER);
        if (!Files.exists(marker)) {
            return true;
        }
        try {
            String recorded = Files.readString(marker).trim();
            return !recorded.equals(plugin.getPluginMeta().getVersion());
        } catch (IOException e) {
            return true;
        }
    }

    private void writeVersionMarker(Path dataFolder) {
        try {
            Files.writeString(dataFolder.resolve(VERSION_MARKER),
                    plugin.getPluginMeta().getVersion());
        } catch (IOException e) {
            plugin.getLogger().fine("Could not write version marker: " + e.getMessage());
        }
    }

    // Copie une ressource du jar vers target. N'ecrase un fichier existant que si overwrite
    // (donc install fraiche / mise a jour). Renvoie true si on a vraiment ecrit.
    private boolean extractResource(String resource, Path target, boolean overwrite, boolean executable) {
        if (Files.exists(target) && !overwrite) {
            return false;
        }
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) {
                return false; // pas embarque
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            if (executable) {
                target.toFile().setExecutable(true, false);
            }
            plugin.getLogger().info("Installed bundled " + resource + " -> " + target);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to extract " + resource + ": " + e.getMessage());
            return false;
        }
    }

    // plateforme courante -> nom de ressource attendu dans le jar, null si on ne sait pas
    private static String mcmmResourceName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        String archKey;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            archKey = "x64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            archKey = "arm64";
        } else {
            return null;
        }

        if (os.contains("win")) {
            return "natives/mcmm-windows-" + archKey + ".exe";
        } else if (os.contains("linux")) {
            return "natives/mcmm-linux-" + archKey;
        } else if (os.contains("mac") || os.contains("darwin")) {
            return "natives/mcmm-macos-" + archKey;
        }
        return null;
    }
}
