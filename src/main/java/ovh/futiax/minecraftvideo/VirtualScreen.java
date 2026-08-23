package ovh.futiax.minecraftvideo;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
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
import io.netty.channel.Channel;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// Un mur virtuel de glow item frames, chacune tenant une map remplie virtuelle.
// 100% paquets : fake entity ids et fake map ids, rien n'existe cote serveur et rien n'est
// ecrit dans le monde.
//
// L'ecran se pose quelques blocs devant le joueur qui l'ancre, vertical, tourne vers lui.
// La tuile (ligne 0, colonne 0) est le coin HAUT-GAUCHE du point de vue du spectateur.
// L'ecran possede eventuellement une ControlBar (boutons cliquables en overlay sur son bord
// bas) et possede TOUJOURS un SubtitleOverlay (un text display fake sur le bas de l'image,
// cache tant qu'aucune piste de sous-titres n'est choisie) ; les deux suivent le cycle de vie
// des viewers de l'ecran : spawnes avec lui, spawnes pour chaque late joiner, et vires par le
// meme paquet destroy.
public final class VirtualScreen {

    // Espaces d'ids fake. Le vanilla alloue ses entity ids et map ids en montant depuis 0 a
    // chaque demarrage ; un serveur charge qui tourne longtemps peut depasser 1 000 000, donc
    // on part pres de Integer.MAX_VALUE pour rester disjoint des vrais ids en pratique.
    // Tout id >= FAKE_ID_BASE est a nous (frames d'ecran ou barre de controle).
    static final int FAKE_ID_BASE = 2_000_000_000;
    private static final AtomicInteger ENTITY_ID_COUNTER = new AtomicInteger(FAKE_ID_BASE);
    private static final AtomicInteger MAP_ID_COUNTER = new AtomicInteger(FAKE_ID_BASE);

    static int nextEntityId() {     // un seul espace d'ids, partage avec la barre
        return ENTITY_ID_COUNTER.getAndIncrement();
    }

    private static final int SCREEN_DISTANCE = 4;   // blocs entre le joueur et le plan de l'ecran
    private static final byte FLAG_INVISIBLE = 0x20;

    private final int width;
    private final int height;
    private final int[] entityIds;      // index de tuile -> fake entity id
    private final int[] mapIds;         // index de tuile -> fake map id
    private final UUID[] entityUuids;   // index de tuile -> fake uuid stable
    private final Vector3d[] positions; // index de tuile -> centre du bloc de la frame
    private final float frameYaw;       // yaw du paquet de spawn, pour l'orientation
    private final int frameDirectionData; // champ data du paquet de spawn = id de direction
    private final int rightUnitX;       // vecteur unitaire "droite spectateur" (X), pour les ancres stereo
    private final int rightUnitZ;       // idem (Z)
    private final int audienceUnitX;    // vecteur unitaire ecran -> public (X), pour les enceintes arriere
    private final int audienceUnitZ;    // idem (Z)

    private final ControlBar controlBar;            // null quand la barre est desactivee
    private final SubtitleOverlay subtitleOverlay;  // cache tant qu'aucune piste n'est active

    private final UUID worldId;         // un joueur d'un autre monde ne verra jamais l'ecran
    private final double centerX;       // centre de l'ecran, pour le culling par distance
    private final double centerY;
    private final double centerZ;
    private final double rangeSquared;  // 0 = pas de limite de distance

    private final Set<Player> viewers = ConcurrentHashMap.newKeySet();
    private volatile byte[][] lastFrame;

    // Viewers a qui on a saute une frame (leur channel netty etait plein) -> quelles tuiles leur
    // manquent, cumulees sur toutes les frames sautees. On leur renvoie CETTE union et pas la
    // frame entiere : un viewer sature est justement celui a qui il ne faut pas balancer 2 Mo
    // d'un coup, ca le resaturerait immediatement.
    private final Map<UUID, boolean[]> missed = new ConcurrentHashMap<>();
    private final AtomicInteger droppedFrames = new AtomicInteger();

    // anchor est capturee sur le main thread, et on n'en tire qu'une position de bloc et un
    // yaw : la Location n'est jamais relue, donc l'ecran ne suit pas le joueur qui l'a pose.
    public VirtualScreen(Location anchor, int width, int height, boolean withControlBar,
                         SubtitleSettings subtitleSettings, int viewerRange) {
        this.width = width;
        this.height = height;
        this.worldId = anchor.getWorld().getUID();
        this.rangeSquared = viewerRange <= 0 ? 0 : (double) viewerRange * viewerRange;

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

        // direction que le joueur regarde (a l'horizontale), et direction des frames :
        // retournees vers lui
        BlockFace playerDirection = yawToFace(anchor.getYaw());
        BlockFace facing = playerDirection.getOppositeFace();

        // Champs du paquet de spawn pour les quatre orientations gerees.
        // Ids de direction d'item frame : DOWN=0 UP=1 NORTH=2 SOUTH=3 WEST=4 EAST=5.
        // Convention de yaw : SOUTH=0, WEST=90, NORTH=180, EAST=270.
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

        // Direction "droite" de l'ecran du point de vue du SPECTATEUR. Il se tient du cote
        // `facing` et regarde le long de -facing :
        //   frames vers SUD   -> il regarde au nord -> sa droite = est   (+X)
        //   frames vers NORD  -> il regarde au sud  -> sa droite = ouest (-X)
        //   frames vers EST   -> il regarde a l'ouest -> sa droite = nord (-Z)
        //   frames vers OUEST -> il regarde a l'est   -> sa droite = sud  (+Z)
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
        // les frames regardent vers le joueur, donc vers le public
        this.audienceUnitX = facing.getModX();
        this.audienceUnitZ = facing.getModZ();

        // Plan de l'ecran a SCREEN_DISTANCE blocs devant le joueur, centre horizontalement sur
        // sa ligne de vue, rangee du bas au niveau des pieds.
        int centerX = anchor.getBlockX() + playerDirection.getModX() * SCREEN_DISTANCE;
        int centerZ = anchor.getBlockZ() + playerDirection.getModZ() * SCREEN_DISTANCE;
        int baseY = anchor.getBlockY();
        int originX = centerX - rightX * ((width - 1) / 2);
        int originZ = centerZ - rightZ * ((width - 1) / 2);

        this.centerX = centerX + 0.5;
        this.centerY = baseY + (height - 1) / 2.0 + 0.5;
        this.centerZ = centerZ + 0.5;

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int x = originX + rightX * col;
                int z = originZ + rightZ * col;
                int y = baseY + (height - 1 - row); // ligne 0 = haut
                positions[row * width + col] = new Vector3d(x + 0.5, y + 0.5, z + 0.5);
            }
        }

        // Barre de controle en overlay sur le bord bas et overlay sous-titres sur le bas de
        // l'image (les deux ont besoin des positions de tuiles calculees juste au dessus).
        double[] sub = getSubAnchor();       // (x centre, y centre de la tuile du bas, z centre)
        double bottomEdgeY = sub[1] - 0.5;   // bord bas = centre de la tuile du bas - 0.5
        if (withControlBar) {
            this.controlBar = new ControlBar(sub[0], bottomEdgeY, sub[2],
                    rightX, rightZ, audienceUnitX, audienceUnitZ);
        } else {
            this.controlBar = null;
        }
        // Toujours spawne (cache) pour qu'on puisse activer les sous-titres a n'importe quel
        // moment et que les late joiners aient l'affichage ; remonte pour degager la barre.
        this.subtitleOverlay = new SubtitleOverlay(sub[0], bottomEdgeY, sub[2],
                audienceUnitX, audienceUnitZ, withControlBar, subtitleSettings);
    }

    // spawn l'ecran pour ces joueurs et les enregistre comme viewers
    public void spawn(Collection<? extends Player> players) {
        for (Player player : players) {
            addViewer(player);
        }
    }

    // Enregistre un viewer et lui (re)envoie les paquets de spawn des frames, les metadata
    // d'item et la derniere image video s'il y en a une.
    public void addViewer(Player player) {
        if (!player.isOnline() || !inRange(player) || !viewers.add(player)) {
            return;
        }
        sendSpawnAndMetadata(player);
        if (controlBar != null) {
            controlBar.spawnFor(player);
        }
        subtitleOverlay.spawnFor(player); // cache sauf si une cue est en cours
        byte[][] frame = lastFrame;
        if (frame != null) {
            sendMapData(player, frame);
        }
    }

    // Envoie les tuiles QUI ONT CHANGE a tous les viewers du moment et retient la frame pour
    // les late joiners (qui recoivent toutes les tuiles via addViewer).
    //
    // Une tuile dont les octets sont identiques a la frame precedente est SAUTEE : le client
    // continue d'afficher l'ancienne map, donc on economise un paquet map-data entier. La bande
    // passante client est le vrai goulot, donc c'est un gain direct sur les zones fixes
    // (bandes noires de letterbox, plans fixes) et ca ne coute jamais rien sur du contenu qui
    // bouge (juste la comparaison d'octets par tuile, faite une fois par frame et pas par
    // viewer). La premiere frame (pas de precedente) envoie tout.
    //
    // Un viewer dont le channel netty n'est pas writable est SAUTE pour cette frame : sa socket
    // tient deja plus qu'elle ne peut ecouler, et empiler de la map data par dessus ne fait que
    // retarder les keepalives et les paquets de mouvement coinces derriere, jusqu'au timeout du
    // client. Droper une frame est la seule facon que cette file se vide un jour. Les tuiles
    // qu'il rate vont dans missed et lui repartent des que sa socket respire.
    //
    // Appelable depuis un thread async : l'envoi de paquets packetevents est thread-safe (il
    // ecrit directement dans le channel netty du joueur).
    public void sendFrame(byte[][] tiles) {
        byte[][] previous = lastFrame;
        lastFrame = tiles;
        viewers.removeIf(viewer -> !viewer.isOnline());
        int count = Math.min(tiles.length, mapIds.length);

        // le diff se fait UNE fois pour toute la frame, pas une fois par viewer
        boolean[] changed = new boolean[count];
        for (int i = 0; i < count; i++) {
            changed[i] = previous == null || i >= previous.length
                    || !Arrays.equals(tiles[i], previous[i]);
        }

        for (Player viewer : viewers) {
            UUID id = viewer.getUniqueId();
            if (!isWritable(viewer)) {
                // on note ce qu'il rate au lieu de l'oublier : les tuiles fixes d'un plan fixe
                // restent hors de sa dette, seules celles qui ont bouge s'y accumulent
                or(missed.computeIfAbsent(id, k -> new boolean[count]), changed, count);
                droppedFrames.incrementAndGet();
                continue;
            }
            boolean[] debt = missed.remove(id);
            if (debt != null) {
                or(debt, changed, count);   // sa dette + ce qui change maintenant
            }
            sendBundle(viewer, tiles, debt != null ? debt : changed, count);
        }
    }

    private static void or(boolean[] into, boolean[] from, int count) {
        for (int i = 0; i < count; i++) {
            into[i] |= from[i];
        }
    }

    // Envoie les tuiles choisies dans UN SEUL bundle pour que le client les applique dans le
    // meme tick. Sans les delimiteurs de bundle les tuiles peuvent atterrir dans des ticks
    // differents, ce qui se voit comme un ecran coupe entre deux images (tearing) des que la
    // connexion souffre.
    //   changed = quelles tuiles envoyer, ou null pour tout envoyer
    // Le bundling des paquets de map est une idee reprise du plugin de lihuajian et son equipe.
    private void sendBundle(Player viewer, byte[][] tiles, boolean[] changed, int count) {
        PlayerManager players = PacketEvents.getAPI().getPlayerManager();
        boolean opened = false;
        for (int i = 0; i < count; i++) {
            if (changed != null && !changed[i]) {
                continue;
            }
            if (!opened) {
                players.writePacket(viewer, new WrapperPlayServerBundle());
                opened = true;
            }
            players.writePacket(viewer, mapPacket(i, tiles[i]));
        }
        if (opened) {
            // delimiteur de fermeture, flushe : un seul syscall pour toute la frame
            players.sendPacket(viewer, new WrapperPlayServerBundle());
        }
    }

    // La connexion du joueur peut-elle encaisser plus de donnees la tout de suite ? Netty
    // bascule ce flag a false des que les octets sortants non flushes passent le high water
    // mark du channel, c'est a dire pile quand la socket OS arrete d'accepter les ecritures.
    private static boolean isWritable(Player viewer) {
        Object channel = PacketEvents.getAPI().getPlayerManager().getChannel(viewer);
        // Type de channel inconnu (autre plateforme, mock) : on suppose writable plutot que de
        // refuser silencieusement d'envoyer quoi que ce soit.
        return !(channel instanceof Channel netty) || netty.isWritable();
    }

    public int getDroppedFrames() {     // frames dropees parce qu'un viewer saturait
        return droppedFrames.get();
    }

    // Aligne l'ensemble des viewers sur qui est reellement a portee : un ecran 5x3@20fps coute
    // ~30 Mbit/s PAR viewer, donc diffuser a quelqu'un qui mine a 5000 blocs ou qui est dans le
    // Nether est du gaspillage pur. Appele periodiquement depuis le main thread.
    public void refreshViewers(Collection<? extends Player> online) {
        for (Player player : online) {
            if (inRange(player)) {
                addViewer(player);  // no-op s'il est deja viewer
            } else {
                removeViewer(player);
            }
        }
    }

    // Retire l'ecran de chez un viewer (hors de portee, ou parti dans un autre monde).
    public void removeViewer(Player player) {
        if (!viewers.remove(player)) {
            return;
        }
        missed.remove(player.getUniqueId());
        if (player.isOnline()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerDestroyEntities(allEntityIds()));
        }
    }

    // La position est lue telle quelle : appele hors du main thread elle peut avoir un tick de
    // retard, ce qui est sans consequence a l'echelle d'un rayon de plusieurs dizaines de blocs.
    private boolean inRange(Player player) {
        Location loc = player.getLocation();
        if (loc.getWorld() == null || !worldId.equals(loc.getWorld().getUID())) {
            return false;
        }
        if (rangeSquared <= 0) {
            return true;
        }
        double dx = loc.getX() - centerX;
        double dy = loc.getY() - centerY;
        double dz = loc.getZ() - centerZ;
        return dx * dx + dy * dy + dz * dz <= rangeSquared;
    }

    // envoie les paquets entity-remove a tous les viewers et les oublie
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

    // tous les ids fake que l'ecran a spawnes : les frames, la barre de controle et l'overlay
    // sous-titres. Un seul paquet remove suffit a tout enlever chez un joueur.
    private int[] allEntityIds() {
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
        return ids;
    }

    private void sendSpawnAndMetadata(Player player) {
        for (int i = 0; i < entityIds.length; i++) {
            WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                    entityIds[i],
                    Optional.of(entityUuids[i]),
                    EntityTypes.GLOW_ITEM_FRAME,
                    positions[i],
                    0f,                 // pitch (mur vertical)
                    frameYaw,           // yaw
                    frameYaw,           // head yaw
                    frameDirectionData, // champ data = direction de la frame
                    Optional.empty());  // pas de velocite
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, spawn);

            ItemStack map = ItemStack.builder()
                    .type(ItemTypes.FILLED_MAP)
                    .amount(1)
                    .component(ComponentTypes.MAP_ID, mapIds[i])
                    .build();

            List<EntityData<?>> data = new ArrayList<>(2);
            // On cache la frame elle-meme pour ne laisser voir que la surface de la map.
            // L'orientation est deja posee par le champ data du paquet de spawn, donc ici on
            // n'ecrit que l'item, a l'index 9 : l'index 8 porte une Direction et y balancer un
            // ItemStack fait rejeter le paquet metadata par le client (protocol error).
            data.add(new EntityData<>(MetadataIndices.ENTITY_FLAGS, EntityDataTypes.BYTE, FLAG_INVISIBLE));
            data.add(new EntityData<>(MetadataIndices.ITEM_FRAME_ITEM, EntityDataTypes.ITEMSTACK, map));
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerEntityMetadata(entityIds[i], data));
        }
    }

    // envoie toutes les tuiles a un joueur (viewer neuf ou late joiner)
    private void sendMapData(Player player, byte[][] tiles) {
        missed.remove(player.getUniqueId()); // c'est justement la frame complete
        sendBundle(player, tiles, null, Math.min(tiles.length, mapIds.length));
    }

    // construit le paquet map-data complet 128x128 d'une tuile
    private WrapperPlayServerMapData mapPacket(int index, byte[] tile) {
        return new WrapperPlayServerMapData(
                mapIds[index],
                (byte) 0,                 // scale : zoom max
                false,                    // trackingPosition
                true,                     // locked
                Collections.emptyList(),  // pas de decorations
                128, 128,                 // colonnes, lignes : maj de la tuile entiere
                0, 0,                     // offsets x, z
                tile);
    }

    // yaw joueur -> BlockFace horizontale qu'il regarde
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

    public ControlBar getControlBar() {     // null si la barre est desactivee
        return controlBar;
    }

    // Affiche text sur l'overlay sous-titres pour tous les viewers du moment (null ou blanc =
    // on cache). Idempotent par cue : texte inchange = rien d'envoye. Appele depuis le thread
    // de lecture, l'envoi packetevents est thread-safe. Les viewers hors ligne sont elagues
    // paresseusement par sendFrame().
    public void setSubtitle(String text) {
        subtitleOverlay.setText(text, viewers);
    }

    public void clearSubtitle() {
        subtitleOverlay.clear(viewers);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    // Centre de l'ecran en coordonnees monde (moyenne des centres de tuiles), sert a ancrer
    // l'audio spatialise. Rend {x, y, z}.
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

    // Ancre monde de l'enceinte GAUCHE d'une paire stereo : le centre de l'ecran decale
    // jusqu'au bord gauche le long de l'axe "droite spectateur". Rend {x, y, z}.
    public double[] getLeftAnchor() {
        double[] c = getCenter();
        double half = width / 2.0;
        return new double[] { c[0] - rightUnitX * half, c[1], c[2] - rightUnitZ * half };
    }

    public double[] getRightAnchor() {      // idem, bord droit
        double[] c = getCenter();
        double half = width / 2.0;
        return new double[] { c[0] + rightUnitX * half, c[1], c[2] + rightUnitZ * half };
    }

    // Ancre de l'enceinte surround ARRIERE GAUCHE : l'ancre du bord gauche poussee de depth
    // blocs depuis le plan de l'ecran vers le public.
    public double[] getRearLeftAnchor(double depth) {
        double[] a = getLeftAnchor();
        return new double[] { a[0] + audienceUnitX * depth, a[1], a[2] + audienceUnitZ * depth };
    }

    public double[] getRearRightAnchor(double depth) {      // idem cote droit
        double[] a = getRightAnchor();
        return new double[] { a[0] + audienceUnitX * depth, a[1], a[2] + audienceUnitZ * depth };
    }

    // Ancre du CAISSON surround : horizontalement au centre de l'ecran, verticalement sur la
    // rangee du bas. Un sub pose au pied de l'ecran, comme au cinema.
    public double[] getSubAnchor() {
        double[] c = getCenter();
        double minY = Double.MAX_VALUE;
        for (Vector3d p : positions) {
            minY = Math.min(minY, p.getY());
        }
        return new double[] { c[0], minY, c[2] };
    }
}
