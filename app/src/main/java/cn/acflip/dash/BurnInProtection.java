package cn.acflip.dash;

// Small physical-pixel shifts and dimming only while paused or disconnected.
final class BurnInProtection {
    private long idleSince = -1, touchedAt;
    void touch(long now) { touchedAt = now; }
    float shiftX(long now) { return (float)(1.5 * Math.sin(now * 2 * Math.PI / 240_000)); }
    float shiftY(long now) { return (float)(1.5 * Math.cos(now * 2 * Math.PI / 317_000)); }
    boolean dim(long now, boolean inactive) {
        if (!inactive) { idleSince = -1; return false; }
        if (idleSince < 0) idleSince = now;
        return now - Math.max(idleSince, touchedAt) >= 300_000;
    }
}
