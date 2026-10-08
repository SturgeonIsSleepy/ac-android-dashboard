package cn.acflip.dash;

public class LapTimingCheck {
    static long tick = 1_000_000_000L;
    static int sequence;
    static Telemetry frame(int laps, int sector, int lastSector, int last, int best) {
        Telemetry t = new Telemetry(); t.sequence = sequence++; t.receivedNanos = tick += 100_000_000L;
        t.track = "test"; t.car = "test"; t.status = 2; t.sectorCount = 3;
        t.laps = laps; t.sectorIndex = sector; t.lastSector = lastSector; t.last = last; t.best = best;
        t.current = sector*30000+1000;
        return t;
    }
    static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) {
        LapTiming timer = new LapTiming();
        timer.update(frame(0,0,0,0,0));
        timer.update(frame(0,1,30000,0,0));
        timer.update(frame(0,2,30000,0,0));
        Telemetry finish = frame(1,0,30000,90000,90000); timer.update(finish);
        expect(timer.shownTime(finish,tick) == 90000, "Hold finished lap time");
        expect(timer.shownColors(tick)[0] == 3 && timer.shownColors(tick)[2] == 3, "First recorded best sectors purple");
        expect(timer.shownSectors(tick)[2] == 30000, "Finished sector values remain visible during hold");
        expect(timer.clockColor(finish,tick) == 3, "Best completed lap purple");
        expect(timer.shownTime(finish,tick+5_000_000_000L) == finish.current, "Return to new lap after hold");
        expect(timer.shownSectors(tick+5_000_000_000L)[2] == 30000, "Keep previous splits until the new lap produces a split");
        timer.update(frame(1,1,31000,90000,90000));
        expect(timer.colors[0] == 2, "Slower than reference yellow");
        expect(timer.shownSectors(tick+5_000_000_000L)[0] == 31000 && timer.shownSectors(tick+5_000_000_000L)[1] == 0,
                "First new split replaces previous-lap display");
        timer.update(frame(1,2,29000,90000,90000));
        expect(timer.colors[1] == 3, "New sector best purple");
        timer.update(frame(2,0,30000,90000,90000));
        timer.update(frame(2,1,30500,90000,90000));
        expect(timer.colors[0] == 2, "No personal improvement stays yellow even when better than best-lap split");
        timer.update(frame(2,2,29500,90000,90000));
        expect(timer.colors[1] == 2, "Slower sector yellow");
        finish = frame(3,0,29800,89800,89800); timer.update(finish);
        expect(timer.shownColors(tick)[0] == 2 && timer.shownColors(tick)[1] == 2 && timer.shownColors(tick)[2] == 3,
                "Completed lap keeps each sector's independent color");
        LapTiming delayed = new LapTiming();
        delayed.update(frame(0,0,0,0,0)); delayed.update(frame(0,1,30000,0,0)); delayed.update(frame(0,2,31000,0,0));
        Telemetry restarted = frame(0,2,31000,92000,92000); restarted.current = 17; restarted.progress = .00053f; delayed.update(restarted);
        delayed.update(frame(1,0,30000,92000,92000));
        expect(delayed.shownSectors(tick)[0] == 30000 && delayed.shownSectors(tick)[1] == 31000
                && delayed.shownSectors(tick)[2] == 31000, "Clock resetting before the lap counter does not erase finish splits");
        LapTiming joined = new LapTiming();
        joined.update(frame(2,2,30000,90000,90000));
        finish = frame(3,0,30000,90000,90000); joined.update(finish);
        expect(joined.shownColors(tick)[0] == 0 && joined.shownColors(tick)[2] == 0, "Do not invent unobserved sector times");
        timer.update(frame(0,0,0,0,0));
        expect(timer.fastest[0] == 0, "Session reset clears old references");
        LapTiming multi = new LapTiming();
        Telemetry seed = frame(0,0,0,0,90000); seed.players = 5; seed.raceContext = true;
        for (int i = 0; i < 3; i++) { seed.personalBest[i] = 30000; seed.worldBest[i] = 28000; }
        multi.update(seed); expect(multi.idealTime() == 90000, "Ideal is sum of individual sector PBs");
        Telemetry pb = frame(0,1,29000,0,90000); pb.players = 5; pb.raceContext = true;
        java.util.Arrays.fill(pb.worldBest,28000); multi.update(pb);
        expect(multi.colors[0] == 1, "Personal PB but not field PB green");
        pb = frame(0,2,27000,0,90000); pb.players = 5; pb.raceContext = true;
        java.util.Arrays.fill(pb.worldBest,28000); multi.update(pb);
        expect(multi.colors[1] == 3, "Field PB purple");
        pb = frame(1,0,30000,86000,86000); pb.players = 5; multi.update(pb);
        expect(multi.shownColors(tick)[2] == 2, "Equal personal PB yellow");
        expect(multi.idealTime() == 86000, "Independent records update ideal");
        LapTiming unknown = new LapTiming(); seed = frame(0,0,0,0,0); seed.players = 5; unknown.update(seed);
        pb = frame(0,1,29000,0,0); pb.players = 5; unknown.update(pb);
        expect(unknown.colors[0] == 1 && unknown.idealTime() == 0, "Unknown field records cannot claim purple or invent ideal");
        LapTiming stale = new LapTiming(); seed = frame(0,0,0,0,0); seed.players = 5; seed.raceContext = true;
        java.util.Arrays.fill(seed.personalBest,30000); java.util.Arrays.fill(seed.worldBest,28000); stale.update(seed);
        Telemetry unavailable = frame(0,0,0,0,0); unavailable.players = 5; stale.update(unavailable);
        pb = frame(0,1,27000,0,0); pb.players = 5; stale.update(pb);
        expect(stale.colors[0] == 1, "Stale field records cannot claim overall PB");
        Telemetry tyres = frame(0,0,0,0,0); tyres.wear[0] = 100; tyres.wear[1] = 60; tyres.wear[2] = 10;
        expect(tyres.wearPercent(0) == 0 && tyres.wearPercent(1) == 40 && tyres.wearPercent(2) == 90, "Invert AC tyre health signal");
        for (int count : new int[] { 0, 1, 2, 4, 6, 12 }) {
            LapTiming variable = new LapTiming();
            Telemetry begin = frame(0,0,0,0,0); begin.sectorCount = count; variable.update(begin);
            for (int i = 1; i < count; i++) {
                Telemetry sector = frame(0,i,30000,0,0); sector.sectorCount = count; variable.update(sector);
            }
            Telemetry end = frame(1,0,30000,Math.max(1,count)*30000,Math.max(1,count)*30000);
            end.sectorCount = count; variable.update(end);
            expect(variable.shownColors(tick).length == count, "Actual sector count " + count);
            for (int color : variable.shownColors(tick)) expect(color == 3, "Every observed sector completed " + count);
            expect(variable.shownTime(end,tick) == end.last, "Finish hold for " + count + " sectors");
            Telemetry changed = frame(1,0,0,end.last,end.best); changed.sectorCount = count+1;
            variable.update(changed);
            expect(variable.colors.length == count+1 && !variable.finished(tick), "Count change resets old layout");
        }
        LapTiming skipped = new LapTiming();
        Telemetry six = frame(0,0,0,0,0); six.sectorCount = 6; skipped.update(six);
        six = frame(0,2,30000,0,0); six.sectorCount = 6; skipped.update(six);
        six = frame(1,0,30000,180000,180000); six.sectorCount = 6; skipped.update(six);
        for (int color : skipped.shownColors(tick)) expect(color == 0, "Skipped sectors remain unknown");
        System.out.println("Lap timing, variable sectors 0/1/2/3/4/6/12, colors, hold, missing data, reset and wear checks passed");
    }
}
