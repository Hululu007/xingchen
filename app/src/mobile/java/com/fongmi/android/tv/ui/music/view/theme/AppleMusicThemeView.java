package com.fongmi.android.tv.ui.music.view.theme;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.ui.music.view.InPageLyricsView;

import java.util.List;

/**
 * Apple Music 主题（瓜子案卷第五节）：横滑三页 pager（信息／封面／全屏歌词，默认封面页，
 * 落位 tween 200/bs4.b）。封面页：封面盒 weight=1 铺满→标题列上叠 −12dp（歌名 20/800、
 * 歌手 14、专辑行 12 仅非空且≠未知专辑；头右爱心＋三个点圆形玻璃键 34/20）→
 * 底部栈：进度条（轨 6、填充 0.58/0.9、无顶盒底行）→控制行 96（上叠 −18；56/84/56）→
 * 音量行 32→功能行 52（歌词/音质/播放列表，alpha 0.5）。
 * 封面切歌转场 600/bs4.a：入 0.97→1、出→1.03＋alpha→0、无滑动。
 */
public class AppleMusicThemeView extends PlayerThemeView {

    private static final int PAGE_INFO = 0;
    private static final int PAGE_COVER = 1;
    private static final int PAGE_LYRICS = 2;

    private FrameLayout mPagerBox;
    private FrameLayout mPagerStrip;
    private final java.util.List<View> mPages = new java.util.ArrayList<>();
    private int mPage = PAGE_COVER;
    private float mPagerStartTx;

    private ImageView mCover;
    private ImageView mCoverOut;
    private TextView mTitle;
    private TextView mArtist;
    private TextView mAlbum;
    private ImageView mBtnFav;
    private AppleScrubberView mScrubber;
    private FrameLayout mBtnPlay;
    private InPageLyricsView mLyrics;
    private SeekBar mVolume;

    private ImageView mInfoCover;
    private TextView mInfoTitle;
    private TextView mInfoSub;
    private TextView mInfoArtist;
    private TextView mInfoAlbum;
    private TextView mInfoDuration;
    private TextView mInfoQuality;

    public AppleMusicThemeView(Context context, Host host) {
        super(context, host);
        build();
    }

    /** 横滑 pager 容器：横向拖动超阈值才拦截（按钮点按、进度条拖动不抢）。 */
    private class PagerBox extends FrameLayout {
        private float downX;
        private float downY;
        private boolean dragging;

        PagerBox(Context ctx) {
            super(ctx);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragging = false;
                    mPagerStrip.animate().cancel();
                    mPagerStartTx = mPagerStrip.getTranslationX();
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!dragging && Math.abs(dx) > dp(14) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        dragging = true;
                        return true;
                    }
                    break;
                }
                default:
                    break;
            }
            return super.onInterceptTouchEvent(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: {
                    int w = getWidth();
                    if (w <= 0) return true;
                    float dx = e.getRawX() - downX;
                    float min = -2f * w, max = 0f;
                    mPagerStrip.setTranslationX(Math.max(min, Math.min(max, mPagerStartTx + dx)));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    int w = getWidth();
                    if (w > 0 && dragging) {
                        float tx = mPagerStrip.getTranslationX();
                        int target = Math.round(-tx / w);
                        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            float dx = e.getRawX() - downX;
                            if (dx < -dp(60)) target = mPage + 1;
                            else if (dx > dp(60)) target = mPage - 1;
                        }
                        mPage = Math.max(0, Math.min(2, target));
                        layoutPager(true);
                    }
                    dragging = false;
                    return true;
                }
                default:
                    return true;
            }
        }
    }

    private void build() {
        mPagerBox = new PagerBox(getContext());
        addView(mPagerBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mPagerStrip = new FrameLayout(getContext());
        mPagerStrip.setClipChildren(false);
        mPagerBox.addView(mPagerStrip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mPages.add(buildInfoPage());
        mPages.add(buildCoverPage());
        mLyrics = new InPageLyricsView(getContext());
        mLyrics.setListener(timeMs -> mHost.seekTo(timeMs));
        LinearLayout lyricPage = new LinearLayout(getContext());
        lyricPage.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        llp.leftMargin = dpI(28);
        llp.rightMargin = dpI(28);
        lyricPage.addView(mLyrics, llp);
        mPages.add(lyricPage);
        for (View p : mPages) {
            mPagerStrip.addView(p, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        mPagerBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> layoutPager(false));
    }

    private void layoutPager(boolean animate) {
        int w = mPagerBox.getWidth();
        if (w <= 0) return;
        // 三页按页宽手动定位（第 i 页 leftMargin=i×页宽），整带平移切换
        for (int i = 0; i < mPages.size(); i++) {
            View child = mPages.get(i);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) child.getLayoutParams();
            if (lp.width != w || lp.leftMargin != i * w) {
                lp.width = w;
                lp.leftMargin = i * w;
                child.setLayoutParams(lp);
            }
        }
        float target = -mPage * w;
        if (animate) {
            mPagerStrip.animate().translationX(target).setDuration(200)
                    .setInterpolator(BS4_B).start();
        } else {
            mPagerStrip.animate().cancel();
            mPagerStrip.setTranslationX(target);
        }
    }

    private void goPage(int page) {
        mPage = Math.max(0, Math.min(2, page));
        layoutPager(true);
    }

    // ---------- 信息页（SongInfo 构成：玻璃 Hero＋信息行） ----------

    private View buildInfoPage() {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dpI(24), dpI(64), dpI(24), 0);
        LinearLayout hero = new LinearLayout(getContext());
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dpI(16), dpI(16), dpI(16), dpI(16));
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0x14FFFFFF);
        hb.setCornerRadius(dp(20));
        hb.setStroke(dpI(1), 0x24FFFFFF);
        hero.setBackground(hb);
        mInfoCover = new ImageView(getContext());
        mInfoCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mInfoCover.setClipToOutline(true);
        mInfoCover.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
            }
        });
        hero.addView(mInfoCover, new LinearLayout.LayoutParams(dpI(80), dpI(80)));
        LinearLayout heroText = new LinearLayout(getContext());
        heroText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams htp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        htp.leftMargin = dpI(14);
        hero.addView(heroText, htp);
        mInfoTitle = text(20, 0xFFF2F2F7, true);
        mInfoTitle.setSingleLine(false);
        mInfoTitle.setMaxLines(2);
        heroText.addView(mInfoTitle);
        mInfoSub = text(12, 0xFFA8A8AE, false);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.topMargin = dpI(4);
        heroText.addView(mInfoSub, stp);
        col.addView(hero);

        LinearLayout section = new LinearLayout(getContext());
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dpI(16), dpI(12), dpI(16), dpI(12));
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(0x14FFFFFF);
        sb.setCornerRadius(dp(20));
        sb.setStroke(dpI(1), 0x24FFFFFF);
        section.setBackground(sb);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dpI(16);
        col.addView(section, slp);
        mInfoArtist = infoRow(section, "歌手");
        mInfoAlbum = infoRow(section, "专辑");
        mInfoDuration = infoRow(section, "时长");
        mInfoQuality = infoRow(section, "音质");
        return col;
    }

    private TextView infoRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dpI(6), 0, dpI(6));
        TextView lab = text(12, 0xFFA8A8AE, false);
        lab.setText(label);
        row.addView(lab, new LinearLayout.LayoutParams(dpI(52), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView val = text(13, 0xFFF2F2F7, false);
        val.setGravity(Gravity.END);
        row.addView(val, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row);
        return val;
    }

    // ---------- 封面页（主版式） ----------

    private View buildCoverPage() {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);

        FrameLayout coverBox = new FrameLayout(getContext());
        col.addView(coverBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mCoverOut = new ImageView(getContext());
        mCoverOut.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mCoverOut.setVisibility(View.GONE);
        coverBox.addView(mCoverOut, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mCover = new ImageView(getContext());
        mCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverBox.addView(mCover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 标题列：相对封面下沿上叠 −12dp
        LinearLayout meta = new LinearLayout(getContext());
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = dpI(24);
        mlp.rightMargin = dpI(24);
        mlp.topMargin = dpI(-12);
        col.addView(meta, mlp);

        LinearLayout titles = new LinearLayout(getContext());
        titles.setOrientation(LinearLayout.VERTICAL);
        meta.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mTitle = text(20, 0xF5FFFFFF, false);
        applyWeight800(mTitle);
        mTitle.setLetterSpacing(-0.02f);
        if (android.os.Build.VERSION.SDK_INT >= 28) mTitle.setLineHeight(dpI(26));
        titles.addView(mTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(30)));
        View g5 = new View(getContext());
        titles.addView(g5, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpI(5)));
        mArtist = text(14, 0x99FFFFFF, false);
        if (android.os.Build.VERSION.SDK_INT >= 28) mArtist.setLineHeight(dpI(18));
        titles.addView(mArtist, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(22)));
        mArtist.setOnClickListener(v -> mHost.artistClick());
        mAlbum = text(12, 0x6BFFFFFF, false);
        if (android.os.Build.VERSION.SDK_INT >= 28) mAlbum.setLineHeight(dpI(16));
        titles.addView(mAlbum, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(18)));
        mAlbum.setOnClickListener(v -> mHost.albumClick());

        View g8 = new View(getContext());
        g8.setLayoutParams(new LinearLayout.LayoutParams(dpI(8), ViewGroup.LayoutParams.MATCH_PARENT));
        meta.addView(g8);
        mBtnFav = roundGlassBtn(R.drawable.ic_music_heart);
        meta.addView(mBtnFav);
        View g12 = new View(getContext());
        g12.setLayoutParams(new LinearLayout.LayoutParams(dpI(12), ViewGroup.LayoutParams.MATCH_PARENT));
        meta.addView(g12);
        ImageView dots = roundGlassBtn(R.drawable.ic_music_more);
        meta.addView(dots);
        dots.setOnClickListener(v -> mHost.openTools());
        mBtnFav.setOnClickListener(v -> mHost.toggleFavorite());

        // 底部栈
        mScrubber = new AppleScrubberView(getContext());
        mScrubber.setListener(ms -> mHost.seekTo(ms));
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(18));
        scp.leftMargin = dpI(24);
        scp.rightMargin = dpI(24);
        scp.topMargin = dpI(10);
        col.addView(mScrubber, scp);

        LinearLayout controls = new LinearLayout(getContext());
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(96));
        clp.topMargin = dpI(-18);
        col.addView(controls, clp);
        controls.addView(transportBtn(R.drawable.ic_music_prev, 56, 48, 42, v -> mHost.prev()));
        View cg = new View(getContext());
        controls.addView(cg, new LinearLayout.LayoutParams(dpI(24), 1));
        mBtnPlay = transportBtn(R.drawable.ic_music_play, 84, 60, 52, v -> mHost.togglePlay());
        controls.addView(mBtnPlay);
        View cg2 = new View(getContext());
        controls.addView(cg2, new LinearLayout.LayoutParams(dpI(24), 1));
        controls.addView(transportBtn(R.drawable.ic_music_next, 56, 48, 42, v -> mHost.next()));

        LinearLayout volume = new LinearLayout(getContext());
        volume.setOrientation(LinearLayout.HORIZONTAL);
        volume.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(32));
        vlp.leftMargin = dpI(24);
        vlp.rightMargin = dpI(24);
        vlp.topMargin = dpI(6);
        col.addView(volume, vlp);
        ImageView vLow = new ImageView(getContext());
        vLow.setImageResource(R.drawable.ic_widget_volume_low);
        vLow.setColorFilter(0xFFFFFFFF);
        vLow.setAlpha(0.6f);
        volume.addView(vLow, new LinearLayout.LayoutParams(dpI(18), dpI(18)));
        mVolume = new SeekBar(getContext());
        mVolume.setProgressDrawable(getContext().getDrawable(R.drawable.bg_player_volume_progress));
        mVolume.setThumb(getContext().getDrawable(R.drawable.bg_player_seek_thumb));
        LinearLayout.LayoutParams vsp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vsp.leftMargin = dpI(10);
        vsp.rightMargin = dpI(10);
        volume.addView(mVolume, vsp);
        ImageView vHigh = new ImageView(getContext());
        vHigh.setImageResource(R.drawable.ic_widget_volume_high);
        vHigh.setColorFilter(0xFFFFFFFF);
        vHigh.setAlpha(0.6f);
        volume.addView(vHigh, new LinearLayout.LayoutParams(dpI(18), dpI(18)));
        initVolume();

        LinearLayout funcs = new LinearLayout(getContext());
        funcs.setOrientation(LinearLayout.HORIZONTAL);
        funcs.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(52));
        flp.topMargin = dpI(2);
        col.addView(funcs, flp);
        funcs.addView(funcBtn(R.drawable.ic_music_note, 24, v -> goPage(PAGE_LYRICS)));
        funcs.addView(funcBtn(R.drawable.ic_music_eq, 24, v -> mHost.openQuality()));
        funcs.addView(funcBtn(R.drawable.ic_music_queue, 28, v -> mHost.openQueue()));

        View bottom = new View(getContext());
        col.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(22)));
        return col;
    }

    private ImageView roundGlassBtn(int iconRes) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(iconRes);
        iv.setColorFilter(0xFFFFFFFF);
        iv.setAlpha(0.85f);
        iv.setPadding(dpI(7), dpI(7), dpI(7), dpI(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x24FFFFFF);
        iv.setBackground(bg);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dpI(34), dpI(34)));
        pressScale(iv, 0.88f);
        return iv;
    }

    private FrameLayout transportBtn(int iconRes, int boxDp, int iconW, int iconH,
                                     View.OnClickListener click) {
        FrameLayout box = new FrameLayout(getContext());
        box.setLayoutParams(new LinearLayout.LayoutParams(dpI(boxDp), dpI(boxDp)));
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(iconRes);
        iv.setColorFilter(0xFFFFFFFF);
        iv.setAlpha(0.95f);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dpI(iconW), dpI(iconH), Gravity.CENTER);
        box.addView(iv, ilp);
        box.setOnClickListener(click);
        pressScale(box, boxDp >= 84 ? 0.9f : 0.88f);
        return box;
    }

    private ImageView funcBtn(int iconRes, int iconDp, View.OnClickListener click) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(iconRes);
        iv.setColorFilter(0xFFFFFFFF);
        iv.setAlpha(0.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpI(iconDp + 24), dpI(52));
        iv.setPadding(dpI(12), dpI((52 - iconDp) / 2), dpI(12), dpI((52 - iconDp) / 2));
        iv.setLayoutParams(lp);
        iv.setOnClickListener(click);
        pressScale(iv, 0.9f);
        return iv;
    }

    private void initVolume() {
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            mVolume.setMax(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            mVolume.setProgress(am.getStreamVolume(AudioManager.STREAM_MUSIC));
            mVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    try {
                        AudioManager a = (AudioManager) getContext()
                                .getSystemService(Context.AUDIO_SERVICE);
                        if (a != null) a.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onBindTrack(MusicTrack track, int dir) {
        mTitle.setText(track.displayTitle());
        mArtist.setText(track.displayArtist());
        String album = track.displayAlbum();
        boolean showAlbum = !TextUtils.isEmpty(album) && !"未知专辑".equals(album.trim());
        mAlbum.setVisibility(showAlbum ? View.VISIBLE : View.GONE);
        mAlbum.setText(showAlbum ? album : "");
        mInfoTitle.setText(track.displayTitle());
        mInfoSub.setText(track.displayArtist());
        mInfoArtist.setText(track.displayArtist());
        mInfoAlbum.setText(TextUtils.isEmpty(album) ? "—" : album);
        mInfoDuration.setText(track.duration > 0 ? formatMs(track.duration) : "—");
        try {
            com.fongmi.android.tv.music.MusicAudioInfo info =
                    com.fongmi.android.tv.music.MusicController.get().audioInfo();
            mInfoQuality.setText(info != null && info.describe() != null ? info.describe() : "—");
        } catch (Throwable ignored) {
            mInfoQuality.setText("—");
        }
        loadCover(track.cover, 720, bmp -> {
            if (bmp == null) {
                mCover.setImageDrawable(null);
                mInfoCover.setImageDrawable(null);
                return;
            }
            mInfoCover.setImageBitmap(bmp);
            Bitmap old = null;
            if (mCover.getDrawable() instanceof android.graphics.drawable.BitmapDrawable) {
                old = ((android.graphics.drawable.BitmapDrawable) mCover.getDrawable()).getBitmap();
            }
            if (old == null || old == bmp) {
                mCover.setImageBitmap(bmp);
                return;
            }
            // 切歌转场 600/bs4.a：入 0.97→1、出 →1.03＋alpha→0、无滑动
            mCoverOut.setImageBitmap(old);
            mCoverOut.setVisibility(View.VISIBLE);
            mCoverOut.setAlpha(1f);
            mCoverOut.setScaleX(1f);
            mCoverOut.setScaleY(1f);
            mCover.setImageBitmap(bmp);
            mCover.setScaleX(0.97f);
            mCover.setScaleY(0.97f);
            mCover.animate().scaleX(1f).scaleY(1f).setDuration(600)
                    .setInterpolator(BS4_A).start();
            mCoverOut.animate().scaleX(1.03f).scaleY(1.03f).alpha(0f).setDuration(600)
                    .setInterpolator(BS4_A)
                    .withEndAction(() -> mCoverOut.setVisibility(View.GONE)).start();
        });
        mLyrics.setLines(null);
    }

    @Override
    protected void onPlayingChanged(boolean playing) {
        // mBtnPlay 是 FrameLayout 盒，图标在 0 号子位
        ImageView icon = (ImageView) mBtnPlay.getChildAt(0);
        icon.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        mLyrics.setPlaying(playing);
    }

    @Override
    protected void onFavoriteChanged(boolean favorite) {
        mBtnFav.setImageResource(favorite
                ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
    }

    @Override
    protected void onLyricLines(List<RichLyrics.Line> lines) {
        mLyrics.setLines(lines);
    }

    @Override
    protected void onProgress(long pos, long dur) {
        if (!mScrubber.isDragging()) mScrubber.setProgress(pos, dur);
        mLyrics.setPosition(pos);
    }
}
