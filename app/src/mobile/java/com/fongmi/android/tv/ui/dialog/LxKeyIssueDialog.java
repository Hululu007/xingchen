package com.fongmi.android.tv.ui.dialog;

import com.fongmi.android.tv.ui.music.MusicFrostDrawable;
import com.fongmi.android.tv.ui.music.MusicGlassWire;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

/**
 * 落雪密钥问题弹窗（B4）：只在 LxKeyIssue 到达时弹（脚本明确报了 401／403），无此错误绝不弹。
 * 只提示＋重试，不提供密钥输入／登录入口——星辰没有密钥下发链，也不做账号功能。
 */
public class LxKeyIssueDialog extends BaseBottomSheetDialog {

    private String platform;
    private String detail;
    private Runnable onRetry;

    // 重试回调按令牌登记在进程内表里：令牌随 arguments 走，系统重建弹窗后凭令牌取回；
    // 弹窗真正销毁（非配置变化）时摘除防泄漏。
    private static final java.util.Map<String, Runnable> PENDING = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    public static void show(FragmentActivity activity, String platform, String detail, Runnable onRetry) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            // 同一弹窗已在就不再叠一层（取链重试／歌词重试可能连发事件）
            if (activity.getSupportFragmentManager().findFragmentByTag("lx_key_issue") != null) return;
        } catch (Throwable ignored) {
        }
        LxKeyIssueDialog d = new LxKeyIssueDialog();
        d.platform = platform == null ? "" : platform;
        d.detail = detail == null ? "" : detail;
        d.onRetry = onRetry;
        android.os.Bundle args = new android.os.Bundle();
        args.putString("platform", d.platform);
        args.putString("detail", d.detail);
        String token = null;
        if (onRetry != null) {
            token = "lx_" + SEQ.incrementAndGet();
            PENDING.put(token, onRetry);
            args.putString("cb_token", token);
        }
        d.setArguments(args);
        try {
            d.show(activity.getSupportFragmentManager(), "lx_key_issue");
        } catch (Throwable ignored) {
            // show 失败被吞：弹窗不会走 onDestroy 摘令牌，在这里摘掉防残留
            if (token != null) PENDING.remove(token);
        }
    }

    private Runnable resolveRetry() {
        if (onRetry != null) return onRetry;
        android.os.Bundle args = getArguments();
        String token = args == null ? null : args.getString("cb_token");
        return token == null ? null : PENDING.get(token);
    }

    // 失效自关判定：字段已丢（系统重建的新实例）＋arguments 里有令牌（原本登记过重试回调）
    // ＋凭令牌回表取不到。三条全中只可能是进程死亡把静态表清空，重试回调已不可恢复。
    private boolean callbackLost() {
        if (onRetry != null) return false;
        android.os.Bundle args = getArguments();
        String token = args == null ? null : args.getString("cb_token");
        return token != null && PENDING.get(token) == null;
    }

    @Override
    public void onStart() {
        super.onStart();
        // 显示后自检：取不到重试回调就立即自关，不留能看不能点的僵尸弹窗；用户从活页面重开即正常。
        if (callbackLost()) dismissAllowingStateLoss();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (getActivity() == null || !getActivity().isChangingConfigurations()) {
            android.os.Bundle args = getArguments();
            String token = args == null ? null : args.getString("cb_token");
            if (token != null) PENDING.remove(token);
        }
    }

    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        // 系统重建（旋转／进程回收）后实例字段丢失，文案从 arguments 恢复，避免空指针。
        android.os.Bundle args = getArguments();
        if (args != null) {
            platform = args.getString("platform", platform);
            detail = args.getString("detail", detail);
        }
        if (platform == null) platform = "";
        if (detail == null) detail = "";
        LinearLayout root = new LinearLayout(inflater.getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(16), pad, dp(20));
        // 面板底＝全 App 同源液态玻璃（原纯白 GradientDrawable 底作废）：仅顶角 20dp、贴底无缺口
        root.setBackground(new MusicFrostDrawable(inflater.getContext(), 20, true));

        TextView title = new TextView(inflater.getContext());
        title.setText("来源可能有问题");
        title.setTextSize(17);
        title.setTextColor(0xFF1C1C1E);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(inflater.getContext());
        sub.setText(platform.isEmpty() ? "该来源返回了 401／403，可能是密钥失效"
                : "来源 " + platform + " 返回了 401／403，可能是密钥失效");
        sub.setTextSize(13);
        sub.setTextColor(0xFF8E8E93);
        sub.setPadding(0, dp(8), 0, 0);
        root.addView(sub);

        if (!detail.isEmpty()) {
            TextView dt = new TextView(inflater.getContext());
            dt.setText(detail.length() > 200 ? detail.substring(0, 200) : detail);
            dt.setTextSize(12);
            dt.setTextColor(0xFF9A9AA0);
            dt.setPadding(0, dp(8), 0, 0);
            root.addView(dt);
        }

        LinearLayout row = new LinearLayout(inflater.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(20), 0, 0);
        TextView ok = button(inflater, "知道了", false);
        ok.setOnClickListener(v -> dismissAllowingStateLoss());
        TextView retry = button(inflater, "重试", true);
        retry.setOnClickListener(v -> {
            dismissAllowingStateLoss();
            Runnable r = resolveRetry();
            if (r != null) r.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(dp(10));
        row.addView(ok, lp);
        row.addView(retry, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);

        return new ViewBinding() {
            @NonNull
            @Override
            public View getRoot() {
                return root;
            }
        };
    }

    private TextView button(LayoutInflater inflater, String text, boolean primary) {
        TextView b = new TextView(inflater.getContext());
        b.setText(text);
        b.setTextSize(15);
        b.setGravity(android.view.Gravity.CENTER);
        b.setPadding(0, dp(12), 0, dp(12));
        b.setTextColor(0xFFFFFFFF);
        // 两钮同换同源玻璃（原主钮深实底、次钮近白实底摆在玻璃根上成白板，2026-10-26 全扫）；
        // 主钮加 tone 与次钮拉开主次（选中青同族的深色调）
        MusicGlassWire.glassifyView(b, 12f);
        if (primary && b.getBackground() instanceof MusicFrostDrawable) {
            ((MusicFrostDrawable) b.getBackground()).setToneColor(0xFF1C1C1E, 0.45f);
        }
        return b;
    }

    @Override
    protected void initView() {
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
