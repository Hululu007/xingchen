package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;

/**
 * 经典主题内嵌歌词窗口（重做第二步，规格 gz-full-parity-1025 §2＋§7.1/§7.4＋§15.1-3）：
 * 窗口前 2 后 6、当前行锚窗口第 3 行；字号走封面键（默认 22）、行高＝字号×4/3、
 * 行距 20dp、字距不设（瓜子 Cover 路恒 Unspecified）、字重走封面字重键、对齐走歌词对齐键（默认左）。
 * 行色 §7.1 深色面回落：当前行 0xFFEAEAEA、非当前 白 0.30；全行字斜 −0.12（他定案）。
 * 点行＝快进（行起始 getTimeMs 回调）。本视图不拦截竖向拖动——经典竖滑归页级切歌（§1）。
 * 行级最小落地：逐字分段着色、非当前行模糊、窗口弹簧趋近属歌词引擎（歌词步），本步不做不自创；
 * 译文行不显（瓜子当前行 hasSub=false＋第一步同口径）。
 */
public class ClassicLyricView extends LinearLayout {

    public interface SeekListener {
        void onLineSeek(long timeMs);
    }

    private static final int WINDOW_BEFORE = 2;
    private static final int WINDOW_AFTER = 6;
    private static final int LINE_GAP_DP = 20;

    private SeekListener seekListener;
    private List<LyricsLine> mainLines;
    private boolean lyricLoading = true;
    private int lyricIndex = -2;

    public ClassicLyricView(Context context) {
        this(context, null);
    }

    public ClassicLyricView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
    }

    public void setSeekListener(SeekListener listener) {
        this.seekListener = listener;
    }

    /** 歌词换批：lines=null 且 loading=true＝加载中；lines 空＝暂无歌词。 */
    public void setLines(List<LyricsLine> lines, boolean loading) {
        mainLines = lines;
        lyricLoading = loading;
        lyricIndex = -2;
        render();
    }

    /** 按播放位置推进当前行（行定位走 LyricsParser.findLine＋设置偏移，与封面卡同口径）。 */
    public void advance(long positionMs) {
        if (mainLines == null || mainLines.isEmpty()) return;
        int idx = LyricsParser.findLine(mainLines, positionMs + MusicPlayerSettings.getLyricOffsetMs());
        if (idx == lyricIndex) return;
        lyricIndex = idx;
        render();
    }

    private void render() {
        removeAllViews();
        float fontSp = MusicPlayerSettings.getCoverLyricFontSize();
        int lineHeightPx = (int) (ResUtil.sp2px((int) fontSp) * 4f / 3f);
        int gapPx = ResUtil.dp2px(LINE_GAP_DP);
        if (mainLines == null || mainLines.isEmpty()) {
            TextView t = lineView(fontSp, lineHeightPx);
            t.setText(mainLines == null && lyricLoading ? "歌词加载中…" : "暂无歌词");
            t.setTextColor(0x8CFFFFFF);
            addView(t);
            return;
        }
        int cur = Math.max(0, Math.min(lyricIndex, mainLines.size() - 1));
        int from = cur - WINDOW_BEFORE;
        int to = cur + WINDOW_AFTER;
        for (int i = from; i <= to; i++) {
            TextView t = lineView(fontSp, lineHeightPx);
            if (i >= 0 && i < mainLines.size()) {
                final LyricsLine line = mainLines.get(i);
                t.setText(line == null ? "" : line.getText());
                if (line != null) {
                    t.setClickable(true);
                    t.setOnClickListener(v -> {
                        if (seekListener != null) seekListener.onLineSeek(line.getTimeMs());
                    });
                }
            } else {
                t.setText("");
            }
            // 行色 §7.1 深色面回落：当前行 EAEAEA、非当前 白 0.30
            t.setTextColor(i == cur ? 0xFFEAEAEA : 0x4DFFFFFF);
            if (i > from) {
                LayoutParams lp = (LayoutParams) t.getLayoutParams();
                lp.topMargin = gapPx;
                t.setLayoutParams(lp);
            }
            addView(t);
        }
    }

    private TextView lineView(float fontSp, int lineHeightPx) {
        TextView t = new TextView(getContext());
        t.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, lineHeightPx));
        int align = MusicPlayerSettings.getLyricAlign();
        t.setGravity(align == 1 ? Gravity.CENTER : align == 2 ? Gravity.END : Gravity.START);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextSize(fontSp);
        // 字重档→值（§7：0→700／1→800／2→900，默认 700）
        int weightTier = MusicPlayerSettings.getLyricWeight();
        t.setTypeface(CoverCardThemeView.typeface(weightTier == 1 ? 800 : weightTier == 2 ? 900 : 700));
        t.getPaint().setTextSkewX(-0.12f);
        return t;
    }
}
