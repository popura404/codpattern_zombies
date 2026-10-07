# COD Pattern Zombies

COD Pattern 的僵尸模式附属模组，独立维护、构建和发布。主模组作为已发布的二进制依赖加载；构建本仓库不需要主模组源码，也不需要同级的 `codPattern` 目录。

## 版本与依赖

| 项目 | 当前配置 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 构建使用 47.4.0 |
| Java | JDK 17 |
| 附属模组 | 0.2.4b，模组 ID 为 `codpattern_zombies` |
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

正式构建产物位于 `build/libs/codpattern-zombies-0.2.4b.jar`。运行 `./gradlew buildSnapshot`（Windows 使用 `.\gradlew.bat buildSnapshot`）会额外生成带本地日期的 `-SNAPSHOT-yyyyMMdd.jar`；快照文件名不会改变模组内部版本。

附属模组 JAR 只包含本仓库的代码和资源，不会内嵌 COD Pattern、TaCZ 或其他前置模组。发布附属模组时需另外声明运行依赖。

## 安装与开发运行

在 Minecraft 1.20.1 的 Forge 实例中，将以下文件分别放入 `mods` 目录：

1. [COD Pattern 0.8.8b](https://modrinth.com/mod/cod-pattern/version/zh5dnopt)。
2. TaCZ 1.1.6 或以上的兼容版本。
3. 本仓库构建的 `codpattern-zombies-0.2.4b.jar`。

客户端与服务器都必须安装附属模组，且附属模组版本必须完全一致。LR Tactical 属于可选扩展，使用对应内容时需另行安装。不要同时放入正式版和快照版附属模组，否则会出现重复模组 ID。

开发客户端可执行 `./gradlew runClient`，开发专用服务器可执行 `./gradlew runServer`。Gradle 会自动提供主模组和 TaCZ 依赖；开发客户端还加载构建脚本中指定的 Physics Mod 和 TaCZ addon。运行目录为本仓库的 `run`。首次启动专用服务器需要阅读并接受 Minecraft EULA。

仅构建通过不代表所有游戏行为均已验证。修改主模组版本时，至少验证客户端启动、专用服务器启动、客户端连接以及僵尸房间创建和开局流程。

## 依赖边界验证

`./gradlew runZombiesAddonPublicApiBoundaryCompat runModeSplitPhase7BoundaryCompat` 会检查附属只引用主模组公开类型、两个产物没有重复生产类，并验证三个游戏模式组合注册。常规兼容测试和 GameTest 使用本仓库的测试输入，不需要主仓库。

少量检查上游实现细节的历史源码断言单独保留在 `runMainSourceIntegrationCompat`。仅在联合维护主模组时显式运行 `./gradlew runMainSourceIntegrationCompat -PmainSourceRoot=/path/to/codPattern`；没有指定路径时该任务会报错。它不属于独立构建要求，也不会将主模组源码加入编译。
