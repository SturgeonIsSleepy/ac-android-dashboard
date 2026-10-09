using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Threading;

// Keep this bridge's ADB reverse mapping alive when an authorized phone is plugged in.
sealed class UsbLink : IDisposable
{
    readonly string adb;
    readonly Thread worker;
    volatile bool closed;
    public volatile string Status;
    public UsbLink()
    {
        adb = FindAdb();
        Status = adb == null ? "未找到 ADB，可将 platform-tools 放在程序旁" : "等待已授权的 USB 设备";
        if (adb != null) { worker = new Thread(Check) { IsBackground = true, Name = "ACFlip-USB" }; worker.Start(); }
    }
    static string FindAdb()
    {
        var paths = new List<string>(); string root = AppDomain.CurrentDomain.BaseDirectory;
        paths.Add(Path.Combine(root,"adb.exe")); paths.Add(Path.Combine(root,"platform-tools","adb.exe"));
        foreach (string key in new[] { "ANDROID_HOME", "ANDROID_SDK_ROOT" }) {
            string value = Environment.GetEnvironmentVariable(key);
            if (!String.IsNullOrEmpty(value)) paths.Add(Path.Combine(value,"platform-tools","adb.exe"));
        }
        paths.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Android","Sdk","platform-tools","adb.exe"));
        foreach (string value in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';'))
            if (!String.IsNullOrWhiteSpace(value)) paths.Add(Path.Combine(value.Trim('"'),"adb.exe"));
        foreach (string path in paths) if (File.Exists(path)) return path;
        return null;
    }
    string Run(string args)
    {
        var info = new ProcessStartInfo(adb,args) { UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true };
        using (var process = Process.Start(info)) {
            process.BeginErrorReadLine();
            if (!process.WaitForExit(2000)) { process.Kill(); return null; }
            return process.ExitCode == 0 ? process.StandardOutput.ReadToEnd() : null;
        }
    }
    void Check()
    {
        while (!closed) {
            try {
                int ready = 0; bool unauthorized = false;
                foreach (string line in (Run("devices") ?? "").Split('\n')) {
                    string[] fields = line.Trim().Split(new[] { '\t',' ' },StringSplitOptions.RemoveEmptyEntries);
                    if (fields.Length != 2) continue;
                    if (fields[1] == "unauthorized") unauthorized = true;
                    if (fields[1] != "device" || fields[0].IndexOf(':') >= 0) continue;
                    // ADB device serials contain no shell; reject quotes before passing a process argument.
                    if (fields[0].IndexOf('"') >= 0) continue;
                    if (Run("-s \""+fields[0]+"\" reverse tcp:9877 tcp:9877") != null) ready++;
                }
                Status = ready > 0 ? "已连接 "+ready+" 台设备，有线优先" : unauthorized ? "请在手机确认 USB 调试授权" : "等待 USB 设备，无线仍可使用";
            } catch (System.ComponentModel.Win32Exception) { Status = "ADB 启动失败，无线仍可使用"; }
            catch (IOException) { Status = "USB 重试中，无线仍可使用"; }
            for (int i = 0; i < 20 && !closed; i++) Thread.Sleep(100);
        }
    }
    public void Dispose() { closed = true; }
}
