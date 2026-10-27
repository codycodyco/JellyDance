package dev.cody.jellydance;

import dev.cody.vanillamobs.api.VanillaModelLibraryApi;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class JellyDancePlugin extends JavaPlugin {

    private DanceManager manager;
    private VanillaModelLibraryApi library;
    private ChoreographyLoader choreographies;
    private MusicPlayer musicPlayer;
    private ScreenShakeManager screenShake;
    private PlayerEmotes playerEmotes;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        library = VanillaModelLibraryApi.get().orElse(null);
        if (library == null) {
            getLogger().severe("VanillaModelLibrary is not registered; JellyDance is inactive");
            return;
        }
        if (!library.ready()) {
            getLogger().warning("VanillaModelLibrary is not ready yet; dances will start empty until it is");
        }
        DanceManager.HideMode mode;
        try {
            mode = DanceManager.HideMode.valueOf(
                    getConfig().getString("hide-mode", "protocol").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            mode = DanceManager.HideMode.PROTOCOL;
            getLogger().warning("Unknown hide-mode; using protocol");
        }
        installAddonContent();
        choreographies = new ChoreographyLoader(this);
        choreographies.reload();
        manager = new DanceManager(this, library, mode, choreographies,
                getConfig().getInt("dance.interpolation-ticks", 2),
                (float) getConfig().getDouble("dance.view-range", 1.0),
                getConfig().getBoolean("dance.freeze-in-place", true),
                getConfig().getDouble("dance.freeze-tolerance", 0.05),
                getConfig().getBoolean("dance.debug", false));
        getServer().getPluginManager().registerEvents(new DanceListener(this), this);
        if (getCommand("jellydance") != null) {
            getCommand("jellydance").setExecutor(new DanceCommand(this));
        }
        musicPlayer = new MusicPlayer(this);
        screenShake = new ScreenShakeManager(this);
        playerEmotes = new PlayerEmotes(this);
        manager.startTicking();
        getLogger().info("JellyDance ready: hide-mode=" + mode + ", library.ready=" + library.ready()
                + ", asset=" + library.assetVersion());
    }

    @Override
    public void onDisable() {
        if (playerEmotes != null) {
            playerEmotes.stopAll();
        }
        if (screenShake != null) {
            screenShake.stopAll();
        }
        if (musicPlayer != null) {
            musicPlayer.stopAll();
        }
        if (manager != null) {
            manager.stopTicking();
            manager.stopAll("shutdown");
        }
    }

    public DanceManager manager() {
        return manager;
    }

    public VanillaModelLibraryApi library() {
        return library;
    }

    /** Re-reads config.yml and dances/*.yml without restarting the server. */
    public void reloadDances() {
        reloadConfig();
        if (choreographies != null) {
            choreographies.reload();
        }
    }

    public ChoreographyLoader choreographies() {
        return choreographies;
    }

    public MusicPlayer musicPlayer() {
        return musicPlayer;
    }

    public ScreenShakeManager screenShake() {
        return screenShake;
    }

    public PlayerEmotes playerEmotes() {
        return playerEmotes;
    }

    /**
     * Installs resources/content/jellydance into RP Engine's content folder.
     * AddonContent preserves server-owner edits and only triggers a pack reload
     * when the bundled files actually changed.
     */
    private void installAddonContent() {
        Plugin rp = getServer().getPluginManager().getPlugin("RPEngine");
        if (rp == null || !rp.isEnabled()) {
            getLogger().info("RPEngine is not installed; bundled music/emotes were not installed");
            return;
        }
        try {
            Class<?> addon = Class.forName("ai.resourcepack.engine.api.AddonContent");
            boolean changed = (boolean) addon
                    .getMethod("install", Plugin.class, Plugin.class, String.class)
                    .invoke(null, this, rp, "jellydance");
            if (changed) {
                rp.getClass().getMethod("reload").invoke(rp);
                getLogger().info("Installed bundled RP Engine content (jellydance) and reloaded packs");
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            getLogger().warning("Could not install bundled RP Engine content: " + error);
        }
    }
}
