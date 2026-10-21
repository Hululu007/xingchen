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
    private static final String K_RECENT = "music_recent";
    private static final int RECENT_MAX = 50;

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
        try {
            Prefers.put(K_QUEUE, App.gson().toJson(queue == null ? new ArrayList<>() : queue));
            Prefers.put(K_INDEX, index);
            Prefers.put(K_POSITION, position);
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
}
