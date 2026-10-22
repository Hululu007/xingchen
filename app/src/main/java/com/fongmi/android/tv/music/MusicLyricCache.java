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

    private static final Map<String, MusicLyric> MEM = new LinkedHashMap<String, MusicLyric>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MusicLyric> eldest) {
            return size() > MEM_MAX;
        }
    };

    /** 缓存键：在线歌 platform:id 精确；本地文件 localPath 唯一；最后才退标题＋歌手。 */
    public static String key(MusicTrack track) {
        if (track == null) return null;
        if (track.id != null && !track.id.isEmpty()) return safe(track.platform) + ":" + track.id;
        if (track.localPath != null && !track.localPath.isEmpty()) return "file:" + track.localPath;
        return "local:" + safe(track.title) + "|" + safe(track.artist);
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
        try {
            File f = diskFile(key);
            if (f != null && f.isFile()) f.delete();
        } catch (Throwable ignored) {
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
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeDisk(String key, MusicLyric lyric) {
        try {
            File f = diskFile(key);
            if (f == null) return;
            DataOutputStream out = new DataOutputStream(new FileOutputStream(f));
            try {
                writeString(out, lyric.rawLrc);
                writeString(out, lyric.translation);
                writeString(out, lyric.romanization);
                writeString(out, lyric.format);
                writeString(out, lyric.sourceName);
            } finally {
                out.close();
            }
            trimDisk();
        } catch (Throwable ignored) {
        }
    }

    private static MusicLyric readDisk(String key) {
        try {
            File f = diskFile(key);
            if (f == null || !f.isFile()) return null;
            DataInputStream in = new DataInputStream(new FileInputStream(f));
            try {
                MusicLyric lyric = new MusicLyric();
                lyric.rawLrc = readString(in);
                lyric.translation = readString(in);
                lyric.romanization = readString(in);
                lyric.format = readString(in);
                lyric.sourceName = readString(in);
                if (!lyric.hasLyric()) return null;
                f.setLastModified(System.currentTimeMillis());
                return lyric;
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 磁盘超 500 条删最久没用的（命中会刷新 lastModified，删的是真冷数据）。 */
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
