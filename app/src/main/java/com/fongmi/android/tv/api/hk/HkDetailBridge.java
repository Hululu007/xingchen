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
        // 第一遍：硬过滤＋按线路收集候选（保序），过滤口径与改前逐字一致（@rule= 等
        // 规则标记条目在第二遍分拣里归引擎解析型入口永远保留，不参与聚类剔除）。
        java.util.Map<String, List<HkDetailItem>> byLine = new java.util.LinkedHashMap<>();
        for (HkDetailItem di : detail.getRawItems()) {
            String url = di.getUrl() == null ? "" : di.getUrl().trim();
            if (url.isEmpty()) continue;
            String low = url.toLowerCase();
            if (low.startsWith("javascript:") || low.equals("hiker://empty")) continue;
            if (low.startsWith("copy://") || low.startsWith("toast://")
                    || low.startsWith("download://") || low.startsWith("video://")) continue;
            boolean playable = low.startsWith("http") || url.contains("@lazyRule=")
                    || url.startsWith("js:")
                    // 磁力/电驴/迅雷配套：裸 magnet:/ed2k:/thunder: 条目此前被过滤掉、连点都没得点；
                    // 放进来，点后走星辰播放器迅雷引擎（用户拍板：海阔磁力走星辰，不维持原版）；
                    // ftp:// 的放行口径见 HkRouter.isP2pPlayLink（走星辰播放器 P2sp），不在本处选集过滤里放行。
                    || low.startsWith("magnet:") || low.startsWith("ed2k:") || low.startsWith("thunder:");
            if (!playable) continue;
            String lineName = di.getLine() == null || di.getLine().isEmpty() ? "默认" : di.getLine();
            byLine.computeIfAbsent(lineName, k -> new ArrayList<>()).add(di);
        }
        // 第二遍：逐线路分拣（HkEpisodeSorter：剔图片直链/详情自链/标签单例，治本通用层），
        // 全被剔光的线路不建空 Flag。
        String pageUrl = detail.getPageUrl() != null && !detail.getPageUrl().isEmpty()
                ? detail.getPageUrl() : (itemUrl == null ? "" : itemUrl);
        List<Flag> flags = new ArrayList<>();
        for (java.util.Map.Entry<String, List<HkDetailItem>> e : byLine.entrySet()) {
            List<HkDetailItem> items = e.getValue();
            List<String> urls = new ArrayList<>(items.size());
            for (HkDetailItem di : items) urls.add(di.getUrl() == null ? "" : di.getUrl().trim());
            boolean[] keep = HkEpisodeSorter.select(urls, pageUrl);
            Flag flag = null;
            for (int i = 0; i < items.size(); i++) {
                if (!keep[i]) continue;
                if (flag == null) {
                    flag = Flag.create(e.getKey());
                    flags.add(flag);
                }
                HkDetailItem di = items.get(i);
                boolean fallbackName = di.getTitle() == null || di.getTitle().isEmpty();
                String epName = fallbackName
                        ? ("第" + (flag.getEpisodes().size() + 1) + "集") : di.getTitle();
                Episode episode = Episode.create(epName, urls.get(i));
                // 源没给集名的回退序号必须打标（与 Flag/VideoActivity 推送直建同口径）：
                // Vod.setFlags 末尾 applySingleEpisodeTitle 据此把单集选集显示换成片名全名，
                // 未打标时海阔单集内容选集只剩「第1集」（他 2026-10-23 单集显名定案）。
                if (fallbackName) episode.setNumberName(true);
                flag.getEpisodes().add(episode);
            }
        }
        vod.setFlags(flags);
        return vod;
    }
}
