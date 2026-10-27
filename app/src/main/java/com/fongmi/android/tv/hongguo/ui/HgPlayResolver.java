package com.fongmi.android.tv.hongguo.ui;

import android.os.Handler;
import android.os.Looper;

import com.fongmi.android.tv.hongguo.api.HgApiClient;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.api.HgModelCache;
import com.fongmi.android.tv.hongguo.crypto.HgUrlCrypto;
import com.fongmi.android.tv.hongguo.model.HgVideoEntry;
import com.fongmi.android.tv.hongguo.model.HgVideoModel;
import com.fongmi.android.tv.hongguo.proxy.HgStreamRegistry;
import com.fongmi.android.tv.hongguo.proxy.HgStreamSpec;
import com.fongmi.android.tv.server.Server;

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
 * 一次 multi 取模型往返＋（仅 fplay 加密形态）一次 fallback 往返，其余全本地。</p>
 */
public final class HgPlayResolver {

    public interface Listener {
        void onReady(String videoId, String localUrl, int videoWidth, int videoHeight);

        void onFailed(String videoId, String message);
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
    private final Set<String> resolving = ConcurrentHashMap.newKeySet();

    public HgPlayResolver(HgApiClient client, HgModelCache cache) {
        this.client = client;
        this.cache = cache;
    }

    public String readyUrl(String videoId) {
        return videoId == null ? null : urlByVideo.get(videoId);
    }

    /** 解链并注册流；已备好直接回。listener 可为 null（只预备不播）。 */
    public void resolve(String videoId, Listener listener) {
        if (videoId == null) {
            if (listener != null) fail(listener, videoId, "videoId 为空");
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
        if (!resolving.add(videoId)) return; // 已在解，页面 settle 时会再查 readyUrl
        worker.execute(() -> {
            try {
                String url = resolveSync(videoId);
                resolving.remove(videoId);
                if (url == null) {
                    if (listener != null) fail(listener, videoId, "解链失败");
                } else if (listener != null) {
                    HgVideoModel m = cache.getFresh(videoId);
                    int w = m == null ? 0 : m.getVideoWidth();
                    int h = m == null ? 0 : m.getVideoHeight();
                    main.post(() -> listener.onReady(videoId, url, w, h));
                }
            } catch (Exception e) {
                resolving.remove(videoId);
                if (listener != null) fail(listener, videoId, e.getMessage());
            }
        });
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

    /** 回收一条流（滑远/切剧/退出）。 */
    public void close(String videoId) {
        if (videoId == null) return;
        urlByVideo.remove(videoId);
        String token = tokenByVideo.remove(videoId);
        if (token != null) registry.close(token);
    }

    public void closeAll() {
        for (String id : tokenByVideo.keySet().toArray(new String[0])) close(id);
        urlByVideo.clear();
    }

    private void fail(Listener listener, String videoId, String message) {
        main.post(() -> listener.onFailed(videoId, message == null ? "解链失败" : message));
    }

    // ---------- 后台线程同步解链（网络等待用锁，不占主线程） ----------

    private String resolveSync(String videoId) throws Exception {
        HgVideoModel model = cache.getFresh(videoId);
        if (model == null) model = fetchModel(videoId);
        if (model == null) return null;
        cache.put(videoId, model);
        HgVideoEntry entry = model.firstPlayableEntry();
        if (entry == null) return null;
        String plain = plainUrl(model, entry);
        if (plain == null) {
            // multi 形态 main_url 为明文时走不到这；加密且无 key_seed 时经 fallback_api 换 fplay 形态重试
            HgVideoModel fplay = fetchFallback(model);
            if (fplay == null) return null;
            cache.put(videoId, fplay);
            entry = fplay.firstPlayableEntry();
            if (entry == null) return null;
            plain = plainUrl(fplay, entry);
            if (plain == null) return null;
        }
        HgStreamSpec spec = HgStreamSpec.fromEntry(plain, entry, videoId);
        if (spec == null) return null; // 无 spade_a 推不出 DEK
        String token = registry.register(spec);
        if (token == null) return null;
        tokenByVideo.put(videoId, token);
        String local = Server.get().getAddress(true) + registry.playbackPath(token);
        urlByVideo.put(videoId, local);
        registry.warmAsync(token);
        return local;
    }

    /** 明文链直用；加密链用本回包 key_seed 解；缺 key_seed 返回 null 交上层走 fallback。 */
    private String plainUrl(HgVideoModel model, HgVideoEntry entry) throws Exception {
        String raw = entry.getMainUrl();
        if (raw == null || raw.isEmpty()) return null;
        if (!entry.isUrlEncrypted()) return raw;
        if (model.getKeySeed() == null || model.getKeySeed().isEmpty()) return null;
        return HgUrlCrypto.decryptUrl(raw, model.getKeySeed());
    }

    private HgVideoModel fetchModel(String videoId) {
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
