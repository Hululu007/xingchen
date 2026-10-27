package com.fongmi.android.tv.legado;

import android.content.ComponentName;
import android.content.Intent;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.subtitle.ShieldBypass;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 人机验证闸门：判定「撞上验证」＋弹验证页＋等结果，全引擎只此一处。
 * <p>
 * 判定（保守，宁可漏判不许误伤正常报错/空结果）：
 * A. HTTP 403/429 且响应体命中盾页特征（ShieldBypass 特征表）；
 * B. HTTP 403/429 且跳转终点 URL 落在 Cloudflare 挑战路径上；
 * C. HTTP 2xx、HTML 响应且命中强特征（整页挑战页才会有的字样，
 *    不含裸 "captcha" —— 正常页提到验证码不算撞墙）。
 * <p>
 * 弹页：验证页是 mobile  flavor 的 LegadoVerifyActivity，引擎按类名拉起，
 * 不在（leanback 等无阅读页的环境）直接报 UNAVAILABLE，不硬闯。
 * 全局同时只允许一场验证：并行请求撞同一堵墙时，其余请求等同一场的结果，
 * 不连环弹。完成信号由验证页回报 onFinished：OK＝用户点了「完成验证」且
 * 已把 Cookie 存进 LegadoCookies；CANCELLED＝用户关页/返回放弃。
 */
public final class LegadoVerify {

    public static final int RESULT_OK = 1;
    public static final int RESULT_CANCELLED = 2;
    public static final int RESULT_UNAVAILABLE = 3;

    private static final String ACTIVITY_CLASS =
            "com.fongmi.android.tv.ui.activity.LegadoVerifyActivity";
    private static final long WAIT_TIMEOUT_MS = 300_000L;
    /** 判定只看响应体前缀，挑战页都是小页面，够用了。 */
    private static final int SCAN_BYTES = 128 * 1024;

    /** 2xx 也算撞墙的强特征（小写匹配）：只有整页挑战页才会出现。 */
    private static final String[] STRONG_MARKERS = {
            "just a moment",
            "challenge-platform",
            "/cdn-cgi/challenge",
            "cf-chl",
            "verify you are human",
            "checking your browser",
            "checking if the site connection is secure",
            "attention required",
            "人机验证",
            "滑动验证",
    };

    private LegadoVerify() {
    }

    // ================= 判定 =================

    public static boolean isBlockedPage(int status, String finalUrl, String contentType, byte[] body) {
        if (status == 403 || status == 429) {
            String url = finalUrl == null ? "" : finalUrl.toLowerCase(Locale.US);
            if (url.contains("/cdn-cgi/challenge") || url.contains("__cf_chl")
                    || url.contains("challenge-platform")) {
                return true;
            }
            return ShieldBypass.isShieldPage(decodePrefix(body));
        }
        if (status >= 200 && status < 300) {
            String ct = contentType == null ? "" : contentType.toLowerCase(Locale.US);
            if (!ct.isEmpty() && !ct.contains("html") && !ct.contains("text/plain")) return false;
            String text = decodePrefix(body);
            if (text.isEmpty()) return false;
            String lower = text.toLowerCase(Locale.US);
            for (String marker : STRONG_MARKERS) {
                if (lower.contains(marker)) return true;
            }
        }
        return false;
    }

    private static String decodePrefix(byte[] body) {
        if (body == null || body.length == 0) return "";
        int n = Math.min(body.length, SCAN_BYTES);
        try {
            return new String(body, 0, n, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }

    // ================= 弹页会话 =================

    private static class Session {
        /** 会话令牌：验证页回报时带回，防上一场迟报串进新会话。 */
        final long id = System.nanoTime();
        final CountDownLatch done = new CountDownLatch(1);
        volatile int result = RESULT_UNAVAILABLE;
    }

    private static final Object LOCK = new Object();
    private static Session current;

    /**
     * 阻塞当前（后台）线程，弹验证页等用户过完。同一时刻已有验证在进行时
     * 等那一场的结果，不再另弹。返回 RESULT_* 三档之一。
     */
    public static int awaitVerification(final String url, final String userAgent) {
        try {
            Class.forName(ACTIVITY_CLASS);
        } catch (Throwable t) {
            return RESULT_UNAVAILABLE;
        }
        final Session session;
        boolean launcher;
        synchronized (LOCK) {
            if (current == null) {
                current = new Session();
                launcher = true;
            } else {
                launcher = false;
            }
            session = current;
        }
        if (launcher) {
            App.post(() -> {
                try {
                    Intent intent = new Intent();
                    intent.setComponent(new ComponentName(App.get(), ACTIVITY_CLASS));
                    intent.putExtra("verify_url", url);
                    intent.putExtra("verify_ua", userAgent == null ? "" : userAgent);
                    intent.putExtra("verify_session", session.id);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    App.get().startActivity(intent);
                } catch (Throwable t) {
                    onFinished(session.id, RESULT_UNAVAILABLE);
                }
            });
        }
        try {
            if (!session.done.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                // 超时放弃本场：清掉当前会话，晚到的回报会被 onFinished 自然忽略
                synchronized (LOCK) {
                    if (current == session) current = null;
                }
                return RESULT_UNAVAILABLE;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RESULT_UNAVAILABLE;
        }
        int r = session.result;
        synchronized (LOCK) {
            if (current == session) current = null;
        }
        return r;
    }

    /** 验证页回报结果（完成验证/取消/打不开都走这里）；令牌对不上或无等待者时自然忽略。 */
    public static void onFinished(long sessionId, int result) {
        Session s;
        synchronized (LOCK) {
            s = current;
        }
        if (s != null && s.id == sessionId) {
            s.result = result;
            s.done.countDown();
        }
    }
}
