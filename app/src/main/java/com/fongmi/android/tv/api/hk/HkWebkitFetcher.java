package com.fongmi.android.tv.api.hk;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.fongmi.android.tv.App;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * WebView 后台抓取（8.83 WebkitFetcher/WebkitExecutor 的简化移植）。
 * 8.83 原文：WebkitFetcher.smali（1082 行）+ WebkitExecutor.smali（895 行）+ WebkitRunner.smali（603 行）
 * 8.83 语义：
 *   fetchCodeByWebView(url, options)：WebView 后台加载 url，取 JS 渲染后的页面源码；
 *     options.timeout（1000-30000ms，默认 30000ms）；同步等待 CountDownLatch；返回源码字符串。
 *   executeWebRule(url, rule, options)：WebView 里执行另一条 web 规则；同上超时机制。
 * 星辰实现：在主线程创建 WebView（WebView 必须在主线程），用 CountDownLatch 同步等待。
 */
public class HkWebkitFetcher {

    private static final int DEFAULT_TIMEOUT = 30000;
    private static final int MIN_TIMEOUT = 1000;
    private static final int MAX_TIMEOUT = 30000;

    /** 8.83 fetchCodeByWebView 语义：返回 JS 渲染后的页面源码 */
    public static String fetchCodeByWebView(String url, Map<String, Object> options) {
        int timeout = getTimeout(options);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("");
        Handler mainHandler = new Handler(Looper.getMainLooper());

        mainHandler.post(() -> {
            WebView webView = null;
            try {
                webView = new WebView(App.get());
                webView.getSettings().setJavaScriptEnabled(true);
                webView.getSettings().setDomStorageEnabled(true);
                webView.getSettings().setBlockNetworkImage(true);
                applyUa(webView, options);
                List<?> rules = blockRulesOf(options);
                WebView finalWv = webView;
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        if (rules != null && request != null && request.getUrl() != null
                                && blocked(request.getUrl().toString(), rules)) {
                            return new WebResourceResponse(null, null, null);
                        }
                        return super.shouldInterceptRequest(view, request);
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        // 等 JS 渲染完成，延迟 1 秒后取源码
                        view.postDelayed(() -> {
                            try {
                                view.evaluateJavascript(
                                    "(function(){return document.documentElement.outerHTML;})();",
                                    html -> {
                                        if (html != null) {
                                            // evaluateJavascript 返回 JSON 转义字符串，去引号
                                            if (html.length() >= 2 && html.startsWith("\"") && html.endsWith("\"")) {
                                                try {
                                                    html = new org.json.JSONTokener(html).nextString('"');
                                                } catch (Exception e) {
                                                    html = html.substring(1, html.length() - 1);
                                                }
                                            }
                                            result.set(html);
                                        }
                                        latch.countDown();
                                        try { finalWv.destroy(); } catch (Throwable ignored) {}
                                    });
                            } catch (Throwable t) {
                                latch.countDown();
                                try { finalWv.destroy(); } catch (Throwable ignored) {}
                            }
                        }, 1000);
                    }

                    @Override
                    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                        latch.countDown();
                        try { finalWv.destroy(); } catch (Throwable ignored) {}
                    }
                });
                Map<String, String> hdrs = headersOf(options);
                if (hdrs != null) webView.loadUrl(url, hdrs); else webView.loadUrl(url);
            } catch (Throwable t) {
                latch.countDown();
                if (webView != null) {
                    try { webView.destroy(); } catch (Throwable ignored) {}
                }
            }
        });

        try {
            latch.await(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String html = result.get();
        return html != null ? html : "";
    }

    /** 8.83 executeWebRule 语义：在 WebView 里加载 url 后执行 JS 规则，返回执行结果 */
    public static String executeWebRule(String url, String ruleJs, Map<String, Object> options) {
        int timeout = getTimeout(options);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("");
        Handler mainHandler = new Handler(Looper.getMainLooper());

        mainHandler.post(() -> {
            WebView webView = null;
            try {
                webView = new WebView(App.get());
                webView.getSettings().setJavaScriptEnabled(true);
                webView.getSettings().setDomStorageEnabled(true);
                applyUa(webView, options);
                List<?> rules = blockRulesOf(options);
                WebView finalWv = webView;
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        if (rules != null && request != null && request.getUrl() != null
                                && blocked(request.getUrl().toString(), rules)) {
                            return new WebResourceResponse(null, null, null);
                        }
                        return super.shouldInterceptRequest(view, request);
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        view.postDelayed(() -> {
                            try {
                                view.evaluateJavascript(ruleJs, value -> {
                                    result.set(value != null ? value : "");
                                    latch.countDown();
                                    try { finalWv.destroy(); } catch (Throwable ignored) {}
                                });
                            } catch (Throwable t) {
                                latch.countDown();
                                try { finalWv.destroy(); } catch (Throwable ignored) {}
                            }
                        }, 1000);
                    }

                    @Override
                    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                        latch.countDown();
                        try { finalWv.destroy(); } catch (Throwable ignored) {}
                    }
                });
                Map<String, String> hdrs = headersOf(options);
                if (hdrs != null) webView.loadUrl(url, hdrs); else webView.loadUrl(url);
            } catch (Throwable t) {
                latch.countDown();
                if (webView != null) {
                    try { webView.destroy(); } catch (Throwable ignored) {}
                }
            }
        });

        try {
            latch.await(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String r = result.get();
        return r != null ? r : "";
    }

    /**
     * 8.83 WebkitParser.parse 语义（简化移植）：WebView 后台加载页，在页内轮询求值解析 js
     * （最多约 30 秒，对齐 8.83 的 120 轮×250ms），解析 js 通过 fy_bridge_app.finishParse(url, mode, ticket)
     * 回真实地址。空地址/超时/异常返回 ""（8.83 此时弹「解析失败，链接为空」，这里由调用方留痕）。
     */
    public static String parseWebRule(String loadUrl, String innerJs, Map<String, Object> options) {
        if (loadUrl == null || loadUrl.isEmpty() || innerJs == null || innerJs.isEmpty()) return "";
        int timeout = getTimeout(options);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("");
        String ticket = java.util.UUID.randomUUID().toString();
        // 8.83 IWebViewParser.generateExtraJS 原样移植：eval 解析 js，非空即回调，最多 120 轮
        String pollJs = "function __wrq(){try{return eval('" + escapeJsString(innerJs) + "');}catch(e){return null;}}\n"
                + "var __wrc=0;\nfunction __wrcheck(){if(__wrc>120){fy_bridge_app.finishParse('','webkit','" + ticket + "');return;}\n"
                + "__wrc++;setTimeout(function(){var u=__wrq();if(u!=null&&u!==''){fy_bridge_app.finishParse(u,'webkit','" + ticket + "');}else{__wrcheck();}},250);}\n__wrcheck();";
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            WebView webView = null;
            try {
                webView = new WebView(App.get());
                webView.getSettings().setJavaScriptEnabled(true);
                webView.getSettings().setDomStorageEnabled(true);
                webView.getSettings().setBlockNetworkImage(true);
                applyUa(webView, options);
                List<?> rules = blockRulesOf(options);
                WebView finalWv = webView;
                webView.addJavascriptInterface(new WebRuleBridge(ticket, result, latch), "fy_bridge_app");
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        if (rules != null && request != null && request.getUrl() != null
                                && blocked(request.getUrl().toString(), rules)) {
                            return new WebResourceResponse(null, null, null);
                        }
                        return super.shouldInterceptRequest(view, request);
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        try {
                            view.evaluateJavascript(pollJs, null);
                        } catch (Throwable t) {
                            latch.countDown();
                            try { finalWv.destroy(); } catch (Throwable ignored) {}
                        }
                    }

                    @Override
                    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                        latch.countDown();
                        try { finalWv.destroy(); } catch (Throwable ignored) {}
                    }
                });
                Map<String, String> hdrs = headersOf(options);
                if (hdrs != null) webView.loadUrl(loadUrl, hdrs); else webView.loadUrl(loadUrl);
            } catch (Throwable t) {
                latch.countDown();
                if (webView != null) {
                    try { webView.destroy(); } catch (Throwable ignored) {}
                }
            }
        });

        try {
            latch.await(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String r = result.get();
        return r != null ? r : "";
    }

    /** 8.83 finishParse(url, mode, ticket) 的桥：只认自己的 ticket，首个有效回调即定稿。 */
    public static class WebRuleBridge {
        private final String ticket;
        private final AtomicReference<String> result;
        private final CountDownLatch latch;

        WebRuleBridge(String ticket, AtomicReference<String> result, CountDownLatch latch) {
            this.ticket = ticket;
            this.result = result;
            this.latch = latch;
        }

        @android.webkit.JavascriptInterface
        public void finishParse(String url, String mode, String ticket) {
            if (ticket != null && ticket.equals(this.ticket)) {
                if (result.compareAndSet("", url == null ? "" : url)) latch.countDown();
            }
        }
    }

    /** 嵌入 eval('...') 单引号串的转义（对齐 8.83 Utils.escapeJavaScriptString 的常用分支）。 */
    private static String escapeJsString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int getTimeout(Map<String, Object> options) {
        if (options != null) {
            try {
                Object t = options.get("timeout");
                if (t != null) {
                    // JS 数字到 Java 是 Double（"5000.0"），Integer.parseInt 会失败掉回默认 30 秒
                    int timeout = (int) Double.parseDouble(String.valueOf(t));
                    if (timeout < MIN_TIMEOUT) return MIN_TIMEOUT;
                    if (timeout > MAX_TIMEOUT) return MAX_TIMEOUT;
                    return timeout;
                }
            } catch (Throwable ignored) {
            }
        }
        return DEFAULT_TIMEOUT;
    }

    /** 8.83 X5Extra.blockRules：命中任一条（正则 find，编译失败退化为包含匹配）即拦截、回空响应。 */
    private static boolean blocked(String url, List<?> rules) {
        if (url == null || rules == null) return false;
        for (Object r : rules) {
            if (r == null) continue;
            String rule = String.valueOf(r);
            if (rule.isEmpty()) continue;
            try {
                if (Pattern.compile(rule).matcher(url).find()) return true;
            } catch (Throwable e) {
                if (url.contains(rule)) return true;
            }
        }
        return false;
    }

    private static List<?> blockRulesOf(Map<String, Object> options) {
        if (options == null) return null;
        Object b = options.get("blockRules");
        return b instanceof List ? (List<?>) b : null;
    }

    private static Map<String, String> headersOf(Map<String, Object> options) {
        if (options == null) return null;
        Map<String, String> out = new HashMap<>();
        Object h = options.get("headers");
        if (h instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) h).entrySet()) {
                if (e.getKey() != null && e.getValue() != null) out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        Object ua = options.get("ua");
        if (ua != null && !String.valueOf(ua).isEmpty()) out.put("User-Agent", String.valueOf(ua));
        return out.isEmpty() ? null : out;
    }

    private static void applyUa(WebView webView, Map<String, Object> options) {
        if (options == null) return;
        Object ua = options.get("ua");
        if (ua != null && !String.valueOf(ua).isEmpty()) {
            webView.getSettings().setUserAgentString(String.valueOf(ua));
        }
    }
}
