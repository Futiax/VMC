package ovh.futiax.mcdata.mcmm;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

// Lance le convertisseur natif mcmm en mode stream :
//   mcmm --stream --palette <palette.json> [--data|--dither|--strict] <source> <map_w> <map_h> <fps>
// stdout = binaire big-endian : header 16 octets puis frames de map_w*map_h tuiles de 16384 octets.
//   Header : "MCMM" | u16 version | u16 map_w | u16 map_h | u16 fps | u32 reserve
//   Frame v2 : s64 PTS micros, puis les tuiles. EOF = fin de la video.
public final class McmmStream implements Closeable {

    public static final int TILE_BYTES = 128 * 128;
    private static final int HEADER_BYTES = 16;
    private static final int PTS_BYTES = 8;
    private static final int MAX_VERSION = 2;
    private static final int MAX_DIMENSION = 64;

    private final Process process;
    private final InputStream stdout;
    private int mapWidth;
    private int mapHeight;
    private int fps;
    private boolean framePts;
    private long ptsMicros;

    public McmmStream(Logger logger, String mcmmPath, String palettePath,
                      String source, int mapW, int mapH, int fps, String renderFlag) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(mcmmPath);
        cmd.add("--stream");
        cmd.add("--palette");
        cmd.add(palettePath);
        if (renderFlag != null && !renderFlag.isEmpty()) {
            cmd.add(renderFlag);
        }
        cmd.add(source);
        cmd.add(Integer.toString(mapW));
        cmd.add(Integer.toString(mapH));
        cmd.add(Integer.toString(fps));
        ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.environment().put("OMP_WAIT_POLICY", "passive");
        this.process = builder.start();
        this.stdout = process.getInputStream();
        drainStderr(logger, process);
    }

    public void readHeader() throws IOException {
        byte[] header = stdout.readNBytes(HEADER_BYTES);
        if (header.length < HEADER_BYTES) {
            throw new IOException("mcmm ended before writing the 16-byte header (got "
                    + header.length + " bytes); check mcmm-path and the source");
        }
        if (header[0] != 'M' || header[1] != 'C' || header[2] != 'M' || header[3] != 'M') {
            throw new IOException("bad stream magic from mcmm (expected \"MCMM\")");
        }
        int version = u16(header, 4);
        if (version < 1 || version > MAX_VERSION) {
            throw new IOException("unsupported mcmm stream version " + version);
        }
        this.framePts = version >= 2;
        this.mapWidth = u16(header, 6);
        this.mapHeight = u16(header, 8);
        this.fps = u16(header, 10);
        if (mapWidth <= 0 || mapHeight <= 0 || fps <= 0
                || mapWidth > MAX_DIMENSION || mapHeight > MAX_DIMENSION) {
            throw new IOException("invalid mcmm header: " + mapWidth + "x" + mapHeight + "@" + fps);
        }
    }

    public int getMapWidth() { return mapWidth; }
    public int getMapHeight() { return mapHeight; }
    public int getFps() { return fps; }
    public long getPtsMicros() { return ptsMicros; }

    // Lit une frame complete (map_w*map_h tuiles). null = EOF.
    public byte[][] nextFrame() throws IOException {
        if (framePts) {
            byte[] pts = stdout.readNBytes(PTS_BYTES);
            if (pts.length < PTS_BYTES) return null;
            ptsMicros = s64(pts);
        }
        int tiles = mapWidth * mapHeight;
        byte[][] frame = new byte[tiles][];
        for (int i = 0; i < tiles; i++) {
            byte[] tile = stdout.readNBytes(TILE_BYTES);
            if (tile.length < TILE_BYTES) return null;
            frame[i] = tile;
        }
        return frame;
    }

    @Override
    public void close() {
        process.destroyForcibly();
        try { stdout.close(); } catch (IOException ignored) {}
    }

    private static void drainStderr(Logger logger, Process process) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("[Parsed_") || line.startsWith(" ") || line.startsWith("frame=")) {
                        logger.fine("[mcmm] " + line);
                    } else {
                        logger.info("[mcmm] " + line);
                    }
                }
            } catch (IOException ignored) {}
        }, "mcdata-mcmm-stderr");
        t.setDaemon(true);
        t.start();
    }

    private static int u16(byte[] d, int o) {
        return ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF);
    }

    private static long s64(byte[] d) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (d[i] & 0xFF);
        return v;
    }
}
