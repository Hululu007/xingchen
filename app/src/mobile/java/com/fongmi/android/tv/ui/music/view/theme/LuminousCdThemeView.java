package com.fongmi.android.tv.ui.music.view.theme;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
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
import android.widget.Toast;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.ui.music.MusicPlayerSettings;
import com.fongmi.android.tv.utils.CoverColor;

import java.util.List;

/**
 * 流光 CD 主题（luminous_cd，瓜子案卷第六节）：三变体 Pearl 浅／Space 深／自动。
 * 页列（左右 16）：头行 64（左收起、右三个点）→旋转盘面→歌名 15/600（跑马灯）→
 * 歌手 11→歌词区（收起摘要 78／展开 21sp 粗居中多行）→五图标行 52
 * （歌曲信息/音质/歌词版本/收藏/表面切换）→CdSeekBar→运输行 80
 * （模式/上一首/播放暂停/下一首/队列）。
 * 色板混色照案卷公式：fresh=base 向封面 tone 混 .22、accent=tone 向 ink 混 .52。
 */
public class LuminousCdThemeView extends PlayerThemeView {

    private static final int PEARL_BASE = 0xFFF4F2EE;
    private static final int PEARL_MIST = 0xFFE9EFF5;
    private static final int PEARL_INK = 0xFF282D36;
    private static final int PEARL_MUTED = 0xFF747D89;
    private static final int SPACE_BASE = 0xFF14161C;
    private static final int SPACE_INK = 0xFFF2F2F7;
    private static final int SPACE_MUTED = 0xFF8E96A3;

    private boolean mDark;
    private int mBase, mMist, mInk, mMuted, mAccent, mTone;
    private float mSolidAlpha, mBgMix, mAccentMaxLum;

    private LinearLayout mRoot;
    private DiscView mDisc;
    private TextView mTitle;
    private TextView mArtist;
    private FrameLayout mLyricWrap;
    private LinearLayout mSummary;
    private TextView mSummaryMain;
    private TextView mSummarySub;
    private ThemeLyricView mLyricView;
    private LinearLayout mInfoBox;
    private boolean mLyricExpanded;
    private boolean mShowInfo;
    private ImageView mBtnInfo;
    private ImageView mBtnFav;
    private CdSeekBar mSeek;
    private ImageView mBtnMode;
    private ImageView mBtnPlay;
    private final List<ImageView> mMutedIcons = new java.util.ArrayList<>();
    private final List<ImageView> mSolidIcons = new java.util.ArrayList<>();

    public LuminousCdThemeView(android.content.Context context, Host host) {
        super(context, host);
        resolveSurface(false);
        build();
        applyPalette();
    }

    private void resolveSurface(boolean hasCover) {
        String theme = MusicPlayerSettings.getTheme();
        if (MusicPlayerSettings.THEME_LUMINOUS_CD_DARK.equals(theme)) {
            mDark = true;
        } else if (MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO.equals(theme)) {
            // 自动档的瓜子判定未定名：暂按封面 tone 明暗落档（无封面先落浅），对照表已单列
            mDark = hasCover && mTone != 0 && luminance(mTone) < 0.45f;
        } else {
            mDark = false;
        }
        if (mDark) {
            mBase = SPACE_BASE;
            mMist = SPACE_BASE;
            mInk = SPACE_INK;
            mMuted = SPACE_MUTED;
            mSolidAlpha = 0.72f;
            mBgMix = 1.0f;
            mAccentMaxLum = 0.45f;
        } else {
            mBase = PEARL_BASE;
            mMist = PEARL_MIST;
            mInk = PEARL_INK;
            mMuted = PEARL_MUTED;
            mSolidAlpha = 1.0f;
            mBgMix = 0f;
            mAccentMaxLum = 1.0f;
        }
        mAccent = mInk;
    }

    private void applyPalette() {
        int fresh = mixColor(mBase, mTone != 0 ? mTone : mBase, 0.22f);
        int midPre = mixColor(mBase, mTone != 0 ? mTone : mBase, 0.12f);
        int middle = mixColor(midPre, mBase, mBgMix);
        int botPre = mixColor(mBase, mTone != 0 ? mTone : mBase, 0.08f);
        int bottom = mixColor(botPre, mMist, mBgMix);
        int stop0 = mixColor(mBase, fresh, 0.92f);
        int stop34 = mixColor(fresh, middle, 0.48f);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{stop0, stop34, middle, bottom});
        mRoot.setBackground(bg);
        int solidInk = withAlpha(mInk, mSolidAlpha);
        mTitle.setTextColor(solidInk);
        mArtist.setTextColor(mMuted);
        mSummaryMain.setTextColor(solidInk);
        mSummarySub.setTextColor(mMuted);
        mDisc.setPalette(mBase, mMist, mInk, mAccent);
        mSeek.setColors(mInk, mAccent, mMuted);
        for (ImageView iv : mMutedIcons) iv.setColorFilter(mMuted);
        for (ImageView iv : mSolidIcons) iv.setColorFilter(solidInk);
        mBtnPlay.setColorFilter(solidInk);
        refreshIconStates();
        mLyricView.configure(new ThemeLyricView.Palette() {
            @Override
            public int sung() {
                return mAccent;
            }

            @Override
            public int currentUnsung() {
                return withAlpha(mAccent, 0.42f);
            }

            @Override
            public int inactive() {
                return withAlpha(mAccent, 0.38f);
            }

            @Override
            public int translation() {
                return withAlpha(mAccent, 0.42f);
            }
        }, 21, true, true, 28f / 21f, 20, 0f);
    }

    private void build() {
        mRoot = new LinearLayout(getContext());
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setPadding(dpI(16), 0, dpI(16), 0);
        addView(mRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(getContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        mRoot.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(64)));
        ImageView collapse = topBtn(R.drawable.ic_player_collapse, v -> mHost.collapse());
        top.addView(collapse);
        View tsp = new View(getContext());
        top.addView(tsp, new LinearLayout.LayoutParams(0, 1, 1f));
        ImageView dots = topBtn(R.drawable.ic_music_more, v -> mHost.openTools());
        top.addView(dots);
        mMutedIcons.add(collapse);
        mMutedIcons.add(dots);

        mDisc = new DiscView(getContext());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.gravity = Gravity.CENTER_HORIZONTAL;
        mRoot.addView(mDisc, dlp);

        mTitle = text(15, mInk, false);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            mTitle.setTypeface(android.graphics.Typeface.create(mTitle.getTypeface(), 600, false));
            mTitle.setLineHeight(dpI(21));
        }
        mTitle.setGravity(Gravity.CENTER);
        mTitle.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        mTitle.setMarqueeRepeatLimit(-1);
        mTitle.setSelected(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dpI(10);
        mRoot.addView(mTitle, tlp);

        mArtist = text(11, mMuted, false);
        if (android.os.Build.VERSION.SDK_INT >= 28) mArtist.setLineHeight(dpI(15));
        mArtist.setGravity(Gravity.CENTER);
        mArtist.setOnClickListener(v -> mHost.artistClick());
        mRoot.addView(mArtist, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mLyricWrap = new FrameLayout(getContext());
        LinearLayout.LayoutParams lwp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(78));
        lwp.topMargin = dpI(6);
        mRoot.addView(mLyricWrap, lwp);
        mSummary = new LinearLayout(getContext());
        mSummary.setOrientation(LinearLayout.VERTICAL);
        mSummary.setGravity(Gravity.CENTER);
        mSummary.setPadding(dpI(24), 0, dpI(24), 0);
        mSummaryMain = text(16, mInk, false);
        mSummaryMain.setGravity(Gravity.CENTER);
        mSummary.addView(mSummaryMain);
        mSummarySub = text(12, mMuted, false);
        mSummarySub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ssp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ssp.topMargin = dpI(4);
        mSummary.addView(mSummarySub, ssp);
        mLyricWrap.addView(mSummary, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mLyricView = new ThemeLyricView(getContext());
        mLyricView.setPadding(dpI(12), 0, dpI(12), 0);
        mLyricView.setVisibility(View.GONE);
        mLyricWrap.addView(mLyricView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mInfoBox = new LinearLayout(getContext());
        mInfoBox.setOrientation(LinearLayout.VERTICAL);
        mInfoBox.setGravity(Gravity.CENTER_VERTICAL);
        mInfoBox.setPadding(dpI(24), 0, dpI(24), 0);
        mInfoBox.setVisibility(View.GONE);
        mLyricWrap.addView(mInfoBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mLyricWrap.setOnClickListener(v -> {
            if (!mShowInfo) setLyricExpanded(!mLyricExpanded);
        });

        LinearLayout icons = new LinearLayout(getContext());
        icons.setOrientation(LinearLayout.HORIZONTAL);
        icons.setPadding(dpI(2), 0, dpI(2), 0);
        mRoot.addView(icons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(52)));
        mBtnInfo = iconCell(icons, R.drawable.ic_player_info, v -> toggleInfo());
        iconCell(icons, R.drawable.ic_music_eq, v -> mHost.openQuality());
        iconCell(icons, R.drawable.ic_music_note, v -> mHost.openLyricVersions());
        mBtnFav = iconCell(icons, R.drawable.ic_music_heart, v -> mHost.toggleFavorite());
        iconCell(icons, R.drawable.ic_player_surface, v -> cycleSurface());

        mSeek = new CdSeekBar(getContext());
        mSeek.setListener(ms -> mHost.seekTo(ms));
        LinearLayout.LayoutParams skp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(34));
        skp.leftMargin = dpI(8);
        skp.rightMargin = dpI(8);
        mRoot.addView(mSeek, skp);

        LinearLayout transport = new LinearLayout(getContext());
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        transport.setPadding(dpI(4), 0, dpI(4), 0);
        mRoot.addView(transport, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(80)));
        mBtnMode = transportBtn(transport, R.drawable.ic_player_sequential, 44, 23, v -> cycleMode());
        mMutedIcons.add(mBtnMode);
        mSolidIcons.add(transportBtn(transport, R.drawable.ic_music_prev, 44, 32, v -> mHost.prev()));
        mBtnPlay = transportBtn(transport, R.drawable.ic_music_play, 68, 56, v -> mHost.togglePlay());
        mSolidIcons.add(transportBtn(transport, R.drawable.ic_music_next, 44, 32, v -> mHost.next()));
        ImageView queue = transportBtn(transport, R.drawable.ic_music_queue, 44, 23, v -> mHost.openQueue());
        mMutedIcons.add(queue);
        refreshModeIcon();
    }

    private ImageView topBtn(int iconRes, View.OnClickListener click) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(iconRes);
        iv.setPadding(dpI(10), dpI(10), dpI(10), dpI(10));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dpI(44), dpI(44)));
        iv.setOnClickListener(click);
        pressScale(iv, 0.9f);
        return iv;
    }

    private ImageView iconCell(LinearLayout parent, int iconRes, View.OnClickListener click) {
        FrameLayout cell = new FrameLayout(parent.getContext());
        parent.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        ImageView iv = new ImageView(parent.getContext());
        iv.setImageResource(iconRes);
        iv.setColorFilter(mMuted);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dpI(26), dpI(26), Gravity.CENTER);
        cell.addView(iv, ilp);
        cell.setOnClickListener(click);
        pressScale(cell, 0.9f);
        if (iconRes != R.drawable.ic_player_info && iconRes != R.drawable.ic_music_heart) {
            mMutedIcons.add(iv);
        }
        return iv;
    }

    private ImageView transportBtn(LinearLayout parent, int iconRes, int boxDp, int iconDp,
                                   View.OnClickListener click) {
        FrameLayout box = new FrameLayout(parent.getContext());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        parent.addView(box, blp);
        ImageView iv = new ImageView(parent.getContext());
        iv.setImageResource(iconRes);
        iv.setColorFilter(withAlpha(mInk, mSolidAlpha));
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dpI(iconDp), dpI(iconDp), Gravity.CENTER);
        box.addView(iv, ilp);
        box.setOnClickListener(click);
        pressScale(box, 0.9f);
        return iv;
    }

    private void setLyricExpanded(boolean expanded) {
        mLyricExpanded = expanded;
        LinearLayout.LayoutParams lwp = (LinearLayout.LayoutParams) mLyricWrap.getLayoutParams();
        if (expanded) {
            lwp.height = 0;
            lwp.weight = 1f;
            mSummary.setVisibility(View.GONE);
            mLyricView.setVisibility(View.VISIBLE);
        } else {
            lwp.height = dpI(78);
            lwp.weight = 0f;
            mSummary.setVisibility(View.VISIBLE);
            mLyricView.setVisibility(View.GONE);
        }
        mLyricWrap.setLayoutParams(lwp);
    }

    private void toggleInfo() {
        mShowInfo = !mShowInfo;
        if (mShowInfo) {
            mSummary.setVisibility(View.GONE);
            mLyricView.setVisibility(View.GONE);
            buildInfoBox();
            mInfoBox.setVisibility(View.VISIBLE);
        } else {
            mInfoBox.setVisibility(View.GONE);
            if (mLyricExpanded) mLyricView.setVisibility(View.VISIBLE);
            else mSummary.setVisibility(View.VISIBLE);
        }
        refreshIconStates();
    }

    private void buildInfoBox() {
        mInfoBox.removeAllViews();
        if (mTrack == null) return;
        addInfoLine("歌名", mTrack.displayTitle());
        addInfoLine("歌手", mTrack.displayArtist());
        addInfoLine("专辑", TextUtils.isEmpty(mTrack.displayAlbum()) ? "—" : mTrack.displayAlbum());
        String q = mHost.qualityText();
        addInfoLine("音质", TextUtils.isEmpty(q) ? "—" : q);
    }

    private void addInfoLine(String label, String value) {
        TextView tv = text(13, mMuted, false);
        tv.setText(label + "  " + value);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpI(3);
        lp.bottomMargin = dpI(3);
        mInfoBox.addView(tv, lp);
    }

    private void refreshIconStates() {
        if (mBtnInfo != null) mBtnInfo.setColorFilter(mShowInfo ? mAccent : mMuted);
        if (mBtnFav != null) {
            mBtnFav.setImageResource(mFavorite
                    ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
            mBtnFav.setColorFilter(mMuted);
        }
    }

    private void cycleSurface() {
        String cur = MusicPlayerSettings.getTheme();
        String next;
        String name;
        if (MusicPlayerSettings.THEME_LUMINOUS_CD.equals(cur)) {
            next = MusicPlayerSettings.THEME_LUMINOUS_CD_DARK;
            name = "流光 · 深色";
        } else if (MusicPlayerSettings.THEME_LUMINOUS_CD_DARK.equals(cur)) {
            next = MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO;
            name = "流光 · 自动";
        } else {
            next = MusicPlayerSettings.THEME_LUMINOUS_CD;
            name = "流光 · 浅色";
        }
        MusicPlayerSettings.setTheme(next);
        Toast.makeText(getContext(), name, Toast.LENGTH_SHORT).show();
        mHost.themeVariantChanged();
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
        if (mShowInfo) buildInfoBox();
        mDisc.onSongChange(dir);
        loadCover(track.cover, 512, bmp -> {
            mDisc.setCover(bmp);
            if (bmp != null) {
                mTone = CoverColor.cdToneColor(bmp);
                int accent = mixColor(mTone, mInk, 0.52f);
                if (luminance(accent) > mAccentMaxLum) accent = mixColor(accent, mBase, 0.5f);
                mAccent = accent;
                resolveSurface(true);
                applyPalette();
            }
        });
        mLyricView.setLines(null);
        mSummaryMain.setText("");
        mSummarySub.setText("");
    }

    @Override
    protected void onPlayingChanged(boolean playing) {
        mBtnPlay.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        mDisc.setPlaying(playing);
    }

    @Override
    protected void onFavoriteChanged(boolean favorite) {
        refreshIconStates();
    }

    @Override
    protected void onLyricLines(List<RichLyrics.Line> lines) {
        mLyricView.setLines(lines);
    }

    @Override
    protected void onProgress(long pos, long dur) {
        if (!mSeek.isDragging()) mSeek.setProgress(pos, dur);
        mLyricView.setPosition(pos);
        int idx = currentLineIndex(pos);
        mSummaryMain.setText(idx >= 0 ? lineText(idx) : "");
        String sub = idx >= 0 ? lineTranslation(idx) : "";
        if (sub.isEmpty() && idx + 1 >= 0) sub = lineText(idx + 1);
        mSummarySub.setText(sub);
    }

    // ---------- 旋转盘面 ----------

    private class DiscView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private Bitmap mCover;
        private float mAngle;
        private boolean mPlaying;
        private long mLastFrame;
        private float mSweepMix = 1f;
        private int mSweepDir = 1;
        private long mSweepStart;
        private int mBaseC, mMistC, mInkC, mAccentC;
        private final Choreographer.FrameCallback mFrame = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                long now = System.nanoTime();
                if (mLastFrame > 0) {
                    float dt = Math.min(0.05f, (now - mLastFrame) / 1e9f);
                    if (mPlaying) mAngle = (mAngle + 16f * dt) % 360f;
                }
                mLastFrame = now;
                if (mSweepStart > 0) {
                    float mix = (System.currentTimeMillis() - mSweepStart) / 600f;
                    if (mix >= 1f) {
                        mSweepMix = 1f;
                        mSweepStart = 0;
                    } else {
                        mSweepMix = mix;
                    }
                }
                invalidate();
                // 只在播放中或扫光进行时续帧，暂停且无扫光即停（省电口径）
                if (isAttached() && (mPlaying || mSweepStart > 0)) {
                    Choreographer.getInstance().postFrameCallback(this);
                } else {
                    mFrameRunning = false;
                    mLastFrame = 0;
                }
            }
        };
        private boolean mFrameRunning;

        DiscView(android.content.Context ctx) {
            super(ctx);
        }

        private boolean isAttached() {
            return getWindowToken() != null;
        }

        private void startFrames() {
            if (!mFrameRunning && isAttached()) {
                mFrameRunning = true;
                mLastFrame = 0;
                Choreographer.getInstance().postFrameCallback(mFrame);
            }
        }

        void setPalette(int base, int mist, int ink, int accent) {
            mBaseC = base;
            mMistC = mist;
            mInkC = ink;
            mAccentC = accent;
            invalidate();
        }

        void setCover(Bitmap bmp) {
            mCover = bmp;
            invalidate();
        }

        void setPlaying(boolean playing) {
            mPlaying = playing;
            if (playing) startFrames();
        }

        void onSongChange(int dir) {
            mSweepDir = dir == 0 ? 1 : dir;
            mSweepMix = 0f;
            mSweepStart = System.currentTimeMillis();
            startFrames();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!mFrameRunning) {
                mFrameRunning = true;
                mLastFrame = 0;
                Choreographer.getInstance().postFrameCallback(mFrame);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            Choreographer.getInstance().removeFrameCallback(mFrame);
            mFrameRunning = false;
            super.onDetachedFromWindow();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            int size = Math.min(w, Math.round(400 * getResources().getDisplayMetrics().density));
            setMeasuredDimension(size, size);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = Math.min(getWidth(), getHeight());
            if (size <= 0) return;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = size / 2f;
            // 盘面底：径向 mist→base
            mPaint.setShader(new RadialGradient(cx, cy, r,
                    new int[]{mMistC, mBaseC}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, mPaint);
            mPaint.setShader(null);
            // 封面：圆角方（边= f×0.89285713、圆角=边×0.05），随盘旋转
            float side = size * 0.89285713f;
            float rad = side * 0.05f;
            canvas.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(new RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f),
                    rad, rad, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            canvas.rotate(mAngle, cx, cy);
            if (mCover != null && !mCover.isRecycled()) {
                float ext = side * 0.42f; // 旋转时角不露底：放大绘制再裁
                canvas.drawBitmap(mCover, null, new RectF(
                        cx - side / 2f - ext, cy - side / 2f - ext,
                        cx + side / 2f + ext, cy + side / 2f + ext), mPaint);
            } else {
                mPaint.setColor(0xFF3A3A3C);
                canvas.drawRect(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f, mPaint);
            }
            canvas.restore();
            // 切歌扫光：presence = sin(mix·π)×0.65 的斜向光带，角度随 mix×方向×90°
            if (mSweepMix < 1f) {
                float presence = (float) Math.sin(mSweepMix * Math.PI) * 0.65f;
                if (presence > 0.01f) {
                    canvas.save();
                    android.graphics.Path discClip = new android.graphics.Path();
                    discClip.addCircle(cx, cy, r, android.graphics.Path.Direction.CW);
                    canvas.clipPath(discClip);
                    canvas.rotate(mSweepMix * mSweepDir * 90f, cx, cy);
                    int a = Math.round(255 * presence);
                    mPaint.setShader(new LinearGradient(cx - r, 0, cx + r, 0,
                            new int[]{0x00FFFFFF, (a << 24) | 0xFFFFFF, 0x00FFFFFF},
                            new float[]{0.42f, 0.5f, 0.58f}, Shader.TileMode.CLAMP));
                    canvas.drawRect(cx - r, cy - r, cx + r, cy + r, mPaint);
                    mPaint.setShader(null);
                    canvas.restore();
                }
            }
            // 盘芯 hub：10 档停点径向环（停点位置照案卷，色以 ink alpha 梯度近似）
            float hubR = r * 0.16f;
            float[] stops = {0f, 0.15f, 0.27f, 0.31f, 0.38f, 0.5f, 0.67f, 0.79f, 0.84f, 1f};
            int[] cols = new int[stops.length];
            for (int i = 0; i < stops.length; i++) {
                float al = (i % 2 == 0) ? 0.20f : 0.06f;
                cols[i] = withAlpha(mInkC, al);
            }
            mPaint.setShader(new RadialGradient(cx, cy, hubR, cols, stops, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, hubR, mPaint);
            mPaint.setShader(null);
            mPaint.setColor(withAlpha(mAccentC, 0.85f));
            canvas.drawCircle(cx, cy, r * 0.028f, mPaint);
        }
    }

    // ---------- CdSeekBar ----------

    private class CdSeekBar extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long mPos, mDur;
        private boolean mDragging;
        private float mDragFrac;
        private int mInkC, mAccentC, mMutedC;
        private Listener mListener;

        CdSeekBar(android.content.Context ctx) {
            super(ctx);
            float sd = getResources().getDisplayMetrics().scaledDensity;
            mText.setTextSize(9 * sd);
        }

        void setListener(Listener l) {
            mListener = l;
        }

        void setColors(int ink, int accent, int muted) {
            mInkC = ink;
            mAccentC = accent;
            mMutedC = muted;
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
            float lineY = 5 * density;
            float lineH = 2 * density;
            float frac = mDragging ? mDragFrac
                    : (mDur > 0 ? Math.max(0f, Math.min(1f, mPos * 1f / mDur)) : 0f);
            mPaint.setColor(withAlpha(mInkC, 0.10f));
            canvas.drawRoundRect(0, lineY - lineH / 2f, w, lineY + lineH / 2f,
                    lineH / 2f, lineH / 2f, mPaint);
            if (frac > 0f) {
                mPaint.setColor(mAccentC);
                canvas.drawRoundRect(0, lineY - lineH / 2f, w * frac, lineY + lineH / 2f,
                        lineH / 2f, lineH / 2f, mPaint);
            }
            mText.setColor(withAlpha(mMutedC, 0.74f));
            String left = formatMs(mDragging && mDur > 0 ? Math.round(mDragFrac * mDur) : mPos);
            String right = formatMs(mDur);
            canvas.drawText(left, 0, getHeight() - 2 * density, mText);
            canvas.drawText(right, w - mText.measureText(right), getHeight() - 2 * density, mText);
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

    private interface Listener {
        void onSeek(long ms);
    }
}
