package ovh.futiax.mcdata.poc;

import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import ovh.futiax.mcdata.McData;
import ovh.futiax.mcdata.channel.VirtualBlocks;
import ovh.futiax.mcdata.mcmm.McmmStream;
import ovh.futiax.mcdata.screen.VirtualScreen;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

// POC "video" : joue une video sur un ecran virtuel (mur de maps) devant le joueur, decodee
// en temps reel par le binaire natif mcmm lance en sous-processus.
//
// Modes :
//   normal  ecran classique, post-effect custom_palette (espace couleur complet)
//   data    mur DataWall (WAN 4x2 ou LAN 5x3), post-effect vmc_data* qui decode le mur en canva.
//           En mode data, pose des CUSHIONS (blocs virtuels light) devant l'ecran a la bonne
//           distance pour que les joueurs sachent ou se placer.
//
// Commandes :
//   /mcdata video play <url> [normal|data] [wan|lan] [player]
//   /mcdata video stop [player]
//   /mcdata video on|off [player]   (ecran normal sans source, juste le mur)
public final class VideoPoc implements Poc {

    public static final String EFFECT_NORMAL = "minecraft:custom_palette";
    public static final String EFFECT_DATA_WAN = "minecraft:vmc_data";
    public static final String EFFECT_DATA_LAN = "minecraft:vmc_data_lan";

    public enum Wall {
        WAN(4, 2, 2.3, EFFECT_DATA_WAN, "--data"),
        LAN(5, 3, 2.7, EFFECT_DATA_LAN, "--data");

        final int width, height;
        final double distance;
        final String effect;
        final String mcmmFlag;

        Wall(int w, int h, double d, String e, String f) {
            width = w; height = h; distance = d; effect = e; mcmmFlag = f;
        }
    }

    private static final int DEFAULT_FPS = 20;

    private record Session(VirtualScreen screen, VirtualBlocks blocks, McmmStream stream,
                           Thread reader, UUID playerId) {}

    private final McData plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public VideoPoc(McData plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() { return "video"; }

    @Override
    public String postEffect() { return EFFECT_NORMAL; }

    @Override
    public String description() {
        return "Video sur ecran virtuel (decodee par mcmm). Mode data: cushions devant l'ecran.";
    }

    @Override
    public boolean isRunning(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public void play(Player player, String url, String mode, String wallName) {
        stop(player);

        boolean data = "data".equalsIgnoreCase(mode);
        Wall wall = "lan".equalsIgnoreCase(wallName) ? Wall.LAN : Wall.WAN;
        int w = data ? wall.width : 6;
        int h = data ? wall.height : 4;
        double distance = data ? wall.distance : screenDistance(w, h);
        String effect = data ? wall.effect : EFFECT_NORMAL;
        String mcmmFlag = data ? wall.mcmmFlag : null;

        Location anchor = player.getLocation();
        VirtualScreen screen = new VirtualScreen(anchor, w, h, distance);
        screen.spawn(java.util.List.of(player));
        plugin.getPostEffects().apply(player, effect);

        VirtualBlocks blocks = new VirtualBlocks();
        if (data) {
            WrappedBlockState cushion = WrappedBlockState.getByString("minecraft:light[level=15]");
            Location center = new Location(player.getWorld(), screen.getCenterX(), screen.getCenterY(),
                    screen.getCenterZ(), screen.getFrameYaw(), 0);
            blocks.poseCushions(player, center, screen.getFrameYaw(), 3, wall.distance,
                    screen.getBaseY() - 1, cushion);
            player.sendMessage("[MCDATA] Cushions poses devant l'ecran (mode data "
                    + wall.name().toLowerCase() + ", distance " + wall.distance + " blocs).");
        }

        McmmStream stream = null;
        Thread reader = null;
        if (url != null && !url.isBlank()) {
            String mcmm = plugin.getMcmmPath();
            String palette = plugin.getPalettePath();
            if (mcmm == null || palette == null) {
                player.sendMessage("[MCDATA] mcmm ou palette non dispo (voir config mcmm-path / palette-path). Ecran pose sans video.");
            } else {
                try {
                    stream = new McmmStream(plugin.getLogger(), mcmm, palette, url, w, h, DEFAULT_FPS, mcmmFlag);
                    stream.readHeader();
                    // l'ecran est construit depuis les dimensions demandees ; si mcmm renvoie
                    // autre chose on adapte
                    if (stream.getMapWidth() != w || stream.getMapHeight() != h) {
                        player.sendMessage("[MCDATA] mcmm a renvoye " + stream.getMapWidth() + "x"
                                + stream.getMapHeight() + " au lieu de " + w + "x" + h + " (force par le mode).");
                    }
                    final McmmStream s = stream;
                    final VirtualScreen scr = screen;
                    reader = new Thread(() -> readLoop(s, scr, player), "mcdata-video-" + player.getName());
                    reader.setDaemon(true);
                    reader.start();
                    player.sendMessage("[MCDATA] Video lancee : " + url + " (" + w + "x" + h
                            + " maps, " + stream.getFps() + " fps, mode " + (data ? "data " + wall.name().toLowerCase() : "normal") + ")");
                } catch (IOException e) {
                    player.sendMessage("[MCDATA] Echec lancement mcmm : " + e.getMessage());
                    plugin.getLogger().warning("mcmm failed: " + e.getMessage());
                    if (stream != null) stream.close();
                    stream = null;
                }
            }
        } else {
            player.sendMessage("[MCDATA] Ecran " + w + "x" + h + " pose (pas de source video).");
        }

        sessions.put(player.getUniqueId(), new Session(screen, blocks, stream, reader, player.getUniqueId()));
    }

    // Thread de lecture : lit les frames mcmm et les pousse a l'ecran au fps annonce, avec des
    // deadlines nanoTime pour ne pas accumuler de derive.
    private void readLoop(McmmStream stream, VirtualScreen screen, Player player) {
        long frameNanos = 1_000_000_000L / Math.max(1, stream.getFps());
        long nextDeadline = System.nanoTime();
        try {
            byte[][] frame;
            while ((frame = stream.nextFrame()) != null) {
                screen.sendFrame(frame);
                nextDeadline += frameNanos;
                long sleep = nextDeadline - System.nanoTime();
                if (sleep > 0) {
                    LockSupport.parkNanos(sleep);
                } else {
                    nextDeadline = System.nanoTime(); // on a pris du retard, on resynchronise
                }
                if (!player.isOnline()) break;
            }
        } catch (IOException e) {
            if (player.isOnline()) {
                player.sendMessage("[MCDATA] Video terminee ou interrompue (" + e.getMessage() + ").");
            }
        } finally {
            stream.close();
        }
    }

    @Override
    public void start(Player player) {
        play(player, null, "normal", null);
    }

    @Override
    public void stop(Player player) {
        Session s = sessions.remove(player.getUniqueId());
        if (s == null) return;
        if (s.stream() != null) s.stream().close();
        if (s.reader() != null) s.reader().interrupt();
        s.screen().destroy();
        s.blocks().clear(player);
        plugin.getPostEffects().clear(player);
        player.sendMessage("[MCDATA] video off.");
    }

    @Override
    public void onPlayerQuit(Player player) {
        Session s = sessions.remove(player.getUniqueId());
        if (s != null) {
            if (s.stream() != null) s.stream().close();
            if (s.reader() != null) s.reader().interrupt();
            s.screen().destroy();
        }
        plugin.getPostEffects().clear(player);
    }

    private static double screenDistance(int w, int h) {
        return Math.max(2, 1.5 * Math.max(w / 2.489, h / 1.400));
    }
}
