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
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Pont minimal et passif vers le demon C (mcmm --daemon).
//
// En mode demon, Java ne gere plus ni la geometrie de l'ecran, ni le diff des tuiles, ni la
// dette reseau (missed), ni l'horloge FPS, ni le seek/pause : il se contente d'envoyer les
// capteurs (positions, isWritable, commandes) sur le stdin de mcmm et d'executer les ordres
// de paquets recus sur son stdout.
public final class McmmDaemon {

    // Java -> C (stdin)
    private static final int EVT_JOIN     = 0x01;
    private static final int EVT_LEAVE    = 0x02;
    private static final int EVT_STATES   = 0x03;
    private static final int EVT_CMD      = 0x04;
    private static final int EVT_WRITABLE = 0x06;

    // C -> Java (stdout)
    private static final int OP_SPAWN_SCREEN  = 0x10;
    private static final int OP_TILES_BUNDLE  = 0x11;
    private static final int OP_DESTROY       = 0x12;
    private static final int OP_CHAT          = 0x15;
    private static final int OP_POSTEFFECT    = 0x16;
    private static final int OP_FRAME_STATS   = 0x17;
    private static final int OP_SESSION_START = 0x18;
    private static final int OP_SESSION_END   = 0x19;

    private static final int MAX_PLAYERS  = 64;
    private static final int CONSOLE_SLOT = 0xFF;
    private static final int TILE_BYTES   = 128 * 128;
    private static final byte FLAG_INVISIBLE = 0x20;

    private final MinecraftVideoPlugin plugin;
    private final Object writeLock = new Object();

    private Process process;
    private DataOutputStream stdin;
    private BukkitTask stateTask;

    // Suivi de la session active en C pour /vmc debug (CSV)
    private DebugTimings timings;
    private volatile String lastRequestedSource = "";
    private volatile String activePlaySource = "";
    private volatile String activeRender = "normal";
    private volatile String activeWall = "wan";
    private volatile int activeW = 4;
    private volatile int activeH = 3;
    private volatile int activeFps = 10;
    private volatile long lastSendNanos;

    // Table de correspondance Slot (0..63) <-> Joueur
    private final UUID[] slotToUuid = new UUID[MAX_PLAYERS];
    private final Map<UUID, Integer> uuidToSlot = new ConcurrentHashMap<>();

    public McmmDaemon(MinecraftVideoPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    // Demarre le demon C s'il ne tourne pas deja.
    public synchronized boolean start() {
        if (isRunning()) {
            return true;
        }
        String mcmmPath = plugin.resolveMcmmPath();
        String palettePath = plugin.resolvePalettePath();
        if (mcmmPath == null || palettePath == null) {
            plugin.getLogger().warning("[C-Daemon] mcmm ou palette introuvable, impossible de demarrer.");
            return false;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(mcmmPath, "--daemon", "--palette", palettePath);
            // Evite que les 3 threads secondaires de libgomp fassent du busy-wait (spin) pendant
            // 30 ms entre deux frames quand le thread principal dort dans poll() : 1000 iterations
            // (~quelques us) suffisent a enchainer les 4 boucles OpenMP d'une meme frame.
            pb.environment().putIfAbsent("GOMP_SPINCOUNT", "1000");
            process = pb.start();
        } catch (IOException e) {
            plugin.getLogger().warning("[C-Daemon] Echec du lancement de mcmm --daemon : " + e.getMessage());
            process = null;
            return false;
        }

        stdin = new DataOutputStream(new BufferedOutputStream(process.getOutputStream(), 8192));
        startStderrThread(process);
        startStdoutThread(process);

        // Synchronise les reglages courants de config.yml vers le demon C
        syncConfigToDaemon();

        // Enregistre les joueurs deja connectes
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            onPlayerJoin(player);
        }

        // Envoie la position et l'etat writable des joueurs a chaque tick (20x/s)
        stateTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::sendStatesTick, 1L, 1L);

        plugin.getLogger().info("[C-Daemon] Processus mcmm --daemon demarre (PID " + process.pid() + ").");
        return true;
    }

    void syncConfigToDaemon() {
        int w = plugin.getConfig().getInt("default-width", 4);
        int h = plugin.getConfig().getInt("default-height", 3);
        int fps = plugin.getConfig().getInt("default-fps", 10);
        String render = RenderMode.fromConfig(plugin.getConfig()).configName();
        String wall = RenderMode.DataWall.fromConfig(plugin.getConfig()).configName();
        sendCmdPacket(CONSOLE_SLOT, 0, 0, 0, 0, 0f, "vmc size " + w + " " + h);
        sendCmdPacket(CONSOLE_SLOT, 0, 0, 0, 0, 0f, "vmc fps " + fps);
        sendCmdPacket(CONSOLE_SLOT, 0, 0, 0, 0, 0f, "vmc render " + render);
        sendCmdPacket(CONSOLE_SLOT, 0, 0, 0, 0, 0f, "vmc data " + wall);
    }

    public synchronized void stop() {
        closeTimings();
        if (stateTask != null) {
            stateTask.cancel();
            stateTask = null;
        }
        if (stdin != null) {
            try {
                stdin.close();
            } catch (IOException ignored) {
            }
            stdin = null;
        }
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
        uuidToSlot.clear();
        for (int i = 0; i < MAX_PLAYERS; i++) {
            slotToUuid[i] = null;
        }
        plugin.getPostEffects().clearAll();
    }

    // Alloue un slot 0..63 au joueur et notifie le C.
    public void onPlayerJoin(Player player) {
        if (!isRunning()) return;
        int slot = allocateSlot(player.getUniqueId());
        if (slot < 0) return;

        byte[] nameBytes = player.getName().getBytes(StandardCharsets.UTF_8);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(3 + nameBytes.length);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(slot);
            out.writeShort(nameBytes.length);
            out.write(nameBytes);
            sendPacket(EVT_JOIN, bos.toByteArray());
        } catch (IOException ignored) {
        }
    }

    public void onPlayerQuit(Player player) {
        Integer slot = uuidToSlot.remove(player.getUniqueId());
        if (slot == null) return;
        synchronized (this) {
            slotToUuid[slot] = null;
        }
        if (!isRunning()) return;
        sendPacket(EVT_LEAVE, new byte[] { slot.byteValue() });
    }

    // Transmet une commande (/video ou /vmc) directement au demon C.
    // Si c'est un "/video play <url>" cachable (YouTube / HTTP), MediaCache la resout d'abord
    // en tache de fond en fichier local avant d'envoyer la commande au C.
    public void forwardCommand(CommandSender sender, String rootCmd, String[] args) {
        if (!isRunning() && !start()) {
            sender.sendMessage("[MinecraftVideo] C-Daemon could not be started.");
            return;
        }

        int slot = CONSOLE_SLOT;
        int worldHash = 0;
        double x = 0, y = 0, z = 0;
        float yaw = 0f;
        if (sender instanceof Player player) {
            slot = allocateSlot(player.getUniqueId());
            if (slot < 0) {
                sender.sendMessage("[MinecraftVideo] Too many players for C-Daemon.");
                return;
            }
            Location loc = player.getLocation();
            worldHash = loc.getWorld() != null ? loc.getWorld().getUID().hashCode() : 0;
            x = loc.getX();
            y = loc.getY();
            z = loc.getZ();
            yaw = loc.getYaw();
        }

        // Si /video play <url> vise une URL distante / YouTube, on utilise MediaCache en async
        if (rootCmd.equalsIgnoreCase("video") && args.length >= 2
                && args[0].equalsIgnoreCase("play")) {
            String rawSource = VideoCommand.stripSurroundingQuotes(args[1]);
            lastRequestedSource = rawSource;
            if (plugin.getMediaCache().isCacheable(rawSource)) {
                sender.sendMessage("[MinecraftVideo] [C-Daemon] Fetching remote source...");
                final int fSlot = slot;
                final int fWorld = worldHash;
                final double fx = x, fy = y, fz = z;
                final float fYaw = yaw;
                final String[] copyArgs = args.clone();
                plugin.getMediaCache().reference(rawSource);
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        String localPath = plugin.getMediaCache().acquire(rawSource, () -> !isRunning());
                        copyArgs[1] = "\"" + localPath + "\"";
                        sendCmdPacket(fSlot, fWorld, fx, fy, fz, fYaw, buildCmdLine(rootCmd, copyArgs));
                    } catch (IOException e) {
                        plugin.getMediaCache().release(rawSource);
                        sender.sendMessage("[MinecraftVideo] Download failed: " + e.getMessage());
                    }
                });
                return;
            }
        }

        sendCmdPacket(slot, worldHash, x, y, z, yaw, buildCmdLine(rootCmd, args));
    }

    private static String buildCmdLine(String root, String[] args) {
        if (args.length == 0) {
            return root;
        }
        StringBuilder sb = new StringBuilder(root);
        for (String a : args) {
            sb.append(' ');
            if (a.indexOf(' ') >= 0 && !a.startsWith("\"")) {
                sb.append('"').append(a).append('"');
            } else {
                sb.append(a);
            }
        }
        return sb.toString();
    }

    private void sendCmdPacket(int slot, int worldHash, double x, double y, double z,
                               float yaw, String cmdLine) {
        byte[] cmdBytes = cmdLine.getBytes(StandardCharsets.UTF_8);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(35 + cmdBytes.length);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(slot);
            out.writeInt(worldHash);
            out.writeDouble(x);
            out.writeDouble(y);
            out.writeDouble(z);
            out.writeFloat(yaw);
            out.writeShort(cmdBytes.length);
            out.write(cmdBytes);
            sendPacket(EVT_CMD, bos.toByteArray());
        } catch (IOException ignored) {
        }
    }

    private void sendStatesTick() {
        if (!isRunning()) return;
        var online = plugin.getServer().getOnlinePlayers();
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 + online.size() * 34);
            DataOutputStream out = new DataOutputStream(bos);
            int count = 0;
            ByteArrayOutputStream entries = new ByteArrayOutputStream(online.size() * 34);
            DataOutputStream eOut = new DataOutputStream(entries);

            for (Player p : online) {
                int slot = allocateSlot(p.getUniqueId());
                if (slot < 0) continue;
                Location loc = p.getLocation();
                int worldHash = loc.getWorld() != null ? loc.getWorld().getUID().hashCode() : 0;
                eOut.writeByte(slot);
                eOut.writeInt(worldHash);
                eOut.writeByte(isWritable(p) ? 1 : 0);
                eOut.writeDouble(loc.getX());
                eOut.writeDouble(loc.getY());
                eOut.writeDouble(loc.getZ());
                eOut.writeFloat(loc.getYaw());
                count++;
            }
            out.writeByte(count);
            out.write(entries.toByteArray());
            sendPacket(EVT_STATES, bos.toByteArray());
        } catch (IOException ignored) {
        }
    }

    private static boolean isWritable(Player viewer) {
        Object channel = PacketEvents.getAPI().getPlayerManager().getChannel(viewer);
        return !(channel instanceof Channel netty) || netty.isWritable();
    }

    private synchronized int allocateSlot(UUID uuid) {
        Integer existing = uuidToSlot.get(uuid);
        if (existing != null) {
            return existing;
        }
        for (int i = 0; i < MAX_PLAYERS; i++) {
            if (slotToUuid[i] == null) {
                slotToUuid[i] = uuid;
                uuidToSlot.put(uuid, i);
                return i;
            }
        }
        return -1;
    }

    private Player playerAtSlot(int slot) {
        if (slot < 0 || slot >= MAX_PLAYERS) return null;
        UUID uuid = slotToUuid[slot];
        return uuid != null ? plugin.getServer().getPlayer(uuid) : null;
    }

    private void sendPacket(int opcode, byte[] payload) {
        synchronized (writeLock) {
            if (stdin == null) return;
            try {
                stdin.writeByte(opcode);
                stdin.writeInt(payload.length);
                stdin.write(payload);
                stdin.flush();
            } catch (IOException e) {
                // Le process C s'est arrete
            }
        }
    }

    private void startStderrThread(Process proc) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    plugin.getLogger().info("[mcmm] " + line);
                }
            } catch (IOException ignored) {
            }
        }, "MinecraftVideo-daemon-stderr");
        t.setDaemon(true);
        t.start();
    }

    private void startStdoutThread(Process proc) {
        Thread t = new Thread(() -> {
            DataInputStream in = new DataInputStream(proc.getInputStream());
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    int opcode = in.readUnsignedByte();
                    int len = in.readInt();
                    handleDaemonInstruction(opcode, len, in);
                }
            } catch (EOFException ignored) {
                plugin.getLogger().info("[C-Daemon] Flux stdout ferme.");
            } catch (IOException e) {
                if (isRunning()) {
                    plugin.getLogger().warning("[C-Daemon] Erreur lecture stdout : " + e.getMessage());
                }
            }
        }, "MinecraftVideo-daemon-stdout");
        t.setDaemon(true);
        t.start();
    }

    private void handleDaemonInstruction(int opcode, int len, DataInputStream in) throws IOException {
        switch (opcode) {
            case OP_SPAWN_SCREEN -> {
                int slot = in.readUnsignedByte();
                float frameYaw = in.readFloat();
                int dirData = in.readInt();
                int count = in.readUnsignedShort();
                Player viewer = playerAtSlot(slot);
                PlayerManager players = PacketEvents.getAPI().getPlayerManager();
                for (int i = 0; i < count; i++) {
                    int entityId = in.readInt();
                    int mapId = in.readInt();
                    double x = in.readDouble();
                    double y = in.readDouble();
                    double z = in.readDouble();
                    if (viewer != null && viewer.isOnline()) {
                        WrapperPlayServerSpawnEntity spawn = new WrapperPlayServerSpawnEntity(
                                entityId, Optional.of(UUID.randomUUID()),
                                EntityTypes.GLOW_ITEM_FRAME,
                                new Vector3d(x, y, z),
                                0f, frameYaw, frameYaw, dirData, Optional.empty());
                        players.sendPacket(viewer, spawn);

                        ItemStack map = ItemStack.builder()
                                .type(ItemTypes.FILLED_MAP)
                                .amount(1)
                                .component(ComponentTypes.MAP_ID, mapId)
                                .build();
                        List<EntityData<?>> data = new ArrayList<>(2);
                        data.add(new EntityData<>(MetadataIndices.ENTITY_FLAGS,
                                EntityDataTypes.BYTE, FLAG_INVISIBLE));
                        data.add(new EntityData<>(MetadataIndices.ITEM_FRAME_ITEM,
                                EntityDataTypes.ITEMSTACK, map));
                        players.sendPacket(viewer, new WrapperPlayServerEntityMetadata(entityId, data));
                    }
                }
            }
            case OP_TILES_BUNDLE -> {
                long viewerMask = in.readLong();
                int count = in.readUnsignedShort();
                List<WrapperPlayServerMapData> packets = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    int mapId = in.readInt();
                    byte[] pixels = new byte[TILE_BYTES];
                    in.readFully(pixels);
                    packets.add(new WrapperPlayServerMapData(
                            mapId, (byte) 0, false, true, Collections.emptyList(),
                            128, 128, 0, 0, pixels));
                }
                long sendStart = System.nanoTime();
                PlayerManager players = PacketEvents.getAPI().getPlayerManager();
                for (int s = 0; s < MAX_PLAYERS; s++) {
                    if ((viewerMask & (1L << s)) == 0) continue;
                    Player viewer = playerAtSlot(s);
                    if (viewer == null || !viewer.isOnline()) continue;
                    players.writePacket(viewer, new WrapperPlayServerBundle());
                    for (WrapperPlayServerMapData pkt : packets) {
                        players.writePacket(viewer, pkt);
                    }
                    players.sendPacket(viewer, new WrapperPlayServerBundle());
                    // Mise a jour immediate de writable vers le C apres le flush du bundle
                    sendPacket(EVT_WRITABLE, new byte[] { (byte) s, (byte) (isWritable(viewer) ? 1 : 0) });
                }
                lastSendNanos += System.nanoTime() - sendStart;
            }
            case OP_SESSION_START -> {
                closeTimings();
                activeW = in.readUnsignedShort();
                activeH = in.readUnsignedShort();
                activeFps = in.readUnsignedShort();
                int rmode = in.readUnsignedByte();
                int wall = in.readUnsignedByte();
                int slen = in.readUnsignedShort();
                byte[] sbytes = new byte[slen];
                in.readFully(sbytes);
                activePlaySource = new String(sbytes, StandardCharsets.UTF_8);
                activeRender = switch (rmode) {
                    case 1 -> "dither";
                    case 2 -> "strict";
                    case 3 -> "data";
                    default -> "normal";
                };
                activeWall = wall == 1 ? "lan" : "wan";
                lastSendNanos = 0;
                timings = new DebugTimings();
            }
            case OP_SESSION_END -> {
                closeTimings();
            }
            case OP_FRAME_STATS -> {
                int[] pkt = new int[20];
                for (int i = 0; i < 20; i++) {
                    pkt[i] = in.readInt();
                }
                long sendNanos = lastSendNanos;
                lastSendNanos = 0;
                logDaemonTimings(pkt, sendNanos);
            }
            case OP_DESTROY -> {
                long viewerMask = in.readLong();
                int count = in.readUnsignedShort();
                int[] ids = new int[count];
                for (int i = 0; i < count; i++) {
                    ids[i] = in.readInt();
                }
                PlayerManager players = PacketEvents.getAPI().getPlayerManager();
                for (int s = 0; s < MAX_PLAYERS; s++) {
                    if ((viewerMask & (1L << s)) == 0) continue;
                    Player viewer = playerAtSlot(s);
                    if (viewer != null && viewer.isOnline()) {
                        players.sendPacket(viewer, new WrapperPlayServerDestroyEntities(ids.clone()));
                    }
                }
            }
            case OP_CHAT -> {
                int slot = in.readUnsignedByte();
                int mlen = in.readUnsignedShort();
                byte[] bytes = new byte[mlen];
                in.readFully(bytes);
                String msg = new String(bytes, StandardCharsets.UTF_8);
                if (slot == CONSOLE_SLOT) {
                    plugin.getLogger().info(msg);
                } else {
                    Player p = playerAtSlot(slot);
                    if (p != null && p.isOnline()) {
                        p.sendMessage(msg);
                    }
                }
            }
            case OP_POSTEFFECT -> {
                int slot = in.readUnsignedByte();
                int enable = in.readUnsignedByte();
                int elen = in.readUnsignedShort();
                byte[] bytes = new byte[elen];
                in.readFully(bytes);
                String effect = new String(bytes, StandardCharsets.UTF_8);
                Player p = playerAtSlot(slot);
                if (p != null && p.isOnline()) {
                    if (enable != 0) {
                        plugin.getPostEffects().apply(p, effect);
                    } else {
                        plugin.getPostEffects().clear(p);
                    }
                }
            }
            default -> {
                // Opcode inconnu : on consomme le payload pour garder le flux aligne
                in.skipBytes(len);
            }
        }
    }

    private synchronized void logDaemonTimings(int[] pkt, long sendNanos) {
        if (timings == null) {
            timings = new DebugTimings();
        }
        if (plugin.isDebugTimings() && !timings.isWriting()) {
            java.nio.file.Path file = DebugTimings.newFile(plugin.getDataFolder().toPath());
            String meta = DebugTimings.formatMeta(activePlaySource, lastRequestedSource, true,
                    activeRender, activeWall, activeW, activeH, activeFps);
            try {
                timings.startWriting(file, meta);
                plugin.getLogger().info("[C-Daemon] Debug timings: writing " + file);
            } catch (IOException e) {
                plugin.getLogger().warning("[C-Daemon] Debug timings: cannot write " + file + " (" + e + ")");
                plugin.setDebugTimings(false);
            }
        } else if (!plugin.isDebugTimings()) {
            timings.close();
        }
        timings.rowDaemon(process, pkt, sendNanos);
    }

    private synchronized void closeTimings() {
        if (timings != null) {
            timings.close();
            timings = null;
        }
    }
}
