package com.fongmi.android.tv.ui.music;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 封面卡片主题（重做第一步；规格 gz-full-parity-1025 §4＋§2＋§1＋§15.1-8）。
 * 布局序定死：顶 42→歌名(30)→4→歌手(22)→22→封面权重区→14→玻璃条(48)→底 12。
 * 默认态：封面叠卡＋玻璃条（收藏键/单行歌词/播放键）。
 * 展开态（点封面 plain if 切态）：封面区换 顶行双筹＋Scrubber＋展开歌词槽＋信息卡＋四玻璃键，
 * 玻璃条变「返回到封面模式」。所有尺寸由本类定死，不留 wrap＋match 塌陷组合。
 * 歌词本步最小落地：玻璃条单行（当前行/加载中文案）＋展开槽行级窗口（当前行居中、
 * 行色走 §7.1 深色面回落值、字斜 −0.12）；逐字动效与非当前行模糊属歌词步，不做。
 */
public class CoverCardThemeView extends LinearLayout implements PlayerThemeFace {

    // §4 布局数值（dp）
    private static final int TOP_PAD = 42;
    private static final int TITLE_H = 30;
    private static final int TITLE_ARTIST_GAP = 4;
    private static final int ARTIST_H = 22;
    private static final int ARTIST_COVER_GAP = 22;
    private static final int COVER_BAR_GAP = 14;
    private static final int BAR_H = 48;
    private static final int BAR_SIDE = 24;
    private static final int BOTTOM_PAD = 12;
    private static final int TEXT_SIDE = 24;

    private final CoverStackView stack;
    private final FrameLayout fxLayer;
    private final LinearLayout expandedColumn;
    private final TextView titleView;
    private final TextView artistView;
    private final TextView glassLyricView;
    private final ImageView favButton;
    private final ImageView playButton;
    private final FrameLayout glassBar;
    private final View barDefaultRow;
    private final TextView barBackRow;
    private final TextView qualityChip;
    private final TextView lyricChip;
    private final TextView timeCurrentView;
    private final TextView timeTotalView;
    private final PlayerScrubberView scrubber;
    private final FrameLayout lyricSlot;
    private final LinearLayout slotLines;
    private final View infoCard;
    private final TextView infoTitleView;
    private final TextView infoSubView;
    private final TextView infoDetailView;

    private PlayerThemeFace.Callback callback;
    private boolean expanded;
    private boolean infoShown;
    private boolean playing;
    private boolean favorite;
    private long durationMs;
    private List<RichLyrics.Line> lyricLines;
    private List<LyricsLine> mainLines;
    private boolean lyricLoading;
    private int lyricIndex = -1;
    private boolean showTranslation = true;

    public CoverCardThemeView(Context context) {
        this(context, null);
    }

    public CoverCardThemeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);

        addView(spacer(TOP_PAD));

        titleView = new TextView(context);
        titleView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, ResUtil.dp2px(TITLE_H)));
        titleView.setPadding(ResUtil.dp2px(TEXT_SIDE), 0, ResUtil.dp2px(TEXT_SIDE), 0);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setTextSize(20f);
        titleView.setTextColor(0xF5FFFFFF);
        titleView.setTypeface(typeface(800));
        addView(titleView);

        addView(spacer(TITLE_ARTIST_GAP));

        artistView = new TextView(context);
        artistView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, ResUtil.dp2px(ARTIST_H)));
        artistView.setPadding(ResUtil.dp2px(TEXT_SIDE), 0, ResUtil.dp2px(TEXT_SIDE), 0);
        artistView.setGravity(Gravity.CENTER_VERTICAL);
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        artistView.setTextSize(14f);
        // 本步歌手无去向 → 不可点色 白 0.55（§4：可点 0.65／不可点 0.55）
        artistView.setTextColor(0x8CFFFFFF);
        artistView.setTypeface(typeface(500));
        addView(artistView);

        addView(spacer(ARTIST_COVER_GAP));

        // —— 封面权重区 ——
        FrameLayout region = new FrameLayout(context);
        LayoutParams regionLp = new LayoutParams(LayoutParams.MATCH_PARENT, 0);
        regionLp.weight = 1f;
        region.setLayoutParams(regionLp);
        addView(region);

        stack = new CoverStackView(context);
        stack.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        region.addView(stack);

        fxLayer = new FrameLayout(context) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                return false;
            }
        };
        fxLayer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        fxLayer.setClickable(false);
        region.addView(fxLayer);

        expandedColumn = buildExpandedColumn(context);
        expandedColumn.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        expandedColumn.setVisibility(GONE);
        region.addView(expandedColumn);

        // 展开列的组成在 buildExpandedColumn 里已建好并回填字段引用
        qualityChip = expandedRefs.qualityChip;
        lyricChip = expandedRefs.lyricChip;
        timeCurrentView = expandedRefs.timeCurrentView;
        timeTotalView = expandedRefs.timeTotalView;
        scrubber = expandedRefs.scrubber;
        lyricSlot = expandedRefs.lyricSlot;
        slotLines = expandedRefs.slotLines;
        infoCard = expandedRefs.infoCard;
        infoTitleView = expandedRefs.infoTitleView;
        infoSubView = expandedRefs.infoSubView;
        infoDetailView = expandedRefs.infoDetailView;

        addView(spacer(COVER_BAR_GAP));

        // —— 玻璃条 ——
        glassBar = new FrameLayout(context) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                // 整条按压 0.98（§4＋§15.1-8(b)：150ms、CubicBezier(0.2,0,0,1)）
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) bounce(this, 0.98f);
                if (ev.getActionMasked() == MotionEvent.ACTION_UP
                        || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) bounce(this, 1f);
                return super.dispatchTouchEvent(ev);
            }
        };
        LayoutParams barLp = new LayoutParams(LayoutParams.MATCH_PARENT, ResUtil.dp2px(BAR_H));
        barLp.leftMargin = ResUtil.dp2px(BAR_SIDE);
        barLp.rightMargin = ResUtil.dp2px(BAR_SIDE);
        glassBar.setLayoutParams(barLp);
        glassBar.setBackgroundResource(R.drawable.bg_cover_glass_bar);
        addView(glassBar);

        FrameLayout def = new FrameLayout(context);
        def.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        favButton = buildBarButton(context, R.drawable.ic_music_heart);
        FrameLayout.LayoutParams favLp = new FrameLayout.LayoutParams(ResUtil.dp2px(40), ResUtil.dp2px(40));
        favLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        favLp.leftMargin = ResUtil.dp2px(16);
        favButton.setLayoutParams(favLp);
        def.addView(favButton);

        glassLyricView = new TextView(context);
        FrameLayout.LayoutParams lyricLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        glassLyricView.setLayoutParams(lyricLp);
        glassLyricView.setPadding(ResUtil.dp2px(72), 0, ResUtil.dp2px(72), 0);
        glassLyricView.setGravity(Gravity.CENTER);
        glassLyricView.setSingleLine(true);
        glassLyricView.setEllipsize(TextUtils.TruncateAt.END);
        glassLyricView.setTextSize(13f);
        glassLyricView.setTextColor(0xEBFFFFFF);
        glassLyricView.setTypeface(typeface(500));
        glassLyricView.setText("暂无歌词");
        def.addView(glassLyricView);

        playButton = buildBarButton(context, R.drawable.ic_music_pause);
        FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(ResUtil.dp2px(40), ResUtil.dp2px(40));
        playLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        playLp.rightMargin = ResUtil.dp2px(16);
        playButton.setLayoutParams(playLp);
        def.addView(playButton);
        glassBar.addView(def);
        barDefaultRow = def;

        barBackRow = new TextView(context);
        barBackRow.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        barBackRow.setGravity(Gravity.CENTER);
        barBackRow.setTextSize(13f);
        barBackRow.setTextColor(0xEBFFFFFF);
        barBackRow.setText("返回到封面模式");
        barBackRow.setVisibility(GONE);
        barBackRow.setClickable(true);
        barBackRow.setOnClickListener(v -> setExpanded(false));
        glassBar.addView(barBackRow);

        addView(spacer(BOTTOM_PAD));

        wireInteractions();
    }

    // buildExpandedColumn 先于字段赋值运行，用静态持有器回填引用
    private static class ExpandedRefs {
        TextView qualityChip;
        TextView lyricChip;
        TextView timeCurrentView;
        TextView timeTotalView;
        PlayerScrubberView scrubber;
        FrameLayout lyricSlot;
        LinearLayout slotLines;
        View infoCard;
        TextView infoTitleView;
        TextView infoSubView;
        TextView infoDetailView;
    }

    private final ExpandedRefs expandedRefs = new ExpandedRefs();

    private LinearLayout buildExpandedColumn(Context context) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(VERTICAL);
        col.setPadding(ResUtil.dp2px(18), ResUtil.dp2px(14), ResUtil.dp2px(18), 0);

        // 顶行双筹（§2：内垫 横12/竖5、字 11sp/700/字距 0.6sp、色 白 0.72、纯胶囊、按压 0.96）
        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(HORIZONTAL);
        chips.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        expandedRefs.qualityChip = buildChip(context);
        expandedRefs.qualityChip.setVisibility(GONE);
        expandedRefs.lyricChip = buildChip(context);
        expandedRefs.lyricChip.setText("译词");
        expandedRefs.lyricChip.setVisibility(GONE);
        LayoutParams chipLp2 = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        chipLp2.leftMargin = ResUtil.dp2px(10);
        expandedRefs.lyricChip.setLayoutParams(chipLp2);
        chips.addView(expandedRefs.qualityChip);
        chips.addView(expandedRefs.lyricChip);
        col.addView(chips);

        // Scrubber 行（§2：时间 11sp／宽 40dp／字距 0.2sp；容器 18dp）
        LinearLayout seekRow = new LinearLayout(context);
        seekRow.setOrientation(HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams seekLp = new LayoutParams(LayoutParams.MATCH_PARENT, ResUtil.dp2px(18));
        seekLp.topMargin = ResUtil.dp2px(12);
        seekRow.setLayoutParams(seekLp);
        expandedRefs.timeCurrentView = buildTimeText(context, Gravity.START);
        expandedRefs.scrubber = new PlayerScrubberView(context);
        LayoutParams scrubLp = new LayoutParams(0, ResUtil.dp2px(18));
        scrubLp.weight = 1f;
        scrubLp.leftMargin = ResUtil.dp2px(6);
        scrubLp.rightMargin = ResUtil.dp2px(6);
        expandedRefs.scrubber.setLayoutParams(scrubLp);
        expandedRefs.timeTotalView = buildTimeText(context, Gravity.END);
        seekRow.addView(expandedRefs.timeCurrentView);
        seekRow.addView(expandedRefs.scrubber);
        seekRow.addView(expandedRefs.timeTotalView);
        col.addView(seekRow);

        // 展开歌词槽（§4/§LyricsView：槽高＝间隙×3＋行高×4，行高＝字号×4/3、间隙 20dp）
        expandedRefs.lyricSlot = new FrameLayout(context);
        expandedRefs.lyricSlot.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        expandedRefs.slotLines = new LinearLayout(context);
        expandedRefs.slotLines.setOrientation(VERTICAL);
        expandedRefs.slotLines.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams slotLinesLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        slotLinesLp.gravity = Gravity.CENTER;
        expandedRefs.slotLines.setLayoutParams(slotLinesLp);
        expandedRefs.lyricSlot.addView(expandedRefs.slotLines);
        col.addView(expandedRefs.lyricSlot);

        // 信息卡（§4：标题 16sp/800/白 0.95；歌手·专辑 11sp/500/白 0.55；分区小标题 11sp/700/白 0.55/字距 0.5；卡底圆角 22＋黑 0.35）
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(VERTICAL);
        card.setBackgroundResource(R.drawable.bg_cover_info_card);
        card.setPadding(ResUtil.dp2px(16), ResUtil.dp2px(14), ResUtil.dp2px(16), ResUtil.dp2px(14));
        LayoutParams cardLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = ResUtil.dp2px(14);
        card.setLayoutParams(cardLp);
        expandedRefs.infoTitleView = new TextView(context);
        expandedRefs.infoTitleView.setTextSize(16f);
        expandedRefs.infoTitleView.setTextColor(0xF2FFFFFF);
        expandedRefs.infoTitleView.setTypeface(typeface(800));
        expandedRefs.infoTitleView.setSingleLine(true);
        expandedRefs.infoTitleView.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(expandedRefs.infoTitleView);
        expandedRefs.infoSubView = new TextView(context);
        expandedRefs.infoSubView.setTextSize(11f);
        expandedRefs.infoSubView.setTextColor(0x8CFFFFFF);
        expandedRefs.infoSubView.setTypeface(typeface(500));
        expandedRefs.infoSubView.setSingleLine(true);
        expandedRefs.infoSubView.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams subLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        subLp.topMargin = ResUtil.dp2px(4);
        expandedRefs.infoSubView.setLayoutParams(subLp);
        card.addView(expandedRefs.infoSubView);
        TextView section = new TextView(context);
        section.setText("歌曲信息");
        section.setTextSize(11f);
        section.setTextColor(0x8CFFFFFF);
        section.setTypeface(typeface(700));
        section.setLetterSpacing(0.5f / 11f);
        LayoutParams sectionLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        sectionLp.topMargin = ResUtil.dp2px(12);
        section.setLayoutParams(sectionLp);
        card.addView(section);
        expandedRefs.infoDetailView = new TextView(context);
        expandedRefs.infoDetailView.setTextSize(12f);
        expandedRefs.infoDetailView.setTextColor(0xD9FFFFFF);
        expandedRefs.infoDetailView.setLineSpacing(ResUtil.dp2px(3), 1f);
        LayoutParams detailLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = ResUtil.dp2px(6);
        expandedRefs.infoDetailView.setLayoutParams(detailLp);
        card.addView(expandedRefs.infoDetailView);
        card.setVisibility(GONE);
        expandedRefs.infoCard = card;
        col.addView(card);

        // 四玻璃键行（§4：42dp、按压 0.88、底白 0.14 椭圆）：上一首/队列/信息/下一首
        View spacer = new View(context);
        LayoutParams spLp = new LayoutParams(LayoutParams.MATCH_PARENT, 0);
        spLp.weight = 1f;
        spacer.setLayoutParams(spLp);
        col.addView(spacer);
        LinearLayout keys = new LinearLayout(context);
        keys.setOrientation(HORIZONTAL);
        keys.setGravity(Gravity.CENTER);
        keys.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, ResUtil.dp2px(42)));
        keys.addView(buildGlassKey(context, R.drawable.ic_music_prev, v -> {
            if (callback != null) callback.onPrev();
        }));
        keys.addView(buildGlassKey(context, R.drawable.ic_music_queue, v -> {
            if (callback != null) callback.onQueue();
        }));
        keys.addView(buildGlassKey(context, R.drawable.ic_music_more, v -> toggleInfo()));
        keys.addView(buildGlassKey(context, R.drawable.ic_music_next, v -> {
            if (callback != null) callback.onNext();
        }));
        col.addView(keys);
        return col;
    }

    private TextView buildChip(Context context) {
        TextView chip = new TextView(context);
        chip.setBackgroundResource(R.drawable.bg_player_glass_btn);
        chip.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(5), ResUtil.dp2px(12), ResUtil.dp2px(5));
        chip.setTextSize(11f);
        chip.setTextColor(0xB8FFFFFF);
        chip.setTypeface(typeface(700));
        chip.setLetterSpacing(0.6f / 11f);
        chip.setClickable(true);
        chip.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) bounce(v, 0.96f);
            if (ev.getActionMasked() == MotionEvent.ACTION_UP
                    || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) bounce(v, 1f);
            return false;
        });
        return chip;
    }

    private TextView buildTimeText(Context context, int gravity) {
        TextView t = new TextView(context);
        t.setLayoutParams(new LayoutParams(ResUtil.dp2px(40), LayoutParams.WRAP_CONTENT));
        t.setGravity(gravity);
        t.setTextSize(11f);
        t.setTextColor(Color.WHITE);
        t.setLetterSpacing(0.2f / 11f);
        t.setAlpha(0f);
        t.setText("0:00");
        return t;
    }

    private ImageView buildGlassKey(Context context, int icon, OnClickListener listener) {
        ImageView key = new ImageView(context);
        LayoutParams lp = new LayoutParams(ResUtil.dp2px(42), ResUtil.dp2px(42));
        lp.leftMargin = ResUtil.dp2px(14);
        lp.rightMargin = ResUtil.dp2px(14);
        key.setLayoutParams(lp);
        key.setBackgroundResource(R.drawable.bg_player_glass_btn);
        key.setImageResource(icon);
        key.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        key.setPadding(ResUtil.dp2px(11), ResUtil.dp2px(11), ResUtil.dp2px(11), ResUtil.dp2px(11));
        key.setClickable(true);
        key.setOnClickListener(listener);
        key.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) bounce(v, 0.88f);
            if (ev.getActionMasked() == MotionEvent.ACTION_UP
                    || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) bounce(v, 1f);
            return false;
        });
        return key;
    }

    private ImageView buildBarButton(Context context, int icon) {
        ImageView btn = new ImageView(context);
        btn.setImageResource(icon);
        btn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        btn.setPadding(ResUtil.dp2px(10), ResUtil.dp2px(10), ResUtil.dp2px(10), ResUtil.dp2px(10));
        btn.setClickable(true);
        btn.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) bounce(v, 0.88f);
            if (ev.getActionMasked() == MotionEvent.ACTION_UP
                    || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) bounce(v, 1f);
            return false;
        });
        return btn;
    }

    private View spacer(int dp) {
        View v = new View(getContext());
        v.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, ResUtil.dp2px(dp)));
        return v;
    }

    static Typeface typeface(int weight) {
        if (Build.VERSION.SDK_INT >= 28) return Typeface.create(Typeface.DEFAULT, weight, false);
        return weight >= 700 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
    }

    static void bounce(View v, float scale) {
        v.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(150L)
                .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f))
                .start();
    }

    private void wireInteractions() {
        stack.setCallback(new CoverStackView.Callback() {
            @Override
            public void onTapCover() {
                if (!expanded) setExpanded(true);
            }

            @Override
            public void onDoubleTapCover(float x, float y) {
                stack.pulse();
                burstHearts(x, y, !favorite);
                if (callback != null) callback.onFavoriteClick();
            }

            @Override
            public void onSwipeCommit(int dir) {
                if (callback == null) return;
                if (dir == CoverStackView.DIR_NEXT) callback.onNext();
                else callback.onPrev();
            }
        });
        favButton.setOnClickListener(v -> {
            if (callback != null) callback.onFavoriteClick();
        });
        playButton.setOnClickListener(v -> {
            if (callback != null) callback.onTogglePlay();
        });
        qualityChip.setOnClickListener(v -> {
            if (callback != null) callback.onQualityClick();
        });
        lyricChip.setOnClickListener(v -> {
            showTranslation = !showTranslation;
            lyricChip.setAlpha(showTranslation ? 1f : 0.55f);
            if (callback != null) callback.onLyricChipToggle(showTranslation);
        });
        scrubber.setListener(new PlayerScrubberView.Listener() {
            @Override
            public void onDraggingChanged(boolean dragging) {
                float a = dragging ? 0.72f : 0f;
                timeCurrentView.animate().alpha(a).setDuration(150L).start();
                timeTotalView.animate().alpha(a).setDuration(150L).start();
            }

            @Override
            public void onSeek(float fraction) {
                if (callback != null && durationMs > 0) callback.onSeekMs((long) (fraction * durationMs));
            }
        });
    }

    // ================= 宿主写入面 =================

    @Override
    public void setCallback(PlayerThemeFace.Callback callback) {
        this.callback = callback;
    }

    /** 封面卡无竖滑切歌提示面（横滑走叠卡），空实现。 */
    @Override
    public void setSwipeHints(String prevTitle, String nextTitle) {
    }

    public CoverStackView stack() {
        return stack;
    }

    public void setTrack(String title, String artist, String album) {
        titleView.setText(title == null ? "" : title);
        artistView.setText(artist == null ? "" : artist);
        infoTitleView.setText(title == null ? "" : title);
        StringBuilder sub = new StringBuilder();
        if (!TextUtils.isEmpty(artist)) sub.append(artist);
        if (!TextUtils.isEmpty(album)) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(album);
        }
        infoSubView.setText(sub.toString());
    }

    public void setInfoDetail(String detail) {
        infoDetailView.setText(detail == null ? "" : detail);
    }

    public void setCover(String url, String title, boolean animate, int dir) {
        stack.setCover(url, title, animate, dir);
    }

    public void setPlaying(boolean value) {
        playing = value;
        playButton.setImageResource(value ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
    }

    public boolean isPlaying() {
        return playing;
    }

    /** 收藏态（§4：alpha 已藏 1.0／未藏 0.85）。 */
    public void setFavorite(boolean value) {
        favorite = value;
        favButton.setImageResource(value ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
        favButton.setAlpha(value ? 1f : 0.85f);
    }

    public boolean isFavorite() {
        return favorite;
    }

    /** 音质筹：describe() 文案；null 时隐藏（测不到不显，他定案）。 */
    public void setQualityText(String text) {
        if (TextUtils.isEmpty(text)) {
            qualityChip.setVisibility(GONE);
        } else {
            qualityChip.setVisibility(VISIBLE);
            qualityChip.setText(text);
        }
    }

    public void setLyricChipState(boolean hasTranslation, boolean show) {
        showTranslation = show;
        lyricChip.setVisibility(hasTranslation ? VISIBLE : GONE);
        lyricChip.setAlpha(show ? 1f : 0.55f);
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean value) {
        if (expanded == value) return;
        expanded = value;
        stack.setGesturesEnabled(!value);
        // 展开/返回显隐动画 150ms（§2 显隐口径）：淡入淡出
        if (value) {
            stack.setVisibility(GONE);
            fxLayer.setVisibility(GONE);
            expandedColumn.setVisibility(VISIBLE);
            expandedColumn.setAlpha(0f);
            expandedColumn.animate().alpha(1f).setDuration(150L).start();
            barDefaultRow.setVisibility(GONE);
            barBackRow.setVisibility(VISIBLE);
        } else {
            expandedColumn.setVisibility(GONE);
            stack.setVisibility(VISIBLE);
            fxLayer.setVisibility(VISIBLE);
            barDefaultRow.setVisibility(VISIBLE);
            barBackRow.setVisibility(GONE);
            if (infoShown) toggleInfo();
        }
    }

    private void toggleInfo() {
        infoShown = !infoShown;
        infoCard.setVisibility(infoShown ? VISIBLE : GONE);
        lyricSlot.setVisibility(infoShown ? GONE : VISIBLE);
    }

    /** 进度（宿主 tick 推入；拖动中 Scrubber 自守预览值）。 */
    public void setProgress(long positionMs, long totalMs) {
        durationMs = totalMs;
        if (totalMs > 0) scrubber.setFraction((float) positionMs / (float) totalMs);
        timeCurrentView.setText(formatTime(positionMs));
        timeTotalView.setText(formatTime(totalMs));
    }

    // ================= 歌词（本步最小落地） =================

    /** 歌词换批：lines=null 且 loading=true＝加载中；lines 空＝暂无歌词。 */
    public void setLyrics(List<RichLyrics.Line> lines, boolean loading) {
        lyricLines = lines;
        lyricLoading = loading;
        lyricIndex = -2;
        mainLines = null;
        boolean hasTrans = false;
        if (lines != null) {
            mainLines = new ArrayList<>();
            for (RichLyrics.Line l : lines) {
                if (l == null) continue;
                mainLines.add(l.main);
                if (!TextUtils.isEmpty(l.translation)) hasTrans = true;
            }
        }
        showTranslation = MusicPlayerSettings.isShowTranslation();
        setLyricChipState(hasTrans, showTranslation);
        refreshLyricViews(true);
    }

    /** 按播放位置推进当前行（行定位走 LyricsParser.findLine＋设置偏移，与既有口径一致）。 */
    public void advanceLyric(long positionMs) {
        if (mainLines == null || mainLines.isEmpty()) return;
        int idx = LyricsParser.findLine(mainLines, positionMs + MusicPlayerSettings.getLyricOffsetMs());
        setLyricIndex(idx);
    }

    /** 当前行推进（宿主 tick 按 findLine 结果推入）。 */
    public void setLyricIndex(int index) {
        if (index == lyricIndex) return;
        lyricIndex = index;
        refreshLyricViews(false);
    }

    private void refreshLyricViews(boolean force) {
        // 玻璃条单行（§4＋PlayerScreenKt.java:9062 文案）
        if (lyricLines == null) {
            glassLyricView.setText(lyricLoading ? "歌词加载中…" : "暂无歌词");
        } else if (lyricLines.isEmpty()) {
            glassLyricView.setText("暂无歌词");
        } else {
            int idx = Math.max(0, Math.min(lyricIndex, lyricLines.size() - 1));
            RichLyrics.Line line = lyricLines.get(idx);
            glassLyricView.setText(line != null && line.main != null ? line.main.getText() : "");
        }
        renderSlot();
    }

    private void renderSlot() {
        slotLines.removeAllViews();
        float fontSp = com.fongmi.android.tv.ui.music.MusicPlayerSettings.getCoverLyricFontSize();
        int lineHeightPx = (int) (ResUtil.sp2px((int) fontSp) * 4f / 3f);
        int gapPx = ResUtil.dp2px(20);
        // 槽高定式：间隙×3＋行高×4
        LayoutParams slotLp = (LayoutParams) lyricSlot.getLayoutParams();
        int slotH = gapPx * 3 + lineHeightPx * 4;
        if (slotLp.height != slotH) {
            slotLp.height = slotH;
            lyricSlot.setLayoutParams(slotLp);
        }
        if (lyricLines == null || lyricLines.isEmpty()) {
            TextView t = slotTextView(fontSp, lineHeightPx);
            t.setText(lyricLines == null && lyricLoading ? "歌词加载中…" : "暂无歌词");
            t.setTextColor(0x8CFFFFFF);
            slotLines.addView(t);
            return;
        }
        int cur = Math.max(0, Math.min(lyricIndex, lyricLines.size() - 1));
        int from = cur - 1;
        int to = cur + 2;
        for (int i = from; i <= to; i++) {
            TextView t = slotTextView(fontSp, lineHeightPx);
            if (i < 0 || i >= lyricLines.size()) {
                t.setText("");
            } else {
                RichLyrics.Line line = lyricLines.get(i);
                t.setText(line != null && line.main != null ? line.main.getText() : "");
            }
            // 行色 §7.1 深色面回落：当前行 EAEAEA、非当前 白 0.30
            t.setTextColor(i == cur ? 0xFFEAEAEA : 0x4DFFFFFF);
            if (i > from) {
                LayoutParams lp = (LayoutParams) t.getLayoutParams();
                lp.topMargin = gapPx;
                t.setLayoutParams(lp);
            }
            slotLines.addView(t);
        }
    }

    private TextView slotTextView(float fontSp, int lineHeightPx) {
        TextView t = new TextView(getContext());
        t.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, lineHeightPx));
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextSize(fontSp);
        // 字重档→值（§7：0→700／1→800／2→900，默认 700）
        int weightTier = MusicPlayerSettings.getLyricWeight();
        t.setTypeface(typeface(weightTier == 1 ? 800 : weightTier == 2 ? 900 : 700));
        t.getPaint().setTextSkewX(-0.12f);
        return t;
    }

    // ================= 双击爱心爆发（§1 参数） =================

    private void burstHearts(float x, float y, boolean favoriting) {
        if (fxLayer.getWidth() <= 0) return;
        Random random = new Random();
        // 涟漪：0→1 800ms
        RippleView ripple = new RippleView(getContext());
        ripple.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ripple.cx = x;
        ripple.cy = y;
        ripple.maxRadius = stack.getWidth() * 0.38f;
        fxLayer.addView(ripple);
        ValueAnimator rippleAnim = ValueAnimator.ofFloat(0f, 1f);
        rippleAnim.setDuration(800L);
        rippleAnim.addUpdateListener(a -> {
            ripple.progress = (float) a.getAnimatedValue();
            ripple.invalidate();
        });
        rippleAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                fxLayer.removeView(ripple);
            }
        });
        rippleAnim.start();
        // 爱心：收藏 8 颗／取消 5 颗；角度 随机×120−60°、飞行 随机×48+42dp、
        // 尺寸 随机×10+9dp、旋转 随机×50−25°、延迟 随机×0.08＋序×0.035
        int count = favoriting ? 8 : 5;
        List<HeartSpec> specs = new ArrayList<>();
        List<ImageView> hearts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            HeartSpec s = new HeartSpec();
            s.angleDeg = random.nextFloat() * 120f - 60f;
            s.distancePx = ResUtil.dp2px((int) (random.nextFloat() * 48f + 42f));
            s.sizePx = ResUtil.dp2px((int) (random.nextFloat() * 10f + 9f));
            s.rotation = random.nextFloat() * 50f - 25f;
            s.delayMs = (long) ((random.nextFloat() * 0.08f + i * 0.035f) * 1000f);
            specs.add(s);
            ImageView heart = new ImageView(getContext());
            heart.setImageResource(R.drawable.ic_music_heart_fill);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s.sizePx, s.sizePx);
            heart.setLayoutParams(lp);
            heart.setX(x - s.sizePx / 2f);
            heart.setY(y - s.sizePx / 2f);
            heart.setAlpha(0f);
            fxLayer.addView(heart);
            hearts.add(heart);
        }
        long total = 1100L;
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(total);
        anim.addUpdateListener(a -> {
            long elapsed = (long) ((float) a.getAnimatedValue() * total);
            for (int i = 0; i < hearts.size(); i++) {
                HeartSpec s = specs.get(i);
                ImageView heart = hearts.get(i);
                float t = (elapsed - s.delayMs) / 700f;
                if (t < 0f) {
                    heart.setAlpha(0f);
                    continue;
                }
                t = Math.min(1f, t);
                double rad = Math.toRadians(s.angleDeg);
                float dx = (float) Math.sin(rad) * s.distancePx * t;
                float dy = (float) -Math.cos(rad) * s.distancePx * t;
                heart.setX(x - s.sizePx / 2f + dx);
                heart.setY(y - s.sizePx / 2f + dy);
                heart.setRotation(s.rotation * t);
                float alpha = t < 0.15f ? t / 0.15f : (t > 0.7f ? (1f - t) / 0.3f : 1f);
                heart.setAlpha(Math.max(0f, alpha));
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                for (ImageView h : hearts) fxLayer.removeView(h);
            }
        });
        anim.start();
    }

    private static class HeartSpec {
        float angleDeg;
        float distancePx;
        int sizePx;
        float rotation;
        long delayMs;
    }

    private static class RippleView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx;
        float cy;
        float maxRadius;
        float progress;

        RippleView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ResUtil.dp2px(2));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (progress <= 0f || maxRadius <= 0f) return;
            paint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, Math.round((1f - progress) * 0.5f * 255f)));
            canvas.drawCircle(cx, cy, maxRadius * progress, paint);
        }
    }

    static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    /** 供宿主引用 LyricsLine 文本（避免宿主再解析一遍原始行类型时走错口）。 */
    static String lineText(LyricsLine line) {
        return line == null ? "" : line.getText();
    }
}
