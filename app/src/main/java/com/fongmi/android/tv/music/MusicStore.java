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

    // ===== 音乐设置页四项（2026-10-20） =====
    private static final String K_DL_QUALITY = "music_dl_quality";
    private static final String K_AUTO_SKIP = "music_auto_skip_on_error";
    private static final String K_MOBILE_PLAY = "music_mobile_play";
    private static final String K_LYRIC_FALLBACK = "music_lyric_fallback";
    private static final String K_SPEED = "music_speed";

    /** 下载默认音质（与播放默认音质相互独立，各管各的链） */
    public static int getDownloadQuality() {
        return Prefers.getInt(K_DL_QUALITY, QUALITY_STANDARD);
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

    public static String qualityName(int q) {
        switch (q) {
            case QUALITY_LOW:
                return "流畅";
            case QUALITY_HIGH:
                return "高清";
            case QUALITY_SUPER:
                return "无损";
            default:
                return "标准";
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
}
