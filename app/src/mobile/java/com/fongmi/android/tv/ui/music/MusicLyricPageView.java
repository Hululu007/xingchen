package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
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
 * 全屏歌词页（批 2 新页，ui/music 包）：播放页内的整屏页层（与瓜子 pager 内一页同理，
 * 共用播放页已加载的歌词数据与 MusicController，不另起取词链）。
 * 形态只认实证（录屏 f001＋截图 112747＋对照图 113010）：封面拉满整屏作背景、下半渐隐到封面取色底；
 * 歌名左对齐压在歌词上方、歌名下「歌手 ♡ 专辑名」一行；歌名行右侧两个裸图标——播放列表（f003 实证）、暂停。
 * 头行最左的 ☰ 已摘（批 7 终局）：jadx 深挖抠实它在瓜子＝「一起听」邀请面板入口
 * （ListenTogetherInviteSheet），而一起听是定案不做项（2026-10-20 他拍板全删＋本重建对照单 §八），
 * 故按「假键比缺键更糟」的明规摘键、不接线、不留空洞（证据链见 batch7-h-finale-report.md）。
 * 歌词左对齐沉浸滚动（FullLyricView）。没有顶部小封面、没有底部进度条和底部按键。
 * 入口＝播放页右划（定案）；点歌词行只快进、不进本页。
 */
public class MusicLyricPageView extends FrameLayout {

    /** 取色渐隐罩：封面上部透明，约 34% 起渐变、47% 到取色实底，其下全实（停点照 f001 实帧量）。 */
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
            int opaque = toneColor | 0xFF000000;
            int transparent = toneColor & 0x00FFFFFF;
            paint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{transparent, transparent, opaque, opaque},
                    new float[]{0f, 0.34f, 0.47f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, getWidth(), h, paint);
            paint.setShader(null);
        }
    }

    private ImageView coverView;
    private ToneVeilView veilView;
    private View headerView;
    private TextView titleView;
    private TextView artistView;
    private TextView albumView;
    private ImageView heartView;
    private ImageView playView;
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
        titleView = findViewById(R.id.tvLyricTitle);
        artistView = findViewById(R.id.tvLyricArtist);
        albumView = findViewById(R.id.tvLyricAlbum);
        heartView = findViewById(R.id.ivLyricHeart);
        playView = findViewById(R.id.btnLyricPlay);
        lyricView = findViewById(R.id.lyricFullView);
        // 渐隐罩插在封面之上、头行与歌词之下（子序 1）。
        veilView = new ToneVeilView(getContext());
        addView(veilView, 1, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (lyricView != null) lyricView.setFadeColor(toneColor);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 头行压在封面方幅（宽＝高）下沿的渐隐区：顶距＝屏宽－30dp（标题压在渐隐上沿内）；
        // 歌词区起点＝头行底＋6dp。此二值是按实帧构图落的排版量，非瓜子源码值（源码定位未抠到，§6.3）。
        float density = getResources().getDisplayMetrics().density;
        if (headerView != null) {
            LayoutParams lp = (LayoutParams) headerView.getLayoutParams();
            lp.topMargin = Math.max(0, Math.round(w - 30f * density));
            headerView.setLayoutParams(lp);
            // 头行高度要等测量完才准：投递到测量后按头行实际底部落歌词区起点。
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

    /** 取色底更新（切歌／氛围色变时由播放页喂）：渐隐罩与歌词顶渐隐同色。 */
    public void setToneColor(int color) {
        toneColor = color;
        if (veilView != null) veilView.setToneColor(color);
        if (lyricView != null) lyricView.setFadeColor(color);
    }

    public void setTrackInfo(String title, String artist, String album) {
        if (titleView != null) titleView.setText(title == null ? "" : title);
        if (artistView != null) artistView.setText(artist == null ? "" : artist);
        boolean hasAlbum = album != null && !album.isEmpty();
        if (albumView != null) {
            albumView.setText(hasAlbum ? album : "");
            albumView.setVisibility(hasAlbum ? View.VISIBLE : View.GONE);
        }
        if (heartView != null) heartView.setVisibility(hasAlbum ? View.VISIBLE : View.GONE);
    }

    /** 封面：有地址走图（加载失败保持透明），无地址才素图（封面占位定案，与播放页同口径）。 */
    public void setCover(String cover, String title) {
        if (coverView == null) return;
        if (cover == null || cover.isEmpty()) {
            coverView.setImageDrawable(new CoverTextDrawable(title, false,
                    28 * getResources().getDisplayMetrics().density));
        } else {
            ImgUtil.load(title, cover, coverView);
        }
    }

    public void setPlaying(boolean playing) {
        if (playView != null) {
            playView.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        }
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
        // 批 7 补线：字重／对齐下发全屏歌词视图（此前 applyLyricStyle 只到字号／模糊／动效）。
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

    public void setOnQueueClickListener(OnClickListener listener) {
        View queue = findViewById(R.id.btnLyricQueue);
        if (queue != null) queue.setOnClickListener(listener);
    }

    public void setOnPlayClickListener(OnClickListener listener) {
        if (playView != null) playView.setOnClickListener(listener);
    }

    public FullLyricView getLyricView() {
        return lyricView;
    }
}
