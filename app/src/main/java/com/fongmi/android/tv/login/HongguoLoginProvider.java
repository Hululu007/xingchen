package com.fongmi.android.tv.login;

import android.content.Context;
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
 * 登录本体（2026-10-28 换官方形态整页）：HongguoLoginPage——抖音
 * 一键登录（web 授权页＋回跳拦截取 code＋原生 auth/login 兑换）与
 * 手机号验证码（原生 passport 短信链）两路，协议勾选共享；兑换与
 * 短信链同走 HgPassportClient（HgDevice 注册档案＋HgSign 签名）。
 * 当年判死的 403 已由探针定根为两道门（随机假设备号未注册＋无签名头），
 * 现行栈两道全过（证据账 ~/workspace/hongguo/login-route-dig-20261028.md）。
 * 网页登录路（hongguoduanju.com、番茄 fanqienovel.com）已按他亲令整撤：
 * 本提供方不再持任何网页登录地址/抓取域，不留 WebView 登录或兜底路径。
 * 网页期经本槽位落盘的会话串是残留（撤版未清盘、至今仍随数据请求外发），
 * 由 {@link #purgeLegacyWebCookieOnce()} 在首读前一次性清掉。
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

    /** 网页登录期残留一次性清理标记（落在 CloudAccountStore 的通用值槽）。 */
    private static final String KEY_WEB_COOKIE_PURGED = "hg_web_cookie_purged_v1";

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
        purgeLegacyWebCookieOnce();
        try {
            String cookie = CloudAccountStore.getCookie(App.get(), ID);
            return cookie == null ? "" : cookie;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 一次性清网页登录期残留（2026-10-29 深夜立项，证据与口径见
     * login-close-loop-20261029.md 第四-5）：2026-10-27/28 的网页登录实验
     * （番茄 fanqienovel.com 一版、hongguoduanju.com 一版）登录成功时经
     * {@link #putCookie} 把网页会话串落进本槽位；其后撤版只改代码、从未清盘，
     * 该串至今仍被 HgLoginAuthProvider 现取、由 HgApiClient 拼头附到红果
     * 全部数据请求上覆盖游客 Cookie（他一手时间线：番茄登录一次之后数据
     * 开始取不出；撤版当时的实测记账也是「番茄会话在场、登完数据全空」）。
     * 原生 passport 会话在任何已发版本里从未登录成功过——本槽位在原生
     * 首登成功之前的内容只可能是网页期残留。故首读前一次性清本提供方的
     * cookie/user/token 三键并落标记，此后原生登录写入的新会话不受影响。
     * 误伤面逐项核过：CloudAccountStore.clear 按 driveId 只删本提供方三键
     * （网盘等其它提供方条目不在内）；历史/收藏/追更/点赞在 HgStore、
     * 设备档案在 HgDevice，各是独立存储面，本清理一概不碰。
     */
    public static void purgeLegacyWebCookieOnce() {
        try {
            Context ctx = App.get();
            if (ctx == null) return;
            if (!TextUtils.isEmpty(CloudAccountStore.getValue(ctx, ID, KEY_WEB_COOKIE_PURGED))) return;
            CloudAccountStore.clear(ctx, ID);
            CloudAccountStore.saveValue(ctx, ID, KEY_WEB_COOKIE_PURGED, "1");
        } catch (Throwable ignored) {
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
