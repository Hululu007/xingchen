package com.fongmi.android.tv.api.loader;

import android.text.TextUtils;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.utils.Util;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import dalvik.system.DexClassLoader;

public class BaseLoader {

    private final JarLoader jarLoader;
    private final PyLoader pyLoader;
    private final JsLoader jsLoader;

    private BaseLoader() {
        jarLoader = new JarLoader();
        pyLoader = new PyLoader();
        jsLoader = new JsLoader();
    }

    public static BaseLoader get() {
        return Loader.INSTANCE;
    }

    private static boolean isJs(String api) {
        return api.contains(".js") || api.contains(".wv");
    }

    private static boolean isPy(String api) {
        return api.contains(".py");
    }

    private static boolean isCsp(String api) {
        return api.startsWith("csp_");
    }

    public void clear() {
        clear("unspecified");
    }

    /** 全清（配蜘蛛失效口径）：原因透传给 PyLoader 记日志，真机重建频率靠它看。\n     * 详情空计数同步清（换配置／恢复备份都走这里，旧一笔不算进“连续”）。 */
    public void clear(String reason) {
        Task.execute(() -> {
            jarLoader.clear();
            pyLoader.clear(reason);
            jsLoader.clear();
            try {
                SiteApi.clearDetailEmptyStrikes();
            } catch (Throwable ignored) {
            }
        });
    }

    public void clearPy() {
        clearPy("unspecified");
    }

    public void clearPy(String reason) {
        pyLoader.clear(reason);
    }

    public void clearJs() {
        jsLoader.clear();
    }

    public void removePySpider(String key) {
        removePySpider(key, "unspecified");
    }

    public void removePySpider(String key, String reason) {
        pyLoader.remove(key, reason);
    }

    /** 按脚本文件单清（目录监听／删插件／导入插件用）：只清该文件对应的蜘蛛＋首页缓存。 */
    public void invalidatePyScript(String absolutePath) {
        pyLoader.invalidateScript(absolutePath);
    }

    public void removeJsSpider(String key) {
        jsLoader.remove(key);
    }

    public void watchPyPlugins(java.io.File dir) {
        pyLoader.watchPlugins(dir);
    }

    public void watchJsPlugins(java.io.File dir) {
        jsLoader.watchPlugins(dir);
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        if (isPy(api)) return pyLoader.getSpider(key, api, ext);
        else if (isJs(api)) return jsLoader.getSpider(key, api, ext, jar);
        else if (isCsp(api)) return jarLoader.getSpider(key, api, ext, jar);
        else return new SpiderNull();
    }

    /** 健康检查用：静默加载（PY 失败不弹 Toast，结果由调用方统一展示）。 */
    public Spider getSpiderQuiet(String key, String api, String ext, String jar) {
        if (isPy(api)) return pyLoader.getSpiderQuiet(key, api, ext);
        else if (isJs(api)) return jsLoader.getSpider(key, api, ext, jar);
        else return new SpiderNull();
    }

    public Spider getSpider(String key) {
        Site site = VodConfig.get().getSite(key);
        Live live = LiveConfig.get().getLive(key);
        if (!site.isEmpty()) return site.spider();
        if (!live.isEmpty()) return live.spider();
        return new SpiderNull();
    }

    public void setRecent(String key, String api, String jar) {
        if (isJs(api)) jsLoader.setRecent(key);
        else if (isPy(api)) pyLoader.setRecent(key);
        else if (isCsp(api)) jarLoader.setRecent(Util.md5(jar));
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (params.containsKey("siteKey")) return getSpider(params.get("siteKey")).proxy(params);
        if ("js".equals(params.get("do"))) return jsLoader.proxy(params);
        if ("py".equals(params.get("do"))) return pyLoader.proxy(params);
        return jarLoader.proxy(params);
    }

    public void parseJar(String jar, boolean recent) {
        if (TextUtils.isEmpty(jar)) return;
        String key = Util.md5(jar);
        jarLoader.parseJar(key, jar);
        if (recent) jarLoader.setRecent(key);
    }

    public DexClassLoader dex(String jar) {
        return jarLoader.dex(jar);
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) throws Throwable {
        return jarLoader.jsonExt(key, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) throws Throwable {
        return jarLoader.jsonExtMix(flag, key, name, jxs, url);
    }

    private static class Loader {
        static volatile BaseLoader INSTANCE = new BaseLoader();
    }
}
