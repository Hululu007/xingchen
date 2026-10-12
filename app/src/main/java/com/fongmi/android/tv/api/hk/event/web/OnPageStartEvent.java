package com.fongmi.android.tv.api.hk.event.web;

/**
 * 8.83 com.example.hikerview.event.web.OnPageStartEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class OnPageStartEvent {

    private String url;

    public OnPageStartEvent(String string0) {
        this.url = string0;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

}