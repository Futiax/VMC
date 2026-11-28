package ovh.futiax.mcdata.poc;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

// Registre des POCs. La commande /mcdata s'appuie dessus pour lister, demarrer, arreter.
public final class PocRegistry {

    private final Map<String, Poc> pocs = new LinkedHashMap<>();

    public void register(Poc poc) {
        pocs.put(poc.name(), poc);
    }

    public Poc get(String name) {
        return pocs.get(name);
    }

    public Collection<Poc> all() {
        return Collections.unmodifiableCollection(pocs.values());
    }

    public void onPlayerJoin(Player player) {
        for (Poc poc : pocs.values()) {
            poc.onPlayerJoin(player);
        }
    }

    public void onPlayerQuit(Player player) {
        for (Poc poc : pocs.values()) {
            poc.onPlayerQuit(player);
        }
    }

    public void stopAll(Player player) {
        for (Poc poc : pocs.values()) {
            if (poc.isRunning(player)) {
                poc.stop(player);
            }
        }
    }
}
