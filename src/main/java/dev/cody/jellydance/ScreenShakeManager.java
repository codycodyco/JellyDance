package dev.cody.jellydance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Experimental player-view shake driven by Player#setWalkSpeed.
 *
 * <p>The dance timeline supplies screen-shake enable/disable events. This is not
 * a camera API: it oscillates the walk-speed attribute and restores the exact
 * original value whenever the timeline disables it or the dance ends.
 */
public final class ScreenShakeManager {

    private final JellyDancePlugin plugin;
    private final List<Session> active = new ArrayList<>();

    public ScreenShakeManager(JellyDancePlugin plugin) {
        this.plugin = plugin;
    }

    public void play(Location center, int durationTicks, DanceChoreography choreography) {
        if (center.getWorld() == null || !choreography.hasScreenShake()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("screen-shake.enabled", true)) {
            return;
        }
        double maxRadius = choreography.maxShakeRadius();
        if (maxRadius <= 0) {
            return;
        }

        stopAll();
        Session session = new Session(center.clone(), Math.max(1, durationTicks));
        for (Player player : center.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= maxRadius * maxRadius) {
                session.players.add(player.getUniqueId());
                session.baseSpeeds.put(player.getUniqueId(), player.getWalkSpeed());
            }
        }
        if (session.players.isEmpty()) {
            return;
        }
        active.add(session);
        session.task = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> tick(session, choreography), 1L, 1L);
    }

    public void stopAll() {
        for (Session session : new ArrayList<>(active)) {
            stop(session);
        }
    }

    private void tick(Session session, DanceChoreography choreography) {
        session.elapsed++;
        DanceChoreography.ShakeState state = choreography.shakeAt(session.elapsed);
        if (!state.enabled()) {
            if (session.shaking) {
                restore(session);
                session.shaking = false;
            }
        } else {
            session.shaking = true;
            double seconds = (session.elapsed - state.startedAt()) / 20.0;
            double offset = state.amplitude()
                    * Math.sin(2.0 * Math.PI * state.frequency() * seconds);
            for (UUID id : session.players) {
                Player player = Bukkit.getPlayer(id);
                if (player == null || !player.isOnline()) {
                    continue;
                }
                if (player.getLocation().distanceSquared(session.center) > state.radius() * state.radius()) {
                    continue;
                }
                float base = session.baseSpeeds.getOrDefault(id, player.getWalkSpeed());
                player.setWalkSpeed((float) clamp(base + offset, 0.0, 1.0));
            }
        }
        if (session.elapsed >= session.durationTicks) {
            stop(session);
        }
    }

    private void stop(Session session) {
        if (!active.remove(session)) {
            return;
        }
        if (session.task != null) {
            session.task.cancel();
        }
        restore(session);
    }

    private void restore(Session session) {
        for (UUID id : session.players) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                player.setWalkSpeed(session.baseSpeeds.getOrDefault(id, 0.1f));
            }
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Session {
        final Location center;
        final int durationTicks;
        final Set<UUID> players = new HashSet<>();
        final Map<UUID, Float> baseSpeeds = new HashMap<>();
        BukkitTask task;
        int elapsed;
        boolean shaking;

        Session(Location center, int durationTicks) {
            this.center = center;
            this.durationTicks = durationTicks;
        }
    }
}
