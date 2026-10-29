package com.fongmi.android.tv.hongguo.ui;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fongmi.android.tv.hongguo.api.HgApiClient;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.api.HgModelCache;
import com.fongmi.android.tv.hongguo.crypto.HgUrlCrypto;
import com.fongmi.android.tv.hongguo.model.HgVideoEntry;
import com.fongmi.android.tv.hongguo.model.HgVideoModel;
import com.fongmi.android.tv.hongguo.proxy.HgStreamRegistry;
import com.fongmi.android.tv.hongguo.proxy.HgStreamSpec;
import com.fongmi.android.tv.server.Server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 单集播放解链：模型（缓存优先）→选档→解加密直链→推 DEK→注册解密流→本地 URL。
 *
 * <p>全程在自有后台线程（hg-resolve 单线程）跑：网络回调等锁、URL 解密、DEK 推导
 * 都不占主线程；结果经主线程 Handler 回页面。token 与本地 URL 按 videoId 缓存，
 * 页面滑远后由 {@link #close(String)} 回收，防注册表流堆积。</p>
 *
 * <p>耗时去向（切集起播链）：命中预取缓存时只剩本地拼装（毫秒级）；未命中时
 * 一次签名单集取模型往返＋（仅 fplay 加密形态）一次 fallback 往返，其余全本地。</p>
 */
public final class HgPlayResolver {

    public interface Listener {
        void onReady(String videoId, String localUrl, int videoWidth, int videoHeight);

        /**
         * 解链失败。code 是失败出口编号（E1–E5，见 resolveSync 各出口；
         * 异常等编号外失败为 null）——纯诊断用（定罪账 clip-play-fail-20261028
         * 候选 4），定罪后撤。
         */
        void onFailed(String videoId, String code, String message);
    }

    private static final String TAG = "HgApi";

    /** 解链结果：url 非空为成功；失败带出口编号（诊断用，定罪后撤）。 */
    private static final class Outcome {
        final String url;
        final String code;

        private Outcome(String url, String code) {
            this.url = url;
            this.code = code;
        }

        static Outcome ok(String url) {
            return new Outcome(url, null);
        }

        static Outcome fail(String code) {
            return new Outcome(null, code);
        }
    }

    private final HgApiClient client;
    private final HgModelCache cache;
    private final HgStreamRegistry registry = HgStreamRegistry.get();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hg-resolve");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> tokenByVideo = new ConcurrentHashMap<>();
    private final Map<String, String> urlByVideo = new ConcurrentHashMap<>();
    /** 面板清晰度选档（按 vid）：解链选档时优先于默认档序；close 不清，同一集换档/重解沿用。 */
    private final Map<String, String> preferredDefByVideo = new ConcurrentHashMap<>();
    /** 实际解出的档（按 vid）：面板高亮当前档读它；close 时清。 */
    private final Map<String, String> chosenDefByVideo = new ConcurrentHashMap<>();
    private final Set<String> resolving = ConcurrentHashMap.newKeySet();
    /**
     * 在途任务的挂载 listener：某条先被 warm（无 listener）占住时，后来的带
     * listener resolve 不再被静默吞掉，而是挂到在途任务上，完成时一并回
     * Ready/Failed（定罪账第三节的独立隐患）。pendingLock 同时罩住
     * resolving 的 add/remove，保证「挂载」与「收尾摘表」不交错丢人。
     */
    private final Object pendingLock = new Object();
    private final Map<String, List<Listener>> pendingListeners = new HashMap<>();

    public HgPlayResolver(HgApiClient client, HgModelCache cache) {
        this.client = client;
        this.cache = cache;
    }

    public String readyUrl(String videoId) {
        return videoId == null ? null : urlByVideo.get(videoId);
    }

    /** 当前实际在播的档（definition 原串如 "720p"）；未解出过返回 null。 */
    public String currentDefinition(String videoId) {
        return videoId == null ? null : chosenDefByVideo.get(videoId);
    }

    /**
     * 以指定档重解一条（面板清晰度切换）：落选档偏好、回收旧流后走常规
     * 解链；偏好档在模型里不存在/不可解时自动回落默认档序（firstPlayableEntry）。
     */
    public void resolveAs(String videoId, String definition, Listener listener) {
        if (videoId == null) {
            if (listener != null) fail(listener, videoId, null, "videoId 为空");
            return;
        }
        if (definition != null) preferredDefByVideo.put(videoId, definition);
        close(videoId);
        resolve(videoId, listener);
    }

    /** 选档：偏好档优先（存在且编码可解才用），否则默认档序。 */
    private HgVideoEntry pickEntry(HgVideoModel model, String videoId) {
        String want = preferredDefByVideo.get(videoId);
        if (want != null) {
            HgVideoEntry e = model.entryByDefinition(want);
            if (e != null && e.isPlayableCodec()) return e;
        }
        return model.firstPlayableEntry();
    }

    /** 解链并注册流；已备好直接回。listener 可为 null（只预备不播）。 */
    public void resolve(String videoId, Listener listener) {
        if (videoId == null) {
            if (listener != null) fail(listener, videoId, null, "videoId 为空");
            return;
        }
        String ready = urlByVideo.get(videoId);
        if (ready != null) {
            if (listener != null) {
                HgVideoModel m = cache.getFresh(videoId);
                int w = m == null ? 0 : m.getVideoWidth();
                int h = m == null ? 0 : m.getVideoHeight();
                main.post(() -> listener.onReady(videoId, ready, w, h));
            }
            return;
        }
        synchronized (pendingLock) {
            if (listener != null) {
                List<Listener> ls = pendingListeners.computeIfAbsent(videoId, k -> new ArrayList<>());
                if (!ls.contains(listener)) ls.add(listener);
            }
            if (!resolving.add(videoId)) return; // 已在解：listener 已挂在途任务，完成时由 finishResolve 一并回
        }
        worker.execute(() -> {
            Outcome outcome;
            try {
                outcome = resolveSync(videoId);
            } catch (Exception e) {
                Log.e(TAG, "resolveSync " + videoId + " 异常: " + e);
                outcome = Outcome.fail(null);
            }
            if (outcome.url == null) {
                // 失败出口编号同步进日志（诊断用，定罪后撤）
                Log.e(TAG, "resolveSync " + videoId + " 失败出口 " + outcome.code);
            }
            finishResolve(videoId, outcome);
        });
    }

    /** 在途任务收尾：摘 resolving 与挂载表，结果逐个回挂着的 listener（主线程）。 */
    private void finishResolve(String videoId, Outcome outcome) {
        List<Listener> ls;
        synchronized (pendingLock) {
            resolving.remove(videoId);
            ls = pendingListeners.remove(videoId);
        }
        if (ls == null || ls.isEmpty()) return;
        if (outcome.url == null) {
            for (Listener l : ls) fail(l, videoId, outcome.code, "解链失败");
        } else {
            HgVideoModel m = cache.getFresh(videoId);
            int w = m == null ? 0 : m.getVideoWidth();
            int h = m == null ? 0 : m.getVideoHeight();
            String url = outcome.url;
            for (Listener l : ls) main.post(() -> l.onReady(videoId, url, w, h));
        }
    }

    /** 预热一条流：解链注册后 warmAsync（盒解析＋首段取数在代理层后台线程）。 */
    public void warm(String videoId) {
        if (videoId == null) return;
        String token = tokenByVideo.get(videoId);
        if (token != null) {
            registry.warmAsync(token);
            return;
        }
        resolve(videoId, null);
    }

    /** 回收一条流（滑远/切剧/退出）。选档偏好保留（重解沿用），实际档记录清掉。 */
    public void close(String videoId) {
        if (videoId == null) return;
        urlByVideo.remove(videoId);
        chosenDefByVideo.remove(videoId);
        String token = tokenByVideo.remove(videoId);
        if (token != null) registry.close(token);
    }

    public void closeAll() {
        for (String id : tokenByVideo.keySet().toArray(new String[0])) close(id);
        urlByVideo.clear();
    }

    private void fail(Listener listener, String videoId, String code, String message) {
        main.post(() -> listener.onFailed(videoId, code, message == null ? "解链失败" : message));
    }

    // ---------- 后台线程同步解链（网络等待用锁，不占主线程） ----------

    /**
     * 同步解链。失败出口编号（诊断用，定罪后撤）：E1 取模型全灭（签名单集口
     * 3 次皆失败）；E2 模型无可播条目；E3 明文链拿不到且 fplay 兜底也空；
     * E4 选中档带加密标记但全模型扫 spade_a 推不出 DEK（模型级判定，
     * 定罪账借条 3；未保护条目免钥匙直喂、不进 E4）；E5 注册流失败。
     */
    private Outcome resolveSync(String videoId) throws Exception {
        HgVideoModel model = cache.getFresh(videoId);
        if (model == null) {
            boolean[] rateLimited = {false};
            model = fetchModel(videoId, rateLimited);
            if (model == null && rateLimited[0]) {
                // 限流形状（整轮空体）轮次计数：第一轮只进冷却（既有
                // 口径）；熬过冷却、再解仍整轮空体＝旧档案被限流定型，
                // 自动换一次新档案重试（防循环口径见
                // fetchModelAfterProfileReset）。冷却中被挡回的不算轮次
                // （fetchModel 没发请求、rateLimited 不置位）。
                int rounds = RATE_LIMIT_ROUNDS.merge(videoId, 1, Integer::sum);
                if (rounds >= RATE_LIMIT_ROUNDS_FOR_RESET) {
                    model = fetchModelAfterProfileReset(videoId);
                }
            }
            if (model != null) RATE_LIMIT_ROUNDS.remove(videoId);
        }
        if (model == null) return Outcome.fail("E1");
        cache.put(videoId, model);
        HgVideoModel use = model;
        HgVideoEntry entry = pickEntry(model, videoId);
        if (entry == null) return Outcome.fail("E2");
        String plain = plainUrl(model, entry);
        if (plain == null) {
            // 明文形态 main_url 走不到这；加密且无 key_seed 时经 fallback_api 换 fplay 形态重试
            HgVideoModel fplay = fetchFallback(model);
            if (fplay == null) return Outcome.fail("E3");
            cache.put(videoId, fplay);
            entry = pickEntry(fplay, videoId);
            if (entry == null) return Outcome.fail("E2");
            plain = plainUrl(fplay, entry);
            if (plain == null) return Outcome.fail("E3");
            use = fplay;
        }
        chosenDefByVideo.put(videoId, entry.getDefinition());
        HgStreamSpec spec = HgStreamSpec.fromEntry(plain, entry, use.deriveDekHex(), videoId);
        if (spec == null) return Outcome.fail("E4"); // 已保护但全模型推不出 DEK
        String token = registry.register(spec);
        if (token == null) return Outcome.fail("E5");
        tokenByVideo.put(videoId, token);
        String local = Server.get().getAddress(true) + registry.playbackPath(token);
        urlByVideo.put(videoId, local);
        registry.warmAsync(token);
        return Outcome.ok(local);
    }

    /** 明文链直用；加密链用本回包 key_seed 解；缺 key_seed 返回 null 交上层走 fallback。 */
    private String plainUrl(HgVideoModel model, HgVideoEntry entry) throws Exception {
        String raw = entry.getMainUrl();
        if (raw == null || raw.isEmpty()) return null;
        if (!entry.isUrlEncrypted()) return raw;
        if (model.getKeySeed() == null || model.getKeySeed().isEmpty()) return null;
        return HgUrlCrypto.decryptUrl(raw, model.getKeySeed());
    }

    /**
     * 取模型链：签名单集口（fqnovel video_model，注册身份＋逐请求签名）最多
     * 3 次，每次都是新签的名（新 ticket）；旧 multi 口已定罪摘除（类头与
     * HgApiClient 类头注）。fplay 兜底只能跟在拿到模型之后（fallback_api
     * 在回包里）。
     */
    private static final Object FETCH_GATE = new Object();
    private static long lastFetchStartMs = 0;

    /**
     * HgApiClient 对「回包无 video_model 且无业务码」的错误码：只表限流
     * 空体这一形。结构化拒绝（如 101000）由客户端原样回业务码（正数），
     * 在 fetchModel 的整轮判定里不算「无模型」——它是该条目被口径拒绝，
     * 不是档案被限流，不许触发全域冷却连坐后续正常条目。
     */
    private static final int ERR_NO_MODEL = -2;
    /**
     * 取模型冷却时长（保守值）：定根报告（feed-all-placeholder §E）只实证
     * 限流至少持续数分钟、换档案即愈，未给自然恢复时长证据；取 60 秒先
     * 止住请求加压，不长锁用户重试。
     */
    private static final long MODEL_COOLDOWN_MS = 60_000;
    /** 冷却截止时刻（档案级语义，跨 resolver 实例一致，用静态）。 */
    private static volatile long modelCooldownUntilMs = 0;

    /**
     * 换档案自愈的轮次门槛：同一条目整轮空体（限流形状）连续达到此数，
     * 才认定旧档案被限流定型、自动换新档案。第一轮仍走既有冷却口径，
     * 不一撞限流就换档案。
     */
    private static final int RATE_LIMIT_ROUNDS_FOR_RESET = 2;
    /** 同一条目连续整轮空体的轮次（静态：跨 resolver 实例一致）。 */
    private static final Map<String, Integer> RATE_LIMIT_ROUNDS = new ConcurrentHashMap<>();
    /**
     * 已为其自动换过档案的条目（本进程会话内）：每条目最多换一次，
     * 换完仍失败就走 E1 原出口，不许反复换档案死循环。
     */
    private static final Set<String> PROFILE_RESET_TRIED = ConcurrentHashMap.newKeySet();
    /** 两次自动换档案的最小间隔：限流是档案级的，刚换完别为别的条目连环换。 */
    private static final long PROFILE_RESET_MIN_INTERVAL_MS = 5 * 60_000;
    private static volatile long lastProfileResetMs = 0;

    /**
     * 取模型一轮。rateLimitedOut[0] 置真＝本轮真发了请求且整轮全是
     * 空体/无码（限流形状定型、已进冷却）；冷却中被挡回、结构化拒绝、
     * 真 HTTP 错误都不置真。
     */
    private HgVideoModel fetchModel(String videoId, boolean[] rateLimitedOut) {
        if (System.currentTimeMillis() < modelCooldownUntilMs) {
            // 冷却中不再重试该接口：限流期里连滑/点重试只会继续加压
            Log.w(TAG, "fetchModel " + videoId + " 取模型冷却中，本次不发请求");
            return null;
        }
        boolean allNoModel = true;
        for (int attempt = 0; attempt < 3; attempt++) {
            // 网关对同一设备短时密集取模型会回空体（2026-10-28 本机实证：
            // 连续取数次后 video_model 回空、换新设备档案立刻恢复）——
            // 重试之间留间隔、全局取模型起步错峰，别连发撞限流
            if (attempt == 1) sleepQuiet(1200);
            else if (attempt == 2) sleepQuiet(3000);
            gateFetchStart();
            int[] errCode = {0};
            HgVideoModel m = fetchSigned(videoId, errCode);
            if (m != null) return m;
            // 任一试拿到业务码（结构化拒绝，如 101000）或真 HTTP 错误，
            // 整轮即不是限流形状：101000 是该条目自身被口径拒绝，混进
            // 冷却判定会让一条 UGC 把 60 秒全域冷却扣到后续所有正常
            // 条目头上（定根账 feed-item-fetch-fail-20261029 §5）
            if (errCode[0] != ERR_NO_MODEL) allNoModel = false;
        }
        if (allNoModel) {
            // 整轮 3 试全是空体/无码回包＝限流形状定型，进冷却
            modelCooldownUntilMs = System.currentTimeMillis() + MODEL_COOLDOWN_MS;
            rateLimitedOut[0] = true;
            Log.w(TAG, "取模型整轮空回，进入冷却 " + (MODEL_COOLDOWN_MS / 1000) + " 秒");
        }
        return null;
    }

    /**
     * 档案级限流自愈：重置匿名设备档案→重新注册→用新档案再取一轮模型。
     * 定根依据（feed-item-fetch-fail-20261029 §6）：play 类接口对被限流
     * 的旧档案回空体，换档案即愈（本机 ohg reset 两次实证），而 App 内
     * 此前没有换档案机制、只能干等冷却。
     *
     * <p>防死循环口径：同一条目本会话最多换一次（PROFILE_RESET_TRIED），
     * 两次换档案至少间隔 PROFILE_RESET_MIN_INTERVAL_MS；换档案后仍取
     * 不到模型，返回 null 走 resolveSync 原有 E1 出口，不再重试。</p>
     *
     * <p>边界（死线）：换的只是匿名设备档案（HgDevice）；账号登录态在
     * 壳子账号托管里独立存储，重置不碰、登录不掉（见 HgDevice
     * .resetProfile 与 HgApiClient.resetDeviceProfile 的注释）。</p>
     */
    private HgVideoModel fetchModelAfterProfileReset(String videoId) {
        if (PROFILE_RESET_TRIED.contains(videoId)) return null;
        long now = System.currentTimeMillis();
        if (now - lastProfileResetMs < PROFILE_RESET_MIN_INTERVAL_MS) {
            // 刚为别的条目换过档案：限流是档案级的，新档案先用着，
            // 本条目不连环换（未记入已试集，间隔过后仍可触发）
            Log.w(TAG, "fetchModel " + videoId + " 距上次自动换档案太近，本次不换");
            return null;
        }
        PROFILE_RESET_TRIED.add(videoId);
        lastProfileResetMs = now;
        // 旧档案攒下的取模型冷却随旧档案作废，否则新档案的第一轮
        // 会被旧冷却挡在门外、白换一次
        modelCooldownUntilMs = 0;
        Log.w(TAG, "fetchModel " + videoId + " 连续整轮空体，自动重置设备档案换新档案重试");
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] ok = {false};
        client.resetDeviceProfile(new HgCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                ok[0] = true;
                latch.countDown();
            }

            @Override
            public void onError(int httpCode, String message) {
                latch.countDown();
            }
        });
        await(latch);
        if (!ok[0]) {
            Log.e(TAG, "fetchModel " + videoId + " 重置档案/重新注册失败，不再重试");
            return null;
        }
        boolean[] rateLimited = {false};
        return fetchModel(videoId, rateLimited);
    }

    private static void gateFetchStart() {
        synchronized (FETCH_GATE) {
            long wait = lastFetchStartMs + 700 - System.currentTimeMillis();
            if (wait > 0) sleepQuiet(wait);
            lastFetchStartMs = System.currentTimeMillis();
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private HgVideoModel fetchSigned(String videoId, int[] errCode) {
        CountDownLatch latch = new CountDownLatch(1);
        HgVideoModel[] box = new HgVideoModel[1];
        client.fetchVideoModel(videoId, new HgCallback<HgVideoModel>() {
            @Override
            public void onSuccess(HgVideoModel result) {
                box[0] = result;
                latch.countDown();
            }

            @Override
            public void onError(int httpCode, String message) {
                errCode[0] = httpCode;
                latch.countDown();
            }
        });
        await(latch);
        return box[0];
    }

    private HgVideoModel fetchFallback(HgVideoModel model) {
        String api = model.getFallbackApi();
        if (api == null || api.isEmpty()) return null;
        CountDownLatch latch = new CountDownLatch(1);
        HgVideoModel[] box = new HgVideoModel[1];
        client.fetchByFallbackApi(api, new HgCallback<HgVideoModel>() {
            @Override
            public void onSuccess(HgVideoModel result) {
                box[0] = result;
                latch.countDown();
            }

            @Override
            public void onError(int httpCode, String message) {
                latch.countDown();
            }
        });
        await(latch);
        return box[0];
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
