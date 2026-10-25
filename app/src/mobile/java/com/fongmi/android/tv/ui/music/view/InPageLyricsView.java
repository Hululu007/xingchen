package com.fongmi.android.tv.ui.music.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
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
 * 经典页内歌词（瓜子 CurrentLyricLine→CoverCurrentLyric 引擎口径）：
 * 默认 22sp（读封面歌词字号设置）、行高 ×4/3、行距 20dp、底部留 45dp；
 * 锚点走 lyricAnchor 设置（top 0.0／center 0.5／bottom 1.0）；逐字上浮 −0.09em、长音光晕近似；
 * 色（沉浸深色供给）：已唱 #FFEAEAEA、当前行未唱 白 0.42、非当前 白 0.30、翻译 白 0.55；
 * 底部淡出 bottomFadeKeep 分段表；逐行模糊 A 组公式（经典页内传 false→A 组）×档位系数，
 * View 体系以 BlurMaskFilter 落地（瓜子原作是逐行 RenderEffect，机制不同、半径值照公式）。
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
    private static final float ALPHA_TRANS = 0.55f;
    private static final float SKEW = -0.12f;

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

    private final float mDensity;
    private final float mScaled;
    private final Paint mMainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, BlurMaskFilter> mBlurCache = new HashMap<>();

    private List<RichLyrics.Line> mLines = new ArrayList<>();
    private List<float[]> mLayout = new ArrayList<>(); // 每行 [top, mainHeight, totalHeight]
    private boolean mHasTime = true;
    private long mPosition;
    private int mCurrent = -1;
    private float mScrollY;
    private ValueAnimator mScrollAnim;
    private Listener mListener;

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
        mSubPaint.setTextSize(mFontSp * 0.6f * mScaled);
        mSubPaint.setTextSkewX(SKEW);
        mSubPaint.setTypeface(mMainPaint.getTypeface());
    }

    public void setLines(List<RichLyrics.Line> lines) {
        mLines = lines == null ? new ArrayList<>() : lines;
        mHasTime = false;
        for (RichLyrics.Line l : mLines) {
            if (l != null && l.main != null && l.main.getTimeMs() > 0) {
                mHasTime = true;
                break;
            }
        }
        if (!mHasTime && mLines.size() > 1) {
            // 全 0 时间戳视作纯文本：无当前行、不滚动
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

    private long now() {
        return mPosition + MusicStore.getLyricOffsetMs();
    }

    private String mainText(RichLyrics.Line line) {
        if (line == null || line.main == null) return "";
        if (mPrimary == 1 && line.translation != null && !line.translation.isEmpty()) return line.translation;
        if (mPrimary == 2 && line.romanization != null && !line.romanization.isEmpty()) return line.romanization;
        return line.main.getText();
    }

    private String subText(RichLyrics.Line line) {
        if (line == null || line.main == null || mPrimary != 0) return null;
        if (mShowTrans && line.translation != null && !line.translation.isEmpty()) return line.translation;
        if (mShowRoma && line.romanization != null && !line.romanization.isEmpty()) return line.romanization;
        return null;
    }

    private float lineHeight() {
        return mFontSp * mScaled * 4f / 3f;
    }

    private void relayout() {
        mLayout = new ArrayList<>();
        int w = getWidth();
        float lh = lineHeight();
        float subH = mFontSp * 0.6f * mScaled * 1.3f;
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
            float total = mainH + (subText(line) != null ? subH + 4f * mDensity : 0);
            mLayout.add(new float[]{top, mainH, total});
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
        float anchorY = mAnchorFraction * Math.max(0, getHeight() - lay[1]);
        return Math.max(0f, lay[0] - anchorY);
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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || mLines.isEmpty()) return;
        long now = now();
        float lh = lineHeight();
        float anchorY = mCurrent >= 0 && mCurrent < mLayout.size()
                ? mAnchorFraction * Math.max(0, h - mLayout.get(mCurrent)[1]) : 0f;

        for (int i = 0; i < mLines.size(); i++) {
            float[] lay = mLayout.get(i);
            float top = lay[0] - mScrollY;
            float bottom = top + lay[2];
            if (bottom < -dp(60) || top > h + dp(60)) continue;
            RichLyrics.Line line = mLines.get(i);
            String main = mainText(line);
            float fade;
            if (i == mCurrent) {
                fade = 1f;
            } else if (top + lay[1] <= anchorY + 1) {
                int dist = Math.abs(i - Math.max(0, mCurrent));
                fade = Math.max(0.3f, 1f - dist * 0.22f);
            } else {
                float t = (top - anchorY) / Math.max(1f, h - anchorY);
                fade = bottomFadeKeep(t);
            }

            // 模糊（A 组）
            mMainPaint.setMaskFilter(null);
            mSubPaint.setMaskFilter(null);
            if (mBlurCoeff > 0 && i != mCurrent && mCurrent >= 0) {
                int dist = Math.abs(i - mCurrent);
                float r = i < mCurrent
                        ? Math.max(0f, Math.min(2.0f - dist, 5f))
                        : Math.min(dist + 1.0f, 5f);
                BlurMaskFilter bf = blurFor(r * mBlurCoeff);
                mMainPaint.setMaskFilter(bf);
                mSubPaint.setMaskFilter(bf);
            }

            float baseline = top - mMainPaint.ascent();
            if (i == mCurrent && mPrimary == 0 && line.main != null) {
                drawCurrentLine(canvas, line.main, now, fade, baseline);
            } else if (i == mCurrent) {
                // 当前行（主显非原文或纯文本）：整行未唱色打底，唱过比例以亮色覆写
                mMainPaint.setColor(0xFFFFFFFF);
                mMainPaint.setAlpha(Math.round(255 * ALPHA_CUR_UNSUNG * fade));
                canvas.drawText(main, alignX(main, mMainPaint), baseline, mMainPaint);
                drawSungPortion(canvas, line, main, now, baseline);
            } else if (mCurrent >= 0 && i < mCurrent) {
                // 已唱过的行：亮色（已唱色），随距锚点淡出
                mMainPaint.setColor(COLOR_SUNG);
                mMainPaint.setAlpha(Math.round(255 * 0.9f * fade));
                canvas.drawText(main, alignX(main, mMainPaint), baseline, mMainPaint);
            } else {
                // 未唱行：白 0.30 × 淡出
                mMainPaint.setColor(0xFFFFFFFF);
                mMainPaint.setAlpha(Math.round(255 * ALPHA_NONCUR * fade));
                canvas.drawText(main, alignX(main, mMainPaint), baseline, mMainPaint);
            }
            mMainPaint.setMaskFilter(null);

            String sub = subText(line);
            if (sub != null) {
                float subBase = top + lay[1] - mSubPaint.ascent() + dp(4);
                mSubPaint.setColor(0xFFFFFFFF);
                mSubPaint.setAlpha(Math.round(255 * ALPHA_TRANS * fade * (i == mCurrent ? 1f : 0.6f)));
                canvas.drawText(sub, alignXSub(sub), subBase, mSubPaint);
                mSubPaint.setMaskFilter(null);
            }
        }
    }

    private float alignXSub(String text) {
        int w = getWidth();
        float tw = mSubPaint.measureText(text);
        switch (mAlign) {
            case 1:
                return Math.max(0, (w - tw) / 2f);
            case 2:
                return Math.max(0, w - tw);
            default:
                return 0;
        }
    }

    /** 当前行逐字：唱过的字亮色、正在唱的字上浮 −0.09em（easeOut），长字带光晕（shadow 近似）。 */
    private void drawCurrentLine(Canvas canvas, LyricsLine main, long now, float fade, float baseline) {
        List<LyricsWord> words = main.getWords();
        String text = main.getText();
        if (words == null || words.isEmpty()) {
            mMainPaint.setColor(0xFFFFFFFF);
            mMainPaint.setAlpha(Math.round(255 * ALPHA_CUR_UNSUNG * fade));
            canvas.drawText(text, alignX(text, mMainPaint), baseline, mMainPaint);
            drawSungPortion(canvas, wrapLine(main), text, now, baseline);
            return;
        }
        float x = alignX(text, mMainPaint);
        long lineStart = main.getTimeMs();
        float em = mFontSp * mScaled;
        for (LyricsWord word : words) {
            String wt = word.getText();
            float ww = mMainPaint.measureText(wt);
            long ws = lineStart + word.getStartOffsetMs();
            long wd = Math.max(1, word.getDurationMs());
            float p = Math.max(0f, Math.min(1f, (float) (now - ws) / (float) wd));
            float lift = 0f;
            if (mAdvancedFx && p > 0f && p < 1f) {
                float e = 1f - (float) Math.pow(1f - p, 3);
                lift = -0.09f * em * e;
            }
            // 未唱底
            mMainPaint.setColor(0xFFFFFFFF);
            mMainPaint.setAlpha(Math.round(255 * ALPHA_CUR_UNSUNG * fade));
            mMainPaint.setShadowLayer(0, 0, 0, 0);
            canvas.drawText(wt, x, baseline + lift, mMainPaint);
            if (p > 0f) {
                // 已唱覆写（按字内进度裁剪）
                mMainPaint.setColor(COLOR_SUNG);
                mMainPaint.setAlpha(Math.round(255 * fade));
                if (mAdvancedFx && wd >= 1000 && p < 1f) mMainPaint.setShadowLayer(dp(6), 0, 0, COLOR_SUNG);
                canvas.save();
                canvas.clipRect(x, baseline - em * 1.6f + lift, x + ww * p, baseline + em * 0.6f + lift);
                canvas.drawText(wt, x, baseline + lift, mMainPaint);
                canvas.restore();
                mMainPaint.setShadowLayer(0, 0, 0, 0);
            }
            x += ww;
        }
    }

    private RichLyrics.Line wrapLine(LyricsLine main) {
        for (RichLyrics.Line l : mLines) if (l.main == main) return l;
        return null;
    }

    /** 无逐字当前行：按行时间进度把已唱部分以亮色覆写（裁剪宽度比例）。 */
    private void drawSungPortion(Canvas canvas, RichLyrics.Line line, String text, long now, float baseline) {
        if (line == null || line.main == null) return;
        int idx = mLines.indexOf(line);
        long start = line.main.getTimeMs();
        long end;
        if (idx >= 0 && idx + 1 < mLines.size() && mLines.get(idx + 1).main != null) {
            end = mLines.get(idx + 1).main.getTimeMs();
        } else {
            end = start + 4000;
        }
        if (end <= start) return;
        float p = Math.max(0f, Math.min(1f, (float) (now - start) / (float) (end - start)));
        if (p <= 0f) return;
        float x = alignX(text, mMainPaint);
        float tw = mMainPaint.measureText(text);
        float em = mFontSp * mScaled;
        mMainPaint.setColor(COLOR_SUNG);
        mMainPaint.setAlpha(255);
        canvas.save();
        canvas.clipRect(x, baseline - em * 1.6f, x + tw * p, baseline + em * 0.6f);
        canvas.drawText(text, x, baseline, mMainPaint);
        canvas.restore();
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
