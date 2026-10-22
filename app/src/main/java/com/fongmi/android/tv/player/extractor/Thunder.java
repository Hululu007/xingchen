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
import com.xunlei.downloadlib.parameter.BtSubTaskDetail;
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

    // 交地址时实际落盘的文件：addTorrentTask 里传给 getLocalUrl 的 new File(parent, name)，
    // addThunderTask 里 taskId.getSaveFile()。lastLocal 本身是 127.0.0.1 本地地址串，
    // 不能从它反推落盘路径，所以另记一份；复用判断只认这份记录，不凭空拼路径。
    // 只在 fetchInternal 把地址写进 lastKey/lastLocal 时才配对有效，否则清掉。
    private File lastDownloadFile;

    // 进行中的 fetch 合流：推送每次播放都会触发两路并发解析（18:02 日志实证两路相隔 20ms，
    // 共用 Source 单例里的同一个 Thunder 实例）。后到的一路如果另建 native 任务，会写同一
    // 目录互抢、覆盖 taskId 字段、随后 stop() 删掉的也是后建的任务，先建的反而泄漏。
    // 所以同 URL 正在 fetch 时后到者直接等先到者的结果，不建第二个任务。
    private final ConcurrentHashMap<String, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();

    @Override
    public String fetch(String url) throws Exception {
        if (url == null) return fetchInternal(url);
        // 入口先归一（与 Parser.normalizeScheme 同款：去首尾空白、scheme 段小写）。
        // match() 认 scheme 时是大小写不敏感的，但下面所有分流判断都是大小写敏感的
        // 精确前缀：推送粘贴来的 Magnet:/ED2K: 变体能骗过 match、却落不进任何一条
        // 建任务的路，最后掉进 addThunderTask——那里只认 ftp/ed2k 小写前缀，
        // 磁力根本不建原生任务，却照样交出本地地址，播放器对着空任务永远 0 字节
        // （1.3.13 真机日志实证：四次尝试全是 status=1 speed=0 size=0；
        // 同种子走正规 magnet:// 播放地址那条路当场出速度）。
        url = normalizeScheme(url);
        synchronized (this) {
            // 字节门禁：只复用已有字节的活任务；0 字节不复用，落到 fetchInternal
            // 清洗重建，再由 addTorrentTask 的字节门禁裁决。有字节的口径与交地址
            // 门禁对齐：父计数 >0 或落盘文件长度 >0 任一成立即算有字节。
            if (sameContent(url, lastKey) && lastLocal != null && isTaskAlive() && !zeroMark(url).exists() && hasDownloadedBytes()) {
                SpiderDebug.log("thunder", "reuse live task, skip duplicate fetch");
                return lastLocal;
            }
        }
        String inflightKey = contentKey(url);
        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> first = inflight.putIfAbsent(inflightKey, mine);
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
            inflight.remove(inflightKey, mine);
        }
    }

    private synchronized String fetchInternal(String url) throws Exception {
        // 零字节卡死任务的干净重建：上一轮播放被进度监控记下「交地址后 30 秒仍 0 字节」
        // 的链接（zeroMark），这次先把旧任务与它的目录整个清掉再重建——1.3.11 日志实证：
        // 卡死的 ed2k 任务状态恒 1、速度恒 0，靠状态判断永远不会被清理，进程内外都复用
        // 同一个尸体，播放器对着本地地址永远超时。只在 fetch 入口、交地址之前清，
        // 不碰正在播的任务（有字节在走的任务监控会把标记撤掉）。
        if (url != null && !url.startsWith("magnet") && zeroMark(url).exists()) wipeZeroMarked(url);
        // 磁力同理（此前被排除在外）：卡死在状态 1、0 字节的 BT 任务状态永远不变，
        // 同链接复用会把尸体一直递给播放器。见标记先停删内存里的同内容任务再重建；
        // 只删任务不删种子文件目录——addTorrentTask 要靠种子文件建任务。
        if (url != null && url.startsWith("magnet") && zeroMark(url).exists()) {
            if (sameContent(url, lastKey) && taskId != null) cleanupStaleTask();
            zeroMark(url).delete();
            SpiderDebug.log("thunder", "magnet zero-byte history wiped, rebuilding clean");
        }
        // 同链接复用：旧任务未明确报错（原生状态非 3）就直接复用，禁止停删重建——
        // 1.3.8 日志实证（00:32:20）：60ms 内三连 handed，播放器攥着第一份地址，
        // 后到的 fetch 把先建任务当死任务杀掉重建，播放器对着已删任务的地址永远 0 字节。
        // FongMi 原版无此清理层、从不杀同链接任务，故一直能播。
        int ns = nativeStatus();
        // 状态单次读数偶发误报 3（出错）：任务已下出字节且没被零字节监控记账时，以字节为准
        // （字节口径与复用条件一致：父计数或落盘长度任一 >0）。
        if (ns == 3 && hasDownloadedBytes() && (url == null || !zeroMark(url).exists())) ns = 1;
        if (url != null && sameContent(url, lastKey) && lastLocal != null && taskId != null && (ns == 0 || ns == 1 || ns == 2)) {
            if (hasDownloadedBytes()) {
                SpiderDebug.log("thunder", "reuse same-url task, no rebuild status=%d", ns);
                lastKey = url;
                return lastLocal;
            }
            // 同链接任务 0 字节：不复用死地址，先停删再由下面流程重建，
            // 重建后仍 0 字节由 addTorrentTask 的字节门禁裁决。
            SpiderDebug.log("thunder", "reuse blocked: same-url task zero bytes status=%d, cleanup and rebuild", ns);
            cleanupStaleTask();
        }
        // 串行重试卫生：仅当原生状态明确为 3（出错）才停删旧任务再建新任务。
        if (taskId != null && ns == 3) cleanupStaleTask();
        // 换链接：旧任务先停删再建新的，避免多任务并存抢带宽与目录
        // （同链接的 stop 保护靠这里兜底，任务不会一直攒着）。
        // 换不换只认内容身份（磁力认 btih 指纹）：同磁力不同字符串写法不算换链接，
        // 否则正在下载的任务会被第二路 fetch 当「旧链接」停删（1.3.12 真机日志实证）。
        if (taskId != null && url != null && !sameContent(url, lastKey)) cleanupStaleTask();
        // 已解析磁力路不走 Parser，不占解析预算，传 0。
        String local = isRawMagnet(url) ? fetchParsedMagnet(url)
                : url.startsWith("magnet") ? addTorrentTask(Uri.parse(url), url, 0) : addThunderTask(url);
        if (local != null && local.contains("127.0.0.1")) {
            lastKey = url;
            lastLocal = local;
        } else {
            // 未进缓存的地址不配对落盘文件，避免旧 lastLocal 配新文件误复用。
            lastDownloadFile = null;
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

    // 复用判断的活任务：只认原生状态 0/1/2；-1（读不到）不算活——读不到就重建，
    // 不许把死任务的地址递给播放器（之前 -1 默认活，死地址复用就是 00:00 无报错的来源之一）。
    // 这里只管复用，不管删任务（删任务走 cleanupStaleTask），不存在误杀在用任务。
    private boolean isTaskAlive() {
        if (taskId == null) return false;
        int status = nativeStatus();
        return status == 0 || status == 1 || status == 2;
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
        lastDownloadFile = null;
        SpiderDebug.log("thunder", "cleaned stale task before rebuild status=%d", status);
    }

    // 与 Parser.normalizeScheme 同款：只归一 scheme 段，不动冒号后的内容
    private static String normalizeScheme(String raw) {
        String u = raw == null ? "" : raw.trim();
        int colon = u.indexOf(':');
        if (colon > 0) u = u.substring(0, colon).toLowerCase(Locale.ROOT) + u.substring(colon);
        return u;
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
        // 裸磁力预算计时：从 Parser 同步解析开始记，含 waitDone（最长约 10 秒），
        // 解析耗时传给 addTorrentTask 扣减等字节预算，不由等字节循环全额重算。
        long startMs = SystemClock.elapsedRealtime();
        List<Episode> episodes = Parser.get(url).call();
        if (episodes.isEmpty()) throw new ExtractException("磁力解析失败，请重试");
        String parsed = episodes.get(0).getUrl();
        return parsed.startsWith("magnet") ? addTorrentTask(Uri.parse(parsed), url, SystemClock.elapsedRealtime() - startMs) : parsed;
    }

    // 照上游：状态 3 抛真错；状态非 0 即交本地地址，播放器自己拉流边下边播，不等字节
    // elapsedMs：调用方在 Parser 侧已耗的毫秒数（已解析路传 0），用于扣减等字节预算。
    private String addTorrentTask(Uri uri, String markUrl, long elapsedMs) throws Exception {
        File torrent = new File(uri.getPath());
        File parent = torrent.getParentFile();
        String name = uri.getQueryParameter("name");
        int index;
        try {
            index = Integer.parseInt(uri.getQueryParameter("index"));
        } catch (Exception e) {
            throw new ExtractException("选集地址不完整，请重新打开详情再播");
        }
        // 种子文件是解析时保存的那份：不存在/空文件时建任务必空转——
        // 原先一路走到 helper 里 mSubFileInfo 为 null 再 NPE，ViewModel 把非
        // ExtractException 吞成空结果，界面只剩通用错、根因全丢。
        // 这里先验先说：文件没了就明说重开详情（重开详情会重新解析补回种子文件）。
        if (parent == null || name == null || !torrent.exists() || torrent.length() == 0) {
            SpiderDebug.log("thunder", "bt torrent file invalid path=%s parent=%s name=%s", torrent.getAbsolutePath(), parent, name);
            throw new ExtractException("种子文件已失效，请重新打开详情再播");
        }
        taskId = XLTaskHelper.get().addTorrentTask(torrent, parent, index);
        SpiderDebug.log("thunder", "bt task created torrent=%s index=%d", torrent.getAbsolutePath(), index);
        // 字节门禁：状态非 0 只说明任务已建/在下，不代表已有字节可播。
        // 状态 1 + 速度 0 + 已下 0 字节时旧逻辑照样交地址，播放器进 BUFFERING 后
        // 30 秒启动超时被取消，界面永远 00:00 且无报错。改为：只有引擎计数
        // mDownloadSize > 0 或子任务落盘文件已有字节才交地址，否则继续等；
        // maxRounds 次跑完仍 0 字节，抛带 status/speed/size 的真错，不再递死地址。
        // 总预算：ViewModel 30 秒超时内留余量，取 25000ms；已解析路 elapsedMs=0
        // 时上限仍封顶 200 次（约 20 秒，与旧行为一致），裸磁力路解析已耗约
        // 10 秒时剩余约 15000ms，自动缩到约 150 次以下（解析耗时更长则更少；
        // 已耗超 25000ms 时 0 次，建任务后立即抛错，不静默等满一轮）。
        long remainingMs = 25000L - Math.max(0L, elapsedMs);
        int maxRounds = (int) Math.max(0L, Math.min(200L, remainingMs / 100L));
        int lastStatus = 0;
        long lastSpeed = 0;
        long lastSize = 0;
        for (int i = 0; i < maxRounds; i++) {
            if (Thread.currentThread().isInterrupted()) throw new ExtractException("迅雷任务被中断，请重试");
            BtSubTaskDetail detail = XLTaskHelper.get().getBtSubTaskInfo(taskId, index);
            if (detail == null || detail.mTaskInfo == null) throw new ExtractException("迅雷子任务信息为空，请重试");
            XLTaskInfo info = detail.mTaskInfo;
            lastStatus = info.mTaskStatus;
            lastSpeed = info.mDownloadSpeed;
            lastSize = info.mDownloadSize;
            if (info.mTaskStatus == 3) {
                String err = info.getErrorMsg();
                if (err == null || err.isEmpty()) err = "迅雷任务出错，请重试";
                throw new ExtractException(err);
            }
            if (info.mTaskStatus != 0) {
                File downloadFile = new File(parent, name);
                long onDisk = downloadFile.length();
                if (info.mDownloadSize > 0 || onDisk > 0) {
                    lastDownloadFile = downloadFile;
                    String local = XLTaskHelper.get().getLocalUrl(downloadFile);
                    SpiderDebug.log("thunder", "bt task handed local=%s status=%d speed=%d size=%d", local, info.mTaskStatus, info.mDownloadSpeed, info.mDownloadSize);
                    // 本地地址是播放的唯一入口：引擎本地服务没起来时 getLocalUrl 回空，
                    // 原先空地址一路递到播放器、界面只剩通用错。这里明说，不递空地址。
                    if (local == null || local.isEmpty()) {
                        throw new ExtractException("迅雷本地地址为空，请重试");
                    }
                    startProgressLog(taskId, markUrl, index);
                    return local;
                }
                // 0 字节：不交地址，继续循环等首字节。
            }
            if (i % 50 == 49) SpiderDebug.log("thunder", "bt waiting status=%d speed=%d size=%d", info.mTaskStatus, info.mDownloadSpeed, info.mDownloadSize);
            SystemClock.sleep(100);
        }
        throw new ExtractException(String.format(Locale.ROOT, "磁力任务%d毫秒仍未下载到字节，无法起播（status=%d speed=%d size=%d），请换源或重试", maxRounds * 100L, lastStatus, lastSpeed, lastSize));
    }

    // ed2k/thunder 直建任务的字节门禁（10-22 日志实证补）：上游零等待交地址，
    // 死任务（有元数据、无字节流）会让播放器永远 BUFFERING 且界面无报错——
    // 15:45/15:55 两轮推送全是这条路（task handed 无 bt 前缀、status=1 speed=0 size=0，
    // 本地代理 206 头对、body 一字节不来；且该链接此前已被零字节标记，属重复死任务）。
    // 改与磁力 BT 路同款门禁：只在引擎计数或落盘文件已有字节时交地址；
    // 200 轮（20 秒）仍 0 字节抛带状态的真错，不递死地址。
    // 健康任务首字节通常 1 秒内到，门禁不拖慢正常起播；预算卡在 ViewModel 30 秒超时内。
    private String addThunderTask(String url) {
        File folder = Path.thunder(Util.md5(url));
        taskId = XLTaskHelper.get().addThunderTask(url, folder);
        File saveFile = taskId.getSaveFile();
        int lastStatus = 0;
        long lastSpeed = 0;
        long lastSize = 0;
        for (int i = 0; i < 200; i++) {
            if (Thread.currentThread().isInterrupted()) throw new ExtractException("迅雷任务被中断，请重试");
            XLTaskInfo info = null;
            try {
                info = XLTaskHelper.get().getNativeTaskInfo(taskId);
            } catch (Exception ignored) {
            }
            if (info != null) {
                lastStatus = info.mTaskStatus;
                lastSpeed = info.mDownloadSpeed;
                lastSize = info.mDownloadSize;
                if (info.mTaskStatus == 3) {
                    String err = info.getErrorMsg();
                    if (err == null || err.isEmpty()) err = "迅雷任务出错，请重试";
                    throw new ExtractException(err);
                }
                if (info.mTaskStatus != 0) {
                    long onDisk = saveFile == null ? 0 : saveFile.length();
                    if (info.mDownloadSize > 0 || onDisk > 0) {
                        String local = XLTaskHelper.get().getLocalUrl(saveFile);
                        SpiderDebug.log("thunder", "task handed local=%s status=%d speed=%d size=%d", local, info.mTaskStatus, info.mDownloadSpeed, info.mDownloadSize);
                        if (local == null || local.isEmpty()) {
                            throw new ExtractException("迅雷本地地址为空，请重试");
                        }
                        lastDownloadFile = saveFile;
                        startProgressLog(taskId, url);
                        return local;
                    }
                }
            }
            if (i % 50 == 49) SpiderDebug.log("thunder", "thunder waiting status=%d speed=%d size=%d", lastStatus, lastSpeed, lastSize);
            SystemClock.sleep(100);
        }
        throw new ExtractException(String.format(Locale.ROOT, "迅雷任务%d毫秒仍未下载到字节，无法起播（status=%d speed=%d size=%d），请换源或重试", 200 * 100L, lastStatus, lastSpeed, lastSize));
    }

    // 零字节标记：交地址后持续 0 字节的链接在 thunder 根目录留一个标记文件，
    // 下次 fetch 见标记先干净重建（见 fetchInternal 的 wipeZeroMarked）。
    private static File zeroMark(String url) {
        // 标记按内容身份记账：同磁力换个字符串写法进来也要能认出这笔账
        return new File(Path.thunder(), "zero-" + Util.md5(contentKey(url)));
    }

    // —— 内容身份：同一条磁力从不同入口进来，字符串常不同（参数顺序、附加参数、编码），
    // 按原文比对会把同内容误判成换链接。磁力认 btih 指纹、电驴认文件哈希，其余用原文。
    private static String contentKey(String url) {
        if (url == null) return "";
        String hash = btihOf(url);
        if (hash != null) return "btih:" + hash;
        hash = ed2kHashOf(url);
        if (hash != null) return "ed2k:" + hash;
        return url;
    }

    private static boolean sameContent(String a, String b) {
        if (a == null || b == null) return false;
        return a.equals(b) || contentKey(a).equals(contentKey(b));
    }

    private static String btihOf(String url) {
        String low = url.toLowerCase(java.util.Locale.ROOT);
        int i = low.indexOf("xt=urn:btih:");
        if (i < 0) return null;
        int start = i + "xt=urn:btih:".length();
        int end = start;
        while (end < url.length()) {
            char c = url.charAt(end);
            if (c == '&' || c == '#' || c == ' ') break;
            end++;
        }
        String hash = url.substring(start, end).trim().toLowerCase(java.util.Locale.ROOT);
        return hash.isEmpty() ? null : hash;
    }

    private static String ed2kHashOf(String url) {
        if (!url.startsWith("ed2k://")) return null;
        String[] parts = url.split("\\|");
        // ed2k://|file|名称|大小|哈希|.../ —— 第 5 段是文件哈希
        return parts.length >= 5 && !parts[4].isEmpty()
                ? parts[4].toLowerCase(java.util.Locale.ROOT) : null;
    }

    /** 当前任务已下载字节（走原生信息，与 nativeStatus 同源）；读不到按 0。 */
    private long downloadedSize() {
        if (taskId == null) return 0;
        try {
            XLTaskInfo info = XLTaskHelper.get().getNativeTaskInfo(taskId);
            return info == null ? 0 : info.mDownloadSize;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 当前任务对应落盘文件的已有长度；无记录或文件不存在时按 0（File.length() 本身即如此）。 */
    private long onDiskSize() {
        return lastDownloadFile == null ? 0 : lastDownloadFile.length();
    }

    /**
     * 复用/纠错共用的“已有字节”口径：父计数或落盘长度任一 >0 即算有字节。
     * 父子计数语义离线证实不了：父任务 getNativeTaskInfo().mDownloadSize 是否聚合
     * BT 子任务，需真机日志核实（交地址处已打 status/speed/size）；在证实前双口径
     * 并联，不谎称父计数已闭环。
     */
    private boolean hasDownloadedBytes() {
        return downloadedSize() > 0 || onDiskSize() > 0;
    }

    private void wipeZeroMarked(String url) {
        try {
            zeroMark(url).delete();
            // 先把引擎侧的旧任务停删（含跨进程残留的同链接任务记录），再删目录，
            // 最后由调用方按原流程新建——保证新任务不挂任何旧状态。
            // 内存里的任务只动属于这条链接的那个；不属于时用同链接探针任务去删引擎侧记录，
            // 不碰可能正在播的别的链接。
            boolean mine = sameContent(url, lastKey);
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
                lastDownloadFile = null;
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
        startProgressLog(id, url, -1);
    }

    // btIndex >= 0 时按 BT 子任务口径读状态/已下量（磁力记账用，与 addTorrentTask 的轮询同口径）
    private void startProgressLog(GetTaskId id, String url, int btIndex) {
        if (id == null) return;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 24; i++) {
                SystemClock.sleep(5000);
                if (taskId != id) return;
                try {
                    XLTaskInfo info = btIndex >= 0
                            ? XLTaskHelper.get().getBtSubTaskInfo(id, btIndex).mTaskInfo
                            : XLTaskHelper.get().getNativeTaskInfo(id);
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
        lastDownloadFile = null;
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
        lastDownloadFile = null;
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
            // parse 回空任务／空地址时下面 getRealUrl 直接 NPE，而上游 ViewModel 只把
            // ExtractException 发成可见错，NPE 会被吞成空结果、界面静默 00:00。先验先说。
            String realUrl = taskId == null ? null : taskId.getRealUrl();
            if (realUrl == null) throw new ExtractException("磁力解析失败，请重试");
            if (!torrent && !realUrl.startsWith("magnet")) {
                SpiderDebug.log("thunder", "种子未解析出文件列表，回退单集 scheme=%s", u.contains(":") ? u.substring(0, u.indexOf(':')) : "");
                return Arrays.asList(create(taskId));
            }
            if (torrent && u.startsWith("http")) Download.create(u, taskId.getSaveFile()).get();
            if (!torrent) waitDone(taskId);
            try {
                return XLTaskHelper.get().getTorrentInfo(taskId.getSaveFile()).getMedias().stream().map(this::create).toList();
            } catch (ExtractException e) {
                throw e;
            } catch (Exception e) {
                // 种子信息取不到（元数据没到、SDK 回空）时原先 NPE 被上游吞成空结果、
                // 界面静默 00:00；转成可见错，不递空结果。
                throw new ExtractException("磁力解析失败，请重试");
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
