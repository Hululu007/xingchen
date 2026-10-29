package com.fongmi.android.tv.api.hk;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 首页订阅服务（移植自 8.83 HomeRulesSubService）。
 * 8.83 原文：com/example/hikerview/ui/rules/service/HomeRulesSubService.smali（1624 行）
 * 8.83 用 BigTextDO.getHomeSub() 存 JSON；星辰用 SharedPreferences 存，语义一致。
 * 仅实现 getSubRecords()（供 getHomeSub/hasHomeSub JS API 用），完整订阅管理待后续。
 */
public class HkHomeSubService {

    private static final String PREFS = "hk_home_sub";
    private static final String KEY_SUB_RECORDS = "sub_records";
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = TypeToken.getParameterized(List.class, HkSubscribeRecord.class).getType();

    private static Context appContext;

    public static void init(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    /**
     * 8.83 原文（HomeRulesSubService.smali:1207）：
     *   String json = BigTextDO.getHomeSub();
     *   if (isEmpty(json)) return new ArrayList<>();
     *   return JSON.parseArray(json, SubscribeRecord.class);
     */
    public static List<HkSubscribeRecord> getSubRecords() {
        try {
            if (appContext == null) return new ArrayList<>();
            SharedPreferences sp = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String json = sp.getString(KEY_SUB_RECORDS, "");
            if (TextUtils.isEmpty(json)) return new ArrayList<>();
            List<HkSubscribeRecord> list = GSON.fromJson(json, LIST_TYPE);
            return list != null ? list : new ArrayList<>();
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /** 保存订阅记录（供设置页用） */
    public static void saveSubRecords(List<HkSubscribeRecord> records) {
        try {
            if (appContext == null) return;
            String json = GSON.toJson(records != null ? records : new ArrayList<>());
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_SUB_RECORDS, json).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 8.83 hasHomeSub 语义：url 为空返回 "false"；遍历记录比对 url，命中返回 "true" */
    public static String hasHomeSub(String url) {
        if (TextUtils.isEmpty(url)) return "false";
        try {
            for (HkSubscribeRecord r : getSubRecords()) {
                if (r != null && url.equals(r.getUrl())) return "true";
            }
        } catch (Throwable ignored) {
        }
        return "false";
    }
}
