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
        // 代理 seam（本地 NanoHTTPD 唯一入口 server/process/Proxy.java:41）：
        // ① 存活判定：siteKey 路先解析蜘蛛——解析出来是 SpiderNull（源已删／无此源）
        // 就是已死的代理，直接返回 null，不许死蜘蛛进代理；do=py/do=js 路由 recent，
        // recent 已清／蜘蛛已摘时 loader 本身返回 null，同样不进代理。
        // ② 已关闭对象／空列表转换失败：删源／重载／世代切换后仍在途的代理请求可能
        // 撞上刚被 destroy() 的蜘蛛——依赖库内 Spider.proxy 读已关闭的 PyObject
        // （PyObject is closed）或对 null 返回做 asList() 抛 NPE，seam 拦不住库内那
        // 一行，但能拦住它抛出来的结果：这两种一律按“代理上游已死”处理，返回 null，
        // 由 Proxy 转 502 可见错，不许以 500／崩溃／静默空结果收场；其它异常（网络、
        // 上游 4xx/5xx 等）原样上抛，保持原有错误口径。
        // ③ 逐字段判空：蜘蛛返回的 Object[] 只有到这里才看得见，逐下标归一——
        // [0] 状态码非 Integer、[2] 数据流非 InputStream、长度不足 3，一律按无效
        // 返回 null（Proxy 转 502）；[1] mime 为空、[3] 请求头不是 Map／为空，按空
        // 处理不抛（mime 透传 null、请求头透传 null，Proxy.createResponse 同样容忍）。
        // 注意：py 返回列表本身为 null 时库内 asList() 已抛 NPE，seam 看不到“空列
        // 表”，只能按②转 502——这是 seam 的边界，见交回卡点。
        Object[] rs;
        try {
            if (params.containsKey("siteKey")) {
                Spider spider = getSpider(params.get("siteKey"));
                if (spider instanceof SpiderNull) {
                    SpiderDebug.log("proxy", "dead spider siteKey=%s: source removed or unknown, skip proxy", params.get("siteKey"));
                    return null;
                }
                rs = spider.proxy(params);
            } else if ("js".equals(params.get("do"))) {
                rs = jsLoader.proxy(params);
            } else if ("py".equals(params.get("do"))) {
                rs = pyLoader.proxy(params);
            } else {
                rs = jarLoader.proxy(params);
            }
        } catch (NullPointerException e) {
            SpiderDebug.log("proxy", "proxy result null conversion do=%s siteKey=%s: %s", params.get("do"), params.get("siteKey"), e);
            return null;
        } catch (Exception e) {
            // 已关闭对象报错走 Exception 族（IllegalStateException／PyException），
            // 这里只接 Exception：Error 不拦、不转 502，原样上抛。
            if (isClosedObject(e)) {
                SpiderDebug.log("proxy", "proxy spider closed do=%s siteKey=%s: %s", params.get("do"), params.get("siteKey"), e);
                return null;
            }
            throw e;
        }
        return normalizeProxyResult(rs);
    }

    /** 原因链里出现“已关闭对象”特征：Chaquopy 已关闭 PyObject 的访问报错。 */
    private static boolean isClosedObject(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase(java.util.Locale.ROOT).contains("is closed")) return true;
        }
        return false;
    }

    /** 逐字段归一：Response 直通原样返回；其余按 [状态码, mime, 数据流, 请求头] 归一。 */
    private static Object[] normalizeProxyResult(Object[] rs) {
        if (rs == null || rs.length == 0) return null;
        if (rs[0] instanceof fi.iki.elonen.NanoHTTPD.Response) return rs;
        if (rs.length < 3 || !(rs[0] instanceof Integer) || !(rs[2] instanceof java.io.InputStream)) return null;
        Object[] out = new Object[4];
        out[0] = rs[0];
        out[1] = rs[1] == null ? null : rs[1].toString();
        out[2] = rs[2];
        out[3] = rs.length > 3 && rs[3] instanceof Map ? rs[3] : null;
        return out;
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
