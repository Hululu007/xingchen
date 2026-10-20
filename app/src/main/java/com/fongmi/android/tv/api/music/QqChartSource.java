package com.fongmi.android.tv.api.music;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicSource;
import com.fongmi.quickjs.bean.Req;
import com.fongmi.quickjs.utils.Connect;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.Response;

/**
 * QQ 音乐榜单壳内源（潇洒哥 2026-10-20 拍板：榜单只要酷我跟 QQ 两家）。
 * 榜单走 QQ 自家公开接口免登录直连（2026-10-20 本机探活实证，参数不许自由发挥）：
 * 列表＝musicu 网关 ToplistInfoServer.GetAll（巅峰/地区/特色/全球共 30 张榜）；
 * 详情＝fcg_v8_toplist_cp.fcg?topid=（热歌榜实测 300 首、飙升榜 100 首）。
 * 本源只供榜单：不进搜索排（searchableSites 空）、不搜歌、不取链。
 * 榜里的歌拼成与歌单导入一致的落雪 tx 形态（platform="lx"、id="lx:tx:{songmid}"、
 * rawJson={_platform:"tx",_item:{songmid}}），播放走现成落雪链——前提是装了
 * 支持 tx 的落雪脚本，没装时与导入的 QQ 歌一样取不到链（走既有失败路径）。
 * 全部方法阻塞，只能在子线程调（MusicRepository 的调用方已在子线程）。
 */
public class QqChartSource implements MusicSource {

    /** 榜单路由键：只用于 MusicChart.platform 与合成站的 platform，不与曲目 platform 混用 */
    public static final String PLATFORM = "qq";

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final int TIMEOUT = 20000;
    private static final String REFERER = "https://y.qq.com/";

    @Override
    public String platform() {
        return PLATFORM;
    }

    /** 合成站：只为让榜单聚合（按站遍历 charts）能带上 QQ；插件管理走 MusicSourceManager，不受影响 */
    @Override
    public List<MusicSite> sites() {
        List<MusicSite> list = new ArrayList<>();
        list.add(new MusicSite(PLATFORM, "QQ音乐", "", ""));
        return list;
    }

    /** 只供榜单，不进搜索横排 */
    @Override
    public List<MusicSite> searchableSites() {
        return Collections.emptyList();
    }

    @Override
    public List<MusicTrack> search(String keyword) {
        return Collections.emptyList();
    }

    @Override
    public List<MusicTrack> tracks(MusicSite site) {
        return Collections.emptyList();
    }

    @Override
    public List<MusicChart> charts(MusicSite site) throws Exception {
        if (site == null || !PLATFORM.equals(site.platform)) return Collections.emptyList();
        String data = "{\"toplist\":{\"module\":\"musicToplist.ToplistInfoServer\",\"method\":\"GetAll\",\"param\":{}}}";
        String url = "https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data=" + enc(data);
        JsonObject root = parse(httpGet(url));
        List<MusicChart> out = new ArrayList<>();
        JsonObject toplist = root.has("toplist") && root.get("toplist").isJsonObject() ? root.getAsJsonObject("toplist") : null;
        JsonObject body = toplist != null && toplist.has("data") && toplist.get("data").isJsonObject() ? toplist.getAsJsonObject("data") : null;
        JsonArray groups = body != null && body.has("group") && body.get("group").isJsonArray() ? body.getAsJsonArray("group") : null;
        if (groups == null) return out;
        for (JsonElement g : groups) {
            if (!g.isJsonObject()) continue;
            JsonArray list = g.getAsJsonObject().has("toplist") && g.getAsJsonObject().get("toplist").isJsonArray()
                    ? g.getAsJsonObject().getAsJsonArray("toplist") : null;
            if (list == null) continue;
            for (JsonElement el : list) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                long topId = optLong(o, "topId");
                String title = optStr(o, "title");
                if (topId <= 0 || title.isEmpty()) continue;
                MusicChart c = new MusicChart();
                c.platform = PLATFORM;
                c.id = String.valueOf(topId);
                c.name = title;
                c.cover = optStr(o, "headPicUrl");
                out.add(c);
            }
        }
        return out;
    }

    @Override
    public List<MusicTrack> chartTracks(MusicChart chart) throws Exception {
        if (chart == null || TextUtils.isEmpty(chart.id)) return Collections.emptyList();
        String url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg?g_tk=5381&uin=0&format=json"
                + "&inCharset=utf-8&outCharset=utf-8&notice=0&platform=h5&needNewCode=1&tpl=3&page=detail&type=top&topid=" + enc(chart.id);
        JsonObject root = parse(httpGet(url));
        List<MusicTrack> out = new ArrayList<>();
        JsonArray songlist = root.has("songlist") && root.get("songlist").isJsonArray() ? root.getAsJsonArray("songlist") : null;
        if (songlist == null) return out;
        for (JsonElement el : songlist) {
            if (!el.isJsonObject()) continue;
            JsonObject wrap = el.getAsJsonObject();
            if (!wrap.has("data") || !wrap.get("data").isJsonObject()) continue;
            JsonObject o = wrap.getAsJsonObject("data");
            String mid = optStr(o, "songmid");
            if (mid.isEmpty()) continue;
            // 与 PlaylistImporter/LxPlatformSearch 的 QQ 歌同形态：落雪 tx 链取链/歌词
            MusicTrack t = new MusicTrack();
            t.platform = LxMusicSource.PLATFORM;
            t.id = "lx:" + LxPlatformSearch.QQ + ":" + mid;
            t.title = optStr(o, "songname");
            StringBuilder singer = new StringBuilder();
            if (o.has("singer") && o.get("singer").isJsonArray()) {
                for (JsonElement s : o.getAsJsonArray("singer")) {
                    if (!s.isJsonObject()) continue;
                    if (singer.length() > 0) singer.append("/");
                    singer.append(optStr(s.getAsJsonObject(), "name"));
                }
            }
            t.artist = singer.toString();
            t.album = optStr(o, "albumname");
            t.duration = optLong(o, "interval") * 1000;
            String albumMid = optStr(o, "albummid");
            t.cover = albumMid.isEmpty() ? "" : "https://y.qq.com/music/photo_new/T002R300x300M000" + albumMid + ".jpg";
            JsonObject item = new JsonObject();
            item.addProperty("songmid", mid);
            JsonObject raw = new JsonObject();
            raw.addProperty("_platform", LxPlatformSearch.QQ);
            raw.add("_item", item);
            t.rawJson = raw.toString();
            out.add(t);
        }
        return out;
    }

    @Override
    public String playUrl(MusicTrack track, int quality) {
        return null;
    }

    @Override
    public String lyric(MusicTrack track) {
        return null;
    }

    /* ---------------- 网络（与 PlaylistImporter 同出口：quickjs Connect 吃代理/DoH/节点定向） ---------------- */

    private static String httpGet(String url) throws Exception {
        Req req = req(url);
        try (Response res = Connect.to(url, req).execute()) {
            if (!res.isSuccessful()) throw new Exception("http " + res.code());
            byte[] bytes = res.body() != null ? res.body().bytes() : new byte[0];
            return new String(bytes, req.getCharset());
        }
    }

    private static Req req(String url) throws Exception {
        JSONObject reqJson = new JSONObject();
        reqJson.put("method", "GET");
        JSONObject headers = new JSONObject();
        headers.put("User-Agent", UA);
        headers.put("Referer", REFERER);
        reqJson.put("headers", headers);
        reqJson.put("timeout", TIMEOUT);
        reqJson.put("redirect", 1);
        return Req.objectFrom(reqJson.toString());
    }

    private static JsonObject parse(String text) throws Exception {
        if (text == null) throw new Exception("empty body");
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    private static String optStr(JsonObject obj, String name) {
        if (obj == null || !obj.has(name) || obj.get(name).isJsonNull()) return "";
        try {
            return obj.get(name).getAsString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static long optLong(JsonObject obj, String name) {
        if (obj == null || !obj.has(name) || obj.get(name).isJsonNull()) return 0;
        try {
            return obj.get(name).getAsLong();
        } catch (Exception e) {
            return 0;
        }
    }
}
