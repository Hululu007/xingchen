package com.fongmi.android.tv.api.music;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicQuality;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicSource;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        for (MusicSite plugin : sites()) {
            try {
                LxBridge b = MusicSourceManager.get().lxBridgeOf(plugin);
                if (b == null) continue;
                for (Map.Entry<String, LxBridge.LxSource> e : b.sources().entrySet()) {
                    String key = e.getKey();
                    if (!LxPlatformSearch.supported(key)) continue;
                    if (!e.getValue().actions.contains("musicUrl")) continue;
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
        String key = platformKeyOf(site);
        if (key.isEmpty()) return Collections.emptyList();
        return LxPlatformSearch.search(key, keyword);
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

    /** 单个落雪脚本取链上限：一个脚本卡住不许拖垮整条取链（对差清单 D2） */
    private static final long PLUGIN_TIMEOUT_MS = 8000;
    /** 取链总预算（与播放服务 20 秒看门狗同口径）：到点还没出链就按失败返回 */
    private static final long TOTAL_TIMEOUT_MS = 20000;

    @Override
    public String playUrl(MusicTrack track, int quality) throws Exception {
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
        for (MusicSite plugin : sites()) {
            if (System.currentTimeMillis() >= deadline) {
                SpiderDebug.log("music", "lx playUrl total timeout");
                break;
            }
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
                // 单脚本 8 秒上限：get 到点就放弃这个脚本换下一个（脚本桥线程自己还有内层上限兜底）
                String r = CompletableFuture.supplyAsync(() -> {
                    try {
                        return b.request(p.platform, "musicUrl", musicInfo, q);
                    } catch (Throwable t) {
                        throw new CompletionException(t);
                    }
                }, Task.executor()).get(wait, TimeUnit.MILLISECONDS);
                String url = extractUrl(r);
                if (!TextUtils.isEmpty(url)) return url;
            } catch (Throwable t) {
                SpiderDebug.log("music", "lx playUrl error plugin=%s %s", plugin.name, t.getMessage());
            }
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
            if (b == null || !b.supports(p.platform, "lyric")) continue;
            try {
                String r = b.request(p.platform, "lyric", musicInfo, "");
                String text = extractLyric(r);
                if (!TextUtils.isEmpty(text)) return text;
            } catch (Throwable t) {
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
        for (MusicSite plugin : sites()) {
            LxBridge b;
            try {
                b = MusicSourceManager.get().lxBridgeOf(plugin);
            } catch (Throwable t) {
                continue;
            }
            if (b == null || !b.supports(p.platform, "lyric")) continue;
            try {
                String r = b.request(p.platform, "lyric", musicInfo, "");
                MusicLyric lyric = extractLyricRich(r);
                if (lyric != null && lyric.hasLyric()) return lyric;
            } catch (Throwable t) {
                SpiderDebug.log("music", "lx lyricRich error plugin=%s %s", plugin.name, t.getMessage());
            }
        }
        return null;
    }

    /* ---------------- 内部 ---------------- */

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
        if (!LyricsParser.parseWordLyric(lxlyric).isEmpty()) return lxlyric;
        if (!LyricsParser.parseWordLyric(plain).isEmpty()) return plain;
        return lxlyric;
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
