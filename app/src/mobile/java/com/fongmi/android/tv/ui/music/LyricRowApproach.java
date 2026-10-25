package com.fongmi.android.tv.ui.music;

import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import java.util.HashMap;
import java.util.Map;

/**
 * 逐行数值趋近（包私有）：Compose animateFloatAsState 语义（gz-full-parity-1025 §15.1 第 6 项）。
 *
 * 目标一变，以当前显示值为起点全程重 tween；缓动 CubicBezier(0.2, 0, 0, 1)。
 * 首次见到的行直落目标（无动画）。模糊动画时长：非当前且非组态 400ms、否则 200ms。
 */
final class LyricRowApproach {

    static final long DURATION_FAR_MS = 400L;
    static final long DURATION_NEAR_MS = 200L;

    private static final Interpolator BEZIER = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private static final class RowState {
        float displayed;
        float from;
        float target;
        long startMs;
        long durationMs;
    }

    private final Map<Integer, RowState> rows = new HashMap<>();

    /**
     * 取某行某量的当前显示值。
     *
     * @param row            行号（可与量的种类拼键，调用方保证同一键同一种量）
     * @param target         目标值
     * @param currentOrGroup 当前行或其组态（决定 200ms / 400ms）
     * @param nowMs          当前时间（SystemClock.uptimeMillis 口径）
     */
    float value(int row, float target, boolean currentOrGroup, long nowMs) {
        RowState state = rows.get(row);
        if (state == null) {
            state = new RowState();
            state.displayed = target;
            state.from = target;
            state.target = target;
            state.startMs = nowMs;
            state.durationMs = 0L;
            rows.put(row, state);
            return target;
        }
        if (state.target != target) {
            // 目标变：先把显示值结算到此刻，再以它为起点重开 tween。
            state.displayed = sample(state, nowMs);
            state.from = state.displayed;
            state.target = target;
            state.startMs = nowMs;
            state.durationMs = currentOrGroup ? DURATION_NEAR_MS : DURATION_FAR_MS;
        }
        state.displayed = sample(state, nowMs);
        return state.displayed;
    }

    private float sample(RowState state, long nowMs) {
        if (state.durationMs <= 0L) return state.target;
        long elapsed = nowMs - state.startMs;
        if (elapsed <= 0L) return state.from;
        if (elapsed >= state.durationMs) return state.target;
        float fraction = BEZIER.getInterpolation(elapsed / (float) state.durationMs);
        return state.from + (state.target - state.from) * fraction;
    }

    /** 是否还有未走完的 tween（视图据此决定是否继续刷新）。 */
    boolean animating(long nowMs) {
        for (RowState state : rows.values()) {
            if (state.durationMs > 0L && nowMs - state.startMs < state.durationMs
                    && state.from != state.target) return true;
        }
        return false;
    }

    void reset() {
        rows.clear();
    }
}
