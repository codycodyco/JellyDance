package dev.cody.jellydance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DanceChoreographyTest {

    private static DanceChoreography dance(List<DanceChoreography.Event> events) {
        return new DanceChoreography("test", "Test", 2.0,
                DanceChoreography.Phase.SYNC, events);
    }

    @Test
    void rotateAccumulatesDegreesPerSecond() {
        DanceChoreography choreography = dance(List.of(
                new DanceChoreography.RotateEvent(0, 180, 0, 0, false)));
        // 1 second = 20 ticks
        assertEquals(180f, choreography.sample(20).yaw(), 0.001);
        assertEquals(360f, choreography.sample(40).yaw(), 0.001);
    }

    @Test
    void cancelRotateHoldsTheCurrentAngleByDefault() {
        DanceChoreography choreography = dance(List.of(
                new DanceChoreography.RotateEvent(0, 180, 0, 0, false),
                new DanceChoreography.CancelEvent(20, DanceChoreography.Target.ROTATE,
                        false, false)));
        assertEquals(180f, choreography.sample(40).yaw(), 0.001);
    }

    @Test
    void cancelRotateWithResetSnapsToZero() {
        DanceChoreography choreography = dance(List.of(
                new DanceChoreography.RotateEvent(0, 180, 0, 0, false),
                new DanceChoreography.CancelEvent(20, DanceChoreography.Target.ROTATE,
                        true, false)));
        assertEquals(0f, choreography.sample(40).yaw(), 0.001);
    }

    @Test
    void scaleOscillatesAndCancelReturnsToNormal() {
        DanceChoreography choreography = dance(List.of(
                new DanceChoreography.ScaleEvent(0, 0.20, 1.0, DanceChoreography.Mode.SQUASH),
                new DanceChoreography.CancelEvent(10, DanceChoreography.Target.SCALE,
                        false, false)));
        DanceChoreography.Frame peak = choreography.sample(5); // 0.25s, sin = 1
        assertEquals(1.10f, peak.scaleX(), 0.001);
        assertEquals(0.80f, peak.scaleY(), 0.001);
        assertEquals(1.10f, peak.scaleZ(), 0.001);
        DanceChoreography.Frame after = choreography.sample(20);
        assertEquals(1.0f, after.scaleX(), 0.001);
        assertEquals(1.0f, after.scaleY(), 0.001);
    }

    @Test
    void cancelScaleWithHoldFreezesTheCurrentScale() {
        DanceChoreography choreography = dance(List.of(
                new DanceChoreography.ScaleEvent(0, 0.20, 1.0, DanceChoreography.Mode.SQUASH),
                new DanceChoreography.CancelEvent(5, DanceChoreography.Target.SCALE,
                        false, true)));
        DanceChoreography.Frame held = choreography.sample(30);
        assertEquals(1.10f, held.scaleX(), 0.001);
        assertEquals(0.80f, held.scaleY(), 0.001);
    }

    @Test
    void translateOscillatesAndCancelReturnsToZero() {
        DanceChoreography choreography = dance(List.of(
                new DanceChoreography.TranslateEvent(0, 0, 0.20, 0, 1.0),
                new DanceChoreography.CancelEvent(10, DanceChoreography.Target.TRANSLATE,
                        false, false)));
        assertEquals(0.20f, choreography.sample(5).y(), 0.001);
        assertEquals(0.0f, choreography.sample(20).y(), 0.001);
    }

    @Test
    void syncPhaseMeansNoOffsetAndRandomStaysInWindow() {
        DanceChoreography sync = dance(List.of());
        assertEquals(0, sync.phaseOffset(UUID.randomUUID()));

        DanceChoreography random = new DanceChoreography("test", "Test", 2.0,
                DanceChoreography.Phase.RANDOM, List.of());
        for (int i = 0; i < 50; i++) {
            int offset = random.phaseOffset(UUID.randomUUID());
            assertTrue(offset >= 0 && offset < 40);
        }
    }

    @Test
    void screenShakeTogglesOnTheTimeline() {
        DanceChoreography choreography = dance(List.of(
                new DanceChoreography.ScreenShakeEvent(0, true, 0.03, 2.0, 16.0),
                new DanceChoreography.ScreenShakeEvent(20, false, 0.03, 2.0, 16.0)));
        DanceChoreography.ShakeState on = choreography.shakeAt(10);
        assertTrue(on.enabled());
        assertEquals(0.03, on.amplitude(), 0.0001);
        assertEquals(2.0, on.frequency(), 0.0001);
        assertEquals(16.0, on.radius(), 0.0001);
        assertEquals(0, on.startedAt());
        assertTrue(!choreography.shakeAt(20).enabled());
        assertTrue(choreography.hasScreenShake());
        assertEquals(16.0, choreography.maxShakeRadius(), 0.0001);
    }

    @Test
    void durationConvertsSecondsToTicks() {
        DanceChoreography choreography = new DanceChoreography("test", "Test", 7.5,
                DanceChoreography.Phase.SYNC, List.of());
        assertEquals(150, choreography.durationTicks());
    }
}
