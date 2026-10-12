package com.fongmi.android.tv.api.hk.event.web;

/**
 * 8.83 com.example.hikerview.event.web.DestroyEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class DestroyEvent {

    private String mode;
    private String ticket;
    private String url;

    public DestroyEvent(String string0, String string1, String string2) {
        this.url = string0;
        this.mode = string1;
        this.ticket = string2;
    }

    public String getMode() {
        return mode;
    }

    public String getTicket() {
        return ticket;
    }

    public String getUrl() {
        return url;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public void setTicket(String ticket) {
        this.ticket = ticket;
    }

    public void setUrl(String url) {
        this.url = url;
    }

}