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
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.legado.LegadoAudio;
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
    private TextView countTitle;

    public static void start(android.content.Context context) {
        context.startActivity(new Intent(context, LegadoActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ReadingSkin.fitWindowBackground(this, ReadingSkin.PAGE_BG_TOP, true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(ReadingSkin.pageBackground(this));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(16), dp(12), dp(12));
        ReadingSkin.applyTopInset(topBar, this, 16);

        ImageButton back = new ImageButton(this);
        back.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setColorFilter(ReadingSkin.SUB);
        back.setOnClickListener(v -> finish());
        topBar.addView(back);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = dp(8);
        titles.setLayoutParams(tlp);
        TextView title = new TextView(this);
        title.setText("小说源");
        title.setTextSize(20);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(ReadingSkin.TEXT);
        titles.addView(title);
        TextView sub = new TextView(this);
        sub.setText("源文件存在本地，自己加自己管");
        sub.setTextSize(12);
        sub.setTextColor(ReadingSkin.SUB);
        sub.setPadding(0, dp(2), 0, 0);
        titles.addView(sub);
        topBar.addView(titles);

        TextView importBtn = new TextView(this);
        importBtn.setText("＋ 导入");
        importBtn.setTextSize(14);
        importBtn.setTextColor(ReadingSkin.GOLD);
        importBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        importBtn.setOnClickListener(v -> showImportDialog());
        topBar.addView(importBtn);
        root.addView(topBar);

        // 添加大卡：两个并排入口（走原有导入逻辑）
        LinearLayout addBox = new LinearLayout(this);
        addBox.setOrientation(LinearLayout.VERTICAL);
        addBox.setBackground(ReadingSkin.glassCard(this, 16));
        try {
            addBox.setElevation(dp(4));
        } catch (Throwable ignored) {
        }
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(16);
        alp.rightMargin = dp(16);
        alp.topMargin = dp(12);
        addBox.setLayoutParams(alp);
        addBox.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView addTitle = new TextView(this);
        addTitle.setText("＋ 添加小说源");
        addTitle.setTextSize(17);
        addTitle.getPaint().setFakeBoldText(true);
        addTitle.setTextColor(ReadingSkin.GOLD);
        addTitle.setGravity(Gravity.CENTER);
        addTitle.setOnClickListener(v -> showImportDialog());
        addBox.addView(addTitle);
        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setPadding(0, dp(12), 0, 0);
        addRow.addView(addButton("📁 导入文件", "选手机里的源文件",
                v -> pickFile()));
        addRow.addView(addButton("🔗 贴链接导入", "粘贴源地址导入",
                v -> showUrlDialog()));
        addBox.addView(addRow);
        root.addView(addBox);

        countTitle = new TextView(this);
        LinearLayout secRow = new LinearLayout(this);
        secRow.setOrientation(LinearLayout.HORIZONTAL);
        secRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(20);
        slp.rightMargin = dp(20);
        slp.topMargin = dp(20);
        slp.bottomMargin = dp(10);
        secRow.setLayoutParams(slp);
        countTitle.setTextSize(17);
        countTitle.getPaint().setFakeBoldText(true);
        countTitle.setTextColor(ReadingSkin.TEXT);
        LinearLayout.LayoutParams ctlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        countTitle.setLayoutParams(ctlp);
        secRow.addView(countTitle);
        root.addView(secRow);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SrcAdapter();
        rv.setAdapter(adapter);
        root.addView(rv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        root.addView(ReadingSkin.footnote(this,
                "漫画源在漫画栏那边单独管，两边不串。"
                        + "源坏了只坏它自己，别的源照常看。"));

        setContentView(root);
        reload();
        // AI 口令「导入书源」直达：打开即弹导入框
        if (getIntent().getBooleanExtra("auto_import", false)) showImportDialog();
    }

    /** 添加入口小按钮：金描边暗底（走原有逻辑）。 */
    private LinearLayout addButton(String a, String b,
                                   View.OnClickListener onClick) {
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.VERTICAL);
        btn.setGravity(Gravity.CENTER_HORIZONTAL);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x1FF2B33D);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(2), 0x80F2B33D);
        btn.setBackground(bg);
        btn.setPadding(dp(8), dp(14), dp(8), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(onClick);
        TextView ta = new TextView(this);
        ta.setText(a);
        ta.setTextSize(15);
        ta.getPaint().setFakeBoldText(true);
        ta.setTextColor(ReadingSkin.TEXT);
        ta.setGravity(Gravity.CENTER);
        btn.addView(ta);
        TextView tb = new TextView(this);
        tb.setText(b);
        tb.setTextSize(11);
        tb.setTextColor(ReadingSkin.SUB);
        tb.setGravity(Gravity.CENTER);
        tb.setPadding(0, dp(6), 0, 0);
        btn.addView(tb);
        return btn;
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
        if (countTitle != null) {
            countTitle.setText("已装 " + entries.size() + " 个");
        }
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
            if (uri == null) {
                Toast.makeText(this, "没取到文件", Toast.LENGTH_SHORT).show();
                return;
            }
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
                    Toast.makeText(this, name.startsWith("已导入") ? name : "已导入：" + name,
                            Toast.LENGTH_SHORT).show();
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
        String unit = s.bookSourceType == LegadoSource.TYPE_AUDIO ? " 集" : " 章";
        new AlertDialog.Builder(this).setTitle(info.name + "（" + names.length + unit + "）")
                .setItems(names, (d, which) -> {
                    if (s.bookSourceType == LegadoSource.TYPE_AUDIO) {
                        // 听书：专辑进听书播放页
                        LegadoAudio.AudioAlbum album =
                                LegadoAudio.buildAlbum(s, info, chapters, which);
                        AudioBookActivity.start(this, LegadoAudio.put(album));
                    } else {
                        ReadingSession session = LegadoReading.buildSession(s, info, chapters, which);
                        ReadingRouter.openReading(this, session);
                    }
                })
                .show();
    }

    // ================= 列表 =================

    private class SrcAdapter extends RecyclerView.Adapter<SrcAdapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            final View dot;
            final TextView name;
            final TextView sub;
            final TextView pill;
            final android.widget.Switch enabled;
            final TextView del;

            Holder(View v, View dot, TextView name, TextView sub,
                   TextView pill, android.widget.Switch enabled,
                   TextView del) {
                super(v);
                this.dot = dot;
                this.name = name;
                this.sub = sub;
                this.pill = pill;
                this.enabled = enabled;
                this.del = del;
            }
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            android.content.Context c = parent.getContext();
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setBackground(ReadingSkin.glassCard(c, 16));
            try {
                row.setElevation(dp(4));
            } catch (Throwable ignored) {
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.leftMargin = dp(16);
            rlp.rightMargin = dp(16);
            rlp.bottomMargin = dp(10);
            row.setLayoutParams(rlp);

            // 状态灯：开绿 / 关灰（稿里的黄灯需延迟数据，代码没有，未加）
            View dot = new View(c);
            int ds = dp(10);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ds, ds);
            dlp.rightMargin = dp(12);
            dot.setLayoutParams(dlp);
            row.addView(dot);

            LinearLayout info = new LinearLayout(c);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView name = new TextView(c);
            name.setTextSize(15);
            name.getPaint().setFakeBoldText(true);
            name.setTextColor(ReadingSkin.TEXT);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(name);

            TextView sub = new TextView(c);
            sub.setTextSize(12);
            sub.setTextColor(ReadingSkin.SUB);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setPadding(0, dp(4), 0, 0);
            info.addView(sub);
            row.addView(info);

            TextView pill = new TextView(c);
            pill.setTextSize(11);
            pill.setPadding(dp(8), dp(5), dp(8), dp(5));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.rightMargin = dp(8);
            pill.setLayoutParams(plp);
            row.addView(pill);

            android.widget.Switch enabled = new android.widget.Switch(c);
            ReadingSkin.styleSwitch(enabled);
            row.addView(enabled);

            TextView del = new TextView(c);
            del.setText("删除");
            del.setTextSize(13);
            del.setTextColor(0xFFCC7777);
            del.setPadding(dp(10), dp(8), dp(2), dp(8));
            row.addView(del);

            return new Holder(row, dot, name, sub, pill, enabled, del);
        }

        /** 状态灯底：绿/灰。 */
        private android.graphics.drawable.Drawable dotBg(boolean on) {
            android.graphics.drawable.GradientDrawable d =
                    new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            d.setColor(on ? 0xFF35B558 : 0xFF4A4A55);
            return d;
        }

        /** 类型胶囊：小说蓝 / 听书金 / 漫画粉 / 文件灰（稿语言）。 */
        private void stylePill(TextView pill, int type) {
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(8));
            int tc, bc;
            switch (type) {
                case LegadoSource.TYPE_AUDIO:
                    tc = ReadingSkin.GOLD;
                    bc = 0x66F2B33D;
                    bg.setColor(0x1AF2B33D);
                    break;
                case LegadoSource.TYPE_IMAGE:
                    tc = 0xFFFFC4DA;
                    bc = 0x52FF8CB4;
                    bg.setColor(0x1AFF8CB4);
                    break;
                case LegadoSource.TYPE_FILE:
                    tc = 0xFFAAAAAA;
                    bc = 0x55AAAAAA;
                    bg.setColor(0x14FFFFFF);
                    break;
                default:
                    tc = 0xFF9FD8FF;
                    bc = 0x5978B4FF;
                    bg.setColor(0x1F78B4FF);
                    break;
            }
            bg.setStroke(1, bc);
            pill.setBackground(bg);
            pill.setTextColor(tc);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            LegadoStore.Entry e = entries.get(position);
            LegadoSource s = parsed.get(position);
            h.name.setText(s.tag());
            String typeLabel = s.typeLabel();
            if (!LegadoSource.runnable(s.bookSourceType)) typeLabel += "·暂不支持";
            h.sub.setText(typeLabel + (s.bookSourceGroup.isEmpty() ? "" : " · " + s.bookSourceGroup));
            h.dot.setBackground(dotBg(e.enabled));
            h.pill.setText(s.typeLabel());
            stylePill(h.pill, s.bookSourceType);
            h.enabled.setOnCheckedChangeListener(null);
            h.enabled.setChecked(e.enabled);
            h.enabled.setOnCheckedChangeListener((v, checked) -> {
                LegadoStore.setEnabled(s.bookSourceUrl, checked);
                e.enabled = checked;
                h.dot.setBackground(dotBg(checked));
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
        return ReadingSkin.dp(this, v);
    }
}
