package com.fongmi.android.tv.bean;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 插件健康检测结果：按插件文件路径持久化（退出重进直接看上次结果）。
 * 档位：UNKNOWN 未测 / OK 能用 / SLOW 慢 / DEAD 挂了 / TESTING 检测中（内存态，不持久）。
 */
public final class PluginHealth {

    public static final int UNKNOWN = 0;
    public static final int OK = 1;
    public static final int SLOW = 2;
    public static final int DEAD = 3;
    public static final int TESTING = 4;

    private static final String PREF = "plugin_health";

    private PluginHealth() {
    }

    private static SharedPreferences pref(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 一条检测结果。 */
    public static final class Result {
        public final int grade;
        public final long ms;
        public final long checkedAt;

        Result(int grade, long ms, long checkedAt) {
            this.grade = grade;
            this.ms = ms;
            this.checkedAt = checkedAt;
        }
    }

    public static Result get(Context c, String path) {
        String v = pref(c).getString(key(path), null);
        if (v == null) return new Result(UNKNOWN, 0, 0);
        try {
            String[] p = v.split(",");
            return new Result(Integer.parseInt(p[0]), Long.parseLong(p[1]), Long.parseLong(p[2]));
        } catch (Throwable ignored) {
            return new Result(UNKNOWN, 0, 0);
        }
    }

    public static void save(Context c, String path, int grade, long ms) {
        pref(c).edit().putString(key(path), grade + "," + ms + "," + System.currentTimeMillis()).apply();
    }

    /** 某页签（py/js）上次检测时间；0=没测过。 */
    public static long lastCheck(Context c, String type) {
        return pref(c).getLong("last_" + type, 0);
    }

    public static void setLastCheck(Context c, String type) {
        pref(c).edit().putLong("last_" + type, System.currentTimeMillis()).apply();
    }

    private static String key(String path) {
        return "r_" + path;
    }

    /** 档位文案。 */
    public static String label(int grade) {
        switch (grade) {
            case OK:
                return "能用";
            case SLOW:
                return "慢";
            case DEAD:
                return "挂了";
            case TESTING:
                return "检测中…";
            default:
                return "";
        }
    }

    /** 档位颜色（挂了标红）。 */
    public static int color(int grade) {
        switch (grade) {
            case OK:
                return 0xFF1F9D5C;
            case SLOW:
                return 0xFFE8A33D;
            case DEAD:
                return 0xFFE5484D;
            case TESTING:
                return 0xFF8A8F98;
            default:
                return 0xFF8A8F98;
        }
    }
}
