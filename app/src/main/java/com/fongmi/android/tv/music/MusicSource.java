package com.fongmi.android.tv.music;

import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;

import java.util.List;

/**
 * 音乐源 SPI（一期源抽象未落地前的播放器侧接口）。
 * 一期落地后，真源实现此接口并向 MusicRepository 注册即可，播放器侧不动。
 */
public interface MusicSource {

    /** 平台唯一标识，如 freejs:xxx */
    String platform();

    /** 该平台下的源列表 */
    List<MusicSite> sites();

    /** 关键词搜歌（阻塞调用，请在子线程调） */
    List<MusicTrack> search(String keyword) throws Exception;

    /** 源曲目（阻塞调用，请在子线程调） */
    List<MusicTrack> tracks(MusicSite site) throws Exception;

    /** 榜单（阻塞调用，请在子线程调） */
    List<MusicChart> charts(MusicSite site) throws Exception;

    /** 榜单曲目（阻塞调用，请在子线程调） */
    List<MusicTrack> chartTracks(MusicChart chart) throws Exception;

    /**
     * 取播放直链（阻塞调用，请在子线程调）。
     * @param quality MusicStore.QUALITY_* 四档
     */
    String playUrl(MusicTrack track, int quality) throws Exception;

    /** 取歌词 LRC 文本，可空（阻塞调用，请在子线程调） */
    String lyric(MusicTrack track) throws Exception;
}
