# COD Pattern Zombies

COD Pattern 的僵尸模式附属模组，独立维护、构建和发布。主模组作为已发布的二进制依赖加载；构建本仓库不需要主模组源码，也不需要同级的 `codPattern` 目录。

## 版本与依赖

| 项目 | 当前配置 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 构建使用 47.4.0 |
| Java | JDK 17 |
| 附属模组 | 0.2.7b，模组 ID 为 `codpattern_zombies` |
| COD Pattern | 构建固定使用 [0.8.8b](https://modrinth.com/mod/cod-pattern/version/zh5dnopt) |
| TaCZ | 1.1.6 或以上；构建使用已固定的 Curse Maven 文件 |
| LR Tactical | 可选；编译兼容依赖为 0.3.0 |

主模组通过 Modrinth Maven 坐标 `maven.modrinth:cod-pattern:0.8.8b` 获取，ForgeGradle 为开发环境处理映射。`gradle.properties` 中的 `codpattern_version` 控制构建依赖，`codpattern_version_range` 控制安装时允许的主模组版本范围。当前最低版本为 0.8.8b；升级构建依赖后应重新执行构建和游戏内验证。

## 独立构建

安装 JDK 17 后，在本仓库根目录执行。首次构建需要联网下载 Gradle、Forge、映射和模组依赖。

Linux / macOS / WSL：

```sh
./gradlew clean build
```

Windows PowerShell：

```powershell
.\gradlew.bat clean build
```

正式构建产物位于 `build/libs/codpattern-zombies-0.2.7b.jar`。运行 `./gradlew buildSnapshot`（Windows 使用 `.\gradlew.bat buildSnapshot`）会额外生成带本地日期的 `-SNAPSHOT-yyyyMMdd.jar`；快照文件名不会改变模组内部版本。

附属模组 JAR 只包含本仓库的代码和资源，不会内嵌 COD Pattern、TaCZ 或其他前置模组。发布附属模组时需另外声明运行依赖。

## 安装与开发运行

在 Minecraft 1.20.1 的 Forge 实例中，将以下文件分别放入 `mods` 目录：

1. [COD Pattern 0.8.8b](https://modrinth.com/mod/cod-pattern/version/zh5dnopt)。
2. TaCZ 1.1.6 或以上的兼容版本。
3. 本仓库构建的 `codpattern-zombies-0.2.7b.jar`。

客户端与服务器都必须安装附属模组，且附属模组版本必须完全一致。LR Tactical 属于可选扩展，使用对应内容时需另行安装。不要同时放入正式版和快照版附属模组，否则会出现重复模组 ID。

开发客户端可执行 `./gradlew runClient`，开发专用服务器可执行 `./gradlew runServer`。Gradle 会自动提供主模组和 TaCZ 依赖；开发客户端还加载构建脚本中指定的 Physics Mod 和 TaCZ addon。运行目录为本仓库的 `run`。首次启动专用服务器需要阅读并接受 Minecraft EULA。

仅构建通过不代表所有游戏行为均已验证。修改主模组版本时，至少验证客户端启动、专用服务器启动、客户端连接以及僵尸房间创建和开局流程。

## 依赖边界验证

`./gradlew runZombiesAddonPublicApiBoundaryCompat runModeSplitPhase7BoundaryCompat` 会检查附属只引用主模组公开类型、两个产物没有重复生产类，并验证三个游戏模式组合注册。常规兼容测试和 GameTest 使用本仓库的测试输入，不需要主仓库。

少量检查上游实现细节的历史源码断言单独保留在 `runMainSourceIntegrationCompat`。仅在联合维护主模组时显式运行 `./gradlew runMainSourceIntegrationCompat -PmainSourceRoot=/path/to/codPattern`；没有指定路径时该任务会报错。它不属于独立构建要求，也不会将主模组源码加入编译。

## 0.2.6b / 0.2.7b 本体备弹更新

0.2.6b 将起始装备、墙枪、抽箱和补弹箱统一接入附属自己的本体弹药服务。枪械身份和标准弹匣容量来自根 `GunId` 对应的 TaCZ 资源，备弹直接使用根 `DummyAmmo` / `MaxDummyAmmo`。普通扩容及枪械专用扩容按 TaCZ 标准数据计算，专用数据优先。配置格式、补弹价格和最低 CDP 依赖仍保持不变。

补弹只增加本体备弹；激活 Arcana 副挂时也遵守同一规则。副挂的弹匣、备弹和当前模式由枪械机制管理。升级只更新管理标签，死亡快照保存完整物品，恢复时从实际根字段重建弹药镜像，不再把旧缓存写回武器。缺失或非法备弹数据会拒绝补弹，明确的 `0/0` 则表示没有备弹容量；这些失败及满弹均不扣费。

0.2.7b 将客户端补弹提示改为读取同一套本体数据，并补齐简体中文、繁体中文、英文、日文反馈。射击面板继续显示当前模式的一整组弹匣、备弹、图标和射击模式，副挂激活时不会混入本体备弹。重连后按房间、装备槽和实际物品身份刷新运行时弹药镜像，不改写持久化物品；同一槽存在冲突物品时不猜测选择。

本轮支持标准 TaCZ 本体弹匣与扩容机制。Arcana 特殊弹种、内置附件等额外动态容量修正未作保证；历史上已经误写到副挂的备弹不自动清理。

### 本体备弹回归

纯逻辑和 HUD 检查：

```sh
./gradlew runZombiesBaseAmmoTransactionCompat runZombiesBackpackCompatSuite runZombiesUltimateMachineHeldWeaponCompatTest runZombiesWeaponHudCompat check
```

带真实 TaCZ 资源的开发服务器检查：

```sh
./gradlew runGameTestServer -PbackpackGameTests -PgameTestRunDirectory=build/gametest/base-ammo
```

测试覆盖三个发枪入口、三个装备槽、扩容、非法数据的只读处理、物品替换、精确扣费、提交回滚及快照恢复。Arcana 回归另设显式开关 `-PbaseAmmoArcanaGameTests`，需要先在隔离测试环境提供实际 Arcana 1.1.4 loader-hybrid 及官方 Vanguard Armament v1.0.1 hotfix4 枪包（`tacz:ak47` + `ts:weapon_gp25`）。该测试调用原生安装和模式切换入口；没有对应运行依赖时应失败，不会跳过后声称兼容。测试使用的 Arcana 反射仅存在于 `src/test`，不会进入发布 JAR。

### 0.2.7b 发布 JAR 验证记录

使用同一个发布 JAR（SHA-256 `85dc547faa046046c65bbfd16ec158cc471dcd15b110ec6a0c916e9cf158b3af`），在 Minecraft 1.20.1 / Forge 47.4.0 / TaCZ 1.1.8-hotfix2 的生产映射运行环境执行 Forge GameTestServer 回归。Arcana 组使用 1.1.4 loader-hybrid、Kotlin for Forge 4.12.0 和上述 Vanguard 枪包。

| CDP | 无 Arcana | 有 Arcana |
| --- | --- | --- |
| 0.8.14b | 22 / 22 通过 | 23 / 23 通过 |
| 0.8.18b | 22 / 22 通过 | 23 / 23 通过 |
| 最低依赖 0.8.8b | 22 / 22 通过 | 未列入本轮矩阵 |

覆盖本体缺弹而副挂满弹、本体满弹而副挂缺弹、副挂激活期间的初始化／补弹／升级／快照恢复，以及拒绝后的扣费和物品完整性。重连回归经过实际玩家 NBT 序列化、登录恢复入口和房间重挂；它不模拟网络断线或玩家文件磁盘传输。原生换弹验证使用 TaCZ 实际备弹提取路径及 Arcana 原生模式切换，不将其表述为换弹按键或动画测试。

客户端用相同 JAR 验证了 CDP 0.8.14b 无 Arcana，以及 0.8.14b / 0.8.18b 有 Arcana 三组环境，均通过。实际集成服务器同步后的本体备弹与补弹提示为 `106/120`；普通枪射击面板为 `030 / 0106`、自动模式，激活 GP25 时为 `001 / 0000`、半自动模式。检查包括六种补弹提示状态、扣费服务流程、完整副挂数据同步及生产面板渲染截图。渲染使用临时房间上下文，不代表完整人工僵尸对局或客户端实体补弹箱点击测试。

Vanguard 的 GP25 原生配置复用了 AK47 HUD 图标，验证以当前模式返回的实际纹理为准。Arcana 服务端持枪 tick 会在缺失时补入 `Extras.Gun_UUID`；同步校验仅允许这项已核实的原生初始化，其他初始字段保持不变，并要求稳定后的服务端与客户端完整 `Extras` 相等。

详细运行清单、JAR 校验值和截图索引保留在本地 `build/verification/base-ammo/final-027-server-matrix.json` 与 `final-027-client-matrix.json`。

上述结果只针对本体备弹改造，不代表对 CDP 改枪界面或其他附属功能的全面兼容认证。原有副挂污染诊断和本轮隔离运行日志保留在本地验证目录，未用新的通过结果覆盖旧证据。
