using System;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;

class BridgeSocketLifetimeCheck
{
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool SetHandleInformation(IntPtr handle, uint mask, uint flags);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool GetHandleInformation(IntPtr handle, out uint flags);

    static int Main(string[] args)
    {
        if (args.Length > 0) { Console.WriteLine("ready"); Console.ReadLine(); return 0; }
        Check(false);
        Check(true);
        Console.WriteLine("Inherited UDP/TCP ports reproduced; protected ports released while child remained alive");
        return 0;
    }

    static void Check(bool protect)
    {
        using (var udp = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0)))
        {
            var tcp = new TcpListener(IPAddress.Loopback, 0);
            tcp.Start();
            int udpPort = ((IPEndPoint)udp.Client.LocalEndPoint).Port;
            int tcpPort = ((IPEndPoint)tcp.LocalEndpoint).Port;
            try
            {
                foreach (Socket socket in new[] { udp.Client, tcp.Server })
                {
                    if (!SetHandleInformation(socket.Handle, 1, 1)) throw new Exception("Cannot create inheritable fixture");
                    if (protect) BridgeSockets.PreventInheritance(socket);
                    uint flags;
                    if (!GetHandleInformation(socket.Handle, out flags) || ((flags & 1) == 0) != protect)
                        throw new Exception("Wrong socket inheritance flag");
                }
                var info = new ProcessStartInfo(Assembly.GetExecutingAssembly().Location, "child") {
                    UseShellExecute = false, CreateNoWindow = true,
                    RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true
                };
                using (var child = Process.Start(info))
                {
                    try
                    {
                        if (child.StandardOutput.ReadLine() != "ready") throw new Exception("Child did not start");
                        udp.Close(); tcp.Stop();
                        if (child.HasExited) throw new Exception("Child exited before port checks");
                        bool udpBound = false, tcpBound = false;
                        try { using (var rebound = new UdpClient(new IPEndPoint(IPAddress.Loopback, udpPort))) udpBound = true; }
                        catch (SocketException e) { if (e.SocketErrorCode != SocketError.AddressAlreadyInUse) throw; }
                        var listener = new TcpListener(IPAddress.Loopback, tcpPort);
                        try { listener.Start(); tcpBound = true; }
                        catch (SocketException e) { if (e.SocketErrorCode != SocketError.AddressAlreadyInUse) throw; }
                        finally { listener.Stop(); }
                        if (udpBound != protect || tcpBound != protect) throw new Exception("Unexpected port lifetime");
                    }
                    finally
                    {
                        child.StandardInput.WriteLine("exit");
                        if (!child.WaitForExit(2000)) child.Kill();
                    }
                }
            }
            finally { tcp.Stop(); }
        }
    }
}
