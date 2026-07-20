package ovh.futiax.minecraftvideo;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin class. Plays videos on virtual in-game map screens.
 *
 * Everything is virtual: fake entity ids, fake map ids, packets only.
 * Nothing is written to the world or to disk.
 *
 * packetevents is used as a separately-installed plugin (declared as a hard
 * dependency in plugin.yml), so it manages its own lifecycle — this plugin
 * never calls setAPI/load/init/terminate, only PacketEvents.getAPI().
 */
public final class MinecraftVideoPlugin extends JavaPlugin {

    /** Single global playback session for this base version. */
    private volatile PlaybackSession activeSession;

    /** FIFO of queued sources; auto-advances when the active session ends. */
    private final PlaylistManager playlist = new PlaylistManager(this);

    /** Local cache for remote sources; null until onEnable reads the config. */
    private MediaCache mediaCache;

    /** Simple Voice Chat hook; null when SVC is not installed. */
    private VoicechatHook voicechatHook;

    /** Extracts the bundled mcmm binary + palette on first start. */
    private final NativeInstaller nativeInstaller = new NativeInstaller(this);

    /** Registered control-bar click listener, kept to unregister on disable. */
    private PacketListenerCommon controlBarListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        nativeInstaller.install();

        YtDlpInstaller ytDlpInstaller = new YtDlpInstaller(getLogger(),
                getDataFolder().toPath(),
                getConfig().getBoolean("youtube-support", true),
                getConfig().getString("yt-dlp-path", ""));
        mediaCache = new MediaCache(getLogger(), getDataFolder().toPath(),
                getConfig().getBoolean("cache-remote-sources", true),
                getConfig().getInt("cache-max-size-mb", 2048),
                new YtDlpResolver(ytDlpInstaller, getLogger()));

        PluginCommand command = getCommand("video");
        if (command != null) {
            VideoCommand videoCommand = new VideoCommand(this);
            command.setExecutor(videoCommand);
            command.setTabCompleter(videoCommand);
        } else {
            getLogger().severe("Command 'video' is missing from plugin.yml!");
        }

        getServer().getPluginManager().registerEvents(new JoinListener(this), this);

        // Control-bar clicks arrive as INTERACT_ENTITY packets aimed at our
        // fake entity ids. packetevents is initialized by its own plugin (hard
        // dependency, loaded before us); we only register a listener.
        controlBarListener = PacketEvents.getAPI().getEventManager()
                .registerListener(new ControlBarListener(this), PacketListenerPriority.NORMAL);

        registerVoicechat();
    }

    /** Registers this plugin as a Simple Voice Chat addon if SVC is present. */
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

    /** @return the SVC hook, or {@code null} if SVC is not installed. */
    public VoicechatHook getVoicechatHook() {
        return voicechatHook;
    }

    /**
     * Resolves the mcmm path: the configured {@code mcmm-path} if it is set and
     * usable, else the binary extracted from the jar. A configured path that
     * points nowhere (e.g. a stale {@code "./mcmm"} from an older config) is
     * ignored so an outdated config.yml can't break playback. Returns
     * {@code null} if neither is available.
     */
    public String resolveMcmmPath() {
        String configured = getConfig().getString("mcmm-path", "");
        if (configured != null && !configured.isBlank()) {
            java.io.File f = new java.io.File(configured);
            boolean looksLikePath = f.isAbsolute()
                    || configured.contains("/") || configured.contains("\\");
            if (!looksLikePath || f.exists()) {
                return configured; // a bare PATH name, or an existing path
            }
            getLogger().warning("Configured mcmm-path '" + configured
                    + "' does not exist; using the bundled mcmm instead.");
        }
        return nativeInstaller.getMcmmPath() != null
                ? nativeInstaller.getMcmmPath().toString() : null;
    }

    /** Resolves the palette path: configured value if it exists, else the bundled one. */
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

    public PlaylistManager getPlaylist() {
        return playlist;
    }

    public MediaCache getMediaCache() {
        return mediaCache;
    }

    /** Subtitle-overlay geometry snapshot from the current config values. */
    public SubtitleSettings buildSubtitleSettings() {
        return new SubtitleSettings(
                (float) getConfig().getDouble("subtitle-size", 1.0),
                getConfig().getDouble("subtitle-height", 0.45),
                getConfig().getDouble("subtitle-depth", 0.05));
    }

    /** Audio configuration snapshot from the current config values. */
    public AudioSettings buildAudioSettings() {
        return new AudioSettings(
                getConfig().getBoolean("audio-enabled", true),
                getConfig().getString("ffmpeg-path", "ffmpeg"),
                getConfig().getInt("audio-distance", 48),
                AudioMode.fromConfig(getConfig().getString("audio-mode", "mono")),
                getConfig().getInt("av-sync-delay-ms", 200),
                getConfig().getInt("audio-start-delay-ms", 1000),
                getConfig().getDouble("surround-rear-distance", 10.0));
    }

    @Override
    public void onDisable() {
        // Unregister from packetevents first (it outlives us; a stale listener
        // would keep this instance alive across /reload and act on clicks).
        if (controlBarListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(controlBarListener);
            controlBarListener = null;
        }
        playlist.clear(); // nothing should auto-start while shutting down
        PlaybackSession session = activeSession;
        if (session != null) {
            session.stop();
            // Wait for the playback thread to finish so it can't send a packet
            // after the plugin class loader is closed on disable.
            session.join(2000);
        }
        activeSession = null;
        if (mediaCache != null) {
            mediaCache.purgeAll(); // remove any cached files on shutdown
        }
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

    /**
     * Called by a session when it ends (EOF, error, /video stop or skip).
     * The playlist then starts the next queued item, if any — /video stop
     * empties the queue before stopping, so a plain stop stays stopped.
     */
    public synchronized void clearSession(PlaybackSession session) {
        if (activeSession == session) {
            activeSession = null;
            playlist.scheduleAdvance();
        }
    }
}
