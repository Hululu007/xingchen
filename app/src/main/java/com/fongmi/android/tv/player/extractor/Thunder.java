package com.fongmi.android.tv.player.extractor;

import android.net.Uri;
import android.os.SystemClock;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;
import com.xunlei.downloadlib.XLTaskHelper;
import com.xunlei.downloadlib.parameter.GetTaskId;
import com.xunlei.downloadlib.parameter.TorrentFileInfo;
import com.xunlei.downloadlib.parameter.XLTaskInfo;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

public class Thunder implements Source.Extractor {

    private GetTaskId taskId;

    @Override
    public boolean match(Uri uri) {
        return List.of("magnet", "ed2k").contains(UrlUtil.scheme(uri));
    }

    // 同一 URL 的本地地址缓存：播放链会对同一链接并发多次 fetch（1.2.4 日志实证 50ms 内
    // 建了 4 个 native 任务写同一文件互抢），复用已建任务的地址，不再重复建任务。
    // 复用前提是旧任务仍活着（状态 0/1/2），死任务不复用，走下面的串行重试卫生。
    private String lastKey;
    private String lastLocal;

    // 进行中的 fetch 合流：推送每次播放都会触发两路并发解析（18:02 日志实证两路相隔 20ms，
    // 共用 Source 单例里的同一个 Thunder 实例）。后到的一路如果另建 native 任务，会写同一
    // 目录互抢、覆盖 taskId 字段、随后 stop() 删掉的也是后建的任务，先建的反而泄漏。
    // 所以同 URL 正在 fetch 时后到者直接等先到者的结果，不建第二个任务。
    private final ConcurrentHashMap<String, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();

    @Override
    public String fetch(String url) throws Exception {
        if (url == null) return fetchInternal(url);
        synchronized (this) {
            if (url.equals(lastKey) && lastLocal != null && isTaskAlive()) {
                SpiderDebug.log("thunder", "reuse live task, skip duplicate fetch");
                return lastLocal;
            }
        }
        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> first = inflight.putIfAbsent(url, mine);
        if (first != null) {
            SpiderDebug.log("thunder", "join in-flight fetch, wait for first result");
            try {
                return first.get(70, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new Exception(cause);
            } catch (TimeoutException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new ExtractException(ResUtil.getString(R.string.error_play_timeout));
            }
        }
        try {
            String local = fetchInternal(url);
            mine.complete(local);
            return local;
        } catch (Throwable t) {
            mine.completeExceptionally(t);
            throw t;
        } finally {
            inflight.remove(url, mine);
        }
    }

    private synchronized String fetchInternal(String url) throws Exception {
        // 有效任务复用：同链接且缓存地址仍对应活任务时直接复用
        if (url != null && url.equals(lastKey) && lastLocal != null && isTaskAlive()) {
            SpiderDebug.log("thunder", "reuse live task, skip duplicate fetch");
            return lastLocal;
        }
        // 串行重试卫生：上轮同一链接失败后 taskId 仍指向旧原生任务（从未 stop/delete）。
        // 旧任务已死/失效时先停删再建新任务，禁止两个原生任务同目录并存互抢。
        if (taskId != null && !isTaskAlive()) cleanupStaleTask();
        String local = isRawMagnet(url) ? fetchParsedMagnet(url)
                : url.startsWith("magnet") ? addTorrentTask(Uri.parse(url)) : addThunderTask(url);
        if (local != null && local.contains("127.0.0.1")) {
            lastKey = url;
            lastLocal = local;
        }
        return local;
    }

    // 任务是否还活着：0 等待 / 1 下载中 / 2 已完成都算活；3 出错、读不到信息都算死
    private boolean isTaskAlive() {
        if (taskId == null) return false;
        try {
            XLTaskInfo info = XLTaskHelper.get().getTaskInfo(taskId);
            int status = info == null ? -1 : info.getTaskStatus();
            return status == 0 || status == 1 || status == 2;
        } catch (Exception e) {
            return false;
        }
    }

    // 清理死任务：stop + delete，不释放引擎（重建任务走热引擎，与上游每轮建任务一致）
    private void cleanupStaleTask() {
        int status = -1;
        try {
            XLTaskInfo info = XLTaskHelper.get().getTaskInfo(taskId);
            status = info == null ? -1 : info.getTaskStatus();
        } catch (Exception ignored) {
        }
        try {
            XLTaskHelper.get().stopTask(taskId);
            XLTaskHelper.get().deleteTask(taskId);
        } catch (Exception e) {
            SpiderDebug.log("thunder", "cleanup stale task err=%s", String.valueOf(e.getMessage()));
        }
        taskId = null;
        lastKey = null;
        lastLocal = null;
        SpiderDebug.log("thunder", "cleaned stale task before rebuild status=%d", status);
    }

    /**
     * 兜底：裸 magnet（如 magnet:?xt=urn:btih:…）没有经过 Parser，
     * 直接走 addTorrentTask 会 NPE（opaque URI 取不到 path/index）。
     * 先同步解析一遍，拿到标准播放地址再继续。
     */
    private boolean isRawMagnet(String url) {
        if (url == null || !url.startsWith("magnet:")) return false;
        try {
            return Uri.parse(url).getQueryParameter("index") == null;
        } catch (Exception e) {
            return true;
        }
    }

    private String fetchParsedMagnet(String url) throws Exception {
        List<Episode> episodes = Parser.get(url).call();
        if (episodes.isEmpty()) throw new ExtractException("磁力解析失败，请重试");
        String parsed = episodes.get(0).getUrl();
        return parsed.startsWith("magnet") ? addTorrentTask(Uri.parse(parsed)) : parsed;
    }

    // 照上游：状态 3 抛真错；状态非 0 即交本地地址，播放器自己拉流边下边播，不等字节
    private String addTorrentTask(Uri uri) throws Exception {
        File torrent = new File(uri.getPath());
        File parent = torrent.getParentFile();
        String name = uri.getQueryParameter("name");
        int index = Integer.parseInt(uri.getQueryParameter("index"));
        taskId = XLTaskHelper.get().addTorrentTask(torrent, parent, index);
        for (int i = 0; i < 100; i++) {
            XLTaskInfo info = XLTaskHelper.get().getBtSubTaskInfo(taskId, index).mTaskInfo;
            if (info.mTaskStatus == 3) throw new ExtractException(info.getErrorMsg());
            if (info.mTaskStatus != 0) {
                String local = XLTaskHelper.get().getLocalUrl(new File(parent, name));
                SpiderDebug.log("thunder", "bt task handed local=%s status=%d speed=%d size=%d", local, info.mTaskStatus, info.mDownloadSpeed, info.mDownloadSize);
                return local;
            }
            if (i % 50 == 49) SpiderDebug.log("thunder", "bt waiting status=%d speed=%d size=%d", info.mTaskStatus, info.mDownloadSpeed, info.mDownloadSize);
            SystemClock.sleep(100);
        }
        throw new ExtractException(ResUtil.getString(R.string.error_play_timeout));
    }

    // 照上游：ed2k/thunder 零等待，直接交本地地址
    private String addThunderTask(String url) {
        File folder = Path.thunder(Util.md5(url));
        taskId = XLTaskHelper.get().addThunderTask(url, folder);
        String local = XLTaskHelper.get().getLocalUrl(taskId.getSaveFile());
        try {
            XLTaskInfo info = XLTaskHelper.get().getNativeTaskInfo(taskId);
            SpiderDebug.log("thunder", "task handed local=%s status=%d speed=%d size=%d", local, info == null ? -1 : info.mTaskStatus, info == null ? 0 : info.mDownloadSpeed, info == null ? 0 : info.mDownloadSize);
        } catch (Exception e) {
            SpiderDebug.log("thunder", "task handed local=%s (info unreadable)", local);
        }
        return local;
    }

    @Override
    public synchronized void stop() {
        lastKey = null;
        lastLocal = null;
        if (taskId == null) return;
        // 回到 FongMi 原版语义：删任务 + 释放 SDK（下次 fetch 会重新 init）；不再额外删下载目录，避免与重播同一链接的新任务抢目录
        XLTaskHelper.get().deleteTask(taskId);
        XLTaskHelper.get().release();
        taskId = null;
    }

    @Override
    public void exit() {
        XLTaskHelper.get().release();
    }

    public record Parser(String url) implements Callable<List<Episode>> {

        private static final Pattern PATTERN = Pattern.compile("(magnet|thunder|ed2k):.*");

        public static boolean match(String url) {
            return PATTERN.matcher(url).find() || isTorrent(url);
        }

        public static Parser get(String url) {
            return new Parser(url);
        }

        private static boolean isTorrent(String url) {
            return !url.startsWith("magnet") && url.split(";")[0].toLowerCase().endsWith(".torrent");
        }

        private Episode create(GetTaskId taskId) {
            return Episode.create(taskId.getFileName(), taskId.getRealUrl());
        }

        private Episode create(TorrentFileInfo info) {
            return Episode.create(info.getFileName(), info.getSize(), info.getPlayUrl());
        }

        @Override
        public List<Episode> call() throws Exception {
            boolean torrent = isTorrent(url);
            if (url.startsWith("ed2k:")) {
                return Arrays.asList(Episode.create(parseEd2kName(url), url));
            }
            GetTaskId taskId;
            try {
                taskId = XLTaskHelper.get().parse(url, Path.thunder(Util.md5(url)));
            } catch (Exception e) {
                throw new ExtractException("磁力解析超时，请检查网络后重试");
            }
            if (!torrent && !taskId.getRealUrl().startsWith("magnet")) return Arrays.asList(create(taskId));
            if (torrent && url.startsWith("http")) Download.create(url, taskId.getSaveFile()).get();
            if (!torrent) waitDone(taskId);
            try {
                return XLTaskHelper.get().getTorrentInfo(taskId.getSaveFile()).getMedias().stream().map(this::create).toList();
            } finally {
                XLTaskHelper.get().stopTask(taskId);
            }
        }

        private void waitDone(GetTaskId taskId) {
            for (int i = 0; i < 100; i++) {
                if (XLTaskHelper.get().getTaskInfo(taskId).getTaskStatus() == 2) return;
                SystemClock.sleep(100);
            }
        }

        private static String parseEd2kName(String url) {
            try {
                String[] parts = url.split("\\|");
                if (parts.length > 2 && !parts[2].isEmpty()) return java.net.URLDecoder.decode(parts[2], "UTF-8");
            } catch (Exception ignored) {
            }
            return url;
        }
    }
}
