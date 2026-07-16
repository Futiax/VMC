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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * One video playback: owns the {@link McmmStream} (the mcmm subprocess), the
 * {@link VirtualScreen} it renders to and the optional {@link AudioPlayback}.
 *
 * <p><b>Segments.</b> Playback is a sequence of segments, each one mcmm + one
 * audio pipeline started at a time offset. The initial play is the segment at
 * offset 0; every {@code /video seek} kills the current segment's subprocesses
 * and starts a new segment at the target offset, REUSING the same virtual
 * screen (no flicker, viewers keep the last frame until the new one arrives).
 *
 * <p><b>A/V sync (freeze-frame model).</b> The audio path carries latency the
 * video path does not: the SVC client buffers audio in a jitter buffer that
 * INFLATES when packets arrive while the client is busy (the render spike of a
 * new screen appearing) and never drains back down, while map packets just
 * apply on the next client tick. So on the first segment, frame 0 is sent
 * immediately and FROZEN for {@code audio-start-delay-ms} — the client absorbs
 * the spawn spike while the picture is static and no audio flows — then video
 * pacing and the audio start TOGETHER, anchored on the same instant. The audio
 * only skips {@code av-sync-delay-ms} of content (ffmpeg {@code -ss}) to
 * compensate the client's steady-state audio buffering; no content is lost to
 * the warm-up itself. Seek segments reuse an already-spawned screen (no render
 * spike), so they skip the freeze and begin audio as soon as it is primed.
 * The audio ffmpeg is spawned BEFORE mcmm's blocking header read (pre-warm),
 * so for URL sources both pipelines connect in parallel.
 *
 * <p>A daemon thread reads frames and paces them at the fps reported by the
 * stream header, using System.nanoTime deadlines (the next deadline is derived
 * from the previous one, so sleep jitter does not accumulate drift).
 *
 * <p>NOTE ON THREADING: map-data and entity packets are intentionally sent from
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
    private final AudioSettings audioSettings;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Object lock = new Object();
    private McmmStream stream;      // guarded by lock (current segment)
    private VirtualScreen screen;   // guarded by lock (whole session)
    private AudioPlayback audio;    // guarded by lock (current segment)
    private volatile Thread playbackThread;

    // Pause state. The playback thread parks on pauseLock while paused; mcmm and
    // the audio ffmpeg are throttled by pipe/queue backpressure meanwhile.
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final Object pauseLock = new Object();

    /** Seek request in millis; -1 = none. Consumed once per segment change. */
    private final AtomicLong pendingSeekMillis = new AtomicLong(-1);

    // Generation-speed metrics for the CURRENT segment: written by the playback
    // thread, read (racily, which is fine for a status readout) by /video status
    // on the main thread.
    private volatile long playStartNanos;    // 0 until the segment's frame loop starts
    private volatile long pausedAccumNanos;  // total time spent paused (this segment)
    private volatile long pauseStartNanos;   // != 0 while currently paused
    private volatile long framesSent;        // frames sent this segment
    private volatile long totalDecodeNanos;  // cumulative time blocked in nextFrame()
    private volatile long totalSendNanos;    // cumulative time in sendFrame()
    private volatile int streamFps;
    private volatile int screenW;
    private volatile int screenH;
    /** Time offset (millis into the video) where the current segment started. */
    private volatile long segmentOffsetMillis;

    /**
     * Source audio channel count, ffprobe'd once per session for the surround
     * upmix decision and reused across seek segments: 0 = not probed yet,
     * -1 = probe failed (surround falls back to the plain 5.1 bed mapping).
     */
    private volatile int sourceAudioChannels;

    public PlaybackSession(MinecraftVideoPlugin plugin, Player initiator,
                           String mcmmPath, String palettePath, String source,
                           int width, int height, int fps, AudioSettings audioSettings) {
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
        this.audioSettings = audioSettings;
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

    /** Current position in the video, in millis (segment offset + frames sent). */
    public long getPositionMillis() {
        int fps = streamFps;
        if (fps <= 0) {
            return segmentOffsetMillis;
        }
        return segmentOffsetMillis + framesSent * 1000L / fps;
    }

    /**
     * Requests a jump to an absolute position (clamped at 0). The current
     * segment's subprocesses are killed; the playback thread starts a new
     * segment at the target on the same screen. Seeking implies resuming.
     * Returns false if the session is already stopped.
     */
    public boolean seekTo(long targetMillis) {
        if (stopped.get()) {
            return false;
        }
        pendingSeekMillis.set(Math.max(0, targetMillis));
        synchronized (lock) {
            if (stream != null) {
                stream.close(); // unblocks the playback thread's read
            }
            if (audio != null) {
                audio.stop();
                audio = null;
            }
        }
        // Seek implies resume: wake the playback thread if it is parked.
        if (paused.compareAndSet(true, false)) {
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        }
        return true;
    }

    /** Relative seek from the current position (e.g. +10 s / -10 s). */
    public boolean seekBy(long deltaMillis) {
        return seekTo(getPositionMillis() + deltaMillis);
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
     * Holds the already-sent first frame on screen for
     * {@code audio-start-delay-ms} of unpaused time, staying responsive to
     * stop/seek (checked every ≤50 ms) and to pause (which extends the freeze).
     */
    private void freezeFirstFrame() {
        long remaining = Math.max(0, audioSettings.audioStartDelayMillis()) * 1_000_000L;
        while (remaining > 0 && !stopped.get() && pendingSeekMillis.get() < 0) {
            if (paused.get()) {
                awaitResume(0); // parks until resume; the freeze clock stops
                continue;
            }
            long t = System.nanoTime();
            LockSupport.parkNanos(Math.min(remaining, 50_000_000L));
            remaining -= System.nanoTime() - t;
        }
    }

    /**
     * Human-readable status lines for /video status, including the position and
     * how much headroom the generation pipeline has over the frame budget.
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
            "Screen: " + screenW + "x" + screenH + " maps @ " + fps + " fps, audio "
                    + audioSettings.mode().configName(),
            "Position: " + formatTimestamp(getPositionMillis())
                    + String.format(" — %d frames this segment, %.1f effective fps", frames, effFps),
            String.format("Generation: %.1f ms/frame (decode %.1f + send %.1f) vs %.1f ms budget",
                    workMs, avgDecodeMs, avgSendMs, budgetMs),
            "Margin: " + margin,
        };
    }

    /** Formats millis as m:ss or h:mm:ss. */
    public static String formatTimestamp(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    private String shortSource() {
        if (source.length() <= 60) {
            return source;
        }
        return "..." + source.substring(source.length() - 57);
    }

    private void run(List<Player> initialViewers) {
        try {
            long offsetMillis = 0;
            boolean first = true;
            while (!stopped.get()) {
                long next = playSegment(initialViewers, offsetMillis, first);
                first = false;
                if (next < 0) {
                    break; // EOF or stop
                }
                offsetMillis = next;
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
     * Plays one segment starting at {@code offsetMillis}. Creates the screen on
     * the first segment, reuses it afterwards. Returns the next segment's offset
     * if a seek was requested, or -1 when playback is over (EOF or stop).
     *
     * @throws IOException on a real failure (not on a seek-triggered close)
     */
    private long playSegment(List<Player> initialViewers, long offsetMillis, boolean first)
            throws IOException {
        // Per-segment metrics reset (status shows the current segment).
        segmentOffsetMillis = offsetMillis;
        playStartNanos = 0;
        pausedAccumNanos = 0;
        pauseStartNanos = 0;
        framesSent = 0;
        totalDecodeNanos = 0;
        totalSendNanos = 0;

        // Start the subprocess, then publish it BEFORE the blocking header
        // read so a concurrent stop()/seekTo() can kill mcmm and unblock us.
        McmmStream newStream = new McmmStream(plugin.getLogger(),
                mcmmPath, palettePath, source,
                requestedWidth, requestedHeight, requestedFps, offsetMillis);
        synchronized (lock) {
            if (stopped.get()) {
                newStream.close();
                return -1;
            }
            stream = newStream;
        }

        // Pre-warm the audio decoder in parallel with mcmm's blocking header
        // read (for URLs both connect concurrently). Owned by us until attached.
        // The decode starts av-sync-delay-ms past the video offset to cover
        // the client's audio buffering (sync model in the class javadoc).
        AudioStream preStream = prewarmAudio(offsetMillis + audioSettings.audioSkipMillis());
        boolean audioAttached = false;

        try {
            newStream.readHeader(); // blocking; stop()/seekTo() -> close() unblocks it

            VirtualScreen scr;
            if (first) {
                // Build the screen from the header dimensions (authoritative) and
                // spawn it under the lock so it cannot interleave with destroy().
                scr = new VirtualScreen(anchor,
                        newStream.getMapWidth(), newStream.getMapHeight());
                synchronized (lock) {
                    if (stopped.get()) {
                        return -1; // stop() already closed the stream
                    }
                    screen = scr;
                    scr.spawn(initialViewers);
                }
            } else {
                synchronized (lock) {
                    scr = screen;
                }
                if (scr == null) {
                    return -1; // stopped concurrently
                }
                if (scr.getWidth() != newStream.getMapWidth()
                        || scr.getHeight() != newStream.getMapHeight()) {
                    throw new IOException("stream header dimensions changed across a seek ("
                            + scr.getWidth() + "x" + scr.getHeight() + " -> "
                            + newStream.getMapWidth() + "x" + newStream.getMapHeight() + ")");
                }
            }

            // Attach the pre-warmed audio to SVC channels anchored on the screen.
            // It stays silent (not begun) until the frame loop starts pacing,
            // after the freeze-frame warm-up.
            AudioPlayback ap = null;
            if (preStream != null) {
                ap = attachAudio(preStream, scr);
                audioAttached = ap != null; // attachAudio closed preStream on failure
            }

            int fps = Math.max(1, newStream.getFps());
            long intervalNanos = 1_000_000_000L / fps;
            this.streamFps = fps;
            this.screenW = newStream.getMapWidth();
            this.screenH = newStream.getMapHeight();

            // Freeze-frame warm-up, first segment only: send frame 0 and hold
            // it for audio-start-delay-ms, so the client's screen-spawn render
            // spike passes over a static picture with no audio in flight. Seek
            // segments reuse the screen (no spawn spike): no freeze.
            if (first) {
                long t0 = System.nanoTime();
                byte[][] frame0 = newStream.nextFrame();
                if (frame0 != null) {
                    long t1 = System.nanoTime();
                    scr.sendFrame(frame0);
                    totalDecodeNanos += (t1 - t0);
                    totalSendNanos += (System.nanoTime() - t1);
                    framesSent++;
                    freezeFirstFrame();
                }
                // frame0 == null: instant EOF or killed; the loop below settles it.
            }

            // Pacing starts HERE: the video deadlines and the audio begin are
            // anchored on the same post-freeze instant, so both timelines run
            // from content 0 of this segment together.
            pausedAccumNanos = 0; // pauses during the freeze already extended it
            this.playStartNanos = System.nanoTime();
            long deadline = playStartNanos + intervalNanos;
            long audioBeginNanos = playStartNanos;
            boolean audioBegun = false;

            while (!stopped.get() && pendingSeekMillis.get() < 0) {
                if (paused.get()) {
                    deadline = awaitResume(intervalNanos); // re-anchors the deadline
                    if (stopped.get()) {
                        break;
                    }
                    continue; // re-check pendingSeek after waking (seek resumes us)
                }

                // The audio begins with the pacing (audioBeginNanos is the
                // post-freeze anchor), gated on ffmpeg having data buffered.
                // If priming made us late, drop the equivalent buffered audio
                // so the content stays aligned with the picture (effective
                // time: pauses shift video and audio alike, they don't count).
                if (!audioBegun && ap != null && ap.isPrimed()) {
                    long effNow = System.nanoTime() - pausedAccumNanos;
                    if (effNow >= audioBeginNanos) {
                        int lateFrames = (int) Math.min(500,
                                (effNow - audioBeginNanos) / 20_000_000L);
                        if (lateFrames > 0) {
                            ap.skipFrames(lateFrames);
                        }
                        ap.begin();
                        if (paused.get()) {
                            ap.setPaused(true); // paused in the same instant; stay frozen
                        }
                        audioBegun = true;
                    }
                }

                long t0 = System.nanoTime();
                byte[][] frame = newStream.nextFrame();
                if (frame == null) {
                    break; // EOF (real end, or the process was killed by a seek)
                }
                long t1 = System.nanoTime();
                scr.sendFrame(frame); // VirtualScreen keeps it for late joiners
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
        } catch (IOException e) {
            // A seek closes the stream mid-read; that IOException is expected
            // and means "segment over", not a failure.
            if (pendingSeekMillis.get() < 0 || stopped.get()) {
                throw e;
            }
        } finally {
            // Tear down this segment's pipelines; the screen persists.
            synchronized (lock) {
                newStream.close();
                if (stream == newStream) {
                    stream = null;
                }
                if (audio != null) {
                    audio.stop();
                    audio = null;
                }
            }
            if (preStream != null && !audioAttached) {
                preStream.close();
            }
        }

        long pending = pendingSeekMillis.getAndSet(-1);
        return (!stopped.get() && pending >= 0) ? pending : -1;
    }

    /**
     * Spawns the audio ffmpeg for this segment if audio is enabled and Simple
     * Voice Chat is available. Returns {@code null} when audio is off or the
     * spawn failed (playback continues silently).
     */
    private AudioStream prewarmAudio(long offsetMillis) {
        if (!audioSettings.enabled()) {
            return null;
        }
        VoicechatHook hook = plugin.getVoicechatHook();
        if (hook == null || !hook.isReady()) {
            return null;
        }
        if (anchor.getWorld() == null) {
            return null;
        }
        try {
            int srcChannels = -1;
            if (audioSettings.mode() == AudioMode.SURROUND) {
                srcChannels = sourceAudioChannels;
                if (srcChannels == 0) { // first segment: probe once, cache for seeks
                    srcChannels = AudioStream.probeChannels(plugin.getLogger(),
                            audioSettings.ffmpegPath(), source);
                    sourceAudioChannels = srcChannels;
                }
            }
            return new AudioStream(plugin.getLogger(), audioSettings.ffmpegPath(), source,
                    audioSettings.mode().decodeChannels(), offsetMillis, srcChannels);
        } catch (Exception e) {
            plugin.getLogger().warning("Audio unavailable (ffmpeg failed to start): "
                    + e.getMessage());
            return null;
        }
    }

    /**
     * Builds the SVC channels for the pre-warmed stream, anchored around the
     * screen according to the audio mode. On failure the stream is closed here
     * and {@code null} is returned (video continues silently).
     */
    private AudioPlayback attachAudio(AudioStream preStream, VirtualScreen forScreen) {
        VoicechatHook hook = plugin.getVoicechatHook();
        World world = anchor.getWorld();
        if (hook == null || !hook.isReady() || world == null) {
            preStream.close();
            return null;
        }
        double[][] anchors = switch (audioSettings.mode()) {
            case MONO -> new double[][] { forScreen.getCenter() };
            case STEREO -> new double[][] {
                    forScreen.getLeftAnchor(), forScreen.getRightAnchor() };
            // Order must match the decoded 5.1 layout: FL FR FC LFE BL BR.
            case SURROUND -> new double[][] {
                    forScreen.getLeftAnchor(),
                    forScreen.getRightAnchor(),
                    forScreen.getCenter(),
                    forScreen.getSubAnchor(),
                    forScreen.getRearLeftAnchor(audioSettings.rearDistance()),
                    forScreen.getRearRightAnchor(audioSettings.rearDistance()) };
        };
        try {
            AudioPlayback playback = AudioPlayback.create(hook.getServerApi(), world,
                    anchors, audioSettings.distance(), preStream, plugin.getLogger());
            synchronized (lock) {
                if (stopped.get()) {
                    playback.stop(); // lost the race with stop(); don't leak ffmpeg
                    return null;
                }
                audio = playback;
            }
            return playback;
        } catch (Exception e) {
            plugin.getLogger().warning("Audio playback unavailable: " + e.getMessage());
            preStream.close();
            return null;
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
