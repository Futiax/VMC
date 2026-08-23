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

// Controles de lecture cliquables poses sur le bord bas d'un VirtualScreen : une rangee de
// quatre boutons - reculer 10 s, play/pause, avancer 10 s, passer a la video suivante de la
// file. 100% paquets comme l'ecran : chaque bouton = une entite TEXT_DISPLAY fake (le glyphe
// que les joueurs voient) + une entite INTERACTION fake (une hitbox invisible ~0.55x0.55 au
// meme endroit, c'est elle qui fait envoyer les INTERACT_ENTITY au clic, traites par
// ControlBarListener).
//
// La barre est en OVERLAY sur la bande du bas de l'image (baseline juste au dessus du bord,
// flottant devant le plan), facon lecteur video. Sous le bord = pas possible : la rangee du
// bas de l'ecran est au niveau des pieds du joueur qui l'ancre, donc tout ce qui passe en
// dessous est enterre dans le sol.
//
// Les displays sont en billboard FIXED, orientes par le yaw de l'ecran (deduit du vecteur
// ecran -> public), comme ca les glyphes restent coplanaires avec l'image comme un vrai
// overlay et peuvent coller au plan sans qu'un pivot les fasse rentrer dedans. Si un jour un
// ecran rend les glyphes en miroir, retourner facingYaw de 180 degres. Les hitboxes de clic
// sont des boites alignees aux axes de toute facon, rien ne depend de l'orientation du texte.
//
// Possedee par le VirtualScreen : spawnee par viewer juste apres les frames de l'ecran (late
// joiners compris) et detruite avec lui (le paquet entity-remove de l'ecran inclut ses ids).
// Tout est immuable apres construction donc les lookups sont bons depuis n'importe quel thread.
public final class ControlBar {

    // les boutons, de gauche a droite du point de vue du public
    public enum Button {
        REWIND("⏪"),      // seek -10 s
        PLAY_PAUSE("⏯"),  // pause/resume
        FORWARD("⏩"),     // seek +10 s
        SKIP("⏭");        // video suivante de la file

        private final String glyph;

        Button(String glyph) {
            this.glyph = glyph;
        }
    }

    private static final byte BILLBOARD_FIXED = 0;              // FIXED=0 VERTICAL=1 HORIZONTAL=2 CENTER=3
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);  // block 15 / sky 15, comme les glow frames

    private static final double BUTTON_SPACING = 0.9;           // ecart entre centres de boutons
    private static final double BASELINE_ABOVE_EDGE = 0.05;     // remontee au dessus du bord bas :
                                                                // la rangee du bas est au niveau des pieds,
                                                                // donc on overlay au lieu de pendre dessous
    private static final double FRONT_OFFSET = 0.55;            // vers le public : la surface map est a 0.5,
                                                                // un chouia devant pour eviter le z-fighting
    private static final float TEXT_SCALE = 2.0f;               // une ligne de glyphe ~0.25 bloc -> ~0.5
    private static final float HITBOX_SIZE = 0.55f;             // hitbox qui couvre a peu pres le glyphe

    private final int[] textIds;        // ordinal du bouton -> id du text display
    private final int[] interactionIds; // ordinal du bouton -> id de l'interaction
    private final UUID[] textUuids;
    private final UUID[] interactionUuids;
    private final Vector3d[] positions; // ancre commune glyphe + hitbox (les deux types
                                        // d'entite s'ancrent par le bas)
    private final float facingYaw;      // yaw du display FIXED : les glyphes regardent le public

    //   centerX/centerZ = centre de l'ecran, bottomEdgeY = Y du bord bas (centre de la tuile
    //   du bas - 0.5), rightX/rightZ = vecteur unitaire "droite du spectateur",
    //   outX/outZ = vecteur unitaire ecran -> public
    ControlBar(double centerX, double bottomEdgeY, double centerZ,
               int rightX, int rightZ, int outX, int outZ) {
        Button[] buttons = Button.values();
        int n = buttons.length;
        this.textIds = new int[n];
        this.interactionIds = new int[n];
        this.textUuids = new UUID[n];
        this.interactionUuids = new UUID[n];
        this.positions = new Vector3d[n];

        // Billboard FIXED : c'est le yaw du display qui oriente le glyphe. On le pointe le long
        // du vecteur ecran -> public (yaw MC : 0=+Z/sud, 90=-X/ouest, 180=-Z/nord, -90=+X/est).
        this.facingYaw = (float) Math.toDegrees(Math.atan2(-outX, outZ));

        double y = bottomEdgeY + BASELINE_ABOVE_EDGE;
        for (int i = 0; i < n; i++) {
            double d = (i - (n - 1) / 2.0) * BUTTON_SPACING; // rangee centree
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

    // Spawn la barre (glyphes + hitboxes) pour un viewer. N'importe quel thread.
    void spawnFor(Player player) {
        Button[] buttons = Button.values();
        for (int i = 0; i < buttons.length; i++) {
            // le glyphe visible : un TEXT_DISPLAY avec le fond par defaut (sombre translucide),
            // full-bright pour que la barre reste lisible dans un cinema dans le noir
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerSpawnEntity(textIds[i],
                            Optional.of(textUuids[i]), EntityTypes.TEXT_DISPLAY,
                            positions[i], 0f, facingYaw, facingYaw, 0, Optional.empty()));
            List<EntityData<?>> textData = new ArrayList<>(4);
            textData.add(new EntityData<>(MetadataIndices.DISPLAY_SCALE,
                    EntityDataTypes.VECTOR3F,
                    new Vector3f(TEXT_SCALE, TEXT_SCALE, TEXT_SCALE)));
            textData.add(new EntityData<>(MetadataIndices.DISPLAY_BILLBOARD,
                    EntityDataTypes.BYTE, BILLBOARD_FIXED));
            textData.add(new EntityData<>(MetadataIndices.DISPLAY_BRIGHTNESS,
                    EntityDataTypes.INT, FULL_BRIGHT));
            textData.add(new EntityData<>(MetadataIndices.TEXT_DISPLAY_TEXT,
                    EntityDataTypes.ADV_COMPONENT, Component.text(buttons[i].glyph)));
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerEntityMetadata(textIds[i], textData));

            // la hitbox cliquable : une entite INTERACTION au meme endroit. "responsive" fait
            // jouer le coup de bras / feedback cote client, donc le bouton parait clique avant
            // meme que le serveur ait reagi.
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerSpawnEntity(interactionIds[i],
                            Optional.of(interactionUuids[i]), EntityTypes.INTERACTION,
                            positions[i], 0f, 0f, 0f, 0, Optional.empty()));
            List<EntityData<?>> boxData = new ArrayList<>(3);
            boxData.add(new EntityData<>(MetadataIndices.INTERACTION_WIDTH,
                    EntityDataTypes.FLOAT, HITBOX_SIZE));
            boxData.add(new EntityData<>(MetadataIndices.INTERACTION_HEIGHT,
                    EntityDataTypes.FLOAT, HITBOX_SIZE));
            boxData.add(new EntityData<>(MetadataIndices.INTERACTION_RESPONSIVE,
                    EntityDataTypes.BOOLEAN, true));
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerEntityMetadata(interactionIds[i], boxData));
        }
    }

    // tous les ids fake de la barre, pour le paquet entity-remove de l'ecran
    int[] getEntityIds() {
        int[] ids = new int[textIds.length + interactionIds.length];
        System.arraycopy(textIds, 0, ids, 0, textIds.length);
        System.arraycopy(interactionIds, 0, ids, textIds.length, interactionIds.length);
        return ids;
    }

    // Le bouton dont la hitbox INTERACTION porte cet id, null si l'id n'est pas une des
    // hitboxes de cette barre (les text displays n'ont pas de hitbox, un clic ne peut donc
    // jamais viser qu'une interaction).
    public Button buttonAt(int entityId) {
        for (int i = 0; i < interactionIds.length; i++) {
            if (interactionIds[i] == entityId) {
                return Button.values()[i];
            }
        }
        return null;
    }
}
