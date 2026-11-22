package top.lsfstudio.spcupdater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
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

    private static String[] launchArgs = new String[0];

    private final Path client;
    private final PclConfig pcl;
    private final CountDownLatch cloudReady = new CountDownLatch(1);

    private volatile ReleaseClient.Release latestRelease;
    private volatile String latestReleaseError = "";

    private Main(Path client) {
        this.client = client;
        this.pcl = new PclConfig(client);
    }

    public static void main(String[] args) {
        boolean wantColor = true;
        boolean forceColor = false;
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
                    }
                }
            }
        }
        launchArgs = Arrays.copyOf(args, args.length);
        Ui.init(wantColor, forceColor);
        new Main(resolveClient(clientArg)).run();
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
        System.out.println("  --help            显示帮助");
    }

    /** 优先找 remote 指向 SweetPotatoClient（不是 Updater）的仓库。 */
    private static Path resolveClient(Path explicit) {
        List<Path> primary = new ArrayList<>();
        if (explicit != null) {
            primary.add(explicit.toAbsolutePath().normalize());
        }
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
        String value = System.getenv("SPC_UPDATER_EXE");
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
            Ui.println(Ui.red("  未找到 git，请先安装 Git for Windows 并加入 PATH。"));
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
        startCloudCheck();
        awaitCloudCheck(Duration.ofSeconds(6));
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

    /** 首屏绘制前最多等一会儿云端版本，避免一直显示「查询中...」。 */
    private void awaitCloudCheck(Duration timeout) {
        try {
            cloudReady.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String cloudVersionText() {
        ReleaseClient.Release release = latestRelease;
        if (release != null) {
            return release.tag();
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
        Ui.println("  更新器版本：" + Ui.cyan(UpdaterInfo.VERSION));
        Ui.println("  云端版本：" + (newer ? Ui.yellow(cloudVersionText() + "（有新版本）") : Ui.gray(cloudVersionText())));
        Ui.println("  更新器仓库：" + Ui.gray(UpdaterInfo.UPDATER_REPO));
        Ui.println("  客户端仓库：" + Ui.gray(UpdaterInfo.CLIENT_REPO));
        if (newer) {
            Ui.println("  " + Ui.yellow("提示：发现更新器新版本 " + latestRelease.tag() + "，按 [2] 更新"));
        }
        Ui.println();
        Ui.println("  客户端：" + Ui.gray(client.toString()));
        String upstream = Git.upstream(client);
        int[] aheadBehind = Git.aheadBehind(client, upstream);
        Ui.println("  本地提交：" + Git.shortHash(client, "HEAD") + "  " + Ui.gray(Git.subject(client, "HEAD")));
        Ui.println("  上游提交：" + Git.shortHash(client, upstream) + "  " + Ui.gray(upstream));
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
                + (newer ? Ui.yellow("  ← 新版本 " + latestRelease.tag()) : ""));
        Ui.println("  " + Ui.cyan("[3]") + " +W 解锁配置文件（PCL 修改设置前）");
        Ui.println("  " + Ui.cyan("[4]") + " -W 回锁配置文件（内置 JDK 路径 + 只读）");
        Ui.println("  " + Ui.cyan("[5]") + " 退出");
        Ui.println();
    }

    // ------------------------------------------------------------ 1 更新客户端

    private boolean updateClient() {
        Ui.clear();
        Ui.title("更新 SweetPotatoClient");
        Ui.println("  正在从上游获取最新信息...");
        Git.Result fetch = Git.run(client, "fetch", "--prune");
        if (!fetch.ok()) {
            showOutput(fetch, "获取上游失败（网络或权限问题）：");
            Ui.pause("按 Enter 返回主菜单...");
            return false;
        }
        String upstream = Git.upstream(client);
        int[] aheadBehind = Git.aheadBehind(client, upstream);
        Ui.println("  上游：" + Ui.gray(upstream));
        Ui.println();
        Ui.println("  上游最近提交：");
        Ui.println("    " + Git.commitLine(client, upstream));
        Ui.println("    " + Ui.yellow(Git.subject(client, upstream)));
        List<String> stat = Git.logStat(client, upstream, MAX_STAT_LINES);
        if (!stat.isEmpty()) {
            Ui.println();
            Ui.println("  文件变更：");
            for (String line : stat) {
                Ui.println("    " + Ui.gray(line));
            }
        }
        Ui.println();
        if (aheadBehind[1] == 0) {
            Ui.println("  " + Ui.green("已经是最新版本，无需更新。"));
            if (aheadBehind[0] > 0) {
                Ui.println("  本地领先上游 " + aheadBehind[0] + " 个提交。");
            }
            Ui.pause("按 Enter 返回主菜单...");
            return false;
        }
        Ui.println("  本地落后上游 " + aheadBehind[1] + " 个提交。");
        List<String> changes = Git.status(client);
        if (!changes.isEmpty()) {
            Ui.println();
            Ui.println("  " + Ui.red("警告：更新会覆盖/丢弃以下本地已修改文件（共 " + changes.size() + " 项）："));
            int shown = Math.min(changes.size(), MAX_CHANGES_SHOWN);
            for (int i = 0; i < shown; i++) {
                String line = changes.get(i);
                if (line.length() > 100) {
                    line = line.substring(0, 100) + "...";
                }
                Ui.println("    " + Ui.gray(line));
            }
            if (changes.size() > shown) {
                Ui.println("    " + Ui.gray("... 其余 " + (changes.size() - shown) + " 项省略"));
            }
        }
        Ui.println();
        if (!Ui.confirm("确认更新？(y/n)")) {
            Ui.println("  已取消。");
            Ui.pause("按 Enter 返回主菜单...");
            return false;
        }

        pcl.unlock();
        Git.Result pull = Git.run(client, "pull");
        if (!pull.ok()) {
            Ui.println();
            Ui.println("  " + Ui.yellow("git pull 失败，尝试清理冲突文件后重试..."));
            List<String> conflicts = Git.conflicts(pull.lines());
            if (conflicts.isEmpty()) {
                showOutput(pull, "无法自动判断冲突文件，请手动处理：");
                pcl.relock();
                Ui.pause("按 Enter 返回主菜单...");
                return false;
            }
            for (String file : conflicts) {
                boolean ok = Git.discard(client, file);
                Ui.println("    " + (ok ? Ui.gray("已丢弃本地改动：" + file) : Ui.red("无法删除：" + file)));
            }
            pull = Git.run(client, "pull");
        }
        if (!pull.ok()) {
            showOutput(pull, "更新失败，请手动处理：");
            pcl.relock();
            Ui.pause("按 Enter 返回主菜单...");
            return false;
        }

        pcl.relock();
        Ui.println();
        Ui.println("  " + Ui.green("更新完成！"));
        Ui.println("  当前提交：" + Git.shortHash(client, "HEAD") + "  " + Ui.gray(Git.subject(client, "HEAD")));
        Ui.println("  配置文件已重新回锁（只读）。");
        Ui.println();
        if (!canRestartUpdater()) {
            Ui.pause("按 Enter 返回主菜单...");
            return false;
        }
        Ui.println("  正在自动重启更新器...");
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (restartUpdater()) {
            return true;
        }
        Ui.pause("按 Enter 返回主菜单...");
        return false;
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
        Ui.clear();
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
            Ui.pause("按 Enter 返回主菜单...");
            return false;
        }
        Ui.println("  最新版本：" + Ui.cyan(release.tag()));
        if (!VersionUtil.isNewer(release.tag(), UpdaterInfo.VERSION)) {
            Ui.println("  " + Ui.green("已经是最新版本，无需更新。"));
            Ui.pause("按 Enter 返回主菜单...");
            return false;
        }

        String expectedName = "update-" + release.tag() + ".exe";
        ReleaseClient.Asset exeAsset = release.asset(expectedName).orElse(null);
        if (exeAsset == null) {
            exeAsset = release.firstUpdateExe().orElse(null);
        }
        if (exeAsset == null) {
            Ui.println("  " + Ui.red("该 Release 没有可用的 update-*.exe 附件。"));
            Ui.pause("按 Enter 返回主菜单...");
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
            Ui.pause("按 Enter 返回主菜单...");
            return false;
        }
        String exeEnv = System.getenv("SPC_UPDATER_EXE");
        if (exeEnv == null || exeEnv.isBlank()) {
            Ui.println("  " + Ui.red("自更新需要由 update.exe 启动（当前不是）。"));
            Ui.pause("按 Enter 返回主菜单...");
            return false;
        }
        Path exe = Path.of(exeEnv).toAbsolutePath();
        Ui.println();
        if (!Ui.confirm("确认更新？(y/n)")) {
            Ui.println("  已取消。");
            Ui.pause("按 Enter 返回主菜单...");
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
            Ui.pause("按 Enter 返回主菜单...");
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
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.start();
        } catch (Exception e) {
            Ui.println("  " + Ui.red("启动替换进程失败：" + e.getMessage()));
            Ui.println("  新版本已下载到：" + Ui.gray(temp.toString()));
            Ui.pause("按 Enter 返回主菜单...");
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

    private boolean canRestartUpdater() {
        String exe = System.getenv("SPC_UPDATER_EXE");
        return exe != null && !exe.isBlank();
    }

    /** 重新拉起更新器（保留原始启动参数）。 */
    private boolean restartUpdater() {
        try {
            List<String> command = new ArrayList<>();
            command.add(System.getenv("SPC_UPDATER_EXE"));
            command.addAll(Arrays.asList(launchArgs));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(client.toFile());
            builder.start();
            return true;
        } catch (Exception e) {
            Ui.println("  " + Ui.red("重启失败：" + e.getMessage()));
            return false;
        }
    }
    /** 当前 update.exe 的路径（由启动器通过 SPC_UPDATER_EXE 传入）。 */
    private Path currentExePath() {
        String fromEnv = System.getenv("SPC_UPDATER_EXE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv).toAbsolutePath();
        }
        Path candidate = client.resolve("update.exe");
        return Files.isRegularFile(candidate) ? candidate.toAbsolutePath() : null;
    }

    /** 启动器进程 PID；直接用 java -jar 运行时为 0（不需要等待旧启动器）。 */
    private static long launcherPid() {
        String fromEnv = System.getenv("SPC_UPDATER_EXE");
        if (fromEnv == null || fromEnv.isBlank()) {
            return 0L;
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
        Ui.clear();
        Ui.title("+W 解锁配置文件");
        if (!pcl.exists()) {
            Ui.println("  " + Ui.red("未找到 PCL/config.v1.yml。"));
            Ui.println("  " + Ui.gray("可以先执行 [4] -W 回锁，它会从模板生成配置文件。"));
            Ui.pause("按 Enter 返回主菜单...");
            return;
        }
        pcl.unlock();
        Ui.println("  已解锁：" + Ui.green("PCL\\config.v1.yml") + "（只读已移除）");
        Ui.println();
        Ui.println("  现在可以启动 PCL 修改客户端设置（Java / 内存 / 模组开关等）。");
        Ui.println("  PCL 会改写该文件里的 Java 路径，属于正常现象。");
        Ui.println();
        Ui.println("  " + Ui.yellow("修改完成后，请回到主菜单执行 [4] -W 回锁配置文件。"));
        Ui.pause("按 Enter 返回主菜单...");
    }

    private void relockConfig() {
        Ui.clear();
        Ui.title("-W 回锁配置文件");
        if (!pcl.exists()) {
            boolean created = pcl.ensureFromTemplate();
            if (!created) {
                Ui.println("  " + Ui.red("未找到 PCL/config.v1.yml，也没有 PCL/config.v1.yml.template。"));
                Ui.pause("按 Enter 返回主菜单...");
                return;
            }
            Ui.println("  " + Ui.gray("已从模板生成 PCL/config.v1.yml。"));
        }
        pcl.relock();
        String javaSelect = pcl.javaSelectLine();
        Ui.println("  已重新固定 Java 路径：");
        Ui.println("    " + Ui.gray(javaSelect.isEmpty() ? "（未找到 VersionArgumentJavaSelect 行）" : javaSelect));
        Ui.println("  已回锁：" + Ui.green("PCL\\config.v1.yml") + "（只读）");
        Ui.pause("按 Enter 返回主菜单...");
    }
}
