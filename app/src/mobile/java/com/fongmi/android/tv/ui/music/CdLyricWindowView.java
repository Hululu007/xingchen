package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;

/**
 * 流光 CD 歌词展开窗。
 *
 * 口径（gz-full-parity-1025 §5、§13.2 02e4f5c4、§15.1 第 7 项）：
 * 字号 21sp、行高 ×4/3、行距 20dp、居中、加粗（字重档 0＝700）；
 * 调色 (sung＝ink, 当前未唱＝ink×0.42, 非当前＝ink×0.38, 副行＝ink×0.42)，左右边距 28dp 由宿主布局给。
 * CD 窗与封面同走 CoverCurrentLyric 引擎：当前行逐字分段（未唱段 ink×0.42），滚动用 cover 档弹簧。
 * 点行 seek 回调给宿主；点窗空白不吞（consumeTapMiss=false），收起由宿主在窗外层处理。无横向手势。
 * 回锚延时：本窗属封面路引擎，取封面面 3000ms（§15.1 第 1 项分面口径）。
 */
public class CdLyricWindowView extends LyricSurfaceBase {

    private static final float FONT_SIZE_SP = 21f;
    private static final float LINE_GAP_DP = 20f;
    private static final long REANCHOR_DELAY_MS = 3000L;

    private int inkColor = 0xFF282D36;

    public CdLyricWindowView(Context context) {
        super(context);
        init();
    }

    public CdLyricWindowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        drawer.configure(FONT_SIZE_SP * scaledDensity(), Paint.Align.CENTER.ordinal());
        drawer.setLineHeightRatio(LyricWordEngine.LINE_HEIGHT_RATIO);
        drawer.setLyricWeight(0);
        drawer.setImmersive(false);
        applyInkPalette();
        setBlurCoefficient(LyricWordEngine.BLUR_MEDIUM);
    }

    /** 墨色（CD palette ink）：四色按 (ink, ink×0.42, ink×0.38, ink×0.42) 派生，不叠乘。 */
    public void setInkColor(int ink) {
        this.inkColor = ink;
        applyInkPalette();
        invalidate();
    }

    private void applyInkPalette() {
        drawer.setPalette(LyricWordEngine.LyricPalette.of(
                inkColor,
                withAlphaScale(inkColor, 0.42f),
                withAlphaScale(inkColor, 0.38f),
                withAlphaScale(inkColor, 0.42f)));
    }

    private static int withAlphaScale(int color, float scale) {
        int alpha = Math.round(255f * scale);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
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
        return true;
    }

    @Override
    protected int anchorIndexOffset() {
        return -1;
    }

    @Override
    protected boolean consumeTapMiss() {
        return false;
    }
}
