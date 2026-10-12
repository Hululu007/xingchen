package com.fongmi.android.tv.ui.activity;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.utils.Task;

/**
 * 下一集预加载：setPlayer 成功后，后台提前解析下一集的播放 URL。
 * 切集时若命中（key+flag+episodeUrl 一致且未过期），跳过解析链直接播。
 * 只做 URL 预解析，不做双 Player 实例（省解码器资源）。
 */
public class NextEpisodePreloader {

    private static final long TTL_MS = 10 * 60 * 1000; // 10 分钟

    private Result mPreloaded;
    private String mSpec; // key|flag|episodeUrl
    private long mTimestamp;

    /**
     * 后台预解析指定集。episode 为空或 URL 为空时清空。
     */
    public void preload(String key, String flag, Episode episode) {
        if (episode == null || episode.getUrl() == null || episode.getUrl().isEmpty()) {
            clear();
            return;
        }
        String spec = buildSpec(key, flag, episode.getUrl());
        synchronized (this) {
            mSpec = spec;
            mPreloaded = null;
            mTimestamp = System.currentTimeMillis();
        }
        Task.execute(() -> {
            try {
                Result result = SiteApi.playerContent(key, flag, episode.getUrl());
                synchronized (this) {
                    if (spec.equals(mSpec)) {
                        mPreloaded = result;
                        mTimestamp = System.currentTimeMillis();
                    }
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * 若预加载命中则返回 Result 并清空，未命中返回 null。
     */
    public synchronized Result consumeIfMatch(String key, String flag, String episodeUrl) {
        if (mSpec == null || mPreloaded == null) return null;
        if (!mSpec.equals(buildSpec(key, flag, episodeUrl))) return null;
        if (System.currentTimeMillis() - mTimestamp > TTL_MS) {
            clear();
            return null;
        }
        Result result = mPreloaded;
        clear();
        return result;
    }

    public synchronized void clear() {
        mPreloaded = null;
        mSpec = null;
        mTimestamp = 0;
    }

    private static String buildSpec(String key, String flag, String url) {
        return key + "|" + flag + "|" + url;
    }
}
