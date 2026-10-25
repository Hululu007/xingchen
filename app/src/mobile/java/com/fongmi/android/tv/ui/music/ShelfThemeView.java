package com.fongmi.android.tv.ui.music;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.List;

public class ShelfThemeView extends LinearLayout implements PlayerThemeFace {

    private static final int SURFACE = 0xFFF1F0ED;
    private static final int INK = 0xFF252522;
    private static final int MUTED = 0xFF86857E;

    private final TextView titleView;
    private final TextView artistView;
    private final ShelfDeckView deckView;
    private final GzLyricView lyricView;
    private final ShelfSeekView seekView;
    private final TextView elapsedView;
    private final TextView totalView;
    private final ImageView playPauseView;
    private final ImageView modeView;
    private final TextView modeBadge;
    private Callback callback;
    private boolean favorite;
    private boolean playing;
    private long lastPosition;
    private int h0Color = INK;
    private String prevCover;
    private String nextCover;
    private String currentCoverUrl;

    public ShelfThemeView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(SURFACE);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dpI(16), 0, dpI(16), 0);
        addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(64)));
        ImageView collapseKey = headerKey(R.drawable.ic_gz_collapse);
        collapseKey.setOnClickListener(v -> {
            if (getContext() instanceof Activity) ((Activity) getContext()).finish();
        });
        header.addView(collapseKey);
        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(VERTICAL);
        titleCol.setGravity(Gravity.CENTER);
        header.addView(titleCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleView = new TextView(context);
        titleView.setTextSize(15f);
        titleView.setTypeface(PlayerViews.weight(500));
        titleView.setTextColor(INK);
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        artistView = new TextView(context);
        artistView.setTextSize(11f);
        artistView.setTextColor(MUTED);
        artistView.setGravity(Gravity.CENTER);
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(artistView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView moreKey = headerKey(R.drawable.ic_gz_more);
        moreKey.setOnClickListener(v -> {
            if (callback != null) callback.onOpenTools();
        });
        header.addView(moreKey);

        deckView = new ShelfDeckView(context);
        addView(deckView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        deckView.setCallback(() -> {
            if (callback != null) callback.onFavoriteClick();
        });

        FrameLayout surface = new FrameLayout(context);
        GradientDrawable surfaceBg = new GradientDrawable();
        surfaceBg.setColor(Color.WHITE);
        surfaceBg.setCornerRadii(new float[]{0, 0, 0, 0, dpI(24), dpI(24), dpI(24), dpI(24)});
        surface.setBackground(surfaceBg);
        LinearLayout.LayoutParams surfaceLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        addView(surface, surfaceLp);

        View topShade = new View(context);
        GradientDrawable shade = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(Math.round(255f * 0.13f), 0, 0, 0),
                        Color.argb(Math.round(255f * 0.055f), 0, 0, 0), Color.TRANSPARENT});
        topShade.setBackground(shade);
        surface.addView(topShade, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dpI(36)));

        LinearLayout surfaceCol = new LinearLayout(context);
        surfaceCol.setOrientation(VERTICAL);
        surfaceCol.setPadding(dpI(16), 0, dpI(16), 0);
        surface.addView(surfaceCol, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        lyricView = new GzLyricView(context);
        lyricView.setMode(GzLyricView.MODE_EMBED);
        lyricView.applySpec(22f, 4f / 3f, 20f, 24f);
        surfaceCol.addView(lyricView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        lyricView.setOnLineSeek(t -> {
            if (callback != null) callback.onSeekMs(t);
        });

        LinearLayout progressBlock = new LinearLayout(context);
        progressBlock.setOrientation(VERTICAL);
        progressBlock.setPadding(dpI(12), 0, dpI(12), 0);
        surfaceCol.addView(progressBlock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(72)));
        seekView = new ShelfSeekView(context);
        progressBlock.addView(seekView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(48)));
        seekView.setOnSeek(pos -> {
            if (callback != null) callback.onSeekMs(pos);
        });
        LinearLayout timeRow = new LinearLayout(context);
        timeRow.setOrientation(HORIZONTAL);
        progressBlock.addView(timeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(24)));
        elapsedView = timeText();
        timeRow.addView(elapsedView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        totalView = timeText();
        totalView.setGravity(Gravity.END);
        timeRow.addView(totalView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dpI(20), 0, dpI(20), 0);
        addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(76)));
        FrameLayout modeWrap = new FrameLayout(context);
        modeWrap.setLayoutParams(new LinearLayout.LayoutParams(dpI(48), dpI(48)));
        modeView = new ImageView(context);
        modeView.setImageResource(R.drawable.ic_gz_repeat);
        modeView.setScaleType(ImageView.ScaleType.CENTER);
        modeView.setColorFilter(INK);
        modeWrap.addView(modeView, new FrameLayout.LayoutParams(dpI(48), dpI(48)));
        modeBadge = new TextView(context);
        modeBadge.setText("1");
        modeBadge.setTextSize(10f);
        modeBadge.setTypeface(PlayerViews.weight(800));
        modeBadge.setTextColor(INK);
        modeBadge.setVisibility(GONE);
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.gravity = Gravity.CENTER;
        modeWrap.addView(modeBadge, badgeLp);
        controls.addView(modeWrap);
        modeWrap.setOnClickListener(v -> {
            if (callback != null) callback.onModeClick();
        });
        PlayerViews.pressScale(modeWrap, 0.9f);
        controls.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        ImageView prevBtn = controlKey(R.drawable.ic_gz_prev, 32);
        prevBtn.setOnClickListener(v -> {
            if (callback != null) callback.onPrev();
        });
        controls.addView(prevBtn);
        controls.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        playPauseView = new ImageView(context);
        playPauseView.setImageResource(R.drawable.ic_gz_pause);
        playPauseView.setScaleType(ImageView.ScaleType.CENTER);
        playPauseView.setColorFilter(INK);
        playPauseView.setLayoutParams(new LinearLayout.LayoutParams(dpI(68), dpI(68)));
        playPauseView.setPadding(dpI(8), dpI(8), dpI(8), dpI(8));
        playPauseView.setOnClickListener(v -> {
            if (callback != null) callback.onTogglePlay();
        });
        PlayerViews.pressScale(playPauseView, 0.9f);
        controls.addView(playPauseView);
        controls.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        ImageView nextBtn = controlKey(R.drawable.ic_gz_next, 32);
        nextBtn.setOnClickListener(v -> {
            if (callback != null) callback.onNext();
        });
        controls.addView(nextBtn);
        controls.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        ImageView queueBtn = controlKey(R.drawable.ic_gz_queue, 24);
        queueBtn.setOnClickListener(v -> {
            if (callback != null) callback.onQueue();
        });
        controls.addView(queueBtn);

        applyLyricPalette();
    }

    private ImageView headerKey(int res) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setColorFilter(PlayerViews.withAlpha(INK, 0.9f));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dpI(48), dpI(48)));
        iv.setPadding(dpI(12), dpI(12), dpI(12), dpI(12));
        PlayerViews.pressScale(iv, 0.9f);
        return iv;
    }

    private ImageView controlKey(int res, int iconDp) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setColorFilter(INK);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dpI(48), dpI(48)));
        int pad = (dpI(48) - dpI(iconDp)) / 2;
        iv.setPadding(pad, pad, pad, pad);
        PlayerViews.pressScale(iv, 0.9f);
        return iv;
    }

    private TextView timeText() {
        TextView tv = new TextView(getContext());
        tv.setTextSize(10f);
        tv.setTextColor(MUTED);
        tv.setText("0:00");
        return tv;
    }

    private void applyLyricPalette() {
        lyricView.setPalette(new GzLyricLineView.Palette(
                h0Color,
                PlayerViews.withAlpha(h0Color, 0.48f),
                PlayerViews.withAlpha(MUTED, 0.72f),
                MUTED));
        seekView.setColors(INK, h0Color);
    }

    private void refreshTone(String url) {
        if (url == null || url.isEmpty()) return;
        Glide.with(getContext()).asBitmap().load(ImgUtil.getUrl(url)).override(64, 64)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource,
                                                @Nullable Transition<? super Bitmap> transition) {
                        if (!url.equals(currentCoverUrl)) return;
                        Palette.from(resource).generate(p -> {
                            if (p == null || !url.equals(currentCoverUrl)) return;
                            int tone = p.getDominantColor(0);
                            if (tone == 0) return;
                            int mixed = blend(tone, INK, 0.35f);
                            float[] hsl = new float[3];
                            ColorUtils.colorToHSL(mixed, hsl);
                            hsl[2] = 0.65f;
                            h0Color = ColorUtils.HSLToColor(hsl);
                            applyLyricPalette();
                        });
                    }

                    @Override
                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                    }
                });
    }

    private int blend(int from, int to, float f) {
        return Color.argb(
                Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f));
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
        currentCoverUrl = coverUrl;
        deckView.setCovers(prevCover, coverUrl, nextCover);
        refreshTone(coverUrl);
    }

    @Override
    public void setSwipeHints(String prevTitle, String nextTitle) {
    }

    public void setNeighborCovers(String prev, String next) {
        prevCover = prev;
        nextCover = next;
        deckView.setCovers(prevCover, currentCoverUrl, nextCover);
    }

    @Override
    public void setPlaying(boolean playing) {
        this.playing = playing;
        playPauseView.setImageResource(playing ? R.drawable.ic_gz_pause : R.drawable.ic_gz_play);
        playPauseView.setColorFilter(INK);
        deckView.setPlaying(playing);
        lyricView.setPlayback(lastPosition, playing);
    }

    @Override
    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
        if (favorite) deckView.pulseReveal();
    }

    @Override
    public boolean isFavorite() {
        return favorite;
    }

    @Override
    public void setProgress(long positionMs, long durationMs) {
        lastPosition = positionMs;
        seekView.setProgress(positionMs, durationMs);
        elapsedView.setText(PlayerScrubberView.formatTime(positionMs));
        totalView.setText(PlayerScrubberView.formatTime(durationMs));
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
    }

    @Override
    public void setLyricState(String chipLabel, boolean wordByWord) {
    }

    @Override
    public void setInfoDetail(String detail) {
    }

    @Override
    public void setMode(int mode) {
        modeBadge.setVisibility(mode == MusicQueue.MODE_REPEAT_ONE ? VISIBLE : GONE);
        modeView.setImageResource(mode == MusicQueue.MODE_SHUFFLE
                ? R.drawable.ic_gz_shuffle : R.drawable.ic_gz_repeat);
        modeView.setColorFilter(INK);
    }

    @Override
    public void setVolume(float fraction) {
    }

    private static class ShelfSeekView extends View {
        interface OnSeek {
            void onSeek(long positionMs);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long positionMs;
        private long durationMs;
        private int inkColor = INK;
        private int fillColor = INK;
        private boolean dragging;
        private float dragFraction;
        private OnSeek listener;

        ShelfSeekView(Context context) {
            super(context);
        }

        void setOnSeek(OnSeek listener) {
            this.listener = listener;
        }

        void setColors(int ink, int fill) {
            inkColor = ink;
            fillColor = blend(fill, ink, 0.65f);
            invalidate();
        }

        void setProgress(long position, long duration) {
            if (!dragging) {
                positionMs = position;
                durationMs = duration;
            }
            invalidate();
        }

        private int blend(int from, int to, float f) {
            return Color.argb(
                    Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f),
                    Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f),
                    Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f),
                    Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float cy = getHeight() / 2f;
            float lineH = 2f * density;
            float canvasH = 4f * density;
            float fraction = durationMs > 0
                    ? Math.max(0f, Math.min(1f, dragging ? dragFraction : positionMs / (float) durationMs))
                    : 0f;
            paint.setStrokeWidth(lineH);
            paint.setColor(PlayerViews.withAlpha(inkColor, 0.12f));
            canvas.drawLine(0, cy, getWidth(), cy, paint);
            paint.setColor(fillColor);
            canvas.drawLine(0, cy, getWidth() * fraction, cy, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (durationMs <= 0) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    dragging = true;
                    dragFraction = frac(event.getX());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    dragFraction = frac(event.getX());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    long target = Math.round(dragFraction * durationMs);
                    positionMs = target;
                    invalidate();
                    if (event.getActionMasked() == MotionEvent.ACTION_UP && listener != null) {
                        listener.onSeek(target);
                    }
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private float frac(float x) {
            return getWidth() <= 0 ? 0 : Math.max(0f, Math.min(1f, x / (float) getWidth()));
        }
    }
}
