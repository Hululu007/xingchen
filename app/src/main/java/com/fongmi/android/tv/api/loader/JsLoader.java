package com.fongmi.android.tv.api.loader;

import android.os.FileObserver;

import com.fongmi.android.tv.App;
import com.fongmi.quickjs.crawler.Loader;
import com.fongmi.quickjs.utils.Module;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JsLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private final Loader loader;
    private volatile String recent;
    private FileObserver watcher;

    public JsLoader() {
        spiders = new ConcurrentHashMap<>();
        loader = new Loader();
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        Module.get().clear();
        spiders.clear();
        recent = null;
    }

    public void remove(String key) {
        Spider spider = spiders.remove(key);
        if (spider != null) {
            try {
                spider.destroy();
            } catch (Throwable ignored) {}
        }
        if (key.equals(recent)) recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    /** 监听 js 插件目录：文件被替换/删除时立即清掉内存里的旧 Spider，下次使用即加载新版。 */
    public synchronized void watchPlugins(File dir) {
        if (dir == null || watcher != null) return;
        try {
            watcher = new FileObserver(dir.getAbsolutePath(),
                    FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.MOVED_FROM
                            | FileObserver.DELETE | FileObserver.CREATE) {
                @Override
                public void onEvent(int event, String path) {
                    clear();
                }
            };
            watcher.startWatching();
        } catch (Throwable ignored) {}
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        return spiders.computeIfAbsent(key, k -> {
            try {
                Spider spider = loader.spider(api, BaseLoader.get().dex(jar));
                spider.siteKey = key;
                spider.init(App.get(), ext);
                return spider;
            } catch (Throwable e) {
                SpiderDebug.log("JsLoader", "JS spider init failed: key=%s api=%s", key, api);
                SpiderDebug.log("JsLoader", e);
                e.printStackTrace();
                return new SpiderNull();
            }
        });
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
