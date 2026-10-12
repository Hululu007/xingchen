package com.fongmi.android.tv.api.hk.event.web;

import android.webkit.WebView;

/**
 * 8.83 com.example.hikerview.event.web.OnLongClickEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class OnLongClickEvent {

    private WebView.HitTestResult result;

    public OnLongClickEvent(WebView.HitTestResult webviewhittestresult0) {
        this.result = webviewhittestresult0;
    }

    public WebView.HitTestResult getResult() {
        return result;
    }

    public void setResult(WebView.HitTestResult result) {
        this.result = result;
    }

}