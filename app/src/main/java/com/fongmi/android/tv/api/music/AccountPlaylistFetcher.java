package com.fongmi.android.tv.api.music;

import android.text.TextUtils;

import com.fongmi.quickjs.bean.Req;
import com.fongmi.quickjs.utils.Connect;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Response;

/**
 * 登录账号导入：手机直接调平台公开接口拉“我的歌单”列表，Cookie 不出手机、不走任何第三方服务器。
 * 网易云：nuser/account/get 取 uid → user/playlist 拉列表（legacy /api/ 族，与 PlaylistImporter 已探活的
 * v6/playlist/detail、v3/song/detail 同族；账号接口与歌单接口形状见多方公开文档，字段缺失容错）。
 * QQ：Cookie 里取 uin（去非数字字符）＋ skey 算 g_tk → musicu.fcg 主页接口拉列表；
 * musicu.fcg 信封（code／req_0.code／req_0.data）是标准形态，data 内歌单数组按候选键容错扫描，
 * 扫不到报真实返回结构证据，不编字段。
 * 全部方法阻塞，只能在子线程调。失败抛 FetchException，reason 只含真实原因（HTTP 码／接口码／
 * 返回结构证据），绝不含 Cookie。
 */
public class AccountPlaylistFetcher {

    public static class Sheet {
        public String id = "";
        public String name = "";
        public String coverUrl = "";
        public String description = "";
        public String creator = "";
        public long songCount = -1;
    }

    public static class FetchException extends Exception {
        public FetchException(String reason) {
            super(reason);
        }
    }

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final int TIMEOUT = 20000;

    /**
     * 真登录判定（登录导入入口共用）：music.163.com／y.qq.com 未登录访问也会种匿名
     * Cookie（NMTID／__csrf 一类），「Cookie 非空」不等于已登录——旧口径据此把未登录
     * 用户挡在「已登录」弹窗、进不了登录页。真判定认登录态标志：
     * 网易云认 MUSIC_U 非空（登录成功才下发）；QQ 认 uin 数字段（uin/wxuin/qqmusic_uin
     * 三级，与 fetchQq 取号同口径）＋ skey/p_skey 任一非空。
     * 只判本地 Cookie 串、不发请求；真账号下标志名是否仍为此二者属待真机验。
     */
    public static boolean hasLoginCookie(String platform, String cookie) {
        if (cookie == null || cookie.trim().isEmpty()) return false;
        if ("netease".equals(platform)) {
            return !cookieValue(cookie, "MUSIC_U").isEmpty();
        }
        String uin = digitsOnly(cookieValue(cookie, "uin"));
        if (uin.isEmpty()) uin = digitsOnly(cookieValue(cookie, "wxuin"));
        if (uin.isEmpty()) uin = digitsOnly(cookieValue(cookie, "qqmusic_uin"));
        if (uin.isEmpty()) return false;
        return !cookieValue(cookie, "skey").isEmpty() || !cookieValue(cookie, "p_skey").isEmpty();
    }

    /* ================= 网易云 ================= */

    public static List<Sheet> fetchNetease(String cookie) throws FetchException {
        String account = httpGet("https://music.163.com/api/nuser/account/get", "https://music.163.com/", cookie);
        JsonObject root = parse(account);
        long code = optLong(root, "code");
        if (code != 200) throw new FetchException("网易云账号接口返回 code=" + code + optMsg(root));
        long uid = 0;
        if (root.has("account") && root.get("account").isJsonObject()) uid = optLong(root.getAsJsonObject("account"), "id");
        if (uid == 0 && root.has("profile") && root.get("profile").isJsonObject()) uid = optLong(root.getAsJsonObject("profile"), "userId");
        if (uid == 0) throw new FetchException("未读到网易云账号 id，请重新登录");
        String list = httpGet("https://music.163.com/api/user/playlist?uid=" + uid + "&limit=1000&offset=0", "https://music.163.com/", cookie);
        JsonObject listRoot = parse(list);
        long listCode = optLong(listRoot, "code");
        if (listCode != 200) throw new FetchException("网易云歌单接口返回 code=" + listCode + optMsg(listRoot));
        List<Sheet> out = new ArrayList<>();
        if (listRoot.has("playlist") && listRoot.get("playlist").isJsonArray()) {
            for (JsonElement el : listRoot.getAsJsonArray("playlist")) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                Sheet s = new Sheet();
                s.id = optStr(o, "id");
                if (s.id.isEmpty()) continue;
                s.name = optStr(o, "name");
                s.coverUrl = optStr(o, "coverImgUrl");
                s.description = optStr(o, "description");
                s.songCount = optLongOrNeg(o, "trackCount");
                if (o.has("creator") && o.get("creator").isJsonObject()) s.creator = optStr(o.getAsJsonObject("creator"), "nickname");
                out.add(s);
            }
        }
        return out;
    }

    /* ================= QQ ================= */

    public static List<Sheet> fetchQq(String cookie) throws FetchException {
        // 取号三级回落与 QqVkeyFetcher.uinOf 同口径（扫描 M17）：只带 qqmusic_uin 的
        // 登录态在取链侧被认、在这里曾被判未登录、导入直接失败。
        String uin = digitsOnly(cookieValue(cookie, "uin"));
        if (uin.isEmpty()) uin = digitsOnly(cookieValue(cookie, "wxuin"));
        if (uin.isEmpty()) uin = digitsOnly(cookieValue(cookie, "qqmusic_uin"));
        if (uin.isEmpty()) throw new FetchException("未读到 QQ 号（uin），请重新登录");
        long gtk = gtk(cookieValue(cookie, "skey"));
        JsonObject body = new JsonObject();
        JsonObject comm = new JsonObject();
        comm.addProperty("ct", 24);
        comm.addProperty("cv", 0);
        body.add("comm", comm);
        JsonObject req0 = new JsonObject();
        req0.addProperty("module", "music.UnifiedHomepage.UnifiedHomepageSrv");
        req0.addProperty("method", "GetHomepage");
        JsonObject param = new JsonObject();
        param.addProperty("uin", uin);
        param.addProperty("IsQueryTabDetail", 0);
        req0.add("param", param);
        body.add("req_0", req0);
        String url = "https://u.y.qq.com/cgi-bin/musicu.fcg?g_tk=" + gtk + "&loginUin=" + uin + "&hostUin=" + uin
                + "&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0&data=" + enc(body.toString());
        String text = httpGet(url, "https://y.qq.com/", cookie);
        JsonObject root = parse(text);
        long code = optLong(root, "code");
        if (code != 0) throw new FetchException("QQ 主页接口返回 code=" + code);
        JsonObject data = null;
        if (root.has("req_0") && root.get("req_0").isJsonObject()) {
            JsonObject r0 = root.getAsJsonObject("req_0");
            long c0 = optLong(r0, "code");
            if (c0 != 0) throw new FetchException("QQ 主页接口 req_0 返回 code=" + c0);
            if (r0.has("data") && r0.get("data").isJsonObject()) data = r0.getAsJsonObject("data");
        }
        if (data == null) throw new FetchException("QQ 主页返回无 data（顶层键：" + keys(root) + "）");
        List<Sheet> out = new ArrayList<>();
        collectSheets(data, out, 0);
        if (out.isEmpty()) throw new FetchException("QQ 主页返回结构未识别（data 顶层键：" + keys(data) + "）");
        return out;
    }

    /** 在 data 内按候选键容错扫描歌单数组（QQ 主页字段未逐项实测锁定，扫不到报结构证据，不编字段）。 */
    private static void collectSheets(JsonObject obj, List<Sheet> out, int depth) {
        if (obj == null || depth > 3) return;
        for (String key : obj.keySet()) {
            JsonElement el = obj.get(key);
            if (el == null) continue;
            if (el.isJsonArray()) {
                JsonArray arr = el.getAsJsonArray();
                boolean looksLikeSheets = false;
                for (JsonElement item : arr) {
                    if (item.isJsonObject() && looksLikeSheet(item.getAsJsonObject())) {
                        looksLikeSheets = true;
                        break;
                    }
                }
                if (looksLikeSheets) {
                    for (JsonElement item : arr) {
                        if (!item.isJsonObject()) continue;
                        Sheet s = mapQqItem(item.getAsJsonObject());
                        if (!s.id.isEmpty()) out.add(s);
                    }
                }
            } else if (el.isJsonObject()) {
                collectSheets(el.getAsJsonObject(), out, depth + 1);
            }
        }
    }

    private static boolean looksLikeSheet(JsonObject o) {
        return !firstStr(o, "dissid", "tid", "id").isEmpty()
                && !firstStr(o, "dissname", "diss_name", "name").isEmpty();
    }

    private static Sheet mapQqItem(JsonObject o) {
        Sheet s = new Sheet();
        s.id = firstStr(o, "dissid", "tid", "id");
        s.name = firstStr(o, "dissname", "diss_name", "name");
        s.coverUrl = firstStr(o, "disscover", "diss_cover", "logo", "pic");
        s.description = firstStr(o, "introduction", "desc", "description");
        s.songCount = firstLong(o, "songnum", "song_cnt", "songcount");
        if (o.has("creator") && o.get("creator").isJsonObject()) {
            s.creator = firstStr(o.getAsJsonObject("creator"), "name", "nick", "nickname");
        } else {
            s.creator = firstStr(o, "creatorname", "nick");
        }
        return s;
    }

    /* ================= 内部 ================= */

    private static String httpGet(String url, String referer, String cookie) throws FetchException {
        try {
            JSONObject reqJson = new JSONObject();
            reqJson.put("method", "GET");
            JSONObject headers = new JSONObject();
            headers.put("User-Agent", UA);
            if (!TextUtils.isEmpty(referer)) headers.put("Referer", referer);
            if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
            reqJson.put("headers", headers);
            reqJson.put("timeout", TIMEOUT);
            reqJson.put("redirect", 1);
            Req req = Req.objectFrom(reqJson.toString());
            try (Response res = Connect.to(url, req).execute()) {
                if (!res.isSuccessful()) throw new FetchException("网络错误：http " + res.code());
                byte[] bytes = res.body() != null ? res.body().bytes() : new byte[0];
                return new String(bytes, req.getCharset());
            }
        } catch (FetchException e) {
            throw e;
        } catch (Exception e) {
            String m = e.getMessage();
            throw new FetchException("网络错误：" + (m == null ? "请求失败" : m));
        }
    }

    /** QQ g_tk 标准算法（32 位回绕后取低 31 位；skey 缺失时退化为 5381）。 */
    private static long gtk(String skey) {
        int hash = 5381;
        if (skey != null) {
            for (int i = 0; i < skey.length(); i++) hash += (hash << 5) + skey.charAt(i);
        }
        return hash & 0x7fffffffL;
    }

    private static String cookieValue(String cookie, String name) {
        if (cookie == null) return "";
        for (String part : cookie.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).trim().equals(name)) return p.substring(eq + 1).trim();
        }
        return "";
    }

    private static String digitsOnly(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
        }
        return b.toString();
    }

    private static JsonObject parse(String text) throws FetchException {
        try {
            JsonElement el = JsonParser.parseString(text);
            if (el.isJsonObject()) return el.getAsJsonObject();
            throw new FetchException("接口返回非 JSON 对象");
        } catch (FetchException e) {
            throw e;
        } catch (Exception e) {
            throw new FetchException("接口返回解析失败");
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

    private static String firstStr(JsonObject obj, String... names) {
        for (String n : names) {
            String v = optStr(obj, n);
            if (!v.isEmpty()) return v;
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

    private static long firstLong(JsonObject obj, String... names) {
        for (String n : names) {
            long v = optLongOrNeg(obj, n);
            if (v >= 0) return v;
        }
        return -1;
    }

    private static long optLongOrNeg(JsonObject obj, String name) {
        try {
            if (obj != null && obj.has(name) && !obj.get(name).isJsonNull()) return obj.get(name).getAsLong();
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static String optMsg(JsonObject obj) {
        String m = optStr(obj, "msg");
        if (m.isEmpty()) m = optStr(obj, "message");
        return m.isEmpty() ? "" : "（" + m + "）";
    }

    private static String keys(JsonObject obj) {
        if (obj == null) return "";
        StringBuilder b = new StringBuilder();
        for (String k : obj.keySet()) {
            if (b.length() > 0) b.append("，");
            b.append(k);
        }
        return b.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }
}
