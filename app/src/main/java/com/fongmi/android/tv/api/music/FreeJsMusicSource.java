package com.fongmi.android.tv.api.music;

import android.text.TextUtils;
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
        return searchBySite(site, keyword, 1);
    }

    /** 分页版：页码原样给桥（FreeJS 协议 search(query, page, type) 本就收页码，旧实现写死 1） */
    @Override
    public List<MusicTrack> searchBySite(MusicSite site, String keyword, int page) throws Exception {
        FreeJsBridge b = MusicSourceManager.get().bridgeOf(site);
        if (b == null) return Collections.emptyList();
        List<MusicTrack> tracks = b.search(keyword, Math.max(1, page), "music").getTracks();
        // 单插件屏蔽平台：该插件屏蔽掉的内容平台不进单源结果（聚合搜索同口径在
        // MusicSourceManager.searchAll；旧实现单源搜索完全不查，屏蔽只在聚合页生效一半）
        if (tracks != null && site != null) {
            List<MusicTrack> kept = new ArrayList<>();
            for (MusicTrack t : tracks) {
                String cp = t == null ? null : com.fongmi.android.tv.music.MusicIdentity.contentPlatformOf(t);
                if (cp == null || !com.fongmi.android.tv.music.MusicStore.isPlatformBlocked(site.srcUrl, cp)) kept.add(t);
            }
            return kept;
        }
        return tracks;
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
     * 酷我榜详情改壳子直连（2026-10-21 拍板）：这里把酷我榜的 platform 改写为 KwChartSource 的 "kw"、
     * id 去掉 srcUrl| 前缀只留裸榜 id，详情因此路由到 KwChartSource.chartTracks（kbangserver 直连），
     * 不再走插件 getTopListDetail；榜名/封面/条目原文原样保留，列表页与首页榜单卡按名匹配不受影响。
     */
    @Override
    public List<MusicChart> charts(MusicSite site) throws Exception {
        if (!isKuwoSite(site)) return Collections.emptyList();
        FreeJsBridge b = MusicSourceManager.get().bridgeOf(site);
        if (b == null) return Collections.emptyList();
        List<MusicChart> charts = b.getTopLists();
        for (MusicChart c : charts) {
            if (c == null || c.id == null) continue;
            int sep = c.id.indexOf('|');
            if (sep < 0) continue;
            c.platform = KwChartSource.PLATFORM;
            c.id = c.id.substring(sep + 1);
        }
        return charts;
    }

    /**
     * 酷我插件判据（以 MusicSourceManager.builtinSites 播种值为准）：
     * 插件自报 platform 经 FreeJsBridge 回填进 site.name，预置播种名同为「酷我音乐」；
     * srcUrl 命中预置的元力KW 地址（原文/URL 编码形）也算。两条都是精确匹配，不搞模糊。
     * 包内可见：KwChartSource 解析 _plugin 戳时复用同一判据，两处不许各写一套。
     */
    static boolean isKuwoSite(MusicSite site) {
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
        return playUrl(track, quality, false);
    }

    /** 探链口径：probe＝true 时只读地址、不记死（面板探链用），透传进桥的死检查处。 */
    @Override
    public String playUrl(MusicTrack track, int quality, boolean probe) throws Exception {
        // 单插件屏蔽平台：归属插件屏蔽了本歌内容平台时不许它取链（弹层文案承诺的
        // 「不再用于该插件的取链」此前没有任何代码兑现）；返回 null 走仓库失败收口，
        // 换源在上层按既有链继续。内容平台取不到时不拦（无平台身份可判）。
        String origin = pluginOf(track);
        String cp = com.fongmi.android.tv.music.MusicIdentity.contentPlatformOf(track);
        if (origin != null && cp != null
                && com.fongmi.android.tv.music.MusicStore.isPlatformBlocked(origin, cp)) return null;
        FreeJsBridge b = MusicSourceManager.get().bridgeForTrack(track);
        if (b == null) return null;
        long t0 = System.currentTimeMillis();
        MusicMediaSource ms = b.getMediaSource(track, MusicQuality.fromInt(quality), probe);
        // C5 真实取链耗时（最近一次口径，覆盖检测初始值）：归属取自 track.rawJson 的 _plugin
        if (ms != null && !TextUtils.isEmpty(ms.url)) {
            try {
                String plugin = com.google.gson.JsonParser.parseString(track.rawJson)
                        .getAsJsonObject().get("_plugin").getAsString();
                com.fongmi.android.tv.music.MusicStore.notePluginLatency(
                        PLATFORM, plugin, System.currentTimeMillis() - t0, false);
            } catch (Throwable ignored) {
            }
        }
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

    /**
     * lyricFallback 的 rich 版：同一条遍历，命中返回完整 MusicLyric。
     * 第 1 步漏词修：查询序列外层（与步骤 3 同款结构）——本地歌按净歌名逐级降级搜，
     * 在线歌序列只有一个原样查询，行为不动；对歌用 matchTarget 按查询换净歌名。
     * 第二遍（own != null 且第一遍无正文命中时）：只搜歌曲自己的插件——步骤 1 跳过
     * 自己插件、步骤 2 跳过整个本源时，自己插件经搜歌＋对歌的词会永远漏网；第二遍
     * 是步骤 0 本源直取的搜歌重试，不走被屏蔽插件过滤（步骤 0 本源直取亦不过滤）。
     */
    public MusicLyric lyricFallbackRich(MusicTrack track) {
        return lyricFallbackRichAtLevel(track, com.fongmi.android.tv.music.MusicRepository.MATCH_ANY);
    }

    /**
     * 待裁12：lyricFallbackRich 的级别版——手动搜词（force）时仓库按对歌级别分遍调用
     * （先 MATCH_EXACT、后 MATCH_CONTAINS），步 1 的包含级命中不许抢跑步 2 的精确遍；
     * MATCH_ANY 即原口径（matchSong 表内先精确后包含），自动路径行为不变。
     */
    public MusicLyric lyricFallbackRichAtLevel(MusicTrack track, int level) {
        if (track == null) return null;
        List<com.fongmi.android.tv.utils.LocalTrackMeta.Query> queries =
                com.fongmi.android.tv.utils.LocalTrackMeta.queries(track.title, track.artist, track.platform);
        if (queries.isEmpty()) return null;
        String own = pluginOf(track);
        MusicLyric first = lyricFallbackRichPass(track, queries, own, false, level);
        if (first != null && first.hasBodyText()) return first;
        if (own != null) {
            MusicLyric second = lyricFallbackRichPass(track, queries, own, true, level);
            if (second != null && second.hasBodyText()) return second;
            if (first == null) first = second;
        }
        return first;
    }

    /** 按对歌级别在本源内对歌：MATCH_ANY 走 matchSong 原口径（表内先精确后包含），指定级别时只认该级。 */
    private static MusicTrack matchAtLevel(MusicTrack track, String matchTitle, List<MusicTrack> candidates, int level) {
        MusicTrack target = com.fongmi.android.tv.music.MusicRepository.matchTarget(track, matchTitle);
        if (level == com.fongmi.android.tv.music.MusicRepository.MATCH_ANY) {
            return com.fongmi.android.tv.music.MusicRepository.matchSong(target, candidates);
        }
        return com.fongmi.android.tv.music.MusicRepository.matchSongAtLevel(target, candidates, level);
    }

    /** lyricFallbackRich 单遍：queries 外层 × 插件内层；onlyOwn 时只搜自己插件；level 为对歌级别（MATCH_ANY 走 matchSong 原口径）。 */
    private MusicLyric lyricFallbackRichPass(MusicTrack track,
            List<com.fongmi.android.tv.utils.LocalTrackMeta.Query> queries, String own, boolean onlyOwn, int level) {
        MusicLyric romajiOnly = null;
        try {
            for (com.fongmi.android.tv.utils.LocalTrackMeta.Query q : queries) {
                for (MusicSite site : MusicSourceManager.get().enabledSitesOfFormat(MusicSourceManager.FORMAT_FREEJS)) {
                    if (site == null || site.srcUrl == null) continue;
                    if (onlyOwn) {
                        if (!own.equals(site.srcUrl)) continue;
                    } else {
                        if (own != null && own.equals(site.srcUrl)) continue;
                        if (com.fongmi.android.tv.music.MusicStore.isPlatformBlocked(site.srcUrl, com.fongmi.android.tv.music.MusicIdentity.contentPlatformOf(track))) continue;
                    }
                    FreeJsBridge b;
                    try {
                        b = MusicSourceManager.get().bridgeOf(site);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (b == null) continue;
                    List<MusicTrack> candidates;
                    try {
                        candidates = b.search(q.keyword, 1, "music").getTracks();
                    } catch (Throwable ignored) {
                        continue;
                    }
                    MusicTrack hit = matchAtLevel(track, q.matchTitle, candidates, level);
                    if (hit == null) continue;
                    try {
                        MusicLyric l = b.getLyric(hit);
                        if (l != null && (l.sourceName == null || l.sourceName.isEmpty())) l.sourceName = site.name;
                        // 命中口径：正文或译文才立即返回（译文-only 也算，仓库
                        // normalizeRich 会把定时译文提为正文）；纯音译只留作候选，
                        // 不许截断后面的插件——它们可能有正文
                        if (l != null && l.hasBodyText()) return l;
                        if (l != null && l.hasLyric() && romajiOnly == null) romajiOnly = l;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return romajiOnly;
    }

    /**
     * lyricFallbackRich 的全量版（B2）：同一条遍历，收集全部实际命中（含纯音译），不截断。
     * 第 1 步漏词修同 lyricFallbackRich：查询序列外层＋自己插件第二遍；两遍都收集，
     * 跨查询／跨遍的同词去重在上游 addVersion（按 rawLrc），方法内不另去重。
     */
    public List<MusicLyric> lyricFallbackAllRich(MusicTrack track) {
        List<MusicLyric> out = new ArrayList<>();
        if (track == null) return out;
        List<com.fongmi.android.tv.utils.LocalTrackMeta.Query> queries =
                com.fongmi.android.tv.utils.LocalTrackMeta.queries(track.title, track.artist, track.platform);
        if (queries.isEmpty()) return out;
        String own = pluginOf(track);
        lyricFallbackAllRichPass(track, queries, own, false, out);
        if (own != null) lyricFallbackAllRichPass(track, queries, own, true, out);
        return out;
    }

    /** lyricFallbackAllRich 单遍：queries 外层 × 插件内层；onlyOwn 时只搜自己插件。 */
    private void lyricFallbackAllRichPass(MusicTrack track,
            List<com.fongmi.android.tv.utils.LocalTrackMeta.Query> queries, String own, boolean onlyOwn,
            List<MusicLyric> out) {
        try {
            for (com.fongmi.android.tv.utils.LocalTrackMeta.Query q : queries) {
                for (MusicSite site : MusicSourceManager.get().enabledSitesOfFormat(MusicSourceManager.FORMAT_FREEJS)) {
                    if (site == null || site.srcUrl == null) continue;
                    if (onlyOwn) {
                        if (!own.equals(site.srcUrl)) continue;
                    } else {
                        if (own != null && own.equals(site.srcUrl)) continue;
                        if (com.fongmi.android.tv.music.MusicStore.isPlatformBlocked(site.srcUrl, com.fongmi.android.tv.music.MusicIdentity.contentPlatformOf(track))) continue;
                    }
                    FreeJsBridge b;
                    try {
                        b = MusicSourceManager.get().bridgeOf(site);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (b == null) continue;
                    List<MusicTrack> candidates;
                    try {
                        candidates = b.search(q.keyword, 1, "music").getTracks();
                    } catch (Throwable ignored) {
                        continue;
                    }
                    MusicTrack hit = com.fongmi.android.tv.music.MusicRepository.matchSong(
                            com.fongmi.android.tv.music.MusicRepository.matchTarget(track, q.matchTitle), candidates);
                    if (hit == null) continue;
                    try {
                        MusicLyric l = b.getLyric(hit);
                        if (l != null && l.hasLyric()) {
                            if (l.sourceName == null || l.sourceName.isEmpty()) l.sourceName = site.name;
                            out.add(l);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** lyricFromLyricPluginsRich 的全量版（B2）：同一条直查，收集全部实际命中（含纯音译），不截断 */
    public List<MusicLyric> lyricPluginsAllRich(MusicTrack track) {
        List<MusicLyric> out = new ArrayList<>();
        if (track == null) return out;
        List<com.fongmi.android.tv.utils.LocalTrackMeta.Query> queries =
                com.fongmi.android.tv.utils.LocalTrackMeta.queries(track.title, track.artist, track.platform);
        if (queries.isEmpty()) return out;
        try {
            for (com.fongmi.android.tv.utils.LocalTrackMeta.Query q : queries) {
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
                        List<MusicTrack> candidates = b.search(q.keyword, 1, "lyric").getTracks();
                        MusicTrack hit = com.fongmi.android.tv.music.MusicRepository.matchSong(
                                com.fongmi.android.tv.music.MusicRepository.matchTarget(track, q.matchTitle), candidates);
                        if (hit == null) continue;
                        MusicLyric l = b.getLyric(hit);
                        if (l != null && l.hasLyric()) {
                            if (l.sourceName == null || l.sourceName.isEmpty()) l.sourceName = site.name;
                            out.add(l);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** lyricFromLyricPlugins 的 rich 版：同一条直查，命中返回完整 MusicLyric */
    public MusicLyric lyricFromLyricPluginsRich(MusicTrack track) {
        if (track == null) return null;
        List<com.fongmi.android.tv.utils.LocalTrackMeta.Query> queries =
                com.fongmi.android.tv.utils.LocalTrackMeta.queries(track.title, track.artist, track.platform);
        if (queries.isEmpty()) return null;
        MusicLyric romajiOnly = null;
        try {
            for (com.fongmi.android.tv.utils.LocalTrackMeta.Query q : queries) {
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
                        List<MusicTrack> candidates = b.search(q.keyword, 1, "lyric").getTracks();
                        MusicTrack hit = com.fongmi.android.tv.music.MusicRepository.matchSong(
                                com.fongmi.android.tv.music.MusicRepository.matchTarget(track, q.matchTitle), candidates);
                        if (hit == null) continue;
                        MusicLyric l = b.getLyric(hit);
                        if (l != null && (l.sourceName == null || l.sourceName.isEmpty())) l.sourceName = site.name;
                        // 同 lyricFallbackRich：正文/译文立即返回，纯音译只留候选不截断
                        if (l != null && l.hasBodyText()) return l;
                        if (l != null && l.hasLyric() && romajiOnly == null) romajiOnly = l;
                    } catch (Throwable ignored) {
                    }
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
    /**
     * 歌词兜底：按启用顺序遍历其它插件（跳过歌曲自己的插件），
     * 用歌名+歌手搜到同一首歌再取它的歌词；复用桥已有的 search/getLyric 通道。
     * 第 1 步漏词修同 lyricFallbackRich：查询序列外层＋自己插件第二遍（第一遍无命中时）。
     */
    public String lyricFallback(MusicTrack track) {
        return lyricFallbackAtLevel(track, com.fongmi.android.tv.music.MusicRepository.MATCH_ANY);
    }

    /** 待裁12：lyricFallback 的级别版（口径同 lyricFallbackRichAtLevel）。 */
    public String lyricFallbackAtLevel(MusicTrack track, int level) {
        if (track == null) return null;
        List<com.fongmi.android.tv.utils.LocalTrackMeta.Query> queries =
                com.fongmi.android.tv.utils.LocalTrackMeta.queries(track.title, track.artist, track.platform);
        if (queries.isEmpty()) return null;
        String own = pluginOf(track);
        String hit = lyricFallbackPass(track, queries, own, false, level);
        if (hit != null) return hit;
        if (own != null) return lyricFallbackPass(track, queries, own, true, level);
        return null;
    }

    /** lyricFallback 单遍：queries 外层 × 插件内层；onlyOwn 时只搜自己插件；level 为对歌级别（MATCH_ANY 走 matchSong 原口径）。 */
    private String lyricFallbackPass(MusicTrack track,
            List<com.fongmi.android.tv.utils.LocalTrackMeta.Query> queries, String own, boolean onlyOwn, int level) {
        try {
            for (com.fongmi.android.tv.utils.LocalTrackMeta.Query q : queries) {
                for (MusicSite site : MusicSourceManager.get().enabledSitesOfFormat(MusicSourceManager.FORMAT_FREEJS)) {
                    if (site == null || site.srcUrl == null) continue;
                    if (onlyOwn) {
                        if (!own.equals(site.srcUrl)) continue;
                    } else {
                        if (own != null && own.equals(site.srcUrl)) continue;
                        if (com.fongmi.android.tv.music.MusicStore.isPlatformBlocked(site.srcUrl, com.fongmi.android.tv.music.MusicIdentity.contentPlatformOf(track))) continue;
                    }
                    FreeJsBridge b;
                    try {
                        b = MusicSourceManager.get().bridgeOf(site);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (b == null) continue;
                    List<MusicTrack> candidates;
                    try {
                        candidates = b.search(q.keyword, 1, "music").getTracks();
                    } catch (Throwable ignored) {
                        continue;
                    }
                    MusicTrack hit = matchAtLevel(track, q.matchTitle, candidates, level);
                    if (hit == null) continue;
                    try {
                        MusicLyric l = b.getLyric(hit);
                        if (l != null && l.rawLrc != null && !l.rawLrc.trim().isEmpty()) return l.rawLrc;
                    } catch (Throwable ignored) {
                    }
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
        List<com.fongmi.android.tv.utils.LocalTrackMeta.Query> queries =
                com.fongmi.android.tv.utils.LocalTrackMeta.queries(track.title, track.artist, track.platform);
        if (queries.isEmpty()) return null;
        try {
            for (com.fongmi.android.tv.utils.LocalTrackMeta.Query q : queries) {
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
                        List<MusicTrack> candidates = b.search(q.keyword, 1, "lyric").getTracks();
                        MusicTrack hit = com.fongmi.android.tv.music.MusicRepository.matchSong(
                                com.fongmi.android.tv.music.MusicRepository.matchTarget(track, q.matchTitle), candidates);
                        if (hit == null) continue;
                        MusicLyric l = b.getLyric(hit);
                        if (l != null && l.rawLrc != null && !l.rawLrc.trim().isEmpty()) return l.rawLrc;
                    } catch (Throwable ignored) {
                    }
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
