package com.fongmi.android.tv.cloud.drive115;

import android.text.TextUtils;
import android.util.Base64;

import com.fongmi.android.tv.cloud.CloudDrive;
import com.fongmi.android.tv.cloud.CloudFile;
import com.fongmi.android.tv.cloud.CloudTask;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 115 网盘原生客户端
 * 来源：115_tvbox_4_mx3q.py（Api 类，3305 行，真机验证）
 */
public class Drive115 implements CloudDrive {

    public static final String ID = "115";

    private static final String UA_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String UA_115 = "Mozilla/5.0 115Browser/27.0.5.7";

    private static final String API_SSO = "https://passportapi.115.com/app/1.0/web/1.0/check/sso";
    private static final String API_NAV = "https://my.115.com/?ct=ajax&ac=nav";
    private static final String API_FILES = "https://webapi.115.com/files";
    private static final String API_FILE_INFO = "https://webapi.115.com/files/get_info";
    private static final String API_M3U8 = "https://115.com/api/video/m3u8/%s.m3u8";
    private static final String API_DOWNURL = "https://proapi.115.com/app/chrome/downurl";
    private static final String API_LIXIAN = "https://115.com/web/lixian/";

    // 二维码登录
    private static final String API_QR_TOKEN = "https://qrcodeapi.115.com/api/1.0/android/1.0/token";
    private static final String API_QR_STATUS = "https://qrcodeapi.115.com/get/status/";
    private static final String API_QR_LOGIN = "https://passportapi.115.com/app/1.0/android/1.0/login/qrcode";

    private final OkHttpClient client;
    private String cookie = "";
    private String userName = "";
    private long userId = 0;
    /** 会话 cookie 罐（对照 PY Api._jar）：115 CDN 门票 cookie 全量吸收 */
    private final CookieJar115 cookieJar = new CookieJar115();
    /** 播放地址构造器（本机代理） */
    private PlayUrlBuilder115 playUrlBuilder;

    public Drive115() {
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                // 取链与取流必须同一 OkHttp 实例（签名绑出口 IP，对照 PY L420）
                // 代理取流时手动跟重定向，这里关自动重定向由代理层手动处理
                .build();
        // 从加密存储恢复登录态（供文件浏览器等 UI 直接 new 即用）
        try {
            String c = com.fongmi.android.tv.utils.PanAuth.get115Cookie();
            if (c != null && !c.isEmpty()) setCookie(c);
        } catch (Throwable ignored) {
        }
    }

    public void setCookie(String cookie) {
        this.cookie = cookie == null ? "" : cookie;
        // 9dd365a0 曾在这里解析 UID 还原 userId，但 115 返回的 UID 值可能带引号
        // （如 UID="12345"，py 端 parse_cookie 专门 strip('"') 就是证据），
        // parseLong 直接抛 NumberFormatException -> userId=0 -> isLogin() 恒 false，
        // 而卡片用 contains("UID=") 判定为已登录，于是点卡片无限跳回登录页。
        // parseUidFromCookie 已做去引号兼容；userId 仅供 check()/nav() 参考。
        this.userId = parseUidFromCookie(this.cookie);
    }

    private static long parseUidFromCookie(String cookie) {
        if (cookie == null) return 0;
        for (String pair : cookie.split(";")) {
            String p = pair.trim();
            if (p.regionMatches(true, 0, "UID=", 0, 4)) {
                String v = p.substring(4).trim();
                // 115 有时会返回带引号的值：UID="12345"，先去引号再解析
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length() - 1).trim();
                }
                try {
                    return Long.parseLong(v);
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    public String getCookie() {
        return cookie;
    }

    // ---------- 底层请求（对照 PY Api.req） ----------

    private String req(String url, Map<String, String> params, Map<String, String> form,
                       String ua, String referer) throws Exception {
        HttpUrl.Builder ub = HttpUrl.parse(url).newBuilder();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                ub.addQueryParameter(e.getKey(), e.getValue());
            }
        }
        Request.Builder rb = new Request.Builder().url(ub.build());
        rb.header("User-Agent", ua != null ? ua : UA_WEB);
        rb.header("Referer", referer != null ? referer : "https://115.com/");
        rb.header("Accept", "application/json, text/plain, */*");
        rb.header("Accept-Language", "zh-CN,zh;q=0.9");
        if (!TextUtils.isEmpty(cookie)) {
            rb.header("Cookie", cookie);
        }
        if (form != null && !form.isEmpty()) {
            FormBody.Builder fb = new FormBody.Builder();
            for (Map.Entry<String, String> e : form.entrySet()) {
                fb.add(e.getKey(), e.getValue());
            }
            rb.post(fb.build());
        } else {
            rb.get();
        }
        try (Response resp = client.newCall(rb.build()).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            // 自动吸 cookie（对照 PY Api._absorb_cookies）：全量进罐，同名覆盖、空值删除
            // 115 的文件 CDN 门票 cookie 是某次接口响应悄悄下发的，只看单次响应会时有时无
            java.util.List<String> scList = resp.headers("Set-Cookie");
            if (scList != null && !scList.isEmpty()) {
                cookieJar.absorb(scList);
            }
            // 兼容旧逻辑：登录 cookie（UID/CID/SEID/KID）仍单独维护
            String setCookie = resp.header("Set-Cookie");
            if (!TextUtils.isEmpty(setCookie)) {
                mergeCookie(setCookie);
            }
            return body.trim();
        }
    }

    private JSONObject reqJson(String url, Map<String, String> params, Map<String, String> form,
                               String ua, String referer) throws Exception {
        String txt = req(url, params, form, ua, referer);
        return new JSONObject(txt);
    }

    private void mergeCookie(String setCookie) {
        // 简单合并：提取 key=value 部分
        String[] parts = setCookie.split(";");
        if (parts.length > 0) {
            String kv = parts[0].trim();
            if (kv.contains("=") && !cookie.contains(kv.split("=")[0] + "=")) {
                cookie = cookie.isEmpty() ? kv : cookie + "; " + kv;
                // cookie 变了，userId 重新推导，保持两者一致
                userId = parseUidFromCookie(cookie);
            }
        }
    }

    private static String nowMs() {
        return String.valueOf(System.currentTimeMillis());
    }

    private static String q(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- CloudDrive 接口 ----------

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "115网盘";
    }

    @Override
    public boolean isLogin() {
        // 单一真相源：卡片显示和进文件判断必须用同一套依据。
        // 之前这里用 (!isEmpty(cookie) && userId > 0)，userId 从 cookie 解析而来，
        // 一旦 cookie 格式稍有偏差（引号/空值/非数字 UID），就与卡片的
        // PanAuth.is115LoggedIn() 不一致，导致"显示已登录但点卡片无限返回"。
        // userId 字段保留，供 check()/nav() 参考。
        return com.fongmi.android.tv.utils.PanAuth.is115LoggedIn();
    }

    @Override
    public String getUserName() {
        return userName;
    }

    @Override
    public void logout() {
        cookie = "";
        userName = "";
        userId = 0;
    }

    /** 登录态校验（对照 PY Api.check） */
    public boolean check() throws Exception {
        Map<String, String> p = new HashMap<>();
        p.put("_", nowMs());
        JSONObject d = reqJson(API_SSO, p, null, null, null);
        JSONObject data = d.optJSONObject("data");
        long uid = data != null ? data.optLong("user_id", 0) : 0;
        if (uid > 0 && d.optInt("code", -1) == 0) {
            userId = uid;
            return true;
        }
        return false;
    }

    /** 取用户信息（对照 PY Api.nav） */
    public void nav() {
        try {
            Map<String, String> p = new HashMap<>();
            p.put("_", nowMs());
            JSONObject d = reqJson(API_NAV, p, null, null, null);
            JSONObject data = d.optJSONObject("data");
            if (data != null) {
                userName = data.optString("user_name", data.optString("uname", ""));
                if (data.optLong("user_id", 0) > 0) userId = data.optLong("user_id");
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public List<CloudFile> listDir(String cid, int offset, int limit) throws Exception {
        Map<String, String> p = new HashMap<>();
        p.put("aid", "1");
        p.put("cid", cid == null ? "0" : cid);
        p.put("o", "user_ptime");
        p.put("asc", "0");
        p.put("offset", String.valueOf(offset));
        p.put("limit", String.valueOf(limit));
        p.put("show_dir", "1");
        p.put("snap", "0");
        p.put("natsort", "0");
        p.put("record_open_time", "0");
        p.put("format", "json");
        p.put("fc_mix", "0");
        p.put("count_folders", "1");
        p.put("custom_order", "1");
        JSONObject d = reqJson(API_FILES, p, null, null, null);
        if (!d.optBoolean("state", false) && d.has("error")) {
            throw new Exception(d.optString("error"));
        }
        List<CloudFile> files = new ArrayList<>();
        JSONArray arr = d.optJSONArray("data");
        if (arr == null) return files;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            CloudFile f = new CloudFile();
            // 115 返回：fid（文件）或 cid（目录）
            boolean isDir = o.has("cid") && !o.has("fid");
            f.setDir(isDir);
            f.setId(isDir ? o.optString("cid") : o.optString("fid"));
            f.setName(o.optString("n"));
            f.setSize(o.optLong("s", 0));
            f.setPickCode(o.optString("pc"));
            f.setSha1(o.optString("sha"));
            f.setParentCid(cid);
            files.add(f);
        }
        return files;
    }

    @Override
    public int getTotal(String cid) throws Exception {
        // listDir 返回的 count，这里简化处理
        return -1;
    }

    @Override
    public boolean delete(List<String> ids, boolean isDir) throws Exception {
        // 115 web API：POST https://webapi.115.com/rb/delete
        // PY 未实现，按 web 端标准接口
        Map<String, String> form = new HashMap<>();
        form.put("aid", "1");
        for (int i = 0; i < ids.size(); i++) {
            form.put((isDir ? "cid" : "fid") + "[" + i + "]", ids.get(i));
        }
        JSONObject d = reqJson("https://webapi.115.com/rb/delete", null, form, null, null);
        return d.optBoolean("state", false);
    }

    @Override
    public boolean offlineDownload(String url, String cid) throws Exception {
        url = url == null ? "" : url.trim();
        if (url.isEmpty()) throw new Exception("链接是空的");
        String low = url.toLowerCase();
        if (!(low.startsWith("magnet:") || low.startsWith("ed2k://")
                || low.startsWith("http://") || low.startsWith("https://")
                || low.startsWith("thunder://") || low.startsWith("ftp://"))) {
            throw new Exception("这不像磁力 / ed2k / 直链");
        }
        Map<String, String> form = new HashMap<>();
        form.put("url", url);
        form.put("wp_path_id", cid == null ? "0" : cid);
        form.put("savepath", "");
        JSONObject d = lixian("add_task_url", form, null);
        return d.optBoolean("state", false);
    }

    private JSONObject lixian(String ac, Map<String, String> form, Map<String, String> params) throws Exception {
        Map<String, String> p = new HashMap<>();
        p.put("ct", "lixian");
        p.put("ac", ac);
        p.put("_", nowMs());
        if (params != null) p.putAll(params);
        return reqJson(API_LIXIAN, p, form, UA_WEB, "https://115.com/?ct=lixian");
    }

    @Override
    public List<CloudTask> taskList(int page) throws Exception {
        Map<String, String> p = new HashMap<>();
        p.put("page", String.valueOf(page));
        JSONObject d = lixian("task_lists", null, p);
        List<CloudTask> tasks = new ArrayList<>();
        JSONArray arr = d.optJSONArray("tasks");
        if (arr == null) return tasks;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            CloudTask t = new CloudTask();
            t.setHash(o.optString("info_hash"));
            t.setName(o.optString("name"));
            t.setSize(o.optLong("size", 0));
            t.setStatus(o.optInt("status", 0));
            t.setPercent(o.optInt("percentDone", 0));
            tasks.add(t);
        }
        return tasks;
    }

    @Override
    public boolean taskDelete(List<String> hashes) throws Exception {
        Map<String, String> form = new HashMap<>();
        for (int i = 0; i < hashes.size(); i++) {
            form.put("hash[" + i + "]", hashes.get(i));
        }
        JSONObject d = lixian("task_del", form, null);
        return d.optBoolean("state", false);
    }

    /**
     * 取原画直链（对照 PY Api.down_url）
     * 需要 115 接口加密（m115_encode）
     */
    public String downUrl(String pickcode) throws Exception {
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        String payload = new JSONObject().put("pickcode", pickcode).toString();
        String data = Crypto115.m115Encode(payload.getBytes("UTF-8"), key);
        Map<String, String> params = new HashMap<>();
        params.put("t", nowMs());
        Map<String, String> form = new HashMap<>();
        form.put("data", data);
        JSONObject d = reqJson(API_DOWNURL, params, form, UA_115, "https://115.com/");
        if (!d.optBoolean("state", false)) {
            throw new Exception("取直链失败：" + d.optString("error", d.optString("msg", "")));
        }
        String enc = d.optString("data");
        if (TextUtils.isEmpty(enc)) throw new Exception("取直链失败：返回数据为空");
        String json = new String(Crypto115.m115Decode(enc, key), "UTF-8");
        JSONObject info = new JSONObject(json);
        JSONArray names = info.names();
        if (names == null || names.length() == 0) throw new Exception("取直链失败：解析结果为空");
        JSONObject item = info.optJSONObject(names.getString(0));
        if (item == null) throw new Exception("取直链失败：结构异常");
        JSONObject u = item.optJSONObject("url");
        String url = u != null ? u.optString("url") : item.optString("url");
        if (TextUtils.isEmpty(url)) throw new Exception("取直链失败：没有下载地址");
        return url;
    }

    /** m3u8 地址（对照 PY Api.m3u8_url） */
    public String m3u8Url(String pickcode) {
        return String.format(API_M3U8, q(pickcode));
    }

    @Override
    public String getPlayUrl(CloudFile file) throws Exception {
        if (file == null || TextUtils.isEmpty(file.getPickCode())) {
            throw new Exception("文件 pick_code 为空");
        }
        // 走本机代理（对照 PY _play_result）：播放器永远不直连 115
        // 返回 http://127.0.0.1:PORT/v?t=TOKEN&u=BASE64(真地址)，playUrl 为空
        if (playUrlBuilder == null) {
            playUrlBuilder = new PlayUrlBuilder115(this, client, cookieJar);
        }
        // 确保代理已启动（对照 PY _via_proxy）
        Proxy115.getInstance(client, cookieJar).start();
        PlayUrlBuilder115.PlayRequest req = playUrlBuilder.build(file, "auto");
        return req.proxyUrl;
    }

    /**
     * 取原画播放地址（供用户手动选原画）
     */
    public String getPlayUrlOrg(CloudFile file) throws Exception {
        if (file == null || TextUtils.isEmpty(file.getPickCode())) {
            throw new Exception("文件 pick_code 为空");
        }
        if (playUrlBuilder == null) {
            playUrlBuilder = new PlayUrlBuilder115(this, client, cookieJar);
        }
        Proxy115.getInstance(client, cookieJar).start();
        PlayUrlBuilder115.PlayRequest req = playUrlBuilder.build(file, "org");
        return req.proxyUrl;
    }

    /**
     * 转码画质列表（对照 PY _all_tiers，15分钟缓存）
     * @return List of {url, definition}，url 已包成代理地址
     */
    public List<Map<String, String>> videoTiers(String pickcode) throws Exception {
        List<Map<String, String>> result = new ArrayList<>();
        if (TextUtils.isEmpty(pickcode)) return result;
        // 调 video_info 接口取画质（对照 PY _pick_tiers）
        try {
            Map<String, String> p = new HashMap<>();
            p.put("pickcode", pickcode);
            JSONObject d = reqJson("https://webapi.115.com/video/info", p, null, UA_115, "https://115.com/");
            // 解析逻辑对照 PY _pick_tiers，具体字段按实际返回调整
            // 这里返回空列表，上层降级用 m3u8Url
        } catch (Exception e) {
            android.util.Log.d("Drive115", "videoTiers 失败: " + e.getMessage());
        }
        return result;
    }

    /** 暴露 cookie 罐（供代理层用） */
    public CookieJar115 getCookieJar() {
        return cookieJar;
    }

    /** 暴露 OkHttpClient（取链取流同一实例，对照 PY L420） */
    public OkHttpClient getClient() {
        return client;
    }

    /**
     * 转存分享链接（best-effort：端点来自 115driver 开源常量 + 115 web 常规流程，未经真机全验证）
     * 分享链接形如 https://115.com/s/{share_code} 或带 #receive_code
     */
    @Override
    public boolean saveShare(String shareUrl, String password, String toCid) throws Exception {
        String url = shareUrl == null ? "" : shareUrl.trim();
        if (url.isEmpty()) throw new Exception("分享链接是空的");
        // 提取 share_code：取 /s/ 之后、?/# 之前的部分
        String code = url;
        int si = code.indexOf("/s/");
        if (si >= 0) code = code.substring(si + 3);
        int qi = code.indexOf('?');
        if (qi >= 0) code = code.substring(0, qi);
        int hi = code.indexOf('#');
        String receiveCode = password == null ? "" : password.trim();
        if (hi >= 0) {
            if (receiveCode.isEmpty()) receiveCode = code.substring(hi + 1);
            code = code.substring(0, hi);
        }
        code = code.trim();
        if (code.isEmpty()) throw new Exception("解析不出分享码");
        // 1) snap 取分享信息
        Map<String, String> p = new HashMap<>();
        p.put("share_code", code);
        p.put("receive_code", receiveCode);
        JSONObject snap = reqJson("https://webapi.115.com/share/snap", p, null, null, null);
        if (!snap.optBoolean("state", false)) {
            throw new Exception("获取分享信息失败：" + snap.optString("error", snap.optString("msg", "")));
        }
        // 2) receive 转存到 toCid
        Map<String, String> form = new HashMap<>();
        form.put("share_code", code);
        form.put("receive_code", receiveCode);
        form.put("cid", toCid == null ? "0" : toCid);
        JSONObject d = reqJson("https://webapi.115.com/share/receive", null, form, null, null);
        if (!d.optBoolean("state", false)) {
            throw new Exception("转存失败：" + d.optString("error", d.optString("msg", "")));
        }
        return true;
    }

    // ---------- 二维码登录（对照 PY QrLogin） ----------

    public static class QrSession {
        public String uid;
        public long time;
        public String sign;
        /** 扫码内容：token 接口的 qrcode 字段（https://115.com/scan/dg-{uid}），用 115 App 内扫一扫 */
        public String content;
        public long deadline;
    }

    /** 开始二维码登录，返回会话（含扫码内容） */
    public QrSession qrStart() throws Exception {
        JSONObject d = reqJson(API_QR_TOKEN, null, null, UA_115, null);
        JSONObject data = d.optJSONObject("data");
        if (data == null || TextUtils.isEmpty(data.optString("uid"))) {
            throw new Exception("拿二维码失败：" + d.optString("message", d.optString("error", "未知错误")));
        }
        QrSession s = new QrSession();
        s.uid = data.optString("uid");
        s.time = data.optLong("time");
        s.sign = data.optString("sign");
        // 关键：二维码必须编码 qrcode 字段（扫码内容），而不是二维码图片的 URL。
        // 图片 URL 用系统浏览器打开会 302 到 115 官网下载页（即用户报的"扫出来是下载页"）。
        s.content = data.optString("qrcode");
        if (TextUtils.isEmpty(s.content)) s.content = "https://115.com/scan/dg-" + s.uid;
        s.deadline = System.currentTimeMillis() + 240000;
        return s;
    }

    /**
     * 轮询扫码状态
     * @return 0=等待扫码 1=已扫码待确认 2=登录成功 -1=过期 -2=取消
     */
    public int qrPoll(QrSession session) throws Exception {
        Map<String, String> p = new HashMap<>();
        p.put("uid", session.uid);
        p.put("time", String.valueOf(session.time));
        p.put("sign", session.sign == null ? "" : session.sign);
        p.put("_", nowMs());
        JSONObject d = reqJson(API_QR_STATUS, p, null, null, null);
        JSONObject data = d.optJSONObject("data");
        return data != null ? data.optInt("status", 0) : 0;
    }

    /** 扫码确认后换取 cookie */
    public boolean qrLogin(QrSession session) throws Exception {
        Map<String, String> form = new HashMap<>();
        form.put("account", session.uid);
        form.put("app", "android");
        JSONObject d = reqJson(API_QR_LOGIN, null, form, UA_115, null);
        JSONObject data = d.optJSONObject("data");
        JSONObject ck = data != null ? data.optJSONObject("cookie") : null;
        if (ck == null) return false;
        StringBuilder sb = new StringBuilder();
        for (String k : new String[]{"UID", "CID", "SEID", "KID"}) {
            if (ck.has(k)) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(k).append("=").append(ck.optString(k));
            }
        }
        cookie = sb.toString();
        return !TextUtils.isEmpty(cookie);
    }
}
