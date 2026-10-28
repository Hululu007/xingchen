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
    private static final String K_SPEED = "hongguo_speed";
    private static final String K_LAUNCH_DIRECT = "hongguo_launch_direct";
    private static final String K_AUTO_NEXT = "hongguo_auto_next";
    private static final String K_LIKED = "hongguo_liked_series";
    private static final String K_FAVED = "hongguo_faved_series";
    private static final String K_FOLLOWED = "hongguo_followed_series";
    /** 作者关注集（key＝作者 uid，与按剧的追更集 K_FOLLOWED 分家：那套喂
     * 追更页签的剧列表，混进 uid 会生出无元信息的鬼行）。 */
    private static final String K_FOLLOWED_AUTHORS = "hongguo_followed_authors";
    private static final String K_RESERVED = "hongguo_reserved_series";
    private static final String K_RESERVED_ONLINE = "hongguo_reserved_online_series";
    private static final String K_PROFILE_NAME = "hongguo_profile_name";
    private static final String K_PROFILE_AVATAR = "hongguo_profile_avatar";
    private static final String K_PROFILE_UID = "hongguo_profile_uid";
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

    // ---------- 红果默认倍速（更多面板选定即落此键，起播初始倍速读它） ----------
    // 红果链不经 PlayerManager/PlayerSetting（自有 engine 直起），此前起播
    // 倍速恒为播放器默认 1x；面板倍速是红果自己的持久档（官方按剧缓存
    // cacheVideoSpeed 的自家等价），与星辰全局倍速互不串。

    public static float getSpeed() {
        float v = Prefers.getFloat(K_SPEED, 1f);
        return v > 0 ? v : 1f;
    }

    public static void setSpeed(float speed) {
        if (speed > 0) Prefers.put(K_SPEED, speed);
    }

    // ---------- 「启动直达短剧」开关（抽屉第一项，他亲定名） ----------

    public static boolean isLaunchDirect() {
        return Prefers.getBoolean(K_LAUNCH_DIRECT, false);
    }

    public static void setLaunchDirect(boolean on) {
        Prefers.put(K_LAUNCH_DIRECT, on);
    }

    // ---------- 自动连播开关（默认开，与此前行为一致） ----------

    public static boolean isAutoNext() {
        return Prefers.getBoolean(K_AUTO_NEXT, true);
    }

    public static void setAutoNext(boolean on) {
        Prefers.put(K_AUTO_NEXT, on);
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

    // ---------- 搜索历史（最近在前，本地落盘；只存词、不带任何账号维度） ----------

    private static final String K_SEARCH_HISTORY = "hongguo_search_history_v1";
    private static final int SEARCH_HISTORY_MAX = 12;

    public static java.util.List<String> searchHistory() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String raw = Prefers.getString(K_SEARCH_HISTORY, "");
        if (raw != null && !raw.isEmpty()) {
            for (String s : raw.split("\n")) {
                String w = s.trim();
                if (!w.isEmpty()) out.add(w);
            }
        }
        return out;
    }

    public static void addSearchHistory(String word) {
        if (word == null) return;
        String w = word.trim();
        if (w.isEmpty()) return;
        java.util.List<String> list = searchHistory();
        list.remove(w);
        list.add(0, w);
        while (list.size() > SEARCH_HISTORY_MAX) list.remove(list.size() - 1);
        Prefers.put(K_SEARCH_HISTORY, String.join("\n", list));
    }

    public static void clearSearchHistory() {
        Prefers.put(K_SEARCH_HISTORY, "");
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

    // ---------- 作者关注（key＝作者 uid；主页关注钮与 feed 头像徽标同读） ----------

    public static boolean isAuthorFollowed(String authorUid) {
        return contains(K_FOLLOWED_AUTHORS, authorUid);
    }

    public static void setAuthorFollowed(String authorUid, boolean on) {
        set(K_FOLLOWED_AUTHORS, authorUid, on);
    }

    public static java.util.List<String> authorFollowedIds() {
        return new java.util.ArrayList<>(readSet(K_FOLLOWED_AUTHORS));
    }

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

    /**
     * 预约「已上线」分档集（服务端 is_online=true 回读结果落盘）：子页签
     * 分档以回源为准（官方分档在服务端）；从未回源过时集为空、页面
     * 回落集数推断。登出随账号镜像整清。
     */
    public static java.util.Set<String> reservedOnlineIds() {
        return readSet(K_RESERVED_ONLINE);
    }

    public static void setReservedOnlineIds(java.util.Collection<String> ids) {
        StringBuilder sb = new StringBuilder();
        if (ids != null) for (String id : ids) {
            if (id == null || id.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        Prefers.put(K_RESERVED_ONLINE, sb.toString());
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

    /**
     * 登出清理：账号维度的本地镜像（点赞/收藏/关注/预约四套 id 集）整清——
     * 账号数据随账号走，登出后不清、换号登录会串上前一账号的格子（审计
     * 实证）。观看历史/断点是设备维度数据、剧目元信息是公开数据，都不在此列。
     */
    public static void clearAccountMirrors() {
        Prefers.put(K_LIKED, "");
        Prefers.put(K_FAVED, "");
        Prefers.put(K_FOLLOWED, "");
        Prefers.put(K_FOLLOWED_AUTHORS, "");
        Prefers.put(K_RESERVED, "");
        Prefers.put(K_RESERVED_ONLINE, "");
        Prefers.put(K_PROFILE_NAME, "");
        Prefers.put(K_PROFILE_AVATAR, "");
        Prefers.put(K_PROFILE_UID, "");
    }

    /** 账号资料镜像（passport 资料口回填；登出随 clearAccountMirrors 一并清）。 */
    public static void saveProfile(String name, String avatar, String uid) {
        if (name != null) Prefers.put(K_PROFILE_NAME, name);
        if (avatar != null) Prefers.put(K_PROFILE_AVATAR, avatar);
        if (uid != null) Prefers.put(K_PROFILE_UID, uid);
    }

    public static String profileName() {
        String v = Prefers.getString(K_PROFILE_NAME, "");
        return v == null || v.isEmpty() ? null : v;
    }

    public static String profileAvatar() {
        String v = Prefers.getString(K_PROFILE_AVATAR, "");
        return v == null || v.isEmpty() ? null : v;
    }

    /** 本人 uid（作者主页门禁用：官方 enableClickAvatarOpenSeriesProfilePage
     * ＝非本人才能点头像进主页）；资料口没回填过为 null。 */
    public static String profileUid() {
        String v = Prefers.getString(K_PROFILE_UID, "");
        return v == null || v.isEmpty() ? null : v;
    }

    // ---------- 剧目元信息（列表页用：标题/封面/集数，随详情与流条目顺手落） ----------

    private static final String K_META_PREFIX = "hongguo_meta_";
    private static final String SEP = "\u0001";

    public static void putMeta(String seriesId, String title, String cover, int episodeCount) {
        putMeta(seriesId, title, cover, episodeCount, null);
    }

    /** badge＝服务端角标原文（漫剧/AI剧/真人剧等，流条目 tag_info.text）；第四段存，旧三段读法不受影响。badge 为 null 时保留已存的角标不冲掉。 */
    public static void putMeta(String seriesId, String title, String cover, int episodeCount,
                               String badge) {
        if (seriesId == null || seriesId.isEmpty()) return;
        String t = title == null ? "" : title.replace(SEP, " ");
        String c = cover == null ? "" : cover.replace(SEP, " ");
        String b = badge;
        if (b == null) {
            String[] old = metaOf(seriesId);
            b = old != null && old.length > 3 ? old[3] : "";
        }
        Prefers.put(K_META_PREFIX + seriesId,
                t + SEP + c + SEP + episodeCount + SEP + b.replace(SEP, " "));
    }

    /** 返回 {标题, 封面, 集数串, 角标}（角标可能缺省为""）；没存过返回 null。 */
    public static String[] metaOf(String seriesId) {
        if (seriesId == null || seriesId.isEmpty()) return null;
        String raw = Prefers.getString(K_META_PREFIX + seriesId, "");
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split(SEP, -1);
        if (parts.length < 3) return null;
        return parts;
    }

    /** 删除一条观看历史（断点+索引一并摘掉，我的页编辑模式用）。 */
    public static void removeHistory(String seriesId) {
        if (seriesId == null || seriesId.isEmpty()) return;
        Prefers.put(K_HIST_PREFIX + seriesId, "");
        java.util.List<String> ids = new java.util.ArrayList<>(readSet(K_HIST_INDEX));
        if (ids.remove(seriesId)) {
            Prefers.put(K_HIST_INDEX, String.join(",", ids));
        }
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

    /** 只写断点本体、不进历史索引/最近位：发现流短片的集内进度落盘用。 */
    public static void saveProgressQuiet(String seriesId, int episodeIndex, long positionMs) {
        if (seriesId == null || seriesId.isEmpty()) return;
        Prefers.put(K_HIST_PREFIX + seriesId,
                episodeIndex + "|" + Math.max(0, positionMs) + "|" + System.currentTimeMillis());
    }

    // ---------- 发现流位置（进程被杀后重进落位用） ----------

    private static final String K_FEED_POS = "hongguo_feed_pos";

    /** 发现流位置落盘：页签名＋流内下标，单值覆盖（只记最近一次浏览面）。 */
    public static void saveFeedPos(String tabName, int index) {
        if (tabName == null) return;
        Prefers.put(K_FEED_POS, tabName + "|" + Math.max(0, index));
    }

    /** 上次发现流所在页签名；没存过返回 null。 */
    public static String feedPosTab() {
        String raw = Prefers.getString(K_FEED_POS, "");
        if (raw == null || !raw.contains("|")) return null;
        return raw.substring(0, raw.indexOf('|'));
    }

    /** 某页签上次的流内下标；页签不符或没存过返回 -1。 */
    public static int feedPosIndex(String tabName) {
        String raw = Prefers.getString(K_FEED_POS, "");
        if (raw == null || tabName == null || !raw.startsWith(tabName + "|")) return -1;
        try {
            return Integer.parseInt(raw.substring(tabName.length() + 1));
        } catch (Exception e) {
            return -1;
        }
    }

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
