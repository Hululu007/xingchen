package com.fongmi.android.tv.ui.lab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.utils.Notify;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务详情页（定稿 lab-service.png）：
 * 大卡＝名字＋状态徽＋服务地址（label＋大人话地址）；
 * 黄色主动作（按产出声明）＋灰色停止/启动；
 * 重启/分享/二维码一行；「查看运行记录 ›」；
 * 高级折叠：命令列表（每行 运行/停止 快捷钮，点行弹变量表运行，长按改/删自定义命令）、
 * 添加命令、包设置、终端、实时输出、导出包。
 * 顶栏只留标题，走系统返回。
 */
public class LabServiceActivity extends AppCompatActivity {

    private static final String EXTRA_ID = "id";

    private String pkgId;
    private LinearLayout body;
    private TextView navTitle;

    public static void start(Activity activity, String pkgId) {
        Intent i = new Intent(activity, LabServiceActivity.class);
        i.putExtra(EXTRA_ID, pkgId);
        activity.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pkgId = getIntent().getStringExtra(EXTRA_ID);
        LabSkin.edgeToEdge(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 变量表里的文件/目录选择回填
        LabVarDialog.onPickResult(requestCode, resultCode, data, this);
    }

    private int dp(int v) {
        return LabSkin.dp(this, v);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 顶栏：只留标题（霜条打底）
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setBackground(LabScrim.bar(this));
        nav.setPadding(dp(18), dp(14) + LabSkin.statusBarHeight(this), dp(18), dp(6));
        TextView title = new TextView(this);
        title.setTextSize(20);
        title.setTextColor(LabSkin.TEXT_MAIN);
        title.getPaint().setFakeBoldText(true);
        nav.addView(title);
        navTitle = title;
        root.addView(nav);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(4), 0, dp(30) + LabSkin.navBarHeight(this));
        scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void reload() {
        body.removeAllViews();
        LabRegistry.Svc s = LabRegistry.get(this).svc(pkgId);
        if (s == null) {
            finish();
            return;
        }
        if (navTitle != null) navTitle.setText(s.name);
        boolean running = LabRunner.isRunning(this, pkgId);
        String err = LabRunner.lastError(pkgId);
        LabManifest manifest = peekManifest();
        int port = LabRunner.portOf(this, pkgId);
        String addr = manifest == null ? "" : manifest.humanAddress(port);

        body.addView(bigCard(s, running, err, addr));
        if (!TextUtils.isEmpty(s.needsReview)) {
            body.addView(warnLine(s.needsReview));
        }
        addMainAction(manifest, port, s, running);
        body.addView(toggleButton(running, s));
        body.addView(utilRow(manifest, port, s));
        body.addView(logLink(s));
        body.addView(advancedBox(manifest, s));
    }

    // ---------------- 大卡 ----------------

    private View bigCard(LabRegistry.Svc s, boolean running, String err, String addr) {
        LinearLayout card = cardBox(running);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(s.name);
        name.setTextSize(20);
        name.setTextColor(LabSkin.TEXT_MAIN);
        name.getPaint().setFakeBoldText(true);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(name);
        row.addView(statusTag(running, err));
        card.addView(row);

        if (running && !TextUtils.isEmpty(addr)) {
            View divider = new View(this);
            divider.setBackgroundColor(0x0F000000);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            dlp.topMargin = dp(14);
            dlp.bottomMargin = dp(12);
            divider.setLayoutParams(dlp);
            card.addView(divider);

            TextView lab = new TextView(this);
            lab.setText("服务地址");
            lab.setTextSize(13);
            lab.setTextColor(LabSkin.TEXT_DIM);
            card.addView(lab);
            TextView addrBox = new TextView(this);
            addrBox.setText(addr);
            addrBox.setTextSize(18);
            addrBox.setTextColor(LabSkin.TEXT_MAIN);
            addrBox.getPaint().setFakeBoldText(true);
            addrBox.setPadding(0, dp(4), 0, 0);
            addrBox.setOnClickListener(v -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("lab-addr", addr));
                Notify.show("地址已复制");
            });
            card.addView(addrBox);
        }
        return card;
    }

    private TextView statusTag(boolean running, String err) {
        TextView tag = new TextView(this);
        tag.setTextSize(13);
        tag.setPadding(dp(14), dp(6), dp(14), dp(6));
        if (running) {
            tag.setText("● 运行中");
            tag.setTextColor(LabSkin.GREEN);
            tag.setBackground(LabSkin.tagGreen(this));
        } else if (err != null) {
            tag.setText("出错了");
            tag.setTextColor(LabSkin.RED);
            tag.setBackground(LabSkin.tagGray(this));
        } else {
            tag.setText("已停止");
            tag.setTextColor(LabSkin.TEXT_DIM);
            tag.setBackground(LabSkin.tagGray(this));
        }
        return tag;
    }

    private View warnLine(String needsReview) {
        TextView warn = new TextView(this);
        warn.setText("导入时有没对上的地方，启动前看一眼：\n" + needsReview);
        warn.setTextSize(13);
        warn.setTextColor(LabSkin.AMBER);
        warn.setBackground(LabScrim.bar(this));
        warn.setPadding(dp(18), dp(8), dp(18), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(18), dp(10), dp(18), 0);
        warn.setLayoutParams(lp);
        return warn;
    }

    // ---------------- 主动作 / 启停 ----------------

    private void addMainAction(LabManifest manifest, int port, LabRegistry.Svc s, boolean running) {
        boolean needChoice = running && (manifest == null || manifest.output == null
                || TextUtils.isEmpty(manifest.output.kind));
        if (running && !needChoice && manifest != null) {
            TextView big = bigButton("▶ " + manifest.actionLabel(), true);
            big.setLayoutParams(buttonLp(dp(14)));
            final String url = LabOutputBridge.address(manifest, port);
            big.setOnClickListener(v -> LabOutputBridge.apply(this, manifest, port));
            big.setOnLongClickListener(v -> {
                copyText(url);
                return true;
            });
            body.addView(big);
        } else if (needChoice) {
            TextView b1 = bigButton("当直播源用", true);
            b1.setLayoutParams(buttonLp(dp(14)));
            b1.setOnClickListener(v -> LabOutputBridge.asLive(this, guessUrl(manifest, port), s.name));
            TextView b2 = bigButton("当点播源用", true);
            b2.setLayoutParams(buttonLp(dp(10)));
            b2.setOnClickListener(v -> LabOutputBridge.asVod(this, guessUrl(manifest, port), s.name));
            body.addView(b1);
            body.addView(b2);
        }
    }

    private TextView toggleButton(boolean running, LabRegistry.Svc s) {
        TextView toggle = bigButton(running ? "停止服务" : "启动服务", false);
        toggle.setLayoutParams(buttonLp(dp(10)));
        toggle.setOnClickListener(v -> {
            if (LabRunner.isRunning(this, pkgId)) {
                LabRunner.stop(this, pkgId);
                reload();
            } else {
                toggle.setText("启动中…");
                toggle.setEnabled(false);
                LabRunner.start(this, pkgId, new LabRunner.Listener() {
                    @Override
                    public void onOutput(String text) {
                    }

                    @Override
                    public void onExit(int code) {
                        runOnUiThread(() -> {
                            if (code != 0 && !LabRunner.isRunning(LabServiceActivity.this, pkgId)) {
                                Notify.show("没起来，去运行记录看看");
                            }
                            reload();
                        });
                    }
                });
            }
        });
        return toggle;
    }

    /** 重启 / 分享 / 二维码一行 */
    private View utilRow(LabManifest manifest, int port, LabRegistry.Svc s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(18), dp(10), dp(18), 0);
        row.setLayoutParams(lp);

        TextView restart = smallButton("重启");
        restart.setOnClickListener(v -> {
            LabRunner.stop(this, pkgId);
            restart.setText("启动中…");
            restart.setEnabled(false);
            LabRunner.start(this, pkgId, new LabRunner.Listener() {
                @Override
                public void onOutput(String text) {
                }

                @Override
                public void onExit(int code) {
                    runOnUiThread(() -> reload());
                }
            });
        });
        TextView share = smallButton("分享");
        share.setOnClickListener(v -> {
            String text = shareText(manifest, port, s);
            if (TextUtils.isEmpty(text)) {
                Notify.show("服务没起来，没有可分享的地址");
                return;
            }
            LabOutputBridge.shareText(this, text);
        });
        TextView qr = smallButton("二维码");
        qr.setOnClickListener(v -> {
            String text = shareText(manifest, port, s);
            if (TextUtils.isEmpty(text)) {
                Notify.show("服务没起来，没有可分享的地址");
                return;
            }
            showQr(text);
        });
        row.addView(restart);
        row.addView(share);
        row.addView(qr);
        return row;
    }

    private String shareText(LabManifest manifest, int port, LabRegistry.Svc s) {
        if (manifest == null) return "";
        String url = LabOutputBridge.address(manifest, port);
        if (TextUtils.isEmpty(url)) url = manifest.humanAddress(port);
        if (TextUtils.isEmpty(url)) return "";
        return s.name + "\n" + url;
    }

    private void showQr(String text) {
        try {
            Bitmap bmp = LabOutputBridge.qrBitmap(text, dp(280));
            if (bmp == null) {
                Notify.show("二维码画不出来");
                return;
            }
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bmp);
            int pad = dp(24);
            iv.setPadding(pad, pad, pad, pad);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("扫码打开")
                    .setView(iv)
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Exception e) {
            Notify.show("二维码画不出来");
        }
    }

    private TextView logLink(LabRegistry.Svc s) {
        TextView log = new TextView(this);
        log.setText("查看运行记录 ›");
        log.setTextSize(15);
        log.setTextColor(LabSkin.TEXT_DIM);
        log.setGravity(Gravity.CENTER);
        log.setPadding(0, dp(18), 0, 0);
        log.setOnClickListener(v -> LabLogActivity.start(this, pkgId, s.name));
        return log;
    }

    // ---------------- 高级折叠 ----------------

    private View advancedBox(LabManifest manifest, LabRegistry.Svc s) {
        LinearLayout adv = new LinearLayout(this);
        adv.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMargins(dp(18), dp(14), dp(18), 0);
        adv.setLayoutParams(alp);

        TextView head = new TextView(this);
        head.setText("高级 ▸");
        head.setTextSize(14);
        head.setTextColor(LabSkin.TEXT_DIM);
        head.setPadding(dp(4), dp(8), dp(4), dp(8));
        adv.addView(head);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setVisibility(View.GONE);

        // 命令列表：包命令＋自定义命令
        List<LabManifest.LabCommand> cmds = new ArrayList<>();
        if (manifest != null) cmds.addAll(manifest.commands);
        List<LabCustomCommands.CustomCommand> customs = LabCustomCommands.list(this, pkgId);
        for (LabCustomCommands.CustomCommand cc : customs) cmds.add(LabCustomCommands.toCommand(cc));
        if (!cmds.isEmpty()) {
            inner.addView(sectionLabel("命令"));
            for (LabManifest.LabCommand c : cmds) {
                inner.addView(commandRow(c, isCustom(c, customs)));
            }
            inner.addView(divider());
        }

        inner.addView(advRow("添加命令", () -> showCustomCommandDialog(null)));
        if (manifest != null && !manifest.settings.isEmpty()) {
            inner.addView(advRow("包设置", () -> {
                Map<String, String> cached = LabCustomCommands.settingsOf(this, pkgId);
                LabVarDialog.show(this, "包设置", manifest.settings, cached, "保存",
                        values -> {
                            LabCustomCommands.saveSettings(this, pkgId, values);
                            Notify.show("设置存好了");
                        });
            }));
        }
        inner.addView(advRow("终端", () -> LabTerminalActivity.start(this, pkgId, s.name)));
        inner.addView(advRow("实时输出", () -> LabLiveOutputActivity.start(this, pkgId, s.name)));
        inner.addView(advRow("导出包", this::exportPackage));

        head.setOnClickListener(v -> {
            boolean show = inner.getVisibility() != View.VISIBLE;
            inner.setVisibility(show ? View.VISIBLE : View.GONE);
            head.setText(show ? "高级 ▾" : "高级 ▸");
        });
        adv.addView(inner);
        return adv;
    }

    private boolean isCustom(LabManifest.LabCommand c, List<LabCustomCommands.CustomCommand> customs) {
        for (LabCustomCommands.CustomCommand cc : customs) {
            if (cc.id != null && cc.id.equals(c.id)) return true;
        }
        return false;
    }

    /** 命令行：点行弹变量表运行；快捷钮 运行/停止；长按改/删（仅自定义命令） */
    private View commandRow(LabManifest.LabCommand c, boolean custom) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(10), dp(4), dp(10));

        TextView name = new TextView(this);
        name.setText(c.name == null ? c.id : c.name);
        name.setTextSize(15);
        name.setTextColor(LabSkin.TEXT_MAIN);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(name);

        TextView go = new TextView(this);
        boolean running = LabRunner.isCommandRunning(this, pkgId, c.id);
        go.setText(running ? "停止" : "运行");
        go.setTextSize(14);
        go.setTextColor(running ? LabSkin.TEXT_MAIN : LabSkin.ON_YELLOW);
        go.setBackground(running ? LabSkin.buttonGray(this) : LabSkin.buttonYellow(this));
        go.setPadding(dp(20), dp(8), dp(20), dp(8));
        go.setOnClickListener(v -> {
            if (LabRunner.isCommandRunning(this, pkgId, c.id)) {
                LabRunner.stopCommand(this, pkgId, c.id);
                reload();
            } else {
                runCommandWithVars(c);
            }
        });
        row.addView(go);

        row.setOnClickListener(v -> runCommandWithVars(c));
        if (custom) {
            row.setOnLongClickListener(v -> {
                showCustomCommandDialog(findCustom(c.id));
                return true;
            });
        }
        return row;
    }

    private LabCustomCommands.CustomCommand findCustom(String id) {
        for (LabCustomCommands.CustomCommand cc : LabCustomCommands.list(this, pkgId)) {
            if (cc.id != null && cc.id.equals(id)) return cc;
        }
        return null;
    }

    /** 点行运行：有变量先弹表，没变量直接跑（变量值进缓存，下次默认带上） */
    private void runCommandWithVars(LabManifest.LabCommand c) {
        List<LabManifest.LabVar> vars = c.variables == null
                ? new ArrayList<>() : c.variables;
        if (vars.isEmpty()) {
            LabRunner.startCommand(this, pkgId, c, null, cmdListener());
            Notify.show("跑起来了，去实时输出看");
            return;
        }
        Map<String, String> cached = new HashMap<>(LabCustomCommands.varCache(this, pkgId));
        LabVarDialog.show(this, c.name == null ? "填参数" : c.name, vars, cached, "运行",
                values -> {
                    LabCustomCommands.saveVarCache(this, pkgId, values);
                    LabRunner.startCommand(this, pkgId, c, values, cmdListener());
                    Notify.show("跑起来了，去实时输出看");
                });
    }

    private LabRunner.Listener cmdListener() {
        return new LabRunner.Listener() {
            @Override
            public void onOutput(String text) {
            }

            @Override
            public void onExit(int code) {
                runOnUiThread(() -> reload());
            }
        };
    }

    /** 添加/编辑自定义命令 */
    private void showCustomCommandDialog(LabCustomCommands.CustomCommand old) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));

        EditText nameEt = dlgEdit(box, "名字", old == null ? "" : old.name);
        EditText cmdEt = dlgEdit(box, "命令", old == null ? "" : old.command);
        cmdEt.setMinLines(3);

        LinearLayout swRow = new LinearLayout(this);
        swRow.setOrientation(LinearLayout.HORIZONTAL);
        swRow.setGravity(Gravity.CENTER_VERTICAL);
        swRow.setPadding(0, dp(8), 0, dp(8));
        TextView swLab = new TextView(this);
        swLab.setText("开机自动运行");
        swLab.setTextSize(15);
        swLab.setTextColor(LabSkin.TEXT_MAIN);
        swLab.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setChecked(old != null && old.autoExecute);
        swRow.addView(swLab);
        swRow.addView(sw);
        box.addView(swRow);

        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle(old == null ? "添加命令" : "改命令")
                .setView(box)
                .setNegativeButton("算了", null)
                .setPositiveButton("保存", (d, w) -> {
                    String name = nameEt.getText().toString().trim();
                    String command = cmdEt.getText().toString().trim();
                    if (TextUtils.isEmpty(command)) {
                        Notify.show("命令不能为空");
                        return;
                    }
                    LabCustomCommands.CustomCommand cc = old == null
                            ? new LabCustomCommands.CustomCommand() : old;
                    if (cc.id == null) cc.id = "custom-" + System.currentTimeMillis();
                    if (TextUtils.isEmpty(name)) name = "自定义命令";
                    cc.name = name;
                    cc.command = command;
                    cc.autoExecute = sw.isChecked();
                    if (old == null) LabCustomCommands.add(this, pkgId, cc);
                    else LabCustomCommands.update(this, pkgId, cc);
                    Notify.show("存好了");
                    reload();
                });
        if (old != null) {
            b.setNeutralButton("删除", (d, w) -> {
                LabRunner.stopCommand(this, pkgId, old.id);
                LabCustomCommands.remove(this, pkgId, old.id);
                Notify.show("删掉了");
                reload();
            });
        }
        b.show();
    }

    private EditText dlgEdit(LinearLayout box, String hint, String value) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(value == null ? "" : value);
        et.setTextSize(15);
        et.setTextColor(LabSkin.TEXT_MAIN);
        et.setHintTextColor(LabSkin.TEXT_DIM);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        box.addView(et, lp);
        return et;
    }

    private interface RowAction {
        void run();
    }

    private View advRow(String label, RowAction action) {
        TextView t = new TextView(this);
        t.setText(label + " ›");
        t.setTextSize(15);
        t.setTextColor(LabSkin.TEXT_MAIN);
        t.setPadding(dp(4), dp(12), dp(4), dp(12));
        t.setOnClickListener(v -> action.run());
        return t;
    }

    private TextView sectionLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(LabSkin.TEXT_DIM);
        t.setPadding(dp(4), dp(12), dp(4), dp(2));
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0x0F000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        v.setLayoutParams(lp);
        return v;
    }

    private void exportPackage() {
        Notify.show("正在打包…");
        LabPackageInstaller.export(this, pkgId, new LabPackageInstaller.ExportListener() {
            @Override
            public void onOk(String fileName) {
                runOnUiThread(() -> Notify.show("包已存到下载目录：" + fileName));
            }

            @Override
            public void onFail(String reason) {
                runOnUiThread(() -> Notify.show(reason));
            }
        });
    }

    // ---------------- 小件 ----------------

    private LabManifest peekManifest() {
        try {
            java.io.File mf = LabPaths.manifestOf(this, pkgId);
            if (!mf.exists()) return null;
            java.io.FileInputStream in = new java.io.FileInputStream(mf);
            byte[] buf = new byte[(int) mf.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            in.close();
            return LabManifest.parse(new String(buf, 0, n, "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    private String guessUrl(LabManifest m, int port) {
        if (m != null && m.output != null && !TextUtils.isEmpty(m.output.target)) {
            return m.output.target.replace("{port}", String.valueOf(port));
        }
        return "http://127.0.0.1:" + port + "/";
    }

    private void copyText(String text) {
        if (TextUtils.isEmpty(text)) return;
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("lab", text));
        Notify.show("已复制");
    }

    private LinearLayout cardBox(boolean running) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(LabSkin.cardBackground(this, running));
        card.setPadding(dp(18), dp(20), dp(18), dp(20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(18), dp(4), dp(18), 0);
        card.setLayoutParams(lp);
        return card;
    }

    private LinearLayout.LayoutParams buttonLp(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(18), dp(topMargin), dp(18), 0);
        return lp;
    }

    private TextView bigButton(String text, boolean yellow) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(18);
        b.getPaint().setFakeBoldText(true);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(yellow ? LabSkin.ON_YELLOW : LabSkin.TEXT_MAIN);
        b.setBackground(yellow ? LabSkin.buttonYellow(this) : LabSkin.buttonGray(this));
        b.setPadding(0, dp(16), 0, dp(16));
        return b;
    }

    private TextView smallButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(LabSkin.TEXT_MAIN);
        b.setBackground(LabSkin.buttonGray(this));
        b.setPadding(0, dp(12), 0, dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        int m = dp(5);
        lp.leftMargin = m;
        lp.rightMargin = m;
        b.setLayoutParams(lp);
        return b;
    }
}
