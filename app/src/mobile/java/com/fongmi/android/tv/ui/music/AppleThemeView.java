package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.RichLyrics;

import java.util.List;

/**
 * Apple Music 主题（照瓜子 AppleMusicPlayer 复刻）：头部（歌名 20sp/800/白.96、歌手 14sp/白.6、
 * 专辑 12sp/白.42 仅有效时显示；右侧爱心＋三点玻璃圆盒 34dp）→封面（切歌 600ms 缩放 .97→1）
 * →底部栈：进度（padH24、轨 6、填充 .58/.9、无时间行）→控制行高 96 上叠 −18
 * （三键间距 24：上/下首盒 56 图标 48×42、播放盒 84 图标 60×52.5）→音量行 32→功能行 52
 * （歌词 24/音质 24/列表 28，间距 48）→底部 22。不显示时间（已定谳）。
 */
public class AppleThemeView extends FrameLayout implements PlayerThemeFace {

    private final float density;
    private PlayerThemeFace.Callback callback;

    private TextView titleView;
    private TextView artistView;
    private TextView albumView;
    private ImageView heartView;
    private ImageView coverView;
    private PlayerScrubberView scrubber;
    private ImageView playIcon;
    private ImageView pauseIcon;
    private VolumeSliderView volumeSlider;
    private LinearLayout bottomStack;

    private boolean playing;
    private boolean favorite;

    public AppleThemeView(Context context) {
        this(context, null);
    }

    public AppleThemeView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        build(context);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private void build(Context c) {
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(8), dp(24), 0);
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(header, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        header.addView(titles, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        titleView = GzViews.text(c, "", 20, 800, 0xF5FFFFFF);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(titleView);
        artistView = GzViews.text(c, "", 14, 500, 0x99FFFFFF);
        artistView.setSingleLine(true);
        artistView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        artistLp.topMargin = dp(2);
        titles.addView(artistView, artistLp);
        albumView = GzViews.text(c, "", 12, 500, 0x6BFFFFFF);
        albumView.setSingleLine(true);
        albumView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams albumLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        albumLp.topMargin = dp(1);
        titles.addView(albumView, albumLp);

        heartView = new ImageView(c);
        heartView.setImageResource(R.drawable.ic_gz_heart);
        LinearLayout.LayoutParams heartLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        heartView.setPadding(dp(8), dp(8), dp(8), dp(8));
        heartView.setLayoutParams(heartLp);
        heartView.setAlpha(0.30f);
        heartView.setOnClickListener(v -> {
            if (callback != null) callback.onFavoriteClick();
        });
        GzViews.pressScale(heartView, 0.88f);
        header.addView(heartView);

        FrameLayout dotsBtn = new FrameLayout(c);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        dotsLp.leftMargin = dp(10);
        dotsBtn.setLayoutParams(dotsLp);
        GradientDrawable dotsBg = new GradientDrawable();
        dotsBg.setColor(0x24FFFFFF);
        dotsBg.setShape(GradientDrawable.OVAL);
        dotsBtn.setBackground(dotsBg);
        ImageView dotsIcon = GzViews.icon(c, R.drawable.ic_gz_dots, 20, 0.85f);
        FrameLayout.LayoutParams diLp = new FrameLayout.LayoutParams(dp(20), dp(20));
        diLp.gravity = Gravity.CENTER;
        dotsIcon.setLayoutParams(diLp);
        dotsBtn.addView(dotsIcon);
        dotsBtn.setOnClickListener(v -> {
            if (callback != null) callback.onOpenTools();
        });
        GzViews.pressScale(dotsBtn, 0.88f);
        header.addView(dotsBtn);

        FrameLayout coverBox = new FrameLayout(c);
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        coverLp.topMargin = dp(14);
        coverLp.bottomMargin = dp(6);
        column.addView(coverBox, coverLp);
        coverView = new ImageView(c);
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverBox.addView(coverView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        bottomStack = new LinearLayout(c);
        bottomStack.setOrientation(LinearLayout.VERTICAL);
        column.addView(bottomStack, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        scrubber = new PlayerScrubberView(c);
        scrubber.trackHeightDp = 6f;
        scrubber.fillIdle = 0.58f;
        scrubber.fillDrag = 0.9f;
        scrubber.showTopRow = false;
        scrubber.showBottomRow = false;
        bottomStack.addView(scrubber, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(18)));
        scrubber.setListener(new PlayerScrubberView.Listener() {
            @Override
            public void onScrubPreview(long positionMs) {
            }

            @Override
            public void onSeekDragEnd(long positionMs) {
                if (callback != null) callback.onSeekMs(positionMs);
            }

            @Override
            public void onSeekTap(long positionMs) {
                if (callback != null) callback.onSeekMs(positionMs);
            }

            @Override
            public void onQualityChipTap() {
            }

            @Override
            public void onLyricChipTap() {
            }
        });

        LinearLayout controls = new LinearLayout(c);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(96));
        controlsLp.topMargin = -dp(18);
        bottomStack.addView(controls, controlsLp);
        controls.addView(transportButton(c, R.drawable.ic_gz_prev, 56, 48, 42, () -> {
            if (callback != null) callback.onPrev();
        }));
        FrameLayout playBox = new FrameLayout(c);
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(84), dp(84));
        playLp.leftMargin = dp(24);
        playLp.rightMargin = dp(24);
        playBox.setLayoutParams(playLp);
        playIcon = GzViews.icon(c, R.drawable.ic_gz_play, 60, 1f);
        pauseIcon = GzViews.icon(c, R.drawable.ic_gz_pause, 60, 1f);
        centerIn(playBox, playIcon, 60, 52);
        centerIn(playBox, pauseIcon, 60, 52);
        playBox.setOnClickListener(v -> {
            if (callback != null) callback.onTogglePlay();
        });
        GzViews.pressScale(playBox, 0.9f);
        controls.addView(playBox);
        controls.addView(transportButton(c, R.drawable.ic_gz_next, 56, 48, 42, () -> {
            if (callback != null) callback.onNext();
        }));

        LinearLayout volumeRow = new LinearLayout(c);
        volumeRow.setOrientation(LinearLayout.HORIZONTAL);
        volumeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams volLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(32));
        volLp.topMargin = dp(6);
        bottomStack.addView(volumeRow, volLp);
        ImageView volLow = GzViews.icon(c, R.drawable.ic_gz_vol_low, 18, 0.5f);
        volumeRow.addView(volLow);
        volumeSlider = new VolumeSliderView(c);
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(0, dp(32), 1f);
        sliderLp.leftMargin = dp(8);
        sliderLp.rightMargin = dp(8);
        volumeRow.addView(volumeSlider, sliderLp);
        volumeSlider.setListener(f -> {
            if (callback != null) callback.onVolume(f);
        });
        ImageView volHigh = GzViews.icon(c, R.drawable.ic_gz_vol_high, 18, 0.5f);
        volumeRow.addView(volHigh);
        refreshVolume(c);

        LinearLayout funcRow = new LinearLayout(c);
        funcRow.setOrientation(LinearLayout.HORIZONTAL);
        funcRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams funcLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52));
        funcLp.topMargin = dp(2);
        bottomStack.addView(funcRow, funcLp);
        funcRow.addView(funcButton(c, R.drawable.ic_gz_lyric, 24, () -> {
            if (callback != null) callback.onOpenLyricPage();
        }));
        funcRow.addView(funcButton(c, R.drawable.ic_gz_quality, 24, () -> {
            if (callback != null) callback.onQualityClick();
        }));
        funcRow.addView(funcButton(c, R.drawable.ic_gz_queue, 28, () -> {
            if (callback != null) callback.onQueue();
        }));

        bottomStack.addView(new View(c), new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(22)));
    }

    private void refreshVolume(Context c) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (max > 0) volumeSlider.setFraction(cur / (float) max);
    }

    private void centerIn(FrameLayout parent, View child, float wDp, float hDp) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(wDp), dp(hDp));
        lp.gravity = Gravity.CENTER;
        child.setLayoutParams(lp);
        parent.addView(child);
    }

    private FrameLayout transportButton(Context c, int res, float boxDp, float iconW, float iconH, Runnable action) {
        FrameLayout box = new FrameLayout(c);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(boxDp), dp(boxDp)));
        ImageView iv = GzViews.icon(c, res, iconW, 1f);
        centerIn(box, iv, iconW, iconH);
        box.setOnClickListener(v -> action.run());
        GzViews.pressScale(box, 0.88f);
        return box;
    }

    private FrameLayout funcButton(Context c, int res, float iconDp, Runnable action) {
        FrameLayout box = new FrameLayout(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(52));
        lp.leftMargin = dp(24);
        lp.rightMargin = dp(24);
        box.setLayoutParams(lp);
        ImageView iv = GzViews.icon(c, res, iconDp, 0.5f);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp));
        ilp.gravity = Gravity.CENTER;
        iv.setLayoutParams(ilp);
        box.addView(iv);
        box.setOnClickListener(v -> action.run());
        GzViews.pressScale(box, 0.96f);
        return box;
    }

    @Override
    public void setCallback(PlayerThemeFace.Callback callback) {
        this.callback = callback;
    }

    @Override
    public void setTrack(String title, String artist, String album) {
        titleView.setText(title == null ? "" : title);
        artistView.setText(artist == null ? "" : artist);
        boolean showAlbum = album != null && !album.isEmpty() && !"未知专辑".equals(album);
        albumView.setVisibility(showAlbum ? VISIBLE : GONE);
        albumView.setText(showAlbum ? album : "");
    }

    @Override
    public void setCover(String url, String title, boolean animate, int dir) {
        if (url == null || url.isEmpty()) {
            coverView.setImageDrawable(null);
            return;
        }
        Glide.with(getContext()).load(url).centerCrop().into(coverView);
        if (animate) {
            coverView.setScaleX(0.97f);
            coverView.setScaleY(0.97f);
            coverView.setAlpha(0.4f);
            coverView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(600)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        }
    }

    @Override
    public void setPlaying(boolean playing) {
        this.playing = playing;
        playIcon.setVisibility(playing ? INVISIBLE : VISIBLE);
        pauseIcon.setVisibility(playing ? VISIBLE : INVISIBLE);
    }

    @Override
    public void setMode(int mode) {
    }

    @Override
    public void setLyrics(List<RichLyrics.Line> lines, boolean loading) {
    }

    @Override
    public void setLyricState(String chipLabel, boolean hasWords) {
    }

    @Override
    public void setQualityText(String text) {
    }

    @Override
    public void setInfoDetail(String detail) {
    }

    @Override
    public void setSwipeHints(String prevTitle, String nextTitle) {
    }

    @Override
    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
        heartView.setAlpha(favorite ? 0.95f : 0.30f);
        heartView.setColorFilter(favorite ? 0xFFFA243C : android.graphics.Color.WHITE);
    }

    @Override
    public boolean isFavorite() {
        return favorite;
    }

    @Override
    public void setProgress(long positionMs, long durationMs) {
        scrubber.setProgress(positionMs, durationMs);
    }

    @Override
    public void advanceLyric(long positionMs) {
    }

    @Override
    public void setBottomStackExpanded(boolean expanded) {
        bottomStack.setVisibility(expanded ? VISIBLE : INVISIBLE);
    }
}
