package com.fongmi.android.tv.ui.activity;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.read.HkNovelFetcher;
import com.fongmi.android.tv.read.ReadingSession;
import com.fongmi.android.tv.read.ReadingStore;
import com.fongmi.android.tv.ui.hk.HkDialogs;
import com.fongmi.android.tv.utils.Task;

/**
 * 小说阅读器：全新设计。纵滑顺滑、字号可调、三套背景主题、章节抽屉、记位置。
 * 进度按万分比存，换字号/换主题后恢复不错位。
 */
public class NovelReaderActivity extends AppCompatActivity {

    private static final String PREFS = "reading_pref";
    private static final String KEY_TEXT_SIZE = "novel_text_size";
    private static final String KEY_THEME = "novel_theme";

    // 主题：白 / 米色 / 深色
    private static final int[] BG = {0xFFFFFFFF, 0xFFF6ECD9, 0xFF141414};
    private static final int[] FG = {0xFF222222, 0xFF4E3A28, 0xFFCFCFCF};

    private ReadingSession session;
    private SharedPreferences prefs;
    private FrameLayout root;
    private ScrollView scrollView;
    private TextView tvContent;
    private View topBar;
    private View bottomBar;
    private TextView tvTitle;
    private TextView tvProgress;
    private boolean barsShown = false;
    private boolean loading = false;
    private String currentText = "";
    private long lastSaveAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        session = ReadingSession.take(getIntent().getStringExtra("session"));
        if (session == null) {
            Toast.makeText(this, "阅读会话已失效", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        loadChapter(session.chapterIndex, true);
    }

    // ---------------- UI ----------------

    private void buildUi() {
        root = new FrameLayout(this);
        applyTheme();

        scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tvContent = new TextView(this);
        tvContent.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int pad = dp(20);
        tvContent.setPadding(pad, dp(56), pad, dp(64));
        tvContent.setTextSize(prefs.getFloat(KEY_TEXT_SIZE, 18));
        tvContent.setLineSpacing(dp(8), 1.0f);
        tvContent.setTextColor(fg());
        scrollView.addView(tvContent);
        root.addView(scrollView);

        GestureDetector gd = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        toggleBars();
                        return true;
                    }
                });
        scrollView.setOnTouchListener((v, e) -> {
            gd.onTouchEvent(e);
            return false;
        });
        scrollView.getViewTreeObserver().addOnScrollChangedListener(this::updateProgressLabel);

        topBar = buildTopBar();
        root.addView(topBar);
        bottomBar = buildBottomBar();
        root.addView(bottomBar);
        setBarsVisible(false);
        setContentView(root);
    }

    private int theme() {
        return prefs.getInt(KEY_THEME, 0);
    }

    private int bg() {
        return BG[theme() % BG.length];
    }

    private int fg() {
        return FG[theme() % FG.length];
    }

    private void applyTheme() {
        root.setBackgroundColor(bg());
        if (tvContent != null) {
            tvContent.setTextColor(fg());
        }
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        bar.setLayoutParams(lp);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xCC000000);
        int pad = dp(12);
        bar.setPadding(pad, dp(28), pad, pad);

        ImageButton back = new ImageButton(this);
        back.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setColorFilter(Color.WHITE);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        tvTitle = new TextView(this);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(15);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = dp(8);
        tvTitle.setLayoutParams(tlp);
        bar.addView(tvTitle);
        refreshTitle();
        return bar;
    }

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        bar.setLayoutParams(lp);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0xCC000000);
        int pad = dp(12);
        bar.setPadding(pad, pad, pad, dp(28));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView prev = btn("上一章");
        prev.setOnClickListener(v -> goChapter(session.chapterIndex - 1));
        row.addView(prev);

        tvProgress = new TextView(this);
        tvProgress.setTextColor(Color.WHITE);
        tvProgress.setTextSize(13);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tvProgress.setLayoutParams(plp);
        tvProgress.setGravity(Gravity.CENTER);
        row.addView(tvProgress);

        TextView next = btn("下一章");
        next.setOnClickListener(v -> goChapter(session.chapterIndex + 1));
        row.addView(next);
        bar.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);

        TextView chapters = btn("目录");
        chapters.setOnClickListener(v -> showChapters());
        row2.addView(chapters);

        TextView smaller = btn("A-");
        smaller.setOnClickListener(v -> changeTextSize(-1));
        row2.addView(smaller);

        TextView bigger = btn("A+");
        bigger.setOnClickListener(v -> changeTextSize(1));
        row2.addView(bigger);

        TextView themeBtn = btn("主题");
        themeBtn.setOnClickListener(v -> {
            prefs.edit().putInt(KEY_THEME, (theme() + 1) % BG.length).apply();
            applyTheme();
            Toast.makeText(this, "已切换主题", Toast.LENGTH_SHORT).show();
        });
        row2.addView(themeBtn);
        bar.addView(row2);
        return bar;
    }

    private TextView btn(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14);
        int hp = dp(12), vp = dp(8);
        tv.setPadding(hp, vp, hp, vp);
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private void toggleBars() {
        setBarsVisible(!barsShown);
    }

    private void setBarsVisible(boolean show) {
        barsShown = show;
        topBar.setVisibility(show ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void refreshTitle() {
        ReadingSession.Chapter c = session.currentChapter();
        String t = session.bookTitle;
        if (!TextUtils.isEmpty(c.name) && !c.name.equals(t)) t = t + " · " + c.name;
        tvTitle.setText(t);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---------------- 数据 ----------------

    private void loadChapter(int index, boolean restoreProgress) {
        if (loading) return;
        if (index < 0 || (session.chapters.size() > 1 && index >= session.chapters.size())) {
            Toast.makeText(this, index < 0 ? "已经是第一章" : "已经是最后一章", Toast.LENGTH_SHORT).show();
            return;
        }
        loading = true;
        session.chapterIndex = Math.max(0, index);
        ReadingSession.Chapter ch = session.currentChapter();
        session.chapterUrl = ch.url;
        tvContent.setText("加载中…");
        refreshTitle();
        Task.execute(() -> {
            String text = resolveText(ch);
            runOnUiThread(() -> {
                loading = false;
                currentText = text;
                if (TextUtils.isEmpty(text)) {
                    tvContent.setText("正文抓取失败，换一章试试");
                } else {
                    tvContent.setText(text);
                }
                scrollView.post(() -> {
                    if (restoreProgress) {
                        ReadingStore.Record prog = ReadingStore.find("NOVEL",
                                session.sourceName, session.bookTitle);
                        if (prog != null && prog.chapterIndex == session.chapterIndex && prog.position > 0) {
                            scrollToFraction(prog.position / 10000f);
                        } else {
                            scrollView.scrollTo(0, 0);
                        }
                    } else {
                        scrollView.scrollTo(0, 0);
                    }
                    updateProgressLabel();
                });
                saveProgress();
            });
        });
    }

    /** 取正文：会话自带（py 内联首章）→ py 按章重跑解析 → 海阔按规则抓。 */
    private String resolveText(ReadingSession.Chapter ch) {
        // py 小说首章：正文随会话内联
        if (!TextUtils.isEmpty(session.text) && ch == session.currentChapter()
                && session.chapterIndex == 0) {
            return session.text;
        }
        // py 小说其它章：按章重跑 playerContent（判别层会再次命中正文分支）
        if (!TextUtils.isEmpty(session.siteKey) && !TextUtils.isEmpty(ch.url)) {
            try {
                com.fongmi.android.tv.bean.Result r =
                        com.fongmi.android.tv.api.SiteApi.playerContent(session.siteKey, "", ch.url);
                ReadingSession rs = com.fongmi.android.tv.read.ReadingGate.extract(r);
                if (rs != null && rs.kind == ReadingSession.Kind.NOVEL && !TextUtils.isEmpty(rs.text)) {
                    return rs.text;
                }
            } catch (Throwable ignored) {
            }
        }
        // Legado 书源：按书源规则抓正文
        if (!TextUtils.isEmpty(session.legadoKey) && !TextUtils.isEmpty(ch.url)) {
            String t = com.fongmi.android.tv.legado.LegadoReading.fetchText(session.legadoKey, ch.url);
            if (!TextUtils.isEmpty(t)) return t;
        }
        // 海阔：按规则抓正文
        if (!TextUtils.isEmpty(session.ruleTitle) && !TextUtils.isEmpty(ch.url)) {
            String t = HkNovelFetcher.fetchText(session.ruleTitle, ch.url);
            if (!TextUtils.isEmpty(t)) return t;
        }
        return "";
    }

    private void goChapter(int index) {
        loadChapter(index, false);
    }

    private void showChapters() {
        if (session.chapters.isEmpty()) {
            Toast.makeText(this, "没有章节列表", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[session.chapters.size()];
        for (int i = 0; i < names.length; i++) names[i] = session.chapters.get(i).name;
        new HkDialogs(this)
                .setTitle("章节（" + names.length + "）")
                .setSingleChoiceItems(names, session.chapterIndex, (d, which) -> {
                    d.dismiss();
                    goChapter(which);
                })
                .setPositiveButton("关闭", (d, w) -> d.dismiss())
                .show();
    }

    private void changeTextSize(float delta) {
        float cur = prefs.getFloat(KEY_TEXT_SIZE, 18);
        float next = Math.max(13, Math.min(26, cur + delta));
        prefs.edit().putFloat(KEY_TEXT_SIZE, next).apply();
        float frac = currentFraction();
        tvContent.setTextSize(next);
        scrollView.post(() -> scrollToFraction(frac));
    }

    // ---------------- 进度 ----------------

    private float currentFraction() {
        try {
            int max = Math.max(1, tvContent.getHeight() - scrollView.getHeight());
            return Math.min(1f, Math.max(0f, scrollView.getScrollY() * 1f / max));
        } catch (Throwable t) {
            return 0f;
        }
    }

    private void scrollToFraction(float frac) {
        try {
            int max = Math.max(1, tvContent.getHeight() - scrollView.getHeight());
            scrollView.scrollTo(0, (int) (frac * max));
        } catch (Throwable ignored) {
        }
    }

    private void updateProgressLabel() {
        if (tvProgress == null) return;
        int pct = Math.round(currentFraction() * 100);
        ReadingSession.Chapter c = session.currentChapter();
        String name = TextUtils.isEmpty(c.name) ? "" : c.name + " · ";
        tvProgress.setText(name + pct + "%");
    }

    private void saveProgress() {
        long now = System.currentTimeMillis();
        if (now - lastSaveAt < 2000) return;
        lastSaveAt = now;
        try {
            ReadingStore.Record r = new ReadingStore.Record();
            r.kind = "NOVEL";
            r.sourceName = session.sourceName;
            r.bookTitle = session.bookTitle;
            r.coverUrl = session.coverUrl;
            r.chapterName = session.currentChapter().name;
            r.chapterIndex = session.chapterIndex;
            r.chapterUrl = session.chapterUrl;
            r.siteKey = session.siteKey;
            r.legadoKey = session.legadoKey;
            r.position = Math.round(currentFraction() * 10000);
            // py 小说：正文存快照，继续读时不用重跑解析
            if (TextUtils.isEmpty(session.ruleTitle) && !TextUtils.isEmpty(currentText)) {
                r.content = currentText.length() > 60000
                        ? currentText.substring(0, 60000) : currentText;
            }
            ReadingStore.save(r);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        lastSaveAt = 0;
        saveProgress();
        super.onPause();
    }
}
