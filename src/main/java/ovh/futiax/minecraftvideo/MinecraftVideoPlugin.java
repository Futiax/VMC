package ovh.futiax.minecraftvideo;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

// Classe principale du plugin. Joue des videos sur des ecrans map virtuels en jeu.
// Tout est virtuel : fake entity ids, fake map ids, paquets uniquement. Rien n'est ecrit
// dans le monde ni sur le disque (sauf le cache de MediaCache).
//
// packetevents est un plugin installe a part (depend dure dans plugin.yml) donc il gere son
// propre cycle de vie : on n'appelle JAMAIS setAPI/load/init/terminate, seulement getAPI().
public final class MinecraftVideoPlugin extends JavaPlugin {

    private volatile PlaybackSession activeSession;     // une seule session a la fois
    private final PlaylistManager playlist = new PlaylistManager(this);     // FIFO + auto-advance
    private MediaCache mediaCache;                      // null tant que onEnable n'a pas lu la config
    private PostEffects postEffects;
    private VoicechatHook voicechatHook;                // null si SVC pas installe
    private final NativeInstaller nativeInstaller = new NativeInstaller(this);
    private PacketListenerCommon controlBarListener;    // garde pour l'unregister au disable
    private YtDlpResolver ytDlpResolver;                // partage avec /stream
    private LiveStream liveStream;                      // non-null pendant un /stream
    private ToolInstaller tools;                        // ffmpeg/ffprobe + yt-dlp
    private ResourcePackService resourcePack;           // pack VMC pousse aux joueurs au join

    @Override
    public void onEnable() {
        saveDefaultConfig();
        nativeInstaller.install();
        postEffects = new PostEffects(this, getConfig().getString("post-effect", ""));
        purgePhotoClips();  // clips temporaires laisses par un arret brutal

        // Resolution des outils externes en tache de fond : onEnable ne doit pas attendre un
        // telechargement de ~130 Mo (ffmpeg), et le serveur doit pouvoir demarrer sans.
        tools = new ToolInstaller(getLogger(), getDataFolder().toPath(),
                getConfig().getBoolean("youtube-support", true),
                getConfig().getString("yt-dlp-path", ""),
                getConfig().getString("ffmpeg-path", "ffmpeg"),
                getConfig().getBoolean("ffmpeg-autodownload", true));
        tools.start();
        resourcePack = new ResourcePackService(this);
        resourcePack.enable();
        ytDlpResolver = new YtDlpResolver(tools, getLogger());
        mediaCache = new MediaCache(getLogger(), getDataFolder().toPath(),
                getConfig().getBoolean("cache-remote-sources", true),
                getConfig().getInt("cache-max-size-mb", 2048),
                ytDlpResolver);

        PluginCommand command = getCommand("video");
        if (command != null) {
            VideoCommand videoCommand = new VideoCommand(this);
            command.setExecutor(videoCommand);
            command.setTabCompleter(videoCommand);
        } else {
            getLogger().severe("Command 'video' is missing from plugin.yml!");
        }

        PluginCommand photo = getCommand("photo");
        if (photo != null) {
            PhotoCommand photoCommand = new PhotoCommand(this);
            photo.setExecutor(photoCommand);
            photo.setTabCompleter(photoCommand);
        } else {
            getLogger().severe("Command 'photo' is missing from plugin.yml!");
        }

        PluginCommand stream = getCommand("stream");
        if (stream != null) {
            StreamCommand streamCommand = new StreamCommand(this);
            stream.setExecutor(streamCommand);
            stream.setTabCompleter(streamCommand);
        } else {
            getLogger().severe("Command 'stream' is missing from plugin.yml!");
        }

        PluginCommand vmc = getCommand("vmc");
        if (vmc != null) {
            VmcCommand vmcCommand = new VmcCommand(this);
            vmc.setExecutor(vmcCommand);
            vmc.setTabCompleter(vmcCommand);
        } else {
            getLogger().severe("Command 'vmc' is missing from plugin.yml!");
        }

        getServer().getPluginManager().registerEvents(new JoinListener(this), this);

        // Les clics sur la barre arrivent en paquets INTERACT_ENTITY vises sur nos fake ids.
        // packetevents s'initialise tout seul (depend dure, charge avant nous), on pose juste
        // un listener.
        controlBarListener = PacketEvents.getAPI().getEventManager()
                .registerListener(new ControlBarListener(this), PacketListenerPriority.NORMAL);

        // Les joueurs bougent : on reevalue chaque seconde qui est a portee de l'ecran. Le
        // scheduler Bukkit annule la tache a onDisable.
        getServer().getScheduler().runTaskTimer(this, () -> {
            PlaybackSession session = getActiveSession();
            if (session != null) {
                session.refreshViewers(getServer().getOnlinePlayers());
            }
        }, 20L, 20L);

        registerVoicechat();
    }

    // On s'enregistre comme addon Simple Voice Chat si SVC est la.
    private void registerVoicechat() {
        BukkitVoicechatService service =
                getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            getLogger().info("Simple Voice Chat not found; videos will play without audio.");
            return;
        }
        voicechatHook = new VoicechatHook();
        service.registerPlugin(voicechatHook);
        getLogger().info("Registered as a Simple Voice Chat addon for video audio.");
    }

    public VoicechatHook getVoicechatHook() {       // null si SVC absent
        return voicechatHook;
    }

    // Resout le chemin mcmm : le mcmm-path configure s'il est defini ET utilisable, sinon le
    // binaire extrait du jar. Un chemin configure qui pointe dans le vide (genre un vieux
    // "./mcmm" d'une config d'avant) est ignore, pour qu'une config.yml perimee ne casse pas
    // la lecture. null si rien de dispo.
    public String resolveMcmmPath() {
        String configured = getConfig().getString("mcmm-path", "");
        if (configured != null && !configured.isBlank()) {
            java.io.File f = new java.io.File(configured);
            boolean looksLikePath = f.isAbsolute()
                    || configured.contains("/") || configured.contains("\\");
            if (!looksLikePath || f.exists()) {
                return configured; // un nom PATH nu, ou un chemin qui existe
            }
            getLogger().warning("Configured mcmm-path '" + configured
                    + "' does not exist; using the bundled mcmm instead.");
        }
        return nativeInstaller.getMcmmPath() != null
                ? nativeInstaller.getMcmmPath().toString() : null;
    }

    // idem pour la palette : valeur configuree si elle existe, sinon celle du jar
    public String resolvePalettePath() {
        String configured = getConfig().getString("palette-path", "");
        if (configured != null && !configured.isBlank()) {
            if (new java.io.File(configured).exists()) {
                return configured;
            }
            getLogger().warning("Configured palette-path '" + configured
                    + "' does not exist; using the bundled palette instead.");
        }
        return nativeInstaller.getPalettePath() != null
                ? nativeInstaller.getPalettePath().toString() : null;
    }

    // Les clips de /photo vivent dans <dataFolder>/photo et sont supprimes a l'arret de la
    // session ; il n'en reste qu'apres un crash. Menage au demarrage, comme MediaCache.
    private void purgePhotoClips() {
        java.nio.file.Path dir = StillClip.directory(getDataFolder().toPath());
        if (!java.nio.file.Files.isDirectory(dir)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path file : files.toList()) {
                try {
                    java.nio.file.Files.deleteIfExists(file);
                } catch (java.io.IOException ignored) {
                    // pas grave : un fichier de plus, le prochain demarrage retentera
                }
            }
        } catch (java.io.IOException ignored) {
        }
    }

    public PostEffects getPostEffects() {
        return postEffects;
    }

    public PlaylistManager getPlaylist() {
        return playlist;
    }

    public YtDlpResolver getYtDlpResolver() {
        return ytDlpResolver;
    }

    public boolean isLive() {
        return liveStream != null;
    }

    public LiveStream getLiveStream() {
        return liveStream;
    }

    // Main thread. Remplace un direct en cours s'il y en a un.
    public void startLiveStream(LiveStream stream, String mediaUrl) throws java.io.IOException {
        stopLiveStream(null);
        stream.start(mediaUrl);
        liveStream = stream;
    }

    // Main thread. Coupe le decoupage, vide la file de tranches et arrete la lecture : une
    // tranche qui joue encore pointe un fichier qu'on vient d'effacer.
    public void stopLiveStream(String reason) {
        if (liveStream == null) {
            return;
        }
        LiveStream stream = liveStream;
        liveStream = null;              // avant le stop : le poll() ne doit pas se rappeler ici
        playlist.clear();
        PlaybackSession session = activeSession;
        if (session != null) {
            session.stop();
        }
        stream.stop();
        if (reason != null) {
            getLogger().info("Direct termine: " + reason);
        }
    }

    public MediaCache getMediaCache() {
        return mediaCache;
    }

    public ToolInstaller getTools() {
        return tools;
    }

    public ResourcePackService getResourcePack() {
        return resourcePack;
    }

    // snapshots des options config, pris au demarrage d'une lecture
    public SubtitleSettings buildSubtitleSettings() {
        return new SubtitleSettings(
                (float) getConfig().getDouble("subtitle-size", 1.0),
                getConfig().getDouble("subtitle-height", 0.45),
                getConfig().getDouble("subtitle-depth", 0.05));
    }

    public AudioSettings buildAudioSettings() {
        return new AudioSettings(
                getConfig().getBoolean("audio-enabled", true),
                // tools.ffmpeg() plutot que la config brute : c'est le binaire telecharge
                // quand il y en a un, et le chemin configure sinon. Tout ce qui lance ffmpeg
                // ou ffprobe (audio, sous-titres, /photo) passe par ce champ.
                tools.ffmpeg(),
                getConfig().getInt("audio-distance", 48),
                AudioMode.fromConfig(getConfig().getString("audio-mode", "mono")),
                getConfig().getInt("av-sync-delay-ms", 200),
                getConfig().getInt("audio-start-delay-ms", 1000),
                getConfig().getDouble("surround-rear-distance", 10.0));
    }

    @Override
    public void onDisable() {
        // Unregister de packetevents en PREMIER : il nous survit, et un listener perime
        // garderait cette instance vivante sur un /reload tout en continuant a traiter les clics.
        if (controlBarListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(controlBarListener);
            controlBarListener = null;
        }
        if (resourcePack != null) {
            resourcePack.disable();     // libere le port, sinon un /reload ne peut plus le rouvrir
        }
        stopLiveStream(null);
        playlist.clear(); // rien ne doit redemarrer tout seul a l'arret
        PlaybackSession session = activeSession;
        if (session != null) {
            session.stop();
            // On attend la fin du thread de lecture pour qu'il n'envoie pas un paquet apres
            // la fermeture du class loader du plugin.
            session.join(2000);
        }
        activeSession = null;
        if (postEffects != null) {
            postEffects.clearAll(); // ce que destroy() n'aurait pas retire (viewer deja parti...)
        }
        if (mediaCache != null) {
            mediaCache.purgeAll(); // on ne laisse pas trainer les fichiers caches
        }
    }

    // Ce qui occupe l'ecran, ou null s'il est libre. Les TROIS commandes qui demarrent une
    // lecture doivent passer par la : un direct qui demarre n'a pas encore de session active
    // (sa premiere tranche n'existe pas), donc tester getActiveSession() seul laisse /photo et
    // /video play lui voler le slot -- et le direct reapparait tout seul une seconde apres
    // l'arret de l'autre, sur SON ancre.
    public String busyReason() {
        PlaybackSession active = getActiveSession();
        if (active != null) {
            return "something is already playing (started by " + active.getInitiatorName() + ")";
        }
        if (isLive()) {
            return "a live stream is running";
        }
        return null;
    }

    public PlaybackSession getActiveSession() {
        return activeSession;
    }

    public synchronized boolean trySetActiveSession(PlaybackSession session) {
        if (activeSession != null) {
            return false;
        }
        activeSession = session;
        return true;
    }

    // Appele par une session quand elle se termine (EOF, erreur, /video stop ou skip). La
    // playlist enchaine sur l'element suivant s'il y en a un : /video stop vide la file AVANT
    // d'arreter, donc un stop simple reste arrete.
    public synchronized void clearSession(PlaybackSession session) {
        if (activeSession == session) {
            activeSession = null;
            playlist.scheduleAdvance();
        }
    }
}
