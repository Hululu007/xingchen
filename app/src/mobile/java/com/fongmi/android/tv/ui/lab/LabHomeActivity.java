package com.fongmi.android.tv.ui.lab;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.utils.Notify;

import java.util.List;

/**
 * 实验室首页：只列环境（新口径）。
 * 十一个环境各一张卡平铺（Python/Node/PHP/mihomo/alist/frp/aria2/Ubuntu/Java/Bun/ffmpeg）；
 * 环境卡＝名字＋已装/未装＋一行小字「里面 N 个服务、M 个运行中」，
 * 点卡进环境页；卡片不带头像位。
 * 顶栏「＋」进包安装；设置条＝自动启动总开关＋全部停止。不设叉，走系统返回。
 */
public class LabHomeActivity extends AppCompatActivity {

    private LinearLayout listBox;
    private Switch masterSwitch;
    private TextView runningStrip;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, LabHomeActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LabSkin.edgeToEdge(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private int dp(int v) {
        return LabSkin.dp(this, v);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 顶栏：标题＋设置＋「＋」（霜条打底，深浅壁纸都看得清）
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackground(LabScrim.bar(this));
        top.setPadding(dp(18), dp(16) + LabSkin.statusBarHeight(this), dp(18), dp(4));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView h1 = new TextView(this);
        h1.setText("实验室");
        h1.setTextSize(24);
        h1.setTextColor(LabSkin.TEXT_MAIN);
        h1.getPaint().setFakeBoldText(true);
        titles.addView(h1);
        top.addView(titles);
        TextView settings = new TextView(this);
        settings.setText("设置");
        settings.setTextSize(15);
        settings.setTextColor(LabSkin.TEXT_SUB);
        settings.setPadding(dp(12), dp(12), dp(12), dp(12));
        settings.setOnClickListener(v -> LabSettingsActivity.start(this));
        top.addView(settings);
        TextView add = new TextView(this);
        add.setText("＋");
        add.setTextSize(22);
        add.setTextColor(LabSkin.ON_YELLOW);
        add.setBackground(LabSkin.buttonYellow(this));
        add.setGravity(Gravity.CENTER);
        add.setPadding(dp(10), dp(6), dp(10), dp(6));
        add.setMinWidth(dp(44));
        add.setOnClickListener(v -> LabStoreActivity.start(this));
        top.addView(add);
        root.addView(top);

        // 设置区：自动启动总开关＋全部停止＋运行中入口（霜条打底）
        LinearLayout zone = new LinearLayout(this);
        zone.setOrientation(LinearLayout.VERTICAL);
        zone.setBackground(LabScrim.bar(this));
        zone.setPadding(0, dp(2), 0, dp(6));

        // 设置条：自动启动总开关＋全部停止
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(18), dp(8), dp(18), dp(8));
        TextView lab = new TextView(this);
        lab.setText("自动启动");
        lab.setTextSize(14);
        lab.setTextColor(LabSkin.TEXT_SUB);
        lab.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        masterSwitch = new Switch(this);
        masterSwitch.setOnCheckedChangeListener((b, on) -> LabRegistry.get(this).setMasterAutoStart(this, on));
        TextView stopAll = new TextView(this);
        stopAll.setText("全部停止");
        stopAll.setTextSize(14);
        stopAll.setTextColor(LabSkin.TEXT_MAIN);
        stopAll.setBackground(LabSkin.buttonGray(this));
        stopAll.setPadding(dp(16), dp(8), dp(16), dp(8));
        stopAll.setOnClickListener(v -> {
            LabRunner.stopAll(this);
            reload();
            Notify.show("都停了");
        });
        strip.addView(lab);
        strip.addView(masterSwitch);
        strip.addView(stopAll);
        zone.addView(strip);

        // 运行中入口：有谁在跑，点开看列表
        runningStrip = new TextView(this);
        runningStrip.setTextSize(14);
        runningStrip.setTextColor(LabSkin.TEXT_SUB);
        runningStrip.setPadding(dp(18), dp(8), dp(18), dp(8));
        runningStrip.setOnClickListener(v -> LabRunningDialog.show(this));
        zone.addView(runningStrip);
        root.addView(zone);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, dp(6), 0, dp(24) + LabSkin.navBarHeight(this));
        scroll.addView(listBox, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void reload() {
        listBox.removeAllViews();
        masterSwitch.setOnCheckedChangeListener(null);
        masterSwitch.setChecked(LabRegistry.get(this).masterAutoStart);
        masterSwitch.setOnCheckedChangeListener((b, on) -> LabRegistry.get(this).setMasterAutoStart(this, on));
        int n = LabRunner.runningCount(this);
        runningStrip.setText(n > 0 ? "运行中（" + n + "） ›" : "运行中 ›");
        runningStrip.setTextColor(n > 0 ? LabSkin.GREEN : LabSkin.TEXT_SUB);
        for (String env : LabRegistry.ENVS) {
            listBox.addView(envCard(env));
        }
    }

    /** 环境卡：名字＋已装/未装＋「里面 N 个服务、M 个运行中」 */
    private View envCard(String env) {
        boolean ready = LabRegistry.envReady(this, env);
        List<LabRegistry.Svc> svcs = LabRegistry.get(this).services(env);
        int running = 0;
        for (LabRegistry.Svc s : svcs) {
            if (LabRunner.isRunning(this, s.id)) running++;
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(LabSkin.cardBackground(this, running > 0));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(18), 0, dp(18), dp(14));
        card.setLayoutParams(lp);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> LabEnvActivity.start(this, env));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(LabRegistry.envLabel(env));
        name.setTextSize(18);
        name.setTextColor(LabSkin.TEXT_MAIN);
        name.getPaint().setFakeBoldText(true);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(name);
        TextView tag = new TextView(this);
        tag.setText(ready ? "已装" : "未装");
        tag.setTextSize(12);
        tag.setTextColor(ready ? LabSkin.GREEN : LabSkin.TEXT_DIM);
        tag.setBackground(ready ? LabSkin.tagGreen(this) : LabSkin.tagGray(this));
        tag.setPadding(dp(12), dp(5), dp(12), dp(5));
        row.addView(tag);
        card.addView(row);

        TextView line = new TextView(this);
        line.setText("里面 " + svcs.size() + " 个服务、" + running + " 个运行中");
        line.setTextSize(12);
        line.setTextColor(LabSkin.TEXT_DIM);
        line.setPadding(0, dp(8), 0, 0);
        card.addView(line);
        return card;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LabInstallActivity.REQ_PICK && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) LabInstallActivity.startWithUri(this, uri);
        }
    }
}
