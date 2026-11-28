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

// Telecharge une fois les sources distantes (http/https) dans un fichier local, pour que tous
// les pipelines d'une lecture - mcmm video, ffmpeg audio, ffprobe, ffmpeg sous-titres - lisent
// le fichier LOCAL au lieu d'ouvrir chacun sa connexion vers l'origine. Ca supprime la charge
// de connexions concurrentes qui fait repondre 5XX aux hotes qui rate-limitent (archive.org
// typiquement), et un seek ou un toggle de sous-titres ne re-telecharge plus rien.
//
// COMPTAGE DE REFERENCES. reference() est appele une fois par occurrence vivante d'une URL
// (chaque entree en file, plus la lecture qui demarre), release() quand cette occurrence se
// termine. Le fichier est telecharge au premier acquire() et supprime seulement quand la
// DERNIERE reference est relachee : la meme URL mise N fois en file se telecharge une fois et
// survit jusqu'a la fin de sa derniere lecture.
//
// CAP DE TAILLE DUR. Une source distante plus grosse que cache-max-size-mb est REFUSEE (pas
// streamee) : acquire() leve.
//
// Les chemins locaux ne sont jamais caches (deja locaux) : toutes les methodes sont des no-op
// pour eux et acquire() rend le chemin tel quel.
//
// NOTE : c'est la seule partie du plugin qui ecrit sur le disque. Les fichiers vivent dans
// <dataFolder>/cache et sont vires au release, a l'enable (orphelins laisses par un crash) et
// au disable. Thread-safety : reference()/release() prennent le moniteur brievement ;
// acquire() telecharge EN DEHORS du moniteur (donc un gros download ne bloque jamais le main
// thread qui manipule la file) et ne le reprend que pour publier le fichier.
public final class MediaCache {

    private final Logger logger;
    private final Path cacheDir;
    private final boolean enabled;
    private final long maxBytes;
    private final YtDlpResolver resolver;   // resout les URLs de page (YouTube...), null = desactive

    // une URL cachee : le fichier local (null tant que pas telecharge) et son compteur
    private static final class Entry {
        Path file;
        int refs;
    }

    private final Map<String, Entry> entries = new HashMap<>();

    private static final int MAX_REDIRECTS = 5;     // suivis A LA MAIN, cf fetch()

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public MediaCache(Logger logger, Path dataFolder, boolean enabled, int maxSizeMb,
                      YtDlpResolver resolver) {
        this.logger = logger;
        this.cacheDir = dataFolder.resolve("cache");
        this.enabled = enabled;
        this.maxBytes = Math.max(0L, (long) maxSizeMb) * 1024L * 1024L;
        this.resolver = resolver;
        if (enabled) {
            purgeDir(); // orphelins laisses par un crash / un arret precedent
        }
    }

    public boolean isCacheable(String source) {
        if (!enabled || source == null) {
            return false;
        }
        String s = source.toLowerCase(Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://");
    }

    // enregistre une occurrence vivante de source. No-op pour un chemin local.
    public synchronized void reference(String source) {
        if (!isCacheable(source)) {
            return;
        }
        entries.computeIfAbsent(source, k -> new Entry()).refs++;
    }

    // Relache une occurrence ; supprime le fichier une fois la derniere reference partie.
    // No-op pour un chemin local ou une source inconnue.
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

    // Rend un chemin local a lire pour source, en le telechargeant d'abord si c'est une URL
    // distante pas encore cachee. Pour un chemin local, la source est rendue telle quelle.
    // BLOQUANT (le download), a appeler hors main thread.
    //
    // Une reference pour cette occurrence DOIT deja etre prise (cf reference()), sinon le
    // fichier peut se faire evincer en plein telechargement.
    //
    // abort est teste pendant le download ; s'il passe a true, le download est avorte (sert a
    // couper quand la session est stoppee). Leve une IOException si la source depasse le cap,
    // si le download echoue, ou s'il a ete avorte.
    public String acquire(String source, BooleanSupplier abort) throws IOException {
        if (!isCacheable(source)) {
            return source; // chemin local : on lit directement
        }
        synchronized (this) {
            Entry e = entries.get(source);
            if (e != null && e.file != null) {
                return e.file.toString(); // deja telecharge (mise en file deux fois par ex)
            }
        }
        // Download hors du lock pour que les operations de file sur le main thread ne bloquent jamais.
        Path file = download(source, abort);
        synchronized (this) {
            Entry e = entries.get(source);
            if (e == null) {
                // Toutes les references ont ete relachees pendant qu'on telechargeait (ne
                // devrait pas arriver, l'appelant en tient une). On ne laisse pas fuiter le fichier.
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
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

        // Une URL de page (YouTube etc.) est telechargee PAR yt-dlp : ces urls CDN signees ne
        // repondent qu'a la forme exacte de requete qu'il a negociee (un GET tout simple se
        // prend un 403, cf YtDlpResolver). Les URLs media directes gardent notre propre chemin
        // HTTP en dessous. Dans les deux cas la cle de cache reste la source D'ORIGINE.
        if (resolver != null && resolver.handles(source)) {
            assertPublicHost(source); // SSRF : l'URL de page elle-meme doit etre publique
            Path produced;
            try {
                produced = resolver.download(source, tmp, maxBytes, abort);
            } catch (YtDlpResolver.NoMediaException e) {
                deleteQuietly(tmp);
                // Page reconnue mais sans media pour yt-dlp (album imgur d'images...) : on prend
                // le media que la page annonce elle-meme dans ses balises og:/twitter:.
                String media = pageMediaUrl(source);
                if (media == null) {
                    throw new IOException("no video or image found on this page ("
                            + e.getMessage() + ")");
                }
                logger.info("yt-dlp found nothing on " + source + ", using its preview media "
                        + media);
                return publish(fetchToFile(media, tmp, abort), target, source);
            } catch (IOException e) {
                deleteQuietly(tmp); // yt-dlp a pu laisser un fichier partiel
                throw e;
            }
            return publish(produced, target, source);
        }
        return publish(fetchToFile(source, tmp, abort), target, source);
    }

    // GET direct (garde SSRF + cap de taille) de url vers tmp. tmp est supprime en cas d'echec.
    private Path fetchToFile(String url, Path tmp, BooleanSupplier abort) throws IOException {
        HttpResponse<InputStream> response = fetch(url);
        // refus immediat si la taille annoncee depasse le cap
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
        return tmp;
    }

    private static final int MAX_PAGE_BYTES = 2 * 1024 * 1024; // on ne lit que le debut du HTML
    private static final java.util.regex.Pattern META_TAG =
            java.util.regex.Pattern.compile("<meta\\b[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern META_ATTR = java.util.regex.Pattern.compile(
            "\\b(property|name|content)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    // ordre de preference : une video avant une image, la version https avant l'autre
    private static final java.util.List<String> MEDIA_KEYS = java.util.List.of(
            "og:video:secure_url", "og:video:url", "og:video",
            "og:image:secure_url", "og:image:url", "og:image",
            "twitter:player:stream", "twitter:image");

    // Lit la page (meme garde SSRF que tout le reste) et rend l'URL absolue du media annonce
    // dans ses balises <meta> og:/twitter:, ou null s'il n'y en a pas.
    private String pageMediaUrl(String page) throws IOException {
        String html;
        try (InputStream in = fetch(page).body()) {
            html = new String(in.readNBytes(MAX_PAGE_BYTES), java.nio.charset.StandardCharsets.UTF_8);
        }
        Map<String, String> found = new HashMap<>();
        java.util.regex.Matcher tag = META_TAG.matcher(html);
        while (tag.find()) {
            String key = null;
            String content = null;
            java.util.regex.Matcher attr = META_ATTR.matcher(tag.group());
            while (attr.find()) {
                String value = attr.group(3) != null ? attr.group(3) : attr.group(4);
                if (attr.group(1).equalsIgnoreCase("content")) {
                    content = value;
                } else {
                    key = value.toLowerCase(Locale.ROOT);
                }
            }
            if (key != null && content != null && !content.isBlank()) {
                found.putIfAbsent(key, content.trim());
            }
        }
        for (String k : MEDIA_KEYS) {
            String v = found.get(k);
            if (v == null) {
                continue;
            }
            v = v.replace("&amp;", "&");
            try {
                String abs = URI.create(page).resolve(v).toString();
                String l = abs.toLowerCase(Locale.ROOT);
                if (l.startsWith("http://") || l.startsWith("https://")) {
                    return abs;
                }
            } catch (IllegalArgumentException ignored) {
                // valeur mal formee : on passe a la suivante
            }
        }
        return null;
    }

    private Path publish(Path downloaded, Path target, String source) throws IOException {
        try {
            Files.move(downloaded, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(downloaded);
            throw new IOException("could not finalize cache file: " + e.getMessage());
        }
        logger.info("Cached " + source + " -> " + target
                + " (" + (Files.size(target) / (1024 * 1024)) + " MB)");
        return target;
    }

    // Fait le GET en suivant les redirects A LA MAIN, pour revalider l'hote de chaque saut
    // avec assertPublicHost (garde SSRF). Rend la reponse 200 avec son body ouvert :
    // c'est a l'appelant de le consommer et de le fermer.
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
                // On resout les redirects relatifs contre le saut courant, puis on revalide le
                // nouvel hote : une origine publique ne doit pas pouvoir nous renvoyer sur une
                // adresse interne.
                uri = assertPublicHost(uri.resolve(location).toString());
                continue;
            }
            response.body().close();
            throw new IOException("server returned HTTP " + code + " for " + source);
        }
        throw new IOException("too many redirects for " + source);
    }

    // Parse l'url, exige un scheme http/https, et la refuse si N'IMPORTE LAQUELLE des IPs
    // resolues n'est pas publique (loopback, link-local dont le 169.254 des metadonnees cloud,
    // prive/site-local, CGNAT, ULA IPv6, wildcard, multicast). C'est la garde SSRF : elle
    // empeche un appelant de faire taper le serveur sur des services internes ou sur un
    // endpoint de metadonnees cloud. (Le DNS-rebinding residuel entre ce check et la connexion
    // reelle est hors modele de menace ici.)
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

    // true pour toute adresse qui ne doit pas etre joignable via une URL fournie par un joueur
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
                return true; // fc00::/7 unique-local IPv6, pas couvert par isSiteLocal
            }
        }
        return false;
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    // vide tout le cache (appele a l'enable pour les orphelins, et au disable)
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
        }
    }

    // nom de fichier sans collision et safe pour le FS : sha-256(url) + extension devinee
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
            hash = Integer.toHexString(source.hashCode()); // SHA-256 est toujours la, ceinture-bretelles
        }
        String ext = guessExtension(source);
        return ext.isEmpty() ? hash : hash + "." + ext;
    }

    // extension devinee depuis le chemin de l'URL, au mieux (ffmpeg sonde le contenu de toute facon)
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
