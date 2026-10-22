package com.fongmi.android.tv.music;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 音乐偏好与断点：播放模式、音质、排序、退出时的队列/序号/进度。
 */
public class MusicStore {

    /** 音质四档 */
    public static final int QUALITY_LOW = 0;
    public static final int QUALITY_STANDARD = 1;
    public static final int QUALITY_HIGH = 2;
    public static final int QUALITY_SUPER = 3;

    private static final String K_MODE = "music_mode";
    private static final String K_QUALITY = "music_quality";
    private static final String K_SORT_HOME = "music_sort_home";
    private static final String K_SORT_LIST = "music_sort_list";
    private static final String K_QUEUE = "music_last_queue";
    private static final String K_INDEX = "music_last_index";
    private static final String K_POSITION = "music_last_position";
    private static final String K_QUEUE_SOURCE = "music_last_queue_source";
    private static final String K_QUEUE_ROUTE = "music_last_queue_route";
    private static final String K_QUEUE_ROUTE_PARAMS = "music_last_queue_route_params";
    /** Q10：断点歌 key（MusicQueue.trackKey 口径，随断点同一批写盘）：恢复时校验序号对应的
     * 是不是原来那首，撕裂／错位时按 key 找回，不按错序号播另一首 */
    private static final String K_INDEX_TRACK_KEY = "music_last_index_track_key";
    private static final String K_RECENT = "music_recent";
    private static final int RECENT_MAX = 50;

    /** 心情电台：建台时保存的队列曲目 keys（\n 连接） */
    private static final String K_MOOD_QUEUE_KEYS = "music_mood_queue_keys";

    public static String getMoodQueueKeys() {
        return Prefers.getString(K_MOOD_QUEUE_KEYS, "");
    }

    public static void setMoodQueueKeys(String keys) {
        Prefers.put(K_MOOD_QUEUE_KEYS, keys == null ? "" : keys);
    }

    /** 下载音质模式：固定档（默认，旧行为） */
    public static final int DL_MODE_FIXED = 0;
    /** 下载音质模式：跟随播放偏好（瓜子 DownloadQualitySettingsDialog「跟随播放偏好」） */
    public static final int DL_MODE_FOLLOW = 1;
    /** 下载音质模式：每次询问（瓜子同弹窗「每次询问」：下载前逐首选档；无 Activity 的旧入口不静默下载，见 MusicDownloadStore） */
    public static final int DL_MODE_ASK = 2;

    // ===== 音乐设置页四项（2026-10-20） =====
    private static final String K_DL_QUALITY = "music_dl_quality";
    private static final String K_DL_MODE = "music_dl_mode";
    private static final String K_AUTO_SKIP = "music_auto_skip_on_error";
    private static final String K_MOBILE_PLAY = "music_mobile_play";
    private static final String K_LYRIC_FALLBACK = "music_lyric_fallback";
    private static final String K_SPEED = "music_speed";

    /** 下载音质模式：0 固定档 1 跟随播放偏好 2 每次询问（默认固定档，旧行为不变） */
    public static int getDownloadMode() {
        int mode = Prefers.getInt(K_DL_MODE, DL_MODE_FIXED);
        return mode == DL_MODE_FOLLOW || mode == DL_MODE_ASK ? mode : DL_MODE_FIXED;
    }

    public static void setDownloadMode(int mode) {
        Prefers.put(K_DL_MODE, mode == DL_MODE_FOLLOW || mode == DL_MODE_ASK ? mode : DL_MODE_FIXED);
    }

    /** 下载固定档存值（跟随模式下不直接用它，下载链走 getDownloadQuality 生效值；每次询问模式下仅作询问弹窗的默认勾选项） */
    public static int getDownloadQualityFixed() {
        return Prefers.getInt(K_DL_QUALITY, QUALITY_STANDARD);
    }

    /**
     * 下载生效音质：跟随模式返回播放默认音质；固定模式返回固定档存值。
     * 每次询问模式下返回固定档存值，仅供无 Activity 的旧入口作安全回退口径（真正逐首选档走
     * MusicDownloadStore.downloadWithQuality，不走这里）；下载链 MusicDownloadStore 直接读它时
     * 每次询问模式已在入口处拦截，不会静默按此值下载。
     */
    public static int getDownloadQuality() {
        if (getDownloadMode() == DL_MODE_FOLLOW) return getQuality();
        return getDownloadQualityFixed();
    }

    public static void setDownloadQuality(int quality) {
        Prefers.put(K_DL_QUALITY, quality);
    }

    /** 播放失败自动跳下一首（默认开；关了则停住并提示） */
    public static boolean isAutoSkipOnError() {
        return Prefers.getBoolean(K_AUTO_SKIP, true);
    }

    public static void setAutoSkipOnError(boolean on) {
        Prefers.put(K_AUTO_SKIP, on);
    }

    /** 允许移动网络播放在线歌曲（默认开；关了流量下点在线歌会被拦，已下载不受影响） */
    public static boolean isMobilePlay() {
        return Prefers.getBoolean(K_MOBILE_PLAY, true);
    }

    public static void setMobilePlay(boolean on) {
        Prefers.put(K_MOBILE_PLAY, on);
    }

    /** 歌词缺失时自动从其它插件搜歌词（默认开） */
    public static boolean isLyricFallback() {
        return Prefers.getBoolean(K_LYRIC_FALLBACK, true);
    }

    public static void setLyricFallback(boolean on) {
        Prefers.put(K_LYRIC_FALLBACK, on);
    }

    /** 倍速（默认 1.0） */
    public static float getSpeed() {
        return Prefers.getFloat(K_SPEED, 1f);
    }

    public static void setSpeed(float speed) {
        Prefers.put(K_SPEED, speed);
    }

    // ===== 音效面板（系统 audiofx：均衡器/低音增强/环绕，music_fx_*） =====
    private static final String K_FX_ENABLED = "music_fx_enabled";
    private static final String K_FX_PRESET = "music_fx_preset";
    private static final String K_FX_BANDS = "music_fx_bands";
    private static final String K_FX_BASS = "music_fx_bass";
    private static final String K_FX_VIRT = "music_fx_virt";

    /** 音效总开关（默认关：关＝三件 audiofx 全部 bypass） */
    public static boolean isFxEnabled() {
        return Prefers.getBoolean(K_FX_ENABLED, false);
    }

    public static void setFxEnabled(boolean on) {
        Prefers.put(K_FX_ENABLED, on);
    }

    /** 当前预设序号；-1 = 自定义（手动拉杆）或还没选过 */
    public static int getFxPreset() {
        return Prefers.getInt(K_FX_PRESET, -1);
    }

    public static void setFxPreset(int preset) {
        Prefers.put(K_FX_PRESET, preset);
    }

    /** 各段电平（毫分贝），逗号分隔存；没有存过返回空数组 */
    public static short[] getFxBands() {
        String s = Prefers.getString(K_FX_BANDS, "");
        if (s == null || s.isEmpty()) return new short[0];
        try {
            String[] parts = s.split(",");
            short[] out = new short[parts.length];
            for (int i = 0; i < parts.length; i++) out[i] = Short.parseShort(parts[i].trim());
            return out;
        } catch (Throwable t) {
            return new short[0];
        }
    }

    public static void setFxBands(short[] levels) {
        if (levels == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < levels.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(levels[i]);
        }
        Prefers.put(K_FX_BANDS, sb.toString());
    }

    /** 低音增强强度 0..1000 */
    public static int getFxBass() {
        return Prefers.getInt(K_FX_BASS, 0);
    }

    public static void setFxBass(int strength) {
        Prefers.put(K_FX_BASS, Math.max(0, Math.min(1000, strength)));
    }

    /** 环绕（虚拟化）强度 0..1000 */
    public static int getFxVirtualizer() {
        return Prefers.getInt(K_FX_VIRT, 0);
    }

    public static void setFxVirtualizer(int strength) {
        Prefers.put(K_FX_VIRT, Math.max(0, Math.min(1000, strength)));
    }

    // ===== 参数均衡器（B6：Media3 处理链，频段增删／滤波类型／Q 值；与系统 audiofx 两套并存，不改称） =====
    private static final String K_PARAMETRIC_ENABLED = "music_parametric_enabled";
    private static final String K_PARAMETRIC_BANDS = "music_parametric_bands";

    /** 参数均衡器总开关（默认关） */
    public static boolean isParametricEnabled() {
        return Prefers.getBoolean(K_PARAMETRIC_ENABLED, false);
    }

    public static void setParametricEnabled(boolean on) {
        Prefers.put(K_PARAMETRIC_ENABLED, on);
    }

    /** 参数均衡器频段（JSON 存；解析失败返回空列表，不崩） */
    public static java.util.List<ParametricEq.Band> getParametricBands() {
        String s = Prefers.getString(K_PARAMETRIC_BANDS, "");
        if (s == null || s.isEmpty()) return new java.util.ArrayList<>();
        try {
            java.lang.reflect.Type type = TypeToken.getParameterized(java.util.List.class,
                    ParametricEq.Band.class).getType();
            java.util.List<ParametricEq.Band> out = App.gson().fromJson(s, type);
            return out == null ? new java.util.ArrayList<>() : out;
        } catch (Throwable ignored) {
            return new java.util.ArrayList<>();
        }
    }

    public static void setParametricBands(java.util.List<ParametricEq.Band> bands) {
        try {
            Prefers.put(K_PARAMETRIC_BANDS, App.gson().toJson(bands == null
                    ? new java.util.ArrayList<>() : bands));
        } catch (Throwable ignored) {
        }
    }

    public static int getMode() {
        return Prefers.getInt(K_MODE, MusicQueue.MODE_SEQUENTIAL);
    }

    public static void setMode(int mode) {
        Prefers.put(K_MODE, mode);
    }

    public static int getQuality() {
        return Prefers.getInt(K_QUALITY, QUALITY_STANDARD);
    }

    public static void setQuality(int quality) {
        Prefers.put(K_QUALITY, quality);
    }

    /** 四档显示名（真规格词）：128k／320k／FLAC／母带。档位值与存档不变，只改名。 */
    public static String qualityName(int q) {
        switch (q) {
            case QUALITY_LOW:
                return "128k";
            case QUALITY_HIGH:
                return "FLAC";
            case QUALITY_SUPER:
                return "母带";
            default:
                return "320k";
        }
    }

    /** 首页源排序：0 默认 1 名称 2 状态 */
    public static int getHomeSort() {
        return Prefers.getInt(K_SORT_HOME, 0);
    }

    public static void setHomeSort(int sort) {
        Prefers.put(K_SORT_HOME, sort);
    }

    /** 列表排序：0 默认 1 标题 2 歌手 3 时长；第三波 R4：升降方向（默认升序） */
    private static final String K_LIST_SORT_DIR = "music_list_sort_dir";

    public static boolean isListSortDesc() {
        return Prefers.getBoolean(K_LIST_SORT_DIR, false);
    }

    public static void setListSortDesc(boolean desc) {
        Prefers.put(K_LIST_SORT_DIR, desc);
    }

    public static int getListSort() {
        return Prefers.getInt(K_SORT_LIST, 0);
    }

    public static void setListSort(int sort) {
        Prefers.put(K_SORT_LIST, sort);
    }

    /** 断点：存队列＋序号＋进度 */
    public static void saveBreakpoint(List<MusicTrack> queue, int index, long position) {
        saveBreakpoint(queue, index, position, null, null, null);
    }

    /** 断点：存队列＋序号＋进度＋队列级来源／route（B7：恢复的队列也带来源，不再反查） */
    public static void saveBreakpoint(List<MusicTrack> queue, int index, long position,
                                     String source, String route, java.util.Map<String, String> routeParams) {
        try {
            // Q10 原子写：原来 6 次 Prefers.put 各自 apply，崩溃卡在写队列与写序号之间时
            // 新队列会配上旧序号、恢复播错一首。改成一次 Editor 批量 apply（SharedPreferences
            // 一次 apply 在内存与落盘都是同一批），并同批写断点歌 key 供恢复校验。
            List<MusicTrack> q = queue == null ? new ArrayList<>() : queue;
            int safeIdx = q.isEmpty() ? 0 : Math.max(0, Math.min(index, q.size() - 1));
            String idxKey = "";
            if (!q.isEmpty()) {
                try {
                    idxKey = MusicQueue.trackKey(q.get(safeIdx));
                } catch (Throwable ignored) {
                }
            }
            Prefers.getPrefers().edit()
                    .putString(K_QUEUE, App.gson().toJson(q))
                    .putInt(K_INDEX, safeIdx)
                    .putLong(K_POSITION, position)
                    .putString(K_QUEUE_SOURCE, source == null ? "" : source)
                    .putString(K_QUEUE_ROUTE, route == null ? "" : route)
                    .putString(K_QUEUE_ROUTE_PARAMS, routeParams == null ? "" : App.gson().toJson(routeParams))
                    .putString(K_INDEX_TRACK_KEY, idxKey)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** Q10：存盘时断点歌的 key（老版本断点无此键返回空＝不校验，沿用旧钳制口径） */
    public static String loadIndexTrackKey() {
        return Prefers.getString(K_INDEX_TRACK_KEY, "");
    }

    public static List<MusicTrack> loadQueue() {
        try {
            String json = Prefers.getString(K_QUEUE, "");
            if (json.isEmpty()) return new ArrayList<>();
            Type type = TypeToken.getParameterized(List.class, MusicTrack.class).getType();
            List<MusicTrack> list = App.gson().fromJson(json, type);
            return list == null ? new ArrayList<>() : list;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    public static int loadIndex() {
        return Prefers.getInt(K_INDEX, 0);
    }

    /** 断点恢复：队列级来源（B7；老版本断点无此键，返回空＝未知） */
    public static String loadQueueSource() {
        try {
            String s = Prefers.getString(K_QUEUE_SOURCE, "");
            return s == null ? "" : s;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 断点恢复：队列级 route（B7；老版本断点无此键，返回空＝无跳转） */
    public static String loadQueueRoute() {
        try {
            String s = Prefers.getString(K_QUEUE_ROUTE, "");
            return s == null ? "" : s;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 断点恢复：route 参数（B7；解析失败返回空 map） */
    public static java.util.Map<String, String> loadQueueRouteParams() {
        try {
            String s = Prefers.getString(K_QUEUE_ROUTE_PARAMS, "");
            if (s == null || s.isEmpty()) return new java.util.HashMap<>();
            java.lang.reflect.Type type = TypeToken.getParameterized(java.util.Map.class, String.class, String.class).getType();
            java.util.Map<String, String> m = App.gson().fromJson(s, type);
            return m == null ? new java.util.HashMap<>() : m;
        } catch (Throwable ignored) {
            return new java.util.HashMap<>();
        }
    }

    public static long loadPosition() {
        return Prefers.getLong(K_POSITION, 0);
    }

    public static void clearBreakpoint() {
        // 与 saveBreakpoint 同口径：清断点也一批清掉 key，免得空队列配旧 key 被找回错歌
        Prefers.getPrefers().edit()
                .putString(K_QUEUE, "")
                .putInt(K_INDEX, 0)
                .putLong(K_POSITION, 0)
                .putString(K_INDEX_TRACK_KEY, "")
                .apply();
    }

    /** 最近播放：切歌成功即记一条，按 id 去重，上限 50 首 */
    public static void pushRecent(MusicTrack track) {
        if (track == null || track.id == null) return;
        try {
            List<MusicTrack> list = loadRecent();
            for (int i = 0; i < list.size(); i++) {
                if (track.id.equals(list.get(i).id)) {
                    list.remove(i);
                    break;
                }
            }
            list.add(0, track);
            while (list.size() > RECENT_MAX) list.remove(list.size() - 1);
            Prefers.put(K_RECENT, App.gson().toJson(list));
        } catch (Throwable ignored) {
        }
    }

    public static List<MusicTrack> loadRecent() {
        try {
            String json = Prefers.getString(K_RECENT, "");
            if (json.isEmpty()) return new ArrayList<>();
            Type type = TypeToken.getParameterized(List.class, MusicTrack.class).getType();
            List<MusicTrack> list = App.gson().fromJson(json, type);
            return list == null ? new ArrayList<>() : list;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    /** 历史页行级移除：按 id 删掉若干条最近播放（不在场的 id 直接跳过） */
    public static void removeRecentByIds(java.util.Set<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        try {
            List<MusicTrack> list = loadRecent();
            List<MusicTrack> kept = new ArrayList<>();
            for (MusicTrack t : list) {
                if (t == null || t.id == null || !ids.contains(t.id)) kept.add(t);
            }
            Prefers.put(K_RECENT, App.gson().toJson(kept));
        } catch (Throwable ignored) {
        }
    }

    /** 历史页清空：最近播放全部清掉 */
    public static void clearRecent() {
        Prefers.put(K_RECENT, "");
    }

    // ===== 搜索历史（搜索页「最近搜索」胶囊；GZ 胶囊 12sp、内边距 10/6dp） =====
    private static final String K_RECENT_SEARCH = "music_recent_search";
    private static final int RECENT_SEARCH_MAX = 20;

    public static List<String> loadRecentSearches() {
        try {
            String json = Prefers.getString(K_RECENT_SEARCH, "");
            if (json.isEmpty()) return new ArrayList<>();
            Type type = TypeToken.getParameterized(List.class, String.class).getType();
            List<String> list = App.gson().fromJson(json, type);
            return list == null ? new ArrayList<>() : list;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    /** 搜过的词记一条：去重、最前、上限 20 条 */
    public static void pushRecentSearch(String kw) {
        if (kw == null) return;
        kw = kw.trim();
        if (kw.isEmpty()) return;
        try {
            List<String> list = loadRecentSearches();
            list.remove(kw);
            list.add(0, kw);
            while (list.size() > RECENT_SEARCH_MAX) list.remove(list.size() - 1);
            Prefers.put(K_RECENT_SEARCH, App.gson().toJson(list));
        } catch (Throwable ignored) {
        }
    }

    public static void clearRecentSearches() {
        Prefers.put(K_RECENT_SEARCH, "");
    }

    // ===== 单插件屏蔽平台（插件页 ⋮「屏蔽平台」；GZ：改该插件自身的 blockedPlatforms 列表，不动其他插件） =====
    // 存法：Map<插件 srcUrl, 逗号拼起来的平台 key>，平台 key 里没有逗号，拼法够用
    private static final String K_BLOCKED_PLATFORMS = "music_blocked_platforms";

    private static java.util.Map<String, String> loadBlockedMap() {
        try {
            String json = Prefers.getString(K_BLOCKED_PLATFORMS, "");
            if (json.isEmpty()) return new java.util.HashMap<>();
            Type type = TypeToken.getParameterized(java.util.Map.class, String.class, String.class).getType();
            java.util.Map<String, String> map = App.gson().fromJson(json, type);
            return map == null ? new java.util.HashMap<>() : map;
        } catch (Throwable ignored) {
            return new java.util.HashMap<>();
        }
    }

    /** 某插件屏蔽掉的平台集合（空 = 没屏蔽） */
    public static java.util.Set<String> getBlockedPlatforms(String srcUrl) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (srcUrl == null) return out;
        String joined = loadBlockedMap().get(srcUrl);
        if (joined == null || joined.isEmpty()) return out;
        for (String p : joined.split(",")) {
            if (p != null && !p.isEmpty()) out.add(p);
        }
        return out;
    }

    public static void setBlockedPlatforms(String srcUrl, java.util.Set<String> platforms) {
        if (srcUrl == null) return;
        try {
            java.util.Map<String, String> map = loadBlockedMap();
            if (platforms == null || platforms.isEmpty()) map.remove(srcUrl);
            else {
                StringBuilder sb = new StringBuilder();
                for (String p : platforms) {
                    if (p == null || p.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(",");
                    sb.append(p);
                }
                map.put(srcUrl, sb.toString());
            }
            Prefers.put(K_BLOCKED_PLATFORMS, App.gson().toJson(map));
        } catch (Throwable ignored) {
        }
    }

    /** 某插件是否屏蔽了某平台（取链/换源/歌词路由侧用） */
    public static boolean isPlatformBlocked(String srcUrl, String platform) {
        if (srcUrl == null || platform == null || platform.isEmpty()) return false;
        return getBlockedPlatforms(srcUrl).contains(platform);
    }

    // ===== 第二波：播放路由（瓜子 LxPluginScreen 播放路由区；只加新键，不动已有键） =====
    // 取链策略：瓜子 LxPluginPriority 两档，存法照瓜子 toStorage：fallback（自动切换）／pluginOnly（仅插件），默认 fallback。
    // 注意：本批只做存取＋UI 行；取链解析侧是否消费该策略不在本批文件内（MusicRepository 禁动），见交卷待办。
    public static final String LINK_POLICY_FALLBACK = "fallback";
    public static final String LINK_POLICY_PLUGIN_ONLY = "pluginOnly";
    private static final String K_LINK_POLICY = "music_link_policy";

    public static String getLinkPolicy() {
        String v = Prefers.getString(K_LINK_POLICY, LINK_POLICY_FALLBACK);
        return LINK_POLICY_PLUGIN_ONLY.equals(v) ? LINK_POLICY_PLUGIN_ONLY : LINK_POLICY_FALLBACK;
    }

    public static void setLinkPolicy(String policy) {
        Prefers.put(K_LINK_POLICY, LINK_POLICY_PLUGIN_ONLY.equals(policy) ? LINK_POLICY_PLUGIN_ONLY : LINK_POLICY_FALLBACK);
    }

    // 指定平台插件：平台 key → 插件 srcUrl（空／缺失 = 自动，即瓜子 AssignOptionRow「默认（自动选择）」）。
    // 存法复用歌单简介同款 loadStringMap／saveStringMap（Map<String, String> JSON）。
    private static final String K_PLATFORM_PLUGIN = "music_platform_plugin";

    /** 某平台指定的取链插件 srcUrl（null = 自动） */
    public static String getPlatformPlugin(String platform) {
        if (platform == null || platform.isEmpty()) return null;
        String v = loadStringMap(K_PLATFORM_PLUGIN).get(platform);
        return (v == null || v.isEmpty()) ? null : v;
    }

    public static void setPlatformPlugin(String platform, String srcUrl) {
        if (platform == null || platform.isEmpty()) return;
        java.util.Map<String, String> map = loadStringMap(K_PLATFORM_PLUGIN);
        if (srcUrl == null || srcUrl.isEmpty()) map.remove(platform);
        else map.put(platform, srcUrl);
        saveStringMap(K_PLATFORM_PLUGIN, map);
    }

    /** 已指定平台个数（指定行副文「已指定 N 个平台」用） */
    public static int getAssignedPlatformCount() {
        int n = 0;
        for (String v : loadStringMap(K_PLATFORM_PLUGIN).values()) {
            if (v != null && !v.isEmpty()) n++;
        }
        return n;
    }

    /** 已指定平台 key 集合（含已卸载插件残留的平台，屏蔽面板同款“残留可见可恢复”口径） */
    public static java.util.Set<String> getAssignedPlatforms() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, String> e : loadStringMap(K_PLATFORM_PLUGIN).entrySet()) {
            if (e.getKey() != null && !e.getKey().isEmpty() && e.getValue() != null && !e.getValue().isEmpty()) out.add(e.getKey());
        }
        return out;
    }

    /** 本地「移除」恢复入口用：清空全部忽略 id（文件不动，只恢复列表显示） */
    public static void clearIgnoredDeviceIds() {
        try {
            Prefers.put(K_IGNORED_DEVICE, "");
        } catch (Throwable ignored) {
        }
    }

    // ===== 第二波：本地扫描偏好（瓜子 LibraryScreen 扫描过滤／文件夹排除；只加新键） =====
    // 扫描过滤：不过滤 = 0，其余为最小时长毫秒（瓜子档：30 秒／1 分钟／2 分钟）。
    private static final String K_DEVICE_MIN_DURATION = "music_device_min_duration";
    private static final String K_DEVICE_EXCLUDED_FOLDERS = "music_device_excluded_folders";

    public static long getDeviceMinDuration() {
        try {
            long v = Long.parseLong(Prefers.getString(K_DEVICE_MIN_DURATION, "0"));
            return (v == 30000L || v == 60000L || v == 120000L) ? v : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static void setDeviceMinDuration(long ms) {
        Prefers.put(K_DEVICE_MIN_DURATION, String.valueOf(
                (ms == 30000L || ms == 60000L || ms == 120000L) ? ms : 0L));
    }

    /** 排除扫描的文件夹（绝对路径集合；命中 = 该目录及其子目录下的歌不进库，不删文件） */
    public static java.util.Set<String> getDeviceExcludedFolders() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            String json = Prefers.getString(K_DEVICE_EXCLUDED_FOLDERS, "");
            if (json.isEmpty()) return out;
            Type type = TypeToken.getParameterized(List.class, String.class).getType();
            List<String> list = App.gson().fromJson(json, type);
            if (list != null) {
                for (String s : list) if (s != null && !s.isEmpty()) out.add(s);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void setDeviceExcludedFolders(java.util.Set<String> folders) {
        try {
            Prefers.put(K_DEVICE_EXCLUDED_FOLDERS, App.gson().toJson(
                    new ArrayList<>(folders == null ? new java.util.HashSet<String>() : folders)));
        } catch (Throwable ignored) {
        }
    }

    // ===== 歌单自定义封面/简介/收藏（歌单详情页英雄卡；不改 Room schema，prefs 存） =====
    private static final String K_SHEET_COVER = "music_sheet_cover";
    private static final String K_SHEET_DESC = "music_sheet_desc";
    private static final String K_SHEET_FAV = "music_sheet_fav";

    private static java.util.Map<String, String> loadStringMap(String key) {
        try {
            String json = Prefers.getString(key, "");
            if (json.isEmpty()) return new java.util.HashMap<>();
            Type type = TypeToken.getParameterized(java.util.Map.class, String.class, String.class).getType();
            java.util.Map<String, String> map = App.gson().fromJson(json, type);
            return map == null ? new java.util.HashMap<>() : map;
        } catch (Throwable ignored) {
            return new java.util.HashMap<>();
        }
    }

    private static void saveStringMap(String key, java.util.Map<String, String> map) {
        try {
            Prefers.put(key, App.gson().toJson(map));
        } catch (Throwable ignored) {
        }
    }

    /** 歌单自定义封面：应用文件目录里的图路径（空 = 没自定义） */
    public static String getSheetCover(long playlistId) {
        return loadStringMap(K_SHEET_COVER).get(String.valueOf(playlistId));
    }

    public static void setSheetCover(long playlistId, String path) {
        java.util.Map<String, String> map = loadStringMap(K_SHEET_COVER);
        if (path == null || path.isEmpty()) map.remove(String.valueOf(playlistId));
        else map.put(String.valueOf(playlistId), path);
        saveStringMap(K_SHEET_COVER, map);
    }

    // ===== 歌曲自定义封面（播放页大封面＋背景；本地相册选图，只保存在本机，不上传、不登录） =====
    private static final String K_SONG_COVER = "music_song_custom_cover";

    /** 歌曲自定义封面键：id 有则 platform|id，否则 platform|title|artist；都空返回空 */
    public static String songCoverKey(MusicTrack track) {
        if (track == null) return null;
        String platform = track.platform == null ? "" : track.platform;
        if (track.id != null && !track.id.isEmpty()) return platform + "|" + track.id;
        String title = track.title == null ? "" : track.title;
        String artist = track.artist == null ? "" : track.artist;
        if (title.isEmpty() && artist.isEmpty()) return null;
        return platform + "|" + title + "|" + artist;
    }

    /** 歌曲自定义封面：应用文件目录里图的 file:// 地址（空 = 没自定义） */
    public static String getSongCover(String key) {
        if (key == null) return null;
        return loadStringMap(K_SONG_COVER).get(key);
    }

    public static void setSongCover(String key, String fileUri) {
        if (key == null) return;
        java.util.Map<String, String> map = loadStringMap(K_SONG_COVER);
        if (fileUri == null || fileUri.isEmpty()) map.remove(key);
        else map.put(key, fileUri);
        saveStringMap(K_SONG_COVER, map);
    }

    /** 歌单简介（空 = 没写简介，英雄卡不显示简介行） */
    public static String getSheetDesc(long playlistId) {
        return loadStringMap(K_SHEET_DESC).get(String.valueOf(playlistId));
    }

    public static void setSheetDesc(long playlistId, String desc) {
        java.util.Map<String, String> map = loadStringMap(K_SHEET_DESC);
        if (desc == null || desc.trim().isEmpty()) map.remove(String.valueOf(playlistId));
        else map.put(String.valueOf(playlistId), desc.trim());
        saveStringMap(K_SHEET_DESC, map);
    }

    /** 收藏的歌单 id 集合（歌单详情页「收藏歌单」两态） */
    public static java.util.Set<Long> loadSheetFavs() {
        java.util.Set<Long> out = new java.util.HashSet<>();
        try {
            String json = Prefers.getString(K_SHEET_FAV, "");
            if (json.isEmpty()) return out;
            Type type = TypeToken.getParameterized(List.class, Long.class).getType();
            List<Long> list = App.gson().fromJson(json, type);
            if (list != null) out.addAll(list);
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void setSheetFav(long playlistId, boolean fav) {
        try {
            java.util.Set<Long> set = loadSheetFavs();
            if (fav) set.add(playlistId);
            else set.remove(playlistId);
            Prefers.put(K_SHEET_FAV, App.gson().toJson(new ArrayList<>(set)));
        } catch (Throwable ignored) {
        }
    }

    // ===== 本地音乐忽略（本地页行内「移除」；重启仍生效，恢复入口待定） =====
    private static final String K_IGNORED_DEVICE = "music_ignored_device";

    public static java.util.Set<String> loadIgnoredDeviceIds() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            String json = Prefers.getString(K_IGNORED_DEVICE, "");
            if (json.isEmpty()) return out;
            Type type = TypeToken.getParameterized(List.class, String.class).getType();
            List<String> list = App.gson().fromJson(json, type);
            if (list != null) out.addAll(list);
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void ignoreDeviceId(String trackId) {
        if (trackId == null) return;
        try {
            java.util.Set<String> set = loadIgnoredDeviceIds();
            set.add(trackId);
            Prefers.put(K_IGNORED_DEVICE, App.gson().toJson(new ArrayList<>(set)));
        } catch (Throwable ignored) {
        }
    }

    // ===== 播放页主题与歌词样式（工具面板/全屏歌词页共用，2026-10-21） =====

    /** 播放页主题 id（与 GZ PlayerTheme 清单同名） */
    public static final String THEME_CLASSIC = "classic";
    public static final String THEME_APPLE_MUSIC = "apple_music";
    public static final String THEME_COVER_IMMERSIVE = "cover_immersive";
    public static final String THEME_LUMINOUS_CD = "luminous_cd";
    public static final String THEME_RECORD_SHELF = "record_shelf";

    /** 流光 CD 表面：浅色 */
    public static final int CD_SURFACE_LIGHT = 0;
    /** 流光 CD 表面：深色 */
    public static final int CD_SURFACE_DARK = 1;
    /** 流光 CD 表面：自动 */
    public static final int CD_SURFACE_AUTO = 2;

    /** 歌词粗细：标准 */
    public static final int LYRIC_WEIGHT_NORMAL = 0;
    /** 歌词粗细：加粗 */
    public static final int LYRIC_WEIGHT_BOLD = 1;
    /** 歌词粗细：特粗 */
    public static final int LYRIC_WEIGHT_BLACK = 2;

    /** 歌词对齐：靠左 */
    public static final int LYRIC_ALIGN_LEFT = 0;
    /** 歌词对齐：居中 */
    public static final int LYRIC_ALIGN_CENTER = 1;
    /** 歌词对齐：靠右 */
    public static final int LYRIC_ALIGN_RIGHT = 2;

    /** 非当前行模糊：关闭 */
    public static final int LYRIC_BLUR_OFF = 0;
    /** 非当前行模糊：轻度 */
    public static final int LYRIC_BLUR_LIGHT = 1;
    /** 非当前行模糊：中度 */
    public static final int LYRIC_BLUR_MEDIUM = 2;

    /** 主歌词：原文 */
    public static final int LYRIC_PRIMARY_ORIGINAL = 0;
    /** 主歌词：翻译 */
    public static final int LYRIC_PRIMARY_TRANSLATION = 1;
    /** 主歌词：音译 */
    public static final int LYRIC_PRIMARY_ROMANIZATION = 2;

    private static final String K_PLAYER_THEME = "music_player_theme";
    private static final String K_CD_SURFACE = "music_cd_surface";
    private static final String K_LYRIC_FONT_SIZE = "music_lyric_font_size";
    private static final String K_LYRIC_WEIGHT = "music_lyric_weight";
    private static final String K_LYRIC_ALIGN = "music_lyric_align";
    private static final String K_LYRIC_BLUR = "music_lyric_blur";
    private static final String K_LYRIC_PRIMARY = "music_lyric_primary";
    private static final String K_SHOW_TRANSLATION = "music_show_translation";
    private static final String K_SHOW_ROMANIZATION = "music_show_romanization";
    private static final String K_ADVANCED_FX = "music_advanced_fx";
    private static final String K_PLAYER_KEEP_SCREEN_ON = "music_player_keep_screen_on";

    /** 播放页主题 id（全新默认封面卡片 cover_immersive，1.4.3 改令；已存的用户值一律不动）。
     * 必-4 一处收口：未存值仍走全新默认 cover_immersive；已存非法值（旧版／外写残留，
     * 存入校验拦不住的历史值）读侧回落 classic，与 setPlayerTheme 非法写 classic、
     * 设置页 else 分支 classic 同口径；存入校验与选择器预选不动。 */
    public static String getPlayerTheme() {
        String theme = Prefers.getString(K_PLAYER_THEME, THEME_COVER_IMMERSIVE);
        if (theme == null || theme.isEmpty()) return THEME_COVER_IMMERSIVE;
        if (THEME_CLASSIC.equals(theme) || THEME_APPLE_MUSIC.equals(theme)
                || THEME_COVER_IMMERSIVE.equals(theme) || THEME_LUMINOUS_CD.equals(theme)
                || THEME_RECORD_SHELF.equals(theme)) {
            return theme;
        }
        return THEME_CLASSIC;
    }

    public static void setPlayerTheme(String theme) {
        // T1：只存 5 个合法 id，非法（含空）写 classic，与瓜子 PlayerTheme.fromId 未知 id → Classic 一致；
        // getPlayerTheme 的全新默认（cover_immersive）不动，两者分属不同落点：存入校验≠读取默认。
        if (THEME_CLASSIC.equals(theme) || THEME_APPLE_MUSIC.equals(theme)
                || THEME_COVER_IMMERSIVE.equals(theme) || THEME_LUMINOUS_CD.equals(theme)
                || THEME_RECORD_SHELF.equals(theme)) {
            Prefers.put(K_PLAYER_THEME, theme);
        } else {
            Prefers.put(K_PLAYER_THEME, THEME_CLASSIC);
        }
    }

    /** 流光 CD 表面：0 浅 1 深 2 自动（默认自动） */
    public static int getCdSurface() {
        return Prefers.getInt(K_CD_SURFACE, CD_SURFACE_AUTO);
    }

    public static void setCdSurface(int surface) {
        Prefers.put(K_CD_SURFACE, Math.max(CD_SURFACE_LIGHT, Math.min(CD_SURFACE_AUTO, surface)));
    }

    /** L5：歌词字号拆双键。旧键 music_lyric_font_size 即全屏键（已有键不动、只改默认与口径）：
     * 全屏默认 24（瓜子 PlayerViewModel:1026）、钳 16–40；封面键新增，默认 22（:1030）、钳 14–32。
     * 设置页旧字号行仍走全屏键，不改该页。 */
    private static final String K_LYRIC_FONT_SIZE_COVER = "music_lyric_font_size_cover";
    /** 必-6 字号迁移标志：封面键首次读取后即置 true，迁移只做一次。 */
    private static final String K_LYRIC_FONT_SIZE_COVER_MIGRATED = "music_lyric_font_size_cover_migrated";

    /** 全屏歌词字号 sp（默认 24，钳 16–40） */
    public static float getLyricFontSize() {
        float size = Prefers.getFloat(K_LYRIC_FONT_SIZE, 24f);
        return Math.max(16f, Math.min(40f, size));
    }

    public static void setLyricFontSize(float size) {
        Prefers.put(K_LYRIC_FONT_SIZE, Math.max(16f, Math.min(40f, size)));
    }

    /** 封面（播放页内嵌）歌词字号 sp（默认 22，钳 14–32）。
     * 必-6 迁移：封面键全新，老用户以前为封面设的字号只存在旧键（即全屏键）里；
     * 封面键首次读取且未迁移时，若旧键确已存值，以旧键值钳 14–32 写入一次并置标志，
     * 之后封面键独立演进，再改旧键（全屏字号行）不再反写封面；旧键从未存值的新用户
     * 只置标志、不写值，否则新用户封面默认会被旧键默认值 24 带偏（应为 22）。 */
    public static float getCoverLyricFontSize() {
        if (!Prefers.getBoolean(K_LYRIC_FONT_SIZE_COVER_MIGRATED, false)) {
            boolean hasOld = false;
            try {
                hasOld = Prefers.getPrefers().contains(K_LYRIC_FONT_SIZE);
            } catch (Throwable ignored) {
            }
            if (hasOld) {
                Prefers.put(K_LYRIC_FONT_SIZE_COVER, Math.max(14f, Math.min(32f, getLyricFontSize())));
            }
            Prefers.put(K_LYRIC_FONT_SIZE_COVER_MIGRATED, true);
        }
        float size = Prefers.getFloat(K_LYRIC_FONT_SIZE_COVER, 22f);
        return Math.max(14f, Math.min(32f, size));
    }

    public static void setCoverLyricFontSize(float size) {
        Prefers.put(K_LYRIC_FONT_SIZE_COVER, Math.max(14f, Math.min(32f, size)));
    }

    /** 歌词粗细：0 标准 1 加粗 2 特粗（默认标准） */
    public static int getLyricWeight() {
        return Prefers.getInt(K_LYRIC_WEIGHT, LYRIC_WEIGHT_NORMAL);
    }

    public static void setLyricWeight(int weight) {
        Prefers.put(K_LYRIC_WEIGHT, Math.max(LYRIC_WEIGHT_NORMAL, Math.min(LYRIC_WEIGHT_BLACK, weight)));
    }

    /** 歌词对齐：0 左 1 中 2 右（默认左） */
    public static int getLyricAlign() {
        return Prefers.getInt(K_LYRIC_ALIGN, LYRIC_ALIGN_LEFT);
    }

    public static void setLyricAlign(int align) {
        Prefers.put(K_LYRIC_ALIGN, Math.max(LYRIC_ALIGN_LEFT, Math.min(LYRIC_ALIGN_RIGHT, align)));
    }

    /** 非当前行模糊：0 关 1 轻 2 中（默认轻，与工具面板“恢复默认”值一致） */
    public static int getLyricBlur() {
        return Prefers.getInt(K_LYRIC_BLUR, LYRIC_BLUR_LIGHT);
    }

    public static void setLyricBlur(int blur) {
        Prefers.put(K_LYRIC_BLUR, Math.max(LYRIC_BLUR_OFF, Math.min(LYRIC_BLUR_MEDIUM, blur)));
    }

    /** 主歌词：0 原文 1 翻译 2 音译（默认原文） */
    public static int getLyricPrimary() {
        return Prefers.getInt(K_LYRIC_PRIMARY, LYRIC_PRIMARY_ORIGINAL);
    }

    public static void setLyricPrimary(int primary) {
        Prefers.put(K_LYRIC_PRIMARY, Math.max(LYRIC_PRIMARY_ORIGINAL, Math.min(LYRIC_PRIMARY_ROMANIZATION, primary)));
    }

    /** 显示翻译副行（默认开） */
    public static boolean isShowTranslation() {
        return Prefers.getBoolean(K_SHOW_TRANSLATION, true);
    }

    public static void setShowTranslation(boolean show) {
        Prefers.put(K_SHOW_TRANSLATION, show);
    }

    /** 显示音译副行（默认关） */
    public static boolean isShowRomanization() {
        return Prefers.getBoolean(K_SHOW_ROMANIZATION, false);
    }

    public static void setShowRomanization(boolean show) {
        Prefers.put(K_SHOW_ROMANIZATION, show);
    }

    /** 歌词高级动效（逐字上浮/长音光晕，默认开） */
    public static boolean isAdvancedFx() {
        return Prefers.getBoolean(K_ADVANCED_FX, true);
    }

    public static void setAdvancedFx(boolean on) {
        Prefers.put(K_ADVANCED_FX, on);
    }

    /** 播放页不熄屏（默认关；开关只存这里，生效由播放页读） */
    public static boolean isPlayerKeepScreenOn() {
        return Prefers.getBoolean(K_PLAYER_KEEP_SCREEN_ON, false);
    }

    public static void setPlayerKeepScreenOn(boolean on) {
        Prefers.put(K_PLAYER_KEEP_SCREEN_ON, on);
    }

    // ===== 面板＋设置面（redo3 round4） =====

    /** 左手布局（横屏）（A7；默认右手） */
    private static final String K_LEFT_HAND = "music_left_hand_landscape";
    /** 动态流体背景（A4；默认关） */
    private static final String K_FLUID_BG = "music_fluid_bg";
    /** 音乐律动（A4；默认关） */
    private static final String K_RHYTHM = "music_rhythm";
    /** 插件检测结果表（C4；srcUrl → MusicPluginTest 的 JSON） */
    private static final String K_PLUGIN_TESTS = "music_plugin_tests";
    /** 取链耗时表（C5；平台 → 插件 srcUrl → 最近取链耗时 ms 的 JSON） */
    private static final String K_PLUGIN_LATENCY = "music_plugin_latency";
    /** 多插件智能切换（C5；默认关） */
    private static final String K_SMART_SWITCH = "music_plugin_smart_switch";
    /** 自动屏蔽无效平台（C4；默认关） */
    private static final String K_AUTO_BLOCK = "music_plugin_auto_block";
    /** 歌词时间偏移（U-05；毫秒整数，默认 0；范围钳制 ±5000ms 为星辰侧自定，非瓜子值） */
    private static final String K_LYRIC_OFFSET_MS = "music_lyric_offset_ms";
    /** 第三波 L1：老值符号翻转迁移标志（旧口径播放位置 − offset，新口径 ＋ offset；只翻一次）。 */
    private static final String K_LYRIC_OFFSET_MIGRATED = "music_lyric_offset_migrated";
    /** 歌词时间偏移钳制（星辰侧自定，非瓜子值） */
    public static final int LYRIC_OFFSET_MAX_MS = 5000;

    public static boolean isLeftHandLandscape() {
        return Prefers.getBoolean(K_LEFT_HAND, false);
    }

    public static void setLeftHandLandscape(boolean on) {
        Prefers.put(K_LEFT_HAND, on);
    }

    public static boolean isFluidBg() {
        return Prefers.getBoolean(K_FLUID_BG, false);
    }

    public static void setFluidBg(boolean on) {
        Prefers.put(K_FLUID_BG, on);
    }

    public static boolean isRhythm() {
        return Prefers.getBoolean(K_RHYTHM, false);
    }

    public static void setRhythm(boolean on) {
        Prefers.put(K_RHYTHM, on);
    }

    /** 插件检测结果表（读不到／解析失败返回空表，不抛） */
    public static java.util.Map<String, com.fongmi.android.tv.bean.music.MusicPluginTest> loadPluginTests() {
        try {
            String json = Prefers.getString(K_PLUGIN_TESTS, "");
            if (json == null || json.isEmpty()) return new java.util.HashMap<>();
            Type type = TypeToken.getParameterized(java.util.Map.class, String.class,
                    com.fongmi.android.tv.bean.music.MusicPluginTest.class).getType();
            java.util.Map<String, com.fongmi.android.tv.bean.music.MusicPluginTest> map =
                    App.gson().fromJson(json, type);
            return map == null ? new java.util.HashMap<>() : map;
        } catch (Throwable ignored) {
            return new java.util.HashMap<>();
        }
    }

    public static void savePluginTests(java.util.Map<String, com.fongmi.android.tv.bean.music.MusicPluginTest> tests) {
        try {
            Prefers.put(K_PLUGIN_TESTS, App.gson().toJson(tests == null ? new java.util.HashMap<>() : tests));
        } catch (Throwable ignored) {
        }
    }

    /** 取链耗时表（平台 → srcUrl → 最近取链耗时 ms；读不到返回空表） */
    public static java.util.Map<String, java.util.Map<String, Long>> loadPluginLatency() {
        try {
            String json = Prefers.getString(K_PLUGIN_LATENCY, "");
            if (json == null || json.isEmpty()) return new java.util.HashMap<>();
            Type type = TypeToken.getParameterized(java.util.Map.class, String.class,
                    TypeToken.getParameterized(java.util.Map.class, String.class, Long.class).getType()).getType();
            java.util.Map<String, java.util.Map<String, Long>> map = App.gson().fromJson(json, type);
            return map == null ? new java.util.HashMap<>() : map;
        } catch (Throwable ignored) {
            return new java.util.HashMap<>();
        }
    }

    /**
     * 记一次取链耗时（C5：最近一次口径，直接覆盖）。
     * fromTest=true 时只作初始值：已有真实取链耗时不覆盖（检测耗时不覆盖真实耗时）。
     */
    public static void notePluginLatency(String platform, String srcUrl, long ms, boolean fromTest) {
        if (platform == null || srcUrl == null) return;
        try {
            java.util.Map<String, java.util.Map<String, Long>> all = loadPluginLatency();
            java.util.Map<String, Long> per = all.get(platform);
            if (per == null) {
                per = new java.util.HashMap<>();
                all.put(platform, per);
            }
            if (fromTest && per.containsKey(srcUrl)) return;
            per.put(srcUrl, ms);
            Prefers.put(K_PLUGIN_LATENCY, App.gson().toJson(all));
        } catch (Throwable ignored) {
        }
    }

    public static boolean isSmartSwitch() {
        return Prefers.getBoolean(K_SMART_SWITCH, false);
    }

    public static void setSmartSwitch(boolean on) {
        Prefers.put(K_SMART_SWITCH, on);
    }

    public static boolean isAutoBlockInvalid() {
        return Prefers.getBoolean(K_AUTO_BLOCK, false);
    }

    public static void setAutoBlockInvalid(boolean on) {
        Prefers.put(K_AUTO_BLOCK, on);
    }

    /** 插件排序（瓜子 LxPluginSortMode，见对照表 P7/P8）：manual 手动（默认）／platform_count 平台／speed 速度／available 可用 */
    public static final String PLUGIN_SORT_MANUAL = "manual";
    public static final String PLUGIN_SORT_PLATFORM_COUNT = "platform_count";
    public static final String PLUGIN_SORT_SPEED = "speed";
    public static final String PLUGIN_SORT_AVAILABLE = "available";
    private static final String K_PLUGIN_SORT = "music_plugin_sort";

    public static String getPluginSort() {
        String v = Prefers.getString(K_PLUGIN_SORT, PLUGIN_SORT_MANUAL);
        if (PLUGIN_SORT_MANUAL.equals(v) || PLUGIN_SORT_PLATFORM_COUNT.equals(v)
                || PLUGIN_SORT_SPEED.equals(v) || PLUGIN_SORT_AVAILABLE.equals(v)) return v;
        return PLUGIN_SORT_MANUAL;
    }

    public static void setPluginSort(String sort) {
        Prefers.put(K_PLUGIN_SORT, getPluginSortValidated(sort));
    }

    private static String getPluginSortValidated(String sort) {
        if (PLUGIN_SORT_PLATFORM_COUNT.equals(sort) || PLUGIN_SORT_SPEED.equals(sort)
                || PLUGIN_SORT_AVAILABLE.equals(sort)) return sort;
        return PLUGIN_SORT_MANUAL;
    }

    /** AMLL 歌词开关（对照表 S6，星辰单源合一行；默认开＝现行行为，关则跳过 AMLL 兜底步） */
    private static final String K_AMLL_ENABLED = "music_amll_enabled";

    public static boolean isAmllEnabled() {
        return Prefers.getBoolean(K_AMLL_ENABLED, true);
    }

    public static void setAmllEnabled(boolean on) {
        Prefers.put(K_AMLL_ENABLED, on);
    }

    // ===== 在线歌词优先级（瓜子同名设置；存法照 K_LINK_POLICY 字符串先例） =====
    // 四值：followSource 跟随来源（保收集序，不分区）／wordByWord 逐字优先／
    // lineByLine 逐行优先／amllFirst AMLL 优先。默认 wordByWord＝星辰老行为（快路径逐字
    // 优先、版本列表逐字在前）；瓜子默认值未追踪到，此处不写成瓜子事实。非法值回落默认。
    public static final String LYRIC_PRIORITY_FOLLOW_SOURCE = "followSource";
    public static final String LYRIC_PRIORITY_WORD_BY_WORD = "wordByWord";
    public static final String LYRIC_PRIORITY_LINE_BY_LINE = "lineByLine";
    public static final String LYRIC_PRIORITY_AMLL_FIRST = "amllFirst";
    private static final String K_LYRIC_PRIORITY = "music_lyric_priority";

    public static String getLyricPriority() {
        String v = Prefers.getString(K_LYRIC_PRIORITY, LYRIC_PRIORITY_WORD_BY_WORD);
        return isLyricPriorityValue(v) ? v : LYRIC_PRIORITY_WORD_BY_WORD;
    }

    public static void setLyricPriority(String priority) {
        Prefers.put(K_LYRIC_PRIORITY, isLyricPriorityValue(priority) ? priority : LYRIC_PRIORITY_WORD_BY_WORD);
    }

    private static boolean isLyricPriorityValue(String v) {
        return LYRIC_PRIORITY_FOLLOW_SOURCE.equals(v) || LYRIC_PRIORITY_WORD_BY_WORD.equals(v)
                || LYRIC_PRIORITY_LINE_BY_LINE.equals(v) || LYRIC_PRIORITY_AMLL_FIRST.equals(v);
    }

    /** 歌词时间偏移 ms（默认 0；读写都钳制 ±5000ms，钳制为星辰侧自定，非瓜子值）。
     * 第三波 L1：符号与瓜子一致（＋＝提前）；已存老值首次读取时翻转符号迁移一次（选了迁移，不弹提示）。 */
    public static int getLyricOffsetMs() {
        if (!Prefers.getBoolean(K_LYRIC_OFFSET_MIGRATED, false)) {
            int old = Prefers.getInt(K_LYRIC_OFFSET_MS, 0);
            Prefers.put(K_LYRIC_OFFSET_MIGRATED, true);
            if (old != 0) Prefers.put(K_LYRIC_OFFSET_MS, -old);
        }
        int v = Prefers.getInt(K_LYRIC_OFFSET_MS, 0);
        return Math.max(-LYRIC_OFFSET_MAX_MS, Math.min(LYRIC_OFFSET_MAX_MS, v));
    }

    public static void setLyricOffsetMs(int ms) {
        Prefers.put(K_LYRIC_OFFSET_MS, Math.max(-LYRIC_OFFSET_MAX_MS, Math.min(LYRIC_OFFSET_MAX_MS, ms)));
        // 封口：新口径值写入后即视为已迁移，任何未来面板“先 set 后 get”都不会
        // 在首次 get 时把新口径值再翻一次符号。Prefers 为外部库、树内无批量
        // Editor 接口，迁移两段写（先标志后值）的进程被杀窗口无法单事务消除，
        // 属既有极小窗口，见交回说明，不在此硬凑单事务。
        Prefers.put(K_LYRIC_OFFSET_MIGRATED, true);
    }

    /** 预加载下一首（瓜子后台播放／预加载下一首；默认开＝切歌交接先取好下一首链） */
    private static final String K_PRELOAD_NEXT = "music_preload_next";

    public static boolean isPreloadNext() {
        return Prefers.getBoolean(K_PRELOAD_NEXT, true);
    }

    public static void setPreloadNext(boolean on) {
        Prefers.put(K_PRELOAD_NEXT, on);
    }

    /** 划掉后台停止播放（瓜子同名项；默认关＝现行行为，划掉后服务照常播） */
    private static final String K_STOP_ON_TASK_REMOVED = "music_stop_on_task_removed";

    public static boolean isStopOnTaskRemoved() {
        return Prefers.getBoolean(K_STOP_ON_TASK_REMOVED, false);
    }

    public static void setStopOnTaskRemoved(boolean on) {
        Prefers.put(K_STOP_ON_TASK_REMOVED, on);
    }

    /** 过滤词曲信息（瓜子歌词过滤：作词／作曲／编曲等制作信息行不进歌词区；默认关＝现行行为） */
    private static final String K_FILTER_LYRIC_META = "music_filter_lyric_meta";

    public static boolean isFilterLyricMeta() {
        return Prefers.getBoolean(K_FILTER_LYRIC_META, false);
    }

    public static void setFilterLyricMeta(boolean on) {
        Prefers.put(K_FILTER_LYRIC_META, on);
    }

    /** 下载命名（瓜子下载命名三档）：0＝歌手 - 歌名，1＝歌名 - 歌手，2＝歌名；
     * 默认 1＝星辰现行行为（歌名 - 歌手），不改老用户已下文件的文件名口径 */
    public static final int DL_NAMING_ARTIST_TITLE = 0;
    public static final int DL_NAMING_TITLE_ARTIST = 1;
    public static final int DL_NAMING_TITLE = 2;
    private static final String K_DL_NAMING = "music_dl_naming";

    public static int getDlNaming() {
        int v = Prefers.getInt(K_DL_NAMING, DL_NAMING_TITLE_ARTIST);
        return v >= DL_NAMING_ARTIST_TITLE && v <= DL_NAMING_TITLE ? v : DL_NAMING_TITLE_ARTIST;
    }

    public static void setDlNaming(int naming) {
        Prefers.put(K_DL_NAMING, naming >= DL_NAMING_ARTIST_TITLE && naming <= DL_NAMING_TITLE
                ? naming : DL_NAMING_TITLE_ARTIST);
    }

    /** 仅 Wi-Fi 下载（瓜子仅 Wi-Fi 下载；默认关＝移动网络也可下载） */
    private static final String K_WIFI_ONLY_DOWNLOAD = "music_wifi_only_download";

    public static boolean isWifiOnlyDownload() {
        return Prefers.getBoolean(K_WIFI_ONLY_DOWNLOAD, false);
    }

    public static void setWifiOnlyDownload(boolean on) {
        Prefers.put(K_WIFI_ONLY_DOWNLOAD, on);
    }

    /** 第三波 S-A3：封面与歌词写盘方式（新任务生效，已下载文件不动）：
     * 0 旁路同名文件（默认＝现行可落地口径）、1 内嵌到音频文件、2 两者。
     * 内嵌档目前无写码位置：树内无音频标签写入库（无 jaudiotagger 之类），
     * 内嵌／两者档的内嵌部分本波未接线（见 MusicDownloadStore.downloadSidecars），
     * 旁路部分照常写；不要把内嵌档报成已实现。 */
    public static final int DL_EMBED_SIDECAR = 0;
    public static final int DL_EMBED_EMBEDDED = 1;
    public static final int DL_EMBED_BOTH = 2;
    private static final String K_DL_EMBED = "music_dl_embed";

    public static int getDlEmbed() {
        int v = Prefers.getInt(K_DL_EMBED, DL_EMBED_SIDECAR);
        return v >= DL_EMBED_SIDECAR && v <= DL_EMBED_BOTH ? v : DL_EMBED_SIDECAR;
    }

    public static void setDlEmbed(int mode) {
        Prefers.put(K_DL_EMBED, mode >= DL_EMBED_SIDECAR && mode <= DL_EMBED_BOTH ? mode : DL_EMBED_SIDECAR);
    }

    /** 第三波 S-A3：写入失败策略（新任务生效）：0 跳过不写（默认）、1 写入 URL 字段
     * （封面 URL 已存在下载记录 cover 字段里，即该字段本身；歌词无 URL 字段可写，失败即跳过）。 */
    public static final int DL_EMBED_FAIL_SKIP = 0;
    public static final int DL_EMBED_FAIL_URL = 1;
    private static final String K_DL_EMBED_FAIL = "music_dl_embed_fail";

    public static int getDlEmbedFail() {
        int v = Prefers.getInt(K_DL_EMBED_FAIL, DL_EMBED_FAIL_SKIP);
        return v == DL_EMBED_FAIL_URL ? DL_EMBED_FAIL_URL : DL_EMBED_FAIL_SKIP;
    }

    public static void setDlEmbedFail(int policy) {
        Prefers.put(K_DL_EMBED_FAIL, policy == DL_EMBED_FAIL_URL ? DL_EMBED_FAIL_URL : DL_EMBED_FAIL_SKIP);
    }

    /** 移动网络音质：-1＝跟随播放音质（默认），否则 0..3 固定档（与播放音质四档同值） */
    private static final String K_MOBILE_QUALITY = "music_mobile_quality";

    public static int getMobileQuality() {
        int v = Prefers.getInt(K_MOBILE_QUALITY, -1);
        return v >= QUALITY_LOW && v <= QUALITY_SUPER ? v : -1;
    }

    public static void setMobileQuality(int quality) {
        Prefers.put(K_MOBILE_QUALITY, quality >= QUALITY_LOW && quality <= QUALITY_SUPER ? quality : -1);
    }

    /** 第三波 S-A1：开关关掉时记住的上次固定档（开关再开时恢复；从未固定过则回落当前播放音质）。 */
    private static final String K_MOBILE_QUALITY_LAST_FIXED = "music_mobile_quality_last_fixed";

    public static int getMobileQualityLastFixed() {
        int v = Prefers.getInt(K_MOBILE_QUALITY_LAST_FIXED, -1);
        return v >= QUALITY_LOW && v <= QUALITY_SUPER ? v : getQuality();
    }

    public static void setMobileQualityLastFixed(int quality) {
        if (quality >= QUALITY_LOW && quality <= QUALITY_SUPER) {
            Prefers.put(K_MOBILE_QUALITY_LAST_FIXED, quality);
        }
    }

    // ===== L10 手动选版持久化（新键，不复用播放页内存 currentLyricKey） =====
    /** 手动选版表：歌曲 key（MusicQueue.trackKey）→ 版本 key＋来源（见 MusicRepository.lyricVersionKey 存法，
     * 值内以制表符分隔版本 key 与来源，来源可空）。JSON 字符串单键存整表，歌数不大时不另建表。 */
    private static final String K_MANUAL_LYRIC_CHOICE = "music_manual_lyric_choice";

    private static java.util.Map<String, String> manualLyricChoices() {
        try {
            String json = Prefers.getString(K_MANUAL_LYRIC_CHOICE, "");
            if (json == null || json.isEmpty()) return new java.util.HashMap<>();
            java.lang.reflect.Type type = com.google.gson.reflect.TypeToken.getParameterized(
                    java.util.Map.class, String.class, String.class).getType();
            java.util.Map<String, String> m = App.gson().fromJson(json, type);
            return m == null ? new java.util.HashMap<>() : m;
        } catch (Throwable ignored) {
            return new java.util.HashMap<>();
        }
    }

    /** 歌曲的手动选版存值（空＝未手动选过）；格式＝版本 key＋制表符＋来源（来源可空）。 */
    public static String getManualLyricChoice(String songKey) {
        if (songKey == null || songKey.isEmpty()) return "";
        String v = manualLyricChoices().get(songKey);
        return v == null ? "" : v;
    }

    /** 只拆版本 key（不含来源）：恢复匹配用版本 key，来源仅作展示／排障。 */
    public static String manualLyricChoiceKey(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        int tab = stored.indexOf('\t');
        return tab < 0 ? stored : stored.substring(0, tab);
    }

    /** 只拆来源（可空）。 */
    public static String manualLyricChoiceSource(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        int tab = stored.indexOf('\t');
        return tab < 0 ? "" : stored.substring(tab + 1);
    }

    public static void setManualLyricChoice(String songKey, String versionKey, String source) {
        if (songKey == null || songKey.isEmpty() || versionKey == null || versionKey.isEmpty()) return;
        try {
            java.util.Map<String, String> m = manualLyricChoices();
            m.put(songKey, versionKey + "\t" + (source == null ? "" : source));
            Prefers.put(K_MANUAL_LYRIC_CHOICE, App.gson().toJson(m));
        } catch (Throwable ignored) {
        }
    }

    public static void clearManualLyricChoice(String songKey) {
        if (songKey == null || songKey.isEmpty()) return;
        try {
            java.util.Map<String, String> m = manualLyricChoices();
            if (m.remove(songKey) != null) Prefers.put(K_MANUAL_LYRIC_CHOICE, App.gson().toJson(m));
        } catch (Throwable ignored) {
        }
    }

    // ===== D3 睡眠分钟档“播完再停”开关（新键，与 SLEEP_AFTER_CURRENT 独立模式互斥，见面板与服务） =====
    /** 分钟档／自定义档到点后是否等当前曲播完再停（默认关＝现行到点即停）。 */
    private static final String K_SLEEP_FINISH_CURRENT = "music_sleep_finish_current";

    public static boolean isSleepFinishCurrent() {
        return Prefers.getBoolean(K_SLEEP_FINISH_CURRENT, false);
    }

    public static void setSleepFinishCurrent(boolean on) {
        Prefers.put(K_SLEEP_FINISH_CURRENT, on);
    }
}
