package com.fongmi.android.tv.ui;

import android.app.Activity;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.HkMusicSource;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.PyMusicSource;
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

    /**
     * 海阔直接播放（带选集）：调用方手里有整份选集时整表进队，从被点那首起播——
     * 旧单曲版只带被点一首，音乐规则的整张歌单没了上一首／下一首。
     * 载荷两种形态与 VideoActivity.setupDirectEpisodes 同口径：扁平 [{name,url,pic}]
     * （线路＝传入的 line）、{flags:[{name,episodes:[...]}]}（多线路、集序一致，
     * 取与 line 同名的线路、无同名取第一条非空线路，selIdx 是集序下标）。
     * 起点以 episodeUrl 命中为准（调用方保证它就是被点那条的地址），命不中才用 selIdx。
     * 解析不出整表就回落单曲版，不低于现状。
     */
    public static boolean routeHk(Activity activity, String ruleTitle, String line, String episodeUrl, String name, String pic, String episodesJson, int selIdx) {
        try {
            if (activity == null || ruleTitle == null || ruleTitle.isEmpty() || episodeUrl == null || episodeUrl.isEmpty()) return false;
            if (!PyMusicSource.looksMusicName(ruleTitle)) return false;
            List<MusicTrack> queue = hkQueueFromJson(ruleTitle, line, episodeUrl, pic, episodesJson);
            if (queue.size() > 1) {
                int start = hkStartIndex(queue, ruleTitle, episodeUrl, selIdx);
                MusicController.get().play(queue, start, ruleTitle, "list", null);
                return true;
            }
            if (queue.size() == 1) return play(activity, queue.get(0));
            return play(activity, HkMusicSource.trackFor(ruleTitle, line, episodeUrl, name, pic));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 海阔详情整队：收藏／历史走 startHk 打开的是视频详情页，音乐规则在那页点选集
     * 会在视频播放器里播（绕开音乐播放器）。详情数据（HkDetailBridge 转出的 Vod）
     * 到手后先问这里：规则名识别为音乐就把第一条线路的选集整表建成乐队进音乐
     * 播放器，返回 true，调用方随即关掉详情页。取链仍走 HkMusicSource 的 hk_ 解析，
     * 不碰海阔引擎。封面每首用条目图兜底（Episode 模型不带单集图）。
     */
    public static boolean routeHkDetail(Activity activity, String ruleTitle, Vod vod) {
        try {
            if (activity == null || ruleTitle == null || ruleTitle.isEmpty() || vod == null) return false;
            if (!PyMusicSource.looksMusicName(ruleTitle)) return false;
            if (vod.getFlags() == null) return false;
            List<MusicTrack> queue = new ArrayList<>();
            for (com.fongmi.android.tv.bean.Flag flag : vod.getFlags()) {
                if (flag == null || flag.getEpisodes() == null || flag.getEpisodes().isEmpty()) continue;
                for (com.fongmi.android.tv.bean.Episode ep : flag.getEpisodes()) {
                    if (ep == null || ep.getUrl() == null || ep.getUrl().isEmpty()) continue;
                    String title = ep.getName() == null || ep.getName().isEmpty() ? vod.getName() : ep.getName();
                    queue.add(HkMusicSource.trackFor(ruleTitle, flag.getFlag(), ep.getUrl(), title, vod.getPic()));
                }
                if (!queue.isEmpty()) break; // 只取第一条非空线路，与详情页默认线路一致
            }
            if (queue.isEmpty()) return false;
            MusicController.get().play(queue, 0, ruleTitle, "list", null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** episodesJson → 乐队（解析口径见 routeHk 选集版注释）；解析失败回空表，由调用方回落单曲 */
    private static List<MusicTrack> hkQueueFromJson(String ruleTitle, String line, String episodeUrl, String pic, String episodesJson) {
        List<MusicTrack> queue = new ArrayList<>();
        if (episodesJson == null || episodesJson.isEmpty()) return queue;
        try {
            String json = episodesJson.trim();
            if (json.startsWith("{")) {
                org.json.JSONObject root = new org.json.JSONObject(json);
                org.json.JSONArray flagArr = root.optJSONArray("flags");
                if (flagArr == null) return queue;
                org.json.JSONObject chosen = null;
                org.json.JSONObject first = null;
                for (int fi = 0; fi < flagArr.length(); fi++) {
                    org.json.JSONObject fo = flagArr.optJSONObject(fi);
                    if (fo == null) continue;
                    org.json.JSONArray eps = fo.optJSONArray("episodes");
                    if (eps == null || eps.length() == 0) continue;
                    if (first == null) first = fo;
                    if (line != null && line.equals(fo.optString("name", ""))) {
                        chosen = fo;
                        break;
                    }
                }
                if (chosen == null) chosen = first;
                if (chosen == null) return queue;
                String lineName = chosen.optString("name", line == null ? "" : line);
                org.json.JSONArray eps = chosen.optJSONArray("episodes");
                for (int i = 0; i < eps.length(); i++) {
                    org.json.JSONObject o = eps.optJSONObject(i);
                    if (o == null) continue;
                    String url = o.optString("url", "");
                    if (url.isEmpty()) continue;
                    String epPic = o.optString("pic", "");
                    queue.add(HkMusicSource.trackFor(ruleTitle, lineName, url,
                            o.optString("name", ""), epPic.isEmpty() ? pic : epPic));
                }
                return queue;
            }
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "");
                if (url.isEmpty()) continue;
                String epPic = o.optString("pic", "");
                queue.add(HkMusicSource.trackFor(ruleTitle, line == null ? "" : line, url,
                        o.optString("name", ""), epPic.isEmpty() ? pic : epPic));
            }
            return queue;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /**
     * 起点定位：episodeUrl 命中优先——trackFor 的曲目 id 就是 "hk:规则名:episodeUrl"
     * 原样拼串（内存串、无序列化转义问题），拼出期望 id 精确比对；
     * 命不中才用 selIdx（扁平形态是数组序、flags 形态是集序，与建队顺序一致）并夹紧范围。
     */
    private static int hkStartIndex(List<MusicTrack> queue, String ruleTitle, String episodeUrl, int selIdx) {
        String wantId = HkMusicSource.PLATFORM + ":" + ruleTitle + ":" + episodeUrl;
        for (int i = 0; i < queue.size(); i++) {
            if (wantId.equals(queue.get(i).id)) return i;
        }
        return Math.max(0, Math.min(selIdx, queue.size() - 1));
    }

    private static boolean play(Activity activity, MusicTrack track) {
        List<MusicTrack> queue = new ArrayList<>();
        queue.add(track);
        MusicController.get().play(queue, 0);
        return true;
    }
}
