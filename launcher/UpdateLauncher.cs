// SweetPotatoClient 更新器启动器（单文件 update.exe）
// 由 Gradle 的 buildExe 任务用 csc 编译；内置 update.jar，运行时用客户端自带的 JDK。
// 兼容 C# 5（.NET Framework 4.x 的 csc）。
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;

internal static class UpdateLauncher
{
    private const string JarResource = "update.jar";

    private static int Main(string[] args)
    {
        try
        {
            string exePath = Process.GetCurrentProcess().MainModule.FileName;
            string exeDir = Path.GetDirectoryName(Path.GetFullPath(exePath));
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
            ProcessStartInfo startInfo = new ProcessStartInfo();
            startInfo.FileName = java;
            startInfo.Arguments = "-jar \"" + jar + "\"" + QuoteArgs(args);
            startInfo.WorkingDirectory = client;
            startInfo.UseShellExecute = false;
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
}
