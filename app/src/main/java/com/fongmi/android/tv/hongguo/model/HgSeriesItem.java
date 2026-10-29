package com.fongmi.android.tv.hongguo.model;

import java.util.Collections;
import java.util.List;

/** cell 驱动页面（剧场/搜索/追剧架）里一部剧的最小条目（不可变）。 */
public final class HgSeriesItem {

    private final String seriesId;
    private final String title;
    private final String coverUrl;
    private final String desc;
    private final int episodeCount;
    /** 回包带 appointment 对象（未上线剧的预约面）；没带为 false。 */
    private final boolean hasAppointment;
    /** appointment.is_subscribed：服务端给的当前账号预约态。 */
    private final boolean appointmentSubscribed;
    /** appointment.due_start_text：上线文案（如「8月20日上线」）；没带为 null。 */
    private final String dueStartText;
    /** 短片自身 id（cell video_data.vid，研究账 §2.5 真回包实证）；没带为 null。 */
    private final String vid;
    /** 角标文案（tag_info.text，如「爆剧」「新剧」，§2.5）；没带为 null。 */
    private final String badge;
    /** 热度文案（rec_text，如「4805万热度」，§2.5）；没带为 null。 */
    private final String recText;
    /** 分类名（category_schema[].name，§2.5）；没带为空表。 */
    private final List<String> tags;
    /** 是否竖屏短片（cell vertical，§3.8 朝向预判）；回包没带为 null。 */
    private final Boolean vertical;
    /** 是否多季系列（is_multi_season，§5.3 字符串池实证键名）；没带为 false。 */
    private final boolean multiSeason;
    /** 所属专辑 id（album_id_str/album_id，§5.3）；没带为 null。 */
    private final String albumId;
    /** 专辑季/部数（album_serial_count，§5.3）；没带为 -1。 */
    private final int albumSerialCount;
    /** 同专辑的兄弟部（album_data_list 逐条抽，§5.3）；回包没带为空表。 */
    private final List<HgSeriesItem> siblings;
    /** 评论数（cell video_data.comment_count，2026-10-28 频道真回包实证）；没带为 -1。 */
    private final long commentCount;
    /** 点赞数（cell video_data.digged_count，同上实证）；没带为 -1。 */
    private final long diggCount;
    /**
     * 完结态（series_status：1＝已完结；频道回包在 video_detail 或
     * video_detail_list 卡上，2026-10-28 存档逐层实证）；没带为 null。
     */
    private final Integer seriesStatus;
    /** 作者声明文案（ai_usage_type 映射，见 HgParser.statementOf）；没带为 null。 */
    private final String statement;
    /** 作者名/头像（cell 的 ugc_user_info.base_info 抬升；没带为 null）。 */
    private final String authorName;
    private final String authorAvatar;
    /**
     * 作者 uid（cell 的 ugc_user_info.user_id 抬升，base_info.user_id 兜底；
     * 频道存档真回包实证——推荐频道条目带、搜索 cell 不带）。作者主页与
     * 关注作者的 object_id 就靠它；没带为 null，点不开主页、不许拿名字认人。
     */
    private final String authorUid;
    /** 作者简介（ugc base_info.description；存档样本里恒 null，有才有）。 */
    private final String authorDesc;
    /**
     * 内容类型（cell content_type，存档频道回包实证：1001＝UGC 短片、
     * 1004＝正剧短片）；没带为 -1。UGC 的播放模型只随 UGC 详情下发、
     * 单集取模型口对它恒回 101000，起播链靠它分流（定根账
     * feed-item-fetch-fail-20261029）。
     */
    private final int contentType;
    /**
     * 出品方品牌（cell video_list[].copyright 抬升，如「魔方短剧」）；
     * 没带为 null。正剧条目作者身份全链无源（详情口恒不下发 uid），
     * 会话层拿它走搜索「用户」签反查作者 uid 兜底（定根账
     * author-page-why-missing-20261029 追补）。
     */
    private final String publisher;

    /**
     * 信息摞筹行（cell secondary_infos／recommend_reason_list 的
     * secondary_info_list，存档频道回包逐字实证）：已按 KeepPriority
     * 降序排好的展示文案（评分 data_type=4、季 highlight、第N季、题材
     * data_type=3、演员 data_type=33 全在这一列里，官方筹行就是它）；
     * 解析器后置填充（不进构造器，与流页 HgFeedItem 的后置字段同
     * 惯例），没带为 null（适配器退回 category_schema 标签）。
     */
    public List<String> infoChips;
    /** 评分筹在 infoChips 里的下标（data_type=4）；没有为 -1。 */
    public int scoreChipPos = -1;
    /** 榜单徽章条文案（rec_tags 中 rec_type=9 的一条，如「喜剧·真人剧榜 No.4」）；没上榜为 null。 */
    public String rankText;
    /** 榜单条渐变色（rec_reason_style 的背景色组，原样 #AARRGGBB 串）；没带为 null。 */
    public List<String> rankColors;
    /** 榜单条奖杯图标址（rec_icon_url）；没带为 null。 */
    public String rankIconUrl;
    /** 追更/收藏人数原值（cell video_detail.followed_cnt）；没带为 -1。 */
    public long followedCnt = -1;
    /**
     * 被推集集序（video_list 里与本条 vid 对应那条的 vid_index）：
     * 流页简介「第N集｜」前缀的集号来源，解析器后置填充（不进构造器，
     * 与 followedCnt 同惯例）；没带为 -1。
     */
    public int vidIndex = -1;

    HgSeriesItem(String seriesId, String title, String coverUrl, String desc, int episodeCount) {
        this(seriesId, title, coverUrl, desc, episodeCount, false, false, null);
    }

    public static HgSeriesItem of(String seriesId, String title, String coverUrl, String desc,
                                  int episodeCount, boolean hasAppointment,
                                  boolean appointmentSubscribed, String dueStartText) {
        return new HgSeriesItem(seriesId, title, coverUrl, desc, episodeCount,
                hasAppointment, appointmentSubscribed, dueStartText);
    }

    HgSeriesItem(String seriesId, String title, String coverUrl, String desc, int episodeCount,
                 boolean hasAppointment, boolean appointmentSubscribed, String dueStartText) {
        this(seriesId, title, coverUrl, desc, episodeCount, hasAppointment, appointmentSubscribed,
                dueStartText, null, null, null, Collections.emptyList(), null, false, null, -1,
                Collections.emptyList(), -1, -1, null, null, null, null, null, null, -1, null);
    }

    HgSeriesItem(String seriesId, String title, String coverUrl, String desc, int episodeCount,
                 boolean hasAppointment, boolean appointmentSubscribed, String dueStartText,
                 String vid, String badge, String recText, List<String> tags, Boolean vertical,
                 boolean multiSeason, String albumId, int albumSerialCount,
                 List<HgSeriesItem> siblings, long commentCount, long diggCount,
                 Integer seriesStatus, String statement, String authorName, String authorAvatar,
                 String authorUid, String authorDesc, int contentType, String publisher) {
        this.seriesId = seriesId;
        this.title = title;
        this.coverUrl = coverUrl;
        this.desc = desc;
        this.episodeCount = episodeCount;
        this.hasAppointment = hasAppointment;
        this.appointmentSubscribed = appointmentSubscribed;
        this.dueStartText = dueStartText;
        this.vid = vid;
        this.badge = badge;
        this.recText = recText;
        this.tags = tags == null ? Collections.emptyList() : tags;
        this.vertical = vertical;
        this.multiSeason = multiSeason;
        this.albumId = albumId;
        this.albumSerialCount = albumSerialCount;
        this.siblings = siblings == null ? Collections.emptyList() : siblings;
        this.commentCount = commentCount;
        this.diggCount = diggCount;
        this.seriesStatus = seriesStatus;
        this.statement = statement;
        this.authorName = authorName;
        this.authorAvatar = authorAvatar;
        this.authorUid = authorUid;
        this.authorDesc = authorDesc;
        this.contentType = contentType;
        this.publisher = publisher;
    }

    /** 补完结态的副本（字段后补场景：如半屏单里当前行由页面条目拼装）。 */
    public HgSeriesItem withSeriesStatus(Integer status) {
        return new HgSeriesItem(seriesId, title, coverUrl, desc, episodeCount,
                hasAppointment, appointmentSubscribed, dueStartText,
                vid, badge, recText, tags, vertical, multiSeason, albumId,
                albumSerialCount, siblings, commentCount, diggCount, status, statement,
                authorName, authorAvatar, authorUid, authorDesc, contentType, publisher);
    }

    public String getStatement() { return statement; }
    public String getAuthorName() { return authorName; }
    public String getAuthorAvatar() { return authorAvatar; }
    /** 内容类型原值（content_type）；没带为 -1。 */
    public int getContentType() { return contentType; }
    /** 是否 UGC 短片（content_type＝1001，存档频道回包实证）。 */
    public boolean isUgc() { return contentType == 1001; }
    public String getAuthorUid() { return authorUid; }
    public String getAuthorDesc() { return authorDesc; }
    /** 出品方品牌（cell copyright）；没带为 null。 */
    public String getPublisher() { return publisher; }

    public String getSeriesId() { return seriesId; }
    public String getTitle() { return title; }
    public String getCoverUrl() { return coverUrl; }
    public String getDesc() { return desc; }
    /** 总集数；回包未给为 -1。 */
    public int getEpisodeCount() { return episodeCount; }
    public boolean hasAppointment() { return hasAppointment; }
    public boolean isAppointmentSubscribed() { return appointmentSubscribed; }
    public String getDueStartText() { return dueStartText; }
    public String getVid() { return vid; }
    public String getBadge() { return badge; }
    public String getRecText() { return recText; }
    public List<String> getTags() { return tags; }
    public Boolean getVertical() { return vertical; }
    public boolean isMultiSeason() { return multiSeason; }
    public String getAlbumId() { return albumId; }
    public int getAlbumSerialCount() { return albumSerialCount; }
    public List<HgSeriesItem> getSiblings() { return siblings; }
    public long getCommentCount() { return commentCount; }
    public long getDiggCount() { return diggCount; }
    /** 完结态原值（series_status）；没带为 null。 */
    public Integer getSeriesStatus() { return seriesStatus; }
    /** 条目级已完结判定：只认下发值＝1（cell 面不套详情的 null 默认口径）。 */
    public boolean isFinished() {
        return seriesStatus != null && seriesStatus == 1;
    }
}
