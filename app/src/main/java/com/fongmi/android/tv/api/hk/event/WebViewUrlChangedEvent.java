package com.fongmi.android.tv.api.hk.event;

/**
 * 8.83 com.example.hikerview.event.WebViewUrlChangedEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class WebViewUrlChangedEvent {

    private String url;

    public WebViewUrlChangedEvent() {
    }

    public WebViewUrlChangedEvent(String string0) {
        this.url = string0;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

}