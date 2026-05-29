# DailyRewards

DailyRewards 是一个 Paper 每日在线奖励插件。它按玩家当天在线时长发放分段奖励，也可以记录连续登录、展示在线排行榜，并通过 GUI 或自动发放完成领取流程。

这个插件更适合生存服、小游戏大厅、养老服这类需要“每日活跃奖励”的场景：奖励规则写在 YAML 里，默认使用 SQLite，想接入多服或外部统计时可以切到 MySQL。

## 基本信息

| 项目 | 内容 |
| --- | --- |
| 插件版本 | 2.0.0 |
| 服务端 | Paper 1.20.4+ |
| Java | 17+ |
| 构建工具 | Maven |
| 主命令 | `/dailyrewards` |
| 命令别名 | `/dr`、`/reward`、`/rewards` |
| 默认存储 | SQLite |

软依赖：

| 插件 | 什么时候需要 |
| --- | --- |
| Vault | 使用 `money` 经济奖励时 |
| PlaceholderAPI | 需要在其他插件里读取在线时长、连续登录等数据时 |
| DecentHolograms | 想用 DecentHolograms 作为全息排行榜后端时 |

不安装这些软依赖也可以启动插件，只是对应功能不可用。

## 功能

- 按当天在线秒数配置多个奖励段，例如 30 秒、1 分钟、30 分钟、1 小时。
- 支持自动发放，也支持玩家打开 GUI 手动领取。
- 奖励类型包括物品、经验、经济、指令、音效、消息、药水效果、Title 和烟花。
- 支持连续登录天数记录、奖励倍率加成和里程碑奖励。
- 支持总在线、今日在线、连续登录三类排行榜。
- 支持 Paper 原生 TextDisplay 全息榜，也可以切换到 DecentHolograms。
- 数据可保存到 SQLite 或 MySQL，并带有内存缓存和定时保存。
- GUI、消息文本、奖励组、排行榜、数据库等都可以通过配置文件调整。

## 安装

1. 下载或构建插件 JAR。
2. 将 `DailyRewards-2.0.0.jar` 放入服务器的 `plugins/` 目录。
3. 启动服务器，让插件生成默认配置。
4. 修改 `plugins/DailyRewards/` 下的配置文件。
5. 执行 `/dr reload` 重载配置；如果改了数据库类型、服务端版本或依赖插件，建议重启服务器。

生成的主要文件：

| 文件 | 用途 |
| --- | --- |
| `config.yml` | 主配置，包含数据库、在线计时、领取模式、连续登录、排行榜和全息设置 |
| `rewards.yml` | 奖励组和在线时长分段 |
| `gui.yml` | 奖励 GUI 的标题、槽位、物品和进度条 |
| `messages.yml` | 插件提示文本 |

## 快速使用

玩家打开每日奖励菜单：

```text
/dr open
```

管理员常用命令：

```text
/dr reload
/dr top total_time
/dr hologram create total_time
```

如果只是想先跑起来，默认配置不需要改。默认奖励段写在 `rewards.yml`，默认存储使用 `plugins/DailyRewards/playerdata.db`。

## 奖励配置

奖励写在 `rewards.yml`。`time` 的单位是秒，玩家当天在线时间达到该值后，就可以领取对应奖励。

```yaml
group-priority:
  - vip
  - default

groups:
  default:
    - time: 60
      display-item:
        material: COOKIE
        name: "<yellow>1分钟奖励</yellow>"
        lore:
          - "<gray>在线 <yellow>1分钟</yellow> 后可领取</gray>"
      rewards:
        - type: experience
          amount: 10
        - type: item
          material: APPLE
          amount: 3
        - type: money
          amount: 10.0
        - type: command
          command: "say %player% 获得了每日奖励"
          console: true
```

奖励组通过权限匹配。比如玩家拥有 `dailyrewards.group.vip`，并且 `group-priority` 里有 `vip`，插件就会优先使用 `vip` 组。没有匹配到其他组时使用 `default`。

支持的奖励类型：

| 类型 | 别名 | 常用字段 |
| --- | --- | --- |
| `item` | 无 | `material`、`amount`、`name`、`lore` |
| `experience` | `exp`、`xp` | `amount` |
| `money` | `vault`、`eco` | `amount` |
| `command` | `cmd` | `command`、`console` |
| `sound` | 无 | `sound`、`volume`、`pitch` |
| `message` | `msg` | `message`、`actionbar` |
| `potion_effect` | `potion`、`effect` | `effect`、`duration`、`amplifier` |
| `title` | 无 | `title`、`subtitle`、`fade-in`、`stay`、`fade-out` |
| `firework` | `fw` | `power`、`colors`、`fade-colors`、`type`、`flicker`、`trail` |

文本支持 MiniMessage。部分消息也兼容传统 `&` 颜色代码。

## 主配置

常用配置项在 `config.yml`：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `reset.hour` / `reset.minute` | `0` / `0` | 每日重置时间，使用服务器本地时间 |
| `tracking.interval` | `60` | 在线时间累加间隔，单位为秒 |
| `mode.auto-grant` | `true` | 达到奖励条件后是否自动发放 |
| `mode.gui-enabled` | `true` | 是否允许玩家使用 GUI |
| `mode.auto-reminder` | `true` | 达到可领取条件时是否提醒 |
| `login.enabled` | `true` | 是否启用连续登录 |
| `login.multiplier` | `0.05` | 连续登录带来的奖励加成倍率 |
| `database.type` | `sqlite` | 可选 `sqlite` 或 `mysql` |
| `storage.save-interval` | `1200` | 自动保存间隔，单位为秒 |
| `leaderboard.default-type` | `total_time` | 默认排行榜类型 |
| `hologram.provider` | `text_display` | 可选 `text_display` 或 `decent_holograms` |
| `placeholder.identifier` | `dailyrewards` | PlaceholderAPI 占位符前缀 |

MySQL 示例：

```yaml
database:
  type: mysql
  mysql:
    host: localhost
    port: 3306
    database: dailyrewards
    user: root
    password: ""
```

## 命令

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/dr open` | `dailyrewards.use` | 打开奖励 GUI |
| `/dr info [玩家]` | `dailyrewards.info` / `dailyrewards.info.others` | 查看自己或其他玩家的在线与登录统计 |
| `/dr top [类型]` | `dailyrewards.top` | 查看排行榜，类型为 `total_time`、`daily_time`、`login` |
| `/dr update` | `dailyrewards.update` | 立即检查所有在线玩家的奖励状态，并刷新全息榜 |
| `/dr addtime <玩家> <秒数>` | `dailyrewards.addtime` | 为在线玩家增加今日在线时间 |
| `/dr save` | `dailyrewards.save` | 立即保存缓存数据 |
| `/dr reload` | `dailyrewards.reload` | 重载配置、奖励、GUI、消息和全息设置 |
| `/dr reset <玩家>` | `dailyrewards.reset` | 重置在线玩家的今日在线时间 |
| `/dr hologram create [类型]` | `dailyrewards.hologram` | 在当前位置上方创建全息排行榜 |
| `/dr hologram remove <ID>` | `dailyrewards.hologram` | 删除指定全息排行榜，支持输入 ID 前缀 |
| `/dr hologram list` | `dailyrewards.hologram` | 列出所有全息排行榜 |
| `/dr hologram refresh` | `dailyrewards.hologram` | 手动刷新所有全息排行榜 |
| `/dr help` | 无 | 查看帮助 |

全息命令也可以写成 `/dr holo ...` 或 `/dr hd ...`。

## 权限

| 权限节点 | 默认 | 说明 |
| --- | --- | --- |
| `dailyrewards.use` | true | 允许打开 GUI |
| `dailyrewards.info` | true | 允许查看自己的统计 |
| `dailyrewards.info.others` | op | 允许查看其他玩家统计 |
| `dailyrewards.top` | true | 允许查看排行榜 |
| `dailyrewards.update` | op | 允许手动更新奖励状态 |
| `dailyrewards.addtime` | op | 允许手动添加在线时间 |
| `dailyrewards.save` | op | 允许手动保存数据 |
| `dailyrewards.reload` | op | 允许重载配置 |
| `dailyrewards.reset` | op | 允许重置玩家今日在线时间 |
| `dailyrewards.hologram` | op | 允许管理全息排行榜 |
| `dailyrewards.group.<组名>` | false | 使用指定奖励组，例如 `dailyrewards.group.vip` |

## PlaceholderAPI

安装 PlaceholderAPI 后，插件会自动注册占位符。默认前缀是 `dailyrewards`。

| 占位符 | 输出 |
| --- | --- |
| `%dailyrewards_daily_time%` | 今日在线时间，单位为分钟 |
| `%dailyrewards_daily_time_raw%` | 今日在线时间，单位为秒 |
| `%dailyrewards_total_time%` | 总在线时间，单位为分钟 |
| `%dailyrewards_total_time_raw%` | 总在线时间，单位为秒 |
| `%dailyrewards_login_days%` | 当前连续登录天数 |
| `%dailyrewards_claimed%` | 今日已领取奖励段数量 |
| `%dailyrewards_daily_time_hms%` | 今日在线时间，格式为 `HH:MM:SS` |
| `%dailyrewards_total_time_hms%` | 总在线时间，格式为 `HH:MM:SS` |

如果想把前缀改短：

```yaml
placeholder:
  identifier: dr
```

重载后即可使用 `%dr_daily_time%`、`%dr_total_time%` 等占位符。

## 排行榜与全息榜

排行榜类型：

| 类型 | 含义 |
| --- | --- |
| `total_time` | 总在线时长 |
| `daily_time` | 今日在线时长 |
| `login` | 连续登录天数 |

默认全息后端是 Paper 原生 TextDisplay：

```yaml
hologram:
  provider: text_display
```

它不需要额外插件。如果要使用 DecentHolograms：

```yaml
hologram:
  provider: decent_holograms
```

切换后执行 `/dr reload`，已有全息榜会按新的配置刷新。

## 数据存储

默认使用 SQLite：

```text
plugins/DailyRewards/playerdata.db
```

如果服务器规模较大，或需要多个服务端接入同一份数据，可以在 `config.yml` 中切换到 MySQL。插件会把玩家数据缓存在内存中，并按 `storage.save-interval` 定时保存；服务器关闭时也会尝试保存未写入的数据。

## 构建

在项目根目录执行：

```bash
mvn clean package
```

构建完成后使用：

```text
target/DailyRewards-2.0.0.jar
```

## 项目结构

```text
src/main/java/me/qscbm/plugins/dailyrewards/
  commands/       命令和 Tab 补全
  gui/            奖励 GUI
  listeners/      玩家连接与 GUI 监听
  managers/       配置、奖励、数据、登录、全息等管理逻辑
  placeholders/   PlaceholderAPI 扩展

src/main/resources/
  plugin.yml
  config.yml
  rewards.yml
  gui.yml
  messages.yml
```

## 常见问题

### 经济奖励没有发放

确认服务器安装了 Vault，并且有可用的经济插件。插件没有成功挂钩经济服务时，`money` 奖励会被跳过。

### 玩家背包满了怎么办

物品奖励会先检查背包空间。如果空间不足，本次领取不会完成，玩家清理背包后可以重新打开 GUI 领取。

### PlaceholderAPI 占位符没有结果

确认 PlaceholderAPI 已安装并正常启用。修改 `placeholder.identifier` 后，需要执行 `/dr reload` 或重启服务器。

### 全息榜不显示

如果使用默认 `text_display`，确认服务端是 Paper 1.20.4+。如果使用 `decent_holograms`，确认已安装 DecentHolograms，并且 `hologram.provider` 配置正确。

### 配置改了但没生效

先执行 `/dr reload`。如果改动涉及数据库、依赖插件、服务端版本或插件 JAR 本身，直接重启服务器更稳。
