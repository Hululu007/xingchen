package com.fongmi.android.tv.hongguo.api;

import com.fongmi.android.tv.hongguo.model.HgCellPage;
import com.fongmi.android.tv.hongguo.model.HgParser;
import com.fongmi.android.tv.hongguo.model.HgSeriesDetail;
import com.fongmi.android.tv.hongguo.model.HgVideoModel;
import com.fongmi.android.tv.hongguo.sign.HgSign;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 红果接口客户端（宿主 reading.snssdk.com），接口表照 structure.md 第二节。
 *
 * <p>网络栈沿用树里现有口径：com.github.catvod.net.OkHttp 出 Call、OkHttp 分发
 * 线程异步执行、解析在分发线程完成（不占主线程），回调口径见 {@link HgCallback}。</p>
 *
 * <p>取址链定罪（2026-10-28，openhongguo-dig-20261028.md）：1.4.53 真机所有
 * 条目加载失败的根是取模型环——旧链无签名头（X-Gorgon/X-Argus/X-Helios/
 * X-Medusa/X-Ladon/X-Khronos/X-SS-STUB）且每次请求随机编造 iid/device_id，
 * 网关一律回 200 空体（A/B 隔离实测：同出口同分钟，带签名全回、剥签名即空，
 * Cookie 不是门、本机海外出口也不是门）。现取模型/详情改走 fqnovel 已签名口：
 * HgDevice 匿名注册拿真 device_id/iid 落盘复用＋HgSign 逐请求签名；
 * 旧 multi 口（reading.snssdk.com、platform=1024、无签名）已从主链摘除。
 * feed 列表类 GET 口（bookmall/tab 等已通的）维持原样不动。</p>
 *
 * <p>路径定罪（2026-10-27 真机「加载失败」案）：bookapi 家族在样本里的路径
 * 模板全部带版本段（classes11.dex 逐字：{@code $GET /reading/bookapi/plan/
 * v:version/} 等，user/subscribe/list 同），无段路径在网关直接 404
 * "Page not found"（本机对照实测：无段 404、带段 200 回真数据，版本段取值
 * 网关不敏感，v1/v2/v668/v725 均路由）。本类早期所有 bookapi 路径都漏了
 * 版本段，真机上 plan→bookmall 两级兜底连环 404、刷剧页只剩「加载失败」。
 * 现统一走 {@link #BV} 拼段；版本段的官方替换值未在样本里抠到定版，
 * 先用 v1（实测回真数据），待真机回包再校准。</p>
 *
 * <p>feed 面说明：推荐流逐条 feed 请求的原始报文未在拆解中定到（structure.md
 * 标未定），本类只落 video_tab/plan + bookmall/tab + cell/change 这套能落的
 * 体系入口，逐条 feed 请求不编造。</p>
 */
public final class HgApiClient {

    public static final String HOST = "https://reading.snssdk.com";

    /** bookapi 路径版本段（样本模板 v:version 的替换值，见类头定罪注）。 */
    private static final String BV = "/v1/";

    private static final String TAG = "HgApi";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private volatile HgAuthProvider authProvider;

    public HgApiClient() {
    }

    /** 登录态注入点（四阶段接壳子统一登录托管）；传 null 即不带登录态。 */
    public void setAuthProvider(HgAuthProvider provider) {
        this.authProvider = provider;
    }

    // ---------- 取播放模型（签名单集口；openhongguo 无批量模型口） ----------

    /**
     * 批量取多集播放模型（预取面）：逐集走签名单集口并发取回、聚合为表。
     * openhongguo 全程没有批量模型口，星辰旧 multi 口已定罪摘除（见类头注）。
     * 全部失败才回调错误；部分成功回调已取到的表（预取语义：缺集起播时再现取）。
     */
    public void fetchVideoModels(Collection<String> videoIds, HgCallback<Map<String, HgVideoModel>> cb) {
        if (videoIds == null || videoIds.isEmpty()) {
            cb.onError(-1, "videoIds 为空");
            return;
        }
        Map<String, HgVideoModel> out = Collections.synchronizedMap(new LinkedHashMap<>());
        AtomicInteger remain = new AtomicInteger(videoIds.size());
        AtomicReference<String> firstError = new AtomicReference<>();
        for (String videoId : videoIds) {
            fetchVideoModel(videoId, new HgCallback<HgVideoModel>() {
                @Override
                public void onSuccess(HgVideoModel m) {
                    out.put(videoId, m);
                    settle();
                }

                @Override
                public void onError(int httpCode, String message) {
                    firstError.compareAndSet(null, message);
                    settle();
                }

                private void settle() {
                    if (remain.decrementAndGet() == 0) {
                        if (out.isEmpty()) cb.onError(-1, "取模型全灭：" + firstError.get());
                        else cb.onSuccess(out);
                    }
                }
            });
        }
    }

    /**
     * 单集取模型 POST /novel/player/video_model/v1/（fqnovel 已签名口，
     * HgRequests.playbackBody 口径）：定罪修法的主路（见类头注）。
     * 回包 data.video_model 解析见 HgParser.parseSingleVideoModel。
     */
    public void fetchVideoModel(String videoId, HgCallback<HgVideoModel> cb) {
        postSigned(HgDevice.PATH_PLAYBACK, HgRequests.playbackBody(videoId), new BodyCallback() {
            @Override
            void onBody(String text) {
                HgVideoModel m = HgParser.parseSingleVideoModel(text, videoId);
                if (m == null) cb.onError(-1, "回包无 video_model");
                else cb.onSuccess(m);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** fplay 兜底取址：multi 回包的 fallback_api 地址直接 GET，返回单集模型（main_url 为加密串形态）。 */
    public void fetchByFallbackApi(String fallbackApiUrl, HgCallback<HgVideoModel> cb) {
        if (fallbackApiUrl == null || fallbackApiUrl.isEmpty()) {
            cb.onError(-1, "fallback_api 为空");
            return;
        }
        get(fallbackApiUrl, null, new BodyCallback() {
            @Override
            void onBody(String text) {
                HgVideoModel m = HgParser.parseFplay(text);
                if (m == null) cb.onError(-1, "fplay 回包无 video_info.data");
                else cb.onSuccess(m);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    // ---------- 设备注册与签名发送（定罪修法核心，见类头注） ----------

    private final Object deviceLock = new Object();
    private final List<HgCallback<Boolean>> deviceWaiters = new ArrayList<>();
    private boolean deviceRegistering;

    /**
     * 确保匿名设备已注册：已注册立即回调成功；未注册只发一次注册请求，
     * 并发调用排队等同一结果。注册身份由 HgDevice 落盘全局复用。
     */
    public void ensureDevice(HgCallback<Boolean> cb) {
        if (HgDevice.isRegistered()) {
            cb.onSuccess(Boolean.TRUE);
            return;
        }
        synchronized (deviceLock) {
            if (HgDevice.isRegistered()) {
                cb.onSuccess(Boolean.TRUE);
                return;
            }
            deviceWaiters.add(cb);
            if (deviceRegistering) return;
            deviceRegistering = true;
        }
        doRegister();
    }

    private void doRegister() {
        final byte[] body;
        try {
            body = HgDevice.buildRegisterBody(System.currentTimeMillis());
        } catch (Exception e) {
            android.util.Log.e(TAG, "注册体构造失败 " + e.getClass().getName() + ": " + e.getMessage());
            finishRegister("注册体构造失败");
            return;
        }
        HgDevice.Profile p = HgDevice.profile();
        String url = HgDevice.REGISTER_HOST + "/service/2/device_register/?req_id="
                + p.reqId + "&tt_data=a";
        Map<String, String> h = new HashMap<>();
        h.put("user-agent", HgDevice.userAgent());
        h.put("Content-Type", "application/octet-stream;tt-data=a");
        h.put("log-encode-type", "gzip");
        h.put("sdk-version", "2");
        h.put("passport-sdk-version", "5051452");
        h.put("x-vc-bdturing-sdk-version", "4.0.3.cn");
        h.put("x-ss-dp", "8662");
        h.put("X-SS-STUB", HgSign.contentMd5Hex(body));
        RequestBody rb = RequestBody.create(body, MediaType.parse("application/octet-stream;tt-data=a"));
        Call call = OkHttp.newCall(url, h, rb);
        enqueue(call, new BodyCallback() {
            @Override
            void onCookies(Map<String, String> cookies) {
                HgDevice.applyGuestCookies(cookies);
            }

            @Override
            void onBody(String text) {
                String[] ids = HgDevice.parseRegister(text);
                if (ids == null) {
                    android.util.Log.e(TAG, "注册回包无 device_id/install_id body=" + abbrev(text));
                    finishRegister("注册回包无设备 id");
                    return;
                }
                HgDevice.saveRegistered(ids[0], ids[1]);
                finishRegister(null);
            }

            @Override
            void onFail(int code, String msg) {
                finishRegister("注册失败：" + msg);
            }
        }, "device_register");
    }

    private void finishRegister(String error) {
        List<HgCallback<Boolean>> waiters;
        synchronized (deviceLock) {
            waiters = new ArrayList<>(deviceWaiters);
            deviceWaiters.clear();
            deviceRegistering = false;
        }
        for (HgCallback<Boolean> w : waiters) {
            if (error == null) w.onSuccess(Boolean.TRUE);
            else w.onError(-1, error);
        }
    }

    /**
     * 已签名 POST（取址链专用）：先确保设备注册，再用注册身份＋有序查询串
     * 逐请求签名（HgSign），发往 fqnovel 宿主。签名覆盖的 body 字节与实际
     * 发送字节为同一串（bodyObj.toString 的 UTF-8 编码）。
     */
    private void postSigned(String path, JsonObject bodyObj, BodyCallback cb) {
        ensureDevice(new HgCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                String body = bodyObj.toString();
                byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
                long ticket = System.currentTimeMillis();
                HgSign.Signed signed = HgSign.signPost(path, HgDevice.deviceQueryPairs(), ticket,
                        HgDevice.profile().deviceId, HgDevice.VERSION_NAME, bodyBytes, null);
                String url = HgDevice.API_HOST + path + "?" + signed.query;
                Map<String, String> h = headers();
                h.put("user-agent", HgDevice.userAgent());
                h.putAll(signed.headers);
                // Content-Type 由 RequestBody 承载（值同为 application/json; charset=UTF-8）
                h.remove("Content-Type");
                if (!h.containsKey("Cookie")) {
                    String guest = HgDevice.guestCookieHeader();
                    if (guest != null) h.put("Cookie", guest);
                }
                h.put("x-ss-dp", "8662");
                h.put("lc", "101");
                h.put("x-reading-request", HgDevice.readingRequestId(ticket));
                RequestBody rb = RequestBody.create(body, JSON);
                Call call = OkHttp.newCall(url, h, rb);
                enqueue(call, cb, path);
            }

            @Override
            public void onError(int httpCode, String message) {
                cb.onFail(httpCode, message);
            }
        });
    }

    // ---------- 剧集详情（fqnovel 已签名口，openhongguo SeriesBody/SeriesBatchBody） ----------

    /** 批量剧集详情 POST /novel/player/multi_video_detail/player/v1 → 以剧 id 为键的详情表。 */
    public void fetchVideoDetails(Collection<String> seriesIds, HgCallback<Map<String, HgSeriesDetail>> cb) {
        if (seriesIds == null || seriesIds.isEmpty()) {
            cb.onError(-1, "seriesIds 为空");
            return;
        }
        postSigned(HgDevice.PATH_SERIES_BATCH, HgRequests.seriesBatchBody(seriesIds), new BodyCallback() {
            @Override
            void onBody(String text) {
                cb.onSuccess(HgParser.parseSeriesBatch(text));
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** 单剧详情 POST /novel/player/video_detail/v1/（入参实为剧 id seriesId）。 */
    public void fetchVideoDetail(String seriesId, HgCallback<HgSeriesDetail> cb) {
        postSigned(HgDevice.PATH_SERIES, HgRequests.seriesBody(seriesId), new BodyCallback() {
            @Override
            void onBody(String text) {
                cb.onSuccess(HgParser.parseVideoDetail(text));
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    // ---------- 搜索 ----------

    /** 搜索结果页签 GET /reading/bookapi/search/tab（keyword 参数名未定版，现用 keyword）。 */
    public void searchTab(String keyword, HgCallback<HgCellPage> cb) {
        Map<String, String> q = new HashMap<>();
        q.put("keyword", keyword);
        get(HOST + "/reading/bookapi/search/tab" + BV, q, cellCallback(cb));
    }

    /** 搜索提示 GET /reading/bookapi/search/cue。 */
    public void searchCue(String keyword, HgCallback<HgCellPage> cb) {
        Map<String, String> q = new HashMap<>();
        q.put("keyword", keyword);
        get(HOST + "/reading/bookapi/search/cue" + BV, q, cellCallback(cb));
    }

    /** 搜索联想 GET /reading/bookapi/search/suggest。 */
    public void searchSuggest(String keyword, HgCallback<HgCellPage> cb) {
        Map<String, String> q = new HashMap<>();
        q.put("keyword", keyword);
        get(HOST + "/reading/bookapi/search/suggest" + BV, q, cellCallback(cb));
    }

    /** 搜索结果翻页 GET /reading/bookapi/search/cell/change（翻页游标参数未定版，透传 params）。 */
    public void searchCellChange(Map<String, String> params, HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/search/cell/change" + BV, params, cellCallback(cb));
    }

    // ---------- 剧场分类 ----------

    public void categoryFront(HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/new_category/front" + BV, null, cellCallback(cb));
    }

    public void categoryLanding(Map<String, String> params, HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/new_category/landing" + BV, params, cellCallback(cb));
    }

    public void categoryCell(Map<String, String> params, HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/new_category/cell" + BV, params, cellCallback(cb));
    }

    // ---------- 首页/剧场计划位（feed 面能落的部分） ----------

    /** GET /reading/bookapi/plan：服务端编排的计划位。 */
    public void plan(HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/plan" + BV, null, cellCallback(cb));
    }

    /** GET /reading/bookapi/bookmall/tab：剧场页签。 */
    public void bookmallTab(HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/bookmall/tab" + BV, null, cellCallback(cb));
    }

    /** GET /reading/bookapi/bookmall/cell/change：剧场 cell 翻页（参数透传）。 */
    public void bookmallCellChange(Map<String, String> params, HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/bookmall/cell/change" + BV, params, cellCallback(cb));
    }

    // ---------- 追剧架（账号维度，靠登录态注入） ----------

    public void bookshelfVideoList(HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/bookshelf/video/list" + BV, null, cellCallback(cb));
    }

    public void bookshelfVideoInfo(String seriesId, HgCallback<HgCellPage> cb) {
        Map<String, String> q = new HashMap<>();
        q.put("series_id", seriesId);
        get(HOST + "/reading/bookapi/bookshelf/video/info" + BV, q, cellCallback(cb));
    }

    /**
     * 追剧架更新 POST /reading/bookapi/bookshelf/video/update（收藏/追更共用此口，
     * 官方 FollowScene 加入收藏与右列追剧钮同走 AddShelf，见 HgRequests
     * bookshelfVideoUpdateBody 注的出处）。add=true 加入、false 移出。
     * 成功判据容忍：回包 code 为 0 / "0" / "SUCCESS" / 缺失均算成（bookapi
     * 的 code 是 BookApiERR 枚举，序列化形态待真机校准），其余算失败回调。
     */
    public void bookshelfVideoUpdate(String seriesId, boolean add, HgCallback<Boolean> cb) {
        JsonObject body = HgRequests.bookshelfVideoUpdateBody(seriesId, add, System.currentTimeMillis());
        postJson(HOST + "/reading/bookapi/bookshelf/video/update" + BV, body, new BodyCallback() {
            @Override
            void onBody(String text) {
                if (isOkCode(text)) cb.onSuccess(Boolean.TRUE);
                else cb.onError(-2, "追剧架更新被服务端拒绝");
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    // ---------- 预约（未上线剧，账号维度，靠登录态注入） ----------

    /**
     * 预约/取消预约 POST /reading/bookapi/search/uncover_subscribe。
     * 出处链逐环见 {@link HgRequests#searchUncoverSubscribeBody} 注（端点＝
     * BookApiService.searchUncoverSubscribeRx，item_type 短剧=14，
     * op_type Subscribe=1/CancelSubscribe=2）。成功判据同追剧架的 code 容忍口径。
     */
    public void subscribeSeries(String seriesId, boolean subscribe, HgCallback<Boolean> cb) {
        JsonObject body = HgRequests.searchUncoverSubscribeBody(seriesId, subscribe);
        postJson(HOST + "/reading/bookapi/search/uncover_subscribe" + BV, body, new BodyCallback() {
            @Override
            void onBody(String text) {
                if (isOkCode(text)) cb.onSuccess(Boolean.TRUE);
                else cb.onError(-2, "预约操作被服务端拒绝");
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /**
     * 我的预约列表 GET /reading/user/subscribe/list（tab_type=MySubscribed=1）。
     * 出处：classes11.dex rpc 模型 GetUserSubscribeListRequest /
     * UserSubscribeData{subscribe_items, has_more, ...}；端点字符串在国内版
     * 样本逐字命中。解析走容忍抽取（见 HgParser.parseSubscribeList 注）。
     */
    public void fetchMyReservations(HgCallback<java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem>> cb) {
        get(HOST + "/reading/user/subscribe/list" + BV, HgRequests.subscribeListParams(), new BodyCallback() {
            @Override
            void onBody(String text) {
                cb.onSuccess(HgParser.parseSubscribeList(text));
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** bookapi 回包 code 容忍判定（枚举序列化形态未定版，见上注）。公开仅为离线单测可达。 */
    public static boolean isOkCode(String text) {
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            if (!root.has("code") || root.get("code").isJsonNull()) return true;
            com.google.gson.JsonElement code = root.get("code");
            if (code.isJsonPrimitive() && code.getAsJsonPrimitive().isNumber()) return code.getAsInt() == 0;
            String s = code.getAsString();
            return s == null || s.isEmpty() || "0".equals(s) || "SUCCESS".equalsIgnoreCase(s) || "Success".equals(s);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 传输层 ----------

    private BodyCallback cellCallback(HgCallback<HgCellPage> cb) {
        return new BodyCallback() {
            @Override
            void onBody(String text) {
                cb.onSuccess(HgParser.parseCellPage(text));
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        };
    }

    private abstract static class BodyCallback {
        abstract void onBody(String text);

        abstract void onFail(int code, String msg);

        /** 回包 Set-Cookie（name=value 已拆好）；默认忽略，注册路用来收游客 Cookie。 */
        void onCookies(Map<String, String> cookies) {
        }
    }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("user-agent", HgRequests.UA);
        HgAuthProvider auth = authProvider;
        if (auth != null) {
            Map<String, String> extra = auth.headers();
            if (extra != null) h.putAll(extra);
            String cookie = auth.cookie();
            if (cookie != null && !cookie.isEmpty()) h.put("Cookie", cookie);
        }
        return h;
    }

    private void postJson(String path, JsonObject body, BodyCallback cb) {
        String url = path + (path.contains("?") ? "&" : "?") + HgRequests.freshQuery();
        RequestBody rb = RequestBody.create(JSON, body.toString());
        Call call = OkHttp.newCall(url, headers(), rb);
        enqueue(call, cb, path);
    }

    private void get(String path, Map<String, String> params, BodyCallback cb) {
        StringBuilder url = new StringBuilder(path);
        url.append(path.contains("?") ? "&" : "?").append(HgRequests.freshQuery());
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                url.append("&").append(enc(e.getKey())).append("=").append(enc(e.getValue()));
            }
        }
        Call call = OkHttp.newCall(url.toString(), headers());
        enqueue(call, cb, path);
    }

    /**
     * 失败一律留全日志（tag=HgApi）：异常类名＋HTTP 状态＋路径都进 logcat，
     * 页面只给人话文案——2026-10-27「加载失败」案就是因为真错误被吞掉、
     * 只剩一句通用提示，无从定罪。此后任何一环失败，先看这行日志。
     */
    private void enqueue(Call call, BodyCallback cb, String path) {
        if (call == null) {
            android.util.Log.e(TAG, path + " 无法创建请求");
            cb.onFail(-1, "无法创建请求");
            return;
        }
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                android.util.Log.e(TAG, path + " 网络失败 "
                        + e.getClass().getName() + ": " + e.getMessage());
                cb.onFail(-1, e.getMessage());
            }

            @Override
            public void onResponse(Call c, Response response) {
                // 此处在 OkHttp 分发线程：读体与解析都不占主线程。
                try (Response r = response) {
                    List<String> setCookies = r.headers("Set-Cookie");
                    if (setCookies != null && !setCookies.isEmpty()) {
                        Map<String, String> cookies = new HashMap<>();
                        for (String sc : setCookies) {
                            String pair = sc;
                            int semi = pair.indexOf(';');
                            if (semi >= 0) pair = pair.substring(0, semi);
                            int eq = pair.indexOf('=');
                            if (eq > 0) cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                        }
                        if (!cookies.isEmpty()) cb.onCookies(cookies);
                    }
                    ResponseBody rb = r.body();
                    String text = rb == null ? "" : rb.string();
                    if (!r.isSuccessful()) {
                        android.util.Log.e(TAG, path + " HTTP " + r.code()
                                + (text.isEmpty() ? "" : " body=" + abbrev(text)));
                        cb.onFail(r.code(), "HTTP " + r.code());
                    } else if (text.isEmpty()) {
                        android.util.Log.e(TAG, path + " 回包空体 HTTP " + r.code());
                        cb.onFail(r.code(), "回包空体");
                    } else {
                        cb.onBody(text);
                    }
                } catch (Exception e) {
                    android.util.Log.e(TAG, path + " 读体失败 "
                            + e.getClass().getName() + ": " + e.getMessage());
                    cb.onFail(-1, e.getMessage());
                }
            }
        });
    }

    private static String abbrev(String text) {
        return text.length() <= 120 ? text : text.substring(0, 120);
    }

    private static String enc(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }
}
