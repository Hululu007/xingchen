package com.fongmi.android.tv.api.music;

import com.fongmi.android.tv.bean.music.LyricVersion;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cloudflare 歌词聚合客户端：向 xc-lyric Worker 取一首歌的多平台歌词版本。
 * 只做请求与解析，不改任何现有取词链；接线由词级链统一做。
 * Worker 回包版本平台值与瓜子一致：kugou、netease、qq、kuwo、migu、amll；
 * 本地版本不走 Worker，手机侧自己有。
 * 传输走 catvod OkHttp（与全 App 取数同待遇：DoH／代理选择器／拦截器）——
 * 此前是全 App 唯一裸 HttpURLConnection 的取数口，代理与分应用 VPN 场景下
 * 直连 workers.dev 慢/超时，面板阶段一长期回空（1.4.44 真机实证）。
 */
public class CfLyricClient {

    public static final String DEFAULT_BASE_URL = "https://xc-lyric.1372659842.workers.dev/lyric";

    private String baseUrl = DEFAULT_BASE_URL;

    public void setBaseUrl(String baseUrl) {
        if (baseUrl != null && !baseUrl.trim().isEmpty()) this.baseUrl = baseUrl.trim();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public CfResponse fetch(MusicTrack track) {
        if (track == null) return new CfResponse();
        int durationSec = track.duration > 0 ? (int) (track.duration / 1000) : 0;
        return fetch(track.title, track.artist, durationSec);
    }

    public CfResponse fetch(String title, String artist, int durationSec) {
        CfResponse response = new CfResponse();
        if (title == null || title.trim().isEmpty()) return response;
        String query;
        try {
            query = "title=" + URLEncoder.encode(title.trim(), "UTF-8")
                    + "&artist=" + URLEncoder.encode(artist == null ? "" : artist.trim(), "UTF-8")
                    + "&duration=" + Math.max(0, durationSec);
        } catch (Throwable ignored) {
            return response;
        }
        String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + query;
        // 传输层没拿到应答（超时/非 200/空体）时重试一次：Worker 首发慢是常态，
        // 一发定生死会把面板阶段一长期卡在空。拿到应答（哪怕版本为空）不重试。
        for (int attempt = 1; attempt <= 2; attempt++) {
            String body = fetchOnce(url);
            if (body != null) return parse(body);
            if (attempt < 2) {
                try {
                    Thread.sleep(600);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return response;
                }
            }
        }
        return response;
    }

    /** 拿到像样的应答体返回原文；传输失败/空体/非 JSON（如边缘错误页）返回 null
     * （与「应答了但没版本」区分，OkHttp.string 本身不分状态码、失败回空串）。 */
    private String fetchOnce(String url) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            String body = OkHttp.string(url, headers, 25000);
            if (body == null) return null;
            String trimmed = body.trim();
            return trimmed.startsWith("{") ? body : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public CfResponse parse(String json) {
        CfResponse response = new CfResponse();
        if (json == null || json.trim().isEmpty()) return response;
        try {
            JSONObject root = new JSONObject(json);
            response.title = root.optString("title");
            response.artist = root.optString("artist");
            JSONArray versions = root.optJSONArray("versions");
            if (versions != null) {
                for (int i = 0; i < versions.length(); i++) {
                    JSONObject item = versions.optJSONObject(i);
                    if (item == null) continue;
                    CfVersion version = new CfVersion();
                    version.platform = item.optString("platform");
                    version.label = item.optString("label");
                    version.songTitle = item.optString("songTitle");
                    version.songArtist = item.optString("songArtist");
                    version.format = item.optString("format");
                    version.kind = item.optString("kind");
                    version.lineCount = item.optInt("lineCount");
                    version.match = item.optInt("match");
                    version.lyric = item.optString("lyric");
                    version.translation = item.optString("translation");
                    version.romanization = item.optString("romanization");
                    if (version.lyric != null && !version.lyric.trim().isEmpty()) response.versions.add(version);
                }
            }
            JSONObject bundle = root.optJSONObject("bundle");
            if (bundle != null) {
                response.bundleLyric = bundle.optString("lyric");
                response.bundleYrc = bundle.optString("yrc");
                response.bundleKlyric = bundle.optString("klyric");
                response.bundleTlyric = bundle.optString("tlyric");
                response.bundleRomalrc = bundle.optString("romalrc");
            }
            JSONObject errors = root.optJSONObject("errors");
            if (errors != null) {
                for (String key : new String[]{"kugou", "netease", "qq", "kuwo", "migu", "amll"}) {
                    String value = errors.optString(key);
                    if (value != null && !value.isEmpty()) response.errors.put(key, value);
                }
            }
        } catch (Throwable ignored) {
        }
        return response;
    }

    public static class CfVersion {
        public String platform;
        public String label;
        public String songTitle;
        public String songArtist;
        public String format;
        public String kind;
        public int lineCount;
        public int match;
        public String lyric;
        public String translation;
        public String romanization;

        public boolean isWord() {
            return "word".equals(kind);
        }

        public boolean isLine() {
            return "line".equals(kind);
        }

        public String displayLabel() {
            String kindLabel = isWord() ? "逐字" : isLine() ? "逐行" : "纯文本";
            if (label == null || label.isEmpty()) return kindLabel;
            return label + "·" + kindLabel;
        }

        public LyricVersion toLyricVersion() {
            MusicLyric musicLyric = new MusicLyric(lyric);
            musicLyric.format = format;
            musicLyric.sourceName = label;
            musicLyric.translation = translation;
            musicLyric.romanization = romanization;
            boolean wordTimed = isWord();
            boolean lineTimed = wordTimed || isLine();
            return new LyricVersion(musicLyric, label, wordTimed, lineTimed);
        }
    }

    public static class CfResponse {
        public String title;
        public String artist;
        public List<CfVersion> versions = new ArrayList<>();
        public String bundleLyric;
        public String bundleYrc;
        public String bundleKlyric;
        public String bundleTlyric;
        public String bundleRomalrc;
        public Map<String, String> errors = new HashMap<>();

        public boolean hasVersions() {
            return !versions.isEmpty();
        }

        public List<LyricVersion> toLyricVersions() {
            List<LyricVersion> out = new ArrayList<>();
            for (CfVersion version : versions) out.add(version.toLyricVersion());
            return out;
        }

        public CfVersion firstWordVersion() {
            for (CfVersion version : versions) if (version.isWord()) return version;
            return null;
        }
    }
}
