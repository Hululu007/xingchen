package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.utils.CoverTextDrawable;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.List;

/**
 * 全屏歌词页（ui/music 包）：播放页内的整屏页层，共用播放页已加载的歌词数据与 MusicController，
 * 不另起取词链。形态只认定案（他的图 112930＋2026-10-22「歌词页重做」定案）：
 * 背景＝封面模糊底（播放页喂整页糊底同配方位图，API31+ 另挂 24dp 模糊效；上盖半透取色罩保可读，
 * 歌词页专属模糊值未抠到、与播放页糊底同值落）；页眉＝封面缩略图＋歌名＋歌手＋右上 Tr 钮
 * （40dp，点开主歌词三选菜单——原文恒有、翻译／音译有数据才出，选后内嵌＋全屏同刷）；
 * 歌词左对齐沉浸滚动（FullLyricView）。没有底部进度条、没有底部按键。
 * 与录屏 f001 形态（清封面拉满＋歌名行队列／暂停键）的出入：那是瓜子的另一歌词面，
 * 本页以 112930 定案为准（冲突在五主题对照表单列）；头行最左的 ☰ 在瓜子＝一起听邀请入口，
 * 定案不做（2026-10-20 全删），不留假键。
 * 入口＝播放页右划（定案）；点歌词行只快进、不进本页。
 */
public class MusicLyricPageView extends FrameLayout {

    /** 半透取色罩：糊底之上由上到下微加深（永不实底，糊底全程可见）；停点/alpha 是可读性近似，瓜子歌词页罩值未抠到。 */
    private static class ToneVeilView extends View {
        private int toneColor = 0xFF1C1C1E;
        private final Paint paint = new Paint();

        ToneVeilView(Context context) {
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
            paint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{Color.argb(76, r, g, b), Color.argb(92, r, g, b),
                            Color.argb(117, r, g, b), Color.argb(143, r, g, b)},
                    new float[]{0f, 0.4f, 0.75f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, getWidth(), h, paint);
            paint.setShader(null);
        }
    }

    private ImageView coverView;
    private ToneVeilView veilView;
    private View headerView;
    private ImageView thumbView;
    private TextView titleView;
    private TextView artistView;
    private TextView trView;
    private FullLyricView lyricView;
    private int toneColor = 0xFF1C1C1E;

    public MusicLyricPageView(Context context) {
        super(context);
    }

    public MusicLyricPageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        coverView = findViewById(R.id.ivLyricCover);
        headerView = findViewById(R.id.lyricHeader);
        thumbView = findViewById(R.id.ivLyricThumb);
        titleView = findViewById(R.id.tvLyricTitle);
        artistView = findViewById(R.id.tvLyricArtist);
        trView = findViewById(R.id.btnLyricTr);
        lyricView = findViewById(R.id.lyricFullView);
        // 取色罩插在背景之上、页眉与歌词之下（子序 1）。
        veilView = new ToneVeilView(getContext());
        addView(veilView, 1, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (lyricView != null) lyricView.setFadeColor(toneColor);
        if (thumbView != null) {
            // 缩略图圆角 8dp（应用内封面圆角同路：OutlineProvider＋clipToOutline）。
            final float radius = 8f * getResources().getDisplayMetrics().density;
            thumbView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            thumbView.setClipToOutline(true);
        }
        if (trView != null) {
            GradientDrawable glass = new GradientDrawable();
            glass.setShape(GradientDrawable.OVAL);
            glass.setColor(0x40FFFFFF);
            glass.setStroke(Math.max(1, Math.round(getResources().getDisplayMetrics().density)), 0x59FFFFFF);
            trView.setBackground(glass);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 页眉落页面顶部（状态栏之下 8dp）；歌词区起点＝页眉底＋6dp。此二值是排版量，非瓜子源码值（其页眉源码定位未抠到）。
        float density = getResources().getDisplayMetrics().density;
        if (headerView != null) {
            LayoutParams lp = (LayoutParams) headerView.getLayoutParams();
            lp.topMargin = statusBarHeightPx() + Math.round(8f * density);
            headerView.setLayoutParams(lp);
            // 页眉高度要等测量完才准：投递到测量后按页眉实际底部落歌词区起点。
            headerView.post(() -> {
                if (lyricView == null || headerView == null) return;
                LayoutParams llp = (LayoutParams) lyricView.getLayoutParams();
                int top = headerView.getTop() + headerView.getHeight() + Math.round(6f * density);
                if (llp.topMargin != top) {
                    llp.topMargin = top;
                    lyricView.setLayoutParams(llp);
                }
            });
        }
    }

    private int statusBarHeightPx() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return getResources().getDimensionPixelSize(id);
        return Math.round(24f * getResources().getDisplayMetrics().density);
    }

    /** 取色更新（切歌／氛围色动画时由播放页喂）：罩与歌词顶渐隐同色。 */
    public void setToneColor(int color) {
        toneColor = color;
        if (veilView != null) veilView.setToneColor(color);
        if (lyricView != null) lyricView.setFadeColor(color);
    }

    public void setTrackInfo(String title, String artist) {
        if (titleView != null) titleView.setText(title == null ? "" : title);
        if (artistView != null) artistView.setText(artist == null ? "" : artist);
    }

    /**
     * 封面（绑定／进页时调）：缩略图走实图；背景先贴实图占位，糊底就绪后由播放页
     * setCoverDrawable 换糊版。无封面地址才素图（封面占位定案，与播放页同口径），
     * 有地址加载失败保持透明、不变素图。
     */
    public void setCover(String cover, String title) {
        float density = getResources().getDisplayMetrics().density;
        if (thumbView != null) {
            if (cover == null || cover.isEmpty()) {
                thumbView.setImageDrawable(new CoverTextDrawable(title, false, 12 * density));
            } else {
                ImgUtil.load(title, cover, thumbView);
            }
        }
        if (coverView == null) return;
        clearCoverEffect();
        if (cover == null || cover.isEmpty()) {
            coverView.setImageDrawable(new CoverTextDrawable(title, false, 28 * density));
        } else {
            ImgUtil.load(title, cover, coverView);
        }
    }

    /** 背景换糊底位图（播放页整页糊底的成品）：API31+ 补挂 24dp 模糊效（位图层在 ≥31 未糊、靠视图效，与 ivBgBlur 同口径）。 */
    public void setCoverDrawable(Drawable drawable) {
        if (coverView == null || drawable == null) return;
        coverView.setImageDrawable(drawable);
        if (Build.VERSION.SDK_INT >= 31) {
            float r = 24f * getResources().getDisplayMetrics().density;
            coverView.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
        } else {
            clearCoverEffect();
        }
    }

    private void clearCoverEffect() {
        if (coverView != null && Build.VERSION.SDK_INT >= 31) {
            coverView.setRenderEffect(null);
        }
    }

    public void setPlaying(boolean playing) {
        if (lyricView != null) lyricView.setPlaying(playing);
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

    /** Tr 钮（主歌词三选菜单入口，菜单与生效由播放页接线）。 */
    public void setOnTrClickListener(OnClickListener listener) {
        if (trView != null) trView.setOnClickListener(listener);
    }

    public FullLyricView getLyricView() {
        return lyricView;
    }
}
