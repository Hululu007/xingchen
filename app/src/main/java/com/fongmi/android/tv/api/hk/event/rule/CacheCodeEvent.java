package com.fongmi.android.tv.api.hk.event.rule;

/**
 * 8.83 com.example.hikerview.event.rule.CacheCode 的等价实现（EventBus 事件类，纯数据载体）。
 * 规则 JS 调 cacheCode0(code) 时发布（code 非空才发，原版 JSEngine.cacheCode0，smali 11838）；
 * 列表页订阅后把 code 存进页面的 cacheCode 字段（原版 ArticleListFragment.updateCacheCode），
 * 供自动缓存落盘时随列表数据一起保存——星辰的自动缓存落盘消费面（AutoCacheUtil.saveCache）未移植，
 * 见 HkArticleFragment.onCacheCode 注释与普查表缺口记录。
 */
public class CacheCodeEvent {

    private final String code;

    public CacheCodeEvent(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
