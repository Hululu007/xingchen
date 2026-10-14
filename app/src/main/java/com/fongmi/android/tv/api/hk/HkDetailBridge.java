package com.fongmi.android.tv.api.hk;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

import java.util.ArrayList;
import java.util.List;

/**
 * 海阔详情 → 星辰 VideoActivity 数据模型桥接（M3）。
 * siteKey 用 {@code hk_<规则名>} 命名空间，播放拦截（M4）据此识别。
 */
public class HkDetailBridge {

    public static final String KEY_PREFIX = "hk_";

    public static String siteKey(String ruleTitle) {
        return KEY_PREFIX + (ruleTitle == null ? "" : ruleTitle);
    }

    public static boolean isHkKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }

    public static Vod toVod(HkRule rule, String itemUrl, HkDetail detail) {
        Vod vod = new Vod();
        vod.setId(itemUrl == null ? "" : itemUrl);
        vod.setName(detail.getTitle());
        vod.setPic(detail.getPic());
        vod.setContent(detail.getContent());
        Site site = new Site();
        site.setKey(siteKey(rule.getTitle()));
        site.setName(rule.getTitle());
        vod.setSite(site);
        // 8.83 原版：从原始条目构建选集（按 line 分组，不再用预分类的 Line 模型）
        List<Flag> flags = new ArrayList<>();
        java.util.Map<String, Flag> flagMap = new java.util.LinkedHashMap<>();
        for (HkDetailItem di : detail.getRawItems()) {
            String url = di.getUrl() == null ? "" : di.getUrl().trim();
            if (url.isEmpty()) continue;
            String low = url.toLowerCase();
            if (low.startsWith("javascript:") || low.equals("hiker://empty")) continue;
            if (low.startsWith("copy://") || low.startsWith("toast://")
                    || low.startsWith("download://") || low.startsWith("video://")) continue;
            boolean playable = low.startsWith("http") || url.contains("@lazyRule=")
                    || url.contains("@rule=") || url.startsWith("js:")
                    // 磁力/电驴轻修配套：裸 magnet:/ed2k:// 条目此前被过滤掉、连点都没得点；
                    // 放进来，点后走迅雷引擎（thunder://、ftp:// 维持原版行为，不放行）。
                    || low.startsWith("magnet:") || low.startsWith("ed2k://");
            if (!playable) continue;
            String lineName = di.getLine() == null || di.getLine().isEmpty() ? "默认" : di.getLine();
            Flag flag = flagMap.get(lineName);
            if (flag == null) {
                flag = Flag.create(lineName);
                flagMap.put(lineName, flag);
                flags.add(flag);
            }
            String epName = di.getTitle() == null || di.getTitle().isEmpty()
                    ? ("第" + (flag.getEpisodes().size() + 1) + "集") : di.getTitle();
            flag.getEpisodes().add(Episode.create(epName, url));
        }
        vod.setFlags(flags);
        return vod;
    }
}
