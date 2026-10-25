package com.fongmi.android.tv.ui.music;

/**
 * 歌词滚动弹簧（包私有）：半隐式欧拉积分，Compose SpringSpec 同构参数 (k, ζ)。
 *
 * 三档（gz-full-parity-1025 §15.1 第 1/6 项）：
 * settle：ζ 1.16 / k 237.25（＝amllPosYComposeStiffness(450)）；
 * seek：ζ 0.833 / k 100；
 * cover：ζ 1.08 / k＝amllPosYComposeStiffness(行时长差)×1.1。
 * snap 判据（|值−目标|＞视高×1.5）由调用方判定后调 {@link #snapTo(float)}，本类不管。
 */
final class LyricScrollSpring {

    static final float SETTLE_DAMPING = 1.16f;
    static final float SETTLE_STIFFNESS = 237.25f;
    static final float SEEK_DAMPING = 0.833f;
    static final float SEEK_STIFFNESS = 100f;
    static final float COVER_DAMPING = 1.08f;

    private float stiffness;
    private float dampingRatio;
    private float value;
    private float velocity;
    private float target;
    private boolean active;

    private LyricScrollSpring(float stiffness, float dampingRatio) {
        this.stiffness = stiffness;
        this.dampingRatio = dampingRatio;
    }

    static LyricScrollSpring settle() {
        return new LyricScrollSpring(SETTLE_STIFFNESS, SETTLE_DAMPING);
    }

    static LyricScrollSpring seek() {
        return new LyricScrollSpring(SEEK_STIFFNESS, SEEK_DAMPING);
    }

    static LyricScrollSpring cover(long lineDeltaMs) {
        float k = LyricWordEngine.amllPosYComposeStiffness(lineDeltaMs) * 1.1f;
        return new LyricScrollSpring(k, COVER_DAMPING);
    }

    /** 换档：保留当前值与速度，只换 (k, ζ) 与目标。 */
    void retarget(float stiffness, float dampingRatio, float newTarget) {
        this.stiffness = stiffness;
        this.dampingRatio = dampingRatio;
        this.target = newTarget;
        this.active = value != newTarget || velocity != 0f;
    }

    void setTarget(float newTarget) {
        this.target = newTarget;
        if (value != newTarget) this.active = true;
    }

    float target() {
        return target;
    }

    float value() {
        return value;
    }

    boolean active() {
        return active;
    }

    /** 直落：值与目标同置、速度清零、停活。 */
    void snapTo(float v) {
        value = v;
        target = v;
        velocity = 0f;
        active = false;
    }

    /**
     * 推进一帧（半隐式欧拉：先算加速度更新速度，再用新速度更新位置）。
     * dt 由调用方钳到 (0, 0.05] 秒。返回是否仍在运动。
     */
    boolean step(float dtSeconds) {
        if (!active) return false;
        if (dtSeconds <= 0f) return true;
        float damping = 2f * dampingRatio * (float) Math.sqrt(stiffness);
        float acceleration = -stiffness * (value - target) - damping * velocity;
        velocity += acceleration * dtSeconds;
        value += velocity * dtSeconds;
        if (Math.abs(value - target) < 0.5f && Math.abs(velocity) < 8f) {
            value = target;
            velocity = 0f;
            active = false;
        }
        return active;
    }
}
