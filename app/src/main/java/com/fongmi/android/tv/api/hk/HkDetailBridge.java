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
            // @rule= 条目不进选集：它是「点开新页面」的导航卡（相关推荐等），
            // 不是能播的集——8.83 原版里它也只是列表卡片。此前误放行，
            // 黄色仓库详情页的相关推荐卡全漏进选集（他真机截图实证）。
            boolean playable = low.startsWith("http") || url.contains("@lazyRule=")
                    || url.startsWith("js:")
                    // 磁力/电驴/迅雷配套：裸 magnet:/ed2k:/thunder: 条目此前被过滤掉、连点都没得点；
                    // 放进来，点后走星辰播放器迅雷引擎（用户拍板：海阔磁力走星辰，不维持原版）；
                    // ftp:// 的放行口径见 HkRouter.isP2pPlayLink（走星辰播放器 P2sp），不在本处选集过滤里放行。
                    || low.startsWith("magnet:") || low.startsWith("ed2k:") || low.startsWith("thunder:");
            if (!playable) continue;
            String lineName = di.getLine() == null || di.getLine().isEmpty() ? "默认" : di.getLine();
            Flag flag = flagMap.get(lineName);
            if (flag == null) {
                flag = Flag.create(lineName);
                flagMap.put(lineName, flag);
                flags.add(flag);
            }
            boolean fallbackName = di.getTitle() == null || di.getTitle().isEmpty();
            String epName = fallbackName
                    ? ("第" + (flag.getEpisodes().size() + 1) + "集") : di.getTitle();
            Episode episode = Episode.create(epName, url);
            // 源没给集名的回退序号必须打标（与 Flag/VideoActivity 推送直建同口径）：
            // Vod.setFlags 末尾 applySingleEpisodeTitle 据此把单集选集显示换成片名全名，
            // 未打标时海阔单集内容选集只剩「第1集」（他 2026-10-23 单集显名定案）。
            if (fallbackName) episode.setNumberName(true);
            flag.getEpisodes().add(episode);
        }
        vod.setFlags(flags);
        return vod;
    }
}
