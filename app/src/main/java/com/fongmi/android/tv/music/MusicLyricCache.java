package com.fongmi.android.tv.music;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 歌词结果缓存（2026-10-22 瓜子歌词秒出对照）：瓜子靠进程内 200 条跨源命中缓存（lyricCrossHit），
 * 星辰原来完全不缓存、每次进页都重走全链（跨源＋歌词插件＋Worker＋平台直连），本地歌尤其慢。
 * 这里内存 200 条＋磁盘落盘双层：重播秒出，重启也不用重取；手动重搜（force）绕过读缓存、命中后覆盖写缓存、空结果清缓存。
 */
public class MusicLyricCache {

    private static final int MEM_MAX = 200;
    private static final int DISK_MAX = 500;
    /** 单字段长度上限（低-03）：按文件头 len 直接分配，缓存损坏时会大额分配；歌词字段远小于此。 */
    private static final int MAX_FIELD_BYTES = 8 * 1024 * 1024;
    /** 磁盘歌词有效期（固定过期，F2 审计④）：自写入时刻起 30 天，命中不刷新——
     * 旧滑动口径（命中即 setLastModified）下常听的错版词永远不过期、被永久钉死。
     * 固定过期后任何自动链写下的词最迟 30 天自愈重取；手动选版/手动重搜的 put
     * 同样从写入时刻重新计期（那是用户当场确认的版，30 天内不被复核打扰）。 */
    private static final long DISK_TTL_MS = 30L * 24 * 3600 * 1000;
    /** 命中复核线：磁盘账龄超过 7 天视为待复核——get() 照常返回（断网也有词），
     * 服务侧取词见待复核就后台重走一条自动链，链成功即覆盖写回新版（错版就此被
     * 纠正，不必等满 30 天），链失败什么都不动、旧账继续用（见 MusicRepository
     * fetchLyricForPlayback）。手动选版不受影响：自动链第一步就是恢复手动选版。 */
    private static final long DISK_REVALIDATE_MS = 7L * 24 * 3600 * 1000;

    private static final Map<String, MusicLyric> MEM = new LinkedHashMap<String, MusicLyric>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MusicLyric> eldest) {
            return size() > MEM_MAX;
        }
    };

    /**
     * 缓存键：在线歌 platform:id 精确；本地文件 localPath 唯一；最后才退标题＋歌手＋专辑＋时长。
     * 两处旧口子已堵：①平台为空时不再拼成 ":id"（不同源同 id 会撞进同一键），
     * 平台为空的 id 视为不可靠身份、落到回退键；②回退键此前只有标题＋歌手，
     * 同名同歌手不同版本（现场／翻唱／重录／不同专辑）会贴错词，现加专辑与时长区分。
     * 注意：回退键格式变了，旧回退键的磁盘文件命中不上（只会重取一次、按新键重写，
     * 旧文件由 500 条上限自然淘汰），在线 platform:id 键不变。
     * 三堵：FreeJS 曲目的 platform 是内容平台（多插件共用）、id 只是插件内编号，
     * 两家插件同平台同 id 会撞键串词——rawJson 带 _plugin 戳时把来源并入键
     * （落雪 id 自带 lx:平台: 命名空间、py 的 id 带 siteKey，都已自含来源，不加）。
     * 带戳曲目的旧键同样只会重取一次。
     */
    public static String key(MusicTrack track) {
        if (track == null) return null;
        if (track.id != null && !track.id.isEmpty()
                && track.platform != null && !track.platform.isEmpty()) {
            String plugin = MusicIdentity.pluginOf(track.rawJson);
            if (plugin != null && !plugin.isEmpty()) return safe(track.platform) + ":" + safe(plugin) + ":" + track.id;
            return safe(track.platform) + ":" + track.id;
        }
        if (track.localPath != null && !track.localPath.isEmpty()) return "file:" + track.localPath;
        return "local:" + safe(track.title) + "|" + safe(track.artist)
                + "|" + safe(track.album) + "|" + track.duration;
    }

    public static MusicLyric get(MusicTrack track) {
        String key = key(track);
        if (key == null) return null;
        synchronized (MEM) {
            MusicLyric hit = MEM.get(key);
            if (hit != null && hit.hasLyric()) return hit;
        }
        MusicLyric disk = readDisk(key);
        if (disk != null) {
            synchronized (MEM) {
                MEM.put(key, disk);
            }
            return disk;
        }
        return null;
    }

    public static void put(MusicTrack track, MusicLyric lyric) {
        if (track == null || lyric == null || !lyric.hasLyric()) return;
        String key = key(track);
        if (key == null) return;
        synchronized (MEM) {
            MEM.put(key, lyric);
        }
        writeDisk(key, lyric);
    }

    public static void putPlain(MusicTrack track, String lrc) {
        if (lrc == null || lrc.trim().isEmpty()) return;
        put(track, new MusicLyric(lrc));
    }

    /** 手动重搜确认无词时清掉旧缓存，不让 stale 的错词一直贴着。 */
    public static void remove(MusicTrack track) {
        String key = key(track);
        if (key == null) return;
        synchronized (MEM) {
            MEM.remove(key);
        }
        // 与 writeDisk 同锁：在途写盘改名不许把刚删掉的键又写回来
        synchronized (DISK_LOCK) {
            try {
                File f = diskFile(key);
                if (f != null && f.isFile()) f.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static File dir() {
        try {
            File d = new File(App.get().getCacheDir(), "lyric_cache");
            if (!d.isDirectory()) d.mkdirs();
            return d;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 歌词缓存目录（MusicCache 统计/清理用）。 */
    public static File diskDir() {
        return dir();
    }

    /** 清全部歌词缓存（内存＋磁盘），「清音乐缓存」入口用。 */
    public static void clearAll() {
        synchronized (MEM) {
            MEM.clear();
        }
        try {
            File d = dir();
            File[] fs = d == null ? null : d.listFiles();
            if (fs != null) {
                for (File f : fs) f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable ignored) {
            return String.valueOf(s.hashCode());
        }
    }

    private static File diskFile(String key) {
        File d = dir();
        return d == null ? null : new File(d, md5(key) + ".bin");
    }

    private static void writeString(DataOutputStream out, String s) throws Throwable {
        if (s == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws Throwable {
        int len = in.readInt();
        if (len < 0) return null;
        if (len > MAX_FIELD_BYTES) throw new java.io.IOException("lyric cache field too large: " + len);
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 待裁26：写盘串行锁——并行取词路（预取／下载／双调用）可同键并发 put，
     * 两路直写同一文件会交错写坏；改临时文件写完再改名，读者也永不见半截文件。 */
    private static final Object DISK_LOCK = new Object();

    private static void writeDisk(String key, MusicLyric lyric) {
        synchronized (DISK_LOCK) {
            try {
                File f = diskFile(key);
                if (f == null) return;
                File tmp = new File(f.getPath() + ".tmp");
                DataOutputStream out = new DataOutputStream(new FileOutputStream(tmp));
                try {
                    writeString(out, lyric.rawLrc);
                    writeString(out, lyric.translation);
                    writeString(out, lyric.romanization);
                    writeString(out, lyric.format);
                    writeString(out, lyric.sourceName);
                } finally {
                    out.close();
                }
                if (!tmp.renameTo(f)) {
                    // 改名失败（如个别文件系统跨卷）退回直写，仍在锁内不交错
                    DataOutputStream direct = new DataOutputStream(new FileOutputStream(f));
                    try {
                        writeString(direct, lyric.rawLrc);
                        writeString(direct, lyric.translation);
                        writeString(direct, lyric.romanization);
                        writeString(direct, lyric.format);
                        writeString(direct, lyric.sourceName);
                    } finally {
                        direct.close();
                    }
                    tmp.delete();
                }
                trimDisk();
            } catch (Throwable ignored) {
            }
        }
    }

    private static MusicLyric readDisk(String key) {
        try {
            File f = diskFile(key);
            if (f == null || !f.isFile()) return null;
            if (System.currentTimeMillis() - f.lastModified() > DISK_TTL_MS) {
                f.delete();
                return null;
            }
            DataInputStream in = new DataInputStream(new FileInputStream(f));
            try {
                MusicLyric lyric = new MusicLyric();
                lyric.rawLrc = readString(in);
                lyric.translation = readString(in);
                lyric.romanization = readString(in);
                lyric.format = readString(in);
                lyric.sourceName = readString(in);
                if (!lyric.hasLyric()) return null;
                // 固定过期：命中不刷新 lastModified（旧滑动刷新已废，见 DISK_TTL_MS 注）
                return lyric;
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 该歌的磁盘账是否已过复核线（账龄＞DISK_REVALIDATE_MS）：无磁盘账/未过线回 false。
     * 只看磁盘写入时刻（内存账是本进程刚写/刚读的，不复核）。 */
    public static boolean isStale(MusicTrack track) {
        String key = key(track);
        if (key == null) return false;
        try {
            File f = diskFile(key);
            return f != null && f.isFile()
                    && System.currentTimeMillis() - f.lastModified() > DISK_REVALIDATE_MS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 磁盘超 500 条删写入最久的（lastModified 自固定过期起就是写入时刻，不再被命中刷新）。 */
    private static void trimDisk() {
        try {
            File d = dir();
            if (d == null) return;
            File[] files = d.listFiles((dir, name) -> name.endsWith(".bin"));
            if (files == null || files.length <= DISK_MAX) return;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < files.length - DISK_MAX; i++) {
                try {
                    files[i].delete();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
