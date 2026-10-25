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

    /**
     * 支持搜歌的子源（插件）列表：搜索页横排只放这些。
     * 只提供歌词等非搜歌能力的插件不要出现在搜索排里，实现按真实能力过滤。
     * 默认＝全部子源。
     */
    default List<MusicSite> searchableSites() {
        return sites();
    }

    /**
     * 按单个子源搜歌（阻塞调用，请在子线程调），结果只属于该子源。
     * 默认退化为整源合并搜（search）。
     */
    default List<MusicTrack> searchBySite(MusicSite site, String keyword) throws Exception {
        return search(keyword);
    }

    /**
     * 分页搜歌（page 从 1 起）：真支持分页的源覆写本方法把页码传到桥／接口；
     * 默认实现只有第一页（走旧两参），第二页起返回空——调用方据空判「没有更多」，
     * 不许拿第一页结果冒充第二页（会出整页重复行）。
     */
    default List<MusicTrack> searchBySite(MusicSite site, String keyword, int page) throws Exception {
        return page <= 1 ? searchBySite(site, keyword) : java.util.Collections.emptyList();
    }

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

    /**
     * 探链口径（第二波堵-4）：probe＝true 时只读地址、不记死——音质面板逐档算大小走这条，
     * 面板探链偶发失败不许污染同曲真实点播。默认退化为真实取链（无死标记的源不受影响）；
     * 落雪／FreeJS 两源各自覆写，把 probe 透传到死标记检查处。
     */
    default String playUrl(MusicTrack track, int quality, boolean probe) throws Exception {
        return playUrl(track, quality);
    }

    /** 取歌词 LRC 文本，可空（阻塞调用，请在子线程调） */
    String lyric(MusicTrack track) throws Exception;
}
