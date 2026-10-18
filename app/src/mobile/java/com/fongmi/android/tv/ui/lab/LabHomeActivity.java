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

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, LabHomeActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        root.setBackground(LabSkin.pageBackground(this));

        // 顶栏：标题＋副标题＋右上「＋」装包
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(18), dp(16), dp(18), dp(4));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView h1 = new TextView(this);
        h1.setText("实验室");
        h1.setTextSize(24);
        h1.setTextColor(LabSkin.TEXT_MAIN);
        h1.getPaint().setFakeBoldText(true);
        titles.addView(h1);
        TextView add = new TextView(this);
        add.setText("＋");
        add.setTextSize(26);
        add.setTextColor(LabSkin.YELLOW);
        add.setPadding(dp(12), dp(12), dp(12), dp(12));
        add.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setItems(new String[]{"选文件", "粘贴文本", "链接"}, (d, which) -> {
                    if (which == 0) LabInstallActivity.pickFile(this);
                    else if (which == 1) LabPasteImportActivity.start(this);
                    else LabPasteImportActivity.startLink(this);
                }).show());
        top.addView(titles);
        top.addView(add);
        root.addView(top);

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
        root.addView(strip);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, dp(6), 0, dp(24));
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
