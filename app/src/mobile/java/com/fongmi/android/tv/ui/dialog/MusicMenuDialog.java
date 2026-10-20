package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.dialog.BaseBottomSheetDialog;

/**
 * 音乐首页 ☰ 面板：底部弹窗样式，选项为独立卡片行（2026-10-19 卡片选项铁律）。
 * 只有两项：插件管理（进源管理）／设置（进音乐设置页），其它一律不加。
 */
public class MusicMenuDialog extends BaseBottomSheetDialog {

    public interface OnMenu {
        void onMenu(int which);
    }

    public static final int MENU_SITES = 0;
    public static final int MENU_SETTINGS = 1;

    private OnMenu callback;

    public static void show(FragmentActivity activity, OnMenu callback) {
        MusicMenuDialog d = new MusicMenuDialog();
        d.callback = callback;
        d.show(activity.getSupportFragmentManager(), "music_menu");
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        Context ctx = getContext();
        float dp = ctx.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dp);
        root.setPadding(pad, (int) (16 * dp), pad, pad);
        addRow(root, R.drawable.ic_music_note, ctx.getString(R.string.music_plugin_manage), MENU_SITES, dp);
        addRow(root, R.drawable.ic_control_setting, ctx.getString(R.string.music_setting), MENU_SETTINGS, dp);
        final View rootView = root;
        return () -> rootView;
    }

    private void addRow(LinearLayout root, int iconRes, String text, int which, float dp) {
        Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ctx.getDrawable(R.drawable.card_music));
        row.setClickable(true);
        row.setFocusable(true);
        int pad = (int) (16 * dp);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (12 * dp);
        row.setLayoutParams(lp);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        int sz = (int) (44 * dp);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(sz, sz);
        icon.setLayoutParams(ip);
        int ipad = (int) (10 * dp);
        icon.setPadding(ipad, ipad, ipad, ipad);

        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(17);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = (int) (16 * dp);
        tv.setLayoutParams(tp);

        row.addView(icon);
        row.addView(tv);
        row.setOnClickListener(v -> {
            if (callback != null) callback.onMenu(which);
            dismissAllowingStateLoss();
        });
        root.addView(row);
    }
}
