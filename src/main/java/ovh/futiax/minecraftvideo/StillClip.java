package ovh.futiax.minecraftvideo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

// Transforme n'importe quelle source en un clip d'UNE seconde qui ne contient qu'une image.
//
// POURQUOI ce detour : mcmm ne sait pas lire une image fixe. Son filtre fps jette la frame
// unique d'un fichier image (duree 0,04 s), et ca ne depend pas du fps demande -- verifie a
// 1, 2, 5 et 10 fps sur png/jpg/webp : toujours "0 frames envoyees", en-tete seul. Un clip
// d'une seconde est une video ordinaire : rien en aval n'a besoin de savoir qu'on affiche
// une photo.
//
// La meme commande avale une VIDEO et en sort la premiere image (facon affiche) :
// trim=end_frame=1 garde la frame 0, loop la repete, -t 1 coupe a une seconde.
//
// En rendu strict, PAS de reduction ici : le scale de ffmpeg moyenne meme en flags=neighbor
// (des couleurs que la source n'a pas), et c'est justement ce que strict interdit. mcmm --strict
// reduit lui-meme, exactement, et refuse au-dela de 8192 px de cote.
//
// png dans le mkv, et pas libx264 : sans perte (la quantification sur la palette est deja
// destructrice, inutile d'y ajouter du DCT) et sans contrainte de dimensions paires -- une
// image en 860x933 fait echouer libx264 en yuv420p ("height not divisible by 2"). Pas ffv1
// non plus, qui etait le premier choix : il n'a pas de yuvj et repasse un JPEG en plage
// limitee (jusqu'a 3 niveaux d'ecart, 73 % des pixels touches), et son mode RGB prend 1 Go
// de RAM sur une image 4000x3000 contre 180 Mo en png -- sans le plafond de 2048 px du rendu
// strict, c'etait un ffmpeg de 3,7 Go pour un PNG de 450 Ko en 12000x12000.
final class StillClip {

    private static final int MAX_SIDE = 2048;   // au-dela, mcmm reduit de toute facon
    private static final int TIMEOUT_SECONDS = 60;

    private StillClip() {
    }

    static Path directory(Path dataFolder) {
        return dataFolder.resolve("photo");
    }

    // BLOQUANT (encodage ffmpeg), jamais sur le main thread. Le fichier produit appartient a
    // l'appelant, qui doit le supprimer.
    static Path build(String ffmpegPath, String source, Path dataFolder, boolean strict)
            throws IOException {
        Path dir = directory(dataFolder);
        Files.createDirectories(dir);
        Path out = Files.createTempFile(dir, "photo-", ".mkv");
        List<String> cmd = List.of(
                ffmpegPath, "-hide_banner", "-v", "error", "-y",
                "-i", source,
                "-vf", "trim=end_frame=1,loop=loop=-1:size=1:start=0,"
                        + (strict ? "" : "scale='min(iw," + MAX_SIDE + ")':'min(ih," + MAX_SIDE
                                + ")':force_original_aspect_ratio=decrease,")
                        + "fps=2",
                "-t", "1", "-an", "-c:v", "png",
                out.toString());
        Process process;
        try {
            // ffmpeg absent / ffmpeg-path faux : start() leve APRES la creation du temp
            process = new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            Files.deleteIfExists(out);
            throw e;
        }
        Deque<String> tail = drainStderr(process);
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                Files.deleteIfExists(out);
                throw new IOException("ffmpeg timed out preparing the image");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            Files.deleteIfExists(out);
            throw new IOException("interrupted while preparing the image");
        }
        if (process.exitValue() != 0 || Files.size(out) == 0) {
            Files.deleteIfExists(out);
            throw new IOException("ffmpeg could not read this image (" + String.join(" ", tail) + ")");
        }
        return out;
    }

    // stderr draine sur un thread a part : un pipe plein bloquerait ffmpeg avant la fin de
    // l'encodage. On garde les dernieres lignes pour avoir une cause dans le message d'erreur.
    private static Deque<String> drainStderr(Process process) {
        Deque<String> tail = new ArrayDeque<>();
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (tail) {
                        tail.addLast(line);
                        if (tail.size() > 4) {
                            tail.removeFirst();
                        }
                    }
                }
            } catch (IOException ignored) {
                // process tue ou stream ferme
            }
        }, "MinecraftVideo-photo-ffmpeg");
        thread.setDaemon(true);
        thread.start();
        return tail;
    }
}
