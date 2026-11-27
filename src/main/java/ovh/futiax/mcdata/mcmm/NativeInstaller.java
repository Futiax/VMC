package ovh.futiax.mcdata.mcmm;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

// Extrait le binaire mcmm et la palette vanilla du jar vers le dossier du plugin au premier
// demarrage (ou apres une mise a jour de version).
public final class NativeInstaller {

    private static final String PALETTE_RESOURCE = "vanilla_map_colors.json";
    private static final String VERSION_MARKER = ".installed-version";

    private final JavaPlugin plugin;
    private Path mcmmPath;
    private Path palettePath;

    public NativeInstaller(JavaPlugin plugin) {
        this.plugin = plugin;
    }

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
        if (extractResource(PALETTE_RESOURCE, palette, fresh, false) || Files.exists(palette)) {
            palettePath = palette;
        }

        String resource = mcmmResourceName();
        if (resource == null) {
            plugin.getLogger().warning("Unsupported platform for bundled mcmm ("
                    + System.getProperty("os.name") + "/" + System.getProperty("os.arch")
                    + "); set mcmm-path in config.yml.");
        } else {
            boolean windows = resource.endsWith(".exe");
            Path target = dataFolder.resolve(windows ? "mcmm.exe" : "mcmm");
            if (extractResource(resource, target, fresh, !windows) || Files.exists(target)) {
                mcmmPath = target;
            } else {
                plugin.getLogger().warning("No bundled mcmm for this platform; set mcmm-path in config.yml.");
            }
        }
        if (fresh) writeVersionMarker(dataFolder);
    }

    public Path getMcmmPath() { return mcmmPath; }
    public Path getPalettePath() { return palettePath; }

    // config override: mcmm-path / palette-path
    public void applyConfigOverrides() {
        String mcmmOverride = plugin.getConfig().getString("mcmm-path", "");
        if (mcmmOverride != null && !mcmmOverride.isBlank() && Files.isExecutable(Path.of(mcmmOverride))) {
            mcmmPath = Path.of(mcmmOverride);
        }
        String paletteOverride = plugin.getConfig().getString("palette-path", "");
        if (paletteOverride != null && !paletteOverride.isBlank() && Files.exists(Path.of(paletteOverride))) {
            palettePath = Path.of(paletteOverride);
        }
    }

    private boolean needsExtraction(Path dataFolder) {
        Path marker = dataFolder.resolve(VERSION_MARKER);
        if (!Files.exists(marker)) return true;
        try {
            return !Files.readString(marker).trim().equals(plugin.getPluginMeta().getVersion());
        } catch (IOException e) {
            return true;
        }
    }

    private void writeVersionMarker(Path dataFolder) {
        try {
            Files.writeString(dataFolder.resolve(VERSION_MARKER), plugin.getPluginMeta().getVersion());
        } catch (IOException ignored) {}
    }

    private boolean extractResource(String resource, Path target, boolean overwrite, boolean executable) {
        if (Files.exists(target) && !overwrite) return false;
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) return false;
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            if (executable) target.toFile().setExecutable(true, false);
            plugin.getLogger().info("Installed bundled " + resource + " -> " + target);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to extract " + resource + ": " + e.getMessage());
            return false;
        }
    }

    private static String mcmmResourceName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String archKey;
        if (arch.equals("amd64") || arch.equals("x86_64")) archKey = "x64";
        else if (arch.equals("aarch64") || arch.equals("arm64")) archKey = "arm64";
        else return null;
        if (os.contains("win")) return "natives/mcmm-windows-" + archKey + ".exe";
        if (os.contains("linux")) return "natives/mcmm-linux-" + archKey;
        if (os.contains("mac") || os.contains("darwin")) return "natives/mcmm-macos-" + archKey;
        return null;
    }
}
