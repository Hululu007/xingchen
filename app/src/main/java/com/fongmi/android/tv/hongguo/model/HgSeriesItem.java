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
                Collections.emptyList());
    }

    HgSeriesItem(String seriesId, String title, String coverUrl, String desc, int episodeCount,
                 boolean hasAppointment, boolean appointmentSubscribed, String dueStartText,
                 String vid, String badge, String recText, List<String> tags, Boolean vertical,
                 boolean multiSeason, String albumId, int albumSerialCount,
                 List<HgSeriesItem> siblings) {
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
    }

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
}
