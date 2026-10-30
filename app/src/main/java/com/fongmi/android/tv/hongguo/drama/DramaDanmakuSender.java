package com.fongmi.android.tv.hongguo.drama;

import com.fongmi.android.tv.api.FqCommentSigner;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 发弹幕（2026-10-30 从零重建）：POST /novel/commentapi/comment/add/v1/，
 * 载荷与传输口径照旧定版（danmakuPostPayload＋评论栈 postRaw）——
 * 设备号与壳子评论栈共用同一 Prefers 键（fq_comment_device_id /
 * fq_comment_iid，同一设备身份），签名走 FqCommentSigner；不带任何
 * 账号 Cookie（旧实现里 authProvider 只在登录态存在时附加，登录线
 * 已整件摘除，本类恒匿名发）。
 *
 * <p>回包判定（旧 writeResult 口径）：code 数值 0 或字符串
 * "0"/"SUCCESS" 为成功；结构化非 0＝服务端拒绝；解不出＝失败。</p>
 */
public final class DramaDanmakuSender {

    private static final String HOST = "https://api5-normal-sinfonlineb.fqnovel.com";
    private static final String UA = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private DramaDanmakuSender() {
    }

    public static void publish(String seriesId, String vid, String text, long offsetMs,
                               HgCallback<Void> cb) {
        if (!isId(seriesId) || !isId(vid)) {
            cb.onError(-1, "id 非法");
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            cb.onError(-1, "弹幕为空");
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("commit_source", 1500);
        payload.addProperty("group_id", vid);
        payload.addProperty("group_type", 30);
        payload.addProperty("data_type", 20);
        payload.addProperty("text", text);
        payload.addProperty("aid", 8662);
        JsonObject business = new JsonObject();
        business.addProperty("book_id", seriesId);
        business.addProperty("offset", Math.max(0, offsetMs));
        business.addProperty("ignore_urge_rule", false);
        JsonObject shark = new JsonObject();
        shark.addProperty("type", "short_play");
        shark.addProperty("aid", "8662");
        business.add("shark_param", shark);
        payload.add("business_param", business);

        long now = System.currentTimeMillis();
        Map<String, String> query = new TreeMap<>();
        query.put("aid", "8662");
        query.put("app_name", "novelread");
        query.put("version_code", "73532");
        query.put("version_name", "7.3.5.32");
        query.put("manifest_version_code", "73532");
        query.put("update_version_code", "73532");
        query.put("channel", "update_64");
        query.put("device_platform", "android");
        query.put("os", "android");
        query.put("ssmix", "a");
        query.put("device_type", "25053RT47C");
        query.put("device_brand", "Redmi");
        query.put("language", "zh");
        query.put("os_api", "36");
        query.put("os_version", "16");
        query.put("resolution", "1280*2772");
        query.put("dpi", "520");
        query.put("ac", "wifi");
        query.put("device_id", deviceId());
        query.put("iid", iid());
        query.put("_rticket", String.valueOf(now));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(enc(e.getValue()));
        }
        String encoded = sb.toString();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "application/json");
        headers.put("X-XS-From-Web", "0");
        headers.put("Sdk-Version", "2");
        byte[] nonce = new byte[11];
        new SecureRandom().nextBytes(nonce);
        headers.putAll(FqCommentSigner.headers(encoded, now, nonce));
        Call call = OkHttp.newCall(HOST + "/novel/commentapi/comment/add/v1/?" + encoded,
                headers, RequestBody.create(payload.toString(), JSON));
        if (call == null) {
            cb.onError(-1, "无法创建请求");
            return;
        }
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                cb.onError(-1, e.getMessage());
            }

            @Override
            public void onResponse(Call c, Response response) {
                try (Response r = response) {
                    ResponseBody rb = r.body();
                    String text2 = rb == null ? "" : rb.string();
                    if (!r.isSuccessful()) {
                        cb.onError(r.code(), "HTTP " + r.code());
                        return;
                    }
                    int w = writeResult(text2);
                    if (w < 0) cb.onError(-2, "发弹幕回包解析失败");
                    else if (w == 0) cb.onError(-2, "发弹幕被服务端拒绝");
                    else cb.onSuccess(null);
                } catch (Exception e) {
                    cb.onError(-1, e.getMessage());
                }
            }
        });
    }

    /** 回包成败（旧 HgCommentParser.writeResult 口径）：1 成功 / 0 拒绝 / -1 解不出。 */
    static int writeResult(String text) {
        if (text == null || text.isEmpty()) return -1;
        try {
            com.google.gson.JsonElement el = JsonParser.parseString(text);
            if (el == null || !el.isJsonObject()) return -1;
            JsonObject root = el.getAsJsonObject();
            if (!root.has("code")) return -1;
            com.google.gson.JsonElement code = root.get("code");
            boolean ok;
            if (code.isJsonPrimitive() && code.getAsJsonPrimitive().isNumber()) {
                ok = code.getAsInt() == 0;
            } else {
                String s = code.getAsString();
                ok = "0".equals(s) || "SUCCESS".equalsIgnoreCase(s);
            }
            return ok ? 1 : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    static boolean isId(String s) {
        return com.fongmi.android.tv.api.HongguoCommentApi.isId(s);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    // ---------- 设备号（与壳子评论栈共用同一 Prefers 键＝同一设备身份） ----------

    private static volatile String deviceId;
    private static volatile String iid;

    private static String deviceId() {
        if (deviceId != null) return deviceId;
        synchronized (DramaDanmakuSender.class) {
            if (deviceId == null) deviceId = loadOrCreate("fq_comment_device_id");
            return deviceId;
        }
    }

    private static String iid() {
        if (iid != null) return iid;
        synchronized (DramaDanmakuSender.class) {
            if (iid == null) iid = loadOrCreate("fq_comment_iid");
            return iid;
        }
    }

    private static String loadOrCreate(String key) {
        try {
            String saved = Prefers.getString(key);
            if (isId(saved)) return saved;
        } catch (Throwable ignored) {
        }
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(19);
        sb.append(7);
        for (int i = 0; i < 18; i++) sb.append(random.nextInt(10));
        String created = sb.toString();
        try {
            Prefers.put(key, created);
        } catch (Throwable ignored) {
        }
        return created;
    }
}
