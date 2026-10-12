package com.fongmi.android.tv.api.hk.event;

/**
 * 8.83 com.example.hikerview.event.FloatVisibleChangeEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class FloatVisibleChangeEvent {

    private boolean visible;

    public FloatVisibleChangeEvent(boolean boolean0) {
        this.visible = boolean0;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

}