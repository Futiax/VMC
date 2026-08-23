package ovh.futiax.minecraftvideo;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Stream;

// Enregistre un direct (Twitch, YouTube live, une URL HLS nue...) en TRANCHES de fichiers
// locaux, que la playlist enchaine ensuite comme des videos ordinaires.
//
// POURQUOI ce detour plutot que donner l'URL du direct a mcmm : mcmm et l'ffmpeg audio ouvrent
// deux connexions INDEPENDANTES a la source. Sur un fichier, elles se positionnent au meme
// endroit et tout va bien. Sur un direct, elles rejoignent le flux a des segments differents,
// et l'ecart -- de zero a une duree de segment, tire au sort a chaque lecture -- est un
// decalage son/image permanent que rien en aval ne peut mesurer. Une tranche TERMINEE est un
// fichier ordinaire : le probleme n'existe plus, et absolument rien en aval n'a besoin de
// savoir qu'on regarde un direct. Pas de format de flux special, pas d'horloge partagee.
//
// PRIX ASSUME, les deux faces du meme reglage (stream-segment-seconds) :
//   - la latence vaut au moins une tranche (on ne peut jouer la n-ieme qu'une fois la
//     suivante commencee, seul indice fiable qu'elle est complete) ;
//   - chaque raccord coute un demarrage de session (spawn mcmm + ffmpeg + gel de premiere
//     frame), soit une couture d'environ une seconde. Trancher court, c'est begayer souvent.
public final class LiveStream {

    private static final int WATCH_TICKS = 20;              // un coup d'oeil au dossier par seconde
    private static final String EXTENSION = ".ts";          // MPEG-TS : se coupe proprement en -c copy

    private final MinecraftVideoPlugin plugin;
    private final String source;                            // ce que le joueur a tape, pour l'affichage
    private final UUID initiatorId;
    private final String initiatorName;
    private final Location anchor;
    private final Path dir;
    private final int segmentSeconds;
    private final int maxBacklog;

    private Process ffmpeg;
    private BukkitTask watcher;
    private final Set<String> enqueued = new HashSet<>();     // tranches deja vues (main thread)
    // Tranches pretes a jouer. Rempli par le main (poll), vide par le thread de lecture
    // (nextSlice) -- d'ou le deque concurrent.
    private final ConcurrentLinkedDeque<String> ready = new ConcurrentLinkedDeque<>();
    private volatile String handedOut;                        // voir nextSlice()
    private volatile boolean stopped;

    public LiveStream(MinecraftVideoPlugin plugin, String source, Player initiator) {
        this.plugin = plugin;
        this.source = source;
        this.initiatorId = initiator.getUniqueId();
        this.initiatorName = initiator.getName();
        this.anchor = initiator.getLocation().clone();
        this.dir = plugin.getDataFolder().toPath().resolve("stream");
        this.segmentSeconds = Math.max(5, plugin.getConfig().getInt("stream-segment-seconds", 30));
        this.maxBacklog = Math.max(1, plugin.getConfig().getInt("stream-max-backlog", 3));
    }

    public String getSource() {
        return source;
    }

    public int getSegmentSeconds() {
        return segmentSeconds;
    }

    // Lance le decoupage. mediaUrl est l'URL que ffmpeg doit ouvrir (deja resolue par yt-dlp
    // si la source etait une page). Main thread : le process demarre vite, il ne lit rien.
    public void start(String mediaUrl) throws IOException {
        wipe();
        Files.createDirectories(dir);
        // -c copy : aucun reencodage, on ne fait que redecouper le flux tel qu'il arrive.
        // -reset_timestamps : chaque tranche repart de zero, donc chaque fichier est une video
        // autonome ordinaire -- c'est tout l'interet de l'operation.
        List<String> cmd = List.of(
                plugin.getConfig().getString("ffmpeg-path", "ffmpeg"),
                "-hide_banner", "-loglevel", "error",
                "-i", mediaUrl,
                "-c", "copy",
                "-f", "segment",
                "-segment_time", Integer.toString(segmentSeconds),
                "-segment_format", "mpegts",
                "-reset_timestamps", "1",
                dir.resolve("%05d" + EXTENSION).toString());
        ffmpeg = new ProcessBuilder(cmd).start();
        drainStderr();
        watcher = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::poll, WATCH_TICKS, WATCH_TICKS);
    }

    // Main thread, une fois par seconde. Le dossier contient une poignee de fichiers, donc le
    // lister ici coute moins cher que la machinerie qu'un thread a part demanderait.
    private void poll() {
        if (stopped) {
            return;
        }
        if (!ffmpeg.isAlive()) {
            plugin.getLogger().warning("Le decoupage du direct s'est arrete (ffmpeg exit "
                    + ffmpeg.exitValue() + ")");
            plugin.stopLiveStream("le flux s'est interrompu");
            return;
        }
        List<Path> segments = listSegments();
        // La DERNIERE tranche est celle que ffmpeg ecrit en ce moment : on ne l'enfile pas.
        // Qu'une suivante existe est le seul indice fiable qu'une tranche est complete.
        for (int i = 0; i < segments.size() - 1; i++) {
            Path segment = segments.get(i);
            if (enqueued.add(segment.getFileName().toString())) {
                ready.addLast(segment.toString());
            }
        }
        trimBacklog();
        startPlaybackIfIdle();
        prune(segments);
    }

    // Un direct n'attend personne : si le rendu ne tient pas le temps reel, la file s'allonge
    // sans fin et on regarde le passe. On jette les plus vieilles pour se recaler sur le
    // direct -- c'est ce que fait n'importe quel lecteur de streaming.
    private void trimBacklog() {
        int dropped = 0;
        while (ready.size() > maxBacklog && ready.pollFirst() != null) {
            dropped++;
        }
        if (dropped > 0) {
            plugin.getLogger().info("Direct en retard : " + dropped
                    + " tranche(s) sautee(s) pour se recaler.");
        }
    }

    // La tranche suivante, ou null si aucune n'est prete. Appele par le THREAD DE LECTURE
    // entre deux tranches (d'ou le deque concurrent), jamais par le main.
    private String nextSlice() {
        String slice = ready.pollFirst();
        if (slice != null) {
            // Elle a quitte "ready" mais la session ne l'annonce pas encore dans playSource :
            // sans cette trace, prune() la verrait orpheline et l'effacerait juste avant que
            // mcmm ne l'ouvre.
            handedOut = slice;
        }
        return slice;
    }

    // Demarre l'UNIQUE session du direct. Les tranches suivantes lui arrivent par
    // setLiveSource, donc l'ecran n'est cree qu'une fois et survit a tous les raccords.
    private void startPlaybackIfIdle() {
        if (plugin.getActiveSession() != null || ready.isEmpty()) {
            return;
        }
        String mcmmPath = plugin.resolveMcmmPath();
        String palettePath = plugin.resolvePalettePath();
        if (mcmmPath == null || palettePath == null) {
            plugin.getLogger().warning("Direct impossible : mcmm ou palette introuvable.");
            plugin.stopLiveStream("mcmm ou palette introuvable");
            return;
        }
        String slice = ready.pollFirst();
        PlaybackSession session = new PlaybackSession(plugin, initiatorId, initiatorName,
                anchor.clone(), mcmmPath, palettePath, slice,
                plugin.getConfig().getInt("default-width", 4),
                plugin.getConfig().getInt("default-height", 3),
                plugin.getConfig().getInt("default-fps", 10),
                plugin.buildAudioSettings());
        session.setLiveSource(this::nextSlice);
        if (!plugin.trySetActiveSession(session)) {
            ready.addFirst(slice);  // course perdue contre un /video play, on retentera
            return;
        }
        session.start(plugin.getServer().getOnlinePlayers());
    }

    // Supprime les tranches dont plus personne n'a besoin : ni en file, ni en train de jouer,
    // ni en cours d'ecriture. Sans ca le dossier grossit pendant toute la duree du direct.
    private void prune(List<Path> segments) {
        PlaybackSession active = plugin.getActiveSession();
        // getPlaySource et pas getSource : la session porte la PREMIERE tranche comme source,
        // mais lit celle-ci. Se tromper ici, c'est effacer le fichier en cours de lecture.
        String playing = active != null ? active.getPlaySource() : null;
        for (int i = 0; i < segments.size() - 1; i++) {
            Path segment = segments.get(i);
            String path = segment.toString();
            if (path.equals(playing) || path.equals(handedOut) || ready.contains(path)
                    || !enqueued.contains(segment.getFileName().toString())) {
                continue;
            }
            try {
                Files.deleteIfExists(segment);
            } catch (IOException ignored) {
                // encore ouvert quelque part, on retentera au prochain tour
            }
        }
    }

    private List<Path> listSegments() {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(EXTENSION))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public void stop() {
        stopped = true;
        if (watcher != null) {
            watcher.cancel();
            watcher = null;
        }
        if (ffmpeg != null) {
            ffmpeg.destroyForcibly();
            ffmpeg = null;
        }
        wipe();
    }

    private void wipe() {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // une tranche encore ouverte par mcmm : la purge du prochain start l'aura
                }
            }
        } catch (IOException ignored) {
        }
    }

    // stderr de ffmpeg -> log serveur, sur un thread daemon (un pipe plein ferait deadlock)
    private void drainStderr() {
        Process process = ffmpeg;
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    plugin.getLogger().warning("[stream] " + line);
                }
            } catch (IOException ignored) {
                // process tue ou stream ferme
            }
        }, "MinecraftVideo-stream-ffmpeg");
        thread.setDaemon(true);
        thread.start();
    }

    // etat du direct pour /stream status
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("Direct: " + PlaylistManager.shorten(source));
        lines.add("Tranches de " + segmentSeconds + " s, " + ready.size()
                + " en attente (max " + maxBacklog + ")");
        return lines;
    }
}
