package ovh.futiax.minecraftvideo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Provides a usable {@code yt-dlp} executable so http(s) sources that aren't
 * direct media files (YouTube and the hundreds of other sites yt-dlp supports)
 * can be resolved to a playable stream URL — WITHOUT the admin installing
 * anything.
 *
 * <p>Resolution order (first hit wins, cached for the session):
 * <ol>
 *   <li>a configured {@code yt-dlp-path};</li>
 *   <li>a {@code yt-dlp} already on the system PATH;</li>
 *   <li>a binary this plugin downloads ONCE from yt-dlp's official GitHub
 *       releases into {@code <dataFolder>/bin}.</li>
 * </ol>
 *
 * <p>Downloading rather than bundling keeps the jar tiny (the standalone yt-dlp
 * is ~30 MB, and it would need one per platform) and — crucially — lets yt-dlp
 * stay current: YouTube breaks extractors often, so a frozen bundled copy would
 * rot and force a plugin re-release on every break. The downloaded binary
 * self-updates ({@code yt-dlp -U}, kicked off in the background).
 *
 * <p>{@link #getExecutable()} may block (the one-off download); call it off the
 * main thread. It returns {@code null} when yt-dlp is disabled or unavailable
 * (no PATH copy, platform unsupported, download failed) — the caller then just
 * doesn't get YouTube-style URL resolution.
 */
public final class YtDlpInstaller {

    private static final String RELEASE_BASE =
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";

    private final Logger logger;
    private final Path binDir;
    private final boolean enabled;
    private final String configuredPath;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL) // GitHub -> CDN; internal, trusted URL
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private String resolved;    // cached usable path once found
    private boolean attempted;  // don't re-run the whole (possibly downloading) probe every call

    public YtDlpInstaller(Logger logger, Path dataFolder, boolean enabled, String configuredPath) {
        this.logger = logger;
        this.binDir = dataFolder.resolve("bin");
        this.enabled = enabled;
        this.configuredPath = configuredPath == null ? "" : configuredPath.trim();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns a runnable yt-dlp command (path or bare "yt-dlp"), downloading the
     * binary once if needed, or {@code null} if unavailable. Blocking.
     */
    public synchronized String getExecutable() {
        if (!enabled) {
            return null;
        }
        if (resolved != null) {
            return resolved;
        }
        if (attempted) {
            return null; // already failed this session; a restart retries
        }
        attempted = true;

        // 1. Configured override.
        if (!configuredPath.isBlank() && probe(configuredPath)) {
            resolved = configuredPath;
            return resolved;
        }
        // 2. Already on PATH (admin installed it) — preferred, they maintain it.
        if (probe("yt-dlp")) {
            resolved = "yt-dlp";
            return resolved;
        }
        // 3. Previously downloaded binary.
        Path local = binDir.resolve(localName());
        if (Files.exists(local) && probe(local.toString())) {
            resolved = local.toString();
            updateAsync(resolved);
            return resolved;
        }
        // 4. Download it once from GitHub.
        if (download(local) && probe(local.toString())) {
            resolved = local.toString();
            logger.info("Downloaded yt-dlp -> " + local);
            return resolved;
        }
        logger.warning("yt-dlp is unavailable (no PATH copy, and the download failed);"
                + " YouTube-style URLs won't play. Install yt-dlp or set yt-dlp-path.");
        return null;
    }

    /** Runs {@code <exe> --version} and returns whether it succeeds. */
    private boolean probe(String exe) {
        Process process = null;
        try {
            process = new ProcessBuilder(exe, "--version")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            process.getInputStream().readAllBytes(); // drain the short output
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false; // not found / not executable
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        }
    }

    private boolean download(Path target) {
        String asset = assetName();
        if (asset == null) {
            logger.warning("No yt-dlp build for this platform ("
                    + System.getProperty("os.name") + " / " + System.getProperty("os.arch")
                    + "); install yt-dlp manually or set yt-dlp-path.");
            return false;
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(binDir);
            HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASE_BASE + asset))
                    .header("User-Agent", "MinecraftVideo-plugin")
                    .GET().build();
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                logger.warning("Downloading yt-dlp failed: HTTP " + response.statusCode());
                return false;
            }
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(tmp)) {
                in.transferTo(out);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().setExecutable(true, false);
            return true;
        } catch (IOException e) {
            deleteQuietly(tmp);
            logger.warning("Downloading yt-dlp failed: " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteQuietly(tmp);
            return false;
        }
    }

    /** Self-updates the downloaded binary in the background (best-effort). */
    private void updateAsync(String exe) {
        Thread thread = new Thread(() -> {
            try {
                Process p = new ProcessBuilder(exe, "-U")
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                p.waitFor(90, TimeUnit.SECONDS);
            } catch (IOException ignored) {
                // best-effort; a stale binary still works for most videos
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "MinecraftVideo-ytdlp-update");
        thread.setDaemon(true);
        thread.start();
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** Local file name for the downloaded binary. */
    private static String localName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "yt-dlp.exe" : "yt-dlp";
    }

    /** GitHub release asset for the running platform, or null if unsupported. */
    private static String assetName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "yt-dlp.exe"; // standalone (bundles Python), x64
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "yt-dlp_macos"; // universal
        }
        if (os.contains("linux")) {
            if (arch.equals("amd64") || arch.equals("x86_64")) {
                return "yt-dlp_linux";
            }
            if (arch.equals("aarch64") || arch.equals("arm64")) {
                return "yt-dlp_linux_aarch64";
            }
        }
        return null;
    }
}
