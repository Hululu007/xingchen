package com.fongmi.android.tv.api.hk.event.rule;

import java.util.concurrent.CountDownLatch;

/**
 * 8.83 com.example.hikerview.event.rule.ItemFindEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 * articleList 对应 8.83 ArticleList（复杂模型，此处用 Object 简化）。
 */
public class ItemFindEvent {

    private Object articleList;
    private CountDownLatch countDownLatch;
    private String id;

    public ItemFindEvent(String id) {
        this.id = id;
    }

    public ItemFindEvent(String id, CountDownLatch countDownLatch) {
        this.id = id;
        this.countDownLatch = countDownLatch;
    }

    public Object getArticleList() {
        return articleList;
    }

    public CountDownLatch getCountDownLatch() {
        return countDownLatch;
    }

    public String getId() {
        return id;
    }

    public void setArticleList(Object articleList) {
        this.articleList = articleList;
    }

    public void setCountDownLatch(CountDownLatch countDownLatch) {
        this.countDownLatch = countDownLatch;
    }

    public void setId(String id) {
        this.id = id;
    }

}
