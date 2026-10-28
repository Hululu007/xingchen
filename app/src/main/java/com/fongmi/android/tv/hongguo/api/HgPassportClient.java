package com.fongmi.android.tv.hongguo.api;

import com.fongmi.android.tv.hongguo.sign.HgSign;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 红果 passport 短信登录客户端（原生短信链，非网页登录，全链无 WebView）。
 *
 * <p>证据链：组参/xor/宿主照 passport-login-dig.md（宿主 security.snssdk.com、
 * 端点 /passport/mobile/send_code/v1/ 与 /passport/mobile/sms_login/、
 * xz0/g.H / xz0/d.H 组参、StringUtils.encryptWithXor 本体）；传输门照
 * login-route-dig-20261028.md 的探针实证（login-probe-20261028/ 六份回包
 * 原文）：旧客户端当年 403 的死因是两道门叠加——①设备号是 freshQuery
 * 现编的随机假号（未在服务端注册，网关直接 403 空体）；②无签名头
 * （过注册门后回 error_code=14「请使用SDK接入」）。本客户端两道门都走
 * 现行栈：设备号走 HgDevice 注册档案（经 HgApiClient.ensureDevice 的
 * 同一注册门，不许再用随机号）、请求走 HgSign.signPost 签名（与
 * HgApiClient.postSigned 同路），探针以此形状两道门全过、走到手机号
 * 校验（1003）与验证码校验（1203）两关。</p>
 *
 * <p>回包判定按探针真回包形状（与 dcfa2bea 原版的区别，证据在探针存档）：
 * 真回包的 error_code / description 在 data 对象内、顶层 message 为
 * "error"/"success"；原版只读顶层 error_code 会把真错误误判成功，
 * 本版先查 data.error_code、再查顶层（防御）。错误文案一律翻成人话
 * （见 humanError），不把错误码、英文与 desc_url 糊到用户脸上。</p>
 *
 * <p>未定（如实）：①发码 type=24 未单独证（探针假号全倒在手机号关，
 * type 没轮到被判，真号首登若回 type 类错误再换候选值）；②真号发码
 * 是否触发图形验证码/shark 二次验证由服务端风险决定，假号验不到，
 * 触发时回包 data.captcha 非空，本版先给人话提示、票据路待续挖；
 * ③登录成功回包→会话落盘未验（无真码），字段形状照 passport-login-dig
 * （data.session_key 组 sessionid，Set-Cookie 优先）。</p>
 */
public final class HgPassportClient {

    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8");

    /** 只借它的设备注册门（ensureDevice），注册身份与全 App 红果链同一份 HgDevice 档案。 */
    private final HgApiClient deviceGate = new HgApiClient();

    public HgPassportClient() {
    }

    /** 发送短信验证码。成功回调 TRUE；失败回调人话原因（不吞错、不贴原始码）。 */
    public void sendSmsCode(String mobile, HgCallback<Boolean> cb) {
        postForm("/passport/mobile/send_code/v1/", HgRequests.sendCodeForm(mobile), (text, setCookie) -> {
            String err = passportError(text);
            if (err == null) cb.onSuccess(Boolean.TRUE);
            else cb.onError(-2, err);
        }, cb::onError);
    }

    /** 短信验证码登录。成功回调可直接落托管的 Cookie 串。 */
    public void smsLogin(String mobile, String code, HgCallback<String> cb) {
        postForm("/passport/mobile/sms_login/", HgRequests.smsLoginForm(mobile, code), (text, setCookie) -> {
            String cookie = loginCookie(text, setCookie);
            if (cookie != null) cb.onSuccess(cookie);
            else {
                String err = passportError(text);
                cb.onError(-2, err != null ? err : "登录没成功，请稍后再试");
            }
        }, cb::onError);
    }

    /**
     * 抖音授权码兑换登录（web 授权路的尾跳）：code 是用户在抖音授权页
     * 点同意后回跳带回的授权码，POST /passport/auth/login/ 换红果会话，
     * 成功回调与短信登录同形状（可直接落托管的 Cookie 串，走同一
     * {@link #loginCookie}）。探针 P4 已证兑换口对本形状只判码本身；
     * 真码兑换成功回包未验（等首登）。
     */
    public void douyinAuthLogin(String code, HgCallback<String> cb) {
        postForm("/passport/auth/login/", HgRequests.douyinAuthForm(code), (text, setCookie) -> {
            String cookie = loginCookie(text, setCookie);
            if (cookie != null) cb.onSuccess(cookie);
            else {
                String err = passportError(text);
                cb.onError(-2, err != null ? err : "登录没成功，请稍后再试");
            }
        }, cb::onError);
    }

    /**
     * passport 回包错误码：0＝成功形状；正数＝服务端 error_code（先查
     * data.error_code——探针真回包所在层，再查顶层作防御）；-1＝回包不是
     * 可解析的 JSON 对象；-2＝顶层 message=error 但无码可取。
     * 公开仅为离线单测可达。
     */
    public static int passportErrorCode(String text) {
        JsonObject root = parseRoot(text);
        if (root == null) return -1;
        JsonObject data = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data") : null;
        int code = errorCodeIn(data);
        if (code != 0) return code;
        code = errorCodeIn(root);
        if (code != 0) return code;
        String msg = str(root, "message");
        if ("error".equals(msg)) return -2;
        return 0;
    }

    /**
     * passport 回包判错＋人话化：成功返回 null；失败返回可直接上屏的人话
     * （已知码按码翻，余者取服务端中文 description，再兜底通用句）。
     * 公开仅为离线单测可达。
     */
    public static String passportError(String text) {
        int code = passportErrorCode(text);
        if (code == 0) return null;
        JsonObject root = parseRoot(text);
        if (root == null) return "服务暂时不可用，请稍后再试";
        JsonObject data = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data") : null;
        // 风控要图形验证码时 data.captcha 非空：SDK 票据路未接，先给人话，不贴字段。
        String captcha = str(data, "captcha");
        if (captcha != null && !captcha.isEmpty()) return "需要先完成安全验证，请稍后再试";
        switch (code) {
            case 7:
                return "操作太频繁了，请稍等一会儿再试";
            case 14:
                return "登录通道暂时异常，请稍后再试";
            case 1003:
                return "手机号好像不对，请检查一下再填";
            case 1105:
                return "验证码不对，请重新输入";
            case 1203:
                return "验证码不对或者已经过期了，请重新获取一个";
            case 1902:
                // 抖音授权码过期/无效（探针 P4 真回包：dialog_tips=aweme_v2_10007:code_expired）
                return "授权已过期，请重新点抖音一键登录";
            default:
                break;
        }
        String desc = str(data, "description");
        if (desc == null) desc = str(root, "description");
        if (desc == null) {
            String msg = str(root, "message");
            if (msg != null && !"error".equals(msg) && !"success".equals(msg)) desc = msg;
        }
        if (desc != null && !desc.isEmpty() && desc.length() <= 60
                && !desc.contains("http") && !desc.contains("SDK")) {
            return desc;
        }
        return "操作没成功，请稍后再试";
    }

    /**
     * 登录回包 → 托管 Cookie：优先服务端 Set-Cookie 里真发的会话键
     * （传输层已把多条 Set-Cookie 摘成 名=值 对串传入）；没有则用
     * data.session_key 组 sessionid（passport 会话键命名，与
     * HongguoLoginProvider 的格式判定对齐）。失败回包返回 null。
     */
    public static String loginCookie(String text, String setCookie) {
        if (passportErrorCode(text) != 0) return null;
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : null;
            String sessionKey = data == null ? null : str(data, "session_key");
            StringBuilder sb = new StringBuilder();
            boolean hasSession = false;
            if (setCookie != null && !setCookie.isEmpty()) {
                sb.append(setCookie);
                hasSession = setCookie.contains("sessionid=") || setCookie.contains("sessionid_ss=");
            }
            if (!hasSession && sessionKey != null && !sessionKey.isEmpty()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append("sessionid=").append(sessionKey);
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 传输层（注册门＋签名，与 HgApiClient.postSigned 同路） ----------

    private interface BodyHandler {
        void handle(String text, String setCookie);
    }

    private interface FailHandler {
        void fail(int code, String msg);
    }

    private void postForm(String path, Map<String, String> form, BodyHandler ok, FailHandler fail) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        final byte[] formBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        deviceGate.ensureDevice(new HgCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean registered) {
                List<String[]> pairs = new ArrayList<>(HgDevice.deviceQueryPairs());
                pairs.add(new String[]{"passport-sdk-version", "5051452"});
                pairs.add(new String[]{"account_sdk_source", "app"});
                long ticket = System.currentTimeMillis();
                HgSign.Signed signed = HgSign.signPost(path, pairs, ticket,
                        HgDevice.profile().deviceId, HgDevice.VERSION_NAME, formBytes, null);
                Map<String, String> headers = new HashMap<>();
                headers.put("user-agent", HgDevice.userAgent());
                headers.putAll(signed.headers);
                // Content-Type 由 RequestBody 承载（表单口径），签名头里的 JSON 值摘掉（与 postSigned 同处理）。
                headers.remove("Content-Type");
                headers.put("x-ss-dp", "8662");
                String url = HgRequests.PASSPORT_HOST + path + "?" + signed.query;
                RequestBody rb = RequestBody.create(FORM, body.toString());
                Call call = OkHttp.newCall(url, headers, rb);
                if (call == null) {
                    fail.fail(-1, "服务暂时不可用，请稍后再试");
                    return;
                }
                call.enqueue(new Callback() {
                    @Override
                    public void onFailure(Call c, IOException e) {
                        fail.fail(-1, "网络不太顺，请检查网络后再试");
                    }

                    @Override
                    public void onResponse(Call c, Response response) {
                        try (Response r = response) {
                            ResponseBody rb2 = r.body();
                            String text = rb2 == null ? "" : rb2.string();
                            if (!r.isSuccessful() || text.isEmpty()) {
                                fail.fail(r.code(), "服务暂时不可用，请稍后再试");
                            } else {
                                ok.handle(text, cookiePairs(r));
                            }
                        } catch (Exception e) {
                            fail.fail(-1, "网络不太顺，请检查网络后再试");
                        }
                    }
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                fail.fail(httpCode, "服务暂时不可用，请稍后再试");
            }
        });
    }

    /** 多条 Set-Cookie 头摘成 名=值 对串（去掉 path/expires 等属性），无则 null。 */
    private static String cookiePairs(Response r) {
        List<String> all = r.headers("Set-Cookie");
        if (all == null || all.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String h : all) {
            if (h == null) continue;
            int semi = h.indexOf(';');
            String pair = (semi >= 0 ? h.substring(0, semi) : h).trim();
            if (pair.isEmpty()) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(pair);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static JsonObject parseRoot(String text) {
        try {
            JsonElement el = JsonParser.parseString(text);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int errorCodeIn(JsonObject o) {
        if (o == null) return 0;
        JsonElement e = o.get("error_code");
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return e.getAsInt();
        }
        return 0;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static String enc(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }
}
