package com.fongmi.android.tv.api.hk.event.web;

/**
 * 8.83 com.example.hikerview.event.web.OnViaInterfaceEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class OnViaInterfaceEvent {

    private String s;

    public OnViaInterfaceEvent(String string0) {
        this.s = string0;
    }

    public String getS() {
        return s;
    }

    public void setS(String s) {
        this.s = s;
    }

}