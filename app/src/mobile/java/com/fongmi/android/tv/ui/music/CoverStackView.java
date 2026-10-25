package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

/**
 * 封面卡片三卡叠放容器（施工图 §3 ThreeCardAlternateStack）：
 * 宽高比 0.65＝宽/高（竖卡）；封面尺寸钳制（瓜子 PlayerScreenKt.java:8877-8885，变量走向以源码为准）：
 * 高＝min(区高×0.98, 区宽/0.65)、宽＝高×0.65、圆角 22dp 居中。
 * 后叠卡是空占位卡（CoverImmersivePlaceholderCard，白渐变＋描边，无封面图），两张同尺寸 ×0.93、圆角 22dp，
 * 旋转 −4°（左后）／+4°（右后），偏移 (−12,−18)dp／(12,−14)dp（瓜子源码 offset 字面值，dig 三.2）。
 * 子视图顺序固定：0＝左后卡（−4°）、1＝右后卡（+4°）、2＝出场卡层、3＝前卡包裹层
 * （瓜子绘制序：先左后右，PlayerScreenKt.java:26730-26743）；
 * 出场卡层与前卡同尺寸同位置（切歌换卡动画用，平时 gone）。
 */
public class CoverStackView extends FrameLayout {

    private static final float ASPECT = 0.65f;

    /** 横滑切歌回调（next=true 下一首）。 */
    public interface OnHorizontalSwipeListener {
        void onHorizontalSwipe(boolean next);
    }

    private OnHorizontalSwipeListener swipeListener;
    private float swipeDownX;
    private float swipeDownY;
    private long swipeDownMs;
    private boolean swipeArmed;

    public CoverStackView(Context context) {
        super(context);
        init();
    }

    public CoverStackView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setClipChildren(false);
    }

    public void setOnHorizontalSwipeListener(OnHorizontalSwipeListener listener) {
        swipeListener = listener;
    }

    private float anchorPx() {
        float density = getResources().getDisplayMetrics().density;
        float widthDp = getResources().getDisplayMetrics().widthPixels / density;
        return Math.max(220f, Math.min(360f, widthDp * 0.42f)) * density;
    }

    /**
     * 横滑切歌拦截（dig 三.13）：|dx|＞|dy|×1.2 才消费并拦截子手势；
     * 跟手位移钳在锚点 clamp(屏宽×0.42, 220, 360)dp 内，松手过 36dp 或甩速 600dp/s 提交，
     * 左滑＝下一首、右滑＝上一首；不提交 150ms 回位（提交动画时长字面值未抠到，取页级提交同值 150ms）。
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swipeDownX = ev.getX();
                swipeDownY = ev.getY();
                swipeDownMs = android.os.SystemClock.uptimeMillis();
                swipeArmed = false;
                return false;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - swipeDownX;
                float dy = ev.getY() - swipeDownY;
                float slop = 8f * getResources().getDisplayMetrics().density;
                if (!swipeArmed && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    swipeArmed = true;
                    return true;
                }
                return swipeArmed;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                swipeArmed = false;
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float density = getResources().getDisplayMetrics().density;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swipeDownX = ev.getX();
                swipeDownY = ev.getY();
                swipeDownMs = android.os.SystemClock.uptimeMillis();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - swipeDownX;
                float anchor = anchorPx();
                setTranslationX(Math.max(-anchor, Math.min(anchor, dx)));
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float dx = ev.getX() - swipeDownX;
                long dt = Math.max(1L, android.os.SystemClock.uptimeMillis() - swipeDownMs);
                float velDpS = Math.abs(dx) / density / dt * 1000f;
                boolean commit = Math.abs(dx) >= 36f * density || velDpS >= 600f;
                animate().translationX(0f).setDuration(150).start();
                if (commit && swipeListener != null) swipeListener.onHorizontalSwipe(dx < 0);
                swipeArmed = false;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                animate().translationX(0f).setDuration(150).start();
                swipeArmed = false;
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
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
        layoutCard(getChildAt(0), cx, cy, cardW * 0.93f, cardH * 0.93f, dp(-12f), dp(-18f), -4f);
        layoutCard(getChildAt(1), cx, cy, cardW * 0.93f, cardH * 0.93f, dp(12f), dp(-14f), 4f);
        layoutCard(getChildAt(2), cx, cy, cardW, cardH, 0f, 0f, 0f);
        layoutCard(getChildAt(3), cx, cy, cardW, cardH, 0f, 0f, 0f);
    }

    private void layoutCard(View child, float cx, float cy, float cw, float ch,
                            float offX, float offY, float rotation) {
        int l = Math.round(cx - cw / 2f);
        int t = Math.round(cy - ch / 2f);
        child.measure(MeasureSpec.makeMeasureSpec(Math.round(cw), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(ch), MeasureSpec.EXACTLY));
        child.layout(l, t, l + Math.round(cw), t + Math.round(ch));
        child.setTranslationX(offX);
        child.setTranslationY(offY);
        child.setRotation(rotation);
    }

    /** 给三张卡（后卡两张＋前卡）统一挂 22dp 圆角裁剪（在 Activity 装配时调用）。 */
    public static void roundCard(ImageView view, float radiusDp, Context context) {
        float radius = radiusDp * context.getResources().getDisplayMetrics().density;
        view.setClipToOutline(true);
        view.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
    }
}
