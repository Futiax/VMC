package ovh.futiax.minecraftvideo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.entity.Player;

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

// Pousse le resource pack VMC (les post effects qui decodent le rendu data, cf
// RenderMode.DataWall) aux joueurs qui se connectent, pour qu'un admin n'ait QUE le plugin a
// installer.
//
// Minecraft ne transporte PAS les packs : le serveur envoie une URL + un SHA-1, et c'est le
// CLIENT qui va chercher le zip en HTTP (c'est aussi, mot pour mot, ce que font les options
// resource-pack / resource-pack-sha1 / resource-pack-id de server.properties -- il n'existe
// aucune feature vanilla d'hebergement). Le pack est donc embarque dans le jar pour le hash,
// et il faut une URL que les clients joignent.
//
// DEUX modes :
//   - URL (defaut) : l'asset pack.zip de la release GitHub de CETTE version du plugin, attache
//     par la CI. Rien a ouvrir, rien a configurer, et ca marche pour tout le monde. L'URL est
//     derivee de la version a l'execution, et PAS mise en dur dans le config.yml : un admin qui
//     met a jour garde son config.yml (saveDefaultConfig ne le reecrit pas) et resterait pointe
//     sur l'ancienne release, dont le hash ne correspondrait plus au pack embarque.
//   - selfhost : un HttpServer du JDK sur resource-pack-port, et chaque joueur recoit une URL
//     batie sur l'hote qu'IL a utilise pour se connecter (NetworkClient#getVirtualHost). Pour
//     un serveur sans acces internet sortant cote joueurs. Demande que ce port -- qui n'est pas
//     celui du jeu -- soit joignable, ce qui est rarement le cas sans intervention.
//
// Le zip est garde en memoire (quelques centaines de Ko) et servi tel quel sur n'importe quel
// chemin : un seul contexte, aucune construction de chemin depuis la requete, donc pas de
// traversee de repertoire possible.
public final class ResourcePackService {

    // Les releases du plugin. L'asset pack.zip y est attache par .github/workflows/build.yml.
    private static final String RELEASE_BASE = "https://github.com/Futiax/VMC/releases/download/v";
    private static final String ASSET = "/pack.zip";

    private static final int STOP_DELAY_SECONDS = 1;

    private final MinecraftVideoPlugin plugin;
    private final boolean enabled;
    private final boolean selfhost;
    private final int port;
    private final String urlOverride;
    private final boolean required;
    private final String prompt;

    private byte[] zip;                 // contenu du pack, null tant qu'on n'est pas pret
    private byte[] sha1;                // hash binaire attendu par addResourcePack
    private String sha1Hex;             // meme hash, pour l'URL en selfhost et les logs
    private UUID packId;                // derive du hash : stable tant que le pack ne change pas
    private String fixedUrl;            // l'URL commune (null en selfhost : elle est par joueur)
    private HttpServer server;
    private final AtomicBoolean hostWarned = new AtomicBoolean();

    public ResourcePackService(MinecraftVideoPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("resource-pack", true);
        this.selfhost = plugin.getConfig().getBoolean("resource-pack-selfhost", false);
        this.port = plugin.getConfig().getInt("resource-pack-port", 8099);
        String url = plugin.getConfig().getString("resource-pack-url", "");
        this.urlOverride = url == null ? "" : url.trim();
        this.required = plugin.getConfig().getBoolean("resource-pack-required", false);
        String text = plugin.getConfig().getString("resource-pack-prompt", "");
        this.prompt = text == null || text.isBlank() ? null : text;
    }

    // Charge le pack embarque et prepare l'URL. Echec = pas de pack pousse, jamais une exception
    // qui ferait rater onEnable : la video marche sans (hors rendu data).
    public void enable() {
        if (!enabled || !load()) {
            return;
        }
        if (selfhost) {
            startServer();
            return;
        }
        fixedUrl = urlOverride.isBlank()
                ? RELEASE_BASE + plugin.getPluginMeta().getVersion() + ASSET
                : urlOverride;
        plugin.getLogger().info("Resource pack: " + fixedUrl + " (sha1 " + sha1Hex + ")");
        verifyAsync(fixedUrl);
    }

    public void disable() {
        if (server != null) {
            server.stop(STOP_DELAY_SECONDS);
            server = null;
        }
        zip = null;
    }

    // Pousse le pack au joueur. Appele au join. force=false par defaut : le client vanilla
    // accepte les packs serveur tout seul, donc tout le monde l'a quand meme, SANS le risque de
    // rendre le serveur injoignable si l'URL est fausse (avec required, un pack inaccessible
    // deconnecte tout le monde a la connexion).
    public void send(Player player) {
        if (zip == null) {
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
        player.addResourcePack(packId, url, sha1, prompt, required);
    }

    // Lit le pack embarque et calcule son hash. false = pas de pack dans ce jar (make-pack.sh
    // n'a rien trouve dans pack/), ce qui est un etat normal : le plugin tourne, seul le rendu
    // data en souffre.
    private boolean load() {
        try (InputStream in = plugin.getResource("pack.zip")) {
            if (in == null) {
                plugin.getLogger().info("Aucun resource pack embarque dans ce jar"
                        + " (pack/ vide au build) : rien a pousser aux joueurs.");
                return false;
            }
            zip = in.readAllBytes();
        } catch (IOException e) {
            plugin.getLogger().warning("Lecture du resource pack embarque ratee : "
                    + e.getMessage());
            return false;
        }
        try {
            sha1 = MessageDigest.getInstance("SHA-1").digest(zip);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 est obligatoire dans toute JVM ; si on arrive ici, autant le dire.
            plugin.getLogger().warning("SHA-1 indisponible sur cette JVM : pack non pousse.");
            zip = null;
            return false;
        }
        sha1Hex = HexFormat.of().formatHex(sha1);
        // Id derive du contenu : il ne change que si le pack change, donc un joueur qui rejoint
        // ne re-telecharge rien, et un pack mis a jour remplace proprement l'ancien.
        packId = UUID.nameUUIDFromBytes(sha1);
        return true;
    }

    // Verifie que le fichier a l'URL est BIT POUR BIT celui qu'on embarque. On annonce le hash
    // du pack embarque, donc le moindre octet d'ecart fait rejeter le pack par le client --
    // silencieusement cote serveur. Sans ce controle, un asset de release oublie ou un pack/
    // modifie sans rebump ne se verrait qu'en jeu, pack par pack. En tache de fond : onEnable
    // n'attend pas, et un echec reseau ne doit rien casser.
    private void verifyAsync(String url) {
        Thread thread = new Thread(() -> {
            byte[] expected = sha1;
            if (expected == null) {
                return;
            }
            try {
                HttpClient http = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)   // GitHub -> CDN d'assets
                        .connectTimeout(Duration.ofSeconds(20))
                        .build();
                HttpResponse<byte[]> response = http.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .header("User-Agent", "MinecraftVideo-plugin")
                                .timeout(Duration.ofMinutes(2))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    plugin.getLogger().warning("Le resource pack n'est pas telechargeable (HTTP "
                            + response.statusCode() + ") : " + url
                            + " -- les joueurs ne le recevront pas."
                            + " Verifie que l'asset pack.zip est bien attache a la release,"
                            + " ou passe en resource-pack-selfhost.");
                    return;
                }
                byte[] remote = MessageDigest.getInstance("SHA-1").digest(response.body());
                if (!Arrays.equals(remote, expected)) {
                    plugin.getLogger().warning("Le resource pack a cette URL ne correspond PAS a"
                            + " celui embarque dans ce jar : les clients le rejetteront."
                            + " attendu " + sha1Hex + ", trouve " + HexFormat.of().formatHex(remote)
                            + " (" + url + ")");
                    return;
                }
                plugin.getLogger().info("Resource pack verifie : l'URL sert bien le pack de ce jar.");
            } catch (IOException e) {
                // Pas joignable d'ici ne veut pas dire pas joignable par les joueurs (sortie
                // reseau filtree cote serveur), donc on informe sans alarmer.
                plugin.getLogger().info("Verification du resource pack impossible ("
                        + e.getMessage() + ") ; l'URL est quand meme poussee aux joueurs.");
            } catch (NoSuchAlgorithmException e) {
                // deja signale par load()
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "MinecraftVideo-pack-verify");
        thread.setDaemon(true);
        thread.start();
    }

    private void startServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", this::serve);
            // Un seul thread suffit : une requete de quelques centaines de Ko par connexion de
            // joueur, servie depuis la memoire.
            server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "MinecraftVideo-pack-http");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            plugin.getLogger().info("Resource pack servi sur le port " + port
                    + " (sha1 " + sha1Hex + "). ATTENTION : ce port n'est pas celui du jeu, il"
                    + " doit etre joignable par les joueurs, sinon leur telechargement finira en"
                    + " \"Connection refused\".");
        } catch (IOException e) {
            server = null;
            zip = null;
            plugin.getLogger().warning("Impossible d'ouvrir le port " + port
                    + " pour le resource pack (" + e.getMessage() + ") : le pack ne sera pas"
                    + " pousse. Change resource-pack-port, ou repasse en mode URL"
                    + " (resource-pack-selfhost: false).");
        }
    }

    // URL a donner A CE JOUEUR en selfhost. getVirtualHost() = l'hote qu'il a tape dans sa liste
    // de serveurs, donc une adresse dont on sait qu'elle resout chez lui ; server-ip en repli
    // quand l'admin l'a fixee. Le hash dans le nom du fichier evite qu'un cache intermediaire
    // serve un vieux pack.
    private String selfhostUrl(Player player) {
        InetSocketAddress virtualHost = player.getVirtualHost();
        String host = virtualHost != null ? virtualHost.getHostString() : null;
        if (host == null || host.isBlank()) {
            host = plugin.getServer().getIp();      // "" quand server-ip n'est pas fixee
        }
        if (host == null || host.isBlank()) {
            return null;
        }
        if (host.indexOf(':') >= 0) {
            host = "[" + host + "]";                // litteral IPv6
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
