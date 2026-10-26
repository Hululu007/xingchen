package com.fongmi.android.tv.api.music;

import android.text.TextUtils;
import android.util.Base64;

import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.quickjs.bean.Req;
import com.fongmi.quickjs.utils.Connect;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Response;

/**
 * 落雪平台搜索（壳子侧）：落雪脚本只做地址解析，搜索必须壳子自己实现。
 * 给落雪脚本覆盖的平台用公开网页接口搜歌，产出 MusicTrack：
 * id 带平台前缀防撞，rawJson={_platform, _item:{平台原生标识}} 供 LxBridge 解析用。
 * 歌词优先走这里的平台歌词接口，取不到再走落雪脚本的 lyric 动作。
 * 已实测（2026-10-20 本机）：kw 搜索 ✅ qq 搜索 ✅ kg 搜索 ✅ mg 搜索 ✅；
 * 歌词 kw ✅ qq ✅ wy ✅；wy 搜索按公开 weapi 方案实现，但本机网络 POST 到
 * music.163.com 一直超时，未回包验通；kg 歌词两步接口按公开方案写，未逐条验通。
 * 2026-10-25 复验补正：五平台分页参数全实证（kw pn 0 起／qq p／kg page／mg pageNo
 * 各取第 2 页回不同 20 条）；mg 封面真字段 imgItems、时长接口不给；kw 改 mobi 变体
 * 取 web_albumpic_short 封面；kg 专辑图改并发补齐；wy 本机出口被风控回
 * code 50000005、无回包，dt／al.picUrl 字段映射未获本机回包实证。
 * 2026-10-26 复验补正：wy 的 weapi 方案定死——本机干净出口实测仍回
 * {"code":50000005} 风控码、无 result，旧实现取空静默归零、网易在搜索里
 * 长期无影。主路改 api/cloudsearch/pc 明文 GET（同日实测同词回 194 条，
 * result.songs 与 weapi 同形，name／ar／al.picUrl／dt 字段齐），weapi 留兜底。
 * kw／kg／mg 三端点同日逐字复测全活（kw HIT=818／kg total=437／mg code 000000）。
 */
public class LxPlatformSearch {

    public static final String KW = "kw";
    public static final String QQ = "tx";
    public static final String WY = "wy";
    public static final String KG = "kg";
    public static final String MG = "mg";

    private static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36";

    public static String platformName(String key) {
        switch (key == null ? "" : key) {
            case KW:
                return "酷我音乐";
            case QQ:
                return "QQ音乐";
            case WY:
                return "网易云音乐";
            case KG:
                return "酷狗音乐";
            case MG:
                return "咪咕音乐";
            default:
                return key == null ? "" : key;
        }
    }

    /** 平台是否已实现壳子搜索 */
    public static boolean supported(String key) {
        return KW.equals(key) || QQ.equals(key) || WY.equals(key) || KG.equals(key) || MG.equals(key);
    }

    public static List<MusicTrack> search(String key, String keyword) throws Exception {
        return search(key, keyword, 1);
    }

    /** 分页版（page 从 1 起）：五平台接口都收页码，旧实现全写死第一页、第二页永远拿不到。
     * 失败／第一页空结果在此留痕（平台＋真因）：旧实现各平台回空与抛错一路静默到
     * 搜索页计数器，某家整家消失时日志里什么都查不到（2026-10-26「只剩 QQ」一案）。 */
    public static List<MusicTrack> search(String key, String keyword, int page) throws Exception {
        int p = Math.max(1, page);
        String k = key == null ? "" : key;
        List<MusicTrack> out;
        try {
            switch (k) {
                case KW:
                    out = searchKw(keyword, p);
                    break;
                case QQ:
                    out = searchQq(keyword, p);
                    break;
                case WY:
                    out = searchWy(keyword, p);
                    break;
                case KG:
                    out = searchKg(keyword, p);
                    break;
                case MG:
                    out = searchMg(keyword, p);
                    break;
                default:
                    out = new ArrayList<>();
                    break;
            }
        } catch (Throwable t) {
            SpiderDebug.log("music", "lx plat search fail platform=%s page=%d %s", k, p, errText(t));
            if (t instanceof Exception) throw (Exception) t;
            throw new Exception(t);
        }
        if (out.isEmpty() && p == 1) {
            SpiderDebug.log("music", "lx plat search empty platform=%s", k);
        }
        return out;
    }

    /** 错误日志文本：与 LxMusicSource.errText 同口径——解包到根因取消息，没消息记类名。 */
    private static String errText(Throwable t) {
        Throwable root = t;
        while ((root instanceof java.util.concurrent.ExecutionException
                || root instanceof java.util.concurrent.CompletionException) && root.getCause() != null) {
            root = root.getCause();
        }
        String m = root.getMessage();
        return m != null ? m : root.getClass().getSimpleName();
    }

    /* ---------------- 酷我 kw ---------------- */

    private static List<MusicTrack> searchKw(String keyword, int page) throws Exception {
        // mobi 变体（2026-10-25 本机实证）：老 web_2013 变体回包条目里没有任何封面字段
        // （ARTISTPIC 只在头部出现且为空），mobi 变体同端点同 abslist 结构、条目带
        // web_albumpic_short 专辑图短路径与 DURATION，回包是真 JSON。pn 为 0 起页码。
        String url = "http://search.kuwo.cn/r.s?all=" + enc(keyword)
                + "&ft=music&client=kt&pn=" + (page - 1) + "&rn=20&uid=2574109560&ver=kwplayer_ar_8.5.4.2"
                + "&vipver=1&cluster=0&strategy=2012&rformat=json&encoding=utf8&vermerge=1&mobi=1";
        String text = httpGet(url, null);
        JsonObject root = JsonParser.parseString(fixPseudoJson(text)).getAsJsonObject();
        List<MusicTrack> out = new ArrayList<>();
        if (!root.has("abslist") || !root.get("abslist").isJsonArray()) return out;
        for (JsonElement el : root.getAsJsonArray("abslist")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String rid = optStr(o, "DC_TARGETID");
            if (rid.isEmpty()) continue;
            MusicTrack t = newTrack(KW, rid);
            t.title = htmlUnescape(optStr(o, "SONGNAME"));
            t.artist = htmlUnescape(optStr(o, "ARTIST"));
            t.album = htmlUnescape(optStr(o, "ALBUM"));
            t.duration = optLong(o, "DURATION") * 1000;
            String picShort = optStr(o, "web_albumpic_short");
            if (!picShort.isEmpty()) {
                // 短路径拼图床前缀（与元力KW 插件运行时产出的 artwork 同址，2026-10-25 实证 200 image/jpeg）
                t.cover = "https://img4.kuwo.cn/star/albumcover/" + picShort;
            }
            JsonObject item = new JsonObject();
            item.addProperty("rid", rid);
            item.addProperty("songmid", rid);
            finish(t, item);
            out.add(t);
        }
        return out;
    }

    /* ---------------- QQ tx ---------------- */

    private static List<MusicTrack> searchQq(String keyword, int page) throws Exception {
        String url = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?g_tk=5381&uin=0&format=json"
                + "&inCharset=utf-8&outCharset=utf-8&notice=0&platform=h5&needNewCode=1&w=" + enc(keyword)
                + "&zhidaqu=1&t=0&flag=1&ie=utf-8&sem=1&aggr=0&perpage=20&n=20&p=" + page + "&remoteplace=txt.mqq.all";
        String text = httpGet(url, "https://y.qq.com/");
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        List<MusicTrack> out = new ArrayList<>();
        JsonArray list = digArray(root, "data", "song", "list");
        if (list == null) return out;
        for (JsonElement el : list) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String mid = optStr(o, "songmid");
            if (mid.isEmpty()) continue;
            MusicTrack t = newTrack(QQ, mid);
            t.title = optStr(o, "songname");
            StringBuilder singer = new StringBuilder();
            if (o.has("singer") && o.get("singer").isJsonArray()) {
                for (JsonElement s : o.get("singer").getAsJsonArray()) {
                    if (!s.isJsonObject()) continue;
                    if (singer.length() > 0) singer.append("/");
                    singer.append(optStr(s.getAsJsonObject(), "name"));
                }
            }
            t.artist = singer.toString();
            t.album = optStr(o, "albumname");
            t.duration = optLong(o, "interval") * 1000;
            String albumMid = optStr(o, "albummid");
            if (!albumMid.isEmpty()) {
                t.cover = "https://y.qq.com/music/photo_new/T002R300x300M000" + albumMid + ".jpg";
            }
            JsonObject item = new JsonObject();
            item.addProperty("songmid", mid);
            // 每档真实大小：搜索回包本身带 size128/size320/sizeflac（字节，2026-10-26 本机实测），
            // 原样带进 _item 供音质面板直接读，不再全靠取链后 HEAD 才有数。
            putSizeIfPositive(item, "size128", o, "size128");
            putSizeIfPositive(item, "size320", o, "size320");
            putSizeIfPositive(item, "sizeFlac", o, "sizeflac");
            finish(t, item);
            out.add(t);
        }
        return out;
    }

    private static void putSizeIfPositive(JsonObject item, String itemKey, JsonObject src, String srcKey) {
        long v = optLong(src, srcKey);
        if (v > 0) item.addProperty(itemKey, v);
    }

    /** wy 子对象（l/h/sq/hr）里的 size → _item 每档大小键；子对象不存在就不写（面板保持“未知”）。 */
    private static void putNestedSizeIfPositive(JsonObject item, String itemKey, JsonObject src, String subKey) {
        if (src == null || !src.has(subKey) || !src.get(subKey).isJsonObject()) return;
        putSizeIfPositive(item, itemKey, src.getAsJsonObject(subKey), "size");
    }

    /** QQ 解析要 strMediaMid：搜索接口不给，取链前用歌曲详情接口补（单首一次请求） */
    public static String qqMediaMid(String songmid) {
        JsonObject file = qqFileInfo(songmid);
        return file == null ? "" : optStr(file, "media_mid");
    }

    /**
     * QQ 歌曲详情的 file 对象（与 qqMediaMid 同一接口）：带 media_mid 与每档真实大小
     * （size_128mp3/size_320mp3/size_flac/size_hires）及 Hi-Res 规格（hires_bitdepth/hires_sample）。
     * 音质面板补 Hi-Res 档大小与规格走这里；取不到返回 null，调用方回落其它数据面。
     */
    public static JsonObject qqFileInfo(String songmid) {
        try {
            String url = "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg?songmid=" + enc(songmid)
                    + "&tpl=yqq_song_detail&format=json&g_tk=5381&loginUin=0&hostUin=0"
                    + "&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0";
            String text = httpGet(url, "https://y.qq.com/");
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonArray data = root.has("data") && root.get("data").isJsonArray() ? root.getAsJsonArray("data") : null;
            if (data != null && data.size() > 0 && data.get(0).isJsonObject()) {
                JsonObject file = data.get(0).getAsJsonObject().getAsJsonObject("file");
                if (file != null) return file;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /* ---------------- 网易 wy（主路 cloudsearch/pc 明文 GET；weapi 已被风控打死、留兜底，见类注释） ---------------- */

    private static final String WY_PRESET_KEY = "0CoJUm6Qyw8W8jud";
    private static final String WY_IV = "0102030405060708";
    private static final String WY_PUBKEY =
            "-----BEGIN PUBLIC KEY-----\n"
                    + "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB\n"
                    + "-----END PUBLIC KEY-----";
    private static final String BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static List<MusicTrack> searchWy(String keyword, int page) throws Exception {
        // 主路：api/cloudsearch/pc 明文 GET（2026-10-26 实测活，字段与 weapi 同形）。
        // 回包结构不合（无 result.songs，如被风控换形）或请求抛错才落 weapi 兜底。
        String url = "https://music.163.com/api/cloudsearch/pc?s=" + enc(keyword)
                + "&type=1&offset=" + ((page - 1) * 20) + "&limit=20&total=true";
        List<MusicTrack> out = null;
        try {
            String text = httpGet(url, "https://music.163.com/");
            out = parseWySongs(JsonParser.parseString(text).getAsJsonObject());
        } catch (Throwable t) {
            SpiderDebug.log("music", "wy search pc fail %s", errText(t));
        }
        if (out != null) return out;
        JsonObject payload = new JsonObject();
        payload.addProperty("s", keyword);
        payload.addProperty("type", 1);
        payload.addProperty("limit", 20);
        payload.addProperty("offset", (page - 1) * 20);
        payload.addProperty("total", true);
        String body = weapiBody(payload.toString());
        String text = httpPost("https://music.163.com/weapi/cloudsearch/get/web?csrf_token=", body,
                "https://music.163.com");
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        if (!root.has("result")) {
            // 风控回包只有 {"code":50000005}：把码记下来，别再静默归零装无结果
            String code = root.has("code") ? root.get("code").toString() : "?";
            SpiderDebug.log("music", "wy search weapi no-result code=%s", code);
        }
        List<MusicTrack> fb = parseWySongs(root);
        return fb == null ? new ArrayList<>() : fb;
    }

    /** wy 回包解析（pc 与 weapi 同形）：无 result.songs 时回 null（与「真无结果」的空表分开）。 */
    private static List<MusicTrack> parseWySongs(JsonObject root) {
        List<MusicTrack> out = new ArrayList<>();
        JsonArray songs = digArray(root, "result", "songs");
        if (songs == null) return null;
        for (JsonElement el : songs) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String id = optStr(o, "id");
            if (id.isEmpty()) continue;
            MusicTrack t = newTrack(WY, id);
            t.title = optStr(o, "name");
            StringBuilder singer = new StringBuilder();
            if (o.has("ar") && o.get("ar").isJsonArray()) {
                for (JsonElement s : o.get("ar").getAsJsonArray()) {
                    if (!s.isJsonObject()) continue;
                    if (singer.length() > 0) singer.append("/");
                    singer.append(optStr(s.getAsJsonObject(), "name"));
                }
            }
            t.artist = singer.toString();
            if (o.has("al") && o.get("al").isJsonObject()) {
                JsonObject al = o.getAsJsonObject("al");
                t.album = optStr(al, "name");
                t.cover = optStr(al, "picUrl");
            }
            t.duration = optLong(o, "dt");
            JsonObject item = new JsonObject();
            item.addProperty("songmid", id);
            item.addProperty("id", id);
            // 每档真实大小：回包 l/h/sq/hr 子对象自带 size（字节，2026-10-26 本机实测
            // 《来不及爱你》l=3285621/h=8213987/sq=40834025、hr 不存在），原样带进 _item
            // 供音质面板同口径读——此前 wy 解析没存，网易歌四档大小全“未知”。
            putNestedSizeIfPositive(item, "size128", o, "l");
            putNestedSizeIfPositive(item, "size320", o, "h");
            putNestedSizeIfPositive(item, "sizeFlac", o, "sq");
            putNestedSizeIfPositive(item, "sizeHires", o, "hr");
            finish(t, item);
            out.add(t);
        }
        return out;
    }

    private static String weapiBody(String jsonText) throws Exception {
        byte[] iv = WY_IV.getBytes(StandardCharsets.UTF_8);
        byte[] inner = aesCbc(jsonText.getBytes(StandardCharsets.UTF_8),
                WY_PRESET_KEY.getBytes(StandardCharsets.UTF_8), iv);
        String innerB64 = Base64.encodeToString(inner, Base64.NO_WRAP);
        SecureRandom rnd = new SecureRandom();
        StringBuilder sec = new StringBuilder();
        for (int i = 0; i < 16; i++) sec.append(BASE62.charAt(rnd.nextInt(BASE62.length())));
        String secretKey = sec.toString();
        byte[] outer = aesCbc(innerB64.getBytes(StandardCharsets.UTF_8),
                secretKey.getBytes(StandardCharsets.UTF_8), iv);
        String params = Base64.encodeToString(outer, Base64.NO_WRAP);
        String encSecKey = rsaNoPadding(new StringBuilder(secretKey).reverse().toString());
        return "params=" + URLEncoder.encode(params, "UTF-8")
                + "&encSecKey=" + URLEncoder.encode(encSecKey, "UTF-8");
    }

    private static byte[] aesCbc(byte[] data, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }

    private static String rsaNoPadding(String text) throws Exception {
        String b64 = WY_PUBKEY.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        PublicKey pub = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.decode(b64, Base64.DEFAULT)));
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[128];
        System.arraycopy(raw, 0, padded, 128 - raw.length, raw.length);
        Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, pub);
        byte[] out = cipher.doFinal(padded);
        StringBuilder hex = new StringBuilder();
        for (byte b : out) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /* ---------------- 酷狗 kg ---------------- */

    private static List<MusicTrack> searchKg(String keyword, int page) throws Exception {
        String url = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=" + enc(keyword)
                + "&page=" + page + "&pagesize=20&showtype=1";
        String text = httpGet(url, null);
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        List<MusicTrack> out = new ArrayList<>();
        JsonArray info = digArray(root, "data", "info");
        if (info == null) return out;
        // 专辑图按 album_id 查专辑信息补（2026-10-25 本机实证：专辑接口 data.imgurl 有值、
        // 单次约 1 秒）。旧实现串行只补前 3 张（怕单张 20 秒超时拖死搜索），第 4 张起全空——
        // 改并发补图：去重专辑全补，小池并行＋整批 6 秒总预算，超预算的留空，不拖死搜索。
        java.util.Set<String> albumIds = new java.util.LinkedHashSet<>();
        for (JsonElement el : info) {
            if (!el.isJsonObject()) continue;
            String albumId = optStr(el.getAsJsonObject(), "album_id");
            if (!albumId.isEmpty()) albumIds.add(albumId);
        }
        java.util.Map<String, String> albumCovers = fetchKgAlbumCovers(albumIds);
        for (JsonElement el : info) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String hash = optStr(o, "hash");
            if (hash.isEmpty()) continue;
            MusicTrack t = newTrack(KG, hash);
            t.title = optStr(o, "songname");
            t.artist = optStr(o, "singername");
            t.album = optStr(o, "album_name");
            t.duration = optLong(o, "duration") * 1000;
            String albumId = optStr(o, "album_id");
            if (!albumId.isEmpty()) {
                String cover = albumCovers.get(albumId);
                if (cover != null && !cover.isEmpty()) t.cover = cover;
            }
            JsonObject item = new JsonObject();
            item.addProperty("hash", hash);
            item.addProperty("albumId", albumId);
            // 歌词候选校验的参照（lyricKg 用）：歌名/歌手/时长（秒）随条目存下，
            // 没有参照时酷狗歌词候选只能盲取第一个、串版无从拦（扫描 M14）。
            item.addProperty("name", t.title);
            item.addProperty("singer", t.artist);
            if (t.duration > 0) item.addProperty("duration", t.duration / 1000);
            item.addProperty("songmid", hash);
            // 每档真实大小：酷狗搜索回包带 filesize（128k）/320filesize/sqfilesize（FLAC），字节，
            // 2026-10-26 本机实测；键名与 QQ 侧统一成 size128/size320/sizeFlac 供音质面板同口径读。
            putSizeIfPositive(item, "size128", o, "filesize");
            putSizeIfPositive(item, "size320", o, "320filesize");
            putSizeIfPositive(item, "sizeFlac", o, "sqfilesize");
            finish(t, item);
            out.add(t);
        }
        return out;
    }

    /** 酷狗专辑图并发补齐：最多 5 路并行、整批最多等 6 秒；单张失败／超时留空，不抛出。 */
    private static java.util.Map<String, String> fetchKgAlbumCovers(java.util.Set<String> albumIds) {
        java.util.Map<String, String> covers = new java.util.concurrent.ConcurrentHashMap<>();
        if (albumIds == null || albumIds.isEmpty()) return covers;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.min(5, albumIds.size()));
        try {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(albumIds.size());
            for (String albumId : albumIds) {
                pool.execute(() -> {
                    try {
                        covers.put(albumId, kgAlbumCover(albumId));
                    } finally {
                        latch.countDown();
                    }
                });
            }
            try {
                latch.await(6, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // 扫描 H12：恢复中断标志——此前吞掉中断，搜索链上游的取消信号在此失效一截
                Thread.currentThread().interrupt();
            }
        } finally {
            pool.shutdownNow();
        }
        return covers;
    }

    /** 酷狗专辑信息取图：只读返回里实际存在的图字段，任一环节失败返回空（不抛、不阻塞搜索）。
     * 扫描 H12：单请求超时压到 6 秒与整批预算对齐——此前走通用 http 的 20 秒超时，
     * latch 的 6 秒只拦得住等待、拦不住池线程（OkHttp 同步 execute 不响应中断），
     * 每首搜索最多 5 条线程跑满 20 秒做已丢弃的工作。 */
    private static String kgAlbumCover(String albumId) {
        try {
            String text = http("http://mobilecdn.kugou.com/api/v3/album/info?albumid=" + enc(albumId), "GET", null, null, 6000);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : root;
            String cover = firstNonEmpty(data, "imgurl", "img", "pic", "cover", "image", "album_img");
            if (!cover.isEmpty()) return cover;
            return firstNonEmpty(root, "imgurl", "img", "pic", "cover", "image");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String firstNonEmpty(JsonObject obj, String... keys) {
        for (String k : keys) {
            String v = optStr(obj, k);
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    /* ---------------- 咪咕 mg ---------------- */

    private static List<MusicTrack> searchMg(String keyword, int page) throws Exception {
        String sw = URLEncoder.encode("{\"song\":1,\"album\":0,\"singer\":0,\"tag\":0,\"lyric\":0,\"bestShow\":1,\"mv\":0,\"songlist\":0,\"singerCard\":0}", "UTF-8");
        String url = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do?text=" + enc(keyword)
                + "&pageNo=" + page + "&pageSize=20&searchSwitch=" + sw;
        String text = httpGet(url, "https://music.migu.cn/");
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        List<MusicTrack> out = new ArrayList<>();
        JsonArray result = digArray(root, "songResultData", "result");
        if (result == null) return out;
        for (JsonElement el : result) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String contentId = optStr(o, "contentId");
            String id = optStr(o, "id");
            if (contentId.isEmpty()) continue;
            MusicTrack t = newTrack(MG, contentId);
            t.title = optStr(o, "name");
            StringBuilder singer = new StringBuilder();
            if (o.has("singers") && o.get("singers").isJsonArray()) {
                for (JsonElement s : o.get("singers").getAsJsonArray()) {
                    if (!s.isJsonObject()) continue;
                    if (singer.length() > 0) singer.append("/");
                    singer.append(optStr(s.getAsJsonObject(), "name"));
                }
            }
            t.artist = singer.toString();
            if (o.has("albums") && o.get("albums").isJsonArray() && o.get("albums").getAsJsonArray().size() > 0) {
                JsonElement first = o.get("albums").getAsJsonArray().get(0);
                if (first.isJsonObject()) {
                    t.album = optStr(first.getAsJsonObject(), "name");
                }
            }
            // 封面真字段（2026-10-25 本机实证回包）：歌曲本级 imgItems[{img, imgSizeType}]，
            // 20/20 条都有；旧实现猜的 pic/img/cover/picUrl 等字段回包里根本不存在、封面恒空。
            // imgSizeType 越大图越大（01/02/03），取最大的一张。时长字段回包全字段皆无——
            // 本接口不给时长，t.duration 保持 0（行上 --:--），不许编造。
            if (o.has("imgItems") && o.get("imgItems").isJsonArray()) {
                String best = "";
                String bestSize = "";
                for (JsonElement im : o.get("imgItems").getAsJsonArray()) {
                    if (!im.isJsonObject()) continue;
                    String img = optStr(im.getAsJsonObject(), "img");
                    String size = optStr(im.getAsJsonObject(), "imgSizeType");
                    if (!img.isEmpty() && size.compareTo(bestSize) >= 0) {
                        best = img;
                        bestSize = size;
                    }
                }
                if (!best.isEmpty()) t.cover = best;
            }
            JsonObject item = new JsonObject();
            item.addProperty("songmid", contentId);
            item.addProperty("copyrightId", optStr(o, "copyrightId"));
            item.addProperty("id", id);
            finish(t, item);
            out.add(t);
        }
        return out;
    }

    /* ---------------- 平台歌词 ---------------- */

    /** 平台侧歌词：取不到返回 null，调用方再走落雪脚本 lyric 动作 */
    public static String lyric(String key, JsonObject item) {
        try {
            switch (key == null ? "" : key) {
                case KW:
                    return lyricKw(item);
                case QQ:
                    return lyricQq(item);
                case WY:
                    return lyricWy(item);
                case KG:
                    return lyricKg(item);
                case MG:
                    return lyricMg(item);
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String lyricKw(JsonObject item) throws Exception {
        String rid = optStr(item, "rid");
        if (rid.isEmpty()) rid = optStr(item, "songmid");
        if (rid.isEmpty()) return null;
        String text = httpGet("https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=" + enc(rid), null);
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        JsonArray list = digArray(root, "data", "lrclist");
        if (list == null || list.size() == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : list) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            double seconds = 0;
            try {
                seconds = Double.parseDouble(optStr(o, "time"));
            } catch (Exception ignored) {
            }
            sb.append(lrcTime(seconds)).append(optStr(o, "lineLyric")).append('\n');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String lyricQq(JsonObject item) throws Exception {
        String mid = optStr(item, "songmid");
        if (mid.isEmpty()) return null;
        String text = httpGet("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=" + enc(mid)
                + "&format=json&nobase64=1", "https://y.qq.com/");
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        String lyric = optStr(root, "lyric");
        return lyric.isEmpty() ? null : lyric;
    }

    private static String lyricWy(JsonObject item) throws Exception {
        String id = optStr(item, "id");
        if (id.isEmpty()) id = optStr(item, "songmid");
        if (id.isEmpty()) return null;
        String text = httpGet("https://music.163.com/api/song/lyric?id=" + enc(id) + "&lv=1", "https://music.163.com/");
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        if (root.has("lrc") && root.get("lrc").isJsonObject()) {
            String lyric = optStr(root.getAsJsonObject("lrc"), "lyric");
            return lyric.isEmpty() ? null : lyric;
        }
        return null;
    }

    /** 版本标记（与 cf-lyric-worker 的 hasVersionMarker 同集）：候选歌名带这些标记而原曲不带，多半是改版 */
    private static boolean hasVersionMarker(String s) {
        if (s == null || s.isEmpty()) return false;
        String t = s.toLowerCase(java.util.Locale.ROOT);
        return t.matches(".*\\bdj\\b.*") || t.contains("dj版") || t.contains("remix")
                || t.contains("翻唱") || t.contains("伴奏");
    }

    /**
     * 酷狗歌词两步：搜索候选 -> 下载 LRC（base64）。
     * 候选校验照 cf-lyric-worker 同口径（扫描 M14：旧实现 candidates.get(0) 盲取，
     * 首位是现场/翻唱/剪辑版就直接串版、还短路掉后面的脚本回落）：
     * 时长差超 12 秒的候选跳过；候选名带版本标记而原曲不带的降为兜底；
     * 最多实下 3 个候选，全灭才用兜底。参照（歌名/时长）取条目里的 name/duration，
     * 存量条目没存时相应校验自动跳过（无参照不误杀）。
     */
    private static String lyricKg(JsonObject item) throws Exception {
        String hash = optStr(item, "hash");
        if (hash.isEmpty()) return null;
        String refName = optStr(item, "name");
        long refDurSec = optLong(item, "duration");
        String search = httpGet("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=&hash=" + enc(hash), null);
        JsonObject root = JsonParser.parseString(search).getAsJsonObject();
        JsonArray candidates = root.has("candidates") && root.get("candidates").isJsonArray()
                ? root.getAsJsonArray("candidates") : null;
        if (candidates == null || candidates.size() == 0) return null;
        String markedFallback = null;
        int downloaded = 0;
        for (JsonElement el : candidates) {
            if (!el.isJsonObject() || downloaded >= 3) break;
            JsonObject cand = el.getAsJsonObject();
            String id = optStr(cand, "id");
            String accesskey = optStr(cand, "accesskey");
            if (id.isEmpty() || accesskey.isEmpty()) continue;
            long candDurSec = optLong(cand, "duration") / 1000;
            if (candDurSec > 0 && refDurSec > 0 && Math.abs(candDurSec - refDurSec) > 12) continue;
            String lyric = downloadKgLyric(id, accesskey);
            downloaded++;
            if (lyric == null) continue;
            boolean marked = !hasVersionMarker(refName) && hasVersionMarker(optStr(cand, "song"));
            if (marked) {
                if (markedFallback == null) markedFallback = lyric;
                continue;
            }
            return lyric;
        }
        return markedFallback;
    }

    /** 酷狗歌词下载一发：id+accesskey -> LRC 文本，失败返回 null */
    private static String downloadKgLyric(String id, String accesskey) throws Exception {
        String dl = httpGet("https://lyrics.kugou.com/download?ver=1&client=pc&id=" + enc(id)
                + "&accesskey=" + enc(accesskey) + "&fmt=lrc&charset=utf8", null);
        JsonObject dlRoot = JsonParser.parseString(dl).getAsJsonObject();
        String content = optStr(dlRoot, "content");
        if (content.isEmpty()) return null;
        try {
            String lyric = new String(Base64.decode(content, Base64.DEFAULT), StandardCharsets.UTF_8);
            return lyric.isEmpty() ? null : lyric;
        } catch (Exception e) {
            return null;
        }
    }

    /** 咪咕歌词：走树内已实证的 resourceinfo.do（MiguClient 同款口径）——
     * copyrightId 查 resource[0] 的 lrcUrl，再拉歌词文本；mrc 加密格式不解，只取 lrc。
     * 任一环节取不到字段返回 null，调用方再走落雪脚本 lyric 动作／兜底，不抛错。 */
    private static String lyricMg(JsonObject item) throws Exception {
        String copyrightId = optStr(item, "copyrightId");
        if (copyrightId.isEmpty()) return null;
        String text = httpGet("https://app.c.nf.migu.cn/MIGUM3.0/v1.0/content/resourceinfo.do?copyrightId="
                + enc(copyrightId) + "&resourceType=2", "https://music.migu.cn/");
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        JsonArray resource = root.has("resource") && root.get("resource").isJsonArray()
                ? root.getAsJsonArray("resource") : null;
        if (resource == null || resource.size() == 0 || !resource.get(0).isJsonObject()) return null;
        JsonObject data = resource.get(0).getAsJsonObject();
        String lrcUrl = firstNonEmpty(data, "lrcUrl", "lyricUrl");
        if (lrcUrl.isEmpty()) return null;
        String lyric = httpGet(lrcUrl, "https://music.migu.cn/");
        return lyric == null || lyric.trim().isEmpty() ? null : lyric;
    }

    /* ---------------- 内部 ---------------- */

    private static MusicTrack newTrack(String platformKey, String rawId) {
        MusicTrack t = new MusicTrack();
        t.id = "lx:" + platformKey + ":" + rawId;
        t.title = "";
        t.artist = "";
        t.album = "";
        t.cover = "";
        t.platform = LxMusicSource.PLATFORM;
        return t;
    }

    private static void finish(MusicTrack t, JsonObject item) {
        JsonObject wrap = new JsonObject();
        wrap.addProperty("_platform", platformOf(t));
        wrap.add("_item", item);
        t.rawJson = wrap.toString();
    }

    private static String platformOf(MusicTrack t) {
        // id = lx:<platform>:<rawId>
        String id = t.id == null ? "" : t.id;
        String[] parts = id.split(":", 3);
        return parts.length >= 2 ? parts[1] : "";
    }

    private static String httpGet(String url, String referer) throws Exception {
        return http(url, "GET", null, referer);
    }

    private static String httpPost(String url, String body, String referer) throws Exception {
        return http(url, "POST", body, referer);
    }

    /** 经 quickjs Connect 走壳子 OkHttp（吃代理/DoH/节点定向），与插件网络同出口 */
    private static String http(String url, String method, String body, String referer) throws Exception {
        return http(url, method, body, referer, 20000);
    }

    private static String http(String url, String method, String body, String referer, int timeoutMs) throws Exception {
        JSONObject reqJson = new JSONObject();
        reqJson.put("method", method);
        JSONObject headers = new JSONObject();
        headers.put("User-Agent", UA);
        if (!TextUtils.isEmpty(referer)) headers.put("Referer", referer);
        if (body != null) headers.put("Content-Type", "application/x-www-form-urlencoded");
        reqJson.put("headers", headers);
        if (body != null) reqJson.put("body", body);
        reqJson.put("timeout", timeoutMs);
        reqJson.put("redirect", 1);
        Req req = Req.objectFrom(reqJson.toString());
        try (Response res = Connect.to(url, req).execute()) {
            byte[] bytes = res.body() != null ? res.body().bytes() : new byte[0];
            return new String(bytes, req.getCharset());
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    private static String lrcTime(double seconds) {
        long cs = Math.round(seconds * 100);
        return String.format("[%02d:%02d.%02d]", cs / 6000, (cs / 100) % 60, cs % 100);
    }

    private static JsonArray digArray(JsonObject root, String... path) {
        JsonObject cur = root;
        for (int i = 0; i < path.length - 1; i++) {
            if (cur == null || !cur.has(path[i]) || !cur.get(path[i]).isJsonObject()) return null;
            cur = cur.getAsJsonObject(path[i]);
        }
        if (cur == null || path.length == 0) return null;
        String last = path[path.length - 1];
        return cur.has(last) && cur.get(last).isJsonArray() ? cur.getAsJsonArray(last) : null;
    }

    private static String optStr(JsonObject obj, String name) {
        try {
            if (obj != null && obj.has(name) && !obj.get(name).isJsonNull()) {
                JsonElement el = obj.get(name);
                return el.isJsonPrimitive() ? el.getAsString() : "";
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static long optLong(JsonObject obj, String name) {
        try {
            if (obj != null && obj.has(name) && !obj.get(name).isJsonNull()) return obj.get(name).getAsLong();
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** 酷我 r.s 是单引号伪 JSON：状态机转成合法 JSON（处理 \' \\ 与值内双引号） */
    static String fixPseudoJson(String text) {
        if (text == null) return "{}";
        String t = text.trim();
        if (t.startsWith("{") && t.contains("\"")) {
            try {
                JsonParser.parseString(t);
                return t;
            } catch (Exception ignored) {
            }
        }
        StringBuilder out = new StringBuilder(t.length() + 16);
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (inSingle) {
                if (c == '\\' && i + 1 < t.length()) {
                    char n = t.charAt(i + 1);
                    if (n == '\'') {
                        out.append('\'');
                        i++;
                    } else {
                        out.append(c).append(n);
                        i++;
                    }
                } else if (c == '\'') {
                    out.append('"');
                    inSingle = false;
                } else if (c == '"') {
                    out.append("\\\"");
                } else if (c < 0x20) {
                    out.append(String.format("\\u%04x", (int) c));
                } else {
                    out.append(c);
                }
            } else if (inDouble) {
                out.append(c);
                if (c == '\\' && i + 1 < t.length()) {
                    out.append(t.charAt(++i));
                } else if (c == '"') {
                    inDouble = false;
                }
            } else {
                if (c == '\'') {
                    out.append('"');
                    inSingle = true;
                } else if (c == '"') {
                    out.append('"');
                    inDouble = true;
                } else {
                    out.append(c);
                }
            }
        }
        return out.toString();
    }

    private static String htmlUnescape(String s) {
        if (s == null) return "";
        return s.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }
}
