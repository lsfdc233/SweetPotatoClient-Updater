package top.lsfstudio.spcupdater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** SweetPotatoClient 更新器（控制台 TUI）。 */
public final class Main {
    private static final int MAX_STAT_LINES = 20;
    private static final int MAX_CHANGES_SHOWN = 15;
    private static final int MAX_RELEASE_BODY_LINES = 12;

    private Path client;
    private PclConfig pcl;
    private Path explicitClient;
    private final CountDownLatch cloudReady = new CountDownLatch(1);
    private final CountDownLatch fetchReady = new CountDownLatch(1);
    private volatile String fetchError = "";

    private volatile ReleaseClient.Release latestRelease;
    private volatile String latestReleaseError = "";
    private boolean autoMode;

    private Main(Path client) {
        this.client = client;
        this.pcl = client == null ? null : new PclConfig(client);
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            switch (args[0]) {
                case "--apply-update" -> {
                    if (args.length >= 4) {
                        System.exit(Launcher.applyUpdate(args[1], args[2], args[3]));
                    }
                    return;
                }
                case "--apply-update-pull" -> {
                    if (args.length >= 5) {
                        System.exit(Launcher.applyUpdatePull(args[1], args[2], args[3], args[4]));
                    }
                    return;
                }
                case "--clear-console" -> {
                    Launcher.clearConsole();
                    return;
                }
                default -> {
                }
            }
        }
        boolean wantColor = true;
        boolean forceColor = false;
        boolean autoYes = false;
        String command = "";
        Path clientArg = null;
        for (String arg : args) {
            switch (arg) {
                case "--no-color" -> wantColor = false;
                case "--color" -> forceColor = true;
                case "--version", "-v" -> {
                    System.out.println(UpdaterInfo.VERSION);
                    return;
                }
                case "--help", "-h" -> {
                    printHelp();
                    return;
                }
                default -> {
                    if (arg.startsWith("--client=")) {
                        clientArg = Path.of(arg.substring("--client=".length()));
                    } else if (arg.equalsIgnoreCase("update")) {
                        command = "update";
                    } else if (arg.equalsIgnoreCase("-y") || arg.equalsIgnoreCase("--yes")) {
                        autoYes = true;
                    }
                }
            }
        }
        Ui.init(wantColor, forceColor);
        Main app = new Main(null);
        app.explicitClient = clientArg;
        app.autoMode = autoYes;
        if ("update".equals(command)) {
            app.client = app.resolveClientPath();
            app.pcl = new PclConfig(app.client);
            app.runCliUpdate();
        } else {
            app.startInteractive();
        }
    }

    /** 启动流程：艺术字 → 检查客户端文件 → 解析路径 → 清屏进入主菜单。 */
    private void startInteractive() {
        Git.startReadyMonitor();
        Ui.clear();
        Ui.banner();
        Ui.println("  made by " + UpdaterInfo.AUTHOR);
        Ui.println();
        Ui.println("  正在检查客户端文件......");
        client = resolveClientPath();
        pcl = new PclConfig(client);
        removeStaleOldExe();
        Ui.clear();
        run();
    }

    /**
     * 解析客户端目录：
     * A. 环境变量 SweetPotatoClient_PATH
     * B. update.exe 同级目录是否为客户端（json / jar / .git）
     * 都不满足时提示用户输入路径或 install（文件选择器 + git clone）。
     */
    private Path resolveClientPath() {
        if (explicitClient != null) {
            Path explicit = explicitClient.toAbsolutePath().normalize();
            if (isClientDir(explicit)) {
                return explicit;
            }
            Ui.println(Ui.red("  --client 指定的目录不是客户端：" + explicit));
        }
        String envPath = System.getenv("SweetPotatoClient_PATH");
        if (envPath != null && !envPath.isBlank()) {
            Path fromEnv = Path.of(envPath).toAbsolutePath().normalize();
            if (isClientDir(fromEnv)) {
                Ui.println("  使用环境变量 SweetPotatoClient_PATH：" + Ui.gray(fromEnv.toString()));
                return fromEnv;
            }
            Ui.println(Ui.yellow("  环境变量 SweetPotatoClient_PATH 无效：" + fromEnv));
        }
        Path self = Launcher.selfExe();
        Path[] candidates = { self == null ? null : self.getParent(), Path.of("").toAbsolutePath() };
        for (Path candidate : candidates) {
            if (candidate != null && isClientDir(candidate)) {
                Path dir = candidate.toAbsolutePath().normalize();
                Ui.println("  当前目录是客户端：" + Ui.gray(dir.toString()));
                writeClientEnvVar(dir);
                return dir;
            }
        }
        while (true) {
            String line = Ui.readLine("请输入客户端文件夹路径，或输入 install 进行全新安装");
            if (line == null) {
                System.exit(1);
            }
            String text = line.trim().replace("\"", "");
            if (text.isEmpty()) {
                continue;
            }
            if (text.equalsIgnoreCase("install")) {
                Path installed = installClient();
                if (installed != null) {
                    return installed;
                }
                continue;
            }
            Path dir = Path.of(text).toAbsolutePath().normalize();
            if (isClientDir(dir)) {
                writeClientEnvVar(dir);
                return dir;
            }
            Ui.println(Ui.red("  该路径不是 SweetPotatoClient 客户端，请重新输入。"));
        }
    }

    /** 清理上一次自替换留下的 <exe>.old（可能当时还被占用）。 */
    private static void removeStaleOldExe() {
        Path self = Launcher.selfExe();
        if (self == null) {
            return;
        }
        Path old = self.resolveSibling(self.getFileName().toString() + ".old");
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                if (!Files.exists(old)) {
                    return;
                }
                Files.delete(old);
                return;
            } catch (Exception e) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
    private static boolean isClientDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        if (Files.isRegularFile(dir.resolve("SweetPotatoClient.json"))) {
            return true;
        }
        if (Files.isDirectory(dir.resolve(".git"))) {
            return true;
        }
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"));
        } catch (Exception e) {
            return false;
        }
    }

    private void writeClientEnvVar(Path dir) {
        try {
            Process process = new ProcessBuilder("setx", "SweetPotatoClient_PATH", dir.toString())
                    .redirectErrorStream(true)
                    .start();
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            Ui.println("  已写入环境变量 SweetPotatoClient_PATH。");
        } catch (Exception e) {
            Ui.println(Ui.yellow("  写入环境变量失败：" + e.getMessage()));
        }
    }

    /** 输入 install：文件选择器选目录 → git clone → 写入环境变量。 */
    private Path installClient() {
        String chosen = pickFolder();
        if (chosen == null || chosen.isBlank()) {
            Ui.println("  已取消安装。");
            return null;
        }
        Path dir = Path.of(chosen.trim());
        try {
            if (Files.exists(dir) && Files.list(dir).findAny().isPresent()) {
                dir = dir.resolve("SweetPotatoClient");
            }
        } catch (Exception ignored) {
            // 目录不可读时交给 git clone 报错
        }
        if (!Git.ready()) {
            Ui.println("  等待 Git 就绪......");
            if (!Git.awaitReady(180_000L)) {
                Ui.println(Ui.red("  Git 未就绪，无法克隆客户端。"));
                return null;
            }
        }
        Ui.println("  正在克隆客户端到：" + Ui.gray(dir.toString()));
        try {
            Process process = new ProcessBuilder(Git.executable(), "clone",
                    "https://git.lsfstudio.top:21619/LiSFeDCo/SweetPotatoClient.git",
                    dir.toString())
                    .inheritIO()
                    .start();
            if (process.waitFor() != 0) {
                Ui.println(Ui.red("  克隆失败，请检查网络后重试。"));
                return null;
            }
        } catch (Exception e) {
            Ui.println(Ui.red("  克隆失败：" + e.getMessage()));
            return null;
        }
        writeClientEnvVar(dir);
        return dir;
    }

    private static String pickFolder() {
        String preset = System.getenv("SPC_INSTALL_DIR");
        if (preset != null && !preset.isBlank()) {
            return preset;
        }
        String script = String.join("\n",
                "Add-Type -AssemblyName System.Windows.Forms",
                "$f = New-Object System.Windows.Forms.FolderBrowserDialog",
                "$f.Description = '选择 SweetPotatoClient 安装位置'",
                "if ($f.ShowDialog() -eq 'OK') {",
                "  [IO.File]::WriteAllText($args[0], $f.SelectedPath, (New-Object Text.UTF8Encoding $false))",
                "}");
        Path scriptFile = null;
        Path resultFile = null;
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"));
            scriptFile = dir.resolve("spc-pick-folder-" + ProcessHandle.current().pid() + ".ps1");
            resultFile = dir.resolve("spc-pick-folder-" + ProcessHandle.current().pid() + ".txt");
            Files.deleteIfExists(resultFile);
            // PowerShell 5.1 读无 BOM 的 .ps1 会按 ANSI 解析，中文会乱码，这里带 BOM 写入
            Files.write(scriptFile, ("\uFEFF" + script).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-STA", "-ExecutionPolicy", "Bypass", "-File", scriptFile.toString(), resultFile.toString());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!Files.isRegularFile(resultFile)) {
                return null;
            }
            String output = Files.readString(resultFile, java.nio.charset.StandardCharsets.UTF_8).trim();
            return output.isEmpty() ? null : output;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                Files.deleteIfExists(scriptFile);
                Files.deleteIfExists(resultFile);
            } catch (Exception ignored) {
                // 忽略清理失败
            }
        }
    }

    private static void printHelp() {
        System.out.println("SweetPotatoClient Updater " + UpdaterInfo.VERSION);
        System.out.println();
        System.out.println("用法：update.exe [选项]");
        System.out.println();
        System.out.println("  --client=<目录>   指定 SweetPotatoClient 根目录（默认自动查找）");
        System.out.println("  --color           强制彩色输出（默认自动检测）");
        System.out.println("  --no-color        关闭颜色（也可设置 NO_COLOR 环境变量）");
        System.out.println("  --version         显示更新器版本");
        System.out.println("  update [-y]       直接执行客户端更新（-y 自动确认，无交互）");
        System.out.println("  --help            显示帮助");
    }

    /** 优先找 remote 指向 SweetPotatoClient（不是 Updater）的仓库。 */
    private static Path resolveClient(Path explicit) {
        if (explicit != null) {
            Path target = explicit.toAbsolutePath().normalize();
            if (Files.isDirectory(target.resolve(".git"))) {
                return target;
            }
            Path repo = walkUpForAnyRepo(target);
            return repo != null ? repo : target;
        }
        List<Path> primary = new ArrayList<>();
        primary.add(Path.of("").toAbsolutePath().normalize());
        Path envDir = envExeDir();
        if (envDir != null) {
            primary.add(envDir);
            if (envDir.getParent() != null) {
                primary.add(envDir.getParent());
            }
        }
        List<Path> secondary = new ArrayList<>();
        Path exeDir = exeDir();
        if (exeDir != null) {
            secondary.add(exeDir);
            if (exeDir.getParent() != null) {
                secondary.add(exeDir.getParent());
            }
        }
        for (Path seed : primary) {
            Path candidate = walkUpForClientRepo(seed);
            if (candidate != null) {
                return candidate;
            }
        }
        for (Path seed : primary) {
            Path candidate = walkUpForAnyRepo(seed);
            if (candidate != null) {
                return candidate;
            }
        }
        for (Path seed : secondary) {
            Path candidate = walkUpForClientRepo(seed);
            if (candidate != null) {
                return candidate;
            }
        }
        for (Path seed : secondary) {
            Path candidate = walkUpForAnyRepo(seed);
            if (candidate != null) {
                return candidate;
            }
        }
        return primary.get(0);
    }

    private static Path walkUpForAnyRepo(Path start) {
        Path current = start;
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static Path envExeDir() {
        String value = Launcher.selfExeEnv();
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value).toAbsolutePath().normalize().getParent();
    }

    private static Path walkUpForClientRepo(Path start) {
        Path current = start;
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) {
                String url = remoteUrl(current);
                String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
                if (lower.contains("sweetpotatoclient") && !lower.contains("updater")) {
                    return current;
                }
            }
            current = current.getParent();
        }
        return null;
    }

    private static String remoteUrl(Path dir) {
        Git.Result result = Git.run(dir, "config", "--get", "remote.origin.url");
        return result.ok() && !result.first().isBlank() ? result.first() : null;
    }

    private static Path exeDir() {
        try {
            return ProcessHandle.current().info().command()
                    .map(Path::of)
                    .map(Path::getParent)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 界面

    private void run() {
        if (!Git.available()) {
            Ui.println("  等待 Git 就绪......");
            Git.awaitReady(180_000L);
        }
        if (!Git.available()) {
            Ui.println(Ui.red("  Git 未就绪（内置 MinGit 释放失败）。"));
            Ui.pause("按 Enter 退出...");
            return;
        }
        if (!Git.isRepo(client)) {
            Ui.clear();
            Ui.println(Ui.red("  当前目录不是 git 仓库：" + client));
            Ui.println(Ui.gray("  请把 update.exe 放到 SweetPotatoClient 根目录，或用 --client=<目录> 指定。"));
            Ui.pause("按 Enter 退出...");
            return;
        }
        Git.ensureIdentity(client);
        startCloudCheck();
        startGitFetch();
        awaitCloudCheck(Duration.ofSeconds(6));
        awaitFetchReady(Duration.ofSeconds(3));
        while (true) {
            drawMenu();
            String choice = Ui.readLine("请选择 [1-5]");
            if (choice == null) {
                return;
            }
            switch (choice.trim()) {
                case "1" -> {
                    if (updateClient()) {
                        return;
                    }
                }
                case "2" -> {
                    if (updateUpdater()) {
                        return;
                    }
                }
                case "3" -> unlockConfig();
                case "4" -> relockConfig();
                case "5" -> {
                    return;
                }
                default -> {
                    // 忽略无效输入，重绘菜单
                }
            }
        }
    }

    /** 后台查询最新 Release，避免阻塞菜单。 */
    private void startCloudCheck() {
        Thread thread = new Thread(() -> {
            try {
                latestRelease = ReleaseClient.fetchLatest(Duration.ofSeconds(8));
                latestReleaseError = "";
            } catch (Exception e) {
                latestReleaseError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } finally {
                cloudReady.countDown();
            }
        }, "spc-cloud-check");
        thread.setDaemon(true);
        thread.start();
    }

    /** 后台刷新上游引用，避免主菜单显示过期的 origin/main。 */
    private void startGitFetch() {
        Thread thread = new Thread(() -> {
            try {
                Git.Result result = Git.runTimed(client, 8, "fetch", "--prune");
                if (!result.ok()) {
                    fetchError = result.first().isBlank() ? "刷新失败" : result.first();
                }
            } finally {
                fetchReady.countDown();
            }
        }, "spc-git-fetch");
        thread.setDaemon(true);
        thread.start();
    }

    private void awaitFetchReady(Duration timeout) {
        try {
            fetchReady.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 首屏绘制前最多等一会儿云端版本，避免一直显示「查询中...」。 */
    private void awaitCloudCheck(Duration timeout) {
        try {
            cloudReady.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String displayVersion(String tag) {
        if (tag == null || tag.isBlank()) {
            return "?";
        }
        return tag.startsWith("v") ? tag : "v" + tag;
    }

    private String cloudVersionText() {
        ReleaseClient.Release release = latestRelease;
        if (release != null) {
            return displayVersion(release.tag());
        }
        if (!latestReleaseError.isEmpty()) {
            return "未知（" + latestReleaseError + "）";
        }
        return "查询中...";
    }

    private boolean hasNewerUpdater() {
        ReleaseClient.Release release = latestRelease;
        return release != null && VersionUtil.isNewer(release.tag(), UpdaterInfo.VERSION);
    }

    private void drawMenu() {
        Ui.clear();
        Ui.banner();
        Ui.println(Ui.gray("  made by " + UpdaterInfo.AUTHOR));
        Ui.println();
        boolean newer = hasNewerUpdater();
        String gitState = Git.ready()
                ? Ui.green("（Git已就绪 路径：" + Git.displayPath() + "）")
                : Ui.yellow("（等待Git就绪...）");
        Ui.println("  更新器版本：" + Ui.cyan(UpdaterInfo.VERSION) + " " + gitState);
        Ui.println("  云端版本：" + (newer ? Ui.yellow(cloudVersionText() + "（有新版本）") : Ui.gray(cloudVersionText())));
        Ui.println("  更新器仓库：" + Ui.gray(UpdaterInfo.UPDATER_REPO));
        Ui.println("  客户端仓库：" + Ui.gray(UpdaterInfo.CLIENT_REPO));
        if (newer) {
            Ui.println("  " + Ui.yellow("提示：发现更新器新版本 " + displayVersion(latestRelease.tag()) + "，按 [2] 更新"));
        }
        Ui.println();
        Ui.println("  客户端：" + Ui.gray(client.toString()));
        String upstream = Git.upstream(client);
        int[] aheadBehind = Git.aheadBehind(client, upstream);
        Ui.println("  本地提交：" + Git.shortHash(client, "HEAD") + "  " + Ui.gray(Git.subject(client, "HEAD")));
        String fetchNote = fetchError.isEmpty() ? "" : "（刷新失败，显示缓存）";
        Ui.println("  上游提交：" + Git.shortHash(client, upstream) + "  " + Ui.gray(upstream + fetchNote));
        String state;
        if (aheadBehind[1] > 0) {
            state = Ui.yellow("落后上游 " + aheadBehind[1] + " 个提交");
        } else if (aheadBehind[0] > 0) {
            state = Ui.yellow("本地领先 " + aheadBehind[0] + " 个提交");
        } else {
            state = Ui.green("已与上游同步");
        }
        Ui.println("  状态：" + state);
        if (pcl.exists()) {
            Ui.println("  配置：" + (pcl.isReadOnly() ? Ui.green("已锁定（只读）") : Ui.yellow("已解锁（可写）")));
        } else {
            Ui.println("  配置：" + Ui.yellow("未生成（PCL/config.v1.yml 不存在）"));
        }
        Ui.println();
        Ui.println("  " + Ui.cyan("[1]") + " 更新 SweetPotatoClient");
        Ui.println("  " + Ui.cyan("[2]") + " 更新 SweetPotatoClient-Updater"
                + (newer ? Ui.yellow("  ← 新版本 " + displayVersion(latestRelease.tag())) : ""));
        Ui.println("  " + Ui.cyan("[3]") + " +W 解锁配置文件（PCL 修改设置前）");
        Ui.println("  " + Ui.cyan("[4]") + " -W 回锁配置文件（内置 JDK 路径 + 只读）");
        Ui.println("  " + Ui.cyan("[5]") + " 退出");
        Ui.println();
    }

    // ------------------------------------------------------------ 1 更新客户端

    private boolean updateClient() {
        Ui.title("更新 SweetPotatoClient");
        if (!Git.ready()) {
            Ui.println("  等待 Git 就绪......");
            if (!Git.awaitReady(60_000L)) {
                Ui.println(Ui.red("  Git 未就绪，无法更新客户端。"));
                returnHome();
                return false;
            }
        }
        Ui.println("  正在从上游获取最新信息...");
        Git.Result fetch = Git.run(client, "fetch", "--prune");
        if (!fetch.ok()) {
            showOutput(fetch, "获取上游失败（网络或权限问题）：");
            returnHome();
            return false;
        }
        String upstream = Git.upstream(client);
        int[] aheadBehind = Git.aheadBehind(client, upstream);
        Ui.println("  上游：" + Ui.gray(upstream));
        Ui.println();
        Ui.println("  上游最近提交：");
        Ui.println("    " + Git.commitLine(client, upstream));
        Ui.println("    " + Ui.yellow(Git.subject(client, upstream)));
        List<Git.FileChange> changes = Git.commitChanges(client, upstream);
        if (!changes.isEmpty()) {
            Ui.println();
            Ui.println("  文件变更：");
            int shown = Math.min(changes.size(), MAX_STAT_LINES);
            for (int i = 0; i < shown; i++) {
                Ui.println("    " + Ui.gray(describeChange(changes.get(i))));
            }
            if (changes.size() > shown) {
                Ui.println("    " + Ui.gray("... 其余 " + (changes.size() - shown) + " 项省略"));
            }
        }
        Ui.println();
        if (aheadBehind[1] == 0) {
            Ui.println("  " + Ui.green("已经与上游同步，无需更新。"));
            Ui.println();
            Ui.println("  本地提交：" + Git.shortHash(client, "HEAD") + "  " + Ui.gray(Git.subject(client, "HEAD")));
            Ui.println("  上游提交：" + Git.shortHash(client, upstream) + "  " + Ui.gray(upstream));
            Ui.println("  配置：" + (pcl.exists()
                    ? (pcl.isReadOnly() ? Ui.green("已锁定（只读）") : Ui.yellow("已解锁（可写）"))
                    : Ui.yellow("未生成")));
            if (aheadBehind[0] > 0) {
                Ui.println("  " + Ui.yellow("本地领先上游 " + aheadBehind[0] + " 个提交（未推送）"));
            }
            returnHome();
            return false;
        }
        Ui.println("  本地落后上游 " + aheadBehind[1] + " 个提交。");

        // 只提示「上游会改到」且本地有改动的文件
        List<String> incoming = Git.incomingPaths(client, upstream);
        List<String> conflicts = new ArrayList<>();
        for (String path : incoming) {
            if (Git.conflictsWithUpdate(client, upstream, path)) {
                conflicts.add(path);
            }
        }
        String updaterRel = updaterRelativePath();
        boolean updaterInIncoming = updaterRel != null
                && incoming.stream().anyMatch(path -> path.equalsIgnoreCase(updaterRel));
        if (updaterInIncoming) {
            conflicts.removeIf(path -> path.equalsIgnoreCase(updaterRel));
        }
        Ui.println();
        if (conflicts.isEmpty()) {
            Ui.println("  本地没有会被覆盖的冲突文件。");
        } else {
            Ui.println("  " + Ui.red("警告：以下 " + conflicts.size() + " 个本地文件与上游改动冲突，更新会丢弃本地版本："));
            int shown = Math.min(conflicts.size(), MAX_CHANGES_SHOWN);
            for (int i = 0; i < shown; i++) {
                Ui.println("    " + Ui.gray(conflicts.get(i)));
            }
            if (conflicts.size() > shown) {
                Ui.println("    " + Ui.gray("... 其余 " + (conflicts.size() - shown) + " 项省略"));
            }
        }
        if (updaterInIncoming) {
            Ui.println();
            Ui.println("  " + Ui.yellow("本次更新包含更新器自身（" + updaterRel + "），将通过热替换完成。"));
        }
        Ui.println();
        if (!autoMode && !Ui.confirm("确认更新？(y/n)")) {
            Ui.println("  已取消。");
            returnHome();
            return false;
        }

        if (updaterInIncoming) {
            if (autoMode) {
                // 自动模式（已经从 update.exe.old 启动）：update.exe 路径已让出，继续普通 pull
            } else {
                Path renamed = renameRunningUpdater();
                if (renamed != null) {
                    Ui.println("  已把当前更新器重命名为 " + renamed.getFileName()
                            + "，正在以 update -y 热重启完成更新...");
                    if (spawnAutoUpdate(renamed)) {
                        return true;
                    }
                }
                return startUpdaterSelfUpdate(upstream, updaterRel);
            }
        }

        for (String path : conflicts) {
            boolean ok = Git.prepareForPull(client, path);
            Ui.println("    " + (ok ? Ui.gray("已丢弃本地改动：" + path) : Ui.red("无法处理：" + path)));
        }

        pcl.unlock();
        Git.Result pull = Git.run(client, "-c", "pull.rebase=false", "pull", "--no-rebase", "--no-edit");
        if (!pull.ok()) {
            Ui.println();
            Ui.println("  " + Ui.yellow("git pull 失败，尝试清理冲突文件后重试..."));
            List<String> retry = Git.conflicts(pull.lines());
            if (retry.isEmpty()) {
                showOutput(pull, "无法自动判断冲突文件，请手动处理：");
                pcl.relock();
                returnHome();
                return false;
            }
            for (String path : retry) {
                boolean ok = Git.prepareForPull(client, path);
                Ui.println("    " + (ok ? Ui.gray("已丢弃本地改动：" + path) : Ui.red("无法处理：" + path)));
            }
            pull = Git.run(client, "-c", "pull.rebase=false", "pull", "--no-rebase", "--no-edit");
        }
        if (!pull.ok()) {
            showOutput(pull, "更新失败，请手动处理：");
            pcl.relock();
            returnHome();
            return false;
        }

        pcl.relock();
        Ui.println();
        Ui.println("  " + Ui.green("更新完成！"));
        Ui.println("  当前提交：" + Git.shortHash(client, "HEAD") + "  " + Ui.gray(Git.subject(client, "HEAD")));
        Ui.println("  配置文件已重新回锁（只读）。");
        Ui.println();
        returnHome();
        return false;
    }

    private static String describeChange(Git.FileChange change) {
        String type = change.type();
        String label;
        if (type.startsWith("A")) {
            label = "新增";
        } else if (type.startsWith("D")) {
            label = "删除";
        } else if (type.startsWith("M")) {
            label = "修改";
        } else if (type.startsWith("R")) {
            label = "重命名";
        } else if (type.startsWith("C")) {
            label = "复制";
        } else if (type.startsWith("T")) {
            label = "类型变更";
        } else {
            label = "变更";
        }
        if (change.oldPath() != null) {
            return label + "  " + change.oldPath() + " -> " + change.path();
        }
        return label + "  " + change.path();
    }
    private void showOutput(Git.Result result, String title) {
        Ui.println("  " + Ui.red(title));
        for (String line : result.lines()) {
            Ui.println("    " + Ui.gray(line));
        }
    }

    // ------------------------------------------------------------ 2 更新更新器

    /** @return true 表示已启动热替换，需要退出当前进程 */
    private boolean updateUpdater() {
        Ui.title("更新 SweetPotatoClient-Updater");
        Ui.println("  当前版本：" + Ui.cyan(UpdaterInfo.VERSION));
        Ui.println("  正在查询最新 Release...");
        ReleaseClient.Release release;
        try {
            release = ReleaseClient.fetchLatest(Duration.ofSeconds(12));
            latestRelease = release;
            latestReleaseError = "";
        } catch (Exception e) {
            Ui.println("  " + Ui.red("查询最新 Release 失败：" + e.getMessage()));
            Ui.println("  " + Ui.gray(UpdaterInfo.UPDATER_REPO + "/releases"));
            returnHome();
            return false;
        }
        Ui.println("  最新版本：" + Ui.cyan(displayVersion(release.tag())));
        if (!VersionUtil.isNewer(release.tag(), UpdaterInfo.VERSION)) {
            Ui.println("  " + Ui.green("已经是最新版本，无需更新。"));
            returnHome();
            return false;
        }

        String expectedName = "update-" + release.tag() + ".exe";
        String expectedNameV = release.tag().startsWith("v")
                ? expectedName
                : "update-v" + release.tag() + ".exe";
        ReleaseClient.Asset exeAsset = release.asset(expectedName).orElse(null);
        if (exeAsset == null) {
            exeAsset = release.asset(expectedNameV).orElse(null);
        }
        if (exeAsset == null) {
            exeAsset = release.firstUpdateExe().orElse(null);
        }
        if (exeAsset == null) {
            Ui.println("  " + Ui.red("该 Release 没有可用的 update-*.exe 附件。"));
            returnHome();
            return false;
        }
        ReleaseClient.Asset shaAsset = release.asset(exeAsset.name() + ".sha256").orElse(null);

        String body = release.body() == null ? "" : release.body().trim();
        if (!body.isEmpty()) {
            Ui.println();
            Ui.println("  " + Ui.gray("Release 说明："));
            String[] lines = body.split("\\R");
            int shown = Math.min(lines.length, MAX_RELEASE_BODY_LINES);
            for (int i = 0; i < shown; i++) {
                Ui.println("    " + Ui.gray(lines[i]));
            }
            if (lines.length > shown) {
                Ui.println("    " + Ui.gray("...（共 " + lines.length + " 行）"));
            }
        }
        Ui.println();
        Ui.println("  下载附件：" + Ui.gray(exeAsset.name()));
        if (shaAsset == null) {
            Ui.println("  " + Ui.red("缺少 " + exeAsset.name() + ".sha256 校验文件，已取消更新。"));
            Ui.println("  " + Ui.gray("请先给 Release 补传校验文件后再试。"));
            returnHome();
            return false;
        }
        String exeEnv = Launcher.selfExeEnv();
        if (exeEnv == null || exeEnv.isBlank()) {
            Ui.println("  " + Ui.red("自更新需要由 update.exe 启动（当前不是）。"));
            returnHome();
            return false;
        }
        Path exe = Path.of(exeEnv).toAbsolutePath();
        Ui.println();
        if (!autoMode && !Ui.confirm("确认更新？(y/n)")) {
            Ui.println("  已取消。");
            returnHome();
            return false;
        }

        Path temp = exe.resolveSibling(exe.getFileName().toString() + ".new");
        String actualHash;
        try {
            Files.deleteIfExists(temp);
            actualHash = ReleaseClient.download(exeAsset.url(), temp, (done, total) -> {
                int percent = total > 0 ? (int) (done * 100 / total) : 0;
                System.out.print("\r  下载中：" + percent + "%  (" + formatSize(done) + " / " + formatSize(total) + ")   ");
                System.out.flush();
            });
            System.out.println();
            String expectedHash = ReleaseClient.parseSha256(ReleaseClient.downloadText(shaAsset.url()));
            if (expectedHash == null) {
                throw new IOException("校验文件格式不正确");
            }
            if (!expectedHash.equalsIgnoreCase(actualHash)) {
                throw new IOException("SHA-256 不匹配（期望 " + expectedHash + "，实际 " + actualHash + "）");
            }
            Ui.println("  " + Ui.green("SHA-256 校验通过：") + Ui.gray(actualHash));
        } catch (Exception e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 忽略清理失败
            }
            Ui.println("  " + Ui.red("下载/校验失败：" + e.getMessage()));
            returnHome();
            return false;
        }

        Ui.println("  正在热替换并重启更新器...");
        try {
            Path helper = createApplyHelper(exe);
            ProcessBuilder builder = new ProcessBuilder(
                    helper.toString(),
                    "--apply-update",
                    temp.toString(),
                    exe.toString(),
                    String.valueOf(launcherPid()));
            builder.directory(exe.getParent().toFile());
            builder.inheritIO();
            builder.start();
        } catch (Exception e) {
            Ui.println("  " + Ui.red("启动替换进程失败：" + e.getMessage()));
            Ui.println("  新版本已下载到：" + Ui.gray(temp.toString()));
            returnHome();
            return false;
        }
        return true;
    }

    /**
     * 复制当前 update.exe 到临时目录，作为“替换助手”运行。
     * 助手不依赖新版本里是否有替换逻辑，也能覆盖正在运行的旧 exe。
     */
    private static Path createApplyHelper(Path exe) throws IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "sweetpotato-updater");
        Files.createDirectories(dir);
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().startsWith("apply-helper-"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 旧助手可能仍被占用，忽略
                        }
                    });
        }
        Path helper = dir.resolve("apply-helper-" + ProcessHandle.current().pid() + ".exe");
        Files.copy(exe, helper, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return helper;
    }

    private void returnHome() {
        if (!autoMode) {
            Ui.pause("按 Enter 返回主菜单...");
        }
    }

    /** CLI：update [-y]，直接跑一遍客户端更新，结束后把新版本 updater 拉起来。 */
    private void runCliUpdate() {
        if (!Git.available()) {
            System.err.println("未找到 git");
            System.exit(1);
        }
        if (!Git.isRepo(client)) {
            System.err.println("当前目录不是 git 仓库：" + client);
            System.exit(1);
        }
        Git.ensureIdentity(client);
        updateClient();
        launchCanonicalIfNeeded();
    }

    /** 把正在运行的 update.exe 改名成 update.exe.old，让出路径给 git pull。 */
    private Path renameRunningUpdater() {
        String env = Launcher.selfExeEnv();
        if (env == null || env.isBlank()) {
            return null;
        }
        Path exe = Path.of(env).toAbsolutePath();
        Path renamed = exe.resolveSibling(exe.getFileName().toString() + ".old");
        try {
            Files.deleteIfExists(renamed);
            Files.move(exe, renamed);
            return renamed;
        } catch (Exception e) {
            Ui.println("  " + Ui.red("重命名更新器失败：" + e.getMessage() + "，改用助手方式。"));
            return null;
        }
    }

    /** 用改名后的 update.exe.old 以 update -y 参数热重启，自动继续更新。 */
    private boolean spawnAutoUpdate(Path renamedExe) {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    renamedExe.toString(), "update", "-y", "--client=" + client);
            builder.directory(client.toFile());
            builder.inheritIO();
            Git.applyIdentity(builder);
            builder.start();
            return true;
        } catch (Exception e) {
            Ui.println("  " + Ui.red("热重启失败：" + e.getMessage()));
            return false;
        }
    }

    /** 自动流程结束后：优先拉起 pull 下来的 update.exe；若没有则把 .old 还原。 */
    private void launchCanonicalIfNeeded() {
        String selfEnv = Launcher.selfExeEnv();
        Path canonical = selfEnv != null && !selfEnv.isBlank()
                ? Path.of(selfEnv).toAbsolutePath()
                : client.resolve("update.exe");
        Path old = canonical.resolveSibling(canonical.getFileName().toString() + ".old");
        try {
            if (!Files.isRegularFile(canonical) && Files.isRegularFile(old)) {
                Files.move(old, canonical);
            }
        } catch (Exception ignored) {
            // 还原失败就保留 .old
        }
        if (!Files.isRegularFile(canonical)) {
            return;
        }
        String env = Launcher.selfExeEnv();
        if (env != null && !env.isBlank()) {
            try {
                if (Files.isSameFile(Path.of(env).toAbsolutePath(), canonical)) {
                    return;
                }
            } catch (Exception ignored) {
                // 路径已变化，继续启动
            }
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(canonical.toString(), "--client=" + client);
            builder.directory(client.toFile());
            builder.inheritIO();
            builder.start();
        } catch (Exception ignored) {
            // 启动失败保留现场
        }
    }
    private String updaterRelativePath() {
        String env = Launcher.selfExeEnv();
        if (env == null || env.isBlank()) {
            return null;
        }
        Path exe = Path.of(env).toAbsolutePath();
        if (!exe.startsWith(client)) {
            return null;
        }
        return client.relativize(exe).toString().replace('\\', '/');
    }

    /** 更新内容包含更新器本身：导出新 exe，退出后由助手执行 pull + 热替换 + 重启。 */
    private boolean startUpdaterSelfUpdate(String upstream, String updaterRel) {
        String env = Launcher.selfExeEnv();
        if (env == null || env.isBlank()) {
            Ui.println("  " + Ui.red("无法定位当前 update.exe，请通过 update.exe 启动。"));
            returnHome();
            return false;
        }
        Path exe = Path.of(env).toAbsolutePath();
        Path newExe = exe.resolveSibling(exe.getFileName().toString() + ".new");
        Ui.println("  正在导出更新器新版本...");
        if (!Git.exportFile(client, upstream, updaterRel, newExe)) {
            Ui.println("  " + Ui.red("导出更新器失败，请手动处理。"));
            returnHome();
            return false;
        }
        try {
            Path helper = createApplyHelper(exe);
            ProcessBuilder builder = new ProcessBuilder(
                    helper.toString(),
                    "--apply-update-pull",
                    newExe.toString(),
                    exe.toString(),
                    String.valueOf(launcherPid()),
                    client.toString());
            builder.directory(client.toFile());
            builder.inheritIO();
            Git.applyIdentity(builder);
            builder.start();
        } catch (Exception e) {
            Ui.println("  " + Ui.red("启动替换助手失败：" + e.getMessage()));
            returnHome();
            return false;
        }
        return true;
    }
    /** 当前 update.exe 的路径（由启动器通过 SPC_UPDATER_EXE 传入）。 */
    private Path currentExePath() {
        String fromEnv = Launcher.selfExeEnv();
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv).toAbsolutePath();
        }
        Path candidate = client.resolve("update.exe");
        return Files.isRegularFile(candidate) ? candidate.toAbsolutePath() : null;
    }

    /** 需要等待退出的进程：原生 exe 模式等当前进程；旧启动器模式等父进程。 */
    private static long launcherPid() {
        String fromEnv = Launcher.selfExeEnv();
        if (fromEnv == null || fromEnv.isBlank()) {
            return 0L;
        }
        if (System.getenv("SPC_UPDATER_EXE") == null) {
            return ProcessHandle.current().pid();
        }
        return ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(0L);
    }

    private static String formatSize(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ------------------------------------------------------------ 3 +W / 4 -W

    private void unlockConfig() {
        Ui.title("+W 解锁配置文件");
        if (!pcl.exists()) {
            Ui.println("  " + Ui.red("未找到 PCL/config.v1.yml。"));
            Ui.println("  " + Ui.gray("可以先执行 [4] -W 回锁，它会从模板生成配置文件。"));
            returnHome();
            return;
        }
        pcl.unlock();
        Ui.println("  已解锁：" + Ui.green("PCL\\config.v1.yml") + "（只读已移除）");
        Ui.println();
        Ui.println("  现在可以启动 PCL 修改客户端设置（Java / 内存 / 模组开关等）。");
        Ui.println("  PCL 会改写该文件里的 Java 路径，属于正常现象。");
        Ui.println();
        Ui.println("  " + Ui.yellow("修改完成后，请回到主菜单执行 [4] -W 回锁配置文件。"));
        returnHome();
    }

    private void relockConfig() {
        Ui.title("-W 回锁配置文件");
        if (!pcl.exists()) {
            boolean created = pcl.ensureFromTemplate();
            if (!created) {
                Ui.println("  " + Ui.red("未找到 PCL/config.v1.yml，也没有 PCL/config.v1.yml.template。"));
                returnHome();
                return;
            }
            Ui.println("  " + Ui.gray("已从模板生成 PCL/config.v1.yml。"));
        }
        pcl.relock();
        String javaSelect = pcl.javaSelectLine();
        Ui.println("  已重新固定 Java 路径：");
        Ui.println("    " + Ui.gray(javaSelect.isEmpty() ? "（未找到 VersionArgumentJavaSelect 行）" : javaSelect));
        Ui.println("  已回锁：" + Ui.green("PCL\\config.v1.yml") + "（只读）");
        returnHome();
    }
}
