package cn.acflip.dash;

import java.util.Arrays;

class OutlapStatusCheck {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        Telemetry t = new Telemetry(); t.fuel = 20; t.maxFuel = 80;
        Arrays.fill(t.temperatures,85); Arrays.fill(t.wear,100);
        check(OutlapStatus.problems(t).length == 0,"Healthy car has no issue list");
        t.temperatures[0] = 40; t.temperatures[3] = 115; t.wear[2] = 10; t.fuel = 1; t.damage[0] = 20; t.tyresOut = 3; t.flag = 2;
        String issues = String.join(",",OutlapStatus.problems(t));
        for (String expected : new String[] {"cold","hot","90%","燃油不足","受损","驶离赛道","黄旗"}) check(issues.contains(expected),expected);
        t.temperatures[1] = 0; t.wear[1] = -1;
        check(OutlapStatus.problems(t).length == 9,"All issues and missing data fit the 3 by 3 display");
        check(OutlapStatus.startDistance(t) == -1 && OutlapStatus.startFill(t) == 0,"Unknown length cannot invent start proximity");
        t.trackLength = 6000; t.progress = .95f; check(OutlapStatus.startFill(t) < .001f,"300 metres starts the bar");
        t.progress = .975f; check(Math.abs(OutlapStatus.startFill(t)-.5f) < .001f,"150 metres is half filled");
        t.progress = 1; check(OutlapStatus.startFill(t) == 1,"Start line fills the bar");
        System.out.println("Healthy/problematic car, missing data, all-issue list and real-distance start indication checks passed");
    }
}
