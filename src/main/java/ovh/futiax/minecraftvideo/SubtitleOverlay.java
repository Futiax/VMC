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

// UNE seule entite TEXT_DISPLAY fake qui flotte sur le bas d'un VirtualScreen, pour afficher
// les sous-titres. 100% paquets comme l'ecran et la ControlBar : un id fake (>= FAKE_ID_BASE),
// spawn par viewer, vire par le paquet destroy de l'ecran.
//
// Geometrie : centre horizontalement, baseline remontee bien au dessus du bord bas pour que
// le texte tombe sur le tiers inferieur de l'image, et si une ControlBar est la, assez haut
// pour degager ses boutons (un TextDisplay pousse vers le HAUT depuis son ancre basse, donc
// une cue multi-lignes ne redescend jamais dans la barre). Billboard FIXED (coplanaire avec
// l'ecran, oriente par son yaw, il peut donc coller au plan) et full-bright, comme la barre.
// Une line width fixe wrap les cues longues au lieu de les laisser deborder de l'ecran.
//
// Le texte se met a jour en place par paquets entity-metadata : une cue active pousse son
// component a tous les viewers, clear() envoie un component vide pour cacher l'affichage
// entre deux cues. Tous les ids/positions sont immuables apres construction, donc spawnFor()
// (late joiner) et les setters sont sans risque depuis le thread de lecture.
public final class SubtitleOverlay {

    private static final byte BILLBOARD_FIXED = 0;
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);

    private static final int LINE_WIDTH = 200;          // unites pixel du TextDisplay (defaut 200),
                                                        // plus bas = wrap plus tot
    private static final double SCREEN_SURFACE = 0.5;   // la surface map est a 0.5 devant le centre
                                                        // du bloc, subtitle-depth s'ajoute par dessus
    private static final double CONTROL_BAR_CLEARANCE = 0.7;    // rab quand la barre est la (boutons ~0.55)

    private final int entityId;
    private final UUID entityUuid;
    private final Vector3d position;
    private final float facingYaw; // yaw du display FIXED : le texte regarde le public
    private final float scale;     // subtitle-size

    private volatile Component current = Component.empty();     // cue en cours, vide = cache.
                                                                // Renvoyee aux late joiners au spawn.

    //   centerX/centerZ = centre de l'ecran, bottomEdgeY = Y du bord bas (centre de la tuile
    //   du bas - 0.5), outX/outZ = vecteur unitaire ecran -> public, hasControlBar = est-ce
    //   qu'une barre occupe la bande du bas, settings = scale/hauteur/profondeur configurables
    SubtitleOverlay(double centerX, double bottomEdgeY, double centerZ,
                    int outX, int outZ, boolean hasControlBar, SubtitleSettings settings) {
        this.scale = settings.scale();
        double frontOffset = SCREEN_SURFACE + settings.depthInFront();
        double lift = settings.heightAboveEdge() + (hasControlBar ? CONTROL_BAR_CLEARANCE : 0.0);
        this.position = new Vector3d(
                centerX + outX * frontOffset,
                bottomEdgeY + lift,
                centerZ + outZ * frontOffset);
        // Billboard FIXED : c'est le yaw du display qui oriente le texte. On le pointe le long
        // du vecteur ecran -> public (yaw MC : 0=+Z/sud, 90=-X/ouest, 180=-Z/nord, -90=+X/est).
        this.facingYaw = (float) Math.toDegrees(Math.atan2(-outX, outZ));
        this.entityId = VirtualScreen.nextEntityId();
        this.entityUuid = UUID.randomUUID();
    }

    // Spawn l'affichage (cache au depart) pour un viewer. N'importe quel thread.
    void spawnFor(Player player) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                new WrapperPlayServerSpawnEntity(entityId, Optional.of(entityUuid),
                        EntityTypes.TEXT_DISPLAY, position, 0f, facingYaw, facingYaw, 0, Optional.empty()));
        List<EntityData<?>> data = new ArrayList<>(5);
        data.add(new EntityData<>(MetadataIndices.DISPLAY_SCALE, EntityDataTypes.VECTOR3F,
                new Vector3f(scale, scale, scale)));
        data.add(new EntityData<>(MetadataIndices.DISPLAY_BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_FIXED));
        data.add(new EntityData<>(MetadataIndices.DISPLAY_BRIGHTNESS, EntityDataTypes.INT, FULL_BRIGHT));
        data.add(new EntityData<>(MetadataIndices.TEXT_DISPLAY_LINE_WIDTH, EntityDataTypes.INT, LINE_WIDTH));
        data.add(new EntityData<>(MetadataIndices.TEXT_DISPLAY_TEXT, EntityDataTypes.ADV_COMPONENT, current));
        PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                new WrapperPlayServerEntityMetadata(entityId, data));
    }

    // Pose le texte de la cue et le pousse a tous les viewers. null ou blanc = on cache
    // (component vide). Idempotent : texte inchange = rien d'envoye, donc une cue qui dure
    // ne spamme pas un paquet metadata a chaque frame.
    //
    // synchronized parce que le thread de lecture reconcilie les cues ici a chaque frame
    // pendant que /video subs off|<n> peut clear/remplacer depuis le main thread : le
    // compare-and-send doit etre atomique, sinon les deux envoient et ca clignote.
    synchronized void setText(String text, Iterable<? extends Player> viewers) {
        // Texte brut couleur par defaut (blanc sur le fond sombre du display), meme usage de
        // Component.text que ControlBar : c'est le meme Component adventure non relocalise
        // bundle par le jar packetevents, donc ca passe le round-trip.
        Component next = (text == null || text.isBlank())
                ? Component.empty()
                : Component.text(text);
        if (next.equals(current)) {
            return; // meme cue toujours affichee, rien a renvoyer
        }
        current = next;
        List<EntityData<?>> data = List.of(new EntityData<>(
                MetadataIndices.TEXT_DISPLAY_TEXT, EntityDataTypes.ADV_COMPONENT, next));
        for (Player viewer : viewers) {
            if (viewer.isOnline()) {
                PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                        new WrapperPlayServerEntityMetadata(entityId, data));
            }
        }
    }

    void clear(Iterable<? extends Player> viewers) {
        setText(null, viewers);
    }

    int getEntityId() {                 // pour le paquet entity-remove de l'ecran
        return entityId;
    }
}
