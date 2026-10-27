package dev.cody.jellydance;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/** Keeps the original hidden from every viewer and ends dances on any lifecycle exit. */
public final class DanceListener implements Listener {

    private final JellyDancePlugin plugin;

    public DanceListener(JellyDancePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTrack(PlayerTrackEntityEvent event) {
        DanceManager manager = plugin.manager();
        if (manager != null && manager.isDancing(event.getEntity())) {
            // The spawn packet is never sent: the client only ever knows the display.
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (plugin.manager() != null) {
            plugin.manager().stop(event.getEntity(), "death");
        }
    }

    @EventHandler
    public void onRemove(EntityRemoveEvent event) {
        if (plugin.manager() != null && plugin.manager().isDancing(event.getEntity())) {
            plugin.manager().stop(event.getEntity(), "removed: " + event.getCause());
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        if (plugin.manager() == null) {
            return;
        }
        for (Entity entity : event.getEntities()) {
            plugin.manager().stop(entity, "chunk unload");
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        if (plugin.manager() != null) {
            plugin.manager().stopInWorld(event.getWorld(), "world unload");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.playerEmotes() != null) {
            plugin.playerEmotes().stop(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.manager() == null) {
            return;
        }
        // Immediately, and again after the join packet burst, so a player who
        // arrives mid-dance never gets one frame of the vanilla body.
        plugin.manager().hideFrom(event.getPlayer());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plugin.manager() != null) {
                plugin.manager().hideFrom(event.getPlayer());
            }
        });
    }
}
