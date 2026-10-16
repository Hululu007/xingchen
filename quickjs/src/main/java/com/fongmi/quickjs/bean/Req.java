package com.fongmi.quickjs.bean;

import android.text.TextUtils;

import com.github.catvod.utils.Json;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Req {

    @SerializedName("buffer")
    private Integer buffer;
    @SerializedName("redirect")
    private Integer redirect;
    @SerializedName("timeout")
    private Integer timeout;
    @SerializedName("postType")
    private String postType;
    @SerializedName("method")
    private String method;
    @SerializedName("body")
    private String body;
    @SerializedName("data")
    private JsonElement data;
    @SerializedName("headers")
    private JsonElement headers;
    @SerializedName("rejectCoding")
    private Boolean rejectCoding;

    public static Req objectFrom(String json) {
        return new Gson().fromJson(normalizeBody(json), Req.class);
    }

    // 8.83 选项语义归一化（HttpHelper.fetch0）：在 Gson 严格反序列化前处理，否则布尔 redirect、
    // 对象 body、单数 header 等合法写法都会让 fromJson 抛错、请求根本发不出去。
    private static String normalizeBody(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed == null || !parsed.isJsonObject()) return json;
            JsonObject obj = parsed.getAsJsonObject();
            boolean changed = false;
            // redirect 允许布尔（8.83 (Boolean) 强转），转 1/0
            JsonElement redirect = obj.get("redirect");
            if (redirect != null && redirect.isJsonPrimitive() && redirect.getAsJsonPrimitive().isBoolean()) {
                obj.addProperty("redirect", redirect.getAsBoolean() ? 1 : 0);
                changed = true;
            }
            // headers 取不到时认单数 header（HttpHelper:84-87）
            if (!obj.has("headers") && obj.has("header")) {
                obj.add("headers", obj.get("header"));
                changed = true;
            }
            // headers 里的 body 键同时是请求体缺省值（HttpHelper:149-153），options.body 优先
            if (!obj.has("body")) {
                JsonElement hdrs = obj.get("headers");
                if (hdrs != null && hdrs.isJsonObject()) {
                    JsonElement hb = hdrs.getAsJsonObject().get("body");
                    if (hb != null && hb.isJsonPrimitive() && hb.getAsJsonPrimitive().isString()) {
                        obj.addProperty("body", hb.getAsString());
                        changed = true;
                    }
                }
            }
            JsonElement body = obj.get("body");
            if (body != null && !body.isJsonNull()
                    && !(body.isJsonPrimitive() && body.getAsJsonPrimitive().isString())) {
                // body 允许对象/数组：原版取出后直接序列化成 JSON 字符串（HttpHelper:167-176）；
                // 本类 body 字段是 String，不预处理的话 Gson 会抛 JsonSyntaxException
                Gson gson = new Gson();
                obj.addProperty("body", gson.toJson(body));
                changed = true;
                // 非字符串 body 且 method 不在 PUT/GET/HEAD 时强制 POST（HttpHelper:171-173）
                JsonElement method = obj.get("method");
                String m = method != null && method.isJsonPrimitive() && method.getAsJsonPrimitive().isString()
                        ? method.getAsString() : null;
                if (m == null || !(m.equalsIgnoreCase("PUT") || m.equalsIgnoreCase("GET") || m.equalsIgnoreCase("HEAD"))) {
                    obj.addProperty("method", "POST");
                }
            }
            return changed ? new Gson().toJson(obj) : json;
        } catch (Exception e) {
            return json;
        }
    }

    public int getBuffer() {
        return buffer == null ? 0 : buffer;
    }

    public Integer getRedirect() {
        return redirect == null ? 1 : redirect;
    }

    public Integer getTimeout() {
        return timeout == null ? 10000 : timeout;
    }

    public String getPostType() {
        return TextUtils.isEmpty(postType) ? "json" : postType;
    }

    public String getMethod() {
        return TextUtils.isEmpty(method) ? "get" : method;
    }

    public String getBody() {
        return body;
    }

    public JsonElement getData() {
        return data;
    }

    private JsonElement getHeaders() {
        return headers;
    }

    public boolean isRedirect() {
        return getRedirect() == 1;
    }

    public boolean isRejectCoding() {
        return rejectCoding != null && rejectCoding;
    }

    public Map<String, String> getHeader() {
        return Json.toMap(getHeaders());
    }

    public String getCharset() {
        Map<String, String> header = getHeader();
        // 8.83 HttpHelper 认的三种写法（按此顺序）：content-type / Content-Type / Content-type
        List<String> keys = Arrays.asList("content-type", "Content-Type", "Content-type");
        for (String key : keys) if (header.containsKey(key)) return getCharset(Objects.requireNonNull(header.get(key)));
        return "UTF-8";
    }

    private String getCharset(String value) {
        for (String text : value.split(";")) if (text.contains("charset=")) return text.split("=")[1];
        // 8.83 兼容原版自己的 charst= 笔误（HttpHelper:174-177）
        for (String text : value.split(";")) if (text.contains("charst=")) return text.split("=")[1];
        return "UTF-8";
    }
}
