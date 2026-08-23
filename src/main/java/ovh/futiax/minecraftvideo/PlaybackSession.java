package ovh.futiax.minecraftvideo;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

// Une lecture video : possede le McmmStream (le sous-process mcmm), le VirtualScreen sur
// lequel il rend, et l'AudioPlayback optionnel.
//
// SEGMENTS. Une lecture est une suite de segments, chacun = un mcmm + un pipeline audio
// demarres a un offset temporel. Le play initial est le segment a l'offset 0 ; chaque
// /video seek tue les sous-process du segment courant et lance un nouveau segment a l'offset
// vise, en REUTILISANT le meme ecran virtuel (pas de clignotement, les viewers gardent la
// derniere image jusqu'a l'arrivee de la nouvelle).
//
// SYNC A/V (modele freeze-frame). Le chemin audio traine une latence que le chemin video n'a
// pas : le client SVC bufferise l'audio dans un jitter buffer qui GONFLE quand les paquets
// arrivent pendant que le client est occupe (le pic de rendu d'un ecran qui apparait) et qui
// ne redescend jamais, alors que les paquets map s'appliquent juste au tick client suivant.
// Donc au premier segment, la frame 0 est envoyee tout de suite puis FIGEE pendant
// audio-start-delay-ms : le client encaisse le pic de spawn pendant que l'image ne bouge pas
// et qu'aucun son ne circule. Ensuite le pacing video et l'audio partent ENSEMBLE, ancres sur
// le meme instant. L'audio ne saute que av-sync-delay-ms de contenu (-ss ffmpeg) pour
// compenser le buffering audio du client en regime etabli : rien n'est perdu a cause du gel
// lui-meme. Les segments de seek reutilisent un ecran deja spawne (pas de pic de rendu), donc
// ils sautent le gel et demarrent l'audio des qu'il est amorce. Le ffmpeg audio est lance
// AVANT la lecture bloquante du header mcmm (pre-chauffe), comme ca sur une URL les deux
// pipelines se connectent en parallele.
//
// SOUS-TITRES. Un SubtitleStream optionnel (son propre ffmpeg par segment, relance au nouvel
// offset sur seek, comme l'audio) extrait une piste texte embarquee en SRT ; la boucle de
// frames l'interroge a la position VIDEO (getPositionMillis(), donc les cues suivent l'image
// et une pause fige le texte) et pilote l'overlay de l'ecran. Choisi/coupe a chaud par
// /video subs.
//
// Un thread daemon lit les frames et les cadence au fps annonce par le header du stream, avec
// des deadlines System.nanoTime (la deadline suivante derive de la precedente, donc la gigue
// des sleeps n'accumule pas de derive).
//
// NOTE THREADING : les paquets map-data et entite sont envoyes VOLONTAIREMENT depuis ce thread
// de lecture async. Le PlayerManager#sendPacket de packetevents est thread-safe en envoi (il
// ecrit direct dans le channel netty du joueur), donc aucun besoin de passer par le main thread
// pour le trafic de paquets. Seuls les appels API Bukkit (messages de chat, comptabilite de
// session) repassent sur le main via le scheduler.
public final class PlaybackSession {

    // Attente max d'une tranche de direct avant de considerer le flux mort (voir
    // awaitLiveContinuation). Genereux : le decoupage a par nature une tranche de retard.
    private static final long LIVE_WAIT_SECONDS = 120;

    private final MinecraftVideoPlugin plugin;
    private final UUID initiatorId;
    private final String initiatorName;
    private final Location anchor; // capture sur le main thread
    private final String mcmmPath;
    private final String palettePath;
    private final String source;
    // Le chemin que les pipelines lisent VRAIMENT : le fichier de cache local pour une source
    // distante (resolu une fois dans run()), ou source pour un chemin local / avant resolution.
    // source reste l'original, pour l'affichage.
    private volatile String playSource;
    private volatile java.util.function.Supplier<String> liveSource;    // null = video ordinaire
    private volatile AudioPlayback liveAudio;   // canaux SVC persistants d'un direct
    private final int requestedWidth;
    private final int requestedHeight;
    private final int requestedFps;
    private final AudioSettings audioSettings;
    private final boolean controlBarEnabled;    // snapshots lus dans le ctor (main thread)
    private final boolean dither;               // passe a mcmm en --dither
    private final int viewerRange;              // rayon de diffusion en blocs, 0 = illimite
    private final SubtitleSettings subtitleSettings;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Object lock = new Object();
    private McmmStream stream;        // garde par lock (segment courant)
    private VirtualScreen screen;     // garde par lock (toute la session)
    private AudioPlayback audio;      // garde par lock (segment courant)
    private SubtitleStream subtitles; // garde par lock (segment courant)
    private volatile Thread playbackThread;

    // Piste de sous-titres choisie (le n du -map 0:s:n de ffmpeg), -1 = aucune. Bascule par
    // /video subs ; lue a chaque debut de segment pour (re)lancer le flux d'extraction, et par
    // la boucle de frames pour savoir s'il faut interroger les cues. Son flux d'extraction est
    // par segment (tue/relance au seek, comme l'audio), et les cues sont calees sur la VIDEO
    // (getPositionMillis()), pas sur l'audio : aucun skip av-sync ne lui est applique.
    private volatile int subtitleTrackIndex = -1;

    // Pistes de sous-titres sondees une fois par session (cachees comme sourceAudioChannels),
    // null = pas encore sonde. Remplies par /video subs list ou /video subs <n> sur une tache async.
    private volatile List<SubtitleTrack> subtitleTracks;

    // Etat de pause. Le thread de lecture se park sur pauseLock pendant la pause ; mcmm et le
    // ffmpeg audio sont freines par la backpressure de leur pipe/file en attendant.
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final Object pauseLock = new Object();

    // demande de seek en millis, -1 = aucune. Consommee une fois par changement de segment.
    private final AtomicLong pendingSeekMillis = new AtomicLong(-1);

    // Metriques de vitesse de generation du segment COURANT : ecrites par le thread de lecture,
    // lues (en race, c'est tres bien pour un affichage de statut) par /video status sur le main.
    private volatile long playStartNanos;    // 0 tant que la boucle de frames du segment n'a pas demarre
    private volatile long pausedAccumNanos;  // temps total passe en pause (ce segment)
    private volatile long pauseStartNanos;   // != 0 pendant qu'on est en pause
    private volatile long framesSent;        // frames envoyees ce segment
    private volatile long totalDecodeNanos;  // temps cumule bloque dans nextFrame()
    private volatile long totalSendNanos;    // temps cumule dans sendFrame()
    private volatile int streamFps;
    private volatile int screenW;
    private volatile int screenH;
    private volatile long segmentOffsetMillis;  // ou (en millis dans la video) le segment a commence

    // Nombre de canaux audio de la source, sonde une fois par session pour la decision d'upmix
    // surround et reutilise entre les segments de seek : 0 = pas encore sonde, -1 = sondage rate
    // (le surround retombe sur le mapping 5.1 direct).
    private volatile int sourceAudioChannels;

    public PlaybackSession(MinecraftVideoPlugin plugin, Player initiator,
                           String mcmmPath, String palettePath, String source,
                           int width, int height, int fps, AudioSettings audioSettings) {
        this(plugin, initiator.getUniqueId(), initiator.getName(),
                initiator.getLocation().clone(), mcmmPath, palettePath, source,
                width, height, fps, audioSettings);
    }

    // Variante avec ancre explicite, pour la playlist : un item en file garde l'ecran la ou la
    // video precedente jouait, et celui qui l'a mis en file peut tres bien etre deconnecte au
    // moment ou il demarre.
    public PlaybackSession(MinecraftVideoPlugin plugin, UUID initiatorId,
                           String initiatorName, Location anchor,
                           String mcmmPath, String palettePath, String source,
                           int width, int height, int fps, AudioSettings audioSettings) {
        this.plugin = plugin;
        this.initiatorId = initiatorId;
        this.initiatorName = initiatorName;
        this.anchor = anchor;
        this.mcmmPath = mcmmPath;
        this.palettePath = palettePath;
        this.source = source;
        this.playSource = source;
        this.requestedWidth = width;
        this.requestedHeight = height;
        this.requestedFps = fps;
        this.audioSettings = audioSettings;
        // Les sessions sont construites sur le main thread (commande ou advance de playlist),
        // donc lire la config vivante ici ne pose pas de probleme.
        this.controlBarEnabled = plugin.getConfig().getBoolean("control-bar-enabled", true);
        this.dither = plugin.getConfig().getBoolean("dither", false);
        this.viewerRange = plugin.getConfig().getInt("viewer-range-blocks", 64);
        this.subtitleSettings = plugin.buildSubtitleSettings();
    }

    // le fichier reellement lu en ce moment (une tranche de direct, ou la source resolue)
    public String getPlaySource() {
        return playSource;
    }

    // Enchainement de DIRECT : appele quand un fichier se termine, doit rendre le suivant, ou
    // null si le direct est fini. Rendre null alors qu'une tranche est simplement en retard
    // terminerait la session -- donc detruirait l'ecran -- pour rien : le fournisseur attend.
    //
    // C'est tout le mecanisme qui evite le respawn d'ecran entre deux tranches : la boucle de
    // segments existe deja pour les seeks et REUTILISE le VirtualScreen. Une tranche suivante
    // n'est qu'un segment de plus, avec un fichier different.
    public void setLiveSource(java.util.function.Supplier<String> supplier) {
        this.liveSource = supplier;
    }

    // Demarre la lecture sur un thread daemon pour ces viewers initiaux (snapshot pris sur le main).
    public void start(Collection<? extends Player> initialViewers) {
        List<Player> viewers = List.copyOf(initialViewers);
        Thread thread = new Thread(() -> run(viewers), "MinecraftVideo-Playback");
        thread.setDaemon(true);
        this.playbackThread = thread;
        thread.start();
    }

    // Ajoute un late joiner : renvoie spawn, metadata et derniere frame.
    //
    // Sous lock pour que ca ne puisse pas s'entrelacer avec un stop()/destroy() concurrent :
    // soit le viewer est ajoute avant destroy() (et recoit ses paquets remove), soit stopped
    // est deja pose et on ne fait rien. Sinon un joiner pourrait recevoir les paquets de spawn
    // APRES le destroy() et se retrouver avec des frames fantomes jusqu'a la reconnexion.
    public void addViewer(Player player) {
        synchronized (lock) {
            if (stopped.get() || screen == null) {
                return;
            }
            screen.addViewer(player);
        }
    }

    // Reevalue qui est a portee de l'ecran. Meme verrou que addViewer, meme raison.
    public void refreshViewers(Collection<? extends Player> online) {
        synchronized (lock) {
            if (stopped.get() || screen == null) {
                return;
            }
            screen.refreshViewers(online);
        }
    }

    // Arrete la lecture : tue le process mcmm et retire l'ecran chez tous les viewers.
    // Idempotent, appelable depuis n'importe quel thread.
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        synchronized (lock) {
            if (stream != null) {
                stream.close(); // destroyForcibly du process
            }
            if (screen != null) {
                screen.destroy();
            }
            if (audio != null) {
                audio.stop();
            }
            liveAudio = null;   // invariant : liveAudio ne designe jamais un playback arrete
            if (subtitles != null) {
                subtitles.close();
                subtitles = null;
            }
        }
        Thread thread = playbackThread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
        plugin.clearSession(this);
    }

    // Attend jusqu'a timeoutMillis que le thread de lecture se termine. Appele depuis
    // onDisable pour qu'aucun paquet ne parte apres l'arret de packetevents et la fermeture
    // du class loader du plugin.
    public void join(long timeoutMillis) {
        Thread thread = playbackThread;
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isStopped() {
        return stopped.get();
    }

    public boolean isPaused() {
        return paused.get();
    }

    public String getInitiatorName() {
        return initiatorName;
    }

    // ancre de l'ecran (position + orientation), clonee : sert a mettre en file au meme endroit
    public Location getAnchor() {
        return anchor.clone();
    }

    // La barre de controle de l'ecran actif, null tant qu'il n'y a pas d'ecran ou si la barre
    // est desactivee. Appelable depuis n'importe quel thread (ControlBarListener s'en sert sur
    // les threads netty pour matcher les ids cliques) : la section critique est une simple
    // lecture de reference.
    public ControlBar getControlBar() {
        synchronized (lock) {
            return screen != null ? screen.getControlBar() : null;
        }
    }

    // Met video et audio en pause. L'ecran continue d'afficher la derniere image ; mcmm et le
    // ffmpeg audio bloquent sur leur pipe/file une fois les buffers pleins, donc rien ne decode
    // en avance. Renvoie false si deja en pause ou stoppe.
    public boolean pause() {
        if (stopped.get() || !paused.compareAndSet(false, true)) {
            return false;
        }
        AudioPlayback a;
        synchronized (lock) {
            a = audio;
        }
        if (a != null) {
            a.setPaused(true);
        }
        return true;
    }

    // reprend une lecture en pause, false si on n'etait pas en pause
    public boolean resume() {
        if (stopped.get() || !paused.compareAndSet(true, false)) {
            return false;
        }
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        AudioPlayback a;
        synchronized (lock) {
            a = audio;
        }
        if (a != null) {
            a.setPaused(false);
        }
        return true;
    }

    // position courante dans la video en millis (offset du segment + frames envoyees)
    public long getPositionMillis() {
        int fps = streamFps;
        if (fps <= 0) {
            return segmentOffsetMillis;
        }
        return segmentOffsetMillis + framesSent * 1000L / fps;
    }

    // Demande un saut a une position absolue (clampee a 0). Les sous-process du segment courant
    // sont tues, le thread de lecture repart sur un nouveau segment a la cible, sur le meme
    // ecran. Un seek implique une reprise. False si la session est deja stoppee.
    public boolean seekTo(long targetMillis) {
        if (stopped.get()) {
            return false;
        }
        pendingSeekMillis.set(Math.max(0, targetMillis));
        synchronized (lock) {
            if (stream != null) {
                stream.close(); // debloque la lecture du thread de playback
            }
            if (audio != null) {
                audio.stop();
                audio = null;
            }
            liveAudio = null;   // meme invariant qu'au stop()
            if (subtitles != null) {
                subtitles.close(); // relance au nouvel offset au segment suivant
                subtitles = null;
            }
        }
        // seek = resume : on reveille le thread de lecture s'il est parke
        if (paused.compareAndSet(true, false)) {
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        }
        return true;
    }

    // seek relatif depuis la position courante (+10 s / -10 s par exemple)
    public boolean seekBy(long deltaMillis) {
        return seekTo(getPositionMillis() + deltaMillis);
    }

    // Park le thread de lecture pendant la pause, rend une deadline toute neuve a la reprise.
    private long awaitResume(long intervalNanos) {
        synchronized (pauseLock) {
            pauseStartNanos = System.nanoTime();
            while (paused.get() && !stopped.get()) {
                try {
                    pauseLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break; // stop() nous interrompt : on laisse la boucle sortir
                }
            }
            pausedAccumNanos += System.nanoTime() - pauseStartNanos;
            pauseStartNanos = 0;
        }
        return System.nanoTime() + intervalNanos;
    }

    // Garde la premiere image (deja envoyee) a l'ecran pendant audio-start-delay-ms de temps
    // NON pause, en restant reactif au stop/seek (verifie toutes les <=50 ms) et a la pause
    // (qui rallonge le gel).
    private void freezeFirstFrame() {
        long remaining = Math.max(0, audioSettings.audioStartDelayMillis()) * 1_000_000L;
        while (remaining > 0 && !stopped.get() && pendingSeekMillis.get() < 0) {
            if (paused.get()) {
                awaitResume(0); // park jusqu'a la reprise, l'horloge du gel s'arrete
                continue;
            }
            long t = System.nanoTime();
            LockSupport.parkNanos(Math.min(remaining, 50_000_000L));
            remaining -= System.nanoTime() - t;
        }
    }

    // Park jusqu'a ce que l'audio ait au moins une frame bufferisee, pour pouvoir ancrer le
    // pacing sur un instant ou begin() joue depuis le premier sample du stream : video et audio
    // demarrent alors strictement ensemble.
    //
    // Au premier segment le gel a deja amorce l'audio, donc ca rend la main tout de suite ; son
    // vrai boulot c'est le chemin SEEK, qui n'a pas de gel. Sans ca la video cadencerait pendant
    // que ffmpeg bufferise encore, et le rattrapage par skipFrames ne recupere pas toujours ->
    // audio en retard apres chaque seek. Borne par audio-start-delay-ms et relache tot des que
    // la source se revele sans (ou sans plus) audio (eof), pour qu'une video muette ne bloque
    // jamais le seek. Reactif au stop/seek ; la pause rallonge l'attente comme pour le gel.
    private void awaitAudioPrimed(AudioPlayback ap) {
        if (ap == null) {
            return;
        }
        long remaining = Math.max(0, audioSettings.audioStartDelayMillis()) * 1_000_000L;
        while (remaining > 0 && !ap.isPrimed() && !ap.hasReachedEof()
                && !stopped.get() && pendingSeekMillis.get() < 0) {
            if (paused.get()) {
                awaitResume(0); // park jusqu'a la reprise, l'horloge d'amorcage s'arrete
                continue;
            }
            long t = System.nanoTime();
            LockSupport.parkNanos(Math.min(remaining, 20_000_000L));
            remaining -= System.nanoTime() - t;
        }
    }

    // Lignes de statut lisibles pour /video status : position et marge du pipeline de
    // generation par rapport au budget par image.
    public String[] describeStatus() {
        long frames = framesSent;
        int fps = streamFps;
        if (playStartNanos == 0 || fps == 0) {
            return new String[] { "Starting up (loading palette / building the color table)..." };
        }

        long now = System.nanoTime();
        long ps = pauseStartNanos;
        long pausedTotal = pausedAccumNanos + (ps != 0 ? now - ps : 0);
        double elapsedS = Math.max(0.0, (now - playStartNanos - pausedTotal)) / 1e9;
        double effFps = elapsedS > 0 ? frames / elapsedS : 0;
        double avgDecodeMs = frames > 0 ? totalDecodeNanos / 1e6 / frames : 0;
        double avgSendMs = frames > 0 ? totalSendNanos / 1e6 / frames : 0;
        double workMs = avgDecodeMs + avgSendMs;
        double budgetMs = 1000.0 / fps;

        String margin;
        if (workMs <= 0) {
            margin = "n/a (no frames yet)";
        } else {
            double headroom = budgetMs / workMs;
            if (headroom >= 1.0) {
                int idlePct = (int) Math.round((1.0 - workMs / budgetMs) * 100);
                margin = String.format("%.1fx headroom (%d%% idle) — plenty of room", headroom, idlePct);
            } else {
                margin = String.format("BEHIND real time (%.2fx) — generation can't keep up, lower fps/size", headroom);
            }
        }

        VirtualScreen scr = screen;
        int dropped = scr != null ? scr.getDroppedFrames() : 0;
        String network = dropped == 0
                ? "all frames delivered"
                : dropped + " frame(s) dropped on saturated connections"
                        + " — lower fps/size if this keeps climbing";

        return new String[] {
            "Source: " + shortSource() + (paused.get() ? "  [PAUSED]" : ""),
            // le dither est affiche parce qu'il se paie sur le decodage mcmm : l'oublier
            // allume, c'est chercher une regression de perf dans le mauvais processus
            "Screen: " + screenW + "x" + screenH + " maps @ " + fps + " fps, audio "
                    + audioSettings.mode().configName() + (dither ? ", dither ON" : ""),
            "Position: " + formatTimestamp(getPositionMillis())
                    + String.format(" — %d frames this segment, %.1f effective fps", frames, effFps),
            String.format("Generation: %.1f ms/frame (decode %.1f + send %.1f) vs %.1f ms budget",
                    workMs, avgDecodeMs, avgSendMs, budgetMs),
            "Margin: " + margin,
            "Network: " + network,
        };
    }

    // formate des millis en m:ss ou h:mm:ss
    public static String formatTimestamp(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    private String shortSource() {
        if (source.length() <= 60) {
            return source;
        }
        return "..." + source.substring(source.length() - 57);
    }

    private void run(List<Player> initialViewers) {
        try {
            // On resout la source UNE fois : une URL distante est telechargee dans un fichier
            // de cache local, comme ca mcmm, l'audio, ffprobe et les sous-titres lisent tous le
            // fichier local (une seule connexion, pas de re-fetch au seek). Un chemin local
            // passe tel quel. Bloquant, avorte si la session est stoppee entre temps.
            MediaCache cache = plugin.getMediaCache();
            if (cache.isCacheable(source)) {
                messageInitiator("Downloading " + shortSource() + " ...");
            }
            try {
                this.playSource = cache.acquire(source, stopped::get);
            } catch (IOException e) {
                if (!stopped.get()) {
                    // Le detail ne part QUE dans le log serveur ; le joueur recoit un message
                    // generique, sinon le statut HTTP / le resultat de connexion servirait
                    // d'oracle pour scanner le reseau interne.
                    plugin.getLogger().warning("Source unavailable (" + source + "): "
                            + e.getMessage());
                    messageInitiator("Could not load the source (see server log for details).");
                }
                return; // le finally tourne quand meme : stop() + release du cache
            }

            long offsetMillis = 0;
            boolean first = true;
            while (!stopped.get()) {
                long next = playSegment(initialViewers, offsetMillis, first);
                first = false;
                if (next < 0) {
                    // EOF : sur un direct, ce n'est pas la fin, juste la tranche suivante a
                    // prendre. On garde la session (donc l'ecran) et on repart a zero sur le
                    // nouveau fichier.
                    String continuation = awaitLiveContinuation();
                    if (continuation == null) {
                        break; // vraie fin, ou stop
                    }
                    this.playSource = continuation;
                    offsetMillis = 0;
                    continue;
                }
                offsetMillis = next;
            }
            if (!stopped.get() && liveSource == null) {
                messageInitiator("Video finished.");
            }
        } catch (IOException e) {
            if (!stopped.get()) {
                plugin.getLogger().warning("Playback failed: " + e.getMessage());
                messageInitiator("Video playback failed: " + e.getMessage());
            }
        } catch (Throwable t) {
            plugin.getLogger().severe("Unexpected playback error: " + t);
            t.printStackTrace();
        } finally {
            stop(); // arret auto en EOF ou sur erreur, idempotent
            // Release de la reference de cache de cette occurrence APRES stop(), pour que les
            // pipelines aient fini de lire le fichier avant qu'il puisse etre supprime.
            plugin.getMediaCache().release(source);
        }
    }

    // Attend la tranche suivante d'un direct. null = pas un direct, ou stop, ou le decoupage
    // ne produit plus rien. Pendant l'attente la derniere image reste affichee (on n'envoie
    // simplement plus de frame), ce qui est infiniment moins brutal qu'un ecran qui disparait.
    private String awaitLiveContinuation() {
        java.util.function.Supplier<String> supplier = liveSource;
        if (supplier == null) {
            return null;
        }
        long deadline = System.nanoTime() + LIVE_WAIT_SECONDS * 1_000_000_000L;
        while (!stopped.get() && System.nanoTime() < deadline) {
            String next = supplier.get();
            if (next != null) {
                return next;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    // Joue un segment a partir de offsetMillis. Cree l'ecran au premier segment, le reutilise
    // ensuite. Rend l'offset du segment suivant si un seek a ete demande, ou -1 quand la
    // lecture est finie (EOF ou stop). Leve une IOException sur une VRAIE panne (pas sur une
    // fermeture declenchee par un seek).
    private long playSegment(List<Player> initialViewers, long offsetMillis, boolean first)
            throws IOException {
        // remise a zero des metriques du segment (le statut montre le segment courant)
        segmentOffsetMillis = offsetMillis;
        playStartNanos = 0;
        pausedAccumNanos = 0;
        pauseStartNanos = 0;
        framesSent = 0;
        totalDecodeNanos = 0;
        totalSendNanos = 0;

        // On lance le sous-process puis on le publie AVANT la lecture bloquante du header,
        // comme ca un stop()/seekTo() concurrent peut tuer mcmm et nous debloquer.
        McmmStream newStream = new McmmStream(plugin.getLogger(),
                mcmmPath, palettePath, playSource,
                requestedWidth, requestedHeight, requestedFps, offsetMillis, dither);
        synchronized (lock) {
            if (stopped.get()) {
                newStream.close();
                return -1;
            }
            stream = newStream;
        }

        // Pre-chauffe du decodeur audio en parallele de la lecture bloquante du header mcmm
        // (sur une URL les deux se connectent en meme temps). Il nous appartient jusqu'a
        // l'attache. Le decodage demarre av-sync-delay-ms apres l'offset video pour couvrir
        // le buffering audio du client (modele de sync en tete de classe).
        AudioStream preStream = prewarmAudio(offsetMillis + audioSettings.audioSkipMillis());
        boolean audioAttached = false;

        try {
            newStream.readHeader(); // bloquant, un stop()/seekTo() -> close() nous debloque

            VirtualScreen scr;
            if (first) {
                // On construit l'ecran depuis les dimensions du header (elles font foi) et on
                // le spawn sous le lock pour que ca ne s'entrelace pas avec destroy().
                scr = new VirtualScreen(anchor,
                        newStream.getMapWidth(), newStream.getMapHeight(),
                        controlBarEnabled, subtitleSettings, viewerRange);
                synchronized (lock) {
                    if (stopped.get()) {
                        return -1; // stop() a deja ferme le stream
                    }
                    screen = scr;
                    scr.spawn(initialViewers);
                }
            } else {
                synchronized (lock) {
                    scr = screen;
                }
                if (scr == null) {
                    return -1; // stoppe en parallele
                }
                if (scr.getWidth() != newStream.getMapWidth()
                        || scr.getHeight() != newStream.getMapHeight()) {
                    throw new IOException("stream header dimensions changed across a seek ("
                            + scr.getWidth() + "x" + scr.getHeight() + " -> "
                            + newStream.getMapWidth() + "x" + newStream.getMapHeight() + ")");
                }
            }

            // On attache l'audio pre-chauffe a des canaux SVC ancres sur l'ecran. Il reste muet
            // (pas de begin()) jusqu'a ce que la boucle de frames commence a cadencer, apres
            // la phase de gel de la premiere image.
            AudioPlayback ap = null;
            if (preStream != null) {
                // En direct, les canaux SVC survivent aux tranches : on ne fait que leur passer
                // le flux suivant. Recreer un canal par tranche s'ENTEND (coupure nette au
                // raccord) et fait defiler le log du client.
                AudioPlayback live = liveAudio;
                if (live != null && live.handOff(preStream)) {
                    ap = live;
                    audioAttached = true;   // le playback possede desormais preStream
                } else {
                    ap = attachAudio(preStream, scr);
                    audioAttached = ap != null; // attachAudio a ferme preStream en cas d'echec
                    if (ap != null && liveSource != null) {
                        ap.setContinuous(true);
                        liveAudio = ap;
                    }
                }
            }

            // Lancement de l'extraction des sous-titres pour ce segment si une piste est
            // active. Demarre a l'offset du segment (les cues sont calees sur l'image, donc
            // pas de skip av-sync) ; la boucle de frames interroge this.subtitles et pilote
            // l'overlay, en le relisant pour qu'un toggle en cours de segment prenne aussi.
            startSubtitleSegment(offsetMillis, scr);

            int fps = Math.max(1, newStream.getFps());
            long intervalNanos = 1_000_000_000L / fps;
            this.streamFps = fps;
            this.screenW = newStream.getMapWidth();
            this.screenH = newStream.getMapHeight();

            // Gel de la premiere image, UNIQUEMENT au premier segment : on envoie la frame 0
            // et on la tient pendant audio-start-delay-ms, comme ca le pic de rendu du spawn
            // d'ecran cote client passe sur une image fixe et sans audio en vol. Les segments
            // de seek reutilisent l'ecran (pas de pic de spawn) : pas de gel.
            if (first) {
                long t0 = System.nanoTime();
                byte[][] frame0 = newStream.nextFrame();
                if (frame0 != null) {
                    long t1 = System.nanoTime();
                    scr.sendFrame(frame0);
                    totalDecodeNanos += (t1 - t0);
                    totalSendNanos += (System.nanoTime() - t1);
                    framesSent++;
                    freezeFirstFrame();
                }
                // frame0 == null : EOF immediat ou process tue, la boucle en dessous regle ca
            }

            // On attend que l'audio bufferise avant d'ancrer la timeline, pour que video et
            // audio partent strictement ensemble. Au premier segment le gel l'a deja amorce
            // (no-op) ; au seek il n'y a pas de gel, et c'est ca qui empeche l'audio de trainer
            // derriere l'image ensuite.
            awaitAudioPrimed(ap);

            // Le pacing demarre ICI : les deadlines video et le begin() audio sont ancres sur
            // le MEME instant, donc les deux timelines partent ensemble du contenu 0 du segment.
            pausedAccumNanos = 0; // les pauses pendant le gel/amorcage sont deja comptees
            this.playStartNanos = System.nanoTime();
            long deadline = playStartNanos + intervalNanos;
            long audioBeginNanos = playStartNanos;
            boolean audioBegun = false;

            while (!stopped.get() && pendingSeekMillis.get() < 0) {
                if (paused.get()) {
                    deadline = awaitResume(intervalNanos); // re-ancre la deadline
                    if (stopped.get()) {
                        break;
                    }
                    continue; // on reverifie pendingSeek au reveil (un seek nous reprend)
                }

                // L'audio demarre avec le pacing (audioBeginNanos = l'ancre post-gel), a
                // condition que ffmpeg ait de quoi jouer. Si l'amorcage nous a mis en retard,
                // on jette l'equivalent d'audio bufferise pour que le contenu reste cale sur
                // l'image (temps EFFECTIF : les pauses decalent video et audio pareil, elles
                // ne comptent pas).
                if (!audioBegun && ap != null && ap.isPrimed()) {
                    long effNow = System.nanoTime() - pausedAccumNanos;
                    if (effNow >= audioBeginNanos) {
                        int lateFrames = (int) Math.min(500,
                                (effNow - audioBeginNanos) / 20_000_000L);
                        if (lateFrames > 0) {
                            ap.skipFrames(lateFrames);
                        }
                        ap.begin();
                        if (paused.get()) {
                            ap.setPaused(true); // pause au meme instant, on reste fige
                        }
                        audioBegun = true;
                    }
                }

                long t0 = System.nanoTime();
                byte[][] frame = newStream.nextFrame();
                if (frame == null) {
                    break; // EOF (vraie fin, ou process tue par un seek)
                }
                long t1 = System.nanoTime();
                scr.sendFrame(frame); // le VirtualScreen la garde pour les late joiners
                long t2 = System.nanoTime();

                totalDecodeNanos += (t1 - t0);
                totalSendNanos += (t2 - t1);
                framesSent++;

                // On reconcilie l'overlay sous-titres depuis la position video a CHAQUE frame :
                // la boucle est le seul writer, donc relire le champ attrape un toggle
                // /video subs en cours de segment ET reaffirme en continu "vide" quand les
                // sous-titres sont coupes (ce qui ferme la race ou une vieille cue restait
                // affichee apres /video subs off). setSubtitle est idempotent, donc en regime
                // etabli ca n'envoie aucun paquet.
                SubtitleStream subs = currentSubtitles();
                // framesSent a deja ete incremente au dessus, donc getPositionMillis() pointe
                // sur le temps de la frame SUIVANTE ; celle qu'on vient d'envoyer montre du
                // contenu un intervalle plus tot. On compare a cet instant-la pour ne pas
                // choisir les cues une frame en avance (sinon biais de -50 ms a 20 fps,
                // -100 ms a 10 fps).
                long shownMillis = getPositionMillis() - 1000L / fps;
                scr.setSubtitle(subs != null && !subs.isClosed()
                        ? subs.cueAtVideoMillis(shownMillis)
                        : null);

                long sleepNanos = deadline - System.nanoTime();
                if (sleepNanos > 0) {
                    LockSupport.parkNanos(sleepNanos);
                }
                // La deadline suivante s'ancre sur la precedente : pas de derive.
                deadline += intervalNanos;
                long now = System.nanoTime();
                if (deadline < now - intervalNanos) {
                    deadline = now; // on a decroche pour de bon (decodage lent), on resynchronise
                }
            }
        } catch (IOException e) {
            // Un seek ferme le stream en pleine lecture : cette IOException est ATTENDUE et
            // veut dire "segment termine", pas "panne".
            if (pendingSeekMillis.get() < 0 || stopped.get()) {
                throw e;
            }
        } finally {
            // On demonte les pipelines de CE segment, l'ecran survit.
            VirtualScreen scrForClear;
            synchronized (lock) {
                newStream.close();
                if (stream == newStream) {
                    stream = null;
                }
                // Le playback d'un direct traverse les tranches : c'est stop() de la session
                // qui le ferme, pas la fin d'un segment.
                if (audio != null && audio != liveAudio) {
                    audio.stop();
                    audio = null;
                }
                if (subtitles != null) {
                    subtitles.close();
                    subtitles = null;
                }
                scrForClear = screen;
            }
            // On vide l'overlay entre deux segments, sinon un seek laisse l'ancienne cue figee
            // a l'ecran jusqu'a ce que le nouveau flux sorte la suivante.
            if (scrForClear != null && subtitleTrackIndex >= 0) {
                scrForClear.clearSubtitle();
            }
            if (preStream != null && !audioAttached) {
                preStream.close();
            }
        }

        long pending = pendingSeekMillis.getAndSet(-1);
        return (!stopped.get() && pending >= 0) ? pending : -1;
    }

    // Lance le ffmpeg audio de ce segment si l'audio est active et que Simple Voice Chat est
    // dispo. null quand l'audio est coupe ou que le lancement a rate (la lecture continue en
    // silence).
    private AudioStream prewarmAudio(long offsetMillis) {
        if (!audioSettings.enabled()) {
            return null;
        }
        VoicechatHook hook = plugin.getVoicechatHook();
        if (hook == null || !hook.isReady()) {
            return null;
        }
        if (anchor.getWorld() == null) {
            return null;
        }
        try {
            int srcChannels = -1;
            if (audioSettings.mode() == AudioMode.SURROUND) {
                srcChannels = sourceAudioChannels;
                if (srcChannels == 0) { // premier segment : on sonde une fois, cache pour les seeks
                    srcChannels = AudioStream.probeChannels(plugin.getLogger(),
                            audioSettings.ffmpegPath(), playSource);
                    sourceAudioChannels = srcChannels;
                }
            }
            return new AudioStream(plugin.getLogger(), audioSettings.ffmpegPath(), playSource,
                    audioSettings.mode().decodeChannels(), offsetMillis, srcChannels);
        } catch (Exception e) {
            plugin.getLogger().warning("Audio unavailable (ffmpeg failed to start): "
                    + e.getMessage());
            return null;
        }
    }

    // Monte les canaux SVC pour le stream pre-chauffe, ancres autour de l'ecran selon le mode
    // audio. En cas d'echec le stream est ferme ICI et on rend null (la video continue muette).
    private AudioPlayback attachAudio(AudioStream preStream, VirtualScreen forScreen) {
        VoicechatHook hook = plugin.getVoicechatHook();
        World world = anchor.getWorld();
        if (hook == null || !hook.isReady() || world == null) {
            preStream.close();
            return null;
        }
        double[][] anchors = switch (audioSettings.mode()) {
            case MONO -> new double[][] { forScreen.getCenter() };
            case STEREO -> new double[][] {
                    forScreen.getLeftAnchor(), forScreen.getRightAnchor() };
            // L'ordre DOIT coller au layout 5.1 decode : FL FR FC LFE BL BR.
            case SURROUND -> new double[][] {
                    forScreen.getLeftAnchor(),
                    forScreen.getRightAnchor(),
                    forScreen.getCenter(),
                    forScreen.getSubAnchor(),
                    forScreen.getRearLeftAnchor(audioSettings.rearDistance()),
                    forScreen.getRearRightAnchor(audioSettings.rearDistance()) };
        };
        try {
            AudioPlayback playback = AudioPlayback.create(hook.getServerApi(), world,
                    anchors, audioSettings.distance(), preStream, plugin.getLogger());
            synchronized (lock) {
                if (stopped.get()) {
                    playback.stop(); // perdu la course contre stop(), on ne fuite pas de ffmpeg
                    return null;
                }
                audio = playback;
            }
            return playback;
        } catch (Exception e) {
            plugin.getLogger().warning("Audio playback unavailable: " + e.getMessage());
            preStream.close();
            return null;
        }
    }

    // flux de sous-titres du segment courant, lu sous le lock ; null s'il n'y en a pas
    private SubtitleStream currentSubtitles() {
        synchronized (lock) {
            return subtitles;
        }
    }

    // (Re)lance l'extraction des sous-titres pour un segment qui commence a offsetMillis si une
    // piste est choisie, en remplacant le flux existant, sous le lock. Appele par le thread de
    // lecture a chaque debut de segment, et par /video subs <n> en cours de segment. Un echec
    // de lancement se contente de logger et laisse les sous-titres coupes pour le segment
    // (la lecture continue).
    private void startSubtitleSegment(long offsetMillis, VirtualScreen forScreen) {
        int track = subtitleTrackIndex;
        SubtitleStream started = null;
        if (track >= 0) {
            try {
                started = new SubtitleStream(plugin.getLogger(),
                        audioSettings.ffmpegPath(), playSource, track, offsetMillis);
            } catch (Exception e) {
                plugin.getLogger().warning("Subtitles unavailable (ffmpeg failed to start): "
                        + e.getMessage());
            }
        }
        SubtitleStream previous;
        synchronized (lock) {
            previous = subtitles;
            // Si stop() est passe entre la lecture du dessus et ici, on ne fuite pas de ffmpeg.
            if (stopped.get()) {
                if (started != null) {
                    started.close();
                }
                subtitles = null;
                return;
            }
            subtitles = started;
        }
        if (previous != null) {
            previous.close();
        }
        // Bascule on/off en cours de segment : on vide tout de suite la cue qui restait.
        if (started == null && forScreen != null) {
            forScreen.clearSubtitle();
        }
    }

    // Choisit la piste index (le n du -map 0:s:n) et lance son extraction pour le segment
    // courant. Main thread (appele depuis /video subs) ; le lancement de ffmpeg est un simple
    // start de process, non bloquant. False si la session est stoppee.
    public boolean setSubtitleTrack(int index) {
        if (stopped.get() || index < 0) {
            return false;
        }
        subtitleTrackIndex = index;
        VirtualScreen scr;
        synchronized (lock) {
            scr = screen;
        }
        // Si un segment tourne, on lance le flux MAINTENANT a la position COURANTE (pas au
        // debut du segment) : le -ss fait atterrir ffmpeg pres de "maintenant", donc la cue
        // active apparait tout de suite au lieu d'attendre qu'il rejoue toutes les cues depuis
        // le debut du segment. -copyts garde les temps absolus, donc les lookups n'ont toujours
        // aucun offset a appliquer. Sinon c'est le demarrage du segment suivant qui prendra le
        // nouvel index.
        if (scr != null) {
            startSubtitleSegment(getPositionMillis(), scr);
        }
        return true;
    }

    // Coupe les sous-titres : arrete le flux d'extraction et vide l'overlay.
    // False s'ils etaient deja coupes.
    public boolean disableSubtitles() {
        if (subtitleTrackIndex < 0) {
            return false;
        }
        subtitleTrackIndex = -1;
        VirtualScreen scr;
        SubtitleStream previous;
        synchronized (lock) {
            previous = subtitles;
            subtitles = null;
            scr = screen;
        }
        if (previous != null) {
            previous.close();
        }
        if (scr != null) {
            scr.clearSubtitle();
        }
        return true;
    }

    public int getSubtitleTrackIndex() {     // -1 si les sous-titres sont coupes
        return subtitleTrackIndex;
    }

    // Liste les pistes de sous-titres embarquees, sondees une fois et cachees pour la session
    // (comme sourceAudioChannels). BLOQUANT (ffprobe, jusqu'a ~20 s sur une URL lente), a
    // appeler hors main thread. Jamais null.
    //
    // Seul un sondage REUSSI est cache : probeTracks() rend null sur un echec passager
    // (ffprobe absent, timeout, erreur d'IO) et on ne cache PAS ca, sinon une panne ponctuelle
    // au tout premier /video subs empoisonnerait tous les appels suivants de la session. Un
    // echec rend une liste vide a l'appelant (donc son chemin isEmpty() marche toujours) tout
    // en laissant le cache vide pour un nouvel essai.
    public List<SubtitleTrack> getSubtitleTracks() {
        List<SubtitleTrack> cached = subtitleTracks;
        if (cached != null) {
            return cached;
        }
        List<SubtitleTrack> probed = SubtitleStream.probeTracks(
                plugin.getLogger(), audioSettings.ffmpegPath(), playSource);
        if (probed == null) {
            return List.of(); // sondage rate : on ne cache pas, un nouvel essai reste possible
        }
        subtitleTracks = probed;
        return probed;
    }

    // envoie un message de chat a celui qui a lance la video, depuis le main thread
    private void messageInitiator(String message) {
        if (!plugin.isEnabled()) {
            return; // impossible de planifier une tache pendant le disable
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player player = plugin.getServer().getPlayer(initiatorId);
                if (player != null) {
                    player.sendMessage("[MinecraftVideo] " + message);
                }
            });
        } catch (IllegalStateException | IllegalPluginAccessException e) {
            // plugin desactive entre le check isEnabled() et le schedule : rien a livrer, et
            // le nettoyage tourne quand meme dans le finally de run()
        }
    }
}
