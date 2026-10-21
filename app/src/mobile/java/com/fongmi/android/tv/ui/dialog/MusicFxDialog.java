package com.fongmi.android.tv.ui.dialog;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogMusicFxBinding;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.service.MusicPlaybackService;
import com.fongmi.android.tv.utils.Notify;

import java.util.List;

/**
 * 播放页「更多 → 音效」面板：总开关＋系统预设＋各段拉杆＋低音增强＋环绕。
 * 状态全从播放服务的 audiofx 三件读，设备不支持某项就置灰写「不支持」，
 * 总开关关＝三件全 bypass。设置持久化走 MusicStore.music_fx_*。
 * 听感真机验（本机验不到）。
 */
public class MusicFxDialog extends BaseBottomSheetDialog {

    private DialogMusicFxBinding binding;
    private MusicPlaybackService.FxState state;

    public static void show(FragmentActivity activity) {
        new MusicFxDialog().show(activity.getSupportFragmentManager(), "music_fx");
    }

    // 内容自带深色底（card_music）：弹窗容器/窗口必须透明，
    // 否则 Material 底部弹窗默认的浅色层会从顶部漏出一条白带。
    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogMusicFxBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        state = MusicController.get().fxState();
        if (state == null) {
            Notify.show("播放器还没起来");
            dismissAllowingStateLoss();
            return;
        }
        binding.swFx.setChecked(state.enabled);
        binding.swFx.setOnCheckedChangeListener((b, on) -> {
            MusicController.get().setFxEnabled(on);
            refresh();
        });
        binding.seekBass.setProgressTintList(ColorStateList.valueOf(0xFFFA243C));
        binding.seekBass.setThumbTintList(ColorStateList.valueOf(0xFFFA243C));
        binding.seekVirt.setProgressTintList(ColorStateList.valueOf(0xFFFA243C));
        binding.seekVirt.setThumbTintList(ColorStateList.valueOf(0xFFFA243C));
        binding.seekBass.setOnSeekBarChangeListener(new SeekEnd() {
            @Override
            public void onEnd(SeekBar sb) {
                MusicController.get().setFxBass(sb.getProgress() * 10);
            }
        });
        binding.seekVirt.setOnSeekBarChangeListener(new SeekEnd() {
            @Override
            public void onEnd(SeekBar sb) {
                MusicController.get().setFxVirtualizer(sb.getProgress() * 10);
            }
        });
        // B6：参数均衡器入口（与系统 audiofx 并存的另一套，点开独立面板；本面板不改称）
        TextView eqRow = new TextView(getContext());
        eqRow.setText(getString(R.string.music_parametric_eq) + " ›");
        eqRow.setTextSize(15);
        eqRow.setTextColor(0xFFFFFFFF);
        eqRow.setPadding(0, dp(16), 0, dp(4));
        eqRow.setOnClickListener(v -> {
            FragmentActivity host = getActivity();
            dismissAllowingStateLoss();
            if (host != null) ParametricEqDialog.show(host);
        });
        ((LinearLayout) binding.getRoot()).addView(eqRow);
        refresh();
    }

    /** 重新从服务读状态并重建全部控件（预设/拉杆/开关状态） */
    private void refresh() {
        MusicPlaybackService.FxState s = MusicController.get().fxState();
        if (s == null || binding == null) return;
        state = s;
        binding.swFx.setOnCheckedChangeListener(null);
        binding.swFx.setChecked(s.enabled);
        binding.swFx.setOnCheckedChangeListener((b, on) -> {
            MusicController.get().setFxEnabled(on);
            refresh();
        });
        buildPresets(s);
        buildBands(s);
        buildBassVirt(s);
    }

    // ================= 预设 =================

    private void buildPresets(MusicPlaybackService.FxState s) {
        binding.presetRow.removeAllViews();
        if (!s.eqSupported) {
            binding.tvPresetLabel.setText(R.string.music_fx_preset);
            binding.scrollPresets.setVisibility(View.GONE);
            return;
        }
        binding.scrollPresets.setVisibility(View.VISIBLE);
        List<String> presets = s.presets;
        for (int i = 0; i < presets.size(); i++) {
            TextView chip = chip(presets.get(i), i == s.preset);
            final int idx = i;
            chip.setOnClickListener(v -> {
                if (!s.enabled) return;
                MusicController.get().setFxPreset(idx);
                refresh();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(10);
            binding.presetRow.addView(chip, lp);
        }
    }

    private TextView chip(String text, boolean selected) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(13);
        int pad = dp(14);
        tv.setPadding(pad, dp(8), pad, dp(8));
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(16));
        if (selected) {
            d.setColor(0xFFFA243C);
            tv.setTextColor(0xFF1A1206);
        } else {
            d.setColor(0xFF1C2636);
            tv.setTextColor(0xFF9AA7B8);
        }
        tv.setBackground(d);
        return tv;
    }

    // ================= 均衡器拉杆 =================

    private void buildBands(MusicPlaybackService.FxState s) {
        binding.bandBox.removeAllViews();
        if (!s.eqSupported) {
            binding.tvEqLabel.setText(getString(R.string.music_equalizer) + "（" + getString(R.string.music_fx_unsupported) + "）");
            return;
        }
        binding.tvEqLabel.setText(R.string.music_equalizer);
        int n = s.levels.length;
        for (int i = 0; i < n; i++) {
            final int band = i;
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView label = new TextView(getContext());
            label.setText(freqText(s.centerFreqsHz[i]));
            label.setTextColor(0xFF9AA7B8);
            label.setTextSize(12);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(label, llp);
            SeekBar bar = new SeekBar(getContext());
            bar.setMax(s.maxLevel - s.minLevel);
            bar.setProgress(s.levels[i] - s.minLevel);
            bar.setProgressTintList(ColorStateList.valueOf(0xFFFA243C));
            bar.setThumbTintList(ColorStateList.valueOf(0xFFFA243C));
            bar.setEnabled(s.enabled);
            bar.setOnSeekBarChangeListener(new SeekEnd() {
                @Override
                public void onEnd(SeekBar sb) {
                    MusicController.get().setFxBandLevel(band, sb.getProgress() + s.minLevel);
                    refresh(); // 手动拉杆离预设，预设高亮回正
                }
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(bar, blp);
            binding.bandBox.addView(row);
        }
    }

    private static String freqText(int hz) {
        if (hz < 1000) return hz + "Hz";
        if (hz % 1000 == 0) return (hz / 1000) + "kHz";
        return (hz / 1000f) + "kHz";
    }

    // ================= 低音增强／环绕 =================

    private void buildBassVirt(MusicPlaybackService.FxState s) {
        bindStrength(binding.seekBass, binding.tvBassLabel, R.string.music_fx_bass, s.bassSupported, s.bass, s.enabled);
        bindStrength(binding.seekVirt, binding.tvVirtLabel, R.string.music_fx_virtualizer, s.virtSupported, s.virt, s.enabled);
    }

    private void bindStrength(SeekBar bar, TextView label, int labelRes, boolean supported, int strength, boolean on) {
        bar.setOnSeekBarChangeListener(null);
        if (!supported) {
            label.setText(getString(labelRes) + "（" + getString(R.string.music_fx_unsupported) + "）");
            bar.setEnabled(false);
            bar.setProgress(0);
            return;
        }
        label.setText(labelRes);
        bar.setEnabled(on);
        bar.setProgress(strength / 10);
        final boolean isBass = bar == binding.seekBass;
        bar.setOnSeekBarChangeListener(new SeekEnd() {
            @Override
            public void onEnd(SeekBar sb) {
                if (isBass) MusicController.get().setFxBass(sb.getProgress() * 10);
                else MusicController.get().setFxVirtualizer(sb.getProgress() * 10);
            }
        });
    }

    private int dp(int v) {
        float d = getContext().getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }

    private abstract static class SeekEnd implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            onEnd(seekBar);
        }

        abstract void onEnd(SeekBar seekBar);
    }
}
