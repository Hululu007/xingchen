package com.fongmi.android.tv.ui.activity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 音乐首页榜单卡的缓存与磁盘快照（首页收官新写，2026-10-25）。
 *
 * 契约（与旧 MusicActivity 内联实现逐项一致，不许漂移）：
 * - 进程内静态缓存：签表／选中签（名＋归属键）／曲目／榜首语气色，10 分钟新鲜期；
 *   新实例先进页先显缓存，新鲜直接停、过期先显后静默刷新。
 * - 磁盘快照 files/music_home_chart.json：冷启动先灌回静态缓存再走同一快路，
 *   冷启动不再空屏等联网（1.4.8「快照只第二次冷启动才显」的根因是旧整包反序列化
 *   被 R8 抹掉泛型签名、元素读回成 LinkedTreeMap 后恢复失败被吞——这里按字段
 *   用 TypeToken 逐项显式定型读，标量按名取，旧快照文件结构不变、直接读通）。
 * - 写盘原子化（D-22）：先写临时文件＋sync 再改名，改名失败退直写兜底；
 *   读写失败各留一行日志，不静默吞。
 * - 插件增删／启停／更新／导入后 invalidate()：静态清空＋删快照文件＋置失效位，
 *   首页 onResume 消费失效位后同实例也重拉。
 */
public final class MusicHomeChartStore {

    /** 缓存新鲜期：10 分钟内新实例不再联网 */
    public static final long TTL_MS = 10 * 60 * 1000L;

    private static final String FILE_NAME = "music_home_chart.json";
    private static final int TONE_DEFAULT = 0xFFFA243C;

    private static List<MusicChart> tabs;
    private static String selName;
    private static String selKey;
    private static List<MusicTrack> tracks;
    private static int tone = TONE_DEFAULT;
    private static String toneCover = "";
    private static boolean toneSet;
    private static long cacheAt;
    private static boolean invalidated;

    private MusicHomeChartStore() {
    }

    /** 磁盘快照形态：字段名即存盘契约，与旧快照文件逐字一致，升级直接读通。 */
    private static class Snapshot {
        public List<MusicChart> tabs;
        public String selName;
        public String selKey;
        public List<MusicTrack> tracks;
        public int tone;
        public String toneCover;
        public boolean toneSet;
        public long cacheAt;
    }

    // ================= 失效位 =================

    /** 插件侧变更后清缓存（MusicActivity.clearHomeChartCache 转调到这里）。 */
    public static void invalidate() {
        tabs = null;
        selName = null;
        selKey = null;
        tracks = null;
        tone = TONE_DEFAULT;
        toneCover = "";
        toneSet = false;
        cacheAt = 0;
        invalidated = true;
        try {
            File f = new File(App.get().getFilesDir(), FILE_NAME);
            if (f.isFile()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 首页 onResume 消费失效位：消费一次即清，返回消费前的值。 */
    public static boolean consumeInvalidated() {
        boolean was = invalidated;
        invalidated = false;
        return was;
    }

    // ================= 内存缓存 =================

    public static boolean hasCache() {
        return tabs != null && !tabs.isEmpty() && tracks != null && !tracks.isEmpty();
    }

    public static boolean isFresh() {
        return System.currentTimeMillis() - cacheAt < TTL_MS;
    }

    /** 静默刷新拉空时把时间戳清零标过期：下次新实例先显旧内容再重试静默刷新。 */
    public static void markStale() {
        cacheAt = 0;
    }

    /** 内容经实拉验证不变时只续内存有效期（不写盘，与旧口径一致）。 */
    public static void renew() {
        cacheAt = System.currentTimeMillis();
    }

    public static List<MusicChart> tabs() {
        return tabs;
    }

    public static String selName() {
        return selName;
    }

    public static String selKey() {
        return selKey;
    }

    public static List<MusicTrack> tracks() {
        return tracks;
    }

    public static int tone() {
        return tone;
    }

    public static String toneCover() {
        return toneCover;
    }

    public static boolean toneSet() {
        return toneSet;
    }

    /** 语气色落袋（只写内存）：下次新实例先用它画下划线，不先红再跳变；随下一次 save 落盘。 */
    public static void saveTone(int value, String cover) {
        tone = value;
        toneCover = cover;
        toneSet = true;
    }

    /** 成功拿到内容后写缓存：签表浅拷贝、曲目深拷贝（分片会改实例曲目封面，缓存必须脱钩），随即落盘。 */
    public static void save(List<MusicChart> newTabs, String newSelName, String newSelKey,
                            List<MusicTrack> newTracks, int newTone, String newToneCover) {
        tabs = new ArrayList<>(newTabs);
        selName = newSelName;
        selKey = newSelKey;
        tracks = copyTracks(newTracks);
        tone = newTone;
        toneCover = newToneCover;
        toneSet = true;
        cacheAt = System.currentTimeMillis();
        writeDisk();
    }

    /** 曲目深拷贝：字段集与旧实现逐项一致（id/title/artist/album/duration/cover＋platform/localPath/qualityTag/rawJson/两个时间戳）。 */
    public static List<MusicTrack> copyTracks(List<MusicTrack> src) {
        List<MusicTrack> out = new ArrayList<>();
        if (src == null) return out;
        for (MusicTrack t : src) {
            if (t == null) continue;
            MusicTrack c = new MusicTrack(t.id, t.title, t.artist, t.album, t.duration, t.cover);
            c.platform = t.platform;
            c.localPath = t.localPath;
            c.qualityTag = t.qualityTag;
            c.rawJson = t.rawJson;
            c.playlistAddedTime = t.playlistAddedTime;
            c.favoriteAddedTime = t.favoriteAddedTime;
            out.add(c);
        }
        return out;
    }

    // ================= 磁盘快照 =================

    private static void writeDisk() {
        try {
            Snapshot s = new Snapshot();
            s.tabs = tabs;
            s.selName = selName;
            s.selKey = selKey;
            s.tracks = tracks;
            s.tone = tone;
            s.toneCover = toneCover;
            s.toneSet = toneSet;
            s.cacheAt = cacheAt;
            String json = App.gson().toJson(s);
            File dir = App.get().getFilesDir();
            File f = new File(dir, FILE_NAME);
            File tmp = new File(dir, FILE_NAME + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) {
                FileOutputStream direct = new FileOutputStream(f);
                try {
                    direct.write(json.getBytes(StandardCharsets.UTF_8));
                } finally {
                    direct.close();
                }
                if (tmp.isFile()) tmp.delete();
            }
        } catch (Throwable t) {
            SpiderDebug.log("music-home-chart", "快照写盘失败: " + t);
        }
    }

    /** 冷启动把磁盘快照灌回静态缓存（静态已有签表时不读；坏文件按无缓存处理并留日志）。 */
    public static void loadDiskIfNeeded() {
        if (tabs != null && !tabs.isEmpty()) return;
        try {
            File f = new File(App.get().getFilesDir(), FILE_NAME);
            if (!f.isFile() || f.length() == 0) return;
            byte[] bytes = new byte[(int) Math.min(f.length(), 4L * 1024 * 1024)];
            FileInputStream in = new FileInputStream(f);
            int read = 0;
            try {
                // 单次 read 不保证读满：循环读到满或 EOF，半截 JSON 只会解析失败白丢快照
                int n;
                while (read < bytes.length && (n = in.read(bytes, read, bytes.length - read)) > 0) read += n;
            } finally {
                in.close();
            }
            if (read <= 0) return;
            Gson gson = App.gson();
            JsonObject obj = JsonParser.parseString(
                    new String(bytes, 0, read, StandardCharsets.UTF_8)).getAsJsonObject();
            Type chartListType = new TypeToken<List<MusicChart>>() {
            }.getType();
            Type trackListType = new TypeToken<List<MusicTrack>>() {
            }.getType();
            List<MusicChart> readTabs = gson.fromJson(obj.get("tabs"), chartListType);
            List<MusicTrack> readTracks = gson.fromJson(obj.get("tracks"), trackListType);
            if (readTabs == null || readTabs.isEmpty() || readTracks == null || readTracks.isEmpty()) return;
            tabs = readTabs;
            selName = obj.has("selName") && !obj.get("selName").isJsonNull() ? obj.get("selName").getAsString() : null;
            selKey = obj.has("selKey") && !obj.get("selKey").isJsonNull() ? obj.get("selKey").getAsString() : null;
            tracks = readTracks;
            tone = obj.has("tone") ? obj.get("tone").getAsInt() : 0;
            toneCover = obj.has("toneCover") && !obj.get("toneCover").isJsonNull() ? obj.get("toneCover").getAsString() : "";
            toneSet = obj.has("toneSet") && obj.get("toneSet").getAsBoolean();
            cacheAt = obj.has("cacheAt") ? obj.get("cacheAt").getAsLong() : 0;
        } catch (Throwable t) {
            SpiderDebug.log("music-home-chart", "快照读回失败（按无缓存处理）: " + t);
        }
    }
}
