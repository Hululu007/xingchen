package com.fongmi.android.tv.hongguo.drama;

import com.github.catvod.utils.Prefers;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 播放模型落盘缓存（2026-10-30 从零重建）：vid → video_model 原串
 * （DramaVideoModel.sourceJson）＋过期时刻，Prefers 单键存一本 JSON 账。
 * 命中且未过期直接以 DramaParser.buildModel 重建（与回包解析同路）；
 * 过期按 url_expire 语义失效（模型自带过期优先，缺失时给 10 分钟保底）。
 * 条数上限 120，超限淘汰最早写入的。
 */
public final class DramaCache {

    private static final String KEY = "drama_models_v1";
    private static final int MAX_ENTRIES = 120;
    private static final long FALLBACK_TTL_MS = 10L * 60 * 1000;

    private DramaCache() {
    }

    public static synchronized DramaVideoModel get(String vid) {
        if (vid == null || vid.isEmpty()) return null;
        JsonObject book = load();
        JsonElement el = book.get(vid);
        if (el == null || !el.isJsonObject()) return null;
        JsonObject e = el.getAsJsonObject();
        long exp = e.has("exp") ? e.get("exp").getAsLong() : 0;
        if (exp > 0 && System.currentTimeMillis() > exp) {
            book.remove(vid);
            save(book);
            return null;
        }
        String json = e.has("json") ? e.get("json").getAsString() : null;
        if (json == null || json.isEmpty()) return null;
        return DramaParser.restoreModel(vid, json, 0, 0);
    }

    public static synchronized void put(DramaVideoModel model) {
        if (model == null || model.getSourceJson() == null
                || model.firstPlayableEntry() == null) return;
        String vid = model.getRequestedId() != null ? model.getRequestedId() : model.getVideoId();
        if (vid == null || vid.isEmpty()) return;
        JsonObject book = load();
        JsonObject e = new JsonObject();
        e.addProperty("json", model.getSourceJson());
        long exp = model.getUrlExpire() > 0
                ? model.getUrlExpire() * 1000L
                : System.currentTimeMillis() + FALLBACK_TTL_MS;
        e.addProperty("exp", exp);
        book.remove(vid);
        book.add(vid, e);
        while (book.size() > MAX_ENTRIES) {
            String first = book.keySet().iterator().next();
            book.remove(first);
        }
        save(book);
    }

    public static synchronized void clear() {
        Prefers.put(KEY, "");
    }

    private static JsonObject load() {
        String raw = Prefers.getString(KEY, "");
        if (raw == null || raw.isEmpty()) return new JsonObject();
        try {
            JsonElement el = JsonParser.parseString(raw);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static void save(JsonObject book) {
        Prefers.put(KEY, book.toString());
    }
}
