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
    private static final String K_RESERVED = "hongguo_reserved_series";
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

    // ---------- 互动状态直写（四阶段：远程同步后的镜像落点） ----------

    public static void setLiked(String seriesId, boolean on) {
        set(K_LIKED, seriesId, on);
    }

    public static void setFaved(String seriesId, boolean on) {
        set(K_FAVED, seriesId, on);
    }

    public static void setFollowed(String seriesId, boolean on) {
        set(K_FOLLOWED, seriesId, on);
    }

    /**
     * 追剧架镜像直写：官方收藏与追剧同走一个追剧架（bookshelf/video/update），
     * 服务端态回来后收藏/关注两面同写一份，防两边镜像互相打架。
     */
    public static void setShelfMirrored(String seriesId, boolean on) {
        set(K_FAVED, seriesId, on);
        set(K_FOLLOWED, seriesId, on);
    }

    private static void set(String key, String id, boolean on) {
        if (id == null || id.isEmpty()) return;
        Set<String> set = readSet(key);
        if (on) set.add(id);
        else set.remove(id);
        Prefers.put(key, String.join(",", set));
    }

    // ---------- 预约镜像（服务端为准：回包 appointment 态与预约列表回读都往这里对） ----------

    public static boolean isReserved(String seriesId) {
        return contains(K_RESERVED, seriesId);
    }

    public static void setReserved(String seriesId, boolean on) {
        set(K_RESERVED, seriesId, on);
    }

    public static boolean toggleReserved(String seriesId) {
        return toggle(K_RESERVED, seriesId);
    }

    public static java.util.List<String> reservedIds() {
        return new java.util.ArrayList<>(readSet(K_RESERVED));
    }

    public static java.util.List<String> likedIds() {
        return new java.util.ArrayList<>(readSet(K_LIKED));
    }

    public static java.util.List<String> favedIds() {
        return new java.util.ArrayList<>(readSet(K_FAVED));
    }

    public static java.util.List<String> followedIds() {
        return new java.util.ArrayList<>(readSet(K_FOLLOWED));
    }

    // ---------- 剧目元信息（列表页用：标题/封面/集数，随详情与流条目顺手落） ----------

    private static final String K_META_PREFIX = "hongguo_meta_";
    private static final String SEP = "\u0001";

    public static void putMeta(String seriesId, String title, String cover, int episodeCount) {
        if (seriesId == null || seriesId.isEmpty()) return;
        String t = title == null ? "" : title.replace(SEP, " ");
        String c = cover == null ? "" : cover.replace(SEP, " ");
        Prefers.put(K_META_PREFIX + seriesId, t + SEP + c + SEP + episodeCount);
    }

    /** 返回 {标题, 封面, 集数串}；没存过返回 null。 */
    public static String[] metaOf(String seriesId) {
        if (seriesId == null || seriesId.isEmpty()) return null;
        String raw = Prefers.getString(K_META_PREFIX + seriesId, "");
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split(SEP, -1);
        if (parts.length < 3) return null;
        return parts;
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

    /** 一部剧的断点：集序号（1 起）＋集内位置毫秒＋最后观看时刻。 */
    public static final class Progress {
        public final int episodeIndex;
        public final long positionMs;
        public final long updatedMs;

        Progress(int episodeIndex, long positionMs, long updatedMs) {
            this.episodeIndex = episodeIndex;
            this.positionMs = positionMs;
            this.updatedMs = updatedMs;
        }
    }

    private static final String K_HIST_INDEX = "hongguo_hist_index";

    public static void saveProgress(String seriesId, int episodeIndex, long positionMs) {
        if (seriesId == null || seriesId.isEmpty()) return;
        Prefers.put(K_HIST_PREFIX + seriesId,
                episodeIndex + "|" + Math.max(0, positionMs) + "|" + System.currentTimeMillis());
        Prefers.put(K_LAST_SERIES, seriesId);
        Prefers.put(K_LAST_EP, episodeIndex);
        Prefers.put(K_LAST_POS, Math.max(0, positionMs));
        touchHistIndex(seriesId);
    }

    /** 历史索引：最近观看在前，逗号串存（与互动集合同格式），上限 200 部。 */
    private static void touchHistIndex(String seriesId) {
        java.util.List<String> ids = new java.util.ArrayList<>(readSet(K_HIST_INDEX));
        ids.remove(seriesId);
        ids.add(0, seriesId);
        if (ids.size() > 200) ids = ids.subList(0, 200);
        Prefers.put(K_HIST_INDEX, String.join(",", ids));
    }

    /** 一条观看历史（历史页用）：断点＋元信息齐。 */
    public static final class HistoryRecord {
        public final String seriesId;
        public final int episodeIndex;
        public final long positionMs;
        public final long updatedMs;
        public final String title;
        public final String cover;
        public final int episodeCount;

        HistoryRecord(String seriesId, Progress p, String[] meta) {
            this.seriesId = seriesId;
            this.episodeIndex = p.episodeIndex;
            this.positionMs = p.positionMs;
            this.updatedMs = p.updatedMs;
            this.title = meta == null ? null : meta[0];
            this.cover = meta == null ? null : meta[1];
            int count = -1;
            if (meta != null) {
                try {
                    count = Integer.parseInt(meta[2]);
                } catch (Exception ignored) {
                }
            }
            this.episodeCount = count;
        }
    }

    /** 全部观看历史（最近在前）；断点丢了的条目自动跳过。 */
    public static java.util.List<HistoryRecord> historyRecords() {
        java.util.List<HistoryRecord> out = new java.util.ArrayList<>();
        for (String id : readSet(K_HIST_INDEX)) {
            Progress p = progressOf(id);
            if (p != null) out.add(new HistoryRecord(id, p, metaOf(id)));
        }
        return out;
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
            long upd = parts.length > 2 ? Long.parseLong(parts[2]) : 0;
            return new Progress(ep, pos, upd);
        } catch (Exception e) {
            return null;
        }
    }

    public static String lastSeriesId() {
        String s = Prefers.getString(K_LAST_SERIES, "");
        return s == null || s.isEmpty() ? null : s;
    }
}
