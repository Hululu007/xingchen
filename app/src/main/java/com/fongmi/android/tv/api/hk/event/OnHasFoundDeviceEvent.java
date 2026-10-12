package com.fongmi.android.tv.api.hk.event;

/**
 * 8.83 com.example.hikerview.event.OnHasFoundDeviceEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class OnHasFoundDeviceEvent {

    private String name;
    private String url;

    public OnHasFoundDeviceEvent(String string0, String string1) {
        this.url = string0;
        this.name = string1;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setUrl(String url) {
        this.url = url;
    }

}