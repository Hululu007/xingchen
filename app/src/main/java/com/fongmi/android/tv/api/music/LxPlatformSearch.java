package com.fongmi.android.tv.api.music;

import android.text.TextUtils;
import android.util.Base64;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.clash.MihomoManager;
import com.fongmi.android.tv.clash.SourceProxyPolicy;
import com.fongmi.android.tv.login.GoogleLoginProvider;
import com.fongmi.android.tv.login.LoginManager;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.quickjs.bean.Req;
import com.fongmi.quickjs.utils.Connect;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.SourceGate;
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
import java.util.Map;
import java.util.Map;

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
    public static final String YT = "yt";

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
            case YT:
                return "油管音乐";
            default:
                return key == null ? "" : key;
        }
    }

    /** 平台是否已实现壳子搜索 */
    public static boolean supported(String key) {
        return KW.equals(key) || QQ.equals(key) || WY.equals(key) || KG.equals(key) || MG.equals(key) || YT.equals(key);
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
                case YT:
                    out = searchYt(keyword, p);
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
            // 每档目录：酷我搜索条目自带 MINFO/N_MINFO（分号分隔的
            // level:..,bitrate:..,format:..,size:.. 段，本机真酷我插件的
            // parseKuwoQualityInfo 解析的就是这个字段，实证在案）——只取档位
            // 映射存 catalogTiers；size 是带单位的原样字符串、口径不明，
            // 不换算成字节冒充大小（面板大小保持“未知”，不编数）。
            String minfoTiers = kwMinfoTiers(o);
            if (!minfoTiers.isEmpty()) item.addProperty("catalogTiers", minfoTiers);
            finish(t, item);
            out.add(t);
        }
        return out;
    }

    /**
     * 酷我 MINFO → 星辰档位集合（逗号串，MusicStore.QUALITY_* 值）：
     * bitrate≥20000 是酷我的母带/全景声特殊码→Hi-Res；format=flac 时
     * bitrate≥4000→Hi-Res、其余（2000）→无损；其余格式 bitrate≤128→标准、
     * 更高（192/320）→极高。段认不全（没 bitrate）跳过，全认不出返回空串。
     */
    private static String kwMinfoTiers(JsonObject o) {
        String minfo = optStr(o, "N_MINFO");
        if (minfo.isEmpty()) minfo = optStr(o, "MINFO");
        if (minfo.isEmpty()) return "";
        java.util.Set<Integer> tiers = new java.util.TreeSet<>();
        for (String seg : minfo.split(";")) {
            Integer bitrate = null;
            String format = "";
            for (String kv : seg.split(",")) {
                int eq = kv.indexOf(':');
                if (eq < 0) continue;
                String k = kv.substring(0, eq).trim();
                String v = kv.substring(eq + 1).trim();
                if ("bitrate".equals(k)) {
                    try {
                        bitrate = Integer.parseInt(v);
                    } catch (Exception ignored) {
                    }
                } else if ("format".equals(k)) {
                    format = v.toLowerCase(java.util.Locale.US);
                }
            }
            if (bitrate == null) continue;
            if (bitrate >= 20000) tiers.add(MusicStore.QUALITY_SUPER);
            else if ("flac".equals(format)) tiers.add(bitrate >= 4000 ? MusicStore.QUALITY_SUPER : MusicStore.QUALITY_HIGH);
            else tiers.add(bitrate <= 128 ? MusicStore.QUALITY_LOW : MusicStore.QUALITY_STANDARD);
        }
        StringBuilder sb = new StringBuilder();
        for (int t : tiers) {
            if (sb.length() > 0) sb.append(',');
            sb.append(t);
        }
        return sb.toString();
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
            // Hi-Res 档：搜索回包有没有这个键本机未实证——两种常见键名都探，在才存
            // （存了面板目录就不用等详情才列 Hi-Res）；不在也不编，QQ 详情 file 对象
            // 仍是权威（面板第二层以它定案目录）。
            putSizeIfPositive(item, "sizeHires", o, "sizehires");
            putSizeIfPositive(item, "sizeHires", o, "size_hires");
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

    /**
     * QQ 解析要 strMediaMid：搜索接口不给，取链前用歌曲详情接口补（单首一次请求）。
     * 提速④（2026-10-26）：media_mid 是歌曲的固有标识、按 songmid 恒定——补查结果
     * 落盘缓存（内存＋Prefers 双层），同歌二次取链免付这约 2.4 秒串行补查。
     * 选落盘缓存不选与脚本链并行：补查无副作用且结果恒定，缓存能把重复取链的
     * 补查成本直接归零，并行只省首回的一发且要动脚本链的时序与汇合点，风险不对等。
     * 缓存只存非空真值；查不到不落盘（下次照常补查，不把一次失败钉成永久无 mid）。
     */
    public static String qqMediaMid(String songmid) {
        if (songmid == null || songmid.isEmpty()) return "";
        String cached = qqMediaMidCacheGet(songmid);
        if (!cached.isEmpty()) return cached;
        JsonObject file = qqFileInfo(songmid);
        String mid = file == null ? "" : optStr(file, "media_mid");
        if (!mid.isEmpty()) qqMediaMidCachePut(songmid, mid);
        return mid;
    }

    private static final String K_QQ_MEDIA_MID = "lx_qq_media_mid_cache";
    private static final java.util.Map<String, String> QQ_MEDIA_MID_MEM =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean qqMediaMidDiskLoaded = false;

    private static synchronized String qqMediaMidCacheGet(String songmid) {
        String mem = QQ_MEDIA_MID_MEM.get(songmid);
        if (mem != null) return mem;
        if (!qqMediaMidDiskLoaded) {
            qqMediaMidDiskLoaded = true;
            try {
                String json = com.github.catvod.utils.Prefers.getString(K_QQ_MEDIA_MID, "");
                if (json != null && !json.isEmpty()) {
                    JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                    for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                        if (e.getValue() != null && e.getValue().isJsonPrimitive()) {
                            QQ_MEDIA_MID_MEM.put(e.getKey(), e.getValue().getAsString());
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        String v = QQ_MEDIA_MID_MEM.get(songmid);
        return v == null ? "" : v;
    }

    private static synchronized void qqMediaMidCachePut(String songmid, String mediaMid) {
        QQ_MEDIA_MID_MEM.put(songmid, mediaMid);
        try {
            // 防无界增长：超 500 首就整表重建（media_mid 可随时再补查，清掉只是慢一回）
            if (QQ_MEDIA_MID_MEM.size() > 500) QQ_MEDIA_MID_MEM.clear();
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, String> e : QQ_MEDIA_MID_MEM.entrySet()) {
                obj.addProperty(e.getKey(), e.getValue());
            }
            com.github.catvod.utils.Prefers.put(K_QQ_MEDIA_MID, obj.toString());
        } catch (Throwable ignored) {
        }
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

    /** 酷狗封面后补专用池（守护线程，进程内共用） */
    private static final java.util.concurrent.ExecutorService KG_FILL_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread th = new Thread(r, "kg-cover-fill");
                th.setDaemon(true);
                return th;
            });

    /** 酷狗专辑图进程内缓存（album_id → 图址）：命中直出、不走空位不闪（提速①缓存直出口径） */
    private static final java.util.Map<String, String> KG_COVER_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 酷狗封面后补监听：补图回填完一批曲目后回调（搜索页据此只重绑那几行、淡入一次） */
    public interface KgCoverListener {
        void onKgCoversFilled(List<MusicTrack> tracks);
    }

    private static final java.util.List<KgCoverListener> KG_COVER_LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 单槽登记（搜索页用）：后来者顶替先到者，传 null 即注销——搜索页 initView／onDestroy
     * 一直在调本方法，此前只有 add/remove 两口、调用对不上（编译断点），在此补齐。 */
    public static void setKgCoverListener(KgCoverListener l) {
        KG_COVER_LISTENERS.clear();
        if (l != null) KG_COVER_LISTENERS.add(l);
    }

    public static void addKgCoverListener(KgCoverListener l) {
        if (l != null && !KG_COVER_LISTENERS.contains(l)) KG_COVER_LISTENERS.add(l);
    }

    public static void removeKgCoverListener(KgCoverListener l) {
        KG_COVER_LISTENERS.remove(l);
    }

    private static List<MusicTrack> searchKg(String keyword, int page) throws Exception {
        String url = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=" + enc(keyword)
                + "&page=" + page + "&pagesize=20&showtype=1";
        String text = httpGet(url, null);
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        List<MusicTrack> out = new ArrayList<>();
        JsonArray info = digArray(root, "data", "info");
        if (info == null) return out;
        // 提速①（2026-10-26）：结果先行、封面后补——旧实现等整批专辑图补齐（5 路并行、
        // 6 秒预算）才返回，酷狗桶感知耗时＝搜索 3 秒＋封面批 2–6 秒。现改为：
        // 缓存里有的专辑图同步直出（不走空位）；没有的先出列表、条目标 coverPending，
        // 补图异步回填同一批曲目对象并通知监听方重绑（行留玻璃空位、图到只淡入一次）。
        java.util.Set<String> albumIds = new java.util.LinkedHashSet<>();
        for (JsonElement el : info) {
            if (!el.isJsonObject()) continue;
            String albumId = optStr(el.getAsJsonObject(), "album_id");
            if (!albumId.isEmpty() && !KG_COVER_CACHE.containsKey(albumId)) albumIds.add(albumId);
        }
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
                String cover = KG_COVER_CACHE.get(albumId);
                if (cover != null && !cover.isEmpty()) t.cover = cover;
                else t.coverPending = true;
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
        if (!albumIds.isEmpty()) scheduleKgCoverFill(out, albumIds);
        return out;
    }

    /**
     * 酷狗封面异步后补：后台小池并发取图（口径同旧同步批：5 路、单张 6 秒），
     * 回填本批曲目对象的 cover 并进进程缓存，最后通知监听方重绑。
     * 补图失败必须留痕：旧同步批把单张失败静默吞掉（catch ignored 无 log），
     * 「同词两次搜一张有一张无」无从定罪——现单张失败与整批结果都记 music 日志。
     */
    private static void scheduleKgCoverFill(List<MusicTrack> tracks, java.util.Set<String> albumIds) {
        final List<MusicTrack> batch = new ArrayList<>(tracks);
        final java.util.Set<String> ids = new java.util.LinkedHashSet<>(albumIds);
        KG_FILL_POOL.execute(() -> {
            java.util.Map<String, String> covers = fetchKgAlbumCovers(ids);
            int filled = 0;
            List<MusicTrack> changed = new ArrayList<>();
            for (MusicTrack t : batch) {
                if (!t.coverPending) continue;
                String albumId = "";
                try {
                    JsonObject wrap = JsonParser.parseString(t.rawJson).getAsJsonObject();
                    if (wrap.has("_item") && wrap.get("_item").isJsonObject()) {
                        albumId = optStr(wrap.getAsJsonObject("_item"), "albumId");
                    }
                } catch (Throwable ignored) {
                }
                String cover = albumId.isEmpty() ? null : covers.get(albumId);
                t.coverPending = false;
                if (cover != null && !cover.isEmpty()) {
                    t.cover = cover;
                    t.coverBackfilled = true;
                    filled++;
                    changed.add(t);
                }
            }
            SpiderDebug.log("music", "kg cover fill albums=%d filledTracks=%d", ids.size(), filled);
            if (!changed.isEmpty()) {
                for (KgCoverListener l : KG_COVER_LISTENERS) {
                    try {
                        l.onKgCoversFilled(changed);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    /** 酷狗专辑图并发补齐：最多 5 路并行、整批最多等 6 秒；单张失败／超时留空，不抛出。
     * 成功结果同步进进程缓存（KG_COVER_CACHE），同专辑再搜直出。 */
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
                        String cover = kgAlbumCover(albumId);
                        covers.put(albumId, cover);
                        if (!cover.isEmpty()) KG_COVER_CACHE.put(albumId, cover);
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
            String fallback = firstNonEmpty(root, "imgurl", "img", "pic", "cover", "image");
            if (fallback.isEmpty()) {
                // 补图失败留痕（F1 低）：此前 catch 与空结果双静默，「补不到图」在日志里不存在
                SpiderDebug.log("music", "kg album cover empty albumId=%s", albumId);
            }
            return fallback;
        } catch (Throwable t) {
            SpiderDebug.log("music", "kg album cover fail albumId=%s %s", albumId, errText(t));
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

    /** 咪咕时长进程内缓存（copyrightId → 毫秒）：resourceinfo 的 length 字段查到后同歌免再查 */
    private static final java.util.Map<String, Long> MG_DURATION_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 咪咕时长后补监听：补查回填完一批曲目后回调（搜索页据此重绑那几行） */
    public interface MgDurationListener {
        void onMgDurationsFilled(List<MusicTrack> tracks);
    }

    private static final java.util.List<MgDurationListener> MG_DURATION_LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 单槽登记（搜索页用），同 setKgCoverListener 口径补齐。 */
    public static void setMgDurationListener(MgDurationListener l) {
        MG_DURATION_LISTENERS.clear();
        if (l != null) MG_DURATION_LISTENERS.add(l);
    }

    public static void addMgDurationListener(MgDurationListener l) {
        if (l != null && !MG_DURATION_LISTENERS.contains(l)) MG_DURATION_LISTENERS.add(l);
    }

    public static void removeMgDurationListener(MgDurationListener l) {
        MG_DURATION_LISTENERS.remove(l);
    }

    /**
     * 咪咕时长后补（F1 咪咕条）：search_all 回包全字段无时长（本机实测逐字段核过），
     * 但既有端点 resourceinfo.do（lyricMg 与视频侧 MiguClient 同一端点）的
     * resource[0].length 带真时长（"00:04:30" 形态，2026-10-26 本机实测《晴天》）。
     * 逐首一发、不能堵搜索：先出列表（时长 --:--），后台 5 路并发补查回填同一批
     * 曲目对象并通知监听方重绑；结果进进程缓存，同歌再搜直出。
     */
    private static void scheduleMgDurationFill(List<MusicTrack> tracks) {
        final List<MusicTrack> batch = new ArrayList<>();
        for (MusicTrack t : tracks) if (t != null && t.durationPending) batch.add(t);
        if (batch.isEmpty()) return;
        KG_FILL_POOL.execute(() -> {
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(Math.min(5, batch.size()));
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(batch.size());
            for (MusicTrack t : batch) {
                pool.execute(() -> {
                    try {
                        String copyrightId = "";
                        try {
                            JsonObject wrap = JsonParser.parseString(t.rawJson).getAsJsonObject();
                            if (wrap.has("_item") && wrap.get("_item").isJsonObject()) {
                                copyrightId = optStr(wrap.getAsJsonObject("_item"), "copyrightId");
                            }
                        } catch (Throwable ignored) {
                        }
                        long ms = copyrightId.isEmpty() ? 0 : mgDurationMs(copyrightId);
                        if (ms > 0) t.duration = ms;
                    } catch (Throwable ignored) {
                    } finally {
                        t.durationPending = false;
                        latch.countDown();
                    }
                });
            }
            try {
                latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pool.shutdownNow();
            List<MusicTrack> changed = new ArrayList<>();
            for (MusicTrack t : batch) if (t.duration > 0) changed.add(t);
            SpiderDebug.log("music", "mg duration fill batch=%d filled=%d", batch.size(), changed.size());
            if (!changed.isEmpty()) {
                for (MgDurationListener l : MG_DURATION_LISTENERS) {
                    try {
                        l.onMgDurationsFilled(changed);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    /** resourceinfo 单查时长：length "hh:mm:ss"/"mm:ss" → 毫秒；取不到返回 0（不编造） */
    private static long mgDurationMs(String copyrightId) {
        Long cached = MG_DURATION_CACHE.get(copyrightId);
        if (cached != null) return cached;
        long ms = 0;
        try {
            String text = http("https://app.c.nf.migu.cn/MIGUM3.0/v1.0/content/resourceinfo.do?copyrightId="
                    + enc(copyrightId) + "&resourceType=2", "GET", null, "https://music.migu.cn/", 6000);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonArray resource = root.has("resource") && root.get("resource").isJsonArray()
                    ? root.getAsJsonArray("resource") : null;
            if (resource != null && resource.size() > 0 && resource.get(0).isJsonObject()) {
                ms = parseColonDurationMs(optStr(resource.get(0).getAsJsonObject(), "length"));
            }
        } catch (Throwable t) {
            SpiderDebug.log("music", "mg duration fail copyrightId=%s %s", copyrightId, errText(t));
        }
        if (ms > 0) MG_DURATION_CACHE.put(copyrightId, ms);
        return ms;
    }

    /** "00:04:30"／"04:30" 形态时长 → 毫秒；认不出返回 0 */
    private static long parseColonDurationMs(String s) {
        if (s == null || s.isEmpty() || !s.contains(":")) return 0;
        try {
            long totalSec = 0;
            for (String p : s.trim().split(":")) totalSec = totalSec * 60 + Long.parseLong(p.trim());
            return totalSec * 1000;
        } catch (Throwable ignored) {
            return 0;
        }
    }

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
            // imgSizeType 越大图越大（01/02/03），取最大的一张。时长字段本接口回包
            // 全字段皆无（不许编造）——改走第二数据源 resourceinfo 的 length 后补，
            // 见下方 durationPending 接线与 scheduleMgDurationFill。
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
            String copyrightId = optStr(o, "copyrightId");
            item.addProperty("copyrightId", copyrightId);
            item.addProperty("id", id);
            // 时长第二数据源（见 scheduleMgDurationFill 注释）：缓存命中直填，
            // 未命中标 pending、列表先行后补；本接口自身确实无时长字段，不编造。
            Long cachedDur = copyrightId.isEmpty() ? null : MG_DURATION_CACHE.get(copyrightId);
            if (cachedDur != null && cachedDur > 0) t.duration = cachedDur;
            else if (!copyrightId.isEmpty()) t.durationPending = true;
            finish(t, item);
            out.add(t);
        }
        scheduleMgDurationFill(out);
        return out;
    }

    /* ---------------- 油管 yt ---------------- */

    /**
     * 油管搜索（落雪形态下搜索归壳子，是协议定死的分工）：轮试口径从原 FreeJS 托管
     * 脚本 1.1.4 搬来不丢——WEB_REMIX（有谷歌登录态先带态）→ ANDROID_MUSIC／
     * IOS_MUSIC 匿名 →（有登录态时）移动系带态 → WEB_REMIX 匿名兜底；每家死因
     * 分开记日志、全灭记总账，不静默吞错。出口必须走节点：本段单独打
     * (music, 油管落雪预置地址) 标记并按需拉内核（与 FreeJsBridge.nativeRequest
     * 同口径），五平台老路不打点、行为不变。分页只做第一页：续页 token 是出它的
     * 客户端发的、壳子搜索无状态，page＞1 回空（不许拿第一页冒充第二页）。
     */
    private static final String YT_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String YT_SAFARI_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";
    private static final String YT_SONG_PARAMS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D";
    private static final String YT_AUTH_ORIGIN = "https://music.youtube.com";
    private static final int YT_POST_TIMEOUT_MS = 6000;
    private static final long YT_SEARCH_DEADLINE_MS = 22000;
    private static final long YT_LOGIN_PROMPT_INTERVAL_MS = 10 * 60 * 1000L;
    private static volatile String ytVisitorData = "";
    private static volatile String ytRemixVersion = "1.20240724.01.00";
    private static volatile boolean ytVisitorTried;
    private static volatile long ytLoginPromptAt;
    private static volatile long ytNodeStartFailedAt;

    private static List<MusicTrack> searchYt(String keyword, int page) {
        if (page > 1) return new ArrayList<>();
        String cookie = LoginManager.getCookie(GoogleLoginProvider.ID);
        boolean hasAuth = GoogleLoginProvider.isCookieValid(cookie);
        if (!hasAuth) maybePromptYtLogin();
        ytEnsureVisitor(cookie);
        // 轮试表：{客户端序号, 是否带态}，次序与脚本 1.1.4 的 attempts 一字不差
        List<int[]> attempts = new ArrayList<>();
        attempts.add(new int[]{0, hasAuth ? 1 : 0});
        attempts.add(new int[]{1, 0});
        attempts.add(new int[]{2, 0});
        if (hasAuth) {
            attempts.add(new int[]{1, 1});
            attempts.add(new int[]{2, 1});
            attempts.add(new int[]{0, 0});
        }
        long startedAt = System.currentTimeMillis();
        for (int i = 0; i < attempts.size(); i++) {
            if (i > 0 && System.currentTimeMillis() - startedAt > YT_SEARCH_DEADLINE_MS) {
                SpiderDebug.log("music", "yt search 轮试到点收口 q=%s 已试%d家", keyword, i);
                break;
            }
            List<MusicTrack> out = ytTrySearch(keyword, attempts.get(i)[0], attempts.get(i)[1] == 1, cookie);
            if (out != null) return out;
        }
        SpiderDebug.log("music", "yt search 全轮试无结果 q=%s auth=%s", keyword, hasAuth ? "yes" : "no");
        return new ArrayList<>();
    }

    /** 未登录时请壳子弹统一登录（10 分钟节流，与 FreeJsBridge.maybePromptLogin 同口径；弹不弹不拦本次匿名搜索） */
    private static void maybePromptYtLogin() {
        try {
            long now = System.currentTimeMillis();
            if (now - ytLoginPromptAt < YT_LOGIN_PROMPT_INTERVAL_MS) return;
            ytLoginPromptAt = now;
            LoginManager.requestLogin(GoogleLoginProvider.ID);
        } catch (Throwable ignored) {
        }
    }

    /** 访客参数（进程内只取一次，失败照常继续——少带一个头，成败由接口定） */
    private static void ytEnsureVisitor(String cookie) {
        if (ytVisitorTried) return;
        ytVisitorTried = true;
        try {
            JSONObject headers = new JSONObject();
            headers.put("User-Agent", YT_SAFARI_UA);
            if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
            String html = ytHttp("https://music.youtube.com/", null, headers, YT_POST_TIMEOUT_MS);
            if (html == null) return;
            java.util.regex.Matcher vd = java.util.regex.Pattern.compile("\"VISITOR_DATA\":\"([^\"]+)\"").matcher(html);
            if (vd.find()) ytVisitorData = vd.group(1);
            java.util.regex.Matcher ver = java.util.regex.Pattern.compile("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").matcher(html);
            if (ver.find()) ytRemixVersion = ver.group(1);
        } catch (Throwable ignored) {
        }
    }

    /** 单家搜索：出 ≥1 条算成（返回曲目）；失败把死因记进日志并返回 null（与脚本 trySearch 同口径） */
    private static List<MusicTrack> ytTrySearch(String keyword, int clientIdx, boolean withAuth, String cookie) {
        String name = clientIdx == 0 ? "WEB_REMIX" : clientIdx == 1 ? "ANDROID_MUSIC" : "IOS_MUSIC";
        String tag = "yt search " + name + (withAuth ? "(带态)" : "(匿名)") + " q=" + keyword;
        JsonObject client = ytClientContext(clientIdx);
        JsonObject body = new JsonObject();
        JsonObject context = new JsonObject();
        context.add("client", client);
        body.add("context", context);
        body.addProperty("query", keyword);
        body.addProperty("params", YT_SONG_PARAMS);
        String text;
        try {
            JSONObject headers = ytClientHeaders(clientIdx, client);
            if (withAuth) ytPutAuth(headers, cookie);
            text = ytHttp("https://www.youtube.com/youtubei/v1/search?key=" + YT_API_KEY + "&prettyPrint=false",
                    body.toString(), headers, YT_POST_TIMEOUT_MS);
        } catch (Throwable t) {
            SpiderDebug.log("music", "%s 请求失败 %s", tag, errText(t));
            return null;
        }
        if (TextUtils.isEmpty(text)) {
            SpiderDebug.log("music", "%s 回包为空", tag);
            return null;
        }
        if (text.contains("LOGIN_REQUIRED")) {
            SpiderDebug.log("music", "%s 回 LOGIN_REQUIRED（出口被谷歌按登录态拦）", tag);
            return null;
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (Throwable t) {
            SpiderDebug.log("music", "%s 回包不是 JSON", tag);
            return null;
        }
        if (root.has("error") && root.get("error").isJsonObject()) {
            JsonObject err = root.getAsJsonObject("error");
            SpiderDebug.log("music", "%s 接口error status=%s msg=%s", tag, optStr(err, "status"), optStr(err, "message"));
            return null;
        }
        List<MusicTrack> out = new ArrayList<>();
        ytVisitShelf(root, out, new java.util.HashSet<>());
        if (out.isEmpty()) {
            SpiderDebug.log("music", "%s 货架为空（真无结果或响应结构不认）", tag);
            return null;
        }
        return out;
    }

    private static JsonObject ytClientContext(int clientIdx) {
        JsonObject c = new JsonObject();
        switch (clientIdx) {
            case 1:
                c.addProperty("clientName", "ANDROID_MUSIC");
                c.addProperty("clientVersion", "7.27.52");
                c.addProperty("osName", "Android");
                c.addProperty("osVersion", "14");
                c.addProperty("androidSdkVersion", 34);
                c.addProperty("hl", "zh-CN");
                c.addProperty("gl", "US");
                c.addProperty("userAgent", "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14) gzip");
                break;
            case 2:
                c.addProperty("clientName", "IOS_MUSIC");
                c.addProperty("clientVersion", "7.27");
                c.addProperty("deviceMake", "Apple");
                c.addProperty("deviceModel", "iPhone16,2");
                c.addProperty("osName", "iOS");
                c.addProperty("osVersion", "18.3.2.22D82");
                c.addProperty("hl", "en");
                c.addProperty("gl", "US");
                c.addProperty("userAgent", "com.google.ios.youtubemusic/7.27 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)");
                break;
            default:
                c.addProperty("clientName", "WEB_REMIX");
                c.addProperty("clientVersion", ytRemixVersion);
                c.addProperty("hl", "zh-CN");
                c.addProperty("gl", "US");
                break;
        }
        if (!TextUtils.isEmpty(ytVisitorData)) c.addProperty("visitorData", ytVisitorData);
        return c;
    }

    private static JSONObject ytClientHeaders(int clientIdx, JsonObject client) throws Exception {
        JSONObject h = new JSONObject();
        String num = clientIdx == 0 ? "67" : clientIdx == 1 ? "21" : "26";
        String ua = clientIdx == 0 ? YT_SAFARI_UA : optStr(client, "userAgent");
        h.put("Content-Type", "application/json");
        h.put("User-Agent", ua);
        h.put("X-YouTube-Client-Name", num);
        h.put("X-YouTube-Client-Version", optStr(client, "clientVersion"));
        if (!TextUtils.isEmpty(ytVisitorData)) h.put("X-Goog-Visitor-Id", ytVisitorData);
        return h;
    }

    /** 带态请求头：Cookie 原串＋SAPISIDHASH 系 Authorization（与脚本 mergeAuth、GoogleLoginProvider 种子口径对齐） */
    private static void ytPutAuth(JSONObject headers, String cookie) {
        try {
            if (TextUtils.isEmpty(cookie) || !GoogleLoginProvider.isCookieValid(cookie)) return;
            List<String> parts = new ArrayList<>();
            String h = ytSapisidHash(ytCookieValue(cookie, "SAPISID"));
            if (!h.isEmpty()) parts.add("SAPISIDHASH " + h);
            String h1 = ytSapisidHash(ytCookieValue(cookie, "__Secure-1PSAPISID"));
            if (!h1.isEmpty()) parts.add("SAPISID1PHASH " + h1);
            String h3 = ytSapisidHash(ytCookieValue(cookie, "__Secure-3PSAPISID"));
            if (!h3.isEmpty()) parts.add("SAPISID3PHASH " + h3);
            headers.put("Cookie", cookie);
            headers.put("Origin", YT_AUTH_ORIGIN);
            headers.put("Referer", YT_AUTH_ORIGIN + "/");
            headers.put("X-Goog-AuthUser", "0");
            if (!parts.isEmpty()) headers.put("Authorization", String.join(" ", parts));
        } catch (Throwable ignored) {
        }
    }

    private static String ytCookieValue(String cookie, String name) {
        if (TextUtils.isEmpty(cookie)) return "";
        for (String pair : cookie.split(";")) {
            String item = pair.trim();
            int eq = item.indexOf('=');
            if (eq <= 0) continue;
            if (name.equals(item.substring(0, eq).trim())) return item.substring(eq + 1).trim();
        }
        return "";
    }

    /** SAPISIDHASH：SHA1(时间戳 + " " + 种子 + " " + Origin)，拼 时间戳_哈希（油管音乐网页端规矩） */
    private static String ytSapisidHash(String seed) {
        if (TextUtils.isEmpty(seed)) return "";
        try {
            long ts = System.currentTimeMillis() / 1000;
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1")
                    .digest((ts + " " + seed + " " + YT_AUTH_ORIGIN).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return ts + "_" + sb;
        } catch (Throwable t) {
            return "";
        }
    }

    /** yt 专用出口：打 (music, 油管落雪预置地址) 标记＋按需拉内核后走壳子 OkHttp（Connect），与脚本 lx.request 同出口 */
    private static String ytHttp(String url, String jsonBody, JSONObject headers, int timeoutMs) throws Exception {
        ytEnsureNode();
        SourceGate.Mark prev = SourceGate.push(SourceProxyPolicy.KIND_MUSIC, MusicSourceManager.BUILTIN_YTMUSIC);
        try {
            JSONObject reqJson = new JSONObject();
            reqJson.put("method", jsonBody == null ? "GET" : "POST");
            if (headers != null) reqJson.put("headers", headers);
            if (jsonBody != null) reqJson.put("body", jsonBody);
            reqJson.put("timeout", timeoutMs);
            reqJson.put("redirect", 1);
            Req req = Req.objectFrom(reqJson.toString());
            try (Response res = Connect.to(url, req).execute()) {
                byte[] bytes = res.body() != null ? res.body().bytes() : new byte[0];
                return new String(bytes, req.getCharset());
            }
        } finally {
            SourceGate.pop(prev);
        }
    }

    /** 策略开则按需拉节点内核（FreeJsBridge.ensureNodeForMusic 同口径：拉不起不堵请求、留一条日志） */
    private static void ytEnsureNode() {
        try {
            App app = App.get();
            if (app == null) return;
            if (!SourceProxyPolicy.isEnabled(app, SourceProxyPolicy.KIND_MUSIC, MusicSourceManager.BUILTIN_YTMUSIC)) return;
            if (MihomoManager.get().isRunning()) return;
            long now = System.currentTimeMillis();
            if (now - ytNodeStartFailedAt < 60000) return;
            if (!MihomoManager.get().ensureRunning(app)) {
                ytNodeStartFailedAt = now;
                SpiderDebug.log("clash", "gate music node-start-failed id=%s （内核没拉起来，本次请求直连）", MusicSourceManager.BUILTIN_YTMUSIC);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 递归摘货架（musicShelfRenderer／musicShelfContinuation），与脚本 extractShelf 同遍历口径 */
    private static void ytVisitShelf(JsonElement node, List<MusicTrack> out, java.util.Set<String> seen) {
        if (node == null) return;
        if (node.isJsonArray()) {
            for (JsonElement el : node.getAsJsonArray()) ytVisitShelf(el, out, seen);
            return;
        }
        if (!node.isJsonObject()) return;
        JsonObject o = node.getAsJsonObject();
        JsonObject shelf = null;
        if (o.has("musicShelfRenderer") && o.get("musicShelfRenderer").isJsonObject()) {
            shelf = o.getAsJsonObject("musicShelfRenderer");
        } else if (o.has("musicShelfContinuation") && o.get("musicShelfContinuation").isJsonObject()) {
            shelf = o.getAsJsonObject("musicShelfContinuation");
        }
        if (shelf != null && shelf.has("contents") && shelf.get("contents").isJsonArray()) {
            for (JsonElement el : shelf.getAsJsonArray("contents")) {
                if (!el.isJsonObject()) continue;
                MusicTrack t = ytParseResponsive(el.getAsJsonObject());
                if (t == null) t = ytParseTwoColumn(el.getAsJsonObject());
                if (t != null && seen.add(t.id)) out.add(t);
            }
        }
        for (Map.Entry<String, JsonElement> e : o.entrySet()) ytVisitShelf(e.getValue(), out, seen);
    }

    /** WEB_REMIX 货架条目（musicResponsiveListItemRenderer，带专辑列），解析口径与脚本 parseShelfItem 一致 */
    private static MusicTrack ytParseResponsive(JsonObject node) {
        if (!node.has("musicResponsiveListItemRenderer") || !node.get("musicResponsiveListItemRenderer").isJsonObject()) return null;
        JsonObject item = node.getAsJsonObject("musicResponsiveListItemRenderer");
        String videoId = "";
        if (item.has("playlistItemData") && item.get("playlistItemData").isJsonObject()) {
            videoId = optStr(item.getAsJsonObject("playlistItemData"), "videoId");
        }
        if (videoId.isEmpty()) return null;
        JsonArray cols = item.has("flexColumns") && item.get("flexColumns").isJsonArray() ? item.getAsJsonArray("flexColumns") : null;
        if (cols == null || cols.size() == 0) return null;
        List<String> titleRuns = ytFlexRuns(cols.get(0));
        if (titleRuns.isEmpty() || titleRuns.get(0).isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        if (cols.size() > 1) {
            for (String s : ytFlexRuns(cols.get(1))) {
                if (!" • ".equals(s) && !s.trim().isEmpty()) parts.add(s);
            }
        }
        String duration = "";
        if (!parts.isEmpty() && parts.get(parts.size() - 1).trim().matches("\\d{1,3}:\\d{2}(:\\d{2})?")) {
            duration = parts.remove(parts.size() - 1).trim();
        }
        MusicTrack t = newTrack(YT, videoId);
        t.title = titleRuns.get(0);
        t.artist = parts.isEmpty() ? "" : parts.get(0);
        t.album = parts.size() > 1 ? parts.get(1) : "";
        t.duration = ytDurationMs(duration);
        t.cover = ytArtwork(item);
        JsonObject it = new JsonObject();
        it.addProperty("songmid", videoId);
        finish(t, it);
        return t;
    }

    /** 移动系货架条目（musicTwoColumnItemRenderer，无专辑列），解析口径与脚本 parseTwoColumnItem 一致 */
    private static MusicTrack ytParseTwoColumn(JsonObject node) {
        if (!node.has("musicTwoColumnItemRenderer") || !node.get("musicTwoColumnItemRenderer").isJsonObject()) return null;
        JsonObject item = node.getAsJsonObject("musicTwoColumnItemRenderer");
        String videoId = "";
        try {
            videoId = item.getAsJsonObject("navigationEndpoint").getAsJsonObject("watchEndpoint").get("videoId").getAsString();
        } catch (Throwable ignored) {
        }
        if (videoId == null || videoId.isEmpty()) return null;
        String title = ytRunsText(item, "title");
        if (title.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        try {
            JsonArray runs = item.getAsJsonObject("subtitle").getAsJsonArray("runs");
            for (JsonElement r : runs) {
                if (!r.isJsonObject()) continue;
                String s = optStr(r.getAsJsonObject(), "text");
                if (!" • ".equals(s) && !s.trim().isEmpty()) parts.add(s);
            }
        } catch (Throwable ignored) {
        }
        String duration = "";
        String artist = "";
        String album = "";
        for (String p0 : parts) {
            String p = p0.trim();
            if (duration.isEmpty() && p.matches("\\d{1,3}:\\d{2}(:\\d{2})?")) {
                duration = p;
                continue;
            }
            if (p.contains("播放次数") || p.contains("观看次数") || p.contains("次播放")) continue;
            if (artist.isEmpty()) {
                artist = p;
                continue;
            }
            if (album.isEmpty()) album = p;
        }
        MusicTrack t = newTrack(YT, videoId);
        t.title = title;
        t.artist = artist;
        t.album = album;
        t.duration = ytDurationMs(duration);
        t.cover = ytArtwork(item);
        JsonObject it = new JsonObject();
        it.addProperty("songmid", videoId);
        finish(t, it);
        return t;
    }

    private static List<String> ytFlexRuns(JsonElement flexColumn) {
        List<String> out = new ArrayList<>();
        try {
            JsonArray runs = flexColumn.getAsJsonObject()
                    .getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
                    .getAsJsonObject("text").getAsJsonArray("runs");
            for (JsonElement r : runs) {
                if (r.isJsonObject()) out.add(optStr(r.getAsJsonObject(), "text"));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String ytRunsText(JsonObject item, String field) {
        StringBuilder sb = new StringBuilder();
        try {
            JsonArray runs = item.getAsJsonObject(field).getAsJsonArray("runs");
            for (JsonElement r : runs) {
                if (r.isJsonObject()) sb.append(optStr(r.getAsJsonObject(), "text"));
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static String ytArtwork(JsonObject item) {
        try {
            JsonArray thumbs = item.getAsJsonObject("thumbnail").getAsJsonObject("musicThumbnailRenderer")
                    .getAsJsonObject("thumbnail").getAsJsonArray("thumbnails");
            if (thumbs.size() == 0) return "";
            return optStr(thumbs.get(thumbs.size() - 1).getAsJsonObject(), "url");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static long ytDurationMs(String duration) {
        if (TextUtils.isEmpty(duration)) return 0;
        try {
            long total = 0;
            for (String part : duration.trim().split(":")) total = total * 60 + Long.parseLong(part.trim());
            return total * 1000;
        } catch (Throwable ignored) {
            return 0;
        }
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

    /* ---- 酷我 songinfoandlrc 熔断（提速⑦，与 CfLyricClient 熔断同款口径） ----
     * 该端点回空已成常态（实测连续三发 data:null、每发白付 1–5.6 秒），且平台词与
     * 元力 KW 插件词都经它。连续 KW_EMPTY_LIMIT 次拿到应答但 lrclist 为空 →
     * 熔断窗口内 lyricKw 直接回 null 走后面兜底腿；窗口过后半开探活一发：
     * 有词清零接回、仍空重新熔断。只认「有应答但空」计数——传输异常走 lyric()
     * 的 catch 回 null、不经本计数，网络问题不算端点坏、不计数也不清零。 */
    private static final int KW_EMPTY_LIMIT = 3;
    private static final long KW_BREAKER_WINDOW_MS = 10 * 60 * 1000L;
    private static int kwEmptyStreak;
    private static long kwBreakerTrippedAt;

    private static synchronized boolean kwBreakerOpen() {
        return kwEmptyStreak >= KW_EMPTY_LIMIT
                && System.currentTimeMillis() - kwBreakerTrippedAt < KW_BREAKER_WINDOW_MS;
    }

    private static synchronized void kwBreakerNoteEmpty() {
        kwEmptyStreak++;
        if (kwEmptyStreak >= KW_EMPTY_LIMIT) kwBreakerTrippedAt = System.currentTimeMillis();
    }

    private static synchronized void kwBreakerNoteHit() {
        kwEmptyStreak = 0;
        kwBreakerTrippedAt = 0;
    }

    private static String lyricKw(JsonObject item) throws Exception {
        String rid = optStr(item, "rid");
        if (rid.isEmpty()) rid = optStr(item, "songmid");
        if (rid.isEmpty()) return null;
        if (kwBreakerOpen()) {
            com.github.catvod.crawler.SpiderDebug.log("music", "kuwo songinfoandlrc breaker open, skip rid=%s", rid);
            return null;
        }
        String text = httpGet("https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=" + enc(rid), null);
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        JsonArray list = digArray(root, "data", "lrclist");
        if (list == null || list.size() == 0) {
            kwBreakerNoteEmpty();
            return null;
        }
        kwBreakerNoteHit();
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
