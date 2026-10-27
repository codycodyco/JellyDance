package dev.cody.jellydance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Loads authored dances from plugins/JellyDance/dances/*.yml. */
public final class ChoreographyLoader {

    private final JellyDancePlugin plugin;
    private final Path directory;
    private Map<String, DanceChoreography> styles = Map.of();

    public ChoreographyLoader(JellyDancePlugin plugin) {
        this.plugin = plugin;
        this.directory = plugin.getDataFolder().toPath().resolve("dances");
    }

    public int reload() {
        try {
            Files.createDirectories(directory);
            saveDefault("dances/default.yml");
            saveDefault("dances/wine.yml");
        } catch (IOException error) {
            plugin.getLogger().warning("cannot create dances directory: " + error.getMessage());
        }

        Map<String, DanceChoreography> loaded = new LinkedHashMap<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                    .endsWith(".yml")).sorted().toList()) {
                String id = file.getFileName().toString();
                id = id.substring(0, id.length() - 4).toLowerCase(Locale.ROOT);
                if (!id.matches("[a-z0-9_-]+")) {
                    plugin.getLogger().warning("Skipping dance with invalid name: " + file.getFileName());
                    continue;
                }
                try {
                    loaded.put(id, parse(id, file));
                } catch (RuntimeException error) {
                    plugin.getLogger().warning("Skipping dance " + file.getFileName() + ": " + error.getMessage());
                }
            }
        } catch (IOException error) {
            plugin.getLogger().warning("cannot list dances directory: " + error.getMessage());
        }

        if (!loaded.containsKey("default")) {
            plugin.getLogger().warning("No valid dances/default.yml; using built-in fallback");
            loaded.put("default", DanceChoreography.fallback());
        }
        styles = Map.copyOf(loaded);
        plugin.getLogger().info("Loaded " + styles.size() + " dance(s): " + String.join(", ", styles.keySet()));
        return styles.size();
    }

    private void saveDefault(String resource) {
        Path file = plugin.getDataFolder().toPath().resolve(resource);
        if (Files.isRegularFile(file)) {
            return;
        }
        try {
            plugin.saveResource(resource, false);
        } catch (IllegalArgumentException error) {
            plugin.getLogger().warning("Missing bundled resource " + resource);
        }
    }

    public Optional<DanceChoreography> get(String id) {
        if (id == null || id.isBlank()) {
            return Optional.ofNullable(styles.get("default"));
        }
        return Optional.ofNullable(styles.get(id.toLowerCase(Locale.ROOT)));
    }

    public DanceChoreography defaultChoreography() {
        return styles.getOrDefault("default", DanceChoreography.fallback());
    }

    public Set<String> ids() {
        return new TreeSet<>(styles.keySet());
    }

    private DanceChoreography parse(String id, Path file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        double duration = yaml.getDouble("duration", 10.0);
        if (duration < 0.05 || duration > 3600.0) {
            throw new IllegalArgumentException("duration must be 0.05..3600 seconds");
        }
        int durationTicks = Math.max(1, (int) Math.round(duration * 20.0));
        String name = yaml.getString("name", id);
        DanceChoreography.Phase phase;
        try {
            phase = DanceChoreography.phase(yaml.getString("phase", "sync"));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("phase must be sync or random");
        }
        if (!yaml.contains("events") && yaml.contains("tracks")) {
            throw new IllegalArgumentException("legacy tracks format is not supported; use events");
        }
        List<DanceChoreography.Event> events = parseEvents(yaml.getMapList("events"), durationTicks);
        DanceChoreography.Music music = parseMusic(yaml);
        boolean players = yaml.getBoolean("players", false);
        String playerEmote = yaml.getString("player-emote", "");
        if (players && (playerEmote == null || playerEmote.isBlank())) {
            throw new IllegalArgumentException("players: true requires player-emote");
        }
        return new DanceChoreography(id, name, duration, phase, events, music,
                players, playerEmote);
    }

    private List<DanceChoreography.Event> parseEvents(List<Map<?, ?>> maps, int durationTicks) {
        List<DanceChoreography.Event> events = new ArrayList<>();
        for (Map<?, ?> map : maps) {
            double time = requiredNumber(map, "time");
            if (time < 0 || time > durationTicks / 20.0 + 0.001) {
                throw new IllegalArgumentException("event time " + time + " outside 0..duration");
            }
            int tick = Math.max(0, Math.min(durationTicks, (int) Math.round(time * 20.0)));
            String type = string(map, "type", "").toLowerCase(Locale.ROOT);
            switch (type) {
                case "rotate" -> events.add(new DanceChoreography.RotateEvent(
                        tick,
                        number(map, "yaw-speed", number(map, "speed", 0)),
                        number(map, "pitch-speed", 0),
                        number(map, "roll-speed", 0),
                        bool(map, "reset", false)));
                case "scale" -> {
                    double amount = number(map, "amount", 0.1);
                    double frequency = number(map, "frequency", 1.0);
                    if (amount < 0 || amount > 1) {
                        throw new IllegalArgumentException("scale amount must be 0..1");
                    }
                    if (frequency <= 0) {
                        throw new IllegalArgumentException("scale frequency must be > 0");
                    }
                    events.add(new DanceChoreography.ScaleEvent(tick, amount, frequency,
                            mode(map, "mode", "squash")));
                }
                case "translate" -> {
                    double frequency = number(map, "frequency", 1.0);
                    if (frequency <= 0) {
                        throw new IllegalArgumentException("translate frequency must be > 0");
                    }
                    events.add(new DanceChoreography.TranslateEvent(tick,
                            number(map, "x", 0), number(map, "y", 0), number(map, "z", 0),
                            frequency));
                }
                case "screen-shake" -> {
                    boolean enabled = bool(map, "enabled", true);
                    double amplitude = number(map, "amplitude",
                            plugin.getConfig().getDouble("screen-shake.amplitude", 0.02));
                    double frequency = number(map, "frequency",
                            plugin.getConfig().getDouble("screen-shake.frequency", 8.0));
                    double radius = number(map, "radius",
                            plugin.getConfig().getDouble("screen-shake.radius", 16.0));
                    if (amplitude < 0 || amplitude > 0.5) {
                        throw new IllegalArgumentException("screen-shake amplitude must be 0..0.5");
                    }
                    if (frequency < 0.1 || frequency > 10) {
                        throw new IllegalArgumentException("screen-shake frequency must be 0.1..10");
                    }
                    if (radius < 1 || radius > 128) {
                        throw new IllegalArgumentException("screen-shake radius must be 1..128");
                    }
                    events.add(new DanceChoreography.ScreenShakeEvent(tick, enabled,
                            amplitude, frequency, radius));
                }
                case "cancel" -> {
                    String target = string(map, "target", "");
                    DanceChoreography.Target parsed;
                    try {
                        parsed = DanceChoreography.target(target);
                    } catch (IllegalArgumentException error) {
                        throw new IllegalArgumentException("cancel target must be rotate/scale/translate/all");
                    }
                    events.add(new DanceChoreography.CancelEvent(tick, parsed,
                            bool(map, "reset", false), bool(map, "hold", false)));
                }
                default -> throw new IllegalArgumentException("unknown event type: " + type);
            }
        }
        events.sort(Comparator.comparingInt(DanceChoreography.Event::tick));
        return events;
    }

    private DanceChoreography.Music parseMusic(YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("music");
        if (section == null) {
            return null;
        }
        String sound = section.getString("sound", "");
        sound = sound == null ? "" : sound.trim();
        if (sound.isEmpty()) {
            throw new IllegalArgumentException("music.sound is required");
        }
        double volume = section.getDouble("volume", 1.0);
        double pitch = section.getDouble("pitch", 1.0);
        double delay = section.getDouble("start-delay", 0.0);
        double radius = section.getDouble("radius", 32.0);
        double length = section.getDouble("length", 0.0);
        boolean stopOnEnd = section.getBoolean("stop-on-end", true);
        boolean loop = section.getBoolean("loop", false);
        if (volume < 0 || volume > 4) {
            throw new IllegalArgumentException("music.volume must be 0..4");
        }
        if (pitch < 0.5 || pitch > 2) {
            throw new IllegalArgumentException("music.pitch must be 0.5..2");
        }
        if (delay < 0) {
            throw new IllegalArgumentException("music.start-delay must be >= 0");
        }
        if (radius < 1 || radius > 128) {
            throw new IllegalArgumentException("music.radius must be 1..128");
        }
        if (length < 0) {
            throw new IllegalArgumentException("music.length must be >= 0");
        }
        if (loop && length <= 0) {
            throw new IllegalArgumentException("music.loop requires music.length");
        }
        if (!stopOnEnd && length <= 0) {
            throw new IllegalArgumentException("music.stop-on-end: false requires music.length");
        }
        return new DanceChoreography.Music(sound, (float) volume, (float) pitch, delay,
                stopOnEnd, loop, radius, length);
    }

    private static double requiredNumber(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("missing numeric " + key);
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException(key + " must be finite");
        }
        return result;
    }

    private static double number(Map<?, ?> map, String key, double fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException(key + " must be finite");
        }
        return result;
    }

    private static String string(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean bool(Map<?, ?> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static DanceChoreography.Mode mode(Map<?, ?> map, String key, String fallback) {
        String value = string(map, key, fallback);
        try {
            return DanceChoreography.mode(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("scale mode must be squash or uniform");
        }
    }
}
