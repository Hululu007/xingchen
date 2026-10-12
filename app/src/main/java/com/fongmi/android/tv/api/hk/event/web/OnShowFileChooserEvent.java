package com.fongmi.android.tv.api.hk.event.web;

import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;

/**
 * 8.83 com.example.hikerview.event.web.OnShowFileChooserEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class OnShowFileChooserEvent {

    private WebChromeClient.FileChooserParams fileChooserParams;
    private ValueCallback filePathCallback;

    public OnShowFileChooserEvent(ValueCallback valuecallback0, WebChromeClient.FileChooserParams webchromeclientfilechooserparams1) {
        this.filePathCallback = valuecallback0;
        this.fileChooserParams = webchromeclientfilechooserparams1;
    }

    public WebChromeClient.FileChooserParams getFileChooserParams() {
        return fileChooserParams;
    }

    public ValueCallback getFilePathCallback() {
        return filePathCallback;
    }

    public void setFileChooserParams(WebChromeClient.FileChooserParams fileChooserParams) {
        this.fileChooserParams = fileChooserParams;
    }

    public void setFilePathCallback(ValueCallback filePathCallback) {
        this.filePathCallback = filePathCallback;
    }

}