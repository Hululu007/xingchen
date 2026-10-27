package com.fongmi.android.tv.hongguo.model;

/**
 * bookmall 频道续页凭证（对照 openhongguo DiscoverContinuation，逐字段同口径）。
 *
 * <p>开页（bookmall/tab）回包里 tab 项带 session_id，单元格带 cell_id_str 与
 * show_type；续页（bookmall/cell/change）按本凭证＋next_offset 往后翻，
 * 每页回包再带新的 next_offset / session_id 覆盖。</p>
 */
public final class HgContinuation {

    private final String cellId;
    private final String sessionId;
    /** tab.client_template 原样回传；回包没带（≤0 语义）时为 null，续页不带该参。 */
    private final String clientTemplate;
    private final String showType;
    /** 续页 tab_type（＝开页的响应 tab_type，如 16/36/39）。 */
    private final String tabType;
    /** 续页 offset：首轮为开页回包的 next_offset（开页没给时为 "0"）。 */
    private final String offset;

    public HgContinuation(String cellId, String sessionId, String clientTemplate,
                          String showType, String tabType, String offset) {
        this.cellId = cellId;
        this.sessionId = sessionId;
        this.clientTemplate = clientTemplate;
        this.showType = showType;
        this.tabType = tabType;
        this.offset = offset == null || offset.isEmpty() ? "0" : offset;
    }

    /** 续页回包后推进：offset 换新页给的 next_offset，session_id 有新值时覆盖。 */
    public HgContinuation advanced(String nextOffset, String newSessionId) {
        return new HgContinuation(cellId,
                newSessionId != null && !newSessionId.isEmpty() ? newSessionId : sessionId,
                clientTemplate, showType, tabType,
                nextOffset == null || nextOffset.isEmpty() ? offset : nextOffset);
    }

    public String getCellId() { return cellId; }
    public String getSessionId() { return sessionId; }
    public String getClientTemplate() { return clientTemplate; }
    public String getShowType() { return showType; }
    public String getTabType() { return tabType; }
    public String getOffset() { return offset; }
}
