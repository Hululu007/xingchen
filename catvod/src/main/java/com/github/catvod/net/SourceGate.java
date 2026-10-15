package com.github.catvod.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按源代理决策层：catvod 侧的中立挂载点。
 * <p>
 * 模块方向约束：app 模块的 clash 包依赖 catvod，反向不行。
 * 这里只放纯静态的「策略提供者接口 + 线程级源归属标记 + 选择器包装」，
 * 具体策略（节点开关/混合口/全部走节点/按源走节点表）由 app 侧
 * 在启动时 register 进来（见 Startup 的 ClashGate.register）。
 * <p>
 * 决策语义：提供者为空或总开关未开 → 透传原链；
 * 回环地址永远直连；
 * 当前线程有源归属 → 按 shouldProxy(kind,id) 决定；
 * 无归属 → 按全部走节点开关决定；
 * 命中 → 返回 127.0.0.1:混合口 的 HTTP 代理。
 */
public final class SourceGate {

    /** 决策策略提供者（app 侧实现，启动时注册一次）。 */
    public interface Provider {
        /** 节点开关开且内核在跑。 */
        boolean enabled();

        /** 本地混合口。 */
        int port();

        /** 无源归属的流量是否走节点（「全部走节点」开关）。 */
        boolean globalMode();

        /** 某来源（kind=py/js/hk，id=源标识/规则名）是否走节点。 */
        boolean shouldProxy(String kind, String id);
    }

    /** 当前线程正在处理的来源归属。 */
    public static final class Mark {
        public final String kind;
        public final String id;

        Mark(String kind, String id) {
            this.kind = kind;
            this.id = id;
        }
    }

    private static volatile Provider provider;
    private static final ThreadLocal<Mark> HOLDER = new ThreadLocal<>();

    private SourceGate() {
    }

    public static void register(Provider p) {
        provider = p;
    }

    /** 打点：调用方必须在 finally 里配套 clear()。kind 为空不打点。 */
    public static void mark(String kind, String id) {
        if (kind == null || kind.isEmpty()) return;
        HOLDER.set(new Mark(kind, id == null ? "" : id));
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static Mark current() {
        return HOLDER.get();
    }

    /** 决策留痕节流：同一键 30 秒内只记一条（他发来的日志里此前没有任何定向决策记录，定不了案）。 */
    private static final ConcurrentHashMap<String, Long> LOG_AT = new ConcurrentHashMap<>();

    private static void logDecision(String key, String msg) {
        long now = System.currentTimeMillis();
        Long last = LOG_AT.get(key);
        if (last != null && now - last < 30_000) return;
        LOG_AT.put(key, now);
        try {
            com.github.catvod.crawler.SpiderDebug.log("clash", "%s", msg);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Python 取数口：py 蜘蛛在 chaquo 里用 requests 自己取数，不经 Java 网络栈，
     * 选择器管不到它——这正是 py 定向从来没通过的原因（1.3.16 日志里 py 调用
     * 一条 gate 决策记录都没有、超时形态与无内核时一模一样）。
     * base/spider.py 的 fetch/post 在发请求前问这里：当前线程的源标记命中策略
     * 就返回本地混合口，py 侧据此给 requests 传 proxies；否则返回 0 走直连。
     * 标记由 SiteApi.marked 设在调用蜘蛛的线程上，py 回调 Java 仍在同一线程，可见。
     */
    public static int proxyPortForCurrent() {
        Provider p = provider;
        Mark m = HOLDER.get();
        if (p == null) {
            if (m != null) logDecision("noprov|" + m.kind + "|" + m.id,
                    "gate direct reason=no-provider kind=" + m.kind + " id=" + m.id + " via=py");
            return 0;
        }
        if (!p.enabled()) {
            if (m != null) logDecision("disabled|" + m.kind + "|" + m.id,
                    "gate direct reason=disabled(开关关或内核没跑) kind=" + m.kind + " id=" + m.id + " via=py");
            return 0;
        }
        boolean hit = (m != null) ? p.shouldProxy(m.kind, m.id) : p.globalMode();
        if (!hit) {
            if (m != null) logDecision("notenabled|" + m.kind + "|" + m.id,
                    "gate direct reason=not-enabled kind=" + m.kind + " id=" + m.id + " via=py");
            return 0;
        }
        int port = p.port();
        if (port <= 0 || port > 65535) {
            logDecision("badport", "gate direct reason=bad-port port=" + port + " via=py");
            return 0;
        }
        logDecision("proxy|" + (m == null ? "global" : m.kind + "|" + m.id),
                "gate proxy kind=" + (m == null ? "-" : m.kind) + " id=" + (m == null ? "-" : m.id) + " port=" + port + " via=py");
        return port;
    }

    /** 把原选择器包在外面：命中策略 → 本地口；否则透传原链。回环永远直连。 */
    public static ProxySelector wrapSelector(ProxySelector inner) {
        final ProxySelector shell = inner != null ? inner : ProxySelector.getDefault();
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                Provider p = provider;
                Mark m = HOLDER.get();
                if (p == null) {
                    if (m != null) logDecision("noprov|" + m.kind + "|" + m.id,
                            "gate direct reason=no-provider kind=" + m.kind + " id=" + m.id);
                    return shell.select(uri);
                }
                if (!p.enabled()) {
                    if (m != null) logDecision("disabled|" + m.kind + "|" + m.id,
                            "gate direct reason=disabled(开关关或内核没跑) kind=" + m.kind + " id=" + m.id);
                    return shell.select(uri);
                }
                if (isLoopback(uri)) return List.of(Proxy.NO_PROXY);
                boolean hit = (m != null) ? p.shouldProxy(m.kind, m.id) : p.globalMode();
                if (hit) {
                    int port = p.port();
                    if (port > 0 && port <= 65535) {
                        logDecision("proxy|" + (m == null ? "global" : m.kind + "|" + m.id),
                                "gate proxy kind=" + (m == null ? "-" : m.kind) + " id=" + (m == null ? "-" : m.id) + " port=" + port);
                        return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port)));
                    }
                    logDecision("badport", "gate direct reason=bad-port port=" + port);
                } else if (m != null) {
                    logDecision("notenabled|" + m.kind + "|" + m.id,
                            "gate direct reason=not-enabled kind=" + m.kind + " id=" + m.id);
                }
                return shell.select(uri);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                shell.connectFailed(uri, sa, ioe);
            }
        };
    }

    private static boolean isLoopback(URI uri) {
        try {
            String host = uri.getHost();
            if (host == null) return false;
            String h = host.toLowerCase();
            return h.equals("localhost") || h.equals("::1") || h.equals("[::1]") || h.startsWith("127.") || h.equals("0.0.0.0");
        } catch (Exception e) {
            return false;
        }
    }
}
