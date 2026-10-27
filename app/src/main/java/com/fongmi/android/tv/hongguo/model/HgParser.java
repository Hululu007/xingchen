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

    /**
     * 单集 video_model 回包（Outer=3 口径，海阔 hgApiOnce 同形态）→ 单集模型：
     * data.video_model 是字符串化 JSON（也容忍对象形态），宽高在 data 一层。
     * 无 data / 无 video_model 返回 null（hgApiOnce 同判据）。
     */
    public static HgVideoModel parseSingleVideoModel(String json, String requestedId) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject data = obj(root, "data");
        if (data == null) return null;
        JsonObject vm = asObject(data.get("video_model"));
        if (vm == null) return null;
        int vw = intOf(data, "video_width", 0);
        int vh = intOf(data, "video_height", 0);
        return buildModel(requestedId, vm, vw, vh);
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
                if (idx < 0) idx = intOf(eo, "episode_index", -1);
                if (idx < 0) idx = intOf(eo, "vid_index", i + 1);
                episodes.add(new HgSeriesDetail.Episode(vid, idx, firstStr(eo, "title", "name")));
                i++;
            }
        }
        String seriesId = firstStr(d, "series_id_str", "series_id", "book_id", "id");
        String title = firstStr(d, "series_title", "title", "series_name", "name");
        String cover = firstStr(d, "series_cover", "cover", "poster_url", "thumb_url", "cover_url");
        String desc = firstStr(d, "series_intro", "desc", "description", "intro", "abstract");
        String author = firstStr(d, "author_name", "author");
        int count = intOf(d, "episode_count", -1);
        if (count < 0) count = intOf(d, "episode_cnt", episodes.isEmpty() ? -1 : episodes.size());
        JsonObject appt = obj(d, "appointment");
        return new HgSeriesDetail(seriesId, title, cover, desc, author, count, episodes, root,
                appt != null, appt != null && boolOf(appt, "is_subscribed"),
                appt == null ? null : str(appt, "due_start_text"));
    }

    /**
     * 批量详情 multi_video_detail 回包 → 以剧 id 为键的详情表
     * （openhongguo ParseSeriesBatch 形态：data 是 {剧id: {video_data: {...}}}）。
     * 每条包回单剧信封复用 {@link #parseVideoDetail} 的容忍抽取。
     */
    public static Map<String, HgSeriesDetail> parseSeriesBatch(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, HgSeriesDetail> out = new LinkedHashMap<>();
        JsonObject data = obj(root, "data");
        if (data == null) return out;
        for (Map.Entry<String, JsonElement> e : data.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject wrap = new JsonObject();
            wrap.add("data", e.getValue());
            HgSeriesDetail detail = parseVideoDetail(wrap.toString());
            if (detail == null) continue;
            String key = detail.getSeriesId() != null ? detail.getSeriesId() : e.getKey();
            out.put(key, detail);
        }
        return out;
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

    // ---------- bookmall 频道面（openhongguo parse.go 逐函数对照移植） ----------

    /**
     * 频道开页回包 → 指定 tab_type 的一页（对照 ParseDiscoverTab＋
     * appendCatalogCell＋DiscoverContinuationTab）：只收 tab_type 命中的
     * tab 项，单元格按 video_data / video_detail 卡 / 嵌套 cell_data 递归抽；
     * has_more 取 tab 项的；续页凭证从同 tab 抽（session_id 在 tab 上、
     * cell 取 cell_id_str 非空且 show_type≠0 的、优先带嵌套 cell_data 的）。
     */
    public static HgCellPage parseDiscoverTab(String json, int tabType) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        int code = intOf(root, "code", 0);
        String msg = str(root, "message");
        List<HgSeriesItem> items = new ArrayList<>();
        JsonObject data = obj(root, "data");
        JsonObject tab = data == null ? null : findTab(data, tabType);
        boolean hasMore = false;
        HgContinuation cont = null;
        if (tab != null) {
            hasMore = boolOf(tab, "has_more");
            JsonArray cells = arr(tab, "cell_data");
            if (cells != null) {
                for (JsonElement cell : cells) collectDiscoverCells(cell, items, 0);
            }
            cont = extractContinuation(tab, tabType);
        }
        return new HgCellPage(code, msg, items, root, hasMore,
                cont == null ? null : cont.getOffset(),
                cont == null ? null : cont.getSessionId(), cont);
    }

    /**
     * 频道续页回包 → 一页（对照 ParseCellChange）：data{has_more,
     * next_offset, session_id, cell_view}，cell_view 走同一套单元格抽取。
     */
    public static HgCellPage parseCellChange(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        int code = intOf(root, "code", 0);
        String msg = str(root, "message");
        List<HgSeriesItem> items = new ArrayList<>();
        JsonObject data = obj(root, "data");
        boolean hasMore = false;
        String nextOffset = null;
        String sessionId = null;
        if (data != null) {
            hasMore = boolOf(data, "has_more");
            nextOffset = firstStr(data, "next_offset");
            if (nextOffset == null) {
                JsonElement no = data.get("next_offset");
                if (no != null && no.isJsonPrimitive() && no.getAsJsonPrimitive().isNumber()) {
                    nextOffset = String.valueOf(no.getAsLong());
                }
            }
            sessionId = str(data, "session_id");
            JsonElement view = data.get("cell_view");
            if (view != null) collectDiscoverCells(view, items, 0);
        }
        return new HgCellPage(code, msg, items, root, hasMore, nextOffset, sessionId, null);
    }

    private static JsonObject findTab(JsonObject data, int tabType) {
        JsonArray tabs = arr(data, "tab_item");
        if (tabs == null) return null;
        for (JsonElement el : tabs) {
            if (el != null && el.isJsonObject()
                    && intOf(el.getAsJsonObject(), "tab_type", -1) == tabType) {
                return el.getAsJsonObject();
            }
        }
        return null;
    }

    /** 续页凭证抽取（对照 DiscoverContinuationTab 的挑选顺序）。 */
    private static HgContinuation extractContinuation(JsonObject tab, int tabType) {
        String sessionId = str(tab, "session_id");
        if (sessionId == null || sessionId.isEmpty()) return null;
        String template = null;
        JsonElement ct = tab.get("client_template");
        if (ct != null && ct.isJsonPrimitive()) {
            if (ct.getAsJsonPrimitive().isNumber()) {
                if (ct.getAsLong() > 0) template = String.valueOf(ct.getAsLong());
            } else {
                String s = ct.getAsString();
                if (s != null && !s.isEmpty()) template = s;
            }
        }
        JsonArray cells = arr(tab, "cell_data");
        if (cells == null) return null;
        JsonObject first = null;
        JsonObject nested = null;
        for (JsonElement el : cells) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject cell = el.getAsJsonObject();
            String cellId = firstStr(cell, "cell_id_str", "cell_id");
            if (cellId == null) cellId = longStr(cell, "cell_id");
            if (cellId == null) continue;
            int showType = intOf(cell, "show_type", 0);
            if (showType == 0) continue;
            if (first == null) first = cell;
            JsonElement inner = cell.get("cell_data");
            if (inner != null && inner.isJsonArray() && inner.getAsJsonArray().size() > 0) {
                nested = cell;
                break;
            }
        }
        JsonObject pick = nested != null ? nested : first;
        if (pick == null) return null;
        String pickId = firstStr(pick, "cell_id_str", "cell_id");
        if (pickId == null) pickId = longStr(pick, "cell_id");
        if (pickId == null) return null;
        return new HgContinuation(pickId, sessionId,
                template, String.valueOf(intOf(pick, "show_type", 0)),
                String.valueOf(tabType), "0");
    }

    /** 单元格递归抽取（对照 appendCatalogCell 的三路：video_data 数组/对象、video_detail 卡表、嵌套 cell_data）。 */
    private static void collectDiscoverCells(JsonElement el, List<HgSeriesItem> out, int depth) {
        if (el == null || depth > 8) return;
        if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) collectDiscoverCells(child, out, depth);
            return;
        }
        if (!el.isJsonObject()) return;
        JsonObject cell = el.getAsJsonObject();
        JsonElement vd = cell.get("video_data");
        if (vd != null) {
            if (vd.isJsonArray()) {
                for (JsonElement entry : vd.getAsJsonArray()) addDiscoverItem(entry, out);
            } else {
                addDiscoverItem(vd, out);
            }
        }
        JsonObject detail = obj(cell, "video_detail");
        if (detail != null) {
            JsonArray cards = arr(detail, "video_detail_list");
            if (cards != null) {
                for (JsonElement card : cards) addDiscoverItem(card, out);
            }
        }
        JsonElement inner = cell.get("cell_data");
        if (inner != null) collectDiscoverCells(inner, out, depth + 1);
    }

    private static void addDiscoverItem(JsonElement entry, List<HgSeriesItem> out) {
        if (entry == null || !entry.isJsonObject()) return;
        HgSeriesItem item = asSeriesItem(normalizeDiscoverEntry(entry.getAsJsonObject()));
        if (item != null) out.add(item);
    }

    /**
     * 频道条目键名归一：video_data 条目的剧信息常嵌在 video_detail 子对象
     * （series_title / series_cover / series_intro），video_detail_list 卡用
     * series_id_str / series_title 等直名——统一映射到 asSeriesItem 的扁平
     * 口径（条目自身字段优先，video_detail 只补缺）。
     */
    private static JsonObject normalizeDiscoverEntry(JsonObject o) {
        JsonObject merged = new JsonObject();
        JsonObject vd = obj(o, "video_detail");
        if (vd != null) {
            putIfPresent(merged, "title", str(vd, "series_title"));
            putIfPresent(merged, "cover", str(vd, "series_cover"));
            putIfPresent(merged, "desc", str(vd, "series_intro"));
            if (intOf(vd, "episode_cnt", -1) >= 0) {
                merged.addProperty("episode_cnt", intOf(vd, "episode_cnt", -1));
            }
            // 计数的位置随频道变（2026-10-28 存档回包逐层实测）：推荐频道在
            // video_data 条目顶层（digged_count；comment_count 不下发），
            // 漫剧/真人剧在 video_detail.video_list[0]（两项齐全）——归一到
            // 条目顶层，asSeriesItem 一处读。条目自身带同名字段时以自身为准
            // （下方 entrySet 覆盖循环会盖回来）
            JsonArray vids = arr(vd, "video_list");
            if (vids != null && vids.size() > 0 && vids.get(0).isJsonObject()) {
                JsonObject v0 = vids.get(0).getAsJsonObject();
                if (longOf(v0, "comment_count", -1) >= 0) {
                    merged.addProperty("comment_count", longOf(v0, "comment_count", -1));
                }
                if (longOf(v0, "digged_count", -1) >= 0) {
                    merged.addProperty("digged_count", longOf(v0, "digged_count", -1));
                }
            }
            putIfPresent(merged, "series_id", firstStr(vd, "series_id", "series_id_str"));
        }
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            merged.add(e.getKey(), e.getValue());
        }
        if (firstStr(merged, "series_id", "book_id") == null) {
            putIfPresent(merged, "series_id", firstStr(merged, "series_id_str"));
        }
        if (firstStr(merged, "title") == null) {
            putIfPresent(merged, "title", firstStr(merged, "series_title"));
        }
        if (firstStr(merged, "cover") == null) {
            putIfPresent(merged, "cover", firstStr(merged, "series_cover"));
        }
        if (firstStr(merged, "desc") == null) {
            putIfPresent(merged, "desc", firstStr(merged, "series_intro"));
        }
        return merged;
    }

    private static void putIfPresent(JsonObject o, String key, String value) {
        if (value != null && !value.isEmpty()) o.addProperty(key, value);
    }

    /**
     * 在 data 子树里找数组，抽出带剧 id + 标题的对象为条目。深度上限 8：
     * bookmall/tab 真回包（2026-10-27 实拉存档）的剧条目在
     * tab_item[].cell_data[].cell_data[].video_data[] 里（带 series_id /
     * title / cover / episode_cnt），嵌套 7 层——旧上限 3 永远抽不到，
     * 是路径修好后解析面剩下的第二个坑。
     */
    private static void collectSeries(JsonElement el, List<HgSeriesItem> out, int depth) {
        if (el == null || depth > 8) return;
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
        return asSeriesItem(el, 0);
    }

    private static HgSeriesItem asSeriesItem(JsonElement el, int depth) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        String id = firstStr(o, "series_id", "book_id", "seriesId");
        if (id == null) id = longStr(o, "item_id");
        String title = firstStr(o, "title", "series_name", "name", "book_name");
        if (id == null || title == null) return null;
        String cover = firstStr(o, "cover", "poster_url", "thumb_url", "cover_url", "image_url");
        String desc = firstStr(o, "desc", "description", "intro", "video_desc");
        int count = intOf(o, "episode_count", -1);
        if (count < 0) count = intOf(o, "episode_cnt", -1);
        // 预约面：rpc 模型 Feed 带 appointment: Appointment{is_subscribed /
        // due_start_text / due_start_time}（classes11.dex），键名蛇形逐字验过；
        // 以前解析器没读、被容忍吞掉，未上线剧的预约态从这里进模型。
        JsonObject appt = obj(o, "appointment");
        // 短片面（研究账 §2.5，bookmall/tab 真回包逐字）：短片自身 vid、
        // 角标 tag_info.text、热度 rec_text、分类 category_schema（真回包是
        // 字符串化 JSON，解开后 [].name——验收 B1）、朝向 vertical——
        // 流改短片流后条目靠这组字段成立。
        String vid = firstStr(o, "vid", "video_id");
        JsonObject tagInfo = obj(o, "tag_info");
        String badge = tagInfo == null ? null : str(tagInfo, "text");
        if (badge == null) {
            JsonArray ctl = arr(o, "cover_tag_info_list");
            if (ctl != null && ctl.size() > 0 && ctl.get(0).isJsonObject()) {
                badge = str(ctl.get(0).getAsJsonObject(), "text");
            }
        }
        List<String> tags = new ArrayList<>();
        JsonArray schema = asArray(o.get("category_schema"));
        if (schema != null) {
            for (JsonElement te : schema) {
                if (te.isJsonObject()) {
                    String name = str(te.getAsJsonObject(), "name");
                    if (name != null && !name.isEmpty()) tags.add(name);
                }
            }
        }
        Boolean vertical = null;
        JsonElement ve = o.get("vertical");
        if (ve != null && ve.isJsonPrimitive() && ve.getAsJsonPrimitive().isBoolean()) {
            vertical = ve.getAsBoolean();
        }
        // 多季/专辑面（研究账 §5.3，classes11 字符串池实证键名）：回包带了才有，
        // 没带全空——系列半屏单只认这组真数据（账 §5.4）。
        List<HgSeriesItem> siblings = new ArrayList<>();
        if (depth == 0) {
            JsonArray album = arr(o, "album_data_list");
            if (album != null) {
                for (JsonElement se : album) {
                    HgSeriesItem sib = asSeriesItem(se, 1);
                    if (sib != null) siblings.add(sib);
                }
            }
        }
        return new HgSeriesItem(id, title, cover, desc, count,
                appt != null, appt != null && boolOf(appt, "is_subscribed"),
                appt == null ? null : str(appt, "due_start_text"),
                vid, badge, str(o, "rec_text"), tags, vertical,
                boolOf(o, "is_multi_season"),
                firstStr(o, "album_id_str", "album_id"),
                intOf(o, "album_serial_count", -1), siblings,
                longOf(o, "comment_count", -1), longOf(o, "digged_count", -1));
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

    /** 元素可能是数组，也可能是字符串化 JSON（真回包 category_schema 即此形态，验收 B1）；解不开安静返回 null。 */
    private static JsonArray asArray(JsonElement e) {
        if (e == null) return null;
        if (e.isJsonArray()) return e.getAsJsonArray();
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            try {
                JsonElement p = JsonParser.parseString(e.getAsString());
                return p.isJsonArray() ? p.getAsJsonArray() : null;
            } catch (Exception ex) {
                return null;
            }
        }
        return null;
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
