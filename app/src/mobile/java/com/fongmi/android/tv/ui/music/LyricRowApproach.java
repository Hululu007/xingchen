package com.fongmi.android.tv.ui.music;

import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * 逐行数值趋近（GZ animateFloatAsState 语义落码，2026-10-25 深挖抠实）：
 * 每行一个通道；目标变化时以「当前显示值」为起点、按新给定时长全程重新 tween
 * （不续接旧动画、无速度继承），easing＝CubicBezier(0.2,0,0,1)、delay 0。
 * 首次见到的行直接落到目标值、不播动画。调用方在绘制时以 approach() 返回值作显示值；
 * hasRunning() 供帧循环判断是否续帧（会先把已到点的通道结算掉）。
 */
final class LyricRowApproach {

    private static final Interpolator EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private float[] current = new float[0];
    private float[] from = new float[0];
    private float[] target = new float[0];
    private long[] startMs = new long[0];
    private int[] durMs = new int[0];
    private boolean[] running = new boolean[0];
    private boolean[] seen = new boolean[0];

    void clear() {
        current = new float[0];
        from = new float[0];
        target = new float[0];
        startMs = new long[0];
        durMs = new int[0];
        running = new boolean[0];
        seen = new boolean[0];
    }

    private void ensure(int row) {
        if (row < current.length) return;
        int n = Math.max(row + 1, Math.max(16, current.length * 2));
        current = java.util.Arrays.copyOf(current, n);
        from = java.util.Arrays.copyOf(from, n);
        target = java.util.Arrays.copyOf(target, n);
        startMs = java.util.Arrays.copyOf(startMs, n);
        durMs = java.util.Arrays.copyOf(durMs, n);
        running = java.util.Arrays.copyOf(running, n);
        seen = java.util.Arrays.copyOf(seen, n);
    }

    /** 取本行当前显示值；目标与上次不同则以当前显示值起、按 dur 毫秒整段重跑。 */
    float approach(int row, float newTarget, int dur, long now) {
        if (row < 0) return newTarget;
        ensure(row);
        if (!seen[row]) {
            seen[row] = true;
            current[row] = from[row] = target[row] = newTarget;
            running[row] = false;
            return newTarget;
        }
        if (newTarget != target[row]) {
            from[row] = valueAt(row, now);
            target[row] = newTarget;
            durMs[row] = Math.max(1, dur);
            startMs[row] = now;
            running[row] = true;
        }
        return valueAt(row, now);
    }

    private float valueAt(int row, long now) {
        if (!running[row]) return current[row];
        float p = (now - startMs[row]) / (float) durMs[row];
        if (p >= 1f) {
            running[row] = false;
            current[row] = target[row];
            return current[row];
        }
        if (p <= 0f) return from[row];
        return from[row] + (target[row] - from[row]) * EASE.getInterpolation(p);
    }

    /** 是否仍有通道在趋近（先结算已到点通道）。 */
    boolean hasRunning(long now) {
        boolean any = false;
        for (int i = 0; i < running.length; i++) {
            if (running[i]) {
                valueAt(i, now);
                if (running[i]) any = true;
            }
        }
        return any;
    }
}
