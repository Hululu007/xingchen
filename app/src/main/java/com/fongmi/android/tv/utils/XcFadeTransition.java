package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import com.fongmi.android.tv.R;

/**
 * 渐入渐出统一入口（海阔试水批先行，全 App 推广时复用同一套）。
 * 只管过渡动画：200ms 纯 alpha，加速曲线柔和；
 * 用户在系统里关了动画（或省电模式把动画缩放清零）时自动降级为无动画直达，不闪不跳。
 * 动画与页面加载并行（只改透明度、不阻塞任何加载链），不拖打开速度体感。
 */
public final class XcFadeTransition {

    /** 全 App 统一时长：200ms（180–240ms 区间内选档）。 */
    public static final int DURATION_MS = 200;

    private XcFadeTransition() {
    }

    /** 系统动画开关是否允许动画（任一缩放为 0 即视为关闭）。 */
    public static boolean isAnimEnabled(Context context) {
        if (context == null) return false;
        try {
            ContentResolver cr = context.getContentResolver();
            float animator = Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            float transition = Settings.Global.getFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f);
            float window = Settings.Global.getFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, 1f);
            return animator > 0f && transition > 0f && window > 0f;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 视图淡入（关闭动画时直接显示）。 */
    public static void fadeIn(View v) {
        if (v == null) return;
        v.animate().cancel();
        if (!isAnimEnabled(v.getContext())) {
            v.setAlpha(1f);
            v.setVisibility(View.VISIBLE);
            return;
        }
        v.setAlpha(0f);
        v.setVisibility(View.VISIBLE);
        v.animate().alpha(1f).setDuration(DURATION_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
    }

    /** 视图淡出后 GONE（关闭动画时直接 GONE）。 */
    public static void fadeOutThenGone(View v) {
        if (v == null) return;
        v.animate().cancel();
        if (v.getVisibility() == View.GONE || !isAnimEnabled(v.getContext())) {
            v.setVisibility(View.GONE);
            return;
        }
        v.animate().alpha(0f).setDuration(DURATION_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> v.setVisibility(View.GONE)).start();
    }

    /** 内页切换：新页淡入、旧页淡出（并行，不互相等待）。 */
    public static void swapViews(View show, View... hides) {
        if (hides != null) {
            for (View h : hides) {
                if (h != null && h != show) fadeOutThenGone(h);
            }
        }
        if (show != null) fadeIn(show);
    }

    /** Activity 进场淡入（动画关闭时框架自己会跳过，仍安全）。 */
    public static void applyEnter(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        activity.overridePendingTransition(R.anim.xc_fade_in, R.anim.xc_fade_out);
    }

    /** Activity 退场淡出。 */
    public static void applyExit(Activity activity) {
        if (activity == null) return;
        activity.overridePendingTransition(R.anim.xc_fade_in, R.anim.xc_fade_out);
    }
}
