package com.fongmi.android.tv.ktv;

import android.util.Base64;

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
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌词三源（照咕噜的顺序）：酷狗歌词为主，lrclib 兜底，lrcmux 再兜底。
 * 行歌词 LRC；lrclib 部分曲目带逐字（增强格式）会解析出逐字时间。
 * 全部同步阻塞，必须在后台线程调用。
 */
public class KtvLyrics {

    public static class Word {
        public long atMs;
        public String text = "";
    }

    public static class Line {
        public long atMs;
        public String text = "";
        public List<Word> words; // 可能为空（只有整行时间）
    }

    public static class Result {
        public List<Line> lines = new ArrayList<>();
        public String source = "";
        public boolean wordTimed;
    }

    private static String httpGet(String url, String referer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0");
        if (referer != null) conn.setRequestProperty("Referer", referer);
        try {
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) return "";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static Result fetch(String name, String singer, long durationMs) {
        String kw = ((singer == null ? "" : singer) + " " + (name == null ? "" : name)).trim();
        try {
            Result r = fromKugou(kw, durationMs);
            if (r != null && !r.lines.isEmpty()) return r;
        } catch (Throwable t) {
            SpiderDebug.log("ktv", "lyrics kugou failed: %s", String.valueOf(t));
        }
        try {
            Result r = fromLrclib(name, singer);
            if (r != null && !r.lines.isEmpty()) return r;
        } catch (Throwable t) {
            SpiderDebug.log("ktv", "lyrics lrclib failed: %s", String.valueOf(t));
        }
        try {
            Result r = fromLrcmux(name, singer, durationMs);
            if (r != null && !r.lines.isEmpty()) return r;
        } catch (Throwable t) {
            SpiderDebug.log("ktv", "lyrics lrcmux failed: %s", String.valueOf(t));
        }
        return new Result();
    }

    private static Result fromKugou(String keyword, long durationMs) throws Exception {
        String search = httpGet("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=" + enc(keyword)
                + "&lrctxt=1" + (durationMs > 0 ? "&timelength=" + durationMs : ""), "https://www.kugou.com/");
        JsonObject sroot = JsonParser.parseString(search).getAsJsonObject();
        JsonArray cands = sroot.has("candidates") ? sroot.getAsJsonArray("candidates") : new JsonArray();
        if (cands.size() == 0) return null;
        JsonObject first = cands.get(0).getAsJsonObject();
        String id = first.has("id") ? first.get("id").getAsString() : "";
        String key = first.has("accesskey") ? first.get("accesskey").getAsString() : "";
        if (id.isEmpty() || key.isEmpty()) return null;
        String dl = httpGet("https://lyrics.kugou.com/download?ver=1&client=pc&id=" + enc(id)
                + "&accesskey=" + enc(key) + "&fmt=lrc&charset=utf8", "https://www.kugou.com/");
        JsonObject droot = JsonParser.parseString(dl).getAsJsonObject();
        if (!droot.has("content")) return null;
        String lrc = new String(Base64.decode(droot.get("content").getAsString(), Base64.DEFAULT), StandardCharsets.UTF_8);
        Result r = new Result();
        r.lines = parseLrc(lrc);
        r.source = "酷狗歌词";
        return r;
    }

    private static Result fromLrclib(String name, String singer) throws Exception {
        String json = httpGet("https://lrclib.net/api/search?track_name=" + enc(name) + "&artist_name=" + enc(singer), null);
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        Result best = null;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String synced = o.has("syncedLyrics") && !o.get("syncedLyrics").isJsonNull() ? o.get("syncedLyrics").getAsString() : "";
            if (!synced.isEmpty()) {
                Result r = new Result();
                r.lines = parseLrc(synced);
                r.source = "lrclib";
                if (!r.lines.isEmpty()) return r;
            }
            String plain = o.has("plainLyrics") && !o.get("plainLyrics").isJsonNull() ? o.get("plainLyrics").getAsString() : "";
            if (best == null && plain.contains("<") && plain.contains(">")) {
                Result r = new Result();
                r.lines = parseEnhanced(plain);
                r.source = "lrclib逐字";
                r.wordTimed = r.lines.stream().anyMatch(l -> l.words != null && !l.words.isEmpty());
                if (!r.lines.isEmpty()) best = r;
            }
        }
        return best;
    }

    private static Result fromLrcmux(String name, String singer, long durationMs) throws Exception {
        String url = "https://lrcmux.dev/get?duration=" + (durationMs > 0 ? durationMs / 1000 : 0)
                + "&artist=" + enc(singer) + "&title=" + enc(name);
        String body = httpGet(url, null);
        if (body == null || body.isEmpty() || body.trim().startsWith("{")) return null;
        Result r = new Result();
        r.lines = parseLrc(body);
        r.source = "lrcmux";
        return r;
    }

    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?]");
    private static final Pattern WORD_STAMP = Pattern.compile("<(\\d{1,2}):(\\d{2})\\.(\\d{1,3})>");

    private static long stampMs(Matcher m) {
        long min = Long.parseLong(m.group(1));
        long sec = Long.parseLong(m.group(2));
        String frac = m.group(3) == null ? "0" : m.group(3);
        long ms = Long.parseLong((frac + "000").substring(0, 3));
        return (min * 60 + sec) * 1000 + ms;
    }

    public static List<Line> parseLrc(String lrc) {
        List<Line> out = new ArrayList<>();
        if (lrc == null) return out;
        for (String raw : lrc.split("\\R")) {
            Matcher m = STAMP.matcher(raw);
            List<Long> times = new ArrayList<>();
            int last = 0;
            while (m.find()) {
                times.add(stampMs(m));
                last = m.end();
            }
            if (times.isEmpty()) continue;
            String text = raw.substring(last).trim();
            for (Long t : times) {
                Line l = new Line();
                l.atMs = t;
                l.text = text;
                out.add(l);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.atMs, b.atMs));
        out.removeIf(l -> l.text.isEmpty());
        return out;
    }

    /** lrclib 的逐字增强格式：一行内 <mm:ss.mmm>字<mm:ss.mmm>字… */
    public static List<Line> parseEnhanced(String text) {
        List<Line> out = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            Matcher m = WORD_STAMP.matcher(raw);
            List<Word> words = new ArrayList<>();
            long lineAt = -1;
            int prevEnd = 0;
            long prevAt = -1;
            while (m.find()) {
                if (prevAt >= 0) {
                    Word w = new Word();
                    w.atMs = prevAt;
                    w.text = raw.substring(prevEnd, m.start());
                    words.add(w);
                }
                prevAt = stampMs(m);
                if (lineAt < 0) lineAt = prevAt;
                prevEnd = m.end();
            }
            if (prevAt >= 0) {
                Word w = new Word();
                w.atMs = prevAt;
                w.text = raw.substring(prevEnd);
                words.add(w);
            }
            if (words.isEmpty()) continue;
            Line l = new Line();
            l.atMs = lineAt;
            StringBuilder sb = new StringBuilder();
            for (Word w : words) sb.append(w.text);
            l.text = sb.toString().trim();
            l.words = words;
            if (!l.text.isEmpty()) out.add(l);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.atMs, b.atMs));
        return out;
    }
}
