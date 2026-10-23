package com.fongmi.android.tv.ui.lab;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.utils.Notify;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.fongmi.android.tv.R;

/**
 * 粘贴导入（头等路）：用户把服务清单 / 命令条目 / 脚本源码粘进框，
 * 壳子立刻识别格式、把服务全列出来，点一下就装。
 * 单行 http(s) 链接也会先拉下来再识别（链接路走同一框）。
 * 选文件时拿到文本类文件（.json/.php/.js/.py/.txt）也转到这里按内容识别。
 * 不设叉，走系统返回。
 */
public class LabPasteImportActivity extends AppCompatActivity {

    private static final String EXTRA_URI = "uri";
    private static final String EXTRA_MODE = "mode";
    private static final String EXTRA_DEFAULT_ENV = "default_env";
    private static final String EXTRA_URL = "url";
    /** 粘贴模式：多行文本框 */
    private static final String MODE_PASTE = "paste";
    /** 链接模式：单行 URL 输入框 */
    private static final String MODE_LINK = "link";

    private EditText input;
    private TextView status;
    private LinearLayout resultBox;
    private TextView importBtn;
    private TextView goBtn;
    private List<LabImportCompat.ImportItem> items;
    private String mode = MODE_PASTE;

    public static void start(Activity activity) {
        start(activity, null);
    }

    /** 环境页「＋」→粘贴：默认落当前环境（条目没声明运行时才用） */
    public static void start(Activity activity, String defaultEnv) {
        Intent i = new Intent(activity, LabPasteImportActivity.class);
        i.putExtra(EXTRA_MODE, MODE_PASTE);
        if (defaultEnv != null) i.putExtra(EXTRA_DEFAULT_ENV, defaultEnv);
        activity.startActivity(i);
    }

    /** 链接入口：单行输入框，拉下来再识别 */
    public static void startLink(Activity activity) {
        startLink(activity, null);
    }

    /** 环境页「＋」→链接：默认落当前环境（条目没声明运行时才用） */
    public static void startLink(Activity activity, String defaultEnv) {
        Intent i = new Intent(activity, LabPasteImportActivity.class);
        i.putExtra(EXTRA_MODE, MODE_LINK);
        if (defaultEnv != null) i.putExtra(EXTRA_DEFAULT_ENV, defaultEnv);
        activity.startActivity(i);
    }

    /** 添加服务页的链接卡跳过来：链接模式＋输入框预填 */
    public static void startWithUrl(Activity activity, String url) {
        Intent i = new Intent(activity, LabPasteImportActivity.class);
        i.putExtra(EXTRA_MODE, MODE_LINK);
        i.putExtra(EXTRA_URL, url);
        activity.startActivity(i);
    }

    private static void startMode(Activity activity, String mode) {
        Intent i = new Intent(activity, LabPasteImportActivity.class);
        i.putExtra(EXTRA_MODE, mode);
        activity.startActivity(i);
    }

    public static void startWithUri(Activity activity, Uri uri) {
        Intent i = new Intent(activity, LabPasteImportActivity.class);
        i.putExtra(EXTRA_URI, uri.toString());
        activity.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String m = getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_LINK.equals(m)) mode = MODE_LINK;
        LabSkin.edgeToEdge(this);
        buildUi();
        String prefill = getIntent().getStringExtra(EXTRA_URL);
        if (!TextUtils.isEmpty(prefill) && input != null) input.setText(prefill);
        String uriStr = getIntent().getStringExtra(EXTRA_URI);
        if (!TextUtils.isEmpty(uriStr)) {
            loadFromUri(Uri.parse(uriStr));
        }

        // 页面进出统一渐入渐出（与 BaseActivity 一致）
        overridePendingTransition(R.anim.xc_fade_in, R.anim.xc_fade_out);
}

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.xc_fade_in, R.anim.xc_fade_out);
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
        
        nav.setPadding(dp(18), dp(14) + LabSkin.statusBarHeight(this), dp(18), dp(6));
        TextView title = new TextView(this);
        boolean linkMode = MODE_LINK.equals(mode);
        title.setText(linkMode ? "链接导入" : "粘贴导入");
        title.setTextSize(20);
        title.setTextColor(LabSkin.TEXT_MAIN);
        title.getPaint().setFakeBoldText(true);
        nav.addView(title);
        root.addView(nav);

        TextView hint = new TextView(this);
        hint.setText(linkMode ? "粘贴服务清单的链接，拉下来自动识别"
                : "把服务清单、命令条目或脚本粘进来，自动识别");
        hint.setTextSize(14);
        hint.setTextColor(LabSkin.TEXT_ON_DARK_DIM);
        hint.setBackground(LabSkin.glassDark(this));
        hint.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(dp(18), dp(2), dp(18), 0);
        hint.setLayoutParams(hlp);
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(30) + LabSkin.navBarHeight(this));

        input = new EditText(this);
        if (MODE_LINK.equals(mode)) {
            input.setSingleLine(true);
            input.setHint("https://…");
        } else {
            input.setMinLines(8);
            input.setHint("粘在这里…");
        }
        input.setTextSize(13);
        input.setTextColor(LabSkin.TEXT_ON_DARK);
        input.setHintTextColor(LabSkin.TEXT_ON_DARK_DIM);
        input.setBackground(LabSkin.glassDark(this));
        input.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(dp(18), dp(4), dp(18), 0);
        body.addView(input, ilp);

        goBtn = bigButton(MODE_LINK.equals(mode) ? "拉取并识别" : "识别", true);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.setMargins(dp(18), dp(12), dp(18), 0);
        goBtn.setLayoutParams(glp);
        goBtn.setOnClickListener(v -> recognize(input.getText().toString(), null));
        body.addView(goBtn);

        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(LabSkin.COUNT_YELLOW);
        status.setBackground(LabSkin.glassDark(this));
        status.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.setMargins(dp(18), dp(10), dp(18), 0);
        status.setLayoutParams(stlp);
        status.setVisibility(View.GONE);
        body.addView(status);

        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(resultBox);

        importBtn = bigButton("导入", true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(dp(18), dp(12), dp(18), 0);
        importBtn.setLayoutParams(blp);
        importBtn.setVisibility(View.GONE);
        importBtn.setOnClickListener(v -> doImport());
        body.addView(importBtn);

        scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void loadFromUri(Uri uri) {
        status.setVisibility(View.VISIBLE);
        status.setText("正在读文件…");
        new Thread(() -> {
            try {
                String name = displayName(uri);
                // 先落盘再识别：7z 离线包（整目录 Node 项目也走这条）整包不进内存，
                // 边读边数，上限仍是 30MB；文本内容仍在下面按 2MB 拦，语义不变
                java.io.File spool = spoolToFile(getContentResolver().openInputStream(uri),
                        30 * 1024 * 1024, "文件太大");
                try {
                    if (!dispatchSevenZ(spool, name)) {
                        if (spool.length() > 2 * 1024 * 1024) throw new Exception("文件太大");
                        String text = new String(readAll(new java.io.FileInputStream(spool),
                                2 * 1024 * 1024), StandardCharsets.UTF_8);
                        final String fn = name;
                        runOnUiThread(() -> recognize(text, fn));
                    }
                } catch (Exception pe) {
                    final String msg = plainError(pe);
                    runOnUiThread(() -> {
                        status.setVisibility(View.VISIBLE);
                        status.setText(msg);
                    });
                } finally {
                    spool.delete();
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setVisibility(View.VISIBLE);
                    status.setText("读不到这个文件");
                });
            }
        }).start();
    }

    private String displayName(Uri uri) {
        try {
            ContentResolver cr = getContentResolver();
            try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) return c.getString(0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 识别：单行链接先下载，否则直接按文本识别 */
    private void recognize(String text, String fileName) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            showStatus(MODE_LINK.equals(mode) ? "先粘个链接进来" : "先粘点东西进来");
            return;
        }
        showStatus("正在识别…");
        resultBox.removeAllViews();
        importBtn.setVisibility(View.GONE);
        items = null;
        new Thread(() -> {
            try {
                String src = t;
                String fn = fileName;
                if (isSingleUrl(t)) {
                    showStatusOnUi("正在拉链接…");
                    // 链接内容先落盘：.lab.7z 离线包整包不进内存（上限仍 30MB）
                    java.io.File spool = downloadFile(t, 30 * 1024 * 1024);
                    try {
                        // 链接也可能是 .lab.7z 离线包：先看魔数/后缀
                        if (dispatchSevenZ(spool, t)) return;
                        if (spool.length() > 2 * 1024 * 1024) throw new Exception("这个文件太大了，下不动");
                        src = new String(readAll(new java.io.FileInputStream(spool),
                                2 * 1024 * 1024), StandardCharsets.UTF_8);
                        fn = null; // 链接内容按内容识别
                    } finally {
                        spool.delete();
                    }
                }
                final LabImportCompat.ParseResult r = LabImportCompat.parseText(src, fn);
                runOnUiThread(() -> showResult(r));
            } catch (Exception e) {
                final String msg = plainError(e);
                runOnUiThread(() -> showStatus(msg));
            }
        }).start();
    }

    /**
     * 报错给人话但不吞真因：自家异常本来就是人话，原样给；解析库的原始报错
     * 只折成一行、截到 140 字，不再整段换成一句「认不出来」——出错位置、
     * parseScript 的后缀提示这类真原因必须留得住（此前一律换短句，真因全吞）。
     */
    private static String plainError(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) return "认不出来，换一种试试";
        String one = msg.replaceAll("\\s+", " ").trim();
        if (one.length() > 140) one = one.substring(0, 140) + "…";
        return one;
    }

    private boolean isSingleUrl(String t) {
        return t.matches("(?i)https?://\\S+") && !t.contains("\n");
    }

    /** 下载落盘：文本路 2MB 上限在读文本时拦（语义不变），7z 包路放宽到 30MB、整包不进内存 */
    private java.io.File downloadFile(String urlStr, int maxBytes) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "XingChenLab/1.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("这个链接拉不下来（" + code + "）");
            return spoolToFile(conn.getInputStream(), maxBytes, "这个文件太大了，下不动");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 流边读边落缓存临时文件（边读边数上限），调用方负责删 */
    private java.io.File spoolToFile(InputStream in, int maxBytes, String tooBigMsg) throws Exception {
        if (in == null) throw new Exception("打不开");
        java.io.File f = java.io.File.createTempFile("lab-import-", ".bin", getCacheDir());
        boolean ok = false;
        try (InputStream i = in;
             java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = i.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) throw new Exception(tooBigMsg);
                out.write(buf, 0, n);
            }
            ok = true;
            return f;
        } finally {
            if (!ok) f.delete();
        }
    }

    /** 落盘文件先嗅 7z（魔数/后缀）：是就走离线包解析并展示，返回 true；不是返回 false 交回文本路 */
    private boolean dispatchSevenZ(java.io.File spool, String sniffName) throws Exception {
        byte[] head = new byte[6];
        int hn = 0;
        try (InputStream in = new java.io.FileInputStream(spool)) {
            int r;
            while (hn < head.length && (r = in.read(head, hn, head.length - hn)) > 0) hn += r;
        }
        byte[] magic = hn == head.length ? head : java.util.Arrays.copyOf(head, Math.max(hn, 0));
        if (!LabImportCompat.isSevenZ(magic, sniffName)) return false;
        final LabImportCompat.ParseResult r;
        try (InputStream in = new java.io.FileInputStream(spool)) {
            r = LabImportCompat.parseSevenZ(in);
        }
        runOnUiThread(() -> showResult(r));
        return true;
    }

    private void showResult(LabImportCompat.ParseResult r) {
        items = r.items;
        showStatus(r.note);
        for (LabImportCompat.ImportItem it : r.items) {
            resultBox.addView(itemCard(it));
        }
        importBtn.setText("导入这 " + r.items.size() + " 个服务");
        importBtn.setVisibility(View.VISIBLE);
    }

    private View itemCard(LabImportCompat.ImportItem it) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(LabSkin.cardBackground(this, false));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(18), dp(10), dp(18), 0);
        card.setLayoutParams(lp);

        TextView name = new TextView(this);
        name.setText(it.name);
        name.setTextSize(17);
        name.setTextColor(LabSkin.TEXT_MAIN);
        name.getPaint().setFakeBoldText(true);
        card.addView(name);

        TextView meta = new TextView(this);
        String port = "auto".equals(it.portPolicy) ? "端口自动分" : "端口 " + it.portPolicy;
        meta.setText(LabRuntimeManager.label(it.runtime) + " · " + port);
        meta.setTextSize(13);
        meta.setTextColor(LabSkin.TEXT_SUB);
        meta.setPadding(0, dp(6), 0, 0);
        card.addView(meta);

        if (!TextUtils.isEmpty(it.needsReview)) {
            TextView warn = new TextView(this);
            warn.setText("要确认：\n" + it.needsReview);
            warn.setTextSize(13);
            warn.setTextColor(LabSkin.AMBER);
            warn.setPadding(0, dp(8), 0, 0);
            card.addView(warn);
        }
        return card;
    }

    private void doImport() {
        if (items == null || items.isEmpty()) return;
        importBtn.setEnabled(false);
        importBtn.setText("导入中…");
        String defaultEnv = getIntent().getStringExtra(EXTRA_DEFAULT_ENV);
        // 照稿弹安装中弹窗：条目声明的环境没装会自动先装（进度同样进这个弹窗）
        LabInstallDialog.Installing dlg = LabInstallDialog.showInstalling(this, "正在导入服务");
        final LabPackageInstaller.InstallHandle[] handle = new LabPackageInstaller.InstallHandle[1];
        dlg.setOnCancel(() -> {
            if (handle[0] != null) handle[0].cancel();
        });
        Runnable[] retry = new Runnable[1];
        retry[0] = () -> handle[0] = LabPackageInstaller.installCompat(this, items, defaultEnv,
                new LabPackageInstaller.CompatListener() {
                    @Override
                    public void onProgress(String text) {
                        String pct = LabInstallDialog.shardLine(text);
                        if (pct != null) dlg.progress(pct, text);
                        else dlg.busy(text);
                    }

                    @Override
                    public void onDone(int count) {
                        runOnUiThread(() -> {
                            dlg.ok();
                            Notify.show("装好了 " + count + " 个");
                            finish();
                        });
                    }

                    @Override
                    public void onFail(String reason) {
                        runOnUiThread(() -> {
                            importBtn.setEnabled(true);
                            importBtn.setText("导入这 " + items.size() + " 个服务");
                            dlg.error(reason, retry[0]);
                        });
                    }

                    @Override
                    public void onCancelled() {
                        runOnUiThread(() -> {
                            dlg.cancelled();
                            importBtn.setEnabled(true);
                            importBtn.setText("导入这 " + items.size() + " 个服务");
                            Notify.show("已取消安装");
                        });
                    }
                });
        retry[0].run();
    }

    private void showStatus(String s) {
        status.setVisibility(View.VISIBLE);
        status.setText(s);
    }

    private void showStatusOnUi(String s) {
        runOnUiThread(() -> showStatus(s));
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

    private byte[] readAll(InputStream in, int maxBytes) throws Exception {
        if (in == null) throw new Exception("打不开");
        try (InputStream i = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = i.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) throw new Exception("文件太大");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
