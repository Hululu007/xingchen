package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.RichLyrics;

import java.util.ArrayList;
import java.util.List;

public class GzLyricView extends ViewGroup {

    public interface OnLineSeek {
        void onSeekLine(long timeMs);
    }

    public static final int MODE_FULL = 0;
    public static final int MODE_EMBED = 1;

    private static final float FULL_ANCHOR_FRACTION = 0.60f;
    private static final long REANCHOR_DELAY_MS = 5000L;

    private final List<GzLyricLineView> pool = new ArrayList<>();
    private List<RichLyrics.Line> lines = new ArrayList<>();
    private GzLyricLineView.Palette palette;
    private int mode = MODE_FULL;
    private int windowBefore = 3;
    private int windowAfter = 15;
    private float fontSizeSp = 24f;
    private float lineHeightMul = 1.3f;
    private float gapDp = 6f;
    private float hPadDp = 28f;
    private float topFadeDp = 40f;
    private int blurLevel = MusicStore.LYRIC_BLUR_MEDIUM;
    private int align = 0;
    private boolean showTranslation = true;
    private boolean showRomanization;
    private int primary;
    private boolean advancedFx = true;
    private boolean loading = true;

    private long rawPositionMs;
    private long smoothMs;
    private long lastFrameNs;
    private boolean playing;
    private boolean frameRunning;
    private int currentIndex = -1;
    private int windowStart = -1;
    private int windowEnd = -1;

    private float scrollOffset;
    private float scrollVelocity;
    private float manualOffset;
    private float manualVelocity;
    private boolean browsing;
    private long lastTouchMs;
    private float dimAlpha = 1f;
    private float dimTarget = 1f;

    private OnLineSeek seekListener;
    private float downY;
    private float downX;
    private float lastY;
    private boolean dragging;
    private long downMs;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!frameRunning) return;
            stepFrame(frameTimeNanos);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public GzLyricView(Context context) {
        super(context);
        init();
    }

    public GzLyricView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void setMode(int mode) {
        this.mode = mode;
        if (mode == MODE_FULL) {
            windowBefore = 3;
            windowAfter = 15;
            lineHeightMul = 1.3f;
            gapDp = 6f;
            topFadeDp = 40f;
            fontSizeSp = MusicStore.getLyricFontSize();
            blurLevel = MusicStore.getCoverLyricBlurLevel();
        } else {
            windowBefore = 2;
            windowAfter = 6;
            lineHeightMul = 4f / 3f;
            gapDp = 20f;
            topFadeDp = 0f;
            fontSizeSp = MusicStore.getCoverLyricFontSize();
            blurLevel = MusicStore.getCoverLyricBlurLevel();
        }
        align = MusicStore.getLyricAlign();
        showTranslation = MusicStore.isShowTranslation();
        showRomanization = MusicStore.isShowRomanization();
        primary = MusicStore.getLyricPrimary();
        advancedFx = MusicStore.isAdvancedFx();
        windowStart = -1;
        rebuildWindow(true);
        requestLayout();
    }

    public void applySpec(float fontSizeSpOverride, float lineHeightMulOverride,
                          float gapDpOverride, float hPadDpOverride) {
        this.fontSizeSp = fontSizeSpOverride;
        this.lineHeightMul = lineHeightMulOverride;
        this.gapDp = gapDpOverride;
        this.hPadDp = hPadDpOverride;
        windowStart = -1;
        rebuildWindow(true);
        requestLayout();
    }

    public void setAlignOverride(int alignOverride) {
        this.align = alignOverride;
        windowStart = -1;
        rebuildWindow(true);
    }

    public void setPalette(GzLyricLineView.Palette palette) {
        this.palette = palette;
        windowStart = -1;
        rebuildWindow(true);
    }

    public void setOnLineSeek(OnLineSeek listener) {
        this.seekListener = listener;
    }

    public void setLyrics(List<RichLyrics.Line> newLines, boolean loading) {
        this.loading = loading;
        this.lines = newLines == null ? new ArrayList<>() : newLines;
        currentIndex = -1;
        windowStart = -1;
        scrollOffset = 0;
        scrollVelocity = 0;
        manualOffset = 0;
        browsing = false;
        rebuildWindow(true);
        requestLayout();
        updateRunning();
    }

    public boolean isLoading() {
        return loading;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void setPlayback(long positionMs, boolean isPlaying) {
        rawPositionMs = positionMs;
        if (isPlaying != playing) {
            playing = isPlaying;
            dimTarget = isPlaying ? 1f : 0.38f;
            if (!isPlaying) smoothMs = rawPositionMs + MusicStore.getLyricOffsetMs();
        }
        updateRunning();
    }

    private void updateRunning() {
        boolean need = playing && !lines.isEmpty();
        if (need && !frameRunning) {
            frameRunning = true;
            lastFrameNs = 0;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else if (!need && frameRunning) {
            frameRunning = false;
        }
        if (!need) stepDim(1f);
    }

    private void stepFrame(long frameTimeNanos) {
        float dt;
        if (lastFrameNs == 0) {
            dt = 0.016f;
        } else {
            dt = (frameTimeNanos - lastFrameNs) / 1_000_000_000f;
        }
        lastFrameNs = frameTimeNanos;
        dt = Math.max(0f, Math.min(0.05f, dt));

        long raw = rawPositionMs + MusicStore.getLyricOffsetMs();
        if (playing) {
            long target = smoothMs + Math.round(dt * 1000f);
            if (target > raw + 1000L) target = raw + 1000L;
            if (raw - smoothMs < -350L) {
                smoothMs = raw;
            } else if (target > smoothMs) {
                smoothMs = target;
            }
            if (smoothMs < raw - 1000L) smoothMs = raw;
        }
        stepDim(dt);
        stepScroll(dt);
        int idx = computeCurrentIndex();
        if (idx != currentIndex) {
            currentIndex = idx;
            rebuildWindow(false);
            requestLayout();
        } else {
            refreshLineStates();
        }
        if (browsing && lastTouchMs > 0 && System.currentTimeMillis() - lastTouchMs > REANCHOR_DELAY_MS
                && !dragging) {
            browsing = false;
        }
        invalidate();
    }

    private void stepDim(float dt) {
        if (Math.abs(dimAlpha - dimTarget) < 0.01f) {
            dimAlpha = dimTarget;
            return;
        }
        float speed = dt / 0.35f;
        dimAlpha += (dimTarget - dimAlpha) * Math.min(1f, speed * 2f);
    }

    private void stepScroll(float dt) {
        float target = browsing ? 0f : 0f;
        float dest = target;
        float stiffness = 237.3f;
        float damping = 1.16f;
        float accel = stiffness * (dest - scrollOffset) - 2f * damping * (float) Math.sqrt(stiffness) * scrollVelocity;
        scrollVelocity += accel * dt;
        scrollOffset += scrollVelocity * dt;
        if (!browsing && Math.abs(manualOffset) > 0.5f) {
            if (Math.abs(manualOffset) > getHeight() * 1.5f) {
                manualOffset = 0;
                manualVelocity = 0;
            } else {
                float a2 = stiffness * (0f - manualOffset) - 2f * damping * (float) Math.sqrt(stiffness) * manualVelocity;
                manualVelocity += a2 * dt;
                manualOffset += manualVelocity * dt;
            }
        }
        if (Math.abs(scrollOffset) < 0.01f && Math.abs(scrollVelocity) < 0.01f) {
            scrollOffset = 0;
            scrollVelocity = 0;
        }
    }

    private int computeCurrentIndex() {
        if (lines.isEmpty()) return -1;
        long first = lines.get(0).main == null ? 0 : lines.get(0).main.getTimeMs();
        long last = lines.get(lines.size() - 1).main == null ? 0 : lines.get(lines.size() - 1).main.getTimeMs();
        if (lines.size() < 2 || first == last) return -1;
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long t = lines.get(mid).main == null ? 0 : lines.get(mid).main.getTimeMs();
            if (t <= smoothMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    private void rebuildWindow(boolean force) {
        if (palette == null) return;
        int anchor = currentIndex < 0 ? 0 : currentIndex;
        int start = Math.max(0, anchor - windowBefore);
        int end = Math.min(lines.size() - 1, anchor + windowAfter);
        if (!force && start == windowStart && end == windowEnd) {
            refreshLineStates();
            return;
        }
        windowStart = start;
        windowEnd = end;
        removeAllViews();
        pool.clear();
        float density = getResources().getDisplayMetrics().density;
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        for (int i = start; i <= end; i++) {
            GzLyricLineView child = new GzLyricLineView(getContext());
            child.bind(lines.get(i), palette, fontSizeSp * scaled, lineHeightMul,
                    mode == MODE_FULL ? -0.2f * scaled : 0f, align,
                    showTranslation, showRomanization, primary, advancedFx);
            int w = MusicStore.getLyricWeight();
            child.setWeight(w == 0 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.BOLD);
            addView(child);
            pool.add(child);
        }
        refreshLineStates();
    }

    private void refreshLineStates() {
        for (int i = 0; i < pool.size(); i++) {
            int idx = windowStart + i;
            GzLyricLineView child = pool.get(i);
            boolean cur = idx == currentIndex;
            boolean past = currentIndex >= 0 && idx < currentIndex;
            float sung = -1f;
            if (cur) sung = sungCharsFor(idx);
            child.setCurrentState(cur, past, sung, smoothMs);
            applyChildEffects(child, idx);
        }
    }

    private float sungCharsFor(int idx) {
        if (idx < 0 || idx >= lines.size()) return -1f;
        RichLyrics.Line rl = lines.get(idx);
        if (rl.main == null) return -1f;
        List<com.fongmi.android.tv.player.lyrics.LyricsWord> words = rl.main.getWords();
        String text = rl.main.getText();
        if (words == null || words.isEmpty() || text == null || text.isEmpty()) {
            return text == null ? -1f : text.length();
        }
        long lineStart = rl.main.getTimeMs();
        float total = 0f;
        int charCount = 0;
        for (com.fongmi.android.tv.player.lyrics.LyricsWord w : words) {
            String wt = w.getText();
            if (wt == null || wt.isEmpty()) continue;
            int pos = text.indexOf(wt, charCount);
            if (pos < 0) pos = charCount;
            total += Math.max(0, pos - charCount);
            charCount = pos;
            long ws = lineStart + w.getStartOffsetMs();
            long we = ws + w.getDurationMs();
            if (smoothMs <= ws) {
                return total;
            } else if (smoothMs >= we) {
                total += wt.length();
            } else {
                float frac = (smoothMs - ws) / (float) Math.max(1L, we - ws);
                total += wt.length() * Math.max(0f, Math.min(1f, frac));
                return total;
            }
            charCount += wt.length();
        }
        return total;
    }

    private void applyChildEffects(GzLyricLineView child, int idx) {
        float centerY = child.getTop() + child.getHeight() / 2f;
        float h = Math.max(1, getHeight());
        float f = Math.max(0f, Math.min(1f, centerY / h));
        float alpha = bottomFadeKeep(f);
        if (mode == MODE_FULL && topFadeDp > 0) {
            float fadePx = topFadeDp * getResources().getDisplayMetrics().density;
            float bottom = child.getBottom();
            if (bottom < fadePx) alpha *= Math.max(0f, bottom / fadePx);
        }
        boolean cur = idx == currentIndex;
        if (!cur) alpha *= 1f;
        child.setAlpha(Math.max(0f, Math.min(1f, alpha * dimAlpha)));
        if (Build.VERSION.SDK_INT >= 31) {
            if (!cur && blurLevel > MusicStore.LYRIC_BLUR_OFF) {
                float factor = blurLevel == MusicStore.LYRIC_BLUR_LIGHT ? 0.45f : 1.0f;
                float e = Math.round(factor * 2f);
                float radius = Math.max(0.5f, e / 2f);
                try {
                    child.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL));
                } catch (Throwable ignored) {
                    child.setRenderEffect(null);
                }
            } else {
                child.setRenderEffect(null);
            }
        }
    }

    private float bottomFadeKeep(float f) {
        float[][] pts = {{0f, 1f}, {0.3f, 0.85f}, {0.55f, 0.62f}, {0.78f, 0.48f}, {1f, 0.45f}};
        if (f <= pts[0][0]) return pts[0][1];
        for (int i = 1; i < pts.length; i++) {
            if (f <= pts[i][0]) {
                float t = (f - pts[i - 1][0]) / (pts[i][0] - pts[i - 1][0]);
                float s = t * t * (3f - 2f * t);
                return pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * s;
            }
        }
        return 0.45f;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(hPadDp * density);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            GzLyricLineView lv = (GzLyricLineView) child;
            int cw = Math.max(0, w - pad * 2);
            child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(lv.contentHeight(), MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (windowStart < 0) return;
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(hPadDp * density);
        int gap = Math.round(gapDp * density);
        int anchorIdx = currentIndex < 0 ? windowStart : currentIndex;
        int anchorPosInWindow = anchorIdx - windowStart;
        float anchorY;
        if (mode == MODE_FULL) {
            anchorY = getHeight() * FULL_ANCHOR_FRACTION;
        } else {
            float y = 0;
            for (int i = 0; i < anchorPosInWindow && i < getChildCount(); i++) {
                y += getChildAt(i).getMeasuredHeight() + gap;
            }
            anchorY = y;
        }
        float cur = anchorY + scrollOffset + manualOffset;
        for (int i = anchorPosInWindow - 1; i >= 0; i--) {
            View child = getChildAt(i);
            cur -= gap + child.getMeasuredHeight();
            child.layout(pad, Math.round(cur), pad + child.getMeasuredWidth(),
                    Math.round(cur) + child.getMeasuredHeight());
        }
        cur = anchorY + scrollOffset + manualOffset;
        for (int i = anchorPosInWindow; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.layout(pad, Math.round(cur), pad + child.getMeasuredWidth(),
                    Math.round(cur) + child.getMeasuredHeight());
            cur += child.getMeasuredHeight() + gap;
        }
        for (int i = 0; i < pool.size(); i++) {
            applyChildEffects(pool.get(i), windowStart + i);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getY();
                downX = event.getX();
                lastY = event.getY();
                downMs = System.currentTimeMillis();
                dragging = false;
                manualVelocity = 0;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy = event.getY() - lastY;
                lastY = event.getY();
                if (Math.abs(event.getY() - downY) > dp(6)) dragging = true;
                if (dragging) {
                    browsing = true;
                    lastTouchMs = System.currentTimeMillis();
                    manualOffset += dy;
                    float maxPull = getHeight() * 1.5f;
                    manualOffset = Math.max(-maxPull * 2f, Math.min(maxPull * 2f, manualOffset));
                    manualVelocity = dy * 60f;
                    requestLayout();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lastTouchMs = System.currentTimeMillis();
                if (!dragging && System.currentTimeMillis() - downMs < 300) {
                    int idx = lineIndexAt(event.getY());
                    if (idx >= 0 && idx < lines.size() && seekListener != null
                            && lines.get(idx).main != null) {
                        browsing = false;
                        manualOffset = 0;
                        manualVelocity = 0;
                        seekListener.onSeekLine(lines.get(idx).main.getTimeMs());
                    }
                } else if (Math.abs(manualVelocity) <= 60f) {
                    manualVelocity = 0;
                }
                dragging = false;
                requestLayout();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private int lineIndexAt(float y) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (y >= child.getTop() && y <= child.getBottom()) return windowStart + i;
        }
        return -1;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDetachedFromWindow() {
        frameRunning = false;
        super.onDetachedFromWindow();
    }
}
