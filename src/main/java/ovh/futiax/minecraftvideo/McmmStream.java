package ovh.futiax.minecraftvideo;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

// Lance le convertisseur natif mcmm :
//   mcmm --stream --palette <palette.json> [--seek <secondes>] <video> <map_w> <map_h> <fps>
// stdout = binaire pur big-endian, tous les logs de mcmm partent sur stderr.
// --seek fait demarrer le decodage a l'offset (mcmm le passe en -ss avant -i a son ffmpeg
// interne), c'est ce qu'utilise /video seek.
//
// Header 16 octets : "MCMM" | u16 version | u16 map_w | u16 map_h | u16 fps | u32 reserve
// puis par frame : map_w*map_h tuiles de 16384 octets pile (128x128 ids de couleur map,
// index = y*128+x), rangees ligne par ligne, tuile i = ligne*map_w + colonne, ligne 0 = HAUT
// de l'image. EOF sur stdout = fin de la video.
//
// version 1 : aucun marqueur entre les frames.
// version 2 : chaque frame est prefixee de 8 octets, un s64 big-endian = le PTS de la frame
// dans la SOURCE, en microsecondes (absolu : inchange par --seek). C'est la seule horloge
// partagee entre deux connexions independantes a un meme flux live. On accepte les deux
// versions pour qu'un vieux binaire mcmm deja deploye continue de marcher.
public final class McmmStream implements Closeable {

    public static final int TILE_BYTES = 128 * 128;             // une tuile = une map 128x128

    public static final long NO_PTS = Long.MIN_VALUE;           // flux v1 : pas d'horloge source

    private static final int HEADER_BYTES = 16;
    private static final int MAX_VERSION = 2;
    private static final int PTS_BYTES = 8;
    // mcmm renvoie la taille deja clampee par la commande, donc plus grand = binaire foireux.
    // On refuse : ca evite l'overflow int dans nextFrame() et l'alloc d'entites fake absurde.
    private static final int MAX_DIMENSION = 64;

    private final Process process;
    private final InputStream stdout;
    private int mapWidth;
    private int mapHeight;
    private int fps;
    private boolean framePts;                                   // vrai a partir de la version 2
    private long ptsMicros = NO_PTS;                            // PTS de la derniere frame lue

    // Demarre le process mais ne lit PAS le header : l'appelant doit d'abord publier
    // l'instance quelque part ou stop()/close() peut l'attraper, puis appeler readHeader()
    // (bloquant). Sinon si mcmm n'ecrit jamais rien on reste bloque sans pouvoir le tuer.
    public McmmStream(Logger logger, String mcmmPath, String palettePath, String source,
                      int requestedWidth, int requestedHeight, int requestedFps,
                      long seekOffsetMillis, boolean dither) throws IOException {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(mcmmPath);
        cmd.add("--stream");
        cmd.add("--palette");
        cmd.add(palettePath);
        if (seekOffsetMillis > 0) {
            cmd.add("--seek");
            // Locale.ROOT sinon une locale fr formate "12,5" et le atof de mcmm se plante
            cmd.add(String.format(java.util.Locale.ROOT, "%.3f", seekOffsetMillis / 1000.0));
        }
        if (dither) {
            cmd.add("--dither");
        }
        cmd.add(source);
        cmd.add(Integer.toString(requestedWidth));
        cmd.add(Integer.toString(requestedHeight));
        cmd.add(Integer.toString(requestedFps));
        this.process = new ProcessBuilder(cmd).start();
        this.stdout = process.getInputStream();

        drainStderr(logger);
    }

    // Lecture bloquante + validation du header. A appeler une seule fois, une fois que
    // close() peut nous joindre : un close() concurrent tue le process et on sort en IOException.
    public void readHeader() throws IOException {
        byte[] header = stdout.readNBytes(HEADER_BYTES);
        if (header.length < HEADER_BYTES) {
            throw new IOException("mcmm ended before writing the 16-byte stream header"
                    + " (got " + header.length + " bytes); check mcmm-path and the video source");
        }
        if (header[0] != 'M' || header[1] != 'C' || header[2] != 'M' || header[3] != 'M') {
            throw new IOException("bad stream magic from mcmm (expected \"MCMM\")");
        }
        int version = u16(header, 4);
        if (version < 1 || version > MAX_VERSION) {
            throw new IOException("unsupported mcmm stream version " + version
                    + " (this plugin reads 1 to " + MAX_VERSION + ")");
        }
        this.framePts = version >= 2;
        this.mapWidth = u16(header, 6);
        this.mapHeight = u16(header, 8);
        this.fps = u16(header, 10);
        // octets 12-15 : u32 reserve, on s'en fout

        if (mapWidth <= 0 || mapHeight <= 0 || fps <= 0) {
            throw new IOException("invalid mcmm stream header: map_w=" + mapWidth
                    + " map_h=" + mapHeight + " fps=" + fps);
        }
        if (mapWidth > MAX_DIMENSION || mapHeight > MAX_DIMENSION) {
            throw new IOException("mcmm stream header dimensions out of range: "
                    + mapWidth + "x" + mapHeight + " (max " + MAX_DIMENSION + ")");
        }
    }

    // tout ce qui suit vient du header, pas de la commande
    public int getMapWidth() {
        return mapWidth;
    }

    public int getMapHeight() {
        return mapHeight;
    }

    public int getFps() {
        return fps;
    }

    // NO_PTS tant qu'aucune frame n'a ete lue, ou si mcmm parle la version 1.
    public long getPtsMicros() {
        return ptsMicros;
    }

    // Lecture bloquante d'une frame complete : map_w*map_h tuiles de 16384 octets,
    // ligne 0 = haut de l'image. Renvoie null en fin de stream (lecture courte = EOF).
    public byte[][] nextFrame() throws IOException {
        if (framePts) {
            byte[] pts = stdout.readNBytes(PTS_BYTES);
            if (pts.length < PTS_BYTES) {
                return null; // EOF pile avant une frame
            }
            ptsMicros = s64(pts);
        }
        int tiles = mapWidth * mapHeight;
        byte[][] frame = new byte[tiles][];
        for (int i = 0; i < tiles; i++) {
            byte[] tile = stdout.readNBytes(TILE_BYTES);
            if (tile.length < TILE_BYTES) {
                return null; // EOF (ou stream tronque) -> fin de video
            }
            frame[i] = tile;
        }
        return frame;
    }

    // stderr de mcmm -> logger du plugin, ligne par ligne, sur un thread daemon
    private void drainStderr(Logger logger) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isFfmpegNoise(line)) {
                        logger.fine("[mcmm] " + line);
                    } else {
                        logger.info("[mcmm] " + line);
                    }
                }
            } catch (IOException ignored) {
                // process tue ou stream ferme, rien a faire
            }
        }, "MinecraftVideo-mcmm-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    // Depuis le format v2, l'ffmpeg interne de mcmm tourne en -v info : il le faut pour capter
    // les PTS de showinfo, mais du coup il deverse tout le reste sur notre stderr.
    //
    // De loin le pire : "[Parsed_showinfo_3 @ 0x...] color_range:..." = UNE LIGNE PAR FRAME,
    // soit 10/s a 10 fps. Mesure sur un vrai log : 123 lignes pour une tranche, dont 106 de
    // ce seul motif. Toute ligne "[Parsed_<filtre>_N @ ...]" est de la tuyauterie interne de
    // mcmm, jamais un diagnostic utile ici.
    //
    // Volontairement une liste de prefixes plutot qu'un filtre malin : ce qu'on ne reconnait
    // pas reste en info. Une vraie panne ("moov atom not found", "Invalid data...") ne
    // commence par aucun de ces prefixes et remonte donc normalement.
    private static boolean isFfmpegNoise(String line) {
        if (line.startsWith(" ") || line.startsWith("[Parsed_")
                || line.startsWith("[out#") || line.startsWith("[in#")) {
            return true;    // chatter du filtergraph / comptabilite de muxing, et tout
                            // le detail indente (Duration, Metadata, service_name...)
        }
        return line.startsWith("Input #") || line.startsWith("Output #")
                || line.startsWith("Stream #") || line.startsWith("Stream mapping:")
                || line.startsWith("frame=") || line.startsWith("Press [q]")
                || line.startsWith("video:") || line.startsWith("size=");
    }

    @Override
    public void close() {
        process.destroyForcibly();
        try {
            stdout.close();
        } catch (IOException ignored) {
        }
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);    // big-endian
    }

    private static long s64(byte[] data) {
        long value = 0;
        for (int i = 0; i < 8; i++) {                                       // big-endian signe
            value = (value << 8) | (data[i] & 0xFF);
        }
        return value;
    }
}
