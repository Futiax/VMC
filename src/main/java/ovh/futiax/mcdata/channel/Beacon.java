package ovh.futiax.mcdata.channel;

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

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

// La balise : canal serveur -> shader sans detection. Quatre cadres invisibles autour du joueur
// portent la meme map. Le text.vsh du pack la reconnait a ses 4 premiers texels et deplace son
// quad dans le coin bas-gauche de l'ecran ; text.fsh y recopie la map pixel pour pixel, plus la
// matrice ModelViewMat (rotation de la camera). Les post effects lisent ces pixels a des positions
// fixes. Quatre cadres : le client ne dessine une entite que si elle est dans son champ, et au
// moins un des quatre l'est tant que le joueur regarde a peu pres a l'horizontale.
public final class Beacon {

    private static final AtomicInteger IDS = new AtomicInteger(2_100_000_000);
    private static final byte FLAG_INVISIBLE = 0x20;
    // voisin (dx, dz), direction d'item frame tournee vers le joueur, yaw du paquet de spawn
    private static final int[][] RING = {{0, -1, 3, 0}, {0, 1, 2, 180}, {-1, 0, 5, 270}, {1, 0, 4, 90}};

    public record Handle(int mapId, int[] entityIds) {}

    // Les quatre cadres autour du bloc des yeux du joueur.
    public static Handle attach(Player player) {
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
                    new EntityData<>(ovh.futiax.mcdata.MetadataIndices.ENTITY_FLAGS, EntityDataTypes.BYTE, FLAG_INVISIBLE),
                    new EntityData<>(ovh.futiax.mcdata.MetadataIndices.ITEM_FRAME_ITEM, EntityDataTypes.ITEMSTACK, map));
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerEntityMetadata(entityIds[i], data));
        }
        return new Handle(mapId, entityIds);
    }

    // Envoie une image de la balise (map-data complet 128x128).
    public static void send(Player player, Handle beacon, byte[] pixels) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerMapData(
                beacon.mapId(), (byte) 0, false, true, Collections.emptyList(), 128, 128, 0, 0, pixels));
    }

    public static void detach(Player player, Handle beacon) {
        if (player.isOnline()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerDestroyEntities(beacon.entityIds()));
        }
    }
}
