package com.fongmi.android.tv.hongguo.ui;

/**
 * 刷剧流的一页＝一集（不可变字段为主，展开状态可变）。
 *
 * <p>来源有两路：剧条目先以「剧头」形态进流（{@link #seriesHead}＝true，只知剧、
 * 不知集），当前页落定时由会话层拉详情把剧头展开成逐集页；详情拿不到时剧头
 * 退化为单集页（videoId＝seriesId 走取模型兜底）。计数（点赞/评论/收藏数）
 * 在已实证的回包样本里没有对应字段，一律为 null＝不显示，不许编数字。</p>
 */
public final class HgFeedItem {

    public final String seriesId;
    /** 集 id；剧头未展开时为 null。 */
    public final String videoId;
    /** 集序号（1 起）；未知为 -1。 */
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
    /** 计数：样本回包无此字段，恒 null＝不显示；四阶段真接口给了才填。 */
    public final String likeCount;
    public final String commentCount;
    public final String favCount;

    /** 剧头（未展开）标记；展开或退化后置 false。 */
    public boolean seriesHead;
    /** 已尝试展开（成败都算），避免反复拉详情。 */
    public boolean expandTried;

    private HgFeedItem(String seriesId, String videoId, int episodeIndex, int episodeCount,
                       String title, String category, String authorName, String authorAvatar,
                       String desc, String coverUrl, int videoWidth, int videoHeight,
                       boolean seriesHead) {
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
        this.likeCount = null;
        this.commentCount = null;
        this.favCount = null;
        this.seriesHead = seriesHead;
    }

    public static HgFeedItem head(String seriesId, String title, String category,
                                  String desc, String coverUrl, int episodeCount) {
        return new HgFeedItem(seriesId, null, -1, episodeCount, title, category,
                null, null, desc, coverUrl, 0, 0, true);
    }

    public static HgFeedItem episode(String seriesId, String videoId, int episodeIndex,
                                     int episodeCount, String title, String category,
                                     String authorName, String authorAvatar, String desc,
                                     String coverUrl) {
        return new HgFeedItem(seriesId, videoId, episodeIndex, episodeCount, title, category,
                authorName, authorAvatar, desc, coverUrl, 0, 0, false);
    }

    /** 带视频尺寸的集页（尺寸来自取模型回包，决定信箱式还是铺满）。 */
    public HgFeedItem withSize(int width, int height) {
        HgFeedItem copy = new HgFeedItem(seriesId, videoId, episodeIndex, episodeCount, title,
                category, authorName, authorAvatar, desc, coverUrl, width, height, seriesHead);
        copy.expandTried = expandTried;
        return copy;
    }

    /** 是否横屏剧（宽大于高）；尺寸未知按竖屏处理。 */
    public boolean isLandscape() {
        return videoWidth > 0 && videoHeight > 0 && videoWidth > videoHeight;
    }

    /** 本剧条文案素材：集数已知才带「·全N集」。 */
    public String seriesBarText() {
        StringBuilder sb = new StringBuilder();
        if (category != null && !category.isEmpty()) sb.append(category).append(' ');
        if (title != null) sb.append(title);
        if (episodeCount > 0) sb.append("·全").append(episodeCount).append("集");
        return sb.toString();
    }
}
