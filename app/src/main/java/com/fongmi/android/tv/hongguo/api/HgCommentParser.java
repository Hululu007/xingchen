package com.fongmi.android.tv.hongguo.api;

import com.fongmi.android.tv.hongguo.model.HgComment;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 红果评论回包解析（Gson 版），解析规则与回验闸逐条照树里在产的
 * api/HongguoCommentApi（设计 hongguo-comments-shell-design.md 双证锁定）：
 * code==0 才认；逐条验 group_id/book_id 归属、对不上整页作废防误抓别家评论；
 * status!=1 跳过；游标必须单调前进（JSON 游标看 offset、数字游标看数值）。
 * 请求体在 {@link HgCommentClient}，字段口径同出该设计，不另编。
 */
public final class HgCommentParser {

    private HgCommentParser() {
    }

    /**
     * 整剧评论回包的 extra 评分卡（详情页用，本机实拉验通）：credibility_score
     * 红果分／credibility_score_count 评分人数／collectors_count 收藏数／
     * can_comment_threshold 参与评分门槛秒／score_cnt 带分数。解析不出返回
     * 全缺省对象（不为 null，消费侧按 hasAny 决定摆不摆卡）。
     */
    public static com.fongmi.android.tv.hongguo.model.HgSeriesRating parseSeriesRating(String text) {
        JsonObject result = parseRoot(text);
        if (result == null) return com.fongmi.android.tv.hongguo.model.HgSeriesRating.empty();
        JsonObject data = obj(result, "data");
        JsonObject extra = data == null ? null : obj(data, "extra");
        if (extra == null) return com.fongmi.android.tv.hongguo.model.HgSeriesRating.empty();
        float score = -1;
        try {
            if (extra.has("credibility_score") && extra.get("credibility_score").isJsonPrimitive()) {
                score = extra.get("credibility_score").getAsFloat();
            }
        } catch (Exception ignored) {
        }
        return new com.fongmi.android.tv.hongguo.model.HgSeriesRating(
                score,
                num(extra, "credibility_score_count", -1),
                num(extra, "collectors_count", -1),
                (int) num(extra, "can_comment_threshold", -1),
                num(extra, "score_cnt", -1));
    }

    public static HgComment.Page parseList(String text, String series, String group, String cursor, boolean seriesDim) {
        JsonObject result = parseRoot(text);
        if (result == null) return null;
        long previous = validateJsonCursor(cursor);
        if (previous < 0) return null;
        JsonObject data = obj(result, "data");
        JsonObject info = data == null ? null : obj(data, "common_list_info");
        JsonArray rows = data == null ? null : arr(data, "data_list");
        if (info == null || rows == null) return null;
        boolean more = bool(info, "has_more");
        String next = str(info, "cursor");
        if (more && (next.equals(cursor) || validateJsonCursor(next) <= previous)) return null;
        List<HgComment> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < Math.min(rows.size(), 100); i++) {
            JsonObject row = asObj(rows.get(i));
            if (row == null) continue;
            JsonObject comment = obj(row, "comment");
            if (comment == null) continue;
            JsonObject common = obj(comment, "common");
            if (common == null) continue;
            JsonObject expand = obj(comment, "expand");
            if (expand == null) continue;
            if (!group.equals(str(common, "group_id")) || !series.equals(str(expand, "book_id"))) return null;
            if (num(common, "status", -1) != 1) continue;
            String id = str(comment, "comment_id");
            JsonObject contentObj = obj(common, "content");
            String content = contentObj == null ? "" : str(contentObj, "text").trim();
            if (id.isEmpty() || id.length() > 120 || content.isEmpty() || !seen.add(id)) continue;
            JsonObject base = baseInfo(common);
            String avatar = base == null ? "" : str(base, "user_avatar");
            if (avatar.length() > 4096 || !isAvatarUrl(avatar)) avatar = "";
            JsonObject stat = obj(comment, "stat");
            long likes = stat != null && stat.has("digg_count") ? Math.max(0, num(stat, "digg_count", 0)) : likesOf(row, common, expand);
            long replyCount = stat == null ? 0 : Math.max(0, num(stat, "reply_count", 0));
            items.add(new HgComment(id,
                    base == null ? "" : truncate(str(base, "user_name"), 200),
                    avatar, truncate(content, 20000),
                    Math.max(0, num(common, "create_timestamp", 0)), likes, replyCount, null,
                    base == null ? "" : str(base, "user_id"),
                    (int) num(expand, "score", -1)));
        }
        return new HgComment.Page(items, Math.max(0, num(info, "total", 0)), more ? next : "", more);
    }

    public static HgComment.Page parseReplies(String text, String series, String group, String cursor) {
        JsonObject result = parseRoot(text);
        if (result == null) return null;
        long previous = validateNumCursor(cursor);
        if (previous < 0) return null;
        JsonObject data = obj(result, "data");
        if (data == null) return null;
        JsonObject parent = obj(data, "comment");
        if (parent != null) {
            JsonObject pExpand = obj(parent, "expand");
            if (pExpand != null && pExpand.has("book_id") && !series.equals(str(pExpand, "book_id"))) return null;
            JsonObject pCommon = obj(parent, "common");
            if (pCommon != null && pCommon.has("group_id") && !group.equals(str(pCommon, "group_id"))) return null;
        }
        JsonObject info = obj(data, "comment_list_info");
        if (info == null) return null;
        boolean more = bool(info, "has_more");
        String next = str(info, "cursor");
        if (more && validateNumCursor(next) <= previous) return null;
        JsonArray rows = arr(data, "reply_list");
        List<HgComment> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (rows != null) {
            for (int i = 0; i < Math.min(rows.size(), 100); i++) {
                JsonObject row = asObj(rows.get(i));
                if (row == null) continue;
                JsonObject common = obj(row, "Common");
                if (common == null) common = obj(row, "common");
                if (common == null) continue;
                String id = str(row, "reply_id");
                JsonObject contentObj = obj(common, "content");
                String content = contentObj == null ? "" : str(contentObj, "text").trim();
                if (id.isEmpty() || id.length() > 120 || content.isEmpty() || !seen.add(id)) continue;
                JsonObject base = baseInfo(common);
                String avatar = "";
                if (base != null) {
                    avatar = str(base, "user_avatar");
                    if (avatar.isEmpty()) avatar = str(base, "expand_user_avatar");
                    if (avatar.length() > 4096 || !isAvatarUrl(avatar)) avatar = "";
                }
                JsonObject stat = obj(row, "stat");
                long likes = stat == null ? 0 : Math.max(0, num(stat, "digg_count", 0));
                long replyCount = stat == null ? 0 : Math.max(0, num(stat, "reply_count", 0));
                String replyTo = null;
                JsonObject toInfo = obj(row, "reply_to_user_info");
                if (toInfo != null) {
                    JsonObject toBase = obj(toInfo, "base_info");
                    if (toBase != null && !str(toBase, "user_name").isEmpty()) replyTo = str(toBase, "user_name");
                }
                items.add(new HgComment(id,
                        base == null ? "" : truncate(str(base, "user_name"), 200),
                        avatar, truncate(content, 20000),
                        Math.max(0, num(common, "create_timestamp", 0)), likes, replyCount, replyTo,
                        base == null ? "" : str(base, "user_id"), -1));
            }
        }
        return new HgComment.Page(items, Math.max(0, num(info, "total", 0)), more ? next : "", more);
    }

    /** 点赞回包（articleapi do_action）：成功取 action_cnt_data 里该集的新计数，取不到为 -1；失败返回 null。 */
    public static Long parseActionCount(String text, String videoId) {
        JsonObject result = parseRoot(text);
        if (result == null) return null;
        JsonObject data = obj(result, "data");
        JsonObject counts = data == null ? null : obj(data, "action_cnt_data");
        if (counts == null) counts = obj(result, "action_cnt_data");
        if (counts != null && counts.has(videoId)) {
            try {
                return counts.get(videoId).getAsLong();
            } catch (Exception ignored) {
            }
        }
        return -1L;
    }

    /**
     * 写操作（comment/add 等）回包三态：1＝受理、0＝被拒、-1＝解析失败。
     * 受理口径与 parseRoot 一致（信封 code=0，兼容字符串 "0"/"SUCCESS"）；
     * 与 parseRoot 的区别是把「有信封但 code 非 0」（被拒）和「非 JSON／
     * 无 code」（解析失败）分开，供发弹幕等写口分别报错。
     */
    public static int writeResult(String text) {
        if (text == null || text.isEmpty()) return -1;
        try {
            JsonElement el = JsonParser.parseString(text);
            if (!el.isJsonObject()) return -1;
            JsonObject root = el.getAsJsonObject();
            if (!root.has("code")) return -1;
            JsonElement code = root.get("code");
            boolean ok;
            if (code.isJsonPrimitive() && code.getAsJsonPrimitive().isNumber()) ok = code.getAsInt() == 0;
            else {
                String s = code.getAsString();
                ok = "0".equals(s) || "SUCCESS".equalsIgnoreCase(s) || "Success".equals(s);
            }
            return ok ? 1 : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    private static JsonObject parseRoot(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            JsonElement el = JsonParser.parseString(text);
            if (!el.isJsonObject()) return null;
            JsonObject root = el.getAsJsonObject();
            if (!root.has("code")) return null;
            JsonElement code = root.get("code");
            boolean ok;
            if (code.isJsonPrimitive() && code.getAsJsonPrimitive().isNumber()) ok = code.getAsInt() == 0;
            else {
                String s = code.getAsString();
                ok = "0".equals(s) || "SUCCESS".equalsIgnoreCase(s) || "Success".equals(s);
            }
            return ok ? root : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON 游标（comment/list 用）：空串合法（offset 0），非法返回 -1。 */
    public static long validateJsonCursor(String cursor) {
        if (cursor == null || cursor.length() > 8192) return -1;
        if (cursor.trim().isEmpty()) return 0;
        try {
            JsonObject data = JsonParser.parseString(cursor).getAsJsonObject();
            String session = str(data, "session_id");
            if (session.isEmpty() || session.length() > 200) return -1;
            long offset = data.has("offset") ? data.get("offset").getAsLong() : -1;
            return offset >= 0 && offset <= 10000000 ? offset : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 数字游标（reply/list 用）：空串合法（0），非法返回 -1。 */
    public static long validateNumCursor(String cursor) {
        if (cursor == null || cursor.length() > 32) return -1;
        if (cursor.trim().isEmpty()) return 0;
        try {
            long v = Long.parseLong(cursor.trim());
            return v >= 0 && v <= 10000000 ? v : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    static boolean isAvatarUrl(String s) {
        return s != null && (s.startsWith("https://") || s.startsWith("http://"));
    }

    private static JsonObject baseInfo(JsonObject common) {
        JsonObject userInfo = obj(common, "user_info");
        return userInfo == null ? null : obj(userInfo, "base_info");
    }

    private static long likesOf(JsonObject row, JsonObject common, JsonObject expand) {
        JsonObject stat = obj(row, "stat");
        if (stat != null && stat.has("digg_count")) return Math.max(0, num(stat, "digg_count", 0));
        if (expand.has("digg_count")) return Math.max(0, num(expand, "digg_count", 0));
        return Math.max(0, num(common, "digg_count", 0));
    }

    /**
     * 弹幕一窗解析（挖掘账 danmaku-dig-20261028.md，本机匿名实拉三窗验过形）：
     * data_list[].comment.common.content.text 是正文，comment.expand.offset_time
     * 是片内时刻（毫秒）；danmaku_position_type／danmaku_style 回包常缺省
     * （缺省＝滚动白字）。续拉凭 common_list_info.cursor 原样回传，
     * 下一窗起点看 extra.next_query_danmaku_list_time。解析失败返回 null。
     */
    public static com.fongmi.android.tv.hongguo.model.HgDanmaku.Window parseDanmakuWindow(String text) {
        JsonObject result = parseRoot(text);
        if (result == null) return null;
        JsonObject data = obj(result, "data");
        if (data == null) return null;
        JsonArray rows = arr(data, "data_list");
        if (rows == null) return null;
        List<com.fongmi.android.tv.hongguo.model.HgDanmaku> items = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            JsonObject row = asObj(rows.get(i));
            JsonObject comment = row == null ? null : obj(row, "comment");
            if (comment == null) continue;
            JsonObject common = obj(comment, "common");
            JsonObject content = common == null ? null : obj(common, "content");
            String body = content == null ? "" : str(content, "text");
            if (body.isEmpty()) continue;
            JsonObject expand = obj(comment, "expand");
            if (expand == null || !expand.has("offset_time")) continue;
            long offset = num(expand, "offset_time", -1);
            if (offset < 0) continue;
            int posType = (int) num(expand, "danmaku_position_type", 0);
            String color = null;
            JsonObject style = obj(expand, "danmaku_style");
            if (style != null) {
                String hex = str(style, "hex");
                if (!hex.isEmpty()) color = hex;
            }
            JsonObject stat = obj(comment, "stat");
            long digg = stat == null ? 0 : num(stat, "digg_count", 0);
            items.add(new com.fongmi.android.tv.hongguo.model.HgDanmaku(body, offset, posType, color, digg));
        }
        items.sort((a, b) -> Long.compare(a.offsetTimeMs, b.offsetTimeMs));
        JsonObject info = obj(data, "common_list_info");
        String cursor = info == null ? null : str(info, "cursor");
        if (cursor != null && cursor.isEmpty()) cursor = null;
        long total = info == null ? -1 : num(info, "total", -1);
        JsonObject extra = obj(data, "extra");
        long nextQuery = extra == null ? -1 : num(extra, "next_query_danmaku_list_time", -1);
        return new com.fongmi.android.tv.hongguo.model.HgDanmaku.Window(items, cursor, nextQuery, total);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static JsonObject asObj(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonObject obj(JsonObject o, String key) {
        if (o == null || !o.has(key)) return null;
        return asObj(o.get(key));
    }

    private static JsonArray arr(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) return null;
        return o.getAsJsonArray(key);
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static long num(JsonObject o, String key, long def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try {
            return o.get(key).getAsLong();
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean bool(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return false;
        try {
            return o.get(key).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }
}
