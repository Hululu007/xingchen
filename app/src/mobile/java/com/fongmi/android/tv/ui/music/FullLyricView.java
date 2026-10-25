package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;

/**
 * 全屏歌词面（全屏歌词页正体，Apple pager 歌词页同面）。
 *
 * 口径（gz-full-parity-1025 §3/§7.3、§15.1 第 1/6 项）：
 * 字号默认 24、行高 ×1.3、字距 −0.2sp、行距 6dp、横垫 28dp 由宿主布局给、顶渐隐 40dp；
 * 正唱行顶部锚；副行只挂正唱行，展开走 anchorReveal：250ms CubicBezier(0.2,0,0,1)，
 * 副行高按展开系数计入行块（GZ target＝行高＋0.6×样本高的结构映射为副行高展开）。
 * 滚动 settle（ζ1.16/k237.25）/ seek（ζ0.833/k100）双档弹簧，|差|＞视高×1.5 snap；回锚 5000ms。
 * 点行 seek：UP 位移小于阈值才认（基类点按判据）。无横向手势。
 */
public class FullLyricView extends LyricSurfaceBase {

    private static final float LINE_GAP_DP = 6f;
    private static final float TOP_FADE_DP = 40f;
    private static final float LINE_HEIGHT_RATIO = 1.3f;
    private static final float LETTER_SPACING_SP = -0.2f;
    private static final long REANCHOR_DELAY_MS = 5000L;
    private static final long REVEAL_DURATION_MS = 250L;

    private final Paint fadePaint = new Paint();
    private int fadeColor = 0xFF000000;

    // anchorReveal 状态：当前行变化时副行高从 0 展开到全高。
    private int revealRow = -1;
    private long revealStartMs = 0L;
    private boolean revealDone = true;
    private static final android.view.animation.Interpolator REVEAL_BEZIER =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);

    public FullLyricView(Context context) {
        super(context);
        init();
    }

    public FullLyricView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        drawer.configure(24f * scaledDensity(), Paint.Align.LEFT.ordinal());
        drawer.setLineHeightRatio(LINE_HEIGHT_RATIO);
        drawer.setLetterSpacingPx(LETTER_SPACING_SP * scaledDensity());
        drawer.setImmersive(true);
        setBlurCoefficient(LyricWordEngine.BLUR_MEDIUM);
    }

    public void setFontSizeSp(float sp) {
        drawer.configure(sp * scaledDensity(), Paint.Align.LEFT.ordinal());
        drawer.setLetterSpacingPx(LETTER_SPACING_SP * scaledDensity());
        markLayoutDirty();
    }

    public void setBlurLevel(int level) {
        setBlurCoefficient(LyricWordEngine.blurCoefficient(level));
    }

    public void setTextAlign(int align) {
        int paintAlign = align == 1 ? Paint.Align.CENTER.ordinal()
                : align == 2 ? Paint.Align.RIGHT.ordinal() : Paint.Align.LEFT.ordinal();
        drawer.configure(drawer.fontSizePx(), paintAlign);
        markLayoutDirty();
    }

    public void setAdvancedFx(boolean advancedFx) {
        drawer.setAdvancedFx(advancedFx);
        invalidate();
    }

    public void setPalette(LyricWordEngine.LyricPalette palette) {
        drawer.setPalette(palette);
        invalidate();
    }

    /** 顶渐隐的收尾色（宿主给页面取色面色，渐隐从透明到此色上行收口——实际绘制为此色向透明渐变罩住顶部 40dp）。 */
    public void setFadeColor(int color) {
        this.fadeColor = color;
        invalidate();
    }

    @Override
    protected float gapPx() {
        return LINE_GAP_DP * density();
    }

    @Override
    protected SubMode subMode() {
        return SubMode.CURRENT_ONLY;
    }

    @Override
    protected SpringKind springKind() {
        return SpringKind.SETTLE_SEEK;
    }

    @Override
    protected long reanchorDelayMs() {
        return REANCHOR_DELAY_MS;
    }

    @Override
    protected boolean allowManualScroll() {
        return true;
    }

    @Override
    protected int anchorIndexOffset() {
        return 0;
    }

    @Override
    protected float subHeightScale(int row, long nowMs) {
        if (row != currentIndex()) return 1f;
        if (revealRow != row) {
            revealRow = row;
            revealStartMs = nowMs;
            revealDone = false;
            ensureLoop();
            return 0f;
        }
        if (revealDone) return 1f;
        long elapsed = nowMs - revealStartMs;
        if (elapsed <= 0L) return 0f;
        if (elapsed >= REVEAL_DURATION_MS) {
            revealDone = true;
            return 1f;
        }
        return REVEAL_BEZIER.getInterpolation(elapsed / (float) REVEAL_DURATION_MS);
    }

    @Override
    protected boolean revealAnimating(long nowMs) {
        if (revealDone) return false;
        // 展开中行块高逐帧变，需持续重排。
        markLayoutDirty();
        return nowMs - revealStartMs < REVEAL_DURATION_MS;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // 顶渐隐 40dp：从页面底色（不透明）向透明渐变，罩住顶部。
        float fadeHeight = TOP_FADE_DP * density();
        if (fadeHeight > 0f && getHeight() > 0) {
            int opaque = fadeColor | 0xFF000000;
            int transparent = fadeColor & 0x00FFFFFF;
            fadePaint.setShader(new LinearGradient(0f, 0f, 0f, fadeHeight,
                    opaque, transparent, Shader.TileMode.CLAMP));
            canvas.drawRect(0f, 0f, getWidth(), fadeHeight, fadePaint);
            fadePaint.setShader(null);
        }
    }
}
