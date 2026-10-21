package com.fongmi.android.tv.music;

import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.bean.music.LyricVersion;
import com.fongmi.android.tv.api.music.AmllMusicLyricSource;
import com.fongmi.android.tv.api.music.FreeJsMusicSource;
import com.fongmi.android.tv.api.music.LxKeyIssue;
import com.fongmi.android.tv.api.music.LxMusicSource;
import com.fongmi.android.tv.api.music.QqChartSource;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
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

    static {
        // 本地音乐源常驻：已下载歌曲（platform="local"）走文件直链，播放链不动
        register(new LocalMusicSource());
        // 一期 FreeJS 真源：MusicFree 插件桥（search/取链/歌词/榜单），常驻注册
        register(new FreeJsMusicSource());
        // 落雪真源：壳子平台搜索＋落雪脚本地址解析（musicUrl/lyric），常驻注册
        register(new LxMusicSource());
        // py 调用桥：已装的 py 点播音乐源递歌单进音乐播放器（跟阅读一样的调用口径）
        register(new PyMusicSource());
        // 设备本地音乐（platform="device"）：取链/同目录 .lrc 歌词；列表走 DeviceMusicStore 扫描
        register(new DeviceMusicSource());
        // QQ 榜单壳内源（platform="qq"）：只供榜单（QQ 巅峰榜直连），歌拼落雪 tx 形态走现成链播
        register(new QqChartSource());
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

    public static String playUrl(MusicTrack track, int quality) {
        if (track == null) return null;
        MusicSource s = find(track.platform);
        if (s == null) return null;
        try {
            return s.playUrl(track, quality);
        } catch (Throwable t) {
            rethrowKeyIssue(t);
            SpiderDebug.log("music", "playUrl error platform=%s %s", track.platform, t.getMessage());
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
        if (!force && !MusicStore.isLyricFallback()) return null;
        String keyword = (track.title == null ? "" : track.title).trim()
                + " " + (track.artist == null ? "" : track.artist).trim();
        keyword = keyword.trim();
        if (keyword.isEmpty()) return null;

        // 1. 本源内部的其它插件（FreeJS 多插件，启用顺序）
        if (s instanceof FreeJsMusicSource) {
            try {
                String fb = ((FreeJsMusicSource) s).lyricFallback(track);
                if (fb != null && !fb.trim().isEmpty()) return fb;
            } catch (Throwable t) {
                SpiderDebug.log("music", "lyric fallback error %s", t.getMessage());
            }
        }
        // 2. 其它源（本地源跳过，它不提供歌词）
        for (MusicSource other : SOURCES) {
            if (other == s || LocalMusicSource.PLATFORM.equals(other.platform())) continue;
            try {
                MusicTrack hit = matchSong(track, other.search(keyword));
                if (hit == null) continue;
                String lrc = other.lyric(hit);
                if (lrc != null && !lrc.trim().isEmpty()) return lrc;
            } catch (Throwable t) {
                rethrowKeyIssue(t);
            SpiderDebug.log("music", "lyric alt search error platform=%s %s", other.platform(), t.getMessage());
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
            break;
        }
        return null;
    }

    public static MusicLyric lyricRich(MusicTrack track) {
        return lyricRich(track, false);
    }

    /**
     * lyric() 的 rich 版：回退链与 lyric() 同构（本源→同源其它 FreeJS 插件兜底→
     * 跨源搜歌对歌→歌词型插件直查），「歌词缺失自动搜索」开关（force 例外）同样生效；
     * FreeJS/落雪源走各自的 rich 通道保住译/音译/逐字正文，其它源只有纯文本，包成只有正文的 MusicLyric。
     */
    public static MusicLyric lyricRich(MusicTrack track, boolean force) {
        if (track == null) return null;
        MusicSource s = find(track.platform);
        if (s == null) return null;
        MusicLyric own = null;
        try {
            own = normalizeRich(richOf(s, track));
        } catch (Throwable t) {
            rethrowKeyIssue(t);
            SpiderDebug.log("music", "lyricRich error platform=%s %s", track.platform, t.getMessage());
        }
        if (hasRaw(own)) return own;

        if (!force && !MusicStore.isLyricFallback()) return own != null && own.hasLyric() ? own : null;
        String keyword = (track.title == null ? "" : track.title).trim()
                + " " + (track.artist == null ? "" : track.artist).trim();
        keyword = keyword.trim();
        if (keyword.isEmpty()) return own != null && own.hasLyric() ? own : null;

        // 1. 本源内部的其它插件（FreeJS 多插件，启用顺序）
        if (s instanceof FreeJsMusicSource) {
            try {
                MusicLyric fb = normalizeRich(((FreeJsMusicSource) s).lyricFallbackRich(track));
                if (hasRaw(fb)) return fb;
            } catch (Throwable t) {
                SpiderDebug.log("music", "lyricRich fallback error %s", t.getMessage());
            }
        }
        // 2. 其它源（本地源跳过，它不提供歌词）
        for (MusicSource other : SOURCES) {
            if (other == s || LocalMusicSource.PLATFORM.equals(other.platform())) continue;
            try {
                MusicTrack hit = matchSong(track, other.search(keyword));
                if (hit == null) continue;
                MusicLyric lrc = normalizeRich(richOf(other, hit));
                if (hasRaw(lrc)) return lrc;
            } catch (Throwable t) {
                rethrowKeyIssue(t);
            SpiderDebug.log("music", "lyricRich alt search error platform=%s %s", other.platform(), t.getMessage());
            }
        }
        // 3. 歌词型插件直查（与 lyric() 同一步、同口径）
        for (MusicSource other : SOURCES) {
            if (!(other instanceof FreeJsMusicSource)) continue;
            try {
                MusicLyric lrc = normalizeRich(((FreeJsMusicSource) other).lyricFromLyricPluginsRich(track));
                if (hasRaw(lrc)) return lrc;
            } catch (Throwable t) {
                SpiderDebug.log("music", "lyricRich plugin search error %s", t.getMessage());
            }
            break;
        }
        // 4. AMLL（B8）：最后兜底，AMLL 档带档位元数据；无命中返回空，不冒充
        // S6 门控：AMLL 开关关时跳过本步；默认开，行为与原来一致
        if (MusicStore.isAmllEnabled()) {
            try {
                MusicLyric amll = normalizeRich(new AmllMusicLyricSource().lyricRich(track));
                if (hasRaw(amll)) return amll;
            } catch (Throwable t) {
                SpiderDebug.log("music", "lyricRich amll error %s", t.getMessage());
            }
        }
        return own != null && own.hasLyric() ? own : null;
    }

    /**
     * lyricRich 的全量版（B2）：同一条回退链，但收集全部实际命中，不截断；去重口径 rawLrc.trim()。
     * 返回按链顺序排列的版本列表；force／歌词缺失开关口径与 lyricRich 一致。
     * 注意：本方法会多发网络请求（同源插件＋跨源＋歌词插件＋AMLL），只供版本筛选条用，不替代快路径 lyricRich。
     */
    public static List<LyricVersion> lyricRichAll(MusicTrack track, boolean force) {
        List<LyricVersion> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (track == null) return out;
        MusicSource s = find(track.platform);
        if (s == null) return out;
        try {
            MusicLyric ownRich = normalizeRich(richOf(s, track));
            if (ownRich != null && (ownRich.sourceName == null || ownRich.sourceName.isEmpty())) {
                ownRich.sourceName = siteNameForPlatform(track.platform);
            }
            addVersion(out, seen, ownRich);
        } catch (Throwable t) {
            rethrowKeyIssue(t);
            SpiderDebug.log("music", "lyricRichAll error platform=%s %s", track.platform, t.getMessage());
        }
        if (!force && !MusicStore.isLyricFallback()) return out;
        String keyword = (track.title == null ? "" : track.title).trim()
                + " " + (track.artist == null ? "" : track.artist).trim();
        keyword = keyword.trim();
        if (keyword.isEmpty()) return out;

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
            SpiderDebug.log("music", "lyricRichAll fallback error %s", t.getMessage());
        }
        // 2. 其它源（本地源跳过）：每源各收一命中
        for (MusicSource other : SOURCES) {
            if (other == s || LocalMusicSource.PLATFORM.equals(other.platform())) continue;
            try {
                MusicTrack hit = matchSong(track, other.search(keyword));
                if (hit == null) continue;
                MusicLyric alt = normalizeRich(richOf(other, hit));
                if (alt != null && (alt.sourceName == null || alt.sourceName.isEmpty())) {
                    alt.sourceName = siteNameForPlatform(hit.platform);
                }
                addVersion(out, seen, alt);
            } catch (Throwable t) {
                rethrowKeyIssue(t);
            SpiderDebug.log("music", "lyricRichAll alt search error platform=%s %s", other.platform(), t.getMessage());
            }
        }
        // 3. 歌词型插件直查（与快路径同一步：只问第一个 FreeJS 源的歌词插件）
        for (MusicSource other : SOURCES) {
            if (!(other instanceof FreeJsMusicSource)) continue;
            try {
                for (MusicLyric l : ((FreeJsMusicSource) other).lyricPluginsAllRich(track)) {
                    addVersion(out, seen, normalizeRich(l));
                }
            } catch (Throwable t) {
                SpiderDebug.log("music", "lyricRichAll plugin search error %s", t.getMessage());
            }
            break;
        }
        // 4. AMLL（B8）；S6 门控：关时跳过，默认开行为不变
        if (MusicStore.isAmllEnabled()) {
            try {
                addVersion(out, seen, normalizeRich(new AmllMusicLyricSource().lyricRich(track)));
            } catch (Throwable t) {
                SpiderDebug.log("music", "lyricRichAll amll error %s", t.getMessage());
            }
        }
        return out;
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
        String artist = normalizeSong(target.artist);
        for (MusicTrack c : candidates) {
            if (c == null) continue;
            String ct = normalizeSong(c.title);
            if (ct.isEmpty()) continue;
            if (!ct.contains(title) && !title.contains(ct)) continue;
            if (!artist.isEmpty()) {
                String ca = normalizeSong(c.artist);
                if (!ca.isEmpty() && !ca.contains(artist) && !artist.contains(ca)) continue;
            }
            return c;
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
