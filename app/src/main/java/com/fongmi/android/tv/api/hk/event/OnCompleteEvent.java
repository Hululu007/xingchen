package com.fongmi.android.tv.api.hk.event;

/**
 * 8.83 com.example.hikerview.event.OnCompleteEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 * clickListener 对应 8.83 CompleteToolBar$OnNavClickListener（UI 回调接口，此处用 Object 简化）。
 */
public class OnCompleteEvent {

    private Object clickListener;
    private String eventMsg;
    private String eventTag;

    public OnCompleteEvent(String eventTag) {
        this(eventTag, "");
    }

    public OnCompleteEvent(String eventTag, String eventMsg) {
        this.eventTag = eventTag;
        this.eventMsg = eventMsg;
    }

    public OnCompleteEvent(String eventTag, String eventMsg, Object clickListener) {
        this.eventTag = eventTag;
        this.eventMsg = eventMsg;
        this.clickListener = clickListener;
    }

    public Object getClickListener() {
        return clickListener;
    }

    public String getEventMsg() {
        return eventMsg;
    }

    public String getEventTag() {
        return eventTag;
    }

    public void setClickListener(Object clickListener) {
        this.clickListener = clickListener;
    }

    public void setEventMsg(String eventMsg) {
        this.eventMsg = eventMsg;
    }

    public void setEventTag(String eventTag) {
        this.eventTag = eventTag;
    }

}
