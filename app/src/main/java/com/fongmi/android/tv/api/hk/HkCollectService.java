package com.fongmi.android.tv.api.hk;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 小程序收藏服务（移植自 8.83 ViewCollection 的 LitePal 存取）。
 * 8.83 原文：FilmListActivity.collectOrUpdate/isHasCollect 经 LitePal.where("CUrl = ? and MTitle = ?", ...)
 * 查询 ViewCollection 表；星辰用 SharedPreferences 存 JSON，语义一致（CUrl + MTitle 唯一键）。
 */
public class HkCollectService {

    private static final String PREFS = "hk_collection";
    private static final String KEY_RECORDS = "view_collections";
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<HkViewCollection>>() {}.getType();

    private static Context appContext;

    public static void init(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    public static List<HkViewCollection> getAll() {
        try {
            if (appContext == null) return new ArrayList<>();
            SharedPreferences sp = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String json = sp.getString(KEY_RECORDS, "");
            if (TextUtils.isEmpty(json)) return new ArrayList<>();
            List<HkViewCollection> list = GSON.fromJson(json, LIST_TYPE);
            return list != null ? list : new ArrayList<>();
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    private static void saveAll(List<HkViewCollection> records) {
        try {
            if (appContext == null) return;
            String json = GSON.toJson(records != null ? records : new ArrayList<>());
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_RECORDS, json).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 8.83 FilmListActivity.isHasCollect(1462) 对齐：
     * LitePal.where("CUrl = ? and MTitle = ?", url, title).limit(1).find(ViewCollection.class)
     * 非空即已收藏。
     */
    public static boolean isHasCollect(String cUrl, String mTitle) {
        if (TextUtils.isEmpty(cUrl)) return false;
        try {
            for (HkViewCollection r : getAll()) {
                if (r != null && cUrl.equals(r.getCUrl())
                        && (mTitle == null || mTitle.equals(r.getMTitle()))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 按 CUrl + MTitle 查找收藏记录（8.83 collectOrUpdate:1139 的查询语义）。 */
    public static HkViewCollection find(String cUrl, String mTitle) {
        try {
            for (HkViewCollection r : getAll()) {
                if (r != null && TextUtils.equals(cUrl, r.getCUrl())
                        && TextUtils.equals(mTitle, r.getMTitle())) {
                    return r;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 8.83 FilmListActivity.collectOrUpdate(787) 对齐：已存在则更新（标题/图片/分组），
     * 不存在则新增。time 设为当前时间。
     */
    public static void collectOrUpdate(HkViewCollection record) {
        if (record == null) return;
        try {
            List<HkViewCollection> all = getAll();
            boolean updated = false;
            for (int i = 0; i < all.size(); i++) {
                HkViewCollection r = all.get(i);
                if (r != null && TextUtils.equals(record.getCUrl(), r.getCUrl())
                        && TextUtils.equals(record.getMTitle(), r.getMTitle())) {
                    record.setTime(new Date());
                    all.set(i, record);
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                if (record.getTime() == null) record.setTime(new Date());
                all.add(0, record);
            }
            saveAll(all);
        } catch (Throwable ignored) {
        }
    }

    /** 取消收藏（8.83 cancelCollectOrUpdate 语义：按 CUrl + MTitle 删除）。 */
    public static void cancelCollect(String cUrl, String mTitle) {
        try {
            List<HkViewCollection> all = getAll();
            boolean changed = false;
            for (int i = all.size() - 1; i >= 0; i--) {
                HkViewCollection r = all.get(i);
                if (r != null && TextUtils.equals(cUrl, r.getCUrl())
                        && TextUtils.equals(mTitle, r.getMTitle())) {
                    all.remove(i);
                    changed = true;
                }
            }
            if (changed) saveAll(all);
        } catch (Throwable ignored) {
        }
    }
}
