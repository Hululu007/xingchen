package com.fongmi.android.tv.ui.music.view;

import android.view.Choreographer;

/**
 * 真弹簧物理动画（与瓜子 Compose spring 同模型）：二阶阻尼振子，半隐式欧拉积分（240Hz 子步），
 * 参数就是源码的阻尼比／刚度——播停偏转 spring(0.55, 600)、封面竖滑回中 spring(0.9, 1400)、
 * 收藏缩放回弹 spring(0.9, 300)。不是曲线近似：起速、过冲、回摆都由积分本身产生。
 */
public class SpringAnimator {

    public interface Listener {
        void onUpdate(float value);

        void onEnd();
    }

    private final double mDampingRatio;
    private final double mStiffness;
    private double mValue;
    private double mVelocity;
    private double mTarget;
    private double mSettleEps = 0.0005;
    private Listener mListener;
    private boolean mRunning;
    private long mLastNs;

    private final Choreographer.FrameCallback mFrame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mRunning) return;
            double dt = mLastNs == 0 ? 1.0 / 60.0 : (frameTimeNanos - mLastNs) / 1e9;
            mLastNs = frameTimeNanos;
            dt = Math.max(0.001, Math.min(0.05, dt));
            int steps = Math.max(1, (int) Math.ceil(dt / (1.0 / 240.0)));
            double h = dt / steps;
            double c = 2 * mDampingRatio * Math.sqrt(mStiffness);
            for (int i = 0; i < steps; i++) {
                double acc = -mStiffness * (mValue - mTarget) - c * mVelocity;
                mVelocity += acc * h;
                mValue += mVelocity * h;
            }
            if (mListener != null) mListener.onUpdate((float) mValue);
            if (Math.abs(mValue - mTarget) < mSettleEps && Math.abs(mVelocity) < mSettleEps * 60) {
                mValue = mTarget;
                mVelocity = 0;
                mRunning = false;
                if (mListener != null) {
                    mListener.onUpdate((float) mValue);
                    mListener.onEnd();
                }
                return;
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public SpringAnimator(double dampingRatio, double stiffness) {
        mDampingRatio = dampingRatio;
        mStiffness = stiffness;
    }

    public void start(double from, double to, Listener listener) {
        cancel();
        mValue = from;
        mVelocity = 0;
        mTarget = to;
        mSettleEps = 0.0005 * Math.max(1.0, Math.abs(to - from));
        mListener = listener;
        mRunning = true;
        mLastNs = 0;
        Choreographer.getInstance().postFrameCallback(mFrame);
    }

    public void cancel() {
        if (mRunning) {
            mRunning = false;
            Choreographer.getInstance().removeFrameCallback(mFrame);
        }
        mListener = null;
    }

    public boolean isRunning() {
        return mRunning;
    }
}
