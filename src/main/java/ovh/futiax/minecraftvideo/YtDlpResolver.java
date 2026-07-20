package ovh.futiax.minecraftvideo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Turns a "page" URL (YouTube etc.) into a directly-playable stream URL using
 * {@link YtDlpInstaller yt-dlp}. Direct media URLs and local paths pass through
 * unchanged.
 *
 * <p>A LOW-RES COMBINED format is requested ({@code -f 18/...}) so yt-dlp yields
 * a SINGLE url carrying both video and audio — no separate DASH streams to
 * juggle, which is exactly right for 128px map screens. The resolved
 * {@code googlevideo.com} url (a public CDN, so it clears the SSRF guard) is
 * then handed to the normal cache/download path; downloading it immediately
 * sidesteps the ~6 h expiry of those urls.
 *
 * <p>On any failure this returns the ORIGINAL source, so a direct URL without a
 * media extension still gets tried directly, and a genuine YouTube failure
 * surfaces as the normal "could not load the source" (details in the log).
 */
public final class YtDlpResolver {

    /**
     * Prefer format 18 (360p mp4, video+audio), else the best COMBINED stream
     * (has both a video and an audio codec) at &le;480p, else any combined one.
     * The {@code acodec!=none/vcodec!=none} filters guarantee a single url.
     */
    private static final String FORMAT =
            "18/b[acodec!=none][vcodec!=none][height<=?480]/b[acodec!=none][vcodec!=none]";

    /** Extensions we treat as direct media (skip yt-dlp, hand straight to ffmpeg/mcmm). */
    private static final Set<String> DIRECT_EXTENSIONS = Set.of(
            "mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "flv", "ogv", "ogg",
            "wmv", "mpg", "mpeg", "m3u8", "mpd", "3gp");

    private final YtDlpInstaller installer;
    private final Logger logger;

    public YtDlpResolver(YtDlpInstaller installer, Logger logger) {
        this.installer = installer;
        this.logger = logger;
    }

    /**
     * Returns the URL to actually fetch: {@code source} unchanged for a local
     * path or a direct media URL, or the yt-dlp-resolved stream URL otherwise.
     * Blocking (spawns yt-dlp); call off the main thread.
     */
    public String resolve(String source) {
        if (source == null || !isHttp(source) || hasDirectExtension(source)) {
            return source;
        }
        String exe = installer.getExecutable();
        if (exe == null) {
            return source; // yt-dlp unavailable: let the direct fetch try (and fail cleanly)
        }
        Process process = null;
        try {
            process = new ProcessBuilder(exe, "-g", "-f", FORMAT,
                    "--no-playlist", "--no-warnings", source)
                    .redirectErrorStream(false).start();
            drainStderr(process); // separate thread: yt-dlp can be chatty on stderr
            String out = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                logger.warning("yt-dlp timed out resolving " + source);
                return source;
            }
            if (process.exitValue() != 0 || out.isEmpty()) {
                logger.warning("yt-dlp could not resolve " + source
                        + " (exit " + process.exitValue() + ")");
                return source;
            }
            // The combined format yields one url; take the first line defensively.
            String url = out.lines().findFirst().orElse("").trim();
            if (url.isEmpty()) {
                return source;
            }
            logger.info("Resolved " + source + " via yt-dlp.");
            return url;
        } catch (IOException e) {
            logger.warning("yt-dlp resolve failed for " + source + ": " + e.getMessage());
            return source;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return source;
        }
    }

    private static boolean isHttp(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://");
    }

    /** True if the URL path ends in a known direct-media extension. */
    private static boolean hasDirectExtension(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot > slash && dot < path.length() - 1) {
            return DIRECT_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
        }
        return false;
    }

    private void drainStderr(Process process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.fine("[yt-dlp] " + line);
                }
            } catch (IOException ignored) {
                // process ended / stream closed
            }
        }, "MinecraftVideo-ytdlp-stderr");
        thread.setDaemon(true);
        thread.start();
    }
}
