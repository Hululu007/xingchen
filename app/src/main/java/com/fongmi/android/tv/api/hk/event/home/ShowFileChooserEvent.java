package com.fongmi.android.tv.api.hk.event.home;

import android.net.Uri;
import android.webkit.ValueCallback;

/**
 * 8.83 com.example.hikerview.event.home.ShowFileChooserEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 * webViewHolder 对应 8.83 ArticleWebViewHolder（WebView 容器，此处用 Object 简化）。
 */
public class ShowFileChooserEvent {

    private ValueCallback<Uri[]> filePathCallback;
    private Object webViewHolder;

    public ShowFileChooserEvent(Object webViewHolder, ValueCallback<Uri[]> filePathCallback) {
        this.webViewHolder = webViewHolder;
        this.filePathCallback = filePathCallback;
    }

    public ValueCallback<Uri[]> getFilePathCallback() {
        return filePathCallback;
    }

    public Object getWebViewHolder() {
        return webViewHolder;
    }

    public void setFilePathCallback(ValueCallback<Uri[]> filePathCallback) {
        this.filePathCallback = filePathCallback;
    }

    public void setWebViewHolder(Object webViewHolder) {
        this.webViewHolder = webViewHolder;
    }

}
