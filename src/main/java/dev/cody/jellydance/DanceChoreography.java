package dev.cody.jellydance;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One authored dance: a timeline of timed behaviours. Behaviours keep running
 * (rotation accumulates, scale/translate oscillate) until a cancel event or the
 * dance's end time.
 *
 * <p>Times are in seconds. Translation is blocks relative to the entity's feet,
 * rotation is degrees added to the entity's own yaw, and scale is a multiplier
 * on the catalog's display scale (1 = authored size).
 */
public final class DanceChoreography {

    public enum Phase {
        SYNC,
        RANDOM
    }

    public enum Mode {
        SQUASH,
        UNIFORM
    }

    public enum Target {
        ROTATE,
        SCALE,
        TRANSLATE,
        ALL
    }

    public interface Event {
        int tick();
    }

    public record RotateEvent(int tick, double yawSpeed, double pitchSpeed,
                              double rollSpeed, boolean reset) implements Event {
    }

    public record ScaleEvent(int tick, double amount, double frequency, Mode mode) implements Event {
    }

    public record TranslateEvent(int tick, double x, double y, double z,
                                 double frequency) implements Event {
    }

    public record CancelEvent(int tick, Target target, boolean reset, boolean hold) implements Event {
    }

    public record Frame(float x, float y, float z,
                        float yaw, float pitch, float roll,
                        float scaleX, float scaleY, float scaleZ) {
    }

    public record Music(String sound, float volume, float pitch, double startDelaySeconds,
                        boolean stopOnEnd, boolean loop, double radius, double lengthSeconds) {
    }

    /** Turns walk-speed screen shake on or off at an absolute timeline tick. */
    public record ScreenShakeEvent(int tick, boolean enabled, double amplitude,
                                   double frequency, double radius) implements Event {
    }

    /** The shake state active at a sampled tick. */
    public record ShakeState(boolean enabled, double amplitude, double frequency,
                             double radius, int startedAt) {
    }

    private final String id;
    private final String name;
    private final double durationSeconds;
    private final int durationTicks;
    private final Phase phase;
    private final List<Event> events;
    private final Music music;
    private final boolean players;
    private final String playerEmote;

    public DanceChoreography(String id, String name, double durationSeconds, Phase phase,
                             List<Event> events) {
        this(id, name, durationSeconds, phase, events, null, false, null);
    }

    public DanceChoreography(String id, String name, double durationSeconds, Phase phase,
                             List<Event> events, Music music) {
        this(id, name, durationSeconds, phase, events, music, false, null);
    }

    public DanceChoreography(String id, String name, double durationSeconds, Phase phase,
                             List<Event> events, Music music, boolean players, String playerEmote) {
        this.id = id;
        this.name = name;
        this.durationSeconds = durationSeconds;
        this.durationTicks = Math.max(1, (int) Math.round(durationSeconds * 20.0));
        this.phase = phase;
        this.events = List.copyOf(events);
        this.music = music;
        this.players = players;
        this.playerEmote = playerEmote;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public double durationSeconds() {
        return durationSeconds;
    }

    public int durationTicks() {
        return durationTicks;
    }

    public Phase phase() {
        return phase;
    }

    public java.util.Optional<Music> music() {
        return java.util.Optional.ofNullable(music);
    }

    public boolean players() {
        return players;
    }

    public String playerEmote() {
        return playerEmote;
    }

    public boolean hasScreenShake() {
        for (Event event : events) {
            if (event instanceof ScreenShakeEvent shake && shake.enabled()) {
                return true;
            }
        }
        return false;
    }

    public double maxShakeRadius() {
        double radius = 0;
        for (Event event : events) {
            if (event instanceof ScreenShakeEvent shake && shake.enabled()) {
                radius = Math.max(radius, shake.radius());
            }
        }
        return radius;
    }

    public ShakeState shakeAt(int tick) {
        ScreenShakeEvent current = null;
        for (Event event : events) {
            if (event.tick() > tick) {
                break;
            }
            if (event instanceof ScreenShakeEvent shake) {
                current = shake;
            }
        }
        if (current == null || !current.enabled()) {
            return new ShakeState(false, 0, 0, 0, 0);
        }
        return new ShakeState(true, current.amplitude(), current.frequency(),
                current.radius(), current.tick());
    }

    public int phaseOffset(UUID entityId) {
        return phase == Phase.SYNC ? 0 : Math.floorMod(entityId.hashCode(), 40);
    }

    public Frame sample(int tick) {
        int target = Math.max(0, Math.min(durationTicks, tick));
        double yaw = 0;
        double pitch = 0;
        double roll = 0;
        double yawSpeed = 0;
        double pitchSpeed = 0;
        double rollSpeed = 0;
        ScaleEvent scale = null;
        double[] heldScale = null;
        TranslateEvent translate = null;
        double[] heldTranslate = null;
        int lastTick = 0;

        for (Event event : events) {
            if (event.tick() > target) {
                break;
            }
            double seconds = (event.tick() - lastTick) / 20.0;
            yaw += yawSpeed * seconds;
            pitch += pitchSpeed * seconds;
            roll += rollSpeed * seconds;
            lastTick = event.tick();

            switch (event) {
                case RotateEvent rotate -> {
                    if (rotate.reset()) {
                        yaw = 0;
                        pitch = 0;
                        roll = 0;
                    }
                    yawSpeed = rotate.yawSpeed();
                    pitchSpeed = rotate.pitchSpeed();
                    rollSpeed = rotate.rollSpeed();
                }
                case ScaleEvent start -> {
                    scale = start;
                    heldScale = null;
                }
                case TranslateEvent start -> {
                    translate = start;
                    heldTranslate = null;
                }
                case ScreenShakeEvent ignored -> {
                    // Player-view shake is sampled by ScreenShakeManager, not the model transform.
                }
                case CancelEvent cancel -> {
                    switch (cancel.target()) {
                        case ROTATE -> {
                            yawSpeed = 0;
                            pitchSpeed = 0;
                            rollSpeed = 0;
                            if (cancel.reset()) {
                                yaw = 0;
                                pitch = 0;
                                roll = 0;
                            }
                        }
                        case SCALE -> {
                            if (!cancel.hold()) {
                                heldScale = null;
                            } else if (scale != null) {
                                heldScale = scaleAt(scale, cancel.tick());
                            }
                            scale = null;
                        }
                        case TRANSLATE -> {
                            if (!cancel.hold()) {
                                heldTranslate = null;
                            } else if (translate != null) {
                                heldTranslate = translateAt(translate, cancel.tick());
                            }
                            translate = null;
                        }
                        case ALL -> {
                            yawSpeed = 0;
                            pitchSpeed = 0;
                            rollSpeed = 0;
                            if (cancel.reset()) {
                                yaw = 0;
                                pitch = 0;
                                roll = 0;
                            }
                            if (!cancel.hold()) {
                                heldScale = null;
                                heldTranslate = null;
                            } else {
                                if (scale != null) {
                                    heldScale = scaleAt(scale, cancel.tick());
                                }
                                if (translate != null) {
                                    heldTranslate = translateAt(translate, cancel.tick());
                                }
                            }
                            scale = null;
                            translate = null;
                        }
                    }
                }
                default -> throw new IllegalArgumentException("unknown dance event");
            }
        }

        double seconds = (target - lastTick) / 20.0;
        yaw += yawSpeed * seconds;
        pitch += pitchSpeed * seconds;
        roll += rollSpeed * seconds;

        double[] scaleValues = scale != null ? scaleAt(scale, target)
                : heldScale != null ? heldScale : new double[]{1, 1, 1};
        double[] translateValues = translate != null ? translateAt(translate, target)
                : heldTranslate != null ? heldTranslate : new double[]{0, 0, 0};
        return new Frame((float) translateValues[0], (float) translateValues[1],
                (float) translateValues[2], (float) yaw, (float) pitch, (float) roll,
                (float) scaleValues[0], (float) scaleValues[1], (float) scaleValues[2]);
    }

    private static double[] scaleAt(ScaleEvent event, int tick) {
        double wave = event.amount()
                * Math.sin(2.0 * Math.PI * event.frequency() * (tick - event.tick()) / 20.0);
        if (event.mode() == Mode.SQUASH) {
            return new double[]{1.0 + wave * 0.5, 1.0 - wave, 1.0 + wave * 0.5};
        }
        return new double[]{1.0 + wave, 1.0 + wave, 1.0 + wave};
    }

    private static double[] translateAt(TranslateEvent event, int tick) {
        double wave = Math.sin(2.0 * Math.PI * event.frequency() * (tick - event.tick()) / 20.0);
        return new double[]{event.x() * wave, event.y() * wave, event.z() * wave};
    }

    public static Phase phase(String value) {
        return Phase.valueOf(value.toUpperCase(Locale.ROOT));
    }

    public static Mode mode(String value) {
        return Mode.valueOf(value.toUpperCase(Locale.ROOT));
    }

    public static Target target(String value) {
        return Target.valueOf(value.toUpperCase(Locale.ROOT));
    }

    /** Built-in fallback if dances/default.yml is missing or invalid. */
    public static DanceChoreography fallback() {
        return new DanceChoreography("default", "Default", 10.0, Phase.RANDOM, List.of(
                new RotateEvent(0, 72, 0, 0, false),
                new ScaleEvent(0, 0.06, 2.0, Mode.SQUASH),
                new TranslateEvent(0, 0, 0.10, 0, 1.0)));
    }
}
