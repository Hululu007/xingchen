package com.fongmi.android.tv.ui.hk;

import com.fongmi.android.tv.api.hk.HkItem;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 详情子页预取结果静态交接槽。
 * 点条目后先在列表页把详情首屏数据抓齐，打开新页时凭 key 一次性取走消费，
 * 新页首帧即正文，不再出现空白页。
 * key 按次生成 UUID，不按 url+规则复用——杜绝不同页面串数据。
 */
public final class HkSubPagePrefetch {

    /** 交接结果：首屏条目 + 解析出的真实地址（新页 myUrl 用）。 */
    public static final class PrefetchResult {
        public final List<HkItem> list;
        public final String parsedUrl;
        final long atMillis;

        PrefetchResult(List<HkItem> list, String parsedUrl) {
            this.list = list;
            this.parsedUrl = parsedUrl == null ? "" : parsedUrl;
            this.atMillis = System.currentTimeMillis();
        }
    }

    private static final long TTL_MS = 5 * 60 * 1000L;
    private static final Map<String, PrefetchResult> SLOTS = new HashMap<>();

    private HkSubPagePrefetch() {
    }

    /** 存入并返回本次请求的 key。 */
    public static synchronized String put(List<HkItem> list, String parsedUrl) {
        pruneLocked();
        String key = UUID.randomUUID().toString();
        SLOTS.put(key, new PrefetchResult(list, parsedUrl));
        return key;
    }

    /** 取走（一次性消费）；过期或不存在返回 null，调用方回落正常加载。 */
    public static synchronized PrefetchResult take(String key) {
        if (key == null) return null;
        PrefetchResult r = SLOTS.remove(key);
        if (r != null && System.currentTimeMillis() - r.atMillis > TTL_MS) return null;
        return r;
    }

    private static void pruneLocked() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, PrefetchResult>> it = SLOTS.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().atMillis > TTL_MS) it.remove();
        }
    }
}
