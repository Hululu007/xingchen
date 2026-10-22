package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.player.lyrics.LyricsWord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 用途：全屏逐字歌词视图——逐字上浮＋长音光晕、非当前行磨砂压暗、弹簧跟随滚动，经典 View＋Canvas 自绘。
 * 参数口径：GZ 反编译参数对照表第 2 节（LyricsViewKt）逐项对齐，2026-10-21；
 * redo3 歌词面：深色面当前行未唱改瓜子白×0.42、深色面翻译改白×0.55、深色面非当前行改白×0.30、
 * 浅色面非当前行／翻译改回 GZ 原值（#9A9AA0／#8A8A90）；歌词斜体 SKEW=-0.12 按用户死令保留不动。
 */
public class KaraokeLyricsView extends View {

    // ===== 常量区（数值出处：GZ LyricsViewKt 常量区／函数实证＋星辰定稿口径，2026-10-21） =====
    /** 全屏歌词字号缺省（sp，PlayerViewModel lyricFontSize）。 */
    private static final float MAIN_SP_FULLSCREEN = 24f;
    /** 封面歌词字号缺省（sp，PlayerViewModel coverLyricFontSize），setCoverMode 定基线。 */
    private static final float MAIN_SP_COVER = 22f;
    /** 歌词字号钳制（GZ LYRIC_SIZE_MIN／LYRIC_SIZE_MAX）。 */
    private static final float LYRIC_SIZE_MIN = 16f;
    private static final float LYRIC_SIZE_MAX = 40f;
    /** B7：封面歌词字号钳制（MusicStore 封面键口径 14–32），与全屏 16–40 分开。 */
    private static final float LYRIC_SIZE_MIN_COVER = 14f;
    private static final float LYRIC_SIZE_MAX_COVER = 32f;
    /** 副行字号比（U-02：副行＝主行×0.62，行高＝副行字号×4/3，见 rebuildLayout）。 */
    private static final float SUB_RATIO = 0.62f;
    /**
     * L2 副行翻译三色 alpha（瓜子 LyricsViewKt:5887-5933）：已唱 1.0、当前行未唱 0.6、
     * 非当前行 0.42（两档未唱 clamp 原值 0.6／0.42，钳制区间 0.32-0.62／0.2-0.48 的落点即这两值）。
     * 三色同色相（翻译色），只以 alpha 区分已唱／未唱／闲行，不另起色。
     */
    private static final int SUB_SUNG_ALPHA = 255;
    private static final int SUB_UNSUNG_ALPHA = 153;
    private static final int SUB_IDLE_ALPHA = 107;
    /** 独立音译行默认（U-02：瓜子 AutoScrollRomaLine 默认 15sp／19sp；星辰音译无独立视图，
     * 音译只作副行或主行，故 15sp 仅作构造时副行画笔初值，19sp 仅作默认主行 24sp 时的自洽参照
     *（副行 24×0.62＝14.88≈15，行高 14.88×4/3≈19.84，与 19sp 量级自洽，不另立行高常量）；
     * 主行改字号后副行一律按 SUB_RATIO 跟着变，不再固定 15sp）。 */
    private static final float ROMA_SP = 15f;
    /** 歌词字形斜切（带一点斜，定稿口径）。死令：不动。 */
    private static final float SKEW = -0.12f;
    /** 当前行锚点档位（U-01：瓜子 lyricAnchor 档位制，默认 "top"；星辰固定分数 0.38 已删，
     * top 档＝当前行块顶对齐歌词区顶部（anchorY() 返回视图顶），不写录屏帧量估计值作常量——
     * 帧量（顶约 0.03、中心约 0.07，误差 ±0.04）只是实测估计，见分表，绝不进代码常量；
     * 其它档位换算无单一分数常量，本面只实现默认 top 档）。 */

    // —— 逐字上浮（GZ 2.2：WORD_LIFT_EM／WORD_LIFT_MIN_DURATION_MS／wordLiftProgressTime） ——
    /** 逐字上浮距离（em，负＝向上）：唱到的字上浮量＝0.09×字号。 */
    private static final float WORD_LIFT_EM = -0.09f;
    /** 上浮进度分母下限（ms）。 */
    private static final long WORD_LIFT_MIN_DURATION_MS = 1100L;
    /** 逐字 alpha 起点（时段内由 0.55 抬到 1，星辰口径）。 */
    private static final float WORD_ALPHA_FROM = 0.55f;

    // —— 长音光晕（GZ emphasizeGlow／emphasizeProgressTime／emphasizeScaleAmount） ——
    /** 长音阈值：时长 ≥1000ms 起算（GZ 光晕进度分母 max(1000, 时长) 即此意）。 */
    private static final long LONG_WORD_MS = 1000L;
    /** 长音字母漂浮（em）。 */
    private static final float EMPHASIZE_LETTER_FLOAT_EM = -0.05f;
    /** 光晕释放尾巴（ms，行末字进度分母再加）。 */
    private static final long EMPH_RELEASE_TAIL_MS = 400L;
    /** 长音光晕强度／半径系数（GZ emphasizeGlow，LyricsViewKt:11404-11427 全公式，第三轮逐系数核对后整段落为静态方法）。 */
    private static final float EMPH_GLOW_BASE = 0.6f;
    private static final float EMPH_GLOW_PER_SEC = 0.045f;
    private static final float EMPH_GLOW_CAP = 0.85f;
    private static final float EMPH_GLOW_LAST_MUL = 1.1f;
    private static final float EMPH_GLOW_LAST_CAP = 0.95f;
    private static final float EMPH_RADIUS_BASE = 0.3f;
    private static final float EMPH_RADIUS_PER_SEC = 0.012f;
    private static final float EMPH_RADIUS_CAP = 0.5f;
    private static final float EMPH_RADIUS_LAST_MUL = 1.15f;
    private static final float EMPH_RADIUS_LAST_CAP = 0.6f;

    // —— 弹簧（GZ 2.4：amllPosYComposeStiffness 公式＋三组弹簧规格） ——
    /** 刚度公式缺省帧间隔 delta（ms，GZ $default）。 */
    private static final long SPRING_DELTA_DEFAULT_MS = 450L;
    /** 封面模式位置弹簧：刚度 ×1.1、阻尼比 1.08（coverLyricPosSpring）。 */
    private static final double COVER_STIFFNESS_MUL = 1.1d;
    private static final double COVER_DAMPING_RATIO = 1.08d;
    /** 全屏落定弹簧：刚度基值、阻尼比 1.16（lyricSettleSpring）。 */
    private static final double SETTLE_DAMPING_RATIO = 1.16d;
    /** 点行 seek 弹簧：刚度 100、阻尼比 0.833（lyricSeekPosSpring）。 */
    private static final double SEEK_STIFFNESS = 100d;
    private static final double SEEK_DAMPING_RATIO = 0.833d;

    // —— 行高与窗口（GZ 2.1） ——
    /** 全屏行距 6dp／封面行距 20dp（APPLE_LYRIC_LINE_GAP／COVER_LINE_GAP）。 */
    private static final float LINE_GAP_FULLSCREEN_DP = 6f;
    private static final float LINE_GAP_COVER_DP = 20f;
    /** 横向 padding 28dp（APPLE_LYRIC_H_PADDING，封面同取此值与经典主题歌词区一致）。 */
    private static final float H_PADDING_DP = 28f;
    /** 渲染窗口：全屏前 3 后 15（APPLE_WINDOW_BEFORE／AFTER）；封面前 2 后 6（COVER_WINDOW_BEFORE／AFTER）。 */
    private static final int WINDOW_BEFORE_FULLSCREEN = 3;
    private static final int WINDOW_AFTER_FULLSCREEN = 15;
    private static final int WINDOW_BEFORE_COVER = 2;
    private static final int WINDOW_AFTER_COVER = 6;
    /** 封面目标可见行数（COVER_TARGET_VISUAL_LINES，视口高度由宿主布局决定，此处为口径常量）。 */
    private static final int COVER_TARGET_VISUAL_LINES = 4;
    /** 当前行最多折行：封面 2（COVER_CURRENT_MAX_LINES）；全屏 GZ 未给上限常量，View 层取 6 防病态长行（近似）。 */
    private static final int CURRENT_MAX_LINES_COVER = 2;
    private static final int CURRENT_MAX_LINES_FULLSCREEN = 6;
    /** 浏览回锚：全屏 5000ms（APPLE_BROWSE_REANCHOR_DELAY）；内嵌松手即恢复跟唱，不用回锚。 */
    private static final long REANCHOR_FULLSCREEN_MS = 5000L;

    // —— 渐隐（GZ 2.3） ——
    /** 顶部渐隐高度 40dp（APPLE_TOP_FADE_HEIGHT＝LYRIC_SIZE_MAX），曲线 smoothstep (3−2t)t²。 */
    private static final float TOP_FADE_HEIGHT_DP = 40f;
    /** 底部渐隐曲线控制点（bottomFadeKeep）：x 与 y（末点 y 缺省 0.45）。 */
    private static final float[] FADE_X = {0f, 0.3f, 0.55f, 0.78f, 1f};
    private static final float[] FADE_Y = {1f, 0.85f, 0.62f, 0.48f, 0.45f};

    // —— 非当前行压暗／模糊 ——
    /** 非当前行 alpha：按行距由 0.85 递减到 0.35 钳制（星辰口径；GZ 按距离的源头公式在 AnchoredLyricEngine 方法体内、jadx 整段跳过未抠到，此为应用侧等价式，保留）。 */
    private static final float IDLE_ALPHA_FAR = 0.85f;
    private static final float IDLE_ALPHA_NEAR_FLOOR = 0.35f;
    private static final float IDLE_ALPHA_STEP = 0.16f;
    /** 非当前行模糊半径（px）：min(BLUR_BASE＋d×BLUR_PER_LINE, BLUR_MAX)，0.5 步进量化（源头公式 GZ 侧未抠到，应用侧等价；GZ 应用侧同为 0.5 量化，见下条）。 */
    private static final float BLUR_BASE = 2f;
    private static final float BLUR_PER_LINE = 2.2f;
    private static final float BLUR_MAX = 12f;
    /**
     * 模糊应用侧（照 GZ LyricsViewKt AnchoredLyricEngine 应用侧 lambda :478-511 整段转写）：
     * 行 alpha 系数 ≥0.08 才取模糊值；值＞0 且 SDK≥31 且 RenderEffect 可用才挂；
     * E＝round(blur×2)、半径＝E/2（0.5 步进），按 E 缓存 RenderEffect.createBlurEffect(r, r, DECAL)；
     * 创建失败置冷却 30000ms（BLUR_RETRY_COOLDOWN_MS，LyricsViewKt:274），冷却内不重试、不挂模糊。
     * API＜31 无 RenderEffect：保留原 BlurMaskFilter 画笔实现作等效（同半径、同 0.5 量化、同 ≥0.08 门槛）。
     */
    private static final float BLUR_ALPHA_GATE = 0.08f;
    private static final long BLUR_RETRY_COOLDOWN_MS = 30000L;

    // —— 空态（GZ 参数表第 3 节：NoLyricPlaceholder／IdlePlaceholder，LyricsViewKt:5816-5817／6029-6030） ——
    /** 空态图标尺寸 40dp（＝LYRIC_SIZE_MAX）。 */
    private static final float EMPTY_ICON_DP = 40f;
    /** 空态字样字号 12sp。 */
    private static final float EMPTY_TEXT_SP = 12f;
    /** 空态字样主题覆盖（默认 0＝未覆盖，走 EMPTY_TEXT_SP／图标＋字样两段式；唱片架／封面卡片展开态按瓜子空态文案覆盖，见 setEmptyTextStyle）。 */
    private float emptyTextSp = EMPTY_TEXT_SP;
    private float emptyLineHeightSp;
    private boolean emptyTextOnly;
    private int emptyTextColor;
    /** 无歌词：图标 alpha 0.5（字样用非当前行色原值）。 */
    private static final float EMPTY_NO_LYRIC_ICON_ALPHA = 0.5f;
    /** 等待播放：图标 alpha 0.3、字样 alpha 0.4。 */
    private static final float EMPTY_WAITING_ICON_ALPHA = 0.3f;
    private static final float EMPTY_WAITING_TEXT_ALPHA = 0.4f;
    /** 三态：取词未定（默认，什么都不画）／已确认无词／无曲目等待播放。 */
    private static final int STATE_LOADING = 0;
    private static final int STATE_NO_LYRIC = 1;
    private static final int STATE_WAITING = 2;

    // —— 颜色（GZ 2.5 节四色原值，LyricsViewKt:294-306） ——
    /** 已唱色 ColorSung（GZ 原值）：当前行已唱部分与长音光晕目标色。 */
    static final int COLOR_SUNG = 0xFFFF2D55;
    /** 当前行未唱色 ColorActiveUnsung（GZ 原值）：浅色面未唱部分用此原值；深色面照此值会隐形，深面未唱用白（见类头注）。 */
    static final int COLOR_ACTIVE_UNSUNG = 0xFF3C3C43;
    /** 非当前行色 ColorInactive（GZ 原值）。 */
    static final int COLOR_INACTIVE = 0xFF9A9AA0;
    /** 翻译色 ColorTranslation（GZ 原值）：副行（翻译／音译）用。 */
    static final int COLOR_TRANSLATION = 0xFF8A8A90;
    /** 已唱柔色 ColorSungSoft（GZ 原值，LyricsViewKt:306）：常量留档，柔和态用。 */
    static final int COLOR_SUNG_SOFT = 0xFFEAEAEA;
    /** 主行未唱颜色（深色面）＝瓜子白×0.42（redo3 N-01：0x6BFFFFFF）。 */
    private static final int MAIN_COLOR = 0x6BFFFFFF;
    /**
     * 主行已唱／高亮颜色（深色面）＝GZ ColorSungSoft 0xFFEAEAEA 近白：
     * 证据 redo2-gz-player-evidence D 节——手机竖屏三主题＋全屏歌词页从不提供 palette、
     * immersiveDark 恒 TRUE，sungColor 走 ColorSungSoft；红 0xFFFF2D55 只在浅色面
     * （immersiveDark=false）被选中，见 activeColor()。与录屏像素实测 (231,227,219) 一致。
     */
    private static final int ACTIVE_COLOR = COLOR_SUNG_SOFT;
    /** 副行颜色（深色面）＝瓜子深色面翻译色白×0.55（redo3 N-03：0x8CFFFFFF）。 */
    private static final int SUB_COLOR = 0x8CFFFFFF;
    /** 非当前行颜色（深色面）＝GZ 非当前行色（再乘按距离 alpha 与渐隐）。 */
    private static final int IDLE_COLOR = COLOR_INACTIVE;
    /** 空歌词提示颜色（深色面）。 */
    private static final int EMPTY_COLOR = 0x8AFFFFFF;
    /** 浅色面（setLightScheme）：主行未唱＝GZ 当前行未唱原值、非当前行＝GZ 非当前行原值、副行＝GZ 翻译原值，主行已唱保持 GZ 已唱红。 */
    private static final int MAIN_COLOR_LIGHT = COLOR_ACTIVE_UNSUNG;
    private static final int IDLE_COLOR_LIGHT = COLOR_INACTIVE;
    private static final int SUB_COLOR_LIGHT = COLOR_TRANSLATION;
    private static final int EMPTY_COLOR_LIGHT = 0x8A000000;

    /** 整行模式无词时一行默认时长（算行进度用，末行兜底）。 */
    private static final long LINE_FALLBACK_MS = 4000L;
    /** 点击判定：位移小于该值视为点击（dp）。 */
    private static final float TAP_SLOP_DP = 16f;

    // —— 伴唱（M-03：瓜子伴唱分支字号×0.7、缩放 0.2×bg＋0.8、透明度×clamp(bg,0,1)、
    // 入场位移 (1-bg)×(-0.8)×行高；bg 入场 300ms 走星辰 INTERP_FOSI 映射（见 bgValue），终态 1） ——
    private static final float BG_FONT_RATIO = 0.7f;
    private static final long BG_ENTER_MS = 300L;
    // —— 间奏圆点（M-04，B 档拟映射：尺寸基准 f 取当前主行字号 sp 值，24sp 时直径 7.2dp、
    // 间距 2.4dp、总宽 26.4dp；待真机目视验证后再定死，见分表） ——
    private static final long INTERLUDE_THRESHOLD_MS = 4000L;
    private static final long INTERLUDE_PRE_OFFSET_MS = 250L;
    // —— 段落标签（M-05，B 档拟映射：字号 10sp、底部 3dp、颜色＝非当前行色×0.75；
    // 待真机目视验证后再定死，见分表） ——
    private static final float PART_TEXT_SP = 10f;
    private static final float PART_BOTTOM_DP = 3f;
    private static final float PART_COLOR_ALPHA = 0.75f;
    /* 歌词时间偏移（U-05）：读 MusicStore.getLyricOffsetMs()（面板路键 music_lyric_offset_ms，
     * 毫秒整数，默认 0，面板路钳制 ±5000ms）；本面只读值，不建设置 UI、不写键。 */

    /** 行点击回调（seek 用）。 */
    public interface OnLineClickListener {
        void onLineClick(int index, long positionMs);
    }

    private final Paint mainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 副行光晕画笔（L2：副行复用主行 drawWord 路径时光晕字号必须跟副行 0.62，不能拿主行 glowPaint）。 */
    private final Paint subGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 段落标签画笔（M-05：10sp，颜色见 applySchemeColors）。 */
    private final Paint partLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 间奏圆点画笔（M-04：颜色取已唱色，逐颗设 alpha）。 */
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Float, BlurMaskFilter> blurCache = new HashMap<>();
    /** API≥31 模糊缓存：按量化档 E 缓存 RenderEffect（GZ 应用侧同口径）；blurRetryAfterMs 为失败冷却截止时刻。 */
    private final Map<Integer, RenderEffect> blurEffectCache = new HashMap<>();
    private long blurRetryAfterMs;
    /** 空态三态（默认 STATE_LOADING：取词未定前什么都不画）。 */
    private int emptyState = STATE_LOADING;
    /** 第三波 A4：无词空态自定义文案（空＝默认“暂无歌词”）；区分“本源无词／兜底也无词”。 */
    private String noLyricText = null;
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            framePosted = false;
            stepFrame();
        }
    };

    private List<LyricsLine> lines = Collections.emptyList();
    private List<String> translations;
    private List<String> romanizations;
    /** 翻译／音译逐词表（M-02：与 lines 按下标对齐，空表＝无逐词）。 */
    private List<List<LyricsWord>> translationWordLists;
    private List<List<LyricsWord>> romanizationWordLists;
    private int primaryMode; // 0=原文 1=翻译 2=音译 作主行
    /** 逐字显示开关（歌词页「逐字」胶囊切换）：开＝逐字分段渲染（上浮/光晕/逐字变色），关＝整行渲染路径。 */
    private boolean wordByWord = true;
    private boolean showTranslation = true;
    private boolean showRomanization = true;
    private OnLineClickListener lineClickListener;

    /** 歌词区空白处点按回调（点行未命中时触发；流光 CD 展开／收起用，点行 seek 不受影响）。 */
    public interface OnEmptyTapListener {
        void onEmptyTap();
    }

    private OnEmptyTapListener emptyTapListener;
    /** 手动搜索歌词进行中（redo3 N-19：歌词区内嵌「搜索在线歌词中...」12sp 提示，取词回落后清除）。 */
    private boolean searchingLyric;

    // 歌词样式（设置面板／播放页可调，GZ LyricStyle 口径）
    private boolean coverMode;
    private float mainTextSizeSp = MAIN_SP_FULLSCREEN;
    private int lyricAlign; // 0左 1中 2右
    private int lyricWeight; // 0标准 1加粗 2特粗
    private int blurLevel = 2; // 0关 1轻(×0.45) 2中(×1)；缺省 2 保持现行观感
    private boolean advancedFx = true;
    private boolean lightScheme;

    private boolean playing;
    /** 被同窗页面（播放页内全屏歌词页）盖住时挂起帧循环：与 playing 解耦，恢复时按当前基准续推。 */
    private boolean suspended;
    private long basePositionMs;
    private long baseRealtimeMs;
    private int currentIndex = -1;

    // 弹簧滚动状态（偏移＝内容坐标，目标为当前行块顶对齐锚点）
    private float scrollOffset;
    private float scrollVelocity;
    private float scrollTarget;
    private long lastFrameMs;
    private boolean framePosted;
    private boolean scrollSnapped;
    private boolean seekSpringActive;

    // 浏览／拖动状态
    private boolean dragging;
    private boolean browsing;
    private final Runnable returnRunnable = () -> {
        browsing = false;
        // G12：暂停时帧循环不跑、scrollTarget 又等于拖动位置，旧版只置假＋ensureFrameLoop 直接返回，回锚不生效；
        // 暂停态直接按当前播放位置贴合当前行（不靠帧循环弹簧），播放态仍走帧循环落定。
        if (!playing) {
            currentIndex = lines.isEmpty() ? -1 : indexForPosition(displayPositionMs());
            if (currentIndex >= 0) scrollTarget = targetFor(currentIndex);
            scrollOffset = scrollTarget;
            scrollVelocity = 0f;
            seekSpringActive = false;
            invalidate();
        } else {
            ensureFrameLoop();
        }
    };
    private float downY;
    private float downX;
    private float dragStartOffset;
    private long downTimeMs;
    private OnTrackSwipeListener trackSwipeListener;
    private float lastMoveY;
    private long lastMoveTimeMs;
    /** 快甩速度末段采样（G9）：上一段 MOVE 的位置／时间，速度按末段算，不用整段平均。 */
    private float segMoveY;
    private long segMoveTimeMs;

    // 布局缓存
    private List<Block> blocks = Collections.emptyList();
    private float contentHeight;
    private int layoutWidth = -1;

    public KaraokeLyricsView(Context context) {
        this(context, null);
    }

    public KaraokeLyricsView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KaraokeLyricsView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        // API≥31 走 RenderEffect（需硬件加速层）；API＜31 的 BlurMaskFilter 需要软件层才稳定生效。
        setLayerType(Build.VERSION.SDK_INT >= 31 ? LAYER_TYPE_HARDWARE : LAYER_TYPE_SOFTWARE, null);
        initPaint(subPaint, ROMA_SP, SUB_COLOR, false);
        partLabelPaint.setTextSize(sp(PART_TEXT_SP));
        partLabelPaint.setTextSkewX(SKEW);
        partLabelPaint.setTypeface(Typeface.DEFAULT);
        dotPaint.setStyle(Paint.Style.FILL);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
        applyMainStyle();
        applySchemeColors();
        setClickable(true);
        setFocusable(true);
    }

    private void initPaint(Paint paint, float sizeSp, int color, boolean bold) {
        paint.setColor(color);
        paint.setTextSize(sp(sizeSp));
        paint.setTextSkewX(SKEW);
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    /** 主行／光晕画笔样式：字号（setMainTextSizeSp／setCoverMode 基线）＋粗细（setLyricWeight）。
     * 全屏字距按 GZ -0.2（Compose 侧以 sp 计，Paint 字距以 em 计，故除以字号换算；封面模式 GZ 未给字距值，保持默认 0）。 */
    private void applyMainStyle() {
        Typeface typeface = mainTypeface();
        float letterSpacing = (coverMode || mainTextSizeSp <= 0) ? 0f : -0.2f / mainTextSizeSp;
        for (Paint paint : new Paint[]{mainPaint, glowPaint}) {
            paint.setTextSize(mainTextSizePx());
            paint.setTextSkewX(SKEW);
            paint.setTypeface(typeface);
            paint.setLetterSpacing(letterSpacing);
            // API 28 以下无字重 API，特粗／加粗用 fakeBold 叠加近似；28+ 已由字重表达，不再叠。
            paint.setFakeBoldText(lyricWeight >= 1 && Build.VERSION.SDK_INT < 28);
        }
        glowPaint.setColor(ACTIVE_COLOR);
        applySubStyle();
    }

    /** 副行字号跟主行（U-02：副行＝主行×0.62，不再固定 15sp；主行改字号／切封面模式时经 applyMainStyle 跟着变）。 */
    private void applySubStyle() {
        subPaint.setTextSize(sp(mainTextSizeSp * SUB_RATIO));
        subGlowPaint.setTextSize(sp(mainTextSizeSp * SUB_RATIO));
        subGlowPaint.setTextSkewX(SKEW);
        subGlowPaint.setTypeface(Typeface.DEFAULT);
    }

    /**
     * 粗细映射（GZ q00 X4：标准 bold／加粗 extraBold／特粗 black）：
     * API 28+ 用 Typeface 字重 700／800／900 精确档；低版本退 DEFAULT_BOLD＋fakeBold 近似（注释口径：View 层近似）。
     */
    private Typeface mainTypeface() {
        if (Build.VERSION.SDK_INT >= 28) {
            int weight = lyricWeight == 1 ? 800 : (lyricWeight == 2 ? 900 : 700);
            return Typeface.create(Typeface.DEFAULT, weight, false);
        }
        return Typeface.DEFAULT_BOLD;
    }

    /** 深／浅色面颜色切换（浅色面配流光 CD record_shelf 白卡等浅底）。 */
    private void applySchemeColors() {
        mainPaint.setColor(unsungColor());
        subPaint.setColor(subColor());
        emptyPaint.setColor(lightScheme ? EMPTY_COLOR_LIGHT : EMPTY_COLOR);
        // M-05 拟映射：段落标签色＝非当前行色×0.75（alpha 乘 0.75，GZ g91.c(0.75, 基色)口径），待真机目视验证。
        partLabelPaint.setColor(idleBaseColor());
        partLabelPaint.setAlpha(Math.round(255f * PART_COLOR_ALPHA));
    }

    private float mainTextSizePx() {
        return sp(mainTextSizeSp);
    }

    private float blurMultiplier() {
        if (blurLevel == 0) return 0f;
        if (blurLevel == 1) {
            // 轻档＝瓜子值 0.45：映射 off 0／light 0.45／medium 1.0 在全屏 AppleLyricsList（LyricsViewKt:1317）
            // 与封面 CoverCurrentLyric（LyricsViewKt:4676）两处同值，故封面／全屏不再分档；
            // 旧封面轻档 0.6 系星辰现行值（当时封面映射未抠到），本处按瓜子值统一为 0.45。
            return 0.45f;
        }
        return 1f;
    }

    // ===== 对外 API =====

    /** 制作信息行前缀（瓜子歌词过滤口径）：作词／作曲／编曲等制作角色＋冒号／空格、OP／SP、
     * 英文 Lyrics by／Composed by 等、Music:／Lyrics: 这类冒号标注；只认行首前缀，歌词正文不受影响
     * （如“演唱会”后跟的不是冒号／空格，就不算；“词／曲”只认冒号形，不认空格形）。 */
    private static final Pattern LYRIC_META_PATTERN = Pattern.compile(
            "^\\[?((作词|作曲|编曲|制作人|出品人|监制|混音|母带|录音|缩混|吉他|贝斯|鼓|键盘|和声|和音|演唱|OP|SP)[:：︰\\s]"
                    + "|(词|曲)[:：︰]"
                    + "|(lyrics? by|composed by|arranged by|produced by|mixed by|mastered by)\\b"
                    + "|(lyrics?|music|written|arranged|composed|produced)\\s*[:：])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static boolean isLyricMetaLine(String text) {
        if (text == null) return false;
        String t = text.trim();
        return !t.isEmpty() && LYRIC_META_PATTERN.matcher(t).find();
    }

    /**
     * 过滤词曲信息（瓜子歌词过滤，设置页开关，默认关＝现行行为）：setLines 入口过滤一次，
     * 主行与翻译／音译／逐词表同下标一起筛；任一非空辅表长度与主行对不上就整批不过滤
     * （宁可全留，不错位）。开关关着时本方法不做任何事；开关开着、切歌重设行时重新过滤。
     */
    private void filterMetaLines() {
        boolean on;
        try {
            on = MusicStore.isFilterLyricMeta();
        } catch (Exception e) {
            on = false;
        }
        if (!on || lines == null || lines.isEmpty()) return;
        int n = lines.size();
        boolean aligned = (translations == null || translations.isEmpty() || translations.size() == n)
                && (romanizations == null || romanizations.isEmpty() || romanizations.size() == n)
                && (translationWordLists == null || translationWordLists.isEmpty() || translationWordLists.size() == n)
                && (romanizationWordLists == null || romanizationWordLists.isEmpty() || romanizationWordLists.size() == n);
        if (!aligned) return;
        boolean[] kept = new boolean[n];
        int keptCount = 0;
        for (int i = 0; i < n; i++) {
            kept[i] = !isLyricMetaLine(lines.get(i).getText());
            if (kept[i]) keptCount++;
        }
        if (keptCount == n) return;
        List<LyricsLine> fl = new ArrayList<>(keptCount);
        List<String> ft = translations == null ? null : new ArrayList<>(keptCount);
        List<String> fr = romanizations == null ? null : new ArrayList<>(keptCount);
        List<List<LyricsWord>> ftw = translationWordLists == null ? null : new ArrayList<>(keptCount);
        List<List<LyricsWord>> frw = romanizationWordLists == null ? null : new ArrayList<>(keptCount);
        for (int i = 0; i < n; i++) {
            if (!kept[i]) continue;
            fl.add(lines.get(i));
            if (ft != null && !translations.isEmpty()) ft.add(translations.get(i));
            if (fr != null && !romanizations.isEmpty()) fr.add(romanizations.get(i));
            if (ftw != null && !translationWordLists.isEmpty()) ftw.add(translationWordLists.get(i));
            if (frw != null && !romanizationWordLists.isEmpty()) frw.add(romanizationWordLists.get(i));
        }
        lines = fl;
        translations = ft;
        romanizations = fr;
        translationWordLists = ftw;
        romanizationWordLists = frw;
    }

    /** 输入歌词：translations／romanizations 与 lines 按下标对齐，可传 null。 */
    public void setLines(List<LyricsLine> lines, List<String> translations, List<String> romanizations) {
        setLines(lines, translations, romanizations, null, null);
    }

    /** 输入歌词（含逐词表，M-02）：translationWords／romanizationWords 与 lines 按下标对齐，可传 null。 */
    public void setLines(List<LyricsLine> lines, List<String> translations, List<String> romanizations,
                         List<List<LyricsWord>> translationWords, List<List<LyricsWord>> romanizationWords) {
        searchingLyric = false;
        // G8：换词即清浏览态——旧版 setLines 不清 browsing/dragging、不撤 5 秒回锚定时，
        // 关页／重开后跟唱冻结在旧行约 5 秒。
        browsing = false;
        dragging = false;
        removeCallbacks(returnRunnable);
        this.lines = lines == null ? Collections.emptyList() : new ArrayList<>(lines);
        this.translations = translations == null ? null : new ArrayList<>(translations);
        this.romanizations = romanizations == null ? null : new ArrayList<>(romanizations);
        this.translationWordLists = translationWords;
        this.romanizationWordLists = romanizationWords;
        filterMetaLines();
        currentIndex = -1;
        scrollSnapped = false;
        layoutWidth = -1;
        rebuildLayout();
        if (!this.lines.isEmpty()) {
            currentIndex = indexForPosition(displayPositionMs());
            snapScrollToCurrent();
        }
        ensureFrameLoop();
        invalidate();
    }

    /** 当前行锚点 Y（U-01：默认 top 档＝视图顶）。 */
    private float anchorY() {
        return 0f;
    }

    /** 歌词时间偏移（U-05：毫秒整数，默认 0；读 MusicStore 统一口径，读失败回 0）。 */
    private long lyricOffsetMs() {
        try {
            return MusicStore.getLyricOffsetMs();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 查找用歌词位置＝播放位置 ＋ offset（第三波 L1：与瓜子一致，正偏移＝歌词提前显示）。 */
    private long lyricPosition(long playbackPositionMs) {
        return playbackPositionMs + lyricOffsetMs();
    }

    /** 当前行匹配入口（U-05＋M-04）：先按偏移换算歌词位置，再判间奏——首行前间奏内不推进当前行（返回 -1，onDraw 以圆点占位），其余走 findLine。 */
    private int indexForPosition(long playbackPositionMs) {
        if (lines.isEmpty()) return -1;
        long pos = lyricPosition(playbackPositionMs);
        Interlude interlude = detectInterlude(lines, pos);
        if (interlude != null && interlude.nextIndex == 0) return -1;
        return LyricsParser.findLine(lines, pos);
    }

    /** 偏移变化后立即按新偏移重算当前行（U-05：面板路写值后调此方法，不用等切歌；未播放时直接贴合新当前行）。 */
    public void refreshLyricOffset() {
        if (lines.isEmpty()) return;
        currentIndex = indexForPosition(displayPositionMs());
        if (currentIndex >= 0) scrollTarget = targetFor(currentIndex);
        if (!playing) {
            scrollOffset = scrollTarget;
            scrollVelocity = 0f;
        }
        invalidate();
    }

    // —— 空态三态接口（线 C 按此接线，方法名已定）：有词走 setLines，此三态只管无词时画什么 ——

    /** 取词未定：什么都不画（默认态）。 */
    public void showLoading() {
        searchingLyric = false;
        emptyState = STATE_LOADING;
        invalidate();
    }

    /**
     * 空态字样主题覆盖（redo3 第三轮 S8／V9）：唱片架空态 16sp／行高 25sp／无图标／ShelfMuted 色
     *（RecordShelfPlayerKt:840-875）；封面卡片展开态空态 14sp／无图标／白 0.65（PlayerScreenKt:9062）。
     * textColor 传 0 表示不覆盖颜色（走非当前行色）；lineHeightSp 传 0 表示不按行高定行盒。
     * 仅作用于无词／等待两态的空态绘制，搜索中内嵌文案（N-19 全局 12sp 口径）与加载态（什么都不画）不动。
     */
    public void setEmptyTextStyle(float textSp, float lineHeightSp, boolean textOnly, int textColor) {
        emptyTextSp = textSp;
        emptyLineHeightSp = lineHeightSp;
        emptyTextOnly = textOnly;
        emptyTextColor = textColor;
        invalidate();
    }

    /** 已确认无词：显示低调空态（图标 40dp／alpha 0.5、「暂无歌词」12sp，GZ 参数表第 3 节）。 */
    public void showNoLyric() {
        showNoLyric(null);
    }

    /** 已确认无词＋自定义文案：文案空时回默认“暂无歌词”。 */
    public void showNoLyric(String text) {
        searchingLyric = false;
        emptyState = STATE_NO_LYRIC;
        noLyricText = text;
        invalidate();
    }

    /** 无曲目：等待播放态（图标 alpha 0.3、「等待播放」alpha 0.4、12sp，GZ 参数表第 3 节）。 */
    public void showWaiting() {
        searchingLyric = false;
        emptyState = STATE_WAITING;
        invalidate();
    }

    /** 手动搜索歌词进行中：歌词区内嵌提示（见 onDraw 搜索态叠加绘制）。 */
    public void showSearching() {
        searchingLyric = true;
        invalidate();
    }

    public void setOnEmptyTapListener(OnEmptyTapListener listener) {
        emptyTapListener = listener;
    }

    /** 行高（GZ coverLyricLineHeight，LyricsViewKt:10901-10903）：行高＝字号×4/3。仅封面模式用此公式。 */
    static float lyricLineHeight(float textSizePx) {
        return textSizePx * 4f / 3f;
    }

    /** 全屏行高系数（GZ 全屏歌词样式，LyricsViewKt lyricTextStyle 与 LineHeightsKey 均为字号×1.3，
     * 不是封面的 ×4/3；24sp 时全屏行高 31.2sp、封面 32sp，两档不可混用）。 */
    private static final float FULLSCREEN_LINE_HEIGHT_RATIO = 1.3f;

    /** 按模式取主行行高：封面走 ×4/3，全屏走 ×1.3。 */
    private float lineHeightFor(float textSizePx) {
        return coverMode ? lyricLineHeight(textSizePx) : textSizePx * FULLSCREEN_LINE_HEIGHT_RATIO;
    }

    /** 封面歌词槽位高（GZ coverLyricSlotHeight，LyricsViewKt:10909-10911）：＝COVER_LINE_GAP×3＋行高×4。 */
    static float coverLyricSlotHeight(float textSizePx, float gapPx) {
        return gapPx * 3f + lyricLineHeight(textSizePx) * 4f;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        // 封面模式高度未被宿主定死时，按 GZ 槽位高（20dp×3＋行高×4）作期望高。
        if (coverMode && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            int desired = Math.round(coverLyricSlotHeight(mainTextSizePx(), dp(LINE_GAP_COVER_DP)));
            int size = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST
                    ? Math.min(desired, MeasureSpec.getSize(heightMeasureSpec)) : desired;
            setMeasuredDimension(getMeasuredWidth(), size);
        }
    }

    /**
     * 逐字显示开关（新增公开方法，不改既有签名）：关时布局层不再按词分段、
     * 绘制层当前行走整行提亮路径（drawWholeLineSegment），是真切换渲染路径而非仅改标记。
     */
    public void setWordByWord(boolean enabled) {
        if (wordByWord == enabled) return;
        wordByWord = enabled;
        layoutWidth = -1;
        rebuildLayout();
        snapScrollToCurrent();
        invalidate();
    }

    public boolean isWordByWord() {
        return wordByWord;
    }

    /** 0=原文作主行、1=翻译作主行、2=音译作主行（作主行时原主行退为副行）。 */
    public void setPrimary(int mode) {
        if (mode < 0 || mode > 2 || mode == primaryMode) return;
        primaryMode = mode;
        layoutWidth = -1;
        rebuildLayout();
        snapScrollToCurrent();
        invalidate();
    }

    /** 控制副行（译／音）显隐；被选作主行的那路不再重复作副行。 */
    public void setShowSub(boolean translation, boolean romanization) {
        if (showTranslation == translation && showRomanization == romanization) return;
        showTranslation = translation;
        showRomanization = romanization;
        layoutWidth = -1;
        rebuildLayout();
        snapScrollToCurrent();
        invalidate();
    }

    // —— GZ 对齐新增 setter（播放页／歌词页／工具面板调用） ——

    /**
     * 封面模式开关：切基线字号（封面 22sp／全屏 24sp）、行距（20dp／6dp）、
     * 渲染窗口（前 2 后 6／前 3 后 15）、当前行折行上限（2／6）与位置弹簧（×1.1＋ζ1.08／基值＋ζ1.16）。
     * 字号基线在此定，之后 setMainTextSizeSp 的调用覆盖基线；再切模式会回到基线。
     */
    public void setCoverMode(boolean cover) {
        coverMode = cover;
        mainTextSizeSp = cover ? MAIN_SP_COVER : MAIN_SP_FULLSCREEN;
        applyMainStyle();
        layoutWidth = -1;
        rebuildLayout();
        snapScrollToCurrent();
        invalidate();
    }

    /** 主行字号（sp）：按 coverMode 分钳制——封面 14–32、全屏 16–40（B7：此前无条件 16–40，
     * 封面滑杆存 14/15sp 时渲染被抬到 16，滑杆与字号不一致）；覆盖 setCoverMode 定下的基线。 */
    public void setMainTextSizeSp(float sizeSp) {
        float min = coverMode ? LYRIC_SIZE_MIN_COVER : LYRIC_SIZE_MIN;
        float max = coverMode ? LYRIC_SIZE_MAX_COVER : LYRIC_SIZE_MAX;
        float clamped = Math.max(min, Math.min(max, sizeSp));
        if (clamped == mainTextSizeSp) return;
        mainTextSizeSp = clamped;
        applyMainStyle();
        layoutWidth = -1;
        rebuildLayout();
        snapScrollToCurrent();
        invalidate();
    }

    /** 主行对齐：0 左 1 中 2 右（GZ 歌词设置对齐段控）。 */
    public void setLyricAlign(int align) {
        int clamped = Math.max(0, Math.min(2, align));
        if (clamped == lyricAlign) return;
        lyricAlign = clamped;
        invalidate();
    }

    /** 主行粗细：0 标准（bold）1 加粗（extraBold 近似）2 特粗（black 近似），实现见 mainTypeface 注释。 */
    public void setLyricWeight(int weight) {
        int clamped = Math.max(0, Math.min(2, weight));
        if (clamped == lyricWeight) return;
        lyricWeight = clamped;
        applyMainStyle();
        layoutWidth = -1;
        rebuildLayout();
        snapScrollToCurrent();
        invalidate();
    }

    /** 非当前行模糊档：0 关 1 轻（系数 ×0.45）2 中（系数 ×1）。 */
    public void setBlurLevel(int level) {
        int clamped = Math.max(0, Math.min(2, level));
        if (clamped == blurLevel) return;
        blurLevel = clamped;
        invalidate();
    }

    /** 高级动效开关：关时逐字上浮与长音光晕全关，只保留逐字变色与行滚动（GZ 高级动效行口径）。 */
    public void setAdvancedFx(boolean enabled) {
        if (advancedFx == enabled) return;
        advancedFx = enabled;
        invalidate();
    }

    /** 浅色面：非当前行／未唱色改深灰系、副行转灰，主行已唱保持高亮金（配 record_shelf 白卡等浅底）。 */
    /**
     * 唱片架专属调色（瓜子 RecordShelfPlayerKt:266,335 的 Color＋alpha 模型，非 hex 写死）：
     * sung＝封面语气色向 ShelfInk 混 0.65、activeUnsung＝sung×0.48、inactive＝ShelfMuted×0.72、
     * translation＝ShelfMuted。只由唱片架宿主经 setShelfLyricPalette 传入；未设置时全部回落
     * 原深／浅色面值，其他主题行为不动。注意：COLOR_INACTIVE（#9A9AA0）／COLOR_TRANSLATION
     * （#8A8A90）两常量保留给通用浅色面，唱片架路径不再取它们。
     */
    private static final int SHELF_INK = 0xFF252522;
    private static final int SHELF_MUTED = 0xFF86857E;
    private boolean shelfPaletteSet;
    private int shelfSung;
    private int shelfActiveUnsung;
    private int shelfInactive;
    private int shelfTranslation;

    /** 唱片架宿主在封面取色就绪后调用（与进度填充同源：封面语气色 dominantColor）。 */
    public void setShelfLyricPalette(int toneColor) {
        int sung = blendOpaque(toneColor, SHELF_INK, 0.65f);
        shelfSung = sung;
        shelfActiveUnsung = (sung & 0x00FFFFFF) | (Math.round(255f * 0.48f) << 24);
        shelfInactive = (SHELF_MUTED & 0x00FFFFFF) | (Math.round(255f * 0.72f) << 24);
        shelfTranslation = SHELF_MUTED;
        shelfPaletteSet = true;
        applySchemeColors();
        invalidate();
    }

    private static int blendOpaque(int from, int to, float ratio) {
        int r = (int) (android.graphics.Color.red(from)
                + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * ratio);
        int g = (int) (android.graphics.Color.green(from)
                + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * ratio);
        int b = (int) (android.graphics.Color.blue(from)
                + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * ratio);
        return android.graphics.Color.rgb(r, g, b);
    }

    /** 已唱色按面取：唱片架调色优先，深色面白（本体实测）、浅色面 GZ 红。 */
    private int activeColor() {
        if (shelfPaletteSet) return shelfSung;
        return lightScheme ? COLOR_SUNG : ACTIVE_COLOR;
    }

    /** 当前行未唱色：唱片架调色优先，否则深／浅色面原值。 */
    private int unsungColor() {
        if (shelfPaletteSet) return shelfActiveUnsung;
        return lightScheme ? MAIN_COLOR_LIGHT : MAIN_COLOR;
    }

    /** 非当前行基色（空态／搜索态／段落标签）：唱片架调色优先，否则深／浅色面原值。 */
    private int idleBaseColor() {
        if (shelfPaletteSet) return shelfInactive;
        return lightScheme ? IDLE_COLOR_LIGHT : IDLE_COLOR;
    }

    /** 副行（翻译）色：唱片架调色优先，否则深／浅色面原值。 */
    private int subColor() {
        if (shelfPaletteSet) return shelfTranslation;
        return lightScheme ? SUB_COLOR_LIGHT : SUB_COLOR;
    }

    /** 非当前行主行色（含深色面白×0.30 分支）：唱片架调色优先，否则原值。 */
    private int idleMainColor() {
        if (shelfPaletteSet) return shelfInactive;
        return lightScheme ? IDLE_COLOR_LIGHT : 0x4DFFFFFF;
    }

    public void setLightScheme(boolean light) {
        if (lightScheme == light) return;
        lightScheme = light;
        applySchemeColors();
        invalidate();
    }

    public void setOnLineClickListener(OnLineClickListener listener) {
        this.lineClickListener = listener;
    }

    /** 全屏歌词页竖滑切歌回调：快甩（见 onTouchEvent 的切歌门）时由宿主切上一曲／下一曲；慢拖仍是歌词滚动。
     * 内嵌（coverMode）不再走这条：竖直滑动归整页容器（PlayerPageSwipeLayout），歌词不滚不动锚。 */
    public interface OnTrackSwipeListener {
        void onTrackSwipe(boolean next);
    }

    public void setOnTrackSwipeListener(OnTrackSwipeListener listener) {
        this.trackSwipeListener = listener;
    }

    public void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        // 冻结／续推的基准对齐：暂停时把当前推算位置固化为基准。
        long now = SystemClock.elapsedRealtime();
        if (!playing) basePositionMs = displayPositionMs();
        this.playing = playing;
        baseRealtimeMs = now;
        if (playing) ensureFrameLoop();
        invalidate();
    }

    /** 外部播放进度喂入：以该值＋流逝时间在帧循环内推算当前播放位置。 */
    public void updatePosition(long positionMs) {
        // GZ 平滑时间回退容忍 350ms：新锚点比当前平滑位置落后 ≤350ms 时锚点不动（保持单调），超过才跟随
        long newPos = Math.max(0, positionMs);
        boolean tolerate = false;
        if (playing) {
            long cur = displayPositionMs();
            tolerate = newPos < cur && cur - newPos <= 350;
        }
        if (!tolerate) {
            basePositionMs = newPos;
            baseRealtimeMs = SystemClock.elapsedRealtime();
        }
        if (!browsing) {
            int next = lines.isEmpty() ? -1 : indexForPosition(displayPositionMs());
            if (next != currentIndex) {
                currentIndex = next;
            }
        }
        ensureFrameLoop();
        invalidate();
    }

    /** Seek 后调用：同步进度并用点行 seek 弹簧（刚度 100、阻尼比 0.833）滑向当前行。 */
    public void seekUi(long posMs) {
        basePositionMs = Math.max(0, posMs);
        baseRealtimeMs = SystemClock.elapsedRealtime();
        browsing = false;
        removeCallbacks(returnRunnable);
        currentIndex = lines.isEmpty() ? -1 : indexForPosition(Math.max(0, posMs));
        seekSpringActive = true;
        scrollTarget = targetFor(currentIndex);
        ensureFrameLoop();
        invalidate();
    }

    public int currentLine() {
        return currentIndex;
    }

    // ===== 帧循环与弹簧 =====

    private long displayPositionMs() {
        if (!playing) return basePositionMs;
        // GZ rememberSmoothTime 口径：外推上限＝锚点 +1000ms（防 tick 迟到时平滑时间跑飞）
        return Math.min(basePositionMs + 1000,
                basePositionMs + Math.max(0, SystemClock.elapsedRealtime() - baseRealtimeMs));
    }

    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
        if (!suspended) ensureFrameLoop();
    }

    private void ensureFrameLoop() {
        if (suspended) return;
        if (framePosted) return;
        if (!playing && !needsSettle()) return;
        if (lines.isEmpty()) return;
        framePosted = true;
        lastFrameMs = SystemClock.elapsedRealtime();
        choreographer.postFrameCallback(frameCallback);
    }

    private boolean needsSettle() {
        return Math.abs(scrollTarget - scrollOffset) > 0.5f || Math.abs(scrollVelocity) > 0.5f;
    }

    private void stepFrame() {
        // 窗口不可见（被其它 Activity/页面盖住）时停帧：不重绘、不再排帧，
        // 回到前台由 onWindowVisibilityChanged 重新拉起（防双页同跑时后台页空转）。
        if (getWindowVisibility() != VISIBLE || !isShown()) {
            framePosted = false;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long frameMs = lastFrameMs == 0 ? 16 : now - lastFrameMs;
        lastFrameMs = now;
        float dt = Math.max(1, Math.min(50, frameMs)) / 1000f;

        if (!browsing && !dragging && !lines.isEmpty()) {
            int next = indexForPosition(displayPositionMs());
            if (next != currentIndex) currentIndex = next;
            // 无当前行（间奏前／无时间轴）不碰 scrollTarget：间奏初态本就是 0，无时间轴时用户手滑位置不被回锚拽回顶
            if (currentIndex >= 0) scrollTarget = targetFor(currentIndex);
            stepSpring(dt);
        }
        invalidate();
        if (!suspended && (playing || needsSettle())) {
            framePosted = true;
            choreographer.postFrameCallback(frameCallback);
        }
    }

    /**
     * 刚度基值（GZ amllPosYComposeStiffness 全公式）：
     * stiffness(delta)＝((1−((clamp(delta,100,800)−100)/700)^0.2)×50＋170)/0.9；
     * 缺省 delta＝450 代入：(0.5^0.2×50＋170)/0.9 ≈ 237.25（离线 harness 逐点核过）。
     * 不再按帧间隔换算（旧「帧间隔 ×10」已删），滚动弹簧一律走缺省基值，规格切换见 stepSpring。
     */
    static double stiffnessForDelta(long deltaMs) {
        double clamped = Math.max(100, Math.min(800, deltaMs));
        double normalized = (clamped - 100d) / 700d;
        return (Math.pow(1d - normalized, 0.2d) * 50d + 170d) / 0.9d;
    }

    /**
     * 标准阻尼弹簧积分（半隐式欧拉，dt 已钳 50ms）：
     * a＝k(target−x)−2ζ√k·v；规格三选一——点行 seek（k＝100、ζ＝0.833）优先，
     * 其次封面位置弹簧（k＝基值×1.1、ζ＝1.08），否则全屏落定弹簧（k＝基值、ζ＝1.16）。
     */
    private void stepSpring(float dt) {
        double base = stiffnessForDelta(SPRING_DELTA_DEFAULT_MS);
        double stiffness;
        double dampingRatio;
        if (seekSpringActive) {
            stiffness = SEEK_STIFFNESS;
            dampingRatio = SEEK_DAMPING_RATIO;
        } else if (coverMode) {
            stiffness = base * COVER_STIFFNESS_MUL;
            dampingRatio = COVER_DAMPING_RATIO;
        } else {
            stiffness = base;
            dampingRatio = SETTLE_DAMPING_RATIO;
        }
        double damping = 2d * dampingRatio * Math.sqrt(stiffness);
        double accel = stiffness * (scrollTarget - scrollOffset) - damping * scrollVelocity;
        scrollVelocity += (float) (accel * dt);
        scrollOffset += scrollVelocity * dt;
        if (Math.abs(scrollTarget - scrollOffset) < 0.25f && Math.abs(scrollVelocity) < 0.25f) {
            scrollOffset = scrollTarget;
            scrollVelocity = 0f;
            seekSpringActive = false;
        }
    }

    private float targetFor(int index) {
        if (index < 0 || index >= blocks.size()) return 0f;
        // 目标：当前行块顶 + 主行基线偏移对齐锚点，换算成「块顶在内容坐标」即块顶本身。
        return blocks.get(index).top;
    }

    private void snapScrollToCurrent() {
        scrollTarget = targetFor(currentIndex);
        scrollOffset = scrollTarget;
        scrollVelocity = 0f;
        scrollSnapped = true;
        seekSpringActive = false;
    }

    // ===== 缓动工具（GZ 口径） =====

    /** 三次贝塞尔缓动求值：二分 12 次解 x(t)＝x 再取 y(t)（与 GZ cubicBezier／easeOut 解法同构）。 */
    static float cubicBezier(float x1, float y1, float x2, float y2, float x) {
        float input = clampF(x, 0f, 1f);
        if (input <= 0f) return 0f;
        if (input >= 1f) return 1f;
        float lo = 0f;
        float hi = 1f;
        for (int i = 0; i < 12; i++) {
            float mid = (lo + hi) * 0.5f;
            if (bezierAxis(x1, x2, mid) < input) lo = mid;
            else hi = mid;
        }
        return bezierAxis(y1, y2, (lo + hi) * 0.5f);
    }

    private static float bezierAxis(float p1, float p2, float t) {
        float u = 1f - t;
        return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t;
    }

    /** GZ easeOut＝cubicBezier(0, 0, 0.58, 1)（LyricsViewKt easeOut／easeOut$bez 实证），逐字上浮进度用。 */
    static float easeOut(float x) {
        return cubicBezier(0f, 0f, 0.58f, 1f, x);
    }

    /** GZ empEasing：前半 cubicBezier(0.2,0.4,0.58,1)、后半 1−cubicBezier(0.3,0,0.58,1)，呈升后降的强调包络。 */
    static float empEasing(float x) {
        float v = clampF(x, 0f, 1f);
        if (v < 0.5f) return cubicBezier(0.2f, 0.4f, 0.58f, 1f, v / 0.5f);
        return 1f - cubicBezier(0.3f, 0f, 0.58f, 1f, (v - 0.5f) / 0.5f);
    }

    private static float smoothstep(float t) {
        float v = clampF(t, 0f, 1f);
        return (3f - 2f * v) * v * v;
    }

    /** GZ bottomFadeKeep：五点曲线（x 0/0.3/0.55/0.78/1 → y 1/0.85/0.62/0.48/0.45），段内 smoothstep 插值。 */
    static float bottomFadeKeep(float f) {
        float v = clampF(f, 0f, 1f);
        for (int i = 0; i < 4; i++) {
            if (v <= FADE_X[i + 1]) {
                float t = (v - FADE_X[i]) / (FADE_X[i + 1] - FADE_X[i]);
                float s = (3f - 2f * t) * t * t;
                return FADE_Y[i] + (FADE_Y[i + 1] - FADE_Y[i]) * s;
            }
        }
        return FADE_Y[4];
    }

    private static float clampF(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ===== 布局：把每行量成一个块（主行可折行＋副行） =====

    private int currentMaxLines() {
        return coverMode ? CURRENT_MAX_LINES_COVER : CURRENT_MAX_LINES_FULLSCREEN;
    }

    private void rebuildLayout() {
        int width = getWidth();
        if (width <= 0) {
            blocks = Collections.emptyList();
            return;
        }
        if (layoutWidth == width && !blocks.isEmpty()) return;
        layoutWidth = width;
        float maxMainWidth = width - dp(H_PADDING_DP) * 2f;
        // 主行行高按模式分流：封面 ×4/3（coverLyricLineHeight），全屏 ×1.3（GZ 全屏样式，见 lineHeightFor）。
        float mainRowHeight = lineHeightFor(mainTextSizePx());
        // U-02：副行行高＝副行字号×4/3（GZ 全屏副行行高未单独抠到，暂留封面公式，列待核，不跟主行改 1.3）。
        float subRowHeight = lyricLineHeight(subPaint.getTextSize());
        float gapPx = dp(coverMode ? LINE_GAP_COVER_DP : LINE_GAP_FULLSCREEN_DP);
        List<Block> list = new ArrayList<>(lines.size());
        float top = 0f;
        for (int i = 0; i < lines.size(); i++) {
            Block block = new Block();
            block.index = i;
            block.top = top;
            block.mainRows = layoutMainRows(i, maxMainWidth);
            block.subRows = subRowsFor(i);
            block.partLabel = partLabelFor(i);
            block.labelHeight = block.partLabel.isEmpty() ? 0f : sp(PART_TEXT_SP) + dp(PART_BOTTOM_DP);
            // M-03：伴唱行主行行高按伴唱字号（基值×0.7）计，系数同样按模式分流。
            float blockMainRowHeight = lines.get(i).isBG() ? lineHeightFor(mainTextSizePx() * BG_FONT_RATIO) : mainRowHeight;
            block.height = block.mainRows.size() * blockMainRowHeight
                    + block.subRows.size() * subRowHeight
                    + block.labelHeight
                    + gapPx;
            block.mainRowHeight = blockMainRowHeight;
            block.subRowHeight = subRowHeight;
            list.add(block);
            top += block.height;
        }
        blocks = list;
        contentHeight = top;
        if (!scrollSnapped && currentIndex >= 0) snapScrollToCurrent();
    }

    /** 主行分段：有逐字词时按词分段折行；否则整行按宽度折行（仅量宽，绘制时整段处理）。
     * M-02：按词分段条件从 primaryMode==0 放宽为三档通用（各档取对应词表，作主行的那路为空时回落原文词）。
     * M-03：伴唱行量宽按伴唱字号（基值×0.7）缩放。 */
    private List<List<Seg>> layoutMainRows(int index, float maxWidth) {
        List<List<Seg>> rows = new ArrayList<>();
        String mainText = mainTextFor(index);
        if (TextUtils.isEmpty(mainText)) {
            rows.add(new ArrayList<>());
            return rows;
        }
        LyricsLine line = lines.get(index);
        List<LyricsWord> mainWords = wordsForMain(index, line);
        boolean wordMode = wordByWord && !mainWords.isEmpty();
        List<Seg> segs = new ArrayList<>();
        if (wordMode) {
            for (LyricsWord word : mainWords) {
                if (TextUtils.isEmpty(word.getText())) continue;
                // L3：布局层按字符拆 Seg（按码点拆，emoji 等代理对不拆半个字符）。
                // 字符切片时长按字宽加权分原词时长（GZ measureLine charTimings 口径：字内时长
                // ∝该字字形宽，总宽 0 时退回均分；末字符吃整数余数），变色按切片算，
                // 光晕／缩放量仍按原词时长算（见 Seg 注释）。均分是旧错：宽字与窄字同分
                // 一份时长，扫边速度随字宽忽快忽慢，正是逐字跟唱“生硬”的根；按宽加权后
                // 扫边速度（px／ms）在词内恒定，与瓜子 sungCharsForTime 字内线性插值同效。
                // 中文词表本身已是单字时拆后与整词一致，差距被掩盖属正常。
                String wtext = word.getText();
                int count = wtext.codePointCount(0, wtext.length());
                if (count <= 1) {
                    float width = mainPaint.measureText(wtext);
                    if (line.isBG()) width *= BG_FONT_RATIO;
                    segs.add(new Seg(wtext, word, width));
                    continue;
                }
                long wordStart = word.getStartOffsetMs();
                long wordDur = word.getDurationMs();
                String[] chars = new String[count];
                float[] charWidths = new float[count];
                float totalWidth = 0f;
                int offset = 0;
                for (int ci = 0; ci < count; ci++) {
                    int cp = wtext.codePointAt(offset);
                    int charLen = Character.charCount(cp);
                    chars[ci] = wtext.substring(offset, offset + charLen);
                    offset += charLen;
                    charWidths[ci] = mainPaint.measureText(chars[ci]);
                    totalWidth += charWidths[ci];
                }
                long elapsed = 0L;
                for (int ci = 0; ci < count; ci++) {
                    float width = charWidths[ci];
                    if (line.isBG()) width *= BG_FONT_RATIO;
                    long charStart = wordStart + elapsed;
                    long charDur;
                    if (ci == count - 1) {
                        charDur = Math.max(0, wordDur - elapsed);
                    } else if (totalWidth > 0f) {
                        charDur = Math.round(wordDur * charWidths[ci] / totalWidth);
                    } else {
                        charDur = wordDur / count;
                    }
                    elapsed += charDur;
                    segs.add(new Seg(chars[ci], word, width, charStart, charDur, ci, count));
                }
            }
            if (segs.isEmpty()) wordMode = false;
        }
        if (!wordMode) {
            float width = mainPaint.measureText(mainText);
            if (line.isBG()) width *= BG_FONT_RATIO;
            segs.add(new Seg(mainText, null, width));
        }
        int maxRows = currentMaxLines();
        List<Seg> row = new ArrayList<>();
        float rowWidth = 0f;
        for (Seg seg : segs) {
            if (!row.isEmpty() && rowWidth + seg.width > maxWidth && rows.size() < maxRows) {
                rows.add(row);
                row = new ArrayList<>();
                rowWidth = 0f;
            }
            row.add(seg);
            rowWidth += seg.width;
        }
        if (!row.isEmpty() || rows.isEmpty()) rows.add(row);
        return rows;
    }

    private String mainTextFor(int index) {
        LyricsLine line = lines.get(index);
        if (primaryMode == 1) {
            String t = at(translations, index);
            if (!TextUtils.isEmpty(t)) return t;
        } else if (primaryMode == 2) {
            String t = at(romanizations, index);
            if (!TextUtils.isEmpty(t)) return t;
        }
        return line.getText();
    }

    /** 作主行的那路逐词表（M-02：1 档取翻译词表、2 档取音译词表；该路字符串为空时回落原文词，词空时返回空表）。 */
    private List<LyricsWord> wordsForMain(int index, LyricsLine line) {
        if (primaryMode == 1) {
            if (!TextUtils.isEmpty(at(translations, index))) return atWords(translationWordLists, index);
        } else if (primaryMode == 2) {
            if (!TextUtils.isEmpty(at(romanizations, index))) return atWords(romanizationWordLists, index);
        }
        return line.getWords();
    }

    private static List<LyricsWord> atWords(List<List<LyricsWord>> lists, int index) {
        if (lists == null || index < 0 || index >= lists.size() || lists.get(index) == null) return Collections.emptyList();
        return lists.get(index);
    }

    /** 段落标签文案（M-05）：仅本行 songPart 非空且与上一行不同时生成；空项处理后以 " / " 连接段落与演唱者。 */
    private String partLabelFor(int index) {
        LyricsLine line = lines.get(index);
        String part = line.getSongPart();
        if (part.isEmpty()) return "";
        if (index > 0 && part.equals(lines.get(index - 1).getSongPart())) return "";
        String display = songPartDisplay(part);
        if (display == null) return "";
        String agent = line.getAgentName();
        return agent.isEmpty() ? display : display + " / " + agent;
    }

    /** 段落文案映射（M-05，照瓜子 songPartDisplay 抄）：空／空白返回 null；去引号后，
     * C／Chorus→副歌，B／Bridge→桥段，I／Intro→前奏，O／Outro→尾奏，
     * V 前缀→主歌＋剩余字符，Verse 前缀返回原串（去引号后），其余未映射值原样返回。 */
    static String songPartDisplay(String part) {
        if (part == null) return null;
        String v = part.replace("\"", "").trim();
        if (v.isEmpty()) return null;
        if ("C".equals(v) || "Chorus".equals(v)) return "副歌";
        if ("B".equals(v) || "Bridge".equals(v)) return "桥段";
        if ("I".equals(v) || "Intro".equals(v)) return "前奏";
        if ("O".equals(v) || "Outro".equals(v)) return "尾奏";
        if (v.startsWith("Verse")) return v;
        if (v.startsWith("V")) return "主歌" + v.substring(1);
        return v;
    }

    /** 伴唱动画值（M-03，星辰侧映射）：当前伴唱行自本行行首起 300ms 内按 INTERP_FOSI
     *（PathInterpolator(0.2,0,0,1)，此处以同参数 cubicBezier 求值）从 0→1；非当前伴唱行取终态 1。 */
    private float bgValue(LyricsLine line, boolean isCurrent, long lyricPosMs) {
        if (!line.isBG() || !isCurrent) return 1f;
        float t = clampF((lyricPosMs - line.getTimeMs()) / (float) BG_ENTER_MS, 0f, 1f);
        return cubicBezier(0.2f, 0f, 0f, 1f, t);
    }

    /** 间奏区间（M-04）：nextIndex＝间奏后下一行下标（首行前间奏为 0），startMs／endMs 为区间起止（歌词时间轴）。 */
    private static final class Interlude {
        final int nextIndex;
        final long startMs;
        final long endMs;

        Interlude(int nextIndex, long startMs, long endMs) {
            this.nextIndex = nextIndex;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    /**
     * 间奏判定（M-04，照瓜子 detectInterlude 抄）：首行前要求首行 timeMs−250≥4000 且当前时间在首行前；
     * 行间要求 (下一行 timeMs−250)−上一行末尾≥4000 且当前时间落在该区间内。
     * 上一行末尾：有词行取末词 endMs；星辰 LyricsLine 无 durationMs 字段，词空行按 KLV 现有行兜底
     * LINE_FALLBACK_MS（4000ms）计末尾（即词空行间触发等价于行首间距≥8250ms，有词行间等价于
     * 下一行首−250−末词末≥4000，与报告“空隙至少 4250ms”为同一式子、不同末尾口径，见分表）。
     */
    static Interlude detectInterlude(List<LyricsLine> lines, long pos) {
        if (lines == null || lines.isEmpty()) return null;
        LyricsLine first = lines.get(0);
        if (pos < first.getTimeMs() && first.getTimeMs() - INTERLUDE_PRE_OFFSET_MS >= INTERLUDE_THRESHOLD_MS) {
            return new Interlude(0, 0, first.getTimeMs());
        }
        for (int i = 0; i < lines.size() - 1; i++) {
            LyricsLine prev = lines.get(i);
            LyricsLine next = lines.get(i + 1);
            long prevEnd;
            if (prev.hasWords()) {
                prevEnd = prev.getTimeMs();
                for (LyricsWord word : prev.getWords()) prevEnd = Math.max(prevEnd, prev.getTimeMs() + word.getEndOffsetMs());
            } else {
                prevEnd = prev.getTimeMs() + LINE_FALLBACK_MS;
            }
            if ((next.getTimeMs() - INTERLUDE_PRE_OFFSET_MS) - prevEnd >= INTERLUDE_THRESHOLD_MS
                    && pos >= prevEnd && pos < next.getTimeMs()) {
                return new Interlude(i + 1, prevEnd, next.getTimeMs());
            }
        }
        return null;
    }

    /** GZ easeOutExpo：淡入系数（已走＜2000ms 时乘此值）。 */
    static float easeOutExpo(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return 1f - (float) Math.pow(2, -10 * t);
    }

    /** GZ easeInOutBack（c1＝1.70158）：收尾系数（剩余＜750ms 时乘 1−easeInOutBack(((750−剩余)/750)/2)）。 */
    static float easeInOutBack(float t) {
        float v = clampF(t, 0f, 1f);
        float c1 = 1.70158f;
        float c2 = c1 * 1.70158f;
        if (v < 0.5f) return (float) (Math.pow(2 * v, 2) * ((c2 + 1) * 2 * v - c2) / 2);
        return (float) (Math.pow(2 * v - 2, 2) * ((c2 + 1) * (2 * v - 2) + c2) + 2) / 2;
    }

    /**
     * 间奏圆点（M-04，照瓜子 InterludeDots 公式抄）：固定 3 颗，颜色取已唱色，对齐默认居中（调用方传中心 x）。
     * 跳动：周期分段 ceil(总时长/1500)，正弦项 sin(4.712…−(已走/(总时长/段数))×2)/20＋1，半径系数再乘 0.7；
     * 淡入：已走＜2000ms 乘 easeOutExpo(已走/2000)，已走＜500ms 时 alpha 因子 0、500–1000ms 线性 ramp、之后 1；
     * 收尾：剩余＜750ms 乘 1−easeInOutBack(((750−剩余)/750)/2)，剩余＜375ms 时 alpha 因子再乘 剩余/375；
     * 三颗逐颗相位错开（每颗 alpha 用 (已走−i×(总时长−750)/3)×3/(总时长−750)×0.75 钳 0.25–1）。
     * 尺寸基准 f 为拟映射（当前主行字号 sp 值），待真机目视验证后再定死。
     */
    private void drawInterludeDots(Canvas canvas, float x, float y, long elapsed, long remaining) {
        float f = mainTextSizeSp;
        float diameter = dp(0.3f * f);
        float step = dp(0.4f * f);
        float totalWidth = dp(1.1f * f);
        long total = elapsed + remaining;
        if (total <= 0) total = 1;
        int segments = (int) Math.ceil(total / 1500.0);
        if (segments <= 0) segments = 1;
        float segDur = total / (float) segments;
        float bounce = (float) (Math.sin(4.71238898 - (elapsed / segDur) * 2.0) / 20.0 + 1.0);
        float scale = bounce * 0.7f;
        if (elapsed < 2000) scale *= easeOutExpo(elapsed / 2000f);
        if (remaining < 750) scale *= 1f - easeInOutBack(((750f - remaining) / 750f) / 2f);
        float alphaBase;
        if (elapsed < 500) alphaBase = 0f;
        else if (elapsed < 1000) alphaBase = (elapsed - 500) / 500f;
        else alphaBase = 1f;
        if (remaining < 375) alphaBase *= remaining / 375f;
        float startX = x - totalWidth / 2f + diameter / 2f;
        for (int i = 0; i < 3; i++) {
            float perDot = 1f;
            if (total > 750) {
                perDot = clampF((elapsed - i * (total - 750) / 3f) * 3f / (total - 750) * 0.75f, 0.25f, 1f);
            }
            dotPaint.setColor(activeColor());
            dotPaint.setAlpha(Math.round(255f * clampF(alphaBase * perDot, 0f, 1f)));
            float radius = diameter / 2f * scale;
            if (radius > 0f) canvas.drawCircle(startX + i * step, y, radius, dotPaint);
        }
        dotPaint.setAlpha(255);
    }

    private static float maxRowWidth(Block block) {
        float max = 0f;
        for (List<Seg> row : block.mainRows) max = Math.max(max, rowWidth(row));
        return max;
    }

    private List<SubRow> subRowsFor(int index) {
        List<SubRow> subs = new ArrayList<>(2);
        String original = lines.get(index).getText();
        String translation = at(translations, index);
        String romanization = at(romanizations, index);
        // 歌词路 2：主行缺数据回落原文时，副行不再把原文重复一遍（主行已是原文才不加原文副行）。
        boolean mainIsOriginal = (primaryMode == 1 && TextUtils.isEmpty(translation))
                || (primaryMode == 2 && TextUtils.isEmpty(romanization));
        // 原文被顶替作主行时退为第一副行（主行已是原文时不重复），词表取主行原文词。
        if (primaryMode != 0 && !mainIsOriginal && !TextUtils.isEmpty(original)) {
            subs.add(new SubRow(original, lines.get(index).getWords()));
        }
        if (primaryMode != 1 && showTranslation && !TextUtils.isEmpty(translation)) {
            subs.add(new SubRow(translation, atWords(translationWordLists, index)));
        }
        if (primaryMode != 2 && showRomanization && !TextUtils.isEmpty(romanization)) {
            subs.add(new SubRow(romanization, atWords(romanizationWordLists, index)));
        }
        return subs;
    }

    private static String at(List<String> list, int index) {
        if (list == null || index < 0 || index >= list.size()) return null;
        String value = list.get(index);
        return value == null ? null : value.trim();
    }

    // ===== 绘制 =====

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutWidth = -1;
        rebuildLayout();
        if (!scrollSnapped) snapScrollToCurrent();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (lines.isEmpty()) {
            drawEmptyState(canvas);
            return;
        }
        if (blocks.isEmpty()) return;
        // U-05：绘制用歌词位置同样按偏移换算（与当前行匹配同一口径）。
        long pos = lyricPosition(displayPositionMs());
        float anchorY = anchorY();
        float maxWidth = getWidth() - dp(H_PADDING_DP) * 2f;
        int windowBefore = coverMode ? WINDOW_BEFORE_COVER : WINDOW_BEFORE_FULLSCREEN;
        int windowAfter = coverMode ? WINDOW_AFTER_COVER : WINDOW_AFTER_FULLSCREEN;
        // M-04：间奏区间内，下一行位置画圆点占位（行间占位行，落码时定），不画该行正文。
        Interlude interlude = detectInterlude(lines, pos);
        for (Block block : blocks) {
            // 渲染窗口（GZ 窗口常量）：当前行前 before 行、后 after 行之外不画。
            if (currentIndex >= 0 && (block.index < currentIndex - windowBefore || block.index > currentIndex + windowAfter)) continue;
            float blockY = anchorY - scrollOffset + block.top;
            if (blockY + block.height < -dp(40) || blockY > getHeight() + dp(40)) continue;
            if (interlude != null && block.index == interlude.nextIndex) {
                drawInterludeDots(canvas, getWidth() / 2f, blockY + block.mainRowHeight * 0.5f,
                        pos - interlude.startMs, interlude.endMs - pos);
                continue;
            }
            int distance = currentIndex < 0 ? Math.abs(block.index) + 4 : Math.abs(block.index - currentIndex);
            boolean isCurrent = block.index == currentIndex;
            drawBlock(canvas, block, blockY, maxWidth, distance, isCurrent, pos, anchorY);
        }
        // 搜索态叠加（redo3 N-19）：手动搜词进行中时在歌词区居中嵌「搜索在线歌词中...」12sp、非当前行色；
        // 旧词仍在时亦叠加显示（瓜子内嵌文案形态），取词回落后由 setLines／showNoLyric 清除。
        if (searchingLyric) {
            int base = idleBaseColor();
            emptyPaint.setColor(base);
            emptyPaint.setTextSize(sp(EMPTY_TEXT_SP));
            emptyPaint.setAlpha(255);
            Paint.FontMetrics fm = emptyPaint.getFontMetrics();
            canvas.drawText("搜索在线歌词中...", getWidth() / 2f,
                    getHeight() / 2f - (fm.ascent + fm.descent) / 2f, emptyPaint);
            emptyPaint.setAlpha(255);
        }
    }

    /**
     * 空态绘制（GZ 参数表第 3 节）：STATE_LOADING 什么都不画；
     * 无歌词＝图标 40dp／alpha 0.5＋「暂无歌词」12sp（LyricsViewKt:5816-5817）；
     * 等待播放＝图标 alpha 0.3＋「等待播放」alpha 0.4、12sp（LyricsViewKt:6029-6030）。
     * GZ 图标为其矢量资源（xe2.q()，具体资产未追到），此处以「♪」字形等尺寸代绘，见小节未验清单。
     * 图标与字样的纵向间距 GZ 列装配未抠到确数，此处居中成组排布。
     */
    private void drawEmptyState(Canvas canvas) {
        if (searchingLyric) {
            int base = idleBaseColor();
            emptyPaint.setColor(base);
            emptyPaint.setTextSize(sp(EMPTY_TEXT_SP));
            emptyPaint.setAlpha(255);
            Paint.FontMetrics fm = emptyPaint.getFontMetrics();
            canvas.drawText("搜索在线歌词中...", getWidth() / 2f,
                    getHeight() / 2f - (fm.ascent + fm.descent) / 2f, emptyPaint);
            emptyPaint.setAlpha(255);
            return;
        }
        if (emptyState == STATE_LOADING) return;
        boolean waiting = emptyState == STATE_WAITING;
        String noLyricLabel = noLyricText == null || noLyricText.isEmpty() ? "暂无歌词" : noLyricText;
        int base = idleBaseColor();
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        if (emptyTextOnly) {
            // 主题覆盖空态（唱片架 16sp／行高 25sp、封面卡片展开态 14sp）：无图标、纯字样居中；
            // 行高作为单行行盒高参与垂直居中（多行换行时亦为行距，当前空态文案均为单行）。
            emptyPaint.setColor(emptyTextColor != 0 ? emptyTextColor : base);
            emptyPaint.setTextSize(sp(emptyTextSp));
            emptyPaint.setAlpha(waiting ? Math.round(255f * EMPTY_WAITING_TEXT_ALPHA) : 255);
            Paint.FontMetrics fm = emptyPaint.getFontMetrics();
            float lineBox = emptyLineHeightSp > 0 ? sp(emptyLineHeightSp) : (fm.descent - fm.ascent);
            float textBaseline = cy - lineBox / 2f - fm.ascent;
            canvas.drawText(waiting ? "等待播放" : noLyricLabel, cx, textBaseline, emptyPaint);
            emptyPaint.setAlpha(255);
            return;
        }
        emptyPaint.setColor(base);
        emptyPaint.setTextSize(dp(EMPTY_ICON_DP));
        emptyPaint.setAlpha(Math.round(255f * (waiting ? EMPTY_WAITING_ICON_ALPHA : EMPTY_NO_LYRIC_ICON_ALPHA)));
        Paint.FontMetrics iconFm = emptyPaint.getFontMetrics();
        float iconBaseline = cy - dp(12) - (iconFm.ascent + iconFm.descent) / 2f;
        canvas.drawText("♪", cx, iconBaseline, emptyPaint);
        emptyPaint.setTextSize(sp(EMPTY_TEXT_SP));
        emptyPaint.setAlpha(waiting ? Math.round(255f * EMPTY_WAITING_TEXT_ALPHA) : 255);
        canvas.drawText(waiting ? "等待播放" : noLyricLabel, cx, cy + dp(20), emptyPaint);
        emptyPaint.setAlpha(255);
    }

    private void drawBlock(Canvas canvas, Block block, float blockTopY, float maxWidth,
                           int distance, boolean isCurrent, long pos, float anchorY) {
        LyricsLine line = lines.get(block.index);
        boolean isBG = line.isBG();
        // M-03：伴唱行主行画笔按伴唱字号（基值×0.7）绘制，绘制后还原（副行画笔不受影响）。
        float savedMainSize = 0f;
        float savedGlowSize = 0f;
        if (isBG) {
            savedMainSize = mainPaint.getTextSize();
            savedGlowSize = glowPaint.getTextSize();
            mainPaint.setTextSize(savedMainSize * BG_FONT_RATIO);
            glowPaint.setTextSize(savedGlowSize * BG_FONT_RATIO);
        }
        Paint.FontMetrics fm = mainPaint.getFontMetrics();
        float baseline = blockTopY - fm.ascent + dp(2);
        // M-05：段落标签占一行（10sp 字高＋底部 3dp），画在正文上方，正文基线下移标签行高。
        if (block.partLabel != null && !block.partLabel.isEmpty()) {
            String label = ellipsize(block.partLabel, partLabelPaint, maxWidth);
            float labelX = rowStartX(partLabelPaint.measureText(label));
            Paint.FontMetrics labelFm = partLabelPaint.getFontMetrics();
            canvas.drawText(label, labelX, blockTopY - labelFm.ascent, partLabelPaint);
            baseline += block.labelHeight;
        }
        // M-03：伴唱入场变换只作用于主行（副行不进变换）：透明度乘 clamp(bg,0,1)（saveLayerAlpha）、
        // 缩放乘 (0.2×bg＋0.8)、入场位移 (1-bg)×(-0.8)×行高；变换原点：对唱行横向 1.0／非对唱行 0.0、
        // 纵向伴唱 0（块顶）；bg 已到终态 1 时不包变换层。
        float bg = bgValue(line, isCurrent, pos);
        boolean bgAnimating = isBG && bg < 1f;
        int layerSave = -1;
        int transformSave = -1;
        if (bgAnimating) {
            float rowWidthMax = maxRowWidth(block);
            float pivotX = rowStartX(rowWidthMax) + (line.isDuet() ? rowWidthMax : 0f);
            layerSave = canvas.saveLayerAlpha(0f, 0f, (float) getWidth(), (float) getHeight(),
                    Math.round(255f * clampF(bg, 0f, 1f)));
            transformSave = canvas.save();
            canvas.translate(0f, (1f - bg) * (-0.8f) * block.mainRowHeight);
            canvas.translate(pivotX, blockTopY);
            canvas.scale(0.2f * bg + 0.8f, 0.2f * bg + 0.8f);
            canvas.translate(-pivotX, -blockTopY);
        }
        if (isCurrent) {
            drawCurrentMain(canvas, block, line, baseline, pos);
        } else {
            drawIdleMain(canvas, block, baseline, distance, blockTopY, anchorY);
        }
        if (bgAnimating) {
            canvas.restoreToCount(transformSave);
            canvas.restoreToCount(layerSave);
        }
        if (isBG) {
            mainPaint.setTextSize(savedMainSize);
            glowPaint.setTextSize(savedGlowSize);
        }
        float subBaseline = baseline + fm.descent + block.subRowHeight * 0.92f;
        subPaint.setAlpha(255);
        subPaint.setMaskFilter(null);
        for (SubRow subRow : block.subRows) {
            if (isCurrent) {
                drawSubRowCurrent(canvas, block, line, subRow, maxWidth, subBaseline, pos);
            } else {
                // L4：非当前行副行跟主行同套压暗＋渐隐——基色 alpha 乘同距 idleAlphaRatio，
                // 再乘 0.42 基底（SUB_IDLE_ALPHA），使闲行翻译有效 alpha 恒 ≤ 闲行主行，
                // 不再出现远处闲行翻译比闲行主行还亮；模糊门：与主行同半径量级时副行不另挂
                // MaskFilter（直绘文本挂 BlurMaskFilter 会糊字，副行只做 alpha 压暗＋渐隐，
                // 模糊门以主行同系数判读、副行不单独挂模糊，见交回）。
                String text = ellipsize(subRow.text, subPaint, maxWidth);
                float x = rowStartX(subPaint.measureText(text));
                subPaint.setColor(subColor());
                float subRatio = idleAlphaRatio(distance, blockTopY, anchorY);
                subPaint.setAlpha(Math.round(SUB_IDLE_ALPHA * clampF(subRatio, 0f, 1f)));
                canvas.drawText(text, x, subBaseline, subPaint);
                subPaint.setAlpha(255);
            }
            subBaseline += block.subRowHeight;
        }
        subPaint.setAlpha(255);
        subPaint.setMaskFilter(null);
    }

    /**
     * L2 当前行副行：有逐词表且逐字开关开时逐词画（复用 drawWordStyled 副行分流，字号 0.62、翻译三色）；
     * 无词表、逐字开关关、词表与文案对不上、或逐词总宽超行宽时回落整行——整行按行进度裁剪已唱／未唱
     * （已唱 1.0／未唱 0.6），不是静止淡色，仍算“随主行进度跟进”。
     */
    private void drawSubRowCurrent(Canvas canvas, Block block, LyricsLine line, SubRow subRow,
                                   float maxWidth, float subBaseline, long pos) {
        int subColor = subColor();
        long relative = pos - line.getTimeMs();
        List<LyricsWord> words = new ArrayList<>();
        for (LyricsWord w : subRow.words) {
            if (w != null && !TextUtils.isEmpty(w.getText())) words.add(w);
        }
        boolean wordPath = wordByWord && !words.isEmpty() && subWordsMatch(subRow.text, words);
        float totalWidth = 0f;
        if (wordPath) {
            for (LyricsWord w : words) totalWidth += subPaint.measureText(w.getText());
            if (totalWidth > maxWidth) wordPath = false;
        }
        if (wordPath) {
            float em = subPaint.getTextSize();
            float x = rowStartX(totalWidth);
            for (int i = 0; i < words.size(); i++) {
                LyricsWord w = words.get(i);
                float width = subPaint.measureText(w.getText());
                Seg seg = new Seg(w.getText(), w, width);
                drawWordStyled(canvas, seg, x, subBaseline, relative, em, i == words.size() - 1,
                        subPaint, subGlowPaint, true);
                x += width;
            }
            subPaint.setAlpha(255);
            subPaint.setMaskFilter(null);
            return;
        }
        // 整行回落：行进度裁剪（行时长口径与主行整行路径同一 lineDuration）。
        String text = ellipsize(subRow.text, subPaint, maxWidth);
        float width = subPaint.measureText(text);
        float x = rowStartX(width);
        long lineDur = lineDuration(block.index, line);
        float progress = lineDur <= 0 ? 1f : clampF(relative / (float) lineDur, 0f, 1f);
        subPaint.setColor(subColor);
        subPaint.setAlpha(SUB_UNSUNG_ALPHA);
        canvas.drawText(text, x, subBaseline, subPaint);
        if (progress > 0f) {
            subPaint.setAlpha(SUB_SUNG_ALPHA);
            if (progress >= 1f) {
                canvas.drawText(text, x, subBaseline, subPaint);
            } else {
                Paint.FontMetrics fm = subPaint.getFontMetrics();
                int saveClip = canvas.save();
                canvas.clipRect(x, subBaseline + fm.top - subPaint.getTextSize() * 0.1f,
                        x + width * progress, subBaseline + fm.bottom + subPaint.getTextSize() * 0.1f);
                canvas.drawText(text, x, subBaseline, subPaint);
                canvas.restoreToCount(saveClip);
            }
        }
        subPaint.setAlpha(255);
    }

    /** 副行逐词表与文案是否同一文本（忽略空格）：对不上时不许按词表画，避免画出缺字／错字行。 */
    private static boolean subWordsMatch(String text, List<LyricsWord> words) {
        if (text == null) return false;
        StringBuilder sb = new StringBuilder();
        for (LyricsWord w : words) sb.append(w.getText());
        String a = text.replace(" ", "").replace("\u00A0", "");
        String b = sb.toString().replace(" ", "").replace("\u00A0", "");
        return !b.isEmpty() && a.equals(b);
    }

    /** 行起始 x：按对齐段控（0 左＝横向 padding、1 中、2 右）。 */
    private float rowStartX(float rowWidth) {
        float pad = dp(H_PADDING_DP);
        if (lyricAlign == 1) return (getWidth() - rowWidth) / 2f;
        if (lyricAlign == 2) return getWidth() - pad - rowWidth;
        return pad;
    }

    private static float rowWidth(List<Seg> row) {
        float width = 0f;
        for (Seg seg : row) width += seg.width;
        return width;
    }

    /** L4：非当前行压暗比（距离衰减＋顶部／底部渐隐），主行与副行同套；主行压暗／模糊数在抠到瓜子源头公式前
     * 保持“等价式”标注，不标已对齐；深色面闲行色白×0.30 保留不动（待裁定，不改回 0xFF9A9AA0）。 */
    private float idleAlphaRatio(int distance, float blockTopY, float anchorY) {
        float lineAlpha = Math.max(IDLE_ALPHA_NEAR_FLOOR, IDLE_ALPHA_FAR - distance * IDLE_ALPHA_STEP);
        float alphaRatio = lineAlpha;
        // 底部渐隐：锚点以下按归一化位置走 bottomFadeKeep 五点曲线；锚点以上不衰减（走顶部渐隐）。
        if (blockTopY > anchorY && getHeight() > anchorY) {
            float f = clampF((blockTopY - anchorY) / (getHeight() - anchorY), 0f, 1f);
            alphaRatio *= bottomFadeKeep(f);
        }
        // 顶部渐隐：距顶 40dp 内按 smoothstep 淡入（GZ 顶部渐隐同式，LyricsViewKt:456-457）。
        float topFadePx = dp(TOP_FADE_HEIGHT_DP);
        if (blockTopY < topFadePx) {
            alphaRatio *= smoothstep(blockTopY / topFadePx);
        }
        return alphaRatio;
    }

    private void drawIdleMain(Canvas canvas, Block block, float baseline, int distance, float blockTopY, float anchorY) {
        // 非当前行：压暗系数（应用侧等价式，源头公式 GZ 未抠到）先算，模糊门槛按此系数判（GZ 应用侧口径）；
        // 最终 alpha 再乘顶部／底部渐隐。
        float lineAlpha = Math.max(IDLE_ALPHA_NEAR_FLOOR, IDLE_ALPHA_FAR - distance * IDLE_ALPHA_STEP);
        float alphaRatio = idleAlphaRatio(distance, blockTopY, anchorY);
        int color = idleMainColor(); // 深色面非当前行基色＝瓜子白×0.30（redo3 N-02；衰减模型瓜子源头未抠到，仍用现行按距离 alpha＋渐隐）
        int alpha = Math.round(255 * clampF(alphaRatio, 0f, 1f));
        // 模糊应用侧（GZ LyricsViewKt:478-511 转写）：半径先量化 E＝round(blur×2)、半径＝E/2；
        // 行 alpha 系数 ≥0.08 才取模糊值；SDK≥31 走 RenderEffect、失败冷却 30s 内不挂；API＜31 走 BlurMaskFilter 等效。
        float rawRadius = Math.min(BLUR_BASE + distance * BLUR_PER_LINE, BLUR_MAX) * blurMultiplier();
        int e = Math.round(rawRadius * 2f);
        float radius = e / 2f;
        boolean blurWanted = lineAlpha >= BLUR_ALPHA_GATE && radius > 0f;
        if (Build.VERSION.SDK_INT >= 31) {
            RenderEffect effect = null;
            if (blurWanted && SystemClock.elapsedRealtime() >= blurRetryAfterMs) {
                effect = blurEffectFor(e);
            }
            if (effect != null && drawIdleRowsWithEffect(canvas, block, baseline, blockTopY, effect, color, alpha)) {
                return;
            }
            // GZ 口径：无可用效果时该行不挂模糊、只压暗直绘。
            drawIdleRows(canvas, block, baseline, color, alpha, null);
            return;
        }
        Paint paint = mainPaint;
        paint.setColor(color);
        paint.setAlpha(alpha);
        paint.setMaskFilter(blurWanted ? blurFilter(radius) : null);
        float y = baseline;
        for (List<Seg> row : block.mainRows) {
            float x = rowStartX(rowWidth(row));
            for (Seg seg : row) {
                canvas.drawText(seg.text, x, y, paint);
                x += seg.width;
            }
            y += block.mainRowHeight;
        }
        paint.setMaskFilter(null);
        paint.setAlpha(255);
    }

    /** 非当前行直绘（无模糊）：API≥31 无可用 RenderEffect 时的 GZ 口径落点。 */
    private void drawIdleRows(Canvas canvas, Block block, float baseline, int color, int alpha, BlurMaskFilter filter) {
        Paint paint = mainPaint;
        paint.setColor(color);
        paint.setAlpha(alpha);
        paint.setMaskFilter(filter);
        float y = baseline;
        for (List<Seg> row : block.mainRows) {
            float x = rowStartX(rowWidth(row));
            for (Seg seg : row) {
                canvas.drawText(seg.text, x, y, paint);
                x += seg.width;
            }
            y += block.mainRowHeight;
        }
        paint.setMaskFilter(null);
        paint.setAlpha(255);
    }

    /**
     * 按 E 取／建 RenderEffect（GZ 应用侧：按 E 缓存 createBlurEffect(E/2, E/2, DECAL)；
     * 创建失败置 30000ms 冷却并返回 null，冷却内不再试）。
     */
    private RenderEffect blurEffectFor(int e) {
        RenderEffect effect = blurEffectCache.get(e);
        if (effect != null) return effect;
        float r = e / 2f;
        try {
            effect = RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL);
            blurEffectCache.put(e, effect);
            return effect;
        } catch (Exception ex) {
            blurRetryAfterMs = SystemClock.elapsedRealtime() + BLUR_RETRY_COOLDOWN_MS;
            return null;
        }
    }

    /**
     * API≥31 非当前行绘制：整块行录进 RenderNode、挂 RenderEffect 后绘出（View 体系下 RenderEffect 的落点）。
     * 录制／绘制异常按 GZ 失败口径置 30s 冷却并回退直绘（返回 false 由调用方直绘）。
     */
    private boolean drawIdleRowsWithEffect(Canvas canvas, Block block, float baseline, float blockTopY,
                                           RenderEffect effect, int color, int alpha) {
        int w = getWidth();
        if (w <= 0) return false;
        int h = Math.max(1, Math.round(block.mainRows.size() * block.mainRowHeight + dp(4)));
        RenderNode node = new RenderNode("karaokeIdleBlur");
        try {
            node.setPosition(0, 0, w, h);
            Canvas rec = node.beginRecording(w, h);
            Paint paint = new Paint(mainPaint);
            paint.setColor(color);
            paint.setAlpha(alpha);
            paint.setMaskFilter(null);
            float y = baseline - blockTopY;
            for (List<Seg> row : block.mainRows) {
                float x = rowStartX(rowWidth(row));
                for (Seg seg : row) {
                    rec.drawText(seg.text, x, y, paint);
                    x += seg.width;
                }
                y += block.mainRowHeight;
            }
            node.endRecording();
            node.setRenderEffect(effect);
            int save = canvas.save();
            canvas.translate(0f, blockTopY);
            canvas.drawRenderNode(node);
            canvas.restoreToCount(save);
            return true;
        } catch (Exception ex) {
            blurRetryAfterMs = SystemClock.elapsedRealtime() + BLUR_RETRY_COOLDOWN_MS;
            return false;
        }
        // 注意：此处不许调 node.discardDisplayList()。硬件加速下当前帧的显示列表在 onDraw 返回后才由渲染线程消费，
        // 立即 discard 会把刚录好的闲行显示列表清掉——闲行“绘制成功”却空白，只剩直绘的当前行可见。
        // 两张实拍（播放页、歌词页）都只剩一句，根子就是这一行。节点随帧引用自然释放，不用手动清。
    }

    private void drawCurrentMain(Canvas canvas, Block block, LyricsLine line, float baseline, long pos) {
        Paint paint = mainPaint;
        paint.setMaskFilter(null);
        // 当前行深色面字幕式阴影（只作用于当前行）：白字直接压在亮色糊底上无对比保护，
        // 亮区会把字吃掉（实拍左段压在月光糊块上最糊）；暗色阴影给字形描边感，背景一层贯穿不动。
        // 闲行共用 mainPaint，末尾必须清掉，不许带进闲行。
        boolean shadowOn = !lightScheme;
        if (shadowOn) paint.setShadowLayer(dp(3), 0f, dp(1), 0xB3000000);
        long relative = pos - line.getTimeMs();
        // M-02：逐字提亮复用原文词渲染路径（布局已按三档词表分段）；词空时 Seg.word 为 null，走整行提亮回落。
        boolean wordMode = wordByWord;
        float em = paint.getTextSize();
        float y = baseline;
        int totalRows = block.mainRows.size();
        for (int rowIndex = 0; rowIndex < totalRows; rowIndex++) {
            List<Seg> row = block.mainRows.get(rowIndex);
            float x = rowStartX(rowWidth(row));
            for (int segIndex = 0; segIndex < row.size(); segIndex++) {
                Seg seg = row.get(segIndex);
                boolean isLastSeg = rowIndex == totalRows - 1 && segIndex == row.size() - 1;
                if (wordMode && seg.word != null) {
                    drawWord(canvas, seg, x, y, relative, em, isLastSeg);
                } else {
                    drawWholeLineSegment(canvas, block, seg, x, y, relative, line);
                }
                x += seg.width;
            }
            y += block.mainRowHeight;
        }
        paint.setAlpha(255);
        paint.setColor(unsungColor());
        if (shadowOn) paint.clearShadowLayer();
    }

    private void drawWord(Canvas canvas, Seg seg, float x, float y, long relative, float em, boolean isLastWord) {
        drawWordStyled(canvas, seg, x, y, relative, em, isLastWord, mainPaint, glowPaint, false);
    }

    /**
     * L2／L3 共用逐词绘制：主行与副行同一路径，画笔与颜色分流。
     * 主行：原口径（未唱色＋提亮 alpha 斜坡＋已唱裁剪）；副行：翻译色＋三色 alpha
     * （已唱 1.0／未唱 0.6，见 SUB_*_ALPHA），光晕色同样走翻译色、字号走副行 0.62（调用方传 em）。
     * L3：变色按字符切片（seg.charStartMs／charDurationMs）算，上浮按原词算（CJK 长音多字词
     * 按字符切片算，GZ liftRanges 口径：长音 CJK 词逐字各有 lift 区间，其余词整词一个区间），
     * 光晕／缩放量按原词时长算；
     * 横向漂移 0.03em 只作用于拆字 Seg（charCount＞1），按上浮进度算；长音正弦浮动按字符序号／字符数
     * 算 sin(2π×charIndex／charCount)（瓜子确切相位未抠到，本式只保证“按序号／字符数算正弦”，见交回）。
     * CJK 门：Seg 已是单字时 isCjkWord(seg.text) 即逐字判，不再整词判。
     */
    private void drawWordStyled(Canvas canvas, Seg seg, float x, float y, long relative, float em,
                                boolean isLastWord, Paint textPaint, Paint glowPaintParam, boolean isSub) {
        LyricsWord word = seg.word;
        long start = seg.charStartMs;
        long duration = Math.max(1, seg.charDurationMs);
        long originalDuration = word.getDurationMs();
        // 变色进度：按字符切片时长归一（与上浮分母分开，GZ 同口径）。
        // 扫速：GZ sungCharsForTime 在字符内线性插值，扫边匀速走，不用 easeOutQuad（每字先快后慢会抖）。
        // 未唱底色不做 0.55→1 的逐字亮度斜坡：瓜子先整遍画未唱色（满 alpha），再叠已唱色，
        // 斜坡是星辰自加的第二重动画，会让每个字先亮后变色、边缘发灰；整行回落路径仍用
        // WORD_ALPHA_FROM（见 drawWholeLineSegment），此处词路径不再用它。
        float colorProgress = clampF((relative - start) / (float) duration, 0f, 1f);
        int alpha = 255;

        float drawY = y;
        float drawX = x;
        float scale = 1f;
        // 长音量预先算好，光晕绘制与缩放共用。
        boolean isLong = advancedFx && originalDuration >= LONG_WORD_MS;
        float glowProgress = 0f;
        float glowIntensity = 0f;
        float glowRadius = 0f;
        float scaleAmount = 0f;
        float liftProgress = 0f;
        if (advancedFx) {
            // 逐字上浮（GZ wordLiftProgressTime）：分母与起点按原词算，不按字符切片算——
            // GZ 的 liftRanges 按词建（词起→词止），lift 数组按 range 索引、词内各字同乘词进度；
            // 此前按切片算，快字（中文单字 200-300ms）分母被 1100 下限压到 0.2，几乎不动，
            // 正是「逐字生硬」的根之一。按词算整词走完才满 0.09em。
            // 例外：长音（≥1000ms）全 CJK 多字词，GZ liftRanges 是逐字区间（charTimings 起止），
            // 各字按自己的切片起止错峰上浮，此处跟该口径（切片时长已按字宽加权，与 charTimings 同效）。
            if (originalDuration >= LONG_WORD_MS && isCjkWord(word.getText()) && seg.charCount > 1) {
                liftProgress = easeOut(clampF((relative - start) / (float) Math.max(WORD_LIFT_MIN_DURATION_MS, duration), 0f, 1f));
            } else {
                long wordStart = word.getStartOffsetMs();
                liftProgress = easeOut(clampF((relative - wordStart) / (float) Math.max(WORD_LIFT_MIN_DURATION_MS, originalDuration), 0f, 1f));
            }
            drawY += WORD_LIFT_EM * em * liftProgress;
            // L3 逐字横向漂移 0.03（LyricsViewKt:7124-7147／10377）：只对拆字 Seg，按上浮进度算，
            // 整词 Seg（charCount＝0）不漂移，避免整词平移。
            if (seg.charCount > 1) drawX = x + 0.03f * em * liftProgress;
        }
        if (isLong) {
            // 光晕进度（GZ emphasizeProgressTime）：empEasing(clamp((t−开始)/分母))，
            // 分母＝max(1000, 原词时长)，行末字释放段再＋400ms（EMPH_RELEASE_TAIL_MS）；
            // 注意：光晕起点按原词起算（不是字符切片起），整词光晕包络不因拆字碎掉。
            long origStart = word.getStartOffsetMs();
            long denom = Math.max(LONG_WORD_MS, originalDuration) + (isLastWord ? EMPH_RELEASE_TAIL_MS : 0L);
            glowProgress = empEasing(clampF((relative - origStart) / (float) denom, 0f, 1f));
            // 强度／半径／边距走 GZ emphasizeGlow／emphasizeGlowMargin 静态方法（见本文件末段）。
            glowIntensity = emphasizeGlowIntensity(originalDuration, isLastWord);
            glowRadius = emphasizeGlowRadius(originalDuration, isLastWord, em);
            // 长音字母浮动：拆字 Seg 按字符序号／字符数算正弦（GZ emphasizeUnitFloatTime 口径基础），
            // 未拆字 Seg 保留原均匀包络；另有 GZ 缩放变换的纵向漂移 −0.025×缩放量×进度×字号（:10376，CJK 与否同式）。
            scaleAmount = emphasizeScaleAmount(originalDuration, isLastWord);
            if (seg.charCount > 1) {
                float sinPhase = (float) Math.sin(2.0 * Math.PI * seg.charIndex / (float) Math.max(1, seg.charCount));
                drawY += EMPHASIZE_LETTER_FLOAT_EM * em * glowProgress * sinPhase;
            } else {
                drawY += EMPHASIZE_LETTER_FLOAT_EM * em * glowProgress;
            }
            drawY += -0.025f * scaleAmount * glowProgress * em;
            // 长音缩放（GZ 应用式 LyricsViewKt:10369）：scale＝1＋0.1×光晕进度×缩放量，且只对非 CJK 字；
            // CJK 字缩放恒 1（GZ isCJK 分支）；Seg 已拆字时 seg.text 即单字，逐字判。
            if (!isCjkWord(seg.text)) scale = 1f + 0.1f * glowProgress * scaleAmount;
        }

        int save = -1;
        if (scale != 1f) {
            save = canvas.save();
            float cx = drawX + seg.width / 2f;
            canvas.translate(cx, drawY);
            canvas.scale(scale, scale);
            canvas.translate(-cx, -drawY);
        }
        if (isLong && glowProgress > 0.01f && glowRadius > 0f) {
            // 光晕边距（GZ emphasizeGlowMargin，见本文件末段）：GZ 用于光晕位图外扩；
            // Canvas 文字模糊不裁剪、无需扩图，此处按公式算出留档，绘制直接用 BlurMaskFilter 等价呈边缘辉光（应用侧等价）。
            float glowMargin = emphasizeGlowMargin(glowRadius, em);
            if (glowMargin > 0f) {
                glowPaintParam.setColor(isSub ? subColor() : activeColor());
                glowPaintParam.setAlpha(Math.round(255f * glowIntensity * glowProgress));
                glowPaintParam.setMaskFilter(blurFilter(glowRadius));
                canvas.drawText(seg.text, drawX, drawY, glowPaintParam);
                glowPaintParam.setMaskFilter(null);
                glowPaintParam.setAlpha(255);
            }
        }
        // 逐字变色（redo3 N-06，瓜子 sungEdgeXFor 口径）：先整词画未唱色，再按词内进度
        // （词宽×colorProgress）竖直裁剪画已唱色；斜体下裁剪边为竖直字符边界级。
        if (isSub) {
            int subColor = subColor();
            textPaint.setColor(subColor);
            textPaint.setAlpha(SUB_UNSUNG_ALPHA);
            canvas.drawText(seg.text, drawX, drawY, textPaint);
            if (colorProgress > 0f) {
                textPaint.setColor(subColor);
                textPaint.setAlpha(SUB_SUNG_ALPHA);
                if (colorProgress >= 1f) {
                    canvas.drawText(seg.text, drawX, drawY, textPaint);
                } else {
                    // GZ 扫边（WordByWordText 渐变段）：带宽 f5＝max(9dp, 字号)，扫边位置
                    // right3＝左＋(词宽＋f5)×进度、渐变起于 right3−f5，裁剪止于词右沿；
                    // 旧 0.35em 带宽＋止于真进度的裁剪，边太硬、每字一顿。本处按 Seg 复现
                    // 同式（Seg 宽即本字宽，进度为字内进度，裁剪仍不许越出本字右沿）。
                    float feather = Math.max(dp(9f), em);
                    float edgeX = drawX + (seg.width + feather) * colorProgress;
                    Paint.FontMetrics fm = textPaint.getFontMetrics();
                    int saveClip = canvas.save();
                    canvas.clipRect(drawX, drawY + fm.top - em * 0.1f,
                            Math.min(edgeX, drawX + seg.width), drawY + fm.bottom + em * 0.1f);
                    textPaint.setShader(new LinearGradient(edgeX - feather, 0, edgeX, 0,
                            new int[]{subColor, Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
                    canvas.drawText(seg.text, drawX, drawY, textPaint);
                    textPaint.setShader(null);
                    canvas.restoreToCount(saveClip);
                }
            }
            textPaint.setAlpha(255);
        } else {
            int unsung = unsungColor();
            textPaint.setColor(unsung);
            textPaint.setAlpha(alpha);
            canvas.drawText(seg.text, drawX, drawY, textPaint);
            if (colorProgress > 0f) {
                textPaint.setColor(activeColor());
                if (colorProgress >= 1f) {
                    canvas.drawText(seg.text, drawX, drawY, textPaint);
                } else {
                    // GZ 扫边同副行分流注释：带宽 f5＝max(9dp, 字号)，扫边位置＝左＋(字宽＋f5)×进度，
                    // 渐变起于扫边−f5，裁剪止于字右沿（旧 0.35em＋止于真进度，边太硬）。
                    float feather = Math.max(dp(9f), em);
                    float edgeX = drawX + (seg.width + feather) * colorProgress;
                    Paint.FontMetrics fm = textPaint.getFontMetrics();
                    int saveClip = canvas.save();
                    canvas.clipRect(drawX, drawY + fm.top - em * 0.1f,
                            Math.min(edgeX, drawX + seg.width), drawY + fm.bottom + em * 0.1f);
                    textPaint.setShader(new LinearGradient(edgeX - feather, 0, edgeX, 0,
                            new int[]{activeColor(), Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
                    canvas.drawText(seg.text, drawX, drawY, textPaint);
                    textPaint.setShader(null);
                    canvas.restoreToCount(saveClip);
                }
            }
        }
        if (save >= 0) canvas.restoreToCount(save);
    }

    /**
     * 长音缩放量（GZ emphasizeScaleAmount 全公式）：f＝时长ms；x＝f/2000，x＞1 取 sqrt、否则取立方，
     * ×0.6；行末字 ×1.6；上限 1.2。此为「量」，实际缩放＝1＋0.1×光晕进度×量（见 drawWord）。
     */
    static float emphasizeScaleAmount(long durationMs, boolean isLastWord) {
        float x = durationMs / 2000f;
        float shaped = x > 1f ? (float) Math.sqrt(x) : x * x * x;
        float amount = shaped * 0.6f;
        if (isLastWord) amount *= 1.6f;
        return Math.min(amount, 1.2f);
    }

    /**
     * GZ emphasizeGlow（LyricsViewKt:11404-11427）整段转写：入参为词时长 ms、是否行末、字号 px；
     * s＝clamp(时长/1000, 1, 8)。强度与半径系数各走先封顶、行末再乘系数再封顶的顺序，不可调换。
     * 触发门仍为星辰侧整词近似（时长 ≥1000ms 的词）：GZ 强调区间来自 AMLL 强调数据、无时长门，
     * 星辰无强调区间输入，故时长门为近似触发条件，不是瓜子触发条件，见残留注记。
     */
    static float emphasizeGlowIntensity(long durationMs, boolean isLastWord) {
        float s = clampF(durationMs / 1000f, 1f, 8f);
        float intensity = Math.min((s - 1f) * EMPH_GLOW_PER_SEC + EMPH_GLOW_BASE, EMPH_GLOW_CAP);
        if (isLastWord) intensity = Math.min(intensity * EMPH_GLOW_LAST_MUL, EMPH_GLOW_LAST_CAP);
        return intensity;
    }

    static float emphasizeGlowRadius(long durationMs, boolean isLastWord, float em) {
        float s = clampF(durationMs / 1000f, 1f, 8f);
        float coef = Math.min((s - 1f) * EMPH_RADIUS_PER_SEC + EMPH_RADIUS_BASE, EMPH_RADIUS_CAP);
        if (isLastWord) coef = Math.min(coef * EMPH_RADIUS_LAST_MUL, EMPH_RADIUS_LAST_CAP);
        return coef * em;
    }

    /** GZ emphasizeGlowMargin（LyricsViewKt:11428-11430）：入参顺序为（光晕半径 px，字号 px）。 */
    static float emphasizeGlowMargin(float radiusPx, float em) {
        return em * 0.12f + radiusPx * 3f + 2f;
    }

    /** GZ isCJK（LyricsViewKt:11550-11563）逐值转写：平假名／CJK 统一表意／兼容表意区段。 */
    static boolean isCJK(char c) {
        if (12352 <= c && c < 12544) return true;
        if (19968 <= c && c < 40960) return true;
        if (13312 <= c && c < 19904) return true;
        if (63744 > c || c >= 64256) return 12288 <= c && c < 12352;
        return true;
    }

    /** 整词是否全 CJK（空串／null 按非 CJK 计，缩放门放行）。 */
    static boolean isCjkWord(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (!isCJK(text.charAt(i))) return false;
        }
        return true;
    }

    /** 整行模式：当前行按行进度整体提亮（主行无逐字词时）。 */
    private void drawWholeLineSegment(Canvas canvas, Block block, Seg seg, float x, float y, long relative, LyricsLine line) {
        long lineDuration = lineDuration(block.index, line);
        float progress = lineDuration <= 0 ? 1f : clampF(relative / (float) lineDuration, 0f, 1f);
        int alpha = Math.round(255 * (WORD_ALPHA_FROM + (1f - WORD_ALPHA_FROM) * progress));
        mainPaint.setColor(progress > 0f ? activeColor() : unsungColor());
        mainPaint.setAlpha(alpha);
        canvas.drawText(seg.text, x, y, mainPaint);
    }

    private long lineDuration(int index, LyricsLine line) {
        if (index + 1 < lines.size()) return Math.max(1, lines.get(index + 1).getTimeMs() - line.getTimeMs());
        if (line.hasWords()) {
            long end = 0;
            for (LyricsWord word : line.getWords()) end = Math.max(end, word.getEndOffsetMs());
            if (end > 0) return end;
        }
        return LINE_FALLBACK_MS;
    }

    private BlurMaskFilter blurFilter(float radius) {
        BlurMaskFilter filter = blurCache.get(radius);
        if (filter == null) {
            filter = new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL);
            blurCache.put(radius, filter);
        }
        return filter;
    }

    private String ellipsize(String text, Paint paint, float maxWidth) {
        if (paint.measureText(text) <= maxWidth) return text;
        String ellipsis = "…";
        int len = paint.breakText(text, true, maxWidth - paint.measureText(ellipsis), null);
        return text.substring(0, Math.max(0, len)) + ellipsis;
    }

    // ===== 触摸：拖动浏览＋点击行 seek =====

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (lines.isEmpty()) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                removeCallbacks(returnRunnable);
                dragging = true;
                browsing = true;
                downX = event.getX();
                downY = event.getY();
                lastMoveY = event.getY();
                lastMoveTimeMs = SystemClock.elapsedRealtime();
                segMoveY = event.getY();
                segMoveTimeMs = lastMoveTimeMs;
                dragStartOffset = scrollOffset;
                downTimeMs = SystemClock.elapsedRealtime();
                scrollVelocity = 0f;
                seekSpringActive = false;
                // 内嵌不拦父容器拦截：整页竖滑切歌容器（PlayerPageSwipeLayout）要靠 onInterceptTouchEvent
                // 接管歌词区竖滑；内嵌若 disallow，容器永远拦不到、竖滑又变回滚歌词。全屏页父层不是切歌容器，照旧拦。
                if (!coverMode && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging && !coverMode) {
                    float maxOffset = Math.max(0f, contentHeight - dp(80));
                    scrollOffset = clamp(dragStartOffset + (downY - event.getY()), -dp(60), maxOffset);
                    scrollTarget = scrollOffset;
                    invalidate();
                }
                if (dragging) {
                    segMoveY = lastMoveY;
                    segMoveTimeMs = lastMoveTimeMs;
                    lastMoveY = event.getY();
                    lastMoveTimeMs = SystemClock.elapsedRealtime();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // 全屏页竖滑切歌分流（内嵌不走这条：竖滑归整页容器，歌词不滚不动锚）：
                // 慢拖＝歌词滚动（上面已滚），快甩＝切歌。门与封面手势同口径——
                // 位移≥36dp、轴向占优（|dy|＞|dx|×1.5）、速度≥600dp/s 三者全过才算切歌；
                // 否则走原逻辑（点行 seek／松手回锚）。
                if (!coverMode && trackSwipeListener != null && event.getActionMasked() == MotionEvent.ACTION_UP && dragging) {
                    float dy = event.getY() - downY;
                    float dx = event.getX() - downX;
                    // G9：快甩速度按末段 MOVE 采样（慢拖稀释整段平均、反向慢拖抵消净位移是旧病）；
                    // 松手前 200ms 内无移动视为已停，不算甩。
                    long nowMs = SystemClock.elapsedRealtime();
                    float velocityDpS;
                    if (nowMs - lastMoveTimeMs > 200) {
                        velocityDpS = 0f;
                    } else if (lastMoveTimeMs > segMoveTimeMs) {
                        float segDtMs = Math.max(1f, (float) (lastMoveTimeMs - segMoveTimeMs));
                        velocityDpS = Math.abs(lastMoveY - segMoveY) / segDtMs * 1000f
                                / getResources().getDisplayMetrics().density;
                    } else {
                        float dtMs = Math.max(1f, (float) (lastMoveTimeMs - downTimeMs));
                        velocityDpS = Math.abs(lastMoveY - downY) / dtMs * 1000f
                                / getResources().getDisplayMetrics().density;
                    }
                    if (Math.abs(dy) >= dp(36) && Math.abs(dy) > Math.abs(dx) * 1.5f && velocityDpS >= 600f) {
                        dragging = false;
                        browsing = false;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                        trackSwipeListener.onTrackSwipe(dy < 0);
                        performClick();
                        return true;
                    }
                }
                // G10：点按门放宽（旧漂移＜10dp 且按住＜400ms 太严，按住 500ms／漂移 12dp 就不算点按）：
                // 瓜子点按阈值未抠到，此为星辰侧合理值——漂移＜16dp 且按住＜600ms。
                boolean tap = dragging
                        && Math.abs(event.getX() - downX) < dp(TAP_SLOP_DP)
                        && Math.abs(event.getY() - downY) < dp(TAP_SLOP_DP)
                        && SystemClock.elapsedRealtime() - downTimeMs < 600;
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (tap && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    int index = lineAtY(event.getY());
                    if (index >= 0 && lineClickListener != null) {
                        lineClickListener.onLineClick(index, lines.get(index).getTimeMs());
                    } else if (index < 0 && emptyTapListener != null) {
                        emptyTapListener.onEmptyTap();
                    }
                    // 点行后走 seek 弹簧滑向目标行（外部 seekUi 亦同规格）。
                    browsing = false;
                    seekSpringActive = true;
                    ensureFrameLoop();
                } else if (coverMode) {
                    // 内嵌：竖滑没滚过歌词，不用等回锚，松手即恢复自动跟唱（否则容器切歌后新曲歌词还要冻 2500ms）。
                    browsing = false;
                    removeCallbacks(returnRunnable);
                } else {
                    // 松手后回锚：全屏 5000ms（见常量区口径），到点弹簧自动跟随当前行。
                    removeCallbacks(returnRunnable);
                    postDelayed(returnRunnable, REANCHOR_FULLSCREEN_MS);
                }
                performClick();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private int lineAtY(float y) {
        float anchorY = anchorY();
        float contentY = y - anchorY + scrollOffset;
        for (Block block : blocks) {
            if (contentY >= block.top && contentY < block.top + block.height) return block.index;
        }
        return -1;
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) ensureFrameLoop();
    }

    @Override
    protected void onDetachedFromWindow() {
        choreographer.removeFrameCallback(frameCallback);
        framePosted = false;
        removeCallbacks(returnRunnable);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ensureFrameLoop();
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Seg {
        final String text;
        @Nullable
        final LyricsWord word;
        final float width;
        /**
         * L3 字符起止：逐字 Seg 时 charStartMs／charDurationMs 是该字符在行内的切片
         * （按字宽加权分原词时长，总宽 0 时退回均分，末字符吃余数）；word 仍是原词（光晕／缩放量按原词时长算，
         * 不按切片时长算，否则长词拆字后切片＜1000ms 会丢光晕）。整词 Seg 时 charStart＝词起、
         * charDuration＝词长、charCount＝0（表示未拆字，漂移／正弦浮动不作用）。
         */
        final long charStartMs;
        final long charDurationMs;
        final int charIndex;
        final int charCount;

        Seg(String text, @Nullable LyricsWord word, float width) {
            this(text, word, width, word == null ? 0 : word.getStartOffsetMs(),
                    word == null ? 0 : word.getDurationMs(), 0, 0);
        }

        Seg(String text, @Nullable LyricsWord word, float width,
            long charStartMs, long charDurationMs, int charIndex, int charCount) {
            this.text = text;
            this.word = word;
            this.width = width;
            this.charStartMs = charStartMs;
            this.charDurationMs = charDurationMs;
            this.charIndex = charIndex;
            this.charCount = charCount;
        }
    }

    /** L2 副行：文案＋逐词表（词表可空＝无词表，回落整行）。 */
    private static final class SubRow {
        final String text;
        final List<LyricsWord> words;

        SubRow(String text, List<LyricsWord> words) {
            this.text = text == null ? "" : text;
            this.words = words == null ? Collections.emptyList() : words;
        }
    }

    private static final class Block {
        int index;
        float top;
        float height;
        float mainRowHeight;
        float subRowHeight;
        /** 段落标签文案（M-05，空＝无标签行）与标签行高（10sp＋3dp，空标签时 0）。 */
        String partLabel = "";
        float labelHeight;
        List<List<Seg>> mainRows = Collections.emptyList();
        List<SubRow> subRows = Collections.emptyList();
    }
}
