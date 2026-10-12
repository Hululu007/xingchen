package com.fongmi.android.tv.api.hk.event.home;

/**
 * 8.83 com.example.hikerview.event.home.ToastEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class ToastEvent {

    private String msg;

    public ToastEvent(String string0) {
        this.msg = string0;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

}