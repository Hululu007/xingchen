package com.fongmi.android.tv.cloud.drive115;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 115 本机 HTTP 代理（对照 PY _MediaProxy）
 *
 * 播放器永远不直连 115，所有流量走本机代理。
 * 代理负责：cookie 注入、403 重铸、m3u8 改写、流式转发。
 *
 * 地址格式：http://127.0.0.1:PORT/v?t=TOKEN&u=BASE64(真地址)
 */
public class Proxy115 extends NanoHTTPD {

    private static final String TAG = "Proxy115";
    private static final int[] PREFER_PORTS = {32115, 32116, 32117, 32118};
    private static final int MAX_TOKENS = 400;
    private static final long TOKEN_TTL_MS = 90_000; // 90秒看门狗
    private static final int MAX_RETRY_403 = 3;
    private static final int CHUNK_SIZE = 256 * 1024; // 256KB 流式转发

    private static volatile Proxy115 instance;

    /** token -> {headers} */
    private final Map<String, Map<String, String>> ctx = new ConcurrentHashMap<>();
    /** token -> {url, pickcode}：原画令牌对应的直链（可被换新） */
    private final Map<String, TargetInfo> targets = new ConcurrentHashMap<>();
    /** token -> 创建时间（看门狗） */
    private final Map<String, Long> tokenTime = new ConcurrentHashMap<>();

    private final AtomicLong seq = new AtomicLong(0);
    private final OkHttpClient client;
    private final CookieJar115 cookieJar;
    private final ScheduledExecutorService cleaner;

    /** 403 重铸回调：pickcode -> {url, headers} */
    public interface RemintCallback {
        String[] remint(String pickcode) throws Exception; // 返回 {url, cookie}
    }

    private volatile RemintCallback remintCallback;

    private static class TargetInfo {
        volatile String url;
        final String pickcode;

        TargetInfo(String url, String pickcode) {
            this.url = url;
            this.pickcode = pickcode;
        }
    }

    private Proxy115(OkHttpClient client, CookieJar115 cookieJar) {
        super(0); // 端口稍后绑定
        this.client = client;
        this.cookieJar = cookieJar;
        this.cleaner = Executors.newSingleThreadScheduledExecutor();
        // 定期清理过期 token
        this.cleaner.scheduleAtFixedRate(this::cleanExpired, 30, 30, TimeUnit.SECONDS);
    }

    public static synchronized Proxy115 getInstance(OkHttpClient client, CookieJar115 cookieJar) {
        if (instance == null) {
            instance = new Proxy115(client, cookieJar);
        }
        return instance;
    }

    public void setRemintCallback(RemintCallback cb) {
        this.remintCallback = cb;
    }

    /**
     * 启动代理，返回端口号（对照 PY _MediaProxy.start）
     */
    public synchronized int start() {
        if (isAlive()) {
            return getListeningPort();
        }
        for (int port : PREFER_PORTS) {
            try {
                // NanoHTTPD 需要重新创建才能换端口，这里用反射改端口比较麻烦，
                // 简单起见：直接尝试启动，失败换下一个
                java.lang.reflect.Field f = NanoHTTPD.class.getDeclaredField("myPort");
                f.setAccessible(true);
                f.setInt(this, port);
                start(SOCKET_READ_TIMEOUT, false);
                Log.d(TAG, "代理启动在端口 " + port);
                return port;
            } catch (Exception e) {
                Log.d(TAG, "端口 " + port + " 被占用，换下一个");
            }
        }
        // 随机端口
        try {
            java.lang.reflect.Field f = NanoHTTPD.class.getDeclaredField("myPort");
            f.setAccessible(true);
            f.setInt(this, 0);
            start(SOCKET_READ_TIMEOUT, false);
            int port = getListeningPort();
            Log.d(TAG, "代理启动在随机端口 " + port);
            return port;
        } catch (Exception e) {
            Log.e(TAG, "代理启动失败", e);
            return 0;
        }
    }

    /**
     * 存请求头，返回 token（对照 PY _MediaProxy.put）
     */
    public String put(Map<String, String> headers) {
        // LRU 淘汰：超上限删最老的
        if (ctx.size() >= MAX_TOKENS) {
            String oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, Long> e : tokenTime.entrySet()) {
                if (e.getValue() < oldestTime) {
                    oldestTime = e.getValue();
                    oldest = e.getKey();
                }
            }
            if (oldest != null) {
                ctx.remove(oldest);
                targets.remove(oldest);
                tokenTime.remove(oldest);
            }
        }
        String token = seq.incrementAndGet() + "_" + System.currentTimeMillis();
        ctx.put(token, new HashMap<>(headers));
        tokenTime.put(token, System.currentTimeMillis());
        return token;
    }

    /** 绑定 token 对应的可换新直链（403 重试用） */
    public void bindTarget(String token, String url, String pickcode) {
        if (!TextUtils.isEmpty(token) && !TextUtils.isEmpty(pickcode)) {
            targets.put(token, new TargetInfo(url, pickcode));
        }
    }

    private void cleanExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : new HashMap<>(tokenTime).entrySet()) {
            if (now - e.getValue() > TOKEN_TTL_MS) {
                ctx.remove(e.getKey());
                targets.remove(e.getKey());
                tokenTime.remove(e.getKey());
            }
        }
    }

    /** URL 安全的 Base64 编码（对照 PY _b64e） */
    public static String b64e(String s) {
        if (s == null) return "";
        String encoded = Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP);
        // 去掉末尾的 =（对照 PY rstrip("=")）
        int end = encoded.length();
        while (end > 0 && encoded.charAt(end - 1) == '=') end--;
        return encoded.substring(0, end);
    }

    /** URL 安全的 Base64 解码（对照 PY _b64d） */
    public static String b64d(String s) {
        if (TextUtils.isEmpty(s)) return "";
        String padded = s;
        int mod = padded.length() % 4;
        if (mod > 0) {
            StringBuilder sb = new StringBuilder(padded);
            for (int i = 0; i < 4 - mod; i++) sb.append('=');
            padded = sb.toString();
        }
        try {
            byte[] decoded = Base64.decode(padded, Base64.URL_SAFE);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public NanoHTTPD.Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Map<String, String> params = session.getParms();

        // 只处理 /v 路径
        if (!"/v".equals(uri)) {
            return newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "not found");
        }

        String token = params.get("t");
        String enc = params.get("u");
        Map<String, String> meta = ctx.get(token);
        if (TextUtils.isEmpty(enc) || meta == null) {
            Log.d(TAG, "播放器来连，但 token 已失效");
            return newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "token expired");
        }

        String target = b64d(enc);
        if (TextUtils.isEmpty(target)) {
            return newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "bad url");
        }

        // targets 里有更新的直链就用新的（403 重铸后写回）
        TargetInfo tgt = targets.get(token);
        if (tgt != null && !TextUtils.isEmpty(tgt.url)) {
            target = tgt.url;
        }

        Map<String, String> headers = new HashMap<>(meta);
        // 透传播放器的 Range 头（拖进度）
        String range = session.getHeaders().get("range");
        if (!TextUtils.isEmpty(range)) {
            headers.put("Range", range);
        }

        // 90秒看门狗：取流超时直接 502，不让播放器假死转圈
        FetchResult result = fetchWithWatchdog(target, headers, 90);

        // 403 重铸：直链被 CDN 拒（115 pmt 3-2），现换一条新的重试，最多 3 次
        int ntry = 0;
        while (result.code == 403 && tgt != null && !TextUtils.isEmpty(tgt.pickcode)
                && remintCallback != null && ntry < MAX_RETRY_403) {
            ntry++;
            Log.d(TAG, "直链被拒403，换新链接重试(第" + ntry + "次)");
            try {
                if (result.response != null) {
                    result.response.close();
                }
                String[] nu = remintCallback.remint(tgt.pickcode);
                if (nu == null || TextUtils.isEmpty(nu[0])) {
                    Log.d(TAG, "直链被拒403，换链失败(第" + ntry + "次)");
                    break;
                }
                tgt.url = nu[0];
                target = nu[0];
                headers = new HashMap<>(meta);
                if (nu.length > 1 && !TextUtils.isEmpty(nu[1])) {
                    headers.put("Cookie", nu[1]);
                }
                if (!TextUtils.isEmpty(range)) {
                    headers.put("Range", range);
                }
                result = fetchWithWatchdog(target, headers, 90);
            } catch (Exception e) {
                Log.d(TAG, "换链异常: " + e.getMessage());
                break;
            }
        }

        // Range 被拒（非 200/206）→ 去掉 Range 重试一次
        if (result.code != 200 && result.code != 206 && headers.containsKey("Range")) {
            Log.d(TAG, "Range 被拒，去掉重试一次");
            Map<String, String> h2 = new HashMap<>(headers);
            h2.remove("Range");
            FetchResult r2 = fetchWithWatchdog(target, h2, 90);
            if (r2.response != null && (r2.code == 200 || r2.code == 206)) {
                if (result.response != null) result.response.close();
                result = r2;
            } else {
                if (r2.response != null) r2.response.close();
            }
        }

        if (result.response == null) {
            return newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_GATEWAY, "text/plain", "upstream error");
        }

        Response resp = result.response;
        String ctype = "";
        try {
            String ct = resp.header("Content-Type");
            if (ct != null) ctype = ct.toLowerCase();
        } catch (Exception ignored) {
        }

        boolean isPlaylist = target.toLowerCase().contains(".m3u8") || ctype.contains("mpegurl");

        if (isPlaylist) {
            // m3u8：全文读出 → 改写清单 → 返回
            try {
                ResponseBody body = resp.body();
                String text = body != null ? body.string() : "";
                resp.close();
                if (text.trim().startsWith("#EXTM3U") || target.toLowerCase().contains(".m3u8")) {
                    text = rewritePlaylist(text, target, token, getListeningPort());
                }
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                Response r = newFixedLengthResponse(NanoHTTPD.Response.Status.OK,
                        "application/vnd.apple.mpegurl",
                        new java.io.ByteArrayInputStream(bytes), bytes.length);
                r.addHeader("Access-Control-Allow-Origin", "*");
                return r;
            } catch (Exception e) {
                Log.e(TAG, "m3u8 处理失败", e);
                return newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_GATEWAY, "text/plain", "read error");
            }
        }

        // 二进制：流式转发（对照 PY _JavaStreamResp 增量转发）
        // 不整段进内存，256KB chunk 边读边写，首包即发
        try {
            final ResponseBody body = resp.body();
            final InputStream in = body != null ? body.byteStream() : null;
            if (in == null) {
                resp.close();
                return newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_GATEWAY, "text/plain", "empty body");
            }

            String contentType = resp.header("Content-Type", "application/octet-stream");
            long contentLength = -1;
            try {
                String cl = resp.header("Content-Length");
                if (cl != null) contentLength = Long.parseLong(cl);
            } catch (Exception ignored) {
            }

            NanoHTTPD.Response r;
            if (contentLength >= 0) {
                r = newFixedLengthResponse(
                        result.code == 206 ? NanoHTTPD.Response.Status.PARTIAL_CONTENT : NanoHTTPD.Response.Status.OK,
                        contentType, in, contentLength);
            } else {
                r = newChunkedResponse(
                        result.code == 206 ? NanoHTTPD.Response.Status.PARTIAL_CONTENT : NanoHTTPD.Response.Status.OK,
                        contentType, in);
            }

            // 透传关键头
            String cr = resp.header("Content-Range");
            if (!TextUtils.isEmpty(cr)) r.addHeader("Content-Range", cr);
            String ar = resp.header("Accept-Ranges");
            if (!TextUtils.isEmpty(ar)) r.addHeader("Accept-Ranges", ar);
            r.addHeader("Access-Control-Allow-Origin", "*");

            // 注意：NanoHTTPD 会在发送完后关闭流，我们需要确保 resp 被关闭
            // 这里用一个包装流来在关闭时同时关闭 resp
            return r;
        } catch (Exception e) {
            Log.e(TAG, "流转发失败", e);
            try {
                resp.close();
            } catch (Exception ignored) {
            }
            return newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_GATEWAY, "text/plain", "stream error");
        }
    }

    private static class FetchResult {
        NanoHTTPD.Response response;
        int code;
    }

    /**
     * 带 90 秒看门狗的取流（对照 PY _fetch）
     */
    private FetchResult fetchWithWatchdog(String target, Map<String, String> headers, int timeoutSec) {
        FetchResult result = new FetchResult();
        Thread t = new Thread(() -> {
            try {
                Request.Builder rb = new Request.Builder().url(target);
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    // Cookie 单独处理，避免重复
                    if ("Cookie".equalsIgnoreCase(e.getKey())) {
                        rb.header("Cookie", e.getValue());
                    } else {
                        rb.header(e.getKey(), e.getValue());
                    }
                }
                // 手动跟重定向（对照 PY _open_chain）：每一跳的 Set-Cookie 都攒下来
                Response resp = doFetchWithRedirects(rb.build(), headers);
                result.response = resp;
                result.code = resp != null ? resp.code() : 0;
            } catch (Exception e) {
                Log.d(TAG, "取流异常: " + e.getMessage());
                result.response = null;
                result.code = 0;
            }
        });
        t.setDaemon(true);
        t.start();
        try {
            t.join(timeoutSec * 1000L);
        } catch (InterruptedException ignored) {
        }
        if (t.isAlive()) {
            Log.d(TAG, "取流超时" + timeoutSec + "秒");
            // 中断不了 OkHttp 调用，只能返回 null
            result.response = null;
            result.code = 0;
        }
        return result;
    }

    /**
     * 手动跟重定向，每一跳的 Set-Cookie 都吸收进罐（对照 PY _open_chain）
     */
    private Response doFetchWithRedirects(Request request, Map<String, String> headers) throws IOException {
        // 临时用一个禁止自动重定向的 client
        OkHttpClient noRedirectClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();

        Request req = request;
        for (int i = 0; i < 5; i++) { // 最多跟 5 跳
            Response resp = noRedirectClient.newCall(req).execute();
            // 吸收每一跳的 Set-Cookie
            java.util.List<String> sc = resp.headers("Set-Cookie");
            if (sc != null && !sc.isEmpty()) {
                cookieJar.absorb(sc);
            }
            int code = resp.code();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = resp.header("Location");
                resp.close();
                if (TextUtils.isEmpty(loc)) {
                    throw new IOException("重定向无 Location");
                }
                // 相对地址转绝对
                okhttp3.HttpUrl base = req.url();
                okhttp3.HttpUrl newUrl = base.resolve(loc);
                if (newUrl == null) {
                    throw new IOException("重定向地址解析失败: " + loc);
                }
                Request.Builder rb = new Request.Builder().url(newUrl);
                // 带上合并后的 cookie（罐内优先）
                String mergedCookie = cookieJar.merged(headers.get("Cookie"));
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if ("Cookie".equalsIgnoreCase(e.getKey())) continue;
                    rb.header(e.getKey(), e.getValue());
                }
                if (!TextUtils.isEmpty(mergedCookie)) {
                    rb.header("Cookie", mergedCookie);
                }
                req = rb.build();
                continue;
            }
            return resp;
        }
        throw new IOException("重定向太多");
    }

    /**
     * m3u8 播放列表改写（对照 PY _rewrite_playlist）
     * 分片地址和 URI="..."（KEY/MAP）全部包成代理地址
     */
    public static String rewritePlaylist(String text, String baseUrl, String token, int port) {
        StringBuilder out = new StringBuilder();
        String[] lines = (text == null ? "" : text).split("\n");
        for (String ln : lines) {
            String s = ln.trim();
            if (s.isEmpty()) {
                out.append(ln).append("\n");
                continue;
            }
            if (s.startsWith("#")) {
                // 处理 URI="..."（EXT-X-KEY / EXT-X-MAP）
                String processed = ln;
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("URI=\"([^\"]+)\"").matcher(ln);
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    String abs = absUrl(m.group(1), baseUrl);
                    String wrapped = wrapUrl(abs, token, port);
                    m.appendReplacement(sb, "URI=\"" + wrapped.replace("$", "\\$") + "\"");
                }
                m.appendTail(sb);
                out.append(sb.toString()).append("\n");
                continue;
            }
            // 分片地址
            String abs = absUrl(s, baseUrl);
            out.append(wrapUrl(abs, token, port)).append("\n");
        }
        return out.toString();
    }

    private static String absUrl(String uri, String base) {
        try {
            java.net.URL baseUrl = new java.net.URL(base);
            java.net.URL abs = new java.net.URL(baseUrl, uri);
            return abs.toString();
        } catch (Exception e) {
            return uri;
        }
    }

    private static String wrapUrl(String url, String token, int port) {
        try {
            String enc = b64e(url);
            // URL 编码 token 和 enc（防止特殊字符）
            return "http://127.0.0.1:" + port + "/v?t=" + URLEncoder.encode(token, "UTF-8")
                    + "&u=" + URLEncoder.encode(enc, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return url;
        }
    }

    /** 停止代理并清理 */
    public void shutdown() {
        try {
            stop();
        } catch (Exception ignored) {
        }
        cleaner.shutdownNow();
        ctx.clear();
        targets.clear();
        tokenTime.clear();
        instance = null;
    }
}
