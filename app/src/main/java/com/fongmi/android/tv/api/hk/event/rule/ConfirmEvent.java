package com.fongmi.android.tv.api.hk.event.rule;

/**
 * 8.83 com.example.hikerview.event.rule.ConfirmEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class ConfirmEvent {

    private String cancel;
    private String confirm;
    private String content;
    private String title;

    public ConfirmEvent() {
    }

    public String getCancel() {
        return cancel;
    }

    public String getConfirm() {
        return confirm;
    }

    public String getContent() {
        return content;
    }

    public String getTitle() {
        return title;
    }

    public void setCancel(String cancel) {
        this.cancel = cancel;
    }

    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setTitle(String title) {
        this.title = title;
    }

}