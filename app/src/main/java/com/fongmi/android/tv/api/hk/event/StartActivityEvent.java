package com.fongmi.android.tv.api.hk.event;

import android.content.Intent;

/**
 * 8.83 com.example.hikerview.event.StartActivityEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class StartActivityEvent {

    private Intent intent;

    public StartActivityEvent() {
    }

    public StartActivityEvent(Intent intent0) {
        this.intent = intent0;
    }

    public Intent getIntent() {
        return intent;
    }

    public void setIntent(Intent intent) {
        this.intent = intent;
    }

}