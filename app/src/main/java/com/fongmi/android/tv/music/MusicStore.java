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

    /** 列表排序：0 默认 1 标题 2 歌手 3 时长 */
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
            Prefers.put(K_QUEUE, App.gson().toJson(queue == null ? new ArrayList<>() : queue));
            Prefers.put(K_INDEX, index);
            Prefers.put(K_POSITION, position);
            Prefers.put(K_QUEUE_SOURCE, source == null ? "" : source);
            Prefers.put(K_QUEUE_ROUTE, route == null ? "" : route);
            Prefers.put(K_QUEUE_ROUTE_PARAMS, routeParams == null ? "" : App.gson().toJson(routeParams));
        } catch (Throwable ignored) {
        }
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
        Prefers.put(K_QUEUE, "");
        Prefers.put(K_INDEX, 0);
        Prefers.put(K_POSITION, 0);
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

    /** 播放页主题 id（全新默认封面卡片 cover_immersive，1.4.3 改令；已存的用户值一律不动） */
    public static String getPlayerTheme() {
        String theme = Prefers.getString(K_PLAYER_THEME, THEME_COVER_IMMERSIVE);
        return theme == null || theme.isEmpty() ? THEME_COVER_IMMERSIVE : theme;
    }

    public static void setPlayerTheme(String theme) {
        // 空值回退与全新默认同落点（cover_immersive）；正常写入的用户值原样保存
        Prefers.put(K_PLAYER_THEME, theme == null || theme.isEmpty() ? THEME_COVER_IMMERSIVE : theme);
    }

    /** 流光 CD 表面：0 浅 1 深 2 自动（默认自动） */
    public static int getCdSurface() {
        return Prefers.getInt(K_CD_SURFACE, CD_SURFACE_AUTO);
    }

    public static void setCdSurface(int surface) {
        Prefers.put(K_CD_SURFACE, Math.max(CD_SURFACE_LIGHT, Math.min(CD_SURFACE_AUTO, surface)));
    }

    /** 全屏歌词字号 sp（默认 24，钳 16–40） */
    public static float getLyricFontSize() {
        float size = Prefers.getFloat(K_LYRIC_FONT_SIZE, 24f);
        return Math.max(16f, Math.min(40f, size));
    }

    public static void setLyricFontSize(float size) {
        Prefers.put(K_LYRIC_FONT_SIZE, Math.max(16f, Math.min(40f, size)));
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

    /** 非当前行模糊：0 关 1 轻 2 中（默认轻） */
    public static int getLyricBlur() {
        return Prefers.getInt(K_LYRIC_BLUR, LYRIC_BLUR_MEDIUM);
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

    /** 歌词时间偏移 ms（默认 0；读写都钳制 ±5000ms，钳制为星辰侧自定，非瓜子值） */
    public static int getLyricOffsetMs() {
        int v = Prefers.getInt(K_LYRIC_OFFSET_MS, 0);
        return Math.max(-LYRIC_OFFSET_MAX_MS, Math.min(LYRIC_OFFSET_MAX_MS, v));
    }

    public static void setLyricOffsetMs(int ms) {
        Prefers.put(K_LYRIC_OFFSET_MS, Math.max(-LYRIC_OFFSET_MAX_MS, Math.min(LYRIC_OFFSET_MAX_MS, ms)));
    }
}
