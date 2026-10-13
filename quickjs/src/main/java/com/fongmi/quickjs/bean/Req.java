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

    // 8.83 同款（HttpHelper）：body 允许是对象/数组，原版取出后直接序列化成 JSON 字符串；
    // 本类 body 字段是 String，不预处理的话 Gson 严格反序列化会抛 JsonSyntaxException，请求根本发不出去
    private static String normalizeBody(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed == null || !parsed.isJsonObject()) return json;
            JsonObject obj = parsed.getAsJsonObject();
            JsonElement body = obj.get("body");
            if (body == null || body.isJsonNull()) return json;
            if (body.isJsonPrimitive() && body.getAsJsonPrimitive().isString()) return json;
            Gson gson = new Gson();
            obj.addProperty("body", gson.toJson(body));
            return gson.toJson(obj);
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
        List<String> keys = Arrays.asList("Content-Type", "content-type");
        for (String key : keys) if (header.containsKey(key)) return getCharset(Objects.requireNonNull(header.get(key)));
        return "UTF-8";
    }

    private String getCharset(String value) {
        for (String text : value.split(";")) if (text.contains("charset=")) return text.split("=")[1];
        return "UTF-8";
    }
}
