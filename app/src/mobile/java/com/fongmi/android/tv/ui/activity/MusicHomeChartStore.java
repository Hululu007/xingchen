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
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 音乐首页榜单卡的跨实例缓存与磁盘快照（从零重写，状态外置，MusicActivity 只消费）。
 *
 * 存盘口径（与旧实现契约一致，存量快照不丢）：
 * - 文件名 music_home_chart.json，字段 tabs/selName/selKey/tracks/tone/toneCover/toneSet/cacheAt；
 * - 读法必须逐项定型：整包 fromJson 到带 List 字段的类会被 R8 抹掉泛型签名，
 *   元素读回成 LinkedTreeMap、恢复时强转当场崩——历史上第一次冷启动无快照就是这个根因；
 *   这里 tabs/tracks 各走 TypeToken 定型、标量按名逐个取；
 * - 写盘原子：临时文件写完 sync 再改名，改名失败退直写；读写失败各留 SpiderDebug 日志（tag music-home-chart）；
 * - TTL 10 分钟：新鲜直接用、过期先显后静默刷新（刷新策略在 MusicActivity）。
 */
public final class MusicHomeChartStore {

    private static final String TAG = "music-home-chart";
    private static final String FILE_NAME = "music_home_chart.json";
    private static final long TTL_MS = 10 * 60 * 1000L;

    private static final Type CHART_LIST = new TypeToken<List<MusicChart>>() {}.getType();
    private static final Type TRACK_LIST = new TypeToken<List<MusicTrack>>() {}.getType();

    /** 一份快照（字段与存盘逐名对应）。 */
    public static final class Snapshot {
        public List<MusicChart> tabs = new ArrayList<>();
        public List<MusicTrack> tracks = new ArrayList<>();
        public String selName = "";
        public String selKey = "";
        public int tone;
        public String toneCover = "";
        public boolean toneSet;
        public long cacheAt;
    }

    private static Snapshot sSnapshot;
    private static boolean sDiskLoaded;
    private static volatile boolean sInvalidated;

    private MusicHomeChartStore() {
    }

    // ================= 内存态 =================

    public static synchronized Snapshot snapshot() {
        return sSnapshot;
    }

    public static synchronized boolean hasCache() {
        return sSnapshot != null && sSnapshot.tabs != null && !sSnapshot.tabs.isEmpty();
    }

    public static synchronized boolean isFresh() {
        return hasCache() && System.currentTimeMillis() - sSnapshot.cacheAt < TTL_MS;
    }

    /** 失效位：源增删等外部变化置位，首页 onResume 消费后清。 */
    public static void invalidate() {
        sInvalidated = true;
    }

    public static synchronized boolean consumeInvalidated() {
        boolean v = sInvalidated;
        sInvalidated = false;
        return v;
    }

    /** 首页榜单卡的静态清缓存入口（其它页历史调用点契约：清内存＋删快照文件）。 */
    public static synchronized void clear() {
        sSnapshot = null;
        sDiskLoaded = true;
        try {
            File f = file();
            if (f != null && f.exists()) f.delete();
        } catch (Throwable t) {
            SpiderDebug.log(TAG, "clear failed: " + t.getMessage());
        }
    }

    // ================= 落盘 / 读盘 =================

    /** 保存一份新快照（深拷贝曲目，防调用方后续改列表污染缓存）并原子写盘。 */
    public static synchronized void save(List<MusicChart> tabs, String selName, String selKey,
                                         List<MusicTrack> tracks, int tone, String toneCover, boolean toneSet) {
        Snapshot s = new Snapshot();
        s.tabs = tabs == null ? new ArrayList<>() : new ArrayList<>(tabs);
        s.selName = selName == null ? "" : selName;
        s.selKey = selKey == null ? "" : selKey;
        s.tracks = copyTracks(tracks);
        s.tone = tone;
        s.toneCover = toneCover == null ? "" : toneCover;
        s.toneSet = toneSet;
        s.cacheAt = System.currentTimeMillis();
        sSnapshot = s;
        writeDisk(s);
    }

    /** 冷启动灌盘：只读一次；读回逐字段定型还原，元素必须是真 MusicChart/MusicTrack 类型。 */
    public static synchronized void loadDiskIfNeeded() {
        if (sDiskLoaded) return;
        sDiskLoaded = true;
        File f = file();
        if (f == null || !f.exists()) return;
        try {
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (json.trim().isEmpty()) return;
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            Gson gson = new Gson();
            Snapshot s = new Snapshot();
            if (obj.has("tabs") && obj.get("tabs").isJsonArray()) {
                List<MusicChart> tabs = gson.fromJson(obj.get("tabs"), CHART_LIST);
                if (tabs != null) s.tabs = tabs;
            }
            if (obj.has("tracks") && obj.get("tracks").isJsonArray()) {
                List<MusicTrack> tracks = gson.fromJson(obj.get("tracks"), TRACK_LIST);
                if (tracks != null) s.tracks = tracks;
            }
            s.selName = optString(obj, "selName");
            s.selKey = optString(obj, "selKey");
            s.tone = obj.has("tone") && obj.get("tone").isJsonPrimitive() ? obj.get("tone").getAsInt() : 0;
            s.toneCover = optString(obj, "toneCover");
            s.toneSet = obj.has("toneSet") && obj.get("toneSet").isJsonPrimitive() && obj.get("toneSet").getAsBoolean();
            s.cacheAt = obj.has("cacheAt") && obj.get("cacheAt").isJsonPrimitive() ? obj.get("cacheAt").getAsLong() : 0L;
            sSnapshot = s;
        } catch (Throwable t) {
            SpiderDebug.log(TAG, "load disk failed: " + t.getMessage());
        }
    }

    private static String optString(JsonObject obj, String key) {
        try {
            return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeDisk(Snapshot s) {
        File f = file();
        if (f == null) return;
        String json;
        try {
            JsonObject obj = new JsonObject();
            Gson gson = new Gson();
            obj.add("tabs", gson.toJsonTree(s.tabs, CHART_LIST));
            obj.add("tracks", gson.toJsonTree(s.tracks, TRACK_LIST));
            obj.addProperty("selName", s.selName);
            obj.addProperty("selKey", s.selKey);
            obj.addProperty("tone", s.tone);
            obj.addProperty("toneCover", s.toneCover);
            obj.addProperty("toneSet", s.toneSet);
            obj.addProperty("cacheAt", s.cacheAt);
            json = gson.toJson(obj);
        } catch (Throwable t) {
            SpiderDebug.log(TAG, "serialize failed: " + t.getMessage());
            return;
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try {
            writeSync(tmp, bytes);
            if (!tmp.renameTo(f)) {
                // 改名失败退直写（同旧契约）
                writeSync(f, bytes);
                tmp.delete();
            }
        } catch (Throwable t) {
            SpiderDebug.log(TAG, "write disk failed: " + t.getMessage());
            try {
                tmp.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void writeSync(File target, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        }
        // RandomAccessFile 兜底校验长度（写坏的半截文件不许冒充快照）
        try (RandomAccessFile raf = new RandomAccessFile(target, "r")) {
            if (raf.length() != bytes.length) throw new IllegalStateException("short write");
        }
    }

    private static File file() {
        try {
            File dir = App.get().getFilesDir();
            return dir == null ? null : new File(dir, FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

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
}
