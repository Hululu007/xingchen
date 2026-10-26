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
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

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

    /**
     * 三-42 取链渠道旁路记录（单条最近命中，按 trackKey 认歌）：playUrl 真实取链命中时
     * 只记「命中的是哪条路＋命中插件 srcUrl」，不参与取链决策、不改任何回落顺序。
     * 探链（probe）不记（与不记死同口径，探链不是播放取链）。服务 prepareTrack 真起播时
     * 按 trackKey 读走存进服务状态，信息页经 MusicController 只读口展示；预加载取的链
     * 也是经 playUrl 记的同一键，正式起播命中预载槽时读到的仍是那次命中的真渠道。
     * 并发取链下单槽以后到者为准，读端一律按 key 比对，key 不符即无记录，不许串歌。
     */
    public static final int PLAY_CHANNEL_NONE = 0;
    /** 指派插件单试命中（明细＝指派插件 srcUrl） */
    public static final int PLAY_CHANNEL_ASSIGNED = 1;
    /** 落雪源内遍历命中（明细＝出链脚本 srcUrl，由 LxMusicSource 成功点旁路补记） */
    public static final int PLAY_CHANNEL_LX = 2;
    /** FreeJS 源内命中（明细＝本歌归属插件 srcUrl，即 track 的 _plugin，桥按它选） */
    public static final int PLAY_CHANNEL_FREEJS = 3;
    /** 直连命中（壳源／本地／设备，无插件明细） */
    public static final int PLAY_CHANNEL_DIRECT = 4;

    private static volatile String playChannelKey;
    private static volatile int playChannelKind = PLAY_CHANNEL_NONE;
    private static volatile String playChannelSrc;

    /** 仓库层成功点记渠道（明细 srcUrl 可空；同 key 且明细传空时不覆盖源内已补记的明细） */
    public static void notePlayChannel(String trackKey, int kind, String srcUrl) {
        if (trackKey == null || trackKey.isEmpty()) return;
        synchronized (DEAD_PLAY_URL_LOCK) {
            if (!trackKey.equals(playChannelKey)) playChannelSrc = null;
            playChannelKey = trackKey;
            playChannelKind = kind;
            if (srcUrl != null) playChannelSrc = srcUrl;
        }
    }

    /** 源内成功点补记命中插件 srcUrl（如落雪遍历命中的脚本）；key 不符不写，不许串歌 */
    public static void notePlayChannelSrc(String trackKey, String srcUrl) {
        if (trackKey == null || trackKey.isEmpty() || srcUrl == null) return;
        synchronized (DEAD_PLAY_URL_LOCK) {
            if (!trackKey.equals(playChannelKey)) {
                playChannelKey = trackKey;
                playChannelKind = PLAY_CHANNEL_NONE;
            }
            playChannelSrc = srcUrl;
        }
    }

    /** 按 key 读渠道枚举；key 不符（不是这首的命中）返回 PLAY_CHANNEL_NONE */
    public static int playChannelKindOf(String trackKey) {
        if (trackKey == null) return PLAY_CHANNEL_NONE;
        synchronized (DEAD_PLAY_URL_LOCK) {
            return trackKey.equals(playChannelKey) ? playChannelKind : PLAY_CHANNEL_NONE;
        }
    }

    /** 按 key 读命中插件 srcUrl；key 不符或无明细返回 null */
    public static String playChannelSrcOf(String trackKey) {
        if (trackKey == null) return null;
        synchronized (DEAD_PLAY_URL_LOCK) {
            return trackKey.equals(playChannelKey) ? playChannelSrc : null;
        }
    }

    /**
     * 二-28 负结果短期缓存：整链跑完确认无词的歌，60 秒内再取词直接回空，不重走全链
     * （本地无词歌每次进播放页都整链串行重跑，是「歌词出现晚、无词也晚」的尾巴；
     * 且播放页 lyricRich 空后还要再走一遍 lyric() 纯文本链，不拦就是双倍整链）。
     * 只记「整链无词」这一种结论：歌词缺失开关关／查询为空的提前收口不记；
     * 手动重搜（force）不读本缓存、整链照跑，跑完按结果刷新；任一步命中即清。
     * 键与 MusicLyricCache 同一身份口径（platform:id／本地路径／标题回退键）。
     */
    private static final long LYRIC_MISS_TTL_MS = 60_000;
    private static final int LYRIC_MISS_MAX = 200;

    /**
     * 负缓存条目带作用域（待裁13）：rich＝完整链 lyricRich 整链无词；窄＝纯文本链
     * lyric() 整链无词。纯文本链已与完整链同跑本源／跨源／歌词插件／Worker／酷我
     * 行级，完整链只多跑平台直连／AMLL 两步，窄链记的负不许挡宽链（下载旁路先走
     * lyric() 记负、随后点播走 lyricRich 被未跑先判无词，即此病）；宽链记的负覆盖
     * 窄链全部步，窄链可以认。
     */
    private static class LyricMiss {
        final long at;
        final boolean rich;

        LyricMiss(boolean rich) {
            this.at = System.currentTimeMillis();
            this.rich = rich;
        }
    }

    private static final Map<String, LyricMiss> LYRIC_MISS_AT = new LinkedHashMap<String, LyricMiss>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, LyricMiss> eldest) {
            return size() > LYRIC_MISS_MAX;
        }
    };

    private static LyricMiss lyricMissed(MusicTrack track) {
        String key = MusicLyricCache.key(track);
        if (key == null) return null;
        synchronized (LYRIC_MISS_AT) {
            LyricMiss m = LYRIC_MISS_AT.get(key);
            if (m == null) return null;
            if (System.currentTimeMillis() - m.at <= LYRIC_MISS_TTL_MS) return m;
            LYRIC_MISS_AT.remove(key);
            return null;
        }
    }

    /** 纯文本链查负：任一作用域的新鲜负都挡（宽链确认无词 ⇒ 窄链必无词）。 */
    private static boolean lyricMissedRecently(MusicTrack track) {
        return lyricMissed(track) != null;
    }

    /** 完整链查负：只认 rich 作用域的新鲜负，窄链的负不挡宽链独有步。 */
    private static boolean lyricRichMissedRecently(MusicTrack track) {
        LyricMiss m = lyricMissed(track);
        return m != null && m.rich;
    }

    private static void markLyricMiss(MusicTrack track, boolean rich) {
        String key = MusicLyricCache.key(track);
        if (key == null) return;
        synchronized (LYRIC_MISS_AT) {
            LYRIC_MISS_AT.put(key, new LyricMiss(rich));
        }
    }

    /**
     * 取词调用代次（待裁14／15）：每次取词进入整链前登记一代，被新请求顶替的旧调用
     * 在检查点发现自己已非最新后：预取不再等、迟到结果直接弃；收尾不写负缓存、
     * 不清正缓存——旧调用的迟到空结果不许压掉新调用的状态。
     * 代次按歌分键（与负缓存同一身份键 MusicLyricCache.key）：顶替只认同一首歌的
     * 更新请求。旧口径是全局单键——切歌时上一首的取词任务还没收尾（rich 空手后
     * 还会再走一遍 lyric() 纯文本链），它一登记代次，新歌的整条预取回退链
     * （跨源／歌词插件／Worker／平台／酷我／AMLL）在 await 检查点被全步作废，
     * 新歌只剩本源一掷：本源再抛错就是整首无词（真机「切歌后歌词全死」的宿主侧
     * 放大器）。跨歌互不顶替后，串歌仍由播放页 trackKey 守卫与各自负缓存键拦。
     */
    private static final java.util.concurrent.atomic.AtomicLong LYRIC_CALL_GEN =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.Map<String, Long> LYRIC_LATEST_GEN_BY_TRACK =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String lyricCallKey(MusicTrack track) {
        String key = track == null ? null : MusicLyricCache.key(track);
        return key == null ? "" : key;
    }

    private static long beginLyricCall(MusicTrack track) {
        long gen = LYRIC_CALL_GEN.incrementAndGet();
        if (LYRIC_LATEST_GEN_BY_TRACK.size() > 512) LYRIC_LATEST_GEN_BY_TRACK.clear();
        LYRIC_LATEST_GEN_BY_TRACK.put(lyricCallKey(track), gen);
        return gen;
    }

    private static boolean isLatestLyricCall(long gen, MusicTrack track) {
        return isLatestLyricCall(gen, lyricCallKey(track));
    }

    private static boolean isLatestLyricCall(long gen, String callKey) {
        Long latest = LYRIC_LATEST_GEN_BY_TRACK.get(callKey == null ? "" : callKey);
        return latest != null && latest == gen;
    }

    private static void clearLyricMiss(MusicTrack track) {
        String key = MusicLyricCache.key(track);
        if (key == null) return;
        synchronized (LYRIC_MISS_AT) {
            LYRIC_MISS_AT.remove(key);
        }
    }

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
     * 平台身份去重（2026-10-25 用户点名「酷我有两个」）：预置酷我（FreeJS）与落雪
     * 合成项酷我（lx://kw）是同一个平台在搜索排里出两张 chip——同身份只留先到者；
     * 聚合顺序里 FreeJS 在落雪之前，故预置优先。去重只作用于本搜索排清单，
     * 插件本体与各源 sites() 一行不动，歌词／取链／歌单导入都不走这里。
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
        List<MusicSite> deduped = new ArrayList<>();
        java.util.Set<String> seenIdentities = new java.util.HashSet<>();
        for (MusicSite site : all) {
            String identity = searchIdentityOf(site);
            if (identity != null && !seenIdentities.add(identity)) continue;
            deduped.add(site);
        }
        return deduped;
    }

    /**
     * 搜索排的平台身份键（认不出返回 null＝不参与去重）：
     * 落雪合成项按 srcUrl 的 lx://&lt;平台&gt; 取平台 key；预置酷我按播种地址认；
     * 其余源名字恰为落雪五平台显示名（酷我音乐／网易云音乐／QQ音乐／酷狗音乐／咪咕音乐）
     * 时按该平台认（FreeJS 站点名即插件自报平台，自报平台名的插件与落雪同平台即同身份）。
     */
    private static String searchIdentityOf(MusicSite site) {
        if (site == null) return null;
        if (site.srcUrl != null && site.srcUrl.startsWith("lx://")) {
            String key = site.srcUrl.substring("lx://".length());
            return key.isEmpty() ? null : "lxplat:" + key;
        }
        if (com.fongmi.android.tv.api.music.MusicSourceManager.isBuiltinKuwo(site.srcUrl)) return "lxplat:kw";
        if (site.name != null) {
            String[] keys = {com.fongmi.android.tv.api.music.LxPlatformSearch.KW,
                    com.fongmi.android.tv.api.music.LxPlatformSearch.QQ,
                    com.fongmi.android.tv.api.music.LxPlatformSearch.WY,
                    com.fongmi.android.tv.api.music.LxPlatformSearch.KG,
                    com.fongmi.android.tv.api.music.LxPlatformSearch.MG};
            for (String key : keys) {
                if (site.name.equals(com.fongmi.android.tv.api.music.LxPlatformSearch.platformName(key))) {
                    return "lxplat:" + key;
                }
            }
        }
        return null;
    }

    /**
     * 按单个子源搜歌：结果只归这个源。失败直接抛出，由搜索页按源单独提示，
     * 不许一个源的失败拖垮整页（阻塞，请在子线程调）。
     */
    public static List<MusicTrack> searchSite(MusicSite site, String keyword) throws Exception {
        return searchSite(site, keyword, 1);
    }

    /** 分页版 searchSite（page 从 1 起）：页码透传到源，源不支持分页时第二页起返回空。 */
    public static List<MusicTrack> searchSite(MusicSite site, String keyword, int page) throws Exception {
        if (site == null) return Collections.emptyList();
        MusicSource s = find(site.platform);
        if (s == null) return Collections.emptyList();
        List<MusicTrack> r = s.searchBySite(site, keyword, Math.max(1, page));
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
                    if (url != null && !url.isEmpty()) {
                        // 三-42 渠道旁路：指派插件单试命中（探链不记，口径见字段注释）
                        if (!probe) notePlayChannel(MusicQueue.trackKey(track), PLAY_CHANNEL_ASSIGNED, site.srcUrl);
                        return url;
                    }
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
            String url = s.playUrl(track, quality, probe);
            // 三-42 渠道旁路：源内命中按平台记枚举；明细——FreeJS 是本歌归属插件
            // （桥按 _plugin 选，真值在仓库层），落雪是遍历命中的脚本（Lx 源内已补记
            // 同 key 明细，此处传空不覆盖），直连无插件明细。探链不记。
            if (!probe && url != null && !url.isEmpty()) {
                String key = MusicQueue.trackKey(track);
                if (LxMusicSource.PLATFORM.equals(track.platform)) {
                    notePlayChannel(key, PLAY_CHANNEL_LX, null);
                } else if (FreeJsMusicSource.PLATFORM.equals(track.platform)) {
                    notePlayChannel(key, PLAY_CHANNEL_FREEJS, originPluginOf(track));
                } else {
                    notePlayChannel(key, PLAY_CHANNEL_DIRECT, null);
                }
            }
            return url;
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
        if (own != null && !own.trim().isEmpty()) {
            clearLyricMiss(track);
            return own;
        }

        // 「歌词缺失自动搜索」：当前源没词（或报错）时，按源顺序拿其它插件/源用歌名+歌手搜歌词补上
        // 本地歌查询序列按净歌名逐级降级（文件名后缀／发行组后缀／占位歌手已清洗）；在线歌序列只有一个原样查询，行为不动
        if (!force && !MusicStore.isLyricFallback()) return null;
        List<LocalTrackMeta.Query> queries = LocalTrackMeta.queries(track.title, track.artist, track.platform);
        if (queries.isEmpty()) return null;
        // 二-28 负缓存后移（待裁19）：负判只拦整链重走，本源直取已在上方先行——
        // 本源新出现的词（本地 .lrc 刚放好／插件刚恢复）不被 60 秒旧判挡在门外。
        // 手动重搜 force 不读，口径不变。
        if (!force && lyricMissedRecently(track)) return null;
        long gen = beginLyricCall(track);

        if (force) {
            // 手动搜词：步 1／2 按对歌级别跨步分遍（待裁12，lyricForcePhased）
            String phased = lyricForcePhased(track, s, queries);
            if (phased != null) {
                clearLyricMiss(track);
                return phased;
            }
        } else {
            // 1. 本源内部的其它插件（FreeJS 多插件，启用顺序）
            if (s instanceof FreeJsMusicSource) {
                try {
                    String fb = ((FreeJsMusicSource) s).lyricFallback(track);
                    if (fb != null && !fb.trim().isEmpty()) {
                        clearLyricMiss(track);
                        return fb;
                    }
                } catch (Throwable t) {
                    SpiderDebug.log("music", "lyric fallback error %s", t.getMessage());
                }
            }
            // 2. 其它源（本地源跳过，它不提供歌词）：查询序列外层（净查询先问遍各源，再降级）
            String crossLrc = lyricCrossSource(track, s, queries, false);
            if (crossLrc != null) {
                clearLyricMiss(track);
                return crossLrc;
            }
        }
        // 3. 歌词型插件直查：歌词网/歌词千寻的 search 只认 type="lyric"，
        //    前两步（源级搜歌）永远问不到它们；本地/设备歌的词主要靠这条。
        //    放在最后，在线歌原有取词优先级不动。
        for (MusicSource other : SOURCES) {
            if (!(other instanceof FreeJsMusicSource)) continue;
            try {
                String lrc = ((FreeJsMusicSource) other).lyricFromLyricPlugins(track);
                if (lrc != null && !lrc.trim().isEmpty()) {
                    clearLyricMiss(track);
                    return lrc;
                }
            } catch (Throwable t) {
                SpiderDebug.log("music", "lyric plugin search error %s", t.getMessage());
            }
        }
        // 4. Worker 聚合（主来源，与 lyricRich 步 4 同一接线缝 cfLyricWord、同一
        //    CfLyricClient 入口与超时口径）：完整链已接、纯文本链此前漏接——rich 链
        //    空手后第二相走到本链时，Worker 的多平台聚合不在链上，只剩同族接口再掷
        //    一次骰子。只认与完整链同口径的词级版（cfLyricWord 本地解析定逐字），
        //    其 rawLrc 即本链要的词级原文（译／音译在 MusicLyric 映射上照带，本链
        //    只回正文字符串，与其余各步同限）。cfLyricWord 内部已吞错回空、绝不
        //    抛错，外层再兜一层与本链各步口径一致；Worker 不可用只回空，尾步照走。
        try {
            MusicLyric cf = cfLyricWord(track);
            if (cf != null && cf.rawLrc != null && !cf.rawLrc.trim().isEmpty()) {
                clearLyricMiss(track);
                return cf.rawLrc;
            }
        } catch (Throwable t) {
            SpiderDebug.log("music", "lyric cf worker error %s", t.getMessage());
        }
        // 尾步：酷我行级直连（与 lyricRich 步 5.5 同路、同客户端）。纯文本链原先
        // 没有这一步——rich 链空手后第二相只剩 freejs／落雪同族接口再掷一次骰子；
        // 酷我自有三端点（newlyric／mobi／openapi）与插件走的 songinfoandlrc 不是
        // 同一接口，那个接口间歇回空时这里仍能兜住行级词。kuwoLineLyric 内部已
        // 判空兜底、绝不抛错，外层再兜一层与本链各步口径一致。
        try {
            MusicLyric kuwo = kuwoLineLyric(track);
            if (kuwo != null && kuwo.rawLrc != null && !kuwo.rawLrc.trim().isEmpty()) {
                clearLyricMiss(track);
                return kuwo.rawLrc;
            }
        } catch (Throwable t) {
            SpiderDebug.log("music", "lyric kuwo line error %s", t.getMessage());
        }
        // 二-28：整链跑完仍无词才记负缓存（提前收口的两处 return 在此之前，不记）；
        // 待裁15：被新请求顶替的旧调用不写（迟到的空结果不压新调用状态）
        if (isLatestLyricCall(gen, track)) markLyricMiss(track, false);
        return null;
    }

    /**
     * 待裁12：手动搜词（force）时步 1（本源内部插件）与步 2（跨源）的级别分遍。
     * 原串行口径下步 1 整步先行：某插件一张包含级逐字命中会先返回，步 2 其它源的
     * 精确级命中永远轮不到——二-8 的「精确优先」只在单个源的候选表内成立、跨步落空。
     * 本遍把两步按级别拆开：精确遍跨两步先行（步 1 精确→步 2 精确），任一精确命中
     * 即收；全程无精确命中才按原步序跑包含遍（步 1 包含→步 2 包含）作次级回落。
     * 总序不变：步序仍 1 先于 2、级别只在同为搜歌对歌的两步之间分遍。
     */
    private static String lyricForcePhased(MusicTrack track, MusicSource s, List<LocalTrackMeta.Query> queries) {
        if (s instanceof FreeJsMusicSource) {
            try {
                String fb = ((FreeJsMusicSource) s).lyricFallbackAtLevel(track, MATCH_EXACT);
                if (fb != null && !fb.trim().isEmpty()) return fb;
            } catch (Throwable t) {
                SpiderDebug.log("music", "lyric fallback exact error %s", t.getMessage());
            }
        }
        String exact = lyricCrossPass(track, s, queries, MATCH_EXACT);
        if (exact != null) return exact;
        if (s instanceof FreeJsMusicSource) {
            try {
                String fb = ((FreeJsMusicSource) s).lyricFallbackAtLevel(track, MATCH_CONTAINS);
                if (fb != null && !fb.trim().isEmpty()) return fb;
            } catch (Throwable t) {
                SpiderDebug.log("music", "lyric fallback contains error %s", t.getMessage());
            }
        }
        return lyricCrossPass(track, s, queries, MATCH_CONTAINS);
    }

    /**
     * lyric() 步 2（跨源纯文本）：自动路径单遍原口径；手动搜词（force，二-8）与 rich 链
     * 同口径——先精确遍（跨全部查询×全部源只认精确级），全程无精确命中才跑包含遍作次级回落。
     */
    private static String lyricCrossSource(MusicTrack track, MusicSource self, List<LocalTrackMeta.Query> queries, boolean force) {
        if (!force) return lyricCrossPass(track, self, queries, MATCH_ANY);
        String exact = lyricCrossPass(track, self, queries, MATCH_EXACT);
        if (exact != null) return exact;
        return lyricCrossPass(track, self, queries, MATCH_CONTAINS);
    }

    /** lyric() 步 2 单遍：level 指定级别时只认该级对歌；密钥问题照原口径向上抛、中止整链 */
    private static String lyricCrossPass(MusicTrack track, MusicSource self, List<LocalTrackMeta.Query> queries, int level) {
        for (LocalTrackMeta.Query q : queries) {
            for (MusicSource other : SOURCES) {
                if (other == self || LocalMusicSource.PLATFORM.equals(other.platform())) continue;
                try {
                    MusicTrack target = matchTarget(track, q.matchTitle);
                    List<MusicTrack> candidates = other.search(q.keyword);
                    MusicTrack hit = level == MATCH_ANY
                            ? matchSong(target, candidates)
                            : matchSongAtLevel(target, candidates, level);
                    if (hit == null) continue;
                    String lrc = other.lyric(hit);
                    if (lrc != null && !lrc.trim().isEmpty()) return lrc;
                } catch (Throwable t) {
                    rethrowKeyIssue(t);
                    SpiderDebug.log("music", "lyric alt search error platform=%s %s", other.platform(), t.getMessage());
                }
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
        return lyricRichInternal(track, force);
    }

    private static MusicLyric lyricRichInternal(MusicTrack track, boolean force) {
        if (track == null) return null;
        // 出词提速：链消费到第一份可用词（行级也算）时先行发布一次，供播放页先上屏；
        // 链的挑选口径与返回值不变，后到的更优版由页面在收口时无缝替换。
        java.util.concurrent.atomic.AtomicBoolean earlyFired = new java.util.concurrent.atomic.AtomicBoolean();
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
        // 来源名回填（与全量链 own 步同口径）：快路径步 0–3 此前集体不填，
        // 信息页「匹配来源」在最常见命中上恒显「—」（三-42 主路径未达成）
        if (own != null && (own.sourceName == null || own.sourceName.isEmpty())) {
            own.sourceName = siteNameForPlatform(track.platform);
        }
        fireEarlyLyric(track, own, earlyFired);
        MusicLyric hit = pick.note(own);
        if (hit != null) {
            clearLyricMiss(track);
            return hit;
        }

        if (!force && !MusicStore.isLyricFallback()) return own != null && own.hasLyric() ? own : null;
        List<LocalTrackMeta.Query> queries = LocalTrackMeta.queries(track.title, track.artist, track.platform);
        if (queries.isEmpty()) return own != null && own.hasLyric() ? own : null;
        // 二-28 负缓存后移＋分域（待裁13／19）：本源直取已在上方先行（本源新词不被旧判
        // 挡住），负判只拦整链重走；且只认 rich 作用域的负——纯文本链 lyric() 记的负
        // 覆盖不到本链独有的平台直连／AMLL 两步，不许未跑先判无词。
        // 手动重搜 force 不读、手动选版恢复在更上方已先行处理，口径不变。
        if (!force && lyricRichMissedRecently(track)) return own != null && own.hasLyric() ? own : null;
        long gen = beginLyricCall(track);
        boolean exactPhaseRaw = false;

        // 1. 本源内部的其它插件（FreeJS 多插件，启用顺序）
        // 手动搜词（force）本步先只跑精确级（待裁12）：步 1 的包含级命中不许抢在
        // 步 2 精确遍前面；包含级在步 2 精确遍之后按步序补跑（见预取段后）。
        if (s instanceof FreeJsMusicSource) {
            try {
                MusicLyric fb = normalizeRich(force
                        ? ((FreeJsMusicSource) s).lyricFallbackRichAtLevel(track, MATCH_EXACT)
                        : ((FreeJsMusicSource) s).lyricFallbackRich(track));
                if (force && hasRaw(fb)) exactPhaseRaw = true;
                fireEarlyLyric(track, fb, earlyFired);
                hit = pick.note(fb);
                if (hit != null) {
                    clearLyricMiss(track);
                    return hit;
                }
            } catch (Throwable t) {
                SpiderDebug.log("music", "lyricRich fallback error %s", t.getMessage());
            }
        }
        // 二-28 并行预取：步 2–6 彼此独立（入参只有 track、各步内部自带封顶——平台直连
        // 整链 18 秒／酷我 18 秒／AMLL 18 秒／Worker 连接 10 秒＋读 25 秒／桥调用 30 秒），
        // 原串行版最坏要把这些封顶逐个加满才出词；现一次并行发出（LyricPrefetch），
        // 回来后仍按原步序逐个取结果、逐个 note：pick 桶的首命中、立即返回点、密钥问题
        // 抛出点都与串行版逐点一致，墙钟从各步之和降为最慢一步。步 2 收集器自带同口径
        // 探针桶，命中首选即停收，与串行版「note 返回即停」同点；主线程已返回后才跑完
        // 的预取结果直接丢弃、不回写任何状态，不改变任何选择。
        // 主来源（Worker）结果一落地就先行发布，不等消费顺序轮到步 4——跨源收集
        // （步 2，桥搜索）慢时，Worker 的词曾备好却压在队列里，页面干等整链收口。
        LyricPrefetch prefetch = LyricPrefetch.start(track, s, queries, force, gen,
                lyric -> fireEarlyLyric(track, lyric, earlyFired));
        // 2. 其它源（本地源跳过，它不提供歌词）：查询序列外层（净查询先问遍各源，再降级）
        // force 时预取内的步 2 只跑精确遍（exactOnly），包含遍在下方与步 1 包含级同段补跑。
        CrossCollect cross = prefetch.cross();
        if (force && hasRawEntry(cross.lyrics)) exactPhaseRaw = true;
        for (MusicLyric lrc : cross.lyrics) {
            fireEarlyLyric(track, lrc, earlyFired);
            hit = pick.note(lrc);
            if (hit != null) {
                prefetch.abandon();
                clearLyricMiss(track);
                return hit;
            }
        }
        // 串行版在抛密钥问题的那个源处中止本步并向上抛；收集器把该点记在 keyIssue 里，
        // 前面各源的命中已按原序 note 完（若已命中首选上面已返回、与串行版同点不抛）。
        if (cross.keyIssue != null) {
            prefetch.abandon();
            throw cross.keyIssue;
        }
        // 待裁12 包含遍（仅 force）：精确遍（步 1 精确＋步 2 精确）全程无带正文命中才跑，
        // 与 collectCross 原「有精确命中不再跑包含遍」同一收口判据（hasRawEntry）；
        // 段内步序仍步 1 先于步 2。被新请求顶替的旧调用不再补跑（结果必被弃）。
        if (force && !exactPhaseRaw && isLatestLyricCall(gen, track)) {
            if (s instanceof FreeJsMusicSource) {
                try {
                    MusicLyric fb = normalizeRich(((FreeJsMusicSource) s).lyricFallbackRichAtLevel(track, MATCH_CONTAINS));
                    fireEarlyLyric(track, fb, earlyFired);
                    hit = pick.note(fb);
                    if (hit != null) {
                        prefetch.abandon();
                        clearLyricMiss(track);
                        return hit;
                    }
                } catch (Throwable t) {
                    SpiderDebug.log("music", "lyricRich fallback contains error %s", t.getMessage());
                }
            }
            CrossCollect cc2 = new CrossCollect();
            collectCrossPass(track, s, queries, MATCH_CONTAINS, new LyricPick(MusicStore.getLyricPriority()), cc2);
            for (MusicLyric lrc : cc2.lyrics) {
                fireEarlyLyric(track, lrc, earlyFired);
                hit = pick.note(lrc);
                if (hit != null) {
                    prefetch.abandon();
                    clearLyricMiss(track);
                    return hit;
                }
            }
            if (cc2.keyIssue != null) {
                prefetch.abandon();
                throw cc2.keyIssue;
            }
        }
        // 3. 歌词型插件直查（与 lyric() 同一步、同口径）
        for (MusicLyric lrc : prefetch.pluginLyrics()) {
            fireEarlyLyric(track, lrc, earlyFired);
            hit = pick.note(lrc);
            if (hit != null) {
                prefetch.abandon();
                clearLyricMiss(track);
                return hit;
            }
        }
        // 4. Worker 聚合（主来源，已接线）：cfLyricWord 只认本地解析逐字，
        // 弱网／超时回空，不阻塞回落；回包口径不动。
        MusicLyric cfHit = prefetch.cf();
        fireEarlyLyric(track, cfHit, earlyFired);
        hit = pick.note(cfHit);
        if (hit != null) {
            prefetch.abandon();
            clearLyricMiss(track);
            return hit;
        }
        // 5. 平台直连回落（Worker 不可用时才走：酷狗 KRC → QQ QRC → 网易云 YRC）：只认词级，行级不截断
        MusicLyric platformHit = prefetch.platform();
        fireEarlyLyric(track, platformHit, earlyFired);
        hit = pick.note(platformHit);
        if (hit != null) {
            prefetch.abandon();
            clearLyricMiss(track);
            return hit;
        }
        // 5.5 酷我行级直连（lrclist 崩的宿主侧根治）：元力 KW 插件 getLyric 在酷我接口回空时
        // 对 null 读 lrclist 抛未处理拒绝，宿主改不了远端混淆插件；本步用宿主自有 KuwoClient
        // （空数据已判空回空串，绝不崩）按歌名直取行级词，记进 pick 的行级／首版桶——
        // 默认逐字模式下不截断链（词版仍优先），最终 pick 无词版时回落本版，歌不再无词可显。
        MusicLyric kuwoHit = prefetch.kuwo();
        fireEarlyLyric(track, kuwoHit, earlyFired);
        hit = pick.note(kuwoHit);
        if (hit != null) {
            prefetch.abandon();
            clearLyricMiss(track);
            return hit;
        }
        // 6. AMLL（B8）：最后兜底，AMLL 档带档位元数据；无命中返回空，不冒充
        // S6 门控：开关关时预取不提交本步（结果为空），默认开，行为与原来一致
        MusicLyric amllHit = prefetch.amll();
        fireEarlyLyric(track, amllHit, earlyFired);
        hit = pick.note(amllHit);
        if (hit != null) {
            prefetch.abandon();
            clearLyricMiss(track);
            return hit;
        }
        MusicLyric result = pick.pick(own);
        prefetch.abandon();
        // 二-28：整链跑完仍无词才记负缓存（提前收口的 return 在此之前，不记）；
        // 待裁15：被新请求顶替的旧调用收尾不写缓存——迟到的空结果不许压掉新调用的
        // 正结果、迟到的命中也不许清新调用刚记的负，以最新请求的状态为准。
        if (isLatestLyricCall(gen, track)) {
            if (result != null) clearLyricMiss(track);
            else markLyricMiss(track, true);
        }
        return result;
    }

    // ================= 出词提速：共享单飞＋先行发布＋服务侧取词 =================
    // 背景（1.4.28 真机日志实证）：同一次切歌，播放页 lyricRich、版本聚合、页重绑重跑
    // 各自整链齐发——平台聚合（酷狗/QQ/网易 findAll）同歌跑了 3 份、AMLL 超时 3 次，
    // 全部挤在 Task.largeExecutor 与各插件单线程桥队列上互相拖慢，词要等最慢一份收口
    // 才上屏。共享单飞把「同歌同口径」的并发调用并成一条链：先到者跑、后来者搭结果。

    /** 共享链专用池：链本体在池线程上等各步结果（各步跑 largeExecutor），不许把链
     * 本体放进 largeExecutor——多条链互等子任务会把池占满自锁。 */
    private static final java.util.concurrent.ExecutorService LYRIC_CHAIN_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "lyric-chain");
                t.setDaemon(true);
                return t;
            });

    /** 在飞共享链：key＝trackKey＋口径（auto/force 分开，手动重搜不搭自动链的旧结果） */
    private static final java.util.Map<String, Future<MusicLyric>> LYRIC_IN_FLIGHT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 搭链等待封顶：高于链内最坏收口（恢复聚合＋各步封顶之和的量级），到点按无结果
     * 处理——链本体不撤、跑完照常回写缓存并经服务 onLyricReady 补达页面。 */
    private static final long LYRIC_SHARED_AWAIT_MS = 75_000;

    /** 先行发布监听：key＝trackKey；链消费到第一份可用词时回调一次（见 fireEarlyLyric） */
    private static final java.util.Map<String, java.util.function.Consumer<MusicLyric>> LYRIC_EARLY =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String lyricSharedKey(MusicTrack track, boolean force) {
        return MusicQueue.trackKey(track) + (force ? "|force" : "|auto");
    }

    /** 页面注册先行发布：链跑到第一份可用词（行级也算）就先递给页面上屏，收口的更优版
     * 随后无缝替换。注册按歌 key，链无论由谁启动（服务起播同刻／页面／预取）都认。 */
    public static void setEarlyLyricListener(MusicTrack track, java.util.function.Consumer<MusicLyric> listener) {
        if (track == null) return;
        if (listener == null) LYRIC_EARLY.remove(MusicQueue.trackKey(track));
        else LYRIC_EARLY.put(MusicQueue.trackKey(track), listener);
    }

    public static void clearEarlyLyricListener(MusicTrack track, java.util.function.Consumer<MusicLyric> listener) {
        if (track == null || listener == null) return;
        LYRIC_EARLY.remove(MusicQueue.trackKey(track), listener);
    }

    private static void fireEarlyLyric(MusicTrack track, MusicLyric candidate, java.util.concurrent.atomic.AtomicBoolean fired) {
        if (!hasRaw(candidate)) return;
        java.util.function.Consumer<MusicLyric> listener = LYRIC_EARLY.get(MusicQueue.trackKey(track));
        if (listener == null) return;
        if (!fired.compareAndSet(false, true)) return;
        try {
            listener.accept(candidate);
        } catch (Throwable ignored) {
        }
    }

    private static Future<MusicLyric> lyricInFlight(MusicTrack track, boolean force) {
        String key = lyricSharedKey(track, force);
        Future<MusicLyric> f = LYRIC_IN_FLIGHT.get(key);
        if (f != null && !f.isDone()) return f;
        Future<MusicLyric> nf = LYRIC_CHAIN_POOL.submit(() -> {
            try {
                MusicLyric r = lyricRichInternal(track, force);
                if (r != null && r.hasLyric()) MusicLyricCache.put(track, r);
                return r;
            } finally {
                LYRIC_IN_FLIGHT.remove(key);
                LYRIC_EARLY.remove(MusicQueue.trackKey(track));
            }
        });
        Future<MusicLyric> prev = LYRIC_IN_FLIGHT.putIfAbsent(key, nf);
        if (prev != null) {
            nf.cancel(false); // 没抢到登记，刚提交的这份不许跑——同歌只许一条链
            return prev;
        }
        return nf;
    }

    /** 共享取词（阻塞版，供页面）：同歌同口径已有链在飞就搭它，不再整链重跑。
     * 密钥问题照旧解包抛给调用方（页面弹密钥提示的口径不变）。 */
    public static MusicLyric lyricRichShared(MusicTrack track, boolean force) {
        if (track == null) return null;
        Future<MusicLyric> f = lyricInFlight(track, force);
        try {
            return f.get(LYRIC_SHARED_AWAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            rethrowKeyIssue(e);
            SpiderDebug.log("music", "lyric shared error %s", e.getMessage());
            return null;
        } catch (Throwable t) {
            // 超时/中断：不撤链，链跑完回写缓存、页面经 onLyricReady 补达
            return null;
        }
    }

    /** 共享取词（发起版，供服务起播同刻与下一首预取）：只保证链在飞，不等结果。 */
    public static void prefetchLyricRich(MusicTrack track) {
        if (track == null) return;
        lyricInFlight(track, false);
    }

    /**
     * 服务侧取词（起播同刻／下一首预取共用）：缓存命中直接收；否则搭共享链，
     * rich 空时再走纯文本链并回写缓存。与播放页取词同口径，只是没有 UI 副作用；
     * 密钥问题在此吞掉（页面自己那份调用会照常抛给页面处理）。
     */
    public static MusicLyric fetchLyricForPlayback(MusicTrack track) {
        if (track == null) return null;
        MusicLyric cached = MusicLyricCache.get(track);
        if (cached != null && cached.hasLyric()) return cached;
        MusicLyric rich = null;
        try {
            rich = lyricRichShared(track, false);
        } catch (Throwable ignored) {
        }
        if (rich != null && rich.hasLyric()) return rich;
        try {
            String plain = lyric(track, false);
            if (plain != null && !plain.trim().isEmpty()) {
                MusicLyricCache.putPlain(track, plain);
                return new MusicLyric(plain);
            }
        } catch (Throwable ignored) {
        }
        return rich;
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
     * Worker 版本全量收集合并（版本聚合用）：逐个版本经 toMusicLyric 映射后走带候选
     * 元数据的 addVersion——候选歌名／歌手／时长／源内 id 都是 Worker 回包原料
     * （durationSec／songId，2026-10-26 Worker 补字段下发），瓜子定级与时长偏差照
     * 瓜子口径在本地当场算（GzLyricMatch＋与跨源步同一偏差公式），不取 Worker 自带
     * 的 match 当显示分（那是它内部排序分，与瓜子分档映射不是一回事）。
     * 逐字签仍由 addVersion 本地解析正文判定，不认回包 kind 宣称；去重口径不变。
     * B8：Worker 失败不许在本层吞掉——异常直接抛给调用方 catch 记不完整；空回包
     * （response＝null）不是失败，聚合仍完整。
     */
    private static void cfLyricVersionsAll(MusicTrack track, List<LyricVersion> out, Set<String> seen) {
        CfLyricClient.CfResponse response = new CfLyricClient().fetch(track);
        if (response == null) return;
        for (CfLyricClient.CfVersion version : response.versions) {
            MusicLyric lyric = normalizeRich(toMusicLyric(version));
            if (lyric == null || !lyric.hasLyric()) continue;
            double gzLevel = GzLyricMatch.level(track.title, track.artist, track.duration,
                    version.songTitle, version.songArtist,
                    version.durationSec > 0 ? version.durationSec * 1000L : 0, version.songId);
            int deltaSec = track.duration > 0 && version.durationSec > 0
                    ? (int) Math.round(Math.abs(version.durationSec * 1000L - track.duration) / 1000.0) : 0;
            addVersion(out, seen, lyric, MATCH_CONTAINS, gzLevel,
                    version.songTitle, version.songArtist, deltaSec);
        }
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
            } else if (MusicStore.LYRIC_PRIORITY_WORD_BY_WORD.equals(priority)) {
                // 逐字模式此前漏了本分支：终局裁定直接落 firstRaw（链序首个），与 note 的
                // 逐字即时返回不对称——只要桶里有逐字版（无论哪一步收进来的），终局都该它赢，
                // 不许让先到的逐行版靠链序压过逐字版（默认选用认逐字优先）
                if (firstWord != null) return firstWord;
                if (firstLineOnly != null) return firstLineOnly;
            }
            if (firstRaw != null) return firstRaw;
            if (own != null && own.hasLyric()) return own;
            return null;
        }
    }

    /**
     * 二-28：步 2（跨源）收集结果——按链序收集的各源命中＋（若有）中止收集的密钥问题。
     * 收集器按串行版同一点停：探针桶 note 返回首选即停收；某源抛密钥问题时，
     * 先收的命中照常在列表里，调用方按原序 note 完后在同一点把密钥问题抛出去，
     * 与串行版「在那个源处中止并向上抛」逐点一致。
     */
    private static class CrossCollect {
        final List<MusicLyric> lyrics = new ArrayList<>();
        LxKeyIssue keyIssue;
    }

    /** 次级（包含级）待入列版本：歌词本体＋命中当场定好的瓜子等级（入列时一并带进 LyricVersion） */
    private static class PendingLyric {
        final MusicLyric lyric;
        final double gzLevel;
        /** 候选歌元数据（跨源命中当场带下来，随版本进弹窗行展示，见 LyricVersion 同名字段） */
        final String candidateTitle;
        final String candidateArtist;
        final int durationDeltaSec;

        PendingLyric(MusicLyric lyric, double gzLevel) {
            this(lyric, gzLevel, null, null, 0);
        }

        PendingLyric(MusicLyric lyric, double gzLevel, String candidateTitle, String candidateArtist,
                     int durationDeltaSec) {
            this.lyric = lyric;
            this.gzLevel = gzLevel;
            this.candidateTitle = candidateTitle;
            this.candidateArtist = candidateArtist;
            this.durationDeltaSec = durationDeltaSec;
        }
    }

    /**
     * 步 2 收集全程。自动取词（force＝false）：单遍原口径（每源 matchSong 先精确后包含），
     * 行为与原串行版逐点一致。手动搜词（force，二-8）：先跑精确遍——跨全部查询×全部源
     * 只认精确级对歌；精确遍有任一命中（或撞密钥问题）就不再跑包含遍，包含级只在
     * 全程无精确命中时作次级回落。原松门槛正在此处：matchSong 的两段只在单个源的
     * 候选表内分先后，跨源时排在前面的源一张包含级命中（如前缀歌）就会先入桶、
     * 甚至命中首选立即返回，后面源的精确命中永远轮不到——单表内精确优先≠跨源精确优先。
     */
    /**
     * 步 2 收集。exactOnly（待裁12）：LyricPrefetch 在 force 时传 true——本收集只跑
     * 精确遍，包含遍由 lyricRich 在步 1 包含级之后按步序补跑（跨步精确优先）；
     * 非 force 时 exactOnly 不起作用（单遍 MATCH_ANY 原口径）。
     */
    private static CrossCollect collectCross(MusicTrack track, MusicSource self, List<LocalTrackMeta.Query> queries, boolean force, boolean exactOnly) {
        CrossCollect out = new CrossCollect();
        LyricPick probe = new LyricPick(MusicStore.getLyricPriority());
        if (!force) {
            collectCrossPass(track, self, queries, MATCH_ANY, probe, out);
            return out;
        }
        collectCrossPass(track, self, queries, MATCH_EXACT, probe, out);
        if (out.keyIssue != null || hasRawEntry(out.lyrics)) return out;
        if (exactOnly) return out;
        collectCrossPass(track, self, queries, MATCH_CONTAINS, probe, out);
        return out;
    }

    /** 精确遍收口判据：只有带正文的入列才算真命中——无正文的精确命中（纯音译等）
     * LyricPick 全程不取，若按「列表非空」收口，包含遍里别家的完整词永远没机会被问。 */
    private static boolean hasRawEntry(List<MusicLyric> lyrics) {
        for (MusicLyric l : lyrics) if (hasRaw(l)) return true;
        return false;
    }

    /** 步 2 单遍收集：查询序列外层×其它源内层，命中按原序入列、探针桶命中首选即停；level 指定级别时只认该级对歌 */
    private static void collectCrossPass(MusicTrack track, MusicSource self, List<LocalTrackMeta.Query> queries,
                                         int level, LyricPick probe, CrossCollect out) {
        for (LocalTrackMeta.Query q : queries) {
            for (MusicSource other : SOURCES) {
                if (other == self || LocalMusicSource.PLATFORM.equals(other.platform())) continue;
                try {
                    MusicTrack target = matchTarget(track, q.matchTitle);
                    List<MusicTrack> candidates = other.search(q.keyword);
                    MusicTrack hitTrack = level == MATCH_ANY
                            ? matchSong(target, candidates)
                            : matchSongAtLevel(target, candidates, level);
                    if (hitTrack == null) continue;
                    MusicLyric lrc = normalizeRich(richOf(other, hitTrack));
                    if (lrc == null) continue;
                    if (lrc.sourceName == null || lrc.sourceName.isEmpty()) {
                        lrc.sourceName = siteNameForPlatform(hitTrack.platform);
                    }
                    out.lyrics.add(lrc);
                    if (probe.note(lrc) != null) return;
                } catch (Throwable t) {
                    try {
                        rethrowKeyIssue(t);
                    } catch (LxKeyIssue k) {
                        out.keyIssue = k;
                        return;
                    }
                    SpiderDebug.log("music", "lyricRich alt search error platform=%s %s", other.platform(), t.getMessage());
                }
            }
        }
    }

    /** 步 3 收集（原串行循环的收集版）：逐个 FreeJs 源问歌词型插件，命中按源序入列 */
    private static List<MusicLyric> collectLyricPlugins(MusicTrack track) {
        List<MusicLyric> out = new ArrayList<>();
        for (MusicSource other : SOURCES) {
            if (!(other instanceof FreeJsMusicSource)) continue;
            try {
                MusicLyric lrc = normalizeRich(((FreeJsMusicSource) other).lyricFromLyricPluginsRich(track));
                if (lrc != null) out.add(lrc);
            } catch (Throwable t) {
                SpiderDebug.log("music", "lyricRich plugin search error %s", t.getMessage());
            }
        }
        return out;
    }

    /**
     * 二-28 歌词回退链并行预取：步 2（跨源收集）／3（歌词型插件）／4（Worker）／5（平台
     * 直连）／5.5（酷我行级）／6（AMLL）互不依赖，一次性提交 Task.largeExecutor 并行跑，
     * lyricRich 仍按原步序逐个 await＋note。线程安全：FreeJS 桥每插件独占单线程
     * QuickJS 上下文，并行步对同桥的调用在桥内单线程执行器排队、不并发进 JS；
     * Worker／平台／酷我／AMLL 客户端都是每次调用新建实例，无共享可变状态；
     * SOURCES 是 CopyOnWriteArrayList，只读遍历安全。步 5／5.5／6 原串行版的
     * try/catch 收口搬进各自任务内，日志文案与原来一致。
     */
    private static class LyricPrefetch {
        /**
         * 待裁18：单步等待总封顶——高于最慢一步的内部封顶（Worker 连接 10 秒＋读 25 秒），
         * 只作防挂死的天花板，不替代各步自己的超时；超时按该步无结果处理并撤下任务。
         */
        private static final long AWAIT_CAP_MS = 45_000;

        private final long gen;
        private final String callKey;
        private final Future<CrossCollect> cross;
        private final Future<List<MusicLyric>> plugins;
        private final Future<MusicLyric> cf;
        private final Future<MusicLyric> platform;
        private final Future<MusicLyric> kuwo;
        private final Future<MusicLyric> amll;

        private LyricPrefetch(long gen, String callKey, Future<CrossCollect> cross, Future<List<MusicLyric>> plugins,
                              Future<MusicLyric> cf, Future<MusicLyric> platform,
                              Future<MusicLyric> kuwo, Future<MusicLyric> amll) {
            this.gen = gen;
            this.callKey = callKey;
            this.cross = cross;
            this.plugins = plugins;
            this.cf = cf;
            this.platform = platform;
            this.kuwo = kuwo;
            this.amll = amll;
        }

        static LyricPrefetch start(MusicTrack track, MusicSource self, List<LocalTrackMeta.Query> queries, boolean force, long gen,
                                   java.util.function.Consumer<MusicLyric> cfEarly) {
            Future<CrossCollect> cross = Task.largeExecutor().submit(() -> collectCross(track, self, queries, force, force));
            Future<List<MusicLyric>> plugins = Task.largeExecutor().submit(() -> collectLyricPlugins(track));
            Future<MusicLyric> cf = Task.largeExecutor().submit(() -> {
                MusicLyric v = cfLyricWord(track);
                // 主来源先到先发布（仅展示先行，步 4 消费与挑选口径不变）
                if (v != null && cfEarly != null) {
                    try {
                        cfEarly.accept(v);
                    } catch (Throwable ignored) {
                    }
                }
                return v;
            });
            Future<MusicLyric> platform = Task.largeExecutor().submit(() -> {
                try {
                    return new PlatformWordLyricSource().lyricRich(track);
                } catch (Throwable t) {
                    SpiderDebug.log("music", "lyricRich platform word error %s", t.getMessage());
                    return null;
                }
            });
            Future<MusicLyric> kuwo = Task.largeExecutor().submit(() -> {
                try {
                    return kuwoLineLyric(track);
                } catch (Throwable t) {
                    SpiderDebug.log("music", "lyricRich kuwo line error %s", t.getMessage());
                    return null;
                }
            });
            Future<MusicLyric> amll = MusicStore.isAmllEnabled()
                    ? Task.largeExecutor().submit(() -> {
                        try {
                            return normalizeRich(new AmllMusicLyricSource().lyricRich(track));
                        } catch (Throwable t) {
                            SpiderDebug.log("music", "lyricRich amll error %s", t.getMessage());
                            return null;
                        }
                    })
                    : null;
            return new LyricPrefetch(gen, lyricCallKey(track), cross, plugins, cf, platform, kuwo, amll);
        }

        CrossCollect cross() {
            CrossCollect r = await(cross);
            return r != null ? r : new CrossCollect();
        }

        List<MusicLyric> pluginLyrics() {
            List<MusicLyric> r = await(plugins);
            return r != null ? r : Collections.emptyList();
        }

        MusicLyric cf() {
            return await(cf);
        }

        MusicLyric platform() {
            return await(platform);
        }

        MusicLyric kuwo() {
            return await(kuwo);
        }

        MusicLyric amll() {
            return amll == null ? null : await(amll);
        }

        private <T> T await(Future<T> f) {
            if (f == null) return null;
            // 待裁14：本代次已被同一首歌的新取词请求顶替——不等了，撤下任务、迟到
            // 结果直接弃，不许孤儿任务继续占线程池与桥队列拖住新请求的取词。
            // 代次按歌分键：别的歌的取词（上一首没收尾的链）不顶替本歌。
            if (!isLatestLyricCall(gen, callKey)) {
                f.cancel(true);
                return null;
            }
            try {
                return f.get(AWAIT_CAP_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Throwable t) {
                // 含超时（TimeoutException）与被撤（CancellationException）：按该步无结果处理
                f.cancel(true);
                SpiderDebug.log("music", "lyric prefetch error %s", t.getMessage());
                return null;
            }
        }

        /** 待裁14：调用方已收口（命中／中止／被顶替）——还没轮到的预取任务全部撤下，
         * 结果本就按既定口径丢弃不回写，撤下只是不让它们白占线程池与桥队列。 */
        void abandon() {
            cancelQuietly(cross);
            cancelQuietly(plugins);
            cancelQuietly(cf);
            cancelQuietly(platform);
            cancelQuietly(kuwo);
            cancelQuietly(amll);
        }

        private static void cancelQuietly(Future<?> f) {
            if (f != null) f.cancel(true);
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

    /** 版本面板阶段一专用：只问主来源 Worker（定案口径：Worker 是歌词主来源），
     * 回来即上桌，不等全量聚合的跨源串行链收尾。 */
    public static List<LyricVersion> lyricWorkerAll(MusicTrack track) {
        List<LyricVersion> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (track == null) return out;
        try {
            cfLyricVersionsAll(track, out, seen);
        } catch (Throwable ignored) {
        }
        // 阶段一列表按定级降序（稳定排序，与面板头「按匹配度排列」同口径）
        java.util.Collections.sort(out, (a, b) ->
                LyricVersionOrdering.compare(a, b, MusicStore.LYRIC_PRIORITY_FOLLOW_SOURCE));
        return out;
    }

    /**
     * lyricRichAll 步 2 单遍收集：level 指定级别时只收该级对歌的版本（MATCH_ANY 走
     * matchSong 原口径）。sink 非空时命中先进次级列（不入 seen、不进主列），由调用方
     * 在全链收完后逐个过 addVersion 殿后追加；sink 为空时直接 addVersion 进主列。
     * 返回 false＝本遍有源抛错（聚合记不完整，与原内联版 catch 后 complete＝false 同口径；
     * 密钥问题照旧解包重抛、中止整链，与原内联版一致）。
     */
    private static boolean collectAllCrossPass(MusicTrack track, MusicSource self, List<LocalTrackMeta.Query> queries,
                                               int level, List<LyricVersion> out, Set<String> seen, List<PendingLyric> sink) {
        boolean complete = true;
        for (LocalTrackMeta.Query q : queries) {
            for (MusicSource other : SOURCES) {
                if (other == self || LocalMusicSource.PLATFORM.equals(other.platform())) continue;
                try {
                    MusicTrack target = matchTarget(track, q.matchTitle);
                    List<MusicTrack> candidates = other.search(q.keyword);
                    MusicTrack hit = level == MATCH_ANY
                            ? matchSong(target, candidates)
                            : matchSongAtLevel(target, candidates, level);
                    if (hit == null) continue;
                    // 对歌级别在命中当场定：分级遍即本遍级别，不分级遍按单候选口径补判，
                    // 随版本带下去供弹窗排序/打分（sink 次级列只由包含遍喂，级别恒包含级）
                    int hitLevel = level == MATCH_ANY ? matchLevel(target, hit) : level;
                    // 瓜子等级在命中当场按瓜子输入定级（目标＝在播曲目本身、候选＝命中歌，
                    // 标题／歌手／时长／源内 id 齐全）：选歌仍走星辰 matchSong（定案不动），
                    // 瓜子只给定级与分数；被瓜子门淘汰（0 级）的版本星辰照收、弹窗不显分数
                    double gzLevel = GzLyricMatch.level(track.title, track.artist, track.duration,
                            hit.title, hit.artist, hit.duration, hit.id);
                    MusicLyric alt = normalizeRich(richOf(other, hit));
                    if (alt != null && (alt.sourceName == null || alt.sourceName.isEmpty())) {
                        alt.sourceName = siteNameForPlatform(hit.platform);
                    }
                    // 候选歌元数据随版本带下去（弹窗行显候选自己的歌名·歌手与时长偏差，瓜子 LyricsCandidate 同名字段）：
                    // 时长差＝|候选时长−目标时长| 秒（双方时长都已知才算，未知记 0＝不显）
                    int deltaSec = track.duration > 0 && hit.duration > 0
                            ? (int) Math.round(Math.abs(hit.duration - track.duration) / 1000.0) : 0;
                    if (sink != null) {
                        if (alt != null && alt.hasLyric()) {
                            sink.add(new PendingLyric(alt, gzLevel, hit.title, hit.artist, deltaSec));
                        }
                    } else {
                        addVersion(out, seen, alt, hitLevel, gzLevel, hit.title, hit.artist, deltaSec);
                    }
                } catch (Throwable t) {
                    rethrowKeyIssue(t);
                    complete = false;
                    SpiderDebug.log("music", "lyricRichAll alt search error platform=%s %s", other.platform(), t.getMessage());
                }
            }
        }
        return complete;
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
            // 本源自有词即本曲本源产出：星辰级别记精确级（不是跨源匹配，不存在对错歌问题）；
            // 瓜子等级照瓜子自有候选口径记 3.0（瓜子自有／在用候选构造分恒 1.0）
            addVersion(out, seen, ownRich, MATCH_EXACT, 3.0);
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
        // 二-8：手动搜词（force）时本步分两级收——精确级先入主列，包含级先攒在次级列，
        // 等全链收完、主列分区排定后才逐个过 addVersion 追加到最末（去重仍走 seen，
        // 与主列／后续步同文版本不重复）：包含级版本仍可列给用户手动选，但不许排在
        // 精确命中前面。自动路径（force＝false）单遍原口径、次级列恒空，行为不变。
        List<PendingLyric> containsLyrics = new ArrayList<>();
        if (force) {
            if (!collectAllCrossPass(track, s, queries, MATCH_EXACT, out, seen, null)) complete = false;
            if (!collectAllCrossPass(track, s, queries, MATCH_CONTAINS, out, seen, containsLyrics)) complete = false;
        } else {
            if (!collectAllCrossPass(track, s, queries, MATCH_ANY, out, seen, null)) complete = false;
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
        // 4. Worker 聚合（主来源，已接线）：cfLyricVersionsAll 带候选元数据合并，
        // 瓜子定级与时长偏差本地算；逐字签仍由 addVersion 本地解析正文判定，不认回包 kind 宣称。
        try {
            cfLyricVersionsAll(track, out, seen);
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
        if (MusicStore.LYRIC_PRIORITY_FOLLOW_SOURCE.equals(priority)) {
            // 二-8：次级（包含级）版本殿后，跟随来源模式下同样不许排在精确命中前面；
            // 次级列只由包含遍喂入，星辰级别在此明写包含级，瓜子等级用命中当场定好的那份
            for (PendingLyric deferred : containsLyrics) {
                addVersion(out, seen, deferred.lyric, MATCH_CONTAINS, deferred.gzLevel,
                        deferred.candidateTitle, deferred.candidateArtist, deferred.durationDeltaSec);
            }
            return new LyricRichAllResult(out, complete);
        }
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
        // 分区内按定级降序（稳定排序）：分区序仍归优先级设置管，区内对齐面板头
        // 「按匹配度排列」——旧口径区内是收集序，有定级的版本也不按分排。
        java.util.Comparator<LyricVersion> byLevel = (a, b) ->
                LyricVersionOrdering.compare(a, b, MusicStore.LYRIC_PRIORITY_FOLLOW_SOURCE);
        java.util.Collections.sort(amll, byLevel);
        java.util.Collections.sort(word, byLevel);
        java.util.Collections.sort(line, byLevel);
        java.util.Collections.sort(plain, byLevel);
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
        // 二-8：次级（包含级）版本殿后追加（自动路径次级列恒空，此处为空操作）；级别口径同上
        for (PendingLyric deferred : containsLyrics) {
            addVersion(out, seen, deferred.lyric, MATCH_CONTAINS, deferred.gzLevel,
                        deferred.candidateTitle, deferred.candidateArtist, deferred.durationDeltaSec);
        }
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

    /** B2：版本收集合并——无词跳过；rawLrc.trim() 去重（纯译文／纯音译版本用三格拼接去重）；档位由解析结果判定。
     * 本重载用于产出链拿不到对歌判定的步（本源其它插件／歌词型插件／Worker／平台直连／酷我／AMLL）：
     * 星辰级别按包含级兜底、瓜子等级记未定级，都不冒充（弹窗排序与匹配度据此拉开真差异）。 */
    private static void addVersion(List<LyricVersion> out, Set<String> seen, MusicLyric lyric) {
        addVersion(out, seen, lyric, MATCH_CONTAINS, LyricVersion.GZ_LEVEL_UNKNOWN);
    }

    /** 带对歌级别的收集合并：级别只在产出当场由对歌判定带下来（本源自有词＝精确级、跨源命中＝命中级别），
     * 去重命中已有版本时首个产出为准，后来同文版本不改级别（与「同文只留首个」同口径）。 */
    private static void addVersion(List<LyricVersion> out, Set<String> seen, MusicLyric lyric, int matchLevel) {
        addVersion(out, seen, lyric, matchLevel, LyricVersion.GZ_LEVEL_UNKNOWN);
    }

    /** 全级别收集合并：gzLevel 为瓜子等级（GzLyricMatch 定级；未定级传 GZ_LEVEL_UNKNOWN）。 */
    private static void addVersion(List<LyricVersion> out, Set<String> seen, MusicLyric lyric,
                                   int matchLevel, double gzLevel) {
        addVersion(out, seen, lyric, matchLevel, gzLevel, null, null, 0);
    }

    /** 带候选歌元数据的收集合并：candidateTitle/Artist/durationDeltaSec 只在跨源命中当场拿得到，
     * 其余产出步走上面的重载（null/0，弹窗行回落当前歌曲、不显偏差）。 */
    private static void addVersion(List<LyricVersion> out, Set<String> seen, MusicLyric lyric,
                                   int matchLevel, double gzLevel, String candidateTitle,
                                   String candidateArtist, int durationDeltaSec) {
        if (lyric == null || !lyric.hasLyric()) return;
        String key = lyric.rawLrc == null ? "" : lyric.rawLrc.trim();
        if (key.isEmpty()) {
            key = (lyric.translation == null ? "" : lyric.translation.trim())
                    + "|" + (lyric.romanization == null ? "" : lyric.romanization.trim());
        }
        if (key.isEmpty() || !seen.add(key)) return;
        boolean[] kinds = versionKinds(lyric);
        out.add(new LyricVersion(lyric, lyric.sourceName, kinds[0], kinds[1], matchLevel, gzLevel,
                candidateTitle, candidateArtist, durationDeltaSec));
    }

    /** 版本档位判定（解析正文）：[0]＝任一行带逐字时间、[1]＝任一行带行时间戳 */
    private static boolean[] versionKinds(MusicLyric lyric) {
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
        return new boolean[]{word, line};
    }

    /**
     * 在用词条（瓜子 buildDisplayedLyricCandidate 同口径）：当前实际在播的那份词本身即一个版本，
     * 构造分恒满分（瓜子等级 3.0／星辰级别精确级），来源名由调用方给出（rich 自带／旁路词＝本地／
     * 纯文本兜底＝曲目来源名）。播放页据此保证「播着词却暂无版本」不再出现；聚合列表里已有
     * 同文版本时以聚合版为准（带真实定级），本词条只补聚合缺失／未回的空窗。
     */
    public static LyricVersion displayedVersion(MusicLyric lyric, String sourceName) {
        if (lyric == null || !lyric.hasLyric()) return null;
        boolean[] kinds = versionKinds(lyric);
        String source = sourceName != null ? sourceName : lyric.sourceName;
        return new LyricVersion(lyric, source, kinds[0], kinds[1],
                LyricVersion.MATCH_LEVEL_EXACT, 3.0);
    }

    /** platform → 站点显示名（版本来源标注用；查不到返回空） */
    public static String sourceDisplayName(String platform) {
        return siteNameForPlatform(platform);
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

    /** 对歌级别：不中／包含级（歌名互含＋歌手关）／精确级（歌名归一化全等＋歌手关）；歌手关与 matchSong 原口径同一道（占位歌手不参与、有歌手时互含） */
    public static final int MATCH_NONE = 0;
    public static final int MATCH_CONTAINS = 1;
    public static final int MATCH_EXACT = 2;
    /** 不分级口径：matchSong 的「先精确遍、再包含遍」 */
    public static final int MATCH_ANY = -1;

    /** 单个候选的对歌级别（matchSong 两段判定的单候选版，判定条件逐项与原两段一致） */
    public static int matchLevel(MusicTrack target, MusicTrack candidate) {
        if (target == null || candidate == null) return MATCH_NONE;
        String title = normalizeSong(target.title);
        if (title.isEmpty()) return MATCH_NONE;
        String ct = normalizeSong(candidate.title);
        if (ct.isEmpty()) return MATCH_NONE;
        // 占位歌手（<unknown>／unknown 类）从不参与对歌：它不是真歌手，拿它卡人只会把对的歌也拒掉
        String artist = LocalTrackMeta.isPlaceholderArtist(target.artist) ? "" : normalizeSong(target.artist);
        if (!artistMatch(artist, candidate.artist)) return MATCH_NONE;
        if (ct.equals(title)) return MATCH_EXACT;
        if (ct.contains(title) || title.contains(ct)) return MATCH_CONTAINS;
        return MATCH_NONE;
    }

    /** 只认指定级别的第一个候选（保列表序）；无该级命中返回 null */
    public static MusicTrack matchSongAtLevel(MusicTrack target, List<MusicTrack> candidates, int level) {
        if (target == null || candidates == null) return null;
        if (normalizeSong(target.title).isEmpty()) return null;
        for (MusicTrack c : candidates) {
            if (matchLevel(target, c) == level) return c;
        }
        return null;
    }

    /** 歌名包含＋（有歌手时）歌手包含才认，防止拿错歌的词；归一化去标点/空白/大小写 */
    public static MusicTrack matchSong(MusicTrack target, List<MusicTrack> candidates) {
        if (target == null || candidates == null) return null;
        if (normalizeSong(target.title).isEmpty()) return null;
        // 先精确：归一化歌名完全相等才认（本地歌无歌手时尤其重要，否则“来不及爱你”会先撞上“来不及”这种前缀歌）
        MusicTrack exact = matchSongAtLevel(target, candidates, MATCH_EXACT);
        if (exact != null) return exact;
        // 再包含：歌名互含才认（旧口径，兜底）
        return matchSongAtLevel(target, candidates, MATCH_CONTAINS);
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
        MusicTrack hit = onlineMeta(track);
        return hit == null ? null : hit.cover.trim();
    }

    /**
     * 在线对歌补全：按净歌名逐级搜在线源（跳过本地／设备源），精确对上同一首歌就返回那首
     * 在线曲（歌手/专辑/封面全套）。本地无标签文件（歌手/专辑/封面全空）与在线无封面曲共用。
     * 此前只有 onlineCover 一个出口且全仓零调用——补全链建了没接线，播放页从没调过（他点名）。
     */
    public static MusicTrack onlineMeta(MusicTrack track) {
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
                            if (hit != null && hit.cover != null && !hit.cover.trim().isEmpty()) return hit;
                        }
                    } else {
                        MusicTrack hit = matchSong(target, other.search(q.keyword));
                        if (hit != null && hit.cover != null && !hit.cover.trim().isEmpty()) return hit;
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
