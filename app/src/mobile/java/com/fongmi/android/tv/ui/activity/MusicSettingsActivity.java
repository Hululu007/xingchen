package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.CompoundButton;

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
 * 顶栏只有标题「设置」，无返回箭头，走系统返回；深色皮跟音乐页一致。
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
        initSwitch(binding.swAutoSkip, MusicStore.isAutoSkipOnError(), MusicStore::setAutoSkipOnError);
        binding.rowAutoSkip.setOnClickListener(v -> binding.swAutoSkip.toggle());
        initSwitch(binding.swMobilePlay, MusicStore.isMobilePlay(), MusicStore::setMobilePlay);
        binding.rowMobilePlay.setOnClickListener(v -> binding.swMobilePlay.toggle());
        initSwitch(binding.swLyricFallback, MusicStore.isLyricFallback(), MusicStore::setLyricFallback);
        binding.rowLyricFallback.setOnClickListener(v -> binding.swLyricFallback.toggle());
        binding.rowClearCache.setOnClickListener(v -> clearCache());
        refreshCacheSize();
    }

    private void initQualityRows() {
        binding.tvPlayQuality.setText(MusicStore.qualityName(MusicStore.getQuality()));
        binding.rowPlayQuality.setOnClickListener(v -> showQualityDialog(true));
        binding.tvDlQuality.setText(MusicStore.qualityName(MusicStore.getDownloadQuality()));
        binding.rowDlQuality.setOnClickListener(v -> showQualityDialog(false));
    }

    private void showQualityDialog(boolean play) {
        int[] values = {MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD, MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
        List<String> opts = new ArrayList<>();
        for (int v : values) opts.add(MusicStore.qualityName(v));
        int cur = play ? MusicStore.getQuality() : MusicStore.getDownloadQuality();
        MusicOptionsDialog.show(this, getString(play ? R.string.music_set_play_quality : R.string.music_set_download_quality),
                opts, cur, idx -> {
                    if (play) {
                        // 只存默认：下一首 prepare 时生效，不动播放页的单曲临时音质入口
                        MusicStore.setQuality(values[idx]);
                        binding.tvPlayQuality.setText(MusicStore.qualityName(values[idx]));
                    } else {
                        MusicStore.setDownloadQuality(values[idx]);
                        binding.tvDlQuality.setText(MusicStore.qualityName(values[idx]));
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
