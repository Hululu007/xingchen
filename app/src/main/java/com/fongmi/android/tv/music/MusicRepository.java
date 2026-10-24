package com.fongmi.android.tv.music;

import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicMediaSource;
import com.fongmi.android.tv.bean.music.MusicQuality;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.bean.music.LyricVersion;
import com.fongmi.android.tv.api.music.AmllMusicLyricSource;
import com.fongmi.android.tv.api.music.CfLyricClient;
import com.fongmi.android.tv.api.music.FreeJsBridge;
import com.fongmi.android.tv.api.music.FreeJsMusicSource;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.api.music.PlatformWordLyricSource;
import com.fongmi.android.tv.api.music.LxKeyIssue;
import com.fongmi.android.tv.api.music.LxMusicSource;
import com.fongmi.android.tv.api.music.KwChartSource;
import com.fongmi.android.tv.api.music.QqChartSource;
import com.fongmi.android.tv.player.lyrics.KuwoClient;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsRepository;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.player.lyrics.LyricsRequest;
import com.fongmi.android.tv.player.lyrics.LyricsResult;
import com.fongmi.android.tv.utils.LocalTrackMeta;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 音乐源门面：播放器/UI 只调这里，内部再找源实现。
 * 一期源抽象落地后，真源向这里 register 即可。
 */
public class MusicRepository {

    private static final List<MusicSource> SOURCES = new CopyOnWriteArrayList<>();
    private static boolean debugTried;
    /**
     * P-3d：同曲一次取链内的死指派插件／死通道缓存（单条当前取链，不按歌建表、不跨歌保留）。
     * 只拦得住 Repository 层指派单试这一条通道：指派插件失败后，本次取链的降档循环不再重进它，
     * 直接走源内取链；源内死标记由源自己管（Lx／FreeJs 各有同口径死集）。
     * 第二波堵-4：死键＝插件＋档位（srcUrl＋MusicStore.QUALITY_* 档值，与源内插件＋档位同口径，
     * 降档顺序与黑名单阈值不动）——某档死掉不许连坐其它档；换歌／新一次取链开始时清掉
     * （服务 prepareTrack 入口调 clearDeadPlayUrlAttempt，本方法同时清仓库层与两源内死集，
     * 不许只清仓库层）；并发取链下单缓存以后到者为准，不建多歌表防串歌。
     */
    private static final Object DEAD_PLAY_URL_LOCK = new Object();
    private static String deadPlayUrlTrackKey = "";
    private static final Set<String> deadAssignedPlayUrlPlugins = new HashSet<>();

    /** P-3d＋第二波堵-4：新一次取链开始时清掉上次取链的死插件记录（仓库层＋Lx＋FreeJs 三处一起清）。 */
    public static void clearDeadPlayUrlAttempt() {
        synchronized (DEAD_PLAY_URL_LOCK) {
            deadPlayUrlTrackKey = "";
            deadAssignedPlayUrlPlugins.clear();
        }
        LxMusicSource.clearDeadPlayUrlAttempt();
        FreeJsBridge.clearDeadPlayUrlAttempt();
    }

    private static String deadAssignedKey(String srcUrl, int quality) {
        return srcUrl + "|" + quality;
    }

    private static boolean isDeadAssigned(String trackKey, String srcUrl, int quality) {
        synchronized (DEAD_PLAY_URL_LOCK) {
            if (trackKey == null || srcUrl == null) return false;
            if (!trackKey.equals(deadPlayUrlTrackKey)) {
                deadPlayUrlTrackKey = trackKey;
                deadAssignedPlayUrlPlugins.clear();
                return false;
            }
            return deadAssignedPlayUrlPlugins.contains(deadAssignedKey(srcUrl, quality));
        }
    }

    private static void markDeadAssigned(String trackKey, String srcUrl, int quality) {
        synchronized (DEAD_PLAY_URL_LOCK) {
            if (trackKey == null || srcUrl == null) return;
            if (!trackKey.equals(deadPlayUrlTrackKey)) {
                deadPlayUrlTrackKey = trackKey;
                deadAssignedPlayUrlPlugins.clear();
            }
            deadAssignedPlayUrlPlugins.add(deadAssignedKey(srcUrl, quality));
        }
    }

    static {
        // 本地音乐源常驻：已下载歌曲（platform="local"）走文件直链，播放链不动
        register(new LocalMusicSource());
        // 一期 FreeJS 真源：MusicFree 插件桥（search/取链/歌词/榜单），常驻注册
        register(new FreeJsMusicSource());
        // 落雪真源：壳子平台搜索＋落雪脚本地址解析（musicUrl/lyric），常驻注册
        register(new LxMusicSource());
        // py 调用桥：已装的 py 点播音乐源递歌单进音乐播放器（跟阅读一样的调用口径）
        register(new PyMusicSource());
        // 海阔音乐路由桥：海阔小程序里识别为音乐的条目进音乐播放器时取链用，不进源列表
        register(new HkMusicSource());
        // 设备本地音乐（platform="device"）：取链/同目录 .lrc 歌词；列表走 DeviceMusicStore 扫描
        register(new DeviceMusicSource());
        // QQ 榜单壳内源（platform="qq"）：只供榜单（QQ 巅峰榜直连），歌拼落雪 tx 形态走现成链播
        register(new QqChartSource());
        // 酷我榜单壳内源（platform="kw"）：只供榜单详情直连；列表仍走插件，详情路由到这里
        register(new KwChartSource());
        // 网盘音乐源（platform="pan"）常驻：只在网盘页打开时注册的话，进程重启后从
        // 通知／系统媒体面续播网盘歌时按 platform 查不到 pan，断点续播断链；静态块常驻
        // 后任何入口都能找到。CloudMusicSource.ensureRegistered() 仍可保留（register 按
        // 平台去重，重复注册无害）。
        register(new CloudMusicSource());
    }

    public static void register(MusicSource source) {
        if (source == null) return;
        for (MusicSource s : SOURCES) {
            if (s.platform().equals(source.platform())) return;
        }
        SOURCES.add(source);
    }

    /**
     * 尝试注册 debug 假源（只在 debug 包存在该类时生效，release 包无此文件）。
     * TODO:一期真源落地后删掉这个方法及 debug 源文件。
     */
    public static synchronized void ensureDebugFake() {
        if (debugTried) return;
        debugTried = true;
        try {
            Class<?> c = Class.forName("com.fongmi.android.tv.music.FakeMusicSource");
            register((MusicSource) c.getDeclaredConstructor().newInstance());
        } catch (Throwable ignored) {
        }
    }

    private static MusicSource find(String platform) {
        if (platform == null) return null;
        for (MusicSource s : SOURCES) {
            if (platform.equals(s.platform())) return s;
        }
        return null;
    }

    public static boolean hasSource() {
        return !SOURCES.isEmpty();
    }

    public static List<MusicSite> sites() {
        List<MusicSite> all = new ArrayList<>();
        for (MusicSource s : SOURCES) {
            try {
                all.addAll(s.sites());
            } catch (Throwable t) {
                SpiderDebug.log("music", "sites error platform=%s %s", s.platform(), t.getMessage());
            }
        }
        return all;
    }

    public static List<MusicTrack> search(String keyword) {
        List<MusicTrack> all = new ArrayList<>();
        for (MusicSource s : SOURCES) {
            try {
                all.addAll(s.search(keyword));
            } catch (Throwable t) {
                SpiderDebug.log("music", "search error platform=%s %s", s.platform(), t.getMessage());
            }
        }
        return all;
    }

    /**
     * 搜索页横排的子源：只有真实支持搜歌（type=music）的源/插件进排。
     * 只提供歌词的插件（声明里只有 lyric）不进排，歌词链路不受影响。
     */
    public static List<MusicSite> searchableSites() {
        List<MusicSite> all = new ArrayList<>();
        for (MusicSource s : SOURCES) {
            try {
                all.addAll(s.searchableSites());
            } catch (Throwable t) {
                SpiderDebug.log("music", "searchable sites error platform=%s %s", s.platform(), t.getMessage());
            }
        }
        return all;
    }

    /**
     * 按单个子源搜歌：结果只归这个源。失败直接抛出，由搜索页按源单独提示，
     * 不许一个源的失败拖垮整页（阻塞，请在子线程调）。
     */
    public static List<MusicTrack> searchSite(MusicSite site, String keyword) throws Exception {
        if (site == null) return Collections.emptyList();
        MusicSource s = find(site.platform);
        if (s == null) return Collections.emptyList();
        List<MusicTrack> r = s.searchBySite(site, keyword);
        return r == null ? Collections.emptyList() : r;
    }

    public static List<MusicTrack> tracks(MusicSite site) {
        if (site == null) return Collections.emptyList();
        MusicSource s = find(site.platform);
        if (s == null) return Collections.emptyList();
        try {
            return s.tracks(site);
        } catch (Throwable t) {
            SpiderDebug.log("music", "tracks error platform=%s %s", site.platform, t.getMessage());
            return Collections.emptyList();
        }
    }

    public static List<MusicChart> charts(MusicSite site) {
        if (site == null) return Collections.emptyList();
        MusicSource s = find(site.platform);
        if (s == null) return Collections.emptyList();
        try {
            return s.charts(site);
        } catch (Throwable t) {
            SpiderDebug.log("music", "charts error platform=%s %s", site.platform, t.getMessage());
            return Collections.emptyList();
        }
    }

    public static List<MusicTrack> chartTracks(MusicChart chart) {
        if (chart == null) return Collections.emptyList();
        MusicSource s = find(chart.platform);
        if (s == null) return Collections.emptyList();
        try {
            return s.chartTracks(chart);
        } catch (Throwable t) {
            SpiderDebug.log("music", "chartTracks error platform=%s %s", chart.platform, t.getMessage());
            return Collections.emptyList();
        }
    }

    /** 酷我榜单详情首屏：kbangserver 直连快出文字＋时长（无封面），仅 kw 源有效，其余返回空；详情页先渲染它，封面后补 */
    public static List<MusicTrack> chartTracksDirect(MusicChart chart) {
        if (chart == null) return Collections.emptyList();
        MusicSource s = find(chart.platform);
        if (!(s instanceof KwChartSource)) return Collections.emptyList();
        try {
            return ((KwChartSource) s).directTracks(chart.id);
        } catch (Throwable t) {
            SpiderDebug.log("music", "chartTracksDirect error platform=%s %s", chart.platform, t.getMessage());
            return Collections.emptyList();
        }
    }

    /** 酷我榜单详情分片预启动：只提交 Worker 分片请求不等待，供详情页直连前先调（与直连并行），仅 kw 源有效 */
    public static void startKwChartSlices(MusicChart chart) {
        if (chart == null) return;
        MusicSource s = find(chart.platform);
        if (!(s instanceof KwChartSource)) return;
        try {
            ((KwChartSource) s).startSlices(chart.id);
        } catch (Throwable ignored) {
        }
    }

    /** 酷我榜单详情封面陆续补：两片各独立等待、先回先回调（互不等），仅 kw 源有效；本方法不阻塞，回调里贴 UI 线程 */
    public static void fetchKwChartCovers(MusicChart chart, KwChartSource.CoverListener listener) {
        if (chart == null || listener == null) return;
        MusicSource s = find(chart.platform);
        if (!(s instanceof KwChartSource)) return;
        try {
            ((KwChartSource) s).fetchCovers(chart.id, listener);
        } catch (Throwable t) {
            SpiderDebug.log("music", "fetchKwChartCovers error platform=%s %s", chart.platform, t.getMessage());
        }
    }

    /** 酷我榜单列表页后台预热：不阻塞，只发请求把 Worker 图库热好（必须手机侧发才热到用户 colo），仅 kw 源有效 */
    public static void prewarmKwChart(MusicChart chart) {
        if (chart == null) return;
        MusicSource s = find(chart.platform);
        if (!(s instanceof KwChartSource)) return;
        try {
            ((KwChartSource) s).prewarm(chart.id);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 清理轮 R-C7：取链入口消费播放路由（MusicStore 取链策略＋平台-插件指派表，
     * 键名以 MusicStore 为准：getLinkPolicy／getPlatformPlugin，策略值 fallback／pluginOnly、
     * 默认 fallback；指派值＝插件 srcUrl，空＝自动）。
     * 指派只对 freejs／lx 两路生效（其余源取链不经过插件桥，指派键原样走现有路径）：
     * 指派插件未安装／已停用／格式与本路不符＝“不可用”，不论策略都回落现有路径，
     * 不失败、不静默丢歌；指派插件可用时先试它一个——试出链直接返回，试空／试错时
     * fallback 策略回落现有路径（成功率优先），pluginOnly 策略停住返回 null
     * （仅插件、不跨插件换源，符合插件页“仅通过第三方插件取链”文案）。
     * 密钥问题（LxKeyIssue）两种策略都不回落，照旧重抛给服务弹提示。
     * FreeJS 指派与本歌同源插件时跳过单试（现有路径本来就是该桥，免得重复取链）；
     * FreeJS 跨插件单试以本歌原条目 _item 直传指派桥（用户显式指派的后果，不做
     * 二次对歌校验，现有取链亦不做）。其余取链行为一字不动：歌词链路不在此方法内。
     */
    public static String playUrl(MusicTrack track, int quality) {
        return playUrl(track, quality, false);
    }

    /** 第三波 A4：manualOriginFallback＝true 时，仅插件策略（pluginOnly）下指派失败不再直接停住，
     * 对手动点的歌回落一次原插件（自动推进／预加载／下载走 2 参口径，策略原样）。 */
    public static String playUrl(MusicTrack track, int quality, boolean manualOriginFallback) {
        return playUrl(track, quality, manualOriginFallback, false);
    }

    /**
     * 第二波堵-4 探链口径：probe＝true 时只读地址、不记死（音质面板逐档算大小走这条）。
     * 探链仍尊重已死标记（不替真实取链翻案），只不产生新标记；降档顺序、黑名单阈值不动。
     */
    public static String playUrl(MusicTrack track, int quality, boolean manualOriginFallback, boolean probe) {
        if (track == null) return null;
        MusicSource s = find(track.platform);
        if (s == null) return null;
        String assigned = null;
        try {
            assigned = MusicStore.getPlatformPlugin(track.platform);
        } catch (Throwable ignored) {
        }
        if (assigned != null) {
            MusicSite site = assignedSite(assigned);
            boolean available = site != null && site.enabled
                    && ((FreeJsMusicSource.PLATFORM.equals(track.platform) && !MusicSourceManager.isLx(site))
                    || (LxMusicSource.PLATFORM.equals(track.platform) && MusicSourceManager.isLx(site)));
            if (!available) {
                SpiderDebug.log("music", "playUrl assigned unavailable platform=%s srcUrl=%s fallback",
                        track.platform, assigned);
            } else if (isDeadAssigned(MusicQueue.trackKey(track), site.srcUrl, quality)) {
                // P-3d：本次取链内已死过的指派插件不再重进单试；仅插件策略下直接判空（原口径），
                // 否则跳过单试、直接走源内取链（源内遍历不在本层拦，见字段注释）。
                boolean pluginOnly = false;
                try {
                    pluginOnly = MusicStore.LINK_POLICY_PLUGIN_ONLY.equals(MusicStore.getLinkPolicy());
                } catch (Throwable ignored) {
                }
                if (pluginOnly && !manualOriginFallback) return null;
                SpiderDebug.log("music", "playUrl assigned dead skipped platform=%s srcUrl=%s",
                        track.platform, assigned);
            } else if (!isSameOriginPlugin(track, site)) {
                boolean pluginOnly = false;
                try {
                    pluginOnly = MusicStore.LINK_POLICY_PLUGIN_ONLY.equals(MusicStore.getLinkPolicy());
                } catch (Throwable ignored) {
                }
                try {
                    String url = assignedPlayUrl(track, quality, site, probe);
                    if (url != null && !url.isEmpty()) return url;
                    if (!probe) markDeadAssigned(MusicQueue.trackKey(track), site.srcUrl, quality);
                    if (pluginOnly && !manualOriginFallback) return null;
                    if (pluginOnly) SpiderDebug.log("music",
                            "playUrl assigned failed, manual fallback to origin platform=%s", track.platform);
                } catch (Throwable t) {
                    rethrowKeyIssue(t);
                    if (!probe) markDeadAssigned(MusicQueue.trackKey(track), site.srcUrl, quality);
                    SpiderDebug.log("music", "playUrl assigned error platform=%s srcUrl=%s %s",
                            track.platform, assigned, t.getMessage());
                    if (pluginOnly && !manualOriginFallback) return null;
                }
            }
            // 同源跳过：现有路径本来就是该桥，不进单试／不触发 pluginOnly 停住，直接走现有路径
        }
        try {
            return s.playUrl(track, quality, probe);
        } catch (Throwable t) {
            rethrowKeyIssue(t);
            SpiderDebug.log("music", "playUrl error platform=%s id=%s %s", track.platform, track.id, t.getMessage());
            return null;
        }
    }

    /** 指派表里的 srcUrl 在全部插件记录里找（启用态另判；已删记录找不到＝不可用） */
    private static MusicSite assignedSite(String srcUrl) {
        try {
            for (MusicSite site : MusicSourceManager.get().listSites()) {
                if (site != null && srcUrl.equals(site.srcUrl)) return site;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 指派插件就是本歌原插件（仅 freejs 有原插件概念）时跳过单试 */
    private static boolean isSameOriginPlugin(MusicTrack track, MusicSite site) {
        return FreeJsMusicSource.PLATFORM.equals(track.platform)
                && site.srcUrl != null && site.srcUrl.equals(originPluginOf(track));
    }

    /** 单插件取链：freejs 走指派桥 getMediaSource；lx 走单脚本 playUrlWithPlugin；probe 透传两处死检查 */
    private static String assignedPlayUrl(MusicTrack track, int quality, MusicSite site, boolean probe) throws Exception {
        if (FreeJsMusicSource.PLATFORM.equals(track.platform)) {
            // 单插件屏蔽平台：指派插件屏蔽了本歌内容平台同样不许取链（与源内取链同口径）
            String cp = MusicIdentity.contentPlatformOf(track);
            if (cp != null && MusicStore.isPlatformBlocked(site.srcUrl, cp)) return null;
            FreeJsBridge b = MusicSourceManager.get().bridgeOf(site);
            if (b == null) return null;
            MusicMediaSource ms = b.getMediaSource(track, MusicQuality.fromInt(quality), probe);
            return ms == null ? null : ms.url;
        }
        if (LxMusicSource.PLATFORM.equals(track.platform)) {
            return new LxMusicSource().playUrlWithPlugin(track, quality, site.srcUrl, probe);
        }
        return null;
    }

    /** FreeJS 本歌原插件 srcUrl（track.rawJson 的 _plugin），取不到返回 null */
    private static String originPluginOf(MusicTrack track) {
        try {
            if (track.rawJson == null || track.rawJson.isEmpty()) return null;
            return com.google.gson.JsonParser.parseString(track.rawJson)
                    .getAsJsonObject().get("_plugin").getAsString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String lyric(MusicTrack track) {
        return lyric(track, false);
    }

    /** force=true 时无视「歌词缺失自动搜索」开关也要跨插件找（播放页手动「搜索歌词」用） */
    public static String lyric(MusicTrack track, boolean force) {
        if (track == null) return null;
        MusicSource s = find(track.platform);
        if (s == null) return null;
        String own = null;
        try {
            own = s.lyric(track);
        } catch (Throwable t) {
            rethrowKeyIssue(t);
            SpiderDebug.log("music", "lyric error platform=%s %s", track.platform, t.getMessage());
        }
        if (own != null && !own.trim().isEmpty()) return own;

        // 「歌词缺失自动搜索」：当前源没词（或报错）时，按源顺序拿其它插件/源用歌名+歌手搜歌词补上
        // 本地歌查询序列按净歌名逐级降级（文件名后缀／发行组后缀／占位歌手已清洗）；在线歌序列只有一个原样查询，行为不动
        if (!force && !MusicStore.isLyricFallback()) return null;
        List<LocalTrackMeta.Query> queries = LocalTrackMeta.queries(track.title, track.artist, track.platform);
        if (queries.isEmpty()) return null;

        // 1. 本源内部的其它插件（FreeJS 多插件，启用顺序）
        if (s instanceof FreeJsMusicSource) {
            try {
                String fb = ((FreeJsMusicSource) s).lyricFallback(track);
                if (fb != null && !fb.trim().isEmpty()) return fb;
            } catch (Throwable t) {
                SpiderDebug.log("music", "lyric fallback error %s", t.getMessage());
            }
        }
        // 2. 其它源（本地源跳过，它不提供歌词）：查询序列外层（净查询先问遍各源，再降级）
        for (LocalTrackMeta.Query q : queries) {
            for (MusicSource other : SOURCES) {
                if (other == s || LocalMusicSource.PLATFORM.equals(other.platform())) continue;
                try {
                    MusicTrack hit = matchSong(matchTarget(track, q.matchTitle), other.search(q.keyword));
                    if (hit == null) continue;
                    String lrc = other.lyric(hit);
                    if (lrc != null && !lrc.trim().isEmpty()) return lrc;
                } catch (Throwable t) {
                    rethrowKeyIssue(t);
                    SpiderDebug.log("music", "lyric alt search error platform=%s %s", other.platform(), t.getMessage());
                }
            }
        }
        // 3. 歌词型插件直查：歌词网/歌词千寻的 search 只认 type="lyric"，
        //    前两步（源级搜歌）永远问不到它们；本地/设备歌的词主要靠这条。
        //    放在最后，在线歌原有取词优先级不动。
        for (MusicSource other : SOURCES) {
            if (!(other instanceof FreeJsMusicSource)) continue;
            try {
                String lrc = ((FreeJsMusicSource) other).lyricFromLyricPlugins(track);
                if (lrc != null && !lrc.trim().isEmpty()) return lrc;
            } catch (Throwable t) {
                SpiderDebug.log("music", "lyric plugin search error %s", t.getMessage());
            }
        }
        return null;
    }

    public static MusicLyric lyricRich(MusicTrack track) {
        return lyricRich(track, false);
    }

    /**
     * lyric() 的 rich 版：按在线歌词优先级（MusicStore.getLyricPriority）选版。
     * 默认 wordByWord＝旧行为：本源带词级时间直接返回；本源只有行级时不立即返回，
     * 继续往后找词级（同源其它插件→跨源→歌词型插件→Worker 聚合（主来源，已接线：
     * cfLyricWord）→平台直连回落→AMLL 兜底），全程没词级才回落到首个行级命中。
     * 逐行／AMLL／跟随来源模式下，旧“见词即返”不再截断链：各步命中只进 LyricPick 桶，
     * 首选命中才立即返回，其余走完链后按优先级次序取；始终保留回落链，原来有词的歌
     * 绝不因此变无词。Worker／平台直连两步仍只出词级版（回包口径不动），逐行／AMLL
     * 模式下它们只记词级回落桶，不单独成首选。
     * 「歌词缺失自动搜索」开关（force 例外）与 AMLL 门控口径不变；FreeJS/落雪源走各自的
     * rich 通道保住译/音译/逐字正文，其它源只有纯文本，包成只有正文的 MusicLyric。
     */
    /**
     * L10 版本 key（持久化／恢复共用同一算法）：档位＋正文内容哈希；正文空时用译文＋音译拼接哈希
     * （与 addVersion 去重口径同源）。来源不进 key：同一正文换源聚合去重后只留首个版本，
     * 按来源硬匹配会把同文版本判成恢复失败；来源单独存 MusicStore，仅作展示／排障。
     * 后波播放页 applyLyricVersion 点选时调 saveManualLyricChoice 存，点选即生效、下次同曲恢复。
     */
    public static String lyricVersionKey(LyricVersion version) {
        if (version == null || version.getLyric() == null) return "";
        MusicLyric lyric = version.getLyric();
        String raw = lyric.rawLrc == null ? "" : lyric.rawLrc.trim();
        if (raw.isEmpty()) {
            raw = (lyric.translation == null ? "" : lyric.translation.trim())
                    + "|" + (lyric.romanization == null ? "" : lyric.romanization.trim());
        }
        if (raw.isEmpty()) return "";
        return version.kindLabel() + "#" + raw.hashCode();
    }

    /** L10：播放页点选版本时存（歌曲 key→版本 key／来源），不要复用播放页内存 currentLyricKey。 */
    public static void saveManualLyricChoice(MusicTrack track, LyricVersion version) {
        if (track == null || version == null) return;
        String key = lyricVersionKey(version);
        if (key.isEmpty()) return;
        MusicStore.setManualLyricChoice(MusicQueue.trackKey(track), key, version.getSource());
    }

    public static MusicLyric lyricRich(MusicTrack track, boolean force) {
        if (track == null) return null;
        // L10：非手动重搜时先试恢复上次手动选的版本（瓜子 restoreManualLyricChoice 口径）。
        // 恢复用全量聚合（lyricRichAllDetailed）匹配版本 key，命中即直接返回该版，不走快路径优先级；
        // 完整聚合里找不到才清除存值并通知一次“已失效”再走快路径——不许静默回落首版。
        // 不完整聚合里找不到时不弹“已失效”、不清除存值，只走快路径（见 B8 注释）。
        // 手动重搜（force）是用户要找新版，不恢复旧选；聚合本身抛错（网络等）时不弹“已失效”，
        // 只记日志走快路径，免得把网络失败说成版本失效；密钥问题照旧重抛给播放页弹密钥提示。
        if (!force) {
            String stored = MusicStore.getManualLyricChoice(MusicQueue.trackKey(track));
            String wantKey = MusicStore.manualLyricChoiceKey(stored);
            if (!wantKey.isEmpty()) {
                try {
                    // B8：恢复只认完整聚合——不完整（某步 transient 失败／歌词缺失开关关导致提前截断）
                    // 时不弹“已失效”，只走快路径，且不清除存值（下次播同一首歌可再试恢复）；
                    // 完整聚合确认缺失后才清除存值并提示一次（清除后下次播不再重复提示）。
                    LyricRichAllResult agg = lyricRichAllDetailed(track, false);
                    for (LyricVersion v : agg.versions) {
                        if (wantKey.equals(lyricVersionKey(v))) return v.getLyric();
                    }
                    if (agg.complete) {
                        MusicStore.clearManualLyricChoice(MusicQueue.trackKey(track));
                        Notify.show("手动选择的歌词版本已失效，已按默认重新取词");
                    }
                } catch (Throwable t) {
                    rethrowKeyIssue(t);
                    SpiderDebug.log("music", "manual lyric restore error %s", t.getMessage());
                }
            }
        }
        MusicSource s = find(track.platform);
        if (s == null) return null;
        LyricPick pick = new LyricPick(MusicStore.getLyricPriority());
        MusicLyric own = null;
        try {
            own = normalizeRich(richOf(s, track));
        } catch (Throwable t) {
            rethrowKeyIssue(t);
            SpiderDebug.log("music", "lyricRich error platform=%s %s", track.platform, t.getMessage());
        }
        MusicLyric hit = pick.note(own);
        if (hit != null) return hit;

        if (!force && !MusicStore.isLyricFallback()) return own != null && own.hasLyric() ? own : null;
        List<LocalTrackMeta.Query> queries = LocalTrackMeta.queries(track.title, track.artist, track.platform);
        if (queries.isEmpty()) return own != null && own.hasLyric() ? own : null;

        // 1. 本源内部的其它插件（FreeJS 多插件，启用顺序）
        if (s instanceof FreeJsMusicSource) {
            try {
                MusicLyric fb = normalizeRich(((FreeJsMusicSource) s).lyricFallbackRich(track));
                hit = pick.note(fb);
                if (hit != null) return hit;
            } catch (Throwable t) {
                SpiderDebug.log("music", "lyricRich fallback error %s", t.getMessage());
            }
        }
        // 2. 其它源（本地源跳过，它不提供歌词）：查询序列外层（净查询先问遍各源，再降级）
        for (LocalTrackMeta.Query q : queries) {
            for (MusicSource other : SOURCES) {
                if (other == s || LocalMusicSource.PLATFORM.equals(other.platform())) continue;
                try {
                    MusicTrack hitTrack = matchSong(matchTarget(track, q.matchTitle), other.search(q.keyword));
                    if (hitTrack == null) continue;
                    MusicLyric lrc = normalizeRich(richOf(other, hitTrack));
                    hit = pick.note(lrc);
                    if (hit != null) return hit;
                } catch (Throwable t) {
                    rethrowKeyIssue(t);
                    SpiderDebug.log("music", "lyricRich alt search error platform=%s %s", other.platform(), t.getMessage());
                }
            }
        }
        // 3. 歌词型插件直查（与 lyric() 同一步、同口径）
        for (MusicSource other : SOURCES) {
            if (!(other instanceof FreeJsMusicSource)) continue;
            try {
                MusicLyric lrc = normalizeRich(((FreeJsMusicSource) other).lyricFromLyricPluginsRich(track));
                hit = pick.note(lrc);
                if (hit != null) return hit;
            } catch (Throwable t) {
                SpiderDebug.log("music", "lyricRich plugin search error %s", t.getMessage());
            }
        }
        // 4. Worker 聚合（主来源，已接线）：cfLyricWord 只认本地解析逐字，
        // 弱网／超时回空，不阻塞回落；回包口径不动。
        MusicLyric cf = cfLyricWord(track);
        hit = pick.note(cf);
        if (hit != null) return hit;
        // 5. 平台直连回落（Worker 不可用时才走：酷狗 KRC → QQ QRC → 网易云 YRC）：只认词级，行级不截断
        try {
            MusicLyric platform = new PlatformWordLyricSource().lyricRich(track);
            hit = pick.note(platform);
            if (hit != null) return hit;
        } catch (Throwable t) {
            SpiderDebug.log("music", "lyricRich platform word error %s", t.getMessage());
        }
        // 5.5 酷我行级直连（lrclist 崩的宿主侧根治）：元力 KW 插件 getLyric 在酷我接口回空时
        // 对 null 读 lrclist 抛未处理拒绝，宿主改不了远端混淆插件；本步用宿主自有 KuwoClient
        // （空数据已判空回空串，绝不崩）按歌名直取行级词，记进 pick 的行级／首版桶——
        // 默认逐字模式下不截断链（词版仍优先），最终 pick 无词版时回落本版，歌不再无词可显。
        try {
            hit = pick.note(kuwoLineLyric(track));
            if (hit != null) return hit;
        } catch (Throwable t) {
            SpiderDebug.log("music", "lyricRich kuwo line error %s", t.getMessage());
        }
        // 6. AMLL（B8）：最后兜底，AMLL 档带档位元数据；无命中返回空，不冒充
        // S6 门控：AMLL 开关关时跳过本步；默认开，行为与原来一致
        if (MusicStore.isAmllEnabled()) {
            try {
                MusicLyric amll = normalizeRich(new AmllMusicLyricSource().lyricRich(track));
                hit = pick.note(amll);
                if (hit != null) return hit;
            } catch (Throwable t) {
                SpiderDebug.log("music", "lyricRich amll error %s", t.getMessage());
            }
        }
        return pick.pick(own);
    }

    /**
     * Worker 聚合接线缝（主来源，已接线）。
     * 只把 Worker 回包映射为 MusicLyric（rawLrc 保留词级时间原文，译／音译原样带上），
     * 不改 Worker 回包口径，不用回包 kind 宣称定档位；快路径只认本地解析：
     * 任一行带词时间才算逐字命中，否则返回空，由直连／AMLL 回落接手。
     * Worker 不可用（弱网／超时／非 200）时 CfLyricClient.fetch 内部已吞错回空，
     * 本缝再兜一层 try，绝不抛错阻塞回落链。
     */
    private static MusicLyric cfLyricWord(MusicTrack track) {
        try {
            CfLyricClient.CfResponse response = new CfLyricClient().fetch(track);
            if (response == null) return null;
            for (CfLyricClient.CfVersion version : response.versions) {
                MusicLyric lyric = toMusicLyric(version);
                if (hasWordLyric(lyric)) return lyric;
            }
        } catch (Throwable t) {
            SpiderDebug.log("music", "lyricRich cf word error %s", t.getMessage());
        }
        return null;
    }

    /**
     * 酷我行级直连（宿主自有客户端，不走插件）：按歌名＋歌手搜酷我，取首个带行时间的版。
     * KuwoClient 内部已判空（data null 回空串、toResult 无行时间回 null），酷我接口回空时
     * 只返回 null 不抛错；本方法再兜一层 try，绝不把异常漏进回退链。
     * 行级版不冒充逐字：format 标 LRC（行级），sourceName 标酷我。
     */
    private static MusicLyric kuwoLineLyric(MusicTrack track) {
        try {
            if (track == null || track.title == null || track.title.trim().isEmpty()) return null;
            String artist = track.artist == null ? "" : track.artist.trim();
            LyricsRequest req = new LyricsRequest("", "", track.title.trim(), artist, "", track.duration);
            // 取词口径总封顶 18 秒（X-29 补账）：经 LyricsRepository 封顶帮手调，不再直调无封顶
            List<LyricsResult> results = LyricsRepository.runWithDeadline("Kuwo",
                    LyricsRepository.deadlineAfter(LyricsRepository.LOAD_TIMEOUT_MS),
                    () -> new KuwoClient().findAll(req, 1));
            if (results == null || results.isEmpty()) return null;
            for (LyricsResult r : results) {
                if (r == null) continue;
                String text = r.getLyrics();
                if (text == null || text.trim().isEmpty()) continue;
                MusicLyric lyric = new MusicLyric(text);
                lyric.format = "LRC";
                lyric.sourceName = "酷我";
                return lyric;
            }
        } catch (Throwable t) {
            SpiderDebug.log("music", "kuwo line lyric error %s", t.getMessage());
        }
        return null;
    }

    /**
     * Worker 聚合全量接线缝（版本聚合用，与 cfLyricWord 同一映射要求）。
     * 全量收：正文非空的版本都交 addVersion，由它本地解析正文定逐字签、
     * 按 rawLrc 去重、跳过空版（酷我／咪咕／AMLL 空版到不了面板，不出空行）；
     * 快路径与此不同：快路径只收本地解析带词时间的首个版本。
     * 因此 Worker 行级版（如现阶段 QQ 行级）在这里只签逐行，不冒充逐字；
     * QQ 逐字（QRC）目前拿不到，不写成已通。
     */
    /**
     * B8：Worker 失败不许在本层吞掉——异常直接抛给 lyricRichAll 第 4 步的 catch，
     * 由它记不完整（旧口径这里 catch 后返回部分列表，恢复判失效会误报）；空回包
     * （response＝null）不是失败，返回空列表、聚合仍完整。
     */
    private static List<MusicLyric> cfLyricWordAll(MusicTrack track) {
        List<MusicLyric> out = new ArrayList<>();
        CfLyricClient.CfResponse response = new CfLyricClient().fetch(track);
        if (response == null) return out;
        for (CfLyricClient.CfVersion version : response.versions) {
            MusicLyric lyric = toMusicLyric(version);
            if (lyric != null && hasRaw(lyric)) out.add(lyric);
        }
        return out;
    }

    /** Worker 版本 → MusicLyric：只搬字段，不搬 kind 宣称的档位（档位一律由解析定）。 */
    private static MusicLyric toMusicLyric(CfLyricClient.CfVersion version) {
        if (version == null) return null;
        MusicLyric lyric = new MusicLyric(version.lyric);
        lyric.format = version.format;
        lyric.sourceName = version.label;
        lyric.translation = version.translation;
        lyric.romanization = version.romanization;
        return lyric;
    }

    /** 词级判定：正文解析后任一行带词时间即逐字（与版本档位同一口径） */
    private static boolean hasWordLyric(MusicLyric lyric) {
        if (!hasRaw(lyric)) return false;
        try {
            for (LyricsLine line : LyricsParser.parseWordLyric(lyric.rawLrc)) {
                if (line != null && line.hasWords()) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 逐行判定：有行时间且无词时间（逐字版行时间同样真，必须先排词）；与 LyricVersion 档位同一口径 */
    private static boolean hasLineOnlyLyric(MusicLyric lyric) {
        if (!hasRaw(lyric)) return false;
        boolean line = false;
        try {
            for (LyricsLine l : LyricsParser.parseWordLyric(lyric.rawLrc)) {
                if (l == null) continue;
                if (l.hasWords()) return false;
                if (l.getTimeMs() > 0) line = true;
            }
        } catch (Throwable ignored) {
        }
        return line;
    }

    /**
     * AMLL 判定：只认来源名 sourceName＝"AMLL"。Worker 版 format 是 TTML（见 CfLyricClient，
     * 其 format 进 toMusicLyric 后是 TTML），只判 format 会漏 Worker 的 AMLL 版；且 LyricVersion
     * 侧可靠标识同样只有 getSource()＝"AMLL"，此处与版本列表同一口径，不另判 format／platform。
     */
    private static boolean isAmllLyric(MusicLyric lyric) {
        return lyric != null && "AMLL".equals(lyric.sourceName);
    }

    /** LyricVersion 侧 AMLL 判定：只认 getSource()＝"AMLL"，不判 format（同 isAmllLyric 口径） */
    private static boolean isAmllVersion(LyricVersion v) {
        return v != null && "AMLL".equals(v.getSource());
    }

    /**
     * 快路径选词桶（在线歌词优先级用）：沿回退链逐个记，不过滤、不丢回落。
     * note() 在命中本模式首选时直接回传该版（调用方立即返回，与旧逐字立即返回同形）；
     * 否则只记桶、回 null 让链继续走。pick() 在链走完后按优先级次序取，兜底链顺序首个
     * 有正文版，再兜底本源有词版——原来有词的歌绝不因此变无词。
     * 各模式首选：wordByWord 逐字（默认＝旧行为）／lineByLine 逐行／amllFirst AMLL／
     * followSource 链序首个有正文版（保来源顺序）。
     */
    private static class LyricPick {
        private final String priority;
        private MusicLyric firstRaw;
        private MusicLyric firstWord;
        private MusicLyric firstLineOnly;
        private MusicLyric firstAmll;

        LyricPick(String priority) {
            this.priority = priority;
        }

        MusicLyric note(MusicLyric lyric) {
            if (!hasRaw(lyric)) return null;
            if (firstRaw == null) firstRaw = lyric;
            boolean word = hasWordLyric(lyric);
            if (word && firstWord == null) firstWord = lyric;
            boolean lineOnly = !word && hasLineOnlyLyric(lyric);
            if (lineOnly && firstLineOnly == null) firstLineOnly = lyric;
            if (isAmllLyric(lyric) && firstAmll == null) firstAmll = lyric;
            if (MusicStore.LYRIC_PRIORITY_FOLLOW_SOURCE.equals(priority)) return lyric;
            if (MusicStore.LYRIC_PRIORITY_LINE_BY_LINE.equals(priority) && lineOnly) return lyric;
            if (MusicStore.LYRIC_PRIORITY_AMLL_FIRST.equals(priority) && isAmllLyric(lyric)) return lyric;
            // 逐字立即返回只属于默认 wordByWord：逐行／AMLL 模式下词版只记桶、不截断链，
            // 否则后到的逐行／AMLL 版永远等不到，设置成假设置（getLyricPriority 已保证四值之一）。
            if (MusicStore.LYRIC_PRIORITY_WORD_BY_WORD.equals(priority) && word) return lyric;
            return null;
        }

        MusicLyric pick(MusicLyric own) {
            if (MusicStore.LYRIC_PRIORITY_LINE_BY_LINE.equals(priority)) {
                if (firstLineOnly != null) return firstLineOnly;
                if (firstWord != null) return firstWord;
            } else if (MusicStore.LYRIC_PRIORITY_AMLL_FIRST.equals(priority)) {
                if (firstAmll != null) return firstAmll;
                if (firstWord != null) return firstWord;
                if (firstLineOnly != null) return firstLineOnly;
            }
            if (firstRaw != null) return firstRaw;
            if (own != null && own.hasLyric()) return own;
            return null;
        }
    }

    /**
     * lyricRich 的全量版（B2）：同一条回退链，但收集全部实际命中，不截断；去重口径 rawLrc.trim()。
     * 返回按链顺序排列的版本列表；force／歌词缺失开关口径与 lyricRich 一致。
     * 注意：本方法会多发网络请求（同源插件＋跨源＋歌词插件＋Worker 聚合＋平台直连回落＋AMLL），只供版本筛选条用，不替代快路径 lyricRich。
     * 返回前按在线歌词优先级做稳定分区（桶内保链顺序）：wordByWord 逐字／逐行／纯文本；
     * lineByLine 逐行／逐字／纯文本；amllFirst source＝"AMLL" 在前、其后逐字／逐行／纯文本；
     * followSource 不分区、保收集序。默认 wordByWord 的逐字在前与快路径口径一致；
     * 逐字标签仍由 addVersion 本地解析各版本正文判定，不认任何回包自带的档位宣称——Worker 只是多格式聚合来源，不是标签依据。
     */
    /**
     * B8 全量聚合结果：versions＝实际拿到的版本；complete＝聚合是否跑完整条链。
     * 不完整＝提前截断（歌词缺失开关关时只返回本源版、查询序列为空时后续步没跑、
     * 歌／源为空时根本没聚合）或任一步 transient 失败（各步 catch 后继续时记不完整，
     * 不许拿部分列表当全量判版本失效）。AMLL 门控关时跳过是既定配置，不记不完整。
     */
    public static class LyricRichAllResult {
        public final List<LyricVersion> versions;
        public final boolean complete;

        LyricRichAllResult(List<LyricVersion> versions, boolean complete) {
            this.versions = versions;
            this.complete = complete;
        }
    }

    /** 旧口径（版本筛选条用）：只取版本列表，不看完整标志；恢复判失效走 lyricRichAllDetailed。 */
    public static List<LyricVersion> lyricRichAll(MusicTrack track, boolean force) {
        return lyricRichAllDetailed(track, force).versions;
    }

    /** B8：与 lyricRichAll 同一条链，但带回是否完整（恢复判失效只认完整聚合）。 */
    public static LyricRichAllResult lyricRichAllDetailed(MusicTrack track, boolean force) {
        List<LyricVersion> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean complete = true;
        if (track == null) return new LyricRichAllResult(out, false);
        MusicSource s = find(track.platform);
        if (s == null) return new LyricRichAllResult(out, false);
        try {
            MusicLyric ownRich = normalizeRich(richOf(s, track));
            if (ownRich != null && (ownRich.sourceName == null || ownRich.sourceName.isEmpty())) {
                ownRich.sourceName = siteNameForPlatform(track.platform);
            }
            addVersion(out, seen, ownRich);
        } catch (Throwable t) {
            rethrowKeyIssue(t);
            complete = false;
            SpiderDebug.log("music", "lyricRichAll error platform=%s %s", track.platform, t.getMessage());
        }
        if (!force && !MusicStore.isLyricFallback()) return new LyricRichAllResult(out, false);
        List<LocalTrackMeta.Query> queries = LocalTrackMeta.queries(track.title, track.artist, track.platform);
        if (queries.isEmpty()) return new LyricRichAllResult(out, false);

        // 1. 本源内部的其它插件（FreeJS 多插件／落雪插件循环，全量，不截断）
        try {
            if (s instanceof FreeJsMusicSource) {
                for (MusicLyric l : ((FreeJsMusicSource) s).lyricFallbackAllRich(track)) {
                    addVersion(out, seen, normalizeRich(l));
                }
            } else if (s instanceof LxMusicSource) {
                for (MusicLyric l : ((LxMusicSource) s).lyricPluginAllRich(track)) {
                    addVersion(out, seen, normalizeRich(l));
                }
            }
        } catch (Throwable t) {
            rethrowKeyIssue(t);
            complete = false;
            SpiderDebug.log("music", "lyricRichAll fallback error %s", t.getMessage());
        }
        // 2. 其它源（本地源跳过）：每源各收一命中；本地歌按查询序列逐级收（去重在 addVersion）
        for (LocalTrackMeta.Query q : queries) {
            for (MusicSource other : SOURCES) {
                if (other == s || LocalMusicSource.PLATFORM.equals(other.platform())) continue;
                try {
                    MusicTrack hit = matchSong(matchTarget(track, q.matchTitle), other.search(q.keyword));
                    if (hit == null) continue;
                    MusicLyric alt = normalizeRich(richOf(other, hit));
                    if (alt != null && (alt.sourceName == null || alt.sourceName.isEmpty())) {
                        alt.sourceName = siteNameForPlatform(hit.platform);
                    }
                    addVersion(out, seen, alt);
                } catch (Throwable t) {
                    rethrowKeyIssue(t);
                    complete = false;
                    SpiderDebug.log("music", "lyricRichAll alt search error platform=%s %s", other.platform(), t.getMessage());
                }
            }
        }
        // 3. 歌词型插件直查（与快路径同一步：逐个问 FreeJS 源的歌词插件，2026-10-22 前这里无条件 break 只问第一家）
        for (MusicSource other : SOURCES) {
            if (!(other instanceof FreeJsMusicSource)) continue;
            try {
                for (MusicLyric l : ((FreeJsMusicSource) other).lyricPluginsAllRich(track)) {
                    addVersion(out, seen, normalizeRich(l));
                }
            } catch (Throwable t) {
                complete = false;
                SpiderDebug.log("music", "lyricRichAll plugin search error %s", t.getMessage());
            }
        }
        // 4. Worker 聚合（主来源，已接线）：cfLyricWordAll 全量交 addVersion，
        // 逐字签仍由 addVersion 本地解析正文判定，不认回包 kind 宣称。
        try {
            for (MusicLyric l : cfLyricWordAll(track)) {
                addVersion(out, seen, normalizeRich(l));
            }
        } catch (Throwable t) {
            complete = false;
            SpiderDebug.log("music", "lyricRichAll cf word error %s", t.getMessage());
        }
        // 5. 平台直连回落（Worker 不可用时才走：酷狗／QQ／网易云各收一词级版；行级不收，版本条只显真实版本）
        try {
            for (MusicLyric l : new PlatformWordLyricSource().lyricRichAll(track)) {
                addVersion(out, seen, normalizeRich(l));
            }
        } catch (Throwable t) {
            complete = false;
            SpiderDebug.log("music", "lyricRichAll platform word error %s", t.getMessage());
        }
        // 5.5 酷我行级直连（与快路径同款：插件崩后宿主自有空安全客户端补一行级版，版本条行级桶收）。
        try {
            addVersion(out, seen, normalizeRich(kuwoLineLyric(track)));
        } catch (Throwable t) {
            complete = false;
            SpiderDebug.log("music", "lyricRichAll kuwo line error %s", t.getMessage());
        }
        // 6. AMLL（B8）；S6 门控：关时跳过，默认开行为不变
        if (MusicStore.isAmllEnabled()) {
            try {
                addVersion(out, seen, normalizeRich(new AmllMusicLyricSource().lyricRich(track)));
            } catch (Throwable t) {
                complete = false;
                SpiderDebug.log("music", "lyricRichAll amll error %s", t.getMessage());
            }
        }
        // 在线歌词优先级分区（稳定：桶内保链顺序；逐行＝行时间且无词时间，纯文本＝无词无行时间）。
        // followSource 不分区：原样返回收集序。AMLL 只认 getSource()＝"AMLL"，不判 format。
        String priority = MusicStore.getLyricPriority();
        if (MusicStore.LYRIC_PRIORITY_FOLLOW_SOURCE.equals(priority)) return new LyricRichAllResult(out, complete);
        List<LyricVersion> amll = new ArrayList<>();
        List<LyricVersion> word = new ArrayList<>();
        List<LyricVersion> line = new ArrayList<>();
        List<LyricVersion> plain = new ArrayList<>();
        for (LyricVersion v : out) {
            if (isAmllVersion(v)) amll.add(v);
            else if (v.isWordTimed()) word.add(v);
            else if (v.isLineTimed()) line.add(v);
            else plain.add(v);
        }
        out.clear();
        if (MusicStore.LYRIC_PRIORITY_AMLL_FIRST.equals(priority)) out.addAll(amll);
        if (MusicStore.LYRIC_PRIORITY_LINE_BY_LINE.equals(priority)) {
            out.addAll(line);
            out.addAll(word);
        } else {
            out.addAll(word);
            out.addAll(line);
        }
        out.addAll(plain);
        return new LyricRichAllResult(out, complete);
    }

    /** B4：密钥问题错误不许被回退链吞掉——解包重抛；其余照旧记日志 */
    private static void rethrowKeyIssue(Throwable t) {
        Throwable root = t;
        while (root instanceof java.util.concurrent.ExecutionException
                || root instanceof java.util.concurrent.CompletionException) {
            if (root.getCause() == null) break;
            root = root.getCause();
        }
        if (root instanceof LxKeyIssue) throw (LxKeyIssue) root;
    }

    /** B2：platform → 站点名（版本筛选条的来源名；查不到返回空，筛选条只显档位） */
    private static String siteNameForPlatform(String platform) {
        if (platform == null || platform.isEmpty()) return null;
        try {
            for (MusicSite site : sites()) {
                if (platform.equals(site.platform)) return site.name;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** B2：版本收集合并——无词跳过；rawLrc.trim() 去重（纯译文／纯音译版本用三格拼接去重）；档位由解析结果判定 */
    private static void addVersion(List<LyricVersion> out, Set<String> seen, MusicLyric lyric) {
        if (lyric == null || !lyric.hasLyric()) return;
        String key = lyric.rawLrc == null ? "" : lyric.rawLrc.trim();
        if (key.isEmpty()) {
            key = (lyric.translation == null ? "" : lyric.translation.trim())
                    + "|" + (lyric.romanization == null ? "" : lyric.romanization.trim());
        }
        if (key.isEmpty() || !seen.add(key)) return;
        boolean word = false;
        boolean line = false;
        try {
            List<LyricsLine> lines = LyricsParser.parseWordLyric(lyric.rawLrc == null ? "" : lyric.rawLrc);
            for (LyricsLine l : lines) {
                if (l == null) continue;
                if (l.hasWords()) word = true;
                if (l.getTimeMs() > 0) line = true;
            }
        } catch (Throwable ignored) {
        }
        out.add(new LyricVersion(lyric, lyric.sourceName, word, line));
    }

    /** 单源取 rich 歌词：FreeJS/落雪走 rich 通道，其它源只有纯文本就包一层 */
    private static MusicLyric richOf(MusicSource source, MusicTrack track) throws Exception {
        if (source instanceof FreeJsMusicSource) return ((FreeJsMusicSource) source).lyricRich(track);
        if (source instanceof LxMusicSource) return ((LxMusicSource) source).lyricRich(track);
        String raw = source.lyric(track);
        return raw == null ? null : new MusicLyric(raw);
    }

    private static boolean hasRaw(MusicLyric lyric) {
        return lyric != null && lyric.rawLrc != null && !lyric.rawLrc.trim().isEmpty();
    }

    /**
     * rich 归一：正文为空但译文有整行定时词时，把译文提为正文（译格保留）。
     * 两页都只拿 rawLrc 当正文解析，译文-only 的 MusicLyric 若原样返回会被判空态——
     * 有词就不许空态；正文已有词时原样不动。
     */
    private static MusicLyric normalizeRich(MusicLyric lyric) {
        if (lyric == null || hasRaw(lyric)) return lyric;
        if (lyric.translation != null && !lyric.translation.trim().isEmpty()
                && com.fongmi.android.tv.player.lyrics.LyricsParser.hasTimedLine(lyric.translation)) {
            lyric.rawLrc = lyric.translation;
        }
        return lyric;
    }

    /** 歌名包含＋（有歌手时）歌手包含才认，防止拿错歌的词；归一化去标点/空白/大小写 */
    public static MusicTrack matchSong(MusicTrack target, List<MusicTrack> candidates) {
        if (target == null || candidates == null) return null;
        String title = normalizeSong(target.title);
        if (title.isEmpty()) return null;
        // 占位歌手（<unknown>／unknown 类）从不参与对歌：它不是真歌手，拿它卡人只会把对的歌也拒掉
        String artist = LocalTrackMeta.isPlaceholderArtist(target.artist) ? "" : normalizeSong(target.artist);
        // 先精确：归一化歌名完全相等才认（本地歌无歌手时尤其重要，否则“来不及爱你”会先撞上“来不及”这种前缀歌）
        for (MusicTrack c : candidates) {
            if (c == null) continue;
            String ct = normalizeSong(c.title);
            if (ct.isEmpty() || !ct.equals(title)) continue;
            if (!artistMatch(artist, c.artist)) continue;
            return c;
        }
        // 再包含：歌名互含才认（旧口径，兜底）
        for (MusicTrack c : candidates) {
            if (c == null) continue;
            String ct = normalizeSong(c.title);
            if (ct.isEmpty()) continue;
            if (!ct.contains(title) && !title.contains(ct)) continue;
            if (!artistMatch(artist, c.artist)) continue;
            return c;
        }
        return null;
    }

    /** 歌手对上：目标无歌手时不卡；候选无歌手时不卡；有歌手时双方归一化后互含才认（与旧口径一致） */
    private static boolean artistMatch(String artist, String candidateArtist) {
        if (artist == null || artist.isEmpty()) return true;
        String ca = normalizeSong(candidateArtist);
        if (ca.isEmpty()) return true;
        return ca.contains(artist) || artist.contains(ca);
    }

    /**
     * 对歌用净副本：本地歌标题换净歌名、占位歌手清空；在线歌原样返回，行为不动。
     * matchTitle 取查询序列里的标题形态（与当次查询关键词对应）。
     */
    public static MusicTrack matchTarget(MusicTrack track, String matchTitle) {
        if (track == null) return null;
        if (!LocalTrackMeta.isLocalPlatform(track.platform)) return track;
        MusicTrack c = new MusicTrack(track.id, matchTitle, LocalTrackMeta.realArtist(track.artist),
                track.album, track.duration, track.cover);
        c.platform = track.platform;
        c.localPath = track.localPath;
        c.rawJson = track.rawJson;
        return c;
    }

    /**
     * 在线封面兜底：按净歌名逐级搜在线源（跳过本地／设备源），命中即返回封面地址；
     * 播放页当前曲无封面时后台调一次，命中后补上封面。第三波 A3：去掉仅本地歌门，在线歌也可用。
     */
    public static String onlineCover(MusicTrack track) {
        if (track == null) return null;
        for (LocalTrackMeta.Query q : LocalTrackMeta.queries(track.title, track.artist, track.platform)) {
            if (q.keyword == null || q.keyword.isEmpty()) continue;
            MusicTrack target = matchTarget(track, q.matchTitle);
            for (MusicSource other : SOURCES) {
                if (other == null || LocalTrackMeta.isLocalPlatform(other.platform())) continue;
                try {
                    if (other instanceof FreeJsMusicSource) {
                        FreeJsMusicSource fj = (FreeJsMusicSource) other;
                        for (MusicSite site : fj.searchableSites()) {
                            MusicTrack hit = matchSong(target, fj.searchBySite(site, q.keyword));
                            if (hit != null && hit.cover != null && !hit.cover.trim().isEmpty()) return hit.cover.trim();
                        }
                    } else {
                        MusicTrack hit = matchSong(target, other.search(q.keyword));
                        if (hit != null && hit.cover != null && !hit.cover.trim().isEmpty()) return hit.cover.trim();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static String normalizeSong(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(ch)) sb.append(ch);
        }
        return sb.toString();
    }
}
