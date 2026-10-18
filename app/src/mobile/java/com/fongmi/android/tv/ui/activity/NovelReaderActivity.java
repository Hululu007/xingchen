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
import com.fongmi.android.tv.utils.Task;

/**
 * 小说阅读器：全新设计。纵滑顺滑、字号可调、三套背景主题、章节抽屉、记位置。
 * 进度按万分比存，换字号/换主题后恢复不错位。
 */
public class NovelReaderActivity extends AppCompatActivity {

    private static final String PREFS = "reading_pref";
    private static final String KEY_TEXT_SIZE = "novel_text_size";
    private static final String KEY_THEME = "novel_theme";
    private static final String KEY_PAGE_MODE = "novel_page_mode"; // 0纵滑 1覆盖 2滑动
    private static final String KEY_FONT = "novel_font"; // 0默认 1衬线 2等宽 3导入
    private static final String KEY_FONT_PATH = "novel_font_path";
    private static final String KEY_SPACING = "novel_spacing"; // 0紧凑 1标准 2疏朗
    private static final String KEY_VOLUME_PAGE = "novel_volume_page";
    private static final String KEY_KEEP_SCREEN = "novel_keep_screen";

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
        applyKeepScreen();
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
        applyTextStyle();
        tvContent.setTextColor(fg());
        scrollView.addView(tvContent);
        root.addView(scrollView);

        GestureDetector gd = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        return onContentTap(e.getX());
                    }
                });
        scrollView.setOnTouchListener((v, e) -> {
            gd.onTouchEvent(e);
            // 覆盖/滑动模式：手势被翻页接管，不自由滚动
            return pageMode() != 0;
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

        // 朗读入口（功能线；视觉随后换皮）：神经朗读从当前章当前位置起读
        TextView ttsBtn = btn("朗读");
        ttsBtn.setOnClickListener(v -> startTts());
        row2.addView(ttsBtn);

        TextView markBtn = btn("书签");
        markBtn.setOnClickListener(v -> showBookmarks());
        row2.addView(markBtn);

        TextView csBtn = btn("换源");
        csBtn.setOnClickListener(v -> changeSource());
        row2.addView(csBtn);

        TextView setBtn = btn("设置");
        setBtn.setOnClickListener(v -> showSettings());
        row2.addView(setBtn);
        bar.addView(row2);
        return bar;
    }

    // ---------------- 设置 ----------------

    private void showSettings() {
        String[] items = {
                "翻页模式：" + new String[]{"纵滑", "覆盖", "滑动"}[pageMode()],
                "字体：" + new String[]{"默认", "衬线", "等宽", "导入的字体"}[prefs.getInt(KEY_FONT, 0)],
                "行距：" + new String[]{"紧凑", "标准", "疏朗"}[prefs.getInt(KEY_SPACING, 1)],
                "音量键翻页：" + (prefs.getBoolean(KEY_VOLUME_PAGE, false) ? "开" : "关"),
                "屏幕常亮：" + (prefs.getBoolean(KEY_KEEP_SCREEN, true) ? "开" : "关"),
                "清除本书缓存"
        };
        new com.fongmi.android.tv.ui.hk.HkDialogs(this)
                .setTitle("阅读设置")
                .setItems(items, (d, which) -> {
                    d.dismiss();
                    switch (which) {
                        case 0: pickPageMode(); break;
                        case 1: pickFont(); break;
                        case 2: pickSpacing(); break;
                        case 3:
                            prefs.edit().putBoolean(KEY_VOLUME_PAGE,
                                    !prefs.getBoolean(KEY_VOLUME_PAGE, false)).apply();
                            showSettings();
                            break;
                        case 4:
                            prefs.edit().putBoolean(KEY_KEEP_SCREEN,
                                    !prefs.getBoolean(KEY_KEEP_SCREEN, true)).apply();
                            applyKeepScreen();
                            showSettings();
                            break;
                        case 5:
                            com.fongmi.android.tv.read.ReadingDownload.clearBook(this, bookKey());
                            Toast.makeText(this, "本书缓存已清除", Toast.LENGTH_SHORT).show();
                            break;
                    }
                })
                .setPositiveButton("关闭", (d, w) -> d.dismiss())
                .show();
    }

    private void pickPageMode() {
        String[] modes = {"纵滑", "覆盖", "滑动"};
        new com.fongmi.android.tv.ui.hk.HkDialogs(this)
                .setTitle("翻页模式")
                .setSingleChoiceItems(modes, pageMode(), (d, which) -> {
                    d.dismiss();
                    prefs.edit().putInt(KEY_PAGE_MODE, which).apply();
                    Toast.makeText(this, "已切换为" + modes[which] + "翻页", Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("取消", (d, w) -> d.dismiss())
                .show();
    }

    private void pickFont() {
        String[] fonts = {"默认", "衬线", "等宽", "导入 ttf 字体…"};
        new com.fongmi.android.tv.ui.hk.HkDialogs(this)
                .setTitle("字体")
                .setItems(fonts, (d, which) -> {
                    d.dismiss();
                    if (which == 3) {
                        pickFontFile();
                        return;
                    }
                    prefs.edit().putInt(KEY_FONT, which).apply();
                    applyTextStyle();
                })
                .setPositiveButton("取消", (d, w) -> d.dismiss())
                .show();
    }

    private void pickSpacing() {
        String[] items = {"紧凑", "标准", "疏朗"};
        new com.fongmi.android.tv.ui.hk.HkDialogs(this)
                .setTitle("行距")
                .setSingleChoiceItems(items, prefs.getInt(KEY_SPACING, 1), (d, which) -> {
                    d.dismiss();
                    prefs.edit().putInt(KEY_SPACING, which).apply();
                    float frac = currentFraction();
                    applyTextStyle();
                    scrollView.post(() -> scrollToFraction(frac));
                })
                .setPositiveButton("取消", (d, w) -> d.dismiss())
                .show();
    }

    // ---------------- 屏幕常亮 ----------------

    private void applyKeepScreen() {
        boolean on = prefs.getBoolean(KEY_KEEP_SCREEN, true);
        if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ---------------- 书签 ----------------

    /** 书 key：与 ReadingStore.Record.key() 同口径。 */
    private String bookKey() {
        if (!TextUtils.isEmpty(session.localId)) return "local|" + session.localId;
        return "NOVEL|" + session.sourceName + "|" + session.bookTitle;
    }

    private void showBookmarks() {
        java.util.List<com.fongmi.android.tv.read.ReadingBookmarkStore.Mark> marks =
                com.fongmi.android.tv.read.ReadingBookmarkStore.list(bookKey());
        com.fongmi.android.tv.read.ReaderDialogs.showBookmarks(this, session.bookTitle, marks,
                this::addBookmarkAtCurrent,
                m -> goChapter(m.chapterIndex, m.position),
                m -> com.fongmi.android.tv.read.ReadingBookmarkStore.remove(m.bookKey, m.createdAt));
    }

    private void addBookmarkAtCurrent() {
        com.fongmi.android.tv.read.ReadingBookmarkStore.Mark m =
                new com.fongmi.android.tv.read.ReadingBookmarkStore.Mark();
        m.bookKey = bookKey();
        m.bookTitle = session.bookTitle;
        m.kind = "NOVEL";
        m.chapterName = session.currentChapter().name;
        m.chapterIndex = session.chapterIndex;
        m.position = Math.round(currentFraction() * 10000);
        m.excerpt = com.fongmi.android.tv.read.ReadingBookmarkStore.excerptOf(currentText);
        com.fongmi.android.tv.read.ReadingBookmarkStore.add(m);
        Toast.makeText(this, "书签已保存", Toast.LENGTH_SHORT).show();
    }

    /** AI 口令"书签"：打开书签列表。 */
    public void aiBookmarks() {
        setBarsVisible(false);
        showBookmarks();
    }

    // ---------------- 换源 ----------------

    private void changeSource() {
        if (!com.fongmi.android.tv.read.ReadingChangeSource.supported(session)) {
            Toast.makeText(this, "当前这本书不支持换源（仅书源的书可换）",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在其它书源里找这本书…", Toast.LENGTH_SHORT).show();
        android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        com.fongmi.android.tv.read.ReadingChangeSource.search(this, session, ui, candidates ->
                com.fongmi.android.tv.read.ReadingChangeSource.pickAndSwitch(
                        this, session, candidates, session.chapterIndex,
                        currentFraction(), 0));
    }

    /** AI 口令"换源"。 */
    public void aiChangeSource() {
        setBarsVisible(false);
        changeSource();
    }

    /** 朗读本章：把会话递给朗读播放页（调用关系，阅读器本身不动）。 */
    private void startTts() {
        try {
            com.fongmi.android.tv.tts.TtsJob job =
                    new com.fongmi.android.tv.tts.TtsJob();
            job.bookTitle = session.bookTitle;
            job.coverUrl = session.coverUrl;
            job.sourceName = session.sourceName;
            job.siteKey = session.siteKey;
            job.legadoKey = session.legadoKey;
            job.ruleTitle = session.ruleTitle;
            job.localId = session.localId;
            job.inlineText = session.text;
            if (session.chapters != null && !session.chapters.isEmpty()) {
                job.chapters.addAll(session.chapters);
            } else {
                job.chapters.add(session.currentChapter());
            }
            job.startChapter = Math.max(0, Math.min(session.chapterIndex,
                    job.chapters.size() - 1));
            job.startFraction = Math.round(currentFraction() * 10000);
            job.voiceId = com.fongmi.android.tv.tts.EdgeVoiceCatalog.defaultVoice();
            job.rate = 1.0f;
            String id = com.fongmi.android.tv.tts.TtsJob.put(job);
            com.fongmi.android.tv.ui.activity.TtsReadActivity.start(this, id);
            setBarsVisible(false);
        } catch (Throwable t) {
            Toast.makeText(this, "朗读启动失败", Toast.LENGTH_SHORT).show();
        }
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

    // ---------------- 翻页模式 ----------------

    private int pageMode() {
        return prefs.getInt(KEY_PAGE_MODE, 0);
    }

    /**
     * 内容区点按：纵滑模式点哪都呼出控制栏；
     * 覆盖/滑动模式左1/3上一页、右1/3下一页、中间呼出控制栏。
     */
    private boolean onContentTap(float x) {
        if (pageMode() == 0) {
            toggleBars();
            return true;
        }
        float w = scrollView.getWidth();
        if (x < w / 3f) pageBy(-1);
        else if (x > w * 2f / 3f) pageBy(1);
        else toggleBars();
        return true;
    }

    /** 整屏翻页：覆盖=直接跳，滑动=带动画。 */
    private void pageBy(int dir) {
        int amount = Math.max(1, scrollView.getHeight() - dp(48));
        if (pageMode() == 2) scrollView.smoothScrollBy(0, dir * amount);
        else scrollView.scrollBy(0, dir * amount);
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (prefs.getBoolean(KEY_VOLUME_PAGE, false)
                && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            pageBy(keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ? 1 : -1);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------- 字体与行距 ----------------

    /** 字体：0默认 1衬线 2等宽 3导入ttf。 */
    private void applyTextStyle() {
        if (tvContent == null) return;
        int font = prefs.getInt(KEY_FONT, 0);
        android.graphics.Typeface tf = android.graphics.Typeface.DEFAULT;
        if (font == 1) tf = android.graphics.Typeface.SERIF;
        else if (font == 2) tf = android.graphics.Typeface.MONOSPACE;
        else if (font == 3) {
            String path = prefs.getString(KEY_FONT_PATH, "");
            try {
                if (!TextUtils.isEmpty(path) && new java.io.File(path).exists()) {
                    tf = android.graphics.Typeface.createFromFile(path);
                }
            } catch (Throwable ignored) {
            }
        }
        tvContent.setTypeface(tf);
        int sp = prefs.getInt(KEY_SPACING, 1);
        if (sp == 0) tvContent.setLineSpacing(0, 1.0f);       // 紧凑
        else if (sp == 2) tvContent.setLineSpacing(dp(14), 1.35f); // 疏朗
        else tvContent.setLineSpacing(dp(8), 1.15f);          // 标准
    }

    private final androidx.activity.result.ActivityResultLauncher<android.content.Intent> fontPicker =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != RESULT_OK || result.getData() == null
                                || result.getData().getData() == null) return;
                        android.net.Uri uri = result.getData().getData();
                        try {
                            java.io.File dir = new java.io.File(getFilesDir(), "fonts");
                            if (!dir.exists()) dir.mkdirs();
                            java.io.File out = new java.io.File(dir, "custom.ttf");
                            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                                 java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                                byte[] buf = new byte[32768];
                                int n;
                                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                            }
                            prefs.edit().putInt(KEY_FONT, 3)
                                    .putString(KEY_FONT_PATH, out.getAbsolutePath()).apply();
                            applyTextStyle();
                            Toast.makeText(this, "已应用导入的字体", Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                            Toast.makeText(this, "字体导入失败", Toast.LENGTH_SHORT).show();
                        }
                    });

    private void pickFontFile() {
        try {
            android.content.Intent it = new android.content.Intent(
                    android.content.Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            fontPicker.launch(it);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开文件选择器", Toast.LENGTH_SHORT).show();
        }
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
            String text = cachedOrResolve(ch);
            runOnUiThread(() -> {
                loading = false;
                currentText = text;
                if (TextUtils.isEmpty(text)) {
                    tvContent.setText("正文抓取失败，换一章试试");
                } else {
                    tvContent.setText(text);
                }
                scrollView.post(() -> {
                    if (session.startFraction >= 0) {
                        // 换源/书签带入的进度优先
                        scrollToFraction(session.startFraction / 10000f);
                        session.startFraction = -1;
                    } else if (restoreProgress) {
                        ReadingStore.Record prog = !TextUtils.isEmpty(session.localId)
                                ? ReadingStore.findLocal(session.localId)
                                : ReadingStore.find("NOVEL", session.sourceName, session.bookTitle);
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

    /**
     * 取正文：缓存 → 本地书 → 会话自带（py 内联首章）→ py 按章重跑解析 → 海阔按规则抓。
     * 静态化供朗读引擎与章节缓存复用（同口径，不另起炉灶）。
     */
    public static String resolveText(ReadingSession session, ReadingSession.Chapter ch) {
        // 本地书：按章从私有目录懒读
        if (com.fongmi.android.tv.localbook.LocalBookReader.isLocal(ch.url)) {
            return com.fongmi.android.tv.localbook.LocalBookReader.textOf(ch.url);
        }
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

    /** 取正文：先看缓存（本地书本身就在本地，不走缓存）。 */
    private String cachedOrResolve(ReadingSession.Chapter ch) {
        if (TextUtils.isEmpty(session.localId)) {
            String cached = com.fongmi.android.tv.read.ReadingDownload.loadNovelChapter(
                    this, bookKey(), session.chapterIndex);
            if (!TextUtils.isEmpty(cached)) return cached;
        }
        return resolveText(session, ch);
    }

    private void goChapter(int index) {
        loadChapter(index, false);
    }

    /** 书签跳转：可带章内进度。 */
    private void goChapter(int index, int fraction) {
        session.startFraction = fraction;
        loadChapter(index, false);
    }

    private void showChapters() {
        if (session.chapters.isEmpty()) {
            Toast.makeText(this, "没有章节列表", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<String> names = new java.util.ArrayList<>(session.chapters.size());
        for (ReadingSession.Chapter c : session.chapters) names.add(c.name);
        boolean local = !TextUtils.isEmpty(session.localId);
        String cacheStatus = local ? null : com.fongmi.android.tv.read.ReadingDownload
                .statusText(this, bookKey(), session.chapters.size());
        com.fongmi.android.tv.read.ReaderDialogs.showChapters(this, session.bookTitle, names,
                session.chapterIndex, cacheStatus,
                local ? null : this::askCacheRange,
                this::goChapter);
    }

    /** 缓存范围选择：后续 20 / 50 / 全部。 */
    private void askCacheRange() {
        String[] items = {"后续 20 章", "后续 50 章", "全部剩余章节"};
        int[] counts = {20, 50, Integer.MAX_VALUE};
        new com.fongmi.android.tv.ui.hk.HkDialogs(this)
                .setTitle("缓存章节（离线看）")
                .setItems(items, (d, which) -> {
                    d.dismiss();
                    Toast.makeText(this, "后台缓存中，完成后会提示", Toast.LENGTH_SHORT).show();
                    android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
                    com.fongmi.android.tv.read.ReadingDownload.downloadNextNovel(
                            this, session, bookKey(), counts[which], ui,
                            (ok, total) -> Toast.makeText(this,
                                    total == 0 ? "没有可缓存的新章节"
                                            : "已缓存 " + ok + " / " + total + " 章",
                                    Toast.LENGTH_SHORT).show());
                })
                .setPositiveButton("取消", (d, w) -> d.dismiss())
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
            r.localId = session.localId;
            r.origin = TextUtils.isEmpty(session.localId) ? "" : "local";
            r.position = Math.round(currentFraction() * 10000);
            // py 小说：正文存快照，继续读时不用重跑解析（本地书从磁盘懒读，不存快照）
            if (TextUtils.isEmpty(session.ruleTitle) && TextUtils.isEmpty(session.localId)
                    && !TextUtils.isEmpty(currentText)) {
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
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyKeepScreen();
    }
}
