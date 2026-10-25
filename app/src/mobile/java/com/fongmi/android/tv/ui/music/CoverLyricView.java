package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Paint;
import android.util.AttributeSet;

/**
 * 封面内嵌歌词窗口（经典主题内嵌 + 封面卡展开态共用）。
 *
 * 口径（gz-full-parity-1025 §2/§4/§7.4、§15.1 第 1/6 项）：
 * 窗口前 2 后 6、目标 4 行（槽高由宿主布局给）、行高＝字号×4/3、行距 20dp、横垫 28dp 由宿主布局给；
 * 字号默认 22（宿主下发）、字重默认 700、字距无（Unspecified＝0，§15.1 第 3 项定死）。
 * 滚动用 LyricScrollSpring cover 档（ζ1.08、k＝amllPosYComposeStiffness(行时长差)×1.1），
 * |差|＞视高×1.5 snap；回锚 3000ms（封面面口径）。
 * 本面不手动滚：竖滑由页级竖滑接管切歌（§1），本面只跟随锚定 + 点行快进；无横向手势。
 */
public class CoverLyricView extends LyricSurfaceBase {

    private static final int WINDOW_BEFORE = 2;
    private static final int WINDOW_AFTER = 6;
    private static final float LINE_GAP_DP = 20f;
    private static final long REANCHOR_DELAY_MS = 3000L;

    private boolean alignCenter = false;

    public CoverLyricView(Context context) {
        super(context);
        init();
    }

    public CoverLyricView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        drawer.configure(22f * scaledDensity(), Paint.Align.LEFT.ordinal());
        drawer.setLineHeightRatio(LyricWordEngine.LINE_HEIGHT_RATIO);
        drawer.setImmersive(false);
        setBlurCoefficient(LyricWordEngine.BLUR_MEDIUM);
    }

    public void setFontSizeSp(float sp) {
        drawer.configure(sp * scaledDensity(), alignCenter
                ? Paint.Align.CENTER.ordinal() : Paint.Align.LEFT.ordinal());
        markLayoutDirty();
    }

    /** 档位 0/1/2 → 系数 0 / 0.45 / 1.0。 */
    public void setBlurLevel(int level) {
        setBlurCoefficient(LyricWordEngine.blurCoefficient(level));
    }

    public void setAdvancedFx(boolean advancedFx) {
        drawer.setAdvancedFx(advancedFx);
        invalidate();
    }

    public void setPalette(LyricWordEngine.LyricPalette palette) {
        drawer.setPalette(palette);
        invalidate();
    }

    public void setAlignCenter(boolean center) {
        this.alignCenter = center;
        drawer.configure(drawer.fontSizePx(), center
                ? Paint.Align.CENTER.ordinal() : Paint.Align.LEFT.ordinal());
        markLayoutDirty();
    }

    /** 对齐口径：0 左 / 1 中 / 2 右（与 setAlignCenter 后写者为准）。 */
    public void setTextAlign(int align) {
        int paintAlign = align == 1 ? Paint.Align.CENTER.ordinal()
                : align == 2 ? Paint.Align.RIGHT.ordinal() : Paint.Align.LEFT.ordinal();
        this.alignCenter = align == 1;
        drawer.configure(drawer.fontSizePx(), paintAlign);
        markLayoutDirty();
    }

    @Override
    protected float gapPx() {
        return LINE_GAP_DP * density();
    }

    @Override
    protected SubMode subMode() {
        return SubMode.ALL_ROWS;
    }

    @Override
    protected SpringKind springKind() {
        return SpringKind.COVER;
    }

    @Override
    protected long reanchorDelayMs() {
        return REANCHOR_DELAY_MS;
    }

    @Override
    protected boolean allowManualScroll() {
        return false;
    }

    @Override
    protected int anchorIndexOffset() {
        return -WINDOW_BEFORE;
    }

    @Override
    protected boolean rowInWindow(int row, int current) {
        int anchor = Math.max(0, current);
        return row >= anchor - WINDOW_BEFORE && row <= anchor + WINDOW_AFTER;
    }
}
