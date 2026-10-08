package cn.acflip.dash;

public class BurnInCheck {
    static void expect(boolean result, String message) { if (!result) throw new AssertionError(message); }
    public static void main(String[] args) {
        BurnInProtection protection = new BurnInProtection();
        expect(!protection.dim(1000, true), "Idle timer starts");
        expect(!protection.dim(300999, true), "Full brightness during first five minutes");
        expect(protection.dim(301000, true), "Paused/disconnected dims after five minutes");
        protection.touch(301000);
        expect(!protection.dim(301001, true), "Touch restores brightness");
        expect(!protection.dim(650000, false), "Driving never auto-dims");
        expect(!protection.dim(660000, true), "New idle period gets five minutes");
        expect(protection.dim(960000, true), "New idle period dims");
        for (long now = 0; now < 317000; now += 16) {
            expect(Math.abs(protection.shiftX(now)) <= 1.5 && Math.abs(protection.shiftY(now)) <= 1.5,
                    "Movement bounded to 1.5 physical pixels");
            expect(Math.abs(protection.shiftX(now+16)-protection.shiftX(now)) < .001 &&
                    Math.abs(protection.shiftY(now+16)-protection.shiftY(now)) < .001, "No position jumps between frames");
        }
        expect(Math.abs(protection.shiftX(60000)-protection.shiftX(180000)) > 2.9, "Slow drift still traverses full range");
        System.out.println("Continuous subpixel drift, five-minute dim, touch restore and driving brightness checks passed");
    }
}
