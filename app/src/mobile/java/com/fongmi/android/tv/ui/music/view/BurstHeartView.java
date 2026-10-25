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
 * 同触发的 favRipple 涟漪（瓜子 CoverArt 绘制本体，smali 亲验）：进度 0→1、800ms／bs4.a，
 * 只在 0&lt;p&lt;1 时画，以双击点为心——实心圆 白×0.16(1−p)、半径 0.62×对角线×p；
 * 描边环 白×0.45(1−p)、同半径、线宽 4dp×(1−p)＋2dp；
 * 延迟内环 v＝clamp((p−0.22)/0.78)：白×0.3(1−v)、半径 0.45×对角线×v、线宽 2dp。
 */
public class BurstHeartView extends View {

    private static class Particle {
        float angleDeg, travelDp, sizeDp, rotationDeg, delay;
    }

    private final float mDensity;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mHeart = new Path();
    private final List<Particle> mParticles = new ArrayList<>();
    private long mStartMs;
    private long mRippleStartMs;
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
        mRippleStartMs = mStartMs;
        mRunning = true;
        setWillNotDraw(false);
        invalidate();
    }

    private static float easeOutQuad(float t) {
        return 1f - (1f - t) * (1f - t);
    }

    /** 三次贝塞尔 y 值（二分求 t）：粒子缩放 (0.34,1.56,0.64,1) 与涟漪缓动 bs4.a (0.2,0,0,1) 共用本体。 */
    private static float cubicBezier(float x, double x1, double y1, double x2, double y2) {
        if (x <= 0f) return 0f;
        if (x >= 1f) return 1f;
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

    private static float overshoot(float x) {
        return cubicBezier(x, 0.34, 1.56, 0.64, 1.0);
    }

    /** favRipple 涟漪（瓜子 CoverArt 绘制本体）：800ms／bs4.a，只在 0<p<1 时画。 */
    private void drawRipple(Canvas canvas, long nowMs) {
        float raw = (nowMs - mRippleStartMs) / 800f;
        if (raw < 0f || raw >= 1f) return;
        float p = cubicBezier(raw, 0.2, 0.0, 0.0, 1.0); // bs4.a
        if (p <= 0f || p >= 1f) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float diag = (float) Math.hypot(w, h);
        float f = 1f - p;
        float r = 0.62f * diag * p;
        // 实心圆：白 alpha 0.16×(1−p)
        mRingPaint.setStyle(Paint.Style.FILL);
        mRingPaint.setColor(0xFFFFFFFF);
        mRingPaint.setAlpha(Math.round(255 * 0.16f * f));
        canvas.drawCircle(mCx, mCy, r, mRingPaint);
        // 描边环：白 alpha 0.45×(1−p)，线宽 4dp×(1−p)＋2dp
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setAlpha(Math.round(255 * 0.45f * f));
        mRingPaint.setStrokeWidth((4f * f + 2f) * mDensity);
        canvas.drawCircle(mCx, mCy, r, mRingPaint);
        // 延迟内环：v 从 p=0.22 起，线宽 2dp
        float v = Math.max(0f, Math.min(1f, (p - 0.22f) / 0.78f));
        if (v > 0f) {
            mRingPaint.setAlpha(Math.round(255 * 0.3f * (1f - v)));
            mRingPaint.setStrokeWidth(2f * mDensity);
            canvas.drawCircle(mCx, mCy, 0.45f * diag * v, mRingPaint);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mRunning) return;
        long nowMs = System.currentTimeMillis();
        float t = (nowMs - mStartMs) / 1200f;
        float rippleRaw = (nowMs - mRippleStartMs) / 800f;
        if (t >= 1f && rippleRaw >= 1f) {
            mRunning = false;
            setWillNotDraw(true);
            return;
        }
        drawRipple(canvas, nowMs);
        if (t < 1f)
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
