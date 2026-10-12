package com.fongmi.android.tv.api.hk.event.web;

/**
 * 8.83 com.example.hikerview.event.web.OnSetAdBlockEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class OnSetAdBlockEvent {

    private String html;
    private String rule;

    public OnSetAdBlockEvent(String string0, String string1) {
        this.html = string0;
        this.rule = string1;
    }

    public String getHtml() {
        return html;
    }

    public String getRule() {
        return rule;
    }

    public void setHtml(String html) {
        this.html = html;
    }

    public void setRule(String rule) {
        this.rule = rule;
    }

}