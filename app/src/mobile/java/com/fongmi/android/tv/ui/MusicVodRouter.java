package com.fongmi.android.tv.ui;

import android.app.Activity;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.HkMusicSource;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.PyMusicSource;
import com.fongmi.android.tv.ui.music.MusicPlayerActivity;

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

    /** 分类列表会话标识：同源＋同分类（＋同筛选）才算同一个列表，续载只追本列表的页。 */
    public static String categorySession(String siteKey, String typeId, String extendsSig) {
        return "cat\u0001" + (siteKey == null ? "" : siteKey) + "\u0001" + (typeId == null ? "" : typeId)
                + "\u0001" + (extendsSig == null ? "" : extendsSig);
    }

    /** 聚合搜索会话标识：同源＋同搜索词才算同一个列表。 */
    public static String searchSession(String siteKey, String keyword) {
        return "search\u0001" + (siteKey == null ? "" : siteKey) + "\u0001" + (keyword == null ? "" : keyword);
    }

    /**
     * 列表整拉：分类／搜索列表里点开一首音乐，整列表的歌都进音乐播放器队列，
     * 从点开的那首开始播（跟音乐侧列表点播同一口径，上一首／下一首／播完自动切都有了）。
     * 多源混排的列表只拉与点开那首同源的歌；没填源键的条目默认算同源（分类列表就是这样）。
     */
    public static boolean routeVodList(Activity activity, String siteKey, java.util.List<Vod> vods, Vod clicked) {
        return routeVodList(activity, siteKey, vods, clicked, null);
    }

    /**
     * 带列表会话版：入队时把「这队歌出自哪个列表」绑在队列上（session）；此后那个列表
     * 再翻页续载，新页的歌经 appendListPage 同步追进队尾——队列含已加载的全部页，
     * 且起播后再加载的页也跟上（会话对不上／队列已换则不追，见 MusicQueue.listSession）。
     */
    public static boolean routeVodList(Activity activity, String siteKey, java.util.List<Vod> vods, Vod clicked, String session) {
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
            if (session == null || session.isEmpty()) {
                MusicController.get().play(queue, index, site.getName(), "list", null);
            } else {
                MusicController.get().playListSession(queue, index, site.getName(), "list", null, session);
            }
            MusicPlayerActivity.start(activity);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 列表续载一页的歌追进同会话队列的队尾：只认与起播同一列表（session 一致＋同源）
     * 的条目；队列侧再按曲目键去重。列表页每次加载到新的一页时调（第一页不调——
     * 它已在起播快照里）。不是音乐源／会话对不上时服务侧自动丢弃，这里不预判。
     */
    public static void appendListPage(String session, String siteKey, java.util.List<Vod> pageItems) {
        try {
            if (session == null || session.isEmpty() || siteKey == null || siteKey.isEmpty()
                    || pageItems == null || pageItems.isEmpty()) return;
            Site site = VodConfig.get().getSite(siteKey);
            if (site == null || site.isEmpty() || !PyMusicSource.looksMusic(site)) return;
            List<MusicTrack> tracks = new ArrayList<>();
            for (Vod v : pageItems) {
                if (v == null || v.getId() == null || v.getId().isEmpty()) continue;
                String vs = v.getSiteKey();
                if (vs != null && !vs.isEmpty() && !vs.equals(siteKey)) continue;
                tracks.add(PyMusicSource.trackForVod(siteKey, v));
            }
            if (tracks.isEmpty()) return;
            MusicController.get().appendToListQueue(session, tracks);
        } catch (Throwable ignored) {
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
