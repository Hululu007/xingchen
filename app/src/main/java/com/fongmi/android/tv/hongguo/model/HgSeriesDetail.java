package com.fongmi.android.tv.hongguo.model;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;

/** 一部剧的详情（不可变）。集目表是播放页选集与预取的输入。 */
public final class HgSeriesDetail {

    /** 一位演员（详情回包 celebrities[]，字段口径照真回包 resp-002/003 逐字）。 */
    public static final class Celebrity {
        private final String nickname;
        private final String roleName;
        private final String avatar;
        private final String subTitle;

        Celebrity(String nickname, String roleName, String avatar, String subTitle) {
            this.nickname = nickname;
            this.roleName = roleName;
            this.avatar = avatar;
            this.subTitle = subTitle;
        }

        public String getNickname() { return nickname; }
        /** 饰演角色名（role_name）；没带为 null。 */
        public String getRoleName() { return roleName; }
        public String getAvatar() { return avatar; }
        /** 饰演副行（sub_title，如「饰 王妈」）；没带为 null。 */
        public String getSubTitle() { return subTitle; }
    }

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
    /** 演员表（回包 celebrities[]）；没带为空表。 */
    private final List<Celebrity> celebrities;
    /**
     * 完结态（回包 series_status：0 更新中、1 已完结、3 今日更新、4 停更）；
     * 回包没带为 null。
     */
    private final Integer seriesStatus;
    /** 作者声明文案（ai_usage_type 映射）；没带为 null。 */
    private final String statement;
    /** 是否多季系列（is_multi_season）。 */
    private final boolean multiSeason;
    /** 所属专辑 id（related_album_id，系列单取数钥匙）；没带为 null。 */
    private final String relatedAlbumId;

    HgSeriesDetail(String seriesId, String title, String coverUrl, String desc,
                   String authorName, int episodeCount, List<Episode> episodes, JsonObject raw) {
        this(seriesId, title, coverUrl, desc, authorName, episodeCount, episodes, raw,
                false, false, null, Collections.emptyList(), null, null, false, null);
    }

    HgSeriesDetail(String seriesId, String title, String coverUrl, String desc,
                   String authorName, int episodeCount, List<Episode> episodes, JsonObject raw,
                   boolean hasAppointment, boolean appointmentSubscribed, String dueStartText) {
        this(seriesId, title, coverUrl, desc, authorName, episodeCount, episodes, raw,
                hasAppointment, appointmentSubscribed, dueStartText,
                Collections.emptyList(), null, null, false, null);
    }

    HgSeriesDetail(String seriesId, String title, String coverUrl, String desc,
                   String authorName, int episodeCount, List<Episode> episodes, JsonObject raw,
                   boolean hasAppointment, boolean appointmentSubscribed, String dueStartText,
                   List<Celebrity> celebrities, Integer seriesStatus, String statement,
                   boolean multiSeason, String relatedAlbumId) {
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
        this.celebrities = Collections.unmodifiableList(celebrities);
        this.seriesStatus = seriesStatus;
        this.statement = statement;
        this.multiSeason = multiSeason;
        this.relatedAlbumId = relatedAlbumId;
    }

    public String getStatement() { return statement; }
    public boolean isMultiSeason() { return multiSeason; }
    public String getRelatedAlbumId() { return relatedAlbumId; }

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
    public List<Celebrity> getCelebrities() { return celebrities; }
    /** 完结态原值（series_status）；回包没带为 null。 */
    public Integer getSeriesStatus() { return seriesStatus; }

    /**
     * 是否已完结（官方口径）：series_status＝1 为完结；字段没下发时官方
     * 消费点（catalog/u2）对 episodesStatus 为 null 默认按 SeriesEnd 处理，
     * 星辰同口径——没带也判完结。
     */
    public boolean isFinished() {
        return seriesStatus == null || seriesStatus == 1;
    }
    /** 原始回包（详情字段口径未完全定版，上层需要冷门字段时从这里取，不丢信息）。 */
    public JsonObject getRaw() { return raw; }
}
