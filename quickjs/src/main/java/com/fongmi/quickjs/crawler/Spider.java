package com.fongmi.quickjs.crawler;

import android.content.Context;

import com.fongmi.quickjs.bean.Res;
import com.fongmi.quickjs.method.Console;
import com.fongmi.quickjs.method.Global;
import com.fongmi.quickjs.method.Local;
import com.fongmi.quickjs.utils.Async;
import com.fongmi.quickjs.utils.JSUtil;
import com.fongmi.quickjs.utils.Module;
import com.github.catvod.utils.Asset;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.UriUtil;
import com.github.catvod.utils.Util;
import com.whl.quickjs.wrapper.JSArray;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dalvik.system.DexClassLoader;

public class Spider extends com.github.catvod.crawler.Spider {

    private final ExecutorService executor;
    private final DexClassLoader dex;
    private final String api;

    // 单次调用的超时上限（同步执行段与 Promise 等待段各一份）。wrapper 没有
    // 中断处理器，卡在原生 JS 里的执行线程无法从 Java 侧强杀，能做的是：
    // 同步段超时即判定执行线程已死、标记 unresponsive，后续调用快速失败、
    // 不再排进死线程后面；Promise 永不 settle 时执行线程还活着，只失败本次。
    private static final long TIMEOUT_SECONDS = 60;

    private QuickJSContext ctx;
    private JSObject jsObject;
    private Global global;
    private boolean cat;
    private volatile boolean unresponsive;

    public Spider(String api, DexClassLoader dex) {
        this.executor = Executors.newSingleThreadExecutor();
        this.api = api;
        this.dex = dex;
    }

    private <T> T submitAndGet(Callable<T> callable) throws Exception {
        if (unresponsive) throw new IllegalStateException("quickjs spider unresponsive");
        Future<T> future = executor.submit(callable);
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            unresponsive = true;
            future.cancel(true);
            throw e;
        }
    }

    private Object call(String func, Object... args) throws Exception {
        CompletableFuture<Object> future = submitAndGet(() -> Async.run(jsObject, func, args));
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // JS 已同步返回、只是 Promise 永不 settle：执行线程还活着，
            // 只让本次调用按超时报错返回，不连坐整个蜘蛛。
            throw e;
        }
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        initializeJS();
        call("init", submitAndGet(() -> getExt(extend)));
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        return (String) call("home", filter);
    }

    @Override
    public String homeVideoContent() throws Exception {
        return (String) call("homeVod");
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        JSObject obj = submitAndGet(() -> JSUtil.toObject(ctx, extend));
        return (String) call("category", tid, pg, filter, obj);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        return (String) call("detail", ids.get(0));
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return (String) call("search", key, quick);
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return (String) call("search", key, quick, pg);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        JSArray array = submitAndGet(() -> JSUtil.toArray(ctx, vipFlags));
        return (String) call("play", flag, id, array);
    }

    @Override
    public String liveContent(String url) throws Exception {
        return (String) call("live", url);
    }

    @Override
    public boolean manualVideoCheck() throws Exception {
        return (Boolean) call("sniffer");
    }

    @Override
    public boolean isVideoFormat(String url) throws Exception {
        return (Boolean) call("isVideo", url);
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        return "catvod".equals(params.get("from")) ? proxy2(params) : proxy1(params);
    }

    @Override
    public String action(String action) throws Exception {
        return (String) call("action", action);
    }

    @Override
    public void destroy() {
        // 执行线程已卡死时不再碰 ctx：排队进去的 destroy 永远轮不到执行，
        // 从别的线程 destroy 正在跑原生代码的上下文有崩溃风险，直接关执行器。
        // 代价是这一个 ctx 的原生内存留到进程结束，换的是不崩、不挂死。
        if (!unresponsive) {
            try {
                call("destroy");
            } catch (Throwable e) {
                e.printStackTrace();
            }
            try {
                releaseJS();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        executor.shutdownNow();
    }

    private void releaseJS() throws Exception {
        submitAndGet(() -> {
            if (global != null) global.destroy();
            if (jsObject != null) jsObject.release();
            if (ctx != null) ctx.destroy();
            return null;
        });
    }

    private void initializeJS() throws Exception {
        submitAndGet(() -> {
            createCtx();
            createFun();
            createObj();
            return null;
        });
    }

    private void createCtx() {
        ctx = QuickJSContext.create();
        ctx.setConsole(new Console());
        ctx.evaluate(Asset.read("js/lib/http.js"));
        ctx.getGlobalObject().setProperty("local", Local.class);
        ctx.setModuleLoader(new QuickJSContext.BytecodeModuleLoader() {
            @Override
            public String moduleNormalizeName(String baseModuleName, String moduleName) {
                return UriUtil.resolve(baseModuleName, moduleName);
            }

            @Override
            public byte[] getModuleBytecode(String moduleName) {
                return ctx.compileModule(Module.get().fetch(moduleName), moduleName);
            }
        });
    }

    private void createFun() {
        try {
            global = Global.create(ctx, executor);
            Class<?> clz = dex.loadClass("com.github.catvod.js.Function");
            clz.getDeclaredConstructor(QuickJSContext.class).newInstance(ctx);
        } catch (Throwable ignored) {
        }
    }

    private void createObj() {
        String spider = "__JS_SPIDER__";
        String global = "globalThis." + spider;
        String content = Module.get().fetch(api);
        cat = content.contains("__jsEvalReturn");
        ctx.evaluateModule(content.replace(spider, global), api);
        ctx.evaluateModule(String.format(Asset.read("js/lib/spider.js"), api));
        jsObject = (JSObject) ctx.getProperty(ctx.getGlobalObject(), spider);
    }

    private Object getExt(String ext) {
        if (!cat) return Json.isObj(ext) ? ctx.parse(ext) : ext;
        JSObject obj = ctx.createNewJSObject();
        obj.setProperty("stype", 3);
        obj.setProperty("skey", siteKey);
        if (!Json.isObj(ext)) obj.setProperty("ext", ext);
        else obj.setProperty("ext", (JSObject) ctx.parse(ext));
        return obj;
    }

    private Object[] proxy1(Map<String, String> params) throws Exception {
        JSObject obj = submitAndGet(() -> JSUtil.toObject(ctx, params));
        JSArray proxy = (JSArray) call("proxy", obj);
        String json = submitAndGet(proxy::stringify);
        JSONArray array = new JSONArray(json);
        Map<String, String> headers = array.length() > 3 ? Json.toMap(array.optString(3)) : null;
        boolean base64 = array.length() > 4 && array.optInt(4) == 1;
        Object[] result = new Object[4];
        result[0] = array.optInt(0);
        result[1] = array.optString(1);
        result[2] = getStream(array.opt(2), base64);
        result[3] = headers;
        return result;
    }

    private Object[] proxy2(Map<String, String> params) throws Exception {
        String url = params.get("url");
        String header = params.get("header");
        JSArray array = submitAndGet(() -> JSUtil.toArray(ctx, Arrays.asList(url.split("/"))));
        Object object = submitAndGet(() -> ctx.parse(header));
        String proxy = (String) call("proxy", array, object);
        Res res = Res.objectFrom(proxy);
        Object[] result = new Object[3];
        result[0] = res.getCode();
        result[1] = res.getContentType();
        result[2] = res.getStream();
        return result;
    }

    private ByteArrayInputStream getStream(Object o, boolean base64) {
        if (o instanceof byte[]) {
            return new ByteArrayInputStream((byte[]) o);
        } else {
            String content = o.toString();
            if (base64 && content.contains("base64,")) content = content.split("base64,")[1];
            return new ByteArrayInputStream(base64 ? Util.decode(content) : content.getBytes());
        }
    }
}
