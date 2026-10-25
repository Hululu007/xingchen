package com.fongmi.android.tv.ui.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.List;

public class AppleThemeView extends LinearLayout implements PlayerThemeFace {

    private final ImageView coverView;
    private final TextView titleView;
    private final TextView artistView;
    private final TextView albumView;
    private final ImageView heartKey;
    private final ImageView playPauseView;
    private final PlayerScrubberView scrubber;
    private final VolumeLine volumeLine;
    private Callback callback;
    private boolean favorite;
    private boolean playing;
    private String coverUrl;
    private String qualityText;
    private String lyricChipLabel;

    public AppleThemeView(Context context) {
        super(context);
        setOrientation(VERTICAL);

        FrameLayout coverWrap = new FrameLayout(context);
        coverWrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addView(coverWrap);
        coverView = new ImageView(context);
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverWrap.addView(coverView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        GestureDetector gd = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (callback != null) callback.onFavoriteClick();
                return true;
            }
        });
        coverView.setOnTouchListener((v, event) -> {
            gd.onTouchEvent(event);
            return true;
        });

        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(VERTICAL);
        titleCol.setPadding(dpI(24), 0, dpI(24), 0);
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tcLp.topMargin = -dpI(12);
        addView(titleCol, tcLp);

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleCol.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(30)));
        titleView = new TextView(context);
        titleView.setTextSize(20f);
        titleView.setTypeface(PlayerViews.weight(800));
        titleView.setTextColor(PlayerViews.white(0.96f));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        titleView.setMarqueeRepeatLimit(-1);
        titleView.setSelected(true);
        titleView.setLetterSpacing(-0.4f / 20f);
        titleRow.addView(titleView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View spacerPre = new View(context);
        titleRow.addView(spacerPre, new LinearLayout.LayoutParams(dpI(8), 1));
        heartKey = glassKey(R.drawable.ic_gz_favorite_border);
        heartKey.setOnClickListener(v -> {
            if (callback != null) callback.onFavoriteClick();
        });
        titleRow.addView(heartKey);
        View spacerMid = new View(context);
        titleRow.addView(spacerMid, new LinearLayout.LayoutParams(dpI(12), 1));
        ImageView moreKey = glassKey(R.drawable.ic_gz_more);
        moreKey.setOnClickListener(v -> {
            if (callback != null) callback.onOpenTools();
        });
        titleRow.addView(moreKey);

        artistView = new TextView(context);
        artistView.setTextSize(14f);
        artistView.setTypeface(PlayerViews.weight(500));
        artistView.setTextColor(PlayerViews.white(0.6f));
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(22));
        artistLp.topMargin = dpI(5);
        titleCol.addView(artistView, artistLp);

        albumView = new TextView(context);
        albumView.setTextSize(12f);
        albumView.setTypeface(PlayerViews.weight(500));
        albumView.setTextColor(PlayerViews.white(0.42f));
        albumView.setSingleLine(true);
        albumView.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(albumView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(18)));

        scrubber = new PlayerScrubberView(context);
        scrubber.setConfig(PlayerScrubberView.Config.apple());
        scrubber.setPadding(dpI(24), 0, dpI(24), 0);
        addView(scrubber, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrubber.setCallback(pos -> {
            if (callback != null) callback.onSeekMs(pos);
        });

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(96));
        controlsLp.topMargin = -dpI(18);
        addView(controls, controlsLp);
        ImageView prevBtn = transportButton(R.drawable.ic_gz_prev, 56, 48, 42);
        prevBtn.setOnClickListener(v -> {
            if (callback != null) callback.onPrev();
        });
        controls.addView(prevBtn);
        addSpace(controls, 24);
        playPauseView = new ImageView(context);
        playPauseView.setImageResource(R.drawable.ic_gz_pause);
        playPauseView.setScaleType(ImageView.ScaleType.CENTER);
        FrameLayout ppWrap = new FrameLayout(context);
        ppWrap.setLayoutParams(new LinearLayout.LayoutParams(dpI(84), dpI(84)));
        FrameLayout.LayoutParams ppLp = new FrameLayout.LayoutParams(dpI(60), dpI(52.5f));
        ppLp.gravity = Gravity.CENTER;
        ppWrap.addView(playPauseView, ppLp);
        ppWrap.setOnClickListener(v -> {
            if (callback != null) callback.onTogglePlay();
        });
        PlayerViews.pressScale(ppWrap, 0.9f);
        controls.addView(ppWrap);
        addSpace(controls, 24);
        ImageView nextBtn = transportButton(R.drawable.ic_gz_next, 56, 48, 42);
        nextBtn.setOnClickListener(v -> {
            if (callback != null) callback.onNext();
        });
        controls.addView(nextBtn);

        addView(spacerView(6));
        LinearLayout volumeRow = new LinearLayout(context);
        volumeRow.setOrientation(HORIZONTAL);
        volumeRow.setGravity(Gravity.CENTER_VERTICAL);
        volumeRow.setPadding(dpI(24), 0, dpI(24), 0);
        addView(volumeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(32)));
        ImageView volLow = smallIcon(R.drawable.ic_gz_volume_low);
        volumeRow.addView(volLow);
        volumeLine = new VolumeLine(context);
        LinearLayout.LayoutParams vlLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        vlLp.leftMargin = dpI(10);
        vlLp.rightMargin = dpI(10);
        volumeRow.addView(volumeLine, vlLp);
        volumeLine.setOnVolumeChange(f -> {
            if (callback != null) callback.onVolume(f);
        });
        ImageView volHigh = smallIcon(R.drawable.ic_gz_volume_high);
        volumeRow.addView(volHigh);

        addView(spacerView(2));
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(52)));
        ImageView lyricBtn = actionButton(R.drawable.ic_gz_lyrics, 24);
        lyricBtn.setOnClickListener(v -> {
            if (callback != null) callback.onOpenLyricPage();
        });
        actions.addView(lyricBtn);
        addSpace(actions, 48);
        ImageView qualityBtn = actionButton(R.drawable.ic_gz_info, 24);
        qualityBtn.setOnClickListener(v -> {
            if (callback != null) callback.onQualityClick();
        });
        actions.addView(qualityBtn);
        addSpace(actions, 48);
        ImageView queueBtn = actionButton(R.drawable.ic_gz_queue, 28);
        queueBtn.setOnClickListener(v -> {
            if (callback != null) callback.onQueue();
        });
        actions.addView(queueBtn);
        addView(spacerView(22));
    }

    private void addSpace(LinearLayout parent, float dp) {
        View v = new View(getContext());
        parent.addView(v, new LinearLayout.LayoutParams(dpI(dp), 1));
    }

    private View spacerView(float dp) {
        View v = new View(getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(dp)));
        return v;
    }

    private ImageView glassKey(int res) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setAlpha(0.85f);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(PlayerViews.white(0.14f));
        iv.setBackground(bg);
        iv.setPadding(dpI(7), dpI(7), dpI(7), dpI(7));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dpI(34), dpI(34)));
        PlayerViews.pressScale(iv, 0.88f);
        return iv;
    }

    private ImageView transportButton(int res, int boxDp, int iconWDp, int iconHDp) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setAlpha(0.9f);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dpI(boxDp), dpI(boxDp)));
        iv.setPadding((dpI(boxDp) - dpI(iconWDp)) / 2, (dpI(boxDp) - dpI(iconHDp)) / 2,
                (dpI(boxDp) - dpI(iconWDp)) / 2, (dpI(boxDp) - dpI(iconHDp)) / 2);
        PlayerViews.pressScale(iv, 0.88f);
        return iv;
    }

    private ImageView smallIcon(int res) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setAlpha(0.6f);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dpI(20), dpI(20)));
        return iv;
    }

    private ImageView actionButton(int res, int iconDp) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setAlpha(0.5f);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dpI(44), dpI(44)));
        int pad = (dpI(44) - dpI(iconDp)) / 2;
        iv.setPadding(pad, pad, pad, pad);
        return iv;
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
        boolean validAlbum = !TextUtils.isEmpty(album) && !"未知专辑".equals(album);
        albumView.setText(validAlbum ? album : "");
        albumView.setVisibility(validAlbum ? VISIBLE : GONE);
    }

    @Override
    public void setCover(String coverUrl, String title, boolean animate, int direction) {
        if (coverUrl != null && coverUrl.equals(this.coverUrl)) return;
        this.coverUrl = coverUrl;
        if (coverUrl == null || coverUrl.isEmpty()) {
            Glide.with(coverView).clear(coverView);
            coverView.setImageDrawable(null);
            return;
        }
        Glide.with(coverView).load(ImgUtil.getUrl(coverUrl)).centerCrop().into(coverView);
        if (animate) {
            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(600L);
            anim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                float s = 0.97f + 0.03f * f;
                coverView.setScaleX(s);
                coverView.setScaleY(s);
                coverView.setAlpha(f);
            });
            anim.start();
        }
    }

    @Override
    public void setSwipeHints(String prevTitle, String nextTitle) {
    }

    @Override
    public void setPlaying(boolean playing) {
        this.playing = playing;
        playPauseView.setImageResource(playing ? R.drawable.ic_gz_pause : R.drawable.ic_gz_play);
        playPauseView.setAlpha(0.95f);
    }

    @Override
    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
        heartKey.setImageResource(favorite ? R.drawable.ic_gz_favorite : R.drawable.ic_gz_favorite_border);
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
    public void setLyrics(List<RichLyrics.Line> lines, boolean loading) {
    }

    @Override
    public void advanceLyric(long positionMs) {
    }

    @Override
    public void setQualityText(String text) {
        qualityText = text;
    }

    @Override
    public void setLyricState(String chipLabel, boolean wordByWord) {
        lyricChipLabel = chipLabel;
    }

    @Override
    public void setInfoDetail(String detail) {
    }

    @Override
    public void setMode(int mode) {
    }

    @Override
    public void setVolume(float fraction) {
        volumeLine.setFraction(fraction);
    }

    private static class VolumeLine extends View {
        interface OnVolumeChange {
            void onChange(float fraction);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float fraction = 0.5f;
        private OnVolumeChange listener;

        VolumeLine(Context context) {
            super(context);
        }

        void setOnVolumeChange(OnVolumeChange listener) {
            this.listener = listener;
        }

        void setFraction(float f) {
            fraction = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cy = getHeight() / 2f;
            float r = dpF(2.5f);
            paint.setColor(PlayerViews.white(0.25f));
            canvas.drawRoundRect(0, cy - r, getWidth(), cy + r, r, r, paint);
            paint.setColor(PlayerViews.white(0.85f));
            canvas.drawRoundRect(0, cy - r, getWidth() * fraction, cy + r, r, r, paint);
            float knobR = dpF(6);
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(Math.max(knobR, Math.min(getWidth() - knobR, getWidth() * fraction)), cy, knobR, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    float f = getWidth() <= 0 ? 0 : Math.max(0f, Math.min(1f, event.getX() / getWidth()));
                    setFraction(f);
                    if (listener != null) listener.onChange(f);
                    return true;
                case MotionEvent.ACTION_UP:
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private float dpF(float v) {
            return v * getResources().getDisplayMetrics().density;
        }
    }
}
