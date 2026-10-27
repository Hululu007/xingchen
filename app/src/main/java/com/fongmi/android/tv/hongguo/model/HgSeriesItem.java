package com.fongmi.android.tv.hongguo.model;

/** cell 驱动页面（剧场/搜索/追剧架）里一部剧的最小条目（不可变）。 */
public final class HgSeriesItem {

    private final String seriesId;
    private final String title;
    private final String coverUrl;
    private final String desc;
    private final int episodeCount;

    HgSeriesItem(String seriesId, String title, String coverUrl, String desc, int episodeCount) {
        this.seriesId = seriesId;
        this.title = title;
        this.coverUrl = coverUrl;
        this.desc = desc;
        this.episodeCount = episodeCount;
    }

    public String getSeriesId() { return seriesId; }
    public String getTitle() { return title; }
    public String getCoverUrl() { return coverUrl; }
    public String getDesc() { return desc; }
    /** 总集数；回包未给为 -1。 */
    public int getEpisodeCount() { return episodeCount; }
}
