package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityKtvSingBinding;
import com.fongmi.android.tv.ktv.KtvLyrics;
import com.fongmi.android.tv.ktv.KtvServer;
import com.fongmi.android.tv.ktv.KtvSong;
import com.fongmi.android.tv.ktv.KtvStore;
import com.fongmi.android.tv.ktv.MvFinder;
import com.fongmi.android.tv.ktv.VocalProcessor;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.gson.Gson;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 唱歌页（独立全屏页，横竖跟 MV 走）：MV 打底、歌词双行滚动、
 * 原唱/伴奏（轨/版/声道）/消音三态、升降调、麦克风返听+录音、唱完自动接下一首。
 * 用自己的 Exo 播放器（为了串消音处理链），不占看片的播放器。
 */
public class KtvSingActivity extends BaseActivity {

    private static final int YELLOW = 0xFFFFD34D;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0xB3FFFFFF;

    private static final int S_ORIGINAL = 0;
    private static final int S_ACC_TRACK = 1;
    private static final int S_ACC_URL = 2;
    private static final int S_CH_LEFT = 3;
    private static final int S_CH_RIGHT = 4;
    private static final int S_CANCEL = 5;

    public static final String EXTRA_SONG = "ktv_song_json";

    private ActivityKtvSingBinding binding;
    private ExoPlayer player;
    private PlayerView playerView;
    private ImageView coverView;
    private FrameLayout stageBox;
    private LinearLayout lyricBox;
    private LinearLayout portraitCol;
    private FrameLayout landscapeFrame;
    private volatile String siteLrc;
    private boolean currentConsumed;
    private boolean modeTouched;
    private String mvUrl;
    private boolean onMv;
    private int mvSeq;
    private long lyricOffsetMs;
    private TextView offsetLabel;
    private final VocalProcessor vocal = new VocalProcessor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView titleText;
    private TextView nextText;
    private TextView linePrev;
    private TextView lineCur;
    private TextView lineNext;
    private TextView modeBtn;
    private TextView pitchLabel;
    private TextView micBtn;
    private TextView playBtn;
    private SeekBar seekBar;
    private TextView timeText;
    private LinearLayout topBar;
    private LinearLayout controlBar;
    private boolean controlsShown = true;
    private boolean seeking;

    private KtvSong current;
    private String mainUrl = "";
    private Map<String, String> mainHeaders;
    private String accUrl = "";
    private Map<String, String> accHeaders;
    private boolean onAccUrl;
    private final List<Integer> states = new ArrayList<>();
    private int stateIdx;
    private int pitchSemi;
    private KtvLyrics.Result lyrics;
    private int curLine = -1;
    private boolean rotateAuto = true;
    private Mic mic;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, KtvSingActivity.class));
    }

    public static void startWith(Activity activity, KtvSong song) {
        Intent i = new Intent(activity, KtvSingActivity.class);
        i.putExtra(EXTRA_SONG, new Gson().toJson(song));
        activity.startActivity(i);
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityKtvSingBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        hideBars();
        buildUi();
        buildPlayer();
        String json = getIntent().getStringExtra(EXTRA_SONG);
        if (json != null && !json.isEmpty()) {
            try {
                current = new Gson().fromJson(json, KtvSong.class);
            } catch (Throwable ignored) {
            }
        }
        if (current == null) current = KtvStore.pollQueue();
        if (current == null) {
            toast("队列是空的");
            finish();
            return;
        }
        playCurrent();
        ui.post(ticker);
        ui.postDelayed(hideRunnable, 4000);
    }

    private void hideBars() {
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.hide(WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    // ================= 界面 =================

    private void buildUi() {
        FrameLayout root = binding.singRoot;
        portraitCol = new LinearLayout(this);
        portraitCol.setOrientation(LinearLayout.VERTICAL);
        portraitCol.setBackgroundColor(0xFF000000);
        landscapeFrame = new FrameLayout(this);
        root.addView(portraitCol, new FrameLayout.LayoutParams(-1, -1));
        root.addView(landscapeFrame, new FrameLayout.LayoutParams(-1, -1));

        stageBox = new FrameLayout(this);
        stageBox.setBackgroundColor(0xFF000000);
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setOnClickListener(v -> toggleControls());
        stageBox.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        coverView = new ImageView(this);
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverView.setAlpha(0.45f);
        coverView.setVisibility(View.GONE);
        stageBox.addView(coverView, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(18), dp(12) + statusBarHeight(), dp(18), dp(12));
        topBar.setBackground(scrim(0x99000000));
        titleText = text("", 16, WHITE);
        topBar.addView(titleText, new LinearLayout.LayoutParams(0, -2, 1f));
        nextText = text("", 12, DIM);
        topBar.addView(nextText);

        lyricBox = new LinearLayout(this);
        lyricBox.setOrientation(LinearLayout.VERTICAL);
        lyricBox.setGravity(Gravity.CENTER);
        int lz = KtvStore.lyricSize();
        float lfs = lz == 1 ? 1.2f : lz == 2 ? 1.45f : 1f;
        linePrev = lyricText((int) (17 * lfs), DIM);
        lineCur = lyricText((int) (30 * lfs), WHITE);
        lineCur.setTypeface(lineCur.getTypeface(), android.graphics.Typeface.BOLD);
        lineNext = lyricText((int) (17 * lfs), DIM);
        lyricBox.addView(linePrev);
        lyricBox.addView(lineCur);
        lyricBox.addView(lineNext);


        controlBar = new LinearLayout(this);
        controlBar.setOrientation(LinearLayout.VERTICAL);
        controlBar.setPadding(dp(18), dp(8), dp(18), dp(14) + navBarHeight());
        controlBar.setBackground(scrim(0x99000000));
        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                seeking = false;
                if (player != null && player.getDuration() > 0) {
                    player.seekTo(player.getDuration() * sb.getProgress() / 1000);
                }
                pokeControls();
            }
        });
        seekRow.addView(seekBar, new LinearLayout.LayoutParams(0, -2, 1f));
        timeText = text("0:00/0:00", 12, DIM);
        seekRow.addView(timeText);
        controlBar.addView(seekRow);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        playBtn = btn("❚❚", v -> togglePlay());
        addBtn(row, playBtn);
        addBtn(row, btn("重唱", v -> {
            if (player != null) {
                player.seekTo(0);
                player.play();
            }
            pokeControls();
        }));
        addBtn(row, btn("切歌", v -> nextSong()));
        modeBtn = btn("原唱", v -> cycleMode());
        addBtn(row, modeBtn);
        addBtn(row, btn("队列", v -> showQueue()));
        addBtn(row, btn("退出", v -> finish()));
        controlBar.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER);
        row2.setPadding(0, dp(8), 0, 0);
        addBtn(row2, btn("调−", v -> setPitch(pitchSemi - 1)));
        pitchLabel = text("调 0", 13, WHITE);
        pitchLabel.setGravity(Gravity.CENTER);
        addBtn(row2, pitchLabel);
        addBtn(row2, btn("调＋", v -> setPitch(pitchSemi + 1)));
        micBtn = btn("麦克风", v -> toggleMic());
        addBtn(row2, micBtn);
        addBtn(row2, btn("词延−", v -> setLyricOffset(lyricOffsetMs - 500)));
        offsetLabel = text("词延 0.0", 13, WHITE);
        offsetLabel.setGravity(Gravity.CENTER);
        addBtn(row2, offsetLabel);
        addBtn(row2, btn("词延＋", v -> setLyricOffset(lyricOffsetMs + 500)));
        controlBar.addView(row2);
        arrangeLayout();
    }

    private static void detach(View v) {
        if (v != null && v.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) v.getParent()).removeView(v);
        }
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    /** 竖屏上下分块（上面半屏画面、下面歌词）；横屏不做特殊全屏，页面铺满即可、控件浮在上面。 */
    private void arrangeLayout() {
        if (stageBox == null) return;
        detach(stageBox);
        detach(topBar);
        detach(lyricBox);
        detach(controlBar);
        if (isLandscape()) {
            portraitCol.setVisibility(View.GONE);
            landscapeFrame.setVisibility(View.VISIBLE);
            landscapeFrame.addView(stageBox, new FrameLayout.LayoutParams(-1, -1));
            landscapeFrame.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            llp.bottomMargin = dp(150);
            llp.leftMargin = dp(24);
            llp.rightMargin = dp(24);
            landscapeFrame.addView(lyricBox, llp);
            landscapeFrame.addView(controlBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            topBar.setVisibility(controlsShown ? View.VISIBLE : View.GONE);
            controlBar.setVisibility(controlsShown ? View.VISIBLE : View.GONE);
        } else {
            landscapeFrame.setVisibility(View.GONE);
            portraitCol.setVisibility(View.VISIBLE);
            portraitCol.addView(topBar, new LinearLayout.LayoutParams(-1, -2));
            portraitCol.addView(stageBox, new LinearLayout.LayoutParams(-1, 0, 1f));
            portraitCol.addView(lyricBox, new LinearLayout.LayoutParams(-1, 0, 1f));
            portraitCol.addView(controlBar, new LinearLayout.LayoutParams(-1, -2));
            controlsShown = true;
            ui.removeCallbacks(hideRunnable);
            topBar.setVisibility(View.VISIBLE);
            controlBar.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        arrangeLayout();
    }

    private final Runnable hideRunnable = () -> {
        if (controlsShown) {
            controlsShown = false;
            topBar.setVisibility(View.GONE);
            controlBar.setVisibility(View.GONE);
        }
    };

    private void toggleControls() {
        if (!isLandscape()) {
            controlsShown = true;
            topBar.setVisibility(View.VISIBLE);
            controlBar.setVisibility(View.VISIBLE);
            return;
        }
        controlsShown = !controlsShown;
        topBar.setVisibility(controlsShown ? View.VISIBLE : View.GONE);
        controlBar.setVisibility(controlsShown ? View.VISIBLE : View.GONE);
        pokeControls();
    }

    private void pokeControls() {
        ui.removeCallbacks(hideRunnable);
        if (controlsShown && isLandscape()) ui.postDelayed(hideRunnable, 4000);
    }

    // ================= 播放器 =================

    private void buildPlayer() {
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this) {
            @Override
            protected AudioSink buildAudioSink(@NonNull Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams) {
                return new DefaultAudioSink.Builder(context)
                        .setAudioProcessors(new AudioProcessor[]{vocal})
                        .build();
            }
        };
        player = new ExoPlayer.Builder(this).setRenderersFactory(rf).build();
        vocal.setCancelStrength(KtvStore.cancelStrength());
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) nextSong();
                if (state == Player.STATE_READY) {
                    refreshStates();
                    maybeSwapToMv();
                    coverView.setVisibility(player.getVideoFormat() == null && current != null && current.pic != null && !current.pic.isEmpty() ? View.VISIBLE : View.GONE);
                }
            }

            @Override
            public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
                if (onMv && mainUrl != null && !mainUrl.isEmpty()) {
                    onMv = false;
                    mvUrl = null;
                    long pos = 0;
                    try {
                        pos = player.getCurrentPosition();
                    } catch (Throwable ignored) {
                    }
                    playMedia(mainUrl, mainHeaders, pos);
                    toast("MV 播不了，回到音频");
                    return;
                }
                toast("这首播不了，下一首");
                nextSong();
            }

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                if (!rotateAuto || videoSize.width <= 0 || videoSize.height <= 0) return;
                float r = (float) videoSize.width / videoSize.height;
                if (r > 1.2f) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                else if (r < 0.83f) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            }

            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                refreshStates();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (playBtn != null) playBtn.setText(isPlaying ? "❚❚" : "▶");
            }
        });
    }

    private void playMedia(String url, Map<String, String> headers, long startMs) {
        DefaultHttpDataSource.Factory dsf = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true);
        if (headers != null && !headers.isEmpty()) dsf.setDefaultRequestProperties(headers);
        MediaItem item = MediaItem.fromUri(url);
        player.setMediaSource(new DefaultMediaSourceFactory(dsf).createMediaSource(item));
        if (startMs > 0) player.seekTo(startMs);
        player.prepare();
        player.play();
    }

    // ================= 一首歌的生命周期 =================

    private void playCurrent() {
        KtvSong song = current;
        String tt = song.title();
        titleText.setText(song.singer == null || song.singer.isEmpty() || tt.contains(song.singer) ? tt : tt + " - " + song.singer);
        siteLrc = null;
        modeTouched = false;
        currentConsumed = false;
        if (song.pic != null && !song.pic.isEmpty()) {
            ImgUtil.load(song.title(), song.pic, coverView);
            coverView.setVisibility(View.VISIBLE);
        } else {
            coverView.setVisibility(View.GONE);
        }
        updateNextHint();
        KtvStore.addHistory(song);
        lyrics = null;
        curLine = -1;
        linePrev.setText("");
        lineCur.setText("正在准备…");
        lineNext.setText("");
        accUrl = "";
        accHeaders = null;
        onAccUrl = false;
        mvUrl = null;
        onMv = false;
        states.clear();
        states.add(S_ORIGINAL);
        states.add(S_CANCEL);
        stateIdx = 0;
        applyStateLabel();
        setPitch(0);
        vocal.setMode(VocalProcessor.OFF);
        Task.execute(() -> resolveAndPlay(song));
        Task.execute(() -> fetchLyrics(song));
        if (!song.isFromServer()) Task.execute(() -> pairAccompaniment(song));
        final int mseq = ++mvSeq;
        if (!song.isFromServer() && KtvStore.pairMv()) {
            MvFinder.find(song.title(), song.singer, url -> ui.post(() -> {
                if (mseq != mvSeq || url == null || url.isEmpty()) return;
                mvUrl = url;
                maybeSwapToMv();
            }));
        }
    }

    private void resolveAndPlay(KtvSong song) {
        try {
            if (song.isFromServer()) {
                KtvServer.ResolveResult r = new KtvServer(KtvStore.baseUrl()).resolve(song.number);
                if (!r.ok) {
                    runOnUiThread(() -> {
                        toast(r.encrypted ? "这首的曲库是加密的，唱不了" : "没拿到播放地址：" + r.error);
                        nextSong();
                    });
                    return;
                }
                mainUrl = r.url;
                mainHeaders = null;
            } else {
                Result r = SiteApi.playerContent(song.siteKey, song.flag, song.episodeUrl);
                mainUrl = r.getUrl() == null ? "" : r.getUrl().v();
                mainHeaders = r.getHeader();
                siteLrc = r.getDesc() == null ? "" : r.getDesc();
                if (mainUrl == null || mainUrl.isEmpty()) throw new IllegalStateException("源没给地址");
            }
            runOnUiThread(() -> {
                if (current == song) playMedia(mainUrl, mainHeaders, 0);
            });
        } catch (Throwable t) {
            runOnUiThread(() -> {
                toast("没拿到播放地址");
                nextSong();
            });
        }
    }

    private void fetchLyrics(KtvSong song) {
        KtvLyrics.Result r = null;
        if (!song.isFromServer()) {
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline && current == song) {
                String d = siteLrc;
                if (d != null) {
                    if (!d.isEmpty()) {
                        java.util.List<KtvLyrics.Line> ls = KtvLyrics.parseLrc(d);
                        if (ls.size() < 3) ls = KtvLyrics.parseEnhanced(d);
                        if (ls.size() >= 3) {
                            r = new KtvLyrics.Result();
                            r.lines = ls;
                        }
                    }
                    break;
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        if (r == null) r = KtvLyrics.fetch(cleanTitle(song), song.singer, 0);
        KtvLyrics.Result use = r;
        runOnUiThread(() -> {
            if (current != song) return;
            lyrics = use;
            curLine = -1;
            if (use == null || use.lines.isEmpty()) {
                linePrev.setText("");
                lineCur.setText("没找到歌词");
                lineNext.setText("");
            }
        });
    }

    private static String cleanTitle(KtvSong song) {
        String t = song.title() == null ? "" : song.title().trim();
        String s = song.singer == null ? "" : song.singer.trim();
        if (!s.isEmpty() && t.contains(" - ")) {
            StringBuilder sb = new StringBuilder();
            for (String part : t.split(" - ")) {
                if (part.trim().equals(s)) continue;
                if (sb.length() > 0) sb.append(" - ");
                sb.append(part.trim());
            }
            if (sb.length() > 0) t = sb.toString();
        }
        return t;
    }

    /** 给点播源的歌自动配伴奏：同源搜「歌名 伴奏」，对上就把地址备好。 */
    private void pairAccompaniment(KtvSong song) {
        try {
            Site site = null;
            for (Site s : VodConfig.get().getSites()) if (s.getKey() != null && s.getKey().equals(song.siteKey)) site = s;
            if (site == null) return;
            String base = baseName(cleanTitle(song));
            Result sr = SiteApi.searchContent(site, base + " 伴奏", false, "1");
            java.util.List<Vod> vods = new java.util.ArrayList<>(sr.getList());
            // 名字带伴奏的结果优先看，其余只扫前几个——原来三十个挨个拉详情，配上时黄花菜都凉了
            vods.sort((a, b) -> Boolean.compare(!(b.getName() != null && b.getName().contains("伴奏")), !(a.getName() != null && a.getName().contains("伴奏"))));
            int scanned = 0;
            for (Vod v : vods) {
                boolean accVod = v.getName() != null && v.getName().contains("伴奏");
                if (!accVod && scanned >= 5) break;
                if (!accVod) scanned++;
                Result dr = SiteApi.detailContent(site.getKey(), v.getId());
                Vod full = dr.getVod();
                if (full.getFlags().isEmpty()) continue;
                Flag f = full.getFlags().get(0);
                for (Episode ep : f.getEpisodes()) {
                    String en = ep.getName() == null ? "" : ep.getName();
                    if (en.contains("伴奏") && !isInstrumentCover(en) && baseName(en).contains(base)) {
                        Result pr = SiteApi.playerContent(site.getKey(), f.getFlag(), ep.getUrl());
                        String u = pr.getUrl() == null ? "" : pr.getUrl().v();
                        if (u != null && !u.isEmpty()) {
                            accUrl = u;
                            accHeaders = pr.getHeader();
                            runOnUiThread(this::refreshStates);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isInstrumentCover(String name) {
        // 古筝/葫芦丝这类是乐器翻奏，不是这首歌的伴奏，配上也不能唱
        String[] marks = {"古筝", "葫芦丝", "二胡", "唢呐", "钢琴版", "吉他弹唱", "尤克里里", "洞箫", "笛子版"};
        for (String m : marks) if (name.contains(m)) return true;
        return false;
    }

    private static String baseName(String name) {
        if (name == null) return "";
        return name.replaceAll("(?i)mv|伴奏|原唱|视频|黑白灰|\\(|\\)|（|）|\\[|\\]|-|_", " ").trim().replaceAll("\\s+", " ");
    }

    private void nextSong() {
        currentConsumed = true;
        KtvSong next = KtvStore.pollQueue();
        if (next == null) {
            toast("唱完了，队列空了");
            ui.postDelayed(this::finishIfAlive, 1200);
            return;
        }
        current = next;
        playCurrent();
    }

    private void finishIfAlive() {
        if (!isFinishing()) finish();
    }

    private void updateNextHint() {
        List<KtvSong> q = KtvStore.queue();
        nextText.setText(q.isEmpty() ? "" : "下一首：" + q.get(0).title());
    }

    // ================= 原唱/伴奏/消音 =================

    private int audioTrackCount() {
        if (player == null) return 0;
        int n = 0;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == C.TRACK_TYPE_AUDIO) n += g.length;
        }
        return n;
    }

    private void refreshStates() {
        if (current == null) return;
        List<Integer> want = new ArrayList<>();
        want.add(S_ORIGINAL);
        if (audioTrackCount() >= 2) want.add(S_ACC_TRACK);
        if (accUrl != null && !accUrl.isEmpty()) want.add(S_ACC_URL);
        if (current.isFromServer() && current.mtvOrVcd != null
                && current.mtvOrVcd.toUpperCase(Locale.ROOT).contains("VCD")) {
            want.add(S_CH_LEFT);
            want.add(S_CH_RIGHT);
        }
        want.add(S_CANCEL);
        int cur = states.isEmpty() ? S_ORIGINAL : states.get(stateIdx);
        states.clear();
        states.addAll(want);
        stateIdx = Math.max(0, states.indexOf(cur));
        if (!modeTouched && KtvStore.defaultAcc()) {
            int ai = states.indexOf(S_ACC_URL);
            if (ai < 0) ai = states.indexOf(S_ACC_TRACK);
            if (ai >= 0 && ai != stateIdx) {
                stateIdx = ai;
                applyState(states.get(stateIdx));
                return;
            }
        }
        applyStateLabel();
    }

    private void cycleMode() {
        modeTouched = true;
        if (states.isEmpty()) return;
        stateIdx = (stateIdx + 1) % states.size();
        applyState(states.get(stateIdx));
        pokeControls();
    }

    /** 配到了 MV 且当前歌没画面、正在原唱状态，就整首换成 MV 播（与换伴奏地址同路数）。 */
    private void maybeSwapToMv() {
        if (mvUrl == null || onMv || player == null) return;
        if (states.isEmpty() || states.get(stateIdx) != S_ORIGINAL) return;
        int ps = player.getPlaybackState();
        if (ps == Player.STATE_IDLE || ps == Player.STATE_ENDED) return;
        if (player.getVideoFormat() != null) return;
        onMv = true;
        playMedia(mvUrl, null, player.getCurrentPosition());
        toast("配到了 MV");
    }

    private void applyState(int state) {
        // 先全复位
        vocal.setMode(VocalProcessor.OFF);
        if (player != null) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverrides().build());
            if (onAccUrl && state != S_ACC_URL && mainUrl != null && !mainUrl.isEmpty()) {
                long pos = player.getCurrentPosition();
                onAccUrl = false;
                playMedia(mainUrl, mainHeaders, pos);
            }
        }
        switch (state) {
            case S_ACC_TRACK:
                selectSecondAudioTrack();
                break;
            case S_ACC_URL:
                if (player != null && accUrl != null && !accUrl.isEmpty()) {
                    long pos = player.getCurrentPosition();
                    onAccUrl = true;
                    playMedia(accUrl, accHeaders, pos);
                }
                break;
            case S_CH_LEFT:
                vocal.setMode(VocalProcessor.LEFT);
                break;
            case S_CH_RIGHT:
                vocal.setMode(VocalProcessor.RIGHT);
                break;
            case S_CANCEL:
                vocal.setMode(VocalProcessor.CANCEL);
                break;
            default:
                break;
        }
        applyStateLabel();
    }

    private void selectSecondAudioTrack() {
        if (player == null) return;
        Tracks.Group firstGroup = null;
        int firstIdx = -1;
        Tracks.Group otherGroup = null;
        int otherIdx = -1;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < g.length; i++) {
                if (g.isTrackSelected(i)) {
                    firstGroup = g;
                    firstIdx = i;
                } else if (otherGroup == null) {
                    otherGroup = g;
                    otherIdx = i;
                }
            }
        }
        Tracks.Group use = otherGroup != null ? otherGroup : firstGroup;
        int idx = otherGroup != null ? otherIdx : firstIdx;
        if (use != null && idx >= 0) {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setOverrideForType(new TrackSelectionOverride(use.getMediaTrackGroup(), List.of(idx)))
                    .build());
        }
    }

    private void applyStateLabel() {
        if (modeBtn == null || states.isEmpty()) return;
        int s = states.get(stateIdx);
        String label;
        switch (s) {
            case S_ACC_TRACK:
                label = "伴奏·轨";
                break;
            case S_ACC_URL:
                label = "伴奏·版";
                break;
            case S_CH_LEFT:
                label = "伴奏·左声道";
                break;
            case S_CH_RIGHT:
                label = "伴奏·右声道";
                break;
            case S_CANCEL:
                label = "消音";
                break;
            default:
                label = "原唱";
        }
        modeBtn.setText(label);
    }

    private void setPitch(int semi) {
        pitchSemi = Math.max(-6, Math.min(6, semi));
        if (player != null) {
            float pitch = (float) Math.pow(2.0, pitchSemi / 12.0);
            player.setPlaybackParameters(new PlaybackParameters(1.0f, pitch));
        }
        if (pitchLabel != null) pitchLabel.setText("调 " + (pitchSemi > 0 ? "+" + pitchSemi : String.valueOf(pitchSemi)));
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) player.pause();
        else player.play();
        pokeControls();
    }

    // ================= 歌词与进度 =================

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            ui.postDelayed(this, 300);
            if (player == null || isFinishing()) return;
            long pos = player.getCurrentPosition();
            long dur = player.getDuration();
            if (!seeking && dur > 0) {
                seekBar.setProgress((int) (pos * 1000 / dur));
                timeText.setText(fmtTime(pos) + "/" + fmtTime(dur));
            }
            updateLyrics(Math.max(0, pos - lyricOffsetMs));
        }
    };

    private void updateLyrics(long pos) {
        if (lyrics == null || lyrics.lines.isEmpty()) return;
        int idx = -1;
        for (int i = 0; i < lyrics.lines.size(); i++) {
            if (lyrics.lines.get(i).atMs <= pos + 120) idx = i;
            else break;
        }
        if (idx == curLine) {
            if (idx >= 0) renderCurLine(pos);
            return;
        }
        curLine = idx;
        linePrev.setText(idx > 0 ? lyrics.lines.get(idx - 1).text : "");
        lineNext.setText(idx >= 0 && idx + 1 < lyrics.lines.size() ? lyrics.lines.get(idx + 1).text : (idx < 0 && !lyrics.lines.isEmpty() ? lyrics.lines.get(0).text : ""));
        renderCurLine(pos);
    }

    private void renderCurLine(long pos) {
        if (lyrics == null || curLine < 0 || curLine >= lyrics.lines.size()) {
            if (lyrics != null && curLine < 0 && player != null) lineCur.setText("♪ ♪ ♪");
            return;
        }
        KtvLyrics.Line line = lyrics.lines.get(curLine);
        if (line.words == null || line.words.isEmpty()) {
            lineCur.setText(line.text);
            lineCur.setTextColor(YELLOW);
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (KtvLyrics.Word w : line.words) {
            int start = sb.length();
            sb.append(w.text == null ? "" : w.text);
            int color = w.atMs <= pos ? YELLOW : WHITE;
            sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        lineCur.setText(sb);
    }

    private static String fmtTime(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    // ================= 麦克风 =================

    private void toggleMic() {
        if (mic != null) {
            stopMic();
            return;
        }
        PermissionUtil.requestAudio(this, ok -> {
            if (Boolean.TRUE.equals(ok)) startMic();
            else toast("没给麦克风权限，唱不了");
        });
        pokeControls();
    }

    private void startMic() {
        try {
            mic = new Mic();
            mic.start();
            micBtn.setText("麦克风·开");
            toast("麦克风开了，唱完再点一次关，录音会存下来");
        } catch (Throwable t) {
            mic = null;
            toast("麦克风没打开");
        }
    }

    private void stopMic() {
        if (mic == null) return;
        File f = mic.stopAndSave();
        mic = null;
        micBtn.setText("麦克风");
        toast(f != null ? "录音存好了：" + f.getName() : "麦克风关了");
    }

    /** 麦克风返听 + 同步录成 WAV（就近取材的土法，延迟以系统为准）。 */
    private class Mic {
        private AudioRecord record;
        private AudioTrack track;
        private Thread thread;
        private volatile boolean run;
        private File file;
        private long dataBytes;

        void start() throws Exception {
            int sr = 44100;
            int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int buf = Math.max(min, 8192);
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, sr,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf * 2);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(buf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            File dir = new File(getCacheDir(), "ktv");
            dir.mkdirs();
            String ttl = current == null ? "" : current.title().replaceAll("[\\\\/:*?\"<>|]", "");
            if (ttl.length() > 24) ttl = ttl.substring(0, 24);
            file = new File(dir, "唱_" + ttl + "_" + new SimpleDateFormat("MMdd_HHmmss", Locale.ROOT).format(new Date()) + ".wav");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(new byte[44]); // 占位头，停时回填
            run = true;
            track.play();
            record.startRecording();
            thread = new Thread(() -> {
                float gain = KtvStore.micGain();
                byte[] b = new byte[buf];
                while (run) {
                    int n = record.read(b, 0, b.length);
                    if (n > 0) {
                        if (gain != 1f) {
                            for (int gi = 0; gi + 1 < n; gi += 2) {
                                int smp = (b[gi] & 0xFF) | (b[gi + 1] << 8);
                                smp = (int) (smp * gain);
                                if (smp > 32767) smp = 32767;
                                if (smp < -32768) smp = -32768;
                                b[gi] = (byte) smp;
                                b[gi + 1] = (byte) (smp >> 8);
                            }
                        }
                        track.write(b, 0, n);
                        try {
                            fos.write(b, 0, n);
                            dataBytes += n;
                        } catch (Throwable ignored) {
                        }
                    }
                }
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
                patchHeader();
            }, "ktv-mic");
            thread.start();
        }

        private void patchHeader() {
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                int sr = 44100;
                raf.write("RIFF".getBytes());
                raf.write(intLe((int) (36 + dataBytes)));
                raf.write("WAVE".getBytes());
                raf.write("fmt ".getBytes());
                raf.write(intLe(16));
                raf.write(shortLe((short) 1));
                raf.write(shortLe((short) 1));
                raf.write(intLe(sr));
                raf.write(intLe(sr * 2));
                raf.write(shortLe((short) 2));
                raf.write(shortLe((short) 16));
                raf.write("data".getBytes());
                raf.write(intLe((int) dataBytes));
            } catch (Throwable ignored) {
            }
        }

        private byte[] intLe(int v) {
            return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
        }

        private byte[] shortLe(short v) {
            return new byte[]{(byte) v, (byte) (v >> 8)};
        }

        File stopAndSave() {
            run = false;
            try {
                if (thread != null) thread.join(800);
            } catch (Throwable ignored) {
            }
            try {
                if (record != null) {
                    record.stop();
                    record.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (track != null) {
                    track.stop();
                    track.release();
                }
            } catch (Throwable ignored) {
            }
            return file != null && dataBytes > 0 ? file : null;
        }
    }

    // ================= 队列弹窗 =================

    private void showQueue() {
        List<KtvSong> q = KtvStore.queue();
        String[] names = new String[q.size() + 1];
        names[0] = "＋ 去点歌台点歌";
        for (int i = 0; i < q.size(); i++) names[i + 1] = (i + 1) + ". " + q.get(i).title() + " - " + q.get(i).singer;
        new AlertDialog.Builder(this)
                .setTitle("接下来要唱的")
                .setItems(names, (d, w) -> {
                    if (w == 0) {
                        KtvActivity.start(this);
                    }
                })
                .setNegativeButton("关", null)
                .show();
        pokeControls();
    }

    // ================= AI 口令入口（给语音层反射调用） =================

    public void aiKtvNext() {
        nextSong();
    }

    public void aiKtvToggle() {
        togglePlay();
    }

    public void aiKtvMode(int mode) {
        // 0 原唱 1 伴奏 2 消音：落到最接近的可用状态
        if (states.isEmpty()) return;
        int target = mode == 0 ? S_ORIGINAL : mode == 2 ? S_CANCEL : -1;
        if (mode == 1) {
            for (int s : states) if (s == S_ACC_TRACK || s == S_ACC_URL || s == S_CH_LEFT) target = s;
            if (target < 0) target = S_CANCEL;
        }
        int idx = states.indexOf(target);
        if (idx >= 0) {
            stateIdx = idx;
            applyState(target);
        }
    }

    // ================= 小件 =================

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView lyricText(int sp, int color) {
        TextView t = text("", sp, color);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int navBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private void addBtn(LinearLayout row, View b) {
        if (b instanceof TextView) ((TextView) b).setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        row.addView(b, lp);
    }

    private void setLyricOffset(long ms) {
        lyricOffsetMs = Math.max(-15000, Math.min(15000, ms));
        if (offsetLabel != null) offsetLabel.setText("词延 " + String.format(java.util.Locale.US, "%+.1f", lyricOffsetMs / 1000.0));
        curLine = -1;
        pokeControls();
    }

    private TextView btn(String s, View.OnClickListener l) {
        TextView t = text(s, 14, WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(13), dp(9), dp(13), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF232838);
        g.setCornerRadius(dp(999));
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(5);
        lp.rightMargin = dp(5);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    private GradientDrawable scrim(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        return g;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (current != null && !currentConsumed) KtvStore.requeueFront(current);
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(hideRunnable);
        if (mic != null) mic.stopAndSave();
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
