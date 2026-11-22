package top.lsfstudio.spcupdater;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 更新器自身的版本与项目地址。 */
public final class UpdaterInfo {
    /** 版本格式：v主.次.修订-构建号；运行时读取构建时写入的 version.txt。 */
    public static final String VERSION = loadVersion();
    public static final String AUTHOR = "lsfdc";
    public static final String UPDATER_REPO = "https://git.lsfstudio.top:21619/LiSFeDCo/SweetPotatoClient-Updater.git";
    public static final String CLIENT_REPO = "https://git.lsfstudio.top:21619/LiSFeDCo/SweetPotatoClient.git";

    private UpdaterInfo() {
    }

    private static String loadVersion() {
        try (InputStream input = UpdaterInfo.class.getResourceAsStream("/version.txt")) {
            if (input != null) {
                String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        } catch (IOException ignored) {
            // 退回开发版本
        }
        return "v0.0.3-00037";
    }

    /**
     * 云端版本。
     *
     * <p>TODO：首个 Release 发布后实现——从更新器仓库的 Release 接口读取最新 tag，
     * 解析成 v主.次.修订-构建号 后与 {@link #VERSION} 比较。</p>
     */
    public static String cloudVersion() {
        return "未知（尚未发布 Release）";
    }
}
