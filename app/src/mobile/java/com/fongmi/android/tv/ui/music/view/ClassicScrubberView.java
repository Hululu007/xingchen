package com.fongmi.android.tv.ui.music.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

/**
 * 经典进度条（瓜子 PlaybackScrubber，经典调用全默认、顶盒开、底行开；数值照对照表回补 8/9/12）：
 * 顶盒 28dp＋下内边距 4dp：左右时间 11sp、字距 0.2sp、各占 40dp 盒，alpha 静止 0→拖动 0.72（150ms）；
 * 中双筹间距 6dp、内边距 12×5、11sp/700/白 0.72/字距 0.6sp；筹面＝竖直渐变 白 0.10→0.04，
 * 描边 0.8dp 白 0.14、全圆；筹透明度＝（拖动 0.28／静止 0.55）×唤出系数（250ms，随唤出态与播放态），
 * 内部再经 150ms 动画。唤出态：与进度条交互置真，闲置 3 秒或切歌（无预览时）清除。
 * 轨道行 18dp：轨 4dp、拖动 5dp；收缩画法——菜单打开时按 150ms 动画收向 60dp 最小宽居中，
 * 轨 alpha 与填充 alpha 随收缩系数内插；填充竖直渐变 白(0.72×a)→白(a)，a 静止 0.42／拖动 0.78；
 * 填充宽＝显示进度×绘制宽（进度为正时下限＝轨高）。无 thumb、圆头收尾。
 * 拖动映射：点按未唤出只唤出不跳；拖动全程发预览，seek 封顶 时长−300ms，抬手提交、取消不提交。
 * 底行内边距 (2,2,2,0)：左已播、右 −剩余，12sp 白 0.65。
 */
public class ClassicScrubberView extends View {

    public interface Listener {
        void onSeek(long ms);

        void onScrubPreview(long ms);

        void onQualityChip();

        void onLyricChip();
    }

    private static final PathInterpolator BS4_A = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private final float mDensity;
    private final float mScaled;
    private final Paint mTimePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mChipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mChipBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mChipStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBottomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mTmp = new RectF();
    private final Handler mIdleHandler = new Handler(Looper.getMainLooper());

    private long mPosition, mDuration;
    private boolean mDragging;
    private float mDragFraction;
    private Long mPreviewMs;
    private float mDispFraction = -1f;
    private ValueAnimator mFractionAnim;

    private float mTimeAlpha;
    private ValueAnimator mAlphaAnim;

    private boolean mRevealed;
    private boolean mPlaying;
    private float mRevealA;
    private ValueAnimator mRevealAnim;
    private float mChipA = 0f;
    private ValueAnimator mChipAnim;

    private boolean mMenuOpen;
    private float mShrinkF = 1f;
    private ValueAnimator mShrinkAnim;

    private String mQualityLabel = "";
    private boolean mLocalTrack;
    private String mLyricLabel = "";
    private Listener mListener;

    private float mQualityChipL, mQualityChipR, mLyricChipL, mLyricChipR, mChipTop, mChipBottom;
    private boolean mDownOnChip;

    private final Runnable mIdleClear = () -> {
        if (!mDragging && mPreviewMs == null) {
            mRevealed = false;
            updateRevealTarget();
        }
    };

    public ClassicScrubberView(Context context) {
        this(context, null);
    }

    public ClassicScrubberView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
        mScaled = context.getResources().getDisplayMetrics().scaledDensity;
        mTimePaint.setColor(0xFFFFFFFF);
        mTimePaint.setTextSize(11f * mScaled);
        mTimePaint.setLetterSpacing(0.2f / 11f);
        mChipTextPaint.setColor(0xFFFFFFFF);
        mChipTextPaint.setTextSize(11f * mScaled);
        mChipTextPaint.setFakeBoldText(true);
        mChipTextPaint.setLetterSpacing(0.6f / 11f);
        mChipStrokePaint.setStyle(Paint.Style.STROKE);
        mChipStrokePaint.setStrokeWidth(0.8f * mDensity);
        mChipStrokePaint.setColor(0x24FFFFFF);
        mBottomPaint.setColor(0xA6FFFFFF);
        mBottomPaint.setTextSize(12f * mScaled);
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    public void setProgress(long position, long duration) {
        mPosition = position;
        mDuration = duration;
        if (!mDragging) {
            float target = duration > 0 ? Math.max(0f, Math.min(1f, (float) position / (float) duration)) : 0f;
            animateFraction(target);
            invalidate();
        }
    }

    public void setChips(String qualityLabel, String lyricLabel) {
        mQualityLabel = qualityLabel == null ? "" : qualityLabel;
        mLyricLabel = lyricLabel == null ? "" : lyricLabel;
        invalidate();
    }

    /** 本地文件（瓜子 z50 本地旗）：音质筹改标「本地」且不可点（无音质可切），歌词筹不受影响。 */
    public void setLocalTrack(boolean local) {
        if (mLocalTrack == local) return;
        mLocalTrack = local;
        invalidate();
    }

    private String qualityText() {
        return mLocalTrack ? "本地" : mQualityLabel;
    }

    public void setPlaying(boolean playing) {
        if (mPlaying == playing) return;
        mPlaying = playing;
        updateRevealTarget();
        updateChipTarget();
    }

    /** 菜单（音质/歌词）打开时轨道收缩居中，关闭恢复全宽（150ms）。 */
    public void setMenuOpen(boolean open) {
        if (mMenuOpen == open) return;
        mMenuOpen = open;
        if (mShrinkAnim != null) mShrinkAnim.cancel();
        mShrinkAnim = ValueAnimator.ofFloat(mShrinkF, open ? 0f : 1f);
        mShrinkAnim.setDuration(150);
        mShrinkAnim.setInterpolator(BS4_A);
        mShrinkAnim.addUpdateListener(a -> {
            mShrinkF = (float) a.getAnimatedValue();
            invalidate();
        });
        mShrinkAnim.start();
    }

    /** 切歌：清唤出态与预览（瓜子：切歌且无预览时唤出态归假）。 */
    public void resetForTrack() {
        mPreviewMs = null;
        mRevealed = false;
        mDispFraction = 0f;
        updateRevealTarget();
        updateChipTarget();
        invalidate();
    }

    public boolean isDragging() {
        return mDragging;
    }

    private float dp(float v) {
        return v * mDensity;
    }

    // ---------- 唤出态 ----------
    private void touchInteract() {
        mIdleHandler.removeCallbacks(mIdleClear);
        mIdleHandler.postDelayed(mIdleClear, 3000);
    }

    private void updateRevealTarget() {
        float target = (mRevealed && mPlaying) ? 1f : 0f;
        if (mRevealAnim != null) mRevealAnim.cancel();
        if (Math.abs(mRevealA - target) < 0.001f) {
            mRevealA = target;
            updateChipTarget();
            invalidate();
            return;
        }
        mRevealAnim = ValueAnimator.ofFloat(mRevealA, target);
        mRevealAnim.setDuration(250);
        mRevealAnim.setInterpolator(BS4_A);
        mRevealAnim.addUpdateListener(a -> {
            mRevealA = (float) a.getAnimatedValue();
            updateChipTarget();
            invalidate();
        });
        mRevealAnim.start();
    }

    private void updateChipTarget() {
        float target = (mDragging ? 0.28f : 0.55f) * mRevealA;
        if (mChipAnim != null) mChipAnim.cancel();
        if (Math.abs(mChipA - target) < 0.001f) {
            mChipA = target;
            return;
        }
        mChipAnim = ValueAnimator.ofFloat(mChipA, target);
        mChipAnim.setDuration(150);
        mChipAnim.setInterpolator(BS4_A);
        mChipAnim.addUpdateListener(a -> {
            mChipA = (float) a.getAnimatedValue();
            invalidate();
        });
        mChipAnim.start();
    }

    private void animateFraction(float target) {
        if (mDispFraction < 0f) {
            mDispFraction = target;
            return;
        }
        if (Math.abs(mDispFraction - target) < 0.0005f) return;
        if (mFractionAnim != null) mFractionAnim.cancel();
        mFractionAnim = ValueAnimator.ofFloat(mDispFraction, target);
        mFractionAnim.setDuration(250);
        mFractionAnim.setInterpolator(new LinearInterpolator());
        mFractionAnim.addUpdateListener(a -> {
            mDispFraction = (float) a.getAnimatedValue();
            invalidate();
        });
        mFractionAnim.start();
    }

    // ---------- seek 映射 ----------
    static long scrubSeekMs(float f, long duration) {
        float c = Math.max(0f, Math.min(1f, f));
        long ms = (long) (c * duration);
        long max = Math.max(0L, duration - 300L);
        return Math.max(0L, Math.min(ms, max));
    }

    static float scrubMaxFraction(long duration) {
        if (duration <= 0) return 1f;
        float v = (float) (duration - 300L) / (float) duration;
        return Math.max(0f, Math.min(1f, v));
    }

    private float displayFraction() {
        if (mDragging) return mDragFraction;
        return mDispFraction >= 0f ? mDispFraction : 0f;
    }

    private long displayPosition() {
        if (mDragging) return scrubSeekMs(mDragFraction, mDuration);
        return mPosition;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        float textH = mBottomPaint.descent() - mBottomPaint.ascent();
        int h = Math.round(dp(28) + dp(4) + dp(18) + dp(2) + textH);
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
        float f = displayFraction();

        // ---------- 顶盒（28dp） ----------
        float topBoxH = dp(28);
        mTimePaint.setAlpha(Math.round(255 * mTimeAlpha));
        float timeY = topBoxH / 2f - (mTimePaint.ascent() + mTimePaint.descent()) / 2f;
        canvas.drawText(fmt(displayPosition()), 0, timeY, mTimePaint);
        String durText = fmt(mDuration);
        float dw = mTimePaint.measureText(durText);
        canvas.drawText(durText, w - dw, timeY, mTimePaint);

        // 双筹（居中，间距 6dp，内边距 12×5）
        float chipH = 11f * mScaled + dp(10);
        mChipTop = (topBoxH - chipH) / 2f;
        mChipBottom = mChipTop + chipH;
        float gap = dp(6);
        String qText = qualityText();
        float qW = qText.isEmpty() ? 0 : mChipTextPaint.measureText(qText) + dp(24);
        float lW = mLyricLabel.isEmpty() ? 0 : mChipTextPaint.measureText(mLyricLabel) + dp(24);
        float total = qW + (qW > 0 && lW > 0 ? gap : 0) + lW;
        float x = (w - total) / 2f;
        if (qW > 0) {
            mQualityChipL = x;
            mQualityChipR = x + qW;
            drawChip(canvas, mQualityChipL, mQualityChipR, qText);
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

        // ---------- 轨道（18dp，收缩画法） ----------
        float trackTop = topBoxH + dp(4);
        float trackCy = trackTop + dp(18) / 2f;
        float fullW = w;
        float sf = mShrinkF;
        float drawW = sf >= 1f ? fullW : (fullW - dp(60)) * sf + dp(60);
        float trackL = (w - drawW) / 2f;
        float trackR = trackL + drawW;
        float trackH = dp(mDragging ? 5 : 4);
        float idleTrack = mDragging ? 0.22f : 0.14f;
        float trackAlpha = (idleTrack - 0.22f) * sf + 0.22f;
        float idleFill = mDragging ? 0.78f : 0.42f;
        float fillAlpha = (idleFill - 0.34f) * sf + 0.34f;
        mTrackPaint.setColor(0xFFFFFFFF);
        mTrackPaint.setAlpha(Math.round(255 * trackAlpha));
        mTmp.set(trackL, trackCy - trackH / 2f, trackR, trackCy + trackH / 2f);
        canvas.drawRoundRect(mTmp, trackH / 2f, trackH / 2f, mTrackPaint);
        float fillW = f * drawW;
        if (f > 0f) fillW = Math.max(fillW, trackH);
        if (fillW > 0f) {
            int aTop = Math.round(255 * fillAlpha * 0.72f);
            int aBot = Math.round(255 * fillAlpha);
            mFillPaint.setShader(new LinearGradient(0, trackCy - trackH / 2f, 0, trackCy + trackH / 2f,
                    (aTop << 24) | 0x00FFFFFF, (aBot << 24) | 0x00FFFFFF, Shader.TileMode.CLAMP));
            mTmp.set(trackL, trackCy - trackH / 2f, trackL + fillW, trackCy + trackH / 2f);
            canvas.drawRoundRect(mTmp, trackH / 2f, trackH / 2f, mFillPaint);
            mFillPaint.setShader(null);
        }

        // ---------- 底行（左已播、右 −剩余，12sp 白 0.65，内边距 (2,2,2,0)） ----------
        float baseY = trackTop + dp(18) + dp(2) - mBottomPaint.ascent();
        canvas.drawText(fmt(displayPosition()), dp(2), baseY, mBottomPaint);
        String rem = "−" + fmt(Math.max(0, mDuration - displayPosition()));
        float rw = mBottomPaint.measureText(rem);
        canvas.drawText(rem, w - dp(2) - rw, baseY, mBottomPaint);
    }

    private void drawChip(Canvas canvas, float l, float r, String label) {
        if (mChipA <= 0.001f) {
            return;
        }
        mTmp.set(l, mChipTop, r, mChipBottom);
        float rad = (mChipBottom - mChipTop) / 2f;
        int aTop = Math.round(255 * 0.10f * mChipA);
        int aBot = Math.round(255 * 0.04f * mChipA);
        mChipBgPaint.setShader(new LinearGradient(0, mChipTop, 0, mChipBottom,
                (aTop << 24) | 0x00FFFFFF, (aBot << 24) | 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(mTmp, rad, rad, mChipBgPaint);
        mChipBgPaint.setShader(null);
        mChipStrokePaint.setAlpha(Math.round(255 * 0.14f * mChipA));
        canvas.drawRoundRect(mTmp, rad, rad, mChipStrokePaint);
        mChipStrokePaint.setAlpha(255);
        mChipTextPaint.setAlpha(Math.round(255 * 0.72f * mChipA));
        float ty = (mChipTop + mChipBottom) / 2f - (mChipTextPaint.ascent() + mChipTextPaint.descent()) / 2f;
        canvas.drawText(label, l + dp(12), ty, mChipTextPaint);
        mChipTextPaint.setAlpha(255);
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

    private void emitPreview() {
        mPreviewMs = scrubSeekMs(mDragFraction, mDuration);
        if (mListener != null) mListener.onScrubPreview(mPreviewMs);
    }

    private void clearPreview() {
        if (mPreviewMs != null) {
            mPreviewMs = null;
            if (mListener != null) mListener.onScrubPreview(-1L);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 筹可点门（瓜子 z52/z54）：唤出动画值 >0.5 才算启用；音质筹另有本地旗（本地时不可点）
                mDownOnChip = mRevealed && mRevealA > 0.5f && y <= mChipBottom + dp(4)
                        && (((x >= mQualityChipL && x <= mQualityChipR && mQualityChipR > 0) && !mLocalTrack)
                        || (x >= mLyricChipL && x <= mLyricChipR && mLyricChipR > 0));
                if (mDownOnChip) return true;
                if (mDuration <= 0) return true;
                if (!mRevealed) {
                    // 未唤出：只唤出，不跳
                    mRevealed = true;
                    updateRevealTarget();
                    touchInteract();
                    return true;
                }
                mDragging = true;
                mDragFraction = fractionFromX(x, false);
                emitPreview();
                animateTimeAlpha(0.72f);
                updateChipTarget();
                touchInteract();
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mDragging) {
                    mDragFraction = fractionFromX(x, true);
                    emitPreview();
                    touchInteract();
                    invalidate();
                    return true;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (mDownOnChip) {
                    if (mListener != null) {
                        if (x >= mQualityChipL && x <= mQualityChipR && mQualityChipR > 0 && !mLocalTrack) {
                            mListener.onQualityChip();
                        } else if (x >= mLyricChipL && x <= mLyricChipR && mLyricChipR > 0) {
                            mListener.onLyricChip();
                        }
                    }
                    mDownOnChip = false;
                    mRevealed = true;
                    updateRevealTarget();
                    touchInteract();
                    return true;
                }
                if (mDragging) {
                    mDragging = false;
                    long seek = scrubSeekMs(mDragFraction, mDuration);
                    clearPreview();
                    animateTimeAlpha(0f);
                    updateChipTarget();
                    touchInteract();
                    if (mListener != null) mListener.onSeek(seek);
                    invalidate();
                    return true;
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (mDragging) {
                    mDragging = false;
                    clearPreview();
                    animateTimeAlpha(0f);
                    updateChipTarget();
                }
                mDownOnChip = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private float fractionFromX(float x, boolean cap) {
        int w = getWidth();
        if (w <= 0) return 0f;
        float v = Math.max(0f, Math.min(1f, x / w));
        return cap ? Math.min(v, scrubMaxFraction(mDuration)) : v;
    }

    @Override
    protected void onDetachedFromWindow() {
        mIdleHandler.removeCallbacks(mIdleClear);
        super.onDetachedFromWindow();
    }
}
