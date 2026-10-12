package com.fongmi.android.tv.api.hk.event.video;

/**
 * 8.83 com.example.hikerview.event.video.PlayChapterEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class PlayChapterEvent {

    private int position;
    private String title;

    public PlayChapterEvent(int int0, String string1) {
        this.position = int0;
        this.title = string1;
    }

    public int getPosition() {
        return position;
    }

    public String getTitle() {
        return title;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public void setTitle(String title) {
        this.title = title;
    }

}