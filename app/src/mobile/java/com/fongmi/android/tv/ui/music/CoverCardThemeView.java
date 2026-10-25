package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.music.RichLyrics;

import java.util.List;

public class CoverCardThemeView extends LinearLayout implements PlayerThemeFace {

    private final TextView titleView;
    private final TextView artistView;
    private final CoverCardView cardView;
    private final FrameLayout cardOverlay;
    private final GzLyricView overlayLyric;
    private final TextView infoText;
    private final PlayerScrubberView overlayScrubber;
    private final ImageView barHeart;
    private final TextView barLyric;
    private final ImageView barPlayPause;
    private final ImageView infoButton;
    private Callback callback;
    private boolean favorite;
    private boolean playing;
    private boolean expanded;
    private boolean infoMode;
    private String qualityText;
    private String lyricChipLabel;
    private List<RichLyrics.Line> lyricLines;
    private long lastPosition;
    private String infoDetail = "";

    public CoverCardThemeView(Context context) {
        super(context);
        setOrientation(VERTICAL);

        addView(spacer(42));
        titleView = new TextView(context);
        titleView.setTextSize(20f);
        titleView.setTypeface(PlayerViews.weight(800));
        titleView.setTextColor(PlayerViews.white(0.96f));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        titleView.setMarqueeRepeatLimit(-1);
        titleView.setSelected(true);
        titleView.setLetterSpacing(-0.4f / 20f);
        titleView.setPadding(dpI(28), 0, dpI(28), 0);
        addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(30)));
        addView(spacer(4));
        artistView = new TextView(context);
        artistView.setTextSize(14f);
        artistView.setTypeface(PlayerViews.weight(500));
        artistView.setTextColor(PlayerViews.white(0.65f));
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        artistView.setPadding(dpI(28), 0, dpI(28), 0);
        addView(artistView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(22)));
        addView(spacer(22));

        FrameLayout coverArea = new FrameLayout(context);
        coverArea.setPadding(dpI(24), 0, dpI(24), 0);
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        addView(coverArea, coverLp);
        cardView = new CoverCardView(context);
        coverArea.addView(cardView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        cardOverlay = new FrameLayout(context);
        cardOverlay.setPadding(dpI(18), dpI(14), dpI(18), dpI(14));
        cardOverlay.setVisibility(GONE);
        GradientDrawable overlayBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF3A3A3C, 0xFF1C1C1E});
        overlayBg.setCornerRadius(dpI(22));
        cardOverlay.setBackground(overlayBg);
        coverArea.addView(cardOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout overlayCol = new LinearLayout(context);
        overlayCol.setOrientation(VERTICAL);
        cardOverlay.addView(overlayCol, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlayLyric = new GzLyricView(context);
        overlayLyric.setMode(GzLyricView.MODE_EMBED);
        overlayLyric.setAlignOverride(1);
        overlayLyric.setPalette(new GzLyricLineView.Palette(
                0xFFEAEAEA, PlayerViews.white(0.42f), PlayerViews.white(0.30f), PlayerViews.white(0.55f)));
        overlayCol.addView(overlayLyric, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        overlayLyric.setOnLineSeek(t -> {
            if (callback != null) callback.onSeekMs(t);
        });
        infoText = new TextView(context);
        infoText.setTextSize(14f);
        infoText.setTextColor(PlayerViews.white(0.85f));
        infoText.setVisibility(GONE);
        overlayCol.addView(infoText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        overlayCol.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView prevBtn = glassButton(R.drawable.ic_gz_prev);
        prevBtn.setOnClickListener(v -> {
            if (callback != null) callback.onPrev();
        });
        buttons.addView(prevBtn);
        ImageView queueBtn = glassButton(R.drawable.ic_gz_queue);
        queueBtn.setOnClickListener(v -> {
            if (callback != null) callback.onQueue();
        });
        buttons.addView(queueBtn);
        infoButton = glassButton(R.drawable.ic_gz_info);
        infoButton.setOnClickListener(v -> setInfoMode(!infoMode));
        buttons.addView(infoButton);
        ImageView nextBtn = glassButton(R.drawable.ic_gz_next);
        nextBtn.setOnClickListener(v -> {
            if (callback != null) callback.onNext();
        });
        buttons.addView(nextBtn);

        overlayScrubber = new PlayerScrubberView(context);
        overlayScrubber.setConfig(PlayerScrubberView.Config.classic());
        LinearLayout.LayoutParams osLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        osLp.topMargin = dpI(12);
        overlayCol.addView(overlayScrubber, osLp);
        overlayScrubber.setCallback(pos -> {
            if (callback != null) callback.onSeekMs(pos);
        });

        addView(spacer(14));

        LinearLayout bar = buildGlassBar();
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(48));
        barLp.leftMargin = dpI(24);
        barLp.rightMargin = dpI(24);
        addView(bar, barLp);
        addView(spacer(12));

        cardView.setCallback(new CoverCardView.Callback() {
            @Override
            public void onSwipePrev() {
                if (callback != null) callback.onPrev();
            }

            @Override
            public void onSwipeNext() {
                if (callback != null) callback.onNext();
            }

            @Override
            public void onTapCard() {
                setExpanded(!expanded);
            }

            @Override
            public void onDoubleTapFavorite() {
                if (callback != null) callback.onFavoriteClick();
            }
        });
        barHeart = (ImageView) bar.getChildAt(0);
        barLyric = (TextView) ((FrameLayout) bar.getChildAt(1)).getChildAt(0);
        barPlayPause = (ImageView) bar.getChildAt(2);
    }

    private View spacer(float dp) {
        View v = new View(getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(dp)));
        return v;
    }

    private ImageView glassButton(int res) {
        FrameLayout wrap = new FrameLayout(getContext());
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(PlayerViews.white(0.14f));
        iv.setBackground(bg);
        iv.setPadding(dpI(10), dpI(10), dpI(10), dpI(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpI(42), dpI(42));
        lp.leftMargin = dpI(5);
        lp.rightMargin = dpI(5);
        iv.setLayoutParams(lp);
        PlayerViews.pressScale(iv, 0.92f);
        return iv;
    }

    private LinearLayout buildGlassBar() {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dpI(16), 0, dpI(16), 0);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{PlayerViews.white(0.26f), PlayerViews.white(0.08f)});
        bg.setCornerRadius(dpI(22));
        bg.setStroke(dpI(1), PlayerViews.white(0.12f));
        bg.setColor(0xFF1E1E22);
        bar.setBackground(bg);
        bar.setElevation(dpI(4));

        ImageView heart = new ImageView(getContext());
        heart.setImageResource(R.drawable.ic_gz_favorite_border);
        heart.setScaleType(ImageView.ScaleType.CENTER);
        heart.setLayoutParams(new LinearLayout.LayoutParams(dpI(40), dpI(40)));
        heart.setOnClickListener(v -> {
            if (callback != null) callback.onFavoriteClick();
        });
        bar.addView(heart);

        FrameLayout center = new FrameLayout(getContext());
        center.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        TextView lyric = new TextView(getContext());
        lyric.setTextSize(13f);
        lyric.setTypeface(PlayerViews.weight(500));
        lyric.setTextColor(PlayerViews.white(0.92f));
        lyric.setSingleLine(true);
        lyric.setEllipsize(TextUtils.TruncateAt.END);
        lyric.setGravity(Gravity.CENTER);
        center.addView(lyric, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        bar.addView(center);

        ImageView play = new ImageView(getContext());
        play.setImageResource(R.drawable.ic_gz_pause);
        play.setScaleType(ImageView.ScaleType.CENTER);
        play.setLayoutParams(new LinearLayout.LayoutParams(dpI(40), dpI(40)));
        play.setOnClickListener(v -> {
            if (callback != null) callback.onTogglePlay();
        });
        bar.addView(play);
        bar.setOnClickListener(v -> setExpanded(!expanded));
        return bar;
    }

    private void setExpanded(boolean value) {
        expanded = value;
        cardOverlay.setVisibility(value ? VISIBLE : GONE);
        barLyric.setText(value ? "返回到封面模式" : currentLineText());
    }

    private void setInfoMode(boolean value) {
        infoMode = value;
        infoText.setVisibility(value ? VISIBLE : GONE);
        overlayLyric.setVisibility(value ? GONE : VISIBLE);
        GradientDrawable bg = (GradientDrawable) infoButton.getBackground();
        bg.setColor(PlayerViews.white(value ? 0.28f : 0.14f));
    }

    private String currentLineText() {
        if (lyricLines == null || lyricLines.isEmpty()) return "暂无歌词";
        int idx = 0;
        for (int i = 0; i < lyricLines.size(); i++) {
            RichLyrics.Line l = lyricLines.get(i);
            if (l.main != null && l.main.getTimeMs() <= lastPosition) idx = i;
            else break;
        }
        RichLyrics.Line l = lyricLines.get(idx);
        String text = l.main == null ? "" : l.main.getText();
        return TextUtils.isEmpty(text) ? "暂无歌词" : text;
    }

    private int dpI(float v) {
        return PlayerViews.dpI(this, v);
    }

    @Override
    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    @Override
    public void setTrack(String title, String artist, String album) {
        titleView.setText(TextUtils.isEmpty(title) ? "未在播放" : title);
        artistView.setText(TextUtils.isEmpty(artist) ? "—" : artist);
    }

    @Override
    public void setCover(String coverUrl, String title, boolean animate, int direction) {
        cardView.setCover(coverUrl, animate, direction);
    }

    @Override
    public void setSwipeHints(String prevTitle, String nextTitle) {
    }

    public void setNeighbors(String prevTitle, String prevCover, String nextTitle, String nextCover) {
        cardView.setNeighbors(prevCover, nextCover);
    }

    @Override
    public void setPlaying(boolean playing) {
        this.playing = playing;
        barPlayPause.setImageResource(playing ? R.drawable.ic_gz_pause : R.drawable.ic_gz_play);
        overlayLyric.setPlayback(lastPosition, playing);
    }

    @Override
    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
        barHeart.setImageResource(favorite ? R.drawable.ic_gz_favorite : R.drawable.ic_gz_favorite_border);
        barHeart.setAlpha(favorite ? 1f : 0.45f);
    }

    @Override
    public boolean isFavorite() {
        return favorite;
    }

    @Override
    public void setProgress(long positionMs, long durationMs) {
        lastPosition = positionMs;
        overlayScrubber.setProgress(positionMs, durationMs);
        if (!expanded) barLyric.setText(currentLineText());
    }

    @Override
    public void setLyrics(List<RichLyrics.Line> lines, boolean loading) {
        lyricLines = lines;
        overlayLyric.setLyrics(lines, loading);
        if (!expanded) barLyric.setText(loading ? "歌词加载中…" : currentLineText());
        barLyric.setAlpha(lines == null || lines.isEmpty() ? 0.45f : 0.92f);
    }

    @Override
    public void advanceLyric(long positionMs) {
        lastPosition = positionMs;
        overlayLyric.setPlayback(positionMs, playing);
    }

    @Override
    public void setQualityText(String text) {
        qualityText = text;
        overlayScrubber.setChips(qualityText, lyricChipLabel);
    }

    @Override
    public void setLyricState(String chipLabel, boolean wordByWord) {
        lyricChipLabel = chipLabel;
        overlayScrubber.setChips(qualityText, lyricChipLabel);
    }

    @Override
    public void setInfoDetail(String detail) {
        infoDetail = detail == null ? "" : detail;
        infoText.setText(infoDetail);
    }

    @Override
    public void setMode(int mode) {
    }

    @Override
    public void setVolume(float fraction) {
    }
}
