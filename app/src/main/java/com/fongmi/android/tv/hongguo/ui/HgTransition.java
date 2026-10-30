package com.fongmi.android.tv.hongguo.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import com.fongmi.android.tv.R;

import java.util.WeakHashMap;

/**
 * 新短剧切换过渡的唯一出口（共享层，他 2026-10-30 亲令「切页面不能
 * 硬切」「要丝滑」）：页间、签间、页内换表全走这一处，不许各页自编。
 *
 * 口径：时长 180–200ms、缓入缓出；进场页动、出场页静止陪衬（任意
 * 时刻画面全覆盖，不透底不闪黑）；内容换表先淡出（90ms）→在不可见
 * 帧里换表/发请求→淡入承接，重活（整表重排、布局管理器更换）一律
 * 落在淡出后的不可见帧，不压在动画帧上；数据回包到达再整表淡入
 * 一次（contentIn），不许数据直接砸进可见表里。
 */
public final class HgTransition {

    /** 内容淡出时长：只求把换表藏住，宁短勿拖。 */
    public static final int CONTENT_OUT_MS = 90;
    /** 内容淡入时长。 */
    public static final int CONTENT_IN_MS = 170;
    /** 数据到达后整表淡入时长。 */
    public static final int ARRIVE_MS = 160;

    private static final AccelerateInterpolator ACCEL = new AccelerateInterpolator();
    private static final DecelerateInterpolator DECEL = new DecelerateInterpolator();

    private HgTransition() {
    }

    // ---------- Activity 页间 ----------

    /** 前进（详情/作者/搜索/我的/播放页等纵深页）：新页淡入＋轻微右滑入，下层静止。 */
    public static void push(Context context, Intent intent) {
        context.startActivity(intent);
        if (context instanceof Activity) {
            ((Activity) context).overridePendingTransition(R.anim.hg_push_in, R.anim.hg_hold);
        }
    }

    /** 签位互切与播放接管（首页↔剧场、竖屏→横屏）：纯淡入，下层静止。 */
    public static void fade(Context context, Intent intent) {
        context.startActivity(intent);
        if (context instanceof Activity) {
            ((Activity) context).overridePendingTransition(R.anim.hg_fade_in, R.anim.hg_hold);
        }
    }

    /** 纵深页退出（各页 finish() 里统一调）：本页淡出＋轻微右滑出，下层静止。 */
    public static void applyPushExit(Activity activity) {
        activity.overridePendingTransition(R.anim.hg_hold, R.anim.hg_pop_out);
    }

    /** 签位页退出（剧场回首页、横屏回竖屏）：本页纯淡出，下层静止。 */
    public static void applyFadeExit(Activity activity) {
        activity.overridePendingTransition(R.anim.hg_hold, R.anim.hg_fade_out);
    }

    // ---------- 页内换表 ----------

    private static final class SwapState {
        boolean running;
        long startedAt;
        Runnable pending;
    }

    private static final WeakHashMap<View, SwapState> SWAPS = new WeakHashMap<>();

    /**
     * 换表淡交：views 先整体淡出（90ms），在 alpha=0 的帧里执行 swap
     * （换适配器/换布局/清表发请求都在这一帧），随后整体淡入。
     * 连点时只认最后一次 swap（中间次作废，免得连切两格闪两轮）。
     */
    public static void fadeSwap(final Runnable swap, final View... views) {
        View anchor = null;
        for (View v : views) {
            if (v != null && v.getVisibility() == View.VISIBLE) {
                anchor = v;
                break;
            }
        }
        if (anchor == null) {
            swap.run();
            return;
        }
        SwapState st = SWAPS.get(anchor);
        if (st == null) {
            st = new SwapState();
            SWAPS.put(anchor, st);
        }
        if (st.running && System.currentTimeMillis() - st.startedAt < 500) {
            st.pending = swap; // 连切：只换不重启动画，最后一次为准
            return;
        }
        st.running = true;
        st.startedAt = System.currentTimeMillis();
        st.pending = swap;
        for (View v : views) {
            if (v == null) continue;
            v.animate().cancel();
            v.animate().alpha(0f).setDuration(CONTENT_OUT_MS)
                    .setInterpolator(ACCEL).start();
        }
        final SwapState state = st;
        anchor.postDelayed(() -> {
            state.running = false;
            Runnable s = state.pending;
            state.pending = null;
            if (s != null) s.run();
            for (View v : views) if (v != null) v.setAlpha(0f);
            fadeInViews(CONTENT_IN_MS, views);
        }, CONTENT_OUT_MS);
    }

    /** 一组视图整体淡入到 1（alpha 起点由调用方/当前值决定）。 */
    public static void fadeInViews(int ms, View... views) {
        for (View v : views) {
            if (v == null) continue;
            v.animate().alpha(1f).setDuration(ms).setInterpolator(DECEL).start();
        }
    }

    /** 单个视图从 0 淡入（元素新出现时用，如筹行后到）。 */
    public static void fadeIn(View v, int ms) {
        if (v == null) return;
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(ms).setInterpolator(DECEL).start();
    }

    /**
     * 数据到达整表淡入：调用方在 notify 之前先 setAlpha(0)，绑定完
     * 同帧调本法把整表带进来——内容出现即是淡入，不许直接砸显。
     */
    public static void contentIn(View v) {
        if (v == null) return;
        v.animate().alpha(1f).setDuration(ARRIVE_MS).setInterpolator(DECEL).start();
    }
}
