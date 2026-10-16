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
        // 按源定向的环境级补丁：给 requests/urllib 装上「发请求前问 SourceGate」的钩子。
        // 只靠 base/spider 的 fetch/post 覆盖不全——自己写 fetch 的蜘蛛（XVideos 用
        // Session、JavMenu 用 urllib）全绕过去，定向对它们从来没生效过（真机实证：
        // py 源跑了多轮，gate 的 via=py 决策记录一条都没有）。
        try {
            Python.getInstance().getModule("gate_patch").callAttr("install");
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
                java.io.File f = new java.io.File(api);
                if (f.exists() && f.isFile()) {
                    byte[] bytes = null;
                    try {
                        bytes = java.nio.file.Files.readAllBytes(f.toPath());
                    } catch (Exception readEx) {
                        // Files.readAllBytes 对中文路径可能失败，fallback 用 FileInputStream
                        java.io.FileInputStream fis = null;
                        try {
                            fis = new java.io.FileInputStream(f);
                            byte[] buf = new byte[(int) f.length()];
                            int offset = 0, n;
                            while (offset < buf.length && (n = fis.read(buf, offset, buf.length - offset)) != -1) offset += n;
                            bytes = buf;
                        } finally {
                            if (fis != null) try { fis.close(); } catch (Exception ignored) {}
                        }
                    }
                    return new String(bytes, StandardCharsets.UTF_8);
                }
                if (api.contains("class Spider") || api.contains("def homeContent") || api.contains("import ")) {
                    return api;
                }
                return api;
            } catch (Exception e) {
                // 详细日志：文件名、可读性、长度、异常链
                java.io.File f = new java.io.File(api);
                StringBuilder log = new StringBuilder("PY read failed: ").append(api)
                        .append(" | exists=").append(f.exists())
                        .append(" | isFile=").append(f.isFile())
                        .append(" | canRead=").append(f.canRead())
                        .append(" | length=").append(f.length());
                Throwable cause = e;
                while (cause != null) {
                    log.append(" | cause=").append(cause.getClass().getSimpleName()).append(":").append(cause.getMessage());
                    cause = cause.getCause();
                }
                SpiderDebug.log("python-spider", log.toString());
                throw new IllegalStateException("Unable to read python script: " + api, e);
            }
        }
    }

    private String scriptName(String api) {
        String lower = api.toLowerCase();
        String suffix = lower.contains(".py") ? ".py" : ".script";
        return Util.md5(api) + suffix;
    }
}
