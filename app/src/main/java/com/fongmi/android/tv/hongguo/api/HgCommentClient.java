package com.fongmi.android.tv.hongguo.api;

import com.fongmi.android.tv.api.FqCommentSigner;
import com.fongmi.android.tv.hongguo.model.HgComment;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 红果 fqnovel 面客户端（hongguo 包自有实现）：评论读三口＋视频点赞 do_action。
 *
 * <p>为什么不直接用 api/HongguoCommentApi：那份是海阔规则年代与 HongguoDanmakuApi
 * 同层共建的壳子抓取器，挂在点播播放链上、类与传输都是包内共用形态；原生刷剧面
 * 若直连它，播放页就依赖规则年代的类与它的包内共用传输（post 是包内可见、设备号
 * 与签名口径绑在那份实现上），后续任一侧改动都会互相牵连。故在本包内按同一份
 * 已实证协议（hongguo-comments-shell-design.md 双证锁定，请求体逐字段照那份在产
 * 实现）重写一份：签名器 FqCommentSigner 是树里共用的纯算法类，直接复用；
 * 设备号沿用同一 Prefers 键，与那份抓取器共用同一设备身份。</p>
 *
 * <p>异步口径同 HgApiClient：OkHttp 分发线程执行与解析，回调不在主线程。</p>
 */
public final class HgCommentClient {

    public static final String HOST = "https://api5-normal-sinfonlineb.fqnovel.com";
    private static final String UA = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static volatile String deviceId;
    private static volatile String iid;
    private volatile HgAuthProvider authProvider;

    public void setAuthProvider(HgAuthProvider provider) {
        this.authProvider = provider;
    }

    // ---------- 请求体（逐字段照在产 HongguoCommentApi 的已实证口径） ----------

    public static JsonObject episodeListPayload(String seriesId, String vid, String cursor) {
        JsonObject payload = new JsonObject();
        payload.addProperty("comment_source", 4);
        payload.addProperty("server_channel", 18);
        payload.addProperty("group_id", vid);
        payload.addProperty("group_type", 30);
        payload.addProperty("comment_type", 4);
        payload.addProperty("sort", 1);
        payload.addProperty("need_count", true);
        payload.addProperty("count", 20);
        payload.addProperty("cursor", cursor == null ? "" : cursor);
        payload.addProperty("aid", 8662);
        payload.addProperty("compliance_status", 0);
        JsonObject business = new JsonObject();
        business.addProperty("book_id", seriesId);
        business.addProperty("req_type", 0);
        payload.add("business_param", business);
        return payload;
    }

    public static JsonObject seriesListPayload(String seriesId, String cursor) {
        JsonObject payload = new JsonObject();
        JsonObject business = new JsonObject();
        business.addProperty("book_id", seriesId);
        business.addProperty("need_count", true);
        business.addProperty("real_level", 2);
        business.addProperty("req_type", 0);
        payload.add("business_param", business);
        payload.addProperty("comment_source", 1);
        payload.addProperty("comment_type", 2);
        payload.addProperty("count", 10);
        payload.addProperty("group_id", seriesId);
        payload.addProperty("group_type", 1);
        payload.addProperty("server_channel", 34);
        payload.addProperty("sort", 1);
        if (cursor != null && !cursor.isEmpty()) payload.addProperty("cursor", cursor);
        return payload;
    }

    public static JsonObject episodeRepliesPayload(String seriesId, String vid, String commentId, String cursor) {
        JsonObject payload = new JsonObject();
        payload.addProperty("aid", 8662);
        JsonObject business = new JsonObject();
        business.addProperty("book_id", seriesId);
        business.addProperty("need_count", false);
        payload.add("business_param", business);
        payload.addProperty("comment_id", commentId);
        payload.addProperty("comment_source", 504);
        payload.addProperty("comment_type", 4);
        payload.addProperty("compliance_status", 0);
        payload.addProperty("count", 10);
        payload.addProperty("cursor", cursor == null ? "" : cursor);
        payload.addProperty("group_id", vid);
        payload.addProperty("group_type", 30);
        payload.addProperty("server_channel", 18);
        return payload;
    }

    public static JsonObject seriesRepliesPayload(String seriesId, String commentId, String cursor) {
        JsonObject payload = new JsonObject();
        JsonObject business = new JsonObject();
        business.addProperty("book_id", seriesId);
        business.addProperty("need_count", cursor == null || cursor.isEmpty());
        business.addProperty("real_level", 2);
        payload.add("business_param", business);
        payload.addProperty("comment_id", commentId);
        payload.addProperty("comment_source", 501);
        payload.addProperty("comment_type", 2);
        payload.addProperty("count", 10);
        payload.addProperty("group_id", seriesId);
        payload.addProperty("group_type", 1);
        payload.addProperty("server_channel", 34);
        if (cursor != null && !cursor.isEmpty()) payload.addProperty("cursor", cursor);
        return payload;
    }

    // ---------- 评论读 ----------

    /** 单集评论一页。cursor 首页传空串，翻页传上一页 Page.nextCursor 原样回传。 */
    public void episodeComments(String seriesId, String vid, String cursor, HgCallback<HgComment.Page> cb) {
        if (!isId(seriesId) || !isId(vid)) {
            cb.onError(-1, "id 非法");
            return;
        }
        String cur = cursor == null ? "" : cursor;
        post("comment/list/" + vid, episodeListPayload(seriesId, vid, cur), 4, 18, new TextCallback() {
            @Override
            void onText(String text) {
                HgComment.Page page = HgCommentParser.parseList(text, seriesId, vid, cur, false);
                if (page == null) cb.onError(-2, "评论回包解析失败");
                else cb.onSuccess(page);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /**
     * 弹幕档请求体（挖掘账 danmaku-dig-20261028.md 定版）：与单集评论同端点，
     * 换弹幕档枚举（source 601／channel 1000／type 20）＋时间窗三件套
     * （start_offset_time＝播放位置毫秒、playlet_item_duration＝本集时长毫秒、
     * need_danmaku_guide_type 全档）；end_offset_time 不传、服务端切 30 秒窗。
     */
    public static JsonObject danmakuPayload(String seriesId, String vid, long startOffsetMs,
                                            long durationMs, String cursor) {
        JsonObject payload = new JsonObject();
        payload.addProperty("comment_source", 601);
        payload.addProperty("server_channel", 1000);
        payload.addProperty("group_id", vid);
        payload.addProperty("group_type", 30);
        payload.addProperty("comment_type", 20);
        payload.addProperty("sort", 1);
        payload.addProperty("count", 90);
        payload.addProperty("cursor", cursor == null ? "" : cursor);
        payload.addProperty("aid", 8662);
        payload.addProperty("compliance_status", 0);
        JsonObject business = new JsonObject();
        business.addProperty("book_id", seriesId);
        business.addProperty("start_offset_time", Math.max(0, startOffsetMs));
        if (durationMs > 0) business.addProperty("playlet_item_duration", durationMs);
        com.google.gson.JsonArray guides = new com.google.gson.JsonArray();
        guides.add(1);
        guides.add(3);
        guides.add(4);
        guides.add(2);
        business.add("need_danmaku_guide_type", guides);
        payload.add("business_param", business);
        return payload;
    }

    /** 弹幕一窗：startOffsetMs 为本窗起点（窗界由服务端定），cursor 续拉原样回传。 */
    public void episodeDanmaku(String seriesId, String vid, long startOffsetMs, long durationMs,
                               String cursor, HgCallback<com.fongmi.android.tv.hongguo.model.HgDanmaku.Window> cb) {
        if (!isId(seriesId) || !isId(vid)) {
            cb.onError(-1, "id 非法");
            return;
        }
        post("comment/list/" + vid, danmakuPayload(seriesId, vid, startOffsetMs, durationMs, cursor), 601, 1000, new TextCallback() {
            @Override
            void onText(String text) {
                com.fongmi.android.tv.hongguo.model.HgDanmaku.Window w = HgCommentParser.parseDanmakuWindow(text);
                if (w == null) cb.onError(-2, "弹幕回包解析失败");
                else cb.onSuccess(w);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** 整剧剧评一页。 */
    public void seriesComments(String seriesId, String cursor, HgCallback<HgComment.Page> cb) {
        if (!isId(seriesId)) {
            cb.onError(-1, "id 非法");
            return;
        }
        String cur = cursor == null ? "" : cursor;
        post("comment/list/" + seriesId, seriesListPayload(seriesId, cur), 1, 34, new TextCallback() {
            @Override
            void onText(String text) {
                HgComment.Page page = HgCommentParser.parseList(text, seriesId, seriesId, cur, true);
                if (page == null) cb.onError(-2, "评论回包解析失败");
                else cb.onSuccess(page);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** 单集评论的回复一页。 */
    public void episodeReplies(String seriesId, String vid, String commentId, String cursor, HgCallback<HgComment.Page> cb) {
        if (!isId(seriesId) || !isId(vid) || commentId == null || commentId.isEmpty()) {
            cb.onError(-1, "id 非法");
            return;
        }
        String cur = cursor == null ? "" : cursor;
        post("reply/list/" + commentId, episodeRepliesPayload(seriesId, vid, commentId, cur), 504, 18, new TextCallback() {
            @Override
            void onText(String text) {
                HgComment.Page page = HgCommentParser.parseReplies(text, seriesId, vid, cur);
                if (page == null) cb.onError(-2, "回复回包解析失败");
                else cb.onSuccess(page);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** 剧评的回复一页。 */
    public void seriesReplies(String seriesId, String commentId, String cursor, HgCallback<HgComment.Page> cb) {
        if (!isId(seriesId) || commentId == null || commentId.isEmpty()) {
            cb.onError(-1, "id 非法");
            return;
        }
        String cur = cursor == null ? "" : cursor;
        post("reply/list/" + commentId, seriesRepliesPayload(seriesId, commentId, cur), 501, 34, new TextCallback() {
            @Override
            void onText(String text) {
                HgComment.Page page = HgCommentParser.parseReplies(text, seriesId, seriesId, cur);
                if (page == null) cb.onError(-2, "回复回包解析失败");
                else cb.onSuccess(page);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    // ---------- 视频点赞 ----------

    /**
     * 视频点赞/取消 POST /novel/articleapi/do_action/v1/（请求体见
     * HgRequests.doArticleActionBody 出处注）。成功回调带服务端新计数
     * （action_cnt_data[vid]，取不到为 -1）；code 非成功算失败。
     * 宿主与签名口径同 commentapi（同属 fqnovel saas ugc 面）——宿主未经
     * 样本单独定版，待真机校准（见四阶段回报未定项）。
     */
    public void doArticleAction(boolean like, String videoId, String seriesId, HgCallback<Long> cb) {
        JsonObject payload = HgRequests.doArticleActionBody(like, videoId, seriesId);
        postRaw("/novel/articleapi/do_action/v1/", payload, 0, 0, new TextCallback() {
            @Override
            void onText(String text) {
                Long count = HgCommentParser.parseActionCount(text, videoId);
                if (count == null) cb.onError(-2, "点赞被服务端拒绝");
                else cb.onSuccess(count);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    // ---------- 传输（照 HongguoCommentApi.post 的签名/设备口径） ----------

    private abstract static class TextCallback {
        abstract void onText(String text);

        abstract void onFail(int code, String msg);
    }

    private void post(String path, JsonObject payload, int source, int channel, TextCallback cb) {
        postRaw("/novel/commentapi/" + path + "/v1/", payload, source, channel, cb);
    }

    private void postRaw(String path, JsonObject payload, int source, int channel, TextCallback cb) {
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
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "application/json");
        headers.put("X-XS-From-Web", "0");
        headers.put("Sdk-Version", "2");
        if (source > 0) headers.put("Comment-Source", String.valueOf(source));
        if (channel > 0) headers.put("Server-Channel", String.valueOf(channel));
        headers.putAll(FqCommentSigner.headers(encoded));
        HgAuthProvider auth = authProvider;
        if (auth != null) {
            Map<String, String> extra = auth.headers();
            if (extra != null) headers.putAll(extra);
            String cookie = auth.cookie();
            if (cookie != null && !cookie.isEmpty()) headers.put("Cookie", cookie);
        }
        Call call = OkHttp.newCall(HOST + path + "?" + encoded, headers, RequestBody.create(JSON, payload.toString()));
        if (call == null) {
            cb.onFail(-1, "无法创建请求");
            return;
        }
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                cb.onFail(-1, e.getMessage());
            }

            @Override
            public void onResponse(Call c, Response response) {
                try (Response r = response) {
                    ResponseBody rb = r.body();
                    String text = rb == null ? "" : rb.string();
                    if (!r.isSuccessful()) cb.onFail(r.code(), "HTTP " + r.code());
                    else if (text.isEmpty()) cb.onFail(r.code(), "回包空体");
                    else cb.onText(text);
                } catch (Exception e) {
                    cb.onFail(-1, e.getMessage());
                }
            }
        });
    }

    static boolean isId(String s) {
        if (s == null || s.length() < 6 || s.length() > 30) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    // ---------- 设备号（与规则年代抓取器共用同一 Prefers 键＝同一设备身份） ----------

    private static String deviceId() {
        if (deviceId != null) return deviceId;
        synchronized (HgCommentClient.class) {
            if (deviceId == null) deviceId = loadOrCreate("fq_comment_device_id", "fq.comment.device_id");
            return deviceId;
        }
    }

    private static String iid() {
        if (iid != null) return iid;
        synchronized (HgCommentClient.class) {
            if (iid == null) iid = loadOrCreate("fq_comment_iid", "fq.comment.iid");
            return iid;
        }
    }

    private static String loadOrCreate(String key, String prop) {
        String fromProp = System.getProperty(prop);
        if (fromProp != null && isId(fromProp)) return fromProp;
        try {
            String saved = Prefers.getString(key);
            if (isId(saved)) return saved;
        } catch (Throwable ignored) {
        }
        String created = randomDeviceNumber();
        try {
            Prefers.put(key, created);
        } catch (Throwable ignored) {
        }
        return created;
    }

    private static String randomDeviceNumber() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(19);
        sb.append(7);
        for (int i = 0; i < 18; i++) sb.append(random.nextInt(10));
        return sb.toString();
    }
}
