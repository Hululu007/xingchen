package com.fongmi.android.tv.api.music;

import android.text.TextUtils;
import android.util.Base64;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.clash.MihomoManager;
import com.fongmi.android.tv.clash.SourceProxyPolicy;
import com.fongmi.android.tv.login.LoginManager;
import com.fongmi.quickjs.bean.Req;
import com.fongmi.quickjs.method.Console;
import com.fongmi.quickjs.utils.Connect;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.SourceGate;
import com.github.catvod.utils.Asset;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.whl.quickjs.android.QuickJSLoader;
import com.whl.quickjs.wrapper.JSCallFunction;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Response;

/**
 * 落雪（LX Music）桥：与 FreeJsBridge 并行、互不干扰。
 * 每脚本独占一个 QuickJSContext（单线程串行）；垫片 quickjs 模块 assets/js/lib/lx.js
 * 注入 globalThis.lx（request/on/send/utils/EVENT_NAMES/version/env/currentScriptInfo），
 * 与落雪官方 FAQ 的宿主契约对齐。脚本只能用注入的 lx 能力，无文件/系统访问，
 * 网络经 __lxRequest（同步）走壳子 OkHttp（吃代理/DoH/节点定向），与 FreeJS 同等沙箱。
 * 解析动作：脚本 on(EVENT_NAMES.request) 的 handler 返回 Promise，本桥挂 then/catch 等结果。
 */
public class LxBridge {

    /** 宿主能力版本（脚本按它分支选 utils 语义，如 bufToString） */
    public static final String LX_VERSION = "1.0.0";

    /**
     * 桥内单线程（QuickJS 上下文只能串行）改优先队列：取链（musicUrl）优先于
     * 取词（lyric）/取图（pic）——与 FreeJsBridge 同口径：点歌受理那一刻服务侧
     * 取词同刻进同一座桥排队，取链排在慢取词后面就白等几秒，重启首播最明显。
     * 只调排队顺序，不抢占正在跑的那条（JS 不可抢占，单线程桥的固有限制）。
     */
    private static final int PRIO_NORMAL = 0;
    private static final int PRIO_HIGH = 1;
    private static final AtomicLong TASK_SEQ = new AtomicLong();

    private static final class PrioTask<T> extends FutureTask<T> implements Comparable<PrioTask<?>> {
        private final int prio;
        private final long seq;

        PrioTask(Callable<T> callable, int prio, long seq) {
            super(callable);
            this.prio = prio;
            this.seq = seq;
        }

        @Override
        public int compareTo(PrioTask<?> other) {
            int byPrio = Integer.compare(other.prio, prio);
            return byPrio != 0 ? byPrio : Long.compare(seq, other.seq);
        }
    }

    private final ExecutorService executor = new ThreadPoolExecutor(1, 1,
            0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(),
            Executors.defaultThreadFactory());
    private final MusicSite site;
    private final String scriptJs;

    private QuickJSContext ctx;
    private JSFunction requestHandler;
    private volatile boolean inited;
    private volatile String initError = "";
    private final LinkedHashMap<String, LxSource> sources = new LinkedHashMap<>();

    /** 脚本头部注释块里的 @name/@version 声明的元信息（平台无关，安装时读一次即可） */
    public final String metaName;
    public final String metaVersion;
    public final String metaAuthor;

    /**
     * 脚本头部声明需要的壳子统一登录提供方 id（@loginProvider，如油管落雪脚本的
     * google）；空＝不依赖登录。只存桥内、不回填 site，与 FreeJsBridge.loginProvider
     * 同纪律：它只是能力声明，登录通路闭包绑定本字段，脚本选不了提供方、读不到
     * 别家的 Cookie；未声明的脚本两个原生函数恒空转，老脚本零影响。
     */
    private final String loginProvider;

    /** 未登录自动弹框的进程内节流（按提供方，10 分钟至多一次，与 FreeJsBridge 同口径） */
    private static final long LOGIN_PROMPT_INTERVAL_MS = 10 * 60 * 1000L;
    private static final java.util.Map<String, Long> LOGIN_PROMPT_AT = new java.util.concurrent.ConcurrentHashMap<>();

    /** 按需拉节点内核失败后的重试间隔（与 FreeJsBridge.ensureNodeForMusic 同口径） */
    private static final long NODE_START_RETRY_MS = 60000;
    private volatile long nodeStartFailedAt;

    public LxBridge(MusicSite site, String scriptJs) {
        this.site = site;
        this.scriptJs = scriptJs == null ? "" : scriptJs;
        this.metaName = headerTag(this.scriptJs, "name");
        this.metaVersion = headerTag(this.scriptJs, "version");
        this.metaAuthor = headerTag(this.scriptJs, "author");
        this.loginProvider = headerTag(this.scriptJs, "loginProvider").trim();
    }

    /** 从脚本开头注释块读 @tag（落雪脚本惯例，如 @name/@version/@author） */
    public static String headerTag(String js, String tag) {
        if (js == null) return "";
        int end = js.indexOf("*/");
        String head = end > 0 ? js.substring(0, Math.min(end + 2, 2048)) : js.substring(0, Math.min(js.length(), 2048));
        Matcher m = Pattern.compile("(?m)^\\s*\\*?\\s*@" + tag + "\\s+(.+?)\\s*$").matcher(head);
        return m.find() ? m.group(1).trim() : "";
    }

    public MusicSite getSite() {
        return site;
    }

    public String key() {
        return site.srcUrl == null ? "" : site.srcUrl;
    }

    private <T> Future<T> submit(Callable<T> callable) {
        return submitPrio(callable, PRIO_NORMAL);
    }

    /** 取链（musicUrl）/拆桥专用高优先提交：排在已排队的取词/取图前面 */
    private <T> Future<T> submitHigh(Callable<T> callable) {
        return submitPrio(callable, PRIO_HIGH);
    }

    private <T> Future<T> submitPrio(Callable<T> callable, int prio) {
        PrioTask<T> task = new PrioTask<>(callable, prio, TASK_SEQ.incrementAndGet());
        executor.execute(task);
        return task;
    }

    /* ---------------- 初始化 ---------------- */

    public void init() throws Exception {
        submit(() -> {
            createCtx();
            loadScript();
            awaitInited();
            readMeta();
            return null;
        }).get(30, TimeUnit.SECONDS);
    }

    private void createCtx() {
        try {
            QuickJSLoader.init();
        } catch (Throwable ignored) {
        }
        ctx = QuickJSContext.create();
        // 必须给 wrapper 的 console 设 stdout：不设时脚本顶层 console.log 直接抛
        // QuickJSException（console.stdout 未设置），整脚本求值失败、导入被拒
        // （全豆要 9.3 顶层就有 console.log，1.3.59 真机实证）。与点播 Spider 同口径。
        ctx.setConsole(new Console());
        // 全局包装只取一份：逐个各取一份用完不放，建桥一次漏八个。
        // 注册全走这一份，完事 release 本地包装（属性由全局对象自持）；
        // createCtx 跑在桥自己的执行线程上，release 线程口径合规。
        JSObject globalObj = ctx.getGlobalObject();
        try {
            globalObj.setProperty("__lxSend", (JSCallFunction) args -> {
                try {
                    String event = args.length > 0 ? String.valueOf(args[0]) : "";
                    String json = args.length > 1 ? String.valueOf(args[1]) : "null";
                    onSend(event, json);
                } catch (Throwable ignored) {
                }
                return null;
            });
            globalObj.setProperty("__lxRequest", (JSCallFunction) args -> {
                try {
                    String url = args.length > 0 ? String.valueOf(args[0]) : "";
                    String opts = args.length > 1 ? String.valueOf(args[1]) : "{}";
                    JSONObject out = new JSONObject();
                    try {
                        out.put("resp", nativeRequest(url, opts));
                    } catch (Throwable t) {
                        JSONObject err = new JSONObject();
                        err.put("msg", String.valueOf(t.getMessage()));
                        out.put("err", err);
                    }
                    return out.toString();
                } catch (Throwable t) {
                    return "{\"err\":{\"msg\":" + JSONObject.quote(String.valueOf(t.getMessage())) + "}}";
                }
            });
            // 统一登录通路（与 FreeJsBridge 同型）：只认本桥脚本头部声明的 loginProvider
            // （闭包绑定，脚本传不了参、选不了提供方）；未声明时恒空转。
            globalObj.setProperty("__lxLoginCookie", (JSCallFunction) args -> {
                try {
                    if (loginProvider.isEmpty()) return "";
                    String cookie = LoginManager.getCookie(loginProvider);
                    return cookie == null ? "" : cookie;
                } catch (Throwable t) {
                    return "";
                }
            });
            globalObj.setProperty("__lxLoginRequest", (JSCallFunction) args -> {
                try {
                    if (loginProvider.isEmpty()) return false;
                    if (LoginManager.isLoggedIn(loginProvider)) return true;
                    return LoginManager.requestLogin(loginProvider);
                } catch (Throwable t) {
                    return false;
                }
            });
            globalObj.setProperty("__lxMd5", (JSCallFunction) args ->
                    md5Hex(args.length > 0 ? String.valueOf(args[0]) : ""));
            globalObj.setProperty("__lxAes", (JSCallFunction) args -> {
                String data = args.length > 0 ? String.valueOf(args[0]) : "";
                String mode = args.length > 1 ? String.valueOf(args[1]) : "aes-128-cbc";
                String key = args.length > 2 ? String.valueOf(args[2]) : "";
                String iv = args.length > 3 ? String.valueOf(args[3]) : "";
                return aesEncrypt(data, mode, key, iv);
            });
            globalObj.setProperty("__lxRsa", (JSCallFunction) args -> {
                String data = args.length > 0 ? String.valueOf(args[0]) : "";
                String pem = args.length > 1 ? String.valueOf(args[1]) : "";
                return rsaEncrypt(data, pem);
            });
            globalObj.setProperty("__lxRandom", (JSCallFunction) args -> {
                int n = args.length > 0 && args[0] instanceof Number ? ((Number) args[0]).intValue() : 0;
                byte[] buf = new byte[Math.max(0, Math.min(4096, n))];
                new SecureRandom().nextBytes(buf);
                return Base64.encodeToString(buf, Base64.NO_WRAP);
            });
        } finally {
            globalObj.release();
        }
        // 元信息（垫片据此组 currentScriptInfo）
        JsonObject meta = new JsonObject();
        meta.addProperty("version", LX_VERSION);
        meta.addProperty("name", metaName);
        meta.addProperty("scriptVersion", metaVersion);
        meta.addProperty("author", metaAuthor);
        meta.addProperty("description", headerTag(scriptJs, "description"));
        meta.addProperty("homepage", headerTag(scriptJs, "homepage"));
        meta.addProperty("rawScript", scriptJs);
        ctx.evaluate("__lxMeta = " + meta.toString() + ";");
        ctx.evaluate(Asset.read("js/lib/lx.js"));
    }

    private void loadScript() {
        ctx.evaluate(scriptJs);
        try {
            // 两份临时包装都当场收口：全局包装取完属性即 release；handlers
            // 只用来提取 request 函数本体（本体由 requestHandler 持有、
            // destroy 收口），提取完 handlers 包装这一份即 release。
            JSObject globalObj = ctx.getGlobalObject();
            try {
                Object raw = ctx.getProperty(globalObj, "__lxHandlers");
                JSObject handlers = raw instanceof JSObject ? (JSObject) raw : null;
                if (handlers != null) {
                    try {
                        requestHandler = handlers.getJSFunction("request");
                    } finally {
                        handlers.release();
                    }
                }
            } finally {
                globalObj.release();
            }
        } catch (Throwable ignored) {
        }
    }

    /** inited 一般在脚本求值时同步发出；个别脚本在微任务里发，轮询等一会儿 */
    private void awaitInited() throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (!inited && System.currentTimeMillis() < deadline) {
            if (!initError.isEmpty()) throw new IllegalStateException(initError);
            try {
                ctx.evaluate("0");
            } catch (Throwable ignored) {
            }
            Thread.sleep(50);
        }
        if (!initError.isEmpty()) throw new IllegalStateException(initError);
        if (!inited) throw new IllegalStateException("脚本初始化超时（没有上报源信息）");
        if (requestHandler == null) throw new IllegalStateException("脚本没有注册解析入口");
        if (sources.isEmpty()) throw new IllegalStateException("脚本没有声明任何源");
    }

    private void onSend(String event, String json) {
        if (!"inited".equals(event)) {
            if ("updateAlert".equals(event)) {
                SpiderDebug.log("lx", "updateAlert site=%s %s", site.name, json);
            }
            return;
        }
        try {
            JsonObject data = JsonParser.parseString(json).getAsJsonObject();
            if (data.has("status") && !data.get("status").getAsBoolean()) {
                initError = "脚本初始化失败" + (data.has("msg") ? "：" + data.get("msg").getAsString() : "");
                inited = true;
                return;
            }
            sources.clear();
            if (data.has("sources") && data.get("sources").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : data.getAsJsonObject("sources").entrySet()) {
                    if (!e.getValue().isJsonObject()) continue;
                    JsonObject o = e.getValue().getAsJsonObject();
                    LxSource s = new LxSource();
                    s.name = optStr(o, "name");
                    s.type = optStr(o, "type");
                    if (o.has("actions") && o.get("actions").isJsonArray()) {
                        for (JsonElement a : o.get("actions").getAsJsonArray()) {
                            if (a.isJsonPrimitive()) s.actions.add(a.getAsString());
                        }
                    }
                    if (o.has("qualitys") && o.get("qualitys").isJsonArray()) {
                        for (JsonElement q : o.get("qualitys").getAsJsonArray()) {
                            if (q.isJsonPrimitive()) s.qualitys.add(q.getAsString());
                        }
                    }
                    sources.put(e.getKey(), s);
                }
            }
        } catch (Throwable t) {
            initError = "脚本源信息解析失败：" + t.getMessage();
        }
        inited = true;
    }

    /** 元信息回填 site：名字/版本优先脚本头部 @name/@version */
    private void readMeta() {
        if (!TextUtils.isEmpty(metaName)) site.name = metaName;
        if (!TextUtils.isEmpty(metaVersion)) site.version = metaVersion;
        if (!TextUtils.isEmpty(metaAuthor)) site.author = metaAuthor;
        site.connected = true;
    }

    /* ---------------- 对外能力 ---------------- */

    /** 脚本声明的源（平台 key → 名称/动作/音质档），顺序即声明顺序 */
    public Map<String, LxSource> sources() {
        return new LinkedHashMap<>(sources);
    }

    public boolean supports(String platformKey, String action) {
        LxSource s = sources.get(platformKey);
        return s != null && s.actions.contains(action);
    }

    /**
     * 调脚本解析：action=musicUrl/lyric/pic，info 里同时给 type 与 quality（老新脚本都认）。
     * 返回脚本 Promise 的兑现值（字符串原样、对象转 JSON）；失败抛异常。
     */
    public String request(String source, String action, JsonObject musicInfo, String quality) throws Exception {
        // 登录提示只挂取链/取词两个用户动作入口（与 FreeJsBridge 同口径）：声明了登录
        // 提供方且未登录时节流弹壳子统一登录框；弹与不弹都不拦本次调用，未登录照常匿名跑。
        if ("musicUrl".equals(action) || "lyric".equals(action)) maybePromptLogin();
        java.util.concurrent.Future<String> bridgeFuture = null;
        try {
            // 取链走高优先、取词/取图维持原序（见 executor 注释）
            java.util.concurrent.Callable<String> bridgeCall = () -> {
                if (requestHandler == null) throw new IllegalStateException("脚本没有注册解析入口");
                JsonObject info = new JsonObject();
                info.add("musicInfo", musicInfo);
                info.addProperty("type", quality);
                info.addProperty("quality", quality);
                JsonObject arg = new JsonObject();
                arg.addProperty("source", source);
                arg.addProperty("action", action);
                arg.add("info", info);
                Object argObj = ctx.parse(arg.toString());
                Object result = requestHandler.call(argObj);
                return awaitPromise(result);
            };
            bridgeFuture = "musicUrl".equals(action) ? submitHigh(bridgeCall) : submit(bridgeCall);
            // P3：40 秒内层上限到点必须取消桥任务，不只放弃等待——否则桥单线程一直被
            // 挂死的脚本占着，后续同插件请求全排队（取消发中断信号，脚本内网络等待会响应中断；
            // 脚本本身死循环则仍占桥线程至其返回，这是单线程桥的固有限制，见交卷清单）
            try {
                return bridgeFuture.get(40, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                bridgeFuture.cancel(true);
                throw te;
            } catch (InterruptedException ie) {
                // 外层放弃等待（单插件上限到点 cancel 了等链的线程）时必须连桥任务
                // 一起取消：桥是单线程执行器，不取消的话这条慢链继续在桥线程上
                // 跑满全链，后面每首歌的请求都排在它后面、被外层逐个砍死
                //（2026-10-26 真机实证：一条慢链堵桥约 2 分钟、kg 连灭三首）。
                // 中断落到桥线程后，awaitPromise 的等待会立即抛断、nativeRequest
                // 也至多再跑完手头这一发就收，不再往下发新请求。
                bridgeFuture.cancel(true);
                throw ie;
            }
        } catch (java.util.concurrent.ExecutionException e) {
            // B4：脚本拒绝消息带显式 401／403 标记时包成密钥问题错误向上传递；
            // 其余失败（超时、断网、脚本内部错误）原样抛，不冒充密钥问题
            Throwable cause = e.getCause();
            String msg = cause == null ? null : cause.getMessage();
            int code = LxKeyIssue.detectStatusCode(msg);
            if (code > 0) throw new LxKeyIssue(source, msg, code);
            throw e;
        }
    }

    /** 未登录自动弹框（节流见 LOGIN_PROMPT_AT）：口径与 FreeJsBridge.maybePromptLogin 一字不差 */
    private void maybePromptLogin() {
        try {
            if (loginProvider.isEmpty()) return;
            if (LoginManager.isLoggedIn(loginProvider)) return;
            long now = System.currentTimeMillis();
            Long last = LOGIN_PROMPT_AT.get(loginProvider);
            if (last != null && now - last < LOGIN_PROMPT_INTERVAL_MS) return;
            LOGIN_PROMPT_AT.put(loginProvider, now);
            LoginManager.requestLogin(loginProvider);
        } catch (Throwable ignored) {
        }
    }

    /** 挂 then/catch 等 Promise 兑现（参照 quickjs Async 的做法，在桥线程内同步等） */
    private String awaitPromise(Object result) throws Exception {
        if (result == null) return null;
        if (!(result instanceof JSObject)) return String.valueOf(result);
        JSObject promise = (JSObject) result;
        JSFunction then = promise.getJSFunction("then");
        if (then == null) {
            // 扫查待裁 23：promise 包装由本次调用独占，stringify 后即 release
            // （与 quickjs Async.then 的既定纪律同口径，不靠 ctx 销毁兜底）
            try {
                return promise.stringify();
            } finally {
                try {
                    promise.release();
                } catch (Throwable ignored) {
                }
            }
        }
        CompletableFuture<Object> future = new CompletableFuture<>();
        JSCallFunction ok = args -> {
            future.complete(args != null && args.length > 0 ? args[0] : null);
            return null;
        };
        JSCallFunction fail = args -> {
            String msg = args != null && args.length > 0 && args[0] != null ? stringifyErr(args[0]) : "解析失败";
            future.completeExceptionally(new Exception(msg));
            return null;
        };
        JSFunction catchFn = null;
        try {
            then.call(ok);
            catchFn = promise.getJSFunction("catch");
            if (catchFn != null) catchFn.call(fail);
            // 桥单线程串行：请求回调的兑现是微任务，轮询泵队列直到兑现或超时
            // 原来等 90 秒：脚本背后的请求单次已被钳在 ≤60 秒，壳子侧再叠 90 秒
            // 会让一次取链最坏卡两分钟，搜索/播歌都像死机；30 秒到点按失败抛。
            Object v = null;
            long deadline = System.currentTimeMillis() + 30000;
            while (true) {
                try {
                    v = future.get(150, TimeUnit.MILLISECONDS);
                    break;
                } catch (java.util.concurrent.TimeoutException te) {
                    if (System.currentTimeMillis() >= deadline) throw new Exception("脚本解析超时");
                    try {
                        ctx.evaluate("0");
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (v == null) return null;
            if (v instanceof JSObject) {
                // 扫查待裁 23：兑现值包装归本次调用消费，stringify 后即 release
                try {
                    return ((JSObject) v).stringify();
                } finally {
                    try {
                        ((JSObject) v).release();
                    } catch (Throwable ignored) {
                    }
                }
            }
            return String.valueOf(v);
        } finally {
            try {
                then.release();
            } catch (Throwable ignored) {
            }
            if (catchFn != null) {
                try {
                    catchFn.release();
                } catch (Throwable ignored) {
                }
            }
            try {
                promise.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private String stringifyErr(Object o) {
        try {
            if (o instanceof JSObject) {
                String s = ((JSObject) o).stringify();
                if (!TextUtils.isEmpty(s) && !"{}".equals(s)) return s;
            }
        } catch (Throwable ignored) {
        }
        return String.valueOf(o);
    }

    public void destroy() {
        try {
            // 拆桥走高优先：别在排队的取词后面干等（后面还有 5 秒兜底与 shutdownNow）
            submitHigh(() -> {
                if (requestHandler != null) {
                    try {
                        requestHandler.release();
                    } catch (Throwable ignored) {
                    }
                }
                if (ctx != null) {
                    try {
                        ctx.destroy();
                    } catch (Throwable ignored) {
                    }
                }
                return null;
            }).get(5, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
        executor.shutdownNow();
    }

    /* ---------------- 宿主网络（脚本 lx.request 出口） ---------------- */

    /**
     * 同步宿主请求（与 FreeJS 桥同出口模型）：直接在桥线程走壳子 OkHttp，
     * 不直连、不另开回调线程，避免单线程桥被自己的异步回调堵死。
     * resp 语义按落雪 FAQ：{statusCode, headers（键小写）, body（JSON 响应自动解析为对象）}。
     */
    private JSONObject nativeRequest(String url, String optsJson) throws Exception {
        JSONObject opts = new JSONObject(optsJson);
        Req req = Req.objectFrom(buildReqJson(opts));
        // 节点定向接线（与 FreeJsBridge.nativeRequest 同口径，2026-10-26 油管落雪形态补）：
        // 桥是落雪脚本唯一的网络出口，在此给当前线程打源归属点（kind=music、id=srcUrl），
        // 壳子 OkHttp 的 SourceGate 选择器才看得见这是哪个音乐源的请求。此前落雪侧从不
        // 打点：油管落雪脚本的请求一律 gate direct reason=no-mark、直连挂死。策略表里
        // 没有条目的落雪源 isEnabled 恒 false，ensureNode 不拉内核、打点只多一条决策
        // 留痕、路由不变，老脚本零影响。
        ensureNodeForMusic();
        SourceGate.Mark prev = SourceGate.push(SourceProxyPolicy.KIND_MUSIC, key());
        try (Response res = Connect.to(url, req).execute()) {
            byte[] bytes = res.body() != null ? res.body().bytes() : new byte[0];
            JSONObject out = new JSONObject();
            out.put("statusCode", res.code());
            JSONObject headers = new JSONObject();
            StringBuilder cookieJoin = new StringBuilder();
            for (Map.Entry<String, List<String>> e : res.headers().toMultimap().entrySet()) {
                String lk = e.getKey().toLowerCase();
                List<String> vals = e.getValue();
                if (vals == null || vals.isEmpty()) continue;
                if ("set-cookie".equals(lk)) {
                    for (String v : vals) {
                        if (cookieJoin.length() > 0) cookieJoin.append("; ");
                        cookieJoin.append(v);
                    }
                    headers.put("set-cookie", cookieJoin.toString());
                } else {
                    headers.put(lk, vals.get(vals.size() - 1));
                }
            }
            if (cookieJoin.length() > 0) headers.put("cookie", cookieJoin.toString());
            out.put("headers", headers);
            String text = new String(bytes, req.getCharset());
            String ct = headers.optString("content-type", "");
            Object body = text;
            if (ct.contains("json") || looksJson(text)) {
                try {
                    Object parsed = parseJson(text);
                    if (parsed != null) body = parsed;
                } catch (Throwable ignored) {
                }
            }
            out.put("body", body);
            return out;
        } finally {
            SourceGate.pop(prev);
        }
    }

    /**
     * 本源在节点策略里开了走节点、而内核还没拉起时，先把内核拉起来再发请求
     * （FreeJsBridge.ensureNodeForMusic 同款口径：单源开了即生效，不要求总开关先开）。
     * 拉起失败不堵请求：照常发（门控会判直连），但留一条 clash 日志说明本次为什么
     * 没走成节点，不许静默直连挂死。
     */
    private void ensureNodeForMusic() {
        try {
            String id = key();
            if (id.isEmpty()) return;
            App app = App.get();
            if (app == null) return;
            if (!SourceProxyPolicy.isEnabled(app, SourceProxyPolicy.KIND_MUSIC, id)) return;
            if (MihomoManager.get().isRunning()) return;
            long now = System.currentTimeMillis();
            if (now - nodeStartFailedAt < NODE_START_RETRY_MS) return;
            if (!MihomoManager.get().ensureRunning(app)) {
                nodeStartFailedAt = now;
                SpiderDebug.log("clash", "gate music node-start-failed id=%s （内核没拉起来，本次请求直连）", id);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean looksJson(String text) {
        if (text == null) return false;
        String t = text.trim();
        return t.startsWith("{") || t.startsWith("[");
    }

    /** 把响应文本解析成 org.json 对象/数组，供塞进 resp.body */
    private Object parseJson(String text) throws Exception {
        String t = text.trim();
        if (t.startsWith("[")) return new JSONArray(t);
        return new JSONObject(t);
    }

    /**
     * lx.request 的 options → quickjs 模块 Req 的 JSON：
     * method 归一（Connect 只认 GET/POST/HEAD，其余按 POST）、headers 透传、
     * body（字符串/已由垫片转成 base64 时解码回字节串）、form 转 urlencoded 体、timeout 钳 5–60 秒。
     */
    private String buildReqJson(JSONObject opts) {
        JSONObject req = new JSONObject();
        try {
            String m = opts.optString("method", "GET").toUpperCase();
            if (!"GET".equals(m) && !"POST".equals(m) && !"HEAD".equals(m)) m = "POST";
            req.put("method", m);
            JSONObject hs = opts.optJSONObject("headers");
            if (hs != null) req.put("headers", hs);
            String body = null;
            if (opts.has("form") && opts.opt("form") instanceof JSONObject) {
                body = formEncode(opts.getJSONObject("form"));
                if (hs == null || !hasHeader(hs, "content-type")) {
                    JSONObject h2 = hs == null ? new JSONObject() : hs;
                    h2.put("Content-Type", "application/x-www-form-urlencoded");
                    req.put("headers", h2);
                }
                if ("GET".equals(m)) req.put("method", "POST");
            } else if (!opts.isNull("body")) {
                Object b = opts.opt("body");
                body = b instanceof JSONObject || b instanceof JSONArray ? b.toString() : opts.optString("body", "");
                if (opts.optBoolean("__lxBodyB64", false)) {
                    try {
                        body = new String(Base64.decode(body, Base64.DEFAULT), StandardCharsets.ISO_8859_1);
                    } catch (Exception ignored) {
                    }
                }
            }
            if (body != null) req.put("body", body);
            long timeout = opts.optLong("timeout", 20000);
            // 下限只兜 1 秒防呆，不许再往上钳：旧下限 5000 把脚本自带的 2 秒超时
            // 放大 2.5 倍——垫片请求是同步串行的，聚合脚本多后端顺序回退时每个
            // 死后端多烧 3 秒，整链必爆单插件上限（2026-10-26 真机全灭＋本机复现实证）。
            // 落雪口径是脚本给多少就用多少，这里只防 0／负数。
            req.put("timeout", (int) Math.max(1000, Math.min(60000, timeout)));
            req.put("redirect", 1);
        } catch (Exception e) {
            try {
                req.put("method", "GET");
            } catch (Exception ignored) {
            }
        }
        return req.toString();
    }

    private boolean hasHeader(JSONObject hs, String name) {
        java.util.Iterator<String> it = hs.keys();
        while (it.hasNext()) {
            if (name.equalsIgnoreCase(it.next())) return true;
        }
        return false;
    }

    private String formEncode(JSONObject form) throws Exception {
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = form.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(k, "UTF-8"));
            sb.append('=');
            sb.append(URLEncoder.encode(String.valueOf(form.opt(k)), "UTF-8"));
        }
        return sb.toString();
    }

    /* ---------------- utils.crypto 原生实现 ---------------- */

    private String md5Hex(String str) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(str.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * AES 加密（官方 utils.crypto.aesEncrypt 语义）：mode 形如 aes-128-ecb/aes-256-cbc，
     * 也容忍只给 ecb/cbc。入参皆 base64 字节，返回 base64 密文；PKCS5/7 填充。
     */
    private String aesEncrypt(String dataB64, String mode, String keyB64, String ivB64) {
        try {
            byte[] data = Base64.decode(dataB64, Base64.DEFAULT);
            byte[] key = Base64.decode(keyB64, Base64.DEFAULT);
            byte[] iv = Base64.decode(ivB64, Base64.DEFAULT);
            String m = mode == null ? "" : mode.toLowerCase();
            String transformation = m.contains("cbc") ? "AES/CBC/PKCS5Padding" : "AES/ECB/PKCS5Padding";
            Cipher cipher = Cipher.getInstance(transformation);
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            if (transformation.contains("CBC")) {
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(iv));
            } else {
                cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            }
            return Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP);
        } catch (Exception e) {
            SpiderDebug.log("lx", "aesEncrypt error %s", String.valueOf(e.getMessage()));
            return "";
        }
    }

    /** RSA 加密（官方 utils.crypto.rsaEncrypt 语义）：PEM 公钥、PKCS1 v1.5 填充 */
    private String rsaEncrypt(String dataB64, String pem) {
        try {
            byte[] data = Base64.decode(dataB64, Base64.DEFAULT);
            String b64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace("-----BEGIN RSA PUBLIC KEY-----", "")
                    .replace("-----END RSA PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.decode(b64, Base64.DEFAULT);
            PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, pub);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int block = 117;
            for (int off = 0; off < data.length; off += block) {
                int len = Math.min(block, data.length - off);
                out.write(cipher.doFinal(data, off, len));
            }
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            SpiderDebug.log("lx", "rsaEncrypt error %s", String.valueOf(e.getMessage()));
            return "";
        }
    }

    /* ---------------- 小工具 ---------------- */

    private String optStr(JsonObject obj, String name) {
        try {
            if (obj.has(name) && !obj.get(name).isJsonNull()) return obj.get(name).getAsString();
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 脚本声明的单个源信息 */
    public static class LxSource {
        public String name = "";
        public String type = "";
        public final List<String> actions = new ArrayList<>();
        public final List<String> qualitys = new ArrayList<>();
    }
}
