package com.fongmi.android.tv.ui.hk;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.fongmi.android.tv.R;

import java.util.WeakHashMap;

/**
 * 详情预取加载小卡：B 版浅卡金字（半透奶油白底、细灰描边、金色细线转圈、深金字）。
 * 非模态：只吃掉卡片自身区域的触摸，列表内容透在后面、照常可见可滚；
 * 条目点按的单飞锁由宿主侧另行控制。
 */
public final class HkPrefetchCard {

    private static final long FAIL_AUTO_DISMISS_MS = 1400L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final class CardHolder {
        final View card;
        Runnable failDismiss;

        CardHolder(View card) {
            this.card = card;
        }
    }

    private static final WeakHashMap<Activity, CardHolder> CARDS = new WeakHashMap<>();

    private HkPrefetchCard() {
    }

    private static int dp(Activity a, int dp) {
        return Math.round(dp * a.getResources().getDisplayMetrics().density);
    }

    private static void runUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else MAIN.post(r);
    }

    private static void setText(View card, String text) {
        try {
            TextView tv = card.findViewById(R.id.hk_prefetch_text);
            if (tv != null) tv.setText(text == null ? "" : text);
        } catch (Exception ignored) {
        }
    }

    private static void setSpinnerVisible(View card, boolean visible) {
        try {
            ProgressBar pb = card.findViewById(R.id.hk_prefetch_spinner);
            if (pb != null) pb.setVisibility(visible ? View.VISIBLE : View.GONE);
        } catch (Exception ignored) {
        }
    }

    /** 显示小卡（已在显示则回到加载态并刷新文字）。 */
    public static void show(Activity activity, String text) {
        if (activity == null) return;
        runUi(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            CardHolder h;
            synchronized (CARDS) {
                h = CARDS.get(activity);
            }
            View card = h == null ? null : h.card;
            if (card == null || card.getParent() == null) {
                ViewGroup root = activity.findViewById(android.R.id.content);
                if (root == null) return;
                card = LayoutInflater.from(activity).inflate(R.layout.hk_prefetch_card, root, false);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER);
                lp.topMargin = dp(activity, 110); // 中央偏下
                card.setAlpha(0f);
                root.addView(card, lp);
                card.animate().alpha(1f).setDuration(150).start();
                h = new CardHolder(card);
                synchronized (CARDS) {
                    CARDS.put(activity, h);
                }
            } else if (h.failDismiss != null) {
                MAIN.removeCallbacks(h.failDismiss);
                h.failDismiss = null;
            }
            setText(card, text == null ? "加载中" : text);
            setSpinnerVisible(card, true);
        });
    }

    /** 原地换成失败提示（同款浅卡皮），约 1.4 秒后自动收掉。 */
    public static void fail(Activity activity, String text) {
        if (activity == null) return;
        runUi(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            CardHolder h;
            synchronized (CARDS) {
                h = CARDS.get(activity);
            }
            View card = h == null ? null : h.card;
            if (card == null || card.getParent() == null) return;
            if (h.failDismiss != null) MAIN.removeCallbacks(h.failDismiss);
            setText(card, text == null ? "加载失败" : text);
            setSpinnerVisible(card, false);
            final Activity fa = activity;
            h.failDismiss = () -> dismiss(fa);
            MAIN.postDelayed(h.failDismiss, FAIL_AUTO_DISMISS_MS);
        });
    }

    /** 立刻收掉小卡。 */
    public static void dismiss(Activity activity) {
        if (activity == null) return;
        runUi(() -> {
            CardHolder h;
            synchronized (CARDS) {
                h = CARDS.remove(activity);
            }
            if (h == null || h.card == null) return;
            if (h.failDismiss != null) MAIN.removeCallbacks(h.failDismiss);
            final View card = h.card;
            ViewGroup parent = (ViewGroup) card.getParent();
            if (parent == null) return;
            card.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                ViewGroup p = (ViewGroup) card.getParent();
                if (p != null) p.removeView(card);
            }).start();
        });
    }
}
