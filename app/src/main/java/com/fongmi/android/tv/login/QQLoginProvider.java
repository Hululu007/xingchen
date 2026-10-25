package com.fongmi.android.tv.login;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.cloud.CloudAccountStore;

/**
 * QQ音乐网页登录（壳子统一登录托管的 QQ 提供方，id="qq"）。
 * <p>
 * 登录目标是 QQ 音乐网页端（y.qq.com），登录 Cookie 经壳子统一弹窗
 * （PanLoginDialog，WebView 登录）拿到后落盘托管，供落雪 tx 歌曲取链兜底
 * （QqVkeyFetcher）经 {@link LoginManager#getCookie(String)} 读取、带他自己的
 * 登录态调官方 CgiGetVkey 取链。存储与快手/谷歌同轨：CloudAccountStore
 * 加密存储，本类不走 PanAuth（网盘专用门面）。
 * <p>
 * 为什么需要登录态：QQ 官方匿名 vkey 已彻底关门（免费歌也不发链，2026-10-25
 * 实测），落雪生态的公开 tx 解析后端全灭；带他自己的 QQ 登录态走官方取链是
 * 唯一剩下的路（2026-10-25 他亲口点定「QQ走登录取链，做」）。
 * Cookie 域口径：登录身份 Cookie（uin/skey 系）落在 .qq.com 域、音乐钥匙
 * （qm_keyst/qqmusic_key）落在 .y.qq.com 域，抓取按 y.qq.com 读——CookieManager
 * 对该 host 返回两域命中的全部 Cookie（歌单登录导入读同一口已在产验证）。
 * 过期口径与快手/谷歌一致：提供方只做格式判定，不预判服务端有效期；Cookie
 * 真过期后取链回空、走既有失败路径，用户重新登录即刷新。
 */
public class QQLoginProvider implements LoginProvider {

    /** 提供方 id。 */
    public static final String ID = "qq";

    /** 登录页：QQ 音乐网页端，在此页内完成 QQ 网页登录（ptlogin）。 */
    public static final String LOGIN_URL = "https://y.qq.com/";

    /** WebView 抓 Cookie 的域名口径（须覆盖 .qq.com 身份 Cookie 与 .y.qq.com 音乐钥匙）。 */
    public static final String COOKIE_DOMAIN = "y.qq.com";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "QQ音乐";
    }

    @Override
    public boolean isLoggedIn() {
        return isCookieValid(getCookie());
    }

    @Override
    public String getCookie() {
        try {
            String cookie = CloudAccountStore.getCookie(App.get(), ID);
            return cookie == null ? "" : cookie;
        } catch (Throwable ignored) {
            return "";
        }
    }

    @Override
    public void clear() {
        try {
            CloudAccountStore.clear(App.get(), ID);
        } catch (Throwable ignored) {
        }
    }

    /** 登录成功后落盘托管（登录弹窗的 onLogin 回调调此口，与实例 clear/get 同一轨）。 */
    public static void putCookie(String cookie) {
        try {
            CloudAccountStore.saveCookie(App.get(), ID, cookie == null ? "" : cookie);
        } catch (Throwable ignored) {
        }
    }

    /**
     * QQ 网页登录 Cookie 有效性判定，与取链消费逐项对齐：CgiGetVkey 带态请求
     * 需要账号标识（uin／qqmusic_uin／wxuin 任一非空）＋音乐钥匙
     * （qm_keyst／qqmusic_key 任一非空，同一把钥匙的两个下发名）。
     * 只有 ptlogin 身份 Cookie（uin＋skey）没有音乐钥匙时取链照样被拒，
     * 不许算登录成立——钥匙由 y.qq.com 页面在登录完成后下发。
     */
    public static boolean isCookieValid(String cookie) {
        if (TextUtils.isEmpty(cookie)) return false;
        boolean uin = false;
        boolean key = false;
        for (String pair : cookie.split(";")) {
            String item = pair.trim();
            int eq = item.indexOf('=');
            if (eq <= 0) continue;
            String name = item.substring(0, eq).trim();
            String value = item.substring(eq + 1).trim();
            if (value.isEmpty()) continue;
            if ("uin".equals(name) || "qqmusic_uin".equals(name) || "wxuin".equals(name)) uin = true;
            else if ("qm_keyst".equals(name) || "qqmusic_key".equals(name)) key = true;
            if (uin && key) return true;
        }
        return false;
    }
}
