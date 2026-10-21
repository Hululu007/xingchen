package com.fongmi.android.tv.ui.dialog;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.ParametricEq;

import java.util.ArrayList;
import java.util.List;

/**
 * 参数均衡器面板（B6）：频段增删、滤波类型、频率（对数）、增益、Q 值。
 * 与系统 audiofx（音效面板的预设＋固定频段＋低音＋环绕）是两套东西：本面板不改称、不替代，
 * 面板内注明「与系统音效叠加生效」；改动即时推给处理链（下一 buffer 起生效）并持久化。
 */
public class ParametricEqDialog extends BaseBottomSheetDialog {

    private static final String[] TYPE_NAMES = {"峰值", "低架", "高架", "低通", "高通", "带通", "陷波"};
    private static final ParametricEq.FilterType[] TYPES = {
            ParametricEq.FilterType.PEAKING, ParametricEq.FilterType.LOWSHELF,
            ParametricEq.FilterType.HIGHSHELF, ParametricEq.FilterType.LOWPASS,
            ParametricEq.FilterType.HIGHPASS, ParametricEq.FilterType.BANDPASS,
            ParametricEq.FilterType.NOTCH};

    private LinearLayout bandBox;
    private List<ParametricEq.Band> bands = new ArrayList<>();

    public static void show(FragmentActivity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            new ParametricEqDialog().show(activity.getSupportFragmentManager(), "music_parametric_eq");
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
        root.setPadding(dp(20), dp(16), dp(20), dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(20));
        root.setBackground(bg);

        TextView title = new TextView(inflater.getContext());
        title.setText(R.string.music_parametric_eq);
        title.setTextSize(17);
        title.setTextColor(0xFF1C1C1E);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        LinearLayout swRow = new LinearLayout(inflater.getContext());
        swRow.setOrientation(LinearLayout.HORIZONTAL);
        swRow.setGravity(Gravity.CENTER_VERTICAL);
        swRow.setPadding(0, dp(16), 0, 0);
        TextView swLabel = new TextView(inflater.getContext());
        swLabel.setText("启用");
        swLabel.setTextSize(14);
        swLabel.setTextColor(0xFF1C1C1E);
        swRow.addView(swLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        SwitchCompat sw = new SwitchCompat(inflater.getContext());
        sw.setChecked(MusicController.get().isParametricEnabled());
        // 开关统一强调色（与设置页同款：钮恒白、开态轨珊瑚红、关态轨浅灰）
        int[][] swStates = new int[][]{{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(swStates, new int[]{0xFFFFFFFF, 0xFFFFFFFF}));
        sw.setTrackTintList(new ColorStateList(swStates, new int[]{0xFFFA243C, 0xFFE5E5EA}));
        sw.setOnCheckedChangeListener((b, on) -> MusicController.get().setParametricEnabled(on));
        swRow.addView(sw);
        root.addView(swRow);

        TextView note = new TextView(inflater.getContext());
        note.setText("与系统音效叠加生效");
        note.setTextSize(12);
        note.setTextColor(0xFF9AA7B8);
        note.setPadding(0, dp(4), 0, 0);
        root.addView(note);

        bandBox = new LinearLayout(inflater.getContext());
        bandBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(bandBox);

        bands = MusicController.get().parametricBands();
        rebuildBands();
        return new ViewBinding() {
            @NonNull
            @Override
            public View getRoot() {
                return root;
            }
        };
    }

    @Override
    protected void initView() {
    }

    private void rebuildBands() {
        if (bandBox == null) return;
        bandBox.removeAllViews();
        for (int i = 0; i < bands.size(); i++) {
            bandBox.addView(bandCard(i));
        }
        TextView add = new TextView(getContext());
        add.setText("＋ 添加频段");
        add.setTextSize(14);
        add.setTextColor(0xFFFA243C);
        add.setGravity(Gravity.CENTER);
        add.setPadding(0, dp(12), 0, dp(12));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFFF3F3F3);
        d.setCornerRadius(dp(12));
        add.setBackground(d);
        add.setOnClickListener(v -> {
            bands.add(new ParametricEq.Band(ParametricEq.FilterType.PEAKING, 1000f, 0f, 1f));
            pushBands();
            rebuildBands();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        bandBox.addView(add, lp);
    }

    private View bandCard(int index) {
        ParametricEq.Band band = bands.get(index);
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF3F3F3);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView type = new TextView(getContext());
        type.setText(TYPE_NAMES[typeIndex(band.type)]);
        type.setTextSize(14);
        type.setTextColor(0xFFFA243C);
        type.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable td = new GradientDrawable();
        td.setColor(0xFFF3F3F3);
        td.setCornerRadius(dp(16));
        type.setBackground(td);
        type.setOnClickListener(v -> {
            band.type = TYPES[(typeIndex(band.type) + 1) % TYPES.length];
            type.setText(TYPE_NAMES[typeIndex(band.type)]);
            pushBands();
        });
        head.addView(type);
        TextView freqLabel = new TextView(getContext());
        freqLabel.setText(freqText(band.freq));
        freqLabel.setTextSize(14);
        freqLabel.setTextColor(0xFF1C1C1E);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        flp.setMarginStart(dp(8));
        head.addView(freqLabel, flp);
        TextView del = new TextView(getContext());
        del.setText("×");
        del.setTextSize(20);
        del.setTextColor(0xFF9AA7B8);
        del.setPadding(dp(8), 0, dp(8), 0);
        del.setOnClickListener(v -> {
            bands.remove(index);
            pushBands();
            rebuildBands();
        });
        head.addView(del);
        card.addView(head);

        SeekBar freqBar = seekBar();
        freqBar.setMax(1000);
        freqBar.setProgress(freqToProgress(band.freq));
        freqBar.setOnSeekBarChangeListener(new SeekEnd() {
            @Override
            public void onProgress(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                band.freq = progressToFreq(progress);
                freqLabel.setText(freqText(band.freq));
            }

            @Override
            public void onEnd(SeekBar sb) {
                pushBands();
            }
        });
        card.addView(freqBar);

        TextView gainLabel = label("增益 " + gainText(band.gainDb));
        card.addView(gainLabel);
        SeekBar gainBar = seekBar();
        gainBar.setMax(240);
        gainBar.setProgress(Math.round((band.gainDb + 12) * 10));
        gainBar.setOnSeekBarChangeListener(new SeekEnd() {
            @Override
            public void onProgress(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                band.gainDb = progress / 10f - 12;
                gainLabel.setText("增益 " + gainText(band.gainDb));
            }

            @Override
            public void onEnd(SeekBar sb) {
                pushBands();
            }
        });
        card.addView(gainBar);

        TextView qLabel = label("Q 值 " + qText(band.q));
        card.addView(qLabel);
        SeekBar qBar = seekBar();
        qBar.setMax(990);
        qBar.setProgress(Math.round((band.q - 0.1f) * 100));
        qBar.setOnSeekBarChangeListener(new SeekEnd() {
            @Override
            public void onProgress(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                band.q = 0.1f + progress / 100f;
                qLabel.setText("Q 值 " + qText(band.q));
            }

            @Override
            public void onEnd(SeekBar sb) {
                pushBands();
            }
        });
        card.addView(qBar);
        return card;
    }

    private void pushBands() {
        MusicController.get().setParametricBands(bands);
    }

    private TextView label(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(0xFF9AA7B8);
        tv.setPadding(0, dp(8), 0, 0);
        return tv;
    }

    private SeekBar seekBar() {
        SeekBar sb = new SeekBar(getContext());
        sb.setProgressTintList(ColorStateList.valueOf(0xFFFA243C));
        sb.setThumbTintList(ColorStateList.valueOf(0xFFFA243C));
        return sb;
    }

    private int typeIndex(ParametricEq.FilterType type) {
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i] == type) return i;
        }
        return 0;
    }

    private static String freqText(float freq) {
        if (freq >= 1000) return String.format("%.1f kHz", freq / 1000f);
        return String.format("%d Hz", Math.round(freq));
    }

    private static String gainText(float gain) {
        return String.format("%+.1f dB", gain);
    }

    private static String qText(float q) {
        return String.format("%.2f", q);
    }

    /** 频率对数映射：20 Hz～20 kHz */
    private static float progressToFreq(int progress) {
        return (float) (20 * Math.pow(1000, progress / 1000.0));
    }

    private static int freqToProgress(float freq) {
        double p = 1000 * Math.log(Math.max(20, Math.min(20000, freq)) / 20.0) / Math.log(1000);
        return (int) Math.round(Math.max(0, Math.min(1000, p)));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 拉杆监听：拖动中只改本地值，松手才推给处理链（与音效面板 SeekEnd 同口径） */
    private abstract static class SeekEnd implements SeekBar.OnSeekBarChangeListener {
        public void onProgress(SeekBar sb, int progress, boolean fromUser) {
        }

        public abstract void onEnd(SeekBar sb);

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            onProgress(seekBar, progress, fromUser);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            onEnd(seekBar);
        }
    }
}
