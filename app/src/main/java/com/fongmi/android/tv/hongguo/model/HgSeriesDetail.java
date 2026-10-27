package com.fongmi.android.tv.hongguo.model;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;

/** 一部剧的详情（不可变）。集目表是播放页选集与预取的输入。 */
public final class HgSeriesDetail {

    /** 一集的引用（不可变）。 */
    public static final class Episode {
        private final String videoId;
        private final int index;
        private final String title;

        Episode(String videoId, int index, String title) {
            this.videoId = videoId;
            this.index = index;
            this.title = title;
        }

        public String getVideoId() { return videoId; }
        /** 集序号（从回包顺序或字段取，未知为 -1）。 */
        public int getIndex() { return index; }
        public String getTitle() { return title; }
    }

    private final String seriesId;
    private final String title;
    private final String coverUrl;
    private final String desc;
    private final String authorName;
    private final int episodeCount;
    private final List<Episode> episodes;
    private final JsonObject raw;

    HgSeriesDetail(String seriesId, String title, String coverUrl, String desc,
                   String authorName, int episodeCount, List<Episode> episodes, JsonObject raw) {
        this.seriesId = seriesId;
        this.title = title;
        this.coverUrl = coverUrl;
        this.desc = desc;
        this.authorName = authorName;
        this.episodeCount = episodeCount;
        this.episodes = Collections.unmodifiableList(episodes);
        this.raw = raw;
    }

    public String getSeriesId() { return seriesId; }
    public String getTitle() { return title; }
    public String getCoverUrl() { return coverUrl; }
    public String getDesc() { return desc; }
    public String getAuthorName() { return authorName; }
    public int getEpisodeCount() { return episodeCount; }
    public List<Episode> getEpisodes() { return episodes; }
    /** 原始回包（详情字段口径未完全定版，上层需要冷门字段时从这里取，不丢信息）。 */
    public JsonObject getRaw() { return raw; }
}
