package com.fongmi.android.tv.api.hk.event.home;

/**
 * 8.83 com.example.hikerview.event.home.LoadingEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class LoadingEvent {

    private boolean show;
    private String text;

    public LoadingEvent(String string0, boolean boolean1) {
        this.text = string0;
        this.show = boolean1;
    }

    public String getText() {
        return text;
    }

    public boolean isShow() {
        return show;
    }

    public void setShow(boolean show) {
        this.show = show;
    }

    public void setText(String text) {
        this.text = text;
    }

}