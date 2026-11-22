package top.lsfstudio.spcupdater;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** 控制台界面：颜色、ASCII 横幅、输入。 */
public final class Ui {
    private static final String ESC = "\u001b[";
    private static final PrintStream OUT = System.out;

    private static final String[] BANNER = {
            "  ____ __        __ _____  _____  _____  ____    ___  _____   _   _____  ___",
            " / ___|\\ \\      / /| ____|| ____||_   _||  _ \\  / _ \\|_   _| / \\ |_   _|/ _ \\",
            " \\___ \\ \\ \\ /\\ / / |  _|  |  _|    | |  | |_) || | | | | |  / _ \\  | | | | | |",
            "  ___) | \\ V  V /  | |___ | |___   | |  |  __/ | |_| | | | / ___ \\ | | | |_| |",
            " |____/   \\_/\\_/   |_____||_____|  |_|  |_|     \\___/  |_|/_/   \\_\\|_|  \\___/",
            "  _   _  ____   ____     _   _____  _____  ____",
            " | | | ||  _ \\ |  _ \\   / \\ |_   _|| ____||  _ \\",
            " | | | || |_) || | | | / _ \\  | |  |  _|  | |_) |",
            " | |_| ||  __/ | |_| |/ ___ \\ | |  | |___ |  _ <",
            "  \\___/ |_|    |____//_/   \\_\\|_|  |_____||_| \\_\\"
    };

    private static boolean color;
    private static BufferedReader reader;

    private Ui() {
    }

    public static void init(boolean wantColor, boolean forceColor) {
        reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        if (!wantColor) {
            color = false;
            return;
        }
        // 显式 --color 优先于 NO_COLOR 与终端检测（重定向时也可强制上色）
        if (forceColor) {
            color = true;
            return;
        }
        boolean launcherAnsi = "1".equals(System.getenv("SPC_UPDATER_ANSI"));
        boolean console = System.console() != null;
        boolean ansi = launcherAnsi
                || System.getenv("WT_SESSION") != null
                || System.getenv("TERM_PROGRAM") != null
                || System.getenv("ANSICON") != null
                || System.getenv("TERM") != null;
        boolean noColorEnv = System.getenv("NO_COLOR") != null;
        color = ansi && !noColorEnv && (console || launcherAnsi);
    }

    public static boolean colorEnabled() {
        return color;
    }

    private static String wrap(String code, String text) {
        return color ? ESC + code + "m" + text + ESC + "0m" : text;
    }

    public static String bold(String text) { return wrap("1", text); }
    public static String dim(String text) { return wrap("2", text); }
    public static String red(String text) { return wrap("31", text); }
    public static String green(String text) { return wrap("32", text); }
    public static String yellow(String text) { return wrap("33", text); }
    public static String cyan(String text) { return wrap("36", text); }
    public static String gray(String text) { return wrap("90", text); }

    public static void clear() {
        OUT.flush();
        if (clearWithHelper()) {
            return;
        }
        if (color) {
            // 3J 连回滚缓冲一起清掉（部分旧终端不支持，所以优先走上面的 Win32 helper）
            OUT.print(ESC + "3J" + ESC + "2J" + ESC + "H");
            OUT.flush();
        } else {
            for (int i = 0; i < 60; i++) {
                OUT.println();
            }
        }
    }

    /**
     * Windows 下让 update.exe --clear-console 用 Win32 API 清空整个控制台缓冲区（含回滚区），
     * 比 ANSI 3J 在 conhost 等旧终端上更可靠。
     */
    private static boolean clearWithHelper() {
        String exe = System.getenv("SPC_UPDATER_EXE");
        if (exe == null || exe.isBlank()) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(exe, "--clear-console").inheritIO().start();
            return process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    public static void banner() {
        for (String line : BANNER) {
            OUT.println(cyan(line));
        }
    }

    public static void println() {
        OUT.println();
    }

    public static void println(String text) {
        OUT.println(text);
    }

    /** 进入子界面：先清屏（连同回滚缓冲），再画标题。 */
    public static void title(String text) {
        clear();
        OUT.println();
        OUT.println("  " + bold(text));
        OUT.println("  " + gray("-".repeat(Math.max(16, text.length()))));
    }

    public static String readLine(String prompt) {
        OUT.print("  " + yellow(prompt) + " ");
        OUT.flush();
        try {
            return reader.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    public static void pause(String message) {
        readLine(message);
    }

    public static boolean confirm(String prompt) {
        String answer = readLine(prompt);
        if (answer == null) {
            return false;
        }
        String trimmed = answer.trim();
        return trimmed.equalsIgnoreCase("y") || trimmed.equalsIgnoreCase("yes");
    }
}
