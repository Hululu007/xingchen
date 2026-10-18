package com.fongmi.android.tv.legado;

import android.content.Context;
import android.content.SharedPreferences;

import com.fongmi.android.tv.App;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 书源存储与管理底座：导入（文本/文件/链接）· 列表 · 启用停用 · 删除。
 * <p>
 * 读回一律 TypeToken（R8 泛型关卡）；type 1/3 只存不跑，UI 标「暂不支持」。
 * 不内置任何书源：只做执行器。
 */
public class LegadoStore {

    private static final String PREFS = "legado_sources";
    private static final String KEY = "sources";
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<Entry>>() {
    }.getType();

    /** 存储条目：源 JSON 原文＋开关。 */
    public static class Entry {
        public String json = "";
        public boolean enabled = true;
        public long addedAt = 0L;
        /** 解析缓存（不持久化）。 */
        transient LegadoSource parsed;
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized List<Entry> all() {
        String raw = prefs().getString(KEY, "[]");
        try {
            List<Entry> list = GSON.fromJson(raw, LIST_TYPE);
            return list == null ? new ArrayList<>() : list;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    private static synchronized void saveAll(List<Entry> list) {
        for (Entry e : list) e.parsed = null;
        prefs().edit().putString(KEY, GSON.toJson(list, LIST_TYPE)).apply();
    }

    public static LegadoSource parse(String json) {
        LegadoSource s;
        try {
            s = GSON.fromJson(json, LegadoSource.class);
        } catch (Throwable t) {
            throw new LegadoException("书源 JSON 解析失败: " + t.getMessage());
        }
        if (s == null || s.bookSourceUrl == null || s.bookSourceUrl.trim().isEmpty()) {
            throw new LegadoException("不是合法书源：缺少 bookSourceUrl");
        }
        return s;
    }

    /** 导入 JSON 文本；同 bookSourceUrl 覆盖。返回源名。 */
    public static synchronized String importText(String json) {
        if (json == null || json.trim().isEmpty()) throw new LegadoException("内容为空");
        LegadoSource s = parse(json.trim());
        List<Entry> list = all();
        boolean replaced = false;
        for (Entry e : list) {
            try {
                if (s.bookSourceUrl.equals(parse(e.json).bookSourceUrl)) {
                    e.json = json.trim();
                    replaced = true;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!replaced) {
            Entry e = new Entry();
            e.json = json.trim();
            e.enabled = true;
            e.addedAt = System.currentTimeMillis();
            list.add(e);
        }
        saveAll(list);
        return s.tag();
    }

    /** 导入文件（.json/.txt）。 */
    public static String importFile(String path) {
        File f = new File(path);
        if (!f.exists() || f.length() > 2 * 1024 * 1024) throw new LegadoException("文件不存在或过大（>2MB）");
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        } catch (Throwable t) {
            throw new LegadoException("文件读取失败: " + t.getMessage());
        }
        return importText(sb.toString());
    }

    /** 导入链接（拉取后按文本导入）。 */
    public static String importUrl(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new LegadoException("链接格式不对");
        }
        try {
            LegadoHttp.Result r = LegadoHttpAndroid.get().fetch(url, "GET", null, null);
            return importText(new String(r.body, StandardCharsets.UTF_8));
        } catch (LegadoException e) {
            throw e;
        } catch (Throwable t) {
            throw new LegadoException("链接拉取失败: " + t.getMessage());
        }
    }

    public static synchronized LegadoSource get(String bookSourceUrl) {
        for (Entry e : all()) {
            try {
                LegadoSource s = parse(e.json);
                if (s.bookSourceUrl.equals(bookSourceUrl)) {
                    e.parsed = s;
                    return s;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static synchronized boolean isEnabled(String bookSourceUrl) {
        for (Entry e : all()) {
            try {
                if (parse(e.json).bookSourceUrl.equals(bookSourceUrl)) return e.enabled;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static synchronized void setEnabled(String bookSourceUrl, boolean enabled) {
        List<Entry> list = all();
        for (Entry e : list) {
            try {
                if (parse(e.json).bookSourceUrl.equals(bookSourceUrl)) e.enabled = enabled;
            } catch (Throwable ignored) {
            }
        }
        saveAll(list);
    }

    public static synchronized void delete(String bookSourceUrl) {
        List<Entry> list = all();
        List<Entry> keep = new ArrayList<>();
        for (Entry e : list) {
            try {
                if (!parse(e.json).bookSourceUrl.equals(bookSourceUrl)) keep.add(e);
            } catch (Throwable ignored) {
            }
        }
        saveAll(keep);
    }

    /** 可跑的源（已启用＋type 0/2）。 */
    public static List<LegadoSource> runnable() {
        List<LegadoSource> out = new ArrayList<>();
        for (Entry e : all()) {
            if (!e.enabled) continue;
            try {
                LegadoSource s = parse(e.json);
                if (LegadoSource.runnable(s.bookSourceType)) out.add(s);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }
}
