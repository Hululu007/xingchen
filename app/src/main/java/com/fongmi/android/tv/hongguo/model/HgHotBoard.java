package com.fongmi.android.tv.hongguo.model;

import java.util.Collections;
import java.util.List;

/**
 * 热搜面一个榜单板（plan/v1 回包 lynx cell_data 里的一板，账
 * hongguo-hotsearch-dig-20261030 §2.2）：板名取 cell_name 原文
 * （短剧热搜榜／漫剧热搜榜／热点话题榜／预约榜等，集合与顺序服务端
 * 下发、逐次可变，不许写死）。作品板填 items、话题板填 topics，
 * 两面皆空的板解析层已丢弃、不进本模型。
 */
public final class HgHotBoard {

    private final String name;
    private final List<HgHotItem> items;
    private final List<HgHotTopic> topics;

    public HgHotBoard(String name, List<HgHotItem> items, List<HgHotTopic> topics) {
        this.name = name;
        this.items = items == null ? Collections.emptyList() : items;
        this.topics = topics == null ? Collections.emptyList() : topics;
    }

    public String getName() { return name; }
    public List<HgHotItem> getItems() { return items; }
    public List<HgHotTopic> getTopics() { return topics; }
}
