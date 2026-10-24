package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.player.lyrics.LyricsLine;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放页内嵌歌词（批 1 行级版，逐字引擎归批 2）：照 §6.2 封面歌词口径——
 * 窗口前 2 后 6、目标可见 4 行；字号默认 22sp 粗体、行高＝字号×4/3、行距 20dp、
 * 横向边距由布局 padding 给（28dp）、左对齐；副行（翻译）字号＝主行×0.62、字重 500、默认开。
 * 非当前行压暗（深色面回落口径：白 0.30）＋模糊：最终半径换算链未抠到（§19），
 * 这里只用已有手段 BlurMaskFilter 按档位系数给一个固定小半径，不冒充瓜子公式。
 * 歌词字带一点斜：SKEW=-0.12（定案，取旧 KaraokeLyricsView 的 SKEW 同量，非瓜子值）。
 * 点行＝回调行首时间（定案点歌词行快进）；超长行截断加省略号（D-11 承接）。
 */
public class CoverLyricView extends View {

    public interface OnLineClickListener {
        void onLineClick(int index, long positionMs);
    }

    /** 歌词斜度（定案）：与旧 KaraokeLyricsView.SKEW 同量 -0.12。 */
    private static final float SKEW = -0.12f;
    private static final int WINDOW_BEFORE = 2;
    private static final int WINDOW_AFTER = 6;
    private static final float LINE_GAP_DP = 20f;
    private static final float SUB_RATIO = 0.62f;

    private final TextPaint mainPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<LyricsLine> lines = new ArrayList<>();
    private final List<String> subs = new ArrayList<>();
    private int currentIndex = -1;
    private float fontSizeSp = 22f;
    private float blurCoefficient = 1.0f;
    private boolean showSub = true;
    private String emptyText = "";
    private OnLineClickListener lineClickListener;

    public CoverLyricView(Context context) {
        super(context);
        initPaints();
    }

    public CoverLyricView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    private void initPaints() {
        mainPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        mainPaint.setTextSkewX(SKEW);
        mainPaint.setColor(0xFFFFFFFF);
        subPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        subPaint.setTextSkewX(SKEW);
        subPaint.setColor(0x8CFFFFFF);
        applyFontSize();
    }

    private void applyFontSize() {
        float density = getResources().getDisplayMetrics().scaledDensity;
        mainPaint.setTextSize(fontSizeSp * density);
        subPaint.setTextSize(fontSizeSp * SUB_RATIO * density);
    }

    public void setFontSizeSp(float sp) {
        fontSizeSp = Math.max(14f, Math.min(32f, sp));
        applyFontSize();
        invalidate();
    }

    public void setBlurCoefficient(float coefficient) {
        blurCoefficient = Math.max(0f, Math.min(1f, coefficient));
        invalidate();
    }

    public void setShowSub(boolean show) {
        showSub = show;
        invalidate();
    }

    public void setEmptyText(String text) {
        emptyText = text == null ? "" : text;
        invalidate();
    }

    public void setOnLineClickListener(OnLineClickListener listener) {
        lineClickListener = listener;
    }

    public void setLines(List<LyricsLine> mains, List<String> subLines) {
        lines.clear();
        subs.clear();
        if (mains != null) lines.addAll(mains);
        if (subLines != null) subs.addAll(subLines);
        currentIndex = -1;
        invalidate();
    }

    public void clearLines() {
        lines.clear();
        subs.clear();
        currentIndex = -1;
        invalidate();
    }

    /** 当前行随播放进度推进（position 已含歌词偏移，调用方加）。 */
    public void setPosition(long positionMs) {
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getTimeMs() <= positionMs) idx = i;
            else break;
        }
        if (idx != currentIndex) {
            currentIndex = idx;
            invalidate();
        }
    }

    private float mainLineHeightPx() {
        return mainPaint.getTextSize() * 4f / 3f;
    }

    private float subLineHeightPx() {
        return subPaint.getTextSize() * 4f / 3f;
    }

    private float gapPx() {
        return LINE_GAP_DP * getResources().getDisplayMetrics().density;
    }

    private float slotHeightPx() {
        return mainLineHeightPx() + (showSub ? subLineHeightPx() : 0f) + gapPx();
    }

    private int windowStart() {
        if (currentIndex < 0) return 0;
        return Math.max(0, currentIndex - WINDOW_BEFORE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        if (width <= 0) return;
        float x = getPaddingLeft();
        if (lines.isEmpty()) {
            if (!emptyText.isEmpty()) {
                subPaint.setAlpha(140);
                subPaint.setMaskFilter(null);
                canvas.drawText(emptyText, x, getPaddingTop() + subLineHeightPx(), subPaint);
            }
            return;
        }
        float slot = slotHeightPx();
        float avail = getHeight() - getPaddingTop() - getPaddingBottom();
        int maxRows = Math.max(1, (int) Math.floor(avail / slot));
        int start = windowStart();
        int end = Math.min(lines.size(), Math.min(start + WINDOW_AFTER + WINDOW_BEFORE + 1, start + maxRows));
        float baseline = getPaddingTop() + mainPaint.getTextSize();
        for (int i = start; i < end; i++) {
            int distance = currentIndex < 0 ? i : Math.abs(i - currentIndex);
            boolean current = i == currentIndex;
            // 主行：当前行全白；非当前行压暗（深色面非当前 白 0.30），距当前 2 行外加已有手段模糊
            mainPaint.setAlpha(current ? 255 : 77);
            if (!current && distance >= 2 && blurCoefficient > 0f) {
                mainPaint.setMaskFilter(new BlurMaskFilter(blurCoefficient * 4f, BlurMaskFilter.Blur.NORMAL));
            } else {
                mainPaint.setMaskFilter(null);
            }
            CharSequence main = TextUtils.ellipsize(lines.get(i).getText(), mainPaint, width, TextUtils.TruncateAt.END);
            canvas.drawText(main, 0, main.length(), x, baseline, mainPaint);
            mainPaint.setMaskFilter(null);
            if (showSub) {
                String sub = i < subs.size() ? subs.get(i) : null;
                if (sub != null && !sub.isEmpty()) {
                    subPaint.setAlpha(current ? 140 : 84);
                    float subBaseline = baseline + subLineHeightPx() * 0.92f;
                    CharSequence subText = TextUtils.ellipsize(sub, subPaint, width, TextUtils.TruncateAt.END);
                    canvas.drawText(subText, 0, subText.length(), x, subBaseline, subPaint);
                }
            }
            baseline += slot;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP && lineClickListener != null && !lines.isEmpty()) {
            float y = event.getY() - getPaddingTop();
            if (y >= 0) {
                int row = (int) Math.floor(y / slotHeightPx());
                int idx = windowStart() + row;
                if (idx >= 0 && idx < lines.size()) {
                    lineClickListener.onLineClick(idx, lines.get(idx).getTimeMs());
                    performClick();
                }
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
