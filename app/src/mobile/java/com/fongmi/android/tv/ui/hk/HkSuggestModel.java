package com.fongmi.android.tv.ui.hk;

import com.fongmi.android.tv.api.hk.HkHttpParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import timber.log.Timber;

/**
 * 照 8.83 SuggestModel 的远程建议词部分转写（ui/search/model/SuggestModel.smali）：
 * GET http://suggestion.baidu.com/su?wd={encodeUrl(query)}&p=1&t={当前毫秒}，
 * 响应体剥 "window.baidu.sug(" 与 ");" 后解析 JSON 的 "s" 数组为建议词列表。
 * 原版的本地建议来源（网络/书签/小程序/历史，getSuggestFrom 开关组）属搜索子系统，随搜索页阶段再搬；
 * 本类只承接适配器 SuggestLoader 钩子的远程一路（原版同名 loadFromRemote 语义）。
 */
public class HkSuggestModel {

    private HkSuggestModel() {
    }

    /** 异步拉取百度建议词并回调（后台线程执行，consumer 在后台线程触发，调用方自行切主线程）。 */
    public static void loadFromRemote(String query, Consumer<List<String>> consumer) {
        new Thread(() -> {
            List<String> result = new ArrayList<>();
            try {
                String url = "http://suggestion.baidu.com/su?wd="
                        + HkHttpParser.encodeUrl(query)
                        + "&p=1&t=" + new Date().getTime();
                Timber.d("getRecommands: %s", url);
                String body = httpGet(url);
                if (body != null) result = parseRecommands(body);
            } catch (Exception e) {
                Timber.e(e);
            }
            if (consumer != null) consumer.accept(result);
        }, "hk-suggest").start();
    }

    /** 照原版 getRecommands(String)：剥壳后取 "s" 数组逐项 toString 入列；异常回已收集部分。 */
    static List<String> parseRecommands(String body) {
        List<String> list = new ArrayList<>();
        try {
            String json = body.replace("window.baidu.sug(", "").replace(");", "");
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonArray array = obj.getAsJsonArray("s");
            if (array != null) {
                for (int i = 0; i < array.size(); i++) {
                    list.add(array.get(i).getAsString());
                }
            }
        } catch (Exception e) {
            Timber.e(e);
        }
        return list;
    }

    private static String httpGet(String urlText) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlText).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod("GET");
        try {
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            in.close();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }
}
