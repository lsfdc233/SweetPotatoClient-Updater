package top.lsfstudio.spcupdater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.Charset;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * 原生 exe（GraalVM native-image）模式下的平台能力，替代原先的 C# 启动器。
 */
public final class Launcher {

    private Launcher() {
    }

    /** 当前更新器 exe 的路径：优先 SPC_UPDATER_EXE（兼容旧启动器），否则用自身进程。 */
    public static Path selfExe() {
        String env = System.getenv("SPC_UPDATER_EXE");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath();
        }
        return ProcessHandle.current().info().command().map(Path::of).orElse(null);
    }

    /** 字符串形式，供旧代码沿用 SPC_UPDATER_EXE 的判断方式。 */
    public static String selfExeEnv() {
        Path self = selfExe();
        return self == null ? null : self.toString();
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }


    /** 启动器已经用 Win32 SetConsoleMode 打开 VT；这里无需再起进程。 */
    public static void enableAnsi() {
        // no-op：避免每次启动拉起 powershell/cmd 触发杀软告警
    }
    /** 直接读 JVM 的控制台编码属性，不启动 cmd/chcp。 */
    public static Charset consoleCharset() {
        if (!isWindows()) {
            return Charset.defaultCharset();
        }
        String encoding = System.getProperty("stdout.encoding");
        if (encoding == null || encoding.isBlank()) {
            encoding = System.getProperty("native.encoding");
        }
        if (encoding != null && !encoding.isBlank()) {
            try {
                return Charset.forName(encoding);
            } catch (Exception ignored) {
                // 回退 GBK
            }
        }
        try {
            return Charset.forName("GBK");
        } catch (Exception ignored) {
            return Charset.defaultCharset();
        }
    }

    /** 清屏：直接用 VT 转义（启动器已开启 VT），不启动 cmd/cls。 */
    public static void clearConsole() {
        System.out.print("\u001b[3J\u001b[2J\u001b[H");
        System.out.flush();
    }
    /** 等待指定 PID 退出；0/无效则直接返回。 */
    public static void waitForPid(String pidText) {
        long pid;
        try {
            pid = Long.parseLong(pidText.trim());
        } catch (Exception e) {
            return;
        }
        if (pid <= 0 || pid == ProcessHandle.current().pid()) {
            return;
        }
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                return;
            }
        }
    }

    private static void copyWithRetry(Path source, Path target) throws IOException {
        long deadline = System.currentTimeMillis() + 15_000;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (Exception e) {
                last = e;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException(ie);
                }
            }
        }
        throw last == null ? new IOException("复制失败") : (last instanceof IOException io ? io : new IOException(last));
    }

    private static void startTarget(Path target) {
        try {
            ProcessBuilder builder = new ProcessBuilder(target.toString());
            Path dir = target.getParent();
            if (dir != null) {
                builder.directory(dir.toFile());
            }
            builder.inheritIO();
            builder.start();
        } catch (Exception ignored) {
            // 启动失败时无法输出（已是收尾阶段）
        }
    }

    /** --apply-update <source> <target> <pid>：等旧进程退出后覆盖并重启。 */
    public static int applyUpdate(String sourcePath, String targetPath, String pidText) {
        waitForPid(pidText);
        Path source = Path.of(sourcePath);
        Path target = Path.of(targetPath);
        if (!Files.isRegularFile(source)) {
            System.err.println("[SweetPotatoClient-Updater] 待替换文件不存在：" + sourcePath);
            return 2;
        }
        try {
            copyWithRetry(source, target);
            Files.deleteIfExists(source);
        } catch (Exception e) {
            System.err.println("[SweetPotatoClient-Updater] 替换失败：" + e.getMessage());
            return 4;
        }
        startTarget(target);
        return 0;
    }

    /** --apply-update-pull <source> <target> <pid> <repo>：先 git pull，再替换并重启。 */
    public static int applyUpdatePull(String sourcePath, String targetPath, String pidText, String repoDir) {
        waitForPid(pidText);
        try {
            System.out.println("正在执行 git pull ...");
            ProcessBuilder git = new ProcessBuilder(Git.executable(), "-c", "pull.rebase=false", "pull", "--no-rebase", "--no-edit");
            git.directory(Path.of(repoDir).toFile());
            git.inheritIO();
            git.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process process = git.start();
            if (process.waitFor() != 0) {
                System.err.println("[SweetPotatoClient-Updater] git pull 返回 " + process.exitValue() + "，可稍后在更新器里重试。");
            }
        } catch (Exception e) {
            System.err.println("[SweetPotatoClient-Updater] git pull 失败：" + e.getMessage());
        }
        return applyUpdate(sourcePath, targetPath, pidText);
    }
}
