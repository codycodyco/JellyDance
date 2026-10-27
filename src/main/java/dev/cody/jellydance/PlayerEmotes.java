package dev.cody.jellydance;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Drives RP Engine player emotes for the command sender only.
 *
 * <p>RP Engine is a soft dependency, so this uses its public API reflectively;
 * servers without RP Engine simply cannot start a player dance.
 */
public final class PlayerEmotes {

    private final JellyDancePlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private boolean resolved;
    private Object emotes;
    private Method wear;
    private Method seek;
    private Method stop;
    private boolean warned;

    public PlayerEmotes(JellyDancePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean play(Player player, String emote, int durationTicks) {
        stop(player.getUniqueId());
        if (!resolve()) {
            return false;
        }
        try {
            Object result = wear.invoke(emotes, player, emote);
            if (!Boolean.TRUE.equals(result.getClass().getMethod("started").invoke(result))) {
                plugin.getLogger().warning("RP Engine refused player emote " + emote + ": " + result);
                return false;
            }
        } catch (InvocationTargetException error) {
            plugin.getLogger().warning("RP Engine player emote " + emote + " failed: "
                    + error.getCause().getMessage());
            return false;
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().warning("RP Engine player emote API mismatch: " + error.getMessage());
            return false;
        }

        Session session = new Session(player.getUniqueId(), Math.max(1, durationTicks));
        sessions.put(player.getUniqueId(), session);
        session.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(session), 1L, 1L);
        return true;
    }

    public void stopAll() {
        for (Session session : new ArrayList<>(sessions.values())) {
            stop(session.playerId);
        }
    }

    public void stop(UUID playerId) {
        Session session = sessions.remove(playerId);
        if (session == null) {
            return;
        }
        if (session.task != null) {
            session.task.cancel();
        }
        if (!resolve()) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            try {
                stop.invoke(emotes, player);
            } catch (ReflectiveOperationException error) {
                plugin.getLogger().warning("RP Engine stop emote failed: " + error.getMessage());
            }
        }
    }

    private void tick(Session session) {
        Player player = Bukkit.getPlayer(session.playerId);
        if (player == null || !player.isOnline()) {
            stop(session.playerId);
            return;
        }
        try {
            seek.invoke(emotes, player, session.elapsed / 20.0);
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().warning("RP Engine seek emote failed: " + error.getMessage());
            stop(session.playerId);
            return;
        }
        session.elapsed++;
        if (session.elapsed >= session.durationTicks) {
            stop(session.playerId);
        }
    }

    private boolean resolve() {
        if (resolved) {
            return emotes != null;
        }
        resolved = true;
        Plugin rp = Bukkit.getPluginManager().getPlugin("RPEngine");
        if (rp == null || !rp.isEnabled()) {
            if (!warned) {
                warned = true;
                plugin.getLogger().info("RPEngine is not installed; player dances are unavailable");
            }
            return false;
        }
        try {
            Class<?> emotesClass = Class.forName("ai.resourcepack.engine.api.Emotes");
            Object api = rp.getClass().getMethod("emotes").invoke(rp);
            wear = emotesClass.getMethod("wear", Player.class, String.class);
            seek = emotesClass.getMethod("seek", Player.class, double.class);
            stop = emotesClass.getMethod("stop", Player.class);
            emotes = api;
            return true;
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().warning("RPEngine emote API not available: " + error.getMessage());
            return false;
        }
    }

    private static final class Session {
        final UUID playerId;
        final int durationTicks;
        BukkitTask task;
        int elapsed;

        Session(UUID playerId, int durationTicks) {
            this.playerId = playerId;
            this.durationTicks = durationTicks;
        }
    }
}
