package com.fongmi.android.tv.ui.music;

/**
 * 播放页宿主接口（批 1 解耦）：工具面板等对话框只认本接口，不再 instanceof 播放页具体类。
 * 三个回调即旧页被 MusicToolsSheetDialog 直接调用的全集：
 * 自定义封面变更后刷新封面、歌词版本数、打开歌词版本面板。
 */
public interface MusicPlayerHost {

    /** 自定义封面设置／恢复后重刷当前曲封面与背景。 */
    void refreshCurrentCover();

    /** 当前曲已聚合的歌词版本数（0＝尚未聚合或无版本）。 */
    int getLyricVersionCount();

    /** 打开歌词版本面板（0／1 版时也允许开，面板内有空态与搜索入口）。 */
    void openLyricVersionDialog();
}
