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
 * 登录本体定版状态（如实）：官方 App 用字节 passport 原生 SDK 登录，样本
 * （hongguo-app dex 字符串）只抠到路径族——/passport/mobile/send_code/v1/、
 * /passport/mobile/validate_code/v1/、/passport/mobile/sms_login/、
 * /passport/auth/login/——请求体与签名（设备注册＋X-Argus 系签名＋风控参数）
 * 在已反编译的 jadx9/19/20 与 classes2/11 定向产物里没有定版，短信登录本体
 * 待补（见四阶段回报）。本类先把托管链落齐：网页登录弹窗入口、Cookie 落盘、
 * 有效性格式判定、HgAuthProvider 回填，登录成功后的带态请求即刻生效。
 * <p>
 * 有效性判定口径：passport 会话 Cookie 的身份键是 sessionid / sessionid_ss
 * （字节 passport 通行命名），任一非空才算登录成立；只做格式判定，不预判
 * 服务端有效期，真过期后账号维度接口回错、走失败回滚路径，用户重新登录即刷新。
 */
public class HongguoLoginProvider implements LoginProvider {

    /** 提供方 id。 */
    public static final String ID = "hongguo";

    /**
     * 网页登录页（壳子统一弹窗 PanLoginDialog 加载）：红果接口宿主根。
     * 注意：官方没有对外的网页登录页样本，此地址未经样本定版——弹窗只是
     * 托管链的入口，本体（短信登录）待补，不要据此认为网页登录已通。
     */
    public static final String LOGIN_URL = "https://reading.snssdk.com/";

    /** WebView 抓 Cookie 的域名口径（红果接口与 passport 会话同宿主）。 */
    public static final String COOKIE_DOMAIN = "reading.snssdk.com";

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

    /** passport 会话 Cookie 格式判定：sessionid / sessionid_ss 任一非空。 */
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
