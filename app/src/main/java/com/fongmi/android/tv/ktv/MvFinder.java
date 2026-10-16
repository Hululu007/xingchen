package com.fongmi.android.tv.ktv;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
* 给点播源的纯音频歌配 MV：按歌名+歌手依次问网易云、Bilibili、QQ音乐，
* 拿到一个能播的 mp4 地址就停。全是免登录公开口，地址带时效、现取现播。
* 配不到返回 null，调用方静默走封面兜底。
*/
public class MvFinder {

public interface Callback {
void onResult(String mvUrl, java.util.Map<String, String> headers);
}

private static final String BILI_COOKIE = "buvid3=7A3E1B0C-4D5F-4A6B-9C8D-1E2F3A4B5C6Dinfoc; buvid4=7A3E1B0C-4D5F-4A6B-9C8D-1E2F3A4B5C6D-024101601-AbCdEfGh";

private static java.util.Map<String, String> headers(String referer, String cookie) {
java.util.Map<String, String> h = new java.util.HashMap<>();
h.put("User-Agent", UA);
if (referer != null) h.put("Referer", referer);
if (cookie != null) h.put("Cookie", cookie);
return h;
}

private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

public static void find(String title, String singer, Callback cb) {
new Thread(() -> {
String url = null;
java.util.Map<String, String> hd = null;
try {
url = fromNetease(title, singer);
KtvLog.add("配MV 网易云：" + (url == null ? "没配到" : "配到"));
if (url != null) hd = headers("https://music.163.com/", null);
} catch (Throwable t) {
KtvLog.add("配MV 网易云出错：" + t);
}
if (url == null) {
try {
url = fromBilibili(title, singer);
KtvLog.add("配MV B站：" + (url == null ? "没配到" : "配到"));
if (url != null) hd = headers("https://www.bilibili.com/", BILI_COOKIE);
} catch (Throwable t) {
KtvLog.add("配MV B站出错：" + t);
}
}
if (url == null) {
try {
url = fromQQ(title, singer);
KtvLog.add("配MV QQ：" + (url == null ? "没配到" : "配到"));
if (url != null) hd = headers("https://y.qq.com/", null);
} catch (Throwable t) {
KtvLog.add("配MV QQ出错：" + t);
}
}
cb.onResult(url, hd);
}, "ktv-mv").start();
}

// ---------- 网易云 ----------

private static String fromNetease(String title, String singer) throws Exception {
String q = URLEncoder.encode((title + " " + singer).trim(), "UTF-8");
String body = httpGet("https://music.163.com/api/search/get?type=1004&s=" + q + "&offset=0&total=true&limit=8",
"https://music.163.com/", null);
JsonObject root = JsonParser.parseString(body).getAsJsonObject();
JsonArray mvs = root.getAsJsonObject("result").getAsJsonArray("mvs");
if (mvs == null) return null;
long bestId = 0;
int best = 0;
for (JsonElement e: mvs) {
JsonObject mv = e.getAsJsonObject();
long id = mv.has("id")? mv.get("id").getAsLong(): 0;
if (id == 0) continue;
int score = score(str(mv, "name"), str(mv, "artistName"), title, singer);
if (score > best) {
best = score;
bestId = id;
}
}
if (bestId == 0 || best < 70) return null;
String detail = httpGet("https://music.163.com/api/mv/detail?id=" + bestId, "https://music.163.com/", null);
JsonObject data = JsonParser.parseString(detail).getAsJsonObject().getAsJsonObject("data");
if (data == null ||!data.has("brs") ||!data.get("brs").isJsonObject()) return null;
JsonObject brs = data.getAsJsonObject("brs");
for (String res: new String[]{"720", "480", "1080", "240"}) {
if (brs.has(res) &&!brs.get(res).isJsonNull()) {
String u = brs.get(res).getAsString();
if (u!= null && u.startsWith("http")) return u;
}
}
return null;
}

// ---------- Bilibili ----------

private static String fromBilibili(String title, String singer) throws Exception {
String cookie = BILI_COOKIE;
String q = URLEncoder.encode((title + " " + singer + " MV").trim(), "UTF-8");
String body = httpGet("https://api.bilibili.com/x/web-interface/search/type?search_type=video&page=1&page_size=10&keyword=" + q,
"https://www.bilibili.com/", cookie);
JsonObject root = JsonParser.parseString(body).getAsJsonObject();
if (!root.has("data") ||!root.get("data").isJsonObject()) return null;
JsonArray list = root.getAsJsonObject("data").getAsJsonArray("result");
if (list == null) return null;
String want = norm(title);
String bestBvid = null;
int best = 0;
for (JsonElement e: list) {
JsonObject v = e.getAsJsonObject();
String t = str(v, "title").replaceAll("<[^>]+>", "");
String low = t.toLowerCase(Locale.ROOT);
if (low.contains("翻唱") || low.contains("鼓谱") || low.contains("合唱") || low.contains("钢琴")
|| low.contains("吉他") || low.contains("教学") || low.contains("架子鼓") || low.contains("贝斯")
|| low.contains("reaction") || low.contains("混剪") || low.contains("鬼畜")) continue;
int score = 0;
if (norm(t).contains(want) &&!want.isEmpty()) score += 40;
if (low.contains("mv")) score += 10;
int sec = parseBiliDuration(str(v, "duration"));
if (sec >= 180 && sec <= 330) score += 15;
else if (sec >= 120 && sec <= 420) score += 5;
long play = v.has("play") && v.get("play").isJsonPrimitive()? v.get("play").getAsLong(): 0;
if (play > 1_000_000) score += 10;
else if (play > 100_000) score += 5;
if (score > best) {
best = score;
bestBvid = str(v, "bvid");
}
}
if (bestBvid == null || bestBvid.isEmpty() || best < 50) return null;
return biliPlayUrl(bestBvid, cookie);
}

private static String biliPlayUrl(String bvid, String cookie) throws Exception {
String pages = httpGet("https://api.bilibili.com/x/player/pagelist?bvid=" + bvid, "https://www.bilibili.com/", cookie);
JsonArray parr = JsonParser.parseString(pages).getAsJsonObject().getAsJsonArray("data");
if (parr == null || parr.size() == 0) return null;
long cid = parr.get(0).getAsJsonObject().get("cid").getAsLong();
String play = httpGet("https://api.bilibili.com/x/player/playurl?bvid=" + bvid + "&cid=" + cid + "&fnval=0",
"https://www.bilibili.com/", cookie);
JsonObject pdata = JsonParser.parseString(play).getAsJsonObject().getAsJsonObject("data");
if (pdata == null ||!pdata.has("durl")) return null;
JsonArray durl = pdata.getAsJsonArray("durl");
if (durl == null || durl.size() == 0) return null;
String u = str(durl.get(0).getAsJsonObject(), "url");
return u.startsWith("http")? u: null;
}

// ---------- 伴奏MV（B站的卡拉OK/伴奏版视频，画面+伴奏一体的那种） ----------

public static void findAccMv(String title, String singer, Callback cb) {
new Thread(() -> {
String url = null;
try {
url = fromBiliAccMv(title, singer);
KtvLog.add("配伴奏MV B站：" + (url == null? "没配到": "配到"));
} catch (Throwable t) {
KtvLog.add("配伴奏MV 出错：" + t);
}
cb.onResult(url, url == null ? null : headers("https://www.bilibili.com/", BILI_COOKIE));
}, "ktv-accmv").start();
}

private static String fromBiliAccMv(String title, String singer) throws Exception {
String cookie = BILI_COOKIE;
String q = URLEncoder.encode((title + " " + singer + " 伴奏").trim(), "UTF-8");
String body = httpGet("https://api.bilibili.com/x/web-interface/search/type?search_type=video&page=1&page_size=10&keyword=" + q,
"https://www.bilibili.com/", cookie);
JsonObject root = JsonParser.parseString(body).getAsJsonObject();
if (!root.has("data") ||!root.get("data").isJsonObject()) return null;
JsonArray list = root.getAsJsonObject("data").getAsJsonArray("result");
if (list == null) return null;
String want = norm(title);
String bestBvid = null;
int best = 0;
for (JsonElement e: list) {
JsonObject v = e.getAsJsonObject();
String t = str(v, "title").replaceAll("<[^>]+>", "");
String low = t.toLowerCase(Locale.ROOT);
if (low.contains("教学") || low.contains("翻唱") || low.contains("钢琴") || low.contains("吉他")
|| low.contains("古筝") || low.contains("葫芦丝") || low.contains("二胡") || low.contains("唢呐")
|| low.contains("架子鼓") || low.contains("reaction") || low.contains("混剪")) continue;
boolean karaoke = low.contains("伴奏") || low.contains("ktv") || low.contains("卡拉ok") || low.contains("カラオケ");
if (!karaoke) continue;
int score = 50;
if (norm(t).contains(want) &&!want.isEmpty()) score += 40;
int sec = parseBiliDuration(str(v, "duration"));
if (sec >= 120 && sec <= 420) score += 10;
long play = v.has("play") && v.get("play").isJsonPrimitive()? v.get("play").getAsLong(): 0;
if (play > 100_000) score += 5;
if (score > best) {
best = score;
bestBvid = str(v, "bvid");
}
}
if (bestBvid == null || bestBvid.isEmpty() || best < 90) return null;
return biliPlayUrl(bestBvid, cookie);
}

private static int parseBiliDuration(String d) {
try {
String[] parts = d.split(":");
int sec = 0;
for (String p: parts) sec = sec * 60 + Integer.parseInt(p.trim());
return sec;
} catch (Throwable e) {
return 0;
}
}

// ---------- QQ音乐 ----------

private static String fromQQ(String title, String singer) throws Exception {
String q = URLEncoder.encode((title + " " + singer).trim(), "UTF-8");
String body = httpGet("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?t=12&w=" + q + "&p=1&n=5&format=json",
"https://y.qq.com/", null);
JsonObject root = JsonParser.parseString(body).getAsJsonObject();
JsonArray list = root.getAsJsonObject("data").getAsJsonObject("mv").getAsJsonArray("list");
if (list == null) return null;
String bestVid = null;
int best = 0;
for (JsonElement e: list) {
JsonObject mv = e.getAsJsonObject();
int score = score(str(mv, "mv_name"), str(mv, "singer_name"), title, singer);
if (score > best) {
best = score;
bestVid = str(mv, "v_id");
}
}
if (bestVid == null || bestVid.isEmpty() || best < 70) return null;
String data = "{\"comm\":{\"g_tk\":0,\"uin\":0,\"platform\":\"h5\"},\"mvUrl\":{\"module\":\"music.stream.MvUrlProxy\",\"method\":\"GetMvUrls\",\"param\":{\"vids\":[\"" + bestVid + "\"],\"request_type\":10003}}}";
String resp = httpGet("https://u.y.qq.com/cgi-bin/musicu.fcg?data=" + URLEncoder.encode(data, "UTF-8"),
"https://y.qq.com/", null);
JsonObject mvUrl = JsonParser.parseString(resp).getAsJsonObject().getAsJsonObject("mvUrl");
if (mvUrl == null ||!mvUrl.has("data")) return null;
JsonObject byVid = mvUrl.getAsJsonObject("data").getAsJsonObject(bestVid);
if (byVid == null ||!byVid.has("mp4")) return null;
for (JsonElement e: byVid.getAsJsonArray("mp4")) {
JsonObject fmt = e.getAsJsonObject();
if (fmt.has("url") && fmt.get("url").isJsonArray()) {
for (JsonElement u: fmt.getAsJsonArray("url")) {
String s = u.getAsString();
if (s!= null && s.startsWith("http")) return s;
}
}
}
return null;
}

// ---------- 匹配打分（网易云/QQ 共用） ----------

private static int score(String name, String artist, String title, String singer) {
String n = norm(name);
String t = norm(title);
String a = norm(artist);
String s = norm(singer);
int score = 0;
if (!t.isEmpty() && n.equals(t)) score += 60;
else if (!t.isEmpty() && (n.contains(t) || t.contains(n))) score += 40;
if (!s.isEmpty() &&!a.isEmpty() && (a.contains(s) || s.contains(a))) score += 30;
return score;
}

private static String norm(String s) {
if (s == null) return "";
return s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_·・.、,，()（）\\[\\]]", "");
}

private static String str(JsonObject o, String key) {
return o!= null && o.has(key) &&!o.get(key).isJsonNull()? o.get(key).getAsString(): "";
}

private static String httpGet(String url, String referer, String cookie) throws Exception {
HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
conn.setConnectTimeout(8000);
conn.setReadTimeout(8000);
conn.setRequestProperty("User-Agent", UA);
if (referer!= null) conn.setRequestProperty("Referer", referer);
if (cookie!= null) conn.setRequestProperty("Cookie", cookie);
try (InputStream in = conn.getInputStream()) {
byte[] buf = new byte[8192];
StringBuilder sb = new StringBuilder();
int n;
while ((n = in.read(buf)) > 0) {
sb.append(new String(buf, 0, n, "UTF-8"));
if (sb.length() > 1_000_000) break;
}
return sb.toString();
}
}
}
