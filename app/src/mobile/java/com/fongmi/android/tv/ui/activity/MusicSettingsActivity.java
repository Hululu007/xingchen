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
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.MusicFxDialog;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.ui.dialog.ParametricEqDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * 音乐设置页（音乐组件自己的设置，不进星辰全局设置）。
 * 顶栏只有标题「设置」，无返回箭头，走系统返回；浅色皮跟音乐页一致。
 * 第 4 项尾巴：按 GZ 12 分类重排，只放适用项——9 类有适用项（播放设置／音质选择／均衡器／
 * 主题与外观／播放器视觉／歌词显示／下载设置／系统媒体与设备／存储与诊断），
 * 3 类无适用项不建分区（曲库与扫描／搜索与通用／底栏与黑胶，原因见布局注释与对照表）。
 * 星辰自有六项底线全部保留：默认播放音质（音质选择）、播放失败自动跳下一首（播放设置）、
 * 移动网络播放开关（播放设置）、下载音质（下载设置）、歌词缺失自动去别的插件找（歌词显示）、
 * 清音乐缓存（存储与诊断）。需登录的功能（云同步、插件同步等）一律排除；
 * 需系统能力／不适用项（桌面歌词、词幕＋LSPosed 等）不搬。
 * 新增行全部真接线已有偏好／对话框，不造假开关。
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
        refreshMobileQualityText();
        binding.rowMobileQuality.setOnClickListener(v -> showMobileQualityDialog());
        refreshDlNamingText();
        binding.rowDlNaming.setOnClickListener(v -> showDlNamingDialog());
        initSwitch(binding.swWifiOnly, MusicStore.isWifiOnlyDownload(), on -> {
            MusicStore.setWifiOnlyDownload(on);
            if (on) MusicDownloadStore.pauseRunningIfCellular();
        });
        binding.rowWifiOnly.setOnClickListener(v -> binding.swWifiOnly.toggle());
        // S1：播放失败行由开关改两档单选（停止播放／自动下一首；存值沿用 isAutoSkipOnError，行为不变）
        refreshAutoSkipText();
        binding.rowAutoSkip.setOnClickListener(v -> showAutoSkipDialog());
        initSwitch(binding.swMobilePlay, MusicStore.isMobilePlay(), MusicStore::setMobilePlay);
        binding.rowMobilePlay.setOnClickListener(v -> binding.swMobilePlay.toggle());
        // 瓜子后台播放／预加载下一首：切歌前先取好下一首链（服务侧读偏好，默认开）
        initSwitch(binding.swPreload, MusicStore.isPreloadNext(), MusicStore::setPreloadNext);
        binding.rowPreload.setOnClickListener(v -> binding.swPreload.toggle());
        // 瓜子划掉后台停止播放：从后台划掉 App 即停播（服务侧 onTaskRemoved 读偏好，默认关＝现行行为）
        initSwitch(binding.swStopOnSwipe, MusicStore.isStopOnTaskRemoved(), MusicStore::setStopOnTaskRemoved);
        binding.rowStopOnSwipe.setOnClickListener(v -> binding.swStopOnSwipe.toggle());
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
        // 第 4 项尾巴新增行（全部真接线已有偏好／对话框）
        refreshPlayModeText();
        binding.rowPlayMode.setOnClickListener(v -> showPlayModeDialog());
        refreshSpeedText();
        binding.rowSpeed.setOnClickListener(v -> showSpeedDialog());
        binding.rowEqSystem.setOnClickListener(v -> MusicFxDialog.show(this));
        binding.rowEqParam.setOnClickListener(v -> ParametricEqDialog.show(this));
        refreshThemeText();
        binding.rowPlayerTheme.setOnClickListener(v -> showThemeDialog());
        refreshCdSurfaceText();
        binding.rowCdSurface.setOnClickListener(v -> showCdSurfaceDialog());
        initSwitch(binding.swFluidBg, MusicStore.isFluidBg(), MusicStore::setFluidBg);
        binding.rowFluidBg.setOnClickListener(v -> binding.swFluidBg.toggle());
        initSwitch(binding.swKeepScreen, MusicStore.isPlayerKeepScreenOn(), MusicStore::setPlayerKeepScreenOn);
        binding.rowKeepScreen.setOnClickListener(v -> binding.swKeepScreen.toggle());
        initSwitch(binding.swLeftHand, MusicStore.isLeftHandLandscape(), MusicStore::setLeftHandLandscape);
        binding.rowLeftHand.setOnClickListener(v -> binding.swLeftHand.toggle());
        refreshLyricFontSizeText();
        binding.rowLyricFontSize.setOnClickListener(v -> showFontSizeDialog());
        refreshLyricWeightText();
        binding.rowLyricWeight.setOnClickListener(v -> showLyricWeightDialog());
        refreshLyricAlignText();
        binding.rowLyricAlign.setOnClickListener(v -> showLyricAlignDialog());
        refreshLyricBlurText();
        binding.rowLyricBlur.setOnClickListener(v -> showLyricBlurDialog());
        refreshLyricPrimaryText();
        binding.rowLyricPrimary.setOnClickListener(v -> showLyricPrimaryDialog());
        initSwitch(binding.swShowTranslation, MusicStore.isShowTranslation(), MusicStore::setShowTranslation);
        binding.rowShowTranslation.setOnClickListener(v -> binding.swShowTranslation.toggle());
        initSwitch(binding.swShowRomanization, MusicStore.isShowRomanization(), MusicStore::setShowRomanization);
        binding.rowShowRomanization.setOnClickListener(v -> binding.swShowRomanization.toggle());
        // 瓜子歌词过滤：作词／作曲／编曲等制作信息行不进歌词区（歌词视图 setLines 读偏好，默认关＝现行行为）
        initSwitch(binding.swFilterMeta, MusicStore.isFilterLyricMeta(), MusicStore::setFilterLyricMeta);
        binding.rowFilterMeta.setOnClickListener(v -> binding.swFilterMeta.toggle());
        initSwitch(binding.swAdvancedFx, MusicStore.isAdvancedFx(), MusicStore::setAdvancedFx);
        binding.rowAdvancedFx.setOnClickListener(v -> binding.swAdvancedFx.toggle());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统电池优化设置返回后，副文二态按实读刷新；其余值行也刷新（工具面板可能改过同一偏好）
        refreshBatteryText();
        refreshPlayModeText();
        refreshSpeedText();
        refreshThemeText();
        refreshCdSurfaceText();
        refreshMobileQualityText();
        refreshDlNamingText();
        refreshLyricFontSizeText();
        refreshLyricWeightText();
        refreshLyricAlignText();
        refreshLyricBlurText();
        refreshLyricPrimaryText();
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

    // ================= 第 4 项尾巴新增行 =================

    /** 播放模式行：顺序／列表循环／单曲循环／随机（瓜子 PlayMode 顺序与叫法；存 MusicStore.K_MODE，播控读它作默认） */
    private void refreshPlayModeText() {
        int m = MusicStore.getMode();
        binding.tvPlayMode.setText(m == MusicQueue.MODE_REPEAT_ONE ? "单曲循环"
                : m == MusicQueue.MODE_REPEAT_ALL ? "列表循环"
                : m == MusicQueue.MODE_SHUFFLE ? "随机播放" : "顺序播放");
    }

    private void showPlayModeDialog() {
        int[] values = {MusicQueue.MODE_SEQUENTIAL, MusicQueue.MODE_REPEAT_ALL,
                MusicQueue.MODE_REPEAT_ONE, MusicQueue.MODE_SHUFFLE};
        List<String> opts = new ArrayList<>();
        opts.add("顺序播放");
        opts.add("列表循环");
        opts.add("单曲循环");
        opts.add("随机播放");
        int cur = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == MusicStore.getMode()) cur = i;
        MusicOptionsDialog.show(this, "播放模式", opts, cur, idx -> {
            MusicStore.setMode(values[idx]);
            try {
                MusicController.get().setMode(values[idx]);
            } catch (Throwable ignored) {
            }
            refreshPlayModeText();
        });
    }

    /** 倍速行：档位与工具面板同款 {0.5, 0.75, 1, 1.25, 1.5, 2}，写播控＋偏好双份（同值，防单边） */
    private void refreshSpeedText() {
        binding.tvSpeed.setText(speedLabel(MusicController.get().getSpeed()));
    }

    private static String speedLabel(float s) {
        return (s == Math.floor(s) ? String.format("%.1f", s) : String.valueOf(s)) + "×";
    }

    private void showSpeedDialog() {
        float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        List<String> opts = new ArrayList<>();
        int checked = 2;
        float cur = MusicController.get().getSpeed();
        for (int i = 0; i < speeds.length; i++) {
            opts.add(speedLabel(speeds[i]));
            if (Math.abs(speeds[i] - cur) < 0.01f) checked = i;
        }
        MusicOptionsDialog.show(this, getString(R.string.music_speed), opts, checked, idx -> {
            try {
                MusicController.get().setSpeed(speeds[idx]);
            } catch (Throwable ignored) {
            }
            MusicStore.setSpeed(speeds[idx]);
            refreshSpeedText();
        });
    }

    /** 播放页主题行：五主题（默认封面卡片不改）；主题名用现有 music_theme_* 文案 */
    private void refreshThemeText() {
        String t = MusicStore.getPlayerTheme();
        binding.tvPlayerTheme.setText(MusicStore.THEME_CLASSIC.equals(t) ? R.string.music_theme_classic
                : MusicStore.THEME_APPLE_MUSIC.equals(t) ? R.string.music_theme_apple
                : MusicStore.THEME_LUMINOUS_CD.equals(t) ? R.string.music_theme_cd
                : MusicStore.THEME_RECORD_SHELF.equals(t) ? R.string.music_theme_vinyl
                : R.string.music_theme_cover);
    }

    private void showThemeDialog() {
        String[] ids = {MusicStore.THEME_CLASSIC, MusicStore.THEME_APPLE_MUSIC,
                MusicStore.THEME_COVER_IMMERSIVE, MusicStore.THEME_LUMINOUS_CD,
                MusicStore.THEME_RECORD_SHELF};
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.music_theme_classic));
        opts.add(getString(R.string.music_theme_apple));
        opts.add(getString(R.string.music_theme_cover));
        opts.add(getString(R.string.music_theme_cd));
        opts.add(getString(R.string.music_theme_vinyl));
        int cur = 2;
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(MusicStore.getPlayerTheme())) cur = i;
        MusicOptionsDialog.show(this, "播放页主题", opts, cur, idx -> {
            MusicStore.setPlayerTheme(ids[idx]);
            refreshThemeText();
        });
    }

    /** 流光 CD 盘面行：0 浅 1 深 2 自动（默认自动；播放页 applyCdPalette 读） */
    private void refreshCdSurfaceText() {
        int s = MusicStore.getCdSurface();
        binding.tvCdSurface.setText(s == MusicStore.CD_SURFACE_LIGHT ? "浅色"
                : s == MusicStore.CD_SURFACE_DARK ? "深色" : "自动");
    }

    private void showCdSurfaceDialog() {
        int[] values = {MusicStore.CD_SURFACE_LIGHT, MusicStore.CD_SURFACE_DARK, MusicStore.CD_SURFACE_AUTO};
        List<String> opts = new ArrayList<>();
        opts.add("浅色");
        opts.add("深色");
        opts.add("自动");
        int cur = 2;
        for (int i = 0; i < values.length; i++) if (values[i] == MusicStore.getCdSurface()) cur = i;
        MusicOptionsDialog.show(this, "流光 CD 盘面", opts, cur, idx -> {
            MusicStore.setCdSurface(values[idx]);
            refreshCdSurfaceText();
        });
    }

    /** 歌词字号行：步进编辑框（±2sp，范围由 setLyricFontSize 钳制 16–40，默认 24sp） */
    private void refreshLyricFontSizeText() {
        binding.tvLyricFontSize.setText(((int) MusicStore.getLyricFontSize()) + "sp");
    }

    private void showFontSizeDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        TextView value = new TextView(this);
        value.setTextSize(22);
        value.setTextColor(0xFFFFFFFF);
        value.setGravity(Gravity.CENTER);
        value.setText(((int) MusicStore.getLyricFontSize()) + "sp");
        box.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = pad;
        Button minus = new Button(this);
        minus.setText("−2sp");
        Button plus = new Button(this);
        plus.setText("+2sp");
        Runnable apply = () -> {
            value.setText(((int) MusicStore.getLyricFontSize()) + "sp");
            refreshLyricFontSizeText();
        };
        minus.setOnClickListener(v -> {
            MusicStore.setLyricFontSize(MusicStore.getLyricFontSize() - 2);
            apply.run();
        });
        plus.setOnClickListener(v -> {
            MusicStore.setLyricFontSize(MusicStore.getLyricFontSize() + 2);
            apply.run();
        });
        row.addView(minus);
        row.addView(plus);
        box.addView(row, rowLp);
        Button reset = new Button(this);
        reset.setText("恢复 24sp");
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetLp.topMargin = pad / 2;
        reset.setOnClickListener(v -> {
            MusicStore.setLyricFontSize(24);
            apply.run();
        });
        box.addView(reset, resetLp);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("歌词字号")
                .setView(box)
                .setPositiveButton("完成", null)
                .show();
    }

    /** 歌词粗细行：0 标准 1 加粗 2 特粗（默认标准） */
    private void refreshLyricWeightText() {
        int w = MusicStore.getLyricWeight();
        binding.tvLyricWeight.setText(w == 2 ? "特粗" : w == 1 ? "加粗" : "标准");
    }

    private void showLyricWeightDialog() {
        int[] values = {0, 1, 2};
        List<String> opts = new ArrayList<>();
        opts.add("标准");
        opts.add("加粗");
        opts.add("特粗");
        MusicOptionsDialog.show(this, "歌词粗细", opts, MusicStore.getLyricWeight(), idx -> {
            MusicStore.setLyricWeight(values[idx]);
            refreshLyricWeightText();
        });
    }

    /** 歌词对齐行：0 左 1 中 2 右（默认左） */
    private void refreshLyricAlignText() {
        int a = MusicStore.getLyricAlign();
        binding.tvLyricAlign.setText(a == 2 ? "靠右" : a == 1 ? "居中" : "靠左");
    }

    private void showLyricAlignDialog() {
        int[] values = {0, 1, 2};
        List<String> opts = new ArrayList<>();
        opts.add("靠左");
        opts.add("居中");
        opts.add("靠右");
        MusicOptionsDialog.show(this, "歌词对齐", opts, MusicStore.getLyricAlign(), idx -> {
            MusicStore.setLyricAlign(values[idx]);
            refreshLyricAlignText();
        });
    }

    /** 非当前行模糊行：0 关闭 1 轻度 2 中度 */
    private void refreshLyricBlurText() {
        int b = MusicStore.getLyricBlur();
        binding.tvLyricBlur.setText(b == 0 ? "关闭" : b == 2 ? "中度" : "轻度");
    }

    private void showLyricBlurDialog() {
        int[] values = {0, 1, 2};
        List<String> opts = new ArrayList<>();
        opts.add("关闭");
        opts.add("轻度");
        opts.add("中度");
        MusicOptionsDialog.show(this, "非当前行模糊", opts, MusicStore.getLyricBlur(), idx -> {
            MusicStore.setLyricBlur(values[idx]);
            refreshLyricBlurText();
        });
    }

    /** 主歌词行：0 原文 1 翻译 2 音译（默认原文） */
    private void refreshLyricPrimaryText() {
        int p = MusicStore.getLyricPrimary();
        binding.tvLyricPrimary.setText(p == 2 ? "音译" : p == 1 ? "翻译" : "原文");
    }

    private void showLyricPrimaryDialog() {
        int[] values = {0, 1, 2};
        List<String> opts = new ArrayList<>();
        opts.add("原文");
        opts.add("翻译");
        opts.add("音译");
        MusicOptionsDialog.show(this, "主歌词", opts, MusicStore.getLyricPrimary(), idx -> {
            MusicStore.setLyricPrimary(values[idx]);
            refreshLyricPrimaryText();
        });
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

    /** 移动网络音质行：跟随播放音质（默认）或固定一档；移动网络下 prepare 时读它（服务侧） */
    private void refreshMobileQualityText() {
        int q = MusicStore.getMobileQuality();
        binding.tvMobileQuality.setText(q < 0 ? "跟随播放音质" : MusicStore.qualityName(q));
    }

    private void showMobileQualityDialog() {
        int[] values = {-1, MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD,
                MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
        List<String> opts = new ArrayList<>();
        opts.add("跟随播放音质");
        for (int i = 1; i < values.length; i++) opts.add(MusicStore.qualityName(values[i]));
        int q = MusicStore.getMobileQuality();
        int cur = q < 0 ? 0 : q + 1;
        MusicOptionsDialog.show(this, "移动网络音质", opts, cur, idx -> {
            MusicStore.setMobileQuality(values[idx]);
            refreshMobileQualityText();
        });
    }

    /** 下载命名行：瓜子三档（歌手 - 歌名／歌名 - 歌手／歌名），默认歌名 - 歌手＝现行行为 */
    private void refreshDlNamingText() {
        int n = MusicStore.getDlNaming();
        binding.tvDlNaming.setText(n == MusicStore.DL_NAMING_ARTIST_TITLE ? "歌手 - 歌名"
                : n == MusicStore.DL_NAMING_TITLE ? "歌名" : "歌名 - 歌手");
    }

    private void showDlNamingDialog() {
        int[] values = {MusicStore.DL_NAMING_ARTIST_TITLE, MusicStore.DL_NAMING_TITLE_ARTIST,
                MusicStore.DL_NAMING_TITLE};
        List<String> opts = new ArrayList<>();
        opts.add("歌手 - 歌名");
        opts.add("歌名 - 歌手");
        opts.add("歌名");
        MusicOptionsDialog.show(this, "下载命名", opts, MusicStore.getDlNaming(), idx -> {
            MusicStore.setDlNaming(values[idx]);
            refreshDlNamingText();
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
