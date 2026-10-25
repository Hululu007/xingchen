package com.fongmi.android.tv.ui.music;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicLyricCache;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.ui.dialog.MusicQueueDialog;

import java.util.List;

/**
 * 全屏歌词页（照定案图 113209＋瓜子全屏歌词规格）：封面拉满整屏垫底（同宿主氛围层）、
 * 标题行左对齐＋右侧三裸键（队列/歌词样式/播停）、歌手行「歌手 ♡ 专辑」、
 * 歌词大字沉浸（24sp、行高 ×1.3、行距 6、padH 28、顶淡出、锚点 .45）、
 * 底部双筹＋细进度线。右划返回播放页。
 */
public class MusicLyricActivity extends AppCompatActivity implements MusicController.Listener {

    public static void start(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, MusicLyricActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        if (context instanceof Activity) {
            ((Activity) context).overridePendingTransition(
                    R.anim.music_lyric_in, R.anim.music_lyric_out);
        }
    }

    private static MusicLyricActivity instance;

    public static boolean isShowing() {
        return instance != null;
    }

    public static void reloadLyrics() {
        if (instance != null) instance.loadLyric();
    }

    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private CoverAmbientView ambient;
    private LyricEngineView lyricView;
    private TextView titleView;
    private TextView artistView;
    private ImageView heartView;
    private ImageView playIcon;
    private ImageView pauseIcon;
    private TextView lyricChip;
    private TextView transChip;
    private View progressFill;
    private View progressTrack;
    private String boundKey;
    private boolean favorite;
    private float swipeX;
    private float swipeY;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            onTick();
            tickHandler.postDelayed(this, 500L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        if (MusicStore.isPlayerKeepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        float density = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        ambient = new CoverAmbientView(this);
        ambient.setFluid(MusicPlayerSettings.isFluidBg(), MusicPlayerSettings.isFluidBeat(),
                MusicPlayerSettings.fluidSpeedFactor());
        root.addView(ambient, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(Math.round(28 * density), 0, Math.round(28 * density), 0);
        root.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(titleRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(34 * density)));
        titleView = GzViews.text(this, "", 17, 800, 0xF2FFFFFF);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(bareButton(R.drawable.ic_gz_queue, 20, () -> {
            if (!isFinishing()) MusicQueueDialog.show(this);
        }));
        titleRow.addView(bareButton(R.drawable.ic_gz_menu, 21, () -> {
            if (!isFinishing()) PlayerToolsDialog.show(this, PlayerToolsDialog.Panel.LYRIC_STYLE);
        }));
        FrameLayout playBtn = new FrameLayout(this);
        playBtn.setLayoutParams(new LinearLayout.LayoutParams(Math.round(40 * density), Math.round(34 * density)));
        playIcon = GzViews.icon(this, R.drawable.ic_gz_play, 24, 1f);
        pauseIcon = GzViews.icon(this, R.drawable.ic_gz_pause, 21, 1f);
        center(playBtn, playIcon);
        center(playBtn, pauseIcon);
        playBtn.setOnClickListener(v -> MusicController.get().toggle());
        titleRow.addView(playBtn);

        LinearLayout artistRow = new LinearLayout(this);
        artistRow.setOrientation(LinearLayout.HORIZONTAL);
        artistRow.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(artistRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(24 * density)));
        artistView = GzViews.text(this, "", 13, 500, 0x99FFFFFF);
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        artistRow.addView(artistView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        heartView = new ImageView(this);
        heartView.setImageResource(R.drawable.ic_gz_heart);
        LinearLayout.LayoutParams heartLp = new LinearLayout.LayoutParams(
                Math.round(25 * density), Math.round(24 * density));
        heartView.setPadding(Math.round(6 * density), Math.round(5 * density),
                Math.round(6 * density), Math.round(6 * density));
        heartView.setLayoutParams(heartLp);
        heartView.setAlpha(0.30f);
        heartView.setOnClickListener(v -> toggleFavorite());
        GzViews.pressScale(heartView, 0.85f);
        artistRow.addView(heartView);

        lyricView = new LyricEngineView(this);
        column.addView(lyricView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        lyricView.setPalette(LyricEngineView.Palette.fullscreen());
        lyricView.setListener((index, timeMs) -> {
            MusicController.get().seekTo(timeMs);
            lyricView.resyncToCurrent();
        });

        FrameLayout progressBox = new FrameLayout(this);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(3 * density));
        trackLp.topMargin = Math.round(8 * density);
        column.addView(progressBox, trackLp);
        progressTrack = new View(this);
        progressTrack.setBackgroundColor(0x24FFFFFF);
        progressBox.addView(progressTrack, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        progressFill = new View(this);
        progressFill.setBackgroundColor(0x6BFFFFFF);
        progressBox.addView(progressFill, new FrameLayout.LayoutParams(
                0, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams chipRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(36 * density));
        chipRowLp.bottomMargin = Math.round(10 * density);
        column.addView(chipRow, chipRowLp);
        lyricChip = chip("逐行");
        transChip = chip(MusicStore.isShowTranslation() ? "译 开" : "译 关");
        transChip.setOnClickListener(v -> {
            boolean target = !MusicStore.isShowTranslation();
            MusicStore.setShowTranslation(target);
            transChip.setText(target ? "译 开" : "译 关");
            loadLyric();
        });
        chipRow.addView(lyricChip);
        chipRow.addView(transChip);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
            } else {
                top = insets.getSystemWindowInsetTop();
            }
            column.setPadding(Math.round(28 * density), top, Math.round(28 * density), 0);
            return insets;
        });
        root.setOnTouchListener(this::onRootSwipe);
        setContentView(root);

        MusicController.get().bind(this);
        MusicController.get().addListener(this);
        MusicTrack current = MusicController.get().current();
        if (current != null) bindTrack(current);
        tickHandler.post(tick);
    }

    private void center(FrameLayout parent, View child) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        child.setLayoutParams(lp);
        parent.addView(child);
    }

    private FrameLayout bareButton(int res, float iconDp, Runnable action) {
        FrameLayout box = new FrameLayout(this);
        float density = getResources().getDisplayMetrics().density;
        box.setLayoutParams(new LinearLayout.LayoutParams(Math.round(40 * density), Math.round(34 * density)));
        center(box, GzViews.icon(this, res, iconDp, 0.95f));
        box.setOnClickListener(v -> action.run());
        GzViews.pressScale(box, 0.96f);
        return box;
    }

    private TextView chip(String label) {
        float density = getResources().getDisplayMetrics().density;
        TextView tv = GzViews.text(this, label, 11, 700, 0xB8FFFFFF);
        tv.setBackground(new android.graphics.drawable.GradientDrawable() {{
            setColor(0x24FFFFFF);
            setCornerRadius(999f);
        }});
        tv.setPadding(Math.round(10 * density), Math.round(4 * density),
                Math.round(10 * density), Math.round(4 * density));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Math.round(8 * density);
        tv.setLayoutParams(lp);
        return tv;
    }

    private boolean onRootSwipe(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swipeX = e.getX();
                swipeY = e.getY();
                return false;
            case MotionEvent.ACTION_UP:
                float dx = e.getX() - swipeX;
                float dy = e.getY() - swipeY;
                float density = getResources().getDisplayMetrics().density;
                if (dx > 64 * density && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                    finish();
                    overridePendingTransition(R.anim.music_lyric_return_in, R.anim.music_lyric_return_out);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private void toggleFavorite() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        boolean target = !favorite;
        favorite = target;
        heartView.setAlpha(target ? 0.45f : 0.30f);
        heartView.setColorFilter(target ? 0xFFFA243C : android.graphics.Color.WHITE);
        MusicLibrary.setFavorite(track, target, () -> refreshFavorite(track));
    }

    private void refreshFavorite(MusicTrack track) {
        MusicLibrary.isFavorite(track, fav -> {
            if (isFinishing() || isDestroyed()) return;
            MusicTrack current = MusicController.get().current();
            if (current == track) {
                favorite = fav;
                heartView.setAlpha(fav ? 0.45f : 0.30f);
                heartView.setColorFilter(fav ? 0xFFFA243C : android.graphics.Color.WHITE);
            }
        });
    }

    private void bindTrack(MusicTrack track) {
        boundKey = MusicQueue.trackKey(track);
        titleView.setText(track.displayTitle());
        String album = track.album;
        String artistLine = track.displayArtist()
                + (TextUtils.isEmpty(album) || "未知专辑".equals(album) ? "" : " · " + album);
        artistView.setText(artistLine);
        CoverColors.load(this, track.cover, (single, extracted) -> {
            if (isFinishing() || isDestroyed()) return;
            MusicTrack cur = MusicController.get().current();
            if (cur == null || track.cover == null || !track.cover.equals(cur.cover)) return;
            ambient.setCover(track.cover, single != null ? single : CoverColors.fallback());
        });
        if (CoverColors.single(track.cover) == null) {
            ambient.setCover(track.cover, CoverColors.fallback());
        }
        refreshFavorite(track);
        loadLyric();
    }

    private void loadLyric() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        MusicLyric lyric = MusicLyricCache.get(track);
        lyricView.configure(MusicPlayerSettings.getFullLyricFontSize(), 6f, 28f,
                true, MusicPlayerSettings.isAdvancedFx(), MusicStore.isShowTranslation(),
                MusicStore.isShowRomanization(), MusicStore.getLyricPrimary(),
                MusicStore.getLyricAlign(), 1.0f);
        if (lyric == null || !lyric.hasLyric()) {
            lyricView.setLines(null);
            lyricChip.setText("无词");
            return;
        }
        List<com.fongmi.android.tv.player.lyrics.LyricsLine> main =
                LyricsParser.parseWordLyric(lyric.rawLrc);
        List<com.fongmi.android.tv.player.lyrics.LyricsLine> trans = TextUtils.isEmpty(lyric.translation)
                ? null : LyricsParser.parseTimed(lyric.translation);
        List<com.fongmi.android.tv.player.lyrics.LyricsLine> roma = TextUtils.isEmpty(lyric.romanization)
                ? null : LyricsParser.parseTimed(lyric.romanization);
        lyricView.setLines(RichLyrics.align(main, trans, roma));
        lyricChip.setText(chipLabel(lyric, main));
    }

    private String chipLabel(MusicLyric lyric, List<com.fongmi.android.tv.player.lyrics.LyricsLine> main) {
        if (lyric.format != null && lyric.format.toUpperCase().contains("AMLL")) return "AMLL";
        if (main == null || main.isEmpty()) return "无词";
        for (com.fongmi.android.tv.player.lyrics.LyricsLine l : main) {
            if (l != null && l.hasWords()) return "逐字";
        }
        long first = main.get(0).getTimeMs();
        long last = main.get(main.size() - 1).getTimeMs();
        if (main.size() >= 2 && first == last) return "纯文本";
        return "逐行";
    }

    private void onTick() {
        MusicController controller = MusicController.get();
        MusicTrack current = controller.current();
        if (current != null && !MusicQueue.trackKey(current).equals(boundKey)) {
            bindTrack(current);
        }
        boolean playing = controller.isPlaying();
        playIcon.setVisibility(playing ? View.INVISIBLE : View.VISIBLE);
        pauseIcon.setVisibility(playing ? View.VISIBLE : View.INVISIBLE);
        long position = controller.position();
        long duration = controller.duration();
        lyricView.setPosition(position + MusicStore.getLyricOffsetMs());
        float frac = duration > 0 ? Math.max(0f, Math.min(1f, position / (float) duration)) : 0f;
        if (progressTrack.getWidth() > 0) {
            ViewGroup.LayoutParams fillLp = progressFill.getLayoutParams();
            int target = Math.round(progressTrack.getWidth() * frac);
            if (fillLp.width != target) {
                fillLp.width = target;
                progressFill.setLayoutParams(fillLp);
            }
        }
    }

    @Override
    public void onTrackChanged(MusicTrack track) {
        if (isFinishing() || isDestroyed()) return;
        if (track != null) bindTrack(track);
    }

    @Override
    public void onPlayingChanged(boolean playing) {
    }

    @Override
    public void onProgress(long positionMs, long durationMs) {
    }

    @Override
    public void onQueueChanged() {
    }

    @Override
    public void onModeChanged(int mode) {
    }

    @Override
    public void onSleepChanged(int sleep) {
    }

    @Override
    public void onAudioInfoChanged() {
    }

    @Override
    public void onKeyIssue(String platform, String detail) {
    }

    @Override
    public void onTrackReady(MusicTrack track) {
    }

    @Override
    public void onLyricReady(MusicTrack track) {
        if (isFinishing() || isDestroyed()) return;
        MusicTrack current = MusicController.get().current();
        if (track == current) loadLyric();
    }

    @Override
    public void onTrackFailed(MusicTrack track, int reasonRes) {
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        tickHandler.removeCallbacks(tick);
        MusicController.get().removeListener(this);
        MusicController.get().unbind();
        super.onDestroy();
    }
}
