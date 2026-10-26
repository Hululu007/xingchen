package com.fongmi.android.tv.ui.music.view.theme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * Apple Music 主题进度条（瓜子 PlaybackScrubber 的 Apple 调用实参）：
 * 画布高 18dp、轨 6dp、填充白渐变 0.58→0.9、顶盒关、底行关（无筹、无时间字）。
 * 拖动 seek：按下/移动即预览位置，抬手提交。
 */
public class AppleScrubberView extends View {

    public interface Listener {
        void onSeek(long ms);
    }

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long mPos;
    private long mDur;
    private boolean mDragging;
    private float mDragFrac;
    private Listener mListener;

    public AppleScrubberView(Context context) {
        super(context);
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    public void setProgress(long pos, long dur) {
        mPos = pos;
        mDur = dur;
        if (!mDragging) invalidate();
    }

    public boolean isDragging() {
        return mDragging;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(getMeasuredWidth(), Math.round(dp(18)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0) return;
        float trackH = dp(6);
        float cy = h / 2f;
        float frac = mDragging ? mDragFrac
                : (mDur > 0 ? Math.max(0f, Math.min(1f, mPos * 1f / mDur)) : 0f);
        mPaint.setShader(null);
        mPaint.setColor(0x24FFFFFF); // 底轨白 0.14
        canvas.drawRoundRect(0, cy - trackH / 2f, w, cy + trackH / 2f,
                trackH / 2f, trackH / 2f, mPaint);
        if (frac > 0f) {
            float fw = w * frac;
            mPaint.setShader(new LinearGradient(0, 0, Math.max(1f, fw), 0,
                    0x94FFFFFF, 0xE6FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(0, cy - trackH / 2f, fw, cy + trackH / 2f,
                    trackH / 2f, trackH / 2f, mPaint);
            mPaint.setShader(null);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragging = true;
                mDragFrac = fracOf(e.getX());
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mDragging) {
                    mDragFrac = fracOf(e.getX());
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDragging) {
                    mDragging = false;
                    if (mListener != null && mDur > 0) {
                        mListener.onSeek(Math.round(mDragFrac * mDur));
                    }
                    invalidate();
                }
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private float fracOf(float x) {
        if (getWidth() <= 0) return 0f;
        return Math.max(0f, Math.min(1f, x / getWidth()));
    }
}
