package com.fongmi.android.tv.ui.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Choreographer;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.utils.ImgUtil;

public class CdDiscView extends View {

    private Bitmap coverBitmap;
    private String coverKey;
    private float spinDeg;
    private boolean playing;
    private boolean frameRunning;
    private long lastFrameNs;
    private float sweepMix;
    private float sweepBase;
    private int sweepDir = 1;
    private ValueAnimator sweepAnimator;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!frameRunning) return;
            step(frameTimeNanos);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public CdDiscView(Context context) {
        super(context);
        ringPaint.setStyle(Paint.Style.STROKE);
    }

    public void setCover(String url, int direction) {
        if (url != null && url.equals(coverKey)) return;
        coverKey = url;
        if (url == null || url.isEmpty()) {
            coverBitmap = null;
            invalidate();
            return;
        }
        Glide.with(getContext()).asBitmap().load(ImgUtil.getUrl(url))
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource,
                                                @Nullable Transition<? super Bitmap> transition) {
                        if (!url.equals(coverKey)) return;
                        coverBitmap = resource;
                        startSweep(direction);
                        invalidate();
                    }

                    @Override
                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                    }
                });
    }

    private void startSweep(int direction) {
        sweepDir = direction <= 0 ? -1 : 1;
        if (sweepAnimator != null) sweepAnimator.cancel();
        sweepAnimator = ValueAnimator.ofFloat(0f, 1f);
        sweepAnimator.setDuration(600L);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            Path path = new Path();
            path.moveTo(0f, 0f);
            path.lineTo(0.05f, 0f);
            path.lineTo(0.133f, 0.06f);
            path.lineTo(0.167f, 0.4f);
            path.lineTo(0.208f, 0.82f);
            path.lineTo(0.25f, 1f);
            path.lineTo(1f, 1f);
            sweepAnimator.setInterpolator(new PathInterpolator(path));
        }
        sweepAnimator.addUpdateListener(a -> {
            sweepMix = (float) a.getAnimatedValue();
            invalidate();
        });
        sweepAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                sweepBase = wrapDeg(sweepBase + sweepDir * 90f);
                sweepMix = 0f;
                invalidate();
            }
        });
        sweepAnimator.start();
    }

    private float wrapDeg(float deg) {
        float d = deg % 360f;
        return d < 0 ? d + 360f : d;
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        updateRunning();
    }

    private void updateRunning() {
        boolean need = playing && isAttachedToWindow();
        if (need && !frameRunning) {
            frameRunning = true;
            lastFrameNs = 0;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else if (!need) {
            frameRunning = false;
        }
    }

    private void step(long frameTimeNanos) {
        float dt;
        if (lastFrameNs == 0) dt = 0.016f;
        else dt = (frameTimeNanos - lastFrameNs) / 1_000_000_000f;
        lastFrameNs = frameTimeNanos;
        dt = Math.max(0f, Math.min(0.05f, dt));
        spinDeg = wrapDeg(spinDeg + 16f * dt);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        frameRunning = false;
        if (sweepAnimator != null) sweepAnimator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        float f = Math.min(Math.min(w, h), 400f * density);
        float cx = w / 2f;
        float cy = h / 2f;
        float art = f * 0.89285713f;
        float corner = art * 0.05f;
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(spinDeg);
        RectF artRect = new RectF(-art / 2f, -art / 2f, art / 2f, art / 2f);
        if (coverBitmap != null) {
            Path clip = new Path();
            clip.addRoundRect(artRect, corner, corner, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(coverBitmap, null, artRect, paint);
            canvas.restore();
        } else {
            paint.setColor(0xFF2A2A2E);
            canvas.drawRoundRect(artRect, corner, corner, paint);
        }
        float ringScale = art / (112f * density);
        ringPaint.setColor(Color.argb(Math.round(255f * 0.18f), 255, 255, 255));
        ringPaint.setStrokeWidth(Math.max(1f, 4f * ringScale));
        canvas.drawCircle(0, 0, art * 0.36f, ringPaint);
        ringPaint.setStrokeWidth(Math.max(1f, 0.6f * ringScale * 2f));
        canvas.drawCircle(0, 0, art * 0.30f, ringPaint);
        float centerR = art * 0.19f;
        if (coverBitmap != null) {
            Path centerClip = new Path();
            centerClip.addCircle(0, 0, centerR, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(centerClip);
            canvas.drawBitmap(coverBitmap, null, new RectF(-centerR, -centerR, centerR, centerR), paint);
            canvas.restore();
        }
        paint.setColor(0xFF101014);
        canvas.drawCircle(0, 0, art * 0.035f, paint);
        canvas.restore();

        if (sweepMix > 0f && sweepMix < 1f) {
            float presence = (float) Math.max(0, Math.sin(sweepMix * Math.PI)) * 0.65f;
            float angle = sweepBase + sweepMix * sweepDir * 90f;
            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(angle);
            sweepPaint.setColor(Color.argb(Math.round(255f * presence * 0.5f), 255, 255, 255));
            canvas.drawRect(-2f, -art / 2f, 2f, art / 2f, sweepPaint);
            canvas.restore();
        }
    }
}
