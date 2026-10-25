package com.fongmi.android.tv.api.music;

import android.text.TextUtils;

import com.fongmi.android.tv.login.LoginManager;
import com.fongmi.android.tv.login.QQLoginProvider;
import com.fongmi.quickjs.bean.Req;
import com.fongmi.quickjs.utils.Connect;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONObject;

import okhttp3.Response;

/**
 * QQ 官方 vkey 带登录态取链（落雪 tx 兜底）：读壳子统一登录托管的 QQ Cookie，
 * 调官方 CgiGetVkey（vkey.GetVkeyServer）拿 sip＋purl 拼全链。
 * <p>
 * 契约口径（2026-10-25 核过官方网页端现行做法＋公开实现，匿名/伪造 Cookie 回包
 * 形态本机实测留证）：POST u.y.qq.com/cgi-bin/musicu.fcg，comm 走 yqq.json
 * （ct=24、cv=4747474、uin=账号数字），req_0.param 带 guid（随机 10 位）、
 * songmid、uin、loginflag=1、platform=20、filename（前缀＋media_mid＋扩展名：
 * 128k=M500.mp3／320k=M800.mp3／flac=F000.flac／母带=AI00.flac、RS01.flac 备选）。
 * 回包取 req_0.data：sip 选第一条非 ws 开头者（无则第一条），purl 为空＝没链
 * （未登录/无版权/账号无权益同形，调用方一律按失败走）。真账号端到端未验，
 * 待他真机登一次验通。
 * 全部方法阻塞，只能在子线程调（取链链本就在音乐池）。
 */
public final class QqVkeyFetcher {

    private QqVkeyFetcher() {
    }

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final int TIMEOUT = 8000;

    /** 未登录弹统一登录框的进程内节流（与 FreeJsBridge 同口径 10 分钟）：取链回落会反复进来，
     * 没有节流就是连环弹窗；弹与不弹都不拦本次调用，本次照常按失败返回。
     * 戳只在投递被接受后写（见 maybePromptLogin），投不出去的那次不占节流窗。 */
    private static final long LOGIN_PROMPT_INTERVAL_MS = 10 * 60 * 1000L;
    private static volatile long lastLoginPromptAt = 0;

    public static boolean isLoggedIn() {
        return LoginManager.isLoggedIn(QQLoginProvider.ID);
    }

    /** 未登录时请壳子弹统一登录框（节流见上）；已登录/节流窗内静默。 */
    public static void maybePromptLogin() {
        try {
            if (isLoggedIn()) return;
            long now = System.currentTimeMillis();
            if (now - lastLoginPromptAt < LOGIN_PROMPT_INTERVAL_MS) return;
            // 节流戳后写：requestLogin 回 true（弹窗已投递）才落戳。先写戳后投递
            // 时，无前台界面的那次空投也会把节流窗占满，之后真能弹时反被自己堵死
            if (LoginManager.requestLogin(QQLoginProvider.ID)) lastLoginPromptAt = now;
        } catch (Throwable ignored) {
        }
    }

    /**
     * 带登录态取链：lxQuality 为落雪档位串（128k／320k／flac／flac24bit）。
     * 未登录、参数缺失、接口无链一律返回 null，不抛错扰动取链链。
     */
    public static String playUrl(String songmid, String mediaMid, String lxQuality) {
        try {
            if (TextUtils.isEmpty(songmid)) return null;
            String cookie = LoginManager.getCookie(QQLoginProvider.ID);
            if (TextUtils.isEmpty(cookie) || !QQLoginProvider.isCookieValid(cookie)) return null;
            String uin = uinOf(cookie);
            if (uin.isEmpty()) return null;
            String media = TextUtils.isEmpty(mediaMid) ? songmid : mediaMid;
            for (String[] attempt : filenameAttempts(lxQuality)) {
                String url = requestVkey(songmid, media, attempt[0] + media + attempt[1], uin, cookie);
                if (!TextUtils.isEmpty(url)) return url;
            }
            return null;
        } catch (Throwable t) {
            SpiderDebug.log("music", "qq vkey fallback error %s", t.getMessage());
            return null;
        }
    }

    /** 档位 → filename 前缀/扩展名候选（母带两前缀先后试：AI00 现行母带、RS01 高清备选）。 */
    private static String[][] filenameAttempts(String lxQuality) {
        switch (lxQuality == null ? "" : lxQuality) {
            case "128k":
                return new String[][]{{"M500", ".mp3"}};
            case "flac":
                return new String[][]{{"F000", ".flac"}};
            case "flac24bit":
                return new String[][]{{"AI00", ".flac"}, {"RS01", ".flac"}};
            default:
                return new String[][]{{"M800", ".mp3"}};
        }
    }

    private static String requestVkey(String songmid, String mediaMid, String filename, String uin, String cookie) throws Exception {
        JsonObject body = new JsonObject();
        JsonObject comm = new JsonObject();
        comm.addProperty("cv", 4747474);
        comm.addProperty("ct", 24);
        comm.addProperty("format", "json");
        comm.addProperty("inCharset", "utf-8");
        comm.addProperty("outCharset", "utf-8");
        comm.addProperty("notice", 0);
        comm.addProperty("platform", "yqq.json");
        comm.addProperty("needNewCode", 1);
        comm.addProperty("uin", parseLong(uin));
        body.add("comm", comm);
        JsonObject req0 = new JsonObject();
        req0.addProperty("module", "vkey.GetVkeyServer");
        req0.addProperty("method", "CgiGetVkey");
        JsonObject param = new JsonObject();
        param.addProperty("guid", String.valueOf(1000000000L + (long) (Math.random() * 9000000000L)));
        JsonArray mids = new JsonArray();
        mids.add(songmid);
        param.add("songmid", mids);
        JsonArray types = new JsonArray();
        types.add(0);
        param.add("songtype", types);
        param.addProperty("uin", uin);
        param.addProperty("loginflag", 1);
        param.addProperty("platform", "20");
        JsonArray files = new JsonArray();
        files.add(filename);
        param.add("filename", files);
        req0.add("param", param);
        body.add("req_0", req0);

        String text = httpPostJson("https://u.y.qq.com/cgi-bin/musicu.fcg", body.toString(), cookie);
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        if (optLong(root, "code") != 0) return null;
        JsonObject r0 = root.has("req_0") && root.get("req_0").isJsonObject() ? root.getAsJsonObject("req_0") : null;
        if (r0 == null || optLong(r0, "code") != 0) return null;
        JsonObject data = r0.has("data") && r0.get("data").isJsonObject() ? r0.getAsJsonObject("data") : null;
        if (data == null) return null;
        JsonArray infos = data.has("midurlinfo") && data.get("midurlinfo").isJsonArray() ? data.getAsJsonArray("midurlinfo") : null;
        if (infos == null || infos.size() == 0 || !infos.get(0).isJsonObject()) return null;
        String purl = optStr(infos.get(0).getAsJsonObject(), "purl");
        if (purl.isEmpty()) return null;
        if (purl.startsWith("http")) return purl;
        String sip = pickSip(data);
        return sip.isEmpty() ? null : sip + purl;
    }

    /** sip 选第一条非 ws 开头者（ws 系是试听域名），全是 ws 时退第一条。 */
    private static String pickSip(JsonObject data) {
        JsonArray sip = data.has("sip") && data.get("sip").isJsonArray() ? data.getAsJsonArray("sip") : null;
        if (sip == null || sip.size() == 0) return "";
        String first = "";
        for (JsonElement el : sip) {
            if (!el.isJsonPrimitive()) continue;
            String s = el.getAsString();
            if (s.isEmpty()) continue;
            if (first.isEmpty()) first = s;
            if (!s.startsWith("http://ws")) return s;
        }
        return first;
    }

    /** 经 quickjs Connect 走壳子 OkHttp（吃代理/DoH/节点定向），与榜单/歌单导入同出口 */
    private static String httpPostJson(String url, String body, String cookie) throws Exception {
        JSONObject reqJson = new JSONObject();
        reqJson.put("method", "POST");
        JSONObject headers = new JSONObject();
        headers.put("User-Agent", UA);
        headers.put("Referer", "https://y.qq.com/");
        headers.put("Content-Type", "application/json");
        if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
        reqJson.put("headers", headers);
        reqJson.put("body", body);
        reqJson.put("timeout", TIMEOUT);
        reqJson.put("redirect", 1);
        Req req = Req.objectFrom(reqJson.toString());
        try (Response res = Connect.to(url, req).execute()) {
            if (!res.isSuccessful()) throw new Exception("http " + res.code());
            byte[] bytes = res.body() != null ? res.body().bytes() : new byte[0];
            return new String(bytes, req.getCharset());
        }
    }

    /** 账号数字：uin 优先（去 o0 前缀等非数字），微信登录态退 wxuin，再退 qqmusic_uin。 */
    private static String uinOf(String cookie) {
        String uin = digitsOnly(cookieValue(cookie, "uin"));
        if (!uin.isEmpty()) return uin;
        uin = digitsOnly(cookieValue(cookie, "wxuin"));
        if (!uin.isEmpty()) return uin;
        return digitsOnly(cookieValue(cookie, "qqmusic_uin"));
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

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
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
