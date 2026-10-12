package com.fongmi.android.tv.api.hk.event.rule;

import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * 8.83 com.example.hikerview.event.rule.ClsItemsFindEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 */
public class ClsItemsFindEvent {

    private List articleLists;
    private String cls;
    private CountDownLatch countDownLatch;

    public ClsItemsFindEvent(String string0) {
        this.cls = string0;
    }

    public ClsItemsFindEvent(String string0, CountDownLatch countdownlatch1) {
        this.cls = string0;
        this.countDownLatch = countdownlatch1;
    }

    public List getArticleLists() {
        return articleLists;
    }

    public String getCls() {
        return cls;
    }

    public CountDownLatch getCountDownLatch() {
        return countDownLatch;
    }

    public void setArticleLists(List articleLists) {
        this.articleLists = articleLists;
    }

    public void setCls(String cls) {
        this.cls = cls;
    }

    public void setCountDownLatch(CountDownLatch countDownLatch) {
        this.countDownLatch = countDownLatch;
    }

}