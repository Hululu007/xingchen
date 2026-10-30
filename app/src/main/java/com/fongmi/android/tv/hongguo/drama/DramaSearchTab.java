package com.fongmi.android.tv.hongguo.drama;

/**
 * 搜索结果页签（search/tab 回包 data.search_tabs 的一项）：服务端下发
 * 页签面，点它换请求 tab_type 重查。短剧面五签（综合/剧集/视频＋批四
 * 补的讨论 31/用户 27，普查分册二卡 5/7）；小说(1)/听书(2)是番茄通用
 * 搜索的面、加不加待裁（普查总图挂账），本类不收。
 */
public final class DramaSearchTab {

    public final int type;
    public final String title;

    public DramaSearchTab(int type, String title) {
        this.type = type;
        this.title = title;
    }
}
