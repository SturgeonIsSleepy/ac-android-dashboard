package cn.acflip.dash;

import java.util.ArrayList;

final class OutlapStatus {
    static String[] problems(Telemetry t) {
        ArrayList<String> result = new ArrayList<>();
        boolean hot = false, cold = false, missingTemperature = false, missingWear = false, damage = false;
        int wear = 0;
        for (int i = 0; i < 4; i++) {
            hot |= t.temperatures[i] >= 110;
            cold |= t.temperatures[i] > 0 && t.temperatures[i] < 60;
            missingTemperature |= t.temperatures[i] <= 0;
            int value = t.wearPercent(i); missingWear |= value < 0; wear = Math.max(wear,value);
        }
        for (float value : t.damage) damage |= value > 0;
        if (cold) result.add("轮胎 cold");
        if (hot) result.add("轮胎 hot");
        if (wear >= 75) result.add("胎损 "+wear+"%");
        if (t.lowFuel()) result.add("燃油不足");
        if (damage) result.add("车身受损");
        if (t.tyresOut >= 3) result.add("驶离赛道");
        if (missingTemperature) result.add("胎温未知");
        if (missingWear) result.add("胎损未知");
        if (t.flag == 2 || t.flag == 3 || t.flag == 6) result.add(t.flag == 2 ? "黄旗减速" : t.flag == 3 ? "黑旗进站" : "赛会处罚");
        return result.toArray(new String[0]);
    }
    static float startDistance(Telemetry t) { return t.trackLength > 0 ? Math.max(0,(1-t.progress)*t.trackLength) : -1; }
    static float startFill(Telemetry t) {
        float distance = startDistance(t);
        return distance < 0 ? 0 : Math.max(0,Math.min(1,1-distance/300));
    }
}
