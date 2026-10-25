package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.utils.CoverTextDrawable;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.List;

/**
 * 全屏歌词页（2026-10-25 从零重写，旧实现整删）。
 *
 * 形态只认瓜子本体实证（他的瓜子真机录屏＋截图 112747/113010，两处同形态互证）：
 * - 背景：清封面居上（约占页高 46%），下沿渐隐进封面取色面，取色面铺满下半页；
 *   播放页喂入的整页糊底位图垫在最下面作氛围底（切歌时封面未就绪不空窗）。
 * - 歌名行压在封面与取色面的交界处（页高约 45%）：歌名左对齐白色粗体，
 *   其下歌手行（白色 0.55）；无封面缩略图、无页眉条。
 * - 歌名行右侧裸键：暂停/播放（瓜子本页三键之一；另两键在瓜子是 ☰ 与播放列表，
 *   ☰ 的瓜子功能定位未抠明、且星辰「一起听」已全删不做，播放列表是播放页本体面板、
 *   本页无契约通道——两键不画假键，见重做报告出入清单）＋星辰保留的主歌词键「Tr」
 *   （点开主歌词三选菜单，播放页接线不变）。
 * - 歌词区在歌名行之下（间隔 6dp），FullLyricView 左对齐沉浸滚动；无底部进度条、
 *   无底部按键。
 * - 歌名字号 16sp/行高 21sp、歌手 13sp/行高 17sp/0.55：与瓜子歌名行组件
 *   （AppleLyricsSongHeader，LyricsViewKt:2083 起）同值，实帧比例互证；
 *   纵向落位（46%/45%）为实帧量比，非源码字面值（本页在瓜子源码中的实现定位
 *   未抠到——2.1.8 源码里只有 pager 页歌词面带页眉，与实帧形态不同，见重做报告）。
 *
 * 与播放页的接入契约（公开方法签名）与旧实现完全一致，本类只换形态与内部结构。
 */
public class MusicLyricPageView extends FrameLayout {

    /** 清封面区高度占页高比例（实帧量比：封面约 0–44%，渐隐带跨其下沿）。 */
    private static final float HERO_HEIGHT_RATIO = 0.46f;
    /** 歌名行顶占页高比例（实帧量比：三处实证帧均在 45%–47%）。 */
    private static final float TITLE_TOP_RATIO = 0.45f;
    /** 取色渐隐：页高 30% 起渐入、56% 起全实（实帧渐隐带量比）。 */
    private static final float FADE_START_RATIO = 0.30f;
    private static final float FADE_END_RATIO = 0.56f;
    /** 横向垫 28dp（LyricsViewKt APPLE_LYRIC_H_PADDING 实读，实帧互证）。 */
    private static final float H_PADDING_DP = 28f;
    /** 歌名行与歌词区间隔 6dp（瓜子页眉下间隔实读）。 */
    private static final float TITLE_TO_LYRIC_GAP_DP = 6f;

    /** 取色面：封面区下半渐隐到取色、其下全实取色，铺满整页。 */
    private static class ToneFieldView extends View {
        private int toneColor = 0xFF1C1C1E;
        private final Paint paint = new Paint();

        ToneFieldView(Context context) {
            super(context);
        }

        void setToneColor(int color) {
            toneColor = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int h = getHeight();
            if (h <= 0) return;
            int r = Color.red(toneColor);
            int g = Color.green(toneColor);
            int b = Color.blue(toneColor);
            float start = h * FADE_START_RATIO;
            float end = h * FADE_END_RATIO;
            paint.setShader(new LinearGradient(0, start, 0, end,
                    new int[]{Color.argb(0, r, g, b), Color.argb(255, r, g, b)},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, getWidth(), h, paint);
            paint.setShader(null);
        }
    }

    private ImageView bgBlurView;
    private ImageView heroView;
    private ToneFieldView toneFieldView;
    private LinearLayout titleBlock;
    private TextView titleView;
    private TextView artistView;
    private ImageView pauseView;
    private TextView trView;
    private FullLyricView lyricView;
    private int toneColor = 0xFF1C1C1E;
    private boolean playing;

    public MusicLyricPageView(Context context) {
        super(context);
        build();
    }

    public MusicLyricPageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        build();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void build() {
        Context context = getContext();
        float pad = dp(H_PADDING_DP);

        bgBlurView = new ImageView(context);
        bgBlurView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgBlurView.setContentDescription(null);
        addView(bgBlurView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        heroView = new ImageView(context);
        heroView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroView.setContentDescription(null);
        addView(heroView, new LayoutParams(LayoutParams.MATCH_PARENT, 0));

        toneFieldView = new ToneFieldView(context);
        addView(toneFieldView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // 歌名行块：歌名行（歌名＋右裸键）＋歌手行，整块压在封面/取色交界。
        titleBlock = new LinearLayout(context);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(Math.round(pad), 0, Math.round(pad), 0);

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        titleView.setTextColor(0xF2FFFFFF);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setIncludeFontPadding(false);
        if (Build.VERSION.SDK_INT >= 28) {
            titleView.setLineHeight(Math.round(21f * getResources().getDisplayMetrics().scaledDensity));
        }
        titleView.setLetterSpacing(-0.3f / 16f);
        titleView.setTypeface(songTitleTypeface());
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(titleView, titleLp);

        pauseView = new ImageView(context);
        pauseView.setImageResource(R.drawable.ic_music_pause);
        pauseView.setContentDescription(null);
        int keyBox = Math.round(dp(40f));
        int keyPad = Math.round(dp(8f));
        pauseView.setPadding(keyPad, keyPad, keyPad, keyPad);
        pauseView.setOnClickListener(v -> MusicController.get().toggle());
        titleRow.addView(pauseView, new LinearLayout.LayoutParams(keyBox, keyBox));

        trView = new TextView(context);
        trView.setText("Tr");
        trView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        trView.setTextColor(0xF2FFFFFF);
        trView.setTypeface(Typeface.DEFAULT_BOLD);
        trView.setGravity(Gravity.CENTER);
        trView.setIncludeFontPadding(false);
        titleRow.addView(trView, new LinearLayout.LayoutParams(keyBox, keyBox));

        titleBlock.addView(titleRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        artistView = new TextView(context);
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        artistView.setTextColor(0x8CFFFFFF);
        artistView.setMaxLines(1);
        artistView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        artistView.setIncludeFontPadding(false);
        if (Build.VERSION.SDK_INT >= 28) {
            artistView.setLineHeight(Math.round(17f * getResources().getDisplayMetrics().scaledDensity));
        }
        LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        artistLp.topMargin = Math.round(dp(2f));
        titleBlock.addView(artistView, artistLp);

        addView(titleBlock, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        lyricView = new FullLyricView(context);
        lyricView.setPadding(Math.round(pad), 0, Math.round(pad), 0);
        lyricView.setFadeColor(toneColor);
        addView(lyricView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private Typeface songTitleTypeface() {
        // 歌名字重照瓜子歌名行组件（sq2.X＝800）；API 28+ 可定字重，低版本退粗体。
        if (Build.VERSION.SDK_INT >= 28) return Typeface.create(Typeface.DEFAULT, 800, false);
        return Typeface.DEFAULT_BOLD;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h <= 0) return;
        LayoutParams heroLp = (LayoutParams) heroView.getLayoutParams();
        heroLp.height = Math.round(h * HERO_HEIGHT_RATIO);
        heroView.setLayoutParams(heroLp);
        LayoutParams titleLp = (LayoutParams) titleBlock.getLayoutParams();
        titleLp.topMargin = Math.round(h * TITLE_TOP_RATIO);
        titleBlock.setLayoutParams(titleLp);
        // 歌词区起点＝歌名行块底＋6dp（块高要等测量，投递到布局后落位）。
        titleBlock.post(() -> {
            if (lyricView == null || titleBlock == null) return;
            LayoutParams llp = (LayoutParams) lyricView.getLayoutParams();
            int top = titleBlock.getTop() + titleBlock.getHeight()
                    + Math.round(dp(TITLE_TO_LYRIC_GAP_DP));
            if (llp.topMargin != top) {
                llp.topMargin = top;
                lyricView.setLayoutParams(llp);
            }
        });
    }

    // ===== 与播放页的接入契约（签名与旧实现一致） =====

    /** 取色更新（切歌／氛围色变化时播放页喂）：取色面与歌词顶渐隐同色。 */
    public void setToneColor(int color) {
        toneColor = color;
        if (toneFieldView != null) toneFieldView.setToneColor(color);
        if (lyricView != null) lyricView.setFadeColor(color);
    }

    public void setTrackInfo(String title, String artist) {
        if (titleView != null) titleView.setText(title == null ? "" : title);
        if (artistView != null) artistView.setText(artist == null ? "" : artist);
    }

    /**
     * 封面（绑定／进页时调）：清封面贴居上封面区。无封面地址才素图（封面占位定案，
     * 与播放页同口径）；有地址加载失败保持透明（下层糊底/取色面透出），不变素图。
     */
    public void setCover(String cover, String title) {
        if (heroView == null) return;
        if (cover == null || cover.isEmpty()) {
            heroView.setImageDrawable(new CoverTextDrawable(title, false,
                    28f * getResources().getDisplayMetrics().density));
        } else {
            ImgUtil.load(title, cover, heroView);
        }
    }

    /** 背景糊底位图（播放页整页糊底成品）：垫在本页最下层作氛围底。 */
    public void setCoverDrawable(Drawable drawable) {
        if (bgBlurView == null || drawable == null) return;
        bgBlurView.setImageDrawable(drawable);
    }

    public void setPlaying(boolean value) {
        playing = value;
        if (pauseView != null) {
            pauseView.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        }
        if (lyricView != null) lyricView.setPlaying(value);
    }

    public void setLyricData(List<LyricsLine> mains, List<String> subs, boolean showSub) {
        if (lyricView == null) return;
        lyricView.setShowSub(showSub);
        lyricView.setLines(mains, subs);
    }

    public void applyLyricStyle(float fontSizeSp, int blurLevel, boolean advancedFx,
                                int lyricWeight, int lyricAlign) {
        if (lyricView == null) return;
        lyricView.setFontSizeSp(fontSizeSp);
        lyricView.setBlurLevel(blurLevel);
        lyricView.setAdvancedFx(advancedFx);
        lyricView.setLyricWeight(lyricWeight);
        lyricView.setTextAlign(lyricAlign);
    }

    public void setLyricPosition(long positionMs) {
        if (lyricView != null) lyricView.setPosition(positionMs);
    }

    public void setEmptyText(String text) {
        if (lyricView != null) lyricView.setEmptyText(text);
    }

    public void setOnLineClickListener(FullLyricView.OnLineClickListener listener) {
        if (lyricView != null) lyricView.setOnLineClickListener(listener);
    }

    /** 主歌词键（主歌词三选菜单入口，菜单与生效由播放页接线）。 */
    public void setOnTrClickListener(OnClickListener listener) {
        if (trView != null) trView.setOnClickListener(listener);
    }

    public FullLyricView getLyricView() {
        return lyricView;
    }
}
