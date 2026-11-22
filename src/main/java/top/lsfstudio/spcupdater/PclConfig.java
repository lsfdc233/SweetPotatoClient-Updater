package top.lsfstudio.spcupdater;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** PCL 实例配置（PCL/config.v1.yml）的只读锁与 Java 路径修复。 */
public final class PclConfig {
    private static final Pattern JAVA_PATH = Pattern.compile("\"JavaExePath\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JAVA_SELECT_LINE = Pattern.compile("(?m)^VersionArgumentJavaSelect:[^\\r\\n]*");
    private static final String JAVA_SELECT_PREFIX = "VersionArgumentJavaSelect:";

    private final Path root;

    public PclConfig(Path clientRoot) {
        this.root = clientRoot;
    }

    public Path config() {
        return root.resolve("PCL").resolve("config.v1.yml");
    }

    public Path template() {
        return root.resolve("PCL").resolve("config.v1.yml.template");
    }

    public boolean exists() {
        return Files.isRegularFile(config());
    }

    public boolean isReadOnly() {
        Path file = config();
        if (!Files.exists(file)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(Files.getAttribute(file, "dos:readonly"));
        } catch (Exception e) {
            return false;
        }
    }

    public void setReadOnly(boolean readOnly) {
        Path file = config();
        if (!Files.exists(file)) {
            return;
        }
        try {
            DosFileAttributeView view = Files.getFileAttributeView(file, DosFileAttributeView.class);
            if (view != null) {
                view.setReadOnly(readOnly);
                return;
            }
        } catch (Exception ignored) {
            // 退回 attrib
        }
        try {
            new ProcessBuilder("attrib", readOnly ? "+R" : "-R", file.toString()).start().waitFor();
        } catch (Exception ignored) {
            // 忽略：调用方会通过 isReadOnly 检查
        }
    }

    /** 配置缺失时从模板生成；模板与配置内容不同时用模板覆盖。 */
    public boolean ensureFromTemplate() {
        Path tpl = template();
        if (!Files.isRegularFile(tpl)) {
            return false;
        }
        Path file = config();
        try {
            if (Files.exists(file)) {
                if (Arrays.equals(Files.readAllBytes(tpl), Files.readAllBytes(file))) {
                    return true;
                }
                setReadOnly(false);
            }
            Files.copy(tpl, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 内置 JDK 的相对路径，如 ./jdk-25.0.5.0.3/bin/java.exe；找不到返回 null。 */
    public String pinnedJava() {
        String fromTemplate = javaPathFrom(template());
        if (fromTemplate != null) {
            String relative = toRelative(fromTemplate);
            if (relative != null) {
                return relative;
            }
        }
        Git.Result head = Git.run(root, "show", "HEAD:PCL/config.v1.yml");
        if (head.ok()) {
            String fromHead = javaPathFromText(head.text());
            if (fromHead != null) {
                String relative = toRelative(fromHead);
                if (relative != null) {
                    return relative;
                }
            }
        }
        try (Stream<Path> stream = Files.list(root)) {
            List<Path> jdks = stream
                    .filter(Files::isDirectory)
                    .filter(this::looksLikeBundledJdk)
                    .filter(dir -> Files.isRegularFile(dir.resolve("bin").resolve("java.exe")))
                    .sorted(Comparator
                            .comparingInt((Path dir) -> dir.getFileName().toString().length())
                            .thenComparing(dir -> dir.getFileName().toString()))
                    .toList();
            if (!jdks.isEmpty()) {
                return "./" + root.relativize(jdks.get(0)).toString().replace('\\', '/') + "/bin/java.exe";
            }
        } catch (IOException ignored) {
            // 无内置 JDK
        }
        return null;
    }

    /** 把 VersionArgumentJavaSelect 行改成固定的内置 JDK 相对路径。 */
    public boolean repair() {
        if (!exists()) {
            return false;
        }
        String pinned = pinnedJava();
        if (pinned == null) {
            return false;
        }
        try {
            String text = Files.readString(config(), StandardCharsets.UTF_8);
            String replacement = JAVA_SELECT_PREFIX
                    + " '{\"kind\":\"exist\",\"JavaExePath\":\"" + pinned + "\"}'";
            String updated = JAVA_SELECT_LINE.matcher(text)
                    .replaceFirst(Matcher.quoteReplacement(replacement));
            if (updated.equals(text)) {
                return false;
            }
            setReadOnly(false);
            Files.writeString(config(), updated, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** +W：解除只读，交给 PCL 修改。 */
    public void unlock() {
        setReadOnly(false);
    }

    /** -W：修复 Java 路径并恢复只读。 */
    public void relock() {
        ensureFromTemplate();
        repair();
        setReadOnly(true);
    }

    public String javaSelectLine() {
        Path file = config();
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith(JAVA_SELECT_PREFIX)) {
                    return line.trim();
                }
            }
        } catch (IOException ignored) {
            // 忽略
        }
        return "";
    }

    private boolean looksLikeBundledJdk(Path dir) {
        String name = dir.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.startsWith("jdk")) {
            return false;
        }
        return !name.endsWith("-full") && !name.endsWith("-min") && !name.endsWith("-old") && !name.endsWith("-bak");
    }

    private String javaPathFrom(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return javaPathFromText(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    private String javaPathFromText(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = JAVA_PATH.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String toRelative(String rawPath) {
        String path = rawPath.replace('\\', '/');
        if (path.startsWith("./")) {
            return path;
        }
        String rootPath = root.toString().replace('\\', '/');
        if (path.startsWith(rootPath + "/")) {
            return "./" + path.substring(rootPath.length() + 1);
        }
        return null;
    }
}
