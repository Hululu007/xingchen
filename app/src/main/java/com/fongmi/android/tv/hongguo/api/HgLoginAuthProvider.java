package com.fongmi.android.tv.hongguo.api;

import com.fongmi.android.tv.login.LoginManager;

import java.util.Map;

/**
 * HgAuthProvider 的真实现：凭证不自己存，每次请求前从壳子统一登录托管
 * （LoginManager 的红果提供方，id="hongguo"）现取 Cookie 回填。
 * 托管侧退出登录后这里自然取空，账号维度请求随即回落未登录口径。
 */
public final class HgLoginAuthProvider implements HgAuthProvider {

    @Override
    public Map<String, String> headers() {
        return null;
    }

    @Override
    public String cookie() {
        try {
            String cookie = LoginManager.getCookie("hongguo");
            return cookie == null || cookie.isEmpty() ? null : cookie;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 是否已登录（托管 Cookie 格式有效）；互动层据此决定走真接口还是先拉登录。 */
    public static boolean isLoggedIn() {
        try {
            return LoginManager.isLoggedIn("hongguo");
        } catch (Throwable ignored) {
            return false;
        }
    }
}
