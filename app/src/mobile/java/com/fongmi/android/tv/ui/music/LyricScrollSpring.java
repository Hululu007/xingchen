package com.fongmi.android.tv.ui.music;

/**
 * 歌词滚动弹簧（瓜子 AnchoredLyricEngine 的 Animatable＋SpringSpec 等价实现，
 * 2026-10-25 smali 深挖 §15.1 第 1/6 项）：半隐式欧拉积分，参数＝(刚度 k, 阻尼比 ζ)，
 * 阻尼系数 c＝2ζ√k。三档选档在调用方：
 * settle（全屏跟随/回锚）ζ1.16、k＝amllPosYComposeStiffness(450)＝237.25；
 * seek（seek 跳变）ζ0.833、k＝100；
 * cover（封面面行进）ζ1.08、k＝amllPosYComposeStiffness(行时长差)×1.1。
 * 大跳变 snap 判据（|值−目标|＞视高×1.5 直接落位）在调用方按引擎原式执行。
 */
final class LyricScrollSpring {

    float value;
    float velocity;
    /** 收敛判据（默认 px 单位；以「行」为单位的消费方（唱片架）改小，见 ShelfLyricView）。 */
    float settleDistance = 0.5f;
    float settleVelocity = 10f;

    void snapTo(float target) {
        value = target;
        velocity = 0f;
    }

    /** 是否仍在动（距目标超收敛距或速度未衰减到收敛速度以下）。 */
    boolean active(float target) {
        return Math.abs(target - value) > settleDistance || Math.abs(velocity) > settleVelocity;
    }

    /**
     * 推进一步（dt 秒，内部按 ≤8ms 子步积分保稳定）。
     * 返回 true 表示仍在动画；收敛时自动 snap 到目标并清零速度。
     */
    boolean step(float dtSec, float target, float stiffness, float dampingRatio) {
        if (dtSec <= 0f) return active(target);
        float diff = target - value;
        if (Math.abs(diff) <= settleDistance && Math.abs(velocity) <= settleVelocity) {
            snapTo(target);
            return false;
        }
        int sub = Math.max(1, (int) Math.ceil(dtSec / 0.008f));
        float h = dtSec / sub;
        double damping = 2.0 * dampingRatio * Math.sqrt(stiffness);
        for (int i = 0; i < sub; i++) {
            double accel = stiffness * (target - value) - damping * velocity;
            velocity += (float) (accel * h);
            value += velocity * h;
        }
        if (Math.abs(target - value) <= settleDistance && Math.abs(velocity) <= settleVelocity) {
            snapTo(target);
            return false;
        }
        return true;
    }
}
