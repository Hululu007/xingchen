package com.fongmi.android.tv.ui.custom;

import android.os.SystemClock;

import androidx.recyclerview.widget.RecyclerView;

/**
 * 唱片架槽位弹簧（B1）：架子层由 RecyclerView 滚动模型改为槽位模型。
 * 槽位坐标 slotPos＝scrollX／slotWidth；切歌目标＝让目标项居中的 scrollX；
 * 弹簧＝半隐式欧拉积分，刚度 300、阻尼比 0.9（与瓜子 spring(0.9, 300) 同值）；
 * snap 规则用瓜子值：|slotPos−目标槽位| ≤ 0.001 时直接 snap 到目标、回调 settled。
 * RecyclerView 只作视图复用，定位由槽位驱动；smoothScroll 不再用于切歌。
 */
public class ShelfSlotAnimator {

    /** 瓜子 spring 阻尼比 */
    private static final float DAMPING_RATIO = 0.9f;
    /** 瓜子 spring 刚度 */
    private static final float STIFFNESS = 300f;
    /** 瓜子槽位 snap 阈（槽位单位） */
    private static final float SNAP_SLOT = 0.001f;
    /** 安全上限：弹簧 3 秒未稳直接落位收口，不许无限振荡 */
    private static final long WATCHDOG_MS = 3000;

    private final RecyclerView rv;
    private final int slotWidth;
    private float target;
    private float x;
    private float v;
    private long lastMs = -1;
    private long startMs;
    private boolean running;
    private Runnable onSettled;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            float dt = lastMs < 0 ? 0.016f : Math.min(0.032f, (now - lastMs) / 1000f);
            lastMs = now;
            float c = 2f * DAMPING_RATIO * (float) Math.sqrt(STIFFNESS);
            float a = -STIFFNESS * (x - target) - c * v;
            v += a * dt;
            x += v * dt;
            int maxScroll = maxScroll();
            if (x < 0) {
                x = 0;
                v = 0;
            } else if (x > maxScroll) {
                x = maxScroll;
                v = 0;
            }
            if (Math.abs((x - target) / slotWidth) <= SNAP_SLOT || now - startMs > WATCHDOG_MS) {
                x = target;
                settle();
                return;
            }
            int dx = Math.round(x - rv.computeHorizontalScrollOffset());
            if (dx != 0) rv.scrollBy(dx, 0);
            rv.postOnAnimation(this);
        }
    };

    public ShelfSlotAnimator(RecyclerView rv, int slotWidth) {
        this.rv = rv;
        this.slotWidth = slotWidth;
    }

    /**
     * 把第 idx 项以弹簧推到居中。返回 false＝起不来（槽宽／视口宽无效），调用方回落旧滚动。
     * settled 在弹簧落位时回调一次（调用方接 tryShelfPageEnter）。
     */
    public boolean animateToCenter(int idx, Runnable onSettled) {
        if (slotWidth <= 0 || rv.getWidth() <= 0) return false;
        float raw = idx * (float) slotWidth - (rv.getWidth() - slotWidth) / 2f;
        target = Math.max(0, Math.min(raw, maxScroll()));
        x = rv.computeHorizontalScrollOffset();
        v = 0;
        this.onSettled = onSettled;
        lastMs = -1;
        startMs = SystemClock.uptimeMillis();
        running = true;
        rv.postOnAnimation(frame);
        return true;
    }

    public void cancel() {
        running = false;
        onSettled = null;
        rv.removeCallbacks(frame);
    }

    private int maxScroll() {
        return Math.max(0, rv.computeHorizontalScrollRange() - rv.getWidth());
    }

    private void settle() {
        running = false;
        int dx = Math.round(target - rv.computeHorizontalScrollOffset());
        if (dx != 0) rv.scrollBy(dx, 0);
        Runnable cb = onSettled;
        onSettled = null;
        if (cb != null) cb.run();
    }
}
