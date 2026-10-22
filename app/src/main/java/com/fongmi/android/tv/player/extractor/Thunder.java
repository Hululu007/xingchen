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
import com.github.catvod.runtime.ThunderRuntime;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

public class Thunder implements Source.Extractor {

    private ThunderRuntime.Task taskId;
    private final ThunderGateway gateway = ThunderGateway.get();

    // 运行时只经网关取：外置优先、直连回落（见 ThunderGateway）。
    // Parser 是 record（静态成员），拿不到外层实例字段，一律用 ThunderGateway.get()。
    private ThunderRuntime runtime() throws ExtractException {
        return gateway.runtime();
    }

    @Override
    public boolean match(Uri uri) {
        // 上游 SCHEMES = magnet／ed2k／ftp；fork 修补批加的 thunder:// 保留（已落地，不回退），
        // UrlUtil.scheme 已做小写归一（Magnet: 变体修补保留）。
        return List.of("magnet", "ed2k", "ftp", "thunder").contains(UrlUtil.scheme(uri));
    }

    // 上游形（FongMi/TV e5ec0c60f Thunder.java，全文 191 行）：
    // fetch 不复用、不合流、不清洗——每一次 fetch 都经 createTask 重新建任务；
    // 清理层（零字节标记清洗、同链接 0 字节停删重建、状态 3 停删、换链接停删、
    // 入口有字节复用、双路合流 70 秒等待）全部拆掉，上游没有这些。
    // 接线（外置包形态）：本文件不再直接碰迅雷类，一律经 ThunderGateway 取
    // ThunderRuntime——先试 RuntimeModule.THUNDER.entry(ThunderRuntime.class)
    // （外置包 com.fongmi.runtime.thunder，兼容 id catvod-thunder-plugin-v1，
    // verifyPackage 与宿主签名比对，createEntry 拒绝已编进宿主的实现），
    // 未接通时回落反射直连（过渡编包里宿主仍含 :thunder 时才成立）。
    // 结构（fetch／先停旧再建新 createTask／getSelection containment／
    // stopFailedTask taskId==task 第二道闸／Parser.match ^ 锚定）与上游原文一一对应；
    // 只有状态取值经网关：直连父状态仍走 getNativeTaskInfo 原生值（绕开
    // getTaskInfo 的“存档存在即报 2”合成），外置路只有接口 Status，父状态
    // 取 getTaskInfo、速度与字节填 -1（未知，不编假数）。
    // 定位日志（上游 Thunder.java 本无日志；上一批按上游删过，本批为真机查磁力
    // 加回，只记日志、不改行为）：① createTask 内 "bt task created ..."；
    // ② waitTorrentTask 内 "bt task handed local=%s status=%d speed=%d size=%d"。
    // 其余日志不在上游删单内，保留。
    @Override
    public String fetch(String url) throws Exception {
        // 上游 fetch 第一步判中断；偏离上游一处（可见错保留）：上游中断抛
        // CancellationException，fork 的 ViewModel 会把 CancellationException 静默
        // return（对照报告 B2），故中断仍抛 ExtractException 可见错，不照搬。
        if (Thread.currentThread().isInterrupted()) throw new ExtractException("迅雷任务被中断，请重试");
        if (url == null) throw new ExtractException("迅雷本地地址为空，请重试");
        return dispatch(normalizeScheme(url));
    }

    // 分流：裸磁力先经 Parser 展开（fork 保留路，上游 fetch 只认已解析选集地址）；
    // 磁力选集路经 getSelection → createTask → waitTorrentTask；
    // thunder:// 经 fetchThunder 解码后复用本分流；其余（ed2k／ftp）直建零等待交地址。
    private String dispatch(String url) throws Exception {
        url = normalizeScheme(url);
        if (isRawMagnet(url)) return fetchParsedMagnet(url);
        if (url.startsWith("magnet")) {
            Selection selection = getSelection(Uri.parse(url));
            ThunderRuntime.Task task = createTask(url, selection);
            try {
                return waitTorrentTask(task, selection);
            } catch (Exception e) {
                stopFailedTask(task, e);
                throw e;
            }
        }
        if (url.startsWith("thunder")) return fetchThunder(url);
        ThunderRuntime.Task task = createTask(url, null);
        try {
            synchronized (this) {
                if (taskId != task || Thread.currentThread().isInterrupted()) throw new ExtractException("迅雷任务被中断，请重试");
                // 树适配：旧 fork 直连 Task 有 getSavePath()，网关 Task 只有 getSaveFile()，同义判空。
                if (task.getFileName() == null || task.getSaveFile() == null) throw new ExtractException("迅雷本地地址为空，请重试");
                String local = runtime().getLocalUrl(task.getSaveFile());
                SpiderDebug.log("thunder", "task handed local=%s", local);
                if (local == null || local.isEmpty()) throw new ExtractException("迅雷本地地址为空，请重试");
                return local;
            }
        } catch (Exception e) {
            stopFailedTask(task, e);
            throw e;
        }
    }

    // 上游 createTask 原文结构：先停旧任务再建新任务；停旧只删任务、不 release。
    // 与 SiteApi 开头 stop() 的联动（不互杀）：playerContent 开头 stop() 先清上一轮
    // 任务（此时 taskId 已空），本方法再停一次是空操作；本方法停旧发生在建新之前，
    // 绝不删刚建的任务；新 fetch 顶掉旧 fetch 时，旧路在 wait 轮次里因 taskId != task
    // 抛可见错，不对着旧任务交地址。
    private synchronized ThunderRuntime.Task createTask(String url, Selection selection) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new ExtractException("迅雷任务被中断，请重试");
        stop();
        if (selection != null) {
            taskId = runtime().addTorrentTask(selection.torrent(), selection.torrent().getParentFile(), selection.index());
            // 定位日志（只记日志）：BT 任务建好即记，建任务失败抛错前不记。
            SpiderDebug.log("thunder", "bt task created torrent=%s index=%d", selection.torrent().getPath(), selection.index());
            return taskId;
        }
        taskId = runtime().addThunderTask(url, Path.thunder(Util.md5(url)));
        // 定位日志（只记日志）：直建路（ed2k／ftp）同样记建任务行，便于真机区分
        // “任务根本没建”与“建了但没交地址”。
        SpiderDebug.log("thunder", "bt task created url=%s", url);
        return taskId;
    }

    // 上游 stopFailedTask 原文形：只当失败任务仍是当前任务时才停；已被新 fetch
    // 顶掉的任务不碰——失败旧路绝不杀新路的任务。
    private synchronized void stopFailedTask(ThunderRuntime.Task task, Exception error) {
        if (taskId == task) {
            try {
                stop();
            } catch (Exception cleanup) {
                error.addSuppressed(cleanup);
            }
        }
    }

    private record Selection(File torrent, File media, int index) {
    }

    // 上游 getSelection 原文校验：种子文件须在 thunder 目录内、播出文件须在种子父目录内
    //（canonical 路径 containment，防 ../ 逃逸）。
    // 偏离上游两处（均为可见错保留）：上游非法选集抛 IllegalArgumentException／
    // NumberFormatException，fork 仍转 ExtractException（非 ExtractException 会被上层
    // 吞成空结果、界面静默）；种子文件不存在／空文件先验是上一批已落地的可见错修补，保留。
    private Selection getSelection(Uri uri) throws Exception {
        String path = uri.getPath();
        String name = uri.getQueryParameter("name");
        int index;
        try {
            index = Integer.parseInt(uri.getQueryParameter("index"));
        } catch (Exception e) {
            throw new ExtractException("选集地址不完整，请重新打开详情再播");
        }
        if (path == null || path.isEmpty() || name == null || name.isEmpty() || index < 0) {
            throw new ExtractException("选集地址不完整，请重新打开详情再播");
        }
        File torrent;
        File parent;
        File media;
        try {
            torrent = new File(path).getCanonicalFile();
            parent = torrent.getParentFile();
            String root = Path.thunder().getCanonicalPath() + File.separator;
            if (parent == null || !parent.getPath().startsWith(root)) throw new ExtractException("种子文件已失效，请重新打开详情再播");
            media = new File(parent, name).getCanonicalFile();
            if (!media.getPath().startsWith(parent.getPath() + File.separator)) throw new ExtractException("种子文件已失效，请重新打开详情再播");
        } catch (ExtractException e) {
            throw e;
        } catch (Exception e) {
            throw new ExtractException("种子文件已失效，请重新打开详情再播");
        }
        if (!torrent.exists() || torrent.length() == 0) throw new ExtractException("种子文件已失效，请重新打开详情再播");
        return new Selection(torrent, media, index);
    }

    // 上游 waitTorrentTask 原文结构：100 轮 × 100ms；父任务状态 3 抛错、子任务状态 3 抛错、
    // 子任务状态非 0 立即交 getLocalUrl（不等字节；已完成且 0 字节也立即交，不另抛）。
    // 父状态直连走 getNativeTaskInfo（绕开 getTaskInfo 的“存档存在即报 2”合成，否则父状态 3 永不可见），
    // 外置路没有这个口子，取接口 getTaskInfo（见网关 StatusEx 注释）。
    // 每轮先核 taskId == task：任务已被新 fetch／stop() 顶掉时立即抛可见错，不对着旧任务交地址。
    private String waitTorrentTask(ThunderRuntime.Task task, Selection selection) throws Exception {
        for (int i = 0; i < 100; i++) {
            synchronized (this) {
                if (taskId != task || Thread.currentThread().isInterrupted()) throw new ExtractException("迅雷任务被中断，请重试");
                ThunderGateway.StatusEx parentInfo = null;
                try {
                    parentInfo = gateway.taskStatus(task);
                } catch (Exception ignored) {
                }
                if (parentInfo != null && parentInfo.taskStatus() == 3) {
                    String err = parentInfo.errorMessage();
                    throw new ExtractException(err == null || err.isEmpty() ? "迅雷任务出错，请重试" : err);
                }
                ThunderGateway.StatusEx info = gateway.btSubTaskStatus(task, selection.index());
                if (info == null) throw new ExtractException("迅雷子任务信息为空，请重试");
                if (info.taskStatus() == 3) {
                    String err = info.errorMessage();
                    throw new ExtractException(err == null || err.isEmpty() ? "迅雷任务出错，请重试" : err);
                }
                if (info.taskStatus() != 0) {
                    String local = runtime().getLocalUrl(selection.media());
                    if (local == null || local.isEmpty()) throw new ExtractException("迅雷本地地址为空，请重试");
                    // 定位日志（只记日志）：状态非 0 交地址即记，含当时速度／已下字节，
                    // 真机查磁力靠“有 created 无 handed”定绞杀／字节门禁。
                    // 外置路速度与字节未知，记 -1，不编假数。
                    SpiderDebug.log("thunder", "bt task handed local=%s status=%d speed=%d size=%d", local, info.taskStatus(), info.downloadSpeed(), info.downloadSize());
                    return local;
                }
            }
            SystemClock.sleep(100);
        }
        throw new ExtractException(ResUtil.getString(R.string.error_play_timeout));
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
     * 直接走 getSelection 会因取不到 path/index 而抛错。
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
        String parsed = normalizeScheme(episodes.get(0).getUrl());
        if (!parsed.startsWith("magnet")) return parsed;
        Selection selection = getSelection(Uri.parse(parsed));
        ThunderRuntime.Task task = createTask(parsed, selection);
        try {
            return waitTorrentTask(task, selection);
        } catch (Exception e) {
            stopFailedTask(task, e);
            throw e;
        }
    }

    // fetchThunder 保留的覆盖点（上游链确实覆盖不到处）：上游 thunder:// 只走
    // Parser 展开路——Parser.call → ThunderRuntime.parse → XLTaskFactory.parse
    // 第一步 manager.parserThunderUrl 解码，再由 Source.fetch 对展开后的地址起播；
    // 上游 fetch() 本身不解 thunder://（selection 为空时 addThunderTask 只认
    // ftp/ed2k，thunder:// 会抛 Unsupported）。本方法只补“起播入口直接拿到裸
    // thunder://、未经 Parser 展开”这一路：解码 primitive 与 XLTaskFactory.parse
    // 第一步相同（manager.parserThunderUrl），解出 http(s) 直接交、解出磁力/ed2k/ftp
    // 复用 dispatch；其余 thunder:// 形态一律走 Parser 展开，不在此另起炉灶。
    private String fetchThunder(String url) throws Exception {
        ThunderRuntime.Task decoded;
        try {
            decoded = runtime().parse(url, Path.thunder(Util.md5(url)));
        } catch (Exception e) {
            throw new ExtractException("迅雷链接解析失败，请重试");
        }
        String real = decoded == null ? null : decoded.getRealUrl();
        // 首个 parse 只用来解码：解出 real 串后立即停删，不留野任务。
        // 下游裸磁力还要经 Parser 再 parse 一次、再由 createTask 建 BT 任务；
        // 首个任务若不清掉，会与下游任务并存，且从未进 taskId 字段、stop() 也够不着。
        // 这里只动局部解码任务，不动字段任务与 stop()，下游建任务路原样不变。
        if (decoded != null) {
            try {
                runtime().stopTask(decoded);
                runtime().deleteTask(decoded);
            } catch (Exception e) {
                SpiderDebug.log("thunder", "thunder decode task cleanup err=%s", String.valueOf(e.getMessage()));
            }
        }
        if (real == null || real.isEmpty() || real.startsWith("thunder")) {
            SpiderDebug.log("thunder", "thunder url decode failed url=%s real=%s", url, real);
            throw new ExtractException("迅雷链接解析失败，请重试");
        }
        real = normalizeScheme(real);
        if (real.startsWith("http://") || real.startsWith("https://")) return real;
        return dispatch(real);
    }

    // 直建路已并入 dispatch + createTask：createTask 建任务后 dispatch 零等待立即交
    // 本地地址，不等字节、不轮询状态；空本地地址仍抛可见错，不递空地址。
    // 上游没有的交地址后进度日志（5 秒线程）与零字节标记（zero-* 文件、wipe、
    // contentKey／sameContent／btih／ed2kHash、downloadedSize／hasDownloadedBytes）
    // 已全部删除，不留残留引用。
    // 上游 stop() 原文形（网关形）：只删任务、不 release、不留地址判断；
    // 上一批的 lastLocal 早返逻辑（未交地址时不删）随上游结构废除。
    // 注意：stop() 会删等待中的任务——这是上游行为；绞杀不再静默的原因是取链窗口
    // 已缩到“状态非 0 即交”（磁力最多 10 秒、直建零等待），且被顶掉的旧路在
    // wait／直建分支里因 taskId != task 抛可见错，不再被静默吞掉。
    // 唯一偏离：运行时已不在（外置包被卸、直连已切）时删不掉任务，只清字段、
    // 不把缺模块错抛给 Source.stop() 的切换链，否则切集会跟着崩。
    @Override
    public synchronized void stop() {
        if (taskId == null) return;
        ThunderRuntime.Task task = taskId;
        taskId = null;
        try {
            runtime().deleteTask(task);
        } catch (ExtractException ignored) {
            SpiderDebug.log("thunder", "stop without runtime, task dropped");
        }
    }

    // 上游 exit() 原文形：先 stop()，再 release 运行时。
    // releaseQuietly 只放已接通的运行时，不为 release 专门去接通一次。
    @Override
    public synchronized void exit() {
        try {
            stop();
        } finally {
            gateway.releaseQuietly();
        }
    }

    public record Parser(String url) implements Callable<List<Episode>> {

        // 上游 Parser.PATTERN 原文：^ 锚定＋大小写不敏感（find() 下 ^ 只认串首，
        // 串中夹带的 magnet:/thunder:/ed2k: 不再误认）。
        private static final Pattern PATTERN = Pattern.compile("^(magnet|thunder|ed2k):.*", Pattern.CASE_INSENSITIVE);

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

        private Episode create(ThunderRuntime.Task taskId) {
            return Episode.create(taskId.getFileName(), taskId.getRealUrl());
        }

        private Episode create(ThunderRuntime.Media info) {
            return Episode.create(info.fileName(), info.size(), info.playUrl());
        }

        @Override
        public List<Episode> call() throws Exception {
            String u = normalizeScheme(url);
            boolean torrent = isTorrent(u);
            if (u.startsWith("ed2k:")) {
                return Arrays.asList(Episode.create(parseEd2kName(u), u));
            }
            ThunderRuntime.Task taskId;
            try {
                taskId = ThunderGateway.get().runtime().parse(u, Path.thunder(Util.md5(u)));
            } catch (Exception e) {
                SpiderDebug.log("thunder", "parse failed url=%s error=%s", u, e);
                // 网关的缺模块错（ExtractException）原样上抛，不被下面通用文案盖掉，
                // 否则“去运行模块安装”的指引会丢成一句含糊的解析失败。
                if (e instanceof ExtractException) throw e;
                if (e instanceof InterruptedException || e instanceof java.util.concurrent.TimeoutException) {
                    throw new ExtractException("磁力解析超时，请检查网络后重试");
                }
                String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
                if (msg.contains("timeout") || msg.contains("timed out")) {
                    throw new ExtractException("磁力解析超时，请检查网络后重试");
                }
                if (e instanceof java.io.IOException) {
                    throw new ExtractException("磁力种子下载失败，请检查网络后重试");
                }
                if (msg.contains("dht") || msg.contains("metadata") || msg.contains("torrent")) {
                    throw new ExtractException("磁力种子元数据获取失败，请重试");
                }
                throw new ExtractException("磁力解析失败，请重试");
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
                List<Episode> medias = ThunderGateway.get().runtime().getTorrentInfo(taskId.getSaveFile()).stream().map(this::create).toList();
                if (medias.isEmpty()) throw new ExtractException("磁力解析失败，请重试");
                return medias;
            } catch (Exception e) {
                // 种子信息取不到（元数据没到、SDK 回空）时原先 NPE 被上游吞成空结果、
                // 界面静默 00:00；转成可见错，不递空结果。
                throw new ExtractException("磁力解析失败，请重试");
            } finally {
                ThunderGateway.get().runtime().stopTask(taskId);
            }
        }

        private void waitDone(ThunderRuntime.Task taskId) throws Exception {
            // 照上游 waitDone：状态 3 抛真错；状态 2 返回；100 轮 × 100ms 超时抛 error_play_timeout，
            // 不许超时后悄悄落到取种子信息（fork 旧 waitDone 超时无抛错、直接落到取种子信息）。
            // 上游用 Thread.sleep，此处沿用 fork 的 SystemClock.sleep（不改睡眠语义，只补抛错口径）。
            // 状态经网关取：直连走 getTaskInfo 合成值（与改前一致），外置走接口 getTaskInfo。
            for (int i = 0; i < 100; i++) {
                ThunderGateway.StatusEx info = ThunderGateway.get().taskStatus(taskId);
                if (info.taskStatus() == 3) {
                    String err = info.errorMessage();
                    if (err == null || err.isEmpty()) err = "迅雷任务出错，请重试";
                    throw new ExtractException(err);
                }
                if (info.taskStatus() == 2) return;
                SystemClock.sleep(100);
            }
            throw new ExtractException(ResUtil.getString(R.string.error_play_timeout));
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
