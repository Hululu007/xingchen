package com.fongmi.chaquo;

import android.text.TextUtils;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.GlobalProxy;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import java.io.File;
import java.nio.charset.StandardCharsets;

import okhttp3.Response;

public class Loader {

    private final PyObject app;

    public Loader() {
        if (!Python.isStarted()) Python.start(Platform.create());
        try {
            Python.getInstance().getModule("webhtv_logging").callAttr("install");
        } catch (RuntimeException error) {
            SpiderDebug.log("python-spider", error);
        }
        // Python 刚启动：把全局代理配置同步进 os.environ，供 requests 读取
        GlobalProxy.syncPython();
        app = Python.getInstance().getModule("app");
    }

    public Spider spider(String api) {
        String name = scriptName(api);
        String source = source(api, name);
        PyObject obj = app.callAttr("spider", Path.py().getAbsolutePath(), source, name);
        return new Spider(app, obj, api);
    }

    private String source(String api, String name) {
        if (api.startsWith("http")) {
            File cache = Path.py(name);
            try (Response response = OkHttp.newCall(OkHttp.client(15000), api).execute()) {
                if (!response.isSuccessful()) throw new IllegalStateException("HTTP " + response.code());
                String source = response.body().string();
                if (TextUtils.isEmpty(source)) throw new IllegalStateException("Empty python script");
                Path.write(cache, source.getBytes(StandardCharsets.UTF_8));
                return source;
            } catch (Exception e) {
                if (cache.exists() && cache.length() > 0) {
                    try {
                        byte[] bytes = java.nio.file.Files.readAllBytes(cache.toPath());
                        String cached = new String(bytes, StandardCharsets.UTF_8);
                        if (!TextUtils.isEmpty(cached)) return cached;
                    } catch (Exception ex) {
                    }
                }
                throw new IllegalStateException("Unable to download python script: " + api, e);
            }
        } else {
            try {
                String path = resolveLocalPath(api);
                java.io.File f = new java.io.File(path);
                if (f.exists() && f.isFile()) {
                    byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                    return new String(bytes, StandardCharsets.UTF_8);
                }
                if (api.contains("class Spider") || api.contains("def homeContent") || api.contains("import ")) {
                    return api;
                }
                return api;
            } catch (Exception e) {
                throw new IllegalStateException("Unable to read python script: " + api, e);
            }
        }
    }

    /**
     * 本地路径容错：api 中若夹带多余空白（如 "/py/ 51暗网.py"），
     * 而磁盘上的真实文件没有空白时，File.exists() 会返回 false 导致加载失败。
     * 此时尝试 trim 后的路径；若 trim 后存在则用 trim 后的路径。
     */
    private String resolveLocalPath(String api) {
        if (api == null) return api;
        java.io.File f = new java.io.File(api);
        if (f.exists()) return api;
        String trimmed = api.trim();
        if (!trimmed.equals(api) && new java.io.File(trimmed).exists()) {
            return trimmed;
        }
        return api;
    }

    private String scriptName(String api) {
        String lower = api.toLowerCase();
        String suffix = lower.contains(".py") ? ".py" : ".script";
        return Util.md5(api) + suffix;
    }
}
