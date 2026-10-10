package cn.acflip.dash;

import android.os.SystemClock;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;

final class UdpFeed {
    volatile Telemetry latest;
    volatile TrackMap map;
    volatile long rttMillis = -1, wirelessRttMillis = -1, received, lost;
    volatile String error = "", transport = "";
    volatile int width, height, display;
    volatile int mirrorView;
    volatile MirrorFrame mirrorFrame;
    volatile long mirrorFrames;
    volatile MirrorConfig mirrorConfig = MirrorConfig.defaults();
    volatile boolean mirrorSettingsReady;
    volatile boolean mirrorSettingsError;
    private final LinkedHashMap<Integer,MirrorCommand> mirrorCommands = new LinkedHashMap<>();
    private long mirrorCommandOrder;
    private final long[] mirrorResetOrder = new long[3];
    private static final class MirrorCommand {
        final int view, key; final long order; final byte[] bytes;
        MirrorCommand(int mirror,int parameter,float value,long sequence) {
            view = mirror; key = mirror*7+parameter; order = sequence;
            bytes = MirrorConfig.command(mirror,parameter,value);
        }
    }
    synchronized boolean setMirrorParameter(int view,int field,float value) {
        if (!mirrorSettingsReady || view < 1 || view > 3 || field < 0 || field > 6 || !Float.isFinite(value)) return false;
        if (field < 6 && (value < MirrorConfig.MIN[field] || value > MirrorConfig.MAX[field])) return false;
        MirrorCommand command = new MirrorCommand(view,field,value,++mirrorCommandOrder);
        if (field == 6) {
            for (int old = 0; old < 7; old++) mirrorCommands.remove(view*7+old);
            mirrorResetOrder[view-1] = command.order;
        }
        mirrorCommands.put(command.key,command); return true;
    }
    private synchronized MirrorCommand nextMirrorCommand() {
        if (!mirrorSettingsReady) return null;
        if (mirrorCommands.isEmpty()) return null;
        Integer key = mirrorCommands.keySet().iterator().next(); return mirrorCommands.remove(key);
    }
    private synchronized void restoreMirrorCommand(MirrorCommand command) {
        if (mirrorCommands.containsKey(command.key) || command.order < mirrorResetOrder[command.view-1]) return;
        LinkedHashMap<Integer,MirrorCommand> newer = new LinkedHashMap<>(mirrorCommands);
        mirrorCommands.clear(); mirrorCommands.put(command.key,command); mirrorCommands.putAll(newer);
    }
    static final class MirrorFrame {
        final Bitmap bitmap; final long receivedNanos; final int view;
        MirrorFrame(Bitmap image, long now, int selected) { bitmap = image; receivedNanos = now; view = selected; }
    }
    private volatile int generation;
    private Closeable connection;
    private Thread thread;

    synchronized void start(String host) {
        stop();
        int token = generation;
        thread = new Thread(() -> receive(host,token),"ACFlip-feed"); thread.start();
    }
    synchronized void stop() {
        generation++;
        if (connection != null) try { connection.close(); } catch (IOException e) { Log.w("ACFlip","Close",e); }
        connection = null;
        if (thread != null) thread.interrupt();
        thread = null; transport = ""; mirrorSettingsReady = false;
    }
    private boolean active(int token) { return token == generation && !Thread.currentThread().isInterrupted(); }
    private synchronized boolean own(Closeable socket, int token) {
        if (!active(token)) return false;
        connection = socket; mirrorSettingsReady = false; return true;
    }
    private synchronized void release(Closeable socket) {
        if (connection == socket) { connection = null; transport = ""; mirrorSettingsReady = false; }
    }
    private byte[] hello(boolean usb, boolean tcp) {
        ByteBuffer b = ByteBuffer.allocate(tcp ? 100 : usb ? 92 : 84).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(Telemetry.MAGIC).putInt(3).putLong(SystemClock.elapsedRealtimeNanos()).putLong(rttMillis)
                .putLong(received).putLong(lost).putInt(width).putInt(height).putInt(display);
        byte[] model = android.os.Build.MODEL.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        b.put(model,0,Math.min(31,model.length));
        if (b.capacity() >= 92) b.putLong(84,wirelessRttMillis);
        if (b.capacity() >= 96) b.putInt(92,mirrorView);
        if (b.capacity() == 100) b.putInt(96,1);
        return b.array();
    }
    private synchronized boolean accept(byte[] bytes, int length, int token, String link) {
        if (!active(token) || length < 8) return false;
        ByteBuffer b = ByteBuffer.wrap(bytes,0,length).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt() != Telemetry.MAGIC) return false;
        int kind = b.getInt(); long now = SystemClock.elapsedRealtimeNanos();
        if (kind == 1) {
            Telemetry next = Telemetry.parse(bytes,length,now); if (next == null) return false;
            Telemetry previous = latest;
            if (previous != null && now-previous.receivedNanos < 1_000_000_000L) {
                int gap = next.sequence-previous.sequence; if (gap <= 0) return true;
                lost += gap-1;
            }
            received++; latest = next;
        } else if (kind == 2) {
            TrackMap next = TrackMap.parse(bytes,length); if (next == null) return false; map = next;
        } else if (kind == 4 && length == 16) {
            long sent = b.getLong(); if (sent <= now && now-sent < 5_000_000_000L) rttMillis = (now-sent)/1_000_000L;
        } else if (kind == 8) {
            if (length != 84 || !(link.equals("USB") || link.equals("TCP"))) return false;
            MirrorConfig settings = MirrorConfig.parse(bytes); if (settings == null) return false;
            mirrorConfig = settings; mirrorSettingsReady = true;
        } else if (kind == 10) {
            if (length != 12 || !(link.equals("USB") || link.equals("TCP"))) return false;
            int status = b.getInt(); if (status != 0 && status != 1) return false;
            mirrorSettingsError = status == 1;
        } else if (kind == 6 && length > 16) {
            int selected = b.getInt();
            if (selected != mirrorView) return true;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes,16,length-16);
            if (bitmap == null) return false;
            mirrorFrame = new MirrorFrame(bitmap,now,selected); mirrorFrames++;
        } else return false;
        error = ""; transport = link; return true;
    }
    private void receive(String host, int token) {
        while (active(token)) {
            if (tcp("127.0.0.1",host,token,"USB")) continue;
            if (!active(token)) return;
            if (!host.trim().isEmpty() && tcp(host,host,token,"TCP")) continue;
            if (!active(token)) return;
            if (host.trim().isEmpty()) error = "长按屏幕设置电脑 IP";
            else udp(host,token);
            if (active(token)) try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
    private boolean tcp(String host, String wirelessHost, int token, String link) {
        boolean valid = false;
        Socket socket = new Socket();
        try (Socket close = socket) {
            if (!own(socket,token)) return false;
            socket.connect(new InetSocketAddress(host,9877),350);
            socket.setTcpNoDelay(true); socket.setSoTimeout(1500);
            if (link.equals("USB") && !wirelessHost.trim().isEmpty())
                new Thread(() -> wirelessProbe(wirelessHost,token,socket),"ACFlip-wireless-RTT").start();
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            long nextHello = 0; int sentMirror = -1;
            while (active(token)) {
                long now = SystemClock.elapsedRealtimeNanos();
                if (now >= nextHello || sentMirror != mirrorView) {
                    if (!link.equals("USB") && usbAvailable()) break;
                    byte[] packet = hello(link.equals("USB"),true); output.writeInt(Integer.reverseBytes(packet.length)); output.write(packet); output.flush();
                    nextHello = now+1_000_000_000L;
                    sentMirror = mirrorView;
                }
                MirrorCommand command;
                while ((command = nextMirrorCommand()) != null) {
                    try { output.writeInt(Integer.reverseBytes(command.bytes.length)); output.write(command.bytes); output.flush(); }
                    catch (IOException e) { restoreMirrorCommand(command); throw e; }
                }
                int size = Integer.reverseBytes(input.readInt()); if (size < 8 || size > 512016) break;
                byte[] packet = new byte[size]; input.readFully(packet);
                if (!accept(packet,size,token,link)) break;
                valid = true;
            }
        } catch (IOException e) { if (active(token) && valid) Log.w("ACFlip",link+" reconnect",e); }
        finally { release(socket); }
        return valid;
    }
    private boolean usbAvailable() {
        try (Socket probe = new Socket()) { probe.connect(new InetSocketAddress("127.0.0.1",9877),50); return true; }
        catch (IOException e) { return false; }
    }
    private void wirelessProbe(String host, int token, Socket usb) {
        wirelessRttMillis = -1;
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(InetAddress.getByName(host),9876); probe.setSoTimeout(300);
            byte[] bytes = new byte[16]; DatagramPacket reply = new DatagramPacket(bytes,bytes.length);
            long nextPing = 0, lastPong = 0;
            while (active(token) && !usb.isClosed()) {
                long now = SystemClock.elapsedRealtimeNanos();
                if (now >= nextPing) {
                    byte[] ping = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(Telemetry.MAGIC).putInt(7).putLong(now).array();
                    probe.send(new DatagramPacket(ping,ping.length)); nextPing = now+1_000_000_000L;
                }
                try {
                    reply.setLength(bytes.length); probe.receive(reply);
                    ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    if (reply.getLength() == 16 && b.getInt() == Telemetry.MAGIC && b.getInt() == 4) {
                        now = SystemClock.elapsedRealtimeNanos(); long sent = b.getLong();
                        if (sent <= now && now-sent < 3_000_000_000L && active(token)) {
                            wirelessRttMillis = (now-sent)/1_000_000L; lastPong = now;
                        }
                    }
                } catch (SocketTimeoutException e) { }
                if (SystemClock.elapsedRealtimeNanos()-lastPong > 3_000_000_000L && active(token)) wirelessRttMillis = -1;
            }
        } catch (IOException e) { if (active(token)) wirelessRttMillis = -1; }
    }
    private void udp(String host, int token) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(); if (!own(socket,token)) return;
            socket.connect(InetAddress.getByName(host),9876); socket.setSoTimeout(250); socket.setReceiveBufferSize(65536);
            byte[] buffer = new byte[1200]; DatagramPacket packet = new DatagramPacket(buffer,buffer.length);
            long nextHello = 0, lastPacket = SystemClock.elapsedRealtimeNanos();
            while (active(token)) {
                long now = SystemClock.elapsedRealtimeNanos();
                if (now >= nextHello) {
                    if (usbAvailable()) return;
                    byte[] data = hello(false,false); socket.send(new DatagramPacket(data,data.length)); nextHello = now+1_000_000_000L;
                }
                packet.setLength(buffer.length);
                try { socket.receive(packet); }
                catch (SocketTimeoutException e) { if (now-lastPacket > 2_000_000_000L) return; else continue; }
                if (accept(buffer,packet.getLength(),token,"UDP")) lastPacket = SystemClock.elapsedRealtimeNanos();
            }
        } catch (IOException e) { if (active(token)) { error = "连接失败  检查 IP 和网络"; Log.w("ACFlip","UDP",e); } }
        finally { if (socket != null) { socket.close(); release(socket); } }
    }
}
