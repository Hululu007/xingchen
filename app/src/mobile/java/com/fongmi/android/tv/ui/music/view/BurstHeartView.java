package com.fongmi.android.tv.ui.music.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 双击封面收藏的爱心爆发（瓜子经典 BurstHeart）：总进度 tween 1200ms 线性；
 * 粒子数＝双击变成已收藏 8 颗／取消收藏 5 颗，随机以触发计数为种子：
 * angle −60~+60°、travel 42–90dp、size 9–19dp、rotation −25~+25°、
 * delay＝随机×0.08＋序×0.035；位移 easeOutQuad、缩放 Overshoot(0.34,1.56,0.64,1)、
 * 进度 >0.55 后线性淡出。只在爆发时跑帧，平时不画、不耗电。
 */
public class BurstHeartView extends View {

    private static class Particle {
        float angleDeg, travelDp, sizeDp, rotationDeg, delay;
    }

    private final float mDensity;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mHeart = new Path();
    private final List<Particle> mParticles = new ArrayList<>();
    private long mStartMs;
    private boolean mRunning;
    private float mCx, mCy;

    public BurstHeartView(Context context) {
        this(context, null);
    }

    public BurstHeartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
        mPaint.setColor(0xFFFFFFFF);
        mPaint.setStyle(Paint.Style.FILL);
        buildHeartPath();
        setWillNotDraw(true);
    }

    private void buildHeartPath() {
        // 单位爱心（宽 1、高 1，原点在中心）
        mHeart.moveTo(0f, 0.32f);
        mHeart.cubicTo(-0.55f, -0.08f, -0.34f, -0.5f, 0f, -0.24f);
        mHeart.cubicTo(0.34f, -0.5f, 0.55f, -0.08f, 0f, 0.32f);
        mHeart.close();
    }

    /** 在 (cx, cy) 处爆发一轮（双击点）；toFav＝这次双击变成已收藏（8 颗）还是取消（5 颗），seed＝触发计数。 */
    public void burst(float cx, float cy, boolean toFav, int seed) {
        mCx = cx;
        mCy = cy;
        mParticles.clear();
        Random rnd = new Random(seed);
        int n = toFav ? 8 : 5;
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            p.angleDeg = -60f + rnd.nextFloat() * 120f;
            p.travelDp = 42f + rnd.nextFloat() * 48f;
            p.sizeDp = 9f + rnd.nextFloat() * 10f;
            p.rotationDeg = -25f + rnd.nextFloat() * 50f;
            p.delay = rnd.nextFloat() * 0.08f + i * 0.035f;
            mParticles.add(p);
        }
        mStartMs = System.currentTimeMillis();
        mRunning = true;
        setWillNotDraw(false);
        invalidate();
    }

    private static float easeOutQuad(float t) {
        return 1f - (1f - t) * (1f - t);
    }

    /** 三次贝塞尔 (0.34, 1.56, 0.64, 1) 的 y 值（瓜子粒子缩放曲线本体，非近似族）。 */
    private static float overshoot(float x) {
        if (x <= 0f) return 0f;
        if (x >= 1f) return 1f;
        double x1 = 0.34, y1 = 1.56, x2 = 0.64, y2 = 1.0;
        double lo = 0, hi = 1, t = x;
        for (int i = 0; i < 24; i++) {
            double u = 1 - t;
            double cx = 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t;
            if (Math.abs(cx - x) < 1e-5) break;
            if (cx < x) lo = t;
            else hi = t;
            t = (lo + hi) / 2;
        }
        double u = 1 - t;
        return (float) (3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mRunning) return;
        float t = (System.currentTimeMillis() - mStartMs) / 1200f;
        if (t >= 1f) {
            mRunning = false;
            setWillNotDraw(true);
            return;
        }
        for (Particle p : mParticles) {
            float lt = (t - p.delay) / Math.max(0.01f, 1f - p.delay);
            if (lt <= 0f || lt > 1f) continue;
            float dist = easeOutQuad(lt) * p.travelDp * mDensity;
            double rad = Math.toRadians(p.angleDeg - 90f); // 0°＝正上
            float x = mCx + (float) Math.cos(rad) * dist;
            float y = mCy + (float) Math.sin(rad) * dist;
            float scale = overshoot(lt) * p.sizeDp * mDensity;
            float alpha = lt > 0.55f ? 1f - (lt - 0.55f) / 0.45f : 1f;
            mPaint.setAlpha(Math.round(255 * Math.max(0f, alpha)));
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(p.rotationDeg * lt);
            canvas.scale(scale, scale);
            canvas.drawPath(mHeart, mPaint);
            canvas.restore();
        }
        postInvalidateOnAnimation();
    }
}
