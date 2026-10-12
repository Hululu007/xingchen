package com.fongmi.android.tv.api.hk.event;

/**
 * 8.83 com.example.hikerview.event.HomeTabModeEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class HomeTabModeEvent {

    private String tab;

    public HomeTabModeEvent(String string0) {
        this.tab = string0;
    }

    public String getTab() {
        return tab;
    }

    public void setTab(String tab) {
        this.tab = tab;
    }

}