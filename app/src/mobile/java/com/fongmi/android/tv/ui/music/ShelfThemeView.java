package com.fongmi.android.tv.ui.music;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.RichLyrics;

import java.util.List;

/**
 * 黑胶唱片架主题（照瓜子 RecordShelfPlayer 复刻）：根实色 #FFF1F0ED；
 * 头行 64（左收起/中标题 15sp+11sp/右三点盒 48）；三槽架＋黑胶 30°/s；
 * 白色信息面（上 0/下 24 圆角）内：歌词列 padH24→ShelfSeekBar（72dp）→
 * ShelfControls（76dp、键盒 48、无爱心，点当前槽唱片＝收藏）。
 * 歌词色板 (h0, h0×.48, Muted×.72, Muted)：h0＝封面 tone 色向 Ink lerp .65（定谳）。
 */
public class ShelfThemeView extends FrameLayout implements PlayerThemeFace {

    static final int ROOT = 0xFFF1F0ED;
    static final int INK = 0xFF252522;
    static final int MUTED = 0xFF86857E;

    private final float density;
    private PlayerThemeFace.Callback callback;

    private TextView titleView;
    private TextView artistView;
    private ShelfView shelfView;
    private LyricEngineView lyricView;
    private ShelfSeekBarView seekBar;
    private ImageView modeIcon;
    private ImageView playIcon;
    private ImageView pauseIcon;
    private View controlsRow;

    private boolean playing;
    private boolean favorite;
    private int mode = MusicQueue.MODE_SEQUENTIAL;
    private int h0 = INK;

    public ShelfThemeView(Context context) {
        this(context, null);
    }

    public ShelfThemeView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(ROOT);
        build(context);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private void build(Context c) {
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), 0, dp(16), 0);
        column.addView(header, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(64)));
        FrameLayout collapseBtn = keyBox(c, 48, R.drawable.ic_gz_collapse, 24, () -> {
            if (c instanceof Activity) ((Activity) c).finish();
        });
        header.addView(collapseBtn);
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titlesLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        header.addView(titles, titlesLp);
        titleView = GzViews.text(c, "", 15, 500, INK);
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(titleView, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        artistView = GzViews.text(c, "", 11, 400, MUTED);
        artistView.setGravity(Gravity.CENTER);
        artistView.setSingleLine(true);
        artistView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(artistView, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        FrameLayout dotsBtn = keyBox(c, 48, R.drawable.ic_gz_dots, 24, () -> {
            if (callback != null) callback.onOpenTools();
        });
        header.addView(dotsBtn);

        shelfView = new ShelfView(c);
        column.addView(shelfView, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        shelfView.setListener(new ShelfView.Listener() {
            @Override
            public void onPrevSlot() {
                if (callback != null) callback.onPrev();
            }

            @Override
            public void onNextSlot() {
                if (callback != null) callback.onNext();
            }

            @Override
            public void onDiscTap() {
                if (callback != null) callback.onFavoriteClick();
            }
        });

        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable infoBg = new GradientDrawable();
        infoBg.setColor(0xFFFFFFFF);
        infoBg.setCornerRadii(new float[]{0, 0, 0, 0, dp(24), dp(24), dp(24), dp(24)});
        info.setBackground(infoBg);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        infoLp.topMargin = dp(10);
        column.addView(info, infoLp);

        lyricView = new LyricEngineView(c);
        LinearLayout.LayoutParams lyricLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        lyricLp.topMargin = dp(12);
        lyricLp.bottomMargin = dp(12);
        info.addView(lyricView, lyricLp);
        lyricView.setListener((index, timeMs) -> {
            if (callback != null) callback.onSeekMs(timeMs);
        });
        configureLyric();

        seekBar = new ShelfSeekBarView(c);
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(72));
        seekLp.leftMargin = dp(28);
        seekLp.rightMargin = dp(28);
        info.addView(seekBar, seekLp);
        seekBar.setListener(positionMs -> {
            if (callback != null) callback.onSeekMs(positionMs);
        });

        LinearLayout controls = new LinearLayout(c);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(20), 0, dp(20), 0);
        info.addView(controls, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(76)));
        controlsRow = controls;
        FrameLayout modeBox = new FrameLayout(c);
        modeBox.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        modeIcon = GzViews.icon(c, R.drawable.ic_gz_mode_seq, 24, 1f);
        modeIcon.setColorFilter(INK);
        centerIn(modeBox, modeIcon);
        modeBox.setOnClickListener(v -> {
            if (callback != null) callback.onModeClick();
        });
        GzViews.pressScale(modeBox, 0.9f);
        controls.addView(modeBox);
        spacer(controls);
        controls.addView(ctrlKey(c, R.drawable.ic_gz_prev, 32, () -> {
            if (callback != null) callback.onPrev();
        }));
        spacer(controls);
        FrameLayout playBox = new FrameLayout(c);
        playBox.setLayoutParams(new LinearLayout.LayoutParams(dp(68), dp(68)));
        playIcon = GzViews.icon(c, R.drawable.ic_gz_play, 52, 1f);
        playIcon.setColorFilter(INK);
        pauseIcon = GzViews.icon(c, R.drawable.ic_gz_pause, 52, 1f);
        pauseIcon.setColorFilter(INK);
        centerIn(playBox, playIcon);
        centerIn(playBox, pauseIcon);
        playBox.setOnClickListener(v -> {
            if (callback != null) callback.onTogglePlay();
        });
        GzViews.pressScale(playBox, 0.9f);
        controls.addView(playBox);
        spacer(controls);
        controls.addView(ctrlKey(c, R.drawable.ic_gz_next, 32, () -> {
            if (callback != null) callback.onNext();
        }));
        spacer(controls);
        controls.addView(ctrlKey(c, R.drawable.ic_gz_queue, 24, () -> {
            if (callback != null) callback.onQueue();
        }));
    }

    private void spacer(LinearLayout parent) {
        parent.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1f));
    }

    private FrameLayout keyBox(Context c, float boxDp, int res, float iconDp, Runnable action) {
        FrameLayout box = new FrameLayout(c);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(boxDp), dp(boxDp)));
        ImageView iv = GzViews.icon(c, res, iconDp, 0.9f);
        iv.setColorFilter(INK);
        centerIn(box, iv);
        box.setOnClickListener(v -> action.run());
        GzViews.pressScale(box, 0.9f);
        return box;
    }

    private FrameLayout ctrlKey(Context c, int res, float iconDp, Runnable action) {
        FrameLayout box = new FrameLayout(c);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        ImageView iv = GzViews.icon(c, res, iconDp, 1f);
        iv.setColorFilter(INK);
        centerIn(box, iv);
        box.setOnClickListener(v -> action.run());
        GzViews.pressScale(box, 0.9f);
        return box;
    }

    private void centerIn(FrameLayout parent, View child) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        child.setLayoutParams(lp);
        parent.addView(child);
    }

    private void configureLyric() {
        LyricEngineView.Palette p = new LyricEngineView.Palette();
        p.sung = h0;
        p.activeUnsung = withAlpha(h0, 0.48f);
        p.inactive = MUTED;
        p.translation = withAlpha(MUTED, 0.72f);
        lyricView.setPalette(p);
        lyricView.configure(MusicPlayerSettings.getCoverLyricFontSize(), 20f, 24f, true,
                MusicPlayerSettings.isAdvancedFx(), MusicStore.isShowTranslation(),
                MusicStore.isShowRomanization(), MusicStore.getLyricPrimary(),
                MusicStore.getLyricAlign(), MusicPlayerSettings.blurCoefficient());
    }

    private int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(255 * alpha), Color.red(color), Color.green(color), Color.blue(color));
    }

    /** h0 定谳（jadx RecordShelfPlayerKt:266）：封面 tone 色向 ShelfInk lerp 0.65。 */
    private int computeH0(int coverColor) {
        return CdDiscView.lerp(coverColor, INK, 0.65f);
    }

    @Override
    public void setCallback(PlayerThemeFace.Callback callback) {
        this.callback = callback;
    }

    @Override
    public void setTrack(String title, String artist, String album) {
        titleView.setText(title == null ? "" : title);
        artistView.setText(artist == null ? "" : artist);
    }

    @Override
    public void setCover(String url, String title, boolean animate, int dir) {
        shelfView.setCover(url);
        CoverColors.load(getContext(), url, (single, extracted) -> {
            h0 = single != null ? computeH0(single) : INK;
            configureLyric();
        });
        Integer cached = CoverColors.single(url);
        if (cached != null) {
            h0 = computeH0(cached);
            configureLyric();
        }
    }

    @Override
    public void setPlaying(boolean playing) {
        this.playing = playing;
        playIcon.setVisibility(playing ? INVISIBLE : VISIBLE);
        pauseIcon.setVisibility(playing ? VISIBLE : INVISIBLE);
        shelfView.setPlaying(playing);
    }

    @Override
    public void setMode(int mode) {
        this.mode = mode;
        int res = mode == MusicQueue.MODE_SHUFFLE ? R.drawable.ic_gz_shuffle
                : mode == MusicQueue.MODE_REPEAT_ALL || mode == MusicQueue.MODE_REPEAT_ONE
                ? R.drawable.ic_gz_loop : R.drawable.ic_gz_mode_seq;
        modeIcon.setImageResource(res);
    }

    @Override
    public void setLyrics(List<RichLyrics.Line> lines, boolean loading) {
        lyricView.setLines(lines);
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
        shelfView.setHints(prevTitle, nextTitle);
    }

    @Override
    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    @Override
    public boolean isFavorite() {
        return favorite;
    }

    @Override
    public void setProgress(long positionMs, long durationMs) {
        seekBar.setProgress(positionMs, durationMs);
    }

    @Override
    public void advanceLyric(long positionMs) {
        lyricView.setPosition(positionMs + MusicStore.getLyricOffsetMs());
    }

    @Override
    public void setBottomStackExpanded(boolean expanded) {
        seekBar.setVisibility(expanded ? VISIBLE : INVISIBLE);
        controlsRow.setVisibility(expanded ? VISIBLE : INVISIBLE);
    }
}
