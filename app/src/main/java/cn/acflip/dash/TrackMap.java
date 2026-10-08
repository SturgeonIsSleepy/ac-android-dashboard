package cn.acflip.dash;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class TrackMap {
    final int id;
    final boolean closed;
    final float[] xs, zs;
    float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

    private TrackMap(int id, int count, boolean closed) {
        this.id = id; this.closed = closed; xs = new float[count]; zs = new float[count];
    }
    static TrackMap parse(byte[] data, int length) {
        if (length < 20) return null;
        ByteBuffer b = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt() != Telemetry.MAGIC || b.getInt() != 2) return null;
        int id = b.getInt(), n = b.getInt(), closed = b.getInt();
        if (n < 0 || n > 128 || length != 20 + n * 8) return null;
        TrackMap map = new TrackMap(id, n, closed == 1);
        for (int i = 0; i < n; i++) {
            float x = b.getFloat(), z = b.getFloat();
            if (!Float.isFinite(x) || !Float.isFinite(z)) return null;
            map.xs[i] = x; map.zs[i] = z;
            map.minX = Math.min(map.minX, x); map.maxX = Math.max(map.maxX, x);
            map.minZ = Math.min(map.minZ, z); map.maxZ = Math.max(map.maxZ, z);
        }
        return map;
    }
}
