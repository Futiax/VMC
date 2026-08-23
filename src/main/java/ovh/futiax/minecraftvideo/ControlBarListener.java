package ovh.futiax.minecraftvideo;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

// Listener packetevents qui transforme les clics sur les boutons de la ControlBar en actions
// de lecture : ⏪ seek -10 s, ⏯ pause/resume, ⏩ seek +10 s, ⏭ skip.
//
// Ca tourne sur le thread netty du client qui clique. Tout le boulot cote paquet reste SANS
// Bukkit (filtre d'id fake, dedup de paquets, debounce par joueur, l'identite vient du User
// de packetevents) ; le clic est ensuite regle dans UNE seule tache main thread parce que le
// check de permission et le message de retour sont de l'API Bukkit. Les operations de session
// (pause/resume/seek/stop) sont thread-safe de toute facon (/video les fait deja depuis le
// main), donc le saut coute au pire un tick, invisible pour un bouton.
//
// Un seul clic physique peut sortir plusieurs INTERACT_ENTITY : un clic droit envoie
// INTERACT_AT + INTERACT (et le client peut retenter avec la main gauche), un clic gauche
// envoie un seul ATTACK. On n'agit que sur INTERACT main principale et ATTACK ; le debounce
// de 250 ms absorbe les double-clics par dessus. Tout paquet vise sur N'IMPORTE QUEL id fake
// (bouton de barre OU frame d'ecran, session courante ou finie il y a un tick) est annule
// quoi qu'il arrive : ces ids n'existent pas cote serveur, le serveur vanilla ne doit jamais
// les voir.
public final class ControlBarListener implements PacketListener {

    private static final long DEBOUNCE_NANOS = 250_000_000L; // par joueur
    private static final long SEEK_STEP_MILLIS = 10_000L;    // pas des ⏪ / ⏩
    private static final String PERMISSION = "minecraftvideo.use";

    private final MinecraftVideoPlugin plugin;
    // dernier clic accepte par joueur (System.nanoTime), ecrit depuis les threads netty
    private final ConcurrentMap<UUID, Long> lastClickNanos = new ConcurrentHashMap<>();

    public ControlBarListener(MinecraftVideoPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }
        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
        int entityId = packet.getEntityId();
        if (entityId < VirtualScreen.FAKE_ID_BASE) {
            return; // vraie entite, pas nos oignons (fast path)
        }
        // N'importe quel id fake (frame d'ecran OU bouton, session courante ou qui vient de
        // finir) n'existe pas cote serveur : on annule AVANT le lookup de session, comme ca
        // un clic qui arrive un tick apres un swap/stop est quand meme avale au lieu de fuir
        // vers le serveur vanilla.
        event.setCancelled(true);

        PlaybackSession session = plugin.getActiveSession();
        ControlBar bar = session != null ? session.getControlBar() : null;
        ControlBar.Button button = bar != null ? bar.buttonAt(entityId) : null;
        if (button == null) {
            // TODO virer ce log une fois la barre reconfirmee en jeu : c'est le diagnostic du
            // "les boutons ne font rien". Un clic sur l'ecran lui-meme tombe ici aussi, donc
            // c'est de l'INFO, pas un warning.
            plugin.getLogger().info("Interact on fake entity " + entityId
                    + " matched no button (session=" + (session != null)
                    + ", bar=" + (bar != null)
                    + ", barIds=" + (bar != null ? Arrays.toString(bar.getEntityIds()) : "-")
                    + ")");
            return; // frame d'ecran ou id de barre perime, rien a faire
        }

        // On replie la rafale de paquets d'un clic physique sur une seule action.
        WrapperPlayClientInteractEntity.InteractAction action = packet.getAction();
        if (action == WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT) {
            return; // un clic droit envoie aussi INTERACT, c'est celui-la qu'on prend
        }
        if (action == WrapperPlayClientInteractEntity.InteractAction.INTERACT
                && packet.getHand() != InteractionHand.MAIN_HAND) {
            return; // retry main gauche du meme clic droit
        }

        UUID uuid = event.getUser().getUUID();
        if (uuid == null) {
            return;
        }
        long now = System.nanoTime();
        Long last = lastClickNanos.get(uuid);
        if (last != null && now - last < DEBOUNCE_NANOS) {
            return; // double-clic (un seul channel = events ordonnes, pas de race)
        }
        lastClickNanos.put(uuid, now);

        runOnMain(() -> handleClick(session, button, uuid));
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        UUID uuid = event.getUser().getUUID();
        if (uuid != null) {
            lastClickNanos.remove(uuid); // sinon la map de debounce grossit sans fin
        }
    }

    // Regle un clic sur le main thread : permission, action, retour au joueur.
    private void handleClick(PlaybackSession session, ControlBar.Button button, UUID uuid) {
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.hasPermission(PERMISSION)) {
            return; // les viewers sans la perm /video sont ignores en silence
        }
        if (session.isStopped()) {
            return; // la video s'est finie entre le clic et ce tick
        }
        plugin.getLogger().info("Control bar: " + button + " clicked by " + player.getName());  // TODO idem, temporaire
        switch (button) {
            case PLAY_PAUSE -> {
                if (session.isPaused()) {
                    if (session.resume()) {
                        message(player, "Resumed.");
                    }
                } else if (session.pause()) {
                    message(player, "Paused.");
                }
            }
            case REWIND -> seekRelative(session, player, -SEEK_STEP_MILLIS);
            case FORWARD -> seekRelative(session, player, SEEK_STEP_MILLIS);
            case SKIP -> {
                // Ne PAS toucher au PlaylistManager ici au dela de la lecture :
                // stop() -> clearSession() enchaine la file tout seul.
                boolean hasNext = !plugin.getPlaylist().isEmpty();
                session.stop();
                message(player, hasNext
                        ? "Skipped — starting the next queued video..."
                        : "Skipped (the queue is empty — stopped).");
            }
        }
    }

    // seek relatif depuis la position courante, meme semantique que /video seek
    private void seekRelative(PlaybackSession session, Player player, long deltaMillis) {
        if (plugin.isLive()) {      // rien a naviguer dans un direct, voir VideoCommand.handleSeek
            message(player, "Cannot seek a live stream.");
            return;
        }
        long target = Math.max(0, session.getPositionMillis() + deltaMillis);
        if (session.seekTo(target)) {
            message(player, "Seeking to "
                    + PlaybackSession.formatTimestamp(target) + "...");
        }
    }

    private void message(Player player, String text) {
        player.sendMessage("[MinecraftVideo] " + text);
    }

    // saut sur le main thread, no-op pendant le disable
    private void runOnMain(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (IllegalStateException | IllegalPluginAccessException e) {
            // desactive entre le check et le schedule, le clic ne sert plus a rien
        }
    }
}
