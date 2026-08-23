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

// Fournit un yt-dlp utilisable, pour que les sources http(s) qui ne sont pas des fichiers
// media directs (YouTube et les centaines d'autres sites que yt-dlp gere) puissent etre
// resolues en flux jouable, SANS que l'admin ait rien a installer.
//
// Ordre de resolution (premier trouve gagne, cache pour la session) :
//   1. un yt-dlp-path configure
//   2. un yt-dlp deja dans le PATH
//   3. un binaire que le plugin telecharge UNE fois depuis les releases GitHub officielles
//      de yt-dlp, dans <dataFolder>/bin
//
// Telecharger plutot qu'embarquer : ca garde le jar minuscule (le yt-dlp standalone fait
// ~30 Mo, et il en faudrait un par plateforme) et surtout ca laisse yt-dlp rester a jour.
// YouTube casse les extracteurs regulierement, donc une copie figee pourrirait et forcerait
// une release du plugin a chaque casse. Le binaire telecharge se met a jour tout seul
// (yt-dlp -U lance en arriere-plan).
//
// getExecutable() peut BLOQUER (le telechargement ponctuel) : jamais depuis le main thread.
// Renvoie null quand yt-dlp est desactive ou indispo (pas de copie dans le PATH, plateforme
// non supportee, download rate) : l'appelant n'a alors juste pas la resolution d'URL.
public final class YtDlpInstaller {

    private static final String RELEASE_BASE =
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";

    private final Logger logger;
    private final Path binDir;
    private final boolean enabled;
    private final String configuredPath;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL) // GitHub -> CDN, URL interne de confiance
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private String resolved;    // chemin utilisable, une fois trouve
    private boolean attempted;  // pour ne pas refaire toute la sonde (avec download) a chaque appel

    public YtDlpInstaller(Logger logger, Path dataFolder, boolean enabled, String configuredPath) {
        this.logger = logger;
        this.binDir = dataFolder.resolve("bin");
        this.enabled = enabled;
        this.configuredPath = configuredPath == null ? "" : configuredPath.trim();
    }

    public boolean isEnabled() {
        return enabled;
    }

    // Rend une commande yt-dlp executable (chemin, ou juste "yt-dlp"), en telechargeant le
    // binaire une fois si besoin, ou null si indispo. BLOQUANT.
    public synchronized String getExecutable() {
        if (!enabled) {
            return null;
        }
        if (resolved != null) {
            return resolved;
        }
        if (attempted) {
            return null; // deja rate cette session, un restart retentera
        }
        attempted = true;

        // 1. override configure
        if (!configuredPath.isBlank() && probe(configuredPath)) {
            resolved = configuredPath;
            return resolved;
        }
        // 2. deja dans le PATH (l'admin l'a installe) : le meilleur cas, c'est lui qui maintient
        if (probe("yt-dlp")) {
            resolved = "yt-dlp";
            return resolved;
        }
        // 3. binaire deja telecharge avant
        Path local = binDir.resolve(localName());
        if (Files.exists(local) && probe(local.toString())) {
            resolved = local.toString();
            updateAsync(resolved);
            return resolved;
        }
        // 4. on le telecharge une bonne fois depuis GitHub
        if (download(local) && probe(local.toString())) {
            resolved = local.toString();
            logger.info("Downloaded yt-dlp -> " + local);
            return resolved;
        }
        logger.warning("yt-dlp is unavailable (no PATH copy, and the download failed);"
                + " YouTube-style URLs won't play. Install yt-dlp or set yt-dlp-path.");
        return null;
    }

    // lance <exe> --version et dit si ca passe
    private boolean probe(String exe) {
        Process process = null;
        try {
            process = new ProcessBuilder(exe, "--version")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            process.getInputStream().readAllBytes(); // vide la sortie, elle est courte
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false; // pas trouve / pas executable
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

    // met a jour le binaire telecharge en tache de fond, au mieux
    private void updateAsync(String exe) {
        Thread thread = new Thread(() -> {
            try {
                Process p = new ProcessBuilder(exe, "-U")
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                p.waitFor(90, TimeUnit.SECONDS);
            } catch (IOException ignored) {
                // tant pis, un binaire un peu vieux marche encore pour la plupart des videos
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
        }
    }

    private static String localName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "yt-dlp.exe" : "yt-dlp";
    }

    // asset de release GitHub pour la plateforme courante, null si pas supportee
    private static String assetName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "yt-dlp.exe"; // standalone (embarque Python), x64
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
