using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

sealed class ClientInfo
{
    public double Seen;
    public long Rtt = -1, Received, Lost, Sent;
    public int Width, Height, Display;
    public string Device = "探测客户端";
}

sealed class ConsoleDashboard
{
    readonly string addresses;
    readonly bool redirected;
    readonly string metricsPath;
    public string LastError = "无";

    public ConsoleDashboard()
    {
        Console.OutputEncoding = Encoding.UTF8;
        Console.Title = "AC FLIP 0.8  连接监视器";
        redirected = Console.IsOutputRedirected;
        if (!redirected)
        {
            Console.CursorVisible = false;
            // Quick Edit selection pauses the entire console process on classic hosts.
            DisableQuickEdit();
            try { Console.SetWindowSize(Math.Min(106, Console.LargestWindowWidth), Math.Min(34, Console.LargestWindowHeight)); }
            catch (IOException) { }
            Console.Clear();
        }
        var rows = new List<string>();
        foreach (var adapter in NetworkInterface.GetAllNetworkInterfaces())
            if (adapter.OperationalStatus == OperationalStatus.Up)
                foreach (var address in adapter.GetIPProperties().UnicastAddresses)
                    if (address.Address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address.Address))
                        rows.Add("  " + adapter.Name + ": " + address.Address);
        addresses = String.Join("\n", rows.ToArray());
        metricsPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "bridge-status.txt");
    }

    public void Render(double now, string game, string track, string car, int rpm, int gear,
        float speed, int routePoints, int cueCount, long totalFrames, long totalBytes, double hz,
        Dictionary<IPEndPoint, ClientInfo> clients, string gameRoot, bool raceReady, float pitLimit)
    {
        var b = new StringBuilder();
        b.AppendLine("AC FLIP 0.8   连接监视器");
        b.AppendLine("关闭本窗口即断开连接    Ctrl+C / Q 退出");
        b.AppendLine();
        b.AppendLine("电脑 IPv4"); b.AppendLine(addresses);
        b.AppendLine("监听: 0.0.0.0:9876 UDP    游戏到手机: 局域网直传");
        b.AppendLine();
        b.AppendLine("游戏: " + game);
        b.AppendLine("赛道: " + track + "    车辆: " + car);
        b.AppendLine("挡位: " + (gear == 0 ? "R" : gear == 1 ? "N" : (gear-1).ToString()) +
            "    转速: " + rpm + " RPM    车速: " + speed.ToString("F0") + " km/h");
        b.AppendLine("共享内存: " + (game == "等待游戏" ? "未就绪" : "已读取") +
            "    路线: " + routePoints + " 点    路书: " + cueCount + " 个弯");
        b.AppendLine("目录: " + gameRoot);
        b.AppendLine("分段采集: " + (raceReady ? "已同步" : "等待新帧") + "    维修区限速: " + (pitLimit > 0 ? pitLimit.ToString("F0")+" km/h" : "未提供"));
        b.AppendLine();
        b.AppendLine("发送: " + hz.ToString("F1") + " Hz    累计帧: " + totalFrames +
            "    累计数据: " + (totalBytes / 1024.0).ToString("F1") + " KB");
        b.AppendLine("客户端: " + clients.Count + "    心跳超时: 5 秒");
        foreach (var pair in clients)
        {
            var c = pair.Value;
            b.AppendLine("  " + pair.Key + "    " + c.Device);
            b.AppendLine("  屏幕: " + c.Width + "×" + c.Height + "  display " + c.Display +
                "    RTT: " + (c.Rtt < 0 ? "等待测量" : c.Rtt + " ms") +
                "    心跳: " + (now-c.Seen).ToString("F1") + " s");
            b.AppendLine("  本窗口发送: " + c.Sent + "    手机累计接收: " + c.Received + "    手机累计丢帧: " + c.Lost);
        }
        if (clients.Count == 0) b.AppendLine("  等待手机连接，在手机连接页填入上方电脑 IP");
        b.AppendLine();
        b.AppendLine("最近错误: " + LastError);
        string screen = b.ToString();
        File.WriteAllText(metricsPath, screen, new UTF8Encoding(false));
        if (redirected) { Console.WriteLine(screen); return; }
        try
        {
            Console.SetCursorPosition(0, 0);
            string[] lines = screen.Replace("\r", "").Split('\n');
            int width = Math.Max(1, Console.WindowWidth-1), height = Math.Min(Console.WindowHeight-1, 32);
            var output = new StringBuilder();
            for (int i = 0; i < height; i++)
            {
                string line = i < lines.Length ? lines[i] : "";
                output.AppendLine(Fit(line, width));
            }
            Console.Write(output.ToString());
        }
        catch (IOException) { }
    }

    static string Fit(string text, int columns)
    {
        var result = new StringBuilder(); int used = 0;
        foreach (char character in text)
        {
            int width = character > 255 ? 2 : 1;
            if (used + width > columns) break;
            result.Append(character); used += width;
        }
        return result.Append(' ', columns-used).ToString();
    }

    [System.Runtime.InteropServices.DllImport("kernel32.dll")] static extern IntPtr GetStdHandle(int id);
    [System.Runtime.InteropServices.DllImport("kernel32.dll")] static extern bool GetConsoleMode(IntPtr handle, out uint mode);
    [System.Runtime.InteropServices.DllImport("kernel32.dll")] static extern bool SetConsoleMode(IntPtr handle, uint mode);
    static void DisableQuickEdit()
    {
        IntPtr handle = GetStdHandle(-10); uint mode;
        if (GetConsoleMode(handle, out mode)) SetConsoleMode(handle, (mode | 0x80) & ~0x40u);
    }
}
