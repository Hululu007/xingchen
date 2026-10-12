package com.fongmi.android.tv.api.hk.event.web;

/**
 * 8.83 com.example.hikerview.event.web.OnRemoveWindowEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 * webView 对应 8.83 HorizontalWebView（WebView 容器，此处用 Object 简化）。
 */
public class OnRemoveWindowEvent {

    private Object webView;

    public OnRemoveWindowEvent(Object webView) {
        this.webView = webView;
    }

    public Object getWebView() {
        return webView;
    }

    public void setWebView(Object webView) {
        this.webView = webView;
    }

}
