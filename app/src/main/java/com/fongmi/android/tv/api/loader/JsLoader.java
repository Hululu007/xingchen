package com.fongmi.android.tv.api.loader;

import android.os.FileObserver;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.quickjs.crawler.Loader;
import com.fongmi.quickjs.utils.Module;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class JsLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private final ConcurrentHashMap<String, Set<String>> pathKeys;
    private final Loader loader;
    private volatile String recent;
    private FileObserver watcher;
    private File watchDir;

    public JsLoader() {
        spiders = new ConcurrentHashMap<>();
        pathKeys = new ConcurrentHashMap<>();
        loader = new Loader();
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        Module.get().clear();
        spiders.clear();
        pathKeys.clear();
        recent = null;
    }

    public void remove(String key) {
        Spider spider = spiders.remove(key);
        removeKeyFromPaths(key);
        if (key != null && key.equals(recent)) recent = null;
        // 与 PyLoader.remove 同款：删 JS 源先清该源解析缓存，否则同 key|flag|id
        // 的旧解析 URL 在 TTL 内仍可命中（按 key| 前缀清，与无参全清版区分）。
        try {
            SiteApi.clearPlayerCache(key);
        } catch (Throwable ignored) {
        }
        if (spider != null) {
            try {
                spider.destroy();
            } catch (Throwable ignored) {}
        }
    }

    private void recordPath(String key, String api) {
        if (key == null || api == null) return;
        pathKeys.computeIfAbsent(api, k -> ConcurrentHashMap.newKeySet()).add(key);
        try {
            File f = new File(api);
            if (f.isAbsolute()) pathKeys.computeIfAbsent(f.getAbsolutePath(), k -> ConcurrentHashMap.newKeySet()).add(key);
            String name = f.getName();
            if (name != null && !name.isEmpty()) pathKeys.computeIfAbsent(name, k -> ConcurrentHashMap.newKeySet()).add(key);
        } catch (Throwable ignored) {
        }
    }

    private void removeKeyFromPaths(String key) {
        if (key == null) return;
        for (Set<String> keys : pathKeys.values()) keys.remove(key);
    }

    private List<String> keysForEvent(String path) {
        List<String> out = new ArrayList<>();
        if (path == null) return out;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        String[] lookups = new String[]{path, new File(path).getName(),
                watchDir == null ? null : new File(watchDir, path).getAbsolutePath()};
        for (String lookup : lookups) {
            if (lookup == null) continue;
            Set<String> keys = pathKeys.get(lookup);
            if (keys != null) seen.addAll(keys);
        }
        out.addAll(seen);
        return out;
    }

    /** 按文件单清：只清事件文件对应的蜘蛛＋该源首页缓存＋解析缓存，并发首页刷新。 */
    private void invalidateScript(String path) {
        for (String key : keysForEvent(path)) {
            try {
                SiteApi.clearHomeCache(key);
            } catch (Throwable ignored) {
            }
            try {
                SiteApi.clearPlayerCache(key);
            } catch (Throwable ignored) {
            }
            remove(key);
        }
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    /** 监听 js 插件目录：文件被替换/删除时只清该文件对应的蜘蛛，下次使用即加载新版，不再全量清。 */
    public synchronized void watchPlugins(File dir) {
        if (dir == null || watcher != null) return;
        watchDir = new File(dir.getAbsolutePath());
        try {
            watcher = new FileObserver(dir.getAbsolutePath(),
                    FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.MOVED_FROM
                            | FileObserver.DELETE | FileObserver.CREATE) {
                @Override
                public void onEvent(int event, String path) {
                    SpiderDebug.log("jsloader", "watch event=%s path=%s", event, path);
                    if (path == null || !(path.toLowerCase().endsWith(".js") || path.toLowerCase().endsWith(".wv"))) return;
                    try {
                        invalidateScript(path);
                        RefreshEvent.home();
                    } catch (Throwable ignored) {
                    }
                }
            };
            watcher.startWatching();
        } catch (Throwable ignored) {}
    }

    private static class SpiderLoadFailed extends RuntimeException {
        SpiderLoadFailed(Throwable cause) {
            super(cause);
        }
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        try {
            return spiders.computeIfAbsent(key, k -> {
                Spider spider = null;
                try {
                    spider = loader.spider(api, BaseLoader.get().dex(jar));
                    spider.siteKey = key;
                    spider.init(App.get(), ext);
                    recordPath(key, api);
                    return spider;
                } catch (Throwable e) {
                    SpiderDebug.log("JsLoader", "JS spider init failed: key=%s api=%s", key, api);
                    SpiderDebug.log("JsLoader", e);
                    e.printStackTrace();
                    // init 失败也要确定性销毁（destroy 内部走 releaseJS），别把 QuickJS 上下文留给 GC。
                    if (spider != null) {
                        try {
                            spider.destroy();
                        } catch (Throwable ignored) {
                        }
                    }
                    throw new SpiderLoadFailed(e);
                }
            });
        } catch (SpiderLoadFailed e) {
            return new SpiderNull();
        }
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
