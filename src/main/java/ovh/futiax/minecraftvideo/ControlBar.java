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
 * Clickable playback controls overlaid on a {@link VirtualScreen}'s bottom
 * edge: a row of four buttons — rewind 10 s, play/pause, forward 10 s, skip
 * to the next queued video. Fully packet-based like the screen itself: each button is one
 * fake TEXT_DISPLAY entity (the glyph players see) plus one fake INTERACTION
 * entity (an invisible ~0.55x0.55 hitbox at the same spot, which makes the
 * client send serverbound INTERACT_ENTITY packets on click — handled by
 * {@link ControlBarListener}).
 *
 * <p>The bar overlays the bottom strip of the picture (baseline just above
 * the screen's bottom edge, floating in front of the plane) — video-player
 * style. Strictly below the edge is not an option: the screen's bottom row
 * sits at the anchor player's foot level, so anything under it is buried in
 * flat ground.
 *
 * <p>The displays use billboard FIXED, oriented by the screen's facing yaw
 * (derived from the screen-to-audience vector) so the glyphs sit coplanar with
 * the picture like a real on-screen overlay, and can be pushed right up against
 * the plane without a pivot rotating them into it. If a screen ever renders the
 * glyphs mirrored / back-to-front, flip {@code facingYaw} by 180°. The click
 * hitboxes are axis-aligned boxes regardless, so nothing depends on the text
 * orientation.
 *
 * <p>Owned by the VirtualScreen: spawned per viewer right after the screen's
 * frames (including late joiners) and destroyed with it (the screen's
 * entity-remove packet includes the bar's ids). All state is immutable after
 * construction, so lookups are safe from any thread.
 */
public final class ControlBar {

    /** The buttons, left to right from the audience's point of view. */
    public enum Button {
        REWIND("⏪"),      // ⏪ seek -10 s
        PLAY_PAUSE("⏯"),  // ⏯ toggle pause/resume
        FORWARD("⏩"),     // ⏩ seek +10 s
        SKIP("⏭");        // ⏭ next queued video

        private final String glyph;

        Button(String glyph) {
            this.glyph = glyph;
        }
    }

    /*
     * Entity-data indices on 1.21.x, verified against EntityLib's metadata
     * offsets for packetevents (1.20.2+ layout; packetevents itself ships no
     * per-entity index constants): base Entity uses 0-7, then Display adds
     * 8-22 (11 translation, 12 scale, 15 billboard, 16 brightness, ...) and
     * TextDisplay continues with 23 text, 24 line width, 25 background color,
     * 26 opacity, 27 flags. Interaction adds 8 width, 9 height, 10 responsive.
     */
    private static final int DISPLAY_SCALE_INDEX = 12;          // Vector3f
    private static final int DISPLAY_BILLBOARD_INDEX = 15;      // byte
    private static final int DISPLAY_BRIGHTNESS_INDEX = 16;     // int
    private static final int TEXT_DISPLAY_TEXT_INDEX = 23;      // component
    private static final int INTERACTION_WIDTH_INDEX = 8;       // float
    private static final int INTERACTION_HEIGHT_INDEX = 9;      // float
    private static final int INTERACTION_RESPONSIVE_INDEX = 10; // boolean

    /** Billboard constraint ids: FIXED=0, VERTICAL=1, HORIZONTAL=2, CENTER=3. */
    private static final byte BILLBOARD_FIXED = 0;
    /** Brightness override block 15 / sky 15, matching the glow-frame screen. */
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);

    /** Distance between button centers along the screen's horizontal axis. */
    private static final double BUTTON_SPACING = 0.9;
    /**
     * Lift of the bar's baseline above the screen's bottom edge. The bottom
     * row is at the anchor player's foot level, so the bar overlays the
     * picture's bottom strip instead of hanging (underground) below it.
     */
    private static final double BASELINE_ABOVE_EDGE = 0.05;
    /** Offset toward the audience: right against the map surface (at 0.5),
     *  a hair in front to avoid z-fighting. FIXED billboarding never pivots the
     *  glyph back into the plane, so it can sit this close. */
    private static final double FRONT_OFFSET = 0.55;
    /** Text scale: one glyph line (~0.25 blocks at scale 1) becomes ~0.5. */
    private static final float TEXT_SCALE = 2.0f;
    /** Interaction hitbox width and height, roughly covering the glyph. */
    private static final float HITBOX_SIZE = 0.55f;

    private final int[] textIds;        // button ordinal -> text display id
    private final int[] interactionIds; // button ordinal -> interaction id
    private final UUID[] textUuids;
    private final UUID[] interactionUuids;
    private final Vector3d[] positions; // shared glyph + hitbox anchor (both
                                        // entity types anchor at their bottom)
    private final float facingYaw;      // FIXED display yaw: glyphs face the audience

    /**
     * @param centerX     screen center X
     * @param bottomEdgeY Y of the screen's bottom edge (bottom tile center - 0.5)
     * @param centerZ     screen center Z
     * @param rightX      viewer-right unit vector of the screen (X component)
     * @param rightZ      viewer-right unit vector of the screen (Z component)
     * @param outX        screen-to-audience unit vector (X component)
     * @param outZ        screen-to-audience unit vector (Z component)
     */
    ControlBar(double centerX, double bottomEdgeY, double centerZ,
               int rightX, int rightZ, int outX, int outZ) {
        Button[] buttons = Button.values();
        int n = buttons.length;
        this.textIds = new int[n];
        this.interactionIds = new int[n];
        this.textUuids = new UUID[n];
        this.interactionUuids = new UUID[n];
        this.positions = new Vector3d[n];

        // FIXED billboarding: the display's own yaw orients the glyph. Face it
        // along the screen-to-audience vector (Minecraft yaw: 0=+Z/south,
        // 90=-X/west, 180=-Z/north, -90=+X/east).
        this.facingYaw = (float) Math.toDegrees(Math.atan2(-outX, outZ));

        double y = bottomEdgeY + BASELINE_ABOVE_EDGE;
        for (int i = 0; i < n; i++) {
            double d = (i - (n - 1) / 2.0) * BUTTON_SPACING; // centered row
            positions[i] = new Vector3d(
                    centerX + rightX * d + outX * FRONT_OFFSET,
                    y,
                    centerZ + rightZ * d + outZ * FRONT_OFFSET);
            textIds[i] = VirtualScreen.nextEntityId();
            interactionIds[i] = VirtualScreen.nextEntityId();
            textUuids[i] = UUID.randomUUID();
            interactionUuids[i] = UUID.randomUUID();
        }
    }

    /** Spawns the bar (glyphs + hitboxes) for one viewer. Any thread. */
    void spawnFor(Player player) {
        Button[] buttons = Button.values();
        for (int i = 0; i < buttons.length; i++) {
            // The visible glyph: a TEXT_DISPLAY with the default (translucent
            // dark) background. Full-bright so the bar reads in a dark cinema.
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerSpawnEntity(textIds[i],
                            Optional.of(textUuids[i]), EntityTypes.TEXT_DISPLAY,
                            positions[i], 0f, facingYaw, facingYaw, 0, Optional.empty()));
            List<EntityData<?>> textData = new ArrayList<>(4);
            textData.add(new EntityData<>(DISPLAY_SCALE_INDEX,
                    EntityDataTypes.VECTOR3F,
                    new Vector3f(TEXT_SCALE, TEXT_SCALE, TEXT_SCALE)));
            textData.add(new EntityData<>(DISPLAY_BILLBOARD_INDEX,
                    EntityDataTypes.BYTE, BILLBOARD_FIXED));
            textData.add(new EntityData<>(DISPLAY_BRIGHTNESS_INDEX,
                    EntityDataTypes.INT, FULL_BRIGHT));
            textData.add(new EntityData<>(TEXT_DISPLAY_TEXT_INDEX,
                    EntityDataTypes.ADV_COMPONENT, Component.text(buttons[i].glyph)));
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerEntityMetadata(textIds[i], textData));

            // The clickable hitbox: an INTERACTION entity at the same spot.
            // "responsive" makes the client play the arm-swing/hit feedback,
            // so the button feels clicked even before the server reacts.
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerSpawnEntity(interactionIds[i],
                            Optional.of(interactionUuids[i]), EntityTypes.INTERACTION,
                            positions[i], 0f, 0f, 0f, 0, Optional.empty()));
            List<EntityData<?>> boxData = new ArrayList<>(3);
            boxData.add(new EntityData<>(INTERACTION_WIDTH_INDEX,
                    EntityDataTypes.FLOAT, HITBOX_SIZE));
            boxData.add(new EntityData<>(INTERACTION_HEIGHT_INDEX,
                    EntityDataTypes.FLOAT, HITBOX_SIZE));
            boxData.add(new EntityData<>(INTERACTION_RESPONSIVE_INDEX,
                    EntityDataTypes.BOOLEAN, true));
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerEntityMetadata(interactionIds[i], boxData));
        }
    }

    /** All fake ids of the bar, for the screen's entity-remove packet. */
    int[] getEntityIds() {
        int[] ids = new int[textIds.length + interactionIds.length];
        System.arraycopy(textIds, 0, ids, 0, textIds.length);
        System.arraycopy(interactionIds, 0, ids, textIds.length, interactionIds.length);
        return ids;
    }

    /**
     * The button whose INTERACTION hitbox carries this entity id, or
     * {@code null} if the id is not one of this bar's hitboxes (text displays
     * have no hitbox, so clicks can only ever target the interactions).
     */
    public Button buttonAt(int entityId) {
        for (int i = 0; i < interactionIds.length; i++) {
            if (interactionIds[i] == entityId) {
                return Button.values()[i];
            }
        }
        return null;
    }
}
