package com.fongmi.chaquo;

import android.content.Context;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.UriUtil;
import com.github.catvod.utils.Util;
import com.google.gson.Gson;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Spider extends com.github.catvod.crawler.Spider {

    private final PyObject app;
    private final PyObject obj;
    private final String api;
    private final Gson gson;

    public Spider(PyObject app, PyObject obj, String api) {
        this.gson = new Gson();
        this.app = app;
        this.obj = obj;
        this.api = api;
    }

    // Chaquopy 纪律（2026-10-21 修订）：先分清哪些 PyObject 能关、哪些不能。
    // Chaquopy 按 Python 对象身份缓存 Java 包装（PyObject.getInstance 的静态
    // cache，成品 dex 反编译实证）：同一个 Python 对象全进程只有一个 Java
    // 包装实例，close() 会把这个共享实例 closeNative 并将 addr 清零——关掉的
    // 不是「你这一份」，是所有人手里的同一份。
    // 【能关】本次调用现造、调用方独占的返回值：json.dumps 新串、现造的
    // list/dict 及其元素。这类若不关，全靠 GC finalize 兜底，FinalizerDaemon
    // 抢不到 GIL 时 10 秒超时把进程带崩（1.4.1 华为机真机实证），必须转成
    // Java 值后立即关。
    // 【永不关】全局单例与进程级对象的包装：模块对象（getModule("app") 与
    // Loader.app 是同一个实例）、None / True / False、type() 返回的类型对象，
    // 以及来源不可控的返回值（如 liveContent，可能是蜘蛛的模块级常量）。
    // 1.4.2 曾把 setHomeGeneration 里 getModule("app") 的包装顺手关掉，等于
    // 关死 Loader.app：此后全进程每个 py 源加载都报 ValueError: PyObject is
    // closed（1.4.3 真机实证，全 py 通杀）。obj 是本实例的蜘蛛对象，只在
    // destroy() 里关。

    private static void closeQuietly(PyObject o) {
        if (o == null) return;
        try {
            o.close();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void init(Context context, String extend) {
        PyObject dependence = app.callAttr("getDependence", obj);
        try {
            if (dependence != null) {
                List<PyObject> items = dependence.asList();
                try {
                    for (PyObject item : items) download(item + ".py");
                } finally {
                    for (PyObject item : items) closeQuietly(item);
                }
            }
        } finally {
            closeQuietly(dependence);
        }
        obj.put("siteKey", siteKey);
        // init 返回 None，是全局单例包装，不关（见类头纪律）。
        app.callAttr("init", obj, extend);
    }

    @Override
    public String homeContent(boolean filter) {
        return homeContent(filter, -1);
    }

    /**
     * 首页代次：SiteApi 首页加载把当前代次带进来；Python 透传层（app.py）发现代次
     * 过期直接返回空 JSON，不再进脚本。gen 传 -1 表示不校验（兼容老路径）。
     */
    public String homeContent(boolean filter, int homeGen) {
        try (PyObject r = app.callAttr("homeContent", obj, filter, homeGen)) {
            return r.toString();
        }
    }

    @Override
    public String homeVideoContent() {
        return homeVideoContent(-1);
    }

    public String homeVideoContent(int homeGen) {
        try (PyObject r = app.callAttr("homeVideoContent", obj, homeGen)) {
            return r.toString();
        }
    }

    /**
     * 推送首页代次到 Python 透传层。由 SiteApi.cancelHomeLoads() 在作废旧首页加载
     * 时调用；Python 未启动时跳过（此时也不可能有在途 py 加载）。
     */
    public static void setHomeGeneration(int gen) {
        try {
            // 两个 PyObject 都不许关：getModule("app") 经身份缓存返回的与
            // Loader.app 是同一个实例，关掉即关死全进程的 app 通道（1.4.3 全
            // py 通杀点）；callAttr 返回值是 None 单例包装，同理。见类头纪律。
            if (Python.isStarted()) Python.getInstance().getModule("app").callAttr("set_home_generation", gen);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try (PyObject r = app.callAttr("categoryContent", obj, tid, pg, filter, gson.toJson(extend))) {
            return r.toString();
        }
    }

    @Override
    public String detailContent(List<String> ids) {
        try (PyObject r = app.callAttr("detailContent", obj, gson.toJson(ids))) {
            return r.toString();
        }
    }

    @Override
    public String searchContent(String key, boolean quick) {
        try (PyObject r = app.callAttr("searchContent", obj, key, quick)) {
            return r.toString();
        }
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        try (PyObject r = app.callAttr("searchContent", obj, key, quick, pg)) {
            return r.toString();
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try (PyObject r = app.callAttr("playerContent", obj, flag, id, gson.toJson(vipFlags))) {
            return r.toString();
        }
    }

    @Override
    public String liveContent(String url) {
        // 返回值由蜘蛛自备，可能是模块级常量字符串（共享包装），不关（见类头纪律）。
        return app.callAttr("liveContent", obj, url).toString();
    }

    @Override
    public boolean manualVideoCheck() {
        // 返回值是 True/False 全局单例包装，不关（见类头纪律）。
        return app.callAttr("manualVideoCheck", obj).toBoolean();
    }

    @Override
    public boolean isVideoFormat(String url) {
        // 返回值是 True/False 全局单例包装，不关（见类头纪律）。
        return app.callAttr("isVideoFormat", obj, url).toBoolean();
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        PyObject result = app.callAttr("localProxy", obj, gson.toJson(params));
        try {
            List<PyObject> list = result.asList();
            try {
                boolean base64 = list.size() > 4 && list.get(4).toInt() == 1;
                boolean header = list.size() > 3 && list.get(3) != null;
                Object[] out = new Object[4];
                out[0] = list.get(0).toInt();
                out[1] = list.get(1).toString();
                out[2] = getStream(list.get(2), base64);
                out[3] = header ? getHeader(list.get(3)) : null;
                return out;
            } finally {
                for (PyObject item : list) closeQuietly(item);
            }
        } finally {
            closeQuietly(result);
        }
    }

    @Override
    public String action(String action) {
        try (PyObject r = app.callAttr("action", obj, action)) {
            return r.toString();
        }
    }

    @Override
    public void destroy() {
        try {
            // destroy 返回 None，是全局单例包装，不关（见类头纪律）；obj 本人关。
            app.callAttr("destroy", obj);
        } catch (Exception ignored) {
        } finally {
            // 蜘蛛被淘汰（文件变更/清缓存/删源）时确定性释放实例对象，
            // 不再把引用计数释放留给 GC finalize。
            closeQuietly(obj);
        }
    }

    private Map<String, String> getHeader(PyObject obj) {
        Map<PyObject, PyObject> map;
        try {
            map = obj.asMap();
        } catch (Exception e) {
            return null;
        }
        Map<String, String> header = new HashMap<>();
        boolean ok = true;
        try {
            for (Map.Entry<PyObject, PyObject> entry : map.entrySet())
                header.put(entry.getKey().toString(), entry.getValue().toString());
        } catch (Exception e) {
            ok = false;
        } finally {
            for (Map.Entry<PyObject, PyObject> entry : map.entrySet()) {
                closeQuietly(entry.getKey());
                closeQuietly(entry.getValue());
            }
        }
        return ok ? header : null;
    }

    private ByteArrayInputStream getStream(PyObject o, boolean base64) {
        if (o == null) return null;
        // type() 返回类型对象的全局单例包装，不关（见类头纪律）。
        boolean bytes = o.type().toString().contains("bytes");
        if (bytes) return new ByteArrayInputStream(o.toJava(byte[].class));
        String content = o.toString();
        if (base64 && content.contains("base64,")) content = content.split("base64,")[1];
        return new ByteArrayInputStream(base64 ? Util.decode(content) : content.getBytes());
    }

    private void download(String name) {
        String path = Path.py(name).getAbsolutePath();
        String url = UriUtil.resolve(api, name);
        // download 返回 None，是全局单例包装，不关（见类头纪律）。
        app.callAttr("download", path, url);
    }
}
