package ovh.futiax.mcdata;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import ovh.futiax.mcdata.channel.BeaconChannel;
import ovh.futiax.mcdata.command.McDataCommand;
import ovh.futiax.mcdata.mcmm.NativeInstaller;
import ovh.futiax.mcdata.poc.PocRegistry;
import ovh.futiax.mcdata.poc.ProbePoc;
import ovh.futiax.mcdata.poc.VideoPoc;

import java.util.List;

// MCDATA : canal universel serveur -> client par map packets + post effects.
//
// Le plugin fournit l'infrastructure commune (balise, post-effects, resource pack) et enregistre
// des POCs qui s'y branchent. Chaque POC est un module autonome : il enregistre son feed de
// donnees dans le BeaconChannel et pousse son post-effect aux joueurs qui l'utilisent.
//
// Le resource pack (pack/) contient les shaders core modifies (text.vsh/fsh reconnaissent la
// balise et la deplacent en coin bas-gauche) et un post-effect par POC.
public final class McData extends JavaPlugin {

    private ResourcePackService resourcePack;
    private PostEffects postEffects;
    private BeaconChannel channel;
    private PocRegistry pocRegistry;
    private NativeInstaller nativeInstaller;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        nativeInstaller = new NativeInstaller(this);
        nativeInstaller.install();
        nativeInstaller.applyConfigOverrides();

        resourcePack = new ResourcePackService(this);
        resourcePack.enable();

        // Les post-effects connus : ceux des POCs enregistres (plus tard, apres le registry).
        postEffects = new PostEffects(this, List.of());

        int rate = getConfig().getInt("channel-rate", 20);
        channel = new BeaconChannel(this, rate);

        pocRegistry = new PocRegistry();
        pocRegistry.register(new ProbePoc(this));
        pocRegistry.register(new VideoPoc(this));
        // futur : pocRegistry.register(new DoomPoc(this));

        // Maintenant que les POCs sont connus, on reconstruit PostEffects avec leurs effets.
        java.util.ArrayList<String> known = new java.util.ArrayList<>();
        for (ovh.futiax.mcdata.poc.Poc poc : pocRegistry.all()) {
            if (!poc.postEffect().isEmpty()) {
                known.add(poc.postEffect());
            }
        }
        postEffects = new PostEffects(this, known);

        PluginCommand cmd = getCommand("mcdata");
        if (cmd != null) {
            McDataCommand handler = new McDataCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        getServer().getPluginManager().registerEvents(new JoinListener(this), this);

        getLogger().info("MCDATA enabled — " + pocRegistry.all().size() + " POC(s) registered: "
                + pocRegistry.all().stream().map(p -> p.name()).toList());
    }

    @Override
    public void onDisable() {
        if (pocRegistry != null) {
            for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
                pocRegistry.stopAll(player);
            }
        }
        if (channel != null) {
            channel.stopAll();
        }
        if (postEffects != null) {
            postEffects.clearAll();
        }
        if (resourcePack != null) {
            resourcePack.disable();
        }
    }

    public ResourcePackService getResourcePack() {
        return resourcePack;
    }

    public PostEffects getPostEffects() {
        return postEffects;
    }

    public BeaconChannel getChannel() {
        return channel;
    }

    public PocRegistry getPocRegistry() {
        return pocRegistry;
    }

    public String getMcmmPath() {
        return nativeInstaller != null && nativeInstaller.getMcmmPath() != null
                ? nativeInstaller.getMcmmPath().toString() : null;
    }

    public String getPalettePath() {
        return nativeInstaller != null && nativeInstaller.getPalettePath() != null
                ? nativeInstaller.getPalettePath().toString() : null;
    }
}
