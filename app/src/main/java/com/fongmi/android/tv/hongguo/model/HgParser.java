package com.fongmi.android.tv.hongguo.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
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
        // 演员表与完结态（详情真回包 data.video_data 内：celebrities[] 与
        // series_status，openhongguo-probe resp-002/003 逐字实证；官方模型
        // SaasVideoDetailModel.getCelebrityList()/episodesStatus 的下发形态）
        List<HgSeriesDetail.Celebrity> celebrities = new ArrayList<>();
        JsonArray celebs = arr(d, "celebrities");
        if (celebs != null) {
            for (JsonElement el : celebs) {
                if (!el.isJsonObject()) continue;
                JsonObject co = el.getAsJsonObject();
                String nick = firstStr(co, "nickname", "name");
                String role = str(co, "role_name");
                if (nick == null && role == null) continue;
                celebrities.add(new HgSeriesDetail.Celebrity(
                        nick, role, str(co, "avatar"), str(co, "sub_title")));
            }
        }
        return new HgSeriesDetail(seriesId, title, cover, desc, author, count, episodes, root,
                appt != null, appt != null && boolOf(appt, "is_subscribed"),
                appt == null ? null : str(appt, "due_start_text"),
                celebrities, intOrNull(d, "series_status"),
                statementOf(intOrNull(d, "ai_usage_type")),
                boolOf(d, "is_multi_season"), firstStr(d, "related_album_id_str", "related_album_id"));
    }

    /**
     * 专辑详情回包 → 各季条目（照 openhongguo ParseAlbum 的线上形状）：
     * data.album_data{album_id_str/album_title/…/series_list|video_list[]}，
     * 每季 series_id_str/series_title/season_index/series_cover/episode_cnt/
     * series_status。code 非 0（如 101001 已下架）返回空表，调用方直进剧。
     */
    public static List<HgSeriesItem> parseAlbum(String json) {
        List<HgSeriesItem> out = new ArrayList<>();
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return out;
        }
        String code = str(root, "code");
        if (code != null && !"0".equals(code)) return out;
        JsonObject data = obj(root, "data");
        if (data == null) return out;
        JsonObject album = obj(data, "album_data");
        if (album == null) return out;
        JsonArray rows = arr(album, "series_list");
        if (rows == null) rows = arr(album, "video_list");
        if (rows == null) return out;
        List<HgSeriesItem> seasons = new ArrayList<>();
        for (JsonElement el : rows) {
            if (!el.isJsonObject()) continue;
            JsonObject r = el.getAsJsonObject();
            String sid = firstStr(r, "series_id_str", "series_id");
            if (sid == null) continue;
            seasons.add(new HgSeriesItem(sid, str(r, "series_title"), str(r, "series_cover"),
                    null, intOf(r, "episode_cnt", -1), false, false, null,
                    null, null, null, subTitleList(r), null, false,
                    firstStr(album, "album_id_str", "album_id"), intOf(album, "season_cnt", -1),
                    Collections.emptyList(), -1, -1, intOrNull(r, "series_status"), null, null, null,
                    null, null));
        }
        final JsonArray seasonRows = rows;
        seasons.sort((a, b) -> Integer.compare(seasonIndex(seasonRows, a.getSeriesId()),
                seasonIndex(seasonRows, b.getSeriesId())));
        out.addAll(seasons);
        return out;
    }

    /**
     * 季行副标题表（U5 定版，u7-u5-dig-20261028.md）：官方系列单行的副行
     * 就是 sub_title_list 前两条，没有独立的集数/热度字段位。REST 侧键名
     * 两形并存（video_detail 是 series_sub_title_list、搜索 subscribe 行是
     * sub_title_list），元素两形并存（对象 {content}／纯字符串），全容忍抽。
     * 专辑回包本体尚无真样本，键名集待真样本逐字校——现按已实证字段族接。
     */
    private static List<String> subTitleList(JsonObject r) {
        List<String> out = new ArrayList<>();
        JsonArray a = arr(r, "series_sub_title_list");
        if (a == null) a = arr(r, "sub_title_list");
        if (a == null) return out;
        for (JsonElement el : a) {
            String v = null;
            if (el.isJsonObject()) v = str(el.getAsJsonObject(), "content");
            else if (el.isJsonPrimitive()) {
                try {
                    v = el.getAsString();
                } catch (Exception ignored) {
                }
            }
            if (v != null && !v.isEmpty()) out.add(v);
        }
        return out;
    }

    private static int seasonIndex(JsonArray rows, String seriesId) {
        for (JsonElement el : rows) {
            if (!el.isJsonObject()) continue;
            JsonObject r = el.getAsJsonObject();
            if (seriesId.equals(firstStr(r, "series_id_str", "series_id"))) {
                int idx = intOf(r, "season_index", -1);
                return idx < 0 ? Integer.MAX_VALUE : idx;
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * 搜索联想回包（openhongguo ParseSuggest 线上形状）：先取
     * data.query_result_v2、再 suggest_result（行名 name/keyword/word_type），
     * 都没有时回落 data.query_result 纯词表。code 非 0 回空表。
     */
    public static java.util.List<HgSuggestion> parseSuggest(String json) {
        java.util.List<HgSuggestion> out = new java.util.ArrayList<>();
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return out;
        }
        String code = str(root, "code");
        if (code != null && !"0".equals(code)) return out;
        JsonObject data = obj(root, "data");
        if (data == null) return out;
        for (String key : new String[]{"query_result_v2", "suggest_result"}) {
            JsonArray rows = arr(data, key);
            if (rows == null) continue;
            for (JsonElement el : rows) {
                if (!el.isJsonObject()) continue;
                JsonObject r = el.getAsJsonObject();
                String name = str(r, "name");
                if (name == null || name.isEmpty()) continue;
                out.add(new HgSuggestion(name, str(r, "keyword"), intOf(r, "word_type", -1)));
            }
            if (!out.isEmpty()) return out;
        }
        JsonArray plain = arr(data, "query_result");
        if (plain != null) {
            for (JsonElement el : plain) {
                if (el.isJsonPrimitive()) {
                    String w = el.getAsString();
                    if (w != null && !w.isEmpty()) out.add(new HgSuggestion(w, w, -1));
                }
            }
        }
        return out;
    }

    /**
     * 搜索框提示词回包（openhongguo ParseCue 线上形状）：data_v1[].text，
     * 去空去重保序。code 非 0 回空表。
     */
    public static java.util.List<String> parseCue(String json) {
        java.util.List<String> out = new java.util.ArrayList<>();
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return out;
        }
        String code = str(root, "code");
        if (code != null && !"0".equals(code)) return out;
        JsonArray rows = arr(root, "data_v1");
        if (rows == null) return out;
        for (JsonElement el : rows) {
            if (!el.isJsonObject()) continue;
            String t = str(el.getAsJsonObject(), "text");
            if (t != null && !t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** 数字字段取值：没带或非数字为 null（区别于 intOf 的 -1 哨兵，状态类字段用）。 */
    private static Integer intOrNull(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        return e.getAsInt();
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
        // 搜索回包的页签面（data.search_tabs）：只收视频面三页签
        // （11 综合/29 剧集/30 视频），顺序照服务端下发
        List<HgSearchTab> tabs = new ArrayList<>();
        // search_tabs 的挂载层随回包形变（存档真回包在根级、无 data 包裹）：
        // 与 collectSeries 同口径，data 在用 data、否则用根级
        JsonObject tabScope = data != null && data.has("search_tabs") ? data : root;
        if (tabScope.has("search_tabs") && tabScope.get("search_tabs").isJsonArray()) {
            for (com.google.gson.JsonElement el : tabScope.getAsJsonArray("search_tabs")) {
                if (el == null || !el.isJsonObject()) continue;
                JsonObject t = el.getAsJsonObject();
                int type = intOf(t, "tab_type", -1);
                String title = str(t, "title");
                if ((type == 11 || type == 29 || type == 30)
                        && title != null && !title.isEmpty()) {
                    tabs.add(new HgSearchTab(type, title));
                }
            }
        }
        return new HgCellPage(code, msg, items, root, false, null, null, null, tabs);
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
            // 单元格自身的短片（video_detail.video_list 首条自带 vid/封面/
            // 时长/计数，是完整短片对象）先入流——此前只收 video_detail_list
            // 的跨推卡、把单元格本体短片整条丢了（存档实证：推荐 tab16
            // inner[0]，audit-1455-identical.md）
            JsonArray own = arr(detail, "video_list");
            if (own != null && own.size() > 0 && own.get(0).isJsonObject()) {
                JsonObject first = own.get(0).getAsJsonObject();
                if (firstStr(first, "vid") != null && firstStr(first, "series_id") != null) {
                    // 作者信息挂在 detail 本体（ugc_user_info），不在
                    // video_list[0] 里——本体短片入流时把 ugc 借道塞进
                    // 合成 video_detail，让 normalize 的作者抬升照常命中；
                    // 条目自带 video_detail 时以自带为准、不借道。
                    JsonObject ugc = obj(detail, "ugc_user_info");
                    if (ugc != null && obj(first, "video_detail") == null) {
                        JsonObject wrapped = first.deepCopy();
                        JsonObject carrier = new JsonObject();
                        carrier.add("ugc_user_info", ugc);
                        wrapped.add("video_detail", carrier);
                        first = wrapped;
                    }
                    addDiscoverItem(first, out);
                }
            }
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
            // 完结态在 video_data 条目的 video_detail 子对象上（频道存档逐层
            // 实证），归一到条目顶层；条目自身带同名键时下方覆盖循环以自身为准
            Integer status = intOrNull(vd, "series_status");
            if (status != null) merged.addProperty("series_status", status);
            Integer aiUsage = intOrNull(vd, "ai_usage_type");
            if (aiUsage != null) merged.addProperty("ai_usage_type", aiUsage);
            // 专辑关系与 UGC 作者同在 video_detail 里（存档逐层实证：详情面
            // is_multi_season/related_album_id、cell 面 ugc_user_info）——不抬
            // 到条目顶层，asSeriesItem 读不到：多季剧在流里永远认不出、
            // 系列单弹不出，短片作者行/头像永远空（2026-10-28 逐项重对）
            if (vd.has("is_multi_season")) {
                merged.addProperty("is_multi_season", boolOf(vd, "is_multi_season"));
            }
            putIfPresent(merged, "album_id", firstStr(vd, "album_id_str", "album_id"));
            if (intOf(vd, "album_serial_count", -1) >= 0) {
                merged.addProperty("album_serial_count", intOf(vd, "album_serial_count", -1));
            }
            JsonObject ugc = obj(vd, "ugc_user_info");
            JsonObject ugcBase = ugc == null ? null : obj(ugc, "base_info");
            if (ugcBase != null) {
                putIfPresent(merged, "author_name", str(ugcBase, "user_name"));
                putIfPresent(merged, "author_avatar", str(ugcBase, "user_avatar"));
                putIfPresent(merged, "author_desc", str(ugcBase, "description"));
            }
            // 作者 uid 同在 ugc_user_info 里（存档真回包实证：ugc 顶层与
            // base_info 各一份同值）——作者主页/关注作者的 object_id 来源；
            // 此前只抬名与头像、uid 被漏掉，头像上的关注只能退化成追剧。
            if (ugc != null) {
                String uid = str(ugc, "user_id");
                if (uid == null && ugcBase != null) uid = str(ugcBase, "user_id");
                putIfPresent(merged, "author_uid", uid);
            }
        }
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            // 条目自带空串不许盖掉已从 video_detail 抬升的真值：推荐频道
            // video_data 条目自带 cover:""/vid:""、真封面只在 video_detail
            // .series_cover 里——无条件覆盖时封面被空串抹掉（2026-10-28
            // 《镇国杀神》真回包探针定罪：parseDiscoverTab 出 cover=null）
            JsonElement v = e.getValue();
            if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()
                    && v.getAsString().isEmpty()
                    && firstStr(merged, e.getKey()) != null) {
                continue;
            }
            merged.add(e.getKey(), v);
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
        if (firstStr(merged, "vid", "video_id") == null) {
            // 频道 video_data 条目的 vid 恒空（存档 24 条全量实证），短片
            // 指针在 highlight.vid（数值型，指向 video_detail.video_list
            // 中的被推集，不恒为首集）——不接它条目永远是剧头、播前被迫
            // 先拉详情（2026-10-28 逐项重对定罪，audit-1455-identical.md）
            JsonObject hl = obj(o, "highlight");
            String hv = hl == null ? null : flexStr(hl, "vid");
            if (hv != null) merged.addProperty("vid", hv);
        }
        return merged;
    }

    /** 数值/字符串两形的 id 读取（highlight.vid 是数值型）。 */
    private static String flexStr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        if (e.getAsJsonPrimitive().isNumber()) return String.valueOf(e.getAsLong());
        String s = e.getAsString();
        return s == null || s.isEmpty() ? null : s;
    }

    private static void putIfPresent(JsonObject o, String key, String value) {
        if (value != null && !value.isEmpty()) o.addProperty(key, value);
    }

    /** 目标键缺失或为空串、且来源值非空时才填（来源为空不写，不拿空串盖有值）。 */
    private static void fillIfBlank(JsonObject o, String key, String value) {
        if (value == null || value.isEmpty()) return;
        String cur = str(o, key);
        if (cur == null || cur.isEmpty()) o.addProperty(key, value);
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
            JsonObject cell = el.getAsJsonObject();
            // video_detail 本体形：条目在 video_list 里、作者挂本体
            // ugc_user_info 上，asSeriesItem 只读条目自身字段永远抬不到
            // 作者——在此把作者四件补进条目副本再收（条目自带同名键
            // 不覆盖），补过的 video_list 不再走通用递归防重复收条。
            JsonObject ugc = obj(cell, "ugc_user_info");
            JsonArray vids = arr(cell, "video_list");
            boolean lifted = false;
            if (ugc != null && vids != null) {
                JsonObject base = obj(ugc, "base_info");
                String aName = base == null ? null : str(base, "user_name");
                String aAvatar = base == null ? null : str(base, "user_avatar");
                String aDesc = base == null ? null : str(base, "description");
                String aUid = str(ugc, "user_id");
                if (aUid == null && base != null) aUid = str(base, "user_id");
                if (aName != null || aUid != null) {
                    lifted = true;
                    for (JsonElement ve : vids) {
                        if (ve == null || !ve.isJsonObject()) continue;
                        JsonObject copy = ve.getAsJsonObject().deepCopy();
                        // 条目自带空串同样算缺：str() 对 "" 返回非 null，旧判
                        // 「==null 才补」会让自带 author_*:"" 挡住 ugc 层真值
                        // ——与 normalizeDiscoverEntry 的空串盖真值同型
                        // （96fa6fa5 只修了归一路径，此处是 collectSeries 路径）
                        fillIfBlank(copy, "author_name", aName);
                        fillIfBlank(copy, "author_avatar", aAvatar);
                        fillIfBlank(copy, "author_desc", aDesc);
                        fillIfBlank(copy, "author_uid", aUid);
                        HgSeriesItem item = asSeriesItem(copy);
                        if (item != null) out.add(item);
                    }
                }
            }
            for (Map.Entry<String, JsonElement> e : cell.entrySet()) {
                if (lifted && "video_list".equals(e.getKey())) continue;
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
                longOf(o, "comment_count", -1), longOf(o, "digged_count", -1),
                intOrNull(o, "series_status"), statementOf(intOrNull(o, "ai_usage_type")),
                str(o, "author_name"), str(o, "author_avatar"),
                str(o, "author_uid"), str(o, "author_desc"));
    }

    /**
     * 作者声明映射：ai_usage_type=1 → 「内容由AI生成」（官方信息面实拍串，
     * 研究账 §3.5）；其余值无实拍样本，不编造，返回 null 不显。
     */
    static String statementOf(Integer aiUsageType) {
        return aiUsageType != null && aiUsageType == 1 ? "内容由AI生成" : null;
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
    /**
     * 账号资料解析（passport 信封，account-info-dig-20261028.md 定版）：
     * {message:"success", data:{screen_name, avatar_url, name, user_id,
     * sec_user_id, media:{avatar_url}}}。昵称＝screen_name（空回落 name），
     * 头像＝avatar_url（空回落 media.avatar_url）。data 缺失或名/头像全空
     * 返回 null（半份资料不落镜像）。注：字段集是客户端愿读的键，真回包
     * 样本待新版首登抓一发对——解析容忍多/缺键。
     */
    public static HgAccountProfile parseAccountInfo(String json) {
        if (json == null || json.isEmpty()) return null;
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
        JsonObject data = obj(root, "data");
        if (data == null) return null;
        String name = firstStr(data, "screen_name", "name", "username");
        String avatar = firstStr(data, "avatar_url");
        if (avatar == null) {
            JsonObject media = obj(data, "media");
            if (media != null) avatar = firstStr(media, "avatar_url");
        }
        String uid = firstStr(data, "user_id", "sec_user_id");
        if (name == null && avatar == null) return null;
        return new HgAccountProfile(name, avatar, uid);
    }

    /**
     * 用户资料解析（GET /reading/user/profile/get/，模型逐字段见
     * {@link HgUserProfile} 类注）：data.ugc_user{user_id, base_info
     * {user_name/user_avatar/description}, user_relation{follow_user_num/
     * fans_num/recv_digged_count/relation_type}}。计数字段缺失记 -1（整行
     * 不显的判据在 HgUserProfile.hasRelationCounts）；relation_type 兼容
     * 数字（UserRelationType 值 1–4）与枚举名字符串两形态。
     */
    public static HgUserProfile parseUserProfile(String json) {
        if (json == null || json.isEmpty()) return null;
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
        JsonObject data = obj(root, "data");
        if (data == null) data = root;
        JsonObject ugc = obj(data, "ugc_user");
        if (ugc == null) return null;
        JsonObject base = obj(ugc, "base_info");
        JsonObject rel = obj(ugc, "user_relation");
        String relationRaw = null;
        Boolean followed = null;
        if (rel != null) {
            JsonElement rt = rel.get("relation_type");
            if (rt != null && rt.isJsonPrimitive()) {
                if (rt.getAsJsonPrimitive().isNumber()) {
                    int v = rt.getAsInt();
                    relationRaw = String.valueOf(v);
                    followed = v == 2 || v == 4;
                } else if (rt.getAsJsonPrimitive().isString()) {
                    relationRaw = rt.getAsString();
                    followed = "Follow".equals(relationRaw) || "MutualFollow".equals(relationRaw);
                }
            }
        }
        return new HgUserProfile(
                firstStr(ugc, "user_id"),
                base == null ? null : firstStr(base, "user_name"),
                base == null ? null : firstStr(base, "user_avatar"),
                base == null ? null : firstStr(base, "description"),
                rel == null ? -1 : intOf(rel, "follow_user_num", -1),
                rel == null ? -1 : intOf(rel, "fans_num", -1),
                rel == null ? -1 : intOf(rel, "recv_digged_count", -1),
                relationRaw, followed);
    }
}
