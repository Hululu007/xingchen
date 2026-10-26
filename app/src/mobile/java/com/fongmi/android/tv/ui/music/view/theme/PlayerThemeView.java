package com.fongmi.android.tv.ui.music.view.theme;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.List;

/**
 * 播放页主题视图基类（批二：封面卡片／Apple Music／流光 CD／黑胶唱片架）。
 * 数据由 MusicPlayerActivity 统一喂（绑曲／歌词行／播放态／进度 tick／收藏态），
 * 动作经 Host 回到 Activity 执行（工具面板／队列／音质／歌词版本与经典页同一套接线）。
 * 氛围层不在主题内重建：页面根下仍是 Activity 的 CoverAmbientView（封面取色＋糊封面），
 * 主题只在其上排自己的版式；经典主题的封面溶边（FadingCoverView）是经典页本体，
 * 本批一字未动。
 */
public abstract class PlayerThemeView extends FrameLayout {

    /** 主题 → 宿主（Activity）的动作回调，全是经典页已有的同一套落点。 */
    public interface Host {
        void openTools();

        void openQueue();

        void togglePlay();

        void prev();

        void next();

        void seekTo(long ms);

        void toggleFavorite();

        void artistClick();

        void albumClick();

        void openLyricPage();

        void openQuality();

        void openLyricVersions();

        /** 收起播放页（流光 CD／唱片架头行左键的瓜子语义）。 */
        void collapse();

        /** CD 表面档切换已写存档，宿主重建页面让新档生效。 */
        void themeVariantChanged();

        /** 音质筹文案（宿主与经典页同一份计算结果：实测音质，取不到为空）。 */
        default String qualityText() {
            return "";
        }

        /** 歌词筹文案（宿主与经典页同一份：逐字/逐行/纯文本/无词）。 */
        default String lyricChipText() {
            return "";
        }
    }

    protected static final PathInterpolator BS4_A = new PathInterpolator(0.2f, 0f, 0f, 1f);
    protected static final PathInterpolator BS4_B = new PathInterpolator(0f, 0f, 0f, 1f);
    protected static final PathInterpolator BS4_C = new PathInterpolator(0.3f, 0f, 1f, 1f);
    protected static final PathInterpolator BS4_E = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    protected final Host mHost;
    protected MusicTrack mTrack;
    protected Bitmap mCoverBitmap;
    protected String mCoverUrl;
    protected boolean mPlaying;
    protected boolean mFavorite;
    protected long mPos;
    protected long mDur;
    protected List<RichLyrics.Line> mLines;

    protected PlayerThemeView(Context context, Host host) {
        super(context);
        mHost = host;
    }

    // ---------- 宿主喂数据 ----------

    public final void bindTrack(MusicTrack track, int dir) {
        mTrack = track;
        mPos = 0;
        onBindTrack(track, dir);
    }

    /** 封面后到（内嵌封面兜底换图）：重拉当前曲封面。 */
    public void refreshCover() {
        if (mTrack != null) onBindTrack(mTrack, 0);
    }

    public void setPlaying(boolean playing) {
        mPlaying = playing;
        onPlayingChanged(playing);
    }

    public void setFavorite(boolean favorite) {
        mFavorite = favorite;
        onFavoriteChanged(favorite);
    }

    public void setLyricLines(List<RichLyrics.Line> lines) {
        mLines = lines;
        onLyricLines(lines);
    }

    /** 250ms tick：位置/时长/播放态（播放态以控制器现读为准，与经典页同一 tick 源）。 */
    public void onTick(long pos, long dur, boolean playing) {
        mPos = pos;
        mDur = dur;
        if (playing != mPlaying) setPlaying(playing);
        onProgress(pos, dur);
    }

    protected abstract void onBindTrack(MusicTrack track, int dir);

    protected void onPlayingChanged(boolean playing) {
    }

    protected void onFavoriteChanged(boolean favorite) {
    }

    protected void onLyricLines(List<RichLyrics.Line> lines) {
    }

    protected void onProgress(long pos, long dur) {
    }

    // ---------- 共用小件 ----------

    protected float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    protected int dpI(float v) {
        return Math.round(dp(v));
    }

    protected TextView text(float sizeSp, int color, boolean bold) {
        TextView tv = new TextView(getContext());
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return tv;
    }

    /** 字重 800（API 28+；低版本退回粗体，与经典页歌名同口径）。 */
    protected void applyWeight800(TextView tv) {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            tv.setTypeface(android.graphics.Typeface.create(tv.getTypeface(), 800, false));
        } else {
            tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
    }

    /** 按压缩放（瓜子 bounceClick 同族：按下缩、抬手回，时长 150/bs4.a）。 */
    protected void pressScale(View v, final float scale) {
        v.setOnTouchListener((view, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(scale).scaleY(scale).setDuration(150)
                        .setInterpolator(BS4_A).start();
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(150)
                        .setInterpolator(BS4_A).start();
            }
            return false;
        });
    }

    /** 拉当前曲封面位图（Glide 命中缓存与氛围层同图）；url 变了才认账，防串图。 */
    protected void loadCover(String url, int sizeDp, final java.util.function.Consumer<Bitmap> cb) {
        mCoverUrl = url;
        if (TextUtils.isEmpty(url)) {
            mCoverBitmap = null;
            cb.accept(null);
            return;
        }
        final String want = url;
        ImgUtil.load(url, sizeDp, new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                if (!want.equals(mCoverUrl)) return;
                mCoverBitmap = resource;
                cb.accept(resource);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                if (!want.equals(mCoverUrl)) return;
                mCoverBitmap = null;
                cb.accept(null);
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
            }
        });
    }

    /** 当前行序号（二分：timeMs≤pos 的最后一行；无词/纯文本回 -1）。 */
    protected int currentLineIndex(long pos) {
        if (mLines == null || mLines.isEmpty()) return -1;
        int lo = 0, hi = mLines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            RichLyrics.Line l = mLines.get(mid);
            long t = l == null || l.main == null ? Long.MAX_VALUE : l.main.getTimeMs();
            if (t <= pos) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    protected String lineText(int idx) {
        if (mLines == null || idx < 0 || idx >= mLines.size()) return "";
        RichLyrics.Line l = mLines.get(idx);
        if (l == null || l.main == null || l.main.getText() == null) return "";
        return l.main.getText();
    }

    protected String lineTranslation(int idx) {
        if (mLines == null || idx < 0 || idx >= mLines.size()) return "";
        RichLyrics.Line l = mLines.get(idx);
        return l == null || l.translation == null ? "" : l.translation;
    }

    protected static String formatMs(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60);
    }

    /** 颜色向目标混（fraction=目标占比），瓜子 lerp 口径。 */
    protected static int mixColor(int from, int to, float fraction) {
        float f = Math.max(0f, Math.min(1f, fraction));
        return Color.argb(
                Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f));
    }

    protected static int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(255 * Math.max(0f, Math.min(1f, alpha))),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    protected static float luminance(int color) {
        return (0.2126f * Color.red(color) + 0.7152f * Color.green(color)
                + 0.0722f * Color.blue(color)) / 255f;
    }
}
