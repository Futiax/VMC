package ovh.futiax.minecraftvideo;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Adds players who join mid-playback as viewers of the active screen
 * (they receive the frame spawns, item metadata and the last video frame).
 */
public final class JoinListener implements Listener {

    private final MinecraftVideoPlugin plugin;

    public JoinListener(MinecraftVideoPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        PlaybackSession session = plugin.getActiveSession();
        if (session != null) {
            session.addViewer(event.getPlayer());
        }
    }
}
