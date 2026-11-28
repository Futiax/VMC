package ovh.futiax.mcdata;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

// Au join : pousse le resource pack, reset les post-effects persistants, et relance les POCs
// actifs sur ce joueur (s'il se reconnecte en cours de session).
// Au quit : clear le post-effect et arrete le channel pour ce joueur.
public final class JoinListener implements Listener {

    private final McData plugin;

    public JoinListener(McData plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Le pack d'abord : les post effects qu'il definit doivent exister cote client avant
        // qu'on pousse un /posteffect qui s'y refere.
        plugin.getResourcePack().send(event.getPlayer());
        plugin.getPostEffects().reset(event.getPlayer());
        plugin.getPocRegistry().onPlayerJoin(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getPocRegistry().onPlayerQuit(event.getPlayer());
        plugin.getChannel().stop(event.getPlayer());
        plugin.getPostEffects().clear(event.getPlayer());
    }
}
