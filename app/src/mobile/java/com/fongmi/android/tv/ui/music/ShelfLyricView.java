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
 * 黑胶唱片架歌词面（批 3 D 路，自有实现；不改批 2 歌词引擎四文件）：
 * 照施工图 §5——字号 22sp（GZ CoverCurrentLyric 以 22 调；本页字号走设置 getCoverLyricFontSize、
 * 出厂 22）、行高＝字号×4/3、行距 20dp、左对齐（行基线 x＝0，任务口径；GZ 对齐参数未逐字核到，见报告）、
 * 歌词字带一点斜 SKEW −0.12（定案）。palette＝PlayerLyricPalette(h0, h0×0.48, ShelfMuted×0.72, ShelfMuted)，
 * h0＝语气色混 ShelfInk 0.65（由宿主算好注入 sung 色；inactive 直接给 ShelfMuted）。
 * 非当前行 alpha 0.5（任务口径）＋模糊 medium：模糊半径走 §6.1 量化公式
 * （≤0.5 全清、0.5–1.5 round(强度×2)/2 档、&gt;1.5 封顶 4.0dp——即 LyricWordEngine.quantizeBlurRadius 公开口径，
 * 本视图只读其静态方法不改其源）；模糊实现用 BlurMaskFilter（RenderEffect 需 API 31 且与硬件加速合成层
 * 有兼容面，本面行数少走面具模糊，效果口径等价、半径数值同源）。
 * X-11：歌词数据从空→有才淡入——setLines 时若此前无词，本面 300ms 从 alpha 0 淡入（时长无 GZ 字面值，
 * 报告明记星辰侧近似）；同歌重复喂数不重播淡入。点行＝回调行首时间 seek（页级口径）。
 */
public class ShelfLyricView extends View {

    public interface OnLineClickListener {
        void onLineClick(int index, long positionMs);
    }

    private static final float SKEW = -0.12f;
    private static final float LINE_GAP_DP = 20f;
    private static final long FADE_IN_MS = 300;

    private final TextPaint mainPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<LyricsLine> lines = new ArrayList<>();
    private final List<String> subs = new ArrayList<>();
    private int currentIndex = -1;
    private int sungColor = 0xFF252522;
    private int inactiveColor = 0xFF86857E;
    private float fontSp = 22f;
    private int weight = 1; // 主行字重档（批 7：默认 1＝旧写死 DEFAULT_BOLD 的现行观感，设置下发后覆盖）
    private int textAlign; // 0 左（默认）／1 中／2 右
    private int blurLevel = 1; // 0 关、1 中（medium）、2 强
    private boolean showSub = true;
    private String emptyText = "";
    private OnLineClickListener lineClickListener;

    private boolean playing;
    private long anchorPosMs;
    private long anchorElapsedMs;
    private long fadeStartMs = -1;
    private boolean framePosted;
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            framePosted = false;
            if (fadeActive()) {
                invalidate();
                postFrame();
                return;
            }
            if (!playing || lines.isEmpty()) return;
            updateCurrentIndex(displayPositionMs());
            invalidate();
            postFrame();
        }
    };

    public ShelfLyricView(Context context) {
        super(context);
        init();
    }

    public ShelfLyricView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        applyTextSizes();
    }

    private void applyTextSizes() {
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        mainPaint.setTextSize(fontSp * scaled);
        // 字重按设置键（批 7 接入前写死 DEFAULT_BOLD；applyTextSizes 在 setFontSizeSp 时重跑，须用 weight 字段重挂）
        mainPaint.setTypeface(weightTypeface());
        mainPaint.setFakeBoldText(weight >= 2
                && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P);
        mainPaint.setTextSkewX(SKEW);
        subPaint.setTextSize(fontSp * 0.62f * scaled);
        subPaint.setTextSkewX(SKEW);
    }

    /** 主行字重画笔（映射与 LyricLineDrawer.setLyricWeight 同口径：0→400、1→700、2→900／低版近似）。 */
    private android.graphics.Typeface weightTypeface() {
        if (weight >= 2) {
            return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
                    ? android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 900)
                    : android.graphics.Typeface.DEFAULT_BOLD;
        }
        if (weight == 1) return android.graphics.Typeface.DEFAULT_BOLD;
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
                ? android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 400)
                : android.graphics.Typeface.DEFAULT;
    }

    /** 主歌词字重（批 7 补线：键 music_lyric_weight 0 标准／1 加粗／2 特粗）。 */
    public void setLyricWeight(int value) {
        weight = value;
        applyTextSizes();
        invalidate();
    }

    /** 文本对齐（批 7 补线：键 music_lyric_align 0 左／1 中／2 右；此前行 X 写死左对齐）。 */
    public void setTextAlign(int align) {
        textAlign = (align == 1 || align == 2) ? align : 0;
        invalidate();
    }

    /** 行起点 X：按对齐与截断后实测宽算。 */
    private float rowX(float x0, int width, CharSequence draw, TextPaint paint) {
        if (textAlign == 0) return x0;
        float w = paint.measureText(draw, 0, draw.length());
        if (textAlign == 1) return x0 + Math.max(0f, (width - w) / 2f);
        return x0 + Math.max(0f, width - w);
    }

    public void setFontSizeSp(float sp) {
        fontSp = sp;
        applyTextSizes();
        invalidate();
    }

    /** palette：sung＝h0（宿主已混 Ink 0.65）、inactive＝ShelfMuted（宿主给）。 */
    public void setPaletteColors(int sung, int inactive) {
        sungColor = sung;
        inactiveColor = inactive;
        invalidate();
    }

    public void setBlurLevel(int level) {
        blurLevel = level;
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
        boolean wasEmpty = lines.isEmpty();
        lines.clear();
        subs.clear();
        if (mains != null) lines.addAll(mains);
        if (subLines != null) subs.addAll(subLines);
        currentIndex = -1;
        anchorPosMs = 0;
        anchorElapsedMs = SystemClock.elapsedRealtime();
        // X-11：词到才淡入——仅「空→有」触发，同批重复喂数不重播
        if (wasEmpty && !lines.isEmpty()) {
            fadeStartMs = SystemClock.elapsedRealtime();
        }
        invalidate();
        postFrame();
    }

    public void clearLines() {
        lines.clear();
        subs.clear();
        currentIndex = -1;
        fadeStartMs = -1;
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

    private boolean fadeActive() {
        return fadeStartMs > 0 && SystemClock.elapsedRealtime() - fadeStartMs < FADE_IN_MS;
    }

    private float fadeAlpha() {
        if (fadeStartMs <= 0) return 1f;
        float f = (SystemClock.elapsedRealtime() - fadeStartMs) / (float) FADE_IN_MS;
        return Math.max(0f, Math.min(1f, f));
    }

    private void postFrame() {
        if (framePosted) return;
        if (!fadeActive() && (!playing || lines.isEmpty())) return;
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

    /** 非当前行模糊半径（dp）：§6.1 量化公式——档位即强度（1 轻→round(1×2)/2＝1.0dp；2 中→强度&gt;1.5 封顶 4.0dp；关＝0）。 */
    private float blurRadiusDpFor(boolean current) {
        if (current || blurLevel <= 0) return 0f;
        float strength = blurLevel >= 2 ? 2f : 1f;
        if (strength <= 0.5f) return 0f;
        if (strength > 1.5f) return 4.0f;
        return Math.round(strength * 2f) / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        if (width <= 0) return;
        float x0 = getPaddingLeft();
        float fade = fadeAlpha();
        if (lines.isEmpty()) {
            if (!emptyText.isEmpty()) {
                subPaint.setColor(inactiveColor);
                subPaint.setMaskFilter(null);
                canvas.drawText(emptyText, x0, getPaddingTop() + subHeightPx(), subPaint);
            }
            return;
        }
        float slot = slotHeightPx();
        int cur = Math.max(0, currentIndex);
        // 当前行锚在面上部 12% 处（面内歌词自舞台底向下排，锚位星辰侧排布，GZ 锚公式未抠到，见报告）
        float anchorY = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) * 0.12f;
        float baseTop = anchorY - cur * slot;
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < lines.size(); i++) {
            float top = baseTop + i * slot;
            if (top + slot < getPaddingTop() || top > getHeight()) continue;
            boolean current = i == currentIndex;
            float blurDp = blurRadiusDpFor(current);
            android.graphics.BlurMaskFilter blur = blurDp > 0
                    ? new android.graphics.BlurMaskFilter(blurDp * density, android.graphics.BlurMaskFilter.Blur.NORMAL) : null;
            mainPaint.setColor(current ? sungColor : inactiveColor);
            mainPaint.setAlpha(Math.round(255f * fade * (current ? 1f : 0.5f)));
            mainPaint.setMaskFilter(blur);
            String text = lines.get(i).getText() == null ? "" : lines.get(i).getText();
            CharSequence draw = android.text.TextUtils.ellipsize(text, mainPaint, width, android.text.TextUtils.TruncateAt.END);
            canvas.drawText(draw, 0, draw.length(), rowX(x0, width, draw, mainPaint), top + mainPaint.getTextSize(), mainPaint);
            if (showSub) {
                String sub = i < subs.size() ? subs.get(i) : null;
                if (sub != null && !sub.isEmpty()) {
                    subPaint.setColor(inactiveColor);
                    subPaint.setAlpha(Math.round(255f * fade * (current ? 0.72f : 0.5f)));
                    subPaint.setMaskFilter(blur);
                    CharSequence dsub = android.text.TextUtils.ellipsize(sub, subPaint, width, android.text.TextUtils.TruncateAt.END);
                    canvas.drawText(dsub, 0, dsub.length(), rowX(x0, width, dsub, subPaint), top + mainHeightPx() + subPaint.getTextSize() * 0.92f, subPaint);
                }
            }
        }
        mainPaint.setMaskFilter(null);
        mainPaint.setAlpha(255);
        subPaint.setMaskFilter(null);
        subPaint.setAlpha(255);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP && lineClickListener != null && !lines.isEmpty()) {
            float slot = slotHeightPx();
            int cur = Math.max(0, currentIndex);
            float anchorY = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) * 0.12f;
            int idx = cur + Math.round((event.getY() - anchorY - mainPaint.getTextSize() / 2f) / slot);
            if (idx >= 0 && idx < lines.size()) {
                lineClickListener.onLineClick(idx, lines.get(idx).getTimeMs());
                performClick();
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
