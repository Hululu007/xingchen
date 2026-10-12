package com.fongmi.android.tv.api.hk.event.video;

/**
 * 8.83 com.example.hikerview.event.video.PlayDataUpdateEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class PlayDataUpdateEvent {

    private boolean restartPosition;
    private String url;

    public PlayDataUpdateEvent(String string0, boolean boolean1) {
        this.url = string0;
        this.restartPosition = boolean1;
    }

    public String getUrl() {
        return url;
    }

    public boolean isRestartPosition() {
        return restartPosition;
    }

    public void setRestartPosition(boolean restartPosition) {
        this.restartPosition = restartPosition;
    }

    public void setUrl(String url) {
        this.url = url;
    }

}