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
import java.util.Locale;
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
            if (url.equals(lastKey) && lastLocal != null && isTaskAlive() && !zeroMark(url).exists()) {
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
        // 零字节卡死任务的干净重建：上一轮播放被进度监控记下「交地址后 30 秒仍 0 字节」
        // 的链接（zeroMark），这次先把旧任务与它的目录整个清掉再重建——1.3.11 日志实证：
        // 卡死的 ed2k 任务状态恒 1、速度恒 0，靠状态判断永远不会被清理，进程内外都复用
        // 同一个尸体，播放器对着本地地址永远超时。只在 fetch 入口、交地址之前清，
        // 不碰正在播的任务（有字节在走的任务监控会把标记撤掉）。
        if (url != null && !url.startsWith("magnet") && zeroMark(url).exists()) wipeZeroMarked(url);
        // 同链接复用：旧任务未明确报错（原生状态非 3）就直接复用，禁止停删重建——
        // 1.3.8 日志实证（00:32:20）：60ms 内三连 handed，播放器攥着第一份地址，
        // 后到的 fetch 把先建任务当死任务杀掉重建，播放器对着已删任务的地址永远 0 字节。
        // FongMi 原版无此清理层、从不杀同链接任务，故一直能播。
        int ns = nativeStatus();
        if (url != null && url.equals(lastKey) && lastLocal != null && taskId != null && ns != 3) {
            SpiderDebug.log("thunder", "reuse same-url task, no rebuild status=%d", ns);
            return lastLocal;
        }
        // 串行重试卫生：仅当原生状态明确为 3（出错）才停删旧任务再建新任务。
        if (taskId != null && ns == 3) cleanupStaleTask();
        // 换链接：旧任务先停删再建新的，避免多任务并存抢带宽与目录
        // （同链接的 stop 保护靠这里兜底，任务不会一直攒着）。
        if (taskId != null && url != null && !url.equals(lastKey)) cleanupStaleTask();
        String local = isRawMagnet(url) ? fetchParsedMagnet(url)
                : url.startsWith("magnet") ? addTorrentTask(Uri.parse(url)) : addThunderTask(url);
        if (local != null && local.contains("127.0.0.1")) {
            lastKey = url;
            lastLocal = local;
        }
        return local;
    }

    // 原生任务状态：走 getNativeTaskInfo 绕开 getTaskInfo 的状态合成（存档文件存在即报 2，会误判）；
    // 读不到返回 -1。0 等待 / 1 下载中 / 2 已完成 / 3 出错。
    private int nativeStatus() {
        if (taskId == null) return -1;
        try {
            XLTaskInfo info = XLTaskHelper.get().getNativeTaskInfo(taskId);
            return info == null ? -1 : info.getTaskStatus();
        } catch (Exception e) {
            return -1;
        }
    }

    // 任务是否还活着：原生状态 0/1/2 算活；-1 读不到不轻易判死（默认活，避免误杀在用任务）
    private boolean isTaskAlive() {
        if (taskId == null) return false;
        int status = nativeStatus();
        return status != 3;
    }

    // 清理死任务：stop + delete，不释放引擎（重建任务走热引擎，与上游每轮建任务一致）
    private void cleanupStaleTask() {
        int status = nativeStatus();
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
                startProgressLog(taskId, null);
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
        startProgressLog(taskId, url);
        return local;
    }

    // 零字节标记：交地址后持续 0 字节的链接在 thunder 根目录留一个标记文件，
    // 下次 fetch 见标记先干净重建（见 fetchInternal 的 wipeZeroMarked）。
    private static File zeroMark(String url) {
        return new File(Path.thunder(), "zero-" + Util.md5(url));
    }

    private void wipeZeroMarked(String url) {
        try {
            zeroMark(url).delete();
            // 先把引擎侧的旧任务停删（含跨进程残留的同链接任务记录），再删目录，
            // 最后由调用方按原流程新建——保证新任务不挂任何旧状态。
            // 内存里的任务只动属于这条链接的那个；不属于时用同链接探针任务去删引擎侧记录，
            // 不碰可能正在播的别的链接。
            boolean mine = url.equals(lastKey);
            try {
                GetTaskId stale = mine && taskId != null ? taskId : XLTaskHelper.get().addThunderTask(url, Path.thunder(Util.md5(url)));
                XLTaskHelper.get().stopTask(stale);
                XLTaskHelper.get().deleteTask(stale);
            } catch (Exception ignored) {
            }
            if (mine) {
                taskId = null;
                lastKey = null;
                lastLocal = null;
            }
            deleteRecursive(Path.thunder(Util.md5(url)));
            SpiderDebug.log("thunder", "zero-byte history wiped, rebuilding clean");
        } catch (Exception e) {
            SpiderDebug.log("thunder", "zero-byte wipe err=%s", String.valueOf(e.getMessage()));
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursive(child);
        file.delete();
    }

    // 交接后任务进度日志：每 5 秒记一次状态/速度/已下量，最长 2 分钟；
    // 任务被换掉、完成或出错即停。用来分清「任务被自己人杀掉」和「资源本身没速度」。
    // url 非空时（ed2k/thunder 直建任务）兼管零字节标记：有字节流动就撤标记；
    // 持续 30 秒 0 字节就落标记，供下次 fetch 干净重建。
    private void startProgressLog(GetTaskId id, String url) {
        if (id == null) return;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 24; i++) {
                SystemClock.sleep(5000);
                if (taskId != id) return;
                try {
                    XLTaskInfo info = XLTaskHelper.get().getNativeTaskInfo(id);
                    if (info == null) return;
                    SpiderDebug.log("thunder", "progress status=%d speed=%d size=%d", info.mTaskStatus, info.mDownloadSpeed, info.mDownloadSize);
                    if (url != null) {
                        if (info.mDownloadSize > 0) {
                            File mark = zeroMark(url);
                            if (mark.exists()) mark.delete();
                        } else if (i >= 5 && (info.mTaskStatus == 0 || info.mTaskStatus == 1)) {
                            File mark = zeroMark(url);
                            if (!mark.exists()) {
                                try {
                                    mark.createNewFile();
                                } catch (Exception ignored) {
                                }
                                SpiderDebug.log("thunder", "zero-byte task marked for clean rebuild");
                            }
                        }
                    }
                    if (info.mTaskStatus == 2 || info.mTaskStatus == 3) return;
                } catch (Exception e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @Override
    public synchronized void stop() {
        // 播放链每次 playerContent 都先 Source.stop()，推送播一个磁力会连调多次：
        // 若照旧删任务+删文件，最后交给播放器的地址立刻成死地址——先有点流量、
        // 然后 0 速、最后超时（1.3.9 真机两例）。同链接任务还活着时 stop 不杀，
        // 真正的清理放到下一次 fetch 换链接时（fetchInternal）与 exit 时做。
        int ns = nativeStatus();
        if (taskId != null && lastLocal != null && (ns == 0 || ns == 1)) {
            SpiderDebug.log("thunder", "stop skipped: task alive status=%d", ns);
            return;
        }
        lastKey = null;
        lastLocal = null;
        if (taskId == null) return;
        XLTaskHelper.get().deleteTask(taskId);
        XLTaskHelper.get().release();
        taskId = null;
    }

    @Override
    public void exit() {
        if (taskId != null) {
            try {
                XLTaskHelper.get().deleteTask(taskId);
            } catch (Exception ignored) {
            }
            taskId = null;
        }
        XLTaskHelper.get().release();
    }

    public record Parser(String url) implements Callable<List<Episode>> {

        private static final Pattern PATTERN = Pattern.compile("(?i)(magnet|thunder|ed2k):.*");

        // 粘贴来的磁力常带首尾空白或 scheme 大小写变体（Magnet:/MAGNET:），而下游
        // XLTaskHelper.parse 的 "magnet:?" 门限是大小写敏感的精确前缀：不变体归一
        // 就会被静默拦下、回退成单集原样（看起来像普通推送）。只归一 scheme 段，
        // 不动冒号后的内容（btih 的 base32 哈希大小写敏感）。
        private static String normalizeScheme(String raw) {
            String u = raw == null ? "" : raw.trim();
            int colon = u.indexOf(':');
            if (colon > 0) u = u.substring(0, colon).toLowerCase(Locale.ROOT) + u.substring(colon);
            return u;
        }

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
            String u = normalizeScheme(url);
            boolean torrent = isTorrent(u);
            if (u.startsWith("ed2k:")) {
                return Arrays.asList(Episode.create(parseEd2kName(u), u));
            }
            GetTaskId taskId;
            try {
                taskId = XLTaskHelper.get().parse(u, Path.thunder(Util.md5(u)));
            } catch (Exception e) {
                throw new ExtractException("磁力解析超时，请检查网络后重试");
            }
            if (!torrent && !taskId.getRealUrl().startsWith("magnet")) {
                SpiderDebug.log("thunder", "种子未解析出文件列表，回退单集 scheme=%s", u.contains(":") ? u.substring(0, u.indexOf(':')) : "");
                return Arrays.asList(create(taskId));
            }
            if (torrent && u.startsWith("http")) Download.create(u, taskId.getSaveFile()).get();
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
