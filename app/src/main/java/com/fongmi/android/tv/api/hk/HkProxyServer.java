package com.fongmi.android.tv.api.hk;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileInputStream;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 本地代理服务器（8.83 RemoteServerManager + DlanWebController /proxy 端点的移植）。
 * 8.83 原文：
 *   RemoteServerManager.smali（374 行）：单例，port=0xcb34(52020)，setProxyJS/startServer/
 *     getServerUrl/destroyServer/isRunning，基于 AndServer。
 *   DlanWebController.proxy (smali:1464)：/proxy 端点，取 proxyJS，用请求参数拼 MY_PARAMS，
 *     调 JSEngine.evalJSForObj 求值，按 ProxyResult{statusCode,headers,body} 回包。
 * 星辰实现：AndServer 换成项目已有的 NanoHTTPD；只实现 startProxyServer 需要的 /proxy 端点，
 *   DLNA/web 管理等其它端点属 App 壳子，不移植。
 */
public class HkProxyServer extends NanoHTTPD {

    public static final int PORT = 0xcb34;

    private static volatile HkProxyServer sInstance;

    private volatile String proxyJS;
    private volatile HkJsRuntime evaluator;

    private static final Gson GSON = new Gson();

    private HkProxyServer() {
        super(PORT);
    }

    public static HkProxyServer instance() {
        if (sInstance == null) {
            synchronized (HkProxyServer.class) {
                if (sInstance == null) sInstance = new HkProxyServer();
            }
        }
        return sInstance;
    }

    public void setProxyJS(String js) {
        this.proxyJS = js;
    }

    public String getProxyJS() {
        return proxyJS;
    }

    public void setEvaluator(HkJsRuntime runtime) {
        this.evaluator = runtime;
    }

    public boolean isRunning() {
        return super.isAlive();
    }

    public synchronized void startServer() throws Exception {
        if (!isRunning()) {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        }
    }

    public synchronized void destroyServer() {
        if (isRunning()) stop();
        proxyJS = null;
        evaluator = null;
    }

    public String getServerUrl() {
        String ip = getLocalIp();
        if (ip == null || ip.isEmpty()) return null;
        return "http://" + ip + ":" + PORT;
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

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "");
        if (uri.equals("/proxy") || uri.startsWith("/proxy?") || uri.startsWith("/proxy/")) {
            return handleProxy(session);
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "");
    }

    /**
     * 8.83 DlanWebController.proxy 语义：
     *   proxyJS 为空 -> 返回 ""；
     *   否则把请求参数转 JSON 拼成 var MY_PARAMS = JSON.parse('...');\n + proxyJS，
     *   用 JSEngine.evalJSForObj(js, requestPath) 求值；
     *   结果是 "{...}" 形式的字符串 -> 按 ProxyResult{statusCode,headers,body} 回包；
     *   Location 头为 file:// 开头 -> 返回本地文件。
     */
    private Response handleProxy(IHTTPSession session) {
        String js = proxyJS;
        if (js == null || js.isEmpty()) {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "");
        }
        HkJsRuntime rt = evaluator;
        if (rt == null) {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "");
        }
        try {
            Map<String, List<String>> params = session.getParameters();
            Map<String, Object> flat = new HashMap<>();
            if (params != null) {
                for (Map.Entry<String, List<String>> e : params.entrySet()) {
                    List<String> v = e.getValue();
                    if (v != null && !v.isEmpty()) {
                        flat.put(e.getKey(), v.size() == 1 ? v.get(0) : new ArrayList<>(v));
                    }
                }
            }
            String paramsJson = GSON.toJson(flat);
            String script = "var MY_PARAMS = JSON.parse('" + escapeJs(paramsJson) + "');\n" + js;
            String path = session.getUri();
            Object result = rt.evalJSForObj(script, path);
            return buildProxyResponse(result);
        } catch (Throwable t) {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "");
        }
    }

    private Response buildProxyResponse(Object result) {
        try {
            if (result instanceof String) {
                String s = (String) result;
                if (s != null && s.startsWith("{") && s.endsWith("}")) {
                    try {
                        ProxyResult pr = GSON.fromJson(s, ProxyResult.class);
                        if (pr != null && ((pr.headers != null && !pr.headers.isEmpty()) || (pr.body != null && !pr.body.isEmpty()))) {
                            return proxyResultResponse(pr);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, s == null ? "" : s);
            }
            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) result;
                return proxyResultMapResponse(map);
            }
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, result == null ? "" : String.valueOf(result));
        } catch (Throwable t) {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "");
        }
    }

    private Response proxyResultResponse(ProxyResult pr) {
        Response.Status status = Response.Status.OK;
        if (pr.statusCode >= 200) {
            Response.Status mapped = Response.Status.lookup(pr.statusCode);
            if (mapped != null) status = mapped;
        }
        if (pr.headers != null && pr.headers.containsKey("Location")) {
            String loc = String.valueOf(pr.headers.get("Location"));
            if (loc != null && loc.startsWith("file://")) {
                try {
                    File f = new File(loc.replace("file://", ""));
                    if (f.exists() && f.isFile()) {
                        return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", new FileInputStream(f), f.length());
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        Response resp = newFixedLengthResponse(status, MIME_PLAINTEXT, pr.body == null ? "" : pr.body);
        if (pr.headers != null) {
            for (Map.Entry<String, String> e : pr.headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    resp.addHeader(e.getKey(), e.getValue());
                }
            }
        }
        return resp;
    }

    private Response proxyResultMapResponse(Map<String, Object> map) {
        Response.Status status = Response.Status.OK;
        if (map.containsKey("statusCode")) {
            try {
                int code = ((Number) map.get("statusCode")).intValue();
                Response.Status mapped = Response.Status.lookup(code);
                if (mapped != null) status = mapped;
            } catch (Throwable ignored) {
            }
        }
        Response resp = newFixedLengthResponse(status, MIME_PLAINTEXT, "");
        Object headers = map.get("headers");
        if (headers instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> hm = (Map<String, Object>) headers;
            for (Map.Entry<String, Object> e : hm.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    resp.addHeader(e.getKey(), String.valueOf(e.getValue()));
                }
            }
        }
        Object body = map.get("body");
        if (body != null) {
            resp = newFixedLengthResponse(status, MIME_PLAINTEXT, String.valueOf(body));
            if (headers instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> hm = (Map<String, Object>) headers;
                for (Map.Entry<String, Object> e : hm.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        resp.addHeader(e.getKey(), String.valueOf(e.getValue()));
                    }
                }
            }
        }
        return resp;
    }

    private static String escapeJs(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    static class ProxyResult {
        String body;
        Map<String, String> headers;
        int statusCode;
    }
}
