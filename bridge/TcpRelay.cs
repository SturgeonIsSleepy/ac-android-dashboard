using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

// Latest-frame TCP relay. ADB reverse exposes this same listener over USB.
sealed class TcpRelay : IDisposable
{
    readonly TcpListener listener;
    readonly List<Peer> peers = new List<Peer>();
    readonly Stopwatch clock = Stopwatch.StartNew();
    readonly MirrorSource mirror;
    volatile byte[] latest, map;
    volatile bool closed;
    long sentBytes;
    public long SentBytes { get { return Interlocked.Read(ref sentBytes); } }
    public TcpRelay(int port, MirrorSource source)
    {
        mirror = source;
        listener = new TcpListener(IPAddress.Any,port); listener.Start();
        new Thread(Accept) { IsBackground = true, Name = "ACFlip-TCP-accept" }.Start();
    }
    void Accept()
    {
        while (!closed)
        {
            try
            {
                var peer = new Peer(this,listener.AcceptTcpClient());
                lock (peers) peers.Add(peer);
                peer.Start();
            }
            catch (SocketException) { if (closed) return; }
            catch (ObjectDisposedException) { return; }
        }
    }
    public void Publish(byte[] frame, byte[] route) {
        map = route; latest = frame;
        int selected = 0;
        lock (peers) foreach (var peer in peers) if (peer.Alive && peer.Subscribed && peer.MirrorView != 0) selected = peer.MirrorView;
        mirror.Selected = selected;
    }
    public Dictionary<IPEndPoint,ClientInfo> Clients(double now)
    {
        var result = new Dictionary<IPEndPoint,ClientInfo>();
        lock (peers)
        {
            peers.RemoveAll(p => !p.Alive);
            foreach (var peer in peers)
            {
                lock (peer.Info)
                {
                    if (!peer.Subscribed) continue;
                    var c = peer.Info;
                    result[peer.Endpoint] = new ClientInfo { Seen = now-(clock.Elapsed.TotalSeconds-c.Seen),
                        Rtt = c.Rtt, Received = c.Received, Lost = c.Lost, Sent = c.Sent, Width = c.Width,
                        Height = c.Height, Display = c.Display, Device = c.Device, WirelessRtt = c.WirelessRtt };
                }
            }
        }
        return result;
    }
    public void Dispose()
    {
        closed = true; listener.Stop();
        lock (peers) foreach (var peer in peers) peer.Close();
    }
    sealed class Peer
    {
        readonly TcpRelay owner;
        readonly TcpClient client;
        readonly NetworkStream stream;
        readonly object send = new object();
        public readonly ClientInfo Info = new ClientInfo();
        public readonly IPEndPoint Endpoint;
        public volatile bool Alive = true, Subscribed, SettingsSupported;
        public volatile int MirrorView;
        public Peer(TcpRelay parent, TcpClient socket)
        {
            owner = parent; client = socket; client.NoDelay = true;
            client.SendBufferSize = 8192; client.SendTimeout = 500; client.ReceiveTimeout = 3000;
            Endpoint = (IPEndPoint)client.Client.RemoteEndPoint; stream = client.GetStream();
        }
        public void Start()
        {
            new Thread(Read) { IsBackground = true, Name = "ACFlip-TCP-read" }.Start();
            new Thread(Write) { IsBackground = true, Name = "ACFlip-TCP-write" }.Start();
        }
        void Read()
        {
            try
            {
                while (Alive && !owner.closed)
                {
                    byte[] prefix = ReadBytes(4); int size = BitConverter.ToInt32(prefix,0);
                    if (size != 84 && size != 92 && size != 96 && size != 100 && size != 20 && size != 16) break;
                    byte[] hello = ReadBytes(size);
                    if (BitConverter.ToInt32(hello,0) != 0x31464341) break;
                    int kind = BitConverter.ToInt32(hello,4);
                    if (size == 20 && kind == 9 && Subscribed && SettingsSupported) {
                        int view = BitConverter.ToInt32(hello,8), field = BitConverter.ToInt32(hello,12);
                        if (field == 6) owner.mirror.Settings.Reset(view); else owner.mirror.Settings.Set(view,field,BitConverter.ToSingle(hello,16));
                        continue;
                    }
                    if (kind != 3 || size == 20) break;
                    lock (Info)
                    {
                        Info.Seen = owner.clock.Elapsed.TotalSeconds;
                        if (size >= 84)
                        {
                            Info.Rtt = BitConverter.ToInt64(hello,16); Info.Received = BitConverter.ToInt64(hello,24);
                            Info.Lost = BitConverter.ToInt64(hello,32); Info.Width = BitConverter.ToInt32(hello,40);
                            Info.Height = BitConverter.ToInt32(hello,44); Info.Display = BitConverter.ToInt32(hello,48);
                            Info.Device = Encoding.UTF8.GetString(hello,52,32).TrimEnd('\0')+(IPAddress.IsLoopback(Endpoint.Address) ? "  USB" : "  TCP");
                            Info.WirelessRtt = size >= 92 ? BitConverter.ToInt64(hello,84) : -1;
                            int requested = size >= 96 ? BitConverter.ToInt32(hello,92) : 0;
                            MirrorView = requested >= 0 && requested <= 3 ? requested : 0;
                            SettingsSupported = size == 100 && BitConverter.ToInt32(hello,96) == 1;
                        }
                        Subscribed = true;
                    }
                    var pong = new byte[16]; Array.Copy(hello,pong,16); Array.Copy(BitConverter.GetBytes(4),0,pong,4,4); Send(pong);
                }
            }
            catch (IOException) { }
            catch (SocketException) { }
            catch (ObjectDisposedException) { }
            finally { Close(); }
        }
        byte[] ReadBytes(int size)
        {
            var bytes = new byte[size]; int used = 0;
            while (used < size) { int count = stream.Read(bytes,used,size-used); if (count == 0) throw new EndOfStreamException(); used += count; }
            return bytes;
        }
        void Write()
        {
            byte[] previous = null, previousMap = null, previousMirror = null, previousSettings = null, previousSettingsStatus = null;
            try
            {
                while (Alive && !owner.closed)
                {
                    byte[] frame = owner.latest, route = owner.map;
                    byte[] settings = owner.mirror.Settings.Packet;
                    if (Subscribed && SettingsSupported && settings != previousSettings) { Send(settings); previousSettings = settings; }
                    byte[] settingsStatus = owner.mirror.Settings.ErrorPacket;
                    if (Subscribed && SettingsSupported && settingsStatus != previousSettingsStatus) { Send(settingsStatus); previousSettingsStatus = settingsStatus; }
                    if (Subscribed && route != null && route != previousMap) { Send(route); previousMap = route; }
                    if (Subscribed && frame != null && frame != previous)
                    {
                        Send(frame); previous = frame;
                        lock (Info) Info.Sent++;
                    }
                    byte[] image = owner.mirror.Latest;
                    if (Subscribed && MirrorView != 0 && image != null && image != previousMirror && BitConverter.ToInt32(image,8) == MirrorView) {
                        Send(image); previousMirror = image;
                    }
                    Thread.Sleep(2);
                }
            }
            catch (IOException) { }
            catch (SocketException) { }
            catch (ObjectDisposedException) { }
            finally { Close(); }
        }
        void Send(byte[] body)
        {
            var packet = new byte[body.Length+4]; Array.Copy(BitConverter.GetBytes(body.Length),packet,4); Array.Copy(body,0,packet,4,body.Length);
            lock (send) stream.Write(packet,0,packet.Length);
            Interlocked.Add(ref owner.sentBytes,packet.Length);
        }
        public void Close() { Alive = false; client.Close(); }
    }
}
