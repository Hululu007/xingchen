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
 * 播放页封面（经典）：底部渐隐融进氛围层——瓜子的封面与背景是一个栈：封面在底层
 * （zIndex −2），氛围的糊封面＋明度渐变罩（停点 0/0.4/0.78/1、明度 +0.05/−0.04、
 * alpha 基数 0.8，CoverAmbientKt 亲读）盖在上面，封面下段就是这样被溶掉的。
 * 星辰栈是氛围在下、封面盒在上，等价做法：封面自带软件层，画完用 DST_OUT 线性
 * 渐变把下段擦透——0.78（罩的第三停点、罩在此到达基值）起擦、到底全透，露出
 * 下方同图的糊化氛围，完成同一件溶合。交叉淡化的一对封面（进/出）同口径。
 */
public class FadingCoverView extends AppCompatImageView {

    private static final float FADE_START = 0.78f;

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
