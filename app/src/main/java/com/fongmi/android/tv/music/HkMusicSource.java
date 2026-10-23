package com.fongmi.android.tv.music;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.hk.HkDetailBridge;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 海阔音乐路由桥：海阔小程序里被识别为音乐的条目进音乐播放器时用。
 * 不进音乐侧源列表（sites 为空），只供视频侧路由建的曲目取链：
 * 走 SiteApi.playerContent 的 hk_ 拦截（HkRouter.play），与视频侧直接播放同一解析。
 * 歌词返回空，走播放器现有按歌名兜底链。
 */
public class HkMusicSource implements MusicSource {

    public static final String PLATFORM = "hk";

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public List<MusicSite> sites() {
        return new ArrayList<>();
    }

    @Override
    public List<MusicTrack> search(String keyword) {
        return new ArrayList<>();
    }

    @Override
    public List<MusicTrack> tracks(MusicSite site) {
        return new ArrayList<>();
    }

    @Override
    public List<MusicChart> charts(MusicSite site) {
        return new ArrayList<>();
    }

    @Override
    public List<MusicTrack> chartTracks(MusicChart chart) {
        return new ArrayList<>();
    }

    @Override
    public String lyric(MusicTrack track) {
        return null;
    }

    @Override
    public String playUrl(MusicTrack track, int quality) throws Exception {
        JsonObject raw = raw(track);
        String rule = str(raw, "rule");
        String line = str(raw, "line");
        String episodeUrl = str(raw, "episodeUrl");
        if (rule.isEmpty() || episodeUrl.isEmpty()) throw new Exception("歌曲缺少来源信息");
        Result play = SiteApi.playerContentParseOnly(HkDetailBridge.siteKey(rule), line, episodeUrl);
        String url = play.getUrl() == null ? "" : play.getUrl().v();
        if (url == null || url.isEmpty()) throw new Exception("解析不到播放地址");
        return url;
    }

    /** 视频侧海阔路由建曲目：rule＝规则名，line＝线路名，episodeUrl＝条目播放地址 */
    public static MusicTrack trackFor(String rule, String line, String episodeUrl, String name, String pic) {
        MusicTrack t = new MusicTrack();
        t.id = PLATFORM + ":" + rule + ":" + episodeUrl;
        t.title = name == null ? "" : name;
        t.artist = "";
        t.cover = pic == null ? "" : pic;
        t.platform = PLATFORM;
        JsonObject raw = new JsonObject();
        raw.addProperty("rule", rule == null ? "" : rule);
        raw.addProperty("line", line == null ? "" : line);
        raw.addProperty("episodeUrl", episodeUrl == null ? "" : episodeUrl);
        t.rawJson = raw.toString();
        return t;
    }

    /** 播放页来源标签用：按 rawJson 的规则名返回 */
    public static String siteNameOf(MusicTrack track) {
        return str(raw(track), "rule");
    }

    private static JsonObject raw(MusicTrack track) {
        try {
            if (track == null || track.rawJson == null || track.rawJson.isEmpty()) return new JsonObject();
            return JsonParser.parseString(track.rawJson).getAsJsonObject();
        } catch (Throwable t) {
            return new JsonObject();
        }
    }

    private static String str(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
        } catch (Throwable t) {
            return "";
        }
    }
}
