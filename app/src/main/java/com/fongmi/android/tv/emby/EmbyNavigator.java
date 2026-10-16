package com.fongmi.android.tv.emby;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.dialog.EmbyQualityDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Emby 集成导航：详情 / 播放 / 搜索站点，全部走壳子既有链路。
 *
 * 详情：注册内存源 csp_Emby（type=3，经 JarLoader 分支 new 内置 EmbySpider），
 * 走 VideoActivity.start(key, id, name) 同一详情入口，条目 id 形如 emby:&lt;serverId&gt;:&lt;itemId&gt;。
 * 播放：EmbyApi.playbackOptions 拿画质候选 → 播前选画质 → SiteApi.PUSH 直播链 + extras 透传。
 */
public class EmbyNavigator {

    public static final String SITE_KEY = "csp_Emby";
    public static final String API = "csp_Emby";

    /** VideoActivity intent extras：Emby 播放来源标记与上报/续播/字幕/轨道信息 */
    public static final String EXTRA_SERVER = "emby_server";
    public static final String EXTRA_ITEM = "emby_item";
    public static final String EXTRA_SESSION = "emby_session";
    public static final String EXTRA_START_MS = "emby_start_ms";
    public static final String EXTRA_SUBTITLES = "emby_subtitles";
    public static final String EXTRA_DEFAULT_SUB = "emby_default_sub";
    public static final String EXTRA_TRACKS = "emby_tracks";

    /** 注册内存源（不落盘），供详情/播放/搜索经 getSite() 解析到 EmbySpider。 */
    public static void ensureSite() {
        try {
            Site site = VodConfig.get().getSite(SITE_KEY);
            if (site != null && !site.isEmpty()) return;
            Site s = new Site();
            s.setKey(SITE_KEY);
            s.setName("Emby");
            s.setApi(API);
            s.setType(3);
            s.setExt("");
            s.setJar("");
            s.setSearchable(1);
            VodConfig.get().registerDynamicSite(s);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 聚合搜索（层2）用源位：每个已登录服一个虚拟源（key "emby:<serverId>"，api "csp_Emby"，名 "Emby·服别名"），
     * 走主搜索页既有多源并发框架，一服一组同屏。
     */
    public static Site searchSiteFor(EmbyStore.EmbyServer s) {
        if (s == null || !s.isLogin()) return null;
        Site site = new Site();
        site.setKey("emby:" + s.id);
        site.setName("Emby·" + s.displayName());
        site.setApi(API);
        site.setType(3);
        site.setExt("");
        site.setJar("");
        site.setSearchable(1);
        VodConfig.get().registerDynamicSite(site);
        return site;
    }

    /** 仅当前已登录服（老口径，AI 口令等单服用）。 */
    public static Site getSearchSite() {
        EmbyStore.EmbyServer s = EmbyStore.active();
        if (s == null || !s.isLogin()) return null;
        ensureSite();
        Site site = VodConfig.get().getSite(SITE_KEY);
        if (site == null || site.isEmpty()) return null;
        site.setName("Emby·" + s.displayName());
        return site;
    }

    /** 所有已登录服的虚拟源（层1：内部多服聚合走多源并发框架）。 */
    public static List<Site> getSearchSites() {
        List<Site> out = new ArrayList<>();
        try {
            for (EmbyStore.EmbyServer s : EmbyStore.servers()) {
                if (s != null && s.isLogin()) {
                    Site site = searchSiteFor(s);
                    if (site != null) out.add(site);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 详情：走既有详情入口（VideoActivity.start），透传 serverId/itemId。 */
    public static void openDetail(Context ctx, String serverId, String itemId) {
        Activity act = toActivity(ctx);
        if (act == null || TextUtils.isEmpty(serverId) || TextUtils.isEmpty(itemId)) return;
        ensureSite();
        VideoActivity.start(act, SITE_KEY, "emby:" + serverId + ":" + itemId, "", null);
    }

    /**
     * 播放：后台一次 playbackCandidates 拿画质候选 + 会话 id + 断点 + 字幕/轨道 → UI 线程播前选画质 →
     * VideoActivity.start(PUSH, url, name) + extras 透传。会话 id 取自同一次 PlaybackInfo 响应，
     * reportPlaying/Progress/Stopped 都用它（不再另调 playback() 取会话）。
     * 若用户在弹窗里选了转码档，播的是候选地址，会话按默认源上报。
     */
    public static void play(Context ctx, String serverId, String itemId, String name) {
        Task.execute(() -> {
            try {
                EmbyStore.EmbyServer s = EmbyStore.getServer(serverId);
                if (s == null || !s.isLogin()) {
                    notify("请先去设置-个性功能登录 Emby");
                    return;
                }
                EmbyApi api = new EmbyApi(s.baseUrl, s.token, s.userId, s.id);
                EmbyItem target = api.getItem(itemId);
                String playId = itemId;
                String playName = name;
                String episodesJson = null;
                if (target != null && target.isSeries()) {
                    // 剧集：拉全季集，选起始集（有续播点的第一集 → 没看过的第一集 → 第一集），配选集单
                    List<EmbyItem> eps = allEpisodes(api, itemId);
                    if (eps.isEmpty()) throw new Exception("这部剧没有可播的集");
                    EmbyItem start = pickStartEpisode(eps);
                    playId = start.id;
                    playName = (target.name == null ? "" : target.name) + " " + (start.name == null ? "" : start.name);
                    episodesJson = episodesJson(serverId, eps);
                }
                EmbyApi.PlaybackCandidates cand = api.playbackCandidates(playId, playName);
                long resumeMs = 0;
                try {
                    EmbyItem it = api.getItem(playId);
                    if (it != null) resumeMs = it.resumeMs();
                } catch (Throwable ignored) {
                }
                long resume = resumeMs;
                String pid = playId;
                String pname = playName;
                String epJson = episodesJson;
                App.post(() -> {
                    Activity act = toActivity(ctx);
                    if (act == null || act.isFinishing() || !(act instanceof FragmentActivity)) return;
                    FragmentActivity fa = (FragmentActivity) act;
                    if (cand.options.size() <= 1) {
                        startPlayback(fa, s, pid, pname, cand.options.get(0).url, cand, resume, epJson);
                    } else {
                        EmbyQualityDialog.show(fa, cand.options, picked ->
                                startPlayback(fa, s, pid, pname, picked.url, cand, resume, epJson));
                    }
                });
            } catch (Exception e) {
                notify("Emby 播放失败：" + e.getMessage());
            }
        });
    }

    private static void startPlayback(Activity act, EmbyStore.EmbyServer s, String itemId, String name,
                                      String url, EmbyApi.PlaybackCandidates cand, long resumeMs, String episodesJson) {
        if (TextUtils.isEmpty(url)) {
            Notify.show("没拿到播放地址");
            return;
        }
        Intent intent = new Intent(act, VideoActivity.class);
        intent.putExtra("collect", false);
        intent.putExtra("name", name == null ? "" : name);
        intent.putExtra("key", SiteApi.PUSH);
        intent.putExtra("id", url);
        intent.putExtra(EXTRA_SERVER, s.id);
        intent.putExtra(EXTRA_ITEM, itemId);
        intent.putExtra(EXTRA_SESSION, cand.playSessionId == null ? "" : cand.playSessionId);
        if (resumeMs > 0) intent.putExtra(EXTRA_START_MS, resumeMs);
        intent.putExtra(EXTRA_SUBTITLES, subtitlesJson(cand.subtitles));
        intent.putExtra(EXTRA_DEFAULT_SUB, defaultSubIndex(cand.subtitles));
        intent.putExtra(EXTRA_TRACKS, tracksJson(cand.audioTracks));
        if (!TextUtils.isEmpty(episodesJson)) intent.putExtra(EXTRA_EPISODES, episodesJson);
        act.startActivity(intent);
    }

    /** intent 携带的选集单（剧集）：[{id:"emby:sid:epid", name:"集名"}]，VideoActivity 配选集/下一集。 */
    public static final String EXTRA_EPISODES = "emby_episodes";

    /** 全季集（按季拼齐，含集名与 UserData）。 */
    private static List<EmbyItem> allEpisodes(EmbyApi api, String seriesId) throws Exception {
        List<EmbyItem> out = new ArrayList<>();
        for (EmbyItem season : api.seasons(seriesId)) {
            for (EmbyItem ep : api.episodes(season.id)) {
                if (ep != null && !TextUtils.isEmpty(ep.id)) out.add(ep);
            }
        }
        return out;
    }

    /** 起始集：有续播点的第一集 → 没看过的第一集 → 第一集。 */
    private static EmbyItem pickStartEpisode(List<EmbyItem> eps) {
        for (EmbyItem e : eps) if (e.resumeMs() > 0) return e;
        for (EmbyItem e : eps) if (!e.played) return e;
        return eps.get(0);
    }

    /** 选集单 json。 */
    private static String episodesJson(String serverId, List<EmbyItem> eps) {
        try {
            JSONArray arr = new JSONArray();
            for (EmbyItem e : eps) {
                JSONObject o = new JSONObject();
                o.put("id", "emby:" + serverId + ":" + e.id);
                o.put("name", e.name == null ? "" : e.name);
                arr.put(o);
            }
            return arr.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String subtitlesJson(List<EmbyApi.Subtitle> subs) {
        JSONArray arr = new JSONArray();
        if (subs != null) for (EmbyApi.Subtitle s : subs) {
            try {
                JSONObject o = new JSONObject();
                o.put("title", s.title == null ? "" : s.title);
                o.put("url", s.url == null ? "" : s.url);
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        return arr.toString();
    }

    private static int defaultSubIndex(List<EmbyApi.Subtitle> subs) {
        return 0;
    }

    /** 音轨/字幕轨信息透传（播放中切换用，VideoActivity 存备查）。 */
    private static String tracksJson(List<EmbyApi.TrackInfo> tracks) {
        JSONArray arr = new JSONArray();
        if (tracks != null) for (EmbyApi.TrackInfo t : tracks) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", t.name == null ? "" : t.name);
                o.put("language", t.language == null ? "" : t.language);
                o.put("index", t.index);
                o.put("default", t.isDefault);
                o.put("external", t.external);
                o.put("url", t.url == null ? "" : t.url);
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        JSONObject root = new JSONObject();
        try {
            root.put("tracks", arr);
        } catch (Throwable ignored) {
        }
        return root.toString();
    }

    /** Emby 条目 id 拆 serverId（emby:&lt;serverId&gt;:&lt;itemId&gt;），拆不出返回空串。 */
    public static String serverIdOf(String embyId) {
        if (TextUtils.isEmpty(embyId) || !embyId.startsWith("emby:")) return "";
        int p = embyId.indexOf(':', 5);
        return p > 5 ? embyId.substring(5, p) : "";
    }

    /** Emby 条目 id 拆 itemId，拆不出返回空串。 */
    public static String itemIdOf(String embyId) {
        if (TextUtils.isEmpty(embyId) || !embyId.startsWith("emby:")) return "";
        int p = embyId.indexOf(':', 5);
        return p > 5 && p + 1 < embyId.length() ? embyId.substring(p + 1) : "";
    }

    public static List<String> loggedServerIds() {
        List<String> ids = new ArrayList<>();
        try {
            for (EmbyStore.EmbyServer s : EmbyStore.servers()) if (s.isLogin()) ids.add(s.id);
        } catch (Throwable ignored) {
        }
        return ids;
    }

    private static Activity toActivity(Context ctx) {
        if (ctx instanceof Activity) return (Activity) ctx;
        try {
            Activity a = App.activity();
            if (a != null) return a;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void notify(String msg) {
        App.post(() -> Notify.show(msg));
    }
}
