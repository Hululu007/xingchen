package com.fongmi.android.tv.hongguo.api;

import com.fongmi.android.tv.hongguo.model.HgCellPage;
import com.fongmi.android.tv.hongguo.model.HgParser;
import com.fongmi.android.tv.hongguo.model.HgSeriesDetail;
import com.fongmi.android.tv.hongguo.model.HgVideoModel;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 红果接口客户端（宿主 reading.snssdk.com），接口表照 structure.md 第二节。
 *
 * <p>网络栈沿用树里现有口径：com.github.catvod.net.OkHttp 出 Call、OkHttp 分发
 * 线程异步执行、解析在分发线程完成（不占主线程），回调口径见 {@link HgCallback}。</p>
 *
 * <p>口径说明：本机直发这些接口回 200 空体是本机海外出口问题（验通记录第八节
 * 第 2 条实证，连已知能成的单集口径在本机也是空体），客户端按官方真口径实现，
 * 不因本机试发结果阉割；离线验只走存档回包解析。</p>
 *
 * <p>feed 面说明：推荐流逐条 feed 请求的原始报文未在拆解中定到（structure.md
 * 标未定），本类只落 video_tab/plan + bookmall/tab + cell/change 这套能落的
 * 体系入口，逐条 feed 请求不编造。</p>
 */
public final class HgApiClient {

    public static final String HOST = "https://reading.snssdk.com";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private volatile HgAuthProvider authProvider;
    private volatile boolean drSceneEnabled;
    private volatile String drScene = "";
    private volatile HgRequests.NovelCommonParam commonParam = new HgRequests.NovelCommonParam();

    public HgApiClient() {
    }

    /** 登录态注入点（四阶段接壳子统一登录托管）；传 null 即不带登录态。 */
    public void setAuthProvider(HgAuthProvider provider) {
        this.authProvider = provider;
    }

    /** dr_scene 开关（官方 AB：VideoPlayerApiDrSceneV715）；开后取模型走 V2 变体。 */
    public void setDrScene(boolean enabled, String scene) {
        this.drSceneEnabled = enabled;
        this.drScene = scene == null ? "" : scene;
    }

    public void setCommonParam(HgRequests.NovelCommonParam param) {
        if (param != null) this.commonParam = param;
    }

    // ---------- 取播放模型（核心，批量） ----------

    /** 批量取多集播放模型：一次请求取回全部集（预取靠它，不做单集现取现等）。 */
    public void fetchVideoModels(Collection<String> videoIds, HgCallback<Map<String, HgVideoModel>> cb) {
        if (videoIds == null || videoIds.isEmpty()) {
            cb.onError(-1, "videoIds 为空");
            return;
        }
        if (drSceneEnabled) {
            String scene = drScene.isEmpty() ? "default" : drScene;
            JsonObject body = HgRequests.multiVideoModelV2Body(videoIds, scene, commonParam);
            postJson(HOST + "/novel/player/multi_video_model/" + scene + "/v1/", body, new BodyCallback() {
                @Override
                void onBody(String text) {
                    cb.onSuccess(HgParser.parseMultiVideoModel(text));
                }

                @Override
                void onFail(int code, String msg) {
                    cb.onError(code, msg);
                }
            });
        } else {
            JsonObject body = HgRequests.multiVideoModelBody(videoIds, commonParam);
            postJson(HOST + "/novel/player/multi_video_model/v1/", body, new BodyCallback() {
                @Override
                void onBody(String text) {
                    cb.onSuccess(HgParser.parseMultiVideoModel(text));
                }

                @Override
                void onFail(int code, String msg) {
                    cb.onError(code, msg);
                }
            });
        }
    }

    /** 单集取模型（走批量口的单元素形态，返回该集模型）。 */
    public void fetchVideoModel(String videoId, HgCallback<HgVideoModel> cb) {
        fetchVideoModels(Collections.singletonList(videoId), new HgCallback<Map<String, HgVideoModel>>() {
            @Override
            public void onSuccess(Map<String, HgVideoModel> result) {
                HgVideoModel m = result.get(videoId);
                if (m == null && !result.isEmpty()) m = result.values().iterator().next();
                if (m == null) cb.onError(-1, "回包无该集模型");
                else cb.onSuccess(m);
            }

            @Override
            public void onError(int httpCode, String message) {
                cb.onError(httpCode, message);
            }
        });
    }

    /** fplay 兜底取址：multi 回包的 fallback_api 地址直接 GET，返回单集模型（main_url 为加密串形态）。 */
    public void fetchByFallbackApi(String fallbackApiUrl, HgCallback<HgVideoModel> cb) {
        if (fallbackApiUrl == null || fallbackApiUrl.isEmpty()) {
            cb.onError(-1, "fallback_api 为空");
            return;
        }
        get(fallbackApiUrl, null, new BodyCallback() {
            @Override
            void onBody(String text) {
                HgVideoModel m = HgParser.parseFplay(text);
                if (m == null) cb.onError(-1, "fplay 回包无 video_info.data");
                else cb.onSuccess(m);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    // ---------- 剧集详情 ----------

    /**
     * 批量剧集详情 POST /novel/player/multi_video_detail/v1/。
     * 注意：请求体字段口径未在样本里定版，现按与取模型同构的形态构造
     * （mixed_video_id_map + NovelCommonParam + content_type），回包容忍解析；
     * 定版后只需改本方法构造处。见交付回报未定项。
     */
    public void fetchVideoDetails(Collection<String> videoIds, HgCallback<HgSeriesDetail> cb) {
        JsonObject body = new JsonObject();
        body.add("biz_param", HgRequests.bizParam(HgRequests.VIDEO_PLATFORM_MIXED));
        body.add("mixed_video_id_map", HgRequests.mixedVideoIdMap(videoIds, HgRequests.VIDEO_PLATFORM_MIXED));
        body.add("NovelCommonParam", commonParam.toJson());
        body.addProperty("content_type", HgRequests.CONTENT_TYPE);
        postJson(HOST + "/novel/player/multi_video_detail/v1/", body, new BodyCallback() {
            @Override
            void onBody(String text) {
                cb.onSuccess(HgParser.parseVideoDetail(text));
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** 单集详情 POST /novel/player/video_detail/v1/（同上，字段口径未定版）。 */
    public void fetchVideoDetail(String videoId, HgCallback<HgSeriesDetail> cb) {
        JsonObject body = new JsonObject();
        body.add("biz_param", HgRequests.bizParam(HgRequests.VIDEO_PLATFORM_MIXED));
        body.addProperty("video_id", videoId);
        body.add("NovelCommonParam", commonParam.toJson());
        body.addProperty("content_type", HgRequests.CONTENT_TYPE);
        postJson(HOST + "/novel/player/video_detail/v1/", body, new BodyCallback() {
            @Override
            void onBody(String text) {
                cb.onSuccess(HgParser.parseVideoDetail(text));
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    // ---------- 搜索 ----------

    /** 搜索结果页签 GET /reading/bookapi/search/tab（keyword 参数名未定版，现用 keyword）。 */
    public void searchTab(String keyword, HgCallback<HgCellPage> cb) {
        Map<String, String> q = new HashMap<>();
        q.put("keyword", keyword);
        get(HOST + "/reading/bookapi/search/tab", q, cellCallback(cb));
    }

    /** 搜索提示 GET /reading/bookapi/search/cue。 */
    public void searchCue(String keyword, HgCallback<HgCellPage> cb) {
        Map<String, String> q = new HashMap<>();
        q.put("keyword", keyword);
        get(HOST + "/reading/bookapi/search/cue", q, cellCallback(cb));
    }

    /** 搜索联想 GET /reading/bookapi/search/suggest。 */
    public void searchSuggest(String keyword, HgCallback<HgCellPage> cb) {
        Map<String, String> q = new HashMap<>();
        q.put("keyword", keyword);
        get(HOST + "/reading/bookapi/search/suggest", q, cellCallback(cb));
    }

    /** 搜索结果翻页 GET /reading/bookapi/search/cell/change（翻页游标参数未定版，透传 params）。 */
    public void searchCellChange(Map<String, String> params, HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/search/cell/change", params, cellCallback(cb));
    }

    // ---------- 剧场分类 ----------

    public void categoryFront(HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/new_category/front", null, cellCallback(cb));
    }

    public void categoryLanding(Map<String, String> params, HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/new_category/landing", params, cellCallback(cb));
    }

    public void categoryCell(Map<String, String> params, HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/new_category/cell", params, cellCallback(cb));
    }

    // ---------- 首页/剧场计划位（feed 面能落的部分） ----------

    /** GET /reading/bookapi/plan：服务端编排的计划位。 */
    public void plan(HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/plan", null, cellCallback(cb));
    }

    /** GET /reading/bookapi/bookmall/tab：剧场页签。 */
    public void bookmallTab(HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/bookmall/tab", null, cellCallback(cb));
    }

    /** GET /reading/bookapi/bookmall/cell/change：剧场 cell 翻页（参数透传）。 */
    public void bookmallCellChange(Map<String, String> params, HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/bookmall/cell/change", params, cellCallback(cb));
    }

    // ---------- 追剧架（账号维度，靠登录态注入） ----------

    public void bookshelfVideoList(HgCallback<HgCellPage> cb) {
        get(HOST + "/reading/bookapi/bookshelf/video/list", null, cellCallback(cb));
    }

    public void bookshelfVideoInfo(String seriesId, HgCallback<HgCellPage> cb) {
        Map<String, String> q = new HashMap<>();
        q.put("series_id", seriesId);
        get(HOST + "/reading/bookapi/bookshelf/video/info", q, cellCallback(cb));
    }

    /**
     * 追剧架更新 POST /reading/bookapi/bookshelf/video/update（收藏/追更共用此口，
     * 官方 FollowScene 加入收藏与右列追剧钮同走 AddShelf，见 HgRequests
     * bookshelfVideoUpdateBody 注的出处）。add=true 加入、false 移出。
     * 成功判据容忍：回包 code 为 0 / "0" / "SUCCESS" / 缺失均算成（bookapi
     * 的 code 是 BookApiERR 枚举，序列化形态待真机校准），其余算失败回调。
     */
    public void bookshelfVideoUpdate(String seriesId, boolean add, HgCallback<Boolean> cb) {
        JsonObject body = HgRequests.bookshelfVideoUpdateBody(seriesId, add, System.currentTimeMillis());
        postJson(HOST + "/reading/bookapi/bookshelf/video/update", body, new BodyCallback() {
            @Override
            void onBody(String text) {
                if (isOkCode(text)) cb.onSuccess(Boolean.TRUE);
                else cb.onError(-2, "追剧架更新被服务端拒绝");
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    // ---------- 预约（未上线剧，账号维度，靠登录态注入） ----------

    /**
     * 预约/取消预约 POST /reading/bookapi/search/uncover_subscribe。
     * 出处链逐环见 {@link HgRequests#searchUncoverSubscribeBody} 注（端点＝
     * BookApiService.searchUncoverSubscribeRx，item_type 短剧=14，
     * op_type Subscribe=1/CancelSubscribe=2）。成功判据同追剧架的 code 容忍口径。
     */
    public void subscribeSeries(String seriesId, boolean subscribe, HgCallback<Boolean> cb) {
        JsonObject body = HgRequests.searchUncoverSubscribeBody(seriesId, subscribe);
        postJson(HOST + "/reading/bookapi/search/uncover_subscribe", body, new BodyCallback() {
            @Override
            void onBody(String text) {
                if (isOkCode(text)) cb.onSuccess(Boolean.TRUE);
                else cb.onError(-2, "预约操作被服务端拒绝");
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /**
     * 我的预约列表 GET /reading/user/subscribe/list（tab_type=MySubscribed=1）。
     * 出处：classes11.dex rpc 模型 GetUserSubscribeListRequest /
     * UserSubscribeData{subscribe_items, has_more, ...}；端点字符串在国内版
     * 样本逐字命中。解析走容忍抽取（见 HgParser.parseSubscribeList 注）。
     */
    public void fetchMyReservations(HgCallback<java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem>> cb) {
        get(HOST + "/reading/user/subscribe/list", HgRequests.subscribeListParams(), new BodyCallback() {
            @Override
            void onBody(String text) {
                cb.onSuccess(HgParser.parseSubscribeList(text));
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** bookapi 回包 code 容忍判定（枚举序列化形态未定版，见上注）。公开仅为离线单测可达。 */
    public static boolean isOkCode(String text) {
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            if (!root.has("code") || root.get("code").isJsonNull()) return true;
            com.google.gson.JsonElement code = root.get("code");
            if (code.isJsonPrimitive() && code.getAsJsonPrimitive().isNumber()) return code.getAsInt() == 0;
            String s = code.getAsString();
            return s == null || s.isEmpty() || "0".equals(s) || "SUCCESS".equalsIgnoreCase(s) || "Success".equals(s);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- 传输层 ----------

    private BodyCallback cellCallback(HgCallback<HgCellPage> cb) {
        return new BodyCallback() {
            @Override
            void onBody(String text) {
                cb.onSuccess(HgParser.parseCellPage(text));
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        };
    }

    private abstract static class BodyCallback {
        abstract void onBody(String text);

        abstract void onFail(int code, String msg);
    }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("user-agent", HgRequests.UA);
        HgAuthProvider auth = authProvider;
        if (auth != null) {
            Map<String, String> extra = auth.headers();
            if (extra != null) h.putAll(extra);
            String cookie = auth.cookie();
            if (cookie != null && !cookie.isEmpty()) h.put("Cookie", cookie);
        }
        return h;
    }

    private void postJson(String path, JsonObject body, BodyCallback cb) {
        String url = path + (path.contains("?") ? "&" : "?") + HgRequests.freshQuery();
        RequestBody rb = RequestBody.create(JSON, body.toString());
        Call call = OkHttp.newCall(url, headers(), rb);
        enqueue(call, cb);
    }

    private void get(String path, Map<String, String> params, BodyCallback cb) {
        StringBuilder url = new StringBuilder(path);
        url.append(path.contains("?") ? "&" : "?").append(HgRequests.freshQuery());
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                url.append("&").append(enc(e.getKey())).append("=").append(enc(e.getValue()));
            }
        }
        Call call = OkHttp.newCall(url.toString(), headers());
        enqueue(call, cb);
    }

    private void enqueue(Call call, BodyCallback cb) {
        if (call == null) {
            cb.onFail(-1, "无法创建请求");
            return;
        }
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                cb.onFail(-1, e.getMessage());
            }

            @Override
            public void onResponse(Call c, Response response) {
                // 此处在 OkHttp 分发线程：读体与解析都不占主线程。
                try (Response r = response) {
                    ResponseBody rb = r.body();
                    String text = rb == null ? "" : rb.string();
                    if (!r.isSuccessful()) {
                        cb.onFail(r.code(), "HTTP " + r.code());
                    } else if (text.isEmpty()) {
                        cb.onFail(r.code(), "回包空体");
                    } else {
                        cb.onBody(text);
                    }
                } catch (Exception e) {
                    cb.onFail(-1, e.getMessage());
                }
            }
        });
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
