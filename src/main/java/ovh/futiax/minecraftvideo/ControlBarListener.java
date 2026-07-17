package ovh.futiax.minecraftvideo;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * packetevents listener that turns clicks on {@link ControlBar} buttons into
 * playback actions: ⏪ seek -10 s, ⏯ pause/resume, ⏩ seek +10 s, ⏭ skip.
 *
 * <p>Runs on the clicking client's netty thread. The packet-side work is kept
 * Bukkit-free (fake-id filter, packet dedup, per-player debounce — the player
 * identity comes from packetevents' own User); the click is then settled in
 * ONE main-thread task, because the permission check and the chat feedback
 * are Bukkit API. The session operations themselves (pause/resume/seek/stop)
 * are thread-safe either way — /video already runs them on the main thread —
 * so the hop costs at most one tick, imperceptible for a button.
 *
 * <p>One physical click can yield several INTERACT_ENTITY packets: a right
 * click sends INTERACT_AT + INTERACT (and the client may retry with the off
 * hand), a left click sends a single ATTACK. Only main-hand INTERACT and
 * ATTACK are acted on; the 250 ms debounce absorbs double-clicks on top.
 * Every packet aimed at ANY fake id (a bar button OR a screen frame, from the
 * current session or one that ended a tick ago) is cancelled regardless — the
 * ids do not exist server-side, so the vanilla server must never see them.
 */
public final class ControlBarListener implements PacketListener {

    private static final long DEBOUNCE_NANOS = 250_000_000L; // per player
    private static final long SEEK_STEP_MILLIS = 10_000L;    // ⏪ / ⏩ step
    private static final String PERMISSION = "minecraftvideo.use";

    private final MinecraftVideoPlugin plugin;
    /** Last accepted click per player (System.nanoTime), from netty threads. */
    private final ConcurrentMap<UUID, Long> lastClickNanos = new ConcurrentHashMap<>();

    public ControlBarListener(MinecraftVideoPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }
        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
        int entityId = packet.getEntityId();
        if (entityId < VirtualScreen.FAKE_ID_BASE) {
            return; // a real entity: none of our business (fast path)
        }
        // Any fake id (screen frame OR bar button, current session or a just
        // ended one) does not exist server-side: cancel BEFORE the session
        // lookup so a click that lands a tick after the session swapped/stopped
        // is still swallowed instead of leaking to the vanilla server.
        event.setCancelled(true);

        PlaybackSession session = plugin.getActiveSession();
        ControlBar bar = session != null ? session.getControlBar() : null;
        ControlBar.Button button = bar != null ? bar.buttonAt(entityId) : null;
        if (button == null) {
            return; // a screen frame or a stale bar id: nothing to act on
        }

        // Collapse the packet burst of one physical click to a single action.
        WrapperPlayClientInteractEntity.InteractAction action = packet.getAction();
        if (action == WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT) {
            return; // a right click also sends INTERACT: act on that one
        }
        if (action == WrapperPlayClientInteractEntity.InteractAction.INTERACT
                && packet.getHand() != InteractionHand.MAIN_HAND) {
            return; // off-hand retry of the same right click
        }

        UUID uuid = event.getUser().getUUID();
        if (uuid == null) {
            return;
        }
        long now = System.nanoTime();
        Long last = lastClickNanos.get(uuid);
        if (last != null && now - last < DEBOUNCE_NANOS) {
            return; // double-click (one channel = ordered events, no race)
        }
        lastClickNanos.put(uuid, now);

        runOnMain(() -> handleClick(session, button, uuid));
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        UUID uuid = event.getUser().getUUID();
        if (uuid != null) {
            lastClickNanos.remove(uuid); // keep the debounce map from growing
        }
    }

    /** Settles one click on the main thread: permission, action, feedback. */
    private void handleClick(PlaybackSession session, ControlBar.Button button, UUID uuid) {
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.hasPermission(PERMISSION)) {
            return; // silently ignore viewers without the /video permission
        }
        if (session.isStopped()) {
            return; // the video ended between the click and this tick
        }
        switch (button) {
            case PLAY_PAUSE -> {
                if (session.isPaused()) {
                    if (session.resume()) {
                        message(player, "Resumed.");
                    }
                } else if (session.pause()) {
                    message(player, "Paused.");
                }
            }
            case REWIND -> seekRelative(session, player, -SEEK_STEP_MILLIS);
            case FORWARD -> seekRelative(session, player, SEEK_STEP_MILLIS);
            case SKIP -> {
                // Do NOT touch the PlaylistManager here beyond reading:
                // stop() -> clearSession() auto-advances the queue by itself.
                boolean hasNext = !plugin.getPlaylist().isEmpty();
                session.stop();
                message(player, hasNext
                        ? "Skipped — starting the next queued video..."
                        : "Skipped (the queue is empty — stopped).");
            }
        }
    }

    /** Relative seek from the current position (same semantics as /video seek). */
    private void seekRelative(PlaybackSession session, Player player, long deltaMillis) {
        long target = Math.max(0, session.getPositionMillis() + deltaMillis);
        if (session.seekTo(target)) {
            message(player, "Seeking to "
                    + PlaybackSession.formatTimestamp(target) + "...");
        }
    }

    private void message(Player player, String text) {
        player.sendMessage("[MinecraftVideo] " + text);
    }

    /** Hops to the main thread; no-ops while the plugin is disabling. */
    private void runOnMain(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (IllegalStateException | IllegalPluginAccessException e) {
            // Disabled between the check and the schedule; the click is moot.
        }
    }
}
