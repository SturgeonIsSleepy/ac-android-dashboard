package cn.acflip.dash;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;
import java.util.Locale;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    static final String[] LAYOUT_NAMES = { "环形转速", "F1 圆形灯", "F1 分段灯", "极简挡位", "F1 计时", "车况", "弯道指引", "飞驰圈", "汽车计时仪表", "参考图复刻", "阶段切换" };
    static Bitmap[] stylePreviews;
    private final UdpFeed feed = new UdpFeed();
    private Dashboard dashboard;
    private String host;
    private int page, layout;
    private boolean resumed;
    private int style, guideStyle, shiftRpm;
    private int[] lapStyles = {9};
    private String shiftCar = "";
    private boolean volumeUp, volumeDown;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setNavigationBarColor(Color.BLACK);
        immersive();
        host = getPreferences(0).getString("host", "");
        page = getPreferences(0).getInt("page", 0);
        style = getPreferences(0).getInt("style", 0); guideStyle = getPreferences(0).getInt("guideStyle", 0);
        layout = getPreferences(0).getInt("layout", layoutFor(page, style, guideStyle));
        readIntent(getIntent());
        dashboard = new Dashboard();
        setContentView(dashboard);
        Toast.makeText(this, "同时按音量＋和音量－打开设置", Toast.LENGTH_SHORT).show();
    }
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
    }
    private void readIntent(Intent intent) {
        if (intent.hasExtra("host")) {
            host = intent.getStringExtra("host");
            getPreferences(0).edit().putString("host", host).apply();
        }
        if (intent.hasExtra("page")) layout = layoutFor(Math.max(0, Math.min(5, intent.getIntExtra("page", 0))), style, guideStyle);
        if (intent.hasExtra("layout")) layout = Math.max(0, Math.min(LAYOUT_NAMES.length-1, intent.getIntExtra("layout", 0)));
        applyLayout(layout);
        getPreferences(0).edit().putInt("layout", layout).apply();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); readIntent(intent);
        if (resumed) feed.start(host);
        dashboard.invalidate();
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        host = getPreferences(0).getString("host", host); style = getPreferences(0).getInt("style", 0);
        guideStyle = getPreferences(0).getInt("guideStyle", 0); shiftCar = "";
        applyLayout(getPreferences(0).getInt("layout", layout));
        lapStyles = RacePhase.styles(getPreferences(0).getString("lapStyles","9"));
        volumeUp = volumeDown = false; feed.start(host);
        dashboard.burnIn.touch(SystemClock.elapsedRealtime()); dashboard.postInvalidateOnAnimation();
    }
    @Override protected void onPause() {
        resumed = false; feed.stop(); getPreferences(0).edit().putInt("page", page).putInt("layout", layout).apply(); super.onPause();
    }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (key == KeyEvent.KEYCODE_VOLUME_UP) volumeUp = true; else volumeDown = true;
            if (volumeUp && volumeDown && event.getRepeatCount() == 0) {
                volumeUp = volumeDown = false; settings();
            }
            return true;
        }
        return super.onKeyDown(key, event);
    }
    @Override public boolean onKeyUp(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_VOLUME_UP) { volumeUp = false; return true; }
        if (key == KeyEvent.KEYCODE_VOLUME_DOWN) { volumeDown = false; return true; }
        return super.onKeyUp(key, event);
    }
    private void settings() {
        dashboard.createPreviews();
        Telemetry t = feed.latest;
        SettingsDialog settings = new SettingsDialog(this,t == null ? 0 : t.maxRpm,t == null ? "" : t.car);
        settings.setOnDismissListener(dialog -> {
            String nextHost = getPreferences(0).getString("host",host);
            if (!nextHost.equals(host)) { host = nextHost; feed.start(host); }
            shiftCar = "";
            applyLayout(getPreferences(0).getInt("layout",layout));
            lapStyles = RacePhase.styles(getPreferences(0).getString("lapStyles","9"));
            volumeUp = volumeDown = false;
            dashboard.burnIn.touch(SystemClock.elapsedRealtime()); dashboard.invalidate();
        });
        settings.show();
    }
    static int layoutFor(int page, int style, int guide) {
        return page == 0 ? Math.max(0, Math.min(3, style)) : page == 1 ? 4 : page == 2 ? 5 : page == 3 ? 6+Math.min(1,guide) : page == 4 ? 8 : 9;
    }
    private void applyLayout(int value) {
        layout = Math.max(0, Math.min(LAYOUT_NAMES.length-1, value));
        page = layout < 4 ? 0 : layout == 4 ? 1 : layout == 5 ? 2 : layout < 8 ? 3 : layout == 8 ? 4 : layout == 9 ? 5 : 6;
        if (layout < 4) style = layout;
        if (layout == 6 || layout == 7) guideStyle = layout-6;
    }
    private int shift(Telemetry t) {
        if (!t.car.equals(shiftCar)) { shiftCar = t.car; shiftRpm = getPreferences(0).getInt("shift:"+t.car, t.maxRpm); }
        return Math.max(1, Math.min(t.maxRpm, shiftRpm));
    }
    private void connection() {
        EditText input = new EditText(this);
        input.setSingleLine(); input.setText(host); input.setSelectAllOnFocus(true);
        input.setHint("例如 192.168.1.234");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this).setTitle("电脑 IP")
                .setView(input).setNegativeButton("取消", null)
                .setPositiveButton("连接", (dialog, which) -> {
                    host = input.getText().toString().trim();
                    getPreferences(0).edit().putString("host", host).apply();
                    feed.start(host);
                }).show();
    }

    private final class Dashboard extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int bg = Color.BLACK, muted = Color.rgb(153, 153, 153);
        private final int white = Color.rgb(248, 248, 248), green = Color.rgb(19, 210, 108);
        private final int amber = Color.rgb(255, 170, 0), red = Color.rgb(239, 59, 67);
        private final int purple = Color.rgb(170, 53, 239), blue = Color.rgb(52, 111, 248);
        private final int panel = Color.rgb(49, 49, 49);
        private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        private final Typeface digits = Typeface.create("monospace", Typeface.BOLD);
        private final Typeface racing = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC);
        private final GestureDetector gestures;
        private float scale, offsetX, offsetY;
        private long lastLog, drawCount, previousCount;
        private final LapTiming lapTiming = new LapTiming();
        private final RacePhase phase = new RacePhase();
        private final HudArt art = new HudArt(MainActivity.this);
        private final BurnInProtection burnIn = new BurnInProtection();
        private boolean dimmed, preview;
        private float dimAmount, drsFill, drsFade, outlapFill, frameSeconds;
        private long animationAt;

        Dashboard() {
            super(MainActivity.this);
            setContentDescription("AC 外屏仪表，长按或同时按音量上下键打开样式设置");
            gestures = new GestureDetector(MainActivity.this, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public void onLongPress(MotionEvent e) { settings(); }
                @Override public boolean onSingleTapUp(MotionEvent e) {
                    float x = (e.getX() - offsetX) / scale, y = (e.getY() - offsetY) / scale;
                    Telemetry t = feed.latest;
                    boolean waiting = t == null || SystemClock.elapsedRealtimeNanos()-t.receivedNanos > 250_000_000L || t.gameStatus() == 0;
                    if (waiting && x >= 100 && x <= 582 && y >= 218 && y <= 283) connection();
                    else if (y < 45 && x > 620) settings();
                    performClick();
                    invalidate(); return true;
                }
            });
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            burnIn.touch(SystemClock.elapsedRealtime());
            return gestures.onTouchEvent(event);
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override protected void onDraw(Canvas c) {
            c.drawColor(bg);
            scale = Math.min(getWidth() / 682f, getHeight() / 422f);
            long millis = SystemClock.elapsedRealtime();
            offsetX = (getWidth() - 682 * scale) / 2 + burnIn.shiftX(millis);
            offsetY = (getHeight() - 422 * scale) / 2 + burnIn.shiftY(millis);
            c.save(); c.translate(offsetX, offsetY); c.scale(scale, scale);
            long now = SystemClock.elapsedRealtimeNanos();
            Telemetry t = feed.latest;
            long age = t == null ? Long.MAX_VALUE : (now - t.receivedNanos) / 1_000_000;
            boolean fresh = age <= 250;
            boolean available = fresh && t != null && t.gameStatus() != 0;
            boolean shouldDim = burnIn.dim(millis, !available || t.gameStatus() == 3);
            dimmed = shouldDim;
            float dt = animationAt == 0 ? 0 : Math.min(.1f, (millis-animationAt)/1000f); animationAt = millis;
            frameSeconds = dt;
            dimAmount = Math.max(0, Math.min(1, dimAmount + (dimmed ? dt/8 : -dt*4)));
            feed.width = getWidth(); feed.height = getHeight(); feed.display = getDisplay().getDisplayId();
            if (available) { lapTiming.update(t); phase.update(t); }
            if (!available || phase.stage != RacePhase.OUTLAP) outlapFill = 0;
            if (!available) waiting(c, t, fresh);
            else if (t.pit != 0) pit(c, t);
            else content(c, t, now);
            p.setColor(fresh ? green : red); p.setStyle(Paint.Style.FILL); c.drawCircle(655, 22, 5, p);
            if (t != null && t.demo()) rightText(c, "演示", 639, 29, 17, amber, regular);
            else if (available && t.gameStatus() == 3) {
                p.setColor(muted); c.drawRect(627,14,631,29,p); c.drawRect(635,14,639,29,p);
            } else if (available && t.gameStatus() == 1) text(c, "↺", 620, 31, 23, amber, regular);
            // Secondary-display brightness may be controlled by the vivo firmware.
            // Also reduce the emitted pixel values, independently of that override.
            if (dimAmount > 0) {
                p.setStyle(Paint.Style.FILL); p.setColor(Color.argb(Math.round(96*dimAmount), 0, 0, 0));
                c.drawRect(-8/scale, -8/scale, 682+8/scale, 422+8/scale, p);
            }
            c.restore();
            drawCount++;
            if (now - lastLog >= 5_000_000_000L) {
                double seconds = lastLog == 0 ? 5 : (now - lastLog) / 1_000_000_000.0;
                String metrics = String.format(Locale.US,
                        "display=%d view=%dx%d page=%d rx=%d lost=%d RTT=%dms receiveToDraw=%dms draws=%.1f/s status=%d seq=%d map=%d shift=%.3f,%.3f dim=%s sectors=%d drs=%d style=%d scale=%d limit=%d drsDistance=%.1f guide=%d pit=%d pitLimit=%.0f context=%s ideal=%d recommended=%d phase=%d runLap=%d runStyle=%d held=%s shownSectors=%s startDistance=%.1f link=%s",
                        getDisplay().getDisplayId(), getWidth(), getHeight(), page, feed.received, feed.lost,
                        feed.rttMillis, t == null ? -1 : age, (drawCount - previousCount) / seconds,
                        t == null ? -1 : t.status, t == null ? -1 : t.sequence, feed.map == null ? 0 : feed.map.xs.length,
                        burnIn.shiftX(millis), burnIn.shiftY(millis), dimmed, t == null ? 0 : t.sectorCount, t == null ? 0 : t.drs,
                        style, t == null ? 0 : t.rpmScale(), t == null ? 0 : t.maxRpm, t == null ? -1 : t.drsDistance,
                        guideStyle, t == null ? 0 : t.pit, t == null ? 0 : t.pitSpeedLimit, t != null && t.raceContext,
                        lapTiming.idealTime(), t == null ? 0 : shift(t), phase.stage, phase.lapNumber, phase.selected(lapStyles),
                        lapTiming.finished(now), java.util.Arrays.toString(lapTiming.shownSectors(now)), t == null ? -1 : OutlapStatus.startDistance(t),feed.transport);
                Log.i("ACFlip", metrics);
                // Some vivo builds suppress app logcat. Keep the latest small diagnostic snapshot.
                final String snapshot = metrics;
                new Thread(() -> {
                    try (FileOutputStream file = new FileOutputStream(new File(getFilesDir(), "metrics.txt"))) {
                        file.write(snapshot.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) { Log.w("ACFlip", "Metrics", e); }
                }, "ACFlip-metrics").start();
                lastLog = now; previousCount = drawCount;
            }
            if (resumed) postInvalidateOnAnimation();
        }
        private void content(Canvas c, Telemetry t, long now) {
            if (layout == 10 && (preview || phase.stage == RacePhase.OUTLAP)) { warmup(c,t,now); return; }
            int selected = layout == 10 ? phase.selected(lapStyles) : layout;
            int savedStyle = style, savedGuide = guideStyle;
            if (selected < 4) { style = selected; gear(c,t); }
            else if (selected == 4) timing(c,t,now);
            else if (selected == 5) condition(c,t);
            else if (selected < 8) { guideStyle = selected-6; map(c,t); }
            else if (selected == 8) automotive(c,t,now);
            else reference(c,t,now);
            style = savedStyle; guideStyle = savedGuide;
        }
        private void createPreviews() {
            Telemetry sample = new Telemetry();
            sample.track = "preview"; sample.car = "preview"; sample.driver = "MAX VERSTAPPEN";
            sample.gear = 5; sample.rpm = 6800; sample.maxRpm = 7500; sample.scaleRpm = 9000;
            sample.speed = 214; sample.fuel = 24.5f; sample.maxFuel = 80;
            sample.current = 84214; sample.best = 85162; sample.delta = -.348f; sample.position = 1;
            sample.sectorCount = 3; sample.sectorIndex = 2; sample.cueCount = 3;
            sample.cueDirection[0] = -1; sample.cueGrade[0] = 3; sample.cueDistance[0] = 150;
            sample.cueDirection[1] = 1; sample.cueGrade[1] = 2; sample.cueDistance[1] = 340;
            sample.cueDirection[2] = 1; sample.cueGrade[2] = 6; sample.cueDistance[2] = 630;
            for (int i = 0; i < 4; i++) { sample.wear[i] = 100-i*16; sample.temperatures[i] = i == 0 ? 120 : 85; }
            int saved = layout, savedShift = shiftRpm; String savedCar = shiftCar;
            float savedFill = drsFill, savedFade = drsFade, savedOutlap = outlapFill, savedSeconds = frameSeconds;
            preview = true; frameSeconds = 1; drsFill = 1; drsFade = 0;
            Bitmap[] images = new Bitmap[LAYOUT_NAMES.length];
            try {
                for (int i = 0; i < LAYOUT_NAMES.length; i++) {
                    applyLayout(i); sample.drs = i == 1 || i == 2 ? 3 : 0; sample.drsDistance = 0;
                    if (page == 4) { sample.fuel = 1; sample.wear[2] = 10; sample.damage[0] = 20; sample.tyresOut = 3; sample.flag = 2; }
                    if (page == 5) {
                        sample.current = 83647; sample.best = 82931; sample.delta = .716f;
                        sample.fuel = 38.4f; sample.maxFuel = 50; sample.fuelPerLap = 3.2f;
                        sample.roadTemperature = 38; sample.grip = .98f; sample.wetness = sample.water = 0;
                        sample.wear[0] = sample.wear[1] = 88; sample.wear[2] = 64; sample.wear[3] = 69;
                        for (int wheel = 0; wheel < 4; wheel++) sample.temperatures[wheel] = 85;
                    }
                    if (page == 6) { sample.compound = "Street (ST)"; sample.temperatures[0] = sample.temperatures[1] = 42; sample.temperatures[2] = 85; sample.temperatures[3] = 115; sample.trackLength = 5793; sample.progress = .98f; }
                    Bitmap bitmap = Bitmap.createBitmap(324, 201, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(bitmap); c.drawColor(Color.BLACK); c.scale(324f/682, 201f/422);
                    content(c, sample, SystemClock.elapsedRealtimeNanos());
                    images[i] = bitmap;
                }
                stylePreviews = images;
            }
            finally { applyLayout(saved); shiftRpm = savedShift; shiftCar = savedCar; drsFill = savedFill; drsFade = savedFade; outlapFill = savedOutlap; frameSeconds = savedSeconds; preview = false; }
        }
        private void text(Canvas c, String s, float x, float y, float size, int color, Typeface face) {
            p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTextSize(size); p.setTypeface(face);
            p.setTextAlign(Paint.Align.LEFT); c.drawText(s, x, y, p);
        }
        private void rightText(Canvas c, String s, float x, float y, float size, int color, Typeface face) {
            p.setTextSize(size); p.setTypeface(face);
            text(c, s, x - p.measureText(s), y, size, color, face);
        }
        private void fitText(Canvas c, String s, float x, float y, float size, float width, int color, Typeface face) {
            p.setTextSize(size); p.setTypeface(face);
            float measured = p.measureText(s);
            text(c, s, x, y, measured > width ? size * width / measured : size, color, face);
        }
        private void box(Canvas c, float x, float y, float w, float h, int color) {
            p.setStyle(Paint.Style.FILL); p.setColor(color); c.drawRoundRect(x, y, x + w, y + h, 12, 12, p);
        }
        private void line(Canvas c, float x1, float y1, float x2, float y2, int color, float width) {
            p.setColor(color); p.setStrokeWidth(width); c.drawLine(x1, y1, x2, y2, p);
        }
        private void waiting(Canvas c, Telemetry t, boolean fresh) {
            centerText(c, fresh ? "已连接电脑" : "未连接电脑", 341, 120, 35, white, bold);
            centerText(c, "未检测到遥测数据", 341, 172, 29, muted, regular);
            box(c, 100, 218, 482, 65, panel);
            centerText(c, "连接", 341, 262, 31, white, bold);
        }
        private void centerText(Canvas c, String s, float x, float y, float size, int color, Typeface face) {
            p.setTextSize(size); p.setTypeface(face);
            text(c, s, x-p.measureText(s)/2, y, size, color, face);
        }
        private void gear(Canvas c, Telemetry t) {
            if (style == 1 || style == 2) { wheel(c, t, style == 2); return; }
            if (style == 3) { minimalGear(c, t); return; }
            int maximum = Math.max(1000, t.rpmScale());
            float ratio = maximum <= 0 ? 0 : Math.min(1f, (float)t.rpm / maximum);
            float redline = Math.min(1f, shift(t)/(float)maximum);
            int pink = Color.rgb(255, 39, 146);
            RectF arc = new RectF(182, 29, 500, 347);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(12); p.setStrokeCap(Paint.Cap.BUTT);
            p.setColor(white); c.drawArc(arc, 140, 260, false, p);
            p.setColor(pink); p.setStrokeWidth(14); c.drawArc(arc, 140+redline*260, (1-redline)*260, false, p);
            for (int value = 0; value <= maximum; value += 200) {
                float a = (float)Math.toRadians(140+value*260f/maximum);
                float cs = (float)Math.cos(a), sn = (float)Math.sin(a);
                int color = value >= shift(t) ? pink : white;
                boolean major = value%1000 == 0;
                float inner = major ? 134 : 150;
                line(c, 341+inner*cs, 188+inner*sn, 341+159*cs, 188+159*sn, color, major ? 6 : 3);
                if (major && value > 0) centerText(c, Integer.toString(value/1000), 341+174*cs,
                        188+174*sn+9, 27, color, racing);
            }
            float angle = (float)Math.toRadians(140+ratio*260);
            float cs = (float)Math.cos(angle), sn = (float)Math.sin(angle);
            line(c, 341+126*cs, 188+126*sn, 341+169*cs, 188+169*sn, pink, 12);
            centerText(c, t.gearText(), 341, 285, 238, t.rpm >= shift(t) ? pink : white, racing);
            text(c, Integer.toString(Math.round(t.speed)), 45, 375, 55, white, racing);
            text(c, "km/h", 47, 403, 20, muted, regular);
            rightText(c, Integer.toString(t.rpm), 410, 394, 36, white, racing);
            text(c, "rpm", 422, 394, 20, muted, regular);
            String warning = t.warning();
            if (!warning.isEmpty()) fitText(c, warning, 22, 35, 20, 200, amber, bold);
        }
        private void drsStrip(Canvas c, Telemetry t) {
            float target = (t.drs & 6) != 0 ? 1 : t.drsDistance >= 0 ? Math.max(0, 1-t.drsDistance/300f) : 0;
            float smoothing = 1-(float)Math.exp(-frameSeconds*12);
            drsFill += (target-drsFill)*smoothing;
            drsFade += (((t.drs & 4) != 0 ? 1 : 0)-drsFade)*smoothing;
            int active = Color.rgb(19, Math.round(210*(1-.65f*drsFade)), Math.round(108*(1-.65f*drsFade)));
            p.setStyle(Paint.Style.FILL); p.setColor(panel); c.drawRoundRect(26, 34, 656, 90, 10, 10, p);
            float filled = 315*drsFill;
            p.setColor(active);
            if (drsFill > .999f) c.drawRoundRect(26,34,656,90,10,10,p);
            else if (filled > 0) {
                c.drawRoundRect(26,34,26+filled,90,10,10,p);
                c.drawRoundRect(656-filled,34,656,90,10,10,p);
            }
            p.setStyle(Paint.Style.STROKE); p.setColor((t.drs & 6) != 0 ? active : muted); p.setStrokeWidth(3);
            c.drawRoundRect(26,34,656,90,10,10,p);
            centerText(c, "DRS", 341, 73, 34, white, racing);
            if (t.drsDistance > 0 && t.drsDistance < 300) rightText(c, Math.round(t.drsDistance)+"m", 638, 73, 27, white, racing);
        }
        private void wheel(Canvas c, Telemetry t, boolean bars) {
            boolean supported = (t.drs & 1) != 0;
            if (supported) drsStrip(c, t); else { drsFill = drsFade = 0; }
            float y = supported ? 123 : 64;
            float progress = Math.max(0, Math.min(1, (t.rpm/(float)shift(t)-.55f)/.45f));
            boolean blink = progress >= .98f && SystemClock.elapsedRealtime()%300 < 150;
            for (int i = 0; i < 15; i++) {
                int color = i < 5 ? green : i < 10 ? red : blue;
                float lit = Math.max(0, Math.min(1, progress*15-i));
                if (blink) lit = 1;
                int lamp = Color.rgb(Math.round(Color.red(panel)+(Color.red(color)-Color.red(panel))*lit),
                        Math.round(Color.green(panel)+(Color.green(color)-Color.green(panel))*lit),
                        Math.round(Color.blue(panel)+(Color.blue(color)-Color.blue(panel))*lit));
                p.setStyle(Paint.Style.FILL); p.setColor(lamp);
                float x = 42+i*(598f/14);
                if (bars) c.drawRoundRect(x-16,y-15,x+16,y+15,4,4,p); else c.drawCircle(x,y,15,p);
            }
            centerText(c, t.gearText(), 341, supported ? 383 : 366, supported ? 255 : 309, white, bold);
            text(c, Integer.toString(t.rpm), 28, 383, 36, white, racing); text(c, "rpm",28,408,18,muted,regular);
            rightText(c, Integer.toString(Math.round(t.speed)), 653, 383, 49, white, racing);
            rightText(c, "km/h", 653, 408, 18, muted, regular);
        }
        private void minimalGear(Canvas c, Telemetry t) {
            centerText(c, t.gearText(), 341, 341, 357, white, bold);
            float ratio = Math.min(1, t.rpm/(float)shift(t));
            box(c, 32, 360, 618, 12, panel); box(c, 32, 360, 618*ratio, 12, ratio > .95f ? red : green);
            text(c, Integer.toString(t.rpm), 32, 411, 34, white, racing);
            rightText(c, Math.round(t.speed)+" km/h",650,411,34,white,racing);
        }
        private int timingColor(int code) { return code == 3 ? purple : code == 1 ? green : code == 2 ? amber : white; }
        private String clockText(Telemetry t, long now) {
            int shown = preview ? t.current : lapTiming.shownTime(t, now);
            return !preview && lapTiming.finished(now) ? Telemetry.lapTime(shown)
                    : shown >= 60000 ? String.format(Locale.US, "%d:%02d.%d", shown/60000, shown/1000%60, shown/100%10)
                    : shown > 0 ? String.format(Locale.US, "%.1f", shown/1000f) : "0.0";
        }
        private void timing(Canvas c, Telemetry t, long now) {
            int clock = lapTiming.clockColor(t, now);
            p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(35, 32, 37)); c.drawRect(12, 10, 670, 413, p);
            p.setColor(red); c.drawRect(12, 10, 66, 60, p);
            centerText(c, t.position > 0 ? Integer.toString(t.position) : "—", 39, 49, 37, white, racing);
            String driver = t.driver.isEmpty() ? t.car : t.driver;
            fitText(c, driver.toUpperCase(Locale.ROOT), 82, 47, 31, 524, white, racing);
            line(c, 12, 63, 670, 63, white, 3);
            fitText(c, clockText(t, now), 25, 246, 153, 394, timingColor(clock), racing);
            fitText(c, Telemetry.lapTime(t.best), 433, 155, 48, 225, white, racing);
            text(c, "最佳", 435, 185, 22, muted, regular);
            float delta = lapTiming.shownDelta(t, now);
            fitText(c, Float.isFinite(delta) ? String.format(Locale.US, "%+.3f", delta) : "—", 432, 266, 48, 226,
                    Float.isFinite(delta) && delta < 0 ? green : amber, racing);
            int[] colors = preview ? new int[] { 3, 1, 2 } : lapTiming.shownColors(now);
            int[] sectorTimes = preview ? new int[] { 28571, 27388, 26910 } : lapTiming.shownSectors(now);
            int visible = Math.min(8, colors.length);
            int start = colors.length <= 8 ? 0 : Math.min((t.sectorIndex/8)*8, colors.length-1);
            visible = Math.min(visible, colors.length-start);
            int columns = Math.min(4, visible), rows = visible > 4 ? 2 : 1;
            float width = columns == 0 ? 0 : 638f/columns, top = rows == 1 ? 307 : 295;
            for (int i = 0; i < visible; i++) {
                int sector = start+i;
                float x = 22+(i%columns)*width, y = top+(i/columns)*56;
                int color = colors[sector] == 0 ? muted : timingColor(colors[sector]);
                centerText(c, "S"+(sector+1), x+width/2, y+(rows == 1 ? 29 : 21), rows == 1 ? 31 : 24, color, bold);
                String sectorTime = sectorTimes[sector] > 0 ? String.format(Locale.US, "%.3f", sectorTimes[sector]/1000f) : "—";
                p.setTypeface(racing); p.setTextSize(rows == 1 ? 43 : 29);
                float size = Math.min(rows == 1 ? 43 : 29, (rows == 1 ? 43 : 29)*(width-12)/Math.max(1,p.measureText(sectorTime)));
                centerText(c, sectorTime, x+width/2, y+(rows == 1 ? 74 : 47), size, white, racing);
                float bar = y+(rows == 1 ? 90 : 53);
                line(c, x+4, bar, x+width-4, bar, colors[sector] == 0 ? panel : color, rows == 1 ? 11 : 7);
                if (!lapTiming.finished(now) && sector == t.sectorIndex && colors[sector] == 0)
                    line(c, x+4, bar, x+width-4, bar, white, rows == 1 ? 11 : 7);
            }
            line(c, 12, 413, 670, 413, muted, 2);
        }
        private void condition(Canvas c, Telemetry t) {
            float ratio = t.maxFuel > 0 ? Math.max(0, Math.min(1, t.fuel/t.maxFuel)) : 0;
            fuelIcon(c, 53, 38, t.lowFuel() ? red : white);
            int fuelColor = t.lowFuel() ? red : ratio < .2f ? amber : white;
            for (int i = 0; i < 20; i++) {
                p.setStyle(Paint.Style.FILL); p.setColor(i < ratio*20 ? fuelColor : panel);
                c.drawRect(44, 311-i*10, 107, 318-i*10, p);
            }
            centerText(c, String.format(Locale.US, "%.1f", t.fuel), 75, 368, 48, fuelColor, racing);
            centerText(c, "L", 75, 398, 23, muted, regular);
            art.icon(c,8,271,90,162,muted);
            for (int i = 0; i < 4; i++) tyre(c, t, i, i%2 == 0 ? 174 : 430, i < 2 ? 60 : 262);
            for (float damage : t.damage) if (damage > 0) { centerText(c, "车损", 342, 39, 22, red, bold); break; }
        }
        private void fuelIcon(Canvas c, float x, float y, int color) {
            art.icon(c,0,x-5,y-3,58,color);
        }
        private void tyreThermal(Canvas c, Telemetry t, int wheel, float x, float y) {
            float temp = t.temperatures[wheel];
            int color = temp >= 110 ? red : temp > 0 && temp < 60 ? blue : temp > 0 ? green : muted;
            p.setStyle(Paint.Style.STROKE); p.setColor(color); p.setStrokeWidth(3);
            c.drawRoundRect(x,y,x+178,y+108,8,8,p);
            text(c,wheel == 0 ? "FL" : wheel == 1 ? "FR" : wheel == 2 ? "RL" : "RR",x+13,y+27,22,white,bold);
            art.icon(c,3,x+8,y+36,62,color);
            centerText(c,temp >= 110 ? "hot" : temp > 0 && temp < 60 ? "cold" : temp > 0 ? "✓" : "—",x+123,y+75,39,color,bold);
        }
        private void tyre(Canvas c, Telemetry t, int wheel, float x, float y) {
            int wear = t.wearPercent(wheel);
            int color = wear < 0 ? muted : wear < 20 ? green : wear < 50 ? Color.rgb(227, 213, 41)
                    : wear < 75 ? amber : red;
            p.setStyle(Paint.Style.FILL); p.setColor(color); c.drawRect(x, y, x+86, y+86, p);
            centerText(c, wear < 0 ? "—" : wear+"%", x+43, y+57, wear == 100 ? 34 : 39, Color.BLACK, racing);
            float temp = t.temperatures[wheel];
            if (temp >= 110 || temp > 0 && temp < 60) {
                int thermal = temp >= 110 ? red : blue;
                p.setStyle(Paint.Style.STROKE); p.setColor(thermal); p.setStrokeWidth(4);
                c.drawRect(x-5, y-5, x+91, y+91, p);
                centerText(c, temp >= 110 ? "hot" : "cold", x+43, y+125, 34, thermal, bold);
            }
        }
        private void map(Canvas c, Telemetry t) {
            if (guideStyle == 1) { flying(c, t); return; }
            if (t.cueCount == 0) centerText(c, "无路线", 341, 183, 39, muted, bold);
            else {
                paceTile(c, 24, 27, 268, t.cueDirection[0], t.cueGrade[0]);
                centerText(c, Math.max(0, Math.round(t.cueDistance[0]/10)*10)+"m", 158, 394, 67, white, racing);
                for (int i = 1; i < t.cueCount; i++) {
                    float x = 315+(i-1)*182;
                    paceTile(c, x, 55, 156, t.cueDirection[i], t.cueGrade[i]);
                    centerText(c, Math.max(0, Math.round(t.cueDistance[i]/10)*10)+"m", x+78, 252, 34, white, racing);
                }
            }
            if ((t.drs & 1) != 0) {
                int drsColor = (t.drs & 4) != 0 ? green : (t.drs & 2) != 0 ? Color.rgb(255, 225, 40) : muted;
                p.setStyle(Paint.Style.FILL); p.setColor((t.drs & 6) != 0 ? drsColor : panel);
                c.drawRect(315, 318, 653, 393, p);
                centerText(c, "DRS", 484, 374, 56, (t.drs & 6) != 0 ? Color.BLACK : white, racing);
                if ((t.drs & 2) != 0 && (t.drs & 4) == 0) {
                    p.setStyle(Paint.Style.STROKE); p.setColor(white); p.setStrokeWidth(3);
                    c.drawRect(320, 323, 648, 388, p);
                }
            }
        }
        private void flying(Canvas c, Telemetry t) {
            if (t.cueCount > 0) {
                paceTile(c, 22, 19, 97, t.cueDirection[0], t.cueGrade[0]);
                text(c, Math.max(0, Math.round(t.cueDistance[0]/10)*10)+"m", 140, 86, 44, white, racing);
            } else text(c, "—", 30, 85, 54, muted, racing);
            rightText(c, "理想", 575, 37, 25, muted, regular);
            rightText(c, Telemetry.lapTime(preview ? 83127 : lapTiming.idealTime()), 652, 99, 52, white, racing);
            line(c, 22, 135, 660, 135, panel, 2);
            long now = SystemClock.elapsedRealtimeNanos();
            fitText(c, clockText(t, now), 22, 311, 168, 638, timingColor(lapTiming.clockColor(t, now)), racing);
            line(c, 22, 342, 660, 342, panel, 2);
            text(c, String.format(Locale.US, "%.1f L", t.fuel), 24, 404, 47, t.lowFuel() ? red : white, racing);
            int wear = -1; boolean hot = false, cold = false;
            for (int i = 0; i < 4; i++) {
                wear = Math.max(wear, t.wearPercent(i));
                hot |= t.temperatures[i] >= 110; cold |= t.temperatures[i] > 0 && t.temperatures[i] < 60;
            }
            text(c, "胎损", 267, 368, 21, muted, regular);
            text(c, wear < 0 ? "—" : wear+"%", 267, 407, 43, wear >= 75 ? red : wear >= 50 ? amber : green, racing);
            rightText(c, hot ? "hot" : cold ? "cold" : "—", 652, 404, 49, hot ? red : cold ? blue : green, bold);
        }
        private void pit(Canvas c, Telemetry t) {
            centerText(c,t.gearText(),91,95,102,white,bold);
            centerText(c,Math.round(t.speed)+"",220,66,45,t.pitSpeedLimit > 0 && t.speed > t.pitSpeedLimit ? red : white,bold);
            centerText(c,"km/h",220,92,18,muted,regular);
            p.setStyle(Paint.Style.FILL); p.setColor(white); c.drawCircle(341,50,41,p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(7); p.setColor(red); c.drawCircle(341,50,38,p);
            centerText(c,t.pitSpeedLimit > 0 ? Integer.toString(Math.round(t.pitSpeedLimit)) : "—",341,65,39,Color.BLACK,bold);
            fuelIcon(c,419,26,t.lowFuel() ? red : white);
            rightText(c,String.format(Locale.US,"%.1f L",t.fuel),654,76,43,t.lowFuel() ? red : white,bold);
            line(c,20,110,662,110,panel,2);
            fitText(c,t.compound.isEmpty() ? "轮胎 —" : t.compound,24,139,26,234,white,bold);
            for (int i = 0; i < 4; i++) {
                float x = i%2 == 0 ? 38 : 166, y = i < 2 ? 160 : 286;
                int wear = t.wearPercent(i), color = wear < 0 ? muted : wear < 20 ? green : wear < 50 ? Color.rgb(227,213,41) : wear < 75 ? amber : red;
                text(c,i == 0 ? "FL" : i == 1 ? "FR" : i == 2 ? "RL" : "RR",x,y-3,17,muted,bold);
                p.setStyle(Paint.Style.FILL); p.setColor(color); c.drawRect(x,y,x+68,y+68,p);
                centerText(c,wear < 0 ? "—" : wear+"%",x+34,y+46,wear == 100 ? 29 : 34,Color.BLACK,bold);
                float temp = t.temperatures[i];
                if (temp >= 110 || temp > 0 && temp < 60) {
                    int thermal = temp >= 110 ? red : blue;
                    p.setStyle(Paint.Style.STROKE); p.setColor(thermal); p.setStrokeWidth(3); c.drawRect(x-3,y-3,x+71,y+71,p);
                    centerText(c,temp >= 110 ? "hot" : "cold",x+34,y+95,26,thermal,bold);
                }
            }
            int weather = t.trackCondition(); art.icon(c,weather == 0 ? 6 : 7,295,139,78,weather == 0 ? amber : weather > 0 ? blue : muted);
            text(c,weather == 0 ? "DRY" : weather == 1 ? "DAMP" : weather == 2 ? "WET" : "—",387,184,42,white,bold);
            text(c,t.roadTemperature < -50 ? "—" : Math.round(t.roadTemperature)+"°C",389,223,33,white,bold);
            text(c,t.grip < 0 ? "Grip —" : String.format(Locale.US,"Grip %.0f%%",t.grip*100),389,257,25,t.grip >= .97f ? green : amber,bold);
            line(c,293,283,652,283,panel,2);
            text(c,"理想",303,316,24,muted,regular);
            art.number(c,Telemetry.lapTime(preview ? 83127 : lapTiming.idealTime()),475,387,62,345,white);
        }
        private void paceTile(Canvas c, float x, float y, float size, int direction, int grade) {
            int color = grade <= 1 ? Color.rgb(213, 46, 53) : grade == 2 ? Color.rgb(226, 108, 33)
                    : grade == 3 ? Color.rgb(202, 158, 17) : grade == 4 ? Color.rgb(131, 170, 35) : Color.rgb(24, 152, 82);
            p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(16,16,16)); c.drawRoundRect(x, y, x+size, y+size, 8, 8, p);
            p.setStyle(Paint.Style.STROKE); p.setColor(color); p.setStrokeWidth(size > 200 ? 8 : 5);
            c.drawRoundRect(x, y, x+size, y+size, 8, 8, p);
            arrow(c, x+size*.08f, y+size*.04f, size*.81f, direction, grade);
            rightText(c, grade == 0 ? "HP" : Integer.toString(grade), x+size*.94f, y+size*.94f,
                    size*.25f, color, racing);
        }
        private void arrow(Canvas c, float x, float y, float size, int direction, int grade) {
            c.save(); c.translate(x+(direction < 0 ? size : 0), y);
            c.scale((direction < 0 ? -1 : 1)*size/100f, size/100f);
            Path path = new Path(); float endX, endY, dx, dy;
            path.moveTo(30, 90);
            if (grade == 0) {
                path.lineTo(30, 32); path.cubicTo(30, 7, 73, 7, 73, 32);
                endX=73; endY=72; dx=0; dy=1;
            } else if (grade == 1) {
                path.lineTo(30, 48); path.lineTo(60, 19);
                endX=89; endY=47; dx=29; dy=28;
            } else if (grade == 2) {
                path.lineTo(30, 45); path.quadTo(30, 25, 50, 25);
                endX=92; endY=25; dx=1; dy=0;
            } else {
                float turnX = grade == 3 ? 47 : grade == 4 ? 41 : grade == 5 ? 36 : 32;
                path.lineTo(30, 59); path.quadTo(30, 43, turnX, 33);
                endX=grade == 3 ? 87 : grade == 4 ? 73 : grade == 5 ? 58 : 43;
                endY=12; dx=endX-turnX; dy=endY-33;
            }
            float length = (float)Math.hypot(dx, dy); dx /= length; dy /= length;
            path.lineTo(endX-dx*12, endY-dy*12);
            p.setStyle(Paint.Style.STROKE); p.setColor(white); p.setStrokeWidth(16);
            p.setStrokeCap(Paint.Cap.BUTT); p.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(path, p);
            Path head = new Path(); head.moveTo(endX, endY);
            head.lineTo(endX-dx*24-dy*18, endY-dy*24+dx*18);
            head.lineTo(endX-dx*24+dy*18, endY-dy*24-dx*18); head.close();
            p.setStyle(Paint.Style.FILL); c.drawPath(head, p);
            c.restore();
        }
        private void reference(Canvas c, Telemetry t, long now) {
            int label = Color.rgb(166,193,222), edge = Color.rgb(25,127,167), lime = Color.rgb(105,244,43);
            referenceBox(c,12,12,362,133,edge); referenceBox(c,12,152,200,93,edge);
            referenceBox(c,220,152,154,93,edge); referenceBox(c,382,12,288,233,edge);
            referenceBox(c,12,253,424,157,edge); referenceBox(c,444,253,226,157,edge);
            text(c,"LAP TIME",26,42,26,label,bold);
            int clock = preview ? t.current : lapTiming.shownTime(t,now);
            fitText(c,clock > 0 ? Telemetry.lapTime(clock) : "0:00.000",24,133,103,338,white,bold);
            text(c,"BEST LAP",26,179,22,label,bold);
            fitText(c,Telemetry.lapTime(t.best),24,227,52,177,white,bold);
            float delta = preview ? t.delta : lapTiming.shownDelta(t,now);
            int deltaColor = Float.isFinite(delta) && delta < 0 ? green : red;
            text(c,"DELTA",234,179,22,deltaColor,bold);
            fitText(c,Float.isFinite(delta) ? String.format(Locale.US,"%+.3f",delta) : "—",233,227,52,129,deltaColor,bold);
            text(c,"TYRE WEAR",396,42,25,label,bold);
            c.save(); c.scale(1,.88f);
            p.setStyle(Paint.Style.STROKE); p.setColor(label); p.setStrokeWidth(2);
            Path car = new Path(); car.moveTo(527,59);
            car.cubicTo(500,59,498,68,496,88); car.lineTo(496,233); car.cubicTo(496,258,558,258,558,233);
            car.lineTo(558,88); car.cubicTo(556,68,554,59,527,59); car.close(); c.drawPath(car,p);
            Path windows = new Path(); windows.moveTo(497,112); windows.quadTo(527,94,557,112);
            windows.lineTo(551,142); windows.quadTo(527,131,503,142); windows.close(); c.drawPath(windows,p);
            windows.reset(); windows.moveTo(503,183); windows.quadTo(527,194,551,183);
            windows.lineTo(554,222); windows.quadTo(527,239,500,222); windows.close(); c.drawPath(windows,p);
            c.restore();
            for (int i = 0; i < 4; i++) {
                float x = i%2 == 0 ? 396 : 594, y = i < 2 ? 80 : 176;
                int wear = t.wearPercent(i), color = wear < 0 ? panel : wear < 20 ? lime : wear < 50 ? Color.rgb(239,224,34) : wear < 75 ? amber : red;
                text(c,i == 0 ? "FL" : i == 1 ? "FR" : i == 2 ? "RL" : "RR",x,y,22,label,bold);
                fitText(c,wear < 0 ? "—" : wear+"%",x,y+37,36,69,white,bold);
                p.setStyle(Paint.Style.FILL); p.setColor(color);
                float block = i%2 == 0 ? 476 : 565; c.drawRoundRect(block,y-7,block+14,y+39,2,2,p);
                float temp = t.temperatures[i];
                if (temp >= 110 || temp > 0 && temp < 60) text(c,temp >= 110 ? "hot" : "cold",x,y+61,22,temp >= 110 ? red : blue,bold);
            }
            text(c,"TRACK CONDITION",26,283,23,label,bold);
            line(c,278,267,278,399,Color.rgb(35,58,73),1);
            int condition = t.trackCondition(), weatherColor = condition == 0 ? Color.rgb(255,204,0) : condition == 1 ? amber : condition == 2 ? blue : muted;
            if (condition == 0) {
                p.setStyle(Paint.Style.FILL); p.setColor(weatherColor); c.drawCircle(52,329,16,p);
                for (int i = 0; i < 8; i++) { double a = i*Math.PI/4; line(c,52+(float)Math.cos(a)*23,329+(float)Math.sin(a)*23,52+(float)Math.cos(a)*30,329+(float)Math.sin(a)*30,weatherColor,3); }
            } else if (condition > 0) {
                p.setStyle(Paint.Style.STROKE); p.setColor(weatherColor); p.setStrokeWidth(3);
                Path cloud = new Path(); cloud.moveTo(31,334); cloud.cubicTo(19,315,36,304,45,314);
                cloud.cubicTo(54,294,79,307,73,319); cloud.cubicTo(89,318,87,335,73,336); cloud.close(); c.drawPath(cloud,p);
                for (int i = 0; i < (condition == 2 ? 3 : 1); i++) line(c,44+i*13,341,40+i*13,348,weatherColor,3);
            } else centerText(c,"—",52,342,38,muted,bold);
            text(c,condition == 0 ? "DRY" : condition == 1 ? "DAMP" : condition == 2 ? "WET" : "—",94,345,46,white,bold);
            for (int i = 0; i < 3; i++) {
                float x = 25+i*80; p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(i == condition ? 2 : 1);
                p.setColor(i == condition ? i == 0 ? lime : i == 1 ? amber : blue : Color.rgb(63,87,107)); c.drawRoundRect(x,373,x+73,401,3,3,p);
                centerText(c,i == 0 ? "DRY" : i == 1 ? "DAMP" : "WET",x+36.5f,395,19,i == condition ? white : label,bold);
            }
            text(c,"TRACK TEMP",291,283,19,label,bold);
            c.save(); c.translate(287,298); c.scale(.55f,.55f); fault(c,0,0,3,white); c.restore();
            fitText(c,t.roadTemperature < -50 ? "—" : Math.round(t.roadTemperature)+"°C",319,327,36,107,white,bold);
            text(c,"GRIP LEVEL",291,353,19,label,bold);
            int gripColor = t.grip >= .97f ? lime : t.grip >= .9f ? amber : red;
            int filled = t.grip < 0 ? 0 : Math.round(t.grip*7);
            for (int i = 0; i < 7; i++) { p.setStyle(Paint.Style.FILL); p.setColor(i < filled ? gripColor : panel); c.drawRect(291+i*18,363,306+i*18,382,p); }
            text(c,t.grip < 0 ? "—" : t.grip >= .97f ? "High" : t.grip >= .9f ? "Med" : "Low",291,403,20,t.grip < 0 ? muted : gripColor,bold);
            text(c,"FUEL",459,283,25,label,bold);
            c.save(); c.translate(456,308); c.scale(.88f,.88f); fuelIcon(c,0,0,t.lowFuel() ? red : white); c.restore();
            int laps = t.fuelLaps();
            fitText(c,laps >= 0 ? laps+" LAPS" : String.format(Locale.US,"%.1f L",t.fuel),504,351,47,151,t.lowFuel() ? red : white,bold);
            float fuel = t.maxFuel > 0 ? Math.max(0,Math.min(1,t.fuel/t.maxFuel)) : 0;
            p.setStyle(Paint.Style.FILL); p.setColor(panel); c.drawRoundRect(458,389,656,404,3,3,p);
            p.setColor(t.lowFuel() ? red : lime); c.drawRoundRect(458,389,458+198*fuel,404,3,3,p);
        }
        private void referenceBox(Canvas c, float x, float y, float width, float height, int color) {
            p.setStyle(Paint.Style.STROKE); p.setColor(color); p.setStrokeWidth(1.5f); c.drawRoundRect(x,y,x+width,y+height,7,7,p);
        }
        private void autoCenter(Canvas c, Telemetry t, long now) {
            art.panel(c);
            text(c, "最佳", 116, 71, 21, muted, regular);
            art.number(c,Telemetry.lapTime(t.best),204,131,39,179,white);
            rightText(c, "理想", 566, 71, 21, muted, regular);
            art.number(c,Telemetry.lapTime(preview ? 83127 : lapTiming.idealTime()),477,131,39,179,white);
            art.number(c,clockText(t,now),341,265,123,454,timingColor(lapTiming.clockColor(t,now)));
            float delta = preview ? t.delta : lapTiming.shownDelta(t, now);
            art.number(c,Float.isFinite(delta) ? String.format(Locale.US,"%+.3f",delta) : "—",341,330,49,350,Float.isFinite(delta) && delta < 0 ? green : amber);
        }
        private void warmup(Canvas c, Telemetry t, long now) {
            text(c,"出场圈",30,31,24,white,bold);
            float distance = OutlapStatus.startDistance(t), target = preview ? .65f : OutlapStatus.startFill(t);
            outlapFill += (target-outlapFill)*Math.min(1,frameSeconds*10);
            if (distance >= 0 && distance <= 300) rightText(c,"飞驰圈 "+Math.round(distance)+"m",588,31,26,green,bold);
            box(c,30,43,622,30,panel);
            box(c,30,43,311*outlapFill,30,green); box(c,652-311*outlapFill,43,311*outlapFill,30,green);
            if (distance >= 0 && distance <= 300) {
                p.setStyle(Paint.Style.STROKE); p.setColor(green); p.setStrokeWidth(3);
                c.drawRoundRect(30,43,652,73,5,5,p);
            }
            tyreThermal(c,t,0,31,82); tyreThermal(c,t,1,227,82);
            tyreThermal(c,t,2,31,205); tyreThermal(c,t,3,227,205);
            int weather = t.trackCondition(); art.icon(c,weather == 0 ? 6 : 7,432,79,65,weather == 0 ? amber : weather > 0 ? blue : muted);
            rightText(c,weather == 0 ? "DRY" : weather == 1 ? "DAMP" : weather == 2 ? "WET" : "—",652,130,40,white,bold);
            text(c,t.roadTemperature < -50 ? "路温 —" : "路温 "+Math.round(t.roadTemperature)+"°C",435,179,28,white,bold);
            text(c,t.grip < 0 ? "抓地 —" : String.format(Locale.US,"抓地 %.0f%%",t.grip*100),435,219,28,t.grip >= .97f ? green : amber,bold);
            art.icon(c,0,433,284,30,t.lowFuel() ? red : white);
            rightText(c,String.format(Locale.US,"%.1f L",t.fuel),652,311,30,t.lowFuel() ? red : white,bold);
            String[] problems = OutlapStatus.problems(t);
            int light = problems.length == 0 ? green : red;
            float pulse = .35f+.65f*(.5f+.5f*(float)Math.sin(now/1_000_000_000.0*Math.PI*2*1.2));
            int flash = Color.argb(Math.round(255*pulse),Color.red(light),Color.green(light),Color.blue(light));
            box(c,8,82,9,331,flash); box(c,665,82,9,331,flash);
            line(c,30,325,652,325,panel,2);
            for (int i = 0; i < problems.length; i++) centerText(c,problems[i],133+(i%3)*208,351+(i/3)*28,23,red,bold);
        }
        private void automotive(Canvas c, Telemetry t, long now) {
            autoCenter(c,t,now);
            boolean damage = false, hot = false, cold = false, worn = false;
            for (float value : t.damage) damage |= value > 0;
            for (int i = 0; i < 4; i++) { hot |= t.temperatures[i] >= 110; cold |= t.temperatures[i] > 0 && t.temperatures[i] < 60; worn |= t.wearPercent(i) >= 75; }
            int quiet = Color.rgb(116,136,154);
            art.icon(c,0,13,58,62,t.lowFuel() ? red : quiet); centerText(c,"油量",44,136,20,t.lowFuel() ? red : muted,bold);
            art.icon(c,1,13,156,62,worn ? amber : quiet); centerText(c,"胎损",44,234,20,worn ? amber : muted,bold);
            art.icon(c,2,13,254,62,damage ? red : quiet); centerText(c,"车损",44,332,20,damage ? red : muted,bold);
            art.icon(c,3,607,58,62,hot ? red : cold ? blue : quiet); centerText(c,"胎温",638,136,20,hot ? red : cold ? blue : muted,bold);
            art.icon(c,4,607,156,62,t.tyresOut >= 3 ? amber : quiet); centerText(c,"出界",638,234,20,t.tyresOut >= 3 ? amber : muted,bold);
            art.icon(c,5,607,254,62,t.flag == 2 ? amber : t.flag == 1 ? blue : t.flag == 3 ? red : quiet); centerText(c,"旗号",638,332,20,t.flag == 2 ? amber : muted,bold);
            int[] colors = lapTiming.shownColors(now);
            int count = preview ? 3 : Math.min(8, colors.length);
            int start = !preview && colors.length > 8 ? Math.min(t.sectorIndex/8*8, colors.length-1) : 0;
            if (!preview) count = Math.min(count, colors.length-start);
            float width = count == 0 ? 0 : 634f/count;
            for (int i = 0; i < count; i++) {
                int color = preview ? i == 0 ? purple : i == 1 ? green : amber : colors[start+i] == 0 ? panel : timingColor(colors[start+i]);
                centerText(c, "S"+(start+i+1), 24+width*(i+.5f), 383, 24, color == panel ? muted : color, bold);
                line(c, 28+width*i, 403, 20+width*(i+1), 403, color, 10);
            }
        }
        private void fault(Canvas c, float x, float y, int kind, int color) {
            art.icon(c,kind,x,y,48,color);
        }
    }
}
