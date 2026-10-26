package com.fongmi.android.tv.bean;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.web.HomeWebController;
import com.fongmi.android.tv.web.ext.WebHomeExtensionRegistry;
import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.ToNumberPolicy;
import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Backup {

    private static final String TAG = "Backup";

    public static final String PREF_WEB_HOME_EXTENSION = "web_home_extension";
    public static final String PREF_WEB_HOME_EXTENSION_SOURCES = "web_home_extension_user_sources";

    private static final Set<String> APP_PREFS = Set.of("doh", "ua", "wall", "wall_type", "reset", "site_block_keys", "search_column", "sync_mode", "sync_paths", "incognito", "drive_check", "drive_check_cache", "compact_episode_title", "web_home_fullscreen", "viewing_record_sync_enabled", "viewing_record_sync_local_write", "playback_remote_sync_config", "playback_webhook_config", "playback_webhook_privacy_accepted", "shell_proxy", "shell_proxy_rules", "shell_proxy_url", "shell_proxy_hosts", "update", "update_source", "update_github_proxy", "update_github_proxy_url", "update_github_proxy_mode", "update_oci_mirror", "update_oci_mirror_url", "adblock", "zhuyin", "theme_color", "wall_color", "crash", "render", "pad_live_mode", "size", "scale", "buffer", "buffer_bytes", "back_buffer", "play_cache", "preload", "preload_threads", "preload_size", "preload_time", "player_auto_change", "background", "music_background", "speed", "play_speed", "caption", "tunnel", "exo_4k_compat", "playback_bluray_menu", "playback_performance_profile", "playback_performance_initialized", "perf_codec_async_queueing", "perf_dynamic_scheduling", "perf_video_duration_progress", "perf_late_drop_input", "perf_track_limit", "perf_adaptive_downgrade", "perf_load_only_selected_tracks", "perf_surface_fixed_size", "perf_decoder_fallback", "perf_soft_video_tune", "perf_high_buffer", "perf_bandwidth_meter", "perf_exo_network_protection_mode", "player_button_order", "player_button_hidden", "audio_prefer", "video_prefer", "prefer_aac", "subtitle_text_size", "subtitle_position", "player_osd_title", "player_osd_resolution", "player_osd_time", "player_osd_progress", "player_osd_traffic", "player_osd_mini", "player_osd_diagnostics", "boot_live", "across", "change", "invert", "scale_live", "live_epg_url", "live_epg_history", "player_auto_play", "language", "ui_scale", "player", "perf_dv7_handling", "episode_column", "mpv_render", "immersive_audio_mode", "github_proxy", "github_proxy_enabled", "site_health_sort", "site_health_dialog_sort", "site_column", "adblock_keywords", "player_brightness", "live_list_style");

    @SerializedName("site")
    private List<Site> site;
    @SerializedName("live")
    private List<Live> live;
    @SerializedName("keep")
    private List<Keep> keep;
    @SerializedName("config")
    private List<Config> config;
    @SerializedName("history")
    private List<History> history;
    @SerializedName("track")
    private List<Track> track;
    @SerializedName("device")
    private List<Device> device;
    @SerializedName("musicFavorite")
    private List<MusicFavorite> musicFavorite;
    @SerializedName("musicPlaylist")
    private List<MusicPlaylist> musicPlaylist;
    @SerializedName("musicPlaylistItem")
    private List<MusicPlaylistItem> musicPlaylistItem;
    @SerializedName("musicDownload")
    private List<MusicDownload> musicDownload;
    @SerializedName("playbackDeleteTombstone")
    private List<PlaybackDeleteTombstone> playbackDeleteTombstone;
    @SerializedName("prefers")
    private Map<String, ?> prefers;

    public static Backup create() {
        Backup backup = new Backup();
        backup.setPrefers(Prefers.getPrefers().getAll());
        backup.setSite(AppDatabase.get().getSiteDao().findAll());
        backup.setLive(AppDatabase.get().getLiveDao().findAll());
        backup.setKeep(AppDatabase.get().getKeepDao().findAll());
        backup.setConfig(AppDatabase.get().getConfigDao().findAll());
        backup.setHistory(AppDatabase.get().getHistoryDao().findAll());
        backup.setTrack(AppDatabase.get().getTrackDao().findAll());
        backup.setDevice(AppDatabase.get().getDeviceDao().findAll());
        backup.setMusicTables();
        return backup;
    }

    // 音乐四表＋播放删除墓碑（FIX-H13）：1.4.14 已堵住「恢复不含这五表却清掉它们」，
    // 这里把导出补齐，五表随全量备份一起走，恢复端按「备份含哪表才动哪表」回填。
    private void setMusicTables() {
        setMusicFavorite(AppDatabase.get().getMusicFavoriteDao().findAll());
        setMusicPlaylist(AppDatabase.get().getMusicPlaylistDao().findAll());
        setMusicPlaylistItem(AppDatabase.get().getMusicPlaylistItemDao().findAll());
        setMusicDownload(AppDatabase.get().getMusicDownloadDao().findAll());
        setPlaybackDeleteTombstone(AppDatabase.get().getPlaybackDeleteTombstoneDao().findAll());
    }

    public static Backup create(SyncOptions options) {
        Backup backup = new Backup();
        if (options.isConfig()) {
            backup.setSite(AppDatabase.get().getSiteDao().findAll());
            backup.setLive(AppDatabase.get().getLiveDao().findAll());
            backup.setConfig(AppDatabase.get().getConfigDao().findAll());
        }
        if (options.isKeep()) {
            backup.setKeep(AppDatabase.get().getKeepDao().findAll());
            // SyncOptions 没有音乐分项：音乐收藏/歌单/下载与墓碑归收藏族，跟 isKeep 同行
            backup.setMusicTables();
        }
        if (options.isHistory()) backup.setHistory(AppDatabase.get().getHistoryDao().findAll());
        backup.setPrefers(filter(Prefers.getPrefers().getAll(), options));
        return backup;
    }

    // 解析失败必须与「本来就是空备份」区分：失败返回 null 并留痕，
    // 调用方据此中止恢复——旧实现吞成空 Backup，远程同步路拿它 force 恢复会先清
    // 站点/直播/配置三表再回填空数据、还回执成功，数据丢了无人知（全应用扫描 O5）。
    public static Backup objectFrom(String json) {
        try {
            Gson gson = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LAZILY_PARSED_NUMBER).create();
            return gson.fromJson(json, Backup.class);
        } catch (Exception e) {
            Log.w(TAG, "objectFrom parse failed", e);
            return null;
        }
    }

    public void restore() {
        restore(true);
    }

    public void restore(boolean preserveMissingWebHomePrefs) {
        // 只清备份里实际包含的表：七表是 create 恒导出的，无条件先清后填；
        // 音乐四表＋墓碑（FIX-H13 起才进备份）只在备份真的含该字段时才动——
        // 旧备份没有这五个字段，恢复时现有音乐数据原样保留，不许清。
        AppDatabase.get().getSiteDao().delete();
        AppDatabase.get().getLiveDao().delete();
        AppDatabase.get().getKeepDao().deleteAll();
        AppDatabase.get().getConfigDao().delete();
        AppDatabase.get().getHistoryDao().delete();
        AppDatabase.get().getTrackDao().deleteAll();
        AppDatabase.get().getDeviceDao().delete();
        AppDatabase.get().getSiteDao().insertOrUpdate(getSite());
        AppDatabase.get().getLiveDao().insertOrUpdate(getLive());
        AppDatabase.get().getKeepDao().insertOrUpdate(getKeep());
        AppDatabase.get().getConfigDao().insertOrUpdate(getConfig());
        AppDatabase.get().getHistoryDao().insertOrUpdate(getHistory());
        AppDatabase.get().getTrackDao().insertOrUpdate(getTrack());
        AppDatabase.get().getDeviceDao().insertOrUpdate(getDevice());
        if (musicFavorite != null) {
            AppDatabase.get().getMusicFavoriteDao().deleteAll();
            AppDatabase.get().getMusicFavoriteDao().insertOrUpdate(musicFavorite);
        }
        if (musicPlaylist != null) {
            AppDatabase.get().getMusicPlaylistDao().deleteAll();
            AppDatabase.get().getMusicPlaylistDao().insertOrUpdate(musicPlaylist);
        }
        if (musicPlaylistItem != null) {
            AppDatabase.get().getMusicPlaylistItemDao().deleteAll();
            AppDatabase.get().getMusicPlaylistItemDao().insertOrUpdate(musicPlaylistItem);
        }
        if (musicDownload != null) {
            AppDatabase.get().getMusicDownloadDao().deleteAll();
            AppDatabase.get().getMusicDownloadDao().insertOrUpdate(musicDownload);
        }
        if (playbackDeleteTombstone != null) {
            AppDatabase.get().getPlaybackDeleteTombstoneDao().deleteAll();
            AppDatabase.get().getPlaybackDeleteTombstoneDao().insertOrUpdate(playbackDeleteTombstone);
        }
        restorePrefers(getPrefers(), true, preserveMissingWebHomePrefs);
    }

    public void restore(SyncOptions options, boolean force) {
        Map<Integer, Integer> cids = new HashMap<>();
        if (options.isConfig()) {
            if (force) {
                AppDatabase.get().getSiteDao().delete();
                AppDatabase.get().getLiveDao().delete();
                AppDatabase.get().getConfigDao().delete();
            }
            AppDatabase.get().getSiteDao().insertOrUpdate(getSite());
            AppDatabase.get().getLiveDao().insertOrUpdate(getLive());
            cids.putAll(restoreConfig());
        }
        if (options.isKeep()) {
            if (force) AppDatabase.get().getKeepDao().deleteAll();
            for (Keep item : getKeep()) {
                if (!cids.containsKey(item.getCid())) continue;
                int newCid = cids.get(item.getCid());
                if (newCid == item.getCid()) continue;
                // 复合主键 (key, cid, type) 下改 cid 是换行不是改行（照 Keep.sync 口径）：
                // cid 真变了才先按 (key, 旧 cid, type) 删旧行再写新行，否则旧行残留成孤儿。
                item.delete();
                item.setCid(newCid);
            }
            AppDatabase.get().getKeepDao().insertOrUpdate(getKeep());
            // 音乐四表＋墓碑随收藏族回填：同步路不清表（force 只管 Keep 本表既有口径），
            // 备份不含的字段（旧备份）跳过。音乐三表不能按源设备自增 id 直接 upsert，
            // 走 restoreMusicSync 的身份重映射；墓碑是字符串键 REPLACE，仍直填。
            restoreMusicSync();
            if (playbackDeleteTombstone != null) AppDatabase.get().getPlaybackDeleteTombstoneDao().insertOrUpdate(playbackDeleteTombstone);
        }
        if (options.isHistory()) {
            if (force) AppDatabase.get().getHistoryDao().delete();
            for (History item : getHistory()) if (cids.containsKey(item.getCid())) item.setCid(cids.get(item.getCid()));
            AppDatabase.get().getHistoryDao().insertOrUpdate(getHistory());
        }
        Map<String, ?> prefers = filter(getPrefers(), options);
        if (options.isMpvConfig()) clearMpvConfigPreferences();
        restorePrefers(prefers, false, false);
        if (options.isConfig() || options.isSpider() || options.isWebHome() || options.isLoginState()) BaseLoader.get().clear("backup-restore");
        if (options.isConfig() || options.isSpider() || options.isWebHome() || options.isLoginState()) SiteApi.clearHomeCache();
        if (options.isConfig() || options.isSpider() || options.isWebHome() || options.isLoginState()) reloadConfig();
        if (options.isWebHome()) refreshWebHomeExtensions();
        if (options.isKeep()) RefreshEvent.keep();
        if (options.isHistory()) RefreshEvent.history();
        RefreshEvent.home();
    }

    private void reloadConfig() {
        VodConfig.get().clear().init().load(new Callback());
        LiveConfig.get().clear().init().load();
        WallConfig.get().init().load();
        ConfigEvent.common();
    }

    private Map<Integer, Integer> restoreConfig() {
        Map<Integer, Integer> cids = new HashMap<>();
        for (Config item : getConfig()) {
            int source = item.getId();
            Config current = AppDatabase.get().getConfigDao().find(item.getUrl(), item.getType());
            item.setId(current == null ? 0 : current.getId());
            long id = AppDatabase.get().getConfigDao().insert(item);
            if (id == -1) AppDatabase.get().getConfigDao().update(item);
            else item.setId(Math.toIntExact(id));
            if (source > 0) cids.put(source, item.getId());
        }
        return cids;
    }

    // 同步恢复音乐三表（W5-01）：源设备的自增 id 在本地没有意义——歌单 id 撞号会整行
    // 覆写本地歌单，收藏/下载的 unique trackId 撞号会静默丢行或抛约束异常打断整条同步。
    // 身份口径照 restoreConfig 的 cids 重映射：歌单源 id 不进本地库、置零插入让本地自增
    // 分配并建源 id→本地 id 映射，条目的 playlistId 走映射改写；收藏/下载按自然键
    // trackId 找本地行，命中就保留本地 id 原地更新、没有才置零插入。全程逐行 try 兜住
    // 并记日志，单行失败不许打断其后的 history/prefers。
    /** 收藏行插件戳比对：行 plugin 列有值认列、没值（43 版前存量行）认 rawJson 戳 */
    private static boolean samePluginOf(MusicFavorite row, String plugin) {
        String rowPlugin = row.getPlugin();
        if (rowPlugin == null) rowPlugin = com.fongmi.android.tv.music.MusicIdentity.pluginOf(row.getRawJson());
        return (rowPlugin == null ? "" : rowPlugin).equals(plugin == null ? "" : plugin);
    }

    private void restoreMusicSync() {
        Map<Long, Long> playlistIds = new HashMap<>();
        if (musicPlaylist != null) {
            for (MusicPlaylist item : getMusicPlaylist()) {
                try {
                    long sourceId = item.getId();
                    item.setId(0);
                    long localId = AppDatabase.get().getMusicPlaylistDao().insert(item);
                    if (localId == -1) {
                        Log.w(TAG, "restoreMusicSync playlist insert ignored, sourceId=" + sourceId);
                        continue;
                    }
                    item.setId(localId);
                    if (sourceId > 0) playlistIds.put(sourceId, localId);
                } catch (Exception e) {
                    Log.w(TAG, "restoreMusicSync playlist failed", e);
                }
            }
        }
        if (musicPlaylistItem != null) {
            for (MusicPlaylistItem item : getMusicPlaylistItem()) {
                try {
                    Long localPlaylistId = playlistIds.get(item.getPlaylistId());
                    // 源歌单不在本次备份或未插入成功：挂到同号本地歌单就是串歌单，跳过
                    if (localPlaylistId == null) continue;
                    item.setPlaylistId(localPlaylistId);
                    // 复合身份找行：唯一键已扩成 (playlistId, trackId, platform)，裸 (playlistId, trackId)
                    // 找行会把同 id 异来源的那行当成本行原地覆盖；复合找不到再裸找、platform 对不上不认
                    MusicPlaylistItem current = AppDatabase.get().getMusicPlaylistItemDao().find(localPlaylistId, item.getTrackId(), item.getPlatform());
                    if (current == null) {
                        MusicPlaylistItem any = AppDatabase.get().getMusicPlaylistItemDao().find(localPlaylistId, item.getTrackId());
                        if (any != null && (any.getPlatform() == null ? "" : any.getPlatform()).equals(item.getPlatform() == null ? "" : item.getPlatform())) current = any;
                    }
                    if (current == null) {
                        item.setId(0);
                        AppDatabase.get().getMusicPlaylistItemDao().insert(item);
                    } else {
                        // 同 (playlistId, trackId) 已有行：保留本地 id 原地更新其余字段
                        // （标题/歌手/排序跟源走），不重复插、不走会 ABORT 的批量 update。
                        item.setId(current.getId());
                        AppDatabase.get().getMusicPlaylistItemDao().update(item);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "restoreMusicSync playlistItem failed", e);
                }
            }
        }
        if (musicFavorite != null) {
            for (MusicFavorite item : getMusicFavorite()) {
                try {
                    // 三段身份找行（扫描 M3，同歌单条目口径）：先 (trackId, platform, plugin)
                    // 精确找，插件段取备份行自带值、旧备份没这列时从 rawJson 戳推导并回填；
                    // 再退复合/裸 id，但 platform 与插件戳都对上才认，防覆盖同 id 异插件的收藏
                    String plugin = item.getPlugin();
                    if (plugin == null) plugin = com.fongmi.android.tv.music.MusicIdentity.pluginOf(item.getRawJson());
                    item.setPlugin(plugin == null ? "" : plugin);
                    MusicFavorite current = item.getPlatform() == null ? null
                            : AppDatabase.get().getMusicFavoriteDao().find(item.getTrackId(), item.getPlatform(), item.getPlugin());
                    if (current == null) {
                        MusicFavorite byPlatform = AppDatabase.get().getMusicFavoriteDao().find(item.getTrackId(), item.getPlatform());
                        if (byPlatform != null && samePluginOf(byPlatform, item.getPlugin())) current = byPlatform;
                    }
                    if (current == null) {
                        MusicFavorite any = AppDatabase.get().getMusicFavoriteDao().find(item.getTrackId());
                        if (any != null && (any.getPlatform() == null ? "" : any.getPlatform()).equals(item.getPlatform() == null ? "" : item.getPlatform()) && samePluginOf(any, item.getPlugin())) current = any;
                    }
                    if (current == null) {
                        item.setId(0);
                        AppDatabase.get().getMusicFavoriteDao().insert(item);
                    } else {
                        item.setId(current.getId());
                        AppDatabase.get().getMusicFavoriteDao().update(item);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "restoreMusicSync favorite failed", e);
                }
            }
        }
        if (musicDownload != null) {
            for (MusicDownload item : getMusicDownload()) {
                try {
                    MusicDownload current = AppDatabase.get().getMusicDownloadDao().findByTrackId(item.getTrackId());
                    if (current == null) {
                        item.setId(0);
                        AppDatabase.get().getMusicDownloadDao().insert(item);
                    } else {
                        item.setId(current.getId());
                        AppDatabase.get().getMusicDownloadDao().update(item);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "restoreMusicSync download failed", e);
                }
            }
        }
    }

    private static Map<String, ?> filter(Map<String, ?> source, SyncOptions options) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            if (entry.getValue() != null && include(entry.getKey(), options)) result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    static boolean include(String key, SyncOptions options) {
        if (key.startsWith("playback_experiment_")) return false;
        if (key.startsWith("playback_performance_profile_merge_")) {
            return false;
        }
        if ("playback_performance_profile_auto_light_v1".equals(key)) {
            return false;
        }
        if ("perf_exo_single_rate_rescue_enabled_v1".equals(key)) {
            return false;
        }
        if (key.startsWith("remote_trust_")) return false;
        if (isWebHomeExtensionPref(key)) return options.isWebHome();
        if (key.startsWith("cache_")) return options.isWebHome() || options.isSpider();
        if (key.startsWith("config_")) return options.isConfig();
        if ("keyword".equals(key) || "hot".equals(key) || key.startsWith("hot_")) return options.isSearch();
        if ("git_cloud_accounts".equals(key)) return options.isSpider() || options.isSettings() || options.isLoginState();
        if (key.startsWith("login_state_")) return options.isLoginState();
        if (key.startsWith("mpv_config_")) return options.isMpvConfig();
        if (isAppPref(key)) return options.isSettings();
        return options.isSpider();
    }

    static boolean isWebHomeExtensionPref(String key) {
        return PREF_WEB_HOME_EXTENSION.equals(key) || key.startsWith("web_home_extension_") || key.startsWith("web_home_ext_enabled_");
    }

    private static boolean isAppPref(String key) {
        // 前缀族补收（逻辑审查实证：这些键此前一概落进「蜘蛛」兜底，只勾设置不同步时全丢）：
        // music_ 音乐设置与插件状态、subtitle_ 字幕细项、csp_warmup 预热、audio_background
        // 后台音频、desktop_lyrics 桌面歌词、karaoke_ 卡拉OK、lyrics_ 歌词细项
        return APP_PREFS.contains(key) || key.startsWith("danmaku_") || key.startsWith("playback_performance_") || key.startsWith("perf_exo_") || key.startsWith("perf_mpv_") || key.startsWith("perf_kernel_") || key.startsWith("music_") || key.startsWith("subtitle_") || key.startsWith("csp_warmup") || key.startsWith("audio_background") || key.startsWith("desktop_lyrics") || key.startsWith("karaoke_") || key.startsWith("lyrics_");
    }

    private static void restorePrefers(Map<String, ?> values, boolean clear, boolean preserveMissingWebHomePrefs) {
        Map<String, Object> preserved = new HashMap<>();
        if (clear && preserveMissingWebHomePrefs) {
            for (Map.Entry<String, ?> entry : Prefers.getPrefers().getAll().entrySet()) {
                if (isWebHomeExtensionPref(entry.getKey()) && !values.containsKey(entry.getKey())) preserved.put(entry.getKey(), entry.getValue());
            }
        }
        SharedPreferences.Editor editor = Prefers.getPrefers().edit();
        if (clear) editor.clear();
        if (containsPlaybackPerformanceProfile(values)) {
            editor.remove("playback_performance_profile_auto_light_v1");
        }
        putPrefers(editor, preserved);
        putPrefers(editor, values);
        editor.commit();
    }

    private static void clearMpvConfigPreferences() {
        SharedPreferences preferences = Prefers.getPrefers();
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith("mpv_config_")) {
                editor.remove(key);
                changed = true;
            }
        }
        if (changed) editor.commit();
    }

    private static boolean containsPlaybackPerformanceProfile(
            Map<String, ?> values) {
        return values.containsKey("playback_performance_profile")
                || values.containsKey("perf_exo_profile")
                || values.containsKey("perf_mpv_profile");
    }

    private static void putPrefers(SharedPreferences.Editor editor, Map<String, ?> values) {
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Set) {
                // 字符串集合（性能覆盖集、登录态集在用）：此前无此分支，恢复时被静默跳过丢值
                Set<String> set = new java.util.HashSet<>();
                for (Object item : (Set<?>) value) if (item instanceof String) set.add((String) item);
                editor.putStringSet(entry.getKey(), set);
            } else if (value instanceof java.util.Collection) {
                // 备份 JSON 往返后 Set 变数组（Gson 读回为 List）：元素全是字符串才按字符串集合回填
                java.util.Collection<?> collection = (java.util.Collection<?>) value;
                Set<String> set = new java.util.HashSet<>();
                boolean strings = true;
                for (Object item : collection) {
                    if (item instanceof String) set.add((String) item);
                    else strings = false;
                }
                if (strings) editor.putStringSet(entry.getKey(), set);
            } else if (value instanceof Number) {
                Number number = (Number) value;
                if (number.toString().contains(".")) editor.putFloat(entry.getKey(), number.floatValue());
                else {
                    // 长整数走 intValue 会截断改值：超出 int 范围的按 long 回填
                    long longValue = number.longValue();
                    if (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) editor.putInt(entry.getKey(), (int) longValue);
                    else editor.putLong(entry.getKey(), longValue);
                }
            }
        }
    }

    public static void refreshWebHomeExtensions() {
        WebHomeExtensionRegistry.get().clear();
        HomeWebController.requestExtensionReload();
    }

    public int getWebHomeExtensionPreferenceCount() {
        int count = 0;
        for (String key : getPrefers().keySet()) if (isWebHomeExtensionPref(key)) count++;
        return count;
    }

    public int getWebHomeExtensionSourceCount() {
        Object value = getPrefers().get(PREF_WEB_HOME_EXTENSION_SOURCES);
        if (!(value instanceof String) || ((String) value).isEmpty()) return 0;
        try {
            JsonElement element = new Gson().fromJson((String) value, JsonElement.class);
            return element != null && element.isJsonArray() ? element.getAsJsonArray().size() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    public List<Site> getSite() {
        return site == null ? Collections.emptyList() : site;
    }

    public void setSite(List<Site> site) {
        this.site = site;
    }

    public List<Live> getLive() {
        return live == null ? Collections.emptyList() : live;
    }

    public void setLive(List<Live> live) {
        this.live = live;
    }

    public List<Keep> getKeep() {
        return keep == null ? Collections.emptyList() : keep;
    }

    public void setKeep(List<Keep> keep) {
        this.keep = keep;
    }

    public List<Config> getConfig() {
        return config == null ? Collections.emptyList() : config;
    }

    public void setConfig(List<Config> config) {
        this.config = config;
    }

    public List<History> getHistory() {
        return history == null ? Collections.emptyList() : history;
    }

    public void setHistory(List<History> history) {
        this.history = history;
    }

    public List<Track> getTrack() {
        return track == null ? Collections.emptyList() : track;
    }

    public void setTrack(List<Track> track) {
        this.track = track;
    }

    public List<Device> getDevice() {
        return device == null ? Collections.emptyList() : device;
    }

    public void setDevice(List<Device> device) {
        this.device = device;
    }

    public List<MusicFavorite> getMusicFavorite() {
        return musicFavorite == null ? Collections.emptyList() : musicFavorite;
    }

    public void setMusicFavorite(List<MusicFavorite> musicFavorite) {
        this.musicFavorite = musicFavorite;
    }

    public List<MusicPlaylist> getMusicPlaylist() {
        return musicPlaylist == null ? Collections.emptyList() : musicPlaylist;
    }

    public void setMusicPlaylist(List<MusicPlaylist> musicPlaylist) {
        this.musicPlaylist = musicPlaylist;
    }

    public List<MusicPlaylistItem> getMusicPlaylistItem() {
        return musicPlaylistItem == null ? Collections.emptyList() : musicPlaylistItem;
    }

    public void setMusicPlaylistItem(List<MusicPlaylistItem> musicPlaylistItem) {
        this.musicPlaylistItem = musicPlaylistItem;
    }

    public List<MusicDownload> getMusicDownload() {
        return musicDownload == null ? Collections.emptyList() : musicDownload;
    }

    public void setMusicDownload(List<MusicDownload> musicDownload) {
        this.musicDownload = musicDownload;
    }

    public List<PlaybackDeleteTombstone> getPlaybackDeleteTombstone() {
        return playbackDeleteTombstone == null ? Collections.emptyList() : playbackDeleteTombstone;
    }

    public void setPlaybackDeleteTombstone(List<PlaybackDeleteTombstone> playbackDeleteTombstone) {
        this.playbackDeleteTombstone = playbackDeleteTombstone;
    }

    public Map<String, ?> getPrefers() {
        return prefers == null ? new HashMap<>() : prefers;
    }

    public void setPrefers(Map<String, ?> prefers) {
        this.prefers = prefers;
    }

    @NonNull
    @Override
    public String toString() {
        return App.gson().toJson(this);
    }
}
