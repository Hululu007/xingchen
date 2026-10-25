package com.fongmi.android.tv.ui.music;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.palette.graphics.Palette;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.List;

public class CdThemeView extends FrameLayout implements PlayerThemeFace {

    private static class CdPalette {
        int base;
        int mist;
        int ink;
        int muted;
        int accent;
        float solidAlpha;
        float bgMix;
        boolean dark;
        float accentMaxLum;
    }

    private final LinearGradientView bgView;
    private final CdDiscView discView;
    private final TextView titleView;
    private final TextView artistView;
    private final GzLyricView lyricView;
    private final LinearLayout summaryBox;
    private final TextView summaryMain;
    private final TextView summarySub;
    private final TextView infoText;
    private final CdSeekView seekView;
    private final TextView elapsedView;
    private final TextView totalView;
    private final ImageView playPauseView;
    private final ImageView modeView;
    private final TextView modeBadge;
    private final ImageView infoIcon;
    private final ImageView favoriteIcon;
    private Callback callback;
    private CdPalette palette = new CdPalette();
    private int surfaceOverride = -1;
    private boolean favorite;
    private boolean playing;
    private boolean lyricExpanded;
    private boolean infoMode;
    private int playMode;
    private List<RichLyrics.Line> lyricLines;
    private long lastPosition;
    private long lastDuration;
    private int swapDirection = 1;

    public CdThemeView(Context context) {
        super(context);
        initPalette(false, null);
        bgView = new LinearGradientView(context);
        addView(bgView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        bgView.setPalette(palette, null);

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dpI(16), 0, dpI(16), 0);
        addView(page, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(topRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(64)));
        ImageView collapseKey = topKey(R.drawable.ic_gz_collapse);
        collapseKey.setOnClickListener(v -> {
            if (getContext() instanceof Activity) ((Activity) getContext()).finish();
        });
        topRow.addView(collapseKey);
        View flex = new View(context);
        topRow.addView(flex, new LinearLayout.LayoutParams(0, 1, 1f));
        ImageView moreKey = topKey(R.drawable.ic_gz_more);
        moreKey.setOnClickListener(v -> {
            if (callback != null) callback.onOpenTools();
        });
        topRow.addView(moreKey);

        FrameLayout stage = new FrameLayout(context);
        page.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        discView = new CdDiscView(context);
        stage.addView(discView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        lyricView = new GzLyricView(context);
        lyricView.setMode(GzLyricView.MODE_EMBED);
        lyricView.applySpec(21f, 28f / 21f, 20f, 12f);
        lyricView.setAlignOverride(1);
        lyricView.setVisibility(GONE);
        stage.addView(lyricView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        lyricView.setOnLineSeek(t -> {
            if (callback != null) callback.onSeekMs(t);
        });
        infoText = new TextView(context);
        infoText.setTextSize(14f);
        infoText.setGravity(Gravity.CENTER);
        infoText.setVisibility(GONE);
        stage.addView(infoText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        discView.setOnClickListener(v -> setLyricExpanded(true));

        titleView = new TextView(context);
        titleView.setTextSize(15f);
        titleView.setTypeface(PlayerViews.weight(600));
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        titleView.setMarqueeRepeatLimit(-1);
        titleView.setSelected(true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(21));
        page.addView(titleView, titleLp);
        artistView = new TextView(context);
        artistView.setTextSize(11f);
        artistView.setGravity(Gravity.CENTER);
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(15));
        artistLp.topMargin = dpI(4);
        page.addView(artistView, artistLp);

        summaryBox = new LinearLayout(context);
        summaryBox.setOrientation(LinearLayout.VERTICAL);
        summaryBox.setGravity(Gravity.CENTER);
        summaryBox.setPadding(dpI(24), 0, dpI(24), 0);
        page.addView(summaryBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(78)));
        summaryMain = new TextView(context);
        summaryMain.setTextSize(16f);
        summaryMain.setTypeface(PlayerViews.weight(600));
        summaryMain.setGravity(Gravity.CENTER);
        summaryMain.setSingleLine(true);
        summaryMain.setEllipsize(TextUtils.TruncateAt.END);
        summaryBox.addView(summaryMain, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        summarySub = new TextView(context);
        summarySub.setTextSize(12f);
        summarySub.setGravity(Gravity.CENTER);
        summarySub.setSingleLine(true);
        summarySub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dpI(4);
        summaryBox.addView(summarySub, subLp);
        summaryBox.setOnClickListener(v -> setLyricExpanded(true));

        LinearLayout iconRow = new LinearLayout(context);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setPadding(dpI(2), 0, dpI(2), 0);
        page.addView(iconRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(52)));
        infoIcon = rowIcon(R.drawable.ic_gz_info);
        infoIcon.setOnClickListener(v -> setInfoMode(!infoMode));
        iconRow.addView(iconCell(infoIcon));
        ImageView qualityIcon = rowIcon(R.drawable.ic_gz_quality);
        qualityIcon.setOnClickListener(v -> {
            if (callback != null) callback.onQualityClick();
        });
        iconRow.addView(iconCell(qualityIcon));
        ImageView lyricIcon = rowIcon(R.drawable.ic_gz_lyrics);
        lyricIcon.setOnClickListener(v -> {
            if (callback != null) callback.onOpenLyricPage();
        });
        iconRow.addView(iconCell(lyricIcon));
        favoriteIcon = rowIcon(R.drawable.ic_gz_favorite_border);
        favoriteIcon.setOnClickListener(v -> {
            if (callback != null) callback.onFavoriteClick();
        });
        iconRow.addView(iconCell(favoriteIcon));
        ImageView surfaceIcon = rowIcon(R.drawable.ic_gz_theme);
        surfaceIcon.setOnClickListener(v -> cycleSurface());
        iconRow.addView(iconCell(surfaceIcon));

        LinearLayout seekWrap = new LinearLayout(context);
        seekWrap.setOrientation(LinearLayout.VERTICAL);
        seekWrap.setPadding(dpI(8), 0, dpI(8), 0);
        page.addView(seekWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        seekView = new CdSeekView(context);
        seekWrap.addView(seekView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(34)));
        seekView.setOnSeek(pos -> {
            if (callback != null) callback.onSeekMs(pos);
        });
        LinearLayout timeRow = new LinearLayout(context);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        seekWrap.addView(timeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        elapsedView = timeText();
        timeRow.addView(elapsedView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        totalView = timeText();
        totalView.setGravity(Gravity.END);
        timeRow.addView(totalView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout transport = new LinearLayout(context);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        transport.setPadding(dpI(4), 0, dpI(4), 0);
        page.addView(transport, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(80)));
        FrameLayout modeWrap = new FrameLayout(context);
        modeWrap.setLayoutParams(new LinearLayout.LayoutParams(dpI(44), dpI(44)));
        modeView = new ImageView(context);
        modeView.setImageResource(R.drawable.ic_gz_repeat);
        modeView.setScaleType(ImageView.ScaleType.CENTER);
        modeWrap.addView(modeView, new FrameLayout.LayoutParams(dpI(44), dpI(44)));
        modeBadge = new TextView(context);
        modeBadge.setText("1");
        modeBadge.setTextSize(9f);
        modeBadge.setTypeface(PlayerViews.weight(800));
        modeBadge.setVisibility(GONE);
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.gravity = Gravity.CENTER;
        modeWrap.addView(modeBadge, badgeLp);
        transport.addView(modeWrap);
        modeWrap.setOnClickListener(v -> {
            if (callback != null) callback.onModeClick();
        });
        PlayerViews.pressScale(modeWrap, 0.9f);
        transport.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        ImageView prevBtn = transportKey(R.drawable.ic_gz_prev, 32);
        prevBtn.setOnClickListener(v -> {
            swapDirection = -1;
            if (callback != null) callback.onPrev();
        });
        transport.addView(prevBtn);
        transport.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        playPauseView = new ImageView(context);
        playPauseView.setImageResource(R.drawable.ic_gz_pause);
        playPauseView.setScaleType(ImageView.ScaleType.CENTER);
        playPauseView.setLayoutParams(new LinearLayout.LayoutParams(dpI(68), dpI(68)));
        playPauseView.setOnClickListener(v -> {
            if (callback != null) callback.onTogglePlay();
        });
        PlayerViews.pressScale(playPauseView, 0.9f);
        transport.addView(playPauseView);
        transport.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        ImageView nextBtn = transportKey(R.drawable.ic_gz_next, 32);
        nextBtn.setOnClickListener(v -> {
            swapDirection = 1;
            if (callback != null) callback.onNext();
        });
        transport.addView(nextBtn);
        transport.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        ImageView queueBtn = transportKey(R.drawable.ic_gz_queue, 23);
        queueBtn.setOnClickListener(v -> {
            if (callback != null) callback.onQueue();
        });
        transport.addView(queueBtn);

        applyPaletteColors();
    }

    private ImageView topKey(int res) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dpI(44), dpI(44)));
        iv.setPadding(dpI(10), dpI(10), dpI(10), dpI(10));
        PlayerViews.pressScale(iv, 0.9f);
        return iv;
    }

    private ImageView rowIcon(int res) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setLayoutParams(new FrameLayout.LayoutParams(dpI(26), dpI(26)));
        PlayerViews.pressScale(iv, 0.9f);
        return iv;
    }

    private FrameLayout iconCell(ImageView icon) {
        FrameLayout cell = new FrameLayout(getContext());
        cell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dpI(26), dpI(26));
        lp.gravity = Gravity.CENTER;
        cell.addView(icon, lp);
        cell.setOnClickListener(v -> icon.performClick());
        return cell;
    }

    private ImageView transportKey(int res, int iconDp) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dpI(44), dpI(44)));
        int pad = (dpI(44) - dpI(iconDp)) / 2;
        iv.setPadding(pad, pad, pad, pad);
        PlayerViews.pressScale(iv, 0.9f);
        return iv;
    }

    private TextView timeText() {
        TextView tv = new TextView(getContext());
        tv.setTextSize(9f);
        tv.setText("0:00");
        return tv;
    }

    private boolean isDarkSurface() {
        String theme = MusicPlayerSettings.getTheme();
        if (surfaceOverride >= 0) return surfaceOverride == 1;
        if (MusicPlayerSettings.THEME_LUMINOUS_CD_DARK.equals(theme)) return true;
        if (MusicPlayerSettings.THEME_LUMINOUS_CD.equals(theme)) return false;
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    private void cycleSurface() {
        boolean dark = isDarkSurface();
        surfaceOverride = dark ? 0 : 1;
        initPalette(!dark, null);
        refreshCoverPalette(lastCoverUrl);
        applyPaletteColors();
        android.widget.Toast.makeText(getContext(),
                dark ? "流光浅色" : "流光深色", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void initPalette(boolean dark, Integer coverColor) {
        palette = new CdPalette();
        palette.dark = dark;
        if (dark) {
            palette.base = 0xFF14161C;
            palette.mist = 0xFF14161C;
            palette.ink = 0xFFF2F2F7;
            palette.muted = 0xFF8E96A3;
            palette.solidAlpha = 0.72f;
            palette.bgMix = 1.0f;
            palette.accentMaxLum = 0.45f;
        } else {
            palette.base = 0xFFF4F2EE;
            palette.mist = 0xFFE9EFF5;
            palette.ink = 0xFF282D36;
            palette.muted = 0xFF747D89;
            palette.solidAlpha = 1.0f;
            palette.bgMix = 0f;
            palette.accentMaxLum = 1.0f;
        }
        palette.accent = palette.ink;
        if (coverColor != null) {
            int mixed = mixColor(coverColor, palette.ink, 0.52f);
            double lum = androidx.core.graphics.ColorUtils.calculateLuminance(mixed);
            if (lum > palette.accentMaxLum) {
                double baseLum = androidx.core.graphics.ColorUtils.calculateLuminance(palette.base);
                double denom = lum - baseLum;
                float f = denom == 0 ? 0f : (float) Math.max(0, Math.min(1,
                        (lum - palette.accentMaxLum) / denom));
                mixed = mixColor(mixed, palette.base, f);
            }
            palette.accent = mixed;
        }
    }

    private String lastCoverUrl;

    private void refreshCoverPalette(String url) {
        if (url == null || url.isEmpty()) return;
        Glide.with(getContext()).asBitmap().load(ImgUtil.getUrl(url)).override(128, 128)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource,
                                                @Nullable Transition<? super Bitmap> transition) {
                        if (!url.equals(lastCoverUrl)) return;
                        Palette.from(resource).maximumColorCount(24).generate(p -> {
                            if (p == null || !url.equals(lastCoverUrl)) return;
                            int cover = CoverAmbientView.extractAmbientColor(p);
                            initPalette(isDarkSurface(), cover);
                            bgView.setPalette(palette, cover);
                            applyPaletteColors();
                        });
                    }

                    @Override
                    public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                    }
                });
    }

    private void applyPaletteColors() {
        bgView.setPalette(palette, null);
        bgView.invalidate();
        titleView.setTextColor(withA(palette.ink, palette.solidAlpha));
        artistView.setTextColor(palette.muted);
        summaryMain.setTextColor(palette.ink);
        summarySub.setTextColor(palette.muted);
        elapsedView.setTextColor(withA(palette.muted, 0.74f));
        totalView.setTextColor(withA(palette.muted, 0.74f));
        infoText.setTextColor(palette.ink);
        modeBadge.setTextColor(palette.ink);
        seekView.setColors(palette.ink, palette.accent);
        lyricView.setPalette(new GzLyricLineView.Palette(
                palette.accent, withA(palette.accent, 0.42f), withA(palette.accent, 0.38f),
                withA(palette.accent, 0.42f)));
        tint(modeView, palette.muted);
        tint(infoIcon, infoMode ? palette.accent : palette.muted);
        tint(favoriteIcon, favorite ? palette.accent : palette.muted);
        favoriteIcon.setImageResource(favorite
                ? R.drawable.ic_gz_favorite : R.drawable.ic_gz_favorite_border);
    }

    private void tint(ImageView iv, int color) {
        if (iv == null || iv.getDrawable() == null) return;
        iv.getDrawable().mutate().setTint(color);
    }

    private int withA(int color, float alpha) {
        return Color.argb(Math.round(255f * alpha),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private int mixColor(int from, int to, float f) {
        return Color.argb(
                Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f));
    }

    private void setLyricExpanded(boolean value) {
        lyricExpanded = value;
        lyricView.setVisibility(value ? VISIBLE : GONE);
        discView.setVisibility(value ? GONE : VISIBLE);
        summaryBox.setVisibility(value ? GONE : VISIBLE);
        if (value) {
            summaryBox.getLayoutParams().height = 0;
        }
    }

    private void setInfoMode(boolean value) {
        infoMode = value;
        infoText.setVisibility(value ? VISIBLE : GONE);
        if (value) {
            lyricView.setVisibility(GONE);
            discView.setVisibility(GONE);
        } else {
            discView.setVisibility(lyricExpanded ? GONE : VISIBLE);
            lyricView.setVisibility(lyricExpanded ? VISIBLE : GONE);
        }
        tint(infoIcon, value ? palette.accent : palette.muted);
    }

    private int dpI(float v) {
        return PlayerViews.dpI(this, v);
    }

    @Override
    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    @Override
    public void setTrack(String title, String artist, String album) {
        titleView.setText(TextUtils.isEmpty(title) ? "未在播放" : title);
        artistView.setText(TextUtils.isEmpty(artist) ? "—" : artist);
    }

    @Override
    public void setCover(String coverUrl, String title, boolean animate, int direction) {
        lastCoverUrl = coverUrl;
        discView.setCover(coverUrl, direction <= 0 ? -1 : 1);
        refreshCoverPalette(coverUrl);
    }

    @Override
    public void setSwipeHints(String prevTitle, String nextTitle) {
    }

    @Override
    public void setPlaying(boolean playing) {
        this.playing = playing;
        playPauseView.setImageResource(playing ? R.drawable.ic_gz_pause : R.drawable.ic_gz_play);
        int iconDp = playing ? 54 : 58;
        int pad = (dpI(68) - dpI(iconDp)) / 2;
        playPauseView.setPadding(pad, pad, pad, pad);
        tint(playPauseView, palette.ink);
        discView.setPlaying(playing);
        lyricView.setPlayback(lastPosition, playing);
    }

    @Override
    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
        applyPaletteColors();
    }

    @Override
    public boolean isFavorite() {
        return favorite;
    }

    @Override
    public void setProgress(long positionMs, long durationMs) {
        lastPosition = positionMs;
        lastDuration = durationMs;
        seekView.setProgress(positionMs, durationMs);
        elapsedView.setText(PlayerScrubberView.formatTime(positionMs));
        totalView.setText(PlayerScrubberView.formatTime(durationMs));
    }

    @Override
    public void setLyrics(List<RichLyrics.Line> lines, boolean loading) {
        lyricLines = lines;
        lyricView.setLyrics(lines, loading);
        updateSummary();
    }

    private void updateSummary() {
        if (lyricLines == null || lyricLines.isEmpty()) {
            summaryMain.setText("暂无歌词");
            summarySub.setText("");
            return;
        }
        int idx = 0;
        for (int i = 0; i < lyricLines.size(); i++) {
            RichLyrics.Line l = lyricLines.get(i);
            if (l.main != null && l.main.getTimeMs() <= lastPosition) idx = i;
            else break;
        }
        RichLyrics.Line cur = lyricLines.get(idx);
        summaryMain.setText(cur.main == null ? "" : cur.main.getText());
        summarySub.setText(cur.translation == null ? "" : cur.translation);
    }

    @Override
    public void advanceLyric(long positionMs) {
        lastPosition = positionMs;
        lyricView.setPlayback(positionMs, playing);
        if (!lyricExpanded) updateSummary();
    }

    @Override
    public void setQualityText(String text) {
    }

    @Override
    public void setLyricState(String chipLabel, boolean wordByWord) {
    }

    @Override
    public void setInfoDetail(String detail) {
        infoText.setText(detail == null ? "" : detail);
    }

    @Override
    public void setMode(int mode) {
        playMode = mode;
        modeBadge.setVisibility(mode == MusicQueue.MODE_REPEAT_ONE ? VISIBLE : GONE);
        modeView.setImageResource(mode == MusicQueue.MODE_SHUFFLE
                ? R.drawable.ic_gz_shuffle : R.drawable.ic_gz_repeat);
        tint(modeView, palette.muted);
    }

    @Override
    public void setVolume(float fraction) {
    }

    private static class LinearGradientView extends View {
        private CdPalette palette;
        private Integer coverColor;
        private final Paint paint = new Paint();

        LinearGradientView(Context context) {
            super(context);
        }

        void setPalette(CdPalette palette, Integer coverColor) {
            this.palette = palette;
            if (coverColor != null) this.coverColor = coverColor;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (palette == null) return;
            int base = palette.base;
            int cover = coverColor == null ? base : coverColor;
            int fresh = mix(base, cover, 0.22f);
            int middle = mix(base, mix(base, cover, 0.12f), palette.bgMix);
            int bottom = mix(palette.mist, mix(base, cover, 0.08f), palette.bgMix);
            int top = mix(base, fresh, 0.92f);
            int upper = mix(base, fresh, palette.dark ? 0.68f : 0.48f);
            LinearGradient gradient = new LinearGradient(0, 0, 0, getHeight(),
                    new int[]{top, upper, middle, bottom},
                    palette.dark ? new float[]{0f, 0.42f, 0.68f, 1f}
                            : new float[]{0f, 0.34f, 0.68f, 1f}, Shader.TileMode.CLAMP);
            paint.setShader(gradient);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
        }

        private int mix(int from, int to, float f) {
            return Color.argb(
                    Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f),
                    Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f),
                    Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f),
                    Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f));
        }
    }

    private static class CdSeekView extends View {
        interface OnSeek {
            void onSeek(long positionMs);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long positionMs;
        private long durationMs;
        private int inkColor = 0xFF282D36;
        private int accentColor = 0xFF282D36;
        private boolean dragging;
        private float dragFraction;
        private OnSeek listener;

        CdSeekView(Context context) {
            super(context);
        }

        void setOnSeek(OnSeek listener) {
            this.listener = listener;
        }

        void setColors(int ink, int accent) {
            inkColor = ink;
            accentColor = accent;
            invalidate();
        }

        void setProgress(long position, long duration) {
            if (!dragging) {
                positionMs = position;
                durationMs = duration;
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float cy = getHeight() / 2f;
            float lineH = 2f * density;
            float fraction = durationMs > 0
                    ? Math.max(0f, Math.min(1f, dragging ? dragFraction : positionMs / (float) durationMs))
                    : 0f;
            paint.setColor(PlayerViews.withAlpha(inkColor, 0.10f));
            canvas.drawRect(0, cy - lineH / 2f, getWidth(), cy + lineH / 2f, paint);
            paint.setColor(accentColor);
            canvas.drawRect(0, cy - lineH / 2f, getWidth() * fraction, cy + lineH / 2f, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (durationMs <= 0) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    dragging = true;
                    dragFraction = frac(event.getX());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    dragFraction = frac(event.getX());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    long target = Math.round(dragFraction * durationMs);
                    positionMs = target;
                    invalidate();
                    if (event.getActionMasked() == MotionEvent.ACTION_UP && listener != null) {
                        listener.onSeek(target);
                    }
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private float frac(float x) {
            return getWidth() <= 0 ? 0 : Math.max(0f, Math.min(1f, x / (float) getWidth()));
        }
    }
}
