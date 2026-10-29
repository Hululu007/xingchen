package com.fongmi.android.tv.hongguo.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import com.airbnb.lottie.LottieAnimationView;

/**
 * 剧条追更药丸的一次性入场动画（官方 FollowUpdateLottieView 的动画层
 * 同构复刻，证据见 hongguo-rework-p3-pillanim 账）：
 *
 * <p>官方形态：药丸常态只有静态铃铛＋文案；动画是「第一次见到未追
 * 药丸」时的一次性引导——绑定后延迟 3000ms，若届时仍满足（药丸在显、
 * 未追、一次性旗未置）则：静态铃铛隐形，铃位 Lottie 播一遍（资产
 * follow_update_entrance_v721.json，官方从其 CDN 同名文件加载、本家
 * 原样搬进 assets），同时身后光晕板（oy 同源图）走 1300ms 透明度
 * 脉冲（关键帧 0→0／0.2307→1／0.7692→1／1→0，线性），板动画结束
 * 即复位静态态并置一次性旗。已追态渲染或点下追更＝立即复位＋置旗
 * （官方 q2(true)→G2+K2）；改绑/藏条＝复位（官方 p2→G2）。</p>
 *
 * <p>待发 Runnable 与板动画分别挂在 anim/plate 的 tag 上，复位时
 * 一并撤净，Holder 复用不会串台。</p>
 */
public final class HgPillEntrance {

    private HgPillEntrance() {
    }

    /**
     * 药丸以「未追」态绑定且判定可见时调：先复位，再按一次性旗决定
     * 是否排入场动画。已追态走 {@link #onFollowed}，不可见/预约态
     * 只需 {@link #reset}。
     */
    public static void onBound(View pill, ImageView plate, ImageView bell,
                               LottieAnimationView anim) {
        reset(pill, plate, bell, anim);
        if (HgStore.pillEntranceAnimShown()) return;
        Runnable fire = () -> play(pill, plate, bell, anim);
        anim.setTag(fire);
        pill.postDelayed(fire, 3000L);
    }

    /** 已追态落定：复位并置一次性旗（此后再绑任何药丸都不再排动画）。 */
    public static void onFollowed(View pill, ImageView plate, ImageView bell,
                                  LottieAnimationView anim) {
        reset(pill, plate, bell, anim);
        HgStore.markPillEntranceAnimShown();
    }

    /** 复位到静态态：撤待发、停板动画、藏动画层、亮静态铃。 */
    public static void reset(View pill, ImageView plate, ImageView bell,
                             LottieAnimationView anim) {
        Object pending = anim.getTag();
        anim.setTag(null);
        if (pending instanceof Runnable) pill.removeCallbacks((Runnable) pending);
        Object running = plate.getTag();
        plate.setTag(null);
        if (running instanceof ObjectAnimator) ((ObjectAnimator) running).cancel();
        anim.pauseAnimation();
        anim.setVisibility(View.GONE);
        plate.setVisibility(View.GONE);
        plate.setAlpha(0f);
        bell.setVisibility(View.VISIBLE);
    }

    private static void play(View pill, ImageView plate, ImageView bell,
                             LottieAnimationView anim) {
        anim.setTag(null);
        // 官方在协程醒来后复查 F2：旗已置/药丸已藏/视图已脱窗都不播
        if (HgStore.pillEntranceAnimShown()) return;
        if (pill.getVisibility() != View.VISIBLE || !pill.isAttachedToWindow()) return;
        bell.setVisibility(View.INVISIBLE);
        anim.setRepeatCount(0);
        anim.setProgress(0f);
        anim.setVisibility(View.VISIBLE);
        anim.playAnimation();
        plate.setVisibility(View.VISIBLE);
        ObjectAnimator oa = ObjectAnimator.ofPropertyValuesHolder(plate,
                PropertyValuesHolder.ofKeyframe(View.ALPHA,
                        Keyframe.ofFloat(0f, 0f),
                        Keyframe.ofFloat(0.23076923f, 1f),
                        Keyframe.ofFloat(0.7692308f, 1f),
                        Keyframe.ofFloat(1f, 0f)));
        oa.setDuration(1300L);
        oa.setInterpolator(new LinearInterpolator());
        oa.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                reset(pill, plate, bell, anim);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                reset(pill, plate, bell, anim);
            }
        });
        plate.setTag(oa);
        oa.start();
        HgStore.markPillEntranceAnimShown();
    }
}
