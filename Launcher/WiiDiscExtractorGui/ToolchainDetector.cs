using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;

namespace WiiCompiled.Android.Builder;

public record ToolchainStatus(
    string? SdkPath,
    string? NdkPath,
    string? CMakePath,
    string? NinjaPath,
    string? AdbPath,
    bool DotNet8Available,
    IReadOnlyList<string> ConnectedDevices
)
{
    public bool HasRequiredTools =>
        !string.IsNullOrEmpty(SdkPath) &&
        !string.IsNullOrEmpty(NdkPath) &&
        !string.IsNullOrEmpty(CMakePath) &&
        !string.IsNullOrEmpty(NinjaPath) &&
        DotNet8Available;
}

public static class ToolchainDetector
{
    public static ToolchainStatus Detect(string workspaceRoot)
    {
        string? localPropsPath = Path.Combine(workspaceRoot, "android", "local.properties");
        string? lpSdk = null;

        if (File.Exists(localPropsPath))
        {
            foreach (var line in File.ReadAllLines(localPropsPath))
            {
                var trimmed = line.Trim();
                if (trimmed.StartsWith("sdk.dir=", StringComparison.OrdinalIgnoreCase))
                    lpSdk = trimmed.Substring(8).Replace("\\\\", "\\").Trim();
            }
        }

        string? sdk = FindAndroidSdk(lpSdk);
        string? ndk = FindNdk(sdk);
        string? cmake = FindCMake(sdk);
        string? ninja = FindNinja(sdk, ndk);
        string? adb = FindAdb(sdk);
        bool dotNet8 = CheckDotNet8();
        var devices = adb != null ? ListAdbDevices(adb) : Array.Empty<string>();

        return new ToolchainStatus(sdk, ndk, cmake, ninja, adb, dotNet8, devices);
    }

    private static string? FindAndroidSdk(string? localPropsSdk)
    {
        var candidates = new List<string?>();
        if (!string.IsNullOrWhiteSpace(localPropsSdk)) candidates.Add(localPropsSdk);
        candidates.Add(Environment.GetEnvironmentVariable("ANDROID_HOME"));
        candidates.Add(Environment.GetEnvironmentVariable("ANDROID_SDK_ROOT"));

        string localApp = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (!string.IsNullOrEmpty(localApp))
            candidates.Add(Path.Combine(localApp, "Android", "Sdk"));

        string userProf = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (!string.IsNullOrEmpty(userProf))
            candidates.Add(Path.Combine(userProf, "AppData", "Local", "Android", "Sdk"));

        candidates.Add(@"C:\Android\Sdk");

        foreach (var c in candidates)
        {
            if (string.IsNullOrWhiteSpace(c)) continue;
            try
            {
                string full = Path.GetFullPath(c);
                if (Directory.Exists(full) && Directory.Exists(Path.Combine(full, "platform-tools")))
                    return full;
            }
            catch { }
        }

        return null;
    }

    private static string? FindNdk(string? sdk)
    {
        var candidates = new List<string?>();
        candidates.Add(Environment.GetEnvironmentVariable("ANDROID_NDK_HOME"));
        candidates.Add(Environment.GetEnvironmentVariable("ANDROID_NDK_ROOT"));
        candidates.Add(Environment.GetEnvironmentVariable("ANDROID_NDK"));

        if (!string.IsNullOrEmpty(sdk))
        {
            string ndkDir = Path.Combine(sdk, "ndk");
            if (Directory.Exists(ndkDir))
            {
                try
                {
                    var dirs = Directory.GetDirectories(ndkDir).OrderByDescending(d => Path.GetFileName(d));
                    candidates.AddRange(dirs);
                }
                catch { }
            }
        }

        foreach (var c in candidates)
        {
            if (string.IsNullOrWhiteSpace(c)) continue;
            try
            {
                string full = Path.GetFullPath(c);
                if (Directory.Exists(full))
                {
                    string toolchainBin = Path.Combine(full, "toolchains", "llvm", "prebuilt", "windows-x86_64", "bin");
                    if (Directory.Exists(toolchainBin))
                        return full;
                }
            }
            catch { }
        }

        return null;
    }

    private static string? FindCMake(string? sdk)
    {
        if (!string.IsNullOrEmpty(sdk))
        {
            string cmakeRoot = Path.Combine(sdk, "cmake");
            if (Directory.Exists(cmakeRoot))
            {
                try
                {
                    var dirs = Directory.GetDirectories(cmakeRoot).OrderByDescending(d => Path.GetFileName(d));
                    foreach (var d in dirs)
                    {
                        string exe = Path.Combine(d, "bin", "cmake.exe");
                        if (File.Exists(exe)) return exe;
                    }
                }
                catch { }
            }
        }

        return FindInPath("cmake.exe");
    }

    private static string? FindNinja(string? sdk, string? ndk)
    {
        if (!string.IsNullOrEmpty(sdk))
        {
            string cmakeRoot = Path.Combine(sdk, "cmake");
            if (Directory.Exists(cmakeRoot))
            {
                try
                {
                    var dirs = Directory.GetDirectories(cmakeRoot).OrderByDescending(d => Path.GetFileName(d));
                    foreach (var d in dirs)
                    {
                        string exe = Path.Combine(d, "bin", "ninja.exe");
                        if (File.Exists(exe)) return exe;
                    }
                }
                catch { }
            }
        }

        if (!string.IsNullOrEmpty(ndk))
        {
            string exe = Path.Combine(ndk, "prebuilt", "windows-x86_64", "bin", "ninja.exe");
            if (File.Exists(exe)) return exe;
        }

        return FindInPath("ninja.exe");
    }

    private static string? FindAdb(string? sdk)
    {
        string? fromPath = FindInPath("adb.exe");
        if (fromPath != null) return fromPath;

        if (!string.IsNullOrEmpty(sdk))
        {
            string exe = Path.Combine(sdk, "platform-tools", "adb.exe");
            if (File.Exists(exe)) return exe;
        }

        return null;
    }

    private static bool CheckDotNet8()
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "dotnet",
                Arguments = "--list-sdks",
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            using var proc = Process.Start(psi);
            if (proc == null) return false;
            string outText = proc.StandardOutput.ReadToEnd();
            proc.WaitForExit();
            return outText.Split('\n', '\r').Any(line => line.Trim().StartsWith("8."));
        }
        catch
        {
            return false;
        }
    }

    private static IReadOnlyList<string> ListAdbDevices(string adbPath)
    {
        var list = new List<string>();
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = adbPath,
                Arguments = "devices",
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            using var proc = Process.Start(psi);
            if (proc == null) return list;
            string outText = proc.StandardOutput.ReadToEnd();
            proc.WaitForExit();

            foreach (var rawLine in outText.Split('\n', '\r'))
            {
                var line = rawLine.Trim();
                if (string.IsNullOrEmpty(line) || line.StartsWith("List of devices") || line.StartsWith("*"))
                    continue;

                var parts = line.Split('\t', ' ');
                if (parts.Length >= 2 && parts.Last().Equals("device", StringComparison.OrdinalIgnoreCase))
                {
                    list.Add(parts[0].Trim());
                }
            }
        }
        catch { }

        return list;
    }

    private static string? FindInPath(string exeName)
    {
        string? pathEnv = Environment.GetEnvironmentVariable("PATH");
        if (string.IsNullOrEmpty(pathEnv)) return null;

        foreach (var p in pathEnv.Split(';', StringSplitOptions.RemoveEmptyEntries))
        {
            try
            {
                string candidate = Path.Combine(p.Trim(), exeName);
                if (File.Exists(candidate)) return candidate;
            }
            catch { }
        }

        return null;
    }
}
