package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.legado.LegadoAudio;
import com.fongmi.android.tv.legado.LegadoSource;
import com.fongmi.android.tv.legado.LegadoStore;
import com.fongmi.android.tv.localbook.LocalBookImporter;
import com.fongmi.android.tv.read.ReadingRouter;
import com.fongmi.android.tv.read.ReadingSession;
import com.fongmi.android.tv.read.ReadingStore;
import com.fongmi.android.tv.tts.EdgeVoice;
import com.fongmi.android.tv.tts.EdgeVoiceCatalog;
import com.fongmi.android.tv.tts.TtsJob;
import com.fongmi.android.tv.utils.ImgUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 书架首页（对稿 reading-ui-mockup-v2/home-novel、home-comic）：
 * 小说栏＝在看的小说卡片（封面/进度/继续读）＋听书分区＋喜马拉雅卡＋我的小说源聚合卡；
 * 漫画栏＝在看的漫画卡片（连载徽）＋我的漫画源聚合卡。各栏只说各栏的字。
 * 顶栏不设叉（用户规矩：所有页面不要叉，走系统返回）。
 */
public class ReadingShelfActivity extends AppCompatActivity {

    public static void start(Context context) {
        context.startActivity(new Intent(context, ReadingShelfActivity.class));
    }

    /** 当前栏："NOVEL"/"COMIC"，小说漫画分开归栏。 */
    private String currentTab = "NOVEL";
    private TextView tabNovel;
    private TextView tabComic;
    private TextView importBtn;
    private LinearLayout contentBox;

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

        // 顶栏：标题＋书源/导入；不设叉，走系统返回
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(12);
        topBar.setPadding(pad, dp(16), pad, pad);
        ReadingSkin.applyTopInset(topBar, this, 16);

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

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        sv.setLayoutParams(svlp);
        contentBox = new LinearLayout(this);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        contentBox.setPadding(0, 0, 0, dp(24));
        sv.addView(contentBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv);

        setContentView(root);
    }

    /** 整页按当前栏重建（书卡片＋分区＋源聚合卡）。 */
    private void refresh() {
        List<ReadingStore.Record> novels = new ArrayList<>();
        List<ReadingStore.Record> comics = new ArrayList<>();
        ReadingStore.Record latestAudio = null;
        for (ReadingStore.Record r : ReadingStore.list()) {
            if ("NOVEL".equals(r.kind)) novels.add(r);
            else if ("COMIC".equals(r.kind)) comics.add(r);
            else if ("AUDIO".equals(r.kind) || "TTS".equals(r.kind)) {
                if (latestAudio == null || r.updatedAt > latestAudio.updatedAt) latestAudio = r;
            }
        }
        boolean novel = "NOVEL".equals(currentTab);
        contentBox.removeAllViews();
        if (novel) {
            contentBox.addView(ReadingSkin.sectionHeader(this, "在看的小说", "长按移除", null));
            if (novels.isEmpty()) contentBox.addView(emptyView(
                    "还没有在看的小说\n导入一本 txt / epub，或去看一本在线小说"));
            else for (ReadingStore.Record r : novels) contentBox.addView(bookCard(r, false));
            if (latestAudio != null) {
                contentBox.addView(ReadingSkin.sectionHeader(this, "听书", "小说直接听", null));
                contentBox.addView(audioCard(latestAudio));
            }
            contentBox.addView(ximaCard());
            contentBox.addView(ReadingSkin.sectionHeader(this, "我的小说源", "管理", null));
            contentBox.addView(sourceCard(true));
        } else {
            contentBox.addView(ReadingSkin.sectionHeader(this, "在看的漫画", "长按移除", null));
            if (comics.isEmpty()) contentBox.addView(emptyView(
                    "还没有在看的漫画\n导入一个图包 / 图片文件夹，或去看一本在线漫画"));
            else for (ReadingStore.Record r : comics) contentBox.addView(bookCard(r, true));
            contentBox.addView(ReadingSkin.sectionHeader(this, "我的漫画源", "管理", null));
            contentBox.addView(sourceCard(false));
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
            note.setLayoutParams(nlp);
            contentBox.addView(note);
        }
        List<ReadingStore.Record> cands = new ArrayList<>(novels);
        cands.addAll(comics);
        checkUpdates(cands);
    }

    private TextView emptyView(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(ReadingSkin.FAINT);
        tv.setGravity(Gravity.CENTER);
        tv.setLineSpacing(dp(6), 1f);
        tv.setPadding(0, dp(48), 0, dp(48));
        return tv;
    }

    /** 书卡片：封面＋书名＋看到第 X 章/话·全 Y＋金色进度条＋百分比·人话时间＋继续读。 */
    private View bookCard(ReadingStore.Record r, boolean comic) {
        Context c = this;
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

        // 封面：渐变占位＋白字书名（无图时）；漫画加连载徽
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
        cover.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
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
        if (comic && r.totalChapters > 0) {
            TextView badge = new TextView(c);
            badge.setText(r.chapterIndex + 1 >= r.totalChapters ? "已完结" : "连载中");
            badge.setTextSize(10);
            badge.setTextColor(0xFFFFFFFF);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(6), dp(3), dp(6), dp(3));
            GradientDrawable bbg = new GradientDrawable();
            bbg.setColor(0x99000000);
            bbg.setCornerRadius(dp(6));
            badge.setBackground(bbg);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = dp(4);
            blp.topMargin = dp(4);
            blp.gravity = Gravity.START | Gravity.TOP;
            coverWrap.addView(badge, blp);
        }
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
        tvTitle.setText(r.bookTitle);
        mid.addView(tvTitle);

        String unit = comic ? "话" : "章";
        TextView tvSub = new TextView(c);
        tvSub.setTextSize(13);
        tvSub.setTextColor(ReadingSkin.SUB);
        tvSub.setSingleLine(true);
        tvSub.setEllipsize(TextUtils.TruncateAt.END);
        tvSub.setPadding(0, dp(6), 0, 0);
        tvSub.setText("看到第 " + (r.chapterIndex + 1) + " " + unit
                + (r.totalChapters > 0 ? " · 全 " + r.totalChapters + " " + unit : ""));
        mid.addView(tvSub);

        android.widget.ProgressBar bar =
                new android.widget.ProgressBar(c, null,
                        android.R.attr.progressBarStyleHorizontal);
        bar.setProgressDrawable(ReadingSkin.goldProgressBar(c));
        bar.setMax(100);
        int pct = progressPct(r);
        bar.setProgress(Math.max(0, Math.min(100, pct)));
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
        tvProgress.setText((pct >= 0 ? pct + "% · " : "")
                + humanTime(r.updatedAt) + (r.hasUpdate ? " · 有更新" : ""));
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

        if (!TextUtils.isEmpty(r.coverUrl)) {
            ImgUtil.load(r.bookTitle, r.coverUrl, cover, true);
            coverTitle.setVisibility(View.GONE);
        } else {
            cover.setImageDrawable(null);
            coverTitle.setVisibility(View.VISIBLE);
            coverTitle.setText(r.bookTitle);
        }
        View.OnClickListener open = v -> {
            com.fongmi.android.tv.read.ReadingUpdateChecker.clearUpdate(r);
            ReadingRouter.openRecord(ReadingShelfActivity.this, r);
        };
        item.setOnClickListener(open);
        btnGo.setOnClickListener(open);
        item.setOnLongClickListener(v -> {
            removeRecord(r);
            return true;
        });
        return item;
    }

    /** 进度百分比：优先按章节数算，缺总数时按千分比回退，缺数据时 -1。 */
    private int progressPct(ReadingStore.Record r) {
        if (r.totalChapters > 0) {
            return Math.round((r.chapterIndex + 1) * 100f / r.totalChapters);
        }
        if (r.position > 0) return Math.round(r.position / 100f);
        return -1;
    }

    /** 听书卡：圆盘＋书名·章节＋上次听到＋音色标签＋大金色播放钮。 */
    private View audioCard(ReadingStore.Record r) {
        Context c = this;
        boolean isTts = "TTS".equals(r.kind);
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(ReadingSkin.glassCard(c, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);

        // 圆盘：深色盘面＋金色盘心
        FrameLayout disc = new FrameLayout(c);
        int ds = dp(60);
        disc.setLayoutParams(new LinearLayout.LayoutParams(ds, ds));
        GradientDrawable discBg = new GradientDrawable();
        discBg.setShape(GradientDrawable.OVAL);
        discBg.setColor(0xFF2B2B34);
        discBg.setStroke(dp(1), 0xFF454550);
        disc.setBackground(discBg);
        View hub = new View(c);
        int hs = dp(17);
        GradientDrawable hubBg = new GradientDrawable();
        hubBg.setShape(GradientDrawable.OVAL);
        hubBg.setColor(ReadingSkin.GOLD);
        hub.setBackground(hubBg);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(hs, hs);
        hlp.gravity = Gravity.CENTER;
        disc.addView(hub, hlp);
        card.addView(disc);

        LinearLayout meta = new LinearLayout(c);
        meta.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        mlp.leftMargin = dp(14);
        mlp.rightMargin = dp(8);
        meta.setLayoutParams(mlp);
        TextView at = new TextView(c);
        at.setTextSize(17);
        at.getPaint().setFakeBoldText(true);
        at.setTextColor(ReadingSkin.TEXT);
        at.setSingleLine(true);
        at.setEllipsize(TextUtils.TruncateAt.END);
        at.setText(r.bookTitle + " · " + (TextUtils.isEmpty(r.chapterName)
                ? "" : r.chapterName));
        meta.addView(at);
        TextView ak = new TextView(c);
        ak.setTextSize(13);
        ak.setTextColor(ReadingSkin.SUB);
        ak.setPadding(0, dp(6), 0, 0);
        ak.setText(r.position > 0 ? "上次听到 " + fmtMs(r.position) : "还没开始听");
        meta.addView(ak);
        if (isTts) {
            TextView pill = new TextView(c);
            pill.setTextSize(12);
            pill.setTextColor(ReadingSkin.GOLD);
            pill.setPadding(dp(10), dp(4), dp(10), dp(4));
            GradientDrawable pbg = new GradientDrawable();
            pbg.setCornerRadius(dp(999));
            pbg.setStroke(dp(2), 0x80F2B33D);
            pbg.setColor(0x1AF2B33D);
            pill.setBackground(pbg);
            pill.setText(defaultVoiceLabel());
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.topMargin = dp(8);
            pill.setLayoutParams(plp);
            meta.addView(pill);
        }
        card.addView(meta);

        TextView play = new TextView(c);
        play.setText("▶");
        play.setTextSize(20);
        play.setTextColor(0xFF1A1408);
        play.setGravity(Gravity.CENTER);
        int ps = dp(52);
        GradientDrawable playBg = new GradientDrawable();
        playBg.setShape(GradientDrawable.OVAL);
        playBg.setColors(new int[]{0xFFF6C65E, 0xFFE8A93B});
        playBg.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        play.setBackground(playBg);
        play.setLayoutParams(new LinearLayout.LayoutParams(ps, ps));
        play.setOnClickListener(v -> {
            if (isTts) openTtsRecord(r);
            else openAudioRecord(r);
        });
        card.addView(play);
        card.setOnClickListener(v -> {
            if (isTts) openTtsRecord(r);
            else openAudioRecord(r);
        });
        return card;
    }

    /** 喜马拉雅卡：去听书源列表逛。 */
    private View ximaCard() {
        Context c = this;
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(ReadingSkin.glassCard(c, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);

        LinearLayout meta = new LinearLayout(c);
        meta.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        meta.setLayoutParams(mlp);
        TextView n = new TextView(c);
        n.setText("喜马拉雅");
        n.setTextSize(15);
        n.getPaint().setFakeBoldText(true);
        n.setTextColor(ReadingSkin.TEXT);
        meta.addView(n);
        TextView d = new TextView(c);
        d.setText("听书源 · 专辑 480 万＋");
        d.setTextSize(12);
        d.setTextColor(ReadingSkin.SUB);
        d.setPadding(0, dp(4), 0, 0);
        meta.addView(d);
        card.addView(meta);

        TextView go = new TextView(c);
        go.setText("去逛逛");
        go.setTextSize(12);
        go.getPaint().setFakeBoldText(true);
        go.setTextColor(0xFF1A1408);
        go.setBackground(ReadingSkin.goldCapsule(c));
        go.setPadding(dp(14), dp(7), dp(14), dp(7));
        card.addView(go);

        View.OnClickListener open = v -> LegadoActivity.start(ReadingShelfActivity.this);
        card.setOnClickListener(open);
        go.setOnClickListener(open);
        return card;
    }

    /** 书源聚合卡：书源仓库·N 个源，点进书源管理。 */
    private View sourceCard(boolean novelTab) {
        Context c = this;
        int[] counts = sourceCounts();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(ReadingSkin.glassCard(c, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);

        TextView icon = new TextView(c);
        icon.setText(novelTab ? "书" : "漫");
        icon.setTextSize(20);
        icon.getPaint().setFakeBoldText(true);
        icon.setTextColor(ReadingSkin.GOLD);
        icon.setGravity(Gravity.CENTER);
        int is = dp(44);
        GradientDrawable ibg = new GradientDrawable();
        ibg.setCornerRadius(dp(11));
        ibg.setColors(new int[]{0x4DF2B33D, 0x14F2B33D});
        ibg.setOrientation(GradientDrawable.Orientation.TL_BR);
        ibg.setStroke(dp(2), 0x66F2B33D);
        icon.setBackground(ibg);
        icon.setLayoutParams(new LinearLayout.LayoutParams(is, is));
        card.addView(icon);

        LinearLayout meta = new LinearLayout(c);
        meta.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        mlp.leftMargin = dp(12);
        meta.setLayoutParams(mlp);
        TextView a = new TextView(c);
        a.setTextSize(15);
        a.getPaint().setFakeBoldText(true);
        a.setTextColor(ReadingSkin.TEXT);
        int total = counts[0] + counts[1] + counts[2];
        a.setText((novelTab ? "书源仓库 · " : "漫画源 · ") + total + " 个源");
        meta.addView(a);
        TextView b = new TextView(c);
        b.setTextSize(12);
        b.setTextColor(ReadingSkin.SUB);
        b.setPadding(0, dp(4), 0, 0);
        String sub = novelTab
                ? ("文本源 " + counts[0] + " · 听书源 " + counts[1])
                : ("图片源 " + counts[2]);
        String sync = syncText();
        b.setText(sub + (TextUtils.isEmpty(sync) ? "" : " · " + sync));
        meta.addView(b);
        card.addView(meta);

        TextView arrow = new TextView(c);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(ReadingSkin.FAINT);
        card.addView(arrow);

        card.setOnClickListener(v -> LegadoActivity.start(ReadingShelfActivity.this));
        return card;
    }

    /** 书源计数：[文本, 听书, 图片]（只计启用的）。 */
    private int[] sourceCounts() {
        int[] r = new int[3];
        try {
            for (LegadoStore.Entry e : LegadoStore.all()) {
                if (!e.enabled) continue;
                LegadoSource s = LegadoStore.parse(e.json);
                if (s == null) continue;
                if (s.bookSourceType == LegadoSource.TYPE_TEXT) r[0]++;
                else if (s.bookSourceType == LegadoSource.TYPE_AUDIO) r[1]++;
                else if (s.bookSourceType == LegadoSource.TYPE_IMAGE) r[2]++;
            }
        } catch (Throwable ignored) {
        }
        return r;
    }

    /** 同步文案：按最近一次添加源的人话时间；无源时空。 */
    private String syncText() {
        try {
            long latest = 0;
            for (LegadoStore.Entry e : LegadoStore.all()) {
                if (e.enabled && e.addedAt > latest) latest = e.addedAt;
            }
            if (latest <= 0) return "";
            return humanTime(latest) + "同步";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 人话时间：刚刚 / X 分钟前 / X 小时前 / 昨天 HH:mm / X 天前 / MM-dd。 */
    private String humanTime(long ts) {
        if (ts <= 0) return "";
        long d = System.currentTimeMillis() - ts;
        long min = d / 60000;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long h = min / 60;
        if (h < 24) return h + " 小时前";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        Calendar now = Calendar.getInstance();
        Calendar y = (Calendar) now.clone();
        y.add(Calendar.DAY_OF_YEAR, -1);
        if (c.get(Calendar.YEAR) == y.get(Calendar.YEAR)
                && c.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)) {
            return "昨天 " + new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(new Date(ts));
        }
        long days = h / 24;
        if (days < 30) return days + " 天前";
        return new SimpleDateFormat("MM-dd", Locale.getDefault()).format(new Date(ts));
    }

    /** 默认朗读的音色标签（续播将用的音色）。 */
    private String defaultVoiceLabel() {
        try {
            String id = EdgeVoiceCatalog.defaultVoice();
            EdgeVoice v = EdgeVoiceCatalog.find(EdgeVoiceCatalog.builtIn(), id);
            if (v != null) return v.label();
        } catch (Throwable ignored) {
        }
        return "神经朗读";
    }

    /**
     * 连载更新检查：低频（每书 6 小时节流）、失败静默，有新章给角标。
     * 只查书源的书（本地书/听书不查）。
     */
    private void checkUpdates(List<ReadingStore.Record> cands) {
        java.util.List<ReadingStore.Record> list = new java.util.ArrayList<>();
        for (ReadingStore.Record r : cands) {
            if (!android.text.TextUtils.isEmpty(r.legadoKey)
                    && !android.text.TextUtils.isEmpty(r.bookUrl)) {
                list.add(r);
            }
        }
        if (list.isEmpty()) return;
        android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        com.fongmi.android.tv.read.ReadingUpdateChecker.check(list, ui, changed -> {
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
                .setItems(new String[]{"导入文件（txt / epub / pdf / mobi / azw3 / zip / cbz / cbr / cb7）", "导入图片文件夹"},
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
                "text/plain", "application/epub+zip", "application/pdf",
                "application/x-mobipocket-ebook", "application/zip",
                "application/x-cbz", "application/x-cbr", "application/x-cb7",
                "application/x-zip-compressed"});
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

    private void removeRecord(ReadingStore.Record r) {
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

    /**
     * 朗读续播：按记录重建最小朗读任务（与听书续播同口径：章节表缺失时单章模式）。
     * TtsReadActivity 进页后按同书 TTS 记录自动恢复章节/段。
     */
    private void openTtsRecord(ReadingStore.Record r) {
        try {
            TtsJob job = new TtsJob();
            job.bookTitle = r.bookTitle == null ? "" : r.bookTitle;
            job.coverUrl = r.coverUrl == null ? "" : r.coverUrl;
            job.sourceName = r.sourceName == null ? "" : r.sourceName;
            job.siteKey = r.siteKey == null ? "" : r.siteKey;
            job.legadoKey = r.legadoKey == null ? "" : r.legadoKey;
            job.ruleTitle = r.ruleTitle == null ? "" : r.ruleTitle;
            job.localId = r.localId == null ? "" : r.localId;
            job.inlineText = r.content == null ? "" : r.content;
            job.chapters.add(new ReadingSession.Chapter(
                    r.chapterName == null ? "" : r.chapterName,
                    r.chapterUrl == null ? "" : r.chapterUrl));
            job.startChapter = 0;
            job.startFraction = 0;
            job.voiceId = EdgeVoiceCatalog.defaultVoice();
            job.rate = 1.0f;
            TtsReadActivity.start(this, TtsJob.put(job));
        } catch (Throwable t) {
            Toast.makeText(this, "打开失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String fmtMs(int ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private int dp(int v) {
        return ReadingSkin.dp(this, v);
    }
}
