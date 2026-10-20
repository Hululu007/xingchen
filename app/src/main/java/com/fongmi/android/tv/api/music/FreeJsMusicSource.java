package com.fongmi.android.tv.api.music;

import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicMediaSource;
import com.fongmi.android.tv.bean.music.MusicQuality;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicSource;
import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
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

    /**
     * 搜索排判据：只有插件自己声明支持 music 搜索的才进排。
     * 歌词型插件（歌词网/歌词千寻）的 search 只认 type=lyric、supportedSearchType 只有
     * ['lyric']，不进搜索排；它们继续在播放页后台配歌词，歌词链路不动。
     */
    @Override
    public List<MusicSite> searchableSites() {
        List<MusicSite> result = new ArrayList<>();
        for (MusicSite s : sites()) {
            try {
                FreeJsBridge b = MusicSourceManager.get().bridgeOf(s);
                if (b != null && b.supportsMusicSearch()) result.add(s);
            } catch (Throwable t) {
                SpiderDebug.log("music", "searchable site error site=%s %s", s.name, t.getMessage());
            }
        }
        return result;
    }

    /** 单插件搜歌：结果只属于该插件，不混其它插件的（阻塞，请在子线程调） */
    @Override
    public List<MusicTrack> searchBySite(MusicSite site, String keyword) throws Exception {
        FreeJsBridge b = MusicSourceManager.get().bridgeOf(site);
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

    /**
     * 歌词兜底：按启用顺序遍历其它插件（跳过歌曲自己的插件），
     * 用歌名+歌手搜到同一首歌再取它的歌词；复用桥已有的 search/getLyric 通道。
     */
    public String lyricFallback(MusicTrack track) {
        if (track == null) return null;
        String keyword = (track.title == null ? "" : track.title).trim()
                + " " + (track.artist == null ? "" : track.artist).trim();
        keyword = keyword.trim();
        if (keyword.isEmpty()) return null;
        String own = pluginOf(track);
        try {
            for (MusicSite site : MusicSourceManager.get().enabledSites()) {
                if (site == null || site.srcUrl == null) continue;
                if (own != null && own.equals(site.srcUrl)) continue;
                FreeJsBridge b;
                try {
                    b = MusicSourceManager.get().bridgeOf(site);
                } catch (Throwable ignored) {
                    continue;
                }
                if (b == null) continue;
                List<MusicTrack> candidates;
                try {
                    candidates = b.search(keyword, 1, "music").getTracks();
                } catch (Throwable ignored) {
                    continue;
                }
                MusicTrack hit = com.fongmi.android.tv.music.MusicRepository.matchSong(track, candidates);
                if (hit == null) continue;
                try {
                    MusicLyric l = b.getLyric(hit);
                    if (l != null && l.rawLrc != null && !l.rawLrc.trim().isEmpty()) return l.rawLrc;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** track.rawJson 里的 _plugin（歌曲自己的插件 srcUrl），取不到返回 null */
    private static String pluginOf(MusicTrack track) {
        try {
            if (track == null || track.rawJson == null || track.rawJson.isEmpty()) return null;
            return com.google.gson.JsonParser.parseString(track.rawJson)
                    .getAsJsonObject().get("_plugin").getAsString();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
