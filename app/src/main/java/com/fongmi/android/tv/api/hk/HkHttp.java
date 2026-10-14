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
    /** DNS 解析封顶（秒）：见 HK_DNS 注释 */
    private static final long DNS_TIMEOUT_SEC = 10;
    private static final java.util.concurrent.ExecutorService DNS_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "hk-dns");
                t.setDaemon(true);
                return t;
            });

    /**
     * 海阔专用的 DNS（含 DoH），移植自 8.83 MoreSettingOfficer.loadDns 逻辑。
     * 外加一层解析封顶（兜底、非 8.83 原有）：本地实测（OkHttp 5.4.0 与 4.9.3 两代一致）
     * callTimeout/connect/read/write 全部管不到 DNS 解析阶段——被污染域名让系统解析器
     * 干挂时，execute() 既不返回也不抛错（1.2.7 真机日志：tjasak 的 POST 发出后 50 秒+
     * 无下文）。故把 lookup 放到达守护线程池、10 秒未回就按解析失败处理，让请求走
     * 正常的失败/重试路径，而不是无限静默。成功路径行为不变。
     *
     * <p>2026-10-14：取数 client() 改回系统默认解析（照 8.83
     * HttpHelper.buildOkHttpClient 原文，未设置 Dns）。漫小肆 www.jjmhw5.top
     * 在补了 charset 头后依然被 Connection reset，真机 dns 被动记录确认取数
     * 走了这里的 DoH，而同机同网原版（无自定义 Dns）能取到——解析路径分叉
     * 是唯一未排除的嫌疑；用户其余部位（PY/JS/主站）的 DoH 设置不受影响。
     * 图片 client 曾误装 HK_DNS（恒走 DoH、无回退）致零封面，已换 HK_IMAGE_DNS
     * （默认系统解析，仅 haikuoshijie 类地址在开 DoH 时走 DoH）；HK_DNS 保留，
     * 现无链使用，不要动它的行为。</p>
     */
    private static final Dns HK_DNS = new Dns() {
        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            DnsOverHttps doh = hkDoh;
            Dns delegate = doh != null ? doh : Dns.SYSTEM;
            java.util.concurrent.Future<List<InetAddress>> f =
                    DNS_POOL.submit(() -> delegate.lookup(hostname));
            try {
                return f.get(DNS_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                f.cancel(true);
                throw new UnknownHostException("dns timeout: " + hostname);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof UnknownHostException) throw (UnknownHostException) c;
                throw new UnknownHostException(String.valueOf(c));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UnknownHostException("dns interrupted: " + hostname);
            }
        }
    };
    private static volatile DnsOverHttps hkDoh;

    /**
     * 图片 client 专用 DNS（照 8.83 OkHttpUrlLoader.buildLoadData 口径）：
     * 默认系统解析；仅 host 含 "haikuoshijie" 的地址（原版
     * DohProvidersKt.shouldUseAutoDNS 口径）在用户开了 DoH 时走 hkDoh，
     * 其余一律系统解析。
     *
     * <p>8.83 的图片 client 在 builder 上不装自定义 Dns（loadDns 默认"智能"
     * 不装任何东西），按地址加 DoH 是逐请求在 buildLoadData 里判的；此前
     * 移植误把恒走 DoH 的 HK_DNS 装到整个图片 client 上，用户开 DoH 后所有
     * 图片域名无回退地走 DoH，picx.drleao.cn 查不到即 UnknownHostException
     * → 解密拿到空 → 封面全白（确定性零封面）。</p>
     *
     * <p>10 秒解析封顶保留（非 8.83 原有、防污染域名干挂的既有保护）。</p>
     */
    private static final Dns HK_IMAGE_DNS = new Dns() {
        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            DnsOverHttps doh = hkDoh;
            Dns delegate = (doh != null && isAutoDnsHost(hostname)) ? doh : Dns.SYSTEM;
            java.util.concurrent.Future<List<InetAddress>> f =
                    DNS_POOL.submit(() -> delegate.lookup(hostname));
            try {
                return f.get(DNS_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                f.cancel(true);
                throw new UnknownHostException("dns timeout: " + hostname);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof UnknownHostException) throw (UnknownHostException) c;
                throw new UnknownHostException(String.valueOf(c));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UnknownHostException("dns interrupted: " + hostname);
            }
        }
    };

    /**
     * 照原版 DohProvidersKt.shouldUseAutoDNS：host 含 haikuoshijie 即走自动 DNS。
     * 原版另有远端下发的 autoDomain 名单一并参与，星辰未移植该名单，按任务口径只保留 haikuoshijie 一类。
     */
    private static boolean isAutoDnsHost(String hostname) {
        return hostname != null && hostname.toLowerCase(java.util.Locale.US).contains("haikuoshijie");
    }

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
     * - DNS：HK_IMAGE_DNS——默认系统解析，仅 haikuoshijie 类地址在用户开 DoH 时走 DoH
     *   （照 8.83 buildLoadData 逐地址判定口径；此前误装恒走 DoH 的 HK_DNS 致零封面）
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
                            .dns(HK_IMAGE_DNS)
                            .connectTimeout(15, TimeUnit.SECONDS)
                            // 8.83 OkHttpUrlLoader.Factory 内建 client：read/write 各 15 秒
                            .readTimeout(15, TimeUnit.SECONDS)
                            .writeTimeout(15, TimeUnit.SECONDS)
                            // 总超时必须有：connect/read/write 都管不住 DNS 解析阶段，
                            // 域名被污染时解析能无限挂起，图片请求整批死等（真机封面全白同因）
                            .callTimeout(45, TimeUnit.SECONDS);
                    // 8.83 图片 client dispatcher maxRequestsPerHost = 48
                    okhttp3.Dispatcher d = new okhttp3.Dispatcher();
                    d.setMaxRequestsPerHost(48);
                    b.dispatcher(d);
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
     * - DNS：系统默认（照 8.83 原文，HttpHelper.buildOkHttpClient 未设置 Dns；
     *   图片 client 用 HK_IMAGE_DNS：默认系统解析，仅 haikuoshijie 类地址在开 DoH 时走 DoH）
     * - ContentTypePreInterceptor + ContentTypeAfterInterceptor（network）
     * - CookieJar 持久化
     * - 统一超时
     */
    // 8.83 HttpHelper.buildOkHttpClient 移植：公开供 HkJsRuntime 等调用方使用，
    // 使海阔 JS 请求与 HkHttp 同走 CookieJar / 内容类型拦截器配置；
    // DNS 走系统默认（照 8.83 原文；图片 client 用 HK_IMAGE_DNS：默认系统解析，
    // 仅 haikuoshijie 类地址在开 DoH 时走 DoH）。
    public static OkHttpClient client() {
        if (client == null) {
            synchronized (HkHttp.class) {
                if (client == null) {
                    OkHttpClient.Builder b = new OkHttpClient.Builder()
                            .sslSocketFactory(HkSsl.getSocketFactory(), HkSsl.TRUST_MANAGER)
                            .hostnameVerifier(HkSsl.HOSTNAME_VERIFIER)
                            // 8.83 原版：Application.onCreate 里 OkGo.getInstance().addCommonHeaders
                            // 加了公共头 charset=UTF-8，每个规则请求都带；此前移植漏了，补上
                            .addInterceptor(chain -> {
                                Request req = chain.request();
                                if (req.header("charset") == null) {
                                    req = req.newBuilder().header("charset", "UTF-8").build();
                                }
                                return chain.proceed(req);
                            })
                            .addInterceptor(HkContentTypePreInterceptor.INSTANCE)
                            .addNetworkInterceptor(HkContentTypeAfterInterceptor.INSTANCE)
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            // 总超时必须有（2026-10-13 真机+本地复现定案）：connect/read/write
                            // 都覆盖不到 DNS 解析阶段，摸鱼日报的接口域名被污染后解析无限挂起，
                            // execute() 既不返回也不抛错，加载线程死等、转圈永转、日志无下文。
                            // callTimeout 把整条调用（含解析）封顶，超时后正常抛错走失败路径。
                            .callTimeout(45, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            // 诊断用：只记解析结果（域名→IP 列表），不改变任何行为。
                            // 漫小肆取数被重置的排查需要知道每次实际连的是哪个 IP
                            .eventListener(new okhttp3.EventListener() {
                                @Override
                                public void dnsEnd(okhttp3.Call call, String domainName, List<InetAddress> inetAddressList) {
                                    try {
                                        StringBuilder sb = new StringBuilder();
                                        if (inetAddressList != null) {
                                            for (InetAddress a : inetAddressList) {
                                                if (sb.length() > 0) sb.append(',');
                                                sb.append(a.getHostAddress());
                                            }
                                        }
                                        com.github.catvod.crawler.SpiderDebug.log("hk", "dns %s -> %s", domainName, sb);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            });
                            // retryOnConnectionFailure 不显式关闭：8.83 HttpHelper 原文未设置
                            // （OkHttp 默认 true，多地址时可换路由重试），此前被仿写版写成 false
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
        // 入口留痕：此前只有完成/失败才记 fetch 诊断，卡在 execute 里时日志里
        // 分不清"没走到"还是"走到了没回来"（1.2.6 真机日志的教训）
        try {
            okhttp3.HttpUrl hu = okhttp3.HttpUrl.parse(url);
            com.github.catvod.crawler.SpiderDebug.log("hk", "fetch start host=%s", hu == null ? "?" : hu.host());
        } catch (Throwable ignored) {
        }
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
        } else if (req.getMethod().equalsIgnoreCase("put")) {
            // 8.83：PUT 走 OkGo.put 带 body；此前 PUT/HEAD 一律掉进 GET，body 被丢
            return rb.put(getPostBody(req, headers.get(HttpHeaders.CONTENT_TYPE))).build();
        } else if (req.getMethod().equalsIgnoreCase("head") || req.getMethod().equalsIgnoreCase("header")) {
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
        // 无 Content-Type 时绝不丢 body（此前直接返回空 body：空 POST 出去、服务端回 200 空正文，规则全灭）。
        // 与 fetch0 共用同一套 8.83 拼法（buildPostBody 按内容判 JSON/表单），消除两套解析并存。
        if (req.getBody() != null) return HkHttpHelper.buildPostBody(req.getMethod(), req.getBody(), null, req.isRejectCoding(), new java.util.HashMap<>());
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
        return get(url, ua, 0);
    }

    /**
     * 带超时的 GET（点击预检等交互链路用，避免长时间无响应导致"点了没反应"）。
     * timeoutMs<=0 时走默认超时（connect 15s / read-write 30s）。
     */
    public static String get(String url, String ua, int timeoutMs) throws IOException {
        Enhancement e = parseEnhancement(url);
        if (e.isPlain()) return getPlain(e.url, ua, timeoutMs);
        return getEnhanced(e, ua, timeoutMs);
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

    /** 无增强的原逻辑（行为与之前完全一致）。timeoutMs>0 时覆盖默认超时。 */
    private static String getPlain(String url, String ua, int timeoutMs) throws IOException {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("不支持的链接协议（需 M2 JS 运行时）：" + url);
        }
        Request.Builder plainRb = new Request.Builder().url(url)
                .header("User-Agent", ua == null || ua.isEmpty() ? DEFAULT_UA : ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9");
        applyThreadTag(plainRb);
        Request req = plainRb.build();
        OkHttpClient c = timeoutMs > 0
                ? client().newBuilder()
                        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .build()
                : client();
        try (Response resp = c.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + " " + url);
            ResponseBody body = resp.body();
            if (body == null) throw new IOException("空响应 " + url);
            return body.string();
        }
    }

    /** 带 method/encoding/headers 的增强请求。timeoutMs>0 时覆盖默认超时。 */
    private static String getEnhanced(Enhancement e, String ua, int timeoutMs) throws IOException {
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
        OkHttpClient ce = timeoutMs > 0
                ? client().newBuilder()
                        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .build()
                : client();
        try (Response resp = ce.newCall(rb.build()).execute()) {
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
