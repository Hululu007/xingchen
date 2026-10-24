package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
import com.fongmi.android.tv.music.MusicDeviceRouter;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.PyMusicSource;
import com.fongmi.android.tv.service.MusicPlaybackService;
import com.fongmi.android.tv.ui.music.CdDiscView;
import com.fongmi.android.tv.ui.music.MusicPlayerHost;
import com.fongmi.android.tv.ui.music.MusicPlayerSettings;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
 * 主字 #1C1C1E、次字 #9A9AA0、中字 #8E8E93、分割线/描边黑 0.06、内嵌黑 0.04；
 * 面板内边距 16dp 底 10dp（GlassSheetPanelKt）、区块间隔 20dp、子面板头 44dp/圆钮 40dp。
 * P04：瓜子工具面板无拖动条，星辰自绘条已删。P39/U1：不熄屏开关选中轨红 #FA243C（smali 实证）、
 * 拇指恒白、未选轨黑 0.10——旧「开关选中态改深色填充 #1C1C1E」口径已废止。
 * 批 4：面板族换液态玻璃皮（面板底 bg_music_glass_panel、分组卡／快捷格／段控／圆钮／预览区换
 * bg_music_glass_* 玻璃件，半透明白＋白描边高光，底不许纯白）；主题子面板改七卡真预览
 * （五主题＋CD 三表面按主题 id 逐个成卡、写 MusicPlayerSettings，预览＝主题布局缩略渲染）；
 * 歌词子面板补模糊三档行；外观子面板补流体速度三档＋高级动效行。
 */
public class MusicToolsSheetDialog extends BaseBottomSheetDialog {

    public interface Listener {
        void onThemeChanged();

        void onLyricStyleChanged();

        void onSearchLyric();

        void onAddToPlaylist();

        void onDownload();

        /** A4 外观开关变化（流体）时通知播放页刷新流体层 */
        void onAppearanceChanged();
    }

    private static final int PANEL_MAIN = 0;
    private static final int PANEL_THEME = 1;
    private static final int PANEL_SLEEP = 2;
    private static final int PANEL_LYRIC = 3;
    private static final int PANEL_SONG_INFO = 4;
    private static final int PANEL_APPEARANCE = 5;
    private static final int PANEL_DEVICE = 6;
    private static final int PANEL_CUSTOM_COVER = 7;

    /** 自定义封面：从相册选图（GetContent image/*，DialogFragment 字段注册，构造时即注册） */
    private final ActivityResultLauncher<String> pickCoverLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onCustomCoverPicked);

    // GZ SheetTokensKt 浅色套
    private static final int COLOR_PANEL = 0xFFF3F3F3;
    private static final int COLOR_DARK = 0xFF1C1C1E;   // 深色文字/恢复默认行用；开关选中轨已改红，不再用它
    private static final int COLOR_RED = 0xFFFA243C;    // GZ AccentRed（本体多处实证；上一版「中性深代红」口径无原话、已废止）
    private static final int COLOR_TEXT = 0xFF1C1C1E;
    private static final int COLOR_LOW = 0xFF9A9AA0;
    private static final int COLOR_MEDIUM = 0xFF8E8E93;
    private static final int COLOR_DIVIDER = 0x0F000000;
    private static final int COLOR_BORDER = 0x0F000000;

    // 主题卡序（瓜子主题清单序，PlayerTheme.java:132-134）：Classic／CoverImmersive／AppleMusic／
    // LuminousCd／RecordShelf；批 4 起流光 CD 三表面按独立主题 id 逐个成卡（luminous_cd／
    // luminous_cd_dark／luminous_cd_auto，§4 主题模型），共七卡。id／名／布局／代表底色一一对应：
    // 代表底色只作缩略渲染的垫底（主题自身表面色：CD 浅 base #F4F2EE／深 #14161C、架 #F1F0ED），不是预览图本体。
    private static final String[] THEME_IDS = {
            MusicPlayerSettings.THEME_CLASSIC, MusicPlayerSettings.THEME_COVER_IMMERSIVE,
            MusicPlayerSettings.THEME_APPLE_MUSIC, MusicPlayerSettings.THEME_LUMINOUS_CD,
            MusicPlayerSettings.THEME_LUMINOUS_CD_DARK, MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO,
            MusicPlayerSettings.THEME_RECORD_SHELF};
    private static final int[] THEME_NAMES = {
            R.string.music_theme_classic, R.string.music_theme_cover, R.string.music_theme_apple,
            R.string.music_theme_cd, R.string.music_theme_cd_dark, R.string.music_theme_cd_auto,
            R.string.music_theme_vinyl};
    private static final int[] THEME_LAYOUTS = {
            R.layout.music_player_theme_classic, R.layout.music_player_theme_cover,
            R.layout.music_player_theme_apple, R.layout.music_player_theme_cd,
            R.layout.music_player_theme_cd, R.layout.music_player_theme_cd,
            R.layout.music_player_theme_shelf};
    private static final int[][] THEME_COLORS = {
            {0xFF3A4A5F, 0xFF10151C},
            {0xFF7B4DFF, 0xFFFF8A3D},
            {0xFFF5F5F7, 0xFFD9D9DE},
            {0xFFF4F2EE, 0xFFE9EFF5},
            {0xFF14161C, 0xFF14161C},
            {0xFFE9EFF5, 0xFF282D36},
            {0xFFF1F0ED, 0xFFF1F0ED}};
    /** 主题真预览图宽高比（PlayerToolsSheetKt ThemePanel 实读 0.46075085＝宽/高，竖幅）。 */
    private static final float THEME_PREVIEW_ASPECT = 0.46075085f;
    /** 主题缩略渲染缓存（本面板实例内，id→位图；渲染失败存空标记不重复试）。 */
    private final java.util.Map<String, android.graphics.Bitmap> themePreviewCache = new java.util.HashMap<>();

    private DialogMusicToolsBinding binding;
    private Listener listener;
    private int currentPanel = PANEL_MAIN;
    private int initialPanel = PANEL_MAIN;
    /** B5：播放设备路由（懒建；建不起来＝通道未通，音量卡不画设备入口） */
    private MusicDeviceRouter deviceRouter;

    // 音量卡：外部音量变化同步（ContentObserver）
    private ContentObserver volumeObserver;
    private SeekBar volumeBar;
    private TextView volumePct;
    private ImageView volumeSpeaker;
    private boolean volumeDragging;

    // 歌词设置子面板：当前行实时预览 + 四态提示
    private TextView lyricPreview;
    private TextView lyricSizeValue;
    private TextView lyricHint;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** crash 项：segment 延迟回调登记表——onDestroyView 时全部取消，否则面板销毁后还调 onPick／动 binding。 */
    private final List<Runnable> segmentPendings = new ArrayList<>();
    /** T9：切歌时主面板重建（信息头／设置卡跟当前曲），否则开着面板切歌后下载／加歌单走旧曲。 */
    private final MusicController.Listener toolsListener = new MusicController.Listener() {
        @Override
        public void onTrackChanged(MusicTrack track) {
            if (isAdded() && currentPanel == PANEL_MAIN) showPanel(PANEL_MAIN);
        }
    };
    private Runnable lyricTicker;
    private final List<long[]> lyricTimes = new ArrayList<>();
    private final List<String> lyricTexts = new ArrayList<>();
    private boolean lyricLoaded;
    private boolean lyricHasTrans;
    private boolean lyricHasRoma;

    public static void show(Activity activity, Listener listener) {
        // 防崩守卫：宿主已结束／已销毁时不弹（页面销毁后弹出守卫，批 4 自查项）
        if (!(activity instanceof FragmentActivity)) return;
        if (activity.isFinishing() || activity.isDestroyed()) return;
        MusicToolsSheetDialog d = new MusicToolsSheetDialog();
        d.listener = listener;
        d.show(((FragmentActivity) activity).getSupportFragmentManager(), "music_tools_sheet");
    }

    /** 直达歌词设置子面板（播放页歌词筹入口） */
    public static void showLyricPanel(Activity activity, Listener listener) {
        if (!(activity instanceof FragmentActivity)) return;
        if (activity.isFinishing() || activity.isDestroyed()) return;
        MusicToolsSheetDialog d = new MusicToolsSheetDialog();
        d.listener = listener;
        d.initialPanel = PANEL_LYRIC;
        d.show(((FragmentActivity) activity).getSupportFragmentManager(), "music_tools_sheet");
    }

    /** 直达歌曲信息子面板（主面板歌曲信息行／Apple 左划同一入口） */
    public static void showSongInfoPanel(Activity activity, Listener listener) {
        if (!(activity instanceof FragmentActivity)) return;
        if (activity.isFinishing() || activity.isDestroyed()) return;
        MusicToolsSheetDialog d = new MusicToolsSheetDialog();
        d.listener = listener;
        d.initialPanel = PANEL_SONG_INFO;
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
        // 工具面板遮罩：瓜子面板打开后底下播放页是暗下去的，星辰这边原来无遮罩（页全亮、面板飘在上面）。
        // 在此显式加遮罩；0.32 为平台默认遮罩值（瓜子面板遮罩确数未从源码抠到，不写成瓜子值）。
        try {
            if (getDialog() != null && getDialog().getWindow() != null) {
                getDialog().getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                getDialog().getWindow().setDimAmount(0.32f);
            }
        } catch (Throwable ignored) {
        }
        // 右缘防裁切：窗口宽强制 MATCH_PARENT，行值/开关完整可见
        try {
            if (getDialog() != null && getDialog().getWindow() != null) {
                getDialog().getWindow().setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {
        }
        // T9：切歌重建主面板（注册在 showPanel 之前，首面板即受监听）。
        MusicController.get().addListener(toolsListener);
        showPanel(initialPanel);
    }

    @Override
    public void onDestroyView() {
        for (Runnable r : segmentPendings) mainHandler.removeCallbacks(r);
        segmentPendings.clear();
        MusicController.get().removeListener(toolsListener);
        stopLyricTicker();
        unregisterVolumeObserver();
        if (deviceRouter != null) {
            deviceRouter.destroy();
            deviceRouter = null;
        }
        binding = null;
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
            case PANEL_SONG_INFO:
                buildSongInfoPanel();
                break;
            case PANEL_APPEARANCE:
                buildAppearancePanel();
                break;
            case PANEL_CUSTOM_COVER:
                buildCustomCoverPanel();
                break;
            case PANEL_DEVICE:
                buildDevicePanel();
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
        cover.setBackground(cardBg(0x1F8E8E93, 12));
        cover.setClipToOutline(true);
        cover.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
            }
        });
        row.addView(cover, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("", 16, COLOR_TEXT, true);
        applyWeight(title, 800);
        title.setLetterSpacing(-0.02f);
        title.setLineHeight(dp(21));
        TextView artist = text("", 12, COLOR_MEDIUM, false);
        artist.setPadding(0, dp(2), 0, 0);
        artist.setLineHeight(dp(16));
        texts.addView(title);
        texts.addView(artist);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(12));
        row.addView(texts, tlp);
        ImageView close = new ImageView(getContext());
        close.setImageResource(R.drawable.ic_music_close);
        close.setImageTintList(ColorStateList.valueOf(COLOR_MEDIUM));
        close.setPadding(dp(11), dp(11), dp(11), dp(11));
        close.setContentDescription(getString(R.string.music_close));
        close.setOnClickListener(v -> dismissAllowingStateLoss());
        attachPress(close, 0.9f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(40), dp(40));
        clp.setMarginStart(dp(8));
        row.addView(close, clp);
        MusicTrack track = MusicController.get().current();
        if (track == null) {
            title.setText(R.string.music_no_track);
        } else {
            title.setText(track.displayTitle());
            String artistLine = track.displayArtist();
            if (artistLine == null || artistLine.isEmpty()) artistLine = getString(R.string.music_unknown_artist);
            if (!TextUtils.isEmpty(track.album)) artistLine += " · " + track.album;
            artist.setText(artistLine);
            ImgUtil.loadRounded(track.displayTitle(), track.cover, cover);
        }
        return row;
    }

    /** ② 快捷格一排 4（GZ 快捷格行 T02–T05）：盒高 56dp、白方格圆角 16dp＋阴影 2dp、图标 26dp、标签 12sp/700、标距 8dp、格间距 8dp。
     * 瓜子第 5 格是一起听（社交分发风险、按定案排除，不为凑数加回）；文案照瓜子：定时／加到歌单。
     * P17：下载＝回调＋关面板；歌词／定时＝showPanel 切子面板不关（showPanel 内 260ms 淡入）；格子本身不关面板。 */
    private View buildQuickGrid() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.addView(quickCell(R.drawable.ic_music_download, R.string.music_download, v -> {
            if (askDownloadHere()) return;
            // 下载对接宿主 downloadCurrent()：本地歌/已下载拦截后走 MusicDownloadStore.download
            if (listener != null) listener.onDownload();
            dismissAllowingStateLoss();
        }), quickLp(0));
        row.addView(quickCell(R.drawable.ic_music_note, R.string.music_tab_lyric, v -> showPanel(PANEL_LYRIC)), quickLp(dp(8)));
        row.addView(quickCell(R.drawable.ic_music_timer, R.string.music_sleep, v -> showPanel(PANEL_SLEEP)), quickLp(dp(8)));
        // P17 已核：加歌单＝回调＋关面板，宿主回调（MPA pickPlaylist）随即开加歌单面板，
        // 与瓜子「关工具面板＋开加歌单面板」一致；格子本身不内置关闭。
        row.addView(quickCell(R.drawable.ic_music_plus, R.string.music_add_to_playlist_cell, v -> {
            if (listener != null) listener.onAddToPlaylist();
            dismissAllowingStateLoss();
        }), quickLp(dp(8)));
        return row;
    }

    /**
     * 面板下载格的每次询问分支（瓜子「每次询问」：下载前逐首选档）：仅当模式为 ASK 且当前轨可下载
     * （非空、非本地、非设备歌曲）时弹选档窗，选档后直接走下载核心并关面板；选档窗取消则面板保持打开、
     * 不下载。其它模式或不可下载的轨一律返回 false，走原宿主 downloadCurrent() 路径（宿主负责
     * 本地／已下载提示）。文件大小瓜子侧逐档显示，星辰取链只回直链、无档位大小接口，故本窗只列四档名、不写大小。
     */
    private boolean askDownloadHere() {
        if (MusicStore.getDownloadMode() != MusicStore.DL_MODE_ASK) return false;
        FragmentActivity host = getActivity();
        if (host == null) return false;
        MusicTrack track = MusicController.get().current();
        if (track == null || MusicDownloadStore.isLocal(track)
                || DeviceMusicSource.PLATFORM.equals(track.platform)) return false;
        int[] values = {MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD,
                MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
        List<String> opts = new ArrayList<>();
        for (int v : values) opts.add(MusicStore.qualityName(v));
        int fixed = MusicStore.getDownloadQualityFixed();
        int checked = 1;
        for (int i = 0; i < values.length; i++) if (values[i] == fixed) checked = i;
        MusicOptionsDialog.show(host, getString(R.string.music_set_download_quality), opts, checked,
                idx -> {
                    MusicDownloadStore.downloadWithQuality(track, values[idx]);
                    dismissAllowingStateLoss();
                });
        return true;
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
        cell.setClipChildren(false);
        FrameLayout boxTile = new FrameLayout(getContext());
        boxTile.setBackgroundResource(R.drawable.bg_music_glass_quick);
        boxTile.setElevation(dp(2));
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(COLOR_TEXT));
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(dp(26), dp(26));
        ivLp.gravity = Gravity.CENTER;
        boxTile.addView(iv, ivLp);
        cell.addView(boxTile, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        TextView tv = text(getString(label), 12, COLOR_TEXT, false);
        applyWeight(tv, 700);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(8);
        cell.addView(tv, tlp);
        cell.setOnClickListener(click);
        attachPress(cell, 0.96f);
        return cell;
    }

    /** ③ 播放模式段控（GZ PlayModeSegmented T06／P18 M3Segmented）：一张白卡四段，圆角 14dp、白底、内边距 4dp；
     * 格高 48dp、卡总高 56dp（GZ 段控总高 56dp，旧 64dp 已改回）；
     * 选中态＝滑动指示器（AccentRed×0.10 ≈ #FEE9EB，250ms 滑标 left/width 动画）＋红图标红字；
     * 点击即时 MusicController.setMode 并就地更新选中态，不整面板重建，滑标动画不被重建打断 */
    private View buildModeSegment() {
        String[] labels = {getString(R.string.music_mode_seq), getString(R.string.music_mode_repeat_all),
                getString(R.string.music_mode_repeat_one), getString(R.string.music_mode_shuffle)};
        int[] icons = {R.drawable.ic_music_sequential, R.drawable.ic_music_repeat,
                R.drawable.ic_music_repeat_one, R.drawable.ic_music_shuffle};
        int[] values = {MusicQueue.MODE_SEQUENTIAL, MusicQueue.MODE_REPEAT_ALL,
                MusicQueue.MODE_REPEAT_ONE, MusicQueue.MODE_SHUFFLE};
        int cur = MusicController.get().getMode();
        int selIdx = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == cur) selIdx = i;
        FrameLayout frame = new FrameLayout(getContext());
        frame.setBackgroundResource(R.drawable.bg_music_glass_seg);
        frame.setPadding(dp(4), dp(4), dp(4), dp(4));
        View indicator = new View(getContext());
        indicator.setBackgroundResource(R.drawable.sheet_mode_selected);
        FrameLayout.LayoutParams indLp = new FrameLayout.LayoutParams(dp(0), dp(48));
        frame.addView(indicator, indLp);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        frame.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        List<ImageView> iconViews = new ArrayList<>();
        List<TextView> textViews = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean sel = i == selIdx;
            LinearLayout segCell = new LinearLayout(getContext());
            segCell.setOrientation(LinearLayout.VERTICAL);
            segCell.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(getContext());
            iv.setImageResource(icons[i]);
            iv.setImageTintList(ColorStateList.valueOf(sel ? COLOR_RED : COLOR_LOW));
            segCell.addView(iv, new LinearLayout.LayoutParams(dp(16), dp(16)));
            TextView tv = text(labels[i], 12, sel ? COLOR_RED : COLOR_LOW, sel);
            if (!sel) applyWeight(tv, 500);
            tv.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(4);
            segCell.addView(tv, tlp);
            iconViews.add(iv);
            textViews.add(tv);
            segCell.setOnClickListener(v -> {
                if (values[idx] == MusicController.get().getMode()) return;
                MusicController.get().setMode(values[idx]);
                for (int j = 0; j < labels.length; j++) {
                    boolean s = j == idx;
                    iconViews.get(j).setImageTintList(ColorStateList.valueOf(s ? COLOR_RED : COLOR_LOW));
                    textViews.get(j).setTextColor(s ? COLOR_RED : COLOR_LOW);
                    textViews.get(j).setTypeface(s ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                    if (!s) applyWeight(textViews.get(j), 500);
                }
                animateIndicator(indicator, row, idx);
            });
            attachPress(segCell, 0.96f);
            row.addView(segCell, new LinearLayout.LayoutParams(0, dp(48), 1f));
        }
        final int initialSel = selIdx;
        frame.post(() -> placeIndicator(indicator, row, initialSel));
        return frame;
    }

    /** 滑标就位（无动画）：指示器左／宽＝目标格左／宽（格在行内的 left 已含轨道内边距，FrameLayout padding 另计，不重复加） */
    private void placeIndicator(View indicator, ViewGroup row, int idx) {
        if (row == null || idx < 0 || idx >= row.getChildCount()) return;
        View cell = row.getChildAt(idx);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) indicator.getLayoutParams();
        lp.width = cell.getWidth();
        lp.height = cell.getHeight();
        lp.leftMargin = cell.getLeft();
        lp.topMargin = 0;
        indicator.setLayoutParams(lp);
    }

    /** 滑标滑动（GZ 段控指示器 tween 250ms）：left／width 从当前值插值到目标格 */
    private void animateIndicator(View indicator, ViewGroup row, int idx) {
        if (row == null || idx < 0 || idx >= row.getChildCount()) return;
        View cell = row.getChildAt(idx);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) indicator.getLayoutParams();
        int fromLeft = lp.leftMargin;
        int fromWidth = lp.width;
        int toLeft = cell.getLeft();
        int toWidth = cell.getWidth();
        if (fromLeft == toLeft && fromWidth == toWidth) return;
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(250);
        anim.setInterpolator(new PathInterpolator(0.3f, 0f, 0.1f, 1f));
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) indicator.getLayoutParams();
            p.leftMargin = Math.round(fromLeft + (toLeft - fromLeft) * f);
            p.width = Math.round(fromWidth + (toWidth - fromWidth) * f);
            p.height = cell.getHeight();
            p.topMargin = 0;
            indicator.setLayoutParams(p);
        });
        anim.start();
    }

    /** B5：设备路由懒建（建不起来＝通道未通，返回空，入口不画） */
    private MusicDeviceRouter deviceRouter() {
        if (deviceRouter == null) {
            deviceRouter = MusicDeviceRouter.create(getContext(), () -> {
                if (currentPanel == PANEL_DEVICE) mainHandler.post(() -> showPanel(PANEL_DEVICE));
            });
        }
        return deviceRouter;
    }

    /** B5：播放设备子面板——只列 MediaRouter 真实路由；当前项标「当前」，点选即切路由并刷新本面板 */
    private void buildDevicePanel() {
        LinearLayout box = binding.container;
        box.addView(subHeader(getString(R.string.music_output_device)));
        MusicDeviceRouter router = deviceRouter();
        if (router == null) {
            showPanel(PANEL_MAIN);
            return;
        }
        List<MusicDeviceRouter.Route> routes = router.routes();
        if (routes.isEmpty()) {
            TextView empty = text("暂无可用播放设备", 13, COLOR_LOW, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(14);
            box.addView(empty, lp);
            return;
        }
        for (MusicDeviceRouter.Route r : routes) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_music_glass_card);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            LinearLayout texts = new LinearLayout(getContext());
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(r.name.isEmpty() ? "未知设备" : r.name, 14, COLOR_TEXT, r.selected);
            texts.addView(name);
            if (!r.description.isEmpty()) {
                TextView desc = text(r.description, 12, COLOR_LOW, false);
                texts.addView(desc);
            }
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (r.selected) {
                TextView mark = text("当前", 12, COLOR_RED, true);
                row.addView(mark);
            }
            row.setOnClickListener(v -> {
                router.select(r);
                showPanel(PANEL_DEVICE);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            box.addView(row, lp);
        }
    }

    /** ④ 系统音量卡（GZ SystemVolumeCard T07）：白卡——滑杆区（高 48dp：喇叭＋深色填充细线滑杆＋百分比同行靠右 12sp/500）；
     * AudioManager STREAM_MUSIC 读写，拖动即时生效；填充/拇指＝AccentRed（GZ 本体图）；ContentObserver 同步外部改量。
     * 音量 0 时喇叭换 VolumeOff 图标（GZ T07 f==0 分支）。卡纵向无内边距（内容高 48dp，旧 8/8 已删）。
     * GZ 右侧「播放设备」入口（T08，B5）：通道通（MediaRouter 可建）才在行尾画投屏钮、点开设备子面板；通道未通不画。 */
    private View buildVolumeCard() {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_music_glass_card);
        card.setElevation(dp(2));
        card.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView speaker = new ImageView(getContext());
        volumeSpeaker = speaker;
        speaker.setImageResource(R.drawable.ic_widget_volume_medium);
        speaker.setImageTintList(ColorStateList.valueOf(COLOR_MEDIUM));
        row.addView(speaker, new LinearLayout.LayoutParams(dp(24), dp(24)));
        volumeBar = new SeekBar(getContext());
        volumeBar.setProgressDrawable(getContext().getDrawable(R.drawable.sheet_volume_progress));
        volumeBar.setThumb(getContext().getDrawable(R.drawable.sheet_volume_thumb));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        blp.setMarginStart(dp(12));
        blp.setMarginEnd(dp(12));
        row.addView(volumeBar, blp);
        volumePct = text("", 12, COLOR_MEDIUM, false);
        applyWeight(volumePct, 500);
        volumePct.setGravity(Gravity.END);
        row.addView(volumePct, new LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT));
        // B5：GZ T08 播放设备入口——通道通才画，点开设备子面板；通道未通不画假入口
        if (deviceRouter() != null) {
            ImageView device = new ImageView(getContext());
            device.setImageResource(R.drawable.ic_player_cast);
            device.setImageTintList(ColorStateList.valueOf(COLOR_MEDIUM));
            device.setPadding(dp(10), dp(14), dp(10), dp(14));
            device.setContentDescription(getString(R.string.music_output_device));
            device.setOnClickListener(v -> showPanel(PANEL_DEVICE));
            attachPress(device, 0.94f);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(40), dp(48));
            dlp.setMarginStart(dp(4));
            row.addView(device, dlp);
        }
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        AudioManager am = audioManager();
        if (am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            volumeBar.setMax(Math.max(1, max));
            volumeBar.setProgress(cur);
            volumePct.setText(volumePct(cur, max));
            updateVolumeIcon(cur);
            volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    AudioManager m = audioManager();
                    if (m == null) return;
                    m.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                    volumePct.setText(volumePct(progress, seekBar.getMax()));
                    updateVolumeIcon(progress);
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
        updateVolumeIcon(cur);
    }

    private void updateVolumeIcon(int cur) {
        if (volumeSpeaker == null) return;
        volumeSpeaker.setImageResource(cur == 0
                ? R.drawable.ic_sheet_volume_off : R.drawable.ic_widget_volume_medium);
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

    /** ⑤ 设置卡（GZ 工具面板设置卡原样）：2 列格（均衡器｜播放页主题／外观设置｜自定义封面）
     * ＋分割线＋不熄屏开关行。
     * 批 7 摘行：「左手布局（横屏）」行已摘——键 music_left_hand_landscape 全树无读取方（批 6 全键核对查实），
     * 是假开关；键与存量值保留不动（MusicStore 本体未动），只摘 UI 行。
     * 堵-2 恢复：歌曲信息行已加回设置卡（导航行→歌曲信息子面板，子面板代码即其去向）；
     * 瓜子对齐后主面板不再放的行：来源行（瓜子主面板没有；在歌曲信息子面板里看）、
     * 下一首播放／加入播放队列（瓜子没有；当前曲已在队列中，复拷意义小）。
     * 歌词版本与搜索歌词已收到歌词子面板，倍速已按用户令拿掉——主面板只留快捷格一个歌词入口。 */
    private View buildSettingsCard() {
        LinearLayout card = cardBox();
        MusicTrack track = MusicController.get().current();
        // GZ 设置卡顺序（ToolsMainPanel$lambda$3，2026-10-22 重做实证）：歌曲信息行 → 分隔线 →
        // 两列格（均衡器｜播放页主题／外观设置｜自定义封面）→ 分隔线 → 播放页不熄屏 →［横屏：分隔线＋左手布局］。
        // 旧版格在前、歌曲信息在后是倒置，已按瓜子重排；自定义封面按瓜子位置落次行右格——本地相册选图，
        // 不走瓜子服务器、不收费、不登录，故不做赞助锁；倍速已按用户令拿掉（瓜子没有），搜索歌词与歌词版本
        // 已收到歌词子面板，主面板只留快捷格一个歌词入口。
        // 歌曲信息行（导航行，带雪佛龙），点进歌曲信息子面板；此行是 Apple 左划的同一入口（左划直达子面板，不经过主面板）。
        addRow(card, getString(R.string.music_song_info), null, v -> showPanel(PANEL_SONG_INFO), true);
        addDivider(card);
        LinearLayout grid1 = new LinearLayout(getContext());
        grid1.setOrientation(LinearLayout.HORIZONTAL);
        grid1.addView(settingsCell(R.drawable.ic_music_eq, getString(R.string.music_equalizer), null, v -> {
            FragmentActivity host = getActivity();
            dismissAllowingStateLoss();
            if (host != null) MusicFxDialog.show(host);
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        grid1.addView(settingsCell(R.drawable.ic_music_palette, getString(R.string.music_player_theme),
                themeName(MusicPlayerSettings.getTheme()), v -> showPanel(PANEL_THEME)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(grid1);
        // A4：设置卡次行“外观设置”格（调色板图标取现有图标）＋右格“自定义封面”
        // （瓜子次行同款位置；无当前曲时右格留空占位，不建假入口）
        LinearLayout grid3 = new LinearLayout(getContext());
        grid3.setOrientation(LinearLayout.HORIZONTAL);
        grid3.addView(settingsCell(R.drawable.ic_music_palette, "外观设置", null,
                v -> showPanel(PANEL_APPEARANCE)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (track != null) {
            String customCover = MusicStore.getSongCover(MusicStore.songCoverKey(track));
            grid3.addView(settingsCell(R.drawable.ic_music_image, getString(R.string.music_custom_cover),
                    customCover != null ? getString(R.string.music_custom_cover_enabled)
                            : getString(R.string.music_custom_cover_default),
                    v -> showPanel(PANEL_CUSTOM_COVER)),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        } else {
            grid3.addView(new View(getContext()),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        card.addView(grid3);
        addDivider(card);
        card.addView(switchRow(R.drawable.ic_setting_sun, getString(R.string.music_keep_screen_on), MusicStore.isPlayerKeepScreenOn(),
                on -> MusicStore.setPlayerKeepScreenOn(on)));
        // 批 7：「左手布局（横屏）」开关行已摘（假行，见方法头注）——键 music_left_hand_landscape 保留不动。
        return card;
    }

    /** 设置卡 2 列格单格（GZ ToolsGridEntry：图标左＋文字右横向、整组左对齐占满、横向内边距 16dp、
     * 图标 20dp、标题 13sp/500、按压 0.98；定高 48dp）：副值（主题名）跟在标签下方 11sp 灰——GZ 格内无副值，
     * 星辰副值是自有状态显示，保留；纵向内边距 0＋内容垂直居中（GZ 的 12dp 纵垫配单行标签够，配星辰标签＋副值两行会夹字，故用 0＋居中） */
    private View settingsCell(int icon, String label, String sub, View.OnClickListener click) {
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(16), 0, dp(16), 0);
        cell.setMinimumHeight(dp(48));
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(COLOR_TEXT));
        cell.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView tv = text(label, 13, COLOR_TEXT, false);
        applyWeight(tv, 500);
        texts.addView(tv);
        if (sub != null && !sub.isEmpty()) {
            TextView sv = text(sub, 11, COLOR_LOW, false);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(2);
            texts.addView(sv, slp);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(8));
        cell.addView(texts, tlp);
        cell.setOnClickListener(click);
        attachPress(cell, 0.98f);
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

    // ================= 外观设置子面板（A4） =================

    /** 外观子面板（A4＋批 4）：头“外观设置”，分区“背景动效”——
     * “动态流体背景”开关（开时说明行显隐）＋“流动速度”三档（慢速／标准／快速，§0 倍率 0.6/1.0/2.5，
     * 写 MusicPlayerSettings 流体速度键、经 onAppearanceChanged 下发批 3 的 FluidCoverAmbientView.setSpeed）
     * ＋“高级动效”开关（星辰现行键 music_advanced_fx，与歌词子面板同一键、同一语义，不另造键）。
     * 批 7 摘行：「音乐律动」行已摘——键 music_rhythm 全树无读取方（批 6 全键核对查实），是假开关；
     * 键与存量值保留不动（MusicStore 本体未动），只摘 UI 行。 */
    private void buildAppearancePanel() {
        LinearLayout box = binding.container;
        box.addView(subHeader("外观设置"));
        TextView section = text("背景动效", 13, COLOR_LOW, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        box.addView(section, slp);
        LinearLayout card = cardBox();
        LinearLayout fluidSub = new LinearLayout(getContext());
        fluidSub.setOrientation(LinearLayout.VERTICAL);
        fluidSub.setPadding(dp(16), 0, dp(16), dp(12));
        TextView fluidNote = text("开启后播放页背景改为流体氛围（封面取色，仍按时间正弦漂移）", 12, COLOR_LOW, false);
        fluidSub.addView(fluidNote);
        fluidSub.setVisibility(MusicPlayerSettings.isFluidBg() ? View.VISIBLE : View.GONE);
        card.addView(switchRow("动态流体背景", MusicPlayerSettings.isFluidBg(), on -> {
            MusicPlayerSettings.setFluidBg(on);
            fluidSub.setVisibility(on ? View.VISIBLE : View.GONE);
            if (listener != null) listener.onAppearanceChanged();
        }));
        card.addView(fluidSub);
        addDivider(card);
        card.addView(switchRow(getString(R.string.music_advanced_fx), MusicStore.isAdvancedFx(), on -> {
            MusicStore.setAdvancedFx(on);
            fireLyricChanged();
        }));
        // 批 7：「音乐律动」行已摘（假行，见方法头注）。
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        box.addView(card, clp);
        // 流动速度三档（批 4 接批 3 setSpeed）：段控写键后通知播放页 applyFluidBg 重下发倍率，并重建本面板对齐选中态
        box.addView(lyricSegRow(getString(R.string.music_fluid_speed),
                new String[]{getString(R.string.music_speed_slow), getString(R.string.music_speed_normal),
                        getString(R.string.music_speed_fast)},
                MusicPlayerSettings.getFluidSpeed(), idx -> {
                    MusicPlayerSettings.setFluidSpeed(idx);
                    if (listener != null) listener.onAppearanceChanged();
                    showPanel(PANEL_APPEARANCE);
                }), marginTop(dp(14)));
    }

    // ================= 自定义封面子面板（瓜子 CustomCoverPanel 同款结构，本地图片版） =================

    /** 自定义封面子面板：头“自定义封面”＋预览＋状态行＋“封面来源”卡（从相册选择图片／恢复默认封面）。
     * 瓜子本体：本地相册选图／选视频当播放页大封面（视频另作模糊背景动效），图片与视频互斥；
     * 星辰只做图片（视频动态封面需主题层视频面＋模糊视频背景管线，另批再做，不做假入口）。
     * 赞助锁不做：瓜子是赞助版功能锁（未赞助格子禁用＋“需要赞助版”），星辰无赞助／付费／登录，本地功能直接可用。 */
    private void buildCustomCoverPanel() {
        LinearLayout box = binding.container;
        box.addView(subHeader(getString(R.string.music_custom_cover)));
        MusicTrack track = MusicController.get().current();
        if (track == null) {
            box.addView(text(getString(R.string.music_no_track), 13, COLOR_LOW, false));
            return;
        }
        String key = MusicStore.songCoverKey(track);
        String custom = MusicStore.getSongCover(key);
        boolean hasCustom = custom != null && !custom.isEmpty();
        // 预览：自定义图 > 原封面；都没图时 ImgUtil 空地址分支上素图
        ImageView preview = new ImageView(getContext());
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            preview.setClipToOutline(true);
            preview.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
                }
            });
        }
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(192));
        plp.topMargin = dp(12);
        box.addView(preview, plp);
        String previewUrl = hasCustom ? custom : (track.cover == null ? "" : track.cover);
        ImgUtil.load(track.displayTitle(), previewUrl, preview);
        TextView status = text(hasCustom ? getString(R.string.music_custom_cover_current_custom)
                : getString(R.string.music_custom_cover_current_default),
                13, hasCustom ? COLOR_RED : COLOR_TEXT, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        box.addView(status, slp);
        TextView section = text("封面来源", 13, COLOR_LOW, false);
        LinearLayout.LayoutParams ssp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ssp.topMargin = dp(12);
        box.addView(section, ssp);
        LinearLayout card = cardBox();
        addRow(card, getString(R.string.music_custom_cover_pick), null,
                v -> pickCoverLauncher.launch("image/*"), true);
        if (hasCustom) {
            addDivider(card);
            addRow(card, getString(R.string.music_custom_cover_restore), null,
                    v -> onCustomCoverRestore(), false);
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        box.addView(card, clp);
        TextView note = text(getString(R.string.music_custom_cover_local_only), 12, COLOR_LOW, false);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(8);
        box.addView(note, nlp);
    }

    /**
     * 相册选图回执：拷到应用文件目录 custom_covers/，按歌曲键存 file:// 地址，刷新播放页封面与背景。
     * 先拷临时文件、成功后再替换旧图和映射：旧写法先删旧图再拷，拷失败（流为空／中途异常）
     * 旧图就丢了、映射还指已删文件；现在拷失败只清临时文件，旧图和映射一律不动。
     * 正式文件名永不等于临时文件名：临时名本身带时间戳（每选一次都不一样），改名失败时
     * 把临时文件内容再拷一份到带时间戳的正式文件——失败分支只删真正的临时文件，
     * 不碰已生效文件；旧拷图只在新正式文件落盘后才清（拷失败时旧图和映射原样保留）。
     */
    private void onCustomCoverPicked(Uri uri) {
        if (uri == null) return;
        FragmentActivity host = getActivity();
        MusicTrack track = MusicController.get().current();
        if (host == null || track == null) return;
        String key = MusicStore.songCoverKey(track);
        if (key == null) return;
        File tmp = null;
        try {
            String type = host.getContentResolver().getType(uri);
            String ext = "image/png".equals(type) ? "png" : "image/webp".equals(type) ? "webp" : "jpg";
            File dir = new File(host.getFilesDir(), "custom_covers");
            if (!dir.exists()) dir.mkdirs();
            String base = String.valueOf(Math.abs(key.hashCode()));
            // 1. 先拷临时文件（名带时间戳，每选一次都不一样，永不等于正式文件名；
            // 旧临时残留由第 3 步的旧文件清理顺带清掉）。拷失败走 catch，只清它。
            tmp = new File(dir, base + "_tmp_" + System.currentTimeMillis() + "." + ext);
            try (InputStream in = host.getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(tmp)) {
                if (in == null) throw new java.io.IOException("null stream");
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            if (tmp.length() <= 0) throw new java.io.IOException("empty copy");
            // 2. 产生正式文件（带时间戳，地址变了 Glide 才不会按旧地址缓存旧图）：
            // 改名成功直接用；改名失败把临时文件内容再拷一份到正式文件——
            // 正式文件与临时文件永不相等，失败分支删临时文件不会伤到已生效文件。
            File out = new File(dir, base + "_" + System.currentTimeMillis() + "." + ext);
            if (!tmp.renameTo(out)) {
                try (InputStream in = new FileInputStream(tmp);
                     OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                if (out.length() <= 0) throw new java.io.IOException("rename fallback copy failed");
            }
            // 3. 新正式文件落盘后才替换：清旧拷图（同歌旧文件名前缀 base_ 或
            // base.jpg/png/webp 全清，临时文件与新正式文件本身不删），再写映射、
            // 删临时文件。拷失败在第 1／2 步就抛了，到不了这里，旧图和映射不动。
            File[] olds = dir.listFiles();
            if (olds != null) {
                for (File f : olds) {
                    String n = f.getName();
                    if (!f.equals(tmp) && !f.equals(out) && (n.startsWith(base + "_")
                            || n.equals(base + ".jpg")
                            || n.equals(base + ".png") || n.equals(base + ".webp"))) f.delete();
                }
            }
            MusicStore.setSongCover(key, Uri.fromFile(out).toString());
            tmp.delete();
            tmp = null;
            refreshPlayerCover();
            showPanel(PANEL_CUSTOM_COVER);
        } catch (Throwable ignored) {
            if (tmp != null) tmp.delete();
            Notify.show(R.string.music_custom_cover_failed);
        }
    }

    /** 恢复默认封面：清映射＋删本地拷图（尽力），刷新播放页 */
    private void onCustomCoverRestore() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        String key = MusicStore.songCoverKey(track);
        String old = MusicStore.getSongCover(key);
        MusicStore.setSongCover(key, null);
        if (old != null && !old.isEmpty()) {
            try {
                File f = new File(Uri.parse(old).getPath());
                if (f.exists()) f.delete();
            } catch (Throwable ignored) {
            }
        }
        refreshPlayerCover();
        showPanel(PANEL_CUSTOM_COVER);
    }

    private void refreshPlayerCover() {
        FragmentActivity host = getActivity();
        if (host instanceof MusicPlayerHost) ((MusicPlayerHost) host).refreshCurrentCover();
    }

    // ================= 主题子面板 =================

    private void buildThemePanel() {
        LinearLayout box = binding.container;
        box.addView(subHeader(getString(R.string.music_player_theme)));
        TextView desc = text(getString(R.string.sheet_theme_desc), 11, COLOR_LOW, false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(4);
        box.addView(desc, dlp);
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        // 批 4：主题读写走 MusicPlayerSettings（七 id 同键 music_player_theme）——MusicStore.setPlayerTheme
        // 只认五 id、会把 CD 深／自动写成 classic，不许再经它写；选中即写键＋回调播放页 applyTheme 刷新。
        String cur = MusicPlayerSettings.getTheme();
        for (int i = 0; i < THEME_IDS.length; i++) {
            final String id = THEME_IDS[i];
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(12));
            row.addView(themeCard(id, THEME_NAMES[i], THEME_COLORS[i], THEME_LAYOUTS[i], id.equals(cur), v -> {
                MusicPlayerSettings.setTheme(id);
                // 存量表面键同步（设置页批 6 前仍读 music_cd_surface）：CD 三 id 落对应表面档，其余主题不动此键
                if (MusicPlayerSettings.THEME_LUMINOUS_CD.equals(id)) MusicStore.setCdSurface(0);
                else if (MusicPlayerSettings.THEME_LUMINOUS_CD_DARK.equals(id)) MusicStore.setCdSurface(1);
                else if (MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO.equals(id)) MusicStore.setCdSurface(2);
                if (listener != null) listener.onThemeChanged();
                showPanel(PANEL_THEME);
            }), lp);
        }
        scroll.addView(row);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(14);
        box.addView(scroll, slp);
    }

    /** 主题卡（ThemePanel 数值照 §7.2：卡宽 112dp 由调用方给、流式间距 12dp 同、卡圆角 16dp、描边 2dp、
     * 主题名 12sp、选中底色动画 150ms、按压 0.97；无 Pro 锁——定案全免费）：
     * 预览区是真预览图——宽＝卡内宽 96dp、高＝96/0.46075085≈208dp（GZ 真预览图宽高比实读），
     * 内容为该主题布局的缩略渲染（见 renderThemePreview），垫底是主题自身表面色；
     * 渲染失败才回落代表色渐变（色板），不拿无关图充数。选中卡底 150ms 渐变到浅粉 #F8E1E3＋红描边红勾。 */
    private View themeCard(String id, int nameRes, int[] colors, int layoutRes, boolean selected, View.OnClickListener click) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(8), dp(9), dp(8), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB3FFFFFF);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(2), COLOR_BORDER);
        if (selected) bg.setStroke(dp(2), COLOR_RED);
        card.setBackground(bg);
        ImageView preview = new ImageView(getContext());
        preview.setScaleType(ImageView.ScaleType.FIT_XY);
        GradientDrawable grad = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        grad.setCornerRadius(dp(8));
        preview.setBackground(grad);
        preview.setClipToOutline(true);
        preview.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(8));
            }
        });
        android.graphics.Bitmap thumb = themePreview(id, layoutRes);
        if (thumb != null) preview.setImageBitmap(thumb);
        int previewH = dp(Math.round(96f / THEME_PREVIEW_ASPECT));
        card.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewH));
        TextView name = text(getString(nameRes), 12, COLOR_TEXT, selected);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(8);
        card.addView(name, nlp);
        TextView check = text("✓", 13, COLOR_RED, true);
        check.setGravity(Gravity.CENTER);
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        card.addView(check, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));
        card.setContentDescription(getString(nameRes));
        card.setOnClickListener(click);
        attachPress(card, 0.97f);
        if (selected) {
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofObject(
                    new android.animation.ArgbEvaluator(), 0xB3FFFFFF, 0xFFF8E1E3);
            anim.setDuration(150);
            anim.addUpdateListener(a -> bg.setColor((int) a.getAnimatedValue()));
            anim.start();
        }
        return card;
    }

    /** 主题真预览：把该主题的播放页布局离屏 measure/layout/draw 成位图（2 倍密度渲染后由 ImageView 缩放显示）。
     * CD 深色卡先把布局内的 CdDiscView 切深色表面（只调主题视图既有 setter、本批不改主题视图文件）；
     * 自动卡按系统夜间态定表面。封面／歌词等动态内容离屏无数据，缩略呈现的是主题布局结构本身；
     * 任一步异常返回空，卡回落代表色渐变。结果按主题 id 在本面板实例内缓存。 */
    private android.graphics.Bitmap themePreview(String id, int layoutRes) {
        if (themePreviewCache.containsKey(id)) return themePreviewCache.get(id);
        android.graphics.Bitmap bmp = null;
        try {
            Context ctx = getContext();
            if (ctx != null) {
                View root = LayoutInflater.from(ctx).inflate(layoutRes, null, false);
                boolean dark = MusicPlayerSettings.THEME_LUMINOUS_CD_DARK.equals(id)
                        || (MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO.equals(id) && isSystemNight());
                if (dark) applyCdDarkSurface(root);
                int w = dp(192);
                int h = Math.round(w / THEME_PREVIEW_ASPECT);
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, w, h);
                bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                root.draw(new android.graphics.Canvas(bmp));
            }
        } catch (Throwable ignored) {
            bmp = null;
        }
        themePreviewCache.put(id, bmp);
        return bmp;
    }

    private void applyCdDarkSurface(View view) {
        if (view instanceof CdDiscView) ((CdDiscView) view).setDarkSurface(true);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyCdDarkSurface(group.getChildAt(i));
        }
    }

    private boolean isSystemNight() {
        try {
            Context ctx = getContext();
            if (ctx == null) return false;
            int night = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String themeName(String id) {
        for (int i = 0; i < THEME_IDS.length; i++) {
            if (THEME_IDS[i].equals(id)) return getString(THEME_NAMES[i]);
        }
        return getString(R.string.music_theme_classic);
    }

    // ================= 定时子面板 =================

    /** 睡眠子面板（GZ Sleep 网格形态）：状态行（18sp/800/高字）＋预设 3 列网格＋「播完本首再停」开关
     * ＋「本首结束」chip＋自定义分钟行＋独立「关闭」钮；
     * 档位＝瓜子预设（10/20/30/40/60/90）＋关／播完这首／自定义分钟（服务侧任意分钟计时链，见 MusicPlaybackService 编码档）；
     * 状态行文案按 GZ 剩余标签：未开启／本首结束后停止／定时仅 m:ss（无档名前缀）。
     * D3：分钟档＋播完再停是一组（开关存 MusicStore，服务到点时现读），与 SLEEP_AFTER_CURRENT 独立模式
     * 二选一：开播完再停时若正处独立模式，先关独立模式；点独立模式时清播完再停开关。开关是偏好，
     * 关定时不清除偏好，下次选分钟档仍带播完再停（再点开关可关）。 */
    private void buildSleepPanel() {
        LinearLayout box = binding.container;
        box.addView(subHeader(getString(R.string.music_sleep)));
        int cur = MusicController.get().getSleep();
        String statusText;
        if (cur == MusicPlaybackService.SLEEP_OFF) {
            statusText = getString(R.string.sheet_sleep_not_on);
        } else if (cur == MusicPlaybackService.SLEEP_AFTER_CURRENT) {
            statusText = "本首结束后停止";
        } else {
            long remain = MusicController.get().sleepRemainingMs();
            if (remain >= 0) {
                // D5：剩余秒向下取整（瓜子 SleepTimerState remainingMs/1000），旧向上取整可差 1 秒。
                long sec = remain / 1000;
                statusText = (sec / 60) + ":" + String.format("%02d", sec % 60);
            } else if (MusicController.get().isSleepWaitingForSongEnd()) {
                // D3 等待态：分钟档已到点、剩余归 -1，档位仍是分钟档——此时显示等待文案，不是档名。
                // 第二波 B4：此处改读服务侧等待态真值，不再只凭开关推断；等待态为 false 时落到下一分支，
                // 哪怕开关事后又被打开，计时任务已跑完、曲终也不会停，同样不显示等待文案。
                statusText = "本首结束后停止";
            } else {
                // 第二波 B4：计时已到点、等待态已被清掉（关开关致定时彻底失效），档位虽仍是分钟档，
                // 但已无任何会停播的定时在跑，显示“未开启”而不是档名，避免档名暗示定时还在走。
                statusText = getString(R.string.sheet_sleep_not_on);
            }
        }
        TextView status = text(statusText, 18, COLOR_TEXT, false);
        applyWeight(status, 800);
        status.setLetterSpacing(-0.017f);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = dp(14);
        box.addView(status, stlp);
        // D4：睡眠预设删 15 分钟档（瓜子 SleepPresets＝10／20／30／40／60／90）；服务侧 SLEEP_15 常量未删，
        // 仅面板不再提供该档（旧存档 15 分钟档状态行仍按档名显示，不另清）。
        int[] gridValues = {MusicPlaybackService.SLEEP_10,
                MusicPlaybackService.SLEEP_20, MusicPlaybackService.SLEEP_30,
                MusicPlaybackService.SLEEP_40, MusicPlaybackService.SLEEP_60,
                MusicPlaybackService.SLEEP_90};
        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < 3; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                int pos = r * 3 + c;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (c > 0) lp.setMarginStart(dp(6));
                if (pos >= gridValues.length) {
                    row.addView(new View(getContext()), lp);
                    continue;
                }
                final int value = gridValues[pos];
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
        // D3：分钟档“播完本首再停”开关（只改睡眠面板与模式相关行，不动歌词字号滑杆）。
        // 开着时分钟档／自定义档到点不暂停、改记等待态，曲终才停（服务 armSleep 到点现读本开关）。
        View finishCell = sleepCell("播完本首再停", MusicStore.isSleepFinishCurrent(), v -> {
            boolean next = !MusicStore.isSleepFinishCurrent();
            MusicStore.setSleepFinishCurrent(next);
            // 第二波 B4：关开关时若已在等待态，服务侧同步清等待态（定时彻底失效，曲终不再停播）。
            // 服务已绑定时 runOnService 同步执行，清完再重建面板，状态行读到的等待态即最新值。
            if (!next) MusicController.get().clearSleepWaitingForSongEnd();
            if (next && MusicController.get().getSleep() == MusicPlaybackService.SLEEP_AFTER_CURRENT) {
                MusicController.get().setSleep(MusicPlaybackService.SLEEP_OFF);
            }
            showPanel(PANEL_SLEEP);
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(12);
        box.addView(finishCell, flp);
        View chip = sleepCell(MusicPlaybackService.sleepName(MusicPlaybackService.SLEEP_AFTER_CURRENT),
                cur == MusicPlaybackService.SLEEP_AFTER_CURRENT, v -> {
                    // 二选一：点独立模式时清分钟档播完再停开关，不许两组同时生效。
                    MusicStore.setSleepFinishCurrent(false);
                    MusicController.get().setSleep(MusicPlaybackService.SLEEP_AFTER_CURRENT);
                    showPanel(PANEL_MAIN);
                });
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chlp.topMargin = dp(12);
        box.addView(chip, chlp);
        // 自定义分钟行（GZ 自定义 chip＋分钟输入＋开启钮）：分钟数经 MusicController.setSleep 以编码档传服务，服务侧按任意分钟计时
        LinearLayout customRow = new LinearLayout(getContext());
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView customLabel = text("自定义", 14, COLOR_TEXT, false);
        customRow.addView(customLabel);
        EditText customInput = new EditText(getContext());
        customInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        customInput.setHint("分钟");
        customInput.setTextSize(14);
        customInput.setTextColor(COLOR_TEXT);
        customInput.setHintTextColor(COLOR_LOW);
        customInput.setBackgroundResource(R.drawable.bg_sheet_seg_track);
        customInput.setPadding(dp(12), 0, dp(12), 0);
        if (cur >= MusicPlaybackService.SLEEP_CUSTOM_MINUTES_BASE) {
            // 已是自定义档时回填原值
            customInput.setText(String.valueOf(cur - MusicPlaybackService.SLEEP_CUSTOM_MINUTES_BASE));
        } else {
            // D5：非自定义档时自定义框默认填 45（瓜子自定义分钟输入初始值 "45"）
            customInput.setText("45");
        }
        LinearLayout.LayoutParams cilp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        cilp.setMarginStart(dp(12));
        cilp.setMarginEnd(dp(12));
        customRow.addView(customInput, cilp);
        TextView customGo = text("开启", 14, COLOR_RED, true);
        customGo.setGravity(Gravity.CENTER);
        customGo.setPadding(dp(16), 0, dp(16), 0);
        customGo.setOnClickListener(v -> {
            int minutes = 0;
            try {
                minutes = Integer.parseInt(customInput.getText().toString().trim());
            } catch (Throwable ignored) {
            }
            if (minutes < 1 || minutes > 999) return;
            MusicController.get().setSleep(
                    MusicPlaybackService.SLEEP_CUSTOM_MINUTES_BASE + minutes);
            showPanel(PANEL_MAIN);
        });
        attachPress(customGo, 0.96f);
        customRow.addView(customGo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)));
        LinearLayout.LayoutParams crlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crlp.topMargin = dp(12);
        box.addView(customRow, crlp);
        // 独立「关闭」钮（GZ：12sp/700、内边距 12/7dp，卡底居中）
        TextView closeBtn = text(MusicPlaybackService.sleepName(MusicPlaybackService.SLEEP_OFF), 12, COLOR_TEXT, false);
        applyWeight(closeBtn, 700);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setBackgroundResource(R.drawable.bg_music_glass_card);
        closeBtn.setElevation(dp(2));
        closeBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
        closeBtn.setOnClickListener(v -> {
            MusicController.get().setSleep(MusicPlaybackService.SLEEP_OFF);
            showPanel(PANEL_MAIN);
        });
        attachPress(closeBtn, 0.96f);
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cblp.topMargin = dp(16);
        cblp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(closeBtn, cblp);
    }

    /** 歌曲信息子面板（GZ 设置卡「歌曲信息」导航行的去向）：只展示当前轨已有数据（歌名／歌手／专辑／来源），无数据项不画，不自创信息 */
    private void buildSongInfoPanel() {
        LinearLayout box = binding.container;
        box.addView(subHeader(getString(R.string.music_song_info)));
        MusicTrack track = MusicController.get().current();
        if (track == null) {
            TextView none = text(getString(R.string.music_no_track), 14, COLOR_LOW, false);
            box.addView(none, marginTop(dp(16)));
            return;
        }
        LinearLayout card = cardBox();
        boolean first = true;
        addInfoRow(card, "歌名", track.displayTitle(), first);
        first = false;
        String artistLine = track.displayArtist();
        if (artistLine == null || artistLine.isEmpty()) artistLine = getString(R.string.music_unknown_artist);
        addInfoRow(card, getString(R.string.music_info_artist), artistLine, first);
        if (!TextUtils.isEmpty(track.album)) {
            addInfoRow(card, getString(R.string.music_info_album), track.album, false);
        }
        String source = sourceName(track);
        if (!source.isEmpty()) {
            addInfoRow(card, getString(R.string.music_info_source), source, false);
        }
        box.addView(card, marginTop(dp(16)));
    }

    /** 定时格/chip：未选 inset 底深字，选中浅粉底红字（GZ 红系选中口径） */
    private View sleepCell(String label, boolean selected, View.OnClickListener click) {
        TextView tv = text(label, 12, selected ? COLOR_RED : COLOR_TEXT, selected);
        tv.setPadding(0, dp(7), 0, dp(7));
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(selected ? R.drawable.sheet_cell_selected : R.drawable.bg_sheet_seg_track);
        tv.setOnClickListener(click);
        attachPress(tv, 0.96f);
        return tv;
    }

    // ================= 歌词设置子面板 =================

    private void buildLyricPanel() {
        // 瓜子歌词面板无翻译／音译开关行：唯一歌词类型控件是主歌词三 chip 单选（本面板 music_lyric_primary 行，
        // GZ CoverLyricStylePanel「主歌词」M3 段控：原文恒有＋翻译／音译有数据时出）；副行在瓜子常态下有数据即显。
        // 故两开关行删掉，卡内只留高级动效行；此前存过关的副行偏好在此一次性回到全显并通知播放页，
        // 否则关过的值将再无入口改回（MusicStore 两键与 setter 保留，供回滚与播放页读取；动态选项门——
        // 瓜子无数据档不出 chip——未做：面板歌词数据异步到达，建面板时无数据可门控，见分表第三轮节）。
        boolean subReset = !MusicStore.isShowTranslation() || !MusicStore.isShowRomanization();
        if (subReset) {
            MusicStore.setShowTranslation(true);
            MusicStore.setShowRomanization(true);
        }
        LinearLayout box = binding.container;
        box.addView(subHeader(getString(R.string.music_lyric_settings)));
        // 歌词来源卡（主面板只留快捷格一个歌词入口，版本与搜索收到歌词子面板内——
        // 瓜子同款结构：版本筛选条 LyricVersionFilterBar 与搜索 searchLyricsForSelection 都在歌词页内，不在工具面板；
        // 多版本功能本身保留，入口挪位不丢功能）
        LinearLayout lyricSrcCard = cardBox();
        boolean firstSrc = true;
        FragmentActivity lyricHost = getActivity();
        // 第三波残留 L9：工具面板版本行 >1 版限制放开（与播放页内版本入口一致：
        // 播放页 openLyricVersionDialog 在 0／1 版时也允许开面板、内有空态文案＋搜索入口）；
        // 0／1 版时行副文改“1 个版本”／“暂无版本”，点进同样开面板，不再藏行。
        if (lyricHost instanceof MusicPlayerHost) {
            int versionCount = ((MusicPlayerHost) lyricHost).getLyricVersionCount();
            addRow(lyricSrcCard, getString(R.string.music_lyric_versions),
                    versionCount <= 0 ? "暂无版本" : versionCount + " 个版本", v -> {
                        dismissAllowingStateLoss();
                        new Handler(Looper.getMainLooper()).post(
                                () -> ((MusicPlayerHost) lyricHost).openLyricVersionDialog());
                    }, true);
            firstSrc = false;
        }
        addRow(lyricSrcCard, getString(R.string.music_search_lyric), null, v -> {
            if (listener != null) listener.onSearchLyric();
            dismissAllowingStateLoss();
        }, firstSrc);
        box.addView(lyricSrcCard, marginTop(dp(12)));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(160));
        previewLp.topMargin = dp(12);
        box.addView(buildLyricPreview(), previewLp);
        // L5：字号双滑杆——全屏行（16–40）＋封面行（14–32），分别复位见恢复默认钮。
        box.addView(buildLyricSizeRow(false), marginTop(dp(16)));
        box.addView(buildLyricSizeRow(true), marginTop(dp(12)));
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
        // 批 4 补：模糊三档（关闭／轻度／中度，§7.2 歌词设置构成）——此前本面板无此行，模糊只能在设置页改；
        // 写存量键 music_cover_lyric_blur（全屏与封面共用，MusicPlayerSettings.getCoverLyricBlurLevel 同键），
        // fireLyricChanged 后播放页 applyLyricStyle 即时下发内嵌＋全屏＋Apple 歌词页。
        box.addView(lyricSegRow(getString(R.string.music_lyric_blur),
                new String[]{getString(R.string.music_blur_off), getString(R.string.music_blur_light),
                        getString(R.string.music_blur_medium)},
                MusicStore.getCoverLyricBlurLevel(), idx -> {
                    MusicStore.setCoverLyricBlurLevel(idx);
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
        // 主歌词不可用提示（GZ T32 四态：10sp、alpha 0.5、低字 #9A9AA0）：歌词数据到达后按四态拼行
        lyricHint = text("", 10, COLOR_LOW, false);
        lyricHint.setAlpha(0.5f);
        lyricHint.setVisibility(View.GONE);
        box.addView(lyricHint, marginTop(dp(8)));
        LinearLayout card = cardBox();
        // 高级动效行高 60dp（GZ 歌词设置行高口径）；开启时弹确认框（GZ T33 文案照抄）
        card.addView(switchRow(getString(R.string.music_advanced_fx), MusicStore.isAdvancedFx(), 60,
                on -> {
                    MusicStore.setAdvancedFx(on);
                    fireLyricChanged();
                    if (on) showAdvancedFxConfirm();
                }));
        box.addView(card, marginTop(dp(16)));
        // 恢复默认钮高 28dp（L5：全屏字号 24／封面字号 22 分别复位；其余粗细／对齐口径不变）
        TextView restore = text(getString(R.string.music_restore_defaults), 13, COLOR_DARK, false);
        restore.setGravity(Gravity.CENTER);
        restore.setBackgroundResource(R.drawable.bg_music_glass_card);
        restore.setElevation(dp(2));
        restore.setOnClickListener(v -> {
            MusicStore.setLyricFontSize(24f);
            MusicStore.setCoverLyricFontSize(22f);
            MusicStore.setLyricWeight(MusicStore.LYRIC_WEIGHT_NORMAL);
            MusicStore.setLyricAlign(MusicStore.LYRIC_ALIGN_LEFT);
            fireLyricChanged();
            showPanel(PANEL_LYRIC);
        });
        attachPress(restore, 0.96f);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        rlp.topMargin = dp(16);
        box.addView(restore, rlp);
        loadLyricForPanel();
        if (subReset) fireLyricChanged();
    }

    private void showAdvancedFxConfirm() {
        if (!isAdded() || getContext() == null) return;
        new MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.sheet_advanced_fx_title)
                .setMessage(R.string.sheet_advanced_fx_msg)
                .setPositiveButton(R.string.sheet_got_it, null)
                .show();
    }

    /** 顶部预览区（GZ T26）：歌名大字＋当前行歌词样例，歌词行按当前样式实时渲染（500ms 跟随播放进度）；
     * 旧版只有歌词行、没有歌名大字——GZ 预览是“歌名大字卡＋歌词行样例”，此处补歌名（当前曲名，28sp 粗体，
     * GZ 标题确切字号未抠到）；歌词行样式逻辑不动。 */
    private View buildLyricPreview() {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundResource(R.drawable.bg_music_glass_preview);
        column.setElevation(dp(2));
        column.setPadding(dp(14), dp(8), dp(14), dp(8));
        MusicTrack track = MusicController.get().current();
        TextView title = text(track == null ? getString(R.string.music_no_track) : track.displayTitle(),
                28, COLOR_TEXT, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricPreview = text(getString(R.string.music_lyric_preview_sample), 22, COLOR_TEXT, false);
        lyricPreview.setMaxLines(2);
        lyricPreview.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(4);
        column.addView(lyricPreview, lp);
        applyPreview();
        return column;
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

    /** 字号滑杆（L5 双键，GZ T27：封面歌词 14–32、全屏 16–40，取整、两端 A 示意字 11sp/16sp）；
     * cover＝false 走全屏键（默认 24、16–40），true 走封面键（默认 22、14–32）；面板预览只跟全屏行，
     * 封面行拖动只改数值 label、松手后经 fireLyricChanged 刷播放页内嵌字号。 */
    private View buildLyricSizeRow(boolean cover) {
        final float min = cover ? 14f : 16f;
        final float max = cover ? 32f : 40f;
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(cover ? "封面字号" : getString(R.string.music_lyric_font_size), 14, COLOR_TEXT, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        float cur0 = cover ? MusicStore.getCoverLyricFontSize() : MusicStore.getLyricFontSize();
        final TextView valueView = text(String.valueOf(Math.round(cur0)), 11, COLOR_LOW, false);
        if (!cover) lyricSizeValue = valueView;
        head.addView(valueView);
        row.addView(head);
        LinearLayout sliderRow = new LinearLayout(getContext());
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView smallA = text("A", 11, COLOR_LOW, false);
        sliderRow.addView(smallA);
        SeekBar bar = new SeekBar(getContext());
        bar.setProgressTintList(ColorStateList.valueOf(COLOR_RED));
        bar.setThumbTintList(ColorStateList.valueOf(COLOR_RED));
        bar.setMax(Math.round(max - min));
        float cur = Math.max(min, Math.min(max, cur0));
        bar.setProgress(Math.round(cur) - Math.round(min));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                float v = progress + min;
                if (!cover && lyricPreview != null) lyricPreview.setTextSize(v);
                valueView.setText(String.valueOf(Math.round(v)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (cover) MusicStore.setCoverLyricFontSize(seekBar.getProgress() + min);
                else MusicStore.setLyricFontSize(seekBar.getProgress() + min);
                float stored = cover ? MusicStore.getCoverLyricFontSize() : MusicStore.getLyricFontSize();
                valueView.setText(String.valueOf(Math.round(stored)));
                if (!cover) applyPreview();
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
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelView = text(label, 13, COLOR_MEDIUM, false);
        applyWeight(labelView, 600);
        row.addView(labelView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(12));
        row.addView(segment(options, selected, onPick), lp);
        row.setMinimumHeight(dp(48));
        return row;
    }

    private void fireLyricChanged() {
        if (listener != null) listener.onLyricStyleChanged();
    }

    // ================= 通用构建件 =================

    /** 子面板头（GlassSheetPanelKt SheetSubPanelHeader）：高 44dp，左圆形钮 40dp（20dp 矢量 ‹ 图标，按压缩放 0.9，回主面板）
     * ＋标题 17sp/800（字距 −0.3sp）＋右圆形钮 40dp（18dp ✕ 图标，按压缩放 0.9，关面板） */
    private View subHeader(String title) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        ImageView back = new ImageView(getContext());
        back.setImageResource(R.drawable.ic_music_chevron_left);
        back.setImageTintList(ColorStateList.valueOf(COLOR_TEXT));
        back.setPadding(dp(10), dp(10), dp(10), dp(10));
        back.setBackgroundResource(R.drawable.bg_music_glass_circle);
        back.setContentDescription(getString(R.string.music_back));
        back.setOnClickListener(v -> showPanel(PANEL_MAIN));
        attachPress(back, 0.9f);
        row.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView tv = text(title, 17, COLOR_TEXT, true);
        applyWeight(tv, 800);
        tv.setLetterSpacing(-0.0176f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(8));
        row.addView(tv, tlp);
        ImageView close = new ImageView(getContext());
        close.setImageResource(R.drawable.ic_music_close);
        close.setImageTintList(ColorStateList.valueOf(COLOR_MEDIUM));
        close.setPadding(dp(11), dp(11), dp(11), dp(11));
        close.setBackgroundResource(R.drawable.bg_music_glass_circle);
        close.setContentDescription(getString(R.string.music_close));
        close.setOnClickListener(v -> dismissAllowingStateLoss());
        attachPress(close, 0.9f);
        row.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        return row;
    }

    /** 通用段控（粗细/对齐/模糊/主歌词/CD 表面共用）：轨道 inset 底、格高 40dp；
     * 选中态＝滑动指示器（AccentRed×0.10 ≈ #FEE9EB，250ms 滑标 left/width 动画），选中文字主字加粗、其余中字；
     * 点击先就地滑标＋换色，250ms 后才执行 onPick（含子面板重建），滑标动画不被重建打断 */
    private LinearLayout segment(String[] labels, int selected, IntConsumer onPick) {
        // 外层仍返回 LinearLayout 口径（调用方按 LinearLayout.LayoutParams 布局）：内含 FrameLayout 滑标层
        LinearLayout wrap = new LinearLayout(getContext());
        wrap.setOrientation(LinearLayout.VERTICAL);
        FrameLayout frame = new FrameLayout(getContext());
        frame.setBackgroundResource(R.drawable.bg_sheet_seg_track);
        frame.setPadding(dp(3), dp(3), dp(3), dp(3));
        View indicator = new View(getContext());
        indicator.setBackgroundResource(R.drawable.sheet_mode_selected);
        frame.addView(indicator, new FrameLayout.LayoutParams(dp(0), dp(40)));
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        frame.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        List<TextView> cells = new ArrayList<>();
        final Runnable[] pending = new Runnable[1];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean sel = i == selected;
            TextView tv = text(labels[i], 13, sel ? COLOR_TEXT : COLOR_LOW, sel);
            tv.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            if (i > 0) lp.setMarginStart(dp(3));
            cells.add(tv);
            tv.setOnClickListener(v -> {
                if (idx == selected) return;
                for (int j = 0; j < labels.length; j++) {
                    boolean s = j == idx;
                    cells.get(j).setTextColor(s ? COLOR_TEXT : COLOR_LOW);
                    cells.get(j).setTypeface(s ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                }
                animateIndicator(indicator, row, idx);
                if (pending[0] != null) mainHandler.removeCallbacks(pending[0]);
                // crash 项：延迟写入带存活守卫并登记，面板销毁时 onDestroyView 统一取消（旧版只靠 pending[0] 去重，
                // 销毁后回调仍执行 onPick／动 binding）。
                pending[0] = () -> {
                    if (!isAdded() || getContext() == null) return;
                    onPick.accept(idx);
                };
                segmentPendings.add(pending[0]);
                mainHandler.postDelayed(pending[0], 250);
            });
            attachPress(tv, 0.96f);
            row.addView(tv, lp);
        }
        wrap.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final int initialSel = selected;
        frame.post(() -> placeIndicator(indicator, row, initialSel));
        return wrap;
    }

    /** 分组玻璃卡容器：圆角 16dp＋阴影 2dp（批 4 换 bg_music_glass_card），行自带 16dp 横向内边距（分割线收边与行边距对齐靠此口径） */
    private LinearLayout cardBox() {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_music_glass_card);
        card.setElevation(dp(2));
        card.setPadding(0, 0, 0, 0);
        return card;
    }

    /**
     * 设置行：单行横排——标签 weight 1、值右对齐同排、雪佛龙 20dp；整行固定高 56dp（GZ SettingsListRow 代码值：
     * 无副值 56dp、有副值 62dp；星辰值与标签同行，统一 56dp）＋整行 gravity 居中，
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
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));
        TextView vv = text(value, 14, COLOR_LOW, false);
        vv.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        vlp.setMarginStart(dp(12));
        row.addView(vv, vlp);
        card.addView(row);
    }

    private View switchRow(String label, boolean checked, java.util.function.Consumer<Boolean> onChange) {
        return switchRow(label, checked, 56, onChange);
    }

    /** 带行首图标的开关行（GZ SettingsToggleRow：行首图标 20dp＋标签 15sp＋右侧开关，行高 56dp） */
    private View switchRow(int iconRes, String label, boolean checked, java.util.function.Consumer<Boolean> onChange) {
        View row = switchRow(label, checked, 56, onChange);
        if (row instanceof LinearLayout) {
            LinearLayout r = (LinearLayout) row;
            ImageView iv = new ImageView(getContext());
            iv.setImageResource(iconRes);
            iv.setImageTintList(ColorStateList.valueOf(COLOR_TEXT));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(20));
            ilp.setMarginEnd(dp(8));
            r.addView(iv, 0, ilp);
        }
        return row;
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
                new int[]{COLOR_RED, 0x1A000000}));
        sw.setOnCheckedChangeListener((button, on) -> onChange.accept(on));
        // 整行可点（跟瓜子一样）：开关本身不直接接手势，行点只翻开关状态、仍走开关监听单次触发，绝不双触发
        sw.setClickable(false);
        row.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
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
        card.addView(div, lp);
    }

    /** GZ 通用按压反馈（MotionKt：150ms；缩放值由调用方传，段控/格子 0.96、圆钮 0.9） */
    private void attachPress(View view, float scale) {
        view.setOnTouchListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(scale).scaleY(scale).setDuration(150).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            }
            return false;
        });
    }

    /** 字重（GZ sq2 档：500/600/800 等 Android bold 之外的档位）：API 28+ 按字重建 Typeface，低版本回落粗体／常规 */
    private void applyWeight(TextView tv, int weight) {
        if (tv == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                tv.setTypeface(Typeface.create(Typeface.DEFAULT, weight, false));
            } else {
                tv.setTypeface(weight >= 700 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
        } catch (Throwable ignored) {
        }
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
