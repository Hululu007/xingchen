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

    public Drive115() {
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public void setCookie(String cookie) {
        this.cookie = cookie == null ? "" : cookie;
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
            // 自动吸 cookie（对照 PY：Api.req 自动吸 cookie 进 jar）
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
        return !TextUtils.isEmpty(cookie) && userId > 0;
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
        // 优先原画直链（用户要求：115 原画播放要做好）
        try {
            return downUrl(file.getPickCode());
        } catch (Exception e) {
            // 降级到 m3u8
            return m3u8Url(file.getPickCode());
        }
    }

    // ---------- 二维码登录（对照 PY QrLogin） ----------

    public static class QrSession {
        public String uid;
        public String qrcodeUrl;
        public long deadline;
    }

    /** 开始二维码登录，返回会话（含二维码图 URL） */
    public QrSession qrStart() throws Exception {
        JSONObject d = reqJson(API_QR_TOKEN, null, null, UA_115, null);
        JSONObject data = d.optJSONObject("data");
        if (data == null || TextUtils.isEmpty(data.optString("uid"))) {
            throw new Exception("拿二维码失败：" + d.optString("message", d.optString("error", "未知错误")));
        }
        QrSession s = new QrSession();
        s.uid = data.optString("uid");
        s.qrcodeUrl = "https://qrcodeapi.115.com/api/1.0/android/1.0/qrcode?uid=" + q(s.uid);
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
