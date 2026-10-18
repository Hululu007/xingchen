package com.fongmi.android.tv.ui.lab;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.utils.Notify;

import java.io.File;
import java.util.List;

/**
 * 实验室设置页（定稿 lab-settings.png）：
 * 卡一：自启总开关＋全部停止 ›；
 * 卡二：环境行（已安装·大小→卸载／未安装·约大小→安装）；
 * 卡三：包来源 ›（公开包仓库）；
 * 卡四：全局代理（开关＋端口＋直连名单，生效于 lab 进程环境）；
 * 卡五：保活（电池优化白名单）。
 * 顶栏只留标题，走系统返回。
 */
public class LabSettingsActivity extends AppCompatActivity {

    private static final String REPO_URL = "https://github.com/XingChenApp/xingchen-lab-assets";

    private LinearLayout body;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, LabSettingsActivity.class));
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
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setBackground(LabScrim.bar(this));
        nav.setPadding(dp(18), dp(14) + LabSkin.statusBarHeight(this), dp(18), dp(6));
        TextView title = new TextView(this);
        title.setText("实验室设置");
        title.setTextSize(20);
        title.setTextColor(LabSkin.TEXT_MAIN);
        title.getPaint().setFakeBoldText(true);
        nav.addView(title);
        root.addView(nav);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(4), 0, dp(24) + LabSkin.navBarHeight(this));
        scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void reload() {
        body.removeAllViews();
        body.addView(autoCard());
        body.addView(envCard());
        body.addView(sourceCard());
        body.addView(proxyCard());
        body.addView(keepAliveCard());
        TextView ver = new TextView(this);
        ver.setText("星辰 " + appVersion());
        ver.setTextSize(12);
        ver.setTextColor(LabSkin.TEXT_DIM);
        ver.setGravity(Gravity.CENTER);
        ver.setBackground(LabScrim.bar(this));
        ver.setPadding(0, dp(6), 0, dp(6));
        body.addView(ver);
    }

    // ---------------- 卡一：自启总开关＋全部停止 ----------------

    private View autoCard() {
        LinearLayout card = cardBox();
        LinearLayout row = rowBox();
        TextView lab = rowLabel("自启总开关");
        row.addView(lab);
        Switch sw = new Switch(this);
        sw.setChecked(LabRegistry.get(this).masterAutoStart);
        sw.setOnCheckedChangeListener((b, on) ->
                LabRegistry.get(this).setMasterAutoStart(this, on));
        row.addView(sw);
        card.addView(row);
        card.addView(divider());
        TextView stopAll = new TextView(this);
        stopAll.setText("全部停止 ›");
        stopAll.setTextSize(16);
        stopAll.setTextColor(LabSkin.TEXT_MAIN);
        stopAll.getPaint().setFakeBoldText(true);
        stopAll.setPadding(0, dp(14), 0, dp(4));
        stopAll.setOnClickListener(v -> {
            LabRunner.stopAll(this);
            Notify.show("都停了");
            reload();
        });
        card.addView(stopAll);
        return card;
    }

    // ---------------- 卡二：环境行 ----------------

    private View envCard() {
        LinearLayout card = cardBox();
        List<String> envs = LabRegistry.ENVS;
        for (int i = 0; i < envs.size(); i++) {
            String env = envs.get(i);
            card.addView(envRow(env));
            if (i < envs.size() - 1) card.addView(divider());
        }
        return card;
    }

    /** 环境行：名字＋已安装·大小（绿）／未安装·约大小（灰），右键 卸载／安装 */
    private View envRow(String env) {
        boolean installed = LabRuntimeManager.isInstalled(this, env);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView name = new TextView(this);
        name.setText(LabRegistry.envLabel(env));
        name.setTextSize(17);
        name.setTextColor(LabSkin.TEXT_MAIN);
        name.getPaint().setFakeBoldText(true);
        left.addView(name);
        TextView sub = new TextView(this);
        boolean needUpdate = installed && LabRuntimeManager.hasUpdate(this, env);
        if (installed) {
            String base = "已安装 · " + fmtSize(dirSize(LabPaths.runtimeDir(this, env)));
            if (needUpdate) {
                sub.setText(base + " · 有新版");
                sub.setTextColor(LabSkin.AMBER);
            } else {
                sub.setText(base);
                sub.setTextColor(LabSkin.GREEN);
            }
        } else {
            sub.setText("未安装 · 约 " + fmtSize(catalogSize(env)));
            sub.setTextColor(LabSkin.TEXT_DIM);
        }
        sub.setTextSize(14);
        sub.setPadding(0, dp(4), 0, 0);
        left.addView(sub);
        row.addView(left);

        TextView btn = new TextView(this);
        if (installed) {
            btn.setText("卸载");
            btn.setTextColor(LabSkin.TEXT_MAIN);
            btn.setBackground(LabSkin.buttonGray(this));
            btn.setOnClickListener(v -> confirmUninstallEnv(env));
        } else {
            btn.setText("安装");
            btn.setTextColor(LabSkin.ON_YELLOW);
            btn.setBackground(LabSkin.buttonYellow(this));
            btn.setOnClickListener(v -> LabEnvActivity.start(this, env));
        }
        btn.setTextSize(15);
        btn.getPaint().setFakeBoldText(true);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(28), dp(12), dp(28), dp(12));
        btn.setMinWidth(dp(96));
        row.addView(btn);
        if (needUpdate) {
            row.setOnClickListener(v -> confirmUpdateEnv(env));
            row.setClickable(true);
            row.setFocusable(true);
        }
        return row;
    }

    /** 有新版：点行确认后走在线安装覆盖（更新检测的对） */
    private void confirmUpdateEnv(String env) {
        String ver = LabRuntimeManager.catalogVersion(this, env);
        new android.app.AlertDialog.Builder(this)
                .setTitle("「" + LabRegistry.envLabel(env) + "」有新版" + (TextUtils.isEmpty(ver) ? "" : " " + ver))
                .setMessage("下新版覆盖安装，服务会先停掉。")
                .setPositiveButton("更新", (d, w) -> updateEnv(env))
                .setNegativeButton("算了", null)
                .show();
    }

    private void updateEnv(String env) {
        final android.app.AlertDialog[] holder = new android.app.AlertDialog[1];
        TextView msg = new TextView(this);
        msg.setTextSize(14);
        msg.setTextColor(LabSkin.TEXT_MAIN);
        msg.setPadding(dp(20), dp(16), dp(20), dp(16));
        msg.setText("正在准备…");
        holder[0] = new android.app.AlertDialog.Builder(this)
                .setTitle("正在更新")
                .setView(msg)
                .setCancelable(false)
                .show();
        LabRuntimeManager.installOnline(this, env, new LabRuntimeManager.Listener() {
            @Override
            public void onProgress(String text) {
                runOnUiThread(() -> msg.setText(text));
            }

            @Override
            public void onOk() {
                runOnUiThread(() -> {
                    holder[0].dismiss();
                    Notify.show("更新好了");
                    reload();
                });
            }

            @Override
            public void onFail(String reason) {
                runOnUiThread(() -> {
                    holder[0].dismiss();
                    Notify.show(reason);
                    reload();
                });
            }
        });
    }

    private void confirmUninstallEnv(String env) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("卸载「" + LabRegistry.envLabel(env) + "」？")
                .setMessage("依赖它的服务会停掉，环境文件删干净。")
                .setPositiveButton("卸载", (d, w) -> {
                    LabRuntimeManager.uninstall(this, env);
                    Notify.show("卸掉了");
                    reload();
                })
                .setNegativeButton("算了", null)
                .show();
    }

    private long catalogSize(String env) {
        try {
            LabEnvCatalog.EnvEntry e = LabEnvCatalog.entry(this, env);
            if (e == null) return 0;
            LabEnvCatalog.Download dl = LabEnvCatalog.chooseDownload(e);
            return dl == null ? 0 : dl.size;
        } catch (Exception ex) {
            return 0;
        }
    }

    private long dirSize(File dir) {
        long total = 0;
        if (dir == null || !dir.exists()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            total += f.isDirectory() ? dirSize(f) : f.length();
        }
        return total;
    }

    private String fmtSize(long bytes) {
        if (bytes <= 0) return "—";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        long mb = bytes / (1024 * 1024);
        long rem = (bytes % (1024 * 1024)) * 10 / (1024 * 1024);
        return mb + (rem > 0 ? "." + rem : "") + " MB";
    }

    // ---------------- 卡三：包来源 ----------------

    private View sourceCard() {
        LinearLayout card = cardBox();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView name = new TextView(this);
        name.setText("包来源");
        name.setTextSize(17);
        name.setTextColor(LabSkin.TEXT_MAIN);
        name.getPaint().setFakeBoldText(true);
        left.addView(name);
        TextView sub = new TextView(this);
        sub.setText("公开包仓库");
        sub.setTextSize(13);
        sub.setTextColor(LabSkin.TEXT_DIM);
        sub.setPadding(0, dp(4), 0, 0);
        left.addView(sub);
        row.addView(left);

        TextView chev = new TextView(this);
        chev.setText("›");
        chev.setTextSize(22);
        chev.setTextColor(LabSkin.TEXT_DIM);
        row.addView(chev);
        card.addView(row);
        card.setOnClickListener(v -> showSource());
        card.setClickable(true);
        card.setFocusable(true);
        return card;
    }

    private void showSource() {
        TextView tv = new TextView(this);
        tv.setText(REPO_URL);
        tv.setTextSize(14);
        tv.setTextColor(LabSkin.TEXT_MAIN);
        tv.setPadding(dp(20), dp(16), dp(20), dp(8));
        tv.setTextIsSelectable(true);
        new android.app.AlertDialog.Builder(this)
                .setTitle("公开包仓库")
                .setView(tv)
                .setPositiveButton("复制地址", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("lab-repo", REPO_URL));
                    Notify.show("地址已复制");
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    // ---------------- 卡四：全局代理 ----------------

    private View proxyCard() {
        LinearLayout card = cardBox();
        card.addView(sectionTitle("全局代理"));
        LinearLayout swRow = rowBox();
        swRow.addView(rowLabel("代理开关"));
        Switch sw = new Switch(this);
        sw.setChecked(LabSettings.proxyOn(this));
        sw.setOnCheckedChangeListener((b, on) -> LabSettings.setProxyOn(this, on));
        swRow.addView(sw);
        card.addView(swRow);
        card.addView(kvRow("端口", LabSettings.proxyPort(this), InputType.TYPE_CLASS_NUMBER,
                v -> LabSettings.setProxyPort(this, v)));
        card.addView(kvRow("直连名单", LabSettings.proxyNoProxy(this), InputType.TYPE_CLASS_TEXT,
                v -> LabSettings.setProxyNoProxy(this, v)));
        return card;
    }

    // ---------------- 卡五：保活 ----------------

    private View keepAliveCard() {
        LinearLayout card = cardBox();
        card.addView(sectionTitle("保活"));
        TextView batt = new TextView(this);
        boolean ignored = isIgnoringBattery();
        batt.setText(ignored ? "电池优化：已忽略" : "电池优化：去设置忽略");
        batt.setTextSize(15);
        batt.setTextColor(ignored ? LabSkin.GREEN : LabSkin.TEXT_MAIN);
        batt.setPadding(0, dp(8), 0, dp(8));
        if (!ignored) batt.setOnClickListener(v -> openBatterySettings());
        card.addView(batt);
        return card;
    }

    // ---------------- 小件 ----------------

    private LinearLayout cardBox() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(LabSkin.cardBackground(this, false));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(18), 0, dp(18), dp(14));
        card.setLayoutParams(lp);
        return card;
    }

    private TextView sectionTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16);
        t.setTextColor(LabSkin.TEXT_MAIN);
        t.getPaint().setFakeBoldText(true);
        return t;
    }

    private LinearLayout rowBox() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(8), 0, dp(8));
        return r;
    }

    private TextView rowLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setTextColor(LabSkin.TEXT_MAIN);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0x0F000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        v.setLayoutParams(lp);
        return v;
    }

    private interface Saver {
        void save(String v);
    }

    private LinearLayout kvRow(String name, String value, int inputType, Saver saver) {
        LinearLayout r = rowBox();
        r.addView(rowLabel(name));
        EditText et = new EditText(this);
        et.setText(value == null ? "" : value);
        et.setTextSize(15);
        et.setTextColor(LabSkin.TEXT_MAIN);
        et.setHintTextColor(LabSkin.TEXT_DIM);
        et.setInputType(inputType);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        et.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                String s = et.getText() == null ? "" : et.getText().toString().trim();
                if (!TextUtils.isEmpty(s)) saver.save(s);
            }
        });
        r.addView(et);
        return r;
    }

    private boolean isIgnoringBattery() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private void openBatterySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ex) {
                Notify.show("打不开系统设置");
            }
        }
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
