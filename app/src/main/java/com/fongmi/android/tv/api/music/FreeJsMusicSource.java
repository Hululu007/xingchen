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
        // 只管 FreeJS 格式的插件；落雪插件走 LxMusicSource，别串桥
        return MusicSourceManager.get().enabledSitesOfFormat(MusicSourceManager.FORMAT_FREEJS);
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
                // 落盘判据已知就不建桥（第十件）：歌词型（=2）直接跳过、支持的（=1）
                // 直接进排；只有未知（=0）才建桥实测一次并落盘。旧实现每次搜索都把
                // 所有启用插件的桥串行白建一遍（下载＋冷启求值），结果还不出来
                if (s.musicSearch == 2) continue;
                if (s.musicSearch == 1) {
                    result.add(s);
                    continue;
                }
                FreeJsBridge b = MusicSourceManager.get().bridgeOf(s);
                if (b == null) continue;
                boolean ok = b.supportsMusicSearch();
                MusicSourceManager.get().noteMusicSearch(s, ok);
                if (ok) result.add(s);
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

    /**
     * 榜单只留酷我（潇洒哥 2026-10-20 拍板「只需要酷我跟QQ」）：哔哩哔哩是视频站、
     * 供的是全站视频榜，没意义；歌词网/歌词千寻是歌词型插件，不供榜。
     * 只有酷我插件（元力KW）调 getTopLists，其余 FreeJS 插件直接返回空、不碰桥。
     * 搜索/播放/歌词等其它链不受影响，哔哩哔哩照常能搜能播。
     */
    @Override
    public List<MusicChart> charts(MusicSite site) throws Exception {
        if (!isKuwoSite(site)) return Collections.emptyList();
        FreeJsBridge b = MusicSourceManager.get().bridgeOf(site);
        if (b == null) return Collections.emptyList();
        return b.getTopLists();
    }

    /**
     * 酷我插件判据（以 MusicSourceManager.builtinSites 播种值为准）：
     * 插件自报 platform 经 FreeJsBridge 回填进 site.name，预置播种名同为「酷我音乐」；
     * srcUrl 命中预置的元力KW 地址（原文/URL 编码形）也算。两条都是精确匹配，不搞模糊。
     */
    private static boolean isKuwoSite(MusicSite site) {
        if (site == null) return false;
        if ("酷我音乐".equals(site.name)) return true;
        String url = site.srcUrl == null ? "" : site.srcUrl;
        return url.endsWith("元力KW.js") || url.endsWith("%E5%85%83%E5%8A%9BKW.js");
    }

    @Override
    public List<MusicTrack> chartTracks(MusicChart chart) throws Exception {
        if (chart == null || chart.id == null) return Collections.emptyList();
        int sep = chart.id.indexOf('|');
        if (sep < 0) return Collections.emptyList();
        String srcUrl = chart.id.substring(0, sep);
        String listId = chart.id.substring(sep + 1);
        for (MusicSite s : MusicSourceManager.get().enabledSitesOfFormat(MusicSourceManager.FORMAT_FREEJS)) {
            if (srcUrl.equals(s.srcUrl)) {
                FreeJsBridge b = MusicSourceManager.get().bridgeOf(s);
                if (b == null) return Collections.emptyList();
                return b.getTopListDetail(listId, chart.rawJson).getTracks();
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

    /** 完整歌词（正文＋译＋音译）：桥 getLyric 的完整 MusicLyric，不压成纯字符串 */
    public MusicLyric lyricRich(MusicTrack track) throws Exception {
        FreeJsBridge b = MusicSourceManager.get().bridgeForTrack(track);
        if (b == null) return null;
        return b.getLyric(track);
    }

    /** lyricFallback 的 rich 版：同一条遍历，命中返回完整 MusicLyric */
    public MusicLyric lyricFallbackRich(MusicTrack track) {
        if (track == null) return null;
        String keyword = (track.title == null ? "" : track.title).trim()
                + " " + (track.artist == null ? "" : track.artist).trim();
        keyword = keyword.trim();
        if (keyword.isEmpty()) return null;
        String own = pluginOf(track);
        MusicLyric romajiOnly = null;
        try {
            for (MusicSite site : MusicSourceManager.get().enabledSitesOfFormat(MusicSourceManager.FORMAT_FREEJS)) {
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
                    // 命中口径：正文或译文才立即返回（译文-only 也算，仓库
                    // normalizeRich 会把定时译文提为正文）；纯音译只留作候选，
                    // 不许截断后面的插件——它们可能有正文
                    if (l != null && l.hasBodyText()) return l;
                    if (l != null && l.hasLyric() && romajiOnly == null) romajiOnly = l;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return romajiOnly;
    }

    /** lyricFromLyricPlugins 的 rich 版：同一条直查，命中返回完整 MusicLyric */
    public MusicLyric lyricFromLyricPluginsRich(MusicTrack track) {
        if (track == null) return null;
        String keyword = (track.title == null ? "" : track.title).trim()
                + " " + (track.artist == null ? "" : track.artist).trim();
        keyword = keyword.trim();
        if (keyword.isEmpty()) return null;
        MusicLyric romajiOnly = null;
        try {
            for (MusicSite site : MusicSourceManager.get().enabledSitesOfFormat(MusicSourceManager.FORMAT_FREEJS)) {
                if (site == null || site.srcUrl == null) continue;
                FreeJsBridge b;
                try {
                    b = MusicSourceManager.get().bridgeOf(site);
                } catch (Throwable ignored) {
                    continue;
                }
                if (b == null) continue;
                try {
                    if (b.supportsMusicSearch()) continue;
                } catch (Throwable ignored) {
                    continue;
                }
                try {
                    List<MusicTrack> candidates = b.search(keyword, 1, "lyric").getTracks();
                    MusicTrack hit = com.fongmi.android.tv.music.MusicRepository.matchSong(track, candidates);
                    if (hit == null) continue;
                    MusicLyric l = b.getLyric(hit);
                    // 同 lyricFallbackRich：正文/译文立即返回，纯音译只留候选不截断
                    if (l != null && l.hasBodyText()) return l;
                    if (l != null && l.hasLyric() && romajiOnly == null) romajiOnly = l;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return romajiOnly;
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
            for (MusicSite site : MusicSourceManager.get().enabledSitesOfFormat(MusicSourceManager.FORMAT_FREEJS)) {
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

    /**
     * 歌词型插件直查歌词（兜底链最后一步）：歌词网/歌词千寻这类插件的 search
     * 只认 type="lyric"，前面所有兜底都在拿它们搜「歌」（type=music / 源级 search），
     * 永远问不到——本地/设备歌缺词主要就卡在这。判据：supportsMusicSearch 为假
     * （声明里没有 music）且有 search 函数的插件才问；能搜歌的插件已在
     * lyricFallback 里问过，不重复。逐个插件：lyric 搜→matchSong 对歌→getLyric 取词。
     */
    public String lyricFromLyricPlugins(MusicTrack track) {
        if (track == null) return null;
        String keyword = (track.title == null ? "" : track.title).trim()
                + " " + (track.artist == null ? "" : track.artist).trim();
        keyword = keyword.trim();
        if (keyword.isEmpty()) return null;
        try {
            for (MusicSite site : MusicSourceManager.get().enabledSitesOfFormat(MusicSourceManager.FORMAT_FREEJS)) {
                if (site == null || site.srcUrl == null) continue;
                FreeJsBridge b;
                try {
                    b = MusicSourceManager.get().bridgeOf(site);
                } catch (Throwable ignored) {
                    continue;
                }
                if (b == null) continue;
                try {
                    if (b.supportsMusicSearch()) continue; // 能搜歌的不在这条问
                } catch (Throwable ignored) {
                    continue;
                }
                try {
                    List<MusicTrack> candidates = b.search(keyword, 1, "lyric").getTracks();
                    MusicTrack hit = com.fongmi.android.tv.music.MusicRepository.matchSong(track, candidates);
                    if (hit == null) continue;
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
