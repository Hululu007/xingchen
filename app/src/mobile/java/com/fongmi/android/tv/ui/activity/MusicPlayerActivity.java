package com.fongmi.android.tv.ui.activity;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.LyricVersion;
import com.fongmi.android.tv.api.music.LxKeyIssue;
import com.fongmi.android.tv.ui.dialog.LxKeyIssueDialog;
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
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.KaraokeLyricsView;
import com.fongmi.android.tv.ui.custom.ShelfStageGeometry;
import com.fongmi.android.tv.ui.dialog.MusicFxDialog;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.ui.dialog.MusicQualityPanelDialog;
import com.fongmi.android.tv.ui.dialog.MusicQueueDialog;
import com.fongmi.android.tv.ui.dialog.MusicLyricVersionDialog;
import com.fongmi.android.tv.ui.dialog.MusicToolsSheetDialog;
import com.fongmi.android.tv.utils.CoverColor;
import com.fongmi.android.tv.utils.CoverTextDrawable;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import jp.wasabeef.glide.transformations.internal.FastBlur;

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
    /** 本页已试过在线封面的本地歌 id：无封面本地歌每首本页只试一次，不反复发搜 */
    // 在线封面去重集合：后台线程失败时也要移除键，HashSet 跨线程不安全，改并发集合
    private final java.util.Set<String> onlineCoverTried = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 背景合成态（五主题一起修，2026-10-21 规矩）：SHARP＝经典满幅顶封面（整幅真模糊＋四段取色渐变罩，无底缘涂抹带）；
     *  DARK＝Apple／卡片／CD／唱片架（独立物件不熔化，只压暗下半）；LYRICS＝歌词页（各主题共用，整幅压暗）。 */
    private static final int BG_SHARP = 0;
    private static final int BG_DARK = 1;
    private static final int BG_LYRICS = 2;
    private static final int BG_APPLE = 3;
    /** 五主题背景合成缓存。旧实现是两张图硬叠：tpCover 清晰图在上、ivBgBlur 64×64 拉伸糊底在下，
     *  交界一道硬边，且糊底无压暗、白字压亮底上看不清。各主题结构不同、单独对齐：
     *  经典封面满幅顶（唯一适用一层满幅）；Apple 是 24dp 边距＋阴影大卡、卡片是圆角卡、CD 是圆盘，
     *  三家的卡边／盘边是设计本身，硬套方区熔化会多出一块清楚方区，只压暗；唱片架无单张大封面、
     *  标题是深色墨字，顶部保持亮（保深色字可读）、只压暗下半（白时间行＋白主控图标）。 */
    private Bitmap bgSoftBitmap;
    private String bgSoftUrl;
    private Bitmap bgFullBitmap;
    private String bgFullUrl;
    private boolean bgCompositeApplied;
    private int bgCompositeMode = -1;
    private String bgCompositeUrl;
    /** M5：上次合成贴图时所用的氛围色（buildCompositeAsync 主线程快照的 fAmbient）。
     * -1＝尚未合成过（只作哨兵；判定同时要求 bgCompositeApplied，贴过图后必已被赋值）。 */
    private int bgCompositeAmbient = -1;
    /** 取色静态缓存（2026-10-22 每次进页颜色不一样根因：取色只跟页面实例，进页重取、取的图每次不一，有时软底回调没贴上就一直停在兜底黑；同封面地址取一次就固定，不再重取）。 */
    private static final java.util.Map<String, Integer> sAmbientCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Integer> sDominantCache = new java.util.concurrent.ConcurrentHashMap<>();
    private TextView titleView;
    private TextView artistView;
    private TextView albumView;
    /** 合并歌手行分隔心（封面卡片／CD／唱片架／Apple 四主题新加；经典主题无此视图，沿用原有爱心收藏键） */
    private View albumSepView;
    private ImageView playTopView;
    private ImageView favHeartView;
    private SeekBar seekView;
    private SeekBar volumeView;
    /** GZ 式迷你进度条（activity 层、五主题共用）：默认短条，点展开，闲置缩回。 */
    private com.fongmi.android.tv.ui.custom.MusicMiniProgressView miniProgressView;
    /** 底部进度栈是否收起（默认 true＝短条态）。 */
    private boolean bottomCollapsed = true;
    /** 闲置缩回超时：星辰侧自定 3s，GZ 代码侧未抠到（无具名迷你条组件、无 autoHide 计时），待真机定手感。 */
    private static final long MINI_HIDE_MS = 3000L;
    private final Handler miniHideHandler = new Handler(Looper.getMainLooper());
    private final Runnable collapseRunnable = this::collapseBottomStack;
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
    private View cdSheenView;
    private View cdDetailView;
    private ImageView cdDetailThumb;
    private TextView cdDetailTitle;
    private TextView cdDetailArtist;
    private View cdDetailBtn;
    /** 流光 CD 摘要行（wave2：瓜子独立摘要行，主行 16sp／副行 12sp；收起态显示、展开态隐藏）。 */
    private View cdSummaryView;
    private TextView cdSummaryMain;
    private TextView cdSummarySub;
    /** 迷你条底边距：主题基准 dp（封面卡片 68＝12 间隔＋48 玻璃条＋8 间隙，其余 12）＋系统导航条 inset。 */
    private int miniNavInsetPx;
    /** 上次绑定过的曲目（onResume 用：后台切歌后回页才重绑，避免每次回页重取词）。 */
    private MusicTrack lastBoundTrack;
    private android.animation.AnimatorSet cdSheenAnim;
    private android.animation.ValueAnimator cdDetailAnim;
    /** 流光 CD 四路流动相位初值（瓜子 rememberCdMotion，LuminousCdPlayerKt:3707 起）：
     * spin＝0、flow＝0、caustic＝42、undertow＝214；四路速率在瓜子侧是随节拍包络变化的实时回调值，
     * 无固定字面速率（见 buildCdAnimator），故星辰侧不编速率、不设连续推进动画器。 */
    /** D5：拖动浮动时间 label（CD 与唱片架共用同一套：主进度条拖动时跟手显示，松手隐藏） */
    private TextView scrubTimeLabel;
    /** A1：Apple 主题经右划打开的歌词页（复用通用歌词页载体、Apple 居中呈现，见 applyPageLyricStyle） */
    private boolean appleLyricsPage;
    private float cdDetailAnimValue;
    private float cdDetailAnimTarget = -1f;
    private CdDetailContent cdDetailContent = CdDetailContent.LYRICS;
    private boolean cdPaletteFirstApply = true;
    private boolean favoriteBusy;
    private int shelfPrevIndex = -1;
    /** B1：唱片架槽位弹簧（切歌定位由此驱动，RecyclerView 只作视图复用） */
    private com.fongmi.android.tv.ui.custom.ShelfSlotAnimator shelfSlotAnimator;
    /** 3 槽舞台切歌插值：旧内容整体平移一槽（下一首 shift 0→−1、上一首 0→＋1），落位同帧重绑新内容；
     * shift＝0 时各槽即静态槽位；动画帧只改位移／缩放（GPU 属性），不触发布局、不重绑。 */
    private float shelfSlotShift;
    /** 槽位弹簧进行中：新切歌到达时先取消旧弹簧、直接落位重绑，不叠加第二根弹簧。 */
    private boolean shelfShiftRunning;
    /** 唱片架歌词面（GZ shelfInnerPage）：切歌时 300＋150／150／0.98 切换动画落在这块面上，不落槽根。 */
    private View shelfInnerPageView;
    private android.animation.AnimatorSet shelfSwitchAnim;
    /** 唱片架歌词面待入场：相邻切歌时歌词尚在途，先不跑淡入淡出，等歌词到达事件再入场。 */
    private boolean shelfLyricSwitchPending;
    /** 待入场兜底：取词超时（1500ms，星辰自定兜底、非瓜子值）仍按现有动画走，不把界面挂死。 */
    private Runnable shelfLyricTimeoutRunnable;
    private android.animation.AnimatorSet coverExpandAnim;
    private RecyclerView shelfList;
    private ShelfAdapter shelfAdapter;

    private enum CdDetailContent { LYRICS, SONG_INFO }

    // ===== 状态 =====
    private final List<LyricsLine> lyricMains = new ArrayList<>();
    /** 上次喂词时过滤词曲信息开关的值（onResume 比对：设置页改过就重喂一次，不用等切歌） */
    private boolean lyricFilterMetaApplied;
    private boolean lyricFilterMetaInit;
    private final List<String> lyricTrans = new ArrayList<>();
    private final List<String> lyricRomas = new ArrayList<>();
    /** 翻译／音译逐词表（M-02：与 lyricMains 按下标对齐，元素为该行逐词表，空表＝无逐词）。 */
    private final List<List<com.fongmi.android.tv.player.lyrics.LyricsWord>> lyricTransWords = new ArrayList<>();
    private final List<List<com.fongmi.android.tv.player.lyrics.LyricsWord>> lyricRomaWords = new ArrayList<>();
    private int lyricIndex = -1;
    /** 取词空态（取回为空时 true；onResume 同曲不重绑时靠它重试，2026-10-22 歌词有时不出根因之一）。 */
    private boolean lyricEmpty;
    /** 取词进行中（防 onResume 重试与进行中的取词撞车）。 */
    private boolean lyricLoading;
    /** B2：当前曲的歌词版本列表（lyricRichAll 全量聚合，只含实际拿到的版本）；空＝单版本或未聚合 */
    private final List<LyricVersion> currentLyricVersions = new ArrayList<>();
    /** B2：当前应用歌词的 rawLrc.trim()（版本筛选条定位当前版用） */
    private String currentLyricKey;
    /** B8：当前应用歌词的档位元数据（"AMLL" 时档位筹显 AMLL） */
    private String currentLyricFormat;
    /** B2：全屏歌词页内的版本筛选条（代码方式插在 pgLyricsView 上方，仅多版本时显示） */
    private HorizontalScrollView lyricVersionRow;
    private LinearLayout lyricVersionChips;
    private int lyricVersionSel = -1;
    private boolean seeking;
    private boolean favorited;
    private boolean playing;
    private int ambientColor = CoverColor.FALLBACK_COLOR;
    private int dominantColor = CoverColor.FALLBACK_COLOR;
    private ValueAnimator ambientAnimator;
    /** 进度条补间（GZ 通用进度 animateFloatAsState tween(200, bs4.a)：tick 间平滑滑动而非跳步） */
    private ValueAnimator seekAnim;
    /** 封面卡片展开态轨高补间（GZ PlaybackScrubber 4dp↔5dp 用 150ms tween；只限沉浸，其余主题走原即时切换）。 */
    private ValueAnimator seekTrackHeightAnim;
    // GZ 全局缓动（defpackage/bs4）：a=FastOutSlowIn(0.2,0,0,1)、e=Emphasized(0.05,0.7,0.1,1)、b=LinearOutSlowIn(0,0,0,1)、f=滑出(0.3,0,0.8,0.15)
    private static final android.view.animation.Interpolator INTERP_FOSI = new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final android.view.animation.Interpolator INTERP_EMPH = new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private static final android.view.animation.Interpolator INTERP_LOSI = new android.view.animation.PathInterpolator(0f, 0f, 0f, 1f);
    private static final android.view.animation.Interpolator INTERP_EXIT = new android.view.animation.PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    /** bs4.c (0.3,0,1,1)：歌名／歌手出场交叉淡化 fadeOut 150ms 用（redo3 M7／F2）。 */
    private static final android.view.animation.Interpolator INTERP_OUT_C = new android.view.animation.PathInterpolator(0.3f, 0f, 1f, 1f);
    /** bs4.d 原路径（GZ defpackage/bs4.java：Emphasized 路径缓动，moveTo(0,0) 后两段 cubicTo；x 单调递增，可作 PathInterpolator）：CD 歌词展开 600／收起 500ms 用（redo3 D7／F4）。扫光动画在 CdSweepView 内（不在本轮文件范围），仍用同族贝塞尔，D8 缓动仍近似。 */
    private static final android.view.animation.Interpolator INTERP_CD_EXPAND = createBs4dInterpolator();

    private static android.view.animation.Interpolator createBs4dInterpolator() {
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(0f, 0f);
        path.cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f);
        path.cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f);
        return new android.view.animation.PathInterpolator(path);
    }
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
    private TextView pageBtnTr;
    private TextView pageChipQuality;
    private TextView pageChipWord;
    private SeekBar pageSeek;
    private boolean lyricsPageOpen;
    private boolean pageHasWordLines;
    private ObjectAnimator cdAnimator;
    private com.fongmi.android.tv.ui.custom.CdSweepView cdSweepView;
    private boolean cdBoundOnce;
    private boolean destroyed;
    /** 后台播放分家：onStop 因「音乐后台播放关」暂停时置位，回页只续同一首（照视频侧口径）。 */
    private boolean bgPausedMusic;
    private String bgPausedTrackKey;
    /** 上一次跳歌方向（redo3 D8：瓜子 lastSkipDirection 口径，≤0 取 −1、否则 ＋1；CD 扫光方向用，默认 ＋1）。 */
    private int lastSkipDirection = 1;
    /** 流光 CD 歌词展开态（redo3 F4）与展开动画。 */
    private boolean cdLyricsExpanded;
    private ValueAnimator cdExpandAnimator;
    /** 封面回中弹簧（redo3 M5：spring(阻尼比 0.9, 刚度 1400) 半隐式欧拉积分）状态。 */
    private android.view.Choreographer.FrameCallback coverSpringCallback;
    private float coverSpringVx;
    private float coverSpringVy;
    private long coverSpringLastMs;
    /** 双击封面缩放回落弹簧（瓜子 CoverArt 回落：spring 阻尼 0.9／刚度 300，半隐式欧拉积分，目标缩放 1）状态。 */
    private android.view.Choreographer.FrameCallback coverScaleSpringCallback;
    private float coverScaleSpringV;
    private long coverScaleSpringLastMs;

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
            // 第三波残留 C3：媒体落位后重刷收藏心（加载中禁用态→启用态），不重绑整页。
            runOnUiThread(() -> paintFavoriteIcons());
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
            runOnUiThread(() -> {
                refreshModeIcon(mode);
                refreshAppleTransportAlpha();
            });
        }

        @Override
        public void onAudioInfoChanged() {
            runOnUiThread(() -> {
                refreshQualityValue();
                refreshPageQualityChip();
            });
        }

        @Override
        public void onKeyIssue(String platform, String detail) {
            // B4：取链路密钥问题——当前曲是落雪歌才弹（防过期事件）；重试＝重播当前曲
            runOnUiThread(() -> showKeyIssue(platform, detail, () -> {
                int idx = MusicController.get().index();
                if (idx >= 0) MusicController.get().playAt(idx);
            }));
        }
    };

    // 播放页顶亮度跟主题走：唱片架顶固定米色恒亮，首帧就用深色图标；其余主题顶是封面氛围（亮暗随封面变），
    // 首帧先按旧口径白图标，applyTheme／氛围变色时 syncStatusBarIcons 按实测亮度纠正（切主题不重建页也生效）。
    @Override
    protected boolean useDarkSystemIcons() {
        try {
            return THEME_SHELF.equals(MusicStore.getPlayerTheme());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 状态栏图标跟顶亮度走：顶亮用深色图标，顶暗用白色图标。
     * 旧口径全页白图标，亮顶上直接隐形＝“状态栏看不清”的根因；导航栏不动。 */
    private void syncStatusBarIcons(int topColor) {
        try {
            int top = THEME_SHELF.equals(themeId) ? 0xFFF1F0ED : topColor;
            boolean light = androidx.core.graphics.ColorUtils.calculateLuminance(top) > 0.5;
            new androidx.core.view.WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView())
                    .setAppearanceLightStatusBars(light);
        } catch (Throwable ignored) {
        }
    }

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
        // GZ 式迷你进度条：activity 层一个，五主题共用；点一下展开完整进度栈，闲置缩回。
        miniProgressView = binding.getRoot().findViewById(R.id.miniProgress);
        if (miniProgressView != null) {
            miniProgressView.setOnClickListener(v -> expandBottomStack());
            // T4：读屏文案（原无 contentDescription，可点但读屏无描述）。
            miniProgressView.setContentDescription(getString(R.string.music_wave2_mini_progress_desc));
            // T4：三键导航下底边距＝主题基准＋系统导航条实测 inset（手势导航 inset 为 0，不写死 48dp）。
            ViewCompat.setOnApplyWindowInsetsListener(miniProgressView, (v, insets) -> {
                miniNavInsetPx = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                applyMiniProgressMargin();
                return insets;
            });
        }
        applyTheme();
        bindTrack(MusicController.get().current());
        setPlaying(MusicController.get().isPlaying());
        refreshModeIcon(MusicController.get().getMode());
        refreshQualityValue();
        syncBottomStack();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // wave2：onPause 已摘监听，回页先去重重挂（initView 已挂一次，直接 add 会双份）。
        MusicController.get().removeListener(listener);
        MusicController.get().addListener(listener);
        // 回页重同步播放态：onPause 摘监听期间错过的暂停（如视频抢焦点停了歌）不会再回调，
        // 不在这里重读服务态，按钮就一直停在旧的播放态——这就是视频打断后歌停了按钮没变的根因。
        setPlaying(MusicController.get().isPlaying());
        // 后台播放分家：只有 onStop 因音乐后台关亲手暂停的才续，且必须是同一首；
        // 用户在外面已手动播/停（isPlaying 已变）或已切歌，都不抢。
        if (bgPausedMusic) {
            bgPausedMusic = false;
            String key = bgPausedTrackKey;
            bgPausedTrackKey = null;
            try {
                MusicTrack curBg = MusicController.get().current();
                if (curBg != null && key != null
                        && key.equals(String.valueOf(curBg.id) + "|" + String.valueOf(curBg.platform))
                        && !MusicController.get().isPlaying()) {
                    MusicController.get().play();
                }
            } catch (Throwable ignored) {
            }
        }
        // wave2：后台切过歌（监听摘掉期间错过的切歌）才重绑；同曲不重绑，但上次取词空了就重试
        // （首取失败／返回空后，再进页以前不重试，只有切歌或手动重搜才重试，是歌词有时不出的另一根因；进行中不重复取）。
        MusicTrack cur = MusicController.get().current();
        if (cur != lastBoundTrack && (cur == null || lastBoundTrack == null
                || !String.valueOf(cur.id).equals(String.valueOf(lastBoundTrack.id))
                || !String.valueOf(cur.platform).equals(String.valueOf(lastBoundTrack.platform)))) {
            bindTrack(cur);
        } else if (cur != null && lastBoundTrack != null
                && String.valueOf(cur.id).equals(String.valueOf(lastBoundTrack.id))
                && String.valueOf(cur.platform).equals(String.valueOf(lastBoundTrack.platform))
                && lyricEmpty && !lyricLoading) {
            loadLyric(cur, false);
        }
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(this).pageResumed("music");
        } catch (Throwable ignored) {
        }
        // 主题可能在别处被改（或工具面板回调漏达）：主题 id 变了就换装
        String theme = MusicStore.getPlayerTheme();
        if (theme != null && !theme.equals(themeId)) applyTheme();
        else {
            applyLyricStyle();
            // L9：回页时全屏歌词页样式也同步（设置页改完回来全屏页不再滞后）。
            applyPageLyricStyle();
            if (lyricView != null) lyricView.setPlaying(playing);
            // U-05：从设置页回来时按新偏移重算一次（暂停态下逐帧循环不跑，单行与 KLV 静态显示靠这次刷新对齐新偏移）
            refreshLyricOffset();
        }
        // 过滤词曲信息开关在设置页改过：与上次喂词时的值不同就重喂一次（setLines 入口重新过滤），不用等切歌
        boolean filterMeta = false;
        try {
            filterMeta = MusicStore.isFilterLyricMeta();
        } catch (Exception ignored) {
        }
        if (!lyricFilterMetaInit) {
            lyricFilterMetaInit = true;
            lyricFilterMetaApplied = filterMeta;
        } else if (filterMeta != lyricFilterMetaApplied) {
            lyricFilterMetaApplied = filterMeta;
            if (!lyricMains.isEmpty()) feedLyricView();
        }
        applyKeepScreenOn();
        refreshVolume();
        updateRotations();
    }

    @Override
    protected void onPause() {
        // wave2：后台摘监听——从播放页进随心听后不再触发本页 bindTrack/loadLyric 占取词池槽；
        // 服务本身不解绑，后台仍播；回页由 onResume 去重重挂＋按需重绑（onDestroy 摘监听不动）。
        MusicController.get().removeListener(listener);
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(this).pagePaused("music");
        } catch (Throwable ignored) {
        }
        if (cdAnimator != null && cdAnimator.isRunning()) cdAnimator.pause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 后台播放分家：音乐只认音乐自己那份开关（PlayerSetting.isMusicBackgroundOff，
        // 默认开＝常驻，离开照常播、通知照常在，行为与从前一字不变）。
        // 关时才走视频侧同款口径：离开播放页就暂停；只有页面没在关（isFinishing 为假）
        // 才记下是哪首歌，回页由 onResume 续同一首——关页离开不留假续播标记（同视频侧）。
        bgPausedMusic = false;
        bgPausedTrackKey = null;
        try {
            if (PlayerSetting.isMusicBackgroundOff() && MusicController.get().isPlaying()) {
                MusicTrack curBg = MusicController.get().current();
                if (curBg != null && !isFinishing()) {
                    bgPausedTrackKey = String.valueOf(curBg.id) + "|" + String.valueOf(curBg.platform);
                    bgPausedMusic = true;
                }
                MusicController.get().pause();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        cancelMiniHideTimer();
        MusicController.get().removeListener(listener);
        MusicController.get().unbind();
        if (ambientAnimator != null) ambientAnimator.cancel();
        if (cdAnimator != null) cdAnimator.cancel();
        if (shelfSlotAnimator != null) shelfSlotAnimator.cancel();
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
        // 必-1：歌词页开着换主题时旗标同步新主题，不滞留旧主题的呈现（开着的内页对齐随之重刷）。
        if (lyricsPageOpen) {
            appleLyricsPage = THEME_APPLE.equals(themeId);
            applyPageLyricStyle();
        }
        if (cdAnimator != null) {
            cdAnimator.cancel();
            cdAnimator = null;
        }
        binding.themeContainer.removeAllViews();
        themeRoot = LayoutInflater.from(this).inflate(layout, binding.themeContainer, false);
        // 主题切换过渡（星辰自加：淡入＋0.98 微缩放、260ms；瓜子侧换主题无过渡字面量、属条件重组直切，
        // 故 260ms 不是瓜子值；保留理由见经典动效对照表——共享入口不动、直切更生硬，不为对齐硬改）
        themeRoot.setAlpha(0f);
        themeRoot.setScaleX(0.98f);
        themeRoot.setScaleY(0.98f);
        themeRoot.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(INTERP_FOSI).start();
        binding.themeContainer.addView(themeRoot);
        // 唱片架头行避挖孔：全面屏下布局顶从屏顶起算，唱片架 20dp 顶垫＋居中歌名会顶进居中前置摄像头；
        // 按内页既有口径（顶内边距＝状态栏高，根布局不吃 fitsSystemWindows）给架根补状态栏高（状态栏高已含挖孔区）。
        if (THEME_SHELF.equals(themeId) && themeRoot != null) {
            int statusBarHeight = 0;
            int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) statusBarHeight = getResources().getDimensionPixelSize(resId);
            themeRoot.setPadding(themeRoot.getPaddingLeft(), themeRoot.getPaddingTop() + statusBarHeight,
                    themeRoot.getPaddingRight(), themeRoot.getPaddingBottom());
        }
        grabThemeViews();
        // 换主题即清经典 RenderEffect：经典糊底只属于经典主题，切离时不等新合成回调，
        // 否则新主题软底先贴图的那段窗口会带着 24dp 模糊（无封面分支同理，见 updateBackground）。
        clearClassicRenderEffect();
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
        applyFluidBg();
        applyLandscapeLayout();
        attachPageSwipe();
        syncBottomStack();
        syncStatusBarIcons(ambientColor);
    }

    /**
     * GZ 式迷你进度条：底部进度栈的展开／缩回（五主题同一套逻辑，栈视图按主题实际存在取，缺的跳过）。
     * 栈＝进度相关视图：细进度线（tpSeek／pgSeek）、格子（tpChipQuality／tpChipLyric／pgChipQuality／pgChipWord）、
     * 时间字（tpPosition／tpDuration／tpPositionTop／tpDurationTop）。下一首块已按用户令拿掉，不在栈内。
     * 不动歌词、头部、队列、音质面板；传输键（播放／上一首／下一首／队列／模式）不在栈内，收起态仍保留。
     * 各主题栈结构已逐个对过：经典＝筹＋顶盒时间＋细线；Apple＝只有细线；封面卡片＝全套；
     * 流光 CD＝细线＋底时间行（盘心详情 tpCdDetail 是盘面信息不是底部栈，不动）；黑胶架＝细线＋底时间行；
     * 歌词内页＝pgSeek＋两胶囊（与主题无关，内页开着时同样收起）。
     */
    private void collectStackViews(java.util.List<View> out) {
        if (seekView != null) out.add(seekView);
        if (positionView != null) out.add(positionView);
        if (positionTopView != null) out.add(positionTopView);
        if (durationTopView != null) out.add(durationTopView);
        if (durationView != null) out.add(durationView);
        View chipQuality = find(R.id.tpChipQuality);
        if (chipQuality != null) out.add(chipQuality);
        View chipLyric = find(R.id.tpChipLyric);
        if (chipLyric != null) out.add(chipLyric);
        if (pageSeek != null) out.add(pageSeek);
        if (pageChipQuality != null) out.add(pageChipQuality);
        if (pageChipWord != null) out.add(pageChipWord);
    }

    private void syncBottomStack() {
        java.util.List<View> stack = new java.util.ArrayList<>();
        collectStackViews(stack);
        applyMiniProgressMargin();
        if (bottomCollapsed) {
            for (View v : stack) v.setVisibility(View.GONE);
            if (miniProgressView != null) miniProgressView.setVisibility(View.VISIBLE);
        } else {
            if (miniProgressView != null) miniProgressView.setVisibility(View.GONE);
            if (seekView != null) seekView.setVisibility(View.VISIBLE);
            if (pageSeek != null) pageSeek.setVisibility(View.VISIBLE);
            if (positionView != null) positionView.setVisibility(View.VISIBLE);
            if (positionTopView != null) positionTopView.setVisibility(View.VISIBLE);
            if (durationTopView != null) durationTopView.setVisibility(View.VISIBLE);
            if (durationView != null) durationView.setVisibility(View.VISIBLE);
            View chipLyric = find(R.id.tpChipLyric);
            if (chipLyric != null) chipLyric.setVisibility(View.VISIBLE);
            // 态相关视图不无脑置显，重跑各自刷新方法按实态定显隐（无线上音质时仍藏）。
            refreshQualityValue();
            refreshPageQualityChip();
            refreshPageWordChip();
        }
    }

    /** 迷你条底边距（wave2＋T4）：封面卡片放底部簇（玻璃条）上方，不藏歌词条／键行；
     * 其余主题 12dp；再叠加系统导航条 inset（手势导航为 0）。 */
    private void applyMiniProgressMargin() {
        if (miniProgressView == null) return;
        int baseDp = THEME_IMMERSIVE.equals(themeId) ? 68 : 12;
        int marginPx = miniNavInsetPx + Math.round(baseDp * getResources().getDisplayMetrics().density);
        ViewGroup.LayoutParams lp = miniProgressView.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            if (mlp.bottomMargin != marginPx) {
                mlp.bottomMargin = marginPx;
                miniProgressView.setLayoutParams(mlp);
            }
        }
    }

    /** 点迷你条展开：栈恢复＋闲置计时重起（3s 星辰侧自定，非 GZ 值）。 */
    private void expandBottomStack() {
        bottomCollapsed = false;
        syncBottomStack();
        restartMiniHideTimer();
    }

    /** 闲置缩回：只藏进度栈，迷你条复现；歌词／头部／队列／传输键不动。 */
    private void collapseBottomStack() {
        bottomCollapsed = true;
        syncBottomStack();
    }

    private void restartMiniHideTimer() {
        miniHideHandler.removeCallbacks(collapseRunnable);
        miniHideHandler.postDelayed(collapseRunnable, MINI_HIDE_MS);
    }

    private void cancelMiniHideTimer() {
        miniHideHandler.removeCallbacks(collapseRunnable);
    }

    /** 播放页整页竖滑切歌接线（GZ coverAxisLock 模型）：拦截层在 themeContainer（PlayerPageSwipeLayout），
     * 五主题通用——封面区、歌词区、标题区、底部区竖滑都切歌；横向手势（歌词页滑入滑出、进度拖动、
     * 架列横滑、封面卡片／CD 横滑）轴锁分流不受影响；内嵌歌词不再竖滚（KaraokeLyricsView coverMode 门），
     * 手动滚歌词只留全屏歌词页。旧空白区监听已删（与拦截层同手势会双切）。 */
    private void attachPageSwipe() {
        if (binding.themeContainer == null) return;
        binding.themeContainer.setTranslationY(0f);
        binding.themeContainer.setOnPageSwipeListener(next -> {
            lastSkipDirection = next ? 1 : -1;
            if (next) MusicController.get().next();
            else MusicController.get().prev();
        });
        TextView hint = findViewById(R.id.swipeHint);
        if (hint != null) {
            binding.themeContainer.setSwipeHintView(hint,
                    getString(R.string.music_next), getString(R.string.music_prev));
        }
        // L6/A1：根／主题容器层 pager 横滑观察（封面切歌区、进度条、架列等已消费触摸的控件到不了这里，
        // 天然与切歌轴锁分流；歌词区原有 72dp／1.5 门保留，见 attachPagerSwipe）。
        attachRootPagerSwipe(binding.themeContainer);
    }

    private <T extends View> T find(int id) {
        return themeRoot == null ? null : themeRoot.findViewById(id);
    }

    private void grabThemeViews() {
        // 换主题重建即重置 CD 首绑防扫：cdBoundOnce 只防本主题内首绑，切到别的主题再切回
        // CD 时旧值恒真会误扫一次切歌扫光；重建时回到“未绑过”态，首绑跳过、真切歌才扫。
        cdBoundOnce = false;
        cancelShelfLyricSwitchPending();
        coverView = find(R.id.tpCover);
        coverExitView = find(R.id.tpCoverExit);
        cdLyricsExpanded = false;
        coverExpanded = false;
        if (cdExpandAnimator != null) {
            cdExpandAnimator.cancel();
            cdExpandAnimator = null;
        }
        titleView = find(R.id.tpTitle);
        artistView = find(R.id.tpArtist);
        albumView = find(R.id.tpAlbum);
        albumSepView = find(R.id.tpAlbumSep);
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
        cdPaletteFirstApply = true;
        cdDisc = find(R.id.tpCdDisc);
        cdSheenView = find(R.id.tpCdSheen);
        cdDetailView = find(R.id.tpCdDetail);
        cdDetailThumb = find(R.id.tpCdDetailThumb);
        cdDetailTitle = find(R.id.tpCdDetailTitle);
        cdDetailArtist = find(R.id.tpCdDetailArtist);
        cdDetailBtn = find(R.id.tpCdUtilInfo);
        cdDetailAnimValue = 0f;
        cdDetailAnimTarget = -1f;
        cdDetailContent = CdDetailContent.LYRICS;
        cdSummaryView = find(R.id.tpCdSummary);
        cdSummaryMain = find(R.id.tpCdSummaryMain);
        cdSummarySub = find(R.id.tpCdSummarySub);
        cdSweepView = find(R.id.tpCdSweep);
        shelfList = find(R.id.tpShelfList);
        shelfInnerPageView = find(R.id.tpShelfInnerPage);
    }

    private void wireThemeViews() {
        if (playView != null) playView.setOnClickListener(v -> MusicController.get().toggle());
        // 经典主题 SongMeta 三键内的播放/暂停键（图标尺寸 28/25dp 在 setPlaying 里按态切 padding）
        if (playTopView != null) playTopView.setOnClickListener(v -> MusicController.get().toggle());
        // 歌名行收藏小爱心：第二行“歌手 ♡ 专辑”里恒在（GZ 截图标准），点按切换收藏
        if (favHeartView != null) favHeartView.setOnClickListener(v -> toggleFavorite(v));
        // 歌手行两段可点（用户令）：点歌手进歌手搜索、点专辑进专辑搜索；只是进搜索填名字自动搜，搜索方式不动
        if (artistView != null) artistView.setOnClickListener(v -> {
            MusicTrack t = MusicController.get().current();
            String a = t == null ? null : t.displayArtist();
            if (a != null && !a.isEmpty()) MusicListActivity.startSearch(this, a);
        });
        if (albumView != null) albumView.setOnClickListener(v -> {
            MusicTrack t = MusicController.get().current();
            String al = t == null ? null : t.album;
            if (al != null && !al.isEmpty()) MusicListActivity.startSearch(this, al);
        });
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

        // 封面圆角（redo3 第四轮 C7）：经典 0dp 方形（GZ 经典封面传 0.0f、无 shape 裁剪），其余主题按各自版式；出场封面层与主封面同形裁剪
        if (coverView != null) {
            if (THEME_CD.equals(themeId)) setCircle(coverView);
            else setRounded(coverView, THEME_CLASSIC.equals(themeId) ? 0 : THEME_APPLE.equals(themeId) ? 20 : THEME_IMMERSIVE.equals(themeId) ? 22 : 24);
        }
        if (coverExitView != null) {
            if (THEME_CD.equals(themeId)) setCircle(coverExitView);
            else setRounded(coverExitView, THEME_CLASSIC.equals(themeId) ? 0 : THEME_APPLE.equals(themeId) ? 20 : THEME_IMMERSIVE.equals(themeId) ? 22 : 24);
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
                    // 不在这里重计时：拖动中 fromUser 也会反复触发，拖住 3 秒不动会在拖动中途缩回；
                    // 点按／拖动结束统一由 onStopTrackingTouch 重计时。
                    // D5：拖动中浮动时间 label 跟手显示当前拖动时间（CD 与唱片架共用本套监听）。
                    if (fromUser && (THEME_CD.equals(themeId) || THEME_SHELF.equals(themeId))) updateScrubTimeLabel(seekBar, progress);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    seeking = true;
                    // 拖动期间保持展开，不缩回。
                    cancelMiniHideTimer();
                    swapSeekDrawable(seekBar, true);
                    setSeekTrackHeight(seekBar, 5f);
                    setTopTimesAlpha(0.72f);
                    // D5 守门：浮动时间 label 只在 CD 与唱片架主题显示，其余主题不建 label。
                    if (THEME_CD.equals(themeId) || THEME_SHELF.equals(themeId)) showScrubTimeLabel(seekBar, seekBar.getProgress());
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    seeking = false;
                    swapSeekDrawable(seekBar, false);
                    setSeekTrackHeight(seekBar, 4f);
                    setTopTimesAlpha(0f);
                    hideScrubTimeLabel();
                    long dur = MusicController.get().duration();
                    MusicController.get().seekTo(dur * seekBar.getProgress() / 1000);
                    // 松手后重计时，闲置 3s 再缩回。
                    restartMiniHideTimer();
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
        // 点行回调——五主题一致：点行只 seek 到该行时间戳，不开歌词页（2026-10-21 他定：播放页点歌词＝快进，进歌词页只走右划；G04 经典点行开页口径作废）；
        // 流光 CD 未展开时点歌词区先展开（GZ CdLyricSummary 点按展开口径），展开后点行才 seek
        if (lyricView != null) {
            if (THEME_CLASSIC.equals(themeId) || THEME_CD.equals(themeId) || THEME_SHELF.equals(themeId) || THEME_IMMERSIVE.equals(themeId)) {
                lyricView.setCoverMode(true);
            }
            if (THEME_SHELF.equals(themeId)) lyricView.setLightScheme(true);
            // 空态字样主题覆盖（redo3 第三轮 S8／V9）：架 16sp／行高 25sp／无图标／ShelfMuted；
            // 封面卡片展开态 14sp／无图标／白 0.65；其余主题走 KLV 默认（图标＋12sp）。
            if (THEME_SHELF.equals(themeId)) lyricView.setEmptyTextStyle(16f, 25f, true, 0xFF86857E);
            else if (THEME_IMMERSIVE.equals(themeId)) lyricView.setEmptyTextStyle(14f, 0f, true, 0xA6FFFFFF);
            lyricView.setOnLineClickListener((index, positionMs) -> {
                if (THEME_CD.equals(themeId)) {
                    // 流光 CD：未展开时点歌词区先展开（瓜子 CdLyricSummary 点按展开口径），展开后点行才 seek
                    if (!cdLyricsExpanded) toggleCdLyricsExpanded();
                    else MusicController.get().seekTo(positionMs);
                } else MusicController.get().seekTo(positionMs);
            });
            lyricView.setOnEmptyTapListener(() -> {
                if (THEME_CD.equals(themeId)) toggleCdLyricsExpanded();
            });
            // 歌词区竖滑切歌：快甩切歌、慢拖滚动（分流在 KaraokeLyricsView 内，门同封面手势）；
            // 经典竖滑：上滑＝下一首、下滑＝上一首；切后新曲绑定走控制器回调（bindTrackViews 重刷）。
            lyricView.setOnTrackSwipeListener(next -> {
                lastSkipDirection = next ? 1 : -1;
                if (next) MusicController.get().next();
                else MusicController.get().prev();
            });
            attachPagerSwipe(lyricView, true);
        }

        // Apple 专属：歌词入口/音质入口＋运输键按压缩放（上/下一首 0.88、播放 0.9）；
        // 功能行首项是瓜子“歌词选择”菜单：开星辰现有歌词版本选择面板（MusicLyricVersionDialog），不进全屏歌词页
        View btnLyric = find(R.id.tpBtnLyric);
        if (btnLyric != null) btnLyric.setOnClickListener(v -> openLyricVersionDialog());
        View btnQuality = find(R.id.tpBtnQuality);
        if (btnQuality != null) btnQuality.setOnClickListener(v -> showQualityDialog());
        if (THEME_APPLE.equals(themeId)) {
            attachPressScale(prevView, 0.88f);
            attachPressScale(nextView, 0.88f);
            attachPressScale(playView, 0.9f);
        }

        // 封面卡片：点玻璃条中段——展开态回封面模式（GZ 文案「返回到封面模式」），默认态展开多行歌词（点行 seek 用）；
        // 2026-10-21 他定：播放页点歌词不进歌词页，默认态开页口径作废，玻璃条点按只做展开/收起切换
        if (lyricLineView != null) lyricLineView.setOnClickListener(v -> {
            if (THEME_IMMERSIVE.equals(themeId)) toggleCoverExpanded();
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

        // 流光 CD：色板生效＋建旋转动画＋盘面响应式尺寸（D4）＋光泽层底色（D11）＋五图标行接线
        if (THEME_CD.equals(themeId)) {
            applyCdPalette();
            buildCdAnimator();
            applyCdDiscSize();
            // wave2：CD 初始为收起态——摘要行显示、歌词窗隐藏；点摘要行展开（与点歌词区展开同口径）。
            if (cdSummaryView != null) cdSummaryView.setVisibility(View.VISIBLE);
            if (lyricView != null) lyricView.setVisibility(View.GONE);
            if (cdSummaryView != null) cdSummaryView.setOnClickListener(v -> toggleCdLyricsExpanded());
            refreshCdUtilGlyphs();
            refreshCdUtilRowEnabled();
            // 光泽层底色由 applyCdPalette 按表面刷 hubSweep（见该函数），此处只定静止态 alpha 0（触发式淡入淡出见 triggerCdSheen）。
            if (cdSheenView != null) cdSheenView.setAlpha(0f);
            // 顶行收起键（瓜子向下箭头，非 ‹）：走系统返回，与铁律一致；歌名跑马灯需 selected 态（布局已写 selected，代码再保一手）。
            View cdCollapse = find(R.id.tpBtnCollapse);
            if (cdCollapse != null) cdCollapse.setOnClickListener(v -> onBackPressed());
            if (titleView != null) titleView.setSelected(true);
            if (cdDetailBtn != null) cdDetailBtn.setOnClickListener(v -> toggleCdDetailContent());
            // 五图标行：①歌曲信息／盘心详情（即 cdDetailBtn，已在上行接线）②音质③歌词版本⑤表面切换
            View cdUtilQuality = find(R.id.tpCdUtilQuality);
            if (cdUtilQuality != null) cdUtilQuality.setOnClickListener(v -> showQualityDialog());
            View cdUtilLyric = find(R.id.tpCdUtilLyric);
            if (cdUtilLyric != null) cdUtilLyric.setOnClickListener(v -> openLyricVersionDialog());
            View cdUtilTheme = find(R.id.tpCdUtilTheme);
            if (cdUtilTheme != null) cdUtilTheme.setOnClickListener(v -> cycleCdSurface());
            if (cdDetailView != null) cdDetailView.setAlpha(0f);
        }
        // 黑胶唱片架：3 槽舞台（只渲染前／当／后三槽，不再铺整条队列；切歌走槽位弹簧插值，不走列表滚动）；
        // 架列高取瓜子舞台高 f-23（RecordShelfPlayerKt:1397），只把架列撑高、把白歌词面往下放
        // （390dp 屏：架列 185→234dp，面顶向瓜子 41.4% 靠）。
        if (shelfList != null) {
            // 舞台模型不许手指横拖列表：横向滚动禁掉，切歌只走槽位弹簧（scrollShelfToCurrent），
            // 否则手指拖动会把三槽拖离舞台中心；竖滑切歌仍走整页拦截层，不动。
            shelfList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false) {
                @Override
                public boolean canScrollHorizontally() {
                    return false;
                }
            });
            shelfAdapter = new ShelfAdapter();
            // 项间补（槽距−封套）间距（390dp 屏约 45dp），使列表项距＝瓜子槽距（槽距即中心参考 c，无钳制）；
            // 当前槽项根宽 1.48×封套使次槽布局左缘多出 0.48×封套，由次槽位移补偿（见 onBind），不改本间距。
            shelfList.addItemDecoration(new RecyclerView.ItemDecoration() {
                @Override
                public void getItemOffsets(@NonNull android.graphics.Rect outRect, @NonNull View view,
                        @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                    outRect.set(0, 0, 0, 0);
                    if (shelfAdapter == null) return;
                    int pos = parent.getChildAdapterPosition(view);
                    if (pos < 0 || pos >= shelfAdapter.getItemCount() - 1) return;
                    float d = getResources().getDisplayMetrics().density;
                    ShelfStageGeometry g = shelfAdapter.shelfGeometry();
                    outRect.right = Math.max(0, Math.round((g.slotPitchDp - g.sleeveDp) * d));
                }
            });
            shelfList.setAdapter(shelfAdapter);
            float d = getResources().getDisplayMetrics().density;
            ViewGroup.LayoutParams shelfLp = shelfList.getLayoutParams();
            shelfLp.height = (int) (shelfAdapter.shelfGeometry().stageHeightDp * d);
            // 架列视口只容 3 槽：宽＝左槽左缘→右槽右缘（2×槽距＋封套）＋两侧 20dp 内垫，居中；
            // 适配器只出 3 项，视口外不再有第 4 项（瓜子固定 3 槽；切歌过程走槽位弹簧插值）。
            ShelfStageGeometry g3 = shelfAdapter.shelfGeometry();
            shelfLp.width = Math.round((2 * g3.slotPitchDp + g3.sleeveDp) * d)
                    + shelfList.getPaddingStart() + shelfList.getPaddingEnd();
            if (shelfLp instanceof android.widget.LinearLayout.LayoutParams) {
                ((android.widget.LinearLayout.LayoutParams) shelfLp).gravity = android.view.Gravity.CENTER_HORIZONTAL;
            }
            shelfList.setLayoutParams(shelfLp);
        }
        if (THEME_SHELF.equals(themeId)) applyShelfSeekTint();
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
            // GZ Apple 头右爱心＋三个点：盒 34dp、按压 0.88
            attachPressScale(find(R.id.tpBtnFav), 0.88f);
            attachPressScale(find(R.id.tpBtnMenu), 0.88f);
            // GZ Apple 运输键：上一首／下一首按压 0.88、播放／暂停按压 0.9
            attachPressScale(prevView, 0.88f);
            attachPressScale(nextView, 0.88f);
            attachPressScale(playView, 0.9f);
        } else if (THEME_CLASSIC.equals(themeId)) {
            attachPressScale(find(R.id.tpBtnMenu), 0.96f);
            attachPressScale(find(R.id.tpBtnQueueTitle), 0.96f);
            attachPressScale(playTopView, 0.96f);
            // K-4：经典小爱心按压 0.85（仅回调非空时包按压；收藏回调恒非空，见 toggleFavorite）。
            attachPressScale(favHeartView, 0.85f);
        } else if (THEME_IMMERSIVE.equals(themeId)) {
            // C-4：展开态四个玻璃键按压 0.88（GZ 四个 LiquidGlassButton 值），不是默认主题 0.96。
            attachPressScale(prevView, 0.88f);
            attachPressScale(nextView, 0.88f);
            attachPressScale(queueView, 0.88f);
            attachPressScale(find(R.id.tpBtnInfoExp), 0.88f);
            attachPressScale(favBtnView, 0.88f);
            // 封面卡片玻璃条播放键按压 0.88（GZ PlayerScreenKt:9620-9640），不是默认主题 0.96。
            attachPressScale(playView, 0.88f);
        } else if (THEME_CD.equals(themeId)) {
            // GZ CD 运输行各键按压 0.9（播放键已是 0.9）
            attachPressScale(prevView, 0.9f);
            attachPressScale(nextView, 0.9f);
            attachPressScale(playView, 0.9f);
            attachPressScale(queueView, 0.9f);
            attachPressScale(modeView, 0.9f);
            // GZ CD 行内键按压 0.9：五图标行五键＋顶右菜单＋顶行收起键同 0.9（favHeartView 即五图标行第 4 格收藏心）
            attachPressScale(favHeartView, 0.9f);
            attachPressScale(find(R.id.tpBtnMenu), 0.9f);
            attachPressScale(find(R.id.tpBtnCollapse), 0.9f);
            attachPressScale(find(R.id.tpCdUtilInfo), 0.9f);
            attachPressScale(find(R.id.tpCdUtilQuality), 0.9f);
            attachPressScale(find(R.id.tpCdUtilLyric), 0.9f);
            attachPressScale(find(R.id.tpCdUtilTheme), 0.9f);
        } else if (THEME_SHELF.equals(themeId)) {
            // GZ 唱片架底行键按压统一 0.9
            attachPressScale(prevView, 0.9f);
            attachPressScale(nextView, 0.9f);
            attachPressScale(playView, 0.9f);
            attachPressScale(modeView, 0.9f);
            attachPressScale(queueView, 0.9f);
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
     * 轴锁 1.5）、封面卡片横滑（锚点距 clamp(屏宽×0.42, 220, 360)dp、轴锁 1.2）、其余主题横滑（轴锁 1.5、无跟手），
     * Apple 竖屏除外（A10：取消封面切歌滑动）；
     * 触发距离 36dp、甩动速度 600dp/s、拖动 1:1 跟手钳在锚点内、过阈 CONTEXT_CLICK 震动一次、
     * 提交先 150ms(bs4.f) 滑到锚点再切歌、不提交回中（GZ 弹簧 0.9/1400 以 250ms tween 近似）。
     */
    /**
     * C1：滑动跟手目标——封面卡片主题平移 tpCoverCard 整卡（卡框＋后叠卡＋封面图一起动，
     * 不再只平移封面图导致图滑出卡边被 clipToOutline 裁掉）；其余主题仍平移封面图。
     */
    private View coverFollowView() {
        if (THEME_IMMERSIVE.equals(themeId)) {
            View card = find(R.id.tpCoverCard);
            if (card != null) return card;
        }
        return coverView;
    }

    private void attachCoverGestures(View wrap) {
        // redo3 第四轮 A10：Apple 竖屏封面无切歌滑动（GZ Apple 竖屏封面无滑动处理器，横滑只在横屏黑胶／封面卡片）；
        // Apple 页仅取消封面切歌滑动（onScroll 守卫），单击／双击／长按与歌词页 pager 滑动不受影响；其余主题横滑口径不动。
        // 整页竖滑切歌后：经典封面竖滑收归 themeContainer 拦截层（竖滑双通道会先封面跟手再整页跟手、视觉抖），
        // 此处只禁经典竖滑分支，点按／双击／长按原样保留；封面卡片／CD 横滑由轴锁分流，不受整页竖滑影响。
        final boolean swipeEnabled = !THEME_APPLE.equals(themeId) && !THEME_CLASSIC.equals(themeId);
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
        // 堵-3：封面横滑起点（ACTION_DOWN 记，ACTION_UP 判）与起点是否在封面区。
        final float[] coverSwipeDown = new float[2];
        final boolean[] coverSwipeDownInWrap = new boolean[1];
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
                // 双击＝收藏切换（不只加不减）；adding＝切后收藏态（favorited 为切前态，取反即切后态）。
                MusicTrack track = MusicController.get().current();
                boolean adding = track != null && !favorited;
                if (track != null) toggleFavorite(null);
                if (THEME_CLASSIC.equals(themeId)) {
                    // 经典：瓜子 genBurstHearts——收藏态 8 颗、非收藏态 5 颗（burstCover 的 big 参即此档）；
                    // 封面 1.045／200ms，回落走 spring（阻尼 0.9／刚度 300），不用 250ms tween 后半段。
                    if (track == null) return true;
                    float[] p = toOverlayPoint(wrap, e.getX(), e.getY());
                    binding.heartsOverlay.burstCover(p[0], p[1], adding);
                    cancelCoverScaleSpring();
                    wrap.animate().scaleX(1.045f).scaleY(1.045f).setDuration(200)
                            .withEndAction(() -> startCoverScaleSpring(wrap))
                            .start();
                    try {
                        wrap.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
                // 取消收藏也有爱心（瓜子行为）：其余主题与经典一致，收藏态 8 颗、取消态 5 颗。
                // 非经典分支此前无 track 守门：无曲目时爆发＋缩放＋震动全跑、toggleFavorite
                // 却静默返回，反馈与实际脱节；照经典分支补同一守门（无曲目直接返回）。
                if (track == null) return true;
                float[] p = toOverlayPoint(wrap, e.getX(), e.getY());
                binding.heartsOverlay.burstCover(p[0], p[1], adding);
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
                if (!swipeEnabled) return false; // A10：Apple 竖屏封面不处理切歌滑动
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
                View followView = coverFollowView();
                if (follow && followView != null) {
                    float clamped = Math.max(-anchorPx, Math.min(anchorPx, off));
                    if (vertical) followView.setTranslationY(clamped);
                    else followView.setTranslationX(clamped);
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
            // 堵-3：ACTION_DOWN 记起点与起点是否落在封面区（inCoverWrap 结果），ACTION_UP 只判这组起点，不判松手点。
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                coverSwipeDown[0] = e.getX();
                coverSwipeDown[1] = e.getY();
                coverSwipeDownInWrap[0] = inCoverWrap(e);
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP && THEME_APPLE.equals(themeId)
                        && coverSwipeDownInWrap[0] && !state[2]) {
                    // Apple 封面横滑（竖屏封面切歌滑动已禁，封面区此前无横滑出口）：
                    // 位移＞72dp 且 |dx|＞|dy|×1.5，左划进歌词页、右划进歌曲信息子面板（堵-2 同一入口）。
                    float dx = e.getX() - coverSwipeDown[0];
                    float dy = e.getY() - coverSwipeDown[1];
                    if (Math.abs(dx) > 72 * density && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        if (dx < 0) openLyricsPage();
                        else showToolsSheetSongInfo();
                    }
                }
                coverSwipeDownInWrap[0] = false;
                TextView hint = findViewById(R.id.swipeHint);
                if (hint != null) hint.setVisibility(View.GONE);
                if (!state[2]) {
                    if (state[0] && Math.abs(drag[0]) >= 36 * density) {
                        commitCoverSwipe(wrap, drag[0] < 0, anchorDp[0] * density, follow, vertical, state);
                    } else if (follow && coverFollowView() != null) {
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

    /** 滑动切歌提交：先滑到锚点（150ms bs4.f）再切歌；无跟手主题直接切（转场由 bindCovers 承担）；
     * C1：跟手目标与 onScroll 一致（封面卡片为整卡），提交／回中沿用现有弹簧与 36dp 触发、600dp/s 甩动口径。 */
    private void commitCoverSwipe(View wrap, boolean next, float anchorPx, boolean follow, boolean vertical, boolean[] state) {
        state[2] = true;
        lastSkipDirection = next ? 1 : -1;
        cancelCoverSpringBack();
        final View followView = coverFollowView();
        Runnable change = () -> {
            if (followView != null) {
                followView.setTranslationX(0f);
                followView.setTranslationY(0f);
            }
            if (coverView != null && followView != coverView) {
                coverView.setTranslationX(0f);
                coverView.setTranslationY(0f);
            }
            if (next) MusicController.get().next();
            else MusicController.get().prev();
        };
        if (follow && followView != null) {
            float target = next ? -anchorPx : anchorPx;
            if (vertical) {
                followView.animate().translationY(target).setDuration(150).setInterpolator(INTERP_EXIT)
                        .withEndAction(change).start();
            } else {
                followView.animate().translationX(target).setDuration(150).setInterpolator(INTERP_EXIT)
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

    /** 封面回中弹簧（redo3 M5）：spring(阻尼比 0.9, 刚度 1400)，半隐式欧拉逐帧积分，dt 钳 50ms；落定阈值位移 0.5px／速度 1px·s⁻¹；
     * C1：回中目标与跟手目标一致（封面卡片为整卡）。 */
    private void startCoverSpringBack() {
        final View target = coverFollowView();
        if (target == null) return;
        cancelCoverSpringBack();
        coverSpringVx = 0f;
        coverSpringVy = 0f;
        coverSpringLastMs = 0L;
        coverSpringCallback = new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                long nowMs = frameTimeNanos / 1000000L;
                float dt = coverSpringLastMs == 0 ? 0.016f
                        : Math.min(0.05f, (nowMs - coverSpringLastMs) / 1000f);
                coverSpringLastMs = nowMs;
                double k = 1400d;
                double damp = 2d * 0.9d * Math.sqrt(k);
                float x = target.getTranslationX();
                float y = target.getTranslationY();
                coverSpringVx += (float) ((k * (0 - x) - damp * coverSpringVx) * dt);
                coverSpringVy += (float) ((k * (0 - y) - damp * coverSpringVy) * dt);
                x += coverSpringVx * dt;
                y += coverSpringVy * dt;
                target.setTranslationX(x);
                target.setTranslationY(y);
                if (Math.abs(x) < 0.5f && Math.abs(y) < 0.5f
                        && Math.abs(coverSpringVx) < 1f && Math.abs(coverSpringVy) < 1f) {
                    target.setTranslationX(0f);
                    target.setTranslationY(0f);
                    coverSpringCallback = null;
                } else {
                    android.view.Choreographer.getInstance().postFrameCallback(this);
                }
            }
        };
        android.view.Choreographer.getInstance().postFrameCallback(coverSpringCallback);
    }

    private void cancelCoverScaleSpring() {
        if (coverScaleSpringCallback != null) {
            android.view.Choreographer.getInstance().removeFrameCallback(coverScaleSpringCallback);
            coverScaleSpringCallback = null;
        }
    }

    /** 双击封面缩放回落：从当前缩放（1.045）以 spring（阻尼 0.9／刚度 300）回落到 1，半隐式欧拉逐帧积分，dt 钳 50ms。 */
    private void startCoverScaleSpring(final View target) {
        if (target == null) return;
        cancelCoverScaleSpring();
        coverScaleSpringV = 0f;
        coverScaleSpringLastMs = 0L;
        coverScaleSpringCallback = new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                long nowMs = frameTimeNanos / 1000000L;
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
        lastBoundTrack = track;
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
        // 唱片架 3 槽模型：内容重绑时机归 scrollShelfToCurrent——切歌时先滑旧内容、落位同帧重绑，
        // 这里不再提前 notifyDataSetChanged（提前重绑会让弹簧起点闪一下新／旧封面）；非唱片架主题 shelfAdapter 为空，不动。
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
        if (track != null && com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(newArtist)) newArtist = getString(R.string.music_unknown_artist);
        if (titleView != null) titleView.setText(newTitle);
        if (artistView != null) artistView.setText(newArtist);
        // C-1／K-2：歌手色按可点态分档——封面卡片有歌手名可点白 0.65、无名白 0.55；
        // 经典有歌手名可点白 0.45、无名 0.34（布局默认值）；其余主题颜色由各自色板定，这里不动。
        if (artistView != null) {
            if (THEME_IMMERSIVE.equals(themeId)) {
                artistView.setTextColor(newArtist == null || newArtist.isEmpty() ? 0x8CFFFFFF : 0xA6FFFFFF);
            } else if (THEME_CLASSIC.equals(themeId)) {
                artistView.setTextColor(newArtist == null || newArtist.isEmpty() ? 0x57FFFFFF : 0x73FFFFFF);
            }
        }
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
        // 专辑：无专辑信息隐藏（该藏的藏）；Apple 仅专辑非空且≠未知专辑时显（经典行沿用旧口径：空才藏）。
        // 歌手行已是歌手 ♡ 专辑名合并行（用户令，五主题一起）；分隔心跟专辑同显同藏。
        if (albumView != null) {
            String album = track == null ? null : track.album;
            if (album == null || album.isEmpty()
                    || (THEME_APPLE.equals(themeId) && "未知专辑".equals(album.trim()))) {
                albumView.setVisibility(View.GONE);
            } else {
                albumView.setText(album);
                albumView.setVisibility(View.VISIBLE);
                // K-2：经典专辑可点时白 0.45（布局默认 0.26 是不可点档）。
                if (THEME_CLASSIC.equals(themeId)) albumView.setTextColor(0x73FFFFFF);
            }
            // 合并行分隔心：跟专辑同显同藏，无专辑时不留 trailing ♡
            if (albumSepView != null) albumSepView.setVisibility(albumView.getVisibility());
            // Apple 专辑独立行：tpAlbum 的父行固定 18dp，只藏文字会留一条空白；
            // 仅 Apple 主题下父行跟专辑同显同藏（其它主题专辑在合并行内，父行不是独立行，不动）。
            if (THEME_APPLE.equals(themeId) && albumView.getParent() instanceof View) {
                ((View) albumView.getParent()).setVisibility(albumView.getVisibility());
            }
        }
        bindCovers(track);
        // wave2：CD 五图标行禁用门控随曲目刷新（无当前曲时整行不可用，见 refreshCdUtilRowEnabled）。
        if (THEME_CD.equals(themeId)) {
            refreshCdUtilGlyphs();
            refreshCdUtilRowEnabled();
        }
        refreshAppleTransportAlpha();
    }

    /** 当前曲应显示的封面地址：自定义封面优先（本地相册选图），否则原封面 */
    private String effectiveCover(MusicTrack track) {
        if (track == null) return null;
        String custom = MusicStore.getSongCover(MusicStore.songCoverKey(track));
        if (custom != null && !custom.isEmpty()) return custom;
        return track.cover;
    }

    /** C1：封面卡片后叠卡——前一首／后一首真封面；邻曲直接调控制器 queueNextIndex()/queuePrevIndex()
     * （即服务侧 MusicQueue.nextIndex()/prevIndex()，含随机洗牌顺序，不由活动侧近似推算）；
     * 无邻曲（序号 -1）时该张后叠卡 GONE，不再画当前曲；邻曲序号等于本曲时才复用当前曲封面；
     * 邻曲封面走 effectiveCover（自定义封面优先），标题走邻曲标题（空地址时素图排字也是邻曲名）。
     * 非封面卡片主题无此二视图，直接返回。 */
    private void refreshCoverBackCards(MusicTrack track) {
        if (!THEME_IMMERSIVE.equals(themeId)) return;
        ImageView prevView = find(R.id.tpCoverBackPrev);
        ImageView nextView = find(R.id.tpCoverBackNext);
        if (prevView == null && nextView == null) return;
        String currentTitle = track == null ? "" : track.displayTitle();
        String current = effectiveCover(track);
        MusicTrack prevTrack = null;
        MusicTrack nextTrack = null;
        int prevIdx = -1;
        int nextIdx = -1;
        int idx = -1;
        try {
            java.util.List<MusicTrack> queue = MusicController.get().queue();
            idx = MusicController.get().index();
            if (queue != null && idx >= 0 && idx < queue.size()) {
                prevIdx = MusicController.get().queuePrevIndex();
                nextIdx = MusicController.get().queueNextIndex();
                if (prevIdx >= 0 && prevIdx < queue.size()) prevTrack = queue.get(prevIdx);
                if (nextIdx >= 0 && nextIdx < queue.size()) nextTrack = queue.get(nextIdx);
            }
        } catch (Throwable ignored) {
        }
        bindBackCard(prevView, prevIdx, idx, prevTrack, currentTitle, current);
        bindBackCard(nextView, nextIdx, idx, nextTrack, currentTitle, current);
    }

    /** 后叠卡单张绑定：-1 → GONE；等于本曲 → 复用当前曲封面与标题；其余 → 邻曲封面与标题。 */
    private void bindBackCard(ImageView view, int neighborIdx, int currentIdx, MusicTrack neighbor, String currentTitle, String currentCover) {
        if (view == null) return;
        if (neighborIdx < 0 || neighbor == null) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(View.VISIBLE);
        if (neighborIdx == currentIdx) {
            loadIntoCover(view, currentCover, currentTitle);
        } else {
            loadIntoCover(view, effectiveCover(neighbor), neighbor.displayTitle());
        }
    }

    /** 自定义封面设置／恢复后重刷当前曲封面与背景（工具面板调宿主用） */
    public void refreshCurrentCover() {
        if (binding == null || destroyed) return;
        bindCovers(MusicController.get().current());
    }

    private void bindCovers(MusicTrack track) {
        String cover = effectiveCover(track);
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
        refreshCoverBackCards(track);
        updateBackground(track);
        // 本地歌无封面（内嵌／旁路／专辑 art 全空才到这）：后台按净歌名搜在线封面，命中补上
        maybeFetchOnlineCover(track);
        // 封面切换：经典/Apple 照 GZ CoverTransitionSpec（经典 400ms/enterScaleFrom 0.94、Apple 600ms/0.97、
        // exitAlphaEnd 0，新封面自透明淡入）；封面卡片不走 CoverTransitionSpec（该文件只有 Classic/Apple
        // 两个预设、无 cover_immersive 分支），改走瓜子 ThreeCardAlternateStack 切歌换卡 transitionSpec：
        // 350ms、入场缩放 0.94→1、出场缩放→0.92；其余主题仍沿用 400ms/0.96 近似。瓜子暂停封面不缩小，入场目标恒 1。
        if (coverView != null && track != null) {
            boolean immersive = THEME_IMMERSIVE.equals(themeId);
            long dur = THEME_APPLE.equals(themeId) ? 600 : immersive ? 350 : 400;
            float fromScale = THEME_APPLE.equals(themeId) ? 0.97f : THEME_CLASSIC.equals(themeId) || immersive ? 0.94f : 0.96f;
            float target = 1f;
            if (stagedExit) {
                // 出场动画（与入场同时跑）：经典出场缩放 1→0.96＋反向位移 0.5×高、Apple 出场缩放 1→1.03、
                // 封面卡片出场缩放 1→0.92＋整幅滑出（ThreeCard 换卡 spec）、其余主题出场只有淡出
                // （GZ 未给其余主题出场缩放值，不编）；出场透明度 1→0，出场层动画结束即隐藏。
                // Apple keepFallbackVisible＝true：出场层在整个转场期间可见，即本层本身。
                float exitScale = THEME_CLASSIC.equals(themeId) ? 0.96f : THEME_APPLE.equals(themeId) ? 1.03f : immersive ? 0.92f : 1f;
                float exitSlide = 0f;
                float exitSlideX = 0f;
                if (THEME_CLASSIC.equals(themeId)) {
                    int h = coverView.getHeight();
                    exitSlide = -(h > 0 ? h : 300 * getResources().getDisplayMetrics().density) * 0.5f;
                } else if (immersive) {
                    // 三卡换卡出场滑出：方向与切歌方向相反（下一首向左、上一首向右），幅度取内容整幅宽
                    // （瓜子滑出偏移由内容全尺寸推导，无 1/4 这类字面量，不编）；滑出缓动 bs4.c。
                    int w = coverView.getWidth();
                    float full = w > 0 ? w : getResources().getDisplayMetrics().widthPixels;
                    exitSlideX = -lastSkipDirection * full;
                }
                final int gen = coverExitGen;
                coverExitView.animate().alpha(0f).scaleX(exitScale).scaleY(exitScale)
                        .translationX(exitSlideX).translationY(exitSlide).setDuration(dur)
                        .setInterpolator(THEME_CLASSIC.equals(themeId) ? INTERP_EMPH : immersive ? INTERP_OUT_C : INTERP_FOSI)
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
            // 入场滑移：经典竖向入场位移 0.5×高×(1−进度)（GZ CoverTransitionSpec coverSlotTransform）；
            // 封面卡片切歌换卡入场整幅滑入（瓜子 ThreeCard 滑入偏移由内容全尺寸推导、方向随切歌方向），
            // 只在有出场层（真切歌）时滑入，首绑／换主题／重刷无出场层时不滑、只淡入＋缩放；
            // Apple 入场位移 0。出场由上方的出场封面层承担。
            // 缓动：单条 ViewPropertyAnimator 链无法像瓜子那样把淡入（bs4.a）与缩放／滑移（bs4.e）分开，
            // 入场整链取 Emphasized（经典链同款取法），淡入缓动随链走、属近似。
            if (THEME_CLASSIC.equals(themeId)) {
                int h = coverView.getHeight();
                float slide = (h > 0 ? h : 300 * getResources().getDisplayMetrics().density) * 0.5f;
                coverView.setTranslationY(slide);
            } else if (immersive && stagedExit) {
                int w = coverView.getWidth();
                float full = w > 0 ? w : getResources().getDisplayMetrics().widthPixels;
                coverView.setTranslationX(lastSkipDirection * full);
            }
            coverView.animate().alpha(1f).scaleX(target).scaleY(target)
                    .translationX(0f).translationY(0f)
                    .setDuration(dur)
                    .setInterpolator(THEME_CLASSIC.equals(themeId) || immersive ? INTERP_EMPH : INTERP_FOSI)
                    .start();
            if (THEME_CD.equals(themeId)) triggerCdSweep();
        }
    }

    /**
     * 无封面时懒取在线封面：按净歌名搜在线源，命中后把封面补到当前曲并重刷封面与背景；
     * 成功每首本页只试一次、失败下次 bind 可再试，切走或已拿到封面即停。只补播放页当前曲，
     * 列表行不动（防滚动反复触发）。第三波 A3：去掉 isLocalPlatform 门，在线歌 cover 为空时也补一次。
     */
    private void maybeFetchOnlineCover(MusicTrack track) {
        if (track == null || (track.cover != null && !track.cover.isEmpty())) return;
        // 自定义封面已设：优先自定义，不让在线补图盖掉
        if (MusicStore.getSongCover(MusicStore.songCoverKey(track)) != null) return;
        if (track.id == null || track.id.isEmpty()) return;
        // 去重键加平台命名空间：裸 id 跨平台会互相挡（同 id 不同平台的歌只能试一首），
        // 回调身份核对也同步加平台，不把 A 平台的封面写到 B 平台同 id 的歌上。
        // 成功只试一次；失败后从集合中移除——本页下次 bind 同曲可再试，不永久占位。
        final String coverKey = (track.platform == null ? "" : track.platform) + "|" + track.id;
        if (!onlineCoverTried.add(coverKey)) return;
        final String trackId = track.id;
        final String trackPlatform = track.platform == null ? "" : track.platform;
        Task.execute(() -> {
            String url;
            try {
                url = MusicRepository.onlineCover(track);
            } catch (Throwable ignored) {
                onlineCoverTried.remove(coverKey);
                return;
            }
            if (url == null || url.isEmpty()) {
                onlineCoverTried.remove(coverKey);
                return;
            }
            runOnUiThread(() -> {
                if (destroyed) return;
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !trackId.equals(cur.id)
                        || !trackPlatform.equals(cur.platform == null ? "" : cur.platform)) return;
                if (cur.cover != null && !cur.cover.isEmpty()) return;
                cur.cover = url;
                // 第三波 P-8d：在线补图命中后写回下载记录（已下载歌：本机缓存即记录本身；
                // 曲库目录歌无下载记录，不写回，只留内存）。已下载歌的兜底门已在
                // 第三波 A3 去掉平台门（本方法不再按原在线平台挡掉下载歌）。
                if ("local".equals(cur.platform)) {
                    com.fongmi.android.tv.music.MusicDownloadStore.updateDownloadCover(cur.id, url);
                }
                loadIntoCover(coverView, url, cur.displayTitle());
                updateBackground(cur);
            });
        });
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

    /** 经典背景 RenderEffect 清除点（API31+ 才有 RenderEffect；低版本无此效果，无需清）。 */
    private void clearClassicRenderEffect() {
        if (binding == null || binding.ivBgBlur == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 31) binding.ivBgBlur.setRenderEffect(null);
    }

    /**
     * 背景层：封面按 64×64 小图请求（GZ BlurredCoverAmbient 口径），放大即糊；
     * 拿到图后取色刷氛围层与 CD 色板。无封面用 CoverColor 兜底色。
     */
    private void updateBackground(MusicTrack track) {
        String cover = effectiveCover(track);
        if (cover == null || cover.isEmpty()) {
            bgSoftBitmap = null;
            bgSoftUrl = null;
            bgFullBitmap = null;
            bgFullUrl = null;
            bgCompositeApplied = false;
            bgCompositeMode = -1;
            bgCompositeUrl = null;
            binding.ivBgBlur.setImageDrawable(null);
            // 无封面分支此前不清 RenderEffect：上一首有封面时挂上的 24dp 模糊会残留在空背景上，
            // 直到下一次合成成功回调才清；无封面即清，不等异步回调。
            clearClassicRenderEffect();
            animateAmbient(CoverColor.ambientBackground(null));
            dominantColor = CoverColor.FALLBACK_COLOR;
            if (THEME_CD.equals(themeId)) applyCdPalette();
            if (THEME_SHELF.equals(themeId)) applyShelfSeekTint();
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
                // 已是当前态的合成不许被软底盖回（切歌／换主题／歌词页开合时合成由 refresh 重建，软底只作过渡）；
                // 经典态不直贴 64×64 原图：拉伸原图当首帧就是旧闪，等全图合成回调再贴（其余主题仍用软底过渡）
                if (compositeMode() != BG_SHARP && !(bgCompositeApplied && cover.equals(bgCompositeUrl) && bgCompositeMode == compositeMode())) {
                    binding.ivBgBlur.setImageDrawable(resource);
                }
                refreshPlayerBackground();
                if (resource instanceof BitmapDrawable) {
                    Bitmap bmp = ((BitmapDrawable) resource).getBitmap();
                    if (bmp != null) {
                        Task.execute(() -> {
                            Integer cachedAmbient = sAmbientCache.get(cover);
                            int ambient;
                            int dominant;
                            if (cachedAmbient != null) {
                                ambient = cachedAmbient;
                                Integer cachedDominant = sDominantCache.get(cover);
                                dominant = cachedDominant != null ? cachedDominant : CoverColor.FALLBACK_COLOR;
                            } else {
                                ambient = CoverColor.ambientBackground(bmp);
                                dominant = CoverColor.dominantColor(bmp);
                                sAmbientCache.put(cover, ambient);
                                sDominantCache.put(cover, dominant);
                            }
                            runOnUiThread(() -> {
                                if (destroyed) return;
                                dominantColor = dominant;
                                animateAmbient(ambient);
                                if (THEME_CD.equals(themeId)) applyCdPalette();
                                if (THEME_SHELF.equals(themeId)) applyShelfSeekTint();
                            });
                        });
                    }
                }
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                // 64×64 软底请求失败时氛围色永不计算、永不重试，而封面显示走另一条全尺寸请求可以成功
                // → 红封面显示着、背景一直兜底黑（2026-10-22 如愿实拍）。失败时改从主封面视图现成的图里取色
                // （封面已显示时这张图就是本歌封面）；主封面也没图时不做事，等下一次 updateBackground。
                if (destroyed) return;
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cover.equals(effectiveCover(cur))) return;
                if (sAmbientCache.get(cover) != null) return;
                if (coverView != null && coverView.getDrawable() instanceof BitmapDrawable) {
                    Bitmap bmp = ((BitmapDrawable) coverView.getDrawable()).getBitmap();
                    if (bmp != null) {
                        Task.execute(() -> {
                            int ambient = CoverColor.ambientBackground(bmp);
                            int dominant = CoverColor.dominantColor(bmp);
                            sAmbientCache.put(cover, ambient);
                            sDominantCache.put(cover, dominant);
                            runOnUiThread(() -> {
                                if (destroyed) return;
                                MusicTrack c2 = MusicController.get().current();
                                if (c2 == null || !cover.equals(effectiveCover(c2))) return;
                                dominantColor = dominant;
                                animateAmbient(ambient);
                            });
                        });
                    }
                }
            }
        });
        // 经典主题才需屏尺寸原图（熔化上半用）；其余主题的压暗只用 64×64 底（合成里先缩到 64 再拉满，底与软底同源）
        if (compositeMode() == BG_SHARP && (bgFullBitmap == null || !cover.equals(bgFullUrl))) {
            ImgUtil.load(this, cover, new CustomTarget<Drawable>() {
                @Override
                public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                    if (destroyed) return;
                    MusicTrack c = MusicController.get().current();
                    if (c == null || !cover.equals(effectiveCover(c))) return;
                    if (resource instanceof BitmapDrawable && ((BitmapDrawable) resource).getBitmap() != null) {
                        bgFullBitmap = ((BitmapDrawable) resource).getBitmap();
                        bgFullUrl = cover;
                        refreshPlayerBackground();
                        // 软底取色没贴上时（软底回调失败／被跳过）全图兜底取一次色，否则背景一直停在兜底黑（如愿红封面下全黑就是这条）。
                        if (sAmbientCache.get(cover) == null) {
                            Bitmap full = bgFullBitmap;
                            Task.execute(() -> {
                                int ambient = CoverColor.ambientBackground(full);
                                int dominant = CoverColor.dominantColor(full);
                                sAmbientCache.put(cover, ambient);
                                sDominantCache.put(cover, dominant);
                                runOnUiThread(() -> {
                                    if (destroyed) return;
                                    MusicTrack c2 = MusicController.get().current();
                                    if (c2 == null || !cover.equals(effectiveCover(c2))) return;
                                    dominantColor = dominant;
                                    animateAmbient(ambient);
                                });
                            });
                        }
                    }
                }

                @Override
                public void onLoadFailed(@Nullable Drawable errorDrawable) {
                    // M6：经典首帧失败兜底（选回调一路，不用超时回落：树内同款 CustomTarget
                    // 在 LiveActivity/VideoActivity/HkArticleAdapter 里已有此签名现成用法）。
                    // 只针对经典态：BG_DARK/BG_LYRICS 分支不动（这里直接返回，不清不贴）。
                    // 全图失败时旧图若不处理会无限期残留：软底（64×64）已到就直接拿它走一次
                    // 经典合成 buildCompositeAsync（不走 refreshPlayerBackground——经典分支无全图
                    // 即早返，走了也刷不出来）；软底也没到（同地址两路都失败）就清掉旧图，
                    // 不留错歌的旧背景，下一次 updateBackground（切歌／换主题）会重新发起加载。
                    // 已贴好本封面本态的合成不许被一次冗余全图失败清掉。
                    if (destroyed || binding == null) return;
                    MusicTrack c = MusicController.get().current();
                    if (c == null || !cover.equals(effectiveCover(c))) return;
                    if (compositeMode() != BG_SHARP) return;
                    if (bgCompositeApplied && cover.equals(bgCompositeUrl) && bgCompositeMode == BG_SHARP) return;
                    if (bgSoftBitmap != null && cover.equals(bgSoftUrl)) {
                        buildCompositeAsync(cover, BG_SHARP, bgSoftBitmap, 0);
                    } else {
                        bgCompositeApplied = false;
                        binding.ivBgBlur.setImageDrawable(null);
                    }
                }
            });
        }
        refreshPlayerBackground();
    }

    /** 当前应有的背景合成态（主题＋歌词页开合决定，与封面地址无关）。 */
    private int compositeMode() {
        if (lyricsPageOpen) return BG_LYRICS;
        if (THEME_CLASSIC.equals(themeId)) return BG_SHARP;
        if (THEME_APPLE.equals(themeId)) return BG_APPLE;
        return BG_DARK;
    }

    /** 五主题背景刷新：按当前态选合成，几何就绪后后台合成、前台贴图。
     *  调用点：updateBackground（切歌／换主题都走 bindCovers→updateBackground）、64×64 回调（软底到图后）、
     *  全图回调（原图到图后）、歌词页开合。歌词绘制一律不动，只动背景亮度层。 */
    private void refreshPlayerBackground() {
        if (binding == null || destroyed) return;
        MusicTrack cur = MusicController.get().current();
        String cover = cur == null ? null : effectiveCover(cur);
        if (cover == null || cover.isEmpty()) return;
        int mode = compositeMode();
        if (bgCompositeApplied && cover.equals(bgCompositeUrl) && bgCompositeMode == mode) return;
        // 唱片架页：瓜子本页无整幅糊底压暗层（全文无 Brush/Gradient/veil，根只有纯色 #FFF1F0ED）；
        // 星辰 BG_DARK 合成图贴在 ivBgBlur 上，但架根是不透明 #FFF1F0ED、整页盖住背景层，veil 视觉上无效，
        // 本页直接跳过合成（省一次后台模糊＋贴图；旧合成图残留在背景层、仍被不透明根盖住，不可见）。
        // 歌词页开着时 BG_LYRICS 是另一面（全屏歌词页的暗底），不跳过。
        if (THEME_SHELF.equals(themeId) && !lyricsPageOpen) return;
        Bitmap src;
        if (mode == BG_SHARP) {
            if (bgFullBitmap == null || !cover.equals(bgFullUrl)) return; // 原图还在路上：全图回调里会再刷一次
            src = bgFullBitmap;
        } else {
            if (bgSoftBitmap != null && cover.equals(bgSoftUrl)) src = bgSoftBitmap;
            else if (bgFullBitmap != null && cover.equals(bgFullUrl)) src = bgFullBitmap;
            else return; // 软底还在路上：64×64 回调里缓存后会再刷一次
        }
        buildCompositeAsync(cover, mode, src, 0);
    }

    /** 合成贴图：主线程量几何（背景尺寸＋封面下缘），后台合成，前台贴图时重核（封面／主题／开合态变了就丢弃）。 */
    private void buildCompositeAsync(String cover, int mode, Bitmap src, int retry) {
        if (binding == null || binding.ivBgBlur == null) return;
        View bgView = binding.ivBgBlur;
        bgView.post(() -> {
            if (binding == null || destroyed) return;
            int vw = bgView.getWidth();
            int vh = bgView.getHeight();
            // BG_SHARP 且 coverView == null 时旧式 coverNeeded 为真，后面 coverView.getHeight()
            // 直接空指针：无封面视图的主题根本量不到封面几何，coverNeeded 必须先判空
            boolean coverNeeded = coverView != null && (mode == BG_SHARP || mode == BG_DARK);
            if (vw <= 0 || vh <= 0 || (coverNeeded && coverView.getHeight() <= 0)) {
                if (retry < 3) bgView.post(() -> buildCompositeAsync(cover, mode, src, retry + 1));
                return;
            }
            int topOff = 0;
            int cardBottom = 0;
            if (coverView != null && coverView.getHeight() > 0) {
                int[] bgLoc = new int[2];
                int[] cvLoc = new int[2];
                bgView.getLocationOnScreen(bgLoc);
                coverView.getLocationOnScreen(cvLoc);
                topOff = Math.max(0, cvLoc[1] - bgLoc[1]);
                cardBottom = topOff + coverView.getHeight();
            }
            float density = getResources().getDisplayMetrics().density;
            // topOff／cardBottom 上面按条件改过值，lambda 抓不到非 final 变量：进后台前先定格成 final 副本；
            // 氛围色同样主线程快照后透传（经典渐变罩用，非经典分支忽略该参数、不参与压暗计算）
            final int fTopOff = topOff;
            final int fCardBottom = cardBottom;
            final int fAmbient = ambientColor;
            Task.execute(() -> {
                Bitmap out = buildPlayerBackground(src, vw, vh, fTopOff, fCardBottom, mode, density, fAmbient);
                if (out == null) return;
                runOnUiThread(() -> {
                    if (binding == null || destroyed) return;
                    MusicTrack c = MusicController.get().current();
                    if (c == null || !cover.equals(effectiveCover(c))) return;
                    if (compositeMode() != mode) return;
                    binding.ivBgBlur.setImageDrawable(new BitmapDrawable(getResources(), out));
                    // 经典 API31+：糊底模糊由视图层 RenderEffect 24dp 落地（位图层已跳过 FastBlur）；
                    // 切离经典时清掉，避免糊底效果漏到其他主题；低版本无 RenderEffect，无需清。
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        if (mode == BG_SHARP) {
                            float r = 24f * bgView.getResources().getDisplayMetrics().density;
                            bgView.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
                        } else {
                            bgView.setRenderEffect(null);
                        }
                    }
                    bgCompositeApplied = true;
                    bgCompositeMode = mode;
                    bgCompositeUrl = cover;
                    bgCompositeAmbient = fAmbient; // M5：记下本次合成所用氛围色，供动画结束比对
                });
            });
        });
    }

    /** 全幅四段取色渐变罩（GZ CoverAmbientKt:329，经典与 Apple 共用）：停点 0/0.4/0.78/1，alpha＝基数 0.8＋偏移
     * （-0.04/+0.02/0/+0.06）钳制 [0,1]；颜色＝同一 ambient 经 toneAdjust（GZ 函数 d），两端再按 GZ s()
     * 只平移明度 V（+0.05/-0.04，钳制 [0.1,0.68]，色相／饱和度不动）。GZ 另有 +f11 一项（p 语义未核实），
     * 本次按任务口径只用基数＋偏移，不擅自带入。 */
    private static void drawAmbientVeil(Canvas canvas, int viewW, int viewH, int ambient) {
        int base = CoverColor.toneAdjust(ambient);
        float[] hsv = new float[3];
        Color.colorToHSV(base, hsv);
        float topV = Math.max(0.1f, Math.min(0.68f, hsv[2] + 0.05f));
        int topColor = Color.HSVToColor(new float[]{hsv[0], hsv[1], topV});
        float bottomV = Math.max(0.1f, Math.min(0.68f, hsv[2] - 0.04f));
        int bottomColor = Color.HSVToColor(new float[]{hsv[0], hsv[1], bottomV});
        int a0 = Math.round(Math.max(0f, Math.min(1f, 0.8f - 0.04f)) * 255);
        int a1 = Math.round(Math.max(0f, Math.min(1f, 0.8f + 0.02f)) * 255);
        int a2 = Math.round(Math.max(0f, Math.min(1f, 0.8f)) * 255);
        int a3 = Math.round(Math.max(0f, Math.min(1f, 0.8f + 0.06f)) * 255);
        Paint tint = new Paint();
        tint.setShader(new LinearGradient(0, 0, 0, viewH,
                new int[]{
                        Color.argb(a0, Color.red(topColor), Color.green(topColor), Color.blue(topColor)),
                        Color.argb(a1, Color.red(base), Color.green(base), Color.blue(base)),
                        Color.argb(a2, Color.red(base), Color.green(base), Color.blue(base)),
                        Color.argb(a3, Color.red(bottomColor), Color.green(bottomColor), Color.blue(bottomColor))},
                new float[]{0f, 0.4f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(new RectF(0, 0, viewW, viewH), tint);
    }

    /** 一层满幅背景合成（后台线程跑）。底＝原图缩到 64px 宽拉满；经典＝整幅模糊底＋整幅四段取色渐变罩
     *  （GZ CoverAmbientKt:329：停点 0/0.4/0.78/1，alpha＝基数 0.8＋偏移钳制 [0,1]，颜色＝ambient 经 toneAdjust、
     *  两端按 s() 平移明度 V），模糊口径：API31+ 由前台视图 RenderEffect 24dp 落地（位图层跳过 FastBlur），
     *  API<31 回落 FastBlur 25；48dp 熔边条、封面带归零罩子与黑压暗已删（瓜子无此物）；
     *  其余主题＝只压暗（卡／盘／架是独立物件，不熔化），不读 ambient 参数；歌词页＝整幅压暗。
     *  ambient 由 buildCompositeAsync 在主线程快照当前氛围色后透传。 */
    private static Bitmap buildPlayerBackground(Bitmap src, int viewW, int viewH, int topOff, int cardBottom, int mode, float density, int ambient) {
        if (src == null || src.isRecycled() || viewW <= 0 || viewH <= 0) return null;
        boolean sharpTop = mode == BG_SHARP;
        if (sharpTop && cardBottom <= topOff) return null;
        // Glide 可能给 HARDWARE 位图，软 Canvas 画不了，先转一份软位图（原图不动、Glide 仍持有）；
        // HARDWARE 枚举是 API 26 才有的，星辰最低 API 24，SDK 门挡住、老机器上不许求值该字段
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
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            int smallH = Math.max(1, Math.round(64f * work.getHeight() / (float) work.getWidth()));
            Bitmap small = Bitmap.createScaledBitmap(work, 64, smallH, true);
            // 经典 API31+：位图层不做 FastBlur，糊底由前台 ivBgBlur 的 RenderEffect 24dp 落地
            // （瓜子 BlurredCoverAmbient 24dp 实时模糊口径，见 buildCompositeAsync 贴图处）；
            // API<31 回落 FastBlur 25（树内现行值，非 24dp 精确换算，仅作等效回落）；非经典分支一律走原 FastBlur 25，不动。
            // 真模糊：small 是本函数现生成的图，canReuse=true 原地模糊安全（绝不能传缓存 bgFull/bgSoft）；
            // 模糊失败（半径非法时才返 null，本处半径 25 不会）则回落直贴，不崩
            boolean renderEffectBlur = sharpTop && android.os.Build.VERSION.SDK_INT >= 31;
            if (!renderEffectBlur) {
                Bitmap blurred = FastBlur.blur(small, 25, true);
                if (blurred != null) small = blurred;
            }
            canvas.drawBitmap(small, null, new RectF(0, 0, viewW, viewH), paint);
            small.recycle();
            if (sharpTop) {
                // 经典＝瓜子整幅模糊底＋整幅 4 段罩（与 Apple 共用 drawAmbientVeil：停点 0/0.4/0.78/1，
                // alpha 基数 0.8）；48dp 熔边条与封面带 alpha 恒 0 的归零罩子已删（瓜子无此物）。
                // 注：罩子与糊底同在一张位图里，视图层 RenderEffect 会同时柔化罩子渐变
                // （停点不变、过渡更 soft），真机需核罩子是否仍读得出 4 段。
                drawAmbientVeil(canvas, viewW, viewH, ambient);
            } else if (mode == BG_APPLE) {
                // Apple 外壳罩子（GZ 外壳口径）：糊底照片上整幅盖 4 段氛围罩子，无锐顶、无熔边
                //（Apple 封面是布局自己的 tpCover，不走背景锐顶）；此前的 BG_DARK 弱压暗使控制区仍像照片，作废。
                drawAmbientVeil(canvas, viewW, viewH, ambient);
            } else if (mode == BG_LYRICS) {
                Paint dark = new Paint();
                dark.setShader(new LinearGradient(0, 0, 0, viewH,
                        new int[]{0x4D000000, 0x99000000}, null, Shader.TileMode.CLAMP));
                canvas.drawRect(new RectF(0, 0, viewW, viewH), dark);
            } else {
                // DARK：卡／盘下缘（唱片架无封面则屏顶）透明 → 屏底黑 0.55；顶部保持亮，保深色标题可读
                int startY = Math.max(0, cardBottom);
                if (startY < viewH) {
                    Paint dark = new Paint();
                    dark.setShader(new LinearGradient(0, startY, 0, viewH,
                            new int[]{0x00000000, 0x8C000000}, null, Shader.TileMode.CLAMP));
                    canvas.drawRect(new RectF(0, startY, viewW, viewH), dark);
                }
            }
            return out;
        } finally {
            if (copied) work.recycle();
        }
    }


    /** 氛围色 450ms 渐变过渡（GZ animateColorAsState tween(450, bs4.a)，PlayerScreenKt coverDominantAmbient） */
    /** A4 流体层：开时可见、颜色取当前封面取色（氛围色＋主色＋toneAdjust）；关时隐藏走原糊底＋氛围层。
     * 律动未接通（level 恒 0，见 MusicRhythmSource 说明），流体仍按时间正弦漂移。 */
    private void applyFluidBg() {
        try {
            if (binding == null || binding.fluidAmbient == null) return;
            boolean on = MusicStore.isFluidBg();
            binding.fluidAmbient.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on) {
                binding.fluidAmbient.setColors(ambientColor, dominantColor,
                        CoverColor.toneAdjust(ambientColor));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * A7 横屏排布分支（仅横屏，竖屏直接返回；旋转会重建 Activity，分支每次重算，无需复位）。
     * 骨架横屏布局里歌词内页已是左右分栏（封面列左、歌词＋主控右列）；
     * 左手模式把封面列换到右侧（主控行与功能键居左半区，封面与歌词居右半区），右手模式保持默认列序。
     * 边界：主题内页沿用竖屏组合（封面／歌词／主控都在主题布局内竖排），逐主题横版文件不在本批文件清单内，
     * 主题内页的左右分栏未做，见报告未落项。
     */
    private void applyLandscapeLayout() {
        try {
            if (getResources().getConfiguration().orientation
                    != android.content.res.Configuration.ORIENTATION_LANDSCAPE) return;
            View coverCol = findViewById(R.id.lyricsCoverCol);
            View mainCol = findViewById(R.id.lyricsMainCol);
            if (coverCol == null || mainCol == null || coverCol.getParent() != mainCol.getParent()) return;
            ViewGroup parent = (ViewGroup) coverCol.getParent();
            boolean leftHand = MusicStore.isLeftHandLandscape();
            int coverIdx = parent.indexOfChild(coverCol);
            if (leftHand && coverIdx == 0) {
                parent.removeView(coverCol);
                parent.addView(coverCol);
            } else if (!leftHand && coverIdx != 0) {
                parent.removeView(coverCol);
                parent.addView(coverCol, 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void animateAmbient(int to) {
        syncStatusBarIcons(to);
        if (ambientAnimator != null) ambientAnimator.cancel();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, to);
        ambientAnimator.setDuration(450);
        ambientAnimator.setInterpolator(INTERP_FOSI);
        ambientAnimator.addUpdateListener(a -> {
            ambientColor = (int) a.getAnimatedValue();
            // 整页一体：背景就是封面糊底本身，不许再盖一层取色色块（viewAmbient 已从布局删除）；
            // 歌词内页同样透明共用糊底，不许再刷氛围色底。
            if (binding.fluidAmbient != null && binding.fluidAmbient.getVisibility() == View.VISIBLE) {
                binding.fluidAmbient.setColors(ambientColor, dominantColor,
                        CoverColor.toneAdjust(ambientColor));
            }
        });
        ambientAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (lyricsPageOpen) applyPageFade();
                // M5：动画结束重合成（只走这一路）。氛围动画 450ms 内合成用的还是旧氛围色，
                // 动画跑完当前 ambientColor 已变、贴图却还是旧色合成的——经典态下若贴图已成、
                // 封面未换、合成氛围色与当前氛围色不一致，就清掉合成态再 refresh 重走合成。
                // 清法：bgCompositeApplied=false 且 bgCompositeUrl=null（refresh 的
                // 早返三条件全破，清 applied 一项已够，Url 一并清是显式失效，不留半态）。
                // animation==ambientAnimator  guard：中途被新动画 cancel 掉的旧动画不触发，
                // 只按最终动画的结束色重合成一次。重合成不调 animateAmbient，不会动画循环。
                if (animation == ambientAnimator && compositeMode() == BG_SHARP && bgCompositeApplied) {
                    MusicTrack cur = MusicController.get().current();
                    String cover = cur == null ? null : effectiveCover(cur);
                    if (cover != null && cover.equals(bgCompositeUrl) && bgCompositeAmbient != ambientColor) {
                        bgCompositeApplied = false;
                        bgCompositeUrl = null;
                        refreshPlayerBackground();
                    }
                }
            }
        });
        ambientAnimator.start();
    }

    /** Apple 第三行：来源·格子短码；无实测整行隐藏（任务口径）。经典/其它主题走 sourceName 逻辑同旧版 */
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
        if (com.fongmi.android.tv.music.HkMusicSource.PLATFORM.equals(track.platform)) {
            return com.fongmi.android.tv.music.HkMusicSource.siteNameOf(track);
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
        lyricLoading = true;
        lyricMains.clear();
        lyricTrans.clear();
        lyricRomas.clear();
        lyricTransWords.clear();
        lyricRomaWords.clear();
        lyricIndex = -1;
        // B2：切歌先清版本条状态，旧曲版本不许残留到新曲
        currentLyricVersions.clear();
        currentLyricFormat = null;
        currentLyricKey = null;
        lyricVersionSel = -1;
        if (lyricVersionRow != null) lyricVersionRow.setVisibility(View.GONE);
        // 切歌必清词：showLoading 只改空态不碰视图旧行，onDraw 在行非空时照画旧行；
        // 本地歌无词走全回退链（跨源＋歌词插件＋AMLL）耗时久，期间上一首的词会
        // 一直贴在新歌上（实拍：来不及爱你播着却显示每段路的词）。切歌（非 force）
        // 必须立即清掉视图行＋歌词格标签；手动重搜（force）是同首歌，旧词即本歌词，
        // 按原设计保留并叠加搜索提示，不清。
        // 版本弹窗跨切歌会残留旧歌版本快照：点选时 applyLyricVersion 只验解析期间
        // 切歌未发生，不验版本归属，旧歌词会贴到新歌上且守门放行；切歌即关掉弹窗。
        if (!force) {
            if (lyricView != null) lyricView.setLines(null, null, null);
            if (lyricsPageOpen && pageLyricView != null) pageLyricView.setLines(null, null, null);
            updateLyricChipLabel(null);
            Fragment f = getSupportFragmentManager().findFragmentByTag("music_lyric_versions");
            if (f instanceof DialogFragment) ((DialogFragment) f).dismissAllowingStateLoss();
        }
        updateCurrentLyricTexts(MusicController.get().position());
        Task.execute(() -> {
            List<LyricsLine> mains = new ArrayList<>();
            List<String> trans = new ArrayList<>();
            List<String> romas = new ArrayList<>();
            List<List<com.fongmi.android.tv.player.lyrics.LyricsWord>> transWords = new ArrayList<>();
            List<List<com.fongmi.android.tv.player.lyrics.LyricsWord>> romaWords = new ArrayList<>();
            String richFormat = null;
            String richKey = null;
            boolean richProvided = false;
            LxKeyIssue keyIssue = null;
            // 第三波 G-C1：已下载歌离线优先读旁路歌词（下载完成时同下的 stem.lrc，
            // 见 MusicDownloadStore.downloadSidecars）；旁路命中即不再走在线链，
            // 旁路为空才走原 rich／纯文本链。内嵌歌词（音频文件内标签）本波未读：
            // 播放链无标签歌词读取口径，旁路是唯一离线词源。
            String sidecarLrc = com.fongmi.android.tv.music.MusicDownloadStore.readSidecarLyric(track);
            if (sidecarLrc != null && !sidecarLrc.trim().isEmpty()) {
                mains = LyricsParser.parseWordLyric(sidecarLrc);
                richProvided = !mains.isEmpty();
                if (richProvided) richKey = sidecarLrc.trim();
            }
            // 2026-10-22 瓜子秒出对照：先查歌词缓存（内存＋磁盘），命中直接解析、不再走全链；手动重搜绕过读缓存。
            MusicLyric cached = force ? null : com.fongmi.android.tv.music.MusicLyricCache.get(track);
            try {
                MusicLyric rich = richProvided ? null : (cached != null ? cached : MusicRepository.lyricRich(track, force));
                if (rich != null) {
                    if (rich.rawLrc != null && !rich.rawLrc.isEmpty()) {
                        mains = LyricsParser.parseWordLyric(rich.rawLrc);
                    }
                    // M-02：翻译／音译改走 parseWordLyric 保词（不再走丢词时间的 parseTimed）；
                    // 词时间已由解析侧换算为相对行首偏移，整行字符串仍作词空时回落。
                    List<LyricsLine> transLines = rich.translation == null || rich.translation.isEmpty()
                            ? null : LyricsParser.parseWordLyric(rich.translation);
                    List<LyricsLine> romaLines = rich.romanization == null || rich.romanization.isEmpty()
                            ? null : LyricsParser.parseWordLyric(rich.romanization);
                    List<RichLyrics.Line> aligned = RichLyrics.align(mains, transLines, romaLines);
                    trans.clear();
                    romas.clear();
                    transWords.clear();
                    romaWords.clear();
                    for (RichLyrics.Line line : aligned) {
                        trans.add(line.translation);
                        romas.add(line.romanization);
                        transWords.add(line.translationWords);
                        romaWords.add(line.romanizationWords);
                    }
                }
                richProvided = !mains.isEmpty();
                // 第三波 G-C1：旁路命中时 rich 为 null（未走在线链），不取 rich 字段；
                // richKey 已在旁路分支写成旁路文本 trim，版本条不以此定位在线版。
                if (richProvided && rich != null) {
                    richFormat = rich.format;
                    richKey = rich.rawLrc == null ? null : rich.rawLrc.trim();
                    if (cached == null) com.fongmi.android.tv.music.MusicLyricCache.put(track, rich);
                }
                if (mains.isEmpty()) {
                    // rich 通道没拿到正文时回退旧纯文本链（口径与旧版 loadLyric 一致）
                    String lrc = MusicRepository.lyric(track, force);
                    if (lrc != null && !lrc.isEmpty()) {
                        mains = LyricsParser.parseWordLyric(lrc);
                        if (!mains.isEmpty()) com.fongmi.android.tv.music.MusicLyricCache.putPlain(track, lrc);
                    }
                }
                // 手动重搜确认无词（非密钥问题）时清掉旧缓存，不让 stale 的错词一直贴着。
                if (force && mains.isEmpty() && keyIssue == null) com.fongmi.android.tv.music.MusicLyricCache.remove(track);
            } catch (LxKeyIssue e) {
                // B4：密钥问题不吞——记下来给 UI 回调弹提示；其余失败照旧静默
                keyIssue = e;
            } catch (Throwable ignored) {
            }
            List<LyricsLine> resultMains = mains;
            List<String> resultTrans = trans;
            List<String> resultRomas = romas;
            List<List<com.fongmi.android.tv.player.lyrics.LyricsWord>> resultTransWords = transWords;
            List<List<com.fongmi.android.tv.player.lyrics.LyricsWord>> resultRomaWords = romaWords;
            String resultFormat = richProvided ? richFormat : null;
            String resultKey = richProvided ? richKey : null;
            LxKeyIssue resultKeyIssue = keyIssue;
            runOnUiThread(() -> {
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cur.equals(track)) return;
                currentLyricFormat = resultFormat;
                currentLyricKey = resultKey;
                applyLyricData(resultMains, resultTrans, resultRomas, resultTransWords, resultRomaWords);
                lyricEmpty = resultMains.isEmpty();
                lyricLoading = false;
                // 歌词到达事件（唱片架歌词面入场时钟）：无词也是到达（空态已定），挂起的入场即跑，
                // 不等版本聚合（版本条是第二阶段，不阻塞歌词面）。
                onShelfLyricArrived();
                // 取词确认为空才露空态；第三波 A4：区分本源无词（未走兜底：开关关且非手动重搜）与兜底也无词
                String noLyricLabel = (force || MusicStore.isLyricFallback()) ? "兜底也无词" : "本源无词";
                if (resultMains.isEmpty() && lyricView != null) lyricView.showNoLyric(noLyricLabel);
                if (resultMains.isEmpty() && lyricsPageOpen && pageLyricView != null) pageLyricView.showNoLyric(noLyricLabel);
                if (force) {
                    Notify.show(resultMains.isEmpty() ? R.string.music_lyric_not_found : R.string.music_lyric_found);
                }
                // B4：歌词路密钥问题——仍是当前曲才弹；重试＝强制重取本曲歌词
                if (resultKeyIssue != null) {
                    showKeyIssue(resultKeyIssue.getPlatform(), resultKeyIssue.getMessage(),
                            () -> loadLyric(track, true));
                }
                // B2 第二阶段：快路径先显词不被聚合拖慢；后台再全量聚合版本，仍是当前曲且多于 1 版才露筛选条
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
                        if (cur2 == null || !cur2.equals(track)) return;
                        currentLyricVersions.clear();
                        currentLyricVersions.addAll(resultVersions);
                        showLyricVersionRow();
                    });
                });
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

    /** 歌词筹文案：GZ lyricChipLabel 五档——AMLL 档（B8，当前词 format＝"AMLL" 时）＋星辰可判定的四档 */
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

    /** B4：密钥问题弹窗——只接 LxKeyIssue 事件；当前曲是落雪歌才弹（防过期事件）；只提示＋重试，不做密钥／登录入口 */
    private void showKeyIssue(String platform, String detail, Runnable onRetry) {
        if (isFinishing() || isDestroyed()) return;
        MusicTrack cur = MusicController.get().current();
        if (cur == null || !"lx".equals(cur.platform)) return;
        LxKeyIssueDialog.show(this, platform, detail, onRetry);
    }
    /** B2：版本筛选条——仅多于 1 版时显示；当前版按 currentLyricKey 定位，找不到回落首版。
     * 第三波残留 L9 分工：本条＝版本切换（已聚合版本间直接切），类型筛只在版本对话框里
     * （MusicLyricVersionDialog），页内不另加类型条，两处语义不打架。 */
    private void showLyricVersionRow() {
        if (currentLyricVersions.size() <= 1) {
            if (lyricVersionRow != null) lyricVersionRow.setVisibility(View.GONE);
            return;
        }
        ensureLyricVersionRow();
        if (lyricVersionChips == null) return;
        lyricVersionChips.removeAllViews();
        // L8：快路径回退纯文本时 currentLyricKey 为空，筛选条无选中（旧默认首版高亮与实际显示的词无关）。
        int sel = -1;
        for (int i = 0; i < currentLyricVersions.size(); i++) {
            MusicLyric l = currentLyricVersions.get(i).getLyric();
            String key = l == null || l.rawLrc == null ? null : l.rawLrc.trim();
            if (currentLyricKey != null && currentLyricKey.equals(key)) sel = i;
        }
        lyricVersionSel = sel;
        for (int i = 0; i < currentLyricVersions.size(); i++) {
            final int index = i;
            TextView chip = new TextView(this);
            chip.setText(currentLyricVersions.get(i).displayLabel());
            chip.setTextSize(12);
            chip.setPadding(lyricDp(12), lyricDp(6), lyricDp(12), lyricDp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(lyricDp(8));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                if (lyricVersionSel == index) return;
                lyricVersionSel = index;
                refreshLyricVersionChips();
                applyLyricVersion(currentLyricVersions.get(index));
            });
            lyricVersionChips.addView(chip);
        }
        refreshLyricVersionChips();
        lyricVersionRow.setVisibility(View.VISIBLE);
    }

    /** 第 13 项：歌词版本面板入口——供工具表设置卡调用；L9：0／1 版时也允许开面板
     * （对话框内空态文案＋搜索入口，见 MusicLyricVersionDialog），不再以“多于 1 版”拦截。 */
    public int getLyricVersionCount() {
        return currentLyricVersions.size();
    }

    public void openLyricVersionDialog() {
        MusicLyricVersionDialog.showWithSearch(this, MusicController.get().current(),
                new ArrayList<>(currentLyricVersions), currentLyricKey, this::applyLyricVersion,
                this::searchLyric);
    }

    /** B2：版本筛选条只建一次，插在全屏歌词页纵列中 pgLyricsView 上方（页内，不改布局文件） */
    private void ensureLyricVersionRow() {
        if (lyricVersionRow != null || lyricsPage == null) return;
        ViewGroup page = (ViewGroup) lyricsPage;
        if (page.getChildCount() == 0) return;
        View column = page.getChildAt(0);
        if (!(column instanceof ViewGroup)) return;
        ViewGroup col = (ViewGroup) column;
        int idx = -1;
        for (int i = 0; i < col.getChildCount(); i++) {
            if (col.getChildAt(i) == pageLyricView) {
                idx = i;
                break;
            }
        }
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(lyricDp(16), lyricDp(4), lyricDp(16), lyricDp(4));
        hsv.addView(chips, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(hsv, idx < 0 ? 0 : idx, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricVersionRow = hsv;
        lyricVersionChips = chips;
    }

    /** B2：版本筹样式——选中深底白字，未选中描边＋深字（与歌词页浅色面同一口径） */
    private void refreshLyricVersionChips() {
        if (lyricVersionChips == null) return;
        for (int i = 0; i < lyricVersionChips.getChildCount(); i++) {
            View child = lyricVersionChips.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            boolean sel = i == lyricVersionSel;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(lyricDp(16));
            if (sel) {
                bg.setColor(0xFF1C1C1E);
                ((TextView) child).setTextColor(0xFFFFFFFF);
            } else {
                bg.setStroke(lyricDp(1), 0xFF8E8E93);
                ((TextView) child).setTextColor(0xFF1C1C1E);
            }
            child.setBackground(bg);
        }
    }

    /** B2：切版本——重走解析＋对齐＋喂词（与 loadLyric 同一口径），只换词数据，不换曲目 */
    private void applyLyricVersion(LyricVersion version) {
        if (version == null || version.getLyric() == null) return;
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        // L10 接线：点选即写盘（MusicRepository.saveManualLyricChoice，第一波已备恢复逻辑与键）；
        // 恢复失败提示口径与第一波一致（lyricRich 内“手动选择的歌词版本已失效”），此处不另弹。
        MusicRepository.saveManualLyricChoice(track, version);
        MusicLyric rich = version.getLyric();
        Task.execute(() -> {
            List<LyricsLine> mains = new ArrayList<>();
            List<String> trans = new ArrayList<>();
            List<String> romas = new ArrayList<>();
            List<List<com.fongmi.android.tv.player.lyrics.LyricsWord>> transWords = new ArrayList<>();
            List<List<com.fongmi.android.tv.player.lyrics.LyricsWord>> romaWords = new ArrayList<>();
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
                    transWords.add(line.translationWords);
                    romaWords.add(line.romanizationWords);
                }
            } catch (Throwable ignored) {
            }
            List<LyricsLine> resultMains = mains;
            List<String> resultTrans = trans;
            List<String> resultRomas = romas;
            List<List<com.fongmi.android.tv.player.lyrics.LyricsWord>> resultTransWords = transWords;
            List<List<com.fongmi.android.tv.player.lyrics.LyricsWord>> resultRomaWords = romaWords;
            runOnUiThread(() -> {
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cur.equals(track)) return;
                currentLyricFormat = rich.format;
                currentLyricKey = rich.rawLrc == null ? null : rich.rawLrc.trim();
                // L7：面板切版本后筛选条高亮同步（旧版只改 key 不改 sel，条仍亮旧版、点旧版筹无反应）。
                lyricVersionSel = -1;
                for (int i = 0; i < currentLyricVersions.size(); i++) {
                    MusicLyric lv = currentLyricVersions.get(i).getLyric();
                    String k = lv == null || lv.rawLrc == null ? null : lv.rawLrc.trim();
                    if (currentLyricKey != null && currentLyricKey.equals(k)) {
                        lyricVersionSel = i;
                        break;
                    }
                }
                refreshLyricVersionChips();
                applyLyricData(resultMains, resultTrans, resultRomas, resultTransWords, resultRomaWords);
            });
        });
    }

    private int lyricDp(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void applyLyricData(List<LyricsLine> mains, List<String> trans, List<String> romas,
                                List<List<com.fongmi.android.tv.player.lyrics.LyricsWord>> transWords,
                                List<List<com.fongmi.android.tv.player.lyrics.LyricsWord>> romaWords) {
        lyricMains.clear();
        lyricMains.addAll(mains);
        lyricTrans.clear();
        lyricTrans.addAll(trans);
        lyricRomas.clear();
        lyricRomas.addAll(romas);
        lyricTransWords.clear();
        if (transWords != null) lyricTransWords.addAll(transWords);
        lyricRomaWords.clear();
        if (romaWords != null) lyricRomaWords.addAll(romaWords);
        lyricIndex = -1;
        updateLyricChipLabel(mains);
        feedLyricView();
        updateCurrentLyricTexts(MusicController.get().position());
    }

    private void feedLyricView() {
        if (lyricView != null) {
            lyricView.setLines(new ArrayList<>(lyricMains), new ArrayList<>(lyricTrans), new ArrayList<>(lyricRomas),
                    new ArrayList<>(lyricTransWords), new ArrayList<>(lyricRomaWords));
            lyricView.setPlaying(playing);
            lyricView.updatePosition(MusicController.get().position());
        }
        if (lyricsPageOpen) feedPageLyricView();
    }

    /** 歌词内页喂词：与播放页同一份 lyricMains 数据，不再单独取词/解析（双份链已删） */
    private void feedPageLyricView() {
        if (pageLyricView == null) return;
        pageLyricView.setLines(new ArrayList<>(lyricMains), new ArrayList<>(lyricTrans), new ArrayList<>(lyricRomas),
                new ArrayList<>(lyricTransWords), new ArrayList<>(lyricRomaWords));
        pageLyricView.setPlaying(playing);
        pageLyricView.updatePosition(MusicController.get().position());
        refreshPageWordChip();
    }

    /** 歌词时间偏移读值（U-05：读 MusicStore.getLyricOffsetMs()，面板路键 music_lyric_offset_ms，毫秒整数，默认 0；本面只读值，不建设置 UI）。 */
    private long lyricOffsetMs() {
        try {
            return MusicStore.getLyricOffsetMs();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 偏移变化后单行歌词立即重算（U-05：面板路写值后调此方法＋两 KLV 的 refreshLyricOffset，不用等切歌）。 */
    public void refreshLyricOffset() {
        lyricIndex = Integer.MIN_VALUE;
        updateCurrentLyricTexts(MusicController.get().position());
        if (lyricView != null) lyricView.refreshLyricOffset();
        if (pageLyricView != null) pageLyricView.refreshLyricOffset();
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
            advanced = MusicStore.isAdvancedFx();
            // 经典：封面歌词模糊不再硬编码轻档（旧 blur=1），改读封面模糊档（默认 medium）；
            // 其余主题本批不动，仍走旧值 1。
            if (THEME_CLASSIC.equals(themeId)) blur = MusicStore.getCoverLyricBlurLevel();
        } catch (Throwable ignored) {
        }
        if (lyricView != null) {
            lyricView.setPrimary(primary);
            lyricView.setShowSub(showTrans, showRoma);
            // L5：本页歌词视图恒为封面模式（经典内嵌/唱片架卡片）：直接用封面键字号（默认 22），
            // 不再用全屏值 -2 换算；全屏歌词内页用全屏键原值（见 applyPageLyricStyle，默认 24）。
            try {
                lyricView.setMainTextSizeSp(MusicStore.getCoverLyricFontSize());
            } catch (Throwable ignored) {
                lyricView.setMainTextSizeSp(Math.max(14f, fontSize));
            }
            lyricView.setLyricAlign(align);
            lyricView.setLyricWeight(weight);
            lyricView.setBlurLevel(blur);
            lyricView.setAdvancedFx(advanced);
            // 流光 CD 歌词照 GZ CdLyricsList 固定口径：21sp、居中、700（KLV 粗细档 0 即 700，与 GZ BOLD 同值；
            // 旧注释写“标准粗细”误导，实为 700，不跟随面板字号／对齐／粗细——与瓜子固定口径一致，面板显示值问题另见报告）。
            if (THEME_CD.equals(themeId)) {
                lyricView.setMainTextSizeSp(21f);
                lyricView.setLyricAlign(1);
                lyricView.setLyricWeight(0);
            }
            // C-3：封面卡片展开态歌词对齐照 GZ m129CoverCurrentLyric 固定居中，不跟随用户对齐。
            if (THEME_IMMERSIVE.equals(themeId)) {
                lyricView.setLyricAlign(1);
            }
        }
        // C-2：封面卡片玻璃条单行歌词照 GZ CoverBarLyricLine 固定居中，不跟随用户对齐。
        int gravity = Gravity.CENTER | Gravity.CENTER_VERTICAL;
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
        // wave2：主歌词档变了，单行／摘要行要立即按新档重算（行下标未变时 updateCurrentLyricTexts 早返回，故先复位）。
        lyricIndex = Integer.MIN_VALUE;
        updateCurrentLyricTexts(MusicController.get().position());
    }

    /** 当前行文本刷新（封面卡片一行；流光 CD 走 KaraokeLyricsView 组件，不经此处）
     * 第三波 L1：单行匹配同样按偏移换算（查找位置＝播放位置 ＋ offset，正偏移＝歌词提前显示）。
     * L10：单行主歌词跟随主歌词档（原文／翻译／音译，缺数据回落原文）；字号／粗细照 GZ 玻璃条固定 13sp／500，不跟随。 */
    private void updateCurrentLyricTexts(long position) {
        int idx = lyricMains.isEmpty() ? -1 : LyricsParser.findLine(lyricMains, position + lyricOffsetMs());
        if (idx == lyricIndex) return;
        lyricIndex = idx;
        String main = "";
        if (idx >= 0 && idx < lyricMains.size()) {
            main = currentMainText(idx);
        } else if (idx < 0 && !lyricMains.isEmpty() && LyricsParser.isUntimed(lyricMains)) {
            // 无时间轴无当前行：玻璃条不空白，静态锚首行（GZ 玻璃条对 untimed 的行为未抠到，此为星辰侧自定）
            main = currentMainText(0);
        }
        if (lyricLineView != null) {
            if (THEME_IMMERSIVE.equals(themeId) && coverExpanded) lyricLineView.setText(R.string.music_cover_back);
            else lyricLineView.setText(main);
        }
        updateCdSummary(main, idx);
    }

    /** 主歌词档取当前行主文本（与 KLV mainTextFor 同口径：翻译／音译缺数据时回落原文）。 */
    private String currentMainText(int idx) {
        if (idx < 0 || idx >= lyricMains.size()) return "";
        LyricsLine line = lyricMains.get(idx);
        String original = line == null || line.getText() == null ? "" : line.getText();
        int primary = 0;
        try {
            primary = MusicStore.getLyricPrimary();
        } catch (Throwable ignored) {
        }
        if (primary == 1 && idx < lyricTrans.size()) {
            String t = lyricTrans.get(idx);
            if (t != null && !t.isEmpty()) return t;
        } else if (primary == 2 && idx < lyricRomas.size()) {
            String t = lyricRomas.get(idx);
            if (t != null && !t.isEmpty()) return t;
        }
        return original;
    }

    /** CD 摘要行刷新（wave2：主行＝当前行主文本，副行＝该行第一副行；无当前行时两行留空）。 */
    private void updateCdSummary(String main, int idx) {
        if (!THEME_CD.equals(themeId) || cdSummaryView == null) return;
        if (cdSummaryMain != null) cdSummaryMain.setText(main == null ? "" : main);
        if (cdSummarySub != null) {
            String sub = "";
            if (idx >= 0 && idx < lyricMains.size()) {
                int primary = 0;
                boolean showTrans = true;
                boolean showRoma = false;
                try {
                    primary = MusicStore.getLyricPrimary();
                    showTrans = MusicStore.isShowTranslation();
                    showRoma = MusicStore.isShowRomanization();
                } catch (Throwable ignored) {
                }
                if (primary != 0 && idx < lyricMains.size()) {
                    LyricsLine line = lyricMains.get(idx);
                    if (line != null && line.getText() != null && !line.getText().isEmpty()) sub = line.getText();
                } else if (primary != 1 && showTrans && idx < lyricTrans.size()) {
                    sub = lyricTrans.get(idx) == null ? "" : lyricTrans.get(idx);
                } else if (primary != 2 && showRoma && idx < lyricRomas.size()) {
                    sub = lyricRomas.get(idx) == null ? "" : lyricRomas.get(idx);
                }
                // 主行已是翻译／音译时，副行不再重复主行文本（与 KLV subTextsFor 同口径）。
                if (sub.equals(currentMainText(idx))) sub = "";
            }
            cdSummarySub.setText(sub);
        }
    }

    /** 封面卡片展开态开关（GZ：点封面切换；展开时玻璃条中段文案变「返回到封面模式」）；
     * redo3 第四轮 V11：不再 setVisibility 硬切，改走 animateCoverExpanded 三路 350ms 动画；
     * lyricLineView 文案切换与 lyricIndex 复位逻辑原样保留。 */
    private void toggleCoverExpanded() {
        View exp = find(R.id.tpCoverExpanded);
        if (exp == null) return;
        coverExpanded = exp.getVisibility() != View.VISIBLE;
        animateCoverExpanded(coverExpanded);
        if (lyricLineView != null) {
            if (coverExpanded) lyricLineView.setText(R.string.music_cover_back);
            else {
                lyricIndex = Integer.MIN_VALUE;
                updateCurrentLyricTexts(MusicController.get().position());
            }
        }
    }

    /** 封面卡片展开切换动画（redo3 第四轮 V11，星辰侧保留的交互动效、行为不动）：
     * 正名：瓜子展开分支是 plain if 切换、无 transitionSpec 字面量，本动画的 350ms／0.94→1／→0.92
     * 数值与瓜子 ThreeCardAlternateStack 切歌换卡 spec 撞值，但不是瓜子展开动效的出处——切歌换卡
     * 的对齐已移到 bindCovers 沉浸分支，本函数只是把既有展开手感原样保留，不改直切（无字面值可对、
     * 直切是拿“无证据”当证据，且会丢顺滑手感）；收起滑出四分之一幅同样是星辰侧既有值、瓜子侧无字面量。
     * 平移（满幅入场／四分之一幅出场，展开取一向、收起取反向：入场自右侧满幅滑入，收起向右侧滑出四分之一幅）、
     * 透明度（淡入／淡出均 INTERP_FOSI）、缩放（入场 0.94→1／出场 1→0.92）；
     * 入场平移／缩放用 INTERP_EMPH，出场平移／缩放用 INTERP_OUT_C；出场动画结束后再 setVisibility(GONE)。 */
    private void animateCoverExpanded(boolean expand) {
        View exp = find(R.id.tpCoverExpanded);
        if (exp == null) return;
        if (coverExpandAnim != null) coverExpandAnim.cancel();
        if (expand) {
            exp.setVisibility(View.VISIBLE);
            if (exp.getWidth() <= 0) {
                exp.post(() -> animateCoverExpanded(true));
                return;
            }
            float w = exp.getWidth();
            exp.setTranslationX(w);
            exp.setAlpha(0f);
            exp.setScaleX(0.94f);
            exp.setScaleY(0.94f);
            android.animation.ObjectAnimator tx = android.animation.ObjectAnimator.ofFloat(exp, View.TRANSLATION_X, w, 0f);
            tx.setDuration(350);
            tx.setInterpolator(INTERP_EMPH);
            android.animation.ObjectAnimator al = android.animation.ObjectAnimator.ofFloat(exp, View.ALPHA, 0f, 1f);
            al.setDuration(350);
            al.setInterpolator(INTERP_FOSI);
            android.animation.ObjectAnimator sx = android.animation.ObjectAnimator.ofFloat(exp, View.SCALE_X, 0.94f, 1f);
            sx.setDuration(350);
            sx.setInterpolator(INTERP_EMPH);
            android.animation.ObjectAnimator sy = android.animation.ObjectAnimator.ofFloat(exp, View.SCALE_Y, 0.94f, 1f);
            sy.setDuration(350);
            sy.setInterpolator(INTERP_EMPH);
            coverExpandAnim = new android.animation.AnimatorSet();
            coverExpandAnim.playTogether(tx, al, sx, sy);
            coverExpandAnim.start();
        } else {
            float w = exp.getWidth() > 0 ? exp.getWidth() : getResources().getDisplayMetrics().widthPixels;
            android.animation.ObjectAnimator tx = android.animation.ObjectAnimator.ofFloat(exp, View.TRANSLATION_X, exp.getTranslationX(), w / 4f);
            tx.setDuration(350);
            tx.setInterpolator(INTERP_OUT_C);
            android.animation.ObjectAnimator al = android.animation.ObjectAnimator.ofFloat(exp, View.ALPHA, exp.getAlpha(), 0f);
            al.setDuration(350);
            al.setInterpolator(INTERP_FOSI);
            android.animation.ObjectAnimator sx = android.animation.ObjectAnimator.ofFloat(exp, View.SCALE_X, exp.getScaleX(), 0.92f);
            sx.setDuration(350);
            sx.setInterpolator(INTERP_OUT_C);
            android.animation.ObjectAnimator sy = android.animation.ObjectAnimator.ofFloat(exp, View.SCALE_Y, exp.getScaleY(), 0.92f);
            sy.setDuration(350);
            sy.setInterpolator(INTERP_OUT_C);
            coverExpandAnim = new android.animation.AnimatorSet();
            coverExpandAnim.playTogether(tx, al, sx, sy);
            coverExpandAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    exp.setVisibility(View.GONE);
                    exp.setTranslationX(0f);
                    exp.setAlpha(1f);
                    exp.setScaleX(1f);
                    exp.setScaleY(1f);
                }
            });
            coverExpandAnim.start();
        }
    }

    /**
     * 流光 CD 歌词展开／收起（redo3 F4／D7）：歌词区高度动画，展开 tween(600, bs4.d 近似)、收起 tween(500, bs4.d 近似)。
     * wave2：瓜子独立摘要行——收起态只显摘要行（主行 16sp／副行 12sp，78dp），展开态只显歌词窗；
     * 触发口径（瓜子实证）：展开态布尔由 CdLyricSummary 点按（未展开时置详情＝歌词并取反）与歌曲信息键切换驱动；
     * 星辰映射为——未展开时点歌词区／摘要行展开、展开后点行 seek、点空白处收起；
     * 歌曲信息键变体不另建新键。展开时盘区本身无缩放／淡出（瓜子 dig：盘区 Box 上无 graphicsLayer，
     * 盘径只随可用高约束收缩，见 applyCdDiscSize），故本批不再把盘区高度缩到 0；随 collapse 淡出的是
     * 下方整列（摘要＋进度＋五图标行＋主控），展开内容区与顶行标题各按瓜子公式走 alpha（见动画内注释）。
     */
    private void toggleCdLyricsExpanded() {
        if (lyricView == null || !THEME_CD.equals(themeId)) return;
        ViewGroup parent = (ViewGroup) lyricView.getParent();
        if (parent == null || parent.getHeight() <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        int collapsedH = Math.round(78 * density);
        boolean expanding = !cdLyricsExpanded;
        int fromLyric = lyricView.getHeight() > 0 ? lyricView.getHeight() : collapsedH;
        int toLyric;
        if (expanding) {
            // 固定高＝除歌词窗／盘区／摘要行／弹性空隙外的兄弟高（空隙是收起态填充件，展开时不计入固定高；
            // 动画中它仍在布局里随歌词区显式高自动收缩，同一布局 pass 内抵消，不产生跳变；动画结束再置 GONE，
            // 此时它的 weight 份额已为 0，置 GONE 无视觉变化）。
            View gap = find(R.id.tpCdGap);
            int rest = 0;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (child == lyricView || child == cdSummaryView || child == gap) continue;
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
        cdLyricsExpanded = expanding;
        refreshCdUtilGlyphs();
        // wave2：展开时歌词窗先可见、摘要行先隐藏；收起动画跑完才换回（动画结束回调里换）。
        if (expanding) {
            if (cdSummaryView != null) cdSummaryView.setVisibility(View.GONE);
            lyricView.setVisibility(View.VISIBLE);
        } else {
            // 收起开始先复现弹性空隙：此时它 weight 份额为 0（总高已满），无视觉变化；随后歌词区缩小时
            // 空隙自动伸长吸掉差额，底部各行位置不动；若只在动画结束才复现，收尾会跳一帧（歌词窗 78dp 槽位
            // 与空隙＋摘要行槽位错位）。
            View gap0 = find(R.id.tpCdGap);
            if (gap0 != null) gap0.setVisibility(View.VISIBLE);
        }
        // 动画期间歌词区只用显式高，weight 清 0：显式高＋weight 会双份分剩余高，收起后实际比 78 高且盘区被挤。
        // 盘区参数动画全程不动（无 weight、无显式高改动）；盘径跟随由每帧末的 applyCdDiscSize 负责。
        ViewGroup.LayoutParams lyricLp = lyricView.getLayoutParams();
        if (lyricLp instanceof android.widget.LinearLayout.LayoutParams) {
            ((android.widget.LinearLayout.LayoutParams) lyricLp).weight = 0f;
            lyricView.setLayoutParams(lyricLp);
        }
        // 瓜子三路 collapse 公式（LuminousCdPlayerKt dig §11，collapse＝展开进度 0→1）：
        // 下方整列 alpha＝clamp(1−1.6×collapse,0,1)；展开内容区（歌词窗）alpha＝clamp((collapse−0.15)/0.85,0,1)；
        // 顶行标题 alpha＝clamp(1−1.4×collapse,0,1) 且上移 20dp×collapse（CD_TEXT_SLIDE）。
        // 三路只改 alpha／translationY（渲染属性，不触发布局）；歌词窗显式高动画仍需一次布局 pass（见动效审计）。
        final View topTitle = find(R.id.tpCdTopTitle);
        final View utilRow = find(R.id.tpCdUtilRow);
        final View timeRow = find(R.id.tpCdTimeRow);
        final View mainRow = find(R.id.tpCdMainRow);
        final float slidePx = 20f * density;
        if (cdExpandAnimator != null) cdExpandAnimator.cancel();
        final int startLyric = fromLyric;
        // 卡顿审计注：歌词窗显式高动画仍是逐帧布局（见报告审计节，未达 GPU-only 标准）；
        // 此处只做两点节流：高度取整后未变的帧不 setLayoutParams（600ms 内约半数帧跳过），
        // 盘径重算只跟高度变化帧走（applyCdDiscSize 内另有尺寸不变 guard）；盘区参数动画全程不动。
        final int[] lastLyricH = new int[]{-1};
        cdExpandAnimator = ValueAnimator.ofFloat(0f, 1f);
        cdExpandAnimator.setDuration(expanding ? 600 : 500);
        cdExpandAnimator.setInterpolator(INTERP_CD_EXPAND);
        cdExpandAnimator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float collapse = expanding ? f : 1f - f;
            int newLyricH = Math.round(startLyric + (toLyric - startLyric) * f);
            if (newLyricH != lastLyricH[0]) {
                lastLyricH[0] = newLyricH;
                ViewGroup.LayoutParams lp = lyricView.getLayoutParams();
                if (lp != null) {
                    lp.height = newLyricH;
                    lyricView.setLayoutParams(lp);
                }
                // 盘径只随可用高约束收缩：歌词区显式高变了，可用高才变，post 到布局后重算（尺寸不变时 guard 挡住）。
                applyCdDiscSize();
            }
            float belowAlpha = Math.max(0f, Math.min(1f, 1f - 1.6f * collapse));
            if (cdSummaryView != null) cdSummaryView.setAlpha(belowAlpha);
            if (utilRow != null) utilRow.setAlpha(belowAlpha);
            if (seekView != null) seekView.setAlpha(belowAlpha);
            if (timeRow != null) timeRow.setAlpha(belowAlpha);
            if (mainRow != null) mainRow.setAlpha(belowAlpha);
            if (lyricView != null) {
                lyricView.setAlpha(Math.max(0f, Math.min(1f, (collapse - 0.15f) / 0.85f)));
            }
            if (topTitle != null) {
                topTitle.setAlpha(Math.max(0f, Math.min(1f, 1f - 1.4f * collapse)));
                topTitle.setTranslationY(-slidePx * collapse);
            }
        });
        cdExpandAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (cdLyricsExpanded) {
                    View gap = find(R.id.tpCdGap);
                    if (gap != null) gap.setVisibility(View.GONE);
                }
                // wave2：收起动画跑完，歌词窗隐藏、摘要行复现、弹性空隙复现（展开态不走这条，摘要行早已隐藏）。
                if (!cdLyricsExpanded) {
                    if (lyricView != null) lyricView.setVisibility(View.GONE);
                    if (cdSummaryView != null) cdSummaryView.setVisibility(View.VISIBLE);
                    View gap = find(R.id.tpCdGap);
                    if (gap != null) gap.setVisibility(View.VISIBLE);
                    lyricIndex = Integer.MIN_VALUE;
                    updateCurrentLyricTexts(MusicController.get().position());
                }
                applyCdDiscSize();
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
            setInfoText(R.id.tpInfoArtist, t == null ? "" : (com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(t.displayArtist()) ? getString(R.string.music_unknown_artist) : t.displayArtist()));
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
        if (!openLyricsPageSetup()) return;
        // 呼出动画：pager 横滑的 View 近似——自右侧滑入（瓜子左滑进页，页须跟手自右侧来）
        lyricsPage.setTranslationX(lyricsPageWidth());
        lyricsPage.animate().translationX(0f).setDuration(260).setInterpolator(INTERP_FOSI).start();
    }

    /** 歌词页宽：已量取实宽，未量取屏宽（拖动跟手与动画起手共用，不各写一套回落）。 */
    private float lyricsPageWidth() {
        return lyricsPage != null && lyricsPage.getWidth() > 0 ? lyricsPage.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
    }

    /** 拖动松手收尾时长：按剩余路程折算（全程 260ms 起、120ms 封底），拖得到位收尾就短，不拖满全程慢慢晃。 */
    private long settleDuration(float fromX, float toX, long base) {
        float w = lyricsPageWidth();
        if (w <= 0) return base;
        float frac = Math.abs(toX - fromX) / w;
        return Math.max(120, Math.min(base, Math.round(base * frac)));
    }

    /** 开页建制（不含动画）：可见性＋主题层隐藏＋头＋样式＋喂词＋栈，点按开页与拖动开页共用；
     * 拖动路在首个跟手 MOVE 调一次，松手提交只补收尾动画，不重复建制、不重置位移。 */
    private boolean openLyricsPageSetup() {
        if (MusicController.get().current() == null || lyricsPage == null) return false;
        // 必-1：旗标统一在入口置（歌词键／封面点按／根层左划三路同值，不再各路零散置）。
        appleLyricsPage = THEME_APPLE.equals(themeId);
        lyricsPageOpen = true;
        lyricsPage.setVisibility(View.VISIBLE);
        // 歌词页透明共用糊底，主题层必须同步藏起，否则两页内容同屏叠印（实拍 112847_8_on7i：两套头＋两套词＋两套底部栈＋SQ 重影）。
        if (binding != null && binding.themeContainer != null) binding.themeContainer.setVisibility(View.GONE);
        if (lyricView != null) lyricView.setSuspended(true);
        bindPageHeader(MusicController.get().current());
        // 瓜子常态：副行有数据即显（工具面板歌词设置同款 subReset）；歌词页开页即回到全显，
        // 否则默认关的音译副行有数据也不出（此前歌词页无拼音的根之一）。
        if (!MusicStore.isShowTranslation() || !MusicStore.isShowRomanization()) {
            MusicStore.setShowTranslation(true);
            MusicStore.setShowRomanization(true);
        }
        applyPageLyricStyle();
        feedPageLyricView();
        refreshPageQualityChip();
        paintFavoriteIcons();
        if (pageBtnPlayTop != null) {
            pageBtnPlayTop.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        }
        if (pageLyricView != null) pageLyricView.setPlaying(playing);
        refreshPlayerBackground();
        // 内页开着时 pg 栈同样服从收起态（refreshPageQualityChip 刚跑过，守卫已压住，sync 再兜底）。
        syncBottomStack();
        return true;
    }

    /** 歌词页 Tr 钮（GZ 全屏歌词页右上 Tr）：主歌词三选小菜单——原文恒有，翻译／音译有数据时出
     * （GZ CoverLyricStylePanel 主歌词口径）；选后即时刷新内嵌＋全屏两视图，与工具面板 onLyricStyleChanged 同路。 */
    private void showPageTrMenu() {
        if (pageBtnTr == null) return;
        boolean hasTrans = false;
        boolean hasRoma = false;
        for (String s : lyricTrans) {
            if (s != null && !s.trim().isEmpty()) {
                hasTrans = true;
                break;
            }
        }
        for (String s : lyricRomas) {
            if (s != null && !s.trim().isEmpty()) {
                hasRoma = true;
                break;
            }
        }
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, pageBtnTr);
        int cur = MusicStore.getLyricPrimary();
        menu.getMenu().add(0, 0, 0, R.string.music_primary_original);
        if (hasTrans) menu.getMenu().add(0, 1, 1, R.string.music_primary_translation);
        if (hasRoma) menu.getMenu().add(0, 2, 2, R.string.music_primary_romanization);
        menu.getMenu().setGroupCheckable(0, true, true);
        for (int i = 0; i < menu.getMenu().size(); i++) {
            android.view.MenuItem it = menu.getMenu().getItem(i);
            it.setCheckable(true);
            it.setChecked(it.getItemId() == cur);
        }
        menu.setOnMenuItemClickListener(item -> {
            MusicStore.setLyricPrimary(item.getItemId());
            applyLyricStyle();
            applyPageLyricStyle();
            feedLyricView();
            return true;
        });
        menu.show();
    }

    private void closeLyricsPage() {
        if (!lyricsPageOpen || lyricsPage == null) return;
        closeLyricsPageCleanup();
        // G11：滑出开始即恢复主题层（页向右滑走、露出下层播放页，pager 式揭示，不再滑完突然出现）。
        lyricsPage.animate().translationX(lyricsPageWidth())
                .setDuration(200).setInterpolator(INTERP_LOSI).withEndAction(() -> {
                    if (!lyricsPageOpen) {
                        lyricsPage.setVisibility(View.GONE);
                        refreshPlayerBackground();
                    }
                }).start();
    }

    /** 关页建制（不含动画）：旗标＋主题层恢复＋播放页歌词恢复，点按关页与拖动取消／拖动关页共用；
     * 原 closeLyricsPage 关页起手即做这套，拆出来后语义不变（动画起手前做，不在收尾做）。 */
    private void closeLyricsPageCleanup() {
        lyricsPageOpen = false;
        appleLyricsPage = false;
        if (binding != null && binding.themeContainer != null) binding.themeContainer.setVisibility(View.VISIBLE);
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
        // 内页顶到边后页眉避开状态栏：顶内边距＝状态栏高（根布局已不吃 fitsSystemWindows，内页自己垫）
        if (lyricsPage != null) {
            int sb = 0;
            int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) sb = getResources().getDimensionPixelSize(resId);
            lyricsPage.setPadding(0, sb, 0, 0);
        }
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
        pageBtnTr = findViewById(R.id.pgBtnTr);
        if (pageBtnTr != null) {
            pageBtnTr.setOnClickListener(v -> showPageTrMenu());
            attachPressScale(pageBtnTr, 0.9f);
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
            // 内页歌词区竖滑切歌：快甩切歌、慢拖滚动（分流同播放页歌词区）
            pageLyricView.setOnTrackSwipeListener(next -> {
                lastSkipDirection = next ? 1 : -1;
                if (next) MusicController.get().next();
                else MusicController.get().prev();
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
        if (pageArtistView != null) pageArtistView.setText(com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(track.displayArtist()) ? getString(R.string.music_unknown_artist) : track.displayArtist());
        if (pageCoverView != null) loadIntoCover(pageCoverView, effectiveCover(track), track.displayTitle());
        applyPageFade();
    }

    /** 封面下缘渐变：融进当前氛围色（共用播放页取色结果，切歌后随 animateAmbient 收尾重生成） */
    private void applyPageFade() {
        if (pageCoverFade == null) return;
        pageCoverFade.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x00000000, ambientColor, ambientColor}));
    }

    /** 歌词内页样式：GZ 全屏歌词口径——原值字号（不减 2）、非封面模式，其余项与播放页同源 MusicStore；
     * 对齐跟随存储设置（默认左），不因 Apple 主题硬改居中：瓜子歌词页一直在左边，星辰歌词页居中就是对差。 */
    private void applyPageLyricStyle() {
        if (pageLyricView == null) return;
        try {
            pageLyricView.setCoverMode(false);
            pageLyricView.setPrimary(MusicStore.getLyricPrimary());
            pageLyricView.setShowSub(MusicStore.isShowTranslation(), MusicStore.isShowRomanization());
            pageLyricView.setMainTextSizeSp(MusicStore.getLyricFontSize());
            pageLyricView.setLyricAlign(MusicStore.getLyricAlign());
            pageLyricView.setLyricWeight(MusicStore.getLyricWeight());
            pageLyricView.setBlurLevel(1);
            pageLyricView.setAdvancedFx(MusicStore.isAdvancedFx());
        } catch (Throwable ignored) {
        }
    }

    /** 歌词内页音质胶囊（redo3 第七节口径修复）：与经典筹同口径——格子短码、无实测时整筹隐藏，所选档永不进显示。 */
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
        // 收起态守卫：内页音质胶囊同样不翻栈。
        if (bottomCollapsed) pageChipQuality.setVisibility(View.GONE);
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
        // 收起态守卫：逐字胶囊同样不翻栈。
        if (bottomCollapsed) pageChipWord.setVisibility(View.GONE);
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
     * 横向位移占优且过 72dp 才提交——2026-10-22 他纠正：瓜子左滑进歌词页、右滑回播放页
     * （10-21 右划进口径作废；滑入滑出动画同步改向，页自右侧来、向右侧去）。
     * 2026-10-22 微信补诉“不流畅”根修：此前 MOVE 只记阈值、松手才跳固定动画，手指拖动时页不动；
     * 现改跟手——轴锁后过 16dp 即拖动页跟手指走（开页起手建制只做一次，位移从拖动起点跟，不跳变），
     * 松手过 72dp 提交（收尾时长按剩余路程折算），不过则弹回原态；中途取消／动画中途被抓都归位，不留半屏。
     */
    private void attachPagerSwipe(View view, boolean embedded) {
        final float[] down = new float[2];
        final boolean[] crossed = {false};
        final boolean[] dragging = {false};
        final boolean[] dragSetup = {false};
        final float[] dragStartTx = {0f};
        view.setOnTouchListener((v, e) -> {
            float d = getResources().getDisplayMetrics().density;
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                down[0] = e.getX();
                down[1] = e.getY();
                crossed[0] = false;
                dragging[0] = false;
                dragSetup[0] = false;
                // 抓中动画不打架：停掉进行中的页动画，位移留在原地由拖动／归位接管，不跳变。
                if (lyricsPage != null) lyricsPage.animate().cancel();
            } else if (e.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                float dx = e.getX() - down[0];
                float dy = e.getY() - down[1];
                boolean axis = Math.abs(dx) > Math.abs(dy) * 1.5f;
                if (Math.abs(dx) > 72 * d && axis) crossed[0] = true;
                else if (Math.abs(dx) <= 72 * d) crossed[0] = false;
                if (axis && !dragging[0] && Math.abs(dx) > 16 * d && lyricsPage != null) {
                    dragging[0] = true;
                    if (embedded && !lyricsPageOpen) {
                        // 开拖：建制一次（可见＋头＋喂词等），页从右侧外起步跟手，不从残留位移跳变。
                        dragSetup[0] = openLyricsPageSetup();
                        if (dragSetup[0]) {
                            lyricsPage.setTranslationX(lyricsPageWidth());
                            dragStartTx[0] = lyricsPageWidth();
                        }
                    } else {
                        dragStartTx[0] = lyricsPage.getTranslationX();
                    }
                }
                if (dragging[0] && lyricsPage != null) {
                    float w = lyricsPageWidth();
                    if (embedded) {
                        if (dragSetup[0]) {
                            lyricsPage.setTranslationX(Math.max(0f, Math.min(w, dragStartTx[0] + dx)));
                        }
                    } else if (lyricsPageOpen) {
                        lyricsPage.setTranslationX(Math.max(0f, Math.min(w, dragStartTx[0] + dx)));
                    }
                }
            } else if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                float dx = e.getX() - down[0];
                float dy = e.getY() - down[1];
                boolean axis = Math.abs(dx) > Math.abs(dy) * 1.5f;
                boolean past = Math.abs(dx) > 72 * d && axis;
                if (dragging[0]) {
                    dragging[0] = false;
                    if (embedded) {
                        if (dragSetup[0]) {
                            if (dx < 0 && past && lyricsPage != null) {
                                float tx = lyricsPage.getTranslationX();
                                lyricsPage.animate().translationX(0f)
                                        .setDuration(settleDuration(tx, 0f, 260)).setInterpolator(INTERP_FOSI).start();
                            } else {
                                closeLyricsPage();
                            }
                        } else {
                            normalizeLyricsPagePosition();
                        }
                    } else if (lyricsPageOpen) {
                        if (dx > 0 && past) {
                            closeLyricsPage();
                        } else if (lyricsPage != null) {
                            float tx = lyricsPage.getTranslationX();
                            lyricsPage.animate().translationX(0f)
                                    .setDuration(settleDuration(tx, 0f, 200)).setInterpolator(INTERP_FOSI).start();
                        }
                    } else {
                        normalizeLyricsPagePosition();
                    }
                    dragSetup[0] = false;
                    crossed[0] = false;
                } else if (crossed[0]) {
                    crossed[0] = false;
                    if (past) {
                        if (embedded && dx < 0) openLyricsPage();
                        else if (!embedded && dx > 0) closeLyricsPage();
                    } else {
                        normalizeLyricsPagePosition();
                    }
                } else {
                    normalizeLyricsPagePosition();
                }
            } else if (e.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                if (dragging[0]) {
                    dragging[0] = false;
                    if (embedded && dragSetup[0]) closeLyricsPage();
                    else if (!embedded && lyricsPageOpen && lyricsPage != null) {
                        float tx = lyricsPage.getTranslationX();
                        lyricsPage.animate().translationX(0f)
                                .setDuration(settleDuration(tx, 0f, 200)).setInterpolator(INTERP_FOSI).start();
                    } else {
                        normalizeLyricsPagePosition();
                    }
                } else {
                    normalizeLyricsPagePosition();
                }
                dragSetup[0] = false;
                crossed[0] = false;
            }
            return false;
        });
    }

    /** 拖动结束无位移／手势取消时归位：中途取消残留的半屏页回到整态（开着回 0，关着滑出隐藏），
     * 已在位的页不触发动画，不干扰点按。 */
    private void normalizeLyricsPagePosition() {
        if (lyricsPage == null) return;
        float w = lyricsPageWidth();
        if (lyricsPageOpen) {
            if (lyricsPage.getTranslationX() != 0f) {
                lyricsPage.animate().translationX(0f).setDuration(200).setInterpolator(INTERP_FOSI).start();
            }
        } else if (lyricsPage.getVisibility() == View.VISIBLE && lyricsPage.getTranslationX() != w) {
            lyricsPage.animate().translationX(w).setDuration(200).setInterpolator(INTERP_LOSI).withEndAction(() -> {
                if (!lyricsPageOpen) {
                    lyricsPage.setVisibility(View.GONE);
                    refreshPlayerBackground();
                }
            }).start();
        }
    }

    /**
     * L6／A1：根／主题容器层 pager 横滑观察（只观察不消费，返回 false，点按／竖滑切歌不受影响）。
     * 门与歌词区一致：横向位移＞72dp 且 |dx|＞|dy|×1.5，松手按最终位移判定（中途拖回可反悔）。
     * 容器监听先于子控件见事件（分发顺序），进度条拖动起点须显式守门——起点落在进度条内不跟手不触发，
     * 否则拖进度条 72dp 会误开歌词页；封面区起点同样放行，不抢切歌手势。
     * 非 Apple：左划进通用歌词页；Apple：左划进 Apple 呈现的歌词页（复用载体，见 A1 注释），
     * 右划进歌曲信息页——直达工具面板歌曲信息子面板（堵-2 主面板歌曲信息行同一入口）；
     * 全屏右划回页不动。
     * 2026-10-22 微信补诉“不流畅”同口径跟手：轴锁后过 16dp 即拖动页跟手指走，松手按 72dp 提交／弹回。
     */
    private void attachRootPagerSwipe(View container) {
        if (container == null) return;
        final float[] down = new float[2];
        final boolean[] crossed = {false};
        final boolean[] dragging = {false};
        final boolean[] dragSetup = {false};
        final float[] dragStartTx = {0f};
        // 堵-3：根层同样只判起点——ACTION_DOWN 记起点是否在封面区，ACTION_UP 不再用松手点重判。
        final boolean[] downInCover = {false};
        final boolean[] downInSeek = {false};
        container.setOnTouchListener((v, e) -> {
            float d = getResources().getDisplayMetrics().density;
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                down[0] = e.getX();
                down[1] = e.getY();
                crossed[0] = false;
                dragging[0] = false;
                dragSetup[0] = false;
                downInCover[0] = inCoverWrap(e);
                downInSeek[0] = false;
                if (seekView != null) {
                    int[] loc = new int[2];
                    seekView.getLocationOnScreen(loc);
                    float rx = e.getRawX(), ry = e.getRawY();
                    downInSeek[0] = rx >= loc[0] && rx <= loc[0] + seekView.getWidth()
                            && ry >= loc[1] && ry <= loc[1] + seekView.getHeight();
                }
                if (lyricsPage != null) lyricsPage.animate().cancel();
            } else if (e.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                float dx = e.getX() - down[0];
                float dy = e.getY() - down[1];
                boolean axis = Math.abs(dx) > Math.abs(dy) * 1.5f;
                if (Math.abs(dx) > 72 * d && axis) crossed[0] = true;
                else if (Math.abs(dx) <= 72 * d) crossed[0] = false;
                boolean canDrag = !lyricsPageOpen && !downInCover[0] && !downInSeek[0]
                        && MusicController.get().current() != null;
                if (axis && !dragging[0] && Math.abs(dx) > 16 * d && lyricsPage != null && canDrag) {
                    dragging[0] = true;
                    dragSetup[0] = openLyricsPageSetup();
                    if (dragSetup[0]) {
                        lyricsPage.setTranslationX(lyricsPageWidth());
                        dragStartTx[0] = lyricsPageWidth();
                    }
                }
                if (dragging[0] && dragSetup[0] && lyricsPage != null) {
                    float w = lyricsPageWidth();
                    lyricsPage.setTranslationX(Math.max(0f, Math.min(w, dragStartTx[0] + dx)));
                }
            } else if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                float dx = e.getX() - down[0];
                float dy = e.getY() - down[1];
                boolean axis = Math.abs(dx) > Math.abs(dy) * 1.5f;
                boolean past = Math.abs(dx) > 72 * d && axis;
                if (dragging[0]) {
                    dragging[0] = false;
                    if (dragSetup[0]) {
                        if (dx < 0 && past && lyricsPage != null) {
                            float tx = lyricsPage.getTranslationX();
                            lyricsPage.animate().translationX(0f)
                                    .setDuration(settleDuration(tx, 0f, 260)).setInterpolator(INTERP_FOSI).start();
                        } else {
                            closeLyricsPage();
                        }
                    } else {
                        normalizeLyricsPagePosition();
                    }
                    dragSetup[0] = false;
                    crossed[0] = false;
                } else if (crossed[0]) {
                    crossed[0] = false;
                    if (past && !lyricsPageOpen && !downInCover[0] && !downInSeek[0]
                            && MusicController.get().current() != null) {
                        if (dx < 0) {
                            // 左划进歌词页（瓜子口径）；Apple 主题记 Apple 呈现旗标
                            appleLyricsPage = THEME_APPLE.equals(themeId);
                            openLyricsPage();
                        } else if (THEME_APPLE.equals(themeId)) {
                            // A1 右＝歌曲信息页：直达工具面板歌曲信息子面板（堵-2 主面板歌曲信息行同一入口）。
                            showToolsSheetSongInfo();
                        }
                    } else {
                        normalizeLyricsPagePosition();
                    }
                } else {
                    normalizeLyricsPagePosition();
                }
            } else if (e.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                if (dragging[0]) {
                    dragging[0] = false;
                    if (dragSetup[0]) closeLyricsPage();
                    else normalizeLyricsPagePosition();
                } else {
                    normalizeLyricsPagePosition();
                }
                dragSetup[0] = false;
                crossed[0] = false;
            }
            return false;
        });
    }

    /** 起点是否落在封面切歌区（tpCoverWrap）内：是则根层 pager 不抢手势。 */
    private boolean inCoverWrap(android.view.MotionEvent e) {
        View wrap = find(R.id.tpCoverWrap);
        if (wrap == null) return false;
        int[] loc = new int[2];
        wrap.getLocationInWindow(loc);
        float x = e.getRawX();
        float y = e.getRawY();
        return x >= loc[0] && x <= loc[0] + wrap.getWidth()
                && y >= loc[1] && y <= loc[1] + wrap.getHeight();
    }

    // ================= 进度/主控 =================

    /**
     * D5：拖动浮动时间 label——浮层形态未抠实（悬浮泡还是行内变值），先用最简单的一种：
     * 根布局上悬浮的圆角深色小 label，横向跟手（拇指 x，贴边钳制），纵向贴在进度条上方；
     * 显示当前拖动时间（mm:ss），松手隐藏。CD 与唱片架共用主进度条监听，即共用本套。
     */
    private void ensureScrubTimeLabel() {
        if (scrubTimeLabel != null || binding == null || binding.getRoot() == null) return;
        TextView tv = new TextView(this);
        tv.setTextSize(12);
        tv.setTextColor(0xFFFFFFFF);
        tv.setGravity(Gravity.CENTER);
        float d = getResources().getDisplayMetrics().density;
        tv.setPadding(Math.round(10 * d), Math.round(4 * d), Math.round(10 * d), Math.round(4 * d));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(14 * d);
        bg.setColor(0xCC000000);
        tv.setBackground(bg);
        tv.setVisibility(View.GONE);
        ((ViewGroup) binding.getRoot()).addView(tv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrubTimeLabel = tv;
    }

    private void showScrubTimeLabel(SeekBar bar, int progress) {
        ensureScrubTimeLabel();
        if (scrubTimeLabel == null) return;
        scrubTimeLabel.setVisibility(View.VISIBLE);
        updateScrubTimeLabel(bar, progress);
    }

    private void updateScrubTimeLabel(SeekBar bar, int progress) {
        if (scrubTimeLabel == null || scrubTimeLabel.getVisibility() != View.VISIBLE || bar == null) return;
        long dur = MusicController.get().duration();
        scrubTimeLabel.setText(fmt(dur * progress / 1000));
        int[] barLoc = new int[2];
        int[] rootLoc = new int[2];
        bar.getLocationInWindow(barLoc);
        binding.getRoot().getLocationInWindow(rootLoc);
        float frac = bar.getMax() > 0 ? (float) progress / bar.getMax() : 0f;
        float thumbX = barLoc[0] - rootLoc[0] + bar.getPaddingLeft()
                + frac * Math.max(0, bar.getWidth() - bar.getPaddingLeft() - bar.getPaddingRight());
        scrubTimeLabel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float x = Math.max(0f, Math.min(thumbX - scrubTimeLabel.getMeasuredWidth() / 2f,
                Math.max(0f, binding.getRoot().getWidth() - scrubTimeLabel.getMeasuredWidth())));
        scrubTimeLabel.setX(x);
        scrubTimeLabel.setY(Math.max(0f, barLoc[1] - rootLoc[1] - scrubTimeLabel.getMeasuredHeight()
                - 8 * getResources().getDisplayMetrics().density));
    }

    private void hideScrubTimeLabel() {
        if (scrubTimeLabel != null) scrubTimeLabel.setVisibility(View.GONE);
    }

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
        if (THEME_CD.equals(themeId) && cdDetailView != null && duration > 0) {
            applyCdDetail((float) position / duration, cdDetailContent == CdDetailContent.SONG_INFO);
        }
        if (positionView != null) positionView.setText(fmt(position));
        // GZ CD 时间行右端＝总时长（LuminousCdPlayerKt:1664 cdTime(duration)），不是负剩余；此前星辰负剩余是错值。
        // 注意：tpDuration 为 CD／唱片架／封面卡片展开态共用视图，本批只改 CD 分支，其余主题保持原负剩余不动。
        if (durationView != null) {
            if (THEME_CD.equals(themeId)) durationView.setText(fmt(duration));
            else durationView.setText("-" + fmt(duration - position));
        }
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
        // 迷你条进度：收起态也照常走（它是收起态唯一的进度显示），展开态藏起但进度照算。
        if (miniProgressView != null && duration > 0) {
            miniProgressView.setProgress((float) position / duration);
        }
        updateCurrentLyricTexts(position);
    }

    private String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    /**
     * 播放键盒与图标尺寸随态改（redo3 第三轮 D12／S9）：流光 CD 盒 68dp、播放态图标 54dp／暂停态 58dp
     *（LuminousCdPlayerKt:3110-3145）；唱片架盒 68dp、图标两态均为 52dp（RecordShelfPlayerKt:2303-2332）。
     * 图标尺寸以盒内 padding 实现（盒−图标）／2；其余主题不动。加载圆环（两主题均为 30dp／线宽 2.5）
     * 因星辰音乐链无 loading 信号源、且架分支方向未复核清，本轮不补视图，见分表缺失节。
     */
    private void applyPlayButtonSize(boolean isPlaying) {
        if (playView == null) return;
        float d = getResources().getDisplayMetrics().density;
        int boxDp;
        int iconDp;
        if (THEME_CD.equals(themeId)) {
            boxDp = 68;
            iconDp = isPlaying ? 54 : 58;
        } else if (THEME_SHELF.equals(themeId)) {
            boxDp = 68;
            iconDp = 52;
        } else {
            return;
        }
        int boxPx = Math.round(boxDp * d);
        int padPx = Math.round((boxDp - iconDp) / 2f * d);
        android.view.ViewGroup.LayoutParams lp = playView.getLayoutParams();
        if (lp != null && (lp.width != boxPx || lp.height != boxPx)) {
            lp.width = boxPx;
            lp.height = boxPx;
            playView.setLayoutParams(lp);
        }
        if (playView.getPaddingLeft() != padPx || playView.getPaddingTop() != padPx) {
            playView.setPadding(padPx, padPx, padPx, padPx);
        }
    }

    private void setPlaying(boolean isPlaying) {
        playing = isPlaying;
        if (playView != null) {
            playView.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
            applyPlayButtonSize(isPlaying);
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
        // 迷你条口径（GZ：暂停进度条自己出来，播放几秒后隐藏变迷你）：
        // 暂停＝展开进度栈并停掉闲置计时（暂停期间一直展开）；播放＝重起 3 秒计时，到时缩回迷你。
        // 此前 setPlaying 完全不碰 bottomCollapsed，暂停也一直是迷你条，是这条的根因。
        if (playing) {
            restartMiniHideTimer();
        } else {
            cancelMiniHideTimer();
            if (bottomCollapsed) {
                bottomCollapsed = false;
                syncBottomStack();
            }
        }
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
    /** 播放／暂停切换只启停圆盘自旋，不重绑：3 槽模型下按 holder 实际绑定的队列号找当前槽，
     * 弹簧进行中重绑会把旧内容槽换成新内容、打断连续插值，故一律不 notify，只动自旋态。 */
    private void refreshShelfSpins() {
        if (shelfAdapter == null || shelfList == null) return;
        int cur = MusicController.get().index();
        for (int slotPos = 0; slotPos < 3; slotPos++) {
            RecyclerView.ViewHolder vh = shelfList.findViewHolderForAdapterPosition(slotPos);
            if (!(vh instanceof ShelfAdapter.Holder)) continue;
            ShelfAdapter.Holder h = (ShelfAdapter.Holder) vh;
            h.stopSpin();
            // 不按槽位号认当前槽：弹簧进行中当前曲在滑入的邻槽视图上（boundQueueIndex 即当前曲号），
            // 静态时当前曲恒为槽位 1，两态同一条件。
            if (cur >= 0 && h.boundQueueIndex == cur && playing && animationsEnabled()) {
                h.startSpin();
            }
        }
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

    /** Apple 运输键禁用态 alpha（GZ Apple：启用 0.9 上/下一首／0.95 播放、禁用 0.35；仅 Apple 分支）。
     * 启用门用树内现成口径：播放＝有当前曲；下一首＝MusicController.canNext()（顺序单首队重头播已含）；
     * 上一首＝服务侧 prevIndex() >= 0（顺序首首／单曲循环／洗牌首位时服务侧返回本首序号，即“重播本首”而非无上一首，
     * 故上一首仅在无队／无序号时禁用）。其它主题不走本方法，alpha 与启用态原样不动。 */
    private void refreshAppleTransportAlpha() {
        if (!THEME_APPLE.equals(themeId)) return;
        boolean hasTrack = MusicController.get().current() != null;
        boolean prevEnabled = hasTrack && MusicController.get().queuePrevIndex() >= 0;
        boolean nextEnabled = hasTrack && MusicController.get().canNext();
        if (prevView != null) {
            prevView.setEnabled(prevEnabled);
            prevView.setAlpha(prevEnabled ? 0.9f : 0.35f);
        }
        if (nextView != null) {
            nextView.setEnabled(nextEnabled);
            nextView.setAlpha(nextEnabled ? 0.9f : 0.35f);
        }
        if (playView != null) {
            playView.setEnabled(hasTrack);
            playView.setAlpha(hasTrack ? 0.95f : 0.35f);
        }
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

    /** GZ CD 五图标行第 5 格：表面循环 LIGHT→DARK→AUTO→LIGHT＋Toast（GZ T24 CdSurfaceMode 循环口径）；
         只重刷色板（applyCdPalette 就地换色，500ms 根背景渐变），不整页重 inflate，避免盘面重建闪一下。 */
    private void cycleCdSurface() {
        if (!THEME_CD.equals(themeId)) return;
        int next;
        try {
            next = (MusicStore.getCdSurface() + 1) % 3;
            MusicStore.setCdSurface(next);
        } catch (Throwable ignored) {
            return;
        }
        applyCdPalette();
        Notify.show(next == MusicStore.CD_SURFACE_LIGHT ? R.string.music_cd_surface_to_light
                : next == MusicStore.CD_SURFACE_DARK ? R.string.music_cd_surface_to_dark
                : R.string.music_cd_surface_to_auto);
    }

    /** CdPalette：浅 Pearl(base #F4F2EE/mist #E9EFF5/ink #282D36/muted #747D89)，深 Space(base #14161C/ink #F2F2F7/muted #8E96A3) */
    /** 流光 CD 盘／封面响应式尺寸（wave2 修正，GZ LuminousCdPlayerKt:3243）：
     * 盘外框 f＝min(区宽, 区高, 400dp)，封面＝f×0.89285713；旧式“盘＝基径×0.892857、封面＝盘×0.68”错一级
     * （0.68 只是中心封面加载像素换算系数，不是显示占比）；中心点 14dp 无瓜子对应值，留着不动。
     * 核对：瓜子纵向盘区无 weight（dig §2），星辰侧盘区 weight 5 已删；区高不再读盘区自身高（盘区现为
     * wrap_content，读它会自指），而按 Compose 度量口径取父列可用高＝父高－父上下垫－固定兄弟高（顶行／
     * 摘要行／歌词窗／五图标行／进度／时间行／主控行，GONE 计 0；盘区、歌词窗显式高动画中的歌词窗、
     * 弹性空隙三者不计：盘区是被求量，歌词窗在动画帧里以布局参数高为准、post 到布局后与 getHeight 一致，
     * 空隙是收起态填充件）；父高未量出时回落盘区现有高，再无则返回等下一帧。 */
    private void applyCdDiscSize() {
        View wrap = find(R.id.tpCoverWrap);
        if (wrap == null || cdDisc == null) return;
        wrap.post(() -> {
            if (cdDisc == null) return;
            float d = getResources().getDisplayMetrics().density;
            float availW = wrap.getWidth() / d;
            float availH = -1f;
            android.view.ViewParent p = wrap.getParent();
            if (p instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) p;
                if (parent.getHeight() > 0) {
                    float usedPx = parent.getPaddingTop() + parent.getPaddingBottom();
                    for (int i = 0; i < parent.getChildCount(); i++) {
                        View child = parent.getChildAt(i);
                        if (child == wrap || child == lyricView) continue;
                        if (child.getId() == R.id.tpCdGap) continue;
                        if (child.getVisibility() == View.GONE) continue;
                        usedPx += child.getHeight();
                        if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                            ViewGroup.MarginLayoutParams m =
                                    (ViewGroup.MarginLayoutParams) child.getLayoutParams();
                            usedPx += m.topMargin + m.bottomMargin;
                        }
                    }
                    // 歌词窗：动画帧里布局参数高即当前目标高（post 时布局已过，getHeight 与之相同，取其一即可）。
                    if (lyricView != null && lyricView.getVisibility() != View.GONE) {
                        usedPx += lyricView.getHeight();
                        if (lyricView.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                            ViewGroup.MarginLayoutParams m =
                                    (ViewGroup.MarginLayoutParams) lyricView.getLayoutParams();
                            usedPx += m.topMargin + m.bottomMargin;
                        }
                    }
                    availH = (parent.getHeight() - usedPx) / d;
                }
            }
            if (availH <= 0) availH = wrap.getHeight() / d;
            if (availW <= 0 || availH <= 0) return;
            float base = Math.min(Math.min(availW, availH), 400f);
            float disc = base;
            float cover = base * 0.89285713f;
            int discPx = Math.round(disc * d);
            int coverPx = Math.round(cover * d);
            ViewGroup.LayoutParams dlp = cdDisc.getLayoutParams();
            if (dlp != null && (dlp.width != discPx || dlp.height != discPx)) {
                dlp.width = discPx;
                dlp.height = discPx;
                cdDisc.setLayoutParams(dlp);
            }
            for (View v : new View[]{coverView, coverExitView}) {
                if (v == null) continue;
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp != null && (lp.width != coverPx || lp.height != coverPx)) {
                    lp.width = coverPx;
                    lp.height = coverPx;
                    v.setLayoutParams(lp);
                }
            }
            // D3：扫光层改盖满整盘（discPx），不再只盖封面圆；CdSweepView 扫程／光强公式不动。
            if (cdSweepView != null) {
                ViewGroup.LayoutParams lp = cdSweepView.getLayoutParams();
                if (lp != null && (lp.width != discPx || lp.height != discPx)) {
                    lp.width = discPx;
                    lp.height = discPx;
                    cdSweepView.setLayoutParams(lp);
                }
            }
        });
    }

    /** 流光 CD 光泽（redo3 第四轮 D11）：切歌／表面切换时淡入 700ms（INTERP_CD_EXPAND，bs4.d）后淡出 450ms（INTERP_FOSI）；
     * 与 D10 表面切换的 500ms 根背景动画分属两个动画器、分开跑。 */
    private void triggerCdSheen() {
        if (cdSheenView == null) return;
        if (cdSheenAnim != null) cdSheenAnim.cancel();
        cdSheenView.setAlpha(0f);
        android.animation.ObjectAnimator in = android.animation.ObjectAnimator.ofFloat(cdSheenView, View.ALPHA, 0f, 1f);
        in.setDuration(700);
        in.setInterpolator(INTERP_CD_EXPAND);
        android.animation.ObjectAnimator out = android.animation.ObjectAnimator.ofFloat(cdSheenView, View.ALPHA, 1f, 0f);
        out.setDuration(450);
        out.setInterpolator(INTERP_FOSI);
        cdSheenAnim = new android.animation.AnimatorSet();
        cdSheenAnim.playSequentially(in, out);
        cdSheenAnim.start();
    }

    /** CD 细节内容切换（redo3 第四轮 D11）：只切 CD 布局自己的细节层，不复用封面卡片的 toggleCoverInfo；
     * SONG_INFO 显示细节行（拇指＋歌名／歌手），LYRICS 藏细节行（歌词走下方 tpLyricView）。 */
    private void toggleCdDetailContent() {
        if (cdDetailView == null) return;
        cdDetailContent = cdDetailContent == CdDetailContent.SONG_INFO ? CdDetailContent.LYRICS : CdDetailContent.SONG_INFO;
        refreshCdUtilGlyphs();
        if (cdDetailContent == CdDetailContent.SONG_INFO) refreshCdDetailTexts();
        long dur = MusicController.get().duration();
        float progress = dur > 0 ? (float) MusicController.get().position() / dur : 0f;
        applyCdDetail(progress, cdDetailContent == CdDetailContent.SONG_INFO);
    }

    /** CD 五图标行字形随态换（wave2，GZ LuminousCdPlayerKt:2975-2977／3032-3040）：
     * 第 1 格：展开态且盘心详情＝歌曲信息时「显示唱片」（唱片字形），否则「查看歌曲信息」（信息字形）；
     * 第 5 格：按当前表面换字形（浅色→太阳，深色→月牙，自动→调色板通用形）。
     * 瓜子字形矢量未从反编译解码到底：唱片形用现成 ic_setting_disc（圆盘＋中心点），月牙为本批自绘，
     * 自动档暂留调色板通用形，抠到瓜子矢量再换，见报告。 */
    private void refreshCdUtilGlyphs() {
        if (!THEME_CD.equals(themeId)) return;
        View info = find(R.id.tpCdUtilInfo);
        if (info instanceof ImageView) {
            boolean showDisc = cdLyricsExpanded && cdDetailContent == CdDetailContent.SONG_INFO;
            ((ImageView) info).setImageResource(showDisc
                    ? R.drawable.ic_setting_disc : R.drawable.ic_player_info);
            info.setContentDescription(getString(showDisc
                    ? R.string.music_wave2_cd_show_disc : R.string.music_song_info));
        }
        View theme = find(R.id.tpCdUtilTheme);
        if (theme instanceof ImageView) {
            int surface = 2;
            try {
                surface = MusicStore.getCdSurface();
            } catch (Throwable ignored) {
            }
            if (surface == MusicStore.CD_SURFACE_LIGHT) {
                ((ImageView) theme).setImageResource(R.drawable.ic_setting_sun);
                theme.setContentDescription(getString(R.string.music_wave2_cd_surface_light));
            } else if (surface == MusicStore.CD_SURFACE_DARK) {
                ((ImageView) theme).setImageResource(R.drawable.ic_wave2_moon);
                theme.setContentDescription(getString(R.string.music_wave2_cd_surface_dark));
            } else {
                ((ImageView) theme).setImageResource(R.drawable.ic_music_palette);
                theme.setContentDescription(getString(R.string.music_wave2_cd_surface_auto));
            }
        }
    }

    /** CD 五图标行禁用门控（瓜子 LuminousCdPlayerKt:3000 前后五次 m122 调用逐格解码）：
     * ①歌曲信息／③歌词版本／④收藏／⑤表面：enabled＝getEnabled()（①⑤在调用掩码里走默认 enabled，
     * 与 getEnabled() 同值）；②音质：enabled＝getEnabled() && qualitySelectable；
     * 不可用时 alpha ×0.35＝CD_DETAIL_FADE_END（LuminousCdPlayerKt:213）。
     * 星辰侧 getEnabled() 对应＝有当前曲；qualitySelectable 在星辰模型里无独立信号（音质面板对有当前曲
     * 一律出档位、无“不可选”态），故 isQualitySelectable() 目前即有当前曲（钩子已按格拆开，待瓜子
     * qualitySelectable 的产生条件抠到后再接真信号，不编本地／无档位之类的猜测条件）。 */
    private void refreshCdUtilRowEnabled() {
        if (!THEME_CD.equals(themeId)) return;
        boolean hasTrack = MusicController.get().current() != null;
        setCdUtilCellEnabled(R.id.tpCdUtilInfo, hasTrack);
        setCdUtilCellEnabled(R.id.tpCdUtilQuality, hasTrack && isQualitySelectable());
        setCdUtilCellEnabled(R.id.tpCdUtilLyric, hasTrack);
        setCdUtilCellEnabled(R.id.tpFavHeart, hasTrack);
        setCdUtilCellEnabled(R.id.tpCdUtilTheme, hasTrack);
    }

    private void setCdUtilCellEnabled(int id, boolean enabled) {
        View v = find(id);
        if (v == null) return;
        v.setEnabled(enabled);
        // 收藏心 tint 由 paintFavoriteIcons 管，alpha 照门控走（启用 1／禁用 0.35）。
        v.setAlpha(enabled ? 1f : 0.35f);
    }

    /** 瓜子 qualitySelectable 的星辰侧钩子：星辰音质面板无“不可选”态，故目前＝有当前曲；见上。 */
    private boolean isQualitySelectable() {
        return MusicController.get().current() != null;
    }

    private void refreshCdDetailTexts() {
        MusicTrack t = MusicController.get().current();
        if (t == null) return;
        if (cdDetailTitle != null) cdDetailTitle.setText(t.displayTitle());
        if (cdDetailArtist != null) cdDetailArtist.setText(com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(t.displayArtist()) ? getString(R.string.music_unknown_artist) : t.displayArtist());
        // 第三波 P-7b（口径定为全应用同分支）：详情缩图空地址也走素图，不留旧图残影；
        // 有地址走加载、失败透明（ImgUtil 失败回调置透明，与主封面 loadIntoCover 同款）。
        if (cdDetailThumb != null) {
            if (t.cover != null && !t.cover.isEmpty()) {
                ImgUtil.load(t.displayTitle(), t.cover, cdDetailThumb);
            } else {
                cdDetailThumb.setImageDrawable(new CoverTextDrawable(t.displayTitle(), false,
                        28 * getResources().getDisplayMetrics().density));
            }
        }
    }

    /** CD 表面细节透明度（redo3 第四轮 D11）：细节动画值 tween(300, INTERP_FOSI)；进度门 0.02、区间 0.33，
     * smoothstep (3-2v)v²；细节透明度 = min(动画值, 1 - smoothstep((clamp(进度,0,1) - 0.02) / 0.33))；
     * 进度未过门时细节层透明且细节键图标 alpha 乘 0.35；文字滑移 20dp（入／出两向取反）。 */
    private void applyCdDetail(float progress, boolean entered) {
        if (cdDetailView == null) return;
        float target = entered ? 1f : 0f;
        if (cdDetailAnimTarget != target) {
            cdDetailAnimTarget = target;
            if (cdDetailAnim != null) cdDetailAnim.cancel();
            cdDetailAnim = ValueAnimator.ofFloat(cdDetailAnimValue, target);
            cdDetailAnim.setDuration(300);
            cdDetailAnim.setInterpolator(INTERP_FOSI);
            cdDetailAnim.addUpdateListener(a -> {
                cdDetailAnimValue = (float) a.getAnimatedValue();
                applyCdDetailAlpha(progress, entered);
            });
            cdDetailAnim.start();
        }
        applyCdDetailAlpha(progress, entered);
    }

    private void applyCdDetailAlpha(float progress, boolean entered) {
        if (cdDetailView == null) return;
        float p = Math.max(0f, Math.min(1f, progress));
        float v = Math.max(0f, Math.min(1f, (p - 0.02f) / 0.33f));
        float smooth = (3f - 2f * v) * v * v;
        float alpha = Math.min(cdDetailAnimValue, 1f - smooth);
        if (p <= 0.02f) alpha = 0f;
        cdDetailView.setAlpha(alpha);
        float d = getResources().getDisplayMetrics().density;
        cdDetailView.setTranslationX((1f - cdDetailAnimValue) * 20f * d * (entered ? 1f : -1f));
        if (cdDetailBtn != null) cdDetailBtn.setAlpha(p <= 0.02f ? 0.35f : 1f);
    }

    /** 带显式停点的纵向线性渐变（GradientDrawable 只能均分停点，瓜子背景停点 0／0.34／0.68／1 需此类）。 */
    private static final class StopLinearDrawable extends android.graphics.drawable.Drawable {
        private final int[] colors;
        private final float[] positions;
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);

        StopLinearDrawable(int[] colors, float[] positions) {
            this.colors = colors;
            this.positions = positions;
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect bounds) {
            super.onBoundsChange(bounds);
            paint.setShader(bounds.height() > 0 ? new android.graphics.LinearGradient(
                    0, 0, 0, bounds.height(), colors, positions,
                    android.graphics.Shader.TileMode.CLAMP) : null);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            canvas.drawRect(getBounds(), paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** 带显式停点的扫掠渐变（瓜子 hubSweep 停点用；起始角可指定，星辰光泽层用 caustic 初值 42°）。 */
    private static final class SweepStopsDrawable extends android.graphics.drawable.Drawable {
        private final int[] colors;
        private final float[] positions;
        private final float startAngleDeg;
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);

        SweepStopsDrawable(int[] colors, float[] positions, float startAngleDeg) {
            this.colors = colors;
            this.positions = positions;
            this.startAngleDeg = startAngleDeg;
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect bounds) {
            super.onBoundsChange(bounds);
            android.graphics.Rect b = getBounds();
            paint.setShader(b.width() > 0 && b.height() > 0 ? new android.graphics.SweepGradient(
                    b.exactCenterX(), b.exactCenterY(), colors, positions) : null);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            canvas.save();
            canvas.rotate(startAngleDeg, b.exactCenterX(), b.exactCenterY());
            canvas.drawOval(new android.graphics.RectF(b), paint);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** 线性 sRGB 相对亮度（Compose Color.luminance 口径，a21.a()/b() 变换用；minSdk 下不用 Color.luminance）。 */
    private static float cdLuminance(int color) {
        float r = linearize(android.graphics.Color.red(color) / 255f);
        float g = linearize(android.graphics.Color.green(color) / 255f);
        float b = linearize(android.graphics.Color.blue(color) / 255f);
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static float linearize(float c) {
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
    }

    private void applyCdPalette() {
        if (cdRoot == null) return;
        boolean dark = cdIsDark();
        int base = dark ? 0xFF14161C : 0xFFF4F2EE;
        // D1：深色 mist 改瓜子值 #14161C（旧自定 #20232C 作废）；浅色 mist 仍 #E9EFF5。
        int mist = dark ? 0xFF14161C : 0xFFE9EFF5;
        int ink = dark ? 0xFFF2F2F7 : 0xFF282D36;
        int muted = dark ? 0xFF8E96A3 : 0xFF747D89;
        // CD 页背景照瓜子 CdPalette 四段纵向渐变（旧平板 base 色作废）：
        // 停点 0→base 与 fresh 混 0.92、0.34→upper、0.68→middle、1→bottom；
        // fresh＝base 向封面色混 0.22；middle＝base 向（base 向封面色混 0.12）按 bgMix 再混；
        // bottom＝mist 向（base 向封面色混 0.08）按 bgMix 再混；upper＝base 向 fresh 按 lerp(0.48,0.73,bgMix) 混。
        // Pearl bgMix 0、Space bgMix 1；封面色取当前氛围色 ambientColor。
        // 停点照瓜子 LuminousCdPlayerKt:2478 的 0／0.34／0.68／1（GradientDrawable 只能均分 0／0.33／0.67／1，
        // 故用 StopLinearDrawable 带停点画，见本文件底部小类）。
        float bgMix = dark ? 1f : 0f;
        int cover = ambientColor;
        int fresh = blend(base, cover, 0.22f);
        int middle = blend(base, blend(base, cover, 0.12f), bgMix);
        int bottom = blend(mist, blend(base, cover, 0.08f), bgMix);
        float upperF = 0.48f + (0.73f - 0.48f) * bgMix;
        int upper = blend(base, fresh, upperF);
        int top = blend(base, fresh, 0.92f);
        android.graphics.drawable.Drawable grad = new StopLinearDrawable(
                new int[]{top, upper, middle, bottom}, new float[]{0f, 0.34f, 0.68f, 1f});
        // 首帧直接定渐变（新 inflate 的根还是布局写死的 #F4F2EE，深色表面若先闪一帧浅色即错）；
        // 之后表面切换 500ms 交叉淡入新渐变（对应瓜子 500ms 全板混色；文字色即时跟随不变）。
        try {
            if (cdPaletteFirstApply) {
                cdRoot.setBackground(grad);
                cdPaletteFirstApply = false;
            } else {
                android.graphics.drawable.Drawable old = cdRoot.getBackground();
                if (old == null) {
                    cdRoot.setBackground(grad);
                } else {
                    android.graphics.drawable.TransitionDrawable td = new android.graphics.drawable.TransitionDrawable(
                            new android.graphics.drawable.Drawable[]{old, grad});
                    cdRoot.setBackground(td);
                    td.startTransition(500);
                }
            }
        } catch (Throwable ignored) {
            cdRoot.setBackground(grad);
        }
        // 歌名色＝solidInk＝ink×solidAlpha（CdPalette:39,44）：浅 solidAlpha 1.0、深 0.72（0.72×255＝184＝0xB8）。
        int solidInk = dark ? (ink & 0x00FFFFFF) | 0xB8000000 : ink;
        if (titleView != null) titleView.setTextColor(solidInk);
        if (artistView != null) artistView.setTextColor(muted);
        // CD 细节行与主标题同口径按表面重刷：布局写死的深墨标题／muted 歌手在深空底上隐形
        if (cdDetailTitle != null) cdDetailTitle.setTextColor(ink);
        if (cdDetailArtist != null) cdDetailArtist.setTextColor(muted);
        // CD 歌词组件按表面明暗切方案：浅表面用浅色方案（深字）、深表面用默认（浅字）
        if (lyricView != null) lyricView.setLightScheme(!dark);
        // CD 时间行（redo3 D13）：9sp（布局侧）＋ muted×0.74
        int timeColor = (muted & 0x00FFFFFF) | 0xBD000000;
        if (positionView != null) positionView.setTextColor(timeColor);
        if (durationView != null) durationView.setTextColor(timeColor);
        if (seekView != null) {
            seekView.setProgressDrawable(getDrawable(dark
                    ? R.drawable.bg_player_seek_progress : R.drawable.bg_player_seek_progress_dark));
            // redo3 第四轮 D14：瓜子 CD 轨无拇指——去掉透明 thumb 盒（布局侧 thumb="@null" 同步），不再设 thumb／不再 tint；
            // 底轨改 ink×0.10：对 progressDrawable 的 background 层做 ink 0.10 着色（共享 drawable 先 mutate，不影响他处）；
            // 填充色瓜子传入色 j 未追到底，不写成黑 0.42，填充层原样保留、记表为残留。
            seekView.setThumb(null);
            try {
                android.graphics.drawable.Drawable pd = seekView.getProgressDrawable();
                if (pd instanceof android.graphics.drawable.LayerDrawable) {
                    android.graphics.drawable.Drawable bg = ((android.graphics.drawable.LayerDrawable) pd)
                            .findDrawableByLayerId(android.R.id.background);
                    if (bg != null) {
                        bg.mutate();
                        bg.setColorFilter((ink & 0x00FFFFFF) | 0x1A000000,
                                android.graphics.PorterDuff.Mode.SRC_IN);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        ColorStateList inkList = ColorStateList.valueOf(ink);
        if (prevView != null) prevView.setImageTintList(inkList);
        if (nextView != null) nextView.setImageTintList(inkList);
        if (playView != null) playView.setImageTintList(inkList);
        if (queueView != null) queueView.setImageTintList(inkList);
        triggerCdSheen(); // D11：光泽与 D10 表面切换分开跑（独立动画器）
        // 盘面材质（瓜子 CdDiscMaterial，defpackage/a21.java:19,23）：盘面扫掠 sweep 组浅 8 档／深 8 档，
        // 每档先经 a21.b()（向基色 c 混：浅 c＝白、深 c＝#FF2A3038，系数 clamp((1−亮度)×e＋d, ≤0.92)，
        // 浅 d＝0.55／e＝0.25、深 d＝0.50／e＝0）得基调色，再经 a21.a()（向基调色混 (1−亮度×0.65)×f，
        // 浅 f＝0.55、深 f＝0.34）得最终档色；档色按扫掠渐变铺盘面（Compose sweep 颜色表均分，无显式停点，
        // 故用 GradientDrawable SWEEP_GRADIENT）。此前星辰三段 TL_BR 渐变＋2dp 描边作废：描边值瓜子侧
        // 未抠到（对照表“描边值未抠到”），不留星辰自定的 2dp 描边。亮度取线性 sRGB 相对亮度（Compose luminance 口径）。
        if (cdDisc != null) {
            int[] sweepRaw = dark ? new int[]{0xFF2E3742, 0xFF8792A0, 0xFF3B4653, 0xFF4E493F,
                    0xFF262D37, 0xFF8C97A5, 0xFF46405A, 0xFF2E3742}
                    : new int[]{0xFF9FAAB7, 0xFFF0F3F6, 0xFFB6C7D0, 0xFFE9E3D4,
                    0xFF929FAC, 0xFFF7F8FA, 0xFFD4CADB, 0xFF9FAAB7};
            float toneLum = cdLuminance(dominantColor);
            float i0 = Math.min((1f - toneLum) * (dark ? 0f : 0.25f) + (dark ? 0.50f : 0.55f), 0.92f);
            int toneBase = blend(dark ? 0xFF2A3038 : 0xFFFFFFFF, dominantColor, i0);
            int[] sweep = new int[sweepRaw.length];
            for (int i = 0; i < sweepRaw.length; i++) {
                float v = Math.max(0f, Math.min(1f, cdLuminance(sweepRaw[i]))) * 0.65f;
                sweep[i] = blend(sweepRaw[i], toneBase, (1f - v) * (dark ? 0.34f : 0.55f));
            }
            GradientDrawable disc = new GradientDrawable();
            disc.setShape(GradientDrawable.OVAL);
            disc.setGradientType(GradientDrawable.SWEEP_GRADIENT);
            disc.setColors(sweep);
            cdDisc.setBackground(disc);
        }
        // 光泽层（tpCdSheen）：此前星辰自定的 TL_BR 白光三段盖满盘面作废，改瓜子 hubSweep 组（a21.b，10 档，
        // 停点 0／0.15／0.27／0.31／0.38／0.5／0.67／0.79／0.84／1.0，档色与盘面同式经 a21.a() 变换）；
        // 光泽层以 caustic 初值 42° 为扫掠起始角（rememberCdMotion 初值）。
        // 缺口说明：hubSweep 在瓜子 CdArtwork 里画在封面之下（hub 圆半径远小于封面圆，封面盖住），星辰侧
        // 无独立 hub 面可贴；此处是借用星辰现成的全盘光泽层表面铺 hubSweep 档色（触发式 700／450ms 淡入淡出
        // 的载体），不是瓜子 hub 面的原位置复现，见报告。
        if (cdSheenView != null) {
            int[] hubRaw = dark ? new int[]{0xFF2E3742, 0xFF8792A0, 0xFF3B4653, 0xFF443C52,
                    0xFF4E493F, 0xFF262D37, 0xFF8C97A5, 0xFF3C4A55, 0xFF46405A, 0xFF2E3742}
                    : new int[]{0xFF9FAAB7, 0xFFF0F3F6, 0xFFB6C7D0, 0xFFCFC6D8,
                    0xFFE9E3D4, 0xFF929FAC, 0xFFF7F8FA, 0xFFBACCD2, 0xFFD4CADB, 0xFF9FAAB7};
            float toneLum2 = cdLuminance(dominantColor);
            float i02 = Math.min((1f - toneLum2) * (dark ? 0f : 0.25f) + (dark ? 0.50f : 0.55f), 0.92f);
            int toneBase2 = blend(dark ? 0xFF2A3038 : 0xFFFFFFFF, dominantColor, i02);
            int[] hub = new int[hubRaw.length];
            for (int i = 0; i < hubRaw.length; i++) {
                float v = Math.max(0f, Math.min(1f, cdLuminance(hubRaw[i]))) * 0.65f;
                hub[i] = blend(hubRaw[i], toneBase2, (1f - v) * (dark ? 0.34f : 0.55f));
            }
            cdSheenView.setBackground(new SweepStopsDrawable(hub,
                    new float[]{0f, 0.15f, 0.27f, 0.31f, 0.38f, 0.5f, 0.67f, 0.79f, 0.84f, 1f}, 42f));
        }
        refreshModeIcon(MusicController.get().getMode());
        // GZ CD 顶右菜单＋五图标行（除收藏心外）统一刷 muted（收藏心由下面 paintFavoriteIcons 按收藏态刷 muted／红心）；
        // 顶右菜单与行图标 drawable 本色是白，不刷 muted 在浅色珍珠底上隐形。
        ColorStateList mutedList = ColorStateList.valueOf(muted);
        View cdMenu = find(R.id.tpBtnMenu);
        if (cdMenu instanceof ImageView) ((ImageView) cdMenu).setImageTintList(mutedList);
        // 顶行收起键与菜单同口径刷 muted（瓜子 CdIcon 默认色即 muted）。
        View cdCollapseBtn = find(R.id.tpBtnCollapse);
        if (cdCollapseBtn instanceof ImageView) ((ImageView) cdCollapseBtn).setImageTintList(mutedList);
        View utilRow = find(R.id.tpCdUtilRow);
        if (utilRow instanceof ViewGroup) {
            ViewGroup row = (ViewGroup) utilRow;
            for (int i = 0; i < row.getChildCount(); i++) {
                View cell = row.getChildAt(i);
                if (cell instanceof ViewGroup) {
                    ViewGroup cellGroup = (ViewGroup) cell;
                    for (int j = 0; j < cellGroup.getChildCount(); j++) {
                        View v = cellGroup.getChildAt(j);
                        if (v instanceof ImageView && v.getId() != R.id.tpFavHeart) {
                            ((ImageView) v).setImageTintList(mutedList);
                        }
                    }
                }
            }
        }
        // 收藏心跟表面走：表面切换（重进主题／切表面）后心色同步重刷，否则 CD 切深浅后心色还停在旧表面
        paintFavoriteIcons();
        // wave2：表面切换后第 5 格字形同步跟当前表面（cycleCdSurface 走 applyCdPalette，不另调）。
        refreshCdUtilGlyphs();
        refreshCdUtilRowEnabled();
    }

    /** 唱片架进度着色（GZ RecordShelfPlayerKt：底轨 ShelfInk 0.12、填充封面语气色向 ShelfInk 混 0.65）；
         先 mutate 隔离共享 drawable（CD 也用同款深色进度 drawable，不隔离会连带改 CD 的轨），再直接改两层 shape 的 solid 色
         （不用 ColorFilter：SRC_IN 会拿 drawable 自身半透明 alpha 相乘，底轨 0.12 会被乘成 0.02 近乎透明）。 */
    private void applyShelfSeekTint() {
        if (seekView == null || !THEME_SHELF.equals(themeId)) return;
        // 歌词调色与进度填充同源（封面语气色 dominantColor）：唱片架歌词走瓜子 Color＋alpha 模型
        // （sung＝语气色向 Ink 混 0.65、未唱 0.48、闲行 Muted 0.72、翻译 Muted），不在浅色面 hex 值上；
        // 调用点与进度着色一致（主题装配／无封面／取色回调），封面换了调色跟着换。
        if (lyricView != null) lyricView.setShelfLyricPalette(dominantColor);
        try {
            android.graphics.drawable.Drawable pd = seekView.getProgressDrawable();
            if (!(pd instanceof android.graphics.drawable.LayerDrawable)) return;
            android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) pd.mutate();
            android.graphics.drawable.Drawable bg = ld.findDrawableByLayerId(android.R.id.background);
            if (bg instanceof android.graphics.drawable.GradientDrawable) {
                ((android.graphics.drawable.GradientDrawable) bg).setColor(0x1F252522);
            }
            android.graphics.drawable.Drawable prog = ld.findDrawableByLayerId(android.R.id.progress);
            if (prog instanceof android.graphics.drawable.ClipDrawable) {
                android.graphics.drawable.Drawable inner = ((android.graphics.drawable.ClipDrawable) prog).getDrawable();
                if (inner instanceof android.graphics.drawable.GradientDrawable) {
                    ((android.graphics.drawable.GradientDrawable) inner)
                            .setColor(blend(dominantColor, 0xFF252522, 0.65f));
                }
            }
        } catch (Throwable ignored) {
        }
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

    /** CD 自旋 16°/s：整圈 22500ms 线性无限；暂停冻结角度（animator.pause 保角）。
     * 持续流动层（瓜子 CdMotion，rememberCdMotion:3707 起）：四路状态初值 spin＝0、flow＝0、caustic＝42、
     * undertow＝214 已落（光泽层 SweepStopsDrawable 以 caustic 初值 42° 为扫掠起始角，见 applyCdPalette；
     * spin 初值 0 即盘旋转起始角）；四路推进速率在瓜子侧是节拍包络回调的实时值（runner 内 boost×16°/s
     * 之外，flow／caustic／undertow 的速率回调由调用方传入的实时状态驱动，jadx 内无固定字面速率），
     * 星辰侧不编速率：此前 6000ms 正弦微动是星辰自定值（无瓜子出处），本批删除，缺口见动效对照表。
     * 光泽层平时静止（触发式 700ms 淡入／450ms 淡出见 triggerCdSheen），持续微动待速率解码后另落。 */
    private void buildCdAnimator() {
        if (cdDisc == null) return;
        // 卡顿审计：盘旋转本身是渲染属性（ROTATION），但盘子树含封面位图＋扫光＋光泽层，无硬件层时每帧
        // 重绘整棵子树；挂硬件层后旋转只走图层变换，位图不逐帧重绘（暂停时 animator.pause 保角，图层保留）。
        cdDisc.setLayerType(View.LAYER_TYPE_HARDWARE, null);
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
        } else {
            if (cdAnimator.isRunning()) cdAnimator.pause();
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
        if (shelfList == null || shelfAdapter == null) return;
        int idx = MusicController.get().index();
        if (idx < 0) {
            // 无曲目：直接重绑（不走弹簧，见 bindTrackViews 注释）。
            shelfAdapter.notifyDataSetChanged();
            return;
        }
        // 3 槽舞台连续插值：切歌时先不重绑，旧内容整体平移一槽（下一首 shift 0→−1、上一首 0→＋1），
        // 落位瞬间同帧重绑新内容——落位前中心槽显示的正是新当前曲封面（旧邻槽滑入），落位后中心槽
        // 仍是同一封面（新当前），像素级连续，无封面闪换；先重绑再滑会在弹簧起点闪一下旧／新封面。
        // 歌词面切换动画（GZ shelfInnerPage：出 150ms／入 300ms＋延迟 150ms／缩放 0.98）与弹簧并行，
        // 落在歌词面视图上，不与槽位变换写同一属性，无冲突。
        int prev = shelfPrevIndex;
        shelfPrevIndex = idx;
        if (prev >= 0 && prev != idx) requestShelfInnerPageSwitch();
        if (prev < 0 || prev == idx || Math.abs(idx - prev) != 1) {
            // 首绑／同曲重绑／跳号切歌（随机播放跳到非相邻曲目）：无滑动，直接重绑。
            // 跳号时滑一槽是假动作——落位重绑会把中心封面换成跳号后的曲，等于闪一下，不如直接换。
            shelfSlotShift = 0f;
            shelfAdapter.notifyDataSetChanged();
            applyShelfSlotShift();
            return;
        }
        if (shelfShiftRunning) {
            // 快速连切：旧弹簧未落位时新切歌到达——取消旧弹簧、直接落位重绑到最新曲目，不叠加弹簧；
            // 连续动画让给最终态，中间态不逐个补播，否则越切越拖。
            if (shelfSlotAnimator != null) shelfSlotAnimator.cancel();
            shelfShiftRunning = false;
            shelfSlotShift = 0f;
            rebindShelfSlotsSync();
            return;
        }
        final float dir = idx > prev ? -1f : 1f;
        shelfSlotShift = 0f;
        // 弹簧期间收藏键先藏（旧当前视图滑向邻槽时键会跟着走，落位重绑后恢复只在当前槽显示）。
        for (int slotPos = 0; slotPos < 3; slotPos++) {
            RecyclerView.ViewHolder vh = shelfList.findViewHolderForAdapterPosition(slotPos);
            if (vh instanceof ShelfAdapter.Holder && ((ShelfAdapter.Holder) vh).fav != null) {
                ((ShelfAdapter.Holder) vh).fav.setVisibility(View.GONE);
            }
        }
        if (!startShelfSlotShiftSpring(dir, () -> {
            // 落位同帧重绑：先把 shift 清零、再同步重绑三槽——同一帧内完成，中心槽像素不变，
            // 不经过“旧内容回静态位、下一帧才换新内容”的中间帧，否则闪一帧旧内容。
            shelfSlotShift = 0f;
            rebindShelfSlotsSync();
        })) {
            shelfSlotShift = 0f;
            rebindShelfSlotsSync();
        }
    }

    /** 三槽同步重绑：直接调 onBindViewHolder（不走 notify 的异步布局），供落位／打断时同帧换内容。 */
    private void rebindShelfSlotsSync() {
        if (shelfList == null || shelfAdapter == null) return;
        for (int slotPos = 0; slotPos < 3; slotPos++) {
            RecyclerView.ViewHolder vh = shelfList.findViewHolderForAdapterPosition(slotPos);
            if (vh instanceof ShelfAdapter.Holder) shelfAdapter.onBindViewHolder((ShelfAdapter.Holder) vh, slotPos);
        }
    }

    /** 3 槽舞台槽位弹簧：shift 从 0 压向 dir（下一首 −1、上一首 ＋1），落位回调跑 onSettled。 */
    private boolean startShelfSlotShiftSpring(float dir, Runnable onSettled) {
        if (shelfList == null || shelfAdapter == null) return false;
        float d = getResources().getDisplayMetrics().density;
        int slotW = (int) (shelfAdapter.shelfGeometry().slotPitchDp * d);
        if (slotW <= 0) return false;
        if (shelfSlotAnimator != null) shelfSlotAnimator.cancel();
        shelfSlotAnimator = new com.fongmi.android.tv.ui.custom.ShelfSlotAnimator(shelfList, slotW);
        shelfShiftRunning = true;
        return shelfSlotAnimator.animateSlotShift(0f, dir,
                new com.fongmi.android.tv.ui.custom.ShelfSlotAnimator.ShiftListener() {
                    @Override
                    public void onShift(float shift) {
                        shelfSlotShift = shift;
                        applyShelfSlotShift();
                    }

                    @Override
                    public void onSettled() {
                        shelfShiftRunning = false;
                        if (onSettled != null) onSettled.run();
                    }
                });
    }

    /** 槽位插值帧：只改三槽 holder 的位移／缩放（GPU 属性），不触发布局、不重绑、不重载图。 */
    private void applyShelfSlotShift() {
        if (shelfList == null || shelfAdapter == null) return;
        for (int slotPos = 0; slotPos < 3; slotPos++) {
            RecyclerView.ViewHolder vh = shelfList.findViewHolderForAdapterPosition(slotPos);
            if (vh instanceof ShelfAdapter.Holder) applyShelfSlotTransform((ShelfAdapter.Holder) vh, slotPos);
        }
    }

    /**
     * 单槽变换：有效槽位＝静态槽位（slotPos−1）＋当前插值 shift；缩放取瓜子公式
     * scaleForSlot（f4＋(1−f4)×v，v＝clamp(1−|slot|,0,1)）；位移＝shift×槽距（整组槽位同向平移，
     * 新当前项由此从旧邻槽滑入中央）＋首项＋次槽布局补偿；alpha 不在这里动（出场淡出／入场淡入
     * 另有动画写 alpha，同属性双写会抖动，静态 alpha＝1 由 onBind 定）。
     * 变换原点 (0.5, 1) 底部居中已在 onCreate 定死（pivotX＝封套×0.5，pivotY＝封套），不动。
     */
    private void applyShelfSlotTransform(ShelfAdapter.Holder h, int slotPos) {
        if (shelfAdapter == null) return;
        ShelfStageGeometry g = shelfAdapter.shelfGeometry();
        float d = getResources().getDisplayMetrics().density;
        float effSlot = (slotPos - 1) + shelfSlotShift;
        float scale = g.scaleForSlot(effSlot);
        h.itemView.setScaleX(scale);
        h.itemView.setScaleY(scale);
        float txDp = shelfSlotShift * g.slotPitchDp + g.listItemTranslationXDp(effSlot);
        // 视觉中心补偿：封面在槽位盒内左对齐，封面视觉中心在槽位盒中心左侧半个项间间距；
        // 补偿算式＝(槽距 − 封套) / 2＝项间间距 / 2，390dp 屏 (230.1 − 185.25) / 2 ≈ 22.4dp，
        // 全槽同量右移（相对间距不变、弹簧插值不变），当前槽封面中心落到舞台中心。
        // 方向按几何注释推导（左对齐→向右补），真机须以屏中线复核，量反了只改本行符号。
        txDp += (g.slotPitchDp - g.sleeveDp) * 0.5f;
        if (slotPos == 2) txDp -= (g.outerWidthDp - g.sleeveDp); // 次槽布局补偿：当前槽根宽 1.48×封套多出的 0.48×封套
        h.itemView.setTranslationX(txDp * d);
    }

    /**
     * 唱片架歌词面切换：动画时钟认歌词到达事件。切歌时 loadLyric 先清旧词、歌词后台异步
     * 到达；若取词尚在途（lyricLoading），先挂起入场、歌词到达时再跑淡入淡出，淡入跑完
     * 时新词已在面内，不再“淡入空面、词到后直弹”；取词超时（1500ms 兜底）或无取词在途
     * 时直接跑现有动画，不把界面挂死。连续切歌会取消上一首的挂起、为新曲重挂。
     */
    private void requestShelfInnerPageSwitch() {
        if (!lyricLoading || shelfInnerPageView == null) {
            animateShelfInnerPageSwitch();
            return;
        }
        cancelShelfLyricSwitchPending();
        shelfLyricSwitchPending = true;
        shelfLyricTimeoutRunnable = () -> {
            if (!shelfLyricSwitchPending) return;
            shelfLyricSwitchPending = false;
            animateShelfInnerPageSwitch();
        };
        shelfInnerPageView.postDelayed(shelfLyricTimeoutRunnable, 1500);
    }

    /** 歌词到达事件：取词回调里 lyricLoading 落位后调，挂起的歌词面入场即跑。 */
    private void onShelfLyricArrived() {
        if (!shelfLyricSwitchPending) return;
        cancelShelfLyricSwitchPending();
        animateShelfInnerPageSwitch();
    }

    /** 取消挂起的歌词面入场（换主题重建／新切歌重挂时用；已跑起的动画本身不动）。 */
    private void cancelShelfLyricSwitchPending() {
        shelfLyricSwitchPending = false;
        if (shelfInnerPageView != null && shelfLyricTimeoutRunnable != null) {
            shelfInnerPageView.removeCallbacks(shelfLyricTimeoutRunnable);
        }
        shelfLyricTimeoutRunnable = null;
    }

    /** 唱片架歌词面切换动画（GZ shelfInnerPage AnimatedContent，RecordShelfPlayerKt:732-737）：
     * 出场＝淡出 150ms（INTERP_OUT_C）＋缩放→0.98；入场＝淡入 300ms＋150ms 延迟（INTERP_LOSI）
     * ＋缩放 0.98→1（INTERP_EMPH）；数值与瓜子一致。注意：对照表称 shelfInnerPage 为歌词面，
     * 故动画落点是歌词面视图（shelfInnerPageView），不是槽根——旧代码落在槽根上是目标错位，
     * 且会与槽位弹簧写同一批位移／缩放属性打架；本批纠正到歌词面，与弹簧并行无冲突。 */
    private void animateShelfInnerPageSwitch() {
        final View surface = shelfInnerPageView;
        if (surface == null) return;
        if (shelfSwitchAnim != null) shelfSwitchAnim.cancel();
        android.animation.ObjectAnimator alOut = android.animation.ObjectAnimator.ofFloat(
                surface, View.ALPHA, surface.getAlpha(), 0f);
        alOut.setDuration(150);
        alOut.setInterpolator(INTERP_OUT_C);
        android.animation.ObjectAnimator sxOut = android.animation.ObjectAnimator.ofFloat(
                surface, View.SCALE_X, surface.getScaleX(), 0.98f);
        sxOut.setDuration(150);
        sxOut.setInterpolator(INTERP_OUT_C);
        android.animation.ObjectAnimator syOut = android.animation.ObjectAnimator.ofFloat(
                surface, View.SCALE_Y, surface.getScaleY(), 0.98f);
        syOut.setDuration(150);
        syOut.setInterpolator(INTERP_OUT_C);
        android.animation.AnimatorSet outSet = new android.animation.AnimatorSet();
        outSet.playTogether(alOut, sxOut, syOut);
        outSet.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                android.animation.ObjectAnimator alIn = android.animation.ObjectAnimator.ofFloat(
                        surface, View.ALPHA, 0f, 1f);
                alIn.setDuration(300);
                alIn.setStartDelay(150);
                alIn.setInterpolator(INTERP_LOSI);
                android.animation.ObjectAnimator sxIn = android.animation.ObjectAnimator.ofFloat(
                        surface, View.SCALE_X, 0.98f, 1f);
                sxIn.setDuration(300);
                sxIn.setStartDelay(150);
                sxIn.setInterpolator(INTERP_EMPH);
                android.animation.ObjectAnimator syIn = android.animation.ObjectAnimator.ofFloat(
                        surface, View.SCALE_Y, 0.98f, 1f);
                syIn.setDuration(300);
                syIn.setStartDelay(150);
                syIn.setInterpolator(INTERP_EMPH);
                shelfSwitchAnim = new android.animation.AnimatorSet();
                shelfSwitchAnim.playTogether(alIn, sxIn, syIn);
                shelfSwitchAnim.start();
            }
        });
        shelfSwitchAnim = outSet;
        outSet.start();
    }

    /** 唱片架项收藏 reveal（redo3 第四轮 S7）：300ms 收起（INTERP_OUT_C）→调现有 toggleFavorite（写盘＋paintFavoriteIcons＋burstFavorite 原样保留）
     * →600ms 放回（INTERP_FOSI）；全程 favoriteBusy 防重入；主控行收藏键保持直通 toggleFavorite 不变；
     * 架项收藏键只在当前项显示（toggleFavorite 只作用于当前曲目，非当前项点收藏会错指曲目，故非当前项不露键）。 */
    private void revealFavorite(ShelfAdapter.Holder holder) {
        if (holder == null || favoriteBusy) return;
        int pos = holder.getBindingAdapterPosition();
        // pos 是槽位号：按槽位映射回队列号再比当前曲目（收藏只作用于当前曲目）。
        if (pos < 0 || shelfAdapter == null || shelfAdapter.slotQueueIndex(pos) != MusicController.get().index()) return;
        favoriteBusy = true;
        View root = holder.itemView;
        try {
            root.animate().scaleX(0.85f).scaleY(0.85f).alpha(0.6f).setDuration(300).setInterpolator(INTERP_OUT_C)
                    .withEndAction(() -> {
                        try {
                            toggleFavorite(holder.fav);
                        } catch (Throwable ignored) {
                        }
                        root.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(600).setInterpolator(INTERP_FOSI)
                                .withEndAction(() -> {
                                    favoriteBusy = false;
                                    if (holder.fav != null) {
                                        holder.fav.setImageResource(favorited
                                                ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
                                        holder.fav.setImageTintList(android.content.res.ColorStateList.valueOf(
                                                favorited ? 0xFFF2433D : 0xFFFFFFFF));
                                    }
                                }).start();
                    }).start();
        } catch (Throwable ignored) {
            favoriteBusy = false;
        }
    }

    /** 唱片架项：封面方卡（左）＋黑胶圆盘（右侧滑出）；当前项盘旋转 30°/s，其余静止；点按切歌；
     * 3 槽舞台：适配器只出前／当／后三槽（slotQueueIndex：槽位 0＝上一首、1＝当前、2＝下一首，
     * 队首／队尾空槽占位不可见），不再铺整条队列；切歌走槽位弹簧插值（scrollShelfToCurrent）。
     * 项几何走 ShelfStageGeometry（瓜子舞台公式链，约束维取屏宽 dp：舞台 clamp(宽×0.66,180,300)，
     * f2 = min(宽×0.5, 舞台-36)，封套 = f2×0.95，圆盘 = 0.97×封套，中心参考 c = 宽×0.59，槽距即 c（无钳制），
     * 非当前缩放 f4 = (f2×0.78)／封套由公式算出）；圆盘以 0.24×封套向右探出（瓜子右探量仅推导 0.48×封套，
     * 非字面值，星辰 0.24 留着，见交回）；双层圆角：袖层 1.5dp（透明轮廓＋4dp 阴影）＋封面本体 2dp（3dp 阴影）；
     * 圆盘阴影 5dp 落在盘内阴影层（盘本体不抬 elevation，防盘盖封面）；架板已渲染（宽 1.1×封套、高 6dp、
     * 偏移 (−0.05×封套, 封套−2dp)，色暂用本主题已有 Ink #FF252522，瓜子色值未定位）；
     * 当前槽项根宽 1.48×封套、非当前槽根宽封套；非当前项按 f4 缩小＋按相对位置位移、无 alpha 压暗（瓜子无压暗）。
     * 布局改编（见 ShelfStageGeometry 类注释）：瓜子位移第二项 c×slot 由槽距排布承担，不以 translationX 叠加。 */
    private class ShelfAdapter extends RecyclerView.Adapter<ShelfAdapter.Holder> {

        ShelfStageGeometry shelfGeometry() {
            float w = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
            return ShelfStageGeometry.fromWidthDp(w);
        }

        /** 槽位→队列号：槽位 0＝上一首、1＝当前、2＝下一首；队首／队尾越界返回 −1（空槽占位）。 */
        int slotQueueIndex(int slotPos) {
            try {
                int idx = MusicController.get().index();
                if (idx < 0) return -1;
                return idx + slotPos - 1;
            } catch (Throwable ignored) {
                return -1;
            }
        }

        float shelfSleeveDp() {
            return shelfGeometry().sleeveDp;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            float d = getResources().getDisplayMetrics().density;
            ShelfStageGeometry g = shelfGeometry();
            float sleeve = g.sleeveDp;
            float discSize = g.discDp;
            FrameLayout root = new FrameLayout(parent.getContext());
            root.setClipChildren(false);
            root.setLayoutParams(new RecyclerView.LayoutParams((int) (sleeve * d), (int) (sleeve * d)));
            // S2：缩放原点改底部（瓜子槽位变换原点 (0.5, 1)），与槽位缩放一致。
            root.setPivotX(sleeve * d * 0.5f);
            root.setPivotY(sleeve * d);
            // 架板（瓜子:3270）：宽 1.1×封套、高 6dp、偏移 (−0.05×封套, 封套−2dp)，相对封套左缘定位，
            // 最先加（在盘与封面之下）；色暂用本主题已有 Ink #FF252522，瓜子色值未定位，见交回。
            View board = new View(parent.getContext());
            FrameLayout.LayoutParams boardLp = new FrameLayout.LayoutParams(
                    (int) (g.boardWidthDp * d), (int) (ShelfStageGeometry.BOARD_HEIGHT_DP * d));
            boardLp.gravity = Gravity.TOP | Gravity.START;
            boardLp.leftMargin = Math.round(g.boardOffsetXDp * d);
            boardLp.topMargin = Math.round(g.boardOffsetYDp * d);
            board.setLayoutParams(boardLp);
            board.setBackgroundColor(0xFF252522);
            root.addView(board);
            // S1：disc 改黑胶盘面绘制——黑底＋纹理环＋中心小封面 0.22×盘＋中心点；
            // 旋转仍转 disc 层（整层 FrameLayout 转，纹理／中心图／中心点同转）。
            // 圆盘阴影 5dp 落在盘内阴影层（elevation 5dp、透明椭圆轮廓，只出阴影不填色）：
            // 盘本体不抬 elevation（抬了会把盘抬到封面之上、盖住封面右半），阴影层随盘同位移同旋转，
            // 旧 1dp 半透明描边近似删掉。
            FrameLayout disc = new FrameLayout(parent.getContext());
            FrameLayout.LayoutParams discLp = new FrameLayout.LayoutParams((int) (discSize * d), (int) (discSize * d));
            discLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            disc.setLayoutParams(discLp);
            disc.setTranslationX(sleeve * ShelfStageGeometry.PROTRUDE_FACTOR * d);
            View discShadow = new View(parent.getContext());
            discShadow.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            android.graphics.drawable.GradientDrawable shadowBg = new android.graphics.drawable.GradientDrawable();
            shadowBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            shadowBg.setColor(0x00000000);
            discShadow.setBackground(shadowBg);
            discShadow.setElevation(ShelfStageGeometry.DISC_SHADOW_DP * d);
            disc.addView(discShadow);
            android.graphics.drawable.GradientDrawable vinyl = new android.graphics.drawable.GradientDrawable();
            vinyl.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            vinyl.setColor(0xFF0A0A0C);
            disc.setBackground(vinyl);
            disc.setClipChildren(false);
            for (float frac : new float[]{0.88f, 0.72f, 0.56f}) {
                View groove = new View(parent.getContext());
                FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                        Math.round(discSize * frac * d), Math.round(discSize * frac * d));
                glp.gravity = Gravity.CENTER;
                groove.setLayoutParams(glp);
                android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
                ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                ring.setStroke(Math.round(1 * d), 0xFF26262B);
                groove.setBackground(ring);
                disc.addView(groove);
            }
            ImageView discCenter = new ImageView(parent.getContext());
            FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(
                    Math.round(discSize * ShelfStageGeometry.DISC_CENTER_FACTOR * d),
                    Math.round(discSize * ShelfStageGeometry.DISC_CENTER_FACTOR * d));
            centerLp.gravity = Gravity.CENTER;
            discCenter.setLayoutParams(centerLp);
            discCenter.setScaleType(ImageView.ScaleType.CENTER_CROP);
            setCircle(discCenter);
            disc.addView(discCenter);
            View dotOuter = new View(parent.getContext());
            FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(
                    Math.round(discSize * ShelfStageGeometry.DISC_DOT1_FACTOR * d),
                    Math.round(discSize * ShelfStageGeometry.DISC_DOT1_FACTOR * d));
            dotLp.gravity = Gravity.CENTER;
            dotOuter.setLayoutParams(dotLp);
            android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
            dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dotBg.setColor(0xFFD8D8D8);
            dotOuter.setBackground(dotBg);
            disc.addView(dotOuter);
            View dotInner = new View(parent.getContext());
            FrameLayout.LayoutParams dotInLp = new FrameLayout.LayoutParams(
                    Math.round(discSize * ShelfStageGeometry.DISC_DOT2_FACTOR * d),
                    Math.round(discSize * ShelfStageGeometry.DISC_DOT2_FACTOR * d));
            dotInLp.gravity = Gravity.CENTER;
            dotInner.setLayoutParams(dotInLp);
            android.graphics.drawable.GradientDrawable dotInBg = new android.graphics.drawable.GradientDrawable();
            dotInBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dotInBg.setColor(0xFF14161C);
            dotInner.setBackground(dotInBg);
            disc.addView(dotInner);
            root.addView(disc);
            // 双层圆角：袖层（1.5dp 透明轮廓＋4dp 阴影）包封面本体（2dp 圆角＋3dp 阴影）；
            // 袖层不填色（不编填充色），只出阴影轮廓；clipChildren 关掉，封面阴影不被袖层裁掉。
            FrameLayout sleeveWrap = new FrameLayout(parent.getContext());
            FrameLayout.LayoutParams wrapLp = new FrameLayout.LayoutParams((int) (sleeve * d), (int) (sleeve * d));
            wrapLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            sleeveWrap.setLayoutParams(wrapLp);
            sleeveWrap.setClipChildren(false);
            android.graphics.drawable.GradientDrawable sleeveBg = new android.graphics.drawable.GradientDrawable();
            sleeveBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            sleeveBg.setCornerRadius(ShelfStageGeometry.SLEEVE_CORNER_DP * d);
            sleeveBg.setColor(0x00000000);
            sleeveWrap.setBackground(sleeveBg);
            sleeveWrap.setElevation(ShelfStageGeometry.SLEEVE_SHADOW_DP * d);
            ImageView cover = new ImageView(parent.getContext());
            cover.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setElevation(ShelfStageGeometry.COVER_SHADOW_DP * d);
            setRounded(cover, ShelfStageGeometry.COVER_CORNER_DP);
            sleeveWrap.addView(cover);
            root.addView(sleeveWrap);
            ImageView fav = new ImageView(parent.getContext());
            FrameLayout.LayoutParams favLp = new FrameLayout.LayoutParams((int) (40 * d), (int) (40 * d));
            // 收藏键锚在封套右上角（START＋左 margin＝封套−40dp），不随当前槽根宽 1.48×封套右移；
            // 否则当前槽键会跑到圆盘区上方。
            favLp.gravity = Gravity.TOP | Gravity.START;
            favLp.leftMargin = (int) ((sleeve - 40) * d);
            fav.setLayoutParams(favLp);
            fav.setPadding((int) (10 * d), (int) (10 * d), (int) (10 * d), (int) (10 * d));
            fav.setElevation(8 * d);
            fav.setImageResource(R.drawable.ic_music_heart);
            fav.setContentDescription(getString(R.string.music_favorite));
            root.addView(fav);
            return new Holder(root, cover, disc, discCenter, fav);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            // 3 槽模型：position 是槽位号（0＝上一首、1＝当前、2＝下一首），不是队列号。
            int queueIndex = slotQueueIndex(position);
            List<MusicTrack> queue = MusicController.get().queue();
            if (queueIndex < 0 || queueIndex >= queue.size()) {
                // 队首／队尾空槽：占位不可见（仍占布局宽，当前槽保持居中），不绑图、不转、不点。
                h.boundQueueIndex = -1;
                h.itemView.setVisibility(View.INVISIBLE);
                h.stopSpin();
                h.itemView.setOnClickListener(null);
                if (h.fav != null) {
                    h.fav.setVisibility(View.GONE);
                    h.fav.setOnClickListener(null);
                }
                return;
            }
            h.itemView.setVisibility(View.VISIBLE);
            h.boundQueueIndex = queueIndex;
            MusicTrack track = queue.get(queueIndex);
            String title = track.displayTitle();
            if (track.cover == null || track.cover.isEmpty()) {
                h.cover.setImageDrawable(new CoverTextDrawable(title, false,
                        12 * getResources().getDisplayMetrics().density));
                // S1：无封面时盘心不贴素图大字（中心仅 0.22×盘），留黑胶底透出。
                h.discCenter.setImageDrawable(null);
            } else {
                ImgUtil.load(title, track.cover, h.cover);
                ImgUtil.load(title, track.cover, h.discCenter);
            }
            boolean current = queueIndex == MusicController.get().index();
            ShelfStageGeometry g = shelfGeometry();
            float d = getResources().getDisplayMetrics().density;
            float sleeve = g.sleeveDp;
            // 项根宽：当前槽 1.48×封套（瓜子:3240）、非当前槽封套宽；根宽变了才重设（避免逐次 requestLayout）。
            ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
            int wPx = Math.round((current ? g.outerWidthDp : sleeve) * d);
            if (lp.width != wPx) {
                lp.width = wPx;
                h.itemView.setLayoutParams(lp);
            }
            // 圆盘锚在封套上：根宽 1.48×封套时盘若仍按 END＋0.24 探出，盘会右移 0.48×封套；
            // 当前槽盘位移改 −0.24×封套，使盘左缘恒为封套左缘＋0.27×封套（右探量仍是 0.24×封套，不动）。
            h.disc.setTranslationX((current ? -1f : 1f) * sleeve * ShelfStageGeometry.PROTRUDE_FACTOR * d);
            // S4：非当前项不再 alpha 压暗（瓜子变换内无 alpha 调用）；缩放／位移走槽位变换
            // （缩放＝f4＋(1−f4)×v，位移＝shift×槽距＋首项＋次槽补偿；c×slot 由槽距排布承担、不叠加）。
            h.itemView.setAlpha(1f);
            applyShelfSlotTransform(h, position);
            h.stopSpin();
            if (current && playing && animationsEnabled()) h.startSpin();
            // wave2：点当前架项不再从头重播（GZ 点当前槽是收藏，不是重播；星辰架项已有当前项爱心键做收藏，
            // 故点当前项改为无操作，不重复触发收藏，避免一次点按双份动作）。
            h.itemView.setOnClickListener(v -> {
                if (queueIndex == MusicController.get().index()) return;
                MusicController.get().playAt(queueIndex);
            });
            // S7：架项收藏键只在当前项显示，点按走 revealFavorite（非当前项不露键，避免错指曲目）。
            if (h.fav != null) {
                h.fav.setVisibility(current ? View.VISIBLE : View.GONE);
                if (current) {
                    h.fav.setImageResource(favorited ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
                    h.fav.setImageTintList(android.content.res.ColorStateList.valueOf(
                            favorited ? 0xFFF2433D : 0xFFFFFFFF));
                    h.fav.setOnClickListener(v -> revealFavorite(h));
                } else {
                    h.fav.setOnClickListener(null);
                }
            }
        }

        @Override
        public void onViewRecycled(@NonNull Holder h) {
            h.stopSpin();
            super.onViewRecycled(h);
        }

        @Override
        public int getItemCount() {
            // 3 槽模型：只出前／当／后三槽（空队列 0 项）；队列长度不再决定项数。
            try {
                return MusicController.get().queue().isEmpty() ? 0 : 3;
            } catch (Throwable ignored) {
                return 0;
            }
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final View disc;
            final ImageView discCenter;
            final ImageView fav;
            /** 本 holder 当前绑定的队列号（−1＝空槽占位）；自旋刷新按此认当前槽，不按槽位号。 */
            int boundQueueIndex = -1;
            ObjectAnimator spin;

            Holder(View root, ImageView cover, View disc, ImageView discCenter, ImageView fav) {
                super(root);
                this.cover = cover;
                this.disc = disc;
                this.discCenter = discCenter;
                this.fav = fav;
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

    /** 经典 Scrubber 轨高（redo3 C9）：静止 4dp、拖动 5dp；Apple 6dp 固定、CD 口径未抠到，均不动（封面卡片展开态 Scrubber 与经典同配置，随经典走）。
     * 封面卡片展开态另按瓜子 PlaybackScrubber 默认值走 150ms tween（4dp↔5dp），其余主题仍即时切换、不动。 */
    private void setSeekTrackHeight(SeekBar bar, float dp) {
        if (bar == null || THEME_APPLE.equals(themeId) || THEME_CD.equals(themeId)) return;
        int px = Math.round(dp * getResources().getDisplayMetrics().density);
        if (!THEME_IMMERSIVE.equals(themeId)) {
            bar.setMaxHeight(px);
            bar.setMinHeight(px);
            return;
        }
        if (seekTrackHeightAnim != null) seekTrackHeightAnim.cancel();
        int from = bar.getMaxHeight();
        if (from == px) {
            bar.setMaxHeight(px);
            bar.setMinHeight(px);
            return;
        }
        seekTrackHeightAnim = ValueAnimator.ofInt(from, px);
        seekTrackHeightAnim.setDuration(150);
        seekTrackHeightAnim.setInterpolator(INTERP_FOSI);
        seekTrackHeightAnim.addUpdateListener(a -> {
            int h = (int) a.getAnimatedValue();
            bar.setMaxHeight(h);
            bar.setMinHeight(h);
        });
        seekTrackHeightAnim.start();
    }

    /** 进度拖动态换装：经典系 bg_player_seek_progress ↔ _active（0.42→0.78）；Apple seek6 ↔ _active（填充 0.58→0.9、底轨 0.14→拖动 0.22）；CD 深色变体不换 */
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
        MusicLibrary.isFavorite(track, fav -> {
            favorited = fav;
            paintFavoriteIcons();
        });
    }

    /** 收藏态刷所有主题里出现的收藏图标（封面卡片玻璃条心走 favBtnView；经典第二行小爱心走 favHeartView，恒在；
     * 唱片架底行无收藏键、架项心由适配器自管，本方法无唱片架分支——旧 SHELF 分支对应视图恒为空，已删）。 */
    private void paintFavoriteIcons() {
        int icon = favorited ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart;
        // 封面卡片玻璃条（GZ A4）：收藏心恒白——已收藏实心白 0.85、未收藏线框白 0.45，不染红
        // 非封面主题未收藏心：CD 用 muted 系（Pearl #747D89／Space #8E96A3，不刷白不刷墨色 ink）；
        // 经典照 GZ 白 0.45／0.30（见下面 CLASSIC 分支）；Apple 头右爱心未收藏白 0.85；
        // 其余主题已收藏红心（深浅面都可见），未收藏白心。
        int color;
        // A3：Apple 分支前置——已收藏实心白 0.85、未收藏线框白 0.85（图标已按收藏态分实心／线框），
        // 不许被下面 favorited 判红分支抢先刷红。
        if (THEME_APPLE.equals(themeId)) {
            color = 0xD9FFFFFF;
        } else if (THEME_IMMERSIVE.equals(themeId)) {
            // 第三波残留 C3：玻璃条收藏心——已收藏 1.0、未收藏 0.85；禁用态 0.45。
            // 加载中信号现成：MusicController.isCurrentLoaded()（媒体已落位才 true）；
            // 禁用门＝无当前曲或当前曲尚未落位；落位后 onTrackReady 回调重刷本方法复位启用态。
            boolean favEnabled = MusicController.get().current() != null
                    && MusicController.get().isCurrentLoaded();
            color = !favEnabled ? 0x73FFFFFF : favorited ? 0xFFFFFFFF : 0xD9FFFFFF;
            // 必-2：沉浸式分支内落位启用态——未落位时收藏心只变暗不够，必须真正禁用点按。
            if (favBtnView != null) favBtnView.setEnabled(favEnabled);
        } else if (THEME_CLASSIC.equals(themeId)) {
            // wave2 K-4：经典小爱心照 GZ——已收藏实心白 0.45、未收藏描边白 0.30（旧“已收藏红／未收藏白 1.0”是错的）。
            color = favorited ? 0x73FFFFFF : 0x4DFFFFFF;
        } else if (favorited) {
            color = 0xFFF2433D;
        } else if (THEME_CD.equals(themeId)) {
            // GZ CD 爱心用 muted 系（Pearl #747D89／Space #8E96A3），不许刷白也不许刷墨色 ink
            color = cdIsDark() ? 0xFF8E96A3 : 0xFF747D89;
        } else {
            color = 0xFFFFFFFF;
        }
        if (favBtnView != null) {
            favBtnView.setImageResource(icon);
            favBtnView.setImageTintList(ColorStateList.valueOf(color));
        }
        // 经典歌名行收藏心：GZ 歌名行第二行“歌手 ♡ 专辑”里的心恒在（截图标准），不是“已收藏才出现”；
        // 未收藏＝线框白心、已收藏＝实心红心，点按切换（toggleFavorite）。歌词页头部心（pageBtnFav）不动。
        if (favHeartView != null) {
            favHeartView.setImageResource(icon);
            favHeartView.setImageTintList(ColorStateList.valueOf(color));
            favHeartView.setVisibility(View.VISIBLE);
        }
        if (pageBtnFav != null) {
            pageBtnFav.setImageResource(icon);
            pageBtnFav.setImageTintList(ColorStateList.valueOf(favorited ? 0xFFFA243C : 0xFFFFFFFF));
        }
    }

    private void toggleFavorite(View fromView) {
        MusicTrack track = MusicController.get().current();
        // 必-2：加载中守门——未落位时不接受收藏切换（此前只判 track==null）。
        if (track == null || !MusicController.get().isCurrentLoaded()) return;
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
        if (MusicStore.getDownloadMode() == MusicStore.DL_MODE_ASK) {
            int[] values = {MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD,
                    MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
            int fixed = MusicStore.getDownloadQualityFixed();
            int checked = 1;
            for (int i = 0; i < values.length; i++) if (values[i] == fixed) checked = i;
            // 第三波 S-A2：与列表页同款——选档前实测文件大小，实测不到不显示、不估算。
            // 堵-1：探查期间先给提示（toast，不新造弹窗）；回调首行存活守卫，页已走不建弹窗。
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

    /**
     * 音质格只显示服务实测的格子短码（SQ／HQ／Std／Hi-Res／24bit，GZ immersiveShortLabel 口径）；
     * 取不到实测时经典音质筹整筹隐藏、Apple 第三行整行隐藏——所选档永不进显示。
     * 旧口径“无损只显编码名（FLAC）”被最新令“就照他这么搞”覆盖，冲突行见对照表供 veto。
     */
    private void refreshQualityValue() {
        String real = realQualityText();
        if (qualityValueView != null) {
            qualityValueView.setText(real == null ? "" : real);
            View chip = qualityValueView.getParent() instanceof View ? (View) qualityValueView.getParent() : null;
            if (chip != null && chip.getId() == R.id.tpChipQuality) {
                chip.setVisibility(real == null ? View.GONE : View.VISIBLE);
                // 收起态守卫：音质实测到达时也不翻栈，仍由迷你条承载进度。
                if (bottomCollapsed) chip.setVisibility(View.GONE);
            }
        }
    }

    private void showQualityDialog() {
        if (isFinishing() || isDestroyed()) return;
        // 播放音质面板（GZ 面板形态）：点行即切档，行为与旧通用选项弹窗一致（只在变化时切档＋重载＋刷新音质格＋提示）
        MusicQualityPanelDialog.show(this, MusicController.get().current(), MusicController.get().getQuality(), quality -> {
            if (MusicController.get().getQuality() != quality) {
                MusicController.get().setQuality(quality);
                MusicController.get().reloadQuality();
                refreshQualityValue();
                Notify.show("已切换" + MusicStore.qualityName(quality));
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
        if (isFinishing() || isDestroyed()) return;
        try {
            MusicToolsSheetDialog.Listener listener = toolsSheetListener();
            if (lyricPanel) MusicToolsSheetDialog.showLyricPanel(this, listener);
            else MusicToolsSheetDialog.show(this, listener);
        } catch (Throwable ignored) {
        }
    }

    /** 堵-2：左划直达歌曲信息子面板（主面板歌曲信息行的同一入口，不经过主面板）。 */
    private void showToolsSheetSongInfo() {
        if (isFinishing() || isDestroyed()) return;
        try {
            MusicToolsSheetDialog.showSongInfoPanel(this, toolsSheetListener());
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
                    // L9：全屏歌词页样式同步刷新（旧版只刷内嵌视图，全屏页要关重开才生效）。
                    applyPageLyricStyle();
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
