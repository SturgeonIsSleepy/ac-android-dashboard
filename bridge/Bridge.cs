using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.MemoryMappedFiles;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using AssettoCorsaSharedMemory;

// AC's layouts come from the unmodified MIT-licensed library in vendor/.
class Bridge
{
    const int Magic = 0x31464341;
    const int Port = 9876;
    static string gameRoot;
    static bool demo;
    static int sequence;
    static int mapId;
    static float[][] route = new float[0][];
    static byte[] mapPacket;
    static string routeKey = "";
    static bool closed;
    static RallyGuide guide;
    static ConsoleDashboard dashboard;
    static DisplayTelemetry displayTelemetry;
    static RaceContext raceContext;
    static long sentBytes;
    static volatile bool running = true;
    delegate bool CloseHandler(uint signal);
    static readonly CloseHandler closeHandler = signal => { running = false; return true; };
    [DllImport("kernel32.dll")] static extern bool SetConsoleCtrlHandler(CloseHandler handler, bool add);

    [DllImport("winmm.dll")] static extern uint timeBeginPeriod(uint period);
    [DllImport("winmm.dll")] static extern uint timeEndPeriod(uint period);

    sealed class Page<T> : IDisposable where T : struct
    {
        readonly MemoryMappedFile file;
        readonly MemoryMappedViewAccessor view;
        readonly byte[] data = new byte[Marshal.SizeOf(typeof(T))];
        readonly GCHandle pin;
        public Page(string name)
        {
            file = MemoryMappedFile.OpenExisting("Local\\" + name, MemoryMappedFileRights.Read);
            view = file.CreateViewAccessor(0, data.Length, MemoryMappedFileAccess.Read);
            pin = GCHandle.Alloc(data, GCHandleType.Pinned);
        }
        public T Read()
        {
            // Copy a stable page when packetId changes during a read.
            for (int attempt = 0; attempt < 3; attempt++)
            {
                int before = view.ReadInt32(0);
                view.ReadArray(0, data, 0, data.Length);
                if (before == view.ReadInt32(0) && before == BitConverter.ToInt32(data, 0)) break;
            }
            return (T)Marshal.PtrToStructure(pin.AddrOfPinnedObject(), typeof(T));
        }
        public void Dispose() { pin.Free(); view.Dispose(); file.Dispose(); }
    }

    static int Main(string[] args)
    {
        timeBeginPeriod(1);
        SetConsoleCtrlHandler(closeHandler, true);
        try { return Run(args); }
        catch (SocketException e)
        {
            Console.WriteLine("无法监听 UDP 9876，请关闭已有桥接窗口后重试\n" + e.Message);
            if (!Console.IsInputRedirected) Console.ReadKey(true);
            return 1;
        }
        finally { SetConsoleCtrlHandler(closeHandler, false); timeEndPeriod(1); }
    }

    static int Run(string[] args)
    {
        gameRoot = @"D:\Program Files\steam\steamapps\common\assettocorsa";
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] == "--demo") demo = true;
            else if (args[i] == "--game-dir" && i + 1 < args.Length) gameRoot = args[++i];
            else { Console.Error.WriteLine("Usage: ACFlipBridge.exe [--demo] [--game-dir PATH]"); return 2; }
        }
        dashboard = new ConsoleDashboard();
        displayTelemetry = new DisplayTelemetry(gameRoot);
        raceContext = new RaceContext(gameRoot);
        Console.CancelKeyPress += delegate(object sender, ConsoleCancelEventArgs e) { e.Cancel = true; running = false; };
        using (UdpClient udp = new UdpClient(new IPEndPoint(IPAddress.Any, Port)))
        {
            // Windows reports ICMP from a closed phone/probe port on the next Receive.
            // Disable that UDP connection reset; other subscribers must keep receiving.
            udp.Client.IOControl(unchecked((int)0x9800000C), new byte[4], null);
            udp.Client.Blocking = false;
            var clients = new Dictionary<IPEndPoint, ClientInfo>();
            var clock = Stopwatch.StartNew();
            Page<Physics> physics = null;
            Page<Graphics> graphics = null;
            Page<StaticInfo> statics = null;
            double nextCheck = 0, nextSend = 0, nextRender = 0, renderTime = 0;
            long totalFrames = 0, renderedFrames = 0;
            bool gameRunning = false;
            if (demo) LoadRoute("monza", "");
            while (running)
            {
                double now = clock.Elapsed.TotalSeconds;
                if (!Console.IsInputRedirected && Console.KeyAvailable && Console.ReadKey(true).Key == ConsoleKey.Q) break;
                while (udp.Available > 0)
                {
                    IPEndPoint peer = new IPEndPoint(IPAddress.Any, 0);
                    byte[] hello = udp.Receive(ref peer);
                    if ((hello.Length != 16 && hello.Length != 84) || BitConverter.ToInt32(hello, 0) != Magic || BitConverter.ToInt32(hello, 4) != 3) continue;
                    if (!clients.ContainsKey(peer)) clients[peer] = new ClientInfo();
                    var client = clients[peer]; client.Seen = now;
                    if (hello.Length == 84)
                    {
                        client.Rtt = BitConverter.ToInt64(hello, 16);
                        client.Received = BitConverter.ToInt64(hello, 24); client.Lost = BitConverter.ToInt64(hello, 32);
                        client.Width = BitConverter.ToInt32(hello, 40); client.Height = BitConverter.ToInt32(hello, 44);
                        client.Display = BitConverter.ToInt32(hello, 48);
                        client.Device = Encoding.UTF8.GetString(hello, 52, 32).TrimEnd('\0');
                    }
                    byte[] pong = new byte[16]; Array.Copy(hello, pong, 16);
                    Array.Copy(BitConverter.GetBytes(4), 0, pong, 4, 4);
                    Send(udp, pong, peer);
                    if (mapPacket != null) Send(udp, mapPacket, peer);
                }
                if (now < nextSend) { Thread.Sleep(1); continue; }
                nextSend += 1.0 / 60.0;
                if (nextSend < now) nextSend = now + 1.0 / 60.0;
                if (!demo && now >= nextCheck)
                {
                    nextCheck = now + 1;
                    var processes = Process.GetProcessesByName("acs");
                    gameRunning = processes.Length > 0;
                    foreach (var process in processes) process.Dispose();
                    if (!gameRunning && physics != null)
                    {
                        physics.Dispose(); graphics.Dispose(); statics.Dispose();
                        physics = null; graphics = null; statics = null;
                        routeKey = ""; mapId = 0; mapPacket = null; guide = null;
                    }
                    if (gameRunning && physics == null)
                    {
                        try
                        {
                            physics = new Page<Physics>("acpmf_physics");
                            graphics = new Page<Graphics>("acpmf_graphics");
                            statics = new Page<StaticInfo>("acpmf_static");
                        }
                        catch (FileNotFoundException)
                        {
                            if (physics != null) physics.Dispose();
                            if (graphics != null) graphics.Dispose();
                            if (statics != null) statics.Dispose();
                            physics = null; graphics = null; statics = null;
                        }
                    }
                }
                Physics p = new Physics(); Graphics g = new Graphics(); StaticInfo s = new StaticInfo();
                if (demo) MakeDemo(now, out p, out g, out s);
                else if (physics != null)
                {
                    p = physics.Read(); g = graphics.Read(); s = statics.Read();
                    if (g.Status != AC_STATUS.AC_OFF) LoadRoute(s.Track, s.TrackConfiguration);
                }
                byte[] frame = Telemetry(p, g, s);
                totalFrames++;
                foreach (var peer in new List<IPEndPoint>(clients.Keys))
                {
                    if (now - clients[peer].Seen > 5) { clients.Remove(peer); continue; }
                    if (Send(udp, frame, peer)) clients[peer].Sent++;
                }
                if (now >= nextRender)
                {
                    string status = demo ? "演示数据" : g.Status == AC_STATUS.AC_OFF ? "等待游戏" :
                        g.Status == AC_STATUS.AC_PAUSE ? "暂停" : g.Status == AC_STATUS.AC_REPLAY ? "回放" : "实时";
                    dashboard.Render(now, status, s.Track ?? "—", s.CarModel ?? "—", p.Rpms, p.Gear, p.SpeedKmh,
                        route.Length, guide == null ? 0 : guide.Cues.Count, totalFrames, sentBytes,
                        now <= renderTime ? 0 : (totalFrames-renderedFrames)/(now-renderTime), clients, gameRoot,
                        BitConverter.ToInt32(frame,428) == 1, raceContext.PitLimit);
                    renderedFrames = totalFrames; renderTime = now; nextRender = now + .5;
                }
            }
            if (physics != null) { physics.Dispose(); graphics.Dispose(); statics.Dispose(); }
        }
        return 0;
    }

    static bool Send(UdpClient udp, byte[] data, IPEndPoint peer)
    {
        try { sentBytes += udp.Send(data, data.Length, peer); return true; }
        catch (SocketException e) { dashboard.LastError = peer + " " + e.SocketErrorCode; return false; }
    }

    static void WriteName(BinaryWriter w, string name)
    {
        var data = new byte[64];
        byte[] text = Encoding.UTF8.GetBytes(name ?? "");
        Array.Copy(text, data, Math.Min(text.Length, 63));
        w.Write(data);
    }

    static byte[] Telemetry(Physics p, Graphics g, StaticInfo s)
    {
        using (var stream = new MemoryStream(1032))
        using (var w = new BinaryWriter(stream))
        {
            w.Write(Magic); w.Write(1); w.Write(sequence++); w.Write(mapId);
            w.Write((int)g.Status | (demo ? 256 : 0)); w.Write(p.Gear); w.Write(p.Rpms); w.Write(s.MaxRpm);
            w.Write(p.SpeedKmh); w.Write(p.Fuel); w.Write(s.MaxFuel);
            w.Write(g.iCurrentTime); w.Write(g.iLastTime); w.Write(g.iBestTime); w.Write(g.CompletedLaps);
            w.Write((g.IsInPit != 0 ? 1 : 0) | (g.IsInPitLane != 0 ? 2 : 0));
            w.Write(p.NumberOfTyresOut); w.Write((int)g.Flag); w.Write(p.PerformanceMeter);
            w.Write(g.CarCoordinates == null ? 0 : g.CarCoordinates[0]);
            w.Write(g.CarCoordinates == null ? 0 : g.CarCoordinates[2]); w.Write(p.Heading);
            WriteFloats(w, p.TyreCoreTemperature, 4);
            WriteFloats(w, p.WheelsPressure, 4);
            WriteFloats(w, p.CarDamage, 5);
            WriteName(w, s.Track + (String.IsNullOrEmpty(s.TrackConfiguration) ? "" : "/" + s.TrackConfiguration));
            WriteName(w, s.CarModel);
            // v0.2 extension. The original 268-byte prefix retains its offsets.
            WriteFloats(w, p.TyreWear, 4);
            w.Write(g.CurrentSectorIndex); w.Write(g.LastSectorTime); w.Write(s.SectorCount);
            w.Write(g.NormalizedCarPosition);
            w.Write((s.HasDRS != 0 ? 1 : 0) | (p.DrsAvailable != 0 ? 2 : 0) | (p.DrsEnabled != 0 ? 4 : 0));
            float[] gaps = new float[0];
            var cues = guide == null ? new RallyGuide.Cue[0] : guide.Upcoming(g.NormalizedCarPosition, out gaps);
            w.Write(cues.Length);
            for (int i = 0; i < 3; i++)
            {
                w.Write(i < cues.Length ? cues[i].Direction : 0);
                w.Write(i < cues.Length ? cues[i].Grade : 0);
                w.Write(i < gaps.Length ? gaps[i] : -1f);
            }
            w.Write(g.Position);
            WriteName(w, String.IsNullOrWhiteSpace(s.PlayerNick) ? ((s.PlayerName ?? "") + " " + (s.PlayerSurname ?? "")).Trim() : s.PlayerNick);
            w.Write(displayTelemetry.RpmScale(s.CarModel, s.MaxRpm));
            w.Write(s.HasDRS == 0 ? -1f : displayTelemetry.DrsDistance(s.Track, s.TrackConfiguration, g.NormalizedCarPosition,
                    s.TrackSPlineLength > 0 ? s.TrackSPlineLength : guide == null ? 0 : guide.Length, p.DrsAvailable != 0 || p.DrsEnabled != 0));
            bool context = raceContext.Read(s.CarModel,s.Track,g.CompletedLaps,g.CurrentSectorIndex);
            w.Write(context ? raceContext.Cars : Math.Max(1,s.NumCars)); w.Write(raceContext.PitLimit);
            w.Write(context ? 1 : 0);
            for (int i = 0; i < 64; i++) w.Write(context ? raceContext.Personal[i] : 0);
            for (int i = 0; i < 64; i++) w.Write(context ? raceContext.World[i] : 0);
            w.Write(p.RoadTemp);
            w.Write(raceContext.EnvironmentFresh ? raceContext.Grip : -1f);
            w.Write(raceContext.EnvironmentFresh ? raceContext.Wetness : -1f);
            w.Write(raceContext.EnvironmentFresh ? raceContext.Water : -1f);
            w.Write(raceContext.EnvironmentFresh && s.AidFuelRate > 0 ? raceContext.FuelPerLap : 0f);
            WriteName(w,g.TyreCompound);
            w.Write(s.TrackSPlineLength > 0 ? s.TrackSPlineLength : guide == null ? 0 : guide.Length);
            return stream.ToArray();
        }
    }

    static void WriteFloats(BinaryWriter writer, float[] array, int count)
    {
        for (int i = 0; i < count; i++) writer.Write(array == null ? 0 : array[i]);
    }

    static void LoadRoute(string track, string configuration)
    {
        string key = (track ?? "") + "/" + (configuration ?? "");
        if (key == routeKey) return;
        routeKey = key;
        uint hash = 2166136261;
        foreach (byte b in Encoding.UTF8.GetBytes(key)) hash = (hash ^ b) * 16777619;
        mapId = (int)hash;
        route = new float[0][];
        closed = false;
        guide = null;
        // Track metadata is untrusted: don't follow paths outside content/tracks.
        if (String.IsNullOrEmpty(track) || track.Contains("..") || track.IndexOfAny(new[] {'/', '\\', ':'}) >= 0 ||
            (configuration ?? "").Contains("..") || (configuration ?? "").IndexOfAny(new[] {'/', '\\', ':'}) >= 0) return;
        string folder = Path.Combine(gameRoot, "content", "tracks", track);
        if (!String.IsNullOrEmpty(configuration)) folder = Path.Combine(folder, configuration);
        string path = Path.Combine(folder, "ai", "fast_lane.ai");
        try
        {
            if (File.Exists(path))
            using (var reader = new BinaryReader(File.OpenRead(path)))
            {
                int version = reader.ReadInt32(), count = reader.ReadInt32();
                reader.ReadInt32(); reader.ReadInt32();
                if (version != 7 || count < 2 || count > 1000000 || reader.BaseStream.Length < 16L + 20L * count)
                    throw new InvalidDataException("Unsupported AI route header");
                var full = new float[count][];
                for (int i = 0; i < count; i++)
                {
                    float x = reader.ReadSingle(); reader.ReadSingle(); float z = reader.ReadSingle();
                    reader.ReadSingle(); reader.ReadInt32(); full[i] = new[] { x, z };
                }
                int sampled = Math.Min(128, count);
                route = new float[sampled][];
                for (int i = 0; i < sampled; i++)
                {
                    int index = (int)((long)i * (count - 1) / (sampled - 1));
                    route[i] = full[index];
                }
                float dx = route[0][0] - route[sampled-1][0], dz = route[0][1] - route[sampled-1][1];
                closed = dx * dx + dz * dz < 2500;
                guide = new RallyGuide(full, closed);
            }
        }
        catch (IOException e) { dashboard.LastError = "路线不可用: " + e.Message; route = new float[0][]; guide = null; }
        using (var stream = new MemoryStream())
        using (var w = new BinaryWriter(stream))
        {
            w.Write(Magic); w.Write(2); w.Write(mapId); w.Write(route.Length); w.Write(closed ? 1 : 0);
            foreach (var point in route) { w.Write(point[0]); w.Write(point[1]); }
            mapPacket = stream.ToArray();
        }
    }

    static void MakeDemo(double time, out Physics p, out Graphics g, out StaticInfo s)
    {
        int rpm = 1800 + (int)((time % 8) / 8 * 6000);
        int index = route.Length == 0 ? 0 : (int)(time / 84 * route.Length) % route.Length;
        float x = route.Length == 0 ? 0 : route[index][0], z = route.Length == 0 ? 0 : route[index][1];
        p = new Physics { Gear = 2 + (int)(time / 8) % 6, Rpms = rpm, SpeedKmh = 112 + (float)(time % 8) * 12,
            Fuel = (int)(time / 15) % 3 == 1 ? 2.3f : 28.5f, TyreCoreTemperature = new[] { 82f, 86f, 89f, 87f },
            WheelsPressure = new[] { 26.2f, 26.4f, 26.5f, 26.3f }, CarDamage = new[] { 0f, 0f, 0f, 0f, 0f },
            TyreWear = new[] { 100f, 60f, 90f, 10f },
            PerformanceMeter = -0.248f, NumberOfTyresOut = (int)(time / 15) % 3 == 2 ? 3 : 0 };
        g = new Graphics { Status = AC_STATUS.AC_LIVE, iCurrentTime = (int)(time % 84 * 1000), iLastTime = 84923,
            iBestTime = 84271, CompletedLaps = (int)(time / 84) + 3, CarCoordinates = new[] { x, 0f, z },
            CurrentSectorIndex = (int)(time % 84 / 28), LastSectorTime = 28000, NormalizedCarPosition = (float)(time % 84 / 84) };
        s = new StaticInfo { Track = "monza", CarModel = "DEMO GT3", MaxRpm = 8000, MaxFuel = 100, SectorCount = 3 };
    }
}
