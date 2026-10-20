package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
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

import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.player.lyrics.LyricsWord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用途：全屏逐字歌词视图——逐字上浮＋长音光晕、非当前行磨砂压暗、弹簧跟随滚动，经典 View＋Canvas 自绘。
 * 参数口径：GZ 反编译参数对照表第 2 节（LyricsViewKt）逐项对齐，2026-10-21；
 * 四色照 GZ 2.5 节原值落码（已唱红／非当前灰／翻译灰，见颜色常量区；当前行未唱深灰为浅面值，
 * 深面用白——GZ 各主题 palette 实例化赋值未抠到（参数表未抠到第 7 条），深面照原值会隐形，
 * 违「字体看得清」铁律，故深面未唱保留白、浅面用 GZ 原值 0xFF3C3C43）。
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
    /** 副行（翻译／音译）字号（sp，星辰口径）。 */
    private static final float SUB_SP = 15f;
    /** 歌词字形斜切（带一点斜，定稿口径）。 */
    private static final float SKEW = -0.12f;
    /** 当前行锚点：视图高度的 38% 处（星辰口径）。 */
    private static final float ANCHOR = 0.38f;

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
    /** 浏览回锚：全屏 5000ms（APPLE_BROWSE_REANCHOR_DELAY）；封面沿星辰现行 2500ms（GZ 未给封面值，近似）。 */
    private static final long REANCHOR_FULLSCREEN_MS = 5000L;
    private static final long REANCHOR_COVER_MS = 2500L;

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
    /** 主行未唱颜色（深色面）。 */
    private static final int MAIN_COLOR = Color.WHITE;
    /** 主行已唱／高亮颜色＝GZ 已唱色。 */
    private static final int ACTIVE_COLOR = COLOR_SUNG;
    /** 副行颜色（深色面）＝GZ 翻译色。 */
    private static final int SUB_COLOR = COLOR_TRANSLATION;
    /** 非当前行颜色（深色面）＝GZ 非当前行色（再乘按距离 alpha 与渐隐）。 */
    private static final int IDLE_COLOR = COLOR_INACTIVE;
    /** 空歌词提示颜色（深色面）。 */
    private static final int EMPTY_COLOR = 0x8AFFFFFF;
    /** 浅色面（setLightScheme）：主行未唱＝GZ 当前行未唱原值、非当前行改深灰系，主行已唱保持 GZ 已唱红。 */
    private static final int MAIN_COLOR_LIGHT = COLOR_ACTIVE_UNSUNG;
    private static final int IDLE_COLOR_LIGHT = 0xFF636366;
    private static final int SUB_COLOR_LIGHT = 0xFF747D89;
    private static final int EMPTY_COLOR_LIGHT = 0x8A000000;

    /** 整行模式无词时一行默认时长（算行进度用，末行兜底）。 */
    private static final long LINE_FALLBACK_MS = 4000L;
    /** 点击判定：位移小于该值视为点击（dp）。 */
    private static final float TAP_SLOP_DP = 10f;

    /** 行点击回调（seek 用）。 */
    public interface OnLineClickListener {
        void onLineClick(int index, long positionMs);
    }

    private final Paint mainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Float, BlurMaskFilter> blurCache = new HashMap<>();
    /** API≥31 模糊缓存：按量化档 E 缓存 RenderEffect（GZ 应用侧同口径）；blurRetryAfterMs 为失败冷却截止时刻。 */
    private final Map<Integer, RenderEffect> blurEffectCache = new HashMap<>();
    private long blurRetryAfterMs;
    /** 空态三态（默认 STATE_LOADING：取词未定前什么都不画）。 */
    private int emptyState = STATE_LOADING;
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            framePosted = false;
            stepFrame();
        }
    };
    private final Runnable returnRunnable = () -> {
        browsing = false;
        ensureFrameLoop();
    };

    private List<LyricsLine> lines = Collections.emptyList();
    private List<String> translations;
    private List<String> romanizations;
    private int primaryMode; // 0=原文 1=翻译 2=音译 作主行
    /** 逐字显示开关（歌词页「逐字」胶囊切换）：开＝逐字分段渲染（上浮/光晕/逐字变色），关＝整行渲染路径。 */
    private boolean wordByWord = true;
    private boolean showTranslation = true;
    private boolean showRomanization = true;
    private OnLineClickListener lineClickListener;

    // 歌词样式（设置面板／播放页可调，GZ LyricStyle 口径）
    private boolean coverMode;
    private float mainTextSizeSp = MAIN_SP_FULLSCREEN;
    private int lyricAlign; // 0左 1中 2右
    private int lyricWeight; // 0标准 1加粗 2特粗
    private int blurLevel = 2; // 0关 1轻(×0.6) 2中(×1)；缺省 2 保持现行观感
    private boolean advancedFx = true;
    private boolean lightScheme;

    private boolean playing;
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
    private float downY;
    private float downX;
    private float dragStartOffset;
    private long downTimeMs;

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
        initPaint(subPaint, SUB_SP, SUB_COLOR, false);
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

    /** 主行／光晕画笔样式：字号（setMainTextSizeSp／setCoverMode 基线）＋粗细（setLyricWeight）。 */
    private void applyMainStyle() {
        Typeface typeface = mainTypeface();
        for (Paint paint : new Paint[]{mainPaint, glowPaint}) {
            paint.setTextSize(mainTextSizePx());
            paint.setTextSkewX(SKEW);
            paint.setTypeface(typeface);
            // API 28 以下无字重 API，特粗／加粗用 fakeBold 叠加近似；28+ 已由字重表达，不再叠。
            paint.setFakeBoldText(lyricWeight >= 1 && Build.VERSION.SDK_INT < 28);
        }
        glowPaint.setColor(ACTIVE_COLOR);
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
        mainPaint.setColor(lightScheme ? MAIN_COLOR_LIGHT : MAIN_COLOR);
        subPaint.setColor(lightScheme ? SUB_COLOR_LIGHT : SUB_COLOR);
        emptyPaint.setColor(lightScheme ? EMPTY_COLOR_LIGHT : EMPTY_COLOR);
    }

    private float mainTextSizePx() {
        return sp(mainTextSizeSp);
    }

    private float blurMultiplier() {
        return blurLevel == 0 ? 0f : (blurLevel == 1 ? 0.6f : 1f);
    }

    // ===== 对外 API =====

    /** 输入歌词：translations／romanizations 与 lines 按下标对齐，可传 null。 */
    public void setLines(List<LyricsLine> lines, List<String> translations, List<String> romanizations) {
        this.lines = lines == null ? Collections.emptyList() : new ArrayList<>(lines);
        this.translations = translations == null ? null : new ArrayList<>(translations);
        this.romanizations = romanizations == null ? null : new ArrayList<>(romanizations);
        currentIndex = -1;
        scrollSnapped = false;
        layoutWidth = -1;
        rebuildLayout();
        if (!this.lines.isEmpty()) {
            currentIndex = LyricsParser.findLine(this.lines, displayPositionMs());
            snapScrollToCurrent();
        }
        ensureFrameLoop();
        invalidate();
    }

    // —— 空态三态接口（线 C 按此接线，方法名已定）：有词走 setLines，此三态只管无词时画什么 ——

    /** 取词未定：什么都不画（默认态）。 */
    public void showLoading() {
        emptyState = STATE_LOADING;
        invalidate();
    }

    /** 已确认无词：显示低调空态（图标 40dp／alpha 0.5、「暂无歌词」12sp，GZ 参数表第 3 节）。 */
    public void showNoLyric() {
        emptyState = STATE_NO_LYRIC;
        invalidate();
    }

    /** 无曲目：等待播放态（图标 alpha 0.3、「等待播放」alpha 0.4、12sp，GZ 参数表第 3 节）。 */
    public void showWaiting() {
        emptyState = STATE_WAITING;
        invalidate();
    }

    /** 行高（GZ coverLyricLineHeight，LyricsViewKt:10901-10903）：行高＝字号×4/3。 */
    static float lyricLineHeight(float textSizePx) {
        return textSizePx * 4f / 3f;
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

    /** 主行字号（sp）：钳制 16–40（GZ LYRIC_SIZE_MIN／MAX），覆盖 setCoverMode 定下的基线。 */
    public void setMainTextSizeSp(float sizeSp) {
        float clamped = Math.max(LYRIC_SIZE_MIN, Math.min(LYRIC_SIZE_MAX, sizeSp));
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

    /** 非当前行模糊档：0 关 1 轻（半径 ×0.6）2 中（半径 ×1）。 */
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
    public void setLightScheme(boolean light) {
        if (lightScheme == light) return;
        lightScheme = light;
        applySchemeColors();
        invalidate();
    }

    public void setOnLineClickListener(OnLineClickListener listener) {
        this.lineClickListener = listener;
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
        basePositionMs = Math.max(0, positionMs);
        baseRealtimeMs = SystemClock.elapsedRealtime();
        if (!browsing) {
            int next = lines.isEmpty() ? -1 : LyricsParser.findLine(lines, basePositionMs);
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
        currentIndex = lines.isEmpty() ? -1 : LyricsParser.findLine(lines, Math.max(0, posMs));
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
        return basePositionMs + Math.max(0, SystemClock.elapsedRealtime() - baseRealtimeMs);
    }

    private void ensureFrameLoop() {
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
        long now = SystemClock.elapsedRealtime();
        long frameMs = lastFrameMs == 0 ? 16 : now - lastFrameMs;
        lastFrameMs = now;
        float dt = Math.max(1, Math.min(50, frameMs)) / 1000f;

        if (!browsing && !dragging && !lines.isEmpty()) {
            int next = LyricsParser.findLine(lines, displayPositionMs());
            if (next != currentIndex) currentIndex = next;
            scrollTarget = targetFor(currentIndex);
            stepSpring(dt);
        }
        invalidate();
        if (playing || needsSettle()) {
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
        // 行高＝字号 ×4/3（GZ coverLyricLineHeight，全屏／封面同公式，LyricsViewKt:10901-10903）。
        float mainRowHeight = lyricLineHeight(mainTextSizePx());
        Paint.FontMetrics subFm = subPaint.getFontMetrics();
        float subRowHeight = (subFm.descent - subFm.ascent) * 1.15f;
        float gapPx = dp(coverMode ? LINE_GAP_COVER_DP : LINE_GAP_FULLSCREEN_DP);
        List<Block> list = new ArrayList<>(lines.size());
        float top = 0f;
        for (int i = 0; i < lines.size(); i++) {
            Block block = new Block();
            block.index = i;
            block.top = top;
            block.mainRows = layoutMainRows(i, maxMainWidth);
            block.subTexts = subTextsFor(i);
            block.height = block.mainRows.size() * mainRowHeight
                    + block.subTexts.size() * subRowHeight
                    + gapPx;
            block.mainRowHeight = mainRowHeight;
            block.subRowHeight = subRowHeight;
            list.add(block);
            top += block.height;
        }
        blocks = list;
        contentHeight = top;
        if (!scrollSnapped && currentIndex >= 0) snapScrollToCurrent();
    }

    /** 主行分段：有逐字词且原文作主行时按词分段折行；否则整行按宽度折行（仅量宽，绘制时整段处理）。 */
    private List<List<Seg>> layoutMainRows(int index, float maxWidth) {
        List<List<Seg>> rows = new ArrayList<>();
        String mainText = mainTextFor(index);
        if (TextUtils.isEmpty(mainText)) {
            rows.add(new ArrayList<>());
            return rows;
        }
        LyricsLine line = lines.get(index);
        boolean wordMode = primaryMode == 0 && wordByWord && line.hasWords();
        List<Seg> segs = new ArrayList<>();
        if (wordMode) {
            for (LyricsWord word : line.getWords()) {
                if (TextUtils.isEmpty(word.getText())) continue;
                segs.add(new Seg(word.getText(), word, mainPaint.measureText(word.getText())));
            }
            if (segs.isEmpty()) wordMode = false;
        }
        if (!wordMode) {
            segs.add(new Seg(mainText, null, mainPaint.measureText(mainText)));
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

    private List<String> subTextsFor(int index) {
        List<String> subs = new ArrayList<>(2);
        String original = lines.get(index).getText();
        String translation = at(translations, index);
        String romanization = at(romanizations, index);
        // 原文被顶替作主行时退为第一副行。
        if (primaryMode != 0 && !TextUtils.isEmpty(original)) subs.add(original);
        if (primaryMode != 1 && showTranslation && !TextUtils.isEmpty(translation)) subs.add(translation);
        if (primaryMode != 2 && showRomanization && !TextUtils.isEmpty(romanization)) subs.add(romanization);
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
        long pos = displayPositionMs();
        float anchorY = getHeight() * ANCHOR;
        float maxWidth = getWidth() - dp(H_PADDING_DP) * 2f;
        int windowBefore = coverMode ? WINDOW_BEFORE_COVER : WINDOW_BEFORE_FULLSCREEN;
        int windowAfter = coverMode ? WINDOW_AFTER_COVER : WINDOW_AFTER_FULLSCREEN;
        for (Block block : blocks) {
            // 渲染窗口（GZ 窗口常量）：当前行前 before 行、后 after 行之外不画。
            if (currentIndex >= 0 && (block.index < currentIndex - windowBefore || block.index > currentIndex + windowAfter)) continue;
            float blockY = anchorY - scrollOffset + block.top;
            if (blockY + block.height < -dp(40) || blockY > getHeight() + dp(40)) continue;
            int distance = currentIndex < 0 ? Math.abs(block.index) + 4 : Math.abs(block.index - currentIndex);
            boolean isCurrent = block.index == currentIndex;
            drawBlock(canvas, block, blockY, maxWidth, distance, isCurrent, pos, anchorY);
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
        if (emptyState == STATE_LOADING) return;
        boolean waiting = emptyState == STATE_WAITING;
        int base = lightScheme ? IDLE_COLOR_LIGHT : IDLE_COLOR;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        emptyPaint.setColor(base);
        emptyPaint.setTextSize(dp(EMPTY_ICON_DP));
        emptyPaint.setAlpha(Math.round(255f * (waiting ? EMPTY_WAITING_ICON_ALPHA : EMPTY_NO_LYRIC_ICON_ALPHA)));
        Paint.FontMetrics iconFm = emptyPaint.getFontMetrics();
        float iconBaseline = cy - dp(12) - (iconFm.ascent + iconFm.descent) / 2f;
        canvas.drawText("♪", cx, iconBaseline, emptyPaint);
        emptyPaint.setTextSize(sp(EMPTY_TEXT_SP));
        emptyPaint.setAlpha(waiting ? Math.round(255f * EMPTY_WAITING_TEXT_ALPHA) : 255);
        canvas.drawText(waiting ? "等待播放" : "暂无歌词", cx, cy + dp(20), emptyPaint);
        emptyPaint.setAlpha(255);
    }

    private void drawBlock(Canvas canvas, Block block, float blockTopY, float maxWidth,
                           int distance, boolean isCurrent, long pos, float anchorY) {
        LyricsLine line = lines.get(block.index);
        Paint.FontMetrics fm = mainPaint.getFontMetrics();
        float baseline = blockTopY - fm.ascent + dp(2);
        if (isCurrent) {
            drawCurrentMain(canvas, block, line, baseline, pos);
        } else {
            drawIdleMain(canvas, block, baseline, distance, blockTopY, anchorY);
        }
        float subBaseline = baseline + fm.descent + block.subRowHeight * 0.92f;
        subPaint.setAlpha(255);
        subPaint.setMaskFilter(null);
        for (String sub : block.subTexts) {
            String text = ellipsize(sub, subPaint, maxWidth);
            float x = rowStartX(subPaint.measureText(text));
            canvas.drawText(text, x, subBaseline, subPaint);
            subBaseline += block.subRowHeight;
        }
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

    private void drawIdleMain(Canvas canvas, Block block, float baseline, int distance, float blockTopY, float anchorY) {
        // 非当前行：压暗系数（应用侧等价式，源头公式 GZ 未抠到）先算，模糊门槛按此系数判（GZ 应用侧口径）；
        // 最终 alpha 再乘顶部／底部渐隐。
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
        int color = lightScheme ? IDLE_COLOR_LIGHT : IDLE_COLOR;
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
        } finally {
            node.discardDisplayList();
        }
    }

    private void drawCurrentMain(Canvas canvas, Block block, LyricsLine line, float baseline, long pos) {
        Paint paint = mainPaint;
        paint.setMaskFilter(null);
        long relative = pos - line.getTimeMs();
        boolean wordMode = primaryMode == 0 && wordByWord && line.hasWords();
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
        paint.setColor(lightScheme ? MAIN_COLOR_LIGHT : MAIN_COLOR);
    }

    private void drawWord(Canvas canvas, Seg seg, float x, float y, long relative, float em, boolean isLastWord) {
        LyricsWord word = seg.word;
        long start = word.getStartOffsetMs();
        long duration = Math.max(1, word.getDurationMs());
        // 变色进度：按词时长归一（与上浮分母分开，GZ 同口径）。
        float colorProgress = clampF((relative - start) / (float) duration, 0f, 1f);
        float colorEased = 1f - (1f - colorProgress) * (1f - colorProgress); // easeOutQuad（星辰现行提亮曲线）
        int alpha = Math.round(255 * (WORD_ALPHA_FROM + (1f - WORD_ALPHA_FROM) * colorEased));

        float drawY = y;
        float scale = 1f;
        // 长音量预先算好，光晕绘制与缩放共用。
        boolean isLong = advancedFx && word.getDurationMs() >= LONG_WORD_MS;
        float glowProgress = 0f;
        float glowIntensity = 0f;
        float glowRadius = 0f;
        float scaleAmount = 0f;
        if (advancedFx) {
            // 逐字上浮（GZ wordLiftProgressTime）：easeOut(clamp((t−字开始)/max(1100, 字时长))) × (−0.09em)。
            float liftProgress = easeOut(clampF((relative - start) / (float) Math.max(WORD_LIFT_MIN_DURATION_MS, duration), 0f, 1f));
            drawY += WORD_LIFT_EM * em * liftProgress;
        }
        if (isLong) {
            // 光晕进度（GZ emphasizeProgressTime）：empEasing(clamp((t−开始)/分母))，
            // 分母＝max(1000, 时长)，行末字释放段再＋400ms（EMPH_RELEASE_TAIL_MS）。
            long denom = Math.max(LONG_WORD_MS, duration) + (isLastWord ? EMPH_RELEASE_TAIL_MS : 0L);
            glowProgress = empEasing(clampF((relative - start) / (float) denom, 0f, 1f));
            // 强度／半径（GZ emphasizeGlow 全公式）：s＝clamp(时长ms/1000,1,8)。
            float s = clampF(word.getDurationMs() / 1000f, 1f, 8f);
            glowIntensity = Math.min((s - 1f) * 0.045f + 0.6f, 0.85f);
            float radiusCoef = Math.min((s - 1f) * 0.012f + 0.3f, 0.5f);
            if (isLastWord) {
                glowIntensity = Math.min(glowIntensity * 1.1f, 0.95f);
                radiusCoef = Math.min(radiusCoef * 1.15f, 0.6f);
            }
            glowRadius = radiusCoef * em;
            // 长音字母另浮 −0.05em（按光晕包络）＋GZ 缩放变换的纵向漂移 −0.025×缩放量×进度×字号。
            scaleAmount = emphasizeScaleAmount(word.getDurationMs(), isLastWord);
            drawY += EMPHASIZE_LETTER_FLOAT_EM * em * glowProgress;
            drawY += -0.025f * scaleAmount * glowProgress * em;
            // 长音缩放（GZ 应用式）：scale＝1＋0.1×光晕进度×缩放量（非 CJK 式；View 按整词近似，CJK 词同式）。
            scale = 1f + 0.1f * glowProgress * scaleAmount;
        }

        int save = -1;
        if (scale != 1f) {
            save = canvas.save();
            float cx = x + seg.width / 2f;
            canvas.translate(cx, drawY);
            canvas.scale(scale, scale);
            canvas.translate(-cx, -drawY);
        }
        if (isLong && glowProgress > 0.01f && glowRadius > 0f) {
            // 光晕边距（GZ emphasizeGlowMargin＝字号×0.12＋半径×3＋2）：GZ 用于光晕位图外扩；
            // Canvas 文字模糊不裁剪、无需扩图，此处按公式算出留档，绘制直接用 BlurMaskFilter 等价呈边缘辉光（应用侧等价）。
            float glowMargin = em * 0.12f + glowRadius * 3f + 2f;
            if (glowMargin > 0f) {
                glowPaint.setColor(ACTIVE_COLOR);
                glowPaint.setAlpha(Math.round(255f * glowIntensity * glowProgress));
                glowPaint.setMaskFilter(blurFilter(glowRadius));
                canvas.drawText(seg.text, x, drawY, glowPaint);
                glowPaint.setMaskFilter(null);
                glowPaint.setAlpha(255);
            }
        }
        mainPaint.setColor(colorProgress > 0f ? ACTIVE_COLOR : (lightScheme ? MAIN_COLOR_LIGHT : MAIN_COLOR));
        mainPaint.setAlpha(alpha);
        canvas.drawText(seg.text, x, drawY, mainPaint);
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

    /** 整行模式：当前行按行进度整体提亮（主行无逐字词时）。 */
    private void drawWholeLineSegment(Canvas canvas, Block block, Seg seg, float x, float y, long relative, LyricsLine line) {
        long lineDuration = lineDuration(block.index, line);
        float progress = lineDuration <= 0 ? 1f : clampF(relative / (float) lineDuration, 0f, 1f);
        int alpha = Math.round(255 * (WORD_ALPHA_FROM + (1f - WORD_ALPHA_FROM) * progress));
        mainPaint.setColor(progress > 0f ? ACTIVE_COLOR : (lightScheme ? MAIN_COLOR_LIGHT : MAIN_COLOR));
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
                dragStartOffset = scrollOffset;
                downTimeMs = SystemClock.elapsedRealtime();
                scrollVelocity = 0f;
                seekSpringActive = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    float maxOffset = Math.max(0f, contentHeight - dp(80));
                    scrollOffset = clamp(dragStartOffset + (downY - event.getY()), -dp(60), maxOffset);
                    scrollTarget = scrollOffset;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean tap = dragging
                        && Math.abs(event.getX() - downX) < dp(TAP_SLOP_DP)
                        && Math.abs(event.getY() - downY) < dp(TAP_SLOP_DP)
                        && SystemClock.elapsedRealtime() - downTimeMs < 400;
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (tap && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    int index = lineAtY(event.getY());
                    if (index >= 0 && lineClickListener != null) {
                        lineClickListener.onLineClick(index, lines.get(index).getTimeMs());
                    }
                    // 点行后走 seek 弹簧滑向目标行（外部 seekUi 亦同规格）。
                    browsing = false;
                    seekSpringActive = true;
                    ensureFrameLoop();
                } else {
                    // 松手后回锚：全屏 5000ms／封面 2500ms（见常量区口径），到点弹簧自动跟随当前行。
                    removeCallbacks(returnRunnable);
                    postDelayed(returnRunnable, coverMode ? REANCHOR_COVER_MS : REANCHOR_FULLSCREEN_MS);
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
        float anchorY = getHeight() * ANCHOR;
        float contentY = y - anchorY + scrollOffset;
        for (Block block : blocks) {
            if (contentY >= block.top && contentY < block.top + block.height) return block.index;
        }
        return -1;
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

        Seg(String text, @Nullable LyricsWord word, float width) {
            this.text = text;
            this.word = word;
            this.width = width;
        }
    }

    private static final class Block {
        int index;
        float top;
        float height;
        float mainRowHeight;
        float subRowHeight;
        List<List<Seg>> mainRows = Collections.emptyList();
        List<String> subTexts = Collections.emptyList();
    }
}
