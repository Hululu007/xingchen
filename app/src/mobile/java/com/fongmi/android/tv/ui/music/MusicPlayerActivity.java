package com.fongmi.android.tv.ui.music;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.app.NotificationManagerCompat;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.LxKeyIssue;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.LyricVersion;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.ActivityMusicPlayerRebuildBinding;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.music.MusicAudioInfo;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.FavRippleView;
import com.fongmi.android.tv.ui.custom.PlayerPageSwipeLayout;
import com.fongmi.android.tv.ui.dialog.LxKeyIssueDialog;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.ui.dialog.MusicQualityPanelDialog;
import com.fongmi.android.tv.ui.dialog.MusicQueueDialog;
import com.fongmi.android.tv.ui.dialog.MusicToolsSheetDialog;
import com.fongmi.android.tv.ui.minibar.ListenMiniBar;
import com.fongmi.android.tv.utils.CoverColor;
import com.fongmi.android.tv.utils.CoverTextDrawable;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 播放页宿主（重做批二从零重写，接线契约见规格 S4、视觉真值见瓜子对照表）。
 * 本页只经 MusicController 绑服务、永不直调 MusicPlaybackService；主题读写只走
 * MusicPlayerSettings（禁用 MusicStore.getPlayerTheme）；同曲判断只用 MusicQueue.trackKey。
 * 背景一体：骨架背景层满幅拉到状态栏后面（BaseActivity 已 enableEdgeToEdge＋透明状态栏，
 * 本页不动共享地基）；内容层（themeContainer 主题根）统一垫 status_bar_height。
 */
public class MusicPlayerActivity extends BaseActivity implements MusicPlayerHost {

    // 宿主再导出的主题别名（只有 5 个；CD 深／自动无别名，判断时直接用 MusicPlayerSettings 常量）
    public static final String THEME_CLASSIC = MusicPlayerSettings.THEME_CLASSIC;
    public static final String THEME_APPLE = MusicPlayerSettings.THEME_APPLE_MUSIC;
    public static final String THEME_IMMERSIVE = MusicPlayerSettings.THEME_COVER_IMMERSIVE;
    public static final String THEME_CD = MusicPlayerSettings.THEME_LUMINOUS_CD;
    public static final String THEME_SHELF = MusicPlayerSettings.THEME_RECORD_SHELF;

    private ActivityMusicPlayerRebuildBinding binding;
    private boolean destroyed;

    // ===== 主题状态 =====
    private String themeId = MusicPlayerSettings.THEME_COVER_IMMERSIVE;
    private View themeRoot;
    private boolean coverTheme;
    private boolean appleTheme;
    private boolean cdTheme;
    private boolean shelfTheme;
    private int cdSurfaceMode; // 0 浅／1 深／2 自动
    private boolean cdDarkSurface;
    private boolean cdBackdropInit;
    private boolean cdSurfaceSwitchPending;
    private boolean coverExpanded;
    private boolean coverInfoMode;
    private boolean cdExpanded;
    private boolean cdInfoMode;
    private float cdCollapseValue;
    private int lastSkipDirection = 1;

    // ===== 主题视图（grabThemeViews 按 R.id.tp* 抓，未命中保持 null、消费点全空守卫） =====
    private TextView tpTitle;
    private TextView tpArtist;
    private TextView tpAlbum;
    private ImageView tpCover;
    private View tpCoverWrap;
    private View tpPlayTop;
    // 经典
    private ImageView tpFavHeart;
    private View tpBtnMenu;
    private View tpBtnQueueTitle;
    private CoverLyricView tpLyricView;
    private TextView tpPositionTop;
    private TextView tpDurationTop;
    private View tpChipQuality;
    private TextView tpQualityValue;
    private View tpChipLyric;
    private TextView tpLyricChipText;
    private MusicScrubberView tpScrubber;
    // 封面卡片
    private View tpCoverArea;
    private CoverStackView tpCoverStack;
    private ImageView tpCoverBack1;
    private ImageView tpCoverBack2;
    private View tpCardExit;
    private View tpCoverExit;
    private View tpExpandedCol;
    private View tpInfoScroll;
    private ViewGroup tpInfoContent;
    private View tpBtnPrev;
    private View tpBtnNext;
    private View tpBtnQueue;
    private View tpBtnInfo;
    private View tpGlassBar;
    private View tpGlassFav;
    private TextView tpGlassLyric;
    private View tpGlassPlay;
    private View tpGlassBack;
    // Apple
    private ApplePagerView tpPager;
    private ViewGroup tpAppleInfoContent;
    private ImageView tpAppleFav;
    private MusicScrubberView tpAppleScrubber;
    private View tpAppleBtnPrev;
    private View tpAppleBtnNext;
    private MusicVolumeSliderView tpVolumeSlider;
    private View tpBtnLyricMenu;
    private View tpBtnQualityFunc;
    private View tpAppleBtnQueue;
    private FullLyricView tpAppleLyric;
    // CD
    private View tpCdBackdrop;
    private View tpBtnClose;
    private View tpBtnTools;
    private CdDiscView tpCdDisc;
    private View tpCdDetail;
    private CdLyricWindowView tpCdLyric;
    private ViewGroup tpCdInfoContent;
    private View tpCdBelowCol;
    private View tpCdSummaryBox;
    private TextView tpCdSummaryMain;
    private TextView tpCdSummarySub;
    private CdSeekBarView tpCdSeek;
    private TextView tpCdPosition;
    private TextView tpCdDuration;
    private View tpCdBtnInfo;
    private View tpCdBtnQuality;
    private View tpCdBtnLyricVer;
    private View tpCdBtnFav;
    private View tpCdBtnSurface;
    private View tpCdBtnMode;
    private View tpCdBtnPrev;
    private View tpCdPlay;
    private View tpCdBtnNext;
    private View tpCdBtnQueue;
    private View tpCdBottomPad;
    // 唱片架
    private ShelfStageView tpShelfStage;
    private ShelfLyricView tpShelfLyric;
    private TextView tpShelfPosition;
    private TextView tpShelfDuration;
    private CdSeekBarView tpShelfSeek;
    private View tpShelfBtnMode;
    private View tpShelfBtnPrev;
    private View tpShelfPlay;
    private View tpShelfBtnNext;
    private View tpShelfBtnQueue;

    // ===== 曲目／封面状态 =====
    private MusicTrack lastBoundTrack;
    private String lastBoundKey = "";
    private Bitmap currentCoverBitmap;
    private String currentCoverUrl = "";
    private int ambientColor = CoverColor.FALLBACK_COLOR;
    private int cdToneBaseColor = CoverColor.FALLBACK_COLOR;
    private boolean currentFavorite;
    private int favTintColor;
    private String coverBitmapKey = "";
    private Bitmap shelfBmpPrev;
    private Bitmap shelfBmpNext;
    private Bitmap coverBmpPrev;
    private Bitmap coverBmpNext;
    private int coverLoadSeq;

    // ===== 歌词状态 =====
    private List<LyricsLine> lyricMains = Collections.emptyList();
    private List<String> lyricSubs = Collections.emptyList();
    private boolean lyricEmpty = true;
    private boolean lyricLoading;
    private int lyricLoadSeq;
    private final List<LyricVersion> currentLyricVersions = new ArrayList<>();
    private String currentLyricKey = "";
    private MusicLyric currentLyric;
    private java.util.function.Consumer<MusicLyric> earlyLyricListener;

    // ===== 律动／动画 =====
    private VisualizerRhythmSource rhythmSource;
    private ValueAnimator ambientAnimator;
    private ValueAnimator cdCollapseAnimator;
    private ValueAnimator cdToneAnimator;
    private ValueAnimator lyricSettleAnimator;
    private ValueAnimator favTintAnimator;
    private ValueAnimator coverScaleAnimator;
    private final List<Animator> heartAnimators = new ArrayList<>();
    private Choreographer coverScaleChoreographer;
    private Choreographer.FrameCallback coverScaleFrame;
    private float coverScaleVelocity;
    private FavRippleView favRippleView;

    // ===== 全屏歌词页 =====
    private MusicLyricPageView lyricPageView;
    private boolean lyricPageOpen;
    private boolean lyricSwipeTracking;
    private float lyricSwipeDownX;
    private float lyricSwipeDownY;
    private float lyricSwipeDx;
    private boolean lyricSwipeEngaged;
    private VelocityTracker swipeVelocityTracker;

    // ===== 后台分家 =====
    private boolean bgPausedMusic;
    private String bgPausedTrackKey = "";

    private final Random heartRandom = new Random();

    public static void start(Activity activity) {
        if (activity == null) return;
        activity.startActivity(new Intent(activity, MusicPlayerActivity.class));
    }

    @Override
    protected boolean useDarkSystemIcons() {
        // 播放页是封面深色底：状态栏图标用白色（背景已拉满到状态栏后面）
        return false;
    }

    @Override
    protected ViewBinding getBinding() {
        if (binding == null) binding = ActivityMusicPlayerRebuildBinding.inflate(getLayoutInflater());
        return binding;
    }

    // ================= 监听器（S4 §1.3 回调消费表） =================

    private final MusicController.Listener listener = new MusicController.Listener() {
        @Override
        public void onTrackChanged(MusicTrack track) {
            runOnUiThread(() -> bindTrack(track));
        }

        @Override
        public void onTrackReady(MusicTrack track) {
            // 落位后收藏态才可信，只刷收藏图标
            runOnUiThread(() -> paintFavoriteIcons());
        }

        @Override
        public void onLyricReady(MusicTrack track) {
            runOnUiThread(() -> {
                // 四重门：切主线程（本行）／当前曲与入参非空／trackKey 身份守卫／只补空不覆盖
                MusicTrack cur = MusicController.get().current();
                if (cur == null || track == null) return;
                if (!MusicQueue.trackKey(cur).equals(MusicQueue.trackKey(track))) return;
                if (lyricEmpty && !lyricLoading) loadLyric(cur, false);
            });
        }

        @Override
        public void onPlayingChanged(boolean playing) {
            runOnUiThread(() -> setPlaying(playing));
        }

        @Override
        public void onModeChanged(int mode) {
            runOnUiThread(() -> paintCdModeIcon());
        }

        @Override
        public void onProgress(long position, long duration) {
            // 控制器对 onProgress 不经 App.post 直转，页面侧自己切主线程
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

    // ================= 生命周期 =================

    @Override
    protected void initView(Bundle savedInstanceState) {
        // 固定序（S4 §1.1）：ensureDebugFake → bind → addListener → 通知提示条 → 竖滑/歌词页/主题 → 首次全量对齐
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
        MusicController.get().addListener(listener);
        if (binding.notifHintGo != null) {
            binding.notifHintGo.setOnClickListener(v -> Notify.openNotificationSettings(this));
        }
        refreshNotifHint();
        attachPageSwipe();
        setupLyricPage();
        applyTheme();
        MusicTrack cur = MusicController.get().current();
        bindTrack(cur);
        setPlaying(MusicController.get().isPlaying());
        refreshQualityValue();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 先摘后加防双注册，再全量对齐现态（S4 §1.3）
        MusicController.get().removeListener(listener);
        MusicController.get().addListener(listener);
        setPlaying(MusicController.get().isPlaying());
        if (!MusicPlayerSettings.getTheme().equals(themeId)) {
            applyTheme();
        } else {
            applyLyricStyle();
        }
        MusicTrack cur = MusicController.get().current();
        if (cur != null && !MusicQueue.trackKey(cur).equals(lastBoundKey)) {
            bindTrack(cur);
        } else if (cur != null && lyricEmpty && !lyricLoading) {
            loadLyric(cur, false);
        }
        // 后台分家回页续播：只对同一 trackKey 且仍未播时（S4 §7.1）
        if (bgPausedMusic && cur != null
                && MusicQueue.trackKey(cur).equals(bgPausedTrackKey)
                && !MusicController.get().isPlaying()) {
            MusicController.get().play();
        }
        bgPausedMusic = false;
        applyKeepScreenOn();
        refreshNotifHint();
        syncVolumeSlider();
        try {
            ListenMiniBar.get(this).pageResumed("music");
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        MusicController.get().removeListener(listener);
        try {
            ListenMiniBar.get(this).pagePaused("music");
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 后台分家（S4 §7.1）：视频与音乐各一份开关；音乐侧关后台时离页暂停并记 trackKey
        if (PlayerSetting.isMusicBackgroundOff() && MusicController.get().isPlaying()) {
            MusicTrack cur = MusicController.get().current();
            if (!isFinishing() && cur != null) {
                bgPausedTrackKey = MusicQueue.trackKey(cur);
                bgPausedMusic = true;
            }
            MusicController.get().pause();
        }
    }

    @Override
    protected void onDestroy() {
        // 七步序钉死（S4 §7.2）＋§7.5 三缺口补收口，顺序不许换
        destroyed = true;
        if (rhythmSource != null) rhythmSource.stop();
        MusicController.get().removeListener(listener);
        MusicController.get().unbind();
        if (ambientAnimator != null) ambientAnimator.cancel();
        if (cdCollapseAnimator != null) cdCollapseAnimator.cancel();
        if (cdToneAnimator != null) cdToneAnimator.cancel();
        cancelCoverScaleSpring();
        // §7.5 补收口一：歌词页收尾动画确定性取消
        cancelLyricSettle();
        // §7.5 补收口二：横滑观察层速度跟踪器回收
        if (swipeVelocityTracker != null) {
            swipeVelocityTracker.recycle();
            swipeVelocityTracker = null;
        }
        // §7.5 补收口三：局部动画一并收口（收藏 tint／封面缩放／爱心粒子）
        if (favTintAnimator != null) favTintAnimator.cancel();
        if (coverScaleAnimator != null) coverScaleAnimator.cancel();
        for (Animator a : new ArrayList<>(heartAnimators)) a.cancel();
        heartAnimators.clear();
        if (earlyLyricListener != null && lastBoundTrack != null) {
            MusicRepository.clearEarlyLyricListener(lastBoundTrack, earlyLyricListener);
        }
        seekHandler.removeCallbacksAndMessages(null);
        seekPending = null;
        if (binding != null && binding.ivBgBlur != null) binding.ivBgBlur.release();
        super.onDestroy();
    }

    @Override
    protected void onBackInvoked() {
        // 全屏歌词页开着时先关页、不退页（S4 §7.1）
        if (lyricPageOpen) {
            hideLyricPage();
            return;
        }
        super.onBackInvoked();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 面板／弹窗抢焦点时喂盘面停／续推进
        if (tpCdDisc != null) tpCdDisc.setForegroundPanelOpen(!hasFocus);
    }

    /** 不熄屏：直读总仓（此键不在 MusicPlayerSettings 里，S4 §3.4）。 */
    private void applyKeepScreenOn() {
        if (MusicStore.isPlayerKeepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void refreshNotifHint() {
        if (binding == null || binding.notifHintStrip == null) return;
        boolean enabled = NotificationManagerCompat.from(this).areNotificationsEnabled();
        binding.notifHintStrip.setVisibility(enabled ? View.GONE : View.VISIBLE);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    // ================= 页级竖滑（S4 §2.6：宿主只做方向→切歌命令的翻译） =================

    private void attachPageSwipe() {
        PlayerPageSwipeLayout container = binding.themeContainer;
        if (container == null) return;
        container.setOnPageSwipeListener(next -> {
            lastSkipDirection = next ? 1 : -1;
            if (next) MusicController.get().next();
            else MusicController.get().prev();
        });
        container.setSwipeHintView(binding.swipeHint, binding.swipeHintDir, binding.swipeHintName,
                getString(R.string.music_next), getString(R.string.music_prev), this::swipeNeighborName);
    }

    /** 相邻歌名按 queue.indexOf(current)±1 线性取（不走 nextIndex，旧口径实情，照抄不「修对」）。 */
    private String swipeNeighborName(boolean next) {
        MusicTrack cur = MusicController.get().current();
        if (cur == null) return "";
        List<MusicTrack> queue = MusicController.get().queue();
        if (queue == null || queue.isEmpty()) return "";
        int idx = -1;
        String curKey = MusicQueue.trackKey(cur);
        for (int i = 0; i < queue.size(); i++) {
            if (curKey.equals(MusicQueue.trackKey(queue.get(i)))) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return "";
        int target = next ? idx + 1 : idx - 1;
        if (target < 0 || target >= queue.size()) return "";
        MusicTrack t = queue.get(target);
        return t == null ? "" : t.displayTitle();
    }

    // ================= 主题装配（S4 §2.3 九步序） =================

    private void applyTheme() {
        themeId = MusicPlayerSettings.getTheme();
        binding.themeContainer.removeAllViews();
        coverTheme = MusicPlayerSettings.THEME_COVER_IMMERSIVE.equals(themeId);
        appleTheme = MusicPlayerSettings.THEME_APPLE_MUSIC.equals(themeId);
        cdTheme = MusicPlayerSettings.THEME_LUMINOUS_CD.equals(themeId)
                || MusicPlayerSettings.THEME_LUMINOUS_CD_DARK.equals(themeId)
                || MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO.equals(themeId);
        shelfTheme = MusicPlayerSettings.THEME_RECORD_SHELF.equals(themeId);
        if (cdTheme) {
            cdSurfaceMode = MusicPlayerSettings.THEME_LUMINOUS_CD_DARK.equals(themeId) ? 1
                    : MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO.equals(themeId) ? 2 : 0;
            cdDarkSurface = resolveCdDarkSurface();
        }
        // 竖滑开关：Apple（横滑 pager＋歌词页竖滚）与唱片架（舞台横滑）关，余开
        binding.themeContainer.setSwipeEnabled(!(appleTheme || shelfTheme));
        coverExpanded = false;
        coverInfoMode = false;
        cdExpanded = false;
        cdInfoMode = false;
        cdCollapseValue = 0f;
        if (!cdSurfaceSwitchPending) cdBackdropInit = false;
        cdSurfaceSwitchPending = false;
        int layoutId = coverTheme ? R.layout.music_player_theme_cover
                : appleTheme ? R.layout.music_player_theme_apple
                : cdTheme ? R.layout.music_player_theme_cd
                : shelfTheme ? R.layout.music_player_theme_shelf
                : R.layout.music_player_theme_classic;
        themeRoot = getLayoutInflater().inflate(layoutId, binding.themeContainer, false);
        binding.themeContainer.addView(themeRoot);
        grabThemeViews();
        applyTopInsetForTheme();
        wireThemeViews();
        applyLyricStyle();
        feedLyricViews();
        bindTrackViews(MusicController.get().current());
        setPlaying(MusicController.get().isPlaying());
        refreshQualityValue();
        applyFluidBg();
        applySkeletonForTheme();
        // 主题重装会换新 View：律动源重新注入当前 cdDiscView 与 fluidAmbient
        syncRhythmSource();
    }

    private boolean resolveCdDarkSurface() {
        if (cdSurfaceMode == 1) return true;
        if (cdSurfaceMode == 2) {
            int night = getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }
        return false;
    }

    /** CD 表面循环（S4 §2.5）：主题 id 即状态，LIGHT→DARK→AUTO 回写 setTheme 后整页重装，无第二把键。 */
    private void cycleCdSurface() {
        String next = MusicPlayerSettings.THEME_LUMINOUS_CD.equals(themeId)
                ? MusicPlayerSettings.THEME_LUMINOUS_CD_DARK
                : MusicPlayerSettings.THEME_LUMINOUS_CD_DARK.equals(themeId)
                ? MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO
                : MusicPlayerSettings.THEME_LUMINOUS_CD;
        MusicPlayerSettings.setTheme(next);
        cdSurfaceSwitchPending = true;
        Notify.show(MusicPlayerSettings.THEME_LUMINOUS_CD_DARK.equals(next) ? "深色表面"
                : MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO.equals(next) ? "自动表面" : "浅色表面");
        applyTheme();
    }

    /** 状态栏落点：背景层在骨架级满幅（见骨架布局），内容层 themeRoot 顶垫 status_bar_height，全主题统一。 */
    private void applyTopInsetForTheme() {
        if (themeRoot == null) return;
        themeRoot.setPadding(themeRoot.getPaddingLeft(), statusBarHeight(),
                themeRoot.getPaddingRight(), themeRoot.getPaddingBottom());
    }

    /** 骨架层按主题显隐：CD／唱片架自有背景，隐藏糊底／流体／罩。 */
    private void applySkeletonForTheme() {
        boolean ownBg = cdTheme || shelfTheme;
        if (binding.ivBgBlur != null) binding.ivBgBlur.setVisibility(ownBg ? View.GONE : View.VISIBLE);
        if (binding.viewVeil != null) binding.viewVeil.setVisibility(ownBg ? View.GONE : View.VISIBLE);
        if (ownBg && binding.fluidAmbient != null) binding.fluidAmbient.setVisibility(View.GONE);
        else applyFluidBg();
    }

    /** 流体开关下发：开则流体层显形并喂基准色／软图／速度／律动源，糊版让位；关则反之。 */
    private void applyFluidBg() {
        if (binding == null || binding.fluidAmbient == null) return;
        boolean ownBg = cdTheme || shelfTheme;
        boolean fluidOn = !ownBg && MusicPlayerSettings.isFluidBg();
        binding.fluidAmbient.setVisibility(fluidOn ? View.VISIBLE : View.GONE);
        if (binding.ivBgBlur != null && !ownBg) {
            binding.ivBgBlur.setVisibility(fluidOn ? View.INVISIBLE : View.VISIBLE);
        }
        if (fluidOn) {
            binding.fluidAmbient.setBaseColor(CoverColor.toneAdjust(ambientColor));
            if (currentCoverBitmap != null && !currentCoverBitmap.isRecycled()) {
                binding.fluidAmbient.setBackdropBitmap(
                        Bitmap.createScaledBitmap(currentCoverBitmap, 64, 64, true));
            }
            binding.fluidAmbient.setSpeed(MusicPlayerSettings.fluidSpeedFactor());
            binding.fluidAmbient.setRhythmSource(rhythmSource);
        }
    }

    private void grabThemeViews() {
        tpTitle = findTp(R.id.tpTitle);
        tpArtist = findTp(R.id.tpArtist);
        tpAlbum = findTp(R.id.tpAlbum);
        tpCover = findTp(R.id.tpCover);
        tpCoverWrap = findTp(R.id.tpCoverWrap);
        tpPlayTop = findTp(R.id.tpPlayTop);
        // 经典
        tpFavHeart = findTp(R.id.tpFavHeart);
        tpBtnMenu = findTp(R.id.tpBtnMenu);
        tpBtnQueueTitle = findTp(R.id.tpBtnQueueTitle);
        tpLyricView = findTp(R.id.tpLyricView);
        tpPositionTop = findTp(R.id.tpPositionTop);
        tpDurationTop = findTp(R.id.tpDurationTop);
        tpChipQuality = findTp(R.id.tpChipQuality);
        tpQualityValue = findTp(R.id.tpQualityValue);
        tpChipLyric = findTp(R.id.tpChipLyric);
        tpLyricChipText = findTp(R.id.tpLyricChipText);
        tpScrubber = findTp(R.id.tpScrubber);
        // 封面卡片
        tpCoverArea = findTp(R.id.tpCoverArea);
        tpCoverStack = findTp(R.id.tpCoverStack);
        tpCoverBack1 = findTp(R.id.tpCoverBack1);
        tpCoverBack2 = findTp(R.id.tpCoverBack2);
        tpCardExit = findTp(R.id.tpCardExit);
        tpCoverExit = findTp(R.id.tpCoverExit);
        tpExpandedCol = findTp(R.id.tpExpandedCol);
        tpInfoScroll = findTp(R.id.tpInfoScroll);
        tpInfoContent = findTp(R.id.tpInfoContent);
        tpBtnPrev = findTp(R.id.tpBtnPrev);
        tpBtnNext = findTp(R.id.tpBtnNext);
        tpBtnQueue = findTp(R.id.tpBtnQueue);
        tpBtnInfo = findTp(R.id.tpBtnInfo);
        tpGlassBar = findTp(R.id.tpGlassBar);
        tpGlassFav = findTp(R.id.tpGlassFav);
        tpGlassLyric = findTp(R.id.tpGlassLyric);
        tpGlassPlay = findTp(R.id.tpGlassPlay);
        tpGlassBack = findTp(R.id.tpGlassBack);
        // Apple
        tpPager = findTp(R.id.tpPager);
        tpAppleInfoContent = findTp(R.id.tpInfoContent);
        tpAppleFav = findTp(R.id.tpAppleFav);
        tpAppleScrubber = tpScrubber;
        tpAppleBtnPrev = findTp(R.id.tpBtnPrev);
        tpAppleBtnNext = findTp(R.id.tpBtnNext);
        tpVolumeSlider = findTp(R.id.tpVolumeSlider);
        tpBtnLyricMenu = findTp(R.id.tpBtnLyricMenu);
        tpBtnQualityFunc = findTp(R.id.tpBtnQualityFunc);
        tpAppleBtnQueue = findTp(R.id.tpBtnQueue);
        tpAppleLyric = findTp(R.id.tpAppleLyric);
        // CD
        tpCdBackdrop = findTp(R.id.tpCdBackdrop);
        tpBtnClose = findTp(R.id.tpBtnClose);
        tpBtnTools = findTp(R.id.tpBtnTools);
        tpCdDisc = findTp(R.id.tpCdDisc);
        tpCdDetail = findTp(R.id.tpCdDetail);
        tpCdLyric = findTp(R.id.tpCdLyric);
        tpCdInfoContent = tpInfoContent;
        tpCdBelowCol = findTp(R.id.tpCdBelowCol);
        tpCdSummaryBox = findTp(R.id.tpCdSummaryBox);
        tpCdSummaryMain = findTp(R.id.tpCdSummaryMain);
        tpCdSummarySub = findTp(R.id.tpCdSummarySub);
        tpCdSeek = findTp(R.id.tpCdSeek);
        tpCdPosition = findTp(R.id.tpCdPosition);
        tpCdDuration = findTp(R.id.tpCdDuration);
        tpCdBtnInfo = findTp(R.id.tpCdBtnInfo);
        tpCdBtnQuality = findTp(R.id.tpCdBtnQuality);
        tpCdBtnLyricVer = findTp(R.id.tpCdBtnLyricVer);
        tpCdBtnFav = findTp(R.id.tpCdBtnFav);
        tpCdBtnSurface = findTp(R.id.tpCdBtnSurface);
        tpCdBtnMode = findTp(R.id.tpCdBtnMode);
        tpCdBtnPrev = findTp(R.id.tpCdBtnPrev);
        tpCdPlay = findTp(R.id.tpCdPlay);
        tpCdBtnNext = findTp(R.id.tpCdBtnNext);
        tpCdBtnQueue = findTp(R.id.tpCdBtnQueue);
        tpCdBottomPad = findTp(R.id.tpCdBottomPad);
        // 唱片架
        tpShelfStage = findTp(R.id.tpShelfStage);
        tpShelfLyric = findTp(R.id.tpShelfLyric);
        tpShelfPosition = findTp(R.id.tpShelfPosition);
        tpShelfDuration = findTp(R.id.tpShelfDuration);
        tpShelfSeek = findTp(R.id.tpShelfSeek);
        tpShelfBtnMode = findTp(R.id.tpShelfBtnMode);
        tpShelfBtnPrev = findTp(R.id.tpShelfBtnPrev);
        tpShelfPlay = findTp(R.id.tpShelfPlay);
        tpShelfBtnNext = findTp(R.id.tpShelfBtnNext);
        tpShelfBtnQueue = findTp(R.id.tpShelfBtnQueue);
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T findTp(int id) {
        if (themeRoot == null) return null;
        return (T) themeRoot.findViewById(id);
    }

    // ================= 主题接线 =================

    private void wireThemeViews() {
        // 共享接线：播放键、菜单键（S4 §4.1 经典/Apple 菜单键同一入口）
        if (tpPlayTop != null) {
            tpPlayTop.setOnClickListener(v -> MusicController.get().toggle());
            attachPressScale(tpPlayTop, 0.96f);
        }
        if (tpBtnMenu != null) tpBtnMenu.setOnClickListener(v -> showToolsSheet());
        if (tpBtnQueueTitle != null) tpBtnQueueTitle.setOnClickListener(v -> MusicQueueDialog.show(this));
        if (tpFavHeart != null) tpFavHeart.setOnClickListener(v -> toggleFavorite());
        if (tpChipQuality != null) {
            tpChipQuality.setOnClickListener(v -> showQualityDialog());
            attachPressScale(tpChipQuality, 0.96f);
        }
        if (tpChipLyric != null) {
            tpChipLyric.setOnClickListener(v -> openLyricVersionDialog());
            attachPressScale(tpChipLyric, 0.96f);
        }
        if (tpScrubber != null) {
            tpScrubber.setOnScrubListener(new MusicScrubberView.OnScrubListener() {
                @Override
                public void onScrubStart() {
                }

                @Override
                public void onScrubPreview(float fraction) {
                    long dur = MusicController.get().duration();
                    if (dur > 0) setPositionText(Math.round(fraction * dur), dur);
                }

                @Override
                public void onScrubEnd(float fraction) {
                    long dur = MusicController.get().duration();
                    if (dur > 0) MusicController.get().seekTo(Math.round(fraction * dur));
                }
            });
        }
        if (tpLyricView != null) {
            tpLyricView.setOnLineClickListener((index, timeMs) -> MusicController.get().seekTo(timeMs));
        }
        if (coverTheme) wireCoverTheme();
        if (appleTheme) wireAppleTheme();
        if (cdTheme) wireCdTheme();
        if (shelfTheme) wireShelfTheme();
        if (!coverTheme && !appleTheme && !cdTheme && !shelfTheme) wireClassicTheme();
    }

    private void wireClassicTheme() {
        // 经典封面：点按进全屏歌词页（他的定案，与双击互斥）／双击收藏＋爱心爆发／长按工具面板
        attachCoverGestures(tpCoverWrap != null ? tpCoverWrap : tpCover, false);
    }

    private void wireCoverTheme() {
        if (tpCover != null) CoverStackView.roundCard(tpCover, 22f, this);
        if (tpCoverWrap != null) {
            tpCoverWrap.setClipToOutline(true);
        }
        if (tpCoverStack != null) {
            // ThreeCard 换卡动画归构件（CoverStackView 内部 350ms 全程：出场层已承旧图滑出）。
            // 回调在动画开始即发：宿主只同步换 tpCover 的图（预载的相邻位图），不许再对
            // tpCardExit 跑任何动画，否则双动画打架。后叠卡是空占位卡（布局底图），宿主不喂图。
            tpCoverStack.setOnHorizontalSwipeListener(next -> {
                lastSkipDirection = next ? 1 : -1;
                int targetIdx = next ? MusicController.get().queueNextIndex()
                        : MusicController.get().queuePrevIndex();
                List<MusicTrack> queue = MusicController.get().queue();
                MusicTrack target = queue != null && targetIdx >= 0 && targetIdx < queue.size()
                        ? queue.get(targetIdx) : null;
                if (next) MusicController.get().next();
                else MusicController.get().prev();
                syncSwapCoverFace(target, next);
            });
        }
        // 封面卡点按＝切展开/信息两态；长按＝工具面板（经典/封面卡片共用入口，S4 §4.1）
        attachCoverGestures(tpCoverWrap != null ? tpCoverWrap : tpCover, true);
        if (tpLyricView != null) {
            tpLyricView.setOnLineClickListener((index, timeMs) -> MusicController.get().seekTo(timeMs));
        }
        if (tpBtnPrev != null) tpBtnPrev.setOnClickListener(v -> {
            lastSkipDirection = -1;
            MusicController.get().prev();
        });
        if (tpBtnNext != null) tpBtnNext.setOnClickListener(v -> {
            lastSkipDirection = 1;
            MusicController.get().next();
        });
        if (tpBtnQueue != null) tpBtnQueue.setOnClickListener(v -> {
            // 先退出信息态再开队列（S4 §4.3）
            if (coverInfoMode) setCoverInfoMode(false);
            MusicQueueDialog.show(this);
        });
        if (tpBtnInfo != null) tpBtnInfo.setOnClickListener(v -> setCoverInfoMode(!coverInfoMode));
        if (tpGlassFav != null) {
            tpGlassFav.setOnClickListener(v -> toggleFavorite());
            attachPressScale(tpGlassFav, 0.88f);
        }
        if (tpGlassPlay != null) {
            tpGlassPlay.setOnClickListener(v -> MusicController.get().toggle());
            attachPressScale(tpGlassPlay, 0.88f);
        }
        if (tpGlassBack != null) tpGlassBack.setOnClickListener(v -> setCoverExpanded(false));
        if (tpGlassBar != null) attachPressScale(tpGlassBar, 0.98f);
        paintCoverExpanded();
    }

    private void wireAppleTheme() {
        // Apple 封面无点按/双击、只长按震动＋工具面板（§15.1 第 9 项 a）
        attachCoverGestures(tpCoverWrap != null ? tpCoverWrap : tpCover, false, true);
        if (tpAppleFav != null) tpAppleFav.setOnClickListener(v -> toggleFavorite());
        if (tpAppleBtnPrev != null) tpAppleBtnPrev.setOnClickListener(v -> {
            lastSkipDirection = -1;
            MusicController.get().prev();
        });
        if (tpAppleBtnNext != null) tpAppleBtnNext.setOnClickListener(v -> {
            lastSkipDirection = 1;
            MusicController.get().next();
        });
        if (tpAppleBtnQueue != null) tpAppleBtnQueue.setOnClickListener(v -> MusicQueueDialog.show(this));
        if (tpBtnLyricMenu != null) tpBtnLyricMenu.setOnClickListener(v -> openLyricVersionDialog());
        if (tpBtnQualityFunc != null) tpBtnQualityFunc.setOnClickListener(v -> showQualityDialog());
        if (tpAppleLyric != null) {
            tpAppleLyric.setOnLineClickListener((index, timeMs) -> MusicController.get().seekTo(timeMs));
        }
        if (tpScrubber != null) tpScrubber.setVariant(6f, 7f, 0.58f, 0.90f);
        if (tpVolumeSlider != null) {
            tpVolumeSlider.setOnVolumeListener(fraction -> {
                // 音量值源＝系统 STREAM_MUSIC（瓜子是播放器内部音量，星辰无内部音量 API；对照表 §13.3 第 4 项口径）
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am == null) return;
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                am.setStreamVolume(AudioManager.STREAM_MUSIC,
                        Math.round(Math.max(0f, Math.min(1f, fraction)) * max), 0);
            });
            syncVolumeSlider();
        }
    }

    private void syncVolumeSlider() {
        if (tpVolumeSlider == null) return;
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (max > 0) {
            tpVolumeSlider.setFraction((float) am.getStreamVolume(AudioManager.STREAM_MUSIC) / max);
        }
    }

    private void wireCdTheme() {
        if (tpBtnClose != null) tpBtnClose.setOnClickListener(v -> finish());
        if (tpBtnTools != null) tpBtnTools.setOnClickListener(v -> showToolsSheet());
        if (tpCdDisc != null) {
            tpCdDisc.setDarkSurface(cdDarkSurface);
            tpCdDisc.setCoverTone(cdToneBaseColor);
            // 盘面点按无动作（信息/歌词窗开时拦截为收起）；长按＝工具面板
            tpCdDisc.setOnClickListener(v -> {
                if (cdExpanded) setCdExpanded(false);
            });
            tpCdDisc.setOnLongClickListener(v -> {
                showToolsSheet();
                return true;
            });
            tpCdDisc.setRhythmSource(rhythmSource);
        }
        if (tpCdSummaryBox != null) {
            tpCdSummaryBox.setOnClickListener(v -> setCdExpanded(!cdExpanded));
        }
        if (tpCdLyric != null) {
            tpCdLyric.setOnLineClickListener((index, timeMs) -> MusicController.get().seekTo(timeMs));
        }
        if (tpCdSeek != null) {
            // OnSeekListener 只有 onSeek 一个回调：按下/拖动/抬手三时点都回调 fraction。
            // 宿主口径：即时刷时间预览，seek 提交做 120ms 合并（最后一个时点必落位）。
            tpCdSeek.setOnSeekListener(fraction -> {
                long dur = MusicController.get().duration();
                if (dur <= 0) return;
                if (tpCdPosition != null) {
                    tpCdPosition.setText(formatTime(Math.round(fraction * dur)));
                }
                scheduleSeekCommit(fraction);
            });
        }
        if (tpCdBtnInfo != null) tpCdBtnInfo.setOnClickListener(v -> {
            cdInfoMode = !cdInfoMode;
            if (cdInfoMode && !cdExpanded) setCdExpanded(true);
            else paintCdDetailMode();
        });
        if (tpCdBtnQuality != null) tpCdBtnQuality.setOnClickListener(v -> showQualityDialog());
        if (tpCdBtnLyricVer != null) tpCdBtnLyricVer.setOnClickListener(v -> openLyricVersionDialog());
        if (tpCdBtnFav != null) tpCdBtnFav.setOnClickListener(v -> toggleFavorite());
        if (tpCdBtnSurface != null) tpCdBtnSurface.setOnClickListener(v -> cycleCdSurface());
        if (tpCdBtnMode != null) tpCdBtnMode.setOnClickListener(v -> {
            MusicController.get().cycleMode();
            paintCdModeIcon();
        });
        if (tpCdBtnPrev != null) tpCdBtnPrev.setOnClickListener(v -> {
            lastSkipDirection = -1;
            MusicController.get().prev();
        });
        if (tpCdBtnNext != null) tpCdBtnNext.setOnClickListener(v -> {
            lastSkipDirection = 1;
            MusicController.get().next();
        });
        if (tpCdPlay != null) {
            tpCdPlay.setOnClickListener(v -> MusicController.get().toggle());
            attachPressScale(tpCdPlay, 0.9f);
        }
        if (tpCdBtnQueue != null) tpCdBtnQueue.setOnClickListener(v -> MusicQueueDialog.show(this));
        paintCdPalette();
        paintCdModeIcon();
        paintCdLyricVerAlpha();
        paintCdUtilityStates();
    }

    private void wireShelfTheme() {
        if (tpBtnClose != null) tpBtnClose.setOnClickListener(v -> finish());
        if (tpBtnTools != null) tpBtnTools.setOnClickListener(v -> showToolsSheet());
        if (tpShelfStage != null) {
            tpShelfStage.setListener(new ShelfStageView.Listener() {
                @Override
                public void onSwipeCommit(boolean next) {
                    // 舞台已自转槽位，宿主只发切歌命令；回绑时 trackKey 对账免清屏（bindCovers 内 consumeJustShifted）
                    lastSkipDirection = next ? 1 : -1;
                    if (next) MusicController.get().next();
                    else MusicController.get().prev();
                }

                @Override
                public void onDiscTap() {
                    // 点当前槽＝收藏（S4/对照表 §6）
                    toggleFavorite();
                }
            });
        }
        if (tpShelfLyric != null) {
            tpShelfLyric.setOnLineClickListener((index, timeMs) -> MusicController.get().seekTo(timeMs));
        }
        if (tpShelfSeek != null) {
            tpShelfSeek.setOnSeekListener(fraction -> {
                long dur = MusicController.get().duration();
                if (dur <= 0) return;
                if (tpShelfPosition != null) {
                    tpShelfPosition.setText(formatTime(Math.round(fraction * dur)));
                }
                scheduleSeekCommit(fraction);
            });
        }
        if (tpShelfBtnMode != null) tpShelfBtnMode.setOnClickListener(v -> {
            MusicController.get().cycleMode();
            paintCdModeIcon();
        });
        if (tpShelfBtnPrev != null) tpShelfBtnPrev.setOnClickListener(v -> {
            lastSkipDirection = -1;
            if (tpShelfStage != null && !tpShelfStage.isAnimating()) {
                tpShelfStage.advanceAnimated(false, () -> MusicController.get().prev());
            } else {
                MusicController.get().prev();
            }
        });
        if (tpShelfBtnNext != null) tpShelfBtnNext.setOnClickListener(v -> {
            lastSkipDirection = 1;
            if (tpShelfStage != null && !tpShelfStage.isAnimating()) {
                tpShelfStage.advanceAnimated(true, () -> MusicController.get().next());
            } else {
                MusicController.get().next();
            }
        });
        if (tpShelfPlay != null) {
            tpShelfPlay.setOnClickListener(v -> MusicController.get().toggle());
            attachPressScale(tpShelfPlay, 0.9f);
        }
        if (tpShelfBtnQueue != null) tpShelfBtnQueue.setOnClickListener(v -> MusicQueueDialog.show(this));
        paintCdModeIcon();
    }

    private final android.os.Handler seekHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable seekPending;

    /** CdSeekBar 的 seek 提交合并：连续 fraction 只留最后一个，120ms 后落位（抬手时点必达）。 */
    private void scheduleSeekCommit(float fraction) {
        if (seekPending != null) seekHandler.removeCallbacks(seekPending);
        final float target = fraction;
        seekPending = () -> {
            long dur = MusicController.get().duration();
            if (dur > 0 && !destroyed) MusicController.get().seekTo(Math.round(target * dur));
        };
        seekHandler.postDelayed(seekPending, 120);
    }

    /** 按压回弹（瓜子口径：按下缩到 scale、松手回 1；动画 150ms 贝塞尔 (0.2,0,0,1)）。 */
    private void attachPressScale(View view, float scale) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            if (!v.isEnabled()) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(scale).scaleY(scale).setDuration(150)
                        .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                        .start();
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150)
                        .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                        .start();
            }
            return false;
        });
    }

    // ================= 曲目绑绑与同步（三面同步：切歌必清旧词、进度归零、封面/歌名同换） =================

    private void bindTrack(MusicTrack track) {
        lastBoundTrack = track;
        lastBoundKey = MusicQueue.trackKey(track);
        syncRhythmSource();
        bindTrackViews(track);
        if (track == null) {
            clearLyricSurfaces();
            lyricEmpty = true;
            currentLyric = null;
            currentLyricVersions.clear();
            currentLyricKey = "";
            return;
        }
        loadLyric(track, false);
        refreshFavorite(track);
        refreshQualityValue();
    }

    /** 全主题刷：标题/歌手/专辑/封面/筹/摘要/玻璃条占位——不许还挂着上一首的脸。 */
    private void bindTrackViews(MusicTrack track) {
        String title = track == null ? "" : track.displayTitle();
        String artist = track == null ? "" : track.displayArtist();
        String album = track == null || track.album == null ? "" : track.album;
        if (tpTitle != null) {
            tpTitle.setText(title);
            tpTitle.setSelected(true);
        }
        if (tpArtist != null) tpArtist.setText(artist);
        if (tpAlbum != null) {
            tpAlbum.setText(album);
            tpAlbum.setVisibility(album.isEmpty() ? View.GONE : View.VISIBLE);
        }
        // 进度归零（切歌定案）
        resetProgressUi(track == null ? 0 : track.duration);
        // 玻璃条单行歌词先回占位，等本曲词上屏后由进度回调刷当前行
        if (tpGlassLyric != null) tpGlassLyric.setText("");
        if (tpCdSummaryMain != null) tpCdSummaryMain.setText(title);
        if (tpCdSummarySub != null) tpCdSummarySub.setText(artist);
        paintLyricChip();
        paintCdLyricVerAlpha();
        paintCdUtilityStates();
        bindCovers(track);
        buildInfoContents(track);
        // 注：refreshQualityValue 内也刷信息面（音质行），两处调用不叠加重复行（buildInfoInto 先清自建行）
        if (lyricPageOpen) feedLyricPageTrack(track);
        if (tpShelfStage != null && track != null) {
            List<MusicTrack> queue = MusicController.get().queue();
            int idx = MusicController.get().index();
            String prevKey = idx > 0 && queue != null && idx - 1 < queue.size()
                    ? MusicQueue.trackKey(queue.get(idx - 1)) : null;
            String nextKey = queue != null && idx >= 0 && idx + 1 < queue.size()
                    ? MusicQueue.trackKey(queue.get(idx + 1)) : null;
            tpShelfStage.noteSlotKeys(prevKey, MusicQueue.trackKey(track), nextKey);
        }
    }

    private void resetProgressUi(long durationMs) {
        if (tpScrubber != null && !tpScrubber.isDragging()) tpScrubber.setFraction(0f);
        if (tpCdSeek != null && !tpCdSeek.isDragging()) tpCdSeek.setFraction(0f);
        if (tpShelfSeek != null && !tpShelfSeek.isDragging()) tpShelfSeek.setFraction(0f);
        setPositionText(0, durationMs);
    }

    private void setPositionText(long posMs, long durMs) {
        String pos = formatTime(posMs);
        String dur = formatTime(durMs);
        if (tpPositionTop != null) tpPositionTop.setText(pos);
        if (tpDurationTop != null) tpDurationTop.setText(dur);
        if (tpCdPosition != null) tpCdPosition.setText(pos);
        if (tpCdDuration != null) tpCdDuration.setText(dur);
        if (tpShelfPosition != null) tpShelfPosition.setText(pos);
        if (tpShelfDuration != null) tpShelfDuration.setText(dur);
    }

    private static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return min + ":" + (sec < 10 ? "0" : "") + sec;
    }

    /** 封面装配：自定义封面映射优先；同一份位图喂主题封面、氛围层、流体层、CD 盘、唱片架三槽。 */
    private void bindCovers(MusicTrack track) {
        final int seq = ++coverLoadSeq;
        if (track == null) {
            currentCoverBitmap = null;
            currentCoverUrl = "";
            applyCoverBitmap(null, seq);
            return;
        }
        String custom = null;
        try {
            custom = MusicStore.getSongCover(MusicStore.songCoverKey(track));
        } catch (Throwable ignored) {
        }
        final String url = custom != null && !custom.isEmpty() ? custom
                : track.cover == null ? "" : track.cover;
        currentCoverUrl = url;
        // 封面先清旧图（有地址加载中/失败保持透明；真没地址才上素图——封面占位按状态分开的定案）
        if (tpCover != null) {
            tpCover.setImageDrawable(null);
            if (url.isEmpty()) {
                tpCover.setImageDrawable(new CoverTextDrawable(track.displayTitle(), false, 0f));
            }
        }
        if (url.isEmpty()) {
            currentCoverBitmap = null;
            applyCoverBitmap(null, seq);
            loadShelfNeighborCovers(track, seq);
            return;
        }
        ImgUtil.load(url, new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(Bitmap resource, com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                if (destroyed || seq != coverLoadSeq) return;
                runOnUiThread(() -> {
                    if (destroyed || seq != coverLoadSeq) return;
                    currentCoverBitmap = resource;
                    coverBitmapKey = MusicQueue.trackKey(track);
                    applyCoverBitmap(resource, seq);
                });
            }

            @Override
            public void onLoadFailed(Drawable errorDrawable) {
                if (destroyed || seq != coverLoadSeq) return;
                runOnUiThread(() -> {
                    if (destroyed || seq != coverLoadSeq) return;
                    // 有地址但加载失败：封面保持透明（定案），氛围层回落默认色
                    currentCoverBitmap = null;
                    applyAmbientOnly(null);
                });
            }
        });
        loadShelfNeighborCovers(track, seq);
    }

    private void applyCoverBitmap(Bitmap bmp, int seq) {
        if (tpCover != null && bmp != null) tpCover.setImageBitmap(bmp);
        applyAmbientOnly(bmp);
        if (tpCdDisc != null && bmp != null) {
            tpCdDisc.setCoverBitmap(bmp, lastSkipDirection);
        }
        if (shelfTheme) feedShelfCovers(seq);
    }

    /** 氛围层喂入：取色→CoverAmbientView（内部 toneAdjust 归一）＋流体层基准色/软图联动。 */
    private void applyAmbientOnly(Bitmap bmp) {
        if (bmp != null && !bmp.isRecycled()) {
            ambientColor = CoverColor.dominantColor(bmp);
            cdToneBaseColor = CoverColor.cdToneColor(bmp);
        } else {
            ambientColor = CoverColor.FALLBACK_COLOR;
            cdToneBaseColor = CoverColor.FALLBACK_COLOR;
        }
        if (binding != null && binding.ivBgBlur != null) {
            binding.ivBgBlur.setAmbient(bmp, ambientColor);
        }
        if (tpCdDisc != null) tpCdDisc.setCoverTone(cdToneBaseColor);
        paintCdPalette();
        paintShelfPalette();
        applyFluidBg();
    }

    /** 相邻封面预载：唱片架三槽与封面卡横滑同步换图共用（位图先备好，换卡回调里才能同步换脸）。 */
    private void loadShelfNeighborCovers(MusicTrack track, int seq) {
        if (track == null || (!shelfTheme && !coverTheme)) return;
        List<MusicTrack> queue = MusicController.get().queue();
        int idx = MusicController.get().index();
        MusicTrack prev = queue != null && idx > 0 && idx - 1 < queue.size() ? queue.get(idx - 1) : null;
        MusicTrack next = queue != null && idx >= 0 && idx + 1 < queue.size() ? queue.get(idx + 1) : null;
        shelfBmpPrev = null;
        shelfBmpNext = null;
        coverBmpPrev = null;
        coverBmpNext = null;
        if (prev != null && prev.cover != null && !prev.cover.isEmpty()) {
            ImgUtil.load(prev.cover, new CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(Bitmap resource, com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                    runOnUiThread(() -> {
                        if (destroyed || seq != coverLoadSeq) return;
                        shelfBmpPrev = resource;
                        coverBmpPrev = resource;
                        feedShelfCovers(seq);
                    });
                }
            });
        }
        if (next != null && next.cover != null && !next.cover.isEmpty()) {
            ImgUtil.load(next.cover, new CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(Bitmap resource, com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                    runOnUiThread(() -> {
                        if (destroyed || seq != coverLoadSeq) return;
                        shelfBmpNext = resource;
                        coverBmpNext = resource;
                        feedShelfCovers(seq);
                    });
                }
            });
        }
        feedShelfCovers(seq);
    }

    /**
     * 封面卡横滑回调内的同步换脸（W-B 接缝决定）：构件已把旧图承进出场层并即刻开始
     * 350ms 换卡动画，前卡此刻必须已是新曲的脸——用预载相邻位图同步setImageBitmap，
     * 歌名/歌手同步换（切歌三面同步定案）；位图未备好时清图（前卡以 alpha 0 滑入，
     * 不许拿上一首的脸冒充新曲），随后 bindTrack 的异步装配会补齐同一份图。
     */
    private void syncSwapCoverFace(MusicTrack target, boolean next) {
        if (target == null) return;
        Bitmap bmp = next ? coverBmpNext : coverBmpPrev;
        if (tpCover != null) {
            if (bmp != null && !bmp.isRecycled()) tpCover.setImageBitmap(bmp);
            else tpCover.setImageDrawable(null);
        }
        if (tpTitle != null) tpTitle.setText(target.displayTitle());
        if (tpArtist != null) tpArtist.setText(target.displayArtist());
    }

    private void feedShelfCovers(int seq) {
        if (tpShelfStage == null || lastBoundTrack == null) return;
        // 舞台刚自转到本曲时先消费自转信号（consumeJustShifted）：视图内槽位已就位，
        // 本次回喂只补三槽内容（含最外侧新槽），不动布局；未命中才整表装配。
        tpShelfStage.consumeJustShifted(MusicQueue.trackKey(lastBoundTrack));
        List<MusicTrack> queue = MusicController.get().queue();
        int idx = MusicController.get().index();
        boolean hasPrev = queue != null && idx > 0;
        boolean hasNext = queue != null && idx >= 0 && idx + 1 < queue.size();
        // 当前槽位图只认属于本曲的那份（防切歌瞬间把上一首的脸喂进舞台）
        Bitmap curBmp = lastBoundKey.equals(coverBitmapKey) ? currentCoverBitmap : null;
        tpShelfStage.setCovers(shelfBmpPrev, curBmp, shelfBmpNext,
                hasPrev, hasNext, lastBoundTrack.displayTitle());
    }

    // ================= 收藏 =================

    private void refreshFavorite(MusicTrack track) {
        if (track == null) {
            currentFavorite = false;
            paintFavoriteIcons();
            return;
        }
        final String key = MusicQueue.trackKey(track);
        MusicLibrary.isFavorite(track, fav -> {
            if (destroyed) return;
            if (!key.equals(MusicQueue.trackKey(MusicController.get().current()))) return;
            currentFavorite = fav;
            paintFavoriteIcons();
        });
    }

    private void paintFavoriteIcons() {
        MusicTrack cur = MusicController.get().current();
        if (cur != null) {
            final String key = MusicQueue.trackKey(cur);
            MusicLibrary.isFavorite(cur, fav -> {
                if (destroyed) return;
                if (!key.equals(MusicQueue.trackKey(MusicController.get().current()))) return;
                currentFavorite = fav;
                paintFavoriteIconsNow();
            });
            return;
        }
        paintFavoriteIconsNow();
    }

    private void paintFavoriteIconsNow() {
        int favIcon = currentFavorite ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart;
        if (tpFavHeart != null) {
            tpFavHeart.setImageResource(favIcon);
            // 收藏小爱心 tint 300ms 颜色动画（对照表 §15.1 第 10 项 songMetaFavTint）
            int target = currentFavorite ? 0xFFFF2D55 : 0xFFFFFFFF;
            if (favTintAnimator != null) favTintAnimator.cancel();
            if (favTintColor == 0) {
                favTintColor = target;
                tpFavHeart.setColorFilter(target);
            } else {
                final int startColor = favTintColor;
                favTintAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), startColor, target);
                favTintAnimator.setDuration(300);
                favTintAnimator.addUpdateListener(a -> {
                    favTintColor = (int) a.getAnimatedValue();
                    if (tpFavHeart != null) tpFavHeart.setColorFilter(favTintColor);
                });
                favTintAnimator.start();
            }
        }
        if (tpGlassFav instanceof ImageView) {
            ((ImageView) tpGlassFav).setImageResource(favIcon);
            tpGlassFav.setAlpha(currentFavorite ? 1.0f : 0.85f);
        }
        if (tpAppleFav != null) tpAppleFav.setImageResource(favIcon);
        if (tpCdBtnFav instanceof ImageView) {
            ((ImageView) tpCdBtnFav).setImageResource(favIcon);
        }
    }

    private void toggleFavorite() {
        MusicTrack cur = MusicController.get().current();
        if (cur == null) return;
        boolean toFav = !currentFavorite;
        currentFavorite = toFav;
        paintFavoriteIconsNow();
        MusicLibrary.setFavorite(cur, toFav, () ->
                Notify.show(toFav ? R.string.music_favorited : R.string.music_unfavorited));
    }

    // ================= 播放态／进度 =================

    private void setPlaying(boolean playing) {
        int icon = playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play;
        if (tpPlayTop instanceof ImageView) ((ImageView) tpPlayTop).setImageResource(icon);
        if (tpGlassPlay instanceof ImageView) ((ImageView) tpGlassPlay).setImageResource(icon);
        if (tpCdPlay instanceof ImageView) ((ImageView) tpCdPlay).setImageResource(icon);
        if (tpShelfPlay instanceof ImageView) ((ImageView) tpShelfPlay).setImageResource(icon);
        if (tpLyricView != null) tpLyricView.setPlaying(playing);
        if (tpAppleLyric != null) tpAppleLyric.setPlaying(playing);
        if (tpCdLyric != null) tpCdLyric.setPlaying(playing);
        if (tpShelfLyric != null) tpShelfLyric.setPlaying(playing);
        if (tpCdDisc != null) tpCdDisc.setPlaying(playing);
        if (tpShelfStage != null) tpShelfStage.setPlaying(playing);
        if (lyricPageView != null) lyricPageView.setPlaying(playing);
        syncRhythmSource();
    }

    private void onProgressUi(long position, long duration) {
        if (destroyed) return;
        long dur = duration > 0 ? duration : MusicController.get().duration();
        if (tpScrubber != null && !tpScrubber.isDragging()) {
            tpScrubber.setFraction(dur > 0 ? (float) position / dur : 0f);
        }
        if (tpCdSeek != null && !tpCdSeek.isDragging()) {
            tpCdSeek.setFraction(dur > 0 ? (float) position / dur : 0f);
        }
        if (tpShelfSeek != null && !tpShelfSeek.isDragging()) {
            tpShelfSeek.setFraction(dur > 0 ? (float) position / dur : 0f);
        }
        setPositionText(position, dur);
        long shown = position + MusicPlayerSettings.getLyricOffsetMs();
        if (tpLyricView != null) tpLyricView.setPosition(shown);
        if (tpAppleLyric != null) tpAppleLyric.setPosition(shown);
        if (tpCdLyric != null) tpCdLyric.setPosition(shown);
        if (tpShelfLyric != null) tpShelfLyric.setPosition(shown);
        if (lyricPageOpen && lyricPageView != null) lyricPageView.setLyricPosition(shown);
        paintCurrentLineFaces();
    }

    /** 玻璃条单行歌词与 CD 摘要跟当前行走（当前行取内嵌面 currentIndex）。 */
    private void paintCurrentLineFaces() {
        int idx = -1;
        if (tpLyricView != null) idx = tpLyricView.currentIndex();
        else if (tpCdLyric != null) idx = tpCdLyric.currentIndex();
        else if (tpShelfLyric != null) idx = tpShelfLyric.currentIndex();
        if (idx < 0 || idx >= lyricMains.size()) return;
        String line = lyricMains.get(idx).getText();
        String sub = idx < lyricSubs.size() ? lyricSubs.get(idx) : null;
        if (tpGlassLyric != null) tpGlassLyric.setText(line == null ? "" : line);
        if (cdTheme && !cdExpanded) {
            if (tpCdSummaryMain != null) tpCdSummaryMain.setText(line == null ? "" : line);
            if (tpCdSummarySub != null) {
                String next = idx + 1 < lyricMains.size() ? lyricMains.get(idx + 1).getText() : null;
                tpCdSummarySub.setText(sub != null && !sub.isEmpty() ? sub : next == null ? "" : next);
            }
        }
    }

    /** 律动源（S4 §7.3）：setPlaying 与 bindTrack 两点调；主题重装重新注入新盘与流体层；销毁在 onDestroy release。 */
    private void syncRhythmSource() {
        if (destroyed) return;
        boolean playing = MusicController.get().isPlaying();
        MusicTrack cur = MusicController.get().current();
        if (!playing || cur == null) {
            if (rhythmSource != null) rhythmSource.stop();
            return;
        }
        if (rhythmSource == null) rhythmSource = new VisualizerRhythmSource(this);
        if (tpCdDisc != null) tpCdDisc.setRhythmSource(rhythmSource);
        if (binding != null && binding.fluidAmbient != null) {
            binding.fluidAmbient.setRhythmSource(rhythmSource);
        }
        rhythmSource.start(MusicController.get().audioSessionId());
    }

    // ================= 音质筹（他的定案：无损只编码名、有损编码＋真实码率、采样率永不显、测不到整筹隐藏） =================

    private String realQualityText() {
        MusicAudioInfo info = MusicController.get().audioInfo();
        if (info == null) return null;
        return info.describe();
    }

    private void refreshQualityValue() {
        String text = realQualityText();
        if (tpQualityValue != null) tpQualityValue.setText(text == null ? "" : text);
        animateChipVisibility(tpChipQuality, text != null);
        paintLyricChip();
        buildInfoContents(MusicController.get().current());
    }

    /** 双筹显隐 150ms 动画（对照表 §2 进度双筹行）。 */
    private void animateChipVisibility(View chip, boolean show) {
        if (chip == null) return;
        if (show) {
            if (chip.getVisibility() != View.VISIBLE) {
                chip.setAlpha(0f);
                chip.setVisibility(View.VISIBLE);
            }
            chip.animate().alpha(1f).setDuration(150).start();
        } else {
            chip.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                if (!destroyed) chip.setVisibility(View.GONE);
            }).start();
        }
    }

    private void paintLyricChip() {
        int count = currentLyricVersions.size();
        if (tpLyricChipText != null) {
            tpLyricChipText.setText(count > 1 ? "歌词 · " + count : "歌词");
        }
        animateChipVisibility(tpChipLyric, true);
        paintCdLyricVerAlpha();
    }

    /** CD 歌词版本键压暗：无版本（聚合未回/只有在用词未入列）时 alpha 0.35（对照表 §5 已修口径）。 */
    private void paintCdLyricVerAlpha() {
        if (tpCdBtnLyricVer != null) {
            tpCdBtnLyricVer.setAlpha(currentLyricVersions.size() > 1 ? 1.0f : 0.35f);
        }
    }

    /** CD 五图标行门控（§15.2 第 6 项）：信息/表面恒可用；音质/收藏/歌词版本以有当前曲为门。 */
    private void paintCdUtilityStates() {
        boolean hasTrack = MusicController.get().current() != null;
        setUtilityEnabled(tpCdBtnQuality, hasTrack);
        setUtilityEnabled(tpCdBtnFav, hasTrack);
        setUtilityEnabled(tpCdBtnLyricVer, hasTrack);
    }

    private void setUtilityEnabled(View view, boolean enabled) {
        if (view == null) return;
        view.setEnabled(enabled);
        if (view != tpCdBtnLyricVer) view.setAlpha(enabled ? 1.0f : 0.35f);
        else if (enabled) paintCdLyricVerAlpha();
        else view.setAlpha(0.35f);
    }

    private void paintCdModeIcon() {
        int mode = MusicController.get().getMode();
        int icon = mode == MusicQueue.MODE_REPEAT_ONE ? R.drawable.ic_music_repeat_one
                : mode == MusicQueue.MODE_SHUFFLE ? R.drawable.ic_music_shuffle
                : mode == MusicQueue.MODE_REPEAT_ALL ? R.drawable.ic_music_repeat
                : R.drawable.ic_music_sequential;
        if (tpCdBtnMode instanceof ImageView) ((ImageView) tpCdBtnMode).setImageResource(icon);
        if (tpShelfBtnMode instanceof ImageView) ((ImageView) tpShelfBtnMode).setImageResource(icon);
    }

    // ================= 密钥弹窗（S4 §6：只由 LxKeyIssue 两链触发，无密钥输入） =================

    private void showKeyIssue(String platform, String detail, Runnable onRetry) {
        if (isFinishing() || isDestroyed()) return;
        MusicTrack cur = MusicController.get().current();
        if (cur == null) return;
        if (!"lx".equals(platform)) return;
        try {
            LxKeyIssueDialog.show(this, platform, detail, onRetry);
        } catch (Throwable ignored) {
        }
    }

    // ================= 取词链（S4 §1.5/§4.6：页面只消费 lyricRichShared＋先行监听，守卫一条不许拆） =================

    private void loadLyric(MusicTrack track, boolean force) {
        if (track == null) return;
        final int seq = ++lyricLoadSeq;
        final String key = MusicQueue.trackKey(track);
        // 切歌先同步清五面旧词再异步取词——不许还挂着上一首的词
        clearLyricSurfaces();
        lyricEmpty = true;
        lyricLoading = true;
        currentLyric = null;
        currentLyricVersions.clear();
        currentLyricKey = "";
        paintLyricChip();
        // 先行监听先行上屏：链跑到第一份可用词就递来，与收口共用一条解析路（parseRichLyric）
        if (earlyLyricListener != null) {
            MusicRepository.clearEarlyLyricListener(track, earlyLyricListener);
        }
        earlyLyricListener = early -> {
            if (destroyed || early == null) return;
            App.post(() -> {
                if (destroyed || seq != lyricLoadSeq) return;
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !key.equals(MusicQueue.trackKey(cur))) return;
                if (currentLyric != null) return; // 收口已上屏，先行不再覆写
                applyLyricData(track, early);
            });
        };
        MusicRepository.setEarlyLyricListener(track, earlyLyricListener);
        Task.execute(() -> {
            MusicLyric result = null;
            LxKeyIssue keyIssue = null;
            try {
                result = MusicRepository.lyricRichShared(track, force);
            } catch (LxKeyIssue e) {
                keyIssue = e;
            } catch (Throwable ignored) {
            }
            final MusicLyric rich = result;
            final LxKeyIssue issue = keyIssue;
            App.post(() -> {
                // 收口守卫：代号＋trackKey＋destroyed 三处齐
                if (destroyed || seq != lyricLoadSeq) return;
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !key.equals(MusicQueue.trackKey(cur))) return;
                lyricLoading = false;
                if (rich != null && rich.hasLyric()) {
                    applyLyricData(track, rich);
                } else {
                    lyricEmpty = true;
                    feedLyricViews();
                }
                // 链 B（取词路）密钥问题：词的上屏/空态处理完成后再弹，重试＝强制重取词
                if (issue != null) {
                    showKeyIssue(issue.getPlatform(), issue.getMessage(), () -> loadLyric(track, true));
                }
                aggregateLyricVersions(track, seq);
            });
        });
    }

    /** 版本聚合：lyricRichAll 合并＋ensureCurrentLyricListed；回来后第三次验同一守卫才合并。 */
    private void aggregateLyricVersions(MusicTrack track, int seq) {
        final String key = MusicQueue.trackKey(track);
        Task.execute(() -> {
            List<LyricVersion> all = null;
            try {
                all = MusicRepository.lyricRichAll(track, false);
            } catch (Throwable ignored) {
            }
            final List<LyricVersion> versions = all;
            App.post(() -> {
                if (destroyed || seq != lyricLoadSeq) return;
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !key.equals(MusicQueue.trackKey(cur))) return;
                List<LyricVersion> merged = new ArrayList<>();
                if (versions != null) merged.addAll(versions);
                currentLyricVersions.clear();
                currentLyricVersions.addAll(ensureCurrentLyricListed(track, merged));
                paintLyricChip();
                paintCdLyricVerAlpha();
            });
        });
    }

    /** 在用词必入列（瓜子口径）：聚合里已有同文版本以聚合版为准，本词条只补缺失。 */
    private List<LyricVersion> ensureCurrentLyricListed(MusicTrack track, List<LyricVersion> versions) {
        List<LyricVersion> out = new ArrayList<>();
        if (versions != null) out.addAll(versions);
        if (currentLyric != null && currentLyric.hasLyric()) {
            String curKey = currentLyricKey;
            boolean listed = false;
            for (LyricVersion v : out) {
                if (v != null && v.getLyric() != null
                        && curKey.equals(MusicRepository.lyricVersionKey(v))) {
                    listed = true;
                    break;
                }
            }
            if (!listed) {
                LyricVersion displayed = MusicRepository.displayedVersion(currentLyric,
                        currentLyric.sourceName);
                if (displayed != null) out.add(0, displayed);
            }
        }
        return out;
    }

    /** rich 歌词解析收口：正文/译/音译三轨解析＋对齐＋主歌词换算，先行与收口共用这一条路。 */
    private void applyLyricData(MusicTrack track, MusicLyric rich) {
        currentLyric = rich;
        currentLyricKey = rich.rawLrc == null ? "" : rich.rawLrc.trim();
        parseRichLyric(rich);
        lyricEmpty = lyricMains.isEmpty();
        feedLyricViews();
        // 在用词先入列（版本数至少 1），聚合回来再合并
        List<LyricVersion> seed = ensureCurrentLyricListed(track, currentLyricVersions);
        currentLyricVersions.clear();
        currentLyricVersions.addAll(seed);
        paintLyricChip();
        paintCdLyricVerAlpha();
    }

    private void parseRichLyric(MusicLyric rich) {
        List<LyricsLine> mains = new ArrayList<>();
        List<String> subs = new ArrayList<>();
        if (rich != null && rich.rawLrc != null && !rich.rawLrc.trim().isEmpty()) {
            List<LyricsLine> original = LyricsParser.parseWordLyric(rich.rawLrc);
            List<LyricsLine> trans = rich.translation == null || rich.translation.trim().isEmpty()
                    ? Collections.emptyList() : LyricsParser.parseWordLyric(rich.translation);
            List<LyricsLine> roma = rich.romanization == null || rich.romanization.trim().isEmpty()
                    ? Collections.emptyList() : LyricsParser.parseWordLyric(rich.romanization);
            List<RichLyrics.Line> aligned = RichLyrics.align(original, trans, roma);
            int primary = MusicPlayerSettings.getLyricPrimary();
            boolean showTrans = MusicPlayerSettings.isShowTranslation();
            boolean showRoma = MusicPlayerSettings.isShowRomanization();
            for (RichLyrics.Line line : aligned) {
                if (line == null || line.main == null) continue;
                LyricsLine mainLine;
                String sub;
                if (primary == 1 && line.translation != null && !line.translation.isEmpty()) {
                    // 翻译为主：译行升主行（带其逐词），原文退副行
                    mainLine = findLineAt(trans, line.main.getTimeMs(), line.translation);
                    sub = line.main.getText();
                } else if (primary == 2 && line.romanization != null && !line.romanization.isEmpty()) {
                    mainLine = findLineAt(roma, line.main.getTimeMs(), line.romanization);
                    sub = line.main.getText();
                } else {
                    mainLine = line.main;
                    if (line.translationWords != null && !line.translationWords.isEmpty()) {
                        mainLine.setTranslationWords(line.translationWords);
                    }
                    if (line.romanizationWords != null && !line.romanizationWords.isEmpty()) {
                        mainLine.setRomanizationWords(line.romanizationWords);
                    }
                    sub = showTrans && line.translation != null ? line.translation
                            : showRoma && line.romanization != null ? line.romanization : null;
                }
                if (mainLine != null) {
                    mains.add(mainLine);
                    subs.add(sub);
                }
            }
        }
        lyricMains = mains;
        lyricSubs = subs;
    }

    private LyricsLine findLineAt(List<LyricsLine> lines, long timeMs, String text) {
        if (lines != null) {
            for (LyricsLine l : lines) {
                if (l != null && l.getTimeMs() == timeMs && text.equals(l.getText())) return l;
            }
            for (LyricsLine l : lines) {
                if (l != null && text.equals(l.getText())) return l;
            }
        }
        return new LyricsLine(timeMs, text);
    }

    private void clearLyricSurfaces() {
        lyricMains = Collections.emptyList();
        lyricSubs = Collections.emptyList();
        if (tpLyricView != null) tpLyricView.clearLines();
        if (tpAppleLyric != null) tpAppleLyric.clearLines();
        if (tpCdLyric != null) tpCdLyric.clearLines();
        if (tpShelfLyric != null) tpShelfLyric.clearLines();
        if (lyricPageView != null) {
            lyricPageView.setLyricData(lyricMains, lyricSubs, false);
        }
        if (tpGlassLyric != null) tpGlassLyric.setText("");
    }

    /** 五面喂词：内嵌（经典/封面卡）／Apple／CD 窗／唱片架／全屏页，口径一致。 */
    private void feedLyricViews() {
        boolean showSub = MusicPlayerSettings.isShowTranslation()
                || MusicPlayerSettings.isShowRomanization();
        String emptyText = lyricLoading ? getString(R.string.music_lyric_searching) : "暂无歌词";
        if (tpLyricView != null) {
            tpLyricView.setEmptyText(emptyText);
            tpLyricView.setShowSub(showSub);
            tpLyricView.setLines(lyricMains, lyricSubs);
        }
        if (tpAppleLyric != null) {
            tpAppleLyric.setEmptyText(emptyText);
            tpAppleLyric.setShowSub(showSub);
            tpAppleLyric.setLines(lyricMains, lyricSubs);
        }
        if (tpCdLyric != null) {
            tpCdLyric.setEmptyText(emptyText);
            tpCdLyric.setShowSub(showSub);
            tpCdLyric.setLines(lyricMains, lyricSubs);
        }
        if (tpShelfLyric != null) {
            tpShelfLyric.setEmptyText(emptyText);
            tpShelfLyric.setShowSub(showSub);
            tpShelfLyric.setLines(lyricMains, lyricSubs);
        }
        if (lyricPageView != null) {
            lyricPageView.setEmptyText(emptyText);
            lyricPageView.setLyricData(lyricMains, lyricSubs, showSub);
        }
        long shown = MusicController.get().position() + MusicPlayerSettings.getLyricOffsetMs();
        if (tpLyricView != null) tpLyricView.setPosition(shown);
        if (tpAppleLyric != null) tpAppleLyric.setPosition(shown);
        if (tpCdLyric != null) tpCdLyric.setPosition(shown);
        if (tpShelfLyric != null) tpShelfLyric.setPosition(shown);
    }

    /** 歌词样式下发（S4 §3 键表读侧全走 MusicPlayerSettings）：字号/模糊/字重/对齐/高级动效/调色到各面。 */
    private void applyLyricStyle() {
        float coverSize = MusicPlayerSettings.getCoverLyricFontSize();
        float fullSize = MusicPlayerSettings.getFullLyricFontSize();
        int blur = MusicPlayerSettings.getCoverLyricBlurLevel();
        int weight = MusicPlayerSettings.getLyricWeight();
        int align = MusicPlayerSettings.getLyricAlign();
        boolean fx = MusicPlayerSettings.isAdvancedFx();
        boolean center = coverTheme; // 封面卡展开歌词居中（对照表 §4），余面走设置对齐
        LyricWordEngine.LyricPalette dark = LyricWordEngine.LyricPalette.darkFallback();
        if (tpLyricView != null) {
            tpLyricView.setFontSizeSp(coverSize);
            tpLyricView.setBlurLevel(blur);
            tpLyricView.setAdvancedFx(fx);
            tpLyricView.setPalette(dark);
            tpLyricView.setLyricWeight(weight);
            tpLyricView.setAlignCenter(center);
            if (!center) tpLyricView.setTextAlign(align);
        }
        if (tpAppleLyric != null) {
            tpAppleLyric.setFontSizeSp(fullSize);
            tpAppleLyric.setBlurLevel(blur);
            tpAppleLyric.setAdvancedFx(fx);
            tpAppleLyric.setPalette(dark);
            tpAppleLyric.setLyricWeight(weight);
            tpAppleLyric.setTextAlign(align);
            tpAppleLyric.setFadeColor(CoverColor.toneAdjust(ambientColor));
        }
        if (tpCdLyric != null) {
            tpCdLyric.setLyricWeight(weight);
            tpCdLyric.setInkColor(cdInkColor());
        }
        if (tpShelfLyric != null) {
            tpShelfLyric.setFontSizeSp(coverSize);
            tpShelfLyric.setBlurLevel(blur);
            tpShelfLyric.setLyricWeight(weight);
            tpShelfLyric.setTextAlign(align);
            paintShelfPalette();
        }
        if (lyricPageView != null) {
            lyricPageView.applyLyricStyle(fullSize, blur, fx, weight,
                    MusicPlayerSettings.isShowTranslation(), MusicPlayerSettings.isShowRomanization());
            if (lyricPageView.getLyricView() != null) {
                lyricPageView.getLyricView().setTextAlign(align);
                lyricPageView.getLyricView().setPalette(dark);
                lyricPageView.getLyricView().setFadeColor(CoverColor.toneAdjust(ambientColor));
            }
        }
    }

    private void paintShelfPalette() {
        if (tpShelfLyric == null) return;
        int h0 = mixColor(cdToneBaseColor, 0.65f, 0xFF252522);
        int inactive = Color.argb(Math.round(0.48f * 255), Color.red(h0), Color.green(h0), Color.blue(h0));
        tpShelfLyric.setPaletteColors(h0, inactive);
    }

    private static int mixColor(int from, float mix, int to) {
        float keep = 1f - mix;
        return Color.argb(255,
                Math.round(Color.red(from) * keep + Color.red(to) * mix),
                Math.round(Color.green(from) * keep + Color.green(to) * mix),
                Math.round(Color.blue(from) * keep + Color.blue(to) * mix));
    }

    // ================= MusicPlayerHost 三方法（S4 §4.0，签名逐字） =================

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
        MusicTrack cur = MusicController.get().current();
        if (cur == null || isFinishing() || isDestroyed()) return;
        try {
            MusicLyricVersionDialog.showWithSearch(this, cur,
                    new ArrayList<>(currentLyricVersions), currentLyricKey,
                    this::applyLyricVersion, this::searchLyric);
        } catch (Throwable ignored) {
        }
    }

    /** 手动选版：先写盘压住自动升级，再只换词数据不换曲（S4 §4.6）。 */
    private void applyLyricVersion(LyricVersion version) {
        MusicTrack cur = MusicController.get().current();
        if (cur == null || version == null || version.getLyric() == null) return;
        MusicRepository.saveManualLyricChoice(cur, version);
        applyVersionData(cur, version, false);
    }

    private void applyVersionData(MusicTrack track, LyricVersion version, boolean cachePick) {
        final String key = MusicQueue.trackKey(track);
        final int seq = lyricLoadSeq;
        Task.execute(() -> {
            MusicLyric lyric = version.getLyric();
            App.post(() -> {
                // 上屏守卫：trackKey＋destroyed（＋代号未被新一轮取词顶掉）
                if (destroyed || seq != lyricLoadSeq) return;
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !key.equals(MusicQueue.trackKey(cur))) return;
                if (lyric != null && lyric.hasLyric()) {
                    lyricLoading = false;
                    applyLyricData(track, lyric);
                }
            });
        });
    }

    private void searchLyric() {
        MusicTrack cur = MusicController.get().current();
        if (cur != null) loadLyric(cur, true);
    }

    // ================= 面板入口（S4 §4，逐主题对位见各 wire* 接线点） =================

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
                feedLyricViews();
            }

            @Override
            public void onSearchLyric() {
                searchLyric();
            }

            @Override
            public void onAddToPlaylist() {
                MusicTrack cur = MusicController.get().current();
                if (cur != null) pickPlaylist(cur);
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

    private void showQualityDialog() {
        if (isFinishing() || isDestroyed()) return;
        MusicTrack cur = MusicController.get().current();
        if (cur == null) return;
        try {
            MusicQualityPanelDialog.show(this, cur, MusicController.get().getQuality(), quality -> {
                if (quality == MusicController.get().getQuality()) return;
                MusicController.get().setQuality(quality);
                MusicController.get().reloadQuality();
                refreshQualityValue();
                Notify.show("已切换" + MusicStore.qualityName(quality));
            });
        } catch (Throwable ignored) {
        }
    }

    private void downloadCurrent() {
        MusicTrack cur = MusicController.get().current();
        if (cur == null) return;
        if (MusicDownloadStore.isLocal(cur)) {
            Notify.show(R.string.music_downloaded);
            return;
        }
        MusicDownloadStore.downloadWithQuality(cur, MusicStore.getDownloadQuality());
        Notify.show("已加入下载");
    }

    private void pickPlaylist(MusicTrack track) {
        MusicLibrary.playlists(list -> {
            if (destroyed) return;
            List<String> names = new ArrayList<>();
            for (MusicPlaylist p : list) names.add(p.getName());
            names.add(getString(R.string.music_new_playlist));
            MusicOptionsDialog.show(this, getString(R.string.music_add_to_playlist), names, -1, idx -> {
                if (idx < list.size()) {
                    MusicLibrary.addToPlaylist(list.get(idx).getId(), track, added ->
                            Notify.show(added ? R.string.music_added : R.string.music_already_in));
                } else {
                    inputPlaylistName(name -> MusicLibrary.createPlaylist(name, p ->
                            MusicLibrary.addToPlaylist(p.getId(), track, added ->
                                    Notify.show(added ? R.string.music_added : R.string.music_already_in))));
                }
            });
        });
    }

    private void inputPlaylistName(MusicLibrary.DataCallback<String> cb) {
        // 与各列表页同款自建深色弹窗（弹窗不用白色底是定案，不用系统 AlertDialog）
        android.app.Dialog dialog = new android.app.Dialog(this);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(16));
        root.setBackgroundResource(R.drawable.bg_music_glass_panel);
        TextView title = new TextView(this);
        title.setText(R.string.music_new_playlist);
        title.setTextSize(16);
        title.setTextColor(0xFFFFFFFF);
        root.addView(title);
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("歌单名");
        input.setHintTextColor(0x99FFFFFF);
        input.setTextColor(0xFFFFFFFF);
        input.setSingleLine(true);
        android.widget.LinearLayout.LayoutParams inputLp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(12);
        root.addView(input, inputLp);
        TextView ok = new TextView(this);
        ok.setText("创建");
        ok.setTextSize(14);
        ok.setTextColor(0xFFFFFFFF);
        ok.setGravity(android.view.Gravity.CENTER);
        ok.setPadding(0, dp(14), 0, 0);
        ok.setOnClickListener(v -> {
            String name = input.getText() == null ? "" : input.getText().toString().trim();
            dialog.dismiss();
            if (!name.isEmpty()) cb.onResult(name);
        });
        root.addView(ok);
        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        }
        dialog.show();
    }

    // ================= 封面卡片展开/信息两态 =================

    private void setCoverExpanded(boolean expanded) {
        coverExpanded = expanded;
        if (!expanded) coverInfoMode = false;
        paintCoverExpanded();
    }

    private void setCoverInfoMode(boolean info) {
        coverInfoMode = info;
        if (info && !coverExpanded) coverExpanded = true;
        paintCoverExpanded();
    }

    private void paintCoverExpanded() {
        if (tpExpandedCol != null) {
            tpExpandedCol.setVisibility(coverExpanded ? View.VISIBLE : View.GONE);
        }
        if (tpInfoScroll != null) {
            tpInfoScroll.setVisibility(coverExpanded && coverInfoMode ? View.VISIBLE : View.GONE);
        }
        if (tpLyricView != null && coverTheme) {
            tpLyricView.setVisibility(coverExpanded && !coverInfoMode ? View.VISIBLE : View.GONE);
        }
        // 玻璃条两态：默认态＝收藏/单行歌词/播放；展开态＝「返回到封面模式」文案＋保留收藏/播放键
        if (tpGlassLyric != null) tpGlassLyric.setVisibility(coverExpanded ? View.GONE : View.VISIBLE);
        if (tpGlassBack != null) tpGlassBack.setVisibility(coverExpanded ? View.VISIBLE : View.GONE);
        if (tpCoverStack != null) tpCoverStack.setVisibility(coverExpanded ? View.GONE : View.VISIBLE);
        if (tpCoverArea != null && !coverExpanded) tpCoverArea.setVisibility(View.VISIBLE);
    }

    // ================= CD 调色/展开/信息 =================

    private int cdInkColor() {
        return cdDarkSurface ? 0xFFF2F2F7 : 0xFF282D36;
    }

    private int cdMutedColor() {
        return cdDarkSurface ? 0xFF8E96A3 : 0xFF747D89;
    }

    /** CD 背景与文字调色：四段渐变停点 0/0.34/0.68/1 取色链色（freshTone 混 0.22 口径），表面基色照 CdPalette 常量。 */
    private void paintCdPalette() {
        if (!cdTheme) return;
        int base = cdDarkSurface ? 0xFF14161C : 0xFFF4F2EE;
        int mist = cdDarkSurface ? 0xFF14161C : 0xFFE9EFF5;
        int ink = cdInkColor();
        int muted = cdMutedColor();
        if (tpCdBackdrop != null) {
            int fresh = mixColor(cdToneBaseColor, 0.22f, base);
            int mid = mixColor(fresh, 0.45f, mist);
            int low = mixColor(mid, 0.5f, base);
            GradientDrawable gradient = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{fresh, mid, low, base});
            tpCdBackdrop.setBackground(gradient);
        }
        if (tpTitle != null) tpTitle.setTextColor(ink);
        if (tpArtist != null) tpArtist.setTextColor(muted);
        if (tpCdSummaryMain != null) tpCdSummaryMain.setTextColor(ink);
        if (tpCdSummarySub != null) {
            tpCdSummarySub.setTextColor(mixColor(ink, 0.58f, base));
        }
        if (tpCdPosition != null) tpCdPosition.setTextColor(mixColor(muted, 0.26f, base));
        if (tpCdDuration != null) tpCdDuration.setTextColor(mixColor(muted, 0.26f, base));
        if (tpCdSeek != null) {
            tpCdSeek.setColors(Color.argb(Math.round(0.10f * 255),
                    Color.red(ink), Color.green(ink), Color.blue(ink)), ink);
        }
        if (tpCdLyric != null) tpCdLyric.setInkColor(ink);
        if (tpCdDisc != null) tpCdDisc.setDarkSurface(cdDarkSurface);
    }

    private void setCdExpanded(boolean expanded) {
        if (cdCollapseAnimator != null) cdCollapseAnimator.cancel();
        cdExpanded = expanded;
        float from = cdCollapseValue;
        float to = expanded ? 1f : 0f;
        cdCollapseAnimator = ValueAnimator.ofFloat(from, to);
        cdCollapseAnimator.setDuration(expanded ? 600 : 500);
        cdCollapseAnimator.addUpdateListener(a -> {
            cdCollapseValue = (float) a.getAnimatedValue();
            applyCdCollapse(cdCollapseValue);
        });
        cdCollapseAnimator.start();
        paintCdDetailMode();
    }

    /** 展开量 c 的三路 alpha/位移（对照表 §5 全式）：盘下整列 clamp(1−1.6c)、展开内容 clamp((c−0.15)/0.85)、顶行 clamp(1−1.4c)＋上移 20dp×c。 */
    private void applyCdCollapse(float c) {
        if (tpCdDisc != null) tpCdDisc.setCollapseFraction(c);
        if (tpCdBelowCol != null) {
            tpCdBelowCol.setAlpha(Math.max(0f, Math.min(1f, 1f - 1.6f * c)));
        }
        if (tpCdDetail != null) {
            tpCdDetail.setAlpha(Math.max(0f, Math.min(1f, (c - 0.15f) / 0.85f)));
            tpCdDetail.setVisibility(c > 0.01f ? View.VISIBLE : View.GONE);
        }
        float topAlpha = Math.max(0f, Math.min(1f, 1f - 1.4f * c));
        float topShift = -dp(20) * c;
        if (tpTitle != null) {
            tpTitle.setAlpha(topAlpha);
            tpTitle.setTranslationY(topShift);
        }
        if (tpArtist != null) {
            tpArtist.setAlpha(topAlpha);
            tpArtist.setTranslationY(topShift);
        }
        if (tpBtnClose != null) tpBtnClose.setAlpha(topAlpha);
        if (tpBtnTools != null) tpBtnTools.setAlpha(topAlpha);
    }

    private void paintCdDetailMode() {
        if (tpCdLyric != null) {
            tpCdLyric.setVisibility(cdInfoMode ? View.GONE : View.VISIBLE);
        }
        if (tpInfoScroll != null && cdTheme) {
            tpInfoScroll.setVisibility(cdInfoMode && cdExpanded ? View.VISIBLE : View.GONE);
        }
    }

    // ================= 信息面内容（Apple 信息页／CD 信息窗／封面卡信息卡共用行构建） =================

    private void buildInfoContents(MusicTrack track) {
        buildInfoInto(tpInfoContent, track);
    }

    private void buildInfoInto(ViewGroup container, MusicTrack track) {
        if (container == null) return;
        // 只重建本宿主构建的行（tag 标记），不碰布局自带子视图
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View child = container.getChildAt(i);
            if (Boolean.TRUE.equals(child.getTag())) container.removeViewAt(i);
        }
        if (track == null) return;
        String quality = realQualityText();
        addInfoRow(container, "歌名", track.displayTitle());
        addInfoRow(container, "歌手", track.displayArtist());
        addInfoRow(container, "专辑", track.album == null || track.album.isEmpty() ? "—" : track.album);
        addInfoRow(container, "来源", track.platform == null ? "—" : track.platform);
        addInfoRow(container, "音质", quality == null ? "—" : quality);
        addInfoRow(container, "时长", track.duration > 0 ? formatTime(track.duration) : "—");
    }

    private void addInfoRow(ViewGroup container, String label, String value) {
        TextView row = new TextView(this);
        row.setTag(Boolean.TRUE);
        row.setText(label + "  " + value);
        row.setTextSize(13);
        row.setTextColor(0xF2FFFFFF);
        row.setPadding(0, dp(4), 0, dp(4));
        container.addView(row);
    }

    // ================= 封面手势（点按/双击/长按互斥，S4 §2.6＋对照表 §1） =================

    private void attachCoverGestures(View target, boolean coverCardTap) {
        attachCoverGestures(target, coverCardTap, false);
    }

    /**
     * 经典：点按＝进全屏歌词页、双击＝收藏＋爱心爆发、长按＝工具面板＋震动类型 0。
     * 封面卡片：点按＝切展开/信息两态、双击/长按同经典。
     * Apple（longPressOnly）：无点按/双击，只长按震动＋工具面板。
     * 竖滑识别不在封面本体（页级容器管），本层不拦截竖向移动。
     */
    private void attachCoverGestures(View target, boolean coverCardTap, boolean longPressOnly) {
        if (target == null) return;
        final View anchor = target;
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                vibrate(VibrationEffect.EFFECT_TICK);
                showToolsSheet();
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (longPressOnly) return true;
                if (coverCardTap) setCoverExpanded(!coverExpanded);
                else showLyricPage();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (longPressOnly) return true;
                onCoverDoubleTap(anchor, e);
                return true;
            }
        });
        target.setOnTouchListener((v, event) -> detector.onTouchEvent(event));
    }

    private void vibrate(int effectId) {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && android.os.Build.VERSION.SDK_INT >= 29) {
                vibrator.vibrate(VibrationEffect.createPredefined(effectId));
            } else if (vibrator != null) {
                vibrator.vibrate(30);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 双击：先切收藏态（颗数按切换后状态：收藏 8 颗/取消 5 颗），再爱心爆发＋涟漪＋封面缩放。 */
    private void onCoverDoubleTap(View anchor, MotionEvent e) {
        MusicTrack cur = MusicController.get().current();
        if (cur == null) return;
        toggleFavorite();
        spawnHearts(anchor, currentFavorite);
        startFavRipple(anchor, e);
        pulseCoverScale();
    }

    private void startFavRipple(View anchor, MotionEvent e) {
        if (favRippleView == null) {
            favRippleView = new FavRippleView(this);
            if (anchor instanceof ViewGroup) {
                ((ViewGroup) anchor).addView(favRippleView, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }
        if (favRippleView != null) {
            favRippleView.bringToFront();
            favRippleView.startRipple(e.getX(), e.getY());
        }
    }

    /** 爱心粒子全式（对照表 §1）：角度 随机×120−60°、飞行 随机×48+42dp、尺寸 随机×10+9dp、
     * 旋转 随机×50−25°、延迟 随机×0.08s＋序×0.035s、驻留 750ms。 */
    private void spawnHearts(View anchor, boolean favorited) {
        if (binding == null || binding.heartsOverlay == null || anchor == null) return;
        int count = favorited ? 8 : 5;
        int[] anchorPos = new int[2];
        int[] overlayPos = new int[2];
        anchor.getLocationInWindow(anchorPos);
        binding.heartsOverlay.getLocationInWindow(overlayPos);
        float cx = anchorPos[0] - overlayPos[0] + anchor.getWidth() / 2f;
        float cy = anchorPos[1] - overlayPos[1] + anchor.getHeight() / 2f;
        for (int i = 0; i < count; i++) {
            final int order = i;
            ImageView heart = new ImageView(this);
            heart.setImageResource(R.drawable.ic_music_heart_fill);
            heart.setColorFilter(0xFFFF2D55);
            int sizeDp = Math.round(heartRandom.nextFloat() * 10f + 9f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
            binding.heartsOverlay.addView(heart, lp);
            heart.setX(cx - dp(sizeDp) / 2f);
            heart.setY(cy - dp(sizeDp) / 2f);
            double angleRad = Math.toRadians(heartRandom.nextFloat() * 120f - 60f);
            float distDp = heartRandom.nextFloat() * 48f + 42f;
            float dx = (float) (Math.sin(angleRad) * distDp);
            float dy = (float) (-Math.cos(angleRad) * distDp);
            float rotation = heartRandom.nextFloat() * 50f - 25f;
            long delayMs = Math.round(heartRandom.nextFloat() * 80) + order * 35L;
            final float baseX = cx - dp(sizeDp) / 2f;
            final float baseY = cy - dp(sizeDp) / 2f;
            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(750);
            anim.setStartDelay(delayMs);
            anim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                heart.setX(baseX + dp(dx) * f);
                heart.setY(baseY + dp(dy) * f);
                heart.setRotation(rotation * f);
                heart.setAlpha(f < 0.7f ? 1f : 1f - (f - 0.7f) / 0.3f);
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    binding.heartsOverlay.removeView(heart);
                    heartAnimators.remove(animation);
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    binding.heartsOverlay.removeView(heart);
                    heartAnimators.remove(animation);
                }
            });
            heartAnimators.add(anim);
            anim.start();
        }
    }

    /** 封面缩放：1.045/200ms 涨起，随后 spring（阻尼 0.9）回落到 1.0；帧回调在 onDestroy 确定性摘除。 */
    private void pulseCoverScale() {
        final View cover = tpCoverWrap != null ? tpCoverWrap : tpCover;
        if (cover == null) return;
        if (coverScaleAnimator != null) coverScaleAnimator.cancel();
        cancelCoverScaleSpring();
        coverScaleAnimator = ValueAnimator.ofFloat(cover.getScaleX(), 1.045f);
        coverScaleAnimator.setDuration(200);
        coverScaleAnimator.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            cover.setScaleX(v);
            cover.setScaleY(v);
        });
        coverScaleAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                startCoverScaleSpring(cover);
            }
        });
        coverScaleAnimator.start();
    }

    private void startCoverScaleSpring(View cover) {
        if (coverScaleChoreographer == null) coverScaleChoreographer = Choreographer.getInstance();
        coverScaleVelocity = 0f;
        coverScaleFrame = new Choreographer.FrameCallback() {
            private long lastNanos;

            @Override
            public void doFrame(long frameTimeNanos) {
                if (destroyed) return;
                float dt = lastNanos == 0 ? 0.016f
                        : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1_000_000_000f);
                lastNanos = frameTimeNanos;
                float x = cover.getScaleX();
                // 弹簧：刚度 300、阻尼比 0.9（回落 spring 0.9/300 口径）
                float stiffness = 300f;
                float damping = 2f * 0.9f * (float) Math.sqrt(stiffness);
                float accel = -stiffness * (x - 1f) - damping * coverScaleVelocity;
                coverScaleVelocity += accel * dt;
                x += coverScaleVelocity * dt;
                cover.setScaleX(x);
                cover.setScaleY(x);
                if (Math.abs(x - 1f) < 0.001f && Math.abs(coverScaleVelocity) < 0.01f) {
                    cover.setScaleX(1f);
                    cover.setScaleY(1f);
                    coverScaleFrame = null;
                    return;
                }
                coverScaleChoreographer.postFrameCallback(this);
            }
        };
        coverScaleChoreographer.postFrameCallback(coverScaleFrame);
    }

    private void cancelCoverScaleSpring() {
        if (coverScaleChoreographer != null && coverScaleFrame != null) {
            coverScaleChoreographer.removeFrameCallback(coverScaleFrame);
        }
        coverScaleFrame = null;
    }

    // ================= 全屏歌词页（右划跟手开页、左划跟手关；S4 §2.6/§7.1） =================

    private void setupLyricPage() {
        if (binding == null || binding.lyricPage == null) return;
        lyricPageView = (MusicLyricPageView) binding.lyricPage.getRoot();
        if (lyricPageView == null) return;
        lyricPageView.setVisibility(View.GONE);
        lyricPageView.setOnLineClickListener((index, timeMs) -> MusicController.get().seekTo(timeMs));
        lyricPageView.setOnTrClickListener(v -> openLyricVersionDialog());
        lyricPageView.setOnPauseClickListener(v -> MusicController.get().toggle());
    }

    private void feedLyricPageTrack(MusicTrack track) {
        if (lyricPageView == null || track == null) return;
        lyricPageView.setToneColor(CoverColor.toneAdjust(ambientColor));
        String artist = track.displayArtist();
        String album = track.album == null ? "" : track.album;
        // 歌手行构成：「歌手 ♡ 专辑」（无专辑只显歌手，对照表 §7.3）
        String artistLine = album.isEmpty() ? artist : artist + " ♡ " + album;
        lyricPageView.setTrackInfo(track.displayTitle(), artistLine);
        lyricPageView.setCover(currentCoverUrl, track.displayTitle());
        if (currentCoverBitmap != null && !currentCoverBitmap.isRecycled()) {
            lyricPageView.setCoverDrawable(new android.graphics.drawable.BitmapDrawable(
                    getResources(), currentCoverBitmap));
        }
        lyricPageView.setPlaying(MusicController.get().isPlaying());
    }

    /** 开页喂页：曲目信息/封面/风格/位置一并下发（开页不改写翻译/音译存值，§13.4 第 5 项待裁口径不落）。 */
    private void prepareLyricPage() {
        MusicTrack cur = MusicController.get().current();
        if (cur != null) feedLyricPageTrack(cur);
        applyLyricStyle();
        feedLyricViews();
        if (lyricPageView != null) {
            lyricPageView.setLyricPosition(MusicController.get().position()
                    + MusicPlayerSettings.getLyricOffsetMs());
        }
    }

    private void showLyricPage() {
        if (lyricPageView == null || lyricPageOpen) return;
        prepareLyricPage();
        lyricPageOpen = true;
        float w = pageWidth();
        lyricPageView.setTranslationX(-w);
        if (binding != null && binding.themeContainer != null) {
            binding.themeContainer.setTranslationX(0f);
        }
        lyricPageView.setVisibility(View.VISIBLE);
        animateLyricSettle(-w, 0f, w);
    }

    private void hideLyricPage() {
        if (lyricPageView == null || !lyricPageOpen) return;
        lyricPageOpen = false;
        float w = lyricPageView.getWidth() > 0 ? lyricPageView.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        animateLyricSettle(lyricPageView.getTranslationX(), -w, w);
    }

    /** 开/关收尾动画：页自左入/出，themeContainer 做 translationX 视差（页位移的 0.3 倍）。 */
    private void animateLyricSettle(float from, float to, float pageWidth) {
        cancelLyricSettle();
        final boolean opening = to == 0f;
        lyricSettleAnimator = ValueAnimator.ofFloat(from, to);
        lyricSettleAnimator.setDuration(260);
        lyricSettleAnimator.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
        lyricSettleAnimator.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            applyLyricSwipeTx(v, pageWidth);
        });
        lyricSettleAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!opening && lyricPageView != null) {
                    lyricPageView.setVisibility(View.GONE);
                    lyricPageView.setTranslationX(0f);
                    if (binding != null && binding.themeContainer != null) {
                        binding.themeContainer.setTranslationX(0f);
                    }
                }
            }
        });
        lyricSettleAnimator.start();
    }

    private void applyLyricSwipeTx(float pageTx, float pageWidth) {
        if (lyricPageView != null) lyricPageView.setTranslationX(pageTx);
        if (binding != null && binding.themeContainer != null && pageWidth > 0) {
            float progress = 1f + pageTx / pageWidth; // 0 关 → 1 开
            binding.themeContainer.setTranslationX(pageWidth * 0.3f * Math.max(0f, Math.min(1f, progress)));
        }
    }

    private void cancelLyricSettle() {
        if (lyricSettleAnimator != null) {
            lyricSettleAnimator.cancel();
            lyricSettleAnimator = null;
        }
    }

    /**
     * 横滑观察层（只观察、不拦截）：歌词面区域起手的右划跟手开页（页自左入）、
     * 开页后左划跟手关。经典封面点按进页与本层并存；竖向手势一律放行给页级竖滑/歌词滚动。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        try {
            trackLyricSwipe(event);
        } catch (Throwable ignored) {
        }
        return super.dispatchTouchEvent(event);
    }

    private void trackLyricSwipe(MotionEvent event) {
        if (lyricPageView == null || destroyed) return;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            lyricSwipeDownX = event.getX();
            lyricSwipeDownY = event.getY();
            lyricSwipeDx = 0f;
            lyricSwipeEngaged = false;
            lyricSwipeTracking = lyricPageOpen || isInLyricRegion(event.getX(), event.getY());
            if (lyricSwipeTracking) {
                cancelLyricSettle();
                if (swipeVelocityTracker == null) swipeVelocityTracker = VelocityTracker.obtain();
                else swipeVelocityTracker.clear();
                swipeVelocityTracker.addMovement(event);
                if (!lyricPageOpen) {
                    prepareLyricPage();
                    lyricPageView.setVisibility(View.VISIBLE);
                    float w = pageWidth();
                    lyricPageView.setTranslationX(-w);
                    if (binding.themeContainer != null) binding.themeContainer.setTranslationX(0f);
                }
            }
            return;
        }
        if (!lyricSwipeTracking) return;
        if (swipeVelocityTracker != null) swipeVelocityTracker.addMovement(event);
        float w = pageWidth();
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - lyricSwipeDownX;
            float dy = event.getY() - lyricSwipeDownY;
            if (!lyricSwipeEngaged) {
                if (Math.abs(dx) > dp(24) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    lyricSwipeEngaged = true;
                } else if (Math.abs(dy) > dp(24)) {
                    lyricSwipeTracking = false;
                    if (!lyricPageOpen) settleLyricPageBack(w);
                    return;
                }
            }
            if (lyricSwipeEngaged) {
                lyricSwipeDx = dx;
                if (!lyricPageOpen) {
                    // 右划开页：页从左边跟手入
                    float tx = Math.max(-w, Math.min(0f, -w + Math.max(0f, dx)));
                    applyLyricSwipeTx(tx, w);
                } else {
                    // 左划关页：页跟手向左出
                    float tx = Math.max(-w, Math.min(0f, Math.min(0f, dx)));
                    applyLyricSwipeTx(tx, w);
                }
            }
            return;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            float velocityX = 0f;
            if (swipeVelocityTracker != null) {
                swipeVelocityTracker.computeCurrentVelocity(1000);
                velocityX = swipeVelocityTracker.getXVelocity();
                swipeVelocityTracker.recycle();
                swipeVelocityTracker = null;
            }
            boolean wasTracking = lyricSwipeTracking;
            lyricSwipeTracking = false;
            if (!wasTracking || !lyricSwipeEngaged) {
                if (!lyricPageOpen) settleLyricPageBack(w);
                lyricSwipeEngaged = false;
                return;
            }
            lyricSwipeEngaged = false;
            if (!lyricPageOpen) {
                boolean open = lyricSwipeDx > w * 0.25f || velocityX > 600f;
                if (open) {
                    lyricPageOpen = true;
                    animateLyricSettle(lyricPageView.getTranslationX(), 0f, w);
                } else {
                    settleLyricPageBack(w);
                }
            } else {
                boolean close = lyricSwipeDx < -w * 0.25f || velocityX < -600f;
                if (close) {
                    lyricPageOpen = false;
                    animateLyricSettle(lyricPageView.getTranslationX(), -w, w);
                } else {
                    animateLyricSettle(lyricPageView.getTranslationX(), 0f, w);
                }
            }
        }
    }

    private void settleLyricPageBack(float w) {
        if (lyricPageView == null) return;
        animateLyricSettle(lyricPageView.getTranslationX(), -w, w);
    }

    private float pageWidth() {
        if (lyricPageView != null && lyricPageView.getWidth() > 0) return lyricPageView.getWidth();
        return getResources().getDisplayMetrics().widthPixels;
    }

    /** 起手点是否落在歌词面区域（内嵌词面/CD 词窗/架词面/已开的歌词页）。
     * Apple 除外：其歌词面是 pager 第三页，横划归 pager 本体，宿主观察层不抢。 */
    private boolean isInLyricRegion(float x, float y) {
        if (lyricPageOpen) return true;
        if (appleTheme) return false;
        View[] regions = {tpLyricView, tpCdLyric, tpShelfLyric};
        int[] loc = new int[2];
        for (View region : regions) {
            if (region == null || region.getVisibility() != View.VISIBLE) continue;
            region.getLocationInWindow(loc);
            if (x >= loc[0] && x <= loc[0] + region.getWidth()
                    && y >= loc[1] && y <= loc[1] + region.getHeight()) {
                return true;
            }
        }
        return false;
    }
}
