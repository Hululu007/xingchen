package com.fongmi.android.tv.api.hk.event.web;

/**
 * 8.83 com.example.hikerview.event.web.BlobDownloadEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class BlobDownloadEvent {

    private String fileName;
    private boolean finished;
    private String headerMap;
    private String result;
    private String url;

    public BlobDownloadEvent(String string0, String string1) {
        this.url = string0;
        this.result = string1;
    }

    public BlobDownloadEvent(String string0, String string1, String string2) {
        this.url = string0;
        this.headerMap = string1;
        this.result = string2;
    }

    public BlobDownloadEvent(String string0, String string1, String string2, String string3) {
        this.url = string0;
        this.fileName = string1;
        this.headerMap = string2;
        this.result = string3;
    }

    public String getFileName() {
        return fileName;
    }

    public String getHeaderMap() {
        return headerMap;
    }

    public String getResult() {
        return result;
    }

    public String getUrl() {
        return url;
    }

    public boolean isFinished() {
        return finished;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public void setFinished(boolean finished) {
        this.finished = finished;
    }

    public void setHeaderMap(String headerMap) {
        this.headerMap = headerMap;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public void setUrl(String url) {
        this.url = url;
    }

}