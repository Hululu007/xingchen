package com.fongmi.android.tv.api.loader;
import com.fongmi.android.tv.utils.ToastUtil;

import android.os.FileObserver;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.chaquo.Loader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class PyLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private final ConcurrentHashMap<String, Long> fileStamps;
    /** 脚本绝对路径 → 用它创建过的源 key（给目录监听单清用：只清该文件对应的蜘蛛）。 */
    private final ConcurrentHashMap<String, Set<String>> pathKeys;
    private final Loader loader;
    private volatile String recent;
    private FileObserver watcher;
    private File watchDir;

    /** 本进程是否有蜘蛛成功创建过：给首页冷热预算判定用（SiteApi.isColdPyHomeLoad）。 */
    private static final AtomicBoolean anySpider = new AtomicBoolean(false);
    private static final java.util.Set<String> createdKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static boolean hasAnySpider() {
        return anySpider.get();
    }

    /** 该源的蜘蛛是否已成功创建过：给首页预算判定用（只看本源，不看进程第一次）。 */
    public static boolean hasSpider(String key) {
        return key != null && createdKeys.contains(key);
    }

    /** 蜘蛛创建失败的包装（unchecked）：穿过 computeIfAbsent 时不被缓存，下次调用可重试。 */
    private static class SpiderLoadFailed extends RuntimeException {
        SpiderLoadFailed(Throwable cause) {
            super(cause);
        }
    }

    public PyLoader() {
        spiders = new ConcurrentHashMap<>();
        fileStamps = new ConcurrentHashMap<>();
        pathKeys = new ConcurrentHashMap<>();
        loader = new Loader();
    }

    public void clear() {
        clear("unspecified");
    }

    /** 全清（配蜘蛛失效口径）：记原因＋清掉的只数，真机重建频率靠这条日志看。\n     * 依赖缓存同步清（只清 download 记名的依赖文件，主脚本不动）：全清是重置动作，\n     * 下次 init 重下依赖，顺带捡回上游更新与同长换内容这种比对漏网；单删插件不走这里\n     * （依赖按名多源共用，单删时不知道哪些依赖只属于被删插件）。 */
    public void clear(String reason) {
        int count = spiders.size();
        SpiderDebug.log("pyloader", "clear reason=%s spiders=%d keys=%s", reason, count, spiders.keySet());
        spiders.values().forEach(Spider::destroy);
        spiders.clear();
        createdKeys.clear();
        fileStamps.clear();
        pathKeys.clear();
        recent = null;
        try {
            com.fongmi.chaquo.Spider.clearDepCache();
        } catch (Throwable ignored) {
        }
    }

    public void remove(String key) {
        remove(key, "unspecified");
    }

    /** 单只驱逐：记原因＋key，真机重建频率靠这条日志看。
     * 顺序：先从表里摘掉并先清 recent（代理 recent 路不再路由到将死的蜘蛛），
     * 再 destroy；同时清该源解析缓存，删源后旧解析结果不许再命中。 */
    public void remove(String key, String reason) {
        Spider spider = spiders.remove(key);
        createdKeys.remove(key);
        fileStamps.remove(key);
        removeKeyFromPaths(key);
        if (key != null && key.equals(recent)) recent = null;
        try {
            SiteApi.clearPlayerCache(key);
        } catch (Throwable ignored) {
        }
        if (spider != null) {
            try {
                spider.destroy();
            } catch (Throwable ignored) {}
        }
        SpiderDebug.log("pyloader", "remove key=%s reason=%s hadSpider=%s", key, reason, spider != null);
    }

    /** 一个源同一时间只对应一个脚本：先把该 key 从旧路径摘掉，再挂到新路径。 */
    private void recordPath(String key, String absolutePath) {
        if (key == null || absolutePath == null) return;
        removeKeyFromPaths(key);
        pathKeys.computeIfAbsent(absolutePath, k -> ConcurrentHashMap.newKeySet()).add(key);
    }

    private void removeKeyFromPaths(String key) {
        if (key == null) return;
        for (Set<String> keys : pathKeys.values()) keys.remove(key);
    }

    /** 某脚本文件对应的源 key 快照（监听／删插件单清用；无缓存返回空，不触发全清）。 */
    public List<String> keysForScript(String absolutePath) {
        if (absolutePath == null) return new ArrayList<>();
        Set<String> keys = pathKeys.get(absolutePath);
        return keys == null ? new ArrayList<>() : new ArrayList<>(keys);
    }

    /**
     * 按文件单清：只清该文件对应的蜘蛛＋首页缓存（删插件／覆盖插件后首页瞬间刷新口径），
     * 其它源的蜘蛛和首页缓存一律不动——目录监听／删插件／导入插件三路共用。
     */
    public void invalidateScript(String absolutePath) {
        if (absolutePath == null) return;
        for (String key : keysForScript(absolutePath)) {
            try {
                SiteApi.clearHomeCache(key);
            } catch (Throwable ignored) {
            }
            try {
                SiteApi.clearPlayerCache(key);
            } catch (Throwable ignored) {
            }
        }
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    /**
     * 监听 py 插件目录：只清事件文件对应的单只蜘蛛＋该源首页缓存（删 py 后首页瞬间刷新口径），
     * 其它源的蜘蛛和首页缓存一律不动。
     * 旧写法是任意事件（包括下载中的 .tmp、编辑器临时文件、非 py 文件）都全清全部蜘蛛＋
     * 全部首页缓存——插件一多，重建总量随源数线性放大，就是“py 加载更慢了”的放大器。
     * 不加防抖：清操作本身幂等（蜘蛛已清掉再清一次是空操作），真正的重建只在下次使用时
     * 发生一次，同一文件的多次事件不会导致多次重建。
     */
    public synchronized void watchPlugins(File dir) {
        if (dir == null || watcher != null) return;
        watchDir = new File(dir.getAbsolutePath());
        try {
            watcher = new FileObserver(watchDir.getAbsolutePath(),
                    FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.MOVED_FROM
                            | FileObserver.DELETE | FileObserver.CREATE) {
                @Override
                public void onEvent(int event, String path) {
                    SpiderDebug.log("pyloader", "watch event=%s path=%s", watchEventName(event), path);
                    if (path == null || !path.toLowerCase().endsWith(".py")) return;
                    try {
                        invalidateScript(new File(watchDir, path).getAbsolutePath());
                        RefreshEvent.home();
                    } catch (Throwable ignored) {
                    }
                }
            };
            watcher.startWatching();
        } catch (Throwable ignored) {}
    }

    private static String watchEventName(int event) {
        switch (event & FileObserver.ALL_EVENTS) {
            case FileObserver.CLOSE_WRITE: return "CLOSE_WRITE";
            case FileObserver.MOVED_TO: return "MOVED_TO";
            case FileObserver.MOVED_FROM: return "MOVED_FROM";
            case FileObserver.DELETE: return "DELETE";
            case FileObserver.CREATE: return "CREATE";
            default: return "event_" + event;
        }
    }

    private String resolve(String api) {
        if (api.startsWith("plugins/") && App.get() != null && App.get().getFilesDir() != null) {
            File f = new File(App.get().getFilesDir(), api);
            if (f.exists()) return f.getAbsolutePath();
        }
        return api;
    }

    private static long stampOf(File f) {
        return f.lastModified() * 31L + f.length();
    }

    /**
     * 脚本文件戳（给首页结果内存缓存当 key 的一部分）：
     * 本地文件 → 「绝对路径#mtime*31+size」；文件已删 → 路径#missing（key 变化，旧缓存自然失效）；
     * 远程脚本 → api 串本身。
     */
    public static String scriptStamp(String api) {
        if (api == null) return "";
        if (api.startsWith("http")) return api;
        String resolved = api;
        if (api.startsWith("plugins/") && App.get() != null && App.get().getFilesDir() != null) {
            File f = new File(App.get().getFilesDir(), api);
            if (f.exists()) resolved = f.getAbsolutePath();
        }
        File f = new File(resolved);
        if (f.exists()) return resolved + "#" + (f.lastModified() * 31L + f.length());
        return resolved + "#missing";
    }

    /**
     * 健康检查用：与 getSpider 同逻辑，但加载失败时静默返回 SpiderNull，不弹 Toast
     *（批量检测时逐个 Toast 会刷屏；检测结果由调用方统一展示）。
     */
    public Spider getSpiderQuiet(String key, String api, String ext) {
        String resolvedApi = resolve(api);
        boolean localFile = !api.startsWith("http");
        if (localFile) {
            File f = new File(resolvedApi);
            if (!f.exists()) {
                remove(key, "file-missing");
                return new SpiderNull();
            }
            if (fileStamps.containsKey(key) && stampOf(f) != fileStamps.get(key)) {
                remove(key, "stamp-changed");
            }
        }
        try {
            return spiders.computeIfAbsent(key, k -> createSpider(k, resolvedApi, ext, localFile));
        } catch (SpiderLoadFailed e) {
            // 失败不缓存（computeIfAbsent 在映射函数抛异常时不写入），下次调用重试。
            return new SpiderNull();
        }
    }

    /**
     * 创建并初始化一只蜘蛛（computeIfAbsent 的映射函数体）。
     * 成功才写入缓存并标记 anySpider；失败抛 SpiderLoadFailed 穿透出去，
     * 外层负责提示并返回未缓存的 SpiderNull——一次失败不再毒化该源整个进程。
     * 计时分两段记（蜘蛛创建＝脚本取数＋编译加载，init＝依赖下载＋蜘蛛 init），
     * 真机分清重建成本里哪段是大头；另记设备 __pycache__ 存在性（执行文件一律
     * 落在 Path.py() 缓存目录，pyc 是否命中看它）。
     */
    private Spider createSpider(String key, String api, String ext, boolean localFile) {
        long spiderStart = System.currentTimeMillis();
        Spider spider;
        try {
            spider = loader.spider(api);
        } catch (Throwable t) {
            SpiderDebug.log("pyloader", "create failed key=%s cost=%dms error=%s", key, System.currentTimeMillis() - spiderStart, t);
            t.printStackTrace();
            throw new SpiderLoadFailed(t);
        }
        long spiderCost = System.currentTimeMillis() - spiderStart;
        long initStart = System.currentTimeMillis();
        try {
            spider.siteKey = key;
            spider.init(App.get(), normalizeExt(ext));
            File f = new File(api);
            if (localFile && f.exists()) {
                fileStamps.put(key, stampOf(f));
                recordPath(key, f.getAbsolutePath());
            } else {
                fileStamps.remove(key);
            }
            anySpider.set(true);
            createdKeys.add(key);
            boolean pycache = false;
            try {
                pycache = new File(Path.py(), "__pycache__").exists();
            } catch (Throwable ignored) {
            }
            SpiderDebug.log("pyloader", "create key=%s spiderCost=%dms initCost=%dms pycache=%s", key, spiderCost, System.currentTimeMillis() - initStart, pycache);
            return spider;
        } catch (Throwable t) {
            SpiderDebug.log("pyloader", "create failed key=%s spiderCost=%dms initCost=%dms error=%s", key, spiderCost, System.currentTimeMillis() - initStart, t);
            t.printStackTrace();
            throw new SpiderLoadFailed(t);
        }
    }

    public Spider getSpider(String key, String api, String ext) {
        String resolvedApi = resolve(api);
        boolean localFile = !api.startsWith("http");
        if (localFile) {
            File f = new File(resolvedApi);
            if (!f.exists()) {
                // 脚本文件已不存在（被删/未导入）：静默给空蜘蛛，不走下面的加载失败弹窗。
                // 此前只有登记过时间戳的 key 才有这条静默路径；时间戳被目录监听清掉后，
                // 源还指着已删文件就会每次进首页都弹一遍“PY加载失败”的错误码。
                remove(key, "file-missing");
                return new SpiderNull();
            }
            if (fileStamps.containsKey(key) && stampOf(f) != fileStamps.get(key)) {
                // 文件被替换：丢掉旧缓存，重新加载
                remove(key, "stamp-changed");
            }
        }
        try {
            return spiders.computeIfAbsent(key, k -> createSpider(k, resolvedApi, ext, localFile));
        } catch (SpiderLoadFailed e) {
            // 失败不缓存：下次打开重试。computeIfAbsent 在映射函数抛异常时不写入，
            // 此前这里返回 SpiderNull 会被写进缓存，一次失败毒化该源整个进程。
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String msg = "PY加载失败 [" + key + "]: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> ToastUtil.show(App.get(), msg, android.widget.Toast.LENGTH_LONG));
            return new SpiderNull();
        }
    }

    private String normalizeExt(String ext) {
        String value = TextUtils.isEmpty(ext) ? "" : ext.trim();
        // Many live Python spiders treat ext as an option object and call .get().
        // TV-style configs commonly express an empty extension as [], which would
        // otherwise deserialize to a list and crash those spiders during init.
        return "[]".equals(value) ? "{}" : ext;
    }


    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
