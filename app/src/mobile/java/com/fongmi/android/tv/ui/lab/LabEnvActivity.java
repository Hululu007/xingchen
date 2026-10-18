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
 * 环境页：该环境下的服务卡列表。
 * 服务卡＝名字＋人话简介＋状态＋唯一主按钮＋自动启动小开关＋长按卸载；
 * 不带头像位。环境没装时先露「一键安装」。
 * 不设叉，走系统返回。
 */
public class LabEnvActivity extends AppCompatActivity {

    private static final String EXTRA_ENV = "env";
    private static final int REQ_RT = 0x2101;

    private String env;
    private LinearLayout listBox;
    private TextView progressText;

    public static void start(Activity activity, String env) {
        Intent i = new Intent(activity, LabEnvActivity.class);
        i.putExtra(EXTRA_ENV, env);
        activity.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        env = getIntent().getStringExtra(EXTRA_ENV);
        if (env == null) env = "tool";
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

        // 导航：‹ ＋ 环境名
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(10), dp(14), dp(18), dp(6));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(30);
        back.setTextColor(0xFFC9C9D2);
        back.setPadding(dp(8), dp(8), dp(14), dp(8));
        back.setOnClickListener(v -> onBackPressed());
        TextView title = new TextView(this);
        title.setText(LabRegistry.envLabel(env));
        title.setTextSize(20);
        title.setTextColor(LabSkin.TEXT_MAIN);
        title.getPaint().setFakeBoldText(true);
        nav.addView(back);
        nav.addView(title);
        root.addView(nav);

        TextView sub = new TextView(this);
        sub.setText(LabRegistry.envDesc(env));
        sub.setTextSize(14);
        sub.setTextColor(LabSkin.TEXT_SUB);
        sub.setPadding(dp(18), 0, dp(18), dp(8));
        root.addView(sub);

        progressText = new TextView(this);
        progressText.setTextSize(14);
        progressText.setTextColor(LabSkin.YELLOW);
        progressText.setPadding(dp(18), dp(4), dp(18), dp(4));
        progressText.setVisibility(View.GONE);
        root.addView(progressText);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, dp(4), 0, dp(24));
        scroll.addView(listBox, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void reload() {
        listBox.removeAllViews();
        if (!"tool".equals(env) && !LabRuntimeManager.isInstalled(this, env)) {
            listBox.addView(installCard());
        }
        List<LabRegistry.Svc> svcs = LabRegistry.get(this).services(env);
        for (LabRegistry.Svc s : svcs) {
            listBox.addView(serviceCard(s));
        }
        if (svcs.isEmpty() && ("tool".equals(env) || LabRuntimeManager.isInstalled(this, env))) {
            TextView empty = new TextView(this);
            empty.setText("这里还没有服务，点实验室右上角「＋」装一个");
            empty.setTextSize(14);
            empty.setTextColor(LabSkin.TEXT_DIM);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(18), dp(30), dp(18), dp(30));
            listBox.addView(empty);
        }
        TextView foot = new TextView(this);
        foot.setText("长按卡片可卸载");
        foot.setTextSize(13);
        foot.setTextColor(LabSkin.TEXT_DIM);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(10), 0, 0);
        listBox.addView(foot);
    }

    /** 环境没装：露一键安装（从本地 zip 导入，进度条呈现「正在准备环境」） */
    private View installCard() {
        LinearLayout card = cardBox(false);
        TextView t = new TextView(this);
        t.setText(LabRegistry.envLabel(env) + "还没装");
        t.setTextSize(17);
        t.setTextColor(LabSkin.TEXT_MAIN);
        t.getPaint().setFakeBoldText(true);
        card.addView(t);
        TextView d = new TextView(this);
        d.setText("从你手机里的压缩包导入，装一次以后都能用");
        d.setTextSize(14);
        d.setTextColor(LabSkin.TEXT_SUB);
        d.setPadding(0, dp(6), 0, dp(12));
        card.addView(d);
        TextView btn = button("一键安装", true);
        btn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_RT);
        });
        card.addView(btn);
        return card;
    }

    /** 服务卡：名字＋人话简介＋状态＋唯一主按钮＋自动启动小开关＋长按卸载 */
    private View serviceCard(LabRegistry.Svc s) {
        boolean running = LabRunner.isRunning(this, s.id);
        String err = LabRunner.lastError(s.id);
        LinearLayout card = cardBox(running);
        card.setOnClickListener(v -> LabServiceActivity.start(this, s.id));
        card.setOnLongClickListener(v -> {
            confirmUninstall(s);
            return true;
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(s.name);
        name.setTextSize(17);
        name.setTextColor(LabSkin.TEXT_MAIN);
        name.getPaint().setFakeBoldText(true);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(name);
        TextView tag = new TextView(this);
        if (running) {
            tag.setText("● 运行中");
            tag.setTextColor(LabSkin.GREEN);
            tag.setBackground(LabSkin.tagGreen(this));
        } else if (err != null) {
            tag.setText("出错了");
            tag.setTextColor(0xFFFF6B6B);
            tag.setBackground(LabSkin.tagGray(this));
        } else {
            tag.setText("已安装");
            tag.setTextColor(LabSkin.TEXT_DIM);
            tag.setBackground(LabSkin.tagGray(this));
        }
        tag.setTextSize(12);
        tag.setPadding(dp(12), dp(5), dp(12), dp(5));
        row.addView(tag);
        card.addView(row);

        TextView desc = new TextView(this);
        desc.setText(s.desc == null ? "" : s.desc);
        desc.setTextSize(14);
        desc.setTextColor(LabSkin.TEXT_SUB);
        desc.setPadding(0, dp(6), 0, dp(12));
        card.addView(desc);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView main;
        if (running) {
            main = button("停止", false);
            main.setOnClickListener(v -> {
                LabRunner.stop(this, s.id);
                reload();
            });
        } else {
            main = button(err != null ? "重试" : "启动", true);
            main.setOnClickListener(v -> {
                main.setText("启动中…");
                main.setEnabled(false);
                LabRunner.start(this, s.id, new LabRunner.Listener() {
                    @Override
                    public void onOutput(String text) {
                    }

                    @Override
                    public void onExit(int code) {
                        runOnUiThread(() -> {
                            if (code != 0 && !LabRunner.isRunning(LabEnvActivity.this, s.id)) {
                                Notify.show("没起来，去运行记录看看");
                            }
                            reload();
                        });
                    }
                });
            });
        }
        actions.addView(main);

        // 自动启动小开关
        LinearLayout sw = new LinearLayout(this);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        sw.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        slp.leftMargin = dp(12);
        sw.setLayoutParams(slp);
        TextView swLab = new TextView(this);
        swLab.setText("自动启动");
        swLab.setTextSize(13);
        swLab.setTextColor(LabSkin.TEXT_DIM);
        Switch swv = new Switch(this);
        swv.setChecked(LabRegistry.autoStartOf(s));
        swv.setOnCheckedChangeListener((b, on) -> {
            LabRegistry.Svc cur = LabRegistry.get(this).svc(s.id);
            if (cur != null) {
                cur.autoStart = on;
                LabRegistry.get(this).upsertSvc(this, cur);
            }
        });
        sw.addView(swLab);
        sw.addView(swv);
        actions.addView(sw);
        card.addView(actions);
        return card;
    }

    private void confirmUninstall(LabRegistry.Svc s) {
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("卸载「" + s.name + "」？")
                .setMessage("服务会停掉，包文件删干净。")
                .setPositiveButton("卸载", (d, w) -> {
                    LabPackageInstaller.uninstall(this, s.id);
                    reload();
                })
                .setNegativeButton("算了", null)
                .create();
        dlg.show();
    }

    private LinearLayout cardBox(boolean running) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(LabSkin.cardBackground(this, running));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(18), 0, dp(18), dp(14));
        card.setLayoutParams(lp);
        card.setClickable(true);
        card.setFocusable(true);
        return card;
    }

    private TextView button(String text, boolean yellow) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(15);
        b.getPaint().setFakeBoldText(true);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(yellow ? 0xFF171310 : LabSkin.TEXT_MAIN);
        b.setBackground(yellow ? LabSkin.buttonYellow(this) : LabSkin.buttonGray(this));
        b.setPadding(dp(28), dp(12), dp(28), dp(12));
        b.setMinWidth(dp(96));
        return b;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_RT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            progressText.setVisibility(View.VISIBLE);
            progressText.setText("正在准备环境…");
            LabRuntimeManager.importFromUri(this, env, uri, new LabRuntimeManager.Listener() {
                @Override
                public void onProgress(String text) {
                    runOnUiThread(() -> progressText.setText(text));
                }

                @Override
                public void onOk() {
                    runOnUiThread(() -> {
                        progressText.setVisibility(View.GONE);
                        Notify.show("环境装好了");
                        reload();
                    });
                }

                @Override
                public void onFail(String reason) {
                    runOnUiThread(() -> {
                        progressText.setVisibility(View.GONE);
                        Notify.show(reason);
                    });
                }
            });
        }
    }
}
