package com.fongmi.android.tv.music;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;

import java.io.File;

/**
 * 音乐缓存（2026-10-23 低-02 修）：
 * 播放服务不做媒体落盘缓存（直链流播），音乐自有的磁盘缓存只有歌词缓存
 * （MusicLyricCache 的 lyric_cache 目录）。此前「清音乐缓存」清的是全 App 共用
 * 的 Glide 图片磁盘缓存，会连坐视频封面，与按钮字面承诺不符；现只清音乐自有
 * 的歌词缓存（内存＋磁盘），不动 Glide 共用缓存。
 */
public class MusicCache {

    public static File cacheDir() {
        return MusicLyricCache.diskDir();
    }

    public static long size() {
        return dirSize(cacheDir());
    }

    private static long dirSize(File dir) {
        long sum = 0;
        try {
            File[] files = dir == null ? null : dir.listFiles();
            if (files == null) return 0;
            for (File f : files) {
                if (f.isDirectory()) sum += dirSize(f);
                else sum += f.length();
            }
        } catch (Throwable ignored) {
        }
        return sum;
    }

    /** 清音乐自有缓存（歌词内存＋磁盘，后台线程）；不动全 App 共用的 Glide 图片缓存 */
    public static void clear(Runnable done) {
        Task.execute(() -> {
            try {
                MusicLyricCache.clearAll();
            } catch (Throwable ignored) {
            }
            if (done != null) App.post(done);
        });
    }

    /** B/MB 友好显示 */
    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        // 扫描 T6：小于 1KB 显示字节数——此前整数除法把 1–1023 字节一律算成「0 KB」，
        // 歌词缓存几百字节时设置页显示得像没缓存
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return (bytes / 1024L) + " KB";
        double mb = bytes / (1024.0 * 1024.0);
        return mb >= 100 ? String.format("%.0f MB", mb) : String.format("%.1f MB", mb);
    }
}
