package com.fongmi.android.tv.ui.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.RichLyrics;

import java.util.List;

public class ClassicThemeView extends LinearLayout implements PlayerThemeFace {

    private final CoverSwipeView coverView;
    private final TextView titleView;
    private final TextView artistView;
    private final TextView albumView;
    private final ImageView heartView;
    private final ImageView playPauseView;
    private final FrameLayout playPauseBox;
    private final GzLyricView lyricView;
    private final PlayerScrubberView scrubber;
    private final LinearLayout metaBlock;
    private Callback callback;
    private boolean favorite;
    private boolean playing;
    private String qualityText;
    private String lyricChipLabel;
    private float downX;
    private float downY;
    private boolean lyricSwipeFired;

    public ClassicThemeView(Context context) {
        super(context);
        setOrientation(VERTICAL);

        FrameLayout coverWrap = new FrameLayout(context);
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        coverWrap.setLayoutParams(coverLp);
        coverView = new CoverSwipeView(context);
        coverWrap.addView(coverView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        addView(coverWrap);

        metaBlock = new LinearLayout(context);
        metaBlock.setOrientation(VERTICAL);
        metaBlock.setPadding(dpI(28), 0, dpI(28), 0);
        addView(metaBlock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(28));
        metaBlock.addView(titleRow, titleRowLp);

        titleView = new TextView(context);
        titleView.setTextSize(18f);
        titleView.setTypeface(PlayerViews.weight(800));
        titleView.setTextColor(PlayerViews.white(0.95f));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        titleView.setMarqueeRepeatLimit(-1);
        titleView.setSelected(true);
        titleView.setLetterSpacing(-0.4f / 18f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(titleView, titleLp);

        LinearLayout keys = new LinearLayout(context);
        keys.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams keysLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        keysLp.setMarginStart(dpI(8));
        titleRow.addView(keys, keysLp);
        ImageView menuKey = keyIcon(R.drawable.ic_gz_menu, 21f);
        menuKey.setOnClickListener(v -> {
            if (callback != null) callback.onOpenTools();
        });
        keys.addView(keyBox(menuKey));
        ImageView queueKey = keyIcon(R.drawable.ic_gz_queue, 20f);
        queueKey.setOnClickListener(v -> {
            if (callback != null) callback.onQueue();
        });
        keys.addView(keyBox(queueKey));
        playPauseBox = new FrameLayout(context);
        playPauseBox.setLayoutParams(new LinearLayout.LayoutParams(dpI(40), dpI(40)));
        playPauseView = new ImageView(context);
        playPauseView.setImageResource(R.drawable.ic_gz_pause);
        FrameLayout.LayoutParams ppLp = new FrameLayout.LayoutParams(dpI(28), dpI(28));
        ppLp.gravity = Gravity.CENTER;
        playPauseBox.addView(playPauseView, ppLp);
        playPauseBox.setOnClickListener(v -> {
            if (callback != null) callback.onTogglePlay();
        });
        keys.addView(playPauseBox);

        LinearLayout secondRow = new LinearLayout(context);
        secondRow.setOrientation(HORIZONTAL);
        secondRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams secondLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(22));
        secondLp.topMargin = dpI(2);
        metaBlock.addView(secondRow, secondLp);

        artistView = new TextView(context);
        artistView.setTextSize(14f);
        artistView.setTypeface(PlayerViews.weight(500));
        artistView.setTextColor(PlayerViews.white(0.45f));
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        artistView.setMarqueeRepeatLimit(-1);
        artistView.setSelected(true);
        secondRow.addView(artistView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        heartView = new ImageView(context);
        heartView.setImageResource(R.drawable.ic_gz_favorite_border);
        heartView.setPadding(dpI(7), 0, dpI(7), 0);
        LinearLayout.LayoutParams heartLp = new LinearLayout.LayoutParams(dpI(27), dpI(13));
        heartLp.leftMargin = dpI(2);
        secondRow.addView(heartView, heartLp);
        heartView.setOnClickListener(v -> {
            if (callback != null) callback.onFavoriteClick();
        });
        PlayerViews.pressScale(heartView, 0.85f);

        albumView = new TextView(context);
        albumView.setTextSize(14f);
        albumView.setTypeface(PlayerViews.weight(500));
        albumView.setTextColor(PlayerViews.white(0.45f));
        albumView.setSingleLine(true);
        albumView.setEllipsize(TextUtils.TruncateAt.END);
        secondRow.addView(albumView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        lyricView = new GzLyricView(context);
        lyricView.setMode(GzLyricView.MODE_EMBED);
        lyricView.setPalette(new GzLyricLineView.Palette(
                0xFFEAEAEA, PlayerViews.white(0.42f), PlayerViews.white(0.30f), PlayerViews.white(0.55f)));
        float coverFontPx = PlayerViews.sp(this, MusicStore.getCoverLyricFontSize());
        int slotHeight = Math.round(dpI(20) * 3f + coverFontPx * (4f / 3f) * 4f);
        LinearLayout.LayoutParams lyricLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, slotHeight);
        addView(lyricView, lyricLp);
        lyricView.setOnLineSeek(timeMs -> {
            if (callback != null) callback.onSeekMs(timeMs);
        });

        scrubber = new PlayerScrubberView(context);
        scrubber.setConfig(PlayerScrubberView.Config.classic());
        scrubber.setPadding(dpI(28), 0, dpI(28), 0);
        LinearLayout.LayoutParams scrubLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrubLp.topMargin = dpI(6);
        addView(scrubber, scrubLp);
        scrubber.setCallback(pos -> {
            if (callback != null) callback.onSeekMs(pos);
        });

        View bottomSpacer = new View(context);
        addView(bottomSpacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(45)));

        coverView.setCallback(new CoverSwipeView.Callback() {
            @Override
            public void onSwipePrev() {
                if (callback != null) callback.onPrev();
            }

            @Override
            public void onSwipeNext() {
                if (callback != null) callback.onNext();
            }

            @Override
            public void onDoubleTapFavorite() {
                if (callback != null) callback.onFavoriteClick();
            }
        });
    }

    private ImageView keyIcon(int res, float sizeDp) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        int s = dpI(sizeDp);
        iv.setLayoutParams(new FrameLayout.LayoutParams(s, s));
        return iv;
    }

    private FrameLayout keyBox(ImageView icon) {
        FrameLayout box = new FrameLayout(getContext());
        box.setLayoutParams(new LinearLayout.LayoutParams(dpI(40), dpI(40)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        box.addView(icon, lp);
        box.setOnClickListener(v -> icon.performClick());
        return box;
    }

    private int dpI(float v) {
        return PlayerViews.dpI(this, v);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                lyricSwipeFired = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!lyricSwipeFired) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (dx < -dpI(80) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        lyricSwipeFired = true;
                        if (callback != null) callback.onOpenLyricPage();
                    }
                }
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    @Override
    public void setTrack(String title, String artist, String album) {
        ValueAnimator fade = ValueAnimator.ofFloat(1f, 0f);
        fade.setDuration(150L);
        fade.addUpdateListener(a -> metaBlock.setAlpha((float) a.getAnimatedValue()));
        fade.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                titleView.setText(TextUtils.isEmpty(title) ? "未在播放" : title);
                artistView.setText(TextUtils.isEmpty(artist) ? "—" : artist);
                boolean hasAlbum = !TextUtils.isEmpty(album);
                albumView.setText(hasAlbum ? album : "");
                albumView.setVisibility(hasAlbum ? VISIBLE : GONE);
                ValueAnimator in = ValueAnimator.ofFloat(0f, 1f);
                in.setDuration(200L);
                in.addUpdateListener(a -> metaBlock.setAlpha((float) a.getAnimatedValue()));
                in.start();
            }
        });
        fade.start();
    }

    @Override
    public void setCover(String coverUrl, String title, boolean animate, int direction) {
        coverView.setCover(coverUrl, animate, direction);
    }

    @Override
    public void setSwipeHints(String prevTitle, String nextTitle) {
    }

    public void setNeighbors(String prevTitle, String prevCover, String nextTitle, String nextCover) {
        coverView.setNeighbors(prevTitle, prevCover, nextTitle, nextCover);
    }

    @Override
    public void setPlaying(boolean playing) {
        this.playing = playing;
        playPauseView.setImageResource(playing ? R.drawable.ic_gz_pause : R.drawable.ic_gz_play);
        int targetDp = playing ? 28 : 25;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) playPauseView.getLayoutParams();
        if (lp.width != dpI(targetDp)) {
            ValueAnimator anim = ValueAnimator.ofInt(lp.width, dpI(targetDp));
            anim.setDuration(300L);
            anim.addUpdateListener(a -> {
                int v = (int) a.getAnimatedValue();
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) playPauseView.getLayoutParams();
                p.width = v;
                p.height = v;
                playPauseView.setLayoutParams(p);
            });
            anim.start();
        }
        coverView.setPlaying(playing);
        lyricView.setPlayback(lastPosition, playing);
    }

    private long lastPosition;
    private long lastDuration;

    @Override
    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
        heartView.setImageResource(favorite
                ? R.drawable.ic_gz_favorite : R.drawable.ic_gz_favorite_border);
        float target = favorite ? 0.45f : 0.30f;
        heartView.setAlpha(target);
    }

    @Override
    public boolean isFavorite() {
        return favorite;
    }

    @Override
    public void setProgress(long positionMs, long durationMs) {
        lastPosition = positionMs;
        lastDuration = durationMs;
        scrubber.setProgress(positionMs, durationMs);
    }

    @Override
    public void setLyrics(List<RichLyrics.Line> lines, boolean loading) {
        lyricView.setLyrics(lines, loading);
    }

    @Override
    public void advanceLyric(long positionMs) {
        lastPosition = positionMs;
        lyricView.setPlayback(positionMs, playing);
    }

    @Override
    public void setQualityText(String text) {
        qualityText = text;
        scrubber.setChips(qualityText, lyricChipLabel);
    }

    @Override
    public void setLyricState(String chipLabel, boolean wordByWord) {
        lyricChipLabel = chipLabel;
        scrubber.setChips(qualityText, lyricChipLabel);
    }

    @Override
    public void setInfoDetail(String detail) {
    }

    @Override
    public void setMode(int mode) {
    }

    @Override
    public void setVolume(float fraction) {
    }
}
