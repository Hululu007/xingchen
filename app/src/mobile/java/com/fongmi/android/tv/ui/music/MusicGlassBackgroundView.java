package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 音乐全域统一背景（批 5 F1，2026-10-24 皮与背景总口径的源头实现）：
 * 凡套液态玻璃的音乐页（首页／搜索／列表族／插件／设置等非播放页），背景一律用这一份，
 * 各页不许再自编背景。后批复用方式：在页面根布局最底层放一个本 View（match_parent），
 * 内容层背景透明即可；播放页／全屏歌词页／五套播放页主题是例外，不用本 View。
 *
 * 构成＝近白底 #FAFAFC ＋ 粉／紫／薄荷极光柔光（第十稿定稿：近白底＋粉紫薄荷极光柔光）。
 * 定稿原件（music-home-final-draft.html／v10 出图稿）在本机 theme-redo 目录缺失，
 * 色值与光斑位置按第十稿定稿图（music-home-drafts 的 bold-v10 webp）目测取最接近值，
 * 并与树内既有 AuroraBackgroundView（瓜子 Aurora 实证色板：蜜桃／淡紫／薄荷）对齐微调；
 * 目测值清单见 batch5-f1 报告，后续若拿到定稿原件逐值复核只改本文件一处即全域生效。
 * 静态绘制、不做漂移动画（流畅优先；与 AuroraBackgroundView 同口径）。
 */
public class MusicGlassBackgroundView extends View {

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

    // 大光斑：中心公式与 AuroraBackgroundView 一致（cx=(W-r)*((a+1)/2)+r/2，半径=r/2）；
    // 色相换第十稿的粉／紫／薄荷：左上粉、右上紫、左下薄荷、右下粉。
    private static final Blob[] LARGE = {
            new Blob(0xFFFFD9E8, -0.85f, -0.55f, 360f, 0.50f, 0.16f),
            new Blob(0xFFE0D9FF, 0.85f, -0.35f, 340f, 0.50f, 0.16f),
            new Blob(0xFFD4F8F0, -0.55f, 0.75f, 320f, 0.50f, 0.16f),
            new Blob(0xFFFFD9E8, 0.60f, 0.85f, 300f, 0.45f, 0.14f),
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

    // 小暖光：与 Aurora 小光斑同位，色保持蜜桃／金／粉（第十稿柔光里的暖点）。
    private static final Dot[] SMALL = {
            new Dot(0xFFFF9A76, 110f, 70f, 190f, 0.094f, 0.031f, false),
            new Dot(0xFFFFD175, 230f, 50f, 150f, 0.078f, 0.024f, false),
            new Dot(0xFFF8C0D0, -140f, 40f, 120f, 0.063f, 0.016f, true),
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public MusicGlassBackgroundView(Context context) {
        super(context);
    }

    public MusicGlassBackgroundView(Context context, AttributeSet attrs) {
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
