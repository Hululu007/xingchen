package com.fongmi.android.tv.music;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;

import java.io.File;

/**
 * 音乐缓存（2026-10-20）：
 * 播放服务不做媒体落盘缓存（直链流播），所以「音乐缓存」实际清的是
 * Glide 图片磁盘缓存——音乐封面/曲库图片走的就是这套缓存（和全 App
 * 的视频封面共用同一个 Glide 磁盘缓存目录，没有独立的音乐封面目录）。
 * 清的是真实缓存目录，但注意会顺带清掉视频封面缓存，详见设置页报告。
 */
public class MusicCache {

    /** Glide 默认磁盘缓存目录名（InternalCacheDiskCacheFactory 默认值） */
    private static final String GLIDE_DISK_CACHE = "image_manager_disk_cache";

    public static File cacheDir() {
        return new File(App.get().getCacheDir(), GLIDE_DISK_CACHE);
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

    /** 清图片磁盘缓存（Glide 标准口，后台线程）＋内存缓存（主线程） */
    public static void clear(Runnable done) {
        Task.execute(() -> {
            try {
                Glide.get(App.get()).clearDiskCache();
            } catch (Throwable ignored) {
            }
            App.post(() -> {
                try {
                    Glide.get(App.get()).clearMemoryCache();
                } catch (Throwable ignored) {
                }
                if (done != null) done.run();
            });
        });
    }

    /** B/MB 友好显示 */
    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024L * 1024L) return (bytes / 1024L) + " KB";
        double mb = bytes / (1024.0 * 1024.0);
        return mb >= 100 ? String.format("%.0f MB", mb) : String.format("%.1f MB", mb);
    }
}
