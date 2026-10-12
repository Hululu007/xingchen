package com.fongmi.android.tv.api.hk.event;

/**
 * 8.83 com.example.hikerview.event.FindVideoEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 * mediaResult 对应 8.83 DetectedMediaResult（复杂模型，此处用 Object 简化）。
 */
public class FindVideoEvent {

    private Object mediaResult;
    private String title;

    public FindVideoEvent() {
    }

    public FindVideoEvent(String title) {
        this.title = title;
    }

    public FindVideoEvent(String title, Object mediaResult) {
        this.title = title;
        this.mediaResult = mediaResult;
    }

    public Object getMediaResult() {
        return mediaResult;
    }

    public String getTitle() {
        return title;
    }

    public void setMediaResult(Object mediaResult) {
        this.mediaResult = mediaResult;
    }

    public void setTitle(String title) {
        this.title = title;
    }

}
