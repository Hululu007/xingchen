package com.fongmi.android.tv.ui.music;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fongmi.android.tv.music.MusicStore;
import com.google.android.material.bottomsheet.BottomSheetDialog;

public final class PlayerToolsDialog {

    private PlayerToolsDialog() {
    }

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF232326);
        bg.setCornerRadius(dp(activity, 16));
        root.setBackground(bg);

        TextView title = new TextView(activity);
        title.setText("播放工具");
        title.setTextSize(16f);
        title.setTypeface(PlayerViews.weight(700));
        title.setTextColor(Color.WHITE);
        root.addView(title);

        root.addView(label(activity, "主题"));
        String[] themeNames = {"经典", "封面卡片", "Apple Music", "流光 CD", "黑胶唱片架"};
        String[] themeIds = {MusicPlayerSettings.THEME_CLASSIC, MusicPlayerSettings.THEME_COVER_IMMERSIVE,
                MusicPlayerSettings.THEME_APPLE_MUSIC, MusicPlayerSettings.THEME_LUMINOUS_CD,
                MusicPlayerSettings.THEME_RECORD_SHELF};
        String current = MusicPlayerSettings.getTheme();
        int selected = 0;
        for (int i = 0; i < themeIds.length; i++) {
            if (themeIds[i].equals(current)
                    || (i == 3 && current != null && current.startsWith("luminous_cd"))) {
                selected = i;
            }
        }
        LinearLayout themeRow = new LinearLayout(activity);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        themeRow.setPadding(0, dp(activity, 6), 0, 0);
        for (int i = 0; i < themeNames.length; i++) {
            final int idx = i;
            TextView chip = chip(activity, themeNames[i], i == selected);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(activity, 8);
            themeRow.addView(chip, lp);
            chip.setOnClickListener(v -> {
                MusicPlayerSettings.setTheme(themeIds[idx]);
                dialog.dismiss();
                activity.recreate();
            });
        }
        root.addView(themeRow);

        root.addView(label(activity, "封面氛围"));
        LinearLayout fluidRow = new LinearLayout(activity);
        fluidRow.setOrientation(LinearLayout.HORIZONTAL);
        fluidRow.setPadding(0, dp(activity, 6), 0, 0);
        TextView fluidChip = chip(activity, MusicPlayerSettings.isFluidBg() ? "流光开" : "流光关",
                MusicPlayerSettings.isFluidBg());
        fluidChip.setOnClickListener(v -> {
            MusicPlayerSettings.getTheme();
            MusicStore.setFluidBg(!MusicStore.isFluidBg());
            dialog.dismiss();
            activity.recreate();
        });
        fluidRow.addView(fluidChip);
        TextView beatChip = chip(activity, MusicPlayerSettings.isFluidBeat() ? "节拍开" : "节拍关",
                MusicPlayerSettings.isFluidBeat());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(activity, 8);
        fluidRow.addView(beatChip, blp);
        beatChip.setOnClickListener(v -> {
            MusicStore.setFluidBeat(!MusicStore.isFluidBeat());
            dialog.dismiss();
            activity.recreate();
        });
        root.addView(fluidRow);

        dialog.setContentView(root);
        dialog.show();
    }

    private static TextView label(Activity a, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(PlayerViews.white(0.65f));
        tv.setPadding(0, dp(a, 12), 0, 0);
        return tv;
    }

    private static TextView chip(Activity a, String text, boolean active) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(a, 14), dp(a, 7), dp(a, 14), dp(a, 7));
        tv.setAlpha(active ? 1f : 0.8f);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(a, 50));
        bg.setColor(PlayerViews.white(active ? 0.30f : 0.12f));
        tv.setBackground(bg);
        return tv;
    }

    private static int dp(Activity a, float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
