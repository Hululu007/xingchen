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
 * <p>回跳契约照官方 SDK 的 BaseWebAuthorizeActivity.handleRedirect 本体
 * （jadx26 逐行）：导航 URL 以 redirect_uri 开头即拦截不加载（回调端点
 * 裸拉是网关 504、本就不供浏览器直开，2026-10-28 夜实拉证），从查询串
 * 取 code/state/scopes（成功）或 errCode（用户拒绝/出错；SDK 只认
 * errCode 这一个失败参数名，不读 OAuth 通行的 error——咱家在此之上多
 * 读一个 error 兜底，见 parseCallback）。同意后换会话走
 * {@link HgPassportClient#douyinAuthLogin}（/passport/auth/login/，
 * 探针 P4 以假码实跑判到 code_expired、非身份拒绝）。</p>
 *
 * <p>授权 URL 参数集 2026-10-29 深夜按 authorize/a.java 本体逐个复核：
 * 此前漏了尾段四参（live_enter_from/comment_id/web_auth_type 三个在
 * 账号登录请求里恒为空串——webAuthType 字段缺省 null、commentId 未设、
 * extras 无 live_enter_from，Uri.Builder 仍逐个拼出空值参数；
 * is_wifi 是网络类型枚举值 MOBILE=0/WIFI=1/NONE=2，出 NetUtils 本体，
 * 按当前网络现填），本版补齐并按官方顺序排列。发页跳对参数对错本就
 * 不敏感（探针 A1–A4 消融），补齐是为同意后校验跳的形状一致。</p>
 *
 * <p>未验（如实）：①state 校验——SDK 拦截段只读 state 透传、不设闸；
 * 咱家加了一道软闸（回跳 state 非空且与发起值不符才判失败，空 state
 * 照常放行），真回跳的 state 回显形状等首登实证。②授权页 H5 内部的
 * 抖音 App 接力机制（113626 截图证 H5 会因无 App 登录态弹「请安装抖音
 * app登录抖音账号再授权」）：2026-10-29 深夜抓授权页两份页面 bundle
 * （pages/auth、pages/oauth）全文检索，无 snssdk/errCode/authorize/
 * callback 等字面量，接力 scheme 的确切形状静态抠不到（疑动态拼串或
 * 服务端驱动）；咱家 WebView 侧把全部出口接住（http 页内放行、
 * redirect 前缀拦截、非 http scheme 交系统、intent:// 解析＋fallback
 * 回退），真接力一跳的落点等他真机点同意时见。③用户真点同意→真
 * code→兑换成功回包整跳只能等首登。</p>
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

    /** is_wifi 参数的网络类型枚举（官方 NetUtils.NetworkType 本体：值是枚举序数语义、非布尔）。 */
    public static final int NETWORK_MOBILE = 0;
    public static final int NETWORK_WIFI = 1;
    public static final int NETWORK_NONE = 2;

    /**
     * 授权起始 URL（参数集与顺序照 authorize/a.java 本体逐字，含尾段
     * live_enter_from/is_wifi/comment_id/web_auth_type；本机实拉与探针
     * A1 回包逐字节同长）。scope 只取 user_info（红果 buildAuthRequest
     * 定版）；networkType 取 NETWORK_* 枚举（调用方按当前网络现填，
     * 对齐 NetUtils.getNetWorkType 的口径：异常时官方亦回 MOBILE）。
     */
    public static String buildAuthorizeUrl(String state, int networkType) {
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
        sb.append("&live_enter_from=");
        sb.append("&enter_from=account");
        sb.append("&is_wifi=").append(networkType);
        sb.append("&comment_id=");
        sb.append("&is_other_account_auth=2");
        sb.append("&web_auth_type=");
        return sb.toString();
    }

    /**
     * 回跳解析结果：code 非空＝拿到授权码；否则 errCode 是抖音侧错误码
     * （可为空串）、error 是 OAuth 通行失败参数（SDK 不读、咱家兜底读）。
     * state/scopes 与官方拦截段同读（state 供发起方软校验、scopes 是
     * 已授权限串，两者都不在咱家设硬闸）。
     */
    public static final class Callback {
        public final String code;
        public final String errCode;
        public final String error;
        public final String state;
        public final String scopes;

        Callback(String code, String errCode, String error, String state, String scopes) {
            this.code = code;
            this.errCode = errCode;
            this.error = error;
            this.state = state;
            this.scopes = scopes;
        }

        public boolean hasCode() {
            return code != null && !code.isEmpty();
        }
    }

    /**
     * 解析回跳 URL：不是回跳地址（不以 REDIRECT_URI 开头）返回 null；
     * 是回跳则取 code（优先）/ errCode / error / state / scopes
     * （与官方拦截段同读法＋error 兜底）。
     */
    public static Callback parseCallback(String url) {
        if (url == null || !url.startsWith(REDIRECT_URI)) return null;
        String state = queryParam(url, "state");
        String scopes = queryParam(url, "scopes");
        String code = queryParam(url, "code");
        if (code != null && !code.isEmpty()) return new Callback(code, null, null, state, scopes);
        String err = queryParam(url, "errCode");
        String error = queryParam(url, "error");
        return new Callback(null, err == null ? "" : err, error, state, scopes);
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
