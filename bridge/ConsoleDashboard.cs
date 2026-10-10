using System;
using System.Collections.Generic;
using System.IO;
using System.Globalization;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

sealed class ClientInfo
{
    public double Seen;
    public long Rtt = -1, WirelessRtt = -1, Received, Lost, Sent;
    public int Width, Height, Display;
    public string Device = "探测客户端";
}

sealed class ConsoleDashboard
{
    readonly string addresses;
    readonly bool redirected;
    readonly string metricsPath;
    public string LastError = "无";
    int mirrorPage, mirrorField;
    string mirrorNumber;
    public bool MirrorKey(ConsoleKeyInfo key,MirrorSettings settings,int selected) {
        if (mirrorPage == 0) { if (key.Key != ConsoleKey.M) return false; mirrorPage = selected > 0 ? selected : 1; return true; }
        if (mirrorNumber != null) {
            if (key.Key == ConsoleKey.Escape) mirrorNumber = null;
            else if (key.Key == ConsoleKey.Backspace && mirrorNumber.Length > 0) mirrorNumber = mirrorNumber.Substring(0,mirrorNumber.Length-1);
            else if (key.Key == ConsoleKey.Enter) {
                float value;
                if (float.TryParse(mirrorNumber,NumberStyles.Float,CultureInfo.InvariantCulture,out value)) settings.Set(mirrorPage,mirrorField,value);
                mirrorNumber = null;
            } else if (Char.IsDigit(key.KeyChar) || key.KeyChar == '-' || key.KeyChar == '.') mirrorNumber += key.KeyChar;
            return true;
        }
        if (key.Key == ConsoleKey.Escape || key.Key == ConsoleKey.M) mirrorPage = 0;
        else if (key.KeyChar >= '1' && key.KeyChar <= '3') mirrorPage = key.KeyChar-'0';
        else if (key.Key == ConsoleKey.UpArrow) mirrorField = (mirrorField+5)%6;
        else if (key.Key == ConsoleKey.DownArrow) mirrorField = (mirrorField+1)%6;
        else if (key.Key == ConsoleKey.Enter) mirrorNumber = "";
        else if (key.Key == ConsoleKey.R) settings.Reset(mirrorPage);
        else if (key.Key == ConsoleKey.LeftArrow || key.Key == ConsoleKey.RightArrow) {
            float step = MirrorSettings.Step[mirrorField]*((key.Modifiers&ConsoleModifiers.Shift) != 0 ? 5 : 1);
            settings.Set(mirrorPage,mirrorField,settings.Get(mirrorPage)[mirrorField]+(key.Key == ConsoleKey.RightArrow ? step : -step));
        }
        return true;
    }

    public ConsoleDashboard()
    {
        Console.OutputEncoding = Encoding.UTF8;
        Console.Title = "AC FLIP 0.9  连接监视器";
        redirected = Console.IsOutputRedirected;
        if (!redirected)
        {
            Console.CursorVisible = false;
            // Quick Edit selection pauses the entire console process on classic hosts.
            DisableQuickEdit();
            try { Console.SetWindowSize(Math.Min(106, Console.LargestWindowWidth), Math.Min(44, Console.LargestWindowHeight)); }
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
        Dictionary<IPEndPoint, ClientInfo> clients, string gameRoot, bool raceReady, float pitLimit, string usbStatus, string mirrorStatus,MirrorSettings settings)
    {
        var b = new StringBuilder();
        b.AppendLine("AC FLIP 0.9   连接监视器");
        b.AppendLine("关闭本窗口即断开连接    Ctrl+C / Q 退出");
        b.AppendLine();
        b.AppendLine("电脑 IPv4"); b.AppendLine(addresses);
        b.AppendLine("监听: UDP 9876 / TCP 9877    USB 优先，也支持局域网直传");
        b.AppendLine("USB: "+usbStatus);
        b.AppendLine("后视镜: "+mirrorStatus);
        if (mirrorPage == 0) b.AppendLine("M 调整后视视野");
        else {
            b.AppendLine("后视视野："+(mirrorPage == 1 ? "左" : mirrorPage == 2 ? "中" : "右")+"    1 左  2 中  3 右");
            b.AppendLine("上下选项  左右调整  Shift 加速  Enter 输入  R 默认  Esc 返回");
            float[] values = settings.Get(mirrorPage);
            for (int i = 0;i < 6;i++) b.AppendLine((i == mirrorField ? " > " : "   ")+MirrorSettings.Names[i]+"："+values[i].ToString(i < 3 ? "0.0" : "0.00",CultureInfo.InvariantCulture)+(i < 3 ? "°" : " m")+(i == mirrorField && mirrorNumber != null ? "  输入："+mirrorNumber : ""));
            b.AppendLine("正值：朝右、抬头、右移、升高、前移");
        }
        if (settings.Error.Length != 0) b.AppendLine(settings.Error);
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
                "    " + (IPAddress.IsLoopback(pair.Key.Address) ? "有线 RTT: " : "无线 RTT: ") + (c.Rtt < 0 ? "等待测量" : c.Rtt + " ms") +
                "    心跳: " + (now-c.Seen).ToString("F1") + " s");
            if (IPAddress.IsLoopback(pair.Key.Address)) b.AppendLine("  无线 RTT: " + (c.WirelessRtt < 0 ? "未连接或等待测量" : c.WirelessRtt+" ms") + "    仅探测，不传仪表数据");
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
            int width = Math.Max(1, Console.WindowWidth-1), height = Math.Max(1, Console.WindowHeight-1);
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
