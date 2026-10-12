package com.fongmi.android.tv.api.hk.event.web;

/**
 * 8.83 com.example.hikerview.event.web.DownloadStartEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 * horizontalWebView 对应 8.83 HorizontalWebView（WebView 容器，此处用 Object 简化）。
 */
public class DownloadStartEvent {

    private String contentDisposition;
    private long contentLength;
    private Object horizontalWebView;
    private String mimetype;
    private String url;
    private String userAgent;

    public DownloadStartEvent(Object horizontalWebView, String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
        this.horizontalWebView = horizontalWebView;
        this.url = url;
        this.userAgent = userAgent;
        this.contentDisposition = contentDisposition;
        this.mimetype = mimetype;
        this.contentLength = contentLength;
    }

    public String getContentDisposition() {
        return contentDisposition;
    }

    public long getContentLength() {
        return contentLength;
    }

    public Object getHorizontalWebView() {
        return horizontalWebView;
    }

    public String getMimetype() {
        return mimetype;
    }

    public String getUrl() {
        return url;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setContentDisposition(String contentDisposition) {
        this.contentDisposition = contentDisposition;
    }

    public void setContentLength(long contentLength) {
        this.contentLength = contentLength;
    }

    public void setHorizontalWebView(Object horizontalWebView) {
        this.horizontalWebView = horizontalWebView;
    }

    public void setMimetype(String mimetype) {
        this.mimetype = mimetype;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

}
