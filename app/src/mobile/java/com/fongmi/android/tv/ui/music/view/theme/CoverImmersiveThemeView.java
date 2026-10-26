package com.fongmi.android.tv.ui.music.view.theme;

import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.ui.music.view.ClassicScrubberView;
import com.fongmi.android.tv.ui.music.view.InPageLyricsView;

import java.util.List;

/**
 * 封面卡片主题（cover_immersive，瓜子案卷第四节）：
 * 纵列 42 间隔→歌名→歌手→封面槽（宽 min(可用宽×0.98, 可用高/0.65)、高×0.65、圆角 22，
 * 背后错位占位卡）→玻璃歌词条（48 高：左收藏／中单行歌词／右播停，无进度条无菜单队列键）。
 * 点封面展开：封面槽变 BackdropCard（渐变＋糊封面＋黑 0.35），内含四玻璃键
 * （上一首/播放列表/歌曲信息/下一首）、全默认 Scrubber、歌词窗。
 * 封面本体不带经典页的下段溶边——溶边是封面融氛围的经典页形态，本主题封面是
 * 有界卡片、与瓜子原样一致；页面根下氛围层仍是 Activity 的 CoverAmbientView。
 */
public class CoverImmersiveThemeView extends PlayerThemeView {

    private TextView mTitle;
    private TextView mArtist;
    private FrameLayout mCoverArea;
    private View mPlaceholderCard;
    private ImageView mCoverCard;
    private FrameLayout mBackdrop;
    private ImageView mBackdropImg;
    private ClassicScrubberView mScrubber;
    private InPageLyricsView mLyrics;
    private LinearLayout mGlassBar;
    private ImageView mBarFav;
    private TextView mBarLyric;
    private ImageView mBarPlay;
    private boolean mExpanded;

    public CoverImmersiveThemeView(android.content.Context context, Host host) {
        super(context, host);
        build();
    }

    private void build() {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        col.addView(spacer(42));
        mTitle = text(20, 0xF5FFFFFF, false);
        applyWeight800(mTitle);
        mTitle.setLetterSpacing(-0.02f);
        if (android.os.Build.VERSION.SDK_INT >= 28) mTitle.setLineHeight(dpI(26));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(30));
        tlp.leftMargin = dpI(28);
        tlp.rightMargin = dpI(28);
        col.addView(mTitle, tlp);

        col.addView(spacer(4));
        mArtist = text(14, 0xA6FFFFFF, false);
        if (android.os.Build.VERSION.SDK_INT >= 28) mArtist.setLineHeight(dpI(18));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(22));
        alp.leftMargin = dpI(28);
        alp.rightMargin = dpI(28);
        col.addView(mArtist, alp);
        mArtist.setOnClickListener(v -> mHost.artistClick());

        col.addView(spacer(22));
        mCoverArea = new FrameLayout(getContext());
        LinearLayout.LayoutParams cap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        cap.leftMargin = dpI(24);
        cap.rightMargin = dpI(24);
        col.addView(mCoverArea, cap);

        mPlaceholderCard = new View(getContext());
        GradientDrawable pg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x14FFFFFF, 0x08FFFFFF});
        pg.setCornerRadius(dp(22));
        pg.setStroke(dpI(1), 0x1FFFFFFF);
        mPlaceholderCard.setBackground(pg);
        mPlaceholderCard.setElevation(dp(6));
        mCoverArea.addView(mPlaceholderCard);

        mCoverCard = new ImageView(getContext());
        mCoverCard.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mCoverCard.setBackgroundColor(0xFF2C2C2E);
        mCoverCard.setClipToOutline(true);
        mCoverCard.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(22));
            }
        });
        mCoverArea.addView(mCoverCard);
        mCoverCard.setOnClickListener(v -> setExpanded(true));

        buildBackdrop();

        col.addView(spacer(14));
        mGlassBar = buildGlassBar();
        LinearLayout.LayoutParams gbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(48));
        gbp.leftMargin = dpI(24);
        gbp.rightMargin = dpI(24);
        col.addView(mGlassBar, gbp);
        col.addView(spacer(12));

        mCoverArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> layoutCoverCards());
    }

    private View spacer(float dpv) {
        View v = new View(getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(dpv)));
        return v;
    }

    /** 封面卡与错位卡同尺寸居中：宽＝min(可用宽×0.98, 可用高/0.65)、高＝宽×0.65。 */
    private void layoutCoverCards() {
        int aw = mCoverArea.getWidth(), ah = mCoverArea.getHeight();
        if (aw <= 0 || ah <= 0) return;
        float w = Math.min(aw * 0.98f, ah / 0.65f);
        float h = w * 0.65f;
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                Math.round(w), Math.round(h), Gravity.CENTER);
        mCoverCard.setLayoutParams(clp);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                Math.round(w), Math.round(h), Gravity.CENTER);
        plp.leftMargin = dpI(10);
        plp.topMargin = dpI(12);
        mPlaceholderCard.setLayoutParams(plp);
    }

    private void buildBackdrop() {
        mBackdrop = new FrameLayout(getContext());
        mBackdrop.setVisibility(View.GONE);
        mBackdrop.setClipToOutline(true);
        mBackdrop.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(22));
            }
        });
        GradientDrawable base = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF3A3A3C, 0xFF1C1C1E});
        mBackdrop.setBackground(base);
        mBackdropImg = new ImageView(getContext());
        mBackdropImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mBackdropImg.setAlpha(0.65f);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                mBackdropImg.setRenderEffect(android.graphics.RenderEffect
                        .createBlurEffect(18f, 18f, android.graphics.Shader.TileMode.CLAMP));
            } catch (Throwable ignored) {
            }
        }
        mBackdrop.addView(mBackdropImg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(getContext());
        shade.setBackgroundColor(0x59000000); // 黑 0.35
        mBackdrop.addView(shade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout inner = new LinearLayout(getContext());
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dpI(18), dpI(14), dpI(18), dpI(14));
        mBackdrop.addView(inner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout keys = new LinearLayout(getContext());
        keys.setOrientation(LinearLayout.HORIZONTAL);
        keys.setGravity(Gravity.CENTER);
        inner.addView(keys, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(42)));
        keys.addView(glassKey(R.drawable.ic_music_prev, v -> mHost.prev()));
        keys.addView(glassKey(R.drawable.ic_music_queue, v -> mHost.openQueue()));
        keys.addView(glassKey(R.drawable.ic_player_info, v -> mHost.openTools()));
        keys.addView(glassKey(R.drawable.ic_music_next, v -> mHost.next()));

        View gap = new View(getContext());
        inner.addView(gap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(12)));

        mScrubber = new ClassicScrubberView(getContext());
        mScrubber.setListener(new ClassicScrubberView.Listener() {
            @Override
            public void onSeek(long ms) {
                mHost.seekTo(ms);
            }

            @Override
            public void onScrubPreview(long ms) {
                mLyrics.setPreviewPosition(ms >= 0 ? ms : null);
            }

            @Override
            public void onQualityChip() {
                mHost.openQuality();
            }

            @Override
            public void onLyricChip() {
                mHost.openLyricVersions();
            }
        });
        inner.addView(mScrubber, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mLyrics = new InPageLyricsView(getContext());
        mLyrics.setListener(timeMs -> mHost.seekTo(timeMs));
        inner.addView(mLyrics, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mBackdrop.setOnClickListener(v -> setExpanded(false));
        mCoverArea.addView(mBackdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View glassKey(int iconRes, View.OnClickListener click) {
        FrameLayout box = new FrameLayout(getContext());
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x24FFFFFF);
        bg.setStroke(dpI(1), 0x2EFFFFFF);
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpI(42), dpI(42));
        lp.leftMargin = dpI(10);
        lp.rightMargin = dpI(10);
        box.setLayoutParams(lp);
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(iconRes);
        iv.setColorFilter(0xFFFFFFFF);
        iv.setPadding(dpI(10), dpI(10), dpI(10), dpI(10));
        box.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.setOnClickListener(click);
        pressScale(box, 0.92f);
        return box;
    }

    private LinearLayout buildGlassBar() {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dpI(16), 0, dpI(16), 0);
        bar.setElevation(dp(4));
        GradientDrawable base = new GradientDrawable();
        base.setColor(0xFF1E1E22);
        base.setCornerRadius(dp(22));
        GradientDrawable sheen = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x42FFFFFF, 0x14FFFFFF});
        sheen.setCornerRadius(dp(22));
        sheen.setStroke(dpI(1), 0x30FFFFFF);
        bar.setBackground(new LayerDrawable(new android.graphics.drawable.Drawable[]{base, sheen}));

        FrameLayout favZone = new FrameLayout(getContext());
        favZone.setLayoutParams(new LinearLayout.LayoutParams(dpI(40), ViewGroup.LayoutParams.MATCH_PARENT));
        mBarFav = new ImageView(getContext());
        mBarFav.setImageResource(R.drawable.ic_music_heart);
        mBarFav.setColorFilter(0xFFFFFFFF);
        mBarFav.setAlpha(0.45f);
        FrameLayout.LayoutParams fip = new FrameLayout.LayoutParams(dpI(20), dpI(20), Gravity.CENTER);
        favZone.addView(mBarFav, fip);
        favZone.setOnClickListener(v -> mHost.toggleFavorite());
        pressScale(favZone, 0.9f);
        bar.addView(favZone);

        mBarLyric = text(13, 0xEBFFFFFF, false);
        mBarLyric.setGravity(Gravity.CENTER);
        bar.addView(mBarLyric, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout playZone = new FrameLayout(getContext());
        playZone.setLayoutParams(new LinearLayout.LayoutParams(dpI(40), ViewGroup.LayoutParams.MATCH_PARENT));
        mBarPlay = new ImageView(getContext());
        mBarPlay.setImageResource(R.drawable.ic_music_play);
        mBarPlay.setColorFilter(0xFFFFFFFF);
        FrameLayout.LayoutParams pip = new FrameLayout.LayoutParams(dpI(24), dpI(24), Gravity.CENTER);
        playZone.addView(mBarPlay, pip);
        playZone.setOnClickListener(v -> mHost.togglePlay());
        pressScale(playZone, 0.9f);
        bar.addView(playZone);
        return bar;
    }

    private void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        if (expanded) {
            mBackdrop.setVisibility(View.VISIBLE);
            mBackdrop.setAlpha(0f);
            mBackdrop.animate().alpha(1f).setDuration(250).setInterpolator(BS4_A).start();
            mCoverCard.animate().alpha(0f).setDuration(200).setInterpolator(BS4_A)
                    .withEndAction(() -> mCoverCard.setVisibility(View.GONE)).start();
            mPlaceholderCard.setVisibility(View.GONE);
            mGlassBar.setVisibility(View.GONE);
            refreshExpandedChips();
        } else {
            mCoverCard.setVisibility(View.VISIBLE);
            mCoverCard.setAlpha(0f);
            mCoverCard.animate().alpha(1f).setDuration(250).setInterpolator(BS4_A).start();
            mPlaceholderCard.setVisibility(View.VISIBLE);
            mGlassBar.setVisibility(View.VISIBLE);
            mBackdrop.animate().alpha(0f).setDuration(200).setInterpolator(BS4_A)
                    .withEndAction(() -> mBackdrop.setVisibility(View.GONE)).start();
        }
    }

    private void refreshExpandedChips() {
        boolean local = mTrack != null
                && com.fongmi.android.tv.music.MusicDownloadStore.isLocal(mTrack);
        mScrubber.setLocalTrack(local);
        mScrubber.setChips(mHost.qualityText(), mHost.lyricChipText());
    }

    @Override
    protected void onBindTrack(MusicTrack track, int dir) {
        mTitle.setText(track.displayTitle());
        mArtist.setText(track.displayArtist());
        mArtist.setTextColor(firstArtistOk(track.displayArtist()) ? 0xA6FFFFFF : 0x8CFFFFFF);
        loadCover(track.cover, 512, bmp -> {
            if (bmp != null) {
                mCoverCard.setImageBitmap(bmp);
                mBackdropImg.setImageBitmap(bmp);
            } else {
                mCoverCard.setImageDrawable(null);
                mBackdropImg.setImageDrawable(null);
            }
        });
        if (mExpanded) refreshExpandedChips();
        mLyrics.setLines(null);
    }

    private static boolean firstArtistOk(String artist) {
        if (artist == null) return false;
        String s = artist.trim();
        return !s.isEmpty() && !"—".equals(s) && !"未知歌手".equals(s) && !"未知艺术家".equals(s);
    }

    @Override
    protected void onPlayingChanged(boolean playing) {
        mBarPlay.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        mScrubber.setPlaying(playing);
        mLyrics.setPlaying(playing);
    }

    @Override
    protected void onFavoriteChanged(boolean favorite) {
        mBarFav.setImageResource(favorite
                ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
        mBarFav.setAlpha(favorite ? 1.0f : 0.45f);
    }

    @Override
    protected void onLyricLines(List<RichLyrics.Line> lines) {
        mLyrics.setLines(lines);
    }

    @Override
    protected void onProgress(long pos, long dur) {
        if (!mScrubber.isDragging()) mScrubber.setProgress(pos, dur);
        mLyrics.setPosition(pos);
        int idx = currentLineIndex(pos);
        String line = idx >= 0 ? lineText(idx) : "";
        mBarLyric.setText(line);
        mBarLyric.setTextColor(line.isEmpty() ? 0x73FFFFFF : 0xEBFFFFFF);
    }
}
