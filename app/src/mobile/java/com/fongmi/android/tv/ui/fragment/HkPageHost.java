package com.fongmi.android.tv.ui.fragment;

import com.fongmi.android.tv.api.hk.HkItem;
import com.fongmi.android.tv.api.hk.HkRule;
import java.util.List;

/**
 * 小程序内页 Fragment 与 HkPageActivity 的通信接口。
 * Fragment 通过此接口调用 Activity 的业务逻辑（规则加载、JS 执行、导航等）。
 * V_RULES（首页）逻辑保留在 Activity，不经过此接口。
 */
public interface HkPageHost {

    /** 获取路由器（HkRouter），用于数据加载 */
    Object getHkRouter();

    /** 当前规则 */
    HkRule getCurrentRule();

    /** 内容页：加载数据 */
    void hostLoadContent(boolean reset);

    /** 内容页：条目点击 */
    void hostOnContentItemClick(HkItem item);

    /** 内容页：直接播放 */
    void hostPlayContentDirect(HkItem item);

    /** 搜索页：执行搜索 */
    void hostDoSearch(String keyword);

    /** 搜索页：跨规则搜索 */
    void hostDoCrossSearch(String keyword);

    /** 详情页：打开详情 */
    void hostOpenDetail(HkItem item);

    /** 详情页：播放剧集 */
    void hostPlayDetailEpisode(HkItem item, int index);

    /** 导航：返回上一页 */
    void hostNavigateBack();

    /** 导航：打开搜索页 */
    void hostOpenSearch(String keyword);

    /** 显示加载中 */
    void hostShowLoading();

    /** 隐藏加载中 */
    void hostHideLoading();
}
