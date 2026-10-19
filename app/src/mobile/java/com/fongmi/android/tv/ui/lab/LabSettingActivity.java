package com.fongmi.android.tv.ui.lab;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.service.ManageService;
import com.fongmi.android.tv.utils.Notify;

/**
 * 实验室设置页：点实验室首页标题「实验室」进入。
 * 只两项：显示到 tab 栏开关、忽略电池优化。
 * 顶栏只留标题，走系统返回。
 */
public class LabSettingActivity extends AppCompatActivity {

    private static final String PREFS = "xingchen";
    private static final String KEY_TAB = "xingchen.lab_tab_enabled";

    private LinearLayout body;
    private TextView batteryState;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, LabSettingActivity.class));
    }

    public static boolean isTabEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TAB, false);
    }

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LabSkin.edgeToEdge(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private int dp(int v) {
        return LabSkin.dp(this, v);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 标题行：只留标题
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(18), dp(9) + LabSkin.statusBarHeight(this), dp(18), dp(20));
        TextView h1 = new TextView(this);
        h1.setText("实验室设置");
        h1.setTextSize(24);
        h1.setTextColor(LabSkin.WALL_TITLE);
        h1.getPaint().setFakeBoldText(true);
        LabSkin.halo(h1);
        h1.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        nav.addView(h1);
        root.addView(nav);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(46) + LabSkin.navBarHeight(this));
        scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void refresh() {
        body.removeAllViews();
        body.addView(tabCard());
        body.addView(batteryCard());
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private View tabCard() {
        LinearLayout card = cardBox();
        TextView label = rowLabel("显示到 tab 栏");
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(label);
        card.addView(LabSkin.miniSwitch(this, isTabEnabled(this), on -> {
            prefs().edit().putBoolean(KEY_TAB, on).apply();
            Notify.show(on ? "实验室已放到 tab 栏" : "实验室已从 tab 栏移除");
        }));
        return card;
    }

    private View batteryCard() {
        LinearLayout card = cardBox();
        TextView label = rowLabel("忽略电池优化");
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(label);
        batteryState = new TextView(this);
        batteryState.setTextSize(13);
        batteryState.getPaint().setFakeBoldText(true);
        card.addView(batteryState);
        refreshBatteryState();
        card.setOnClickListener(v -> {
            if (ManageService.isIgnoringBatteryOptimizations(this)) {
                Notify.show("已经忽略了");
                return;
            }
            ManageService.requestIgnoreBatteryOptimizations(this);
        });
        return card;
    }

    private void refreshBatteryState() {
        if (batteryState == null) return;
        boolean ignored = ManageService.isIgnoringBatteryOptimizations(this);
        batteryState.setText(ignored ? "已忽略" : "未忽略");
        batteryState.setTextColor(ignored ? LabSkin.GREEN_ON_DARK : LabSkin.COUNT_YELLOW);
    }

    private LinearLayout cardBox() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(LabSkin.glassDark(this));
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(18), 0, dp(18), dp(12));
        card.setLayoutParams(lp);
        return card;
    }

    private TextView rowLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16);
        t.setTextColor(LabSkin.TEXT_ON_DARK);
        return t;
    }
}
