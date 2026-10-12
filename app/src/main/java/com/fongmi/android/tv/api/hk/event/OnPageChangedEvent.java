package com.fongmi.android.tv.api.hk.event;

/**
 * 8.83 com.example.hikerview.event.OnPageChangedEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class OnPageChangedEvent {

    private boolean newWindow;
    private String position;

    public OnPageChangedEvent() {
    }

    public OnPageChangedEvent(String string0) {
        this.position = string0;
    }

    public String getPosition() {
        return position;
    }

    public boolean isNewWindow() {
        return newWindow;
    }

    public void setNewWindow(boolean newWindow) {
        this.newWindow = newWindow;
    }

    public void setPosition(String position) {
        this.position = position;
    }

}