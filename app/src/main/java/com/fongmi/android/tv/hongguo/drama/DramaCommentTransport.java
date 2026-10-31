package com.fongmi.android.tv.hongguo.drama;

import com.fongmi.android.tv.api.FqCommentSigner;
import com.fongmi.android.tv.api.HongguoCommentApi;
import com.fongmi.android.tv.hongguo.api.HgAccountStore;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

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
 * 评论栈共用传输（2026-10-30 从零重建）：POST /novel/commentapi/<path>/v1/，
 * 设备号与壳子评论栈共用同一 Prefers 键（fq_comment_device_id /
 * fq_comment_iid，同一设备身份），签名走 FqCommentSigner。post 恒
 * 匿名（不带任何账号 Cookie），发弹幕与作者作品流共用；postAuthed
 * 是发评论专用口（2026-10-31 增），同传输加挂账号会话头。
 */
public final class DramaCommentTransport {

    static final String HOST = "https://api5-normal-sinfonlineb.fqnovel.com";
    static final String UA = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public interface Sink {
        void onText(String text);

        void onFail(int code, String msg);
    }

    private DramaCommentTransport() {
    }

    public static void post(String path, String payloadJson, Sink sink) {
        post(path, payloadJson, null, sink);
    }

    /**
     * 带账号会话头的 POST（发评论专用，DramaCommentPoster 用）：在
     * post 的同一传输上加挂登录落盘的会话头（Cookie＋x-tt-token＋
     * x-tt-passport-csrf-token，照 HgAccountSync 会话头口径）。匿名
     * 的 post（发弹幕/作者作品流）不经此口、行为逐字不变。
     */
    public static void postAuthed(String path, String payloadJson, Sink sink) {
        Map<String, String> extra = new LinkedHashMap<>();
        HgAccountStore.Session session = HgAccountStore.load();
        if (session != null) {
            if (session.cookie != null && !session.cookie.isEmpty()) {
                extra.put("Cookie", session.cookie);
            }
            if (session.ttToken != null && !session.ttToken.isEmpty()) {
                extra.put("x-tt-token", session.ttToken);
            }
            if (session.csrf != null && !session.csrf.isEmpty()) {
                extra.put("x-tt-passport-csrf-token", session.csrf);
            }
        }
        post(path, payloadJson, extra, sink);
    }

    private static void post(String path, String payloadJson,
                             Map<String, String> extraHeaders, Sink sink) {
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
        if (extraHeaders != null) headers.putAll(extraHeaders);
        Call call = OkHttp.newCall(HOST + "/novel/commentapi/" + path + "/v1/?" + encoded,
                headers, RequestBody.create(payloadJson, JSON));
        if (call == null) {
            sink.onFail(-1, "无法创建请求");
            return;
        }
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                sink.onFail(-1, e.getMessage());
            }

            @Override
            public void onResponse(Call c, Response response) {
                try (Response r = response) {
                    ResponseBody rb = r.body();
                    String text = rb == null ? "" : rb.string();
                    if (!r.isSuccessful()) sink.onFail(r.code(), "HTTP " + r.code());
                    else if (text.isEmpty()) sink.onFail(-2, "回包空体");
                    else sink.onText(text);
                } catch (Exception e) {
                    sink.onFail(-1, e.getMessage());
                }
            }
        });
    }

    static boolean isId(String s) {
        return HongguoCommentApi.isId(s);
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
        synchronized (DramaCommentTransport.class) {
            if (deviceId == null) deviceId = loadOrCreate("fq_comment_device_id");
            return deviceId;
        }
    }

    private static String iid() {
        if (iid != null) return iid;
        synchronized (DramaCommentTransport.class) {
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
