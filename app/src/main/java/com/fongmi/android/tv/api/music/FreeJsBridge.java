package com.fongmi.android.tv.api.music;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicMediaSource;
import com.fongmi.android.tv.bean.music.MusicQuality;
import com.fongmi.android.tv.bean.music.MusicSearchResult;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.clash.MihomoManager;
import com.fongmi.android.tv.clash.SourceProxyPolicy;
import com.fongmi.android.tv.login.LoginManager;
import com.fongmi.quickjs.bean.Req;
import com.fongmi.quickjs.method.Console;
import com.fongmi.quickjs.utils.Async;
import com.fongmi.quickjs.utils.Connect;
import com.fongmi.android.tv.music.MusicQueue;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.SourceGate;
import com.github.catvod.utils.Asset;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.whl.quickjs.android.QuickJSLoader;
import com.whl.quickjs.wrapper.JSCallFunction;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Response;

/**
 * FreeJS（MusicFree 插件）桥：一期音乐源引擎。
 * 每个插件独占一个 QuickJSContext（单线程串行），新 loader 分支，不走点播 Spider。
 * 垫片见 quickjs 模块 assets/js/lib/freejs.js（CommonJS + fetch/axios/he/dayjs 最小实现，
 * crypto-js / cheerio 由宿主注入原文）。
 * 插件网络经 __freejsRequest 同步走壳子 OkHttp，吃代理/DoH/节点定向，不直连。
 */
public class FreeJsBridge {

    /**
     * 桥调用总上限：插件里是多次串行同步 HTTP（单请求已被 Connect 钳在约 10 秒），
     * 但桥这一层原来 .get() 死等无上限——插件循环重试/源挂死就能把调用线程永久顶住，
     * 搜索结果永远不回、取链永远不落地（1.3.58 真机实证）。到点按失败抛给上层收口。
     */
    private static final long CALL_TIMEOUT_MS = 30000;

    /** 取链（getMediaSource）单步上限：起播路径总预算 20 秒，这里压到 15 秒留出余量 */
    private static final long SOURCE_TIMEOUT_MS = 15000;

    /**
     * 桥内单线程（QuickJS 上下文只能串行）改优先队列：取链（getMediaSource）的
     * 提交优先于取词/搜索——点歌受理那一刻，服务侧与页面侧的取词同刻涌进同一
     * 座桥排队，取链若老实排在慢取词后面（酷我取词接口间歇回空还要整段重试），
     * 点→声白白多等几秒，重启首播时桥刚建好、队列最挤、最明显。优先只调排队
     * 顺序，不抢占正在跑的那条（JS 不可抢占，是单线程桥的固有限制）。
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
    private final String pluginJs;

    /**
     * 插件声明需要的壳子统一登录提供方 id（插件导出 loginProvider，如油管音乐的
     * "google"）；空＝不依赖登录。只存桥内、不回填 site：与 srcUrl 同口径，
     * 插件自报值不许污染壳子的身份/落盘数据，它只是能力声明。
     */
    private String loginProvider = "";

    /**
     * 未登录自动弹框的进程内节流（按提供方）：搜索排/歌词兜底链会反复进桥，
     * 没有节流就是连环弹窗扰用户；10 分钟内至多自动弹一次，用户关掉没登
     * 就先按匿名跑，脚本也可经 __freejsLoginRequest 主动再请。
     */
    private static final long LOGIN_PROMPT_INTERVAL_MS = 10 * 60 * 1000L;
    private static final java.util.Map<String, Long> LOGIN_PROMPT_AT = new java.util.concurrent.ConcurrentHashMap<>();

    private QuickJSContext ctx;
    private JSObject exports;

    /**
     * setTimeout 钩子的计时面（垫片第 8 节的真实现）：与点播 Global.setTimeout
     * 同一机制——java.util.Timer 计时、JSFunction 先 hold、到点把回调投递回
     * 桥单线程执行、执行后 release。只经 __freejsSetTimeout/__freejsClearTimeout
     * 两个窄钩子暴露，不把 Global 注册进本上下文：它会把整套点播方法面
     * （req/pdfh/md5X…）带进 FreeJS，且它的 req 绕开本桥带 SourceGate 标记的
     * 唯一网络出口 __freejsRequest。destroyed 后不再接新定时器、已排队的
     * 回调直接收口不进 ctx。
     */
    private final Timer timer = new Timer("freejs-timer", true);
    private final AtomicInteger timerSeq = new AtomicInteger();
    private final Map<Integer, TimerEntry> timers = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean destroyed;

    private static final class TimerEntry {
        final JSFunction func;
        volatile TimerTask task;

        TimerEntry(JSFunction func) {
            this.func = func;
        }
    }

    /**
     * cheerio 按需注入（提速⑨）：cheerio 原文 488KB、冷建桥求值约 0.6 秒量级，
     * 旧实现每桥无条件注入——元力 KW／油管等根本不用的插件白付这笔。
     * 判据：插件源码含 cheerio 关键词（静态判）＋保守白名单（歌词网／歌词千寻
     * 两个已知需要的预置歌词插件，按预置地址认）。误判兜底在 init：未注入时
     * 若初始化失败且错误链提到 cheerio，强制注入重建一次（见 init）。
     */
    private final boolean cheerioWanted;
    private boolean cheerioInjected;
    private boolean forceCheerio;

    public FreeJsBridge(MusicSite site, String pluginJs) {
        this.site = site;
        this.pluginJs = pluginJs;
        this.cheerioWanted = pluginJs != null && pluginJs.toLowerCase().contains("cheerio")
                || (site != null && MusicSourceManager.isBuiltinLyricPreset(site.srcUrl));
    }

    private static boolean mentionsCheerio(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String m = cur.getMessage();
            if (m != null && m.toLowerCase().contains("cheerio")) return true;
        }
        return false;
    }

    public MusicSite getSite() {
        return site;
    }

    /** 插件 key：用 srcUrl 标识，源管理按它找桥 */
    public String key() {
        return site.srcUrl == null ? "" : site.srcUrl;
    }

    private <T> Future<T> submit(Callable<T> callable) {
        return submitPrio(callable, PRIO_NORMAL);
    }

    /** 取链专用高优先提交：排在已排队的取词/搜索前面（正在跑的那条不抢占） */
    private <T> Future<T> submitHigh(Callable<T> callable) {
        return submitPrio(callable, PRIO_HIGH);
    }

    private <T> Future<T> submitPrio(Callable<T> callable, int prio) {
        PrioTask<T> task = new PrioTask<>(callable, prio, TASK_SEQ.incrementAndGet());
        executor.execute(task);
        return task;
    }

    public void init() throws Exception {
        submit(() -> {
            try {
                createCtx();
                loadPlugin();
                readMeta();
            } catch (Throwable first) {
                // cheerio 误判兜底（提速⑨）：静态判没判出需要、插件跑起来才发现缺
                // cheerio 时，强制全量注入重建一次；其它失败照原样抛，不许拿重试掩盖真因
                if (cheerioInjected || !mentionsCheerio(first)) {
                    if (first instanceof Exception) throw (Exception) first;
                    throw new Exception(first);
                }
                SpiderDebug.log("music", "freejs cheerio fallback rebuild site=%s", site.name);
                forceCheerio = true;
                cancelAllTimeouts();
                try {
                    if (ctx != null) ctx.destroy();
                } catch (Throwable ignored) {
                }
                ctx = null;
                exports = null;
                createCtx();
                loadPlugin();
                readMeta();
            }
            return null;
        }).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void createCtx() {
        try {
            QuickJSLoader.init();
        } catch (Throwable ignored) {
        }
        ctx = QuickJSContext.create();
        // 同 LxBridge：不设 console stdout 时，插件顶层 console.log 会抛
        // QuickJSException 让整插件求值失败，先把出口设上（垫片自己的
        // console 定义有 !global.console 守卫，wrapper console 已存在时不会覆盖）。
        ctx.setConsole(new Console());
        // 全局包装是上下文内部缓存的同一份（QuickJSContext 自持），不是每次
        // 新取的临时副本：只取来注册，绝不 release——放掉会把全上下文共用的
        // 包装 refCount 打到 0 并 native 多 free 一次（1.4.57 音乐进首页闪退
        // 定根，见 healthcheck-20261029/music-crash-triage.md）。
        JSObject globalObj = ctx.getGlobalObject();
        globalObj.setProperty("__freejsRequest", (JSCallFunction) args -> {
            try {
                String method = args.length > 0 ? String.valueOf(args[0]) : "GET";
                String url = args.length > 1 ? String.valueOf(args[1]) : "";
                String opts = args.length > 2 ? String.valueOf(args[2]) : "{}";
                return nativeRequest(method, url, opts);
            } catch (Throwable t) {
                return "{\"error\":" + JSONObject.quote(String.valueOf(t.getMessage())) + "}";
            }
        });
        // 统一登录通路：只认本桥插件声明的 loginProvider（闭包绑定，脚本传不了参、
        // 选不了提供方）——A 插件声明谷歌只能拿到谷歌的托管 Cookie，读不到别家的，
        // 更拿不到没声明登录的插件手里。未声明时两个函数都是空转，老插件零影响。
        globalObj.setProperty("__freejsLoginCookie", (JSCallFunction) args -> {
            try {
                if (loginProvider.isEmpty()) return "";
                String cookie = LoginManager.getCookie(loginProvider);
                return cookie == null ? "" : cookie;
            } catch (Throwable t) {
                return "";
            }
        });
        globalObj.setProperty("__freejsLoginRequest", (JSCallFunction) args -> {
            try {
                if (loginProvider.isEmpty()) return false;
                if (LoginManager.isLoggedIn(loginProvider)) return true;
                return LoginManager.requestLogin(loginProvider);
            } catch (Throwable t) {
                return false;
            }
        });
        globalObj.setProperty("__freejsLog", (JSCallFunction) args -> {
            try {
                String lv = args.length > 0 ? String.valueOf(args[0]) : "I";
                String msg = args.length > 1 ? String.valueOf(args[1]) : "";
                SpiderDebug.log("freejs-" + lv, "%s", msg);
            } catch (Throwable ignored) {
            }
            return null;
        });
        // setTimeout 真实现（垫片第 8 节）：只收函数与延时，其余参数已由垫片
        // 打包进回调闭包。钩子本体跑在桥线程（JS 调用点），登记即 hold。
        globalObj.setProperty("__freejsSetTimeout", (JSCallFunction) args -> {
            try {
                if (args == null || args.length == 0 || !(args[0] instanceof JSFunction)) return 0;
                long delay = 0;
                if (args.length > 1 && args[1] instanceof Number) delay = Math.max(0L, ((Number) args[1]).longValue());
                return scheduleTimeout((JSFunction) args[0], delay);
            } catch (Throwable t) {
                return 0;
            }
        });
        globalObj.setProperty("__freejsClearTimeout", (JSCallFunction) args -> {
            try {
                if (args != null && args.length > 0 && args[0] instanceof Number) cancelTimeout(((Number) args[0]).intValue());
            } catch (Throwable ignored) {
            }
            return null;
        });
        ctx.evaluate(Asset.read("js/lib/freejs.js"));
        injectLib("crypto-js", Asset.read("js/lib/crypto-js.js"));
        // cheerio 按需（提速⑨，判据与兜底见 cheerioWanted 字段注）
        cheerioInjected = cheerioWanted || forceCheerio;
        if (cheerioInjected) injectLib("cheerio", Asset.read("js/lib/cheerio.cjs.js"));
    }

    /** 把 UMD/CJS 库原文包进 CommonJS 定义，供插件 require */
    private void injectLib(String name, String src) {
        if (TextUtils.isEmpty(src)) return;
        ctx.evaluate("(function(){\nvar __m={exports:{}};\n"
                + "(function(require,module,exports){\n" + src + "\n})(require,__m,__m.exports);\n"
                + "__freejsDefine(\"" + name + "\",function(require,module){module.exports=__m.exports;});\n})();");
    }

    /* ---------------- setTimeout（垫片钩子，机制同点播 Global） ---------------- */

    private int scheduleTimeout(JSFunction func, long delay) {
        if (destroyed) return 0;
        int id = timerSeq.incrementAndGet();
        TimerEntry entry = new TimerEntry(func);
        timers.put(id, entry);
        func.hold();
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                // 计时线程只投递：回调必须回桥单线程执行（ctx 线程亲和）
                try {
                    executor.execute(() -> fireTimeout(id));
                } catch (Throwable t) {
                    // 执行器已关停：条目留在表里等拆桥统一收口；计时线程上不
                    // release（异线程 release 会抛线程亲和异常，随 ctx 销毁
                    // 回收，同 Global 的 postCallback 兜底口径）
                }
            }
        };
        entry.task = task;
        try {
            timer.schedule(task, delay);
        } catch (Throwable t) {
            cancelTimeout(id);
            return 0;
        }
        return id;
    }

    private void fireTimeout(int id) {
        TimerEntry entry = timers.remove(id);
        if (entry == null) return; // 已被 clearTimeout 或拆桥收口
        try {
            if (!destroyed && ctx != null) {
                entry.func.call();
                // 泵一次 job 队列：这类回调多是 Promise 的 resolve，兑现续跑
                // 是微任务（LxBridge awaitPromise 的 evaluate("0") 泵法同款，
                // 兜 wrapper 未在 call 后排空队列的情形）
                try {
                    ctx.evaluate("0");
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                entry.func.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 只在桥线程调用（release 有线程亲和）：JS 的 clearTimeout 钩子与拆桥收口共用 */
    private void cancelTimeout(int id) {
        TimerEntry entry = timers.remove(id);
        if (entry == null) return;
        if (entry.task != null) entry.task.cancel();
        try {
            entry.func.release();
        } catch (Throwable ignored) {
        }
    }

    /** ctx 销毁/重建前先把挂着的定时器全收口（挂着的 JSFunction 属于即将销毁的 ctx） */
    private void cancelAllTimeouts() {
        for (Integer id : new ArrayList<>(timers.keySet())) cancelTimeout(id);
    }

    private void loadPlugin() {
        ctx.evaluate(pluginJs);
        // module/exports 的形态完全由插件 JS 决定：先 instanceof 再转，畸形插件
        // （把 module/exports 赋成字符串/函数等）走「没有导出」的失败路，
        // 不许在建桥时直接 ClassCastException 崩掉（大扫查防崩同型命中）。
        // module 只用来取 exports（exports 本体由字段持有、另有存续期 release
        // 安排），取完 module 包装这一份即 release，失败路也走 finally 不漏；
        // 全局包装是上下文缓存的同一份，只取来读属性，绝不 release（见 createCtx 注）。
        Object module = ctx.getProperty(ctx.getGlobalObject(), "module");
        if (!(module instanceof JSObject)) throw new IllegalStateException("插件没有导出 module.exports");
        try {
            Object exportsObj = ctx.getProperty((JSObject) module, "exports");
            if (!(exportsObj instanceof JSObject)) throw new IllegalStateException("插件没有导出 module.exports");
            exports = (JSObject) exportsObj;
        } finally {
            ((JSObject) module).release();
        }
    }

    private String prop(String name) {
        Object v = null;
        try {
            v = ctx.getProperty(exports, name);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            return "";
        } finally {
            // getProperty 回对象/数组时是 JSObject 包装：用完即 release，
            // 与本文件 supportsMusicSearch 的 finally release 纪律对齐；
            // 回字符串/数字等原始值时不是包装，不放。
            if (v instanceof JSObject) {
                try {
                    ((JSObject) v).release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 读插件元数据回填 site（platform/version/author）。
     * 注意：插件自报的 srcUrl 绝不能回填——site.srcUrl 是壳子给插件的身份键
     * （桥缓存、_plugin 归属戳、增删/启停全按它对齐），插件自报值与安装地址
     * 不是一回事（元力KW 自报 kstore 地址、预置装的是 jsDelivr 地址），
     * 一旦覆写，搜索结果的 _plugin 戳就与源管理里的地址对不上，
     * bridgeForTrack 认不出归属、取链全灭（1.3.59 真机实证 KW 歌播不出来）。
     */
    private void readMeta() {
        String platform = prop("platform");
        String version = prop("version");
        String author = prop("author");
        // 规范身份单独落 pluginPlatform（去重认它，见 MusicSite 字段注）：name 仍按
        // 老口径被自报 platform 覆写作显示名，但身份不再寄生在显名上——用户改名、
        // 镜像址重装都动不了身份判据。
        if (!TextUtils.isEmpty(platform)) site.pluginPlatform = platform;
        if (!TextUtils.isEmpty(platform)) site.name = platform;
        if (!TextUtils.isEmpty(version)) site.version = version;
        if (!TextUtils.isEmpty(author)) site.author = author;
        // 登录声明只进桥内字段（见 loginProvider 字段注释），不落 site
        String lp = prop("loginProvider");
        if (!TextUtils.isEmpty(lp)) loginProvider = lp.trim();
    }

    /**
     * 插件声明了登录提供方且未登录时，请壳子弹统一登录框（节流见 LOGIN_PROMPT_AT）。
     * 只挂在 search / getMediaSource / getLyric 三个用户动作入口上：探测型调用
     * （supportsMusicSearch 等）不弹，免得用户没点这个源也被弹窗找上门。
     * 弹与不弹都不拦本次调用——未登录照旧按匿名跑，登录是用户自己的选择。
     */
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

    public boolean hasFunction(String name) throws Exception {
        return submit(() -> {
            JSFunction f = null;
            try {
                f = exports.getJSFunction(name);
                return f != null;
            } catch (Throwable t) {
                return false;
            } finally {
                if (f != null) {
                    try {
                        f.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 是否支持搜歌（search 的 type=music）：判据是插件自己的声明，不按名字猜。
     * 插件声明了 supportedSearchType 时必须包含 "music" 才算（歌词网/歌词千寻只声明
     * ['lyric']、search 对非 lyric 类型直接返回空，不进搜索排）；未声明的老插件，
     * 有 search 函数即按可搜歌处理。
     */
    public boolean supportsMusicSearch() throws Exception {
        return submit(() -> {
            JSFunction f = null;
            try {
                f = exports.getJSFunction("search");
                if (f == null) return false;
            } catch (Throwable t) {
                return false;
            } finally {
                if (f != null) {
                    try {
                        f.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
            Object declared;
            try {
                declared = ctx.getProperty(exports, "supportedSearchType");
            } catch (Throwable t) {
                declared = null;
            }
            if (declared == null) return true;
            try {
                String json = declared instanceof JSObject ? ((JSObject) declared).stringify() : String.valueOf(declared);
                if (json == null || json.isEmpty() || "undefined".equals(json)) return true;
                return json.contains("\"music\"");
            } finally {
                // 扫查待裁 23：getProperty 的 JSObject 用完即 release（与本文件
                // getJSFunction 的 finally release 纪律对齐，不靠 ctx 销毁兜底）
                if (declared instanceof JSObject) {
                    try {
                        ((JSObject) declared).release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private Object call(String func, Object... args) throws Exception {
        return callTimed(func, CALL_TIMEOUT_MS, args);
    }

    private Object callTimed(String func, long timeoutMs, Object... args) throws Exception {
        return callTimed(func, timeoutMs, false, args);
    }

    /** 取链专用：桥内排队时优先于取词/搜索（见 executor 注释） */
    private Object callTimedHigh(String func, long timeoutMs, Object... args) throws Exception {
        return callTimed(func, timeoutMs, true, args);
    }

    private Object callTimed(String func, long timeoutMs, boolean high, Object... args) throws Exception {
        try {
            return callTimedOnce(func, timeoutMs, high, args);
        } catch (Exception e) {
            // cheerio 运行时误判兜底（提速⑨）：有的插件在 search/getLyric 函数里才
            // require('cheerio')，init 阶段不暴露——调用报错且提到 cheerio、而本桥
            // 没注入时，强制注入重建一次再重试本调用一次；仍失败照原样抛。
            if (cheerioInjected || !mentionsCheerio(e)) throw e;
            SpiderDebug.log("music", "freejs cheerio runtime fallback func=%s site=%s", func, site.name);
            rebuildWithCheerio();
            return callTimedOnce(func, timeoutMs, high, args);
        }
    }

    /** 强制注入 cheerio 重建上下文（桥线程内执行，与 init 兜底同口径） */
    private void rebuildWithCheerio() throws Exception {
        submit(() -> {
            forceCheerio = true;
            cancelAllTimeouts();
            try {
                if (exports != null) exports.release();
            } catch (Throwable ignored) {
            }
            try {
                if (ctx != null) ctx.destroy();
            } catch (Throwable ignored) {
            }
            ctx = null;
            exports = null;
            createCtx();
            loadPlugin();
            readMeta();
            return null;
        }).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private Object callTimedOnce(String func, long timeoutMs, boolean high, Object... args) throws Exception {
        // P3：两段等待到点都必须取消底层任务，不只放弃等待——调用线程（音乐专用池）
        // 不许被挂死的插件拖住；桥是单线程，取消发中断信号，插件内同步网络等待会响应，
        // 插件本身死循环则仍占桥线程至其返回（单线程桥的固有限制，见交卷清单）
        Future<CompletableFuture<Object>> outer = high
                ? submitHigh(() -> Async.run(exports, func, args))
                : submit(() -> Async.run(exports, func, args));
        CompletableFuture<Object> stage;
        try {
            stage = outer.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            outer.cancel(true);
            throw te;
        } catch (InterruptedException ie) {
            // 扫描 H2（落雪桥已修、FreeJS 侧同型漏修）：等待被中断（看门狗/停播）时
            // 必须连桥任务一起取消，否则被放弃的调用继续占单线程桥，后续同插件
            // 搜索/取链/取词全排队等它跑完。中断标志由 InterruptedException 语义自带清除，不另恢复。
            outer.cancel(true);
            throw ie;
        }
        try {
            return stage.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            outer.cancel(true);
            stage.cancel(true);
            throw te;
        } catch (InterruptedException ie) {
            outer.cancel(true);
            stage.cancel(true);
            throw ie;
        }
    }

    private String toJson(Object result) throws Exception {
        return toJson(result, false);
    }

    /** 取链专用：结果序列化也走高优先（见 executor 注释） */
    private String toJsonHigh(Object result) throws Exception {
        return toJson(result, true);
    }

    private String toJson(Object result, boolean high) throws Exception {
        if (result == null) return null;
        if (result instanceof JSObject) {
            // 扫查待裁 23：调用结果对象 stringify 后即在桥线程 release；全部调用方
            // 只用返回的 JSON 串、不再碰 result 本体，释放不影响后续。
            Callable<String> stringify = () -> {
                try {
                    return ((JSObject) result).stringify();
                } finally {
                    try {
                        ((JSObject) result).release();
                    } catch (Throwable ignored) {
                    }
                }
            };
            return (high ? submitHigh(stringify) : submit(stringify))
                    .get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        return String.valueOf(result);
    }

    /* ---------------- 协议函数 ---------------- */

    /** search(query, page, type)：type = music/album/artist/sheet/lyric */
    public MusicSearchResult search(String keyword, int page, String type) throws Exception {
        maybePromptLogin();
        Object result = call("search", keyword, page, type);
        String json = toJson(result);
        MusicSearchResult r = new MusicSearchResult();
        r.setPage(page);
        r.setType(type);
        if (TextUtils.isEmpty(json) || "null".equals(json.trim()) || "undefined".equals(json.trim())) {
            r.setEnd(true);
            return r;
        }
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        r.setEnd(!obj.has("isEnd") || obj.get("isEnd").getAsBoolean());
        List<MusicTrack> tracks = new ArrayList<>();
        if (obj.has("data") && obj.get("data").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("data")) {
                if (el.isJsonObject()) tracks.add(toTrack(el.getAsJsonObject()));
            }
        }
        r.setTracks(tracks);
        return r;
    }

    /**
     * P-3d 源内死通道黑名单：同曲一次取链链内（多档回落／面板逐档取链多次进桥）已死过的
     * （插件，档位）通道不再重进 JS；只记当前一曲（单缓存：换歌即清），绝不跨歌拉黑好通道；
     * 第一波仓库层指派拦截保留，两层不重复拦同一通道（仓库层拦指派单试，本层拦桥内取链）。
     * 第二波堵-4：死键插件＋档位口径不动；死集用并发集，换键清集＋查／记包在同一
     * DEAD_LOCK 同步块里（调用方含服务取链、预加载、面板探链三线程，旧静态 HashSet 无同步）；
     * 探链（probe）只读不记死。
     */
    private static final Object DEAD_LOCK = new Object();
    private static String deadTrackKey = "";
    private static final Set<String> deadChannels = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static boolean isDeadChannel(String trackKey, String channel) {
        synchronized (DEAD_LOCK) {
            if (trackKey == null || !trackKey.equals(deadTrackKey)) {
                deadTrackKey = trackKey == null ? "" : trackKey;
                deadChannels.clear();
                return false;
            }
            return channel != null && deadChannels.contains(channel);
        }
    }

    private static void markDeadChannel(String trackKey, String channel) {
        synchronized (DEAD_LOCK) {
            if (trackKey == null || !trackKey.equals(deadTrackKey)) {
                deadTrackKey = trackKey == null ? "" : trackKey;
                deadChannels.clear();
            }
            if (channel != null) deadChannels.add(channel);
        }
    }

    /** 第二波堵-4：新一次取链开始时清源内死通道（服务 prepareTrack 入口与仓库层一起调，不只清仓库层）。 */
    public static void clearDeadPlayUrlAttempt() {
        synchronized (DEAD_LOCK) {
            deadTrackKey = "";
            deadChannels.clear();
        }
    }

    /** 一次 getLyric 桥调用：入参在本插件上下文里现 parse，不跨调用复用 JS 对象。 */
    private Object callGetLyric(JsonObject item) throws Exception {
        Object arg = submit(() -> ctx.parse(item.toString())).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return call("getLyric", arg);
    }

    /** getMediaSource(musicItem, quality)：quality = low/standard/high/super */
    public MusicMediaSource getMediaSource(MusicTrack track, MusicQuality quality) throws Exception {
        return getMediaSource(track, quality, false);
    }

    /** 探链口径：probe＝true 时只读地址、不记死（面板探链用）；死键检查仍尊重已死标记。 */
    public MusicMediaSource getMediaSource(MusicTrack track, MusicQuality quality, boolean probe) throws Exception {
        return getMediaSource(track, quality, probe, SOURCE_TIMEOUT_MS);
    }

    /**
     * 带调用上限的取链：默认上限 SOURCE_TIMEOUT_MS（15 秒）；元力 KW 的快速降档路
     * 传更短的首试上限（解析站慢/挂时不等满 15 秒，见 FreeJsMusicSource 的 KW 保底）。
     * 死通道口径与默认路完全一致（同 channel 键、probe 只读不记死）。
     */
    public MusicMediaSource getMediaSource(MusicTrack track, MusicQuality quality, boolean probe, long callTimeoutMs) throws Exception {
        maybePromptLogin();
        String trackKey = MusicQueue.trackKey(track);
        String channel = key() + "|" + (quality == null ? "" : quality.getKey());
        if (isDeadChannel(trackKey, channel)) return null;
        MusicMediaSource ms;
        try {
            ms = getMediaSourceInner(track, quality, callTimeoutMs);
        } catch (Throwable t) {
            if (!probe) markDeadChannel(trackKey, channel);
            if (t instanceof Exception) throw (Exception) t;
            throw new Exception(t);
        }
        if (ms == null || TextUtils.isEmpty(ms.url)) {
            if (!probe) markDeadChannel(trackKey, channel);
            return null;
        }
        return ms;
    }

    private MusicMediaSource getMediaSourceInner(MusicTrack track, MusicQuality quality) throws Exception {
        return getMediaSourceInner(track, quality, SOURCE_TIMEOUT_MS);
    }

    private MusicMediaSource getMediaSourceInner(MusicTrack track, MusicQuality quality, long callTimeoutMs) throws Exception {
        JsonObject item = unwrapItem(track);
        if (item == null) return null;
        Future<Object> argFuture = submitHigh(() -> ctx.parse(item.toString()));
        Object arg;
        try {
            arg = argFuture.get(callTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            argFuture.cancel(true);
            throw te;
        }
        Object result = callTimedHigh("getMediaSource", callTimeoutMs, arg, quality.getKey());
        String json = toJsonHigh(result);
        if (TextUtils.isEmpty(json)) return null;
        json = json.trim();
        MusicMediaSource ms = new MusicMediaSource();
        if (json.startsWith("\"")) {
            ms.url = JsonParser.parseString(json).getAsString();
        } else {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            ms.url = optStr(obj, "url");
            if (obj.has("headers") && obj.get("headers").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("headers").entrySet()) {
                    try {
                        ms.headers.put(e.getKey(), e.getValue().getAsString());
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return TextUtils.isEmpty(ms.url) ? null : ms;
    }

    /** getLyric(musicItem)：返回 {rawLrc, translation?} 或纯字符串 */
    public MusicLyric getLyric(MusicTrack track) throws Exception {
        maybePromptLogin();
        JsonObject item = unwrapItem(track);
        if (item == null) return null;
        Object result;
        try {
            result = callGetLyric(item);
        } catch (Exception first) {
            // 酷我歌词接口（songinfoandlrc）间歇回空 data:null（实测同 id 时好时坏），
            // 元力 KW 插件 getLyric 不判空、读 lrclist 直接抛未处理拒绝——本源取词
            // 一次掷骰定生死。抛错立刻原样重试一遍（每次重新 parse 入参，不复用
            // 上一轮的 JS 对象）；仍失败照原口径把异常抛给上层走回退链，不吞。
            // 扫描 T9：超时型失败不重试——要救的只是「回空抛错」一型，插件挂死/超时
            // 时原样重试只会把桥超时整段重烧一遍（单插件最坏翻倍到 60 秒以上）。
            if (first instanceof java.util.concurrent.TimeoutException) throw first;
            try {
                result = callGetLyric(item);
            } catch (Exception second) {
                // 空数据读字段崩的归一（2026-10-26 真机日志 18 次实证）：重试后仍是
                // 同一型——插件在 getLyric 里对接口回的 null 数据读字段（元力 KW 读
                // lrclist 是已知一例，任何 FreeJS 插件同型）抛的未处理拒绝。这一型
                // 的语义就是「本源此刻无词」，与插件判空后返回空等价；远端插件脚本
                // 宿主改不了，就在桥这一层（全部 FreeJS 插件共用）归一成空结果：
                // 上层各步按「本源无词」继续走链，不再逐链记 error、不再把全量
                // 聚合标成不完整。其余异常（网络／插件真 bug）照原口径上抛，不吞。
                if (isNullPayloadDeref(second)) return null;
                throw second;
            }
        }
        String json = toJson(result);
        if (TextUtils.isEmpty(json)) return null;
        json = json.trim();
        MusicLyric lyric = new MusicLyric();
        if (json.startsWith("\"")) {
            lyric.rawLrc = JsonParser.parseString(json).getAsString();
        } else {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            lyric.rawLrc = firstNonEmpty(optStr(obj, "rawLrc"), optStr(obj, "lyric"), optStr(obj, "lrc"));
            lyric.translation = firstNonEmpty(optStr(obj, "translation"), optStr(obj, "tlyric"));
            lyric.romanization = firstNonEmpty(optStr(obj, "romalrc"), optStr(obj, "romanization"));
        }
        // 空白也算无词：与 MusicLyric.hasLyric 的 trim 口径一致，不许纯空白正文冒充有词堵住兜底
        return isBlank(lyric.rawLrc) && isBlank(lyric.translation) && isBlank(lyric.romanization) ? null : lyric;
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    /**
     * 「插件对 null 数据读字段」崩签名：QuickJS 的 TypeError 文案
     * （cannot read property 'X' of null）。异常经桥的 Future 层层包装
     * （ExecutionException 的 message 是 cause 的 toString），故沿 cause 链
     * 逐层查 message，任一层命中即算——只认这一型，其它拒绝不许冒充无词。
     */
    private static boolean isNullPayloadDeref(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String m = cur.getMessage();
            if (m == null) continue;
            String lower = m.toLowerCase();
            if (lower.contains("cannot read property") && lower.contains("of null")) return true;
        }
        return false;
    }

    /**
     * getTopLists()：可选，插件有就接。
     * MusicFree 协议返回的是分组：[{title:组名, data:[榜单条目...]}]，真榜单在 data 里；
     * 早先把组当榜单取 id，组上没 id → 每张榜 id 为空、点开必空（1.3.58 真机榜单全空定根）。
     * 兼容少数直接平铺返回榜单数组的插件（元素自带 id、无 data）。
     */
    public List<MusicChart> getTopLists() throws Exception {
        List<MusicChart> charts = new ArrayList<>();
        Object result = call("getTopLists");
        String json = toJson(result);
        if (TextUtils.isEmpty(json)) return charts;
        JsonArray arr = toArray(json);
        if (arr == null) return charts;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("data") && obj.get("data").isJsonArray()) {
                for (JsonElement item : obj.getAsJsonArray("data")) {
                    if (item.isJsonObject()) addChart(charts, item.getAsJsonObject());
                }
            } else {
                addChart(charts, obj);
            }
        }
        return charts;
    }

    private void addChart(List<MusicChart> charts, JsonObject item) {
        String id = optStr(item, "id");
        if (TextUtils.isEmpty(id)) return;
        MusicChart c = new MusicChart();
        c.platform = FreeJsMusicSource.PLATFORM;
        c.id = key() + "|" + id;
        c.name = optStr(item, "title");
        c.cover = optStr(item, "coverImg");
        c.rawJson = item.toString();
        charts.add(c);
    }

    /**
     * getTopListDetail(榜单条目对象)：可选，返回榜单曲目。
     * 协议要的是整个条目对象（插件读 topListItem.id 等字段）；早先传裸 id 字符串，
     * 插件取到 undefined、请求打到空地址抛错被上层吞掉，点开榜单永远空。
     * itemJson 缺失时退化为只带 id 的对象（只读 .id 的插件够用）。
     */
    public MusicSearchResult getTopListDetail(String listId, String itemJson) throws Exception {
        String argJson = itemJson;
        if (TextUtils.isEmpty(argJson)) {
            JsonObject o = new JsonObject();
            o.addProperty("id", listId == null ? "" : listId);
            argJson = o.toString();
        }
        final String parseSrc = argJson;
        Object arg = submit(() -> ctx.parse(parseSrc)).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        Object result = call("getTopListDetail", arg);
        String json = toJson(result);
        MusicSearchResult r = new MusicSearchResult();
        r.setEnd(true);
        if (TextUtils.isEmpty(json)) return r;
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        JsonArray arr = null;
        if (obj.has("musicList") && obj.get("musicList").isJsonArray()) arr = obj.getAsJsonArray("musicList");
        else if (obj.has("data") && obj.get("data").isJsonArray()) arr = obj.getAsJsonArray("data");
        List<MusicTrack> tracks = new ArrayList<>();
        if (arr != null) {
            for (JsonElement el : arr) {
                if (el.isJsonObject()) tracks.add(toTrack(el.getAsJsonObject()));
            }
        }
        r.setTracks(tracks);
        return r;
    }

    /** importMusicSheet(url)：可选，返回插件给的原始 JSON（歌单形态由播放器侧再定） */
    public String importMusicSheet(String url) throws Exception {
        Object result = call("importMusicSheet", url);
        return toJson(result);
    }

    public void destroy() {
        destroyed = true;
        try {
            // 拆桥走高优先：别在排队的取词/搜索后面干等（后面还有 5 秒兜底与 shutdownNow）
            submitHigh(() -> {
                // 定时器先收口（挂着的回调属于本 ctx），计时器本体随桥关停
                cancelAllTimeouts();
                timer.cancel();
                if (exports != null) {
                    try {
                        exports.release();
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

    /* ---------------- 内部 ---------------- */

    /** rawJson = {_plugin: srcUrl, _item: 插件原始条目}，拆出 _item */
    private JsonObject unwrapItem(MusicTrack track) {
        if (track == null || TextUtils.isEmpty(track.rawJson)) return null;
        try {
            JsonObject wrap = JsonParser.parseString(track.rawJson).getAsJsonObject();
            if (wrap.has("_item") && wrap.get("_item").isJsonObject()) return wrap.getAsJsonObject("_item");
            return wrap;
        } catch (Exception e) {
            return null;
        }
    }

    private MusicTrack toTrack(JsonObject item) {
        MusicTrack t = new MusicTrack();
        t.id = optStr(item, "id");
        t.title = optStr(item, "title");
        t.artist = optStr(item, "artist");
        t.album = optStr(item, "album");
        t.cover = firstNonEmpty(optStr(item, "artwork"), optStr(item, "cover"), optStr(item, "pic"),
                optStr(item, "picUrl"), optStr(item, "artworkUrl"), optStr(item, "coverUrl"),
                optStr(item, "albumPic"), optStr(item, "pic_url"));
        t.duration = toMillis(item);
        t.qualityTag = qualityTagOf(item);
        t.platform = FreeJsMusicSource.PLATFORM;
        JsonObject wrap = new JsonObject();
        wrap.addProperty("_plugin", key());
        wrap.add("_item", item);
        t.rawJson = wrap.toString();
        return t;
    }

    /**
     * 插件 qualities（MusicFree musicItem.qualities）→ 列表行音质小标，只标最高档：
     * 协议键 low/standard/high/super 依次对应 128k/320k/无损/超高，
     * 小标口径只用真规格词 FLAC/320k/128k（super 也只标 FLAC，不冒充母带）。
     * 插件没给 qualities、或给了认不出的键 → 返回 null 不显示，绝不硬凑。
     * （实测：元力KW、bilibili 的搜索条目都不带 qualities，这种歌就是不显示。）
     */
    private static String qualityTagOf(JsonObject item) {
        try {
            if (!item.has("qualities") || item.get("qualities").isJsonNull()) return null;
            JsonElement q = item.get("qualities");
            boolean has128 = false, has320 = false, hasFlac = false;
            List<String> keys = new ArrayList<>();
            if (q.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : q.getAsJsonObject().entrySet()) {
                    JsonElement v = e.getValue();
                    if (v == null || v.isJsonNull()) continue;
                    if (v.isJsonPrimitive() && v.getAsString().isEmpty()) continue;
                    keys.add(e.getKey());
                }
            } else if (q.isJsonArray()) {
                for (JsonElement el : q.getAsJsonArray()) {
                    if (el != null && el.isJsonPrimitive()) keys.add(el.getAsString());
                }
            }
            for (String raw : keys) {
                // 键名先归一：hi-res/hi_res 都按 hires 认，别因分隔符漏标
                String k = raw == null ? "" : raw.toLowerCase().replace("-", "").replace("_", "");
                if (k.contains("flac") || k.contains("hires") || k.contains("24bit")
                        || k.equals("high") || k.equals("super")) hasFlac = true;
                else if (k.contains("320") || k.equals("standard")) has320 = true;
                else if (k.contains("128") || k.equals("low")) has128 = true;
            }
            if (hasFlac) return "FLAC";
            if (has320) return "320k";
            if (has128) return "128k";
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 插件 duration 三种形态都要认：数字（多为秒；毫秒级 >=100000 直接用）、
     * 数字字符串、以及 "mm:ss"/"hh:mm:ss" 字符串——哔哩哔哩搜索接口给的就是
     * "3:45" 这种字符串，老写法 getAsLong 直接抛异常被吞成 0，播放页时长恒 00:00。
     * 源没给/给不明白就保持 0，不编造。
     */
    private long toMillis(JsonObject item) {
        try {
            if (!item.has("duration") || item.get("duration").isJsonNull()) return 0;
            JsonElement el = item.get("duration");
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                String s = el.getAsString().trim();
                if (s.isEmpty()) return 0;
                if (s.contains(":")) {
                    long totalSec = 0;
                    for (String p : s.split(":")) totalSec = totalSec * 60 + Long.parseLong(p.trim());
                    return totalSec * 1000;
                }
                long d = Long.parseLong(s);
                return d < 100000 ? d * 1000 : d;
            }
            long d = el.getAsLong();
            return d < 100000 ? d * 1000 : d;
        } catch (Exception e) {
            return 0;
        }
    }

    private JsonArray toArray(String json) {
        try {
            json = json.trim();
            if (json.startsWith("[")) return JsonParser.parseString(json).getAsJsonArray();
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("data") && obj.get("data").isJsonArray()) return obj.getAsJsonArray("data");
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String optStr(JsonObject obj, String name) {
        try {
            if (obj.has(name) && !obj.get(name).isJsonNull()) return obj.get(name).getAsString();
        } catch (Exception ignored) {
        }
        return "";
    }

    private String firstNonEmpty(String... vals) {
        for (String v : vals) if (!TextUtils.isEmpty(v)) return v;
        return "";
    }

    /** 内核按需拉起失败的重试节流：拉起含最长 10 秒健康检查，失败后每个请求都重试会把桥拖死 */
    private static final long NODE_START_RETRY_MS = 60_000L;
    private static volatile long nodeStartFailedAt = 0L;

    /**
     * 本源在策略表里开了「走节点」、而内核没在跑时，发请求前同步把内核拉起来
     * （EmbyApi.ensureNodeForServer 同款口径：单源开了即生效，不要求总开关先开）。
     * 本方法跑在桥的单线程上（后台线程，ensureRunning 的同步阻塞可接受）。
     * 拉起失败不堵请求：照常发（门控会判直连），但留一条 clash 日志说明本次为什么
     * 没走成节点，不许静默直连挂死（内核未下载时 startSync 抛错、ensureRunning 回 false）。
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

    /**
     * 插件网络出口：复用 quickjs 模块的 Connect（壳子 OkHttp，吃代理/DoH/节点定向），不直连。
     * 返回 {status, headers, text} 的 JSON；失败返回 {error}。
     */
    private String nativeRequest(String method, String url, String optsJson) {
        JSONObject out = new JSONObject();
        // 节点定向接线（2026-10-26）：桥是插件唯一的网络出口，在此给当前线程打源归属点
        // （kind=music、id=srcUrl，与 SiteApi/EmbyApi 的 push/pop 同口径），壳子 OkHttp 的
        // SourceGate 选择器才看得见「这是哪个音乐源的请求」，按策略表决定走节点还是直连。
        // 此前音乐侧从不打点：油管请求一律 gate direct reason=no-mark、直连 youtube 挂死，
        // 源名里的「需代理」只是展示名、不改任何路由。打点前先按需拉内核（见上）。
        ensureNodeForMusic();
        SourceGate.Mark prev = SourceGate.push(SourceProxyPolicy.KIND_MUSIC, key());
        try {
            Req req = Req.objectFrom(buildReqJson(method, optsJson));
            try (Response res = Connect.to(url, req).execute()) {
                byte[] bytes = res.body() != null ? res.body().bytes() : new byte[0];
                out.put("status", res.code());
                JSONObject headers = new JSONObject();
                for (Map.Entry<String, List<String>> e : res.headers().toMultimap().entrySet()) {
                    List<String> vals = e.getValue();
                    if (vals != null && !vals.isEmpty()) headers.put(e.getKey(), vals.get(vals.size() - 1));
                }
                out.put("headers", headers);
                out.put("text", new String(bytes, req.getCharset()));
            }
        } catch (Throwable t) {
            try {
                out.put("error", String.valueOf(t.getMessage()));
            } catch (Exception ignored) {
            }
        } finally {
            SourceGate.pop(prev);
        }
        return out.toString();
    }

    /**
     * 把垫片的 {headers, body, timeout} 转成 quickjs 模块 Req bean 的 JSON。
     * Connect 只认 GET/POST/HEAD：PUT/DELETE/PATCH 统一按 POST 发（body 保留），
     * 与 Req.normalizeBody 的归一思路一致。
     */
    private String buildReqJson(String method, String optsJson) {
        JSONObject req = new JSONObject();
        try {
            JSONObject opts = new JSONObject(optsJson);
            String m = method == null ? "GET" : method.toUpperCase();
            if (!"GET".equals(m) && !"POST".equals(m) && !"HEAD".equals(m)) m = "POST";
            req.put("method", m);
            JSONObject hs = opts.optJSONObject("headers");
            if (hs != null) req.put("headers", hs);
            if (!opts.isNull("body")) req.put("body", opts.optString("body", ""));
            long timeout = opts.optLong("timeout", 20000);
            // 扫描 H1（与落雪桥 LxBridge 同口径）：下限 1000——旧下限 5000 把插件自带
            // 的小超时（如 2 秒）放大 2.5 倍，多后端顺序回退时每死一个多烧约 3 秒，
            // 整链易撞桥 30 秒/取链 15 秒上限。
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
}
