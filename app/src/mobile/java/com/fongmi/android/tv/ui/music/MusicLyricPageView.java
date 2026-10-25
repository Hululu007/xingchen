package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.lyrics.LyricsLine;

import java.util.List;

/**
 * 全屏歌词页（整页，gz-full-parity-1025 §7.3 按实帧形态）：
 * 无页眉；CoverFadeLayout 封面居上 46%＋84dp 渐隐进取色面；
 * 歌名行压交界（歌名 16sp、歌手 13sp×0.55，歌手行由宿主拼好「歌手 ♡ 专辑」）；
 * 右键只留 ⏸ 与 Tr（⏸ 点击由宿主经 setOnPauseClickListener 接线，Tr 经 setOnTrClickListener）；
 * 正体 FullLyricView，正唱行顶部锚、副行只挂正唱行。
 * 布局数值：横垫 28dp（与全屏面同口径）；歌名行压在封面交界上（顶边在 46% 线上方 30dp 处起）。
 */
public class MusicLyricPageView extends FrameLayout {

    private static final float COVER_RATIO = 0.46f;
    private static final float TITLE_LIFT_DP = 30f;
    private static final float SIDE_PADDING_DP = 28f;

    private CoverFadeLayout coverFade;
    private LinearLayout titleBlock;
    private TextView titleView;
    private TextView artistView;
    private TextView pauseButton;
    private TextView trButton;
    private FullLyricView lyricView;

    private boolean built = false;
    private boolean playing = false;
    private int toneColor = 0xFF101418;

    // 样式暂存（宿主可能先给样式后给数据，两路都汇到 FullLyricView）。
    private boolean styleShowSub = true;

    public MusicLyricPageView(Context context) {
        super(context);
        build();
    }

    public MusicLyricPageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() > 0) {
            wireInflated();
        } else {
            build();
        }
    }

    private void build() {
        if (built) return;
        built = true;
        Context context = getContext();

        coverFade = new CoverFadeLayout(context);
        addView(coverFade, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        lyricView = new FullLyricView(context);
        int side = Math.round(SIDE_PADDING_DP * density());
        lyricView.setPadding(side, 0, side, 0);
        addView(lyricView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        titleBlock = new LinearLayout(context);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        artistView = new TextView(context);
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        artistView.setTextColor(0x8CFFFFFF); // 13sp × 白 0.55
        artistView.setSingleLine(true);
        artistView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBlock.addView(titleView);
        titleBlock.addView(artistView);
        addView(titleBlock);

        pauseButton = makeSideButton(context, "⏸");
        addView(pauseButton);
        trButton = makeSideButton(context, "Tr");
        addView(trButton);

        applyTone();
        updatePauseGlyph();
    }

    private TextView makeSideButton(Context context, String text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        button.setTextColor(0xFFFFFFFF);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        int size = Math.round(48f * density());
        button.setLayoutParams(new LayoutParams(size, size));
        return button;
    }

    private void wireInflated() {
        // 布局文件路径：按 id 认领子件；缺哪个补哪个，保证契约方法不空转。
        View fade = findViewById(R.id.musicLyricCoverFade);
        View full = findViewById(R.id.musicLyricFull);
        if (fade instanceof CoverFadeLayout && full instanceof FullLyricView
                && findViewById(R.id.musicLyricTitle) != null) {
            built = true;
            coverFade = (CoverFadeLayout) fade;
            lyricView = (FullLyricView) full;
            titleView = findViewById(R.id.musicLyricTitle);
            artistView = findViewById(R.id.musicLyricArtist);
            titleBlock = findViewById(R.id.musicLyricTitleBlock);
            pauseButton = findViewById(R.id.musicLyricBtnPause);
            trButton = findViewById(R.id.musicLyricBtnTr);
            applyTone();
            updatePauseGlyph();
        } else {
            removeAllViews();
            build();
        }
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    private void applyTone() {
        setBackgroundColor(toneColor);
        if (coverFade != null) coverFade.setToneColor(toneColor);
        if (lyricView != null) lyricView.setFadeColor(toneColor);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (!built) return;
        float density = density();
        int side = Math.round(SIDE_PADDING_DP * density);
        int junction = Math.round(h * COVER_RATIO);
        int titleTop = Math.max(0, junction - Math.round(TITLE_LIFT_DP * density));

        LayoutParams titleParams = (LayoutParams) titleBlock.getLayoutParams();
        titleParams.width = LayoutParams.MATCH_PARENT;
        titleParams.height = LayoutParams.WRAP_CONTENT;
        titleParams.leftMargin = side;
        // 右侧给 ⏸/Tr 两键让位。
        titleParams.rightMargin = side + Math.round(112f * density);
        titleParams.topMargin = titleTop;
        titleBlock.setLayoutParams(titleParams);

        int buttonSize = Math.round(48f * density);
        LayoutParams pauseParams = (LayoutParams) pauseButton.getLayoutParams();
        pauseParams.width = buttonSize;
        pauseParams.height = buttonSize;
        pauseParams.gravity = Gravity.TOP | Gravity.END;
        pauseParams.topMargin = titleTop;
        pauseParams.rightMargin = side + Math.round(52f * density);
        pauseButton.setLayoutParams(pauseParams);

        LayoutParams trParams = (LayoutParams) trButton.getLayoutParams();
        trParams.width = buttonSize;
        trParams.height = buttonSize;
        trParams.gravity = Gravity.TOP | Gravity.END;
        trParams.topMargin = titleTop;
        trParams.rightMargin = side;
        trButton.setLayoutParams(trParams);

        // 歌词正体从歌名行下方起，正唱行顶部锚在 FullLyricView 自身顶部。
        int lyricTop = titleTop + Math.round(64f * density);
        LayoutParams lyricParams = (LayoutParams) lyricView.getLayoutParams();
        lyricParams.width = LayoutParams.MATCH_PARENT;
        lyricParams.height = LayoutParams.MATCH_PARENT;
        lyricParams.topMargin = lyricTop;
        lyricView.setLayoutParams(lyricParams);
    }

    private void updatePauseGlyph() {
        if (pauseButton != null) pauseButton.setText(playing ? "⏸" : "▶");
    }

    // ---- 对外契约 ----

    public void setToneColor(int color) {
        this.toneColor = color;
        if (built) applyTone();
    }

    /** artistLine 由宿主拼好（「歌手 ♡ 专辑」，无专辑只给歌手）。 */
    public void setTrackInfo(String title, String artistLine) {
        if (!built) return;
        titleView.setText(title == null ? "" : title);
        artistView.setText(artistLine == null ? "" : artistLine);
    }

    public void setCover(String url, String title) {
        if (built) coverFade.setCover(url, title);
    }

    public void setCoverDrawable(Drawable drawable) {
        if (built) coverFade.setCoverDrawable(drawable);
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        if (!built) return;
        lyricView.setPlaying(playing);
        updatePauseGlyph();
    }

    public void setLyricData(List<LyricsLine> mains, List<String> subLines, boolean showSub) {
        if (!built) return;
        lyricView.setShowSub(showSub && styleShowSub);
        lyricView.setLines(mains, subLines);
    }

    public void applyLyricStyle(float fontSizeSp, int blurLevel, boolean advancedFx,
                                int weight, boolean showTrans, boolean showRoma) {
        if (!built) return;
        styleShowSub = showTrans || showRoma;
        lyricView.setFontSizeSp(fontSizeSp);
        lyricView.setBlurLevel(blurLevel);
        lyricView.setAdvancedFx(advancedFx);
        lyricView.setLyricWeight(weight);
        lyricView.setShowSub(styleShowSub);
    }

    public void setLyricPosition(long ms) {
        if (built) lyricView.setPosition(ms);
    }

    public void setEmptyText(String text) {
        if (built) lyricView.setEmptyText(text);
    }

    public void setOnLineClickListener(FullLyricView.OnLineClickListener listener) {
        if (built) lyricView.setOnLineClickListener(listener);
    }

    public void setOnTrClickListener(View.OnClickListener listener) {
        if (built) trButton.setOnClickListener(listener);
    }

    /** ⏸ 键点击转发（宿主接线：点按暂停/恢复）。 */
    public void setOnPauseClickListener(View.OnClickListener listener) {
        if (built) pauseButton.setOnClickListener(listener);
    }

    public FullLyricView getLyricView() {
        return lyricView;
    }
}
