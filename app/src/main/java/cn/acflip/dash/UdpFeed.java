package cn.acflip.dash;

import android.os.SystemClock;
import android.util.Log;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class UdpFeed {
    volatile Telemetry latest;
    volatile TrackMap map;
    volatile long rttMillis = -1, received, lost;
    volatile String error = "";
    volatile int width, height, display;
    private volatile boolean running;
    private volatile DatagramSocket socket;
    private Thread thread;

    void start(String host) {
        stop();
        running = true;
        thread = new Thread(() -> receive(host), "ACFlip-UDP");
        thread.start();
    }
    void stop() {
        running = false;
        DatagramSocket s = socket;
        if (s != null) s.close();
        if (thread != null) {
            thread.interrupt();
            try { thread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        socket = null;
    }
    private void receive(String host) {
        if (host.trim().isEmpty()) { error = "长按屏幕设置电脑 IP"; return; }
        byte[] buffer = new byte[1200];
        long nextHello = 0;
        while (running) {
        try (DatagramSocket s = new DatagramSocket()) {
            socket = s;
            s.connect(InetAddress.getByName(host), 9876);
            s.setSoTimeout(250);
            s.setReceiveBufferSize(65536);
            error = "";
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while (running) {
                long now = SystemClock.elapsedRealtimeNanos();
                if (now >= nextHello) {
                    ByteBuffer b = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(Telemetry.MAGIC).putInt(3).putLong(now).putLong(rttMillis)
                            .putLong(received).putLong(lost).putInt(width).putInt(height).putInt(display);
                    byte[] name = android.os.Build.MODEL.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    b.put(name, 0, Math.min(31, name.length));
                    byte[] hello = b.array();
                    s.send(new DatagramPacket(hello, hello.length));
                    nextHello = now + 1_000_000_000L;
                }
                packet.setLength(buffer.length);
                try { s.receive(packet); } catch (SocketTimeoutException e) { continue; }
                now = SystemClock.elapsedRealtimeNanos();
                int length = packet.getLength();
                if (length < 8) continue;
                ByteBuffer b = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN);
                if (b.getInt() != Telemetry.MAGIC) continue;
                int kind = b.getInt();
                if (kind == 1) {
                    Telemetry next = Telemetry.parse(buffer, length, now);
                    if (next == null) continue;
                    Telemetry previous = latest;
                    // Restarted bridge sequences become acceptable once the previous stream is stale.
                    if (previous != null && now - previous.receivedNanos < 1_000_000_000L) {
                        int gap = next.sequence - previous.sequence;
                        if (gap <= 0) continue;
                        lost += gap - 1;
                    }
                    received++;
                    latest = next;
                } else if (kind == 2) {
                    TrackMap next = TrackMap.parse(buffer, length);
                    if (next != null) map = next;
                } else if (kind == 4 && length == 16) {
                    long sent = b.getLong();
                    if (sent <= now && now - sent < 5_000_000_000L) rttMillis = (now - sent) / 1_000_000L;
                }
            }
        } catch (Exception e) {
            if (running) { error = "连接失败  检查 IP 和网络"; Log.w("ACFlip", "UDP", e); }
        }
        if (running) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        }
    }
}
