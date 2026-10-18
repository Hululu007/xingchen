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
        root.setBackgroundColor(0xFFF5F3EF);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(12);
        topBar.setPadding(pad, dp(28), pad, pad);
        topBar.setBackgroundColor(Color.WHITE);

        ImageButton back = new ImageButton(this);
        back.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setColorFilter(0xFF333333);
        back.setOnClickListener(v -> finish());
        topBar.addView(back);

        TextView title = new TextView(this);
        title.setText("书架");
        title.setTextSize(18);
        title.setTextColor(0xFF222222);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = dp(8);
        title.setLayoutParams(tlp);
        topBar.addView(title);

        TextView srcBtn = new TextView(this);
        srcBtn.setText("书源");
        srcBtn.setTextSize(15);
        srcBtn.setTextColor(0xFFB8860B);
        srcBtn.setPadding(dp(12), dp(8), dp(4), dp(8));
        srcBtn.setOnClickListener(v -> LegadoActivity.start(ReadingShelfActivity.this));
        topBar.addView(srcBtn);

        importBtn = new TextView(this);
        importBtn.setText("导入");
        importBtn.setTextSize(15);
        importBtn.setTextColor(0xFFB8860B);
        importBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        importBtn.setOnClickListener(v -> showImportMenu());
        topBar.addView(importBtn);
        root.addView(topBar);

        // 小说/漫画分栏
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(Color.WHITE);
        tabs.setPadding(0, 0, 0, dp(4));
        tabNovel = makeTab("小说", "NOVEL");
        tabComic = makeTab("漫画", "COMIC");
        tabs.addView(tabNovel);
        tabs.addView(tabComic);
        root.addView(tabs);
        refreshTabs();

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
        tvEmpty.setTextColor(0xFF999999);
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setLineSpacing(dp(6), 1f);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        tvEmpty.setLayoutParams(elp);
        tvEmpty.setGravity(Gravity.CENTER);
        content.addView(tvEmpty);
        root.addView(content);

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
        adapter.notifyDataSetChanged();
    }

    private TextView makeTab(String text, String tab) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(24), dp(10), dp(24), dp(10));
        tv.setOnClickListener(v -> {
            currentTab = tab;
            refreshTabs();
            refresh();
        });
        return tv;
    }

    private void refreshTabs() {
        boolean novel = "NOVEL".equals(currentTab);
        tabNovel.setTextColor(novel ? 0xFFB8860B : 0xFF999999);
        tabNovel.getPaint().setFakeBoldText(novel);
        tabComic.setTextColor(novel ? 0xFF999999 : 0xFFB8860B);
        tabComic.getPaint().setFakeBoldText(!novel);
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
        return (int) (v * getResources().getDisplayMetrics().density);
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
            final TextView tvTitle;
            final TextView tvSub;
            final TextView tvProgress;

            Holder(View itemView, ImageView cover, TextView tvTitle, TextView tvSub, TextView tvProgress) {
                super(itemView);
                this.cover = cover;
                this.tvTitle = tvTitle;
                this.tvSub = tvSub;
                this.tvProgress = tvProgress;
            }
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout item = new LinearLayout(parent.getContext());
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            int pad = dp(12);
            item.setPadding(pad, pad, pad, pad);
            item.setBackgroundColor(Color.WHITE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            item.setLayoutParams(lp);

            ImageView cover = new ImageView(parent.getContext());
            int cw = dp(56);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cw, (int) (cw * 1.3));
            cover.setLayoutParams(clp);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackgroundColor(0xFFE8E2D9);
            item.addView(cover);

            LinearLayout mid = new LinearLayout(parent.getContext());
            mid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            mlp.leftMargin = dp(12);
            mid.setLayoutParams(mlp);

            TextView tvTitle = new TextView(parent.getContext());
            tvTitle.setTextSize(16);
            tvTitle.setTextColor(0xFF222222);
            tvTitle.setSingleLine(true);
            tvTitle.setEllipsize(TextUtils.TruncateAt.END);
            mid.addView(tvTitle);

            TextView tvSub = new TextView(parent.getContext());
            tvSub.setTextSize(13);
            tvSub.setTextColor(0xFF888888);
            tvSub.setSingleLine(true);
            tvSub.setEllipsize(TextUtils.TruncateAt.END);
            tvSub.setPadding(0, dp(4), 0, 0);
            mid.addView(tvSub);

            TextView tvProgress = new TextView(parent.getContext());
            tvProgress.setTextSize(13);
            tvProgress.setTextColor(0xFFB8860B);
            tvProgress.setPadding(0, dp(4), 0, 0);
            mid.addView(tvProgress);

            item.addView(mid);
            return new Holder(item, cover, tvTitle, tvSub, tvProgress);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            ReadingStore.Record r = records.get(position);
            h.tvTitle.setText(r.bookTitle);
            String time = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(r.updatedAt));
            h.tvSub.setText(r.sourceName + " · " + r.chapterName + " · " + time);
            if ("NOVEL".equals(r.kind)) {
                h.tvProgress.setText("已读 " + Math.round(r.position / 100f) + "%");
            } else if ("AUDIO".equals(r.kind)) {
                h.tvProgress.setText(r.position > 0 ? "已听 " + fmtMs(r.position) : "开始听");
            } else {
                h.tvProgress.setText(r.position > 0 ? "看到第 " + (r.position + 1) + " 张" : "开始看");
            }
            if (!TextUtils.isEmpty(r.coverUrl)) {
                ImgUtil.load(r.bookTitle, r.coverUrl, h.cover, true);
            } else {
                h.cover.setImageDrawable(null);
            }
            h.itemView.setOnClickListener(v -> {
                if ("AUDIO".equals(r.kind)) openAudioRecord(r);
                else ReadingRouter.openRecord(ReadingShelfActivity.this, r);
            });
            h.itemView.setOnLongClickListener(v -> {
                ReadingStore.remove(r.kind, r.sourceName, r.bookTitle);
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
