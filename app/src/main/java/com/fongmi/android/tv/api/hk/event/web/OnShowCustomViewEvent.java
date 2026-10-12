package com.fongmi.android.tv.api.hk.event.web;

import android.view.View;
import android.webkit.WebChromeClient;

/**
 * 8.83 com.example.hikerview.event.web.OnShowCustomViewEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class OnShowCustomViewEvent {

    private WebChromeClient.CustomViewCallback callback;
    private View view;

    public OnShowCustomViewEvent(View view0, WebChromeClient.CustomViewCallback webchromeclientcustomviewcallback1) {
        this.view = view0;
        this.callback = webchromeclientcustomviewcallback1;
    }

    public WebChromeClient.CustomViewCallback getCallback() {
        return callback;
    }

    public View getView() {
        return view;
    }

    public void setCallback(WebChromeClient.CustomViewCallback callback) {
        this.callback = callback;
    }

    public void setView(View view) {
        this.view = view;
    }

}