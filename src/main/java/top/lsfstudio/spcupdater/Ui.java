package top.lsfstudio.spcupdater;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** 控制台界面：颜色、ASCII 横幅、输入。 */
public final class Ui {
    private static final String ESC = "\u001b[";
    private static PrintStream OUT = System.out;

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
        if (Launcher.isWindows()) {
            try {
                java.nio.charset.Charset charset = Launcher.consoleCharset();
                OUT = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, charset);
                System.setOut(OUT);
                System.setErr(new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, charset));
            } catch (Exception ignored) {
                OUT = System.out;
            }
        }
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
        // 原生 exe 模式下 System.console() 可能为 null，默认按支持 ANSI 处理；
        // 通过 --no-color / NO_COLOR 关闭。
        boolean launcherAnsi = "1".equals(System.getenv("SPC_UPDATER_ANSI"));
        boolean noColorEnv = System.getenv("NO_COLOR") != null;
        color = wantColor && !noColorEnv || (forceColor && wantColor);
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
        Launcher.clearConsole();
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

    /** 读取输入；prompt 已经是带颜色的字符串，不再整体着色。 */
    public static String readMarked(String prompt) {
        OUT.print("  " + prompt + " ");
        OUT.flush();
        try {
            return reader.readLine();
        } catch (Exception e) {
            return null;
        }
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
