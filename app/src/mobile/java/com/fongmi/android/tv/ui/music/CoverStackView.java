package com.fongmi.android.tv.ui.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

/**
 * 封面卡片三卡叠放（瓜子 CoverImmersive 口径，gz-full-parity-1025 §4／§13.1／§13.2／§15.1 第 8 项）：
 * 子 View 由布局给，顺序固定：0＝左后卡、1＝右后卡、2＝出场卡层、3＝前卡包裹层（绘制序先左后右）。
 * 前卡尺寸：高＝min(区高×0.98, 区宽/0.65)、宽＝高×0.65、圆角 22dp（宿主经 roundCard 挂裁剪）；
 * 后叠卡是空占位卡（布局 background 给竖渐变白 0.08→0.03＋描边 1dp 白 0.12＋圆角 22dp），
 * 尺寸 ×0.93、旋转 −4°（左）／+4°（右）、偏移 (−12,−18)dp／(12,−14)dp；
 * 阴影 6dp（ambient 黑 0.10／spot 黑 0.16，API 28+ 设色、以下只 elevation）。
 * 横滑切歌：dominance 1.2、锚点 clamp(屏宽×0.42, 220, 360)dp、触发 36dp、甩速 600dp/s（释放速度定向）；
 * 跟手只动前卡：位移钳锚点、缩放＝1−|位移|/锚点×0.05，后卡与出场层不动。
 * 提交后本类跑 ThreeCard 换卡 350ms：回调先行（宿主此刻换前卡封面图），出场层承旧图
 * 滑出（幅度＝卡全幅÷4）＋淡出＋缩放→0.92，前卡整幅滑入＋淡入＋缩放 0.94→1；
 * 缓动分工：滑移／缩放 CubicBezier(0.2,0,0,1)，淡入淡出另一曲线（本类用线性近似，见回执抠不到清单）。
 * 未提交：前卡 150ms 回位（横滑回位时长瓜子字面值未抠到，取页级同值）。
 */
public class CoverStackView extends FrameLayout {

    public interface OnHorizontalSwipeListener {
        void onHorizontalSwipe(boolean next);
    }

    private static final float ASPECT = 0.65f;
    private static final float DOMINANCE = 1.2f;
    private static final float TRIGGER_DP = 36f;
    private static final float FLING_DP_S = 600f;
    private static final long SWAP_MS = 350L;
    private static final long SETTLE_MS = 150L;
    private static final PathInterpolator SLIDE_INTERPOLATOR = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final LinearInterpolator FADE_INTERPOLATOR = new LinearInterpolator();

    private OnHorizontalSwipeListener swipeListener;
    private float downX;
    private float downY;
    private boolean armed;
    private boolean swapping;
    private VelocityTracker velocityTracker;
    private ValueAnimator swapAnimator;

    public CoverStackView(Context context) {
        super(context);
        setClipChildren(false);
    }

    public CoverStackView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
    }

    public void setOnHorizontalSwipeListener(OnHorizontalSwipeListener listener) {
        swipeListener = listener;
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    private float anchorPx() {
        float widthDp = getResources().getDisplayMetrics().widthPixels / density();
        return Math.max(220f, Math.min(360f, widthDp * 0.42f)) * density();
    }

    private View backLeft() {
        return getChildCount() > 0 ? getChildAt(0) : null;
    }

    private View backRight() {
        return getChildCount() > 1 ? getChildAt(1) : null;
    }

    private View exitLayer() {
        return getChildCount() > 2 ? getChildAt(2) : null;
    }

    private View frontWrap() {
        return getChildCount() > 3 ? getChildAt(3) : null;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (swapping) return true;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                armed = false;
                recycleTracker();
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(ev);
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                if (armed) return true;
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                float slop = 8f * density();
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * DOMINANCE) {
                    armed = true;
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                armed = false;
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                if (swapping) return true;
                float dx = ev.getX() - downX;
                float anchor = anchorPx();
                float clamped = Math.max(-anchor, Math.min(anchor, dx));
                View front = frontWrap();
                if (front != null) {
                    front.setTranslationX(clamped);
                    float scale = 1f - Math.abs(clamped) / anchor * 0.05f;
                    front.setScaleX(scale);
                    front.setScaleY(scale);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                float dx = ev.getX() - downX;
                float velDpS = 0f;
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    velDpS = velocityTracker.getXVelocity() / density();
                }
                recycleTracker();
                armed = false;
                if (swapping) return true;
                if (Math.abs(dx) >= TRIGGER_DP * density()) {
                    startSwap(dx < 0);
                } else if (Math.abs(velDpS) >= FLING_DP_S) {
                    startSwap(velDpS < 0);
                } else {
                    settleFront();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                recycleTracker();
                armed = false;
                if (!swapping) settleFront();
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    private void recycleTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private void settleFront() {
        final View front = frontWrap();
        if (front == null) return;
        front.animate().translationX(0f).scaleX(1f).scaleY(1f)
                .setDuration(SETTLE_MS).setInterpolator(SLIDE_INTERPOLATOR).start();
    }

    /**
     * ThreeCard 换卡：出场层承接前卡当前图样滑出，前卡换图后整幅滑入。
     * 回调在动画开始即发，宿主须同步换好前卡内的封面图（tpCover）。
     */
    private void startSwap(final boolean next) {
        final View front = frontWrap();
        final View exit = exitLayer();
        if (front == null || exit == null || swapping) {
            if (swipeListener != null) swipeListener.onHorizontalSwipe(next);
            settleFront();
            return;
        }
        final float cardW = front.getWidth();
        if (cardW <= 0f) {
            if (swipeListener != null) swipeListener.onHorizontalSwipe(next);
            settleFront();
            return;
        }
        // 出场层承旧图：前卡包裹层首个 ImageView 即封面图
        if (exit instanceof ImageView && front instanceof FrameLayout) {
            FrameLayout wrap = (FrameLayout) front;
            for (int i = 0; i < wrap.getChildCount(); i++) {
                View child = wrap.getChildAt(i);
                if (child instanceof ImageView && child.getVisibility() == View.VISIBLE) {
                    ((ImageView) exit).setImageDrawable(((ImageView) child).getDrawable());
                    break;
                }
            }
        }
        final float sign = next ? -1f : 1f;
        final float exitStartX = front.getTranslationX();
        final float exitStartScale = front.getScaleX();
        final float exitEndX = sign * cardW / 4f;
        final float frontStartX = -sign * cardW;
        exit.setTranslationX(exitStartX);
        exit.setTranslationY(0f);
        exit.setScaleX(front.getScaleX());
        exit.setScaleY(front.getScaleY());
        exit.setAlpha(1f);
        exit.setVisibility(View.VISIBLE);
        if (swipeListener != null) swipeListener.onHorizontalSwipe(next);
        front.setTranslationX(frontStartX);
        front.setScaleX(0.94f);
        front.setScaleY(0.94f);
        front.setAlpha(0f);
        swapping = true;
        if (swapAnimator != null) swapAnimator.cancel();
        final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(SWAP_MS);
        anim.addUpdateListener(a -> {
            float raw = (float) a.getAnimatedValue();
            float slide = SLIDE_INTERPOLATOR.getInterpolation(raw);
            float fade = FADE_INTERPOLATOR.getInterpolation(raw);
            exit.setTranslationX(exitStartX + (exitEndX - exitStartX) * slide);
            float exitScale = exitStartScale + (0.92f - exitStartScale) * slide;
            exit.setScaleX(exitScale);
            exit.setScaleY(exitScale);
            exit.setAlpha(1f - fade);
            front.setTranslationX(frontStartX * (1f - slide));
            float frontScale = 0.94f + (1f - 0.94f) * slide;
            front.setScaleX(frontScale);
            front.setScaleY(frontScale);
            front.setAlpha(fade);
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                finishSwap(front, exit);
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                finishSwap(front, exit);
            }
        });
        swapAnimator = anim;
        anim.start();
    }

    private void finishSwap(View front, View exit) {
        swapping = false;
        swapAnimator = null;
        exit.setVisibility(View.GONE);
        if (exit instanceof ImageView) ((ImageView) exit).setImageDrawable(null);
        exit.setTranslationX(0f);
        exit.setScaleX(1f);
        exit.setScaleY(1f);
        exit.setAlpha(1f);
        front.setTranslationX(0f);
        front.setScaleX(1f);
        front.setScaleY(1f);
        front.setAlpha(1f);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (swapAnimator != null) {
            swapAnimator.removeAllUpdateListeners();
            swapAnimator.removeAllListeners();
            swapAnimator.cancel();
            swapAnimator = null;
        }
        swapping = false;
        recycleTracker();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int w = right - left;
        int h = bottom - top;
        if (w <= 0 || h <= 0 || getChildCount() < 4) {
            super.onLayout(changed, left, top, right, bottom);
            return;
        }
        float cardH = Math.min(h * 0.98f, w / ASPECT);
        float cardW = cardH * ASPECT;
        float cx = w / 2f;
        float cy = h / 2f;
        layoutCard(backLeft(), cx, cy, cardW * 0.93f, cardH * 0.93f, dp(-12f), dp(-18f), -4f);
        layoutCard(backRight(), cx, cy, cardW * 0.93f, cardH * 0.93f, dp(12f), dp(-14f), 4f);
        layoutCard(exitLayer(), cx, cy, cardW, cardH, 0f, 0f, 0f);
        if (!swapping) layoutCard(frontWrap(), cx, cy, cardW, cardH, 0f, 0f, 0f);
        else layoutCardKeepTransform(frontWrap(), cx, cy, cardW, cardH);
        applyShadows();
    }

    private void layoutCard(View child, float cx, float cy, float cw, float ch,
                            float offX, float offY, float rotation) {
        if (child == null) return;
        int l = Math.round(cx - cw / 2f);
        int t = Math.round(cy - ch / 2f);
        child.measure(MeasureSpec.makeMeasureSpec(Math.round(cw), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(ch), MeasureSpec.EXACTLY));
        child.layout(l, t, l + Math.round(cw), t + Math.round(ch));
        child.setTranslationX(offX);
        child.setTranslationY(offY);
        child.setRotation(rotation);
    }

    private void layoutCardKeepTransform(View child, float cx, float cy, float cw, float ch) {
        if (child == null) return;
        int l = Math.round(cx - cw / 2f);
        int t = Math.round(cy - ch / 2f);
        child.measure(MeasureSpec.makeMeasureSpec(Math.round(cw), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(ch), MeasureSpec.EXACTLY));
        child.layout(l, t, l + Math.round(cw), t + Math.round(ch));
    }

    /** 阴影 6dp：三层同高保绘制序（后卡／出场层／前卡），色 ambient 黑 0.10／spot 黑 0.16（API 28+）。 */
    private void applyShadows() {
        float elevation = dp(6f);
        View[] layers = {backLeft(), backRight(), exitLayer(), frontWrap()};
        for (View v : layers) {
            if (v == null) continue;
            v.setElevation(elevation);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                v.setOutlineAmbientShadowColor(0x1A000000);
                v.setOutlineSpotShadowColor(0x29000000);
            }
        }
    }

    private float dp(float value) {
        return value * density();
    }

    /** 22dp 圆角裁剪（宿主装配时对后卡／前卡封面调用）。 */
    public static void roundCard(ImageView view, float radiusDp, Context context) {
        final float radius = radiusDp * context.getResources().getDisplayMetrics().density;
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
    }
}
