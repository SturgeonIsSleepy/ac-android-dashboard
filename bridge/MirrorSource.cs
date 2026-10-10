using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Text;
using System.Threading;

sealed class MirrorSource : IDisposable
{
    readonly string folder;
    volatile bool closed;
    public volatile int Selected;
    public volatile byte[] Latest;
    public volatile string Status = "关闭";
    readonly ImageCodecInfo jpeg;
    public readonly MirrorSettings Settings;
    public MirrorSource(string gameRoot) {
        folder = Path.Combine(gameRoot,"apps","lua","acflip_relay");
        Settings = new MirrorSettings(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"mirror-settings.txt"));
        foreach (var codec in ImageCodecInfo.GetImageEncoders()) if (codec.MimeType == "image/jpeg") jpeg = codec;
        new Thread(Read) { IsBackground = true, Name = "ACFlip-mirror" }.Start();
    }
    void Request(int view) {
        string path = Path.Combine(folder,"mirror-request.txt"), temporary = path+".next";
        long stamp = (long)(DateTime.UtcNow-new DateTime(1970,1,1)).TotalSeconds;
        File.WriteAllText(temporary,view+","+stamp,new UTF8Encoding(false));
        if (File.Exists(path)) File.Replace(temporary,path,null); else File.Move(temporary,path);
    }
    void Read() {
        var clock = Stopwatch.StartNew(); double nextRequest = 0;
        DateTime previous = DateTime.MinValue, selectedAt = DateTime.UtcNow; int oldView = 0, sequence = 0;
        string previousSettings = null;
        while (!closed) {
            int view = Selected;
            try {
                string configText = Settings.Text;
                if (configText != previousSettings && Directory.Exists(folder)) {
                    string configPath = Path.Combine(folder,"mirror-config.txt"), next = configPath+".next";
                    File.WriteAllText(next,configText,new UTF8Encoding(false));
                    if (File.Exists(configPath)) File.Replace(next,configPath,null); else File.Move(next,configPath);
                    previousSettings = configText;
                }
                if (view != oldView) { Latest = null; previous = DateTime.MinValue; selectedAt = DateTime.UtcNow; oldView = view; nextRequest = 0; }
                if (view != 0 || nextRequest == 0) {
                    if (clock.Elapsed.TotalSeconds >= nextRequest) { Request(view); nextRequest = clock.Elapsed.TotalSeconds+.5; }
                }
                if (view == 0) { Status = "关闭"; Thread.Sleep(20); continue; }
                string path = Path.Combine(folder,"mirror-"+view+".jpg");
                var file = new FileInfo(path);
                if (!file.Exists || file.LastWriteTimeUtc < selectedAt || DateTime.UtcNow-file.LastWriteTimeUtc > TimeSpan.FromSeconds(1)) {
                    Latest = null; Status = "等待游戏镜面，请检查采集应用"; Thread.Sleep(20); continue;
                }
                if (file.LastWriteTimeUtc != previous) {
                    byte[] jpg;
                    using (var input = new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.ReadWrite|FileShare.Delete)) {
                        if (input.Length < 4 || input.Length > 2000000) { Thread.Sleep(20); continue; }
                        jpg = new byte[(int)input.Length]; int used = 0;
                        while (used < jpg.Length) { int count = input.Read(jpg,used,jpg.Length-used); if (count == 0) break; used += count; }
                        if (used != jpg.Length) continue;
                    }
                    if (jpg[0] != 255 || jpg[1] != 216 || jpg[jpg.Length-2] != 255 || jpg[jpg.Length-1] != 217) continue;
                    using (var input = new MemoryStream(jpg))
                    using (var image = Image.FromStream(input))
                    using (var encoded = new MemoryStream())
                    using (var settings = new EncoderParameters(1)) {
                        settings.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality,84L);
                        image.Save(encoded,jpeg,settings); jpg = encoded.ToArray();
                    }
                    if (jpg.Length > 512000) continue;
                    byte[] frame = new byte[jpg.Length+16];
                    Array.Copy(BitConverter.GetBytes(0x31464341),0,frame,0,4); Array.Copy(BitConverter.GetBytes(6),0,frame,4,4);
                    Array.Copy(BitConverter.GetBytes(view),0,frame,8,4); Array.Copy(BitConverter.GetBytes(sequence++),0,frame,12,4);
                    Array.Copy(jpg,0,frame,16,jpg.Length); Latest = frame; previous = file.LastWriteTimeUtc;
                    Status = (view == 1 ? "左侧" : view == 2 ? "中间" : "右侧")+"  独立后视画面  "+(jpg.Length/1024)+" KB/帧";
                }
            } catch (IOException) { Status = "等待游戏镜面"; }
            catch (UnauthorizedAccessException) { Status = "无法读取游戏采集目录"; }
            catch (ArgumentException) { Status = "等待有效后视图像"; }
            Thread.Sleep(10);
        }
    }
    public void Dispose() { closed = true; try { if (Directory.Exists(folder)) Request(0); } catch (IOException) { } }
}
