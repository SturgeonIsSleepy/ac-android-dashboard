package cn.acflip.dash;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

public class SettingsActivity extends Activity {
    private SharedPreferences preferences;
    private LinearLayout root, content;
    private int section, maximum;
    private String car;
    private int editingLap = -1;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        preferences = getSharedPreferences("MainActivity", 0);
        maximum = getIntent().getIntExtra("maxRpm", 0); car = getIntent().getStringExtra("car");
        if (car == null) car = "";
        show(0);
    }
    private TextView label(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(Color.WHITE);
        return view;
    }
    private Button button(String value, Runnable action) {
        Button view = new Button(this); view.setText(value); view.setTextSize(14); view.setTextColor(Color.WHITE);
        view.setAllCaps(false); view.setSingleLine(true); view.setIncludeFontPadding(false); view.setGravity(Gravity.CENTER);
        view.setMinHeight(0); view.setMinimumHeight(0); view.setPadding(dp(8),dp(4),dp(8),dp(4));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.rgb(49,49,49)); background.setCornerRadius(dp(5));
        view.setBackgroundTintList(null);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.rgb(83,83,83)),background,null));
        view.setOnClickListener(v -> action.run()); return view;
    }
    private void show(int page) {
        section = page;
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(5), dp(12), dp(4)); root.setBackgroundColor(Color.BLACK);
        String[] titles = { "设置", "显示样式", "转速", "连接", "阶段样式" };
        LinearLayout header = new LinearLayout(this);
        header.addView(label(titles[page],19),new LinearLayout.LayoutParams(0,dp(29),1));
        if (page == 4) header.addView(button("启用阶段切换", () -> { preferences.edit().putInt("layout",10).apply(); finish(); }),new LinearLayout.LayoutParams(dp(104),dp(29)));
        root.addView(header);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        if (page == 0) {
            String[] names = { "全部样式", "阶段样式", "换挡转速", "电脑连接" };
            int[] destinations = {1,4,2,3};
            for (int i = 0; i < names.length; i++) {
                final int choice = destinations[i];
                content.addView(button(names[i], () -> { editingLap = -1; show(choice); }), new LinearLayout.LayoutParams(-1, 0, 1));
            }
        } else if (page == 1) gallery();
        else if (page == 2) rpm();
        else if (page == 4) phaseStyles();
        else {
            content.addView(label(preferences.getString("host", "未设置电脑 IP"), 19));
            content.addView(button("电脑 IP", () -> connection()), new LinearLayout.LayoutParams(-1, dp(40)));
        }
        root.addView(button(page == 0 ? "返回仪表" : "返回设置", () -> { if (section == 0) finish(); else if (editingLap >= 0 && section == 1) { editingLap = -1; show(4); } else show(0); }),
                new LinearLayout.LayoutParams(-1, dp(40)));
        setContentView(root);
    }
    private void gallery() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        int[] plan = RacePhase.styles(preferences.getString("lapStyles","9"));
        int selected = editingLap >= 0 ? plan[editingLap] : preferences.getInt("layout", 0);
        int count = MainActivity.LAYOUT_NAMES.length-(editingLap >= 0 ? 1 : 0);
        for (int row = 0; row < (count+1)/2; row++) {
            LinearLayout line = new LinearLayout(this);
            for (int column = 0; column < 2; column++) {
                final int choice = row*2+column;
                if (choice >= count) {
                    line.addView(new TextView(this), new LinearLayout.LayoutParams(0, -1, 1)); continue;
                }
                LinearLayout tile = new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL);
                tile.setPadding(dp(3),dp(3),dp(3),dp(3));
                GradientDrawable border = new GradientDrawable(); border.setColor(Color.rgb(16,16,16));
                border.setCornerRadius(dp(5)); border.setStroke(dp(2), choice == selected ? Color.rgb(19,210,108) : Color.rgb(49,49,49));
                tile.setBackground(border);
                ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                if (MainActivity.stylePreviews != null) image.setImageBitmap(MainActivity.stylePreviews[choice]);
                tile.addView(image, new LinearLayout.LayoutParams(-1, dp(83)));
                TextView name = label(MainActivity.LAYOUT_NAMES[choice], 13); name.setGravity(android.view.Gravity.CENTER);
                if (choice == selected) name.setTextColor(Color.rgb(19,210,108));
                tile.addView(name, new LinearLayout.LayoutParams(-1, dp(24)));
                tile.setContentDescription(MainActivity.LAYOUT_NAMES[choice]+"，点击选择"); tile.setSelected(choice == selected);
                tile.setOnClickListener(v -> {
                    if (editingLap < 0) { preferences.edit().putInt("layout", choice).apply(); finish(); }
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
        ScrollView scroll = new ScrollView(this); LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        list.addView(label("维修区 → 出场圈 → 飞驰圈",13));
        for (int i = 0; i < plan.length; i++) {
            final int index = i;
            list.addView(button("第"+(i+1)+"飞驰圈："+MainActivity.LAYOUT_NAMES[plan[i]], () -> { editingLap = index; show(1); }),new LinearLayout.LayoutParams(-1,dp(40)));
        }
        LinearLayout actions = new LinearLayout(this);
        actions.addView(button("增加一圈", () -> { int[] next = java.util.Arrays.copyOf(plan,plan.length+1); next[plan.length] = plan[plan.length-1]; savePlan(next); show(4); }),new LinearLayout.LayoutParams(0,dp(40),1));
        if (plan.length > 1) actions.addView(button("删最后一圈", () -> { savePlan(java.util.Arrays.copyOf(plan,plan.length-1)); show(4); }),new LinearLayout.LayoutParams(0,dp(40),1));
        list.addView(actions); list.addView(label("最后一套沿用，进站后重新开始",12));
        scroll.addView(list); content.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
    }
    private void rpm() {
        content.addView(label("引擎最大转速  " + (maximum > 0 ? maximum : "—"), 17));
        TextView value = label("", 17); content.addView(value);
        if (maximum < 500 || car.isEmpty()) { value.setText("连接车辆后调整"); return; }
        String key = "shift:"+car;
        SeekBar slider = new SeekBar(this); slider.setMax((maximum-500)/50);
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
        if (section == 0) finish();
        else if (section == 1 && editingLap >= 0) { editingLap = -1; show(4); }
        else { editingLap = -1; show(0); }
    }
    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private void connection() {
        EditText input = new EditText(this); input.setSingleLine(); input.setText(preferences.getString("host", ""));
        new AlertDialog.Builder(this).setTitle("电脑 IP").setView(input).setNegativeButton("取消", null)
                .setPositiveButton("连接", (dialog, which) -> { preferences.edit().putString("host", input.getText().toString().trim()).apply(); show(3); }).show();
    }
}
