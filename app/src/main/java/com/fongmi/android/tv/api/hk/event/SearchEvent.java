package com.fongmi.android.tv.api.hk.event;

import java.util.List;

/**
 * 8.83 com.example.hikerview.event.SearchEvent 的等价实现（EventBus 事件类，纯数据载体）。
 * 直接搬运：字段、构造方法、getter/setter 与 8.83 原文一致。
 * searchEngine 对应 8.83 SearchEngine、group/groups 对应 SearchGroup（复杂模型，此处用 Object 简化）。
 */
public class SearchEvent {

    private Object group;
    private List<Object> groups;
    private boolean newWindow;
    private Object searchEngine;
    private String tag;
    private String text;

    public SearchEvent(String text, Object searchEngine, String tag, List<Object> groups, Object group) {
        this.text = text;
        this.searchEngine = searchEngine;
        this.tag = tag;
        this.groups = groups;
        this.group = group;
    }

    public SearchEvent(String text, Object searchEngine, String tag, List<Object> groups, Object group, boolean newWindow) {
        this.text = text;
        this.searchEngine = searchEngine;
        this.tag = tag;
        this.groups = groups;
        this.group = group;
        this.newWindow = newWindow;
    }

    public Object getGroup() {
        return group;
    }

    public List<Object> getGroups() {
        return groups;
    }

    public Object getSearchEngine() {
        return searchEngine;
    }

    public String getTag() {
        return tag;
    }

    public String getText() {
        return text;
    }

    public boolean isNewWindow() {
        return newWindow;
    }

    public void setGroup(Object group) {
        this.group = group;
    }

    public void setGroups(List<Object> groups) {
        this.groups = groups;
    }

    public void setNewWindow(boolean newWindow) {
        this.newWindow = newWindow;
    }

    public void setSearchEngine(Object searchEngine) {
        this.searchEngine = searchEngine;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public void setText(String text) {
        this.text = text;
    }

}
