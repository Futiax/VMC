package ovh.futiax.minecraftvideo;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;

/**
 * Registers this plugin as a Simple Voice Chat addon and captures the
 * {@link VoicechatServerApi} once the voice chat server starts.
 *
 * Registered in {@code onEnable} via
 * {@code getServicesManager().load(BukkitVoicechatService.class).registerPlugin(...)}.
 * When SVC is not installed the service is absent and this is never registered,
 * so playback simply runs without audio.
 *
 * NOTE: {@link VoicechatServerStartedEvent} fires when the SVC server starts,
 * which on a normal boot happens after plugins enable. If this plugin is
 * hot-reloaded on its own while SVC is already running the event may not
 * re-fire; a full server restart always yields the api.
 */
public final class VoicechatHook implements VoicechatPlugin {

    public static final String PLUGIN_ID = "minecraftvideo";

    private volatile VoicechatServerApi serverApi;

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        // Server api is obtained from the started event below.
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class,
                event -> serverApi = event.getVoicechat());
    }

    /** @return the server api, or {@code null} if SVC has not started yet. */
    public VoicechatServerApi getServerApi() {
        return serverApi;
    }

    public boolean isReady() {
        return serverApi != null;
    }
}
