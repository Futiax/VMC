package ovh.futiax.minecraftvideo;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Wraps the native mcmm converter spawned as:
 *
 *   mcmm --stream --palette <palette.json> <video> <map_w> <map_h> <fps>
 *
 * stdout is pure binary, big-endian; all mcmm logs go to stderr.
 *
 * 16-byte header:
 *   bytes 0-3  magic "MCMM"
 *   bytes 4-5  uint16 version = 1
 *   bytes 6-7  uint16 map_w
 *   bytes 8-9  uint16 map_h
 *   bytes 10-11 uint16 fps
 *   bytes 12-15 uint32 reserved = 0
 *
 * Then per decoded frame: map_w*map_h buffers of exactly 16384 bytes each
 * (128x128 Minecraft map color ids, byte index = y*128+x), in row-major tile
 * order (tile i = row*map_w + col, row 0 = TOP of the image). No per-frame
 * marker; EOF on stdout means end of video.
 */
public final class McmmStream implements Closeable {

    /** One 128x128 map tile of color ids. */
    public static final int TILE_BYTES = 128 * 128;

    private static final int HEADER_BYTES = 16;
    private static final int PROTOCOL_VERSION = 1;

    /**
     * Hard upper bound on the screen size the header may declare. mcmm echoes
     * back the (already command-clamped) requested size, so a larger value
     * means a misbehaving binary; rejecting it prevents an int overflow in
     * {@link #nextFrame()} and absurd fake-entity allocation in VirtualScreen.
     */
    private static final int MAX_DIMENSION = 64;

    private final Process process;
    private final InputStream stdout;
    private int mapWidth;
    private int mapHeight;
    private int fps;

    /**
     * Starts the mcmm subprocess but does NOT read the header yet. The caller
     * must publish this instance somewhere reachable by stop()/close() and then
     * call {@link #readHeader()} (which blocks). That way close() can kill the
     * process and unblock the header read even if mcmm never writes anything.
     */
    public McmmStream(Logger logger, String mcmmPath, String palettePath, String source,
                      int requestedWidth, int requestedHeight, int requestedFps) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                mcmmPath, "--stream", "--palette", palettePath, source,
                Integer.toString(requestedWidth),
                Integer.toString(requestedHeight),
                Integer.toString(requestedFps));
        this.process = builder.start();
        this.stdout = process.getInputStream();

        drainStderr(logger);
    }

    /**
     * Blocking read + validation of the 16-byte stream header. Call exactly
     * once, after the instance is reachable by close(). If close() is invoked
     * concurrently the process dies and this throws an IOException.
     */
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
        if (version != PROTOCOL_VERSION) {
            throw new IOException("unsupported mcmm stream version " + version
                    + " (expected " + PROTOCOL_VERSION + ")");
        }
        this.mapWidth = u16(header, 6);
        this.mapHeight = u16(header, 8);
        this.fps = u16(header, 10);
        // bytes 12-15: uint32 reserved, ignored.

        if (mapWidth <= 0 || mapHeight <= 0 || fps <= 0) {
            throw new IOException("invalid mcmm stream header: map_w=" + mapWidth
                    + " map_h=" + mapHeight + " fps=" + fps);
        }
        if (mapWidth > MAX_DIMENSION || mapHeight > MAX_DIMENSION) {
            throw new IOException("mcmm stream header dimensions out of range: "
                    + mapWidth + "x" + mapHeight + " (max " + MAX_DIMENSION + ")");
        }
    }

    /** Screen width in map tiles, as reported by the stream header. */
    public int getMapWidth() {
        return mapWidth;
    }

    /** Screen height in map tiles, as reported by the stream header. */
    public int getMapHeight() {
        return mapHeight;
    }

    /** Frames per second, as reported by the stream header. */
    public int getFps() {
        return fps;
    }

    /**
     * Blocking read of one full frame: map_w*map_h tiles of 16384 bytes each,
     * in row-major tile order (row 0 = top of the image).
     *
     * @return byte[tiles][16384], or {@code null} at end of stream
     *         (a short read is treated as EOF).
     */
    public byte[][] nextFrame() throws IOException {
        int tiles = mapWidth * mapHeight;
        byte[][] frame = new byte[tiles][];
        for (int i = 0; i < tiles; i++) {
            byte[] tile = stdout.readNBytes(TILE_BYTES);
            if (tile.length < TILE_BYTES) {
                return null; // EOF (or truncated stream) -> end of video
            }
            frame[i] = tile;
        }
        return frame;
    }

    /** Forwards mcmm's stderr to the plugin logger, line by line, on a daemon thread. */
    private void drainStderr(Logger logger) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.info("[mcmm] " + line);
                }
            } catch (IOException ignored) {
                // Process was killed or stream closed; nothing to do.
            }
        }, "MinecraftVideo-mcmm-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        process.destroyForcibly();
        try {
            stdout.close();
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF); // big-endian
    }
}
