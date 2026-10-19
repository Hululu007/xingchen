package com.fongmi.android.tv.ui.lab;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 实验室首页环境更新自动检测（1.3.57 起）：
 * 进实验室时后台比一次目录版本与本机已装版本，有新版的首页卡片标「可更新」，
 * 点进环境页仍走原有的「更新」按钮安装。自动查只是提醒，漏了/查不到手动点更新就行。
 * <p>
 * 节流：一天最多自动查一次，上次时间与结果存在偏好里，当天再进直接用上次结果标。
 * 失败/查不到静默，不弹任何提示，不打断首页打开。安装管线一律不碰。
 */
public final class LabEnvUpdateCheck {

    private LabEnvUpdateCheck() {
    }

    /** 自动查到有新版的环境 id 集合回调（工作线程上回调，调用方自转 UI 线程）。 */
    public interface Listener {
        void onDone(Set<String> updatables);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("lab_settings", Context.MODE_PRIVATE);
    }

    /** 距上次自动查过去的毫秒（<0 表示没查过）。纯函数，方便单测。 */
    public static long elapsedSince(long nowMs, long lastMs) {
        return lastMs <= 0 ? -1 : nowMs - lastMs;
    }

    /** 是否需要重新查：没查过，或已超 24 小时。纯函数，方便单测。 */
    public static boolean needsCheck(long nowMs, long lastMs) {
        long e = elapsedSince(nowMs, lastMs);
        return e < 0 || e >= 24L * 60 * 60 * 1000;
    }

    /** 结果串编码/解码（逗号连接的 env id）。纯函数，方便单测。 */
    public static String encode(Set<String> ids) {
        if (ids == null || ids.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (id == null || id.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        return sb.toString();
    }

    public static Set<String> decode(String s) {
        if (s == null || s.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String p : s.split(",")) {
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    /** 上次自动查的结果（当天直接用它标卡片，不重复查）。 */
    public static Set<String> known(Context c) {
        return decode(prefs(c).getString("updatecheck_result", ""));
    }

    /**
     * 进实验室首页时调：一天内查过就直接回上次结果；否则后台线程挨个比版本，
     * 查完存结果（时间＋结果）并回调。任何异常都静默吞掉，不打扰。
     */
    public static void maybeCheck(Context ctx, Listener listener) {
        final Context app = ctx.getApplicationContext();
        final long now = System.currentTimeMillis();
        final long last = prefs(app).getLong("updatecheck_last", 0);
        if (!needsCheck(now, last)) {
            if (listener != null) listener.onDone(known(app));
            return;
        }
        new Thread(() -> {
            Set<String> out = new HashSet<>();
            try {
                for (String env : LabRegistry.ENVS) {
                    try {
                        if (LabRuntimeManager.hasUpdate(app, env)) out.add(env);
                    } catch (Throwable ignored) {
                    }
                }
                prefs(app).edit()
                        .putLong("updatecheck_last", now)
                        .putString("updatecheck_result", encode(out))
                        .apply();
            } catch (Throwable ignored) {
                // 静默：用旧结果，不打断
                out = known(app);
            }
            if (listener != null) listener.onDone(out);
        }).start();
    }
}
