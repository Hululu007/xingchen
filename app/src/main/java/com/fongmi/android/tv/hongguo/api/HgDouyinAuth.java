package com.fongmi.android.tv.hongguo.api;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.UUID;

/**
 * 红果抖音一键登录（web 授权路）的纯逻辑件：授权 URL 组装与回跳解析。
 *
 * <p>身份参数全套出自官方包逐个抠实（证据账
 * hongguo/douyin-login-probe-20261028.md 第一节）：client_key 与
 * platform_app_id 出 TokenHelper；证书 MD5（web 路 signature 参数）出
 * 官方 APK 证书 DER；app_identity＝MD5(宿主包名) 出 authorize/a.java 的
 * 组法；redirect_uri 出 AuthImpl.preloadAuthorizeWeb 写死值。授权 URL 的
 * 参数集与顺序照 com.bytedance.sdk.open.aweme.authorize/a.java 本体；
 * 本机已按此形状实拉复验（2026-10-28 夜：HTTP 200、与探针 A1 回包同
 * 46261 字节、bid=douyin_open_auth）。</p>
 *
 * <p>回跳契约照官方 SDK 的 BaseWebAuthorizeActivity.shouldOverrideUrlLoading：
 * 导航 URL 以 redirect_uri 开头即拦截不加载（回调端点裸拉是网关 504、
 * 本就不供浏览器直开，2026-10-28 夜实拉证），从查询串取 code（成功）
 * 或 errCode（用户拒绝/出错）。同意后换会话走
 * {@link HgPassportClient#douyinAuthLogin}（/passport/auth/login/，
 * 探针 P4 以假码实跑判到 code_expired、非身份拒绝）。</p>
 *
 * <p>未验（如实）：state 只做生成与透传、回跳不做强校验（官方拦截段
 * 读了 state 但校验口径未逐行抠到，先不设闸，免得把真回跳误杀）；
 * 用户真点同意→真 code→兑换成功回包整跳只能等首登。</p>
 */
public final class HgDouyinAuth {

    /** 抖音开放平台 client_key（红果登记值）。 */
    public static final String CLIENT_KEY = "awhrxjuqewhhyckk";

    /** passport 侧 platform_app_id（红果登记值）。 */
    public static final String PLATFORM_APP_ID = "7828";

    /** passport 侧 platform 名。 */
    public static final String PLATFORM = "aweme_v2";

    /** web 路 signature 参数＝红果官方 APK 签名证书 MD5。 */
    public static final String CERT_MD5 = "56a962410c494bbaf0b58dba20cae56f";

    /** web 路 app_identity 参数＝MD5("com.phoenix.read")。 */
    public static final String APP_IDENTITY = "a31cac73514ff8516d8e56844032054f";

    /** 授权完成回跳地址（官方 SDK 写死值，拦截前缀）。 */
    public static final String REDIRECT_URI = "https://api.snssdk.com/oauth/authorize/callback/";

    /** web 授权页起始地址。 */
    public static final String AUTHORIZE_BASE = "https://open.douyin.com/platform/oauth/connect/";

    private HgDouyinAuth() {
    }

    /** 每次授权一枚新 state（透传给抖音侧、回跳原样带回）。 */
    public static String newState() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 授权起始 URL（参数集照 authorize/a.java；本机实拉与探针 A1 回包
     * 逐字节同长）。scope 只取 user_info（红果 buildAuthRequest 定版）。
     */
    public static String buildAuthorizeUrl(String state) {
        StringBuilder sb = new StringBuilder(AUTHORIZE_BASE);
        sb.append("?response_type=code");
        sb.append("&redirect_uri=").append(enc(REDIRECT_URI));
        sb.append("&client_key=").append(CLIENT_KEY);
        sb.append("&state=").append(enc(state == null ? "" : state));
        sb.append("&from=opensdk");
        sb.append("&scope=user_info");
        sb.append("&optionalScope=");
        sb.append("&signature=").append(CERT_MD5);
        sb.append("&app_identity=").append(APP_IDENTITY);
        sb.append("&device_platform=android");
        sb.append("&enter_from=account");
        sb.append("&is_other_account_auth=2");
        return sb.toString();
    }

    /** 回跳解析结果：code 非空＝拿到授权码；否则 errCode 是抖音侧错误码（可为空串）。 */
    public static final class Callback {
        public final String code;
        public final String errCode;

        Callback(String code, String errCode) {
            this.code = code;
            this.errCode = errCode;
        }

        public boolean hasCode() {
            return code != null && !code.isEmpty();
        }
    }

    /**
     * 解析回跳 URL：不是回跳地址（不以 REDIRECT_URI 开头）返回 null；
     * 是回跳则取 code / errCode（code 优先，与官方拦截段同读法）。
     */
    public static Callback parseCallback(String url) {
        if (url == null || !url.startsWith(REDIRECT_URI)) return null;
        String code = queryParam(url, "code");
        if (code != null && !code.isEmpty()) return new Callback(code, null);
        String err = queryParam(url, "errCode");
        return new Callback(null, err == null ? "" : err);
    }

    private static String queryParam(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        String query = url.substring(q + 1);
        int frag = query.indexOf('#');
        if (frag >= 0) query = query.substring(0, frag);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            if (!name.equals(key)) continue;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                return URLDecoder.decode(value, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                return value;
            }
        }
        return null;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }
}
