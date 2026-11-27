package ovh.futiax.minecraftvideo;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

// Extrait UNE piste de sous-titres embarquee en texte SubRip avec un ffmpeg dedie, et repond
// a la question "quelle cue est affichee au temps video T ?". C'est le pendant overlay de
// AudioStream : son propre ffmpeg par segment, tue et relance au nouvel offset sur /video seek.
//
// ffmpeg est lance comme ca :
//   ffmpeg -v error [-ss <offset>] -copyts -i <source> -map 0:s:<n> -f srt -
// donc la n-ieme piste de sous-titres transcodee en SRT sur stdout. Deux choix assumes sur les
// timestamps (valides sur ffmpeg 8 avec un MKV de test a deux pistes) :
//
//  - -copyts garde les timestamps de cue D'ORIGINE. Sans lui, le rebasing ffmpeg des subs
//    texte n'est pas fiable (il ne decale pas de la valeur du seek et ne drope pas
//    proprement les cues anterieures), donc les temps ne colleraient plus a l'image apres un
//    seek. Avec -copyts les temps du SRT SONT du temps video absolu, et cueAtVideoMillis()
//    compare directement getPositionMillis() : aucun calcul d'offset.
//  - -ss avant -i n'est qu'un hint de vitesse (sauter dans un gros conteneur). Il peut
//    laisser fuir quelques cues d'avant l'offset : elles ne sont juste pas actives a la
//    position courante, donc elles ne s'affichent jamais.
//
// Un thread daemon de lecture pousse stdout dans le SrtParser et empile les cues finies dans
// une liste triee par temps ; cueAtVideoMillis() y fait une recherche binaire. Les cues
// deviennent donc dispo des que ffmpeg les sort (bien avant la lecture pour un fichier local),
// et la lecture des sous-titres ne bloque jamais la video.
//
// La source et l'index partent en argv (pas de shell) : aucune surface d'injection. Seuls les
// codecs texte sont extractibles, l'appelant filtre les pistes bitmap via textBased() avant
// de construire.
public final class SubtitleStream implements Closeable {

    private final Process process;
    private final Thread reader;
    private volatile boolean closed = false;

    // cues triees par temps de debut (millis video absolus), gardees par le moniteur de cues
    private final List<SrtParser.Cue> cues = new ArrayList<>();

    public SubtitleStream(Logger logger, String ffmpegPath, String source,
                          int subtitleIndex, long startOffsetMillis) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-v");
        cmd.add("error");
        if (startOffsetMillis > 0) {
            cmd.add("-ss"); // avant -i = fast input seek (juste un hint, cf plus haut)
            // Locale.ROOT sinon une locale fr sort "12,5" et ffmpeg se plante
            cmd.add(String.format(Locale.ROOT, "%.3f", startOffsetMillis / 1000.0));
        }
        cmd.add("-copyts");              // on garde les timestamps de cue d'origine (absolus)
        cmd.add("-i");
        cmd.add(source);
        cmd.add("-map");
        cmd.add("0:s:" + subtitleIndex); // la n-ieme piste de sous-titres
        cmd.add("-f");
        cmd.add("srt");
        cmd.add("-");                    // stdout
        this.process = new ProcessBuilder(cmd).start();
        this.reader = new Thread(() -> readLoop(logger), "MinecraftVideo-subs-reader");
        this.reader.setDaemon(true);
        this.reader.start();
        // stderr draine EN PARALLELE sur son propre thread daemon : avec -v error c'est
        // normalement minuscule, mais une source pleine de warnings de decodage pourrait
        // remplir le pipe stderr (~64 Ko) et bloquer les ecritures stdout de ffmpeg
        // (deadlock) si on ne lisait stderr qu'apres l'EOF de stdout.
        drainStderr(logger);
    }

    // thread de lecture : parse le SRT de stdout en cues jusqu'a EOF/close
    private void readLoop(Logger logger) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            SrtParser parser = new SrtParser();
            String line;
            while ((line = in.readLine()) != null) {
                SrtParser.Cue cue = parser.accept(line);
                if (cue != null) {
                    addCue(cue);
                }
            }
            SrtParser.Cue tail = parser.flush(); // dernier bloc s'il n'a pas eu sa ligne vide
            if (tail != null) {
                addCue(tail);
            }
        } catch (IOException ignored) {
            // process tue (seek/stop) ou stream ferme, rien a faire
        }
    }

    private void addCue(SrtParser.Cue cue) {
        synchronized (cues) {
            // ffmpeg sort le SRT dans l'ordre chronologique, donc l'ajout en fin est presque
            // toujours deja trie ; on se protege quand meme d'une queue desordonnee, ca coute rien.
            if (!cues.isEmpty() && cue.startMillis() < cues.get(cues.size() - 1).startMillis()) {
                int i = cues.size();
                while (i > 0 && cues.get(i - 1).startMillis() > cue.startMillis()) {
                    i--;
                }
                cues.add(i, cue);
            } else {
                cues.add(cue);
            }
        }
    }

    // La cue active au temps video absolu videoMillis (= getPositionMillis()), ou null si
    // rien n'est affiche (trou entre deux cues, ou pas encore extrait). Les temps de cue sont
    // absolus (-copyts) donc on compare la position directement.
    public String cueAtVideoMillis(long videoMillis) {
        synchronized (cues) {
            // recherche binaire de la derniere cue dont le start <= videoMillis
            int lo = 0;
            int hi = cues.size() - 1;
            int best = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (cues.get(mid).startMillis() <= videoMillis) {
                    best = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            // Les cues qui se chevauchent sont rares mais legales : on rescanne en arriere
            // depuis la derniere qui a commence, au cas ou une cue plus ancienne (parfois
            // beaucoup) et plus longue couvre encore l'instant alors que des courtes sont
            // triees apres elle. Pas de cap fixe : une longue cue banniere suivie de plein de
            // courtes qui la chevauchent ne doit pas etre dropee juste parce qu'elle sortait
            // d'une fenetre.
            for (int i = best; i >= 0; i--) {
                SrtParser.Cue c = cues.get(i);
                if (c.startMillis() <= videoMillis && videoMillis < c.endMillis()) {
                    return c.text();
                }
            }
            return null;
        }
    }

    private void drainStderr(Logger logger) {
        Thread thread = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    logger.info("[ffmpeg-subs] " + line);
                }
            } catch (IOException ignored) {
                // process tue ou stream ferme
            }
        }, "MinecraftVideo-subs-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        closed = true;
        process.destroyForcibly();
        reader.interrupt();
    }

    public boolean isClosed() {
        return closed;
    }

    // Liste les pistes de sous-titres embarquees avec ffprobe (cherche a cote du ffmpeg
    // configure, meme deduction que AudioStream.probeChannels). Liste VIDE = sonde, cette
    // source n'a pas de sous-titres ; null = c'est le SONDAGE qui a rate, l'appelant retentera
    // au lieu de cacher un echec passager pour toujours.
    //
    // BLOQUANT (jusqu'a ~20 s sur une URL lente), a appeler hors main thread. L'index relatif
    // aux subs de chaque piste (le n de -map 0:s:n) est sa position dans la liste rendue.
    public static List<SubtitleTrack> probeTracks(Logger logger, String ffmpegPath, String source) {
        String ffprobePath = siblingFfprobe(ffmpegPath);
        List<SubtitleTrack> tracks = new ArrayList<>();
        Process process = null;
        try {
            // Une ligne par piste : "codec|langue|titre" (les tags absents restent vides).
            // -select_streams s limite aux pistes de sous-titres, donc l'index de ligne EST
            // l'index du -map 0:s:n.
            process = new ProcessBuilder(ffprobePath, "-v", "error",
                    "-select_streams", "s",
                    "-show_entries", "stream=codec_name:stream_tags=language,title",
                    "-of", "csv=p=0",
                    source).redirectErrorStream(true).start();
            // Drain de stdout sur un thread separe DEMARRE AVANT le waitFor : un conteneur
            // corrompu (plein d'erreurs de parsing, des dizaines de pistes aux titres longs)
            // peut sortir plus que les ~64 Ko du buffer de pipe de l'OS, et ffprobe resterait
            // bloque en ecriture sur un pipe plein si on ne lisait qu'apres le waitFor : tout
            // l'appel partirait en timeout mort a 20 s. Lire en parallele garde le pipe vide
            // et laisse le process se terminer.
            final Process proc = process;
            final StringBuilder sb = new StringBuilder();
            Thread drain = new Thread(() -> {
                try {
                    byte[] bytes = proc.getInputStream().readAllBytes();
                    synchronized (sb) {
                        sb.append(new String(bytes, StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                    // process tue ou stream ferme, on garde ce qu'on a lu
                }
            }, "MinecraftVideo-ffprobe-reader");
            drain.setDaemon(true);
            drain.start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                logger.warning("ffprobe timed out listing subtitle tracks of '" + source + "'.");
                return null;
            }
            drain.join(1000); // le pipe est en EOF une fois le process sorti, on finit de lire
            String out;
            synchronized (sb) {
                out = sb.toString();
            }
            int i = 0;
            for (String line : out.split("\n")) {
                String row = line.strip();
                if (row.isEmpty()) {
                    continue;
                }
                // csv=p=0 => codec_name,language,title separes par des virgules (les champs de
                // fin manquants sont juste absents). Split avec une limite pour qu'une virgule
                // dans un titre garde la suite du titre.
                String[] parts = row.split(",", 3);
                String codec = parts.length > 0 ? parts[0].trim() : "";
                String lang = parts.length > 1 ? parts[1].trim() : "";
                String title = parts.length > 2 ? parts[2].trim() : "";
                tracks.add(new SubtitleTrack(i++, codec, lang, title));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return null; // sondage interrompu = echec, pas "pas de pistes"
        } catch (IOException e) {
            logger.warning("ffprobe (" + ffprobePath + ") could not list subtitles of '"
                    + source + "' (" + e.getMessage() + ").");
            return null;
        }
        return tracks;
    }

    // meme regle que dans AudioStream : ffmpeg -> ffprobe en gardant le dossier et le .exe
    // eventuel, retombee sur "ffprobe" du PATH si le nom ne contient pas "ffmpeg"
    private static String siblingFfprobe(String ffmpegPath) {
        java.io.File f = new java.io.File(ffmpegPath);
        String name = f.getName();
        if (!name.toLowerCase(Locale.ROOT).contains("ffmpeg")) {
            return "ffprobe";
        }
        String probeName = name.replaceFirst("(?i)ffmpeg", "ffprobe");
        String parent = f.getParent();
        return parent == null ? probeName : new java.io.File(parent, probeName).getPath();
    }
}
