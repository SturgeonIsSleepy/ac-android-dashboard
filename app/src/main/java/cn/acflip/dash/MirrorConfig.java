package cn.acflip.dash;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class MirrorConfig {
    static final String[] NAMES = {"视野角（上下）","水平朝向（右＋）","俯仰（抬头＋）","左右偏移（右＋）","高度偏移（上＋）","前后偏移（前＋）"};
    static final float[] MIN = {20,-60,-35,-2,-1,-3}, MAX = {100,60,35,2,1,3}, STEP = {1,1,1,.02f,.02f,.05f};
    final int revision;
    final float[][] values;
    MirrorConfig(int version,float[][] settings) { revision = version; values = settings; }
    static MirrorConfig defaults() { return new MirrorConfig(-1,new float[][] {{50,-10.2f,0,0,0,0},{50,0,0,0,0,0},{50,10.2f,0,0,0,0}}); }
    static MirrorConfig parse(byte[] bytes) {
        if (bytes.length != 84) return null;
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (data.getInt() != Telemetry.MAGIC || data.getInt() != 8) return null;
        int revision = data.getInt(); if (revision < 0) return null;
        float[][] values = new float[3][6];
        for (int view = 0;view < 3;view++) for (int field = 0;field < 6;field++) {
            float value = data.getFloat();
            if (!Float.isFinite(value) || value < MIN[field] || value > MAX[field]) return null;
            values[view][field] = value;
        }
        return new MirrorConfig(revision,values);
    }
    static byte[] command(int view,int field,float value) {
        return ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(Telemetry.MAGIC).putInt(9).putInt(view).putInt(field).putFloat(value).array();
    }
}
