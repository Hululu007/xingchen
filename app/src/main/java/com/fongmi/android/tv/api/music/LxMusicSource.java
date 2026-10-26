package com.fongmi.android.tv.api.music;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicQuality;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicSource;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.quickjs.bean.Req;
import com.fongmi.quickjs.utils.Connect;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.Response;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * 落雪真源：实现播放器侧 MusicSource SPI，platform="lx"。
 * 搜索是壳子的（LxPlatformSearch 按平台搜），脚本只做地址解析（LxBridge 的
 * EVENT_NAMES.request）。搜索排按当前启用的落雪脚本声明的平台取并集生成：
 * 一个平台一项合成站点（srcUrl="lx://&lt;平台&gt;"），与脚本数量无关。
 * 播放取链：按启用顺序遍历声明了该平台的落雪脚本，逐个解析、成功即停。
 * 歌词：平台歌词接口优先，取不到再试声明了 lyric 动作的脚本。
 */
public class LxMusicSource implements MusicSource {

    public static final String PLATFORM = "lx";
    private static final String SITE_PREFIX = "lx://";

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public List<MusicSite> sites() {
        return MusicSourceManager.get().enabledSitesOfFormat(MusicSourceManager.FORMAT_LX);
    }

    /**
     * 搜索排：所有启用落雪脚本声明、且壳子已实现搜索的平台并集，一平台一项。
     * 需要加载脚本读声明（阻塞，请在子线程调）。
     */
    @Override
    public List<MusicSite> searchableSites() {
        List<MusicSite> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        // 单插件屏蔽平台：某平台只有在至少一个声明它的插件没屏蔽它时才进搜索排——
        // 全部插件都屏蔽了该平台还摆出来，点进去搜得到却永远取不了链（摆设第二张脸）
        for (MusicSite plugin : sites()) {
            try {
                LxBridge b = MusicSourceManager.get().lxBridgeOf(plugin);
                if (b == null) continue;
                for (Map.Entry<String, LxBridge.LxSource> e : b.sources().entrySet()) {
                    String key = e.getKey();
                    if (!LxPlatformSearch.supported(key)) continue;
                    if (!e.getValue().actions.contains("musicUrl")) continue;
                    if (com.fongmi.android.tv.music.MusicStore.isPlatformBlocked(plugin.srcUrl, key)) continue;
                    if (seen.add(key)) {
                        MusicSite s = new MusicSite(PLATFORM, LxPlatformSearch.platformName(key), "", SITE_PREFIX + key);
                        result.add(s);
                    }
                }
            } catch (Throwable t) {
                SpiderDebug.log("music", "lx searchable site error site=%s %s", plugin.name, t.getMessage());
            }
        }
        return result;
    }

    /** 合并搜：各平台各搜一次汇合（歌词兜底跨源搜索走这里） */
    @Override
    public List<MusicTrack> search(String keyword) {
        List<MusicTrack> all = new ArrayList<>();
        for (MusicSite s : searchableSites()) {
            try {
                all.addAll(searchBySite(s, keyword));
            } catch (Throwable t) {
                SpiderDebug.log("music", "lx search error site=%s %s", s.name, t.getMessage());
            }
        }
        return all;
    }

    @Override
    public List<MusicTrack> searchBySite(MusicSite site, String keyword) throws Exception {
        return searchBySite(site, keyword, 1);
    }

    /** 分页版：页码透传壳子平台搜索（五平台接口都收页码，旧实现全写死第一页） */
    @Override
    public List<MusicTrack> searchBySite(MusicSite site, String keyword, int page) throws Exception {
        String key = platformKeyOf(site);
        if (key.isEmpty()) return Collections.emptyList();
        return LxPlatformSearch.search(key, keyword, Math.max(1, page));
    }

    @Override
    public List<MusicTrack> tracks(MusicSite site) {
        return Collections.emptyList();
    }

    @Override
    public List<MusicChart> charts(MusicSite site) {
        return Collections.emptyList();
    }

    @Override
    public List<MusicTrack> chartTracks(MusicChart chart) {
        return Collections.emptyList();
    }

    /**
     * P-3d 源内死插件黑名单：同曲一次取链链内（多档回落／仓库回落多次进 playUrl）已死过的插件不再重进；
     * 只记当前一曲（单缓存：换歌即清），绝不跨歌拉黑好插件；第一波仓库层指派拦截保留，两层不重复拦同一通道
     * （仓库层拦指派单试，本层拦源内遍历与 playUrlWithPlugin 单步）。
     * 第二波堵-4：死键＝插件＋档位（请求档的落雪串，如 flac／320k，与仓库层插件＋档位同口径，
     * FreeJS 侧插件＋MusicQuality key 不动）——某档死掉不许连坐其它档；死集用并发集，
     * 换键清集＋查／记包在同一 DEAD_LOCK 同步块里（调用方含服务取链、预加载、面板探链三线程，
     * 旧静态 HashSet 无同步会丢标记／串歌）；探链（probe）只读不记死。
     */
    private static final Object DEAD_LOCK = new Object();
    private static String deadTrackKey = "";
    private static final Set<String> deadPlayUrlPlugins = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static boolean isDeadPlugin(String trackKey, String srcUrl, String qualityKey) {
        synchronized (DEAD_LOCK) {
            if (trackKey == null || !trackKey.equals(deadTrackKey)) {
                deadTrackKey = trackKey == null ? "" : trackKey;
                deadPlayUrlPlugins.clear();
                return false;
            }
            return srcUrl != null && deadPlayUrlPlugins.contains(deadKey(srcUrl, qualityKey));
        }
    }

    private static void markDeadPlugin(String trackKey, String srcUrl, String qualityKey) {
        synchronized (DEAD_LOCK) {
            if (trackKey == null || !trackKey.equals(deadTrackKey)) {
                deadTrackKey = trackKey == null ? "" : trackKey;
                deadPlayUrlPlugins.clear();
            }
            if (srcUrl != null) deadPlayUrlPlugins.add(deadKey(srcUrl, qualityKey));
        }
    }

    private static String deadKey(String srcUrl, String qualityKey) {
        return srcUrl + "|" + (qualityKey == null ? "" : qualityKey);
    }

    /** 第二波堵-4：新一次取链开始时清源内死集（服务 prepareTrack 入口与仓库层一起调，不只清仓库层）。 */
    public static void clearDeadPlayUrlAttempt() {
        synchronized (DEAD_LOCK) {
            deadTrackKey = "";
            deadPlayUrlPlugins.clear();
        }
    }

    /** 单个落雪脚本取链上限：一个脚本卡住不许拖垮整条取链（对差清单 D2）。
     * 8 秒是误判脚本形态定的：聚合脚本按落雪语义是多后端并发回退，到星辰同步
     * 垫片里变全串行——本机同脚本同歌复现串行总耗时 10199 毫秒（星海 2.3s＋
     * Huibq 2.5s＋溯音 3.2s＋聆川 2.2s），他网络下更慢（日志实测星海一发 4 秒、
     * Huibq 一发 10 秒），8 秒一到必砍、错误还是无消息的 TimeoutException，
     * 日志只剩 error null（2026-10-26 真机 tx/kg/mg 全灭实证）。改 18 秒：
     * 仍在取链总预算 20 秒与桥内层 40 秒之内，wait 仍与 remain 取小。 */
    private static final long PLUGIN_TIMEOUT_MS = 18000;
    /** 脚本链实测验链的探测超时（毫秒）：只读响应码即断、不读体，见 probeUrlDead 口径 */
    private static final int PROBE_TIMEOUT_MS = 3000;
    /** 取链总预算（与播放服务 20 秒看门狗同口径）：到点还没出链就按失败返回 */
    private static final long TOTAL_TIMEOUT_MS = 20000;

    @Override
    public String playUrl(MusicTrack track, int quality) throws Exception {
        return playUrl(track, quality, false);
    }

    /** 探链口径：probe＝true 时只读地址、不记死（面板探链用）；死键按请求档记，见字段注释。 */
    @Override
    public String playUrl(MusicTrack track, int quality, boolean probe) throws Exception {
        Parsed p = parse(track);
        if (p == null) return null;
        long deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS;
        JsonObject musicInfo = musicInfo(track, p);
        if (LxPlatformSearch.QQ.equals(p.platform) && TextUtils.isEmpty(optStr(musicInfo, "strMediaMid"))) {
            // QQ 取链要 strMediaMid，搜索结果里没有，取链前补一次详情
            String mediaMid = LxPlatformSearch.qqMediaMid(optStr(musicInfo, "songmid"));
            if (!TextUtils.isEmpty(mediaMid)) musicInfo.addProperty("strMediaMid", mediaMid);
        }
        String want = lxQuality(MusicQuality.fromInt(quality));
        String deadKey = MusicQueue.trackKey(track);
        // 实测验链结果只在本次取链内按 URL 缓存：同一条链被多个脚本重复给出时不反复敲
        Map<String, Boolean> probeCache = new HashMap<>();
        for (MusicSite plugin : sites()) {
            // 服务看门狗／停播取消取链时中断本线程：中断后不许继续逐个脚本试下去
            if (Thread.currentThread().isInterrupted()) return null;
            if (System.currentTimeMillis() >= deadline) {
                SpiderDebug.log("music", "lx playUrl total timeout");
                break;
            }
            // P-3d：本曲已死过的插件不再重进（多档回落会多次进本循环）；探链只读不记死，
                // 但仍尊重已死标记（不替真实取链翻案），见字段注释。
            if (isDeadPlugin(deadKey, plugin.srcUrl, want)) continue;
            // 单插件屏蔽平台：该插件屏蔽了本歌内容平台（p.platform）就不许它取链——
            // 旧实现落雪侧从头到尾不查，屏蔽对落雪插件完全是摆设
            if (com.fongmi.android.tv.music.MusicStore.isPlatformBlocked(plugin.srcUrl, p.platform)) continue;
            LxBridge b;
            try {
                b = MusicSourceManager.get().lxBridgeOf(plugin);
            } catch (Throwable t) {
                continue;
            }
            if (b == null || !b.supports(p.platform, "musicUrl")) continue;
            String q = pickQuality(b, p.platform, want);
            long remain = deadline - System.currentTimeMillis();
            long wait = Math.min(PLUGIN_TIMEOUT_MS, Math.max(0, remain));
            if (wait <= 0) break;
            try {
                // 单脚本 8 秒上限：get 到点就放弃这个脚本换下一个；P3：超时必须 cancel(true)
                // 取消底层任务，不只放弃等待——否则 supplyAsync 线程继续占共享池，连续坏歌
                // 即可占满 5 条、后续取链排队等死（本层改走音乐专用池见 Task.submitMusic 注释
                // 的调用方，桥内层 40 秒上限见 LxBridge.request 的取消）
                long lxT0 = System.currentTimeMillis();
                java.util.concurrent.Future<String> lxFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return b.request(p.platform, "musicUrl", musicInfo, q);
                    } catch (Throwable t) {
                        throw new CompletionException(t);
                    }
                }, Task.musicExecutor());
                String r;
                try {
                    r = lxFuture.get(wait, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    lxFuture.cancel(true);
                    throw te;
                }
                String url = extractUrl(r);
                if (!TextUtils.isEmpty(url) && LxPlatformSearch.QQ.equals(p.platform) && isFilelessUrl(url)) {
                    // QQ 幻影链（已死后端模板拼的只有域名、没有文件段的地址）：当空链处理，
                    // 继续换脚本，最终回落官方 vkey 兜底，不许拿它当成功链交出去
                    SpiderDebug.log("music", "lx playUrl qq fileless url dropped plugin=%s", plugin.name);
                    url = null;
                }
                if (!TextUtils.isEmpty(url) && isProbeDeadUrl(url, probeCache)) {
                    // 实测验链定罪的死链：带文件名的模板拼链形态判定拦不住，真敲一次
                    // 回 403 类才落网。当空链处理，继续换脚本，全灭后回落兜底段
                    SpiderDebug.log("music", "lx playUrl probe-dead url dropped plugin=%s", plugin.name);
                    url = null;
                }
                if (!TextUtils.isEmpty(url)) {
                    // C5 真实取链耗时（最近一次口径）：记到实际出链的那个脚本名下
                    com.fongmi.android.tv.music.MusicStore.notePluginLatency(
                            PLATFORM, plugin.srcUrl, System.currentTimeMillis() - lxT0, false);
                    // 三-42 渠道旁路：记遍历命中的就是这个脚本（仓库层随后补渠道枚举；
                    // 探链不记，与不记死同口径）。只记不改取链。
                    if (!probe) com.fongmi.android.tv.music.MusicRepository.notePlayChannelSrc(
                            deadKey, plugin.srcUrl);
                    return url;
                }
                // P-3d：出空链也算死一次，本曲后续回落不再重进该插件；探链不记死。
                if (!probe) markDeadPlugin(deadKey, plugin.srcUrl, want);
            } catch (Throwable t) {
                if (!probe) markDeadPlugin(deadKey, plugin.srcUrl, want);
                rethrowKeyIssue(t);
                SpiderDebug.log("music", "lx playUrl error plugin=%s %s", plugin.name, t.getMessage());
            }
        }
        if (LxPlatformSearch.QQ.equals(p.platform)) {
            // tx 官方兜底（通用层一处，搜索/榜单/歌单导入的 tx 歌都经此）：脚本全灭、
            // 回空、回幻影链、乃至一个脚本都没装时，已存 QQ 登录态就改走官方 vkey
            // 带态取链；未登录按 10 分钟节流弹统一登录（探链不弹），本次照常失败返回。
            String fallback = QqVkeyFetcher.playUrl(
                    optStr(musicInfo, "songmid"), optStr(musicInfo, "strMediaMid"), want);
            if (!TextUtils.isEmpty(fallback)) return fallback;
            if (!probe) QqVkeyFetcher.maybePromptLogin();
        }
        return null;
    }

    /**
     * 没有文件段的地址：域名后第一段路径为空或不带扩展名分隔点，不是可播的媒体
     * 文件地址（QQ 已死解析后端模板拼出的幻影链形态，如只有域名加残缺参数）。
     */
    private static boolean isFilelessUrl(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) return false;
        int segStart = url.indexOf('/', scheme + 3);
        if (segStart < 0) return true;
        segStart++;
        int end = url.length();
        int q = url.indexOf('?', segStart);
        if (q >= 0) end = Math.min(end, q);
        int h = url.indexOf('#', segStart);
        if (h >= 0) end = Math.min(end, h);
        String seg = url.substring(segStart, end);
        return seg.isEmpty() || seg.indexOf('.') < 0;
    }

    /**
     * 验链带缓存口：true＝实测定罪死链，false＝放行（含验不到）。缓存活在单次
     * 取链调用内（见两处出口旁的 probeCache），同一条链被多个脚本重复给出时只真敲一次。
     */
    private static boolean isProbeDeadUrl(String url, Map<String, Boolean> probeCache) {
        Boolean cached = probeCache.get(url);
        if (cached != null) return cached;
        boolean dead = probeUrlDead(url);
        probeCache.put(url, dead);
        return dead;
    }

    /**
     * 脚本链实测验链（isFilelessUrl 形态判定之后的第二道）：长青类已死后端不经
     * 服务器、本地按模板拼出带文件名的完整地址，形态上挑不出毛病，播放器真请求
     * 它回 403 零字节——取链若原样交出去，兜底段（官方 vkey＋登录弹窗）永远轮
     * 不到执行。接受前先真敲一次：GET 带 Range 只取首字节，走壳子 OkHttp 出口
     * （与 QqVkeyFetcher 同出口，吃代理/DoH/节点定向），跟随重定向看最终态，
     * 只读响应码即断开、不读体。
     * 口径只许定罪不许误杀：403／404／410 才算死链；2xx／3xx 最终态、其它状态
     * 码（416、5xx 等）、超时、网络异常一律放行——验不到不许杀真链（有些后端
     * 对无 Cookie 的探测请求回怪码、带播放器上下文却能播）。
     * 阻塞方法，只能在取链线程（音乐池）调，与 playUrl 同线程口径。
     */
    private static boolean probeUrlDead(String url) {
        try {
            JSONObject reqJson = new JSONObject();
            reqJson.put("method", "GET");
            JSONObject headers = new JSONObject();
            headers.put("Range", "bytes=0-0");
            reqJson.put("headers", headers);
            reqJson.put("timeout", PROBE_TIMEOUT_MS);
            reqJson.put("redirect", 1);
            Req req = Req.objectFrom(reqJson.toString());
            try (Response res = Connect.to(url, req).execute()) {
                int code = res.code();
                return code == 403 || code == 404 || code == 410;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 清理轮 R-C7：单插件取链（播放路由指派用）。只试 srcUrl 命中的那一个启用落雪脚本，
     * 不遍历其它脚本：取不到返回 null，由仓库按策略决定是回落还是停住，不在这里跨脚本。
     * 单步口径与 playUrl 的单次迭代一致：QQ mediaMid 补齐、档位 pickQuality、单脚本
     * 8 秒上限（超时 cancel(true)）、出链记 C5 耗时到该脚本名下；脚本未启用／不支持
     * 该平台 musicUrl 时返回 null（不是抛错）。密钥问题（LxKeyIssue）照旧重抛，不许吞。
     */
    public String playUrlWithPlugin(MusicTrack track, int quality, String srcUrl) throws Exception {
        return playUrlWithPlugin(track, quality, srcUrl, false);
    }

    /** 探链口径：probe＝true 时只读地址、不记死（仓库指派单试的探链用）。 */
    public String playUrlWithPlugin(MusicTrack track, int quality, String srcUrl, boolean probe) throws Exception {
        Parsed p = parse(track);
        if (p == null || srcUrl == null || srcUrl.isEmpty()) return null;
        MusicSite target = null;
        for (MusicSite plugin : sites()) {
            if (srcUrl.equals(plugin.srcUrl)) {
                target = plugin;
                break;
            }
        }
        if (target == null) return null;
        // 单插件屏蔽平台：指派单试同口径，目标插件屏蔽了本歌内容平台直接判空走仓库回落
        if (com.fongmi.android.tv.music.MusicStore.isPlatformBlocked(srcUrl, p.platform)) return null;
        // P-3d：仓库指派单试失败后回落到源内遍历时，本步不再重进同一死插件。
        String deadKey = MusicQueue.trackKey(track);
        // 实测验链结果只在本次取链内按 URL 缓存（同 playUrl 口径）
        Map<String, Boolean> probeCache = new HashMap<>();
        String want = lxQuality(MusicQuality.fromInt(quality));
        if (isDeadPlugin(deadKey, srcUrl, want)) return null;
        if (Thread.currentThread().isInterrupted()) return null;
        JsonObject musicInfo = musicInfo(track, p);
        if (LxPlatformSearch.QQ.equals(p.platform) && TextUtils.isEmpty(optStr(musicInfo, "strMediaMid"))) {
            String mediaMid = LxPlatformSearch.qqMediaMid(optStr(musicInfo, "songmid"));
            if (!TextUtils.isEmpty(mediaMid)) musicInfo.addProperty("strMediaMid", mediaMid);
        }
        LxBridge b;
        try {
            b = MusicSourceManager.get().lxBridgeOf(target);
        } catch (Throwable t) {
            return null;
        }
        if (b == null || !b.supports(p.platform, "musicUrl")) return null;
        String q = pickQuality(b, p.platform, want);
        try {
            long lxT0 = System.currentTimeMillis();
            java.util.concurrent.Future<String> lxFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return b.request(p.platform, "musicUrl", musicInfo, q);
                } catch (Throwable t) {
                    throw new CompletionException(t);
                }
            }, Task.musicExecutor());
            String r;
            try {
                r = lxFuture.get(PLUGIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                lxFuture.cancel(true);
                if (!probe) markDeadPlugin(deadKey, srcUrl, want);
                return null;
            }
            String url = extractUrl(r);
            if (!TextUtils.isEmpty(url) && isProbeDeadUrl(url, probeCache)) {
                // 实测验链定罪的死链（同 playUrl 口径）：当空链处理，返回 null 交仓库回落
                SpiderDebug.log("music", "lx playUrlWithPlugin probe-dead url dropped plugin=%s", target.name);
                url = null;
            }
            if (!TextUtils.isEmpty(url)) {
                com.fongmi.android.tv.music.MusicStore.notePluginLatency(
                        PLATFORM, target.srcUrl, System.currentTimeMillis() - lxT0, false);
                return url;
            }
            if (!probe) markDeadPlugin(deadKey, srcUrl, want);
        } catch (Throwable t) {
            if (!probe) markDeadPlugin(deadKey, srcUrl, want);
            rethrowKeyIssue(t);
            SpiderDebug.log("music", "lx playUrlWithPlugin error plugin=%s %s", target.name, t.getMessage());
        }
        return null;
    }

    @Override
    public String lyric(MusicTrack track) throws Exception {
        Parsed p = parse(track);
        if (p == null) return null;
        String own = LxPlatformSearch.lyric(p.platform, p.item);
        if (!TextUtils.isEmpty(own)) return own;
        JsonObject musicInfo = musicInfo(track, p);
        for (MusicSite plugin : sites()) {
            LxBridge b;
            try {
                b = MusicSourceManager.get().lxBridgeOf(plugin);
            } catch (Throwable t) {
                continue;
            }
            // 单插件屏蔽平台：屏蔽了本歌内容平台的插件不许供词（取链同口径）
            if (com.fongmi.android.tv.music.MusicStore.isPlatformBlocked(plugin.srcUrl, p.platform)) continue;
            if (b == null || !b.supports(p.platform, "lyric")) continue;
            try {
                String r = b.request(p.platform, "lyric", musicInfo, "");
                String text = extractLyric(r);
                if (!TextUtils.isEmpty(text)) return text;
            } catch (Throwable t) {
                rethrowKeyIssue(t);
                SpiderDebug.log("music", "lx lyric error plugin=%s %s", plugin.name, t.getMessage());
            }
        }
        return null;
    }

    /**
     * 完整歌词（正文＋译＋音译）：与 lyric() 同一条通道——平台歌词接口只有纯文本，
     * 命中时包成只有正文的 MusicLyric；脚本 lyric 动作的原始返回走 extractLyricRich，
     * 保住 lxlyric 逐字正文、tlyric 译、romalrc 音译三格。
     */
    public MusicLyric lyricRich(MusicTrack track) {
        Parsed p = parse(track);
        if (p == null) return null;
        String own = LxPlatformSearch.lyric(p.platform, p.item);
        if (!TextUtils.isEmpty(own)) return new MusicLyric(own);
        JsonObject musicInfo = musicInfo(track, p);
        MusicLyric romajiOnly = null;
        for (MusicSite plugin : sites()) {
            LxBridge b;
            try {
                b = MusicSourceManager.get().lxBridgeOf(plugin);
            } catch (Throwable t) {
                continue;
            }
            // 单插件屏蔽平台：屏蔽了本歌内容平台的插件不许供词（取链同口径）
            if (com.fongmi.android.tv.music.MusicStore.isPlatformBlocked(plugin.srcUrl, p.platform)) continue;
            if (b == null || !b.supports(p.platform, "lyric")) continue;
            try {
                String r = b.request(p.platform, "lyric", musicInfo, "");
                MusicLyric lyric = extractLyricRich(r);
                if (lyric != null && (lyric.sourceName == null || lyric.sourceName.isEmpty())) lyric.sourceName = plugin.name;
                // 正文/译文立即返回；纯音译只留候选，不截断后面插件的正文
                if (lyric != null && lyric.hasBodyText()) return lyric;
                if (lyric != null && lyric.hasLyric() && romajiOnly == null) romajiOnly = lyric;
            } catch (Throwable t) {
                rethrowKeyIssue(t);
                SpiderDebug.log("music", "lx lyricRich error plugin=%s %s", plugin.name, t.getMessage());
            }
        }
        return romajiOnly;
    }

    /** 插件循环的全量版（B2）：同一条插件遍历，收集全部实际命中（含纯音译），不截断；平台歌词不在内（仓库 own 步已覆盖） */
    public java.util.List<MusicLyric> lyricPluginAllRich(MusicTrack track) {
        java.util.List<MusicLyric> out = new java.util.ArrayList<>();
        Parsed p = parse(track);
        if (p == null) return out;
        JsonObject musicInfo = musicInfo(track, p);
        for (MusicSite plugin : sites()) {
            LxBridge b;
            try {
                b = MusicSourceManager.get().lxBridgeOf(plugin);
            } catch (Throwable t) {
                continue;
            }
            // 单插件屏蔽平台：屏蔽了本歌内容平台的插件不许供词（取链同口径）
            if (com.fongmi.android.tv.music.MusicStore.isPlatformBlocked(plugin.srcUrl, p.platform)) continue;
            if (b == null || !b.supports(p.platform, "lyric")) continue;
            try {
                String r = b.request(p.platform, "lyric", musicInfo, "");
                MusicLyric lyric = extractLyricRich(r);
                if (lyric != null && lyric.hasLyric()) {
                    if (lyric.sourceName == null || lyric.sourceName.isEmpty()) lyric.sourceName = plugin.name;
                    out.add(lyric);
                }
            } catch (Throwable t) {
                rethrowKeyIssue(t);
                SpiderDebug.log("music", "lx lyricPluginAllRich error plugin=%s %s", plugin.name, t.getMessage());
            }
        }
        return out;
    }

    /* ---------------- 内部 ---------------- */

    /** B4：把包在 ExecutionException／CompletionException 里的密钥问题错误解出来重抛；其余原样记日志 */
    private static void rethrowKeyIssue(Throwable t) {
        Throwable root = t;
        while (root instanceof java.util.concurrent.ExecutionException
                || root instanceof java.util.concurrent.CompletionException) {
            if (root.getCause() == null) break;
            root = root.getCause();
        }
        if (root instanceof LxKeyIssue) throw (LxKeyIssue) root;
    }

    private String platformKeyOf(MusicSite site) {
        if (site == null || site.srcUrl == null || !site.srcUrl.startsWith(SITE_PREFIX)) return "";
        return site.srcUrl.substring(SITE_PREFIX.length());
    }

    /** 播放器四档 → 落雪音质串：low→128k、standard→320k、high→flac、super→flac24bit */
    static String lxQuality(MusicQuality q) {
        switch (q) {
            case LOW:
                return "128k";
            case HIGH:
                return "flac";
            case SUPER:
                return "flac24bit";
            default:
                return "320k";
        }
    }

    /** 脚本没声明想要的档时，往下找它声明过的最高档；都没有就用它声明的最低档 */
    private String pickQuality(LxBridge bridge, String platform, String want) {
        LxBridge.LxSource s = bridge.sources().get(platform);
        if (s == null || s.qualitys.isEmpty()) return want;
        if (s.qualitys.contains(want)) return want;
        String[] order = {"flac24bit", "flac", "320k", "128k"};
        int wantIdx = indexOf(order, want);
        for (int i = wantIdx + 1; i < order.length; i++) {
            if (s.qualitys.contains(order[i])) return order[i];
        }
        return s.qualitys.get(0);
    }

    private int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return arr.length - 1;
    }

    /** musicInfo：平台原生标识（_item 全字段）＋通用字段，落雪脚本按各自习惯读 */
    private JsonObject musicInfo(MusicTrack track, Parsed p) {
        JsonObject info = new JsonObject();
        for (Map.Entry<String, JsonElement> e : p.item.entrySet()) {
            info.add(e.getKey(), e.getValue());
        }
        info.addProperty("name", track.title == null ? "" : track.title);
        info.addProperty("singer", track.artist == null ? "" : track.artist);
        String album = track.album == null ? "" : track.album;
        info.addProperty("albumName", album);
        if (!info.has("album")) info.addProperty("album", album);
        info.addProperty("source", p.platform);
        info.addProperty("type", "music");
        if (track.duration > 0) info.addProperty("interval", track.duration / 1000);
        if (!TextUtils.isEmpty(track.cover)) info.addProperty("img", track.cover);
        return info;
    }

    private String extractUrl(String result) {
        if (TextUtils.isEmpty(result)) return null;
        String r = result.trim();
        if (r.startsWith("http")) return r;
        if (r.startsWith("\"")) {
            try {
                String s = JsonParser.parseString(r).getAsString();
                return s.startsWith("http") ? s : null;
            } catch (Exception e) {
                return null;
            }
        }
        if (r.startsWith("{")) {
            try {
                JsonObject o = JsonParser.parseString(r).getAsJsonObject();
                String url = optStr(o, "url");
                return url.startsWith("http") ? url : null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private String extractLyric(String result) {
        if (TextUtils.isEmpty(result)) return null;
        String r = result.trim();
        if (r.startsWith("\"")) {
            try {
                return JsonParser.parseString(r).getAsString();
            } catch (Exception e) {
                return null;
            }
        }
        if (r.startsWith("{")) {
            try {
                JsonObject o = JsonParser.parseString(r).getAsJsonObject();
                String s = firstNonEmpty(optStr(o, "lyric"), optStr(o, "rawLrc"), optStr(o, "lrc"));
                return s.isEmpty() ? null : s;
            } catch (Exception e) {
                return null;
            }
        }
        return r.contains("[") ? r : null;
    }

    /**
     * extractLyric 的 rich 版：落雪 lyric 动作返回对象时的字段优先级——
     * 正文由 pickRawLrc 选（lxlyric 可解析时优先，否则 lyric &gt; rawLrc &gt; lrc）；译取 tlyric；音译取 romalrc。
     * 纯字符串 / 裸文本返回与 extractLyric 同口径包成只有正文。
     */
    private MusicLyric extractLyricRich(String result) {
        if (TextUtils.isEmpty(result)) return null;
        String r = result.trim();
        if (r.startsWith("\"")) {
            try {
                String s = JsonParser.parseString(r).getAsString();
                return TextUtils.isEmpty(s) ? null : new MusicLyric(s);
            } catch (Exception e) {
                return null;
            }
        }
        if (r.startsWith("{")) {
            try {
                JsonObject o = JsonParser.parseString(r).getAsJsonObject();
                MusicLyric lyric = new MusicLyric();
                lyric.rawLrc = pickRawLrc(optStr(o, "lxlyric"),
                        firstNonEmpty(optStr(o, "lyric"), optStr(o, "rawLrc"), optStr(o, "lrc")));
                lyric.translation = firstNonEmpty(optStr(o, "tlyric"), optStr(o, "translation"));
                lyric.romanization = firstNonEmpty(optStr(o, "romalrc"), optStr(o, "romanization"));
                if (lyric.rawLrc != null && lyric.rawLrc.isEmpty()) lyric.rawLrc = null;
                if (lyric.translation.isEmpty()) lyric.translation = null;
                if (lyric.romanization.isEmpty()) lyric.romanization = null;
                return lyric.hasLyric() ? lyric : null;
            } catch (Exception e) {
                return null;
            }
        }
        return r.contains("[") ? new MusicLyric(r) : null;
    }

    /**
     * 正文选源：lxlyric 逐字优先，但必须真能解析出整行词才算数；
     * lxlyric 损坏/不可解析而 plain 字段（lyric 等）能解析时用 plain——与 plain 链
     * extractLyric 只认 lyric 字段的口径对齐，不许 rich 链拿坏逐字把好整行词顶掉。
     * 两边都解析不出时保留 lxlyric 原文，交解析层的整行/纯文本回落再 salvage。
     */
    private String pickRawLrc(String lxlyric, String plain) {
        if (TextUtils.isEmpty(lxlyric)) return plain.isEmpty() ? null : plain;
        if (plain.isEmpty()) return lxlyric;
        if (isUsableLxLyric(lxlyric)) return lxlyric;
        if (!LyricsParser.parseWordLyric(plain).isEmpty()) return plain;
        return lxlyric;
    }

    /**
     * lxlyric 真可用判据：parseWordLyric 非空还不够——解析层的纯文本回落会把乱码/报错串
     * 也收成无时间轴「整行词」，这种回落行不许算逐字可用、顶掉 plain 的真整行词。
     * 真逐字/LRC 至少命中一条：解析行带逐字词、原文有 [mm:ss] 定时行、或有 [ms,时长] 逐字行头
     * （行头在但词标记损坏被 salvage 成整行时靠这条认）。
     */
    private static boolean isUsableLxLyric(String text) {
        List<com.fongmi.android.tv.player.lyrics.LyricsLine> lines = LyricsParser.parseWordLyric(text);
        if (lines.isEmpty()) return false;
        for (com.fongmi.android.tv.player.lyrics.LyricsLine line : lines) {
            if (line.hasWords()) return true;
        }
        if (LyricsParser.hasTimedLine(text)) return true;
        return java.util.regex.Pattern.compile("(?m)^\\s*\\[\\d+,\\d+]").matcher(text).find();
    }

    private String firstNonEmpty(String... vals) {
        for (String v : vals) if (!TextUtils.isEmpty(v)) return v;
        return "";
    }

    private String optStr(JsonObject obj, String name) {
        try {
            if (obj.has(name) && !obj.get(name).isJsonNull()) return obj.get(name).getAsString();
        } catch (Exception ignored) {
        }
        return "";
    }

    private Parsed parse(MusicTrack track) {
        if (track == null || TextUtils.isEmpty(track.rawJson)) return null;
        try {
            JsonObject wrap = JsonParser.parseString(track.rawJson).getAsJsonObject();
            Parsed p = new Parsed();
            p.platform = optStr(wrap, "_platform");
            if (p.platform.isEmpty()) return null;
            p.item = wrap.has("_item") && wrap.get("_item").isJsonObject()
                    ? wrap.getAsJsonObject("_item") : new JsonObject();
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private static class Parsed {
        String platform;
        JsonObject item;
    }
}
