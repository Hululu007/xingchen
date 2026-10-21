package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.DialogMusicToolsBinding;
import com.fongmi.android.tv.music.DeviceMusicSource;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.PyMusicSource;
import com.fongmi.android.tv.service.MusicPlaybackService;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 音乐底部工具面板（浅色，照 GZ 本体转写）：主面板＝信息头＋快捷格＋模式段控（一张白卡四段）
 * ＋系统音量卡＋设置卡（分组白卡、行内「名字左/值右」同一行），
 * ＋三子面板（播放页主题/定时/歌词设置），同 Dialog 内换内容区切换。
 * 令牌照 GZ 参数表第 7 节浅色套（SheetTokensKt）：sheetBg 白、sheetPanelBg #F3F3F3、卡面白、
 * 主字 #1C1C1E、次字 #9A9AA0、中字 #8E8E93、分割线/描边黑 0.06、内嵌黑 0.04、拖动条黑 0.14；
 * 面板内边距 16dp 底 10dp（GlassSheetPanelKt）、区块间隔 20dp、子面板头 44dp/圆钮 40dp。
 * 颜色差异（用户口径）：GZ 的 AccentRed 品牌红不进星辰——模式选中态改中性深底白字、
 * 音量填充/滑杆/开关选中态改深色填充（#1C1C1E），结构与尺寸仍照 GZ。
 */
public class MusicToolsSheetDialog extends BaseBottomSheetDialog {

    public interface Listener {
        void onThemeChanged();

        void onLyricStyleChanged();

        void onSearchLyric();

        void onAddToPlaylist();

        void onDownload();
    }

    private static final int PANEL_MAIN = 0;
    private static final int PANEL_THEME = 1;
    private static final int PANEL_SLEEP = 2;
    private static final int PANEL_LYRIC = 3;

    // GZ SheetTokensKt 浅色套
    private static final int COLOR_PANEL = 0xFFF3F3F3;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_DARK = 0xFF1C1C1E;   // 仅余开关轨/恢复默认等无 GZ 色值证据处用
    private static final int COLOR_RED = 0xFFFA243C;    // GZ AccentRed（本体多处实证；上一版「中性深代红」口径无原话、已废止）
    private static final int COLOR_TEXT = 0xFF1C1C1E;
    private static final int COLOR_LOW = 0xFF9A9AA0;
    private static final int COLOR_MEDIUM = 0xFF8E8E93;
    private static final int COLOR_DIVIDER = 0x0F000000;
    private static final int COLOR_BORDER = 0x0F000000;

    private static final String[] THEME_IDS = {
            MusicStore.THEME_CLASSIC, MusicStore.THEME_APPLE_MUSIC, MusicStore.THEME_COVER_IMMERSIVE,
            MusicStore.THEME_LUMINOUS_CD, MusicStore.THEME_RECORD_SHELF};
    private static final int[] THEME_NAMES = {
            R.string.music_theme_classic, R.string.music_theme_apple, R.string.music_theme_cover,
            R.string.music_theme_cd, R.string.music_theme_vinyl};
    private static final int[][] THEME_COLORS = {
            {0xFF3A4A5F, 0xFF10151C},
            {0xFFF5F5F7, 0xFFD9D9DE},
            {0xFF7B4DFF, 0xFFFF8A3D},
            {0xFF59E3FF, 0xFFFF5DA2, 0xFFFFD35D},
            {0xFF4A3220, 0xFF14100C}};

    private DialogMusicToolsBinding binding;
    private Listener listener;
    private int currentPanel = PANEL_MAIN;
    private int initialPanel = PANEL_MAIN;

    // 音量卡：外部音量变化同步（ContentObserver）
    private ContentObserver volumeObserver;
    private SeekBar volumeBar;
    private TextView volumePct;
    private boolean volumeDragging;

    // 歌词设置子面板：当前行实时预览 + 四态提示
    private TextView lyricPreview;
    private TextView lyricSizeValue;
    private TextView lyricHint;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable lyricTicker;
    private final List<long[]> lyricTimes = new ArrayList<>();
    private final List<String> lyricTexts = new ArrayList<>();
    private boolean lyricLoaded;
    private boolean lyricHasTrans;
    private boolean lyricHasRoma;

    public static void show(Activity activity, Listener listener) {
        if (!(activity instanceof FragmentActivity)) return;
        MusicToolsSheetDialog d = new MusicToolsSheetDialog();
        d.listener = listener;
        d.show(((FragmentActivity) activity).getSupportFragmentManager(), "music_tools_sheet");
    }

    /** 直达歌词设置子面板（播放页歌词筹入口） */
    public static void showLyricPanel(Activity activity, Listener listener) {
        if (!(activity instanceof FragmentActivity)) return;
        MusicToolsSheetDialog d = new MusicToolsSheetDialog();
        d.listener = listener;
        d.initialPanel = PANEL_LYRIC;
        d.show(((FragmentActivity) activity).getSupportFragmentManager(), "music_tools_sheet");
    }

    // 内容自带浅色底（布局 bg_sheet_panel）：弹窗容器/窗口必须透明，
    // 否则 Material 底部弹窗默认的浅色层会从顶部漏出一条白带。
    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogMusicToolsBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        // 右缘防裁切：窗口宽强制 MATCH_PARENT，行值/开关完整可见
        try {
            if (getDialog() != null && getDialog().getWindow() != null) {
                getDialog().getWindow().setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {
        }
        showPanel(initialPanel);
    }

    @Override
    public void onDestroyView() {
        stopLyricTicker();
        unregisterVolumeObserver();
        super.onDestroyView();
    }

    // ================= 面板切换 =================

    private void showPanel(int panel) {
        if (binding == null) return;
        currentPanel = panel;
        stopLyricTicker();
        lyricPreview = null;
        lyricSizeValue = null;
        lyricHint = null;
        binding.container.removeAllViews();
        // 子面板切换过渡（GZ MotionKt.f/g：tween(260, bs4.a) 淡入）
        binding.container.setAlpha(0f);
        binding.container.animate().alpha(1f).setDuration(260)
                .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        switch (panel) {
            case PANEL_THEME:
                buildThemePanel();
                break;
            case PANEL_SLEEP:
                buildSleepPanel();
                break;
            case PANEL_LYRIC:
                buildLyricPanel();
                break;
            default:
                buildMainPanel();
                break;
        }
        binding.scroll.scrollTo(0, 0);
    }

    // ================= 主面板 =================

    private void buildMainPanel() {
        LinearLayout box = binding.container;
        box.addView(buildHero());
        box.addView(buildQuickGrid(), marginTop(dp(20)));
        box.addView(buildModeSegment(), marginTop(dp(20)));
        box.addView(buildVolumeCard(), marginTop(dp(20)));
        box.addView(buildSettingsCard(), marginTop(dp(20)));
    }

    /** ① 歌曲信息头（GZ SongInfoHero T01）：封面 48dp 圆角 8＋歌名 16sp＋歌手 12sp，面板底上裸行，右端关闭钮；跟随 MusicController 当前轨。
     * GZ 歌手行是「歌手 · 专辑」一并显示，星辰同形跟进（专辑为空时只显示歌手） */
    private View buildHero() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView cover = new ImageView(getContext());
        cover.setBackground(cardBg(0x1F8E8E93, 8));
        row.addView(cover, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("", 16, COLOR_TEXT, true);
        title.setLetterSpacing(-0.02f);
        TextView artist = text("", 12, COLOR_MEDIUM, false);
        artist.setPadding(0, dp(2), 0, 0);
        texts.addView(title);
        texts.addView(artist);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(12));
        row.addView(texts, tlp);
        ImageView close = new ImageView(getContext());
        close.setImageResource(R.drawable.ic_music_close);
        close.setImageTintList(ColorStateList.valueOf(COLOR_MEDIUM));
        close.setPadding(dp(8), dp(8), dp(8), dp(8));
        close.setContentDescription(getString(R.string.music_close));
        close.setOnClickListener(v -> dismissAllowingStateLoss());
        attachPress(close, 0.9f);
        row.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        MusicTrack track = MusicController.get().current();
        if (track == null) {
            title.setText(R.string.music_no_track);
        } else {
            title.setText(track.displayTitle());
            String artistLine = track.displayArtist();
            if (!TextUtils.isEmpty(track.album)) artistLine += " · " + track.album;
            artist.setText(artistLine);
            ImgUtil.loadRounded(track.displayTitle(), track.cover, cover);
        }
        return row;
    }

    /** ② 快捷格一排 4（GZ 快捷格行 T02–T05）：行高 84dp、白方格圆角 8dp＋下方标签、格间距 20dp */
    private View buildQuickGrid() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(quickCell(R.drawable.ic_music_download, R.string.music_download, v -> {
            // 下载对接宿主 downloadCurrent()：本地歌/已下载拦截后走 MusicDownloadStore.download
            if (listener != null) listener.onDownload();
            dismissAllowingStateLoss();
        }), quickLp(0));
        row.addView(quickCell(R.drawable.ic_music_note, R.string.music_tab_lyric, v -> showPanel(PANEL_LYRIC)), quickLp(dp(20)));
        row.addView(quickCell(R.drawable.ic_music_timer, R.string.music_sleep, v -> showPanel(PANEL_SLEEP)), quickLp(dp(20)));
        row.addView(quickCell(R.drawable.ic_music_plus, R.string.music_add_to_playlist, v -> {
            if (listener != null) listener.onAddToPlaylist();
            dismissAllowingStateLoss();
        }), quickLp(dp(20)));
        return row;
    }

    private LinearLayout.LayoutParams quickLp(int startMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(84), 1f);
        lp.setMarginStart(startMargin);
        return lp;
    }

    private View quickCell(int icon, int label, View.OnClickListener click) {
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout boxTile = new FrameLayout(getContext());
        boxTile.setBackgroundResource(R.drawable.bg_sheet_quick);
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(COLOR_TEXT));
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(dp(24), dp(24));
        ivLp.gravity = Gravity.CENTER;
        boxTile.addView(iv, ivLp);
        cell.addView(boxTile, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        TextView tv = text(getString(label), 12, COLOR_TEXT, false);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(6);
        cell.addView(tv, tlp);
        cell.setOnClickListener(click);
        attachPress(cell, 0.96f);
        return cell;
    }

    /** ③ 播放模式段控（GZ PlayModeSegmented T06）：一张白卡四段；
     * 选中态＝浅粉底 #F8E1E3＋红图标红字（GZ 本体录屏像素实测）；点击即时 MusicController.setMode 并重建刷新，
     * 选中值每次构建都从服务真实模式 getMode() 读回 */
    private View buildModeSegment() {
        String[] labels = {getString(R.string.music_mode_seq), getString(R.string.music_mode_repeat_all),
                getString(R.string.music_mode_repeat_one), getString(R.string.music_mode_shuffle)};
        int[] icons = {R.drawable.ic_music_sequential, R.drawable.ic_music_repeat,
                R.drawable.ic_music_repeat_one, R.drawable.ic_music_shuffle};
        int[] values = {MusicQueue.MODE_SEQUENTIAL, MusicQueue.MODE_REPEAT_ALL,
                MusicQueue.MODE_REPEAT_ONE, MusicQueue.MODE_SHUFFLE};
        int cur = MusicController.get().getMode();
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundResource(R.drawable.bg_sheet_card);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean sel = values[i] == cur;
            LinearLayout segCell = new LinearLayout(getContext());
            segCell.setOrientation(LinearLayout.VERTICAL);
            segCell.setGravity(Gravity.CENTER);
            if (sel) segCell.setBackgroundResource(R.drawable.sheet_mode_selected);
            ImageView iv = new ImageView(getContext());
            iv.setImageResource(icons[i]);
            iv.setImageTintList(ColorStateList.valueOf(sel ? COLOR_RED : COLOR_LOW));
            segCell.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
            TextView tv = text(labels[i], 11, sel ? COLOR_RED : COLOR_LOW, sel);
            tv.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(4);
            segCell.addView(tv, tlp);
            segCell.setOnClickListener(v -> {
                MusicController.get().setMode(values[idx]);
                showPanel(PANEL_MAIN);
            });
            attachPress(segCell, 0.96f);
            card.addView(segCell, new LinearLayout.LayoutParams(0, dp(52), 1f));
        }
        return card;
    }

    /** ④ 系统音量卡（GZ SystemVolumeCard T07）：白卡——标题行＋滑杆区（高 48dp：喇叭＋深色填充细线滑杆＋百分比同行靠右 12sp）；
     * AudioManager STREAM_MUSIC 读写，拖动即时生效；填充/拇指＝AccentRed（GZ 本体图）；ContentObserver 同步外部改量。
     * GZ 右侧「播放设备」入口（T08）星辰无对应底层，本版不做假入口（见报告缺口）。 */
    private View buildVolumeCard() {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_sheet_card);
        card.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView speaker = new ImageView(getContext());
        speaker.setImageResource(R.drawable.ic_widget_volume_medium);
        speaker.setImageTintList(ColorStateList.valueOf(COLOR_MEDIUM));
        row.addView(speaker, new LinearLayout.LayoutParams(dp(20), dp(20)));
        volumeBar = new SeekBar(getContext());
        volumeBar.setProgressDrawable(getContext().getDrawable(R.drawable.sheet_volume_progress));
        volumeBar.setThumb(getContext().getDrawable(R.drawable.sheet_volume_thumb));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        blp.setMarginStart(dp(12));
        blp.setMarginEnd(dp(12));
        row.addView(volumeBar, blp);
        volumePct = text("", 12, COLOR_MEDIUM, false);
        volumePct.setGravity(Gravity.END);
        row.addView(volumePct, new LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        AudioManager am = audioManager();
        if (am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            volumeBar.setMax(Math.max(1, max));
            volumeBar.setProgress(cur);
            volumePct.setText(volumePct(cur, max));
            volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    AudioManager m = audioManager();
                    if (m == null) return;
                    m.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                    volumePct.setText(volumePct(progress, seekBar.getMax()));
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    volumeDragging = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    volumeDragging = false;
                    syncVolumeFromSystem();
                }
            });
            registerVolumeObserver();
        }
        return card;
    }

    private void registerVolumeObserver() {
        unregisterVolumeObserver();
        Context c = getContext();
        if (c == null) return;
        volumeObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                syncVolumeFromSystem();
            }
        };
        try {
            c.getContentResolver().registerContentObserver(
                    Settings.System.CONTENT_URI, true, volumeObserver);
        } catch (Throwable t) {
            volumeObserver = null;
        }
    }

    private void unregisterVolumeObserver() {
        if (volumeObserver == null) return;
        try {
            Context c = getContext();
            if (c != null) c.getContentResolver().unregisterContentObserver(volumeObserver);
        } catch (Throwable ignored) {
        }
        volumeObserver = null;
    }

    private void syncVolumeFromSystem() {
        if (volumeBar == null || volumePct == null || volumeDragging) return;
        AudioManager am = audioManager();
        if (am == null) return;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (volumeBar.getMax() != Math.max(1, max)) volumeBar.setMax(Math.max(1, max));
        volumeBar.setProgress(cur);
        volumePct.setText(volumePct(cur, max));
    }

    private String volumePct(int cur, int max) {
        if (max <= 0) return "0%";
        return Math.round(cur * 100f / max) + "%";
    }

    private AudioManager audioManager() {
        try {
            Context c = getContext();
            return c == null ? null : (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** ⑤ 设置卡（GZ 本体图 112768_26 形态）：信息行（T09）置卡首、有值才显；
     * 中段 2 列格（均衡器｜播放页主题／倍速｜搜索歌词）；分割线下不熄屏开关行；
     * 卡尾保留星辰自有行（下一首播放/加入队列）。 */
    private View buildSettingsCard() {
        LinearLayout card = cardBox();
        MusicTrack track = MusicController.get().current();
        boolean first = true;
        if (track != null) {
            String source = sourceName(track);
            if (!source.isEmpty()) {
                addInfoRow(card, getString(R.string.music_info_source), source, first);
                first = false;
            }
        }
        // GZ 设置卡形态（本体图 112768_26）：2 列格——首行 均衡器｜播放页主题、次行 倍速｜搜索歌词
        // （GZ 次行是「外观设置/自定义封面」，星辰无对应后端，按令以自有真功能 倍速/搜索歌词 替位，不做假入口）
        LinearLayout grid1 = new LinearLayout(getContext());
        grid1.setOrientation(LinearLayout.HORIZONTAL);
        grid1.addView(settingsCell(R.drawable.ic_music_eq, getString(R.string.music_equalizer), null, v -> {
            FragmentActivity host = getActivity();
            dismissAllowingStateLoss();
            if (host != null) MusicFxDialog.show(host);
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        grid1.addView(settingsCell(R.drawable.ic_music_palette, getString(R.string.music_player_theme),
                themeName(MusicStore.getPlayerTheme()), v -> showPanel(PANEL_THEME)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(grid1);
        LinearLayout grid2 = new LinearLayout(getContext());
        grid2.setOrientation(LinearLayout.HORIZONTAL);
        grid2.addView(settingsCell(R.drawable.ic_music_speed, getString(R.string.music_speed), speedName(),
                v -> showSpeedDialog()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        grid2.addView(settingsCell(R.drawable.ic_music_search, getString(R.string.music_search_lyric), null, v -> {
            if (listener != null) listener.onSearchLyric();
            dismissAllowingStateLoss();
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(grid2);
        addDivider(card);
        card.addView(switchRow(getString(R.string.music_keep_screen_on), MusicStore.isPlayerKeepScreenOn(),
                on -> MusicStore.setPlayerKeepScreenOn(on)));
        addRow(card, getString(R.string.music_play_next), null, v -> {
            if (track != null) {
                MusicController.get().insertNext(track);
                Notify.show(R.string.music_play_next);
            }
            dismissAllowingStateLoss();
        }, false);
        addRow(card, getString(R.string.music_add_queue), null, v -> {
            if (track != null) {
                MusicController.get().appendTrack(track);
                Notify.show(R.string.music_added_queue);
            }
            dismissAllowingStateLoss();
        }, false);
        return card;
    }

    /** 设置卡 2 列格单格（GZ 本体图 112790_3：图标左＋文字右横向、整组在格内居中）：
     * 图标 24dp＋标签 13sp，副值（主题名/倍速）跟在标签下方 11sp 灰——GZ 格内无副值，
     * 星辰副值是自有状态显示，保留但不另起一行居中，避免格子被撑成纵向 */
    private View settingsCell(int icon, String label, String sub, View.OnClickListener click) {
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, dp(12), 0, dp(12));
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(COLOR_TEXT));
        cell.addView(iv, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView tv = text(label, 13, COLOR_TEXT, false);
        texts.addView(tv);
        if (sub != null && !sub.isEmpty()) {
            TextView sv = text(sub, 11, COLOR_LOW, false);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(2);
            texts.addView(sv, slp);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart(dp(8));
        cell.addView(texts, tlp);
        cell.setOnClickListener(click);
        attachPress(cell, 0.96f);
        return cell;
    }

    /** 来源名（与播放页 sourceName 同口径）：插件名优先（rawJson 的 _plugin 精确对归属），本地/py 源走各自标识；查不到返回空串（整行不显示） */
    private String sourceName(MusicTrack track) {
        if (track == null || track.platform == null) return "";
        try {
            List<MusicSite> sites = MusicSourceManager.get().listSites();
            String plugin = null;
            if (!TextUtils.isEmpty(track.rawJson)) {
                plugin = new JSONObject(track.rawJson).optString("_plugin", null);
            }
            if (plugin != null) {
                for (MusicSite site : sites) {
                    if (site != null && plugin.equals(site.srcUrl)) return site.name == null ? "" : site.name;
                }
            }
            for (MusicSite site : sites) {
                if (site != null && track.platform.equals(site.platform)) return site.name == null ? "" : site.name;
            }
        } catch (Throwable ignored) {
        }
        if ("local".equals(track.platform)) return getString(R.string.music_downloaded);
        if (DeviceMusicSource.PLATFORM.equals(track.platform)) return getString(R.string.music_device);
        if (PyMusicSource.PLATFORM.equals(track.platform)) return PyMusicSource.siteNameOf(track);
        return "";
    }

    private void showSpeedDialog() {
        FragmentActivity host = getActivity();
        if (host == null) return;
        float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        List<String> opts = new ArrayList<>();
        int checked = 2;
        float cur = MusicController.get().getSpeed();
        for (int i = 0; i < speeds.length; i++) {
            opts.add(speedLabel(speeds[i]));
            if (Math.abs(speeds[i] - cur) < 0.01f) checked = i;
        }
        dismissAllowingStateLoss();
        MusicOptionsDialog.show(host, getString(R.string.music_speed), opts, checked,
                idx -> MusicController.get().setSpeed(speeds[idx]));
    }

    private String speedName() {
        return speedLabel(MusicController.get().getSpeed());
    }

    private static String speedLabel(float s) {
        return (s == Math.floor(s) ? String.format("%.1f", s) : String.valueOf(s)) + "×";
    }

    // ================= 主题子面板 =================

    private void buildThemePanel() {
        LinearLayout box = binding.container;
        box.addView(subHeader(getString(R.string.music_player_theme)));
        TextView desc = text(getString(R.string.sheet_theme_desc), 12, COLOR_LOW, false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(8);
        box.addView(desc, dlp);
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        String cur = MusicStore.getPlayerTheme();
        for (int i = 0; i < THEME_IDS.length; i++) {
            final String id = THEME_IDS[i];
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(10));
            row.addView(themeCard(id, THEME_NAMES[i], THEME_COLORS[i], id.equals(cur), v -> {
                MusicStore.setPlayerTheme(id);
                if (listener != null) listener.onThemeChanged();
                showPanel(PANEL_THEME);
            }), lp);
        }
        scroll.addView(row);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(14);
        box.addView(scroll, slp);
        // 选中流光 CD 时才出表面三段：浅色/深色/自动（GZ T24 CdSurfaceMode）
        if (MusicStore.THEME_LUMINOUS_CD.equals(MusicStore.getPlayerTheme())) {
            String[] labels = {getString(R.string.music_surface_light), getString(R.string.music_surface_dark),
                    getString(R.string.music_surface_auto)};
            View seg = segment(labels, MusicStore.getCdSurface(), idx -> {
                MusicStore.setCdSurface(idx);
                if (listener != null) listener.onThemeChanged();
                showPanel(PANEL_THEME);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(16);
            box.addView(seg, lp);
        }
    }

    /** 主题卡（T22/T23，无 Pro 锁——用户定案全免费）：色块示意＋主题名＋选中勾；
     * 选中卡背景 150ms 渐变到浅粉 #F8E1E3＋红描边红勾（GZ 主题选中卡口径） */
    private View themeCard(String id, int nameRes, int[] colors, boolean selected, View.OnClickListener click) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), COLOR_BORDER);
        if (selected) bg.setStroke(dp(2), COLOR_RED);
        card.setBackground(bg);
        View block = new View(getContext());
        GradientDrawable grad = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        grad.setCornerRadius(dp(8));
        block.setBackground(grad);
        card.addView(block, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        TextView name = text(getString(nameRes), 13, COLOR_TEXT, selected);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(8);
        card.addView(name, nlp);
        TextView check = text("✓", 14, COLOR_RED, true);
        check.setGravity(Gravity.CENTER);
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        card.addView(check, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));
        card.setContentDescription(getString(nameRes));
        card.setOnClickListener(click);
        attachPress(card, 0.96f);
        if (selected) {
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofObject(
                    new android.animation.ArgbEvaluator(), 0xFFFFFFFF, 0xFFF8E1E3);
            anim.setDuration(150);
            anim.addUpdateListener(a -> bg.setColor((int) a.getAnimatedValue()));
            anim.start();
        }
        return card;
    }

    private String themeName(String id) {
        for (int i = 0; i < THEME_IDS.length; i++) {
            if (THEME_IDS[i].equals(id)) return getString(THEME_NAMES[i]);
        }
        return getString(R.string.music_theme_classic);
    }

    // ================= 定时子面板 =================

    /** 睡眠子面板（GZ Sleep 网格形态）：状态行（18sp）＋预设 3 列网格＋「播完这首」chip；
     * 档位只用星辰服务现成六档（关/播完这首/15/30/60/90 → MusicController.setSleep）。
     * GZ 的 10/20/40 档与自定义分钟服务无对应档位，不自创（见报告阻塞项）；倒计时读数走服务截止时刻（GZ B07）。 */
    private void buildSleepPanel() {
        LinearLayout box = binding.container;
        box.addView(subHeader(getString(R.string.music_sleep)));
        int cur = MusicController.get().getSleep();
        // 状态行（GZ B07）：未开启显「未开启」；定时档显档名＋剩余 m:ss；播完这首显其档名
        String statusText;
        if (cur == MusicPlaybackService.SLEEP_OFF) {
            statusText = getString(R.string.sheet_sleep_not_on);
        } else {
            statusText = MusicPlaybackService.sleepName(cur);
            long remain = MusicController.get().sleepRemainingMs();
            if (remain >= 0) {
                long sec = (remain + 999) / 1000;
                statusText += " · " + (sec / 60) + ":" + String.format("%02d", sec % 60);
            }
        }
        TextView status = text(statusText, 18, COLOR_MEDIUM, false);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = dp(14);
        box.addView(status, stlp);
        int[] gridValues = {MusicPlaybackService.SLEEP_15, MusicPlaybackService.SLEEP_30,
                MusicPlaybackService.SLEEP_60, MusicPlaybackService.SLEEP_90,
                MusicPlaybackService.SLEEP_OFF, -1};
        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                final int value = gridValues[r * 3 + c];
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
                if (c > 0) lp.setMarginStart(dp(10));
                if (value < 0) {
                    row.addView(new View(getContext()), lp);
                    continue;
                }
                row.addView(sleepCell(MusicPlaybackService.sleepName(value), value == cur, v -> {
                    MusicController.get().setSleep(value);
                    showPanel(PANEL_MAIN);
                }), lp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (r > 0) rlp.topMargin = dp(10);
            grid.addView(row, rlp);
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(16);
        box.addView(grid, glp);
        View chip = sleepCell(MusicPlaybackService.sleepName(MusicPlaybackService.SLEEP_AFTER_CURRENT),
                cur == MusicPlaybackService.SLEEP_AFTER_CURRENT, v -> {
                    MusicController.get().setSleep(MusicPlaybackService.SLEEP_AFTER_CURRENT);
                    showPanel(PANEL_MAIN);
                });
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        chlp.topMargin = dp(12);
        box.addView(chip, chlp);
    }

    /** 定时格/chip：未选 inset 底深字，选中浅粉底红字（GZ 红系选中口径） */
    private View sleepCell(String label, boolean selected, View.OnClickListener click) {
        TextView tv = text(label, 14, selected ? COLOR_RED : COLOR_TEXT, selected);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(selected ? R.drawable.sheet_cell_selected : R.drawable.bg_sheet_seg_track);
        tv.setOnClickListener(click);
        attachPress(tv, 0.96f);
        return tv;
    }

    // ================= 歌词设置子面板 =================

    private void buildLyricPanel() {
        LinearLayout box = binding.container;
        box.addView(subHeader(getString(R.string.music_lyric_settings)));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(132));
        previewLp.topMargin = dp(12);
        box.addView(buildLyricPreview(), previewLp);
        box.addView(buildLyricSizeRow(), marginTop(dp(16)));
        box.addView(lyricSegRow(getString(R.string.music_lyric_weight),
                new String[]{getString(R.string.music_weight_normal), getString(R.string.music_weight_bold),
                        getString(R.string.music_weight_black)},
                MusicStore.getLyricWeight(), idx -> {
                    MusicStore.setLyricWeight(idx);
                    fireLyricChanged();
                    showPanel(PANEL_LYRIC);
                }), marginTop(dp(14)));
        box.addView(lyricSegRow(getString(R.string.music_lyric_align),
                new String[]{getString(R.string.music_align_left), getString(R.string.music_align_center),
                        getString(R.string.music_align_right)},
                MusicStore.getLyricAlign(), idx -> {
                    MusicStore.setLyricAlign(idx);
                    fireLyricChanged();
                    showPanel(PANEL_LYRIC);
                }), marginTop(dp(14)));
        box.addView(lyricSegRow(getString(R.string.music_lyric_blur),
                new String[]{getString(R.string.music_blur_off), getString(R.string.music_blur_light),
                        getString(R.string.music_blur_medium)},
                MusicStore.getLyricBlur(), idx -> {
                    MusicStore.setLyricBlur(idx);
                    fireLyricChanged();
                    showPanel(PANEL_LYRIC);
                }), marginTop(dp(14)));
        box.addView(lyricSegRow(getString(R.string.music_lyric_primary),
                new String[]{getString(R.string.music_primary_original), getString(R.string.music_primary_translation),
                        getString(R.string.music_primary_romanization)},
                MusicStore.getLyricPrimary(), idx -> {
                    MusicStore.setLyricPrimary(idx);
                    fireLyricChanged();
                    showPanel(PANEL_LYRIC);
                }), marginTop(dp(14)));
        // 主歌词不可用提示（GZ T32 四态：10sp、alpha 0.5）：歌词数据到达后按四态拼行
        lyricHint = text("", 10, COLOR_MEDIUM, false);
        lyricHint.setAlpha(0.5f);
        lyricHint.setVisibility(View.GONE);
        box.addView(lyricHint, marginTop(dp(8)));
        LinearLayout card = cardBox();
        card.addView(switchRow(getString(R.string.music_show_translation), MusicStore.isShowTranslation(),
                on -> {
                    MusicStore.setShowTranslation(on);
                    fireLyricChanged();
                    refreshLyricHint();
                }));
        addDivider(card);
        card.addView(switchRow(getString(R.string.music_show_romanization), MusicStore.isShowRomanization(),
                on -> {
                    MusicStore.setShowRomanization(on);
                    fireLyricChanged();
                    refreshLyricHint();
                }));
        addDivider(card);
        // 高级动效行高 60dp（GZ 歌词设置行高口径）；开启时弹确认框（GZ T33 文案照抄）
        card.addView(switchRow(getString(R.string.music_advanced_fx), MusicStore.isAdvancedFx(), 60,
                on -> {
                    MusicStore.setAdvancedFx(on);
                    fireLyricChanged();
                    if (on) showAdvancedFxConfirm();
                }));
        box.addView(card, marginTop(dp(16)));
        // 恢复默认钮高 28dp（GZ T34：字号 22/粗细 bold（一段「标准」）/靠左/模糊轻度，只复位这四项）
        TextView restore = text(getString(R.string.music_restore_defaults), 13, COLOR_DARK, false);
        restore.setGravity(Gravity.CENTER);
        restore.setBackgroundResource(R.drawable.bg_sheet_card);
        restore.setOnClickListener(v -> {
            MusicStore.setLyricFontSize(22f);
            MusicStore.setLyricWeight(MusicStore.LYRIC_WEIGHT_NORMAL);
            MusicStore.setLyricAlign(MusicStore.LYRIC_ALIGN_LEFT);
            MusicStore.setLyricBlur(MusicStore.LYRIC_BLUR_LIGHT);
            fireLyricChanged();
            showPanel(PANEL_LYRIC);
        });
        attachPress(restore, 0.96f);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        rlp.topMargin = dp(16);
        box.addView(restore, rlp);
        loadLyricForPanel();
    }

    private void showAdvancedFxConfirm() {
        if (!isAdded() || getContext() == null) return;
        new MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.sheet_advanced_fx_title)
                .setMessage(R.string.sheet_advanced_fx_msg)
                .setPositiveButton(R.string.sheet_got_it, null)
                .show();
    }

    /** 顶部预览区（GZ T26）：高 132dp、内边 14/8，当前行歌词按当前样式实时渲染（500ms 跟随播放进度） */
    private View buildLyricPreview() {
        FrameLayout frame = new FrameLayout(getContext());
        frame.setBackgroundResource(R.drawable.bg_sheet_card);
        frame.setPadding(dp(14), dp(8), dp(14), dp(8));
        lyricPreview = text(getString(R.string.music_lyric_preview_sample), 22, COLOR_TEXT, false);
        lyricPreview.setMaxLines(2);
        lyricPreview.setEllipsize(TextUtils.TruncateAt.END);
        frame.addView(lyricPreview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyPreview();
        return frame;
    }

    private void applyPreview() {
        if (lyricPreview == null) return;
        lyricPreview.setTextSize(MusicStore.getLyricFontSize());
        int w = MusicStore.getLyricWeight();
        lyricPreview.setTypeface(w == MusicStore.LYRIC_WEIGHT_NORMAL ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
        lyricPreview.getPaint().setFakeBoldText(w == MusicStore.LYRIC_WEIGHT_BLACK);
        lyricPreview.getPaint().setTextSkewX(-0.12f);
        int a = MusicStore.getLyricAlign();
        int horizontal = a == MusicStore.LYRIC_ALIGN_CENTER ? Gravity.CENTER_HORIZONTAL
                : a == MusicStore.LYRIC_ALIGN_RIGHT ? Gravity.END : Gravity.START;
        lyricPreview.setGravity(Gravity.CENTER_VERTICAL | horizontal);
    }

    /** 后台取当前轨 rich 歌词（MusicRepository.lyricRich 是阻塞调用）：解析时间轴供预览跟随，并算译/音译可用性供四态提示 */
    private void loadLyricForPanel() {
        lyricLoaded = false;
        lyricHasTrans = false;
        lyricHasRoma = false;
        lyricTimes.clear();
        lyricTexts.clear();
        MusicTrack track = MusicController.get().current();
        if (track == null) {
            lyricLoaded = true;
            refreshLyricHint();
            return;
        }
        String trackId = track.id;
        new Thread(() -> {
            MusicLyric lyric = null;
            try {
                lyric = MusicRepository.lyricRich(track);
            } catch (Throwable ignored) {
            }
            MusicLyric result = lyric;
            mainHandler.post(() -> {
                if (!isAdded() || currentPanel != PANEL_LYRIC) return;
                MusicTrack cur = MusicController.get().current();
                if (cur == null) return;
                if (trackId == null ? cur.id != null : !trackId.equals(cur.id)) return;
                lyricLoaded = true;
                if (result != null) {
                    lyricHasTrans = !TextUtils.isEmpty(result.translation) && !result.translation.trim().isEmpty();
                    lyricHasRoma = !TextUtils.isEmpty(result.romanization) && !result.romanization.trim().isEmpty();
                    parseLrc(result.rawLrc);
                }
                refreshLyricHint();
                startLyricTicker();
            });
        }, "music-tools-lyric").start();
    }

    private static final Pattern LRC_TAG = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");

    private void parseLrc(String raw) {
        lyricTimes.clear();
        lyricTexts.clear();
        if (TextUtils.isEmpty(raw)) return;
        List<long[]> times = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (String line : raw.split("\n")) {
            Matcher m = LRC_TAG.matcher(line);
            List<Long> tags = new ArrayList<>();
            int end = 0;
            while (m.find()) {
                long cs = 0;
                if (m.group(3) != null) {
                    String f = m.group(3);
                    cs = Long.parseLong(f) * (f.length() == 1 ? 100 : f.length() == 2 ? 10 : 1);
                }
                tags.add(Long.parseLong(m.group(1)) * 60000L + Long.parseLong(m.group(2)) * 1000L + cs);
                end = m.end();
            }
            if (tags.isEmpty()) continue;
            String text = line.substring(end).trim();
            for (Long t : tags) {
                times.add(new long[]{t});
                texts.add(text);
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) order.add(i);
        Collections.sort(order, (a, b) -> Long.compare(times.get(a)[0], times.get(b)[0]));
        for (int i : order) {
            lyricTimes.add(times.get(i));
            lyricTexts.add(texts.get(i));
        }
    }

    private void startLyricTicker() {
        stopLyricTicker();
        lyricTicker = new Runnable() {
            @Override
            public void run() {
                if (!isAdded() || currentPanel != PANEL_LYRIC || lyricPreview == null) return;
                updateLyricPreviewLine();
                mainHandler.postDelayed(this, 500);
            }
        };
        mainHandler.post(lyricTicker);
    }

    private void stopLyricTicker() {
        if (lyricTicker != null) mainHandler.removeCallbacks(lyricTicker);
        lyricTicker = null;
    }

    private void updateLyricPreviewLine() {
        if (lyricPreview == null || lyricTimes.isEmpty()) return;
        long pos = MusicController.get().position();
        String line = null;
        for (int i = 0; i < lyricTimes.size(); i++) {
            if (lyricTimes.get(i)[0] <= pos) line = lyricTexts.get(i);
            else break;
        }
        if (!TextUtils.isEmpty(line)) lyricPreview.setText(line);
    }

    /** 四态提示（GZ T32，与源码同判定同拼接）：无译→「当前歌词无翻译」，有译但关→「翻译歌词已关闭」；音译同理；以「；」拼接 */
    private void refreshLyricHint() {
        if (lyricHint == null || !lyricLoaded) return;
        List<String> parts = new ArrayList<>();
        if (!lyricHasTrans) parts.add(getString(R.string.sheet_hint_no_translation));
        else if (!MusicStore.isShowTranslation()) parts.add(getString(R.string.sheet_hint_translation_off));
        if (!lyricHasRoma) parts.add(getString(R.string.sheet_hint_no_romanization));
        else if (!MusicStore.isShowRomanization()) parts.add(getString(R.string.sheet_hint_romanization_off));
        if (parts.isEmpty()) {
            lyricHint.setVisibility(View.GONE);
        } else {
            lyricHint.setText(TextUtils.join("；", parts));
            lyricHint.setVisibility(View.VISIBLE);
        }
    }

    /** 字号滑杆（GZ T27：封面歌词 14–32、取整、两端 A 示意字 11sp/16sp）；
     * 写入 MusicStore.setLyricFontSize（星辰统一字号键，其内钳 16–40，14/15 档实际落 16，松手后按存回值回显） */
    private View buildLyricSizeRow() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(getString(R.string.music_lyric_font_size), 14, COLOR_TEXT, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        lyricSizeValue = text(String.valueOf(Math.round(MusicStore.getLyricFontSize())), 13, COLOR_LOW, false);
        head.addView(lyricSizeValue);
        row.addView(head);
        LinearLayout sliderRow = new LinearLayout(getContext());
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView smallA = text("A", 11, COLOR_LOW, false);
        sliderRow.addView(smallA);
        SeekBar bar = new SeekBar(getContext());
        bar.setProgressTintList(ColorStateList.valueOf(COLOR_RED));
        bar.setThumbTintList(ColorStateList.valueOf(COLOR_RED));
        bar.setMax(40 - 16);
        float cur = Math.max(16f, Math.min(40f, MusicStore.getLyricFontSize()));
        bar.setProgress(Math.round(cur) - 16);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                float v = progress + 16f;
                if (lyricPreview != null) lyricPreview.setTextSize(v);
                if (lyricSizeValue != null) lyricSizeValue.setText(String.valueOf(Math.round(v)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                MusicStore.setLyricFontSize(seekBar.getProgress() + 16f);
                float stored = MusicStore.getLyricFontSize();
                if (lyricSizeValue != null) lyricSizeValue.setText(String.valueOf(Math.round(stored)));
                applyPreview();
                fireLyricChanged();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.setMarginStart(dp(8));
        blp.setMarginEnd(dp(8));
        sliderRow.addView(bar, blp);
        TextView bigA = text("A", 16, COLOR_LOW, false);
        sliderRow.addView(bigA);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(4);
        row.addView(sliderRow, slp);
        return row;
    }

    private View lyricSegRow(String label, String[] options, int selected, IntConsumer onPick) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.addView(text(label, 14, COLOR_TEXT, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.addView(segment(options, selected, onPick), lp);
        return row;
    }

    private void fireLyricChanged() {
        if (listener != null) listener.onLyricStyleChanged();
    }

    // ================= 通用构建件 =================

    /** 子面板头（GlassSheetPanelKt SheetSubPanelHeader）：高 44dp，圆形钮 40dp（按压缩放 0.9）＋标题（字距 −0.3）；返回回主面板 */
    private View subHeader(String title) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        TextView back = text("‹", 22, COLOR_TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setBackgroundResource(R.drawable.bg_sheet_circle_btn);
        back.setContentDescription(getString(R.string.music_back));
        back.setOnClickListener(v -> showPanel(PANEL_MAIN));
        attachPress(back, 0.9f);
        row.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView tv = text(title, 16, COLOR_TEXT, true);
        tv.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(8));
        row.addView(tv, tlp);
        return row;
    }

    /** 通用段控（粗细/对齐/模糊/主歌词/CD 表面共用）：轨道 inset 底、格高 40dp、选中白格＋主字加粗 */
    private LinearLayout segment(String[] labels, int selected, IntConsumer onPick) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.bg_sheet_seg_track);
        row.setPadding(dp(3), dp(3), dp(3), dp(3));
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean sel = i == selected;
            TextView tv = text(labels[i], 13, sel ? COLOR_TEXT : COLOR_LOW, sel);
            tv.setGravity(Gravity.CENTER);
            if (sel) tv.setBackgroundResource(R.drawable.bg_sheet_seg_selected);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            if (i > 0) lp.setMarginStart(dp(3));
            tv.setOnClickListener(v -> onPick.accept(idx));
            attachPress(tv, 0.96f);
            row.addView(tv, lp);
        }
        return row;
    }

    /** 分组白卡容器：行自带 16dp 横向内边距（分割线收边与行边距对齐靠此口径） */
    private LinearLayout cardBox() {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_sheet_card);
        card.setPadding(0, 0, 0, 0);
        return card;
    }

    /**
     * 设置行：单行横排——标签 weight 1、值右对齐同排、雪佛龙 20dp；整行固定高 56dp＋整行 gravity 居中，
     * 标签/值同处一行中线（根绝旧版值掉行/右缘裁切）。
     */
    private void addRow(LinearLayout card, String label, String value, View.OnClickListener click, boolean first) {
        if (!first) addDivider(card);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        TextView tv = text(label, 15, COLOR_TEXT, false);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(tv, new LinearLayout.LayoutParams(0, dp(56), 1f));
        if (value != null) {
            TextView vv = text(value, 14, COLOR_LOW, false);
            vv.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            row.addView(vv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));
        }
        ImageView chevron = new ImageView(getContext());
        chevron.setImageResource(R.drawable.ic_music_chevron);
        chevron.setImageTintList(ColorStateList.valueOf(COLOR_LOW));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(20), dp(20));
        clp.setMarginStart(dp(6));
        row.addView(chevron, clp);
        row.setOnClickListener(click);
        attachPress(row, 0.98f);
        card.addView(row);
    }

    /** 歌曲信息行（T09）：名字左、值右、同一行；无雪佛龙、不可点；值 weight 1 靠右，长值尾部省略不断行 */
    private void addInfoRow(LinearLayout card, String label, String value, boolean first) {
        if (!first) addDivider(card);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        TextView tv = text(label, 15, COLOR_TEXT, false);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        TextView vv = text(value, 14, COLOR_LOW, false);
        vv.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        vlp.setMarginStart(dp(12));
        row.addView(vv, vlp);
        card.addView(row);
    }

    private View switchRow(String label, boolean checked, java.util.function.Consumer<Boolean> onChange) {
        return switchRow(label, checked, 56, onChange);
    }

    private View switchRow(String label, boolean checked, int heightDp, java.util.function.Consumer<Boolean> onChange) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        TextView tv = text(label, 15, COLOR_TEXT, false);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(tv, new LinearLayout.LayoutParams(0, dp(heightDp), 1f));
        SwitchCompat sw = new SwitchCompat(getContext());
        sw.setChecked(checked);
        sw.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFFFFFFFF, 0xFFFFFFFF}));
        sw.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{COLOR_DARK, 0xFFD9D9DE}));
        sw.setOnCheckedChangeListener((button, on) -> onChange.accept(on));
        row.addView(sw);
        return row;
    }

    /** 分割线（GZ 口径）：只出现在卡内行间，高 0.5dp、色黑 0.06、左右各收 16dp 与行内边距对齐 */
    private void addDivider(LinearLayout card) {
        View div = new View(getContext());
        div.setBackgroundColor(COLOR_DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(0.5f * density())));
        lp.setMarginStart(dp(16));
        lp.setMarginEnd(dp(16));
        card.addView(div, lp);
    }

    /** GZ 通用按压反馈（MotionKt：260ms；缩放值由调用方传，段控/格子 0.96、圆钮 0.9） */
    private void attachPress(View view, float scale) {
        view.setOnTouchListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(scale).scaleY(scale).setDuration(260).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(260).start();
            }
            return false;
        });
    }

    private TextView text(String content, int sp, int color, boolean bold) {
        TextView tv = new TextView(getContext());
        tv.setText(content);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private GradientDrawable cardBg(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private float density() {
        Context c = getContext();
        return c == null ? 1f : c.getResources().getDisplayMetrics().density;
    }

    private int dp(int v) {
        return Math.round(v * density());
    }
}
