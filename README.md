# SweetPotatoClient-Updater

SweetPotatoClient 整合包的更新器 / 配置锁工具（Java 控制台 TUI）。

- 更新器版本：见仓库根目录 `VERSION` 文件
- 更新器仓库：<https://git.lsfstudio.top:21619/LiSFeDCo/SweetPotatoClient-Updater.git>
- 客户端仓库：<https://git.lsfstudio.top:21619/LiSFeDCo/SweetPotatoClient.git>

## 功能

| 菜单 | 说明 |
|---|---|
| `[1] 更新 SweetPotatoClient` | `git fetch` 后展示上游提交与文件变更，确认后解锁配置 → `git pull`（失败时按冲突文件清理后重试）→ 回锁配置 → 成功后自动重启更新器 |
| `[2] 更新 SweetPotatoClient-Updater` | 查询最新 Release → 版本核对 → 下载 `update-<tag>.exe` → SHA-256 校验 → 热替换并重启 |
| `[3] +W 解锁配置文件` | 去掉 `PCL/config.v1.yml` 的只读，供 PCL 修改设置 |
| `[4] -W 回锁配置文件` | 把 Java 路径改回内置 JDK 相对路径，并恢复只读 |
| `[5] 退出` | 退出 |

首屏会等云端版本查询完成（最多 6 秒）再绘制，避免一直显示「查询中...」；每次从子界面返回菜单都会清屏并清理回滚缓冲。
有新版本时会在菜单里提示，并在 `[2]` 后面标出新版本号。

## 更新器自更新（[2]）

1. 请求 `GET /api/v1/repos/LiSFeDCo/SweetPotatoClient-Updater/releases/latest`；
2. 读 `tag_name`（如 `v0.0.3-00038`）与本地版本比较；
3. 在附件里找 `update-<tag>.exe`，并找同名 `.sha256`；
4. 下载到 `update.exe.new`，用 `.sha256` 校验（**没有校验文件会拒绝更新**）；
5. 热替换：把当前 `update.exe` 复制成临时「替换助手」→ 当前进程退出 → 助手覆盖 `update.exe` 并重启新版本。

> Release 发布要求：附件名必须是 `update-vX.Y.Z-NNNNN.exe`，并同时上传 `update-vX.Y.Z-NNNNN.exe.sha256`
> （内容为 `<hash>  <文件名>`，GNU sha256sum 格式）。

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
SweetPotatoClient\update.exe                          <- 直接生成到客户端根目录（单文件，约 30 KB）
SweetPotatoClient-Updater\dist\update-<版本号>.exe    <- 带版本号的 Release 副本（如 update-v0.0.3-00038.exe）
SweetPotatoClient-Updater\build\libs\SweetPotatoClient-Updater-<版本号>.jar
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
update.exe --color                :: 强制彩色（默认自动检测）
update.exe --no-color             :: 关闭颜色
update.exe --version              :: 显示版本
update.exe update [-y]            :: 直接执行客户端更新（-y 自动确认，无交互）
```

颜色：

- 由 `update.exe` 启动时给控制台打开 ANSI/VT 转义（`SetConsoleMode` + `ENABLE_VIRTUAL_TERMINAL_PROCESSING`），
  所以 cmd / conhost / Windows Terminal 都能显示彩色；
- 直接 `java -jar update.jar` 运行时，只有 Windows Terminal 等原生支持 ANSI 的终端才会彩色；
- 设置环境变量 `NO_COLOR=1` 可强制关闭彩色。

## 版本号

版本格式：`v主.次.修订-构建号`，例如 `v0.0.3-00038`。

- 唯一来源是仓库根目录的 **`VERSION`** 文件；构建脚本和运行时（jar 内 `version.txt`）都读它，不会出现两处不一致；
- 构建号递增：`gradlew.bat bumpVersion`（例：`v0.0.3-00037` → `v0.0.3-00038`）；
- 要手改版本：直接编辑 `VERSION` 文件后重新构建；
- `update.exe --version` 和主菜单里的「更新器版本」都显示该值。

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
