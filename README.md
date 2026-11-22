# SweetPotatoClient-Updater

SweetPotatoClient 整合包的更新器 / 配置锁工具（Java 控制台 TUI）。

- 更新器版本：`v0.0.1-00001`
- 更新器仓库：<https://git.lsfstudio.top:21619/LiSFeDCo/SweetPotatoClient-Updater.git>
- 客户端仓库：<https://git.lsfstudio.top:21619/LiSFeDCo/SweetPotatoClient.git>

## 功能

| 菜单 | 说明 |
|---|---|
| `[1] 更新 SweetPotatoClient` | `git fetch` 后展示上游提交与文件变更，确认后解锁配置 → `git pull`（失败时按冲突文件清理后重试）→ 回锁配置 |
| `[2] 更新 SweetPotatoClient-Updater` | **占位**：等待首个 Release 发布后再实现（版本格式/下载地址待定） |
| `[3] +W 解锁配置文件` | 去掉 `PCL/config.v1.yml` 的只读，供 PCL 修改设置 |
| `[4] -W 回锁配置文件` | 把 Java 路径改回内置 JDK 相对路径，并恢复只读 |
| `[5] 退出` | 退出 |

主菜单会显示：更新器版本、云端版本（待接入 Release）、两个仓库地址、客户端本地/上游提交、配置锁状态。

## 构建

需要 **JDK 21+**（Gradle 用来编译）。单文件 exe 由 .NET Framework 自带的 `csc` 生成（Windows 10/11 默认就有）。

```bat
build.bat
```

或手动：

```bat
gradlew.bat --no-daemon build
```

产物：

```
SweetPotatoClient\update.exe      <- 直接生成到客户端根目录（单文件，约 30 KB）
SweetPotatoClient-Updater\build\libs\SweetPotatoClient-Updater-v0.0.1-00001.jar
```

> `update.exe` 是单文件启动器：内置了 `update.jar`，运行时自动使用客户端自带的 JDK
> （`jdk-*/bin/java.exe`），不需要额外的 `runtime/` 目录。
> 每次 `gradlew build` / `build.bat` 都会把新的 `update.exe` 输出到 `SweetPotatoClient` 根目录。

可选：需要自带运行时的 app-image 时执行 `gradlew packageExe`，输出到 `dist/update/update.exe`。

## 运行

把 `update.exe` 放到 `SweetPotatoClient` 根目录即可双击运行；

```bat
update.exe                        :: 自动向上查找 SweetPotatoClient 仓库
update.exe --client=D:\path\to\SweetPotatoClient
update.exe --no-color             :: 关闭 ANSI 颜色
update.exe --version              :: 显示版本
```

版本格式：`v主.次.修订-构建号`，例如 `v0.0.1-00001`。

## 目录

```
src/main/java/top/lsfstudio/spcupdater/
  Main.java        菜单与各项流程
  Ui.java          控制台界面（ASCII 横幅 / 颜色 / 输入）
  Git.java         git 命令封装
  PclConfig.java   PCL 配置只读锁 + Java 路径修复
  UpdaterInfo.java 版本与仓库地址
launcher/
  UpdateLauncher.cs 单文件 exe 启动器（内嵌 jar，调用客户端 JDK）
```
