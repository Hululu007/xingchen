package com.fongmi.android.tv.hongguo.drama;

/**
 * 热搜面一行作品（plan/v1 search_input_page 回包的 video_data 一行，
 * 字段照 openhongguo hotRow／sdk HotItem 逐字，账
 * hongguo-hotsearch-dig-20261030 §2.3）：series_id／title／cover／
 * episode_cnt／content_type／card_tips／rec_text／tag_info.text 逐字段收，
 * card_tips 是榜单自身数值（searchScore，如「389万热搜值」）、rec_text
 * 是作品总热度（heat，如「4011万热度」），两件不同物、不许混。
 * 名次无服务端字段：position 由解析按板内收录序自算（index+1）。
 * card_tips 在 DramaItem 上没有对应位（rankText 是 rec_tags 派生、
 * 不同物），热搜面用本类独立承接、不塞 DramaItem（账 §4.3）。
 */
public final class DramaHotItem {

    private final int position;
    private final String seriesId;
    private final String title;
    private final String coverUrl;
    private final int episodeCount;
    /** content_type 原值（VideoContentType 枚举，账 §2.5）；点击分发在消费侧按它过 kindOf 同判。 */
    private final int contentType;
    /** card_tips：榜单自身数值（如「530万热搜值」）；猜行/无榜单数值行为 null。 */
    private final String searchScore;
    /** rec_text：作品总热度（如「4762万热度」）；没带为 null。 */
    private final String heat;
    /** tag_info.text 角标（如「新剧」）；没带为 null。 */
    private final String tag;
    /** tag_info.bg_color[0] 角标底色原文（如「#00AE83」）；没带为 null。 */
    private final String tagBg;
    /** vid（行上实字段，热搜面渲染不用、照账收下）；没带为 null。 */
    private final String vid;

    public DramaHotItem(int position, String seriesId, String title, String coverUrl,
                     int episodeCount, int contentType, String searchScore, String heat,
                     String tag, String tagBg, String vid) {
        this.position = position;
        this.seriesId = seriesId;
        this.title = title;
        this.coverUrl = coverUrl;
        this.episodeCount = episodeCount;
        this.contentType = contentType;
        this.searchScore = searchScore;
        this.heat = heat;
        this.tag = tag;
        this.tagBg = tagBg;
        this.vid = vid;
    }

    public int getPosition() { return position; }
    public String getSeriesId() { return seriesId; }
    public String getTitle() { return title; }
    public String getCoverUrl() { return coverUrl; }
    /** 总集数；回包未给为 -1（预约榜行即此形）。 */
    public int getEpisodeCount() { return episodeCount; }
    public int getContentType() { return contentType; }
    public String getSearchScore() { return searchScore; }
    public String getHeat() { return heat; }
    public String getTag() { return tag; }
    public String getTagBg() { return tagBg; }
    public String getVid() { return vid; }
}
