package com.fongmi.android.tv.ui.music;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.utils.ImgUtil;

public class CoverCardView extends FrameLayout {

    public interface Callback {
        void onSwipePrev();

        void onSwipeNext();

        void onTapCard();

        void onDoubleTapFavorite();
    }

    private final ImageView backCard;
    private final ImageView mainCard;
    private Callback callback;
    private String currentUrl;
    private String prevUrl;
    private String nextUrl;
    private float dragDx;
    private boolean dragging;
    private boolean axisLocked;
    private float downX;
    private float downY;
    private long downMs;
    private VelocityTracker velocityTracker;
    private boolean hapticFired;
    private int cardW;
    private int cardH;
    private long lastTapMs;
    private float lastTapX;
    private float lastTapY;

    public CoverCardView(Context context) {
        super(context);
        backCard = new ImageView(context);
        backCard.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable placeholder = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{PlayerViews.white(0.08f), PlayerViews.white(0.03f)});
        placeholder.setCornerRadius(dpF(22));
        backCard.setBackground(placeholder);
        backCard.setElevation(dpF(6));
        addView(backCard);
        mainCard = new ImageView(context);
        mainCard.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mainCard.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dpF(22));
            }
        });
        mainCard.setClipToOutline(true);
        mainCard.setElevation(dpF(6));
        addView(mainCard);
        setClipChildren(false);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setNeighbors(String prevCover, String nextCover) {
        prevUrl = prevCover;
        nextUrl = nextCover;
    }

    public void setCover(String url, boolean animate, int direction) {
        currentUrl = url;
        if (url == null || url.isEmpty()) {
            Glide.with(mainCard).clear(mainCard);
            mainCard.setImageDrawable(null);
            return;
        }
        Glide.with(mainCard).load(ImgUtil.getUrl(url)).centerCrop().into(mainCard);
    }

    private float dpF(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private int dpI(float v) {
        return Math.round(dpF(v));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int availW = MeasureSpec.getSize(widthMeasureSpec);
        int availH = MeasureSpec.getSize(heightMeasureSpec);
        float w = Math.min(availW * 0.98f, availH / 0.65f);
        float h = w * 0.65f;
        cardW = Math.round(w);
        cardH = Math.round(h);
        setMeasuredDimension(availW, availH);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int w = right - left;
        int h = bottom - top;
        int cx = (w - cardW) / 2;
        int cy = (h - cardH) / 2;
        backCard.layout(cx + dpI(10), cy + dpI(8), cx + dpI(10) + cardW, cy + dpI(8) + cardH);
        mainCard.layout(cx, cy, cx + cardW, cy + cardH);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downMs = System.currentTimeMillis();
                dragging = false;
                axisLocked = false;
                hapticFired = false;
                dragDx = 0;
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (velocityTracker != null) velocityTracker.addMovement(event);
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!axisLocked) {
                    if (Math.abs(dx) > dpI(4) || Math.abs(dy) > dpI(4)) {
                        axisLocked = Math.abs(dx) > Math.abs(dy) * 1.2f;
                        dragging = axisLocked;
                        if (dragging) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (dragging) {
                    float max = anchorDistance();
                    dragDx = Math.max(-max, Math.min(max, dx));
                    mainCard.setTranslationX(dragDx);
                    float trigger = dpI(36);
                    if (!hapticFired && Math.abs(dragDx) >= trigger) {
                        hapticFired = true;
                        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                    } else if (Math.abs(dragDx) < trigger) {
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
                float vx = velocityTracker == null ? 0 : velocityTracker.getXVelocity();
                if (velocityTracker != null) velocityTracker.recycle();
                velocityTracker = null;
                if (dragging) {
                    float trigger = dpI(36);
                    float fling = 600f * getResources().getDisplayMetrics().density;
                    int commit = 0;
                    if (Math.abs(dragDx) >= trigger) commit = dragDx < 0 ? 1 : -1;
                    else if (Math.abs(vx) >= fling) commit = vx < 0 ? 1 : -1;
                    if (commit != 0) commitSwipe(commit);
                    else settleBack();
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        && System.currentTimeMillis() - downMs < 300) {
                    long now = System.currentTimeMillis();
                    if (now - lastTapMs < 300
                            && Math.abs(event.getX() - lastTapX) < dpI(48)
                            && Math.abs(event.getY() - lastTapY) < dpI(48)) {
                        lastTapMs = 0;
                        if (callback != null) callback.onDoubleTapFavorite();
                    } else {
                        lastTapMs = now;
                        lastTapX = event.getX();
                        lastTapY = event.getY();
                        postDelayed(() -> {
                            if (lastTapMs != 0 && System.currentTimeMillis() - lastTapMs >= 280) {
                                lastTapMs = 0;
                                if (callback != null) callback.onTapCard();
                            }
                        }, 300);
                    }
                }
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private float anchorDistance() {
        float density = getResources().getDisplayMetrics().density;
        float screenDp = getResources().getDisplayMetrics().widthPixels / density;
        return Math.max(220f, Math.min(360f, screenDp * 0.42f)) * density;
    }

    private void commitSwipe(int direction) {
        float target = (direction == 1 ? -1f : 1f) * anchorDistance();
        ValueAnimator anim = ValueAnimator.ofFloat(dragDx, target);
        anim.setDuration(150L);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            anim.setInterpolator(new PathInterpolator(0.3f, 0f, 0.8f, 0.15f));
        }
        anim.addUpdateListener(a -> mainCard.setTranslationX((float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mainCard.setTranslationX(0);
                if (callback != null) {
                    if (direction == 1) callback.onSwipeNext();
                    else callback.onSwipePrev();
                }
            }
        });
        anim.start();
    }

    private void settleBack() {
        ValueAnimator anim = ValueAnimator.ofFloat(dragDx, 0f);
        anim.setDuration(300L);
        anim.addUpdateListener(a -> mainCard.setTranslationX((float) a.getAnimatedValue()));
        anim.start();
    }
}
