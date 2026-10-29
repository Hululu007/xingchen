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
                if (m != null) {
                    cb.onSuccess(m);
                    return;
                }
                // 无 model 分两形：带顶层业务码的是结构化拒绝（如 UGC vid
                // 的 101000「服务异常」）——原样回业务码，它是该条目自身
                // 被口径拒绝、不是档案被限流，不许与空体同口径进取模型
                // 冷却连坐后续条目（定根账 feed-item-fetch-fail-20261029
                // §5）；空体/无码才是限流形状，回 -2 交解链层判定冷却。
                int biz = HgParser.businessCodeOf(text);
                if (biz > 0) cb.onError(biz, "服务端拒绝 code=" + biz);
                else cb.onError(-2, "回包无 video_model");
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

    // 注册闸全实例合并：设备档案是 HgDevice 的全局一份（多处各持一个
    // HgApiClient：会话/护照/书城/登录检查），闸若按实例分家，跨实例就能
    // 并发发两轮注册——合并成静态一把锁、一个在途旗、一张等待表（扫查 P2）
    private static final Object deviceLock = new Object();
    private static final List<HgCallback<Boolean>> deviceWaiters = new ArrayList<>();
    private static boolean deviceRegistering;
    /** 存量档案 device_token 补刷在途旗（与注册闸分家：补刷不占注册轮）。 */
    private static boolean deviceTokenRefreshing;
    /** 旧代次轮作废后按新档案续注册用的空等待者（结果只给真等待者）。 */
    private static final HgCallback<Boolean> REREGISTER_WAITER = new HgCallback<Boolean>() {
        @Override
        public void onSuccess(Boolean ok) {
        }

        @Override
        public void onError(int httpCode, String message) {
        }
    };

    /**
     * 确保匿名设备已注册：已注册立即回调成功；未注册只发一次注册请求，
     * 并发调用排队等同一结果。注册身份由 HgDevice 落盘全局复用。
     */
    public void ensureDevice(HgCallback<Boolean> cb) {
        if (HgDevice.isRegistered()) {
            cb.onSuccess(Boolean.TRUE);
            maybeRefreshDeviceToken();
            return;
        }
        synchronized (deviceLock) {
            if (HgDevice.isRegistered()) {
                cb.onSuccess(Boolean.TRUE);
                maybeRefreshDeviceToken();
                return;
            }
            deviceWaiters.add(cb);
            if (deviceRegistering) return;
            deviceRegistering = true;
        }
        doRegister();
    }

    /**
     * 存量档案 device_token 补刷（定根账 login-ticket-dig-20261029.md
     * 第六节）：已注册但 token 为空的档案是旧版注册的——旧 parseRegister
     * 把回包里的 device_token 扔了，不补刷就永远发空 x-tt-dt。在签名门
     * （ensureDevice）成功路径后静默发一轮同身份刷新注册：注册体照旧
     * 由同一份 cdid/openudid/clientudid 派生（身份锚不变，与官方注册
     * SDK 的周期性重注册同机制；本机实拉验证同锚二刷 ids 不变、token
     * 每轮轮换、以最新一发为准）。失败不拦业务、不回错给任何等待者，
     * 下一轮 ensureDevice 再试。
     */
    private void maybeRefreshDeviceToken() {
        if (!HgDevice.needsDeviceToken()) return;
        synchronized (deviceLock) {
            if (deviceTokenRefreshing) return;
            deviceTokenRefreshing = true;
        }
        doRefreshDeviceToken();
    }

    private void doRefreshDeviceToken() {
        // 发出时的档案代次：回包落盘前由 saveRegisteredIfGeneration 原子
        // 复核——期间档案被重置则本轮 token 属旧档案，整发作废不写。
        final int gen = HgDevice.profileGeneration();
        final byte[] body;
        try {
            body = HgDevice.buildRegisterBody(System.currentTimeMillis());
        } catch (Exception e) {
            finishRefreshDeviceToken();
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
                // 与注册轮同口径：刷新回包的游客 Cookie 也是本档案的
                HgDevice.applyGuestCookiesIfGeneration(gen, cookies);
            }

            @Override
            void onBody(String text) {
                finishRefreshDeviceToken();
                String[] ids = HgDevice.parseRegister(text);
                if (ids == null || ids[2].isEmpty()) return;
                // 只为取 token：本机实证同锚二刷 ids 与现档案一致；万一
                // 服务端回了不同 ids，按代次原子口径整体回填（与官方
                // SDK 对回包 ids 的处置同义），代次已变则整发作废。
                HgDevice.saveRegisteredIfGeneration(gen, ids[0], ids[1], ids[2]);
            }

            @Override
            void onFail(int code, String msg) {
                // 静默：补刷失败不拦业务，下一轮 ensureDevice 再试
                finishRefreshDeviceToken();
            }
        }, "device_register_token_refresh");
    }

    private void finishRefreshDeviceToken() {
        synchronized (deviceLock) {
            deviceTokenRefreshing = false;
        }
    }

    /**
     * 重置设备档案并重新注册（档案级限流自愈用，定根账
     * feed-item-fetch-fail-20261029 §6：play 类接口对旧档案回空体、
     * 换档案即愈）：作废 HgDevice 整份匿名身份后走 ensureDevice 既有
     * 注册流程拿新档案，结果经 cb 回（口径同 ensureDevice）。
     *
     * <p>边界（死线）：只动匿名设备档案；账号登录态（passport 会话
     * Cookie 在壳子账号托管 CloudAccountStore）不在此列，重置不读
     * 不写，换档案后登录不掉。若恰有一轮注册在途，HgDevice 代次已变，
     * 那一轮的回包会被 doRegister 的代次校验作废并按新档案自动重注册，
     * 在途等待者与本次 cb 都等新档案的结果。</p>
     */
    public void resetDeviceProfile(HgCallback<Boolean> cb) {
        HgDevice.resetProfile();
        ensureDevice(cb);
    }

    private void doRegister() {
        // 发出时的档案代次：回包落盘前比对，期间被重置过则本轮结果作废
        final int gen = HgDevice.profileGeneration();
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
                // 代次复核与落盘原子：代次已变＝这批游客 Cookie 是旧档案
                // 注册回的，整批不落进新档案（HgDevice 类锁内比对）
                HgDevice.applyGuestCookiesIfGeneration(gen, cookies);
            }

            @Override
            void onBody(String text) {
                String[] ids = HgDevice.parseRegister(text);
                if (ids == null) {
                    if (HgDevice.profileGeneration() != gen) {
                        // 旧代次轮的坏回包不算数，别拿它失败掉已改等新
                        // 档案的等待者，按新档案重新注册
                        reregisterAfterProfileReset();
                        return;
                    }
                    android.util.Log.e(TAG, "注册回包无 device_id/install_id body=" + abbrev(text));
                    finishRegister("注册回包无设备 id");
                    return;
                }
                if (!HgDevice.saveRegisteredIfGeneration(gen, ids[0], ids[1], ids[2])) {
                    // 落盘瞬间档案已被重置：回的 id 属于旧档案、原子校验
                    // 已保证没写进新档案，作废本轮、按新档案重新注册；
                    // 等待者不释放、等新一轮结果
                    android.util.Log.w(TAG, "注册回包时档案已重置，按新档案重新注册");
                    reregisterAfterProfileReset();
                    return;
                }
                finishRegister(null);
            }

            @Override
            void onFail(int code, String msg) {
                if (HgDevice.profileGeneration() != gen) {
                    // 同上：旧档案那一轮的失败不算数，按新档案重新注册
                    reregisterAfterProfileReset();
                    return;
                }
                finishRegister("注册失败：" + msg);
            }
        }, "device_register");
    }

    /**
     * 旧代次注册轮作废后的续注册：必须走注册闸——先把本轮占的闸放开，
     * 再经 ensureDevice 排队/起新轮；已有别轮在途时只排队等它，不许绕过
     * deviceRegistering 直接并发再发一轮（扫查 P2 附带项）。等待表里原
     * 等待者不动，结果仍由最终成功的那一轮 finishRegister 统一回。
     */
    private void reregisterAfterProfileReset() {
        synchronized (deviceLock) {
            deviceRegistering = false;
        }
        ensureDevice(REREGISTER_WAITER);
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

    /**
     * 动态 UGC 视频详情 POST /novel/player/multi_video_detail/player/v1
     * （官方 UGC 数据源口径 source=17，见 HgRequests.ugcBatchBody）：
     * 回该 vid 的详情（video_data 自带 video_model，HgParser 挂到
     * HgSeriesDetail.ugcVideoModel）；回包无此条/无模型时 detail 为
     * null 或模型为空，调用方自行回落。
     */
    public void fetchUgcVideoDetail(String videoId, HgCallback<HgSeriesDetail> cb) {
        if (videoId == null || videoId.isEmpty()) {
            cb.onError(-1, "videoId 为空");
            return;
        }
        postSigned(HgDevice.PATH_SERIES_BATCH, HgRequests.ugcBatchBody(videoId), new BodyCallback() {
            @Override
            void onBody(String text) {
                Map<String, HgSeriesDetail> map = HgParser.parseSeriesBatch(text);
                HgSeriesDetail detail = map.get(videoId);
                if (detail == null && !map.isEmpty()) {
                    detail = map.values().iterator().next();
                }
                if (detail == null) cb.onError(-2, "回包无该视频详情");
                else cb.onSuccess(detail);
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
     * 搜索「用户」签（tab_type＝27）GET 同搜索口：按名反查账号
     * （uid/名/头像/作品数，HgParser.parseUserTab）。用途单一：正剧条目
     * 作者 uid 兜底（详情口恒不下发作者身份，定根账
     * author-page-why-missing-20261029 追补）——会话层拿作者名/出品方
     * 品牌来查、同名精确匹配定正主；搜索页本身没有用户签界面，不作它用。
     */
    public void searchUsers(String keyword,
                            HgCallback<java.util.List<com.fongmi.android.tv.hongguo.model.HgAuthorHit>> cb) {
        getSigned("/reading/bookapi/search/tab/v", HgRequests.searchPairs(keyword, 0, 27), new BodyCallback() {
            @Override
            void onBody(String text) {
                cb.onSuccess(HgParser.parseUserTab(text));
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

    /**
     * 看剧偏好读 GET /reading/user/preference/info/（a45/k.java:224 定版，
     * 模板无版本段、不拼 BV；参数见 HgRequests.preferenceInfoParams）。
     * 与资料口同宿主同 headers()（登录态统一附）；回包三区解析见
     * HgParser.parsePreferenceInfo。偏好是账号维度数据：未登录时服务端
     * 认不认、回什么形状，待新版首登实拉验（与预约列表同级状态）。
     */
    public void fetchWatchPreference(HgCallback<com.fongmi.android.tv.hongguo.model.HgPreference> cb) {
        get(HOST + "/reading/user/preference/info/", HgRequests.preferenceInfoParams(), new BodyCallback() {
            @Override
            void onBody(String text) {
                com.fongmi.android.tv.hongguo.model.HgPreference p =
                        HgParser.parsePreferenceInfo(text);
                if (p == null) cb.onError(-2, "看剧偏好回包解析失败");
                else cb.onSuccess(p);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /**
     * 看剧偏好保存 POST /reading/user/preference/set/（a45/k.java:364
     * 定版，模板无版本段）。只提交与回包原态不同的选项（增量口径见
     * HgPreference 类注）；无增量直接成功不发请求。回包 code 容忍判定
     * 复用 isOkCode，非成功码按失败报、不假装存上。
     */
    public void saveWatchPreference(com.fongmi.android.tv.hongguo.model.HgPreference page,
                                    HgCallback<Boolean> cb) {
        java.util.List<com.fongmi.android.tv.hongguo.model.HgPreference.Option> changed =
                page == null ? java.util.Collections.emptyList() : page.changedOptions();
        if (changed.isEmpty()) {
            cb.onSuccess(Boolean.TRUE);
            return;
        }
        java.util.List<String[]> pairs = new java.util.ArrayList<>();
        boolean[] selected = new boolean[changed.size()];
        for (int i = 0; i < changed.size(); i++) {
            pairs.add(new String[]{changed.get(i).id, changed.get(i).text});
            selected[i] = changed.get(i).selected;
        }
        postJson(HOST + "/reading/user/preference/set/",
                HgRequests.preferenceSetBody(pairs, selected), new BodyCallback() {
                    @Override
                    void onBody(String text) {
                        if (isOkCode(text)) cb.onSuccess(Boolean.TRUE);
                        else cb.onError(-2, "保存没被服务端接受");
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
     * 本人点赞列表全量（GET /reading/ugc/action/mget，不带 target_user_id
     * ＝本人维度；官方 VideoLikeDataServer.loadRemoteVideoLike 定版：
     * action_type=3(Like)、object_type_list="6,15,10"、count=50、
     * offset 续拉至 has_more=false）。本机匿名实拉结构验通（游客回空表，
     * 真数据靠 authProvider 注入的登录会话）。页数上限 20（1000 条）防
     * 服务端 has_more 异常时死循环；中途某页失败＝整次失败（消费侧回落
     * 本地镜像，不许拿半份列表当全量合并）。
     */
    public void fetchMyLikes(HgCallback<List<com.fongmi.android.tv.hongguo.model.HgAuthorWork>> cb) {
        fetchMyLikesPage(0, 0, new ArrayList<>(), cb);
    }

    private void fetchMyLikesPage(int offset, int depth,
                                  List<com.fongmi.android.tv.hongguo.model.HgAuthorWork> acc,
                                  HgCallback<List<com.fongmi.android.tv.hongguo.model.HgAuthorWork>> cb) {
        Map<String, String> q = new HashMap<>();
        q.put("action_type", "3");
        q.put("object_type_list", "6,15,10");
        q.put("count", "50");
        q.put("offset", String.valueOf(Math.max(0, offset)));
        get(HOST + "/reading/ugc/action/mget" + BV, q, new BodyCallback() {
            @Override
            void onBody(String text) {
                com.fongmi.android.tv.hongguo.model.HgAuthorWork.Page page =
                        HgParser.parseMyLikes(text);
                if (page == null) {
                    cb.onError(-2, "点赞列表回包解析失败");
                    return;
                }
                acc.addAll(page.items);
                int next = -1;
                try {
                    next = page.nextCursor.isEmpty() ? -1 : Integer.parseInt(page.nextCursor);
                } catch (Exception ignored) {
                }
                if (page.hasMore && next >= 0 && depth < 20) {
                    fetchMyLikesPage(next, depth + 1, acc, cb);
                } else {
                    cb.onSuccess(acc);
                }
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /**
     * 服务端观看历史全量（GET /reading/bookapi/read_history/list，官方
     * VideoRecordImpl.downloadRemoteRecordAsync 定版：book_type 分
     * Watch=2/Album=3 两路各自分页拉全、合并按 read_timestamp 倒序；
     * Pugc 路官方要配置开关才开，咱家不拉）。is_first_load 只在每路
     * 第一页带 true（官方按账号首拉语义）。本机匿名实拉结构验通
     * （游客回空表）。某路失败＝整次失败，消费侧回落本地历史。
     */
    public void fetchReadHistory(HgCallback<List<com.fongmi.android.tv.hongguo.model.HgHistoryEntry>> cb) {
        List<com.fongmi.android.tv.hongguo.model.HgHistoryEntry> acc =
                Collections.synchronizedList(new ArrayList<>());
        Done finish = () -> {
            List<com.fongmi.android.tv.hongguo.model.HgHistoryEntry> out =
                    new ArrayList<>(acc);
            out.sort((a, b) -> Long.compare(b.readTsMs, a.readTsMs));
            cb.onSuccess(out);
        };
        fetchReadHistoryType(2, 0, 0, acc,
                () -> fetchReadHistoryType(3, 0, 0, acc, finish, cb), cb);
    }

    private interface Done {
        void run();
    }

    private void fetchReadHistoryType(int bookType, int offset, int depth,
                                      List<com.fongmi.android.tv.hongguo.model.HgHistoryEntry> acc,
                                      Done done,
                                      HgCallback<List<com.fongmi.android.tv.hongguo.model.HgHistoryEntry>> cb) {
        Map<String, String> q = new HashMap<>();
        q.put("book_type", String.valueOf(bookType));
        q.put("offset", String.valueOf(Math.max(0, offset)));
        q.put("is_first_load", offset == 0 ? "true" : "false");
        get(HOST + "/reading/bookapi/read_history/list" + BV, q, new BodyCallback() {
            @Override
            void onBody(String text) {
                com.fongmi.android.tv.hongguo.model.HgHistoryEntry.Page page =
                        HgParser.parseReadHistory(text);
                if (page == null) {
                    cb.onError(-2, "历史回包解析失败");
                    return;
                }
                acc.addAll(page.items);
                if (page.hasMore && page.nextOffset >= 0 && depth < 20) {
                    fetchReadHistoryType(bookType, page.nextOffset, depth + 1, acc, done, cb);
                } else {
                    done.run();
                }
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
                        // 200＋字面零字节空体＝网关限流形状（HgPlayResolver
                        // fetchModel 的定型）：归一成 -2 与 fetchVideoModel
                        // onBody 的无 model 口径一致，否则解链层拿到
                        // errCode=200 进不了计数/冷却/换档案自愈（扫查
                        // H1）。只限 200 这一形，非 200 空体仍按真 HTTP
                        // 码报，不许把别的 200 错误误归 -2。
                        cb.onFail(r.code() == 200 ? -2 : r.code(), "回包空体");
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
