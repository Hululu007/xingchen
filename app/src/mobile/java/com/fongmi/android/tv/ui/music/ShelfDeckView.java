package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.utils.ImgUtil;

public class ShelfDeckView extends View {

    public interface Callback {
        void onTapCurrentRecord();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groovePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap prevBitmap;
    private Bitmap currentBitmap;
    private Bitmap nextBitmap;
    private String prevKey;
    private String currentKey;
    private String nextKey;
    private boolean playing;
    private float spinDeg;
    private boolean frameRunning;
    private long lastFrameNs;
    private float reveal = 1f;
    private Callback callback;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!frameRunning) return;
            step(frameTimeNanos);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public ShelfDeckView(Context context) {
        super(context);
        groovePaint.setStyle(Paint.Style.STROKE);
        setOnClickListener(v -> {
            if (callback != null) callback.onTapCurrentRecord();
        });
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setCovers(String prev, String current, String next) {
        prevKey = prev;
        currentKey = current;
        nextKey = next;
        load(prev, 0);
        load(current, 1);
        load(next, 2);
    }

    private void load(String url, int slot) {
        if (url == null || url.isEmpty()) {
            setBitmap(slot, null);
            return;
        }
        Glide.with(getContext()).asBitmap().load(ImgUtil.getUrl(url)).override(720, 720)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource,
                                                @Nullable Transition<? super Bitmap> transition) {
                        String key = slot == 0 ? prevKey : slot == 1 ? currentKey : nextKey;
                        if (!url.equals(key)) return;
                        setBitmap(slot, resource);
                        invalidate();
                    }

                    @Override
                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                    }
                });
    }

    private void setBitmap(int slot, Bitmap bitmap) {
        if (slot == 0) prevBitmap = bitmap;
        else if (slot == 1) currentBitmap = bitmap;
        else nextBitmap = bitmap;
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        updateRunning();
    }

    public void pulseReveal() {
        reveal = 0f;
        android.animation.ValueAnimator out = android.animation.ValueAnimator.ofFloat(0f, 1f);
        out.setDuration(600L);
        out.addUpdateListener(a -> {
            reveal = (float) a.getAnimatedValue();
            invalidate();
        });
        out.start();
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
        spinDeg = (spinDeg + 30f * dt) % 360f;
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
        super.onDetachedFromWindow();
    }

    private float dpF(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        float density = getResources().getDisplayMetrics().density;
        float fDp = Math.max(180f, Math.min(300f, (w / density) * 0.66f));
        int h = Math.round((fDp - 23f) * density);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        if (w <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        float f = Math.max(180f, Math.min(300f, (w / density) * 0.66f)) * density;
        float f2 = Math.min(w * 0.5f, f - 36f * density);
        float f3 = f2 * 0.95f;
        float centerX = w / 2f;
        float top = (getHeight() - f3) / 2f;
        float currentW = 1.48f * f3;
        float slotGap = f3 * 0.22f;

        float prevCx = centerX - currentW / 2f - slotGap - f3 / 2f;
        drawRecord(canvas, prevCx, top + f3 / 2f, f3, prevBitmap);
        float nextCx = centerX + currentW / 2f + slotGap + f3 / 2f;
        drawRecord(canvas, nextCx, top + f3 / 2f, f3, nextBitmap);

        float sleeveLeft = centerX - currentW / 2f;
        float discCx = sleeveLeft + f3 + (currentW - f3) * (0.4f + 0.6f * reveal) - f3 / 2f
                + f3 / 2f;
        discCx = sleeveLeft + f3 / 2f + (currentW - f3) * (0.35f + 0.65f * reveal);
        drawRecord(canvas, discCx, top + f3 / 2f, f3, currentBitmap);
        if (currentBitmap != null) {
            canvas.drawBitmap(currentBitmap, null,
                    new android.graphics.RectF(sleeveLeft, top, sleeveLeft + f3, top + f3), paint);
        } else {
            paint.setColor(0xFFDDD8CF);
            canvas.drawRect(sleeveLeft, top, sleeveLeft + f3, top + f3, paint);
        }
        paint.setColor(0xFF252522);
        float boardW = 1.1f * f3;
        canvas.drawRect(centerX - boardW / 2f, top + f3 + dpF(4), centerX + boardW / 2f,
                top + f3 + dpF(10), paint);
    }

    private void drawRecord(Canvas canvas, float cx, float cy, float f3, Bitmap cover) {
        float discR = 0.97f * f3 / 2f;
        paint.setColor(0xFF111111);
        canvas.drawCircle(cx, cy, discR, paint);
        groovePaint.setColor(Color.argb(60, 255, 255, 255));
        groovePaint.setStrokeWidth(Math.max(1f, f3 * 0.004f));
        canvas.drawCircle(cx, cy, discR * 0.82f, groovePaint);
        canvas.drawCircle(cx, cy, discR * 0.68f, groovePaint);
        canvas.drawCircle(cx, cy, discR * 0.54f, groovePaint);
        float centerR = 0.22f * f3 / 2f;
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(spinDeg);
        if (cover != null) {
            Path clip = new Path();
            clip.addCircle(0, 0, centerR, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(cover, null, new android.graphics.RectF(-centerR, -centerR, centerR, centerR), paint);
            canvas.restore();
        } else {
            paint.setColor(0xFF2A2A2A);
            canvas.drawCircle(0, 0, centerR, paint);
        }
        canvas.restore();
        paint.setColor(0xFFD4D0C5);
        canvas.drawCircle(cx, cy, 0.065f * f3 / 2f, paint);
        paint.setColor(0xFF080808);
        canvas.drawCircle(cx, cy, 0.025f * f3 / 2f, paint);
    }
}
