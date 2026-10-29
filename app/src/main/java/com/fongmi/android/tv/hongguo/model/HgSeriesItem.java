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
    /**
     * 官方底栏条分段原文（回包 video_detail.bottom_bar.text 数组，存档
     * 频道/搜索回包逐字实证：如 ["漫剧","剧名","全63集"]、
     * ["观看完整短剧","全303集"]、["合集","剧名","更新至114集"]）：
     * 服务端逐条下发的成品分段，流页底栏照原序以「·」相接即官方文案；
     * 解析器后置填充（不进构造器，与 infoChips 同惯例），没带为 null。
     */
    public List<String> bottomBarText;
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
    /**
     * 条目类型（普查卡 6/7/8 三型）：只由解析器在解析入口按协议字段
     * 定型后置填充（HgParser.asSeriesItem：content_type＝1003 或身份
     * 只来自 album_info → ALBUM；content_type＝1001 → UGC_CLIP；其余
     * → SERIES），手工构造的条目（专辑名单行/系列单补行）恒为正剧
     * 缺省。消费端只读本字段分发，不许再按 content_type 原值推断。
     * content_type 原值字段（旧 contentType/getContentType/isUgc）已于
     * 批五按全树零真消费裁定删除：定型只在解析入口读 JSON 原值完成，
     * 模型不再携带原值（账 hongguo-batch5-cleanup-20261029）。
     */
    public HgItemKind kind = HgItemKind.SERIES;
    /**
     * 内容类型原值（官方 VideoContentType：1＝正剧短片、1001＝UGC、
     * 1003＝合集、1004＝漫剧 MotionComic、1007＝虚构漫剧；协议键
     * content_type，所在层是剧级对象——频道回包 video_detail 本体、
     * 详情回包 video_data 本体，抠查账 hongguo-manju-follow-dig
     * -20261030 件 1 定版）：解析器后置填充（不进构造器，与 kind
     * 同惯例），回包没带为 -1。与 kind 的分工：kind 是解析入口定完
     * 的三型分发结论、消费端只读它分发；本字段是原值留底，真消费
     * 只有漫剧判别（追更药丸 B 路径）一处等值判，不许拿它在消费端
     * 反推类型（批一分家口径不破）。旧 contentType 字段曾于批五以
     * 「零真消费」删除，本次因药丸 B 路径成为真消费方而按规格复置，
     * 不是把批五的删除翻案（账 hongguo-rework-p1-feedplay-20261030）。
     */
    public int contentType = -1;

    /** 是否漫剧（content_type＝1004，官方 VideoContentType.MotionComic）。 */
    public boolean isMotionComic() {
        return contentType == 1004;
    }
    /**
     * 作者关系态（ugc_user_info.user_relation.relation_type，官方
     * UserRelationType 原值：1=None 未关注、2=Follow 我已关注他、
     * 3=Followed 他关注我、4=MutualFollow 互关）；解析器后置填充
     * （与 followedCnt 同惯例），回包没带为 -1。流页关注徽标的显隐与
     * HgStore 镜像校准靠它（账 hongguo-ugc-follow-dig-20261030 §3/§5.2）。
     */
    public int authorRelationType = -1;
    /** 作者可否关注（user_relation.can_follow）；回包没带为 false。 */
    public boolean authorCanFollow;

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
                Collections.emptyList(), -1, -1, null, null, null, null, null, null, null);
    }

    HgSeriesItem(String seriesId, String title, String coverUrl, String desc, int episodeCount,
                 boolean hasAppointment, boolean appointmentSubscribed, String dueStartText,
                 String vid, String badge, String recText, List<String> tags, Boolean vertical,
                 boolean multiSeason, String albumId, int albumSerialCount,
                 List<HgSeriesItem> siblings, long commentCount, long diggCount,
                 Integer seriesStatus, String statement, String authorName, String authorAvatar,
                 String authorUid, String authorDesc, String publisher) {
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
        this.publisher = publisher;
    }

    /** 补完结态的副本（字段后补场景：如半屏单里当前行由页面条目拼装）。 */
    public HgSeriesItem withSeriesStatus(Integer status) {
        HgSeriesItem copy = new HgSeriesItem(seriesId, title, coverUrl, desc, episodeCount,
                hasAppointment, appointmentSubscribed, dueStartText,
                vid, badge, recText, tags, vertical, multiSeason, albumId,
                albumSerialCount, siblings, commentCount, diggCount, status, statement,
                authorName, authorAvatar, authorUid, authorDesc, publisher);
        copy.kind = kind;
        copy.contentType = contentType;
        copy.authorRelationType = authorRelationType;
        copy.authorCanFollow = authorCanFollow;
        return copy;
    }

    public String getStatement() { return statement; }
    public String getAuthorName() { return authorName; }
    public String getAuthorAvatar() { return authorAvatar; }
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
