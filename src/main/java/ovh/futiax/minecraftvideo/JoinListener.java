package ovh.futiax.minecraftvideo;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

// Un joueur qui arrive en cours de lecture devient viewer de l'ecran actif : il recoit les
// spawns de frames, les metadata d'item et la derniere image envoyee. Le post-effect suit :
// remis a zero au join (au cas ou il aurait survecu a une coupure), retire au quit pendant
// que la commande peut encore viser le joueur.
public final class JoinListener implements Listener {

    private final MinecraftVideoPlugin plugin;

    public JoinListener(MinecraftVideoPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Le pack d'abord : les post effects qu'il definit doivent exister cote client avant
        // qu'on pousse un /posteffect qui s'y refere.
        plugin.getResourcePack().send(event.getPlayer());
        plugin.getPostEffects().reset(event.getPlayer());
        PlaybackSession session = plugin.getActiveSession();
        if (session != null) {
            session.addViewer(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getPostEffects().clear(event.getPlayer());
    }
}
