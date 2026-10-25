package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.player.lyrics.LyricsLine;

import java.util.ArrayList;
import java.util.List;

/**
 * 流光 CD 歌词展开窗（批 3 D 路，自有实现；不改批 2 歌词引擎四文件，只消费 LyricsLine 数据）：
 * 照施工图 §4——字号 21sp、行高＝字号×4/3、行距（槽附加）20dp、居中、加粗；
 * 调色 PlayerLyricPalette(ink, ink×0.42, ink×0.38, ink×0.42)（sung/activeUnsung/inactive/translation）；
 * 当前行 alpha 1.0、其余 0.55（任务口径）；歌词字带一点斜 SKEW −0.12（定案，非瓜子值，
 * 与 CoverLyricView 同口径）；副行（翻译）字号＝主行×0.62、字重 500。
 * 窗口：当前行块居中锚（2026-10-25 深挖抠实：max((窗高−块高)/2, 字号量)，旧「窗高 40%」作废），
 * 前后行按槽高排布；点行＝回调行首时间（页级口径：点歌词行 seek）。
 * 非当前行模糊：源头距离公式未抠到（§19-2），本窗不冒充——只压暗不挂模糊（报告明记）。
 */
public class CdLyricWindowView extends View {

    public interface OnLineClickListener {
        void onLineClick(int index, long positionMs);
    }

    private static final float FONT_SP = 21f;
    private static final float LINE_GAP_DP = 20f;
    private static final float SKEW = -0.12f;

    private final TextPaint mainPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<LyricsLine> lines = new ArrayList<>();
    private final List<String> subs = new ArrayList<>();
    private int currentIndex = -1;
    private int inkColor = 0xFF282D36;
    private boolean showSub = true;
    private String emptyText = "";
    private OnLineClickListener lineClickListener;

    private boolean playing;
    private long anchorPosMs;
    private long anchorElapsedMs;
    private boolean framePosted;
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            framePosted = false;
            if (!playing || lines.isEmpty()) return;
            updateCurrentIndex(displayPositionMs());
            invalidate();
            postFrame();
        }
    };

    public CdLyricWindowView(Context context) {
        super(context);
        init();
    }

    public CdLyricWindowView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        mainPaint.setTextSize(FONT_SP * scaled);
        mainPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        mainPaint.setTextSkewX(SKEW);
        subPaint.setTextSize(FONT_SP * 0.62f * scaled);
        subPaint.setTextSkewX(SKEW);
    }

    /** 调色：ink 为准，余三色按 §4 palette 的 alpha 系数（0.42/0.38/0.42）合成。 */
    public void setInkColor(int ink) {
        inkColor = ink;
        invalidate();
    }

    /**
     * 主歌词字重（批 7 补线：键 music_lyric_weight 0 标准／1 加粗／2 特粗）。
     * 映射与 LyricLineDrawer.setLyricWeight 同口径（0→400、1→700、2→900／低版近似）。
     * 对齐不接：本窗居中是 §4 瓜子定式，不随设置键走（见批 7 报告）。
     */
    public void setLyricWeight(int weight) {
        android.graphics.Typeface base;
        boolean fake = false;
        if (weight >= 2) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                base = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 900);
            } else {
                base = android.graphics.Typeface.DEFAULT_BOLD;
                fake = true;
            }
        } else if (weight == 1) {
            base = android.graphics.Typeface.DEFAULT_BOLD;
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            base = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 400);
        } else {
            base = android.graphics.Typeface.DEFAULT;
        }
        mainPaint.setTypeface(base);
        mainPaint.setFakeBoldText(fake);
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

    public int currentIndex() {
        return currentIndex;
    }

    public void setLines(List<LyricsLine> mains, List<String> subLines) {
        lines.clear();
        subs.clear();
        if (mains != null) lines.addAll(mains);
        if (subLines != null) subs.addAll(subLines);
        currentIndex = -1;
        anchorPosMs = 0;
        anchorElapsedMs = SystemClock.elapsedRealtime();
        invalidate();
        postFrame();
    }

    public void clearLines() {
        lines.clear();
        subs.clear();
        currentIndex = -1;
        invalidate();
    }

    public void setPlaying(boolean value) {
        if (playing == value) return;
        anchorPosMs = displayPositionMs();
        anchorElapsedMs = SystemClock.elapsedRealtime();
        playing = value;
        postFrame();
        invalidate();
    }

    public void setPosition(long positionMs) {
        anchorPosMs = positionMs;
        anchorElapsedMs = SystemClock.elapsedRealtime();
        updateCurrentIndex(positionMs);
        invalidate();
        postFrame();
    }

    private long displayPositionMs() {
        if (!playing) return anchorPosMs;
        return anchorPosMs + (SystemClock.elapsedRealtime() - anchorElapsedMs);
    }

    private void updateCurrentIndex(long positionMs) {
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getTimeMs() <= positionMs) idx = i;
            else break;
        }
        currentIndex = idx;
    }

    private void postFrame() {
        if (framePosted || !playing || lines.isEmpty()) return;
        framePosted = true;
        choreographer.postFrameCallback(frameCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (framePosted) {
            choreographer.removeFrameCallback(frameCallback);
            framePosted = false;
        }
        super.onDetachedFromWindow();
    }

    private float mainHeightPx() {
        return mainPaint.getTextSize() * (4f / 3f);
    }

    private float subHeightPx() {
        return subPaint.getTextSize() * (4f / 3f);
    }

    private float slotHeightPx() {
        float density = getResources().getDisplayMetrics().density;
        return mainHeightPx() + (showSub ? subHeightPx() : 0f) + LINE_GAP_DP * density;
    }

    private int withAlpha(int color, float alpha) {
        int a = Math.round(255f * Math.max(0f, Math.min(1f, alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /**
     * 当前行块顶 Y（视图坐标）：GZ CoverCurrentLyric 居中支实证（2026-10-25 深挖）——
     * anchor＝max((内容高 − 当前行块高)/2, 字号量)，字号量＝字号px（0.75×行高，行高＝字号×4/3）；
     * 块高按当前行实有构成（主行＋有副行时副行）近 GZ 的实测块高。星辰旧版「窗高 40%」作废。
     */
    private float anchorY() {
        float contentH = getHeight() - getPaddingTop() - getPaddingBottom();
        float blockH = mainHeightPx();
        int cur = Math.max(0, currentIndex);
        if (showSub && cur < subs.size()) {
            String sub = subs.get(cur);
            if (sub != null && !sub.isEmpty()) blockH += subHeightPx();
        }
        float centered = (contentH - blockH) / 2f;
        return getPaddingTop() + Math.max(centered, mainPaint.getTextSize());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        if (width <= 0) return;
        float x0 = getPaddingLeft();
        if (lines.isEmpty()) {
            if (!emptyText.isEmpty()) {
                subPaint.setColor(withAlpha(inkColor, 0.42f));
                drawCentered(canvas, emptyText, subPaint, x0, getPaddingTop() + subHeightPx(), width);
            }
            return;
        }
        float slot = slotHeightPx();
        float anchorY = anchorY();
        int cur = Math.max(0, currentIndex);
        float baseline = anchorY - cur * slot + mainPaint.getTextSize();
        for (int i = 0; i < lines.size(); i++) {
            float top = anchorY - cur * slot + i * slot;
            if (top + slot < getPaddingTop() || top > getHeight() - getPaddingBottom()) {
                continue;
            }
            boolean current = i == currentIndex;
            LyricsLine line = lines.get(i);
            // 调色走 palette 四值（LuminousCdPlayerKt.java:3661-3662）：当前行＝ink（sung 色）、
            // 非当前主行＝ink×0.38、副行＝ink×0.42，均不再整行乘 alpha（原非当前 0.55、副行叠乘后约 0.23 不符）。
            // 当前行已唱/未唱分段（未唱 ink×0.42）需逐字口径、本窗行级渲染不做，记对照表。
            mainPaint.setColor(current ? inkColor : withAlpha(inkColor, 0.38f));
            mainPaint.setAlpha(255);
            drawCentered(canvas, line.getText() == null ? "" : line.getText(), mainPaint, x0, baseline + i * slot, width);
            if (showSub) {
                String sub = i < subs.size() ? subs.get(i) : null;
                if (sub != null && !sub.isEmpty()) {
                    subPaint.setColor(withAlpha(inkColor, 0.42f));
                    subPaint.setAlpha(255);
                    drawCentered(canvas, sub, subPaint, x0, baseline + i * slot + subHeightPx() * 0.92f, width);
                }
            }
        }
        mainPaint.setAlpha(255);
        subPaint.setAlpha(255);
    }

    private void drawCentered(Canvas canvas, String text, TextPaint paint, float x0, float baseline, int width) {
        CharSequence draw = android.text.TextUtils.ellipsize(text, paint, width, android.text.TextUtils.TruncateAt.END);
        float w = paint.measureText(draw, 0, draw.length());
        canvas.drawText(draw, 0, draw.length(), x0 + Math.max(0f, (width - w) / 2f), baseline, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (!lines.isEmpty()) {
                float slot = slotHeightPx();
                float anchorY = anchorY();
                int cur = Math.max(0, currentIndex);
                int idx = cur + Math.round((event.getY() - anchorY) / slot);
                if (idx >= 0 && idx < lines.size()) {
                    // 点行＝seek（不触发 View 点击，避免与宿主的点窗收起打架）
                    if (lineClickListener != null) lineClickListener.onLineClick(idx, lines.get(idx).getTimeMs());
                    return true;
                }
            }
            // 点窗空白区＝View 点击（宿主接作收起）
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
