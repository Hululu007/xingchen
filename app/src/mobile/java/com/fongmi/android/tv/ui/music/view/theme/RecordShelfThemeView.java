package com.fongmi.android.tv.ui.music.view.theme;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.ui.music.MusicPlayerSettings;
import com.fongmi.android.tv.ui.music.view.SpringAnimator;
import com.fongmi.android.tv.utils.CoverColor;

import java.util.List;

/**
 * 黑胶唱片架主题（record_shelf，瓜子案卷第七节）：根实色 #FFF1F0ED、Ink #FF252522、
 * Muted #FF86857E、immersiveDark=FALSE。头行 64（左收起／中标题列／右三个点）→
 * 唱片架 3 槽（当前槽袖套＋抽出黑胶，点当前槽=收藏、点侧槽=切歌）→白色歌词面
 * （上 0/下 24 圆角，palette h0=lerp(封面 tone, Ink, .65)）→进度区 72→底行 76。
 * 本主题是浅色实底页（瓜子原样），页面根下氛围层被实底盖住——与经典页溶边无冲突
 * （溶边是全幅封面形态，本主题封面只作袖套/盘心小图）。
 */
public class RecordShelfThemeView extends PlayerThemeView {

    private static final int BG = 0xFFF1F0ED;
    private static final int INK = 0xFF252522;
    private static final int MUTED = 0xFF86857E;

    private TextView mTitle;
    private TextView mArtist;
    private ShelfDeckView mDeck;
    private ThemeLyricView mLyricView;
    private ShelfSeek mSeek;
    private ImageView mBtnMode;
    private ImageView mBtnPlay;
    private int mH0 = INK;

    public RecordShelfThemeView(android.content.Context context, Host host) {
        super(context, host);
        build();
    }

    private void build() {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dpI(16), 0, dpI(16), 0);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(64)));
        header.addView(headerBtn(R.drawable.ic_player_collapse, v -> mHost.collapse()));
        LinearLayout titles = new LinearLayout(getContext());
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dpI(8);
        tlp.rightMargin = dpI(8);
        header.addView(titles, tlp);
        mTitle = text(15, INK, false);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            mTitle.setTypeface(android.graphics.Typeface.create(mTitle.getTypeface(), 500, false));
            mTitle.setLineHeight(dpI(21));
        }
        mTitle.setGravity(Gravity.CENTER);
        titles.addView(mTitle);
        mArtist = text(11, MUTED, false);
        if (android.os.Build.VERSION.SDK_INT >= 28) mArtist.setLineHeight(dpI(15));
        mArtist.setGravity(Gravity.CENTER);
        titles.addView(mArtist);
        header.addView(headerBtn(R.drawable.ic_music_more, v -> mHost.openTools()));

        mDeck = new ShelfDeckView(getContext());
        mDeck.setHostAction(new ShelfDeckView.Action() {
            @Override
            public void onSideTap(int dir) {
                if (dir > 0) mHost.next();
                else mHost.prev();
            }

            @Override
            public void onCurrentTap() {
                mHost.toggleFavorite();
            }
        });
        root.addView(mDeck, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 白色歌词面：上圆角 0／下圆角 24，顶部渐变 黑 .13→.055→透明
        FrameLayout lyricFace = new FrameLayout(getContext());
        GradientDrawable faceBg = new GradientDrawable();
        faceBg.setColor(0xFFFFFFFF);
        faceBg.setCornerRadii(new float[]{0, 0, 0, 0, dp(24), dp(24), dp(24), dp(24)});
        lyricFace.setBackground(faceBg);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        flp.leftMargin = dpI(16);
        flp.rightMargin = dpI(16);
        root.addView(lyricFace, flp);
        View topShade = new View(getContext());
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x21000000, 0x0E000000, 0x00000000});
        topShade.setBackground(shade);
        lyricFace.addView(topShade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(48)));
        mLyricView = new ThemeLyricView(getContext());
        mLyricView.setPadding(dpI(24), dpI(12), dpI(24), dpI(12));
        lyricFace.addView(mLyricView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyLyricPalette();

        mSeek = new ShelfSeek(getContext());
        mSeek.setListener(ms -> mHost.seekTo(ms));
        LinearLayout.LayoutParams skp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(72));
        skp.leftMargin = dpI(28);
        skp.rightMargin = dpI(28);
        root.addView(mSeek, skp);

        LinearLayout controls = new LinearLayout(getContext());
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dpI(20), 0, dpI(20), 0);
        root.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(76)));
        mBtnMode = controlBtn(controls, R.drawable.ic_player_sequential, 24, v -> cycleMode());
        controlBtn(controls, R.drawable.ic_music_prev, 32, v -> mHost.prev());
        mBtnPlay = controlBtn(controls, R.drawable.ic_music_play, 52, v -> mHost.togglePlay());
        controlBtn(controls, R.drawable.ic_music_next, 32, v -> mHost.next());
        controlBtn(controls, R.drawable.ic_music_queue, 24, v -> mHost.openQueue());
        refreshModeIcon();
    }

    private ImageView headerBtn(int iconRes, View.OnClickListener click) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(iconRes);
        iv.setColorFilter(withAlpha(INK, 0.9f));
        iv.setPadding(dpI(12), dpI(12), dpI(12), dpI(12));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dpI(48), dpI(48)));
        iv.setOnClickListener(click);
        pressScale(iv, 0.9f);
        return iv;
    }

    private ImageView controlBtn(LinearLayout parent, int iconRes, int iconDp,
                                 View.OnClickListener click) {
        FrameLayout cell = new FrameLayout(parent.getContext());
        parent.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        ImageView iv = new ImageView(parent.getContext());
        iv.setImageResource(iconRes);
        iv.setColorFilter(INK);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dpI(iconDp), dpI(iconDp), Gravity.CENTER);
        cell.addView(iv, ilp);
        cell.setOnClickListener(click);
        pressScale(cell, 0.9f);
        return iv;
    }

    private void applyLyricPalette() {
        final int h0 = mH0;
        mLyricView.configure(new ThemeLyricView.Palette() {
            @Override
            public int sung() {
                return h0;
            }

            @Override
            public int currentUnsung() {
                return withAlpha(h0, 0.48f);
            }

            @Override
            public int inactive() {
                return withAlpha(MUTED, 0.72f);
            }

            @Override
            public int translation() {
                return MUTED;
            }
        }, MusicPlayerSettings.getCoverLyricFontSize(), false, false, 4f / 3f, 20, 0f);
        if (mSeek != null) mSeek.setFillColor(h0);
    }

    private void cycleMode() {
        int cur = MusicController.get().getMode();
        int next;
        switch (cur) {
            case MusicQueue.MODE_SEQUENTIAL:
                next = MusicQueue.MODE_REPEAT_ALL;
                break;
            case MusicQueue.MODE_REPEAT_ALL:
                next = MusicQueue.MODE_REPEAT_ONE;
                break;
            case MusicQueue.MODE_REPEAT_ONE:
                next = MusicQueue.MODE_SHUFFLE;
                break;
            default:
                next = MusicQueue.MODE_SEQUENTIAL;
                break;
        }
        MusicController.get().setMode(next);
        refreshModeIcon();
    }

    private void refreshModeIcon() {
        if (mBtnMode == null) return;
        switch (MusicController.get().getMode()) {
            case MusicQueue.MODE_REPEAT_ALL:
                mBtnMode.setImageResource(R.drawable.ic_player_repeat);
                break;
            case MusicQueue.MODE_REPEAT_ONE:
                mBtnMode.setImageResource(R.drawable.ic_player_repeat_one);
                break;
            case MusicQueue.MODE_SHUFFLE:
                mBtnMode.setImageResource(R.drawable.ic_music_shuffle);
                break;
            default:
                mBtnMode.setImageResource(R.drawable.ic_player_sequential);
                break;
        }
    }

    @Override
    protected void onBindTrack(MusicTrack track, int dir) {
        mTitle.setText(track.displayTitle());
        mArtist.setText(track.displayArtist());
        // 架上三槽：队列前后曲（控制器现读）
        MusicTrack prev = null, next = null;
        try {
            java.util.List<MusicTrack> q = MusicController.get().queue();
            int idx = MusicController.get().index();
            if (q != null && idx >= 0) {
                if (idx > 0) prev = q.get(idx - 1);
                if (idx + 1 < q.size()) next = q.get(idx + 1);
            }
        } catch (Throwable ignored) {
        }
        mDeck.setTracks(prev, track, next, dir);
        loadCover(track.cover, 720, bmp -> {
            if (bmp != null) {
                mH0 = mixColor(CoverColor.cdToneColor(bmp), INK, 0.65f);
                applyLyricPalette();
            }
        });
        mLyricView.setLines(null);
    }

    @Override
    protected void onPlayingChanged(boolean playing) {
        mBtnPlay.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        mDeck.setPlaying(playing);
    }

    @Override
    protected void onLyricLines(List<RichLyrics.Line> lines) {
        mLyricView.setLines(lines);
    }

    @Override
    protected void onProgress(long pos, long dur) {
        if (!mSeek.isDragging()) mSeek.setProgress(pos, dur);
        mLyricView.setPosition(pos);
    }

    // ---------- 唱片架 ----------

    static class ShelfDeckView extends View {
        interface Action {
            void onSideTap(int dir);

            void onCurrentTap();
        }

        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private Action mAction;
        private Bitmap mPrevBmp, mCurBmp, mNextBmp;
        private String mPrevUrl, mCurUrl, mNextUrl;
        private boolean mPlaying;
        private float mAngle;
        private long mLastFrame;
        private float mShiftPx;
        private float mReveal = 0.55f;
        private SpringAnimator mShiftSpring = new SpringAnimator(0.9, 300);
        private android.animation.ValueAnimator mRevealAnim;
        private float mDownX, mDownY;
        private final Choreographer.FrameCallback mFrame = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                long now = System.nanoTime();
                if (mLastFrame > 0 && mPlaying) {
                    float dt = Math.min(0.05f, (now - mLastFrame) / 1e9f);
                    mAngle = (mAngle + 30f * dt) % 360f;
                    invalidate();
                }
                mLastFrame = now;
                if (mPlaying && getWindowToken() != null) {
                    Choreographer.getInstance().postFrameCallback(this);
                }
            }
        };

        ShelfDeckView(android.content.Context ctx) {
            super(ctx);
        }

        void setHostAction(Action a) {
            mAction = a;
        }

        void setTracks(MusicTrack prev, MusicTrack cur, MusicTrack next, int dir) {
            loadSlot(prev == null ? null : prev.cover, 0);
            loadSlot(cur == null ? null : cur.cover, 1);
            loadSlot(next == null ? null : next.cover, 2);
            if (dir != 0) {
                float density = getResources().getDisplayMetrics().density;
                float pitch = pitchDp() * density;
                mShiftSpring.cancel();
                mShiftSpring.start(dir * pitch, 0, new SpringAnimator.Listener() {
                    @Override
                    public void onUpdate(float value) {
                        mShiftPx = value;
                        invalidate();
                    }

                    @Override
                    public void onEnd() {
                    }
                });
            }
            invalidate();
        }

        private void loadSlot(String url, int slot) {
            if (url == null || url.isEmpty()) {
                setSlotBmp(slot, null, null);
                return;
            }
            final String want = url;
            com.fongmi.android.tv.utils.ImgUtil.load(url, 720, new com.fongmi.android.tv.impl.CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(@androidx.annotation.NonNull Bitmap resource,
                                            @androidx.annotation.Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                    if (want.equals(urlOf(slot))) setSlotBmp(slot, want, resource);
                }

                @Override
                public void onLoadFailed(@androidx.annotation.Nullable android.graphics.drawable.Drawable errorDrawable) {
                    if (want.equals(urlOf(slot))) setSlotBmp(slot, want, null);
                }

                @Override
                public void onLoadCleared(@androidx.annotation.Nullable android.graphics.drawable.Drawable placeholder) {
                }
            });
        }

        private String urlOf(int slot) {
            return slot == 0 ? mPrevUrl : slot == 1 ? mCurUrl : mNextUrl;
        }

        private void setSlotBmp(int slot, String url, Bitmap bmp) {
            if (slot == 0) {
                mPrevUrl = url;
                mPrevBmp = bmp;
            } else if (slot == 1) {
                mCurUrl = url;
                mCurBmp = bmp;
            } else {
                mNextUrl = url;
                mNextBmp = bmp;
            }
            invalidate();
        }

        void setPlaying(boolean playing) {
            mPlaying = playing;
            animateReveal(playing ? 1f : 0.55f, playing ? 200 : 600, playing);
            if (playing && getWindowToken() != null) {
                mLastFrame = 0;
                Choreographer.getInstance().postFrameCallback(mFrame);
            }
        }

        private void animateReveal(float to, long duration, boolean easeOut) {
            if (mRevealAnim != null) mRevealAnim.cancel();
            mRevealAnim = android.animation.ValueAnimator.ofFloat(mReveal, to);
            mRevealAnim.setDuration(duration);
            mRevealAnim.setInterpolator(easeOut
                    ? new android.view.animation.PathInterpolator(0.3f, 0f, 1f, 1f)
                    : new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
            mRevealAnim.addUpdateListener(a -> {
                mReveal = (float) a.getAnimatedValue();
                invalidate();
            });
            mRevealAnim.start();
        }

        private float fDp() {
            float density = getResources().getDisplayMetrics().density;
            float wDp = getWidth() / density;
            return Math.max(180f, Math.min(300f, wDp * 0.66f));
        }

        private float f3Dp() {
            float density = getResources().getDisplayMetrics().density;
            float wDp = getWidth() / density;
            float f2 = Math.min(wDp * 0.5f, fDp() - 36f);
            return f2 * 0.95f;
        }

        private float pitchDp() {
            return f3Dp() * 1.62f;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            float density = getResources().getDisplayMetrics().density;
            float wDp = getMeasuredWidth() / density;
            float f = Math.max(180f, Math.min(300f, wDp * 0.66f));
            setMeasuredDimension(getMeasuredWidth(), Math.round((f - 23) * density));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float f3 = f3Dp() * density;
            float cx = getWidth() / 2f + mShiftPx;
            float top = (getHeight() - f3) / 2f - 3 * density;
            drawSlot(canvas, cx - pitchDp() * density, top, f3, mPrevBmp, false);
            drawSlot(canvas, cx + pitchDp() * density, top, f3, mNextBmp, false);
            drawSlot(canvas, cx, top, f3, mCurBmp, true);
        }

        private void drawSlot(Canvas canvas, float centerX, float top, float f3,
                              Bitmap bmp, boolean current) {
            float density = getResources().getDisplayMetrics().density;
            float boxW = current ? 1.48f * f3 : f3;
            float left = centerX - boxW / 2f;
            if (current) {
                // 黑胶：袖套之后抽出（reveal 决定露出量）
                float d = 0.97f * f3;
                float discCx = left + f3 * 0.5f + mReveal * f3 * 0.48f;
                float discCy = top + f3 / 2f;
                mPaint.setShader(new android.graphics.RadialGradient(discCx, discCy, d / 2f,
                        new int[]{0xFF2A2A2C, 0xFF0A0A0C}, new float[]{0.55f, 1f},
                        android.graphics.Shader.TileMode.CLAMP));
                canvas.drawCircle(discCx, discCy, d / 2f, mPaint);
                mPaint.setShader(null);
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(1 * density);
                mPaint.setColor(0x12FFFFFF);
                for (int i = 1; i <= 3; i++) {
                    canvas.drawCircle(discCx, discCy, d / 2f * (0.55f + i * 0.12f), mPaint);
                }
                mPaint.setStyle(Paint.Style.FILL);
                // 盘心封面（随盘旋转）
                float inner = 0.22f * f3;
                canvas.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addCircle(discCx, discCy, inner / 2f, android.graphics.Path.Direction.CW);
                canvas.clipPath(clip);
                canvas.rotate(mAngle, discCx, discCy);
                if (bmp != null && !bmp.isRecycled()) {
                    float ext = inner * 0.45f;
                    canvas.drawBitmap(bmp, null, new RectF(
                            discCx - inner / 2f - ext, discCy - inner / 2f - ext,
                            discCx + inner / 2f + ext, discCy + inner / 2f + ext), mPaint);
                } else {
                    mPaint.setColor(0xFF3A3A3C);
                    canvas.drawRect(discCx - inner / 2f, discCy - inner / 2f,
                            discCx + inner / 2f, discCy + inner / 2f, mPaint);
                }
                canvas.restore();
                float pad = 0.065f * f3;
                mPaint.setColor(0xFFD4D0C5);
                canvas.drawCircle(discCx, discCy, pad / 2f, mPaint);
                float dot = 0.025f * f3;
                mPaint.setColor(0xFF080808);
                canvas.drawCircle(discCx, discCy, dot / 2f, mPaint);
            }
            // 袖套封面
            RectF sleeve = new RectF(left, top, left + f3, top + f3);
            canvas.save();
            android.graphics.Path sp = new android.graphics.Path();
            sp.addRoundRect(sleeve, 2 * density, 2 * density, android.graphics.Path.Direction.CW);
            canvas.clipPath(sp);
            if (bmp != null && !bmp.isRecycled()) {
                canvas.drawBitmap(bmp, null, sleeve, mPaint);
            } else {
                mPaint.setColor(0xFFDDD9D0);
                canvas.drawRect(sleeve, mPaint);
            }
            canvas.restore();
            // 架板条：宽 1.1×f3、高 6dp
            mPaint.setColor(0x2E252522);
            float stripW = 1.1f * f3;
            float stripY = top + f3 + 4 * density;
            canvas.drawRoundRect(centerX - stripW / 2f, stripY,
                    centerX + stripW / 2f, stripY + 6 * density,
                    3 * density, 3 * density, mPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownX = e.getX();
                    mDownY = e.getY();
                    return true;
                case MotionEvent.ACTION_UP: {
                    float dx = e.getX() - mDownX, dy = e.getY() - mDownY;
                    if (Math.abs(dx) > dpTouchSlop() || Math.abs(dy) > dpTouchSlop()) return true;
                    float density = getResources().getDisplayMetrics().density;
                    float pitch = pitchDp() * density;
                    float cx = getWidth() / 2f + mShiftPx;
                    if (mAction == null) return true;
                    if (e.getX() < cx - pitch * 0.5f) mAction.onSideTap(-1);
                    else if (e.getX() > cx + pitch * 0.5f) mAction.onSideTap(1);
                    else {
                        // 点当前槽＝收藏切换：reveal 先收 300/bs4.c → 切换 → 再抽出 600/bs4.a
                        animateReveal(0f, 300, true);
                        postDelayed(() -> {
                            if (mAction != null) mAction.onCurrentTap();
                            animateReveal(mPlaying ? 1f : 0.55f, 600, false);
                        }, 300);
                    }
                    return true;
                }
                default:
                    return true;
            }
        }

        private float dpTouchSlop() {
            return 12 * getResources().getDisplayMetrics().density;
        }

        @Override
        protected void onDetachedFromWindow() {
            Choreographer.getInstance().removeFrameCallback(mFrame);
            if (mRevealAnim != null) mRevealAnim.cancel();
            mShiftSpring.cancel();
            super.onDetachedFromWindow();
        }
    }

    // ---------- 唱片架进度 ----------

    static class ShelfSeek extends View {
        interface Listener {
            void onSeek(long ms);
        }

        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long mPos, mDur;
        private boolean mDragging;
        private float mDragFrac;
        private int mFill = INK;
        private Listener mListener;

        ShelfSeek(android.content.Context ctx) {
            super(ctx);
            mText.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
            mText.setColor(MUTED);
        }

        void setListener(Listener l) {
            mListener = l;
        }

        void setFillColor(int color) {
            mFill = color;
            invalidate();
        }

        void setProgress(long pos, long dur) {
            mPos = pos;
            mDur = dur;
            if (!mDragging) invalidate();
        }

        boolean isDragging() {
            return mDragging;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            if (w <= 0) return;
            float density = getResources().getDisplayMetrics().density;
            float cy = 24 * density;
            float lineH = 2 * density;
            float frac = mDragging ? mDragFrac
                    : (mDur > 0 ? Math.max(0f, Math.min(1f, mPos * 1f / mDur)) : 0f);
            mPaint.setColor(withAlphaF(INK, 0.12f));
            canvas.drawRoundRect(0, cy - lineH / 2f, w, cy + lineH / 2f,
                    lineH / 2f, lineH / 2f, mPaint);
            if (frac > 0f) {
                mPaint.setColor(mFill);
                canvas.drawRoundRect(0, cy - lineH / 2f, w * frac, cy + lineH / 2f,
                        lineH / 2f, lineH / 2f, mPaint);
            }
            String left = formatMs(mDragging && mDur > 0 ? Math.round(mDragFrac * mDur) : mPos);
            String right = formatMs(mDur);
            float baseY = getHeight() - 8 * density;
            canvas.drawText(left, 0, baseY, mText);
            canvas.drawText(right, w - mText.measureText(right), baseY, mText);
        }

        private static int withAlphaF(int color, float a) {
            return android.graphics.Color.argb(Math.round(255 * a),
                    android.graphics.Color.red(color), android.graphics.Color.green(color),
                    android.graphics.Color.blue(color));
        }

        private static String formatMs(long ms) {
            if (ms < 0) ms = 0;
            long s = ms / 1000;
            return (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDragging = true;
                    mDragFrac = fracOf(e.getX());
                    getParent().requestDisallowInterceptTouchEvent(true);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mDragging) {
                        mDragFrac = fracOf(e.getX());
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mDragging) {
                        mDragging = false;
                        if (mListener != null && mDur > 0) {
                            mListener.onSeek(Math.round(mDragFrac * mDur));
                        }
                        invalidate();
                    }
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        private float fracOf(float x) {
            return getWidth() <= 0 ? 0f : Math.max(0f, Math.min(1f, x / getWidth()));
        }
    }
}
