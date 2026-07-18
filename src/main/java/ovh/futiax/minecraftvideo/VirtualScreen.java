package ovh.futiax.minecraftvideo;

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
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A virtual wall of glow item frames, each holding a virtual filled map.
 *
 * Fully packet-based: fake entity ids and fake map ids, nothing exists
 * server-side and nothing is written to the world.
 *
 * The screen is placed a few blocks in front of the anchor player, vertical,
 * facing back toward the player. Tile (row 0, col 0) is the TOP-LEFT corner
 * from the viewer's point of view. The screen optionally owns a
 * {@link ControlBar} (clickable playback buttons overlaid on its bottom edge)
 * and always owns a {@link SubtitleOverlay} (a fake text display over the lower
 * picture, hidden until a subtitle track is selected); both share the screen's
 * viewer lifecycle: spawned with it, per late joiner, and removed by the same
 * destroy packet.
 */
public final class VirtualScreen {

    /**
     * Fake id spaces. Vanilla allocates entity ids and map ids upward from 0
     * each server start; a busy long-running server can pass 1,000,000, so we
     * start near Integer.MAX_VALUE to stay disjoint from real ids in practice.
     * Any id >= FAKE_ID_BASE is one of ours (screen frames or control bar).
     */
    static final int FAKE_ID_BASE = 2_000_000_000;
    private static final AtomicInteger ENTITY_ID_COUNTER = new AtomicInteger(FAKE_ID_BASE);
    private static final AtomicInteger MAP_ID_COUNTER = new AtomicInteger(FAKE_ID_BASE);

    /** Allocates a fake entity id (one id space shared with the control bar). */
    static int nextEntityId() {
        return ENTITY_ID_COUNTER.getAndIncrement();
    }

    /** Distance (in blocks) between the player and the screen plane. */
    private static final int SCREEN_DISTANCE = 4;

    /**
     * Item-frame entity-data index for the contained item on 1.21.x: base
     * Entity uses indices 0-7, then ItemFrame adds 8 = facing (Direction),
     * 9 = item (ItemStack), 10 = rotation. The facing is already set via the
     * spawn packet's data field, so we only write the item (index 9) here.
     * (Index 8 holds a Direction; writing an ItemStack there makes the 1.21.8
     * client reject the metadata packet with a protocol error.)
     */
    private static final int ITEM_FRAME_ITEM_INDEX = 9;
    private static final int ENTITY_FLAGS_INDEX = 0;
    private static final byte FLAG_INVISIBLE = 0x20;

    private final int width;
    private final int height;
    private final int[] entityIds;      // tile index -> fake entity id
    private final int[] mapIds;         // tile index -> fake map id
    private final UUID[] entityUuids;   // tile index -> stable fake uuid
    private final Vector3d[] positions; // tile index -> frame block center
    private final float frameYaw;       // spawn packet yaw for the facing
    private final int frameDirectionData; // spawn packet data field = direction id
    private final int rightUnitX;       // viewer-right unit vector (X), for stereo anchors
    private final int rightUnitZ;       // viewer-right unit vector (Z), for stereo anchors
    private final int audienceUnitX;    // screen->audience unit vector (X), for rear speakers
    private final int audienceUnitZ;    // screen->audience unit vector (Z), for rear speakers

    /** Clickable buttons under the screen; {@code null} when disabled. */
    private final ControlBar controlBar;
    /** Subtitle text display over the lower picture; hidden until a track is on. */
    private final SubtitleOverlay subtitleOverlay;

    private final Set<Player> viewers = ConcurrentHashMap.newKeySet();
    private volatile byte[][] lastFrame;

    /**
     * @param anchor         the anchor player's location (captured on the main
     *                       thread); the screen is derived from its block
     *                       position and yaw
     * @param width          screen width in map tiles
     * @param height         screen height in map tiles
     * @param withControlBar whether to attach the clickable control bar
     * @param subtitleSettings geometry (scale/height/depth) of the subtitle overlay
     */
    public VirtualScreen(Location anchor, int width, int height, boolean withControlBar,
                         SubtitleSettings subtitleSettings) {
        this.width = width;
        this.height = height;

        int tiles = width * height;
        this.entityIds = new int[tiles];
        this.mapIds = new int[tiles];
        this.entityUuids = new UUID[tiles];
        this.positions = new Vector3d[tiles];
        for (int i = 0; i < tiles; i++) {
            entityIds[i] = nextEntityId();
            mapIds[i] = MAP_ID_COUNTER.getAndIncrement();
            entityUuids[i] = UUID.randomUUID();
        }

        // Direction the player looks at (horizontal), and the direction the
        // frames face: back toward the player.
        BlockFace playerDirection = yawToFace(anchor.getYaw());
        BlockFace facing = playerDirection.getOppositeFace();

        // Spawn packet fields for the four supported facings.
        // Item frame direction ids: DOWN=0 UP=1 NORTH=2 SOUTH=3 WEST=4 EAST=5.
        // Yaw convention: SOUTH=0, WEST=90, NORTH=180, EAST=270.
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

        // "Right" direction of the screen from the VIEWER's point of view.
        // The viewer stands on the `facing` side and looks along -facing:
        //   frames face SOUTH -> viewer looks north -> viewer right = east (+X)
        //   frames face NORTH -> viewer looks south -> viewer right = west (-X)
        //   frames face EAST  -> viewer looks west  -> viewer right = north (-Z)
        //   frames face WEST  -> viewer looks east  -> viewer right = south (+Z)
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
        // Frames face back toward the player, i.e. toward the audience.
        this.audienceUnitX = facing.getModX();
        this.audienceUnitZ = facing.getModZ();

        // Screen plane SCREEN_DISTANCE blocks in front of the player,
        // horizontally centered on the line of sight, bottom row at foot level.
        int centerX = anchor.getBlockX() + playerDirection.getModX() * SCREEN_DISTANCE;
        int centerZ = anchor.getBlockZ() + playerDirection.getModZ() * SCREEN_DISTANCE;
        int baseY = anchor.getBlockY();
        int originX = centerX - rightX * ((width - 1) / 2);
        int originZ = centerZ - rightZ * ((width - 1) / 2);

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int x = originX + rightX * col;
                int z = originZ + rightZ * col;
                int y = baseY + (height - 1 - row); // row 0 = top
                positions[row * width + col] = new Vector3d(x + 0.5, y + 0.5, z + 0.5);
            }
        }

        // Control bar overlaid on the bottom edge and subtitle overlay over the
        // lower picture (both need the tile positions above for their geometry).
        double[] sub = getSubAnchor();       // (center x, bottom tile center y, center z)
        double bottomEdgeY = sub[1] - 0.5;   // bottom edge = bottom tile center - 0.5
        if (withControlBar) {
            this.controlBar = new ControlBar(sub[0], bottomEdgeY, sub[2],
                    rightX, rightZ, audienceUnitX, audienceUnitZ);
        } else {
            this.controlBar = null;
        }
        // Always spawned (hidden) so subtitles can be toggled on at any time and
        // late joiners get the display; lifted to clear the bar when present.
        this.subtitleOverlay = new SubtitleOverlay(sub[0], bottomEdgeY, sub[2],
                audienceUnitX, audienceUnitZ, withControlBar, subtitleSettings);
    }

    /** Spawns the screen for the given players and registers them as viewers. */
    public void spawn(Collection<? extends Player> players) {
        for (Player player : players) {
            addViewer(player);
        }
    }

    /**
     * Registers a viewer and (re-)sends the frame spawn packets, the item
     * metadata and the last video frame (if any) to that player.
     */
    public void addViewer(Player player) {
        if (!player.isOnline() || !viewers.add(player)) {
            return;
        }
        sendSpawnAndMetadata(player);
        if (controlBar != null) {
            controlBar.spawnFor(player);
        }
        subtitleOverlay.spawnFor(player); // hidden unless a cue is currently set
        byte[][] frame = lastFrame;
        if (frame != null) {
            sendMapData(player, frame);
        }
    }

    /**
     * Sends one full 128x128 map-data packet per tile to every current viewer
     * and remembers the frame for late joiners.
     *
     * Safe to call from an async thread: packetevents packet sending is
     * thread-safe (it writes directly to the player's netty channel).
     */
    public void sendFrame(byte[][] tiles) {
        byte[][] copiedTiles = new byte[tiles.length][];
        for (int i = 0; i < tiles.length; i++) {
            copiedTiles[i] = java.util.Arrays.copyOf(tiles[i], tiles[i].length);
        }
        lastFrame = copiedTiles;
        viewers.removeIf(viewer -> !viewer.isOnline());
        for (Player viewer : viewers) {
            sendMapData(viewer, copiedTiles);
        }
    }

    /** Sends entity-remove packets to all viewers and forgets them. */
    public void destroy() {
        // One remove packet per viewer covering the frames, the control bar and
        // the subtitle overlay (all fake ids the screen spawned).
        List<Integer> idList = new ArrayList<>(entityIds.length + 8);
        for (int id : entityIds) {
            idList.add(id);
        }
        if (controlBar != null) {
            for (int id : controlBar.getEntityIds()) {
                idList.add(id);
            }
        }
        idList.add(subtitleOverlay.getEntityId());
        int[] ids = new int[idList.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = idList.get(i);
        }
        for (Player viewer : viewers) {
            if (viewer.isOnline()) {
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                        new WrapperPlayServerDestroyEntities(ids.clone()));
            }
        }
        viewers.clear();
    }

    private void sendSpawnAndMetadata(Player player) {
        for (int i = 0; i < entityIds.length; i++) {
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                    entityIds[i],
                    Optional.of(entityUuids[i]),
                    EntityTypes.GLOW_ITEM_FRAME,
                    positions[i],
                    0f,                 // pitch (vertical wall frame)
                    frameYaw,           // yaw
                    frameYaw,           // head yaw
                    frameDirectionData, // data field = frame direction
                    Optional.empty());  // no velocity
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, spawn);

            ItemStack map = ItemStack.builder()
                    .type(ItemTypes.FILLED_MAP)
                    .amount(1)
                    .component(ComponentTypes.MAP_ID, mapIds[i])
                    .build();

            List<EntityData<?>> data = new ArrayList<>(2);
            // Hide the frame itself so only the map surface is visible.
            data.add(new EntityData<>(ENTITY_FLAGS_INDEX, EntityDataTypes.BYTE, FLAG_INVISIBLE));
            data.add(new EntityData<>(ITEM_FRAME_ITEM_INDEX, EntityDataTypes.ITEMSTACK, map));
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerEntityMetadata(entityIds[i], data));
        }
    }

    private void sendMapData(Player player, byte[][] tiles) {
        int count = Math.min(tiles.length, mapIds.length);
        for (int i = 0; i < count; i++) {
            WrapperPlayServerMapData mapData = new WrapperPlayServerMapData(
                    mapIds[i],
                    (byte) 0,                 // scale: fully zoomed in
                    false,                    // trackingPosition
                    true,                     // locked
                    Collections.emptyList(),  // no decorations
                    128, 128,                 // columns, rows: full update
                    0, 0,                     // x, z offsets
                    tiles[i]);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, mapData);
        }
    }

    /** Maps a player yaw to the horizontal BlockFace the player looks at. */
    private static BlockFace yawToFace(float yaw) {
        float normalized = ((yaw % 360f) + 360f) % 360f;
        if (normalized >= 315f || normalized < 45f) {
            return BlockFace.SOUTH;
        } else if (normalized < 135f) {
            return BlockFace.WEST;
        } else if (normalized < 225f) {
            return BlockFace.NORTH;
        } else {
            return BlockFace.EAST;
        }
    }

    /** The clickable control bar under the screen, or {@code null} if disabled. */
    public ControlBar getControlBar() {
        return controlBar;
    }

    /**
     * Shows {@code text} on the subtitle overlay for every current viewer (a
     * {@code null}/blank hides it). Idempotent per cue: unchanged text sends
     * nothing. Called from the playback thread; packetevents sending is
     * thread-safe. Offline viewers are pruned lazily by {@link #sendFrame}.
     */
    public void setSubtitle(String text) {
        subtitleOverlay.setText(text, viewers);
    }

    /** Hides the subtitle overlay for every current viewer. */
    public void clearSubtitle() {
        subtitleOverlay.clear(viewers);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * World-space center of the screen (average of all tile centers), used to
     * anchor spatialised audio. Returns {@code {x, y, z}}.
     */
    public double[] getCenter() {
        double x = 0, y = 0, z = 0;
        for (Vector3d p : positions) {
            x += p.getX();
            y += p.getY();
            z += p.getZ();
        }
        int n = positions.length;
        return new double[] { x / n, y / n, z / n };
    }

    /**
     * World anchor for the LEFT speaker of a stereo pair: the screen center
     * shifted to the screen's left edge along the viewer-right axis. Returns
     * {@code {x, y, z}}.
     */
    public double[] getLeftAnchor() {
        double[] c = getCenter();
        double half = width / 2.0;
        return new double[] { c[0] - rightUnitX * half, c[1], c[2] - rightUnitZ * half };
    }

    /** World anchor for the RIGHT speaker of a stereo pair: the right edge. */
    public double[] getRightAnchor() {
        double[] c = getCenter();
        double half = width / 2.0;
        return new double[] { c[0] + rightUnitX * half, c[1], c[2] + rightUnitZ * half };
    }

    /**
     * World anchor for the surround REAR-LEFT speaker: the left-edge anchor
     * pushed {@code depth} blocks from the screen plane toward the audience.
     */
    public double[] getRearLeftAnchor(double depth) {
        double[] a = getLeftAnchor();
        return new double[] { a[0] + audienceUnitX * depth, a[1], a[2] + audienceUnitZ * depth };
    }

    /** World anchor for the surround REAR-RIGHT speaker (see {@link #getRearLeftAnchor}). */
    public double[] getRearRightAnchor(double depth) {
        double[] a = getRightAnchor();
        return new double[] { a[0] + audienceUnitX * depth, a[1], a[2] + audienceUnitZ * depth };
    }

    /**
     * World anchor for the surround SUBWOOFER: horizontally at the screen
     * center, vertically at the screen's bottom row — a sub sitting at the
     * base of the screen, like in a cinema.
     */
    public double[] getSubAnchor() {
        double[] c = getCenter();
        double minY = Double.MAX_VALUE;
        for (Vector3d p : positions) {
            minY = Math.min(minY, p.getY());
        }
        return new double[] { c[0], minY, c[2] };
    }
}
