package com.fongmi.android.tv.ui.music;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.fongmi.android.tv.music.MusicStore;
import com.google.android.material.bottomsheet.BottomSheetDialog;

public final class LyricStyleDialog {

    private LyricStyleDialog() {
    }

    public static void show(Activity activity, Runnable onChanged) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF232326);
        bg.setCornerRadius(dp(activity, 16));
        root.setBackground(bg);
        scroll.addView(root);

        root.addView(title(activity, "歌词样式"));
        root.addView(rowLabel(activity, "字号"));
        SeekBar sizeBar = new SeekBar(activity);
        sizeBar.setMax(24);
        sizeBar.setProgress(Math.round(MusicStore.getLyricFontSize() - 16f));
        root.addView(sizeBar, full(activity));
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                MusicStore.setLyricFontSize(16f + progress);
                if (onChanged != null) onChanged.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        root.addView(rowLabel(activity, "粗细"));
        root.addView(chipRow(activity, new String[]{"标准", "加粗", "特粗"},
                MusicStore.getLyricWeight(), idx -> {
                    MusicStore.setLyricWeight(idx);
                    if (onChanged != null) onChanged.run();
                }));

        root.addView(rowLabel(activity, "非当前行模糊"));
        root.addView(chipRow(activity, new String[]{"关闭", "轻度", "中度"},
                MusicStore.getCoverLyricBlurLevel(), idx -> {
                    MusicStore.setCoverLyricBlurLevel(idx);
                    if (onChanged != null) onChanged.run();
                }));

        root.addView(rowLabel(activity, "对齐"));
        root.addView(chipRow(activity, new String[]{"左", "中", "右"},
                MusicStore.getLyricAlign(), idx -> {
                    MusicStore.setLyricAlign(idx);
                    if (onChanged != null) onChanged.run();
                }));

        root.addView(rowLabel(activity, "副行"));
        root.addView(chipRow(activity, new String[]{"翻译开", "翻译关"},
                MusicStore.isShowTranslation() ? 0 : 1, idx -> {
                    MusicStore.setShowTranslation(idx == 0);
                    if (onChanged != null) onChanged.run();
                }));
        root.addView(chipRow(activity, new String[]{"音译开", "音译关"},
                MusicStore.isShowRomanization() ? 0 : 1, idx -> {
                    MusicStore.setShowRomanization(idx == 0);
                    if (onChanged != null) onChanged.run();
                }));

        dialog.setContentView(scroll);
        dialog.show();
    }

    private interface Pick {
        void onPick(int index);
    }

    private static TextView title(Activity a, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(16f);
        tv.setTypeface(PlayerViews.weight(700));
        tv.setTextColor(Color.WHITE);
        tv.setPadding(0, 0, 0, dp(a, 8));
        return tv;
    }

    private static TextView rowLabel(Activity a, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(PlayerViews.white(0.65f));
        tv.setPadding(0, dp(a, 10), 0, dp(a, 6));
        return tv;
    }

    private static LinearLayout chipRow(Activity a, String[] labels, int selected, Pick pick) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] chips = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView chip = new TextView(a);
            chip.setText(labels[i]);
            chip.setTextSize(13f);
            chip.setTextColor(Color.WHITE);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(a, 14), dp(a, 7), dp(a, 14), dp(a, 7));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(a, 50));
            chip.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = dp(a, 8);
            row.addView(chip, lp);
            chips[i] = chip;
            chip.setOnClickListener(v -> {
                pick.onPick(idx);
                styleChips(a, chips, idx);
            });
        }
        styleChips(a, chips, selected);
        return row;
    }

    private static void styleChips(Activity a, TextView[] chips, int selected) {
        for (int i = 0; i < chips.length; i++) {
            GradientDrawable bg = (GradientDrawable) chips[i].getBackground();
            bg.setColor(PlayerViews.white(i == selected ? 0.30f : 0.12f));
            chips[i].setAlpha(i == selected ? 1f : 0.8f);
        }
    }

    private static LinearLayout.LayoutParams full(Activity a) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Activity a, float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
