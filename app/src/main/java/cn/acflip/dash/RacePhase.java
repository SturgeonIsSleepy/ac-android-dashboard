package cn.acflip.dash;

final class RacePhase {
    static final int NORMAL = 0, PIT = 1, OUTLAP = 2;
    int stage = NORMAL, lapNumber = 1;
    private int anchor;
    private Telemetry previous;

    void update(Telemetry t) {
        if (previous != null && t.sequence == previous.sequence) return;
        boolean crossing = previous != null && previous.pit == 0 && t.pit == 0 && t.last > 0
                && (t.last != previous.last || t.sectorCount > 0 && previous.sectorIndex == t.sectorCount-1
                && (t.sectorIndex == 0 || t.progress < .03f));
        boolean reset = previous == null || !t.car.equals(previous.car) || !t.track.equals(previous.track)
                || t.laps < previous.laps || t.laps == previous.laps && t.current+2000 < previous.current && !crossing;
        boolean leavingPit = previous != null && previous.pit != 0 && t.pit == 0 && t.laps >= previous.laps
                && t.car.equals(previous.car) && t.track.equals(previous.track);
        if (reset || t.gameStatus() == 1) { stage = NORMAL; anchor = t.laps-1; }
        if (t.gameStatus() != 1) {
            if (t.pit != 0) stage = PIT;
            else if (stage == PIT || leavingPit) { stage = OUTLAP; anchor = t.laps; }
            else if (stage == OUTLAP && t.laps > anchor) stage = NORMAL;
        }
        lapNumber = Math.max(1,t.laps-anchor);
        previous = t;
    }
    static int[] styles(String encoded) {
        String[] values = encoded.split(","); int[] result = new int[Math.max(1,values.length)];
        for (int i = 0; i < result.length; i++) {
            try { result[i] = Math.max(0,Math.min(9,Integer.parseInt(values[i]))); }
            catch (NumberFormatException e) { result[i] = 9; }
        }
        return result;
    }
    int selected(int[] styles) { return styles[Math.min(styles.length-1,lapNumber-1)]; }
}
