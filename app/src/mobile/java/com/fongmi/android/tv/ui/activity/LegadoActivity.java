package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.legado.LegadoException;
import com.fongmi.android.tv.legado.LegadoPipeline;
import com.fongmi.android.tv.legado.LegadoReading;
import com.fongmi.android.tv.legado.LegadoSource;
import com.fongmi.android.tv.legado.LegadoStore;
import com.fongmi.android.tv.read.ReadingRouter;
import com.fongmi.android.tv.read.ReadingSession;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 书源管理（阶段一最小面）：导入（文本/文件/链接）· 启用停用 · 删除 · 搜索 · 读。
 * 精修稿另案，本件只求能用。
 */
public class LegadoActivity extends Activity {

    private static final int REQ_PICK_FILE = 1001;

    private SrcAdapter adapter;
    private final List<LegadoStore.Entry> entries = new ArrayList<>();
    private final List<LegadoSource> parsed = new ArrayList<>();

    public static void start(android.content.Context context) {
        context.startActivity(new Intent(context, LegadoActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F3EF);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(28), dp(12), dp(12));
        topBar.setBackgroundColor(Color.WHITE);

        ImageButton back = new ImageButton(this);
        back.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(v -> finish());
        topBar.addView(back);

        TextView title = new TextView(this);
        title.setText("书源");
        title.setTextSize(18);
        title.setTextColor(0xFF222222);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = dp(8);
        title.setLayoutParams(tlp);
        topBar.addView(title);

        TextView importBtn = new TextView(this);
        importBtn.setText("＋ 导入");
        importBtn.setTextSize(15);
        importBtn.setTextColor(0xFFB8860B);
        importBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        importBtn.setOnClickListener(v -> showImportDialog());
        topBar.addView(importBtn);
        root.addView(topBar);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SrcAdapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        reload();
    }

    private void reload() {
        entries.clear();
        parsed.clear();
        for (LegadoStore.Entry e : LegadoStore.all()) {
            try {
                LegadoSource s = LegadoStore.parse(e.json);
                entries.add(e);
                parsed.add(s);
            } catch (Throwable ignored) {
            }
        }
        adapter.notifyDataSetChanged();
    }

    // ================= 导入 =================

    private void showImportDialog() {
        new AlertDialog.Builder(this)
                .setTitle("导入书源")
                .setItems(new String[]{"粘贴 JSON 文本", "选择本地文件", "输入链接"}, (d, which) -> {
                    if (which == 0) showPasteDialog();
                    else if (which == 1) pickFile();
                    else showUrlDialog();
                })
                .show();
    }

    private void showPasteDialog() {
        EditText et = new EditText(this);
        et.setHint("粘贴书源 JSON");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(6);
        et.setGravity(Gravity.TOP);
        new AlertDialog.Builder(this).setTitle("粘贴导入").setView(et)
                .setPositiveButton("导入", (d, w) -> doImport(() -> LegadoStore.importText(et.getText().toString())))
                .setNegativeButton("取消", null).show();
    }

    private void showUrlDialog() {
        EditText et = new EditText(this);
        et.setHint("https://…/xxx.json");
        new AlertDialog.Builder(this).setTitle("链接导入").setView(et)
                .setPositiveButton("拉取", (d, w) -> doImport(() -> LegadoStore.importUrl(et.getText().toString().trim())))
                .setNegativeButton("取消", null).show();
    }

    private void pickFile() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "选择书源文件"), REQ_PICK_FILE);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            doImport(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new LegadoException("打不开文件");
                    byte[] buf = new byte[8192];
                    StringBuilder sb = new StringBuilder();
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > 2 * 1024 * 1024) throw new LegadoException("文件过大（>2MB）");
                        sb.append(new String(buf, 0, n, "UTF-8"));
                    }
                    return LegadoStore.importText(sb.toString());
                } catch (LegadoException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new LegadoException("文件读取失败: " + t.getMessage());
                }
            });
        }
    }

    private interface Importer {
        String run();
    }

    private void doImport(Importer importer) {
        Toast.makeText(this, "导入中…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String name = importer.run();
                runOnUiThread(() -> {
                    Toast.makeText(this, "已导入：" + name, Toast.LENGTH_SHORT).show();
                    reload();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this,
                        "导入失败：" + msg(t), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ================= 搜索 → 读 =================

    private void onSourceTap(LegadoSource s) {
        if (!LegadoSource.runnable(s.bookSourceType)) {
            Toast.makeText(this, s.typeLabel() + "源暂不支持（阶段一）", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText et = new EditText(this);
        et.setHint("输入书名/作者");
        new AlertDialog.Builder(this).setTitle("在「" + s.tag() + "」搜索").setView(et)
                .setPositiveButton("搜索", (d, w) -> doSearch(s, et.getText().toString().trim()))
                .setNegativeButton("取消", null).show();
    }

    private void doSearch(LegadoSource s, String key) {
        if (key.isEmpty()) return;
        Toast.makeText(this, "搜索中…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                List<LegadoPipeline.Book> books = new LegadoPipeline(s).search(key);
                runOnUiThread(() -> showBooks(s, books));
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this, "搜索失败：" + msg(t), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showBooks(LegadoSource s, List<LegadoPipeline.Book> books) {
        if (books.isEmpty()) {
            Toast.makeText(this, "没有搜到", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[books.size()];
        for (int i = 0; i < names.length; i++) {
            LegadoPipeline.Book b = books.get(i);
            names[i] = b.name + (b.author.isEmpty() ? "" : " · " + b.author);
        }
        new AlertDialog.Builder(this).setTitle("搜索结果（" + names.length + "）")
                .setItems(names, (d, which) -> doOpenBook(s, books.get(which)))
                .show();
    }

    private void doOpenBook(LegadoSource s, LegadoPipeline.Book book) {
        Toast.makeText(this, "拉取目录中…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                LegadoPipeline p = new LegadoPipeline(s);
                LegadoPipeline.BookInfo info = p.bookInfo(book.bookUrl);
                if (info.name.isEmpty()) info.name = book.name;
                if (info.coverUrl.isEmpty()) info.coverUrl = book.coverUrl;
                String tocUrl = info.tocUrl.isEmpty() ? book.bookUrl : info.tocUrl;
                List<LegadoPipeline.TocChapter> chapters = p.toc(tocUrl);
                if (chapters.isEmpty()) throw new LegadoException("目录为空");
                runOnUiThread(() -> showChapters(s, info, chapters));
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this, "打开失败：" + msg(t), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showChapters(LegadoSource s, LegadoPipeline.BookInfo info,
                              List<LegadoPipeline.TocChapter> chapters) {
        String[] names = new String[chapters.size()];
        for (int i = 0; i < names.length; i++) names[i] = chapters.get(i).name;
        new AlertDialog.Builder(this).setTitle(info.name + "（" + names.length + " 章）")
                .setItems(names, (d, which) -> {
                    ReadingSession session = LegadoReading.buildSession(s, info, chapters, which);
                    ReadingRouter.openReading(this, session);
                })
                .show();
    }

    // ================= 列表 =================

    private class SrcAdapter extends RecyclerView.Adapter<SrcAdapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView sub;
            final CheckBox enabled;
            final TextView del;

            Holder(View v, TextView name, TextView sub, CheckBox enabled, TextView del) {
                super(v);
                this.name = name;
                this.sub = sub;
                this.enabled = enabled;
                this.del = del;
            }
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackgroundColor(Color.WHITE);

            LinearLayout info = new LinearLayout(parent.getContext());
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView name = new TextView(parent.getContext());
            name.setTextSize(16);
            name.setTextColor(0xFF222222);
            info.addView(name);

            TextView sub = new TextView(parent.getContext());
            sub.setTextSize(12);
            sub.setTextColor(0xFF999999);
            info.addView(sub);
            row.addView(info);

            CheckBox enabled = new CheckBox(parent.getContext());
            row.addView(enabled);

            TextView del = new TextView(parent.getContext());
            del.setText("删除");
            del.setTextSize(14);
            del.setTextColor(0xFFCC3333);
            del.setPadding(dp(12), dp(8), dp(4), dp(8));
            row.addView(del);

            return new Holder(row, name, sub, enabled, del);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            LegadoStore.Entry e = entries.get(position);
            LegadoSource s = parsed.get(position);
            h.name.setText(s.tag());
            String typeLabel = s.typeLabel();
            if (!LegadoSource.runnable(s.bookSourceType)) typeLabel += "·暂不支持";
            h.sub.setText(typeLabel + (s.bookSourceGroup.isEmpty() ? "" : " · " + s.bookSourceGroup));
            h.enabled.setOnCheckedChangeListener(null);
            h.enabled.setChecked(e.enabled);
            h.enabled.setOnCheckedChangeListener((v, checked) -> {
                LegadoStore.setEnabled(s.bookSourceUrl, checked);
                e.enabled = checked;
            });
            h.del.setOnClickListener(v -> {
                new AlertDialog.Builder(LegadoActivity.this)
                        .setTitle("删除书源「" + s.tag() + "」？")
                        .setPositiveButton("删除", (d, w) -> {
                            LegadoStore.delete(s.bookSourceUrl);
                            reload();
                        })
                        .setNegativeButton("取消", null).show();
            });
            h.itemView.setOnClickListener(v -> onSourceTap(s));
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }

    private static String msg(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        return m.length() > 120 ? m.substring(0, 120) : m;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
