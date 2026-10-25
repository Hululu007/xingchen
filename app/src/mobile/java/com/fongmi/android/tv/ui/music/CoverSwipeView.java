package com.fongmi.android.tv.ui.music;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class CoverSwipeView extends FrameLayout {

    public interface Callback {
        void onSwipePrev();

        void onSwipeNext();

        void onDoubleTapFavorite();
    }

    public static final int DIR_NEXT = 1;
    public static final int DIR_PREV = -1;

    private final ImageView currentImage;
    private final ImageView incomingImage;
    private final TextView hintTop;
    private final TextView hintBottom;
    private final BurstView burstView;
    private final GestureDetector gestureDetector;
    private Callback callback;

    private String currentUrl;
    private String prevUrl;
    private String nextUrl;
    private String prevTitle;
    private String nextTitle;
    private boolean playing = true;
    private float dragDy;
    private boolean dragging;
    private boolean axisLocked;
    private float downX;
    private float downY;
    private VelocityTracker velocityTracker;
    private boolean hapticFired;

    public CoverSwipeView(Context context) {
        super(context);
        currentImage = new ImageView(context);
        currentImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        incomingImage = new ImageView(context);
        incomingImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        incomingImage.setVisibility(GONE);
        addView(incomingImage, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(currentImage, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        hintTop = makeHint();
        hintBottom = makeHint();
        LayoutParams ht = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        ht.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        ht.topMargin = dpI(10);
        addView(hintTop, ht);
        LayoutParams hb = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        hb.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        hb.bottomMargin = dpI(10);
        addView(hintBottom, hb);
        burstView = new BurstView(context);
        addView(burstView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                burstView.burst();
                if (callback != null) callback.onDoubleTapFavorite();
                return true;
            }
        });
        setClipChildren(true);
    }

    private TextView makeHint() {
        TextView tv = new TextView(getContext());
        tv.setTextColor(Color.argb(Math.round(255f * 0.85f), 255, 255, 255));
        tv.setTextSize(13f);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setVisibility(GONE);
        return tv;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setNeighbors(String prevTitle, String prevCover, String nextTitle, String nextCover) {
        this.prevTitle = prevTitle;
        this.nextTitle = nextTitle;
        this.prevUrl = prevCover;
        this.nextUrl = nextCover;
    }

    public void setCover(String url, boolean animate, int direction) {
        if (url != null && url.equals(currentUrl)) return;
        currentUrl = url;
        if (animate && getWidth() > 0) {
            playEnterTransition(direction);
        }
        loadInto(currentImage, url);
        currentImage.setTranslationY(0);
        currentImage.setAlpha(1f);
        currentImage.setScaleX(1f);
        currentImage.setScaleY(1f);
        applyPlayScale(false);
    }

    private void playEnterTransition(int direction) {
        float h = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
        final float fromY = 0.5f * h * (direction == DIR_NEXT ? 1f : -1f);
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(400L);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            anim.setInterpolator(new PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f));
        }
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            currentImage.setTranslationY(fromY * (1f - f));
            float s = 0.94f + 0.06f * f;
            currentImage.setScaleX(s * playScale());
            currentImage.setScaleY(s * playScale());
            currentImage.setAlpha(f);
        });
        anim.start();
    }

    private float playScale() {
        return playing ? 1f : 0.9f;
    }

    private void applyPlayScale(boolean animate) {
        float target = playScale();
        if (animate) {
            currentImage.animate().scaleX(target).scaleY(target).setDuration(300L).start();
        } else {
            currentImage.setScaleX(target);
            currentImage.setScaleY(target);
        }
    }

    public void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        this.playing = playing;
        applyPlayScale(true);
    }

    private void loadInto(ImageView iv, String url) {
        if (url == null || url.isEmpty()) {
            Glide.with(iv).clear(iv);
            iv.setImageDrawable(null);
            return;
        }
        Glide.with(iv).load(ImgUtil.getUrl(url)).centerCrop().into(iv);
    }

    private int dpI(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                axisLocked = false;
                hapticFired = false;
                dragDy = 0;
                cancelSettle();
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (velocityTracker != null) velocityTracker.addMovement(event);
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!axisLocked) {
                    if (Math.abs(dy) > dpI(4) || Math.abs(dx) > dpI(4)) {
                        axisLocked = Math.abs(dy) > Math.abs(dx) * 1.5f;
                        dragging = axisLocked;
                        if (dragging) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (dragging) {
                    float anchorDist = anchorDistance();
                    dragDy = Math.max(-anchorDist, Math.min(anchorDist, dy));
                    applyDrag(dragDy, anchorDist);
                    float trigger = dpI(36);
                    if (!hapticFired && Math.abs(dragDy) >= trigger) {
                        hapticFired = true;
                        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                    } else if (Math.abs(dragDy) < trigger) {
                        hapticFired = false;
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (velocityTracker != null) {
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000);
                }
                float vy = velocityTracker == null ? 0 : velocityTracker.getYVelocity();
                if (velocityTracker != null) velocityTracker.recycle();
                velocityTracker = null;
                if (dragging) {
                    float trigger = dpI(36);
                    float fling = 600f * getResources().getDisplayMetrics().density;
                    int commit = 0;
                    if (Math.abs(dragDy) >= trigger) commit = dragDy < 0 ? DIR_NEXT : DIR_PREV;
                    else if (Math.abs(vy) >= fling) commit = vy < 0 ? DIR_NEXT : DIR_PREV;
                    if (commit != 0) commitSwipe(commit);
                    else settleBack();
                }
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private float anchorDistance() {
        float density = getResources().getDisplayMetrics().density;
        float screenDp = getResources().getDisplayMetrics().heightPixels / density;
        return Math.max(240f, Math.min(400f, screenDp * 0.42f)) * density;
    }

    private void applyDrag(float dy, float anchorDist) {
        currentImage.setTranslationY(dy);
        boolean next = dy < 0;
        String url = next ? nextUrl : prevUrl;
        String title = next ? nextTitle : prevTitle;
        if (url != null) {
            if (incomingImage.getVisibility() != VISIBLE) {
                loadInto(incomingImage, url);
                incomingImage.setVisibility(VISIBLE);
            }
            float inFrom = anchorDist * (next ? 1f : -1f);
            incomingImage.setTranslationY(inFrom + dy);
        }
        TextView hint = next ? hintBottom : hintTop;
        TextView other = next ? hintTop : hintBottom;
        other.setVisibility(GONE);
        if (title != null && Math.abs(dy) > dpI(8)) {
            hint.setText((next ? "下一首 · " : "上一首 · ") + title);
            hint.setVisibility(VISIBLE);
        } else {
            hint.setVisibility(GONE);
        }
    }

    private void commitSwipe(int direction) {
        float target = (direction == DIR_NEXT ? -1f : 1f) * anchorDistance();
        ValueAnimator anim = ValueAnimator.ofFloat(dragDy, target);
        anim.setDuration(150L);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            anim.setInterpolator(new PathInterpolator(0.3f, 0f, 0.8f, 0.15f));
        }
        anim.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            applyDrag(v, anchorDistance());
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                hintTop.setVisibility(GONE);
                hintBottom.setVisibility(GONE);
                incomingImage.setVisibility(GONE);
                if (callback != null) {
                    if (direction == DIR_NEXT) callback.onSwipeNext();
                    else callback.onSwipePrev();
                }
            }
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelSettle();
        super.onDetachedFromWindow();
    }

    private float settleVelocity;
    private long settleLastNanos;
    private final android.view.Choreographer.FrameCallback settleCallback = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (settleLastNanos == 0L) settleLastNanos = frameTimeNanos;
            float dt = Math.min(0.033f, (frameTimeNanos - settleLastNanos) / 1_000_000_000f);
            settleLastNanos = frameTimeNanos;
            float stiffness = 1400f;
            float damping = 2f * 0.9f * (float) Math.sqrt(stiffness);
            int steps = 2;
            float h = dt / steps;
            for (int i = 0; i < steps; i++) {
                float accel = -stiffness * dragDy - damping * settleVelocity;
                settleVelocity += accel * h;
                dragDy += settleVelocity * h;
            }
            currentImage.setTranslationY(dragDy);
            incomingImage.setTranslationY((dragDy >= 0 ? -anchorDistance() : anchorDistance()) + dragDy);
            if (Math.abs(dragDy) < 0.5f && Math.abs(settleVelocity) < 10f) {
                dragDy = 0f;
                settleVelocity = 0f;
                settleLastNanos = 0L;
                hintTop.setVisibility(GONE);
                hintBottom.setVisibility(GONE);
                incomingImage.setVisibility(GONE);
                currentImage.setTranslationY(0);
                return;
            }
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void settleBack() {
        settleVelocity = 0f;
        settleLastNanos = 0L;
        android.view.Choreographer.getInstance().removeFrameCallback(settleCallback);
        android.view.Choreographer.getInstance().postFrameCallback(settleCallback);
    }

    private void cancelSettle() {
        android.view.Choreographer.getInstance().removeFrameCallback(settleCallback);
        settleLastNanos = 0L;
        settleVelocity = 0f;
    }

    private static class BurstView extends View {
        private final List<float[]> particles = new ArrayList<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path heart = new Path();
        private ValueAnimator animator;
        private float progress;
        private final Random random = new Random();

        BurstView(Context context) {
            super(context);
            setVisibility(GONE);
        }

        void burst() {
            particles.clear();
            int count = 8;
            for (int i = 0; i < count; i++) {
                float angle = random.nextFloat() * 120f - 60f;
                float travel = random.nextFloat() * 48f + 42f;
                float size = random.nextFloat() * 10f + 9f;
                float rotation = random.nextFloat() * 50f - 25f;
                float delay = random.nextFloat() * 0.08f + i * 0.035f;
                particles.add(new float[]{angle, travel, size, rotation, delay});
            }
            setVisibility(VISIBLE);
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1200L);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    setVisibility(GONE);
                }
            });
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            for (float[] p : particles) {
                float delay = p[4];
                float v = (progress - delay) / Math.max(0.01f, 1f - delay);
                if (v <= 0 || v >= 1) continue;
                v = Math.max(0f, Math.min(1f, v));
                float dist = p[1] * density * (1f - (1f - v) * (1f - v));
                double rad = Math.toRadians(p[0]);
                float x = cx + (float) Math.sin(rad) * dist;
                float y = cy - (float) Math.cos(rad) * dist;
                float alpha;
                if (v < 0.06f) alpha = v / 0.06f;
                else if (v < 0.55f) alpha = 1f;
                else alpha = 1f - (v - 0.55f) / 0.45f;
                float scaleT = Math.max(0f, Math.min(1f, v / 0.16f));
                float overshoot = overshoot(scaleT);
                float sizeScale = (1f - Math.max(0f, v - 0.35f) * 0.3f) * overshoot;
                float size = p[2] * density * sizeScale;
                paint.setColor(Color.WHITE);
                paint.setAlpha(Math.round(255f * Math.max(0f, Math.min(1f, alpha))));
                canvas.save();
                canvas.translate(x, y);
                canvas.rotate(p[3] * alpha);
                canvas.scale(size / 24f, size / 24f);
                drawHeart(canvas);
                canvas.restore();
            }
        }

        private float overshoot(float t) {
            float u = 1f - t;
            return 3f * u * u * t * 1.56f + 3f * u * t * t * 1.0f + t * t * t;
        }

        private void drawHeart(Canvas canvas) {
            heart.reset();
            heart.moveTo(0, 8);
            heart.cubicTo(-14, -4, -7, -16, 0, -7);
            heart.cubicTo(7, -16, 14, -4, 0, 8);
            heart.close();
            canvas.drawPath(heart, paint);
        }
    }
}
