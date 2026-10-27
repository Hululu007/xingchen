import com.fongmi.android.tv.hongguo.api.HgApiClient;
import com.fongmi.android.tv.hongguo.api.HgModelCache;
import com.fongmi.android.tv.hongguo.model.HgParser;
import com.fongmi.android.tv.hongguo.model.HgVideoModel;
import com.fongmi.android.tv.hongguo.ui.HgPlayResolver;
import com.google.gson.JsonParser;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HgPlayResolver 的 warm 预热竞态（定罪账 clip-play-fail-20261028 第三节）：
 * 先 warm 占住在途任务、后带 listener resolve 同一 vid，在途任务完成时
 * listener 必须收到结果——旧实现静默吞 listener，完成时无人收到。
 *
 * 在途窗口的造法：模型条目是加密串（a8 00 01 00 头 Base64）、模型无
 * key_seed、带 fallback_api，resolveSync 必然走 fetchFallback；离线桩的
 * 请求永不回调，在途任务会在 resolver 的 20 秒锁等待里挂满，窗口确定。
 * 在途任务的终局是 E3（明文链拿不到且 fplay 兜底皆空）。送达耗时是
 * 判别项：挂载应在在途任务收尾时（约 20 秒）送达；若实现是「等在途
 * 完了再重新 resolve」，会再挂满一个 20 秒（约 40 秒），靠耗时断言分开。
 */
public class HgResolverRaceTest {
    static int fail = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (detail == null ? "" : " | " + detail));
        if (!ok) fail++;
    }

    public static void main(String[] args) throws Exception {
        String vid = "race-vid-1";
        String json = "{"
                + "\"video_id\":\"" + vid + "\","
                + "\"fallback_api\":{\"fallback_api\":\"https://example.invalid/fplay\"},"
                + "\"video_list\":[{\"main_url\":\"qAABAAAA\","
                + "\"video_meta\":{\"codec_type\":\"bytevc1\",\"definition\":\"360p\"},"
                + "\"encrypt_info\":{}}]"
                + "}";
        HgVideoModel hang = HgParser.parseVideoModel(
                JsonParser.parseString(json).getAsJsonObject(), vid);
        check("竞态模型解析（加密条目＋无 key_seed＋有 fallback_api）",
                hang != null && hang.firstPlayableEntry() != null
                        && hang.getFallbackApi() != null && hang.getKeySeed() == null,
                null);

        HgModelCache cache = new HgModelCache();
        cache.put(vid, hang);
        HgPlayResolver resolver = new HgPlayResolver(new HgApiClient(), cache);

        // 1) warm 先占住在途（listener=null，只解链不回任何人，行为不变）
        resolver.warm(vid);
        Thread.sleep(500); // 让在途任务进入 fetchFallback 的锁等待

        // 2) 同 vid 带 listener resolve：应挂到在途任务上，完成时收到 Failed
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> gotVid = new AtomicReference<>();
        AtomicReference<String> gotCode = new AtomicReference<>();
        long resolveAt = System.nanoTime();
        resolver.resolve(vid, new HgPlayResolver.Listener() {
            @Override
            public void onReady(String v, String url, int w, int h) {
                gotVid.set(v);
                gotCode.set("READY");
                latch.countDown();
            }

            @Override
            public void onFailed(String v, String code, String message) {
                gotVid.set(v);
                gotCode.set(code);
                latch.countDown();
            }
        });
        boolean delivered = latch.await(40, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - resolveAt) / 1_000_000L;
        check("warm 占住后带 listener 的 resolve 在在途任务完成时收到结果",
                delivered && vid.equals(gotVid.get()),
                "delivered=" + delivered + " vid=" + gotVid.get());
        check("送达耗时 <30 秒（挂载在途任务，而非等完后重新解链）",
                delivered && elapsedMs < 30_000L, "elapsedMs=" + elapsedMs);
        check("在途任务的失败编号 E3 一路带到 listener",
                "E3".equals(gotCode.get()), "code=" + gotCode.get());

        System.out.println(fail == 0 ? "ALL PASS HgResolverRaceTest" : "FAILURES=" + fail);
        if (fail > 0) System.exit(1);
    }
}
