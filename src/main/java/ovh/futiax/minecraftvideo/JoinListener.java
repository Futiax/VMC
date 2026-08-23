package ovh.futiax.minecraftvideo;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

// Un joueur qui arrive en cours de lecture devient viewer de l'ecran actif : il recoit les
// spawns de frames, les metadata d'item et la derniere image envoyee.
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
