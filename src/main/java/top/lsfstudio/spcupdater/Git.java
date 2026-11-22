package top.lsfstudio.spcupdater;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** git 命令封装。所有输出按 UTF-8 读取（git 输出即 UTF-8）。 */
public final class Git {

    public record Result(int code, List<String> lines) {
        public boolean ok() {
            return code == 0;
        }

        public String first() {
            return lines.isEmpty() ? "" : lines.get(0).trim();
        }

        public String text() {
            return String.join("\n", lines);
        }
    }

    private Git() {
    }

    public static Result run(Path dir, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(dir.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_PAGER", "cat");
        applyIdentity(builder);
        try {
            Process process = builder.start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            return new Result(process.waitFor(), lines);
        } catch (IOException e) {
            return new Result(127, List.of("无法执行 git：" + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(130, List.of("git 被中断"));
        }
    }

    /** 带超时的运行：超时后强制结束进程，避免网络卡住界面。 */
    public static Result runTimed(Path dir, long timeoutSeconds, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(dir.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_PAGER", "cat");
        applyIdentity(builder);
        try {
            Process process = builder.start();
            List<String> lines = Collections.synchronizedList(new ArrayList<>());
            Thread reader = new Thread(() -> {
                try (BufferedReader readerStream = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = readerStream.readLine()) != null) {
                        lines.add(line);
                    }
                } catch (IOException ignored) {
                    // 进程被结束时正常
                }
            }, "git-output-reader");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return new Result(124, List.of("git 超时（" + timeoutSeconds + " 秒）"));
            }
            reader.join(2000);
            return new Result(process.exitValue(), new ArrayList<>(lines));
        } catch (IOException e) {
            return new Result(127, List.of("无法执行 git：" + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(130, List.of("git 被中断"));
        }
    }

    public static boolean available() {
        return run(Path.of("."), "--version").ok();
    }

    public static boolean isRepo(Path dir) {
        return run(dir, "rev-parse", "--git-dir").ok();
    }

    public static String upstream(Path dir) {
        Result result = run(dir, "rev-parse", "--abbrev-ref", "@{u}");
        if (result.ok() && !result.first().isBlank()) {
            return result.first();
        }
        return "origin/main";
    }

    /** @return [ahead, behind] */
    public static int[] aheadBehind(Path dir, String upstream) {
        Result result = run(dir, "rev-list", "--left-right", "--count", "HEAD..." + upstream);
        if (!result.ok() || result.lines().isEmpty()) {
            return new int[]{0, 0};
        }
        String[] parts = result.first().trim().split("\\s+");
        if (parts.length < 2) {
            return new int[]{0, 0};
        }
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException e) {
            return new int[]{0, 0};
        }
    }

    public static String shortHash(Path dir, String rev) {
        Result result = run(dir, "rev-parse", "--short", rev);
        return result.ok() && !result.first().isBlank() ? result.first() : "???????";
    }

    public static String subject(Path dir, String rev) {
        Result result = run(dir, "log", "-1", "--format=%s", rev);
        return result.ok() ? result.first() : "";
    }

    public static String commitLine(Path dir, String rev) {
        Result result = run(dir, "log", "-1", "--format=%h  %ci  %an", rev);
        return result.ok() ? result.first() : "";
    }

    public static List<String> logStat(Path dir, String rev, int maxLines) {
        Result result = run(dir, "log", "-1", "--stat", "--format=", rev);
        if (!result.ok()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : result.lines()) {
            if (line.isBlank()) {
                continue;
            }
            if (out.size() >= maxLines) {
                out.add("...");
                break;
            }
            out.add(line.strip());
        }
        return out;
    }

    private static volatile String identityNameOverride;
    private static volatile String identityEmailOverride;

    /**
     * 用户没有配置 git 身份时，用内置身份兜底（Player / no-reply@lsfstudio.top），
     * 避免 git pull 需要合并时报 "Committer identity unknown"。
     */
    public static void ensureIdentity(Path dir) {
        try {
            String name = run(dir, "config", "--get", "user.name").first();
            String email = run(dir, "config", "--get", "user.email").first();
            identityNameOverride = name.isBlank() ? "Player" : null;
            identityEmailOverride = email.isBlank() ? "no-reply@lsfstudio.top" : null;
        } catch (Exception e) {
            identityNameOverride = "Player";
            identityEmailOverride = "no-reply@lsfstudio.top";
        }
    }

    private static void applyIdentity(ProcessBuilder builder) {
        if (identityNameOverride != null) {
            builder.environment().put("GIT_AUTHOR_NAME", identityNameOverride);
            builder.environment().put("GIT_COMMITTER_NAME", identityNameOverride);
        }
        if (identityEmailOverride != null) {
            builder.environment().put("GIT_AUTHOR_EMAIL", identityEmailOverride);
            builder.environment().put("GIT_COMMITTER_EMAIL", identityEmailOverride);
        }
    }
    public record FileChange(String type, String path, String oldPath) {
    }

    /** 某个提交的改动文件（含 A/M/D/R 类型）。 */
    public static List<FileChange> commitChanges(Path dir, String rev) {
        Result result = run(dir, "-c", "core.quotepath=false", "show", "--name-status", "--format=", rev);
        List<FileChange> changes = new ArrayList<>();
        if (!result.ok()) {
            return changes;
        }
        for (String line : result.lines()) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length < 2) {
                continue;
            }
            String type = parts[0];
            if (parts.length >= 3) {
                changes.add(new FileChange(type, parts[2], parts[1]));
            } else {
                changes.add(new FileChange(type, parts[1], null));
            }
        }
        return changes;
    }

    /** 本次更新会从上游带下来的文件路径（不含重命名的旧路径拆解）。 */
    public static List<String> incomingPaths(Path dir, String upstream) {
        Result result = run(dir, "-c", "core.quotepath=false", "diff", "--name-only", "--no-renames",
                "HEAD..." + upstream);
        if (!result.ok()) {
            return List.of();
        }
        return result.lines().stream().filter(line -> !line.isBlank()).toList();
    }

    /**
     * 判断某个「上游会改到的文件」是否和本地有冲突（本地会丢东西）。
     * - 已跟踪：git status 有改动即冲突；
     * - 未跟踪（含被 .gitignore 忽略）：比较本地内容与上游内容，不同才算冲突；
     * - 上游没有该文件（删除）或本地不存在：不算冲突。
     */
    public static boolean conflictsWithUpdate(Path dir, String upstream, String relativePath) {
        boolean tracked = run(dir, "ls-files", "--error-unmatch", "--", relativePath).ok();
        if (tracked) {
            Result status = run(dir, "status", "--porcelain", "--", relativePath);
            return status.ok() && status.lines().stream().anyMatch(line -> !line.isBlank());
        }
        Path local = dir.resolve(relativePath);
        if (!Files.exists(local)) {
            return false;
        }
        Result upstreamBlob = run(dir, "rev-parse", upstream + ":" + relativePath);
        if (!upstreamBlob.ok() || upstreamBlob.first().isBlank()) {
            return false;
        }
        Result localBlob = run(dir, "hash-object", "--", relativePath);
        return !localBlob.ok() || !localBlob.first().equalsIgnoreCase(upstreamBlob.first());
    }
    /** 丢弃某个文件的本地改动：已跟踪的还原到 HEAD，未跟踪的删除。 */
    public static boolean prepareForPull(Path dir, String relativePath) {
        boolean tracked = run(dir, "ls-files", "--error-unmatch", "--", relativePath).ok();
        if (tracked) {
            run(dir, "checkout", "--", relativePath);
            return run(dir, "diff", "--quiet", "--", relativePath).ok();
        }
        Path file = dir.resolve(relativePath);
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 删除失败交给调用方提示
        }
        return !Files.exists(file);
    }
    public static List<String> status(Path dir) {
        Result result = run(dir, "status", "--porcelain");
        if (!result.ok()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : result.lines()) {
            if (!line.isBlank()) {
                out.add(line);
            }
        }
        return out;
    }

    /** 从 git pull 的输出里解析出会被覆盖的冲突文件。 */
    public static List<String> conflicts(List<String> pullOutput) {
        List<String> files = new ArrayList<>();
        boolean listing = false;
        for (String line : pullOutput) {
            if (line.matches(".*would be overwritten by (merge|checkout|pull).*")) {
                listing = true;
                continue;
            }
            if (line.contains("unable to unlink old '")) {
                int start = line.indexOf("unable to unlink old '") + "unable to unlink old '".length();
                int end = line.indexOf('\'', start);
                if (end > start) {
                    files.add(line.substring(start, end));
                }
                listing = false;
                continue;
            }
            if (listing) {
                if (line.isBlank()) {
                    listing = false;
                } else if (line.startsWith("\t") || line.startsWith(" ")) {
                    files.add(line.trim());
                } else {
                    listing = false;
                }
            }
        }
        return files.stream().distinct().toList();
    }

    /** 丢弃某个文件的本地改动：先还原（已跟踪文件），再删除，保证 pull 可以重写。 */
    public static boolean discard(Path dir, String relativePath) {
        boolean tracked = run(dir, "ls-files", "--error-unmatch", "--", relativePath).ok();
        if (tracked) {
            run(dir, "checkout", "--", relativePath);
        }
        Path file = dir.resolve(relativePath);
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 删不掉就让调用方报错
        }
        return !Files.exists(file);
    }
}
