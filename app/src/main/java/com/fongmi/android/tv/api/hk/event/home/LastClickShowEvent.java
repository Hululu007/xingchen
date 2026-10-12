package com.fongmi.android.tv.api.hk.event.home;

/**
 * 8.83 com.example.hikerview.event.home.LastClickShowEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class LastClickShowEvent {

    private String simpleTitle;
    private String title;

    public LastClickShowEvent(String string0) {
        this.title = string0;
    }

    public LastClickShowEvent(String string0, String string1) {
        this.title = string0;
        this.simpleTitle = string1;
    }

    public String getSimpleTitle() {
        return simpleTitle;
    }

    public String getTitle() {
        return title;
    }

    public void setSimpleTitle(String simpleTitle) {
        this.simpleTitle = simpleTitle;
    }

    public void setTitle(String title) {
        this.title = title;
    }

}