package ovh.futiax.minecraftvideo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// Telecharge les videos (YouTube, etc.) avec yt-dlp (voir YtDlpInstaller). Les URLs media
// directes et les chemins locaux NE passent PAS par ici : MediaCache les recupere lui-meme
// avec son client HTTP protege contre le SSRF.
//
// On demande un format basse-reso, video+audio, pile ce qu'il faut pour des ecrans map 128px.
public final class YtDlpResolver {

    // Video+audio separes en 480p FUSIONNES par ffmpeg, sinon un stream combine (pour les
    // sites qui ne servent que ca), sinon ce que yt-dlp prefere.
    //
    // NE PAS redemander le format combine legacy 18 en premier : YouTube ne le sert plus que
    // via des clients dont les urls signees repondent 403 (constate en jeu en 0.5.4, yt-dlp
    // lui-meme se prend le 403). Les streams DASH que yt-dlp choisit par defaut sont la voie
    // maintenue.
    private static final String FORMAT = "bv*[height<=?480]+ba/b[height<=?480]/bv*+ba/b";
    // Direct : une seule entree pour ffmpeg, donc un flux deja combine, jamais un merge.
    private static final String LIVE_FORMAT = "b[height<=?480]/b";

    // extensions traitees comme media direct (c'est MediaCache qui les gere)
    private static final Set<String> DIRECT_EXTENSIONS = Set.of(
            "mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "flv", "ogv", "ogg",
            "wmv", "mpg", "mpeg", "m3u8", "mpd", "3gp");

    private static final int ERROR_TAIL_LINES = 6;      // queue de sortie gardee pour expliquer un echec
    private static final Pattern FRAGMENT = Pattern.compile("\\.f\\d+\\.[^.]+$");    // fichier mono-format
                                                                                    // avant fusion, ex .f299.mp4

    private final YtDlpInstaller installer;
    private final Logger logger;
    private final String ffmpegPath;

    public YtDlpResolver(YtDlpInstaller installer, Logger logger, String ffmpegPath) {
        this.installer = installer;
        this.logger = logger;
        this.ffmpegPath = ffmpegPath;
    }

    // Est-ce que cette source est une URL de PAGE que yt-dlp doit telecharger (http(s) qui
    // n'est pas un fichier media direct) ? Verifs legeres seulement, on ne resout PAS
    // l'executable, donc appelable depuis n'importe quel thread.
    public boolean handles(String source) {
        return installer.isEnabled() && source != null
                && isHttp(source) && !hasDirectExtension(source);
    }

    // Telecharge source vers target (ou vers un sibling si yt-dlp tient a son extension) et
    // renvoie le fichier reellement ecrit. BLOQUANT, hors main thread.
    //   maxBytes = limite de taille, 0 = illimite
    //   abort    = teste pendant que yt-dlp tourne, true tue le telechargement
    // Leve une IOException si yt-dlp est indispo, echoue, ou si l'abort tire.
    public Path download(String source, Path target, long maxBytes, BooleanSupplier abort)
            throws IOException {
        String exe = installer.getExecutable();
        if (exe == null) {
            throw new IOException("yt-dlp indisponible (voir log serveur)");
        }
        List<String> command = new ArrayList<>(List.of(exe,
                "-f", FORMAT,
                "--merge-output-format", "mp4",
                "--no-playlist",
                "--no-warnings",
                "--no-progress",
                "--no-part", // on gere notre nommage .part nous-memes
                // Le ".%(ext)s" n'est PAS decoratif : avec un nom litteral, yt-dlp prend notre
                // ".part" final pour l'extension et la reecrit, donc le fichier atterrit la ou
                // on ne regarde jamais (constate en 0.5.4 : "reported success but wrote no
                // file"). Avec un placeholder d'extension explicite, le stem est laisse tranquille.
                "-o", target + ".%(ext)s"));
        // Seulement si c'est un vrai chemin : un "ffmpeg" nu est un nom de PATH, et
        // --ffmpeg-location attend un fichier ou un dossier.
        if (ffmpegPath != null && ffmpegPath.indexOf('/') >= 0) {
            command.add("--ffmpeg-location");
            command.add(ffmpegPath);
        }
        if (maxBytes > 0) {
            command.add("--max-filesize");
            command.add(Long.toString(maxBytes));
        }
        command.add(source);

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        Deque<String> tail = new ArrayDeque<>();
        drainOutput(process.getInputStream(), tail);
        try {
            return await(process, target, tail, abort);
        } catch (IOException e) {
            cleanup(target); // une fusion qui plante laisse des mono-format derriere
            throw e;
        }
    }

    // Resout une page de DIRECT en une URL de manifeste que ffmpeg ouvrira lui-meme.
    // BLOQUANT, hors main thread.
    //
    // C'est bien le "-g" abandonne en 0.5.4 pour les VOD, et ce n'est PAS une rechute : le 403
    // de l'epoque venait des urls googlevideo signees, qui exigent une requete ranged que notre
    // HttpClient n'envoyait pas. Ici personne ne telecharge le fichier -- ffmpeg suit un
    // manifeste HLS, requete par requete, exactement comme le ferait un lecteur. Un direct n'a
    // de toute facon pas de fichier a telecharger.
    public String resolveLive(String source) throws IOException {
        String exe = installer.getExecutable();
        if (exe == null) {
            throw new IOException("yt-dlp indisponible (voir log serveur)");
        }
        Process process = new ProcessBuilder(exe, "-g", "-f", LIVE_FORMAT,
                "--no-playlist", "--no-warnings", source).start();
        Deque<String> tail = new ArrayDeque<>();
        drainOutput(process.getErrorStream(), tail);    // stderr a part : stdout porte l'URL
        String url;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            url = reader.readLine();                    // -g imprime une URL par ligne
        }
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("yt-dlp n'a pas repondu en 30 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("resolution interrompue");
        }
        if (process.exitValue() != 0 || url == null || url.isBlank()) {
            throw new IOException("yt-dlp n'a pas pu resoudre ce direct: " + snapshot(tail));
        }
        return url;
    }

    private static Path await(Process process, Path target, Deque<String> tail,
            BooleanSupplier abort) throws IOException {
        try {
            // On poll au lieu d'un waitFor simple pour que /video stop puisse tuer le download.
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (abort.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    process.destroyForcibly();
                    throw new IOException("download aborted");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("download interrupted");
        }
        if (process.exitValue() != 0) {
            throw new IOException("yt-dlp failed (exit " + process.exitValue() + "): "
                    + snapshot(tail));
        }
        Path produced = locate(target);
        if (produced == null) {
            throw new IOException("yt-dlp reported success but wrote no file: "
                    + snapshot(tail));
        }
        return produced;
    }

    // le thread de drain peut encore ecrire dedans, donc on lit sous son verrou
    private static String snapshot(Deque<String> tail) {
        synchronized (tail) {
            return String.join(" | ", tail);
        }
    }

    // yt-dlp peut honorer notre -o litteral ou coller sa propre extension : on accepte les
    // deux plutot que de deviner. Les fichiers mono-format (<name>.f299.mp4) sont ignores,
    // seul le resultat fusionne compte.
    private static Path locate(Path target) throws IOException {
        if (Files.exists(target)) {
            return target;
        }
        try (Stream<Path> files = siblings(target)) {
            return files.filter(p -> !FRAGMENT.matcher(p.getFileName().toString()).find())
                    .findFirst().orElse(null);
        }
    }

    // vire le fichier de sortie et les restes mono-format d'un run rate
    private static void cleanup(Path target) {
        try (Stream<Path> files = siblings(target)) {
            files.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    // tous les fichiers que yt-dlp a pu ecrire pour ce nom de -o
    private static Stream<Path> siblings(Path target) throws IOException {
        Path dir = target.getParent();
        if (dir == null) {
            return Stream.empty();
        }
        String name = target.getFileName().toString();
        return Files.list(dir).filter(p -> {
            String n = p.getFileName().toString();
            return n.equals(name) || n.startsWith(name + ".");
        });
    }

    private static boolean isHttp(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://");
    }

    // true si le chemin de l'URL finit par une extension media directe connue
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

    // consomme la sortie de yt-dlp sur un thread daemon (un pipe plein ferait deadlock)
    private void drainOutput(InputStream stream, Deque<String> tail) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.fine("[yt-dlp] " + line);
                    synchronized (tail) {
                        tail.addLast(line);
                        if (tail.size() > ERROR_TAIL_LINES) {
                            tail.removeFirst();
                        }
                    }
                }
            } catch (IOException ignored) {
                // process fini / stream ferme
            }
        }, "MinecraftVideo-ytdlp-output");
        thread.setDaemon(true);
        thread.start();
    }
}
