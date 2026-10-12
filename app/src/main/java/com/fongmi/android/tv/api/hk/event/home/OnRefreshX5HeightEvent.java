package com.fongmi.android.tv.api.hk.event.home;

/**
 * 8.83 com.example.hikerview.event.home.OnRefreshX5HeightEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class OnRefreshX5HeightEvent {

    private String desc;

    public OnRefreshX5HeightEvent(String string0) {
        this.desc = string0;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

}