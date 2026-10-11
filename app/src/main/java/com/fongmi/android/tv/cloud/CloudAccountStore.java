package com.fongmi.android.tv.cloud;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * 网盘账号加密存储
 */
public class CloudAccountStore {

    private static final String PREF = "cloud_account";

    /**
     * EncryptedSharedPreferences 单例：原来每次读写都 new MasterKey（走 Android Keystore）
     * 再 create 一次 EncryptedSharedPreferences，DrivePikPak 构造一次就触发约 7 次，
     * 在主线程直接卡死 UI。缓存后只做一次。
     */
    private static volatile SharedPreferences sPrefs;

    private static SharedPreferences prefs(Context context) {
        SharedPreferences p = sPrefs;
        if (p == null) {
            synchronized (CloudAccountStore.class) {
                p = sPrefs;
                if (p == null) {
                    Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                    try {
                        MasterKey masterKey = new MasterKey.Builder(app)
                                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                                .build();
                        p = EncryptedSharedPreferences.create(
                                app, PREF, masterKey,
                                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
                    } catch (Exception e) {
                        p = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
                    }
                    sPrefs = p;
                }
            }
        }
        return p;
    }

    public static void saveCookie(Context context, String driveId, String cookie) {
        prefs(context).edit().putString("cookie_" + driveId, cookie == null ? "" : cookie).apply();
    }

    public static String getCookie(Context context, String driveId) {
        return prefs(context).getString("cookie_" + driveId, "");
    }

    public static void saveUserName(Context context, String driveId, String name) {
        prefs(context).edit().putString("user_" + driveId, name == null ? "" : name).apply();
    }

    public static String getUserName(Context context, String driveId) {
        return prefs(context).getString("user_" + driveId, "");
    }

    public static void clear(Context context, String driveId) {
        prefs(context).edit()
                .remove("cookie_" + driveId)
                .remove("user_" + driveId)
                .remove("token_" + driveId)
                .apply();
    }

    /** 通用键值存储（用于 access_token / refresh_token / device_id 等） */
    public static void saveValue(Context context, String driveId, String key, String value) {
        prefs(context).edit().putString(key + "_" + driveId, value == null ? "" : value).apply();
    }

    public static String getValue(Context context, String driveId, String key) {
        return prefs(context).getString(key + "_" + driveId, "");
    }

    public static void saveLong(Context context, String driveId, String key, long value) {
        prefs(context).edit().putLong(key + "_" + driveId, value).apply();
    }

    public static long getLong(Context context, String driveId, String key) {
        return prefs(context).getLong(key + "_" + driveId, 0);
    }
}
