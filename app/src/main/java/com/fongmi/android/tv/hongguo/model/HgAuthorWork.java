package com.fongmi.android.tv.hongguo.model;

import java.util.Collections;
import java.util.List;

/**
 * 红果作者主页一条内容（作品/收藏/点赞三签共用）。
 *
 * <p>作品签数据源＝POST /novel/commentapi/user/profile/:uid/v1/
 * （官方 CommentApiService.getUserProfileRxJava 定版，本机实拉三位作者
 * 验通）：data_list 按 data_type 分形——1=video（common{cover/title/intro}
 * ＋expand{episode_cnt/sub_title_list}＋stat{digg_cnt/play_cnt/hot}）、
 * 3=post（common{title/content/cover}）、4=comment（剧评，common.content
 * ＋expand.score 十分制）。tab_type=11（ProduceVideo「剧作」）回的
 * video 条目 video_id 即 series_id（ohg info 实证可直查详情）；
 * tab_type=5（Video「动态」）回的是单条 UGC 视频/帖子。</p>
 *
 * <p>收藏签数据源＝GET /reading/bookapi/bookshelf/video/info
 * （target_user_id 维度，本机实拉验通）：video_shelf_info 条目带
 * series_id/series_title/series_cover_url。点赞签数据源＝
 * GET /reading/ugc/action/mget/v1/（action_type=3，本机实拉验通）：
 * mixed_data_list 条目 data_type=30 带 video_data{vid/series_id/title/
 * cover/episode_cnt}、35 带 video_detail_info。</p>
 */
public final class HgAuthorWork {

    public static final int KIND_VIDEO = 1;
    public static final int KIND_POST = 3;
    public static final int KIND_COMMENT = 4;

    public final int kind;
    /** video_id / post_id / comment_id。 */
    public final String id;
    /** 所属剧 id；单条 UGC 视频/帖子为空串。 */
    public final String seriesId;
    public final String cover;
    public final String title;
    public final String intro;
    /** 集数（剧作条目）；非剧为 0。 */
    public final int episodeCount;
    public final long diggCount;
    public final long playCount;
    public final long hot;
    /** expand.sub_title_list 文案（如「种田」「109集」）；无则空表。 */
    public final List<String> subTitles;
    public final String authorName;
    public final String authorAvatar;
    /** 剧评评分（十分制，expand.score）；非剧评为 -1。 */
    public final int score;
    /** 发布时间（秒）。 */
    public final long createTime;

    public HgAuthorWork(int kind, String id, String seriesId, String cover, String title,
                        String intro, int episodeCount, long diggCount, long playCount, long hot,
                        List<String> subTitles, String authorName, String authorAvatar,
                        int score, long createTime) {
        this.kind = kind;
        this.id = id == null ? "" : id;
        this.seriesId = seriesId == null ? "" : seriesId;
        this.cover = cover == null ? "" : cover;
        this.title = title == null ? "" : title;
        this.intro = intro == null ? "" : intro;
        this.episodeCount = episodeCount;
        this.diggCount = diggCount;
        this.playCount = playCount;
        this.hot = hot;
        this.subTitles = subTitles == null ? Collections.emptyList()
                : Collections.unmodifiableList(subTitles);
        this.authorName = authorName == null ? "" : authorName;
        this.authorAvatar = authorAvatar == null ? "" : authorAvatar;
        this.score = score;
        this.createTime = createTime;
    }

    /** 点进播放的剧 id：有 seriesId 用 seriesId，否则用条目 id 本身（剧作条目 id 即剧 id）。 */
    public String playSeriesId() {
        return seriesId.isEmpty() ? id : seriesId;
    }

    /** 作者页一签的子档（服务端 tabs 下发：名＋tab_type＋总数）。 */
    public static final class SubTab {
        public final String name;
        public final int tabType;
        public final long total;

        public SubTab(String name, int tabType, long total) {
            this.name = name == null ? "" : name;
            this.tabType = tabType;
            this.total = total;
        }
    }

    /** 一页结果：条目＋续拉凭据（作品签是整串 cursor；收藏/点赞签是 offset 数字串）。 */
    public static final class Page {
        public final List<HgAuthorWork> items;
        public final String nextCursor;
        public final boolean hasMore;
        /** 仅作品签首包带（服务端 tabs 的子档表）；其余为空表。 */
        public final List<SubTab> subTabs;

        public Page(List<HgAuthorWork> items, String nextCursor, boolean hasMore,
                    List<SubTab> subTabs) {
            this.items = items == null ? Collections.emptyList()
                    : Collections.unmodifiableList(items);
            this.nextCursor = nextCursor == null ? "" : nextCursor;
            this.hasMore = hasMore;
            this.subTabs = subTabs == null ? Collections.emptyList()
                    : Collections.unmodifiableList(subTabs);
        }
    }
}
