package ovh.futiax.mcdata.channel;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// Blocs VIRTUELS (client-only) : envoyes par paquets block-change, jamais ecrits dans le monde.
// Sert a poser des "cushions" devant un ecran pour montrer aux joueurs ou se placer.
//
// Chaque joueur a sa propre couche de blocs virtuels : on garde en memoire les positions
// ecrasees pour pouvoir restaurer le vrai bloc du monde (lu via World#getType) au retrait.
// Comme tout est par paquet, un chunk reload cote serveur ne restaure rien tout seul :
// on restaure explicitement via clear().
public final class VirtualBlocks {

    // joueur -> (position encodee -> vrai block state du monde avant ecrasement)
    private final Map<UUID, Map<Long, WrappedBlockState>> layers = new HashMap<>();

    // Ecrase un bloc cote client pour ce joueur. Le vrai etat du monde est memorise pour
    // restauration. Appelable hors main thread (envoi de paquet thread-safe).
    public void set(Player player, int x, int y, int z, WrappedBlockState state) {
        Map<Long, WrappedBlockState> layer = layers.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        long key = key(x, y, z);
        layer.computeIfAbsent(key, k -> {
            // lit le vrai bloc dans le monde (snapshot au moment de l'ecrasement)
            World world = player.getWorld();
            org.bukkit.block.Block b = world.getBlockAt(x, y, z);
            return toWrapped(b.getBlockData());
        });
        PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                new WrapperPlayServerBlockChange(new Vector3i(x, y, z), state));
    }

    // Restaure le vrai bloc du monde pour toutes les positions de la couche de ce joueur.
    public void clear(Player player) {
        Map<Long, WrappedBlockState> layer = layers.remove(player.getUniqueId());
        if (layer == null || !player.isOnline()) {
            return;
        }
        World world = player.getWorld();
        for (Map.Entry<Long, WrappedBlockState> e : layer.entrySet()) {
            int x = (int) (e.getKey() >> 38);
            int y = (int) ((e.getKey() >> 26) & 0xFFF);
            int z = (int) (e.getKey() << 38 >> 38);
            // on relit le bloc actuel du monde au cas ou il aurait change entre-temps
            org.bukkit.block.Block b = world.getBlockAt(x, y, z);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerBlockChange(new Vector3i(x, y, z), toWrapped(b.getBlockData())));
        }
    }

    public void clearAll() {
        for (UUID id : layers.keySet().toArray(new UUID[0])) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                clear(p);
            } else {
                layers.remove(id);
            }
        }
    }

    // Pose une rangee de "cushions" (blocs virtuels) devant un ecran, au sol, centree sur
    // l'ecran. `count` blocs de large, a `distance` blocs devant le centre de l'ecran,
    // a la hauteur du bas de l'ecran. Renvoie les positions posees.
    public void poseCushions(Player player, Location screenCenter, float screenYaw,
                            int count, double distance, int baseY, WrappedBlockState cushion) {
        // direction "avant" de l'ecran (vers le public) : opposee a la direction des frames
        float yawRad = (float) Math.toRadians(screenYaw);
        // convention Minecraft : yaw 0 = sud (+Z), 90 = ouest (-X)
        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);
        // vecteur "droite" de l'ecran (perpendiculaire)
        double rx = Math.cos(yawRad);
        double rz = Math.sin(yawRad);

        double cx = screenCenter.getX() + fx * distance;
        double cz = screenCenter.getZ() + fz * distance;
        int half = count / 2;
        for (int i = -half; i < count - half; i++) {
            int x = (int) Math.floor(cx + rx * i);
            int z = (int) Math.floor(cz + rz * i);
            set(player, x, baseY, z, cushion);
            // un deuxieme étage pour visibilité
            set(player, x, baseY + 1, z, cushion);
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF);
    }

    // Convertit un Bukkit BlockData en WrappedBlockState PacketEvents via le parseur de string.
    private static WrappedBlockState toWrapped(org.bukkit.block.data.BlockData data) {
        return WrappedBlockState.getByString(data.getAsString());
    }
}
