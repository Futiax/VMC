package ovh.futiax.minecraftvideo;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A single fake TEXT_DISPLAY entity floating over the lower part of a
 * {@link VirtualScreen}, used to show subtitle text. Packet-based exactly like
 * the screen and the {@link ControlBar}: one fake entity id (≥
 * {@link VirtualScreen#FAKE_ID_BASE}), spawned per viewer and removed by the
 * screen's destroy packet.
 *
 * <p>Geometry: centered horizontally, baseline lifted well above the screen's
 * bottom edge so the text sits over the lower third of the picture — and, when
 * a {@link ControlBar} is present, high enough to clear its buttons (a
 * TextDisplay grows UPWARD from its bottom anchor, so a multi-line cue never
 * grows down into the bar). Billboard FIXED (coplanar with the screen, oriented
 * by its facing yaw so it can sit right against the plane) and full-bright,
 * matching the bar. A fixed line width wraps long cues instead of letting them
 * stretch off the screen.
 *
 * <p>The text is updated in place with entity-metadata packets: an active cue
 * sends its component to every viewer, and {@link #clear()} sends an empty
 * component to hide the display between cues. All ids/positions are immutable
 * after construction, so {@link #spawnFor} (per late joiner) and the setters are
 * safe from the playback thread.
 */
public final class SubtitleOverlay {

    /*
     * Display / TextDisplay entity-data indices on 1.21.x (same source as
     * ControlBar: EntityLib's metadata offsets for the 1.20.2+ layout).
     */
    private static final int DISPLAY_SCALE_INDEX = 12;        // Vector3f
    private static final int DISPLAY_BILLBOARD_INDEX = 15;    // byte
    private static final int DISPLAY_BRIGHTNESS_INDEX = 16;   // int
    private static final int TEXT_DISPLAY_TEXT_INDEX = 23;    // component
    private static final int TEXT_DISPLAY_LINE_WIDTH_INDEX = 24; // int

    private static final byte BILLBOARD_FIXED = 0;
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);

    /** Wrap width in the TextDisplay's own pixel units (default is 200): lower
     *  wraps sooner, so long cues break onto several lines instead of one wide row. */
    private static final int LINE_WIDTH = 200;
    /** The map surface sits this far in front of the tile's block center; the
     *  configured depth (subtitle-depth) is measured from here. */
    private static final double SCREEN_SURFACE = 0.5;
    /** Extra lift when a control bar is present, to clear its ~0.55-tall buttons. */
    private static final double CONTROL_BAR_CLEARANCE = 0.7;

    private final int entityId;
    private final UUID entityUuid;
    private final Vector3d position;
    private final float facingYaw; // FIXED display yaw: text faces the audience
    private final float scale;     // text scale (subtitle-size)

    /** Current cue text (empty = hidden); resent to late joiners on spawn. */
    private volatile Component current = Component.empty();

    /**
     * @param centerX        screen center X
     * @param bottomEdgeY    Y of the screen's bottom edge (bottom tile center - 0.5)
     * @param centerZ        screen center Z
     * @param outX           screen-to-audience unit vector (X component)
     * @param outZ           screen-to-audience unit vector (Z component)
     * @param hasControlBar  whether a control bar occupies the very bottom strip
     * @param settings       overlay scale / height / depth (configurable)
     */
    SubtitleOverlay(double centerX, double bottomEdgeY, double centerZ,
                    int outX, int outZ, boolean hasControlBar, SubtitleSettings settings) {
        this.scale = settings.scale();
        double frontOffset = SCREEN_SURFACE + settings.depthInFront();
        double lift = settings.heightAboveEdge() + (hasControlBar ? CONTROL_BAR_CLEARANCE : 0.0);
        this.position = new Vector3d(
                centerX + outX * frontOffset,
                bottomEdgeY + lift,
                centerZ + outZ * frontOffset);
        // FIXED billboarding: the display's own yaw orients the text. Face it
        // along the screen-to-audience vector (Minecraft yaw: 0=+Z/south,
        // 90=-X/west, 180=-Z/north, -90=+X/east).
        this.facingYaw = (float) Math.toDegrees(Math.atan2(-outX, outZ));
        this.entityId = VirtualScreen.nextEntityId();
        this.entityUuid = UUID.randomUUID();
    }

    /** Spawns the (initially hidden) subtitle display for one viewer. Any thread. */
    void spawnFor(Player player) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                new WrapperPlayServerSpawnEntity(entityId, Optional.of(entityUuid),
                        EntityTypes.TEXT_DISPLAY, position, 0f, facingYaw, facingYaw, 0, Optional.empty()));
        List<EntityData<?>> data = new ArrayList<>(5);
        data.add(new EntityData<>(DISPLAY_SCALE_INDEX, EntityDataTypes.VECTOR3F,
                new Vector3f(scale, scale, scale)));
        data.add(new EntityData<>(DISPLAY_BILLBOARD_INDEX, EntityDataTypes.BYTE, BILLBOARD_FIXED));
        data.add(new EntityData<>(DISPLAY_BRIGHTNESS_INDEX, EntityDataTypes.INT, FULL_BRIGHT));
        data.add(new EntityData<>(TEXT_DISPLAY_LINE_WIDTH_INDEX, EntityDataTypes.INT, LINE_WIDTH));
        data.add(new EntityData<>(TEXT_DISPLAY_TEXT_INDEX, EntityDataTypes.ADV_COMPONENT, current));
        PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                new WrapperPlayServerEntityMetadata(entityId, data));
    }

    /**
     * Sets the cue text and pushes it to every current viewer. A {@code null} or
     * blank string hides the display (empty component). Idempotent: unchanged
     * text sends nothing, so an unchanged cue does not spam metadata packets
     * every frame.
     *
     * <p>Synchronized: the playback thread reconciles cues here every frame, and
     * {@code /video subs off/&lt;n&gt;} may clear/replace from the main thread at
     * the same time, so the compare-and-send must be atomic (otherwise both
     * could send, causing a flicker).
     */
    synchronized void setText(String text, Iterable<? extends Player> viewers) {
        // Plain default-colored text (white on the display's dark background),
        // matching ControlBar's Component.text usage — the same non-relocated
        // adventure Component the packetevents jar bundles, so it round-trips.
        Component next = (text == null || text.isBlank())
                ? Component.empty()
                : Component.text(text);
        if (next.equals(current)) {
            return; // same cue still showing: nothing to resend
        }
        current = next;
        List<EntityData<?>> data = List.of(new EntityData<>(
                TEXT_DISPLAY_TEXT_INDEX, EntityDataTypes.ADV_COMPONENT, next));
        for (Player viewer : viewers) {
            if (viewer.isOnline()) {
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                        new WrapperPlayServerEntityMetadata(entityId, data));
            }
        }
    }

    /** Hides the subtitle text for every current viewer. */
    void clear(Iterable<? extends Player> viewers) {
        setText(null, viewers);
    }

    /** The overlay's fake entity id, for the screen's entity-remove packet. */
    int getEntityId() {
        return entityId;
    }
}
