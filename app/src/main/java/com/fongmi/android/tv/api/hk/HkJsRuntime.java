package com.fongmi.android.tv.api.hk;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.TextUtils;
import android.util.Base64;

import com.fongmi.android.tv.App;
import com.fongmi.quickjs.bean.Req;
import com.fongmi.quickjs.utils.Crypto;
import com.fongmi.quickjs.utils.Parser;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.logger.Logger;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ConsString;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.NetworkInterface;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Response;
import okhttp3.ResponseBody;
import android.os.Environment;
import java.net.URI;
import java.util.UUID;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.GeneratedClassLoader;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.SecurityController;
import org.mozilla.javascript.commonjs.module.provider.StrongCachingModuleScriptProvider;
import org.mozilla.javascript.commonjs.module.provider.UrlModuleSourceProvider;

/**
 * 海阔 JS 运行时（M2，设计文档 §4.4）。
 *
 * <ul>
 *   <li>每个规则一个独立 Rhino Context/Scope（隔离变量），跑在单线程 executor 上，API 全部同步实现。</li>
 *   <li>注册 Hiker 方言 API：fetch/post、setResult 系列、parseDom 系列、MY_* 变量、putVar/getVar、setItem/getItem、
 *   toast/log/setError、base64、encodeStr/decodeStr、aes、getResCode/getUrl、require。</li>
 *   <li>preRule 在首次 home/search 前各执行一次（主要用途取 cookie）。</li>
 *   <li>实现 {@link HkSelector.JsEvaluator}，给选择器的 {@code .js:} 值加工提供求值能力。</li>
 * </ul>
 *
 * 注意：lazyRule 回调按"独立作用域"铁律设计——HikerApi 只注入全局函数与形参，不依赖 JS 闭包。
 */
public class HkJsRuntime implements HkSelector.JsEvaluator, Parser.JsEvaluator {

    private static final String TAG = "HkJsRuntime";
    /** be()/batchExecute 真实实现后置 true，跨小程序内容搜索据此判断引擎是否就绪。 */
    public static volatile boolean BATCH_EXECUTE_READY = false;
    private static final Gson GSON = new Gson();
    private static final Type MAP_LIST_TYPE = new TypeToken<List<Map<String, Object>>>() {}.getType();

    private final ExecutorService executor;
    private final HkRule rule;
    private final Parser parser;
    /** JS 单线程 executor 的 worker 线程（.js: 回调时判断是否已在 JS 线程，避免死锁） */
    private volatile Thread jsThread;
    private final Map<String, String> vars;
    private final List<HkItem> results;
    /** 明细原始条目收集（parseDetailRaw 用，保留 line/col_type 等扩展字段）。 */
    private final List<Map<String, String>> rawResults = new ArrayList<>();
    private volatile boolean collectRaw;
    /** 规则内会话变量（getMyVar/putMyVar，官方 key 形态为 ruleTitle@key；单规则单实例，等价）。 */
    private final Map<String, String> myVars = new HashMap<>();

    /**
     * E8: myVar key 加 ruleTitle@ 前缀做规则间隔离（8.83 JSEngine.smali:21694）。
     * 若 key 已带 @ 则直接用（兼容旧数据）。
     */
    private String myVarKey(String key) {
        if (key == null) return "";
        if (key.contains("@")) return key;
        String title = "";
        try {
            title = rule != null && rule.getTitle() != null ? rule.getTitle() : "";
        } catch (Throwable ignored) {}
        return title + "@" + key;
    }
    /** 跨规则公共持久化（setPublicItem/getPublicItem）。 */
    private final Map<String, String> publicKv = new HashMap<>();
    /** addListener 注册的事件回调（事件名 → 回调描述）。 */
    private final Map<String, String> listeners = new HashMap<>();
    /** fc/rc 的内存缓存（url → 数据+过期时间）。 */
    private final Map<String, MemCache> memCache = new HashMap<>();
    /** setPreResult 预返回 staging：下一记 setResult/setSearchResult 先清空。 */
    private boolean preResultActive;
    /** refreshPage 请求标记（JS 线程写，宿主线程读后清零）。 */
    private volatile boolean refreshRequested;
    private volatile boolean refreshToTop;
    /** goTo(url) 导航请求（JS 线程写，宿主线程读后清零）。规则如 xhotvideo 用 goTo('hiker://page/detail?...') 跳 V4。 */
    private volatile String goToUrl;
    /** setPageTitle/getPageTitle 的页标题状态。 */
    private String pageTitle = "";
    /** setPagePicUrl：官方 SetPagePicEvent 语义，宿主存字段供上层取用。 */
    private String pagePicUrl = "";
    /** setLastChapterRule：官方存最新章节规则，宿主存字段供上层取用。 */
    private String lastChapterRule = "";
    /** setStrResult/setLastChapterResult：官方 JS 回调字符串结果，宿主存字段供上层取用。 */
    private volatile String strResult = "";
    /** 当前页码（MY_PAGE 注入用）。 */
    private int page = 1;
    /** 列表页上下文（MY_TYPE/MY_CLASS_URL/MY_CLASS_NAME/MY_PARAMS/MY_AREA/MY_YEAR/MY_SORT 注入用）。 */
    private String myType = "";
    private String myClassUrl = "";
    private String myClassName = "";
    private String myParams = "";
    private String myArea = "";
    private String myYear = "";
    private String mySort = "";

    /** evalPrivateJS 的 AES 密钥（官方 AesUtil.decrypt 写死值）。 */
    private static final String AES_PRIVATE_KEY = "hk6666666109";
    private static final String UA_MOBILE = "Mozilla/5.0 (Linux; Android 15; 2407FRK8EC Build/AP3A.240617.008; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.127 Mobile Safari/537.36";
    private static final String UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** 内存缓存条目。 */
    private static class MemCache {
        final String data;
        final long expireAt;
        MemCache(String data, long expireAt) {
            this.data = data;
            this.expireAt = expireAt;
        }
        boolean alive() {
            return System.currentTimeMillis() < expireAt;
        }
    }

    private Context rhinoCx;
    private ScriptableObject scope;
    private Map<String, String> kv;
    private String error;
    /** preRule 执行失败的根因（动态域名类规则排障用）；parseList 会清 error，本字段独立保留。 */
    private String preRuleError;
    private int resCode;
    /** @rule= js: 预检时设置的响应正文（HkHttp.get 取回），供 getResCode() 返回；用后清零。 */
    private String evalResCodeBody;
    private String lastUrl;
    private volatile boolean destroyed;
    private volatile boolean preRuleDone;
    /**
     * 入口口令/输入框一次性 input 覆盖（官方 ArticleListFragment.clickItem 语义）：
     * input 类型条目点击时，把用户在 EditText 里输入的文本作为 input 全局变量注入，
     * 而不是默认的 pageUrl。set 后仅下一次 evalLazy 生效，读后清零。
     */
    private volatile String nextInputOverride;
    /** JS 定时器（setTimeout/setInterval）：id → ScheduledFuture；回调统一提交回 JS 单线程执行。 */
    private final Map<Integer, ScheduledFuture<?>> jsTimers = new HashMap<>();
    private final ScheduledExecutorService timerScheduler = Executors.newScheduledThreadPool(1);
    private final AtomicInteger timerIdSeq = new AtomicInteger(1);
    /** 8.83 registerTask 定时任务：taskId(String) → ScheduledFuture；JS 字符串延迟单次执行。 */
    private final Map<String, ScheduledFuture<?>> jsTasks = new HashMap<>();

    /** QuickJS 风格的函数签名（Object[] args -> Object），供 putFunction 注册。 */
    private interface JsFunc {
        Object call(Object[] args);
    }

    /**
     * 把 lambda 注册为 scope 上的全局函数（Rhino BaseFunction）。
     * 8.83 官方语义（JSEngine.smali）：桥接函数内不使用 Context.javaToJS
     * （22 处调用全在 runScript/evalJS 的 scope 全局注入）；Java 返回值靠 Rhino
     * LiveConnect 自动包装。Java null 直接返回（Rhino 转为 JS null，
     * 与官方 STRING/OBJECT 模板的 null 语义一致），不转 undefined。
     */
    private void putFunction(String name, JsFunc fn) {
        putFunction(scope, name, fn);
    }

    private void putFunction(ScriptableObject target, String name, JsFunc fn) {
        ScriptableObject.putProperty(target, name, new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                Object[] a = args == null ? new Object[0] : args;
                for (int i = 0; i < a.length; i++) {
                    if (a[i] == Context.getUndefinedValue()) a[i] = null;
                }
                return fn.call(a);
            }
        });
    }

    /** 求值（替代 ctx.evaluate）。 */
    private Object evalJs(String js) {
        return rhinoCx.evaluateString(scope, js, "hk", 1, null);
    }

    /**
     * JS 定时器实现（setTimeout/setInterval）。
     * 调度走独立 ScheduledExecutorService，到期后把回调提交回 JS 单线程 executor 执行
     *（Rhino Context 与 JS 线程绑定，跨线程直接求值会崩）。返回数字 timerId。
     */
    private int jsSetTimer(Object[] args, boolean repeat) {
        if (destroyed) return 0;
        if (args == null || args.length == 0) return 0;
        final Object cb = args[0];
        long delay = 0;
        if (args.length > 1 && args[1] instanceof Number) delay = Math.max(0, ((Number) args[1]).longValue());
        final Object[] cbArgs;
        if (args.length > 2) {
            cbArgs = new Object[args.length - 2];
            System.arraycopy(args, 2, cbArgs, 0, cbArgs.length);
        } else {
            cbArgs = new Object[0];
        }
        final int id = timerIdSeq.getAndIncrement();
        final long period = Math.max(1, delay);
        Runnable fire = () -> {
            try {
                submit(() -> {
                    try {
                        if (destroyed) return null;
                        synchronized (jsTimers) {
                            if (!jsTimers.containsKey(id)) return null;
                            if (!repeat) jsTimers.remove(id);
                        }
                        fireTimerCallback(cb, cbArgs);
                    } catch (Throwable t) {
                        Logger.t(TAG).d("timer fire failed: " + t.getMessage());
                    }
                    return null;
                });
            } catch (Throwable ignored) {
            }
        };
        try {
            ScheduledFuture<?> f = repeat
                    ? timerScheduler.scheduleAtFixedRate(fire, delay, period, TimeUnit.MILLISECONDS)
                    : timerScheduler.schedule(fire, delay, TimeUnit.MILLISECONDS);
            synchronized (jsTimers) {
                jsTimers.put(id, f);
            }
            return id;
        } catch (Throwable t) {
            Logger.t(TAG).d("setTimer failed: " + t.getMessage());
            return 0;
        }
    }

    /** 取消 JS 定时器（clearTimeout/clearInterval 共用）。 */
    private void jsClearTimer(Object[] args) {
        if (args == null || args.length == 0) return;
        int id = 0;
        try {
            if (args[0] instanceof Number) id = ((Number) args[0]).intValue();
            else id = Integer.parseInt(String.valueOf(args[0]));
        } catch (Throwable ignored) {
            return;
        }
        if (id <= 0) return;
        ScheduledFuture<?> f;
        synchronized (jsTimers) {
            f = jsTimers.remove(id);
        }
        if (f != null) {
            try {
                f.cancel(false);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 在 JS 线程上执行定时器回调：Function 直接调用，字符串则求值。 */
    private void fireTimerCallback(Object cb, Object[] cbArgs) {
        try {
            if (cb instanceof Function) {
                ((Function) cb).call(rhinoCx, scope, scope, cbArgs);
            } else {
                String code = argStr(cb);
                if (!TextUtils.isEmpty(code)) evalJs(code);
            }
        } catch (Throwable t) {
            error = t.getMessage();
            Logger.t(TAG).d("timer callback failed: " + t.getMessage());
        }
    }

    /** 清理全部 JS 定时器（页面销毁/规则切换时调用，避免泄漏）。 */
    private void clearAllTimers() {
        Map<Integer, ScheduledFuture<?>> copy;
        synchronized (jsTimers) {
            copy = new HashMap<>(jsTimers);
            jsTimers.clear();
        }
        for (ScheduledFuture<?> f : copy.values()) {
            try {
                f.cancel(false);
            } catch (Throwable ignored) {
            }
        }
        // 8.83 registerTask 任务一并清理
        Map<String, ScheduledFuture<?>> copy2;
        synchronized (jsTasks) {
            copy2 = new HashMap<>(jsTasks);
            jsTasks.clear();
        }
        for (ScheduledFuture<?> f : copy2.values()) {
            try {
                f.cancel(false);
            } catch (Throwable ignored) {
            }
        }
    }

    /** JSON 解析（替代 ctx.parse，即 JSON.parse 语义）：非法 JSON 返回带原型的空对象，不抛异常。
     * 8.83 做法：Java 侧不造裸 NativeObject（无原型会导致 JS 拼字符串时抛"未找到对象默认值"），
     * 一律用 cx.newObject(scope) 创建带原型链的对象。 */
    private Object parseJson(String json) {
        if (TextUtils.isEmpty(json)) return rhinoCx.newObject(scope);
        try {
            Object r = NativeJSON.parse(rhinoCx, scope, json, null);
            return r == null ? rhinoCx.newObject(scope) : r;
        } catch (Throwable e) {
            return rhinoCx.newObject(scope);
        }
    }

    /** JSON 序列化（替代 JSObject.stringify）。 */
    private String jsStringify(Object value) {
        try {
            Object r = NativeJSON.stringify(rhinoCx, scope, value, null, null);
            if (r == null || r == Context.getUndefinedValue()) return "null";
            return Context.toString(r);
        } catch (Throwable e) {
            return String.valueOf(value);
        }
    }

    /** evaluateString 结果转字符串（undefined/null 归一为空）。 */
    private static String jsResultToString(Object r) {
        if (r == null || r == Context.getUndefinedValue()) return "";
        return String.valueOf(r);
    }

    /**
     * 8.83 JSEngine.isUndefined（smali 5714-5738）：字符串 "undefined" 或 Rhino Undefined。
     */
    private static boolean isUndefined(Object o) {
        return "undefined".equals(o) || Undefined.isUndefined(o);
    }

    /**
     * 8.83 JSEngine.argsNativeObjectAdjust(Object)（smali 704-716）：调双参版本，p2=false。
     */
    private Object argsNativeObjectAdjust(Object o) {
        return argsNativeObjectAdjust(o, false);
    }

    /**
     * 8.83 JSEngine.argsNativeObjectAdjust(Object, boolean)（smali 717-897）：
     * 递归把 NativeObject 转为 Map、NativeArray 转为 List；p2=true 时跳过 "param" 键的递归；
     * ConsString 取字符串；NativeJavaObject 解包；其余原样返回。
     * 注：8.83 用 fastjson 的 JSONObject/JSONArray，本项目未引入 fastjson，
     * 用 JDK 的 LinkedHashMap/ArrayList 等价替代（getString 只判断 instanceof String，行为一致）。
     */
    private Object argsNativeObjectAdjust(Object o, boolean skipParam) {
        if (isUndefined(o)) return o;
        if (o instanceof NativeObject) {
            Map<String, Object> map = new LinkedHashMap<>();
            NativeObject no = (NativeObject) o;
            for (Object key : no.keySet()) {
                Object val = no.get(key);
                if (!skipParam || !"param".equals(key)) {
                    val = argsNativeObjectAdjust(val, skipParam);
                }
                try {
                    map.put(key + "", val);
                } catch (Throwable e) {
                    Logger.t(TAG).e(e, "argsNativeObjectAdjust put failed");
                }
            }
            return map;
        }
        if (o instanceof NativeArray) {
            List<Object> list = new ArrayList<>();
            NativeArray na = (NativeArray) o;
            for (int i = 0; i < na.size(); i++) {
                list.add(argsNativeObjectAdjust(na.get(i), skipParam));
            }
            return list;
        }
        if (o instanceof ConsString) return o.toString();
        if (o instanceof NativeJavaObject) o = ((NativeJavaObject) o).unwrap();
        return o;
    }

    /**
     * 8.83 JSEngine.getString（smali 5320-5343）：永不对 JS 对象调 toString，
     * 非 String 一律返回 null（91短视频 TypeError: 未找到对象默认值 根因修复）。
     */
    private String getString(Object o) {
        Object adjusted = argsNativeObjectAdjust(o);
        if (adjusted instanceof String) return (String) adjusted;
        return null;
    }

    /**
     * JS 参数转字符串（8.83 getString 语义 + 调用方判空惯例）：
     * 先走 getString；Number/Boolean 保留 Java 字符串形式（sleep/parseBoolean 等数值参数兼容，
     * 对应 8.83 getIntValue 对数值的保留）；其余（JS 对象/undefined/null）兜底 ""。
     */
    private String argStr(Object o) {
        String s = getString(o);
        if (s != null) return s;
        if (o instanceof Number || o instanceof Boolean) return String.valueOf(o);
        return "";
    }

    /**
     * JS 对象转 Map（用于 proxyClearM3u8 的 headers 参数解析）。
     * 支持 Rhino NativeObject 和 Java Map。
     */
    @SuppressWarnings("unchecked")
    private java.util.Map<?, ?> toStringMap(Object o) {
        if (o == null) return null;
        Object adjusted = argsNativeObjectAdjust(o);
        if (adjusted instanceof java.util.Map) return (java.util.Map<?, ?>) adjusted;
        return null;
    }

    /** MD5（用于 proxyClearM3u8 缓存文件名，对应 8.83 StringUtil.md5）。 */
    private String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable e) {
            return String.valueOf(s.hashCode());
        }
    }

    /** List<String> -> JS 数组（替代 JSUtil.toArray）。
     * 8.83 做法：用 cx.newArray 创建带原型链的数组，不用裸 new NativeArray()。 */
    private NativeArray toJsArray(List<String> items) {
        NativeArray arr = (NativeArray) rhinoCx.newArray(scope, items == null ? 0 : items.size());
        if (items != null) {
            for (int i = 0; i < items.size(); i++) arr.put(i, arr, items.get(i));
        }
        return arr;
    }

    /** byte[] -> JS 数组（替代 JSUtil.toArray）。
     * 8.83 做法：用 cx.newArray 创建带原型链的数组，不用裸 new NativeArray()。 */
    private NativeArray toJsArray(byte[] bytes) {
        NativeArray arr = (NativeArray) rhinoCx.newArray(scope, bytes == null ? 0 : bytes.length);
        if (bytes != null) {
            for (int i = 0; i < bytes.length; i++) arr.put(i, arr, (int) bytes[i]);
        }
        return arr;
    }

    public HkJsRuntime(HkRule rule) {
        this.rule = rule;
        this.executor = Executors.newSingleThreadExecutor();
        this.parser = new Parser();
        this.parser.setJsEvaluator(this);
        this.vars = new HashMap<>();
        this.results = new ArrayList<>();
        this.kv = new HashMap<>();
        loadPublicKv();
    }

    private <T> Future<T> submit(java.util.concurrent.Callable<T> callable) {
        return executor.submit(callable);
    }

    /**
     * 在 JS 线程上初始化：建 Context、注册 API、加载 kv、跑 preRule。
     */
    public void init() throws Exception {
        submit(() -> {
            jsThread = Thread.currentThread();
            // Rhino：每个规则独立 Context + Scope（变量隔离），跑在单线程 executor 上；
            // setOptimizationLevel(-1) 禁用优化器（Android 无字节码生成，必须关）
            rhinoCx = Context.enter();
            rhinoCx.setOptimizationLevel(-1);
            scope = rhinoCx.initStandardObjects();
            registerApi();
            // D1: build 8.83 allFunctions prelude (JSAnnotation-reflected stubs + MY_UA suffix).
            try {
                allFunctions = String.format(getAllFunctions(), HkJsRuntime.class.getName())
                        + "\n var MY_UA = JSON.parse(getUaObject());\n var MOBILE_UA =  MY_UA.mobileUa;\n var PC_UA = MY_UA.pcUa";
            } catch (Throwable t) {
                Logger.t(TAG).e(t, "build allFunctions");
                allFunctions = "";
            }
            // 官方内置 CryptoJS（aes.js）：自动注入为全局，兼容直接使用 CryptoJS 的规则；
            // 库本身幂等（var CryptoJS = CryptoJS || ...），规则再 eval(getCryptoJS()) 无害
            try {
                String cryptoJs = loadAsset("aes.js");
                if (!TextUtils.isEmpty(cryptoJs)) evalJs(cryptoJs);
            } catch (Throwable e) {
                Logger.t(TAG).d("inject CryptoJS failed: " + e.getMessage());
            }
            // 官方内置 Hikerurl.js（$ 工具库）：自动注入为全局，定义 $.lazyRule/$.rule/$.image 等；
            // 官方 JSEngine.allFunctionsEnd 会 eval(getJsPlugin())，规则直接调 $ 方法时也需要
            // Hikerurl.js 引用官方 Java 类 com.example.hikerview..RequireUtils（模块缓存映射），
            // 宿主无此 Java 类时整文件求值失败导致 $ 残缺；先在 JS 层 stub 该命名空间（官方调用处有 try-catch）
            try {
                evalJs(
                    "var com = (typeof com !== 'undefined') ? com : {};\n" +
                    "com.example = com.example || {};\n" +
                    "com.example.hikerview = com.example.hikerview || {};\n" +
                    "com.example.hikerview.ui = com.example.hikerview.ui || {};\n" +
                    "com.example.hikerview.ui.rules = com.example.hikerview.ui.rules || {};\n" +
                    "com.example.hikerview.ui.rules.service = com.example.hikerview.ui.rules.service || {};\n" +
                    "com.example.hikerview.ui.rules.service.require = com.example.hikerview.ui.rules.service.require || {};\n" +
                    "com.example.hikerview.ui.rules.service.require.RequireUtils = " +
                    "com.example.hikerview.ui.rules.service.require.RequireUtils || { generateRequireMap: function() {} };\n"
                );
                String hikerUrlJs = loadAsset("Hikerurl.js");
                if (!TextUtils.isEmpty(hikerUrlJs)) evalJs(hikerUrlJs);
            } catch (Throwable e) {
                Logger.t(TAG).d("inject Hikerurl.js failed: " + e.getMessage());
            }
            loadKv();
            loadConfig();
            runPreRule();
            return null;
        }).get();
    }

    // ================= API 注册 =================

    private void registerApi() {
        // 覆盖 JSON.parse：空串/非法 JSON 返回带原型空对象，不抛异常（8.83 lenient 语义）；
        // JSON.stringify 保持原生不动。
        Object jsonObj = ScriptableObject.getProperty(scope, "JSON");
        if (jsonObj instanceof ScriptableObject) {
            ScriptableObject json = (ScriptableObject) jsonObj;
            json.put("parse", json, new BaseFunction() {
                @Override
                public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                    Object a0 = (args != null && args.length > 0) ? args[0] : null;
                    if (a0 == null || a0 == Context.getUndefinedValue()) return parseJson(null);
                    return parseJson(Context.toString(a0));
                }
            });
        }
        // ---- 结果返回 ----
        putFunction("setResult", args -> {
            beginResult();
            collectResult(args);
            return null;
        });
        putFunction("setHomeResult", args -> {
            beginResult();
            collectHomeResult(args);
            return null;
        });
        putFunction("setSearchResult", args -> {
            beginResult();
            collectResult(args);
            return null;
        });
        // setPreResult：预返回（官方要求与 setResult 成对、顺序固定）。
        // 语义：先占位展示，下一记 setResult/setSearchResult 到达时整体替换。
        putFunction("setPreResult", args -> {
            results.clear();
            rawResults.clear();
            collectResult(args);
            preResultActive = true;
            Logger.t(TAG).d("setPreResult: %d items staged", results.size());
            return null;
        });
        // ------------------------------------------------------------------
        // 8.83: setError(Object o, Object callbackKey, Object ruleKey, Object evalKey)V
        // [JSEngine.smali] alias "error". D2: 完整实现。
        // o: String | JavaScriptException | RhinoException | ScriptableObject({fileName,lineNumber,lineSource})
        // 构造 "\n来源：\n代码：\n行数：\n详情：" 明细；超 2000 字截断；查 callbackMap：
        // 有回调 -> callback.showErr(msg)；无 -> 宿主 Toast。
        // ------------------------------------------------------------------
        putFunction("setError", args -> {
            setErrorImpl(args);
            return null;
        });
        // 官方别名：error 即 setError
        putFunction("error", args -> {
            setErrorImpl(args);
            return null;
        });

        // ---- 网络 ----
        putFunction("fetch", args -> {
            if (args == null || args.length == 0) return "";
            String url = argStr(args[0]);
            // D2：hiker://assets/ 走内置 asset（官方 HttpHelper.fetch 经 fetchByHiker 拦截同款）
            if (url.startsWith("hiker://assets/")) return loadAssetText(url);
            // 8.83 fetchByHiker 同款：hiker://page/、hiker://files/、file://、通用 hiker://
            // 走内部协议分发返回内容，不再直接掉进 fetchSync 返回 ""（曾导致 JSON.parse("") 抛 Empty JSON string）
            if (url.startsWith("hiker://") || url.startsWith("file://")) {
                Object r = fetchByHiker(url, null, false, false);
                return r instanceof String ? (String) r : "";
            }
            String options = args.length > 1 && args[1] != null ? stringifyArg(args[1]) : null;
            return fetchSync(url, options);
        });
        putFunction("post", args -> {
            if (args == null || args.length == 0) return "";
            String url = argStr(args[0]);
            // 8.83 post0→fetch0 经 RuleFetchDelegate→fetchByHiker 拦截同款：内部协议走分发
            if (url.startsWith("hiker://") || url.startsWith("file://")) {
                Object r = fetchByHiker(url, null, false, false);
                return r instanceof String ? (String) r : "";
            }
            String options = args.length > 1 && args[1] != null ? stringifyArg(args[1]) : "{}";
            options = mergeMethod(options, "post");
            // N1: 8.83 的 post 默认补移动端 UA（JSEngine.smali:21353），裸 fetch 才不补
            options = injectMobileUa(options);
            return fetchSync(url, options);
        });
        putFunction("request", args -> {
            if (args == null || args.length == 0) return "";
            String url = argStr(args[0]);
            // hiker://page/<path> 内部协议：从规则 pages 里按 path 取页面规则，返回 {"rule": "..."}
            if (url.startsWith("hiker://page/")) return handlePageRequest(url);
            // D2：hiker://assets/ 走内置 asset（官方 $.require 经 getScript→request() 取脚本同款）
            if (url.startsWith("hiker://assets/")) return loadAssetText(url);
            // 8.83 fetchByHiker 同款：hiker://files/、file://、通用 hiker:// 走内部协议分发
            if (url.startsWith("hiker://") || url.startsWith("file://")) {
                Object r = fetchByHiker(url, null, false, false);
                return r instanceof String ? (String) r : "";
            }
            String options = args.length > 1 && args[1] != null ? stringifyArg(args[1]) : null;
            // N1: 8.83 的 request 默认补移动端 UA（JSEngine.smali:22856），裸 fetch 才不补
            options = injectMobileUa(options);
            return fetchSync(url, options);
        });
        putFunction("getResCode", args -> {
            // 8.83 (JSEngine.smali:17733): getResCode(callbackKey) 返回 resCodeMap 中的响应正文 (String)，
            // 不是 int 状态码。规则常写 getResCode() || ''，返回 0 会导致空内容。
            // 1. JS 显式传了 callbackKey 参数时用它查表
            if (args != null && args.length > 0 && args[0] != null) {
                try {
                    String key = String.valueOf(argsNativeObjectAdjust(args[0]));
                    String v = resCodeMap.get(key);
                    if (v != null) return v;
                } catch (Throwable ignored) {}
            }
            // 2. 从当前 scope 取 CALLBACK_KEY 查表（parseHome/parseStr 等流程注入）
            try {
                Object ck = scope.get("CALLBACK_KEY", scope);
                if (ck != null && ck != Scriptable.NOT_FOUND) {
                    String key = String.valueOf(argsNativeObjectAdjust(ck));
                    String v = resCodeMap.get(key);
                    if (v != null) return v;
                }
            } catch (Throwable ignored) {}
            // 3. fallback: @rule= js: 预检时设置的正文
            if (evalResCodeBody != null) return evalResCodeBody;
            return "";
        });
        // 官方别名：getCode 即 getResCode（8.83 smali alias="getCode"）
        putFunction("getCode", args -> {
            if (args != null && args.length > 0 && args[0] != null) {
                try {
                    String key = String.valueOf(argsNativeObjectAdjust(args[0]));
                    String v = resCodeMap.get(key);
                    if (v != null) return v;
                } catch (Throwable ignored) {}
            }
            try {
                Object ck = scope.get("CALLBACK_KEY", scope);
                if (ck != null && ck != Scriptable.NOT_FOUND) {
                    String key = String.valueOf(argsNativeObjectAdjust(ck));
                    String v = resCodeMap.get(key);
                    if (v != null) return v;
                }
            } catch (Throwable ignored) {}
            if (evalResCodeBody != null) return evalResCodeBody;
            return "";
        });
        putFunction("getUrl", args -> lastUrl == null ? "" : lastUrl);

        // ---- DOM 解析（复用 Parser，即海阔 parseHikerToJq 路径） ----
        putFunction("parseDom", args -> {
            if (args == null || args.length < 2) return "";
            String urlKey = args.length > 2 && args[2] != null ? argStr(args[2]) : (lastUrl == null ? "" : lastUrl);
            return parser.pdfh(argStr(args[0]), sel(args[1]), urlKey);
        });
        putFunction("pd", args -> {
            if (args == null || args.length < 2) return "";
            String urlKey = args.length > 2 && args[2] != null ? argStr(args[2]) : (lastUrl == null ? "" : lastUrl);
            return parser.pdfh(argStr(args[0]), sel(args[1]), urlKey);
        });
        putFunction("parseDomForHtml", args -> {
            if (args == null || args.length < 2) return "";
            return parser.pdfh(argStr(args[0]), sel(args[1]), "");
        });
        putFunction("pdfh", args -> {
            if (args == null || args.length < 2) return "";
            return parser.pdfh(argStr(args[0]), sel(args[1]), "");
        });
        putFunction("parseDomForArray", args -> {
            if (args == null || args.length < 2) return toJsArray(new ArrayList<>());
            List<String> items = parser.pdfa(argStr(args[0]), sel(args[1]));
            return toJsArray(items);
        });
        putFunction("pdfa", args -> {
            if (args == null || args.length < 2) return toJsArray(new ArrayList<>());
            List<String> items = parser.pdfa(argStr(args[0]), sel(args[1]));
            return toJsArray(items);
        });

        // ---- 存储 ----
        putFunction("putVar", args -> {
            if (args != null && args.length > 1) vars.put(argStr(args[0]), argStr(args[1]));
            return null;
        });
        // 官方 putVar2：值非字符串时 JSON 序列化后存入（与 putVar 区别仅在此）
        putFunction("putVar2", args -> {
            if (args == null || args.length < 2) return null;
            String k = argStr(args[0]);
            if (TextUtils.isEmpty(k)) return null;
            Object v = args[1];
            String s;
            if (v instanceof String) s = (String) v;
            else {
                try { s = stringifyArg(v); }
                catch (Throwable ignored) { s = String.valueOf(v); }
            }
            vars.put(k, s);
            return null;
        });
        putFunction("getVar", args -> {
            if (args == null || args.length == 0) return "";
            String v = vars.get(argStr(args[0]));
            // 8.83 官方 getVar：default 为 undefined/null 时返回 ""，不是 "null" 字符串
            if (v == null && args.length > 1 && args[1] != null) v = argStr(args[1]);
            return v == null ? "" : v;
        });
        putFunction("setItem", args -> {
            if (args != null && args.length > 1) {
                kv.put(argStr(args[0]), argStr(args[1]));
                saveKv();
            }
            return null;
        });
        putFunction("getItem", args -> {
            if (args == null || args.length == 0) return "";
            String v = kv.get(argStr(args[0]));
            if (v == null && args.length > 1) v = argStr(args[1]);
            return v == null ? "" : v;
        });

        // ---- 调试 ----
        putFunction("toast", args -> {
            final String msg = args != null && args.length > 0 ? argStr(args[0]) : "";
            Logger.t(TAG).d("toast: %s", msg);
            try {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(App.get(), msg, android.widget.Toast.LENGTH_SHORT).show());
            } catch (Throwable ignored) {
            }
            return null;
        });
        putFunction("log", args -> {
            Logger.t(TAG).d("%s", args != null && args.length > 0 ? argStr(args[0]) : "");
            return null;
        });

        // ---- 交互/配置（海阔规则常用，preRule 里调） ----
        putFunction("confirm", args -> {
            // 官方弹确认框；我们无 UI，直接走 confirm 回调（若有）并返回 true
            try {
                if (args != null && args.length > 0 && args[0] != null) {
                    String json = stringifyArg(args[0]);
                    Logger.t(TAG).d("confirm: %s", json);
                }
            } catch (Throwable ignored) {
            }
            return true;
        });
        putFunction("initConfig", args -> {
            // initConfig({host: ...})：把配置持久化到 plugins/hk/config/<规则名>.json，供 config.xxx 读取
            try {
                if (args != null && args.length > 0 && args[0] != null) {
                    String json = stringifyArg(args[0]);
                    if (!TextUtils.isEmpty(json) && json.startsWith("{")) {
                        HkRuleManager.get().saveRuleConfig(rule.getTitle(), json);
                        // 同步刷新当前 ctx 的 config 对象
                        try {
                            Object obj = parseJson(json);
                            ScriptableObject.putProperty(scope, "config", obj);
                        } catch (Throwable ignored) {
                        }
                        Logger.t(TAG).d("initConfig saved for %s", rule.getTitle());
                    }
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("initConfig failed: %s", e.getMessage());
            }
            return null;
        });
        putFunction("getParam", args -> {
            // getParam('word')：从 MY_URL 的 query 里取值（$('...').rule(fn) 场景）
            if (args == null || args.length == 0) return "";
            String key = argStr(args[0]);
            try {
                String u = lastUrl == null ? "" : lastUrl;
                int q = u.indexOf('?');
                if (q < 0) return "";
                String query = u.substring(q + 1);
                int h = query.indexOf('#');
                if (h >= 0) query = query.substring(0, h);
                for (String kv : query.split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0 && kv.substring(0, eq).equals(key)) {
                        return java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                    }
                }
            } catch (Throwable ignored) {
            }
            return "";
        });

        // ---- 编解码 ----
        putFunction("base64Encode", args -> {
            if (args == null || args.length == 0) return "";
            return Util.base64(argStr(args[0]).getBytes(Charset.forName("UTF-8")));
        });
        putFunction("base64Decode", args -> {
            if (args == null || args.length == 0) return "";
            try {
                return new String(Base64.decode(argStr(args[0]), Base64.DEFAULT), Charset.forName("UTF-8"));
            } catch (Throwable e) {
                return "";
            }
        });
        putFunction("encodeStr", args -> {
            if (args == null || args.length == 0) return "";
            try {
                String charset = args.length > 1 ? argStr(args[1]) : "UTF-8";
                return Util.base64(argStr(args[0]).getBytes(Charset.forName(charset)));
            } catch (Throwable e) {
                return "";
            }
        });
        putFunction("decodeStr", args -> {
            if (args == null || args.length == 0) return "";
            try {
                String charset = args.length > 1 ? argStr(args[1]) : "UTF-8";
                return new String(Base64.decode(argStr(args[0]), Base64.DEFAULT), Charset.forName(charset));
            } catch (Throwable e) {
                return "";
            }
        });
        putFunction("aesEncode", args -> {
            if (args == null || args.length < 2) return "";
            String key = argStr(args[1]);
            String iv = args.length > 2 ? argStr(args[2]) : "";
            return Crypto.aes("AES/CBC/PKCS5Padding", true, argStr(args[0]), false, key, iv, true);
        });
        putFunction("aesDecode", args -> {
            if (args == null || args.length < 2) return "";
            String key = argStr(args[1]);
            String iv = args.length > 2 ? argStr(args[2]) : "";
            return Crypto.aes("AES/CBC/PKCS5Padding", false, argStr(args[0]), true, key, iv, false);
        });

        // ---- require：远程库加载（preRule 常用）；本地 libs/<md5(url)>.js 优先（.hkzip 自带库） ----
        putFunction("require", args -> {
            if (args == null || args.length == 0) return null;
            try {
                String libUrl = argStr(args[0]);
                String code = loadLibLocal(libUrl);
                if (code == null) code = fetchSync(libUrl, null);
                if (!TextUtils.isEmpty(code)) evalJs(code);
            } catch (Throwable e) {
                Logger.t(TAG).d("require failed: %s", e.getMessage());
            }
            return null;
        });

        // ---- JS 定时器（setTimeout/setInterval/clearTimeout/clearInterval） ----
        // 规则"20秒后继续"倒计时等场景依赖；回调统一提交回 JS 单线程执行（Rhino Context 线程绑定）。
        putFunction("setTimeout", args -> jsSetTimer(args, false));
        putFunction("setInterval", args -> jsSetTimer(args, true));
        putFunction("clearTimeout", args -> {
            jsClearTimer(args);
            return null;
        });
        putFunction("clearInterval", args -> {
            jsClearTimer(args);
            return null;
        });

        // ---- __hkShowUpdateLog：更新日志底部弹窗（星辰原生重实现） ----
        // 规则的 hikerPop.updateRecordsBottom 依赖 8.83 内部类（MyStatusBarUtil 等），
        // 在星辰包名下跑不起来。此处原生实现 8.83 UpdateRecordsBottom 样式：
        // 从底部滑出，标题"更新日志"，列表展示各版本 records，右下"知道啦"关闭。
        putFunction("__hkShowUpdateLog", args -> {
            if (args == null || args.length == 0) return null;
            final String json = argStr(args[0]);
            com.fongmi.android.tv.App.post(() -> {
                try {
                    com.fongmi.android.tv.api.hk.HkUpdateLogPopup.show(json);
                } catch (Throwable t) {
                    android.util.Log.d("HkJs", "__hkShowUpdateLog failed: " + t.getMessage());
                }
            });
            return null;
        });

        // ---- __hkRequirePage：$.require(path) 的 Java 实现 ----
        // hiker://page/<path>：从规则 pages 按 path 找页面代码，IIFE 包裹求值（防顶层 const 重声明），
        // 页面代码以 $.exports = xxx 导出，JS 层 $.require 直接返回 $.exports（官方同款语义）。
        // 裸页面名（如 $.require("Cate")，无 :// scheme）：官方语义也是先查规则 pages，
        // 找不到再走远程/本地库逻辑。其他路径：复用 require 的远程/本地库逻辑。
        putFunction("__hkRequirePage", args -> {
            if (args == null || args.length == 0) return null;
            String path = argStr(args[0]);
            try {
                String pageCode = null;
                boolean isRulePage = false;
                if (path.startsWith("hiker://assets/")) {
                    // hiker://assets/xxx.js：映射到宿主内置 assets（海阔官方同款内置库，如 crypto-java.js）
                    String assetPath = path.substring("hiker://assets/".length());
                    int q = assetPath.indexOf('?');
                    if (q >= 0) assetPath = assetPath.substring(0, q);
                    int h = assetPath.indexOf('#');
                    if (h >= 0) assetPath = assetPath.substring(0, h);
                    String code = loadAsset(assetPath.trim());
                    if (TextUtils.isEmpty(code)) {
                        Logger.t(TAG).d("$.require: no built-in asset for %s", assetPath);
                        return null;
                    }
                    try {
                        evalJs(stripJsPrefix(code));
                    } catch (Throwable e) {
                        Logger.t(TAG).d("$.require: asset eval failed %s: %s", assetPath, e.getMessage());
                    }
                    return null;
                } else if (path.startsWith("hiker://page/")) {
                    String p = path.substring("hiker://page/".length());
                    int q = p.indexOf('?');
                    if (q >= 0) p = p.substring(0, q);
                    int h = p.indexOf('#');
                    if (h >= 0) p = p.substring(0, h);
                    pageCode = findPageCode(p.trim());
                    isRulePage = true;
                    if (pageCode == null) {
                        Logger.t(TAG).d("$.require: no page for path=%s", p);
                        return null;
                    }
                } else if (path.indexOf("://") < 0) {
                    // 裸页面名：先查规则 pages，找不到再走远程/本地库逻辑
                    pageCode = findPageCode(path.trim());
                    isRulePage = pageCode != null;
                }
                if (isRulePage) {
                    evalJs("(function(){\n" + stripJsPrefix(pageCode) + "\n})();");
                } else {
                    String code = loadLibLocal(path);
                    if (code == null) code = fetchSync(path, null);
                    if (TextUtils.isEmpty(code)) {
                        Logger.t(TAG).d("$.require: empty lib %s", path);
                        return null;
                    }
                    evalJs(stripJsPrefix(code));
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("$.require failed %s: %s", path, e.getMessage());
            }
            return null;
        });

        // ---- 规则内会话变量（getMyVar/putMyVar/clearMyVar/listMyVarKeys） ----
        // E8: key 加 ruleTitle@ 前缀做规则间隔离（8.83 JSEngine.smali:21694）
        putFunction("putMyVar", args -> {
            // E8: 8.83 putMyVar 对象值 JSON 序列化存（JSEngine.smali:21861），key 加 ruleTitle@ 前缀隔离
            if (args != null && args.length > 1) myVars.put(myVarKey(argStr(args[0])), stringifyArg(args[1]));
            return null;
        });
        putFunction("getMyVar", args -> {
            if (args == null || args.length == 0) return "";
            String v = myVars.get(myVarKey(argStr(args[0])));
            if (v == null && args.length > 1) v = argStr(args[1]);
            return v == null ? "" : v;
        });
        putFunction("clearMyVar", args -> {
            // E8: 8.83 无"无参清空全表"行为（JSEngine.smali:12188），无参时不做任何事，避免误删其他规则的变量
            if (args != null && args.length > 0) myVars.remove(myVarKey(argStr(args[0])));
            return null;
        });
        putFunction("listMyVarKeys", args -> GSON.toJson(new ArrayList<>(myVars.keySet())));
        putFunction("clearVar", args -> {
            vars.clear();
            return null;
        });

        // ---- 跨规则公共持久化（setPublicItem/getPublicItem/clearPublicItem） ----
        putFunction("setPublicItem", args -> {
            if (args != null && args.length > 1) {
                publicKv.put(argStr(args[0]), argStr(args[1]));
                savePublicKv();
            }
            return null;
        });
        putFunction("getPublicItem", args -> {
            if (args == null || args.length == 0) return "";
            String v = publicKv.get(argStr(args[0]));
            if (v == null && args.length > 1) v = argStr(args[1]);
            return v == null ? "" : v;
        });
        putFunction("clearPublicItem", args -> {
            if (args != null && args.length > 0) publicKv.remove(argStr(args[0]));
            else publicKv.clear();
            savePublicKv();
            return null;
        });

        // ---- storage0 对象（官方：支持存储 JSON 对象的存储封装） ----
        // put* 用 stringifyArg 把值（含 JSObject）序列化为 JSON 存；get* 存的是 JSON 对象/数组则解析回 JS 对象返回
        ScriptableObject storage0 = (ScriptableObject) rhinoCx.newObject(scope);
        putFunction(storage0, "putVar", args -> {
            if (args != null && args.length > 1) vars.put(argStr(args[0]), stringifyArg(args[1]));
            return null;
        });
        putFunction(storage0, "getVar", args -> storage0Get(vars, args));
        putFunction(storage0, "putMyVar", args -> {
            if (args != null && args.length > 1) myVars.put(argStr(args[0]), stringifyArg(args[1]));
            return null;
        });
        putFunction(storage0, "getMyVar", args -> storage0Get(myVars, args));
        putFunction(storage0, "setItem", args -> {
            if (args != null && args.length > 1) {
                kv.put(argStr(args[0]), stringifyArg(args[1]));
                saveKv();
            }
            return null;
        });
        putFunction(storage0, "getItem", args -> storage0Get(kv, args));
        putFunction(storage0, "setPublicItem", args -> {
            if (args != null && args.length > 1) {
                publicKv.put(argStr(args[0]), stringifyArg(args[1]));
                savePublicKv();
            }
            return null;
        });
        putFunction(storage0, "getPublicItem", args -> storage0Get(publicKv, args));
        ScriptableObject.putProperty(scope, "storage0", storage0);

        // ---- 当前结果列表的动态修改（官方经 EventBus 改 UI 列表；本宿主直接改 results） ----
        putFunction("updateItem", args -> {
            // updateItem(id, obj) 或 updateItem(obj)（obj.extra.id / obj.url 作 id）
            try {
                String id = null;
                Object obj = null;
                if (args != null && args.length >= 2) {
                    id = argStr(args[0]);
                    obj = args[1];
                } else if (args != null && args.length == 1) {
                    obj = args[0];
                }
                Map<String, Object> m = toStrMap(obj);
                if (id == null && m != null) {
                    id = str(m, "url");
                    if (TextUtils.isEmpty(id)) {
                        Object extra = m.get("extra");
                        if (extra instanceof Map) id = str((Map<String, Object>) extra, "id");
                    }
                }
                if (!TextUtils.isEmpty(id) && m != null) {
                    int idx = findResultIndex(id);
                    if (idx >= 0) {
                        mergeItem(results.get(idx), m);
                        Logger.t(TAG).d("updateItem: %s", id);
                    }
                    if (collectRaw) {
                        for (Map<String, String> raw : rawResults) {
                            if (id.equals(raw.get("url"))) {
                                for (Map.Entry<String, Object> e : m.entrySet()) {
                                    if (!"extra".equals(e.getKey())) raw.put(e.getKey(), String.valueOf(e.getValue()));
                                }
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("updateItem failed: %s", e.getMessage());
            }
            return null;
        });
        putFunction("addItemAfter", args -> {
            addItemAt(args, true);
            return null;
        });
        putFunction("addItemBefore", args -> {
            addItemAt(args, false);
            return null;
        });
        putFunction("deleteItem", args -> {
            if (args != null && args.length > 0) {
                String id = argStr(args[0]);
                int idx = findResultIndex(id);
                if (idx >= 0) results.remove(idx);
                if (collectRaw) rawResults.removeIf(r -> id.equals(r.get("url")));
                Logger.t(TAG).d("deleteItem: %s", id);
            }
            return null;
        });
        putFunction("deleteItemByCls", args -> {
            if (args != null && args.length > 0) {
                String cls = argStr(args[0]);
                results.removeIf(it -> cls.equals(it.getColType()));
                if (collectRaw) rawResults.removeIf(r -> cls.equals(r.get("col_type")));
                Logger.t(TAG).d("deleteItemByCls: %s", cls);
            }
            return null;
        });
        // 官方 clearItem(key) 清的是规则持久化存储里的 key；本宿主 kv.json 即规则存储，对其删 key 等价。
        putFunction("clearItem", args -> {
            if (args != null && args.length > 0 && args[0] != null) {
                kv.remove(argStr(args[0]));
                saveKv();
                Logger.t(TAG).d("clearItem: %s", args[0]);
            }
            return null;
        });

        // ---- 私有加密 JS：AES/ECB/PKCS5Padding，key=hk6666666109 补 0 到 32 字节，base64 输入 ----
        putFunction("evalPrivateJS", args -> {
            if (args == null || args.length == 0) return null;
            try {
                String plain = aesDecryptECB(argStr(args[0]).trim(), AES_PRIVATE_KEY);
                if (TextUtils.isEmpty(plain)) return null;
                return evalJs(plain);
            } catch (Throwable e) {
                Logger.t(TAG).d("evalPrivateJS failed: %s", e.getMessage());
                return null;
            }
        });

        // ---- 带缓存的网络（fc=fetchCache，rc=requireCache，key=url，hours 小时过期） ----
        putFunction("fc", args -> {
            if (args == null || args.length == 0) return "";
            String url = argStr(args[0]);
            double hours = args.length > 1 ? toDouble(args[1]) : 0;
            return memCached("fc:" + url, hours, () -> fetchSync(url, null));
        });
        // 官方全名：fetchCache 即 fc
        putFunction("fetchCache", args -> {
            if (args == null || args.length == 0) return "";
            String url = argStr(args[0]);
            double hours = args.length > 1 ? toDouble(args[1]) : 0;
            return memCached("fc:" + url, hours, () -> fetchSync(url, null));
        });
        putFunction("rc", args -> {
            if (args == null || args.length == 0) return null;
            String url = argStr(args[0]);
            double hours = args.length > 1 ? toDouble(args[1]) : 0;
            String code = memCached("rc:" + url, hours, () -> {
                String c = loadLibLocal(url);
                return c == null ? fetchSync(url, null) : c;
            });
            try {
                if (!TextUtils.isEmpty(code)) evalJs(stripJsPrefix(code));
            } catch (Throwable e) {
                Logger.t(TAG).d("rc evaluate failed: %s", e.getMessage());
            }
            return null;
        });
        // 官方全名：requireCache 即 rc
        putFunction("requireCache", args -> {
            if (args == null || args.length == 0) return null;
            String url = argStr(args[0]);
            double hours = args.length > 1 ? toDouble(args[1]) : 0;
            String code = memCached("rc:" + url, hours, () -> {
                String c = loadLibLocal(url);
                return c == null ? fetchSync(url, null) : c;
            });
            try {
                if (!TextUtils.isEmpty(code)) evalJs(stripJsPrefix(code));
            } catch (Throwable e) {
                Logger.t(TAG).d("requireCache evaluate failed: %s", e.getMessage());
            }
            return null;
        });
        // ---- 批量（bf=batchFetch 并发取回 body 数组；be=batchExecute 预加载，宿主 no-op；bcm 返回原地址） ----
        putFunction("bf", args -> {
            if (args == null || args.length == 0) return "[]";
            try {
                String json = args[0] instanceof NativeArray ? jsStringify(args[0]) : argStr(args[0]);
                List<Object> list = GSON.fromJson(json.trim().startsWith("[") ? json : "[]",
                        new com.google.gson.reflect.TypeToken<List<Object>>() {}.getType());
                if (list == null || list.isEmpty()) return "[]";
                int n = list.size();
                int threads = 4;
                if (args.length > 1) {
                    try { threads = Math.max(1, (int) toDouble(args[1])); } catch (Throwable ignored) {}
                }
                threads = Math.min(n, threads);
                java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(threads);
                try {
                    List<Future<String>> futures = new ArrayList<>(n);
                    for (Object o : list) {
                        final String u;
                        final String opt;
                        if (o instanceof Map) {
                            Map<String, Object> m = (Map<String, Object>) o;
                            u = String.valueOf(m.get("url"));
                            Object op = m.get("options");
                            opt = op == null ? null : GSON.toJson(op);
                        } else {
                            u = String.valueOf(o);
                            opt = null;
                        }
                        futures.add(pool.submit(() -> fetchSync(u, opt)));
                    }
                    List<String> out = new ArrayList<>(n);
                    for (Future<String> f : futures) {
                        try { out.add(f.get(30, TimeUnit.SECONDS)); }
                        catch (Throwable ignored) { out.add(""); }
                    }
                    return GSON.toJson(out);
                } finally {
                    pool.shutdownNow();
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("bf failed: %s", e.getMessage());
                return "[]";
            }
        });
        // 官方全名：batchFetch 即 bf（并发取回 body 数组）
        putFunction("batchFetch", args -> {
            if (args == null || args.length == 0) return "[]";
            try {
                String json = args[0] instanceof NativeArray ? jsStringify(args[0]) : argStr(args[0]);
                List<Object> list = GSON.fromJson(json.trim().startsWith("[") ? json : "[]",
                        new com.google.gson.reflect.TypeToken<List<Object>>() {}.getType());
                if (list == null || list.isEmpty()) return "[]";
                int n = list.size();
                int threads = 4;
                if (args.length > 1) {
                    try { threads = Math.max(1, (int) toDouble(args[1])); } catch (Throwable ignored) {}
                }
                threads = Math.min(n, threads);
                java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(threads);
                try {
                    List<Future<String>> futures = new ArrayList<>(n);
                    for (Object o : list) {
                        final String u;
                        final String opt;
                        if (o instanceof Map) {
                            Map<String, Object> m = (Map<String, Object>) o;
                            u = String.valueOf(m.get("url"));
                            Object op = m.get("options");
                            opt = op == null ? null : GSON.toJson(op);
                        } else {
                            u = String.valueOf(o);
                            opt = null;
                        }
                        futures.add(pool.submit(() -> fetchSync(u, opt)));
                    }
                    List<String> out = new ArrayList<>(n);
                    for (Future<String> f : futures) {
                        try { out.add(f.get(30, TimeUnit.SECONDS)); }
                        catch (Throwable ignored) { out.add(""); }
                    }
                    return GSON.toJson(out);
                } finally {
                    pool.shutdownNow();
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("batchFetch failed: %s", e.getMessage());
                return "[]";
            }
        });
        // ------------------------------------------------------------------
        // 8.83: batchExecute(Object tasks, Object listener0, Object success, Object ruleKey)V
        // [JSEngine.smali 11276-11680]  alias "be". D2: 真实实现（替代 no-op）。
        // 语义：tasks 为 [{id, func, param}]，并发在独立 Rhino Context 里执行各
        // func(param)；每完成一个，若 listener0.func 存在且完成数 <= success，
        // 则回调 listener0.func(listener0.param, id, false, result)；返回 "break"
        // 则停止后续回调。等待 (n/16*30+30)s 后取消并关闭线程池。
        // 跨小程序内容搜索（"我的主页"规则）依赖此实现。
        // ------------------------------------------------------------------
        putFunction("be", args -> {
            batchExecuteImpl(args);
            return null;
        });
        // 官方全名：batchExecute 即 be
        putFunction("batchExecute", args -> {
            batchExecuteImpl(args);
            return null;
        });
        putFunction("bcm", args -> {
            String url = args != null && args.length > 0 ? argStr(args[0]) : "";
            Logger.t(TAG).d("bcm: passthrough %s", url);
            return url;
        });
        // 官方全名：batchCacheM3u8 即 bcm（返回原地址，宿主不做真实缓存）
        putFunction("batchCacheM3u8", args -> {
            String url = args != null && args.length > 0 ? argStr(args[0]) : "";
            Logger.t(TAG).d("batchCacheM3u8: passthrough %s", url);
            return url;
        });
        // ---- PC 模式请求（桌面 UA）与 postRequest（即 post） ----
        putFunction("fetchPC", args -> {
            if (args == null || args.length == 0) return "";
            String url = argStr(args[0]);
            String options = args.length > 1 && args[1] != null ? stringifyArg(args[1]) : "{}";
            return fetchSync(url, mergeDesktopUa(options));
        });
        // 官方 postPC：桌面 UA 的 POST
        putFunction("postPC", args -> {
            if (args == null || args.length == 0) return "";
            String url = argStr(args[0]);
            String options = args.length > 1 && args[1] != null ? stringifyArg(args[1]) : "{}";
            options = mergeMethod(options, "post");
            return fetchSync(url, mergeDesktopUa(options));
        });
        // 官方 fetchCookie(url, options)：带 withHeaders 抓取，返回 set-cookie
        putFunction("fetchCookie", args -> {
            if (args == null || args.length == 0) return "";
            try {
                String url = argStr(args[0]);
                String options = args.length > 1 && args[1] != null ? stringifyArg(args[1]) : "{}";
                // 强制 withHeaders
                if (!options.contains("withHeaders")) {
                    options = options.trim();
                    if (options.endsWith("}")) options = options.substring(0, options.length() - 1) + ",\"withHeaders\":true}";
                    else options = "{\"withHeaders\":true}";
                }
                String result = fetchSync(url, options);
                if (TextUtils.isEmpty(result)) return "";
                Map<String, Object> map = GSON.fromJson(result, new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
                if (map == null) return "";
                Object headers = map.get("headers");
                if (!(headers instanceof Map)) return "";
                Map<String, Object> hm = (Map<String, Object>) headers;
                List<String> cookies = new ArrayList<>();
                for (Map.Entry<String, Object> e : hm.entrySet()) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase("set-cookie") && e.getValue() != null) {
                        String v = String.valueOf(e.getValue());
                        // 取 cookie 名值对（分号前）
                        int semi = v.indexOf(';');
                        cookies.add(semi > 0 ? v.substring(0, semi).trim() : v.trim());
                    }
                }
                return TextUtils.join("; ", cookies);
            } catch (Throwable e) {
                Logger.t(TAG).d("fetchCookie failed: %s", e.getMessage());
                return "";
            }
        });
        putFunction("postRequest", args -> {
            if (args == null || args.length == 0) return "";
            String url = argStr(args[0]);
            String options = args.length > 1 && args[1] != null ? stringifyArg(args[1]) : "{}";
            return fetchSync(url, mergeMethod(options, "post"));
        });
        // fcbw/ewr：WebView 取码与 Web 规则执行
        // 8.83 原文：
        //   fetchCodeByWebView(url, options) (JSEngine.smali:15513，别名 fcbw)：
        //     用 WebkitFetcher（WebView）在后台加载 url，取 JS 渲染后的页面源码；
        //     options.timeout（1000-30000ms，默认 30000ms）；同步等待 CountDownLatch；返回源码字符串。
        //   executeWebRule(url, rule, options) (JSEngine.smali:15132，别名 ewr)：
        //     用 WebkitExecutor 在 WebView 里执行另一条 web 规则；同上超时机制；返回执行结果字符串。
        // 依赖：8.83 的 WebkitFetcher/WebkitExecutor/WebkitRunner（WebView 基础设施，约2000+行）。
        // 星辰暂无 WebView 后台抓取基础设施。完整移植需先搬运 Webkit* 类（Phase 2）。
        stub("fcbw", "");
        stub("ewr", "");
        // 官方全名 stub
        stub("fetchCodeByWebView", "");
        stub("executeWebRule", "");
        stub("cacheCode0", null);
        // setPageReverse：官方设置页面倒序标记；宿主存 kv 供规则自查
        putFunction("setPageReverse", args -> {
            try {
                boolean rev = args != null && args.length > 0 && Boolean.parseBoolean(argStr(args[0]));
                kv.put("__pageReverse", rev ? "1" : "0");
                saveKv();
            } catch (Throwable ignored) {
            }
            return null;
        });

        // ---- 文件（作用域限定 App 文件目录；hiker://files/ 映射到 filesDir） ----
        putFunction("writeFile", args -> {
            if (args != null && args.length > 1) writeFileContent(resolveHikerPath(argStr(args[0])), argStr(args[1]));
            return null;
        });
        putFunction("readFile", args -> {
            if (args == null || args.length == 0) return "";
            String charset = args.length > 1 ? argStr(args[1]) : null;
            return readFileContent(resolveHikerPath(argStr(args[0])), charset);
        });
        putFunction("fileExist", args -> {
            if (args == null || args.length == 0) return "false";
            // 官方返回 STRING "true"/"false"
            return new File(resolveHikerPath(argStr(args[0]))).exists() ? "true" : "false";
        });
        // 官方别名：exist 即 fileExist
        putFunction("exist", args -> {
            if (args == null || args.length == 0) return "false";
            return new File(resolveHikerPath(argStr(args[0]))).exists() ? "true" : "false";
        });
        putFunction("getPath", args -> {
            if (args == null || args.length == 0) return "";
            return resolveHikerPath(argStr(args[0]));
        });
        putFunction("saveFile", args -> {
            // saveFile(name, content, mode)：存到规则数据目录
            if (args != null && args.length > 1) {
                File f = new File(HkRuleManager.get().getDataDir(rule.getTitle()), argStr(args[0]));
                writeFileContent(f.getAbsolutePath(), argStr(args[1]));
            }
            return null;
        });
        putFunction("deleteFile", args -> {
            try {
                if (args != null && args.length > 0) new File(resolveHikerPath(argStr(args[0]))).delete();
            } catch (Throwable ignored) {}
            return null;
        });
        putFunction("downloadFile", args -> {
            if (args == null || args.length < 2) return null;
            try {
                String url = argStr(args[0]);
                String dest = resolveHikerPath(argStr(args[1]));
                File f = new File(dest);
                if (f.isDirectory() || dest.endsWith("/")) {
                    String name = url.replaceAll("[?#].*$", "").replaceAll("^.*/", "");
                    if (TextUtils.isEmpty(name)) name = "download.bin";
                    f = new File(f, name);
                }
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                try (okhttp3.Response res = HkHttp.to(url,
                        com.fongmi.quickjs.bean.Req.objectFrom("{}")).execute()) {
                    if (res.isSuccessful() && res.body() != null) {
                        java.nio.file.Files.write(f.toPath(), res.body().bytes());
                        Logger.t(TAG).d("downloadFile ok: %s", f.getAbsolutePath());
                    }
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("downloadFile failed: %s", e.getMessage());
            }
            return null;
        });

        // ---- 剪贴板 / 分享 ----
        putFunction("copy", args -> {
            try {
                if (args != null && args.length > 0) {
                    ClipboardManager cm = (ClipboardManager) App.get().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("hk", argStr(args[0])));
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("copy failed: %s", e.getMessage());
            }
            return null;
        });
        putFunction("parsePaste", args -> {
            // 解析分享文本：云口令则拉取规则 JSON 返回，否则原文返回
            if (args == null || args.length == 0) return "";
            String t = argStr(args[0]).trim();
            try {
                if (HkRuleManager.isCloudCode(t)) {
                    HkRule r = HkRuleManager.get().importByCloudCode(t);
                    Logger.t(TAG).d("parsePaste: cloud code imported %s", r.getTitle());
                    return GSON.toJson(r);
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("parsePaste cloud failed: %s", e.getMessage());
            }
            return t;
        });
        putFunction("getPastes", args -> "[]");
        putFunction("sharePaste", args -> {
            Logger.t(TAG).d("sharePaste: no-op in host");
            return "";
        });

        // ---- m3u8 ----
        putFunction("fixM3u8", args -> {
            if (args == null || args.length < 2) return args != null && args.length > 0 ? argStr(args[0]) : "";
            return fixM3u8Content(argStr(args[0]), argStr(args[1]));
        });
        putFunction("cacheM3u8", args -> {
            String url = args != null && args.length > 0 ? argStr(args[0]) : "";
            Logger.t(TAG).d("cacheM3u8: passthrough %s", url);
            return url;
        });
        // proxyClearM3u8：移植自 8.83 JSEngine.proxyClearM3u8 (JSEngine.smali:21401)。
        // 语义：proxyClearM3u8(u, h, r) 去 m3u8 广告片段。
        //   u: m3u8 URL；h: headers Map（可选，取 "headers"/"header" 子键）；r: 广告规则 JSONArray（可选）。
        //   返回：清理后的内容存为 hiker://files/_cache/<md5>.m3u8，返回 "file://<path>##<原URL>"；
        //   若清理结果为空则返回原 URL。
        // 依赖：8.83 的 M3u8ClearUtil.getProxyContent（2193 行，smali_classes2）。
        //   完整移植见 HkM3u8ClearUtil.java（待搬运）。
        putFunction("proxyClearM3u8", args -> {
            try {
                if (args == null || args.length == 0 || args[0] == null) return "";
                String url = argStr(args[0]);
                if (url == null || url.isEmpty() || "undefined".equals(url)) return "";
                // h 参数：headers Map（8.83 从 "headers" 或 "header" 子键取）
                java.util.Map<String, String> headers = new java.util.HashMap<>();
                if (args.length > 1 && args[1] != null) {
                    Object h = args[1];
                    // Rhino NativeObject -> Map 转换（复用现有 argMap 逻辑）
                    java.util.Map<?, ?> hm = toStringMap(h);
                    if (hm != null) {
                        Object sub = hm.get("headers");
                        if (sub == null) sub = hm.get("header");
                        java.util.Map<?, ?> sm = toStringMap(sub);
                        if (sm != null) {
                            for (java.util.Map.Entry<?, ?> e : sm.entrySet()) {
                                if (e.getKey() != null && e.getValue() != null) {
                                    headers.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                                }
                            }
                        }
                    }
                }
                // r 参数：广告规则 JSONArray（8.83 传 List<M3u8ClearAdRule>）
                java.util.List<HkM3u8ClearAdRule> adRules = null;
                if (args.length > 2 && args[2] != null) {
                    try {
                        String rJson = argStr(args[2]);
                        if (rJson != null && !rJson.isEmpty() && !"undefined".equals(rJson)) {
                            adRules = new com.google.gson.Gson().fromJson(rJson,
                                    new com.google.gson.reflect.TypeToken<java.util.List<HkM3u8ClearAdRule>>() {
                                    }.getType());
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (adRules == null) adRules = HkM3u8ClearUtil.getRules();
                String cleaned = HkM3u8ClearUtil.getProxyContent(url, headers, adRules);
                if (cleaned == null || cleaned.isEmpty()) {
                    return url; // 8.83：清理结果为空返回原 URL
                }
                // 存缓存文件，返回 file://## 格式（8.83 JSEngine.smali:21580-21620）
                String name = md5(url) + ".m3u8";
                java.io.File cacheDir = new java.io.File(App.get().getFilesDir(), "hk_m3u8_cache");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                java.io.File out = new java.io.File(cacheDir, name);
                try (java.io.FileWriter fw = new java.io.FileWriter(out)) {
                    fw.write(cleaned);
                }
                return "file://" + out.getAbsolutePath() + "##" + url;
            } catch (Throwable e) {
                Logger.t(TAG).d("proxyClearM3u8 failed: %s", e.getMessage());
                return args != null && args.length > 0 && args[0] != null ? argStr(args[0]) : "";
            }
        });

        // ---- 页面信息 ----
        putFunction("setPageTitle", args -> {
            if (args != null && args.length > 0) pageTitle = argStr(args[0]);
            return null;
        });
        putFunction("getPageTitle", args -> pageTitle);
        putFunction("getHome", args -> {
            if (args == null || args.length == 0) return "";
            return homeOf(argStr(args[0]));
        });
        putFunction("buildUrl", args -> {
            if (args == null || args.length == 0) return "";
            String url = argStr(args[0]);
            if (args.length < 2 || args[1] == null) return url;
            try {
                String json = stringifyArg(args[1]);
                Map<String, Object> map = GSON.fromJson(json, new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
                if (map == null || map.isEmpty()) return url;
                StringBuilder sb = new StringBuilder(url);
                boolean hasQ = url.contains("?");
                for (Map.Entry<String, Object> e : map.entrySet()) {
                    if (e.getValue() == null) continue;
                    sb.append(hasQ ? '&' : '?');
                    hasQ = true;
                    sb.append(java.net.URLEncoder.encode(e.getKey(), "UTF-8"));
                    sb.append('=');
                    sb.append(java.net.URLEncoder.encode(String.valueOf(e.getValue()), "UTF-8"));
                }
                return sb.toString();
            } catch (Throwable ex) {
                return url;
            }
        });
        putFunction("joinUrl", args -> {
            if (args == null || args.length < 2) return args != null && args.length > 0 ? argStr(args[0]) : "";
            try {
                return new java.net.URI(argStr(args[0])).resolve(argStr(args[1])).toString();
            } catch (Throwable e) {
                return argStr(args[1]);
            }
        });
        putFunction("getColTypes", args -> GSON.toJson(COL_TYPES));

        // ---- 编解码补充 ----
        putFunction("md5", args -> {
            if (args == null || args.length == 0) return "";
            String v = Util.md5(argStr(args[0]));
            return v == null ? "" : v;
        });
        // 官方 _base64 全局：8.83 JSEngine 注入的 MyBase64 实例（DEFAULT/NO_WRAP 常量 + 编解码方法），
        // 规则如 CryptoUtil.Data.parseBase64(s, _base64.NO_WRAP) 依赖其存在
        putFunction("__b64encodeToString", args -> {
            if (args == null || args.length == 0) return "";
            try {
                byte[] data = args[0] instanceof byte[] ? (byte[]) args[0] : argStr(args[0]).getBytes("UTF-8");
                int flags = args.length > 1 ? (int) Double.parseDouble(argStr(args[1])) : android.util.Base64.DEFAULT;
                return android.util.Base64.encodeToString(data, flags);
            } catch (Throwable e) {
                return "";
            }
        });
        putFunction("__b64decode", args -> {
            if (args == null || args.length == 0) return toJsArray(new byte[0]);
            try {
                int flags = args.length > 1 ? (int) Double.parseDouble(argStr(args[1])) : android.util.Base64.DEFAULT;
                return toJsArray(android.util.Base64.decode(argStr(args[0]), flags));
            } catch (Throwable e) {
                return toJsArray(new byte[0]);
            }
        });
        try {
            evalJs(
                "var _base64 = {\n" +
                "  DEFAULT: 0,\n" +
                "  NO_PADDING: 1,\n" +
                "  NO_WRAP: 2,\n" +
                "  CRLF: 4,\n" +
                "  URL_SAFE: 8,\n" +
                "  encodeToString: function(d, f) { return __b64encodeToString(d, f); },\n" +
                "  decode: function(s, f) { return __b64decode(s, f); },\n" +
                "  encode: function(d, f) { return __b64decode(__b64encodeToString(d, f)); },\n" +
                "  decodeToString: function(s, f) { var b = __b64decode(s, f); var r = ''; for (var i = 0; i < b.length; i++) r += String.fromCharCode(b[i] & 255); return r; }\n" +
                "};\n"
            );
        } catch (Throwable e) {
            Logger.t(TAG).d("_base64 inject failed: " + e.getMessage());
        }
        putFunction("hexToBase64", args -> {
            if (args == null || args.length == 0) return "";
            return hexToB64(argStr(args[0]));
        });
        putFunction("hexToBytes", args -> {
            if (args == null || args.length == 0) return toJsArray(new byte[0]);
            try {
                String h = argStr(args[0]).trim();
                byte[] b = new byte[h.length() / 2];
                for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
                return toJsArray(b);
            } catch (Throwable e) {
                return toJsArray(new byte[0]);
            }
        });
        putFunction("rsaEncrypt", args -> {
            if (args == null || args.length < 2) return "";
            return rsaCrypto(true, argStr(args[0]), argStr(args[1]));
        });
        putFunction("rsaDecrypt", args -> {
            if (args == null || args.length < 2) return "";
            return rsaCrypto(false, argStr(args[0]), argStr(args[1]));
        });
        putFunction("getCryptoJS", args -> {
            // 官方语义：返回内置 CryptoJS 库（aes.js）源码，规则 eval(getCryptoJS()) 后使用
            String code = loadAsset("aes.js");
            if (code == null) {
                Logger.t(TAG).d("getCryptoJS: aes.js missing in assets");
                return "";
            }
            return code;
        });
        putFunction("toCorrectJSONString", args -> {
            if (args == null || args.length == 0) return "";
            String t = argStr(args[0]).trim();
            try {
                GSON.fromJson(t, Object.class);
                return t;
            } catch (Throwable e) {
                return "";
            }
        });
        putFunction("justTestSign", args -> {
            Logger.t(TAG).d("justTestSign: no-op in host");
            return "";
        });

        // ---- 网络杂项 ----
        putFunction("getCookie", args -> {
            // 宿主 fetch 未维护 cookie 池，返回空（规则多用 getCookie 做登录态判断，空即未登录）
            Logger.t(TAG).d("getCookie: empty in host");
            return "";
        });
        putFunction("getIP", args -> getLocalIp());
        putFunction("ipping", args -> false);
        putFunction("isLogin", args -> false);

        // ---- 事件监听（官方 onClose 等；宿主仅记录，不触发） ----
        putFunction("addListener", args -> {
            try {
                if (args != null && args.length > 1) {
                    listeners.put(argStr(args[0]), argStr(args[1]));
                    Logger.t(TAG).d("addListener: %s", args[0]);
                }
            } catch (Throwable ignored) {}
            return null;
        });
        // 官方别名：listen 即 addListener
        putFunction("listen", args -> {
            try {
                if (args != null && args.length > 1) listeners.put(argStr(args[0]), argStr(args[1]));
            } catch (Throwable ignored) {}
            return null;
        });

        // ---- 选择弹窗（官方弹 UI；宿主无 UI，no-op） ----
        putFunction("showSelectOptions", args -> {
            Logger.t(TAG).d("showSelectOptions: no-op in host");
            return null;
        });

        // ---- 下划线内部别名（兼容老规则写法） ----
        putFunction("_pd", args -> {
            if (args == null || args.length < 2) return "";
            return parser.pdfh(argStr(args[0]), sel(args[1]), "");
        });
        putFunction("_pdfh", args -> {
            if (args == null || args.length < 2) return "";
            return parser.pdfh(argStr(args[0]), sel(args[1]), "");
        });
        putFunction("_pdfa", args -> {
            if (args == null || args.length < 2) return toJsArray(new ArrayList<>());
            return toJsArray(parser.pdfa(argStr(args[0]), sel(args[1])));
        });
        putFunction("_pdfl", args -> {
            if (args == null || args.length < 2) return toJsArray(new ArrayList<>());
            return toJsArray(parser.pdfa(argStr(args[0]), sel(args[1])));
        });
        putFunction("_findItem", args -> "");
        // findItemsByCls/deleteItemByCls：按条目 extra.cls 匹配当前已收集结果
        putFunction("_findItemsByCls", args -> {
            try {
                String cls = args != null && args.length > 0 ? argStr(args[0]) : "";
                List<Map<String, Object>> out = new ArrayList<>();
                for (HkItem it : results) {
                    if (cls.equals(it.getExtra("cls"))) {
                        Map<String, Object> m = new HashMap<>();
                        m.put("title", it.getTitle());
                        m.put("url", it.getUrl());
                        m.put("pic", it.getPic());
                        m.put("desc", it.getDesc());
                        m.put("col_type", it.getColType());
                        out.add(m);
                    }
                }
                return GSON.toJson(out);
            } catch (Throwable e) {
                return "[]";
            }
        });

        // ---- xpath（宿主 Parser 基于 jsoup，不支持 xpath，走空） ----
        putFunction("xpath", args -> "");
        putFunction("xpa", args -> toJsArray(new ArrayList<>()));
        // 官方全名：xpathArray 即 xpa
        putFunction("xpathArray", args -> toJsArray(new ArrayList<>()));

        // ---- refreshPage：官方刷新当前页（bool 为 true 时回顶）。宿主实现为"刷新请求"
        // 标记：lazyRule 回调（如 tab 切换 setItem 后调 refreshPage(true)）经 evalLazy 求值，
        // HkRouter.evalTab 消费该标记后由上层 loadContent(true) 重刷，语义与官方一致。
        putFunction("refreshPage", args -> {
            refreshRequested = true;
            refreshToTop = args != null && args.length > 0 && Boolean.parseBoolean(argStr(args[0]));
            Logger.t(TAG).d("refreshPage(%s) requested", refreshToTop);
            return null;
        });

        // ---- goTo(url)：页面导航。规则（如 xhotvideo）在 @rule=js: 回调里调
        // goTo('hiker://page/detail?rule=X&u=Y') 跳 V4 详情页。宿主实现为"导航请求"
        // 标记：调用方在 JS 求值后用 consumeGoToRequest() 取走并处理。
        putFunction("goTo", args -> {
            goToUrl = args != null && args.length > 0 ? argStr(args[0]) : "";
            Logger.t(TAG).d("goTo(%s) requested", goToUrl);
            return null;
        });

        // ---- sleep(ms)：官方真实 API（规则里常被 try/catch 包裹做限速/等待）
        putFunction("sleep", args -> {
            long ms = 0;
            try {
                if (args != null && args.length > 0) ms = Long.parseLong(argStr(args[0]));
            } catch (Throwable ignored) {
            }
            if (ms > 0) {
                try {
                    Thread.sleep(Math.min(ms, 10000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return null;
        });

        // ---- 其余官方 API：宿主无对应能力，一律安全桩（防 ReferenceError，中断规则） ----
        // 导航/UI 类
        stub("back", null);
        stub("backToHome", null);
        // closeMe：移植自 8.83 JSEngine.closeMe (JSEngine.smali:12407)。
        // 关闭 fetch({inputStream:true}) 返回的流，规则用完必须调。
        putFunction("closeMe", args -> {
            try {
                if (args != null && args.length > 0 && args[0] instanceof java.io.InputStream) {
                    ((java.io.InputStream) args[0]).close();
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("closeMe failed: %s", e.getMessage());
            }
            return null;
        });
        // R6: 8.83 的 refresh() 等同 refreshPage()（JSEngine.smali:22071），之前被 stub 为空函数
        putFunction("refresh", args -> {
            refreshRequested = true;
            refreshToTop = args != null && args.length > 0 && Boolean.parseBoolean(argStr(args[0]));
            Logger.t(TAG).d("refresh(%s) requested (alias of refreshPage)", refreshToTop);
            return null;
        });
        stub("setPageParams", null);
        // 官方 setPagePicUrl(title)：发 SetPagePicEvent 更新页面配图；宿主存字段供上层取用
        putFunction("setPagePicUrl", args -> {
            try {
                pagePicUrl = args != null && args.length > 0 && args[0] != null ? argStr(args[0]) : "";
                Logger.t(TAG).d("setPagePicUrl: %s", pagePicUrl);
            } catch (Throwable e) {
                Logger.t(TAG).d("setPagePicUrl failed: %s", e.getMessage());
            }
            return null;
        });
        // 8.83 showLoading(text)/hideLoading()：post LoadingEvent 由 UI 层弹 loading 框；
        // 动态域名类规则 preRule 里调 showLoading("正在获取域名，请稍候")，之前是 stub 导致无任何提示。
        putFunction("showLoading", args -> {
            try {
                String text = args != null && args.length > 0 ? argStr(args[0]) : "";
                Logger.t(TAG).d("showLoading: %s", text);
                HkLoading.show(rule.getTitle(), text);
            } catch (Throwable e) {
                Logger.t(TAG).d("showLoading failed: %s", e.getMessage());
            }
            return null;
        });
        putFunction("hideLoading", args -> {
            try {
                HkLoading.hide(rule.getTitle());
            } catch (Throwable e) {
                Logger.t(TAG).d("hideLoading failed: %s", e.getMessage());
            }
            return null;
        });
        stub("refreshReadData", null);
        stub("refreshVideoSource", null);
        stub("refreshVideoUrl", null);
        stub("refreshX5Desc", null);
        stub("refreshX5WebView", null);
        stub("startQRScanPage", null);
        stub("createQRCode", null);
        stub("createQRCodeToFile", "");
        stub("openAppIntent", false);
        stub("initChaquopy", null);
        // 文件/下载类（官方行为：saveImage 下载图片到 path；requireDownload 文件不存在才下载；
        // deleteCache(c) c=-1 清规则全部缓存否则按 url 清，宿主清内存缓存）
        putFunction("requireDownload", args -> {
            if (args == null || args.length < 2) return null;
            try {
                String url = argStr(args[0]);
                String dest = resolveHikerPath(argStr(args[1]));
                File f = new File(dest);
                if (f.exists()) return null;
                String headers = args.length > 2 && args[2] != null ? stringifyArg(args[2]) : "{}";
                downloadToFile(url, dest, headers);
            } catch (Throwable e) {
                Logger.t(TAG).d("requireDownload failed: %s", e.getMessage());
            }
            return null;
        });
        putFunction("saveImage", args -> {
            // 官方 saveImage(url, path)：参数顺序注意是 (url, path)
            if (args == null || args.length < 2) return null;
            try {
                String url = argStr(args[0]);
                String path = argStr(args[1]);
                if (TextUtils.isEmpty(url) || "undefined".equalsIgnoreCase(url)) return null;
                if (TextUtils.isEmpty(path) || "undefined".equalsIgnoreCase(path)) return null;
                downloadToFile(url, resolveHikerPath(path), "{}");
            } catch (Throwable e) {
                Logger.t(TAG).d("saveImage failed: %s", e.getMessage());
            }
            return null;
        });
        stub("copyFiles", null);
        putFunction("deleteCache", args -> {
            try {
                String c = args != null && args.length > 0 && args[0] != null ? argStr(args[0]) : "-1";
                if ("-1".equals(c) || TextUtils.isEmpty(c)) {
                    memCache.clear();
                    Logger.t(TAG).d("deleteCache: cleared all memCache");
                } else {
                    final String key = c;
                    memCache.keySet().removeIf(k -> k.contains(key));
                    Logger.t(TAG).d("deleteCache: cleared memCache for %s", key);
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("deleteCache failed: %s", e.getMessage());
            }
            return null;
        });
        stub("shareDirectory", null);
        stub("writeHexFile", null);
        // 代理/服务类
        // startProxyServer：8.83 JSEngine.startProxyServer (JSEngine.smali:25880)。
        //   语义：startProxyServer(js) 设置代理 JS，启动 AndServer 本地服务（30秒超时等待），
        //   返回 serverUrl + "/proxy"。
        //   依赖：8.83 的 RemoteServerManager + AndServer 库（com.yanzhenjie.andserver）。
        //   星辰无 AndServer 依赖。完整移植需先引入 AndServer 并搬运 RemoteServerManager。
        stub("startProxyServer", "");
        stub("registerDNS", null);
        stub("addWebProxyRule", null);
        stub("removeWebProxyRule", null);
        stub("refreshWebProxyRule", null);
        stub("png2Ts", null);
        stub("buildWebDav", null);
        // 阅读类（第二期再做）
        stub("getEpubChapters", "[]");
        stub("getEpubContent0", "");
        stub("getEpubMetadata", "{}");
        // 字符串结果回调：官方 setStrResult(o, callbackKey, ruleKey) 完成 JS 回调；
        // 宿主同步模型无 callbackMap，存 strResult 字段供上层取用（setLastChapterResult 官方即委托 setStrResult）
        putFunction("setStrResult", args -> {
            try {
                strResult = args != null && args.length > 0 && args[0] != null ? argStr(args[0]) : "";
                Logger.t(TAG).d("setStrResult: %d chars", strResult.length());
            } catch (Throwable e) {
                Logger.t(TAG).d("setStrResult failed: %s", e.getMessage());
            }
            return null;
        });
        putFunction("setLastChapterResult", args -> {
            try {
                strResult = args != null && args.length > 0 && args[0] != null ? argStr(args[0]) : "";
                Logger.t(TAG).d("setLastChapterResult: %d chars", strResult.length());
            } catch (Throwable e) {
                Logger.t(TAG).d("setLastChapterResult failed: %s", e.getMessage());
            }
            return null;
        });
        putFunction("setLastChapterRule", args -> {
            try {
                lastChapterRule = args != null && args.length > 0 && args[0] != null ? argStr(args[0]) : "";
                Logger.t(TAG).d("setLastChapterRule: %d chars", lastChapterRule.length());
            } catch (Throwable e) {
                Logger.t(TAG).d("setLastChapterRule failed: %s", e.getMessage());
            }
            return null;
        });
        // 搜索/隐私
        stub("getSearchMode", "");
        stub("setSearchMode", null);
        stub("searchContains", false);
        stub("checkPrivacyPassword", false);
        stub("getPrivacyPasswordLen", 0);
        // 规则管理（官方：LitePal 规则表；宿主：HkRuleManager 落盘目录）
        putFunction("getLastRules", args -> {
            try {
                int count = 12;
                if (args != null && args.length > 0 && args[0] != null) {
                    try {
                        count = (int) toDouble(args[0]);
                    } catch (Throwable ignored) {
                        return "[]";
                    }
                    if (count == -1) count = Integer.MAX_VALUE;
                }
                File dir = HkRuleManager.get().getDir();
                File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
                if (files == null || files.length == 0) return "[]";
                java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                List<Object> out = new ArrayList<>();
                for (File f : files) {
                    if (out.size() >= count) break;
                    try {
                        byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                        Object o = GSON.fromJson(new String(bytes, Charset.forName("UTF-8")), Object.class);
                        if (o != null) out.add(o);
                    } catch (Throwable ignored) {
                    }
                }
                return GSON.toJson(out);
            } catch (Throwable e) {
                Logger.t(TAG).d("getLastRules failed: %s", e.getMessage());
                return "[]";
            }
        });
        stub("publishRule", null);
        putFunction("getRuleCount", args -> {
            try {
                File dir = HkRuleManager.get().getDir();
                File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
                return String.valueOf(files == null ? 0 : files.length);
            } catch (Throwable e) {
                return "0";
            }
        });
        stub("isVideoOrMusic", false);
        stub("isShorthand", false);
        // 任务调度：移植自 8.83 JSEngine.registerTask/unRegisterTask
        // (JSEngine.smali:22645/26271)。8.83 没有 setTimeout/setInterval，规则定时全靠它。
        // 语义：registerTask(id, time, js) 延迟 time 毫秒后单次执行 JS 字符串；id 为空则生成 UUID；
        // time 默认 10000ms，最小 500ms；unRegisterTask(id) 取消。
        putFunction("registerTask", args -> {
            try {
                String id = args != null && args.length > 0 ? argStr(args[0]) : null;
                if (id == null || id.isEmpty() || "undefined".equals(id) || "null".equals(id)) {
                    id = java.util.UUID.randomUUID().toString();
                }
                long delay = 10000; // 8.83 defaultInt = 0x2710
                if (args != null && args.length > 1 && args[1] != null) {
                    Object t = args[1];
                    if (t instanceof Integer) delay = ((Integer) t).intValue();
                    else if (t instanceof Double) delay = ((Double) t).intValue();
                    else if (t instanceof Long) delay = (Long) t;
                    else if (t instanceof Number) delay = ((Number) t).intValue();
                }
                if (delay < 500) delay = 500; // 8.83 最小 0x1f4
                String js = args != null && args.length > 2 && args[2] != null ? argStr(args[2]) : "";
                final String taskId = id;
                final String taskJs = js;
                Runnable fire = () -> {
                    try {
                        submit(() -> {
                            try {
                                if (destroyed) return null;
                                synchronized (jsTasks) {
                                    if (!jsTasks.containsKey(taskId)) return null;
                                    jsTasks.remove(taskId); // 单次执行
                                }
                                if (taskJs != null && !taskJs.isEmpty()) {
                                    evalJs(taskJs);
                                }
                            } catch (Throwable th) {
                                Logger.t(TAG).d("registerTask fire failed: " + th.getMessage());
                            }
                            return null;
                        });
                    } catch (Throwable ignored) {
                    }
                };
                ScheduledFuture<?> f = timerScheduler.schedule(fire, delay, TimeUnit.MILLISECONDS);
                synchronized (jsTasks) {
                    // 同 id 覆盖：先取消旧的（8.83 SchedulePool.add 同 key 覆盖语义）
                    ScheduledFuture<?> old = jsTasks.put(taskId, f);
                    if (old != null) old.cancel(false);
                }
                Logger.t(TAG).d("registerTask(%s, %dms)", taskId, delay);
            } catch (Throwable e) {
                Logger.t(TAG).d("registerTask failed: %s", e.getMessage());
            }
            return null;
        });
        putFunction("unRegisterTask", args -> {
            try {
                String id = args != null && args.length > 0 && args[0] != null ? argStr(args[0]) : null;
                if (id != null) {
                    synchronized (jsTasks) {
                        ScheduledFuture<?> f = jsTasks.remove(id);
                        if (f != null) f.cancel(false);
                    }
                    Logger.t(TAG).d("unRegisterTask(%s)", id);
                }
            } catch (Throwable e) {
                Logger.t(TAG).d("unRegisterTask failed: %s", e.getMessage());
            }
            return null;
        });
        // Java 互操作（宿主不开放）
        stub("getCurrentActivity", null);
        stub("findJavaClass", null);
        stub("loadJavaClass", null);
        // 官方 getPrivateJS(c)：AES 加密（key=AES_DEFAULT_KEY="1234567890kkkk"）；
        // 宿主用同 key 的 AES/ECB/PKCS5Padding 实现（base64 输出），保证宿主内确定性
        putFunction("getPrivateJS", args -> {
            if (args == null || args.length == 0 || args[0] == null) return "";
            try {
                return aesEncryptECB(argStr(args[0]), "1234567890kkkk");
            } catch (Throwable e) {
                Logger.t(TAG).d("getPrivateJS failed: %s", e.getMessage());
                return "";
            }
        });
        // 官方语义：返回内置 Hikerurl.js（$ 工具库）源码，规则 eval(getJsPlugin()) 后使用；
        // init() 已自动注入为全局，规则不 eval 也能用
        putFunction("getJsPlugin", args -> {
            String code = loadAsset("Hikerurl.js");
            if (TextUtils.isEmpty(code)) {
                Logger.t(TAG).d("getJsPlugin: Hikerurl.js missing in assets");
                return "";
            }
            return code;
        });
        // 官方 getJsLazyPlugin()：返回内置 assets/plugin.js 源码
        putFunction("getJsLazyPlugin", args -> {
            String code = loadAsset("plugin.js");
            if (code == null) {
                Logger.t(TAG).d("getJsLazyPlugin: plugin.js missing in assets");
                return "";
            }
            return code;
        });
        stub("getMyType", "");
        stub("getMyCallbackKey", "");
        stub("getMyInput", "");
        stub("getMyJs", "");
        stub("syncExecute", null);
        // 首页子模块：8.83 JSEngine.getHomeSub/hasHomeSub (JSEngine.smali:17037/18002)
        // 依赖 8.83 的 HomeRulesSubService.getSubRecords()（首页订阅服务），星辰无此服务。
        // 8.83 语义：getHomeSub() 返回订阅记录 JSON 数组；hasHomeSub(url) 返回 "true"/"false" 字符串。
        // 宿主无订阅服务，保持安全默认值。完整移植需先搬运 HomeRulesSubService。
        stub("hasHomeSub", false);
        stub("getHomeSub", "[]");
        // 应用信息（真实现）
        putFunction("getAppVersion", args -> {
            try {
                android.content.pm.PackageInfo pi = App.get().getPackageManager()
                        .getPackageInfo(App.get().getPackageName(), 0);
                return pi.versionName == null ? "" : pi.versionName;
            } catch (Throwable ignored) {
                return "";
            }
        });
        putFunction("getCpuAbi", args -> {
            try {
                String[] abis = android.os.Build.SUPPORTED_ABIS;
                return abis != null && abis.length > 0 ? abis[0] : "";
            } catch (Throwable ignored) {
                return "";
            }
        });
        putFunction("getUaObject", args -> {
            Map<String, String> ua = new HashMap<>();
            ua.put("mobileUa", UA_MOBILE);
            ua.put("pcUa", UA_PC);
            return GSON.toJson(ua);
        });

        // ---- $ 选择器助手：$('').lazyRule(fn) / $('...').rule(fn) / $.toString(fn, ...args) / $.require(path) ----
        // lazyRule 把函数序列化为 @lazyRule=.js:... 字符串，拼到 URL 上由 HkRouter.play() 求值；
        // $.toString 把函数+绑定参数序列化为 js:... 字符串，点击时由 evalLazy 求值（input 为全局）。
        // $.require(path)：hiker://page/<path> 从规则 pages 取页面代码求值返回 $.exports，
        // 其他路径复用 require 的远程/本地库逻辑（__hkRequirePage 为 Java 实现）。
        try {
            evalJs(
                "function $(url, headers) {\n" +
                "  url = (typeof url === 'undefined' || url == null) ? '' : String(url);\n" +
                "  var _headersJson = '{}';\n" +
                "  try {\n" +
                "    if (headers != null) _headersJson = (typeof headers === 'string') ? headers : JSON.stringify(headers);\n" +
                "  } catch (_he) {}\n" +
                "  return {\n" +
                "    lazyRule: function(fn) {\n" +
                "      var _a = [];\n" +
                "      for (var _i = 1; _i < arguments.length; _i++) {\n" +
                "        try { _a.push(JSON.stringify(arguments[_i])); } catch (_e) { _a.push('null'); }\n" +
                "      }\n" +
                "      return url + '@lazyRule=.js:(' + fn.toString() + ')(' + _a.join(',') + ');';\n" +
                "    },\n" +
                "    rule: function(fn) {\n" +
                "      return url + '@rule=js:(' + fn.toString() + ')();';\n" +
                "    },\n" +
                "    image: function(fn) {\n" +
                "      if (typeof fn !== 'function') return url;\n" +
                "      var _a = [];\n" +
                "      for (var _i = 1; _i < arguments.length; _i++) {\n" +
                "        try { _a.push(JSON.stringify(arguments[_i])); } catch (_e) { _a.push('null'); }\n" +
                "      }\n" +
                "      var _mt = '';\\n" +
                "      try { _mt = (typeof MY_RULE !== 'undefined' && MY_RULE && MY_RULE.title) ? MY_RULE.title : ''; } catch (_mte) {}\\n" +
                "      return url + '@headers=' + _headersJson + '@js=MY_TITLE=' + JSON.stringify(_mt) + ';(' + fn.toString() + ')(' + _a.join(',') + ');';\\n" +
                "    },\n" +
                "    confirm: function(fn) {\n" +
                "      return url + '@confirmRule=js:(' + fn.toString() + ')();';\n" +
                "    },\n" +
                "    input: function(fn) {\n" +
                "      return '@inputRule=.js:(' + fn.toString() + ')();';\n" +
                "    },\n" +
                "    b64: function() {\n" +
                "      return $(url, headers);\n" +
                "    },\n" +
                "    x5Rule: function(fn) {\n" +
                "      return '@x5Rule=js:(' + fn.toString() + ')();';\n" +
                "    }\n" +
                "  };\n" +
                "}\n" +
                "$.toString = function(fn) {\n" +
                "  if (arguments.length === 0) return \"$\";\n" +
                "  var args = [];\n" +
                "  for (var i = 1; i < arguments.length; i++) {\n" +
                "    try { args.push((typeof $.stringify === 'function' ? $.stringify : JSON.stringify)(arguments[i])); } catch (e) { args.push('null'); }\n" +
                "  }\n" +
                "  if (typeof fn !== 'function') return '';\n" +
                "  var _fnStr;\n" +
                "  try { _fnStr = fn.__toString__(); } catch (_e) { try { _fnStr = fn.toString(); } catch (_e2) { return ''; } }\n" +
                "  return '(' + _fnStr + ')(' + args.join(',') + ')';\n" +
                "};\n" +
                                "var $require = (function() {\n" +
                "    var RequireUtils = com.example.hikerview.ui.rules.service.require.RequireUtils;\n" +
                "    function Module(o) {\n" +
                "        this.id = o.id;\n" +
                "        this.exports = {};\n" +
                "        this.importParam = o.importParam;\n" +
                "        this.modulePath = o.modulePath;\n" +
                "        this.headers = o.headers;\n" +
                "        this.time = o.time;\n" +
                "        this.code = o.code;\n" +
                "    }\n" +
                "    Module._cache = new Map();\n" +
                "    Module._extensions = {\n" +
                "        'json': function(module) {\n" +
                "            var script = getScript(module).trim();\n" +
                "            try {\n" +
                "                module.exports = JSON.parse(script);\n" +
                "            } catch (e) {\n" +
                "                Module._extensions.js(module);\n" +
                "            }\n" +
                "        },\n" +
                "        'js': function(module) {\n" +
                "            var script = getScript(module);\n" +
                "            var templateFn = new Function(\"module\", \"exports\", \"__filename\", script);\n" +
                "            var exports = module.exports;\n" +
                "            templateFn.call(exports, module, exports, module.id);\n" +
                "        }\n" +
                "    };\n" +
                "    Module.prototype.load = function() {\n" +
                "        var $temp = [$.exports, $.importParam];\n" +
                "        var referee = this.exports;\n" +
                "        $.exports = this.exports;\n" +
                "        $.importParam = this.importParam;\n" +
                "        var type = this.id.split(\"?\")[0];\n" +
                "        if (type.endsWith(\".json\")) {\n" +
                "            Module._extensions.json(this);\n" +
                "        } else {\n" +
                "            Module._extensions.js(this);\n" +
                "        }\n" +
                "        if ($.exports !== this.exports && referee === this.exports) {\n" +
                "            this.exports = $.exports;\n" +
                "        }\n" +
                "        $.exports = $temp[0];\n" +
                "        $.importParam = $temp[1];\n" +
                "    };\n" +
                "    function getScript(module) {\n" +
                "        var code = \"\";\n" +
                "        if (module.code !== void 0) return module.code;\n" +
                "        if (module.id.startsWith(\"hiker://page/\")) {\n" +
                "            var codeObject = request(module.id);\n" +
                "            if (!codeObject) throw new Error('Module \"' + module.modulePath + '\" cannot be found.');\n" +
                "            code = JSON.parse(codeObject).rule;\n" +
                "        } else {\n" +
                "            if (fileExist(module.id) || module.id.startsWith(\"hiker://assets/\")) {\n" +
                "                if (module.time) {\n" +
                "                    code = fetchCache(module.modulePath, module.time, module.headers);\n" +
                "                } else {\n" +
                "                    code = request(module.id);\n" +
                "                }\n" +
                "            } else if (module.modulePath.startsWith(\"http\")) {\n" +
                "                code = request(module.modulePath, module.headers);\n" +
                "                if (!isJsCode(code)) {\n" +
                "                    throw new Error('failed to get module \"' + module.modulePath + '\" from the network!');\n" +
                "                }\n" +
                "                writeFile(module.id, code);\n" +
                "            } else {\n" +
                "                throw new Error('Module \"' + module.modulePath + '\" cannot be found.');\n" +
                "            }\n" +
                "            if (module.modulePath.startsWith(\"http\")) {\n" +
                "                try {\n" +
                "                    var title = \"\";\n" +
                "                    if (typeof MY_RULE !== \"undefined\" && MY_RULE != null) {\n" +
                "                        title = MY_RULE.title;\n" +
                "                    } else if (typeof MY_TITLE !== \"undefined\") {\n" +
                "                        title = MY_TITLE;\n" +
                "                    }\n" +
                "                    generateRequireMap(title, module.modulePath, \"\", getPath(module.id).slice(7));\n" +
                "                } catch (e) {}\n" +
                "            }\n" +
                "        }\n" +
                "        if (code.startsWith(\"js:\")) {\n" +
                "            code = code.slice(3);\n" +
                "        }\n" +
                "        return code;\n" +
                "    }\n" +
                "    function isJsCode(code) {\n" +
                "        if (!code) return false;\n" +
                "        code = code.trim();\n" +
                "        var notJsCode1 = [\"<!DOCTYPE\", \"<html\", \"<?xml\"];\n" +
                "        for (var s of notJsCode1) {\n" +
                "            if (code.startsWith(s)) return false;\n" +
                "        }\n" +
                "        var notJsCode2 = [\"</html>\", \"</rss>\"];\n" +
                "        for (var s of notJsCode2) {\n" +
                "            if (code.endsWith(s)) return false;\n" +
                "        }\n" +
                "        var jsKey = [\"var \", \"let \", \"const \", \"this.\", \"function\", \"eval(\", \"call(\", \"eval (\", \"call (\", \" => \", \")=>\"];\n" +
                "        for (var s of jsKey) {\n" +
                "            if (code.includes(s)) return true;\n" +
                "        }\n" +
                "        return false;\n" +
                "    }\n" +
                "    function require(modulePath, importParam, headers, time) {\n" +
                "        if (typeof headers === \"number\") {\n" +
                "            time = headers;\n" +
                "            headers = undefined;\n" +
                "        }\n" +
                "        modulePath = modulePath || \"\";\n" +
                "        var absPathname = require.resolve(modulePath);\n" +
                "        if (Module._cache.has(absPathname)) {\n" +
                "            return Module._cache.get(absPathname).exports;\n" +
                "        }\n" +
                "        var module = new Module({id: absPathname, modulePath: modulePath, importParam: importParam, headers: headers, time: time});\n" +
                "        module.load();\n" +
                "        Module._cache.set(module.id, module);\n" +
                "        return module.exports;\n" +
                "    }\n" +
                "    require.resolve = function(modulePath) {\n" +
                "        if (modulePath.startsWith(\"../\") || modulePath.startsWith(\"./\")) {\n" +
                "            return joinUrl(\"file:///files/data/\" + MY_RULE.title + \"/\", modulePath).replace(\"file:///\", \"hiker://\");\n" +
                "        } else if (modulePath.startsWith(\"https://\") || modulePath.startsWith(\"http://\")) {\n" +
                "            return \"hiker://files/libs/\" + md5(modulePath) + \".js\";\n" +
                "        } else if (!modulePath.startsWith(\"hiker://\") && !modulePath.startsWith(\"file://\")) {\n" +
                "            return \"hiker://page/\" + modulePath;\n" +
                "        } else {\n" +
                "            return modulePath;\n" +
                "        }\n" +
                "    };\n" +
                "    require.cache = Module._cache;\n" +
                "    require.eval = function(code, eid, importParam) {\n" +
                "        eid = eid || md5(code);\n" +
                "        if (Module._cache.has(eid)) {\n" +
                "            return Module._cache.get(eid).exports;\n" +
                "        }\n" +
                "        var module = new Module({id: eid, importParam: importParam, code: code});\n" +
                "        module.load();\n" +
                "        Module._cache.set(module.id, module);\n" +
                "        return module.exports;\n" +
                "    };\n" +
                "    return require;\n" +
                "})();\n" +
                "$.require = $require;\n" +
                "$.exports = $.exports || {};\n" +
                "$.importParam = $.importParam || null;\n" +
                "\n" +
                "function Uint8Array(a) {\n" +
                "  var r = [];\n" +
                "  if (typeof a === 'number') { for (var i = 0; i < a; i++) r.push(0); }\n" +
                "  else if (a && typeof a.length === 'number') { for (var j = 0; j < a.length; j++) r.push(a[j] & 255); }\n" +
                "  return r;\n" +
                "}\n"
            );
        } catch (Throwable e) {
            Logger.t(TAG).d("$ helper failed: %s", e.getMessage());
        }
    }

    /**
     * 安全桩：官方有但宿主无对应能力的 API，返回类型合适的默认值并打日志，
     * 保证任何规则都不会因 ReferenceError 中断。
     */
    private void stub(String name, Object ret) {
        try {
            putFunction(name, args -> {
                Logger.t(TAG).d("stub %s called (no-op in host)", name);
                return ret;
            });
        } catch (Throwable ignored) {
        }
    }


    /**
     * hiker://page/&lt;path&gt; 内部协议：从规则 pages（JSON 数组）里按 path 找页面规则，
     * 返回 {@code {"rule": "..."}} JSON 字符串。官方 JSEngine.fetchByHiker 同款语义。
     */
    /**
     * 处理 hiker://page/ 请求，移植自 8.83 PageParser.parsePageRule：
     * (decompiled/smali/com/example/hikerview/service/parser/PageParser.smali:544)
     *
     * 8.83 返回完整的 ArticleListPageRule 对象（path/name/rule/col_type/params/parent），
     * 而非仅 {"rule":"..."}。params 来自 URL query（MY_PARAMS 的来源），
     * parent 为规则标题。找不到页面时 8.83 抛 ParseException，上层返回 ""。
     */
    private String handlePageRequest(String url) {
        try {
            String path = url.substring("hiker://page/".length());
            // D1：解析 query 中的 rule= 参数（官方 PageParser.parsePageRule：?rule=标题 按标题换规则查库）
            String ruleTitle = null;
            // 8.83 HttpParser.getParamsByUrl：解析 URL query 为 Map（MY_PARAMS 来源）
            Map<String, String> params = parseUrlParams(url);
            int q = path.indexOf('?');
            if (q >= 0) {
                String query = path.substring(q + 1);
                int hh = query.indexOf('#');
                if (hh >= 0) query = query.substring(0, hh);
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        String k = java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                        if ("rule".equals(k)) {
                            ruleTitle = java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                            break;
                        }
                    }
                }
                path = path.substring(0, q);
            }
            int h = path.indexOf('#');
            if (h >= 0) path = path.substring(0, h);
            // 8.83：按 ; 分隔取第一段（UrlDetector.clearTag 前）
            int sc = path.indexOf(';');
            if (sc >= 0) path = path.substring(0, sc);
            path = path.trim();
            // hikerPop.js：星辰原生重实现（规则自带的依赖 8.83 内部类，跑不起来）。
            // 返回合成页面，rule 里 $.exports.updateRecordsBottom 调原生 __hkShowUpdateLog 弹底部弹窗。
            if ("hikerPop.js".equals(path)) {
                Map<String, Object> out = new HashMap<>();
                out.put("path", path);
                out.put("rule", "js:$.exports.updateRecordsBottom = function(data){ __hkShowUpdateLog(JSON.stringify(data)); };");
                out.put("params", params);
                out.put("parent", rule != null ? rule.getTitle() : "");
                return GSON.toJson(out);
            }
            // 若指定了 rule= 标题，从目标规则的 pages 里找；找不到标题则回退当前规则
            HkRule targetRule = rule;
            String parentTitle = rule != null ? rule.getTitle() : "";
            if (ruleTitle != null && !ruleTitle.isEmpty()) {
                HkRule found = HkRuleManager.get().getInstalledRule(ruleTitle);
                if (found != null) {
                    targetRule = found;
                    parentTitle = found.getTitle();
                } else {
                    Logger.t(TAG).d("handlePageRequest: rule title not found: %s, fallback to current", ruleTitle);
                }
            }
            String pagesJson = targetRule != null ? targetRule.getPages() : "";
            if (!TextUtils.isEmpty(pagesJson)) {
                List<Map<String, Object>> pages = GSON.fromJson(pagesJson, MAP_LIST_TYPE);
                if (pages != null) {
                    for (Map<String, Object> p : pages) {
                        if (path.equals(String.valueOf(p.get("path")))) {
                            // 8.83：返回完整 page 对象，补 params 和 parent
                            Map<String, Object> out = new HashMap<>(p);
                            out.put("params", params);
                            out.put("parent", parentTitle);
                            return GSON.toJson(out);
                        }
                    }
                }
            }
            Logger.t(TAG).d("handlePageRequest: no page rule for path=%s", path);
        } catch (Throwable e) {
            Logger.t(TAG).d("handlePageRequest failed: %s", e.getMessage());
        }
        // 8.83 找不到时抛 ParseException，上层返回 ""（非 "{}"，避免 JSON.parse("{}").rule 为 undefined）
        return "";
    }

    /** 8.83 HttpParser.getParamsByUrl 等价：解析 URL query 为 Map */
    private static Map<String, String> parseUrlParams(String url) {
        Map<String, String> map = new HashMap<>();
        try {
            int q = url.indexOf('?');
            if (q < 0) return map;
            String query = url.substring(q + 1);
            int hh = query.indexOf('#');
            if (hh >= 0) query = query.substring(0, hh);
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String k = java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                    String v = java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    map.put(k, v);
                } else if (!pair.isEmpty()) {
                    map.put(java.net.URLDecoder.decode(pair, "UTF-8"), "");
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    /**
     * setPreResult 之后的第一记 setResult/setSearchResult/setHomeResult：清空预返回占位。
     */
    private void beginResult() {
        if (preResultActive) {
            results.clear();
            rawResults.clear();
            preResultActive = false;
        }
    }

    /**
     * $.require('hiker://page/xxx') 的页面代码查找：从规则 pages（JSON 数组）按 path 取 rule 字段。
     */
    private String findPageCode(String path) {
        try {
            String pagesJson = rule.getPages();
            if (TextUtils.isEmpty(pagesJson)) return null;
            List<Map<String, Object>> pages = GSON.fromJson(pagesJson, MAP_LIST_TYPE);
            if (pages == null) return null;
            for (Map<String, Object> p : pages) {
                if (path.equals(String.valueOf(p.get("path")))) {
                    Object r = p.get("rule");
                    return r == null ? null : String.valueOf(r);
                }
            }
        } catch (Throwable e) {
            Logger.t(TAG).d("findPageCode failed: %s", e.getMessage());
        }
        return null;
    }

    /** 把 JS 传来的对象参数转成 Map<String,Object>（JSObject/stringify/JSON 字符串均可）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toStrMap(Object o) {
        if (o == null) return null;
        try {
            if (o instanceof Map) return (Map<String, Object>) o;
            String json = o instanceof NativeObject ? jsStringify(o) : String.valueOf(o).trim();
            if (!json.startsWith("{")) return null;
            return GSON.fromJson(json, new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
        } catch (Throwable e) {
            return null;
        }
    }

    /** 在当前 results 里按 url 找条目下标。 */
    private int findResultIndex(String id) {
        for (int i = 0; i < results.size(); i++) {
            if (id.equals(results.get(i).getUrl())) return i;
        }
        return -1;
    }

    /** updateItem：把 obj 里的 title/url/pic/pic_url/img/desc/col_type 合并进条目。 */
    private void mergeItem(HkItem item, Map<String, Object> m) {
        String t = str(m, "title");
        if (!TextUtils.isEmpty(t)) item.setTitle(t);
        String u = str(m, "url");
        if (!TextUtils.isEmpty(u)) item.setUrl(u);
        String pic = str(m, "pic_url");
        if (TextUtils.isEmpty(pic)) pic = str(m, "img");
        if (TextUtils.isEmpty(pic)) pic = str(m, "pic");
        if (!TextUtils.isEmpty(pic)) item.setPic(pic);
        String d = str(m, "desc");
        if (!TextUtils.isEmpty(d)) item.setDesc(d);
        String c = str(m, "col_type");
        if (!TextUtils.isEmpty(c)) item.setColType(c);
        // 8.83 DetailPage 字段（updateItem/addItem 用）
        String fr = str(m, "findRule");
        if (TextUtils.isEmpty(fr)) fr = str(m, "find_rule");
        if (!TextUtils.isEmpty(fr)) item.setFindRule(fr);
        String g = str(m, "group");
        if (!TextUtils.isEmpty(g)) item.setGroup(g);
        String pg = str(m, "pages");
        if (!TextUtils.isEmpty(pg)) item.setPages(pg);
        String pr = str(m, "preRule");
        if (TextUtils.isEmpty(pr)) pr = str(m, "pre_rule");
        if (!TextUtils.isEmpty(pr)) item.setPreRule(pr);
        String r = str(m, "rule");
        if (!TextUtils.isEmpty(r)) item.setRule(r);
    }

    /** addItemAfter/addItemBefore(id, obj)：在匹配 url 的条目之后/之前插入。 */
    private void addItemAt(Object[] args, boolean after) {
        try {
            if (args == null || args.length < 2) return;
            String id = argStr(args[0]);
            Map<String, Object> m = toStrMap(args[1]);
            if (m == null) return;
            HkItem item = new HkItem();
            mergeItem(item, m);
            if (TextUtils.isEmpty(item.getUrl())) item.setUrl(id);
            int idx = findResultIndex(id);
            if (idx < 0) {
                results.add(item);
            } else {
                results.add(after ? idx + 1 : idx, item);
            }
            Logger.t(TAG).d("addItem%s: %s", after ? "After" : "Before", id);
        } catch (Throwable e) {
            Logger.t(TAG).d("addItemAt failed: %s", e.getMessage());
        }
    }

    /** 官方 AesUtil.decrypt 同款：base64 → AES/ECB/PKCS5Padding，key 补 '0' 到 32 字节。 */
    private static String aesDecryptECB(String b64, String key) {
        try {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            byte[] keyBytes = new byte[32];
            byte[] kb = key.getBytes(Charset.forName("UTF-8"));
            System.arraycopy(kb, 0, keyBytes, 0, Math.min(kb.length, 32));
            for (int i = kb.length; i < 32; i++) keyBytes[i] = '0';
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(keyBytes, "AES"));
            return new String(cipher.doFinal(data), Charset.forName("UTF-8"));
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * getPrivateJS 的加密 counterpart：AES/ECB/PKCS5Padding，key 补 0 到 32 字节，base64 输出。
     * 官方用 AES_DEFAULT_KEY="1234567890kkkk"，宿主取同 key、ECB 模式实现，保证宿主内确定性。
     */
    private static String aesEncryptECB(String plain, String key) {
        try {
            byte[] keyBytes = new byte[32];
            byte[] kb = key.getBytes(Charset.forName("UTF-8"));
            System.arraycopy(kb, 0, keyBytes, 0, Math.min(kb.length, 32));
            for (int i = kb.length; i < 32; i++) keyBytes[i] = '0';
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(keyBytes, "AES"));
            return Base64.encodeToString(cipher.doFinal(plain.getBytes(Charset.forName("UTF-8"))), Base64.NO_WRAP);
        } catch (Throwable e) {
            return "";
        }
    }

    /** fc/rc 的内存缓存：hours<=0 不缓存；命中且未过期直接返回。 */
    private String memCached(String key, double hours, java.util.concurrent.Callable<String> loader) {
        try {
            if (hours > 0) {
                MemCache hit = memCache.get(key);
                if (hit != null && hit.alive()) {
                    Logger.t(TAG).d("memCache hit: %s", key);
                    return hit.data;
                }
            }
            String data = loader.call();
            if (data == null) data = "";
            if (hours > 0) memCache.put(key, new MemCache(data, System.currentTimeMillis() + (long) (hours * 3600_000)));
            return data;
        } catch (Throwable e) {
            Logger.t(TAG).d("memCached failed: %s", e.getMessage());
            return "";
        }
    }

    private static double toDouble(Object o) {
        try {
            if (o instanceof Number) return ((Number) o).doubleValue();
            return Double.parseDouble(String.valueOf(o));
        } catch (Throwable e) {
            return 0;
        }
    }

    /** fetchPC：options 里没有 UA 时补桌面 UA（规则自带的 UA 优先保留）。 */
    private static String mergeDesktopUa(String optionsJson) {
        try {
            Map<String, Object> map = GSON.fromJson(
                    TextUtils.isEmpty(optionsJson) ? "{}" : optionsJson,
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
            if (map == null) map = new HashMap<>();
            Object headers = map.get("headers");
            Map<String, Object> hm = headers instanceof Map ? (Map<String, Object>) headers : new HashMap<>();
            boolean hasUa = false;
            for (String k : hm.keySet()) {
                if ("user-agent".equalsIgnoreCase(k)) { hasUa = true; break; }
            }
            if (!hasUa) {
                hm.put("User-Agent", UA_PC);
                map.put("headers", hm);
            }
            return GSON.toJson(map);
        } catch (Throwable e) {
            return "{\"headers\":{\"User-Agent\":\"" + UA_PC + "\"}}";
        }
    }

    /**
     * 8.83 JSEngine.generateRequestOptions 同款：fetch/post/request 没传 User-Agent 时
     * 自动补移动端 UA（SettingConfig.getMobileUA）。照抄 mergeDesktopUa，只换 UA 值。
     */
    private static String mergeMobileUa(String optionsJson) {
        try {
            Map<String, Object> map = GSON.fromJson(
                    TextUtils.isEmpty(optionsJson) ? "{}" : optionsJson,
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
            if (map == null) map = new HashMap<>();
            Object headers = map.get("headers");
            Map<String, Object> hm = headers instanceof Map ? (Map<String, Object>) headers : new HashMap<>();
            boolean hasUa = false;
            for (String k : hm.keySet()) {
                if ("user-agent".equalsIgnoreCase(k)) { hasUa = true; break; }
            }
            if (!hasUa) {
                hm.put("User-Agent", UA_MOBILE);
                map.put("headers", hm);
            }
            return GSON.toJson(map);
        } catch (Throwable e) {
            return "{\"headers\":{\"User-Agent\":\"" + UA_MOBILE + "\"}}";
        }
    }

    /**
     * 文件路径解析：hiker://files/... → App filesDir；绝对路径原样；相对路径 → 规则数据目录。
     * 全部钳制在 App 文件目录内。
     */
    private String resolveHikerPath(String p) {
        try {
            if (TextUtils.isEmpty(p)) return "";
            File base = App.get().getFilesDir();
            if (p.startsWith("hiker://files/")) {
                return new File(base, p.substring("hiker://files/".length())).getAbsolutePath();
            }
            if (p.startsWith("file://")) p = p.substring("file://".length());
            File f = new File(p);
            if (!f.isAbsolute()) f = new File(HkRuleManager.get().getDataDir(rule.getTitle()), p);
            return f.getAbsolutePath();
        } catch (Throwable ignored) {
            return p == null ? "" : p;
        }
    }

    private String readFileContent(String absPath) {
        return readFileContent(absPath, null);
    }

    /**
     * 读文件为字符串（8.83 FileUtil.fileToString 等价：平台默认字符集，Android 上即 UTF-8）。
     * 8.83 本身不做字符集探测；此处在等价基础上做两处兼容增强：
     * 1) 显式 charset 参数（规则可传 "GBK" 等）；
     * 2) 剥离 UTF-8 BOM（\uFEFF），否则 JSON 解析可能失败。
     */
    private String readFileContent(String absPath, String charset) {
        try {
            File f = new File(absPath);
            if (!f.exists() || !f.isFile()) return "";
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            Charset cs = Charset.forName("UTF-8");
            if (charset != null && !charset.trim().isEmpty()) {
                try {
                    cs = Charset.forName(charset.trim());
                } catch (Throwable ignored) {
                }
            }
            String s = new String(bytes, cs);
            if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
            return s;
        } catch (Throwable e) {
            return "";
        }
    }

    /** 8.83 JSEngine.aesDecode(Object, Object) 的 Java 等价：AES/CBC 解密文件内容。 */
    private String aesDecode(String key, String content) {
        try {
            return Crypto.aes("AES/CBC/PKCS5Padding", false, content, true, key, "", false);
        } catch (Throwable e) {
            return "";
        }
    }

    private void writeFileContent(String absPath, String content) {
        try {
            File f = new File(absPath);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), (content == null ? "" : content).getBytes(Charset.forName("UTF-8")));
        } catch (Throwable e) {
            Logger.t(TAG).d("writeFile failed %s: %s", absPath, e.getMessage());
        }
    }

    /** fixM3u8(url, content)：把 m3u8 里相对分片地址按 url 补全。 */
    private static String fixM3u8Content(String url, String content) {
        if (TextUtils.isEmpty(content)) return "";
        try {
            java.net.URI base = new java.net.URI(url);
            StringBuilder sb = new StringBuilder();
            for (String line : content.split("\n")) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#") && !t.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
                    try { t = base.resolve(t).toString(); } catch (Throwable ignored) {}
                    sb.append(t).append('\n');
                } else {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (Throwable e) {
            return content;
        }
    }

    private static String hexToB64(String hex) {
        try {
            String h = hex.trim();
            byte[] b = new byte[h.length() / 2];
            for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            return Base64.encodeToString(b, Base64.NO_WRAP);
        } catch (Throwable e) {
            return "";
        }
    }

    /** RSA 加解密（best-effort）：公钥加密 X.509 / 私钥解密 PKCS8，PKCS1Padding，base64 出入。 */
    private static String rsaCrypto(boolean encrypt, String input, String keyB64) {
        try {
            byte[] keyBytes = Base64.decode(keyB64.trim(), Base64.DEFAULT);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            if (encrypt) {
                java.security.PublicKey pub = kf.generatePublic(new java.security.spec.X509EncodedKeySpec(keyBytes));
                c.init(javax.crypto.Cipher.ENCRYPT_MODE, pub);
                return Base64.encodeToString(c.doFinal(input.getBytes(Charset.forName("UTF-8"))), Base64.NO_WRAP);
            } else {
                java.security.PrivateKey prv = kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(keyBytes));
                c.init(javax.crypto.Cipher.DECRYPT_MODE, prv);
                return new String(c.doFinal(Base64.decode(input.trim(), Base64.DEFAULT)), Charset.forName("UTF-8"));
            }
        } catch (Throwable e) {
            return "";
        }
    }

    private static String getLocalIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a.getHostAddress() != null && a.getHostAddress().indexOf(':') < 0) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    // ================= setPublicItem/getPublicItem 持久化（跨规则） =================

    private File publicKvFile() {
        File dataDir = HkRuleManager.get().getDataDir(rule.getTitle());
        File parent = dataDir.getParentFile();
        if (parent == null) parent = dataDir;
        return new File(parent, "public_kv.json");
    }

    private void loadPublicKv() {
        try {
            File f = publicKvFile();
            if (!f.exists()) return;
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            Map<String, String> map = GSON.fromJson(new String(bytes, "UTF-8"),
                    new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());
            if (map != null) publicKv.putAll(map);
        } catch (Throwable ignored) {
        }
    }

    private void savePublicKv() {
        try {
            File f = publicKvFile();
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), GSON.toJson(publicKv).getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }

    /** 官方 ArticleColTypeEnum 的全部 col_type code（getColTypes 用）。 */
    private static final List<String> COL_TYPES = java.util.Arrays.asList(
            "text_1", "text_2", "text_3", "text_4", "text_5", "text_center_1", "text_icon",
            "movie_1", "movie_2", "movie_3", "movie_1_left_pic", "movie_1_vertical_pic",
            "movie_1_vertical_pic_blur", "movie_3_marquee",
            "pic_1", "pic_2", "pic_3", "pic_1_full", "pic_1_center", "pic_3_square", "pic_1_card", "pic_2_card",
            "icon_1_search", "icon_2_round", "icon_3_fill", "icon_4", "icon_small_4", "icon_round_small_4",
            "icon_5_no_crop", "icon_1_left_pic", "icon_2", "icon_3_round_fill", "icon_4_card", "icon_5",
            "icon_round_4", "icon_small_3",
            "rich_text", "long_text", "avatar", "video", "header", "footer", "line", "line_blank",
            "blank_block", "big_blank_block", "big_big_blank_block", "scroll_button", "flex_button", "input",
            "card_pic_1", "card_pic_2", "card_pic_2_2", "card_pic_2_2_left", "card_pic_3", "card_pic_3_center",
            "x5_webview_single"
    );

    /**
     * require() 本地库优先：找规则数据目录 data/&lt;规则名&gt;/libs/&lt;md5(url)&gt;.js，
     * 命中则直接加载（.hkzip 导入时已解压，无网络也能用）；未命中返回 null 走远程。
     */
    private String loadLibLocal(String libUrl) {
        try {
            if (libUrl == null || !libUrl.startsWith("http")) return null;
            String md5 = Util.md5(libUrl);
            if (TextUtils.isEmpty(md5)) return null;
            File f = new File(new File(HkRuleManager.get().getDataDir(rule.getTitle()), "libs"), md5 + ".js");
            if (!f.exists()) return null;
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            String code = new String(bytes, Charset.forName("UTF-8"));
            return TextUtils.isEmpty(code) ? null : code;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * hiker://assets/xxx：从宿主内置 assets 读取文件内容（海阔官方内置库同款路径）。
     * 文件不存在返回 null。
     */
    private String loadAsset(String assetPath) {
        try {
            java.io.InputStream is = App.get().getAssets().open(assetPath);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            String code = bos.toString("UTF-8");
            return TextUtils.isEmpty(code) ? null : code;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * D2：hiker://assets/xxx.js → 读宿主内置 assets 返回文本内容。
     * 供 request()/fetch() 调用（官方 $.require 经 getScript→request() 取脚本，
     * 不是 __hkRequirePage）。去 query/fragment 后按 asset 路径读取。
     * 找不到返回 ""（不抛异常）。
     */
    private String loadAssetText(String url) {
        try {
            String assetPath = url.substring("hiker://assets/".length());
            int q = assetPath.indexOf('?');
            if (q >= 0) assetPath = assetPath.substring(0, q);
            int h = assetPath.indexOf('#');
            if (h >= 0) assetPath = assetPath.substring(0, h);
            String code = loadAsset(assetPath.trim());
            return code == null ? "" : code;
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ================= 对外接口 =================

    /**
     * 带 headers 下载文件到绝对路径（saveImage/requireDownload 共用）。
     * headersJson 为 JSON：{headers:{...}} 或直接 {k:v}。
     */
    private void downloadToFile(String url, String dest, String headersJson) {
        if (TextUtils.isEmpty(url)) return;
        try {
            File f = new File(dest);
            if (f.isDirectory() || dest.endsWith("/")) {
                String name = url.replaceAll("[?#].*$", "").replaceAll("^.*/", "");
                if (TextUtils.isEmpty(name)) name = "download.bin";
                f = new File(f, name);
            }
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            String opt = "{\"headers\":{}}";
            try {
                Map<String, Object> m = GSON.fromJson(headersJson,
                        new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
                if (m != null) {
                    Object h = m.get("headers");
                    if (h == null) h = m.get("header");
                    Map<String, Object> hm = h instanceof Map ? (Map<String, Object>) h : m;
                    Map<String, String> flat = new HashMap<>();
                    for (Map.Entry<String, Object> e : hm.entrySet()) {
                        if (e.getValue() != null) flat.put(e.getKey(), String.valueOf(e.getValue()));
                    }
                    Map<String, Object> wrap = new HashMap<>();
                    wrap.put("headers", flat);
                    opt = GSON.toJson(wrap);
                }
            } catch (Throwable ignored) {
            }
            try (okhttp3.Response res = HkHttp.to(url,
                    com.fongmi.quickjs.bean.Req.objectFrom(opt)).execute()) {
                if (res.isSuccessful() && res.body() != null) {
                    java.nio.file.Files.write(f.toPath(), res.body().bytes());
                    Logger.t(TAG).d("downloadToFile ok: %s", f.getAbsolutePath());
                } else {
                    Logger.t(TAG).d("downloadToFile bad response: %s", url);
                }
            }
        } catch (Throwable e) {
            Logger.t(TAG).d("downloadToFile failed %s: %s", url, e.getMessage());
        }
    }

    /**
     * 执行首页/分类的 js: 规则，返回条目列表。
     */
    public List<HkItem> parseList(String jsCode, String myUrl) throws Exception {
        return parseList(jsCode, myUrl, 1);
    }

    /**
     * 执行首页/分类的 js: 规则，返回条目列表（带页码，注入 MY_PAGE）。
     */
    public List<HkItem> parseList(String jsCode, String myUrl, int page) throws Exception {
        return submit(() -> {
            results.clear();
            error = null;
            preResultActive = false;
            // R6: 求值前清掉 refreshPage 标记，避免上次残留被误判为本次的新请求
            refreshRequested = false;
            refreshToTop = false;
            this.page = Math.max(1, page);
            setContext(myUrl);
            try {
                evalJs(stripJsPrefix(jsCode));
            } catch (Throwable e) {
                // 之前异常直接上抛到 HkEngine.home() 被吞掉只剩 debug 日志，
                // UI 永远只显示"加载失败"。先把根因记下来，供 getError()/空态展示。
                error = jsErrorMsg(e);
                throw e;
            }
            return drainResults();
        }).get();
    }

    /**
     * 执行搜索的 js: 规则，返回条目列表。
     */
    public List<SearchResult> parseSearch(String jsCode, String myUrl, String keyword) throws Exception {
        return parseSearch(jsCode, myUrl, keyword, 1);
    }

    /**
     * 执行搜索的 js: 规则，返回搜索结果（带页码，注入 MY_PAGE）。
     * 8.83 JSEngine.callbackSearchResult 同款：JS 结果按 title/url/desc/descMore/
     * content/img/extra/type 映射为独立 SearchResult（type 硬编码 "video"）。
     */
    public List<SearchResult> parseSearch(String jsCode, String myUrl, String keyword, int page) throws Exception {
        return submit(() -> {
            results.clear();
            error = null;
            preResultActive = false;
            // R6: 求值前清掉 refreshPage 标记，避免上次残留被误判
            refreshRequested = false;
            refreshToTop = false;
            this.page = Math.max(1, page);
            setContext(myUrl);
            ScriptableObject.putProperty(scope, "MY_KEYWORD", keyword == null ? "" : keyword);
            try {
                evalJs(stripJsPrefix(jsCode));
            } catch (Throwable e) {
                error = jsErrorMsg(e);
                throw e;
            }
            return drainSearchResults();
        }).get();
    }

    /** setStrResult/setLastChapterResult 存下的字符串结果。 */
    public String getStrResult() {
        return strResult;
    }

    /** setLastChapterRule 存下的最新章节规则。 */
    public String getLastChapterRule() {
        return lastChapterRule;
    }

    /** setPagePicUrl 存下的页面配图。 */
    public String getPagePicUrl() {
        return pagePicUrl;
    }

    /**
     * HkSelector.JsEvaluator / Parser.JsEvaluator 实现：选择器 .js: 值加工。
     * 把取值结果作为全局 input，执行 JS 片段并返回结果字符串。
     * 已在 JS 线程时（如 pdfh/pdfa 的 .js: 回调）直接求值，避免单线程池死锁。
     */
    @Override
    public String eval(String js, String input) {
        error = null;
        try {
            if (Thread.currentThread() == jsThread) {
                ScriptableObject.putProperty(scope, "input", input == null ? "" : input);
                return argStr(evalJs(js));
            }
            return submit(() -> {
                ScriptableObject.putProperty(scope, "input", input == null ? "" : input);
                Object r = evalJs(js);
                // 8.83 语义：数字/布尔返回值转字符串（如 .js: 取 input.id 为数字时），
                // 用 argStr() 而非裸 getString()（后者对 Number 返回 null → ""）。
                return argStr(r);
            }).get();
        } catch (Exception e) {
            String msg = e.getCause() != null && e.getCause().getMessage() != null
                    ? e.getCause().getMessage() : e.getMessage();
            error = msg == null || msg.isEmpty() ? "JS 执行失败" : msg;
            Logger.t(TAG).d("evalJs failed: %s", e.getMessage());
            return input == null ? "" : input;
        }
    }

    /**
     * 8.83 OkHttpStreamFetcher.evalJS 语义：图片 {@code @js=} 求值。
     *
     * <p>{@code input} 绑定为 Java InputStream（响应流；下载失败时为 null）。
     * JS 求值结果为 InputStream（或其 NativeJavaObject 包装）才采用，且与原 input
     * 不同时关闭原流；结果非流或任何异常 → 返回原 input（调用方决定失败处理）。
     * 复用规则 scope（$、Hikerurl.js、规则函数均已注入），跑在 JS 单线程 executor
     * 上保证 scope 安全；求值前后保存/恢复全局 input。</p>
     */
    public InputStream evalImageJs(String jsCode, InputStream input, Map<String, List<String>> headers, String urlString) {
        try {
            return submit(() -> {
                // C5: 8.83 每次图片 JS 用全新 scope 并重求值 Hikerurl.js（JSEngine.smali:13914，
                // OkHttpStreamFetcher.smali:289/451），$ / $.require 必可用。
                // 星辰之前复用规则 scope，$ 缺失时只靠 ensureDollarShim 兜底 crypto-java.js，
                // 其他 require 路径抛错被吞 → 返回加密原字节 → 解码失败。
                Scriptable imageScope = rhinoCx.newObject(scope);
                imageScope.setPrototype(scope);
                imageScope.setParentScope(null);
                try {
                    // 重求值 Hikerurl.js，确保 $ 完整可用
                    try {
                        String hikerUrlJs = loadAsset("Hikerurl.js");
                        if (!TextUtils.isEmpty(hikerUrlJs)) {
                            rhinoCx.evaluateString(imageScope, hikerUrlJs, "Hikerurl.js", 1, null);
                        }
                    } catch (Throwable t) {
                        Logger.t(TAG).d("evalImageJs: re-eval Hikerurl.js failed: %s", t.getMessage());
                    }
                    ScriptableObject.putProperty(imageScope, "input", Context.javaToJS(input, imageScope));
                    String code = generateMyHeaders(headers) + getMyUrlJs(urlString) + ensureDollarShim() + wrapImageJs(jsCode);
                    Object r = rhinoCx.evaluateString(imageScope, code, "imageJs", 1, null);
                    return coerceImageStream(r, input);
                } catch (Throwable t) {
                    return input;
                }
            }).get();
        } catch (Throwable t) {
            return input;
        }
    }

    /**
     * 图片 @js= 求值时的 $ 兜底。
     *
     * <p>8.83 的图片 JS 求值上下文（OkHttpStreamFetcher.evalJS）与规则共用 JSEngine 单例，
     * $（Hikerurl.js）必然可用。但星辰是多规则 HkJsRuntime，图片求值复用的 scope
     * 可能因 Hikerurl.js 注入失败而没有 $，导致解密 JS 的 $.require("hiker://assets/crypto-java.js")
     * 抛 ReferenceError，被 wrapImageJs 的 try/catch 吞掉后返回加密原字节，Glide 解码失败。</p>
     *
     * <p>此处注入最小 $ shim：仅当 $ 或 $.require 不可用时生效，提供能加载
     * hiker://assets/crypto-java.js 的 require（从宿主 assets 读取，复用 $.exports 导出约定）。</p>
     */
    private String ensureDollarShim() {
        try {
            String cryptoJs = loadAsset("crypto-java.js");
            if (cryptoJs == null) return "";
            // 转义为 JS 字符串字面量
            String esc = escapeJsString(cryptoJs);
            return "if (typeof $ === 'undefined' || typeof $.require !== 'function') {\n" +
                    "  var $ = (typeof $ !== 'undefined') ? $ : {};\n" +
                    "  $.exports = $.exports || {};\n" +
                    "  $.require = (function() {\n" +
                    "    var __cryptoCache = null;\n" +
                    "    var __cryptoCode = '" + esc + "';\n" +
                    "    return function(path) {\n" +
                    "      if (path === 'hiker://assets/crypto-java.js') {\n" +
                    "        if (__cryptoCache) return __cryptoCache;\n" +
                    "        eval(__cryptoCode);\n" +
                    "        __cryptoCache = $.exports;\n" +
                    "        $.exports = {};\n" +
                    "        return __cryptoCache;\n" +
                    "      }\n" +
                    "      throw new Error('Module not found in image context: ' + path);\n" +
                    "    };\n" +
                    "  })();\n" +
                    "}\n";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 8.83 wrapJS：@js= 体包 try/catch，异常记 log 后按"非流"处理（返回原 input）。 */
    private static String wrapImageJs(String jsCode) {
        return "try{\n" + jsCode + "\n}catch(e){\nlog(e.toString());\n}";
    }

    /** 8.83 JSEngine.generateMY("MY_HEADERS", multimap) 语义。 */
    private static String generateMyHeaders(Map<String, List<String>> headers) {
        if (headers == null) return "var MY_HEADERS = null;\n";
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                o.put(e.getKey(), new org.json.JSONArray(e.getValue()));
            }
            return "var my_headers = '" + escapeJsString(o.toString()) + "';\n var MY_HEADERS = JSON.parse(my_headers);\n";
        } catch (Throwable t) {
            return "var MY_HEADERS = null;\n";
        }
    }

    /** 8.83 JSEngine.getMyUrl 语义：var MY_URL / var MY_HOME。 */
    private static String getMyUrlJs(String url) {
        if (url == null) url = "";
        String esc = escapeJsString(url);
        return "var MY_URL = '" + esc + "';\nvar MY_HOME = '" + getHomeUrl(esc) + "';\n";
    }

    /** 8.83 StringUtil.getHome 语义：scheme://dom。 */
    private static String getHomeUrl(String url) {
        try {
            String u = getRealImageUrl(url);
            String dom = getImageDom(u);
            String scheme = u.split("://")[0];
            return scheme + "://" + dom;
        } catch (Throwable t) {
            return "";
        }
    }

    /** JS 单引号字符串转义（adblockplus Utils.escapeJavaScriptString 同款语义）。 */
    private static String escapeJsString(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 8.83 StringUtil.getRealUrl 简化版：去 @lazyRule=/@rule= 后缀与 hiker://empty 前缀。 */
    private static String getRealImageUrl(String url) {
        if (TextUtils.isEmpty(url)) return url;
        try {
            String u = url.split("@lazyRule=")[0].split("@rule=")[0];
            if (u.startsWith("hiker://empty#http")) u = replaceOnce(u, "hiker://empty#", "");
            else if (u.startsWith("webview://")) u = u.replaceFirst("webview://", "");
            else if (u.startsWith("hiker://empty##http") || u.startsWith("hiker://empty##https%3A%2F%2F") || u.startsWith("hiker://empty##http%3A%2F%2F"))
                u = replaceOnce(u, "hiker://empty##", "");
            else if (u.startsWith("hiker://empty$http")) u = replaceOnce(u, "hiker://empty$", "");
            else if (u.startsWith("hiker://empty$$http")) u = replaceOnce(u, "hiker://empty$$", "");
            int d = u.indexOf("$$$");
            if (d >= 0) u = u.substring(0, d);
            return u;
        } catch (Throwable t) {
            return url;
        }
    }

    private static String replaceOnce(String s, String from, String to) {
        int i = s.indexOf(from);
        return i < 0 ? s : s.substring(0, i) + to + s.substring(i + from.length());
    }

    /** 8.83 StringUtil.getDom/getDom0 语义：取 host。 */
    private static String getImageDom(String url) {
        if (TextUtils.isEmpty(url)) return url;
        try {
            String u = getRealImageUrl(url);
            String[] parts = u.split("://");
            if (parts.length > 1) return parts[1].split("/")[0];
            parts = u.split("//");
            if (parts.length > 1) return parts[1].split("/")[0];
            return u.replaceFirst("http://", "").replaceFirst("https://", "").replaceFirst("hiker://", "").split("/")[0];
        } catch (Throwable t) {
            return "";
        }
    }

    /** 求值结果 → 流：是流则采用（与原流不同时关原流），否则返回原 input。 */
    private static InputStream coerceImageStream(Object r, InputStream input) {
        try {
            Object v = r;
            if (v instanceof NativeJavaObject) v = ((NativeJavaObject) v).unwrap();
            if (v instanceof InputStream) {
                if (v != input && input != null) {
                    try {
                        input.close();
                    } catch (Throwable ignored) {
                    }
                }
                return (InputStream) v;
            }
        } catch (Throwable ignored) {
        }
        return input;
    }

    /**
     * M4：lazyRule / 播放 js 的独立作用域求值。
     *
     * <p>按"独立作用域"铁律：只注入全局 Hiker API，外加 {@code MY_URL}/{@code input}
     * 两个形参（均为当前页面地址），不依赖 JS 闭包。返回代码求值结果的字符串；
     * 若求值为空则兜底取 {@code setResult} 收集到的第一条 url。</p>
     */
    public String evalLazy(String jsCode, String pageUrl) throws Exception {        return submit(() -> {
            error = null;
            setContext(pageUrl);
            // 入口口令/输入框：若有一次性 input 覆盖（用户输入的文本），优先使用；
            // 否则按原逻辑用 pageUrl。读后清零，保证只生效一次。
            String inputVal = nextInputOverride != null ? nextInputOverride : (pageUrl == null ? "" : pageUrl);
            nextInputOverride = null;
            ScriptableObject.putProperty(scope, "input", inputVal);
            Object r = evalJs(stripJsPrefix(jsCode));
            // 同 eval()：用 argStr() 处理数字/布尔返回值
            String s = argStr(r);
            s = s.trim();
            if (s.isEmpty() || "undefined".equals(s) || "null".equals(s)) {
                List<HkItem> items = drainResults();
                if (!items.isEmpty() && !TextUtils.isEmpty(items.get(0).getUrl())) {
                    return items.get(0).getUrl().trim();
                }
                return "";
            }
            return s;
        }).get();
    }

    /**
     * 给 HkSelector 配一个带 JS 求值能力的实例（M1 的 null 透传升级为真求值）。
     */
    public HkSelector newSelector() {
        HkSelector selector = new HkSelector();
        selector.setJsEvaluator(this);
        return selector;
    }

    /** 选择器文本预处理：冲突字符解码（？？→?、＆＆→&、；；→;、，，→,）。 */
    private String sel(Object o) {
        return HkSelector.decodeConflict(String.valueOf(o));
    }

    public String getError() {
        return error;
    }

    /**
     * JS 引擎最后一次 fetch 的真实 URL（图片自动 Referer 用，对齐 8.83 GlideUtil.getGlideUrl 语义）。
     * 注意：setContext 时会先被置为 MY_URL（含 hiker://empty## 前缀的模板 URL），
     * 只有 fetch 成功后才是真实 http(s) URL；调用方需自行校验 scheme。
     */
    public String getLastUrl() {
        return lastUrl;
    }

    /**
     * 设置 @rule= js: 预检时的响应正文（调用方先 HkHttp.get(baseUrl) 取回）。
     * getResCode()/getCode() 在无 CALLBACK_KEY 时会返回此值；传 null 清除。
     */
    public void setEvalResCodeBody(String body) {
        evalResCodeBody = body;
    }

    /**
     * 取异常链上第一条非空 message（ExecutionException 包裝后的根因），
     * 供 parseList/parseSearch 在 JS 求值失败时记录，避免"加载失败"无从查起。
     */
    private static String jsErrorMsg(Throwable e) {
        Throwable t = e;
        while (t != null) {
            String m = t.getMessage();
            if (m != null && !m.trim().isEmpty()) {
                String s = m.trim();
                // Rhino 异常常带堆栈，截短到一行
                int nl = s.indexOf('\n');
                if (nl > 0) s = s.substring(0, nl);
                return s.length() > 160 ? s.substring(0, 160) : s;
            }
            t = t.getCause();
        }
        return e == null ? "" : String.valueOf(e.getClass().getSimpleName());
    }

    /**
     * 取走 refreshPage 请求标记（读后清零）。供 HkRouter.evalTab 等在 JS 求值后检查：
     * 若规则回调里调了 refreshPage，上层应重刷当前列表。
     */
    public boolean consumeRefreshRequest() {
        boolean r = refreshRequested;
        refreshRequested = false;
        return r;
    }

    /**
     * 当前 results 数量快照。供 @rule=js: 点击动作在 evalLazy 前后成对调用，
     * 判断规则是否就地改了列表（如自绘"加载更多"按钮调 addItemBefore/deleteItemByCls 追加条目）。
     * 必须在 evalLazy 同一后台线程调用（evalLazy 内部 submit().get() 已同步 JS 线程）。
     */
    public int actionResultCount() {
        return results.size();
    }

    /**
     * 当前 results 拷贝。供 @rule=js: 点击动作取回规则就地追加的条目，
     * 上层按 url+title 去重后并入当前列表（8.83 addItemBefore 就地更新同款）。
     */
    public List<HkItem> snapshotActionResults() {
        return new ArrayList<>(results);
    }

    /**
     * 取走 goTo 导航请求（读后清零）。供 @rule=js: 等动作 JS 求值后检查：
     * 若规则调了 goTo(url)，上层应导航到该 URL（如 hiker://page/detail 打开 V4）。
     * @return 目标 URL，无请求时返回 ""。
     */
    public String consumeGoToRequest() {
        String r = goToUrl == null ? "" : goToUrl;
        goToUrl = null;
        return r;
    }

    /** refreshPage 请求标记当前是否为 true（不读后清零，供调用方做"变化检测"）。 */
    public boolean isRefreshRequested() {
        return refreshRequested;
    }

    /**
     * 设置下一次 evalLazy 的 input 覆盖值（入口口令/输入框流程用）。
     * 官方语义：input 条目点击时用户输入的文本作为 input 变量，而非 pageUrl。
     */
    public void setNextInputOverride(String input) {
        this.nextInputOverride = input;
    }

    public boolean isRefreshToTop() {
        return refreshToTop;
    }

    public void destroy() {
        destroyed = true;
        clearAllTimers();
        try {
            submit(() -> {
                try {
                    // Rhino Context 与线程绑定：enter/exit 必须在同一线程配对（即本单线程 executor）
                    if (rhinoCx != null) Context.exit();
                } catch (Throwable ignored) {
                }
                rhinoCx = null;
                scope = null;
                return null;
            }).get();
        } catch (Throwable ignored) {
        } finally {
            executor.shutdownNow();
            try {
                timerScheduler.shutdownNow();
            } catch (Throwable ignored) {
            }
        }
    }

    // ================= 内部实现 =================

    private void setContext(String myUrl) {
        lastUrl = myUrl == null ? "" : myUrl;
        ScriptableObject.putProperty(scope, "MY_URL", lastUrl);
        ScriptableObject.putProperty(scope, "MY_HOME", homeOf(lastUrl));
        ScriptableObject.putProperty(scope, "MY_PAGE", page);
        ScriptableObject.putProperty(scope, "MY_TICKET", "");
        ScriptableObject.putProperty(scope, "MOBILE_UA", UA_MOBILE);
        ScriptableObject.putProperty(scope, "PC_UA", UA_PC);
        // P1：补全官方注入变量
        ScriptableObject.putProperty(scope, "MY_TYPE", myType == null ? "" : myType);
        ScriptableObject.putProperty(scope, "MY_CLASS_URL", myClassUrl == null ? "" : myClassUrl);
        ScriptableObject.putProperty(scope, "MY_CLASS_NAME", myClassName == null ? "" : myClassName);
        ScriptableObject.putProperty(scope, "MY_NAME", rule == null ? "" : rule.getTitle());
        // 官方 generateMyParams：MY_PARAMS 是 JSON 对象不是字符串（空时为 {}）
        try {
            String mp = myParams == null || myParams.isEmpty() ? "{}" : myParams;
            ScriptableObject.putProperty(scope, "MY_PARAMS", parseJson(mp));
        } catch (Throwable ignored) {
            ScriptableObject.putProperty(scope, "MY_PARAMS", rhinoCx.newObject(scope));
        }
        ScriptableObject.putProperty(scope, "MY_AREA", myArea == null ? "" : myArea);
        ScriptableObject.putProperty(scope, "MY_YEAR", myYear == null ? "" : myYear);
        ScriptableObject.putProperty(scope, "MY_SORT", mySort == null ? "" : mySort);
        // MY_YEAR_xxx / MY_AREA_xxx / MY_SORT_xxx：按当前取值动态注入（如 MY_YEAR_2024）
        if (myYear != null && !myYear.isEmpty())
            ScriptableObject.putProperty(scope, "MY_YEAR_" + myYear, myYear);
        if (myArea != null && !myArea.isEmpty())
            ScriptableObject.putProperty(scope, "MY_AREA_" + myArea, myArea);
        if (mySort != null && !mySort.isEmpty())
            ScriptableObject.putProperty(scope, "MY_SORT_" + mySort, mySort);
        try {
            Map<String, String> ua = new HashMap<>();
            ua.put("mobileUa", UA_MOBILE);
            ua.put("pcUa", UA_PC);
            ScriptableObject.putProperty(scope, "MY_UA", parseJson(GSON.toJson(ua)));
        } catch (Throwable ignored) {
        }
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", rule == null ? UA_MOBILE : rule.resolvedUa());
            ScriptableObject.putProperty(scope, "MY_HEADERS", parseJson(GSON.toJson(headers)));
        } catch (Throwable ignored) {
        }
        try {
            ScriptableObject.putProperty(scope, "MY_RULE", parseJson(GSON.toJson(rule)));
        } catch (Throwable e) {
            ScriptableObject.putProperty(scope, "MY_RULE", rhinoCx.newObject(scope));
        }
    }

    /**
     * 设置列表页上下文（HkEngine.home/search 在 parseList/parseSearch 之前调用），
     * 供 setContext 注入 MY_TYPE/MY_CLASS_URL/MY_CLASS_NAME/MY_PARAMS 等官方变量。
     */
    public void setListContext(String type, String classUrl, String className,
                              String params, String area, String year, String sort) {
        this.myType = type == null ? "" : type;
        this.myClassUrl = classUrl == null ? "" : classUrl;
        this.myClassName = className == null ? "" : className;
        this.myParams = params == null ? "" : params;
        this.myArea = area == null ? "" : area;
        this.myYear = year == null ? "" : year;
        this.mySort = sort == null ? "" : sort;
    }

    /**
     * 设置详情页 params（官方 dealRule 把点击条目的 extra 设为新规则的 params，
     * detail_find_rule 里通过 MY_PARAMS 取用）。extra 为 Map 时序列化为 JSON。
     */
    public void setDetailParams(java.util.Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) {
            this.myParams = "";
        } else {
            try {
                this.myParams = GSON.toJson(extra);
            } catch (Throwable ignored) {
                this.myParams = "";
            }
        }
    }

    private static String homeOf(String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            java.net.URI uri = new java.net.URI(url);
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            String host = uri.getHost() == null ? "" : uri.getHost();
            int port = uri.getPort();
            return scheme + "://" + host + (port > 0 ? ":" + port : "");
        } catch (Throwable e) {
            return url;
        }
    }

    private static String stripJsPrefix(String code) {
        if (code == null) return "";
        String t = code.trim();
        return t.startsWith("js:") ? t.substring(3) : t;
    }

    private void runPreRule() {
        if (preRuleDone) return;
        preRuleDone = true;
        String pre = rule.getPreRule();
        if (TextUtils.isEmpty(pre) || TextUtils.isEmpty(pre.trim())) return;
        try {
            // preRule 的 MY_URL 同样传原始 url（保留 hiker://empty# 前缀）。
            setContext(HkHttp.expandUrl(rule.getUrl(), "", "", "", "", 1, false));
            evalJs(pre.trim().startsWith("js:") ? pre.trim().substring(3) : pre);
            Logger.t(TAG).d("preRule done for %s", rule.getTitle());
        } catch (Throwable e) {
            Logger.t(TAG).d("preRule failed for %s: %s", rule.getTitle(), e.getMessage());
            // 之前静默吞掉，动态域名类规则（如 18AV）拿不到域名时用户只能看到"加载失败"。
            // 记下来供排障/UI 展示；不抛异常，保持原有"失败也继续加载"的行为。
            preRuleError = jsErrorMsg(e);
        }
    }

    /** preRule 失败根因（null 表示 preRule 不存在或执行成功）。 */
    public String getPreRuleError() {
        return preRuleError;
    }

    /**
     * 注入海阔规则配置对象 {@code config}（海阔"长按规则→设置"的 key-value，如 config.host）。
     * 配置持久化在 plugins/hk/config/&lt;规则名&gt;.json；文件不存在时注入空对象，保证
     * {@code config.xxx} 不抛 ReferenceError（取值为 undefined）。
     */
    private void loadConfig() {
        try {
            String json = HkRuleManager.get().loadRuleConfig(rule.getTitle());
            Object obj = parseJson(json);
            ScriptableObject.putProperty(scope, "config", obj);
        } catch (Throwable e) {
            try {
                ScriptableObject.putProperty(scope, "config", rhinoCx.newObject(scope));
            } catch (Throwable ignored) {
            }
        }
    }

    private void collectResult(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) return;
        try {
            String json = args[0] instanceof NativeArray
                    ? jsStringify(args[0])
                    : argStr(args[0]);
            if (json == null) return;
            String t = json.trim();
            // 官方 callbackHomeResult 兼容：setResult({data:[...]}) 的对象形式。
            // 之前只认顶层数组，对象形式会被 GSON 抛错静默丢弃 → 整个列表为空（"加载失败"）。
            if (t.startsWith("{")) {
                Map<String, Object> obj = GSON.fromJson(t, new TypeToken<Map<String, Object>>() {}.getType());
                Object data = obj == null ? null : obj.get("data");
                if (data instanceof List) {
                    appendResultItems(GSON.toJson(data));
                } else {
                    Logger.t(TAG).d("collectResult: object without data array");
                }
                return;
            }
            appendResultItems(t);
        } catch (Throwable e) {
            Logger.t(TAG).d("collectResult failed: %s", e.getMessage());
        }
    }

    /** 把 JSON 数组字符串解析为条目并追加到 results（collectResult 的实际落子逻辑）。 */
    private void appendResultItems(String jsonArray) {
        List<Map<String, Object>> list = GSON.fromJson(jsonArray, MAP_LIST_TYPE);
        if (list == null) return;
            for (Map<String, Object> m : list) {
                if (collectRaw) {
                    Map<String, String> raw = new HashMap<>();
                    for (Map.Entry<String, Object> e : m.entrySet()) {
                        raw.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                    }
                    rawResults.add(raw);
                    continue;
                }
                HkItem item = new HkItem();
                item.setTitle(str(m, "title"));
                item.setUrl(str(m, "url"));
                String pic = str(m, "pic_url");
                if (TextUtils.isEmpty(pic)) pic = str(m, "img");
                if (TextUtils.isEmpty(pic)) pic = str(m, "pic");
                // 兼容更多封面字段名（规则写法不统一）
                if (TextUtils.isEmpty(pic)) pic = str(m, "image");
                if (TextUtils.isEmpty(pic)) pic = str(m, "cover");
                if (TextUtils.isEmpty(pic)) pic = str(m, "thumbnail");
                if (TextUtils.isEmpty(pic)) pic = str(m, "poster");
                if (TextUtils.isEmpty(pic)) pic = str(m, "imgUrl");
                if (TextUtils.isEmpty(pic)) pic = str(m, "picUrl");
                item.setPic(pic);
                item.setDesc(str(m, "desc"));
                String colType = str(m, "col_type");
                if (TextUtils.isEmpty(colType)) colType = str(m, "colType");
                if (TextUtils.isEmpty(colType)) colType = str(m, "coltype");
                item.setColType(colType);
                // P1：补全官方条目字段 extra/content/line（updateItem/deleteItem/findItemsByCls 用）
                item.setContent(str(m, "content"));
                item.setLine(str(m, "line"));
                // 8.83 DetailPage 字段：findRule/group/pages/preRule/rule（Gson 直映，键名与原版一致）
                String findRule = str(m, "findRule");
                if (TextUtils.isEmpty(findRule)) findRule = str(m, "find_rule");
                item.setFindRule(findRule);
                item.setGroup(str(m, "group"));
                item.setPages(str(m, "pages"));
                String preRule = str(m, "preRule");
                if (TextUtils.isEmpty(preRule)) preRule = str(m, "pre_rule");
                item.setPreRule(preRule);
                item.setRule(str(m, "rule"));
                Object extraObj = m.get("extra");
                if (extraObj instanceof Map) {
                    Map<String, String> extra = new HashMap<>();
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) extraObj).entrySet()) {
                        extra.put(String.valueOf(e.getKey()),
                                e.getValue() == null ? "" : String.valueOf(e.getValue()));
                    }
                    item.setExtra(extra);
                } else {
                    // 兼容：id/cls 等顶层字段也并入 extra，保证按 id/url/cls 匹配可用
                    Map<String, String> extra = new HashMap<>();
                    for (String k : new String[]{"id", "cls", "pageTitle", "newWindow", "lineVisible", "textAlign"}) {
                        String v = str(m, k);
                        if (!v.isEmpty()) extra.put(k, v);
                    }
                    if (!extra.isEmpty()) item.setExtra(extra);
                }
                results.add(item);
            }
    }

    /**
     * 海阔首页专用：{@code setHomeResult(res)}，其中 {@code res = {data: [...]}}，
     * data 是条目数组。直接传数组的写法也兼容。
     */
    private void collectHomeResult(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) return;
        try {
            if (args[0] instanceof NativeArray) {
                collectResult(args);
                return;
            }
            String json = args[0] instanceof NativeObject
                    ? jsStringify(args[0])
                    : argStr(args[0]).trim();
            if (json.startsWith("{")) {
                Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> map = GSON.fromJson(json, mapType);
                Object data = map == null ? null : map.get("data");
                if (data instanceof List) {
                    collectResult(new Object[]{GSON.toJson(data)});
                    return;
                }
                Logger.t(TAG).d("setHomeResult: no data array");
                return;
            }
            collectResult(new Object[]{json});
        } catch (Throwable e) {
            Logger.t(TAG).d("collectHomeResult failed: %s", e.getMessage());
        }
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private List<HkItem> drainResults() {
        List<HkItem> out = new ArrayList<>(results);
        results.clear();
        return out;
    }

    /**
     * 搜索专用 drain：把收集到的 HkItem 按 8.83 callbackSearchResult 映射为
     * 独立 SearchResult（title/url/desc/img/content/extra，type 硬编码 "video"）。
     * 8.83 extra 语义：String 原样 / null 置 null / 其他 JSON.toJSONString。
     */
    private List<SearchResult> drainSearchResults() {
        List<SearchResult> out = new ArrayList<>(results.size());
        for (HkItem it : results) {
            if (it == null) continue;
            SearchResult sr = new SearchResult();
            sr.setTitle(nz(it.getTitle()));
            sr.setUrl(nz(it.getUrl()));
            sr.setDesc(nz(it.getDesc()));
            sr.setImg(nz(it.getPic()));
            sr.setContent(nz(it.getContent()));
            sr.setType("video");
            if (it.getExtra() != null && !it.getExtra().isEmpty()) {
                try {
                    sr.setExtra(GSON.toJson(it.getExtra()));
                } catch (Throwable ignored) {
                }
            }
            out.add(sr);
        }
        results.clear();
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 执行详情的 js: 规则，返回原始条目（含 line/col_type 等扩展字段，M3）。
     * setResult 项约定：title/url/pic_url/desc + 可选 line（线路名）/col_type。
     */
    public List<Map<String, String>> parseDetailRaw(String jsCode, String myUrl) throws Exception {
        return submit(() -> {
            rawResults.clear();
            results.clear();
            error = null;
            collectRaw = true;
            try {
                setContext(myUrl);
                evalJs(stripJsPrefix(jsCode));
            } catch (Throwable e) {
                // 详情页 JS 异常也要记录，供 V4 空态展示真实原因（之前只记 log，用户看到"0条线路"无法反馈）
                error = jsErrorMsg(e);
                throw e;
            } finally {
                collectRaw = false;
            }
            List<Map<String, String>> out = new ArrayList<>(rawResults);
            rawResults.clear();
            results.clear();
            return out;
        }).get();
    }

    private String stringifyArg(Object arg) {
        try {
            if (arg instanceof NativeObject) return jsStringify(arg);
        } catch (Throwable ignored) {
        }
        return String.valueOf(arg);
    }

    /**
     * storage0.get*：存的是 JSON 对象/数组则解析回 JS 对象返回，否则原样返回字符串。
     * 缺省值（第 2 参数）有则原样返回，不做解析。
     */
    private Object storage0Get(java.util.Map<String, String> map, Object[] args) {
        if (args == null || args.length == 0) return "";
        String v = map.get(argStr(args[0]));
        if (v == null) {
            if (args.length > 1 && args[1] != null) return args[1];
            return "";
        }
        String t = v.trim();
        if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
            try {
                return parseJson(v);
            } catch (Throwable ignored) {
            }
        }
        return v;
    }

    /**
     * N1: 8.83 的 request/post 默认补移动端 UA（JSEngine.smali:22856/21353），裸 fetch 不补。
     * 若 options 的 headers 里已有 User-Agent（不区分大小写）则不覆盖。
     */
    private static String injectMobileUa(String optionsJson) {
        try {
            com.google.gson.JsonObject obj;
            if (TextUtils.isEmpty(optionsJson)) {
                obj = new com.google.gson.JsonObject();
            } else {
                try {
                    obj = com.google.gson.JsonParser.parseString(optionsJson).getAsJsonObject();
                } catch (Throwable t) {
                    return optionsJson;
                }
            }
            com.google.gson.JsonObject headers = null;
            if (obj.has("headers") && obj.get("headers").isJsonObject()) {
                headers = obj.getAsJsonObject("headers");
            }
            boolean hasUa = false;
            if (headers != null) {
                for (String k : headers.keySet()) {
                    if ("User-Agent".equalsIgnoreCase(k)) {
                        hasUa = true;
                        break;
                    }
                }
            }
            if (!hasUa) {
                if (headers == null) {
                    headers = new com.google.gson.JsonObject();
                    obj.add("headers", headers);
                }
                headers.addProperty("User-Agent", UA_MOBILE);
            }
            return GSON.toJson(obj);
        } catch (Throwable e) {
            return optionsJson;
        }
    }

    private static String mergeMethod(String optionsJson, String method) {
        try {
            // N2: 8.83 用 Map<String,Object>（HttpHelper.smali:2790），保留嵌套的 body/headers 对象。
            // 之前用 Map<String,String> 中转，嵌套对象被 safeString 置空，导致 POST 发空 body（摸鱼日报根因）。
            com.google.gson.JsonObject obj;
            try {
                obj = com.google.gson.JsonParser.parseString(optionsJson).getAsJsonObject();
            } catch (Throwable t) {
                obj = new com.google.gson.JsonObject();
            }
            obj.addProperty("method", method);
            return GSON.toJson(obj);
        } catch (Throwable e) {
            return "{\"method\":\"" + method + "\"}";
        }
    }

    /**
     * 同步 fetch（跑在 JS 单线程上，直接阻塞等待）。
     * options 为 JSON：{headers, body/data, method, timeout, withHeaders}。
     */
    private String fetchSync(String url, String optionsJson) {
        if (TextUtils.isEmpty(url)) return "";
        // hiker://empty 等非 http 协议直接返回空（由上层按形态分流）
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "";
        // 8.83 原版：fetch 不走 generateRequestOptions，不自动补 UA（smali 实证）。
        // 之前 mergeMobileUa 是偏离 8.83 的行为，已移除。
        try {
            Req req = TextUtils.isEmpty(optionsJson) ? Req.objectFrom("{}") : Req.objectFrom(optionsJson);
            String opt = optionsJson == null ? "" : optionsJson;
            boolean withHeaders = opt.contains("\"withHeaders\":true") || opt.contains("\"withHeaders\":1");
            // P2：fetch 选项 withStatusCode / toHex / onlyHeaders
            boolean withStatusCode = opt.contains("\"withStatusCode\":true") || opt.contains("\"withStatusCode\":1");
            boolean toHex = opt.contains("\"toHex\":true") || opt.contains("\"toHex\":1");
            boolean onlyHeaders = opt.contains("\"onlyHeaders\":true") || opt.contains("\"onlyHeaders\":1");
            try (Response res = HkHttp.to(url, req).execute()) {
                resCode = res.code();
                lastUrl = url;
                Map<String, String> headers = new HashMap<>();
                for (String name : res.headers().names()) headers.put(name, res.header(name, ""));
                if (onlyHeaders) {
                    Map<String, Object> out = new HashMap<>();
                    out.put("headers", headers);
                    out.put("code", res.code());
                    return GSON.toJson(out);
                }
                ResponseBody body = res.body();
                byte[] bytes = body == null ? new byte[0] : body.bytes();
                String content;
                if (toHex) {
                    StringBuilder sb = new StringBuilder(bytes.length * 2);
                    for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
                    content = sb.toString();
                } else {
                    // N4: 8.83 的 rejectCoding 是请求侧标志（POST 时 body 原样发送，跳过编码处理，
                    // 见 HkHttpHelper.buildPostBody），不是响应侧。响应侧一律按 charset 解码，
                    // 8.83 没有 ISO-8859-1 字节映射行为。
                    content = new String(bytes, req.getCharset());
                }
                if (withStatusCode) {
                    Map<String, Object> out = new HashMap<>();
                    out.put("body", content);
                    out.put("code", res.code());
                    out.put("headers", headers);
                    return GSON.toJson(out);
                }
                if (!withHeaders) return content;
                Map<String, Object> out = new HashMap<>();
                out.put("body", content);
                out.put("headers", headers);
                out.put("code", res.code());
                return GSON.toJson(out);
            }
        } catch (Throwable e) {
            Logger.t(TAG).d("fetch failed %s: %s", url, e.getMessage());
            resCode = 0;
            return "";
        }
    }

    // ================= setItem/getItem 持久化 =================

    private File kvFile() {
        File dir = HkRuleManager.get().getDataDir(rule.getTitle());
        return new File(dir, "kv.json");
    }

    private void loadKv() {
        try {
            File f = kvFile();
            if (!f.exists()) return;
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> map = GSON.fromJson(new String(bytes, "UTF-8"), type);
            if (map != null) kv.putAll(map);
        } catch (Throwable ignored) {
        }
    }

    private void saveKv() {
        try {
            File f = kvFile();
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), GSON.toJson(kv).getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
    }
// ============ D1: 8.83 JSEngine P0 core port ============
// 8.83 JSEngine.smali P0 (D1): eval core / script wrap / require / parse entries /
// network bottom + infra helpers. Faithful smali->Java ports; substitutions documented
// in workspace/jsengine-d1/*_notes.md. New methods have no live callers yet (D3 wires them).

    // ---- D1 nested types (8.83 JSEngine$*) ----
    /** 8.83 JSEngine$ReturnType (enum, $values order). */
    public enum ReturnType { VOID, STRING, JSON, BOOL, Num, OBJECT }

    /** 8.83 JSEngine$JSAnnotation. */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
    public @interface JSAnnotation {
        String alias() default "";
        ReturnType returnType() default ReturnType.VOID;
    }

    /** 8.83 JSEngine$Parameter. */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.PARAMETER)
    public @interface Parameter {
        int defaultInt() default -1;
        String defaultValue() default "";
        String value() default "";
    }

    /** 8.83 JSEngine$OnFindCallBack mirror. */
    private interface JSEngineCallback<T> {
        void onSuccess(String key, T result);
        void onUpdate(String key, String value);
        void showErr(String err);
    }

    /** 8.83 NoSecurityController (rhino/NoSecurityController.smali, 42 lines). */
    private static class HkNoSecurityController extends SecurityController {
        @Override
        public GeneratedClassLoader createClassLoader(ClassLoader parent, Object securityDomain) {
            return new GeneratedClassLoader() {
                private final HkDefiningLoader loader = new HkDefiningLoader(parent);
                @Override public Class<?> defineClass(String name, byte[] data) {
                    return loader.define(name, data);
                }
                @Override public void linkClass(Class<?> cl) {
                    loader.link(cl);
                }
            };
        }
        @Override public Object getDynamicSecurityDomain(Object securityDomain) { return null; }
    }

    /**
     * ClassLoader 桥：Rhino 的 GeneratedClassLoader 是 interface，没有可 super
     * 调用的 defineClass(String, byte[], int, int)，故用具名 ClassLoader 子类
     * 承载真正的 defineClass/resolveClass。
     */
    private static class HkDefiningLoader extends ClassLoader {
        HkDefiningLoader(ClassLoader parent) { super(parent); }
        Class<?> define(String name, byte[] data) { return super.defineClass(name, data, 0, data.length); }
        void link(Class<?> cl) { super.resolveClass(cl); }
    }

    /** 8.83 JSEngine$MyRequire: Rhino Require with function name "requirejs". */
    private static class MyRequire extends org.mozilla.javascript.commonjs.module.Require {
        MyRequire(Context cx, Scriptable scope,
                  org.mozilla.javascript.commonjs.module.ModuleScriptProvider provider,
                  Script preExec, Script postExec, boolean sandboxed) {
            super(cx, scope, provider, preExec, postExec, sandboxed);
        }
        @Override
        public String getFunctionName() {
            return "requirejs";
        }
    }

    /** Minimal page-rule model for hiker://page/ JSON (GSON). 8.83: ArticleListPageRule. */
    private static class HkPageRule {
        String rule;
        String getRule() { return rule; }
    }

    /** 8.83 RequireDescription model (GSON). */
    private static class RequireDescription {
        String url; int version;
        RequireDescription(String url, int version) { this.url = url; this.version = version; }
        int getVersion() { return version; }
    }

    /** 8.83 RequireItem model (GSON). */
    private static class RequireItem {
        String url, proxy, file; long accessTime;
        RequireItem(String url, String proxy, String file, long accessTime) {
            this.url = url; this.proxy = proxy; this.file = file; this.accessTime = accessTime;
        }
        String getUrl() { return url; }
        long getAccessTime() { return accessTime; }
        void setAccessTime(long t) { accessTime = t; }
        void setProxy(String p) { proxy = p; }
    }


    // ---- D1 fields (8.83 JSEngine state) ----
    private final Map<String, Object> globalMap = new HashMap<>();
    private final HkRc4 rc4 = new HkRc4();
    private final HkMyBase64 myBase64 = new HkMyBase64();
    private final Map<String, JSEngineCallback<?>> callbackMap = new HashMap<>();
    private final Map<String, String> resCodeMap = new HashMap<>();
    private final List<String> methodList = new ArrayList<>();
    private volatile StrongCachingModuleScriptProvider cachingModuleScriptProvider;
    private String allFunctions = "";
    private final Class<?> clazz = HkJsRuntime.class;
    private static final Map<String, String> ruleTicketMap = new HashMap<>();
    private static final Map<String, String> ticketMap = new HashMap<>();


    // ---- D1 small helpers (merger-written) ----
    /** 8.83 RhinoAndroidHelper.enterContext() equivalent: NoSecurityController + Context.enter(). */
    private static Context enterRhino() {
        if (!SecurityController.hasGlobal()) {
            SecurityController.initGlobal(new HkNoSecurityController());
        }
        return Context.enter();
    }

    /** 8.83 UriUtils.getRootDir(ctx): getExternalFilesDir(DOCUMENTS), mkdirs. */
    private static String hkRootDir() {
        File docs = App.get().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (docs != null && !docs.exists()) docs.mkdirs();
        return docs != null ? docs.getAbsolutePath() : "";
    }

    /** 8.83 JSEngine.getFilesDir(): rootDir + "/files" (smali 3563-3621). */
    private String getFilesDir() {
        return hkRootDir() + File.separator + "files";
    }

    /** 8.83 UUIDUtil.genUUID(): dashed-stripped UUID (verified in smali). */
    private static String genUuid() {
        return UUID.randomUUID().toString().replaceAll("-", "");
    }

    /** 8.83 JSEngine.generateTicket (smali 2998-3045): title -> stable ticket uuid. */
    private static String generateTicket(String title) {
        if (TextUtils.isEmpty(title)) return title;
        String t = ruleTicketMap.get(title);
        if (!TextUtils.isEmpty(t)) return t;
        String uuid = genUuid();
        ticketMap.put(uuid, title);
        ruleTicketMap.put(title, uuid);
        return uuid;
    }

    /** 8.83 JSEngine.log(Object, Object) JS API (D3 ports full version; D1: log it). */
    private void log(Object msg, Object tag) {
        Logger.t(TAG).d(String.valueOf(msg));
    }

    /** 8.83 4-arg setError (smali 24004, 530 lines). D2: 完整实现，委托 setErrorImpl。 */
    private void setError(Object o, Object callbackKey, Object ruleKey, Object extra) {
        setErrorImpl(new Object[]{o, callbackKey, ruleKey, extra});
    }

    private static InputStream hkFileToInputStream(String path) throws java.io.IOException {
        return new java.io.FileInputStream(path);
    }

    private static byte[] hkFileToBytes(String path) throws java.io.IOException {
        java.io.File f = new java.io.File(path);
        byte[] b = new byte[(int) f.length()];
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
        }
        return b;
    }

    /** hiker://assets/ -> app assets (8.83 HikerRuleUtil.getAssetsFileByHiker; D1 mapping). */
    private String hkGetAssetsFile(String url) {
        try {
            String p = url.substring("hiker://assets/".length());
            return loadAsset(p);
        } catch (Exception e) {
            return "";
        }
    }

    /** hiker:// rule URLs (8.83 HikerRuleUtil.getRulesByHiker)：hiker://home@<规则标题> → 按标题查规则库返回规则 JSON。 */
    private String hkGetRulesByHiker(String url) {
        try {
            if (TextUtils.isEmpty(url) || !url.startsWith("hiker://")) return "";
            String p0 = url.replace("hiker://", "");
            if (p0.startsWith("home")) {
                // 8.83: split("@") 取 parts[1] 为标题查库；宿主用 HkRuleManager.loadRuleJson 同款语义
                String[] parts = p0.split("@");
                if (parts.length > 1) {
                    String json = HkRuleManager.get().loadRuleJson(parts[1]);
                    return json == null ? "" : json;
                }
                return "";
            }
            // 8.83 其余分支（bookmark 等）为 D3 范畴，暂返回 ""
            return "";
        } catch (Exception e) {
            Logger.t(TAG).e(e, "hkGetRulesByHiker");
            return "";
        }
    }


    // ---- D1-A: eval core (smali 13914-15069, 26009-26128) ----
    // (ReturnType/JSAnnotation/Parameter defined once as nested types above.)

// 1. evalJS(String, InputStream) — smali lines 13914-14288.
// Runs the script in a FRESH Rhino context/scope, injecting input/_globalMap/rc4/
// _base64/javaContext/javaLoader plus the allFunctions prelude; returns the eval
// result as InputStream (unwrapping NativeJavaObject), closing the passed-in
// stream when the result is a different InputStream. Any Exception -> Context.exit
// and return the original stream; any Throwable -> Context.exit and rethrow.
public InputStream evalJS(String p1, InputStream p2) {
    String v1;
    if (p1.contains("let my_rule = '")) {
        v1 = "";
    } else {
        v1 = getMyRule(null); // MISSING (8.83 static)
    }
    String script = new StringBuilder()
            .append(v1)
            .append(getMyType("eval")) // MISSING
            .append(getMyCallbackKey(genUuid())) // MISSING; 8.83 external
            .append(p1)
            .toString();
    String v3 = getReplaceJS(appendLineVar(script)); // MISSING, MISSING
    Context v1cx = Context.enter(); // 8.83 RhinoAndroidHelper 等价：Context.enter()
    initRhino(v1cx); // MISSING
    try {
        ScriptableObject v2 = v1cx.initStandardObjects();
        org.mozilla.javascript.ImporterTopLevel.init(v1cx, v2, false);
        initRequire(v1cx, v2); // MISSING
        ScriptableObject.putProperty(v2, "input", Context.javaToJS(p2, v2));
        ScriptableObject.putProperty(v2, "_globalMap", Context.javaToJS(globalMap, v2)); // field MISSING
        ScriptableObject.putProperty(v2, "globalMap0", Context.javaToJS(globalMap, v2)); // 8.83 _globalMap 别名（skill 文档名）
        ScriptableObject.putProperty(v2, "rc4", Context.javaToJS(rc4, v2)); // field MISSING
        ScriptableObject.putProperty(v2, "_base64", Context.javaToJS(myBase64, v2)); // field MISSING
        ScriptableObject.putProperty(v2, "javaContext", Context.javaToJS(this, v2));
        ScriptableObject.putProperty(v2, "javaLoader", Context.javaToJS(clazz.getClassLoader(), v2)); // field MISSING
        if (!v3.contains("eval(getJsPlugin());\n")) {
            v1cx.evaluateString(v2, allFunctions + "\neval(getJsPlugin());\n", clazz.getSimpleName(), 1, null); // field MISSING
        } else {
            v1cx.evaluateString(v2, allFunctions, clazz.getSimpleName(), 1, null);
        }
        Object p1res = v1cx.evaluateString(v2, v3, clazz.getSimpleName(), 1, null);
        if (p1res instanceof InputStream) {
            if (p1res != p2 && p2 != null) {
                try {
                    p2.close();
                } catch (Exception e) {
                    Logger.t(TAG).e(e.getMessage()); // 8.83 external; target uses Logger
                }
            }
            InputStream result = (InputStream) p1res;
            Context.exit();
            return result;
        }
        if (p1res instanceof NativeJavaObject) {
            p1res = ((NativeJavaObject) p1res).unwrap();
            if (p1res instanceof InputStream && p1res != p2 && p2 != null) {
                try {
                    p2.close();
                } catch (Exception e) {
                    Logger.t(TAG).e(e.getMessage()); // 8.83 external; target uses Logger
                }
            }
            if (p1res instanceof InputStream) {
                p2 = (InputStream) p1res;
            }
            Context.exit();
            return p2;
        }
        Context.exit();
        return p2;
    } catch (Exception e) {
        Context.exit();
        return p2;
    } catch (Throwable t) {
        // smali :catchall_0 — exit then rethrow (NOT a finally: normal paths exit explicitly)
        Context.exit();
        throw t;
    }
}

// 2. evalJS(String, String) — smali lines 14289-14301. Delegates with decode=true.
public String evalJS(String p1, String p2) {
    return evalJS(p1, p2, true);
}

// 3. evalJS(String, String, boolean) — smali lines 14302-14735.
// Same fresh-context shape as (1) but injects `var input=...` (getMyInput) instead of
// the "input" property; when p3 is true the script is passed through
// StringUtil.decodeConflictStr. Result mapping: String -> itself;
// Undefined -> "undefined"; NativeObject/NativeArray -> argsNativeObjectAdjust +
// JSON stringify; anything else -> String.valueOf. Exception -> "error:"+message;
// Throwable -> Context.exit and rethrow.
public String evalJS(String p1, String p2, boolean p3) {
    String errPrefix = "error:";
    String empty = "";
    String v4;
    if (p1.contains("let my_rule = '")) {
        v4 = empty;
    } else {
        v4 = getMyRule(null); // MISSING (8.83 static)
    }
    String v4b = new StringBuilder()
            .append(getMyInput(p2)) // MISSING
            .append(v4)
            .append(getMyType("eval")) // MISSING
            .append(getMyCallbackKey(genUuid())) // MISSING; 8.83 external
            .toString();
    String v0;
    if (p3) {
        v0 = v4b + decodeConflictStr(p1); // 8.83 external
    } else {
        v0 = v4b + p1;
    }
    String v8 = getReplaceJS(appendLineVar(v0)); // MISSING, MISSING
    Context v6 = Context.enter(); // 8.83 RhinoAndroidHelper 等价：Context.enter()
    initRhino(v6); // MISSING
    try {
        ScriptableObject v7 = v6.initStandardObjects();
        org.mozilla.javascript.ImporterTopLevel.init(v6, v7, false);
        initRequire(v6, v7); // MISSING
        ScriptableObject.putProperty(v7, "_globalMap", Context.javaToJS(globalMap, v7)); // field MISSING
        ScriptableObject.putProperty(v7, "globalMap0", Context.javaToJS(globalMap, v7)); // 8.83 _globalMap 别名
        ScriptableObject.putProperty(v7, "rc4", Context.javaToJS(rc4, v7)); // field MISSING
        ScriptableObject.putProperty(v7, "_base64", Context.javaToJS(myBase64, v7)); // field MISSING
        ScriptableObject.putProperty(v7, "javaContext", Context.javaToJS(this, v7));
        ScriptableObject.putProperty(v7, "javaLoader", Context.javaToJS(clazz.getClassLoader(), v7)); // field MISSING
        if (!v8.contains("eval(getJsPlugin());\n")) {
            v6.evaluateString(v7, allFunctions + "\neval(getJsPlugin());\n", clazz.getSimpleName(), 1, null); // field MISSING
        } else {
            v6.evaluateString(v7, allFunctions, clazz.getSimpleName(), 1, null);
        }
        Object v0res = v6.evaluateString(v7, v8, clazz.getSimpleName(), 1, null);
        if (v0res instanceof String) {
            Context.exit();
            return (String) v0res;
        }
        if (v0res instanceof Undefined) {
            Context.exit();
            return "undefined";
        }
        if (v0res instanceof NativeObject || v0res instanceof NativeArray) {
            Object adjusted = argsNativeObjectAdjust(v0res); // exists in HkJsRuntime
            // 8.83: GSON.toJson(adjusted); fastjson absent in
            // target — argsNativeObjectAdjust yields LinkedHashMap/ArrayList, so GSON is equivalent.
            String json = GSON.toJson(adjusted);
            Context.exit();
            return json;
        }
        String s = new StringBuilder().append(v0res).append(empty).toString();
        Context.exit();
        return s;
    } catch (Exception e) {
        String err = errPrefix + e.getMessage();
        Context.exit();
        return err;
    } catch (Throwable t) {
        // smali :catchall_0 — exit then rethrow (NOT a finally: normal paths exit explicitly)
        Context.exit();
        throw t;
    }
}

// 4. evalJSForObj(String, Object) — smali lines 14736-15032.
// Same fresh-context shape as (1) ("input" property injected, NOT getMyInput).
// Returns the raw eval result after argsNativeObjectAdjust (+ NativeJavaObject
// unwrap); any Exception -> Context.exit and return null; Throwable -> exit, rethrow.
public Object evalJSForObj(String p1, Object p2) {
    String v1;
    if (p1.contains("let my_rule = '")) {
        v1 = "";
    } else {
        v1 = getMyRule(null); // MISSING (8.83 static)
    }
    String script = new StringBuilder()
            .append(v1)
            .append(getMyType("eval")) // MISSING
            .append(getMyCallbackKey(genUuid())) // MISSING; 8.83 external
            .append(p1)
            .toString();
    String v5 = getReplaceJS(appendLineVar(script)); // MISSING, MISSING
    Context v3 = Context.enter(); // 8.83 RhinoAndroidHelper 等价：Context.enter()
    initRhino(v3); // MISSING
    try {
        ScriptableObject v4 = v3.initStandardObjects();
        org.mozilla.javascript.ImporterTopLevel.init(v3, v4, false);
        initRequire(v3, v4); // MISSING
        ScriptableObject.putProperty(v4, "input", Context.javaToJS(p2, v4));
        ScriptableObject.putProperty(v4, "_globalMap", Context.javaToJS(globalMap, v4)); // field MISSING
        ScriptableObject.putProperty(v4, "globalMap0", Context.javaToJS(globalMap, v4)); // 8.83 _globalMap 别名
        ScriptableObject.putProperty(v4, "rc4", Context.javaToJS(rc4, v4)); // field MISSING
        ScriptableObject.putProperty(v4, "_base64", Context.javaToJS(myBase64, v4)); // field MISSING
        ScriptableObject.putProperty(v4, "javaContext", Context.javaToJS(this, v4));
        ScriptableObject.putProperty(v4, "javaLoader", Context.javaToJS(clazz.getClassLoader(), v4)); // field MISSING
        if (!v5.contains("eval(getJsPlugin());\n")) {
            v3.evaluateString(v4, allFunctions + "\neval(getJsPlugin());\n", clazz.getSimpleName(), 1, null); // field MISSING
        } else {
            v3.evaluateString(v4, allFunctions, clazz.getSimpleName(), 1, null);
        }
        Object p1res = v3.evaluateString(v4, v5, clazz.getSimpleName(), 1, null);
        p1res = argsNativeObjectAdjust(p1res); // exists in HkJsRuntime
        if (p1res instanceof NativeJavaObject) {
            p1res = ((NativeJavaObject) p1res).unwrap();
        }
        Context.exit();
        return p1res;
    } catch (Exception e) {
        Context.exit();
        return null;
    } catch (Throwable t) {
        // smali :catchall_0 — exit then rethrow (NOT a finally: normal paths exit explicitly)
        Context.exit();
        throw t;
    }
}

// 5. evalLazyRule(Object, String, String) — smali lines 15033-15069.
// Builds getMyRule(p1) + decodeConflictStr(p2), then evalJS(script, p3, false)
// (decode=false: the lazy rule was already decoded).
public String evalLazyRule(Object p1, String p2, String p3) {
    String script = new StringBuilder()
            .append(getMyRule(p1)) // MISSING (8.83 static)
            .append(decodeConflictStr(p2)) // 8.83 external
            .toString();
    return evalJS(script, p3, false);
}

// 6. syncExecute(Object) — smali lines 26009-26128, declared-synchronized.
// JS-bridge entry (8.83 @JSAnnotation(returnType=OBJECT), @Parameter("task")):
// if the arg is null/undefined -> null; else argsNativeObjectAdjust(arg, true)
// (8.83 casts to fastjson JSONObject — adapted to java.util.Map since the target's
// adjuster returns LinkedHashMap). Takes "func" (BaseFunction) and "param" from the
// map and invokes func.call(currentContext, func.getParentScope(), func, [param]).
@JSAnnotation(returnType = ReturnType.OBJECT)
public synchronized Object syncExecute(@Parameter("task") Object task) {
    Object p1 = task;
    if (p1 != null) {
        if (isUndefined(p1)) { // exists in HkJsRuntime; matches 8.83 smali 5714-5738
            p1 = null;
        } else {
            // 8.83: check-cast to com.alibaba.fastjson.JSONObject (fastjson absent in
            // target); the target's two-arg adjuster returns LinkedHashMap, so Map is equivalent.
            p1 = (java.util.Map<?, ?>) argsNativeObjectAdjust(p1, true); // two-arg exists in HkJsRuntime
        }
    }
    java.util.Map<?, ?> jo = (java.util.Map<?, ?>) p1; // null-safe cast, mirrors smali register flow
    BaseFunction v1;
    if (jo != null && jo.containsKey("func")) {
        v1 = (BaseFunction) jo.get("func");
    } else {
        v1 = null;
    }
    Object v0 = null;
    if (jo != null) {
        v0 = jo.get("param");
    }
    Scriptable parentScope = v1.getParentScope();
    Context v2 = Context.getCurrentContext();
    return v1.call(v2, parentScope, v1, new Object[]{v0});
}


    // ---- D1-B: script wrap (smali 3622-4574, 9142-9479, 9963-9991) ----


// ---- methods ----------------------------------------------------------------

/**
 * Port of JSEngine.getFunctionStr(String, JSEngine$ReturnType, String, Method).
 * Smali lines 3622-4574. Builds the JS injection template for one ScriptAPI
 * method: " var method_<name> = ScriptAPI.getMethod(\"<name>\"[,types]);" plus
 * " function <name>(param0,...){ ... }", honouring @Parameter annotations
 * (ruleTitleKey/evalKey/dirKey/typeKey/urlKey/ruleKey/callbackKey, plus
 * defaultValue/defaultInt fallbacks). All const-string literals reproduced
 * character-for-character from the smali.
 */
private String getFunctionStr(String prefix, ReturnType type, String alias, java.lang.reflect.Method method) {
    String name = method.getName();
    Class<?>[] paramTypes = method.getParameterTypes();
    String types;
    String invokeArgs;
    String paramNames;
    if (paramTypes.length > 0) {
        String[] typeNames = new String[paramTypes.length];
        String[] names = new String[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            typeNames[i] = paramTypes[i].getName();
            names[i] = "param" + i;
        }
        types = String.format(",[%s]", TextUtils.join(",", typeNames));
        paramNames = TextUtils.join(",", names);
        invokeArgs = "," + paramNames;
    } else {
        types = "";
        invokeArgs = "";
        paramNames = "";
    }
    StringBuilder sb = new StringBuilder();
    java.lang.annotation.Annotation[][] paramAnnotations = method.getParameterAnnotations();
    for (int i = 0; i < paramAnnotations.length; i++) {
        for (java.lang.annotation.Annotation annotation : paramAnnotations[i]) {
            if (Parameter.class.equals(annotation.annotationType())) {
                Parameter param = (Parameter) annotation;
                String value = param.value();
                int which = -1;
                switch (value) {
                    case "ruleTitleKey":
                        which = 0;
                        break;
                    case "evalKey":
                        which = 1;
                        break;
                    case "dirKey":
                        which = 2;
                        break;
                    case "typeKey":
                        which = 3;
                        break;
                    case "urlKey":
                        which = 4;
                        break;
                    case "ruleKey":
                        which = 5;
                        break;
                    case "callbackKey":
                        which = 6;
                        break;
                }
                switch (which) {
                    case 0:
                        sb.append("param").append(i).append(" = MY_TICKET;\n");
                        break;
                    case 1:
                        sb.append("param").append(i).append(" = eval;\n");
                        break;
                    case 2:
                        sb.append("param").append(i).append(" = param").append(i).append(" || MY_DIR;\n");
                        break;
                    case 3:
                        sb.append("param").append(i).append(" = MY_TYPE;\n");
                        break;
                    case 4:
                        sb.append("param").append(i).append(" = param").append(i).append(" || MY_URL;\n");
                        break;
                    case 5:
                        sb.append("param").append(i).append(" = MY_RULE;\n");
                        break;
                    case 6:
                        sb.append("param").append(i).append(" = CALLBACK_KEY;\n");
                        break;
                }
                String defaultValue = param.defaultValue();
                if (!TextUtils.isEmpty(defaultValue)) {
                    sb.append("param").append(i).append(" = param").append(i)
                            .append(" || \"").append(escapeJsString(defaultValue)).append("\";\n");
                }
                int defaultInt = param.defaultInt();
                if (defaultInt >= 0) {
                    sb.append("param").append(i).append(" = param").append(i)
                            .append(" != null ? param").append(i).append(" : ").append(defaultInt).append(";\n");
                }
            }
        }
    }
    String retPrefix = method.getReturnType().getSimpleName().equals("void") ? "" : "return";
    String methodDecl = String.format(" var method_%s = ScriptAPI.getMethod(\"%s\"%s);\n", name, name, types);
    String functionStr;
    if (type == ReturnType.STRING) {
        functionStr = String.format(new StringBuilder(" function %s(%s){\n")
                .append(sb.toString())
                .append("    var retStr = method_%s.invoke(javaContext%s);\n"
                        + "    try {\n"
                        + "       if(retStr instanceof java.io.InputStream) return retStr;\n"
                        + "    } catch(e){}\n"
                        + "    return retStr == null ? retStr : retStr + '';\n"
                        + " }\n")
                .toString(), name, paramNames, name, invokeArgs);
    } else if (type == ReturnType.JSON) {
        functionStr = String.format(new StringBuilder(" function %s(%s){\n")
                .append(sb.toString())
                .append("    var retStr = method_%s.invoke(javaContext%s);\n"
                        + "    return JSON.parse(retStr);\n"
                        + " }\n")
                .toString(), name, paramNames, name, invokeArgs);
    } else if (type == ReturnType.OBJECT) {
        functionStr = String.format(new StringBuilder(" function %s(%s){\n")
                .append(sb.toString())
                .append("    return method_%s.invoke(javaContext%s);\n"
                        + " }\n")
                .toString(), name, paramNames, name, invokeArgs);
    } else if (type == ReturnType.BOOL) {
        functionStr = String.format(new StringBuilder(" function %s(%s){\n")
                .append(sb.toString())
                .append("    var retStr = method_%s.invoke(javaContext%s);\n"
                        + "    return retStr + '' == 'true';\n"
                        + " }\n")
                .toString(), name, paramNames, name, invokeArgs);
    } else if (type == ReturnType.Num) {
        functionStr = String.format(new StringBuilder(" function %s(%s){\n")
                .append(sb.toString())
                .append("    var retStr = method_%s.invoke(javaContext%s);\n"
                        + "    return parseInt(retStr);\n"
                        + " }\n")
                .toString(), name, paramNames, name, invokeArgs);
    } else {
        functionStr = String.format(new StringBuilder(" function %s(%s){\n")
                .append(sb.toString())
                .append("    %s method_%s.invoke(javaContext%s);\n"
                        + " }\n")
                .toString(), name, paramNames, retPrefix, name, invokeArgs);
    }
    String result = prefix + methodDecl + functionStr;
    methodList.add(name);
    if (!TextUtils.isEmpty(alias)) {
        result += String.format("\nvar %s = %s;", alias, name);
        methodList.add(alias);
    }
    return result;
}

/**
 * Port of JSEngine.runScript(String, String). Smali lines 9142-9152.
 * Delegates to the 3-arg overload with throwOnError=false.
 */
private void runScript(String script, String key) {
    runScript(script, key, false);
}

/**
 * Port of JSEngine.runScript(String, String, boolean). Smali lines 9153-9479.
 * Builds the final script via appendLineVar + getReplaceJS, enters a fresh
 * Rhino context (RhinoAndroidHelper), installs the standard 8.83 scope
 * bindings (_globalMap, rc4, _base64, javaContext, javaLoader), evaluates
 * allFunctions (+ "\neval(getJsPlugin());\n" when the script lacks the
 * "eval(getJsPlugin());\n" marker) and then the script itself. On Exception:
 * setError("JS编译出错：" + msg, key, new JSONObject()) unless throwOnError,
 * in which case the exception is rethrown. Context.exit() always runs
 * (smali allcatch -> finally). Afterwards resCodeMap/callbackMap entries for
 * key are removed (each path has its own try/catch -> Timber.e, faithfully
 * collapsed here into the single straight-line cleanup).
 */
private void runScript(String script, String key, boolean throwOnError) {
    String replaced = getReplaceJS(appendLineVar(script), throwOnError);
    Context cx = Context.enter(); // 8.83 RhinoAndroidHelper 等价：Context.enter()
    initRhino(cx);
    try {
        ScriptableObject scope = cx.initStandardObjects();
        org.mozilla.javascript.ImporterTopLevel.init(cx, scope, false);
        initRequire(cx, scope);
        ScriptableObject.putProperty(scope, "_globalMap",
                Context.javaToJS(globalMap, scope));
        ScriptableObject.putProperty(scope, "globalMap0",
                Context.javaToJS(globalMap, scope)); // 8.83 _globalMap 别名（skill 文档名）
        ScriptableObject.putProperty(scope, "rc4",
                Context.javaToJS(rc4, scope));
        ScriptableObject.putProperty(scope, "_base64",
                Context.javaToJS(myBase64, scope));
        ScriptableObject.putProperty(scope, "javaContext",
                Context.javaToJS(this, scope));
        ScriptableObject.putProperty(scope, "javaLoader",
                Context.javaToJS(clazz.getClassLoader(), scope));
        if (!script.contains("eval(getJsPlugin());\n")) {
            cx.evaluateString(scope, allFunctions + "\neval(getJsPlugin());\n",
                    clazz.getSimpleName(), 1, null);
        } else {
            cx.evaluateString(scope, allFunctions, clazz.getSimpleName(), 1, null);
        }
        cx.evaluateString(scope, replaced, clazz.getSimpleName(), 1, null);
    } catch (Exception e) {
        if (!throwOnError) {
            setError("JS编译出错：" + e.getMessage(), key, new HashMap<String, Object>());
        } else {
            throw e;
        }
    } finally {
        Context.exit();
    }
    try {
        if (!TextUtils.isEmpty(key)) {
            resCodeMap.remove(key);
            callbackMap.remove(key);
        }
    } catch (Exception e) {
        Logger.t(TAG).e(e.getMessage());
    }
}

/**
 * Port of JSEngine.wrapTryScript(String). Smali lines 9963-9991.
 * Returns "try {" + script + "}catch(err){}".
 */
private String wrapTryScript(String script) {
    return new StringBuilder("try {").append(script).append("}catch(err){}").toString();
}


    // ---- D1-C: require (smali 5400-5484, 8192-8237, 8468-9141) ----
    /**
     * 8.83 JSEngine.require0(Object p1, Object p2, int p3, Object p4,
     *                        Object p5, boolean p6, String p7, int p8) — smali 8468-9141.
     * $.require 的核心模块加载器。参数角色按寄存器用法：
     *   p1 options     — 传给 proxyRequire 的选项对象（可含 proxy 字段）
     *   p2 name        — 经 tryGetString 转字符串，作模块名/描述（updateDescription/generateRequireMap 用）
     *   p3 expireHours — 本地缓存过期小时数：<0 只比版本；==0 永远重下；>0 版本不够重下，否则按 3600000*p3 过期
     *   p4 fetchOpts   — 透传给 requestForCode 的请求选项对象
     *   p5 callback    — Function；p6=true 时把代码字符串作唯一实参回调，其 parentScope 作调用 scope
     *   p6 useCallback — true=回调模式（返回 Function.call 结果），false=返回代码字符串
     *   p7 ruleTitle   — 规则标题（hiker://page/ 查库 + generateRequireMap 用）
     *   p8 reqVersion  — 期望依赖版本号（与 <md5>.json 里存的版本比）
     */
    private Object require0(Object options, Object name, int expireHours, Object fetchOpts,
                            Object callback, boolean useCallback, String ruleTitle, int reqVersion) throws Exception {
        // smali: v1=p4(fetchOpts), v2=p7(ruleTitle), v3=tryGetString(p2), v4=p8(reqVersion)
        String desc = tryGetString(name);            // v3, .line 3626
        String url = proxyRequire(options, desc);    // v5, .line 3627

        // .line 3628: isNotEmpty(url) && (startsWith("file://") || startsWith("hiker://")) -> :cond_0
        if (!TextUtils.isEmpty(url) && (url.startsWith("file://") || url.startsWith("hiker://"))) {
            // :cond_0
            Object ruleObj = null;                   // v7
            if (url.startsWith("hiker://page/")) {
                // 8.83: LitePal.where("title = ?", ruleTitle).limit(1).findFirst(ArticleListRule.class)
                ruleObj = HkRuleManager.get().getInstalledRule(ruleTitle);
            }
            // :cond_1
            String code = (String) fetchByHiker(url, ruleObj, false, false);  // v2
            if (!useCallback) {
                return code;                         // :cond_4
            }
            if (url.startsWith("hiker://page/")) {
                // 8.83: com.alibaba.fastjson.JSON.parseObject(code, HkPageRule.class)
                HkPageRule pageRule = GSON.fromJson(code, HkPageRule.class);
                if (pageRule != null) {
                    String rule = pageRule.getRule();
                    if (!TextUtils.isEmpty(rule)) {
                        code = rule.startsWith("js:") ? rule.substring(3) : rule;  // v8=3
                    }
                }
            }
            // :goto_0 — Function.call(cx, parentScope, parentScope, new Object[]{code})
            Context cx = Context.getCurrentContext();
            Scriptable parentScope = ((Scriptable) callback).getParentScope();
            return ((Function) callback).call(cx, parentScope, parentScope, new Object[]{code});
        }

        // :cond_5 — 非 file/hiker 协议：必须 http 开头，否则抛
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) {
            // :cond_12
            throw new Exception("require地址必须为http地址：" + url);
        }

        // http(s) 分支：本地缓存 libs/<md5(url)>.js + 版本文件 <md5(url)>.json
        Context cx = Context.getCurrentContext();                          // v6
        Scriptable parentScope = ((Scriptable) callback).getParentScope(); // v9
        String md5 = Util.md5(url);                                        // v10, 8.83: StringUtil.md5
        String libsDir = hkRootDir() + File.separator + "libs";  // v12
        String jsPath = libsDir + File.separator + md5 + ".js";            // v11
        String jsonPath = libsDir + File.separator + md5 + ".json";        // v10
        File jsFile = new File(jsPath);                                    // v12
        int savedVer = getRequireVersion(jsonPath, reqVersion); // v13
        // v15 = "获取远程依赖失败："

        // 缓存有效性：smali 3667-3672 的 if-gez/if-ge/if-lt/if-eqz/cmp-long 判定链
        //   p3 < 0：只比版本（savedVer >= reqVersion 用本地，否则下载）
        //   p3 == 0：永远重新下载
        //   p3 > 0：版本不够直接下载；版本够则 age <= 3600000*p3 用本地，否则下载
        boolean useLocal;
        if (!jsFile.exists()) {
            useLocal = false;
        } else if (expireHours < 0) {
            useLocal = savedVer >= reqVersion;
        } else if (expireHours == 0) {
            useLocal = false;
        } else if (savedVer < reqVersion) {
            useLocal = false;
        } else {
            long age = System.currentTimeMillis() - jsFile.lastModified();
            useLocal = age <= 3600000L * expireHours;
        }

        String code;  // v1
        if (useLocal) {
            // :cond_b / :goto_1 — 缓存有效，直接读本地
            code = readFileContent(jsPath);          // 8.83: FileUtil.fileToString(jsPath)
        } else {
            code = requestForCode(url, fetchOpts);   // :cond_7 / :cond_f 下载
            if (!jsFile.exists()) {
                // :cond_f — 本地无文件：无内容校验，直接落盘
                if (TextUtils.isEmpty(code)) {
                    // :cond_11
                    throw new Exception("获取远程依赖失败：" + url);
                }
                writeFileContent(jsPath, code);      // 8.83: FileUtil.stringToFile(code, jsPath)
                updateDescription(jsonPath, desc, reqVersion);
            } else if (useCallback && !isJsCode(code)) {
                // :cond_7 回调模式：下载内容被判定为非 JS 代码 → 打日志后回退本地
                log("url: " + url + "获取的内容被判定为非JS代码", null);  // v7=null
                code = readFileContent(jsPath);      // goto :goto_1
            } else if (!useCallback && url.contains("hiker.nokia.press")
                    && (TextUtils.isEmpty(code) || code.length() < 3 || code.contains("非法猎鹰"))) {
                // :cond_8 — nokia.press 拦截页（空/过短/含"非法猎鹰"）→ 回退本地
                code = readFileContent(jsPath);      // goto :goto_1
            } else if (giteePoisoned(url, code)) {
                // :cond_9 + :cond_a — gitee 坏内容 → 回退本地
                code = readFileContent(jsPath);      // goto :goto_1
            } else if (TextUtils.isEmpty(code)) {
                // :cond_c — 空内容
                throw new Exception("获取远程依赖失败：" + url);
            } else {
                // :cond_c — 内容可用，落盘并更新版本描述
                writeFileContent(jsPath, code);      // 8.83: FileUtil.stringToFile(code, jsPath)
                updateDescription(jsonPath, desc, reqVersion);
            }
        }
        // :goto_2 公共收尾
        return finishRequire(ruleTitle, desc, url, jsPath, code, cx, parentScope, callback, useCallback);
    }

    /**
     * :cond_9 + :cond_a 的 gitee 内容校验（结构辅助方法，非 8.83 独立方法）：
     * url 含 gitee.com/ 且（内容空 / 长度<3 / 含 "The Repository is invisible"）→ true（回退本地）。
     * 不含 gitee.com/ → false（走 :cond_c）。
     */
    private boolean giteePoisoned(String url, String code) {
        if (!url.contains("gitee.com/")) return false;  // :cond_9 !contains -> :cond_a -> !contains -> :cond_c
        if (TextUtils.isEmpty(code)) return true;       // :cond_9 空 -> :goto_1
        if (code.length() < 3) return true;             // :cond_9 长度<3 -> :goto_1（v8=3）
        return code.contains("The Repository is invisible");  // :cond_a
    }

    /**
     * :goto_2 公共收尾（结构辅助方法，非 8.83 独立方法）：
     * generateRequireMap 登记模块；useCallback=true 时调 Function 回调并返回其结果，否则返回代码字符串。
     */
    private Object finishRequire(String ruleTitle, String desc, String url, String jsPath, String code,
                                 Context cx, Scriptable parentScope, Object callback, boolean useCallback) {
        generateRequireMap(ruleTitle, desc, url, jsPath);
        if (useCallback) {
            return ((Function) callback).call(cx, parentScope, parentScope, new Object[]{code});
        }
        return code;
    }

    /**
     * 8.83 JSEngine.proxyRequire(Object p1, String p2) — smali 8192-8237 (.line 3590-3596)。
     * p2 为空（null/""）直接返回 p2；否则把 p1 经 argsNativeObjectAdjust 转换，
     * 是 JSONObject（本移植：Map，见 argsNativeObjectAdjust 注释）则取 "proxy" 字段，
     * 走 proxyUrl(p2, proxy) 拼出代理地址返回；不是则原样返回 p2。
     */
    private String proxyRequire(Object options, String url) {
        if (url == null || url.isEmpty()) return url;   // :cond_1 / :goto_0
        // :cond_0
        Object adjusted = argsNativeObjectAdjust(options);  // .line 3593
        // 8.83: adjusted instanceof com.alibaba.fastjson.JSONObject；本移植已转 Map
        if (adjusted instanceof Map) {                  // .line 3594
            Object proxy = ((Map<?, ?>) adjusted).get("proxy");  // .line 3595
            return proxyUrl(url, proxy == null ? null : String.valueOf(proxy));  // .line 3596
        }
        return url;                                     // :cond_1
    }

    /**
     * 8.83 JSEngine.initRequire(Context p1, Scriptable p2) — smali 5400-5484 (.line 540-558)。
     * CommonJS requirejs 桥：StrongCachingModuleScriptProvider（UrlModuleSourceProvider 指向
     * getDirectory() 即 <rootDir>/libs 的 file:// URI）双重检查锁单例；
     * new MyRequire(cx, scope, provider, null, null, false) 挂到 scope.requirejs。
     * 8.83 锁的是 JSEngine.class，本移植锁 HkJsRuntime.class（移植类）。
     */
    private void initRequire(Context cx, Scriptable scope) {
        if (cachingModuleScriptProvider == null) {      // .line 540
            synchronized (HkJsRuntime.class) {          // .line 541, 8.83: synchronized (JSEngine.class)
                if (cachingModuleScriptProvider == null) {  // .line 542
                    // .line 543-546
                    UrlModuleSourceProvider sourceProvider = new UrlModuleSourceProvider(
                            Collections.singleton(getDirectory()), null);
                    cachingModuleScriptProvider = new StrongCachingModuleScriptProvider(sourceProvider);
                }
            }                                           // :catchall_0 即 synchronized 语义
        }
        // :cond_1 / :goto_0 — .line 550: new MyRequire(cx, scope, provider, null, null, false)
        MyRequire require = new MyRequire(cx, scope, cachingModuleScriptProvider, null, null, false);
        // .line 558
        ScriptableObject.putProperty(scope, "requirejs", require);
    }


    // ---- D1-D: parse entries (smali 7945-8147, 20401-21179) ----
    // JSEngineCallback / callbackMap / resCodeMap 已在上方 D1 fields 区定义（3256/3319-3320），此处不重复。

    // ================= 1. parseHome (smali 20401-20560) =================
    /**
     * 8.83: parseHome(String url, String resCode, ArticleListRule rule, String js,
     * Map injectMap, OnFindCallBack&lt;List&lt;ArticleList&gt;&gt; callback)。
     * 脚本拼接顺序：\n + getMyRule + generateInjectMap + generateMyParams(myParams)
     * + getMyType("home") + getMyCallbackKey + getMyUrl + getMyJs；runScript(script, uuid)。
     */
    public void parseHome(String url, String resCode, HkRule rule, String js,
                          Map<String, Object> injectMap, JSEngineCallback<List<?>> callback) {
        String uuid = genUuid();
        callbackMap.put(uuid, callback);
        resCodeMap.put(uuid, resCode);
        try {
            String script = "\n"
                    + getMyRule(rule)
                    + generateInjectMap(injectMap)
                    + generateMyParams(myParams)
                    + getMyType("home")
                    + getMyCallbackKey(uuid)
                    + getMyUrl(url)
                    + getMyJs(js);
            runScript(script, uuid);
        } catch (Exception e) {
            setError("运行出错：" + e.getMessage(), uuid, GSON.toJson(rule));
            Logger.t(TAG).e(e, "parseHome: ");
        }
    }

    // ================= 2. parseLastChapterRule (smali 20561-20701) =================
    /**
     * 8.83: parseLastChapterRule(String url, String resCode, ArticleListRule rule, String js,
     * Map injectMap, OnFindCallBack&lt;String&gt; callback)。
     * 拼接顺序：getMyRule + generateMyParams(myParams) + generateInjectMap
     * + getMyType("lastChapter") + getMyCallbackKey + getMyUrl + getMyJs；
     * runScript(script, uuid, true)。异常走 callback.showErr（不 setError）。
     */
    public void parseLastChapterRule(String url, String resCode, HkRule rule, String js,
                                     Map<String, Object> injectMap, JSEngineCallback<String> callback) {
        String uuid = genUuid();
        callbackMap.put(uuid, callback);
        resCodeMap.put(uuid, resCode);
        try {
            String script = ""
                    + getMyRule(rule)
                    + generateMyParams(myParams)
                    + generateInjectMap(injectMap)
                    + getMyType("lastChapter")
                    + getMyCallbackKey(uuid)
                    + getMyUrl(url)
                    + getMyJs(js);
            runScript(script, uuid, true);
        } catch (Exception e) {
            callback.showErr(e.getMessage());
            Logger.t(TAG).e(e, "parseLastChapter: ");
        }
    }

    // ================= 3. parsePreRule(SearchEngine) (smali 20725-20823) =================
    /**
     * 8.83: parsePreRule(SearchEngine engine)。
     * 拼接顺序：getMyCallbackKey + getMyType("preEngine") + getMyRule + engine.getPreRule()；
     * runScript(script, uuid)。因 HkRule 统一了 SearchEngine/ArticleListRule 模型，
     * 与下方的 parsePreRule(HkRule) 签名冲突，故更名为 parsePreEngineRule（见 notes）。
     */
    public void parsePreEngineRule(HkRule rule) {
        String uuid = genUuid();
        try {
            String script = ""
                    + getMyCallbackKey(uuid)
                    + getMyType("preEngine")
                    + getMyRule(rule)
                    + rule.getPreRule();
            runScript(script, uuid);
        } catch (Exception e) {
            setError("运行出错：" + e.toString(), uuid, GSON.toJson(rule));
            Logger.t(TAG).e(e, "parsePreRule: ");
        }
    }

    // ================= 4. parsePreRule(ArticleListRule) (smali 20824-20834) =================
    /** 8.83: parsePreRule(ArticleListRule rule) -> parsePreRule(rule, false)。 */
    public void parsePreRule(HkRule rule) throws Exception {
        parsePreRule(rule, false);
    }

    // ================= 5. parsePreRule(ArticleListRule, boolean) (smali 20835-20955) =================
    /**
     * 8.83: parsePreRule(ArticleListRule rule, boolean throwOnError)。
     * 拼接顺序：getMyCallbackKey + getMyType("preHome") + getMyRule
     * + generateMyParams(myParams) + rule.getPreRule()；
     * runScript(script, uuid, throwOnError)。
     * catch 内先 Timber.e；throwOnError=false 时 setError 后返回，true 时原样 throw。
     */
    public void parsePreRule(HkRule rule, boolean throwOnError) throws Exception {
        String uuid = genUuid();
        try {
            String script = ""
                    + getMyCallbackKey(uuid)
                    + getMyType("preHome")
                    + getMyRule(rule)
                    + generateMyParams(myParams)
                    + rule.getPreRule();
            runScript(script, uuid, throwOnError);
        } catch (Exception e) {
            Logger.t(TAG).e(e, "parsePreRule: ");
            if (!throwOnError) {
                setError("运行出错：" + e.getMessage(), uuid, GSON.toJson(rule));
            } else {
                throw e;
            }
        }
    }

    // ================= 6. parseSearchRes public (smali 21021-21059) =================
    /**
     * 8.83: parseSearchRes(String url, String resCode, SearchEngine engine,
     * Map injectMap, OnFindCallBack&lt;List&lt;SearchResult&gt;&gt; callback)
     * 先 engine.toMovieRule() 再调私有 parseSearchRes。
     * HkRule 统一模型后 toMovieRule 投影被吸收，直接委托私有版本。
     */
    public void parseSearchRes(String url, String resCode, HkRule rule,
                               Map<String, Object> injectMap, JSEngineCallback<List<?>> callback) {
        parseSearchResInternal(url, resCode, rule, injectMap, callback);
    }

    // ================= 7. parseStr (smali 21060-21179) =================
    /**
     * 8.83: parseStr(String resCode, String js, MovieRule rule, OnFindCallBack&lt;String&gt; callback)。
     * 注意：p1 是 resCode（进 resCodeMap），p2 是 js。
     * 拼接顺序：getMyCallbackKey + getMyType("string") + getMyRule + getMyJs(js)；
     * runScript(script, uuid)。
     */
    public void parseStr(String resCode, String js, HkRule rule, JSEngineCallback<String> callback) {
        String uuid = genUuid();
        callbackMap.put(uuid, callback);
        resCodeMap.put(uuid, resCode);
        try {
            String script = ""
                    + getMyCallbackKey(uuid)
                    + getMyType("string")
                    + getMyRule(rule)
                    + getMyJs(js);
            runScript(script, uuid);
        } catch (Exception e) {
            setError("运行出错：" + e.getMessage(), uuid, GSON.toJson(rule));
            Logger.t(TAG).e(e, "parseStr: ");
        }
    }

    // ================= 8. parseSearchRes private (smali 7945-8147) =================
    /**
     * 8.83 私有 parseSearchRes(String url, String resCode, MovieRule rule,
     * Map injectMap, OnFindCallBack&lt;List&lt;SearchResult&gt;&gt; callback)。
     * searchFind 非 "js:" 开头时直接 callback.showErr(title + "---搜索结果解析失败！请检查规则") 并返回。
     * 拼接顺序：getMyCallbackKey + getMyRule + generateInjectMap
     * + generateMyParams(myParams) + getMyUrl(url) + getMyType("search")
     * + getMyJs(rule.getSearchFind())；runScript(script, uuid)。
     * catch 内先 Timber.e 再 setError（用 e.toString() 而非 getMessage()）。
     */
    private void parseSearchResInternal(String url, String resCode, HkRule rule,
                                        Map<String, Object> injectMap, JSEngineCallback<List<?>> callback) {
        String uuid = genUuid();
        callbackMap.put(uuid, callback);
        resCodeMap.put(uuid, resCode);
        if (!rule.getSearchFind().startsWith("js:")) {
            callback.showErr(rule.getTitle() + "---搜索结果解析失败！请检查规则");
            return;
        }
        try {
            String script = ""
                    + getMyCallbackKey(uuid)
                    + getMyRule(rule)
                    + generateInjectMap(injectMap)
                    + generateMyParams(myParams)
                    + getMyUrl(url)
                    + getMyType("search")
                    + getMyJs(rule.getSearchFind());
            runScript(script, uuid);
        } catch (Exception e) {
            Logger.t(TAG).e(e, "parseSearchRes: ");
            setError("运行出错：" + e.toString(), uuid, GSON.toJson(rule));
        }
    }


    // ---- D1-E: network (smali 2061-2381, 8148-8191, 15903-15919) ----

// ---------------------------------------------------------------------------
// 8.83: JSEngine$$ExternalSyntheticLambda15 / lambda$fetch0$9
// HttpHelper$RuleFetchDelegate.fetch(String url, boolean toHex, boolean inputStream)
// captures (JSEngine, ruleKey): argsNativeObjectAdjust(ruleKey) -> fetchByHiker(...)
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 8.83: JSEngine.fetch0(String url, Object options, Object ruleKey,
//                        HttpHelper$HeadersInterceptor)  [smali 2061-2143]
// Called by the JS fetch API path. resCode/lastUrl are captured inside
// HttpHelper.fetch0 (NOT HERE); this method only builds options and delegates.
// ---------------------------------------------------------------------------
private Object fetch0(String url, Object options, Object ruleKey, HkHttpHelper.HeadersInterceptor interceptor) {
    try {
        if (TextUtils.isEmpty(url)) return "";
        url = proxyRequire(ruleKey, url);
        java.util.Map<String, Object> opts = null;
        if (options != null && !isUndefined(options)) {
            opts = (java.util.Map<String, Object>) argsNativeObjectAdjust(options);
        }
        final Object ruleKey0 = ruleKey;
        return HkHttpHelper.fetch0(url, opts, interceptor, new HkHttpHelper.RuleFetchDelegate() {
            @Override
            public Object fetch(String u, boolean toHex, boolean inputStream) {
                return fetchByHiker(u, argsNativeObjectAdjust(ruleKey0), toHex, inputStream);
            }
        });
    } catch (Throwable t) {
        Logger.t(TAG).e(t, "fetch0");
        return "";
    }
}

// ---------------------------------------------------------------------------
// 8.83: JSEngine.fetchByHiker(String url, Object options, boolean toHex,
//                             boolean inputStream)  [smali 2144-2381]
// Handles the hiker:// and file:// pseudo-protocols for fetch.
// NOTE the signature swap: at smali :cond_0 p3 ("hiker://page/") is reused as a
// boolean flag AFTER the page branch, i.e. (toHex, inputStream) order.
// ---------------------------------------------------------------------------
private Object fetchByHiker(String url, Object options, boolean toHex, boolean inputStream) {
    if (url.startsWith("hiker://files/") || url.startsWith("file://")) {
        // file branch
        String path = getFilePath(url);
        java.io.File f = new java.io.File(path);
        if (!f.exists()) return "";
        if (inputStream) {
            try {
                return hkFileToInputStream(path);
            } catch (java.io.IOException e) {
                Logger.t(TAG).e(e, "fetchByHiker file inputStream");
                return "";
            }
        }
        if (toHex) {
            try {
                byte[] bytes = hkFileToBytes(path);
                return hkBytesToHexString(bytes);
            } catch (java.io.IOException e) {
                Logger.t(TAG).e(e, "fetchByHiker file toHex");
                return "";
            }
        }
        return fileToString(f, null, true);
    }
    if (url.startsWith("hiker://page/")) {
        // 8.83: PageParser.parsePageRule(rule, url)；宿主已移植为 handlePageRequest(url)（同文件）。
        try {
            return handlePageRequest(url);
        } catch (Exception e) {
            Logger.t(TAG).e(e, "fetchByHiker page");
            return "";
        }
    }
    if (url.startsWith("hiker://")) {
        if (url.startsWith("hiker://assets/")) {
            try {
                return hkGetAssetsFile(url);
            } catch (Exception e) {
                return "";
            }
        }
        try {
            return hkGetRulesByHiker(url);
        } catch (Exception e) {
            return "";
        }
    }
    return Boolean.FALSE; // 8.83 clobbers p4 to 0x0 before :cond_2, so this is always FALSE
}

// ---------------------------------------------------------------------------
// 8.83: JSEngine.post0(String url, Object options, Object ruleKey,
//                      boolean flag)  [smali 8148-8191]
// NOTE: the trailing boolean is passed INVERTED (xor 1) to
// generateRequestOptions(ops, !flag). 8.83 callers: post/postRequest pass
// true, postPC passes false — keep the same values at the call sites.
// ---------------------------------------------------------------------------
private Object post0(String url, Object options, Object ruleKey, boolean flag) {
    Object postOps = argsNativeObjectAdjust(options);
    if (isUndefined(postOps)) {
        postOps = null;
    }
    if (postOps != null) {
        postOps = HkHttpHelper.generatePostOps((java.util.Map<String, Object>) postOps);
    }
    Object requestOptions = generateRequestOptions(postOps, !flag);
    return fetch0(url, requestOptions, ruleKey, null);
}

// ---------------------------------------------------------------------------
// 8.83: JSEngine.fetchWithHeadersInterceptor(String, Object, Object,
//                                            HttpHelper$HeadersInterceptor)
//                                              [smali 15903-15919] (public)
// Applies generateHeadersOptions(options) first, then fetch0.
// ---------------------------------------------------------------------------
public String fetchWithHeadersInterceptor(String url, Object options, Object ruleKey,
                                          HkHttpHelper.HeadersInterceptor interceptor) {
    Object headerOpts = generateHeadersOptions(options);
    Object result = fetch0(url, headerOpts, ruleKey, interceptor);
    return (String) result;
}


    // ---- D1-F: eval infra helpers ----
/**
 * Port of JSEngine.initRhino(Lorg/mozilla/javascript/Context;)V.
 * Smali lines 5485-5505. Note: the Context here is Context.
 */
private void initRhino(Context cx) {
    cx.setOptimizationLevel(-1);
    cx.setLanguageVersion(200);
    cx.setTrackUnhandledPromiseRejections(true);
}

// ---- 2. getMyRule (smali 4936-5112) ------------------------------------------

/**
 * Port of JSEngine.getMyRule(Ljava/lang/Object;)Ljava/lang/String; (public static).
 * Smali lines 4936-5112.
 *
 * DIVERGENCE (documented, not simplified): 8.83 dispatches on three model
 * classes (ArticleListRule / SearchEngine / MovieRule), each contributing
 * getTitle() + getDisplayName(). The target has a single HkRule type with
 * getTitle() and no display-name concept, so the dispatch collapses to one
 * instanceof and displayName stays null (the _displayName branch is kept
 * verbatim and simply never fires).
 */
public static String getMyRule(Object rule) {
    String title = "";
    String displayName = null;
    if (rule instanceof HkRule) {
        title = ((HkRule) rule).getTitle();
    }
    StringBuilder sb = new StringBuilder("let my_rule = '");
    sb.append(escapeJsString(GSON.toJson(rule)));
    sb.append("';\n const MY_RULE = JSON.parse(my_rule);\n my_rule = null;\n ");
    if (!TextUtils.isEmpty(displayName)) {
        sb.append("const _displayName = '").append(escapeJsString(displayName)).append("';\n");
    }
    sb.append("const MY_TICKET = '");
    sb.append(escapeJsString(generateTicket(title)));
    sb.append("';\neval(getJsPlugin());\neval(getJsLazyPlugin());\n");
    return sb.toString();
}

// ---- 3. getMyType (smali 5113-5139) --------------------------------------------

/**
 * Port of JSEngine.getMyType(Ljava/lang/String;)Ljava/lang/String; (private).
 * Smali lines 5113-5139. Note: 8.83 does NOT escape the type value here.
 */
private String getMyType(String type) {
    return "var MY_TYPE = '" + type + "';\n";
}

// ---- 4. getMyCallbackKey (smali 4863-4889) ---------------------------------------

/**
 * Port of JSEngine.getMyCallbackKey(Ljava/lang/String;)Ljava/lang/String; (private).
 * Smali lines 4863-4889. Note: 8.83 does NOT escape the key here.
 */
private String getMyCallbackKey(String callbackKey) {
    return "var CALLBACK_KEY = '" + callbackKey + "';\n";
}

// ---- 5. getMyInput (smali 4890-4920) ----------------------------------------------

/**
 * Port of JSEngine.getMyInput(Ljava/lang/String;)Ljava/lang/String; (private).
 * Smali lines 4890-4920.
 */
private String getMyInput(String input) {
    return "var input = '" + escapeJsString(input) + "';\n";
}

// ---- 6. getMyJs (smali 4921-4935) -------------------------------------------------

/**
 * Port of JSEngine.getMyJs(Ljava/lang/String;)Ljava/lang/String; (private).
 * Smali lines 4921-4935. 8.83 uses Apache StringUtils.replaceOnce("js:", "");
 * the indexOf/substring form below is the same first-occurrence replacement.
 */
private String getMyJs(String js) {
    int i = js.indexOf("js:");
    return i < 0 ? js : js.substring(0, i) + js.substring(i + 3);
}

// ---- 7. getMyUrl (smali 17338-17389) -----------------------------------------------

/**
 * Port of JSEngine.getMyUrl(Ljava/lang/String;)Ljava/lang/String; (public in 8.83).
 * Smali lines 17338-17389.
 * QUIRK PRESERVED: 8.83 escapes the URL first, then computes MY_HOME from the
 * ESCAPED string (StringUtil.getHome is called on the already-escaped value).
 */
public String getMyUrl(String url) {
    if (url == null) {
        url = "";
    }
    url = escapeJsString(url);
    return "var MY_URL = '" + url + "';\nvar MY_HOME = '" + getHomeUrl(url) + "';\n";
}

// ---- 8. appendLineVar (smali 555-703) -----------------------------------------------

/**
 * Port of JSEngine.appendLineVar(Ljava/lang/String;)Ljava/lang/String; (private).
 * Smali lines 555-703. Counts the leading run of blank lines plus lines that
 * declare script-injected vars (const/var my_*, const/var MY_*, "var input =",
 * "var CALLBACK_", "eval(getJs", "const _displayName"); the FIRST other line
 * stops the count. Emits "const MY_PRE_LINE = <count>;\n" ahead of the script.
 * Returns the input unchanged when it is empty.
 */
private String appendLineVar(String script) {
    if (!TextUtils.isEmpty(script)) {
        String[] lines = script.split("\n");
        int count = 0;
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty()
                    && !t.startsWith("const my_")
                    && !t.startsWith("var my_")
                    && !t.startsWith("const MY_")
                    && !t.startsWith("var MY_")
                    && !t.startsWith("var input =")
                    && !t.startsWith("var CALLBACK_")
                    && !t.startsWith("eval(getJs")
                    && !t.startsWith("const _displayName")) {
                break;
            }
            count++;
        }
        script = "const MY_PRE_LINE = " + count + ";\n" + script;
    }
    return script;
}

// ---- 9. getReplaceJS (smali 5212-5224) ------------------------------------------------

/**
 * Port of JSEngine.getReplaceJS(Ljava/lang/String;)Ljava/lang/String; (private).
 * Smali lines 5212-5224. Delegates with raw=false.
 */
private String getReplaceJS(String js) {
    return getReplaceJS(js, false);
}

// ---- 10. getReplaceJS (smali 5225-5273) -------------------------------------------------

/**
 * Port of JSEngine.getReplaceJS(Ljava/lang/String;Z)Ljava/lang/String; (private).
 * Smali lines 5225-5273. Rewrites the Java-style "b.length()" to the JS
 * "b.length"; when raw is false (the usual path) wraps the whole script in
 * "try{\n ... \n}catch(e){\nsetError(e);\n}". Empty input is returned as-is.
 */
private String getReplaceJS(String js, boolean raw) {
    if (!TextUtils.isEmpty(js)) {
        js = js.replace("if (b != null && b.length() > 0) {", "if (b != null && b.length > 0) {");
        if (raw) {
            return js;
        }
        js = "try{\n" + js + "\n}catch(e){\nsetError(e);\n}";
    }
    return js;
}

// ---- 11. getAllFunctions (smali 3046-3105) -----------------------------------------------

/**
 * Port of JSEngine.getAllFunctions()Ljava/lang/String; (private).
 * Smali lines 3046-3105. Reflects over the engine class for methods annotated
 * with @JSAnnotation and folds getFunctionStr(template, returnType, alias, method)
 * over them, starting from the " var ScriptAPI = java.lang.Class.forName(...)"
 * template. Returns the template unchanged when no method is annotated.
 * DIVERGENCE: 8.83 uses this.getClass(); per the port spec this reflects over
 * HkJsRuntime.class explicitly.
 */
private String getAllFunctions() {
    java.lang.reflect.Method[] methods = HkJsRuntime.class.getDeclaredMethods();
    String result = " var ScriptAPI = java.lang.Class.forName(\"%s\", true, javaLoader);\n";
    for (java.lang.reflect.Method method : methods) {
        JSAnnotation annotation = method.getAnnotation(JSAnnotation.class);
        if (annotation == null) {
            continue;
        }
        result = getFunctionStr(result, annotation.returnType(), annotation.alias(), method);
    }
    return result;
}

// ---- 12. getUaObject (smali 17809-17847) ---------------------------------------------------

/**
 * Port of JSEngine.getUaObject()Ljava/lang/String; (public, @JSAnnotation STRING).
 * Smali lines 17809-17847. 8.83 builds a fastjson JSONObject; the target has
 * no fastjson, so a LinkedHashMap + Gson is used (same key order, same JSON).
 */
@JSAnnotation(returnType = ReturnType.STRING)
public String getUaObject() {
    java.util.Map<String, String> ua = new java.util.LinkedHashMap<>();
    ua.put("mobileUa", UA_MOBILE);
    ua.put("pcUa", UA_PC);
    return GSON.toJson(ua);
}

// ---- 13. setError 3-arg (smali 23978-24003) ----------------------------------------------------

/**
 * Port of JSEngine.setError(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V
 * (public). Smali lines 23978-24003. Delegates to the 4-arg overload with a
 * null 4th argument. @Parameter annotations kept as in 8.83.
 */
public void setError(@Parameter("o") Object o,
                    @Parameter("callbackKey") Object callbackKey,
                    @Parameter("ruleKey") Object ruleKey) {
    setError(o, callbackKey, ruleKey, null);
}

// ---- 14. tryGetString (smali 9929-9962) ---------------------------------------------------------

/**
 * Port of JSEngine.tryGetString(Ljava/lang/Object;)Ljava/lang/String; (private).
 * Smali lines 9929-9962. Throws Exception("参数类型只能是字符串") when the
 * (native-object-adjusted) argument is not a String.
 */
private String tryGetString(Object o) throws Exception {
    o = argsNativeObjectAdjust(o);
    if (o instanceof String) {
        return (String) o;
    }
    throw new Exception("参数类型只能是字符串");
}

// ---- 15. generateMyParams (smali 2836-2879) ---------------------------------------------------------

/**
 * Port of JSEngine.generateMyParams(Ljava/lang/String;)Ljava/lang/String; (private).
 * Smali lines 2836-2879. Empty input yields "var MY_PARAMS = {};\n"; otherwise
 * the JSON text is embedded escaped and parsed as MY_PARAMS.
 */
private String generateMyParams(String params) {
    if (TextUtils.isEmpty(params)) {
        return "var MY_PARAMS = {};\n";
    }
    return "var _my_params = '" + escapeJsString(params) + "';\n var MY_PARAMS = JSON.parse(_my_params);\n";
}

// ---- 16. generateInjectMap (smali 2600-2835) ------------------------------------------------------------

/**
 * Port of JSEngine.generateInjectMap(Ljava/util/Map;)Ljava/lang/String;
 * (private static). Smali lines 2600-2835. Emits one "var <key> = <value>;"
 * line per entry: String values are single-quoted + escaped, null becomes
 * "null;", Integer/Float/Boolean are appended raw, anything else becomes
 * "JSON.parse('<escaped fastjson/gson json>');". Null map yields "".
 * (escapeJsString is private static in the target, so this static method can
 * call it directly.)
 */
private static String generateInjectMap(java.util.Map<String, Object> map) {
    String result = "";
    if (map == null) {
        return result;
    }
    for (java.util.Map.Entry<String, Object> entry : map.entrySet()) {
        Object value = entry.getValue();
        result = result + "var " + entry.getKey() + " = ";
        if (value instanceof String) {
            result = result + "'" + escapeJsString((String) value) + "';\n";
        } else if (value == null) {
            result = result + "null;\n";
        } else if (value instanceof Integer || value instanceof Float || value instanceof Boolean) {
            result = result + value + ";\n";
        } else {
            result = result + "JSON.parse('" + escapeJsString(GSON.toJson(value)) + "');\n";
        }
    }
    return result;
}

// ---- 17. decodeConflictStr (StringUtil.smali 1421-1469) -----------------------------------------------

/**
 * Port of StringUtil.decodeConflictStr(Ljava/lang/String;)Ljava/lang/String;
 * (public static). StringUtil.smali lines 1421-1469. Maps doubled full-width
 * punctuation back to ASCII: ？？→? ＆＆→& ；；→; ，，→,. Empty input returned as-is.
 */
private static String decodeConflictStr(String s) {
    if (TextUtils.isEmpty(s)) {
        return s;
    }
    s = s.replace("？？", "?");
    s = s.replace("＆＆", "&");
    s = s.replace("；；", ";");
    s = s.replace("，，", ",");
    return s;
}


    // ---- D1-G: require/net helpers ----
public static String proxyUrl(String url, String proxyConfig) {
    if (!TextUtils.isEmpty(proxyConfig)) {
        String[] lines = proxyConfig.split("\\n");
        for (String line : lines) {
            String[] kv = line.split("=>");
            if (kv.length < 2) continue;
            String key = kv[0].trim();
            if (!url.contains(key)) continue;
            String replacement = kv[1].trim();
            String newUrl = url.replace(key, replacement);
            Logger.t(TAG).d("proxyRequire: old: %s, new: %s", url, newUrl);
            return newUrl;
        }
    }
    return url;
}

// ---------------------------------------------------------------------------
// 8.83: JSEngine.requestForCode(String, Object)String  [JSEngine.smali 8374-8467]
// private. Requests url with withHeaders options; the body must be a JSON
// object {"error":..., "statusCode":..., "body":...}: returns body only when
// error is empty/"null" and statusCode starts with "2"; else null.
// fastjson JSONObject access is mapped to Rhino Scriptable access (see notes).
// ---------------------------------------------------------------------------
private String requestForCode(String url, Object options) {
    try {
        Object headersOptions = generateHeadersOptions(options);
        String result = (String) fetch0(url, generateRequestOptions(headersOptions, false), null, null); // MISSING: JSEngine.request(String,Object,Object)
        Scriptable json = (Scriptable) parseJson(result);
        if (json != null) {
            String error = getString(ScriptableObject.getProperty(json, "error"));
            if (!TextUtils.isEmpty(error) && !"null".equals(error)) return null;
            String statusCode = String.valueOf((int) toDouble(ScriptableObject.getProperty(json, "statusCode")));
            if (!statusCode.startsWith("2")) return null;
            return getString(ScriptableObject.getProperty(json, "body"));
        }
    } catch (Exception e) {
        Logger.t(TAG).e(e, "requestForCode failed");
    }
    return null;
}

// ---------------------------------------------------------------------------
// 8.83: JSEngine.isJsCode(String)Z  [JSEngine.smali 5506-5713]
// private. False for empty input and for HTML/XML documents; true when the
// text contains JS keywords or any name from the methodList field.
// ---------------------------------------------------------------------------
private boolean isJsCode(String code) {
    if (TextUtils.isEmpty(code)) return false;
    code = code.trim();
    for (String prefix : new String[]{"<!DOCTYPE", "<html", "<?xml"}) {
        if (code.startsWith(prefix)) return false;
    }
    for (String suffix : new String[]{"</html>", "</rss>"}) {
        if (code.endsWith(suffix)) return false;
    }
    for (String keyword : new String[]{"var ", "let ", "function", "eval(", "call(", "eval (", "call (", " => ", ")=>"}) {
        if (code.contains(keyword)) return true;
    }
    if (this.methodList != null) { // MISSING: 8.83 JSEngine field List<String> methodList
        for (String name : this.methodList) {
            if (code.contains(name)) return true;
        }
    }
    return false;
}

// ---------------------------------------------------------------------------
// 8.83: JSEngine.getDirectory()URI  [JSEngine.smali 3311-3361]
// private. 8.83: URI.create("file://" + UriUtils.getRootDir(ctx) + "/libs/").
// Substituted per task: App.get().getExternalFilesDir(DIRECTORY_DOCUMENTS),
// mkdirs if missing.
// ---------------------------------------------------------------------------
private URI getDirectory() {
    File docs = App.get().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
    if (docs != null && !docs.exists()) docs.mkdirs();
    String root = docs != null ? docs.getAbsolutePath() : "";
    return URI.create("file://" + root + File.separator + "libs" + File.separator);
}

// ---------------------------------------------------------------------------
// 8.83: JSEngine.generateRequestOptions(Object, boolean)Object
// [JSEngine.smali 2880-2997]  private. NOTE: exact signature takes (Object,
// boolean) — the boolean selects PC vs mobile UA.
// Normalizes options to a Map (headers/header fallback), filling in a
// default User-Agent when absent.
// ---------------------------------------------------------------------------
@SuppressWarnings("unchecked")
private Object generateRequestOptions(Object options, boolean isPc) {
    if (options != null && !isUndefined(options)) {
        Map<String, Object> map = (Map<String, Object>) argsNativeObjectAdjust(options);
        Map<String, Object> headers = (Map<String, Object>) map.get("headers");
        if (headers == null) headers = (Map<String, Object>) map.get("header");
        if (headers == null) {
            headers = new HashMap<>();
            map.put("headers", headers);
        }
        if (!headers.containsKey("User-Agent")) {
            // MISSING: 8.83 com.example.hikerview.constants.UA_PC
            // MISSING: 8.83 com.example.hikerview.ui.setting.model.UA_MOBILE
            headers.put("User-Agent", isPc ? UA_PC : UA_MOBILE);
        }
        return map;
    }
    Map<String, Object> map = new HashMap<>();
    Map<String, Object> headers = new HashMap<>();
    // MISSING: UA_PC / UA_MOBILE (same as above)
    headers.put("User-Agent", isPc ? UA_PC : UA_MOBILE);
    map.put("headers", headers);
    return map;
}

// ---------------------------------------------------------------------------
// 8.83: JSEngine.generateHeadersOptions(Object)Object  [JSEngine.smali 2555-2599]
// private. NOTE: exact signature takes a single Object and returns Object.
// Adds withHeaders=true to the options map (fresh map when options is
// null/undefined).
// ---------------------------------------------------------------------------
@SuppressWarnings("unchecked")
private Object generateHeadersOptions(Object options) {
    Boolean withHeaders = Boolean.valueOf(true);
    if (options != null && !isUndefined(options)) {
        Map<String, Object> map = (Map<String, Object>) argsNativeObjectAdjust(options);
        map.put("withHeaders", withHeaders);
        return map;
    }
    Map<String, Object> map = new HashMap<>();
    map.put("withHeaders", withHeaders);
    return map;
}

// ---------------------------------------------------------------------------
// 8.83: JSEngine.getFilePath(String)String  [JSEngine.smali 3490-3562]
// NOTE: smali declares this public static (not private as described in the
// task); kept public static. "hiker://files/<p>" -> rootDir/<p>;
// "file://<p>" -> <p> (prefix stripped).
// UriUtils.getRootDir substituted per task (same as getDirectory).
// ---------------------------------------------------------------------------
public static String getFilePath(String path) {
    if (path.startsWith("hiker://files/")) {
        path = path.replace("hiker://files/", "");
        File docs = App.get().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (docs != null && !docs.exists()) docs.mkdirs();
        String root = docs != null ? docs.getAbsolutePath() : "";
        return root + File.separator + path;
    }
    if (path.startsWith("file://")) {
        path = path.replace("file://", "");
    }
    return path;
}

// ---------------------------------------------------------------------------
// 8.83: batchExecute(Object tasks, Object listener0, Object success, Object ruleKey)V
// [JSEngine.smali 11276-11680]  D2 真实实现。
// tasks: [{id, func(BaseFunction), param}]；listener0: {func, param} 或 null/undefined；
// success: 触发回调的完成数阈值（默认 tasks.size()）。
// 每个任务在独立线程 + 独立 Rhino Context 里执行 func(param)；
// 完成后若 listener 存在且计数 <= success，回调 listener.func(param, id, false, result)。
// ---------------------------------------------------------------------------
@SuppressWarnings("unchecked")
private void batchExecuteImpl(Object[] args) {
    try {
        Object tasksArg = args != null && args.length > 0 ? argsNativeObjectAdjust(args[0], true) : null;
        if (!(tasksArg instanceof List)) {
            Logger.t(TAG).d("be: tasks not a list");
            return;
        }
        List<Object> tasks = (List<Object>) tasksArg;
        // listener0: {func, param}
        BaseFunction listenerFunc = null;
        Object listenerParam = null;
        if (args.length > 1 && !isUndefined(args[1])) {
            Object lo = argsNativeObjectAdjust(args[1], true);
            if (lo instanceof Map) {
                Map<String, Object> lm = (Map<String, Object>) lo;
                Object f = lm.get("func");
                if (f instanceof BaseFunction) {
                    listenerFunc = (BaseFunction) f;
                    listenerParam = lm.get("param");
                }
            }
        }
        // success 阈值
        int success = tasks.size();
        if (args.length > 2 && !isUndefined(args[2])) {
            Object s = argsNativeObjectAdjust(args[2]);
            if (s instanceof Number) success = ((Number) s).intValue();
        }
        if (success <= 0 || success > tasks.size()) success = tasks.size();
        final int successLimit = success;
        final BaseFunction finalListenerFunc = listenerFunc;
        final Object finalListenerParam = listenerParam;

        int poolSize = Math.min(tasks.size(), 16);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(poolSize, poolSize, 1L,
                TimeUnit.SECONDS, new LinkedBlockingDeque<>(4096));
        CountDownLatch latch = new CountDownLatch(tasks.size());
        AtomicInteger counter = new AtomicInteger(0);

        for (Object t : tasks) {
            if (!(t instanceof Map)) {
                latch.countDown();
                continue;
            }
            final Map<String, Object> task = (Map<String, Object>) t;
            final Object funcObj = task.get("func");
            if (!(funcObj instanceof BaseFunction)) {
                latch.countDown();
                continue;
            }
            final BaseFunction taskFunc = (BaseFunction) funcObj;
            final Object taskParam = task.get("param");
            final String taskId = task.get("id") == null ? "" : String.valueOf(task.get("id"));
            pool.execute(() -> {
                // 8.83: latch.getCount()>0 且 counter < successLimit 才执行，否则直接 countDown
                if (latch.getCount() <= 0 || counter.get() >= successLimit) {
                    latch.countDown();
                    return;
                }
                Context cx = Context.enter();
                try {
                    cx.setOptimizationLevel(-1);
                    Object result = null;
                    try {
                        Scriptable taskScope = taskFunc.getParentScope();
                        result = taskFunc.call(cx, taskScope, taskScope, new Object[]{taskParam});
                    } catch (Throwable e) {
                        Logger.t(TAG).e(e, "be task failed: %s", taskId);
                    }
                    counter.incrementAndGet();
                    // 8.83: synchronized(latch) 回调 listener
                    if (finalListenerFunc != null) {
                        synchronized (latch) {
                            if (counter.get() <= successLimit && latch.getCount() > 0) {
                                try {
                                    Scriptable ls = finalListenerFunc.getParentScope();
                                    Object r = finalListenerFunc.call(cx, ls, ls,
                                            new Object[]{finalListenerParam, taskId, false, result});
                                    Object adjusted = argsNativeObjectAdjust(r);
                                    if ("break".equals(adjusted)) {
                                        counter.getAndSet(successLimit);
                                    }
                                } catch (Throwable e) {
                                    Logger.t(TAG).e(e, "be listener failed");
                                }
                            }
                        }
                    }
                } finally {
                    try {
                        if (latch.getCount() > 0) latch.countDown();
                    } catch (Throwable ignored) {
                    }
                    Context.exit();
                }
            });
        }
        // 8.83: await(size/16*30+30)s
        long timeoutSec = tasks.size() / 16 * 30L + 30L;
        try {
            latch.await(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (!pool.isShutdown()) pool.shutdownNow();
        } catch (Throwable e) {
            Logger.t(TAG).e(e, "be pool shutdown failed");
        }
        BATCH_EXECUTE_READY = true;
        Logger.t(TAG).d("be: done, %d tasks", tasks.size());
    } catch (Throwable e) {
        Logger.t(TAG).e(e, "batchExecute failed");
    }
}

/**
 * 8.83: realLineNumber(int preLine, int lineNumber, Object sourceName)I [smali].
 * sourceName 含 "eval" -> 直接返回 lineNumber；否则 preLine>0 时 lineNumber-preLine。
 */
private int realLineNumber(int preLine, int lineNumber, Object sourceName) {
    String sn = sourceName == null ? "" : String.valueOf(sourceName);
    if (sn.contains("eval")) return lineNumber;
    if (preLine > 0) {
        int r = lineNumber - preLine;
        return r > 0 ? r : lineNumber;
    }
    return lineNumber;
}

/**
 * 8.83: setError(Object o, Object callbackKey, Object ruleKey, Object evalKey)V 完整实现。
 * 明细格式："\n来源：{source}\n代码：{lineSource}\n行数：{n}\n详情：{msg}"；
 * 超 2000 字截断并追加 "...文本过长被截断"；最终消息 = ("解析失败！" | title+"解析失败！") + 明细。
 * 有 callbackKey 对应回调 -> callback.showErr(msg)；否则宿主 Toast。
 */
private void setErrorImpl(Object[] args) {
    try {
        Object o = args != null && args.length > 0 ? argsNativeObjectAdjust(args[0]) : null;
        Object callbackKey = args != null && args.length > 1 ? args[1] : null;
        Object ruleKey = args != null && args.length > 2 ? args[2] : null;
        Object evalKey = args != null && args.length > 3 ? args[3] : null;

        // evalKey 为 Scriptable 时取父 scope 的 MY_PRE_LINE
        int preLine = 0;
        if (evalKey instanceof Scriptable) {
            Scriptable parent = ((Scriptable) evalKey).getParentScope();
            if (parent != null) {
                Object v = parent.get("MY_PRE_LINE", parent);
                if (v != null && !isUndefined(v)) {
                    try {
                        int n = Integer.parseInt(String.valueOf(argsNativeObjectAdjust(v)));
                        if (n > 0) preLine = n + 1;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        String detail;
        if (o instanceof String) {
            detail = (String) o;
        } else if (o instanceof org.mozilla.javascript.JavaScriptException) {
            org.mozilla.javascript.JavaScriptException je = (org.mozilla.javascript.JavaScriptException) o;
            int ln = realLineNumber(preLine, je.lineNumber(), je.sourceName());
            detail = "\n来源：" + je.sourceName() + "\n代码：" + je.lineSource()
                    + "\n行数：" + ln + "\n详情：" + o.toString();
        } else if (o instanceof org.mozilla.javascript.RhinoException) {
            org.mozilla.javascript.RhinoException re = (org.mozilla.javascript.RhinoException) o;
            int ln = realLineNumber(preLine, re.lineNumber(), re.sourceName());
            detail = "\n来源：" + re.sourceName() + "\n代码：" + re.lineSource()
                    + "\n行数：" + ln + "\n详情：" + o.toString();
        } else if (o instanceof ScriptableObject) {
            ScriptableObject so = (ScriptableObject) o;
            Object fileName = so.get("fileName");
            Object lineNumber = so.get("lineNumber");
            Object lineSource = so.get("lineSource");
            int ln = 0;
            try {
                ln = Integer.parseInt(String.valueOf(argsNativeObjectAdjust(lineNumber)));
            } catch (Throwable ignored) {
            }
            ln = realLineNumber(preLine, ln, fileName);
            detail = "\n来源：" + fileName + "\n代码：" + lineSource
                    + "\n行数：" + ln + "\n详情：" + o.toString();
        } else {
            detail = o == null ? "" : String.valueOf(argsNativeObjectAdjust(o));
        }

        if (detail != null && detail.length() > 2000) {
            detail = detail.substring(0, 2000) + "...文本过长被截断";
        }
        error = detail == null ? "" : detail;
        Logger.t(TAG).d("setError: %s", error);

        Object rk = argsNativeObjectAdjust(ruleKey);
        String ck = String.valueOf(argsNativeObjectAdjust(callbackKey));
        JSEngineCallback<?> callback = callbackMap.get(ck);

        // ruleKey 为 Map 且有非空 title -> title + "解析失败！"
        String prefix = "解析失败！";
        if (rk instanceof Map) {
            Object title = ((Map<?, ?>) rk).get("title");
            if (title != null && !String.valueOf(title).isEmpty()) {
                prefix = title + "解析失败！";
            }
        }
        final String msg = prefix + error;
        if (callback == null) {
            try {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(App.get(), msg, android.widget.Toast.LENGTH_LONG).show());
            } catch (Throwable ignored) {
            }
        } else {
            callbackMap.remove(ck);
            try {
                callback.showErr(msg);
            } catch (Throwable e) {
                Logger.t(TAG).e(e, "setError callback.showErr failed");
            }
        }
    } catch (Throwable e) {
        Logger.t(TAG).e(e, "setError failed");
    }
}

// ---------------------------------------------------------------------------
// 8.83: JSEngine.fileToString(File, String, boolean)String
// [JSEngine.smali 2382-2554]  private.
// NOTE: exact signature is (File, String, boolean), not (String).
// Binary extensions (.zip/.db/.apk/.mp4/.png/.jpg) and anything under a
// "backup" dir return "". When charset arg is non-empty: encrypted=false ->
// charset-detect read; encrypted=true -> fetch the "i--" item as AES key and
// decrypt. Empty charset arg + path inside getFilesDir() -> "".
// ---------------------------------------------------------------------------
private String fileToString(File file, String charset, boolean encrypted) {
    if (!file.exists()) return "";
    String path = file.getAbsolutePath();
    if (path.endsWith(".zip") || path.endsWith(".db") || path.endsWith(".apk")
            || path.endsWith(".mp4") || path.endsWith(".png") || path.endsWith(".jpg")) {
        return "";
    }
    if (path.contains(File.separator + "backup" + File.separator)) return "";
    if (!TextUtils.isEmpty(charset)) {
        // D2: 8.83 语义补全。charset 非空：
        //   encrypted=false -> 字符集探测读取；
        //   encrypted=true  -> 取 "i--" 项为 AES key 解密文件内容。
        if (!encrypted) return getContentByCharsetDetect(path);
        String key = kv != null ? kv.get("i--") : null;
        if (!TextUtils.isEmpty(key)) {
            return aesDecode(key, readFileContent(path));
        }
    } else {
        if (path.contains(getFilesDir())) return "";
    }
    return getContentByCharsetDetect(path);
}

/**
 * 8.83: JSEngine.getContentByCharsetDetect(String) [smali 2669-2674] +
 * FileUtil.getContentByCharsetDetect [FileUtil.smali]。
 * .js/.json 直接按平台默认字符集读；其余用 juniversalchardet 探测编码
 * （org.mozilla.universalchardet，与 8.83 的 EncodeUtilKt.getEncode 同款库），
 * UTF-8（含探测失败回退）走默认读取，否则按探测到的编码解码。
 */
private String getContentByCharsetDetect(String path) {
    if (path.endsWith(".js") || path.endsWith(".json")) {
        return readFileContent(path);
    }
    String encode = detectCharset(path);
    if (encode == null || encode.equalsIgnoreCase("UTF-8")) {
        return readFileContent(path);
    }
    try {
        byte[] bytes = java.nio.file.Files.readAllBytes(new File(path).toPath());
        if (bytes == null) return "";
        return new String(bytes, encode);
    } catch (Throwable e) {
        Logger.t(TAG).e(e, "getContentByCharsetDetect failed: %s", path);
        return readFileContent(path);
    }
}

/** juniversalchardet 探测文件编码（8.83 EncodeUtilKt.getEncode 等价）。 */
private String detectCharset(String path) {
    org.mozilla.universalchardet.UniversalDetector detector = null;
    java.io.InputStream in = null;
    try {
        detector = new org.mozilla.universalchardet.UniversalDetector(null);
        in = new java.io.BufferedInputStream(new java.io.FileInputStream(path));
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0 && !detector.isDone()) {
            detector.handleData(buf, 0, n);
        }
        detector.dataEnd();
        return detector.getDetectedCharset();
    } catch (Throwable e) {
        return null;
    } finally {
        try {
            if (in != null) in.close();
        } catch (Throwable ignored) {
        }
    }
}

// ---------------------------------------------------------------------------
// 8.83: getRequireVersion(String, int)int
// [RequireUtils.smali 866-913]  public static.
// DEVIATION: target has no RequireUtils class; ported as private static here.
// Reads the description file and returns its version; Math.min(version, 0)
// when the file is missing/unparseable.
// ---------------------------------------------------------------------------
private static int getRequireVersion(String path, int version) {
    if (new File(path).exists()) {
        String content = readFileContentStatic(path);
        // fastjson JSON.parseObject -> GSON.fromJson substitution.
        RequireDescription desc = GSON.fromJson(content, RequireDescription.class);
        if (desc != null) return desc.getVersion();
    }
    return Math.min(version, 0);
}

// ---------------------------------------------------------------------------
// 8.83: updateDescription(String, String, int)void
// [RequireUtils.smali 946-975]  public static.
// DEVIATION: target has no RequireUtils class; ported as private static here.
// Writes new RequireDescription(url, version) JSON to path; IOException is
// logged and swallowed.
// ---------------------------------------------------------------------------
private static void updateDescription(String path, String url, int version) {
    try {
        // MISSING: 8.83 com.example.hikerview.ui.rules.model.RequireDescription
        RequireDescription desc = new RequireDescription(url, version);
        // 8.83 FileUtil.stringToFile -> plain write; fastjson JSON.toJSONString -> GSON.toJson.
        writeFileContentStatic(path, GSON.toJson(desc));
    } catch (Exception e) {
        Logger.t(TAG).e(e, "updateDescription failed");
    }
}

// ---------------------------------------------------------------------------
// 8.83: generateRequireMap(String, String, String, String)void
// [RequireUtils.smali 431-461]  public static.
// DEVIATION: target has no RequireUtils class; ported as private static here.
// (dirName, url, proxy, file). 8.83 wraps generateRequireMapSync in a synthetic
// lambda class and posts it to HeavyTaskUtil; expressed here as a Java lambda.
// NOTE: the guard below is a literal port — 8.83 posts ONLY when dirName is
// empty AND url is non-empty (see notes; semantics look inverted).
// ---------------------------------------------------------------------------
private static void generateRequireMapSync(String dirName, String url, String proxy, String file) {
    String path = getFilesDirStatic() + dirName + File.separator + "require.json";
    java.util.List<RequireItem> items;
    if (new File(path).exists()) {
        try {
            String json = readFileContentStatic(path);
            java.lang.reflect.Type t = new TypeToken<java.util.List<RequireItem>>() {}.getType();
            items = GSON.fromJson(json, t);
            if (items == null) items = new ArrayList<>();
        } catch (Exception e) {
            Logger.t(TAG).e(e.getMessage());
            items = new ArrayList<>();
        }
    } else {
        items = new ArrayList<>();
    }
    generateRequireMap(items, dirName, url, proxy, file);
}

private static String getFilesDirStatic() {
    return hkRootDir() + File.separator + "files";
}

private static String readFileContentStatic(String path) {
    try {
        java.io.File f = new java.io.File(path);
        byte[] b = new byte[(int) f.length()];
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
        }
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
        return "";
    }
}

private static void writeFileContentStatic(String path, String content) {
    try (java.io.OutputStream out = new java.io.FileOutputStream(path)) {
        out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (Exception e) {
        Logger.t(TAG).e(e, "writeFileContent failed");
    }
}

private static void generateRequireMap(String dirName, String url, String proxy, String file) {
    if (TextUtils.isEmpty(dirName) && !TextUtils.isEmpty(url)) {
        // 8.83 posts to HeavyTaskUtil queue; here a plain thread (generateRequireMapSync ported above).
        new Thread(() -> generateRequireMapSync(dirName, url, proxy, file)).start();
    }
}

// ---------------------------------------------------------------------------
// 8.83: generateRequireMap(List<RequireItem>, String, String,
//   String, String)void  [RequireUtils.smali 462-648]  private static.
// DEVIATION: target has no RequireUtils class; kept private static here.
// (items, dirName, url, proxy, file): refresh accessTime/proxy of the entry
// whose url matches, else append new RequireItem(url, proxy, file, now);
// drop entries untouched for 7 days when the list exceeds 100; persist to
// <getFilesDir()>/<dirName>/require.json.
// ---------------------------------------------------------------------------
private static void generateRequireMap(List<RequireItem> items, String dirName, String url, String proxy, String file) {
    long now = System.currentTimeMillis();
    boolean found = false;
    // MISSING: 8.83 com.example.hikerview.ui.rules.model.RequireItem
    //   (url, proxy, file, accessTime; RequireItem.smali ctor verified)
    for (RequireItem item : items) {
        if (item.getUrl().equals(url)) {
            item.setAccessTime(now);
            // 8.83: org.apache.commons.lang3.StringUtils.equals(url, proxy) -> null-safe; TextUtils.equals used.
            if (TextUtils.equals(url, proxy)) proxy = null;
            item.setProxy(proxy);
            found = true;
            break;
        }
    }
    if (!found) {
        items.add(new RequireItem(url, TextUtils.equals(url, proxy) ? null : proxy, file, now));
    }
    if (items.size() > 100) {
        long threshold = now - 604800000L; // 8.83 literal 0x240c8400 = 7 days in ms
        // 8.83: annimon Stream.of(items).filter(i -> i.getAccessTime() > threshold).toList()
        List<RequireItem> kept = new ArrayList<>();
        for (RequireItem item : items) {
            if (item.getAccessTime() > threshold) kept.add(item);
        }
        items = kept;
    }
    try {
        String path = getFilesDirStatic() + dirName + File.separator + "require.json";
        // fastjson JSON.toJSONString -> GSON.toJson substitution.
        writeFileContentStatic(path, GSON.toJson(items));
    } catch (Exception e) {
        Logger.t(TAG).e(e, "generateRequireMap failed");
    }
}



    // ---- D1-H: crypto (RC4 / MyBase64) ----
private static class HkRc4 {

    public HkRc4() {
    }

    public String encode(String data, String key) {
        return enc(data, key, "UTF-8");
    }

    public String encode(String data, String key, String charset) {
        return enc(data, key, charset);
    }

    public static String decode(String hex, String key) {
        return dec(hex, key, "UTF-8");
    }

    public static String decode(String hex, String key, String charset) {
        return dec(hex, key, charset);
    }

    public static String enc(String data, String key, String charset) {
        if (data == null || key == null) {
            return "";
        }
        try {
            char[] digits = "0123456789ABCDEF".toCharArray();
            byte[] cipher = rc4Base(data.getBytes(charset), key, charset);
            char[] out = new char[cipher.length * 2];
            int j = 0;
            for (byte b : cipher) {
                out[j++] = digits[(b >>> 4) & 0xF];
                out[j++] = digits[b & 0xF];
            }
            return new String(out);
        } catch (Exception e) {
            return "";
        }
    }

    public static String dec(String hex, String key, String charset) {
        if (hex == null || key == null) {
            return "";
        }
        try {
            byte[] cipher = hexString2Bytes(hex, charset);
            byte[] plain = rc4Base(cipher, key, charset);
            return new String(plain, charset);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] hexString2Bytes(String hex, String charset) {
        try {
            byte[] src = hex.getBytes(charset);
            byte[] out = new byte[hex.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = uniteBytes(src[i * 2], src[i * 2 + 1]);
            }
            return out;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static byte[] rc4Base(byte[] data, String key, String charset) {
        byte[] s = initKey(key, charset);
        byte[] out = new byte[data.length];
        int i = 0;
        int j = 0;
        for (int n = 0; n < data.length; n++) {
            i = (i + 1) & 0xFF;
            byte si = s[i];
            j = (j + (si & 0xFF)) & 0xFF;
            byte sj = s[j];
            s[i] = sj;
            s[j] = si;
            int t = ((s[i] & 0xFF) + (si & 0xFF)) & 0xFF;
            out[n] = (byte) (data[n] ^ s[t]);
        }
        return out;
    }

    private static byte[] initKey(String key, String charset) {
        try {
            byte[] keyBytes = key.getBytes(charset);
            byte[] s = new byte[256];
            for (int i = 0; i < 256; i++) {
                s[i] = (byte) i;
            }
            if (keyBytes == null || keyBytes.length == 0) {
                return null;
            }
            int j = 0;
            for (int i = 0; i < 256; i++) {
                j = (j + (keyBytes[i % keyBytes.length] & 0xFF) + (s[i] & 0xFF)) & 0xFF;
                byte tmp = s[i];
                s[i] = s[j];
                s[j] = tmp;
            }
            return s;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static byte uniteBytes(byte b1, byte b2) {
        int high = Byte.decode("0x" + new String(new byte[]{b1})).byteValue();
        int low = Byte.decode("0x" + new String(new byte[]{b2})).byteValue();
        return (byte) (((char) high << 4) ^ (char) low);
    }
}

// Fragment for HkJsRuntime.java — paste inside the class body.
// Source: 8.83 decompile of com.example.hikerview.utils.encrypt.MyBase64 (+ MyBase64$Coder/Decoder/Encoder).
// Finding: it is a verbatim port of AOSP android.util.Base64 — same flag constants, same
// standard alphabet (A–Z a–z 0–9 +/, and -_ under URL_SAFE), same 76-char wrap, same padding
// rules, same "bad base-64" IllegalArgumentException. Delegating to android.util.Base64 is
// therefore bit-identical; the full streaming Encoder/Decoder/Coder state machine was not
// re-ported by hand to avoid transcription drift. See frag_crypto_notes.md.

private static class HkMyBase64 {

    public final int DEFAULT = 0;
    public final int NO_PADDING = 1;
    public final int NO_WRAP = 2;
    public final int CRLF = 4;
    public final int URL_SAFE = 8;
    public final int NO_CLOSE = 16;

    public HkMyBase64() {
    }

    public byte[] decode(String str, int flags) {
        return decode(str.getBytes(), flags);
    }

    public byte[] decode(byte[] input, int flags) {
        return decode(input, 0, input.length, flags);
    }

    public byte[] decode(byte[] input, int offset, int len, int flags) {
        return android.util.Base64.decode(input, offset, len, flags);
    }

    public byte[] encode(byte[] input, int flags) {
        return encode(input, 0, input.length, flags);
    }

    public byte[] encode(byte[] input, int offset, int len, int flags) {
        return android.util.Base64.encode(input, offset, len, flags);
    }

    public String encodeToString(byte[] input, int flags) {
        return android.util.Base64.encodeToString(input, flags);
    }

    public String encodeToString(byte[] input, int offset, int len, int flags) {
        return android.util.Base64.encodeToString(input, offset, len, flags);
    }
}


    // ---- D1 merge shims ----
    /** 8.83 HttpHelper.bytesToHexString (in HkHttpHelper as private; local copy for fetchByHiker). */
    private static String hkBytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String h = Integer.toHexString(b & 0xFF);
            if (h.length() == 1) sb.append('0');
            sb.append(h);
        }
        return sb.toString();
    }


}
