package com.fongmi.android.tv.ui.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.RichLyrics;

import java.util.List;

/**
 * 经典主题（照瓜子经典竖屏构图复刻）：封面（横拖切歌＋双击收藏爱心爆发）→歌名行
 * （18sp/800/白.95＋右侧三键：菜单 21/队列 20/播停 28·25）→歌手行（14sp/白.45＋爱心 13）
 * →页内歌词（封面歌词字号、行距 20）→间隔 6→进度条→底部 45。
 * 数值出处：player-redo-blueprint 经典节＋PS 竖屏拼装段亲读。
 */
public class ClassicThemeView extends FrameLayout implements PlayerThemeFace {

    private final float density;
    private PlayerThemeFace.Callback callback;

    private ImageView coverView;
    private TextView hintView;
    private TextView titleView;
    private TextView artistView;
    private ImageView heartView;
    private ImageView playIcon;
    private ImageView pauseIcon;
    private LyricEngineView lyricView;
    private PlayerScrubberView scrubber;
    private FrameLayout burstLayer;

    private boolean playing;
    private boolean favorite;
    private String qualityText = "";
    private String lyricChipLabel = "";
    private boolean lyricHasWords;
    private String prevTitle;
    private String nextTitle;

    private float dragX;
    private float dragY;
    private float dragOffset;
    private boolean dragLocked;
    private boolean dragAxisH;
    private long lastTapTime;
    private float lastTapX;
    private float lastTapY;

    public ClassicThemeView(Context context) {
        this(context, null);
    }

    public ClassicThemeView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        build(context);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private void build(Context c) {
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(28), dp(6), dp(28), 0);
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        FrameLayout coverBox = new FrameLayout(c);
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f);
        column.addView(coverBox, coverLp);
        coverView = new ImageView(c);
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverView.setPivotX(0.5f);
        coverBox.addView(coverView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        burstLayer = new FrameLayout(c);
        coverBox.addView(burstLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        hintView = GzViews.text(c, "", 13, 600, 0xE6FFFFFF);
        hintView.setVisibility(INVISIBLE);
        hintView.setShadowLayer(dp(6), 0, 0, 0x66000000);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hintLp.topMargin = dp(14);
        coverBox.addView(hintView, hintLp);
        coverBox.setOnTouchListener(this::onCoverTouch);

        LinearLayout titleRow = new LinearLayout(c);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleRowLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(28));
        titleRowLp.topMargin = dp(10);
        column.addView(titleRow, titleRowLp);
        titleView = GzViews.text(c, "", 18, 800, 0xF2FFFFFF);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        titleView.setMarqueeRepeatLimit(-1);
        titleView.setSelected(true);
        titleView.setLayoutParams(new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(titleView);

        FrameLayout menuBtn = iconButton(c, R.drawable.ic_gz_menu, 21, () -> {
            if (callback != null) callback.onOpenTools();
        });
        FrameLayout queueBtn = iconButton(c, R.drawable.ic_gz_queue, 20, () -> {
            if (callback != null) callback.onQueue();
        });
        FrameLayout playBtn = new FrameLayout(c);
        playBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(40)));
        playIcon = GzViews.icon(c, R.drawable.ic_gz_play, 28, 1f);
        pauseIcon = GzViews.icon(c, R.drawable.ic_gz_pause, 25, 1f);
        centerIn(playBtn, playIcon);
        centerIn(playBtn, pauseIcon);
        playBtn.setOnClickListener(v -> {
            if (callback != null) callback.onTogglePlay();
        });
        GzViews.pressScale(playBtn, 0.96f);
        titleRow.addView(menuBtn);
        titleRow.addView(queueBtn);
        titleRow.addView(playBtn);

        LinearLayout artistRow = new LinearLayout(c);
        artistRow.setOrientation(LinearLayout.HORIZONTAL);
        artistRow.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(artistRow, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(22)));
        artistView = GzViews.text(c, "", 14, 500, 0x73FFFFFF);
        artistView.setSingleLine(true);
        artistView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        artistRow.addView(artistView, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        heartView = new ImageView(c);
        heartView.setImageResource(R.drawable.ic_gz_heart);
        LinearLayout.LayoutParams heartLp = new LinearLayout.LayoutParams(dp(27), dp(22));
        heartView.setPadding(dp(7), dp(4), dp(7), dp(5));
        heartView.setLayoutParams(heartLp);
        heartView.setAlpha(0.30f);
        heartView.setOnClickListener(v -> {
            if (callback != null) callback.onFavoriteClick();
        });
        GzViews.pressScale(heartView, 0.85f);
        artistRow.addView(heartView);
        artistRow.setOnTouchListener(this::onMetaSwipe);

        lyricView = new LyricEngineView(c);
        LinearLayout.LayoutParams lyricLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(58));
        lyricLp.topMargin = dp(2);
        column.addView(lyricView, lyricLp);
        lyricView.setPalette(LyricEngineView.Palette.classic());
        lyricView.setListener((index, timeMs) -> {
            if (callback != null) callback.onSeekMs(timeMs);
        });
        lyricView.setOnTouchListener(this::onMetaSwipe);

        View spacer = new View(c);
        column.addView(spacer, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(6)));

        scrubber = new PlayerScrubberView(c);
        column.addView(scrubber, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        scrubber.setListener(new PlayerScrubberView.Listener() {
            @Override
            public void onScrubPreview(long positionMs) {
            }

            @Override
            public void onSeekDragEnd(long positionMs) {
                if (callback != null) callback.onSeekMs(positionMs);
                lyricView.resyncToCurrent();
            }

            @Override
            public void onSeekTap(long positionMs) {
                if (callback != null) callback.onSeekMs(positionMs);
                lyricView.resyncToCurrent();
            }

            @Override
            public void onQualityChipTap() {
                if (callback != null) callback.onQualityClick();
            }

            @Override
            public void onLyricChipTap() {
                if (callback != null) callback.onLyricChipToggle(!MusicStore.isShowTranslation());
            }
        });

        View bottom = new View(c);
        column.addView(bottom, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(45)));
    }

    private void centerIn(FrameLayout parent, View child) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        child.setLayoutParams(lp);
        parent.addView(child);
    }

    private FrameLayout iconButton(Context c, int res, float iconDp, Runnable action) {
        FrameLayout box = new FrameLayout(c);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        ImageView iv = GzViews.icon(c, res, iconDp, 1f);
        centerIn(box, iv);
        box.setOnClickListener(v -> action.run());
        GzViews.pressScale(box, 0.96f);
        return box;
    }

    private boolean onCoverTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragX = e.getX();
                dragY = e.getY();
                dragOffset = 0;
                dragLocked = false;
                dragAxisH = false;
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastTapTime < 300 && Math.abs(e.getX() - lastTapX) < dp(48) && Math.abs(e.getY() - lastTapY) < dp(48)) {
                    if (callback != null) callback.onFavoriteClick();
                    heartBurst(e.getX(), e.getY());
                    lastTapTime = 0;
                } else {
                    lastTapTime = now;
                    lastTapX = e.getX();
                    lastTapY = e.getY();
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getX() - dragX;
                float dy = e.getY() - dragY;
                if (!dragLocked && (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8))) {
                    dragLocked = true;
                    dragAxisH = Math.abs(dx) > Math.abs(dy) * 1.2f;
                }
                if (dragLocked && dragAxisH) {
                    dragOffset = dx;
                    applyDragVisual(dx);
                    String hint = dx < 0 ? (nextTitle == null ? "下一首" : "下一首 · " + nextTitle)
                            : (prevTitle == null ? "上一首" : "上一首 · " + prevTitle);
                    hintView.setText(hint);
                    hintView.setVisibility(Math.abs(dx) > dp(12) ? VISIBLE : INVISIBLE);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                hintView.setVisibility(INVISIBLE);
                if (dragLocked && dragAxisH) {
                    if (dragOffset <= -dp(36)) {
                        if (callback != null) callback.onNext();
                    } else if (dragOffset >= dp(36)) {
                        if (callback != null) callback.onPrev();
                    }
                }
                resetDragVisual();
                dragLocked = false;
                return true;
            default:
                return false;
        }
    }

    private void applyDragVisual(float dx) {
        float anchor = Math.max(dp(240), Math.min(dp(400), getHeight() * 0.42f));
        float v = Math.max(0f, Math.min(1f, Math.abs(dx) / anchor));
        coverView.setTranslationX(dx);
        coverView.setAlpha(Math.max(0f, Math.min(1f, 1f - 1.6f * v * v)));
        float s = 1f - 0.06f * v * v;
        coverView.setScaleX(s * playScale());
        coverView.setScaleY(s * playScale());
        coverView.setPivotX(coverView.getWidth() * 0.5f);
        coverView.setPivotY(coverView.getHeight() * 0.45f);
        coverView.setCameraDistance(8000f * density);
        coverView.setRotationY(-(dx / anchor) * 4.5f);
    }

    private void resetDragVisual() {
        coverView.animate().translationX(0f).alpha(1f).rotationY(0f)
                .setDuration(250).setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        float s = playScale();
        coverView.animate().scaleX(s).scaleY(s).setDuration(250).start();
    }

    private float playScale() {
        return playing ? 1f : 0.9f;
    }

    private boolean onMetaSwipe(View v, MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            dragX = e.getX();
            dragY = e.getY();
            return false;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            float dx = e.getX() - dragX;
            float dy = e.getY() - dragY;
            if (dx < -dp(48) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                if (callback != null) callback.onOpenLyricPage();
                return true;
            }
        }
        return false;
    }

    private void heartBurst(float x, float y) {
        for (int i = 0; i < 8; i++) {
            boolean big = i < 5;
            ImageView h = new ImageView(getContext());
            h.setImageResource(R.drawable.ic_gz_heart);
            h.setColorFilter(0xFFFA243C);
            int size = dp(big ? 18 : 12);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
            h.setLayoutParams(lp);
            h.setX(x - size / 2f);
            h.setY(y - size / 2f);
            burstLayer.addView(h);
            double angle = -Math.PI / 2 + (Math.random() - 0.5) * 1.6;
            float dist = dp(40) + (float) Math.random() * dp(50);
            h.animate().translationXBy((float) (Math.cos(angle) * dist))
                    .translationYBy((float) (Math.sin(angle) * dist) - dp(24))
                    .alpha(0f).setDuration(750)
                    .withEndAction(() -> burstLayer.removeView(h)).start();
        }
    }

    @Override
    public void setCallback(PlayerThemeFace.Callback callback) {
        this.callback = callback;
    }

    @Override
    public void setTrack(String title, String artist, String album) {
        titleView.setText(title == null ? "" : title);
        artistView.setText(artist == null ? "" : artist);
    }

    @Override
    public void setCover(String url, String title, boolean animate, int dir) {
        if (url == null || url.isEmpty()) {
            coverView.setImageDrawable(null);
            return;
        }
        Glide.with(getContext()).load(url).centerCrop().into(coverView);
        if (animate) {
            coverView.setAlpha(0f);
            coverView.setTranslationX(dir * dp(40));
            coverView.animate().alpha(1f).translationX(0f).setDuration(400)
                    .setInterpolator(new PathInterpolator(0.05f, 0.7f, 0.1f, 1f)).start();
        }
        float s = playScale();
        coverView.setScaleX(s);
        coverView.setScaleY(s);
    }

    @Override
    public void setPlaying(boolean playing) {
        this.playing = playing;
        playIcon.setVisibility(playing ? INVISIBLE : VISIBLE);
        pauseIcon.setVisibility(playing ? VISIBLE : INVISIBLE);
        float s = playScale();
        coverView.animate().scaleX(s).scaleY(s).setDuration(300).start();
    }

    @Override
    public void setMode(int mode) {
    }

    @Override
    public void setLyrics(List<RichLyrics.Line> lines, boolean loading) {
        lyricView.configure(MusicPlayerSettings.getCoverLyricFontSize(), 20f, 28f,
                true, MusicPlayerSettings.isAdvancedFx(), MusicStore.isShowTranslation(),
                MusicStore.isShowRomanization(), MusicStore.getLyricPrimary(),
                MusicStore.getLyricAlign(), MusicPlayerSettings.blurCoefficient());
        lyricView.setLines(lines);
    }

    @Override
    public void setLyricState(String chipLabel, boolean hasWords) {
        lyricChipLabel = chipLabel == null ? "" : chipLabel;
        lyricHasWords = hasWords;
        pushChips();
    }

    @Override
    public void setQualityText(String text) {
        qualityText = text == null ? "" : text;
        pushChips();
    }

    private void pushChips() {
        scrubber.setChips(qualityText, true, lyricChipLabel, true);
    }

    @Override
    public void setInfoDetail(String detail) {
    }

    @Override
    public void setSwipeHints(String prevTitle, String nextTitle) {
        this.prevTitle = prevTitle;
        this.nextTitle = nextTitle;
    }

    @Override
    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
        heartView.setAlpha(favorite ? 0.45f : 0.30f);
        heartView.setColorFilter(favorite ? 0xFFFA243C : Color.WHITE);
    }

    @Override
    public boolean isFavorite() {
        return favorite;
    }

    @Override
    public void setProgress(long positionMs, long durationMs) {
        scrubber.setProgress(positionMs, durationMs);
    }

    @Override
    public void advanceLyric(long positionMs) {
        lyricView.setPosition(positionMs + MusicStore.getLyricOffsetMs());
    }

    @Override
    public void setBottomStackExpanded(boolean expanded) {
        scrubber.setVisibility(expanded ? VISIBLE : INVISIBLE);
    }
}
