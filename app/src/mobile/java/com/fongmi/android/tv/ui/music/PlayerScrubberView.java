package com.fongmi.android.tv.ui.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import com.fongmi.android.tv.utils.ResUtil;

/**
 * 播放进度条（重做第一步，规格 gz-full-parity-1025 §2 默认变体＋§1 手势）：
 * 容器高 18dp（由父给定）；轨 4dp、拖动时 5dp（150ms tween）；无拇指；
 * 已播白 alpha 静止 0.42／拖动 0.78。点按＝seek、拖动＝松手提交（onSeek）。
 * 底轨 alpha §2 未给默认变体的值，取同族 Apple 变体的静止底轨 0.14（见回执缺口）。
 * 时间文字在父布局（11sp／宽 40dp／字距 0.2sp，alpha 拖动 0.72／静止 0 由父随本控件状态切）。
 */
public class PlayerScrubberView extends View {

    public interface Listener {
        /** 拖动态变化（父据此切时间文字 alpha）。 */
        void onDraggingChanged(boolean dragging);

        /** 松手/点按提交：目标比例 0..1。 */
        void onSeek(float fraction);
    }

    private static final float TRACK_DP = 4f;
    private static final float TRACK_DRAG_DP = 5f;
    private static final long THICK_ANIM_MS = 150L;
    private static final float PLAYED_ALPHA = 0.42f;
    private static final float PLAYED_ALPHA_DRAG = 0.78f;
    private static final float BASE_ALPHA = 0.14f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;
    private float fraction;
    private boolean dragging;
    private float dragFraction;
    private float thickFraction;
    private ValueAnimator thickAnim;

    public PlayerScrubberView(Context context) {
        this(context, null);
    }

    public PlayerScrubberView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 播放进度（拖动中不覆盖预览值）。 */
    public void setFraction(float f) {
        fraction = clamp01(f);
        if (!dragging) invalidate();
    }

    public boolean isDragging() {
        return dragging;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float thick = ResUtil.dp2px((int) (TRACK_DP + (TRACK_DRAG_DP - TRACK_DP) * thickFraction));
        float top = (h - thick) / 2f;
        float radius = thick / 2f;
        float shown = dragging ? dragFraction : fraction;
        paint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, Math.round(BASE_ALPHA * 255f)));
        canvas.drawRoundRect(0f, top, w, top + thick, radius, radius, paint);
        paint.setColor(ColorUtils.setAlphaComponent(Color.WHITE,
                Math.round((dragging ? PLAYED_ALPHA_DRAG : PLAYED_ALPHA) * 255f)));
        canvas.drawRoundRect(0f, top, Math.max(radius * 2f, w * shown), top + thick, radius, radius, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                setDragging(true);
                dragFraction = fractionOf(event.getX());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    dragFraction = fractionOf(event.getX());
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    dragFraction = fractionOf(event.getX());
                    float commit = dragFraction;
                    setDragging(false);
                    if (listener != null) listener.onSeek(commit);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                setDragging(false);
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void setDragging(boolean value) {
        if (dragging == value) return;
        dragging = value;
        if (listener != null) listener.onDraggingChanged(value);
        if (thickAnim != null) thickAnim.cancel();
        thickAnim = ValueAnimator.ofFloat(thickFraction, value ? 1f : 0f);
        thickAnim.setDuration(THICK_ANIM_MS);
        thickAnim.addUpdateListener(a -> {
            thickFraction = (float) a.getAnimatedValue();
            invalidate();
        });
        thickAnim.start();
        invalidate();
    }

    private float fractionOf(float x) {
        if (getWidth() <= 0) return 0f;
        return clamp01(x / getWidth());
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
