package com.fongmi.android.tv.api.hk.event.web;

/**
 * 8.83 com.example.hikerview.event.web.BlobDownloadProgressEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class BlobDownloadProgressEvent {

    private boolean finished;
    private String progress;
    private String url;

    public BlobDownloadProgressEvent(String string0, String string1) {
        this.url = string0;
        this.progress = string1;
    }

    public String getProgress() {
        return progress;
    }

    public String getUrl() {
        return url;
    }

    public boolean isFinished() {
        return finished;
    }

    public void setFinished(boolean finished) {
        this.finished = finished;
    }

    public void setProgress(String progress) {
        this.progress = progress;
    }

    public void setUrl(String url) {
        this.url = url;
    }

}