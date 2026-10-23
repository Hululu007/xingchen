package com.fongmi.android.tv.music;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * py 音乐源调用桥：点播侧的 py 音乐源跟阅读一样「调用」音乐播放器——
 * 源只递歌单（搜索/列表），播放、队列、歌词兜底、音效全归音乐播放器。
 * <p>
 * 判据沿用老音乐页的 looksMusic（源名/键含 音乐/听歌/歌曲/电台/ktv/music/mv/酷我/酷狗/qq音乐/网易云），
 * 再叠 py 蜘蛛判据（key 以 py_ 开头或 api 含 .py，且是蜘蛛型源），不新造口径。
 * py 源仍归点播源管理，进不了插件管理页（那边只管 FreeJS/落雪脚本）；但进音乐搜索横排。
 */
public class PyMusicSource implements MusicSource {

    public static final String PLATFORM = "py";

    @Override
    public String platform() {
        return PLATFORM;
    }

    /** 音乐判据：文本（源名／键／海阔规则名）小写后含任一关键词即认作音乐 */
    public static boolean looksMusicName(String text) {
        String n = (text == null ? "" : text).toLowerCase(Locale.ROOT);
        return n.contains("音乐") || n.contains("听歌") || n.contains("歌曲") || n.contains("电台")
                || n.contains("ktv") || n.contains("music") || n.contains(" mv") || n.contains("[mv]")
                || n.contains("酷我") || n.contains("酷狗") || n.contains("qq音乐") || n.contains("网易云");
    }

    /** 老音乐页判据原文照搬：名字＋键小写后含任一关键词即认作音乐源 */
    public static boolean looksMusic(Site s) {
        if (s == null) return false;
        return looksMusicName((s.getName() == null ? "" : s.getName()) + " " + (s.getKey() == null ? "" : s.getKey()));
    }

    /** py 蜘蛛判据：与 SiteApi.isPySpiderSite 同口径，再要求蜘蛛型（type 3）能走 spider 链 */
    static boolean isPySpider(Site s) {
        String key = s.getKey();
        String api = s.getApi();
        boolean py = (key != null && key.startsWith("py_")) || (api != null && api.contains(".py"));
        boolean spider = (s.getType() != null && s.getType() == 3) || (key != null && key.startsWith("py_"));
        return py && spider;
    }

    static boolean isMusicPy(Site s) {
        return s != null && isPySpider(s) && looksMusic(s);
    }

    /** 用户已装的 py 音乐源 → 音乐侧站点（srcUrl 借放点播源 key，做横排唯一键） */
    @Override
    public List<MusicSite> sites() {
        List<MusicSite> out = new ArrayList<>();
        try {
            for (Site s : VodConfig.get().getSites()) {
                if (!isMusicPy(s)) continue;
                MusicSite ms = new MusicSite();
                ms.platform = PLATFORM;
                ms.name = s.getName();
                ms.version = "";
                ms.srcUrl = s.getKey();
                ms.format = "py";
                ms.connected = true;
                out.add(ms);
            }
        } catch (Throwable t) {
            SpiderDebug.log("music", "py sites error %s", t.getMessage());
        }
        return out;
    }

    @Override
    public List<MusicTrack> search(String keyword) {
        List<MusicTrack> all = new ArrayList<>();
        for (MusicSite ms : sites()) {
            try {
                all.addAll(searchBySite(ms, keyword));
            } catch (Throwable t) {
                SpiderDebug.log("music", "py search error site=%s %s", ms.srcUrl, t.getMessage());
            }
        }
        return all;
    }

    @Override
    public List<MusicTrack> searchBySite(MusicSite site, String keyword) throws Exception {
        Site vod = vodSite(site == null ? null : site.srcUrl);
        if (vod == null) return new ArrayList<>();
        Result result = SiteApi.searchContent(vod, keyword, false, "1");
        return mapList(vod, result);
    }

    /** 列表：首页推荐＋前几个分类第一页合并（分类太多只扫前 5 个，防慢源拖死列表页） */
    @Override
    public List<MusicTrack> tracks(MusicSite site) throws Exception {
        Site vod = vodSite(site == null ? null : site.srcUrl);
        if (vod == null) return new ArrayList<>();
        List<MusicTrack> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            Result home = SiteApi.homeContent(vod);
            addMapped(out, seen, vod, home);
            int scanned = 0;
            for (com.fongmi.android.tv.bean.Class type : home.getTypes()) {
                if (scanned >= 5) break;
                scanned++;
                try {
                    Result cat = SiteApi.categoryContent(vod.getKey(), type.getTypeId(), "1", false, new HashMap<>());
                    addMapped(out, seen, vod, cat);
                } catch (Throwable t) {
                    SpiderDebug.log("music", "py category error key=%s tid=%s %s", vod.getKey(), type.getTypeId(), t.getMessage());
                }
            }
        } catch (Throwable t) {
            if (!out.isEmpty()) return out;
            throw t instanceof Exception ? (Exception) t : new Exception(t);
        }
        return out;
    }

    /** py 源没有榜单概念：不硬凑，返回空（榜单页由 FreeJS 等源供） */
    @Override
    public List<MusicChart> charts(MusicSite site) {
        return new ArrayList<>();
    }

    @Override
    public List<MusicTrack> chartTracks(MusicChart chart) {
        return new ArrayList<>();
    }

    /**
     * 取链：detailContent 拿 vod_play_url 解析出的选集（单曲通常一集；多集取 rawJson 记的集号）。
     * 选集值本身是 http 直链就直接用；不是直链（是待解析 id）才走壳子 playerContent 解析一层。
     * 拿不到就抛错——上层按播放失败处理（自动跳下一首开关管）。
     */
    @Override
    public String playUrl(MusicTrack track, int quality) throws Exception {
        JsonObject raw = raw(track);
        String siteKey = str(raw, "siteKey");
        String vodId = str(raw, "vodId");
        if (siteKey.isEmpty() || vodId.isEmpty()) throw new Exception("歌曲缺少来源信息");
        Result detail = SiteApi.detailContent(siteKey, vodId, false, false);
        Vod vod = detail.getVod();
        List<Flag> flags = vod == null ? null : vod.getFlags();
        if (vod == null || flags == null || flags.isEmpty()) {
            detail = SiteApi.detailContent(siteKey, vodId, true, false);
            vod = detail.getVod();
            flags = vod == null ? null : vod.getFlags();
        }
        if (vod == null) throw new Exception("详情为空");
        if (flags == null || flags.isEmpty()) throw new Exception("没有播放列表");
        if (track != null) {
            if ((track.artist == null || track.artist.isEmpty()) && !vod.getActor().isEmpty()) track.artist = vod.getActor();
            if ((track.album == null || track.album.isEmpty()) && !vod.getAlbum().isEmpty()) track.album = vod.getAlbum();
        }
        Flag flag = pickFlag(flags, str(raw, "flag"));
        List<Episode> episodes = flag.getEpisodes();
        if (episodes == null || episodes.isEmpty()) throw new Exception("没有可播的集");
        int ep = Math.max(0, Math.min(num(raw, "ep"), episodes.size() - 1));
        String url = episodes.get(ep).getUrl();
        if (url == null || url.isEmpty()) throw new Exception("播放地址为空");
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        Result play = SiteApi.playerContentParseOnly(siteKey, flag.getFlag(), url);
        if (play.getReadingSession() != null) throw new Exception("这个源给的是阅读内容，不是歌曲");
        String resolved = play.getUrl() == null ? "" : play.getUrl().v();
        if (resolved == null || resolved.isEmpty()) throw new Exception("解析不到播放地址");
        return resolved;
    }

    /** py 源一般不给歌词：返回 null，走 MusicRepository 现有兜底链（其它插件按歌名找词） */
    @Override
    public String lyric(MusicTrack track) {
        return null;
    }

    // ================= 内部 =================

    private Site vodSite(String key) {
        if (key == null || key.isEmpty()) return null;
        try {
            Site s = VodConfig.get().getSite(key);
            return s == null || s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    private List<MusicTrack> mapList(Site site, Result result) {
        List<MusicTrack> out = new ArrayList<>();
        if (result == null) return out;
        for (Vod vod : result.getList()) out.add(toTrack(site, vod));
        return out;
    }

    private void addMapped(List<MusicTrack> out, Set<String> seen, Site site, Result result) {
        for (MusicTrack t : mapList(site, result)) {
            if (seen.add(t.id)) out.add(t);
        }
    }

    /**
     * 视频侧音乐路由用：点播源（py／js／json 等任意 VodConfig 源）被识别为音乐时直接建曲目。
     * 取链只走 SiteApi 的详情＋解析链，与源类型无关；platform 仍标 py 以便服务按本桥解析。
     * 歌手只有视频侧给的字段，缺失不猜，显示层按未知歌手兜底。
     */
    public static MusicTrack trackForVod(String siteKey, String vodId, String name, String pic) {
        MusicTrack t = new MusicTrack();
        t.id = PLATFORM + ":" + siteKey + ":" + vodId;
        t.title = name == null ? "" : name;
        t.artist = "";
        t.cover = pic == null ? "" : pic;
        t.platform = PLATFORM;
        JsonObject raw = new JsonObject();
        raw.addProperty("siteKey", siteKey);
        raw.addProperty("vodId", vodId);
        raw.addProperty("ep", 0);
        raw.addProperty("flag", "");
        t.rawJson = raw.toString();
        return t;
    }

    /** 列表整拉用：Vod 自带 vod_actor／vod_remarks，歌手按 artistOf 口径带过去（2026-10-22 酷我 py 歌手丢失根因：整拉一直走上面那个只有名和图的版本，歌手写死空）。专辑：Vod 模型没有专辑字段，py 列表转 VOD 时 album 就丢了，这里补不了，批量里另定。 */
    public static MusicTrack trackForVod(String siteKey, com.fongmi.android.tv.bean.Vod vod) {
        MusicTrack t = new MusicTrack();
        t.id = PLATFORM + ":" + siteKey + ":" + vod.getId();
        t.title = vod.getName();
        t.artist = artistOf(vod);
        t.album = vod.getAlbum();
        t.cover = vod.getPic();
        t.platform = PLATFORM;
        JsonObject raw = new JsonObject();
        raw.addProperty("siteKey", siteKey);
        raw.addProperty("vodId", vod.getId());
        raw.addProperty("ep", 0);
        raw.addProperty("flag", "");
        t.rawJson = raw.toString();
        return t;
    }

    private MusicTrack toTrack(Site site, Vod vod) {
        MusicTrack t = new MusicTrack();
        t.id = PLATFORM + ":" + site.getKey() + ":" + vod.getId();
        t.title = vod.getName();
        t.artist = artistOf(vod);
        t.album = vod.getAlbum();
        t.cover = vod.getPic();
        t.platform = PLATFORM;
        JsonObject raw = new JsonObject();
        raw.addProperty("siteKey", site.getKey());
        raw.addProperty("vodId", vod.getId());
        raw.addProperty("ep", 0);
        raw.addProperty("flag", "");
        t.rawJson = raw.toString();
        return t;
    }

    /** 歌手能取则取：先 actor（音乐蜘蛛多把歌手放这），再 remarks（须不像集数/时长/状态字） */
    private static String artistOf(Vod vod) {
        String actor = trim(vod.getActor());
        if (!actor.isEmpty()) return actor;
        String remarks = trim(vod.getRemarks());
        if (remarks.isEmpty()) return "";
        if (remarks.matches(".*\\d{1,2}:\\d{2}.*")) return ""; // 像时长 03:45
        if (remarks.contains("更新") || remarks.contains("连载") || remarks.contains("全集")) return "";
        if (remarks.length() > 24) return "";
        return remarks;
    }

    private static Flag pickFlag(List<Flag> flags, String want) {
        if (want != null && !want.isEmpty()) {
            for (Flag f : flags) if (want.equals(f.getFlag())) return f;
        }
        return flags.get(0);
    }

    /** 播放页来源标签用：按 rawJson 的点播源 key 查源名 */
    public static String siteNameOf(MusicTrack track) {
        try {
            String key = str(raw(track), "siteKey");
            if (key.isEmpty()) return "";
            Site s = VodConfig.get().getSite(key);
            return s == null ? "" : s.getName();
        } catch (Throwable t) {
            return "";
        }
    }

    private static JsonObject raw(MusicTrack track) {
        try {
            if (track == null || track.rawJson == null || track.rawJson.isEmpty()) return new JsonObject();
            return JsonParser.parseString(track.rawJson).getAsJsonObject();
        } catch (Throwable t) {
            return new JsonObject();
        }
    }

    private static String str(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static int num(JsonObject o, String key) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
