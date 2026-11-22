package top.lsfstudio.spcupdater;

/** 更新器自身的版本与项目地址。 */
public final class UpdaterInfo {
    /** 版本格式：v主.次.修订-构建号 */
    public static final String VERSION = "v0.0.1-00001";
    public static final String AUTHOR = "lsfdc";
    public static final String UPDATER_REPO = "https://git.lsfstudio.top:21619/LiSFeDCo/SweetPotatoClient-Updater.git";
    public static final String CLIENT_REPO = "https://git.lsfstudio.top:21619/LiSFeDCo/SweetPotatoClient.git";

    private UpdaterInfo() {
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
