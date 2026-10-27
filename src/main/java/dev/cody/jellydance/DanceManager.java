package dev.cody.jellydance;

import dev.cody.vanillamobs.api.ModelDisplay;
import dev.cody.vanillamobs.api.ModelSelection;
import dev.cody.vanillamobs.api.VanillaModelLibraryApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Owns every dance. The original entity keeps its identity, AI, collision and
 * position; only its client-visible appearance is replaced by one ItemDisplay
 * that plays the whole-model motion.
 */
public final class DanceManager {

    public enum HideMode {
        PROTOCOL,
        INVISIBLE
    }

    private final JellyDancePlugin plugin;
    private final VanillaModelLibraryApi library;
    private final HideMode hideMode;
    private final ChoreographyLoader choreographies;
    private final int interpolationTicks;
    private final float viewRange;
    private final boolean freezeInPlace;
    private final double freezeTolerance;
    private final boolean debug;
    private final Map<UUID, DanceSession> byEntity = new LinkedHashMap<>();
    private final Map<UUID, DanceSession> byDisplay = new LinkedHashMap<>();
    private BukkitTask ticker;

    public DanceManager(JellyDancePlugin plugin, VanillaModelLibraryApi library, HideMode hideMode,
                        ChoreographyLoader choreographies, int interpolationTicks, float viewRange,
                        boolean freezeInPlace, double freezeTolerance, boolean debug) {
        this.plugin = plugin;
        this.library = library;
        this.hideMode = hideMode;
        this.choreographies = choreographies;
        this.interpolationTicks = Math.max(0, interpolationTicks);
        this.viewRange = Math.min(1.0f, Math.max(0.0f, viewRange));
        this.freezeInPlace = freezeInPlace;
        this.freezeTolerance = Math.max(0.01, freezeTolerance);
        this.debug = debug;
    }

    public void startTicking() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stopTicking() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    public Optional<DanceSession> start(LivingEntity entity, ModelSelection selection,
                                        DanceChoreography choreography) {
        if (byEntity.containsKey(entity.getUniqueId()) || !entity.isValid() || entity.isDead()) {
            return Optional.empty();
        }
        Optional<ItemStack> stack = library.createItem(selection.modelId());
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        ModelDisplay displaySpec = selection.display();
        DanceSession session = new DanceSession(entity.getUniqueId(), entity.getWorld().getUID(),
                selection.modelId(), displaySpec, choreography.durationTicks(),
                choreography.phaseOffset(entity.getUniqueId()), entity.getLocation().clone(),
                choreography);
        if (freezeInPlace) {
            entity.setVelocity(new Vector(0, 0, 0));
            if (entity instanceof Mob mob) {
                session.originalAI = mob.hasAI();
                mob.setAI(false);
            }
        }
        hide(entity, session);

        ItemDisplay display;
        try {
            display = entity.getWorld().spawn(entity.getLocation(), ItemDisplay.class, spawned -> {
                spawned.setItemStack(stack.get());
                spawned.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                spawned.setBillboard(Display.Billboard.FIXED);
                spawned.setPersistent(false);
                spawned.setInvulnerable(true);
                spawned.setGravity(false);
                spawned.setSilent(true);
                spawned.setViewRange(viewRange);
                spawned.setInterpolationDuration(interpolationTicks);
                spawned.setTeleportDuration(interpolationTicks);
                spawned.setInterpolationDelay(0);
                spawned.setRotation(entity.getYaw() + displaySpec.yawOffsetDegrees(), 0f);
                spawned.setTransformation(transform(session, 0));
            });
        } catch (RuntimeException error) {
            restore(entity, session);
            plugin.getLogger().warning("cannot spawn ItemDisplay for " + entity.getUniqueId()
                    + ": " + error.getMessage());
            return Optional.empty();
        }
        session.displayId = display.getUniqueId();
        byEntity.put(session.entityId, session);
        byDisplay.put(session.displayId, session);
        if (debug) {
            plugin.getLogger().info("dance started: " + entity.getType() + " " + session.entityId
                    + " -> " + session.modelId + " display=" + session.displayId);
        }
        return Optional.of(session);
    }

    public boolean isDancing(Entity entity) {
        return entity != null && byEntity.containsKey(entity.getUniqueId());
    }

    public boolean isDancingDisplay(Entity entity) {
        return entity != null && byDisplay.containsKey(entity.getUniqueId());
    }

    public int active() {
        return byEntity.size();
    }

    public List<DanceSession> sessions() {
        return List.copyOf(byEntity.values());
    }

    public void stop(Entity entity, String reason) {
        if (entity != null) {
            stopById(entity.getUniqueId(), reason);
        }
    }

    public void stopById(UUID entityId, String reason) {
        DanceSession session = byEntity.get(entityId);
        if (session != null) {
            end(session, reason);
        }
    }

    public void stopAll(String reason) {
        for (DanceSession session : new ArrayList<>(byEntity.values())) {
            end(session, reason);
        }
    }

    public void stopInWorld(World world, String reason) {
        for (DanceSession session : new ArrayList<>(byEntity.values())) {
            if (session.worldId.equals(world.getUID())) {
                end(session, reason);
            }
        }
    }

    /** Called when a player joins while dances are running, and after a reload. */
    public void hideFrom(Player player) {
        for (DanceSession session : byEntity.values()) {
            Entity entity = Bukkit.getEntity(session.entityId);
            if (entity != null) {
                player.hideEntity(plugin, entity);
            }
        }
    }

    private void tick() {
        for (DanceSession session : new ArrayList<>(byEntity.values())) {
            Entity entity = Bukkit.getEntity(session.entityId);
            Entity displayEntity = Bukkit.getEntity(session.displayId);
            if (!(entity instanceof LivingEntity) || !entity.isValid() || entity.isDead()) {
                end(session, "entity gone");
                continue;
            }
            if (!(displayEntity instanceof ItemDisplay display) || !displayEntity.isValid()) {
                end(session, "display gone");
                continue;
            }
            if (freezeInPlace) {
                root(entity, session);
            }
            updateDisplay(entity, display, session);
            session.elapsed++;
            if (session.elapsed >= session.durationTicks) {
                end(session, "finished");
            }
        }
    }

    private void updateDisplay(Entity entity, ItemDisplay display, DanceSession session) {
        Location target = entity.getLocation();
        target.setYaw(entity.getYaw() + session.display.yawOffsetDegrees());
        target.setPitch(0f);
        if (display.getWorld() != entity.getWorld()) {
            display.teleport(target);
        } else if (display.getLocation().distanceSquared(target) > 1.0E-6) {
            display.teleport(target);
        } else {
            float yaw = target.getYaw();
            if (Math.abs(wrap(display.getYaw() - yaw)) > 0.01f) {
                display.setRotation(yaw, 0f);
            }
        }
        display.setTransformation(transform(session, session.elapsed));
    }

    private Transformation transform(DanceSession session, int tick) {
        DanceChoreography.Frame frame = session.choreography.sample(tick + session.phaseOffset);
        ModelDisplay spec = session.display;
        Vector3f translation = new Vector3f(
                spec.offsetX() + frame.x(),
                spec.offsetY() + frame.y(),
                spec.offsetZ() + frame.z());
        Vector3f scale = new Vector3f(
                spec.scale() * frame.scaleX(),
                spec.scale() * frame.scaleY(),
                spec.scale() * frame.scaleZ());
        // Minecraft yaw turns clockwise around Y, so the yaw is negated here.
        Quaternionf left = new Quaternionf().rotationYXZ(
                (float) Math.toRadians(-frame.yaw()),
                (float) Math.toRadians(frame.pitch()),
                (float) Math.toRadians(frame.roll()));
        return new Transformation(translation, left, scale, new Quaternionf());
    }

    public Optional<DanceChoreography> choreography(String id) {
        return choreographies.get(id);
    }

    public Set<String> choreographyIds() {
        return choreographies.ids();
    }

    public DanceChoreography defaultChoreography() {
        return choreographies.defaultChoreography();
    }

    public int reloadChoreographies() {
        return choreographies.reload();
    }

    private void end(DanceSession session, String reason) {
        if (byEntity.remove(session.entityId) == null) {
            return;
        }
        byDisplay.remove(session.displayId);
        Entity display = Bukkit.getEntity(session.displayId);
        if (display != null) {
            display.remove();
        }
        Entity entity = Bukkit.getEntity(session.entityId);
        if (entity != null) {
            restore(entity, session);
        }
        if (debug) {
            plugin.getLogger().info("dance ended: " + session.entityId + " (" + reason + ")");
        }
    }

    /** Keeps a dancer on its anchor: no walking, no pushing, no turning away. */
    private void root(Entity entity, DanceSession session) {
        Vector velocity = entity.getVelocity();
        if (velocity.lengthSquared() > 1.0E-8) {
            entity.setVelocity(new Vector(0, 0, 0));
        }
        Location anchor = session.anchor;
        if (entity.getWorld() != anchor.getWorld()) {
            return;
        }
        entity.setRotation(anchor.getYaw(), anchor.getPitch());
        if (entity instanceof LivingEntity living) {
            living.setBodyYaw(anchor.getYaw());
        }
        if (entity.getLocation().distanceSquared(anchor) > freezeTolerance * freezeTolerance) {
            entity.teleport(anchor);
        }
    }

    private void hide(Entity entity, DanceSession session) {
        session.originalInvisible = entity.isInvisible();
        session.originalCustomNameVisible = entity.isCustomNameVisible();
        session.originalGlowing = entity.isGlowing();
        entity.setCustomNameVisible(false);

        if (hideMode == HideMode.INVISIBLE) {
            session.usedInvisibleFallback = true;
            entity.setInvisible(true);
            entity.setGlowing(false);
            return;
        }
        boolean leaked = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideEntity(plugin, entity);
            if (player.canSee(entity)) {
                leaked = true;
            }
        }
        if (leaked) {
            // The tracking path did not take for every viewer; keep the rest of
            // the frame clean with the vanilla flag as a safety net.
            session.usedInvisibleFallback = true;
            entity.setInvisible(true);
            entity.setGlowing(false);
            plugin.getLogger().warning("per-player hide did not stick for " + entity.getUniqueId()
                    + "; falling back to invisibility for this dance");
        }
    }

    private void restore(Entity entity, DanceSession session) {
        if (session.originalAI != null && entity instanceof Mob mob) {
            mob.setAI(session.originalAI);
        }
        if (session.usedInvisibleFallback) {
            entity.setInvisible(session.originalInvisible);
            entity.setGlowing(session.originalGlowing);
        }
        entity.setCustomNameVisible(session.originalCustomNameVisible);
        if (hideMode == HideMode.PROTOCOL) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.showEntity(plugin, entity);
            }
        }
    }

    private static float wrap(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        }
        if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }
}
