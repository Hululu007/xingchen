package com.fongmi.android.tv.api.node;

import android.net.Uri;
import android.os.Looper;
import android.text.TextUtils;

import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.playback.vod.VodPlaybackHost;
import com.fongmi.android.tv.playback.vod.VodPlaybackInfo;
import com.fongmi.android.tv.player.PlaybackSnapshot;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.PlaybackService;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonObject;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class NodePlayback {

    private volatile PlayInfo playInfo = PlayInfo.empty();

    void clear() {
        playInfo = PlayInfo.empty();
    }

    void setPlayInfo(String flag, String id) {
        VodPlaybackInfo.Snapshot snapshot = VodPlaybackInfo.get();
        String title = snapshot.title();
        String episodeName = snapshot.episodeName();
        if (TextUtils.isEmpty(flag)) flag = snapshot.flag();
        if (title.isEmpty() && App.activity() instanceof VodPlaybackHost host) {
            title = host.getVodName();
            episodeName = host.getVodMark();
        }
        playInfo = new PlayInfo(text(title), text(episodeName), fileName(id), text(flag), text(id));
    }

    String getPlayInfo() {
        PlaybackSnapshot current = getPlaybackSnapshot();
        MediaMetadata metadata = current.metadata();
        PlayInfo pending = playInfo;
        String title = text(metadata == null ? null : metadata.title);
        String episodeName = text(metadata == null ? null : metadata.artist);
        if (title.isEmpty()) title = pending.title();
        if (episodeName.isEmpty()) episodeName = pending.episodeName();
        VodPlaybackInfo.Snapshot snapshot = VodPlaybackInfo.get();
        if (title.isEmpty()) title = snapshot.title();
        if (episodeName.isEmpty()) episodeName = snapshot.episodeName();
        if (App.activity() instanceof VodPlaybackHost host) {
            if (title.isEmpty()) title = host.getVodName();
            if (episodeName.isEmpty()) episodeName = host.getVodMark();
        }
        return serializePlayInfo(current, pending, title, episodeName);
    }

    private String serializePlayInfo(PlaybackSnapshot current, PlayInfo pending, String title, String episodeName) {
        String url = current.url();
        String fileName = pending.fileName().isEmpty() ? fileName(url) : pending.fileName();
        JsonObject result = new JsonObject();
        result.addProperty("title", title);
        result.addProperty("episodeName", episodeName);
        result.addProperty("fileName", fileName);
        result.addProperty("flag", pending.flag());
        result.addProperty("id", pending.id());
        result.addProperty("url", url);
        result.addProperty("key", current.key());
        result.addProperty("position", current.position());
        result.addProperty("duration", current.duration());
        SpiderDebug.log("NodeRuntime", "getPlayInfo title=%s, episode=%s, flag=%s", title, episodeName, pending.flag());
        return result.toString();
    }

    boolean pushDanmaku(String url) {
        if (url.isEmpty()) return clearDanmaku();
        App.post(() -> RefreshEvent.danmaku(url));
        return true;
    }

    boolean clearDanmaku() {
        PlaybackService service = Server.get().getService();
        PlayerManager player = service == null ? null : service.player();
        if (player == null) return false;
        // 树适配：上游此处调 PlayerManager 的 public clearDanmaku()（清 spec
        // 选中的弹幕源）；本树 PlayerManager 没有这个公开面——弹幕走
        // DanmakuController＋私有 clearDanmaku(reason)。本树既有的清弹幕
        // 公开面是 RefreshEvent 空路径：两变体 VideoActivity 都以
        // reloadDanmaku(Danmaku.from(path)) 消费 DANMAKU 事件，空 Danmaku
        // 在 setDanmaku 里走 clearDanmaku("empty_source") 分支（已核源码），
        // 与 pushDanmaku 的非空路径正好成对，故走事件面，不动播放器本体。
        App.post(() -> RefreshEvent.danmaku(""));
        return true;
    }

    private static PlaybackSnapshot getPlaybackSnapshot() {
        PlaybackService service = Server.get().getService();
        PlayerManager player = service == null ? null : service.player();
        if (player == null) return PlaybackSnapshot.empty();
        if (Looper.myLooper() == Looper.getMainLooper()) return PlaybackSnapshot.capture(player);
        PlaybackSnapshot pending = PlaybackSnapshot.pending(player);
        FutureTask<PlaybackSnapshot> task = new FutureTask<>(() -> PlaybackSnapshot.capture(player));
        App.post(task);
        try {
            return task.get(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            SpiderDebug.log(e);
        } catch (TimeoutException e) {
            task.cancel(false);
        }
        return pending;
    }

    private static String fileName(String value) {
        if (TextUtils.isEmpty(value)) return "";
        try {
            String name = Uri.parse(value).getLastPathSegment();
            return TextUtils.isEmpty(name) ? value : name;
        } catch (Exception e) {
            return value;
        }
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private record PlayInfo(String title, String episodeName, String fileName, String flag, String id) {
        private static PlayInfo empty() {
            return new PlayInfo("", "", "", "", "");
        }
    }
}
