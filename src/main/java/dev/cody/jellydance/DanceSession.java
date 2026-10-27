package dev.cody.jellydance;

import dev.cody.vanillamobs.api.ModelDisplay;
import java.util.UUID;
import org.bukkit.Location;

/** One dancing mob: the hidden original, its ItemDisplay, and the motion state. */
final class DanceSession {

    final UUID entityId;
    final UUID worldId;
    final String modelId;
    final ModelDisplay display;
    final int durationTicks;
    final DanceChoreography choreography;
    final int phaseOffset;
    final Location anchor;
    Boolean originalAI;
    UUID displayId;
    int elapsed;
    boolean usedInvisibleFallback;
    boolean originalInvisible;
    boolean originalCustomNameVisible;
    boolean originalGlowing;

    DanceSession(UUID entityId, UUID worldId, String modelId, ModelDisplay display,
                 int durationTicks, int phaseOffset, Location anchor,
                 DanceChoreography choreography) {
        this.entityId = entityId;
        this.worldId = worldId;
        this.modelId = modelId;
        this.display = display;
        this.durationTicks = durationTicks;
        this.phaseOffset = phaseOffset;
        this.anchor = anchor;
        this.choreography = choreography;
    }

    int remainingTicks() {
        return Math.max(0, durationTicks - elapsed);
    }
}
