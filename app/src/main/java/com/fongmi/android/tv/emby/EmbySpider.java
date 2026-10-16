package com.fongmi.android.tv.emby;

import android.text.TextUtils;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;

/**
 * Emby 内置源：把 Emby 服务器装进 catvod Spider 合同，供壳子既有页复用。
 * 条目 id 方案：emby:<serverId>:<itemId>；分类 tid：view:<viewId> / resume / latest / nextup / fav。
 * 未登录抛中文异常，上层转去设置-个性功能登录引导。
 */
public class EmbySpider extends Spider {

    private static final int LIMIT = 50;
    private static final String NEED_LOGIN = "请先去设置-个性功能登录 Emby";

    private EmbyApi activeApi() throws Exception {
        EmbyStore.EmbyServer s = EmbyStore.active();
        if (s == null || !s.isLogin()) {
            List<EmbyStore.EmbyServer> list = EmbyStore.servers();
            s = null;
            for (EmbyStore.EmbyServer it : list) if (it.isLogin()) {
                s = it;
                break;
            }
        }
        if (s == null || !s.isLogin()) throw new Exception(NEED_LOGIN);
        return new EmbyApi(s.baseUrl, s.token, s.userId, s.id);
    }

    private EmbyStore.EmbyServer serverOf(String serverId) throws Exception {
        EmbyStore.EmbyServer s = EmbyStore.getServer(serverId);
        if (s == null || !s.isLogin()) throw new Exception(NEED_LOGIN);
        return s;
    }

    /** emby:<serverId>:<itemId> 拆成 [serverId, itemId] */
    private static String[] splitId(String id) throws Exception {
        if (TextUtils.isEmpty(id) || !id.startsWith("emby:") || id.split(":").length < 3)
            throw new Exception("条目地址不对");
        int p = id.indexOf(':', 5);
        return new String[]{id.substring(5, p), id.substring(p + 1)};
    }

    // ---------- home ----------

    @Override
    public String homeContent(boolean filter) throws Exception {
        EmbyApi api = activeApi();
        JSONArray cls = new JSONArray();
        for (EmbyItem v : api.views()) {
            JSONObject c = new JSONObject();
            c.put("type_id", "view:" + v.id);
            c.put("type_name", v.name);
            cls.put(c);
        }
        String[][] extra = {{"resume", "继续观看"}, {"nextup", "接着看"}, {"latest", "最新添加"}, {"fav", "我的收藏"}};
        for (String[] e : extra) {
            JSONObject c = new JSONObject();
            c.put("type_id", e[0]);
            c.put("type_name", e[1]);
            cls.put(c);
        }
        JSONObject r = new JSONObject();
        r.put("class", cls);
        r.put("list", new JSONArray());
        return r.toString();
    }

    // ---------- category ----------

    private JSONObject vodJson(EmbyApi api, EmbyStore.EmbyServer s, EmbyItem it) throws Exception {
        JSONObject v = new JSONObject();
        v.put("vod_id", "emby:" + s.id + ":" + it.id);
        v.put("vod_name", it.name);
        v.put("vod_pic", api.imageUrl(it.id, it.imageTag, 400));
        v.put("vod_year", it.productionYear > 0 ? String.valueOf(it.productionYear) : "");
        v.put("vod_remarks", remarkOf(it));
        return v;
    }

    private static String remarkOf(EmbyItem it) {
        if (it.resumeMs() > 0 && it.runTimeTicks > 0) {
            int pct = (int) (it.positionTicks * 100 / it.runTimeTicks);
            return "续播 " + pct + "%";
        }
        if (!it.ratingText().isEmpty()) return it.ratingText();
        if (it.played) return "已看";
        return "";
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        EmbyStore.EmbyServer s = EmbyStore.active();
        if (s == null || !s.isLogin()) throw new Exception(NEED_LOGIN);
        EmbyApi api = new EmbyApi(s.baseUrl, s.token, s.userId, s.id);
        int page = 1;
        try {
            page = Math.max(1, Integer.parseInt(pg));
        } catch (Exception ignored) {
        }
        int start = (page - 1) * LIMIT;
        JSONArray list = new JSONArray();
        int total;
        if (tid != null && tid.startsWith("view:")) {
            EmbyApi.ItemPage p = api.items(tid.substring(5), start, LIMIT);
            total = p.total;
            for (EmbyItem it : p.items) list.put(vodJson(api, s, it));
        } else {
            List<EmbyItem> all;
            if ("resume".equals(tid)) all = api.resume();
            else if ("latest".equals(tid)) all = api.latest();
            else if ("nextup".equals(tid)) all = api.nextUp();
            else if ("fav".equals(tid)) all = api.favoriteItems();
            else throw new Exception("未知分类");
            total = all.size();
            for (int i = start; i < Math.min(start + LIMIT, all.size()); i++) list.put(vodJson(api, s, all.get(i)));
        }
        JSONObject r = new JSONObject();
        r.put("page", page);
        r.put("pagecount", total <= 0 ? 1 : (total + LIMIT - 1) / LIMIT);
        r.put("limit", LIMIT);
        r.put("total", total);
        r.put("list", list);
        return r.toString();
    }

    // ---------- detail ----------

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String[] sp = splitId(ids.get(0));
        EmbyStore.EmbyServer s = serverOf(sp[0]);
        EmbyApi api = new EmbyApi(s.baseUrl, s.token, s.userId, s.id);
        EmbyItem item = api.getItem(sp[1]);

        StringBuilder from = new StringBuilder();
        StringBuilder urls = new StringBuilder();
        if (item.isSeries()) {
            List<EmbyItem> seasons = api.seasons(item.id);
            StringBuilder transFrom = new StringBuilder("转码");
            StringBuilder transUrls = new StringBuilder();
            for (EmbyItem season : seasons) {
                if (from.length() > 0) {
                    from.append("$$$");
                    urls.append("$$$");
                    transUrls.append("$$$");
                }
                from.append(season.name);
                StringBuilder eps = new StringBuilder();
                for (EmbyItem ep : api.episodes(item.id, season.id)) {
                    if (eps.length() > 0) eps.append("#");
                    String epName = "第" + ep.indexNumber + "集";
                    if (!TextUtils.isEmpty(ep.name) && !ep.name.equals(epName)) epName += " " + ep.name;
                    eps.append(epName).append("$emby:").append(s.id).append(":").append(ep.id);
                }
                urls.append(eps);
                transUrls.append(eps);
            }
            from.append("$$$").append(transFrom);
            urls.append("$$$").append(transUrls);
        } else {
            from.append("Emby$$$转码");
            String ep = "正片$emby:" + s.id + ":" + item.id;
            urls.append(ep).append("$$$").append(ep);
        }

        JSONObject v = new JSONObject();
        v.put("vod_id", "emby:" + s.id + ":" + item.id);
        v.put("vod_name", item.name);
        v.put("vod_pic", api.imageUrl(item.id, item.imageTag, 400));
        v.put("type_name", item.isSeries() ? "电视剧" : "电影");
        v.put("vod_year", item.productionYear > 0 ? String.valueOf(item.productionYear) : "");
        v.put("vod_area", "");
        v.put("vod_director", item.directorText());
        v.put("vod_actor", item.actorText());
        v.put("vod_content", item.overview);
        v.put("vod_remarks", remarkOf(item));
        v.put("vod_play_from", from.toString());
        v.put("vod_play_url", urls.toString());
        if (!TextUtils.isEmpty(item.backdropTag))
            v.put("emby_backdrop", api.backdropUrl(item.id, 1280));
        if (!item.ratingText().isEmpty()) v.put("emby_rating", item.ratingText());
        if (item.resumeMs() > 0) v.put("emby_resume_ms", item.resumeMs());
        if (!item.genres.isEmpty()) v.put("emby_genres", TextUtils.join("、", item.genres));
        JSONArray similar = new JSONArray();
        for (EmbyItem it : api.similar(item.id)) {
            if (similar.length() >= 20) break;
            JSONObject o = new JSONObject();
            o.put("id", "emby:" + s.id + ":" + it.id);
            o.put("name", it.name);
            o.put("pic", api.imageUrl(it.id, it.imageTag, 400));
            similar.put(o);
        }
        v.put("emby_similar", similar);

        JSONObject r = new JSONObject();
        JSONArray list = new JSONArray();
        list.put(v);
        r.put("list", list);
        return r.toString();
    }

    // ---------- player ----------

    private static String subFormat(String url) {
        String u = url == null ? "" : url.toLowerCase();
        if (u.contains(".ass") || u.contains(".ssa")) return "ass";
        if (u.contains(".vtt")) return "vtt";
        return "srt";
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String[] sp = splitId(id);
        EmbyStore.EmbyServer s = serverOf(sp[0]);
        EmbyApi api = new EmbyApi(s.baseUrl, s.token, s.userId, s.id);
        boolean forceTranscode = flag != null && (flag.contains("转码") || flag.contains("transcode"));

        String playUrl;
        JSONArray subs = new JSONArray();
        String sessionId = "";
        if (forceTranscode) {
            List<EmbyApi.PlayOption> options = api.playbackOptions(sp[1], "");
            EmbyApi.PlayOption pick = null;
            for (EmbyApi.PlayOption o : options) if (!o.direct) {
                pick = o;
                break;
            }
            if (pick == null) pick = options.get(0);
            playUrl = pick.url;
        } else {
            EmbyApi.PlayTarget t = api.playback(sp[1], "");
            playUrl = t.url;
            sessionId = t.playSessionId;
            for (EmbyApi.Subtitle st : t.subtitles) {
                JSONObject o = new JSONObject();
                o.put("name", st.title);
                o.put("url", st.url);
                o.put("lang", "");
                o.put("format", subFormat(st.url));
                subs.put(o);
            }
        }
        if (TextUtils.isEmpty(playUrl)) throw new Exception("没拿到播放地址");

        JSONObject header = new JSONObject();
        header.put("X-Emby-Authorization", api.authHeader());
        JSONObject r = new JSONObject();
        r.put("parse", 0);
        r.put("playUrl", playUrl);
        r.put("flag", flag == null ? "" : flag);
        r.put("header", header);
        r.put("subs", subs);
        r.put("emby_item_id", sp[1]);
        r.put("emby_play_session_id", sessionId);
        return r.toString();
    }

    // ---------- search ----------

    /**
     * 按 siteKey 选服：key 格式 "emby:&lt;serverId&gt;"（层1 每服一个虚拟源，JarLoader 已把 key
     * 透传给 spider.siteKey）；格式不对或服不存在/未登录 → 回退 active()。
     */
    private EmbyStore.EmbyServer searchServer() {
        String sk = siteKey;
        if (!TextUtils.isEmpty(sk) && sk.startsWith("emby:")) {
            EmbyStore.EmbyServer s = EmbyStore.getServer(sk.substring(5));
            if (s != null && s.isLogin()) return s;
        }
        return EmbyStore.active();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        EmbyStore.EmbyServer s = searchServer();
        if (s == null || !s.isLogin()) throw new Exception(NEED_LOGIN);
        EmbyApi api = new EmbyApi(s.baseUrl, s.token, s.userId, s.id);
        EmbyApi.ItemPage p = api.search(key, 0, LIMIT);
        JSONArray list = new JSONArray();
        for (EmbyItem it : p.items) {
            JSONObject v = vodJson(api, s, it);
            v.put("vod_remarks", "Emby·" + s.displayName());
            list.put(v);
        }
        JSONObject r = new JSONObject();
        r.put("list", list);
        r.put("page", 1);
        r.put("pagecount", 1);
        r.put("limit", LIMIT);
        r.put("total", p.total);
        return r.toString();
    }

    @Override
    public boolean isVideoFormat(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String u = url.toLowerCase();
        return u.contains("m3u8") || u.contains("/videos/") || u.contains("/video/") || u.endsWith(".mp4");
    }
}
