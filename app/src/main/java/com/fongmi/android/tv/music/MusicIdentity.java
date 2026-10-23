package com.fongmi.android.tv.music;

import com.fongmi.android.tv.bean.music.MusicTrack;
import com.google.gson.JsonParser;

/**
 * 音乐曲目全局身份键：platform|插件来源|id。
 * <p>
 * id 只是源内编号（FreeJS 各插件的数字 id 互不相干、落雪 id 靠 lx:平台: 前缀
 * 自带命名空间、py 的 id 带 siteKey），单认裸 id 会跨插件串歌：收藏/歌单唯一键、
 * 下载登记键、歌词缓存键一律走本键，不再裸用 trackId。
 * 插件来源取 rawJson 里的 _plugin 戳（FreeJS 曲目才有；无戳的源靠 id 自带
 * 命名空间区分，来源段留空）。id 为空时无身份，返回 null。
 */
public final class MusicIdentity {

    private MusicIdentity() {
    }

    /** rawJson 里的 _plugin（插件 srcUrl），取不到返回 null */
    public static String pluginOf(String rawJson) {
        try {
            if (rawJson == null || rawJson.isEmpty()) return null;
            return JsonParser.parseString(rawJson).getAsJsonObject().get("_plugin").getAsString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String keyOf(String platform, String rawJson, String id) {
        if (id == null || id.isEmpty()) return null;
        String plugin = pluginOf(rawJson);
        return (platform == null ? "" : platform) + "|" + (plugin == null ? "" : plugin) + "|" + id;
    }

    public static String keyOf(MusicTrack track) {
        if (track == null) return null;
        return keyOf(track.platform, track.rawJson, track.id);
    }

    /**
     * 内容平台（kw/tx/wy/kg/mg 这一层），与源层 platform（freejs/lx/py/站 key）是两回事：
     * 「屏蔽平台」屏的是这一层。落雪曲目取 rawJson 的 _platform；FreeJS 聚合插件的
     * 条目自带 platform 字段时取 _item.platform；单平台插件的条目不带此字段、无从
     * 按平台区分，返回 null（＝没有平台身份，屏蔽检查对它不生效，别拿源层 platform
     * 冒充——旧口径拿 "freejs" 比对，用户屏蔽它等于整插件自屏蔽）。
     */
    public static String contentPlatformOf(MusicTrack track) {
        if (track == null || track.rawJson == null || track.rawJson.isEmpty()) return null;
        try {
            com.google.gson.JsonObject wrap = JsonParser.parseString(track.rawJson).getAsJsonObject();
            String p = optStr(wrap, "_platform");
            if (p != null && !p.isEmpty()) return p;
            if (wrap.has("_item") && wrap.get("_item").isJsonObject()) {
                p = optStr(wrap.getAsJsonObject("_item"), "platform");
                if (p != null && !p.isEmpty()) return p;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String optStr(com.google.gson.JsonObject obj, String name) {
        try {
            if (obj.has(name) && !obj.get(name).isJsonNull()) return obj.get(name).getAsString();
        } catch (Throwable ignored) {
        }
        return null;
    }
}
