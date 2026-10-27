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
    /** 回包带 appointment 对象（未上线剧的预约面）；没带为 false。 */
    private final boolean hasAppointment;
    /** appointment.is_subscribed：服务端给的当前账号预约态。 */
    private final boolean appointmentSubscribed;
    /** appointment.due_start_text：上线文案；没带为 null。 */
    private final String dueStartText;

    HgSeriesDetail(String seriesId, String title, String coverUrl, String desc,
                   String authorName, int episodeCount, List<Episode> episodes, JsonObject raw) {
        this(seriesId, title, coverUrl, desc, authorName, episodeCount, episodes, raw,
                false, false, null);
    }

    HgSeriesDetail(String seriesId, String title, String coverUrl, String desc,
                   String authorName, int episodeCount, List<Episode> episodes, JsonObject raw,
                   boolean hasAppointment, boolean appointmentSubscribed, String dueStartText) {
        this.seriesId = seriesId;
        this.title = title;
        this.coverUrl = coverUrl;
        this.desc = desc;
        this.authorName = authorName;
        this.episodeCount = episodeCount;
        this.episodes = Collections.unmodifiableList(episodes);
        this.raw = raw;
        this.hasAppointment = hasAppointment;
        this.appointmentSubscribed = appointmentSubscribed;
        this.dueStartText = dueStartText;
    }

    public String getSeriesId() { return seriesId; }
    public String getTitle() { return title; }
    public String getCoverUrl() { return coverUrl; }
    public String getDesc() { return desc; }
    public String getAuthorName() { return authorName; }
    public int getEpisodeCount() { return episodeCount; }
    public List<Episode> getEpisodes() { return episodes; }
    public boolean hasAppointment() { return hasAppointment; }
    public boolean isAppointmentSubscribed() { return appointmentSubscribed; }
    public String getDueStartText() { return dueStartText; }
    /** 原始回包（详情字段口径未完全定版，上层需要冷门字段时从这里取，不丢信息）。 */
    public JsonObject getRaw() { return raw; }
}
