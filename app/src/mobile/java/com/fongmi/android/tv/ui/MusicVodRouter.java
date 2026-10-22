package com.fongmi.android.tv.ui;

import android.app.Activity;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.HkMusicSource;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.PyMusicSource;
import com.fongmi.android.tv.ui.activity.MusicPlayerActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频侧音乐路由：跟阅读一样「调用」音乐播放器。
 * 视频那边（首页／分类／搜索／历史／收藏／海阔）点开被识别为音乐的源的封面时，
 * 不进视频详情页和视频播放器，直接把这一首交给音乐播放器页面；
 * 播放器页面只是被调用，系统返回照样回到原本的地方（调用方不 finish）。
 * 识别口径与音乐侧一致：源名／键／海阔规则名含音乐关键词即认作音乐，不分 py／js／点播／海阔。
 */
public class MusicVodRouter {

    private MusicVodRouter() {
    }

    /** 点播源封面：识别为音乐则进音乐播放器并返回 true，调用方不再走视频链 */
    public static boolean routeVod(Activity activity, String siteKey, String vodId, String name, String pic) {
        try {
            if (activity == null || siteKey == null || siteKey.isEmpty() || vodId == null || vodId.isEmpty()) return false;
            Site site = VodConfig.get().getSite(siteKey);
            if (site == null || site.isEmpty()) return false;
            if (!PyMusicSource.looksMusic(site)) return false;
            return play(activity, PyMusicSource.trackForVod(siteKey, vodId, name, pic));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 列表整拉：分类／搜索列表里点开一首音乐，整列表的歌都进音乐播放器队列，
     * 从点开的那首开始播（跟音乐侧列表点播同一口径，上一首／下一首／播完自动切都有了）。
     * 多源混排的列表只拉与点开那首同源的歌；没填源键的条目默认算同源（分类列表就是这样）。
     */
    public static boolean routeVodList(Activity activity, String siteKey, java.util.List<Vod> vods, Vod clicked) {
        try {
            if (activity == null || siteKey == null || siteKey.isEmpty() || vods == null || vods.isEmpty() || clicked == null) return false;
            Site site = VodConfig.get().getSite(siteKey);
            if (site == null || site.isEmpty()) return false;
            if (!PyMusicSource.looksMusic(site)) return false;
            List<MusicTrack> queue = new ArrayList<>();
            int index = 0;
            boolean found = false;
            for (Vod v : vods) {
                if (v == null || v.getId() == null || v.getId().isEmpty()) continue;
                String vs = v.getSiteKey();
                if (vs != null && !vs.isEmpty() && !vs.equals(siteKey)) continue;
                if (!found && v.getId().equals(clicked.getId())) {
                    index = queue.size();
                    found = true;
                }
                queue.add(PyMusicSource.trackForVod(siteKey, v));
            }
            if (queue.isEmpty()) return false;
            MusicController.get().play(queue, index, site.getName(), "list", null);
            MusicPlayerActivity.start(activity);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 海阔条目：规则名识别为音乐则进音乐播放器并返回 true，调用方不再走视频链 */
    public static boolean routeHk(Activity activity, String ruleTitle, String line, String episodeUrl, String name, String pic) {
        try {
            if (activity == null || ruleTitle == null || ruleTitle.isEmpty() || episodeUrl == null || episodeUrl.isEmpty()) return false;
            if (!PyMusicSource.looksMusicName(ruleTitle)) return false;
            return play(activity, HkMusicSource.trackFor(ruleTitle, line, episodeUrl, name, pic));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean play(Activity activity, MusicTrack track) {
        List<MusicTrack> queue = new ArrayList<>();
        queue.add(track);
        MusicController.get().play(queue, 0);
        MusicPlayerActivity.start(activity);
        return true;
    }
}
