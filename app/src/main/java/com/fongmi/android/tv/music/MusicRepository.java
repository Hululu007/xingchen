package com.fongmi.android.tv.music;

import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.api.music.FreeJsMusicSource;
import com.fongmi.android.tv.api.music.LxMusicSource;
import com.fongmi.android.tv.api.music.QqChartSource;
import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
