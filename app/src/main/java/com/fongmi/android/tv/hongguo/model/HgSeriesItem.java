package com.fongmi.android.tv.hongguo.model;

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

    HgSeriesItem(String seriesId, String title, String coverUrl, String desc, int episodeCount) {
        this(seriesId, title, coverUrl, desc, episodeCount, false, false, null);
    }

    HgSeriesItem(String seriesId, String title, String coverUrl, String desc, int episodeCount,
                 boolean hasAppointment, boolean appointmentSubscribed, String dueStartText) {
        this.seriesId = seriesId;
        this.title = title;
        this.coverUrl = coverUrl;
        this.desc = desc;
        this.episodeCount = episodeCount;
        this.hasAppointment = hasAppointment;
        this.appointmentSubscribed = appointmentSubscribed;
        this.dueStartText = dueStartText;
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
}
