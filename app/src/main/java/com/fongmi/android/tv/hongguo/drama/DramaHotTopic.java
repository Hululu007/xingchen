package com.fongmi.android.tv.hongguo.drama;

/**
 * 热点话题榜一行（plan/v1 回包的 video_topic 一行，账
 * hongguo-hotsearch-dig-20261030 §2.4）：话题行不带剧 id，它的落点
 * 就是「以 query 发起一次搜索」——query 由解析从 url 里解
 * directQueryWord（url 形 dragon8662://search?…&directQueryWord=…），
 * heat 取 secondary_info_list[0].content（如「4.5万热度」）。
 */
public final class DramaHotTopic {

    private final String id;
    private final String title;
    private final String query;
    private final String heat;

    public DramaHotTopic(String id, String title, String query, String heat) {
        this.id = id;
        this.title = title;
        this.query = query;
        this.heat = heat;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    /** 点击要发起的搜索词（url 的 directQueryWord 解码值）；解不出为 null。 */
    public String getQuery() { return query; }
    public String getHeat() { return heat; }
}
