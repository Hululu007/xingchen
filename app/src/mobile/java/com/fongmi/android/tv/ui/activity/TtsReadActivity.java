package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;

import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.read.ReadingSession;
import com.fongmi.android.tv.read.ReadingStore;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.tts.EdgeTtsClient;
import com.fongmi.android.tv.tts.EdgeVoice;
import com.fongmi.android.tv.tts.EdgeVoiceCatalog;
import com.fongmi.android.tv.tts.TtsEngine;
import com.fongmi.android.tv.tts.TtsJob;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 神经朗读播放页（功能线最小面，视觉随后换皮）：
 * 小说阅读器「朗读」入口 → 按段合成（Edge 神经音色）→ 逐段播放。
 * 复用 PlaybackService（后台＋锁屏）＋ PlayerManager；进度记 ReadingStore（kind="TTS"，
 * 与 NOVEL 阅读进度分开记，互不冲）；与音乐播放器分家（只借底层服务）。
 * 失败人话提示，不许回退系统机械声。
 */
public class TtsReadActivity extends Activity
        implements ServiceConnection, TtsEngine.Listener {

    private static final float[] SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f};
    private static final String[] TIMER_ITEMS = {"15 分钟", "30 分钟", "60 分钟", "关闭定时"};

    private String jobId;
    private TtsJob job;
    private ReadingSession rs;
    private TtsEngine engine;
    private PlaybackService mService;
    private boolean bound;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int ci;
    private int si;
    private int segCount;
    private int wantCi = -1;
    private int wantSi = -1;
    private File currentFile;
    private boolean inTrial;
    private int trialResumeCi;
    private int trialResumeSi;

    private EdgeVoice curVoice;
    private List<EdgeVoice> voices = new ArrayList<>();
    private int speedIndex = 1;
    private long sleepAt = 0;
    private int endedGuardCi = -1;
    private int endedGuardSi = -1;
    private boolean ttsDead;
    private boolean closed;
    private boolean released;
    private boolean seeking;

    private TextView tvChapter, tvSeg, tvPos, tvDur, tvSpeed, tvTimer, tvVoice;
    private SeekBar seekBar;
    private TextView btnPlay;

    public static void start(Activity activity, String jobId) {
        Intent i = new Intent(activity, TtsReadActivity.class);
        i.putExtra("job", jobId);
        activity.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        jobId = getIntent().getStringExtra("job");
        job = TtsJob.get(jobId);
        if (job == null || job.chapters.isEmpty()) {
            Toast.makeText(this, "朗读任务已失效", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        buildSession();
        // 断点：同书的 TTS 记录优先于本次传入位置
        ReadingStore.Record r = findRecord();
        if (r != null && r.chapterIndex >= 0 && r.chapterIndex < job.chapters.size()) {
            ci = r.chapterIndex;
            si = Math.max(0, r.segIndex);
        } else {
            ci = Math.max(0, Math.min(job.startChapter, job.chapters.size() - 1));
            si = 0;
        }
        buildUi();
        loadVoices();
        bindPlaybackService();
        handler.post(ticker);
    }

    private void buildSession() {
        rs = new ReadingSession();
        rs.kind = ReadingSession.Kind.NOVEL;
        rs.sourceName = job.sourceName;
        rs.bookTitle = job.bookTitle;
        rs.coverUrl = job.coverUrl;
        rs.siteKey = job.siteKey;
        rs.legadoKey = job.legadoKey;
        rs.ruleTitle = job.ruleTitle;
        rs.localId = job.localId;
        rs.text = job.inlineText;
        rs.chapters.addAll(job.chapters);
    }

    // ================= UI（最小面，随后换皮） =================

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

        TextView tvTitle = new TextView(this);
        tvTitle.setText(job.bookTitle);
        tvTitle.setTextSize(20);
        tvTitle.setTextColor(0xFF222222);
        tvTitle.setPadding(0, dp(24), 0, 0);
        root.addView(tvTitle);

        TextView tvSrc = new TextView(this);
        tvSrc.setText("神经朗读 · " + job.sourceName);
        tvSrc.setTextSize(13);
        tvSrc.setTextColor(0xFF999999);
        tvSrc.setPadding(0, dp(4), 0, 0);
        root.addView(tvSrc);

        tvChapter = new TextView(this);
        tvChapter.setTextSize(16);
        tvChapter.setTextColor(0xFFB8860B);
        tvChapter.setPadding(0, dp(24), 0, 0);
        root.addView(tvChapter);

        tvSeg = new TextView(this);
        tvSeg.setTextSize(13);
        tvSeg.setTextColor(0xFF999999);
        tvSeg.setPadding(0, dp(4), 0, 0);
        root.addView(tvSeg);

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
        prev.setOnClickListener(v -> jumpChapter(-1));
        TextView next = ctrlButton("⏭");
        next.setOnClickListener(v -> jumpChapter(1));
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
        tvVoice = optButton("音色");
        tvVoice.setOnClickListener(v -> pickVoice());
        TextView list = optButton("目录");
        list.setOnClickListener(v -> showToc());
        opts.addView(tvSpeed);
        opts.addView(tvTimer);
        opts.addView(tvVoice);
        opts.addView(list);
        root.addView(opts);

        setContentView(root);
        refreshLabels();
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
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void refreshLabels() {
        if (tvChapter == null) return;
        ReadingSession.Chapter c = job.chapters.get(Math.min(ci, job.chapters.size() - 1));
        tvChapter.setText(c.name);
        tvSeg.setText("第 " + (si + 1) + " 段" + (segCount > 0 ? " / 共 " + segCount + " 段" : ""));
        tvVoice.setText(curVoice == null ? "音色" : curVoice.name);
    }

    // ================= 服务绑定 =================

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
        engine = new TtsEngine(this, job.bookKey(), this::resolveText, this);
        engine.setVoice(job.voiceId, job.rate);
        // 起始段：按传入的阅读万分比估算（确定性切分可重算）
        prepareChapter(ci, si, true);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mService = null;
    }

    private String playbackKey() {
        return "tts:" + job.bookKey().hashCode();
    }

    private final PlaybackService.NavigationCallback navCallback =
            new PlaybackService.NavigationCallback() {
                @Override public void onPrev() { if (!released) jumpChapter(-1); }
                @Override public void onNext() { if (!released) jumpChapter(1); }
                @Override public void onStop() { if (!released) onClose(); }
            };

    private void unregisterNav() {
        try {
            if (mService != null) mService.setNavigationCallback(null, playbackKey());
        } catch (Throwable ignored) {
        }
    }

    // ================= 正文与段 =================

    /** 章正文（worker 线程调）：照小说阅读器同口径懒取。 */
    private String resolveText(int chapterIndex) {
        try {
            rs.chapterIndex = chapterIndex;
            ReadingSession.Chapter ch = rs.chapters.get(chapterIndex);
            return NovelReaderActivity.resolveText(rs, ch);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 准备某章某段（后台线程做正文解析，主线程只发请求）。 */
    private void prepareChapter(int chapter, int seg, boolean fromStart) {
        new Thread(() -> {
            List<String> segs = engine.segmentsOf(chapter);
            if (segs.isEmpty()) {
                runOnUiThread(() -> skipEmptyChapter(chapter, 1));
                return;
            }
            int s = Math.max(0, Math.min(seg, segs.size() - 1));
            if (fromStart && seg == 0 && job.startFraction > 0 && chapter == ci) {
                s = Math.max(0, Math.min(
                        Math.round(job.startFraction / 10000f * segs.size()), segs.size() - 1));
            }
            final int target = s;
            final int count = segs.size();
            runOnUiThread(() -> {
                if (released) return;
                segCount = count;
                wantCi = chapter;
                wantSi = target;
                refreshLabels();
                engine.request(chapter, target);
                if (mService != null && !player().isPlaying()
                        && player().getPlaybackState() != Player.STATE_BUFFERING) {
                    Toast.makeText(this, "正在合成语音…", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void playFile(int chapter, int seg, File file) {
        if (released || mService == null) return;
        ci = chapter;
        si = seg;
        currentFile = file;
        wantCi = chapter;
        wantSi = seg;
        endedGuardCi = -1;
        refreshLabels();
        ReadingSession.Chapter c = job.chapters.get(Math.min(ci, job.chapters.size() - 1));
        MediaMetadata meta = new MediaMetadata.Builder()
                .setTitle(c.name + " · 第" + (si + 1) + "段")
                .setArtist(job.bookTitle + " · 神经朗读")
                .build();
        player().browse(PlaySpec.from(playbackKey(), Uri.fromFile(file).toString(),
                new HashMap<>(), meta));
        saveProgress();
    }

    private void skipEmptyChapter(int chapter, int dir) {
        int next = chapter + dir;
        int guard = 0;
        while (next >= 0 && next < job.chapters.size() && guard++ < 50) {
            final int n = next;
            List<String> segs;
            try {
                segs = engine.segmentsOf(n);
            } catch (Throwable t) {
                segs = new ArrayList<>();
            }
            if (!segs.isEmpty()) {
                if (guard > 1 || dir > 0) {
                    final ReadingSession.Chapter c = job.chapters.get(n);
                    Toast.makeText(this, "「" + c.name + "」没有正文，已跳过",
                            Toast.LENGTH_SHORT).show();
                }
                prepareChapter(n, 0, false);
                return;
            }
            next += dir;
        }
        Toast.makeText(this, "后面没有可读的章节了", Toast.LENGTH_SHORT).show();
    }

    private void jumpChapter(int d) {
        int n = ci + d;
        if (n < 0 || n >= job.chapters.size()) {
            Toast.makeText(this, d > 0 ? "已经是最后一章" : "已经是第一章",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        saveProgress();
        if (mService != null) player().stop();
        new Thread(() -> {
            List<String> segs = engine.segmentsOf(n);
            runOnUiThread(() -> {
                if (segs.isEmpty()) skipEmptyChapter(n, d);
                else prepareChapter(n, 0, false);
            });
        }).start();
    }

    // ================= TtsEngine.Listener（worker 线程回调） =================

    @Override
    public void onSegmentReady(int chapter, int seg, File file) {
        runOnUiThread(() -> {
            if (released) return;
            if (chapter == wantCi && seg == wantSi) playFile(chapter, seg, file);
        });
    }

    @Override
    public void onChapterEmpty(int chapter) {
        // 预取命中空章：推进时由 skipEmptyChapter 处理，这里忽略
    }

    @Override
    public void onTtsError(String humanMsg) {
        runOnUiThread(() -> {
            if (released) return;
            ttsDead = true;
            try {
                if (mService != null) player().pause();
            } catch (Throwable ignored) {
            }
            Toast.makeText(this, humanMsg + "（点播放可重试）", Toast.LENGTH_LONG).show();
        });
    }

    private void toggle() {
        if (mService == null) return;
        if (ttsDead) {
            // 重试：重建引擎，从当前段重来
            ttsDead = false;
            try {
                engine.shutdown();
            } catch (Throwable ignored) {
            }
            engine = new TtsEngine(this, job.bookKey(), this::resolveText, this);
            engine.setVoice(curVoice == null ? job.voiceId : curVoice.id, SPEEDS[speedIndex]);
            prepareChapter(ci, si, false);
            Toast.makeText(this, "正在重连朗读服务…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (player().isPlaying()) player().pause();
        else player().play();
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        float s = SPEEDS[speedIndex];
        String label = String.format(Locale.US, "%.2f", s)
                .replaceAll("0+$", "").replaceAll("\\.$", "");
        tvSpeed.setText(label + "x");
        if (engine != null && curVoice != null) {
            engine.setVoice(curVoice.id, s);
            // 语速走服务端合成：从本段起用新语速重听
            prepareChapter(ci, si, false);
            Toast.makeText(this, "语速 " + label + "x，本段起生效", Toast.LENGTH_SHORT).show();
        }
    }

    private void pickTimer() {
        new AlertDialog.Builder(this).setTitle("定时关闭")
                .setItems(TIMER_ITEMS, (d, which) -> {
                    long[] ms = {15 * 60000L, 30 * 60000L, 60 * 60000L, 0};
                    sleepAt = ms[which] == 0 ? 0 : System.currentTimeMillis() + ms[which];
                    tvTimer.setText(ms[which] == 0 ? "定时关" : "剩" + TIMER_ITEMS[which]);
                }).show();
    }

    private void pickVoice() {
        if (voices.isEmpty()) {
            Toast.makeText(this, "音色列表加载中，稍候再试", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[voices.size()];
        int checked = 0;
        String cur = curVoice == null ? job.voiceId : curVoice.id;
        for (int i = 0; i < voices.size(); i++) {
            EdgeVoice v = voices.get(i);
            items[i] = v.label() + (v.storyteller ? " ★" : "");
            if (v.id.equals(cur)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("选择音色（神经人声）")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    d.dismiss();
                    EdgeVoice v = voices.get(which);
                    curVoice = v;
                    EdgeVoiceCatalog.setDefaultVoice(v.id);
                    tvVoice.setText(v.name);
                    if (engine != null) {
                        engine.setVoice(v.id, SPEEDS[speedIndex]);
                        prepareChapter(ci, si, false);
                        Toast.makeText(this, "已换「" + v.name + "」，本段起生效",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("试听当前音色", (d, w) -> trialVoice())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void trialVoice() {
        final EdgeVoice v = curVoice != null ? curVoice
                : EdgeVoiceCatalog.find(voices, job.voiceId);
        if (v == null) return;
        Toast.makeText(this, "正在合成试听…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                byte[] mp3 = new EdgeTtsClient().synthesize(
                        EdgeVoiceCatalog.TRIAL_TEXT, v.id, SPEEDS[speedIndex]);
                File dir = new File(getCacheDir(), "tts_trial");
                dir.mkdirs();
                File f = new File(dir, "trial.mp3");
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(mp3);
                }
                runOnUiThread(() -> {
                    if (released || mService == null) return;
                    inTrial = true;
                    trialResumeCi = ci;
                    trialResumeSi = si;
                    MediaMetadata meta = new MediaMetadata.Builder()
                            .setTitle("试听 · " + v.name)
                            .setArtist("神经朗读").build();
                    player().browse(PlaySpec.from(playbackKey(),
                            Uri.fromFile(f).toString(), new HashMap<>(), meta));
                });
            } catch (EdgeTtsClient.TtsException e) {
                runOnUiThread(() -> Toast.makeText(TtsReadActivity.this,
                        e.getMessage(), Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(TtsReadActivity.this,
                        EdgeTtsClient.ERR_OFFLINE, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showToc() {
        String[] names = new String[job.chapters.size()];
        for (int i = 0; i < names.length; i++) names[i] = job.chapters.get(i).name;
        new AlertDialog.Builder(this).setTitle(job.bookTitle + "（" + names.length + " 章）")
                .setItems(names, (d, which) -> {
                    saveProgress();
                    if (mService != null) player().stop();
                    prepareChapter(which, 0, false);
                }).show();
    }

    private void loadVoices() {
        curVoice = EdgeVoiceCatalog.find(EdgeVoiceCatalog.builtIn(), job.voiceId);
        if (curVoice == null && !EdgeVoiceCatalog.builtIn().isEmpty()) {
            curVoice = EdgeVoiceCatalog.builtIn().get(0);
        }
        new Thread(() -> {
            List<EdgeVoice> list = EdgeVoiceCatalog.fetchRemote();
            runOnUiThread(() -> {
                if (released) return;
                voices = list;
                EdgeVoice v = EdgeVoiceCatalog.find(list,
                        curVoice == null ? job.voiceId : curVoice.id);
                if (v != null) curVoice = v;
                refreshLabels();
            });
        }).start();
        voices = EdgeVoiceCatalog.builtIn();
    }

    private String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
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
                    if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
                        sleepAt = 0;
                        tvTimer.setText("定时关");
                        player().pause();
                        Toast.makeText(TtsReadActivity.this, "定时到，已暂停",
                                Toast.LENGTH_SHORT).show();
                    }
                    if (p != null && p.getPlaybackState() == Player.STATE_ENDED
                            && !(endedGuardCi == ci && endedGuardSi == si)) {
                        endedGuardCi = ci;
                        endedGuardSi = si;
                        if (inTrial) {
                            inTrial = false;
                            prepareChapter(trialResumeCi, trialResumeSi, false);
                        } else if (si + 1 < segCount) {
                            prepareChapter(ci, si + 1, false);
                        } else {
                            saveProgress();
                            skipEmptyChapter(ci, 1);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, 500);
        }
    };

    // ================= 进度（kind="TTS"，与阅读进度分开） =================

    private ReadingStore.Record findRecord() {
        try {
            for (ReadingStore.Record r : ReadingStore.list()) {
                if ("TTS".equals(r.kind) && job.sourceName.equals(r.sourceName)
                        && job.bookTitle.equals(r.bookTitle)) return r;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void saveProgress() {
        try {
            ReadingSession.Chapter c = job.chapters.get(Math.min(ci, job.chapters.size() - 1));
            ReadingStore.Record r = findRecord();
            if (r == null) r = new ReadingStore.Record();
            r.kind = "TTS";
            r.sourceName = job.sourceName;
            r.bookTitle = job.bookTitle;
            r.coverUrl = job.coverUrl;
            r.chapterName = c.name;
            r.chapterIndex = ci;
            r.segIndex = si;
            r.chapterUrl = c.url;
            r.siteKey = job.siteKey;
            r.legadoKey = job.legadoKey;
            r.ruleTitle = job.ruleTitle;
            r.localId = job.localId;
            r.origin = TextUtils.isEmpty(job.localId) ? "" : "local";
            r.position = mService == null ? 0 : (int) player().getPosition();
            r.updatedAt = System.currentTimeMillis();
            ReadingStore.save(r);
        } catch (Throwable ignored) {
        }
    }

    private void onClose() {
        closed = true;
        TtsJob.remove(jobId);
        saveProgress();
        try {
            if (engine != null) engine.shutdown();
        } catch (Throwable ignored) {
        }
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
        // 页面销毁即停合成引擎（防泄漏）；当前段在后台服务里播到段尾。
        // 用户点关闭才 stop 播放＋注销锁屏回调（与 AudioBookActivity 同口径）。
        try {
            if (engine != null) engine.shutdown();
        } catch (Throwable ignored) {
        }
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
