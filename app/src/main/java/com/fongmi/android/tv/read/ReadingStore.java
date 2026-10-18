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
 * 书架/阅读进度存储：照 HkFavoriteStore 模式（SharedPreferences + Gson）。
 * 查找键：kind + sourceName + bookTitle。
 * <p>
 * R8 注意：读回必须用 TypeToken<List<Record>>（AGENTS.md 第一道关卡），
 * 裸 List.class 读回的是 LinkedTreeMap，会在取值时崩。
 */
public class ReadingStore {

    private static final String PREFS = "reading_shelf";
    private static final String KEY_RECORDS = "records";

    /** 书架记录。 */
    public static class Record {
        /** "COMIC" / "NOVEL"。 */
        public String kind = "";
        public String sourceName = "";
        public String bookTitle = "";
        public String coverUrl = "";
        public String chapterName = "";
        public int chapterIndex = 0;
        /** 当前章地址（继续读时重建会话用）。 */
        public String chapterUrl = "";
        /** py 源 key（继续读后按章重取用）。 */
        public String siteKey = "";
        /** 小说正文快照（py 小说继续读时用，海阔小说按章重抓）。 */
        public String content = "";
        /** 漫画：看到第几张图（下标）；小说：进度万分比（0~10000，换字号也准）。 */
        public int position = 0;
        public long updatedAt = 0L;

        public String key() {
            return kind + "|" + sourceName + "|" + bookTitle;
        }
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static List<Record> readAll() {
        try {
            String json = prefs().getString(KEY_RECORDS, "");
            if (TextUtils.isEmpty(json)) return new ArrayList<>();
            // R8 关卡：必须 TypeToken，否则读回 LinkedTreeMap
            List<Record> list = new Gson().fromJson(json, new TypeToken<List<Record>>() {
            }.getType());
            return list == null ? new ArrayList<>() : list;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    private static void writeAll(List<Record> list) {
        try {
            prefs().edit().putString(KEY_RECORDS, new Gson().toJson(list)).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 书架全部（按最近阅读倒序）。 */
    public static List<Record> list() {
        List<Record> list = readAll();
        list.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return list;
    }

    public static Record find(String kind, String sourceName, String bookTitle) {
        if (kind == null || sourceName == null || bookTitle == null) return null;
        String k = kind + "|" + sourceName + "|" + bookTitle;
        for (Record r : readAll()) {
            if (k.equals(r.key())) return r;
        }
        return null;
    }

    public static boolean contains(String kind, String sourceName, String bookTitle) {
        return find(kind, sourceName, bookTitle) != null;
    }

    /** 新增或更新进度（同键覆盖）。 */
    public static void save(Record rec) {
        if (rec == null || TextUtils.isEmpty(rec.bookTitle)) return;
        rec.updatedAt = System.currentTimeMillis();
        List<Record> list = readAll();
        boolean hit = false;
        for (int i = 0; i < list.size(); i++) {
            if (rec.key().equals(list.get(i).key())) {
                list.set(i, rec);
                hit = true;
                break;
            }
        }
        if (!hit) list.add(rec);
        writeAll(list);
    }

    public static void remove(String kind, String sourceName, String bookTitle) {
        if (kind == null || sourceName == null || bookTitle == null) return;
        String k = kind + "|" + sourceName + "|" + bookTitle;
        List<Record> list = readAll();
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (k.equals(list.get(i).key())) {
                list.remove(i);
                changed = true;
            }
        }
        if (changed) writeAll(list);
    }

    /** 最近在读的一本（「继续读」用）。 */
    public static Record latest() {
        List<Record> list = list();
        return list.isEmpty() ? null : list.get(0);
    }
}
