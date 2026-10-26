package com.fongmi.android.tv.api.music;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.quickjs.bean.Req;
import com.fongmi.quickjs.utils.Connect;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

/**
 * 导入各大平台公开歌单（免登录）：粘贴分享文案 → 抠链接/ID（抠不到链接且整段是
 * 纯数字时按歌单 ID 逐家认领）→ 平台公开接口拉全量 →
 * 拼成与壳内搜索同形态的落雪 Track（platform="lx"、id="lx:{平台}:{原生id}"、
 * rawJson={_platform,_item}），播放/歌词/下载直接走现成链。
 * 接口口径全部来自 2026-10-20 本机探活实证（music-playlist-import-plan.md），参数不许自由发挥。
 * 全部方法阻塞，只能在子线程调。平台键与 LxPlatformSearch 一致：wy/qq 是 tx/kg/kw。
 */
public class PlaylistImporter {

    /** 失败分类，与 strings 里的四条失败文案一一对应 */
    public enum Fail {
        /** 链接认不出来 */
        UNRECOGNIZED,
        /** 歌单不存在或没公开 */
        NOT_FOUND,
        /** 歌单里没有歌 */
        EMPTY,
        /** 平台连不上/接口异常 */
        NETWORK
    }

    public static class ImportException extends Exception {
        public final Fail fail;

        public ImportException(Fail fail) {
            super(fail.name());
            this.fail = fail;
        }
    }

    public static class Result {
        /** 平台歌单原名 */
        public String name = "";
        /** 平台键：wy / tx(QQ) / kg / kw */
        public String platformKey = "";
        /** 平台声明的总数（trackCount/songnum/total）；未知为 -1 */
        public int total = -1;
        public List<MusicTrack> tracks = new ArrayList<>();

        public boolean partial() {
            return total > tracks.size();
        }
    }

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final int TIMEOUT = 20000;

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>，。；、！？]+");
    private static final Pattern ID_PARAM = Pattern.compile("[?&]id=(\\d+)");
    private static final Pattern WY_PATH = Pattern.compile("/playlist/(\\d+)");
    private static final Pattern QQ_PATH = Pattern.compile("/playlist/(\\d+)");
    private static final Pattern KG_SPECIAL = Pattern.compile("/special/single/(\\d+)");
    private static final Pattern KG_SONGLIST = Pattern.compile("/songlist/([A-Za-z0-9_-]+)");
    private static final Pattern KW_PATH = Pattern.compile("/playlist_detail/(\\d+)");

    private static class Target {
        String platformKey;
        String id;
        boolean songlist;
    }

    /* ================= 入口 ================= */

    /** 从整段分享文案导入。阻塞，子线程调 */
    public static Result importFromText(String text) throws ImportException {
        Target target = extract(text);
        if (target == null) {
            // 兜底（方案文档 music-playlist-import-plan.md §三原口径，此前代码缺这条）：
            // 整段文本抠不到 URL、且本身就是一串纯数字时，按各平台歌单 ID 逐家试认领。
            String numericId = pureNumericId(text);
            if (numericId != null) return importFromNumericId(numericId);
            throw new ImportException(Fail.UNRECOGNIZED);
        }
        try {
            switch (target.platformKey) {
                case LxPlatformSearch.WY:
                    return fetchWy(target.id);
                case LxPlatformSearch.QQ:
                    return fetchQq(target.id);
                case LxPlatformSearch.KG:
                    return target.songlist ? fetchKgSonglist(target.id) : fetchKgSpecial(target.id);
                case LxPlatformSearch.KW:
                    return fetchKw(target.id);
                default:
                    throw new ImportException(Fail.UNRECOGNIZED);
            }
        } catch (ImportException e) {
            throw e;
        } catch (Exception e) {
            throw new ImportException(Fail.NETWORK);
        }
    }

    /** 按平台歌单 id 导入（登录账号导入复用：先拉“我的歌单”列表，点某张再走本链拉详情；公开链路与分享导入一致）。阻塞，子线程调 */
    public static Result importFromId(String platformKey, String id) throws ImportException {
        if (TextUtils.isEmpty(platformKey) || TextUtils.isEmpty(id)) throw new ImportException(Fail.UNRECOGNIZED);
        try {
            switch (platformKey) {
                case LxPlatformSearch.WY:
                    return fetchWy(id);
                case LxPlatformSearch.QQ:
                    return fetchQq(id);
                default:
                    throw new ImportException(Fail.UNRECOGNIZED);
            }
        } catch (ImportException e) {
            throw e;
        } catch (Exception e) {
            throw new ImportException(Fail.NETWORK);
        }
    }

    /** 是否已装声明支持该平台 musicUrl 的落雪脚本。阻塞（要加载脚本），子线程调 */
    public static boolean hasLxSupport(String platformKey) {
        try {
            for (MusicSite s : new LxMusicSource().searchableSites()) {
                if (s.srcUrl != null && s.srcUrl.equals("lx://" + platformKey)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /* ================= 文案抠取 ================= */

    private static Target extract(String text) {
        if (TextUtils.isEmpty(text)) return null;
        Matcher m = URL_PATTERN.matcher(text);
        if (!m.find()) return null;
        String url = m.group();
        Target t = matchUrl(url);
        if (t != null) return t;
        // 已知域名但没抠到 ID、或未知短链：跟随重定向拿最终 URL 再抠一次
        try {
            String finalUrl = resolveFinalUrl(url);
            if (finalUrl != null && !finalUrl.equals(url)) return matchUrl(finalUrl);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Target matchUrl(String url) {
        if (url == null) return null;
        String u = url.toLowerCase();
        Target t;
        if (u.contains("163.com")) {
            t = new Target();
            t.platformKey = LxPlatformSearch.WY;
            t.id = firstGroup(ID_PARAM, url);
            if (t.id == null) t.id = firstGroup(WY_PATH, url);
            return t.id == null ? null : t;
        }
        if (u.contains("qq.com")) {
            t = new Target();
            t.platformKey = LxPlatformSearch.QQ;
            t.id = firstGroup(QQ_PATH, url);
            if (t.id == null) t.id = firstGroup(ID_PARAM, url);
            return t.id == null ? null : t;
        }
        if (u.contains("kugou.com")) {
            t = new Target();
            t.platformKey = LxPlatformSearch.KG;
            t.id = firstGroup(KG_SPECIAL, url);
            if (t.id != null) return t;
            t.id = firstGroup(KG_SONGLIST, url);
            t.songlist = t.id != null;
            return t.id == null ? null : t;
        }
        if (u.contains("kuwo.cn")) {
            t = new Target();
            t.platformKey = LxPlatformSearch.KW;
            t.id = firstGroup(KW_PATH, url);
            return t.id == null ? null : t;
        }
        return null;
    }

    private static String firstGroup(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** 纯数字 ID 形态：整段（去首尾空白）全是数字且不少于 4 位。四家歌单 ID 的
     * 主形态都是纯数字（wy 歌单号／QQ disstid／酷狗 special 号／酷我 pid）；
     * 酷狗 songlist 的 ID 是字母数字令牌，不在此列——纯数字只走 special 抓法。 */
    private static final Pattern PURE_NUMERIC_ID = Pattern.compile("^\\d{4,}$");

    private static String pureNumericId(String text) {
        if (TextUtils.isEmpty(text)) return null;
        String t = text.trim();
        return PURE_NUMERIC_ID.matcher(t).matches() ? t : null;
    }

    /** 纯数字 ID 逐家认领：按 wy → tx → kg(special) → kw 顺序试，第一家真拉到
     * 歌单的认作归属（同号跨家撞车时先到先得，与 ID 主流分布一致）。
     * 单家报 NOT_FOUND／EMPTY／NETWORK 都不挡下一家；全灭时失败口径取：
     * 有一家明确报 EMPTY（歌单在但没歌）报 EMPTY，其次有 NOT_FOUND 报
     * NOT_FOUND，全是网络问题才报 NETWORK。 */
    private static Result importFromNumericId(String id) throws ImportException {
        boolean sawEmpty = false;
        boolean sawNotFound = false;
        String[] order = {LxPlatformSearch.WY, LxPlatformSearch.QQ, LxPlatformSearch.KG, LxPlatformSearch.KW};
        for (String key : order) {
            try {
                switch (key) {
                    case LxPlatformSearch.WY:
                        return fetchWy(id);
                    case LxPlatformSearch.QQ:
                        return fetchQq(id);
                    case LxPlatformSearch.KG:
                        return fetchKgSpecial(id);
                    default:
                        return fetchKw(id);
                }
            } catch (ImportException e) {
                if (e.fail == Fail.EMPTY) sawEmpty = true;
                else if (e.fail == Fail.NOT_FOUND) sawNotFound = true;
            } catch (Exception e) {
                // 当作这家网络不通，继续试下一家
            }
        }
        if (sawEmpty) throw new ImportException(Fail.EMPTY);
        if (sawNotFound) throw new ImportException(Fail.NOT_FOUND);
        throw new ImportException(Fail.NETWORK);
    }

    private static String resolveFinalUrl(String url) throws Exception {
        Req req = req(url, null);
        try (Response res = Connect.to(url, req).execute()) {
            return res.request().url().toString();
        }
    }

    /* ================= 网易云 wy ================= */

    private static Result fetchWy(String id) throws Exception {
        String text = httpGet("https://music.163.com/api/v6/playlist/detail?id=" + enc(id), "https://music.163.com/");
        JsonObject root = parse(text);
        int code = (int) optLong(root, "code");
        if (code == 404) throw new ImportException(Fail.NOT_FOUND);
        if (code != 200 || !root.has("playlist") || !root.get("playlist").isJsonObject()) {
            throw new ImportException(Fail.NETWORK);
        }
        JsonObject playlist = root.getAsJsonObject("playlist");
        Result r = new Result();
        r.platformKey = LxPlatformSearch.WY;
        r.name = optStr(playlist, "name");
        int trackCount = (int) optLong(playlist, "trackCount");
        r.total = trackCount;
        List<String> ids = new ArrayList<>();
        if (playlist.has("trackIds") && playlist.get("trackIds").isJsonArray()) {
            for (JsonElement el : playlist.getAsJsonArray("trackIds")) {
                if (!el.isJsonObject()) continue;
                String tid = optStr(el.getAsJsonObject(), "id");
                if (!tid.isEmpty()) ids.add(tid);
            }
        }
        if (ids.isEmpty()) {
            // 有声明总数却拿不到 id 列表 = 没公开；压根没歌 = 空
            throw new ImportException(trackCount > 0 ? Fail.NOT_FOUND : Fail.EMPTY);
        }
        // 第二段：歌曲详情分批补全，200 一批；按 trackIds 顺序回填
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        for (int from = 0; from < ids.size(); from += 200) {
            List<String> batch = ids.subList(from, Math.min(from + 200, ids.size()));
            StringBuilder c = new StringBuilder("[");
            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) c.append(',');
                c.append("{\"id\":").append(batch.get(i)).append('}');
            }
            c.append(']');
            String detail = httpGet("https://music.163.com/api/v3/song/detail?c=" + enc(c.toString()), "https://music.163.com/");
            JsonObject dRoot = parse(detail);
            if (dRoot.has("songs") && dRoot.get("songs").isJsonArray()) {
                for (JsonElement el : dRoot.getAsJsonArray("songs")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject song = el.getAsJsonObject();
                    String sid = optStr(song, "id");
                    if (!sid.isEmpty()) byId.put(sid, song);
                }
            }
        }
        for (String tid : ids) {
            JsonObject song = byId.get(tid);
            if (song == null) continue;
            MusicTrack t = newTrack(LxPlatformSearch.WY, tid);
            t.title = optStr(song, "name");
            t.artist = joinArtists(song, "ar");
            if (song.has("al") && song.get("al").isJsonObject()) {
                JsonObject al = song.getAsJsonObject("al");
                t.album = optStr(al, "name");
                t.cover = optStr(al, "picUrl");
            }
            t.duration = optLong(song, "dt");
            JsonObject item = new JsonObject();
            item.addProperty("songmid", tid);
            item.addProperty("id", tid);
            finish(t, item);
            r.tracks.add(t);
        }
        if (r.tracks.isEmpty()) throw new ImportException(Fail.EMPTY);
        return r;
    }

    /* ================= QQ tx ================= */

    private static Result fetchQq(String id) throws Exception {
        String url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&disstid=" + enc(id)
                + "&format=json&g_tk=5381&loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0";
        JsonObject root = parse(httpGet(url, "https://y.qq.com/"));
        if (!root.has("cdlist") || !root.get("cdlist").isJsonArray() || root.getAsJsonArray("cdlist").size() == 0
                || !root.getAsJsonArray("cdlist").get(0).isJsonObject()) {
            throw new ImportException(Fail.NOT_FOUND);
        }
        JsonObject cd = root.getAsJsonArray("cdlist").get(0).getAsJsonObject();
        Result r = new Result();
        r.platformKey = LxPlatformSearch.QQ;
        r.name = optStr(cd, "dissname");
        int songnum = (int) optLong(cd, "songnum");
        r.total = songnum;
        JsonArray list = cd.has("songlist") && cd.get("songlist").isJsonArray() ? cd.getAsJsonArray("songlist") : null;
        if (list == null || list.size() == 0) {
            // 声明有歌（songnum>0）却不给列表＝歌单没公开（私密歌单，如他人「我喜欢」），
            // 不是平台连不上：旧实现报 NETWORK「平台暂时连不上」是假原因，改 NOT_FOUND
            // （文案「歌单不存在或没公开，先在原 App 里设为公开」）；songnum=0 同走此路。
            throw new ImportException(Fail.NOT_FOUND);
        }
        for (JsonElement el : list) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String mid = optStr(o, "songmid");
            if (mid.isEmpty()) continue;
            MusicTrack t = newTrack(LxPlatformSearch.QQ, mid);
            t.title = optStr(o, "songname");
            t.artist = joinArtists(o, "singer");
            t.album = optStr(o, "albumname");
            t.duration = optLong(o, "interval") * 1000;
            String albumMid = optStr(o, "albummid");
            if (!albumMid.isEmpty()) {
                t.cover = "https://y.qq.com/music/photo_new/T002R300x300M000" + albumMid + ".jpg";
            }
            JsonObject item = new JsonObject();
            item.addProperty("songmid", mid);
            finish(t, item);
            r.tracks.add(t);
        }
        if (r.tracks.isEmpty()) throw new ImportException(Fail.EMPTY);
        return r;
    }

    /* ================= 酷狗 kg：老歌单 special ================= */

    private static Result fetchKgSpecial(String id) throws Exception {
        Result r = new Result();
        r.platformKey = LxPlatformSearch.KG;
        // song 接口不带歌单名（实测只有 info/total），名从 info 接口单独拿；拿不到不挡导入，结尾有占位名兜底
        try {
            JsonObject infoRoot = parse(httpGet("http://mobilecdn.kugou.com/api/v3/special/info?specialid=" + enc(id)
                    + "&version=9108&area_code=1", null));
            JsonObject infoData = infoRoot.has("data") && infoRoot.get("data").isJsonObject()
                    ? infoRoot.getAsJsonObject("data") : null;
            if (infoData != null) r.name = optStr(infoData, "specialname");
        } catch (Exception ignored) {
        }
        int page = 1;
        // 翻页上限兜底：300/页 × 20 页 = 6000 首，真歌单够用；到不了 total 时结果按部分导入报
        while (page <= 20) {
            String url = "http://mobilecdn.kugou.com/api/v3/special/song?specialid=" + enc(id)
                    + "&page=" + page + "&pagesize=300&version=9108&area_code=1";
            JsonObject root = parse(httpGet(url, null));
            JsonObject data = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : null;
            if (data == null) {
                if (page == 1) throw new ImportException(Fail.NOT_FOUND);
                break;
            }
            if (page == 1) {
                r.total = (int) optLong(data, "total");
            }
            JsonArray info = data.has("info") && data.get("info").isJsonArray() ? data.getAsJsonArray("info") : null;
            if (info == null || info.size() == 0) break;
            for (JsonElement el : info) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String hash = optStr(o, "hash");
                if (hash.isEmpty()) continue;
                MusicTrack t = newTrack(LxPlatformSearch.KG, hash);
                // 接口只给 filename="歌手 - 歌名"，按第一个 " - " 切；专辑名不给，留空
                String filename = optStr(o, "filename");
                int cut = filename.indexOf(" - ");
                if (cut > 0) {
                    t.artist = filename.substring(0, cut).trim();
                    t.title = filename.substring(cut + 3).trim();
                } else {
                    t.title = filename;
                }
                t.duration = optLong(o, "duration") * 1000;
                JsonObject item = new JsonObject();
                item.addProperty("hash", hash);
                item.addProperty("albumId", optStr(o, "album_id"));
                item.addProperty("songmid", hash);
                finish(t, item);
                r.tracks.add(t);
            }
            if (r.total > 0 && r.tracks.size() >= r.total) break;
            page++;
        }
        if (r.tracks.isEmpty()) throw new ImportException(r.total == 0 ? Fail.EMPTY : Fail.NOT_FOUND);
        if (r.name.isEmpty()) r.name = "酷狗歌单";
        return r;
    }

    /* ================= 酷狗 kg：新版歌单 songlist（页面内嵌 JSON，未抓到活样本时的抓法） ================= */

    private static Result fetchKgSonglist(String id) throws Exception {
        String html = httpGet("https://www.kugou.com/songlist/" + enc(id) + "/", "https://www.kugou.com/");
        String json = extractBalanced(html, "window.$output");
        if (json == null) throw new ImportException(Fail.NETWORK);
        JsonObject root = parse(json);
        Result r = new Result();
        r.platformKey = LxPlatformSearch.KG;
        JsonObject info = root.has("listinfo") && root.get("listinfo").isJsonObject() ? root.getAsJsonObject("listinfo") : root;
        r.name = optStr(info, "name");
        JsonArray songs = null;
        if (root.has("songs") && root.get("songs").isJsonArray()) songs = root.getAsJsonArray("songs");
        else if (info.has("songs") && info.get("songs").isJsonArray()) songs = info.getAsJsonArray("songs");
        if (songs == null || songs.size() == 0) throw new ImportException(Fail.EMPTY);
        for (JsonElement el : songs) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String hash = optStr(o, "hash");
            if (hash.isEmpty()) hash = optStr(o, "FileHash");
            if (hash.isEmpty()) continue;
            MusicTrack t = newTrack(LxPlatformSearch.KG, hash);
            String name = optStr(o, "name");
            String singer = optStr(o, "singername");
            if (singer.isEmpty()) singer = optStr(o, "author_name");
            int cut = name.indexOf(" - ");
            if (!singer.isEmpty()) {
                t.artist = singer;
                t.title = cut > 0 ? name.substring(cut + 3).trim() : name;
            } else if (cut > 0) {
                t.artist = name.substring(0, cut).trim();
                t.title = name.substring(cut + 3).trim();
            } else {
                t.title = name;
            }
            long timelen = optLong(o, "timelen");
            t.duration = timelen >= 60000 ? timelen : timelen * 1000;
            JsonObject item = new JsonObject();
            item.addProperty("hash", hash);
            item.addProperty("albumId", optStr(o, "album_id"));
            item.addProperty("songmid", hash);
            finish(t, item);
            r.tracks.add(t);
        }
        if (r.tracks.isEmpty()) throw new ImportException(Fail.EMPTY);
        r.total = r.tracks.size();
        if (r.name.isEmpty()) r.name = "酷狗歌单";
        return r;
    }

    /** 从 marker 后的第一个 { 起做大括号配平抠 JSON（页面内嵌对象） */
    private static String extractBalanced(String text, String marker) {
        if (text == null) return null;
        int at = text.indexOf(marker);
        if (at < 0) return null;
        int start = text.indexOf('{', at);
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return text.substring(start, i + 1);
        }
        return null;
    }

    /* ================= 酷我 kw（老接口，官网新接口要 token 已死） ================= */

    private static Result fetchKw(String id) throws Exception {
        Result r = new Result();
        r.platformKey = LxPlatformSearch.KW;
        int pn = 0;
        // 翻页上限兜底：100/页 × 30 页 = 3000 首；到不了 total 时按部分导入报
        while (pn <= 30) {
            String url = "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=" + enc(id)
                    + "&pn=" + pn + "&rn=100&encode=utf-8&keyset=pl2012&vipver=MUSIC_8.0.3.0&newver=1";
            JsonObject root = parse(httpGet(url, null));
            JsonArray list = null;
            if (root.has("musiclist") && root.get("musiclist").isJsonArray()) list = root.getAsJsonArray("musiclist");
            else if (root.has("list") && root.get("list").isJsonArray()) list = root.getAsJsonArray("list");
            if (list == null) {
                if (pn == 0) throw new ImportException(Fail.NOT_FOUND);
                break;
            }
            if (pn == 0) {
                r.total = (int) optLong(root, "total");
                r.name = optStr(root, "title");
                if (r.name.isEmpty()) r.name = optStr(root, "name");
            }
            if (list.size() == 0) break;
            for (JsonElement el : list) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String rid = optStr(o, "id");
                if (rid.isEmpty()) continue;
                MusicTrack t = newTrack(LxPlatformSearch.KW, rid);
                t.title = optStr(o, "name");
                t.artist = optStr(o, "artist");
                t.album = optStr(o, "album");
                t.duration = optLong(o, "duration") * 1000;
                t.cover = optStr(o, "albumpic");
                if (t.cover.isEmpty()) t.cover = optStr(o, "pic");
                JsonObject item = new JsonObject();
                item.addProperty("rid", rid);
                item.addProperty("songmid", rid);
                finish(t, item);
                r.tracks.add(t);
            }
            if (r.total > 0 && r.tracks.size() >= r.total) break;
            pn++;
        }
        if (r.tracks.isEmpty()) throw new ImportException(r.total == 0 ? Fail.EMPTY : Fail.NOT_FOUND);
        if (r.name.isEmpty()) r.name = "酷我歌单";
        return r;
    }

    /* ================= 内部 ================= */

    /** 与 LxPlatformSearch.newTrack 同规格：导入的歌与壳内搜出的同平台歌形态一致 */
    private static MusicTrack newTrack(String platformKey, String rawId) {
        MusicTrack t = new MusicTrack();
        t.id = "lx:" + platformKey + ":" + rawId;
        t.title = "";
        t.artist = "";
        t.album = "";
        t.cover = "";
        t.platform = LxMusicSource.PLATFORM;
        return t;
    }

    private static void finish(MusicTrack t, JsonObject item) {
        JsonObject wrap = new JsonObject();
        String id = t.id == null ? "" : t.id;
        String[] parts = id.split(":", 3);
        wrap.addProperty("_platform", parts.length >= 2 ? parts[1] : "");
        wrap.add("_item", item);
        t.rawJson = wrap.toString();
    }

    private static String joinArtists(JsonObject o, String key) {
        StringBuilder sb = new StringBuilder();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement s : o.get(key).getAsJsonArray()) {
                if (!s.isJsonObject()) continue;
                if (sb.length() > 0) sb.append("/");
                sb.append(optStr(s.getAsJsonObject(), "name"));
            }
        }
        return sb.toString();
    }

    private static String httpGet(String url, String referer) throws Exception {
        Req req = req(url, referer);
        try (Response res = Connect.to(url, req).execute()) {
            if (!res.isSuccessful()) throw new Exception("http " + res.code());
            byte[] bytes = res.body() != null ? res.body().bytes() : new byte[0];
            return new String(bytes, req.getCharset());
        }
    }

    /** 经 quickjs Connect 走壳子 OkHttp（吃代理/DoH/节点定向），与 LxPlatformSearch 同出口 */
    private static Req req(String url, String referer) throws Exception {
        JSONObject reqJson = new JSONObject();
        reqJson.put("method", "GET");
        JSONObject headers = new JSONObject();
        headers.put("User-Agent", UA);
        if (!TextUtils.isEmpty(referer)) headers.put("Referer", referer);
        reqJson.put("headers", headers);
        reqJson.put("timeout", TIMEOUT);
        reqJson.put("redirect", 1);
        return Req.objectFrom(reqJson.toString());
    }

    private static JsonObject parse(String text) throws Exception {
        if (text == null) throw new Exception("empty body");
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    private static String optStr(JsonObject obj, String name) {
        try {
            if (obj != null && obj.has(name) && !obj.get(name).isJsonNull()) {
                JsonElement el = obj.get(name);
                return el.isJsonPrimitive() ? el.getAsString() : "";
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static long optLong(JsonObject obj, String name) {
        try {
            if (obj != null && obj.has(name) && !obj.get(name).isJsonNull()) return obj.get(name).getAsLong();
        } catch (Exception ignored) {
        }
        return 0;
    }
}
