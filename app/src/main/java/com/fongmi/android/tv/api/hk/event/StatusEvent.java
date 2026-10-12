package com.fongmi.android.tv.api.hk.event;

/**
 * 8.83 com.example.hikerview.event.StatusEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 * engine 对应 8.83 SearchEngine（复杂模型，此处用 Object 简化）。
 */
public class StatusEvent {

    private Object engine;
    private String group;

    public StatusEvent() {
    }

    public StatusEvent(Object engine) {
        this.engine = engine;
    }

    public StatusEvent(Object engine, String group) {
        this.engine = engine;
        this.group = group;
    }

    public Object getEngine() {
        return engine;
    }

    public String getGroup() {
        return group;
    }

    public void setEngine(Object engine) {
        this.engine = engine;
    }

    public void setGroup(String group) {
        this.group = group;
    }

}
