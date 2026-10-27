package com.fongmi.android.tv.legado;

import com.fongmi.android.tv.api.hk.HkHttp;
import com.fongmi.android.tv.clash.SourceProxyPolicy;
import com.github.catvod.net.SourceGate;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 书源网络层宿主默认实现：复用 HkHttp 的 OkHttp 客户端（含节点选路）。
 * 超时：连接 12s / 读写 20s；任何失败只抛 IOException，不拖垮宿主。
 */
public class LegadoHttpAndroid implements LegadoHttp {

    /** 响应体上限 16MB（低-17）。 */
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    /** 非 2xx 只读判定前缀的上限：够认出验证页，不整包吞错误体。 */
    private static final int ERROR_PREFIX_BYTES = 256 * 1024;
    /** 请求与验证页必须用同一个 UA，否则 Cloudflare 的通行证不认（过盾同口径）。 */
    static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    /** 并行请求刚有人验完的免弹窗口：窗口内直接带证重试，不连环弹。 */
    private static final long VERIFY_FRESH_MS = 90_000L;

    private static volatile LegadoHttp instance;

    public static void setInstance(LegadoHttp impl) {
        instance = impl;
    }

    public static LegadoHttp get() {
        LegadoHttp impl = instance;
        return impl != null ? impl : Holder.DEF;
    }

    private static class Holder {
        static final LegadoHttpAndroid DEF = new LegadoHttpAndroid();
    }

    private OkHttpClient client() {
        return HkHttp.client().newBuilder()
                .connectTimeout(LegadoHttp.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(LegadoHttp.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(LegadoHttp.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 当前线程正在抓取的书源 key（bookSourceUrl）：LegadoPipeline 在抓取前后
     * 设置/恢复，打点精确到具体书源（设置页「书源」栏逐个开关）。
     * 用 push/pop 语义，外层打点（如章节缓存下载的外层归属）不会被吞。
     */
    private static final ThreadLocal<String> SOURCE_KEY = new ThreadLocal<>();

    public static String swapSourceKey(String key) {
        String prev = SOURCE_KEY.get();
        if (key == null || key.isEmpty()) SOURCE_KEY.remove();
        else SOURCE_KEY.set(key);
        return prev;
    }

    public static void restoreSourceKey(String prev) {
        if (prev == null || prev.isEmpty()) SOURCE_KEY.remove();
        else SOURCE_KEY.set(prev);
    }

    /** 一次原始请求的结果：状态码/终点 URL/响应体（非 2xx 只留判定前缀）/回带 Cookie。 */
    private static class Raw {
        int code;
        String finalUrl = "";
        String contentType = "";
        byte[] body = new byte[0];
        final Map<String, String> cookies = new java.util.HashMap<>();
    }

    @Override
    public LegadoHttp.Result fetch(String url, String method, Map<String, String> headers, byte[] body) throws IOException {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IOException("不支持的协议: " + url);
        }
        // 节点打点：书源走 KIND_BOOK 口径（id=具体书源 key），与「按源定向」书源栏一致。
        // 无 key（如导入时校验地址）时 id 为空串 → 只有「全部走节点」能带走它。
        String key = SOURCE_KEY.get();
        SourceGate.Mark prev = SourceGate.push(SourceProxyPolicy.KIND_BOOK, key == null ? "" : key);
        try {
            String host = LegadoCookies.hostOf(url);
            Raw raw = doFetch(url, method, withCookies(headers, host), body);
            if (LegadoVerify.isBlockedPage(raw.code, raw.finalUrl, raw.contentType, raw.body)) {
                raw = verifyAndRetry(url, method, headers, body, host);
            }
            if (raw.code < 200 || raw.code >= 300) throw new IOException("HTTP " + raw.code + " " + url);
            // 已存通行证的站：把这次回带的 Cookie 并回去保鲜（站点会轮换）
            if (LegadoCookies.has(host)) LegadoCookies.merge(host, raw.cookies);
            LegadoHttp.Result r = new LegadoHttp.Result();
            r.body = raw.body;
            r.contentType = raw.contentType;
            r.cookies.putAll(raw.cookies);
            return r;
        } finally {
            SourceGate.pop(prev);
        }
    }

    /**
     * 撞上人机验证的续跑：同一次 fetch 最多弹一次验证页。
     * 并行请求里刚有人验完（窗口内）先免弹带证重试一次，没顶住再弹；
     * 弹完带新证重试一次，还被挡就如实报「验证没过去」，不循环弹。
     */
    private Raw verifyAndRetry(String url, String method, Map<String, String> headers, byte[] body, String host) throws IOException {
        if (LegadoCookies.recentlySaved(host, VERIFY_FRESH_MS)) {
            Raw fresh = doFetch(url, method, withCookies(headers, host), body);
            if (!LegadoVerify.isBlockedPage(fresh.code, fresh.finalUrl, fresh.contentType, fresh.body)) return fresh;
        }
        int res = LegadoVerify.awaitVerification(url, USER_AGENT);
        if (res == LegadoVerify.RESULT_CANCELLED) throw new LegadoException("验证已取消");
        if (res != LegadoVerify.RESULT_OK) throw new LegadoException("这个站需要人机验证，但验证页打不开");
        Raw retry = doFetch(url, method, withCookies(headers, host), body);
        if (LegadoVerify.isBlockedPage(retry.code, retry.finalUrl, retry.contentType, retry.body)) {
            throw new LegadoException("这个站的验证没过去");
        }
        return retry;
    }

    /** 已存通行证时给请求头补 Cookie（调用方自带 Cookie 头时不覆盖）；不改调用方的 map。 */
    private Map<String, String> withCookies(Map<String, String> headers, String host) {
        String cookie = LegadoCookies.cookieHeader(host);
        if (cookie.isEmpty()) return headers;
        Map<String, String> copy = new java.util.HashMap<>();
        if (headers != null) copy.putAll(headers);
        for (String k : copy.keySet()) {
            if (k != null && k.equalsIgnoreCase("Cookie")) return copy;
        }
        copy.put("Cookie", cookie);
        return copy;
    }

    private Raw doFetch(String url, String method, Map<String, String> headers, byte[] body) throws IOException {
        Request.Builder rb = new Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9");
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) {
                try {
                    if (h.getKey() != null && h.getValue() != null) rb.header(h.getKey(), h.getValue());
                } catch (Throwable ignored) {
                }
            }
        }
        String m = method == null ? "GET" : method.toUpperCase();
        if ("POST".equals(m)) {
            byte[] b = body == null ? new byte[0] : body;
            rb.post(RequestBody.create(b, MediaType.parse("application/x-www-form-urlencoded; charset=utf-8")));
        } else {
            rb.get();
        }
        Raw raw = new Raw();
        try (Response resp = client().newCall(rb.build()).execute()) {
            raw.code = resp.code();
            raw.finalUrl = resp.request().url().toString();
            String ct = resp.header("Content-Type");
            raw.contentType = ct == null ? "" : ct;
            List<String> setCookies = resp.headers("Set-Cookie");
            if (setCookies != null) {
                for (String c : setCookies) {
                    int eq = c.indexOf('=');
                    int sc = c.indexOf(';');
                    if (eq > 0) raw.cookies.put(c.substring(0, eq).trim(),
                            c.substring(eq + 1, sc < 0 ? c.length() : sc).trim());
                }
            }
            ResponseBody rb2 = resp.body();
            if (rb2 == null) {
                if (resp.isSuccessful()) throw new IOException("空响应 " + url);
                return raw;
            }
            if (resp.isSuccessful()) {
                // 流式读＋16MB 上限（低-17）：rb2.bytes() 整包进内存，对方回个超大包
                // （或被劫持回流式垃圾）直接顶爆内存；书源正文/目录远小于此上限。
                if (rb2.contentLength() > MAX_BODY_BYTES) {
                    throw new IOException("响应过大: " + rb2.contentLength());
                }
                java.io.InputStream in = rb2.byteStream();
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                    if (baos.size() > MAX_BODY_BYTES) throw new IOException("响应过大（超 16MB）");
                }
                raw.body = baos.toByteArray();
            } else {
                // 错误响应只读判定前缀：够认出验证页即可，不整包吞
                java.io.InputStream in = rb2.byteStream();
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while (baos.size() < ERROR_PREFIX_BYTES && (n = in.read(buf)) != -1) {
                    baos.write(buf, 0, Math.min(n, ERROR_PREFIX_BYTES - baos.size()));
                }
                raw.body = baos.toByteArray();
            }
        }
        return raw;
    }
}
