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
        if (model == null) model = fetchModel(videoId);
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
     * 取模型（官方取数链口径，2026-10-29 对齐）：签名单集口逐请求现签、
     * 最多 3 次直取（补偿官方 TTNet 传输层自带重试；每次都是同一形状
     * 的新签请求），先成先回；全灭回 null 走 E1。官方 App 层没有的
     * 自有机制（轮次计数、60 秒全域冷却、700ms 错峰闸、重试睡眠、
     * 限流自动换档案）已整批摘除——它们改变取数行为本身且掩盖真断
     * 点（账 feed-stuck-autoscroll-20261029 主线节）。fplay 兜底只能
     * 跟在拿到模型之后（fallback_api 在回包里）。
     */
    /** 单次取模型的失败形状（最后一次失败的码与文案，落日志用）。 */
    private static final class FetchError {
        boolean recorded;
        int httpCode;
        String message;
    }

    private HgVideoModel fetchModel(String videoId) {
        FetchError lastError = new FetchError();
        for (int attempt = 0; attempt < 3; attempt++) {
            HgVideoModel m = fetchSigned(videoId, lastError);
            if (m != null) return m;
        }
        // 三次皆失败：最后一次的真实失败形状落日志（此前 onError 把
        // httpCode/message 整件丢弃，E1 不带形状、每次定根都要再跑一轮
        // 探针，定根账 feed-fail-1460-20261029 §1/§4 附带小修）。只落
        // 日志，E1 的上屏文案不动。
        Log.e(TAG, "fetchModel " + videoId + " 3 次皆失败 lastHttpCode="
                + (lastError.recorded ? lastError.httpCode : -1)
                + " lastMessage=" + (lastError.recorded ? lastError.message : "无回包（等待超时）"));
        return null;
    }

    private HgVideoModel fetchSigned(String videoId, FetchError lastError) {
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
                lastError.recorded = true;
                lastError.httpCode = httpCode;
                lastError.message = message;
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
