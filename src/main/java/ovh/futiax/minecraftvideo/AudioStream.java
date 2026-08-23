package ovh.futiax.minecraftvideo;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

// Decode une source video/audio en PCM brut avec son propre ffmpeg et la ressort frame SVC
// par frame SVC, desentrelacee par canal. ffmpeg est lance comme ca :
//
//   ffmpeg -v error [-ss <offset>] -i <source> -vn [-af ...] -ac <channels> -ar 48000 -f s16le -
//
// donc du PCM 48 kHz signed 16-bit little-endian sur stdout, exactement ce qu'attend Simple
// Voice Chat, en frames de 960 samples par canal (20 ms). Avec N > 1 canaux ffmpeg sort de
// l'entrelace que nextFrame() re-separe en un short[] par canal. ffmpeg lit aussi bien les
// fichiers locaux que les URLs. Le -ss optionnel (avant -i = fast input seek) fait demarrer
// le decodage a un offset, c'est ce qu'utilise /video seek.
//
// Gestion du layout de canaux (-af) :
//  - sortie mono/stereo : downmix aresample explicite avec lfe_mix_level, comme ca le LFE
//    d'une source 5.1 est replie dedans au lieu d'etre jete en silence (defaut swresample) ;
//  - sortie surround (6ch) depuis une source qui a MOINS de 6 canaux : un simple -ac 6
//    laisserait FC/LFE/BL/BR en silence NUMERIQUE (swresample ne synthetise jamais de
//    canal, prouve avec le derivative stereo de surroundTest.mp4 sur archive.org), donc on
//    passe par le filtre surround qui construit un vrai 5.1 : centre depuis la correlation
//    stereo, arrieres depuis l'ambiance, LFE synthetise en passe-bas (vraie bass management).
//    L'appelant fournit le nombre de canaux sonde, voir probeChannels() ;
//  - sortie surround depuis une vraie source 5.1+ : mapping direct du lit, pas de filtre.
//
// La source part en argument de ProcessBuilder (argv, pas de shell) : aucune surface
// d'injection shell ici.
public final class AudioStream implements Closeable {

    public static final int FRAME_SAMPLES = 960;    // samples par frame SVC et PAR CANAL : 48 kHz * 20 ms

    private final int channels;
    private final int frameBytes; // channels * FRAME_SAMPLES * 2 (s16le)
    private final Process process;
    private final InputStream stdout;

    // sourceChannels = nombre de canaux du flux audio de la source (via probeChannels), ou -1
    // si inconnu. Sert UNIQUEMENT a decider de l'upmix surround.
    public AudioStream(Logger logger, String ffmpegPath, String source, int channels,
                       long startOffsetMillis, int sourceChannels) throws IOException {
        this.channels = channels;
        this.frameBytes = channels * FRAME_SAMPLES * 2;
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-v");
        cmd.add("error");
        if (startOffsetMillis > 0) {
            cmd.add("-ss"); // avant -i = fast input seek
            // Locale.ROOT sinon une locale fr sort "12,5" et ffmpeg se plante
            cmd.add(String.format(java.util.Locale.ROOT, "%.3f", startOffsetMillis / 1000.0));
        }
        cmd.add("-i");
        cmd.add(source);
        cmd.add("-vn");                            // pas de video
        if (channels <= 2) {
            // Etage resampler explicite pour le downmix mono/stereo : le lfe_mix_level par
            // defaut de swresample est 0, autrement dit le canal LFE d'une source 5.1/7.1
            // (la ou les films mettent les booms) est jete EN SILENCE. On le replie a ~-3 dB.
            // Le surround (-ac 6) garde le LFE comme canal a part. (out_chlayout : ffmpeg >= 6)
            cmd.add("-af");
            cmd.add("aresample=out_chlayout=" + (channels == 1 ? "mono" : "stereo")
                    + ":lfe_mix_level=0.707");
        } else if (channels == 6 && sourceChannels > 0 && sourceChannels < 6) {
            // Vrai upmix pour une source <6 canaux en mode surround : un simple -ac 6 laisse
            // FC/LFE/BL/BR en silence numerique. On normalise en stereo (en repliant le LFE
            // s'il y en a un) puis le filtre surround construit un vrai 5.1 : centre depuis
            // la correlation stereo, arrieres depuis l'ambiance, LFE synthetise en passe-bas.
            // Du coup le caisson en jeu sert aussi sur des fichiers stereo tout betes.
            cmd.add("-af");
            cmd.add("aresample=out_chlayout=stereo:lfe_mix_level=0.707,"
                    + "surround=chl_out=5.1");
        }
        cmd.add("-ac");
        cmd.add(Integer.toString(channels));       // 1 = mono, 2 = stereo, 6 = 5.1
        cmd.add("-ar");
        cmd.add("48000");                          // 48 kHz
        cmd.add("-f");
        cmd.add("s16le");                          // PCM signed 16-bit little-endian
        cmd.add("-");                              // stdout
        this.process = new ProcessBuilder(cmd).start();
        this.stdout = process.getInputStream();
        drainStderr(logger);
    }

    public int getChannels() {
        return channels;
    }

    // Nombre de canaux du premier flux audio de la source, sonde avec ffprobe (cherche a cote
    // du ffmpeg configure), ou -1 si le sondage foire (pas de ffprobe, timeout, pas de flux
    // audio...). -1 fait retomber le decode surround sur le mapping 5.1 direct : correct pour
    // les vrais films, et c'est le statu quo d'avant l'upmix pour le reste.
    //
    // Bloquant (jusqu'a ~20 s sur une URL lente) : appeler une fois par source et cacher le
    // resultat entre les segments de seek.
    public static int probeChannels(Logger logger, String ffmpegPath, String source) {
        String ffprobePath = siblingFfprobe(ffmpegPath);
        Process process = null;
        try {
            process = new ProcessBuilder(ffprobePath, "-v", "error",
                    "-select_streams", "a:0",
                    "-show_entries", "stream=channels",
                    "-of", "csv=p=0",
                    source).redirectErrorStream(true).start();
            // La sortie tient sur une ligne minuscule, aucun risque de backpressure sur le
            // pipe : on peut faire waitFor d'abord et lire apres.
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                logger.warning("ffprobe timed out probing audio channels of '"
                        + source + "'; assuming a 5.1 source.");
                return -1;
            }
            String out = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            int nb = Integer.parseInt(out.lines().findFirst().orElse("").trim());
            return nb > 0 ? nb : -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly(); // non-null : start() a reussi
            return -1;
        } catch (IOException | NumberFormatException e) {
            logger.warning("ffprobe (" + ffprobePath + ") could not probe '" + source
                    + "' (" + e.getMessage() + "); assuming a 5.1 source.");
            return -1;
        }
    }

    // Deduit le chemin de ffprobe depuis celui de ffmpeg : ffmpeg -> ffprobe,
    // /opt/ffmpeg -> /opt/ffprobe, C:\tools\ffmpeg.exe -> C:\tools\ffprobe.exe.
    // Si le nom de fichier ne contient pas "ffmpeg", on retombe sur "ffprobe" du PATH.
    private static String siblingFfprobe(String ffmpegPath) {
        java.io.File f = new java.io.File(ffmpegPath);
        String name = f.getName();
        if (!name.toLowerCase(java.util.Locale.ROOT).contains("ffmpeg")) {
            return "ffprobe";
        }
        String probeName = name.replaceFirst("(?i)ffmpeg", "ffprobe");
        String parent = f.getParent();
        return parent == null ? probeName : new java.io.File(parent, probeName).getPath();
    }

    // Lecture bloquante d'une frame de 20 ms (960 samples par canal), desentrelacee.
    // Renvoie short[channels][FRAME_SAMPLES], ou null en fin de stream (lecture courte = EOF).
    public short[][] nextFrame() throws IOException {
        byte[] raw = stdout.readNBytes(frameBytes);
        if (raw.length < frameBytes) {
            return null; // EOF ou queue tronquee -> fin de l'audio
        }
        short[][] frame = new short[channels][FRAME_SAMPLES];
        for (int s = 0; s < FRAME_SAMPLES; s++) {
            int base = s * channels * 2; // entrelace : tous les canaux du sample s a la suite
            for (int c = 0; c < channels; c++) {
                int off = base + c * 2;
                frame[c][s] = (short) ((raw[off] & 0xFF) | (raw[off + 1] << 8));     // little-endian
            }
        }
        return frame;
    }

    private void drainStderr(Logger logger) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("[ffmpeg-audio] " + line);
                }
            } catch (IOException ignored) {
                // process tue ou stream ferme
            }
        }, "MinecraftVideo-audio-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        process.destroyForcibly();
        try {
            stdout.close();
        } catch (IOException ignored) {
        }
    }
}
