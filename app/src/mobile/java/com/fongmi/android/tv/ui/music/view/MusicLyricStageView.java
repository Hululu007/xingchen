package com.fongmi.android.tv.ui.music.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.Scroller;

import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.music.MusicPlayerSettings;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsWord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全屏歌词（瓜子 AppleLyricsView／AnchoredLyricEngine 口径，数值照 gz-player-values 第八节）：
 * 舞台铺满整屏（宿主布局 match_parent），行横边距 28dp 在舞台内扣；默认字号读全屏歌词
 * 字号键（默认 24sp、钳 16–40，与页内歌词分键）；行高 ×1.3、字距 −0.2sp、行距 6dp；
 * 锚点＝clamp(0.22×整屏高, 64dp, 120dp) 再封顶 0.28×整屏高、减 24dp/2，对当前行中线；
 * 顶部渐隐带 40dp（smoothstep）；下方淡出起始 0.8×高、bottomFadeKeep 分段表（地板 0.45）；
 * 色（沉浸深色供给，与页内歌词同一条供给链）：已唱 #FFEAEAEA、当前行未唱 白 0.42、
 * 非当前 白 0.30、副行 白 0.55；逐字上浮 −0.09em／缓动分母 max(1100ms, 字长）、长音光晕
 * （与页内同一 createGlyphEdgeGlow 配方）＋行末 400ms 拖尾；
 * 长行换行走瓜子 calcBalancedBreaks 均衡断行（§十六：行宽代价＋标点/空格/CJK 奖惩），不再溢出；
 * 逐行模糊 B 组（全屏传真）：上下均 min(|距|×2.5, 16) ×档位系数，0.5px 量化、RenderEffect DECAL；
 * 模糊尾巴（§十八）：覆写旗三道→0、恒 2.5 特殊态、半径变化时长 400/200ms 分支（缓动 bs4.a）；
 * 当前行判定：二分取 timeMs≤now 最后一行，平滑时间回退 ≤350ms 忽略；纯文本无当前行、静态不滚；
 * 手滑浏览：拖动不钳偏移，松手判定 |偏移|>视口×1.5 直接 snap 回锚、否则惯性后 5000ms 回锚
 * （lyricSettleSpring 1.16/≈237.3）；点行 seek 立即回锚（lyricSeekPosSpring 0.833/100）＋
 * 行对齐 tween(350, bs4.a)；星辰产品项：字体带一点斜（skew −0.12，他定）；点行＝快进到那行（他定）。
 * 副行（翻译/音译）字号比与间距复用页内歌词已对齐口径（×0.62、音译 +4dp、翻译/原文 +6dp），
 * 副行行高随主行口径改 ×1.3。
 */
public class MusicLyricStageView extends View {

    public interface Listener {
        void onLineTap(long timeMs);
    }

    private static final PathInterpolator BS4_A = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final int COLOR_SUNG = 0xFFEAEAEA;
    private static final int COLOR_UNSUNG = 0xFFFFFFFF;
    private static final float ALPHA_UNSUNG = 0.42f;
    private static final float ALPHA_NONCUR = 0.30f;
    private static final float ALPHA_SUB = 0.55f;
    private static final float SKEW = -0.12f;
    private static final float LINE_HEIGHT_RATIO = 1.3f;
    private static final float LINE_GAP_DP = 6f;
    private static final float H_PADDING_DP = 28f;
    private static final float TOP_FADE_DP = 40f;
    private static final float FADE_START = 0.8f;
    private static final long LIFT_MIN_MS = 1100L;
    private static final long EMPH_TAIL_MS = 400L;
    private static final long REANCHOR_DELAY_MS = 5000L;
    private static final long REGRESSION_TOLERANCE_MS = 350L;

    private static final float[] FADE_IN = {0f, 0.3f, 0.55f, 0.78f, 1f};
    private static final float[] FADE_OUT = {1f, 0.85f, 0.62f, 0.48f, 0.45f};

    static float bottomFadeKeep(float t) {
        t = Math.max(0f, Math.min(1f, t));
        for (int i = 1; i < FADE_IN.length; i++) {
            if (t <= FADE_IN[i]) {
                float seg = (t - FADE_IN[i - 1]) / (FADE_IN[i] - FADE_IN[i - 1]);
                float s = seg * seg * (3f - 2f * seg);
                return FADE_OUT[i - 1] + (FADE_OUT[i] - FADE_OUT[i - 1]) * s;
            }
        }
        return FADE_OUT[FADE_OUT.length - 1];
    }

    private static float smoothstep(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    private static class SubRow {
        final String text;
        final float gapDp;
        List<VisualRow> rows;

        SubRow(String text, float gapDp) {
            this.text = text;
            this.gapDp = gapDp;
        }
    }

    /** 均衡断行的一段（照瓜子 WrapSegment：sentenceStart＝段首字符偏移、width＝段宽、text＝段文）。 */
    private static class WrapSegment {
        final int sentenceStart;
        final float width;
        final String text;

        WrapSegment(int sentenceStart, float width, String text) {
            this.sentenceStart = sentenceStart;
            this.width = width;
            this.text = text;
        }

        boolean isSpace() {
            for (int i = 0; i < text.length(); i++) {
                if (!Character.isWhitespace(text.charAt(i))) return false;
            }
            return true;
        }
    }

    /** 一条逻辑行断行后的一个视觉行（字符区间＋显示文本）。 */
    private static class VisualRow {
        final int start;
        final int end;
        final String text;

        VisualRow(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    private final float mDensity;
    private final float mScaled;
    private final Paint mMainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, BlurMaskFilter> mBlurCache = new HashMap<>();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Scroller mScroller;

    private List<RichLyrics.Line> mLines = new ArrayList<>();
    private List<float[]> mLayout = new ArrayList<>(); // 每行 [top, mainHeight, totalHeight]
    private List<List<SubRow>> mSubs = new ArrayList<>();
    private List<List<VisualRow>> mVisualRows = new ArrayList<>(); // 每逻辑行的视觉行（均衡断行结果）
    private List<List<float[]>> mWordPlaces = new ArrayList<>(); // 每行每词 [视觉行序, 行内 x 偏移]（逐字行用）
    // 逐行模糊半径的动画态（§十八时长分支）：每行当前显示半径与在飞的半径动画
    private final Map<Integer, Float> mBlurShown = new HashMap<>();
    private final Map<Integer, ValueAnimator> mBlurAnims = new HashMap<>();
    // 全屏页不供色板（§八供给链：色板仅流光 CD/唱片架主题供）——恒 2.5 特殊态在本页不触发，
    // 分支按 §十八原样保留，主题页供色板时接同一口径
    private final boolean mPaletteSupplied = false;
    private boolean mHasTime = true;
    private long mPosition;
    private Long mPreviewMs;
    private int mCurrent = -1;
    private long mSmoothNow = Long.MIN_VALUE;
    private boolean mResyncNow;
    private long mMonoLineStart = Long.MIN_VALUE;
    private final HashMap<Integer, Float> mMonoWordP = new HashMap<>();
    private float mScrollY;
    private ValueAnimator mScrollAnim;
    private Listener mListener;

    // 手滑浏览：mBrowse 是叠加在锚点滚动上的手动偏移（正＝往后翻看）
    private float mBrowse;
    private final SpringAnimator mSettleSpring = new SpringAnimator(1.16, 237.3);
    private final SpringAnimator mSeekSpring = new SpringAnimator(0.833, 100);
    private final Runnable mReanchor = () -> settleBrowse(mSettleSpring);
    private float mDownX, mDownY, mLastY;
    private boolean mDragging;
    private VelocityTracker mVt;

    private static class RowNode {
        RenderNode node;
        String sig;
    }

    private final Map<Integer, RowNode> mRowNodes = new HashMap<>();
    private int mContentVersion;

    private static class GlowBmp {
        Bitmap bmp;
        float originX;
        float originBase;
    }

    private final Map<String, GlowBmp> mGlowCache = new HashMap<>();
    private final Paint mGlowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private void clearGlowCache() {
        for (GlowBmp g : mGlowCache.values()) {
            if (g.bmp != null && !g.bmp.isRecycled()) g.bmp.recycle();
        }
        mGlowCache.clear();
    }

    // 设置快照（与页内歌词同一批键：字号单键／字重／对齐／主歌词／翻译／音译／模糊档／高级动效）
    private float mFontSp = 24f;
    private int mWeight = 1;
    private int mAlign = 0;
    private int mPrimary = 0;
    private boolean mShowTrans = true;
    private boolean mShowRoma = false;
    private float mBlurCoeff = 1.0f;
    private boolean mAdvancedFx = true;

    public MusicLyricStageView(Context context) {
        this(context, null);
    }

    public MusicLyricStageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
        mScaled = context.getResources().getDisplayMetrics().scaledDensity;
        mScroller = new Scroller(context);
        reloadSettings();
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    public void reloadSettings() {
        mContentVersion++;
        mRowNodes.clear();
        clearBlurState();
        clearGlowCache();
        mFontSp = MusicPlayerSettings.getFullLyricFontSize();
        mWeight = MusicStore.getLyricWeight();
        mAlign = MusicStore.getLyricAlign();
        mPrimary = MusicStore.getLyricPrimary();
        mShowTrans = MusicPlayerSettings.isShowTranslation();
        mShowRoma = MusicPlayerSettings.isShowRomanization();
        mBlurCoeff = MusicPlayerSettings.blurCoefficient();
        mAdvancedFx = MusicStore.isAdvancedFx();
        applyPaintSettings();
        relayout();
        invalidate();
    }

    private void applyPaintSettings() {
        mMainPaint.setTextSize(mFontSp * mScaled);
        mMainPaint.setTextSkewX(SKEW);
        // 字距 −0.2sp（瓜子全屏口径）→ Paint 的 em 口径
        mMainPaint.setLetterSpacing(-0.2f / mFontSp);
        if (mWeight >= 2) {
            mMainPaint.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
            mMainPaint.setFakeBoldText(false);
        } else if (mWeight == 1) {
            mMainPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            mMainPaint.setFakeBoldText(false);
        } else {
            mMainPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            mMainPaint.setFakeBoldText(true);
        }
        mSubPaint.setTextSize(mFontSp * 0.62f * mScaled);
        mSubPaint.setTextSkewX(SKEW);
        mSubPaint.setLetterSpacing(-0.2f / mFontSp);
        mSubPaint.setTypeface(mMainPaint.getTypeface());
    }

    public void setLines(List<RichLyrics.Line> lines) {
        mContentVersion++;
        mRowNodes.clear();
        clearBlurState();
        clearGlowCache();
        mPreviewMs = null;
        cancelBrowse(true);
        mResyncNow = true;
        mSmoothNow = Long.MIN_VALUE;
        mLines = lines == null ? new ArrayList<>() : lines;
        mHasTime = false;
        for (RichLyrics.Line l : mLines) {
            if (l != null && l.main != null && l.main.getTimeMs() > 0) {
                mHasTime = true;
                break;
            }
        }
        if (!mHasTime) mCurrent = -1;
        relayout();
        updateCurrent(true);
        invalidate();
    }

    public void setPosition(long positionMs) {
        if (positionMs < mRawBaseMs - 500) {
            mMonoLineStart = Long.MIN_VALUE;
            mResyncNow = true;
        }
        mPosition = positionMs;
        mRawBaseMs = positionMs;
        mRawSyncElapsed = android.os.SystemClock.elapsedRealtime();
        updateCurrent(false);
        invalidate();
    }

    // ===== 帧时钟（与页内歌词同路：tick 只做同步点，播放中逐帧插值推进逐字） =====
    private long mRawBaseMs;
    private long mRawSyncElapsed;
    private boolean mPlaying;
    private boolean mFrameRunning;
    private final android.view.Choreographer.FrameCallback mFrameCallback = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mPlaying || !isAttachedToWindow()) {
                mFrameRunning = false;
                return;
            }
            updateCurrent(false);
            invalidate();
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private long rawNowMs() {
        if (!mPlaying) return mRawBaseMs;
        return mRawBaseMs + (android.os.SystemClock.elapsedRealtime() - mRawSyncElapsed);
    }

    public void setPlaying(boolean playing) {
        if (mPlaying == playing) return;
        if (playing) {
            mRawBaseMs = mPosition;
            mRawSyncElapsed = android.os.SystemClock.elapsedRealtime();
            mPlaying = true;
            if (!mFrameRunning && isAttachedToWindow()) {
                mFrameRunning = true;
                android.view.Choreographer.getInstance().postFrameCallback(mFrameCallback);
            }
        } else {
            mPosition = rawNowMs();
            mRawBaseMs = mPosition;
            mPlaying = false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mPlaying && !mFrameRunning) {
            mRawBaseMs = mPosition;
            mRawSyncElapsed = android.os.SystemClock.elapsedRealtime();
            mFrameRunning = true;
            android.view.Choreographer.getInstance().postFrameCallback(mFrameCallback);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        android.view.Choreographer.getInstance().removeFrameCallback(mFrameCallback);
        mFrameRunning = false;
        mMainHandler.removeCallbacks(mReanchor);
        mSettleSpring.cancel();
        mSeekSpring.cancel();
        if (mScrollAnim != null) mScrollAnim.cancel();
        clearBlurState();
        super.onDetachedFromWindow();
    }

    private void clearBlurState() {
        for (ValueAnimator a : mBlurAnims.values()) a.cancel();
        mBlurAnims.clear();
        mBlurShown.clear();
    }

    /** 拖动进度条的预览位置（瓜子 scrubPreviewMs 同路）；传 null 回播放位置。 */
    public void setPreviewPosition(Long previewMs) {
        if (previewMs == null ? mPreviewMs == null : previewMs.equals(mPreviewMs)) return;
        mPreviewMs = previewMs;
        mResyncNow = true;
        updateCurrent(false);
        invalidate();
    }

    /** 平滑时间（瓜子口径）：回退 ≤350ms 忽略，seek/切歌/预览经 mResyncNow 显式重同步。 */
    private long now() {
        long base = (mPreviewMs != null ? mPreviewMs : rawNowMs()) + MusicStore.getLyricOffsetMs();
        if (mResyncNow || mSmoothNow == Long.MIN_VALUE
                || base >= mSmoothNow || base < mSmoothNow - REGRESSION_TOLERANCE_MS) {
            mSmoothNow = base;
            mResyncNow = false;
        }
        return mSmoothNow;
    }

    private String mainText(RichLyrics.Line line) {
        if (line == null || line.main == null) return "";
        if (mPrimary == 1 && line.translation != null && !line.translation.isEmpty()) return line.translation;
        if (mPrimary == 2 && line.romanization != null && !line.romanization.isEmpty()) return line.romanization;
        return line.main.getText();
    }

    /** 副行（与页内同一分支与间距：音译 +4dp、翻译/原文 +6dp）。 */
    private List<SubRow> subRows(RichLyrics.Line line) {
        List<SubRow> out = new ArrayList<>();
        if (line == null || line.main == null) return out;
        String original = line.main.getText();
        boolean hasTrans = line.translation != null && !line.translation.isEmpty();
        boolean hasRoma = line.romanization != null && !line.romanization.isEmpty();
        if (mPrimary == 1) {
            if (mShowRoma && hasRoma) out.add(new SubRow(line.romanization, 4f));
            out.add(new SubRow(original, 6f));
        } else if (mPrimary == 2) {
            if (mShowTrans && hasTrans) out.add(new SubRow(line.translation, 6f));
            out.add(new SubRow(original, 6f));
        } else {
            if (mShowRoma && hasRoma) out.add(new SubRow(line.romanization, 4f));
            if (mShowTrans && hasTrans) out.add(new SubRow(line.translation, 6f));
        }
        return out;
    }

    private float lineHeight() {
        return mFontSp * mScaled * LINE_HEIGHT_RATIO;
    }

    private float subLineHeight() {
        return mFontSp * 0.62f * mScaled * LINE_HEIGHT_RATIO;
    }

    /** 舞台内内容左界（行横边距 28dp，瓜子 §八）：舞台铺满整屏，边距在舞台内扣。 */
    private float contentLeft() {
        return dp(H_PADDING_DP);
    }

    /** 可用行宽＝整屏宽 − 左右 28dp。 */
    private float contentWidth() {
        return Math.max(0f, getWidth() - 2 * dp(H_PADDING_DP));
    }

    private void relayout() {
        mLayout = new ArrayList<>();
        mSubs = new ArrayList<>();
        mVisualRows = new ArrayList<>();
        mWordPlaces = new ArrayList<>();
        float avail = contentWidth();
        float lh = lineHeight();
        float subH = subLineHeight();
        float gap = LINE_GAP_DP * mDensity;
        float top = 0f;
        for (RichLyrics.Line line : mLines) {
            String main = mainText(line);
            boolean wordSegs = mPrimary == 0 && line != null && line.main != null
                    && line.main.hasWords() && wordsConcat(line.main).equals(main);
            List<WrapSegment> segs = wordSegs
                    ? wordSegments(line.main, main)
                    : charSegments(main, mMainPaint);
            List<VisualRow> rows = buildVisualRows(main, segs, avail);
            mVisualRows.add(rows);
            mWordPlaces.add(wordSegs ? wordPlacements(line.main, main, rows) : new ArrayList<>());
            float mainH = rows.size() * lh;
            List<SubRow> subs = subRows(line);
            float total = mainH;
            for (SubRow s : subs) {
                s.rows = buildVisualRows(s.text, charSegments(s.text, mSubPaint), avail);
                total += s.gapDp * mDensity + s.rows.size() * subH;
            }
            mLayout.add(new float[]{top, mainH, total});
            mSubs.add(subs);
            top += total + gap;
        }
    }

    private static String wordsConcat(LyricsLine main) {
        StringBuilder sb = new StringBuilder();
        for (LyricsWord w : main.getWords()) sb.append(w.getText());
        return sb.toString();
    }

    /** 段列按词建（照瓜子 measureLine 用例：sentenceStart＝词首字符偏移、宽＝逐字测宽和）。 */
    private List<WrapSegment> wordSegments(LyricsLine main, String text) {
        List<WrapSegment> segs = new ArrayList<>();
        int cursor = 0;
        for (LyricsWord w : main.getWords()) {
            String wt = w.getText();
            int idx = text.indexOf(wt, cursor);
            int start = idx >= 0 ? idx : cursor;
            segs.add(new WrapSegment(start, mMainPaint.measureText(wt), wt));
            cursor = start + wt.length();
        }
        return segs;
    }

    /** 无词行分段（星辰侧适配：瓜子无词行走文本布局自然换行，自绘 Canvas 无此能力，
     *  故按码点分段喂同一 calcBalancedBreaks；算法本体与常量仍是瓜子原样）。 */
    private List<WrapSegment> charSegments(String text, Paint paint) {
        List<WrapSegment> segs = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);
            String s = text.substring(i, i + len);
            segs.add(new WrapSegment(i, paint.measureText(s), s));
            i += len;
        }
        return segs;
    }

    private List<VisualRow> buildVisualRows(String text, List<WrapSegment> segs, float avail) {
        List<VisualRow> rows = new ArrayList<>();
        if (text.isEmpty() || avail <= 0 || segs.isEmpty()) {
            rows.add(new VisualRow(0, text.length(), text));
            return rows;
        }
        List<Integer> breaks = calcBalancedBreaks(segs, avail);
        int start = 0;
        for (int b : breaks) {
            rows.add(new VisualRow(start, b, text.substring(start, b)));
            start = b;
        }
        rows.add(new VisualRow(start, text.length(), text.substring(start)));
        return rows;
    }

    /** 每词落到哪个视觉行、行内 x 偏移（断点在词界上，词不跨行）。 */
    private List<float[]> wordPlacements(LyricsLine main, String text, List<VisualRow> rows) {
        List<float[]> places = new ArrayList<>();
        int cursor = 0;
        for (LyricsWord w : main.getWords()) {
            String wt = w.getText();
            int idx = text.indexOf(wt, cursor);
            int start = idx >= 0 ? idx : cursor;
            int row = 0;
            for (int r = 0; r < rows.size(); r++) {
                if (rows.get(r).start <= start) row = r;
                else break;
            }
            float x = mMainPaint.measureText(text.substring(rows.get(row).start, start));
            places.add(new float[]{row, x});
            cursor = start + wt.length();
        }
        return places;
    }

    // 断点附加奖惩（§十六，W＝最大行宽）：标点 −(0.6W)²／空格 −(0.4W)²／CJK +(0.15W)²／其余 +(0.5W)²
    private static final String WRAP_PUNCT = ",.;:!?，。；：！？、）】》」』’”)]}>~…";
    private static final double WRAP_OVERFLOW_PENALTY = 1000.0;

    private static boolean isCJK(char c) {
        // 瓜子 isCJK 码点区间（§十六亲读）：[3000,3040) [3040,3100) [3400,4DC0) [4E00,A000) [F900,FB00)（十六进制）
        return (c >= 0x3000 && c < 0x3040) || (c >= 0x3040 && c < 0x3100)
                || (c >= 0x3400 && c < 0x4DC0) || (c >= 0x4E00 && c < 0xA000)
                || (c >= 0xF900 && c < 0xFB00);
    }

    private static double wrapBreakAdjustment(WrapSegment prev, double w) {
        String t = prev.text;
        if (!t.isEmpty() && WRAP_PUNCT.indexOf(t.charAt(t.length() - 1)) >= 0) {
            double b = 0.6 * w;
            return -(b * b);
        }
        if (prev.isSpace() || t.endsWith(" ")) {
            double b = 0.4 * w;
            return -(b * b);
        }
        for (int i = 0; i < t.length(); i++) {
            if (isCJK(t.charAt(i))) {
                double p = 0.15 * w;
                return p * p;
            }
        }
        double p = 0.5 * w;
        return p * p;
    }

    /**
     * 瓜子 calcBalancedBreaks（§十六亲读原文移植）：对段列求均衡断点。
     * 一行代价＝(W−行宽)²；单段独占且超宽＝(超宽)²×1000；断点附加按断点前一段
     * （标点/空格/CJK/其余，优先级照原序）。返回各断点处段的 sentenceStart（升序）。
     * 总宽 ≤W 返回空。
     */
    static List<Integer> calcBalancedBreaks(List<WrapSegment> segs, float maxW) {
        List<Integer> out = new ArrayList<>();
        int n = segs.size();
        if (n == 0 || maxW <= 0) return out;
        double[] prefix = new double[n + 1];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + segs.get(i).width;
        if (prefix[n] <= maxW) return out;
        double w = maxW;
        double[] dpCost = new double[n + 1];
        int[] from = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            dpCost[i] = Double.POSITIVE_INFINITY;
            from[i] = -1;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = i - 1; j >= 0; j--) {
                double lineW = prefix[i] - prefix[j];
                if (lineW > w && j < i - 1) break;
                double cost;
                if (lineW > w) {
                    double over = lineW - w;
                    cost = over * over * WRAP_OVERFLOW_PENALTY;
                } else {
                    double slack = w - lineW;
                    cost = slack * slack;
                }
                double total = dpCost[j] + cost;
                if (j > 0) total += wrapBreakAdjustment(segs.get(j - 1), w);
                if (total < dpCost[i]) {
                    dpCost[i] = total;
                    from[i] = j;
                }
            }
        }
        int cur = n;
        while (cur > 0 && from[cur] > 0) {
            out.add(0, segs.get(from[cur]).sentenceStart);
            cur = from[cur];
        }
        return out;
    }

    private float contentHeight() {
        if (mLayout.isEmpty()) return 0f;
        float[] last = mLayout.get(mLayout.size() - 1);
        return last[0] + last[2];
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        relayout();
        updateCurrent(true);
    }

    private int findCurrent(long now) {
        if (!mHasTime || mLines.isEmpty()) return -1;
        int lo = 0, hi = mLines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            long t = mLines.get(mid).main == null ? 0 : mLines.get(mid).main.getTimeMs();
            if (t <= now) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    /** 全屏锚点（瓜子定谳）：clamp(0.22×容器高, 64dp, 120dp)，再封顶 0.28×容器高，减 24dp/2，对当前行中线。 */
    private float anchorY() {
        int h = getHeight();
        if (h <= 0) return 0f;
        float y = Math.max(dp(64), Math.min(dp(120), 0.22f * h));
        y = Math.min(y, 0.28f * h);
        return y - dp(24) / 2f;
    }

    private float targetScrollFor(int idx) {
        if (idx < 0 || idx >= mLayout.size()) return 0f;
        float[] lay = mLayout.get(idx);
        return Math.max(0f, lay[0] + lay[1] / 2f - anchorY());
    }

    private void updateCurrent(boolean instant) {
        int idx = findCurrent(now());
        if (idx == mCurrent && !instant) return;
        mCurrent = idx;
        float target = targetScrollFor(idx);
        if (instant || !mHasTime) {
            if (mScrollAnim != null) mScrollAnim.cancel();
            mScrollY = target;
            return;
        }
        if (mScrollAnim != null) mScrollAnim.cancel();
        final float from = mScrollY;
        mScrollAnim = ValueAnimator.ofFloat(from, target);
        mScrollAnim.setDuration(350);
        mScrollAnim.setInterpolator(BS4_A);
        mScrollAnim.addUpdateListener(a -> {
            mScrollY = (float) a.getAnimatedValue();
            invalidate();
        });
        mScrollAnim.start();
    }

    private BlurMaskFilter blurFor(float radiusPx) {
        int key = Math.round(radiusPx * 2f);
        if (key <= 0) return null;
        BlurMaskFilter f = mBlurCache.get(key);
        if (f == null) {
            f = new BlurMaskFilter(key / 2f, BlurMaskFilter.Blur.NORMAL);
            mBlurCache.put(key, f);
        }
        return f;
    }

    /** 行内 x（内容盒相对坐标：盒宽＝整屏宽 −左右 28dp；直接绘制时宿主先平移到 contentLeft）。 */
    private float alignX(String text, Paint p) {
        float w = contentWidth();
        float tw = p.measureText(text);
        switch (mAlign) {
            case 1:
                return Math.max(0, (w - tw) / 2f);
            case 2:
                return Math.max(0, w - tw);
            default:
                return 0;
        }
    }

    /** 与当前行同一时间组（同 timeMs 的同唱/对唱合成行）。 */
    private boolean sameTimeGroup(int i) {
        if (mCurrent < 0 || i == mCurrent || i >= mLines.size() || mCurrent >= mLines.size()) return false;
        RichLyrics.Line a = mLines.get(i);
        RichLyrics.Line b = mLines.get(mCurrent);
        return a != null && b != null && a.main != null && b.main != null
                && a.main.getTimeMs() == b.main.getTimeMs();
    }

    /** 浏览派生态（与淡出共用同一派生源的那道旗在本页的可观察量）：拖动中/惯性中/偏移未归零。 */
    private boolean browsingDerived() {
        return mDragging || !mScroller.isFinished() || mBrowse != 0f;
    }

    /**
     * 逐行模糊目标半径（§十八结案口径）：B 组主曲线 min(|距|×2.5, 16)；恒 2.5 特殊态
     * （色板在场且组/行索引 0）先于主曲线；档位倍率最后统一乘；覆写旗三道任一成立→0：
     * ①浏览派生态 ②同时间组/对唱合成旗 ③预览位置在场（星辰侧状态接法，瓜子 pos36 侧）。
     * 结果 0.5px 量化（与既有口径同）。
     */
    private float blurTargetRadius(int i) {
        if (mBlurCoeff <= 0f || mCurrent < 0 || i == mCurrent) return 0f;
        if (sameTimeGroup(i)) return 0f;
        float r;
        if (mPaletteSupplied && i == 0) r = 2.5f;
        else r = Math.min(Math.abs(i - mCurrent) * 2.5f, 16f);
        r *= mBlurCoeff;
        if (browsingDerived()) return 0f;
        if (mPreviewMs != null) return 0f;
        return Math.round(r * 2f) / 2f;
    }

    /** 模糊半径变化时长（§十八）：派生旗真且同时间组旗 0 且两状态旗皆假→400ms，其余 200ms。 */
    private long blurAnimDurationMs(int i) {
        boolean derived = mCurrent >= 0 && mHasTime;
        return derived && !sameTimeGroup(i) && mPreviewMs == null && !browsingDerived() ? 400L : 200L;
    }

    /** 当前显示半径：向目标半径按 §十八时长＋bs4.a 缓动过渡（节点按 0.5px 量化重录）。 */
    private float blurShownRadius(int i, float target) {
        if (target <= 0f) {
            ValueAnimator old = mBlurAnims.remove(i);
            if (old != null) old.cancel();
            mBlurShown.put(i, 0f);
            return 0f;
        }
        Float shown = mBlurShown.get(i);
        if (shown == null) {
            mBlurShown.put(i, target);
            return target;
        }
        if (Math.abs(shown - target) > 0.01f && !mBlurAnims.containsKey(i)) {
            final int idx = i;
            ValueAnimator a = ValueAnimator.ofFloat(shown, target);
            a.setDuration(blurAnimDurationMs(i));
            a.setInterpolator(BS4_A);
            a.addUpdateListener(anim -> {
                mBlurShown.put(idx, (float) anim.getAnimatedValue());
                invalidate();
            });
            a.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mBlurAnims.remove(idx);
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    mBlurAnims.remove(idx);
                }
            });
            mBlurAnims.put(i, a);
            a.start();
        }
        Float cur = mBlurShown.get(i);
        return cur == null ? target : Math.round(cur * 2f) / 2f;
    }

    /** 行淡出：上＝smoothstep（行顶/40dp 顶渐隐带），下＝行底过 0.8×高起始线后乘 bottomFadeKeep（地板 0.45）。 */
    private float fadeFor(float top, float blockH, int h) {
        float fade = smoothstep(top / Math.max(1f, dp(TOP_FADE_DP)));
        float startY = FADE_START * h;
        float bottom = top + blockH;
        if (bottom > startY && h > startY) {
            fade *= bottomFadeKeep((bottom - startY) / (h - startY));
        }
        return fade;
    }

    private float effScroll() {
        float max = Math.max(0f, contentHeight() - anchorY());
        float v = mScrollY + mBrowse;
        return Math.max(0f, Math.min(max, v));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (mLines.isEmpty()) {
            // 与页内歌词同口径（体检低）：无词补占位字，不摆整片空白。
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTextSize(dp(15));
            p.setColor(0x66FFFFFF);
            p.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(getContext().getString(
                    com.fongmi.android.tv.R.string.music_no_lyric), w / 2f, h / 2f, p);
            return;
        }
        long now = now();
        float scroll = effScroll();

        for (int i = 0; i < mLines.size(); i++) {
            float[] lay = mLayout.get(i);
            float top = lay[0] - scroll;
            float bottom = top + lay[2];
            if (bottom < -dp(60) || top > h + dp(60)) continue;
            float fade = fadeFor(top, lay[2], h);

            // 模糊半径（§十八：B 组主曲线＋覆写旗＋特殊态出目标，显示半径按 400/200ms 时长过渡）
            float radius = blurShownRadius(i, blurTargetRadius(i));

            if (radius > 0f && Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()) {
                drawRowViaNode(canvas, i, top, fade, radius);
            } else {
                canvas.save();
                canvas.translate(contentLeft(), 0);
                drawRowContent(canvas, i, top, fade, now, radius);
                canvas.restore();
            }
        }
    }

    /** 逐行 RenderEffect 模糊（与页内同机制）：行内容录进独立 RenderNode，滚动只改节点位置。
     *  节点盒＝内容盒（宽＝整屏宽 −左右 28dp），节点摆在 contentLeft 处，录制用盒内相对坐标。 */
    private void drawRowViaNode(Canvas canvas, int i, float top, float fade, float radius) {
        float[] lay = mLayout.get(i);
        int nodeW = Math.max(1, (int) Math.ceil(contentWidth()));
        int nodeH = Math.max(1, (int) Math.ceil(lay[2]));
        String sig = i + "|" + (i < mCurrent ? 1 : 0) + "|" + radius + "|" + nodeW + "|" + mContentVersion;
        RowNode rn = mRowNodes.get(i);
        if (rn == null || !sig.equals(rn.sig) || !rn.node.hasDisplayList()) {
            if (rn == null) {
                rn = new RowNode();
                rn.node = new RenderNode("stageLyricRow" + i);
                mRowNodes.put(i, rn);
            }
            rn.sig = sig;
            rn.node.setPosition(0, 0, nodeW, nodeH);
            Canvas rec = rn.node.beginRecording(nodeW, nodeH);
            drawRowContent(rec, i, 0f, 1f, now(), 0f);
            rn.node.endRecording();
            rn.node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL));
        }
        int topI = Math.round(top);
        int leftI = Math.round(contentLeft());
        rn.node.setPosition(leftI, topI, leftI + nodeW, topI + nodeH);
        rn.node.setAlpha(Math.max(0f, Math.min(1f, fade)));
        canvas.drawRenderNode(rn.node);
    }

    private void drawRowContent(Canvas canvas, int i, float top, float fade, long now, float maskBlurRadius) {
        RichLyrics.Line line = mLines.get(i);
        String main = mainText(line);

        mMainPaint.setMaskFilter(null);
        mSubPaint.setMaskFilter(null);
        if (maskBlurRadius > 0f) {
            BlurMaskFilter bf = blurFor(maskBlurRadius);
            mMainPaint.setMaskFilter(bf);
            mSubPaint.setMaskFilter(bf);
        }

        List<VisualRow> rows = i < mVisualRows.size() ? mVisualRows.get(i) : new ArrayList<>();
        if (rows.isEmpty()) rows.add(new VisualRow(0, main.length(), main));
        float lh = lineHeight();
        if (i == mCurrent && mPrimary == 0 && line.main != null && line.main.hasWords()
                && i < mWordPlaces.size() && !mWordPlaces.get(i).isEmpty()) {
            drawCurrentLine(canvas, i, line.main, now, fade, top);
        } else if (i == mCurrent || (mCurrent >= 0 && i < mCurrent)) {
            mMainPaint.setColor(COLOR_SUNG);
            mMainPaint.setAlpha(Math.round(255 * fade));
            for (int r = 0; r < rows.size(); r++) {
                VisualRow vr = rows.get(r);
                canvas.drawText(vr.text, alignX(vr.text, mMainPaint),
                        top + r * lh - mMainPaint.ascent(), mMainPaint);
            }
        } else {
            mMainPaint.setColor(0xFFFFFFFF);
            mMainPaint.setAlpha(Math.round(255 * ALPHA_NONCUR * fade));
            for (int r = 0; r < rows.size(); r++) {
                VisualRow vr = rows.get(r);
                canvas.drawText(vr.text, alignX(vr.text, mMainPaint),
                        top + r * lh - mMainPaint.ascent(), mMainPaint);
            }
        }
        mMainPaint.setMaskFilter(null);

        List<SubRow> subs = mSubs.get(i);
        if (subs != null && !subs.isEmpty()) {
            float subTop = top + mLayout.get(i)[1];
            for (SubRow s : subs) {
                subTop += s.gapDp * mDensity;
                mSubPaint.setColor(0xFFFFFFFF);
                mSubPaint.setAlpha(Math.round(255 * ALPHA_SUB * fade));
                List<VisualRow> srows = s.rows;
                if (srows == null || srows.isEmpty()) {
                    float subBase = subTop - mSubPaint.ascent();
                    canvas.drawText(s.text, alignX(s.text, mSubPaint), subBase, mSubPaint);
                    subTop += subLineHeight();
                } else {
                    for (VisualRow vr : srows) {
                        float subBase = subTop - mSubPaint.ascent();
                        canvas.drawText(vr.text, alignX(vr.text, mSubPaint), subBase, mSubPaint);
                        subTop += subLineHeight();
                    }
                }
            }
            mSubPaint.setMaskFilter(null);
        }
    }

    /** 瓜子 emphasizeGlow 强度（与页内同一公式）。 */
    private static float emphIntensity(long durMs, boolean last) {
        float sec = Math.max(1f, Math.min(8f, durMs / 1000f));
        float c = Math.min(0.85f, (sec - 1f) * 0.045f + 0.6f);
        if (last) c = Math.min(0.95f, c * 1.1f);
        return c;
    }

    /** 瓜子 emphasizeGlow 模糊半径（与页内同一公式）。 */
    private static float emphRadiusPx(long durMs, boolean last, float fontPx) {
        float sec = Math.max(1f, Math.min(8f, durMs / 1000f));
        float ratio = Math.min(0.5f, (sec - 1f) * 0.012f + 0.3f);
        if (last) ratio = Math.min(0.6f, ratio * 1.15f);
        return ratio * fontPx;
    }

    /** 字形边缘光晕位图（与页内同一 createGlyphEdgeGlow 配方）。 */
    private GlowBmp glowFor(String word, float radiusPx, float fontPx) {
        String key = word + "@" + Math.round(radiusPx) + "@" + Math.round(fontPx);
        GlowBmp cached = mGlowCache.get(key);
        if (cached != null) return cached;
        if (mGlowCache.size() > 32) clearGlowCache();
        GlowBmp out = new GlowBmp();
        mGlowCache.put(key, out);
        Paint paint = new Paint(mMainPaint);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha(255);
        paint.setMaskFilter(null);
        paint.setShadowLayer(0, 0, 0, 0);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float tw = paint.measureText(word);
        int pad = Math.max(8, (int) Math.ceil(radiusPx) + 4);
        int gw = Math.max(1, (int) Math.ceil(tw) + pad * 2);
        int gh = Math.max(1, (int) Math.ceil(fm.descent - fm.ascent) + pad * 2);
        Bitmap glyph = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888);
        Canvas gc = new Canvas(glyph);
        float base = pad - fm.ascent;
        gc.drawText(word, pad, base, paint);
        Paint blurPaint = new Paint();
        blurPaint.setMaskFilter(new BlurMaskFilter(Math.max(1f, radiusPx), BlurMaskFilter.Blur.NORMAL));
        int[] offset = new int[2];
        Bitmap alphaBmp = glyph.extractAlpha(blurPaint, offset);
        if (alphaBmp == null) {
            glyph.recycle();
            return out;
        }
        Bitmap bmp = Bitmap.createBitmap(alphaBmp.getWidth(), alphaBmp.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas oc = new Canvas(bmp);
        Paint tint = new Paint(Paint.FILTER_BITMAP_FLAG);
        tint.setColorFilter(new PorterDuffColorFilter(COLOR_SUNG, PorterDuff.Mode.SRC_IN));
        oc.drawBitmap(alphaBmp, 0, 0, tint);
        Paint cut = new Paint();
        cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        oc.drawBitmap(glyph, -offset[0], -offset[1], cut);
        alphaBmp.recycle();
        glyph.recycle();
        out.bmp = bmp;
        out.originX = pad - offset[0];
        out.originBase = base - offset[1];
        return out;
    }

    /** 当前行逐字（与页内同一唱法：已唱色覆写＋前沿羽化＋上浮＋长音光晕）。
     *  换行后逐词按均衡断行的落位画：每词回到自己视觉行的行首基准＋行内偏移，基线随行推进。 */
    private void drawCurrentLine(Canvas canvas, int lineIndex, LyricsLine main, long now, float fade, float top) {
        List<LyricsWord> words = main.getWords();
        List<VisualRow> rows = mVisualRows.get(lineIndex);
        List<float[]> places = mWordPlaces.get(lineIndex);
        float lh = lineHeight();
        long lineStart = main.getTimeMs();
        float em = mFontSp * mScaled;
        if (lineStart != mMonoLineStart) {
            mMonoLineStart = lineStart;
            mMonoWordP.clear();
        }
        float feather = 0.25f * em;
        boolean prevWasFrontier = false;
        float frontierX = 0f;
        for (int wi = 0; wi < words.size(); wi++) {
            LyricsWord word = words.get(wi);
            boolean isLastWord = wi == words.size() - 1;
            String wt = word.getText();
            float ww = mMainPaint.measureText(wt);
            float[] place = wi < places.size() ? places.get(wi) : null;
            int vrow = place != null ? (int) place[0] : 0;
            VisualRow vr = rows != null && vrow < rows.size() ? rows.get(vrow)
                    : (rows != null && !rows.isEmpty() ? rows.get(0) : new VisualRow(0, 0, ""));
            float x = alignX(vr.text, mMainPaint) + (place != null ? place[1] : 0f);
            float baseline = top + vrow * lh - mMainPaint.ascent();
            long ws = lineStart + word.getStartOffsetMs();
            long wd = Math.max(1, word.getDurationMs());
            long elapsed = now - ws;
            float p = Math.max(0f, Math.min(1f, (float) elapsed / (float) wd));
            Float monoPrev = mMonoWordP.get(wi);
            if (monoPrev != null && p < monoPrev) p = monoPrev;
            mMonoWordP.put(wi, p);
            boolean isFrontier = p > 0f && p < 1f;
            if (isFrontier) frontierX = x + ww * p;

            float lift = 0f;
            if (mAdvancedFx && elapsed > 0) {
                float lp = Math.max(0f, Math.min(1f, (float) elapsed / (float) Math.max(LIFT_MIN_MS, wd)));
                float e = 1f - (float) Math.pow(1f - lp, 3);
                lift = -0.09f * em * e;
            }

            float emph = 0f;
            if (mAdvancedFx && wd >= 1000 && elapsed > 0 && elapsed < wd + EMPH_TAIL_MS) {
                emph = (float) Math.sin(Math.PI * Math.min(1f, (float) elapsed / (float) (wd + EMPH_TAIL_MS)));
            }
            float scale = 1f + 0.2f * emph;
            float floatY = -0.05f * em * emph;

            canvas.save();
            if (emph > 0f) {
                canvas.translate(x + ww / 2f, baseline);
                canvas.scale(scale, scale);
                canvas.translate(-(x + ww / 2f), -baseline);
            }
            mMainPaint.setColor(COLOR_UNSUNG);
            mMainPaint.setAlpha(Math.round(255 * ALPHA_UNSUNG * fade));
            mMainPaint.setShadowLayer(0, 0, 0, 0);
            canvas.drawText(wt, x, baseline + lift + floatY, mMainPaint);
            if (emph > 0f) {
                GlowBmp glow = glowFor(wt, emphRadiusPx(wd, isLastWord, em), em);
                if (glow.bmp != null) {
                    mGlowPaint.setAlpha(Math.round(255 * fade * emphIntensity(wd, isLastWord) * emph));
                    canvas.drawBitmap(glow.bmp, x - glow.originX,
                            (baseline + lift + floatY) - glow.originBase, mGlowPaint);
                }
            }
            if (p >= 1f) {
                mMainPaint.setColor(COLOR_SUNG);
                mMainPaint.setAlpha(Math.round(255 * fade));
                mMainPaint.setShader(null);
                canvas.drawText(wt, x, baseline + lift + floatY, mMainPaint);
            } else if (isFrontier || prevWasFrontier) {
                mMainPaint.setColor(COLOR_SUNG);
                mMainPaint.setAlpha(Math.round(255 * fade));
                mMainPaint.setShader(new android.graphics.LinearGradient(
                        frontierX - feather / 2f, 0, frontierX + feather / 2f, 0,
                        new int[]{COLOR_SUNG, 0x00EAEAEA},
                        new float[]{0f, 1f}, android.graphics.Shader.TileMode.CLAMP));
                canvas.drawText(wt, x, baseline + lift + floatY, mMainPaint);
                mMainPaint.setShader(null);
            }
            mMainPaint.setMaskFilter(null);
            prevWasFrontier = isFrontier;
            canvas.restore();
        }
    }

    private float dp(float v) {
        return v * mDensity;
    }

    // ---------- 手滑浏览（瓜子口径：拖动不钳偏移，松手 |偏移|>视口×1.5 直接 snap、否则惯性＋5000ms 回锚；点行 seek 立即回锚） ----------
    private void cancelBrowse(boolean instant) {
        mMainHandler.removeCallbacks(mReanchor);
        mSettleSpring.cancel();
        mSeekSpring.cancel();
        if (!mScroller.isFinished()) mScroller.abortAnimation();
        if (instant) mBrowse = 0f;
    }

    private void settleBrowse(SpringAnimator spring) {
        if (mBrowse == 0f) return;
        final float from = mBrowse;
        spring.start(from, 0, new SpringAnimator.Listener() {
            @Override
            public void onUpdate(float value) {
                mBrowse = value;
                invalidate();
            }

            @Override
            public void onEnd() {
                mBrowse = 0f;
                invalidate();
            }
        });
    }

    private void scheduleReanchor() {
        mMainHandler.removeCallbacks(mReanchor);
        mMainHandler.postDelayed(mReanchor, REANCHOR_DELAY_MS);
    }

    /** 松手判定（瓜子口径）：|偏移| 超过视口 1.5 倍直接 snap 回锚，不走弹簧不排回锚。 */
    private boolean browseBeyondSnap() {
        return Math.abs(mBrowse) > Math.max(1f, getHeight() * 1.5f);
    }

    private void snapBrowse() {
        cancelBrowse(false);
        mBrowse = 0f;
        invalidate();
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            mBrowse = mScroller.getCurrY();
            // 惯性帧靠动画帧自驱（暂停态没有歌词帧时钟，不能指望宿主 tick 喂帧）
            postInvalidateOnAnimation();
        } else if (browseBeyondSnap()) {
            snapBrowse();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = e.getX();
                mDownY = e.getY();
                mLastY = e.getY();
                mDragging = false;
                cancelBrowse(false);
                if (mVt != null) mVt.recycle();
                mVt = VelocityTracker.obtain();
                mVt.addMovement(e);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (mVt != null) mVt.addMovement(e);
                float dy = e.getY() - mDownY;
                if (!mDragging && Math.abs(dy) > dp(8) && mHasTime) {
                    mDragging = true;
                    mLastY = e.getY();
                }
                if (mDragging) {
                    mBrowse += (mLastY - e.getY());
                    mLastY = e.getY();
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float vy = 0;
                if (mVt != null) {
                    mVt.addMovement(e);
                    mVt.computeCurrentVelocity(1000);
                    vy = mVt.getYVelocity();
                    mVt.recycle();
                    mVt = null;
                }
                if (mDragging) {
                    mDragging = false;
                    if (browseBeyondSnap()) {
                        snapBrowse();
                    } else {
                        if (Math.abs(vy) > 60f && e.getActionMasked() == MotionEvent.ACTION_UP) {
                            int lim = Integer.MAX_VALUE / 2;
                            mScroller.fling(0, Math.round(mBrowse), 0, Math.round(-vy),
                                    0, 0, -lim, lim);
                            postInvalidateOnAnimation();
                        }
                        scheduleReanchor();
                    }
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    float dx = e.getX() - mDownX, ddy = e.getY() - mDownY;
                    if (Math.abs(dx) < dp(12) && Math.abs(ddy) < dp(12) && mListener != null) {
                        float y = e.getY() + effScroll();
                        for (int i = 0; i < mLayout.size(); i++) {
                            float[] lay = mLayout.get(i);
                            if (y >= lay[0] && y <= lay[0] + lay[2]) {
                                RichLyrics.Line l = mLines.get(i);
                                if (l != null && l.main != null) {
                                    // 点行 seek 立即回锚（lyricSeekPosSpring 0.833/100）
                                    mResyncNow = true;
                                    settleBrowse(mSeekSpring);
                                    mListener.onLineTap(l.main.getTimeMs());
                                }
                                break;
                            }
                        }
                    }
                }
                return true;
            }
            default:
                return true;
        }
    }
}
