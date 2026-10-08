package cn.acflip.dash;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class Telemetry {
    static final int MAGIC = 0x31464341;
    static final int SIZE = 1032;
    int sequence, mapId, status, gear, rpm, maxRpm, current, last, best, laps, pit, tyresOut, flag;
    float speed, fuel, maxFuel, delta, x, z, heading;
    final float[] temperatures = new float[4], pressures = new float[4], damage = new float[5];
    final float[] wear = { -1, -1, -1, -1 }, cueDistance = { -1, -1, -1 };
    final int[] cueDirection = new int[3], cueGrade = new int[3];
    int sectorIndex, lastSector, sectorCount, drs, cueCount;
    float progress, trackLength;
    String track, car, driver = "", compound = "";
    int position;
    int scaleRpm;
    float drsDistance = -1;
    int players = 1;
    float pitSpeedLimit;
    boolean raceContext;
    float roadTemperature = -1000, grip = -1, wetness = -1, water = -1, fuelPerLap;
    final int[] personalBest = new int[64], worldBest = new int[64];
    long receivedNanos;

    static Telemetry parse(byte[] bytes, int length, long now) {
        if (length != SIZE && length != 1028 && length != 964 && length != 944 && length != 420 && length != 412 && length != 344 && length != 268) return null;
        ByteBuffer b = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt() != MAGIC || b.getInt() != 1) return null;
        Telemetry t = new Telemetry();
        t.sequence = b.getInt(); t.mapId = b.getInt(); t.status = b.getInt();
        t.gear = b.getInt(); t.rpm = b.getInt(); t.maxRpm = b.getInt();
        t.speed = b.getFloat(); t.fuel = b.getFloat(); t.maxFuel = b.getFloat();
        t.current = b.getInt(); t.last = b.getInt(); t.best = b.getInt(); t.laps = b.getInt();
        t.pit = b.getInt(); t.tyresOut = b.getInt(); t.flag = b.getInt(); t.delta = b.getFloat();
        t.x = b.getFloat(); t.z = b.getFloat(); t.heading = b.getFloat();
        for (int i = 0; i < 4; i++) t.temperatures[i] = b.getFloat();
        for (int i = 0; i < 4; i++) t.pressures[i] = b.getFloat();
        for (int i = 0; i < 5; i++) t.damage[i] = b.getFloat();
        t.track = name(b); t.car = name(b); t.receivedNanos = now;
        if (length >= 344) {
            for (int i = 0; i < 4; i++) t.wear[i] = b.getFloat();
            t.sectorIndex = b.getInt(); t.lastSector = b.getInt(); t.sectorCount = b.getInt();
            t.progress = b.getFloat(); t.drs = b.getInt(); t.cueCount = b.getInt();
            if (t.cueCount < 0 || t.cueCount > 3 || !Float.isFinite(t.progress)) return null;
            for (int i = 0; i < 3; i++) {
                t.cueDirection[i] = b.getInt(); t.cueGrade[i] = b.getInt(); t.cueDistance[i] = b.getFloat();
                if (!Float.isFinite(t.cueDistance[i]) || Math.abs(t.cueDirection[i]) > 1 ||
                        t.cueGrade[i] < 0 || t.cueGrade[i] > 6) return null;
            }
            for (float value : t.wear) if (!Float.isFinite(value)) return null;
            if (t.sectorCount < 0 || t.sectorCount > 64) return null;
        }
        if (length >= 412) { t.position = b.getInt(); t.driver = name(b); }
        if (length >= 420) {
            t.scaleRpm = b.getInt(); t.drsDistance = b.getFloat();
            if (t.scaleRpm < 0 || !Float.isFinite(t.drsDistance)) return null;
        }
        if (length >= 944) {
            t.players = b.getInt(); t.pitSpeedLimit = b.getFloat(); t.raceContext = b.getInt() == 1;
            if (t.players < 1 || !Float.isFinite(t.pitSpeedLimit) || t.pitSpeedLimit < 0 || t.pitSpeedLimit > 200) return null;
            for (int i = 0; i < 64; i++) { t.personalBest[i] = b.getInt(); if (t.personalBest[i] < 0) return null; }
            for (int i = 0; i < 64; i++) { t.worldBest[i] = b.getInt(); if (t.worldBest[i] < 0) return null; }
        }
        if (length >= 964) {
            t.roadTemperature = b.getFloat(); t.grip = b.getFloat(); t.wetness = b.getFloat(); t.water = b.getFloat(); t.fuelPerLap = b.getFloat();
            if (!Float.isFinite(t.roadTemperature) || t.roadTemperature < -50 || t.roadTemperature > 150 ||
                    !Float.isFinite(t.grip) || t.grip < -1 || t.grip > 1 || !Float.isFinite(t.wetness) || t.wetness < -1 || t.wetness > 1 ||
                    !Float.isFinite(t.water) || t.water < -1 || t.water > 1 || !Float.isFinite(t.fuelPerLap) || t.fuelPerLap < 0 || t.fuelPerLap > 1000) return null;
        }
        if (length >= 1028) t.compound = name(b);
        if (length == SIZE) {
            t.trackLength = b.getFloat();
            if (!Float.isFinite(t.trackLength) || t.trackLength < 0) return null;
        }
        if (!Float.isFinite(t.speed) || !Float.isFinite(t.fuel) || !Float.isFinite(t.delta)
                || !Float.isFinite(t.x) || !Float.isFinite(t.z) || t.rpm < 0 || t.gear < 0) return null;
        for (float value : t.temperatures) if (!Float.isFinite(value)) return null;
        for (float value : t.pressures) if (!Float.isFinite(value)) return null;
        for (float value : t.damage) if (!Float.isFinite(value)) return null;
        return t;
    }

    private static String name(ByteBuffer b) {
        byte[] bytes = new byte[64]; b.get(bytes);
        int n = 0; while (n < bytes.length && bytes[n] != 0) n++;
        return new String(bytes, 0, n, StandardCharsets.UTF_8);
    }

    String gearText() { return gear == 0 ? "R" : gear == 1 ? "N" : Integer.toString(gear - 1); }
    int rpmScale() { return Math.max(maxRpm, scaleRpm > 0 ? scaleRpm : (int)Math.ceil(maxRpm/1000f)*1000); }
    boolean demo() { return (status & 256) != 0; }
    int gameStatus() { return status & 255; }
    boolean lowFuel() { return fuel <= Math.max(3f, maxFuel * 0.05f); }
    int trackCondition() { return wetness < 0 || water < 0 ? -1 : water >= .05f || wetness >= .7f ? 2 : wetness >= .05f ? 1 : 0; }
    int fuelLaps() { return fuelPerLap > .001f ? Math.max(0, Math.min(999, (int)Math.floor(fuel/fuelPerLap))) : -1; }
    int wearPercent(int wheel) {
        return wear[wheel] < 0 ? -1 : Math.round(Math.max(0, Math.min(100, 100-wear[wheel])));
    }
    String warning() {
        if (flag == 3) return "黑旗  请进站";
        if (flag == 2) return "黄旗  减速";
        if (pit != 0) return "维修区  注意限速";
        if (lowFuel()) return "燃油不足";
        if (tyresOut >= 3) return "驶离赛道";
        for (float v : damage) if (v > 0) return "车身受损";
        for (float v : temperatures) if (v >= 110) return "胎温偏高";
        if (flag == 1) return "蓝旗  注意后车";
        if (flag == 6) return "赛会处罚";
        if (flag == 5) return "方格旗";
        return "";
    }

    static String lapTime(int millis) {
        if (millis <= 0 || millis == Integer.MAX_VALUE) return "--:--.---";
        return String.format(java.util.Locale.US, "%d:%02d.%03d", millis / 60000, millis / 1000 % 60, millis % 1000);
    }
}
