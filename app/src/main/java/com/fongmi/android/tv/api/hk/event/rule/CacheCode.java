package com.fongmi.android.tv.api.hk.event.rule;

/**
 * 8.83 com.example.hikerview.event.rule.CacheCode 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class CacheCode {

    private String code;

    public CacheCode(String string0) {
        this.code = string0;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

}