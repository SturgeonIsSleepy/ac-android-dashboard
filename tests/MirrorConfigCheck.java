package cn.acflip.dash;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

public class MirrorConfigCheck {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static ByteBuffer data(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    static byte[] packet() {
        ByteBuffer bytes = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(Telemetry.MAGIC).putInt(8).putInt(42);
        for (float[] view : MirrorConfig.defaults().values) for (float field : view) bytes.putFloat(field);
        return bytes.array();
    }
    public static void main(String[] args) throws Exception {
        byte[] valid = packet(); MirrorConfig settings = MirrorConfig.parse(valid);
        check(settings != null && settings.revision == 42,"Exact configuration packet and revision decode");
        for (int view = 0;view < 3;view++) for (int field = 0;field < 6;field++)
            check(settings.values[view][field] == MirrorConfig.defaults().values[view][field],"View-major little-endian float32 values decode");
        check(settings.values[0][1] == -10.2f && settings.values[2][1] == 10.2f,"Distinct left and right default directions");
        for (int length : new int[] {0,16,83,85,100}) check(MirrorConfig.parse(new byte[length]) == null,"Reject wrong packet length");
        byte[] bad = valid.clone(); data(bad).putInt(0,0); check(MirrorConfig.parse(bad) == null,"Reject foreign magic");
        bad = valid.clone(); data(bad).putInt(4,9); check(MirrorConfig.parse(bad) == null,"Reject wrong message type");
        for (int view = 0;view < 3;view++) for (int field = 0;field < 6;field++) {
            int offset = 12+(view*6+field)*4;
            for (float value : new float[] {MirrorConfig.MIN[field],MirrorConfig.MAX[field]}) {
                byte[] bound = valid.clone(); data(bound).putFloat(offset,value);
                check(MirrorConfig.parse(bound).values[view][field] == value,"Accept inclusive field boundary");
            }
            for (float value : new float[] {Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,MirrorConfig.MIN[field]-.01f,MirrorConfig.MAX[field]+.01f}) {
                bad = valid.clone(); data(bad).putFloat(offset,value);
                check(MirrorConfig.parse(bad) == null,"Reject invalid value in every mirror and field");
            }
        }
        byte[] command = MirrorConfig.command(3,5,-1.25f); ByteBuffer bytes = data(command);
        check(command.length == 20 && bytes.getInt() == Telemetry.MAGIC && bytes.getInt() == 9,"Command magic, type and length");
        check(bytes.getInt() == 3 && bytes.getInt() == 5 && bytes.getFloat() == -1.25f,"Command view, field and float32 value");
        check(data(MirrorConfig.command(2,6,0)).getInt(12) == 6,"Selected mirror reset uses field 6");
        if (args.length > 0) {
            byte[] captured = Files.readAllBytes(Paths.get(args[0])); MirrorConfig bridge = MirrorConfig.parse(captured);
            check(bridge != null,"C# model packet decodes with the actual Android codec");
            for (int view = 0;view < 3;view++) for (int field = 0;field < 6;field++)
                check(bridge.values[view][field] == data(captured).getFloat(12+(view*6+field)*4),"C# float32 field order agrees with Android");
        }
        System.out.println("Mirror config: cross-language packets, field limits, finite values and update/reset commands passed");
    }
}
