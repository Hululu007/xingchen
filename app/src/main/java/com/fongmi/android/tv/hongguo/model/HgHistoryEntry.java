package com.fongmi.android.tv.hongguo.model;

import java.util.Collections;
import java.util.List;

/**
 * 红果服务端观看历史一条（「我的」历史格的同步源）。
 *
 * <p>数据源＝GET /reading/bookapi/read_history/list/v1/（官方 BookApiService
 * GetReadHistory 定版，VideoRecordImpl 消费实证）：请求带 book_type
 * （ReadingBookType：Watch=2 短剧／Album=3 专辑／Pugc=4）、offset、
 * is_first_load，回包 data{data_list[], has_more, next_offset, total}，
 * 条目即 rpc ReadHistoryData——book_id/book_id_str＝剧 id、book_name＝剧名、
 * thumb_url＝封面、episode_cnt＝总集数、vid_index＝看到第几集（1 起）、
 * current_play_position＝集内位置毫秒、read_timestamp_ms＝观看时刻、
 * is_delete＝服务端已删标记。本机匿名实拉结构验通（游客身份回空表，
 * 真数据靠登录会话）。</p>
 */
public final class HgHistoryEntry {

    public final String seriesId;
    public final String title;
    public final String cover;
    /** 总集数；回包没带为 -1。 */
    public final int episodeCount;
    /** 看到第几集（vid_index，1 起）；没带为 0。 */
    public final int episodeIndex;
    /** 集内位置毫秒（current_play_position）。 */
    public final long positionMs;
    /** 观看时刻毫秒（read_timestamp_ms）；合并排序与断点新旧判定用。 */
    public final long readTsMs;

    public HgHistoryEntry(String seriesId, String title, String cover, int episodeCount,
                          int episodeIndex, long positionMs, long readTsMs) {
        this.seriesId = seriesId == null ? "" : seriesId;
        this.title = title == null ? "" : title;
        this.cover = cover == null ? "" : cover;
        this.episodeCount = episodeCount;
        this.episodeIndex = episodeIndex;
        this.positionMs = Math.max(0, positionMs);
        this.readTsMs = readTsMs;
    }

    /** read_history/list 一页（offset 续拉）。 */
    public static final class Page {
        public final List<HgHistoryEntry> items;
        public final boolean hasMore;
        /** hasMore 时的续拉 offset；不可续为 -1。 */
        public final int nextOffset;
        public final long total;

        public Page(List<HgHistoryEntry> items, boolean hasMore, int nextOffset, long total) {
            this.items = items == null ? Collections.emptyList()
                    : Collections.unmodifiableList(items);
            this.hasMore = hasMore;
            this.nextOffset = nextOffset;
            this.total = total;
        }
    }
}
