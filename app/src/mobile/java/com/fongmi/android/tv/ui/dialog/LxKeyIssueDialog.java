package com.fongmi.android.tv.ui.dialog;

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
        try {
            d.show(activity.getSupportFragmentManager(), "lx_key_issue");
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        LinearLayout root = new LinearLayout(inflater.getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(16), pad, dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(20));
        root.setBackground(bg);

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
            if (onRetry != null) onRetry.run();
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
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        if (primary) {
            d.setColor(0xFF1C1C1E);
            b.setTextColor(0xFFFFFFFF);
        } else {
            d.setColor(0xFFF2F2F7);
            b.setTextColor(0xFF1C1C1E);
        }
        b.setBackground(d);
        return b;
    }

    @Override
    protected void initView() {
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
