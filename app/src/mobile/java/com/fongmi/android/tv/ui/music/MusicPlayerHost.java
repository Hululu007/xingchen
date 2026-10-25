package com.fongmi.android.tv.ui.music;

/**
 * 播放页宿主接口（批 1 解耦）：工具面板等对话框只认本接口，不再 instanceof 播放页具体类。
 * 工具面板直调的只有自定义封面变更后刷新封面；版本数与打开歌词版本面板是宿主自身
 * 歌词筹入口的能力（播放页歌词筹直开版本弹窗，照瓜子去向），保留在本接口成集。
 */
public interface MusicPlayerHost {

    /** 自定义封面设置／恢复后重刷当前曲封面与背景。 */
    void refreshCurrentCover();

    /** 当前曲已聚合的歌词版本数（0＝尚未聚合或无版本）。 */
    int getLyricVersionCount();

    /** 打开歌词版本面板（0／1 版时也允许开，面板内有空态与搜索入口）。 */
    void openLyricVersionDialog();
}
