package com.fongmi.android.tv.ui.music;

/**
 * 播放页宿主接口（规格 S4 §4.0）：只有三个方法，签名逐字不许改。
 * 实际使用面：工具面板直调的只有 refreshCurrentCover()（自定义封面设置／恢复后刷封面）；
 * getLyricVersionCount／openLyricVersionDialog 的真实调用方是宿主自己的歌词筹／功能键，
 * 工具面板不经手。其余面板完全不认本接口，只认 MusicController＋各自的 show 回调。
 */
public interface MusicPlayerHost {

    /** 只重刷封面与背景，不重绑整曲（自定义封面落盘／恢复后由工具面板调用）。 */
    void refreshCurrentCover();

    /** 当前已聚合到的歌词版本数（宿主取词收口后维护的版本列表大小）。 */
    int getLyricVersionCount();

    /** 开歌词版本面板（带搜索回调，传宿主持有的版本列表副本）。 */
    void openLyricVersionDialog();
}
