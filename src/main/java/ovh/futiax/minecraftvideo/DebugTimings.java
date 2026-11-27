package ovh.futiax.minecraftvideo;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Les temps de chaque frame, de ffmpeg jusqu'a l'envoi : la derniere minute en memoire pour
// /video status, et une ligne de CSV par frame pendant /vmc debug on. row() et le fichier = thread
// de lecture seulement ; describe() = main thread, d'ou le verrou sur les fenetres. Temps du CSV en
// microsecondes. Les colonnes *_cpu_ms sont cumulees depuis le lancement du process ; relevees une
// fois par seconde, comme system_cpu_pct, et vides sinon.
final class DebugTimings implements Closeable {

    // Dans l'ordre du flux : voir STREAM_STATS dans mcmm.c.
    static final String[] MCMM_COLUMNS = {"ffmpeg_us", "mcmm_write_us", "mcmm_cpu_us", "convert_us",
            "dct_us", "encode_us", "search_us", "assemble_us", "symbols_us", "q", "stream_bits"};
    private static final int FFMPEG = 0, MCMM_CPU = 2, CONVERT = 3, SEARCH = 6, Q = 9;

    private static final long SAMPLE_NANOS = 1_000_000_000L;
    private static final long WINDOW_NANOS = 60 * SAMPLE_NANOS;
    private static final long MAX_SAMPLE_GAP_NANOS = 3 * SAMPLE_NANOS;  // au-dela : une pause, pas un debit

    private record FrameTimes(long nanos, long ffmpeg, long convert, long search, boolean saturated,
                              long send, long late, boolean sent) {}

    private record CpuSample(long nanos, long mcmm, long ffmpeg, long java, long netty, long systemPct) {}

    private final long startNanos = System.nanoTime();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final ArrayDeque<FrameTimes> frames = new ArrayDeque<>();
    private final ArrayDeque<CpuSample> samples = new ArrayDeque<>();
    private long lastSampleNanos = startNanos - SAMPLE_NANOS;   // premiere ligne = premier releve
    private long mcmmCpuMicros;
    private ProcessHandle daemonFfmpeg;
    private long daemonFfmpegCpuMillis;
    private PrintWriter out;

    static Path newFile(Path dataFolder) {
        return dataFolder.resolve("debug").resolve(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv");
    }

    static String formatMeta(String playSource, String source, boolean daemon,
                             String render, String wall, int width, int height, int fps) {
        String name = "";
        if (playSource != null && !playSource.isEmpty()) {
            try {
                name = Path.of(playSource).getFileName().toString();
            } catch (Exception ignored) {
                name = playSource;
            }
        }
        String src = source != null ? source.replace(',', ' ') : "";
        return String.format("# name=%s,source=%s,daemon=%s,render=%s,wall=%s,width=%d,height=%d,fps=%d",
                name, src, daemon ? "on" : "off", render, wall, width, height, fps);
    }

    boolean isWriting() {
        return out != null;
    }

    void startWriting(Path file, String metaComment) throws IOException {
        Files.createDirectories(file.getParent());
        BufferedWriter writer = Files.newBufferedWriter(file);
        out = new PrintWriter(writer);
        if (metaComment != null && !metaComment.isBlank()) {
            out.println(metaComment.startsWith("#") ? metaComment : "# " + metaComment);
        }
        out.println("frame,t_ms,segment_ms,pts_ms,budget_us,read_us," + String.join(",", MCMM_COLUMNS)
                + ",sent,diff_us,send_us,tiles_changed,viewers,viewers_skipped,sleep_us,late_us"
                + ",ffmpeg_cpu_ms,java_cpu_ms,netty_cpu_ms,system_cpu_pct");
    }

    // sent = false : frame sautee pour rattraper le retard, sendNanos ne compte alors rien.
    void row(long frame, long segmentMillis, McmmStream stream, long budgetNanos, long readNanos,
             VirtualScreen screen, boolean sent, long sendNanos, long sleepNanos) {
        long now = System.nanoTime();
        int[] stats = stream.getStats();
        long late = Math.max(-sleepNanos, 0);
        mcmmCpuMicros += Integer.toUnsignedLong(stats[MCMM_CPU]);
        CpuSample sample = null;
        if (now - lastSampleNanos >= SAMPLE_NANOS) {
            lastSampleNanos = now;
            sample = sampleCpu(now, stream);
        }
        synchronized (this) {
            frames.addLast(new FrameTimes(now, Integer.toUnsignedLong(stats[FFMPEG]),
                    Integer.toUnsignedLong(stats[CONVERT]), Integer.toUnsignedLong(stats[SEARCH]),
                    stats[Q] != 0, sendNanos, late, sent));
            if (sample != null) {
                samples.addLast(sample);
            }
            expire(now);
        }
        if (out != null) {
            writeRow(now, frame, segmentMillis, stream, budgetNanos, readNanos, screen, sent, sendNanos,
                    sleepNanos, sample);
        }
    }

    private void writeRow(long now, long frame, long segmentMillis, McmmStream stream, long budgetNanos,
                          long readNanos, VirtualScreen screen, boolean sent, long sendNanos,
                          long sleepNanos, CpuSample sample) {
        StringBuilder line = new StringBuilder(160);
        line.append(frame).append(',').append((now - startNanos) / 1_000_000).append(',')
                .append(segmentMillis).append(',')
                .append(stream.getPtsMicros() == McmmStream.NO_PTS ? "" : stream.getPtsMicros() / 1000).append(',')
                .append(budgetNanos / 1000).append(',').append(readNanos / 1000);
        for (int value : stream.getStats()) {
            line.append(',').append(Integer.toUnsignedLong(value));
        }
        long diff = sent ? screen.getLastDiffNanos() : 0;
        line.append(',').append(sent ? 1 : 0)
                .append(',').append(diff / 1000).append(',').append((sendNanos - diff) / 1000)
                .append(',').append(sent ? screen.getLastChangedTiles() : 0)
                .append(',').append(screen.getLastViewers())
                .append(',').append(sent ? screen.getLastSkippedViewers() : 0)
                .append(',').append(Math.max(sleepNanos, 0) / 1000)
                .append(',').append(Math.max(-sleepNanos, 0) / 1000);
        if (sample != null) {
            line.append(',').append(sample.ffmpeg()).append(',').append(sample.java())
                    .append(',').append(sample.netty()).append(',').append(sample.systemPct());
            out.flush();                // suivable en direct, et rien de perdu sur un crash
        } else {
            line.append(",,,,");
        }
        out.println(line);
    }

    // Ecrit une ligne de CSV en mode demon C (OP_FRAME_STATS).
    void rowDaemon(Process daemonProc, int[] pkt, long sendNanos) {
        if (out == null || pkt.length < 20) return;
        long now = System.nanoTime();
        long frame = Integer.toUnsignedLong(pkt[0]);
        long segmentMs = pkt[1];
        long ptsMs = pkt[2];
        long budgetUs = Integer.toUnsignedLong(pkt[3]);
        mcmmCpuMicros += Integer.toUnsignedLong(pkt[6]);

        CpuSample sample = null;
        if (now - lastSampleNanos >= SAMPLE_NANOS) {
            lastSampleNanos = now;
            sample = sampleDaemonCpu(now, daemonProc);
        }

        StringBuilder line = new StringBuilder(160);
        line.append(frame).append(',').append((now - startNanos) / 1_000_000).append(',')
                .append(segmentMs).append(',').append(ptsMs).append(',')
                .append(budgetUs).append(',').append(0); // read_us = 0 (c'est le C qui pousse)
        for (int i = 4; i <= 14; i++) {
            line.append(',').append(Integer.toUnsignedLong(pkt[i]));
        }
        int diffUs = pkt[15];
        int changed = pkt[16];
        int viewers = pkt[17];
        int skipped = pkt[18];
        int sleepUs = pkt[19];
        line.append(',').append(1)
                .append(',').append(diffUs)
                .append(',').append(sendNanos / 1000)
                .append(',').append(changed)
                .append(',').append(viewers)
                .append(',').append(skipped)
                .append(',').append(Math.max(sleepUs, 0))
                .append(',').append(Math.max(-sleepUs, 0));
        if (sample != null) {
            line.append(',').append(sample.ffmpeg()).append(',').append(sample.java())
                    .append(',').append(sample.netty()).append(',').append(sample.systemPct());
            out.flush();
        } else {
            line.append(",,,,");
        }
        out.println(line);
    }

    private CpuSample sampleDaemonCpu(long now, Process daemonProc) {
        var os = ManagementFactory.getOperatingSystemMXBean();
        long javaMs = 0, pct = 0;
        if (os instanceof com.sun.management.OperatingSystemMXBean jvm) {
            javaMs = jvm.getProcessCpuTime() / 1_000_000;
            pct = Math.round(jvm.getCpuLoad() * 100);
        }
        if (daemonProc != null) {
            if (daemonFfmpeg == null || !daemonFfmpeg.isAlive()) {
                daemonFfmpeg = daemonProc.descendants()
                        .filter(p -> p.info().command().orElse("").contains("ffmpeg"))
                        .findFirst().orElse(null);
            }
            if (daemonFfmpeg != null) {
                daemonFfmpegCpuMillis = daemonFfmpeg.info().totalCpuDuration()
                        .map(java.time.Duration::toMillis).orElse(daemonFfmpegCpuMillis);
            }
        }
        return new CpuSample(now, mcmmCpuMicros / 1000, daemonFfmpegCpuMillis, javaMs, nettyCpuMillis(), pct);
    }

    // La marge compte tout le travail d'une frame : le temps ou le plugin attend mcmm n'en dit rien,
    // puisque mcmm prend de l'avance et que cette attente reste faible jusqu'au decrochage.
    String[] describe(long budgetNanos) {
        long now = System.nanoTime();
        List<FrameTimes> window;
        List<CpuSample> cpu;
        synchronized (this) {
            expire(now);
            window = new ArrayList<>(frames);
            cpu = new ArrayList<>(samples);
        }
        if (window.isEmpty()) {
            return new String[] {"Timings: no frame measured in the last minute"};
        }

        int n = window.size();
        long ffmpeg = 0, convert = 0, send = 0, satConvert = 0, satSearch = 0;
        int saturated = 0, late = 0, skipped = 0;
        long[] totals = new long[n];
        for (int i = 0; i < n; i++) {
            FrameTimes f = window.get(i);
            ffmpeg += f.ffmpeg();
            convert += f.convert();
            send += f.send() / 1000;
            totals[i] = f.ffmpeg() + f.convert() + f.send() / 1000;
            if (f.saturated()) {
                saturated++;
                satConvert += f.convert();
                satSearch += f.search();
            }
            if (f.late() > 0) {
                late++;
            }
            if (!f.sent()) {
                skipped++;
            }
        }
        Arrays.sort(totals);
        double budgetMs = budgetNanos / 1e6;
        double workMs = (ffmpeg + convert + send) / 1e3 / n;
        double slowestMs = totals[Math.min(n - 1, (int) (n * 0.99))] / 1e3;
        double margin = budgetMs / workMs;
        double slowestMargin = budgetMs / slowestMs;
        String verdict = margin < 1 ? "BEHIND real time, lower fps/size"
                : slowestMargin < 1 ? "peaks over budget" : "OK";
        long seconds = Math.round((window.get(n - 1).nanos() - window.get(0).nanos()) / 1e9);

        List<String> lines = new ArrayList<>();
        lines.add(String.format("Last %d s: %.1f ms/frame vs %.1f ms budget (ffmpeg wait %.1f + encode %.1f + send %.1f)",
                seconds, workMs, budgetMs, ffmpeg / 1e3 / n, convert / 1e3 / n, send / 1e3 / n));
        lines.add(String.format("Margin: x%.2f on average, x%.2f on the slowest 1%% — %s",
                margin, slowestMargin, verdict));
        if (saturated > 0) {
            lines.add(String.format("Full wall: %d%% of frames, encode %.1f ms (q search %.1f)",
                    Math.round(100.0 * saturated / n), satConvert / 1e3 / saturated, satSearch / 1e3 / saturated));
        }
        lines.add(String.format("Late: %d frame(s), image now %.1f s behind, %d frame(s) skipped to catch up",
                late, window.get(n - 1).late() / 1e9, skipped));
        String cpuLine = describeCpu(cpu);
        if (cpuLine != null) {
            lines.add(cpuLine);
        }
        return lines.toArray(new String[0]);
    }

    // Par paires de releves : un seek relance ffmpeg (son compteur repart de zero) et une pause laisse
    // un trou, les deux ne doivent pas fausser le debit.
    private static String describeCpu(List<CpuSample> cpu) {
        long millis = 0, mcmm = 0, ffmpeg = 0, java = 0, netty = 0, pct = 0;
        int counted = 0;
        for (int i = 1; i < cpu.size(); i++) {
            CpuSample a = cpu.get(i - 1), b = cpu.get(i);
            if (b.nanos() - a.nanos() > MAX_SAMPLE_GAP_NANOS) {
                continue;
            }
            millis += (b.nanos() - a.nanos()) / 1_000_000;
            mcmm += b.mcmm() - a.mcmm();
            ffmpeg += Math.max(b.ffmpeg() - a.ffmpeg(), 0);
            java += b.java() - a.java();
            netty += b.netty() - a.netty();
            pct += b.systemPct();
            counted++;
        }
        if (counted == 0) {
            return null;
        }
        double t = millis;
        return String.format("CPU: mcmm %.2f + ffmpeg %.2f + Netty %.2f + other Java %.2f cores, machine %d%%",
                mcmm / t, ffmpeg / t, netty / t, Math.max(java - netty, 0) / t, pct / counted);
    }

    private void expire(long now) {
        while (!frames.isEmpty() && now - frames.peekFirst().nanos() > WINDOW_NANOS) {
            frames.removeFirst();
        }
        while (!samples.isEmpty() && now - samples.peekFirst().nanos() > WINDOW_NANOS) {
            samples.removeFirst();
        }
    }

    private CpuSample sampleCpu(long now, McmmStream stream) {
        var os = ManagementFactory.getOperatingSystemMXBean();
        long java = 0, pct = 0;
        if (os instanceof com.sun.management.OperatingSystemMXBean jvm) {
            java = jvm.getProcessCpuTime() / 1_000_000;
            pct = Math.round(jvm.getCpuLoad() * 100);
        }
        return new CpuSample(now, mcmmCpuMicros / 1000, stream.getFfmpegCpuMillis(), java, nettyCpuMillis(), pct);
    }

    // Les threads reseau du serveur : c'est eux qui encodent et compressent les paquets de map.
    private long nettyCpuMillis() {
        long nanos = 0;
        for (ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds())) {
            if (info != null && info.getThreadName().contains("Netty")) {
                nanos += Math.max(threads.getThreadCpuTime(info.getThreadId()), 0);
            }
        }
        return nanos / 1_000_000;
    }

    @Override
    public void close() {
        if (out != null) {
            out.close();
            out = null;
        }
    }
}
