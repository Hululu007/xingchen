package com.fongmi.android.tv.api.music;

import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicMediaSource;
import com.fongmi.android.tv.bean.music.MusicQuality;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicSource;

import java.util.Collections;
import java.util.List;

/**
 * FreeJS 真源：实现播放器侧 MusicSource SPI，向 MusicRepository 注册即可用。
 * 内部按插件（srcUrl）管多个 FreeJsBridge；platform 统一为 "freejs"。
 */
public class FreeJsMusicSource implements MusicSource {

    public static final String PLATFORM = "freejs";

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public List<MusicSite> sites() {
        return MusicSourceManager.get().enabledSites();
    }

    @Override
    public List<MusicTrack> search(String keyword) throws Exception {
        FreeJsBridge b = MusicSourceManager.get().primaryBridge();
        if (b == null) return Collections.emptyList();
        return b.search(keyword, 1, "music").getTracks();
    }

    @Override
    public List<MusicTrack> tracks(MusicSite site) {
        // FreeJS 协议没有"源首页曲目"概念，返回空
        return Collections.emptyList();
    }

    @Override
    public List<MusicChart> charts(MusicSite site) throws Exception {
        FreeJsBridge b = MusicSourceManager.get().bridgeOf(site);
        if (b == null) return Collections.emptyList();
        return b.getTopLists();
    }

    @Override
    public List<MusicTrack> chartTracks(MusicChart chart) throws Exception {
        if (chart == null || chart.id == null) return Collections.emptyList();
        int sep = chart.id.indexOf('|');
        if (sep < 0) return Collections.emptyList();
        String srcUrl = chart.id.substring(0, sep);
        String listId = chart.id.substring(sep + 1);
        for (MusicSite s : MusicSourceManager.get().enabledSites()) {
            if (srcUrl.equals(s.srcUrl)) {
                FreeJsBridge b = MusicSourceManager.get().bridgeOf(s);
                if (b == null) return Collections.emptyList();
                return b.getTopListDetail(listId).getTracks();
            }
        }
        return Collections.emptyList();
    }

    @Override
    public String playUrl(MusicTrack track, int quality) throws Exception {
        FreeJsBridge b = MusicSourceManager.get().bridgeForTrack(track);
        if (b == null) return null;
        MusicMediaSource ms = b.getMediaSource(track, MusicQuality.fromInt(quality));
        return ms == null ? null : ms.url;
    }

    @Override
    public String lyric(MusicTrack track) throws Exception {
        FreeJsBridge b = MusicSourceManager.get().bridgeForTrack(track);
        if (b == null) return null;
        MusicLyric l = b.getLyric(track);
        return l == null ? null : l.rawLrc;
    }
}
