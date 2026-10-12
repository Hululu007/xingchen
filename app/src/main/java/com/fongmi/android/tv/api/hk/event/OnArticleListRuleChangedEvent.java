package com.fongmi.android.tv.api.hk.event;

/**
 * 8.83 com.example.hikerview.event.OnArticleListRuleChangedEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class OnArticleListRuleChangedEvent {

    private String fromClazz;
    private String rule;
    private boolean update;
    private int updateFromSub;

    public OnArticleListRuleChangedEvent() {
    }

    public OnArticleListRuleChangedEvent(int int0) {
        this.updateFromSub = int0;
    }

    public OnArticleListRuleChangedEvent(String string0, boolean boolean1) {
        this.rule = string0;
        this.update = boolean1;
    }

    public String getFromClazz() {
        return fromClazz;
    }

    public String getRule() {
        return rule;
    }

    public int getUpdateFromSub() {
        return updateFromSub;
    }

    public boolean isUpdate() {
        return update;
    }

    public void setFromClazz(String fromClazz) {
        this.fromClazz = fromClazz;
    }

    public void setRule(String rule) {
        this.rule = rule;
    }

    public void setUpdate(boolean update) {
        this.update = update;
    }

    public void setUpdateFromSub(int updateFromSub) {
        this.updateFromSub = updateFromSub;
    }

}