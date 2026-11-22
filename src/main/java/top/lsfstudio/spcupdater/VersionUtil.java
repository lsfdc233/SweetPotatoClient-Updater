package top.lsfstudio.spcupdater;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 版本号比较：v主.次.修订-构建号。 */
public final class VersionUtil {
    private static final Pattern PATTERN = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(\\d+))?.*$");

    private VersionUtil() {
    }

    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    public static int compare(String left, String right) {
        int[] a = parse(left);
        int[] b = parse(right);
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private static int[] parse(String text) {
        Matcher matcher = PATTERN.matcher(text == null ? "" : text.trim());
        if (!matcher.find()) {
            return new int[]{0, 0, 0, 0};
        }
        int build = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
        return new int[]{
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                build
        };
    }
}
