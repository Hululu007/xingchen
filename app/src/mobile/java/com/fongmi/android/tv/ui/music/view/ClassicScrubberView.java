package com.fongmi.android.tv.ui.music.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * 经典进度条（瓜子 PlaybackScrubber，经典调用实参：全默认、顶盒开、底行开）：
 * 顶盒 28dp（左右时间 11sp/宽 40dp/字距 0.2，静止 alpha 0→拖动 0.72/150ms；中双筹间距 6dp、
 * 筹内边距 12×5、11sp/700/白 0.72）→轨道 18dp（轨 4dp、拖动 +1；底轨白 0.14→拖动 0.22；
 * 填充渐变白 0.42→0.78；无 thumb 圆头；f<1 居中收缩、最小宽 60dp）→底行（左已播、右 −剩余，12sp 白 0.65）。
 */
public class ClassicScrubberView extends View {

    public interface Listener {
        void onSeek(long ms);

        void onQualityChip();

        void onLyricChip();
    }

    private static final PathInterpolator BS4_A = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private final float mDensity;
    private final Paint mTimePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mChipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mChipBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mChipStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBottomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mTmp = new RectF();

    private long mPosition, mDuration;
    private boolean mDragging;
    private float mDragFraction;
    private float mTimeAlpha; // 0 静止 → 0.72 拖动
    private ValueAnimator mAlphaAnim;
    private String mQualityLabel = "";
    private String mLyricLabel = "";
    private Listener mListener;

    private float mQualityChipL, mQualityChipR, mLyricChipL, mLyricChipR, mChipTop, mChipBottom;
    private boolean mDownOnChip;

    public ClassicScrubberView(Context context) {
        this(context, null);
    }

    public ClassicScrubberView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
        mTimePaint.setColor(0xFFFFFFFF);
        mTimePaint.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
        mTimePaint.setLetterSpacing(0.2f);
        mChipTextPaint.setColor(0xB8FFFFFF);
        mChipTextPaint.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
        mChipTextPaint.setFakeBoldText(true);
        mChipTextPaint.setLetterSpacing(0.6f / 11f);
        mChipStrokePaint.setStyle(Paint.Style.STROKE);
        mChipStrokePaint.setStrokeWidth(1f * mDensity);
        mChipStrokePaint.setColor(0x40FFFFFF);
        mBottomPaint.setColor(0xA6FFFFFF);
        mBottomPaint.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    public void setProgress(long position, long duration) {
        mPosition = position;
        mDuration = duration;
        if (!mDragging) invalidate();
    }

    public void setChips(String qualityLabel, String lyricLabel) {
        mQualityLabel = qualityLabel == null ? "" : qualityLabel;
        mLyricLabel = lyricLabel == null ? "" : lyricLabel;
        invalidate();
    }

    public boolean isDragging() {
        return mDragging;
    }

    private float dp(float v) {
        return v * mDensity;
    }

    private float fraction() {
        if (mDragging) return mDragFraction;
        return mDuration > 0 ? Math.max(0f, Math.min(1f, (float) mPosition / (float) mDuration)) : 0f;
    }

    private long displayPosition() {
        return mDragging ? (long) (mDragFraction * mDuration) : mPosition;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = (int) (dp(28) + dp(4) + dp(18) + dp(2) + 12f * getResources().getDisplayMetrics().scaledDensity + dp(4));
        setMeasuredDimension(w, h);
    }

    private static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        if (w <= 0) return;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        float f = fraction();

        // ---------- 顶盒（28dp） ----------
        float topBoxH = dp(28);
        mTimePaint.setAlpha(Math.round(255 * mTimeAlpha));
        float timeY = topBoxH / 2f - (mTimePaint.ascent() + mTimePaint.descent()) / 2f;
        // 左＝当前（40dp 盒内左对齐）、右＝总长（40dp 盒内右对齐）
        canvas.drawText(fmt(displayPosition()), 0, timeY, mTimePaint);
        String durText = fmt(mDuration);
        float dw = mTimePaint.measureText(durText);
        canvas.drawText(durText, w - dw, timeY, mTimePaint);

        // 双筹（居中，间距 6dp，内边距 12×5）
        float chipH = 11f * sp + dp(10);
        mChipTop = (topBoxH - chipH) / 2f;
        mChipBottom = mChipTop + chipH;
        float gap = dp(6);
        float qW = mQualityLabel.isEmpty() ? 0 : mChipTextPaint.measureText(mQualityLabel) + dp(24);
        float lW = mLyricLabel.isEmpty() ? 0 : mChipTextPaint.measureText(mLyricLabel) + dp(24);
        float total = qW + (qW > 0 && lW > 0 ? gap : 0) + lW;
        float x = (w - total) / 2f;
        if (qW > 0) {
            mQualityChipL = x;
            mQualityChipR = x + qW;
            drawChip(canvas, mQualityChipL, mQualityChipR, mQualityLabel);
            x += qW + gap;
        } else {
            mQualityChipL = mQualityChipR = -1;
        }
        if (lW > 0) {
            mLyricChipL = x;
            mLyricChipR = x + lW;
            drawChip(canvas, mLyricChipL, mLyricChipR, mLyricLabel);
        } else {
            mLyricChipL = mLyricChipR = -1;
        }

        // ---------- 轨道（18dp） ----------
        float trackTop = topBoxH + dp(4);
        float trackCy = trackTop + dp(18) / 2f;
        float fullW = w;
        float trackW = f >= 1f ? fullW : Math.max(dp(60), f * fullW);
        float trackL = (w - trackW) / 2f;
        float trackR = trackL + trackW;
        float trackH = dp(mDragging ? 5 : 4);
        mTrackPaint.setColor(0xFFFFFFFF);
        mTrackPaint.setAlpha(Math.round(255 * (mDragging ? 0.22f : 0.14f)));
        mTmp.set(trackL, trackCy - trackH / 2f, trackR, trackCy + trackH / 2f);
        canvas.drawRoundRect(mTmp, trackH / 2f, trackH / 2f, mTrackPaint);
        float fillR = trackL + trackW * f;
        if (fillR > trackL) {
            mFillPaint.setShader(new LinearGradient(trackL, 0, trackR, 0,
                    0x6BFFFFFF, 0xC7FFFFFF, Shader.TileMode.CLAMP));
            mTmp.set(trackL, trackCy - trackH / 2f, fillR, trackCy + trackH / 2f);
            canvas.drawRoundRect(mTmp, trackH / 2f, trackH / 2f, mFillPaint);
            mFillPaint.setShader(null);
        }

        // ---------- 底行（左已播、右 −剩余，12sp 白 0.65） ----------
        float baseY = trackTop + dp(18) + dp(2) + 12f * sp * 0.9f;
        canvas.drawText(fmt(displayPosition()), dp(2), baseY, mBottomPaint);
        String rem = "−" + fmt(Math.max(0, mDuration - displayPosition()));
        float rw = mBottomPaint.measureText(rem);
        canvas.drawText(rem, w - dp(2) - rw, baseY, mBottomPaint);
    }

    private void drawChip(Canvas canvas, float l, float r, String label) {
        mTmp.set(l, mChipTop, r, mChipBottom);
        float rad = (mChipBottom - mChipTop) / 2f;
        mChipBgPaint.setShader(null);
        mChipBgPaint.setColor(0x24FFFFFF); // 面：白 0.14
        canvas.drawRoundRect(mTmp, rad, rad, mChipBgPaint);
        mChipBgPaint.setShader(new LinearGradient(0, mChipTop, 0, mChipBottom,
                0x1AFFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP)); // 渐变罩：白 0.10→0.04
        canvas.drawRoundRect(mTmp, rad, rad, mChipBgPaint);
        mChipBgPaint.setShader(null);
        canvas.drawRoundRect(mTmp, rad, rad, mChipStrokePaint);
        float ty = (mChipTop + mChipBottom) / 2f - (mChipTextPaint.ascent() + mChipTextPaint.descent()) / 2f;
        canvas.drawText(label, l + dp(12), ty, mChipTextPaint);
    }

    private void animateTimeAlpha(float to) {
        if (mAlphaAnim != null) mAlphaAnim.cancel();
        mAlphaAnim = ValueAnimator.ofFloat(mTimeAlpha, to);
        mAlphaAnim.setDuration(150);
        mAlphaAnim.setInterpolator(BS4_A);
        mAlphaAnim.addUpdateListener(a -> {
            mTimeAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        mAlphaAnim.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownOnChip = y <= mChipBottom + dp(4)
                        && ((x >= mQualityChipL && x <= mQualityChipR && mQualityChipR > 0)
                        || (x >= mLyricChipL && x <= mLyricChipR && mLyricChipR > 0));
                if (mDownOnChip) return true;
                if (mDuration > 0) {
                    mDragging = true;
                    mDragFraction = fractionFromX(x);
                    animateTimeAlpha(0.72f);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mDragging) {
                    mDragFraction = fractionFromX(x);
                    invalidate();
                    return true;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (mDownOnChip) {
                    if (mListener != null) {
                        if (x >= mQualityChipL && x <= mQualityChipR && mQualityChipR > 0) mListener.onQualityChip();
                        else if (x >= mLyricChipL && x <= mLyricChipR && mLyricChipR > 0) mListener.onLyricChip();
                    }
                    mDownOnChip = false;
                    return true;
                }
                if (mDragging) {
                    mDragging = false;
                    animateTimeAlpha(0f);
                    if (mListener != null) mListener.onSeek((long) (mDragFraction * mDuration));
                    invalidate();
                    return true;
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                mDownOnChip = false;
                animateTimeAlpha(0f);
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private float fractionFromX(float x) {
        int w = getWidth();
        if (w <= 0) return 0f;
        // 拖动映射走全宽（按下即跳到触点，与瓜子 scrub 手感一致）
        return Math.max(0f, Math.min(1f, x / w));
    }
}
