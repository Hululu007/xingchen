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
 * 旧 multi 口（reading.snssdk.com、platform=1024、无签名）已从主链摘除。</p>
 *
 * <p>feed 面重建（2026-10-28，tab-fail-dig-20261028.md 两案定罪）：漫剧/
 * 真人剧页签原接的 new_category/front 是小说分类页壳接口（结构上永远不带
 * 剧条目）且无签名发法恒回空体；推荐页签走的 plan/无参 bookmall/tab 不是
 * 官方推荐频道（无参回书城首页壳，只有找剧位 6 张卡）、且从不续页、流封顶
 * 6 条。三页签现统一改走已签名 bookmall/tab 频道面（openhongguo 频道口径：
 * tab_type 开页请求 -1/36/39、响应频道 16/36/39、bottom_tab_type=7、
 * client_req_type=3）＋ cell/change 续页（client_req_type=2），见
 * {@link #bookmallChannel} / {@link #bookmallCellChange}；plan、category*
 * 与无参 bookmallTab 已从 feed 面摘除删除。</p>
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
 * <p>feed 面说明（已更新，见上「feed 面重建」段）：feed 三页签的原始报文
 * 已由 openhongguo 频道面定版并在本机实跑验通，旧「未定」口径作废。</p>
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
                // 错误码 -2 专表「回包无 video_model」：网关限流回 200 空体与
                // 结构化无模型回包在解链层同形，HgPlayResolver 靠它判定进冷却
                if (m == null) cb.onError(-2, "回包无 video_model");
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

    /**
     * 已签名 GET（feed 频道面专用）：与 postSigned 同路——先确保设备注册，
     * 再用注册身份对有序参数逐请求签名（HgSign.signGet 传真实路径），
     * 发往 fqnovel 宿主；头组装照 openhongguo anonymousHeaders 口径
     * （UA 换设备面 UA、x-ss-dp / lc / x-reading-request、游客 Cookie 兜底）。
     */
    private void getSigned(String path, List<String[]> pairs, BodyCallback cb) {
        ensureDevice(new HgCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                long ticket = System.currentTimeMillis();
                HgSign.Signed signed = HgSign.signGet(path, pairs, ticket,
                        HgDevice.profile().deviceId, HgDevice.VERSION_NAME, null);
                String url = HgDevice.API_HOST + path + "?" + signed.query;
                Map<String, String> h = headers();
                h.put("user-agent", HgDevice.userAgent());
                h.putAll(signed.headers);
                if (!h.containsKey("Cookie")) {
                    String guest = HgDevice.guestCookieHeader();
                    if (guest != null) h.put("Cookie", guest);
                }
                h.put("x-ss-dp", "8662");
                h.put("lc", "101");
                h.put("x-reading-request", HgDevice.readingRequestId(ticket));
                Call call = OkHttp.newCall(url, h);
                enqueue(call, cb, path);
            }

            @Override
            public void onError(int httpCode, String message) {
                cb.onFail(httpCode, message);
            }
        });
    }

    // ---------- 同步口（阅读管线等阻塞调用方用；签名/头组装仍走上面同一套，不许另抄一份） ----------

    /** 已签名 GET 的同步版：回包原文；失败抛异常（消息为人话＋服务端信息）。 */
    public String getSignedSync(String path, java.util.List<String[]> pairs) throws Exception {
        return syncCall(cb -> getSigned(path, pairs, cb));
    }

    /** 已签名 POST（JSON 体）的同步版：回包原文；失败抛异常。 */
    public String postSignedSync(String path, JsonObject bodyObj) throws Exception {
        return syncCall(cb -> postSigned(path, bodyObj, cb));
    }

    private interface SyncStarter {
        void start(BodyCallback cb);
    }

    private static String syncCall(SyncStarter starter) throws Exception {
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        final String[] body = new String[1];
        final String[] error = new String[1];
        starter.start(new BodyCallback() {
            @Override
            void onBody(String text) {
                body[0] = text;
                latch.countDown();
            }

            @Override
            void onFail(int code, String msg) {
                error[0] = msg == null || msg.isEmpty() ? ("请求失败(" + code + ")") : msg;
                latch.countDown();
            }
        });
        if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new Exception("红果接口请求超时");
        }
        if (error[0] != null) throw new Exception(error[0]);
        return body[0] == null ? "" : body[0];
    }

    // ---------- bookmall 频道面（feed 三页签，openhongguo 频道口径） ----------

    /**
     * 频道开页 GET /reading/bookapi/bookmall/tab/v（已签名）：
     * requestTabType 为开页请求值（推荐 -1、漫剧 36、真人剧 39），
     * responseTabType 为回包里要读的频道（推荐 16、漫剧 36、真人剧 39）。
     * 解析按 tab_type 过滤＋抽续页凭证，见 HgParser.parseDiscoverTab。
     */
    public void bookmallChannel(int requestTabType, int responseTabType, HgCallback<HgCellPage> cb) {
        getSigned(HgDevice.PATH_BOOKMALL_TAB, HgRequests.discoverPairs(requestTabType), new BodyCallback() {
            @Override
            void onBody(String text) {
                cb.onSuccess(HgParser.parseDiscoverTab(text, responseTabType));
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /**
     * 频道续页 GET /reading/bookapi/bookmall/cell/change/v（已签名）：
     * 凭证来自开页回包（HgContinuation），回包再带新的 next_offset /
     * session_id 供上层推进凭证。解析见 HgParser.parseCellChange。
     */
    public void bookmallCellChange(com.fongmi.android.tv.hongguo.model.HgContinuation cont,
                                   HgCallback<HgCellPage> cb) {
        getSigned(HgDevice.PATH_BOOKMALL_CELL_CHANGE, HgRequests.cellChangePairs(cont), new BodyCallback() {
            @Override
            void onBody(String text) {
                cb.onSuccess(HgParser.parseCellChange(text));
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
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
    /**
     * 专辑详情（多季系列的各季表）：POST /novel/player/album_detail/v1/，
     * 请求体照 openhongguo AlbumBody（APK GetVideoAlbumDetailRequest 观察定版），
     * 回包 HgParser.parseAlbum；已下架（code 101001）回空表。
     */
    public void fetchAlbum(String albumId, HgCallback<java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem>> cb) {
        postSigned(HgDevice.PATH_ALBUM, HgRequests.albumBody(albumId), new BodyCallback() {
            @Override
            void onBody(String text) {
                cb.onSuccess(com.fongmi.android.tv.hongguo.model.HgParser.parseAlbum(text));
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

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

    /**
     * 搜索结果页签 GET /reading/bookapi/search/tab/v（已签名，fqnovel 宿主）：
     * 2026-10-28 A/B 实证——旧形（reading 宿主、无签名、freshQuery 假指纹、
     * keyword 参数）回 HTTP 200 空体，与取模型口同一道签名门，搜索在真机上
     * 同样恒空；现改走 getSigned＋openhongguo SignedSearchPairs（query 参数），
     * 探针实跑回 63,856 字节真结果。路径尾段字面 v 同频道面口径。
     */
    public void searchTab(String keyword, HgCallback<HgCellPage> cb) {
        getSigned("/reading/bookapi/search/tab/v", HgRequests.searchPairs(keyword), new BodyCallback() {
            @Override
            void onBody(String text) {
                cb.onSuccess(HgParser.parseCellPage(text));
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /**
     * 搜索框提示词 GET /reading/bookapi/search/cue/v1/（已签名）：旧无签名
     * 形同门回空体、界面也从没用过，已换签名口（openhongguo SearchCues 同口）。
     */
    public void searchCues(HgCallback<java.util.List<String>> cb) {
        getSigned("/reading/bookapi/search/cue/v1/", HgRequests.cuePairs(), new BodyCallback() {
            @Override
            void onBody(String text) {
                cb.onSuccess(com.fongmi.android.tv.hongguo.model.HgParser.parseCue(text));
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /**
     * 搜索联想 GET /reading/bookapi/search/suggest/v1/（已签名，fqnovel 宿主）：
     * 旧无签名形与旧搜索同门回空体，已改走 getSigned＋suggestPairs，
     * openhongguo Suggest 同口实跑可回（词条含直达剧的 id）。
     */
    public void searchSuggest(String prefix, HgCallback<java.util.List<com.fongmi.android.tv.hongguo.model.HgSuggestion>> cb) {
        getSigned("/reading/bookapi/search/suggest/v1/", HgRequests.suggestPairs(prefix), new BodyCallback() {
            @Override
            void onBody(String text) {
                cb.onSuccess(com.fongmi.android.tv.hongguo.model.HgParser.parseSuggest(text));
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** 搜索翻页：同 searchTab，offset 为已取条数。 */
    public void searchTab(String keyword, int offset, HgCallback<HgCellPage> cb) {
        searchTab(keyword, offset, 11, cb);
    }

    /** 搜索换页签：tabType 取回包 search_tabs 的 tab_type（11 综合/29 剧集/30 视频）。 */
    public void searchTab(String keyword, int offset, int tabType, HgCallback<HgCellPage> cb) {
        getSigned("/reading/bookapi/search/tab/v", HgRequests.searchPairs(keyword, offset, tabType), new BodyCallback() {
            @Override
            void onBody(String text) {
                cb.onSuccess(HgParser.parseCellPage(text));
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** 搜索结果翻页 GET /reading/bookapi/search/cell/change（翻页游标参数未定版，透传 params）。 */
    public void searchCellChange(Map<String, String> params, HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/search/cell/change" + BV, params, cellCallback(cb));
    }

    // ---------- 追剧架（账号维度，靠登录态注入） ----------

    /**
     * 用户资料 GET /reading/user/profile/get/（a45/k.java:344 GetUserProfile
     * 定版，请求体只有 user_id）：「我的」头部关系行与作者主页数据行同源
     * （模型与拼法见 HgUserProfile 类注）。走本面已签名 get 口，登录态由
     * headers() 统一附；回包认不认网页会话与资料口同命，失败静默由调用方兜。
     */
    public void fetchUserProfile(String userId,
                                 HgCallback<com.fongmi.android.tv.hongguo.model.HgUserProfile> cb) {
        if (userId == null || userId.isEmpty()) {
            cb.onError(-1, "user_id 为空");
            return;
        }
        Map<String, String> q = new HashMap<>();
        q.put("user_id", userId);
        get(HOST + "/reading/user/profile/get" + BV, q, new BodyCallback() {
            @Override
            void onBody(String text) {
                com.fongmi.android.tv.hongguo.model.HgUserProfile p =
                        HgParser.parseUserProfile(text);
                if (p == null) cb.onError(-2, "用户资料回包解析失败");
                else cb.onSuccess(p);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    public void bookshelfVideoList(HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/bookshelf/video/list" + BV, null, cellCallback(cb));
    }

    /**
     * 账号资料（account-info-dig-20261028.md 定版）：passport 口
     * GET security.snssdk.com/passport/account/info/v2/?scene=normal，
     * 凭据＝壳子托管的登录 Cookie（本面自己的 headers() 已统一附），
     * csrf 头从 Cookie 的 passport_csrf_token 取。此口是异宿主、无 body
     * 签名（只认 Cookie 会话）；会话来源已换原生短信链（HgPassportClient），
     * 原生会话认不认账待新版首登一发定论（登录后第一验 HgLoginSessionCheck
     * 记形状）。无 Cookie 直接失败不发请求。
     */
    public void fetchAccountInfo(HgCallback<com.fongmi.android.tv.hongguo.model.HgAccountProfile> cb) {
        HgAuthProvider auth = authProvider;
        String cookie = auth == null ? null : auth.cookie();
        if (cookie == null || cookie.isEmpty()) {
            cb.onError(-3, "未登录");
            return;
        }
        HgDevice.Profile profile = HgDevice.profile();
        StringBuilder url = new StringBuilder("https://security.snssdk.com/passport/account/info/v2/");
        url.append("?scene=normal&passport-sdk-version=5051452&account_sdk_source=app");
        url.append("&aid=").append(HgDevice.AID);
        if (profile != null && profile.registered()) {
            url.append("&device_id=").append(enc(profile.deviceId));
            url.append("&iid=").append(enc(profile.installId));
        }
        Map<String, String> h = headers();
        String csrf = cookieValue(cookie, "passport_csrf_token");
        if (csrf != null && !csrf.isEmpty()) h.put("x-tt-passport-csrf-token", csrf);
        Call call = OkHttp.newCall(url.toString(), h);
        enqueue(call, new BodyCallback() {
            @Override
            void onBody(String text) {
                com.fongmi.android.tv.hongguo.model.HgAccountProfile p =
                        com.fongmi.android.tv.hongguo.model.HgParser.parseAccountInfo(text);
                if (p == null) cb.onError(-2, "账号资料回包解析失败");
                else cb.onSuccess(p);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        }, "passport/account/info");
    }

    private static String cookieValue(String cookie, String name) {
        for (String part : cookie.split(";")) {
            String t = part.trim();
            if (t.startsWith(name + "=")) return t.substring(name.length() + 1).trim();
        }
        return null;
    }

    public void bookshelfVideoInfo(String seriesId, HgCallback<HgCellPage> cb) {
        Map<String, String> q = new HashMap<>();
        q.put("series_id", seriesId);
        get(HOST + "/reading/bookapi/bookshelf/video/info" + BV, q, cellCallback(cb));
    }

    /**
     * 作者收藏签一页（官方 GetBookShelfVideoInfoRequest 的 target_user_id
     * 维度，tu3/f.java 定版；本机以同一路径实拉验通：data.video_shelf_info
     * ＋next_offset/has_more）。offset 从 0 起，续拉用回包 next_offset。
     */
    public void authorShelf(String uid, int offset,
                            HgCallback<com.fongmi.android.tv.hongguo.model.HgAuthorWork.Page> cb) {
        Map<String, String> q = new HashMap<>();
        q.put("target_user_id", uid);
        q.put("count", "18");
        q.put("offset", String.valueOf(Math.max(0, offset)));
        get(HOST + "/reading/bookapi/bookshelf/video/info" + BV, q, new BodyCallback() {
            @Override
            void onBody(String text) {
                com.fongmi.android.tv.hongguo.model.HgAuthorWork.Page page =
                        com.fongmi.android.tv.hongguo.model.HgParser.parseAuthorShelf(text);
                if (page == null) cb.onError(-2, "收藏回包解析失败");
                else cb.onSuccess(page);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /**
     * 作者点赞签一页（官方 UgcApiService.mGetUserActionList 的 Like 维度，
     * bv3/f.java 定版：action_type=3、object_type_list="6,15,10"；本机
     * 实拉验通：data.mixed_data_list＋next_offset/has_more）。
     */
    public void authorLikes(String uid, int offset,
                            HgCallback<com.fongmi.android.tv.hongguo.model.HgAuthorWork.Page> cb) {
        Map<String, String> q = new HashMap<>();
        q.put("action_type", "3");
        q.put("object_type_list", "6,15,10");
        q.put("target_user_id", uid);
        q.put("count", "18");
        q.put("offset", String.valueOf(Math.max(0, offset)));
        get(HOST + "/reading/ugc/action/mget" + BV, q, new BodyCallback() {
            @Override
            void onBody(String text) {
                com.fongmi.android.tv.hongguo.model.HgAuthorWork.Page page =
                        com.fongmi.android.tv.hongguo.model.HgParser.parseAuthorLikes(text);
                if (page == null) cb.onError(-2, "点赞回包解析失败");
                else cb.onSuccess(page);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
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
     * 关注/取关作者备路 POST /reading/ugc/general/follow/v:version/
     * （UgcApiService.A，classes11.dex；版本段照本类 BV 口径拼 /v1/）。
     * 只在评论口 do_action 主路回包非 0 时由 HgCommentClient 转投过来
     * （见 HgCommentClient.doFollowAction）；请求体字段与成功判据
     * （code=0）见 {@link HgRequests#followRequestBody} 注。
     */
    public void followAuthor(boolean follow, String authorUid, HgCallback<Void> cb) {
        JsonObject body = HgRequests.followRequestBody(follow, authorUid);
        postJson(HOST + "/reading/ugc/general/follow" + BV, body, new BodyCallback() {
            @Override
            void onBody(String text) {
                if (isOkCode(text)) cb.onSuccess(null);
                else cb.onError(-2, "关注被服务端拒绝（备路）");
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
    /** 按上线档拉我的预约（官方 is_online 分档，见 HgRequests.subscribeListParams）。 */
    public void fetchMyReservations(boolean online, HgCallback<java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem>> cb) {
        fetchReservationsPage(0, online, new java.util.ArrayList<>(), cb);
    }

    /**
     * 预约回读分页累积（D1 修）：整页（50 条）回来就续下一页，直到不满页
     * 或空页；上限 10 页防服务端异常时死循环。中途某页失败时已累积的
     * 照常回成功（回读对齐宁可少、不可全丢）。
     */
    private void fetchReservationsPage(int offset, boolean online,
                                       java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem> acc,
                                       HgCallback<java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem>> cb) {
        get(HOST + "/reading/user/subscribe/list" + BV, HgRequests.subscribeListParams(offset, online), new BodyCallback() {
            @Override
            void onBody(String text) {
                java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem> page =
                        HgParser.parseSubscribeList(text);
                if (page != null) acc.addAll(page);
                if (page != null && page.size() >= 50 && offset < 450) {
                    fetchReservationsPage(offset + 50, online, acc, cb);
                } else {
                    cb.onSuccess(acc);
                }
            }

            @Override
            void onFail(int code, String msg) {
                if (acc.isEmpty()) cb.onError(code, msg);
                else cb.onSuccess(acc);
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
