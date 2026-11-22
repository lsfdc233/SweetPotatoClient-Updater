package top.lsfstudio.spcupdater;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 更新器 Release 接口：查询最新版本、下载附件、计算/解析 SHA-256。 */
public final class ReleaseClient {
    public static final String LATEST_API =
            "https://git.lsfstudio.top:21619/api/v1/repos/LiSFeDCo/SweetPotatoClient-Updater/releases/latest";

    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern BODY = Pattern.compile("\"body\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.DOTALL);
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern URL = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern SHA256_HEX = Pattern.compile("\\b([0-9a-fA-F]{64})\\b");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record Asset(String name, String url) {
    }

    public record Release(String tag, String body, List<Asset> assets) {
        public Optional<Asset> asset(String name) {
            return assets.stream().filter(a -> a.name().equalsIgnoreCase(name)).findFirst();
        }

        public Optional<Asset> firstUpdateExe() {
            return assets.stream()
                    .filter(a -> a.name().toLowerCase(Locale.ROOT).endsWith(".exe"))
                    .filter(a -> a.name().toLowerCase(Locale.ROOT).contains("update"))
                    .findFirst();
        }
    }

    /** 下载进度回调。 */
    public interface Progress {
        void update(long done, long total);
    }

    private ReleaseClient() {
    }

    public static Release fetchLatest(Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_API))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent())
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Release 接口返回 HTTP " + response.statusCode());
        }
        String json = response.body();
        Matcher tagMatcher = TAG.matcher(json);
        if (!tagMatcher.find()) {
            throw new IOException("无法解析 Release：缺少 tag_name");
        }
        String tag = unescape(tagMatcher.group(1));
        Matcher bodyMatcher = BODY.matcher(json);
        String body = bodyMatcher.find() ? unescape(bodyMatcher.group(1)) : "";
        return new Release(tag, body, parseAssets(json));
    }

    public static String download(String url, Path target, Progress progress) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", userAgent())
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("下载失败：HTTP " + response.statusCode());
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("当前 JRE 不支持 SHA-256", e);
        }
        try (InputStream input = response.body();
             OutputStream output = Files.newOutputStream(target,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            int lastPercent = -1;
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                done += read;
                if (total > 0) {
                    int percent = (int) (done * 100 / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        progress.update(done, total);
                    }
                }
            }
        }
        return hex(digest.digest());
    }

    public static String downloadText(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", userAgent())
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("下载校验文件失败：HTTP " + response.statusCode());
        }
        return response.body();
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("当前 JRE 不支持 SHA-256", e);
        }
    }

    /** 从 sha256 文件内容里取第一个 64 位十六进制串（GNU 格式：hash  filename）。 */
    public static String parseSha256(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = SHA256_HEX.matcher(text);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** 在 "assets" 数组内按出现顺序把 name 与 browser_download_url 配对。 */
    private static List<Asset> parseAssets(String json) {
        List<Asset> assets = new ArrayList<>();
        int assetsAt = json.indexOf("\"assets\"");
        if (assetsAt < 0) {
            return assets;
        }
        int start = json.indexOf('[', assetsAt);
        if (start < 0) {
            return assets;
        }
        int end = json.indexOf(']', start);
        String block = end < 0 ? json.substring(start) : json.substring(start, end);
        Matcher names = NAME.matcher(block);
        Matcher urls = URL.matcher(block);
        while (names.find() && urls.find()) {
            assets.add(new Asset(unescape(names.group(1)), unescape(urls.group(1))));
        }
        return assets;
    }

    private static String userAgent() {
        return "SweetPotatoClient-Updater/" + UpdaterInfo.VERSION;
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    private static String unescape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char next = raw.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'u' -> {
                    if (i + 4 < raw.length()) {
                        try {
                            out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException ignored) {
                            out.append("\\u");
                        }
                    } else {
                        out.append("\\u");
                    }
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }
}
