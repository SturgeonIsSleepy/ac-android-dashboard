using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Threading;

// Test-only sender, temporarily run at the already-allowed bridge executable path.
class VisualFixture
{
    static void Main(string[] args)
    {
        Console.Title = "AC FLIP VISUAL CHECK";
        Console.WriteLine("外屏视觉验证  合成数据\n测试结束后恢复真实游戏桥接\nCtrl+C 退出");
        using (var udp = new UdpClient(new IPEndPoint(IPAddress.Any, 9876)))
        {
            udp.Client.IOControl(unchecked((int)0x9800000C), new byte[4], null);
            var clients = new Dictionary<IPEndPoint, DateTime>();
            int sequence = 2000000;
            byte[] packet = null;
            while (true)
            {
                while (udp.Available > 0)
                {
                    var peer = new IPEndPoint(IPAddress.Any, 0);
                    var hello = udp.Receive(ref peer);
                    if ((hello.Length != 16 && hello.Length != 84) || BitConverter.ToInt32(hello, 4) != 3) continue;
                    clients[peer] = DateTime.UtcNow;
                    var pong = new byte[16]; Array.Copy(hello, pong, 16);
                    Array.Copy(BitConverter.GetBytes(4), 0, pong, 4, 4); udp.Send(pong, 16, peer);
                }
                try { packet = File.ReadAllBytes(args.Length == 0 ? Path.Combine("artifacts","visual-0.3","fixture.bin") : args[0]); }
                catch (IOException) { }
                if (packet != null && (packet.Length == 412 || packet.Length == 420 || packet.Length == 944 || packet.Length == 964 || packet.Length == 1028 || packet.Length == 1032))
                {
                    Array.Copy(BitConverter.GetBytes(++sequence), 0, packet, 8, 4);
                    foreach (var peer in new List<IPEndPoint>(clients.Keys))
                    {
                        if ((DateTime.UtcNow-clients[peer]).TotalSeconds > 5) clients.Remove(peer);
                        else udp.Send(packet, packet.Length, peer);
                    }
                }
                Thread.Sleep(16);
            }
        }
    }
}
