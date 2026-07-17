package ovh.futiax.minecraftvideo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Downloads remote (http/https) sources to a local file once, so every pipeline
 * of a playback — mcmm video, ffmpeg audio, ffprobe, ffmpeg subtitles — reads
 * the LOCAL file instead of each opening its own connection to the origin. That
 * removes the concurrent-connection load that makes rate-limiting hosts (e.g.
 * archive.org) answer 5XX, and means a seek or a subtitle toggle never
 * re-fetches.
 *
 * <p><b>Reference counting.</b> {@link #reference} is called once per live
 * occurrence of a URL (each queue entry, plus the play about to start), and
 * {@link #release} when that occurrence ends. The file is downloaded on the
 * first {@link #acquire} and deleted only when the LAST reference is released —
 * so the same URL queued N times downloads once and survives until its final
 * playback ends.
 *
 * <p><b>Hard size cap.</b> A remote source whose size exceeds
 * {@code cache-max-size-mb} is refused (not streamed): {@link #acquire} throws.
 *
 * <p>Local file paths are never cached (already local): every method is a
 * passthrough no-op for them, and {@link #acquire} returns the path unchanged.
 *
 * <p>NOTE: this is the only part of the plugin that writes to disk. Files live
 * under {@code <dataFolder>/cache} and are removed on release, on enable
 * (orphans left by a crash) and on disable. Thread-safety: {@link #reference}/
 * {@link #release} take the monitor briefly; {@link #acquire} downloads OUTSIDE
 * the monitor (so a long download never blocks the main thread doing queue ops)
 * and only re-takes it to publish the file.
 */
public final class MediaCache {

    private final Logger logger;
    private final Path cacheDir;
    private final boolean enabled;
    private final long maxBytes;

    /** One cached URL: the local file (null until downloaded) and its refcount. */
    private static final class Entry {
        Path file;
        int refs;
    }

    private final Map<String, Entry> entries = new HashMap<>();

    /** Redirects are followed MANUALLY so each hop's host is re-validated (below). */
    private static final int MAX_REDIRECTS = 5;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public MediaCache(Logger logger, Path dataFolder, boolean enabled, int maxSizeMb) {
        this.logger = logger;
        this.cacheDir = dataFolder.resolve("cache");
        this.enabled = enabled;
        this.maxBytes = Math.max(0L, (long) maxSizeMb) * 1024L * 1024L;
        if (enabled) {
            purgeDir(); // clear orphans left by a previous crash/stop
        }
    }

    /** Whether {@code source} is a remote URL this cache handles. */
    public boolean isCacheable(String source) {
        if (!enabled || source == null) {
            return false;
        }
        String s = source.toLowerCase(Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /** Registers one live occurrence of {@code source}. No-op for local paths. */
    public synchronized void reference(String source) {
        if (!isCacheable(source)) {
            return;
        }
        entries.computeIfAbsent(source, k -> new Entry()).refs++;
    }

    /**
     * Releases one occurrence of {@code source}; deletes the file once the last
     * reference is gone. No-op for local paths or an unknown source.
     */
    public synchronized void release(String source) {
        if (!isCacheable(source)) {
            return;
        }
        Entry e = entries.get(source);
        if (e == null) {
            return;
        }
        if (--e.refs <= 0) {
            entries.remove(source);
            if (e.file != null) {
                try {
                    Files.deleteIfExists(e.file);
                } catch (IOException ex) {
                    logger.warning("Could not delete cached file " + e.file + ": " + ex.getMessage());
                }
            }
        }
    }

    /**
     * Returns a local path to read for {@code source}, downloading it first if it
     * is a not-yet-cached remote URL. For a local path the source is returned
     * unchanged. Blocking (the download); call it off the main thread.
     *
     * <p>A reference for this occurrence MUST already be held (see
     * {@link #reference}) so the file cannot be evicted mid-download.
     *
     * @param abort polled during the download; if it turns true the download is
     *              aborted (used to stop a download when the session is stopped)
     * @throws IOException if the source exceeds the size cap, the download fails,
     *                     or it was aborted
     */
    public String acquire(String source, BooleanSupplier abort) throws IOException {
        if (!isCacheable(source)) {
            return source; // local path: read it directly
        }
        synchronized (this) {
            Entry e = entries.get(source);
            if (e != null && e.file != null) {
                return e.file.toString(); // already downloaded (e.g. queued twice)
            }
        }
        // Download outside the lock so queue ops on the main thread never block.
        Path file = download(source, abort);
        synchronized (this) {
            Entry e = entries.get(source);
            if (e == null) {
                // Every reference was released while we downloaded (should not
                // happen: the caller holds one). Don't leak the file.
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // best-effort
                }
                throw new IOException("source no longer referenced");
            }
            e.file = file;
            return file.toString();
        }
    }

    private Path download(String source, BooleanSupplier abort) throws IOException {
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new IOException("cannot create cache dir " + cacheDir + ": " + e.getMessage());
        }
        Path target = cacheDir.resolve(cacheName(source));
        Path tmp = cacheDir.resolve(cacheName(source) + ".part");

        HttpResponse<InputStream> response = fetch(source);
        // Reject early on an advertised size over the cap.
        long advertised = response.headers().firstValueAsLong("content-length").orElse(-1L);
        if (maxBytes > 0 && advertised > maxBytes) {
            response.body().close();
            throw new IOException("source is " + (advertised / (1024 * 1024))
                    + " MB, over the " + (maxBytes / (1024 * 1024)) + " MB cache cap");
        }

        long total = 0;
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (abort.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw new IOException("download aborted");
                }
                total += n;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new IOException("source exceeds the "
                            + (maxBytes / (1024 * 1024)) + " MB cache cap");
                }
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw e;
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new IOException("could not finalize cache file: " + e.getMessage());
        }
        logger.info("Cached " + source + " -> " + target + " (" + (total / (1024 * 1024)) + " MB)");
        return target;
    }

    /**
     * Issues the GET, following redirects MANUALLY so every hop's host is
     * re-validated against {@link #assertPublicHost} (SSRF guard). Returns the
     * 200 response with an open body; the caller consumes and closes it.
     */
    private HttpResponse<InputStream> fetch(String source) throws IOException {
        URI uri = assertPublicHost(source);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("User-Agent", "MinecraftVideo-plugin")
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("could not open " + source + ": " + e.getMessage());
            }
            int code = response.statusCode();
            if (code == 200) {
                return response;
            }
            if (isRedirect(code)) {
                response.body().close();
                String location = response.headers().firstValue("location").orElse(null);
                if (location == null) {
                    throw new IOException("redirect with no Location for " + source);
                }
                // Resolve relative redirects against the current hop, then
                // re-validate the new host: a public origin must not be able to
                // bounce us onto an internal address.
                uri = assertPublicHost(uri.resolve(location).toString());
                continue;
            }
            response.body().close();
            throw new IOException("server returned HTTP " + code + " for " + source);
        }
        throw new IOException("too many redirects for " + source);
    }

    /**
     * Parses {@code url}, requires an http/https scheme, and refuses it if ANY
     * resolved IP is non-public (loopback, link-local incl. 169.254 cloud
     * metadata, private/site-local, CGNAT, IPv6 ULA, wildcard, multicast). This
     * is the SSRF guard: it stops a caller from making the server reach internal
     * services or a cloud metadata endpoint. (Residual DNS-rebind between this
     * check and the actual connect is out of scope for this threat model.)
     */
    private static URI assertPublicHost(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IOException("unsupported URL scheme");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IOException("URL has no host");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IOException("cannot resolve host " + host);
        }
        for (InetAddress address : addresses) {
            if (isNonPublic(address)) {
                throw new IOException("refusing to fetch a non-public/internal address");
            }
        }
        return uri;
    }

    /** True for any address that must not be reachable via a user-supplied URL. */
    private static boolean isNonPublic(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int o0 = b[0] & 0xFF, o1 = b[1] & 0xFF;
            if (o0 == 0) {
                return true; // 0.0.0.0/8
            }
            if (o0 == 100 && o1 >= 64 && o1 <= 127) {
                return true; // 100.64.0.0/10 carrier-grade NAT
            }
        } else if (b.length == 16) {
            int first = b[0] & 0xFF;
            if (first == 0xFC || first == 0xFD) {
                return true; // fc00::/7 IPv6 unique-local (not covered by isSiteLocal)
            }
        }
        return false;
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    /** Deletes every cached file (called on enable for orphans and on disable). */
    public synchronized void purgeAll() {
        entries.clear();
        purgeDir();
    }

    private void purgeDir() {
        if (!Files.isDirectory(cacheDir)) {
            return;
        }
        try (var stream = Files.newDirectoryStream(cacheDir)) {
            for (Path p : stream) {
                deleteQuietly(p);
            }
        } catch (IOException e) {
            logger.warning("Could not clean cache dir " + cacheDir + ": " + e.getMessage());
        }
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** A collision-free, filesystem-safe file name: sha-256(url) + guessed ext. */
    private static String cacheName(String source) {
        String hash;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            hash = sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            hash = Integer.toHexString(source.hashCode()); // SHA-256 is always present
        }
        String ext = guessExtension(source);
        return ext.isEmpty() ? hash : hash + "." + ext;
    }

    /** Best-effort extension from the URL path (ffmpeg probes content anyway). */
    private static String guessExtension(String source) {
        String path = source;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot > slash && dot < path.length() - 1) {
            String ext = path.substring(dot + 1);
            if (ext.length() <= 5 && ext.chars().allMatch(Character::isLetterOrDigit)) {
                return ext.toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }
}
