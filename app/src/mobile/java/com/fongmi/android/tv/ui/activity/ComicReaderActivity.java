package com.fongmi.android.tv.ui.activity;

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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.hk.HkPlay;
import com.fongmi.android.tv.api.hk.HkRouter;
import com.fongmi.android.tv.read.ReadingGate;
import com.fongmi.android.tv.read.ReadingSession;
import com.fongmi.android.tv.read.ReadingStore;
import com.fongmi.android.tv.ui.hk.HkDialogs;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * 漫画阅读器：全新设计，不抄 8.83 PictureListActivity 的交互。
 * 竖滑连读（默认）/ 横向可切、Glide 预加载、章节抽屉、滚动进度、点按呼出控制栏。
 * 内容区干净：控制栏默认隐藏，点按才出（该藏的藏）。
 */
public class ComicReaderActivity extends AppCompatActivity {

    private static final String PREFS = "reading_pref";
    private static final String KEY_DIRECTION = "comic_direction_h";

    private ReadingSession session;
    private RecyclerView rv;
    private ImgAdapter adapter;
    private final List<String> images = new ArrayList<>();
    private View topBar;
    private View bottomBar;
    private TextView tvTitle;
    private TextView tvPage;
    private SeekBar seekBar;
    private boolean barsShown = false;
    private boolean horizontal = false;
    private boolean loading = false;
    private boolean firstLoad = true;
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
        horizontal = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DIRECTION, false);
        buildUi();
        loadChapter(session.chapterIndex, Math.max(0, session.startPage));
    }

    // ---------------- UI ----------------

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        rv = new RecyclerView(this);
        rv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayoutManager lm = new LinearLayoutManager(this,
                horizontal ? LinearLayoutManager.HORIZONTAL : LinearLayoutManager.VERTICAL, false);
        lm.setInitialPrefetchItemCount(4);
        rv.setLayoutManager(lm);
        adapter = new ImgAdapter();
        rv.setAdapter(adapter);
        root.addView(rv);

        // 点按（非滚动）呼出/隐藏控制栏
        GestureDetector gd = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        toggleBars();
                        return true;
                    }
                });
        rv.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(RecyclerView r, MotionEvent e) {
                gd.onTouchEvent(e);
                return false;
            }
        });

        topBar = buildTopBar();
        root.addView(topBar);
        bottomBar = buildBottomBar();
        root.addView(bottomBar);
        setBarsVisible(false);

        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView r, int dx, int dy) {
                updatePageLabel();
            }

            @Override
            public void onScrollStateChanged(RecyclerView r, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    preloadAhead();
                    saveProgress();
                }
            }
        });

        setContentView(root);
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

        TextView prev = chapterButton("上一章");
        prev.setOnClickListener(v -> goChapter(session.chapterIndex - 1));
        row.addView(prev);

        seekBar = new SeekBar(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        row.addView(seekBar, slp);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                scrollToPage(s.getProgress());
            }
        });

        tvPage = new TextView(this);
        tvPage.setTextColor(Color.WHITE);
        tvPage.setTextSize(13);
        tvPage.setPadding(dp(8), 0, dp(8), 0);
        row.addView(tvPage);

        TextView next = chapterButton("下一章");
        next.setOnClickListener(v -> goChapter(session.chapterIndex + 1));
        row.addView(next);
        bar.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);

        TextView chapters = chapterButton("目录");
        chapters.setOnClickListener(v -> showChapters());
        row2.addView(chapters);

        TextView dir = chapterButton(horizontal ? "横向" : "竖向");
        dir.setOnClickListener(v -> {
            horizontal = !horizontal;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DIRECTION, horizontal).apply();
            dir.setText(horizontal ? "横向" : "竖向");
            int pos = currentPage();
            rv.setLayoutManager(new LinearLayoutManager(this,
                    horizontal ? LinearLayoutManager.HORIZONTAL : LinearLayoutManager.VERTICAL, false));
            rv.setAdapter(adapter);
            scrollToPage(Math.min(pos, Math.max(0, images.size() - 1)));
        });
        row2.addView(dir);
        bar.addView(row2);
        return bar;
    }

    /** 轻量文字按钮：字大好点，阅读器控制栏用。 */
    private TextView chapterButton(String text) {
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

    /** 切章：取图（懒取）→ 换列表 → 回顶 → 存进度。 */
    private void loadChapter(int index, int startPage) {
        if (loading) return;
        if (index < 0 || (session.chapters.size() > 1 && index >= session.chapters.size())) {
            Toast.makeText(this, index < 0 ? "已经是第一章" : "已经是最后一章", Toast.LENGTH_SHORT).show();
            return;
        }
        loading = true;
        session.chapterIndex = Math.max(0, index);
        ReadingSession.Chapter ch = session.currentChapter();
        session.chapterUrl = ch.url;
        Task.execute(() -> {
            List<String> list = resolveImages(ch);
            runOnUiThread(() -> {
                loading = false;
                firstLoad = false;
                images.clear();
                if (list != null) images.addAll(list);
                adapter.notifyDataSetChanged();
                refreshTitle();
                updateSeekMax();
                if (images.isEmpty()) {
                    Toast.makeText(this, "这一章没有取到图片", Toast.LENGTH_SHORT).show();
                } else {
                    scrollToPage(Math.min(startPage, images.size() - 1));
                    preloadAhead();
                }
                saveProgress();
            });
        });
    }

    /** 解析某章图片：会话自带（首载）→ py 内嵌串 → 海阔懒取。 */
    private List<String> resolveImages(ReadingSession.Chapter ch) {
        // 1. 首载用会话自带（判别层/入口已带入）
        if (firstLoad && !session.images.isEmpty()) {
            return new ArrayList<>(session.images);
        }
        // 2. py：章节 url 本身就是图片串
        List<String> fromUrl = ReadingGate.imagesOfChapter(ch);
        if (!fromUrl.isEmpty()) return fromUrl;
        // 3. 海阔：router.play 取 pics
        if (!TextUtils.isEmpty(session.ruleTitle) && !TextUtils.isEmpty(ch.url)) {
            try {
                HkRouter router = SiteApi.getHkRouter(session.ruleTitle);
                if (router != null) {
                    HkPlay play = router.play(ch.url);
                    if (play != null && !play.getPics().isEmpty()) {
                        return new ArrayList<>(play.getPics());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // Legado 书源：按书源规则取图片
        if (!TextUtils.isEmpty(session.legadoKey) && !TextUtils.isEmpty(ch.url)) {
            try {
                java.util.List<String> list =
                        com.fongmi.android.tv.legado.LegadoReading.fetchImages(session.legadoKey, ch.url);
                if (list != null && !list.isEmpty()) return new ArrayList<>(list);
            } catch (Throwable ignored) {
            }
        }
        return new ArrayList<>();
    }

    private void goChapter(int index) {
        loadChapter(index, 0);
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

    // ---------------- 进度 ----------------

    private int currentPage() {
        try {
            LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
            return Math.max(0, lm.findFirstVisibleItemPosition());
        } catch (Throwable t) {
            return 0;
        }
    }

    private void scrollToPage(int pos) {
        if (pos < 0 || pos >= images.size()) return;
        rv.scrollToPosition(pos);
        updatePageLabel();
        saveProgress();
    }

    private void updatePageLabel() {
        int total = images.size();
        int cur = total == 0 ? 0 : Math.min(currentPage() + 1, total);
        tvPage.setText(cur + " / " + total);
        if (seekBar != null && total > 0) {
            seekBar.setProgress(Math.min(currentPage(), total - 1));
        }
    }

    private void updateSeekMax() {
        if (seekBar != null) seekBar.setMax(Math.max(0, images.size() - 1));
        updatePageLabel();
    }

    /** 预加载后面 3 张（跟手的关键）。 */
    private void preloadAhead() {
        try {
            int cur = currentPage();
            for (int i = 1; i <= 3; i++) {
                int p = cur + i;
                if (p < images.size()) ImgUtil.preload(this, images.get(p));
            }
        } catch (Throwable ignored) {
        }
    }

    private void saveProgress() {
        long now = System.currentTimeMillis();
        if (now - lastSaveAt < 2000) return;
        lastSaveAt = now;
        try {
            ReadingStore.Record r = new ReadingStore.Record();
            r.kind = "COMIC";
            r.sourceName = session.sourceName;
            r.bookTitle = session.bookTitle;
            r.coverUrl = session.coverUrl;
            r.chapterName = session.currentChapter().name;
            r.chapterIndex = session.chapterIndex;
            r.chapterUrl = session.chapterUrl;
            r.siteKey = session.siteKey;
            r.legadoKey = session.legadoKey;
            r.position = currentPage();
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

    // ---------------- 适配器 ----------------

    private class ImgAdapter extends RecyclerView.Adapter<ImgAdapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            final ImageView iv;

            Holder(ImageView iv) {
                super(iv);
                this.iv = iv;
            }
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            ImageView iv = new ImageView(parent.getContext());
            iv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            return new Holder(iv);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            String url = images.get(position);
            // 按屏宽缩放：宽铺满、高按比例，不跳版
            ImgUtil.load("", url, holder.iv, false, 0, 0,
                    TextUtils.isEmpty(session.referer) ? null : session.referer);
        }

        @Override
        public int getItemCount() {
            return images.size();
        }
    }
}
