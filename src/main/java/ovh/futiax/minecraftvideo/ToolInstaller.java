package ovh.futiax.minecraftvideo;

import java.io.File;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// Fournit les outils externes dont le plugin a besoin -- yt-dlp et le couple ffmpeg/ffprobe --
// sans que l'admin ait rien a installer.
//
// Ordre de resolution, par outil (premier trouve gagne, garde pour la session) :
//   1. le chemin configure (ffmpeg-path / yt-dlp-path)
//   2. une copie dans le PATH (le meilleur cas : c'est l'admin qui la maintient)
//   3. un binaire deja telecharge dans <dataFolder>/bin
//   4. un telechargement unique depuis les releases GitHub officielles
//
// Telecharger plutot qu'embarquer : ca garde le jar minuscule. yt-dlp fait ~30 Mo par
// plateforme et ses extracteurs cassent sans arret (une copie figee pourrirait) ; ffmpeg
// statique fait 110-180 Mo par plateforme, et ne rien distribuer evite au passage toute
// obligation de licence.
//
// start() fait tout ca au DEMARRAGE sur un thread daemon : onEnable ne bloque jamais, et les
// ~130 Mo d'ffmpeg ne tombent pas sur la tete du premier joueur qui tape /video play. Les
// getters ne BLOQUENT PAS non plus (ils sont appeles depuis le main thread par /photo et
// /stream) : tant que la resolution tourne, ffmpeg() rend le chemin configure, donc le
// comportement est exactement celui d'avant. isPreparing() sert a l'expliquer au joueur.
public final class ToolInstaller {

    private static final String YTDLP_RELEASES =
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/";

    // Tag "latest" de BtbN, mais assets de la branche de release 9.0 : ces noms-la sont
    // stables (contrairement aux builds "master-latest" qui suivent le tronc), donc l'URL ne
    // pourrit pas. On prend les builds LGPL et pas GPL : rien ici n'encode en x264/x265
    // (StillClip sort du png, LiveStream fait -c copy, AudioStream du PCM, SubtitleStream du
    // texte), donc le GPL n'apporterait que du poids et des contraintes de licence.
    private static final String FFMPEG_RELEASES =
            "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/";

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");
    private static final String EXE = WINDOWS ? ".exe" : "";

    private final Logger logger;
    private final Path binDir;
    private final boolean ytDlpEnabled;
    private final String ytDlpConfigured;
    private final String ffmpegConfigured;
    private final boolean ffmpegAutoDownload;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL) // GitHub -> CDN, redirection interne
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private volatile String ytDlp;      // null = indispo (desactive, ou tout a rate)
    private volatile String ffmpeg;     // null = rien de mieux que le chemin configure
    private volatile boolean preparing = true;

    public ToolInstaller(Logger logger, Path dataFolder, boolean ytDlpEnabled,
                         String ytDlpPath, String ffmpegPath, boolean ffmpegAutoDownload) {
        this.logger = logger;
        this.binDir = dataFolder.resolve("bin");
        this.ytDlpEnabled = ytDlpEnabled;
        this.ytDlpConfigured = ytDlpPath == null ? "" : ytDlpPath.trim();
        this.ffmpegConfigured = ffmpegPath == null || ffmpegPath.isBlank()
                ? "ffmpeg" : ffmpegPath.trim();
        this.ffmpegAutoDownload = ffmpegAutoDownload;
    }

    // Lance la resolution des deux outils en tache de fond. A appeler une fois, depuis onEnable.
    public void start() {
        Thread thread = new Thread(() -> {
            try {
                ffmpeg = resolveFfmpeg();
                if (ytDlpEnabled) {
                    ytDlp = resolveYtDlp();
                }
            } finally {
                preparing = false;
            }
        }, "MinecraftVideo-tools");
        thread.setDaemon(true);
        thread.start();
    }

    // true pendant que la resolution de demarrage tourne (typiquement le telechargement
    // d'ffmpeg) : de quoi dire au joueur de reessayer dans un instant plutot que de lui
    // sortir une erreur d'ffmpeg introuvable.
    public boolean isPreparing() {
        return preparing;
    }

    // Jamais null : le chemin configure sert de repli tant qu'on n'a rien de mieux, donc les
    // appelants gardent exactement le comportement d'avant cette classe.
    public String ffmpeg() {
        String resolved = ffmpeg;
        return resolved != null ? resolved : ffmpegConfigured;
    }

    public boolean isYtDlpEnabled() {
        return ytDlpEnabled;
    }

    // Commande yt-dlp utilisable, ou null si l'outil est desactive ou indisponible.
    public String ytDlp() {
        return ytDlp;
    }

    // Prefixe le PATH du process avec notre dossier bin. mcmm lance son PROPRE ffmpeg et le
    // cherche dans le PATH : c'est comme ca qu'il trouve celui qu'on a telecharge, sans un
    // seul changement dans le code C (qui vit dans un autre repo).
    public void exportTo(ProcessBuilder builder) {
        String dir = binDir.toAbsolutePath().toString();
        String current = builder.environment().get("PATH");
        builder.environment().put("PATH", current == null || current.isBlank()
                ? dir : dir + File.pathSeparator + current);
    }

    // ---------------------------------------------------------------- ffmpeg

    private String resolveFfmpeg() {
        // 1+2. chemin configure, ou simple nom resolu par le PATH ("ffmpeg" par defaut)
        if (hasFfmpegPair(ffmpegConfigured)) {
            return ffmpegConfigured;
        }
        // 3. deja telecharge lors d'un demarrage precedent
        Path local = binDir.resolve("ffmpeg" + EXE);
        if (hasFfmpegPair(local.toString())) {
            logger.info("ffmpeg: utilisation du binaire telecharge (" + local + ")");
            return local.toString();
        }
        if (!ffmpegAutoDownload) {
            logger.warning("ffmpeg introuvable et ffmpeg-autodownload est desactive :"
                    + " l'audio, les sous-titres, /photo et /stream ne marcheront pas."
                    + " Installe ffmpeg et ffprobe, ou renseigne ffmpeg-path.");
            return null;
        }
        // 4. on le telecharge une bonne fois
        if (downloadFfmpeg() && hasFfmpegPair(local.toString())) {
            logger.info("ffmpeg installe -> " + local);
            return local.toString();
        }
        logger.warning("Aucun ffmpeg utilisable (rien dans le PATH, et le telechargement a"
                + " echoue) : l'audio, les sous-titres, /photo et /stream ne marcheront pas."
                + " Installe ffmpeg et ffprobe, ou renseigne ffmpeg-path.");
        return null;
    }

    // ffmpeg ET ffprobe : le plugin a besoin des deux (ffprobe sonde les canaux audio et liste
    // les pistes de sous-titres), et AudioStream deduit le chemin d'ffprobe de celui d'ffmpeg.
    // Un ffmpeg seul ne sert donc a rien ici.
    private boolean hasFfmpegPair(String ffmpegPath) {
        return probe(ffmpegPath, "-version") && probe(siblingFfprobe(ffmpegPath), "-version");
    }

    // ffmpeg -> ffprobe, /opt/ffmpeg -> /opt/ffprobe, C:\t\ffmpeg.exe -> C:\t\ffprobe.exe.
    // Meme regle que AudioStream.siblingFfprobe, pour qu'on valide bien le binaire qu'il ira
    // chercher.
    private static String siblingFfprobe(String ffmpegPath) {
        File f = new File(ffmpegPath);
        String name = f.getName();
        if (!name.toLowerCase(Locale.ROOT).contains("ffmpeg")) {
            return "ffprobe";
        }
        String probeName = name.replaceFirst("(?i)ffmpeg", "ffprobe");
        String parent = f.getParent();
        return parent == null ? probeName : new File(parent, probeName).getPath();
    }

    private boolean downloadFfmpeg() {
        String asset = ffmpegAsset();
        if (asset == null) {
            logger.warning("Pas de build ffmpeg statique pour cette plateforme ("
                    + System.getProperty("os.name") + " / " + System.getProperty("os.arch")
                    + ") : installe ffmpeg et ffprobe a la main, ou renseigne ffmpeg-path.");
            return false;
        }
        Path archive = binDir.resolve(asset);
        Path work = binDir.resolve("ffmpeg-unpack");
        try {
            Files.createDirectories(binDir);
            logger.info("Telechargement de ffmpeg (" + asset + ", ~130 Mo, une seule fois)...");
            if (!fetch(FFMPEG_RELEASES + asset, archive)) {
                return false;
            }
            deleteTree(work);
            Files.createDirectories(work);
            if (!unpack(archive, work)) {
                return false;
            }
            // On ne fait AUCUNE confiance a l'arborescence interne de l'archive : on cherche
            // les binaires par leur nom, ou qu'ils soient. Ca evite aussi tout zip-slip, vu
            // qu'on ecrit sous un nom qu'on choisit nous-memes.
            return adopt(work, "ffmpeg") && adopt(work, "ffprobe");
        } catch (IOException e) {
            logger.warning("Installation de ffmpeg ratee : " + e.getMessage());
            return false;
        } finally {
            deleteQuietly(archive);
            deleteTree(work);
        }
    }

    // Asset de release BtbN pour la plateforme courante, null si pas couverte. BtbN ne publie
    // PAS de build macOS : un serveur Paper sur mac devra installer ffmpeg lui-meme.
    private static String ffmpegAsset() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (os.contains("win")) {
            if (x64) {
                return "ffmpeg-n9.0-latest-win64-lgpl-9.0.zip";
            }
            return arm ? "ffmpeg-n9.0-latest-winarm64-lgpl-9.0.zip" : null;
        }
        if (os.contains("linux")) {
            if (x64) {
                return "ffmpeg-n9.0-latest-linux64-lgpl-9.0.tar.xz";
            }
            return arm ? "ffmpeg-n9.0-latest-linuxarm64-lgpl-9.0.tar.xz" : null;
        }
        return null;
    }

    // Sort ffmpeg et ffprobe de l'archive dans work. Le .zip passe par java.util.zip ; le
    // .tar.xz par le tar du systeme, parce que la JVM ne sait pas lire xz et qu'embarquer de
    // quoi le faire (commons-compress + xz) pour ce seul usage ne vaut pas le poids.
    private boolean unpack(Path archive, Path work) throws IOException {
        if (archive.getFileName().toString().endsWith(".zip")) {
            return unzipBinaries(archive, work);
        }
        // Pas de shell ici (ProcessBuilder), donc les motifs arrivent intacts a tar au lieu
        // d'etre manges par le globbing. --wildcards est specifique a GNU tar : sans lui (BSD
        // tar) les motifs marchent deja, donc on retente sans en cas d'echec.
        List<String> selective = List.of("tar", "-xJf", archive.toString(),
                "-C", work.toString(), "--strip-components=2",
                "--wildcards", "*/bin/ffmpeg" + EXE, "*/bin/ffprobe" + EXE);
        if (run(selective)) {
            return true;
        }
        List<String> bsd = new ArrayList<>(selective);
        bsd.remove("--wildcards");
        if (run(bsd)) {
            return true;
        }
        // Dernier recours : tout extraire et laisser adopt() retrouver les binaires. Coute
        // ~400 Mo de disque le temps de l'operation, mais ne suppose rien de l'archive.
        logger.info("Extraction selective refusee par tar, extraction complete...");
        return run(List.of("tar", "-xJf", archive.toString(), "-C", work.toString()));
    }

    private boolean unzipBinaries(Path archive, Path work) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            boolean any = false;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                int slash = name.lastIndexOf('/');
                String base = slash < 0 ? name : name.substring(slash + 1);
                if (!base.equals("ffmpeg" + EXE) && !base.equals("ffprobe" + EXE)) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, work.resolve(base), StandardCopyOption.REPLACE_EXISTING);
                }
                any = true;
            }
            return any;
        }
    }

    // Deplace le binaire <name> trouve sous work vers binDir et le rend executable.
    private boolean adopt(Path work, String name) throws IOException {
        String wanted = name + EXE;
        Path found;
        try (Stream<Path> files = Files.walk(work)) {
            found = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(wanted))
                    .findFirst().orElse(null);
        }
        if (found == null) {
            logger.warning("L'archive ffmpeg ne contient pas " + wanted);
            return false;
        }
        Path target = binDir.resolve(wanted);
        Files.move(found, target, StandardCopyOption.REPLACE_EXISTING);
        target.toFile().setExecutable(true, false);
        return true;
    }

    // ---------------------------------------------------------------- yt-dlp

    private String resolveYtDlp() {
        if (!ytDlpConfigured.isBlank() && probe(ytDlpConfigured, "--version")) {
            return ytDlpConfigured;
        }
        if (probe("yt-dlp", "--version")) {
            return "yt-dlp";
        }
        Path local = binDir.resolve(WINDOWS ? "yt-dlp.exe" : "yt-dlp");
        if (Files.exists(local) && probe(local.toString(), "--version")) {
            updateYtDlpAsync(local.toString());
            return local.toString();
        }
        if (downloadYtDlp(local) && probe(local.toString(), "--version")) {
            logger.info("yt-dlp installe -> " + local);
            return local.toString();
        }
        logger.warning("yt-dlp indisponible (rien dans le PATH, et le telechargement a"
                + " echoue) : les URLs de type YouTube ne joueront pas."
                + " Installe yt-dlp, ou renseigne yt-dlp-path.");
        return null;
    }

    private boolean downloadYtDlp(Path target) {
        String asset = ytDlpAsset();
        if (asset == null) {
            logger.warning("Pas de build yt-dlp pour cette plateforme ("
                    + System.getProperty("os.name") + " / " + System.getProperty("os.arch")
                    + ") : installe yt-dlp a la main, ou renseigne yt-dlp-path.");
            return false;
        }
        try {
            Files.createDirectories(binDir);
            if (!fetch(YTDLP_RELEASES + asset, target)) {
                return false;
            }
            target.toFile().setExecutable(true, false);
            return true;
        } catch (IOException e) {
            logger.warning("Telechargement de yt-dlp rate : " + e.getMessage());
            return false;
        }
    }

    // Met a jour le binaire telecharge en tache de fond, au mieux : les extracteurs YouTube
    // cassent souvent, et un yt-dlp fige finirait par ne plus rien jouer.
    private void updateYtDlpAsync(String exe) {
        Thread thread = new Thread(() -> {
            try {
                Process p = new ProcessBuilder(exe, "-U").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                p.waitFor(90, TimeUnit.SECONDS);
            } catch (IOException ignored) {
                // tant pis, un binaire un peu vieux joue encore la plupart des videos
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "MinecraftVideo-ytdlp-update");
        thread.setDaemon(true);
        thread.start();
    }

    private static String ytDlpAsset() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "yt-dlp.exe";    // standalone, embarque Python, x64
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "yt-dlp_macos";  // universal
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

    // ---------------------------------------------------------------- plomberie

    // Telecharge url vers target via un .part, pour qu'un download coupe ne laisse jamais un
    // fichier tronque qui passerait pour installe au demarrage suivant.
    private boolean fetch(String url, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "MinecraftVideo-plugin")
                    .timeout(Duration.ofMinutes(30))
                    .GET().build();
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                logger.warning("Telechargement refuse (HTTP " + response.statusCode()
                        + ") : " + url);
                return false;
            }
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(tmp)) {
                in.transferTo(out);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteQuietly(tmp);
            return false;
        }
    }

    // lance <exe> <arg> et dit si ca passe
    private boolean probe(String exe, String arg) {
        Process process = null;
        try {
            process = new ProcessBuilder(exe, arg).redirectErrorStream(true).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            process.getInputStream().readAllBytes(); // courte, et ca vide le pipe
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;   // pas trouve / pas executable
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        }
    }

    private boolean run(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return false;
            }
            String out = new String(process.getInputStream().readAllBytes());
            if (process.exitValue() != 0) {
                logger.fine("[" + command.get(0) + "] " + out);
                return false;
            }
            return true;
        } catch (IOException e) {
            logger.warning(command.get(0) + " indisponible (" + e.getMessage() + ")");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        }
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    private void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException ignored) {
        }
    }
}
