package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 瓜子 Aurora 背景（浅色，静态，不做漂移动画）。
 * 实证来源：瓜子 2.1.8 反编译 com.gzmusic.app.ui.AuroraBackgroundKt＋defpackage.p70 case 0。
 * 不是线性渐变、不是纯白、不是单色底：底色 #FFFAFAFC（250,250,252）上叠 7 颗径向柔光：
 * 4 颗大光斑（起 0.45→0.55 处 0.15→边缘 0）：蜜桃 #FFFFE8D8（-0.85,-0.55,360）、
 * 淡紫 #FFE0D9FF（0.85,-0.35,340）、薄荷 #FFD4F8F0（-0.55,0.75,320）、蜜桃 #FFFFE8D8（0.6,0.85,300）；
 * 3 颗小暖光（起 0.094/0.078/0.063）：#FFFF9A76、#FFFFD175、#FFF8C0D0。
 * 大光斑中心公式与瓜子一致：cx=(W-r)*((a+1)/2)+r/2，cy=(H-r)*((b+1)/2)+r/2，渐变半径=r/2，
 * 其中 r=光斑直径×(屏宽dp/390)；小光斑中心与半径直接按屏宽比例取。
 * 星辰此前 home_bg_page 注释称“照 GZ 纯白”是错的：瓜子浅色底本身就是 #FAFAFC＋光斑，不是 #FFFFFF。
 * 本视图只用于歌单页（sheets 模式），其它模式保持 GONE，不改共用底。
 */
public class AuroraBackgroundView extends View {

    private static final int BASE = 0xFFFAFAFC;

    private static final class Blob {
        final int color;
        final float a, b, diameter, alpha0, alpha1;

        Blob(int color, float a, float b, float diameter, float alpha0, float alpha1) {
            this.color = color;
            this.a = a;
            this.b = b;
            this.diameter = diameter;
            this.alpha0 = alpha0;
            this.alpha1 = alpha1;
        }
    }

    private static final Blob[] LARGE = {
            new Blob(0xFFFFE8D8, -0.85f, -0.55f, 360f, 0.45f, 0.15f),
            new Blob(0xFFE0D9FF, 0.85f, -0.35f, 340f, 0.45f, 0.15f),
            new Blob(0xFFD4F8F0, -0.55f, 0.75f, 320f, 0.45f, 0.15f),
            new Blob(0xFFFFE8D8, 0.60f, 0.85f, 300f, 0.45f, 0.15f),
    };

    private static final class Dot {
        final int color;
        final float x, y, radius, alpha0, alpha1;
        final boolean right;

        Dot(int color, float x, float y, float radius, float alpha0, float alpha1, boolean right) {
            this.color = color;
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.alpha0 = alpha0;
            this.alpha1 = alpha1;
            this.right = right;
        }
    }

    private static final Dot[] SMALL = {
            new Dot(0xFFFF9A76, 110f, 70f, 190f, 0.094f, 0.031f, false),
            new Dot(0xFFFFD175, 230f, 50f, 150f, 0.078f, 0.024f, false),
            new Dot(0xFFF8C0D0, -140f, 40f, 120f, 0.063f, 0.016f, true),
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public AuroraBackgroundView(Context context) {
        super(context);
    }

    public AuroraBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(alpha * 255f);
        if (a < 0) a = 0;
        if (a > 255) a = 255;
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private void drawBlob(Canvas canvas, float cx, float cy, float radius, int color, float alpha0, float alpha1) {
        if (radius <= 0f) return;
        RadialGradient g = new RadialGradient(cx, cy, radius,
                new int[]{withAlpha(color, alpha0), withAlpha(color, alpha1), withAlpha(color, 0f)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        paint.setShader(g);
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
        paint.setShader(null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;
        canvas.drawColor(BASE);
        float unit = w / 390f;
        for (Blob blob : LARGE) {
            float d = blob.diameter * unit;
            float cx = (w - d) * ((blob.a + 1f) / 2f) + d / 2f;
            float cy = (h - d) * ((blob.b + 1f) / 2f) + d / 2f;
            drawBlob(canvas, cx, cy, d / 2f, blob.color, blob.alpha0, blob.alpha1);
        }
        for (Dot dot : SMALL) {
            float cx = dot.right ? w + dot.x * unit : dot.x * unit;
            float cy = dot.y * unit;
            drawBlob(canvas, cx, cy, dot.radius * unit, dot.color, dot.alpha0, dot.alpha1);
        }
    }
}
