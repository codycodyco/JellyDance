# JellyDance

**Make Minecraft mobs dance!**

JellyDance is a Paper plugin that makes nearby vanilla mobs spin, bounce, and dance to music. It uses [VanillaModelLibrary](https://github.com/codycodyco/VanillaModelLibrary) for mob models and [RP Engine](https://github.com/resourcepack-ai/rp-engine) to display them.

You can choose a dance, make a group of mobs move together, or create your own routines with simple YAML files.

> Only mobs with a supported model in VanillaModelLibrary can dance. JellyDance animates the **whole model**, not individual arms, legs, or heads.

## Features

- **Mob dancing:** Make nearby supported mobs spin, bounce, and move in sync or at different times.
- **Custom dances:** Create routines with timed rotation, scaling, and movement.
- **Music:** Play a custom sound alongside a dance.
- **Player emotes:** Optionally let the player who starts a dance join in using an RP Engine emote.
- **Automatic assets:** Bundled music and emote resources are installed into RP Engine automatically.
- **Easy cleanup:** Mobs return to their normal appearance when the dance ends or is stopped.

## Installation

**Requirements:** Paper 26.2, Java 25, [VanillaModelLibrary](https://github.com/codycodyco/VanillaModelLibrary), and [RP Engine](https://github.com/resourcepack-ai/rp-engine).

1. Install RP Engine and VanillaModelLibrary.
2. Put the JellyDance JAR in your server's `plugins/` folder.
3. Restart the server.
4. Run `/dance list` to see the available dances.

JellyDance comes with two dances: `default` and `wine`. The first launch also creates editable dance files in `plugins/JellyDance/dances/`.

## Quick start

Run these commands in-game:

```text
/dance                   # Start the default dance near you
/dance start 12 wine     # Play "wine" for nearby supported mobs within 12 blocks
/dance list              # Show available dances
/dance stop              # Stop your own player emote (or all dances if you're an admin)
```

The default dance radius is **12 blocks**, and you can change it in `plugins/JellyDance/config.yml`.

## Commands

| Command | What it does |
| --- | --- |
| `/dance` | Start the default dance near you |
| `/dance start [radius] [dance]` | Start a dance for nearby mobs |
| `/dance list` | List available dances |
| `/dance stop` | Stop your own player emote; admins stop all active dances |
| `/dance startat <player\|x y z> [radius] [dance]` | Start a dance at another location (admin) |
| `/dance reload` | Reload the config and dance files (admin) |
| `/dance status` | Show active dances (admin) |
| `/dance why [player\|x y z] [radius]` | Check why a nearby mob does or doesn't have a matching model (admin) |

Basic commands are available to everyone (`jellydance.use`). Admin commands require `jellydance.admin`, which is granted to OPs by default.

## Create your own dance

Add a `.yml` file to `plugins/JellyDance/dances/`. The filename becomes the dance name.

For example, save this as `bounce.yml`:

```yaml
name: Bounce
duration: 10
phase: sync

events:
  - { time: 0, type: rotate, yaw-speed: 180 }
  - { time: 0, type: scale, amount: 0.20, frequency: 2.0, mode: squash }
  - { time: 0, type: translate, y: 0.15, frequency: 1.0 }
```

Run `/dance reload`, then `/dance start 12 bounce`.

- `duration` and `time` are in seconds.
- `rotate` spins the model; `scale` makes it pulse or squash; `translate` moves it back and forth.
- `phase: sync` keeps the group together; `phase: random` gives mobs different starting offsets.
- Use a `cancel` event to stop an effect before the dance ends, such as `{ time: 5, type: cancel, target: rotate }`.

You can also add `music` and player emote settings. See the bundled [wine.yml](src/main/resources/dances/wine.yml) for an example.

## Music and player emotes

JellyDance includes a sample music track and RP Engine player emote used by `wine`. These assets are installed under:

```text
plugins/RPEngine/content/jellydance/
```

For your own music, add the sound to RP Engine, register its sound ID, and reference it in your dance file:

```yaml
music:
  sound: "jellydance:wine"
  volume: 1.0
  radius: 32.0

players: true
player-emote: "jellydance_wine"
```

Player emotes affect **only the player who runs the command**, not everyone nearby. After editing RP Engine sound or emote assets, run `/rp reload`.

## Good to know

- **Supported mobs only:** If VanillaModelLibrary can't find a suitable model, that mob is skipped.
- **No skeletal animation:** Dancing is done by moving and transforming a replacement 3D model.
- **Mobs stand still while dancing:** This is enabled by default. Their AI is restored on normal cleanup, but a server crash during a dance may leave an affected mob with AI disabled.
- **Experimental screen shake:** Disabled by default. It simulates a shake by changing nearby players' walk speed, so it may affect movement and is not a true camera effect.
- **Some settings need a restart:** `/dance reload` reads dance files and config, but changes such as `hide-mode` and `freeze-in-place` take effect after restarting the plugin/server.

## Building from source

Requires **JDK 25** and **Gradle**. Build VanillaModelLibrary 0.2.2 into `../library/build/libs/` first (the build references that local JAR), then run:

```bash
gradle build
```

The resulting JAR will be in `build/libs/`.
