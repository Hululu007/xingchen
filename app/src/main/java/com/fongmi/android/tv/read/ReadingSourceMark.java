package com.fongmi.android.tv.read;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

/**
 * 源级「书源」标记存储（不漏认第一层·钉死层）。
 * <p>
 * 用户在详情页把一个源标记为书源后，该源的详情整页改道阅读流，
 * 不再过内容判别猜形状。按 siteKey 持久化，只对被标记的源生效。
 * <p>
 * Emby 源不许被标记（标记入口不露）；push_agent（txt 混合链）不许被标记。
 * <p>
 * R8 关卡：读回必须用 TypeToken&lt;List&lt;Record&gt;&gt;（AGENTS.md 第一道关卡），
 * 裸 List.class 读回的是 LinkedTreeMap，会在取值时崩。
 */
public class ReadingSourceMark {

    private static final String PREFS = "reading_source_mark";
    private static final String KEY_MARKS = "marks";

    /** 标记记录。 */
    public static class Record {
        /** py/接口源 key。 */
        public String siteKey = "";
        /** 源显示名（详情页展示用）。 */
        public String sourceName = "";
        public long markedAt = 0L;
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static List<Record> readAll() {
        try {
            String json = prefs().getString(KEY_MARKS, "");
            if (TextUtils.isEmpty(json)) return new ArrayList<>();
            List<Record> list = new Gson().fromJson(json, new TypeToken<List<Record>>() {
            }.getType());
            return list == null ? new ArrayList<>() : list;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    private static void writeAll(List<Record> list) {
        try {
            prefs().edit().putString(KEY_MARKS, new Gson().toJson(list)).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 该源是否被标记为书源。 */
    public static boolean isMarked(String siteKey) {
        if (TextUtils.isEmpty(siteKey)) return false;
        for (Record r : readAll()) {
            if (siteKey.equals(r.siteKey)) return true;
        }
        return false;
    }

    /** 标记为书源（同 key 覆盖更新）。 */
    public static void mark(String siteKey, String sourceName) {
        if (TextUtils.isEmpty(siteKey)) return;
        List<Record> list = readAll();
        boolean hit = false;
        for (Record r : list) {
            if (siteKey.equals(r.siteKey)) {
                if (!TextUtils.isEmpty(sourceName)) r.sourceName = sourceName;
                r.markedAt = System.currentTimeMillis();
                hit = true;
                break;
            }
        }
        if (!hit) {
            Record r = new Record();
            r.siteKey = siteKey;
            r.sourceName = sourceName == null ? "" : sourceName;
            r.markedAt = System.currentTimeMillis();
            list.add(r);
        }
        writeAll(list);
    }

    /** 取消标记，立即恢复原行为。 */
    public static void unmark(String siteKey) {
        if (TextUtils.isEmpty(siteKey)) return;
        List<Record> list = readAll();
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (siteKey.equals(list.get(i).siteKey)) {
                list.remove(i);
                changed = true;
            }
        }
        if (changed) writeAll(list);
    }

    /** 全部标记（排查用）。 */
    public static List<Record> list() {
        List<Record> list = readAll();
        list.sort((a, b) -> Long.compare(b.markedAt, a.markedAt));
        return list;
    }
}
