package com.fongmi.android.tv.ui.activity;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.ActivityMusicPlayerBinding;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.KaraokeLyricsView;
import com.fongmi.android.tv.ui.dialog.MusicFxDialog;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.ui.dialog.MusicQueueDialog;
import com.fongmi.android.tv.ui.dialog.MusicToolsSheetDialog;
import com.fongmi.android.tv.utils.CoverColor;
import com.fongmi.android.tv.utils.CoverTextDrawable;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 音乐播放页：全主题共用骨架（封面糊底背景＋氛围色罩＋主题容器＋爱心粒子层），
 * 主题布局按 MusicStore.getPlayerTheme() 装进容器：classic / apple_music /
 * cover_immersive / luminous_cd / record_shelf。播放只走 MusicController 接口。
 */
public class MusicPlayerActivity extends BaseActivity {

    public static final String THEME_CLASSIC = "classic";
    public static final String THEME_APPLE = "apple_music";
    public static final String THEME_IMMERSIVE = "cover_immersive";
    public static final String THEME_CD = "luminous_cd";
    public static final String THEME_SHELF = "record_shelf";

    private ActivityMusicPlayerBinding binding;

    // ===== 当前主题视图引用（applyTheme 时按 id 找回，缺失的主题留 null） =====
    private View themeRoot;
    private String themeId = THEME_CLASSIC;
    private ImageView coverView;
    private ImageView coverExitView;
    /** 出场层代次：快速连续切歌时，旧出场动画的收尾只在代次未变时才隐藏出场层。 */
    private int coverExitGen;
    private TextView titleView;
    private TextView artistView;
    private TextView albumView;
    private TextView metaView;
    private ImageView playTopView;
    private ImageView favHeartView;
    private SeekBar seekView;
    private SeekBar volumeView;
    private TextView positionView;
    private TextView positionTopView;
    private TextView durationTopView;
    private TextView durationView;
    private ImageView playView;
    private ImageView prevView;
    private ImageView nextView;
    private ImageView modeView;
    private ImageView queueView;
    private ImageView favBtnView;
    private KaraokeLyricsView lyricView;
    private TextView qualityValueView;
    private TextView lyricChipTextView;
    private boolean coverExpanded;
    private boolean coverInfoMode;
    private TextView lyricLineView;
    private View cdRoot;
    private View cdDisc;
    private RecyclerView shelfList;
    private ShelfAdapter shelfAdapter;

    // ===== 状态 =====
    private final List<LyricsLine> lyricMains = new ArrayList<>();
    private final List<String> lyricTrans = new ArrayList<>();
    private final List<String> lyricRomas = new ArrayList<>();
    private int lyricIndex = -1;
    private boolean seeking;
    private boolean favorited;
    private boolean playing;
    private int ambientColor = CoverColor.FALLBACK_COLOR;
    private int dominantColor = CoverColor.FALLBACK_COLOR;
    private ValueAnimator ambientAnimator;
    /** 进度条补间（GZ 通用进度 animateFloatAsState tween(200, bs4.a)：tick 间平滑滑动而非跳步） */
    private ValueAnimator seekAnim;
    // GZ 全局缓动（defpackage/bs4）：a=FastOutSlowIn(0.2,0,0,1)、e=Emphasized(0.05,0.7,0.1,1)、b=LinearOutSlowIn(0,0,0,1)、f=滑出(0.3,0,0.8,0.15)
    private static final android.view.animation.Interpolator INTERP_FOSI = new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final android.view.animation.Interpolator INTERP_EMPH = new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private static final android.view.animation.Interpolator INTERP_LOSI = new android.view.animation.PathInterpolator(0f, 0f, 0f, 1f);
    private static final android.view.animation.Interpolator INTERP_EXIT = new android.view.animation.PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    /** bs4.c (0.3,0,1,1)：歌名／歌手出场交叉淡化 fadeOut 150ms 用（redo3 M7／F2）。 */
    private static final android.view.animation.Interpolator INTERP_OUT_C = new android.view.animation.PathInterpolator(0.3f, 0f, 1f, 1f);
    /** bs4.d 近似（类 Emphasized 路径缓动，树内 CdSweep 已用同族贝塞尔 (0.3,0,0.1,1)）：CD 歌词展开 600／收起 500ms 用（redo3 D7／F4）。 */
    private static final android.view.animation.Interpolator INTERP_CD_EXPAND = new android.view.animation.PathInterpolator(0.3f, 0f, 0.1f, 1f);
    // ===== 全屏歌词内页（GZ 本体为播放页内 HorizontalPager 第 2 页，与播放页同一状态源；
    // 原独立 MusicLyricsActivity 自带一份取词/解析/取色/进度监听链、与播放页双份打架，已整条删除） =====
    private View lyricsPage;
    private com.fongmi.android.tv.ui.custom.KaraokeLyricsView pageLyricView;
    private ImageView pageCoverView;
    private View pageCoverFade;
    private TextView pageTitleView;
    private TextView pageArtistView;
    private ImageView pageBtnFav;
    private ImageView pageBtnPlayTop;
    private TextView pageChipQuality;
    private TextView pageChipWord;
    private SeekBar pageSeek;
    private boolean lyricsPageOpen;
    private boolean pageHasWordLines;
    private ObjectAnimator cdAnimator;
    private com.fongmi.android.tv.ui.custom.CdSweepView cdSweepView;
    private boolean cdBoundOnce;
    private boolean destroyed;
    /** 上一次跳歌方向（redo3 D8：瓜子 lastSkipDirection 口径，≤0 取 −1、否则 ＋1；CD 扫光方向用，默认 ＋1）。 */
    private int lastSkipDirection = 1;
    /** 流光 CD 歌词展开态（redo3 F4）与展开动画。 */
    private boolean cdLyricsExpanded;
    private ValueAnimator cdExpandAnimator;
    private int cdCoverWrapHeight;
    /** 封面回中弹簧（redo3 M5：spring(阻尼比 0.9, 刚度 1400) 半隐式欧拉积分）状态。 */
    private android.view.Choreographer.FrameCallback coverSpringCallback;
    private float coverSpringVx;
    private float coverSpringVy;
    private long coverSpringLastMs;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, MusicPlayerActivity.class));
    }

    private final MusicController.Listener listener = new MusicController.Listener() {
        @Override
        public void onTrackChanged(MusicTrack track) {
            runOnUiThread(() -> bindTrack(track));
        }

        @Override
        public void onPlayingChanged(boolean playing) {
            runOnUiThread(() -> setPlaying(playing));
        }

        @Override
        public void onProgress(long position, long duration) {
            runOnUiThread(() -> onProgressUi(position, duration));
        }

        @Override
        public void onModeChanged(int mode) {
            runOnUiThread(() -> refreshModeIcon(mode));
        }

        @Override
        public void onAudioInfoChanged() {
            runOnUiThread(() -> {
                refreshQualityValue();
                refreshPageQualityChip();
            });
        }
    };

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityMusicPlayerBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
        MusicController.get().addListener(listener);
        // 深底页：状态栏图标显式浅色，防音乐首页设置的深色图标串扰（06:17 状态栏衔接核查）
        ReadingSkin.setSystemBarIconAppearance(this, true);
        wireLyricsPage();
        applyTheme();
        bindTrack(MusicController.get().current());
        setPlaying(MusicController.get().isPlaying());
        refreshModeIcon(MusicController.get().getMode());
        refreshQualityValue();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(this).pageResumed("music");
        } catch (Throwable ignored) {
        }
        // 主题可能在别处被改（或工具面板回调漏达）：主题 id 变了就换装
        String theme = MusicStore.getPlayerTheme();
        if (theme != null && !theme.equals(themeId)) applyTheme();
        else {
            applyLyricStyle();
            if (lyricView != null) lyricView.setPlaying(playing);
        }
        applyKeepScreenOn();
        refreshVolume();
        updateRotations();
    }

    @Override
    protected void onPause() {
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(this).pagePaused("music");
        } catch (Throwable ignored) {
        }
        if (cdAnimator != null && cdAnimator.isRunning()) cdAnimator.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        MusicController.get().removeListener(listener);
        MusicController.get().unbind();
        if (ambientAnimator != null) ambientAnimator.cancel();
        if (cdAnimator != null) cdAnimator.cancel();
        super.onDestroy();
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

    /** 按 MusicStore 主题 id 装布局（非法 id 回退 classic，与 MusicStore 口径一致再兜一层） */
    private void applyTheme() {
        String theme = MusicStore.getPlayerTheme();
        int layout;
        if (THEME_APPLE.equals(theme)) layout = R.layout.player_theme_apple_music;
        else if (THEME_IMMERSIVE.equals(theme)) layout = R.layout.player_theme_cover_immersive;
        else if (THEME_CD.equals(theme)) layout = R.layout.player_theme_luminous_cd;
        else if (THEME_SHELF.equals(theme)) layout = R.layout.player_theme_record_shelf;
        else {
            theme = THEME_CLASSIC;
            layout = R.layout.player_theme_classic;
        }
        themeId = theme;
        if (cdAnimator != null) {
            cdAnimator.cancel();
            cdAnimator = null;
        }
        binding.themeContainer.removeAllViews();
        themeRoot = LayoutInflater.from(this).inflate(layout, binding.themeContainer, false);
        // 主题切换过渡（GZ MotionKt 进出转场 tween(260, bs4.a) 近似：淡入＋0.98 微缩放）
        themeRoot.setAlpha(0f);
        themeRoot.setScaleX(0.98f);
        themeRoot.setScaleY(0.98f);
        themeRoot.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(INTERP_FOSI).start();
        binding.themeContainer.addView(themeRoot);
        grabThemeViews();
        wireThemeViews();
        applyLyricStyle();
        feedLyricView();
        // 换装后把当前歌曲的静态信息重刷一遍（封面走缓存，不重拉歌词）
        MusicTrack track = MusicController.get().current();
        bindTrackViews(track);
        setPlaying(playing);
        refreshModeIcon(MusicController.get().getMode());
        refreshQualityValue();
        refreshVolume();
    }

    private <T extends View> T find(int id) {
        return themeRoot == null ? null : themeRoot.findViewById(id);
    }

    private void grabThemeViews() {
        coverView = find(R.id.tpCover);
        coverExitView = find(R.id.tpCoverExit);
        cdLyricsExpanded = false;
        if (cdExpandAnimator != null) {
            cdExpandAnimator.cancel();
            cdExpandAnimator = null;
        }
        titleView = find(R.id.tpTitle);
        artistView = find(R.id.tpArtist);
        albumView = find(R.id.tpAlbum);
        metaView = find(R.id.tpMeta);
        playTopView = find(R.id.tpPlayTop);
        favHeartView = find(R.id.tpFavHeart);
        seekView = find(R.id.tpSeek);
        volumeView = find(R.id.tpVolume);
        positionView = find(R.id.tpPosition);
        positionTopView = find(R.id.tpPositionTop);
        durationTopView = find(R.id.tpDurationTop);
        durationView = find(R.id.tpDuration);
        playView = find(R.id.tpPlay);
        prevView = find(R.id.tpPrev);
        nextView = find(R.id.tpNext);
        modeView = find(R.id.tpMode);
        queueView = find(R.id.tpQueue);
        favBtnView = find(R.id.tpBtnFav);
        lyricView = find(R.id.tpLyricView);
        qualityValueView = find(R.id.tpQualityValue);
        lyricChipTextView = find(R.id.tpLyricChipText);
        lyricLineView = find(R.id.tpLyricLine);
        cdRoot = find(R.id.tpCdRoot);
        cdDisc = find(R.id.tpCdDisc);
        cdSweepView = find(R.id.tpCdSweep);
        shelfList = find(R.id.tpShelfList);
    }

    private void wireThemeViews() {
        if (playView != null) playView.setOnClickListener(v -> MusicController.get().toggle());
        // 经典主题 SongMeta 三键内的播放/暂停键（图标尺寸 28/25dp 在 setPlaying 里按态切 padding）
        if (playTopView != null) playTopView.setOnClickListener(v -> MusicController.get().toggle());
        // 歌名行收藏小爱心：已收藏才显示（GZ F04），点按可取消/收藏
        if (favHeartView != null) favHeartView.setOnClickListener(v -> toggleFavorite(v));
        if (prevView != null) prevView.setOnClickListener(v -> {
            lastSkipDirection = -1;
            MusicController.get().prev();
        });
        if (nextView != null) nextView.setOnClickListener(v -> {
            lastSkipDirection = 1;
            MusicController.get().next();
        });
        if (modeView != null) modeView.setOnClickListener(v -> MusicController.get().cycleMode());
        if (queueView != null) queueView.setOnClickListener(v -> MusicQueueDialog.show(this));
        if (favBtnView != null) favBtnView.setOnClickListener(v -> toggleFavorite(v));

        // 封面圆角：经典沿用现有 28dp 裁剪口径，其余主题按各自版式；出场封面层与主封面同形裁剪
        if (coverView != null) {
            if (THEME_CD.equals(themeId)) setCircle(coverView);
            else setRounded(coverView, THEME_CLASSIC.equals(themeId) ? 28 : THEME_APPLE.equals(themeId) ? 20 : THEME_IMMERSIVE.equals(themeId) ? 22 : 24);
        }
        if (coverExitView != null) {
            if (THEME_CD.equals(themeId)) setCircle(coverExitView);
            else setRounded(coverExitView, THEME_CLASSIC.equals(themeId) ? 28 : THEME_APPLE.equals(themeId) ? 20 : THEME_IMMERSIVE.equals(themeId) ? 22 : 24);
        }

        // 封面手势（G01–G03）：经典/Apple/封面卡片三套带大封面的主题共用
        View coverWrap = find(R.id.tpCoverWrap);
        if (coverWrap != null) attachCoverGestures(coverWrap);

        // 进度口径统一千分比（沿用已验口径：max=1000，拖完才 seek）
        if (seekView != null) {
            seekView.setMax(1000);
            seekView.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    seeking = true;
                    swapSeekDrawable(seekBar, true);
                    setTopTimesAlpha(0.72f);
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    seeking = false;
                    swapSeekDrawable(seekBar, false);
                    setTopTimesAlpha(0f);
                    long dur = MusicController.get().duration();
                    MusicController.get().seekTo(dur * seekBar.getProgress() / 1000);
                }
            });
        }
        wireVolume();

        // 经典主题专属：SongMeta 菜单键/队列键＋Scrubber 音质筹/歌词筹
        // （收藏走双击封面与歌名行小爱心、下载/音效/模式走工具面板——GZ 经典主题无独立筹行与底部主控行）
        View queueTitle = find(R.id.tpBtnQueueTitle);
        if (queueTitle != null) queueTitle.setOnClickListener(v -> MusicQueueDialog.show(this));
        View menu = find(R.id.tpBtnMenu);
        if (menu != null) menu.setOnClickListener(v -> showToolsSheet());
        View chipQuality = find(R.id.tpChipQuality);
        if (chipQuality != null) chipQuality.setOnClickListener(v -> showQualityDialog());
        View chipLyric = find(R.id.tpChipLyric);
        if (chipLyric != null) chipLyric.setOnClickListener(v -> showToolsSheetLyric());

        // 歌词视图接线：经典/流光 CD/唱片架均为 cover 模式；
        // 点行回调——经典点开全屏歌词页（G04 单击翻页口径），CD/唱片架点行 seek（GZ onLyricSeek 口径）
        if (lyricView != null) {
            if (THEME_CLASSIC.equals(themeId) || THEME_CD.equals(themeId) || THEME_SHELF.equals(themeId) || THEME_IMMERSIVE.equals(themeId)) {
                lyricView.setCoverMode(true);
            }
            if (THEME_SHELF.equals(themeId)) lyricView.setLightScheme(true);
            lyricView.setOnLineClickListener((index, positionMs) -> {
                if (THEME_CLASSIC.equals(themeId)) openLyricsPage();
                else if (THEME_CD.equals(themeId)) {
                    // 流光 CD：未展开时点歌词区先展开（瓜子 CdLyricSummary 点按展开口径），展开后点行才 seek
                    if (!cdLyricsExpanded) toggleCdLyricsExpanded();
                    else MusicController.get().seekTo(positionMs);
                } else MusicController.get().seekTo(positionMs);
            });
            lyricView.setOnEmptyTapListener(() -> {
                if (THEME_CD.equals(themeId)) toggleCdLyricsExpanded();
            });
            attachPagerSwipe(lyricView, true);
        }

        // Apple 专属：歌词入口/音质入口＋运输键按压缩放（上/下一首 0.88、播放 0.9）
        View btnLyric = find(R.id.tpBtnLyric);
        if (btnLyric != null) btnLyric.setOnClickListener(v -> openLyricsPage());
        View btnQuality = find(R.id.tpBtnQuality);
        if (btnQuality != null) btnQuality.setOnClickListener(v -> showQualityDialog());
        if (THEME_APPLE.equals(themeId)) {
            attachPressScale(prevView, 0.88f);
            attachPressScale(nextView, 0.88f);
            attachPressScale(playView, 0.9f);
        }

        // 封面卡片：点玻璃条中段——展开态回封面模式（GZ 文案「返回到封面模式」），默认态开全屏歌词页
        if (lyricLineView != null) lyricLineView.setOnClickListener(v -> {
            if (THEME_IMMERSIVE.equals(themeId) && coverExpanded) toggleCoverExpanded();
            else openLyricsPage();
        });

        // 封面卡片（GZ A2）：封面卡宽＝min(区宽×0.98, 区高/0.65)、高＝宽×0.65、居中、圆角 22dp 裁剪
        View immersiveWrap = find(R.id.tpCoverWrap);
        View coverCard = find(R.id.tpCoverCard);
        if (THEME_IMMERSIVE.equals(themeId) && immersiveWrap != null && coverCard != null) {
            coverCard.setClipToOutline(true);
            coverCard.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline outline) {
                    int r = (int) (22 * v.getResources().getDisplayMetrics().density);
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
                }
            });
            immersiveWrap.post(() -> {
                int availW = immersiveWrap.getWidth() - com.fongmi.android.tv.utils.ResUtil.dp2px(48);
                int availH = immersiveWrap.getHeight();
                if (availW <= 0 || availH <= 0) return;
                int w = Math.min(Math.round(availW * 0.98f), Math.round(availH / 0.65f));
                int h = Math.round(w * 0.65f);
                ViewGroup.LayoutParams lp = coverCard.getLayoutParams();
                if (lp != null && (lp.width != w || lp.height != h)) {
                    lp.width = w;
                    lp.height = h;
                    coverCard.setLayoutParams(lp);
                }
            });
            View infoBtn = find(R.id.tpBtnInfoExp);
            if (infoBtn != null) infoBtn.setOnClickListener(v -> toggleCoverInfo());
        }

        // 流光 CD：色板生效＋建旋转动画
        if (THEME_CD.equals(themeId)) {
            applyCdPalette();
            buildCdAnimator();
        }
        // 黑胶唱片架：横向队列陈列
        if (shelfList != null) {
            shelfList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            shelfAdapter = new ShelfAdapter();
            shelfList.setAdapter(shelfAdapter);
        }
        applyTitleWeight();
        attachThemePressScales();
    }

    /** 歌名／歌手字重（redo3）：经典与封面卡片歌名 800、封面卡片歌手 500、唱片架歌名 500（API 28+ 字重 API；低版本沿用布局 bold 兜底）。 */
    private void applyTitleWeight() {
        if (android.os.Build.VERSION.SDK_INT < 28) return;
        if (titleView != null) {
            if (THEME_CLASSIC.equals(themeId) || THEME_IMMERSIVE.equals(themeId)) {
                titleView.setTypeface(Typeface.create(Typeface.DEFAULT, 800, false));
            } else if (THEME_SHELF.equals(themeId)) {
                titleView.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
            }
        }
        if (artistView != null && THEME_IMMERSIVE.equals(themeId)) {
            artistView.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        }
    }

    /** 按压缩放覆盖面（redo3 M6：GZ MotionKt.c 默认 0.96、收藏键 0.88；Apple 运输键已另挂 0.88／0.9，此处不再重复挂）。 */
    private void attachThemePressScales() {
        if (THEME_APPLE.equals(themeId)) {
            attachPressScale(find(R.id.tpBtnLyric), 0.96f);
            attachPressScale(find(R.id.tpBtnQuality), 0.96f);
            attachPressScale(queueView, 0.96f);
        } else if (THEME_CLASSIC.equals(themeId)) {
            attachPressScale(find(R.id.tpBtnMenu), 0.96f);
            attachPressScale(find(R.id.tpBtnQueueTitle), 0.96f);
            attachPressScale(playTopView, 0.96f);
            attachPressScale(favHeartView, 0.88f);
        } else if (THEME_IMMERSIVE.equals(themeId)) {
            attachPressScale(prevView, 0.96f);
            attachPressScale(nextView, 0.96f);
            attachPressScale(queueView, 0.96f);
            attachPressScale(find(R.id.tpBtnInfoExp), 0.96f);
            attachPressScale(favBtnView, 0.88f);
            attachPressScale(playView, 0.96f);
        } else if (THEME_CD.equals(themeId)) {
            attachPressScale(prevView, 0.96f);
            attachPressScale(nextView, 0.96f);
            attachPressScale(playView, 0.96f);
            attachPressScale(queueView, 0.96f);
            attachPressScale(modeView, 0.96f);
        } else if (THEME_SHELF.equals(themeId)) {
            attachPressScale(prevView, 0.96f);
            attachPressScale(nextView, 0.96f);
            attachPressScale(playView, 0.96f);
            attachPressScale(modeView, 0.96f);
            attachPressScale(favBtnView, 0.88f);
        }
    }

    private void setRounded(ImageView view, float radiusDp) {
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                int r = (int) (radiusDp * v.getResources().getDisplayMetrics().density);
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
    }

    private void setCircle(ImageView view) {
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
    }

    /** 按压缩放（Apple 运输键口径）：按下缩、松开回，不吞点击 */
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

    // ================= 封面手势（G01–G03） =================

    /**
     * 封面手势（GZ §2 锚点模型转写）：双击→收藏＋burstCover（大爆）；长按→工具面板；
     * 单击→开歌词内页/切展开；滑动切歌按主题分轴——经典竖滑（锚点距 clamp(屏高×0.42, 240, 400)dp、
     * 轴锁 1.5）、封面卡片横滑（锚点距 clamp(屏宽×0.42, 220, 360)dp、轴锁 1.2）、其余主题横滑（轴锁 1.5、无跟手）；
     * 触发距离 36dp、甩动速度 600dp/s、拖动 1:1 跟手钳在锚点内、过阈 CONTEXT_CLICK 震动一次、
     * 提交先 150ms(bs4.f) 滑到锚点再切歌、不提交回中（GZ 弹簧 0.9/1400 以 250ms tween 近似）。
     */
    private void attachCoverGestures(View wrap) {
        final boolean vertical = THEME_CLASSIC.equals(themeId);
        final boolean follow = vertical || THEME_IMMERSIVE.equals(themeId);
        final float dominance = THEME_IMMERSIVE.equals(themeId) ? 1.2f : 1.5f;
        final float density = getResources().getDisplayMetrics().density;
        // 横滑锚点（redo3 M4）：封面卡片沿用屏宽公式（已对齐）；其余横滑主题按本任务指令改瓜子
        // 封面边长公式 clamp(封面边长×0.3, 70, 140)dp——分表注：该公式原为横屏手机口径，竖屏 Apple／CD
        // 原口径未抠到，此处为指令驱动落值，非竖屏实证；封面未量出宽时先用屏宽公式，量出后纠正。
        final float[] anchorDp = new float[]{vertical
                ? Math.max(240f, Math.min(400f, getResources().getDisplayMetrics().heightPixels / density * 0.42f))
                : Math.max(220f, Math.min(360f, getResources().getDisplayMetrics().widthPixels / density * 0.42f))};
        if (!vertical && !THEME_IMMERSIVE.equals(themeId) && coverView != null) {
            wrap.post(() -> {
                int w = coverView.getWidth();
                if (w > 0) anchorDp[0] = Math.max(70f, Math.min(140f, w / density * 0.3f));
            });
        }
        final float[] drag = new float[1];
        final boolean[] state = new boolean[3]; // 0 dominant 1 hapticFired 2 committed
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                cancelCoverSpringBack();
                drag[0] = 0f;
                state[0] = false;
                state[1] = false;
                state[2] = false;
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // 封面卡片主题：单击封面切换展开态（GZ CoverImmersive 点封面展开/收起）；
                // 其余主题单击开全屏歌词页（双击手势被识别时本回调不触发，与双击收藏天然互斥）
                if (THEME_IMMERSIVE.equals(themeId)) toggleCoverExpanded();
                else openLyricsPage();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                MusicTrack track = MusicController.get().current();
                if (track != null && !favorited) toggleFavorite(null);
                float[] p = toOverlayPoint(wrap, e.getX(), e.getY());
                binding.heartsOverlay.burstCover(p[0], p[1], true);
                wrap.animate().scaleX(1.045f).scaleY(1.045f).setDuration(200)
                        .withEndAction(() -> wrap.animate().scaleX(1f).scaleY(1f).setDuration(250).start())
                        .start();
                try {
                    wrap.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                } catch (Throwable ignored) {
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                showToolsSheet();
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                float off = vertical ? dy : dx;
                float cross = vertical ? dx : dy;
                // 竖滑状态栏死区（redo3 M3）：触摸起点落在状态栏高度内且向下滑时不触发切歌
                if (vertical && dy > 0) {
                    int statusBarHeight = 0;
                    int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
                    if (resId > 0) statusBarHeight = getResources().getDimensionPixelSize(resId);
                    int[] wrapLoc = new int[2];
                    wrap.getLocationInWindow(wrapLoc);
                    if (wrapLoc[1] + e1.getY() < statusBarHeight) {
                        state[0] = false;
                        return false;
                    }
                }
                boolean dominant = Math.abs(off) > Math.abs(cross) * dominance;
                state[0] = dominant;
                if (!dominant) return false;
                drag[0] = off;
                float anchorPx = anchorDp[0] * density;
                if (follow && coverView != null) {
                    float clamped = Math.max(-anchorPx, Math.min(anchorPx, off));
                    if (vertical) coverView.setTranslationY(clamped);
                    else coverView.setTranslationX(clamped);
                }
                TextView hint = findViewById(R.id.swipeHint);
                if (Math.abs(off) > 36 * density) {
                    if (!state[1]) {
                        state[1] = true;
                        try {
                            wrap.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (hint != null) {
                        hint.setText(off < 0 ? R.string.music_next : R.string.music_prev);
                        hint.setVisibility(View.VISIBLE);
                    }
                } else {
                    state[1] = false;
                    if (hint != null) hint.setVisibility(View.GONE);
                }
                return false;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                float v = vertical ? velocityY : velocityX;
                // 甩动提交：位移不足但轴向速度达 600dp/s 也触发，方向取速度符号
                if (state[0] && Math.abs(v) >= 600 * density && Math.abs(drag[0]) < 36 * density) {
                    commitCoverSwipe(wrap, v < 0, anchorDp[0] * density, follow, vertical, state);
                    return true;
                }
                return false;
            }
        });
        wrap.setOnTouchListener((v, e) -> {
            boolean handled = detector.onTouchEvent(e);
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                TextView hint = findViewById(R.id.swipeHint);
                if (hint != null) hint.setVisibility(View.GONE);
                if (!state[2]) {
                    if (state[0] && Math.abs(drag[0]) >= 36 * density) {
                        commitCoverSwipe(wrap, drag[0] < 0, anchorDp[0] * density, follow, vertical, state);
                    } else if (follow && coverView != null) {
                        // 不提交回中（redo3 M5：GZ spring(阻尼比 0.9, 刚度 1400) 弹簧积分回中）
                        startCoverSpringBack();
                    }
                }
                drag[0] = 0f;
                state[0] = false;
            }
            return handled;
        });
    }

    /** 滑动切歌提交：先滑到锚点（150ms bs4.f）再切歌；无跟手主题直接切（转场由 bindCovers 承担） */
    private void commitCoverSwipe(View wrap, boolean next, float anchorPx, boolean follow, boolean vertical, boolean[] state) {
        state[2] = true;
        lastSkipDirection = next ? 1 : -1;
        cancelCoverSpringBack();
        Runnable change = () -> {
            if (coverView != null) {
                coverView.setTranslationX(0f);
                coverView.setTranslationY(0f);
            }
            if (next) MusicController.get().next();
            else MusicController.get().prev();
        };
        if (follow && coverView != null) {
            float target = next ? -anchorPx : anchorPx;
            if (vertical) {
                coverView.animate().translationY(target).setDuration(150).setInterpolator(INTERP_EXIT)
                        .withEndAction(change).start();
            } else {
                coverView.animate().translationX(target).setDuration(150).setInterpolator(INTERP_EXIT)
                        .withEndAction(change).start();
            }
        } else {
            change.run();
        }
    }

    /** 取消封面回中弹簧（新手势按下／提交切歌时调用）。 */
    private void cancelCoverSpringBack() {
        if (coverSpringCallback != null) {
            android.view.Choreographer.getInstance().removeFrameCallback(coverSpringCallback);
            coverSpringCallback = null;
        }
    }

    /** 封面回中弹簧（redo3 M5）：spring(阻尼比 0.9, 刚度 1400)，半隐式欧拉逐帧积分，dt 钳 50ms；落定阈值位移 0.5px／速度 1px·s⁻¹。 */
    private void startCoverSpringBack() {
        if (coverView == null) return;
        cancelCoverSpringBack();
        coverSpringVx = 0f;
        coverSpringVy = 0f;
        coverSpringLastMs = 0L;
        coverSpringCallback = new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (coverView == null) {
                    coverSpringCallback = null;
                    return;
                }
                long nowMs = frameTimeNanos / 1000000L;
                float dt = coverSpringLastMs == 0 ? 0.016f
                        : Math.min(0.05f, (nowMs - coverSpringLastMs) / 1000f);
                coverSpringLastMs = nowMs;
                double k = 1400d;
                double damp = 2d * 0.9d * Math.sqrt(k);
                float x = coverView.getTranslationX();
                float y = coverView.getTranslationY();
                coverSpringVx += (float) ((k * (0 - x) - damp * coverSpringVx) * dt);
                coverSpringVy += (float) ((k * (0 - y) - damp * coverSpringVy) * dt);
                x += coverSpringVx * dt;
                y += coverSpringVy * dt;
                coverView.setTranslationX(x);
                coverView.setTranslationY(y);
                if (Math.abs(x) < 0.5f && Math.abs(y) < 0.5f
                        && Math.abs(coverSpringVx) < 1f && Math.abs(coverSpringVy) < 1f) {
                    coverView.setTranslationX(0f);
                    coverView.setTranslationY(0f);
                    coverSpringCallback = null;
                } else {
                    android.view.Choreographer.getInstance().postFrameCallback(this);
                }
            }
        };
        android.view.Choreographer.getInstance().postFrameCallback(coverSpringCallback);
    }

    /** 主题内坐标 → 爱心覆盖层坐标 */
    private float[] toOverlayPoint(View anchor, float x, float y) {
        int[] a = new int[2];
        int[] o = new int[2];
        anchor.getLocationInWindow(a);
        binding.heartsOverlay.getLocationInWindow(o);
        return new float[]{a[0] - o[0] + x, a[1] - o[1] + y};
    }

    private float[] viewCenterInOverlay(View view) {
        int[] a = new int[2];
        int[] o = new int[2];
        view.getLocationInWindow(a);
        binding.heartsOverlay.getLocationInWindow(o);
        return new float[]{a[0] - o[0] + view.getWidth() / 2f, a[1] - o[1] + view.getHeight() / 2f};
    }

    // ================= 歌曲绑定 =================

    private void bindTrack(MusicTrack track) {
        bindTrackViews(track);
        bindPageHeader(track);
        if (track == null) {
            // 无曲目：歌词视图等待态
            if (lyricView != null) lyricView.showWaiting();
            return;
        }
        loadLyric(track, false);
        refreshFavorite(track);
        refreshQualityValue();
        if (shelfAdapter != null) shelfAdapter.notifyDataSetChanged();
        scrollShelfToCurrent();
    }

    /** 旧文案幽灵层：按原视图位置／字号／字重／颜色／字距／对齐复制一层，fadeOut 150ms（bs4.c）后移除；未量出尺寸或文案未变时不建层。 */
    private void fadeOutOldText(TextView view, String oldText, String newText) {
        if (view == null || oldText == null || oldText.isEmpty() || oldText.equals(newText)) return;
        if (view.getWidth() <= 0 || view.getHeight() <= 0) return;
        try {
            int[] loc = new int[2];
            view.getLocationInWindow(loc);
            int[] rootLoc = new int[2];
            binding.getRoot().getLocationInWindow(rootLoc);
            TextView ghost = new TextView(this);
            ghost.setText(oldText);
            ghost.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, view.getTextSize());
            ghost.setTypeface(view.getTypeface());
            ghost.setTextColor(view.getCurrentTextColor());
            ghost.setLetterSpacing(view.getLetterSpacing());
            ghost.setMaxLines(1);
            ghost.setEllipsize(view.getEllipsize());
            ghost.setIncludeFontPadding(false);
            ghost.setGravity(view.getGravity());
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(view.getWidth(), view.getHeight());
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = loc[0] - rootLoc[0];
            lp.topMargin = loc[1] - rootLoc[1];
            ((ViewGroup) binding.getRoot()).addView(ghost, lp);
            ghost.animate().alpha(0f).setDuration(150).setInterpolator(INTERP_OUT_C)
                    .withEndAction(() -> {
                        try {
                            ((ViewGroup) binding.getRoot()).removeView(ghost);
                        } catch (Throwable ignored) {
                        }
                    }).start();
        } catch (Throwable ignored) {
        }
    }

    /** 只刷静态视图（歌名/歌手/封面/背景）；歌词与收藏由 bindTrack 另行刷新 */
    private void bindTrackViews(MusicTrack track) {
        // 歌名／歌手切换交叉淡化（redo3 M7／F2）：先记旧文案，换新文案后旧文案以幽灵层
        // 在原位 fadeOut 150ms（bs4.c），新文案同步 fadeIn 200ms（bs4.e），形成双层交叉；
        // 幽灵层挂在 Activity 根 FrameLayout 上，不改各主题布局结构，动画结束即移除。
        String oldTitle = titleView == null ? null : String.valueOf(titleView.getText());
        String oldArtist = artistView == null ? null : String.valueOf(artistView.getText());
        String newTitle = track == null ? getString(R.string.music_no_track) : track.displayTitle();
        String newArtist = track == null ? "" : track.displayArtist();
        if (titleView != null) titleView.setText(newTitle);
        if (artistView != null) artistView.setText(newArtist);
        fadeOutOldText(titleView, oldTitle, newTitle);
        fadeOutOldText(artistView, oldArtist, newArtist);
        // 歌名/歌手切换淡入（GZ AnimatedSongText：入 fadeIn tween(200, bs4.e)）
        if (titleView != null) {
            titleView.setAlpha(0f);
            titleView.animate().alpha(1f).setDuration(200).setInterpolator(INTERP_EMPH).start();
        }
        if (artistView != null) {
            artistView.setAlpha(0f);
            artistView.animate().alpha(1f).setDuration(200).setInterpolator(INTERP_EMPH).start();
        }
        // 经典 SongMeta 专辑行：无专辑信息整行隐藏（该藏的藏）
        if (albumView != null) {
            String album = track == null ? null : track.album;
            if (album == null || album.isEmpty()) albumView.setVisibility(View.GONE);
            else {
                albumView.setText(album);
                albumView.setVisibility(View.VISIBLE);
            }
        }
        bindCovers(track);
        refreshMetaLine(track);
    }

    private void bindCovers(MusicTrack track) {
        String cover = track == null ? null : track.cover;
        String title = track == null ? "" : track.displayTitle();
        // 出场封面层（redo3 F1／M1／M2）：切歌前把旧封面 drawable 暂存到出场层；首绑（旧层无图）不建出场动画
        boolean stagedExit = track != null && coverView != null && coverExitView != null && coverView.getDrawable() != null;
        if (stagedExit) {
            coverExitGen++;
            coverExitView.setImageDrawable(coverView.getDrawable());
            coverExitView.setVisibility(View.VISIBLE);
            coverExitView.setAlpha(1f);
            coverExitView.setScaleX(1f);
            coverExitView.setScaleY(1f);
            coverExitView.setTranslationX(0f);
            coverExitView.setTranslationY(0f);
        }
        loadIntoCover(coverView, cover, title);
        updateBackground(track);
        // 封面切换照 GZ CoverTransitionSpec：经典 400ms/enterScaleFrom 0.94、Apple 600ms/0.97、
        // exitAlphaEnd 0（新封面自透明淡入）；其余主题无独立 Spec，沿用 400ms/0.96 近似。
        // 暂停缩放 0.9 只对经典/Apple。
        if (coverView != null && track != null) {
            long dur = THEME_APPLE.equals(themeId) ? 600 : 400;
            float fromScale = THEME_APPLE.equals(themeId) ? 0.97f : THEME_CLASSIC.equals(themeId) ? 0.94f : 0.96f;
            boolean pauseScale = THEME_CLASSIC.equals(themeId) || THEME_APPLE.equals(themeId);
            float target = pauseScale ? (playing ? 1f : 0.9f) : 1f;
            if (stagedExit) {
                // 出场动画（与入场同时跑）：经典出场缩放 1→0.96＋反向位移 0.5×高、Apple 出场缩放 1→1.03、
                // 其余主题出场只有淡出（GZ 未给其余主题出场缩放值，不编）；出场透明度 1→0，出场层动画结束即隐藏。
                // Apple keepFallbackVisible＝true：出场层在整个转场期间可见，即本层本身。
                float exitScale = THEME_CLASSIC.equals(themeId) ? 0.96f : THEME_APPLE.equals(themeId) ? 1.03f : 1f;
                float exitSlide = 0f;
                if (THEME_CLASSIC.equals(themeId)) {
                    int h = coverView.getHeight();
                    exitSlide = -(h > 0 ? h : 300 * getResources().getDisplayMetrics().density) * 0.5f;
                }
                final int gen = coverExitGen;
                coverExitView.animate().alpha(0f).scaleX(exitScale).scaleY(exitScale)
                        .translationY(exitSlide).setDuration(dur)
                        .setInterpolator(THEME_CLASSIC.equals(themeId) ? INTERP_EMPH : INTERP_FOSI)
                        .withEndAction(() -> {
                            if (coverExitView != null && coverExitGen == gen) {
                                coverExitView.setVisibility(View.GONE);
                                coverExitView.setImageDrawable(null);
                            }
                        }).start();
            }
            coverView.setAlpha(0f);
            coverView.setScaleX(fromScale);
            coverView.setScaleY(fromScale);
            // 入场滑移（GZ CoverTransitionSpec coverSlotTransform）：经典竖向入场位移 0.5×高×(1−进度)、
            // 缓动经典 Emphasized(bs4.e)/Apple FastOutSlowIn(bs4.a)；Apple 入场位移 0。出场由上方的出场封面层承担。
            if (THEME_CLASSIC.equals(themeId)) {
                int h = coverView.getHeight();
                float slide = (h > 0 ? h : 300 * getResources().getDisplayMetrics().density) * 0.5f;
                coverView.setTranslationY(slide);
            }
            coverView.animate().alpha(1f).scaleX(target).scaleY(target)
                    .translationX(0f).translationY(0f)
                    .setDuration(dur)
                    .setInterpolator(THEME_CLASSIC.equals(themeId) ? INTERP_EMPH : INTERP_FOSI)
                    .start();
            if (THEME_CD.equals(themeId)) triggerCdSweep();
        }
    }

    private void loadIntoCover(ImageView view, String cover, String title) {
        if (view == null) return;
        if (cover == null || cover.isEmpty()) {
            // 没封面地址才上素图（素灰底＋歌名排字）；有地址的加载失败也保持透明，不变
            view.setImageDrawable(new CoverTextDrawable(title, false,
                    28 * getResources().getDisplayMetrics().density));
        } else {
            ImgUtil.load(title, cover, view);
        }
    }

    /**
     * 背景层：封面按 64×64 小图请求（GZ BlurredCoverAmbient 口径），放大即糊；
     * 拿到图后取色刷氛围层与 CD 色板。无封面用 CoverColor 兜底色。
     */
    private void updateBackground(MusicTrack track) {
        String cover = track == null ? null : track.cover;
        if (cover == null || cover.isEmpty()) {
            binding.ivBgBlur.setImageDrawable(null);
            animateAmbient(CoverColor.ambientBackground(null));
            dominantColor = CoverColor.FALLBACK_COLOR;
            if (THEME_CD.equals(themeId)) applyCdPalette();
            return;
        }
        ImgUtil.load(this, cover, 64, 64, new CustomTarget<Drawable>() {
            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                if (destroyed) return;
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cover.equals(cur.cover)) return;
                binding.ivBgBlur.setImageDrawable(resource);
                if (resource instanceof BitmapDrawable) {
                    Bitmap bmp = ((BitmapDrawable) resource).getBitmap();
                    if (bmp != null) {
                        Task.execute(() -> {
                            int ambient = CoverColor.ambientBackground(bmp);
                            int dominant = CoverColor.dominantColor(bmp);
                            runOnUiThread(() -> {
                                if (destroyed) return;
                                dominantColor = dominant;
                                animateAmbient(ambient);
                                if (THEME_CD.equals(themeId)) applyCdPalette();
                            });
                        });
                    }
                }
            }
        });
    }

    /** 氛围色 450ms 渐变过渡（GZ animateColorAsState tween(450, bs4.a)，PlayerScreenKt coverDominantAmbient） */
    private void animateAmbient(int to) {
        if (ambientAnimator != null) ambientAnimator.cancel();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, to);
        ambientAnimator.setDuration(450);
        ambientAnimator.setInterpolator(INTERP_FOSI);
        ambientAnimator.addUpdateListener(a -> {
            ambientColor = (int) a.getAnimatedValue();
            binding.viewAmbient.setBackgroundColor(ambientColor);
            if (lyricsPageOpen && lyricsPage != null) lyricsPage.setBackgroundColor(ambientColor);
        });
        ambientAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (lyricsPageOpen) applyPageFade();
            }
        });
        ambientAnimator.start();
    }

    /** Apple 第三行：来源·实测音质；无实测整行隐藏（任务口径）。经典/其它主题走 sourceName 逻辑同旧版 */
    private void refreshMetaLine(MusicTrack track) {
        if (metaView == null) return;
        String real = realQualityText();
        if (track == null || real == null) {
            metaView.setVisibility(View.GONE);
            return;
        }
        String source = sourceName(track);
        metaView.setText(TextUtils.isEmpty(source) ? real : source + " · " + real);
        metaView.setVisibility(View.VISIBLE);
    }

    /** 来源标签：查插件名；查不到不显示，本地文件标识为已下载，py 源显示点播源名 */
    private String sourceName(MusicTrack track) {
        if (track == null || track.platform == null) return "";
        try {
            java.util.List<MusicSite> sites = MusicSourceManager.get().listSites();
            // FreeJS 多个插件共用一个 platform：先按 rawJson 的 _plugin 精确对归属，
            // 只按 platform 匹配永远命中排第一的插件（KW 的歌被标成 bilibili 的实证根因）
            String plugin = pluginOf(track);
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
        if (com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform)) {
            return getString(R.string.music_device);
        }
        if (com.fongmi.android.tv.music.PyMusicSource.PLATFORM.equals(track.platform)) {
            return com.fongmi.android.tv.music.PyMusicSource.siteNameOf(track);
        }
        return "";
    }

    /** track.rawJson 里的 _plugin（FreeJS 曲目归属的插件 srcUrl），取不到返回 null */
    private static String pluginOf(MusicTrack track) {
        try {
            if (track == null || track.rawJson == null || track.rawJson.isEmpty()) return null;
            return com.google.gson.JsonParser.parseString(track.rawJson)
                    .getAsJsonObject().get("_plugin").getAsString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ================= 歌词 =================

    private void loadLyric(MusicTrack track, boolean force) {
        // 切歌/开始取词先露加载态，取词未回不许先露空态（时序接线口径）；
        // 单行歌词数据（封面卡片/CD 当前行）照旧先清，避免旧歌词残留错位
        if (lyricView != null) lyricView.showLoading();
        if (lyricsPageOpen && pageLyricView != null) pageLyricView.showLoading();
        lyricMains.clear();
        lyricTrans.clear();
        lyricRomas.clear();
        lyricIndex = -1;
        updateCurrentLyricTexts(MusicController.get().position());
        Task.execute(() -> {
            List<LyricsLine> mains = new ArrayList<>();
            List<String> trans = new ArrayList<>();
            List<String> romas = new ArrayList<>();
            try {
                MusicLyric rich = MusicRepository.lyricRich(track, force);
                if (rich != null) {
                    if (rich.rawLrc != null && !rich.rawLrc.isEmpty()) {
                        mains = LyricsParser.parseWordLyric(rich.rawLrc);
                    }
                    List<LyricsLine> transLines = rich.translation == null || rich.translation.isEmpty()
                            ? null : LyricsParser.parseTimed(rich.translation);
                    List<LyricsLine> romaLines = rich.romanization == null || rich.romanization.isEmpty()
                            ? null : LyricsParser.parseTimed(rich.romanization);
                    List<RichLyrics.Line> aligned = RichLyrics.align(mains, transLines, romaLines);
                    trans.clear();
                    romas.clear();
                    for (RichLyrics.Line line : aligned) {
                        trans.add(line.translation);
                        romas.add(line.romanization);
                    }
                }
                if (mains.isEmpty()) {
                    // rich 通道没拿到正文时回退旧纯文本链（口径与旧版 loadLyric 一致）
                    String lrc = MusicRepository.lyric(track, force);
                    if (lrc != null && !lrc.isEmpty()) mains = LyricsParser.parseWordLyric(lrc);
                }
            } catch (Throwable ignored) {
            }
            List<LyricsLine> resultMains = mains;
            List<String> resultTrans = trans;
            List<String> resultRomas = romas;
            runOnUiThread(() -> {
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cur.equals(track)) return;
                applyLyricData(resultMains, resultTrans, resultRomas);
                // 取词确认为空才露空态
                if (resultMains.isEmpty() && lyricView != null) lyricView.showNoLyric();
                if (resultMains.isEmpty() && lyricsPageOpen && pageLyricView != null) pageLyricView.showNoLyric();
                if (force) {
                    Notify.show(resultMains.isEmpty() ? R.string.music_lyric_not_found : R.string.music_lyric_found);
                }
            });
        });
    }

    /** 工具面板里的手动搜索歌词：强制跨插件找；进行中歌词区内嵌「搜索在线歌词中...」（redo3 N-19），不再弹 Toast */
    private void searchLyric() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        loadLyric(track, true);
        if (lyricView != null) lyricView.showSearching();
        if (lyricsPageOpen && pageLyricView != null) pageLyricView.showSearching();
    }

    /** GZ Scrubber 顶盒两侧时间：静止 alpha 0、拖动 0.72，切换 tween(150, bs4.a)（仅经典布局有此二视图） */
    private void setTopTimesAlpha(float alpha) {
        if (positionTopView != null) {
            positionTopView.animate().alpha(alpha).setDuration(150).setInterpolator(INTERP_FOSI).start();
        }
        if (durationTopView != null) {
            durationTopView.animate().alpha(alpha).setDuration(150).setInterpolator(INTERP_FOSI).start();
        }
    }

    /** 歌词筹文案照 GZ lyricChipLabel 五档中星辰可判定的四档（AMLL 是 GZ 专有格式，星辰无此来源不冒充） */
    private void updateLyricChipLabel(List<LyricsLine> mains) {
        if (lyricChipTextView == null) return;
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

    private void applyLyricData(List<LyricsLine> mains, List<String> trans, List<String> romas) {
        lyricMains.clear();
        lyricMains.addAll(mains);
        lyricTrans.clear();
        lyricTrans.addAll(trans);
        lyricRomas.clear();
        lyricRomas.addAll(romas);
        lyricIndex = -1;
        updateLyricChipLabel(mains);
        feedLyricView();
        updateCurrentLyricTexts(MusicController.get().position());
    }

    private void feedLyricView() {
        if (lyricView != null) {
            lyricView.setLines(new ArrayList<>(lyricMains), new ArrayList<>(lyricTrans), new ArrayList<>(lyricRomas));
            lyricView.setPlaying(playing);
            lyricView.updatePosition(MusicController.get().position());
        }
        if (lyricsPageOpen) feedPageLyricView();
    }

    /** 歌词内页喂词：与播放页同一份 lyricMains 数据，不再单独取词/解析（双份链已删） */
    private void feedPageLyricView() {
        if (pageLyricView == null) return;
        pageLyricView.setLines(new ArrayList<>(lyricMains), new ArrayList<>(lyricTrans), new ArrayList<>(lyricRomas));
        pageLyricView.setPlaying(playing);
        pageLyricView.updatePosition(MusicController.get().position());
        refreshPageWordChip();
    }

    /**
     * 歌词样式从 MusicStore 读并重应用（onResume 与工具面板 onLyricStyleChanged 时走）。
     * 组件现行 API 只覆盖主歌词（setPrimary）与译/音显隐（setShowSub）；
     * 字号/粗细/对齐/模糊/高级动效组件暂无 setter——读取点集中在此，TextView 型单行歌词
     * （封面卡片当前行、CD 歌词行）先吃对齐与粗细，其余待组件补 API 后在此一处接入。
     */
    private void applyLyricStyle() {
        int primary = 0;
        boolean showTrans = true;
        boolean showRoma = false;
        int align = 0;
        int weight = 0;
        float fontSize = 24f;
        int blur = 1;
        boolean advanced = true;
        try {
            primary = MusicStore.getLyricPrimary();
            showTrans = MusicStore.isShowTranslation();
            showRoma = MusicStore.isShowRomanization();
            align = MusicStore.getLyricAlign();
            weight = MusicStore.getLyricWeight();
            fontSize = MusicStore.getLyricFontSize();
            blur = MusicStore.getLyricBlur();
            advanced = MusicStore.isAdvancedFx();
        } catch (Throwable ignored) {
        }
        if (lyricView != null) {
            lyricView.setPrimary(primary);
            lyricView.setShowSub(showTrans, showRoma);
            // 本页歌词视图恒为封面模式（经典内嵌/唱片架卡片）：字号按 GZ 封面 22 对
            // 全屏 24 的差值口径减 2，全屏歌词内页用原值（见 applyPageLyricStyle）。
            lyricView.setMainTextSizeSp(Math.max(16f, fontSize - 2f));
            lyricView.setLyricAlign(align);
            lyricView.setLyricWeight(weight);
            lyricView.setBlurLevel(blur);
            lyricView.setAdvancedFx(advanced);
            // 流光 CD 歌词照 GZ CdLyricsList 固定口径：21sp、居中、标准粗细（TtmlNode.BOLD＝段控「标准」）
            if (THEME_CD.equals(themeId)) {
                lyricView.setMainTextSizeSp(21f);
                lyricView.setLyricAlign(1);
                lyricView.setLyricWeight(0);
            }
        }
        int gravity = align == 1 ? Gravity.CENTER : align == 2 ? Gravity.END : Gravity.START;
        // 封面卡片玻璃条单行歌词（redo3 N-13）：瓜子固定 500，不跟随用户字重档；API 28+ 按字重 500，低版本用常规（不叠粗体）
        Typeface tf;
        if (android.os.Build.VERSION.SDK_INT >= 28) tf = Typeface.create(Typeface.DEFAULT, 500, false);
        else tf = Typeface.DEFAULT;
        if (lyricLineView != null) {
            lyricLineView.setGravity(gravity | Gravity.CENTER_VERTICAL);
            lyricLineView.setTypeface(tf);
            // 歌词字形带一点斜（用户定案）：单行 TextView 走 Paint skew；
            // KaraokeLyricsView 无斜体公开 API，内嵌歌词的斜体列入 C2 缺口清单
            lyricLineView.getPaint().setTextSkewX(-0.12f);
        }
    }

    /** 当前行文本刷新（封面卡片一行；流光 CD 走 KaraokeLyricsView 组件，不经此处） */
    private void updateCurrentLyricTexts(long position) {
        int idx = lyricMains.isEmpty() ? -1 : LyricsParser.findLine(lyricMains, position);
        if (idx == lyricIndex) return;
        lyricIndex = idx;
        String main = "";
        if (idx >= 0 && idx < lyricMains.size()) {
            LyricsLine line = lyricMains.get(idx);
            main = line == null || line.getText() == null ? "" : line.getText();
        }
        if (lyricLineView != null) {
            if (THEME_IMMERSIVE.equals(themeId) && coverExpanded) lyricLineView.setText(R.string.music_cover_back);
            else lyricLineView.setText(main);
        }
    }

    /** 封面卡片展开态开关（GZ：点封面切换；展开时玻璃条中段文案变「返回到封面模式」） */
    private void toggleCoverExpanded() {
        View exp = find(R.id.tpCoverExpanded);
        if (exp == null) return;
        coverExpanded = exp.getVisibility() != View.VISIBLE;
        exp.setVisibility(coverExpanded ? View.VISIBLE : View.GONE);
        if (lyricLineView != null) {
            if (coverExpanded) lyricLineView.setText(R.string.music_cover_back);
            else {
                lyricIndex = Integer.MIN_VALUE;
                updateCurrentLyricTexts(MusicController.get().position());
            }
        }
    }

    /**
     * 流光 CD 歌词展开／收起（redo3 F4／D7）：歌词区高度动画，展开 tween(600, bs4.d 近似)、收起 tween(500, bs4.d 近似)。
     * 触发口径（瓜子实证）：展开态布尔由 CdLyricSummary 点按（未展开时置详情＝歌词并取反）与歌曲信息键切换驱动；
     * 星辰 CD 布局无摘要行与歌曲信息键，故映射为——未展开时点歌词区展开、展开后点行 seek、点空白处收起；
     * 歌曲信息键变体不另建新键。展开时盘面区（tpCoverWrap）高度同步缩到 0，收起时恢复权重布局；
     * 盘面随 collapse 因子的缩放／淡出无逐项值，本批只落歌词区高度动画。
     */
    private void toggleCdLyricsExpanded() {
        if (lyricView == null || !THEME_CD.equals(themeId)) return;
        ViewGroup parent = (ViewGroup) lyricView.getParent();
        View wrap = find(R.id.tpCoverWrap);
        if (parent == null || parent.getHeight() <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        int collapsedH = Math.round(132 * density);
        boolean expanding = !cdLyricsExpanded;
        int fromLyric = lyricView.getHeight() > 0 ? lyricView.getHeight() : collapsedH;
        int fromWrap = wrap == null ? 0 : wrap.getHeight();
        if (expanding) cdCoverWrapHeight = fromWrap;
        int toLyric;
        if (expanding) {
            int rest = 0;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (child == lyricView || child == wrap) continue;
                rest += child.getHeight();
                if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
                    rest += m.topMargin + m.bottomMargin;
                }
            }
            if (lyricView.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lyricView.getLayoutParams();
                rest += m.topMargin + m.bottomMargin;
            }
            toLyric = parent.getHeight() - parent.getPaddingTop() - parent.getPaddingBottom() - rest;
            if (toLyric <= fromLyric) return;
        } else {
            toLyric = collapsedH;
        }
        int toWrap = expanding ? 0 : cdCoverWrapHeight;
        cdLyricsExpanded = expanding;
        if (cdExpandAnimator != null) cdExpandAnimator.cancel();
        final int startLyric = fromLyric;
        final int startWrap = fromWrap;
        cdExpandAnimator = ValueAnimator.ofFloat(0f, 1f);
        cdExpandAnimator.setDuration(expanding ? 600 : 500);
        cdExpandAnimator.setInterpolator(INTERP_CD_EXPAND);
        cdExpandAnimator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            ViewGroup.LayoutParams lp = lyricView.getLayoutParams();
            if (lp != null) {
                lp.height = Math.round(startLyric + (toLyric - startLyric) * f);
                lyricView.setLayoutParams(lp);
            }
            if (wrap != null) {
                ViewGroup.LayoutParams wlp = wrap.getLayoutParams();
                if (wlp != null) {
                    if (wlp instanceof android.widget.LinearLayout.LayoutParams) {
                        ((android.widget.LinearLayout.LayoutParams) wlp).weight = 0f;
                    }
                    wlp.height = Math.round(startWrap + (toWrap - startWrap) * f);
                    wrap.setLayoutParams(wlp);
                }
            }
        });
        cdExpandAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!cdLyricsExpanded && wrap != null) {
                    ViewGroup.LayoutParams wlp = wrap.getLayoutParams();
                    if (wlp != null) {
                        wlp.height = 0;
                        if (wlp instanceof android.widget.LinearLayout.LayoutParams) {
                            ((android.widget.LinearLayout.LayoutParams) wlp).weight = 5f;
                        }
                        wrap.setLayoutParams(wlp);
                    }
                }
            }
        });
        cdExpandAnimator.start();
    }

    /** 展开态「歌曲信息」模式：卡内信息卡与歌词窗二选一（GZ CoverSongInfoCard）；选中态钮面白 0.28、普通 0.14（redo3 V8，tint 切换值未抠到、保持白 tint） */
    private void toggleCoverInfo() {
        View info = find(R.id.tpCoverInfo);
        if (info == null) return;
        coverInfoMode = info.getVisibility() != View.VISIBLE;
        info.setVisibility(coverInfoMode ? View.VISIBLE : View.GONE);
        View infoBtn = find(R.id.tpBtnInfoExp);
        if (infoBtn != null) {
            infoBtn.setBackgroundResource(coverInfoMode
                    ? R.drawable.bg_player_glass_btn_selected : R.drawable.bg_player_glass_btn);
        }
        if (lyricView != null) lyricView.setVisibility(coverInfoMode ? View.GONE : View.VISIBLE);
        if (coverInfoMode) {
            MusicTrack t = MusicController.get().current();
            setInfoText(R.id.tpInfoTitle, t == null ? "" : t.displayTitle());
            setInfoText(R.id.tpInfoArtist, t == null ? "" : t.displayArtist());
            setInfoText(R.id.tpInfoAlbum, t == null || t.album == null ? "" : t.album);
            setInfoText(R.id.tpInfoSource, t == null || t.platform == null ? "" : t.platform);
        }
    }

    private void setInfoText(int id, String text) {
        TextView tv = find(id);
        if (tv != null) tv.setText(text);
    }

    /** 打开全屏歌词页（GZ 本体为同屏 pager 第 2 页；现为播放页内页，与播放页共用数据/进度/监听一套） */
    private void openLyricsPage() {
        if (MusicController.get().current() == null || lyricsPage == null) return;
        lyricsPageOpen = true;
        lyricsPage.setVisibility(View.VISIBLE);
        // 呼出动画：pager 横滑的 View 近似——自右侧滑入（GZ pager 页切换口径，动效组对值见报告）
        lyricsPage.setTranslationX(lyricsPage.getWidth() > 0 ? lyricsPage.getWidth() : getResources().getDisplayMetrics().widthPixels);
        lyricsPage.animate().translationX(0f).setDuration(260).setInterpolator(INTERP_FOSI).start();
        if (lyricView != null) lyricView.setSuspended(true);
        bindPageHeader(MusicController.get().current());
        applyPageLyricStyle();
        feedPageLyricView();
        refreshPageQualityChip();
        paintFavoriteIcons();
        if (pageBtnPlayTop != null) {
            pageBtnPlayTop.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        }
        if (pageLyricView != null) pageLyricView.setPlaying(playing);
    }

    private void closeLyricsPage() {
        if (!lyricsPageOpen || lyricsPage == null) return;
        lyricsPageOpen = false;
        lyricsPage.animate().translationX(lyricsPage.getWidth() > 0 ? lyricsPage.getWidth() : getResources().getDisplayMetrics().widthPixels)
                .setDuration(200).setInterpolator(INTERP_LOSI).withEndAction(() -> {
                    if (!lyricsPageOpen) lyricsPage.setVisibility(View.GONE);
                }).start();
        if (lyricView != null) {
            lyricView.setSuspended(false);
            lyricView.setPlaying(playing);
            lyricView.updatePosition(MusicController.get().position());
        }
    }

    @Override
    public void onBackPressed() {
        if (lyricsPageOpen) {
            closeLyricsPage();
            return;
        }
        super.onBackPressed();
    }

    /** 歌词内页接线（根布局固定控件，与主题无关，一次接好） */
    private void wireLyricsPage() {
        lyricsPage = findViewById(R.id.lyricsPage);
        pageLyricView = findViewById(R.id.pgLyricsView);
        pageCoverView = findViewById(R.id.pgCover);
        pageCoverFade = findViewById(R.id.pgCoverFade);
        pageTitleView = findViewById(R.id.pgTitle);
        pageArtistView = findViewById(R.id.pgArtist);
        pageBtnFav = findViewById(R.id.pgBtnFav);
        pageBtnPlayTop = findViewById(R.id.pgBtnPlayTop);
        pageChipQuality = findViewById(R.id.pgChipQuality);
        pageChipWord = findViewById(R.id.pgChipWord);
        pageSeek = findViewById(R.id.pgSeek);
        if (pageSeek != null) pageSeek.setMax(1000);
        ImageView menu = findViewById(R.id.pgBtnMenu);
        if (menu != null) {
            menu.setOnClickListener(v -> showToolsSheet());
            attachPressScale(menu, 0.88f);
        }
        ImageView queue = findViewById(R.id.pgBtnQueue);
        if (queue != null) {
            queue.setOnClickListener(v -> MusicQueueDialog.show(this));
            attachPressScale(queue, 0.88f);
        }
        if (pageBtnPlayTop != null) {
            pageBtnPlayTop.setOnClickListener(v -> MusicController.get().toggle());
            attachPressScale(pageBtnPlayTop, 0.88f);
        }
        if (pageBtnFav != null) pageBtnFav.setOnClickListener(v -> toggleFavorite(v));
        if (pageChipQuality != null) pageChipQuality.setOnClickListener(v -> {
            showQualityDialog();
            refreshPageQualityChip();
        });
        if (pageChipWord != null) pageChipWord.setOnClickListener(v -> togglePageWordByWord());
        if (pageLyricView != null) {
            pageLyricView.setOnLineClickListener((index, positionMs) -> {
                MusicController.get().seekTo(positionMs);
                pageLyricView.seekUi(positionMs);
            });
            attachPagerSwipe(pageLyricView, false);
        }
        applyPageFade();
    }

    /** 歌词内页头部绑定：与播放页同一曲目数据；封面整幅、底色共用播放页氛围色（不另取色） */
    private void bindPageHeader(MusicTrack track) {
        if (!lyricsPageOpen) return;
        if (track == null) {
            if (pageTitleView != null) pageTitleView.setText(R.string.music_no_track);
            if (pageArtistView != null) pageArtistView.setText("");
            if (pageLyricView != null) pageLyricView.showWaiting();
            if (pageChipQuality != null) pageChipQuality.setVisibility(View.GONE);
            if (pageChipWord != null) pageChipWord.setVisibility(View.GONE);
            return;
        }
        if (pageTitleView != null) pageTitleView.setText(track.displayTitle());
        if (pageArtistView != null) pageArtistView.setText(track.displayArtist());
        if (pageCoverView != null) loadIntoCover(pageCoverView, track.cover, track.displayTitle());
        if (lyricsPage != null) lyricsPage.setBackgroundColor(ambientColor);
        applyPageFade();
    }

    /** 封面下缘渐变：融进当前氛围色（共用播放页取色结果，切歌后随 animateAmbient 收尾重生成） */
    private void applyPageFade() {
        if (pageCoverFade == null) return;
        pageCoverFade.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x00000000, ambientColor, ambientColor}));
    }

    /** 歌词内页样式：GZ 全屏歌词口径——原值字号（不减 2）、非封面模式，其余项与播放页同源 MusicStore */
    private void applyPageLyricStyle() {
        if (pageLyricView == null) return;
        try {
            pageLyricView.setCoverMode(false);
            pageLyricView.setPrimary(MusicStore.getLyricPrimary());
            pageLyricView.setShowSub(MusicStore.isShowTranslation(), MusicStore.isShowRomanization());
            pageLyricView.setMainTextSizeSp(MusicStore.getLyricFontSize());
            pageLyricView.setLyricAlign(MusicStore.getLyricAlign());
            pageLyricView.setLyricWeight(MusicStore.getLyricWeight());
            pageLyricView.setBlurLevel(MusicStore.getLyricBlur());
            pageLyricView.setAdvancedFx(MusicStore.isAdvancedFx());
        } catch (Throwable ignored) {
        }
    }

    /** 歌词内页音质胶囊（redo3 第七节口径修复）：与经典筹同口径——有损显编码＋真实码率、无实测时整筹隐藏，所选档永不进显示。 */
    private void refreshPageQualityChip() {
        if (pageChipQuality == null) return;
        if (MusicController.get().current() == null) {
            pageChipQuality.setVisibility(View.GONE);
            return;
        }
        String label = realQualityText();
        if (label == null) {
            pageChipQuality.setVisibility(View.GONE);
            return;
        }
        pageChipQuality.setText(label);
        pageChipQuality.setVisibility(View.VISIBLE);
    }

    private void refreshPageWordChip() {
        pageHasWordLines = false;
        for (LyricsLine line : lyricMains) {
            if (line != null && line.hasWords()) {
                pageHasWordLines = true;
                break;
            }
        }
        if (pageChipWord == null) return;
        pageChipWord.setVisibility(pageHasWordLines ? View.VISIBLE : View.GONE);
        paintPageWordChip();
    }

    private void paintPageWordChip() {
        if (pageChipWord == null || pageLyricView == null) return;
        boolean on = pageLyricView.isWordByWord();
        pageChipWord.setAlpha(on ? 1f : 0.45f);
        pageChipWord.setTextColor(on ? 0xFFFFFFFF : 0xFF9A9AA0);
    }

    private void togglePageWordByWord() {
        if (!pageHasWordLines || pageLyricView == null) return;
        boolean on = !pageLyricView.isWordByWord();
        pageLyricView.setWordByWord(on);
        paintPageWordChip();
        Notify.show(on ? R.string.lyr_word_on : R.string.lyr_word_off);
    }

    /**
     * pager 横滑观察（GZ F08/G05：播放页⇄歌词页同屏 pager、轴锁 1.5）：只观察不消费，
     * 横向位移占优且过 72dp 才触发——内嵌歌词左滑开内页、内页右滑回播放页。
     */
    private void attachPagerSwipe(View view, boolean embedded) {
        final float[] down = new float[2];
        final boolean[] fired = {false};
        view.setOnTouchListener((v, e) -> {
            float d = getResources().getDisplayMetrics().density;
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                down[0] = e.getX();
                down[1] = e.getY();
                fired[0] = false;
            } else if (e.getAction() == android.view.MotionEvent.ACTION_MOVE && !fired[0]) {
                float dx = e.getX() - down[0];
                float dy = e.getY() - down[1];
                if (Math.abs(dx) > 72 * d && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    fired[0] = true;
                    if (embedded && dx < 0) openLyricsPage();
                    else if (!embedded && dx > 0) closeLyricsPage();
                }
            }
            return false;
        });
    }

    // ================= 进度/主控 =================

    /** 进度条 200ms 补间到目标（tick 250ms 到达前滑完上一段，视觉连续；拖动中不补间、切歌大跳直接落位） */
    private void animateSeekTo(int target) {
        if (seekView == null) return;
        int from = seekView.getProgress();
        if (Math.abs(target - from) > 25) {
            if (seekAnim != null) seekAnim.cancel();
            seekView.setProgress(target);
            return;
        }
        if (seekAnim != null) seekAnim.cancel();
        seekAnim = ValueAnimator.ofInt(from, target);
        seekAnim.setDuration(200);
        seekAnim.setInterpolator(INTERP_FOSI);
        seekAnim.addUpdateListener(a -> {
            if (!seeking && seekView != null) seekView.setProgress((int) a.getAnimatedValue());
        });
        seekAnim.start();
    }

    private void onProgressUi(long position, long duration) {
        if (!seeking && duration > 0 && seekView != null) {
            animateSeekTo((int) (position * 1000 / duration));
        }
        if (positionView != null) positionView.setText(fmt(position));
        // GZ Scrubber 底行右＝负剩余（-mm:ss），不是总时长
        if (durationView != null) durationView.setText("-" + fmt(Math.max(0, duration - position)));
        if (positionTopView != null) positionTopView.setText(fmt(position));
        if (durationTopView != null) durationTopView.setText(fmt(duration));
        if (lyricView != null) {
            lyricView.updatePosition(position);
            lyricView.setPlaying(playing);
        }
        if (lyricsPageOpen) {
            if (pageLyricView != null) pageLyricView.updatePosition(position);
            if (pageSeek != null && duration > 0) {
                pageSeek.setProgress((int) (position * 1000 / duration));
            }
        }
        updateCurrentLyricTexts(position);
    }

    private String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private void setPlaying(boolean isPlaying) {
        playing = isPlaying;
        if (playView != null) {
            playView.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        }
        // 经典 SongMeta 播放键：图标播放中 28dp / 暂停态 25dp（40dp 点击区内靠 padding 换尺寸，GZ animateDpAsState tween(300) 真动画）
        if (playTopView != null) {
            playTopView.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
            float d = getResources().getDisplayMetrics().density;
            int fromPad = playTopView.getPaddingLeft();
            int toPad = Math.round((playing ? 6f : 7.5f) * d);
            if (fromPad != toPad) {
                ValueAnimator padAnim = ValueAnimator.ofInt(fromPad, toPad);
                padAnim.setDuration(300);
                padAnim.addUpdateListener(a -> {
                    int v = (int) a.getAnimatedValue();
                    playTopView.setPadding(v, v, v, v);
                });
                padAnim.start();
            }
        }
        if (lyricView != null) lyricView.setPlaying(playing);
        // 内嵌歌词播/停变暗（GZ lyricDimA：播放 1.0 / 暂停 0.38、tween(350, bs4.a)；CD 主题歌词属 CD 表面不走此口径、歌词内页不压暗）
        if (lyricView != null && !THEME_CD.equals(themeId)) {
            lyricView.animate().alpha(playing ? 1f : 0.38f).setDuration(350).setInterpolator(INTERP_FOSI).start();
        }
        if (pageLyricView != null) pageLyricView.setPlaying(playing);
        if (pageBtnPlayTop != null) {
            pageBtnPlayTop.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        }
        // 经典封面暂停缩放 0.9（GZ 口径）
        if (coverView != null && (THEME_CLASSIC.equals(themeId) || THEME_APPLE.equals(themeId))) {
            float target = playing ? 1f : 0.9f;
            coverView.animate().scaleX(target).scaleY(target).setDuration(250).start();
        }
        updateRotations();
        refreshShelfSpins();
    }

    /** 播放/暂停切换只重绑当前唱片一项来启停自旋，不整表重绑（整表 notify 会让每张唱片重载封面＋重建旋转动画，是卡顿源） */
    private void refreshShelfSpins() {
        if (shelfAdapter == null) return;
        int cur = MusicController.get().index();
        if (cur >= 0) shelfAdapter.notifyItemChanged(cur);
    }

    private void refreshModeIcon(int mode) {
        if (modeView == null) return;
        int icon;
        switch (mode) {
            case MusicQueue.MODE_REPEAT_ONE:
                icon = R.drawable.ic_music_repeat_one;
                break;
            case MusicQueue.MODE_REPEAT_ALL:
                icon = R.drawable.ic_music_repeat;
                break;
            case MusicQueue.MODE_SHUFFLE:
                icon = R.drawable.ic_music_shuffle;
                break;
            default:
                icon = R.drawable.ic_music_sequential;
                break;
        }
        modeView.setImageResource(icon);
        int tint;
        if (THEME_CD.equals(themeId)) {
            tint = cdIsDark() ? 0xFFF2F2F7 : 0xFF282D36;
            boolean active = mode != MusicQueue.MODE_SEQUENTIAL;
            modeView.setImageTintList(ColorStateList.valueOf(active ? 0xFFFA243C : tint));
        } else {
            boolean active = mode != MusicQueue.MODE_SEQUENTIAL;
            modeView.setImageTintList(ColorStateList.valueOf(active ? 0xFFFA243C : 0xFF9AA7B8));
        }
        modeView.setContentDescription(MusicQueue.modeName(mode));
    }

    // ================= 流光 CD =================

    private boolean cdIsDark() {
        int surface = 0;
        try {
            surface = MusicStore.getCdSurface();
        } catch (Throwable ignored) {
        }
        if (surface == 1) return true;
        if (surface == 2) {
            int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return night == Configuration.UI_MODE_NIGHT_YES;
        }
        return false;
    }

    /** CdPalette：浅 Pearl(base #F4F2EE/mist #E9EFF5/ink #282D36/muted #747D89)，深 Space(base #14161C/ink #F2F2F7/muted #8E96A3) */
    private void applyCdPalette() {
        if (cdRoot == null) return;
        boolean dark = cdIsDark();
        int base = dark ? 0xFF14161C : 0xFFF4F2EE;
        int mist = dark ? 0xFF20232C : 0xFFE9EFF5;
        int ink = dark ? 0xFFF2F2F7 : 0xFF282D36;
        int muted = dark ? 0xFF8E96A3 : 0xFF747D89;
        // 表面切换 500ms bs4.a：根背景旧色→新色渐变（其余文字/盘面色即时跟随；表面细节透明度 tween(300, bs4.a) 的对应关系未证实，不另做）
        try {
            android.graphics.drawable.Drawable old = cdRoot.getBackground();
            int from = old instanceof android.graphics.drawable.ColorDrawable
                    ? ((android.graphics.drawable.ColorDrawable) old).getColor() : base;
            ValueAnimator bgAnim = ValueAnimator.ofObject(new ArgbEvaluator(), from, base);
            bgAnim.setDuration(500);
            bgAnim.setInterpolator(INTERP_FOSI);
            bgAnim.addUpdateListener(a -> cdRoot.setBackgroundColor((int) a.getAnimatedValue()));
            bgAnim.start();
        } catch (Throwable ignored) {
            cdRoot.setBackgroundColor(base);
        }
        if (titleView != null) titleView.setTextColor(ink);
        if (artistView != null) artistView.setTextColor(muted);
        // CD 歌词组件按表面明暗切方案：浅表面用浅色方案（深字）、深表面用默认（浅字）
        if (lyricView != null) lyricView.setLightScheme(!dark);
        // CD 时间行（redo3 D13）：9sp（布局侧）＋ muted×0.74
        int timeColor = (muted & 0x00FFFFFF) | 0xBD000000;
        if (positionView != null) positionView.setTextColor(timeColor);
        if (durationView != null) durationView.setTextColor(timeColor);
        if (seekView != null) {
            seekView.setProgressDrawable(getDrawable(dark
                    ? R.drawable.bg_player_seek_progress : R.drawable.bg_player_seek_progress_dark));
            seekView.setThumb(getDrawable(R.drawable.bg_player_seek_thumb));
            if (seekView.getThumb() != null) {
                seekView.getThumb().setTintList(ColorStateList.valueOf(ink));
            }
        }
        ColorStateList inkList = ColorStateList.valueOf(ink);
        if (prevView != null) prevView.setImageTintList(inkList);
        if (nextView != null) nextView.setImageTintList(inkList);
        if (playView != null) playView.setImageTintList(inkList);
        if (queueView != null) queueView.setImageTintList(inkList);
        // 盘面三段色：fresh 向主色混 0.22 / middle 0.12 / bottom 向 mist 侧混 0.08（CdPalette 梯度）
        if (cdDisc != null) {
            int fresh = blend(base, dominantColor, 0.22f);
            int middle = blend(base, dominantColor, 0.12f);
            int bottom = blend(mist, dominantColor, 0.08f);
            GradientDrawable disc = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, new int[]{fresh, middle, bottom});
            disc.setShape(GradientDrawable.OVAL);
            disc.setStroke((int) (2 * getResources().getDisplayMetrics().density),
                    dark ? 0xFF3A3F4A : 0xFFD8D2C8);
            cdDisc.setBackground(disc);
        }
        refreshModeIcon(MusicController.get().getMode());
    }

    private static int blend(int from, int to, float ratio) {
        int r = (int) (android.graphics.Color.red(from) + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * ratio);
        int g = (int) (android.graphics.Color.green(from) + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * ratio);
        int b = (int) (android.graphics.Color.blue(from) + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * ratio);
        return android.graphics.Color.rgb(r, g, b);
    }

    /** 切歌扫光触发（GZ CdSongChange）：CD 主题且非首绑时扫一次，方向取上一次跳歌方向（redo3 D8：skipDirection≤0 取 −1、否则 ＋1） */
    private void triggerCdSweep() {
        if (cdSweepView == null) return;
        if (!cdBoundOnce) {
            cdBoundOnce = true;
            return;
        }
        cdSweepView.sweep(lastSkipDirection);
    }

    /** CD 自旋 16°/s：整圈 22500ms 线性无限；暂停冻结角度（animator.pause 保角） */
    private void buildCdAnimator() {
        if (cdDisc == null) return;
        cdAnimator = ObjectAnimator.ofFloat(cdDisc, View.ROTATION, 0f, 360f);
        cdAnimator.setDuration(22500);
        cdAnimator.setRepeatCount(ValueAnimator.INFINITE);
        cdAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        updateRotations();
    }

    private void updateRotations() {
        if (cdAnimator == null) return;
        // GZ 口径：仅系统动画开启时跑（ANIMATOR_DURATION_SCALE＝0 时不启动）；暂停冻结角度（animator.pause 保角）
        if (playing && !destroyed && animationsEnabled()) {
            if (cdAnimator.isPaused()) cdAnimator.resume();
            else if (!cdAnimator.isStarted()) cdAnimator.start();
        } else if (cdAnimator.isRunning()) {
            cdAnimator.pause();
        }
    }

    /** 系统动画总开关（GZ CD／唱片架自旋口径：系统动画关闭时不转）。 */
    private boolean animationsEnabled() {
        try {
            return android.provider.Settings.Global.getFloat(getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
        } catch (Throwable ignored) {
            return true;
        }
    }

    // ================= 黑胶唱片架 =================

    private void scrollShelfToCurrent() {
        if (shelfList == null) return;
        int idx = MusicController.get().index();
        if (idx >= 0) shelfList.post(() -> shelfList.smoothScrollToPosition(idx));
    }

    /** 唱片架项：封面方卡（左）＋黑胶圆盘（右侧滑出）；当前项盘旋转 30°/s，其余静止；点按切歌 */
    private class ShelfAdapter extends RecyclerView.Adapter<ShelfAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            float d = getResources().getDisplayMetrics().density;
            FrameLayout root = new FrameLayout(parent.getContext());
            root.setLayoutParams(new RecyclerView.LayoutParams((int) (196 * d), (int) (196 * d)));
            ImageView disc = new ImageView(parent.getContext());
            FrameLayout.LayoutParams discLp = new FrameLayout.LayoutParams((int) (150 * d), (int) (150 * d));
            discLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            disc.setLayoutParams(discLp);
            disc.setScaleType(ImageView.ScaleType.CENTER_CROP);
            setCircle(disc);
            root.addView(disc);
            ImageView cover = new ImageView(parent.getContext());
            FrameLayout.LayoutParams coverLp = new FrameLayout.LayoutParams((int) (150 * d), (int) (150 * d));
            coverLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            cover.setLayoutParams(coverLp);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setElevation(6 * d);
            setRounded(cover, 12);
            root.addView(cover);
            return new Holder(root, cover, disc);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            List<MusicTrack> queue = MusicController.get().queue();
            if (position < 0 || position >= queue.size()) return;
            MusicTrack track = queue.get(position);
            String title = track.displayTitle();
            if (track.cover == null || track.cover.isEmpty()) {
                h.cover.setImageDrawable(new CoverTextDrawable(title, false,
                        12 * getResources().getDisplayMetrics().density));
                h.disc.setImageDrawable(new CoverTextDrawable(title, false,
                        75 * getResources().getDisplayMetrics().density));
            } else {
                ImgUtil.load(title, track.cover, h.cover);
                ImgUtil.load(title, track.cover, h.disc);
            }
            boolean current = position == MusicController.get().index();
            h.cover.setAlpha(current ? 1f : 0.75f);
            h.stopSpin();
            if (current && playing && animationsEnabled()) h.startSpin();
            h.itemView.setOnClickListener(v -> MusicController.get().playAt(position));
        }

        @Override
        public void onViewRecycled(@NonNull Holder h) {
            h.stopSpin();
            super.onViewRecycled(h);
        }

        @Override
        public int getItemCount() {
            try {
                return MusicController.get().queue().size();
            } catch (Throwable ignored) {
                return 0;
            }
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final ImageView disc;
            ObjectAnimator spin;

            Holder(View root, ImageView cover, ImageView disc) {
                super(root);
                this.cover = cover;
                this.disc = disc;
            }

            void startSpin() {
                stopSpin();
                spin = ObjectAnimator.ofFloat(disc, View.ROTATION, disc.getRotation(), disc.getRotation() + 360f);
                spin.setDuration(12000); // 30°/s
                spin.setRepeatCount(ValueAnimator.INFINITE);
                spin.setInterpolator(new android.view.animation.LinearInterpolator());
                spin.start();
            }

            void stopSpin() {
                if (spin != null) {
                    spin.cancel();
                    spin = null;
                }
            }
        }
    }

    // ================= 音量（Apple 主题，系统音量 STREAM_MUSIC） =================

    private AudioManager audioManager() {
        return (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    /** 进度拖动态换装：经典系 bg_player_seek_progress ↔ _active（0.42→0.78）；Apple seek6 ↔ _active（0.58→0.9）；CD 深色变体不换 */
    private void swapSeekDrawable(SeekBar bar, boolean active) {
        if (bar == null) return;
        try {
            if (THEME_APPLE.equals(themeId)) {
                bar.setProgressDrawable(getDrawable(active
                        ? R.drawable.bg_player_seek6_progress_active : R.drawable.bg_player_seek6_progress));
            } else if (!THEME_CD.equals(themeId)) {
                bar.setProgressDrawable(getDrawable(active
                        ? R.drawable.bg_player_seek_progress_active : R.drawable.bg_player_seek_progress));
            }
        } catch (Throwable ignored) {
        }
    }

    private void wireVolume() {
        if (volumeView == null) return;
        try {
            AudioManager am = audioManager();
            volumeView.setMax(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            volumeView.setProgress(am.getStreamVolume(AudioManager.STREAM_MUSIC));
            volumeView.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        try {
                            audioManager().setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                        } catch (Throwable ignored) {
                        }
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void refreshVolume() {
        if (volumeView == null) return;
        try {
            volumeView.setProgress(audioManager().getStreamVolume(AudioManager.STREAM_MUSIC));
        } catch (Throwable ignored) {
        }
    }

    // ================= 功能：收藏/下载/音质 =================

    private void refreshFavorite(MusicTrack track) {
        if (track == null || track.id == null) {
            favorited = false;
            paintFavoriteIcons();
            return;
        }
        MusicLibrary.isFavorite(track.id, fav -> {
            favorited = fav;
            paintFavoriteIcons();
        });
    }

    /** 收藏态刷所有主题里出现的收藏图标（胶囊心/唱片架心走 favBtnView；经典歌名行小爱心已收藏才显示，GZ F04） */
    private void paintFavoriteIcons() {
        int icon = favorited ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart;
        // 封面卡片玻璃条（GZ A4）：收藏心恒白——已收藏实心白 0.85、未收藏线框白 0.45，不染红
        int color = THEME_IMMERSIVE.equals(themeId)
                ? (favorited ? 0xD9FFFFFF : 0x73FFFFFF)
                : (favorited ? 0xFFF2433D : 0xFFFFFFFF);
        if (favBtnView != null) {
            favBtnView.setImageResource(icon);
            favBtnView.setImageTintList(ColorStateList.valueOf(color));
        }
        if (favHeartView != null) favHeartView.setVisibility(favorited ? View.VISIBLE : View.GONE);
        if (pageBtnFav != null) {
            pageBtnFav.setImageResource(icon);
            pageBtnFav.setImageTintList(ColorStateList.valueOf(favorited ? 0xFFFA243C : 0xFFFFFFFF));
        }
    }

    private void toggleFavorite(View fromView) {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        MusicLibrary.setFavorite(track, !favorited, () -> {
            favorited = !favorited;
            paintFavoriteIcons();
            Notify.show(favorited ? R.string.music_favorited : R.string.music_unfavorited);
            if (favorited && fromView != null) {
                float[] p = viewCenterInOverlay(fromView);
                binding.heartsOverlay.burstFavorite(p[0], p[1]);
            }
        });
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
        MusicDownloadStore.download(track);
    }

    private String realQualityText() {
        try {
            com.fongmi.android.tv.music.MusicAudioInfo info = MusicController.get().audioInfo();
            if (info != null) {
                String real = info.describe();
                if (real != null && !real.isEmpty()) return real;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 音质只显示服务实测（无损只显编码名、有损显编码＋真实码率）；取不到实测时
     * 经典音质筹整筹隐藏、Apple 第三行整行隐藏——所选档永不进显示（用户铁律）。
     */
    private void refreshQualityValue() {
        String real = realQualityText();
        if (qualityValueView != null) {
            qualityValueView.setText(real == null ? "" : real);
            View chip = qualityValueView.getParent() instanceof View ? (View) qualityValueView.getParent() : null;
            if (chip != null && chip.getId() == R.id.tpChipQuality) {
                chip.setVisibility(real == null ? View.GONE : View.VISIBLE);
            }
        }
        refreshMetaLine(MusicController.get().current());
    }

    private void showQualityDialog() {
        List<String> opts = new ArrayList<>();
        int[] values = {MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD, MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
        for (int v : values) opts.add(MusicStore.qualityName(v));
        MusicOptionsDialog.show(this, getString(R.string.music_quality), opts, MusicController.get().getQuality(), idx -> {
            if (MusicController.get().getQuality() != values[idx]) {
                MusicController.get().setQuality(values[idx]);
                MusicController.get().reloadQuality();
                refreshQualityValue();
                Notify.show("已切换" + MusicStore.qualityName(values[idx]));
            }
        });
    }

    // ================= 工具面板 =================

    private void showToolsSheet() {
        showToolsSheet(false);
    }

    private void showToolsSheetLyric() {
        showToolsSheet(true);
    }

    private void showToolsSheet(boolean lyricPanel) {
        try {
            MusicToolsSheetDialog.Listener listener = new MusicToolsSheetDialog.Listener() {
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
            };
            if (lyricPanel) MusicToolsSheetDialog.showLyricPanel(this, listener);
            else MusicToolsSheetDialog.show(this, listener);
        } catch (Throwable ignored) {
        }
    }

    private void pickPlaylist(MusicTrack track) {
        MusicLibrary.playlists(list -> {
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
        EditText et = new EditText(this);
        et.setHint(R.string.music_playlist_name_hint);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF5E6B7E);
        et.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.music_new_playlist)
                .setView(et)
                .setPositiveButton(android.R.string.ok, (d, w) -> cb.onResult(et.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
