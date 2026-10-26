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
 * 播放页封面（经典）：底部渐隐融进氛围层。依据（2026-10-27 复核，证据分级）：
 * 瓜子成品帧像素实测（定案图 113250＋他 GZ 录屏帧《每段路》）——封面纹理保持完整
 * 到约 0.78–0.80 封面高、其后渐溶、到封面底缘全透、无一步刀切；0.78 这个起点与
 * 瓜子 CoverAmbientKt 渐变罩的第三停点同值（停点 0/0.4/0.78/1，源码亲读）。
 * 瓜子内部到底哪一层画这道溶边，混淆后的 jadx 钉不死（CoverArt 内 zIndex −2 已
 * 验明是封面内部兜底层 CoverStaticFallbackLayer、与页面栈无关；根栈序是氛围先
 * 发射在下、内容列在上）——aa54260d 曾据「源码里没有渐隐件」整段删掉本溶带，
 * 但成品帧证明瓜子封面下段确实被溶，删后 1.4.46/1.4.47 两版被他定罪「封面像
 * 贴上去的一张图、硬切一刀」。星辰栈是氛围在下、封面盒在上，等价做法不变：
 * 封面自带软件层，画完用 DST_OUT 线性渐变把下段擦透——0.78 起擦、到底全透，
 * 露出下方同图的糊化氛围（氛围糊图已按瓜子 Crop 口径铺满，636a0040）。交叉
 * 淡化的一对封面（进/出）同口径。
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
