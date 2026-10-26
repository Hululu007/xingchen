package com.fongmi.android.tv.ui.music.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * 播放页封面（经典）：底部渐隐融进氛围层——瓜子帧里封面下沿不是一条硬边，
 * 是从约三分之二高处开始溶进同图的糊化氛围、到底全透。实现：自带软件层，
 * 画完图后用 DST_OUT 线性渐变把下段逐步擦透（上 0.68 全实、到底全透）。
 * 交叉淡化的一对封面（进/出）都用本类，溶边口径一致。
 */
public class FadingCoverView extends AppCompatImageView {

    private static final float FADE_START = 0.68f;

    private final Paint fadePaint = new Paint();

    public FadingCoverView(Context context) {
        super(context);
        init();
    }

    public FadingCoverView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            fadePaint.setShader(new LinearGradient(0, h * FADE_START, 0, h,
                    0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getHeight() > 0) canvas.drawRect(0, getHeight() * FADE_START, getWidth(), getHeight(), fadePaint);
    }
}
