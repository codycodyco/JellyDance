package dev.cody.jellydance;

import dev.cody.vanillamobs.api.Appearance;
import dev.cody.vanillamobs.api.ModelSelection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

public final class DanceCommand implements CommandExecutor {

    private final JellyDancePlugin plugin;

    public DanceCommand(JellyDancePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        DanceManager manager = plugin.manager();
        if (manager == null) {
            sender.sendMessage("JellyDance is inactive: VanillaModelLibrary is missing.");
            return true;
        }
        String sub = args.length == 0 ? "start" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> start(sender, manager, args);
            case "startat" -> startAt(sender, manager, args);
            case "list" -> list(sender, manager);
            case "reload" -> {
                if (!sender.hasPermission("jellydance.admin")) {
                    sender.sendMessage("You need jellydance.admin.");
                    return true;
                }
                plugin.reloadDances();
                sender.sendMessage("JellyDance reloaded " + manager.choreographyIds().size()
                        + " dance(s): " + String.join(", ", manager.choreographyIds()));
            }
            case "stop" -> {
                if (sender instanceof Player player && !sender.hasPermission("jellydance.admin")) {
                    if (plugin.playerEmotes() != null) {
                        plugin.playerEmotes().stop(player.getUniqueId());
                    }
                    sender.sendMessage("JellyDance stopped your player dance.");
                    return true;
                }
                if (!sender.hasPermission("jellydance.admin")) {
                    sender.sendMessage("You need jellydance.admin.");
                    return true;
                }
                int before = manager.active();
                manager.stopAll("command");
                if (plugin.musicPlayer() != null) {
                    plugin.musicPlayer().stopAll();
                }
                if (plugin.screenShake() != null) {
                    plugin.screenShake().stopAll();
                }
                if (plugin.playerEmotes() != null) {
                    plugin.playerEmotes().stopAll();
                }
                sender.sendMessage("JellyDance stopped " + before + " dance(s).");
            }
            case "status" -> {
                if (!sender.hasPermission("jellydance.admin")) {
                    sender.sendMessage("You need jellydance.admin.");
                    return true;
                }
                status(sender, manager);
            }
            case "why" -> why(sender, manager, args);
            default -> sender.sendMessage(
                    "Usage: /dance <start [radius] [dance]|startat <player|x y z> [radius] [dance]"
                            + "|list|reload|stop|status|why [player|x y z] [radius]>");
        }
        return true;
    }

    private void start(CommandSender sender, DanceManager manager, String[] args) {
        if (!sender.hasPermission("jellydance.use")) {
            sender.sendMessage("You need jellydance.use.");
            return;
        }
        if (!ready(sender)) {
            return;
        }
        Location center = sender instanceof Player player
                ? player.getLocation()
                : Bukkit.getWorlds().get(0).getSpawnLocation();
        RadiusStyle choice = choice(sender, args, 1);
        if (choice == null) {
            return;
        }
        DanceChoreography choreography = manager.choreography(choice.style()).orElse(null);
        if (choreography == null) {
            sender.sendMessage("Unknown dance: " + choice.style() + ". Use /dance list.");
            return;
        }
        started(sender, manager, center, choice.radius(), choreography);
        startPlayer(sender, choreography);
    }

    private void startAt(CommandSender sender, DanceManager manager, String[] args) {
        if (!sender.hasPermission("jellydance.admin")) {
            sender.sendMessage("You need jellydance.admin.");
            return;
        }
        if (!ready(sender)) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /dance startat <player|x y z> [radius] [dance]");
            return;
        }
        Location center = locationAt(args, 1);
        if (center == null) {
            sender.sendMessage("Usage: /dance startat <player|x y z> [radius] [dance]");
            return;
        }
        RadiusStyle choice = choice(sender, args, isCoordinate(args, 1) ? 4 : 2);
        if (choice == null) {
            return;
        }
        DanceChoreography choreography = manager.choreography(choice.style()).orElse(null);
        if (choreography == null) {
            sender.sendMessage("Unknown dance: " + choice.style() + ". Use /dance list.");
            return;
        }
        started(sender, manager, center, choice.radius(), choreography);
        startPlayer(sender, choreography);
    }

    private void startPlayer(CommandSender sender, DanceChoreography choreography) {
        if (!(sender instanceof Player player) || !choreography.players()) {
            return;
        }
        if (plugin.playerEmotes() == null) {
            sender.sendMessage("Player dance is unavailable: RPEngine API missing.");
            return;
        }
        if (plugin.playerEmotes().play(player, choreography.playerEmote(), choreography.durationTicks())) {
            sender.sendMessage("JellyDance player emote: " + choreography.playerEmote());
        } else {
            sender.sendMessage("Could not start player emote " + choreography.playerEmote()
                    + "; check RPEngine content and /rp reload.");
        }
    }

    private void started(CommandSender sender, DanceManager manager, Location center, double radius,
                         DanceChoreography choreography) {
        radius = Math.min(32.0, Math.max(1.0, radius));
        int max = Math.max(1, plugin.getConfig().getInt("dance.max-participants", 16));
        List<Entity> nearby = new ArrayList<>(center.getWorld().getNearbyEntities(center, radius, 4.0, radius));
        int started = 0;
        int noModel = 0;
        int already = 0;
        for (Entity entity : nearby) {
            if (started >= max) {
                break;
            }
            if (!(entity instanceof LivingEntity living) || living instanceof Player) {
                continue;
            }
            if (!living.isValid() || living.isDead()) {
                continue;
            }
            if (manager.isDancing(living)) {
                already++;
                continue;
            }
            Optional<ModelSelection> selection = plugin.library().selectForDisplay(living);
            if (selection.isEmpty()) {
                noModel++;
                continue;
            }
            if (manager.start(living, selection.get(), choreography).isPresent()) {
                started++;
            }
        }
        if (started > 0) {
            choreography.music().ifPresent(music -> {
                if (plugin.musicPlayer() != null) {
                    plugin.musicPlayer().play(music, center, choreography.durationTicks());
                }
            });
            if (plugin.screenShake() != null) {
                plugin.screenShake().play(center, choreography.durationTicks(), choreography);
            }
        }
        sender.sendMessage("JellyDance [" + choreography.id() + "]: started " + started
                + " of " + nearby.size() + " nearby entities"
                + " (skipped " + noModel + " without a deterministic model, "
                + already + " already dancing).");
    }

    private void list(CommandSender sender, DanceManager manager) {
        Set<String> ids = new LinkedHashSet<>(manager.choreographyIds());
        if (ids.isEmpty()) {
            sender.sendMessage("No dances loaded. Put .yml files in plugins/JellyDance/dances/.");
            return;
        }
        sender.sendMessage("JellyDance dances:");
        for (String id : ids) {
            manager.choreography(id).ifPresent(dance -> sender.sendMessage(
                    "  " + id + " - " + dance.name() + " (" + dance.durationTicks()
                            + "t, phase=" + dance.phase().name().toLowerCase(Locale.ROOT)
                            + (dance.players() ? ", player=" + dance.playerEmote() : "") + ")"));
        }
    }

    private void why(CommandSender sender, DanceManager manager, String[] args) {
        if (!sender.hasPermission("jellydance.admin")) {
            sender.sendMessage("You need jellydance.admin.");
            return;
        }
        Location center = sender instanceof Player player
                ? player.getLocation()
                : Bukkit.getWorlds().get(0).getSpawnLocation();
        int radiusIndex = 1;
        if (args.length > 1) {
            Location explicit = locationAt(args, 1);
            if (explicit != null) {
                center = explicit;
                radiusIndex = isCoordinate(args, 1) ? 4 : 2;
            }
        }
        double radius = radius(sender, args, radiusIndex);
        radius = Math.min(32.0, Math.max(1.0, radius));
        int shown = 0;
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, 4.0, radius)) {
            if (!(entity instanceof LivingEntity living) || living instanceof Player) {
                continue;
            }
            Appearance a = plugin.library().observe(living);
            String strict = plugin.library().select(living).map(ModelSelection::modelId).orElse("-");
            String display = plugin.library().selectForDisplay(living).map(ModelSelection::modelId).orElse("-");
            sender.sendMessage(entity.getType() + " " + entity.getUniqueId()
                    + " strict=" + strict + " display=" + display
                    + " age=" + a.age() + " pose=" + a.pose()
                    + " equipEmpty=" + a.equipmentEmpty() + " variant=" + a.variant()
                    + " color=" + a.color() + " sheared=" + a.sheared() + " jeb=" + a.jeb()
                    + " invisible=" + a.invisible() + " moving=" + a.moving()
                    + " headPitch=" + a.headPitchDegrees() + " headYaw=" + a.headYawDegrees());
            if (++shown >= 20) {
                sender.sendMessage("... more candidates omitted");
                break;
            }
        }
        if (shown == 0) {
            sender.sendMessage("No non-player living entities within " + radius + " blocks.");
        }
    }

    private RadiusStyle choice(CommandSender sender, String[] args, int index) {
        double radius = plugin.getConfig().getDouble("dance.radius", 6.0);
        String style = "default";
        if (args.length > index) {
            if (isNumber(args[index])) {
                radius = Double.parseDouble(args[index]);
                if (args.length > index + 1) {
                    style = args[index + 1];
                }
            } else {
                style = args[index];
            }
        }
        if (style.isBlank()) {
            sender.sendMessage("Dance name cannot be empty.");
            return null;
        }
        return new RadiusStyle(radius, style.toLowerCase(Locale.ROOT));
    }

    private Location locationAt(String[] args, int index) {
        Player target = Bukkit.getPlayerExact(args[index]);
        if (target != null) {
            return target.getLocation();
        }
        if (!isCoordinate(args, index)) {
            return null;
        }
        try {
            return new Location(Bukkit.getWorlds().get(0),
                    Double.parseDouble(args[index]),
                    Double.parseDouble(args[index + 1]),
                    Double.parseDouble(args[index + 2]));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private boolean isCoordinate(String[] args, int index) {
        if (args.length < index + 3) {
            return false;
        }
        try {
            Double.parseDouble(args[index]);
            Double.parseDouble(args[index + 1]);
            Double.parseDouble(args[index + 2]);
            return true;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private boolean isNumber(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private boolean ready(CommandSender sender) {
        if (plugin.library() == null || !plugin.library().ready()) {
            sender.sendMessage("VanillaModelLibrary is not ready: "
                    + (plugin.library() == null ? "missing" : "check /vanillamobs status"));
            return false;
        }
        return true;
    }

    private double radius(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            try {
                return Double.parseDouble(args[index]);
            } catch (NumberFormatException error) {
                sender.sendMessage("Radius must be a number; using the configured default.");
            }
        }
        return plugin.getConfig().getDouble("dance.radius", 6.0);
    }

    private void status(CommandSender sender, DanceManager manager) {
        sender.sendMessage("JellyDance: " + manager.active() + " active dance(s), library="
                + (plugin.library() != null && plugin.library().ready() ? "ready" : "not ready"));
        for (DanceSession session : manager.sessions()) {
            sender.sendMessage("  " + session.entityId + " -> " + session.modelId
                    + " style=" + session.choreography.id()
                    + " display=" + session.displayId + " remaining=" + session.remainingTicks() + "t");
        }
    }

    private record RadiusStyle(double radius, String style) {
    }
}
