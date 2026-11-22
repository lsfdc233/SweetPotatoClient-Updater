// SweetPotatoClient 更新器启动器（单文件 update.exe）
// 由 Gradle 的 buildExe 任务用 csc 编译；内置 update.jar，运行时用客户端自带的 JDK。
// 兼容 C# 5（.NET Framework 4.x 的 csc）。
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

internal static class UpdateLauncher
{
    private const string JarResource = "update.jar";

    private const int StdOutputHandle = -11;
    private const uint EnableVirtualTerminalProcessing = 0x0004;

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr GetStdHandle(int nStdHandle);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetConsoleMode(IntPtr hConsoleHandle, out uint lpMode);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetConsoleMode(IntPtr hConsoleHandle, uint dwMode);

    [StructLayout(LayoutKind.Sequential)]
    private struct Coordinates
    {
        public short X;
        public short Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SmallRectangle
    {
        public short Left;
        public short Top;
        public short Right;
        public short Bottom;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ConsoleScreenBufferInfo
    {
        public Coordinates Size;
        public Coordinates CursorPosition;
        public ushort Attributes;
        public SmallRectangle Window;
        public Coordinates MaximumWindowSize;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetConsoleScreenBufferInfo(IntPtr handle, out ConsoleScreenBufferInfo info);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool FillConsoleOutputCharacterW(IntPtr handle, char character, uint length,
        Coordinates position, out uint written);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool FillConsoleOutputAttribute(IntPtr handle, ushort attribute, uint length,
        Coordinates position, out uint written);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetConsoleCursorPosition(IntPtr handle, Coordinates position);

    /// <summary>用 Win32 API 清空整个控制台缓冲区（含回滚区），比 ANSI 3J 在旧终端上更可靠。</summary>
    private static void ClearConsoleBuffer()
    {
        try
        {
            IntPtr handle = GetStdHandle(StdOutputHandle);
            if (handle == IntPtr.Zero || handle == new IntPtr(-1))
            {
                return;
            }
            ConsoleScreenBufferInfo info;
            if (!GetConsoleScreenBufferInfo(handle, out info))
            {
                return;
            }
            uint cells = (uint)(info.Size.X * info.Size.Y);
            Coordinates origin = new Coordinates();
            uint written;
            FillConsoleOutputCharacterW(handle, ' ', cells, origin, out written);
            FillConsoleOutputAttribute(handle, info.Attributes, cells, origin, out written);
            SetConsoleCursorPosition(handle, origin);
        }
        catch (Exception)
        {
            // 非控制台环境：忽略
        }
    }
    /// <summary>给当前控制台打开 ANSI/VT 转义支持；成功返回 true。</summary>
    private static bool EnableAnsiOnConsole()
    {
        try
        {
            IntPtr handle = GetStdHandle(StdOutputHandle);
            if (handle == IntPtr.Zero || handle == new IntPtr(-1))
            {
                return false;
            }
            uint mode;
            if (!GetConsoleMode(handle, out mode))
            {
                return false;
            }
            if ((mode & EnableVirtualTerminalProcessing) != 0)
            {
                return true;
            }
            return SetConsoleMode(handle, mode | EnableVirtualTerminalProcessing);
        }
        catch (Exception)
        {
            return false;
        }
    }

    private static int Main(string[] args)
    {

        try
        {
            if (args != null && args.Length >= 1
                && string.Equals(args[0], "--clear-console", StringComparison.OrdinalIgnoreCase))
            {
                ClearConsoleBuffer();
                return 0;
            }
            if (args != null && args.Length >= 5
                && string.Equals(args[0], "--apply-update-pull", StringComparison.OrdinalIgnoreCase))
            {
                return ApplyUpdateWithPull(args[1], args[2], args[3], args[4]);
            }
            if (args != null && args.Length >= 4
                && string.Equals(args[0], "--apply-update", StringComparison.OrdinalIgnoreCase))
            {
                if (!File.Exists(args[1]))
                {
                    Console.Error.WriteLine("[SweetPotatoClient-Updater] 待替换文件不存在：" + args[1]);
                    return 2;
                }
                return ApplyUpdate(args[1], args[2], args[3]);
            }
            string exePath = Process.GetCurrentProcess().MainModule.FileName;
            string exeDir = Path.GetDirectoryName(Path.GetFullPath(exePath));
            CleanupStaleUpdate(exeDir);
            string client = FindClientRoot(exeDir);
            if (client == null)
            {
                Fail("找不到 SweetPotatoClient 根目录（需要 SweetPotatoClient.json 或 PCL 目录）。");
                return 2;
            }
            string java = FindJava(client);
            if (java == null)
            {
                Fail("在客户端目录中找不到内置 JDK（jdk-*/bin/java.exe）。");
                return 3;
            }
            string jar = ExtractJar();
            bool ansi = EnableAnsiOnConsole();
            ProcessStartInfo startInfo = new ProcessStartInfo();
            startInfo.FileName = java;
            startInfo.Arguments = "-jar \"" + jar + "\"" + QuoteArgs(args);
            startInfo.WorkingDirectory = client;
            startInfo.UseShellExecute = false;
            startInfo.EnvironmentVariables["SPC_UPDATER_EXE"] = exePath;
            if (ansi)
            {
                startInfo.EnvironmentVariables["SPC_UPDATER_ANSI"] = "1";
            }
            Process process = Process.Start(startInfo);
            process.WaitForExit();
            return process.ExitCode;
        }
        catch (Exception e)
        {
            Fail("启动失败：" + e.Message);
            return 1;
        }
    }

    private static void Fail(string message)
    {
        Console.Error.WriteLine("[SweetPotatoClient-Updater] " + message);
        try
        {
            Console.WriteLine("按 Enter 退出...");
            Console.ReadLine();
        }
        catch (Exception)
        {
            // 非交互环境
        }
    }

    private static string QuoteArgs(string[] args)
    {
        StringBuilder builder = new StringBuilder();
        if (args == null)
        {
            return string.Empty;
        }
        foreach (string arg in args)
        {
            builder.Append(' ');
            if (arg.IndexOf(' ') >= 0 || arg.IndexOf('"') >= 0 || arg.IndexOf('=') >= 0)
            {
                builder.Append('"').Append(arg.Replace("\"", "\\\"")).Append('"');
            }
            else
            {
                builder.Append(arg);
            }
        }
        return builder.ToString();
    }

    private static string FindClientRoot(string start)
    {
        string current = start;
        while (!string.IsNullOrEmpty(current))
        {
            if (File.Exists(Path.Combine(current, "SweetPotatoClient.json"))
                || Directory.Exists(Path.Combine(current, "PCL")))
            {
                return current;
            }
            DirectoryInfo parent = Directory.GetParent(current);
            current = parent == null ? null : parent.FullName;
        }
        return null;
    }

    private static string FindJava(string client)
    {
        string preferred = Path.Combine(client, "jdk-25.0.5.0.3", "bin", "java.exe");
        if (File.Exists(preferred))
        {
            return preferred;
        }
        List<string> candidates = new List<string>();
        string[] dirs = Directory.GetDirectories(client, "jdk*");
        foreach (string dir in dirs)
        {
            string name = Path.GetFileName(dir);
            if (name.EndsWith("-full") || name.EndsWith("-min") || name.EndsWith("-old"))
            {
                continue;
            }
            string java = Path.Combine(dir, "bin", "java.exe");
            if (File.Exists(java))
            {
                candidates.Add(java);
            }
        }
        candidates.Sort(delegate(string a, string b)
        {
            int byLength = a.Length.CompareTo(b.Length);
            return byLength != 0 ? byLength : string.CompareOrdinal(a, b);
        });
        return candidates.Count > 0 ? candidates[0] : null;
    }

    private static string ExtractJar()
    {
        Assembly assembly = Assembly.GetExecutingAssembly();
        using (Stream stream = assembly.GetManifestResourceStream(JarResource))
        {
            if (stream == null)
            {
                throw new InvalidOperationException("内置 " + JarResource + " 资源缺失");
            }
            string hash;
            using (SHA256 sha = SHA256.Create())
            {
                hash = BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", string.Empty).Substring(0, 16);
            }
            stream.Position = 0;
            string dir = Path.Combine(Path.GetTempPath(), "sweetpotato-updater");
            Directory.CreateDirectory(dir);
            string jar = Path.Combine(dir, "update-" + hash + ".jar");
            if (!File.Exists(jar))
            {
                string temp = jar + ".tmp";
                using (FileStream output = File.Create(temp))
                {
                    stream.CopyTo(output);
                }
                if (File.Exists(jar))
                {
                    File.Delete(jar);
                }
                File.Move(temp, jar);
            }
            return jar;
        }
    }
    /// <summary>先 git pull（此时旧 update.exe 已退出，可写），再热替换并重启。</summary>
    private static int ApplyUpdateWithPull(string sourcePath, string targetPath, string launcherPidText, string repoDir)
    {
        int pid;
        int.TryParse(launcherPidText, out pid);
        DateTime deadline = DateTime.UtcNow.AddSeconds(60);
        while (pid > 0 && ProcessExists(pid) && DateTime.UtcNow < deadline)
        {
            Thread.Sleep(200);
        }

        try
        {
            Console.WriteLine("正在执行 git pull ...");
            ProcessStartInfo git = new ProcessStartInfo("git");
            git.Arguments = "-c pull.rebase=false pull --no-rebase --no-edit";
            git.WorkingDirectory = repoDir;
            git.UseShellExecute = false;
            git.EnvironmentVariables["GIT_TERMINAL_PROMPT"] = "0";
            using (Process process = Process.Start(git))
            {
                process.WaitForExit();
                if (process.ExitCode != 0)
                {
                    Console.Error.WriteLine("[SweetPotatoClient-Updater] git pull 返回 " + process.ExitCode + "，可稍后在更新器里重试。");
                }
            }
        }
        catch (Exception e)
        {
            Console.Error.WriteLine("[SweetPotatoClient-Updater] git pull 失败：" + e.Message);
        }

        return ApplyUpdate(sourcePath, targetPath, "0");
    }
    private static int ApplyUpdate(string sourcePath, string targetPath, string launcherPidText)
    {
        string dir = Path.GetDirectoryName(targetPath);
        int pid;
        int.TryParse(launcherPidText, out pid);
        DateTime deadline = DateTime.UtcNow.AddSeconds(60);
        while (pid > 0 && ProcessExists(pid) && DateTime.UtcNow < deadline)
        {
            Thread.Sleep(200);
        }
        DateTime copyDeadline = DateTime.UtcNow.AddSeconds(30);
        Exception last = null;
        while (DateTime.UtcNow < copyDeadline)
        {
            try
            {
                File.Copy(sourcePath, targetPath, true);
                last = null;
                break;
            }
            catch (Exception e)
            {
                last = e;
                Thread.Sleep(250);
            }
        }
        if (last != null)
        {
            Log(dir, "替换失败：" + last.Message);
            Console.Error.WriteLine("[SweetPotatoClient-Updater] 替换失败：" + last.Message);
            Console.Error.WriteLine("新版本文件：" + sourcePath);
            Console.Error.WriteLine("请手动把它复制为：" + targetPath);
            return 4;
        }
        try
        {
            File.Delete(sourcePath);
        }
        catch (Exception)
        {
            // 删不掉也无妨
        }
        try
        {
            // 始终继承当前控制台：新版本会接在原窗口里运行，不会另开窗口也不会“看起来没起来”
            ProcessStartInfo startInfo = new ProcessStartInfo(targetPath);
            startInfo.UseShellExecute = false;
            startInfo.WorkingDirectory = Path.GetDirectoryName(targetPath);
            Process.Start(startInfo);
        }
        catch (Exception)
        {
            // 文件已替换，用户可手动启动
        }
        return 0;
    }


    private static void Log(string dir, string message)
    {
        try
        {
            File.AppendAllText(Path.Combine(dir, "update-apply.log"),
                DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " " + message + Environment.NewLine);
        }
        catch (Exception)
        {
            // 忽略日志失败
        }
    }

    private static bool ProcessExists(int pid)
    {
        try
        {
            using (Process process = Process.GetProcessById(pid))
            {
                return !process.HasExited;
            }
        }
        catch (Exception)
        {
            return false;
        }
    }

    private static void CleanupStaleUpdate(string exeDir)
    {
        try
        {
            string stale = Path.Combine(exeDir, "update.exe.new");
            if (File.Exists(stale))
            {
                File.Delete(stale);
            }
        }
        catch (Exception)
        {
            // 忽略：下次启动再清理
        }
    }
}