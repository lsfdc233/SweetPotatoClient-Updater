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

    /** 经 PowerShell 调用 Win32 SetConsoleMode，给当前控制台打开 VT 转义（颜色/清屏）。 */
    public static void enableAnsi() {
        if (!isWindows()) {
            return;
        }
        String script = String.join("\n",
                "$k = Add-Type -MemberDefinition @'",
                "[DllImport(\"kernel32.dll\")] public static extern IntPtr GetStdHandle(int n);",
                "[DllImport(\"kernel32.dll\")] public static extern bool GetConsoleMode(IntPtr h, out uint m);",
                "[DllImport(\"kernel32.dll\")] public static extern bool SetConsoleMode(IntPtr h, uint m);",
                "'@ -Name K -PassThru",
                "$h = $k::GetStdHandle(-11)",
                "$m = 0",
                "[void]$k::GetConsoleMode($h, [ref]$m)",
                "[void]$k::SetConsoleMode($h, $m -bor 4)");
        try {
            Path scriptFile = Path.of(System.getProperty("java.io.tmpdir"),
                    "sweetpotato-updater-enable-ansi-" + ProcessHandle.current().pid() + ".ps1");
            Files.writeString(scriptFile, script);
            ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", scriptFile.toString());
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process process = builder.start();
            process.waitFor(5, TimeUnit.SECONDS);
            Files.deleteIfExists(scriptFile);
        } catch (Exception ignored) {
            // 非控制台/无 PowerShell：忽略
        }
    }

    /** 当前控制台代码页对应的字符集（Windows 控制台默认不是 UTF-8）。 */
    public static Charset consoleCharset() {
        if (!isWindows()) {
            return Charset.defaultCharset();
        }
        try {
            Process process = new ProcessBuilder("cmd.exe", "/c", "chcp").redirectErrorStream(true).start();
            byte[] bytes = process.getInputStream().readAllBytes();
            process.waitFor(3, TimeUnit.SECONDS);
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{3,5})")
                    .matcher(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1));
            if (matcher.find()) {
                int codePage = Integer.parseInt(matcher.group(1));
                return switch (codePage) {
                    case 65001 -> java.nio.charset.StandardCharsets.UTF_8;
                    case 54936 -> Charset.forName("GB18030");
                    default -> Charset.forName("GBK");
                };
            }
        } catch (Exception ignored) {
            // 回退 GBK
        }
        return Charset.forName("GBK");
    }

    /** 清屏：Windows 下用 cls（含回滚缓冲），其它平台回退 ANSI。 */
    public static void clearConsole() {
        if (isWindows()) {
            try {
                ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", "cls");
                builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                builder.redirectError(ProcessBuilder.Redirect.INHERIT);
                Process process = builder.start();
                if (process.waitFor(3, TimeUnit.SECONDS)) {
                    return;
                }
                process.destroyForcibly();
            } catch (Exception ignored) {
                // 回退 ANSI
            }
        }
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
            ProcessBuilder git = new ProcessBuilder("git", "-c", "pull.rebase=false", "pull", "--no-rebase", "--no-edit");
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
