package com.fongmi.android.tv.music;

import com.fongmi.android.tv.bean.music.MusicTrack;

import java.util.Collections;
import java.util.List;

/**
 * 分页搜索一页的结果＋末页信号（F1 低·FreeJS isEnd 被丢弃的治本载体）：
 * 旧 SPI 只有曲目列表，源明示的「没有下一页」（FreeJS 协议 isEnd、落雪平台
 * 不满整页、Py 的 pagecount）传不到调用方，末页还要多发一页靠回空兜底。
 * end=true 表示该源该关键词已到末页，调用方据此判桶终局、不再续页。
 */
public class MusicSearchPage {

    public final List<MusicTrack> tracks;
    public final boolean end;
    /** 失败归纳码（源自报，0＝无）：油管第一页全灭时带 LxPlatformSearch.YT_FAIL_*，
     * 搜索页据此给油管桶单独的空态文案；其它源恒 0、呈现不变。 */
    public final int failCause;

    public MusicSearchPage(List<MusicTrack> tracks, boolean end) {
        this(tracks, end, 0);
    }

    public MusicSearchPage(List<MusicTrack> tracks, boolean end, int failCause) {
        this.tracks = tracks == null ? Collections.emptyList() : tracks;
        this.end = end;
        this.failCause = failCause;
    }
}
