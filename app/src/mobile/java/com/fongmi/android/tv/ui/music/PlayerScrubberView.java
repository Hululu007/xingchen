package com.fongmi.android.tv.ui.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class PlayerScrubberView extends LinearLayout {

    public interface Callback {
        void onSeekCommit(long positionMs);
    }

    public static class Config {
        public float trackDp = 4f;
        public float fillIdle = 0.42f;
        public float fillActive = 0.78f;
        public boolean topBox = true;
        public boolean bottomRow = true;
        public boolean bottomRowOnlyDragging;

        public static Config classic() {
            return new Config();
        }

        public static Config apple() {
            Config c = new Config();
            c.trackDp = 6f;
            c.fillIdle = 0.58f;
            c.fillActive = 0.9f;
            c.topBox = false;
            c.bottomRow = false;
            c.bottomRowOnlyDragging = true;
            return c;
        }
    }

    private final Context ctx;
    private Config config = new Config();
    private Callback callback;

    private FrameLayout topBoxView;
    private TextView topLeftTime;
    private TextView topRightTime;
    private LinearLayout chipRow;
    private TextView qualityChip;
    private TextView lyricChip;
    private TrackView trackView;
    private LinearLayout bottomRowView;
    private TextView bottomLeftTime;
    private TextView bottomRightTime;

    private long positionMs;
    private long durationMs;
    private boolean dragging;
    private float dragFraction;
    private float timeAlpha;
    private float chipsScale = 1f;
    private ValueAnimator stateAnimator;

    public PlayerScrubberView(Context context) {
        super(context);
        this.ctx = context;
        build();
    }

    public PlayerScrubberView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.ctx = context;
        build();
    }

    private void build() {
        setOrientation(VERTICAL);
        topBoxView = new FrameLayout(ctx);
        topBoxView.setPadding(0, 0, 0, dpI(4));
        LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(28));
        topBoxView.setLayoutParams(tbLp);

        topLeftTime = timeLabel(11f, 0f);
        FrameLayout.LayoutParams lLp = new FrameLayout.LayoutParams(dpI(40),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
        topBoxView.addView(topLeftTime, lLp);
        topRightTime = timeLabel(11f, 0f);
        FrameLayout.LayoutParams rLp = new FrameLayout.LayoutParams(dpI(40),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        topRightTime.setGravity(Gravity.END);
        topBoxView.addView(topRightTime, rLp);

        chipRow = new LinearLayout(ctx);
        chipRow.setOrientation(HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        topBoxView.addView(chipRow, cLp);
        qualityChip = makeChip();
        lyricChip = makeChip();
        chipRow.addView(qualityChip);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.leftMargin = dpI(6);
        lyricChip.setLayoutParams(chipLp);
        chipRow.addView(lyricChip);
        addView(topBoxView);

        trackView = new TrackView(ctx);
        trackView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(18)));
        addView(trackView);

        bottomRowView = new LinearLayout(ctx);
        bottomRowView.setOrientation(HORIZONTAL);
        bottomRowView.setPadding(dpI(2), dpI(2), dpI(2), 0);
        bottomLeftTime = timeLabel(12f, 0.65f);
        bottomRightTime = timeLabel(12f, 0.65f);
        bottomRightTime.setGravity(Gravity.END);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bottomRowView.addView(bottomLeftTime, bl);
        LinearLayout.LayoutParams br = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bottomRowView.addView(bottomRightTime, br);
        addView(bottomRowView);
        applyConfig();
    }

    private TextView timeLabel(float sp, float alpha) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(sp);
        tv.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        tv.setLetterSpacing(0.2f / sp);
        tv.setTextColor(Color.argb(Math.round(255f * alpha), 255, 255, 255));
        tv.setText("0:00");
        return tv;
    }

    private TextView makeChip() {
        TextView tv = new TextView(ctx);
        tv.setTextSize(11f);
        tv.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        tv.setLetterSpacing(0.6f / 11f);
        tv.setTextColor(Color.argb(Math.round(255f * 0.72f), 255, 255, 255));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dpI(12), dpI(5), dpI(12), dpI(5));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(Math.round(255f * 0.10f), 255, 255, 255),
                        Color.argb(Math.round(255f * 0.04f), 255, 255, 255)});
        bg.setColor(Color.argb(Math.round(255f * 0.14f), 255, 255, 255));
        bg.setCornerRadius(dpI(50));
        bg.setStroke(dpI(1), Color.argb(Math.round(255f * 0.10f), 255, 255, 255));
        tv.setBackground(bg);
        tv.setVisibility(GONE);
        return tv;
    }

    public void setConfig(Config config) {
        this.config = config == null ? new Config() : config;
        applyConfig();
    }

    private void applyConfig() {
        topBoxView.setVisibility(config.topBox ? VISIBLE : GONE);
        boolean showBottom = config.bottomRow || (config.bottomRowOnlyDragging && dragging);
        bottomRowView.setVisibility(showBottom ? VISIBLE : GONE);
        if (trackView != null) trackView.invalidate();
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setChips(String qualityText, String lyricText) {
        setChip(qualityChip, qualityText);
        setChip(lyricChip, lyricText);
    }

    private void setChip(TextView chip, String text) {
        if (text == null || text.isEmpty()) {
            chip.setVisibility(GONE);
        } else {
            chip.setText(text);
            chip.setVisibility(VISIBLE);
        }
    }

    public void setProgress(long positionMs, long durationMs) {
        if (!dragging) {
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }
        refreshLabels();
        trackView.invalidate();
    }

    private void refreshLabels() {
        long pos = dragging ? Math.round(dragFraction * Math.max(0, durationMs)) : positionMs;
        String left = formatTime(pos);
        String rightTotal = formatTime(durationMs);
        String rightRemain = "-" + formatTime(Math.max(0, durationMs - pos));
        topLeftTime.setText(left);
        topRightTime.setText(rightTotal);
        bottomLeftTime.setText(left);
        bottomRightTime.setText(rightRemain);
        int topAlpha = Math.round(255f * 0.72f * timeAlpha);
        topLeftTime.setTextColor(Color.argb(topAlpha, 255, 255, 255));
        topRightTime.setTextColor(Color.argb(topAlpha, 255, 255, 255));
        if (config.bottomRowOnlyDragging) {
            int a = Math.round(255f * 0.65f * timeAlpha);
            bottomLeftTime.setTextColor(Color.argb(a, 255, 255, 255));
            bottomRightTime.setTextColor(Color.argb(a, 255, 255, 255));
        }
    }

    public static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000L;
        long min = totalSec / 60L;
        long sec = totalSec % 60L;
        return min + ":" + (sec < 10 ? "0" : "") + sec;
    }

    private void setDragging(boolean value) {
        if (dragging == value) return;
        dragging = value;
        if (stateAnimator != null) stateAnimator.cancel();
        final float fromAlpha = timeAlpha;
        final float toAlpha = value ? 1f : 0f;
        final float fromScale = chipsScale;
        final float toScale = value ? 0.51f : 1f;
        stateAnimator = ValueAnimator.ofFloat(0f, 1f);
        stateAnimator.setDuration(150L);
        stateAnimator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            timeAlpha = fromAlpha + (toAlpha - fromAlpha) * f;
            chipsScale = fromScale + (toScale - fromScale) * f;
            chipRow.setScaleX(chipsScale);
            chipRow.setScaleY(chipsScale);
            refreshLabels();
            trackView.invalidate();
        });
        stateAnimator.start();
        applyConfig();
    }

    private int dpI(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private class TrackView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        TrackView(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float trackH = (config.trackDp + (dragging ? 1f : 0f)) * density;
            float cy = getHeight() / 2f;
            float left = 0f;
            float right = getWidth();
            float fraction = durationMs > 0
                    ? Math.max(0f, Math.min(1f, (dragging ? dragFraction
                    : positionMs / (float) durationMs))) : 0f;
            float idleAlpha = dragging ? 0.22f : 0.14f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.round(255f * idleAlpha), 255, 255, 255));
            rect.set(left, cy - trackH / 2f, right, cy + trackH / 2f);
            canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint);
            float fillRight = left + (right - left) * fraction;
            if (fillRight > left) {
                float a0 = config.fillIdle * 0.72f;
                float a1 = dragging ? config.fillActive : config.fillIdle;
                LinearGradient gradient = new LinearGradient(left, 0, Math.max(fillRight, left + 1), 0,
                        Color.argb(Math.round(255f * a0), 255, 255, 255),
                        Color.argb(Math.round(255f * a1), 255, 255, 255),
                        Shader.TileMode.CLAMP);
                paint.setShader(gradient);
                rect.set(left, cy - trackH / 2f, fillRight, cy + trackH / 2f);
                canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint);
                paint.setShader(null);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (durationMs <= 0) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    setDragging(true);
                    dragFraction = fractionFor(event.getX());
                    refreshLabels();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    dragFraction = fractionFor(event.getX());
                    refreshLabels();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    long target = Math.round(dragFraction * durationMs);
                    setDragging(false);
                    positionMs = target;
                    refreshLabels();
                    invalidate();
                    if (event.getActionMasked() == MotionEvent.ACTION_UP && callback != null) {
                        callback.onSeekCommit(target);
                    }
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private float fractionFor(float x) {
            if (getWidth() <= 0) return 0f;
            return Math.max(0f, Math.min(1f, x / (float) getWidth()));
        }
    }
}
