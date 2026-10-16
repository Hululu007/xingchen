package com.fongmi.android.tv.watch;

import android.os.Handler;
import android.os.Looper;

import java.util.Map;

/**
 * 一起看控制器：把 WatchSession 的房间事件接到播放器（Host 接口由 VideoActivity 实现），
 * 房主每 3 秒广播进度对表，客人误差超过 1.5 秒自动拉齐。
 */
public final class WatchController implements WatchSession.Listener {

    public interface Host {
        void watchPlayUrl(String url, Map<String, String> headers, String title, long posMs);

        void watchPause();

        void watchResume();

        void watchSeek(long posMs);

        void watchChat(String name, String text);

        void watchStatus(String text);

        void watchMembers(int count);

        long watchPosition();

        boolean watchIsPlaying();

        void watchEnded();

        void watchSingStart(String name);

        void watchSingStop(String name);

        void watchScore(String name, int score, String grade);
    }

    private final Host host;
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private WatchSession session;
    private String lastUrl = "";

    private final Runnable syncLoop = new Runnable() {
        @Override
        public void run() {
            if (session != null && session.isActive() && session.isHost()) {
                session.hostSync(host.watchPosition(), host.watchIsPlaying());
                ticker.postDelayed(this, 3000);
            }
        }
    };

    public WatchController(Host host) {
        this.host = host;
    }

    public boolean inRoom() {
        return session != null && session.isActive();
    }

    public boolean isHost() {
        return session != null && session.isHost();
    }

    public String roomCode() {
        return session == null ? "" : session.getRoomCode();
    }

    public void createRoom(String name, String password) {
        stopInternal();
        session = new WatchSession(this);
        session.createRoom(name, password);
    }

    public void joinRoom(String code, String name, String password) {
        stopInternal();
        session = new WatchSession(this);
        session.joinRoom(code, name, password);
    }

    public void release() {
        stopInternal();
    }

    public void leaveRoom() {
        stopInternal();
        host.watchEnded();
    }

    private void stopInternal() {
        ticker.removeCallbacks(syncLoop);
        if (session != null) {
            session.leave();
            session = null;
        }
        lastUrl = "";
    }

    // ---- 播放器侧事件（VideoActivity 钩子调用） ----

    public void onHostPlayResolved(String url, Map<String, String> headers, String title) {
        if (session == null || !session.isActive() || !session.isHost()) return;
        if (url == null || url.isEmpty() || url.equals(lastUrl)) return;
        lastUrl = url;
        session.hostPlay(url, title, Math.max(0, host.watchPosition()), headers);
    }

    public void onHostPlayingChanged(boolean playing) {
        if (session == null || !session.isActive() || !session.isHost()) return;
        if (playing) session.hostResume(host.watchPosition());
        else session.hostPause(host.watchPosition());
    }

    public void onHostSeek(long posMs) {
        if (session == null || !session.isActive() || !session.isHost()) return;
        session.hostSeek(posMs);
    }

    public void onLocalSingStart() {
        if (session != null && session.isActive()) session.sendSingStart();
    }

    public void onLocalSingStop() {
        if (session != null && session.isActive()) session.sendSingStop();
    }

    public void onLocalSingResult(int score, String grade) {
        if (session != null && session.isActive()) session.sendScore(score, grade);
    }

    public void sendChat(String text) {
        if (session != null && session.isActive()) session.sendChat(text);
    }

    // ---- WatchSession.Listener ----

    @Override
    public void onConnected(boolean isHost, String roomCode) {
        host.watchStatus(isHost ? "房间已建好，房号 " + roomCode : "已进入房间 " + roomCode);
        if (isHost) {
            lastUrl = "";
            ticker.removeCallbacks(syncLoop);
            ticker.postDelayed(syncLoop, 1500);
        }
    }

    @Override
    public void onDisconnected(String reason) {
        host.watchStatus("一起看已断开");
        host.watchEnded();
    }

    @Override
    public void onPlay(String url, String title, long posMs, Map<String, String> headers) {
        if (session != null && session.isHost()) return;
        lastUrl = url;
        host.watchPlayUrl(url, headers, title, posMs);
    }

    @Override
    public void onPause() {
        if (session == null || session.isHost()) return;
        host.watchPause();
    }

    @Override
    public void onResume(long posMs) {
        if (session == null || session.isHost()) return;
        host.watchResume();
    }

    @Override
    public void onSeek(long posMs) {
        if (session == null || session.isHost()) return;
        host.watchSeek(posMs);
    }

    @Override
    public void onSync(long posMs, boolean playing) {
        if (session == null || session.isHost()) return;
        long mine = host.watchPosition();
        if (Math.abs(posMs - mine) > 1500) host.watchSeek(posMs);
        if (playing != host.watchIsPlaying()) {
            if (playing) host.watchResume();
            else host.watchPause();
        }
    }

    @Override
    public void onChat(String name, String text) {
        host.watchChat(name, text);
    }

    @Override
    public void onMembers(int count) {
        host.watchMembers(count);
    }

    @Override
    public void onPeerJoin(String name) {
        host.watchStatus((name == null || name.isEmpty() ? "有人" : name) + " 进房了");
    }

    @Override
    public void onSingStart(String name) {
        host.watchSingStart(name);
    }

    @Override
    public void onSingStop(String name) {
        host.watchSingStop(name);
    }

    @Override
    public void onScore(String name, int score, String grade) {
        host.watchScore(name, score, grade);
    }

    @Override
    public void onVoiceSignal(String kind, String fromId, String name, com.google.gson.JsonObject data) {
        // 实时语音（合唱）下一版接：信令通道已通，这里先收下不处理。
    }

    @Override
    public void onHostGone() {
        host.watchStatus("房主离开了");
    }
}
