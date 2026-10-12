package com.fongmi.android.tv.api.hk.event;

import android.content.Context;
import android.content.Intent;

/**
 * 8.83 com.example.hikerview.event.PlaySourceUpdateEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class PlaySourceUpdateEvent {

    private Context context;
    private Intent intent;

    public PlaySourceUpdateEvent(Context context0, Intent intent1) {
        this.intent = intent1;
        this.context = context0;
    }

    public Context getContext() {
        return context;
    }

    public Intent getIntent() {
        return intent;
    }

    public void setContext(Context context) {
        this.context = context;
    }

    public void setIntent(Intent intent) {
        this.intent = intent;
    }

}