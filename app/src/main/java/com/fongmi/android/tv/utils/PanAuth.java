package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.fongmi.android.tv.App;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Locale;

public class PanAuth {

    private static final String PREFS = "xingchen";
    private static final String KEY_115_COOKIE = "pan115_cookie";
    private static final String KEY_115_VIP = "pan115_vip";
    private static final String KEY_QUARK_COOKIE = "panquark_cookie";
    private static final String KEY_QUARK_VIP = "panquark_vip";
    private static final String KEY_PIKPAK_VIP = "panpikpak_vip";

    private static SharedPreferences prefs() {
        Context ctx = App.get();
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * App 内登录流程的 115 cookie，只看 SharedPreferences。
     * 用于 UI 登录态判定。
     */
    public static String get115Cookie() {
        try {
            String c = prefs().getString(KEY_115_COOKIE, "");
            return c == null ? "" : c;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 115 分享解析用的 cookie，App 自身没有时回退读取用户 PY 脚本的 115_cookie.json。
     */
    public static String get115CookieForParser() {
        String c = get115Cookie();
        if (!c.isEmpty()) return c;
        try {
            Context ctx = App.get();
            if (ctx != null) {
                File f = new File(ctx.getFilesDir(), "plugins/py/115_cookie.json");
                if (f.exists()) {
                    String txt = readAll(f);
                    String cookie = new JSONObject(txt).optString("cookie", "");
                    if (!cookie.isEmpty()) return cookie;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * 115 登录态 cookie 必须包含 UID / CID / SEID。
     */
    public static boolean is115CookieValid(String cookie) {
        if (cookie == null || cookie.isEmpty()) return false;
        boolean uid = false, cid = false, seid = false;
        for (String pair : cookie.split(";")) {
            String name = pair.trim();
            int eq = name.indexOf('=');
            if (eq > 0) name = name.substring(0, eq).trim();
            if ("UID".equals(name)) uid = true;
            else if ("CID".equals(name)) cid = true;
            else if ("SEID".equals(name)) seid = true;
            if (uid && cid && seid) return true;
        }
        return false;
    }

    public static void put115Cookie(String cookie) {
        prefs().edit().putString(KEY_115_COOKIE, cookie == null ? "" : cookie).apply();
    }

    public static void clear115() {
        prefs().edit().remove(KEY_115_COOKIE).remove(KEY_115_VIP).apply();
    }

    public static boolean is115LoggedIn() {
        return isCookieValid(get115Cookie());
    }

    /**
     * 115 登录态格式校验：必须包含 UID/CID/SEID 三个关键字段，
     * 与 115 网盘爬虫的 cookie_ok 判定一致。光有 cookie 字符串不算登录。
     */
    private static boolean isCookieValid(String cookie) {
        if (cookie == null || cookie.isEmpty()) return false;
        String upper = cookie.toUpperCase(Locale.US);
        return upper.contains("UID=") && upper.contains("CID=") && upper.contains("SEID=");
    }

    public static String get115Vip() {
        return prefs().getString(KEY_115_VIP, "");
    }

    public static void put115Vip(String vip) {
        prefs().edit().putString(KEY_115_VIP, vip == null ? "" : vip).apply();
    }

    public static String getQuarkCookie() {
        try {
            String c = prefs().getString(KEY_QUARK_COOKIE, "");
            return c == null ? "" : c;
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static void putQuarkCookie(String cookie) {
        prefs().edit().putString(KEY_QUARK_COOKIE, cookie == null ? "" : cookie).apply();
    }

    public static void clearQuark() {
        prefs().edit().remove(KEY_QUARK_COOKIE).remove(KEY_QUARK_VIP).apply();
    }

    public static boolean isQuarkLoggedIn() {
        return !getQuarkCookie().isEmpty();
    }

    public static String getQuarkVip() {
        return prefs().getString(KEY_QUARK_VIP, "");
    }

    public static void putQuarkVip(String vip) {
        prefs().edit().putString(KEY_QUARK_VIP, vip == null ? "" : vip).apply();
    }

    public static String loginSummary() {
        boolean l115 = is115LoggedIn();
        boolean lQuark = isQuarkLoggedIn();
        boolean lPikPak = isPikPakLoggedIn();
        if (!l115 && !lQuark && !lPikPak) return "未登录";
        StringBuilder sb = new StringBuilder();
        if (l115) sb.append("115");
        if (lQuark) {
            if (sb.length() > 0) sb.append("、");
            sb.append("夸克");
        }
        if (lPikPak) {
            if (sb.length() > 0) sb.append("、");
            sb.append("PikPak");
        }
        sb.append("已登录");
        return sb.toString();
    }

    // ---------- PikPak（会话由 DrivePikPak 经 CloudAccountStore 加密存储） ----------

    /**
     * PikPak 登录态缓存：原来每次 isPikPakLoggedIn() 都 new DrivePikPak，
     * 构造器做 OkHttpClient + 7 次加密存储读写，在主线程卡 UI。
     * 登录态只在登录/退出时变化，缓存后主线程只读内存。
     */
    private static volatile Boolean sPikPakLoggedIn;
    private static volatile String sPikPakUserName;

    public static boolean isPikPakLoggedIn() {
        Boolean cached = sPikPakLoggedIn;
        if (cached != null) return cached;
        boolean login = false;
        String name = "";
        try {
            com.fongmi.android.tv.cloud.drivepikpak.DrivePikPak d =
                    new com.fongmi.android.tv.cloud.drivepikpak.DrivePikPak(App.get());
            login = d.isLogin();
            if (login) name = d.getUserName();
        } catch (Throwable ignored) {
        }
        sPikPakLoggedIn = login;
        sPikPakUserName = name;
        return login;
    }

    public static String getPikPakUserName() {
        if (!isPikPakLoggedIn()) return "";
        String n = sPikPakUserName;
        return n == null ? "" : n;
    }

    /** 登录成功 / 退出登录后调用，刷新缓存 */
    public static void invalidatePikPakCache() {
        sPikPakLoggedIn = null;
        sPikPakUserName = null;
    }

    public static void clearPikPak() {
        try {
            new com.fongmi.android.tv.cloud.drivepikpak.DrivePikPak(App.get()).logout();
        } catch (Throwable ignored) {
        }
        prefs().edit().remove(KEY_PIKPAK_VIP).apply();
        invalidatePikPakCache();
    }

    public static String getPikPakVip() {
        return prefs().getString(KEY_PIKPAK_VIP, "");
    }

    public static void putPikPakVip(String vip) {
        prefs().edit().putString(KEY_PIKPAK_VIP, vip == null ? "" : vip).apply();
    }

    private static String readAll(File f) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }
}
