package com.fongmi.android.tv.api.hk.event.video;

/**
 * 8.83 com.example.hikerview.event.video.MusicStatusEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class MusicStatusEvent {

    private boolean playing;

    public MusicStatusEvent() {
    }

    public MusicStatusEvent(boolean boolean0) {
        this.playing = boolean0;
    }

    public boolean isPlaying() {
        return playing;
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
    }

}