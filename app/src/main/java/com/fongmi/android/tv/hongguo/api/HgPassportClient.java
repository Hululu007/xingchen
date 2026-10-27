package com.fongmi.android.tv.hongguo.api;

import com.github.catvod.net.OkHttp;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 红果 passport 短信登录客户端（原生短信链，非网页登录）。
 *
 * <p>证据链见 ~/workspace/hongguo/passport-login-dig.md：宿主
 * security.snssdk.com（App 配置实现定版）、端点 /passport/mobile/send_code/v1/
 * 与 /passport/mobile/sms_login/（passport SDK 的 yy0/e 路径常量）、字段与
 * XOR 混淆口径（xz0/g.H / xz0/d.H 组参＋StringUtils.encryptWithXor 本体）。
 * 表单以 application/x-www-form-urlencoded POST，公共参数拼查询串
 * （HgRequests.freshQuery，与官方 NetworkParams.putCommonParams 同源一套）。</p>
 *
 * <p>未定版（如实）：①官方请求经 App 的 TTNet 原生层加签名头
 * （libsscronet/libmetasec），本客户端走 OkHttp 直发、没有这组头，
 * passport 签名校验能否过只能真机回包定；②发码 type=24 是 SDK 便捷方法
 * 的硬编码场景值，登录页最终传值未追到底；③登录回包的 Set-Cookie 在
 * OkHttp 里只能取到末值，本类以 data.session_key 兜底组 sessionid。</p>
 */
public final class HgPassportClient {

    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8");

    public HgPassportClient() {
    }

    /** 发送短信验证码。成功回调 TRUE；失败回调服务端原话（不吞错）。 */
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
            else cb.onError(-2, passportError(text) != null ? passportError(text) : "登录没成功，请稍后再试");
        }, cb::onError);
    }

    /**
     * passport 回包判错：{error_code 非 0} 为失败，返回可读信息
     * （message 优先、其次 description）；判不出失败返回 null（按成功处理，
     * 与 bookapi 的 code 容忍口径一致）。公开仅为离线单测可达。
     */
    public static String passportError(String text) {
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonElement code = root.get("error_code");
            if (code != null && code.isJsonPrimitive() && code.getAsJsonPrimitive().isNumber()
                    && code.getAsInt() != 0) {
                String msg = str(root, "message");
                if (msg == null) msg = str(root, "description");
                return msg != null ? msg : "验证码发送失败（" + code.getAsInt() + "）";
            }
            if (root.has("data") && root.get("data").isJsonObject()) return null;
            String msg = str(root, "message");
            if ("success".equals(msg)) return null;
            return msg != null && !msg.isEmpty() ? msg : "回包未识别";
        } catch (Exception e) {
            return "回包未识别";
        }
    }

    /**
     * 登录回包 → 托管 Cookie：优先服务端 Set-Cookie 里真发的会话键；
     * 没有则用 data.session_key 组 sessionid（passport 会话键命名，
     * 与 HongguoLoginProvider 的格式判定对齐）。失败回包返回 null。
     */
    public static String loginCookie(String text, String setCookie) {
        if (passportError(text) != null) return null;
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : null;
            String sessionKey = data == null ? null : str(data, "session_key");
            StringBuilder sb = new StringBuilder();
            boolean hasSession = false;
            if (setCookie != null && !setCookie.isEmpty()) {
                sb.append(setCookie);
                hasSession = setCookie.contains("sessionid=");
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

    // ---------- 传输层（与 HgApiClient 同口径：OkHttp 分发线程异步） ----------

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
        String url = HgRequests.PASSPORT_HOST + path + "?" + HgRequests.freshQuery();
        Map<String, String> headers = new HashMap<>();
        headers.put("user-agent", HgRequests.UA);
        RequestBody rb = RequestBody.create(FORM, body.toString());
        Call call = OkHttp.newCall(url, headers, rb);
        if (call == null) {
            fail.fail(-1, "无法创建请求");
            return;
        }
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                fail.fail(-1, e.getMessage());
            }

            @Override
            public void onResponse(Call c, Response response) {
                try (Response r = response) {
                    ResponseBody rb2 = r.body();
                    String text = rb2 == null ? "" : rb2.string();
                    if (!r.isSuccessful()) {
                        fail.fail(r.code(), "HTTP " + r.code());
                    } else if (text.isEmpty()) {
                        fail.fail(r.code(), "回包空体");
                    } else {
                        ok.handle(text, r.header("Set-Cookie"));
                    }
                } catch (Exception e) {
                    fail.fail(-1, e.getMessage());
                }
            }
        });
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
