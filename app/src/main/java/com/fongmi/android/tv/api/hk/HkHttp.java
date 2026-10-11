package com.fongmi.android.tv.api.hk;

import android.content.Context;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.XingChenDoh;
import com.fongmi.quickjs.bean.Req;
import com.github.catvod.bean.Doh;
import com.github.catvod.utils.Json;
import com.google.common.net.HttpHeaders;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.dnsoverhttps.DnsOverHttps;

/**
 * 海阔 URL 模板展开 + 简单 GET（设计文档 §4.2）。
 * <p>
 * 支持的替换词：
 * <ul>
 * <li>{@code fyclass/fyarea/fyyear/fysort} 分类/地区/年份/排序</li>
 * <li>{@code fyAll} 通用替换（取分类值）</li>
 * <li>{@code fypage} 页码，支持算术后缀 {@code fypage@-1@*20@}（即 (page-1)*20 → 0,20,40…）</li>
 * <li>{@code [firstPage=xxx]} page==1 时用括号内模板，否则去掉</li>
 * <li>{@code hiker://empty#真实地址} 取 # 后的真实地址</li>
 * <li>{@code **} 搜索关键词占位（URL 编码后替换）</li>
 * </ul>
 */
public class HkHttp {

    private static final String EMPTY_PREFIX = "hiker://empty#";
    private static final Pattern FIRST_PAGE = Pattern.compile("\\[firstPage=([^\\]]*)\\]");
    /** fypage@-1@*20@ 即 (page-1)*20；各 op 以 @ 分隔：@op1@op2@… */
    private static final Pattern FYPAGE_EX = Pattern.compile("fypage@((?:[-+*/]\\d+@)+)");
    private static final Pattern FYPAGE_OP = Pattern.compile("([-+*/])(\\d+)@");

    private static final String DEFAULT_UA = "Mozilla/5.0 (Linux; Android 15; 2407FRK8EC Build/AP3A.240617.008; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.127 Mobile Safari/537.36";

    private static volatile OkHttpClient client;
    /** 海阔图片专用 client（8.83 OkHttpUrlLoader.Factory 内建 client 配置） */
    private static volatile OkHttpClient imageClient;
    /** 海阔专用的 DNS（含 DoH），移植自 8.83 MoreSettingOfficer.loadDns 逻辑 */
    private static final Dns HK_DNS = new Dns() {
        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            DnsOverHttps doh = hkDoh;
            return (doh != null ? doh : Dns.SYSTEM).lookup(hostname);
        }
    };
    private static volatile DnsOverHttps hkDoh;

    /**
     * 应用 DoH 设置到海阔客户端（对应 8.83 MoreSettingOfficer.loadDns）。
     * 8.83 默认"智能"不设置 DNS（走系统）；星辰"系统"同理传空。
     */
    public static void applyDoh(Context context) {
        try {
            int idx = XingChenDoh.getIndex(context);
            if (idx == 0) {
                hkDoh = null;
            } else {
                Doh d = XingChenDoh.toDoh(context);
                HttpUrl url = HttpUrl.parse(d.getUrl());
                hkDoh = url == null ? null : new DnsOverHttps.Builder().client(new OkHttpClient()).url(url).bootstrapDnsHosts(d.getHosts()).build();
            }
            // 重建 client 使新 DNS 生效
            synchronized (HkHttp.class) {
                client = null;
                imageClient = null;
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * 海阔图片专用 OkHttpClient，移植自 8.83 OkHttpUrlLoader.Factory 内建 client：
     * - BrotliInterceptor
     * - 信任所有证书 SSL（UnSafeTrustManager + UnSafeHostnameVerifier）
     * - loadDns（DoH）
     * - 超时 + CookieJar
     * 不含 ContentType 拦截器（8.83 图片 client 同样不加）。
     */
    public static OkHttpClient imageClient() {
        if (imageClient == null) {
            synchronized (HkHttp.class) {
                if (imageClient == null) {
                    OkHttpClient.Builder b = new OkHttpClient.Builder()
                            .sslSocketFactory(HkSsl.getSocketFactory(), HkSsl.TRUST_MANAGER)
                            .hostnameVerifier(HkSsl.HOSTNAME_VERIFIER)
                            .dns(HK_DNS)
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS);
                    try {
                        Class<?> c = Class.forName("okhttp3.brotli.BrotliInterceptor");
                        Object inst = c.getField("INSTANCE").get(null);
                        b.addInterceptor((okhttp3.Interceptor) inst);
                    } catch (Throwable ignored) {
                    }
                    try {
                        b.cookieJar(HkCookieJar.get(App.get()));
                    } catch (Throwable ignored) {
                    }
                    imageClient = b.build();
                }
            }
        }
        return imageClient;
    }

    /**
     * 构建 OkHttpClient，移植自 8.83 HttpHelper.buildOkHttpClient(int, boolean)：
     * - 信任所有证书 SSL（HttpsUtils.getSslSocketFactory + UnSafeHostnameVerifier）
     * - loadDns（DoH）
     * - ContentTypePreInterceptor + ContentTypeAfterInterceptor（network）
     * - CookieJar 持久化
     * - 统一超时
     */
    // 8.83 HttpHelper.buildOkHttpClient 移植：公开供 HkJsRuntime 等调用方使用，
    // 使海阔 JS 请求与 HkHttp 同走 DoH / CookieJar / 内容类型拦截器配置。
    public static OkHttpClient client() {
        if (client == null) {
            synchronized (HkHttp.class) {
                if (client == null) {
                    OkHttpClient.Builder b = new OkHttpClient.Builder()
                            .sslSocketFactory(HkSsl.getSocketFactory(), HkSsl.TRUST_MANAGER)
                            .hostnameVerifier(HkSsl.HOSTNAME_VERIFIER)
                            .dns(HK_DNS)
                            .addInterceptor(HkContentTypePreInterceptor.INSTANCE)
                            .addNetworkInterceptor(HkContentTypeAfterInterceptor.INSTANCE)
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true);
                    // Brotli（8.83 标配）：反射加载，缺失则跳过
                    try {
                        Class<?> c = Class.forName("okhttp3.brotli.BrotliInterceptor");
                        Object inst = c.getField("INSTANCE").get(null);
                        b.addInterceptor((okhttp3.Interceptor) inst);
                    } catch (Throwable ignored) {
                    }
                    try {
                        b.cookieJar(HkCookieJar.get(App.get()));
                    } catch (Throwable ignored) {
                    }
                    client = b.build();
                }
            }
        }
        return client;
    }

    /**
     * 构造带 Req 参数的请求 Call：请求头/请求体构建逻辑与 Connect.to 同构，
     * 只是客户端换成 8.83 配置的 HkHttp.client()（DoH / CookieJar / 拦截器都生效）。
     *
     * <p>8.83 HttpHelper.buildOkHttpClient(int timeout, boolean redirect) 语义：
     * 规则可通过 Req 指定 redirect（是否跟随跳转）和 timeout（毫秒）；
     * 例如 fetch(url, {redirect: 0}) 用于取 302 的 Location 头。默认跟随跳转。</p>
     */
    public static Call to(String url, Req req) {
        boolean redirect = req.isRedirect();
        // Req.getTimeout() 默认 10000ms；8.83 用传入的 timeout 设 read/write/connect
        int timeout = 10000;
        try {
            Integer t = req.getTimeout();
            if (t != null) timeout = t;
        } catch (Throwable ignored) {
        }
        OkHttpClient c = client();
        if (!redirect || timeout != 10000) {
            OkHttpClient.Builder b = c.newBuilder()
                    .followRedirects(redirect)
                    .followSslRedirects(redirect);
            if (timeout != 10000) {
                b.connectTimeout(timeout, TimeUnit.MILLISECONDS)
                 .readTimeout(timeout, TimeUnit.MILLISECONDS)
                 .writeTimeout(timeout, TimeUnit.MILLISECONDS);
            }
            c = b.build();
        }
        return c.newCall(getRequest(url, req, Headers.of(req.getHeader())));
    }

    private static Request getRequest(String url, Req req, Headers headers) {
        Request.Builder rb = new Request.Builder().url(url).headers(headers);
        applyThreadTag(rb);
        if (req.getMethod().equalsIgnoreCase("post")) {
            return rb.post(getPostBody(req, headers.get(HttpHeaders.CONTENT_TYPE))).build();
        } else if (req.getMethod().equalsIgnoreCase("header")) {
            return rb.head().build();
        } else {
            return rb.get().build();
        }
    }

    /** 8.83 addTagForThread 语义：当前线程有 tag 时给请求打上，供 cancelByTag 取消。 */
    private static void applyThreadTag(Request.Builder rb) {
        try {
            Object tag = HkHttpHelper.getTagFromThread();
            if (tag != null) rb.tag(Object.class, tag);
        } catch (Throwable ignored) {
        }
    }

    private static RequestBody getPostBody(Req req, String contentType) {
        if (req.getData() != null && "json".equals(req.getPostType())) return getJsonBody(req);
        if (req.getData() != null && "form".equals(req.getPostType())) return getFormBody(req);
        if (req.getData() != null && "form-data".equals(req.getPostType())) return getFormDataBody(req);
        if (req.getBody() != null && contentType != null) return RequestBody.create(req.getBody(), MediaType.get(contentType));
        return RequestBody.create(new byte[0]);
    }

    private static RequestBody getJsonBody(Req req) {
        return RequestBody.create(req.getData().toString(), MediaType.get("application/json; charset=utf-8"));
    }

    private static RequestBody getFormBody(Req req) {
        FormBody.Builder builder = new FormBody.Builder();
        Map<String, String> params = Json.toMap(req.getData());
        for (String key : params.keySet()) builder.add(key, params.get(key));
        return builder.build();
    }

    private static RequestBody getFormDataBody(Req req) {
        String boundary = "--dio-boundary-" + new SecureRandom().nextInt(42949) + new SecureRandom().nextInt(67296);
        MultipartBody.Builder builder = new MultipartBody.Builder(boundary).setType(MultipartBody.FORM);
        Map<String, String> params = Json.toMap(req.getData());
        for (String key : params.keySet()) builder.addFormDataPart(key, params.get(key));
        return builder.build();
    }

    /**
     * 展开首页/分类 URL 模板。
     *
     * @param template 规则 url 模板
     * @param cls      分类值（fyclass/fyAll）
     * @param area     地区值（fyarea）
     * @param year     年份值（fyyear）
     * @param sort     排序值（fysort）
     * @param page     页码（从 1 开始）
     */
    public static String expandUrl(String template, String cls, String area, String year, String sort, int page) {
        return expandUrl(template, cls, area, year, sort, page, true);
    }

    /**
     * 展开首页/分类 URL 模板。
     *
     * @param stripEmptyPrefix 是否剥离 {@code hiker://empty#} 前缀。
     *                       js: 规则必须传 false——规则 JS 里常写
     *                       {@code MY_URL.replace("hiker://empty##", host)}，
     *                       MY_URL 必须是原始 url，提前剥离会破坏替换逻辑。
     */
    public static String expandUrl(String template, String cls, String area, String year, String sort, int page, boolean stripEmptyPrefix) {
        if (template == null) return "";
        String url = template.trim();
        if (stripEmptyPrefix && url.startsWith(EMPTY_PREFIX)) url = url.substring(EMPTY_PREFIX.length());
        Matcher fm = FIRST_PAGE.matcher(url);
        if (fm.find()) {
            String first = fm.group(1);
            url = page <= 1 ? first : fm.replaceAll("");
            if (stripEmptyPrefix && url.startsWith(EMPTY_PREFIX)) url = url.substring(EMPTY_PREFIX.length());
        }
        url = url.replace("fyclass", safe(cls));
        url = url.replace("fyarea", safe(area));
        url = url.replace("fyyear", safe(year));
        url = url.replace("fysort", safe(sort));
        url = url.replace("fyAll", safe(cls));
        Matcher pm = FYPAGE_EX.matcher(url);
        StringBuffer sb = new StringBuffer();
        while (pm.find()) {
            pm.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(applyOps(page, pm.group(1)))));
        }
        pm.appendTail(sb);
        return sb.toString().replace("fypage", String.valueOf(page));
    }

    /** 展开搜索 URL：先按 expandUrl 处理，再把 ** 换成编码后的关键词。 */
    public static String expandSearchUrl(String template, String keyword, int page) {
        return expandSearchUrl(template, keyword, page, true);
    }

    /** 展开搜索 URL；stripEmptyPrefix=false 时保留 hiker://empty# 前缀（js: 规则用）。 */
    public static String expandSearchUrl(String template, String keyword, int page, boolean stripEmptyPrefix) {
        String url = expandUrl(template, "", "", "", "", page, stripEmptyPrefix);
        String kw = keyword == null ? "" : keyword;
        try {
            kw = URLEncoder.encode(kw, "UTF-8");
        } catch (Exception ignored) {
        }
        return url.replace("**", kw);
    }

    private static int applyOps(int page, String ops) {
        int v = page;
        if (ops == null) return v;
        Matcher m = FYPAGE_OP.matcher(ops);
        while (m.find()) {
            int n;
            try {
                n = Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            switch (m.group(1)) {
                case "+":
                    v += n;
                    break;
                case "-":
                    v -= n;
                    break;
                case "*":
                    v *= n;
                    break;
                case "/":
                    if (n != 0) v /= n;
                    break;
                default:
                    break;
            }
        }
        return v;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * URL 增强解析结果（官方格式 {@code url;method;encoding;{K@V&&K2@V2}}）。
     */
    public static class Enhancement {
        public String url = "";
        public String method = "GET";
        public String charset = "UTF-8";
        public final Map<String, String> headers = new HashMap<>();

        /** 是否为"干净"请求（无增强，按原逻辑走即可）。 */
        public boolean isPlain() {
            return "GET".equals(method) && "UTF-8".equalsIgnoreCase(charset) && headers.isEmpty();
        }
    }

    /**
     * 解析 URL 增强：{@code url;method;encoding;{K@V&&K2@V2}}。
     * header 块用 {@code {}} 包裹、内以 {@code &&} 分隔、键值以 {@code @} 分隔。
     */
    public static Enhancement parseEnhancement(String raw) {
        Enhancement e = new Enhancement();
        if (raw == null) return e;
        String u = raw.trim();
        int b = u.indexOf('{');
        int be = u.lastIndexOf('}');
        if (b > 0 && be > b) {
            for (String kv : u.substring(b + 1, be).split("&&")) {
                int at = kv.indexOf('@');
                if (at > 0) e.headers.put(kv.substring(0, at).trim(), kv.substring(at + 1).trim());
            }
            u = (u.substring(0, b) + u.substring(be + 1)).trim();
        }
        String[] parts = u.split(";", -1);
        e.url = parts[0].trim();
        if (parts.length > 1 && !parts[1].trim().isEmpty()) e.method = parts[1].trim().toUpperCase();
        if (parts.length > 2 && !parts[2].trim().isEmpty()) e.charset = parts[2].trim();
        return e;
    }

    /**
     * 简单 GET 请求，返回文本。
     *
     * @throws IOException        网络失败/非 2xx/空 body
     * @throws IllegalArgumentException 非 http(s) 链接（hiker://、js: 等需 M2 的 JS 运行时处理）
     */
    public static String get(String url, String ua) throws IOException {
        Enhancement e = parseEnhancement(url);
        if (e.isPlain()) return getPlain(e.url, ua);
        return getEnhanced(e, ua);
    }

    /**
     * 下载二进制（图片解密用）。支持 headers map。
     */
    public static byte[] getBytes(String url, Map<String, String> headers) throws IOException {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("不支持的链接协议: " + url);
        }
        Request.Builder rb = new Request.Builder().url(url)
                .header("User-Agent", DEFAULT_UA)
                .header("Accept", "image/*,*/*;q=0.8");
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) {
                try {
                    rb.header(h.getKey(), h.getValue());
                } catch (Throwable ignored) {
                }
            }
        }
        applyThreadTag(rb);
        try (Response resp = client().newCall(rb.build()).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + " " + url);
            ResponseBody body = resp.body();
            if (body == null) throw new IOException("空响应 " + url);
            return body.bytes();
        }
    }

    /** 无增强的原逻辑（行为与之前完全一致）。 */
    private static String getPlain(String url, String ua) throws IOException {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("不支持的链接协议（需 M2 JS 运行时）：" + url);
        }
        Request.Builder plainRb = new Request.Builder().url(url)
                .header("User-Agent", ua == null || ua.isEmpty() ? DEFAULT_UA : ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9");
        applyThreadTag(plainRb);
        Request req = plainRb.build();
        try (Response resp = client().newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + " " + url);
            ResponseBody body = resp.body();
            if (body == null) throw new IOException("空响应 " + url);
            return body.string();
        }
    }

    /** 带 method/encoding/headers 的增强请求。 */
    private static String getEnhanced(Enhancement e, String ua) throws IOException {
        String url = e.url;
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("不支持的链接协议（需 M2 JS 运行时）：" + url);
        }
        Request.Builder rb = new Request.Builder().url(url)
                .header("User-Agent", ua == null || ua.isEmpty() ? DEFAULT_UA : ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9");
        for (Map.Entry<String, String> h : e.headers.entrySet()) {
            try {
                rb.header(h.getKey(), h.getValue());
            } catch (Throwable ignored) {
            }
        }
        if ("POST".equals(e.method)) {
            rb.post(RequestBody.create(new byte[0], null));
        }
        applyThreadTag(rb);
        try (Response resp = client().newCall(rb.build()).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + " " + url);
            ResponseBody body = resp.body();
            if (body == null) throw new IOException("空响应 " + url);
            byte[] bytes = body.bytes();
            try {
                return new String(bytes, e.charset);
            } catch (Throwable t) {
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }
}
