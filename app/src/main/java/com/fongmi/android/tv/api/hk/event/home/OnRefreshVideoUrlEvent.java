package com.fongmi.android.tv.api.hk.event.home;

import java.util.Map;

/**
 * 8.83 com.example.hikerview.event.home.OnRefreshVideoUrlEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class OnRefreshVideoUrlEvent {

    private Map headers;
    private String url;

    public OnRefreshVideoUrlEvent(String string0, Map map1) {
        this.url = string0;
        this.headers = map1;
    }

    public Map getHeaders() {
        return headers;
    }

    public String getUrl() {
        return url;
    }

    public void setHeaders(Map headers) {
        this.headers = headers;
    }

    public void setUrl(String url) {
        this.url = url;
    }

}