package com.fongmi.android.tv.ui.lab;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Map;

/**
 * 实时输出页（上游 LabOutputActivity 口径，星辰皮）：
 * 命令运行时直播输出，退出码回传。前台命令（background=false）启动时直接进这一页。
 * 走系统返回；页关了进程照旧跑（后台继续）。
 */
public class LabLiveOutputActivity extends AppCompatActivity {

    private static final String EXTRA_PKG = "pkg";
    private static final String EXTRA_CMD = "cmd";
    private static final String EXTRA_TITLE = "title";

    private String pkgId;
    private String cmdId;
    private TextView output;
    private ScrollView scroll;
    private final StringBuilder buf = new StringBuilder();

    /** 按服务默认命令看实时输出 */
    public static void start(Activity activity, String pkgId, String title) {
        start(activity, pkgId, null, title);
    }

    /** 按命令看实时输出（cmdId 为 null = 服务默认命令） */
    public static void start(Activity activity, String pkgId, String cmdId, String title) {
        Intent i = new Intent(activity, LabLiveOutputActivity.class);
        i.putExtra(EXTRA_PKG, pkgId);
        if (cmdId != null) i.putExtra(EXTRA_CMD, cmdId);
        i.putExtra(EXTRA_TITLE, title == null ? "实时输出" : title);
        activity.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pkgId = getIntent().getStringExtra(EXTRA_PKG);
        cmdId = getIntent().getStringExtra(EXTRA_CMD);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null) title = "实时输出";
        LabSkin.edgeToEdge(this);
        buildUi(title);
        String key = cmdId == null ? null : LabRunner.commandKey(pkgId, cmdId);
        String log = LabRunner.getLog(this, pkgId);
        if (log != null && !log.isEmpty()) append(log);
        // 订阅后续输出（页关了自动断订，不影响进程）
        LiveBus.watch(key == null ? pkgId : key, new LabRunner.Listener() {
            @Override
            public void onOutput(String text) {
                runOnUiThread(() -> append(text));
            }

            @Override
            public void onExit(int code) {
                runOnUiThread(() -> append("\n[进程退出，code=" + code + "]\n"));
            }
        });
    }

    @Override
    protected void onDestroy() {
        LiveBus.unwatch(pkgId);
        super.onDestroy();
    }

    private int dp(int v) {
        return LabSkin.dp(this, v);
    }

    private void buildUi(String title) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setBackground(LabScrim.bar(this));
        nav.setPadding(dp(18), dp(14) + LabSkin.statusBarHeight(this), dp(18), dp(6));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(20);
        t.setTextColor(LabSkin.TEXT_MAIN);
        t.getPaint().setFakeBoldText(true);
        nav.addView(t);
        root.addView(nav);

        output = new TextView(this);
        output.setTextSize(13);
        output.setTextColor(LabSkin.TEXT_BODY);
        output.setMovementMethod(new ScrollingMovementMethod());
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(LabSkin.cardBackground(this, false));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        clp.setMargins(dp(18), dp(8), dp(18), dp(18) + LabSkin.navBarHeight(this));
        card.addView(output, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.addView(card, clp);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void append(String s) {
        if (s == null || s.isEmpty()) return;
        buf.append(s);
        if (buf.length() > 200_000) buf.delete(0, buf.length() - 200_000);
        output.setText(buf.toString());
        scroll.post(() -> scroll.fullScroll(android.view.View.FOCUS_DOWN));
    }

    /**
     * 输出总线：运行中的命令把输出广播给正在看的页面。
     * LabRunner.pump 里已有 listener 直连；这里给「启动时已在跑」的页面补订阅。
     */
    public static final class LiveBus {
        private static final Map<String, LabRunner.Listener> WATCHERS = new java.util.concurrent.ConcurrentHashMap<>();

        public static void watch(String tag, LabRunner.Listener l) {
            if (tag != null && l != null) WATCHERS.put(tag, l);
        }

        public static void unwatch(String tag) {
            if (tag != null) WATCHERS.remove(tag);
        }

        public static void emit(String tag, String text) {
            LabRunner.Listener l = WATCHERS.get(tag);
            if (l != null) l.onOutput(text);
        }

        public static void emitExit(String tag, int code) {
            LabRunner.Listener l = WATCHERS.get(tag);
            if (l != null) l.onExit(code);
        }
    }
}
