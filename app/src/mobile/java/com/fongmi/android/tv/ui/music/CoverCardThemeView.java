package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
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
import com.fongmi.android.tv.player.lyrics.LyricsLine;

import java.util.List;

/**
 * 封面卡片主题（照瓜子 CoverImmersive 复刻）：纵列 42→歌名 20sp/800→4→歌手 14sp→22→
 * 权重区（padH24）里居中放卡：宽=min(可用宽×.98, 可用高/.65)、高=宽×.65、圆角 22，
 * 封面拉满＋黑罩四档(.18/.26/.40/.50)；默认态底部玻璃条 48dp（左收藏/中单行歌词 13sp/右播停），
 * 点卡展开四玻璃键（上一首/播放列表/歌曲信息/下一首 42dp）＋进度条。卡后是模糊封面垫卡。
 * 工具面板入口：瓜子封面卡本体无入口（定谳），按抠值记录的装配决定在歌名行右侧放
 * 三键（菜单/队列/播停）与经典同位；展开态四键照瓜子（歌曲信息键进工具面板）。
 * 此项标注为待真机对照、不算已对齐。
 */
public class CoverCardThemeView extends FrameLayout implements PlayerThemeFace {

    private final float density;
    private PlayerThemeFace.Callback callback;

    private TextView titleView;
    private TextView artistView;
    private ImageView titlePlayIcon;
    private ImageView titlePauseIcon;
    private FrameLayout card;
    private ImageView coverView;
    private ImageView backdropView;
    private LinearLayout glassBar;
    private LinearLayout expandedBox;
    private ImageView heartView;
    private TextView barLyricView;
    private ImageView barPlayIcon;
    private ImageView barPauseIcon;
    private PlayerScrubberView scrubber;

    private boolean playing;
    private boolean favorite;
    private boolean expanded;
    private List<RichLyrics.Line> lines;
    private String qualityText = "";
    private String lyricChipLabel = "";
    private float dragX;
    private float dragY;
    private boolean dragMoved;

    public CoverCardThemeView(Context context) {
        this(context, null);
    }

    public CoverCardThemeView(Context context, @Nullable AttributeSet attrs) {
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
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        column.addView(new View(c), new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(42)));

        FrameLayout titleBox = new FrameLayout(c);
        LinearLayout.LayoutParams titleBoxLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(30));
        titleBoxLp.leftMargin = dp(24);
        titleBoxLp.rightMargin = dp(24);
        column.addView(titleBox, titleBoxLp);
        titleView = GzViews.text(c, "", 20, 800, 0xF5FFFFFF);
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (android.os.Build.VERSION.SDK_INT >= 21) titleView.setLetterSpacing(-0.02f);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        titleLp.gravity = Gravity.CENTER;
        titleView.setLayoutParams(titleLp);
        titleBox.addView(titleView);
        LinearLayout titleKeys = new LinearLayout(c);
        titleKeys.setOrientation(LinearLayout.HORIZONTAL);
        titleKeys.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams keysLp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
        keysLp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        titleKeys.setLayoutParams(keysLp);
        titleBox.addView(titleKeys);
        titleKeys.addView(smallKey(c, R.drawable.ic_gz_menu, 21, () -> {
            if (callback != null) callback.onOpenTools();
        }));
        titleKeys.addView(smallKey(c, R.drawable.ic_gz_queue, 20, () -> {
            if (callback != null) callback.onQueue();
        }));
        FrameLayout titlePlay = new FrameLayout(c);
        titlePlay.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(30)));
        titlePlayIcon = GzViews.icon(c, R.drawable.ic_gz_play, 22, 1f);
        titlePauseIcon = GzViews.icon(c, R.drawable.ic_gz_pause, 19, 1f);
        centerIn(titlePlay, titlePlayIcon);
        centerIn(titlePlay, titlePauseIcon);
        titlePlay.setOnClickListener(v -> {
            if (callback != null) callback.onTogglePlay();
        });
        titleKeys.addView(titlePlay);

        column.addView(new View(c), new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(4)));

        artistView = GzViews.text(c, "", 14, 500, 0xA6FFFFFF);
        artistView.setGravity(Gravity.CENTER);
        artistView.setSingleLine(true);
        artistView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(22));
        artistLp.leftMargin = dp(24);
        artistLp.rightMargin = dp(24);
        column.addView(artistView, artistLp);

        column.addView(new View(c), new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(22)));

        FrameLayout weightZone = new FrameLayout(c);
        LinearLayout.LayoutParams wzLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        wzLp.leftMargin = dp(24);
        wzLp.rightMargin = dp(24);
        column.addView(weightZone, wzLp);

        backdropView = new ImageView(c);
        backdropView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        weightZone.addView(backdropView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        card = new FrameLayout(c);
        GradientDrawable cardBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF3A3A3C, 0xFF1C1C1E});
        cardBg.setCornerRadius(dp(22));
        card.setBackground(cardBg);
        card.setClipToOutline(true);
        weightZone.addView(card, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        coverView = new ImageView(c);
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(coverView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        View shade = new View(c);
        GradientDrawable shadeGd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x2E000000, 0x42000000, 0x66000000, 0x80000000});
        shadeGd.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        shade.setBackground(shadeGd);
        card.addView(shade, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        glassBar = buildGlassBar(c);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48));
        barLp.gravity = Gravity.BOTTOM;
        barLp.leftMargin = dp(12);
        barLp.rightMargin = dp(12);
        barLp.bottomMargin = dp(12);
        card.addView(glassBar, barLp);

        expandedBox = buildExpanded(c);
        FrameLayout.LayoutParams expLp = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        expLp.gravity = Gravity.BOTTOM;
        expLp.leftMargin = dp(10);
        expLp.rightMargin = dp(10);
        expLp.bottomMargin = dp(10);
        expandedBox.setVisibility(INVISIBLE);
        card.addView(expandedBox, expLp);

        card.setOnTouchListener(this::onCardTouch);

        column.addView(new View(c), new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(12)));
    }

    private LinearLayout buildGlassBar(Context c) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E1E22);
        bg.setCornerRadius(dp(22));
        bar.setBackground(bg);
        bar.setPadding(dp(16), 0, dp(16), 0);

        heartView = new ImageView(c);
        heartView.setImageResource(R.drawable.ic_gz_heart);
        LinearLayout.LayoutParams heartLp = new LinearLayout.LayoutParams(dp(40), dp(48));
        heartView.setPadding(dp(10), dp(14), dp(10), dp(14));
        heartView.setLayoutParams(heartLp);
        heartView.setAlpha(0.30f);
        heartView.setOnClickListener(v -> {
            if (callback != null) callback.onFavoriteClick();
        });
        bar.addView(heartView);

        barLyricView = GzViews.text(c, "", 13, 500, 0xEBFFFFFF);
        barLyricView.setGravity(Gravity.CENTER);
        barLyricView.setSingleLine(true);
        barLyricView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(barLyricView, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout playBox = new FrameLayout(c);
        playBox.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(48)));
        barPlayIcon = GzViews.icon(c, R.drawable.ic_gz_play, 24, 1f);
        barPauseIcon = GzViews.icon(c, R.drawable.ic_gz_pause, 24, 1f);
        centerIn(playBox, barPlayIcon);
        centerIn(playBox, barPauseIcon);
        playBox.setOnClickListener(v -> {
            if (callback != null) callback.onTogglePlay();
        });
        bar.addView(playBox);
        return bar;
    }

    private LinearLayout buildExpanded(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.addView(glassKey(c, R.drawable.ic_gz_prev, false, () -> {
            if (callback != null) callback.onPrev();
        }));
        row.addView(glassKey(c, R.drawable.ic_gz_queue, false, () -> {
            if (callback != null) callback.onQueue();
        }));
        row.addView(glassKey(c, R.drawable.ic_gz_info, true, () -> {
            if (callback != null) callback.onOpenTools();
        }));
        row.addView(glassKey(c, R.drawable.ic_gz_next, false, () -> {
            if (callback != null) callback.onNext();
        }));
        box.addView(row);
        scrubber = new PlayerScrubberView(c);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(12);
        box.addView(scrubber, sLp);
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
                if (callback != null) callback.onQualityClick();
            }

            @Override
            public void onLyricChipTap() {
                if (callback != null) callback.onLyricChipToggle(!MusicStore.isShowTranslation());
            }
        });
        return box;
    }

    private FrameLayout glassKey(Context c, int res, boolean selected, Runnable action) {
        FrameLayout key = new FrameLayout(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        key.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0x47FFFFFF : 0x24FFFFFF);
        bg.setCornerRadius(dp(21));
        bg.setStroke(1, 0x26FFFFFF);
        key.setBackground(bg);
        ImageView iv = GzViews.icon(c, res, 22, 1f);
        centerIn(key, iv);
        key.setOnClickListener(v -> action.run());
        GzViews.pressScale(key, 0.96f);
        return key;
    }

    private FrameLayout smallKey(Context c, int res, float iconDp, Runnable action) {
        FrameLayout box = new FrameLayout(c);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(30)));
        ImageView iv = GzViews.icon(c, res, iconDp, 1f);
        centerIn(box, iv);
        box.setOnClickListener(v -> action.run());
        GzViews.pressScale(box, 0.96f);
        return box;
    }

    private void centerIn(FrameLayout parent, View child) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        child.setLayoutParams(lp);
        parent.addView(child);
    }

    private boolean onCardTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragX = e.getX();
                dragY = e.getY();
                dragMoved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - dragX) > dp(10) || Math.abs(e.getY() - dragY) > dp(10)) dragMoved = true;
                return true;
            case MotionEvent.ACTION_UP:
                float dx = e.getX() - dragX;
                float dy = e.getY() - dragY;
                if (Math.abs(dx) > dp(36) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    if (callback != null) {
                        if (dx < 0) callback.onNext();
                        else callback.onPrev();
                    }
                } else if (!dragMoved) {
                    setExpanded(!expanded);
                }
                return true;
            default:
                return false;
        }
    }

    private void setExpanded(boolean target) {
        expanded = target;
        glassBar.setVisibility(target ? INVISIBLE : VISIBLE);
        expandedBox.setVisibility(target ? VISIBLE : INVISIBLE);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        int availW = getMeasuredWidth() - dp(48);
        int availH = getMeasuredHeight() - dp(42 + 30 + 4 + 22 + 22 + 12);
        int cardW = Math.min(Math.round(availW * 0.98f), Math.round(availH / 0.65f));
        int cardH = Math.round(cardW * 0.65f);
        if (card != null && cardW > 0) {
            FrameLayout parent = (FrameLayout) card.getParent();
            LayoutParams lp = (LayoutParams) card.getLayoutParams();
            if (lp.width != cardW || lp.height != cardH) {
                lp.width = cardW;
                lp.height = cardH;
                lp.gravity = Gravity.CENTER;
                card.setLayoutParams(lp);
            }
            LayoutParams blp = (LayoutParams) backdropView.getLayoutParams();
            blp.width = cardW + dp(24);
            blp.height = cardH + dp(24);
            blp.gravity = Gravity.CENTER;
            backdropView.setLayoutParams(blp);
            parent.requestLayout();
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
        if (url == null || url.isEmpty()) {
            coverView.setImageDrawable(null);
            backdropView.setImageDrawable(null);
            return;
        }
        Glide.with(getContext()).load(url).centerCrop().into(coverView);
        Glide.with(getContext()).load(url).centerCrop().into(backdropView);
        if (android.os.Build.VERSION.SDK_INT >= 31 && CoverColors.blurSupported()) {
            try {
                backdropView.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        dp(24), dp(24), android.graphics.Shader.TileMode.CLAMP));
            } catch (Throwable ignored) {
                backdropView.setRenderEffect(null);
            }
        }
        backdropView.setAlpha(0.55f);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            backdropView.setForeground(new android.graphics.drawable.ColorDrawable(0x59000000));
        }
        if (animate) {
            card.setAlpha(0f);
            card.setTranslationX(dir * dp(32));
            card.animate().alpha(1f).translationX(0f).setDuration(400)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)).start();
        }
    }

    @Override
    public void setPlaying(boolean playing) {
        this.playing = playing;
        barPlayIcon.setVisibility(playing ? INVISIBLE : VISIBLE);
        barPauseIcon.setVisibility(playing ? VISIBLE : INVISIBLE);
        if (titlePlayIcon != null) titlePlayIcon.setVisibility(playing ? INVISIBLE : VISIBLE);
        if (titlePauseIcon != null) titlePauseIcon.setVisibility(playing ? VISIBLE : INVISIBLE);
    }

    @Override
    public void setMode(int mode) {
    }

    @Override
    public void setLyrics(List<RichLyrics.Line> newLines, boolean loading) {
        lines = newLines;
    }

    @Override
    public void setLyricState(String chipLabel, boolean hasWords) {
        lyricChipLabel = chipLabel == null ? "" : chipLabel;
        scrubber.setChips(qualityText, true, lyricChipLabel, true);
    }

    @Override
    public void setQualityText(String text) {
        qualityText = text == null ? "" : text;
        scrubber.setChips(qualityText, true, lyricChipLabel, true);
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
        heartView.setAlpha(favorite ? 0.92f : 0.30f);
        heartView.setColorFilter(favorite ? 0xFFFA243C : Color.WHITE);
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
        if (lines == null) return;
        long pos = positionMs + MusicStore.getLyricOffsetMs();
        String current = "";
        for (RichLyrics.Line line : lines) {
            LyricsLine main = line.main;
            if (main == null) continue;
            if (main.getTimeMs() <= pos) current = main.getText() == null ? "" : main.getText();
            else break;
        }
        barLyricView.setText(current);
    }

    @Override
    public void setBottomStackExpanded(boolean expandedStack) {
        if (!expandedStack && expanded) setExpanded(false);
    }
}
