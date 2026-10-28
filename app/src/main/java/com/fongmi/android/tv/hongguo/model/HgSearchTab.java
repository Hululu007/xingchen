package com.fongmi.android.tv.hongguo.model;

/**
 * 搜索结果页签（search/tab 回包 data.search_tabs 的一项）：服务端下发
 * 页签面，点它换请求 tab_type 重查。红果只接视频面三页签（综合/剧集/
 * 视频），小说/听书/用户/讨论是番茄通用搜索的面、不在短剧范围内。
 */
public final class HgSearchTab {

    public final int type;
    public final String title;

    public HgSearchTab(int type, String title) {
        this.type = type;
        this.title = title;
    }
}
