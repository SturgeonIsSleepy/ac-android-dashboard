package cn.acflip.dash;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;

final class SettingsDialog extends Dialog {
    private SharedPreferences preferences;
    private LinearLayout root, content;
    private int section, maximum;
    private String car;
    private int editingLap = -1;
    private final UdpFeed feed;
    private int editingMirror = 1;
    private SeekBar[] mirrorSliders;
    private TextView[] mirrorValues;
    private boolean[] mirrorDragging;
    private TextView mirrorStatus;
    private Button mirrorReset;
    private Runnable mirrorRefresh;
    SettingsDialog(Activity owner, int maxRpm, String currentCar, UdpFeed source) {
        super(owner);
        feed = source;
        maximum = maxRpm; car = currentCar == null ? "" : currentCar;
        preferences = owner.getSharedPreferences("MainActivity",0);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        getWindow().setBackgroundDrawableResource(android.R.color.black);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        show(0);
    }
    @Override public void show() {
        super.show();
        getWindow().setLayout(-1,-1);
        getWindow().getDecorView().setPadding(0,0,0,0);
        getWindow().getDecorView().setSystemUiVisibility(5894);
    }
    private TextView label(String value, int size) {
        TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(size); view.setTextColor(Color.WHITE);
        return view;
    }
    private Button button(String value, Runnable action) {
        Button view = new Button(getContext()); view.setText(value); view.setTextSize(14); view.setTextColor(Color.WHITE);
        view.setAllCaps(false); view.setSingleLine(true); view.setIncludeFontPadding(false); view.setGravity(Gravity.CENTER);
        view.setMinHeight(0); view.setMinimumHeight(0); view.setPadding(dp(8),dp(4),dp(8),dp(4));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.rgb(49,49,49)); background.setCornerRadius(dp(5));
        view.setBackgroundTintList(null);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.rgb(83,83,83)),background,null));
        view.setOnClickListener(v -> action.run()); return view;
    }
    private void show(int page) {
        stopMirrorRefresh();
        section = page;
        root = new LinearLayout(getContext()); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(5), dp(12), dp(4)); root.setBackgroundColor(Color.BLACK);
        String[] titles = { "设置", "选择样式", "换挡转速", "电脑连接", "按圈切换", "后视镜", "调整" };
        LinearLayout header = new LinearLayout(getContext());
        header.addView(label(page == 6 ? new String[] {"左","中","右"}[editingMirror-1]+"后视镜调整" : titles[page],19),new LinearLayout.LayoutParams(0,dp(29),1));
        if (page == 4) header.addView(button("启用", () -> { preferences.edit().putInt("layout",10).putInt("mirror",0).apply(); dismiss(); }),new LinearLayout.LayoutParams(dp(64),dp(29)));
        if (page == 6) header.addView(button("预览", () -> dismiss()),new LinearLayout.LayoutParams(dp(64),dp(29)));
        root.addView(header);
        content = new LinearLayout(getContext()); content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        if (page == 0) {
            String[][] names = { {"固定样式","按圈切换"}, {"换挡转速","后视镜"}, {"电脑连接"} };
            int[][] destinations = { {1,4}, {2,5}, {3} };
            for (int group = 0; group < 3; group++) {
                content.addView(label(group == 0 ? "显示" : group == 1 ? "车辆" : "连接",13),new LinearLayout.LayoutParams(-1,dp(19)));
                LinearLayout row = new LinearLayout(getContext());
                for (int column = 0; column < names[group].length; column++) {
                    final int choice = destinations[group][column];
                    Button item = button(names[group][column], () -> { editingLap = -1; show(choice); });
                    if (group == 0 && preferences.getInt("mirror",0) == 0 && (preferences.getInt("layout",0) == 10 ? column == 1 : column == 0)
                            || group == 1 && column == 1 && preferences.getInt("mirror",0) != 0) item.setTextColor(Color.rgb(19,210,108));
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,-1,1);
                    params.setMargins(dp(2),dp(2),dp(2),dp(2)); row.addView(item,params);
                }
                content.addView(row,new LinearLayout.LayoutParams(-1,0,1));
            }
        } else if (page == 1) gallery();
        else if (page == 2) rpm();
        else if (page == 4) phaseStyles();
        else if (page == 5) {
            String[] names = {"仪表","左","中","右"};
            for (int i = 0; i < names.length; i++) {
                final int selected = i;
                Button choice = button(names[i], () -> { selectMirror(selected); dismiss(); });
                if (preferences.getInt("mirror",0) == i) choice.setTextColor(Color.rgb(19,210,108));
                LinearLayout row = new LinearLayout(getContext());
                row.addView(choice,new LinearLayout.LayoutParams(0,-1,1));
                if (i > 0) {
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(72),-1);
                    params.leftMargin = dp(6);
                    row.addView(button("调整", () -> { selectMirror(selected); editingMirror = selected; show(6); }),params);
                }
                content.addView(row,new LinearLayout.LayoutParams(-1,0,1));
            }
        }
        else if (page == 6) mirrorSettings();
        else {
            content.addView(label("有线优先，断开后自动转无线",14));
            content.addView(label(preferences.getString("host", "未设置电脑 IP"), 19));
            content.addView(button("电脑 IP", () -> connection()), new LinearLayout.LayoutParams(-1, dp(40)));
        }
        if (page != 6) root.addView(button(page == 0 ? "返回仪表" : editingLap >= 0 && page == 1 ? "返回圈序" : "返回设置", () -> { if (section == 0) dismiss(); else if (editingLap >= 0 && section == 1) { editingLap = -1; show(4); } else show(0); }),
                new LinearLayout.LayoutParams(-1, dp(40)));
        setContentView(root);
    }
    private void selectMirror(int selected) {
        preferences.edit().putInt("mirror",selected).apply(); feed.mirrorView = selected;
    }
    private void mirrorSettings() {
        mirrorStatus = label("",12); content.addView(mirrorStatus);
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout list = new LinearLayout(getContext()); list.setOrientation(LinearLayout.VERTICAL);
        mirrorSliders = new SeekBar[6]; mirrorValues = new TextView[6]; mirrorDragging = new boolean[6];
        for (int i = 0; i < 6; i++) {
            final int field = i;
            TextView value = label("",14); mirrorValues[i] = value;
            value.setPadding(0,dp(3),0,0); list.addView(value);
            SeekBar slider = new SeekBar(getContext()); mirrorSliders[i] = slider;
            slider.setMax(Math.round((MirrorConfig.MAX[i]-MirrorConfig.MIN[i])/MirrorConfig.STEP[i]));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                    if (!user) return;
                    float next = MirrorConfig.MIN[field]+progress*MirrorConfig.STEP[field];
                    if (feed.setMirrorParameter(editingMirror,field,next)) value.setText(mirrorValue(field,next));
                    else refreshMirrorSettings();
                }
                public void onStartTrackingTouch(SeekBar bar) { mirrorDragging[field] = true; }
                public void onStopTrackingTouch(SeekBar bar) { mirrorDragging[field] = false; }
            });
            list.addView(slider,new LinearLayout.LayoutParams(-1,dp(36)));
        }
        scroll.addView(list); content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions = new LinearLayout(getContext());
        actions.addView(button("返回镜面选择", () -> show(5)),new LinearLayout.LayoutParams(0,dp(40),1));
        mirrorReset = button("恢复默认", () -> feed.setMirrorParameter(editingMirror,6,0));
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(0,dp(40),1); resetParams.leftMargin = dp(6);
        actions.addView(mirrorReset,resetParams); content.addView(actions);
        refreshMirrorSettings();
        LinearLayout pageRoot = root;
        mirrorRefresh = new Runnable() {
            public void run() {
                if (section != 6 || root != pageRoot || !isShowing()) return;
                refreshMirrorSettings(); pageRoot.postDelayed(this,200);
            }
        };
        pageRoot.postDelayed(mirrorRefresh,200);
    }
    private String mirrorValue(int field,float value) {
        String[] names = {"视野角（上下）","水平朝向","俯仰","左右偏移","高度偏移","前后偏移"};
        String[] negative = {"","左 ","低头 ","左 ","下 ","后 "};
        String[] positive = {"","右 ","抬头 ","右 ","上 ","前 "};
        String direction = value < -.001f ? negative[field] : value > .001f ? positive[field] : "";
        return names[field]+"　"+direction+String.format(Locale.US,field < 3 ? "%.1f°" : "%.2f 米",Math.abs(value));
    }
    private void refreshMirrorSettings() {
        boolean enabled = feed.mirrorSettingsReady;
        MirrorConfig settings = feed.mirrorConfig;
        mirrorStatus.setText(feed.mirrorSettingsError ? "电脑配置读写失败，未保存" : enabled ? "已连接，可实时调整" : feed.transport.equals("USB") || feed.transport.equals("TCP") ? "等待电脑参数" : "请连接有线或更新电脑端");
        mirrorReset.setEnabled(enabled); mirrorReset.setAlpha(enabled ? 1f : .45f);
        for (int field = 0; field < 6; field++) {
            mirrorSliders[field].setEnabled(enabled);
            if (!enabled) mirrorDragging[field] = false;
            if (mirrorDragging[field]) continue;
            float value = settings.values[editingMirror-1][field];
            mirrorSliders[field].setProgress(Math.round((value-MirrorConfig.MIN[field])/MirrorConfig.STEP[field]));
            mirrorValues[field].setText(mirrorValue(field,value));
        }
    }
    private void stopMirrorRefresh() {
        if (mirrorRefresh != null && root != null) root.removeCallbacks(mirrorRefresh);
        mirrorRefresh = null;
    }
    @Override public void dismiss() { stopMirrorRefresh(); super.dismiss(); }
    @Override protected void onStop() { stopMirrorRefresh(); super.onStop(); }
    private void gallery() {
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout grid = new LinearLayout(getContext()); grid.setOrientation(LinearLayout.VERTICAL);
        int[] plan = RacePhase.styles(preferences.getString("lapStyles","9"));
        int selected = editingLap >= 0 ? plan[editingLap] : preferences.getInt("layout", 0);
        int count = MainActivity.LAYOUT_NAMES.length-1;
        for (int row = 0; row < (count+2)/3; row++) {
            LinearLayout line = new LinearLayout(getContext());
            for (int column = 0; column < 3; column++) {
                final int choice = row*3+column;
                if (choice >= count) {
                    line.addView(new TextView(getContext()), new LinearLayout.LayoutParams(0, -1, 1)); continue;
                }
                LinearLayout tile = new LinearLayout(getContext()); tile.setOrientation(LinearLayout.VERTICAL);
                tile.setPadding(dp(3),dp(3),dp(3),dp(3));
                GradientDrawable border = new GradientDrawable(); border.setColor(Color.rgb(16,16,16));
                border.setCornerRadius(dp(5)); border.setStroke(dp(2), choice == selected ? Color.rgb(19,210,108) : Color.rgb(49,49,49));
                tile.setBackground(border);
                ImageView image = new ImageView(getContext()); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                if (MainActivity.stylePreviews != null) image.setImageBitmap(MainActivity.stylePreviews[choice]);
                tile.addView(image, new LinearLayout.LayoutParams(-1, dp(66)));
                String number = String.format(Locale.US,"%02d",choice+1);
                TextView name = label(number, 18); name.setGravity(android.view.Gravity.CENTER);
                if (choice == selected) name.setTextColor(Color.rgb(19,210,108));
                tile.addView(name, new LinearLayout.LayoutParams(-1, dp(24)));
                tile.setContentDescription(number+"，点击选择"); tile.setSelected(choice == selected);
                tile.setOnClickListener(v -> {
                    if (editingLap < 0) { preferences.edit().putInt("layout", choice).putInt("mirror",0).apply(); dismiss(); }
                    else { plan[editingLap] = choice; savePlan(plan); editingLap = -1; show(4); }
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                params.setMargins(dp(2),dp(2),dp(2),dp(2)); line.addView(tile,params);
            }
            grid.addView(line);
        }
        scroll.addView(grid); content.addView(scroll, new LinearLayout.LayoutParams(-1,-1));
    }
    private void savePlan(int[] plan) {
        StringBuilder value = new StringBuilder();
        for (int style : plan) { if (value.length() > 0) value.append(','); value.append(style); }
        preferences.edit().putString("lapStyles",value.toString()).apply();
    }
    private void phaseStyles() {
        int[] plan = RacePhase.styles(preferences.getString("lapStyles","9"));
        ScrollView scroll = new ScrollView(getContext()); LinearLayout list = new LinearLayout(getContext()); list.setOrientation(LinearLayout.VERTICAL);
        list.addView(label("维修区 → 出场圈 → 自定义圈序",13));
        for (int i = 0; i < plan.length; i++) {
            final int index = i;
            list.addView(button("第"+(i+1)+"圈　"+String.format(Locale.US,"%02d",plan[i]+1), () -> { editingLap = index; show(1); }),new LinearLayout.LayoutParams(-1,dp(40)));
        }
        LinearLayout actions = new LinearLayout(getContext());
        actions.addView(button("增加一圈", () -> { int[] next = java.util.Arrays.copyOf(plan,plan.length+1); next[plan.length] = plan[plan.length-1]; savePlan(next); show(4); }),new LinearLayout.LayoutParams(0,dp(40),1));
        if (plan.length > 1) actions.addView(button("删最后一圈", () -> { savePlan(java.util.Arrays.copyOf(plan,plan.length-1)); show(4); }),new LinearLayout.LayoutParams(0,dp(40),1));
        list.addView(actions); list.addView(label("最后一套沿用，进站后重启",12));
        scroll.addView(list); content.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
    }
    private void rpm() {
        content.addView(label("引擎最大转速  " + (maximum > 0 ? maximum : "—"), 17));
        TextView value = label("", 17); content.addView(value);
        if (maximum < 500 || car.isEmpty()) { value.setText("连接车辆后调整"); return; }
        String key = "shift:"+car;
        SeekBar slider = new SeekBar(getContext()); slider.setMax((maximum-500)/50);
        int selected = Math.max(500, Math.min(maximum, preferences.getInt(key, maximum)));
        value.setText("红线（推荐换挡）  " + selected);
        slider.setProgress((selected-500)/50);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                int rpm = 500+progress*50; value.setText("红线（推荐换挡）  "+rpm);
                if (user) preferences.edit().putInt(key, rpm).apply();
            }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { }
        });
        content.addView(slider, new LinearLayout.LayoutParams(-1, dp(35)));
        content.addView(button("恢复默认", () -> { preferences.edit().remove(key).apply(); show(2); }),
                new LinearLayout.LayoutParams(-1, dp(40)));
    }
    @Override public void onBackPressed() {
        if (section == 0) dismiss();
        else if (section == 6) show(5);
        else if (section == 1 && editingLap >= 0) { editingLap = -1; show(4); }
        else { editingLap = -1; show(0); }
    }
    private int dp(float value) { return Math.round(value*getContext().getResources().getDisplayMetrics().density); }
    private void connection() {
        EditText input = new EditText(getContext()); input.setSingleLine(); input.setText(preferences.getString("host", ""));
        new AlertDialog.Builder(getContext()).setTitle("电脑 IP").setView(input).setNegativeButton("取消", null)
                .setPositiveButton("连接", (dialog, which) -> { preferences.edit().putString("host", input.getText().toString().trim()).apply(); show(3); }).show();
    }
}
