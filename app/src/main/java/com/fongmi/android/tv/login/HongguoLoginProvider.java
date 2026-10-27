package com.fongmi.android.tv.login;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.cloud.CloudAccountStore;

/**
 * 红果登录（壳子统一登录托管的红果提供方，id="hongguo"）。
 * <p>
 * 存储与 QQ/快手/谷歌同轨：CloudAccountStore 加密存储，本类只做托管与格式判定，
 * 凭证由 {@link com.fongmi.android.tv.hongguo.api.HgLoginAuthProvider} 回填给
 * 红果接口客户端（HgApiClient 的 HgAuthProvider 注入点）。
 * <p>
 * 登录本体（2026-10-28 换网页登录路）：原生短信链已判死——passport 请求
 * 缺 TTNet 原生签名头（libmetasec_ml），咱们直发被 403（证据账
 * ~/workspace/hongguo/passport-login-dig.md、app-study §12.2/U11），
 * 原生短信弹窗与 passport 客户端已整删，不留两套。
 * 改走壳子统一登录弹窗（PanLoginDialog）在番茄小说网页版完成登录：
 * 番茄与红果同属 com.dragon.read、同一 passport 账号系（同手机号同号），
 * 是唯一站得住的官方网页入口（挖掘账
 * ~/workspace/hongguo/web-login-dig-20261028.md）；弹窗按 COOKIE_DOMAIN
 * 整域抓取 Cookie 串落本托管槽位，HgApiClient 拼头处以登录 Cookie
 * 覆盖游客 Cookie（接缝在 HgLoginAuthProvider，本类零感知）。
 * <p>
 * 未验项（如实）：网页登录实际种哪些 Cookie、网页会话带到已签名
 * fqnovel/bookapi 链后账号维度接口认不认账，只能等他本人在弹窗里用
 * 手机号＋短信验证码真登一次定（账内 U-web1/U-web2）；入口先用站点
 * 首页，页内点登录（U-web4）。
 * <p>
 * 有效性判定口径：会话 Cookie 的身份键是 sessionid / sessionid_ss
 * （字节 passport 通行命名），任一非空才算登录成立；只做格式判定，不预判
 * 服务端有效期，真过期后账号维度接口回错、走失败回滚路径，用户重新登录即刷新。
 */
public class HongguoLoginProvider implements LoginProvider {

    /** 提供方 id。 */
    public static final String ID = "hongguo";

    /** 网页登录入口：番茄小说网页版首页（与红果同账号系，页内点登录）。 */
    public static final String LOGIN_URL = "https://fanqienovel.com/";

    /** 登录 Cookie 抓取域（PanLoginDialog 按此域读 CookieManager 全量串）。 */
    public static final String COOKIE_DOMAIN = "fanqienovel.com";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "红果";
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

    /** 会话 Cookie 格式判定：sessionid / sessionid_ss 任一非空。 */
    public static boolean isCookieValid(String cookie) {
        if (TextUtils.isEmpty(cookie)) return false;
        for (String pair : cookie.split(";")) {
            String item = pair.trim();
            int eq = item.indexOf('=');
            if (eq <= 0) continue;
            String name = item.substring(0, eq).trim();
            String value = item.substring(eq + 1).trim();
            if (value.isEmpty()) continue;
            if ("sessionid".equals(name) || "sessionid_ss".equals(name)) return true;
        }
        return false;
    }
}
