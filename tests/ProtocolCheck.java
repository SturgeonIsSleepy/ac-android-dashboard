package cn.acflip.dash;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ProtocolCheck {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        byte[] packet = Files.readAllBytes(Paths.get(args[0]));
        Telemetry t = Telemetry.parse(packet, packet.length, 123);
        check(t != null && t.receivedNanos == 123, "Captured bridge packet decodes on Android codec");
        check(Telemetry.parse(packet, packet.length - 1, 0) == null, "Reject truncated telemetry");
        byte[] bad = packet.clone(); bad[0] = 0;
        check(Telemetry.parse(bad, bad.length, 0) == null, "Reject foreign magic");
        bad = packet.clone();
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putFloat(76, Float.NaN);
        check(Telemetry.parse(bad, bad.length, 0) == null, "Reject invalid map position");
        t.gear = 0; check(t.gearText().equals("R"), "AC reverse gear conversion");
        t.gear = 1; check(t.gearText().equals("N"), "AC neutral gear conversion");
        t.gear = 2; check(t.gearText().equals("1"), "AC first gear conversion");
        check(Telemetry.lapTime(84923).equals("1:24.923"), "Keep millisecond lap precision");
        check(Telemetry.lapTime(Integer.MAX_VALUE).equals("--:--.---"), "Unset lap sentinel");
        t.flag = 2; t.fuel = 1;
        check(t.warning().startsWith("黄旗"), "Yellow flag takes priority over low fuel");
        t.flag = 0; t.pit = 0;
        check(t.warning().equals("燃油不足"), "Low fuel indication");
        if (packet.length >= 344) {
            check(t.sectorCount >= 0 && t.cueCount <= 3, "Extended sector and cue fields decode");
            byte[] invalid = packet.clone();
            ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(304, 4);
            check(Telemetry.parse(invalid, invalid.length, 0) == null, "Reject oversized cue queue");
            check(Telemetry.parse(java.util.Arrays.copyOf(packet, 344), 344, 0) != null, "v0.2 prefix remains readable");
            if (packet.length >= 412) check(Telemetry.parse(java.util.Arrays.copyOf(packet, 412), 412, 0) != null, "v0.3 prefix remains readable");
            check(Telemetry.parse(java.util.Arrays.copyOf(packet, 268), 268, 0) != null, "v0.1 prefix remains readable");
            invalid = packet.clone(); ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(292, -1);
            check(Telemetry.parse(invalid, invalid.length, 0) == null, "Reject invalid sector count");
            if (packet.length >= 420) {
                check(t.rpmScale() >= t.maxRpm, "Scale includes actual vehicle limiter");
                invalid = packet.clone(); ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putFloat(416, Float.NaN);
                check(Telemetry.parse(invalid, invalid.length, 0) == null, "Reject invalid DRS distance");
            }
            if (packet.length >= 944) {
                check(Telemetry.parse(java.util.Arrays.copyOf(packet, 420), 420, 0) != null, "v0.4 prefix remains readable");
                invalid = packet.clone(); ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putFloat(424, Float.NaN);
                check(Telemetry.parse(invalid, invalid.length, 0) == null, "Reject invalid pit limit");
                invalid = packet.clone(); ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(432, -1);
                check(Telemetry.parse(invalid, invalid.length, 0) == null, "Reject invalid personal sector record");
            }
            if (packet.length >= 964) {
                check(Telemetry.parse(java.util.Arrays.copyOf(packet,944),944,0) != null,"v0.6 prefix remains readable");
                invalid = packet.clone(); ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putFloat(948,Float.NaN);
                check(Telemetry.parse(invalid,invalid.length,0) == null,"Reject nonfinite grip");
                invalid = packet.clone(); ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putFloat(960,-1);
                check(Telemetry.parse(invalid,invalid.length,0) == null,"Reject negative fuel estimate");
            }
            if (packet.length >= 1028) check(Telemetry.parse(java.util.Arrays.copyOf(packet,964),964,0) != null,"v0.7 prefix remains readable");
            if (packet.length == 1032) {
                check(Telemetry.parse(java.util.Arrays.copyOf(packet,1028),1028,0).trackLength == 0,"v0.8 prefix stays readable with unknown track length");
                invalid = packet.clone(); ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putFloat(1028,Float.NaN);
                check(Telemetry.parse(invalid,invalid.length,0) == null,"Reject invalid track length");
            }
        }
        Telemetry surface = new Telemetry();
        check(surface.trackCondition() == -1 && surface.fuelLaps() == -1,"Missing conditions and fuel estimate stay unknown");
        surface.wetness = surface.water = 0; check(surface.trackCondition() == 0,"Dry surface");
        surface.wetness = .2f; check(surface.trackCondition() == 1,"Damp surface");
        surface.wetness = .8f; check(surface.trackCondition() == 2,"Wet surface");
        surface.wetness = 0; surface.water = .1f; check(surface.trackCondition() == 2,"Standing water wet");
        surface.fuel = 38.4f; surface.fuelPerLap = 3.2f; check(surface.fuelLaps() == 12,"Original AC estimate produces remaining laps");
        surface.fuel = 1; check(surface.fuelLaps() == 0,"Fuel cannot complete a whole estimated lap");
        if (args.length > 1) {
            byte[] route = Files.readAllBytes(Paths.get(args[1]));
            TrackMap map = TrackMap.parse(route, route.length);
            check(map != null && map.xs.length == 128, "Actual AI route decoded");
            check(map.maxX > map.minX && map.maxZ > map.minZ, "World coordinate map bounds");
            check(TrackMap.parse(route, route.length - 1) == null, "Reject partial route packet");
            ByteBuffer.wrap(route).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 100000);
            check(TrackMap.parse(route, route.length) == null, "Reject oversized route");
        }
        System.out.println("Protocol checks passed: captured C# packets, Android decoding, invalid packets, gears, lap times, alerts and map");
    }
}
