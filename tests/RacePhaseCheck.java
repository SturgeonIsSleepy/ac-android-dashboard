package cn.acflip.dash;

class RacePhaseCheck {
    static int sequence;
    static Telemetry t(int lap, int pit, int clock) {
        Telemetry t = new Telemetry(); t.sequence = sequence++; t.car = "car"; t.track = "track";
        t.status = 2; t.laps = lap; t.pit = pit; t.current = clock; return t;
    }
    static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    public static void main(String[] args) {
        RacePhase phase = new RacePhase(); int[] plan = RacePhase.styles("8,9,4");
        phase.update(t(4,0,80000)); check(phase.stage == 0 && phase.lapNumber == 1,"Mid-session starts current profile");
        phase.update(t(4,2,82000)); check(phase.stage == 1,"Pit lane detected");
        phase.update(t(4,1,83000)); check(phase.stage == 1,"Pit box remains pit");
        phase.update(t(5,2,1000)); check(phase.stage == 1,"Crossing line inside pit does not start driving style");
        phase.update(t(5,0,4000)); check(phase.stage == 2,"Pit exit starts outlap");
        phase.update(t(5,0,90000)); check(phase.stage == 2,"Outlap remains until next crossing");
        phase.update(t(6,0,1000)); check(phase.stage == 0 && phase.lapNumber == 1 && phase.selected(plan) == 8,"First flying lap profile");
        phase.update(t(7,0,1000)); check(phase.lapNumber == 2 && phase.selected(plan) == 9,"Second lap profile");
        phase.update(t(9,0,1000)); check(phase.selected(plan) == 4,"Last profile repeats beyond list");
        phase.update(t(9,2,2000)); phase.update(t(9,0,3000)); phase.update(t(10,0,1000));
        check(phase.lapNumber == 1 && phase.selected(plan) == 8,"Another pit visit restarts plan");
        phase.update(t(0,0,0)); check(phase.stage == 0 && phase.lapNumber == 1,"Session reset");
        Telemetry other = t(3,0,1000); other.track = "other"; phase.update(other);
        check(phase.lapNumber == 1,"Track change resets plan");
        phase.update(t(3,1,80000)); phase.update(t(3,0,1000));
        check(phase.stage == 2,"Pit exit clock reset still starts outlap");
        Telemetry nearLine = t(3,0,80000); nearLine.sectorCount = 3; nearLine.sectorIndex = 2; phase.update(nearLine);
        Telemetry clockFirst = t(3,0,17); clockFirst.sectorCount = 3; clockFirst.sectorIndex = 2; clockFirst.last = 90000; clockFirst.progress = .00053f;
        phase.update(clockFirst); check(phase.stage == 2,"Real AC clock-first crossing does not reset outlap");
        Telemetry countNext = t(4,0,44); countNext.sectorCount = 3; countNext.last = 90000; phase.update(countNext);
        check(phase.stage == 0 && phase.lapNumber == 1,"Real AC count-next crossing starts first flying lap");
        Telemetry replay = t(0,2,1000); replay.status = 1; phase.update(replay); check(phase.stage == 0,"Replay does not simulate pit exit");
        check(RacePhase.styles("bad")[0] == 9,"Invalid stored preference falls back to reference style");
        System.out.println("Pit, outlap boundary, per-lap plan, repetition, session reset and replay checks passed");
    }
}
