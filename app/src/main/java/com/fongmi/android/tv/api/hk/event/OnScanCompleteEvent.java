package com.fongmi.android.tv.api.hk.event;

/**
 * 8.83 com.example.hikerview.event.OnScanCompleteEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class OnScanCompleteEvent {

    private String process;

    public OnScanCompleteEvent(String string0) {
        this.process = string0;
    }

    public String getProcess() {
        return process;
    }

    public void setProcess(String process) {
        this.process = process;
    }

}