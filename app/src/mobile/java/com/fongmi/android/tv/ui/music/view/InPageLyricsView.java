package com.fongmi.android.tv.ui.music.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

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
 * 经典页内歌词（瓜子 CoverCurrentLyric 引擎口径，数值照对照表回补 1–7）：
 * 默认 22sp（读封面歌词字号设置）、行高 ×4/3、行距 20dp、字距不指定（平台默认）；
 * 副行字号＝主字号 ×0.62、行高 ×4/3，间距按类型累加（音译 +4dp、翻译/原文 +6dp）；
 * 锚点：当前行块顶＋行内对齐点（lyricAnchor 设置 top/center/bottom）− 0.75×行高；
 * 淡出：上方 smoothstep（行顶/0.75×行高），下方起始 0.9×高、bottomFadeKeep 分段表（地板 0.45）；
 * 色（沉浸深色供给）：已唱 #FFEAEAEA、当前行未唱 白 0.42、非当前 白 0.30、副行 白 0.55，不带自编系数；
 * 逐字上浮 −0.09em、缓动分母 max(1100ms, 字长）；长音（≥1000ms）光晕＋放大上限 1.2＋−0.05em 浮动＋行末 400ms 拖尾；
 * 无逐字行不估已唱比例（整行按未唱色，唱过由行级已唱色承担）；
 * 逐行模糊 A 组（上 min(2+k,5)、下 min(k+1,5)）×档位系数：API 31+ 逐行 RenderNode＋
 * RenderEffect 模糊（半径 round(v×2)/2 量化、DECAL，与瓜子同机制）；低版本回落 BlurMaskFilter。
 * 拖动进度条时歌词跟预览位置走（瓜子 scrubPreviewMs 同路：预览行成当前行、滚动跟随）。
 * 星辰产品项：歌词带一点斜（skew −0.12，他定）；点行＝跳到那行唱（他定）。
 */
public class InPageLyricsView extends View {

    public interface Listener {
        void onLineTap(long timeMs);
    }

    private static final PathInterpolator BS4_A = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final int COLOR_SUNG = 0xFFEAEAEA;
    private static final float ALPHA_CUR_UNSUNG = 0.42f;
    private static final float ALPHA_NONCUR = 0.30f;
    private static final float ALPHA_SUB = 0.55f;
    private static final float SKEW = -0.12f;
    private static final float FADE_START = 0.9f;
    private static final long LIFT_MIN_MS = 1100L;
    private static final long EMPH_TAIL_MS = 400L;

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

    private List<RichLyrics.Line> mLines = new ArrayList<>();
    private List<float[]> mLayout = new ArrayList<>(); // 每行 [top, mainHeight, totalHeight]
    private List<List<SubRow>> mSubs = new ArrayList<>();
    private boolean mHasTime = true;
    private long mPosition;
    private Long mPreviewMs;
    private int mCurrent = -1;
    private float mScrollY;
    private ValueAnimator mScrollAnim;
    private Listener mListener;

    // 逐行 RenderNode 模糊缓存（API 31+）：按行号存节点，签名变了才重录
    private static class RowNode {
        RenderNode node;
        String sig;
    }

    private final Map<Integer, RowNode> mRowNodes = new HashMap<>();
    private int mContentVersion;

    // 设置快照
    private float mFontSp = 22f;
    private int mWeight = 1;
    private int mAlign = 0;
    private int mPrimary = 0;
    private boolean mShowTrans = true;
    private boolean mShowRoma = false;
    private float mBlurCoeff = 1.0f;
    private float mAnchorFraction = 0f;
    private boolean mAdvancedFx = true;

    private float mDownX, mDownY;

    public InPageLyricsView(Context context) {
        this(context, null);
    }

    public InPageLyricsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
        mScaled = context.getResources().getDisplayMetrics().scaledDensity;
        reloadSettings();
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    public void reloadSettings() {
        mContentVersion++;
        mRowNodes.clear();
        mFontSp = MusicPlayerSettings.getCoverLyricFontSize();
        mWeight = MusicStore.getLyricWeight();
        mAlign = MusicStore.getLyricAlign();
        mPrimary = MusicStore.getLyricPrimary();
        mShowTrans = MusicPlayerSettings.isShowTranslation();
        mShowRoma = MusicPlayerSettings.isShowRomanization();
        mBlurCoeff = MusicPlayerSettings.blurCoefficient();
        mAnchorFraction = MusicPlayerSettings.lyricAnchorFraction();
        mAdvancedFx = MusicStore.isAdvancedFx();
        applyPaintSettings();
        relayout();
        invalidate();
    }

    private void applyPaintSettings() {
        mMainPaint.setTextSize(mFontSp * mScaled);
        mMainPaint.setTextSkewX(SKEW);
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
        mSubPaint.setTypeface(mMainPaint.getTypeface());
    }

    public void setLines(List<RichLyrics.Line> lines) {
        mContentVersion++;
        mRowNodes.clear();
        mPreviewMs = null;
        mLines = lines == null ? new ArrayList<>() : lines;
        mHasTime = false;
        for (RichLyrics.Line l : mLines) {
            if (l != null && l.main != null && l.main.getTimeMs() > 0) {
                mHasTime = true;
                break;
            }
        }
        if (!mHasTime) {
            mCurrent = -1;
        }
        relayout();
        updateCurrent(true);
        invalidate();
    }

    public List<RichLyrics.Line> getLines() {
        return mLines;
    }

    public void setPosition(long positionMs) {
        mPosition = positionMs;
        updateCurrent(false);
        invalidate();
    }

    /** 拖动进度条的预览位置（瓜子 scrubPreviewMs）：非空时当前行/逐字/滚动全跟预览走；传 null 回到播放位置。 */
    public void setPreviewPosition(Long previewMs) {
        if (previewMs == null ? mPreviewMs == null : previewMs.equals(mPreviewMs)) return;
        mPreviewMs = previewMs;
        updateCurrent(false);
        invalidate();
    }

    private long now() {
        long base = mPreviewMs != null ? mPreviewMs : mPosition;
        return base + MusicStore.getLyricOffsetMs();
    }

    private String mainText(RichLyrics.Line line) {
        if (line == null || line.main == null) return "";
        if (mPrimary == 1 && line.translation != null && !line.translation.isEmpty()) return line.translation;
        if (mPrimary == 2 && line.romanization != null && !line.romanization.isEmpty()) return line.romanization;
        return line.main.getText();
    }

    /** 副行（照瓜子 measureCoverCurrentBlock 的分支与间距）：音译 +4dp、翻译/原文 +6dp。 */
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
        return mFontSp * mScaled * 4f / 3f;
    }

    private float subLineHeight() {
        return mFontSp * 0.62f * mScaled * 4f / 3f;
    }

    private void relayout() {
        mLayout = new ArrayList<>();
        mSubs = new ArrayList<>();
        int w = getWidth();
        float lh = lineHeight();
        float subH = subLineHeight();
        float gap = 20f * mDensity;
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

    private float targetScrollFor(int idx) {
        if (idx < 0 || idx >= mLayout.size()) return 0f;
        float[] lay = mLayout.get(idx);
        float anchorInBlock = mAnchorFraction * lay[2];
        return Math.max(0f, lay[0] + anchorInBlock - 0.75f * lineHeight());
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

    /** 行淡出（全行统一）：上＝smoothstep（行顶/顶淡高），下＝行底过起始线后乘 bottomFadeKeep。 */
    private float fadeFor(float top, float blockH, int h) {
        float fade = smoothstep(top / Math.max(1f, 0.75f * lineHeight()));
        float startY = Math.min(FADE_START, 0.999f) * h;
        float bottom = top + blockH;
        if (bottom > startY && h > startY) {
            fade *= bottomFadeKeep((bottom - startY) / (h - startY));
        }
        return fade;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || mLines.isEmpty()) return;
        long now = now();

        for (int i = 0; i < mLines.size(); i++) {
            float[] lay = mLayout.get(i);
            float top = lay[0] - mScrollY;
            float bottom = top + lay[2];
            if (bottom < -dp(60) || top > h + dp(60)) continue;
            float fade = fadeFor(top, lay[2], h);

            // 模糊半径（A 组：带符号行距——上 min(2+k,5)、下 min(k+1,5)，k＝距当前行数；量化 round(v×2)/2）
            float radius = 0f;
            if (mBlurCoeff > 0 && i != mCurrent && mCurrent >= 0) {
                int k = Math.abs(i - mCurrent);
                float r = i < mCurrent ? Math.min(2f + k, 5f) : Math.min(k + 1f, 5f);
                radius = Math.round(r * mBlurCoeff * 2f) / 2f;
            }

            if (radius > 0f && Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()) {
                drawRowViaNode(canvas, i, top, fade, radius, w);
            } else {
                drawRowContent(canvas, i, top, fade, now, radius);
            }
        }
    }

    /**
     * 逐行 RenderEffect 模糊（瓜子同机制）：行内容录进独立 RenderNode，模糊挂在节点上，
     * 滚动只改节点位置、淡出走节点 alpha；签名（行号/已唱态/半径/宽/内容版本）变了才重录。
     */
    private void drawRowViaNode(Canvas canvas, int i, float top, float fade, float radius, int w) {
        float[] lay = mLayout.get(i);
        int nodeH = Math.max(1, (int) Math.ceil(lay[2]));
        String sig = i + "|" + (i < mCurrent ? 1 : 0) + "|" + radius + "|" + w + "|" + mContentVersion;
        RowNode rn = mRowNodes.get(i);
        if (rn == null || !sig.equals(rn.sig)) {
            if (rn == null) {
                rn = new RowNode();
                rn.node = new RenderNode("lyricRow" + i);
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

    /** 一行内容（主行＋副行）；maskBlurRadius>0 时走 BlurMaskFilter 回落（低版本），=0 不加滤镜。 */
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
            // 当前行（无逐字或主显非原文）：整行未唱色，不估已唱比例
            mMainPaint.setColor(0xFFFFFFFF);
            mMainPaint.setAlpha(Math.round(255 * ALPHA_CUR_UNSUNG * fade));
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

    /**
     * 当前行逐字：唱过的字以已唱色覆写（按字内进度裁剪）；正在唱的字上浮 −0.09em
     * （easeOut，进度分母 max(1100ms, 字长）)；长音字（≥1000ms）带光晕＋放大（上限约 1.2)
     * ＋−0.05em 正弦浮动，发音结束后拖尾 400ms。
     */
    private void drawCurrentLine(Canvas canvas, LyricsLine main, long now, float fade, float baseline) {
        List<LyricsWord> words = main.getWords();
        String text = main.getText();
        float x = alignX(text, mMainPaint);
        long lineStart = main.getTimeMs();
        float em = mFontSp * mScaled;
        for (LyricsWord word : words) {
            String wt = word.getText();
            float ww = mMainPaint.measureText(wt);
            long ws = lineStart + word.getStartOffsetMs();
            long wd = Math.max(1, word.getDurationMs());
            long elapsed = now - ws;
            float p = Math.max(0f, Math.min(1f, (float) elapsed / (float) wd));

            float lift = 0f;
            if (mAdvancedFx && elapsed > 0) {
                float lp = Math.max(0f, Math.min(1f, (float) elapsed / (float) Math.max(LIFT_MIN_MS, wd)));
                float e = 1f - (float) Math.pow(1f - lp, 3);
                lift = -0.09f * em * e;
            }

            // 长音强调：发音中至结束后 400ms 内，包络＝sin(π × elapsed/(字长＋拖尾）)
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
            // 未唱底
            mMainPaint.setColor(0xFFFFFFFF);
            mMainPaint.setAlpha(Math.round(255 * ALPHA_CUR_UNSUNG * fade));
            mMainPaint.setShadowLayer(0, 0, 0, 0);
            canvas.drawText(wt, x, baseline + lift + floatY, mMainPaint);
            if (p > 0f) {
                mMainPaint.setColor(COLOR_SUNG);
                mMainPaint.setAlpha(Math.round(255 * fade));
                if (emph > 0f) {
                    mMainPaint.setMaskFilter(new BlurMaskFilter(dp(6) * emph, BlurMaskFilter.Blur.OUTER));
                }
                canvas.save();
                canvas.clipRect(x, baseline - em * 1.6f + lift + floatY, x + ww * p, baseline + em * 0.6f + lift + floatY);
                canvas.drawText(wt, x, baseline + lift + floatY, mMainPaint);
                canvas.restore();
                mMainPaint.setMaskFilter(null);
            }
            canvas.restore();
            x += ww;
        }
    }

    private float dp(float v) {
        return v * mDensity;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = e.getX();
                mDownY = e.getY();
                return true;
            case MotionEvent.ACTION_UP:
                float dx = e.getX() - mDownX, dy = e.getY() - mDownY;
                if (Math.abs(dx) < dp(12) && Math.abs(dy) < dp(12) && mListener != null) {
                    float y = e.getY() + mScrollY;
                    for (int i = 0; i < mLayout.size(); i++) {
                        float[] lay = mLayout.get(i);
                        if (y >= lay[0] && y <= lay[0] + lay[2]) {
                            RichLyrics.Line l = mLines.get(i);
                            if (l != null && l.main != null) mListener.onLineTap(l.main.getTimeMs());
                            break;
                        }
                    }
                }
                return true;
            default:
                return true;
        }
    }
}
