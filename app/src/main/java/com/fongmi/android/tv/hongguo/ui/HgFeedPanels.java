package com.fongmi.android.tv.hongguo.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.Player;

import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.drama.DramaDanmakuSender;
import com.fongmi.android.tv.hongguo.drama.DramaEntry;
import com.fongmi.android.tv.hongguo.drama.DramaVideoModel;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 播放页的三个小面板（2026-10-30 从零重建，简单口径）：更多面板
 * （倍速/清晰度/弹幕开关＋设置/投屏/定时关闭，UI 同步批按定稿行序重排、
 * 摘选集行——播放页有整宽选集条；投屏后经 HgCaster 打通）、弹幕设置
 * （四滑杆＋两开关）、发弹幕框。都只干一件事，不堆装饰。
 */
public final class HgFeedPanels {

    /** 宿主回调面（竖屏页实现；横屏页阶段8 共用同一面）。 */
    public interface Host {
        Player player();

        String vid();

        String seriesId();

        DramaVideoModel currentModel();

        String currentDefinition();

        void changeDefinition(String definition);

        void setSpeed(float speed);

        void openEpisodes();

        /** 定时关闭设档后回调（宿主可据此提示/重排检查）；缺省无动作。 */
        default void onTimerChanged() {
        }

        /** 打开投屏（宿主经 HgCaster 接壳子投屏组件）；缺省无动作。 */
        default void openCast() {
        }

        void openDanmakuSettings();

        void onDanmakuOptionsChanged();

        void onDanmakuSent(String text, long offsetMs);
    }

    private HgFeedPanels() {
    }

    private static int dp(AppCompatActivity a, float v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static GradientDrawable roundBg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private static LinearLayout sheetRoot(AppCompatActivity a) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundBg(0xFF1C1C1E, dp(a, 18)));
        root.setPadding(dp(a, 18), dp(a, 16), dp(a, 18), dp(a, 16));
        return root;
    }

    private static TextView sectionTitle(AppCompatActivity a, LinearLayout root, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(0xFF9E9EA7);
        t.setTextSize(12.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(a, 14);
        root.addView(t, lp);
        return t;
    }

    private static TextView chip(AppCompatActivity a, String text, boolean on) {
        TextView c = new TextView(a);
        c.setText(text);
        c.setTextSize(13);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(a, 14), dp(a, 7), dp(a, 14), dp(a, 7));
        c.setTextColor(on ? Color.WHITE : 0xFFE8E8EA);
        c.setBackground(roundBg(on ? 0xFFFF5A4E : 0xFF2C2C30, dp(a, 15)));
        return c;
    }

    private static void addChips(AppCompatActivity a, LinearLayout root, List<TextView> chips) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.topMargin = dp(a, 8);
        for (TextView c : chips) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(a, 8);
            row.addView(c, lp);
        }
        root.addView(row, rlp);
    }

    // ---------- 更多面板 ----------

    public static void showMore(AppCompatActivity a, Host host) {
        BottomSheetDialog dialog = new BottomSheetDialog(a);
        LinearLayout root = sheetRoot(a);

        TextView head = new TextView(a);
        head.setText("更多");
        head.setTextColor(Color.WHITE);
        head.setTextSize(16);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(head);

        // 当前倍速（定稿行序：倍速在前，六段 0.75/1/1.25/1.5/2/3，默认 1x）
        sectionTitle(a, root, "当前倍速");
        List<TextView> speedChips = new ArrayList<>();
        float curSpeed = HgStore.getSpeed();
        for (float s : HgPanelLogic.SPEEDS) {
            TextView c = chip(a, speedLabel(s), s == curSpeed);
            c.setOnClickListener(v -> {
                host.setSpeed(s);
                dialog.dismiss();
            });
            speedChips.add(c);
        }
        addChips(a, root, speedChips);

        // 清晰度（当前模型实有档，官方序高→低；缺省取最高档≈1080P 优先）
        DramaVideoModel model = host.currentModel();
        if (model != null && !model.getEntries().isEmpty()) {
            sectionTitle(a, root, "清晰度");
            Set<String> defs = new LinkedHashSet<>();
            for (DramaEntry e : model.getEntries()) {
                if (e.getDefinition() != null && e.isPlayableCodec()) defs.add(e.getDefinition());
            }
            List<TextView> chips = new ArrayList<>();
            String cur = host.currentDefinition();
            for (String def : HgPanelLogic.orderedDefinitions(defs)) {
                TextView c = chip(a, defLabel(def), def.equals(cur));
                c.setOnClickListener(v -> {
                    dialog.dismiss();
                    host.changeDefinition(def);
                });
                chips.add(c);
            }
            addChips(a, root, chips);
        }

        // 弹幕行：开关＋设置
        LinearLayout dmRow = new LinearLayout(a);
        dmRow.setOrientation(LinearLayout.HORIZONTAL);
        dmRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = dp(a, 18);
        TextView dmLabel = new TextView(a);
        dmLabel.setText("弹幕");
        dmLabel.setTextColor(0xFFEEEEEE);
        dmLabel.setTextSize(14.5f);
        dmRow.addView(dmLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView dmSettings = chip(a, "弹幕设置", false);
        dmSettings.setOnClickListener(v -> {
            dialog.dismiss();
            host.openDanmakuSettings();
        });
        dmRow.addView(dmSettings);
        android.widget.Switch dmSwitch = new android.widget.Switch(a);
        dmSwitch.setChecked(HgStore.isDanmakuOn());
        dmSwitch.setOnCheckedChangeListener((b, on) -> {
            HgStore.setDanmakuOn(on);
            host.onDanmakuOptionsChanged();
        });
        LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(-2, -2);
        swlp.leftMargin = dp(a, 10);
        dmRow.addView(dmSwitch, swlp);
        root.addView(dmRow, dlp);

        // 投屏行（定稿行序：弹幕之后、定时关闭之前；经 HgCaster 桥接壳子
        // 投屏组件，见 hongguo-cast 账）
        LinearLayout castRow = new LinearLayout(a);
        castRow.setOrientation(LinearLayout.HORIZONTAL);
        castRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(a, 18);
        TextView castLabel = new TextView(a);
        castLabel.setText("投屏");
        castLabel.setTextColor(0xFFEEEEEE);
        castLabel.setTextSize(14.5f);
        castRow.addView(castLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView castGo = new TextView(a);
        castGo.setText("›");
        castGo.setTextColor(0xFF9E9EA7);
        castGo.setTextSize(16f);
        castRow.addView(castGo);
        castRow.setOnClickListener(v -> {
            dialog.dismiss();
            host.openCast();
        });
        root.addView(castRow, clp);

        // 定时关闭行（状态文案在右）
        LinearLayout timerRow = new LinearLayout(a);
        timerRow.setOrientation(LinearLayout.HORIZONTAL);
        timerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(a, 18);
        TextView timerLabel = new TextView(a);
        timerLabel.setText("定时关闭");
        timerLabel.setTextColor(0xFFEEEEEE);
        timerLabel.setTextSize(14.5f);
        timerRow.addView(timerLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView timerState = new TextView(a);
        timerState.setText(HgPanelLogic.timerStateText(
                HgStore.getTimerMode(), HgStore.getTimerMinutes()));
        timerState.setTextColor(0xFF9E9EA7);
        timerState.setTextSize(13.5f);
        timerRow.addView(timerState);
        timerRow.setOnClickListener(v -> {
            dialog.dismiss();
            showTimerOptions(a, host);
        });
        root.addView(timerRow, tlp);

        dialog.setContentView(root);
        dialog.show();
        clearSheetBg(dialog);
    }

    // ---------- 定时关闭选项 ----------

    public static void showTimerOptions(AppCompatActivity a, Host host) {
        BottomSheetDialog dialog = new BottomSheetDialog(a);
        LinearLayout root = sheetRoot(a);
        TextView head = new TextView(a);
        head.setText("定时关闭");
        head.setTextColor(Color.WHITE);
        head.setTextSize(16);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(head);

        int cur = HgStore.getTimerMode();
        List<TextView> chips = new ArrayList<>();
        String[] labels = {"关闭", "播完本剧", HgPanelLogic.FIXED_MINUTES + "分钟", "自定义"};
        int[] modes = {HgPanelLogic.TIMER_OFF, HgPanelLogic.TIMER_SERIES_END,
                HgPanelLogic.TIMER_FIXED, HgPanelLogic.TIMER_CUSTOM};
        for (int i = 0; i < labels.length; i++) {
            final int m = modes[i];
            TextView c = chip(a, labels[i], m == cur);
            c.setOnClickListener(v -> {
                if (m == HgPanelLogic.TIMER_CUSTOM) {
                    dialog.dismiss();
                    showTimerCustom(a, host);
                    return;
                }
                HgStore.setTimer(m, HgStore.getTimerMinutes());
                host.onTimerChanged();
                dialog.dismiss();
            });
            chips.add(c);
        }
        addChips(a, root, chips);

        dialog.setContentView(root);
        dialog.show();
        clearSheetBg(dialog);
    }

    private static void showTimerCustom(AppCompatActivity a, Host host) {
        EditText input = new EditText(a);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("分钟数");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF77777F);
        int cur = HgStore.getTimerMinutes();
        if (cur > 0) input.setText(String.valueOf(cur));
        new androidx.appcompat.app.AlertDialog.Builder(a)
                .setTitle("自定义定时（分钟）")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    int minutes = 0;
                    try {
                        minutes = Integer.parseInt(
                                input.getText() == null ? "" : input.getText().toString().trim());
                    } catch (Exception ignored) {
                    }
                    if (minutes > 0) {
                        HgStore.setTimer(HgPanelLogic.TIMER_CUSTOM, minutes);
                        host.onTimerChanged();
                    }
                })
                .show();
    }

    private static String defLabel(String def) {
        return HgPanelLogic.definitionLabel(def);
    }

    private static String speedLabel(float s) {
        return HgPanelLogic.speedLabel(s);
    }

    // ---------- 弹幕设置 ----------

    public static void showDanmakuSettings(AppCompatActivity a, Host host) {
        BottomSheetDialog dialog = new BottomSheetDialog(a);
        LinearLayout root = sheetRoot(a);
        TextView head = new TextView(a);
        head.setText("弹幕设置");
        head.setTextColor(Color.WHITE);
        head.setTextSize(16);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(head);

        addSlider(a, root, "不透明度", HgStore.getDanmakuOpacity(), 100, v -> {
            HgStore.setDanmakuOpacity(v);
            host.onDanmakuOptionsChanged();
        });
        addSlider(a, root, "移动速度", HgStore.getDanmakuSpeedIndex(),
                HgPanelLogic.DM_SPEED_LABELS.length - 1, v -> {
                    HgStore.setDanmakuSpeedIndex(v);
                    host.onDanmakuOptionsChanged();
                });
        addSlider(a, root, "显示区域", HgStore.getDanmakuLines() - HgPanelLogic.DM_LINES_MIN,
                HgPanelLogic.DM_LINES_MAX - HgPanelLogic.DM_LINES_MIN, v -> {
                    HgStore.setDanmakuLines(v + HgPanelLogic.DM_LINES_MIN);
                    host.onDanmakuOptionsChanged();
                });
        addSlider(a, root, "字体大小", HgStore.getDanmakuFontIndex(),
                HgPanelLogic.DM_FONT_LABELS.length - 1, v -> {
                    HgStore.setDanmakuFontIndex(v);
                    host.onDanmakuOptionsChanged();
                });
        addSwitchRow(a, root, "屏蔽彩色弹幕", HgStore.getDanmakuBlockColorful(), on -> {
            HgStore.setDanmakuBlockColorful(on);
            host.onDanmakuOptionsChanged();
        });
        addSwitchRow(a, root, "清屏时显示弹幕", HgStore.getDanmakuShowInClean(), on -> {
            HgStore.setDanmakuShowInClean(on);
            host.onDanmakuOptionsChanged();
        });
        TextView reset = new TextView(a);
        reset.setText("恢复默认");
        reset.setTextColor(0xFFFF5A4E);
        reset.setTextSize(14);
        reset.setPadding(0, dp(a, 16), 0, dp(a, 2));
        reset.setOnClickListener(v -> {
            HgStore.resetDanmakuOptions();
            host.onDanmakuOptionsChanged();
            dialog.dismiss();
            showDanmakuSettings(a, host);
        });
        root.addView(reset);

        dialog.setContentView(root);
        dialog.show();
        clearSheetBg(dialog);
    }

    private interface IntSetter {
        void set(int v);
    }

    private interface BoolSetter2 {
        void set(boolean on);
    }

    private static void addSlider(AppCompatActivity a, LinearLayout root, String label,
                                  int value, int max, IntSetter setter) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(a, 12);
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextColor(0xFFEEEEEE);
        t.setTextSize(14);
        row.addView(t, new LinearLayout.LayoutParams(dp(a, 76), -2));
        SeekBar bar = new SeekBar(a);
        bar.setMax(Math.max(1, max));
        bar.setProgress(Math.min(value, Math.max(1, max)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) setter.set(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(row, rlp);
    }

    private static void addSwitchRow(AppCompatActivity a, LinearLayout root, String label,
                                     boolean initial, BoolSetter2 setter) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(a, 12);
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextColor(0xFFEEEEEE);
        t.setTextSize(14);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.Switch sw = new android.widget.Switch(a);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((b, on) -> setter.set(on));
        row.addView(sw);
        root.addView(row, rlp);
    }

    // ---------- 发弹幕 ----------

    public static void showDanmakuSend(AppCompatActivity a, Host host) {
        if (host.seriesId() == null || host.vid() == null) return;
        BottomSheetDialog dialog = new BottomSheetDialog(a);
        LinearLayout root = sheetRoot(a);
        TextView head = new TextView(a);
        head.setText("发弹幕");
        head.setTextColor(Color.WHITE);
        head.setTextSize(16);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(head);

        EditText input = new EditText(a);
        input.setHint("说点什么…");
        input.setHintTextColor(0xFF77777F);
        input.setTextColor(Color.WHITE);
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setBackground(roundBg(0xFF2C2C30, dp(a, 10)));
        input.setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.topMargin = dp(a, 12);
        root.addView(input, ilp);

        TextView error = new TextView(a);
        error.setText("发送失败，请重试");
        error.setTextColor(0xFFFF5A4E);
        error.setTextSize(12.5f);
        error.setVisibility(View.GONE);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
        elp.topMargin = dp(a, 8);
        root.addView(error, elp);

        TextView send = new TextView(a);
        send.setText("发送");
        send.setTextColor(Color.WHITE);
        send.setTextSize(15);
        send.setGravity(Gravity.CENTER);
        send.setBackground(roundBg(0xFFFF5A4E, dp(a, 10)));
        send.setPadding(0, dp(a, 11), 0, dp(a, 11));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(a, 14);
        root.addView(send, slp);

        final boolean[] sending = {false};
        send.setOnClickListener(v -> {
            String text = input.getText() == null ? "" : input.getText().toString().trim();
            if (text.isEmpty() || sending[0]) return;
            sending[0] = true;
            send.setAlpha(0.5f);
            error.setVisibility(View.GONE);
            Player p = host.player();
            long offset = p == null ? 0 : Math.max(0, p.getCurrentPosition());
            DramaDanmakuSender.publish(host.seriesId(), host.vid(), text, offset, new HgCallback<Void>() {
                @Override
                public void onSuccess(Void ignored) {
                    a.runOnUiThread(() -> {
                        dialog.dismiss();
                        host.onDanmakuSent(text, offset);
                    });
                }

                @Override
                public void onError(int code, String message) {
                    a.runOnUiThread(() -> {
                        sending[0] = false;
                        send.setAlpha(1f);
                        error.setVisibility(View.VISIBLE);
                    });
                }
            });
        });

        dialog.setContentView(root);
        dialog.show();
        clearSheetBg(dialog);
        input.requestFocus();
    }

    private static void clearSheetBg(BottomSheetDialog dialog) {
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) sheet.setBackgroundColor(Color.TRANSPARENT);
    }
}
