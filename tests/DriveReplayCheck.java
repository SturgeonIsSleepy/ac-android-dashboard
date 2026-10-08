package cn.acflip.dash;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

class DriveReplayCheck {
    public static void main(String[] args) throws Exception {
        ByteBuffer trace = ByteBuffer.wrap(Files.readAllBytes(Paths.get(args[0]))).order(ByteOrder.LITTLE_ENDIAN);
        LapTiming timing = new LapTiming(); RacePhase phase = new RacePhase(); Telemetry previous = null;
        int crossings = 0, pitExits = 0, complete = 0;
        while (trace.remaining() >= 12) {
            int length = trace.getInt(); long now = trace.getLong(); if (trace.remaining() < length) break;
            byte[] frame = new byte[length]; trace.get(frame); Telemetry t = Telemetry.parse(frame,length,now);
            if (t == null || t.demo()) throw new AssertionError("Only valid real telemetry is accepted");
            if (previous != null && t.laps == previous.laps+1 && t.car.equals(previous.car) && t.track.equals(previous.track)) {
                int observed = 0; for (int sector : timing.sectors) if (sector > 0) observed++;
                boolean fromOutlap = phase.stage == RacePhase.OUTLAP;
                timing.update(t); phase.update(t); crossings++;
                int kept = 0; for (int sector : timing.shownSectors(now)) if (sector > 0) kept++;
                if (kept < observed) throw new AssertionError("Real crossing lost observed splits");
                if (timing.shownTime(t,now) != t.last) throw new AssertionError("Real finish time not held");
                if (fromOutlap && phase.lapNumber != 1) throw new AssertionError("Outlap crossing skipped first flying profile");
                if (kept == t.sectorCount) complete++;
                System.out.println("Real crossing: lap="+t.laps+" retained="+kept+"/"+t.sectorCount+" runLap="+phase.lapNumber);
            } else {
                if (previous != null && previous.pit != 0 && t.pit == 0) pitExits++;
                timing.update(t); phase.update(t);
            }
            previous = t;
        }
        if (crossings == 0) throw new AssertionError("Trace has no actual finish crossing");
        System.out.println("Real AC trace passed: crossings="+crossings+" fullLaps="+complete+" pitExits="+pitExits);
    }
}
