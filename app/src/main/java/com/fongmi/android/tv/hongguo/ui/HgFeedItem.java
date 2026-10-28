package com.fongmi.android.tv.hongguo.ui;

import com.fongmi.android.tv.hongguo.model.HgSeriesItem;

import java.util.Collections;
import java.util.List;

/**
 * 刷剧页表的一页（不可变字段为主，展开/富集状态可变）。
 *
 * <p>两种形态（研究账 §2）：</p>
 * <ul>
 *   <li><b>短片</b>（{@link #clip}＝true）：首页流的页——一条短片属一部剧，
 *       自带 vid 直接播；集表不进流，进剧走 §6 的集流（另一份页表）。</li>
 *   <li><b>集</b>：进剧后集流的页（剧头经会话层展开成逐集页，或详情退化单集）。</li>
 * </ul>
 * <p>「剧头」（{@link #seriesHead}＝true）是未定形态的占位页：流里是还没定下
 * 短片 vid 的条目（落定时由会话层拉详情定成短片或预约面），集流里是待展开
 * 成逐集页的剧。计数（点赞/评论/收藏数）在已实证的回包样本里没有对应字段，
 * 一律为 null＝不显示，不许编数字；热评/作者声明同口径（账 §3.4/§3.5，
 * 字段槽先留、下发面未定 U3/U4，有数据才填）。</p>
 */
public final class HgFeedItem {

    public final String seriesId;
    /** 集/短片 id；剧头未定形态时为 null。 */
    public final String videoId;
    /** 集序号（1 起）；短片未知为 -1。 */
    public final int episodeIndex;
    /** 全剧集数；未知为 -1。 */
    public final int episodeCount;
    public final String title;
    /** 类型标签（如「漫剧」「真人剧」）；来源未给为 null，不显示。 */
    public final String category;
    public final String authorName;
    public final String authorAvatar;
    public final String desc;
    public final String coverUrl;
    /** 视频宽/高（取模型回包的 video_width/video_height）；未知为 0。 */
    public final int videoWidth;
    public final int videoHeight;
    /**
     * 计数（已格式化文案）：评论数/点赞数来自 cell 的 comment_count /
     * digged_count（2026-10-28 频道真回包实证，旧注「样本无此字段」作废）；
     * 收藏数 cell 不下发、恒 null＝不显示，不编。
     */
    public final String likeCount;
    public final String commentCount;
    public final String favCount;
    /** 短片标记（首页流的页）；集流的页为 false。 */
    public final boolean clip;
    /** 角标文案（cell tag_info.text，账 §2.5）；没带为 null。 */
    public final String badge;
    /** 热度文案（cell rec_text，账 §2.5）；没带为 null。 */
    public final String recText;
    /** 分类名（cell category_schema，账 §2.5）；没带为空表。 */
    public final List<String> tags;
    /** 是否多季系列（is_multi_season / album 关系，账 §5.3）；没带为 false。 */
    public final boolean multiSeason;
    /** 所属专辑 id（账 §5.3）；没带为 null。 */
    public final String albumId;
    /** 专辑季/部数（album_serial_count，账 §5.3）；没带为 -1。 */
    public final int albumSerialCount;
    /** 同专辑兄弟部（album_data_list，账 §5.3）；没带为空表。 */
    public final List<HgSeriesItem> siblings;
    /** 完结态（cell series_status，频道存档实证）；没带为 null。 */
    public final Integer seriesStatus;

    /** 剧头（未定形态）标记；定形态后置 false。 */
    public boolean seriesHead;
    /** 已尝试定形态/展开（成败都算），避免反复拉详情。 */
    public boolean expandTried;
    /**
     * 未上线剧的预约面：回包带 appointment 时为 true，页面亮预约片；
     * 预约态本身走 HgStore 镜像（服务端为准），本字段只管「该不该亮」。
     */
    public boolean reservable;
    /** appointment.due_start_text 上线文案（如「8月20日上线」）；没带为 null。 */
    public String dueStartText;
    /** 热评/主演说文案（账 §3.4）：下发面未定（U3），有数据才填、没填不显。 */
    public String hotComment;
    /** 热评种类：0 无、1 热评、2 主演说（HotCommentInfo.playletRoleType 口径待定）。 */
    public int hotKind;
    /** 作者声明文案（账 §3.5）：字段名未实证（U4），有数据才填、没填不显。 */
    public String statement;
    /**
     * 作者 uid（cell ugc_user_info.user_id 抬升，见 HgSeriesItem.authorUid）：
     * 点头像/作者名进作者主页、关注作者都靠它；没带为 null（详情面不下发，
     * 集流的页由会话层从进剧条目继承）。后置字段（与 statement 同惯例，
     * 不进构造器），fromSeries 与各 with* 副本负责携带。
     */
    public String authorUid;
    /** 作者简介（ugc base_info.description；样本恒空，有才带）。 */
    public String authorDesc;
    /** 横屏预判（cell vertical=false，账 §3.8）：取模型宽高到手前先据此给全屏观看钮。 */
    public boolean landscapeHint;

    private HgFeedItem(String seriesId, String videoId, int episodeIndex, int episodeCount,
                       String title, String category, String authorName, String authorAvatar,
                       String desc, String coverUrl, int videoWidth, int videoHeight,
                       boolean seriesHead, boolean clip, String badge, String recText,
                       List<String> tags, boolean multiSeason, String albumId,
                       int albumSerialCount, List<HgSeriesItem> siblings,
                       String likeCount, String commentCount, Integer seriesStatus) {
        this.seriesId = seriesId;
        this.videoId = videoId;
        this.episodeIndex = episodeIndex;
        this.episodeCount = episodeCount;
        this.title = title;
        this.category = category;
        this.authorName = authorName;
        this.authorAvatar = authorAvatar;
        this.desc = desc;
        this.coverUrl = coverUrl;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.likeCount = likeCount;
        this.commentCount = commentCount;
        this.favCount = null;
        this.seriesHead = seriesHead;
        this.clip = clip;
        this.badge = badge;
        this.recText = recText;
        this.tags = tags == null ? Collections.emptyList() : tags;
        this.multiSeason = multiSeason;
        this.albumId = albumId;
        this.albumSerialCount = albumSerialCount;
        this.siblings = siblings == null ? Collections.emptyList() : siblings;
        this.seriesStatus = seriesStatus;
    }

    public static HgFeedItem head(String seriesId, String title, String category,
                                  String desc, String coverUrl, int episodeCount) {
        return new HgFeedItem(seriesId, null, -1, episodeCount, title, category,
                null, null, desc, coverUrl, 0, 0, true, false,
                null, null, null, false, null, -1, null, null, null, null);
    }

    public static HgFeedItem episode(String seriesId, String videoId, int episodeIndex,
                                     int episodeCount, String title, String category,
                                     String authorName, String authorAvatar, String desc,
                                     String coverUrl) {
        return new HgFeedItem(seriesId, videoId, episodeIndex, episodeCount, title, category,
                authorName, authorAvatar, desc, coverUrl, 0, 0, false, false,
                null, null, null, false, null, -1, null, null, null, null);
    }

    /**
     * 计数格式化（官方实拍口径）：万以下出原数（实拍 705/5808/934），
     * 万起折「x.x万」（实拍 3.2万），一位小数去尾零；没数据（-1）返回 null 不显。
     */
    static String formatCount(long n) {
        if (n < 0) return null;
        if (n < 10000) return String.valueOf(n);
        double wan = n / 10000.0;
        String s = String.format(java.util.Locale.US, "%.1f", wan);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + "万";
    }

    /**
     * cell 条目 → 流页（账 §2.5）：带 vid 的直接成短片；没 vid 的先成剧头，
     * 落定时会话层拉详情定形态。呈现字段（角标/热度/分类/专辑）全程携带。
     */
    public static HgFeedItem fromSeries(HgSeriesItem s, String category) {
        HgFeedItem item = new HgFeedItem(s.getSeriesId(), s.getVid(), -1, s.getEpisodeCount(),
                s.getTitle(), category, s.getAuthorName(), s.getAuthorAvatar(), s.getDesc(), s.getCoverUrl(), 0, 0,
                s.getVid() == null, s.getVid() != null,
                s.getBadge(), s.getRecText(), s.getTags(), s.isMultiSeason(),
                s.getAlbumId(), s.getAlbumSerialCount(), s.getSiblings(),
                formatCount(s.getDiggCount()), formatCount(s.getCommentCount()), s.getSeriesStatus());
        if (s.hasAppointment()) {
            item.reservable = true;
            item.dueStartText = s.getDueStartText();
        }
        item.authorUid = s.getAuthorUid();
        item.authorDesc = s.getAuthorDesc();
        item.statement = s.getStatement();
        if (s.getVertical() != null) item.landscapeHint = !s.getVertical();
        return item;
    }

    /** 带视频尺寸的页（尺寸来自取模型回包，决定信箱式还是铺满）。 */
    public HgFeedItem withSize(int width, int height) {
        HgFeedItem copy = new HgFeedItem(seriesId, videoId, episodeIndex, episodeCount, title,
                category, authorName, authorAvatar, desc, coverUrl, width, height,
                seriesHead, clip, badge, recText, tags, multiSeason, albumId,
                albumSerialCount, siblings, likeCount, commentCount, seriesStatus);
        copy.expandTried = expandTried;
        copy.reservable = reservable;
        copy.dueStartText = dueStartText;
        copy.hotComment = hotComment;
        copy.hotKind = hotKind;
        copy.statement = statement;
        copy.authorUid = authorUid;
        copy.authorDesc = authorDesc;
        copy.landscapeHint = landscapeHint;
        return copy;
    }

    /** 剧名正名后的页：短片自带文案当标题入流时，详情到手后换回真剧名。 */
    public HgFeedItem withSeriesTitle(String seriesTitle) {
        if (seriesTitle == null || seriesTitle.isEmpty() || seriesTitle.equals(title)) return this;
        HgFeedItem copy = new HgFeedItem(seriesId, videoId, episodeIndex, episodeCount, seriesTitle,
                category, authorName, authorAvatar, desc, coverUrl, videoWidth, videoHeight,
                seriesHead, clip, badge, recText, tags, multiSeason, albumId,
                albumSerialCount, siblings, likeCount, commentCount, seriesStatus);
        copy.expandTried = expandTried;
        copy.reservable = reservable;
        copy.dueStartText = dueStartText;
        copy.hotComment = hotComment;
        copy.hotKind = hotKind;
        copy.statement = statement;
        copy.authorUid = authorUid;
        copy.authorDesc = authorDesc;
        copy.landscapeHint = landscapeHint;
        return copy;
    }

    /**
     * 详情富集后的页：补作者/头像/简介（短片定形态与富集共用）。
     * 只在短片语境调用（剧头定形态/短片富集），产物恒为短片形态；
     * 集流的页走 {@link #episode} 工厂，不经这里。
     */
    public HgFeedItem withDetail(String videoId, int episodeIndex, String author, String avatar,
                                 String detailDesc) {
        HgFeedItem copy = new HgFeedItem(seriesId,
                videoId != null ? videoId : this.videoId,
                episodeIndex > 0 ? episodeIndex : this.episodeIndex,
                episodeCount, title, category,
                author != null ? author : authorName,
                avatar != null ? avatar : authorAvatar,
                detailDesc != null ? detailDesc : desc,
                coverUrl, videoWidth, videoHeight, false, true,
                badge, recText, tags, multiSeason, albumId, albumSerialCount, siblings,
                likeCount, commentCount, seriesStatus);
        copy.expandTried = true;
        copy.reservable = reservable;
        copy.dueStartText = dueStartText;
        copy.hotComment = hotComment;
        copy.hotKind = hotKind;
        copy.statement = statement;
        copy.authorUid = authorUid;
        copy.authorDesc = authorDesc;
        copy.landscapeHint = landscapeHint;
        return copy;
    }

    /** 是否横屏剧（宽大于高）；尺寸未知时 cell 的 vertical 预判由 fromSeries 侧补（见适配器）。 */
    public boolean isLandscape() {
        return videoWidth > 0 && videoHeight > 0 && videoWidth > videoHeight;
    }

    /**
     * 剧条前缀（官方 CatalogView 定版，dig-seriessdk §2：前缀是独立文本位、
     * 按内容类型三档——Album→「合集」、Movie→「电影」、其余→「选集」，
     * 串源 e4_/eeq/eer）。自家数据面没有 Movie 类型字段，movie 恒传 false
     * （拼法已备、暂不触发，账 fix-rail-catalog-series 留痕）。
     */
    public static String catalogPrefix(boolean album, boolean movie) {
        if (movie) return "电影";
        if (album) return "合集";
        return "选集";
    }

    /** 电影分钟数（官方口径：时长秒数 /60 向上取整）。 */
    public static int movieMinutes(long durationSec) {
        if (durationSec <= 0) return 0;
        return (int) Math.ceil(durationSec / 60.0);
    }

    /**
     * 剧条标题段（官方 catalogview/l 拼法定版，dig-seriessdk §2）：剧名＋状态段——
     * 电影「剧名 · 共X分钟」；已完结「剧名 · 已完结 · 全N集」；未完结
     * 「剧名 · 全N集」。集数未知不编「全0集」：完结只带「 · 已完结」、
     * 未完结不带状态段。官方另有付费/番茄原著改编/每日更新三段，自家
     * 数据面无对应字段，不拼。
     */
    public static String catalogTitleText(String title, boolean finished,
                                          int episodeCount, int movieMinutes) {
        StringBuilder sb = new StringBuilder();
        if (title != null) sb.append(title);
        if (movieMinutes > 0) {
            sb.append(" · 共").append(movieMinutes).append("分钟");
        } else if (episodeCount > 0) {
            if (finished) sb.append(" · 已完结");
            sb.append(" · 全").append(episodeCount).append("集");
        } else if (finished) {
            sb.append(" · 已完结");
        }
        return sb.toString();
    }

    /**
     * 本剧条文案：前缀＋标题段（dig-seriessdk §2 定版，取代旧「类目＋剧名
     * ＋全N集＋已完结」序——旧序出自实拍读图，集数/完结段次序与类目词都
     * 被代码级证据推翻）。前缀与标题段之间以一空格相接（官方是两文本位
     * 相邻，自家单 TextView 以空格代行，与实拍类目款的接法一致）。
     * 合集判定与适配器系列剧款同口径（multiSeason／albumId／兄弟部任一）。
     */
    public String seriesBarText() {
        boolean album = multiSeason || albumId != null || !siblings.isEmpty();
        boolean finished = seriesStatus != null && seriesStatus == 1;
        return catalogPrefix(album, false) + " "
                + catalogTitleText(title, finished, episodeCount, 0);
    }

    /**
     * 集流页选集条文案：前缀＋状态段、不带剧名（官方实拍 113558 集流页条
     * 「选集 · 已完结 · 全132集」证；与流页条同一拼法，只差标题段有无——
     * 集流里当前页就是这部剧，剧名在标题行另有位置）。状态段次序与
     * catalogTitleText 同口径：完结段在全N集之前；集数未知不编全0集。
     */
    public String seriesCatalogText() {
        boolean album = multiSeason || albumId != null || !siblings.isEmpty();
        boolean finished = seriesStatus != null && seriesStatus == 1;
        StringBuilder sb = new StringBuilder(catalogPrefix(album, false));
        if (episodeCount > 0) {
            if (finished) sb.append(" · 已完结");
            sb.append(" · 全").append(episodeCount).append("集");
        } else if (finished) {
            sb.append(" · 已完结");
        }
        return sb.toString();
    }
}
