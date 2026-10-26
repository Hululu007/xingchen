package com.fongmi.android.tv.ui.music.view;

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
 * 行横边距由宿主布局给 28dp；默认字号读全 App 歌词字号单键（瓜子全屏默认 24sp、
 * 星辰字号单键收口后默认 22——按星辰既定收口走同键，不另起一份）；行高 ×1.3、字距 −0.2sp、行距 6dp；
 * 字号钳制走同键的 14–32（瓜子全屏钳 16–40 的差记在交付对照表）；
 * 锚点＝clamp(0.22×容器高, 64dp, 120dp) 再封顶 0.28×容器高、减 24dp/2，对当前行中线；
 * 顶部渐隐带 40dp（smoothstep）；下方淡出起始 0.8×高、bottomFadeKeep 分段表（地板 0.45）；
 * 色（沉浸深色供给，与页内歌词同一条供给链）：已唱 #FFEAEAEA、当前行未唱 白 0.42、
 * 非当前 白 0.30、副行 白 0.55；逐字上浮 −0.09em／缓动分母 max(1100ms, 字长）、长音光晕
 * （与页内同一 createGlyphEdgeGlow 配方）＋行末 400ms 拖尾；
 * 逐行模糊 B 组（全屏传真）：上下均 min(|距|×2.5, 16) ×档位系数，0.5px 量化、RenderEffect DECAL；
 * 当前行判定：二分取 timeMs≤now 最后一行，平滑时间回退 ≤350ms 忽略；纯文本无当前行、静态不滚；
 * 手滑浏览：偏移叠加 clamp（±1.5×视口）、|速度|>60 走惯性、松手 5000ms 回锚
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

        SubRow(String text, float gapDp) {
            this.text = text;
            this.gapDp = gapDp;
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
        clearGlowCache();
        mFontSp = MusicPlayerSettings.getCoverLyricFontSize();
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
        super.onDetachedFromWindow();
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

    private void relayout() {
        mLayout = new ArrayList<>();
        mSubs = new ArrayList<>();
        int w = getWidth();
        float lh = lineHeight();
        float subH = subLineHeight();
        float gap = LINE_GAP_DP * mDensity;
        float top = 0f;
        for (RichLyrics.Line line : mLines) {
            String main = mainText(line);
            int rows = 1;
            if (w > 0 && !main.isEmpty()) {
                float tw = mMainPaint.measureText(main);
                rows = Math.max(1, Math.min(2, (int) Math.ceil(tw / Math.max(1, w))));
            }
            float mainH = rows * lh;
            List<SubRow> subs = subRows(line);
            float total = mainH;
            for (SubRow s : subs) total += s.gapDp * mDensity + subH;
            mLayout.add(new float[]{top, mainH, total});
            mSubs.add(subs);
            top += total + gap;
        }
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

    private float alignX(String text, Paint p) {
        int w = getWidth();
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

            // 模糊半径（B 组：上下均 min(|距|×2.5, 16)×档位系数，0.5px 量化）
            float radius = 0f;
            if (mBlurCoeff > 0 && i != mCurrent && mCurrent >= 0) {
                float r = Math.min(Math.abs(i - mCurrent) * 2.5f, 16f);
                radius = Math.round(r * mBlurCoeff * 2f) / 2f;
            }

            if (radius > 0f && Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()) {
                drawRowViaNode(canvas, i, top, fade, radius, w);
            } else {
                drawRowContent(canvas, i, top, fade, now, radius);
            }
        }
    }

    /** 逐行 RenderEffect 模糊（与页内同机制）：行内容录进独立 RenderNode，滚动只改节点位置。 */
    private void drawRowViaNode(Canvas canvas, int i, float top, float fade, float radius, int w) {
        float[] lay = mLayout.get(i);
        int nodeH = Math.max(1, (int) Math.ceil(lay[2]));
        String sig = i + "|" + (i < mCurrent ? 1 : 0) + "|" + radius + "|" + w + "|" + mContentVersion;
        RowNode rn = mRowNodes.get(i);
        if (rn == null || !sig.equals(rn.sig) || !rn.node.hasDisplayList()) {
            if (rn == null) {
                rn = new RowNode();
                rn.node = new RenderNode("stageLyricRow" + i);
                mRowNodes.put(i, rn);
            }
            rn.sig = sig;
            rn.node.setPosition(0, 0, w, nodeH);
            Canvas rec = rn.node.beginRecording(w, nodeH);
            drawRowContent(rec, i, 0f, 1f, now(), 0f);
            rn.node.endRecording();
            rn.node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL));
        }
        int topI = Math.round(top);
        rn.node.setPosition(0, topI, w, topI + nodeH);
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

        float baseline = top - mMainPaint.ascent();
        if (i == mCurrent && mPrimary == 0 && line.main != null && line.main.hasWords()) {
            drawCurrentLine(canvas, line.main, now, fade, baseline);
        } else if (i == mCurrent) {
            mMainPaint.setColor(COLOR_SUNG);
            mMainPaint.setAlpha(Math.round(255 * fade));
            canvas.drawText(main, alignX(main, mMainPaint), baseline, mMainPaint);
        } else if (mCurrent >= 0 && i < mCurrent) {
            mMainPaint.setColor(COLOR_SUNG);
            mMainPaint.setAlpha(Math.round(255 * fade));
            canvas.drawText(main, alignX(main, mMainPaint), baseline, mMainPaint);
        } else {
            mMainPaint.setColor(0xFFFFFFFF);
            mMainPaint.setAlpha(Math.round(255 * ALPHA_NONCUR * fade));
            canvas.drawText(main, alignX(main, mMainPaint), baseline, mMainPaint);
        }
        mMainPaint.setMaskFilter(null);

        List<SubRow> subs = mSubs.get(i);
        if (subs != null && !subs.isEmpty()) {
            float subTop = top + mLayout.get(i)[1];
            for (SubRow s : subs) {
                subTop += s.gapDp * mDensity;
                float subBase = subTop - mSubPaint.ascent();
                mSubPaint.setColor(0xFFFFFFFF);
                mSubPaint.setAlpha(Math.round(255 * ALPHA_SUB * fade));
                canvas.drawText(s.text, alignX(s.text, mSubPaint), subBase, mSubPaint);
                subTop += subLineHeight();
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

    /** 当前行逐字（与页内同一唱法：已唱色覆写＋前沿羽化＋上浮＋长音光晕）。 */
    private void drawCurrentLine(Canvas canvas, LyricsLine main, long now, float fade, float baseline) {
        List<LyricsWord> words = main.getWords();
        String text = main.getText();
        float x = alignX(text, mMainPaint);
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
            x += ww;
        }
    }

    private float dp(float v) {
        return v * mDensity;
    }

    // ---------- 手滑浏览（瓜子口径：偏移叠加 clamp、惯性、松手 5000ms 回锚；点行 seek 立即回锚） ----------
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

    private float clampBrowse(float v) {
        float limit = Math.max(1f, getHeight() * 1.5f);
        return Math.max(-limit, Math.min(limit, v));
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            mBrowse = clampBrowse(mScroller.getCurrY());
            // 惯性帧靠动画帧自驱（暂停态没有歌词帧时钟，不能指望宿主 tick 喂帧）
            postInvalidateOnAnimation();
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
                    mBrowse = clampBrowse(mBrowse + (mLastY - e.getY()));
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
                    if (Math.abs(vy) > 60f && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        float limit = Math.max(1f, getHeight() * 1.5f);
                        mScroller.fling(0, Math.round(mBrowse), 0, Math.round(-vy),
                                0, 0, Math.round(-limit), Math.round(limit));
                        postInvalidateOnAnimation();
                    }
                    scheduleReanchor();
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
