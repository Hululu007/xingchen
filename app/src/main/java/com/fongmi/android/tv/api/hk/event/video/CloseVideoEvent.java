package com.fongmi.android.tv.api.hk.event.video;

/**
 * 8.83 com.example.hikerview.event.video.CloseVideoEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class CloseVideoEvent {

    private boolean checkPip;

    public CloseVideoEvent() {
    }

    public CloseVideoEvent(boolean boolean0) {
        this.checkPip = boolean0;
    }

    public boolean isCheckPip() {
        return checkPip;
    }

    public void setCheckPip(boolean checkPip) {
        this.checkPip = checkPip;
    }

}