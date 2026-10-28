package com.fongmi.android.tv.hongguo.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 更新提示的纯判定（无 Android 依赖，离线单测直达）：追更红点与预约
 * 上线召回弹窗共用。官方两面都是服务端驱动（功能位 showRedDot／浮层
 * 任务下发），咱家无对应下发链，判定改走服务端真数据的本地推导：
 * 追更＝追剧架回源的最新集数 vs 已看基线；预约＝预约已上线档 vs
 * 已提示集。基线/已提示集的落盘在 HgStore（红果自有口）。
 */
public final class HgUpdateLogic {

    private HgUpdateLogic() {
    }

    /**
     * 追更红点判定：最新集数比「上次看过」新才算更新。基线取两者的
     * 大者——进追更面时落盘的已看快照（seenCount，没落过为 -1）、
     * 实际观看断点（watchedEpisode，看过的集不算新）。最新集数未知
     * （&le;0）不判更新，宁缺勿假。
     */
    public static boolean followHasUpdate(int latestCount, int seenCount, int watchedEpisode) {
        if (latestCount <= 0) return false;
        int baseline = Math.max(Math.max(seenCount, 0), Math.max(watchedEpisode, 0));
        return latestCount > baseline;
    }

    /**
     * 预约上线「新到」判定：已上线档里不在已知集（已提示/已播种）中的
     * 条目，按服务端回包顺序返回；空 id 与重复 id 剔除。knownIds 为
     * null 时视为全集未知（全部算新到）——首轮播种走 HgStore 的
     * seeded 旗，不经此路误弹。
     */
    public static List<String> newOnlineArrivals(List<String> onlineIds, Set<String> knownIds) {
        List<String> out = new ArrayList<>();
        if (onlineIds == null) return out;
        Set<String> dedup = new HashSet<>();
        for (String id : onlineIds) {
            if (id == null || id.isEmpty() || !dedup.add(id)) continue;
            if (knownIds == null || !knownIds.contains(id)) out.add(id);
        }
        return out;
    }
}
