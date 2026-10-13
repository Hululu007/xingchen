package com.fongmi.android.tv.api.hk;

import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.fongmi.android.tv.App;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
                WebView finalWv = webView;
                webView.setWebViewClient(new WebViewClient() {
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
                webView.loadUrl(url);
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
                WebView finalWv = webView;
                webView.setWebViewClient(new WebViewClient() {
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
                webView.loadUrl(url);
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

    private static int getTimeout(Map<String, Object> options) {
        if (options != null) {
            try {
                Object t = options.get("timeout");
                if (t != null) {
                    int timeout = Integer.parseInt(String.valueOf(t));
                    if (timeout < MIN_TIMEOUT) return MIN_TIMEOUT;
                    if (timeout > MAX_TIMEOUT) return MAX_TIMEOUT;
                    return timeout;
                }
            } catch (Throwable ignored) {
            }
        }
        return DEFAULT_TIMEOUT;
    }
}
