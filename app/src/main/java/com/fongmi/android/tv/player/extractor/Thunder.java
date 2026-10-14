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
import java.util.regex.Pattern;

public class Thunder implements Source.Extractor {

    private GetTaskId taskId;

    @Override
    public boolean match(Uri uri) {
        return List.of("magnet", "ed2k").contains(UrlUtil.scheme(uri));
    }

    // 同一 URL 的本地地址缓存：播放链会对同一链接并发多次 fetch（1.2.4 日志实证 50ms 内
    // 建了 4 个 native 任务写同一文件互抢），复用已建任务的地址，不再重复建任务
    private String lastKey;
    private String lastLocal;

    @Override
    public synchronized String fetch(String url) throws Exception {
        if (url != null && url.equals(lastKey) && lastLocal != null && taskId != null) {
            SpiderDebug.log("thunder", "reuse live task, skip duplicate fetch");
            return lastLocal;
        }
        String local = isRawMagnet(url) ? fetchParsedMagnet(url)
                : url.startsWith("magnet") ? addTorrentTask(Uri.parse(url)) : addThunderTask(url);
        if (local != null && local.contains("127.0.0.1")) {
            lastKey = url;
            lastLocal = local;
        }
        return local;
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

    private String addTorrentTask(Uri uri) throws Exception {
        File torrent = new File(uri.getPath());
        File parent = torrent.getParentFile();
        String name = uri.getQueryParameter("name");
        int index = Integer.parseInt(uri.getQueryParameter("index"));
        taskId = XLTaskHelper.get().addTorrentTask(torrent, parent, index);
        // 就绪门（见 waitDataReady）：等子任务真实已下载字节，不再状态非 0 就交地址
        waitDataReady(() -> XLTaskHelper.get().getBtSubTaskInfo(taskId, index).mTaskInfo);
        String local = XLTaskHelper.get().getLocalUrl(new File(parent, name));
        SpiderDebug.log("thunder", "bt task ready local=%s", local);
        return local;
    }

    private String addThunderTask(String url) throws Exception {
        File folder = Path.thunder(Util.md5(url));
        taskId = XLTaskHelper.get().addThunderTask(url, folder);
        // 就绪门（见 waitDataReady）：进度必须读原生任务信息的已下载字节——保存文件在建任务
        // 瞬间就被预分配成全长，读文件长度等于没等数据就放行（真机实证磁力/ed2k 双 0% 黑屏）
        waitDataReady(() -> XLTaskHelper.get().getNativeTaskInfo(taskId));
        String local = XLTaskHelper.get().getLocalUrl(taskId.getSaveFile());
        SpiderDebug.log("thunder", "task ready local=%s", local);
        return local;
    }

    private interface InfoSource {
        XLTaskInfo get();
    }

    // 就绪门：SDK 本地服务对未下载到的区间只挂起不吐数据，地址交早了播放器就是黑屏干等，
    // 所以放行必须建立在真实已下载字节上（信号来源见 addTorrentTask/addThunderTask，两条
    // 都读原生任务信息；保存文件长度是预分配全长，绝不能当进度）。
    // 放行条件：下完（size≥total）；或 ≥512KB 且最近 2 秒还在涨（源是活的，播放器自己会
    // 缓冲）；或 ≥4MB（缓冲已够起播）。旧版的"两次见涨就放行/3 秒不涨就放行"会被几 KB 的
    // 握手流量骗开门，对端一掉光就是 0KB/s 黑屏，已删。
    // 上限 25 秒：外层播放解析只给 30 秒（SiteViewModel.withTimeout），门必须在被掐之前
    // 自己收尾——到时已有 ≥512KB 就交地址试播，不足则抛"连接超时"真错误上屏，不留假象。
    // 任务真失败（status==3）立即抛真错误，不把死地址交给播放器。
    private void waitDataReady(InfoSource source) throws Exception {
        long lastSize = -1;
        long size = 0;
        long total = 0;
        long lastGrow = SystemClock.elapsedRealtime();
        for (int i = 0; i < 250; i++) {
            XLTaskInfo info = source.get();
            if (info != null && info.mTaskStatus == 3) throw new ExtractException(info.getErrorMsg());
            size = info == null ? 0 : info.mDownloadSize;
            total = info == null ? 0 : info.mFileSize;
            long now = SystemClock.elapsedRealtime();
            if (size > lastSize) lastGrow = now;
            if (total > 0 && size >= total) {
                SpiderDebug.log("thunder", "data complete size=%d", size);
                return;
            }
            if (size >= 512_000 && (size >= 4_000_000 || now - lastGrow <= 2000)) {
                SpiderDebug.log("thunder", "data ready size=%d total=%d", size, total);
                return;
            }
            lastSize = size;
            if (i % 50 == 49) SpiderDebug.log("thunder", "waiting data size=%d total=%d status=%d", size, total, info == null ? -1 : info.mTaskStatus);
            SystemClock.sleep(100);
        }
        if (size >= 512_000) {
            SpiderDebug.log("thunder", "data slow size=%d total=%d, hand url anyway", size, total);
            return;
        }
        throw new ExtractException(ResUtil.getString(R.string.error_play_timeout));
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
