package com.fongmi.android.tv.ui.hk;

import android.app.Activity;
import android.content.Context;
import android.webkit.WebView;

/**
 * 对应 8.83 ArticleWebViewHolder（ui/home/webview）：
 * 模式枚举与高度解析（getHeightByExtra）照原版转写；extra/x5Extra/videoUrl/videoHeaders/lazyRuleConsumer
 * 为原版同名数据面；WebView 本体由 {@link HkWebkitHolder}（ArticleWebkitHolder 转写）承载
 * （星辰用系统 WebView，原版为腾讯 X5），{@link #ensureWebView(Activity)} 时惰性创建。
 */
public class HkWebViewHolder {

    public enum Mode {
        FLOAT, LIST
    }

    private Mode mode = Mode.LIST;
    private String url;
    private int height;
    private WebView webView;
    private String extra;
    private HkX5Extra x5Extra;
    private String videoUrl;
    private java.util.Map<String, String> videoHeaders;
    private java.util.function.Consumer<String> lazyRuleConsumer;
    private HkWebkitHolder webkitHolder;
    private HkWebkitHolder.UrlInterceptorEvaluator urlInterceptorEvaluator;
    private HkWebkitHolder.BridgeOpenHandler bridgeOpenHandler;

    public void setUrlInterceptorEvaluator(HkWebkitHolder.UrlInterceptorEvaluator e) {
        this.urlInterceptorEvaluator = e;
        if (webkitHolder != null) webkitHolder.setUrlInterceptorEvaluator(e);
    }

    public void setBridgeOpenHandler(HkWebkitHolder.BridgeOpenHandler h) {
        this.bridgeOpenHandler = h;
        if (webkitHolder != null) webkitHolder.setBridgeOpenHandler(h);
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public WebView getWebView() {
        return webkitHolder != null ? webkitHolder.getWebView() : webView;
    }

    /** 照抄原版 ArticleWebViewHolder.isVideoItem：当前 url 是视频占位页（empty.html）时为 true（命名与直觉相反，照原版）。 */
    public boolean isVideoItem() {
        return url != null && url.startsWith("file:///android_asset/empty.html");
    }

    public void setWebView(WebView webView) {
        this.webView = webView;
    }

    public String getExtra() {
        return extra;
    }

    public void setExtra(String extra) {
        this.extra = extra;
    }

    public HkX5Extra getX5Extra() {
        return x5Extra;
    }

    public void setX5Extra(HkX5Extra x5Extra) {
        this.x5Extra = x5Extra;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public void setVideoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
    }

    public java.util.Map<String, String> getVideoHeaders() {
        return videoHeaders;
    }

    public void setVideoHeaders(java.util.Map<String, String> videoHeaders) {
        this.videoHeaders = videoHeaders;
    }

    public java.util.function.Consumer<String> getLazyRuleConsumer() {
        return lazyRuleConsumer;
    }

    public void setLazyRuleConsumer(java.util.function.Consumer<String> lazyRuleConsumer) {
        this.lazyRuleConsumer = lazyRuleConsumer;
    }

    public HkWebkitHolder getWebkitHolder() {
        return webkitHolder;
    }

    /**
     * 照原版 updateWebView 的创建点语义：新建 WebView → 交 HkWebkitHolder 配置（initWebView）→ 加载。
     * 视频条目（isVideoItem）加载 videoUrl（带 videoHeaders），普通条目加载 url。
     */
    public void ensureWebView(Activity activity) {
        if (webkitHolder != null || activity == null) return;
        WebView wv = new WebView(activity);
        wv.setBackgroundColor(0xFFFFFFFF);
        webkitHolder = new HkWebkitHolder(wv);
        webkitHolder.setX5Extra(x5Extra);
        webkitHolder.setLazyRuleConsumer(lazyRuleConsumer);
        if (urlInterceptorEvaluator != null) webkitHolder.setUrlInterceptorEvaluator(urlInterceptorEvaluator);
        if (bridgeOpenHandler != null) webkitHolder.setBridgeOpenHandler(bridgeOpenHandler);
        webkitHolder.setUrl(url);
        webkitHolder.initWebView(activity);
        if (isVideoItem() && videoUrl != null && !videoUrl.isEmpty()) {
            if (videoHeaders != null && !videoHeaders.isEmpty()) wv.loadUrl(videoUrl, videoHeaders);
            else wv.loadUrl(videoUrl);
        } else if (url != null && !url.isEmpty()) {
            wv.loadUrl(url);
        }
    }

    /** 照抄原版 getHeightByExtra(String url, String extra)：url 为空给 0，否则按 extra 解析。 */
    public static int getHeightByExtra(Context context, String url, String extra) {
        if (url != null && !url.isEmpty()) {
            return getHeightByExtra(context, extra);
        }
        return 0;
    }

    /**
     * 照抄原版 getHeightByExtra(String extra)：缺省 240；extra 按 "&amp;&amp;" 分段逐段处理（后段可覆盖前段）：
     * FLOAT/LIST 段跳过；auto=-2；100%（extra 含 float 时）=-1；top（extra 含 float 时）=-1000；纯数字段=该数值。
     * 8.83 出处：ArticleWebViewHolder.getHeightByExtra（ui/home/webview）。
     */
    public static int getHeightByExtra(Context context, String extra) {
        int height = 240;
        if (extra != null && !extra.isEmpty()) {
            String[] parts = extra.split("&&");
            for (String raw : parts) {
                String seg = raw.trim();
                if (seg.equalsIgnoreCase(Mode.FLOAT.name()) || seg.equalsIgnoreCase(Mode.LIST.name())) {
                    continue;
                }
                if ("auto".equals(seg)) {
                    height = -2;
                } else if ("100%".equals(seg)) {
                    if (extra.contains("float")) height = -1;
                } else if ("top".equals(seg)) {
                    if (extra.contains("float")) height = -1000;
                } else {
                    try {
                        height = Integer.parseInt(seg);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return height;
    }
}
