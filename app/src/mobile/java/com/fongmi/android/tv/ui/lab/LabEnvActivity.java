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
 * 不带头像位。环境没装时先露安装入口。
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

        // 顶栏：只留环境名，走系统返回（霜条打底）
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setBackground(LabScrim.bar(this));
        nav.setPadding(dp(18), dp(14) + LabSkin.statusBarHeight(this), dp(18), dp(6));
        TextView title = new TextView(this);
        title.setText(LabRegistry.envLabel(env));
        title.setTextSize(20);
        title.setTextColor(LabSkin.TEXT_MAIN);
        title.getPaint().setFakeBoldText(true);
        nav.addView(title);
        root.addView(nav);

        progressText = new TextView(this);
        progressText.setTextSize(14);
        progressText.setTextColor(LabSkin.AMBER);
        progressText.setBackground(LabScrim.bar(this));
        progressText.setPadding(dp(18), dp(4), dp(18), dp(4));
        progressText.setVisibility(View.GONE);
        root.addView(progressText);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, dp(4), 0, dp(24) + LabSkin.navBarHeight(this));
        scroll.addView(listBox, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void reload() {
        listBox.removeAllViews();
        boolean installed = !"tool".equals(env) && LabRuntimeManager.isInstalled(this, env);
        if (!"tool".equals(env) && !installed) {
            listBox.addView(installCard());
        }
        List<LabRegistry.Svc> svcs = LabRegistry.get(this).services(env);
        for (LabRegistry.Svc s : svcs) {
            listBox.addView(serviceCard(s));
        }
        if (installed) {
            listBox.addView(uninstallCard());
        }
    }

    /** 环境没装：安装入口，在线/本地二选一（选择卡样式照首页「＋」） */
    private View installCard() {
        LinearLayout card = cardBox(false);
        TextView t = new TextView(this);
        t.setText(LabRegistry.envLabel(env) + "还没装");
        t.setTextSize(17);
        t.setTextColor(LabSkin.TEXT_MAIN);
        t.getPaint().setFakeBoldText(true);
        t.setPadding(0, 0, 0, dp(12));
        card.addView(t);
        TextView btn = button("安装", true);
        btn.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setItems(new String[]{"在线安装", "本地安装"}, (d, which) -> {
                    if (which == 0) installOnline();
                    else pickLocalZip();
                }).show());
        card.addView(btn);
        return card;
    }

    /** 已装环境：补「卸载」，落点在服务卡列表最下方 */
    private View uninstallCard() {
        LinearLayout card = cardBox(false);
        TextView btn = button("卸载", false);
        btn.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("卸载「" + LabRegistry.envLabel(env) + "」？")
                .setMessage("依赖它的服务会停掉，环境文件删干净。")
                .setPositiveButton("卸载", (d, w) -> {
                    LabRuntimeManager.uninstall(this, env);
                    Notify.show("卸掉了");
                    reload();
                })
                .setNegativeButton("算了", null)
                .create().show());
        card.addView(btn);
        return card;
    }

    /** 本地安装：选本地 zip 文件，走环境安装管线 */
    private void pickLocalZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_RT);
    }

    /** 在线安装：统一入口（目录制/回填表/分片/镜像都在 LabRuntimeManager 里） */
    private void installOnline() {
        progressText.setVisibility(View.VISIBLE);
        progressText.setText("正在准备…");
        LabRuntimeManager.installOnline(this, env, installListener());
    }

    private LabRuntimeManager.Listener installListener() {
        return new LabRuntimeManager.Listener() {
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
        };
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
            tag.setTextColor(LabSkin.RED);
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
            LabRuntimeManager.importFromUri(this, env, uri, installListener());
        }
    }
}
