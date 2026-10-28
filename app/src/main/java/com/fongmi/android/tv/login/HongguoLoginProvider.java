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
 * 登录本体（2026-10-28 复活原生短信链）：全链原生——原生弹窗
 * （HongguoSmsLoginDialog，手机号＋验证码＋协议勾选）＋原生 passport
 * 请求链（HgPassportClient：HgDevice 注册档案＋HgSign 签名）。
 * 当年判死的 403 已由探针定根为两道门（随机假设备号未注册＋无签名头），
 * 现行栈两道全过（证据账 ~/workspace/hongguo/login-route-dig-20261028.md）。
 * 网页登录路（hongguoduanju.com、番茄 fanqienovel.com）已按他亲令整撤：
 * 本提供方不再持任何网页登录地址/抓取域，不留 WebView 登录或兜底路径。
 * 登录产出的会话串（Set-Cookie 优先、data.session_key 组 sessionid 兜底）
 * 由弹窗经 LoginManager 落本托管槽位，HgApiClient 拼头处以登录 Cookie
 * 覆盖游客 Cookie（接缝在 HgLoginAuthProvider，本类零感知）。
 * <p>
 * 未验项（如实）：登录成功回包→会话落盘、原生会话在 bookapi 账号面
 * 认不认账，只能等他本人用真号在弹窗里走完一次首登定（登录后第一验
 * HgLoginSessionCheck 会把 account/info 与书架两口的回包形状记进日志）。
 * <p>
 * 有效性判定口径：会话 Cookie 的身份键是 sessionid / sessionid_ss
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
