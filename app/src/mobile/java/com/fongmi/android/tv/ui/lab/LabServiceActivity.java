package com.fongmi.android.tv.ui.lab;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.utils.Notify;

/**
 * 服务详情页（定稿图 lab-service.png）：
 * 大卡＝状态＋产出地址人话一行；主动作大黄钮；停止钮；
 * 「查看运行记录 ›」次入口进日志页（每包一份自己的日志，留全但藏着）；
 * 命令等收进「高级」折叠，默认收起。不设叉，走系统返回。
 */
public class LabServiceActivity extends AppCompatActivity {

    private static final String EXTRA_ID = "id";

    private String pkgId;
    private LinearLayout root;

    public static void start(Activity activity, String pkgId) {
        Intent i = new Intent(activity, LabServiceActivity.class);
        i.putExtra(EXTRA_ID, pkgId);
        activity.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pkgId = getIntent().getStringExtra(EXTRA_ID);
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
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(LabSkin.pageBackground(this));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(30));
        scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.setTag(body);
        setContentView(root);
    }

    private void reload() {
        LinearLayout body = (LinearLayout) root.getTag();
        body.removeAllViews();
        LabRegistry.Svc s = LabRegistry.get(this).svc(pkgId);
        if (s == null) {
            finish();
            return;
        }
        boolean running = LabRunner.isRunning(this, pkgId);

        // 导航：‹ ＋ 服务名
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
        title.setText(s.name);
        title.setTextSize(20);
        title.setTextColor(LabSkin.TEXT_MAIN);
        title.getPaint().setFakeBoldText(true);
        nav.addView(back);
        nav.addView(title);
        body.addView(nav);

        // 大卡
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(LabSkin.cardBackground(this, running));
        card.setPadding(dp(18), dp(20), dp(18), dp(20));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dp(18), dp(10), dp(18), 0);
        card.setLayoutParams(clp);

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
        TextView tag = new TextView(this);
        tag.setTextSize(13);
        tag.setPadding(dp(14), dp(6), dp(14), dp(6));
        if (running) {
            tag.setText("● 运行中");
            tag.setTextColor(LabSkin.GREEN);
            tag.setBackground(LabSkin.tagGreen(this));
        } else {
            tag.setText("已停止");
            tag.setTextColor(LabSkin.TEXT_DIM);
            tag.setBackground(LabSkin.tagGray(this));
        }
        row.addView(tag);
        card.addView(row);

        // 产出地址人话一行
        int port = LabRunner.portOf(this, pkgId);
        String addr = "";
        try {
            java.io.File mf = LabPaths.manifestOf(this, pkgId);
            if (mf.exists()) {
                java.io.FileInputStream in = new java.io.FileInputStream(mf);
                byte[] buf = new byte[(int) mf.length()];
                int n = 0, r;
                while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
                in.close();
                LabManifest m = LabManifest.parse(new String(buf, 0, n, "UTF-8"));
                addr = m.humanAddress(port);
            }
        } catch (Exception ignored) {
        }
        if (running && !TextUtils.isEmpty(addr)) {
            TextView addrBox = new TextView(this);
            addrBox.setText("服务地址\n" + addr);
            addrBox.setTextSize(15);
            addrBox.setTextColor(LabSkin.TEXT_MAIN);
            addrBox.setPadding(0, dp(18), 0, 0);
            card.addView(addrBox);
        }
        body.addView(card);

        // 兼容导入时没映射完、要用户人工确认的事项（有才显示）
        if (!TextUtils.isEmpty(s.needsReview)) {
            TextView warn = new TextView(this);
            warn.setText("导入时有没对上的地方，启动前看一眼：\n" + s.needsReview);
            warn.setTextSize(13);
            warn.setTextColor(LabSkin.YELLOW);
            warn.setPadding(dp(18), dp(10), dp(18), 0);
            body.addView(warn);
        }

        // 主动作大黄钮（按产出声明；缺声明给两钮）
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(dp(18), dp(14), dp(18), 0);
        LabManifest manifest = peekManifest();
        boolean needChoice = running && (manifest == null || manifest.output == null
                || TextUtils.isEmpty(manifest.output.kind));
        if (running && !needChoice && manifest != null) {
            TextView big = bigButton(manifest.actionLabel(), true);
            big.setLayoutParams(blp);
            big.setOnClickListener(v -> LabOutputBridge.apply(this, manifest, port));
            body.addView(big);
        } else if (needChoice) {
            TextView b1 = bigButton("当直播源用", true);
            b1.setLayoutParams(blp);
            b1.setOnClickListener(v -> LabOutputBridge.asLive(this, guessUrl(manifest, port), s.name));
            TextView b2 = bigButton("当点播源用", true);
            LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp2.setMargins(dp(18), dp(10), dp(18), 0);
            b2.setLayoutParams(blp2);
            b2.setOnClickListener(v -> LabOutputBridge.asVod(this, guessUrl(manifest, port), s.name));
            body.addView(b1);
            body.addView(b2);
        }

        // 停止/启动
        TextView toggle = bigButton(running ? "停止服务" : "启动服务", false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(dp(18), dp(10), dp(18), 0);
        toggle.setLayoutParams(tlp);
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
        body.addView(toggle);

        // 查看运行记录 ›
        TextView log = new TextView(this);
        log.setText("查看运行记录 ›");
        log.setTextSize(15);
        log.setTextColor(0xFF9A9AA4);
        log.setGravity(Gravity.CENTER);
        log.setPadding(0, dp(18), 0, 0);
        log.setOnClickListener(v -> LabLogActivity.start(this, pkgId, s.name));
        body.addView(log);

        // 高级折叠：命令文本，默认收起
        if (manifest != null && !manifest.commands.isEmpty()) {
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
            TextView cmds = new TextView(this);
            StringBuilder sb = new StringBuilder();
            for (LabManifest.LabCommand c : manifest.commands) {
                sb.append(c.name).append("\n").append(c.command).append("\n\n");
            }
            cmds.setText(sb.toString().trim());
            cmds.setTextSize(13);
            cmds.setTextColor(LabSkin.TEXT_SUB);
            cmds.setPadding(dp(4), 0, dp(4), dp(8));
            cmds.setVisibility(View.GONE);
            head.setOnClickListener(v -> {
                boolean show = cmds.getVisibility() != View.VISIBLE;
                cmds.setVisibility(show ? View.VISIBLE : View.GONE);
                head.setText(show ? "高级 ▾" : "高级 ▸");
            });
            adv.addView(head);
            adv.addView(cmds);
            body.addView(adv);
        }
    }

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
        if (m != null && !TextUtils.isEmpty(m.output.target)) {
            return m.output.target.replace("{port}", String.valueOf(port));
        }
        return "http://127.0.0.1:" + port + "/";
    }

    private TextView bigButton(String text, boolean yellow) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(18);
        b.getPaint().setFakeBoldText(true);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(yellow ? 0xFF171310 : LabSkin.TEXT_MAIN);
        b.setBackground(yellow ? LabSkin.buttonYellow(this) : LabSkin.buttonGray(this));
        b.setPadding(0, dp(16), 0, dp(16));
        return b;
    }
}
