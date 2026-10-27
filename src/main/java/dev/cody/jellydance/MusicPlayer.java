package dev.cody.jellydance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/** Plays one music track per dance start and stops it when the dance ends. */
public final class MusicPlayer {

    private final JellyDancePlugin plugin;
    private final List<Track> active = new ArrayList<>();

    public MusicPlayer(JellyDancePlugin plugin) {
        this.plugin = plugin;
    }

    public void play(DanceChoreography.Music music, Location center, int danceTicks) {
        // A new group replaces the previous group's track instead of stacking.
        stopAll();

        int delay = Math.max(0, (int) Math.round(music.startDelaySeconds() * 20.0));
        int stopAfter = music.stopOnEnd()
                ? Math.max(1, danceTicks)
                : Math.max(1, (int) Math.round(music.lengthSeconds() * 20.0));
        int lengthTicks = Math.max(0, (int) Math.round(music.lengthSeconds() * 20.0));

        Track track = new Track(music, center.clone());
        for (Player player : center.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= music.radius() * music.radius()) {
                track.players.add(player.getUniqueId());
            }
        }
        active.add(track);

        if (delay == 0) {
            playNow(track);
        } else {
            track.startTask = Bukkit.getScheduler().runTaskLater(plugin, () -> playNow(track), delay);
        }
        track.stopTask = Bukkit.getScheduler().runTaskLater(plugin,
                () -> stop(track), delay + stopAfter);
        if (music.loop() && lengthTicks > 0 && lengthTicks < stopAfter) {
            track.loopTask = Bukkit.getScheduler().runTaskTimer(plugin,
                    () -> playNow(track), delay + lengthTicks, lengthTicks);
        }
    }

    public void stopAll() {
        for (Track track : new ArrayList<>(active)) {
            stop(track);
        }
    }

    private void playNow(Track track) {
        for (UUID id : track.players) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                player.playSound(track.center, track.music.sound(), SoundCategory.MUSIC,
                        track.music.volume(), track.music.pitch());
            }
        }
    }

    private void stop(Track track) {
        if (!active.remove(track)) {
            return;
        }
        cancel(track.startTask);
        cancel(track.stopTask);
        cancel(track.loopTask);
        for (UUID id : track.players) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                player.stopSound(track.music.sound(), SoundCategory.MUSIC);
            }
        }
    }

    private static void cancel(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private static final class Track {
        final DanceChoreography.Music music;
        final Location center;
        final Set<UUID> players = new HashSet<>();
        BukkitTask startTask;
        BukkitTask stopTask;
        BukkitTask loopTask;

        Track(DanceChoreography.Music music, Location center) {
            this.music = music;
            this.center = center;
        }
    }
}
