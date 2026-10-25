package com.fongmi.android.tv.ui.music;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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

public class MusicLyricActivity extends AppCompatActivity implements MusicController.Listener {

    public static void start(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, MusicLyricActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private CoverAmbientView ambient;
    private GzLyricView lyricView;
    private TextView titleView;
    private TextView artistView;
    private TextView albumView;
    private ImageView heartView;
    private ImageView playPauseView;
    private TextView chipStd;
    private TextView chipWord;
    private ProgressLine progressLine;
    private MusicTrack boundTrack;
    private String boundKey;
    private boolean bound;
    private boolean favorite;
    private boolean playing;
    private boolean lyricSettled;
    private int lyricWaitTicks;
    private float downX;
    private float downY;
    private boolean backSwipeFired;

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
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        FrameLayout root = new FrameLayout(this);
        ambient = new CoverAmbientView(this);
        ambient.setFluidEnabled(MusicPlayerSettings.isFluidBg());
        ambient.setBeatEnabled(MusicPlayerSettings.isFluidBeat());
        ambient.setSpeedFactors(MusicPlayerSettings.fluidSpeedFactor(), MusicPlayerSettings.fluidBeatFactor());
        root.addView(ambient, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        lyricView = new GzLyricView(this);
        lyricView.setMode(GzLyricView.MODE_FULL);
        lyricView.setPalette(new GzLyricLineView.Palette(
                0xFFEAEAEA, PlayerViews.white(0.42f), PlayerViews.white(0.30f), PlayerViews.white(0.55f)));
        root.addView(lyricView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        lyricView.setOnLineSeek(timeMs -> MusicController.get().seekTo(timeMs));

        root.addView(buildHeader(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));
        root.addView(buildBottom(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        setContentView(root);

        MusicController.get().bind(this);
        MusicController.get().addListener(this);
        bound = true;
        MusicTrack current = MusicController.get().current();
        if (current != null) bindTrack(current);
        playing = MusicController.get().isPlaying();
        playPauseView.setImageResource(playing ? R.drawable.ic_gz_pause : R.drawable.ic_gz_play);
        tickHandler.post(tick);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        int status = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) status = getResources().getDimensionPixelSize(resourceId);
        header.setPadding(dpI(28), status + dpI(10), dpI(28), 0);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleView = new TextView(this);
        titleView.setTextSize(20f);
        titleView.setTypeface(PlayerViews.weight(800));
        titleView.setTextColor(PlayerViews.white(0.96f));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        titleView.setMarqueeRepeatLimit(-1);
        titleView.setSelected(true);
        titleView.setLetterSpacing(-0.4f / 20f);
        titleRow.addView(titleView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView queueKey = bareKey(R.drawable.ic_gz_queue, 20f);
        queueKey.setOnClickListener(v -> MusicQueueDialog.show(this));
        titleRow.addView(queueKey);
        ImageView styleKey = bareKey(R.drawable.ic_gz_style, 20f);
        styleKey.setOnClickListener(v -> LyricStyleDialog.show(this, () -> refreshLyricStyle()));
        titleRow.addView(styleKey);
        playPauseView = bareKey(R.drawable.ic_gz_pause, 24f);
        playPauseView.setOnClickListener(v -> MusicController.get().toggle());
        titleRow.addView(playPauseView);
        header.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(30)));

        LinearLayout artistRow = new LinearLayout(this);
        artistRow.setOrientation(LinearLayout.HORIZONTAL);
        artistRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(22));
        artistLp.topMargin = dpI(4);
        header.addView(artistRow, artistLp);
        artistView = new TextView(this);
        artistView.setTextSize(14f);
        artistView.setTypeface(PlayerViews.weight(500));
        artistView.setTextColor(PlayerViews.white(0.65f));
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        artistRow.addView(artistView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        heartView = new ImageView(this);
        heartView.setImageResource(R.drawable.ic_gz_favorite_border);
        heartView.setPadding(dpI(7), 0, dpI(7), 0);
        LinearLayout.LayoutParams heartLp = new LinearLayout.LayoutParams(dpI(27), dpI(13));
        heartLp.leftMargin = dpI(2);
        artistRow.addView(heartView, heartLp);
        heartView.setOnClickListener(v -> toggleFavorite());
        albumView = new TextView(this);
        albumView.setTextSize(14f);
        albumView.setTypeface(PlayerViews.weight(500));
        albumView.setTextColor(PlayerViews.white(0.45f));
        albumView.setSingleLine(true);
        albumView.setEllipsize(TextUtils.TruncateAt.END);
        artistRow.addView(albumView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return header;
    }

    private ImageView bareKey(int res, float sizeDp) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpI(40), dpI(40));
        iv.setLayoutParams(lp);
        int s = dpI(sizeDp);
        iv.setPadding((dpI(40) - s) / 2, (dpI(40) - s) / 2, (dpI(40) - s) / 2, (dpI(40) - s) / 2);
        return iv;
    }

    private View buildBottom() {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER);
        chipRow.setPadding(0, dpI(8), 0, dpI(10));
        chipStd = makeChip("Std");
        chipWord = makeChip("逐字");
        chipRow.addView(chipStd);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.leftMargin = dpI(10);
        chipWord.setLayoutParams(wlp);
        chipRow.addView(chipWord);
        chipStd.setOnClickListener(v -> setLyricPriority(false));
        chipWord.setOnClickListener(v -> setLyricPriority(true));
        bottom.addView(chipRow);
        progressLine = new ProgressLine(this);
        bottom.addView(progressLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpI(2)));
        refreshChips();
        return bottom;
    }

    private TextView makeChip(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dpI(14), dpI(5), dpI(14), dpI(5));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dpI(50));
        tv.setBackground(bg);
        return tv;
    }

    private void setLyricPriority(boolean wordByWord) {
        MusicStore.setLyricPriority(wordByWord
                ? MusicStore.LYRIC_PRIORITY_WORD_BY_WORD : MusicStore.LYRIC_PRIORITY_LINE_BY_LINE);
        refreshChips();
        if (boundTrack != null) tryLoadLyricFromCache(boundTrack);
    }

    private void refreshChips() {
        boolean word = MusicStore.LYRIC_PRIORITY_WORD_BY_WORD.equals(MusicStore.getLyricPriority());
        styleChip(chipStd, !word);
        styleChip(chipWord, word);
    }

    private void styleChip(TextView chip, boolean active) {
        android.graphics.drawable.GradientDrawable bg =
                (android.graphics.drawable.GradientDrawable) chip.getBackground();
        bg.setColor(PlayerViews.white(active ? 0.28f : 0.14f));
        chip.setAlpha(active ? 1f : 0.75f);
    }

    private void refreshLyricStyle() {
        lyricView.setMode(GzLyricView.MODE_FULL);
        if (boundTrack != null) tryLoadLyricFromCache(boundTrack);
    }

    private void toggleFavorite() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        boolean target = !favorite;
        setFavorite(target);
        MusicLibrary.setFavorite(track, target, () -> refreshFavorite(track));
    }

    private void refreshFavorite(MusicTrack track) {
        MusicLibrary.isFavorite(track, fav -> {
            if (isFinishing() || isDestroyed()) return;
            if (track == boundTrack) setFavorite(fav);
        });
    }

    private void setFavorite(boolean fav) {
        favorite = fav;
        heartView.setImageResource(fav ? R.drawable.ic_gz_favorite : R.drawable.ic_gz_favorite_border);
        heartView.setAlpha(fav ? 0.45f : 0.30f);
    }

    private void bindTrack(MusicTrack track) {
        boundTrack = track;
        boundKey = MusicQueue.trackKey(track);
        lyricSettled = false;
        lyricWaitTicks = 0;
        titleView.setText(track.displayTitle());
        artistView.setText(track.displayArtist());
        boolean hasAlbum = !TextUtils.isEmpty(track.album);
        albumView.setText(hasAlbum ? track.album : "");
        albumView.setVisibility(hasAlbum ? View.VISIBLE : View.GONE);
        ambient.setCover(track.cover);
        lyricView.setLyrics(null, true);
        refreshFavorite(track);
        tryLoadLyricFromCache(track);
    }

    private void tryLoadLyricFromCache(MusicTrack track) {
        MusicLyric lyric = MusicLyricCache.get(track);
        if (lyric == null || !lyric.hasLyric()) return;
        applyLyric(track, lyric);
    }

    private void applyLyric(MusicTrack track, MusicLyric lyric) {
        if (track != boundTrack) return;
        List<com.fongmi.android.tv.player.lyrics.LyricsLine> main =
                LyricsParser.parseWordLyric(lyric.rawLrc);
        List<com.fongmi.android.tv.player.lyrics.LyricsLine> trans = TextUtils.isEmpty(lyric.translation)
                ? null : LyricsParser.parseTimed(lyric.translation);
        List<com.fongmi.android.tv.player.lyrics.LyricsLine> roma = TextUtils.isEmpty(lyric.romanization)
                ? null : LyricsParser.parseTimed(lyric.romanization);
        lyricSettled = true;
        lyricView.setLyrics(RichLyrics.align(main, trans, roma), false);
    }

    private void onTick() {
        MusicController controller = MusicController.get();
        MusicTrack current = controller.current();
        if (current != null && !MusicQueue.trackKey(current).equals(boundKey)) {
            bindTrack(current);
        }
        if (boundTrack == null) return;
        long position = controller.position();
        long duration = controller.duration();
        lyricView.setPlayback(position, playing);
        progressLine.setProgress(duration > 0 ? position / (float) duration : 0f);
        if (!lyricSettled && lyricWaitTicks < 20) {
            lyricWaitTicks++;
            tryLoadLyricFromCache(boundTrack);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                backSwipeFired = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!backSwipeFired) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (dx > dpI(80) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        backSwipeFired = true;
                        finish();
                    }
                }
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    private int dpI(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onTrackChanged(MusicTrack track) {
        if (isFinishing() || isDestroyed()) return;
        if (track != null) bindTrack(track);
    }

    @Override
    public void onPlayingChanged(boolean isPlaying) {
        if (isFinishing() || isDestroyed()) return;
        playing = isPlaying;
        playPauseView.setImageResource(isPlaying ? R.drawable.ic_gz_pause : R.drawable.ic_gz_play);
    }

    @Override
    public void onProgress(long positionMs, long durationMs) {
    }

    @Override
    public void onQueueChanged() {
        if (isFinishing() || isDestroyed()) return;
        MusicTrack current = MusicController.get().current();
        if (current != null && !MusicQueue.trackKey(current).equals(boundKey)) bindTrack(current);
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
        if (isFinishing() || isDestroyed()) return;
        if (track == boundTrack && !lyricSettled) tryLoadLyricFromCache(track);
    }

    @Override
    public void onLyricReady(MusicTrack track) {
        if (isFinishing() || isDestroyed()) return;
        if (track != boundTrack) return;
        MusicLyric lyric = MusicLyricCache.get(track);
        if (lyric != null && lyric.hasLyric()) {
            applyLyric(track, lyric);
        } else {
            lyricSettled = true;
            lyricView.setLyrics(java.util.Collections.emptyList(), false);
        }
    }

    @Override
    public void onTrackFailed(MusicTrack track, int reasonRes) {
    }

    @Override
    protected void onDestroy() {
        tickHandler.removeCallbacks(tick);
        if (bound) {
            MusicController.get().removeListener(this);
            MusicController.get().unbind();
            bound = false;
        }
        super.onDestroy();
    }

    private static class ProgressLine extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float fraction;

        ProgressLine(Context context) {
            super(context);
        }

        void setProgress(float f) {
            fraction = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(PlayerViews.white(0.18f));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setColor(PlayerViews.white(0.85f));
            canvas.drawRect(0, 0, getWidth() * fraction, getHeight(), paint);
        }
    }
}
