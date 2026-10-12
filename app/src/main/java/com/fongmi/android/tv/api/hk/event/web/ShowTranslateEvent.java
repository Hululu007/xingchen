package com.fongmi.android.tv.api.hk.event.web;

/**
 * 8.83 com.example.hikerview.event.web.ShowTranslateEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class ShowTranslateEvent {

    private String text;

    public ShowTranslateEvent() {
    }

    public ShowTranslateEvent(String string0) {
        this.text = string0;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

}