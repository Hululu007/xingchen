package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;

import com.fongmi.android.tv.legado.LegadoAudio;
import com.fongmi.android.tv.legado.LegadoException;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.read.ReadingStore;
import com.fongmi.android.tv.service.PlaybackService;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 听书播放页（阶段二·甲最小面）：
 * type 1 音频源出口。复用现有链——PlaybackService（后台播放＋锁屏控制）＋
 * PlayerManager（倍速/进度）。断点续播/书架复用 ReadingStore。精修稿另案。
 */
public class AudioBookActivity extends Activity implements ServiceConnection {

    private static final float[] SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

    private LegadoAudio.AudioAlbum album;
    private PlaybackService mService;
    private boolean bound;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvTitle, tvChapter, tvPos, tvDur, tvSpeed, tvTimer;
    private SeekBar seekBar;
    private TextView btnPlay;
    private boolean seeking;
    private int speedIndex = 1;
    private long sleepAt = 0;
    private int endedGuard = -1;
    private long resumeMs = 0;
    /** 用户点了关闭（真结束）；released=页面已销毁（后台可能还在播）。 */
    private boolean closed;
    private boolean released;

    public static void start(Activity activity, String sessionId) {
        Intent i = new Intent(activity, AudioBookActivity.class);
        i.putExtra("session", sessionId);
        activity.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        album = LegadoAudio.take(getIntent().getStringExtra("session"));
        if (album == null || album.chapters.isEmpty()) {
            Toast.makeText(this, "专辑数据丢失", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        buildUi();
        // 续播：同源同书的 AUDIO 记录带回位置
        ReadingStore.Record r = findRecord();
        if (r != null && r.chapterIndex >= 0 && r.chapterIndex < album.chapters.size()) {
            album.index = r.chapterIndex;
            resumeMs = Math.max(0, r.position);
        }
        bindPlaybackService();
        handler.post(ticker);
    }

    // ================= UI（最小面） =================

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F3EF);
        root.setPadding(dp(20), dp(48), dp(20), dp(24));

        TextView close = new TextView(this);
        close.setText("✕ 关闭");
        close.setTextSize(14);
        close.setTextColor(0xFF999999);
        close.setGravity(Gravity.END);
        close.setPadding(0, 0, 0, dp(8));
        close.setOnClickListener(v -> onClose());
        root.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvTitle = new TextView(this);
        tvTitle.setText(album.title);
        tvTitle.setTextSize(20);
        tvTitle.setTextColor(0xFF222222);
        tvTitle.setPadding(0, dp(24), 0, 0);
        root.addView(tvTitle);

        TextView tvSrc = new TextView(this);
        tvSrc.setText(album.sourceName + " · 听书");
        tvSrc.setTextSize(13);
        tvSrc.setTextColor(0xFF999999);
        tvSrc.setPadding(0, dp(4), 0, 0);
        root.addView(tvSrc);

        tvChapter = new TextView(this);
        tvChapter.setTextSize(16);
        tvChapter.setTextColor(0xFFB8860B);
        tvChapter.setPadding(0, dp(24), 0, 0);
        root.addView(tvChapter);

        seekBar = new SeekBar(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(24);
        root.addView(seekBar, slp);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && mService != null) player().seekTo(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { seeking = false; }
        });

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        tvPos = timeLabel();
        tvDur = timeLabel();
        tvDur.setGravity(Gravity.END);
        times.addView(tvPos, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        times.addView(tvDur, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(times);

        LinearLayout ctrls = new LinearLayout(this);
        ctrls.setOrientation(LinearLayout.HORIZONTAL);
        ctrls.setGravity(Gravity.CENTER);
        ctrls.setPadding(0, dp(24), 0, 0);

        TextView prev = ctrlButton("⏮");
        prev.setOnClickListener(v -> playDelta(-1));
        TextView next = ctrlButton("⏭");
        next.setOnClickListener(v -> playDelta(1));
        btnPlay = ctrlButton("⏸");
        btnPlay.setOnClickListener(v -> toggle());
        ctrls.addView(prev);
        ctrls.addView(btnPlay);
        ctrls.addView(next);
        root.addView(ctrls);

        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.HORIZONTAL);
        opts.setGravity(Gravity.CENTER);
        opts.setPadding(0, dp(20), 0, 0);

        tvSpeed = optButton("1.0x");
        tvSpeed.setOnClickListener(v -> cycleSpeed());
        tvTimer = optButton("定时关");
        tvTimer.setOnClickListener(v -> pickTimer());
        TextView list = optButton("目录");
        list.setOnClickListener(v -> showToc());
        opts.addView(tvSpeed);
        opts.addView(tvTimer);
        opts.addView(list);
        root.addView(opts);

        setContentView(root);
        refreshChapterLabel();
    }

    private TextView timeLabel() {
        TextView t = new TextView(this);
        t.setText("00:00");
        t.setTextSize(12);
        t.setTextColor(0xFF999999);
        return t;
    }

    private TextView ctrlButton(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(30);
        t.setTextColor(0xFF333333);
        t.setPadding(dp(20), dp(8), dp(20), dp(8));
        return t;
    }

    private TextView optButton(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(0xFFB8860B);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ================= 服务绑定（复用 PlaybackService） =================

    private void bindPlaybackService() {
        if (bound) return;
        startService(new Intent(this, PlaybackService.class));
        bindService(new Intent(this, PlaybackService.class)
                .setAction(PlaybackService.LOCAL_BIND_ACTION), this, BIND_AUTO_CREATE);
        bound = true;
    }

    private PlayerManager player() {
        return mService.player();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        mService = ((PlaybackService.LocalBinder) binder).getService();
        mService.setPlaybackForeground(true);
        mService.setNavigationCallback(navCallback, playbackKey());
        playChapter(album.index, resumeMs);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mService = null;
    }

    private String playbackKey() {
        return "audiobook:" + album.legadoKey.hashCode() + ":" + album.title.hashCode();
    }

    private final PlaybackService.NavigationCallback navCallback = new PlaybackService.NavigationCallback() {
        @Override public void onPrev() { if (!released) playDelta(-1); }
        @Override public void onNext() { if (!released) playDelta(1); }
        @Override public void onStop() { if (!released) onClose(); }
    };

    /** 注销锁屏回调：只在听书真结束（用户关闭）时调；后台续播时保留。 */
    private void unregisterNav() {
        try {
            if (mService != null) mService.setNavigationCallback(null, playbackKey());
        } catch (Throwable ignored) {
        }
    }

    // ================= 播放 =================

    private void playDelta(int d) {
        int n = album.index + d;
        if (n < 0 || n >= album.chapters.size()) {
            Toast.makeText(this, d > 0 ? "已经是最后一集" : "已经是第一集",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        saveProgress();
        playChapter(n, 0);
    }

    private void playChapter(int index, long resume) {
        album.index = index;
        endedGuard = -1;
        refreshChapterLabel();
        new Thread(() -> {
            String url;
            try {
                LegadoAudio.Chapter c = album.current();
                com.fongmi.android.tv.legado.LegadoSource src =
                        com.fongmi.android.tv.legado.LegadoStore.get(album.legadoKey);
                url = LegadoAudio.fetchAudioUrl(src, c.url, null);
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    String m = t instanceof LegadoException ? t.getMessage() : "音频地址解析失败";
                    Toast.makeText(AudioBookActivity.this, "源级错误：" + m,
                            Toast.LENGTH_LONG).show();
                });
                return;
            }
            final String audioUrl = url;
            runOnUiThread(() -> {
                if (released || mService == null) return;
                LegadoAudio.Chapter c = album.current();
                MediaMetadata meta = new MediaMetadata.Builder()
                        .setTitle(c.name)
                        .setArtist(album.title)
                        .setArtworkUri(album.coverUrl.isEmpty() ? null
                                : Uri.parse(album.coverUrl))
                        .build();
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/120.0 Mobile Safari/537.36");
                player().browse(PlaySpec.from(playbackKey(), audioUrl, headers, meta));
                if (resume > 0) player().seekTo(resume);
            });
        }).start();
    }

    private void toggle() {
        if (mService == null) return;
        if (player().isPlaying()) player().pause();
        else player().play();
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        float s = SPEEDS[speedIndex];
        if (mService != null) player().setSpeed(s);
        String label = String.format(Locale.US, "%.2f", s)
                .replaceAll("0+$", "").replaceAll("\\.$", "");
        tvSpeed.setText(label + "x");
        Toast.makeText(this, "倍速 " + tvSpeed.getText(), Toast.LENGTH_SHORT).show();
    }

    private void pickTimer() {
        String[] items = {"15 分钟", "30 分钟", "60 分钟", "关闭定时"};
        new AlertDialog.Builder(this).setTitle("定时关闭")
                .setItems(items, (d, which) -> {
                    long[] ms = {15 * 60000L, 30 * 60000L, 60 * 60000L, 0};
                    sleepAt = ms[which] == 0 ? 0 : System.currentTimeMillis() + ms[which];
                    tvTimer.setText(ms[which] == 0 ? "定时关"
                            : "剩" + items[which]);
                    if (ms[which] == 0) Toast.makeText(this, "已关闭定时",
                            Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showToc() {
        String[] names = new String[album.chapters.size()];
        for (int i = 0; i < names.length; i++) names[i] = album.chapters.get(i).name;
        new AlertDialog.Builder(this).setTitle(album.title + "（" + names.length + " 集）")
                .setItems(names, (d, which) -> {
                    saveProgress();
                    playChapter(which, 0);
                }).show();
    }

    private void refreshChapterLabel() {
        LegadoAudio.Chapter c = album.current();
        if (c != null && tvChapter != null) {
            tvChapter.setText("第 " + (album.index + 1) + " / "
                    + album.chapters.size() + " 集 · " + c.name);
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            try {
                if (mService != null) {
                    Player p = player().getPlayer();
                    long pos = player().getPosition();
                    long dur = player().getDuration();
                    if (!seeking && dur > 0) {
                        seekBar.setMax((int) dur);
                        seekBar.setProgress((int) Math.min(pos, dur));
                    }
                    tvPos.setText(fmt(pos));
                    tvDur.setText(fmt(dur));
                    btnPlay.setText(player().isPlaying() ? "⏸" : "▶");
                    // 睡眠定时
                    if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
                        sleepAt = 0;
                        tvTimer.setText("定时关");
                        player().pause();
                        Toast.makeText(AudioBookActivity.this, "定时到，已暂停",
                                Toast.LENGTH_SHORT).show();
                    }
                    // 本集播完自动下一集
                    if (p != null && p.getPlaybackState() == Player.STATE_ENDED
                            && endedGuard != album.index) {
                        endedGuard = album.index;
                        if (album.index + 1 < album.chapters.size()) {
                            saveProgress();
                            playChapter(album.index + 1, 0);
                        } else {
                            Toast.makeText(AudioBookActivity.this, "专辑播完",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, 500);
        }
    };

    private String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    // ================= 进度/书架（复用 ReadingStore） =================

    private ReadingStore.Record findRecord() {
        try {
            for (ReadingStore.Record r : ReadingStore.list()) {
                if ("AUDIO".equals(r.kind) && album.legadoKey.equals(r.legadoKey)
                        && album.title.equals(r.bookTitle)) return r;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void saveProgress() {
        try {
            LegadoAudio.Chapter c = album.current();
            if (c == null) return;
            ReadingStore.Record r = findRecord();
            if (r == null) r = new ReadingStore.Record();
            r.kind = "AUDIO";
            r.sourceName = album.sourceName;
            r.bookTitle = album.title;
            r.coverUrl = album.coverUrl;
            r.chapterName = c.name;
            r.chapterIndex = album.index;
            r.chapterUrl = c.url;
            r.legadoKey = album.legadoKey;
            r.position = mService == null ? 0 : (int) player().getPosition();
            r.updatedAt = System.currentTimeMillis();
            ReadingStore.save(r);
        } catch (Throwable ignored) {
        }
    }

    private void onClose() {
        closed = true;
        saveProgress();
        try {
            if (mService != null) player().stop();
        } catch (Throwable ignored) {
        }
        unregisterNav();
        finish();
    }

    @Override
    protected void onDestroy() {
        released = true;
        saveProgress();
        handler.removeCallbacks(ticker);
        // 后台续播：页面销毁但播放继续，保留锁屏回调（服务 tryShutdown 会因此放行，
        // 且服务层 saveProgress 有 audiobook: 隔离，不会污染点播历史）。
        // 用户点了关闭才注销回调、放行服务回收。
        if (closed) unregisterNav();
        if (bound) {
            try {
                unbindService(this);
            } catch (Throwable ignored) {
            }
            bound = false;
        }
        mService = null;
        super.onDestroy();
    }
}
