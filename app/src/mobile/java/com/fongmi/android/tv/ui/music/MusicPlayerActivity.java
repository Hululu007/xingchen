package com.fongmi.android.tv.ui.music;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.LxKeyIssue;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.LyricVersion;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.ActivityMusicPlayerRebuildBinding;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicLyricCache;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.ui.dialog.MusicQueueDialog;
import com.fongmi.android.tv.ui.dialog.MusicToolsSheetDialog;
import com.fongmi.android.tv.utils.CoverColor;
import com.fongmi.android.tv.utils.CoverTextDrawable;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.List;

import jp.wasabeef.glide.transformations.internal.FastBlur;

/**
 * 音乐播放页（批 1 重建，ui.music 新包）：骨架＝氛围背景层（糊底／流体双路径）＋主题容器
 * （PlayerPageSwipeLayout 页级竖滑切歌层）＋爱心粒子层；主题分发按 MusicPlayerSettings
 * 主题 id：classic（批 1）、cover_immersive 封面卡片＋apple_music（批 3 C 路）、
 * luminous_cd 三 id 流光 CD＋record_shelf 黑胶唱片架（批 3 D 路）五主题全实现。
 * 播放只走 MusicController（未绑定积压重放机制在 Controller 内，服务没就绪不直调、不崩）。
 */
public class MusicPlayerActivity extends BaseActivity implements MusicPlayerHost {

    public static final String THEME_CLASSIC = MusicPlayerSettings.THEME_CLASSIC;
    public static final String THEME_APPLE = MusicPlayerSettings.THEME_APPLE_MUSIC;
    public static final String THEME_IMMERSIVE = MusicPlayerSettings.THEME_COVER_IMMERSIVE;
    public static final String THEME_CD = MusicPlayerSettings.THEME_LUMINOUS_CD;
    public static final String THEME_SHELF = MusicPlayerSettings.THEME_RECORD_SHELF;

    private ActivityMusicPlayerRebuildBinding binding;
    private View themeRoot;
    private String themeId = THEME_CLASSIC;

    /** 全屏歌词页（批 2）：整屏页层，右划进、左划／返回键出；与内嵌歌词共用本页已加载的词数据。 */
    private MusicLyricPageView lyricPageView;
    private boolean lyricPageVisible;

    private ImageView coverView;
    private ImageView coverExitView;
    private int coverExitGen;
    private TextView titleView;
    private TextView artistView;
    private TextView albumView;
    private ImageView favHeartView;
    private ImageView playTopView;
    private CoverLyricView lyricView;
    private MusicScrubberView scrubberView;
    private TextView positionTopView;
    private TextView durationTopView;
    private TextView qualityValueView;
    private TextView lyricChipTextView;

    // ===== 批 3：封面卡片／Apple 共用装配字段（按主题 grab，未命中的保持 null，全部消费点空守卫） =====
    private boolean coverTheme;
    private boolean appleTheme;
    // ===== 批 3 D 路：流光 CD／黑胶唱片架字段（按主题 grab，未命中的保持 null，消费点全空守卫） =====
    private boolean cdTheme;
    private boolean shelfTheme;
    /** CD 表面：0 LIGHT／1 DARK／2 AUTO（主题 id 决定初值，五图标键循环切换并回写主题 id）。 */
    private int cdSurfaceMode;
    private boolean cdDarkSurface;
    /** 表面键切换触发的 applyTheme：保留渐变起点走 500ms 混色补间，不硬切。 */
    private boolean cdSurfaceSwitchPending;
    private boolean cdExpanded;
    private boolean cdInfoMode;
    private ValueAnimator cdCollapseAnimator;
    private ValueAnimator cdToneAnimator;
    private final int[] cdBackdropFrom = new int[4];
    private final int[] cdBackdropTo = new int[4];
    private boolean cdBackdropInit;
    private View cdBackdropView;
    private CdDiscView cdDiscView;
    /** 律动包络源（X-15）：本页创建/启停，喂流体背景 level 与 CD 盘 beatA/beatB。 */
    private VisualizerRhythmSource rhythmSource;
    private View cdDetailView;
    private CdLyricWindowView cdLyricView;
    private View cdBelowColView;
    private View cdSummaryBoxView;
    private TextView cdSummaryMainView;
    private TextView cdSummarySubView;
    private CdSeekBarView cdSeekView;
    private TextView cdPositionView;
    private TextView cdDurationView;
    private TextView cdTitleView;
    private ImageView cdPlayView;
    private ImageView cdBtnModeView;
    private ImageView cdBtnFavView;
    private View cdBtnLyricVerView;
    private String cdSummaryShown = "";
    private ShelfStageView shelfStageView;
    private ShelfLyricView shelfLyricView;
    private CdSeekBarView shelfSeekView;
    private TextView shelfPositionView;
    private TextView shelfDurationView;
    private ImageView shelfPlayView;
    private ImageView shelfBtnModeView;
    private Bitmap shelfBmpPrev;
    private Bitmap shelfBmpCur;
    private Bitmap shelfBmpNext;
    private int shelfCoverGen;
    // 架三槽曲目键（与舞台 noteSlotKeys 同值）：切歌时判「切到的正是相邻槽」走连续翻页装配
    private String shelfPrevKey;
    private String shelfCurKey;
    private String shelfNextKey;
    /** 程序化相邻翻页动画在途：封面加载完成先只存字段，落位回调里统一回喂（防动画中途整图跳换）。 */
    private boolean shelfFeedDeferred;
    private View coverStackView;
    private View expandedColView;
    private ImageView coverBack1View;
    private ImageView coverBack2View;
    private ImageView cardExitView;
    private ImageView glassFavView;
    private ImageView glassPlayView;
    private TextView glassLyricView;
    private View glassBackView;
    private View infoBtnView;
    private ScrollView infoScrollView;
    private LinearLayout infoContentView;
    private boolean coverExpanded;
    private boolean coverInfoMode;
    private String glassLyricShown = "";
    private boolean glassLyricPlaceholder;
    private FullLyricView appleLyricView;
    private MusicVolumeSliderView volumeSliderView;
    private AudioManager audioManager;

    private boolean destroyed;
    private boolean playing;
    private boolean favorited;
    private boolean favoriteBusy;
    private MusicTrack lastBoundTrack;

    /** 通知不可用提示条：本进程内只主动挂一次（同会话不反复弹）；挂上后回页复检、恢复即撤。 */
    private static boolean notifHintShownOnce;

    /** 后台播放分家（批 0 点名迁移，旧页 :517-533 同口径）：onStop 因音乐后台关暂停时置位，回页只续同一首。 */
    private boolean bgPausedMusic;
    private String bgPausedTrackKey;

    private int ambientColor = CoverColor.FALLBACK_COLOR;
    private int dominantColor = CoverColor.FALLBACK_COLOR;
    private ValueAnimator ambientAnimator;
    private Bitmap bgSoftBitmap;
    private String bgSoftUrl;
    private String bgAppliedUrl;
    /** 全屏歌词页糊底自合成去重（流体路径）：正在为哪张封面合成，防重复起线程。 */
    private String lyricBgBuildUrl;
    private int lastSkipDirection = 1;

    private final List<LyricsLine> lyricMains = new ArrayList<>();
    private final List<String> lyricTrans = new ArrayList<>();
    private final List<String> lyricRomas = new ArrayList<>();
    private final List<LyricVersion> currentLyricVersions = new ArrayList<>();
    /** 当前实际在播的那份词构成的版本（补单②：在用词本身必须在版本列表里，见 loadLyric） */
    private LyricVersion currentLyricVersion;
    private String currentLyricKey;
    private String currentLyricFormat;
    /** 当前已应用歌词的来源名（MusicLyric.sourceName／LyricVersion.getSource，信息页「匹配来源」行数据源） */
    private String currentLyricSource;
    private boolean lyricEmpty;
    private boolean lyricLoading;

    private static final android.view.animation.Interpolator INTERP_FOSI = new PathInterpolator(0.2f, 0f, 0f, 1f);
    /** bs4.b 字段字面 (0,0,0,1)（favScale 上行／图标入场用；与 bs4.b() 方法返回值不同物）。 */
    private static final android.view.animation.Interpolator INTERP_BEZ_0001 = new PathInterpolator(0f, 0f, 0f, 1f);
    private static final android.view.animation.Interpolator INTERP_EMPH = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    /** 切歌换卡出场缓动（瓜子 bs4.c＝(0.3,0,1,1)，dig 三.16）。 */
    private static final android.view.animation.Interpolator INTERP_EXIT = new PathInterpolator(0.3f, 0f, 1f, 1f);

    /** 旧页 start() 原样语义：无 extras、无标志，普通 startActivity 进播放页。 */
    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, MusicPlayerActivity.class));
    }

    private final MusicController.Listener listener = new MusicController.Listener() {
        @Override
        public void onTrackChanged(MusicTrack track) {
            runOnUiThread(() -> bindTrack(track));
        }

        @Override
        public void onTrackReady(MusicTrack track) {
            runOnUiThread(() -> paintFavoriteIcons());
        }

        @Override
        public void onPlayingChanged(boolean value) {
            runOnUiThread(() -> setPlaying(value));
        }

        @Override
        public void onModeChanged(int mode) {
            runOnUiThread(() -> paintCdModeIcon());
        }

        @Override
        public void onProgress(long position, long duration) {
            runOnUiThread(() -> onProgressUi(position, duration));
        }

        @Override
        public void onAudioInfoChanged() {
            runOnUiThread(() -> refreshQualityValue());
        }

        @Override
        public void onKeyIssue(String platform, String detail) {
            runOnUiThread(() -> showKeyIssue(platform, detail, () -> {
                int idx = MusicController.get().index();
                if (idx >= 0) MusicController.get().playAt(idx);
            }));
        }
    };

    @Override
    protected boolean useDarkSystemIcons() {
        return false;
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityMusicPlayerRebuildBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        MusicRepository.ensureDebugFake();
        // 冷进不崩：只经 MusicController 绑定，服务未就绪时 Controller 积压重放，本页不直调服务。
        MusicController.get().bind(this);
        MusicController.get().addListener(listener);
        if (binding.notifHintGo != null) {
            binding.notifHintGo.setOnClickListener(v -> Notify.openNotificationSettings(this));
        }
        attachPageSwipe();
        setupLyricPage();
        applyTheme();
        bindTrack(MusicController.get().current());
        setPlaying(MusicController.get().isPlaying());
        refreshQualityValue();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MusicController.get().removeListener(listener);
        MusicController.get().addListener(listener);
        refreshNotifHintOnResume();
        setPlaying(MusicController.get().isPlaying());
        if (appleTheme) refreshVolumeSlider();
        // 后台关暂停的回页续播（旧页原样迁移）：只有 onStop 亲手暂停的才续，且必须是同一首。
        if (bgPausedMusic) {
            bgPausedMusic = false;
            String key = bgPausedTrackKey;
            bgPausedTrackKey = null;
            try {
                MusicTrack curBg = MusicController.get().current();
                if (curBg != null && key != null
                        && key.equals(trackKey(curBg))
                        && !MusicController.get().isPlaying()) {
                    MusicController.get().play();
                }
            } catch (Throwable ignored) {
            }
        }
        MusicTrack cur = MusicController.get().current();
        if (cur != lastBoundTrack && (cur == null || lastBoundTrack == null
                || !trackKey(cur).equals(trackKey(lastBoundTrack)))) {
            bindTrack(cur);
        } else if (cur != null && lyricEmpty && !lyricLoading) {
            loadLyric(cur, false);
        }
        String theme = MusicPlayerSettings.getTheme();
        if (!theme.equals(themeId)) applyTheme();
        else applyLyricStyle();
        applyKeepScreenOn();
        // 迷你条钩子（1.4.27 查修批恢复，旧页 fe2e780b onResume 末尾同位）：进播放页要向迷你条报到，
        // 否则 MiniBarPolicy 的音乐 pageForeground 永不置真、页内暂停满 10 秒会被计时器真关会话。
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(this).pageResumed("music");
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        MusicController.get().removeListener(listener);
        // 迷你条钩子（同上，旧页 onPause 开头同位）：离页报离，页面前台标记与暂停计时恢复由迷你条侧接管。
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(this).pagePaused("music");
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 后台播放分家（旧页 :517-533 原样迁移）：音乐只认音乐自己那份开关
        // （PlayerSetting.isMusicBackgroundOff，默认开＝常驻）；关时离开播放页暂停，
        // 页面没在关（isFinishing 为假）才记下是哪首，回页由 onResume 续同一首。
        bgPausedMusic = false;
        bgPausedTrackKey = null;
        try {
            if (PlayerSetting.isMusicBackgroundOff() && MusicController.get().isPlaying()) {
                MusicTrack curBg = MusicController.get().current();
                if (curBg != null && !isFinishing()) {
                    bgPausedTrackKey = trackKey(curBg);
                    bgPausedMusic = true;
                }
                MusicController.get().pause();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 律动包络源同步（X-15）：播放中按当前会话 id 启动（id 变了自动重挂），暂停即停。
     * 源建立失败/无权限时其包络恒 0，消费侧退化基础速率——本方法只管接线，不弹窗不拦截。
     */
    private void syncRhythmSource() {
        try {
            if (!playing) {
                if (rhythmSource != null) rhythmSource.stop();
                return;
            }
            if (rhythmSource == null) {
                rhythmSource = new VisualizerRhythmSource(this);
            }
            // 注入不能只在首次创建走：applyTheme 每次都重建 cdDiscView，
            // 只注一次的话主题切换后的新盘永远拿不到节拍源（beatA/beatB 恒 0）
            if (cdDiscView != null) cdDiscView.setRhythmSource(rhythmSource);
            if (binding != null && binding.fluidAmbient != null) {
                binding.fluidAmbient.setRhythmSource(rhythmSource);
            }
            rhythmSource.start(MusicController.get().audioSessionId());
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (rhythmSource != null) rhythmSource.stop();
        MusicController.get().removeListener(listener);
        MusicController.get().unbind();
        if (ambientAnimator != null) ambientAnimator.cancel();
        // 暂停呼吸是无限循环动画，不取消会经 AnimationHandler 常驻、持住已销毁的本页
        if (playBreathAnimator != null) {
            playBreathAnimator.cancel();
            playBreathAnimator = null;
        }
        if (cdCollapseAnimator != null) cdCollapseAnimator.cancel();
        if (cdToneAnimator != null) cdToneAnimator.cancel();
        cancelCoverScaleSpring();
        super.onDestroy();
    }

    private static String trackKey(MusicTrack track) {
        return track == null ? "" : String.valueOf(track.id) + "|" + String.valueOf(track.platform);
    }

    private void applyKeepScreenOn() {
        try {
            if (MusicStore.isPlayerKeepScreenOn()) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= 主题装配 =================

    /**
     * 主题分发：按 MusicPlayerSettings 主题 id 选渲染，五主题各一份布局
     * （classic 批 1、cover_immersive＋apple_music 批 3 C 路、luminous_cd 三 id＋record_shelf 批 3 D 路）。
     * 主题切换直切、无过渡动画（§19-9 瓜子无字面值）；氛围色 450ms 与切歌转场照旧。
     */
    private void applyTheme() {
        String theme = MusicPlayerSettings.getTheme();
        themeId = theme;
        binding.themeContainer.removeAllViews();
        coverTheme = THEME_IMMERSIVE.equals(theme);
        appleTheme = THEME_APPLE.equals(theme);
        cdTheme = MusicPlayerSettings.THEME_LUMINOUS_CD.equals(theme)
                || MusicPlayerSettings.THEME_LUMINOUS_CD_DARK.equals(theme)
                || MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO.equals(theme);
        shelfTheme = THEME_SHELF.equals(theme);
        if (cdTheme) {
            cdSurfaceMode = MusicPlayerSettings.THEME_LUMINOUS_CD_DARK.equals(theme) ? 1
                    : MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO.equals(theme) ? 2 : 0;
            cdDarkSurface = resolveCdDarkSurface();
        }
        // 竖滑切歌是经典/封面卡片/CD 的页级手势；Apple 是横滑 pager＋歌词页竖滚，
        // 唱片架切歌走舞台内横滑——两家都关页级竖滑避免抢手势（架口径与经典不同处见 D 路报告）
        binding.themeContainer.setSwipeEnabled(!(appleTheme || shelfTheme));
        coverExpanded = false;
        coverInfoMode = false;
        cdExpanded = false;
        cdInfoMode = false;
        cdCollapseValue = 0f;
        if (!cdSurfaceSwitchPending) cdBackdropInit = false;
        if (cdCollapseAnimator != null) {
            cdCollapseAnimator.cancel();
            cdCollapseAnimator = null;
        }
        int layout = coverTheme ? R.layout.music_player_theme_cover
                : appleTheme ? R.layout.music_player_theme_apple
                : cdTheme ? R.layout.music_player_theme_cd
                : shelfTheme ? R.layout.music_player_theme_shelf
                : R.layout.music_player_theme_classic;
        themeRoot = LayoutInflater.from(this).inflate(layout, binding.themeContainer, false);
        binding.themeContainer.addView(themeRoot);
        grabThemeViews();
        applyTopInsetForTheme();
        wireThemeViews();
        applyLyricStyle();
        feedLyricView();
        MusicTrack track = MusicController.get().current();
        bindTrackViews(track);
        setPlaying(playing);
        refreshQualityValue();
        applyFluidBg();
        applySkeletonForTheme();
    }

    /** CD 表面实际明暗：LIGHT=false、DARK=true、AUTO＝跟随系统夜间模式。 */
    private boolean resolveCdDarkSurface() {
        if (cdSurfaceMode == 1) return true;
        if (cdSurfaceMode == 2) {
            int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return night == Configuration.UI_MODE_NIGHT_YES;
        }
        return false;
    }

    /**
     * 骨架层按主题收口：CD（取色渐变自带底）与唱片架（纯色底 #F1F0ED）不用骨架的糊底/流体/罩层，
     * 全部隐藏防透出；其余主题恢复骨架原状（流体开关由 applyFluidBg 管）。骨架文件本身不改。
     */
    private void applySkeletonForTheme() {
        boolean themed = cdTheme || shelfTheme;
        if (binding.ivBgBlur != null) binding.ivBgBlur.setVisibility(themed ? View.GONE : View.VISIBLE);
        if (binding.fluidAmbient != null && themed) binding.fluidAmbient.setVisibility(View.GONE);
        if (binding.viewVeil != null && themed) binding.viewVeil.setVisibility(View.GONE);
        if (binding.swipeHintDir != null && binding.swipeHintName != null) {
            // CD 浅底上切歌提示不能用骨架的白字（字体看得清铁律）：走当前表面 ink；深底主题恢复白。
            // 两行 alpha 按 GZ 提示口径折进色值：方向字 0.72、歌名 0.92。
            int base = cdTheme ? (cdDarkSurface ? 0xF2F2F7 : 0x282D36) : 0xFFFFFF;
            binding.swipeHintDir.setTextColor(0xB8000000 | base);
            binding.swipeHintName.setTextColor(0xEB000000 | base);
        }
        if (!themed) applyFluidBg();
    }

    /** 全屏歌词页接线（112930 定案形态）：Tr 钮＝主歌词三选菜单、点行＝seek。
     * 旧形态的队列／暂停键随页眉改版摘除（那是瓜子另一歌词面的键组，本页定案只有 Tr）。 */
    private void setupLyricPage() {
        lyricPageView = binding.lyricPage == null ? null : binding.lyricPage.getRoot();
        if (lyricPageView == null) return;
        lyricPageView.setOnTrClickListener(v -> showLyricPrimaryMenu(v));
        lyricPageView.setOnLineClickListener((index, positionMs) ->
                MusicController.get().seekTo(positionMs));
    }

    /**
     * 主歌词三选菜单（2026-10-22 定案：Tr 钮点开小菜单，原文恒有、翻译／音译有数据才出、当前打勾；
     * 选后写 music_lyric_primary 并整页重喂——内嵌与全屏同刷）。深色玻璃面（弹窗不用白底）。
     */
    private void showLyricPrimaryMenu(View anchor) {
        int current = MusicPlayerSettings.getLyricPrimary();
        List<int[]> kinds = new ArrayList<>();
        kinds.add(new int[]{0, R.string.music_primary_original});
        if (hasNonEmpty(lyricTrans)) kinds.add(new int[]{1, R.string.music_primary_translation});
        if (hasNonEmpty(lyricRomas)) kinds.add(new int[]{2, R.string.music_primary_romanization});
        float density = getResources().getDisplayMetrics().density;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(6f * density);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE61C1C1E);
        bg.setCornerRadius(14f * density);
        bg.setStroke(Math.max(1, Math.round(density)), 0x33FFFFFF);
        panel.setBackground(bg);
        android.widget.PopupWindow popup = new android.widget.PopupWindow(
                panel, Math.round(168f * density), android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        for (int[] kind : kinds) {
            TextView row = new TextView(this);
            row.setText(getString(kind[1]) + (kind[0] == current ? "  ✓" : ""));
            row.setTextColor(0xFFFFFFFF);
            row.setTextSize(15f);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(Math.round(14f * density), Math.round(10f * density),
                    Math.round(14f * density), Math.round(10f * density));
            row.setOnClickListener(v -> {
                popup.dismiss();
                if (kind[0] != current) {
                    MusicStore.setLyricPrimary(kind[0]);
                    feedLyricView();
                }
            });
            panel.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        popup.showAsDropDown(anchor, Math.round(-128f * density), Math.round(6f * density));
    }

    /**
     * 全屏歌词页背景同步（定案：歌词页也是封面模糊底）：优先复用整页糊底的成品位图（ivBgBlur）；
     * 流体路径下按同配方（buildBlurredAmbient）现合成；糊底未就绪时页内先贴实图占位，
     * 合成完成由 refreshBlurredAmbient 末尾再同步一次。
     */
    private void applyLyricPageBackground() {
        if (lyricPageView == null || binding == null) return;
        MusicTrack cur = MusicController.get().current();
        String cover = cur == null ? null : effectiveCover(cur);
        if (cover == null || cover.isEmpty()) return;
        if (!MusicPlayerSettings.isFluidBg() && binding.ivBgBlur != null
                && binding.ivBgBlur.getDrawable() != null && cover.equals(bgAppliedUrl)) {
            lyricPageView.setCoverDrawable(binding.ivBgBlur.getDrawable());
            return;
        }
        if (!lyricPageVisible || bgSoftBitmap == null || !cover.equals(bgSoftUrl)) return;
        if (cover.equals(lyricBgBuildUrl)) return;
        int vw = lyricPageView.getWidth();
        int vh = lyricPageView.getHeight();
        if (vw <= 0 || vh <= 0) return;
        lyricBgBuildUrl = cover;
        Bitmap src = bgSoftBitmap;
        int ambient = ambientColor;
        Task.execute(() -> {
            Bitmap out = buildBlurredAmbient(src, vw, vh, ambient);
            if (out == null) return;
            runOnUiThread(() -> {
                if (destroyed || lyricPageView == null) return;
                MusicTrack c = MusicController.get().current();
                if (c == null || !cover.equals(effectiveCover(c))) return;
                lyricPageView.setCoverDrawable(new BitmapDrawable(getResources(), out));
            });
        });
    }

    private void showLyricPage() {
        if (lyricPageView == null || lyricPageVisible) return;
        prepareLyricPage();
        // 程序化开页与跟手同口径：页先落左侧屏外，再减速滑到位（不再固定小位移动画「盖」上去）。
        applyLyricSwipeTx(-lyricPageWidth());
        settleLyricPage(true);
    }

    /** 全屏歌词页歌手行（实帧口径：歌手 ♡ 专辑名；无专辑名只显歌手）。 */
    private static String lyricPageArtistLine(String artist, String album) {
        if (album == null || album.trim().isEmpty()) return artist;
        return artist + " ♡ " + album.trim();
    }

    /** 开页准备（喂词/副行复位/封面/进度对齐）：程序化开页与横滑跟手共用，只备页、不动位移。 */
    private void prepareLyricPage() {
        lyricPageVisible = true;
        lyricPageView.setVisibility(View.VISIBLE);
        lyricPageView.setAlpha(1f);
        lyricPageView.setToneColor(ambientColor);
        // 开页副行复位全显（2026-10-22 定案）：翻译/音译显隐复位为开、有数据即显，
        // 不许再出现「不进设置就永远看不到音译」。须在 feedLyricView 之前落。
        if (!MusicStore.isShowTranslation()) MusicStore.setShowTranslation(true);
        if (!MusicStore.isShowRomanization()) MusicStore.setShowRomanization(true);
        MusicTrack track = MusicController.get().current();
        if (track != null) {
            lyricPageView.setTrackInfo(track.displayTitle(), lyricPageArtistLine(track.displayArtist(), track.album));
            lyricPageView.setCover(effectiveCover(track), track.displayTitle());
            applyLyricPageBackground();
        }
        feedLyricView();
        lyricPageView.setPlaying(playing);
        lyricPageView.setLyricPosition(MusicController.get().position() + MusicPlayerSettings.getLyricOffsetMs());
    }

    private void hideLyricPage() {
        if (lyricPageView == null || !lyricPageVisible) return;
        // 退页（左划跟手松手/返回键）与进页同口径：整页往左减速滑回屏外，滑尽才 GONE。
        settleLyricPage(false);
    }

    /** 歌词页行程宽度：优先取页自身宽，未布局到时退屏宽（页在布局里是 match_parent，两者等价）。 */
    private float lyricPageWidth() {
        if (lyricPageView != null && lyricPageView.getWidth() > 0) return lyricPageView.getWidth();
        return getResources().getDisplayMetrics().widthPixels;
    }

    /**
     * 按位移统一落两页位置：歌词页 tx 钳在 [-页宽, 0]（本位在左侧屏外，0 为全开）；
     * 播放页本体（主题容器）同向视差跟随——行程只有歌词页的 LYRIC_PARALLAX_RATIO 倍，
     * 两页成「翻」的关系不是「盖」的关系。背景层不参与（歌词页与播放页共用同一糊底氛围）。
     */
    private void applyLyricSwipeTx(float tx) {
        if (lyricPageView == null) return;
        float w = lyricPageWidth();
        float clamped = Math.max(-w, Math.min(0f, tx));
        lyricPageView.setTranslationX(clamped);
        float progress = w > 0 ? (clamped + w) / w : 0f;
        if (binding != null && binding.themeContainer != null) {
            binding.themeContainer.setTranslationX(progress * w * LYRIC_PARALLAX_RATIO);
        }
    }

    /** 收尾动画在跑时先取消：位移冻结在当前值，新手势/新指令从当前位接管，不回跳。 */
    private void cancelLyricSettle() {
        if (lyricSettleAnimator != null) {
            lyricSettleAnimator.cancel();
            lyricSettleAnimator = null;
        }
    }

    /**
     * 松手/程序化收尾：从当前位减速滑到全开（0）或全关（-页宽）。
     * 时长按剩余行程折算（全程 350ms、下限 120ms），曲线走全文件 FOSI 减速口径，不匀速、不瞬移。
     */
    private void settleLyricPage(boolean open) {
        if (lyricPageView == null) return;
        cancelLyricSettle();
        float w = lyricPageWidth();
        float from = lyricPageView.getTranslationX();
        float to = open ? 0f : -w;
        if (open) lyricPageVisible = true;
        if (from == to) {
            finishLyricSettle(open);
            return;
        }
        float frac = w > 0 ? Math.abs(to - from) / w : 0f;
        ValueAnimator anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration((long) (120f + 230f * frac));
        anim.setInterpolator(INTERP_FOSI);
        anim.addUpdateListener(a -> applyLyricSwipeTx((float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (lyricSettleAnimator == animation) {
                    lyricSettleAnimator = null;
                    finishLyricSettle(open);
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                if (lyricSettleAnimator == animation) lyricSettleAnimator = null;
            }
        });
        lyricSettleAnimator = anim;
        anim.start();
    }

    private void finishLyricSettle(boolean open) {
        if (lyricPageView == null) return;
        applyLyricSwipeTx(open ? 0f : -lyricPageWidth());
        if (open) {
            lyricPageVisible = true;
            return;
        }
        lyricPageVisible = false;
        lyricPageView.setVisibility(View.GONE);
        // 关稳后两页位移复位：页已 GONE，下次开页由跟手/开页动画重新落位（与旧 hide 的复位口径一致）。
        lyricPageView.setTranslationX(0f);
        if (binding != null && binding.themeContainer != null) binding.themeContainer.setTranslationX(0f);
    }

    @Override
    protected void onBackInvoked() {
        if (lyricPageVisible) {
            hideLyricPage();
            return;
        }
        super.onBackInvoked();
    }

    // ===== 右划进歌词页（定案：点歌词行只快进、不进页；右划才进） =====
    // 只观察不消费：页级竖滑切歌（PlayerPageSwipeLayout，轴 dominance 1.5）与进度拖动不受影响；
    // 起点落在进度条行内时不触发（横拖进度优先）。
    // 跟手口径（2026-10-25）：过触发门后进入跟随段，歌词页位移实时跟手指（本位在左侧屏外），
    // 播放页本体同向视差跟随；松手按过半程/甩动速度定去留，收尾减速滑到位或弹回。
    // 视差比为保守近似值——瓜子 pager 的视差原值未抠到，不冒充真值。
    private static final float LYRIC_PARALLAX_RATIO = 0.2f;
    // 甩动定去留的速度门（dp/s，与 ViewPager fling 口径同量级）。
    private static final float LYRIC_FLING_DP_PER_SEC = 400f;

    private float swipeDownX;
    private float swipeDownY;
    private boolean swipeFollowing;
    private float swipeEngageDx;
    private float swipeEngageTx;
    private boolean swipeDownInCoverArea;
    private VelocityTracker swipeVelocityTracker;
    private ValueAnimator lyricSettleAnimator;
    // 经典主题歌词区横滑切歌（瓜子 coverAxisLock 口径）：起点在歌词区内时横滑归切歌，
    // 与右划进歌词页分工——歌词区内横滑＝上一首/下一首，封面区右划仍进歌词页。
    private boolean swipeDownInLyricArea;
    private boolean songSwipeFollowing;
    private float songSwipeEngageDx;
    private float songSwipeEngageTx;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swipeDownX = ev.getX();
                swipeDownY = ev.getY();
                swipeFollowing = false;
                // 收尾动画中途再划：先冻结在当前位（取消不回跳），新手势从当前位接管。
                cancelLyricSettle();
                if (swipeVelocityTracker == null) swipeVelocityTracker = VelocityTracker.obtain();
                else swipeVelocityTracker.clear();
                swipeVelocityTracker.addMovement(ev);
                // 封面卡片主题：起点落在封面叠卡区内时，横滑归主题自己的切歌手势（dig 三.13），不触发右划进歌词页
                swipeDownInCoverArea = false;
                if (coverTheme && coverStackView != null && coverStackView.getVisibility() == View.VISIBLE) {
                    int[] loc = new int[2];
                    coverStackView.getLocationInWindow(loc);
                    swipeDownInCoverArea = ev.getX() >= loc[0] && ev.getX() <= loc[0] + coverStackView.getWidth()
                            && ev.getY() >= loc[1] && ev.getY() <= loc[1] + coverStackView.getHeight();
                }
                // 唱片架主题：起点落在舞台内时，横滑归舞台自己的横滑翻页，不触发右划进歌词页（同豁免口径）
                if (shelfTheme && shelfStageView != null && shelfStageView.getVisibility() == View.VISIBLE) {
                    int[] loc = new int[2];
                    shelfStageView.getLocationInWindow(loc);
                    swipeDownInCoverArea = ev.getX() >= loc[0] && ev.getX() <= loc[0] + shelfStageView.getWidth()
                            && ev.getY() >= loc[1] && ev.getY() <= loc[1] + shelfStageView.getHeight();
                }
                // 经典主题：起点落在内嵌歌词区内时，横滑归歌词区切歌，不触发右划进歌词页
                // （分工见 songSwipe 字段注；封面卡片展开态歌词面不在此列，其横滑归叠卡）。
                swipeDownInLyricArea = false;
                songSwipeFollowing = false;
                if (!coverTheme && !appleTheme && !cdTheme && !shelfTheme
                        && lyricView != null && lyricView.getVisibility() == View.VISIBLE) {
                    int[] loc = new int[2];
                    lyricView.getLocationInWindow(loc);
                    swipeDownInLyricArea = ev.getX() >= loc[0] && ev.getX() <= loc[0] + lyricView.getWidth()
                            && ev.getY() >= loc[1] && ev.getY() <= loc[1] + lyricView.getHeight();
                }
                // 新手势接管前先停掉上一轮歌词区切歌的回位动画（位移冻结在当前值，不回跳）
                if (binding != null && binding.themeContainer != null) {
                    binding.themeContainer.animate().cancel();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (swipeVelocityTracker != null) swipeVelocityTracker.addMovement(ev);
                if (swipeDownInLyricArea && !lyricPageVisible
                        && binding != null && binding.themeContainer != null) {
                    float dx = ev.getX() - swipeDownX;
                    float dy = ev.getY() - swipeDownY;
                    if (!songSwipeFollowing) {
                        // 轴判定照瓜子 coverAxisLock：竖向占优（|dy|＞|dx|×1.5）让给页级竖滑
                        // 切歌，横向占优过触摸阈值才接管；接管以当前总位移为零点（不跳）。
                        float slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
                        if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                            songSwipeFollowing = true;
                            songSwipeEngageDx = dx;
                            songSwipeEngageTx = binding.themeContainer.getTranslationX();
                        }
                    }
                    if (songSwipeFollowing) {
                        // 跟手：播放页本体随手指横移，钳在瓜子锚距内（见 songSwipeAnchorPx）
                        float anchor = songSwipeAnchorPx();
                        float tx = songSwipeEngageTx + (dx - songSwipeEngageDx);
                        binding.themeContainer.setTranslationX(Math.max(-anchor, Math.min(anchor, tx)));
                    }
                }
                if (!swipeDownInCoverArea && !swipeDownInLyricArea && lyricPageView != null) {
                    float dx = ev.getX() - swipeDownX;
                    float dy = ev.getY() - swipeDownY;
                    if (!swipeFollowing) {
                        // 触发量与轴判定照瓜子横滑口径（施工图 §3：触发 36dp、dominance 1.2）；
                        // 原 60dp＋1.5 在真机自然右划下难以触发，是「右划进不了歌词页」的断点之一。
                        float threshold = 36f * getResources().getDisplayMetrics().density;
                        if (Math.abs(dx) > threshold && Math.abs(dx) > Math.abs(dy) * 1.2f
                                && !isInScrubberRow(ev)) {
                            boolean pageOut = lyricPageVisible || lyricPageView.getVisibility() == View.VISIBLE;
                            if (dx > 0 && !pageOut) {
                                // 从全关态右划开页：先备页并落到左侧屏外，再从当前位接管跟随（接管不跳）。
                                prepareLyricPage();
                                applyLyricSwipeTx(-lyricPageWidth());
                                swipeFollowing = true;
                            } else if (pageOut) {
                                // 已出页（全开/半开/收尾被冻结）：双向跟随，以接管时的位移为零点。
                                swipeFollowing = true;
                            }
                            if (swipeFollowing) {
                                swipeEngageDx = dx;
                                swipeEngageTx = lyricPageView.getTranslationX();
                            }
                        }
                    }
                    if (swipeFollowing) {
                        // 跟随：页位移＝接管位移＋手指增量，钳制在 applyLyricSwipeTx 内做（反向改主意自然回退）。
                        applyLyricSwipeTx(swipeEngageTx + (dx - swipeEngageDx));
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (swipeVelocityTracker != null) swipeVelocityTracker.addMovement(ev);
                if (swipeFollowing) {
                    swipeFollowing = false;
                    // 定去留：甩动速度优先，其次看是否过半程。
                    float vx = 0f;
                    if (swipeVelocityTracker != null) {
                        swipeVelocityTracker.computeCurrentVelocity(1000);
                        vx = swipeVelocityTracker.getXVelocity();
                    }
                    float w = lyricPageWidth();
                    float tx = lyricPageView != null ? lyricPageView.getTranslationX() : 0f;
                    float progress = w > 0 ? (tx + w) / w : 0f;
                    float fling = LYRIC_FLING_DP_PER_SEC * getResources().getDisplayMetrics().density;
                    boolean open;
                    if (vx > fling) open = true;
                    else if (vx < -fling) open = false;
                    else open = progress >= 0.5f;
                    settleLyricPage(open);
                }
                if (songSwipeFollowing) {
                    songSwipeFollowing = false;
                    float dx = ev.getX() - swipeDownX;
                    float vx = 0f;
                    if (swipeVelocityTracker != null) {
                        swipeVelocityTracker.computeCurrentVelocity(1000);
                        vx = swipeVelocityTracker.getXVelocity();
                    }
                    float density = getResources().getDisplayMetrics().density;
                    // 提交口径照瓜子：位移过 36dp 或甩速过 600dp/s；方向以位移为准、
                    // 位移未过门时以甩速方向为准（左＝下一首、右＝上一首）。
                    boolean byDistance = Math.abs(dx) >= 36f * density;
                    boolean commit = byDistance || Math.abs(vx) >= 600f * density;
                    if (binding != null && binding.themeContainer != null) {
                        // 回位 150ms（封面卡片叠卡横滑同值）；切歌换内容在回位中并行发生。
                        binding.themeContainer.animate().translationX(0f).setDuration(150).start();
                    }
                    if (commit && ev.getActionMasked() == MotionEvent.ACTION_UP) {
                        boolean next = byDistance ? dx < 0 : vx < 0;
                        lastSkipDirection = next ? 1 : -1;
                        if (next) MusicController.get().next();
                        else MusicController.get().prev();
                    }
                }
                if (swipeVelocityTracker != null) {
                    swipeVelocityTracker.recycle();
                    swipeVelocityTracker = null;
                }
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 歌词区横滑切歌锚距（瓜子 coverHorizontalSwipe 抠值）：clamp(屏宽 dp×0.42, 220, 360)dp，CoverStackView 同式。 */
    private float songSwipeAnchorPx() {
        float density = getResources().getDisplayMetrics().density;
        float widthDp = getResources().getDisplayMetrics().widthPixels / density;
        return Math.max(220f, Math.min(360f, widthDp * 0.42f)) * density;
    }

    private boolean isInScrubberRow(MotionEvent ev) {
        if (scrubberView == null) return false;
        int[] loc = new int[2];
        scrubberView.getLocationInWindow(loc);
        float density = getResources().getDisplayMetrics().density;
        return ev.getY() >= loc[1] - 28f * density && ev.getY() <= loc[1] + scrubberView.getHeight() + 8f * density
                && ev.getX() >= loc[0] && ev.getX() <= loc[0] + scrubberView.getWidth();
    }

    private void attachPageSwipe() {
        if (binding.themeContainer == null) return;
        binding.themeContainer.setTranslationY(0f);
        binding.themeContainer.setOnPageSwipeListener(next -> {
            lastSkipDirection = next ? 1 : -1;
            if (next) MusicController.get().next();
            else MusicController.get().prev();
        });
        if (binding.swipeHint != null) {
            binding.themeContainer.setSwipeHintView(binding.swipeHint,
                    binding.swipeHintDir, binding.swipeHintName,
                    getString(R.string.music_next), getString(R.string.music_prev),
                    this::swipeNeighborName);
        }
    }

    /** 竖滑提示第二行：相邻歌名（队列内现查；无相邻留空）。 */
    private String swipeNeighborName(boolean next) {
        try {
            java.util.List<MusicTrack> queue = MusicController.get().queue();
            MusicTrack current = MusicController.get().current();
            if (queue == null || current == null) return "";
            int index = queue.indexOf(current);
            if (index < 0) return "";
            int neighbor = next ? index + 1 : index - 1;
            if (neighbor < 0 || neighbor >= queue.size()) return "";
            MusicTrack track = queue.get(neighbor);
            return track == null ? "" : track.displayTitle();
        } catch (Throwable ignored) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T find(int id) {
        return themeRoot == null ? null : (T) themeRoot.findViewById(id);
    }

    private void grabThemeViews() {
        coverView = find(R.id.tpCover);
        coverExitView = find(R.id.tpCoverExit);
        titleView = find(R.id.tpTitle);
        artistView = find(R.id.tpArtist);
        albumView = find(R.id.tpAlbum);
        favHeartView = find(R.id.tpFavHeart);
        playTopView = find(R.id.tpPlayTop);
        lyricView = find(R.id.tpLyricView);
        scrubberView = find(R.id.tpScrubber);
        positionTopView = find(R.id.tpPositionTop);
        durationTopView = find(R.id.tpDurationTop);
        qualityValueView = find(R.id.tpQualityValue);
        lyricChipTextView = find(R.id.tpLyricChipText);
        // 批 3 两主题（classic 下全 null）
        coverStackView = find(R.id.tpCoverStack);
        expandedColView = find(R.id.tpExpandedCol);
        coverBack1View = find(R.id.tpCoverBack1);
        coverBack2View = find(R.id.tpCoverBack2);
        cardExitView = find(R.id.tpCardExit);
        glassFavView = find(R.id.tpGlassFav);
        glassPlayView = find(R.id.tpGlassPlay);
        glassLyricView = find(R.id.tpGlassLyric);
        glassBackView = find(R.id.tpGlassBack);
        infoBtnView = find(R.id.tpBtnInfo);
        infoScrollView = find(R.id.tpInfoScroll);
        infoContentView = find(R.id.tpInfoContent);
        appleLyricView = find(R.id.tpAppleLyric);
        volumeSliderView = find(R.id.tpVolumeSlider);
        // 批 3 D 路（classic/cover/apple 下全 null）
        cdBackdropView = find(R.id.tpCdBackdrop);
        cdDiscView = find(R.id.tpCdDisc);
        cdDetailView = find(R.id.tpCdDetail);
        cdLyricView = find(R.id.tpCdLyric);
        cdBelowColView = find(R.id.tpCdBelowCol);
        cdSummaryBoxView = find(R.id.tpCdSummaryBox);
        cdSummaryMainView = find(R.id.tpCdSummaryMain);
        cdSummarySubView = find(R.id.tpCdSummarySub);
        cdSeekView = find(R.id.tpCdSeek);
        cdPositionView = find(R.id.tpCdPosition);
        cdDurationView = find(R.id.tpCdDuration);
        cdTitleView = titleView;
        cdPlayView = find(R.id.tpCdPlay);
        cdBtnModeView = find(R.id.tpCdBtnMode);
        cdBtnFavView = find(R.id.tpCdBtnFav);
        shelfStageView = find(R.id.tpShelfStage);
        shelfLyricView = find(R.id.tpShelfLyric);
        shelfSeekView = find(R.id.tpShelfSeek);
        shelfPositionView = find(R.id.tpShelfPosition);
        shelfDurationView = find(R.id.tpShelfDuration);
        shelfPlayView = find(R.id.tpShelfPlay);
        shelfBtnModeView = find(R.id.tpShelfBtnMode);
        if (shelfStageView != null) {
            // X-14：舞台高布局期定死（stage−23dp 公式）；仅竖版固定高布局才写，横版权重布局不动
            android.view.ViewGroup.LayoutParams lp = shelfStageView.getLayoutParams();
            if (lp != null && lp.height > 0) {
                lp.height = Math.round(shelfStageView.stageHeightDp()
                        * getResources().getDisplayMetrics().density);
                shelfStageView.setLayoutParams(lp);
            }
        }
        if (coverStackView != null) {
            CoverStackView.roundCard(coverBack1View, 22f, this);
            CoverStackView.roundCard(coverBack2View, 22f, this);
            CoverStackView.roundCard(cardExitView, 22f, this);
            View wrap = find(R.id.tpCoverWrap);
            if (wrap != null) {
                wrap.setClipToOutline(true);
                float radius = 22f * getResources().getDisplayMetrics().density;
                wrap.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, android.graphics.Outline outline) {
                        outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                    }
                });
            }
        }
        if (lyricView != null && coverTheme) {
            lyricView.setAlignCenter(true);
            sizeExpandedLyricSlot();
        }
        if (scrubberView != null && appleTheme) {
            // Apple 进度变体（§2：轨 6dp／拖动 7dp、已播白 0.58／拖动 0.90；底轨 0.14/0.22 与默认同值）
            scrubberView.setVariant(6f, 7f, 0.58f, 0.90f);
        }
        if (!coverTheme && !appleTheme && !cdTheme && !shelfTheme) {
            // 经典封面容器高定死＝宽（方形，§1；断点见经典布局注：wrap_content 高由 Glide
            // 交付位图宽高比决定、竖长条交付图把封面撑满整页压住歌名/歌词）。宽度变化时同步。
            View classicWrap = find(R.id.tpCoverWrap);
            if (classicWrap != null) {
                classicWrap.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, oldR, oldB) -> {
                    // 横版经典是左右分栏、封面高 match_parent（布局已定），方形只管竖版
                    if (getResources().getConfiguration().orientation
                            == android.content.res.Configuration.ORIENTATION_LANDSCAPE) return;
                    int w = r - l;
                    if (w > 0 && v.getLayoutParams() != null && v.getLayoutParams().height != w) {
                        v.getLayoutParams().height = w;
                        v.requestLayout();
                    }
                });
            }
        }
    }

    /** 展开态歌词槽高（§3：槽高＝间隙×3＋行高×4，间隙 20dp、行高＝字号×4/3）。 */
    private void sizeExpandedLyricSlot() {
        if (lyricView == null || !coverTheme) return;
        float density = getResources().getDisplayMetrics().density;
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        float fontPx = MusicPlayerSettings.getCoverLyricFontSize() * scaled;
        int slot = Math.round(20f * 3f * density + fontPx * (4f / 3f) * 4f);
        android.view.ViewGroup.LayoutParams lp = lyricView.getLayoutParams();
        if (lp != null && lp.height != slot) {
            lp.height = slot;
            lyricView.setLayoutParams(lp);
        }
    }

    private void wireThemeViews() {
        // SongMeta 三键：菜单＝工具面板、队列＝MusicQueueDialog（已核：队列对话框不引用播放页，直接复用）、播放/暂停＝Controller.toggle()
        if (playTopView != null) playTopView.setOnClickListener(v -> MusicController.get().toggle());
        View menu = find(R.id.tpBtnMenu);
        if (menu != null) {
            menu.setOnClickListener(v -> showToolsSheet());
            attachPressScale(menu, 0.96f);
        }
        View queueTitle = find(R.id.tpBtnQueueTitle);
        if (queueTitle != null) {
            queueTitle.setOnClickListener(v -> MusicQueueDialog.show(this));
            attachPressScale(queueTitle, 0.96f);
        }
        if (playTopView != null) attachPressScale(playTopView, 0.96f);
        if (favHeartView != null) {
            favHeartView.setOnClickListener(v -> toggleFavorite());
            attachPressScale(favHeartView, 0.85f);
        }
        // 歌手／专辑可点：进歌手／专辑搜索（旧页同口径；无名时不可点、颜色走不可点档，见 bindTrackViews）
        if (artistView != null) artistView.setOnClickListener(v -> {
            MusicTrack t = MusicController.get().current();
            String a = t == null ? null : t.displayArtist();
            if (a != null && !a.isEmpty()) {
                try {
                    com.fongmi.android.tv.ui.activity.MusicSearchActivity.startSearch(this, a);
                } catch (Throwable ignored) {
                }
            }
        });
        if (albumView != null) albumView.setOnClickListener(v -> {
            MusicTrack t = MusicController.get().current();
            String al = t == null ? null : t.album;
            if (al != null && !al.isEmpty()) {
                try {
                    com.fongmi.android.tv.ui.activity.MusicSearchActivity.startSearch(this, al);
                } catch (Throwable ignored) {
                }
            }
        });
        View coverWrap = find(R.id.tpCoverWrap);
        if (coverWrap != null) attachCoverGestures(coverWrap);
        if (scrubberView != null) {
            scrubberView.setOnScrubListener(new MusicScrubberView.OnScrubListener() {
                @Override
                public void onScrubbing(float fraction) {
                    setTopTimesAlpha(0.72f);
                    long dur = MusicController.get().duration();
                    if (positionTopView != null) positionTopView.setText(formatTime(Math.round(dur * fraction)));
                }

                @Override
                public void onScrubCommit(float fraction) {
                    setTopTimesAlpha(0f);
                    long dur = MusicController.get().duration();
                    if (dur > 0) MusicController.get().seekTo(Math.round(dur * fraction));
                }
            });
        }
        View chipQuality = find(R.id.tpChipQuality);
        if (chipQuality != null) {
            chipQuality.setOnClickListener(v -> showQualityDialog());
            attachPressScale(chipQuality, 0.96f);
        }
        View chipLyric = find(R.id.tpChipLyric);
        if (chipLyric != null) {
            chipLyric.setOnClickListener(v -> openLyricVersionDialog());
            attachPressScale(chipLyric, 0.96f);
        }
        if (lyricView != null) {
            // 点行＝seek 到该行行首（定案；行时间是原始时间轴，偏移只作用于显示侧当前行判定）
            lyricView.setOnLineClickListener((index, positionMs) ->
                    MusicController.get().seekTo(positionMs));
        }
        wireBatch3Views();
    }

    /** 批 3 四主题接线（classic 下控件全 null、整段跳过）。 */
    private void wireBatch3Views() {
        if (coverTheme) wireCoverTheme();
        if (appleTheme) wireAppleTheme();
        if (cdTheme) wireCdTheme();
        if (shelfTheme) wireShelfTheme();
    }

    // ================= 批 3 D 路：流光 CD 接线 =================

    private void wireCdTheme() {
        View close = find(R.id.tpBtnClose);
        if (close != null) {
            close.setOnClickListener(v -> finish());
            attachPressScale(close, 0.9f);
        }
        View tools = find(R.id.tpBtnTools);
        if (tools != null) {
            tools.setOnClickListener(v -> showToolsSheet());
            attachPressScale(tools, 0.9f);
        }
        if (cdTitleView != null) cdTitleView.setSelected(true);
        if (cdPlayView != null) {
            cdPlayView.setOnClickListener(v -> MusicController.get().toggle());
            attachPressScale(cdPlayView, 0.9f);
        }
        View prev = find(R.id.tpCdBtnPrev);
        if (prev != null) {
            prev.setOnClickListener(v -> {
                lastSkipDirection = -1;
                MusicController.get().prev();
            });
            attachPressScale(prev, 0.9f);
        }
        View next = find(R.id.tpCdBtnNext);
        if (next != null) {
            next.setOnClickListener(v -> {
                lastSkipDirection = 1;
                MusicController.get().next();
            });
            attachPressScale(next, 0.9f);
        }
        if (cdBtnModeView != null) {
            cdBtnModeView.setOnClickListener(v -> {
                MusicController.get().cycleMode();
                paintCdModeIcon();
            });
            attachPressScale(cdBtnModeView, 0.9f);
        }
        View queue = find(R.id.tpCdBtnQueue);
        if (queue != null) {
            queue.setOnClickListener(v -> MusicQueueDialog.show(this));
            attachPressScale(queue, 0.9f);
        }
        View info = find(R.id.tpCdBtnInfo);
        if (info != null) {
            info.setOnClickListener(v -> setCdInfoMode(!cdInfoMode));
            attachPressScale(info, 0.9f);
        }
        View quality = find(R.id.tpCdBtnQuality);
        if (quality != null) {
            quality.setOnClickListener(v -> showQualityDialog());
            attachPressScale(quality, 0.9f);
        }
        View lyricVer = find(R.id.tpCdBtnLyricVer);
        if (lyricVer != null) {
            cdBtnLyricVerView = lyricVer;
            lyricVer.setOnClickListener(v -> openLyricVersionDialog());
            attachPressScale(lyricVer, 0.9f);
            paintCdLyricVerAlpha();
        }
        if (cdBtnFavView != null) {
            cdBtnFavView.setOnClickListener(v -> toggleFavorite());
            attachPressScale(cdBtnFavView, 0.9f);
        }
        View surface = find(R.id.tpCdBtnSurface);
        if (surface != null) {
            surface.setOnClickListener(v -> cycleCdSurface());
            attachPressScale(surface, 0.9f);
        }
        // 点摘要行＝展开歌词窗（§4 CdLyricSummary 点击走 LYRICS 详情并展开）；展开态点歌词窗空白区收起由窗自身点击处理
        if (cdSummaryBoxView != null) {
            cdSummaryBoxView.setOnClickListener(v -> {
                if (cdInfoMode) setCdInfoMode(false);
                setCdExpanded(true);
            });
        }
        if (cdLyricView != null) {
            cdLyricView.setOnLineClickListener((index, positionMs) ->
                    MusicController.get().seekTo(positionMs));
            cdLyricView.setOnClickListener(v -> {
                if (cdExpanded && !cdInfoMode) setCdExpanded(false);
            });
        }
        if (cdSeekView != null) {
            cdSeekView.setOnSeekListener(new CdSeekBarView.OnSeekListener() {
                @Override
                public void onSeeking(float fraction) {
                    long dur = MusicController.get().duration();
                    if (cdPositionView != null) cdPositionView.setText(formatTime(Math.round(dur * fraction)));
                }

                @Override
                public void onSeekCommit(float fraction) {
                    long dur = MusicController.get().duration();
                    if (dur > 0) MusicController.get().seekTo(Math.round(dur * fraction));
                }
            });
        }
        paintCdModeIcon();
        // 表面键切换进来时走 500ms 混色补间（渐变＋盘面材质），普通进页直落目标色
        boolean animateSurface = cdSurfaceSwitchPending;
        cdSurfaceSwitchPending = false;
        applyCdPalette(animateSurface);
    }

    /** 五图标模式键图标：按 MusicController 模式刷（映射照工具面板 mode 序核对：0 顺序／1 单曲／2 循环／3 随机）。 */
    private void paintCdModeIcon() {
        if (cdBtnModeView != null) cdBtnModeView.setImageResource(modeIconRes());
        if (shelfBtnModeView != null) shelfBtnModeView.setImageResource(modeIconRes());
    }

    private int modeIconRes() {
        int mode;
        try {
            mode = MusicController.get().getMode();
        } catch (Throwable ignored) {
            mode = 0;
        }
        switch (mode) {
            case 1:
                return R.drawable.ic_music_repeat_one;
            case 2:
                return R.drawable.ic_music_repeat;
            case 3:
                return R.drawable.ic_music_shuffle;
            default:
                return R.drawable.ic_music_sequential;
        }
    }

    /** 表面键循环 LIGHT→DARK→AUTO→LIGHT：回写主题 id 并整页重装（GZ setPlayerTheme(next.themeId()) 同路）。 */
    private void cycleCdSurface() {
        int next = (cdSurfaceMode + 1) % 3;
        cdSurfaceSwitchPending = true;
        MusicPlayerSettings.setTheme(next == 1 ? MusicPlayerSettings.THEME_LUMINOUS_CD_DARK
                : next == 2 ? MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO
                : MusicPlayerSettings.THEME_LUMINOUS_CD);
        Notify.show(next == 1 ? R.string.music_cd_surface_dark
                : next == 2 ? R.string.music_cd_surface_auto : R.string.music_cd_surface_light);
        applyTheme();
    }

    /**
     * CD 色板落码（CdPalette 源码值）：Pearl(LIGHT) base #F4F2EE／mist #E9EFF5／ink #282D36／muted #747D89；
     * Space(DARK) base＝mist #14161C／ink #F2F2F7／muted #8E96A3。任务简述的 #F1F0ED/#12100E 与源码不符，
     * 以源码为准（#F1F0ED 是唱片架底色），差异见 D 路报告。
     * 背景四段渐变（停点 0/0.34/0.68/1）：色式照 LuminousCdContent——
     * fresh＝lerp(base, tone, 0.22)；s0＝lerp(base, fresh, 0.92)；
     * s.34＝lerp(base, fresh, mixF(0.48, 0.73, bgMix))；s.68＝lerp(base, lerp(base, tone, 0.12), bgMix)；
     * s1＝lerp(mist, lerp(base, tone, 0.08), bgMix)；bgMix：LIGHT 0／DARK 1（混色走 RGB 近似，GZ 为 OkLab，见报告）。
     * 取色 tone＝封面主色经 CoverColor.toneAdjust（HSV 归一式与 §4 同式）；无封面回落 #8E8E93。
     * 取色变化 500ms 补间刷渐变（§4 500ms ValueAnimator）；表面切换的 500ms 混色补间走同一动画器
     * （animate=true 时从当前四色混到新表面四色，GZ CD_SURFACE_SWITCH_SPEC 500ms）。
     */
    private void applyCdPalette(boolean animate) {
        if (!cdTheme) return;
        boolean dark = cdDarkSurface;
        int base = dark ? 0xFF14161C : 0xFFF4F2EE;
        int mist = dark ? 0xFF14161C : 0xFFE9EFF5;
        int ink = dark ? 0xFFF2F2F7 : 0xFF282D36;
        int muted = dark ? 0xFF8E96A3 : 0xFF747D89;
        float bgMix = dark ? 1f : 0f;
        int tone = currentCdTone();
        int fresh = lerpColor(base, tone, 0.22f);
        int[] target = new int[4];
        target[0] = lerpColor(base, fresh, 0.92f);
        target[1] = lerpColor(base, fresh, dark ? 0.73f : 0.48f);
        target[2] = lerpColor(base, lerpColor(base, tone, 0.12f), bgMix);
        target[3] = lerpColor(mist, lerpColor(base, tone, 0.08f), bgMix);
        System.arraycopy(target, 0, cdBackdropTo, 0, 4);
        if (cdToneAnimator != null) cdToneAnimator.cancel();
        if (!animate || !cdBackdropInit || cdBackdropView == null) {
            System.arraycopy(target, 0, cdBackdropFrom, 0, 4);
            cdBackdropInit = true;
            paintCdBackdrop(1f);
        } else {
            // 从当前呈现色起混（cdBackdropFrom 在上次 paint 时已是呈现值，直接作起点）
            cdToneAnimator = ValueAnimator.ofFloat(0f, 1f);
            cdToneAnimator.setDuration(500);
            cdToneAnimator.addUpdateListener(a -> paintCdBackdrop((float) a.getAnimatedValue()));
            cdToneAnimator.start();
        }
        // 元素色（顶行/摘要/时间/图标 tint 统一走 ink/muted 系）
        if (titleView != null) titleView.setTextColor(ink);
        if (artistView != null) artistView.setTextColor(muted);
        if (cdSummaryMainView != null) cdSummaryMainView.setTextColor(ink);
        if (cdSummarySubView != null) cdSummarySubView.setTextColor(withAlpha(muted, 0.85f));
        int timeColor = withAlpha(muted, 0.74f);
        if (cdPositionView != null) cdPositionView.setTextColor(timeColor);
        if (cdDurationView != null) cdDurationView.setTextColor(timeColor);
        int accent = cdAccentColor(tone, ink, dark);
        if (cdSeekView != null) cdSeekView.setColors(withAlpha(ink, 0.18f), accent);
        if (cdLyricView != null) cdLyricView.setInkColor(ink);
        if (cdDiscView != null) {
            cdDiscView.setDarkSurface(dark);
            cdDiscView.setCoverTone(tone);
        }
        tintCdIcons(ink);
        paintFavoriteIcons();
        // 底距 max(16dp, 安全区)：安全区取导航栏 inset 近似（窗口 decor 底 inset 无现成值时用 16dp 底）
        View pad = find(R.id.tpCdBottomPad);
        if (pad != null) {
            int nav = navBarHeightPx();
            int want = Math.max(dpI(16), nav);
            android.view.ViewGroup.LayoutParams lp = pad.getLayoutParams();
            if (lp != null && lp.height != want) {
                lp.height = want;
                pad.setLayoutParams(lp);
            }
        }
    }

    private int currentCdTone() {
        MusicTrack track = MusicController.get().current();
        if (track == null || effectiveCover(track) == null) return 0xFF8E8E93;
        return CoverColor.toneAdjust(dominantColor);
    }

    /** CD accent：封面 tone 向 ink 混 0.52 后按表面亮度上限钳制（LIGHT 1.0 不钳／DARK 0.45，CdPalette.m95accent 口径近似）。 */
    private int cdAccentColor(int tone, int ink, boolean dark) {
        int mixed = lerpColor(tone, ink, 0.52f);
        if (!dark) return mixed;
        float[] hsv = new float[3];
        Color.colorToHSV(mixed, hsv);
        if (hsv[2] > 0.45f) {
            hsv[2] = 0.45f;
            return Color.HSVToColor(hsv);
        }
        return mixed;
    }

    private void paintCdBackdrop(float mix) {
        if (cdBackdropView == null) return;
        int[] colors = new int[4];
        for (int i = 0; i < 4; i++) {
            colors[i] = lerpColor(cdBackdropFrom[i], cdBackdropTo[i], mix);
        }
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
        gradient.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        // 停点：GradientDrawable 四色等距分布＝0/0.333/0.667/1，瓜子为 0/0.34/0.68/1——偏差 ≤0.013，
        // 视觉不可辨，记为已知近似（报告有载）。
        cdBackdropView.setBackground(gradient);
        if (mix >= 1f) System.arraycopy(cdBackdropTo, 0, cdBackdropFrom, 0, 4);
    }

    private void tintCdIcons(int ink) {
        ColorStateList tint = ColorStateList.valueOf(ink);
        int[] ids = {R.id.tpBtnClose, R.id.tpBtnTools, R.id.tpCdBtnInfo, R.id.tpCdBtnQuality,
                R.id.tpCdBtnLyricVer, R.id.tpCdBtnSurface, R.id.tpCdBtnMode, R.id.tpCdBtnPrev,
                R.id.tpCdBtnNext, R.id.tpCdBtnQueue, R.id.tpCdPlay};
        for (int id : ids) {
            View v = find(id);
            if (v instanceof ImageView) ((ImageView) v).setImageTintList(tint);
        }
        if (cdBtnFavView != null && !favorited) cdBtnFavView.setImageTintList(tint);
    }

    private static int lerpColor(int from, int to, float f) {
        float t = Math.max(0f, Math.min(1f, f));
        int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }

    private static int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(255f * Math.max(0f, Math.min(1f, alpha))),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private int navBarHeightPx() {
        int resId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return resId > 0 ? getResources().getDimensionPixelSize(resId) : 0;
    }

    private int statusBarHeightPx() {
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resId > 0 ? getResources().getDimensionPixelSize(resId) : 0;
    }

    /**
     * 顶行文字主题的状态栏留位（帧边缘）：本页全屏沉浸、内容自 y=0 起，
     * CD 与唱片架的顶行是文字行，不留位就跑进状态栏里（2026-10-25 真机点名唱片架，CD 同型一并修）；
     * 经典/Apple 顶是封面满幅（瓜子同形态、状态图标压封面）、封面卡片顶距 42dp 已过状态栏高，均不动。
     * CD 只垫内容列（取色渐变底保持满幅）；唱片架垫根列（根背景 #F1F0ED 含垫区、仍是满幅纯色）。
     */
    private void applyTopInsetForTheme() {
        if (cdTheme && cdBackdropView != null
                && cdBackdropView.getParent() instanceof android.view.ViewGroup) {
            // CD 根 FrameLayout 只有两个子：取色渐变底（tpCdBackdrop）＋内容列，内容列即另一个子
            android.view.ViewGroup root = (android.view.ViewGroup) cdBackdropView.getParent();
            for (int i = 0; i < root.getChildCount(); i++) {
                View child = root.getChildAt(i);
                if (child != cdBackdropView) {
                    child.setPadding(dpI(16), statusBarHeightPx(), dpI(16), child.getPaddingBottom());
                    break;
                }
            }
        }
        if (shelfTheme && themeRoot != null) {
            themeRoot.setPadding(0, statusBarHeightPx(), 0, 0);
        }
    }

    /** CD 展开/收起（§4：展开 600ms／收起 500ms；盘下整列 alpha＝clamp(1−1.6c)、展开区 alpha＝clamp((c−0.15)/0.85)、顶行标题 alpha＝clamp(1−1.4c)＋上移 20dp×c）。 */
    private void setCdExpanded(boolean expanded) {
        if (!cdTheme || cdExpanded == expanded) return;
        cdExpanded = expanded;
        if (cdCollapseAnimator != null) cdCollapseAnimator.cancel();
        float from = currentCdCollapse();
        float to = expanded ? 1f : 0f;
        cdCollapseAnimator = ValueAnimator.ofFloat(from, to);
        cdCollapseAnimator.setDuration(expanded ? 600 : 500);
        cdCollapseAnimator.addUpdateListener(a -> applyCdCollapse((float) a.getAnimatedValue()));
        cdCollapseAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!cdExpanded && cdDetailView != null) cdDetailView.setVisibility(View.GONE);
            }
        });
        if (expanded && cdDetailView != null) cdDetailView.setVisibility(View.VISIBLE);
        applyCdCollapse(from);
        cdCollapseAnimator.start();
    }

    private float cdCollapseValue;

    private float currentCdCollapse() {
        return cdCollapseValue;
    }

    private void applyCdCollapse(float c) {
        cdCollapseValue = c;
        if (cdBelowColView != null) {
            cdBelowColView.setAlpha(Math.max(0f, Math.min(1f, 1f - 1.6f * c)));
            cdBelowColView.setVisibility(c >= 0.99f ? View.INVISIBLE : View.VISIBLE);
        }
        if (cdDetailView != null) {
            cdDetailView.setAlpha(Math.max(0f, Math.min(1f, (c - 0.15f) / 0.85f)));
        }
        // 盘区让位（§4：展开时盘径随可用高收缩）：盘区与展开窗在列内按权重对调（盘 1−c／窗 c），
        // 盘不缩放不淡出、只随区高变小直至退场——旧叠层写法盘面留在窗后继续转，信息糊在盘上。
        if (cdDiscView != null && cdDetailView != null
                && cdDiscView.getParent() instanceof View) {
            // 盘区＝CdDiscView 的父 FrameLayout，与展开窗同为内容列直接子（布局口径）
            View discArea = (View) cdDiscView.getParent();
            if (discArea.getLayoutParams() instanceof LinearLayout.LayoutParams
                    && cdDetailView.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams discLp = (LinearLayout.LayoutParams) discArea.getLayoutParams();
                LinearLayout.LayoutParams detailLp = (LinearLayout.LayoutParams) cdDetailView.getLayoutParams();
                if (Math.abs(discLp.weight - (1f - c)) > 0.001f
                        || Math.abs(detailLp.weight - c) > 0.001f) {
                    discLp.weight = 1f - c;
                    detailLp.weight = c;
                    discArea.setLayoutParams(discLp);
                    cdDetailView.setLayoutParams(detailLp);
                }
            } else if (cdDiscView != null && cdDetailView != null) {
                // 横版 CD 是左盘右列、展开窗仍在盘区内叠层（星辰侧适配排布）：无列内让位可走，
                // 展开到位时把盘面本体藏掉，信息不压在转盘上（同病兜底）。
                cdDiscView.setVisibility(c >= 0.99f ? View.INVISIBLE : View.VISIBLE);
            }
        }
        View topRow = titleView == null ? null : (View) titleView.getParent();
        View topBar = topRow == null ? null : (View) topRow.getParent();
        if (topBar != null) {
            topBar.setAlpha(Math.max(0f, Math.min(1f, 1f - 1.4f * c)));
            topBar.setTranslationY(-20f * getResources().getDisplayMetrics().density * c);
        }
    }

    /** CD 信息态：展开窗内歌词↔信息切换（GZ LYRICS/SONG_INFO 两态）；信息内容复用 refreshInfoContent 构建。 */
    private void setCdInfoMode(boolean info) {
        if (!cdTheme) return;
        cdInfoMode = info;
        if (infoScrollView != null) infoScrollView.setVisibility(info ? View.VISIBLE : View.GONE);
        if (cdLyricView != null) cdLyricView.setVisibility(info ? View.GONE : View.VISIBLE);
        if (info) {
            if (!cdExpanded) setCdExpanded(true);
            refreshInfoContent();
        }
    }

    // ================= 批 3 D 路：黑胶唱片架接线 =================

    private void wireShelfTheme() {
        View close = find(R.id.tpBtnClose);
        if (close != null) {
            close.setOnClickListener(v -> finish());
            attachPressScale(close, 0.9f);
        }
        View tools = find(R.id.tpBtnTools);
        if (tools != null) {
            tools.setOnClickListener(v -> showToolsSheet());
            attachPressScale(tools, 0.9f);
        }
        if (titleView != null) titleView.setSelected(true);
        if (shelfPlayView != null) {
            shelfPlayView.setOnClickListener(v -> MusicController.get().toggle());
            attachPressScale(shelfPlayView, 0.9f);
        }
        View prev = find(R.id.tpShelfBtnPrev);
        if (prev != null) {
            prev.setOnClickListener(v -> {
                lastSkipDirection = -1;
                MusicController.get().prev();
            });
            attachPressScale(prev, 0.9f);
        }
        View next = find(R.id.tpShelfBtnNext);
        if (next != null) {
            next.setOnClickListener(v -> {
                lastSkipDirection = 1;
                MusicController.get().next();
            });
            attachPressScale(next, 0.9f);
        }
        if (shelfBtnModeView != null) {
            shelfBtnModeView.setOnClickListener(v -> {
                MusicController.get().cycleMode();
                paintCdModeIcon();
            });
            attachPressScale(shelfBtnModeView, 0.9f);
        }
        View queue = find(R.id.tpShelfBtnQueue);
        if (queue != null) {
            queue.setOnClickListener(v -> MusicQueueDialog.show(this));
            attachPressScale(queue, 0.9f);
        }
        if (shelfSeekView != null) {
            // 进度色（§5）：底轨 ShelfInk×0.12、填充＝语气色混 ShelfInk 0.65（h0，与歌词面同色）
            shelfSeekView.setColors(withAlpha(ShelfStageView.SHELF_INK, 0.12f), shelfToneColor());
            shelfSeekView.setOnSeekListener(new CdSeekBarView.OnSeekListener() {
                @Override
                public void onSeeking(float fraction) {
                    long dur = MusicController.get().duration();
                    if (shelfPositionView != null) shelfPositionView.setText(formatTime(Math.round(dur * fraction)));
                }

                @Override
                public void onSeekCommit(float fraction) {
                    long dur = MusicController.get().duration();
                    if (dur > 0) MusicController.get().seekTo(Math.round(dur * fraction));
                }
            });
        }
        if (shelfStageView != null) {
            shelfStageView.setListener(new ShelfStageView.Listener() {
                @Override
                public void onSwipeCommit(boolean next) {
                    lastSkipDirection = next ? 1 : -1;
                    if (next) MusicController.get().next();
                    else MusicController.get().prev();
                }

                @Override
                public void onDiscTap() {
                    // 收藏＝点唱片本身（§5：仅槽 0 且无滑动动画时，视图侧已守门）
                    toggleFavorite();
                }
            });
        }
        if (shelfLyricView != null) {
            shelfLyricView.setOnLineClickListener((index, positionMs) ->
                    MusicController.get().seekTo(positionMs));
            shelfLyricView.setPaletteColors(shelfToneColor(), 0xFF86857E);
        }
        // 架图标 tint 统一 ShelfInk（矢量自带灰蓝 tint 必须覆盖，经典布局同口径）
        ColorStateList ink = ColorStateList.valueOf(ShelfStageView.SHELF_INK);
        int[] ids = {R.id.tpBtnClose, R.id.tpBtnTools, R.id.tpShelfBtnMode, R.id.tpShelfBtnPrev,
                R.id.tpShelfBtnNext, R.id.tpShelfBtnQueue, R.id.tpShelfPlay};
        for (int id : ids) {
            View v = find(id);
            if (v instanceof ImageView) ((ImageView) v).setImageTintList(ink);
        }
        paintCdModeIcon();
    }

    /** 唱片架语气色 h0：封面 tone 向 ShelfInk 混 0.65（§5 palette／进度填充同源）。 */
    private int shelfToneColor() {
        int tone = CoverColor.toneAdjust(dominantColor);
        return lerpColor(tone, ShelfStageView.SHELF_INK, 0.65f);
    }

    private void wireCoverTheme() {
        // 前卡手势：单击＝切换展开/收起（§3 点封面展开，plain if 切换无专属动效）走手势检测的
        // onSingleTapConfirmed（见 attachCoverGestures 的 onCoverSingleTap）；双击/长按与经典同口径已挂 tpCoverWrap。
        // 封面区横滑切歌挂在 CoverStackView 拦截层（dig 三.13 参数在该类内），回调在此切歌并记方向给换卡动画。
        if (coverStackView instanceof CoverStackView) {
            ((CoverStackView) coverStackView).setOnHorizontalSwipeListener(next -> {
                lastSkipDirection = next ? 1 : -1;
                if (next) MusicController.get().next();
                else MusicController.get().prev();
            });
        }
        if (glassFavView != null) {
            glassFavView.setOnClickListener(v -> toggleFavorite());
            attachPressScale(glassFavView, 0.88f);
        }
        if (glassPlayView != null) {
            glassPlayView.setOnClickListener(v -> MusicController.get().toggle());
            attachPressScale(glassPlayView, 0.88f);
        }
        View glassBar = find(R.id.tpGlassBar);
        if (glassBar != null) {
            attachPressScale(glassBar, 0.98f);
            // 展开态整条都是「返回到封面模式」：歌词展开态与信息态点条一律回封面态
            // （旧口径只认信息态、歌词态点条无动作，与条上文案不符——2026-10-25 他真机点名「返回不了」）
            glassBar.setOnClickListener(v -> {
                if (coverExpanded) setCoverExpanded(false);
            });
        }
        // 展开态四玻璃键（源码实读顺序：上一首／播放列表／歌曲信息／下一首，图标 22dp、按压 0.88）
        View prev = find(R.id.tpBtnPrev);
        if (prev != null) {
            prev.setOnClickListener(v -> {
                lastSkipDirection = -1;
                MusicController.get().prev();
            });
            attachPressScale(prev, 0.88f);
        }
        View next = find(R.id.tpBtnNext);
        if (next != null) {
            next.setOnClickListener(v -> {
                lastSkipDirection = 1;
                MusicController.get().next();
            });
            attachPressScale(next, 0.88f);
        }
        View queue = find(R.id.tpBtnQueue);
        if (queue != null) {
            queue.setOnClickListener(v -> {
                // 展开态队列键：先退出信息态再开队列（GZ PlayerScreenKt.java:9187-9190）
                if (coverInfoMode) setCoverInfoMode(false);
                MusicQueueDialog.show(this);
            });
            attachPressScale(queue, 0.88f);
        }
        if (infoBtnView != null) {
            infoBtnView.setOnClickListener(v -> setCoverInfoMode(!coverInfoMode));
            attachPressScale(infoBtnView, 0.88f);
        }
        // 封面主题歌手按压 0.97（PlayerScreenKt.java:8633）
        if (artistView != null) attachPressScale(artistView, 0.97f);
    }

    private void wireAppleTheme() {
        ApplePagerView pager = find(R.id.tpPager);
        if (pager != null) pager.setPageImmediate(1);
        // Apple 歌手行按压 0.97（瓜子 Motion 压感字面值）；点击行为走 wireThemeViews 的共享接线
        if (artistView != null) attachPressScale(artistView, 0.97f);
        View fav = find(R.id.tpAppleFav);
        if (fav != null) {
            fav.setOnClickListener(v -> toggleFavorite());
            attachPressScale(fav, 0.88f);
            // 图标色 alpha 0.85 只压图标、不连带底色 0.14（GZ LiquidGlassButton 两参独立，
            // PlayerScreenKt.java:3146-3159）；布局已摘整视图 alpha，改走 imageAlpha。
            ((ImageView) fav).setImageAlpha(217);
        }
        View appleMenu = find(R.id.tpBtnMenu);
        if (appleMenu != null) {
            attachPressScale(appleMenu, 0.88f);
            ((ImageView) appleMenu).setImageAlpha(217);
        }
        // 专辑行按压 0.97（PlayerScreenKt.java:3138）
        if (albumView != null) attachPressScale(albumView, 0.97f);
        // 运输行（§2 按压：上下首 0.88、播放 0.9）
        View prev = find(R.id.tpBtnPrev);
        if (prev != null) {
            prev.setOnClickListener(v -> {
                lastSkipDirection = -1;
                MusicController.get().prev();
            });
            attachPressScale(prev, 0.88f);
        }
        View next = find(R.id.tpBtnNext);
        if (next != null) {
            next.setOnClickListener(v -> {
                lastSkipDirection = 1;
                MusicController.get().next();
            });
            attachPressScale(next, 0.88f);
        }
        if (playTopView != null) attachPressScale(playTopView, 0.9f);
        // 功能行：第一键是歌词菜单（不是进歌词页）——直开歌词版本弹窗（照瓜子歌词筹去向：
        // 点开即版本列表＋筛选签，空态带搜索入口；样式设置在工具面板歌词设置子面板，不在此串）
        View lyricMenu = find(R.id.tpBtnLyricMenu);
        if (lyricMenu != null) {
            lyricMenu.setOnClickListener(v -> openLyricVersionDialog());
            attachPressScale(lyricMenu, 0.88f);
        }
        View qualityFunc = find(R.id.tpBtnQualityFunc);
        if (qualityFunc != null) {
            qualityFunc.setOnClickListener(v -> showQualityDialog());
            attachPressScale(qualityFunc, 0.88f);
        }
        View queue = find(R.id.tpBtnQueue);
        if (queue != null) {
            queue.setOnClickListener(v -> MusicQueueDialog.show(this));
            attachPressScale(queue, 0.88f);
        }
        // 音量行：接系统媒体音量（星辰侧等价口径；瓜子是播放器内部音量 setPlayerVolume，星辰无此 API）
        if (volumeSliderView != null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            refreshVolumeSlider();
            volumeSliderView.setOnVolumeListener(fraction -> {
                if (audioManager == null) return;
                try {
                    int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
                            Math.round(fraction * max), 0);
                } catch (Throwable ignored) {
                }
            });
        }
        if (appleLyricView != null) {
            appleLyricView.setOnLineClickListener((index, positionMs) ->
                    MusicController.get().seekTo(positionMs));
        }
    }

    private void refreshVolumeSlider() {
        if (volumeSliderView == null || audioManager == null) return;
        try {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (max > 0) volumeSliderView.setFraction(cur / (float) max);
        } catch (Throwable ignored) {
        }
    }

    // ===== 封面卡片两态（§3：点封面展开；plain if 切换、无专属 transitionSpec 字面值） =====

    private void setCoverExpanded(boolean expanded) {
        coverExpanded = expanded;
        if (!expanded) coverInfoMode = false;
        if (coverStackView != null) coverStackView.setVisibility(expanded ? View.GONE : View.VISIBLE);
        if (expandedColView != null) expandedColView.setVisibility(expanded ? View.VISIBLE : View.GONE);
        paintGlassBarMode();
        if (expanded) applyInfoModeViews();
    }

    private void setCoverInfoMode(boolean info) {
        coverInfoMode = info;
        applyInfoModeViews();
        paintGlassBarMode();
    }

    private void applyInfoModeViews() {
        if (lyricView != null) lyricView.setVisibility(coverInfoMode ? View.GONE : View.VISIBLE);
        if (infoScrollView != null) {
            infoScrollView.setVisibility(coverInfoMode ? View.VISIBLE : View.GONE);
            if (coverInfoMode) refreshInfoContent();
        }
        if (infoBtnView != null) {
            // 信息态键底白 0.28、其余 0.14（§3 展开态四玻璃键）
            infoBtnView.setBackgroundResource(coverInfoMode
                    ? R.drawable.bg_player_glass_btn_info : R.drawable.bg_player_glass_btn);
        }
    }

    /** 玻璃条两态：默认态中槽＝单行歌词；展开态中槽＝「返回到封面模式」文案。
     * 收藏键与播放键两态都在（GZ PlayerScreenKt.java:9680-9703 全行结构），只换中槽。 */
    private void paintGlassBarMode() {
        boolean backMode = coverExpanded;
        if (glassLyricView != null) glassLyricView.setVisibility(backMode ? View.GONE : View.VISIBLE);
        if (glassBackView != null) glassBackView.setVisibility(backMode ? View.VISIBLE : View.GONE);
    }

    /** 按压缩放（不吞点击）：按下缩、松开回。 */
    private void attachPressScale(View view, float scale) {
        if (view == null) return;
        view.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(scale).scaleY(scale).setDuration(150).setInterpolator(INTERP_FOSI).start();
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(INTERP_FOSI).start();
            }
            return false;
        });
    }

    private void setTopTimesAlpha(float alpha) {
        if (positionTopView != null) {
            positionTopView.animate().alpha(alpha).setDuration(150).setInterpolator(INTERP_FOSI).start();
        }
        if (durationTopView != null) {
            durationTopView.animate().alpha(alpha).setDuration(150).setInterpolator(INTERP_FOSI).start();
        }
    }

    private static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    // ================= 播放态 =================

    private android.animation.ObjectAnimator playBreathAnimator;
    /** 经典小爱心 tint 300ms 动画状态（GZ songMetaFavTint）。 */
    private boolean favTintInit;
    private int favTintColor;
    private ValueAnimator favTintAnimator;

    /**
     * 通知不可用明示（播放页）：起播后首次检出不可用才挂条，同进程只挂一次，
     * 不反复弹；检测只读系统状态（Notify.notificationBlockReason），不拖起播。
     * 挂条后从设置页回页（onResume）复检，恢复则自动撤下。
     */
    private void maybeShowNotifHint() {
        if (notifHintShownOnce || binding == null || binding.notifHintStrip == null) return;
        if (binding.notifHintStrip.getVisibility() == View.VISIBLE) return;
        int reason = Notify.notificationBlockReason(this);
        if (reason == Notify.NOTIF_OK) return;
        notifHintShownOnce = true;
        if (reason == Notify.NOTIF_NO_PERMISSION) {
            binding.notifHintText.setText("通知权限未开启，后台放歌时通知栏不会显示播放条");
        } else if (reason == Notify.NOTIF_APP_OFF) {
            binding.notifHintText.setText("通知已被系统关闭，后台放歌时通知栏不会显示播放条");
        } else if (reason == Notify.NOTIF_CHANNEL_OFF) {
            binding.notifHintText.setText("播放通知渠道已被关闭，后台放歌时通知栏不会显示播放条");
        } else {
            binding.notifHintText.setText("播放通知渠道未就绪，后台放歌时通知栏不会显示播放条");
        }
        binding.notifHintStrip.setVisibility(View.VISIBLE);
    }

    /** 提示条挂着时回页复检：通知恢复可用即自动撤下（仍不可用则原样留着，不重挂不闪烁）。 */
    private void refreshNotifHintOnResume() {
        if (binding == null || binding.notifHintStrip == null) return;
        if (binding.notifHintStrip.getVisibility() != View.VISIBLE) return;
        if (Notify.notificationBlockReason(this) == Notify.NOTIF_OK) {
            binding.notifHintStrip.setVisibility(View.GONE);
        }
    }

    private void setPlaying(boolean value) {
        playing = value;
        if (value) maybeShowNotifHint();
        syncRhythmSource();
        if (lyricView != null) lyricView.setPlaying(value);
        if (appleLyricView != null) appleLyricView.setPlaying(value);
        if (lyricPageView != null) lyricPageView.setPlaying(value);
        if (cdTheme) {
            // CD 主控播放键：盒 68dp；图标播放态（显暂停）54dp／暂停态（显播放）58dp——padding (68−54)/2=7／(68−58)/2=5
            if (cdPlayView != null) {
                cdPlayView.setImageResource(value ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
                int pad = Math.round((value ? 7f : 5f) * getResources().getDisplayMetrics().density);
                cdPlayView.setPadding(pad, pad, pad, pad);
            }
            if (cdDiscView != null) cdDiscView.setPlaying(value);
            if (cdLyricView != null) cdLyricView.setPlaying(value);
            return;
        }
        if (shelfTheme) {
            // 唱片架播放键：盒 68dp、图标 52dp 两态同尺寸（布局 padding 8dp 已给）
            if (shelfPlayView != null) {
                shelfPlayView.setImageResource(value ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
            }
            if (shelfStageView != null) shelfStageView.setPlaying(value);
            if (shelfLyricView != null) shelfLyricView.setPlaying(value);
            return;
        }
        // 封面卡片玻璃条播放键（盒 40dp、图标 24dp 由布局给）；
        // 图标 alpha＝有曲 1.0／无曲 0.35（GZ PlayerScreenKt.java:9789,9794；加载态另记对照表，星辰无加载信号）
        if (glassPlayView != null) {
            glassPlayView.setImageResource(value ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
            boolean hasTrack = false;
            try {
                hasTrack = MusicController.get().current() != null;
            } catch (Throwable ignored) {
            }
            glassPlayView.setAlpha(hasTrack ? 1f : 0.35f);
        }
        // Apple 运输行：播放键只切图标（尺寸/按压由布局与接线给）；禁用 alpha 在 bindTrackViews 刷
        if (appleTheme) {
            if (playTopView != null) {
                playTopView.setImageResource(value ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
            }
            return;
        }
        if (playTopView == null) return;
        // 三键播放/暂停图标随状态切尺寸（瓜子 PlayerScreenKt.java:25153-25154,25267：
        // 播放中＝暂停图标 28dp（padding 6dp）；已暂停＝播放图标 25dp（padding 7.5dp）；300ms tween 用 padding 补间近似）。
        playTopView.setImageResource(value ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        int pad = Math.round((value ? 6f : 7.5f) * getResources().getDisplayMetrics().density);
        ValueAnimator padAnim = ValueAnimator.ofInt(playTopView.getPaddingLeft(), pad);
        // 300ms 缓动曲线 GZ 实证（2026-10-25 深挖）：playPauseSize tween(300, bs4.a=(0.2,0,0,1))＝INTERP_FOSI
        padAnim.setDuration(300);
        padAnim.setInterpolator(INTERP_FOSI);
        padAnim.addUpdateListener(a -> {
            int p = (int) a.getAnimatedValue();
            playTopView.setPadding(p, p, p, p);
        });
        padAnim.start();
        // 暂停态呼吸（§1 playBreath 0.28→1.0、800ms）：仅暂停态跑，播放态停并复位
        if (playBreathAnimator != null) {
            playBreathAnimator.cancel();
            playBreathAnimator = null;
        }
        if (!value) {
            playTopView.setAlpha(0.28f);
            playBreathAnimator = android.animation.ObjectAnimator.ofFloat(playTopView, "alpha", 0.28f, 1.0f);
            playBreathAnimator.setDuration(800);
            playBreathAnimator.setRepeatCount(ValueAnimator.INFINITE);
            playBreathAnimator.setRepeatMode(ValueAnimator.REVERSE);
            playBreathAnimator.start();
        } else {
            playTopView.setAlpha(1f);
        }
    }

    private void onProgressUi(long position, long duration) {
        if (scrubberView != null && duration > 0) {
            scrubberView.setFraction(position / (float) duration);
            if (!scrubberView.isDragging() && positionTopView != null) {
                positionTopView.setText(formatTime(position));
            }
            if (durationTopView != null) durationTopView.setText(formatTime(duration));
        }
        if (lyricView != null) {
            lyricView.setPosition(position + MusicPlayerSettings.getLyricOffsetMs());
        }
        if (appleLyricView != null) {
            appleLyricView.setPosition(position + MusicPlayerSettings.getLyricOffsetMs());
        }
        if (cdTheme) {
            long shifted = position + MusicPlayerSettings.getLyricOffsetMs();
            if (cdLyricView != null) cdLyricView.setPosition(shifted);
            if (cdSeekView != null && duration > 0) {
                cdSeekView.setFraction(position / (float) duration);
                if (!cdSeekView.isDragging() && cdPositionView != null) {
                    cdPositionView.setText(formatTime(position));
                }
                if (cdDurationView != null) cdDurationView.setText(formatTime(duration));
            }
            updateCdSummary();
        }
        if (shelfTheme) {
            long shifted = position + MusicPlayerSettings.getLyricOffsetMs();
            if (shelfLyricView != null) shelfLyricView.setPosition(shifted);
            if (shelfSeekView != null && duration > 0) {
                shelfSeekView.setFraction(position / (float) duration);
                if (!shelfSeekView.isDragging() && shelfPositionView != null) {
                    shelfPositionView.setText(formatTime(position));
                }
                if (shelfDurationView != null) shelfDurationView.setText(formatTime(duration));
            }
        }
        updateGlassLyricLine();
        if (lyricPageView != null) {
            lyricPageView.setLyricPosition(position + MusicPlayerSettings.getLyricOffsetMs());
        }
    }

    /** 玻璃条单行歌词（§3：当前行文本，13sp 居中由布局给）：随内嵌歌词当前行走，无词时显示无词文案。
     * 批 7：取生效主行（主歌词键提升后显示提升文本，与各歌词面同口径）。 */
    private void updateGlassLyricLine() {
        if (glassLyricView == null) return;
        String text;
        boolean placeholder;
        List<LyricsLine> mains = effectiveMains();
        if (mains.isEmpty()) {
            text = getString(R.string.music_no_lyric);
            placeholder = true;
        } else {
            int idx = lyricView == null ? -1 : lyricView.currentIndex();
            if (idx < 0) idx = 0;
            if (idx >= mains.size()) idx = mains.size() - 1;
            text = mains.get(idx).getText();
            if (text == null) text = "";
            placeholder = text.isEmpty();
            if (placeholder) text = " · · · ";
        }
        // 空态口径（GZ PlayerScreenKt.java:8217,8225-8229）：无词与空行都显占位、alpha 0.45；正常行 0.92。
        if (!text.equals(glassLyricShown) || placeholder != glassLyricPlaceholder) {
            glassLyricShown = text;
            glassLyricPlaceholder = placeholder;
            glassLyricView.setText(text);
            glassLyricView.setTextColor(placeholder ? 0x73FFFFFF : 0xEBFFFFFF);
        }
    }

    /** CD 摘要行（§4 CdLyricSummary）：主行＝当前歌词行预览 16sp 粗、副行＝当前行副文本（翻译）12sp；无词时主行走无词提示。 */
    private void updateCdSummary() {
        if (cdSummaryMainView == null) return;
        String main;
        String sub = "";
        List<LyricsLine> mains = effectiveMains();
        if (mains.isEmpty()) {
            main = getString(R.string.music_no_lyric);
        } else {
            int idx = cdLyricView == null ? -1 : cdLyricView.currentIndex();
            if (idx < 0) idx = 0;
            if (idx >= mains.size()) idx = mains.size() - 1;
            main = mains.get(idx).getText();
            if (main == null) main = "";
            List<String> subs = subLines();
            if (idx < subs.size() && subs.get(idx) != null) sub = subs.get(idx);
        }
        String key = main + "\u0001" + sub;
        if (!key.equals(cdSummaryShown)) {
            cdSummaryShown = key;
            cdSummaryMainView.setText(main);
            if (cdSummarySubView != null) {
                cdSummarySubView.setText(sub);
                cdSummarySubView.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
            }
        }
    }

    // ================= 歌曲绑定 =================

    private void bindTrack(MusicTrack track) {
        lastBoundTrack = track;
        syncRhythmSource();
        bindTrackViews(track);
        if (track == null) {
            if (lyricView != null) {
                lyricView.clearLines();
                lyricView.setEmptyText(getString(R.string.music_lyric_chip_none));
            }
            if (cdLyricView != null) cdLyricView.clearLines();
            if (shelfLyricView != null) shelfLyricView.clearLines();
            if (appleLyricView != null) appleLyricView.clearLines();
            if (lyricPageView != null) {
                lyricPageView.setLyricData(new ArrayList<>(), new ArrayList<>(), false);
                lyricPageView.setEmptyText(getString(R.string.music_lyric_chip_none));
            }
            return;
        }
        loadLyric(track, false);
        refreshFavorite(track);
        refreshQualityValue();
    }

    private void bindTrackViews(MusicTrack track) {
        String newTitle = track == null ? getString(R.string.music_no_track) : track.displayTitle();
        String newArtist = track == null ? "" : track.displayArtist();
        if (track != null && com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(newArtist)) {
            newArtist = getString(R.string.music_unknown_artist);
        }
        if (titleView != null) titleView.setText(newTitle);
        if (artistView != null) {
            artistView.setText(newArtist);
            if (coverTheme) {
                // 封面卡片歌手色（§3）：可点（有歌手名）白 0.65／不可点 0.55
                artistView.setTextColor(newArtist == null || newArtist.isEmpty() ? 0x8CFFFFFF : 0xA6FFFFFF);
            } else if (cdTheme) {
                // CD 歌手色＝当前表面 muted（applyCdPalette 同步刷，此处为绑定时序兜底同值）
                artistView.setTextColor(cdDarkSurface ? 0xFF8E96A3 : 0xFF747D89);
            } else if (shelfTheme) {
                // 唱片架歌手色＝ShelfMuted（布局同值，防落进经典白系分支）
                artistView.setTextColor(0xFF86857E);
            } else if (appleTheme) {
                // Apple 歌手色（§2）：白 0.60 固定
                artistView.setTextColor(0x99FFFFFF);
            } else {
                // 经典歌手色：可点（有歌手名）白 0.45／不可点 0.34（§1）
                artistView.setTextColor(newArtist == null || newArtist.isEmpty() ? 0x57FFFFFF : 0x73FFFFFF);
            }
        }
        if (albumView != null) {
            String album = track == null ? null : track.album;
            boolean hide = album == null || album.isEmpty()
                    || (appleTheme && getString(R.string.music_unknown_album).equals(album));
            if (hide) {
                // 专辑空则整行 GONE（§1；Apple 另含「未知专辑」整行隐藏，§2/D-08 口径）
                albumView.setVisibility(View.GONE);
            } else {
                albumView.setText(album);
                albumView.setVisibility(View.VISIBLE);
                albumView.setTextColor(appleTheme ? 0x6BFFFFFF : 0x73FFFFFF);
            }
        }
        paintTransportState(track);
        refreshInfoContent();
        if (lyricPageView != null) {
            lyricBgBuildUrl = null;
            lyricPageView.setTrackInfo(newTitle, lyricPageArtistLine(newArtist, track == null ? null : track.album));
            lyricPageView.setCover(effectiveCover(track), newTitle);
            lyricPageView.setToneColor(ambientColor);
            applyLyricPageBackground();
        }
        bindCovers(track);
    }

    /**
     * Apple 运输键禁用态（§2：启用 alpha 上下首 0.9／播放 0.95、禁用 0.35；
     * 可用性取队列相邻序号，无队列位置时按无相邻处理）。仅 Apple 主题有这三个键的 id 组合。
     */
    private void paintTransportState(MusicTrack track) {
        if (!appleTheme) return;
        // 运输三键共用同一 enabled 标志（GZ PlayerScreenKt.java:1451,1498,1537 的 z 同源）：
        // 启用 上下首 0.9／播放 0.95、禁用一律 0.35；不再按队列相邻分键判定。
        boolean enabled = track != null;
        View prev = find(R.id.tpBtnPrev);
        View next = find(R.id.tpBtnNext);
        if (prev != null) prev.setAlpha(enabled ? 0.9f : 0.35f);
        if (next != null) next.setAlpha(enabled ? 0.9f : 0.35f);
        if (playTopView != null) playTopView.setAlpha(enabled ? 0.95f : 0.35f);
        // 功能行三键透明度：enabled 0.5／禁用 0.25（PlayerScreenKt.java:1291-1293）
        float funcAlpha = enabled ? 0.5f : 0.25f;
        View lyricMenu = find(R.id.tpBtnLyricMenu);
        if (lyricMenu != null) lyricMenu.setAlpha(funcAlpha);
        View qualityFunc = find(R.id.tpBtnQualityFunc);
        if (qualityFunc != null) qualityFunc.setAlpha(funcAlpha);
        View queueFunc = find(R.id.tpBtnQueue);
        if (queueFunc != null) queueFunc.setAlpha(funcAlpha);
    }

    /** 当前曲应显示的封面地址：自定义封面优先（本地相册选图），否则原封面（旧页同口径）。 */
    private String effectiveCover(MusicTrack track) {
        if (track == null) return null;
        String custom = MusicStore.getSongCover(MusicStore.songCoverKey(track));
        if (custom != null && !custom.isEmpty()) return custom;
        return track.cover;
    }

    private void loadIntoCover(ImageView view, String cover, String title) {
        if (view == null) return;
        if (cover == null || cover.isEmpty()) {
            // 没封面地址才上素图；有地址的加载失败也保持透明，不变素图（封面占位定案）
            view.setImageDrawable(new CoverTextDrawable(title, false,
                    28 * getResources().getDisplayMetrics().density));
        } else {
            ImgUtil.load(title, cover, view);
        }
    }

    private void bindCovers(MusicTrack track) {
        String cover = effectiveCover(track);
        String title = track == null ? "" : track.displayTitle();
        if (coverTheme) {
            bindCoverThemeCovers(track, cover, title);
            return;
        }
        if (appleTheme) {
            bindAppleCovers(track, cover, title);
            return;
        }
        if (cdTheme) {
            bindCdCovers(track, cover);
            return;
        }
        if (shelfTheme) {
            bindShelfCovers(track);
            return;
        }
        // 出场层：切歌前把旧封面暂存（首绑旧层无图不建出场动画）——CoverTransitionSpec.Classic 出场侧
        boolean stagedExit = track != null && coverView != null && coverExitView != null && coverView.getDrawable() != null;
        if (stagedExit) {
            coverExitGen++;
            coverExitView.setImageDrawable(coverView.getDrawable());
            coverExitView.setVisibility(View.VISIBLE);
            coverExitView.setAlpha(1f);
            coverExitView.setScaleX(1f);
            coverExitView.setScaleY(1f);
            coverExitView.setTranslationY(0f);
        }
        loadIntoCover(coverView, cover, title);
        updateBackground(track);
        if (coverView != null && track != null) {
            // 入场（Classic CoverTransitionSpec 实证：400ms、bs4.e 缓动、竖向滑移 0.5H＋缩放 0.94→1；
            // 方向 2026-10-25 深挖补实：transitionDir 下一首 +1＝新封面自下方入、旧封面上行出，
            // 上一首 −1 反向。星辰 lastSkipDirection 与 transitionDir 同号（next=+1），直接乘）
            int dir = lastSkipDirection >= 0 ? 1 : -1;
            int h = coverView.getHeight();
            float slide = (h > 0 ? h : 300 * getResources().getDisplayMetrics().density) * 0.5f;
            coverView.setAlpha(0f);
            coverView.setScaleX(0.94f);
            coverView.setScaleY(0.94f);
            coverView.setTranslationY(dir * slide);
            coverView.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(400).setInterpolator(INTERP_EMPH).start();
            if (stagedExit) {
                final int gen = coverExitGen;
                coverExitView.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).translationY(-dir * slide)
                        .setDuration(400).setInterpolator(INTERP_EMPH)
                        .withEndAction(() -> {
                            if (coverExitView != null && coverExitGen == gen) {
                                coverExitView.setVisibility(View.GONE);
                                coverExitView.setImageDrawable(null);
                            }
                        }).start();
            }
        }
    }

    /**
     * 封面卡片切歌换卡（dig 三.16 ThreeCard alternate：350ms；入场整幅滑入＋淡入＋缩放 0.94→1、
     * 出场滑出＋淡出＋缩放→0.92；方向取 lastSkipDirection）。后叠卡装队列相邻曲封面
     * （左后＝上一首、右后＝下一首；无相邻时回落当前封面——后卡曲目对应关系未在源码中逐字抠实，见报告）。
     * 展开态下叠卡不可见：只换图不做动画。
     */
    private void bindCoverThemeCovers(MusicTrack track, String cover, String title) {
        boolean animate = track != null && coverStackView != null
                && coverStackView.getVisibility() == View.VISIBLE
                && coverView != null && coverView.getDrawable() != null && cardExitView != null;
        android.graphics.drawable.Drawable oldFront = animate ? coverView.getDrawable() : null;
        loadIntoCover(coverView, cover, title);
        // 后叠卡是空占位卡（GZ CoverImmersivePlaceholderCard），布局已挂 bg_cover_card_back、不装封面图
        updateBackground(track);
        if (!animate || coverView == null) return;
        int dir = lastSkipDirection >= 0 ? 1 : -1;
        float w = coverStackView.getWidth() > 0 ? coverStackView.getWidth()
                : 300 * getResources().getDisplayMetrics().density;
        coverExitGen++;
        final int gen = coverExitGen;
        cardExitView.setImageDrawable(oldFront);
        cardExitView.setVisibility(View.VISIBLE);
        cardExitView.setAlpha(1f);
        cardExitView.setScaleX(1f);
        cardExitView.setScaleY(1f);
        cardExitView.setTranslationX(0f);
        // 缓动分工（GZ PlayerScreenKt.java:26882-26895；bs4.java:6,8,20）：滑移/缩放走出场曲线 (0.3,0,1,1)，
        // 淡出单独走 (0.2,0,0,1)——ViewPropertyAnimator 只有一个插值器，alpha 拆 ObjectAnimator 并行。
        cardExitView.animate().translationX(-dir * w).scaleX(0.92f).scaleY(0.92f)
                .setDuration(350).setInterpolator(INTERP_EXIT)
                .withEndAction(() -> {
                    if (cardExitView != null && coverExitGen == gen) {
                        cardExitView.setVisibility(View.GONE);
                        cardExitView.setImageDrawable(null);
                        cardExitView.setTranslationX(0f);
                    }
                }).start();
        android.animation.ObjectAnimator exitFade = android.animation.ObjectAnimator.ofFloat(cardExitView, "alpha", 1f, 0f);
        exitFade.setDuration(350);
        exitFade.setInterpolator(INTERP_FOSI);
        exitFade.start();
        View wrap = find(R.id.tpCoverWrap);
        if (wrap != null) {
            wrap.setTranslationX(dir * w);
            wrap.setAlpha(0f);
            wrap.setScaleX(0.94f);
            wrap.setScaleY(0.94f);
            wrap.animate().translationX(0f).scaleX(1f).scaleY(1f)
                    .setDuration(350).setInterpolator(INTERP_EMPH).start();
            android.animation.ObjectAnimator entryFade = android.animation.ObjectAnimator.ofFloat(wrap, "alpha", 0f, 1f);
            entryFade.setDuration(350);
            entryFade.setInterpolator(INTERP_FOSI);
            entryFade.start();
        }
    }

    /**
     * Apple 切歌转场（CoverTransitionSpec.AppleMusic：600ms、入场滑移 0／缩放 0.97→1、
     * 出场滑移 0／缩放→1.03／alpha→0）。
     */
    private void bindAppleCovers(MusicTrack track, String cover, String title) {
        boolean stagedExit = track != null && coverView != null && coverExitView != null
                && coverView.getDrawable() != null;
        if (stagedExit) {
            coverExitGen++;
            coverExitView.setImageDrawable(coverView.getDrawable());
            coverExitView.setVisibility(View.VISIBLE);
            coverExitView.setAlpha(1f);
            coverExitView.setScaleX(1f);
            coverExitView.setScaleY(1f);
        }
        loadIntoCover(coverView, cover, title);
        updateBackground(track);
        if (coverView == null || track == null) return;
        coverView.setAlpha(0f);
        coverView.setScaleX(0.97f);
        coverView.setScaleY(0.97f);
        coverView.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(600).setInterpolator(INTERP_FOSI).start();
        if (stagedExit) {
            final int gen = coverExitGen;
            coverExitView.animate().alpha(0f).scaleX(1.03f).scaleY(1.03f)
                    .setDuration(600).setInterpolator(INTERP_FOSI)
                    .withEndAction(() -> {
                        if (coverExitView != null && coverExitGen == gen) {
                            coverExitView.setVisibility(View.GONE);
                            coverExitView.setImageDrawable(null);
                        }
                    }).start();
        }
    }

    /**
     * 流光 CD 封面装配：位图喂 CdDiscView（有旧图走切歌扫光、方向取 lastSkipDirection；
     * 无封面地址＝盘面走 tone 素色，没地址才素图、无图不扫——封面占位定案：有地址加载失败保持现状不换素图，
     * 异步回调以 cover 地址核对当前曲，防迟到回填）。背景取色仍走 updateBackground 主链
     * （dominantColor 更新点回调 refreshToneDrivenSurfaces 刷 CD 渐变）。
     */
    private void bindCdCovers(MusicTrack track, String cover) {
        updateBackground(track);
        if (cdDiscView == null) return;
        if (track == null || cover == null || cover.isEmpty()) {
            cdDiscView.setCoverBitmap(null, 1);
            return;
        }
        // 盘面显示径上限 400dp（§4 瓜子值）：按显示尺寸取图，不用 96dp 小图拉糊
        ImgUtil.load(cover, 400, new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                if (destroyed || !cdTheme) return;
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cover.equals(effectiveCover(cur))) return;
                cdDiscView.setCoverBitmap(resource, lastSkipDirection);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                // 有地址失败：不换素图、不清盘（占位定案）
            }
        });
    }

    /**
     * 唱片架三槽封面装配（X-14）：三槽位图异步加载后一次喂入 ShelfStageView.setCovers，
     * 只换内容不动布局；代次守卫防迟到回填串歌。无相邻曲时该槽 exists=false 不绘。
     */
    private void bindShelfCovers(MusicTrack track) {
        updateBackground(track);
        if (shelfStageView == null) return;
        shelfCoverGen++;
        final int gen = shelfCoverGen;
        MusicTrack prev = null;
        MusicTrack next = null;
        try {
            List<MusicTrack> queue = MusicController.get().queue();
            int prevIdx = MusicController.get().queuePrevIndex();
            int nextIdx = MusicController.get().queueNextIndex();
            if (queue != null) {
                if (prevIdx >= 0 && prevIdx < queue.size()) prev = queue.get(prevIdx);
                if (nextIdx >= 0 && nextIdx < queue.size()) next = queue.get(nextIdx);
            }
        } catch (Throwable ignored) {
        }
        final MusicTrack prevTrack = prev;
        final MusicTrack nextTrack = next;
        final String title = track == null ? "" : track.displayTitle();
        String curKey = track == null ? null : MusicQueue.trackKey(track);
        String prevKey = prevTrack == null ? null : MusicQueue.trackKey(prevTrack);
        String nextKey = nextTrack == null ? null : MusicQueue.trackKey(nextTrack);
        // 连续翻页对账：切到的正是相邻槽时不许清屏重装（旧路三槽先清 null、封面逐张
        // 弹入＝切歌跳变源头）。①舞台横滑提交：视图已自转槽位（consumeJustShifted 对账），
        // 宿主字段同序自转后直接回喂；②按钮／自动切到相邻槽：字段先自转，舞台动画滑到
        // 相邻位、落位自转后回调回喂。两路都只差最外侧新槽封面，异步加载补齐。
        // 对不上（跳选任意曲／队列变了）走旧清屏装配。
        int shiftedDir = shelfStageView.consumeJustShifted(curKey);
        int advanceDir = 0;
        if (shiftedDir != 0) {
            rotateShelfFields(shiftedDir);
            shelfFeedDeferred = false;
            feedShelfCovers(title);
        } else if (curKey != null && curKey.equals(shelfNextKey)) {
            advanceDir = 1;
        } else if (curKey != null && curKey.equals(shelfPrevKey)) {
            advanceDir = -1;
        }
        if (advanceDir != 0) {
            rotateShelfFields(advanceDir);
            shelfFeedDeferred = true;
            final int dir = advanceDir;
            shelfStageView.advanceAnimated(dir > 0, () -> {
                if (destroyed || gen != shelfCoverGen) return;
                shelfFeedDeferred = false;
                feedShelfCovers(title);
            });
        } else if (shiftedDir == 0) {
            shelfBmpPrev = null;
            shelfBmpCur = null;
            shelfBmpNext = null;
            shelfFeedDeferred = false;
            shelfStageView.resetOffset();
            shelfStageView.setCovers(null, null, null, prevTrack != null, nextTrack != null, title);
        }
        shelfPrevKey = prevKey;
        shelfCurKey = curKey;
        shelfNextKey = nextKey;
        shelfStageView.noteSlotKeys(prevKey, curKey, nextKey);
        loadShelfSlot(track, gen, 0, title);
        loadShelfSlot(prevTrack, gen, -1, title);
        loadShelfSlot(nextTrack, gen, 1, title);
        refreshShelfToneSurfaces();
    }

    /** 宿主持有的三槽位图随切歌自转一槽（与 ShelfStageView.settleIntoNeighbor 同序同向）。 */
    private void rotateShelfFields(int dir) {
        if (dir > 0) {
            shelfBmpPrev = shelfBmpCur;
            shelfBmpCur = shelfBmpNext;
            shelfBmpNext = null;
        } else {
            shelfBmpNext = shelfBmpCur;
            shelfBmpCur = shelfBmpPrev;
            shelfBmpPrev = null;
        }
    }

    /** 按宿主当前字段整组回喂舞台（存在位以控制器队列口径为准）。 */
    private void feedShelfCovers(String title) {
        if (shelfStageView == null) return;
        shelfStageView.setCovers(shelfBmpPrev, shelfBmpCur, shelfBmpNext,
                hasShelfNeighbor(-1), hasShelfNeighbor(1), title);
    }

    private void loadShelfSlot(MusicTrack slotTrack, int gen, int slot, String title) {
        if (slotTrack == null) return;
        String cover = effectiveCover(slotTrack);
        if (cover == null || cover.isEmpty()) return;
        // 袖套封面铺满袖面（显示尺寸远超 96dp）：按 400dp 取图
        ImgUtil.load(cover, 400, new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                if (destroyed || !shelfTheme || gen != shelfCoverGen || shelfStageView == null) return;
                if (slot == 0) shelfBmpCur = resource;
                else if (slot < 0) shelfBmpPrev = resource;
                else shelfBmpNext = resource;
                // 相邻翻页动画在途：先只存字段，落位回调统一回喂（防动画中途整图跳换）
                if (shelfFeedDeferred) return;
                shelfStageView.setCovers(shelfBmpPrev, shelfBmpCur, shelfBmpNext,
                        hasShelfNeighbor(-1), hasShelfNeighbor(1), title);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                // 有地址失败：该槽保持无图素底（占位定案，不换别图）
            }
        });
    }

    private boolean hasShelfNeighbor(int dir) {
        try {
            return dir < 0 ? MusicController.get().queuePrevIndex() >= 0
                    : MusicController.get().queueNextIndex() >= 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 取色驱动面刷新：CD 渐变（500ms 补间）／唱片架歌词 palette 与进度填充（h0 随 tone 变）。 */
    private void refreshToneDrivenSurfaces() {
        if (cdTheme) applyCdPalette(true);
        if (shelfTheme) refreshShelfToneSurfaces();
    }

    private void refreshShelfToneSurfaces() {
        if (!shelfTheme) return;
        int h0 = shelfToneColor();
        if (shelfLyricView != null) shelfLyricView.setPaletteColors(h0, 0xFF86857E);
        if (shelfSeekView != null) shelfSeekView.setColors(withAlpha(ShelfStageView.SHELF_INK, 0.12f), h0);
    }

    // ================= 氛围背景（双路径） =================

    private void clearClassicRenderEffect() {
        if (binding == null || binding.ivBgBlur == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 31) binding.ivBgBlur.setRenderEffect(null);
    }

    /**
     * 背景双路径分流：流体开→FluidCoverAmbientView（只借底层机制，不改其内部）；
     * 流体关→BlurredCoverAmbient（§1：封面缩 64×64、模糊 24dp、四段罩、取色过渡 450ms）。
     * 切到无封面歌必须清掉旧模糊与旧色不留残留（D-12 行为承接）：本方法无封面分支先清图、
     * 清 RenderEffect、清罩、氛围色回兜底，再走流体分支时流体色也回兜底三色。
     */
    private void updateBackground(MusicTrack track) {
        String cover = effectiveCover(track);
        if (cover == null || cover.isEmpty()) {
            bgSoftBitmap = null;
            bgSoftUrl = null;
            bgAppliedUrl = null;
            if (binding.ivBgBlur != null) binding.ivBgBlur.setImageDrawable(null);
            clearClassicRenderEffect();
            if (binding.viewVeil != null) binding.viewVeil.setBackground(null);
            dominantColor = CoverColor.FALLBACK_COLOR;
            animateAmbient(CoverColor.ambientBackground(null));
            applyFluidBg();
            refreshToneDrivenSurfaces();
            return;
        }
        ImgUtil.load(this, cover, 64, 64, new CustomTarget<Drawable>() {
            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                if (destroyed) return;
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cover.equals(effectiveCover(cur))) return;
                if (resource instanceof BitmapDrawable && ((BitmapDrawable) resource).getBitmap() != null) {
                    bgSoftBitmap = ((BitmapDrawable) resource).getBitmap();
                    bgSoftUrl = cover;
                }
                refreshBlurredAmbient();
                if (resource instanceof BitmapDrawable) {
                    Bitmap bmp = ((BitmapDrawable) resource).getBitmap();
                    if (bmp != null) {
                        Task.execute(() -> {
                            int ambient = CoverColor.ambientBackground(bmp);
                            int dominant = CoverColor.dominantColor(bmp);
                            runOnUiThread(() -> {
                                if (destroyed) return;
                                MusicTrack c2 = MusicController.get().current();
                                if (c2 == null || !cover.equals(effectiveCover(c2))) return;
                                dominantColor = dominant;
                                animateAmbient(ambient);
                                applyFluidBg();
                                refreshToneDrivenSurfaces();
                            });
                        });
                    }
                }
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                if (destroyed) return;
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cover.equals(effectiveCover(cur))) return;
                // 软底失败时从主封面现成图取色兜底（旧页同教训：不让背景永停兜底黑）
                if (coverView != null && coverView.getDrawable() instanceof BitmapDrawable) {
                    Bitmap bmp = ((BitmapDrawable) coverView.getDrawable()).getBitmap();
                    if (bmp != null) {
                        bgSoftBitmap = bmp;
                        bgSoftUrl = cover;
                        refreshBlurredAmbient();
                        Task.execute(() -> {
                            int ambient = CoverColor.ambientBackground(bmp);
                            int dominant = CoverColor.dominantColor(bmp);
                            runOnUiThread(() -> {
                                if (destroyed) return;
                                dominantColor = dominant;
                                animateAmbient(ambient);
                                applyFluidBg();
                                refreshToneDrivenSurfaces();
                            });
                        });
                    }
                }
            }
        });
        refreshBlurredAmbient();
    }

    /** 糊底合成：64×64 小图拉满（图层 alpha 0.4＋放大 1.85，照瓜子原式）＋24dp 模糊＋四段取色罩，合成一张位图贴 ivBgBlur；流体开时不贴（流体层在上）。 */
    private void refreshBlurredAmbient() {
        if (binding == null || destroyed) return;
        if (MusicPlayerSettings.isFluidBg()) return;
        MusicTrack cur = MusicController.get().current();
        String cover = cur == null ? null : effectiveCover(cur);
        if (cover == null || bgSoftBitmap == null || !cover.equals(bgSoftUrl)) return;
        if (cover.equals(bgAppliedUrl) && binding.ivBgBlur.getDrawable() != null) return;
        View bgView = binding.ivBgBlur;
        bgView.post(() -> {
            if (binding == null || destroyed) return;
            int vw = bgView.getWidth();
            int vh = bgView.getHeight();
            if (vw <= 0 || vh <= 0) return;
            Bitmap src = bgSoftBitmap;
            String url = bgSoftUrl;
            int ambient = ambientColor;
            Task.execute(() -> {
                Bitmap out = buildBlurredAmbient(src, vw, vh, ambient);
                if (out == null) return;
                runOnUiThread(() -> {
                    if (binding == null || destroyed) return;
                    MusicTrack c = MusicController.get().current();
                    if (c == null || !url.equals(effectiveCover(c))) return;
                    if (MusicPlayerSettings.isFluidBg()) return;
                    binding.ivBgBlur.setImageDrawable(new BitmapDrawable(getResources(), out));
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        float r = 24f * getResources().getDisplayMetrics().density;
                        binding.ivBgBlur.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
                    }
                    bgAppliedUrl = url;
                    // 糊底就绪：全屏歌词页若开着，背景同步换糊版（此前是实图占位）。
                    if (lyricPageVisible) applyLyricPageBackground();
                });
            });
        });
    }

    /**
     * 一层满幅糊底合成（后台线程）：照瓜子 CoverAmbientKt.BurredCoverAmbient 原式——
     * 封面缩 64×64 拉满，图层中心放大 1.85（糊边溢出画外）、糊 24dp（API<31 位图层
     * FastBlur 25 回落；API31+ 由视图层 RenderEffect 落地，位图层跳过）、图层 alpha
     * 0.4 压在深色底上（瓜子为窗口底，本树等价物＝骨架根背景 #0A0E16），上盖四段
     * 氛围罩——停点 0/0.4/0.78/1、alpha 0.76/0.82/0.80/0.86、底色两端明度 +0.05/−0.04。
     * 图层 alpha 是关键：旧式图层不透明（1.0）时罩后仍透出约 18% 糊图鬼影，整页下半
     * 认得出一张糊照片＝第二张皮；0.4 时鬼影约 7%，背景回归取色色场、与封面一体。
     */
    private static Bitmap buildBlurredAmbient(Bitmap src, int viewW, int viewH, int ambient) {
        if (src == null || src.isRecycled() || viewW <= 0 || viewH <= 0) return null;
        Bitmap work = src;
        boolean copied = false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && src.getConfig() == Bitmap.Config.HARDWARE) {
            work = src.copy(Bitmap.Config.ARGB_8888, false);
            if (work == null) return null;
            copied = true;
        }
        try {
            Bitmap out = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            // 深色底（骨架根背景字面值）：瓜子图层 alpha 0.4 所压的窗口底在本树的等价物
            canvas.drawColor(0xFF0A0E16);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            paint.setAlpha(Math.round(0.4f * 255));
            int smallH = Math.max(1, Math.round(64f * work.getHeight() / (float) work.getWidth()));
            Bitmap small = Bitmap.createScaledBitmap(work, 64, smallH, true);
            if (android.os.Build.VERSION.SDK_INT < 31) {
                Bitmap blurred = FastBlur.blur(small, 25, true);
                if (blurred != null) small = blurred;
            }
            // 图层中心放大 1.85（瓜子 graphicsLayer scale 原值）：糊的边缘溢出画外
            float zx = (1.85f - 1f) / 2f * viewW;
            float zy = (1.85f - 1f) / 2f * viewH;
            canvas.drawBitmap(small, null, new RectF(-zx, -zy, viewW + zx, viewH + zy), paint);
            small.recycle();
            drawAmbientVeil(canvas, viewW, viewH, ambient);
            return out;
        } finally {
            if (copied) work.recycle();
        }
    }

    /** 四段氛围罩（§1）：停点 0/0.4/0.78/1；alpha 0.76/0.82/0.80/0.86；色＝取色经 toneAdjust，两端明度 +0.05/−0.04。 */
    private static void drawAmbientVeil(Canvas canvas, int viewW, int viewH, int ambient) {
        int base = CoverColor.toneAdjust(ambient);
        float[] hsv = new float[3];
        Color.colorToHSV(base, hsv);
        float topV = Math.max(0.1f, Math.min(0.68f, hsv[2] + 0.05f));
        int topColor = Color.HSVToColor(new float[]{hsv[0], hsv[1], topV});
        float bottomV = Math.max(0.1f, Math.min(0.68f, hsv[2] - 0.04f));
        int bottomColor = Color.HSVToColor(new float[]{hsv[0], hsv[1], bottomV});
        int[] alphas = {Math.round(0.76f * 255), Math.round(0.82f * 255), Math.round(0.80f * 255), Math.round(0.86f * 255)};
        int[] colors = {topColor, base, base, bottomColor};
        int[] argb = new int[4];
        for (int i = 0; i < 4; i++) {
            argb[i] = Color.argb(alphas[i], Color.red(colors[i]), Color.green(colors[i]), Color.blue(colors[i]));
        }
        Paint tint = new Paint();
        tint.setShader(new LinearGradient(0, 0, 0, viewH, argb, new float[]{0f, 0.4f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(new RectF(0, 0, viewW, viewH), tint);
    }

    /** 氛围取色过渡 450ms（§1）。 */
    private void animateAmbient(int to) {
        if (ambientAnimator != null) ambientAnimator.cancel();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, to);
        ambientAnimator.setDuration(450);
        ambientAnimator.setInterpolator(INTERP_FOSI);
        ambientAnimator.addUpdateListener(a -> {
            ambientColor = (int) a.getAnimatedValue();
            if (binding.fluidAmbient != null && binding.fluidAmbient.getVisibility() == View.VISIBLE) {
                binding.fluidAmbient.setColors(ambientColor, dominantColor, CoverColor.toneAdjust(ambientColor));
            }
            if (lyricPageView != null && lyricPageVisible) {
                lyricPageView.setToneColor(ambientColor);
            }
            if (appleLyricView != null) {
                appleLyricView.setFadeColor(ambientColor);
            }
        });
        ambientAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                // 合成时用的氛围色已旧：清合成标记按最终色重合成一次（不回调 animateAmbient，不成环）
                if (animation == ambientAnimator && !MusicPlayerSettings.isFluidBg()) {
                    bgAppliedUrl = null;
                    refreshBlurredAmbient();
                }
            }
        });
        ambientAnimator.start();
    }

    /**
     * 流体路径：开时流体层可见并喂取色三色、糊底合成停用；关时流体层隐藏、回糊底路径。
     * 速度（批 3 D 路收口）：FluidCoverAmbientView 已开放 setSpeed，本页是唯一消费者——
     * 从 MusicPlayerSettings.fluidSpeedFactor()（键 music_fluid_speed：slow 0.6/normal 1.0/fast 2.5）下发；
     * 视图默认 1.0 与补 API 前逐字一致，其它引用方零改动。
     * CD/唱片架主题不用骨架背景层（自带底），本方法对两主题直接跳过（骨架层由 applySkeletonForTheme 隐藏）。
     */
    private void applyFluidBg() {
        try {
            if (binding == null || binding.fluidAmbient == null) return;
            if (cdTheme || shelfTheme) return;
            boolean on = MusicPlayerSettings.isFluidBg();
            binding.fluidAmbient.setVisibility(on ? View.VISIBLE : View.GONE);
            if (binding.viewVeil != null) binding.viewVeil.setVisibility(on ? View.GONE : View.VISIBLE);
            if (on) {
                binding.fluidAmbient.setSpeed(MusicPlayerSettings.fluidSpeedFactor());
                binding.fluidAmbient.setColors(ambientColor, dominantColor, CoverColor.toneAdjust(ambientColor));
            } else {
                bgAppliedUrl = null;
                refreshBlurredAmbient();
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= 封面手势 =================

    /**
     * 封面手势三态（§1 detectTapGestures 等价）：双击＝收藏切换＋爱心爆发＋封面缩放，
     * 长按＝开工具面板（与菜单键同一入口 showToolsSheet），单击在经典下无对照动作、不绑
     * （parity-diff 两文只抠到双击／长按的经典回调，点按回调未抠到）。
     * 切歌竖滑不在封面本体（页级 PlayerPageSwipeLayout，§1 同口径）。
     * 爱心爆发时机：双击即放（GZ 口径，见 onDoubleTap 内注）；缩放／震动同为双击本身的反馈。
     */
    private void attachCoverGestures(View wrap) {
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                onCoverSingleTap();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                MusicTrack track = MusicController.get().current();
                if (track == null) return true;
                float[] p = toOverlayPoint(wrap, e.getX(), e.getY());
                // 爆发时机 GZ 口径（CoverArt$6$1.java:51-56）：双击即放、不等收藏写库回调；
                // 颗数按切换后收藏态（收藏 8／取消 5，参数在 HeartsBurstView 内）。
                // 原 X-10「等回调确认状态真变才放」与 GZ 不符，本批改回 GZ 口径。
                binding.heartsOverlay.burstCover(p[0], p[1], !favorited);
                toggleFavorite(null);
                cancelCoverScaleSpring();
                // favScale 上行段 GZ 实证：tween(200ms, bs4.b 字段=(0,0,0,1)) 到 1.045，随后 spring(0.9, 300) 回落
                wrap.animate().scaleX(1.045f).scaleY(1.045f).setDuration(200)
                        .setInterpolator(INTERP_BEZ_0001)
                        // 待裁10：页已销毁时不许再起 spring——帧循环会对着已 detach 的
                        // wrap 继续写缩放（虽自收敛、无崩点，但属销毁后动画，白跑且持引用）
                        .withEndAction(() -> {
                            if (!destroyed) startCoverScaleSpring(wrap);
                        })
                        .start();
                startRipple(wrap, e.getX(), e.getY());
                try {
                    // 双击震动 GZ 实证（2026-10-25 深挖）：Compose 类型 9（TextHandleMove）＋
                    // View CONTEXT_CLICK(6) 双路并行；旧值 LONG_PRESS(0) 是 GZ 的长按值，错位已改
                    wrap.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE);
                    wrap.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                } catch (Throwable ignored) {
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                try {
                    // 长按震动 GZ 实证：haptic 类型 0（LONG_PRESS），与开面板同拍（CoverArt$6$1.java:60-64）
                    wrap.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                } catch (Throwable ignored) {
                }
                showToolsSheet();
            }
        });
        wrap.setOnTouchListener((v, e) -> detector.onTouchEvent(e));
    }

    /**
     * 封面单击：经典无对照动作、不绑（批 1 口径）；封面卡片＝切换展开/收起（§3 点封面展开，
     * plain if 切换）。横滑切歌时叠卡层拦截会给封面发 CANCEL，不会误触发单击。
     */
    private void onCoverSingleTap() {
        if (coverTheme) setCoverExpanded(!coverExpanded);
    }

    /**
     * 双击涟漪（2026-10-25 深挖换实：GZ favRipple 是独立 Canvas 层——以双击点为心、
     * 三圆公式、800ms×bs4.a 驱动，见 FavRippleView；旧自造居中椭圆整套参数非 GZ 值，已废）。
     */
    private void startRipple(View wrap, float tapX, float tapY) {
        if (!(wrap instanceof android.view.ViewGroup)) return;
        com.fongmi.android.tv.ui.custom.FavRippleView ripple = new com.fongmi.android.tv.ui.custom.FavRippleView(this);
        ripple.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        ((android.view.ViewGroup) wrap).addView(ripple);
        ripple.startRipple(tapX, tapY);
    }

    private float[] toOverlayPoint(View anchor, float x, float y) {
        int[] a = new int[2];
        int[] o = new int[2];
        anchor.getLocationInWindow(a);
        binding.heartsOverlay.getLocationInWindow(o);
        return new float[]{a[0] - o[0] + x, a[1] - o[1] + y};
    }

    // ---- 双击封面缩放回落 spring（§1：阻尼 0.9／刚度 300，半隐式欧拉积分；树内无 androidx.dynamicanimation，见页级切歌回弹同注） ----
    private android.view.Choreographer.FrameCallback coverScaleSpringCallback;
    private float coverScaleSpringV;
    private long coverScaleSpringLastMs;

    private void cancelCoverScaleSpring() {
        if (coverScaleSpringCallback != null) {
            android.view.Choreographer.getInstance().removeFrameCallback(coverScaleSpringCallback);
            coverScaleSpringCallback = null;
        }
    }

    private void startCoverScaleSpring(View target) {
        cancelCoverScaleSpring();
        coverScaleSpringV = 0f;
        coverScaleSpringLastMs = android.os.SystemClock.uptimeMillis();
        coverScaleSpringCallback = new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                // 待裁10：spring 跑到一半页面销毁——立即收链，不再续帧
                if (destroyed) {
                    coverScaleSpringCallback = null;
                    return;
                }
                long nowMs = android.os.SystemClock.uptimeMillis();
                float dt = coverScaleSpringLastMs == 0 ? 0.016f
                        : Math.min(0.05f, (nowMs - coverScaleSpringLastMs) / 1000f);
                coverScaleSpringLastMs = nowMs;
                double k = 300d;
                double damp = 2d * 0.9d * Math.sqrt(k);
                float s = target.getScaleX();
                coverScaleSpringV += (float) ((k * (1f - s) - damp * coverScaleSpringV) * dt);
                s += coverScaleSpringV * dt;
                target.setScaleX(s);
                target.setScaleY(s);
                if (Math.abs(s - 1f) < 0.001f && Math.abs(coverScaleSpringV) < 0.01f) {
                    target.setScaleX(1f);
                    target.setScaleY(1f);
                    coverScaleSpringCallback = null;
                } else {
                    android.view.Choreographer.getInstance().postFrameCallback(this);
                }
            }
        };
        android.view.Choreographer.getInstance().postFrameCallback(coverScaleSpringCallback);
    }

    // ================= 收藏 =================

    private void refreshFavorite(MusicTrack track) {
        if (track == null || track.id == null) {
            favorited = false;
            paintFavoriteIcons();
            return;
        }
        MusicLibrary.isFavorite(track, fav -> {
            favorited = fav;
            paintFavoriteIcons();
        });
    }

    /** CD 五图标行歌词版本键门控：无任何版本时压暗 ×0.35（瓜子五图标行不可用 alpha 口径，
     * LuminousCdPlayerKt 五图标行）；点击仍开版本弹窗（空态与搜索入口在弹窗内，星辰口径不变）。 */
    private void paintCdLyricVerAlpha() {
        if (cdBtnLyricVerView != null) {
            cdBtnLyricVerView.setAlpha(currentLyricVersions.isEmpty() ? 0.35f : 1f);
        }
    }

    private void paintFavoriteIcons() {
        // CD 五图标收藏键：已收藏实心／未收藏描边，tint 统一当前表面 ink（applyCdPalette 同步刷）
        if (cdBtnFavView != null) {
            cdBtnFavView.setImageResource(favorited
                    ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
            cdBtnFavView.setImageTintList(ColorStateList.valueOf(cdDarkSurface ? 0xFFF2F2F7 : 0xFF282D36));
            MusicTrack curFav = null;
            try {
                curFav = MusicController.get().current();
            } catch (Throwable ignored) {
            }
            cdBtnFavView.setAlpha(curFav == null ? 0.35f : 1f);
        }
        // Apple 头右收藏玻璃钮：图标随收藏态换实心/描边（底与 alpha 0.85 由布局给）
        View appleFav = find(R.id.tpAppleFav);
        if (appleFav instanceof ImageView) {
            ((ImageView) appleFav).setImageResource(favorited
                    ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
        }
        // 封面卡片玻璃条收藏键（§3 透明度：已收藏 1.0／未收藏 0.85／禁用 0.45）
        if (glassFavView != null) {
            glassFavView.setImageResource(favorited
                    ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
            MusicTrack cur = null;
            try {
                cur = MusicController.get().current();
            } catch (Throwable ignored) {
            }
            glassFavView.setAlpha(cur == null ? 0.45f : favorited ? 1.0f : 0.85f);
        }
        if (favHeartView == null) return;
        int icon = favorited ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart;
        favHeartView.setImageResource(icon);
        // 经典小爱心：已收藏实心白 0.45、未收藏描边白 0.30；切换走 300ms 颜色动画
        // （GZ songMetaFavTint，PlayerScreenKt.java:24950-24960），首绑直设不动画。
        int tintTarget = favorited ? 0x73FFFFFF : 0x4DFFFFFF;
        if (!favTintInit) {
            favTintInit = true;
            favTintColor = tintTarget;
            favHeartView.setImageTintList(ColorStateList.valueOf(tintTarget));
        } else if (tintTarget != favTintColor) {
            if (favTintAnimator != null) favTintAnimator.cancel();
            favTintAnimator = ValueAnimator.ofObject(new android.animation.ArgbEvaluator(), favTintColor, tintTarget);
            favTintAnimator.setDuration(300);
            favTintAnimator.addUpdateListener(a -> {
                favTintColor = (int) a.getAnimatedValue();
                if (favHeartView != null) favHeartView.setImageTintList(ColorStateList.valueOf(favTintColor));
            });
            favTintAnimator.start();
        }
        favHeartView.setVisibility(View.VISIBLE);
    }

    private void toggleFavorite() {
        toggleFavorite(null);
    }

    /**
     * 收藏切换：未落位（isCurrentLoaded 为假）不接受（旧页必-2 守门）。
     * onChanged 只在状态真的变了才回调（X-10：双击爱心爆发挂在这里，无变化不放）。
     */
    private void toggleFavorite(Runnable onChanged) {
        MusicTrack track = MusicController.get().current();
        if (track == null || favoriteBusy || !MusicController.get().isCurrentLoaded()) return;
        favoriteBusy = true;
        boolean before = favorited;
        MusicLibrary.setFavorite(track, !favorited, () -> {
            favoriteBusy = false;
            favorited = !before;
            paintFavoriteIcons();
            Notify.show(favorited ? R.string.music_favorited : R.string.music_unfavorited);
            if (favorited != before && onChanged != null) onChanged.run();
        });
    }

    // ================= 音质筹 =================

    /**
     * 音质格只显示服务实测的格子短码（SQ／HQ／Std／Hi-Res／24bit，GZ immersiveShortLabel 口径）；
     * 取不到实测时音质筹整筹隐藏——所选档永不进显示。
     * 旧口径“无损只显编码名（FLAC）”被最新令“就照他这么搞”覆盖，冲突行见对照表供 veto。
     */
    private String realQualityText() {
        try {
            com.fongmi.android.tv.music.MusicAudioInfo info = MusicController.get().audioInfo();
            if (info != null) {
                String real = info.shortCode();
                if (real != null && !real.isEmpty()) return real;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void refreshQualityValue() {
        String real = realQualityText();
        if (qualityValueView != null) {
            qualityValueView.setText(real == null ? "" : real);
            View chip = qualityValueView.getParent() instanceof View ? (View) qualityValueView.getParent() : null;
            if (chip != null && chip.getId() == R.id.tpChipQuality) {
                animateChipVisibility(chip, real != null);
            }
        }
        // 实测音频参数也是信息页「技术规格」的数据源，变化时同步重建信息内容
        refreshInfoContent();
    }

    /** 筹显隐透明动画 150ms（§1）。 */
    private void animateChipVisibility(View chip, boolean show) {
        if (show) {
            chip.setVisibility(View.VISIBLE);
            chip.setAlpha(0f);
            chip.animate().alpha(1f).setDuration(150).start();
        } else {
            chip.animate().alpha(0f).setDuration(150)
                    .withEndAction(() -> chip.setVisibility(View.GONE)).start();
        }
    }

    /** 音质筹点击＝音质面板（旧页同口径：点行切档＋重载＋刷新音质筹）。 */
    private void showQualityDialog() {
        if (isFinishing() || isDestroyed()) return;
        com.fongmi.android.tv.ui.dialog.MusicQualityPanelDialog.show(this, MusicController.get().current(),
                MusicController.get().getQuality(), quality -> {
                    if (MusicController.get().getQuality() != quality) {
                        MusicController.get().setQuality(quality);
                        MusicController.get().reloadQuality();
                        refreshQualityValue();
                        Notify.show("已切换" + MusicStore.qualityName(quality));
                    }
                });
    }

    /** 歌词筹文案（旧页 updateLyricChipLabel 同口径，GZ lyricChipLabel 五档）：AMLL 档＋逐字／逐行／纯文本／无词。 */
    private void updateLyricChipLabel(List<LyricsLine> mains) {
        if (lyricChipTextView == null) return;
        if ("AMLL".equals(currentLyricFormat)) {
            lyricChipTextView.setText(R.string.music_lyric_chip_amll);
            return;
        }
        int label;
        if (mains == null || mains.isEmpty()) label = R.string.music_lyric_chip_none;
        else {
            boolean word = false;
            boolean timed = false;
            for (LyricsLine line : mains) {
                if (line.hasWords()) word = true;
                if (line.getTimeMs() > 0) timed = true;
            }
            label = word ? R.string.music_lyric_chip_word : timed ? R.string.music_lyric_chip_line : R.string.music_lyric_chip_plain;
        }
        lyricChipTextView.setText(label);
    }

    // ================= 歌词（批 2：逐字引擎＋全屏歌词页） =================

    private void applyLyricStyle() {
        // 批 7 补线：字重／对齐两键在此统一读出下发（主歌词键在喂词层 effectiveMains 换算，见该处注）。
        int lyricWeight = MusicPlayerSettings.getLyricWeight();
        int lyricAlign = MusicPlayerSettings.getLyricAlign();
        if (cdLyricView != null) {
            // CD 歌词窗字号固定 21sp（§4 字面值，不走封面字号设置）；副行开关与全页同口径
            cdLyricView.setShowSub(MusicPlayerSettings.isShowTranslation() || MusicPlayerSettings.isShowRomanization());
            cdLyricView.setEmptyText(getString(R.string.music_no_lyric));
            // 批 7：CD 窗只接字重——对齐不接（§4 本窗居中是瓜子定式：摘要行居中、窗内居中单行，不是设置键的左对齐面）。
            cdLyricView.setLyricWeight(lyricWeight);
        }
        if (shelfLyricView != null) {
            // 唱片架歌词面字号走封面字号键（出厂 22＝§5 字面值）、模糊档共用存量键（默认 medium 档位映射见 ShelfLyricView）
            shelfLyricView.setFontSizeSp(MusicPlayerSettings.getCoverLyricFontSize());
            shelfLyricView.setBlurLevel(MusicPlayerSettings.getCoverLyricBlurLevel());
            shelfLyricView.setShowSub(MusicPlayerSettings.isShowTranslation() || MusicPlayerSettings.isShowRomanization());
            shelfLyricView.setEmptyText(getString(R.string.music_no_lyric));
            // 批 7：唱片架独立画笔，接字重＋对齐（其行 X 现为写死左对齐，现按键算）。
            shelfLyricView.setLyricWeight(lyricWeight);
            shelfLyricView.setTextAlign(lyricAlign);
        }
        if (lyricView != null) {
            lyricView.setFontSizeSp(MusicPlayerSettings.getCoverLyricFontSize());
            lyricView.setBlurLevel(MusicPlayerSettings.getCoverLyricBlurLevel());
            lyricView.setAdvancedFx(MusicPlayerSettings.isAdvancedFx());
            lyricView.setShowSub(MusicPlayerSettings.isShowTranslation() || MusicPlayerSettings.isShowRomanization());
            lyricView.setLyricWeight(lyricWeight);
            lyricView.setTextAlign(lyricAlign);
            sizeExpandedLyricSlot();
        }
        if (appleLyricView != null) {
            // Apple 歌词页（pager 第 2 页）与全屏歌词页同口径：字号走全屏字号（默认 24）、模糊档共用存量键
            appleLyricView.setFontSizeSp(MusicPlayerSettings.getFullLyricFontSize());
            appleLyricView.setBlurLevel(MusicPlayerSettings.getCoverLyricBlurLevel());
            appleLyricView.setAdvancedFx(MusicPlayerSettings.isAdvancedFx());
            appleLyricView.setLyricWeight(lyricWeight);
            appleLyricView.setTextAlign(lyricAlign);
            appleLyricView.setFadeColor(ambientColor);
        }
        if (lyricPageView != null) {
            // 全屏模糊档与封面共用同一个存量键（MusicStore 只有这一把模糊键，§0 全屏／封面默认同为 medium）。
            lyricPageView.applyLyricStyle(MusicPlayerSettings.getFullLyricFontSize(),
                    MusicPlayerSettings.getCoverLyricBlurLevel(), MusicPlayerSettings.isAdvancedFx(),
                    lyricWeight, lyricAlign);
        }
    }

    /**
     * 主歌词生效行（批 7 补线：键 music_lyric_primary 0 原文为主／1 翻译为主／2 音译为主，
     * 此前只进了工具面板预览 applyPreview、真渲染从未读）。
     * 提升法：以原文行的 timeMs 为时间轴，把译文／音译逐行提为主行文本（该行译文为空时回落该行原文，
     * 不出空行）；副行改挂原文（见 subLines）。提升后的主行是纯文本行（无逐字词），
     * 逐字上浮／扫边自然走 LyricLineDrawer 的整行提亮回落——这是数据形态的固有限制，不是接线缺失。
     * 在喂词层（feedLyricView／摘要／玻璃条）统一换算，一处覆盖全部歌词面（内嵌／全屏／Apple／CD／唱片架／歌词页）。
     */
    private List<LyricsLine> effectiveMains() {
        int primary = MusicPlayerSettings.getLyricPrimary();
        if (primary == 1 && hasNonEmpty(lyricTrans)) return promotedLines(lyricTrans);
        if (primary == 2 && hasNonEmpty(lyricRomas)) return promotedLines(lyricRomas);
        return lyricMains;
    }

    private List<LyricsLine> promotedLines(List<String> src) {
        List<LyricsLine> out = new ArrayList<>();
        for (int i = 0; i < lyricMains.size(); i++) {
            LyricsLine orig = lyricMains.get(i);
            String text = i < src.size() ? src.get(i) : null;
            if (text == null || text.isEmpty()) text = orig.getText();
            out.add(new LyricsLine(orig.getTimeMs(), text == null ? "" : text));
        }
        return out;
    }

    private List<String> originalTextLines() {
        List<String> out = new ArrayList<>();
        for (LyricsLine line : lyricMains) out.add(line.getText() == null ? "" : line.getText());
        return out;
    }

    /** 副行选词：翻译开取翻译、罗马音开取罗马音（都开时翻译优先，与旧页喂词口径一致的简化：副行只有一条槽）。
     * 批 7：主歌词已提升翻译／音译时，副行改挂原文（与 effectiveMains 成对换位，不重复显示同一文本）。 */
    private List<String> subLines() {
        int primary = MusicPlayerSettings.getLyricPrimary();
        if (primary == 1 && hasNonEmpty(lyricTrans)) return originalTextLines();
        if (primary == 2 && hasNonEmpty(lyricRomas)) return originalTextLines();
        if (MusicPlayerSettings.isShowTranslation()) return new ArrayList<>(lyricTrans);
        if (MusicPlayerSettings.isShowRomanization()) return new ArrayList<>(lyricRomas);
        return new ArrayList<>();
    }

    private void feedLyricView() {
        if (cdLyricView != null) {
            cdLyricView.setLines(new ArrayList<>(effectiveMains()), subLines());
            cdLyricView.setPosition(MusicController.get().position() + MusicPlayerSettings.getLyricOffsetMs());
            cdSummaryShown = "\u0001";
            updateCdSummary();
        }
        if (shelfLyricView != null) {
            shelfLyricView.setLines(new ArrayList<>(effectiveMains()), subLines());
            shelfLyricView.setPosition(MusicController.get().position() + MusicPlayerSettings.getLyricOffsetMs());
        }
        if (lyricView != null) {
            lyricView.setLines(new ArrayList<>(effectiveMains()), subLines());
            lyricView.setPosition(MusicController.get().position() + MusicPlayerSettings.getLyricOffsetMs());
        }
        if (appleLyricView != null) {
            boolean showSub = MusicPlayerSettings.isShowTranslation() || MusicPlayerSettings.isShowRomanization();
            appleLyricView.setShowSub(showSub);
            appleLyricView.setLines(new ArrayList<>(effectiveMains()), subLines());
            appleLyricView.setPosition(MusicController.get().position() + MusicPlayerSettings.getLyricOffsetMs());
        }
        glassLyricShown = "\u0001";
        updateGlassLyricLine();
        if (lyricPageView != null) {
            boolean showSub = MusicPlayerSettings.isShowTranslation() || MusicPlayerSettings.isShowRomanization();
            lyricPageView.setLyricData(new ArrayList<>(effectiveMains()), subLines(), showSub);
            lyricPageView.setLyricPosition(MusicController.get().position() + MusicPlayerSettings.getLyricOffsetMs());
        }
    }

    private void applyLyricData(List<LyricsLine> mains, List<String> trans, List<String> romas) {
        lyricMains.clear();
        lyricMains.addAll(mains);
        lyricTrans.clear();
        lyricTrans.addAll(trans);
        lyricRomas.clear();
        lyricRomas.addAll(romas);
        updateLyricChipLabel(mains);
        feedLyricView();
        refreshInfoContent();
    }

    /**
     * 取词：走现成查找链（旧页同路）——已下载旁路词 → MusicLyricCache → MusicRepository.lyricRich
     * （内部走 player/lyrics/LyricsRepository）→ 纯文本回退 lyric()；解析 LyricsParser、翻译对齐 RichLyrics。
     * 切歌先清旧词防残留（旧页同时序口径的本批版）。
     */
    private void loadLyric(MusicTrack track, boolean force) {
        lyricLoading = true;
        lyricMains.clear();
        lyricTrans.clear();
        lyricRomas.clear();
        currentLyricVersions.clear();
        paintCdLyricVerAlpha();
        currentLyricVersion = null;
        currentLyricFormat = null;
        currentLyricKey = null;
        currentLyricSource = null;
        if (!force) {
            // 切歌同步（他定「不许还挂着上一首的脸」）：全部歌词面同步清旧词，不许只清内嵌面、
            // 其余面（CD／唱片架／Apple／全屏页）残留上一首的词等异步取词回来才覆盖。
            if (lyricView != null) lyricView.clearLines();
            if (cdLyricView != null) cdLyricView.clearLines();
            if (shelfLyricView != null) shelfLyricView.clearLines();
            if (appleLyricView != null) appleLyricView.clearLines();
            if (lyricPageView != null) {
                lyricPageView.setLyricData(new ArrayList<>(), new ArrayList<>(), false);
            }
        }
        updateLyricChipLabel(null);
        Task.execute(() -> {
            List<LyricsLine> mains = new ArrayList<>();
            List<String> trans = new ArrayList<>();
            List<String> romas = new ArrayList<>();
            String richFormat = null;
            String richKey = null;
            String richSource = null;
            boolean richProvided = false;
            LxKeyIssue keyIssue = null;
            // 在用词本体（补单②）：mains 实际取自哪份词就记哪份，聚合缺失／未回时它就是版本列表的底
            MusicLyric inUseLyric = null;
            String inUseSource = null;
            String sidecarLrc = MusicDownloadStore.readSidecarLyric(track);
            if (sidecarLrc != null && !sidecarLrc.trim().isEmpty()) {
                mains = LyricsParser.parseWordLyric(sidecarLrc);
                richProvided = !mains.isEmpty();
                if (richProvided) richKey = sidecarLrc.trim();
                if (!mains.isEmpty()) {
                    inUseLyric = new MusicLyric(sidecarLrc);
                    inUseSource = "本地";
                }
            }
            MusicLyric cached = force ? null : MusicLyricCache.get(track);
            try {
                MusicLyric rich = richProvided ? null : (cached != null ? cached : MusicRepository.lyricRich(track, force));
                if (rich != null) {
                    if (rich.rawLrc != null && !rich.rawLrc.isEmpty()) {
                        mains = LyricsParser.parseWordLyric(rich.rawLrc);
                        if (!mains.isEmpty()) {
                            inUseLyric = rich;
                            inUseSource = rich.sourceName;
                        }
                    }
                    List<LyricsLine> transLines = rich.translation == null || rich.translation.isEmpty()
                            ? null : LyricsParser.parseWordLyric(rich.translation);
                    List<LyricsLine> romaLines = rich.romanization == null || rich.romanization.isEmpty()
                            ? null : LyricsParser.parseWordLyric(rich.romanization);
                    List<RichLyrics.Line> aligned = RichLyrics.align(mains, transLines, romaLines);
                    trans.clear();
                    romas.clear();
                    for (RichLyrics.Line line : aligned) {
                        trans.add(line.translation);
                        romas.add(line.romanization);
                    }
                }
                richProvided = !mains.isEmpty();
                if (richProvided && rich != null) {
                    richFormat = rich.format;
                    richSource = rich.sourceName;
                    richKey = rich.rawLrc == null ? null : rich.rawLrc.trim();
                    if (cached == null) MusicLyricCache.put(track, rich);
                }
                if (mains.isEmpty()) {
                    String lrc = MusicRepository.lyric(track, force);
                    if (lrc != null && !lrc.isEmpty()) {
                        mains = LyricsParser.parseWordLyric(lrc);
                        if (!mains.isEmpty()) {
                            MusicLyricCache.putPlain(track, lrc);
                            inUseLyric = new MusicLyric(lrc);
                            inUseSource = MusicRepository.sourceDisplayName(track.platform);
                        }
                    }
                }
                if (force && mains.isEmpty() && keyIssue == null) MusicLyricCache.remove(track);
            } catch (LxKeyIssue e) {
                keyIssue = e;
            } catch (Throwable ignored) {
            }
            List<LyricsLine> resultMains = mains;
            List<String> resultTrans = trans;
            List<String> resultRomas = romas;
            String resultFormat = richProvided ? richFormat : null;
            String resultKey = richProvided ? richKey : null;
            String resultSource = richProvided ? richSource : null;
            MusicLyric resultLyric = inUseLyric;
            String resultLyricSource = inUseSource;
            LxKeyIssue resultKeyIssue = keyIssue;
            runOnUiThread(() -> {
                MusicTrack cur = MusicController.get().current();
                // 守卫认 trackKey（id＋平台）：MusicTrack.equals 只比 id，异平台同 id 的歌会让上一首的迟到词贴到新歌上
                if (cur == null || !trackKey(cur).equals(trackKey(track))) return;
                currentLyricFormat = resultFormat;
                currentLyricKey = resultKey;
                currentLyricSource = resultSource;
                applyLyricData(resultMains, resultTrans, resultRomas);
                // 补单②：在用词先行入列（不等聚合回来）——词在播时版本数至少为 1，
                // 设置面板不许再出现「播着词却暂无版本」；聚合回来后由 ensureCurrentLyricListed 合并
                currentLyricVersion = resultMains.isEmpty() ? null
                        : MusicRepository.displayedVersion(resultLyric, resultLyricSource);
                currentLyricVersions.clear();
                if (currentLyricVersion != null) currentLyricVersions.add(currentLyricVersion);
                paintCdLyricVerAlpha();
                lyricEmpty = resultMains.isEmpty();
                lyricLoading = false;
                if (resultMains.isEmpty() && lyricView != null) {
                    lyricView.setEmptyText(getString(R.string.music_lyric_chip_none));
                }
                if (resultMains.isEmpty() && lyricPageView != null) {
                    lyricPageView.setEmptyText(getString(R.string.music_lyric_chip_none));
                }
                if (force) {
                    Notify.show(resultMains.isEmpty() ? R.string.music_lyric_not_found : R.string.music_lyric_found);
                }
                if (resultKeyIssue != null) {
                    showKeyIssue(resultKeyIssue.getPlatform(), resultKeyIssue.getMessage(),
                            () -> loadLyric(track, true));
                }
                // 版本聚合（宿主接口 getLyricVersionCount/openLyricVersionDialog 的数据来源，旧页 B2 同路）
                Task.execute(() -> {
                    List<LyricVersion> versions;
                    try {
                        versions = MusicRepository.lyricRichAll(track, force);
                    } catch (Throwable ignored) {
                        versions = new ArrayList<>();
                    }
                    List<LyricVersion> resultVersions = versions;
                    runOnUiThread(() -> {
                        MusicTrack cur2 = MusicController.get().current();
                        if (cur2 == null || !trackKey(cur2).equals(trackKey(track))) return;
                        currentLyricVersions.clear();
                        currentLyricVersions.addAll(resultVersions);
                        ensureCurrentLyricListed();
                        paintCdLyricVerAlpha();
                        maybeUpgradeLyricByPriority(track);
                    });
                });
            });
        });
    }

    /** 补单②合并：聚合列表里有同文版本时以聚合版为准（带真实定级）并认它作在用版本；
     * 没有（聚合缺失／旁路词／纯文本兜底词不在聚合视野里）时把在用词条补进列表首位——
     * 列表为空只许是真的全链无词。 */
    private void ensureCurrentLyricListed() {
        if (currentLyricVersion == null || currentLyricVersion.getLyric() == null) return;
        String raw = currentLyricVersion.getLyric().rawLrc;
        String key = raw == null ? "" : raw.trim();
        if (key.isEmpty()) return;
        for (LyricVersion v : currentLyricVersions) {
            if (v.getLyric() != null && v.getLyric().rawLrc != null
                    && key.equals(v.getLyric().rawLrc.trim())) {
                currentLyricVersion = v;
                return;
            }
        }
        currentLyricVersions.add(0, currentLyricVersion);
    }

    /**
     * 补单①：默认选用认「在线歌词优先级」设置。聚合每播必跑——聚合里若有比在用词更合
     * 设置的版本（默认逐字优先：在用是逐行／文本而聚合有逐字版），自动换成那份并写歌词
     * 缓存，下次播放直接从它起步（治旧逐行词被缓存长期压住：播放页读缓存在取词链之前，
     * 缓存里的旧词靠自身永远等不到逐字版）。手动选过版本的曲目不许动；跟随来源模式不升级。
     * 只换在用词、不动取词链与聚合总序。
     */
    private void maybeUpgradeLyricByPriority(MusicTrack track) {
        if (currentLyricVersion == null) return;
        String stored = MusicStore.getManualLyricChoice(MusicQueue.trackKey(track));
        if (stored != null && !MusicStore.manualLyricChoiceKey(stored).isEmpty()) return;
        String priority = MusicStore.getLyricPriority();
        LyricVersion best = null;
        if (MusicStore.LYRIC_PRIORITY_WORD_BY_WORD.equals(priority)) {
            if (currentLyricVersion.isWordTimed()) return;
            for (LyricVersion v : currentLyricVersions) if (v.isWordTimed()) { best = v; break; }
        } else if (MusicStore.LYRIC_PRIORITY_LINE_BY_LINE.equals(priority)) {
            if (currentLyricVersion.isLineTimed() && !currentLyricVersion.isWordTimed()) return;
            for (LyricVersion v : currentLyricVersions) {
                if (v.isLineTimed() && !v.isWordTimed()) { best = v; break; }
            }
        } else if (MusicStore.LYRIC_PRIORITY_AMLL_FIRST.equals(priority)) {
            if ("AMLL".equals(currentLyricVersion.getSource())) return;
            for (LyricVersion v : currentLyricVersions) {
                if ("AMLL".equals(v.getSource())) { best = v; break; }
            }
        } else {
            return;
        }
        if (best == null || best == currentLyricVersion || best.getLyric() == null) return;
        String bestRaw = best.getLyric().rawLrc == null ? "" : best.getLyric().rawLrc.trim();
        if (bestRaw.isEmpty() || bestRaw.equals(currentLyricKey)) return;
        applyVersionData(track, best, true);
    }

    private void searchLyric() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        loadLyric(track, true);
    }

    private void showKeyIssue(String platform, String detail, Runnable onRetry) {
        if (isFinishing() || isDestroyed()) return;
        MusicTrack cur = MusicController.get().current();
        if (cur == null || !"lx".equals(cur.platform)) return;
        com.fongmi.android.tv.ui.dialog.LxKeyIssueDialog.show(this, platform, detail, onRetry);
    }

    // ================= MusicPlayerHost（工具面板宿主回调） =================

    @Override
    public void refreshCurrentCover() {
        if (binding == null || destroyed) return;
        bindCovers(MusicController.get().current());
    }

    @Override
    public int getLyricVersionCount() {
        return currentLyricVersions.size();
    }

    @Override
    public void openLyricVersionDialog() {
        MusicLyricVersionDialog.showWithSearch(this, MusicController.get().current(),
                new ArrayList<>(currentLyricVersions), currentLyricKey, this::applyLyricVersion,
                this::searchLyric);
    }

    /** 切版本：重走解析＋对齐＋喂词（与 loadLyric 同一口径），只换词数据不换曲目；点选即写盘（旧页 B2 同路）。 */
    private void applyLyricVersion(LyricVersion version) {
        if (version == null || version.getLyric() == null) return;
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        MusicRepository.saveManualLyricChoice(track, version);
        applyVersionData(track, version, false);
    }

    /** 把某版本的词应用到播放页并认作在用版本；cachePick 时同时写歌词缓存（聚合升级用）。 */
    private void applyVersionData(MusicTrack track, LyricVersion version, boolean cachePick) {
        MusicLyric rich = version.getLyric();
        Task.execute(() -> {
            List<LyricsLine> mains = new ArrayList<>();
            List<String> trans = new ArrayList<>();
            List<String> romas = new ArrayList<>();
            try {
                if (rich.rawLrc != null && !rich.rawLrc.isEmpty()) {
                    mains = LyricsParser.parseWordLyric(rich.rawLrc);
                }
                List<LyricsLine> transLines = rich.translation == null || rich.translation.isEmpty()
                        ? null : LyricsParser.parseWordLyric(rich.translation);
                List<LyricsLine> romaLines = rich.romanization == null || rich.romanization.isEmpty()
                        ? null : LyricsParser.parseWordLyric(rich.romanization);
                List<RichLyrics.Line> aligned = RichLyrics.align(mains, transLines, romaLines);
                for (RichLyrics.Line line : aligned) {
                    trans.add(line.translation);
                    romas.add(line.romanization);
                }
            } catch (Throwable ignored) {
            }
            List<LyricsLine> resultMains = mains;
            List<String> resultTrans = trans;
            List<String> resultRomas = romas;
            runOnUiThread(() -> {
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !trackKey(cur).equals(trackKey(track))) return;
                currentLyricFormat = rich.format;
                currentLyricKey = rich.rawLrc == null ? null : rich.rawLrc.trim();
                currentLyricSource = version.getSource();
                currentLyricVersion = version;
                if (!resultMains.isEmpty()) lyricEmpty = false;
                if (cachePick) MusicLyricCache.put(track, rich);
                applyLyricData(resultMains, resultTrans, resultRomas);
            });
        });
    }

    // ================= 歌曲信息（批 3：Apple pager 第 0 页与封面卡片信息卡共用构建） =================

    private float dpF(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private int dpI(float value) {
        return Math.round(dpF(value));
    }

    /** 信息页深字面开关：流光 CD 浅色表面（浅底取色）时整页信息翻深字版。旧实现字色按深面
     *  写死（白字系＋近透明卡底），压在 CD 浅面上糊成一团（2026-10-25 真机图 113184 实证）；
     *  Apple／封面卡片信息页走深面（cdTheme=false 或深底），不受影响。refreshInfoContent 入口赋值。 */
    private boolean infoInkText;

    /** 白字系颜色 → 同 alpha 的墨色 #282D36（CdDiscView 浅面 ink 同值）；深面时原样返回。
     *  信息页全部文字都经 infoText 建，映射收在此一处，调用点字面值不动。 */
    private int infoInk(int whiteColor) {
        if (!infoInkText) return whiteColor;
        return android.graphics.Color.argb(android.graphics.Color.alpha(whiteColor), 0x28, 0x2D, 0x36);
    }

    /** 信息卡/筹的浅面底（代码建形，不新增 drawable 资源）：几何与 bg_info_glass／bg_info_chip_muted
     *  同（卡圆角 20dp＋1dp 描边、筹圆角 8dp），仅换浅面色值——半透明白磨砂面＋深描边／深色半透明筹底。 */
    private android.graphics.drawable.GradientDrawable infoLightBg(boolean chip) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius(dpF(chip ? 8f : 20f));
        if (chip) {
            d.setColor(0x14000000);
        } else {
            d.setColor(0xA6FFFFFF);
            d.setStroke(dpI(1), 0x1F000000);
        }
        return d;
    }

    private TextView infoText(float sp, int weight, int color, String text) {
        TextView tv = new TextView(this);
        tv.setText(text == null ? "" : text);
        tv.setTextSize(sp);
        tv.setTextColor(infoInk(color));
        tv.setIncludeFontPadding(false);
        tv.setTypeface(android.graphics.Typeface.DEFAULT,
                weight >= 700 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                // 字重数值走 Typeface.create 的 weight 轴（TextView.setFontWeight 不在公开 SDK 面）
                tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, weight, false));
            } catch (Throwable ignored) {
            }
        }
        return tv;
    }

    private LinearLayout infoSection(String title, boolean compact) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        if (infoInkText) section.setBackground(infoLightBg(false));
        else section.setBackgroundResource(R.drawable.bg_info_glass);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dpI(16);
        section.setLayoutParams(slp);
        // 分区小标题：Apple GlassSection 12sp/700/白 0.55/字距 0.5；封面卡片 CoverInfoSection 11sp/700/白 0.55/字距 0.5（§3）
        TextView head = infoText(compact ? 11f : 12f, 700, 0x8CFFFFFF, title);
        head.setLetterSpacing(0.5f / (compact ? 11f : 12f));
        head.setPadding(dpI(16), dpI(12), dpI(16), 0);
        section.addView(head);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dpI(16), dpI(12), dpI(16), dpI(12));
        section.addView(body);
        section.setTag(body);
        return section;
    }

    private void infoRow(LinearLayout body, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dpI(6);
        row.setLayoutParams(rlp);
        // InfoRow 实读：标签 12sp/500/白 0.55＋12dp 间隔＋值 13sp/500/白 0.95
        row.addView(infoText(12f, 500, 0x8CFFFFFF, label));
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(dpI(12), 1));
        row.addView(gap);
        TextView valueView = infoText(13f, 500, 0xF2FFFFFF, value == null || value.isEmpty() ? "—" : value);
        valueView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(valueView);
        body.addView(row);
    }

    private void infoChip(LinearLayout row, String text, boolean tone) {
        TextView chip = infoText(10f, 700, tone ? 0xFFFFFFFF : 0x8CFFFFFF, text);
        if (!tone && infoInkText) chip.setBackground(infoLightBg(true));
        else chip.setBackgroundResource(tone ? R.drawable.bg_info_chip_tone : R.drawable.bg_info_chip_muted);
        chip.setPadding(dpI(8), dpI(4), dpI(8), dpI(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = row.getChildCount() > 0 ? dpI(6) : 0;
        chip.setLayoutParams(lp);
        row.addView(chip);
    }

    private static String durationLabel(long ms) {
        if (ms <= 0) return "—";
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // 采样率不进信息页：他的定案口径——采样率（赫兹）任何情况不许显示（2026-10-20），压过瓜子信息页构成。
    /**
     * 信息内容重建（数据＝当前曲目＋实测音频参数＋已加载歌词元信息，全部取星辰现有数据源）：
     * Apple 页＝HeroCard（封面 80dp 圆角 16＋歌名 20sp/800＋歌手·专辑 12sp/500/0.55）＋三 GlassSection；
     * 封面卡片＝信息卡头（标题 16sp/800/0.95＋歌手·专辑合并行 11sp/500/0.55）＋同三段（小标题 11sp）。
     * 瓜子「取链渠道／换源」行在星辰无对应数据源，不落（见报告未抠到/无对应清单）；
     * 歌词段「匹配来源」行有数据源（已应用歌词的 sourceName／版本来源），已接。
     */
    private void refreshInfoContent() {
        if (infoContentView == null) return;
        if (coverTheme && !coverInfoMode) return;
        if (cdTheme && !cdInfoMode) return;
        infoContentView.removeAllViews();
        infoInkText = cdTheme && !cdDarkSurface;
        MusicTrack track = null;
        try {
            track = MusicController.get().current();
        } catch (Throwable ignored) {
        }
        // CD 信息态走非 compact（HeroCard＋三段玻璃分区，与 Apple 同构建；GZ CD 为 SongInfoPage 内嵌同源）
        boolean compact = coverTheme;
        if (track == null) {
            TextView hint = infoText(12f, 500, 0x61FFFFFF, getString(R.string.music_info_empty));
            hint.setPadding(dpI(16), dpI(12), dpI(16), dpI(12));
            infoContentView.addView(hint);
            return;
        }
        String title = track.displayTitle();
        if (title == null || title.isEmpty()) title = getString(R.string.music_no_track);
        String artist = track.displayArtist();
        String album = track.album;
        if (compact) {
            TextView head = infoText(16f, 800, 0xF2FFFFFF, title);
            infoContentView.addView(head);
            StringBuilder merged = new StringBuilder();
            if (artist != null && !artist.isEmpty()) merged.append(artist);
            if (album != null && !album.isEmpty()) {
                if (merged.length() > 0) merged.append(" · ");
                merged.append(album);
            }
            TextView sub = infoText(11f, 500, 0x8CFFFFFF, merged.length() > 0 ? merged.toString() : "—");
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dpI(4);
            sub.setLayoutParams(subLp);
            infoContentView.addView(sub);
        } else {
            // HeroCard（SongInfoPageKt 实读：玻璃面圆角 20、内垫 16、封面 80dp 圆角 16、歌名与副行间隔 4）
            LinearLayout hero = new LinearLayout(this);
            hero.setOrientation(LinearLayout.HORIZONTAL);
            hero.setGravity(Gravity.CENTER_VERTICAL);
            if (infoInkText) hero.setBackground(infoLightBg(false));
            else hero.setBackgroundResource(R.drawable.bg_info_glass);
            hero.setPadding(dpI(16), dpI(16), dpI(16), dpI(16));
            ImageView heroCover = new ImageView(this);
            heroCover.setLayoutParams(new LinearLayout.LayoutParams(dpI(80), dpI(80)));
            heroCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            CoverStackView.roundCard(heroCover, 16f, this);
            String heroCoverUrl = effectiveCover(track);
            if (heroCoverUrl == null || heroCoverUrl.isEmpty()) {
                heroCover.setImageResource(R.drawable.ic_music_note);
                heroCover.setPadding(dpI(26), dpI(26), dpI(26), dpI(26));
                heroCover.setImageTintList(ColorStateList.valueOf(infoInk(0x73FFFFFF)));
            } else {
                ImgUtil.load(title, heroCoverUrl, heroCover);
            }
            hero.addView(heroCover);
            LinearLayout heroText = new LinearLayout(this);
            heroText.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams htlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            htlp.leftMargin = dpI(16);
            heroText.setLayoutParams(htlp);
            heroText.addView(infoText(20f, 800, 0xF2FFFFFF, title));
            StringBuilder merged = new StringBuilder();
            if (artist != null && !artist.isEmpty()) merged.append(artist);
            if (album != null && !album.isEmpty()) {
                if (merged.length() > 0) merged.append(" · ");
                merged.append(album);
            }
            TextView sub = infoText(12f, 500, 0x8CFFFFFF, merged.length() > 0 ? merged.toString() : "—");
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dpI(4);
            sub.setLayoutParams(subLp);
            heroText.addView(sub);
            hero.addView(heroText);
            infoContentView.addView(hero);
        }
        // 来源段
        LinearLayout source = infoSection(getString(R.string.music_info_source), compact);
        LinearLayout sourceBody = (LinearLayout) source.getTag();
        infoRow(sourceBody, getString(R.string.music_info_platform),
                track.platform == null || track.platform.isEmpty() ? "—" : track.platform);
        infoRow(sourceBody, getString(R.string.music_info_song_id),
                track.id == null || track.id.isEmpty() ? "—" : track.id);
        String qualityName = "—";
        try {
            qualityName = MusicStore.qualityName(MusicController.get().getQuality());
        } catch (Throwable ignored) {
        }
        infoRow(sourceBody, getString(R.string.music_info_quality_request), qualityName);
        String plugin = null;
        try {
            if (track.rawJson != null && !track.rawJson.isEmpty()) {
                org.json.JSONObject raw = new org.json.JSONObject(track.rawJson);
                plugin = raw.optString("_plugin", null);
            }
        } catch (Throwable ignored) {
        }
        if (plugin != null && !plugin.isEmpty()) {
            infoRow(sourceBody, getString(R.string.music_info_plugin), plugin);
        }
        infoContentView.addView(source);
        // 技术规格段（六格两列：标签 10sp/700/0.55、值 15sp/800/0.95、行距 10dp——TechSpecCard 实读）
        LinearLayout tech = infoSection(compact
                ? getString(R.string.music_info_section_tech)
                : getString(R.string.music_info_section_tech) + " · 实时", compact);
        LinearLayout techBody = (LinearLayout) tech.getTag();
        com.fongmi.android.tv.music.MusicAudioInfo info = null;
        try {
            info = MusicController.get().audioInfo();
        } catch (Throwable ignored) {
        }
        long duration = 0;
        try {
            duration = MusicController.get().duration();
        } catch (Throwable ignored) {
        }
        if (duration <= 0) duration = track.duration;
        String[][] cells = {
                {getString(R.string.music_info_format),
                        info != null && info.codec != null && !info.codec.isEmpty() ? info.codec.toUpperCase(java.util.Locale.US) : "—"},
                {getString(R.string.music_info_bit_depth),
                        info != null && info.bitDepth > 0 ? info.bitDepth + " bit" : "—"},
                {getString(R.string.music_info_bitrate),
                        info != null && info.bitrateKbps > 0 ? info.bitrateKbps + " kbps" : "—"},
                {getString(R.string.music_info_channels), "—"},
                {getString(R.string.music_info_duration), durationLabel(duration)},
        };
        for (int r = 0; r < cells.length; r += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (r > 0) rlp.topMargin = dpI(10);
            row.setLayoutParams(rlp);
            for (int c = 0; c < 2; c++) {
                if (r + c >= cells.length) {
                    // 格数为奇数时末行补等宽空占位（采样率格已按他的口径摘除，见 sampleRate 注记）
                    View spacer = new View(this);
                    spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
                    row.addView(spacer);
                    break;
                }
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                cell.addView(infoText(10f, 700, 0x8CFFFFFF, cells[r + c][0]));
                TextView value = infoText(15f, 800, 0xF2FFFFFF, cells[r + c][1]);
                LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                vlp.topMargin = dpI(4);
                value.setLayoutParams(vlp);
                cell.addView(value);
                row.addView(cell);
            }
            techBody.addView(row);
        }
        infoContentView.addView(tech);
        // 歌词段（筹行＋总行数/加载通道行——LyricsCard 实读构成的子集，星辰有数据源的部分）
        LinearLayout lyric = infoSection(getString(R.string.music_info_section_lyric), compact);
        LinearLayout lyricBody = (LinearLayout) lyric.getTag();
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, 0, 0, dpI(4));
        if (!lyricMains.isEmpty()) {
            String toneLabel = "AMLL".equals(currentLyricFormat)
                    ? getString(R.string.music_lyric_chip_amll)
                    : hasWordLyric() ? getString(R.string.music_lyric_chip_word)
                    : getString(R.string.music_lyric_chip_line);
            infoChip(chips, toneLabel, true);
        }
        if (currentLyricFormat != null && !currentLyricFormat.isEmpty() && !"AMLL".equals(currentLyricFormat)) {
            infoChip(chips, currentLyricFormat, false);
        }
        if (currentLyricFormat != null && !currentLyricFormat.isEmpty() && !"AMLL".equals(currentLyricFormat)) {
            infoChip(chips, currentLyricFormat, false);
        }
        if (hasNonEmpty(lyricTrans)) infoChip(chips, getString(R.string.music_info_chip_translation), false);
        if (hasNonEmpty(lyricRomas)) infoChip(chips, getString(R.string.music_info_chip_roma), false);
        lyricBody.addView(chips);
        infoRow(lyricBody, getString(R.string.music_info_lyric_channel),
                currentLyricFormat == null || currentLyricFormat.isEmpty() ? "—" : currentLyricFormat);
        // 三-42 取链渠道行：服务状态里当前真起播那首的渠道（按 trackKey 守卫，非本曲
        // 或无记录返回 null 显「—」，不拿平台名冒充）；行序承加载通道之后、匹配来源之前
        String playChannel = null;
        try {
            playChannel = MusicController.get().playChannelLabel(track);
        } catch (Throwable ignored) {
        }
        infoRow(lyricBody, getString(R.string.music_info_play_channel),
                playChannel == null || playChannel.isEmpty() ? "—" : playChannel);
        infoRow(lyricBody, getString(R.string.music_info_lyric_matched_source),
                currentLyricSource == null || currentLyricSource.isEmpty() ? "—" : currentLyricSource);
        infoRow(lyricBody, getString(R.string.music_info_lyric_lines),
                lyricMains.isEmpty() ? "—" : lyricMains.size() + " 行");
        infoContentView.addView(lyric);
    }

    private boolean hasWordLyric() {
        for (LyricsLine line : lyricMains) if (line.hasWords()) return true;
        return false;
    }

    private static boolean hasNonEmpty(List<String> list) {
        if (list == null) return false;
        for (String s : list) if (s != null && !s.isEmpty()) return true;
        return false;
    }

    // ================= 工具面板 =================

    private void showToolsSheet() {
        if (isFinishing() || isDestroyed()) return;
        try {
            MusicToolsSheetDialog.show(this, toolsSheetListener());
        } catch (Throwable ignored) {
        }
    }

    private MusicToolsSheetDialog.Listener toolsSheetListener() {
        return new MusicToolsSheetDialog.Listener() {
            @Override
            public void onThemeChanged() {
                applyTheme();
            }

            @Override
            public void onLyricStyleChanged() {
                applyLyricStyle();
                feedLyricView();
            }

            @Override
            public void onSearchLyric() {
                searchLyric();
            }

            @Override
            public void onAddToPlaylist() {
                MusicTrack track = MusicController.get().current();
                if (track != null) pickPlaylist(track);
            }

            @Override
            public void onDownload() {
                downloadCurrent();
            }

            @Override
            public void onAppearanceChanged() {
                applyFluidBg();
            }
        };
    }

    private void pickPlaylist(MusicTrack track) {
        MusicLibrary.playlists(list -> {
            if (isFinishing() || isDestroyed()) return;
            List<String> names = new ArrayList<>();
            for (MusicPlaylist p : list) names.add(p.getName());
            names.add(getString(R.string.music_new_playlist));
            MusicOptionsDialog.show(this, getString(R.string.music_add_to_playlist), names, -1, idx -> {
                if (idx < list.size()) {
                    MusicLibrary.addToPlaylist(list.get(idx).getId(), track, added ->
                            Notify.show(added ? R.string.music_added : R.string.music_already_in));
                } else {
                    inputPlaylistName(name -> {
                        if (name == null || name.trim().isEmpty()) return;
                        MusicLibrary.createPlaylist(name.trim(), p ->
                                MusicLibrary.addToPlaylist(p.getId(), track, added ->
                                        Notify.show(added ? R.string.music_added : R.string.music_already_in)));
                    });
                }
            });
        });
    }

    private interface NameInput {
        void onResult(String name);
    }

    private void inputPlaylistName(NameInput cb) {
        if (isFinishing() || isDestroyed()) return;
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint(R.string.music_playlist_name_hint);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF5E6B7E);
        et.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.music_new_playlist)
                .setView(et)
                .setPositiveButton(android.R.string.ok, (d, w) -> cb.onResult(et.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void downloadCurrent() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        if (com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform)) {
            Notify.show(R.string.music_already_local);
            return;
        }
        if (MusicDownloadStore.isLocal(track)) {
            Notify.show(R.string.music_download_exists);
            return;
        }
        if (MusicStore.getDownloadMode() == MusicStore.DL_MODE_ASK) {
            int[] values = {MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD,
                    MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
            int fixed = MusicStore.getDownloadQualityFixed();
            int checked = 1;
            for (int i = 0; i < values.length; i++) if (values[i] == fixed) checked = i;
            Notify.show("正在查询大小…");
            final int checkedFinal = checked;
            MusicDownloadStore.probeQualitySizes(track, values, sizes -> {
                if (isFinishing() || isDestroyed()) return;
                List<String> opts = new ArrayList<>();
                for (int i = 0; i < values.length; i++) {
                    String name = MusicStore.qualityName(values[i]);
                    opts.add(sizes[i] > 0 ? name + " · " + com.fongmi.android.tv.music.MusicCache.formatSize(sizes[i]) : name);
                }
                MusicOptionsDialog.show(this, getString(R.string.music_set_download_quality), opts, checkedFinal,
                        idx -> MusicDownloadStore.downloadWithQuality(track, values[idx]));
            });
            return;
        }
        MusicDownloadStore.download(track);
    }
}
