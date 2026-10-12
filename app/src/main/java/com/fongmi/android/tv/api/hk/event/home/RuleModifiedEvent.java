package com.fongmi.android.tv.api.hk.event.home;

import com.fongmi.android.tv.api.hk.HkRule;

/**
 * 8.83 com.example.hikerview.event.home.RuleModifiedEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 8.83 原文为 Kotlin data class（rule 字段 + component1/copy/equals/hashCode/toString）。
 * rule 对应 8.83 ArticleListRule，星辰用 HkRule 等价替换。
 */
public class RuleModifiedEvent {

    private final HkRule rule;

    public RuleModifiedEvent(HkRule rule) {
        this.rule = rule;
    }

    public HkRule component1() {
        return rule;
    }

    public RuleModifiedEvent copy(HkRule rule) {
        return new RuleModifiedEvent(rule);
    }

    public HkRule getRule() {
        return rule;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RuleModifiedEvent)) return false;
        RuleModifiedEvent that = (RuleModifiedEvent) o;
        return rule != null ? rule.equals(that.rule) : that.rule == null;
    }

    @Override
    public int hashCode() {
        return rule != null ? rule.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "RuleModifiedEvent(rule=" + rule + ")";
    }

}
