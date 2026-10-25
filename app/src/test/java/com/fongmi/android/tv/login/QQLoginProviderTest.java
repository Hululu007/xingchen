package com.fongmi.android.tv.login;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * QQ音乐登录提供方（壳子统一登录托管）：注册在位、Cookie 校验口径
 * （账号标识＋音乐钥匙两项齐）、经 LoginManager 的存入→读出→清除整链，
 * 以及与 115 提供方的隔离。存储后端是与 115 同轨的 CloudAccountStore
 * （已在产），离线运行 harness 以同签名内存桩顶替其 Android 加密存储本体，
 * 验的是托管链语义。真账号 Cookie 形态待他真机首登后对拍。
 */
public class QQLoginProviderTest {

    /** y.qq.com 登录完成后的 Cookie 形态：.qq.com 身份组＋.y.qq.com 音乐钥匙组。 */
    private static final String LOGIN_COOKIE =
            "uin=o0123456789; skey=@AbCdEfGh; p_uin=o0123456789; p_skey=xyz*abc; "
                    + "qm_keyst=Q_H_L_63bXyzFakeKeyForTestOnly; qqmusic_key=Q_H_L_63bXyzFakeKeyForTestOnly; login_type=1";

    @Test
    public void providerRegisteredWithAlignedId() {
        LoginProvider provider = LoginManager.get().getProvider("qq");
        assertNotNull(provider);
        assertTrue(provider instanceof QQLoginProvider);
        assertEquals(QQLoginProvider.ID, provider.id());
        assertEquals("QQ音乐", provider.name());
    }

    @Test
    public void cookieValidityRequiresUinAndMusicKey() {
        assertTrue(QQLoginProvider.isCookieValid(LOGIN_COOKIE));
        // 钥匙两个下发名任一即可
        assertTrue(QQLoginProvider.isCookieValid("uin=o0123; qqmusic_key=K"));
        assertTrue(QQLoginProvider.isCookieValid("qqmusic_uin=123; qm_keyst=K"));
        assertTrue(QQLoginProvider.isCookieValid("wxuin=123; qm_keyst=K"));
        // 只有 ptlogin 身份组、没有音乐钥匙：取链会被拒，不算登录
        assertFalse(QQLoginProvider.isCookieValid("uin=o0123456789; skey=@AbCdEfGh; p_skey=xyz"));
        // 只有钥匙没有账号标识
        assertFalse(QQLoginProvider.isCookieValid("qm_keyst=K; qqmusic_key=K"));
        // 空值不算
        assertFalse(QQLoginProvider.isCookieValid("uin=; qm_keyst=K"));
        assertFalse(QQLoginProvider.isCookieValid("uin=o0123; qm_keyst="));
        assertFalse(QQLoginProvider.isCookieValid(""));
        assertFalse(QQLoginProvider.isCookieValid(null));
    }

    @Test
    public void hostedRoundTripThroughLoginManager() {
        LoginManager.logoutQQ();
        assertEquals("", LoginManager.getCookie("qq"));
        assertFalse(LoginManager.isQQLoggedIn());

        QQLoginProvider.putCookie(LOGIN_COOKIE);
        assertEquals(LOGIN_COOKIE, LoginManager.getCookie("qq"));
        assertEquals(LOGIN_COOKIE, LoginManager.getQQCookie());
        assertTrue(LoginManager.isQQLoggedIn());
        assertTrue(LoginManager.isLoggedIn("qq"));

        LoginManager.logoutQQ();
        assertEquals("", LoginManager.getCookie("qq"));
        assertFalse(LoginManager.isQQLoggedIn());
    }

    @Test
    public void invalidCookieStoredRawButNotCountedAsLoggedIn() {
        QQLoginProvider.putCookie("uin=o0123456789; skey=@AbCdEfGh");
        assertEquals("uin=o0123456789; skey=@AbCdEfGh", LoginManager.getQQCookie());
        assertFalse(LoginManager.isQQLoggedIn());
        LoginManager.logoutQQ();
    }

    @Test
    public void provider115UnaffectedByQqChain() {
        assertNotNull(LoginManager.get().getProvider("115"));
        String cookie115Before = LoginManager.get115Cookie();
        boolean logged115Before = LoginManager.is115LoggedIn();

        QQLoginProvider.putCookie(LOGIN_COOKIE);
        assertEquals(cookie115Before, LoginManager.get115Cookie());
        assertEquals(logged115Before, LoginManager.is115LoggedIn());
        LoginManager.logoutQQ();
        assertEquals(cookie115Before, LoginManager.get115Cookie());
    }
}
