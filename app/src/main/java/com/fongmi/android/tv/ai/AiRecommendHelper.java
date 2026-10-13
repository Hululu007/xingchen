package com.fongmi.android.tv.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.fongmi.android.tv.bean.History;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * AI 个性化推荐（P6）。
 * 根据用户观看历史生成推荐，一次调用 + 1 小时缓存，省额度。
 */
public class AiRecommendHelper {

    private static final String PREFS = "xingchen";
    private static final String KEY_CACHE = "ai_recommend_cache";
    private static final String KEY_CACHE_TIME = "ai_recommend_time";
    private static final long CACHE_TTL_MS = 60 * 60 * 1000L;

    private static final Gson GSON = new Gson();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 推荐条目：片名 + 推荐理由 */
    public static class RecommendItem {
        public String title = "";
        public String reason = "";

        public RecommendItem() {
        }

        public RecommendItem(String title, String reason) {
            this.title = title == null ? "" : title;
            this.reason = reason == null ? "" : reason;
        }
    }

    public interface RecommendCallback {
        void onResult(List<RecommendItem> items);

        void onError(String msg);
    }

    /** 最近观看的片名（去重，最多 20 个） */
    public static List<String> getWatchHistory() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            List<History> list = History.get();
            if (list == null) return new ArrayList<>();
            for (History h : list) {
                if (h == null) continue;
                String name = h.getVodName();
                if (!TextUtils.isEmpty(name)) names.add(name.trim());
                if (names.size() >= 20) break;
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(names);
    }

    /** 是否有可用的缓存 */
    public static boolean hasCache(Context context) {
        SharedPreferences sp = prefs(context);
        long t = sp.getLong(KEY_CACHE_TIME, 0);
        return System.currentTimeMillis() - t < CACHE_TTL_MS
                && !TextUtils.isEmpty(sp.getString(KEY_CACHE, null));
    }

    /** 读缓存（不校验有效期，调用方先 hasCache） */
    public static List<RecommendItem> readCache(Context context) {
        String json = prefs(context).getString(KEY_CACHE, null);
        if (TextUtils.isEmpty(json)) return new ArrayList<>();
        try {
            Type type = new TypeToken<List<RecommendItem>>() {
            }.getType();
            List<RecommendItem> list = GSON.fromJson(json, type);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 清缓存（手动刷新用） */
    public static void clearCache(Context context) {
        prefs(context).edit().remove(KEY_CACHE).remove(KEY_CACHE_TIME).apply();
    }

    private static void writeCache(Context context, List<RecommendItem> items) {
        prefs(context).edit()
                .putString(KEY_CACHE, GSON.toJson(items))
                .putLong(KEY_CACHE_TIME, System.currentTimeMillis())
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 生成推荐：优先读缓存，无缓存则调 AI（一次调用）。
     * 回调在主线程触发。
     */
    public static void recommend(Context context, RecommendCallback cb) {
        AiProvider provider = AiStore.resolveForFeature(context, AiStore.FEATURE_RECOMMEND);
        if (provider == null) {
            postError(cb, "请先在 AI 设置中配置服务商");
            return;
        }
        if (hasCache(context)) {
            List<RecommendItem> cached = readCache(context);
            if (!cached.isEmpty()) {
                postResult(cb, cached);
                return;
            }
        }
        List<String> history = getWatchHistory();
        if (history.isEmpty()) {
            postError(cb, "暂无观看记录，多看几部片再来试试");
            return;
        }
        new Thread(() -> {
            try {
                List<RecommendItem> items = requestRecommend(provider, history);
                if (items.isEmpty()) {
                    postError(cb, "AI 没想出来，换个服务商试试");
                    return;
                }
                writeCache(context.getApplicationContext(), items);
                postResult(cb, items);
            } catch (Exception e) {
                postError(cb, e.getMessage() == null ? "推荐失败" : e.getMessage());
            }
        }).start();
    }

    private static List<RecommendItem> requestRecommend(AiProvider provider, List<String> history) throws Exception {
        StringBuilder user = new StringBuilder();
        user.append("我最近看过的影视作品有：\n");
        for (String name : history) user.append("- ").append(name).append("\n");
        user.append("\n请根据我的口味推荐 10 部我可能喜欢的影视作品。");
        user.append("只输出严格的 JSON 数组，不要输出其他任何文字，不要用 markdown 代码块包裹。");
        user.append("格式：[{\"title\":\"片名\",\"reason\":\"一句话推荐理由（20字以内）\"}]。");
        user.append("推荐的片名不要和我看过的重复，尽量是真实存在的热门作品。");

        List<AiClient.Message> msgs = new ArrayList<>();
        msgs.add(AiClient.Message.system("你是星辰 App 的追剧推荐官，熟悉中外影视作品，只输出严格 JSON 数组，不输出解释。"));
        msgs.add(AiClient.Message.user(user.toString()));

        AiClient.Options opt = new AiClient.Options();
        opt.temperature = 0.7;
        opt.maxTokens = 1024;

        AiClient.ChatResult r = AiClient.chat(provider, msgs, opt);
        return parseItems(r.text, history);
    }

    /** 宽容解析：去 markdown 包裹、截取首个 [...]，过滤掉已看过的 */
    private static List<RecommendItem> parseItems(String text, List<String> history) {
        List<RecommendItem> out = new ArrayList<>();
        if (TextUtils.isEmpty(text)) return out;
        try {
            String s = text.trim();
            // 去掉 markdown 代码块包裹
            if (s.startsWith("```")) {
                int nl = s.indexOf('\n');
                s = nl > 0 ? s.substring(nl + 1) : s.substring(3);
                int end = s.lastIndexOf("```");
                if (end > 0) s = s.substring(0, end);
                s = s.trim();
            }
            int l = s.indexOf('[');
            int r = s.lastIndexOf(']');
            if (l < 0 || r <= l) return out;
            s = s.substring(l, r + 1);
            Type type = new TypeToken<List<RecommendItem>>() {
            }.getType();
            List<RecommendItem> list = GSON.fromJson(s, type);
            if (list == null) return out;
            for (RecommendItem it : list) {
                if (it == null || TextUtils.isEmpty(it.title)) continue;
                it.title = it.title.trim();
                // 过滤已看过的
                boolean watched = false;
                for (String name : history) {
                    if (name.equals(it.title) || it.title.contains(name) || name.contains(it.title)) {
                        watched = true;
                        break;
                    }
                }
                if (!watched) out.add(it);
                if (out.size() >= 10) break;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void postResult(RecommendCallback cb, List<RecommendItem> items) {
        if (cb == null) return;
        MAIN.post(() -> cb.onResult(items));
    }

    private static void postError(RecommendCallback cb, String msg) {
        if (cb == null) return;
        MAIN.post(() -> cb.onError(msg));
    }
}
