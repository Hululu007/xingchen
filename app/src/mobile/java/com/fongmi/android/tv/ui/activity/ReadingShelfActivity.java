package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.legado.LegadoAudio;
import com.fongmi.android.tv.read.ReadingRouter;
import com.fongmi.android.tv.read.ReadingStore;
import com.fongmi.android.tv.utils.ImgUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 书架：在看的书＋进度＋继续读。全新设计，干净列表。
 * 点条目继续读（按记录重建会话）；长按移除。
 */
public class ReadingShelfActivity extends AppCompatActivity {

    public static void start(Context context) {
        context.startActivity(new Intent(context, ReadingShelfActivity.class));
    }

    private final List<ReadingStore.Record> records = new ArrayList<>();
    private ShelfAdapter adapter;
    private TextView tvEmpty;
    /** 当前栏："NOVEL"/"COMIC"，小说漫画分开归栏。 */
    private String currentTab = "NOVEL";
    private TextView tabNovel;
    private TextView tabComic;
    private TextView importBtn;
    private TextView secTitle;
    private TextView comicNote;

    private final ActivityResultLauncher<android.content.Intent> filePicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                List<android.net.Uri> uris = new ArrayList<>();
                if (result.getData().getClipData() != null) {
                    int n = result.getData().getClipData().getItemCount();
                    for (int i = 0; i < n; i++) {
                        uris.add(result.getData().getClipData().getItemAt(i).getUri());
                    }
                } else if (result.getData().getData() != null) {
                    uris.add(result.getData().getData());
                }
                importFiles(uris);
            });

    private final ActivityResultLauncher<android.content.Intent> treePicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null
                        || result.getData().getData() == null) return;
                importTree(result.getData().getData());
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        // AI 口令"继续读"中转：直接进最近一本，不在书架停留
        if (getIntent().getBooleanExtra("auto_continue", false)) {
            ReadingRouter.continueReading(this);
            finish();
            return;
        }
        buildUi();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(ReadingSkin.pageBackground(this));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(12);
        topBar.setPadding(pad, dp(16), pad, pad);
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
        title.setText("阅读");
        title.setTextSize(22);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(ReadingSkin.TEXT);
        titles.addView(title);
        TextView sub = new TextView(this);
        sub.setText("小说 · 漫画，各看各的");
        sub.setTextSize(12);
        sub.setTextColor(ReadingSkin.SUB);
        sub.setPadding(0, dp(2), 0, 0);
        titles.addView(sub);
        topBar.addView(titles);

        TextView srcBtn = new TextView(this);
        srcBtn.setText("书源");
        srcBtn.setTextSize(14);
        srcBtn.setTextColor(ReadingSkin.GOLD);
        srcBtn.setPadding(dp(12), dp(8), dp(4), dp(8));
        srcBtn.setOnClickListener(v -> LegadoActivity.start(ReadingShelfActivity.this));
        topBar.addView(srcBtn);

        importBtn = new TextView(this);
        importBtn.setText("导入");
        importBtn.setTextSize(14);
        importBtn.setTextColor(ReadingSkin.GOLD);
        importBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        importBtn.setOnClickListener(v -> showImportMenu());
        topBar.addView(importBtn);
        root.addView(topBar);

        // 小说/漫画分栏：胶囊切换（选中金字＋金描边）
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackground(ReadingSkin.segContainer(this));
        LinearLayout.LayoutParams tabslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tabslp.leftMargin = dp(20);
        tabslp.rightMargin = dp(20);
        tabslp.topMargin = dp(12);
        tabs.setLayoutParams(tabslp);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabNovel = makeTab("小 说", "NOVEL");
        tabComic = makeTab("漫 画", "COMIC");
        tabs.addView(tabNovel);
        tabs.addView(tabComic);
        root.addView(tabs);
        refreshTabs();

        root.addView(ReadingSkin.sectionHeader(this, "在看的小说", "长按移除",
                null));
        secTitle = (TextView) ((LinearLayout) root.getChildAt(
                root.getChildCount() - 1)).getChildAt(0);

        FrameLayout content = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        content.setLayoutParams(clp);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ShelfAdapter();
        rv.setAdapter(adapter);
        content.addView(rv);

        tvEmpty = new TextView(this);
        tvEmpty.setText("还没有在看的书\n去看一本漫画或小说，它会出现在这里");
        tvEmpty.setTextSize(15);
        tvEmpty.setTextColor(ReadingSkin.FAINT);
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setLineSpacing(dp(6), 1f);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        tvEmpty.setLayoutParams(elp);
        content.addView(tvEmpty);
        root.addView(content);

        TextView note = new TextView(this);
        note.setText("漫画栏只收漫画：源、书架、添加入口都在这边，"
                + "小说那边的东西不会串过来。");
        note.setTextSize(12);
        note.setTextColor(ReadingSkin.FAINT);
        note.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = dp(20);
        nlp.rightMargin = dp(20);
        nlp.topMargin = dp(8);
        nlp.bottomMargin = dp(16);
        note.setLayoutParams(nlp);
        note.setTag("comic_note");
        note.setVisibility(View.GONE);
        root.addView(note);
        this.comicNote = note;

        setContentView(root);
    }

    private void refresh() {
        records.clear();
        for (ReadingStore.Record r : ReadingStore.list()) {
            // 听书（AUDIO）归小说栏：小说栏分"看"和"听"（用户结构定案）
            if (currentTab.equals(r.kind) || ("NOVEL".equals(currentTab) && "AUDIO".equals(r.kind))) {
                records.add(r);
            }
        }
        tvEmpty.setText("NOVEL".equals(currentTab)
                ? "还没有在看的小说\n导入一本 txt / epub，或去看一本在线小说"
                : "还没有在看的漫画\n导入一个图包 / 图片文件夹，或去看一本在线漫画");
        tvEmpty.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
        if (secTitle != null) {
            secTitle.setText("NOVEL".equals(currentTab) ? "在看的小说" : "在看的漫画");
        }
        if (comicNote != null) {
            comicNote.setVisibility("COMIC".equals(currentTab)
                    ? View.VISIBLE : View.GONE);
        }
        adapter.notifyDataSetChanged();
        checkUpdates();
    }

    /**
     * 连载更新检查：低频（每书 6 小时节流）、失败静默，有新章给角标。
     * 只查书源的书（本地书/听书不查）。
     */
    private void checkUpdates() {
        java.util.List<ReadingStore.Record> cands = new java.util.ArrayList<>();
        for (ReadingStore.Record r : records) {
            if (!android.text.TextUtils.isEmpty(r.legadoKey)
                    && !android.text.TextUtils.isEmpty(r.bookUrl)) {
                cands.add(r);
            }
        }
        if (cands.isEmpty()) return;
        android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        com.fongmi.android.tv.read.ReadingUpdateChecker.check(cands, ui, changed -> {
            if (changed) refresh();
        });
    }

    private TextView makeTab(String text, String tab) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.getPaint().setFakeBoldText(true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(24), dp(12), dp(24), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(v -> {
            currentTab = tab;
            refreshTabs();
            refresh();
        });
        return tv;
    }

    private void refreshTabs() {
        boolean novel = "NOVEL".equals(currentTab);
        styleTab(tabNovel, novel);
        styleTab(tabComic, !novel);
    }

    /** 胶囊切换：选中金字＋金描边深色胶囊（稿语言）。 */
    private void styleTab(TextView tv, boolean selected) {
        if (selected) {
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable
                                    .Orientation.TOP_BOTTOM,
                            new int[]{0x4DF2B33D, 0x29F2B33D});
            bg.setCornerRadius(dp(999));
            bg.setStroke(dp(2), 0x8CF2B33D);
            tv.setBackground(bg);
            tv.setTextColor(ReadingSkin.GOLD);
        } else {
            tv.setBackgroundColor(Color.TRANSPARENT);
            tv.setTextColor(ReadingSkin.SUB);
        }
    }

    private void showImportMenu() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("导入本地书")
                .setItems(new String[]{"导入文件（txt / epub / zip / cbz）", "导入图片文件夹"},
                        (d, which) -> {
                            if (which == 0) pickFiles();
                            else pickTree();
                        })
                .show();
    }

    private void pickFiles() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        it.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/plain", "application/epub+zip", "application/zip",
                "application/x-cbz", "application/x-zip-compressed"});
        it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            filePicker.launch(it);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickTree() {
        try {
            treePicker.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE));
        } catch (Throwable t) {
            Toast.makeText(this, "打不开文件夹选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void setImporting(boolean on) {
        importBtn.setEnabled(!on);
        importBtn.setText(on ? "导入中…" : "导入");
    }

    private void importFiles(List<android.net.Uri> uris) {
        if (uris.isEmpty()) return;
        setImporting(true);
        new Thread(() -> {
            int ok = 0;
            String err = "";
            for (android.net.Uri u : uris) {
                try {
                    LocalBookImporter.importUri(getApplicationContext(), u);
                    ok++;
                } catch (Exception e) {
                    err = e.getMessage();
                }
            }
            final int fOk = ok;
            final String fErr = err;
            runOnUiThread(() -> {
                setImporting(false);
                if (fOk > 0) {
                    Toast.makeText(ReadingShelfActivity.this,
                            "导入成功 " + fOk + " 本" + (fErr.isEmpty() ? "" : "；失败：" + fErr),
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(ReadingShelfActivity.this, "导入失败：" + fErr,
                            Toast.LENGTH_LONG).show();
                }
                refresh();
            });
        }).start();
    }

    private void importTree(android.net.Uri treeUri) {
        setImporting(true);
        new Thread(() -> {
            String err = "";
            int ok = 0;
            try {
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            try {
                LocalBookImporter.importTree(getApplicationContext(), treeUri);
                ok = 1;
            } catch (Exception e) {
                err = e.getMessage();
            }
            final int fOk = ok;
            final String fErr = err;
            runOnUiThread(() -> {
                setImporting(false);
                Toast.makeText(ReadingShelfActivity.this,
                        fOk > 0 ? "导入成功" : "导入失败：" + fErr, Toast.LENGTH_LONG).show();
                refresh();
            });
        }).start();
    }

    private int dp(int v) {
        return ReadingSkin.dp(this, v);
    }

    /**
     * 听书续播：按记录重建单集专辑（与阅读续读同口径：章节表缺失时单章模式）。
     * AudioBookActivity 进页后按 AUDIO 记录自动恢复位置。
     */
    private void openAudioRecord(ReadingStore.Record r) {
        try {
            LegadoAudio.AudioAlbum a = new LegadoAudio.AudioAlbum();
            a.legadoKey = r.legadoKey == null ? "" : r.legadoKey;
            a.sourceName = r.sourceName == null ? "" : r.sourceName;
            a.title = r.bookTitle == null ? "" : r.bookTitle;
            a.coverUrl = r.coverUrl == null ? "" : r.coverUrl;
            a.chapters.add(new LegadoAudio.Chapter(
                    r.chapterName == null ? "" : r.chapterName,
                    r.chapterUrl == null ? "" : r.chapterUrl));
            a.index = 0;
            AudioBookActivity.start(this, LegadoAudio.put(a));
        } catch (Throwable t) {
            Toast.makeText(this, "打开失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String fmtMs(int ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private class ShelfAdapter extends RecyclerView.Adapter<ShelfAdapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView coverTitle;
            final TextView tvTitle;
            final TextView tvSub;
            final TextView tvProgress;
            final android.widget.ProgressBar bar;
            final TextView btnGo;

            Holder(View itemView, ImageView cover, TextView coverTitle,
                   TextView tvTitle, TextView tvSub, TextView tvProgress,
                   android.widget.ProgressBar bar, TextView btnGo) {
                super(itemView);
                this.cover = cover;
                this.coverTitle = coverTitle;
                this.tvTitle = tvTitle;
                this.tvSub = tvSub;
                this.tvProgress = tvProgress;
                this.bar = bar;
                this.btnGo = btnGo;
            }
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            android.content.Context c = parent.getContext();
            LinearLayout item = new LinearLayout(c);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            int pad = dp(14);
            item.setPadding(pad, pad, pad, pad);
            item.setBackground(ReadingSkin.glassCard(c, 16));
            try {
                item.setElevation(dp(4));
            } catch (Throwable ignored) {
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(16);
            lp.rightMargin = dp(16);
            lp.bottomMargin = dp(12);
            item.setLayoutParams(lp);

            // 封面：渐变占位＋白字书名（无图时）
            FrameLayout coverWrap = new FrameLayout(c);
            int cw = dp(64);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cw,
                    (int) (cw * 1.32));
            coverWrap.setLayoutParams(clp);
            ImageView cover = new ImageView(c);
            cover.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF2C2118, 0xFF6B4A2E, 0xFFC99A5E}));
            ReadingSkin.roundClip(cover, c, 8);
            coverWrap.addView(cover);
            TextView coverTitle = new TextView(c);
            coverTitle.setTextSize(13);
            coverTitle.getPaint().setFakeBoldText(true);
            coverTitle.setTextColor(0xFFFFFFFF);
            coverTitle.setGravity(Gravity.CENTER);
            coverTitle.setPadding(dp(6), 0, dp(6), 0);
            coverTitle.setMaxLines(3);
            coverWrap.addView(coverTitle, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            item.addView(coverWrap);

            LinearLayout mid = new LinearLayout(c);
            mid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            mlp.leftMargin = dp(14);
            mid.setLayoutParams(mlp);

            TextView tvTitle = new TextView(c);
            tvTitle.setTextSize(16);
            tvTitle.getPaint().setFakeBoldText(true);
            tvTitle.setTextColor(ReadingSkin.TEXT);
            tvTitle.setSingleLine(true);
            tvTitle.setEllipsize(TextUtils.TruncateAt.END);
            mid.addView(tvTitle);

            TextView tvSub = new TextView(c);
            tvSub.setTextSize(13);
            tvSub.setTextColor(ReadingSkin.SUB);
            tvSub.setSingleLine(true);
            tvSub.setEllipsize(TextUtils.TruncateAt.END);
            tvSub.setPadding(0, dp(6), 0, 0);
            mid.addView(tvSub);

            android.widget.ProgressBar bar =
                    new android.widget.ProgressBar(c, null,
                            android.R.attr.progressBarStyleHorizontal);
            bar.setProgressDrawable(ReadingSkin.goldProgressBar(c));
            bar.setMax(100);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
            blp.topMargin = dp(10);
            mid.addView(bar, blp);

            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, 0);
            TextView tvProgress = new TextView(c);
            tvProgress.setTextSize(12);
            tvProgress.setTextColor(ReadingSkin.SUB);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tvProgress.setLayoutParams(plp);
            row.addView(tvProgress);
            TextView btnGo = new TextView(c);
            btnGo.setText("继续读");
            btnGo.setTextSize(13);
            btnGo.getPaint().setFakeBoldText(true);
            btnGo.setTextColor(0xFF1A1408);
            btnGo.setBackground(ReadingSkin.goldCapsule(c));
            btnGo.setPadding(dp(16), dp(8), dp(16), dp(8));
            row.addView(btnGo);
            mid.addView(row);

            item.addView(mid);
            return new Holder(item, cover, coverTitle, tvTitle, tvSub,
                    tvProgress, bar, btnGo);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            ReadingStore.Record r = records.get(position);
            h.tvTitle.setText(r.bookTitle);
            String time = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(r.updatedAt));
            h.tvSub.setText(r.sourceName + " · " + r.chapterName + " · " + time);
            boolean isAudio = "AUDIO".equals(r.kind);
            boolean isNovel = "NOVEL".equals(r.kind);
            if (isNovel) {
                int pct = Math.round(r.position / 100f);
                h.tvProgress.setText("已读 " + pct + "%"
                        + (r.hasUpdate ? " · 有更新" : ""));
                h.bar.setVisibility(View.VISIBLE);
                h.bar.setProgress(Math.max(0, Math.min(100, pct)));
            } else if (isAudio) {
                h.tvProgress.setText(r.position > 0 ? "已听 " + fmtMs(r.position) : "开始听");
                h.bar.setVisibility(View.GONE);
            } else {
                h.tvProgress.setText((r.position > 0 ? "看到第 " + (r.position + 1) + " 张" : "开始看")
                        + (r.hasUpdate ? " · 有更新" : ""));
                h.bar.setVisibility(View.GONE);
            }
            h.btnGo.setText(isAudio ? "继续听" : "继续读");
            if (!TextUtils.isEmpty(r.coverUrl)) {
                ImgUtil.load(r.bookTitle, r.coverUrl, h.cover, true);
                h.coverTitle.setVisibility(View.GONE);
            } else {
                h.cover.setImageDrawable(null);
                h.coverTitle.setVisibility(View.VISIBLE);
                h.coverTitle.setText(r.bookTitle);
            }
            View.OnClickListener open = v -> {
                com.fongmi.android.tv.read.ReadingUpdateChecker.clearUpdate(r);
                if (isAudio) openAudioRecord(r);
                else ReadingRouter.openRecord(ReadingShelfActivity.this, r);
            };
            h.itemView.setOnClickListener(open);
            h.btnGo.setOnClickListener(open);
            h.itemView.setOnLongClickListener(v -> {
                ReadingStore.remove(r.kind, r.sourceName, r.bookTitle);
                com.fongmi.android.tv.read.ReadingBookmarkStore.removeBook(r.key());
                if ("local".equals(r.origin) && !TextUtils.isEmpty(r.localId)) {
                    // 本地书：连书文件一起删，不在私有目录留垃圾
                    com.fongmi.android.tv.localbook.LocalBookStore.remove(r.localId);
                    Toast.makeText(ReadingShelfActivity.this, "已删除本地书",
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ReadingShelfActivity.this, "已从书架移除",
                            Toast.LENGTH_SHORT).show();
                }
                refresh();
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return records.size();
        }
    }
}
