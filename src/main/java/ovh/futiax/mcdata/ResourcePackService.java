package ovh.futiax.mcdata;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

// Pousse le resource pack MCDATA (shaders core et post effects) aux joueurs qui se connectent.
//
// Minecraft ne transporte PAS les packs : le serveur envoie une URL + un SHA-1, et c'est le
// CLIENT qui va chercher le zip en HTTP. Le pack est embarque dans le jar (pack.zip, ecrit par
// make-pack.sh).
//
// DEUX modes :
//   - selfhost (defaut) : un HttpServer du JDK sur resource-pack-port, et chaque joueur recoit
//     une URL batie sur l'hote qu'IL a utilise pour se connecter (NetworkClient#getVirtualHost).
//   - URL : resource-pack-url (et resource-pack-selfhost: false), un zip heberge ailleurs.
public final class ResourcePackService {

    private static final int STOP_DELAY_SECONDS = 1;

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final boolean enabled;
    private final boolean selfhost;
    private final int port;
    private final String urlOverride;
    private final boolean required;
    private final String prompt;

    private byte[] zip;
    private byte[] bundledSha1;
    private String sha1Hex;
    private String fixedUrl;
    private HttpServer server;
    private final AtomicBoolean hostWarned = new AtomicBoolean();

    private volatile byte[] sendSha1;
    private volatile UUID packId;
    private volatile boolean ready;

    public ResourcePackService(org.bukkit.plugin.java.JavaPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("resource-pack", true);
        this.selfhost = plugin.getConfig().getBoolean("resource-pack-selfhost", true);
        this.port = plugin.getConfig().getInt("resource-pack-port", 8098);
        String url = plugin.getConfig().getString("resource-pack-url", "");
        this.urlOverride = url == null ? "" : url.trim();
        this.required = plugin.getConfig().getBoolean("resource-pack-required", false);
        String text = plugin.getConfig().getString("resource-pack-prompt", "");
        this.prompt = text == null || text.isBlank() ? null : text;
    }

    public void enable() {
        if (!enabled || !load()) {
            return;
        }
        if (selfhost) {
            publish(bundledSha1);
            startServer();
            return;
        }
        if (urlOverride.isBlank()) {
            plugin.getLogger().warning("resource-pack-selfhost est coupe sans resource-pack-url :"
                    + " resource pack non pousse.");
            zip = null;
            return;
        }
        fixedUrl = urlOverride;
        plugin.getLogger().info("Resource pack: " + fixedUrl);
        resolveAsync(fixedUrl);
    }

    private void publish(byte[] hash) {
        sendSha1 = hash;
        packId = UUID.nameUUIDFromBytes(hash);
        sha1Hex = HexFormat.of().formatHex(hash);
        ready = true;
    }

    public void disable() {
        if (server != null) {
            server.stop(STOP_DELAY_SECONDS);
            server = null;
        }
        zip = null;
    }

    public void send(Player player) {
        if (zip == null || !ready) {
            return;
        }
        String url = fixedUrl != null ? fixedUrl : selfhostUrl(player);
        if (url == null) {
            if (hostWarned.compareAndSet(false, true)) {
                plugin.getLogger().warning("Impossible de deviner l'adresse a donner aux clients"
                        + " pour le resource pack : renseigne resource-pack-url.");
            }
            return;
        }
        player.addResourcePack(packId, url, sendSha1, prompt, required);
    }

    private boolean load() {
        try (InputStream in = plugin.getResource("pack.zip")) {
            if (in == null) {
                plugin.getLogger().info("Aucun resource pack embarque dans ce jar"
                        + " (pack/ vide au build) : rien a pousser aux joueurs.");
                return false;
            }
            zip = in.readAllBytes();
        } catch (IOException e) {
            plugin.getLogger().warning("Lecture du resource pack embarque ratee : " + e.getMessage());
            return false;
        }
        try {
            bundledSha1 = MessageDigest.getInstance("SHA-1").digest(zip);
        } catch (NoSuchAlgorithmException e) {
            plugin.getLogger().warning("SHA-1 indisponible sur cette JVM : pack non pousse.");
            zip = null;
            return false;
        }
        return true;
    }

    private void resolveAsync(String url) {
        Thread thread = new Thread(() -> {
            try {
                HttpClient http = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(20))
                        .build();
                HttpResponse<byte[]> response = http.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .header("User-Agent", "MCDATA-plugin")
                                .timeout(Duration.ofMinutes(2))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    plugin.getLogger().warning("Le resource pack n'est pas telechargeable (HTTP "
                            + response.statusCode() + ") : " + url);
                    fallback();
                    return;
                }
                byte[] remote = MessageDigest.getInstance("SHA-1").digest(response.body());
                publish(remote);
                catchUp();
            } catch (IOException e) {
                plugin.getLogger().info("Resolution du resource pack impossible ("
                        + e.getMessage() + ") ; on annonce le hash du pack embarque.");
                fallback();
            } catch (NoSuchAlgorithmException e) {
                // deja signale par load()
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "MCDATA-pack-resolve");
        thread.setDaemon(true);
        thread.start();
    }

    private void fallback() {
        publish(bundledSha1);
        catchUp();
    }

    private void catchUp() {
        try {
            plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getServer().getOnlinePlayers().forEach(this::send));
        } catch (IllegalStateException | IllegalPluginAccessException e) {
            // plugin en cours de desactivation
        }
    }

    private void startServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", this::serve);
            server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "MCDATA-pack-http");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            plugin.getLogger().info("Resource pack servi sur le port " + port
                    + " (sha1 " + sha1Hex + "). ATTENTION : ce port n'est pas celui du jeu, il"
                    + " doit etre joignable par les joueurs.");
        } catch (IOException e) {
            server = null;
            zip = null;
            plugin.getLogger().warning("Impossible d'ouvrir le port " + port
                    + " pour le resource pack (" + e.getMessage() + ") : le pack ne sera pas"
                    + " pousse. Change resource-pack-port, ou repasse en mode URL.");
        }
    }

    private String selfhostUrl(Player player) {
        InetSocketAddress virtualHost = player.getVirtualHost();
        String host = virtualHost != null ? virtualHost.getHostString() : null;
        if (host == null || host.isBlank()) {
            host = plugin.getServer().getIp();
        }
        if (host == null || host.isBlank()) {
            return null;
        }
        if (host.indexOf(':') >= 0) {
            host = "[" + host + "]";
        }
        return "http://" + host + ":" + port + "/" + sha1Hex + ".zip";
    }

    private void serve(HttpExchange exchange) throws IOException {
        byte[] body = zip;
        try (exchange) {
            if (body == null || !"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(body == null ? 404 : 405, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
