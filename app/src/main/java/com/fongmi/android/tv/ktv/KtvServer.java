package com.fongmi.android.tv.ktv;

import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 点歌服务器协议（照咕噜 /v2/ 定案扒的）：目录接口全明文 JSON；
 * resolve 手动跟跳转，终点若是 {iv,data} 加密体则明确报「加密」，不硬解。
 * 全部方法同步阻塞，必须在后台线程调用。
 */
public class KtvServer {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    public static class ResolveResult {
        public boolean ok;
        public boolean encrypted;
        public String url = "";
        public String error = "";
    }

    public static class Category {
        public String id = "";
        public String name = "";
        public long count;
    }

    private final String base;

    public KtvServer(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
    }

    public boolean ready() {
        return base.startsWith("http");
    }

    private String get(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
        try {
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(in);
            if (code >= 400) throw new IllegalStateException("HTTP " + code);
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** 容忍各种信封：根数组，或 list/data/songs/items/results 里的数组。 */
    private static JsonArray findArray(JsonElement root) {
        if (root == null) return new JsonArray();
        if (root.isJsonArray()) return root.getAsJsonArray();
        if (root.isJsonObject()) {
            JsonObject o = root.getAsJsonObject();
            for (String k : new String[]{"list", "data", "songs", "items", "results", "rows", "singers"}) {
                if (o.has(k) && o.get(k).isJsonArray()) return o.getAsJsonArray(k);
                if (o.has(k) && o.get(k).isJsonObject()) {
                    JsonArray nested = findArray(o.get(k));
                    if (nested.size() > 0) return nested;
                }
            }
        }
        return new JsonArray();
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static List<KtvSong> parseSongs(JsonElement root) {
        List<KtvSong> out = new ArrayList<>();
        for (JsonElement el : findArray(root)) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            KtvSong s = new KtvSong();
            s.origin = "server";
            s.number = str(o, "number");
            s.name = str(o, "name");
            if (s.name.isEmpty()) s.name = str(o, "title");
            s.singer = str(o, "singer_names");
            if (s.singer.isEmpty()) s.singer = str(o, "singer");
            s.acronym = str(o, "acronym");
            s.mtvOrVcd = str(o, "mtv_or_vcd");
            s.languageId = str(o, "language_id");
            s.typeId = str(o, "type_id");
            s.pic = str(o, "pic");
            if (!s.name.isEmpty() || !s.number.isEmpty()) out.add(s);
        }
        return out;
    }

    public List<Category> languages() throws Exception {
        JsonObject root = JsonParser.parseString(get("/v2/categories")).getAsJsonObject();
        List<Category> out = new ArrayList<>();
        if (root.has("languages")) {
            for (JsonElement el : root.getAsJsonArray("languages")) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                Category c = new Category();
                c.id = str(o, "id");
                c.name = str(o, "name");
                try {
                    c.count = o.has("count") ? o.get("count").getAsLong() : 0;
                } catch (Throwable ignored) {
                }
                out.add(c);
            }
        }
        return out;
    }

    public List<KtvSong> hot(int page) throws Exception {
        return parseSongs(JsonParser.parseString(get("/v2/hot?page=" + page)));
    }

    public List<KtvSong> browse(int page, String language, String type) throws Exception {
        StringBuilder p = new StringBuilder("/v2/browse?page=").append(page).append("&sort=hot");
        if (language != null && !language.isEmpty()) p.append("&language=").append(enc(language));
        if (type != null && !type.isEmpty()) p.append("&type=").append(enc(type));
        return parseSongs(JsonParser.parseString(get(p.toString())));
    }

    public List<KtvSong> search(int page, String q) throws Exception {
        return parseSongs(JsonParser.parseString(get("/v2/search?page=" + page + "&q=" + enc(q))));
    }

    public List<String> singers(int page, String letter) throws Exception {
        String path = "/v2/singers?page=" + page + (letter == null || letter.isEmpty() ? "" : "&letter=" + enc(letter));
        List<String> out = new ArrayList<>();
        for (JsonElement el : findArray(JsonParser.parseString(get(path)))) {
            if (el.isJsonObject()) {
                String n = str(el.getAsJsonObject(), "name");
                if (!n.isEmpty()) out.add(n);
            } else if (el.isJsonPrimitive()) {
                out.add(el.getAsString());
            }
        }
        return out;
    }

    public List<String> hotSingers(int page) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonElement el : findArray(JsonParser.parseString(get("/v2/singers/hot?page=" + page)))) {
            if (el.isJsonObject()) {
                String n = str(el.getAsJsonObject(), "name");
                if (!n.isEmpty()) out.add(n);
            } else if (el.isJsonPrimitive()) {
                out.add(el.getAsString());
            }
        }
        return out;
    }

    public List<KtvSong> singerSongs(int page, String name) throws Exception {
        return parseSongs(JsonParser.parseString(get("/v2/singer?page=" + page + "&name=" + enc(name))));
    }

    /** 连通测试：目录接口能通就算通。 */
    public boolean ping() {
        try {
            get("/v2/categories");
            return true;
        } catch (Throwable t) {
            SpiderDebug.log("ktv", "server ping failed: %s", String.valueOf(t));
            return false;
        }
    }

    public ResolveResult resolve(String number) {
        ResolveResult r = new ResolveResult();
        String url = base + "/v2/resolve?number=" + enc(number);
        try {
            for (int hop = 0; hop <= 4; hop++) {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null || loc.isEmpty()) {
                        r.error = "服务器跳转丢了地址";
                        return r;
                    }
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }
                String body = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
                conn.disconnect();
                if (code >= 400) {
                    r.error = "服务器回了 " + code;
                    return r;
                }
                return parseResolved(body);
            }
            r.error = "服务器跳转太多次";
            return r;
        } catch (Throwable t) {
            r.error = String.valueOf(t.getMessage());
            return r;
        }
    }

    private ResolveResult parseResolved(String body) {
        ResolveResult r = new ResolveResult();
        String b = body == null ? "" : body.trim();
        if (b.startsWith("http")) {
            r.ok = true;
            r.url = b.split("\\s")[0];
            return r;
        }
        if (b.startsWith("{")) {
            try {
                JsonObject o = JsonParser.parseString(b).getAsJsonObject();
                if (o.has("iv") && o.has("data")) {
                    r.encrypted = true;
                    r.error = "这个服务器的曲库是加密的，暂时唱不了";
                    return r;
                }
                String u = str(o, "url");
                if (u.isEmpty()) u = str(o, "play_url");
                if (u.startsWith("http")) {
                    r.ok = true;
                    r.url = u;
                    return r;
                }
            } catch (Throwable ignored) {
            }
        }
        r.error = "没认出服务器给的地址";
        return r;
    }
}
