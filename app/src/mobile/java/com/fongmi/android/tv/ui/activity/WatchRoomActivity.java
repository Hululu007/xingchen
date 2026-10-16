package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.databinding.ActivityWatchRoomBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.watch.WatchController;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一起看房间页（独立页面，跟 KTV 一样点了一起看就单独进来，跟详情页彻底脱开）：
 * 上面自己的播放器、下面房间面板（复用 WatchRoomView），聊天同时飘弹幕；
 * 房主在页内选集/播放/暂停/拖动都会同步给客人，客人跟随房主。
 */
public class WatchRoomActivity extends BaseActivity implements WatchController.Host {

    private static WeakReference<WatchRoomActivity> CURRENT;

    private ActivityWatchRoomBinding binding;
    private ExoPlayer player;
    private PlayerView playerView;
    private WatchRoomView.FloatLayer floatLayer;
    private WatchRoomView roomView;
    private FrameLayout playerBox;
    private LinearLayout transport;
    private TextView playBtn;
    private TextView timeText;
    private TextView epBtn;
    private TextView chatBtn;
    private SeekBar seekBar;
    private boolean seeking;
    private boolean panelShown = true;

    private WatchController mWatch;
    private boolean host;
    private String firstUrl;
    private java.util.Map<String, String> firstHeaders;
    private int firstEpIndex = -1;
    private String myName = "我";
    private String siteKey = "";
    private String flag = "";
    private String vodTitle = "";
    private final List<String[]> episodes = new ArrayList<>();
    private int curEp = -1;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---------- 入口 ----------

    public static boolean isOpen() {
        WatchRoomActivity a = CURRENT == null ? null : CURRENT.get();
        return a != null && !a.isFinishing();
    }

    public static void bringToFront(Context ctx) {
        Intent i = new Intent(ctx, WatchRoomActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        ctx.startActivity(i);
    }

    public static boolean leaveIfOpen() {
        WatchRoomActivity a = CURRENT == null ? null : CURRENT.get();
        if (a == null || a.isFinishing()) return false;
        a.runOnUiThread(a::doLeave);
        return true;
    }

    public static boolean voiceIfOpen(Activity caller, boolean on) {
        WatchRoomActivity a = CURRENT == null ? null : CURRENT.get();
        if (a == null || a.isFinishing()) return false;
        a.runOnUiThread(() -> a.aiWatchVoice(on));
        return true;
    }

    public static void startCreate(Context ctx, String name, String password, String siteKey, String flag, String vodName, String pic, List<Episode> eps, int startIndex, long startPosMs, String firstUrl, java.util.Map<String, String> firstHeaders) {
        Intent i = new Intent(ctx, WatchRoomActivity.class);
        i.putExtra("mode", "create");
        i.putExtra("name", name);
        i.putExtra("password", password);
        i.putExtra("site_key", siteKey);
        i.putExtra("flag", flag);
        i.putExtra("vod_name", vodName);
        i.putExtra("pic", pic);
        JsonArray arr = new JsonArray();
        if (eps != null) {
            for (Episode e : eps) {
                JsonObject o = new JsonObject();
                o.addProperty("n", e.getName() == null ? "" : e.getName());
                o.addProperty("u", e.getUrl() == null ? "" : e.getUrl());
                arr.add(o);
            }
        }
        i.putExtra("episodes", arr.toString());
        i.putExtra("start_index", startIndex);
        i.putExtra("start_pos", startPosMs);
        // 房主正在播的那一集的已解析地址：房间页自己重解析对有些源（海阔/需解析的）拿不到，
        // 直接把现成的带进来，至少正在看的这一集进房就能播。
        if (firstUrl != null && !firstUrl.isEmpty()) {
            i.putExtra("first_url", firstUrl);
            if (firstHeaders != null && !firstHeaders.isEmpty()) {
                JsonObject h = new JsonObject();
                for (java.util.Map.Entry<String, String> e : firstHeaders.entrySet()) h.addProperty(e.getKey(), e.getValue());
                i.putExtra("first_headers", h.toString());
            }
        }
        ctx.startActivity(i);
    }

    public static void startJoin(Context ctx, String code, String name, String password) {
        Intent i = new Intent(ctx, WatchRoomActivity.class);
        i.putExtra("mode", "join");
        i.putExtra("code", code);
        i.putExtra("name", name);
        i.putExtra("password", password);
        ctx.startActivity(i);
    }

    // ---------- 生命周期 ----------

    @Override
    protected androidx.viewbinding.ViewBinding getBinding() {
        return binding = ActivityWatchRoomBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        CURRENT = new WeakReference<>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Intent i = getIntent();
        host = "create".equals(i.getStringExtra("mode"));
        myName = i.getStringExtra("name") == null || i.getStringExtra("name").isEmpty() ? "我" : i.getStringExtra("name");
        siteKey = i.getStringExtra("site_key") == null ? "" : i.getStringExtra("site_key");
        flag = i.getStringExtra("flag") == null ? "" : i.getStringExtra("flag");
        vodTitle = i.getStringExtra("vod_name") == null ? "" : i.getStringExtra("vod_name");
        String epsJson = i.getStringExtra("episodes");
        if (epsJson != null && !epsJson.isEmpty()) {
            try {
                JsonArray arr = JsonParser.parseString(epsJson).getAsJsonArray();
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    episodes.add(new String[]{o.get("n").getAsString(), o.get("u").getAsString()});
                }
            } catch (Throwable ignored) {
            }
        }
        firstUrl = i.getStringExtra("first_url");
        String fh = i.getStringExtra("first_headers");
        if (fh != null && !fh.isEmpty()) {
            try {
                firstHeaders = new java.util.HashMap<>();
                JsonObject o = JsonParser.parseString(fh).getAsJsonObject();
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) firstHeaders.put(e.getKey(), e.getValue().getAsString());
            } catch (Throwable ignored) {
            }
        }
        firstEpIndex = i.getIntExtra("start_index", 0);
        buildUi();
        buildPlayer();
        mWatch = new WatchController(this);
        roomView.setMyName(myName);
        if (host) {
            mWatch.createRoom(myName, i.getStringExtra("password") == null ? "" : i.getStringExtra("password"));
            int start = i.getIntExtra("start_index", 0);
            long pos = i.getLongExtra("start_pos", 0);
            if (!episodes.isEmpty()) {
                int st = Math.max(0, Math.min(start, episodes.size() - 1));
                firstEpIndex = st;
                resolveAndPlay(st, pos);
            }
            else roomView.addSystem("没带选集进来，房主可以在详情页选好片再建房");
        } else {
            mWatch.joinRoom(i.getStringExtra("code") == null ? "" : i.getStringExtra("code"), myName, i.getStringExtra("password") == null ? "" : i.getStringExtra("password"));
            roomView.addSystem("等房主开播…");
        }
        ui.post(ticker);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        arrange();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(ticker);
        if (CURRENT != null && CURRENT.get() == this) CURRENT = null;
        if (mWatch != null) {
            mWatch.release();
            mWatch = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (host) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("离开房间")
                    .setMessage("你是房主，离开就会解散房间。")
                    .setPositiveButton("离开", (d, w) -> doLeave())
                    .setNegativeButton("再待会", null)
                    .show();
        } else {
            doLeave();
        }
    }

    // ---------- 界面 ----------

    private void buildUi() {
        FrameLayout root = binding.watchRoot;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        playerBox = new FrameLayout(this);
        playerBox.setBackgroundColor(Color.BLACK);
        col.addView(playerBox);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerBox.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        floatLayer = new WatchRoomView.FloatLayer(this);
        playerBox.addView(floatLayer, new FrameLayout.LayoutParams(-1, -1));

        transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        transport.setPadding(dp(10), dp(6), dp(10), dp(6));
        transport.setBackgroundColor(0x99000000);
        playBtn = tbtn("▶", v -> togglePlay());
        transport.addView(playBtn);
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
            }
        });
        if (!host) seekBar.setOnTouchListener((v, e) -> true);
        transport.addView(seekBar, new LinearLayout.LayoutParams(0, -2, 1f));
        timeText = ttext("0:00/0:00", 12, 0xFFC9CDD4);
        transport.addView(timeText);
        if (host && episodes.size() > 1) {
            epBtn = tbtn("选集", v -> showEpisodes());
            transport.addView(epBtn);
        }
        chatBtn = tbtn("聊天", v -> {
            panelShown = !panelShown;
            arrange();
        });
        transport.addView(chatBtn);
        transport.addView(tbtn("横屏", v -> toggleOrientation()));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        playerBox.addView(transport, tlp);

        roomView = new WatchRoomView(this, new WatchRoomView.Action() {
            @Override
            public void onSend(String text) {
                if (mWatch != null) mWatch.sendChat(text);
            }

            @Override
            public void onToggleVoice() {
                toggleVoice();
            }

            @Override
            public void onLeave() {
                onBackPressed();
            }

            @Override
            public void onCollapse() {
                panelShown = false;
                arrange();
            }
        });
        roomView.setOnTitleClick(() -> {
            if (mWatch == null || !mWatch.inRoom()) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("房号", mWatch.roomCode()));
            Notify.show("房号 " + mWatch.roomCode() + " 已复制，发给朋友就能进");
        });
        col.addView(roomView, new LinearLayout.LayoutParams(-1, 0, 1f));
        arrange();
    }

    private void arrange() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        roomView.setLandscapeMode(landscape);
        LinearLayout.LayoutParams plp = (LinearLayout.LayoutParams) playerBox.getLayoutParams();
        LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) roomView.getLayoutParams();
        if (landscape) {
            plp.height = LinearLayout.LayoutParams.MATCH_PARENT;
            plp.weight = 1f;
            roomView.setVisibility(panelShown ? View.VISIBLE : View.GONE);
            rlp.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
            rlp.weight = 0f;
        } else {
            int w = getResources().getDisplayMetrics().widthPixels;
            plp.height = w * 9 / 16;
            plp.weight = 0f;
            roomView.setVisibility(View.VISIBLE);
            rlp.height = 0;
            rlp.weight = 1f;
        }
        playerBox.setLayoutParams(plp);
        roomView.setLayoutParams(rlp);
        chatBtn.setVisibility(landscape ? View.VISIBLE : View.GONE);
    }

    private TextView tbtn(String label, View.OnClickListener click) {
        TextView b = ttext(label, 13, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(7), dp(12), dp(7));
        b.setOnClickListener(click);
        return b;
    }

    private TextView ttext(String label, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toggleOrientation() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    // ---------- 播放器 ----------

    private void buildPlayer() {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                playBtn.setText(isPlaying ? "❚❚" : "▶");
                if (host && mWatch != null) mWatch.onHostPlayingChanged(isPlaying);
            }

            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
                if (host && mWatch != null && reason == Player.DISCONTINUITY_REASON_SEEK) mWatch.onHostSeek(newPosition.positionMs);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED && host && curEp >= 0 && curEp + 1 < episodes.size()) {
                    switchEpisode(curEp + 1);
                }
            }

            @Override
            public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
                Notify.show("播放出错了");
            }
        });
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            ui.postDelayed(this, 400);
            if (player == null || isFinishing()) return;
            long pos = player.getCurrentPosition();
            long dur = player.getDuration();
            if (!seeking && dur > 0) {
                seekBar.setProgress((int) (pos * 1000 / dur));
                timeText.setText(fmt(pos) + "/" + fmt(dur));
            }
        }
    };

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void playMedia(String url, Map<String, String> headers, long posMs) {
        DefaultHttpDataSource.Factory dsf = new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true);
        if (headers != null && !headers.isEmpty()) dsf.setDefaultRequestProperties(headers);
        boolean hasUa = false;
        if (headers != null) for (String k : headers.keySet()) if (k != null && k.equalsIgnoreCase("User-Agent")) hasUa = true;
        if (!hasUa) dsf.setUserAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        player.setMediaSource(new DefaultMediaSourceFactory(dsf).createMediaSource(MediaItem.fromUri(url)));
        if (posMs > 0) player.seekTo(posMs);
        player.prepare();
        player.play();
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) player.pause();
        else player.play();
    }

    private void resolveAndPlay(int index, long posMs) {
        if (index < 0 || index >= episodes.size()) return;
        curEp = index;
        if (index == firstEpIndex && firstUrl != null && !firstUrl.isEmpty()) {
            playMedia(firstUrl, firstHeaders, posMs);
            if (mWatch != null) mWatch.onHostPlayResolved(firstUrl, firstHeaders, vodTitle);
            return;
        }
        String[] ep = episodes.get(index);
        String key = siteKey, fl = flag;
        Task.execute(() -> {
            try {
                Result r = SiteApi.playerContent(key, fl, ep[1]);
                String url = r.getUrl() == null ? "" : r.getUrl().v();
                if (url == null || url.isEmpty()) throw new IllegalStateException("源没给地址");
                Map<String, String> headers = r.getHeader();
                runOnUiThread(() -> {
                    if (isFinishing() || player == null) return;
                    playMedia(url, headers, posMs);
                    if (mWatch != null) mWatch.onHostPlayResolved(url, headers, vodTitle);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> Notify.show("没拿到播放地址"));
            }
        });
    }

    private void switchEpisode(int index) {
        if (!host || index == curEp) return;
        resolveAndPlay(index, 0);
        Notify.show("切到 " + episodes.get(index)[0]);
    }

    private void showEpisodes() {
        String[] names = new String[episodes.size()];
        for (int i = 0; i < episodes.size(); i++) names[i] = episodes.get(i)[0];
        new MaterialAlertDialogBuilder(this)
                .setTitle("选集")
                .setItems(names, (d, which) -> switchEpisode(which))
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 房间动作 ----------

    private void toggleVoice() {
        if (mWatch == null) return;
        if (mWatch.isVoiceEnabled()) {
            mWatch.setVoiceEnabled(false);
            roomView.setVoiceState(false);
        } else {
            PermissionUtil.requestAudio(this, ok -> {
                if (Boolean.TRUE.equals(ok)) {
                    mWatch.setVoiceEnabled(true);
                    roomView.setVoiceState(true);
                } else {
                    Notify.show("没给麦克风权限，开不了合唱");
                }
            });
        }
    }

    private void doLeave() {
        if (mWatch != null) mWatch.leaveRoom();
        else finish();
    }

    private void syncRoomInfo() {
        if (mWatch == null || roomView == null) return;
        roomView.setMyName(mWatch.getMyName());
        roomView.setRoomInfo(mWatch.roomCode(), mWatch.isHost(), mWatch.getMembers().size());
        roomView.setVoiceState(mWatch.isVoiceEnabled());
        roomView.updateMembers(mWatch.getMembers());
    }

    /** AI：退出一起看 */
    public void aiWatchLeave() {
        runOnUiThread(this::doLeave);
    }

    /** AI：开关实时合唱 */
    public void aiWatchVoice(boolean on) {
        runOnUiThread(() -> {
            if (mWatch == null || !mWatch.inRoom()) {
                Notify.show("先开一起看房间，才能开合唱");
                return;
            }
            if (!on) {
                mWatch.setVoiceEnabled(false);
                roomView.setVoiceState(false);
                return;
            }
            PermissionUtil.requestAudio(this, ok -> {
                if (Boolean.TRUE.equals(ok)) {
                    mWatch.setVoiceEnabled(true);
                    roomView.setVoiceState(true);
                } else {
                    Notify.show("没给麦克风权限，开不了合唱");
                }
            });
        });
    }

    /** AI：暂停/播放切换 */
    public void aiTogglePlay() {
        runOnUiThread(this::togglePlay);
    }

    // ---------- WatchController.Host ----------

    @Override
    public void watchPlayUrl(String url, Map<String, String> headers, String title, long posMs) {
        if (player == null) return;
        playMedia(url, headers, Math.max(0, posMs));
        Notify.show("房主开播了，正在同步");
    }

    @Override
    public void watchPause() {
        if (player != null) player.pause();
    }

    @Override
    public void watchResume() {
        if (player != null) player.play();
    }

    @Override
    public void watchSeek(long posMs) {
        if (player != null) player.seekTo(posMs);
    }

    @Override
    public void watchChat(String name, String text) {
        roomView.addChat(name, text);
        floatLayer.floatText(name, text);
    }

    @Override
    public void watchStatus(String text) {
        Notify.show(text);
        roomView.addSystem(text);
        syncRoomInfo();
    }

    @Override
    public void watchMembers(int count) {
        syncRoomInfo();
    }

    @Override
    public long watchPosition() {
        return player == null ? 0 : player.getCurrentPosition();
    }

    @Override
    public boolean watchIsPlaying() {
        return player != null && player.isPlaying();
    }

    @Override
    public void watchEnded() {
        if (!isFinishing()) finish();
    }

    @Override
    public void watchSingStart(String name) {
        roomView.addSystem((name == null || name.isEmpty() ? "伙伴" : name) + " 开唱了");
    }

    @Override
    public void watchSingStop(String name) {
        roomView.addSystem((name == null || name.isEmpty() ? "伙伴" : name) + " 唱完了");
    }

    @Override
    public void watchScore(String name, int score, String grade) {
        roomView.addSystem((name == null || name.isEmpty() ? "伙伴" : name) + " 得分 " + score + (grade == null || grade.isEmpty() ? "" : "（" + grade + "）"));
    }

}
