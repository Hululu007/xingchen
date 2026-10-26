package com.fongmi.android.tv.ui.activity;

import com.fongmi.android.tv.ui.music.MusicGlassWire;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
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
import com.fongmi.android.tv.ui.music.MusicGlassDialogBuilder;
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
 * 批 6（2026-10-24）：背景收口全域玻璃（布局侧）；主题行与流光 CD 盘面行改走
 * MusicPlayerSettings 七 id（旧 MusicStore 五 id 路会把 CD 深／自动写成 classic）；
 * 摘除左手布局／过滤词曲信息两行假开关（全树无读取方，证据见布局头注）。
 */
public class MusicSettingsActivity extends BaseActivity {

    private ActivityMusicSettingsBinding binding;
    /** 在线歌词优先级行右侧值（动态插行生成，布局文件未动时由 insertLyricPriorityRow 赋值） */
    private TextView tvLyricPriority;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, MusicSettingsActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        // 只 inflate 一次：BaseActivity 先取根 setContentView、initView 再取根走 glassifyTree，
        // 两次必须是同一棵树——重复 inflate 会让接线落在游离树上、显示的那棵永不生效。
        if (binding == null) binding = ActivityMusicSettingsBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        MusicGlassWire.glassifyTree(getBinding().getRoot()); // 全页玻璃件同源接线：共用件按资源识别统一换液态玻璃（MusicGlassWire）
        initQualityRows();
        // 第三波 S-A1：单独设置移动网络音质开关＋子行两段式（服务侧 effectivePlayQuality 逻辑不动：
        // 开关状态就是存值本身——开＝存固定档、关＝存 -1 跟随；关时先记下固定档以便再开恢复）。
        initSwitch(binding.swMobileQualitySeparate, MusicStore.getMobileQuality() >= 0, on -> {
            boolean separate = MusicStore.getMobileQuality() >= 0;
            if (on == separate) return;
            if (on) {
                MusicStore.setMobileQuality(MusicStore.getMobileQualityLastFixed());
            } else {
                int cur = MusicStore.getMobileQuality();
                if (cur >= 0) MusicStore.setMobileQualityLastFixed(cur);
                MusicStore.setMobileQuality(-1);
            }
            refreshMobileQualityText();
        });
        binding.rowMobileQualitySwitch.setOnClickListener(v -> binding.swMobileQualitySeparate.toggle());
        refreshMobileQualityText();
        binding.rowMobileQuality.setOnClickListener(v -> showMobileQualityDialog());
        refreshDlNamingText();
        binding.rowDlNaming.setOnClickListener(v -> showDlNamingDialog());
        initSwitch(binding.swWifiOnly, MusicStore.isWifiOnlyDownload(), on -> {
            MusicStore.setWifiOnlyDownload(on);
            if (on) MusicDownloadStore.pauseRunningIfCellular();
        });
        binding.rowWifiOnly.setOnClickListener(v -> binding.swWifiOnly.toggle());
        // 第三波 S-A3：封面与歌词／写入失败策略行（新任务生效，已下载文件不动；内嵌档的
        // 内嵌部分未接线，见 MusicStore 注释与 MusicDownloadStore.downloadSidecars）。
        refreshDlEmbedText();
        binding.rowDlEmbed.setOnClickListener(v -> showDlEmbedDialog());
        refreshDlEmbedFailText();
        binding.rowDlEmbedFail.setOnClickListener(v -> showDlEmbedFailDialog());
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
        // 后台播放行已摘（2026-10-26 他令：审计出的假开关整行摘除）：开关写了服务侧没人读、
        // 关了照样后台播；摘后音乐后台常驻维持现状（本就是默认口径），music_background 键
        // 的读写（PlayerSetting 四个方法）已一并清掉，不留死键。
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
        // 在线歌词优先级行：插在 AMLL 行之后（歌词区内），一行＋当前值＋选项弹窗，模式同主歌词行。
        // 本批只许改四个 Java 文件、布局文件不动，故用代码插行（样式值照 MusicSettingRow／
        // MusicSettingName／MusicSettingValue 手写对齐）；布局里补正式行时须删掉本动态插行，免得重行。
        insertLyricPriorityRow();
        refreshLyricPriorityText();
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
        // 批 6：左手布局行已摘（music_left_hand_landscape 全树无读取方，是假开关；键与存量值保留未删）
        refreshLyricFontSizeText();
        binding.rowLyricFontSize.setOnClickListener(v -> showFontSizeDialog());
        refreshLyricWeightText();
        binding.rowLyricWeight.setOnClickListener(v -> showLyricWeightDialog());
        refreshLyricAlignText();
        binding.rowLyricAlign.setOnClickListener(v -> showLyricAlignDialog());
        refreshLyricPrimaryText();
        binding.rowLyricPrimary.setOnClickListener(v -> showLyricPrimaryDialog());
        initSwitch(binding.swShowTranslation, MusicStore.isShowTranslation(), MusicStore::setShowTranslation);
        binding.rowShowTranslation.setOnClickListener(v -> binding.swShowTranslation.toggle());
        initSwitch(binding.swShowRomanization, MusicStore.isShowRomanization(), MusicStore::setShowRomanization);
        binding.rowShowRomanization.setOnClickListener(v -> binding.swShowRomanization.toggle());
        // 批 6：过滤词曲信息行已摘（music_filter_lyric_meta 全树无读取方：歌词解析链的元数据
        // 过滤是无条件执行、与此键无关，开关改不了行为＝假开关；键与存量值保留未删）
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
        refreshDlEmbedText();
        refreshDlEmbedFailText();
        refreshDlNamingText();
        refreshLyricFontSizeText();
        refreshLyricWeightText();
        refreshLyricAlignText();
        refreshLyricPrimaryText();
        refreshLyricPriorityText();
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
     * 范围钳制 ±3000ms、步进 50ms（瓜子真值，smali 深挖 §15.1 第 4 项；存值走 MusicStore.setLyricOffsetMs 的钳制）。
     * 本批只做行＋编辑框＋存值；偏移真正作用到歌词时间由歌词路接同一键名，不在这里改。
     */
    private void showOffsetDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        TextView value = new TextView(this);
        value.setTextSize(22);
        value.setTextColor(0xFF1C1C1E);
        value.setGravity(Gravity.CENTER);
        value.setText(offsetText(MusicStore.getLyricOffsetMs()));
        box.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView hint = new TextView(this);
        hint.setText("正值＝歌词提前，负值＝歌词延后");
        hint.setTextSize(13);
        hint.setTextColor(0xFF8E8E93);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = pad / 2;
        box.addView(hint, hintLp);
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
        new MusicGlassDialogBuilder(this)
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

    /** 批 6 迁移：播放页主题行改走 MusicPlayerSettings（七 id，与批 4 工具面板同一条路）。
     * 旧路 MusicStore.getPlayerTheme/setPlayerTheme 只认五 id：读到 luminous_cd_dark/auto
     * 会回落 classic 显示、写回更会把 CD 深／自动静默写成 classic（批 4 已在面板侧堵过同坑）。
     * 同键 music_player_theme、同存量值，迁移无数据搬家问题。 */
    private void refreshThemeText() {
        String t = com.fongmi.android.tv.ui.music.MusicPlayerSettings.getTheme();
        binding.tvPlayerTheme.setText(com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_CLASSIC.equals(t) ? R.string.music_theme_classic
                : com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_COVER_IMMERSIVE.equals(t) ? R.string.music_theme_cover
                : com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_APPLE_MUSIC.equals(t) ? R.string.music_theme_apple
                : com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_LUMINOUS_CD.equals(t) ? R.string.music_theme_cd
                : com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_LUMINOUS_CD_DARK.equals(t) ? R.string.music_theme_cd_dark
                : com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO.equals(t) ? R.string.music_theme_cd_auto
                : com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_RECORD_SHELF.equals(t) ? R.string.music_theme_vinyl
                : R.string.music_theme_cover);
    }

    private void showThemeDialog() {
        // 瓜子序＋CD 三 id 独立成项（§4 三主题 id 模型）：Classic／CoverImmersive／AppleMusic／
        // LuminousCd／LuminousCd 深／LuminousCd 自动／RecordShelf。
        String[] ids = {com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_CLASSIC,
                com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_COVER_IMMERSIVE,
                com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_APPLE_MUSIC,
                com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_LUMINOUS_CD,
                com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_LUMINOUS_CD_DARK,
                com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO,
                com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_RECORD_SHELF};
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.music_theme_classic));
        opts.add(getString(R.string.music_theme_cover));
        opts.add(getString(R.string.music_theme_apple));
        opts.add(getString(R.string.music_theme_cd));
        opts.add(getString(R.string.music_theme_cd_dark));
        opts.add(getString(R.string.music_theme_cd_auto));
        opts.add(getString(R.string.music_theme_vinyl));
        int cur = 1;
        String theme = com.fongmi.android.tv.ui.music.MusicPlayerSettings.getTheme();
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(theme)) cur = i;
        MusicOptionsDialog.show(this, "播放页主题", opts, cur, idx -> {
            com.fongmi.android.tv.ui.music.MusicPlayerSettings.setTheme(ids[idx]);
            refreshThemeText();
            refreshCdSurfaceText();
        });
    }

    /** 批 6 接线：流光 CD 盘面行改走主题 id（播放页只认主题 id 推导表面，旧键 music_cd_surface
     * 无任何播放侧读取方——旧行是写了没人读的假选项）。选表面＝切到对应 CD 主题变体；
     * 当前主题不是 CD 时右侧值显「未启用」，选任一档即切到该 CD 变体（与瓜子三 id 模型一致）。
     * 旧表面键 music_cd_surface 已整组摘除（扫查待裁 25：只写不读的纯残留，主题 id 是唯一真状态）。 */
    private void refreshCdSurfaceText() {
        String t = com.fongmi.android.tv.ui.music.MusicPlayerSettings.getTheme();
        if (com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_LUMINOUS_CD.equals(t)) {
            binding.tvCdSurface.setText("浅色");
        } else if (com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_LUMINOUS_CD_DARK.equals(t)) {
            binding.tvCdSurface.setText("深色");
        } else if (com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO.equals(t)) {
            binding.tvCdSurface.setText("自动");
        } else {
            binding.tvCdSurface.setText("未启用");
        }
    }

    private void showCdSurfaceDialog() {
        String[] ids = {com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_LUMINOUS_CD,
                com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_LUMINOUS_CD_DARK,
                com.fongmi.android.tv.ui.music.MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO};
        List<String> opts = new ArrayList<>();
        opts.add("浅色");
        opts.add("深色");
        opts.add("自动");
        int cur = 2;
        String theme = com.fongmi.android.tv.ui.music.MusicPlayerSettings.getTheme();
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(theme)) cur = i;
        MusicOptionsDialog.show(this, "流光 CD 盘面", opts, cur, idx -> {
            com.fongmi.android.tv.ui.music.MusicPlayerSettings.setTheme(ids[idx]);
            refreshCdSurfaceText();
            refreshThemeText();
        });
    }

    /** 歌词字号行：步进编辑框（±2sp，与渲染器同一正本键 music_lyric_font_size_cover，
     * 由 setCoverLyricFontSize 钳制 14–32，默认 22sp；与播放页工具面板同键互通） */
    private void refreshLyricFontSizeText() {
        binding.tvLyricFontSize.setText(((int) MusicStore.getCoverLyricFontSize()) + "sp");
    }

    private void showFontSizeDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        TextView value = new TextView(this);
        value.setTextSize(22);
        value.setTextColor(0xFF1C1C1E);
        value.setGravity(Gravity.CENTER);
        value.setText(((int) MusicStore.getCoverLyricFontSize()) + "sp");
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
            value.setText(((int) MusicStore.getCoverLyricFontSize()) + "sp");
            refreshLyricFontSizeText();
        };
        minus.setOnClickListener(v -> {
            MusicStore.setCoverLyricFontSize(MusicStore.getCoverLyricFontSize() - 2);
            apply.run();
        });
        plus.setOnClickListener(v -> {
            MusicStore.setCoverLyricFontSize(MusicStore.getCoverLyricFontSize() + 2);
            apply.run();
        });
        row.addView(minus);
        row.addView(plus);
        box.addView(row, rowLp);
        Button reset = new Button(this);
        reset.setText("恢复 22sp");
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetLp.topMargin = pad / 2;
        reset.setOnClickListener(v -> {
            MusicStore.setCoverLyricFontSize(22f);
            apply.run();
        });
        box.addView(reset, resetLp);
        new MusicGlassDialogBuilder(this)
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

    /** 在线歌词优先级行右侧值：跟随来源／逐字优先（默认＝老行为）／逐行优先／AMLL 优先 */
    private void refreshLyricPriorityText() {
        if (tvLyricPriority == null) return;
        String p = MusicStore.getLyricPriority();
        if (MusicStore.LYRIC_PRIORITY_FOLLOW_SOURCE.equals(p)) tvLyricPriority.setText("跟随来源");
        else if (MusicStore.LYRIC_PRIORITY_LINE_BY_LINE.equals(p)) tvLyricPriority.setText("逐行优先");
        else if (MusicStore.LYRIC_PRIORITY_AMLL_FIRST.equals(p)) tvLyricPriority.setText("AMLL 优先");
        else tvLyricPriority.setText("逐字优先");
    }

    private void showLyricPriorityDialog() {
        String[] values = {MusicStore.LYRIC_PRIORITY_FOLLOW_SOURCE, MusicStore.LYRIC_PRIORITY_WORD_BY_WORD,
                MusicStore.LYRIC_PRIORITY_LINE_BY_LINE, MusicStore.LYRIC_PRIORITY_AMLL_FIRST};
        List<String> opts = new ArrayList<>();
        opts.add("跟随来源");
        opts.add("逐字优先");
        opts.add("逐行优先");
        opts.add("AMLL 优先");
        int checked = 1;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(MusicStore.getLyricPriority())) checked = i;
        }
        MusicOptionsDialog.show(this, "在线歌词优先级", opts, checked, idx -> {
            MusicStore.setLyricPriority(values[idx]);
            refreshLyricPriorityText();
        });
    }

    /** 动态插“在线歌词优先级”行：插在 AMLL 行与其后分隔线之间（行＋分隔线各一）。 */
    private void insertLyricPriorityRow() {
        if (tvLyricPriority != null) return;
        try {
            if (binding.rowAmll.getParent() == null || !(binding.rowAmll.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) binding.rowAmll.getParent();
            int index = parent.indexOfChild(binding.rowAmll);
            if (index < 0) return;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(60));
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ImageView icon = new ImageView(this);
            icon.setBackgroundResource(R.drawable.bg_music_setting_icon);
            icon.setBackgroundTintList(ColorStateList.valueOf(0xFFCE93D8));
            icon.setImageResource(R.drawable.ic_music_note);
            icon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            icon.setPadding(dp(8), dp(8), dp(8), dp(8));
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(36), dp(36));
            iconLp.setMarginEnd(dp(12));
            icon.setLayoutParams(iconLp);
            row.addView(icon);
            TextView name = new TextView(this);
            name.setText("在线歌词优先级");
            name.setTextColor(0xFFF1F4F5);
            name.setTextSize(16);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            name.setLayoutParams(nameLp);
            row.addView(name);
            tvLyricPriority = new TextView(this);
            tvLyricPriority.setTextColor(0x99FFFFFF);
            tvLyricPriority.setTextSize(15);
            LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            valueLp.setMarginStart(dp(16));
            tvLyricPriority.setLayoutParams(valueLp);
            row.addView(tvLyricPriority);
            row.setOnClickListener(v -> showLyricPriorityDialog());
            android.view.View divider = new android.view.View(this);
            divider.setBackgroundColor(0x0F000000);
            LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
            dividerLp.setMarginStart(dp(16));
            dividerLp.setMarginEnd(dp(16));
            divider.setLayoutParams(dividerLp);
            // 先插分隔线、再插行：最终顺序＝AMLL 行／新分隔线／新行／原分隔线／歌词时间偏移行
            parent.addView(divider, index + 1);
            parent.addView(row, index + 2);
        } catch (Exception ignored) {
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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

    /** 移动网络音质行：开关关时子行隐藏（开关本身即“跟随”态）；开时子行显固定档名。
     * 开关勾选与存值一致时 setChecked 不产生副作用（监听里先比对现态，早退）。 */
    private void refreshMobileQualityText() {
        int q = MusicStore.getMobileQuality();
        boolean separate = q >= 0;
        binding.rowMobileQuality.setVisibility(separate ? android.view.View.VISIBLE : android.view.View.GONE);
        binding.tvMobileQuality.setText(separate ? MusicStore.qualityName(q) : "跟随播放音质");
        if (binding.swMobileQualitySeparate.isChecked() != separate) {
            binding.swMobileQualitySeparate.setChecked(separate);
        }
    }

    /** 子行对话框：只列四档固定档（跟随由开关管，不再在对话框里二合一）。 */
    private void showMobileQualityDialog() {
        int[] values = {MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD,
                MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
        List<String> opts = new ArrayList<>();
        for (int v : values) opts.add(MusicStore.qualityName(v));
        int q = MusicStore.getMobileQuality();
        int cur = q >= 0 && q < values.length ? q : 0;
        MusicOptionsDialog.show(this, "移动网络音质", opts, cur, idx -> {
            MusicStore.setMobileQuality(values[idx]);
            refreshMobileQualityText();
        });
    }

    /** 第三波 S-A3：封面与歌词行值（内嵌档后附“未接线”不冒充已实现，见 MusicStore 注释）。 */
    private void refreshDlEmbedText() {
        int m = MusicStore.getDlEmbed();
        binding.tvDlEmbed.setText(m == MusicStore.DL_EMBED_EMBEDDED ? "内嵌到音频文件（未接线）"
                : m == MusicStore.DL_EMBED_BOTH ? "两者（内嵌未接线）" : "旁路同名文件");
    }

    private void showDlEmbedDialog() {
        List<String> opts = new ArrayList<>();
        opts.add("旁路同名文件");
        opts.add("内嵌到音频文件");
        opts.add("两者");
        MusicOptionsDialog.show(this, "封面与歌词", opts, MusicStore.getDlEmbed(), idx -> {
            MusicStore.setDlEmbed(idx);
            refreshDlEmbedText();
        });
    }

    private void refreshDlEmbedFailText() {
        binding.tvDlEmbedFail.setText(MusicStore.getDlEmbedFail() == MusicStore.DL_EMBED_FAIL_URL
                ? "写入 URL 字段" : "跳过不写");
    }

    private void showDlEmbedFailDialog() {
        List<String> opts = new ArrayList<>();
        opts.add("跳过不写");
        opts.add("写入 URL 字段");
        MusicOptionsDialog.show(this, "写入失败策略", opts, MusicStore.getDlEmbedFail(), idx -> {
            MusicStore.setDlEmbedFail(idx);
            refreshDlEmbedFailText();
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

    /** 清音乐缓存（低-02 修后）：只清音乐自有歌词缓存（内存＋磁盘，见 MusicCache 头注），
     * 不动全 App 共用的 Glide 图片缓存，不会误清视频封面；行名、副文与完成提示已同步改成音乐缓存口径。 */
    private void clearCache() {
        MusicCache.clear(() -> {
            Notify.show(R.string.music_cache_cleared);
            refreshCacheSize();
        });
    }
}
