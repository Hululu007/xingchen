package com.fongmi.android.tv.api.music;

import com.fongmi.android.tv.bean.music.LyricVersion;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
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
        HttpURLConnection connection = null;
        try {
            String query = "title=" + URLEncoder.encode(title.trim(), "UTF-8")
                    + "&artist=" + URLEncoder.encode(artist == null ? "" : artist.trim(), "UTF-8")
                    + "&duration=" + Math.max(0, durationSec);
            URL url = new URL(baseUrl + (baseUrl.contains("?") ? "&" : "?") + query);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(25000);
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            if (code != 200) return response;
            InputStream input = connection.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
            reader.close();
            return parse(builder.toString());
        } catch (Throwable ignored) {
            return response;
        } finally {
            if (connection != null) connection.disconnect();
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
