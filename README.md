# JellyDance

独立 Paper 插件：让附近任意 VanillaModelLibrary 可解析的原版非玩家生物跳整体模型舞。
它通过 VanillaModelLibrary 选模型、拿 ITEM，用**一个 ItemDisplay** 展示静态模型，并按
`dances/*.yml` 的行为时间轴持续旋转、缩放和位移。

## 视觉隐藏：协议层优先

默认 `hide-mode: protocol`：

- 对每个在线玩家调用 `Player#hideEntity(plugin, entity)`，客户端收到移除包，不再绘制原版实体。
- 监听 `PlayerTrackEntityEvent` 并取消：新进入视野的玩家永远不会收到该实体的 spawn 包。
- 协议层隐藏本身不改实体的身份、位置、AI、碰撞或游戏逻辑；只是客户端不可见。
- 舞蹈结束时 `showEntity`，并删除 ItemDisplay，不会出现“原版 + 模型”双重画面。
- 玩家在舞蹈中途加入时，`PlayerJoinEvent` 立即再次隐藏。

`hide-mode: invisible` 是回退方案：临时 `setInvisible(true)`、关闭名牌和发光，结束后恢复。
它会在实体上留下持久化状态，服务器崩溃时可能残留，因此只作为不能走协议层时的备选。

## 舞蹈流程

1. `VanillaModelLibraryApi.selectForDisplay(entity)` 严格匹配；匹配不到就跳过（不猜、不替换）。
   0.3.4 的 136 个 supported 模型全部开放，没有实体类型白名单。
2. `createItem(modelId)` 取 RP Engine 的 PAPER + `item_model` 组件。
3. 生成一个 `ItemDisplay`：`item_display: none`、`billboard: fixed`、无重力、不持久化。
4. 位置跟随实体脚底，朝向 `entity.yaw + catalog.display.yawOffsetDegrees`。
5. 每 tick 按行为时间轴采样 `Transformation`：位移、yaw/pitch/roll、缩放。
6. **原地定身**：`freeze-in-place: true` 时，舞蹈开始会暂停 AI、清零速度、锁定朝向，
   并在被推动时拉回起始锚点；结束/死亡/卸载/停用时恢复原 AI 状态。
7. 时长到达、实体死亡、区块卸载、世界卸载、展示实体消失、插件停用都会清理展示实体并
   恢复原版可见性。

## 编舞文件

每个舞一个文件，放在：

```text
plugins/JellyDance/dances/
  default.yml
  wine.yml
```

文件名就是舞名，`/dance start 12 wine` 会使用 `wine.yml`；不写舞名时使用 `default`。
插件首次启动会把内置的 `default.yml` / `wine.yml` 复制出来，已存在的文件不会被覆盖。

### 时间与行为

- `duration`：整段舞的截止时间，**单位秒**；到时所有行为停止、展示实体清理。
- `events`：按绝对时间执行的指令列表，`time` 也是**秒**。
- 行为一旦开始就持续循环，直到同类型的 `cancel` 或整段舞结束：
  - `rotate`：持续累计角度（角速度，度/秒）；
  - `scale`：持续压缩/放大振荡（幅度 + 频率）；
  - `translate`：持续位移振荡（振幅 + 频率）。
- `phase: sync` 群体同步；`random` 每只错开最多 2 秒。

### 示例

```yaml
name: Wine
duration: 10
phase: sync

events:
  # 0 秒开始：180 度/秒持续旋转（10 秒共五圈）
  - { time: 0, type: rotate, yaw-speed: 180 }

  # 0 秒开始：20% 压缩，每秒 2 次，竖向压扁/横向鼓出
  - { time: 0, type: scale, amount: 0.20, frequency: 2.0, mode: squash }

  # 0 秒开始：上下 ±0.15 格，每秒 1 次
  - { time: 0, type: translate, y: 0.15, frequency: 1.0 }

  # 可选取消：旋转停在当前角度；scale 回到 1.0
  # - { time: 7, type: cancel, target: rotate }
  # - { time: 8, type: cancel, target: scale }
```

### 行为参数

#### rotate

```yaml
- { time: 0, type: rotate, yaw-speed: 180 }
```

| 字段 | 含义 |
|---|---|
| `yaw-speed` | 绕 Y 轴角速度，度/秒；负数为反方向 |
| `pitch-speed` | 绕 X 轴角速度，可选 |
| `roll-speed` | 绕 Z 轴角速度，可选 |
| `reset` | `true` 时在该事件开始时把累计角度归零 |

也可以写 `speed` 作为 `yaw-speed` 的别名。

#### scale

```yaml
- { time: 0, type: scale, amount: 0.20, frequency: 2.0, mode: squash }
```

| 字段 | 含义 |
|---|---|
| `amount` | 0..1，振荡幅度；0.20 = ±20% |
| `frequency` | 每秒循环次数，必须 > 0 |
| `mode` | `squash`（Y 压扁、X/Z 鼓出）或 `uniform`（三轴等比） |

#### translate

```yaml
- { time: 0, type: translate, y: 0.15, frequency: 1.0 }
```

- `x/y/z`：振幅，单位方块，默认 0；
- `frequency`：每秒循环次数，必须 > 0。

#### cancel

```yaml
- { time: 7, type: cancel, target: rotate }
```

| 字段 | 含义 |
|---|---|
| `target` | `rotate` / `scale` / `translate` / `all` |
| `reset` | 旋转取消时是否归零；`false`（默认）停在当前角度 |
| `hold` | 缩放/位移取消时是否停在当前值；`false`（默认）回到 1.0 / 0 |

- `cancel rotate` 默认**停在当前角度**；`reset: true` 立即归零。
- `cancel scale` 默认**回到 1.0**；`hold: true` 停在当前比例。
- `cancel translate` 默认**回到 0**；`hold: true` 停在当前偏移。
- `cancel, target: all` 同时处理三类。

### 校验规则

- 事件 `time` 必须在 `0..duration`，可以乱序写，加载时按时间排序；
- `duration` 范围 `0.05..3600` 秒；
- `scale.amount` 必须 `0..1`，`frequency` 必须 > 0；
- 未知 `type`、未知 `target`、旧版 `tracks:` 格式都会让该文件被跳过并在控制台报原因；
- 某个文件无效不影响其它舞；`default` 无效时使用内置 fallback。

## 音乐

编舞文件可以绑定一个声音事件：

```yaml
music:
  sound: "jellydance:wine"   # RP Engine content/jellydance 里的声音 ID
  volume: 1.0
  pitch: 1.0
  start-delay: 0.0           # 秒
  stop-on-end: true          # 舞蹈结束停止
  loop: false                # true 时按 length 循环
  radius: 32.0               # 只对附近玩家播放
  length: 38.545             # 秒；loop 或 stop-on-end: false 时必填
```

- 音乐在**舞蹈组中心**只播放一次，不会每只生物各播一份；
- 播放/停止由 JellyDance 的 `MusicPlayer` 负责；
- `stop-on-end: true` 时按编舞 `duration` 停止；
- `stop-on-end: false` 或 `loop: true` 时必须给 `length`；
- 中途加入的玩家不会自动补播。

### 资源包放置

音乐作为 RP Engine 的独立命名空间，和模型一起进 main 包：

```text
plugins/RPEngine/content/jellydance/
  pack.yml
  sounds/music.yml
  assets/sounds/music/wine.ogg
```

`sounds/music.yml`：

```yaml
wine:
  file: music/wine
  category: music
  subtitle: "Wine"
  volume: 1.0
  pitch: 1.0
  stream: true
  length: 38.545
```

- 只支持 **Ogg Vorbis**；Ogg FLAC / MP3 改名都不会响；
- `stream: true` 让客户端从包里流式读取；
- 修改后 `/rp reload`，RP Engine 会重新生成 main.zip，客户端按新的包 SHA-1 重新下载；
- 当前测试服 main.zip 约 3.4 MB，其中 `wine.ogg` 590 KB。

## 实验性屏幕震动

屏幕震动是编舞 `events` 里的事件，可以按绝对时间开关：

```yaml
events:
  - { time: 9,  type: screen-shake, enabled: true,
      amplitude: 0.03, frequency: 2.0, radius: 16.0 }
  - { time: 23, type: screen-shake, enabled: false }
```

- `time` 是秒；到该时间点切换屏幕震动；
- `enabled: true` 开始振荡，`enabled: false` 停止并恢复走速；
- `amplitude`：围绕玩家原走速的偏移；`frequency`：每秒振荡次数；
- `radius`：只影响舞蹈中心周围该半径内的玩家；
- 省略 `amplitude/frequency/radius` 时，使用 `config.yml` 的默认值；
- 舞蹈结束、`/dance stop`、插件停用都会恢复原走速。

`config.yml` 只作为默认值和总开关：

```yaml
screen-shake:
  enabled: true       # 总开关；false 时所有事件都不生效
  amplitude: 0.02
  frequency: 8.0
  radius: 16.0
```

- 对玩家调用 `Player#setWalkSpeed`，按
  `base + amplitude * sin(2π * frequency * 秒)` 振荡；
- 这是**实验性效果，不是真正的摄像机震动 API**，同时会改变玩家移动速度；
- 服务器在震动中途崩溃时，玩家走速可能停留在异常值，需要重新登录或手动恢复；
- 如果没有 `screen-shake` 事件，就不会有震动，即使全局 `enabled: true`。

如果 `setWalkSpeed` 在客户端只改变走速、没有屏幕震动，就需要换方案；Paper 没有
直接的 camera-shake API。

## 玩家参与

每个舞可以决定玩家是否参与：

```yaml
players: true
player-emote: "jellydance_wine"
```

- 玩家直接执行 `/dance`，只让**命令发送者本人**穿 RP Engine emote；
- 不会让附近其他玩家一起跳；
- 玩家保留自己的皮肤，不是 ItemDisplay 替身；
- 没有 RPEngine 时只提示，不影响 mob 舞蹈；
- 普通玩家 `/dance stop` 只停自己的 emote；管理员仍可停全部。

玩家动画资源放在 RP Engine content：

```text
plugins/RPEngine/content/jellydance/emotes/wine.yml
```

```yaml
jellydance_wine:
  name: Wine
  length: 38.5
  loop: false
  root:
    rotation:
      - { time: 9,  value: [0, 0, 0] }
      - { time: 23, value: [0, 5040, 0], interpolation: linear }
    scale:
      - { time: 0, value: [1, 1, 1] }
  animators:
    rightArm:
      rotation:
        - { time: 0, value: [0, 0, 0] }
```

修改后 `/rp reload`。玩家第一次进服后 RP Engine 才会烘焙该玩家的 rig；首次可能使用默认人物，
`/rp reload` 后正常。

> 玩家 emote 是独立的 RP Engine 动画，不是直接把 mob 的 ItemDisplay `Transformation`
> 套到玩家骨骼上。动作要接近，需要单独编一份 emote。

## 资源包自动安装

JellyDance 的 JAR 里已经带了：

```text
content/jellydance/
  pack.yml
  sounds/music.yml
  assets/sounds/music/wine.ogg
  emotes/wine.yml
```

插件启用时通过 RP Engine 的 `AddonContent.install(this, engine, "jellydance")`
解包到：

```text
plugins/RPEngine/content/jellydance/
```

- 只有内容真的变化时才调用 `engine.reload()`，同一个 JAR 重启不会重复重建；
- 服务器主人改过的文件会被 `.installed` 哈希保护，更新 JAR 时不会覆盖；
- 模型不打包进 JellyDance；模型仍由 VanillaModelLibrary / RP Engine 原来的托管负责；
- 第一次安装会自动重建资源包，不需要手动拷 content 文件夹。

编辑音乐/玩家动作：

```text
plugins/RPEngine/content/jellydance/assets/sounds/music/wine.ogg
plugins/RPEngine/content/jellydance/emotes/wine.yml
```

改完执行 `/rp reload`。如果 JellyDance 更新时提示某个文件被保留，删掉该文件或
`.installed` 对应行再更新即可。

## 命令

| 命令 | 权限 | 作用 |
|---|---|---|
| `/dance list` | 所有人 | 列出已加载的舞 |
| `/dance start [半径] [舞名]` | `jellydance.use` | 让附近可解析生物按指定舞开始 |
| `/dance startat <player|x y z> [半径] [舞名]` | `jellydance.admin` | 在指定玩家/坐标附近开始 |
| `/dance reload` | `jellydance.admin` | 重读 config.yml 和 dances/*.yml |
| `/dance why [player|x y z] [半径]` | `jellydance.admin` | 打印候选的 strict/display 匹配与外观快照 |
| `/dance stop` | `jellydance.admin` | 停止全部并恢复 |
| `/dance status` | `jellydance.admin` | 当前会话、舞名、剩余 tick |

### reload 范围

`/dance reload` 会重读 `config.yml` 和 `dances/*.yml`：

- **立即生效**：`dance.radius`、`dance.max-participants`、所有编舞文件（只影响新开始的舞蹈）；
- **需要重启**：`hide-mode`、`dance.interpolation-ticks`、`dance.view-range`、
  `dance.freeze-in-place`、`dance.freeze-tolerance`、`dance.debug`；
- 正在进行的舞蹈继续使用开始时的编舞对象，`/dance stop` 后再开始即可用新版本。

## 已知边界

- 只做整体模型动作，不做头、手、脚独立骨骼。
- 支持 catalog 里所有 `supported=true` 的生物；骷髅、蜘蛛、末影人、马、猫、村民等
  没有模型的实体安全跳过。
- 水平移动中的生物不会入选；请让它们站定再触发。
- `freeze-in-place` 会临时写 `NoAI`；正常结束/停服会恢复，服务器在舞蹈中途崩溃时可能残留。
- 展示实体是 `ItemDisplay`，原版实体被隐藏后没有阴影/碰撞可视化；碰撞仍在服务端生效。
- 音乐、客户端双重画面、旁观者/重连/传送行为仍需后续实测。

## 构建

先构建 `../library`（0.2.2），再：

```bash
export JAVA_HOME=/home/cody_test/jdks/jdk-25
export GRADLE_USER_HOME=$PWD/../.gradle-home
"$GRADLE" --no-daemon build
```
