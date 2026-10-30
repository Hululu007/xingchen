package com.fongmi.android.tv.hongguo.drama;

import com.fongmi.android.tv.hongguo.model.HgContinuation;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;

/**
 * cell 驱动接口（剧场分类/搜索结果/计划位/追剧架）的通用回包（不可变）。
 *
 * <p>这类接口的 cell 结构由服务端编排、字段口径未在样本里定版：本类保留原始
 * JsonObject 不丢信息，同时尽力抽出剧条目列表供上层直接用。</p>
 */
public final class DramaCellPage {

    private final int code;
    private final String message;
    private final List<DramaItem> items;
    private final JsonObject raw;
    /** 频道面续页态（bookmall/tab 与 cell/change 回包才有；其余面为 false/null）。 */
    private final boolean hasMore;
    private final String nextOffset;
    private final String sessionId;
    private final HgContinuation continuation;
    /** 搜索结果页签（只有 search/tab 回包带，其余面为空表）。 */
    private final List<DramaSearchTab> tabs;
    /** 搜索结果按型行表（只有走 parseCellPage(json, tabType) 的搜索口带：
     * 服务端原序、多型同列；items 仍是其中的剧/视频子集。同口回包的
     * hasMore/nextOffset 即签段服务端下发的续拉口径（has_more/next_offset），
     * 搜索续拉以此为准、不按条数自算。其余面为空表）。 */
    private final List<DramaSearchRow> searchRows;
    /** 小分类面（频道回包递归抽取 category_id+名称；其余面为空表）。 */
    private final List<DramaCategory> categories;

    DramaCellPage(int code, String message, List<DramaItem> items, JsonObject raw) {
        this(code, message, items, raw, false, null, null, null);
    }

    DramaCellPage(int code, String message, List<DramaItem> items, JsonObject raw,
               boolean hasMore, String nextOffset, String sessionId,
               HgContinuation continuation) {
        this(code, message, items, raw, hasMore, nextOffset, sessionId, continuation,
                Collections.emptyList());
    }

    DramaCellPage(int code, String message, List<DramaItem> items, JsonObject raw,
               boolean hasMore, String nextOffset, String sessionId,
               HgContinuation continuation, List<DramaSearchTab> tabs) {
        this(code, message, items, raw, hasMore, nextOffset, sessionId, continuation,
                tabs, Collections.emptyList());
    }

    DramaCellPage(int code, String message, List<DramaItem> items, JsonObject raw,
               boolean hasMore, String nextOffset, String sessionId,
               HgContinuation continuation, List<DramaSearchTab> tabs,
               List<DramaSearchRow> searchRows) {
        this(code, message, items, raw, hasMore, nextOffset, sessionId, continuation,
                tabs, searchRows, Collections.emptyList());
    }

    DramaCellPage(int code, String message, List<DramaItem> items, JsonObject raw,
               boolean hasMore, String nextOffset, String sessionId,
               HgContinuation continuation, List<DramaSearchTab> tabs,
               List<DramaSearchRow> searchRows, List<DramaCategory> categories) {
        this.code = code;
        this.message = message;
        this.items = Collections.unmodifiableList(items);
        this.raw = raw;
        this.hasMore = hasMore;
        this.nextOffset = nextOffset;
        this.sessionId = sessionId;
        this.continuation = continuation;
        this.tabs = Collections.unmodifiableList(tabs);
        this.searchRows = Collections.unmodifiableList(searchRows);
        this.categories = Collections.unmodifiableList(categories);
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
    public List<DramaItem> getItems() { return items; }
    public JsonObject getRaw() { return raw; }
    public boolean isHasMore() { return hasMore; }
    public String getNextOffset() { return nextOffset; }
    public String getSessionId() { return sessionId; }
    public HgContinuation getContinuation() { return continuation; }
    public List<DramaSearchTab> getTabs() { return tabs; }
    public List<DramaSearchRow> getSearchRows() { return searchRows; }
    public List<DramaCategory> getCategories() { return categories; }
}
