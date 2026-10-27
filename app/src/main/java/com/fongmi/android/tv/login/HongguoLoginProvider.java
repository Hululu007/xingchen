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
 * 登录本体（2026-10-27 换原生短信链）：官方 passport 短信登录已抠实落码
 * （证据账 ~/workspace/hongguo/passport-login-dig.md）——宿主
 * security.snssdk.com、发码 /passport/mobile/send_code/v1/、登录
 * /passport/mobile/sms_login/、字段 XOR 混淆口径，由
 * {@link com.fongmi.android.tv.hongguo.api.HgPassportClient} 发起，
 * 页面是 {@link HongguoSmsLoginDialog}（照谷歌版登录页定案构成），
 * 不再走网页框。未定版：passport 的 TTNet 原生签名头咱们没有，
 * 能否过校验待真机回包；登录 Cookie 以 data.session_key 组 sessionid
 * 兜底（服务端 Set-Cookie 优先）。
 * <p>
 * 有效性判定口径：passport 会话 Cookie 的身份键是 sessionid / sessionid_ss
 * （字节 passport 通行命名），任一非空才算登录成立；只做格式判定，不预判
 * 服务端有效期，真过期后账号维度接口回错、走失败回滚路径，用户重新登录即刷新。
 */
public class HongguoLoginProvider implements LoginProvider {

    /** 提供方 id。 */
    public static final String ID = "hongguo";

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
