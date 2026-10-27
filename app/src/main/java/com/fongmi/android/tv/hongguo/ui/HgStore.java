package com.fongmi.android.tv.hongguo.ui;

import com.github.catvod.utils.Prefers;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 红果自有存储：全部键带 {@code hongguo_} 前缀走 {@link Prefers}（与音乐
 * MusicStore 同惯例），独立命名空间，不碰点播的历史表（bean.History / Room）。
 *
 * <p>存三类东西：互动本地状态（点赞/收藏/关注，四阶段换真接口后以服务端为准）、
 * 弹幕开关、观看断点（按剧存最近一集与位置，重进续播）。</p>
 */
public final class HgStore {

    private static final String K_DANMAKU_ON = "hongguo_danmaku_on";
    private static final String K_LIKED = "hongguo_liked_series";
    private static final String K_FAVED = "hongguo_faved_series";
    private static final String K_FOLLOWED = "hongguo_followed_series";
    private static final String K_HIST_PREFIX = "hongguo_hist_";
    private static final String K_LAST_SERIES = "hongguo_last_series";
    private static final String K_LAST_EP = "hongguo_last_ep";
    private static final String K_LAST_POS = "hongguo_last_pos";

    private HgStore() {
    }

    // ---------- 弹幕开关 ----------

    public static boolean isDanmakuOn() {
        return Prefers.getBoolean(K_DANMAKU_ON, false);
    }

    public static void setDanmakuOn(boolean on) {
        Prefers.put(K_DANMAKU_ON, on);
    }

    // ---------- 互动本地状态（按剧 key） ----------

    public static boolean isLiked(String seriesId) {
        return contains(K_LIKED, seriesId);
    }

    public static boolean toggleLiked(String seriesId) {
        return toggle(K_LIKED, seriesId);
    }

    public static boolean isFaved(String seriesId) {
        return contains(K_FAVED, seriesId);
    }

    public static boolean toggleFaved(String seriesId) {
        return toggle(K_FAVED, seriesId);
    }

    public static boolean isFollowed(String seriesId) {
        return contains(K_FOLLOWED, seriesId);
    }

    public static boolean toggleFollowed(String seriesId) {
        return toggle(K_FOLLOWED, seriesId);
    }

    private static Set<String> readSet(String key) {
        Set<String> out = new LinkedHashSet<>();
        String raw = Prefers.getString(key, "");
        if (raw != null && !raw.isEmpty()) {
            for (String s : raw.split(",")) if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static boolean contains(String key, String id) {
        return id != null && readSet(key).contains(id);
    }

    /** 切换成员状态，返回切换后的状态。 */
    private static boolean toggle(String key, String id) {
        if (id == null || id.isEmpty()) return false;
        Set<String> set = readSet(key);
        boolean now;
        if (set.contains(id)) {
            set.remove(id);
            now = false;
        } else {
            set.add(id);
            now = true;
        }
        Prefers.put(key, String.join(",", set));
        return now;
    }

    // ---------- 观看断点 ----------

    /** 一部剧的断点：集序号（1 起）＋集内位置毫秒。 */
    public static final class Progress {
        public final int episodeIndex;
        public final long positionMs;

        Progress(int episodeIndex, long positionMs) {
            this.episodeIndex = episodeIndex;
            this.positionMs = positionMs;
        }
    }

    public static void saveProgress(String seriesId, int episodeIndex, long positionMs) {
        if (seriesId == null || seriesId.isEmpty()) return;
        Prefers.put(K_HIST_PREFIX + seriesId,
                episodeIndex + "|" + Math.max(0, positionMs) + "|" + System.currentTimeMillis());
        Prefers.put(K_LAST_SERIES, seriesId);
        Prefers.put(K_LAST_EP, episodeIndex);
        Prefers.put(K_LAST_POS, Math.max(0, positionMs));
    }

    /** 取某剧断点；没有返回 null。历史只留最近位置，不做列表（列表面在后续阶段）。 */
    public static Progress progressOf(String seriesId) {
        if (seriesId == null || seriesId.isEmpty()) return null;
        String raw = Prefers.getString(K_HIST_PREFIX + seriesId, "");
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split("\\|");
        try {
            int ep = Integer.parseInt(parts[0]);
            long pos = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
            return new Progress(ep, pos);
        } catch (Exception e) {
            return null;
        }
    }

    public static String lastSeriesId() {
        String s = Prefers.getString(K_LAST_SERIES, "");
        return s == null || s.isEmpty() ? null : s;
    }
}
