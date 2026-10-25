package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * 封面栈容器（经典／Apple 的 tpCoverWrap）：下沿 84dp 内把封面内容本体的 alpha
 * 线性渐隐到全透，透出的就是整页糊底本身——封面与背景构造上一体、无接缝阶跃。
 * <p>
 * 取代旧「熔边层」（在封面上盖一层氛围实色渐变）：盖色收尾是实色、落点背景却是
 * 带糊图鬼影的罩后糊底，熔边条下沿必然出一条横向阶跃线，本身就是接缝源；
 * 瓜子 CoverArt 无此构造（全量核对无渐变／无盖层），其录屏里封面下沿是本体
 * 渐隐进氛围。渐隐高度 84dp 沿用旧熔边条高；渐变曲线瓜子未抠到，此处用线性。
 * <p>
 * 实现：dispatchDraw 把子视图画到离屏层，再用 DST_IN 线性渐变只擦除下沿条带；
 * 条带以上原样保留。容器自身的触摸／布局行为与 FrameLayout 一致。
 */
public class CoverFadeLayout extends FrameLayout {

    /** 渐隐带高度（沿用旧熔边条的 84dp）。 */
    private static final float FADE_DP = 84f;

    private final Paint fadePaint = new Paint();
    private LinearGradient fadeGradient;
    private int gradientHeight = -1;

    public CoverFadeLayout(Context context) {
        super(context);
    }

    public CoverFadeLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CoverFadeLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            super.dispatchDraw(canvas);
            return;
        }
        float fade = Math.min(FADE_DP * getResources().getDisplayMetrics().density, h);
        if (fadeGradient == null || gradientHeight != h) {
            fadeGradient = new LinearGradient(0f, h - fade, 0f, h,
                    new int[]{0xFFFFFFFF, 0x00000000}, new float[]{0f, 1f},
                    Shader.TileMode.CLAMP);
            fadePaint.setShader(fadeGradient);
            fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            gradientHeight = h;
        }
        int save = canvas.saveLayer(0f, 0f, w, h, null);
        super.dispatchDraw(canvas);
        canvas.drawRect(0f, h - fade, w, h, fadePaint);
        canvas.restoreToCount(save);
    }
}
