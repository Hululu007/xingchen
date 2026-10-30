package com.fongmi.android.tv.hongguo.api;

import com.fongmi.android.tv.hongguo.sign.HgSign;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p>批量口复位（2026-10-30，hongguo-fetch-quota-dig2-20261030.md §四
 * 定论）：配额探针钉死——计量键＝注册设备身份、桶＝每档案 1 次成功
 * 请求全端点共享、按请求计不按 vid 计（一发 multi 带 2 vid 回两份
 * 模型亲验）。据此新增**已签名**批量口（host sinfonlineb、
 * multi_video_model、体照红果果[短]源码），与上文摘除的无签名旧
 * multi 不是同一件；取模型改批量优先、单集口降为兜底，消耗面从
 * 1 份/集降到 1 份/批。</p>
 *
 * <p>共享档案＋自动兜底（2026-10-30，他点定，账
 * hongguo-shared-profile-impl-20261030.md）：取模型面（批量＋单集）
 * 与搜索面默认改走红果果同款公开共享档案呈现（HgDevice 的共享
 * 模式：整套呈现原子切换、不发注册请求）；自家注册档案降为兜底——
 * 共享回身份层拒绝（110001）立即以自家重发同批并锁存自家优先，
 * 共享回空体而自家同批能出货同样锁存，双空不锁存（配额问题不是
 * 身份问题）。锁存落盘在 HgDevice、只由抽屉「刷新设备档案」清。
 * 本机海外出口探针里共享身份全灭（110001/空体），但出口类别能翻
 * 结论有前科、他录屏里红果果在国内真机全链能播——终判在国内真机，
 * 兜底保证最差不差于现状。feed 频道/详情/专辑等面与登录链不切
 * 共享，维持自家呈现。</p>
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

    public HgApiClient() {
    }

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
     * 抽屉「刷新设备档案」（2026-10-30 语义改，共享档案单）：＝清
     * 兜底锁存＋重置自家档案，回到共享优先。旧语义（重置后立即重
     * 注册一份新自家档案当主身份、结果经注册成败回）随主身份改成
     * 共享档案而废：自家档案只作兜底，不在此急注册——兜底路径
     * （postSignedMode 的自家模式）经 ensureDevice 现场注册，新装
     * 机同路。本地重置不会失败，恒回成功；调用方收口（清运行时
     * 缓存＋重拉）与旧版成败同路不变。
     *
     * <p>边界（死线）：只动匿名设备档案；账号登录态（passport 会话
     * Cookie 在壳子账号托管 CloudAccountStore）不在此列，重置不读
     * 不写。若恰有一轮自家注册在途，HgDevice 代次已变，那一轮的
     * 回包会被 doRegister 的代次校验作废并按新档案自动重注册。</p>
     */
    public void resetDeviceProfile(HgCallback<Boolean> cb) {
        HgDevice.resetProfile();
        cb.onSuccess(Boolean.TRUE);
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
     * 已签名 POST（取址链专用，自家呈现）：先确保设备注册，再用注册身份＋有序查询串
     * 逐请求签名（HgSign），发往 fqnovel 宿主。签名覆盖的 body 字节与实际
     * 发送字节为同一串（bodyObj.toString 的 UTF-8 编码）。
     */
    private void postSigned(String path, JsonObject bodyObj, BodyCallback cb) {
        postSigned(HgDevice.API_HOST, path, bodyObj, cb);
    }

    /** 已签名 POST 的指定宿主版（批量取模型口走 sinfonlineb，其余同 {@link #postSigned(String, JsonObject, BodyCallback)}）。 */
    private void postSigned(String host, String path, JsonObject bodyObj, BodyCallback cb) {
        postSignedMode(host, path, bodyObj, HgDevice.MODE_OWN, cb);
    }

    /** 已签名 POST 的呈现模式版（路径版，宿主默认 fqnovel a 面）。 */
    private void postSignedMode(String path, JsonObject bodyObj, int mode, BodyCallback cb) {
        postSignedMode(HgDevice.API_HOST, path, bodyObj, mode, cb);
    }

    /**
     * 已签名 POST 的呈现模式版：共享模式不发注册请求（身份是现成的、
     * 注册反而污染共享画像），直接在共享作用域里组装发送；自家模式
     * 先 ensureDevice、再在自家作用域里组装（与旧 postSigned 同路）。
     * 作用域只罩「组装＋签名＋入队」这段同步代码，呈现整套同进同退
     * 的保证在 HgDevice 的作用域机制上（见其类注）。
     */
    private void postSignedMode(String host, String path, JsonObject bodyObj, int mode, BodyCallback cb) {
        if (mode == HgDevice.MODE_SHARED) {
            dispatchSignedPost(host, path, bodyObj, mode, cb);
            return;
        }
        ensureDevice(new HgCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                dispatchSignedPost(host, path, bodyObj, mode, cb);
            }

            @Override
            public void onError(int httpCode, String message) {
                cb.onFail(httpCode, message);
            }
        });
    }

    private void dispatchSignedPost(String host, String path, JsonObject bodyObj, int mode, BodyCallback cb) {
        int prev = HgDevice.beginPresentation(mode);
        try {
            String body = bodyObj.toString();
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            long ticket = System.currentTimeMillis();
            HgSign.Signed signed = HgSign.signPost(path, HgDevice.deviceQueryPairs(), ticket,
                    HgDevice.activeDeviceId(), HgDevice.activeVersionName(), bodyBytes, null);
            String url = host + path + "?" + signed.query;
            Map<String, String> h = headers(mode != HgDevice.MODE_SHARED);
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
        } finally {
            HgDevice.endPresentation(prev);
        }
    }

    /**
     * 已签名 GET（自家呈现）：与 postSigned 同路——先确保设备注册，
     * 再用注册身份对有序参数逐请求签名（HgSign.signGet 传真实路径），
     * 发往 fqnovel 宿主；头组装照 openhongguo anonymousHeaders 口径
     * （UA 换设备面 UA、x-ss-dp / lc / x-reading-request、游客 Cookie 兜底）。
     * feed 频道面走此口、恒自家呈现；搜索面走 {@link #getSignedMode}
     * 按默认模式（共享未锁存时走 drpys 搜索变体呈现）。
     */
    private void getSigned(String path, List<String[]> pairs, BodyCallback cb) {
        getSignedMode(path, pairs, HgDevice.MODE_OWN, false, cb);
    }

    /** 已签名 GET 的呈现模式版：mode/searchFace 必须与 pairs 的组装
     * 模式一致（HgApiClient 搜索面同点取 HgDevice.defaultMode() 一次、
     * 同时喂 pairs 构造与本方法），签名里的版本名/UA 按同一模式对齐。 */
    private void getSignedMode(String path, List<String[]> pairs, int mode, boolean searchFace, BodyCallback cb) {
        if (mode == HgDevice.MODE_SHARED) {
            dispatchSignedGet(path, pairs, mode, searchFace, cb);
            return;
        }
        ensureDevice(new HgCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                dispatchSignedGet(path, pairs, mode, searchFace, cb);
            }

            @Override
            public void onError(int httpCode, String message) {
                cb.onFail(httpCode, message);
            }
        });
    }

    private void dispatchSignedGet(String path, List<String[]> pairs, int mode, boolean searchFace, BodyCallback cb) {
        int prev = HgDevice.beginPresentation(mode);
        try {
            long ticket = System.currentTimeMillis();
            String versionName = mode == HgDevice.MODE_SHARED
                    ? (searchFace ? HgDevice.SHARED_SEARCH_VERSION_NAME : HgDevice.SHARED_VERSION_NAME)
                    : HgDevice.VERSION_NAME;
            HgSign.Signed signed = HgSign.signGet(path, pairs, ticket,
                    HgDevice.activeDeviceId(), versionName, null);
            String url = HgDevice.API_HOST + path + "?" + signed.query;
            Map<String, String> h = headers(mode != HgDevice.MODE_SHARED);
            h.put("user-agent", HgDevice.uaFor(mode, searchFace));
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
        } finally {
            HgDevice.endPresentation(prev);
        }
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

    private abstract static class BodyCallback {
        abstract void onBody(String text);

        abstract void onFail(int code, String msg);

        /** 回包 Set-Cookie（name=value 已拆好）；默认忽略，注册路用来收游客 Cookie。 */
        void onCookies(Map<String, String> cookies) {
        }
    }

    private Map<String, String> headers() {
        return headers(true);
    }

    /**
     * @param withAuth false＝不带登录态：共享呈现专用——drpys 全程
     * 游客、无任何 Cookie；且把他的账号 Cookie 挂到全网共用的共享
     * 身份上等于在服务端把账号与这份公共身份绑在一起，不许。
     * 自家呈现恒 true、与旧口径逐字一致。
     */
    private Map<String, String> headers(boolean withAuth) {
        // 登录全套已随短剧重建删除（2026-10-30）：此口不再注入任何登录态，
        // withAuth 仅为旧调用点签名保留，无实际作用。
        Map<String, String> h = new HashMap<>();
        h.put("user-agent", HgRequests.UA);
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
