package ovh.futiax.minecraftvideo;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;

// Enregistre le plugin comme addon Simple Voice Chat et recupere le VoicechatServerApi
// au demarrage du serveur vocal. Branche dans onEnable via
// getServicesManager().load(BukkitVoicechatService.class).registerPlugin(...).
// Si SVC n'est pas installe le service n'existe pas, on n'enregistre rien et la lecture
// tourne simplement sans son.
//
// ATTENTION : VoicechatServerStartedEvent part au demarrage du serveur SVC, donc apres
// l'enable des plugins sur un boot normal. Si on reload le plugin tout seul alors que SVC
// tourne deja l'event peut ne pas repartir -> un vrai restart donne toujours l'api.
public final class VoicechatHook implements VoicechatPlugin {

    public static final String PLUGIN_ID = "minecraftvideo";

    private volatile VoicechatServerApi serverApi;

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        // rien ici, l'api serveur arrive par l'event plus bas
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class,
                event -> serverApi = event.getVoicechat());
    }

    public VoicechatServerApi getServerApi() {       // null tant que SVC n'a pas demarre
        return serverApi;
    }

    public boolean isReady() {
        return serverApi != null;
    }
}
