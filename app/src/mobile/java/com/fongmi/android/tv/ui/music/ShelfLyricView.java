package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * 黑胶唱片架歌词面。
 *
 * 口径（gz-full-parity-1025 §6、§15.1 第 7 项）：与封面同一引擎（CoverCurrentLyric）——
 * 滚动用 cover 档弹簧（ζ1.08、k＝amllPosYComposeStiffness(行时长差)×1.1）、行单位收敛判据、顶锚。
 * 字号默认走封面键（宿主下发，缺省 22）、行高 ×4/3、行距 20dp（封面路同值）、字距 0。
 * 面圆角 上 0 / 下 24dp。调色由宿主给 (sung, inactive)：
 * 瓜子四值 (h0, h0@0.48, ShelfMuted@0.72, ShelfMuted) 映射为
 * sung＝入参 sung、当前未唱＝sung×0.48、非当前＝入参 inactive、副行＝入参 inactive（同色相）。
 * 回锚延时：封面路引擎面，取 3000ms（§15.1 第 1 项分面口径）。点行快进、竖滑滚词，无横向手势。
 */
public class ShelfLyricView extends LyricSurfaceBase {

    private static final float LINE_GAP_DP = 20f;
    private static final float BOTTOM_RADIUS_DP = 24f;
    private static final long REANCHOR_DELAY_MS = 3000L;

    public ShelfLyricView(Context context) {
        super(context);
        init();
    }

    public ShelfLyricView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        drawer.configure(22f * scaledDensity(), Paint.Align.LEFT.ordinal());
        drawer.setLineHeightRatio(LyricWordEngine.LINE_HEIGHT_RATIO);
        drawer.setImmersive(false);
        setBlurCoefficient(LyricWordEngine.BLUR_MEDIUM);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                float radius = BOTTOM_RADIUS_DP * density();
                // 只圆下两角：轮廓矩形向上多出一个半径，顶角圆心落在视图顶边上、顶边保持直角。
                outline.setRoundRect(0, (int) -radius, view.getWidth(), view.getHeight(), radius);
            }
        });
    }

    public void setFontSizeSp(float sp) {
        drawer.configure(sp * scaledDensity(), Paint.Align.LEFT.ordinal());
        markLayoutDirty();
    }

    public void setTextAlign(int align) {
        int paintAlign = align == 1 ? Paint.Align.CENTER.ordinal()
                : align == 2 ? Paint.Align.RIGHT.ordinal() : Paint.Align.LEFT.ordinal();
        drawer.configure(drawer.fontSizePx(), paintAlign);
        markLayoutDirty();
    }

    /** 宿主按 §6 算好给入：sung＝封面语气色混 ShelfInk 0.65 后的 h0；inactive＝ShelfMuted@0.72。 */
    public void setPaletteColors(int sung, int inactive) {
        int activeUnsung = Color.argb(Math.round(Color.alpha(sung) * 0.48f),
                Color.red(sung), Color.green(sung), Color.blue(sung));
        drawer.setPalette(LyricWordEngine.LyricPalette.of(sung, activeUnsung, inactive, inactive));
        invalidate();
    }

    public void setBlurLevel(int level) {
        setBlurCoefficient(LyricWordEngine.blurCoefficient(level));
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
        return 0;
    }
}
