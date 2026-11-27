package ovh.futiax.doommc;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

// Le pack au join, puis les post effects remis a zero ; au quit, le joueur quitte sa partie (ou l'essai
// /doomprobe) et l'effet est retire pendant que la commande peut encore viser le joueur.
public final class JoinListener implements Listener {

    private final DoomMc plugin;

    public JoinListener(DoomMc plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Le pack est pousse par le JoinListener de MCDATA, enregistre avant celui-ci.
        plugin.getPostEffects().reset(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getGames().leave(event.getPlayer());
        plugin.getDoomProbe().stop(event.getPlayer());
        plugin.getPostEffects().clear(event.getPlayer());
    }
}
