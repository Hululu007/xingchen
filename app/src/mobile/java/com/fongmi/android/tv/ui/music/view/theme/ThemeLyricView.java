package com.fongmi.android.tv.ui.music.view.theme;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.PathInterpolator;

import com.fongmi.android.tv.ui.music.MusicPlayerSettings;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsWord;

import java.util.List;

/**
 * 主题自带歌词窗（流光 CD／黑胶唱片架共用）：两主题在瓜子侧各供自家 palette、
 * 不走页内歌词引擎的白口径，故单独一台轻量渲染：当前行居锚、行距/字号由主题给，
 * 当前行逐字按词时变色（已唱/未唱两色），非当前行与翻译行走 palette 对应档。
 * 滚动：行变更时 350ms/bs4.a 补间到新锚位（与瓜子行对齐 tween 同值）。
 */
public class ThemeLyricView extends View {

    public interface Palette {
        int sung();

        int currentUnsung();

        int inactive();

        int translation();
    }

    private static final PathInterpolator BS4_A = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<RichLyrics.Line> mLines;
    private Palette mPalette;
    private float mTextSizeSp = 21f;
    private boolean mBold;
    private boolean mCenter;
    private float mLineHeightRatio = 4f / 3f;
    private float mLineGapDp = 20f;
    /** 锚点：当前行顶在可视区顶下方的比例（0=贴顶）。 */
    private float mAnchorFraction = 0f;
    private long mPos;
    private int mCurrent = -1;
    private float mScrollPx;
    private ValueAnimator mScrollAnim;

    public ThemeLyricView(Context context) {
        super(context);
    }

    public void configure(Palette palette, float textSizeSp, boolean bold, boolean center,
                          float lineHeightRatio, float lineGapDp, float anchorFraction) {
        mPalette = palette;
        mTextSizeSp = textSizeSp;
        mBold = bold;
        mCenter = center;
        mLineHeightRatio = lineHeightRatio;
        mLineGapDp = lineGapDp;
        mAnchorFraction = anchorFraction;
        invalidate();
    }

    public void setLines(List<RichLyrics.Line> lines) {
        mLines = lines;
        mCurrent = -1;
        mScrollPx = 0;
        if (mScrollAnim != null) mScrollAnim.cancel();
        invalidate();
    }

    public void setPosition(long pos) {
        mPos = pos;
        int idx = indexAt(pos);
        if (idx != mCurrent) {
            mCurrent = idx;
            animateScrollTo(idx);
        }
        invalidate();
    }

    private int indexAt(long pos) {
        if (mLines == null || mLines.isEmpty()) return -1;
        int ans = -1;
        for (int i = 0; i < mLines.size(); i++) {
            RichLyrics.Line l = mLines.get(i);
            long t = l == null || l.main == null ? Long.MAX_VALUE : l.main.getTimeMs();
            if (t <= pos) ans = i;
            else break;
        }
        return ans;
    }

    private float stepPx() {
        float density = getResources().getDisplayMetrics().density;
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        return mTextSizeSp * mLineHeightRatio * scaled + mLineGapDp * density;
    }

    private void animateScrollTo(int idx) {
        if (idx < 0) return;
        final float from = mScrollPx;
        final float to = idx * stepPx();
        if (Math.abs(to - from) < 0.5f) {
            mScrollPx = to;
            return;
        }
        if (mScrollAnim != null) mScrollAnim.cancel();
        mScrollAnim = ValueAnimator.ofFloat(from, to);
        mScrollAnim.setDuration(350);
        mScrollAnim.setInterpolator(BS4_A);
        mScrollAnim.addUpdateListener(a -> {
            mScrollPx = (float) a.getAnimatedValue();
            invalidate();
        });
        mScrollAnim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mPalette == null || mLines == null || mLines.isEmpty()) return;
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        float sizePx = mTextSizeSp * scaled;
        float step = stepPx();
        float anchorY = getHeight() * mAnchorFraction;
        mPaint.setTextSize(sizePx);
        mPaint.setTypeface(mBold ? android.graphics.Typeface.DEFAULT_BOLD
                : android.graphics.Typeface.DEFAULT);
        boolean showTrans = false;
        try {
            showTrans = MusicPlayerSettings.isShowTranslation();
        } catch (Throwable ignored) {
        }
        int first = Math.max(0, (int) Math.floor((mScrollPx - anchorY) / step) - 1);
        for (int i = first; i < mLines.size(); i++) {
            float top = anchorY + i * step - mScrollPx;
            if (top > getHeight()) break;
            if (top + step < 0) continue;
            RichLyrics.Line line = mLines.get(i);
            if (line == null || line.main == null) continue;
            String txt = line.main.getText() == null ? "" : line.main.getText();
            float baseline = top + sizePx;
            if (i == mCurrent) {
                drawCurrentLine(canvas, line, txt, baseline, sizePx);
            } else {
                mPaint.setColor(mPalette.inactive());
                drawAligned(canvas, txt, baseline);
            }
            if (showTrans && line.translation != null && !line.translation.isEmpty()) {
                mPaint.setTextSize(sizePx * 0.62f);
                mPaint.setColor(mPalette.translation());
                drawAligned(canvas, line.translation, baseline + sizePx * 0.78f);
                mPaint.setTextSize(sizePx);
            }
        }
    }

    private void drawAligned(Canvas canvas, String txt, float baseline) {
        if (mCenter) {
            float w = mPaint.measureText(txt);
            // 过宽居中行按可用宽截断（瓜子封面窗当前行最多 2 行的折行不在本窗实现，单行省略）
            float avail = getWidth();
            String s = txt;
            while (s.length() > 1 && mPaint.measureText(s + "…") > avail) {
                s = s.substring(0, s.length() - 1);
            }
            if (!s.equals(txt)) s = s + "…";
            canvas.drawText(s, (getWidth() - mPaint.measureText(s)) / 2f, baseline, mPaint);
        } else {
            float avail = getWidth();
            String s = txt;
            while (s.length() > 1 && mPaint.measureText(s + "…") > avail) {
                s = s.substring(0, s.length() - 1);
            }
            if (!s.equals(txt)) s = s + "…";
            canvas.drawText(s, 0, baseline, mPaint);
        }
    }

    /** 当前行：有逐字词时逐词两色（已唱 sung／未唱 currentUnsung），无词整行 sung。 */
    private void drawCurrentLine(Canvas canvas, RichLyrics.Line line, String txt,
                                 float baseline, float sizePx) {
        LyricsLine main = line.main;
        List<LyricsWord> words = main.getWords();
        if (words == null || words.isEmpty() || !main.hasWords()) {
            mPaint.setColor(mPalette.sung());
            drawAligned(canvas, txt, baseline);
            return;
        }
        // 逐词横排：先量总宽定起点（居中/左对齐），逐词按词终点判定已唱
        float total = 0;
        for (LyricsWord w : words) total += mPaint.measureText(w.getText() == null ? "" : w.getText());
        float x = mCenter ? Math.max(0, (getWidth() - total) / 2f) : 0;
        long lineStart = main.getTimeMs();
        for (LyricsWord w : words) {
            String wt = w.getText() == null ? "" : w.getText();
            long end = lineStart + w.getStartOffsetMs() + Math.max(1, w.getDurationMs());
            mPaint.setColor(mPos >= end ? mPalette.sung() : mPalette.currentUnsung());
            canvas.drawText(wt, x, baseline, mPaint);
            x += mPaint.measureText(wt);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mScrollAnim != null) mScrollAnim.cancel();
        super.onDetachedFromWindow();
    }
}
