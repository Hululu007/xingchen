package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

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
 * 跟滚（2026-10-25）：当前行锚位做成连续量 displayCur（单位=行），按 FullLyricView 同口径
 * 每帧 0.18 指数趋近——歌词面随当前行平滑滚，不再整槽跳变；点行只认点按（位移小于触摸
 * 阈值），滑动松手不许误触 seek。
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
    /** 面顶部内阴影带专用（GZ shelfInformationSurface 绘制修饰抠实，见 onDraw）。 */
    private final Paint shadowPaint = new Paint();
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
    /** 当前行锚位的连续值（单位=行）：向 currentIndex 趋近，见 stepScroll。 */
    private float displayCur;
    private float tapSlopPx;
    private float touchDownX;
    private float touchDownY;
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            framePosted = false;
            if (fadeActive()) {
                stepScroll();
                invalidate();
                postFrame();
                return;
            }
            if (lines.isEmpty()) return;
            if (playing) updateCurrentIndex(displayPositionMs());
            boolean scrolling = stepScroll();
            if (!playing && !scrolling) return;
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
        tapSlopPx = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        applyTextSizes();
    }

    /** 锚位趋近一帧：返回是否仍在滚动中（FullLyricView 同口径 0.18 趋近、亚行收口）。 */
    private boolean stepScroll() {
        float target = Math.max(0, currentIndex);
        float diff = target - displayCur;
        if (Math.abs(diff) < 0.004f) {
            displayCur = target;
            return false;
        }
        displayCur += diff * 0.18f;
        return true;
    }

    private boolean scrollAnimating() {
        return Math.abs(Math.max(0, currentIndex) - displayCur) >= 0.004f;
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

    private static int withAlphaOf(int color, float factor) {
        int a = Math.round(android.graphics.Color.alpha(color) * factor);
        return android.graphics.Color.argb(a, android.graphics.Color.red(color),
                android.graphics.Color.green(color), android.graphics.Color.blue(color));
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
        displayCur = 0f;
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
        displayCur = 0f;
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
        if (!fadeActive() && (!playing || lines.isEmpty()) && !scrollAnimating()) return;
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

    /**
     * 当前行锚位 Y（视图坐标）：GZ CoverCurrentLyric 钳制支实证（2026-10-25 深挖）——
     * anchor＝min(字号量＋槽高量, 0.55×内容高)；字号量＝字号px（0.75×行高，行高＝字号×4/3）、
     * 槽高量＝行高＋20dp（COVER_LINE_GAP）。星辰旧版「面高 12%」固定比例作废。
     */
    private float anchorY() {
        float density = getResources().getDisplayMetrics().density;
        float contentH = getHeight() - getPaddingTop() - getPaddingBottom();
        float fontPx = mainPaint.getTextSize();
        float want = fontPx + (fontPx * (4f / 3f) + LINE_GAP_DP * density);
        return getPaddingTop() + Math.min(want, contentH * 0.55f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // 面顶部内阴影带（GZ shelfInformationSurface 抠实）：高 min(20dp, 面高)，
        // 竖向渐变停点 0→黑α0.13、0.35→黑α0.055、1→透明。
        if (getHeight() > 0 && getWidth() > 0) {
            float density = getResources().getDisplayMetrics().density;
            float bandH = Math.min(20f * density, getHeight());
            shadowPaint.setShader(new LinearGradient(0, 0, 0, bandH,
                    new int[]{0x21000000, 0x0E000000, 0x00000000},
                    new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, getWidth(), bandH, shadowPaint);
            shadowPaint.setShader(null);
        }
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
        float cur = Math.max(0f, displayCur);
        float anchorY = anchorY();
        float baseTop = anchorY - cur * slot;
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < lines.size(); i++) {
            float top = baseTop + i * slot;
            if (top + slot < getPaddingTop() || top > getHeight()) continue;
            boolean current = i == currentIndex;
            float blurDp = blurRadiusDpFor(current);
            android.graphics.BlurMaskFilter blur = blurDp > 0
                    ? new android.graphics.BlurMaskFilter(blurDp * density, android.graphics.BlurMaskFilter.Blur.NORMAL) : null;
            // 调色直接用宿主 palette 四值（RecordShelfPlayerKt.java:335）：当前主行 sung、非当前主行 sung×0.48、
            // 当前副行 ShelfMuted×0.72、非当前副行 ShelfMuted——原自设叠乘（主行非当前 ×0.5、副行当前 ×0.72/非当前 ×0.5）摘除。
            mainPaint.setColor(current ? sungColor : withAlphaOf(sungColor, 0.48f));
            mainPaint.setAlpha(Math.round(255f * fade));
            mainPaint.setMaskFilter(blur);
            String text = lines.get(i).getText() == null ? "" : lines.get(i).getText();
            CharSequence draw = android.text.TextUtils.ellipsize(text, mainPaint, width, android.text.TextUtils.TruncateAt.END);
            canvas.drawText(draw, 0, draw.length(), rowX(x0, width, draw, mainPaint), top + mainPaint.getTextSize(), mainPaint);
            if (showSub) {
                String sub = i < subs.size() ? subs.get(i) : null;
                if (sub != null && !sub.isEmpty()) {
                    subPaint.setColor(current ? withAlphaOf(inactiveColor, 0.72f) : inactiveColor);
                    subPaint.setAlpha(Math.round(255f * fade));
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
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getX();
                touchDownY = event.getY();
                break;
            case MotionEvent.ACTION_UP:
                // 点行只认点按：位移超阈值是滑（页级手势），不许误触 seek。
                if (lineClickListener != null && !lines.isEmpty()
                        && Math.hypot(event.getX() - touchDownX, event.getY() - touchDownY) < tapSlopPx) {
                    float slot = slotHeightPx();
                    float anchorY = anchorY();
                    // 行号按视觉锚位（displayCur）算，与滚动态画面对齐。
                    int idx = Math.round(displayCur + (event.getY() - anchorY - mainPaint.getTextSize() / 2f) / slot);
                    if (idx >= 0 && idx < lines.size()) {
                        lineClickListener.onLineClick(idx, lines.get(idx).getTimeMs());
                        performClick();
                    }
                }
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
