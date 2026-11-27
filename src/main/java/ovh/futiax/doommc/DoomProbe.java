package ovh.futiax.doommc;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

// La balise : canal serveur -> shader sans detection. Quatre cadres invisibles autour du joueur
// portent la meme map. Le text.vsh du pack la reconnait a ses 4 premiers texels et deplace son quad
// dans le coin bas-gauche de l'ecran ; text.fsh y recopie la map pixel pour pixel, plus la matrice
// ModelViewMat (rotation de la camera). Les post effects lisent ces pixels a des positions fixes.
// Quatre cadres : le client ne dessine une entite que si elle est dans son champ, et au moins un des
// quatre l'est tant que le joueur regarde a peu pres a l'horizontale. Main thread seulement.
//
// /doomprobe : l'essai du canal (doom_probe affiche le compteur, l'angle lu cote client et celui du
// serveur). /doom passe par DoomGames, qui envoie la trame du moteur dans la balise de chaque joueur.
final class DoomProbe {

    // Ids de couleur de map (base * 4 + nuance). gen_probe.py relit ces deux tableaux pour ecrire
    // les RGB correspondants dans les shaders : les garder sur une ligne chacun.
    static final int[] SIGNATURE = {73, 77, 81, 85};
    static final int[] NIBBLES = {10, 14, 18, 22, 26, 30, 34, 38, 42, 46, 50, 54, 58, 62, 66, 70};

    static final String EFFECT = "minecraft:doom_probe";
    static final String VIEW_EFFECT = "minecraft:doom_view";

    private static final AtomicInteger IDS = new AtomicInteger(2_120_000_000); // disjoint de la balise MCDATA (2_100_000_000) et des ecrans
    private static final byte FLAG_INVISIBLE = 0x20;
    // voisin (dx, dz), direction d'item frame tournee vers le joueur, yaw du paquet de spawn
    private static final int[][] RING = {{0, -1, 3, 0}, {0, 1, 2, 180}, {-1, 0, 5, 270}, {1, 0, 4, 90}};

    record Beacon(int mapId, int[] entityIds) {}

    private record Probe(Beacon beacon, BukkitTask task) {}

    private final DoomMc plugin;
    private final Map<UUID, Probe> probes = new HashMap<>();

    DoomProbe(DoomMc plugin) {
        this.plugin = plugin;
    }

    // Les quatre cadres autour du bloc des yeux du joueur
    static Beacon attach(Player player) {
        int mapId = IDS.getAndIncrement();
        int[] entityIds = new int[RING.length];
        Location eye = player.getEyeLocation();
        int bx = eye.getBlockX(), by = eye.getBlockY(), bz = eye.getBlockZ();
        ItemStack map = ItemStack.builder().type(ItemTypes.FILLED_MAP).amount(1)
                .component(ComponentTypes.MAP_ID, mapId).build();
        for (int i = 0; i < RING.length; i++) {
            entityIds[i] = IDS.getAndIncrement();
            int[] r = RING[i];
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerSpawnEntity(
                    entityIds[i], Optional.of(UUID.randomUUID()), EntityTypes.GLOW_ITEM_FRAME,
                    new Vector3d(bx + r[0] + 0.5, by + 0.5, bz + r[1] + 0.5),
                    0f, r[3], r[3], r[2], Optional.empty()));
            List<EntityData<?>> data = List.of(
                    new EntityData<>(MetadataIndices.ENTITY_FLAGS, EntityDataTypes.BYTE, FLAG_INVISIBLE),
                    new EntityData<>(MetadataIndices.ITEM_FRAME_ITEM, EntityDataTypes.ITEMSTACK, map));
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerEntityMetadata(entityIds[i], data));
        }
        return new Beacon(mapId, entityIds);
    }

    // Une image de la balise : compteur, regard du serveur, puis la trame du moteur (payload)
    static void send(Player player, Beacon beacon, int counter, int[] payload) {
        Location loc = player.getLocation();
        byte[] pixels = encode(counter, loc.getYaw(), loc.getPitch(), payload);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerMapData(
                beacon.mapId(), (byte) 0, false, true, Collections.emptyList(), 128, 128, 0, 0, pixels));
    }

    static void detach(Player player, Beacon beacon) {
        if (player.isOnline()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerDestroyEntities(beacon.entityIds()));
        }
    }

    boolean isRunning(Player player) {
        return probes.containsKey(player.getUniqueId());
    }

    void start(Player player) {
        stop(player);
        Beacon beacon = attach(player);
        int[] counter = {0};
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin.host(), () -> {
            if (!player.isOnline()) {
                stop(player);
                return;
            }
            send(player, beacon, counter[0]++, new int[0]);
        }, 0L, 1L);
        probes.put(player.getUniqueId(), new Probe(beacon, task));
        plugin.getPostEffects().apply(player, EFFECT);
    }

    void stop(Player player) {
        Probe probe = probes.remove(player.getUniqueId());
        if (probe == null) {
            return;
        }
        probe.task().cancel();
        detach(player, probe.beacon());
        if (player.isOnline()) {
            plugin.getPostEffects().clear(player);
        }
    }

    void stopAll() {
        for (UUID id : List.copyOf(probes.keySet())) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                stop(player);
            } else {
                probes.remove(id).task().cancel();
            }
        }
    }

    // Texels 0-3 de la ligne 0 : la signature. Puis des valeurs de 16 bits, 4 demi-octets chacune,
    // poids faible d'abord : 0-5 = compteur, yaw et pitch du serveur en centiemes de degre (int32
    // signes, mot bas puis haut) ; 6 et plus = la trame du moteur (payload). Le reste : 0.
    static byte[] encode(int counter, float yaw, float pitch, int[] payload) {
        byte[] pixels = new byte[128 * 128];
        java.util.Arrays.fill(pixels, (byte) NIBBLES[0]);
        for (int i = 0; i < SIGNATURE.length; i++) {
            pixels[i] = (byte) SIGNATURE[i];
        }
        int normalizedYaw = Math.round((((yaw % 360f) + 540f) % 360f - 180f) * 100f);
        int[] words = {counter, normalizedYaw, Math.round(pitch * 100f)};
        int at = SIGNATURE.length;
        for (int word : words) {
            for (int n = 0; n < 8; n++) {
                pixels[at++] = (byte) NIBBLES[(word >>> (4 * n)) & 15];
            }
        }
        for (int value : payload) {
            if (at + 4 > pixels.length) {
                break;
            }
            for (int n = 0; n < 4; n++) {
                pixels[at++] = (byte) NIBBLES[(value >>> (4 * n)) & 15];
            }
        }
        return pixels;
    }
}
