package top.lsfstudio.spcupdater;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** SweetPotatoClient 更新器（控制台 TUI）。 */
public final class Main {
    private static final int MAX_STAT_LINES = 20;
    private static final int MAX_CHANGES_SHOWN = 15;

    private final Path client;
    private final PclConfig pcl;

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
        LinkedHashSet<Path> seeds = new LinkedHashSet<>();
        if (explicit != null) {
            seeds.add(explicit.toAbsolutePath().normalize());
        }
        seeds.add(Path.of("").toAbsolutePath().normalize());
        Path exeDir = exeDir();
        if (exeDir != null) {
            seeds.add(exeDir);
            if (exeDir.getParent() != null) {
                seeds.add(exeDir.getParent());
            }
        }
        for (Path seed : seeds) {
            Path candidate = walkUpForClientRepo(seed);
            if (candidate != null) {
                return candidate;
            }
        }
        for (Path seed : seeds) {
            Path current = seed;
            while (current != null) {
                if (Files.isDirectory(current.resolve(".git"))) {
                    return current;
                }
                current = current.getParent();
            }
        }
        return seeds.iterator().next();
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
        while (true) {
            drawMenu();
            String choice = Ui.readLine("请选择 [1-5]");
            if (choice == null) {
                return;
            }
            switch (choice.trim()) {
                case "1" -> updateClient();
                case "2" -> updateUpdater();
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

    private void drawMenu() {
        Ui.clear();
        Ui.banner();
        Ui.println(Ui.gray("  made by " + UpdaterInfo.AUTHOR));
        Ui.println();
        Ui.println("  更新器版本：" + Ui.cyan(UpdaterInfo.VERSION));
        Ui.println("  云端版本：" + Ui.gray(UpdaterInfo.cloudVersion()));
        Ui.println("  更新器仓库：" + Ui.gray(UpdaterInfo.UPDATER_REPO));
        Ui.println("  客户端仓库：" + Ui.gray(UpdaterInfo.CLIENT_REPO));
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
        Ui.println("  " + Ui.cyan("[2]") + " 更新 SweetPotatoClient-Updater");
        Ui.println("  " + Ui.cyan("[3]") + " +W 解锁配置文件（PCL 修改设置前）");
        Ui.println("  " + Ui.cyan("[4]") + " -W 回锁配置文件（内置 JDK 路径 + 只读）");
        Ui.println("  " + Ui.cyan("[5]") + " 退出");
        Ui.println();
    }

    // ------------------------------------------------------------ 1 更新客户端

    private void updateClient() {
        Ui.clear();
        Ui.title("更新 SweetPotatoClient");
        Ui.println("  正在从上游获取最新信息...");
        Git.Result fetch = Git.run(client, "fetch", "--prune");
        if (!fetch.ok()) {
            showOutput(fetch, "获取上游失败（网络或权限问题）：");
            Ui.pause("按 Enter 返回主菜单...");
            return;
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
            return;
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
            return;
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
                return;
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
            return;
        }

        pcl.relock();
        Ui.println();
        Ui.println("  " + Ui.green("更新完成！"));
        Ui.println("  当前提交：" + Git.shortHash(client, "HEAD") + "  " + Ui.gray(Git.subject(client, "HEAD")));
        Ui.println("  配置文件已重新回锁（只读）。");
        Ui.pause("按 Enter 返回主菜单...");
    }

    private void showOutput(Git.Result result, String title) {
        Ui.println("  " + Ui.red(title));
        for (String line : result.lines()) {
            Ui.println("    " + Ui.gray(line));
        }
    }

    // ------------------------------------------------------------ 2 更新更新器（占位）

    private void updateUpdater() {
        Ui.clear();
        Ui.title("更新 SweetPotatoClient-Updater");
        Ui.println("  该功能等待更新器发布首个 Release 后再实现（下载地址与版本格式待确认）。");
        Ui.println();
        Ui.println("  当前更新器版本：" + Ui.cyan(UpdaterInfo.VERSION));
        Ui.println("  云端版本：" + Ui.gray(UpdaterInfo.cloudVersion()));
        Ui.println("  更新器仓库：" + Ui.gray(UpdaterInfo.UPDATER_REPO));
        Ui.println();
        Ui.println("  " + Ui.gray("TODO：首个 Release 发布后补全：查询最新 Release → 校验版本 → 下载 → 替换 update.exe。"));
        Ui.pause("按 Enter 返回主菜单...");
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
