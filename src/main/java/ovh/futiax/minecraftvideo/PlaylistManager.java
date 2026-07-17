package ovh.futiax.minecraftvideo;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Playlist: a FIFO of sources waiting behind the active playback. MAIN-THREAD
 * ONLY (commands and scheduler tasks); the one cross-thread entry point is
 * {@link #scheduleAdvance()}, which hops onto the main thread.
 *
 * <p>Auto-advance: {@link MinecraftVideoPlugin#clearSession} calls
 * {@code scheduleAdvance()} whenever the active session ends — natural EOF,
 * error, {@code /video stop} or {@code /video skip} alike. {@code stop}
 * empties the queue FIRST (so "stop" really stops), while {@code skip} and
 * EOF leave it intact and the next item starts.
 *
 * <p>Queued items reuse the anchor of the session playing when they were
 * added, so the screen stays put across episodes; items queued while idle use
 * the adder's position (same semantics as {@code /video play}). Screen size,
 * fps and audio settings are read from the persisted options when the item
 * STARTS, like a plain {@code /video play} without arguments.
 */
public final class PlaylistManager {

    /** One queued video: what to play, where, and who asked. */
    public record QueueItem(String source, UUID initiatorId, String initiatorName,
                            Location anchor) {}

    private final MinecraftVideoPlugin plugin;
    private final List<QueueItem> queue = new ArrayList<>();

    public PlaylistManager(MinecraftVideoPlugin plugin) {
        this.plugin = plugin;
    }

    /** Appends a source; returns its queue position (1-based). */
    public int add(String source, Player initiator) {
        PlaybackSession active = plugin.getActiveSession();
        Location anchor = active != null ? active.getAnchor()
                : initiator.getLocation().clone();
        queue.add(new QueueItem(source, initiator.getUniqueId(),
                initiator.getName(), anchor));
        // Reference the cache for this queued occurrence: the file survives while
        // any occurrence is still queued or playing. The reference is transferred
        // to the session when advance() plays this item (session releases it).
        plugin.getMediaCache().reference(source);
        return queue.size();
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public void clear() {
        // Release each queued occurrence's cache reference before dropping them.
        for (QueueItem item : queue) {
            plugin.getMediaCache().release(item.source());
        }
        queue.clear();
    }

    /** Removes the 1-based {@code position}; returns the removed item or null. */
    public QueueItem remove(int position) {
        if (position < 1 || position > queue.size()) {
            return null;
        }
        QueueItem removed = queue.remove(position - 1);
        plugin.getMediaCache().release(removed.source()); // occurrence dropped unplayed
        return removed;
    }

    /** Numbered listing for {@code /video queue list}. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>(queue.size());
        for (int i = 0; i < queue.size(); i++) {
            QueueItem item = queue.get(i);
            lines.add((i + 1) + ". " + shorten(item.source())
                    + " (queued by " + item.initiatorName() + ")");
        }
        return lines;
    }

    static String shorten(String source) {
        return source.length() <= 60 ? source
                : "..." + source.substring(source.length() - 57);
    }

    /**
     * Hops to the main thread and starts the next queued item if nothing is
     * playing. Safe to call from any thread; no-ops while the plugin disables.
     */
    public void scheduleAdvance() {
        if (queue.isEmpty() || !plugin.isEnabled()) {
            return; // isEmpty is a racy pre-check; advance() re-checks on main
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, this::advance);
        } catch (IllegalStateException | IllegalPluginAccessException e) {
            // Plugin disabling between the check and the schedule; nothing to start.
        }
    }

    /** Starts the next queued item if idle. Main thread only. */
    public void advance() {
        if (queue.isEmpty() || plugin.getActiveSession() != null) {
            return;
        }
        // remove(0) here transfers this occurrence's cache reference to the
        // session that will play it (the session releases it when it ends). The
        // two early-outs below that do NOT start a session release it themselves.
        QueueItem item = queue.remove(0);
        String mcmmPath = plugin.resolveMcmmPath();
        String palettePath = plugin.resolvePalettePath();
        if (mcmmPath == null || palettePath == null) {
            plugin.getLogger().warning("Skipping queued video (mcmm or palette missing): "
                    + item.source());
            plugin.getMediaCache().release(item.source()); // not played: drop its reference
            advance(); // try the next one
            return;
        }
        int width = plugin.getConfig().getInt("default-width", 4);
        int height = plugin.getConfig().getInt("default-height", 3);
        int fps = plugin.getConfig().getInt("default-fps", 10);
        PlaybackSession session = new PlaybackSession(plugin, item.initiatorId(),
                item.initiatorName(), item.anchor().clone(), mcmmPath, palettePath,
                item.source(), width, height, fps, plugin.buildAudioSettings());
        if (!plugin.trySetActiveSession(session)) {
            queue.add(0, item); // lost a race with a manual /video play; keep it queued
            return;
        }
        session.start(plugin.getServer().getOnlinePlayers());
        Player initiator = plugin.getServer().getPlayer(item.initiatorId());
        if (initiator != null) {
            initiator.sendMessage("[MinecraftVideo] Now playing from the queue: "
                    + shorten(item.source())
                    + (queue.isEmpty() ? "" : " (" + queue.size() + " more queued)"));
        }
    }
}
