# 僵尸模式地图部署手册

适用范围：本仓库当前源码（2026-10-05），包含玩家复活点分组与屏障联动功能。构建目标为 Minecraft 1.20.1、Forge 47.4.0，附属模组版本为 0.2.1b。本文同时核对了相邻主工程 `codPattern` 的地图管理、存储及工具交互实现；正式部署时，客户端和服务端应使用包含这些实现的配套版本。

本手册面向地图作者和服务器管理员，覆盖从注册区域、布置对象到编写规则、开局验收及搬运地图的完整流程。文中的“必须”表示源码约束，“建议”表示制作建议。示例价格、波次数量和区域坐标是演示配置，不是平衡性标准。

## 目录

1. [安装条件与数据分工](#1-安装条件与数据分工)
2. [先做一张最小可开局地图](#2-先做一张最小可开局地图)
3. [部署工具操作](#3-部署工具操作)
4. [地图目录与文件职责](#4-地图目录与文件职责)
5. [玩家复活点和僵尸出生点](#5-玩家复活点和僵尸出生点)
6. [屏障几何与分组规则](#6-屏障几何与分组规则)
7. [购买设施字段和交互要求](#7-购买设施字段和交互要求)
8. [波次与波次文本](#8-波次与波次文本)
9. [房间与武器规则](#9-房间与武器规则)
10. [结束传送点与管理命令](#10-结束传送点与管理命令)
11. [开局校验和验收](#11-开局校验和验收)
12. [保存、生效、搬运与迁移](#12-保存生效搬运与迁移)
13. [故障排查](#13-故障排查)
14. [源码索引](#14-源码索引)

## 1. 安装条件与数据分工

### 1.1 环境

| 项目 | 当前源码依据 |
|---|---|
| Java | 17 |
| Minecraft / Forge | 构建目标 1.20.1 / 47.4.0；建议按此版本准备环境 |
| 主模组 | `codpattern`，附属模组声明要求 `0.8.6b` 或以上 |
| 僵尸附属模组 | `codpattern_zombies`，当前版本 `0.2.1b` |
| TACZ | `tacz`，声明要求 `1.1.6` 或以上 |
| 安装侧 | 依赖声明为客户端和服务端双方 |
| 部署权限 | 服务器权限等级至少 2；仅切换创造模式不等于具有部署权限 |

还需满足主模组自身的依赖。枪械规则使用的 TACZ 枪械 ID 必须在实际安装的枪包中存在；复制示例前应核实 `tacz:glock_17`、`tacz:ak47`、`tacz:m4a1` 等是否可用。

### 1.2 三类数据分别放在哪里

| 数据 | 保存位置 | 推荐编辑方式 |
|---|---|---|
| 地形、建筑、装饰方块 | Minecraft 世界区块 | 游戏内建造或地图制作工具 |
| 地图范围、玩家点、僵尸点、屏障几何、设施位置 | 该地图的 `map.json` | 僵尸部署工具；结束传送用地图管理工具 |
| 屏障价格与切组、波次、武器池、房间参数 | 该地图的 `rules/` | 文本编辑器修改对应 JSON |

`map.json` 不是建筑蓝图，不会打包整个场景。发布地图必须同时交付相应世界地形、地图元数据、规则及所需枪包。

## 2. 先做一张最小可开局地图

先跑通一小块场地，再添加区域解锁和购买设施。以下步骤不要求先布置商店、电源或屏障。

1. 在目标维度建好地面，留出玩家和怪物活动空间，确定完整的三维地图边界。
2. 获取工具：`/give @s codpattern:zombies_deploy_tool`。
3. 主手持工具，按 **Ctrl + 右键**打开界面。在“地图注册”页输入地图名，例如 `demo`，填写覆盖场地的 A/B 两角坐标，然后“创建地图”。也可先收起界面，在世界里左键选 A、右键选 B，再回来创建。
4. 进入“部署点编辑”，选择玩家出生点 `INITIAL`。收起界面，在地面上表面左键，创建玩家点；回面板确认 `group=0`、坐标在区域内。建议为计划容纳的每名玩家分别布点，但源码并不强制恰好四个点。
5. 选择僵尸出生点，收起后在另一处左键新增；确认 `group=0`、`weight=1.0`，并留出怪物身体空间和通往玩家的路径。
6. 按“保存”或 **Ctrl + S**。创建地图和保存部署对象是两个动作，新增点后仍需保存。
7. 找到[地图规则目录](#4-地图目录与文件职责)，创建或修改 `rules/waves/wave_001.json`，使用第 8 节的简单僵尸波次；目录不存在时先创建。自动生成的波次包含监守者等多种怪物，不适合直接作为首次验收关卡。
8. 创建或修改 `rules/wave_text/wave_001.json`，写入自己的提示，或使用 `{"wave":1,"messages":[]}`，避免出现默认 `text1`、`text2`。新地图的文本目录可能尚未生成，此时先创建目录。
9. 回部署界面的“校验详情”，选择完整校验档位 `MVP3_FULL_INITIAL`，修复错误并再次保存。对象校验通过后，还要实际开局验证波次和规则。
10. 回到世界后按 **Esc → 游戏大厅 → 僵尸**，在目标地图房间点击“加入”，再“准备”“发起开始投票”，并在投票框点击“接受”。按第 11 节完成实战验收。

最小地图的硬性基础是：至少一个有效玩家组 `0` 点、至少一个有效且正权重的僵尸组 `0` 点、有效的波次及规则。已配置对象仍必须满足完整校验；“某设施不是必需”不表示配置错误的该设施会被忽略。

## 3. 部署工具操作

### 3.1 地图注册

界面分为“地图注册”和“部署点编辑”两页。地图注册页左侧显示已注册僵尸地图，右侧是新建地图表单。

- A/B 两角定义三维范围，垂直范围也要覆盖玩家、怪物和设施。
- 世界取点得到的是所点方块表面外侧的相邻格。例如点击地面上表面，得到地面上方那一格。
- “创建地图”会立即注册并保存地图，成功后自动进入该地图的玩家点编辑。
- 已注册地图的区域显示不等于区域编辑功能。注册页 A/B 表单用于新建地图，不能用它直接修改选中旧地图的边界。
- 名称使用清晰、唯一的短名。存储目录由名称编码生成，不应凭肉眼改目录名来重命名地图。

### 3.2 世界取点与面板快捷键

| 场景 | 操作 | 实际效果 |
|---|---|---|
| 主手持僵尸工具 | Ctrl + 右键 | 打开部署面板；可对空气使用 |
| 注册阶段，面板已收起 | 左键 / 右键 | 记录新地图 A / B 角 |
| 普通单点对象，面板已收起 | 左键 | 在新位置新增一个对象；不会移动当前选中对象 |
| 电源开关 | 左键 | 新增唯一电源；已有选中电源时更新位置 |
| 新屏障 | 先左键，后右键 | 记录起点、终点，完成一面屏障 |
| 已选屏障 | 右键 | 修改其第二个端点；左键会开始新屏障 |
| 玩家点、僵尸点、普通设施、电源 | 普通右键 | 当前实现不执行取点操作 |
| 面板内 | Ctrl + S | 保存当前地图草稿 |
| 面板内，输入框未聚焦 | Ctrl + Z | 撤销 |
| 面板内，输入框未聚焦 | Ctrl + Y / Ctrl + Shift + Z | 重做 |
| 面板收起且主手持工具 | R / Shift + R | 撤销 / 重做 |

当前没有“潜行选点”的要求。部分界面文字仍可能显示“右键设置交互点”，但单点类型的服务端实现已不执行这一行为；交互点请在属性栏填写。

出生点取点记录操作者的水平 `yaw`，不会自动记录低头仰头的 `pitch`。箱体取点时朝向为操作者水平朝向的反方向，使箱体正面朝向操作者。

### 3.3 属性、草稿和预览

选择类型后，从对象列表选中目标，在属性栏修改。`objectId` 和 `dimension` 在当前 GUI 中只读；新对象 ID 由工具生成，世界取点确定维度。

属性输入框失焦、按 Enter 或页面导航前，会把字段提交到当前草稿；仍需“保存”才写入地图。需要移动已有单点对象时，编辑坐标字段，不要反复在世界左键。

“收起”保留草稿并返回世界，适合继续取点；“关闭”或 Esc 结束编辑，存在未保存改动时会提示丢弃。切换地图也可能丢弃未保存编辑。已保存的数据不会因丢弃草稿被撤销。

撤销最多保留 10 次操作。保存不清空撤销历史，但保存后撤销的结果也必须再次保存。草稿位于服务器内存，闲置超过 30 分钟会被清理，不能代替文件保存。

工具显示区域线框、当前类型对象、选中对象和草稿标记，玩家点预览含组号。对象预览要求操作者处于地图维度。箱体模型草稿还受 64 格预览距离、区块加载、视锥和位置占用影响；预览不出现不等于对象已被删除。

界面虽然有“复制”按钮，但复制保留原坐标，而服务端会拒绝坐标冲突，所以当前通常不能通过“先复制、再移动”批量布点。建议在不同位置逐个左键新增。冲突检查跨对象类型，包含出生点、屏障体积及设施位置/交互点；同一设施自身的位置和交互点可以重合。

## 4. 地图目录与文件职责

### 4.1 当前存储路径

所有路径以当前世界目录为起点：

```text
<世界目录>/serverconfig/codpattern/maps/zombies/m-<地图名 UTF-8 十六进制>/
├── map.json
└── rules/
    ├── room.json
    ├── weapon_rules.json
    ├── weapon_wall.json
    ├── mystery_box.json
    ├── backpack.json
    ├── barrier_groups.json
    ├── waves/
    │   ├── wave_001.json
    │   └── wave_002.json
    └── wave_text/
        ├── wave_001.json
        └── wave_002.json
```

例如地图名 `demo` 对应 `m-64656d6f`。单人世界通常在实例的 `saves/<世界名>/`，专用服务器使用实际配置的世界目录；不要把规则放到客户端全局 `config/` 后期待服务器读取。

### 4.2 文件职责与格式版本

| 文件 | `schemaVersion` | 内容 |
|---|---:|---|
| `map.json` | 1 | 地图身份、维度、范围、结束传送、部署对象；优先由工具维护 |
| `room.json` | 1 | 房间时序、怪物默认参数、护甲减伤、出生距离权重 |
| `weapon_rules.json` | 1 | 稀有度伤害、强化等级/价格/倍率 |
| `weapon_wall.json` | 1 | 墙枪刷新间隔、稀有度池、枪械权重、价格 |
| `mystery_box.json` | 1 | 神秘箱价格、刷新间隔、枪池 |
| `backpack.json` | 1 | 初始武器及配件预设、按枪械类别和单枪设置的固定备弹上限 |
| `barrier_groups.json` | **2** | 屏障组共享切组动作及各入口价格/钥匙 |
| `waves/wave_NNN.json` | 不使用上述版本字段 | 一份文件定义一波战斗 |
| `wave_text/wave_NNN.json` | 不使用上述版本字段 | 一份文件定义一波聊天提示 |

`room.json`、`weapon_rules.json`、`weapon_wall.json`、`mystery_box.json` 缺失、JSON 损坏或格式版本错误时，读取器会把该文件重建成默认值。修改前保留副本，修改后检查文件与日志，不能只凭“服务器没报崩溃”判断设置有效。

`backpack.json` 缺失时生成默认 Glock 和固定备弹表；已有文件的 JSON 格式、版本、字段类型或数值无效时保留原文并阻止开局，不覆盖为默认值。枪械、物品、配件及类别标签按字符串读取，不在配置层检查资源存在性或标签格式，NBT 与配件 SNBT 也只在实际发枪时解析。

`barrier_groups.json` 的策略不同：不存在时创建空 v2 模板；已存在但无效时保留原文件、报告错误，不会悄悄改成免费屏障。只要地图配置了屏障，就必须补全它所绑定的组和入口。

当前统一读取器不再以旧 `config.json` 为规则入口，也不读取 `waves.json` 或 `wave_texts.json` 作为波次合集。波次文本目录是 `wave_text`，不是旧 `wavetext`。

## 5. 玩家复活点和僵尸出生点

### 5.1 三种组号不能混为一谈

| 组号类型 | 设置处 | 初始状态 | 由谁改变 |
|---|---|---|---|
| 玩家复活组 | 玩家 `INITIAL` 点的 `group` | 每局仅组 `0` 启用 | 屏障规则 `playerSpawnGroupChanges` |
| 僵尸出生组 | 僵尸点的 `group` | 每局仅组 `0` 启用 | 屏障规则 `spawnGroupChanges` |
| 屏障联动组 | 屏障的 `group` | 本局尚未购买开启 | 购买该组任一有效入口 |

玩家组 `2`、僵尸组 `2`、屏障组 `2` 属于三套独立含义。同号不会自动关联，关联必须写在屏障规则里。建议地图设计表分别记录三套组号。

### 5.2 玩家点 `INITIAL`

| 字段 | 新建表单默认 | 含义 |
|---|---|---|
| `group` | `0` | 非负整数；至少保留一个有效组 `0` 点 |
| `posX/Y/Z` | `0/64/0` | 整数方块坐标；建议世界点选后核对 |
| `yaw` / `pitch` | `0.0` / `0.0` | 水平/俯仰朝向；必须是有限数值 |
| `dimension` | 表单初始为主世界 | 实际取点使用当前维度，必须匹配地图 |

玩家点总数没有四个的上限；房间当前最多容纳四名幸存者。开局从组 `0` 中分配玩家位置，波间复活从当前启用组中分配。筛选后按配置顺序分配，点数不足时循环使用，不是选择离玩家最近的点。

玩家点虽然保存 `pitch` 字段，但当前实际玩家传送使用配置的 `yaw`，并把俯仰角设为 `0`；不要依赖玩家出生时朝上或朝下的设计。传送还会检查脚部、头部和身体碰撞空间；坐标校验通过但位置被方块堵住，仍可能传送失败。

屏障改变玩家组后，会更新后续复活的候选点和相关缓存，但**不会把仍然存活的玩家立刻传送到新区域**。死亡玩家通常先观战，在下一波准备阶段才按当前启用组复活。测试这一流程需至少两名玩家，并保证仍有人存活；单人死亡不能用来验证波间复活。

旧玩家点没写 `group` 时按 `0` 兼容。新区域中的玩家点应改为其他组，避免开局就把玩家分配到尚未解锁的区域。

### 5.3 僵尸点

在玩家点的坐标、维度、朝向字段之外，僵尸点还有自动生成的 `objectId`、非负整数 `group` 和 `weight`。

- `weight` 默认 `1.0`，必须有限且非负。`0` 合法，但不会参与正常抽样。
- 至少一个组 `0` 点必须具有正权重。
- 权重影响出生点选择，不表示这一点每波产生多少只怪物；怪物数量由波次文件中的 `count` 决定。
- 运行时还受距离权重、寻路失败降权等影响，基础权重 `2` 不保证最终出生概率始终恰好翻倍。
- 怪物出生位置使用方块中心 `(x+0.5, y, z+0.5)`，并应用该点朝向。

地面怪生成时会检查身体碰撞。地图校验通过不保证所有怪物都能在该处生成、站稳或到达玩家；空中点、液体及危险地面等部分情况只记诊断。建议按最大体型留空间，实际测试监守者等大体型怪物和小型/飞行怪物。

## 6. 屏障几何与分组规则

### 6.1 先布置屏障几何

选择屏障类型，左键起点、右键终点，再到面板填写：

| 字段 | 新建表单默认 | 要求 |
|---|---|---|
| `name` | 空 | 可读名称，建议写区域入口名 |
| `group` | `2` | 正整数，绑定屏障联动组 |
| `entryId` | `1` | 正整数，绑定该组某个购买入口 |
| `blocksPlayersOnly` | `true` | 当前必须为 `true` |
| `areaFromX/Y/Z`、`areaToX/Y/Z` | `(0,64,0)`、`(0,66,0)` | 直墙两端点，含端点计算尺寸 |
| `interactionX/Y/Z` | `(0,65,0)` | 玩家购买入口的位置；世界新建初始取第一个端点 |

几何限制：两个端点必须同 X 或同 Z，构成一格厚直墙；长度最多 32 格，高度 2～8 格；单面最多 256 格，全图屏障合计最多 2048 格。不同屏障不得重叠，端点与交互位置必须在地图范围内、处于地图维度。

屏障只阻挡玩家，不能作为阻挡僵尸的实体墙使用。需要挡怪的建筑应由地图地形和可通行路线设计实现。

价格、钥匙、两套出生组动作都不在几何属性中编辑。部署工具会读取规则供查看，但权威数据必须写入 `rules/barrier_groups.json`。

### 6.2 最小免费屏障规则

如果只需打开屏障组 `10`，不改变任何出生组，文件可以写成：

```json
{
  "schemaVersion": 2,
  "groups": {
    "10": {
      "entries": {
        "1": { "cost": 0 }
      }
    }
  }
}
```

地图屏障必须设 `group=10`、`entryId=1`。省略两套动作表示维持原启用状态。旧屏障如果缺 `entryId`，会读成 `0`，意为未选入口；不会自动选择入口 `1`。

### 6.3 完整示例：A 区 → B 区 → C 区

先在三个区域布置下列对象，保证地形上只有依次开门才能进入后区：

| 区域/入口 | 玩家点组 | 僵尸点组 | 屏障组与入口 |
|---|---:|---:|---|
| A 区，开局场地 | 0 | 0 | — |
| A → B 的门 | — | — | `group=10, entryId=1` |
| B 区 | 2 | 2 | — |
| B → C 的门 | — | — | `group=20, entryId=1` |
| C 区 | 3 | 3 | — |

以下是此三区域地图的完整 `barrier_groups.json` 示例：

```json
{
  "schemaVersion": 2,
  "groups": {
    "10": {
      "spawnGroupChanges": { "enable": [2], "disable": [0] },
      "playerSpawnGroupChanges": { "enable": [2], "disable": [0] },
      "entries": {
        "1": { "cost": 1000 },
        "2": {
          "cost": 0,
          "requiredItem": "minecraft:tripwire_hook{BarrierKey:\"group10\"}"
        }
      }
    },
    "20": {
      "spawnGroupChanges": { "enable": [3], "disable": [2] },
      "playerSpawnGroupChanges": { "enable": [3], "disable": [2] },
      "entries": {
        "1": { "cost": 1500 }
      }
    }
  }
}
```

预期过程：开局双方只使用组 `0`；打开屏障组 `10` 后，僵尸后续生成和玩家后续波间复活改用组 `2`；打开组 `20` 后改用组 `3`。新一局重新回到组 `0`。

这里组 `10` 还提供可选入口 `2`：另放一面同组屏障、设 `entryId=2` 即可用钥匙购买。任一入口成功，都打开该组所有屏障，共享同一套出生组动作。若要“积分和钥匙同时满足”，在同一个入口中把 `cost` 设为正数并保留 `requiredItem`。

测试钥匙可用：

```mcfunction
/give @s minecraft:tripwire_hook{BarrierKey:"group10"} 1
```

钥匙只检查持有，不消耗。匹配要求物品及完整 NBT 一致；额外改名或添加其他 NBT 可能使其不再匹配，数量不参与相同物品判定。

本仓库另有[屏障规则示例文件](examples/barrier_groups.example.json)，包含积分、钥匙、积分加钥匙三种入口。该文件是另一份独立示例，不应与本节三区域配置直接叠加。

### 6.4 切组必须理解的边界

- `enable`、`disable` 中写 JSON 数字，例如 `[2]`，不能写 `["2"]`。出生组可为 `0`，屏障组键和入口键则必须是正整数字符串。
- 同一套动作不能同时启用和关闭同一组，引用的组必须在对应类型的点中存在。文件里尚未被屏障使用的规则也会检查引用和道具合法性。
- `cost` 必须放在 `entries.<入口号>` 中并显式写为非负整数；未知字段和错误层级会被拒绝。
- 玩家组动作如果会关闭所有可用玩家点，购买失败，不扣分、不开门。僵尸组不要切到没有正权重可用点的状态，否则可能剩余生成预算无法执行而卡波。
- 同一屏障组只购买一次，不重复扣费或重放动作。不同屏障组的动作按实际购买顺序生效，不存在“关闭永远优先”的规则。
- 示例没有逻辑上的“先买 10 才能买 20”前置条件。若允许玩家绕路先买 20，再买 10，最终可能同时启用多个组；应通过通路设计或修改动作显式处理顺序。
- 想保留旧区继续刷怪/复活，只启用新区、不要把旧区写入 `disable`。两套动作可以不同，例如保留 A 区刷怪，同时只在 B 区复活。

## 7. 购买设施字段和交互要求

### 7.1 通用字段

除电源外，普通购买箱体均具有 `objectId`、`dimension`、`posX/Y/Z`、`facing`、`interactionX/Y/Z`。`facing` 新建默认 `north`，只允许 `north/east/south/west`。

**标准箱体保持交互坐标等于位置坐标。** 实际箱体方块生成在 `pos`，点击匹配的是 `interactionPos`，且交互位置处必须存在正确的箱体。随意分开会导致“箱子看得到但买不了”。世界左键新增会自动令两者一致。

保存会同步实际设施方块。目标格应为空气、可替换方块或原有兼容设施，不能直接覆盖实心建筑；移动/删除设施会清理对应旧设施方块。单独在创造栏放一个箱体方块不等于完成地图对象注册。

购买通常只在波间准备 `INTERMISSION` 和战斗 `WAVE_ACTIVE` 阶段开放。用主手点击，目标需在交互距离内（点击判定上限 6 格）。普通箱体不参与“朝空气寻找最近交互对象”的回退。

下表是**部署界面新建表单默认值**，不是直接手写 `map.json` 缺字段时的全部兼容默认值。

### 7.2 各设施配置

| 类型 | 特有部署字段 | 默认与说明 |
|---|---|---|
| 墙枪 `weapon_wall` | 无额外销售字段 | 枪池、价格和刷新从 `weapon_wall.json` 读取 |
| 弹药箱 `ammo_box` | `pricesByWeaponLevel` | `1=0,2=250,3=500`，逗号或分号分隔 `等级=价格` |
| 护甲站 `armor_station` | `armorLevel`、`buyCost` | 等级默认 1，仅 1/2/3；价格默认 500 |
| 电源 `power_switch` | `block`、`cost` | 固定方块 `codpattern:zombies_power_switch`，价格默认 1000；还有位置、朝向、ID、维度，无独立交互坐标 |
| 汽水机 `soda_machine` | `buffId`、`cost`、`requiresPower` | 默认 `double_health`、1500、`true` |
| 升级机/终极机器 `ultimate_machine` | `requiresPower` | 默认 `true`；升级规则在 `weapon_rules.json` |
| 神秘箱/抽奖箱 `mystery_box` | 无额外销售字段 | 价格与枪池在 `mystery_box.json` |

价格必须非负。所有非空对象 ID 必须全图唯一；对象位置和维度必须通过地图校验。标准箱体交互坐标保持与位置一致：当前完整校验并未逐一覆盖普通设施独立 `interactionPos` 的范围，不能用“校验通过”代替这一检查。

**弹药箱：**按当前枪的 `weaponLevel` 精确查价，缺少某等级就不能补弹，不会自动套用最高档。空表不能用于实际补弹。主手必须是房间认可的 TACZ 武器；补充的是备用弹药，已满会拒绝购买。

**护甲站：**不能重复购买同级或更低护甲；减伤比例由 `room.json` 的 `armor` 管理。地图对象中旧的伤害倍率不是当前配置入口。

**电源：**全图只能部署一个，状态属于整个房间，每局重置为关闭，已经开电不会再次收费。若任意汽水机或升级机要求电力，必须部署有效电源；没有耗电设施时电源不是开局必需。

**汽水机：**合法 `buffId` 为 `double_health`、`speed_boost`、`reactive_explosion`、`double_ammo`、`score_multiplier`、`headshot_damage`。需要电力的机器要先开电；已有相同增益不能重复购买。死亡/复活流程会清除增益，不能把它当作永久地图进度。

**升级机：**主手需持房间认可的 TACZ 枪，等级上限、每级费用和伤害倍率都从 `weapon_rules.json` 读取，不在对象几何中写 `maxUpgradeLevel/levels`。

**神秘箱：**付款后滚动 100 tick，结果领取期限最多 200 tick，领取成功或超时后冷却 20 tick；仅付款玩家可领取。正常 20 TPS 时约为 5 秒、最多 10 秒、1 秒。不要用地图对象旧 `cost/weaponPool` 控制现行抽取。

### 7.3 窗口功能现状

源码保留了 `windows` 持久化结构，但当前没有窗口部署类型、修窗交互服务或修窗奖励流程。不要把窗口当作已支持的“可修复挡怪窗”交付。建筑上的普通窗口可以制作，但其玩法需另有实现。

## 8. 波次与波次文本

### 8.1 一波一个文件

战斗文件放在 `rules/waves/` 的第一层，推荐连续命名 `wave_001.json`、`wave_002.json` 等。实现接受 `wave_<数字><后缀>.json`，但同一波应只保留一份，不要把备份也命名成可匹配的文件。

下例可直接作为首次测试的 `wave_001.json`：

```json
{
  "wave": 1,
  "bossIntro": false,
  "description": "第一波部署验收：5 只普通僵尸",
  "healthMultiplier": 1.0,
  "damageMultiplier": 1.0,
  "speedMultiplier": 1.0,
  "maxAlive": 3,
  "fastestSpawnIntervalTicks": 20,
  "slowestSpawnIntervalTicks": 40,
  "mobs": [
    {
      "entity": "minecraft:zombie",
      "count": 5,
      "healthMultiplier": 1.0,
      "damageMultiplier": 1.0,
      "speedMultiplier": 1.0,
      "killPoints": 10,
      "assistPoints": 3
    }
  ]
}
```

| 字段 | 含义与约束 |
|---|---|
| `wave` | 正整数；填写时必须与文件名波号一致，战斗文件省略时可取文件名 |
| `bossIntro` | 可选布尔值，仅选择波次开场音效；不会自动生成 Boss，不接受字符串 `"true"` |
| `description` | 可选说明元数据，不影响运行 |
| 三种 `*Multiplier` | 生命、攻击伤害属性、移速倍率，正有限数；可配置于整波及单个怪物条目 |
| `maxAlive` | 同时存活的波次怪物上限，正整数，不是该波总数量 |
| `fastestSpawnIntervalTicks` | 最快生成间隔，正整数 |
| `slowestSpawnIntervalTicks` | 最慢生成间隔，正整数，不能小于最快值 |
| `mobs` | 必须存在；数组可为空，空数组表示空波次 |
| `mobs[].entity` | 支持列表中的实体 ID，推荐写完整命名空间 |
| `mobs[].count` | 该波计划生成数量，非负整数 |
| `killPoints` / `assistPoints` | 单个怪物条目的击杀/助攻积分，非负整数 |

正常 20 TPS 时 20 tick 约一秒；生成间隔在最快到最慢之间随机选取，包含边界。省略波次参数会使用房间中的怪物默认值。旧 `spawnIntervalTicks` 固定间隔仍有兼容读取，新配置用上下限表达。

最终属性倍率为 **整波倍率 × 怪物条目倍率**，条目不会覆盖整波设置。整波未配置时取 `room.json.mobDefaults`，怪物条目未配置倍率时取 `1.0`；例如整波生命倍率 `2.0`、僵尸条目 `1.5`，该僵尸的生命属性按基础值的 3 倍计算。`damageMultiplier` 调整的是 `ATTACK_DAMAGE` 属性，苦力怕爆炸等特殊伤害不能据此假定也会同比变化。

当前支持的实体只有：

```text
minecraft:zombie          minecraft:husk
minecraft:wither_skeleton minecraft:creeper
minecraft:wolf            minecraft:silverfish
minecraft:spider          minecraft:vindicator
minecraft:vex             minecraft:warden
```

安装了其他生物模组不表示这些实体会自动进入支持列表。同一波的正数量怪物条目不要重复同一实体 ID。怪物条目或波次文件错误会进入开局预检；不要期待只跳过有问题的战斗文件后继续开局。

建议波号从 1 连续到 N，并在最后一波后验证结算。缺号并不是“等待补文件”的机制。没有匹配波次文件时，系统会创建含多种怪物的演示第一波，包含监守者；部署作者应主动替换。

### 8.2 波次聊天文本

文件放在 `rules/wave_text/`。例如 `wave_001.json`：

```json
{
  "wave": 1,
  "messages": [
    { "delayTicks": 0, "text": "第一波即将开始，请守住入口。" },
    { "delayTicks": 40, "text": "击杀怪物获得积分，可用于购买装备和开门。" },
    { "delayTicks": 60, "text": "倒下后请等待队友完成这一波。" }
  ]
}
```

时间从**进入该波准备阶段**开始，不是从第一只怪物生成开始。延时按上一条消息累加：上例约在第 0、2、5 秒发出，而不是第 0、2、3 秒。计时可跨越准备和战斗阶段。

文本文件中的 `wave` 必须是与文件名一致的正整数，`messages` 必须为数组，条目要求非负整数 `delayTicks` 和非空字符串 `text`。非法条目会被跳过并记日志；文本错误本身不阻止战斗开局。同波多个文本文件按文件名字典序保留第一份。

如果不需要提示，保留以下有效文件即可；直接清空整个目录可能再次生成默认示例：

```json
{ "wave": 1, "messages": [] }
```

## 9. 房间与武器规则

优先在自动生成的文件上修改已知字段，不要把旧综合配置整个覆盖进新拆分文件。

### 9.1 `room.json`

下面保留主要默认值，明确写出怪物生成间隔：

```json
{
  "schemaVersion": 1,
  "room": {
    "startVoteTimeoutSeconds": 15,
    "startVoteRequiredPercent": 60,
    "intermissionSeconds": 5,
    "failDelaySeconds": 8,
    "offlineGraceSeconds": 120,
    "deadPlayerPolicy": "spectate_until_wave_intermission"
  },
  "mobDefaults": {
    "healthMultiplier": 1.0,
    "damageMultiplier": 1.0,
    "speedMultiplier": 1.0,
    "maxAlive": 8,
    "fastestSpawnIntervalTicks": 20,
    "slowestSpawnIntervalTicks": 50,
    "killPoints": 10,
    "assistPoints": 3
  },
  "armor": {
    "level1DamageReduction": 0.25,
    "level2DamageReduction": 0.5,
    "level3DamageReduction": 0.75
  },
  "spawnPointWeighting": {
    "enabled": true,
    "tooCloseDistance": 8.0,
    "idealMinDistance": 24.0,
    "idealMaxDistance": 56.0,
    "farDistance": 112.0,
    "minMultiplier": 0.65,
    "idealMultiplier": 1.15,
    "farMultiplier": 0.85,
    "maxMultiplier": 1.2
  }
}
```

`intermissionSeconds` 是波间准备时长，`offlineGraceSeconds` 用于掉线宽限。`deadPlayerPolicy` 当前使用上述固定策略，不应自行编造即时复活等字符串。

护甲字段是减伤比例：`0.25` 表示减伤 25%，实际承伤为原来的 75%。出生距离参数是软权重调节，不是“24 格内禁止刷怪”等硬性边界。调小场地时要连同这些参数和刷怪点分布一起实测。

### 9.2 `weapon_rules.json`

本文件仅管理稀有度与强化。初始物品与弹药使用第 9.5 节的 `backpack.json`，此处示例为默认稀有度及两级强化：

```json
{
  "schemaVersion": 1,
  "rarities": [
    { "id": "common", "damageMultiplier": 1.0 },
    { "id": "rare", "damageMultiplier": 1.25 },
    { "id": "epic", "damageMultiplier": 1.6 },
    { "id": "legendary", "damageMultiplier": 2.0 }
  ],
  "upgrades": {
    "maxUpgradeLevel": 2,
    "levels": {
      "1": { "price": 2500, "damageMultiplier": 2.0 },
      "2": { "price": 5000, "damageMultiplier": 3.0 }
    }
  }
}
```

稀有度伤害在本文件统一定义；墙枪和神秘箱规则中的旧伤害字段不再负责实际倍率。升级等级键表示强化级别，应覆盖允许的每一级；弹药箱的价格键使用运行时武器等级，调整升级设计后要实测各等级补弹。

### 9.3 `weapon_wall.json`

以下是固定出售普通 AK 的简单验收配置；只有一个有效枪池时，墙枪随机结果也只有这一种：

```json
{
  "schemaVersion": 1,
  "refreshIntervalWaves": 5,
  "rarityPools": [
    {
      "rarityId": "common",
      "initialWeight": 100.0,
      "weightDeltaPerRefresh": 0.0,
      "minWeight": 0.0,
      "maxWeight": 100.0,
      "price": 500,
      "guns": [
        { "gunId": "tacz:ak47", "weight": 1.0 }
      ]
    }
  ]
}
```

全图墙枪使用该地图的统一规则，各墙枪对象生成自己的报价。刷新间隔为 5 时，对应第 1、6、11……波的准备阶段刷新。

第 `wave` 波的刷新次数为 `floor((wave-1)/refreshIntervalWaves)`；稀有度权重由 `initialWeight + 次数 × weightDeltaPerRefresh` 得出，并限制在 `minWeight` 与 `maxWeight` 之间。先选稀有度池，再按其中枪械权重选择。空枪池不能提供有效报价，枪包缺失也会导致配置或购买问题。

### 9.4 `mystery_box.json`

神秘箱价格在顶层。注意其稀有度字段叫 **`id`**，墙枪对应字段则叫 **`rarityId`**：

```json
{
  "schemaVersion": 1,
  "cost": 950,
  "refreshIntervalWaves": 5,
  "rarityPools": [
    {
      "id": "common",
      "initialWeight": 70.0,
      "weightDeltaPerRefresh": -5.0,
      "minWeight": 10.0,
      "maxWeight": 100.0,
      "guns": [
        { "gunId": "tacz:glock_17", "weight": 1.0 }
      ]
    },
    {
      "id": "rare",
      "initialWeight": 30.0,
      "weightDeltaPerRefresh": 5.0,
      "minWeight": 0.0,
      "maxWeight": 100.0,
      "guns": [
        { "gunId": "tacz:ak47", "weight": 1.0 }
      ]
    }
  ]
}
```

池中稀有度 ID 应在 `weapon_rules.json` 中有对应定义。不要把枪械物品 ID `tacz:modern_kinetic_gun` 填进 `gunId`。

### 9.5 `backpack.json`

本文件是本地图初始武器与备弹的唯一配置入口。默认模板如下：

```json
{
  "schemaVersion": 1,
  "starterWeapon": {
    "item": "tacz:modern_kinetic_gun",
    "count": 1,
    "nbt": "{GunId:\"tacz:glock_17\",GunCurrentAmmoCount:17,GunFireMode:\"SEMI\",HasBulletInBarrel:1}",
    "attachmentPreset": ""
  },
  "ammunition": {
    "defaultMaxReserveAmmo": 120,
    "maxReserveAmmoByType": {
      "pistol": 180,
      "rifle": 360,
      "smg": 420,
      "mg": 560,
      "shotgun": 180,
      "sniper": 120,
      "rpg": 36
    },
    "maxReserveAmmoByGunId": {}
  }
}
```

初始物品的 `item` 是物品注册 ID，NBT 中的 `GunId` 及单枪覆盖表的键是 TaCZ 枪械定义 ID。`attachmentPreset` 可省略，默认空字符串。

开局枪、墙枪和神秘箱统一按 **单枪 ID → 枪械类别 → 默认值** 确定备弹上限。单枪标签精确匹配；类别比较时去除首尾空格并转小写，归一化后重名时后项覆盖前项。`rpg` 对应发射器。两个覆盖表可以省略或使用 `{}`，不会自动补回默认类别。例如添加 `"tacz:ak47": 480` 后，AK 的备弹上限为 480，其他步枪仍用类别值 360。

上限按每把武器分别保存，不包含弹匣和膛内子弹。发枪时填满备弹，扩容配件只影响弹匣容量；稀有度和强化不改变备弹上限。补弹补到该武器保存的上限，复活与掉线恢复保留已有状态。

**校验只涉及基本结构与公开数值字段。** `schemaVersion` 为整数 `1`；`count` 为 `1` 至 `2147483647` 的整数；所有备弹值为 `0` 至 `2147483647` 的整数。`0` 表示没有备弹。负数、小数字面值、数字字符串、显式 `null`、越界数值及错误字段类型均无效，加载时报明字段并保留原文件。

物品、枪械、配件、类别标签及 NBT/SNBT 字符串不会在加载或迁移预检时检查格式与资源存在性。未知类别使用默认备弹；未命中的单枪条目不报错。字符串按原文保留，配置层不会替换成 Glock。标签实际无法创建物品时，使用原有发枪失败和回滚流程；“配置能加载”不代表相关枪包一定可用。

配置在地图初始化和每次开局时加载；磁盘修改从下一局生效，当前对局已有武器不热更新。

### 9.6 从旧备弹配置升级

已有地图没有 `backpack.json` 时会生成上面的完整默认模板。**旧初始枪不会自动迁移**，管理员需在新文件重新设置。旧 `weapon_rules.json` 中的初始武器及弹匣倍数字段彻底失效，不参与校验、计算或回退，稀有度和强化仍照常读取。

`weapon_filter.json` 和历史 `zombies_weapon_filter.json` 已停用，不读取、不生成、不修复、不自动删除；旧黑名单和 `weaponTabs` 不再有效。即使旧过滤文件损坏，也不会阻止正常加载或迁移。新文件无效时需修复新文件，不会转而读取旧配置。

存储迁移预检只读：允许源地图缺少新文件；已有新文件按同样的结构和数值规则检查，不检查标签及 SNBT，也不在预检时生成模板。

## 10. 结束传送点与管理命令

### 10.1 单张地图的结束传送点

当前所有僵尸地图校验档位都没有把结束传送点设为开局硬要求，但正式地图建议配置明确的结算返回位置。

1. 获取主模组地图管理工具：`/give @s codpattern:map_management_tool`。
2. 普通右键打开管理界面，选中目标地图，进入“结束传送”。
3. 在大厅安全位置使用“使用当前位置”，或使用已设好的全局位置填表。
4. 核对坐标后“保存”；活动地图不能在此保存结束传送设置。

该界面权限要求为等级 2。直接修改 XYZ 会保留已加载的维度和水平朝向，pitch 规范为 0。僵尸部署面板本身没有 endtp 对象类型。

地图管理的“全局设置”提供未来新建地图的默认结束位置，不会让已有地图自动跟随。需要修改旧地图时仍应单图保存。

### 10.2 已实现的管理命令

下列 `demo` 替换为实际地图名，带空格的名称保留双引号。房间 ID 的编码形式为 `zombies|地图名`。

| 命令 | 权限等级 | 用途 |
|---|---:|---|
| `/cdp screen` | 玩家可执行 | 打开背包界面；不是游戏大厅入口 |
| `/cdp map list` | 2 | 查看地图类型 |
| `/cdp map list zombies` | 2 | 查看僵尸地图列表 |
| `/cdp map endtp show "demo"` | 3 | 查询指定名称地图的结束传送点 |
| `/cdp mode debug room` | 2 | 查看自己当前房间 |
| `/cdp mode debug state "zombies|demo"` | 2 | 查看房间状态 |
| `/cdp mode debug entities "zombies|demo"` | 2 | 查看房间实体信息 |
| `/cdp mode debug areas zombies "demo"` | 2 | 查看地图区域诊断 |
| `/roomforceend zombies "demo"` | 2 | 强制结束该地图本轮对局并恢复玩家，保留房间成员 |
| `/cdp map migrate check` | 4 | 检查旧地图存储迁移状态 |

特别注意：`/cdp map endtp set` 是等级 3 的**批量命令**，把全部支持此功能的已有地图赋为命令执行位置和朝向。它没有 `set <地图名>` 子命令，也不等于设置未来新图默认值。单图调整使用上面的地图管理界面。

## 11. 开局校验和验收

### 11.1 校验档位

| 档位 | 适用阶段 |
|---|---|
| `MVP1_MINIMAL` | 布置基础玩家点、僵尸点时快速检查 |
| `MVP2_PURCHASES` | 继续检查购买设施参数和条件 |
| `MVP3_FULL_INITIAL` | 完整位置、维度、地图范围及屏障几何等检查；开局默认使用此档位 |

“校验详情”可切换档位并重新校验，点击问题会定位到对象类型和对应对象，不会传送操作者。

**保存成功不等于地图可开局。** 系统允许保存校验未通过的草稿并提示未就绪；字段无法解析、坐标冲突或实体方块同步失败则可能直接拒绝操作。开局还会检查规则文件、屏障绑定、枪械配置和波次，至少需一份有效波次。

### 11.2 加入房间和启动一局

1. 保存后回到世界，按 **Esc → 游戏大厅 → 僵尸**。如果正在背包界面，先关闭背包，再打开暂停菜单。
2. 找到目标地图对应的房间，点击“加入”。已注册地图就是房间，无须另建房间。只能加入等待中、未满员且没有恢复阻塞的房间。
3. 每名成员点击“准备”。僵尸模式只有幸存者队伍，无须选择其他模式的阵营。
4. 全部在线成员准备后，任意成员点击“发起开始投票”。
5. 在投票弹窗点击“接受”。**发起者不会自动算同意票，也需要响应。**
6. 投票通过且开局预检成功后，进入固定 **20 秒开局倒计时**，之后开始波次流程。

房间允许 1～4 人。默认投票期限 15 秒、同意门槛 60% 向上取整，因此 1/2/3/4 人分别需要 1/2/2/3 票。期限和比例可在 `room.json` 调整；发起投票时确定成员快照和门槛，快照成员离开会使该次投票失败。

当前没有用于加入僵尸房间、准备或发起投票的命令替代。`/cdp screen` 仅打开背包，不通向游戏大厅。测试完可在地图管理界面强制结束，或用 `/roomforceend zombies "地图名"`；僵尸模式不支持结束投票。

### 11.3 最小实战验收

首次测试可单人进行基础开局、刷怪和结算；测试死亡后的波间复活至少安排两名玩家。

1. **加入与开局：**加入目标僵尸房间，完成准备/开始流程；确认被传送到玩家组 `0`，拿到可用初始装备，第一波提示正常。
2. **刷怪与结束：**确认怪物从僵尸组 `0` 生成、能够接近玩家、击杀有积分；杀完后能进入下一波或最终结算。建议至少配置两波，以验证波间阶段。
3. **购买：**逐个测试墙枪、补弹、护甲、电源、汽水、升级、神秘箱，验证价格、缺钱拒绝、电力条件及领取权限。
4. **屏障：**准备足够积分或符合完整 NBT 的钥匙，购买一个入口；确认同组全部屏障开启，同组第二入口不再次收费。
5. **刷怪切组：**开门前后观察新生成怪物的位置；已经存活的旧区怪物不会因关闭出生组而自动清除。
6. **玩家复活切组：**开门后让一名玩家死亡，其他玩家继续完成本波；进入下一波准备时，确认死亡玩家在新启用玩家组复活。存活玩家应保持原位置。
7. **分支顺序：**若地图有多条开门路线，分别测试购买顺序，确认不会留下不希望启用的出生组，也不会关闭所有可用刷怪点。
8. **重开：**完成或结束一局，再开新局，确认屏障、电源及出生组状态按新局重置，起点回到组 `0`。
9. **重启服务器：**确认地图、点位、设施朝向和规则能从磁盘恢复。这个步骤能发现只改了草稿、改错世界目录等问题。

### 11.4 上线前检查表

- [ ] 完整范围覆盖场景、出生点、屏障端点及交互点，维度一致。
- [ ] 玩家组 `0` 至少一个点，僵尸组 `0` 至少一个正权重点。
- [ ] 玩家点与僵尸点各组在设计表中有明确用途，后区点未误留在组 `0`。
- [ ] 每个屏障 `group/entryId` 都能在 v2 规则中解析，所有引用组存在。
- [ ] 任何可达开门顺序均有可用刷怪点；玩家复活点留在已开放区域。
- [ ] 箱体目标格可放置，位置等于交互坐标，朝向正确，已保存实际方块。
- [ ] 需要电力的设施有对应电源；强化后的各级枪都有预期补弹价格。
- [ ] 波次连续、无重复文件、无演示监守者或占位文本意外残留。
- [ ] TACZ 枪包、初始武器和枪池 ID 在客户端/服务端一致可用。
- [ ] 完整校验、开局预检、双人复活测试及重启恢复均完成。

## 12. 保存、生效、搬运与迁移

### 12.1 修改何时生效

| 修改 | 生效方式 |
|---|---|
| 面板字段输入 | 先更新编辑草稿；保存后写入地图 |
| 部署设施保存 | 会立即同步世界中的实际设施方块 |
| 磁盘规则、波次修改 | 每次开局重新读取；编辑后结束当前局再开新局验证 |
| 对局中的对象和屏障逻辑 | 使用开局冻结快照；不能期待中途改磁盘立即改变当前逻辑 |
| 手动编辑 `map.json` | 应停服后操作并重启读取，避免运行中内存状态覆盖磁盘 |

当前部署 GUI 没有全面禁止运行中编辑。因此请在没有正在进行的对局时施工：即使当局逻辑使用冻结快照，保存仍可能立刻移除、移动或新建实际箱体，不能把它理解成完全不影响当局世界。

手写 `map.json` 时，不要照抄界面新建默认值假设：部分 JSON 缺位置默认原点、缺价格默认 0、缺弹药价表默认空表。优先让工具生成正确结构，再备份后局部调整。

### 12.2 备份和搬运

完整备份至少包含世界地形、该地图整个目录（`map.json` 和 `rules/`）、配套模组及枪包信息。只复制 `rules/` 不会创建地图或建筑，只复制 `map.json` 也不会复制场景。

搬到另一台服务器时，先准备配套环境，停服复制世界与地图目录，再启动检查地图列表、维度、区域和设施。如果改了维度 ID 或移动了建筑坐标，原点位不会自动跟着改变，必须重新部署并验收。

不要仅重命名编码目录来改地图名；名称、目录和保存数据的身份需要一致。跨地图复用规则后，还应检查目标图的组号、入口、设施需求和枪池。

### 12.3 旧数据迁移

旧工程可能使用 `serverconfig/codpattern/zombies_rules/<安全地图名>/` 等旧规则位置。先执行 `/cdp map migrate check` 阅读检查结果；需要正式执行存储迁移时，使用主模组的 `/cdp map migrate confirm`（权限等级 4），并事先保存备份。

存储迁移不等于任意旧规则内容自动升级。当前迁移预检要求拆分的五份 v1 配置有效，存在的屏障配置必须是合法 v2；发现旧 `wavetext` 目录会要求手动转换为当前结构。

玩家点旧数据缺 `group` 可按 `0` 兼容；旧屏障缺 `entryId` 会成为未绑定入口，应在面板选定。屏障 v1 的价格/动作需要整理进 v2 的组动作与 `entries`，不能只把 `schemaVersion` 数字改成 2。

## 13. 故障排查

| 现象 | 优先检查 |
|---|---|
| 工具打不开 | 是否主手持正确工具、是否 Ctrl + 右键、是否拥有服务器等级 2 权限 |
| 右键不能设置普通设施交互点 | 当前实现单点类型右键无操作；在属性栏改交互坐标 |
| 点击世界后多出一个点 | 普通单点左键是新增；移动已有对象需改坐标 |
| 复制对象提示位置重复 | 副本保留原坐标且被冲突检查拒绝；在其他位置新增 |
| 关闭界面后刚才修改没了 | 是否使用“关闭/丢弃”而不是“收起”，是否完成保存 |
| 显示已保存但不能开局 | 切完整校验，再看开局预检的地图、波次、规则问题；保存允许未就绪状态 |
| 玩家开局出现在后区 | 后区玩家点是否仍是组 `0` |
| 开门后死亡仍在旧区复活 | 是否只写了 `spawnGroupChanges`；还需独立配置 `playerSpawnGroupChanges` 并新开一局 |
| 开门后存活玩家没传送 | 这是预期行为；组切换影响后续复活，不会自动搬动存活玩家 |
| 屏障入口无规则或不能买 | v2 中是否存在准确的组键、入口键，几何 `entryId` 是否仍为 0 |
| 钥匙外观相同但不能开门 | 物品 ID、完整 NBT 是否相同，是否存在额外改名等标签 |
| 某波一直不结束 | 看实体诊断是否有残留怪物；检查当前僵尸启用组是否有正权重、无碰撞且可达的点 |
| 出生点校验通过却不刷怪 | 开局只启用组 `0`；检查权重、身体空间、当前维度、怪物体型和剩余预算 |
| 有箱体但点击无效 | 是否注册了对象、已保存、`pos=interactionPos`、实际方块正确、主手操作、处于购买阶段 |
| 保存设施失败 | 目标是否实心建筑，是否与其他对象坐标冲突，是否有位置/朝向冲突 |
| 补弹或升级不认枪 | 主手是否为本房间认可的 TACZ 枪，当前武器等级是否有价格，是否已满弹或满级 |
| 汽水/升级机提示未开电 | `requiresPower` 是否为 true，地图是否有有效电源，本局是否已购买开电 |
| 修改规则后当局不变 | 当前局使用冻结配置；结束后新开一局。确认修改的是当前世界、当前地图目录 |
| 配置改完恢复默认 | 普通五份 v1 文件可能因 JSON 或 schema 错误被默认重建；恢复备份并检查格式 |
| 聊天出现 text1/text2 | `wave_text` 为空时自动生成的示例未改，替换文本或保留空 messages 文件 |
| 想用修窗奖励但找不到选项 | 当前仅有窗口数据结构，尚无对应部署与玩法实现 |

服务器 `logs/latest.log` 中的开局预检会包含房间 ID、最大波数、地图/规则/波次问题数量及首个错误。排查时先修复首个具体问题，再重新验证，避免用重新保存掩盖配置错误。

## 14. 源码索引

以下链接指向本手册核对过的实现。修改功能时，应同步检查相应文档段落。

| 主题 | 源码 |
|---|---|
| 版本和依赖 | [gradle.properties](../gradle.properties)、[mods.toml](../src/main/resources/META-INF/mods.toml) |
| 部署字段与新建默认 | [ZombiesDeployFieldSchema](../src/main/java/com/cdp/codpattern/app/zombies/deploy/ZombiesDeployFieldSchema.java) |
| 世界取点、保存、坐标冲突、历史 | [ZombiesDeployToolService](../src/main/java/com/cdp/codpattern/app/zombies/deploy/ZombiesDeployToolService.java) |
| 面板操作及快捷键 | [ZombiesDeployToolScreen](../src/main/java/com/cdp/codpattern/client/gui/screen/zombies/deploy/ZombiesDeployToolScreen.java)、[ZombiesDeployWorldInputHandler](../src/main/java/com/cdp/codpattern/event/client/zombies/ZombiesDeployWorldInputHandler.java) |
| 地图对象持久化 | [ZombiesMapObjects](../src/main/java/com/cdp/codpattern/app/zombies/map/ZombiesMapObjects.java)、[ZombiesInitialSpawnData](../src/main/java/com/cdp/codpattern/app/zombies/map/object/ZombiesInitialSpawnData.java) |
| 地图完整校验 | [ZombiesMapValidator](../src/main/java/com/cdp/codpattern/app/zombies/validation/ZombiesMapValidator.java)、[ZombiesMapValidationProfile](../src/main/java/com/cdp/codpattern/app/zombies/validation/ZombiesMapValidationProfile.java) |
| 开局预检 | [ZombiesStartupValidationService](../src/main/java/com/cdp/codpattern/app/zombies/service/ZombiesStartupValidationService.java) |
| 玩家分配与波间复活 | [ZombiesSpawnAssignmentService](../src/main/java/com/cdp/codpattern/app/zombies/service/ZombiesSpawnAssignmentService.java)、[ZombiesIntermissionRespawnService](../src/main/java/com/cdp/codpattern/app/zombies/service/ZombiesIntermissionRespawnService.java) |
| 屏障 v2 配置与购买 | [ZombiesBarrierGroupsConfig](../src/main/java/com/cdp/codpattern/config/zombies/ZombiesBarrierGroupsConfig.java)、[ZombiesBarrierService](../src/main/java/com/cdp/codpattern/app/zombies/service/ZombiesBarrierService.java) |
| 屏障尺寸与实际方块 | [ZombiesBarrierBlockRuntimeService](../src/main/java/com/cdp/codpattern/app/zombies/service/ZombiesBarrierBlockRuntimeService.java) |
| 箱体放置和交互 | [ZombiesPurchasableBlockService](../src/main/java/com/cdp/codpattern/app/zombies/service/ZombiesPurchasableBlockService.java)、[ZombiesObjectInteractionService](../src/main/java/com/cdp/codpattern/app/zombies/service/ZombiesObjectInteractionService.java) |
| 规则路径与默认重建 | [ZombiesConfigPaths](../src/main/java/com/cdp/codpattern/config/zombies/ZombiesConfigPaths.java)、[ZombiesConfigRepository](../src/main/java/com/cdp/codpattern/config/zombies/ZombiesConfigRepository.java) |
| 房间、武器与枪池规则 | [config/zombies 目录](../src/main/java/com/cdp/codpattern/config/zombies/) |
| 波次文件读取 | [ZombiesWaveConfigRepository](../src/main/java/com/cdp/codpattern/app/zombies/service/ZombiesWaveConfigRepository.java) |
| 波次文本与相对延时 | [ZombiesWaveTextRepository](../src/main/java/com/cdp/codpattern/app/zombies/service/ZombiesWaveTextRepository.java)、[ZombiesWaveTextScheduler](../src/main/java/com/cdp/codpattern/app/zombies/service/ZombiesWaveTextScheduler.java) |
| 当局冻结、清局重置与波次阶段 | [ZombiesMap](../src/main/java/com/cdp/codpattern/compat/fpsmatch/map/zombies/ZombiesMap.java) |

跨仓库行为来自配套主工程 `codPattern`，该工程不在本仓库内。维护者可按以下源码路径定位：

- 存储根路径与名称编码：`com/cdp/codpattern/config/storage/MapStoragePaths.java`。
- `/cdp map`、endtp 和迁移命令：`com/cdp/codpattern/command/MapManagementCommand.java`。
- 房间诊断命令：`com/cdp/codpattern/command/ModeDebugCommand.java`。
- 暂停菜单游戏大厅入口：`com/cdp/codpattern/event/client/CreateMenuButtonsHandler.java`；房间操作：`com/cdp/codpattern/client/gui/screen/match/ModeRoomActionController.java`。
- 强制结束命令：`com/cdp/codpattern/event/RoomTerminationEvents.java`。
- 地图管理界面与结束传送：`com/cdp/codpattern/client/gui/screen/MapManagementScreen.java`、`EndTeleportScreen.java`。
- 工具权限与表面相邻格取点：`com/phasetranscrystal/fpsmatch/common/item/tool/ToolAccessHelper.java`、`ToolInteractionHit.java`。
- 玩家传送朝向与碰撞检查：`com/phasetranscrystal/fpsmatch/core/map/BaseMap.java`、`SpawnSafetyValidator.java`。

本文示例是配置说明，不是已经安装到某个世界的部署结果。部署完成以目标服务器上的完整校验与实战验收为准。
