package com.fongmi.android.tv.api.hk.event;

/**
 * 8.83 com.example.hikerview.event.ActivityFinishEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class ActivityFinishEvent {

    private String clazz;

    public ActivityFinishEvent(String string0) {
        this.clazz = string0;
    }

    public String getClazz() {
        return clazz;
    }

    public void setClazz(String clazz) {
        this.clazz = clazz;
    }

}