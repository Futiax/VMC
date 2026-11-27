package ovh.futiax.mcdata.poc;

import org.bukkit.entity.Player;
import ovh.futiax.mcdata.McData;
import ovh.futiax.mcdata.channel.BeaconChannel;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// POC "probe" : test du canal serveur -> shader. Envoie un compteur croissant dans la balise ;
// le post-effect mcdata_probe affiche le compteur et l'angle lu cote client en bas a gauche de
// l'ecran. Equivalent du /doomprobe de DOOMMC, isole comme POC du plugin universel.
public final class ProbePoc implements Poc {

    public static final String EFFECT = "minecraft:mcdata_probe";

    private final McData plugin;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    public ProbePoc(McData plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "probe";
    }

    @Override
    public String postEffect() {
        return EFFECT;
    }

    @Override
    public String description() {
        return "Test du canal map-data : compteur + angles lus par le shader (coin bas-gauche).";
    }

    @Override
    public void start(Player player) {
        if (!running.add(player.getUniqueId())) {
            return;
        }
        BeaconChannel channel = plugin.getChannel();
        channel.addFeed(player, name(), counter -> new int[]{counter & 0xFFFF, (counter >> 16) & 0xFFFF});
        plugin.getPostEffects().apply(player, EFFECT);
        player.sendMessage("[MCDATA] probe on : compteur et angles en bas a gauche. /mcdata probe off pour arreter.");
    }

    @Override
    public void stop(Player player) {
        if (!running.remove(player.getUniqueId())) {
            return;
        }
        plugin.getChannel().removeFeed(player, name());
        plugin.getPostEffects().clear(player);
        player.sendMessage("[MCDATA] probe off.");
    }

    @Override
    public boolean isRunning(Player player) {
        return running.contains(player.getUniqueId());
    }
}
