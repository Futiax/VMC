package ovh.futiax.minecraftvideo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Extracts the bundled {@code mcmm} binary and the color palette from the
 * plugin jar into the plugin data folder on first start (or after an update),
 * so a fresh install works without the admin placing those files by hand.
 *
 * The native binary is platform-specific: the jar may contain
 * {@code natives/mcmm-<os>-<arch>} (with {@code .exe} on Windows). If none
 * matches the running platform, {@link #getMcmmPath()} returns {@code null} and
 * the admin must build mcmm and point {@code mcmm-path} at it.
 *
 * ffmpeg is NOT bundled (size and (L)GPL redistribution) and must be in PATH.
 */
public final class NativeInstaller {

    private static final String PALETTE_RESOURCE = "vanilla_map_colors.json";
    private static final String VERSION_MARKER = ".installed-version";

    private final MinecraftVideoPlugin plugin;
    private Path mcmmPath;     // null if no bundled binary for this platform
    private Path palettePath;  // null if the palette resource is missing

    public NativeInstaller(MinecraftVideoPlugin plugin) {
        this.plugin = plugin;
    }

    /** Extracts bundled assets if needed. Safe to call once on enable. */
    public void install() {
        Path dataFolder = plugin.getDataFolder().toPath();
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not create data folder: " + e.getMessage());
            return;
        }

        boolean fresh = needsExtraction(dataFolder);

        // --- Palette (platform-independent) ---
        Path palette = dataFolder.resolve("vanilla_map_colors.json");
        if (extractResource(PALETTE_RESOURCE, palette, fresh, false)) {
            palettePath = palette;
        } else if (Files.exists(palette)) {
            palettePath = palette;
        }

        // --- Native mcmm binary (platform-specific) ---
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

    /** @return the extracted mcmm path, or {@code null} if none is available. */
    public Path getMcmmPath() {
        return mcmmPath;
    }

    /** @return the extracted palette path, or {@code null} if unavailable. */
    public Path getPalettePath() {
        return palettePath;
    }

    /** Re-extract when the plugin version changed (or on a fresh install). */
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

    /**
     * Copies a bundled resource to {@code target}. Only overwrites an existing
     * file when {@code overwrite} is true (i.e. on a fresh install / update).
     *
     * @return true if the resource was written this call.
     */
    private boolean extractResource(String resource, Path target, boolean overwrite, boolean executable) {
        if (Files.exists(target) && !overwrite) {
            return false;
        }
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) {
                return false; // not bundled
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

    /** Maps the running platform to the expected jar resource name, or null. */
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
