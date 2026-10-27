package com.fongmi.android.tv.hongguo.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 红果回包解析（纯 Gson，无 Android 依赖，可在纯 JVM 单测）。
 *
 * <p>吃两种已实证形态：</p>
 * <ul>
 *   <li>multi_video_model 回包：data 是 {集id: {video_model: "字符串化JSON", ...}}，
 *       内层 video_list 是数组，档位字段在 video_meta、加密字段在 encrypt_info。</li>
 *   <li>fplay 回包：video_info.data 即 video_model，video_list 是 {video_N: {...}} 对象表，
 *       字段扁平，且带 key_seed / poster_url。</li>
 * </ul>
 * <p>详情与 cell 类回包字段口径未在样本里定版，解析走容忍抽取并保留 raw。</p>
 */
public final class HgParser {

    private HgParser() {
    }

    // ---------- 播放模型 ----------

    /** multi_video_model 回包 → 以请求集 id 为键的模型表（保持回包顺序）。 */
    public static Map<String, HgVideoModel> parseMultiVideoModel(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, HgVideoModel> out = new LinkedHashMap<>();
        JsonObject data = obj(root, "data");
        if (data == null) return out;
        for (Map.Entry<String, JsonElement> e : data.entrySet()) {
            JsonObject wrap = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : null;
            if (wrap == null) continue;
            JsonElement vmEl = wrap.get("video_model");
            JsonObject vm = asObject(vmEl);
            if (vm == null) continue;
            int vw = intOf(wrap, "video_width", 0);
            int vh = intOf(wrap, "video_height", 0);
            out.put(e.getKey(), buildModel(e.getKey(), vm, vw, vh));
        }
        return out;
    }

    /** fplay 回包（fallback_api 的返回）→ 单集模型。 */
    public static HgVideoModel parseFplay(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject vi = obj(root, "video_info");
        JsonObject data = vi == null ? null : obj(vi, "data");
        if (data == null) return null;
        return buildModel(null, data, 0, 0);
    }

    /** 单个 video_model 对象（已解开字符串化）→ 模型。 */
    public static HgVideoModel parseVideoModel(JsonObject vm, String requestedId) {
        return buildModel(requestedId, vm, 0, 0);
    }

    private static HgVideoModel buildModel(String requestedId, JsonObject vm, int vw, int vh) {
        List<HgVideoEntry> entries = new ArrayList<>();
        JsonElement listEl = vm.get("video_list");
        if (listEl != null && listEl.isJsonArray()) {
            for (JsonElement el : listEl.getAsJsonArray()) {
                if (el.isJsonObject()) entries.add(buildEntryArrayForm(el.getAsJsonObject()));
            }
        } else if (listEl != null && listEl.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : listEl.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonObject()) entries.add(buildEntryFlatForm(e.getValue().getAsJsonObject()));
            }
        }
        String fallback = null;
        JsonObject fb = obj(vm, "fallback_api");
        if (fb != null) fallback = str(fb, "fallback_api");
        return new HgVideoModel(
                requestedId,
                str(vm, "video_id"),
                intOf(vm, "status", 0),
                str(vm, "message"),
                doubleOf(vm, "video_duration", 0),
                str(vm, "media_type"),
                longOf(vm, "url_expire", 0),
                str(vm, "poster_url"),
                str(vm, "key_seed"),
                fallback,
                vw, vh,
                entries);
    }

    /** multi 形态：字段在 video_meta / encrypt_info 子对象里（子对象也可能是字符串化 JSON）。 */
    private static HgVideoEntry buildEntryArrayForm(JsonObject item) {
        JsonObject meta = asObject(item.get("video_meta"));
        if (meta == null) meta = new JsonObject();
        JsonObject enc = asObject(item.get("encrypt_info"));
        if (enc == null) enc = new JsonObject();
        String backup = str(item, "backup_url");
        if (backup == null) backup = str(item, "backup_url_1");
        return new HgVideoEntry(
                str(meta, "definition"), str(meta, "quality"), str(meta, "vtype"),
                intOf(meta, "vwidth", 0), intOf(meta, "vheight", 0),
                longOf(meta, "bitrate", 0), longOf(meta, "real_bitrate", 0),
                doubleOf(meta, "fps", 0), str(meta, "codec_type"),
                longOf(meta, "size", 0), str(meta, "file_id"), str(meta, "file_hash"),
                str(item, "main_url"), backup,
                str(enc, "spade_a"), str(enc, "kid"), str(enc, "encryption_method"),
                str(item, "gear_des_key"));
    }

    /** fplay 形态：字段扁平在项上。 */
    private static HgVideoEntry buildEntryFlatForm(JsonObject item) {
        String backup = str(item, "backup_url_1");
        if (backup == null) backup = str(item, "backup_url");
        return new HgVideoEntry(
                str(item, "definition"), str(item, "quality"), str(item, "vtype"),
                intFlexible(item, "vwidth"), intFlexible(item, "vheight"),
                longFlexible(item, "bitrate"), longFlexible(item, "real_bitrate"),
                doubleFlexible(item, "fps"), str(item, "codec_type"),
                longFlexible(item, "size"), str(item, "file_id"), str(item, "file_hash"),
                str(item, "main_url"), backup,
                str(item, "spade_a"), str(item, "kid"), str(item, "encryption_method"),
                str(item, "gear_des_key"));
    }

    // ---------- 详情（容忍抽取，字段口径未定版） ----------

    public static HgSeriesDetail parseVideoDetail(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject data = obj(root, "data");
        JsonObject d = data != null ? data : root;
        // data 下可能再套一层以剧 id 为键的表
        if (str(d, "series_id") == null && str(d, "book_id") == null && str(d, "title") == null
                && str(d, "series_name") == null && !d.entrySet().isEmpty()) {
            for (Map.Entry<String, JsonElement> e : d.entrySet()) {
                if (e.getValue().isJsonObject()) {
                    d = e.getValue().getAsJsonObject();
                    break;
                }
            }
        }
        List<HgSeriesDetail.Episode> episodes = new ArrayList<>();
        JsonArray eps = arr(d, "episode_list");
        if (eps == null) eps = arr(d, "episodes");
        if (eps == null) eps = arr(d, "video_list");
        if (eps != null) {
            int i = 0;
            for (JsonElement el : eps) {
                if (!el.isJsonObject()) continue;
                JsonObject eo = el.getAsJsonObject();
                String vid = firstStr(eo, "video_id", "vid", "id");
                if (vid == null) continue;
                int idx = intOf(eo, "index", -1);
                if (idx < 0) idx = intOf(eo, "episode_index", i + 1);
                episodes.add(new HgSeriesDetail.Episode(vid, idx, firstStr(eo, "title", "name")));
                i++;
            }
        }
        String seriesId = firstStr(d, "series_id", "book_id", "id");
        String title = firstStr(d, "title", "series_name", "name");
        String cover = firstStr(d, "cover", "poster_url", "thumb_url", "cover_url");
        String desc = firstStr(d, "desc", "description", "intro", "abstract");
        String author = firstStr(d, "author_name", "author");
        int count = intOf(d, "episode_count", -1);
        if (count < 0) count = intOf(d, "episode_cnt", episodes.isEmpty() ? -1 : episodes.size());
        JsonObject appt = obj(d, "appointment");
        return new HgSeriesDetail(seriesId, title, cover, desc, author, count, episodes, root,
                appt != null, appt != null && boolOf(appt, "is_subscribed"),
                appt == null ? null : str(appt, "due_start_text"));
    }

    // ---------- cell 类回包（容忍抽取 + 保留 raw） ----------

    public static HgCellPage parseCellPage(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        int code = intOf(root, "code", 0);
        String msg = str(root, "message");
        List<HgSeriesItem> items = new ArrayList<>();
        JsonObject data = obj(root, "data");
        collectSeries(data != null ? data : root, items, 0);
        return new HgCellPage(code, msg, items, root);
    }

    /** 在 data 子树里（深度 ≤3）找数组，抽出带剧 id + 标题的对象为条目。 */
    private static void collectSeries(JsonElement el, List<HgSeriesItem> out, int depth) {
        if (el == null || depth > 3) return;
        if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) {
                HgSeriesItem item = asSeriesItem(child);
                if (item != null) out.add(item);
                else collectSeries(child, out, depth + 1);
            }
        } else if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                collectSeries(e.getValue(), out, depth + 1);
            }
        }
    }

    private static HgSeriesItem asSeriesItem(JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        String id = firstStr(o, "series_id", "book_id", "seriesId");
        if (id == null) id = longStr(o, "item_id");
        String title = firstStr(o, "title", "series_name", "name", "book_name");
        if (id == null || title == null) return null;
        String cover = firstStr(o, "cover", "poster_url", "thumb_url", "cover_url", "image_url");
        String desc = firstStr(o, "desc", "description", "intro");
        int count = intOf(o, "episode_count", -1);
        if (count < 0) count = intOf(o, "episode_cnt", -1);
        // 预约面：rpc 模型 Feed 带 appointment: Appointment{is_subscribed /
        // due_start_text / due_start_time}（classes11.dex），键名蛇形逐字验过；
        // 以前解析器没读、被容忍吞掉，未上线剧的预约态从这里进模型。
        JsonObject appt = obj(o, "appointment");
        return new HgSeriesItem(id, title, cover, desc, count,
                appt != null, appt != null && boolOf(appt, "is_subscribed"),
                appt == null ? null : str(appt, "due_start_text"));
    }

    /**
     * 我的预约列表回包 → 条目表（GET /reading/user/subscribe/list）。
     * data.subscribe_items[] 是 UserSubscribeItem（classes11.dex rpc 模型），
     * 剧信息字段在样本里未逐字定版：逐个条目先按 cell 条目口径直抽，
     * 抽不到再看它的一层子对象（如 item/book/series 包裹），再抽不到整条跳过，
     * 不编造。data 下没有 subscribe_items 时退回整树容忍抽取兜底。
     */
    public static java.util.List<HgSeriesItem> parseSubscribeList(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject data = obj(root, "data");
        List<HgSeriesItem> out = new ArrayList<>();
        JsonArray items = data == null ? null : arr(data, "subscribe_items");
        if (items == null && data != null) items = arr(data, "subscribeItems");
        if (items == null) return parseCellPage(json).getItems();
        for (JsonElement el : items) {
            HgSeriesItem item = asSeriesItem(el);
            if (item == null && el != null && el.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                    item = asSeriesItem(e.getValue());
                    if (item != null) break;
                }
            }
            if (item != null) out.add(item);
        }
        return out;
    }

    // ---------- 基础取值 ----------

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    /** 元素可能是对象，也可能是字符串化 JSON。 */
    private static JsonObject asObject(JsonElement e) {
        if (e == null) return null;
        if (e.isJsonObject()) return e.getAsJsonObject();
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            try {
                JsonElement p = JsonParser.parseString(e.getAsString());
                return p.isJsonObject() ? p.getAsJsonObject() : null;
            } catch (Exception ex) {
                return null;
            }
        }
        return null;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static String firstStr(JsonObject o, String... keys) {
        for (String k : keys) {
            String v = str(o, k);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private static boolean boolOf(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e != null && e.isJsonPrimitive()) {
            if (e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
            if (e.getAsJsonPrimitive().isNumber()) return e.getAsInt() != 0;
        }
        return false;
    }

    /** 数值型 id 字段（如 item_id:J）转字符串；字符串形态也认。 */
    private static String longStr(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        if (e.getAsJsonPrimitive().isNumber()) return String.valueOf(e.getAsLong());
        String s = e.getAsString();
        return s == null || s.isEmpty() ? null : s;
    }

    private static int intOf(JsonObject o, String key, int def) {
        JsonElement e = o == null ? null : o.get(key);
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsInt();
        return def;
    }

    private static long longOf(JsonObject o, String key, long def) {
        JsonElement e = o == null ? null : o.get(key);
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsLong();
        return def;
    }

    private static double doubleOf(JsonObject o, String key, double def) {
        JsonElement e = o == null ? null : o.get(key);
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsDouble();
        return def;
    }

    /** fplay 形态里数值常以字符串给出。 */
    private static int intFlexible(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive()) return 0;
        try {
            return e.getAsJsonPrimitive().isNumber() ? e.getAsInt()
                    : (int) Double.parseDouble(e.getAsString());
        } catch (Exception ex) {
            return 0;
        }
    }

    private static long longFlexible(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive()) return 0;
        try {
            return e.getAsJsonPrimitive().isNumber() ? e.getAsLong()
                    : (long) Double.parseDouble(e.getAsString());
        } catch (Exception ex) {
            return 0;
        }
    }

    private static double doubleFlexible(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive()) return 0;
        try {
            return e.getAsJsonPrimitive().isNumber() ? e.getAsDouble()
                    : Double.parseDouble(e.getAsString());
        } catch (Exception ex) {
            return 0;
        }
    }
}
