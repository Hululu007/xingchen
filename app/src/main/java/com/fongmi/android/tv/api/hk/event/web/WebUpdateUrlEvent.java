package com.fongmi.android.tv.api.hk.event.web;

/**
 * 8.83 com.example.hikerview.event.web.WebUpdateUrlEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class WebUpdateUrlEvent {

    private boolean newWindow;
    private String url;
    private boolean useNow = true;

    public WebUpdateUrlEvent() {
    }

    public WebUpdateUrlEvent(String string0) {
        this.url = string0;
    }

    public WebUpdateUrlEvent(String string0, boolean boolean1) {
        this.url = string0;
        this.newWindow = boolean1;
    }

    public WebUpdateUrlEvent(String string0, boolean boolean1, boolean boolean2) {
        this.url = string0;
        this.newWindow = boolean1;
        this.useNow = boolean2;
    }

    public String getUrl() {
        return url;
    }

    public boolean isNewWindow() {
        return newWindow;
    }

    public boolean isUseNow() {
        return useNow;
    }

    public void setNewWindow(boolean newWindow) {
        this.newWindow = newWindow;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setUseNow(boolean useNow) {
        this.useNow = useNow;
    }

}