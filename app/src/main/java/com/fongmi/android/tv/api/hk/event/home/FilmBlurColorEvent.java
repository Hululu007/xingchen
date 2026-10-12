package com.fongmi.android.tv.api.hk.event.home;

/**
 * 8.83 com.example.hikerview.event.home.FilmBlurColorEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class FilmBlurColorEvent {

    private int color;

    public FilmBlurColorEvent(int int0) {
        this.color = int0;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }

}