package cn.acflip.dash;

import java.util.Arrays;

// Compare completed sectors against records from before the crossing.
final class LapTiming {
    int[] sectors = new int[0], colors = new int[0], fastest = new int[0];
    private int[] world = new int[0];
    private boolean worldKnown;
    private int[] finishedColors = new int[0];
    private int[] finishedSectors = new int[0];
    private Telemetry previous;
    private long finishedAt;
    private int finishedTime, finishedBest;

    void update(Telemetry t) {
        if (previous != null && t.sequence == previous.sequence) return;
        int count = Math.max(0, t.sectorCount);
        boolean changedCount = count != sectors.length;
        if (changedCount) {
            sectors = new int[count]; colors = new int[count]; fastest = new int[count];
            world = new int[count]; finishedColors = new int[count];
            finishedSectors = new int[count];
        }
        boolean crossing = previous != null && previous.pit == 0 && t.pit == 0 && t.last > 0
                && (t.last != previous.last || count > 0 && previous.sectorIndex == count-1
                && (t.sectorIndex == 0 || t.progress < .03f));
        boolean reset = changedCount || previous != null && (!t.track.equals(previous.track) || !t.car.equals(previous.car)
                || t.laps < previous.laps || (t.laps == previous.laps && t.current + 2000 < previous.current && !crossing));
        if (reset || t.gameStatus() == 1) {
            Arrays.fill(sectors, 0); Arrays.fill(colors, 0); Arrays.fill(fastest, 0); Arrays.fill(world, 0);
            worldKnown = false;
            finishedAt = 0;
        }
        if (!reset && previous != null && t.gameStatus() != 1) {
            if (t.laps == previous.laps && t.sectorIndex == previous.sectorIndex+1 && t.lastSector > 0)
                complete(previous.sectorIndex, t.lastSector, t.players);
            if (t.laps == previous.laps+1 && t.last > 0) {
                int sum = 0;
                boolean known = count > 0;
                for (int i = 0; i < count-1; i++) { sum += sectors[i]; known &= sectors[i] > 0; }
                if (known && t.last > sum) complete(count-1, t.last-sum, t.players);
                finishedTime = t.last; finishedBest = t.best; finishedAt = t.receivedNanos;
                System.arraycopy(colors, 0, finishedColors, 0, count);
                System.arraycopy(sectors, 0, finishedSectors, 0, count);
                Arrays.fill(sectors, 0); Arrays.fill(colors, 0);
            }
        }
        if (t.raceContext && t.gameStatus() != 1) {
            for (int i = 0; i < count; i++) {
                int value = t.personalBest[i];
                if (value > 0 && (fastest[i] == 0 || value < fastest[i])) fastest[i] = value;
                world[i] = t.worldBest[i];
            }
            worldKnown = true;
        } else worldKnown = false;
        previous = t;
    }
    private void complete(int index, int millis, int players) {
        if (index < 0 || index >= sectors.length) return;
        sectors[index] = millis;
        if (fastest[index] == 0 || millis < fastest[index]) {
            colors[index] = players <= 1 || worldKnown && (world[index] == 0 || millis < world[index]) ? 3 : 1;
            fastest[index] = millis;
        } else colors[index] = 2;
    }
    int idealTime() {
        if (fastest.length == 0) return 0;
        long sum = 0;
        for (int value : fastest) { if (value <= 0) return 0; sum += value; }
        return sum < Integer.MAX_VALUE ? (int)sum : 0;
    }
    boolean finished(long now) { return finishedAt != 0 && now-finishedAt < 4_000_000_000L; }
    int shownTime(Telemetry t, long now) { return finished(now) ? finishedTime : t.current; }
    float shownDelta(Telemetry t, long now) {
        if (finished(now)) return finishedBest > 0 ? (finishedTime-finishedBest)/1000f : Float.NaN;
        return t.best > 0 ? t.delta : Float.NaN;
    }
    int clockColor(Telemetry t, long now) {
        if (finished(now)) return finishedBest > 0 && finishedTime <= finishedBest ? 3 : 2;
        float delta = shownDelta(t, now);
        return !Float.isFinite(delta) || delta == 0 ? 0 : delta < 0 ? 1 : 2;
    }
    int[] shownColors(long now) { return finished(now) ? finishedColors : colors; }
    int[] shownSectors(long now) { return finished(now) ? finishedSectors : sectors; }
}
