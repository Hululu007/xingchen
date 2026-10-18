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

/**
 * 粘贴导入（头等路）：用户把服务清单 / 命令条目 / 脚本源码粘进框，
 * 壳子立刻识别格式、把服务全列出来，点一下就装。
 * 单行 http(s) 链接也会先拉下来再识别（链接路走同一框）。
 * 选文件时拿到文本类文件（.json/.php/.js/.py/.txt）也转到这里按内容识别。
 * 不设叉，走系统返回。
 */
public class LabPasteImportActivity extends AppCompatActivity {

    private static final String EXTRA_URI = "uri";

    private EditText input;
    private TextView status;
    private LinearLayout resultBox;
    private TextView importBtn;
    private List<LabImportCompat.ImportItem> items;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, LabPasteImportActivity.class));
    }

    public static void startWithUri(Activity activity, Uri uri) {
        Intent i = new Intent(activity, LabPasteImportActivity.class);
        i.putExtra(EXTRA_URI, uri.toString());
        activity.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        String uriStr = getIntent().getStringExtra(EXTRA_URI);
        if (!TextUtils.isEmpty(uriStr)) {
            loadFromUri(Uri.parse(uriStr));
        }
    }

    private int dp(int v) {
        return LabSkin.dp(this, v);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(LabSkin.pageBackground(this));

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
        title.setText("粘贴导入");
        title.setTextSize(20);
        title.setTextColor(LabSkin.TEXT_MAIN);
        title.getPaint().setFakeBoldText(true);
        nav.addView(back);
        nav.addView(title);
        root.addView(nav);

        TextView hint = new TextView(this);
        hint.setText("把服务清单、命令条目或脚本粘进来，自动识别；也可以粘一个清单链接");
        hint.setTextSize(14);
        hint.setTextColor(LabSkin.TEXT_SUB);
        hint.setPadding(dp(18), dp(4), dp(18), dp(8));
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(30));

        input = new EditText(this);
        input.setMinLines(8);
        input.setTextSize(13);
        input.setTextColor(LabSkin.TEXT_MAIN);
        input.setHintTextColor(LabSkin.TEXT_DIM);
        input.setHint("粘在这里…");
        input.setBackground(LabSkin.cardBackground(this, false));
        input.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(dp(18), dp(4), dp(18), 0);
        body.addView(input, ilp);

        TextView go = bigButton("识别", true);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.setMargins(dp(18), dp(12), dp(18), 0);
        go.setLayoutParams(glp);
        go.setOnClickListener(v -> recognize(input.getText().toString(), null));
        body.addView(go);

        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(LabSkin.YELLOW);
        status.setPadding(dp(18), dp(10), dp(18), 0);
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
                byte[] data = readAll(getContentResolver().openInputStream(uri));
                String text = new String(data, StandardCharsets.UTF_8);
                final String fn = name;
                runOnUiThread(() -> recognize(text, fn));
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
            showStatus("先粘点东西进来");
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
                    src = download(t);
                    fn = null; // 链接内容按内容识别
                }
                final LabImportCompat.ParseResult r = LabImportCompat.parseText(src, fn);
                runOnUiThread(() -> showResult(r));
            } catch (Exception e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> showStatus(msg == null ? "认不出来，换一种试试" : msg));
            }
        }).start();
    }

    private boolean isSingleUrl(String t) {
        return t.matches("(?i)https?://\\S+") && !t.contains("\n");
    }

    private String download(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "XingChenLab/1.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("这个链接拉不下来（" + code + "）");
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 2 * 1024 * 1024) throw new Exception("这个文件太大了，下不动");
                    out.write(buf, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
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
            warn.setTextColor(LabSkin.YELLOW);
            warn.setPadding(0, dp(8), 0, 0);
            card.addView(warn);
        }
        return card;
    }

    private void doImport() {
        if (items == null || items.isEmpty()) return;
        importBtn.setEnabled(false);
        importBtn.setText("导入中…");
        LabPackageInstaller.installCompat(this, items, new LabPackageInstaller.CompatListener() {
            @Override
            public void onProgress(String text) {
                runOnUiThread(() -> showStatus(text));
            }

            @Override
            public void onDone(int count) {
                runOnUiThread(() -> {
                    Notify.show("装好了 " + count + " 个");
                    finish();
                });
            }

            @Override
            public void onFail(String reason) {
                runOnUiThread(() -> {
                    importBtn.setEnabled(true);
                    importBtn.setText("导入这 " + items.size() + " 个服务");
                    showStatus(reason);
                    Notify.show(reason);
                });
            }
        });
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

    private byte[] readAll(InputStream in) throws Exception {
        if (in == null) throw new Exception("打不开");
        try (InputStream i = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = i.read(buf)) > 0) {
                total += n;
                if (total > 2 * 1024 * 1024) throw new Exception("文件太大");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
