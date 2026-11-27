package ovh.futiax.doommc;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;

// Pendant /doom : clic gauche = tirer, clic droit = utiliser, barre d'outils = armes. Les clics ne
// touchent pas le monde Minecraft (blocs proteges, meme en creatif).
public final class DoomListener implements Listener {

    private final DoomMc plugin;

    public DoomListener(DoomMc plugin) {
        this.plugin = plugin;
    }

    private DoomGames.Controls controls(org.bukkit.entity.Player player) {
        return plugin.getGames().controls(player);
    }

    // Le balancement du bras part a chaque clic gauche, dans le vide comme sur un bloc
    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        DoomGames.Controls c = controls(event.getPlayer());
        if (c != null) {
            DoomGames.fire(c);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        DoomGames.Controls c = controls(event.getPlayer());
        if (c == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            DoomGames.use(c);
        }
    }

    @EventHandler
    public void onHotbar(PlayerItemHeldEvent event) {
        DoomGames.Controls c = controls(event.getPlayer());
        if (c != null) {
            DoomGames.selectWeapon(c, event.getNewSlot());
        }
    }
}
