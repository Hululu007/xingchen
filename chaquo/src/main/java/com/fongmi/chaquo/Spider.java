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

    // Chaquopy 纪律：callAttr / getModule / asList / asMap / type() 交出来的每个
    // PyObject 都必须显式 close。靠 GC finalize 兜底时，FinalizerDaemon 要抢到
    // GIL 才能 closeNative，Python 正忙（多源并发＋长请求）时 10 秒抢不到就抛
    // TimeoutException 把进程带崩（1.4.1 华为机真机实证）。持有关系：app 是
    // Loader 的进程级模块对象，本类永不关；obj 是本实例的蜘蛛对象，只在
    // destroy() 里关；其余一律在转成 Java 值后立即关。

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
        closeQuietly(app.callAttr("init", obj, extend));
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
        PyObject module = null;
        try {
            if (Python.isStarted()) {
                module = Python.getInstance().getModule("app");
                closeQuietly(module.callAttr("set_home_generation", gen));
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(module);
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
        try (PyObject r = app.callAttr("liveContent", obj, url)) {
            return r.toString();
        }
    }

    @Override
    public boolean manualVideoCheck() {
        try (PyObject r = app.callAttr("manualVideoCheck", obj)) {
            return r.toBoolean();
        }
    }

    @Override
    public boolean isVideoFormat(String url) {
        try (PyObject r = app.callAttr("isVideoFormat", obj, url)) {
            return r.toBoolean();
        }
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
            closeQuietly(app.callAttr("destroy", obj));
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
        PyObject type = o.type();
        boolean bytes;
        try {
            bytes = type.toString().contains("bytes");
        } finally {
            closeQuietly(type);
        }
        if (bytes) return new ByteArrayInputStream(o.toJava(byte[].class));
        String content = o.toString();
        if (base64 && content.contains("base64,")) content = content.split("base64,")[1];
        return new ByteArrayInputStream(base64 ? Util.decode(content) : content.getBytes());
    }

    private void download(String name) {
        String path = Path.py(name).getAbsolutePath();
        String url = UriUtil.resolve(api, name);
        closeQuietly(app.callAttr("download", path, url));
    }
}
