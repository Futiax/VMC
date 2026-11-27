package ovh.futiax.doommc;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

// Un moteur Doom (native/doommc-engine, doomgeneric) pour une partie de 1 a 4 joueurs. Le main
// thread envoie une ligne d'entrees par tick (et les arrivees / departs) ; un thread lit les trames
// de reponse et garde la derniere de chaque place. Protocole : native/doommc_engine.c.
final class EngineProcess {

    static final int SLOTS = 4;
    // Bits de boutons de doommc_engine.c
    static final int FORWARD = 1, BACK = 2, LEFT = 4, RIGHT = 8, FIRE = 16, USE = 32, RUN = 64;

    private static final int[] EMPTY = new int[0];

    private final Process process;
    private final OutputStream in;
    private final AtomicReferenceArray<int[]> latest = new AtomicReferenceArray<>(SLOTS);

    EngineProcess(File binary, File wad, File workDir, String name, List<String> extraArgs) throws IOException {
        workDir.mkdirs();
        List<String> command = new ArrayList<>(List.of(binary.getAbsolutePath(), "-iwad", wad.getAbsolutePath(),
                "-warp", "1", "1", "-skill", "3"));
        command.addAll(extraArgs);
        process = new ProcessBuilder(command)
                .directory(workDir)
                .redirectError(ProcessBuilder.Redirect.to(new File(workDir, "engine.log")))
                .start();
        in = process.getOutputStream();
        for (int i = 0; i < SLOTS; i++) {
            latest.set(i, EMPTY);
        }
        Thread reader = new Thread(this::readFrames, "DOOMMC-engine-" + name);
        reader.setDaemon(true);
        reader.start();
    }

    // uint16 nombre de trames, puis par joueur : uint16 place, uint16 n, n valeurs (petit-boutiste)
    private void readFrames() {
        try (DataInputStream out = new DataInputStream(process.getInputStream())) {
            while (true) {
                int frames = readU16(out);
                for (int f = 0; f < frames; f++) {
                    int slot = readU16(out), n = readU16(out);
                    int[] values = new int[n];
                    for (int i = 0; i < n; i++) {
                        values[i] = readU16(out);
                    }
                    if (slot < SLOTS) {
                        latest.set(slot, values);
                    }
                }
            }
        } catch (IOException ignored) {
            // moteur arrete, ou flux ferme par close()
        }
    }

    private static int readU16(DataInputStream s) throws IOException {
        int lo = s.read(), hi = s.read();
        if ((lo | hi) < 0) {
            throw new EOFException();
        }
        return lo | (hi << 8);
    }

    boolean isAlive() {
        return process.isAlive();
    }

    private void write(String line) {
        try {
            in.write(line.getBytes(StandardCharsets.US_ASCII));
            in.flush();
        } catch (IOException e) {
            process.destroy();
        }
    }

    // Une ligne par tick serveur ; angles en BAM (0 = est, sens trigonometrique). Places libres : 0.
    void send(int[] buttons, int[] weapons, long[] angles) {
        StringBuilder line = new StringBuilder("T");
        for (int i = 0; i < SLOTS; i++) {
            line.append(' ').append(buttons[i]).append(' ').append(weapons[i]).append(' ').append(angles[i] & 0xFFFFFFFFL);
        }
        write(line.append('\n').toString());
    }

    void join(int slot) {
        latest.set(slot, EMPTY);
        write("J " + slot + "\n");
    }

    void leave(int slot) {
        write("L " + slot + "\n");
        latest.set(slot, EMPTY);
    }

    // Derniere trame de la place : la charge utile de la balise a partir de la valeur 6 (vide avant la premiere).
    int[] latest(int slot) {
        return latest.get(slot);
    }

    void close() {
        process.destroy();
    }

    // Regard Minecraft -> angle Doom : Doom (x, y) = Minecraft (x, -z), donc angle = -90 - yaw.
    static long doomAngle(float yawDegrees) {
        double turns = (((-90.0 - yawDegrees) % 360.0) + 360.0) % 360.0 / 360.0;
        return Math.round(turns * 4294967296.0) & 0xFFFFFFFFL;
    }
}
