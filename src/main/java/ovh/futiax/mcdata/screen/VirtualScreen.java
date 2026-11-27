package ovh.futiax.mcdata.screen;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import ovh.futiax.mcdata.MetadataIndices;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// Un mur virtuel de glow item frames, chacune tenant une map remplie virtuelle.
// 100% paquets : fake entity ids et fake map ids, rien n'existe cote serveur.
// Version light de VirtualScreen de VMC : pas de control bar ni sous-titres, juste le mur.
public final class VirtualScreen {

    static final int FAKE_ID_BASE = 2_000_000_000;
    private static final AtomicInteger ENTITY_ID_COUNTER = new AtomicInteger(FAKE_ID_BASE);
    private static final AtomicInteger MAP_ID_COUNTER = new AtomicInteger(FAKE_ID_BASE);
    private static final byte FLAG_INVISIBLE = 0x20;

    private final int width;
    private final int height;
    private final int[] entityIds;
    private final int[] mapIds;
    private final UUID[] entityUuids;
    private final Vector3d[] positions;
    private final float frameYaw;
    private final int frameDirectionData;
    private final int rightUnitX;
    private final int rightUnitZ;
    private final UUID worldId;
    private final double centerX, centerY, centerZ;
    private final Set<Player> viewers = ConcurrentHashMap.newKeySet();
    private volatile byte[][] lastFrame;

    public VirtualScreen(Location anchor, int width, int height, double distance) {
        this.width = width;
        this.height = height;
        this.worldId = anchor.getWorld().getUID();

        int tiles = width * height;
        this.entityIds = new int[tiles];
        this.mapIds = new int[tiles];
        this.entityUuids = new UUID[tiles];
        this.positions = new Vector3d[tiles];
        for (int i = 0; i < tiles; i++) {
            entityIds[i] = ENTITY_ID_COUNTER.getAndIncrement();
            mapIds[i] = MAP_ID_COUNTER.getAndIncrement();
            entityUuids[i] = UUID.randomUUID();
        }

        BlockFace playerDirection = yawToFace(anchor.getYaw());
        BlockFace facing = playerDirection.getOppositeFace();

        this.frameDirectionData = switch (facing) {
            case NORTH -> 2;
            case SOUTH -> 3;
            case WEST -> 4;
            case EAST -> 5;
            default -> throw new IllegalStateException("unsupported facing " + facing);
        };
        this.frameYaw = switch (facing) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> 270f;
            default -> 0f;
        };
        int rightX = switch (facing) {
            case SOUTH -> 1;
            case NORTH -> -1;
            default -> 0;
        };
        int rightZ = switch (facing) {
            case EAST -> -1;
            case WEST -> 1;
            default -> 0;
        };
        this.rightUnitX = rightX;
        this.rightUnitZ = rightZ;

        boolean alongX = playerDirection.getModX() != 0;
        int forward = playerDirection.getModX() + playerDirection.getModZ();
        double along = alongX ? anchor.getX() : anchor.getZ();
        double toEdge = along - Math.floor(along);
        if (forward > 0) toEdge = 1 - toEdge;
        int blocks = Math.max(1, (int) Math.round(distance - toEdge + 0.0625));
        int plane = (int) Math.floor(along) + forward * blocks;
        double side = alongX ? anchor.getZ() : anchor.getX();
        int first = (int) Math.round(side - (rightX + rightZ) * (width - 1) / 2.0 - 0.5);
        int originX = alongX ? plane : first;
        int originZ = alongX ? first : plane;
        int baseY = anchor.getBlockY();

        this.centerX = originX + rightX * (width - 1) / 2.0 + 0.5;
        this.centerY = baseY + (height - 1) / 2.0 + 0.5;
        this.centerZ = originZ + rightZ * (width - 1) / 2.0 + 0.5;

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int x = originX + rightX * col;
                int z = originZ + rightZ * col;
                int y = baseY + (height - 1 - row);
                positions[row * width + col] = new Vector3d(x + 0.5, y + 0.5, z + 0.5);
            }
        }
    }

    public void spawn(Collection<? extends Player> players) {
        for (Player p : players) addViewer(p);
    }

    public void addViewer(Player player) {
        if (!player.isOnline() || !viewers.add(player)) return;
        sendSpawnAndMetadata(player);
        byte[][] frame = lastFrame;
        if (frame != null) sendBundle(player, frame, null);
    }

    public void removeViewer(Player player) {
        if (!viewers.remove(player)) return;
        if (player.isOnline()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerDestroyEntities(allEntityIds()));
        }
    }

    public void sendFrame(byte[][] tiles) {
        lastFrame = tiles;
        viewers.removeIf(v -> !v.isOnline());
        int count = Math.min(tiles.length, mapIds.length);
        boolean[] changed = new boolean[count];
        byte[][] previous = lastFrame;
        // diff contre la frame precedente (on vient de poser lastFrame, on garde une copie)
        for (int i = 0; i < count; i++) {
            changed[i] = previous == null || !Arrays.equals(tiles[i], previous[i]);
        }
        for (Player viewer : viewers) {
            sendBundle(viewer, tiles, changed);
        }
    }

    private void sendBundle(Player viewer, byte[][] tiles, boolean[] changed) {
        var players = PacketEvents.getAPI().getPlayerManager();
        int count = Math.min(tiles.length, mapIds.length);
        boolean opened = false;
        for (int i = 0; i < count; i++) {
            if (changed != null && !changed[i]) continue;
            if (!opened) {
                players.writePacket(viewer, new WrapperPlayServerBundle());
                opened = true;
            }
            players.writePacket(viewer, mapPacket(i, tiles[i]));
        }
        if (opened) {
            players.sendPacket(viewer, new WrapperPlayServerBundle());
        }
    }

    public void destroy() {
        int[] ids = allEntityIds();
        for (Player viewer : viewers) {
            if (viewer.isOnline()) {
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                        new WrapperPlayServerDestroyEntities(ids.clone()));
            }
        }
        viewers.clear();
    }

    private int[] allEntityIds() {
        return entityIds.clone();
    }

    private void sendSpawnAndMetadata(Player player) {
        for (int i = 0; i < entityIds.length; i++) {
            var spawn = new WrapperPlayServerSpawnEntity(
                    entityIds[i], Optional.of(entityUuids[i]), EntityTypes.GLOW_ITEM_FRAME,
                    positions[i], 0f, frameYaw, frameYaw, frameDirectionData, Optional.empty());
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, spawn);
            ItemStack map = ItemStack.builder().type(ItemTypes.FILLED_MAP).amount(1)
                    .component(ComponentTypes.MAP_ID, mapIds[i]).build();
            List<EntityData<?>> data = List.of(
                    new EntityData<>(MetadataIndices.ENTITY_FLAGS, EntityDataTypes.BYTE, FLAG_INVISIBLE),
                    new EntityData<>(MetadataIndices.ITEM_FRAME_ITEM, EntityDataTypes.ITEMSTACK, map));
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerEntityMetadata(entityIds[i], data));
        }
    }

    private WrapperPlayServerMapData mapPacket(int index, byte[] tile) {
        return new WrapperPlayServerMapData(mapIds[index], (byte) 0, false, true,
                Collections.emptyList(), 128, 128, 0, 0, tile);
    }

    private static BlockFace yawToFace(float yaw) {
        float n = ((yaw % 360f) + 360f) % 360f;
        if (n >= 315f || n < 45f) return BlockFace.SOUTH;
        if (n < 135f) return BlockFace.WEST;
        if (n < 225f) return BlockFace.NORTH;
        return BlockFace.EAST;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public double getCenterX() { return centerX; }
    public double getCenterY() { return centerY; }
    public double getCenterZ() { return centerZ; }
    public float getFrameYaw() { return frameYaw; }
    public int getRightUnitX() { return rightUnitX; }
    public int getRightUnitZ() { return rightUnitZ; }
    public int getBaseY() { return (int) Math.floor(centerY - (height - 1) / 2.0); }
}
