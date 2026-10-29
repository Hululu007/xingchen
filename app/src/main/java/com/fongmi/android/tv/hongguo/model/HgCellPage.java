package com.fongmi.android.tv.hongguo.model;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;

/**
 * cell 驱动接口（剧场分类/搜索结果/计划位/追剧架）的通用回包（不可变）。
 *
 * <p>这类接口的 cell 结构由服务端编排、字段口径未在样本里定版：本类保留原始
 * JsonObject 不丢信息，同时尽力抽出剧条目列表供上层直接用。</p>
 */
public final class HgCellPage {

    private final int code;
    private final String message;
    private final List<HgSeriesItem> items;
    private final JsonObject raw;
    /** 频道面续页态（bookmall/tab 与 cell/change 回包才有；其余面为 false/null）。 */
    private final boolean hasMore;
    private final String nextOffset;
    private final String sessionId;
    private final HgContinuation continuation;
    /** 搜索结果页签（只有 search/tab 回包带，其余面为空表）。 */
    private final List<HgSearchTab> tabs;
    /** 搜索结果按型行表（只有走 parseCellPage(json, tabType) 的搜索口带：
     * 服务端原序、多型同列；items 仍是其中的剧/视频子集，供既有计数与
     * 翻页口径。其余面为空表）。 */
    private final List<HgSearchRow> searchRows;

    HgCellPage(int code, String message, List<HgSeriesItem> items, JsonObject raw) {
        this(code, message, items, raw, false, null, null, null);
    }

    HgCellPage(int code, String message, List<HgSeriesItem> items, JsonObject raw,
               boolean hasMore, String nextOffset, String sessionId,
               HgContinuation continuation) {
        this(code, message, items, raw, hasMore, nextOffset, sessionId, continuation,
                Collections.emptyList());
    }

    HgCellPage(int code, String message, List<HgSeriesItem> items, JsonObject raw,
               boolean hasMore, String nextOffset, String sessionId,
               HgContinuation continuation, List<HgSearchTab> tabs) {
        this(code, message, items, raw, hasMore, nextOffset, sessionId, continuation,
                tabs, Collections.emptyList());
    }

    HgCellPage(int code, String message, List<HgSeriesItem> items, JsonObject raw,
               boolean hasMore, String nextOffset, String sessionId,
               HgContinuation continuation, List<HgSearchTab> tabs,
               List<HgSearchRow> searchRows) {
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
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
    public List<HgSeriesItem> getItems() { return items; }
    public JsonObject getRaw() { return raw; }
    public boolean isHasMore() { return hasMore; }
    public String getNextOffset() { return nextOffset; }
    public String getSessionId() { return sessionId; }
    public HgContinuation getContinuation() { return continuation; }
    public List<HgSearchTab> getTabs() { return tabs; }
    public List<HgSearchRow> getSearchRows() { return searchRows; }
}
