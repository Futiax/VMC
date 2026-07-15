package ovh.futiax.minecraftvideo;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * One video playback: owns the {@link McmmStream} (the mcmm subprocess) and
 * the {@link VirtualScreen} it renders to.
 *
 * A daemon thread reads frames and paces them at the fps reported by the
 * stream header, using System.nanoTime deadlines (the next deadline is
 * derived from the previous one, so sleep jitter does not accumulate drift).
 *
 * NOTE ON THREADING: map-data and entity packets are intentionally sent from
 * this async playback thread. packetevents' PlayerManager#sendPacket is
 * thread-safe for sending (it writes straight to the player's netty channel),
 * so no main-thread scheduling is needed for the packet traffic. Only Bukkit
 * API calls (chat messages, session bookkeeping) are hopped back to the main
 * thread via the scheduler.
 */
public final class PlaybackSession {

    private final MinecraftVideoPlugin plugin;
    private final UUID initiatorId;
    private final String initiatorName;
    private final Location anchor; // captured on the main thread
    private final String mcmmPath;
    private final String palettePath;
    private final String source;
    private final int requestedWidth;
    private final int requestedHeight;
    private final int requestedFps;
    private final String ffmpegPath;
    private final boolean audioEnabled;
    private final int audioDistance;
    private final int audioChannels; // 1 = mono (center), 2 = stereo (L/R edges)

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Object lock = new Object();
    private McmmStream stream;      // guarded by lock
    private VirtualScreen screen;   // guarded by lock
    private AudioPlayback audio;    // guarded by lock
    private volatile Thread playbackThread;

    // Pause state. The playback thread parks on pauseLock while paused; mcmm and
    // the audio ffmpeg are throttled by pipe/queue backpressure meanwhile.
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final Object pauseLock = new Object();

    // Generation-speed metrics: written by the playback thread, read (racily,
    // which is fine for a status readout) by /video status on the main thread.
    private volatile long playStartNanos;    // 0 until the frame loop starts
    private volatile long pausedAccumNanos;  // total time spent paused
    private volatile long pauseStartNanos;   // != 0 while currently paused
    private volatile long framesSent;
    private volatile long totalDecodeNanos;  // cumulative time blocked in nextFrame()
    private volatile long totalSendNanos;    // cumulative time in sendFrame()
    private volatile int streamFps;
    private volatile int screenW;
    private volatile int screenH;

    public PlaybackSession(MinecraftVideoPlugin plugin, Player initiator,
                           String mcmmPath, String palettePath, String source,
                           int width, int height, int fps,
                           String ffmpegPath, boolean audioEnabled, int audioDistance,
                           int audioChannels) {
        this.plugin = plugin;
        this.initiatorId = initiator.getUniqueId();
        this.initiatorName = initiator.getName();
        this.anchor = initiator.getLocation().clone();
        this.mcmmPath = mcmmPath;
        this.palettePath = palettePath;
        this.source = source;
        this.requestedWidth = width;
        this.requestedHeight = height;
        this.requestedFps = fps;
        this.ffmpegPath = ffmpegPath;
        this.audioEnabled = audioEnabled;
        this.audioDistance = audioDistance;
        this.audioChannels = audioChannels;
    }

    /**
     * Starts playback on a daemon thread for the given initial viewers
     * (snapshot taken on the main thread).
     */
    public void start(Collection<? extends Player> initialViewers) {
        List<Player> viewers = List.copyOf(initialViewers);
        Thread thread = new Thread(() -> run(viewers), "MinecraftVideo-Playback");
        thread.setDaemon(true);
        this.playbackThread = thread;
        thread.start();
    }

    /**
     * Adds a late joiner: re-sends spawn, metadata and the last frame.
     *
     * Done under {@code lock} so it cannot interleave with a concurrent
     * stop()/destroy(): either the viewer is added before destroy() (and gets
     * its remove packets), or stopped is already set and we skip entirely.
     * Otherwise a joiner could receive spawn packets after destroy() ran,
     * leaving ghost frames until relog.
     */
    public void addViewer(Player player) {
        synchronized (lock) {
            if (stopped.get() || screen == null) {
                return;
            }
            screen.addViewer(player);
        }
    }

    /**
     * Stops playback: kills the mcmm process and removes the screen for all
     * viewers. Idempotent; safe to call from any thread.
     */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        synchronized (lock) {
            if (stream != null) {
                stream.close(); // destroys the process forcibly
            }
            if (screen != null) {
                screen.destroy();
            }
            if (audio != null) {
                audio.stop();
            }
        }
        Thread thread = playbackThread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
        plugin.clearSession(this);
    }

    /**
     * Waits up to {@code timeoutMillis} for the playback thread to finish.
     * Called from onDisable so no packet is sent after packetevents is
     * terminated and the plugin class loader is closed.
     */
    public void join(long timeoutMillis) {
        Thread thread = playbackThread;
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isStopped() {
        return stopped.get();
    }

    public boolean isPaused() {
        return paused.get();
    }

    public String getInitiatorName() {
        return initiatorName;
    }

    /**
     * Pauses video and audio. The screen keeps showing the last frame; mcmm and
     * the audio ffmpeg block on their pipe/queue once buffers fill, so no
     * decoding runs ahead. Returns false if already paused or stopped.
     */
    public boolean pause() {
        if (stopped.get() || !paused.compareAndSet(false, true)) {
            return false;
        }
        AudioPlayback a;
        synchronized (lock) {
            a = audio;
        }
        if (a != null) {
            a.setPaused(true);
        }
        return true;
    }

    /** Resumes a paused playback. Returns false if not currently paused. */
    public boolean resume() {
        if (stopped.get() || !paused.compareAndSet(true, false)) {
            return false;
        }
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        AudioPlayback a;
        synchronized (lock) {
            a = audio;
        }
        if (a != null) {
            a.setPaused(false);
        }
        return true;
    }

    /** Parks the playback thread while paused; returns a fresh deadline on resume. */
    private long awaitResume(long intervalNanos) {
        synchronized (pauseLock) {
            pauseStartNanos = System.nanoTime();
            while (paused.get() && !stopped.get()) {
                try {
                    pauseLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break; // stop() interrupts us; fall through and let the loop exit
                }
            }
            pausedAccumNanos += System.nanoTime() - pauseStartNanos;
            pauseStartNanos = 0;
        }
        return System.nanoTime() + intervalNanos;
    }

    /**
     * Human-readable status lines for /video status, including how much headroom
     * the generation pipeline has over the target frame budget.
     */
    public String[] describeStatus() {
        long frames = framesSent;
        int fps = streamFps;
        if (playStartNanos == 0 || fps == 0) {
            return new String[] { "Starting up (loading palette / building the color table)..." };
        }

        long now = System.nanoTime();
        long ps = pauseStartNanos;
        long pausedTotal = pausedAccumNanos + (ps != 0 ? now - ps : 0);
        double elapsedS = Math.max(0.0, (now - playStartNanos - pausedTotal)) / 1e9;
        double effFps = elapsedS > 0 ? frames / elapsedS : 0;
        double avgDecodeMs = frames > 0 ? totalDecodeNanos / 1e6 / frames : 0;
        double avgSendMs = frames > 0 ? totalSendNanos / 1e6 / frames : 0;
        double workMs = avgDecodeMs + avgSendMs;
        double budgetMs = 1000.0 / fps;

        String margin;
        if (workMs <= 0) {
            margin = "n/a (no frames yet)";
        } else {
            double headroom = budgetMs / workMs;
            if (headroom >= 1.0) {
                int idlePct = (int) Math.round((1.0 - workMs / budgetMs) * 100);
                margin = String.format("%.1fx headroom (%d%% idle) — plenty of room", headroom, idlePct);
            } else {
                margin = String.format("BEHIND real time (%.2fx) — generation can't keep up, lower fps/size", headroom);
            }
        }

        return new String[] {
            "Source: " + shortSource() + (paused.get() ? "  [PAUSED]" : ""),
            "Screen: " + screenW + "x" + screenH + " maps @ " + fps + " fps",
            String.format("Progress: %d frames, %.1fs, %.1f effective fps", frames, elapsedS, effFps),
            String.format("Generation: %.1f ms/frame (decode %.1f + send %.1f) vs %.1f ms budget",
                    workMs, avgDecodeMs, avgSendMs, budgetMs),
            "Margin: " + margin,
        };
    }

    private String shortSource() {
        if (source.length() <= 60) {
            return source;
        }
        return "..." + source.substring(source.length() - 57);
    }

    private void run(List<Player> initialViewers) {
        try {
            // Start the subprocess, then publish it BEFORE the blocking header
            // read so a concurrent stop() can kill mcmm and unblock us.
            McmmStream newStream = new McmmStream(plugin.getLogger(),
                    mcmmPath, palettePath, source,
                    requestedWidth, requestedHeight, requestedFps);
            synchronized (lock) {
                if (stopped.get()) {
                    newStream.close();
                    return;
                }
                stream = newStream;
            }
            newStream.readHeader(); // blocking; stop() -> close() unblocks it

            // Build the screen from the header dimensions (authoritative) and
            // spawn it under the lock so it cannot interleave with destroy().
            VirtualScreen newScreen = new VirtualScreen(anchor,
                    newStream.getMapWidth(), newStream.getMapHeight());
            synchronized (lock) {
                if (stopped.get()) {
                    return; // stop() already closed the stream
                }
                screen = newScreen;
                newScreen.spawn(initialViewers);
            }

            // Audio is independent of the map packets: its own ffmpeg + SVC
            // channel, started alongside the first frame.
            startAudioIfEnabled(newScreen);

            int fps = Math.max(1, newStream.getFps());
            long intervalNanos = 1_000_000_000L / fps;
            long deadline = System.nanoTime() + intervalNanos;
            this.streamFps = fps;
            this.screenW = newStream.getMapWidth();
            this.screenH = newStream.getMapHeight();
            this.playStartNanos = System.nanoTime();

            while (!stopped.get()) {
                if (paused.get()) {
                    deadline = awaitResume(intervalNanos); // re-anchors the deadline
                    if (stopped.get()) {
                        break;
                    }
                }

                long t0 = System.nanoTime();
                byte[][] frame = newStream.nextFrame();
                if (frame == null) {
                    break; // EOF: end of video
                }
                long t1 = System.nanoTime();
                newScreen.sendFrame(frame); // VirtualScreen keeps it for late joiners
                long t2 = System.nanoTime();

                totalDecodeNanos += (t1 - t0);
                totalSendNanos += (t2 - t1);
                framesSent++;

                long sleepNanos = deadline - System.nanoTime();
                if (sleepNanos > 0) {
                    LockSupport.parkNanos(sleepNanos);
                }
                // Anchor the next deadline to the previous one: no drift.
                deadline += intervalNanos;
                long now = System.nanoTime();
                if (deadline < now - intervalNanos) {
                    deadline = now; // fell far behind (slow decode); resync
                }
            }

            if (!stopped.get()) {
                messageInitiator("Video finished.");
            }
        } catch (IOException e) {
            if (!stopped.get()) {
                plugin.getLogger().warning("Playback failed: " + e.getMessage());
                messageInitiator("Video playback failed: " + e.getMessage());
            }
        } catch (Throwable t) {
            plugin.getLogger().severe("Unexpected playback error: " + t);
            t.printStackTrace();
        } finally {
            stop(); // auto-stop at EOF or on error; idempotent
        }
    }

    /**
     * Starts spatialised audio for this playback if enabled and Simple Voice
     * Chat is available. Failure is non-fatal: the video keeps playing silently.
     */
    private void startAudioIfEnabled(VirtualScreen forScreen) {
        if (!audioEnabled) {
            return;
        }
        VoicechatHook hook = plugin.getVoicechatHook();
        if (hook == null || !hook.isReady()) {
            return;
        }
        World world = anchor.getWorld();
        if (world == null) {
            return;
        }
        // Mono: one channel at the screen center. Stereo: one per screen edge,
        // so the client pans L/R from the viewer's own position (a fixed image).
        double[][] anchors = (audioChannels == 2)
                ? new double[][] { forScreen.getLeftAnchor(), forScreen.getRightAnchor() }
                : new double[][] { forScreen.getCenter() };
        AudioPlayback playback = AudioPlayback.start(hook.getServerApi(), world,
                anchors, audioDistance, ffmpegPath, source, plugin.getLogger());
        if (playback == null) {
            return;
        }
        synchronized (lock) {
            if (stopped.get()) {
                playback.stop(); // lost the race with stop(); don't leak ffmpeg
                return;
            }
            audio = playback;
        }
    }

    /** Sends a chat message to the initiator on the main thread. */
    private void messageInitiator(String message) {
        if (!plugin.isEnabled()) {
            return; // can't schedule tasks while disabling
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player player = plugin.getServer().getPlayer(initiatorId);
                if (player != null) {
                    player.sendMessage("[MinecraftVideo] " + message);
                }
            });
        } catch (IllegalStateException | IllegalPluginAccessException e) {
            // Plugin got disabled between the isEnabled() check and scheduling;
            // nothing to deliver, and cleanup still runs in run()'s finally.
        }
    }
}
