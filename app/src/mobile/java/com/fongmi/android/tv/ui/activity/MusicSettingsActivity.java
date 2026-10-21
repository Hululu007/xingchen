package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityMusicSettingsBinding;
import com.fongmi.android.tv.music.MusicCache;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * 音乐设置页（音乐组件自己的设置，不进星辰全局设置）。
 * 顶栏只有标题「设置」，无返回箭头，走系统返回；浅色皮跟音乐页一致。
 * 六项（全部真接线，判读点见各自注释）：
 * 1 播放音质=取链默认 quality（prepareTrack 读）；2 失败自动下一首（onPlayFailed 读）；
 * 3 移动网络播放（prepareTrack 取链前读）；4 下载音质（下载取链读，独立于播放音质）；
 * 5 歌词缺失自动搜索（MusicRepository.lyric 读）；6 清除音乐缓存（Glide 磁盘缓存）。
 */
public class MusicSettingsActivity extends BaseActivity {

    private ActivityMusicSettingsBinding binding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, MusicSettingsActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityMusicSettingsBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        initQualityRows();
        // S1：播放失败行由开关改两档单选（停止播放／自动下一首；存值沿用 isAutoSkipOnError，行为不变）
        refreshAutoSkipText();
        binding.rowAutoSkip.setOnClickListener(v -> showAutoSkipDialog());
        initSwitch(binding.swMobilePlay, MusicStore.isMobilePlay(), MusicStore::setMobilePlay);
        binding.rowMobilePlay.setOnClickListener(v -> binding.swMobilePlay.toggle());
        // S1：电池优化白名单行（动作行，跳系统电池优化设置；副文二态按系统实读）
        refreshBatteryText();
        binding.rowBatteryOpt.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        });
        initSwitch(binding.swLyricFallback, MusicStore.isLyricFallback(), MusicStore::setLyricFallback);
        binding.rowLyricFallback.setOnClickListener(v -> binding.swLyricFallback.toggle());
        // S6：AMLL 歌词开关一行（默认开＝现行行为；门控见 MusicRepository.lyricRich／lyricRichAll）
        initSwitch(binding.swAmll, MusicStore.isAmllEnabled(), MusicStore::setAmllEnabled);
        binding.rowAmll.setOnClickListener(v -> binding.swAmll.toggle());
        binding.rowClearCache.setOnClickListener(v -> clearCache());
        refreshCacheSize();
        // U-05：歌词时间偏移行（位置：歌词缺失自动搜索行之后、清除缓存行之前）
        refreshOffsetText();
        binding.rowLyricOffset.setOnClickListener(v -> showOffsetDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统电池优化设置返回后，副文二态按实读刷新
        refreshBatteryText();
    }

    /** S1 播放失败行右侧值：关＝停止播放，开＝自动下一首 */
    private void refreshAutoSkipText() {
        binding.tvAutoSkip.setText(MusicStore.isAutoSkipOnError()
                ? R.string.music_play_failed_next : R.string.music_play_failed_stop);
    }

    private void showAutoSkipDialog() {
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.music_play_failed_stop));
        opts.add(getString(R.string.music_play_failed_next));
        MusicOptionsDialog.show(this, getString(R.string.music_set_play_failed), opts,
                MusicStore.isAutoSkipOnError() ? 1 : 0, idx -> {
                    MusicStore.setAutoSkipOnError(idx == 1);
                    refreshAutoSkipText();
                });
    }

    /** S1 电池优化白名单副文二态：按 PowerManager.isIgnoringBatteryOptimizations 实读 */
    private void refreshBatteryText() {
        boolean ignoring = false;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) ignoring = pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception ignored) {
        }
        binding.tvBatteryOpt.setText(ignoring
                ? R.string.music_battery_ignored : R.string.music_battery_not_ignored);
    }

    /** U-05 右侧当前值：默认“0ms”，非零带符号（如“+200ms”“-200ms”） */
    private static String offsetText(int ms) {
        if (ms == 0) return "0ms";
        return (ms > 0 ? "+" : "") + ms + "ms";
    }

    private void refreshOffsetText() {
        binding.tvLyricOffset.setText(offsetText(MusicStore.getLyricOffsetMs()));
    }

    /**
     * U-05 编辑框：−50ms／+50ms 步进按钮＋当前值显示＋“恢复 0ms”复位；
     * 范围钳制 ±5000ms（星辰侧自定，非瓜子值；存值走 MusicStore.setLyricOffsetMs 的钳制）。
     * 本批只做行＋编辑框＋存值；偏移真正作用到歌词时间由歌词路接同一键名，不在这里改。
     */
    private void showOffsetDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        TextView value = new TextView(this);
        value.setTextSize(22);
        value.setTextColor(0xFFFFFFFF);
        value.setGravity(Gravity.CENTER);
        value.setText(offsetText(MusicStore.getLyricOffsetMs()));
        box.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = pad;
        Button minus = new Button(this);
        minus.setText("−50ms");
        Button plus = new Button(this);
        plus.setText("+50ms");
        Runnable apply = () -> {
            value.setText(offsetText(MusicStore.getLyricOffsetMs()));
            refreshOffsetText();
        };
        minus.setOnClickListener(v -> {
            MusicStore.setLyricOffsetMs(MusicStore.getLyricOffsetMs() - 50);
            apply.run();
        });
        plus.setOnClickListener(v -> {
            MusicStore.setLyricOffsetMs(MusicStore.getLyricOffsetMs() + 50);
            apply.run();
        });
        row.addView(minus);
        row.addView(plus);
        box.addView(row, rowLp);
        Button reset = new Button(this);
        reset.setText("恢复 0ms");
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetLp.topMargin = pad / 2;
        reset.setOnClickListener(v -> {
            MusicStore.setLyricOffsetMs(0);
            apply.run();
        });
        box.addView(reset, resetLp);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("歌词时间偏移")
                .setView(box)
                .setPositiveButton("完成", null)
                .show();
    }

    private void initQualityRows() {
        binding.tvPlayQuality.setText(MusicStore.qualityName(MusicStore.getQuality()));
        binding.rowPlayQuality.setOnClickListener(v -> showQualityDialog(true));
        refreshDlQualityText();
        binding.rowDlQuality.setOnClickListener(v -> showDownloadModeDialog());
    }

    /** 下载行值文案：跟随模式显「跟随播放偏好」，每次询问模式显「每次询问」，固定模式显固定档名（不走生效值，免得跟随下误显播放档名） */
    private void refreshDlQualityText() {
        if (MusicStore.getDownloadMode() == MusicStore.DL_MODE_FOLLOW) {
            binding.tvDlQuality.setText("跟随播放偏好");
        } else if (MusicStore.getDownloadMode() == MusicStore.DL_MODE_ASK) {
            binding.tvDlQuality.setText(R.string.music_dl_mode_ask);
        } else {
            binding.tvDlQuality.setText(MusicStore.qualityName(MusicStore.getDownloadQualityFixed()));
        }
    }

    /** 下载音质模式（瓜子三策略：跟随播放偏好／固定下载音质／每次询问；每次询问的下载时选档窗在下载调用点弹，见 MusicDownloadStore） */
    private void showDownloadModeDialog() {
        List<String> opts = new ArrayList<>();
        opts.add("跟随播放偏好");
        opts.add("固定下载音质");
        opts.add(getString(R.string.music_dl_mode_ask));
        int mode = MusicStore.getDownloadMode();
        int cur = mode == MusicStore.DL_MODE_FOLLOW ? 0 : mode == MusicStore.DL_MODE_ASK ? 2 : 1;
        MusicOptionsDialog.show(this, getString(R.string.music_set_download_quality), opts, cur, idx -> {
            if (idx == 0) {
                MusicStore.setDownloadMode(MusicStore.DL_MODE_FOLLOW);
                refreshDlQualityText();
            } else if (idx == 2) {
                MusicStore.setDownloadMode(MusicStore.DL_MODE_ASK);
                refreshDlQualityText();
            } else {
                MusicStore.setDownloadMode(MusicStore.DL_MODE_FIXED);
                showQualityDialog(false);
            }
        });
    }

    private void showQualityDialog(boolean play) {
        int[] values = {MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD, MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
        List<String> opts = new ArrayList<>();
        for (int v : values) opts.add(MusicStore.qualityName(v));
        int cur = play ? MusicStore.getQuality() : MusicStore.getDownloadQualityFixed();
        MusicOptionsDialog.show(this, getString(play ? R.string.music_set_play_quality : R.string.music_set_download_quality),
                opts, cur, idx -> {
                    if (play) {
                        // 只存默认：下一首 prepare 时生效，不动播放页的单曲临时音质入口
                        MusicStore.setQuality(values[idx]);
                        binding.tvPlayQuality.setText(MusicStore.qualityName(values[idx]));
                    } else {
                        MusicStore.setDownloadQuality(values[idx]);
                        refreshDlQualityText();
                    }
                });
    }

    private interface BoolSetter {
        void set(boolean on);
    }

    private void initSwitch(CompoundButton sw, boolean on, BoolSetter setter) {
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> setter.set(isChecked));
    }

    private void refreshCacheSize() {
        Task.execute(() -> {
            long bytes = MusicCache.size();
            runOnUiThread(() -> binding.tvCacheSize.setText(MusicCache.formatSize(bytes)));
        });
    }

    private void clearCache() {
        MusicCache.clear(() -> {
            Notify.show(R.string.music_cache_cleared);
            refreshCacheSize();
        });
    }
}
