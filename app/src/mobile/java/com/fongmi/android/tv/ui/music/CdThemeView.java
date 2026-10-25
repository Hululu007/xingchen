package com.fongmi.android.tv.ui.music;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.player.lyrics.LyricsLine;

import java.util.List;

/**
 * 流光 CD 主题（照瓜子 LuminousCdPlayer＋CdPalette＋a21 复刻）：页 padH16；
 * 顶行 64（左收起/右三点 44）；盘 f=min(约束宽,约束高,400dp) 旋转 16°/s；
 * 标题 15sp/600 跑马灯、歌手 11sp/400、无爱心；歌词区（收起摘要 16/12、高 78；
 * 展开 21sp 居中、行高 28、行距 20）；五图标行 52（信息/音质/歌词版本/收藏/表面切换）；
 * CdSeekBar；运输行 80（模式/上首/播停 68/下首/队列）。表面 LIGHT=Pearl、DARK=Space、
 * AUTO 跟系统深色；切换循环＋Toast 档名。
 */
public class CdThemeView extends FrameLayout implements PlayerThemeFace {

    private static final class Palette {
        int base, mist, ink, muted;
        float toneMix, solidAlpha, accentMaxLum;
        boolean dark;
    }

    private final float density;
    private PlayerThemeFace.Callback callback;

    private CdDiscView discView;
    private TextView titleView;
    private TextView artistView;
    private LinearLayout lyricSummary;
    private TextView summaryMain;
    private TextView summarySub;
    private LyricEngineView lyricFull;
    private CdSeekBarView seekBar;
    private ImageView modeIcon;
    private ImageView heartIcon;
    private ImageView playIcon;
    private ImageView pauseIcon;
    private ImageView surfaceIcon;
    private LinearLayout rootColumn;

    private boolean playing;
    private boolean favorite;
    private boolean lyricExpanded;
    private int coverColor = 0xFF888888;
    private Palette palette;
    private int accent = 0xFF282D36;
    private List<RichLyrics.Line> lines;
    private int mode = MusicQueue.MODE_SEQUENTIAL;

    public CdThemeView(Context context) {
        this(context, null);
    }

    public CdThemeView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        palette = paletteFor(surfaceDark());
        build(context);
        applyPalette();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private boolean surfaceDark() {
        String theme = MusicPlayerSettings.getTheme();
        if (MusicPlayerSettings.THEME_LUMINOUS_CD_DARK.equals(theme)) return true;
        if (MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO.equals(theme)) {
            int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return night == Configuration.UI_MODE_NIGHT_YES;
        }
        return false;
    }

    private Palette paletteFor(boolean dark) {
        Palette p = new Palette();
        if (dark) {
            p.base = 0xFF14161C;
            p.mist = 0xFF14161C;
            p.ink = 0xFFF2F2F7;
            p.muted = 0xFF8E96A3;
            p.toneMix = 1f;
            p.solidAlpha = 0.72f;
            p.accentMaxLum = 0.45f;
            p.dark = true;
        } else {
            p.base = 0xFFF4F2EE;
            p.mist = 0xFFE9EFF5;
            p.ink = 0xFF282D36;
            p.muted = 0xFF747D89;
            p.toneMix = 0f;
            p.solidAlpha = 1f;
            p.accentMaxLum = 1f;
            p.dark = false;
        }
        return p;
    }

    private void build(Context c) {
        rootColumn = new LinearLayout(c);
        rootColumn.setOrientation(LinearLayout.VERTICAL);
        rootColumn.setPadding(dp(16), 0, dp(16), 0);
        addView(rootColumn, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout topRow = new LinearLayout(c);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        rootColumn.addView(topRow, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(64)));
        FrameLayout collapseBtn = topButton(c, R.drawable.ic_gz_collapse, 24, () -> {
            if (c instanceof Activity) ((Activity) c).finish();
        });
        topRow.addView(collapseBtn);
        topRow.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        FrameLayout dotsBtn = topButton(c, R.drawable.ic_gz_dots, 23, () -> {
            if (callback != null) callback.onOpenTools();
        });
        topRow.addView(dotsBtn);

        FrameLayout discBox = new FrameLayout(c);
        LinearLayout.LayoutParams discLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        rootColumn.addView(discBox, discLp);
        discView = new CdDiscView(c);
        discBox.addView(discView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        titleView = GzViews.text(c, "", 15, 600, palette.ink);
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        titleView.setMarqueeRepeatLimit(-1);
        titleView.setSelected(true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(24));
        titleLp.topMargin = dp(6);
        rootColumn.addView(titleView, titleLp);
        artistView = GzViews.text(c, "", 11, 400, palette.muted);
        artistView.setGravity(Gravity.CENTER);
        artistView.setSingleLine(true);
        artistView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        rootColumn.addView(artistView, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(16)));

        lyricSummary = new LinearLayout(c);
        lyricSummary.setOrientation(LinearLayout.VERTICAL);
        lyricSummary.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(78));
        rootColumn.addView(lyricSummary, sumLp);
        summaryMain = GzViews.text(c, "", 16, 600, palette.ink);
        summaryMain.setGravity(Gravity.CENTER);
        summaryMain.setSingleLine(true);
        summaryMain.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lyricSummary.addView(summaryMain, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        summarySub = GzViews.text(c, "", 12, 400, palette.muted);
        summarySub.setGravity(Gravity.CENTER);
        summarySub.setSingleLine(true);
        summarySub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        lyricSummary.addView(summarySub, subLp);
        lyricSummary.setOnClickListener(v -> setLyricExpanded(true));

        lyricFull = new LyricEngineView(c);
        lyricFull.setVisibility(GONE);
        rootColumn.addView(lyricFull, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(150)));
        lyricFull.setListener((index, timeMs) -> {
            if (callback != null) callback.onSeekMs(timeMs);
        });
        lyricFull.setOnClickListener(v -> {
        });
        lyricFull.setOnLongClickListener(v -> {
            setLyricExpanded(false);
            return true;
        });

        LinearLayout iconRow = new LinearLayout(c);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER);
        rootColumn.addView(iconRow, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52)));
        iconRow.addView(rowIcon(c, R.drawable.ic_gz_info, () -> {
            if (callback != null) callback.onOpenTools();
        }));
        iconRow.addView(rowIcon(c, R.drawable.ic_gz_quality, () -> {
            if (callback != null) callback.onQualityClick();
        }));
        iconRow.addView(rowIcon(c, R.drawable.ic_gz_lyric, () -> {
            if (callback != null) callback.onOpenTools();
        }));
        heartIcon = null;
        FrameLayout heartBox = rowIcon(c, R.drawable.ic_gz_heart, () -> {
            if (callback != null) callback.onFavoriteClick();
        });
        heartIcon = (ImageView) heartBox.getChildAt(0);
        iconRow.addView(heartBox);
        FrameLayout surfaceBox = rowIcon(c, R.drawable.ic_gz_surface, this::cycleSurface);
        surfaceIcon = (ImageView) surfaceBox.getChildAt(0);
        iconRow.addView(surfaceBox);

        seekBar = new CdSeekBarView(c);
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(34));
        seekLp.leftMargin = dp(8);
        seekLp.rightMargin = dp(8);
        rootColumn.addView(seekBar, seekLp);
        seekBar.setListener(positionMs -> {
            if (callback != null) callback.onSeekMs(positionMs);
        });

        LinearLayout transport = new LinearLayout(c);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER);
        rootColumn.addView(transport, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(80)));
        FrameLayout modeBox = transportButton(c, 44, () -> {
            if (callback != null) callback.onModeClick();
        });
        modeIcon = GzViews.icon(c, R.drawable.ic_gz_mode_seq, 23, 1f);
        centerIn(modeBox, modeIcon);
        transport.addView(modeBox);
        transport.addView(transportButton(c, 44, R.drawable.ic_gz_prev, 32, () -> {
            if (callback != null) callback.onPrev();
        }));
        FrameLayout playBox = new FrameLayout(c);
        playBox.setLayoutParams(new LinearLayout.LayoutParams(dp(68), dp(68)));
        playIcon = GzViews.icon(c, R.drawable.ic_gz_play, 54, 1f);
        pauseIcon = GzViews.icon(c, R.drawable.ic_gz_pause, 58, 1f);
        centerIn(playBox, playIcon);
        centerIn(playBox, pauseIcon);
        playBox.setOnClickListener(v -> {
            if (callback != null) callback.onTogglePlay();
        });
        GzViews.pressScale(playBox, 0.9f);
        transport.addView(playBox);
        transport.addView(transportButton(c, 44, R.drawable.ic_gz_next, 32, () -> {
            if (callback != null) callback.onNext();
        }));
        transport.addView(transportButton(c, 44, R.drawable.ic_gz_queue, 23, () -> {
            if (callback != null) callback.onQueue();
        }));
    }

    private void centerIn(FrameLayout parent, View child) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        child.setLayoutParams(lp);
        parent.addView(child);
    }

    private FrameLayout topButton(Context c, int res, float iconDp, Runnable action) {
        FrameLayout box = new FrameLayout(c);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        ImageView iv = GzViews.icon(c, res, iconDp, 1f);
        centerIn(box, iv);
        box.setOnClickListener(v -> action.run());
        GzViews.pressScale(box, 0.9f);
        return box;
    }

    private FrameLayout rowIcon(Context c, int res, Runnable action) {
        FrameLayout box = new FrameLayout(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(52));
        box.setLayoutParams(lp);
        ImageView iv = GzViews.icon(c, res, 26, 1f);
        centerIn(box, iv);
        box.setOnClickListener(v -> action.run());
        GzViews.pressScale(box, 0.9f);
        return box;
    }

    private FrameLayout transportButton(Context c, float boxDp, Runnable action) {
        FrameLayout box = new FrameLayout(c);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(boxDp), dp(boxDp)));
        box.setOnClickListener(v -> action.run());
        GzViews.pressScale(box, 0.9f);
        return box;
    }

    private FrameLayout transportButton(Context c, float boxDp, int res, float iconDp, Runnable action) {
        FrameLayout box = transportButton(c, boxDp, action);
        ImageView iv = GzViews.icon(c, res, iconDp, 1f);
        centerIn(box, iv);
        return box;
    }

    private void cycleSurface() {
        String theme = MusicPlayerSettings.getTheme();
        String next;
        String label;
        if (MusicPlayerSettings.THEME_LUMINOUS_CD.equals(theme)) {
            next = MusicPlayerSettings.THEME_LUMINOUS_CD_DARK;
            label = "深色";
        } else if (MusicPlayerSettings.THEME_LUMINOUS_CD_DARK.equals(theme)) {
            next = MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO;
            label = "自动";
        } else {
            next = MusicPlayerSettings.THEME_LUMINOUS_CD;
            label = "浅色";
        }
        MusicPlayerSettings.setTheme(next);
        palette = paletteFor(surfaceDark());
        applyPalette();
        Toast.makeText(getContext(), label, Toast.LENGTH_SHORT).show();
    }

    private void applyPalette() {
        int c = coverColor;
        int fresh = CdDiscView.lerp(palette.base, c, 0.22f);
        int top = CdDiscView.lerp(palette.base, fresh, 0.92f);
        int upper = CdDiscView.lerp(palette.base, fresh, palette.dark ? 0.68f : 0.48f);
        int middle = CdDiscView.lerp(palette.base, CdDiscView.lerp(palette.base, c, 0.12f), palette.toneMix);
        int bottom = CdDiscView.lerp(palette.mist, CdDiscView.lerp(palette.base, c, 0.08f), palette.toneMix);
        float[] stops = palette.dark
                ? new float[]{0f, 0.42f, 0.68f, 1f}
                : new float[]{0f, 0.34f, 0.68f, 1f};
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, upper, middle, bottom});
        try {
            java.lang.reflect.Method setStops = GradientDrawable.class.getMethod(
                    "setColors", int[].class, float[].class);
            setStops.invoke(bg, new int[]{top, upper, middle, bottom}, stops);
        } catch (Throwable ignored) {
        }
        setBackground(bg);
        accent = CdDiscView.lerp(c, palette.ink, 0.52f);
        float lum = CdDiscView.lightness(accent);
        if (lum > palette.accentMaxLum) {
            float lumBase = CdDiscView.lightness(palette.base);
            float t = lumBase == lum ? 0f : Math.max(0f, Math.min(1f, (lum - palette.accentMaxLum) / (lum - lumBase)));
            accent = CdDiscView.lerp(accent, palette.base, t);
        }
        titleView.setTextColor(palette.ink);
        artistView.setTextColor(palette.muted);
        summaryMain.setTextColor(accent);
        summarySub.setTextColor(withAlpha(palette.muted, 0.9f));
        discView.setMaterial(palette.dark ? CdDiscView.space() : CdDiscView.pearl());
        discView.setCover(coverUrlCache, coverColor);
        seekBar.inkColor = palette.ink;
        seekBar.accentColor = accent;
        seekBar.mutedColor = palette.muted;
        seekBar.invalidate();
        int iconTint = palette.ink;
        tintTree(rootColumn, iconTint);
        heartIcon.setColorFilter(favorite ? 0xFFFA243C : iconTint);
        configureFullLyric();
    }

    private String coverUrlCache;

    private void tintTree(View view, int color) {
        if (view instanceof ImageView && view != discView) {
            ImageView iv = (ImageView) view;
            if (iv != heartIcon && iv != surfaceIcon) iv.setColorFilter(color);
            if (iv == surfaceIcon) iv.setColorFilter(color);
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tintTree(group.getChildAt(i), color);
        }
    }

    private int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(255 * alpha), Color.red(color), Color.green(color), Color.blue(color));
    }

    private void configureFullLyric() {
        LyricEngineView.Palette p = new LyricEngineView.Palette();
        p.sung = accent;
        p.activeUnsung = withAlpha(accent, 0.42f);
        p.inactive = withAlpha(accent, 0.38f);
        p.translation = withAlpha(accent, 0.42f);
        lyricFull.setPalette(p);
        lyricFull.configure(21f, 20f, 24f, true, MusicPlayerSettings.isAdvancedFx(),
                MusicStore.isShowTranslation(), MusicStore.isShowRomanization(),
                MusicStore.getLyricPrimary(), 1, 0f);
        if (lines != null) lyricFull.setLines(lines);
    }

    private void setLyricExpanded(boolean target) {
        lyricExpanded = target;
        lyricSummary.setVisibility(target ? GONE : VISIBLE);
        lyricFull.setVisibility(target ? VISIBLE : GONE);
        if (target) configureFullLyric();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int w = getMeasuredWidth() - dp(32);
        int h = getMeasuredHeight() - dp(64 + 24 + 6 + 16 + 78 + 52 + 34 + 80);
        int disc = Math.min(Math.min(w, h), dp(400));
        if (discView != null && disc > 0) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) discView.getLayoutParams();
            if (lp.width != disc || lp.height != disc) {
                lp.width = disc;
                lp.height = disc;
                lp.gravity = Gravity.CENTER;
                discView.setLayoutParams(lp);
            }
        }
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
        coverUrlCache = url;
        discView.setCover(url, coverColor);
        CoverColors.load(getContext(), url, (single, extracted) -> {
            coverColor = single != null ? single : CoverColors.fallback();
            applyPalette();
        });
        if (CoverColors.single(url) != null) {
            coverColor = CoverColors.single(url);
            applyPalette();
        }
    }

    @Override
    public void setPlaying(boolean playing) {
        this.playing = playing;
        playIcon.setVisibility(playing ? INVISIBLE : VISIBLE);
        pauseIcon.setVisibility(playing ? VISIBLE : INVISIBLE);
        discView.setPlaying(playing);
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
    public void setLyrics(List<RichLyrics.Line> newLines, boolean loading) {
        lines = newLines;
        if (lyricExpanded) lyricFull.setLines(newLines);
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
        heartIcon.setColorFilter(favorite ? 0xFFFA243C : palette.ink);
        heartIcon.setAlpha(favorite ? 1f : 0.85f);
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
        long pos = positionMs + MusicStore.getLyricOffsetMs();
        if (lyricExpanded) {
            lyricFull.setPosition(pos);
            return;
        }
        if (lines == null) {
            summaryMain.setText("");
            summarySub.setText("");
            return;
        }
        String current = "";
        String next = "";
        for (int i = 0; i < lines.size(); i++) {
            LyricsLine main = lines.get(i).main;
            if (main == null) continue;
            if (main.getTimeMs() <= pos) {
                current = main.getText() == null ? "" : main.getText();
                if (i + 1 < lines.size() && lines.get(i + 1).main != null) {
                    next = lines.get(i + 1).main.getText() == null ? "" : lines.get(i + 1).main.getText();
                }
            } else break;
        }
        summaryMain.setText(current);
        summaryMain.setTextColor(accent);
        summarySub.setText(next);
        summarySub.setTextColor(withAlpha(accent, 0.42f));
    }

    @Override
    public void setBottomStackExpanded(boolean expanded) {
        seekBar.setVisibility(expanded ? VISIBLE : INVISIBLE);
    }
}
