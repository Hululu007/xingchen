package com.fongmi.android.tv.hongguo.drama;

import com.fongmi.android.tv.hongguo.api.HgApiClient;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.api.HgDevice;
import com.fongmi.android.tv.hongguo.api.HgRequests;
import com.fongmi.android.tv.hongguo.model.HgContinuation;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 短剧取数客户端（2026-10-30 从零重建）：全部端点共享呈现优先
 * （DramaSign，drpys/hongguoTV 形态），身份被拒/空体/传输失败时按
 * DramaFetchPolicy 冷却降级到自家档案（HgApiClient 已验同步签名口）；
 * 永久锁存整件废除，冷却到期自动回共享。
 *
 * <p>端点与回包口径维持已验链路：频道 bookmall tab/cell change、详情
 * video_detail/multi_video_detail/album_detail、搜索 search/tab、
 * 播放 multi_video_model 批量优先（≤20/发）＋落盘缓存＋单集兜底
 * ＋fallback_api 直取。线程口径同 HgCallback：回调在后台线程。</p>
 */
public final class DramaClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=UTF-8");
    private static final int MULTI_CHUNK = 20;
    private static final String KEY_COOLDOWN = "drama_fetch_cooldown_until";

    private static final ExecutorService WORKER = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "drama-fetch");
        t.setDaemon(true);
        return t;
    });

    private static final DramaFetchPolicy POLICY = new DramaFetchPolicy(
            System::currentTimeMillis,
            new DramaFetchPolicy.Store() {
                @Override
                public long readCooldownUntil() {
                    return Prefers.getLong(KEY_COOLDOWN, 0L);
                }

                @Override
                public void writeCooldownUntil(long untilMillis) {
                    Prefers.put(KEY_COOLDOWN, untilMillis);
                }
            });

    private DramaClient() {
    }

    /** 取模型请求项：vid＋内容类型（mixed_video_id_map 的分组键，漫剧 1004 等）。 */
    public static final class ModelRequest {
        public final String vid;
        public final int contentType;

        public ModelRequest(String vid, int contentType) {
            this.vid = vid;
            this.contentType = contentType;
        }
    }

    // ---------- 内部收口 ----------

    private interface Sink {
        void body(String text);

        void fail(int code, String msg);
    }

    /** 回包预检：空体→-2；业务码非 0→以业务码失败；否则放行原文。 */
    private static void deliver(String text, Sink sink) {
        if (text == null || text.isEmpty()) {
            sink.fail(DramaFetchPolicy.CODE_EMPTY, "回包空体");
            return;
        }
        int code = DramaParser.businessCodeOf(text);
        if (code != 0) {
            sink.fail(code, DramaParser.businessMessageOf(text));
            return;
        }
        sink.body(text);
    }

    private static void enqueue(Call call, Sink sink) {
        if (call == null) {
            sink.fail(-1, "无法创建请求");
            return;
        }
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call c, IOException e) {
                sink.fail(-1, e.getMessage());
            }

            @Override
            public void onResponse(Call c, Response response) {
                try (Response r = response) {
                    ResponseBody rb = r.body();
                    String text = rb == null ? "" : rb.string();
                    if (!r.isSuccessful()) sink.fail(r.code(), "HTTP " + r.code());
                    else deliver(text, sink);
                } catch (Exception e) {
                    sink.fail(-1, e.getMessage());
                }
            }
        });
    }

    /** 播放面宿主口径（已验链路原样）：只有 multi 口走 sinfonlineb，其余走 a 面。 */
    private static String hostFor(String path) {
        return HgDevice.PATH_PLAYBACK_MULTI.equals(path) ? HgDevice.API_HOST_B : HgDevice.API_HOST;
    }

    private static void sharedPost(String path, JsonObject bodyObj, Sink sink) {
        try {
            String host = hostFor(path);
            byte[] body = bodyObj.toString().getBytes(StandardCharsets.UTF_8);
            DramaSign.Signed s = DramaSign.sign(host + path, DramaSign.devicePairs(false), body,
                    System.currentTimeMillis());
            Map<String, String> h = new LinkedHashMap<>(s.headers);
            enqueue(OkHttp.newCall(s.url, h, RequestBody.create(body, JSON)), sink);
        } catch (Exception e) {
            sink.fail(-1, e.getMessage());
        }
    }

    private static void sharedGet(String host, String path, List<String[]> pairs,
                                  boolean searchFace, Sink sink) {
        try {
            List<String[]> all = new ArrayList<>(DramaSign.devicePairs(searchFace));
            all.addAll(businessOnly(pairs));
            DramaSign.Signed s = DramaSign.sign(host + path, all, null, System.currentTimeMillis());
            enqueue(OkHttp.newCall(s.url, new LinkedHashMap<>(s.headers)), sink);
        } catch (Exception e) {
            sink.fail(-1, e.getMessage());
        }
    }

    private static void ownPost(String path, JsonObject bodyObj, Sink sink) {
        WORKER.execute(() -> {
            try {
                deliver(HgApiClient.getInstance().postSignedSync(hostFor(path), path, bodyObj), sink);
            } catch (Exception e) {
                sink.fail(-1, e.getMessage());
            }
        });
    }

    private static void ownGet(String path, List<String[]> pairs, Sink sink) {
        WORKER.execute(() -> {
            try {
                deliver(HgApiClient.getInstance().getSignedSync(path, pairs), sink);
            } catch (Exception e) {
                sink.fail(-1, e.getMessage());
            }
        });
    }

    /** 共享先行＋策略兜底的统一包覆（POST 播放面）。 */
    private static void postPlayer(String path, JsonObject bodyObj, Sink sink) {
        if (POLICY.route() == DramaFetchPolicy.Route.OWN) {
            ownPost(path, bodyObj, sink);
            return;
        }
        sharedPost(path, bodyObj, new Sink() {
            @Override
            public void body(String text) {
                POLICY.onSharedOutcome(DramaFetchPolicy.SharedOutcome.SUCCESS);
                sink.body(text);
            }

            @Override
            public void fail(int code, String msg) {
                DramaFetchPolicy.Action a = POLICY.onSharedOutcome(DramaFetchPolicy.classify(code));
                if (a == DramaFetchPolicy.Action.FALLBACK_OWN) ownPost(path, bodyObj, sink);
                else sink.fail(code, msg);
            }
        });
    }

    /** 共享先行＋策略兜底的统一包覆（GET 浏览面）。 */
    private static void getBrowse(String path, List<String[]> fullPairs, boolean searchFace, Sink sink) {
        if (POLICY.route() == DramaFetchPolicy.Route.OWN) {
            ownGet(path, fullPairs, sink);
            return;
        }
        sharedGet(HgApiClient.HOST, path, fullPairs, searchFace, new Sink() {
            @Override
            public void body(String text) {
                POLICY.onSharedOutcome(DramaFetchPolicy.SharedOutcome.SUCCESS);
                sink.body(text);
            }

            @Override
            public void fail(int code, String msg) {
                DramaFetchPolicy.Action a = POLICY.onSharedOutcome(DramaFetchPolicy.classify(code));
                if (a == DramaFetchPolicy.Action.FALLBACK_OWN) ownGet(path, fullPairs, sink);
                else sink.fail(code, msg);
            }
        });
    }

    private static final Set<String> DEVICE_KEYS = new HashSet<>(java.util.Arrays.asList(
            "iid", "device_id", "ac", "channel", "aid", "app_name", "version_code",
            "version_name", "device_platform", "os", "ssmix", "device_type",
            "device_brand", "language", "os_api", "os_version", "manifest_version_code",
            "resolution", "dpi", "update_version_code", "host_abi", "dragon_device_type",
            "pv_player", "compliance_status", "need_personal_recommend",
            "player_so_load", "is_android_pad_screen", "cdid", "openudid", "ts", "_rticket"));

    /** 从 HgRequests 的整表（含设备段）里摘出业务参（共享签名时设备段由 DramaSign 提供）。 */
    private static List<String[]> businessOnly(List<String[]> pairs) {
        List<String[]> out = new ArrayList<>();
        for (String[] p : pairs) if (!DEVICE_KEYS.contains(p[0])) out.add(p);
        return out;
    }

    private static <T> Sink sinkOf(HgCallback<T> cb, java.util.function.Function<String, T> parse) {
        return new Sink() {
            @Override
            public void body(String text) {
                T v;
                try {
                    v = parse.apply(text);
                } catch (Exception e) {
                    cb.onError(-2, "回包解析失败");
                    return;
                }
                if (v == null) cb.onError(-2, "回包解析失败");
                else cb.onSuccess(v);
            }

            @Override
            public void fail(int code, String msg) {
                cb.onError(code, msg);
            }
        };
    }

    // ---------- 播放模型 ----------

    /** multi 请求体（hongguoTV 字面口径：101FF 面同体）。 */
    private static JsonObject multiBody(Map<String, List<String>> grouped) {
        JsonObject biz = new JsonObject();
        biz.addProperty("detail_page_version", 0);
        biz.addProperty("device_level", 3);
        biz.addProperty("disable_digg_stat", false);
        biz.addProperty("need_all_video_definition", true);
        biz.addProperty("need_mp4_align", false);
        biz.addProperty("use_os_player", false);
        biz.addProperty("use_server_dns", false);
        biz.addProperty("video_platform", 1024);
        JsonObject map = new JsonObject();
        for (Map.Entry<String, List<String>> e : grouped.entrySet()) {
            JsonArray ids = new JsonArray();
            for (String id : e.getValue()) ids.add(id);
            map.add(e.getKey(), ids);
        }
        JsonObject body = new JsonObject();
        body.add("biz_param", biz);
        body.add("mixed_video_id_map", map);
        return body;
    }

    /** 单集请求体（旧 playbackBody 定版口径）。 */
    private static JsonObject singleBody(String videoId) {
        JsonObject biz = new JsonObject();
        biz.addProperty("need_all_video_definition", true);
        biz.addProperty("video_id_type", 0);
        biz.addProperty("video_platform", HgRequests.VIDEO_PLATFORM_FQ);
        JsonObject body = new JsonObject();
        body.add("biz_param", biz);
        body.addProperty("video_id", videoId);
        return body;
    }

    /**
     * 批量取模型：缓存命中的直接入表，未命中的按内容类型分组、每发
     * ≤20 走 multi；回包缺失的 vid 不入表（由调用方决定是否单集兜底）。
     */
    public static void fetchModels(List<ModelRequest> requests,
                                   HgCallback<Map<String, DramaVideoModel>> cb) {
        Map<String, DramaVideoModel> out = new LinkedHashMap<>();
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        if (requests != null) {
            for (ModelRequest r : requests) {
                if (r == null || r.vid == null || r.vid.isEmpty() || out.containsKey(r.vid)) continue;
                DramaVideoModel cached = DramaCache.get(r.vid);
                if (cached != null && cached.firstPlayableEntry() != null) {
                    out.put(r.vid, cached);
                    continue;
                }
                grouped.computeIfAbsent(String.valueOf(r.contentType), k -> new ArrayList<>()).add(r.vid);
            }
        }
        if (grouped.isEmpty()) {
            cb.onSuccess(out);
            return;
        }
        // 按 ≤20 切发，串行累积（预取/首屏批量都在这个量级）
        List<Map.Entry<String, List<String>>> groups = new ArrayList<>(grouped.entrySet());
        fetchChunk(groups, 0, out, cb);
    }

    private static void fetchChunk(List<Map.Entry<String, List<String>>> groups, int index,
                                   Map<String, DramaVideoModel> out,
                                   HgCallback<Map<String, DramaVideoModel>> cb) {
        // 找到下一块未发完的分组切片
        while (index < groups.size() && groups.get(index).getValue().isEmpty()) index++;
        if (index >= groups.size()) {
            cb.onSuccess(out);
            return;
        }
        Map.Entry<String, List<String>> g = groups.get(index);
        List<String> ids = g.getValue();
        int n = Math.min(MULTI_CHUNK, ids.size());
        List<String> slice = new ArrayList<>(ids.subList(0, n));
        ids.subList(0, n).clear();
        Map<String, List<String>> one = new LinkedHashMap<>();
        one.put(g.getKey(), slice);
        final int idx = index;
        postPlayer(HgDevice.PATH_PLAYBACK_MULTI, multiBody(one), new Sink() {
            @Override
            public void body(String text) {
                Map<String, DramaVideoModel> got = DramaParser.parseMultiVideoModel(text);
                for (DramaVideoModel m : got.values()) {
                    DramaCache.put(m);
                    String vid = m.getRequestedId() != null ? m.getRequestedId() : m.getVideoId();
                    if (vid != null) out.put(vid, m);
                }
                fetchChunk(groups, idx, out, cb);
            }

            @Override
            public void fail(int code, String msg) {
                // 本块失败不连坐其余块：跳过本块继续（缺失 vid 由上层单集兜底）
                fetchChunk(groups, idx, out, cb);
            }
        });
    }

    /**
     * 起播取模型全链：落盘缓存 → multi 单发 →（回包无此条才）单集口 →
     * 仍无可播档且模型带 fallback_api 时直取 fplay。
     */
    public static void fetchModelForPlay(String vid, int contentType, HgCallback<DramaVideoModel> cb) {
        if (vid == null || vid.isEmpty()) {
            cb.onError(-1, "videoId 为空");
            return;
        }
        DramaVideoModel cached = DramaCache.get(vid);
        if (cached != null && cached.firstPlayableEntry() != null) {
            cb.onSuccess(cached);
            return;
        }
        List<ModelRequest> one = new ArrayList<>();
        one.add(new ModelRequest(vid, contentType));
        fetchModels(one, new HgCallback<Map<String, DramaVideoModel>>() {
            @Override
            public void onSuccess(Map<String, DramaVideoModel> map) {
                DramaVideoModel m = map.get(vid);
                if (m != null && m.firstPlayableEntry() != null) {
                    cb.onSuccess(m);
                    return;
                }
                fetchSingle(vid, m, cb);
            }

            @Override
            public void onError(int code, String message) {
                fetchSingle(vid, null, cb);
            }
        });
    }

    private static void fetchSingle(String vid, DramaVideoModel partial, HgCallback<DramaVideoModel> cb) {
        postPlayer(HgDevice.PATH_PLAYBACK, singleBody(vid), new Sink() {
            @Override
            public void body(String text) {
                DramaVideoModel m = DramaParser.parseSingleVideoModel(text, vid);
                if (m != null) DramaCache.put(m);
                settlePlayModel(vid, m != null ? m : partial, cb);
            }

            @Override
            public void fail(int code, String msg) {
                if (partial != null) settlePlayModel(vid, partial, cb);
                else cb.onError(code, msg);
            }
        });
    }

    private static void settlePlayModel(String vid, DramaVideoModel m, HgCallback<DramaVideoModel> cb) {
        if (m == null) {
            cb.onError(-2, "回包无播放模型");
            return;
        }
        if (m.firstPlayableEntry() != null) {
            cb.onSuccess(m);
            return;
        }
        String fplay = m.getFallbackApi();
        if (fplay == null || fplay.isEmpty()) {
            cb.onError(-2, "回包无可播档");
            return;
        }
        enqueue(OkHttp.newCall(fplay, new LinkedHashMap<>()), new Sink() {
            @Override
            public void body(String text) {
                DramaVideoModel fm = DramaParser.parseFplay(text);
                if (fm != null && fm.firstPlayableEntry() != null) cb.onSuccess(fm);
                else cb.onError(-2, "回包无可播档");
            }

            @Override
            public void fail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** fallback_api 直取（无签名 GET，fplay 形态模型；解链层加密兜底用）。 */
    public static void fetchFplay(String fallbackApiUrl, HgCallback<DramaVideoModel> cb) {
        if (fallbackApiUrl == null || fallbackApiUrl.isEmpty()) {
            cb.onError(-1, "fallback_api 为空");
            return;
        }
        enqueue(OkHttp.newCall(fallbackApiUrl, new LinkedHashMap<>()), new Sink() {
            @Override
            public void body(String text) {
                DramaVideoModel m = DramaParser.parseFplay(text);
                if (m == null) cb.onError(-2, "fplay 回包解析失败");
                else cb.onSuccess(m);
            }

            @Override
            public void fail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    // ---------- 详情 ----------

    private static JsonObject seriesBody(String seriesId) {
        JsonObject biz = new JsonObject();
        biz.addProperty("caller_scene", "single_col");
        biz.addProperty("detail_page_version", 0);
        biz.addProperty("from_video_id", "");
        biz.addProperty("need_all_video_definition", false);
        biz.addProperty("need_mp4_align", false);
        biz.addProperty("source", 4);
        biz.addProperty("video_id_type", 1);
        JsonObject body = new JsonObject();
        body.add("biz_param", biz);
        body.addProperty("series_id", seriesId);
        return body;
    }

    private static JsonObject seriesBatchBody(Collection<String> seriesIds) {
        JsonObject biz = new JsonObject();
        biz.addProperty("detail_page_version", 0);
        biz.addProperty("need_all_video_definition", false);
        biz.addProperty("need_mp4_align", false);
        biz.addProperty("source", 7);
        biz.addProperty("use_os_player", false);
        biz.addProperty("use_server_dns", false);
        JsonObject body = new JsonObject();
        body.add("biz_param", biz);
        body.addProperty("dr_scene", "player");
        StringBuilder joined = new StringBuilder();
        if (seriesIds != null) {
            for (String id : seriesIds) {
                if (id == null) continue;
                if (joined.length() > 0) joined.append(',');
                joined.append(id);
            }
        }
        body.addProperty("series_id", joined.toString());
        return body;
    }

    private static JsonObject ugcBatchBody(String videoId) {
        JsonObject biz = new JsonObject();
        biz.addProperty("source", 17);
        biz.addProperty("video_id_type", 1);
        JsonObject body = new JsonObject();
        body.add("biz_param", biz);
        body.addProperty("dr_scene", "player");
        body.addProperty("series_id", videoId == null ? "" : videoId);
        return body;
    }

    private static JsonObject albumBody(String albumId) {
        JsonObject biz = new JsonObject();
        biz.addProperty("video_platform", 3);
        JsonObject body = new JsonObject();
        body.addProperty("album_id", albumId);
        body.add("biz_param", biz);
        body.addProperty("need_video_detail_info", true);
        body.add("series_ids", new JsonArray());
        return body;
    }

    /** 单剧详情（含集表）。 */
    public static void fetchDetail(String seriesId, HgCallback<DramaDetail> cb) {
        postPlayer(HgDevice.PATH_SERIES, seriesBody(seriesId),
                sinkOf(cb, text -> DramaParser.parseVideoDetail(text)));
    }

    /** 批量剧详情 → 以剧 id 为键。 */
    public static void fetchDetails(Collection<String> seriesIds,
                                    HgCallback<Map<String, DramaDetail>> cb) {
        postPlayer(HgDevice.PATH_SERIES_BATCH, seriesBatchBody(seriesIds),
                sinkOf(cb, DramaParser::parseSeriesBatch));
    }

    /** UGC 单条详情（回包自带 video_model 时挂在 detail 上）。 */
    public static void fetchUgcDetail(String videoId, HgCallback<DramaDetail> cb) {
        postPlayer(HgDevice.PATH_SERIES_BATCH, ugcBatchBody(videoId), new Sink() {
            @Override
            public void body(String text) {
                Map<String, DramaDetail> map = DramaParser.parseSeriesBatch(text);
                DramaDetail d = map.get(videoId);
                if (d == null && !map.isEmpty()) d = map.values().iterator().next();
                if (d == null) cb.onError(-2, "回包无该视频详情");
                else cb.onSuccess(d);
            }

            @Override
            public void fail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /**
     * 相关作品（详情页）：官方 plan 口（book_id＝本剧、from＝
     * detail_page_more_related、scene＝10）回包 547 cell 的同 IP
     * 绑定作品表。cell 条目集数常为 0，取回后对缺集数的条目发一发
     * 批量详情补 episode_cnt/series_status（补失败不连坐、保持原表
     * 回调）；无绑定作品回空表。证据与口径见账 hongguo-related-20261030。
     */
    public static void fetchRelatedWorks(String seriesId,
                                         HgCallback<List<DramaDetail.RelatedWork>> cb) {
        if (seriesId == null || seriesId.isEmpty()) {
            cb.onError(-1, "series_id 为空");
            return;
        }
        List<String[]> pairs = new ArrayList<>(HgDevice.deviceQueryPairs());
        pairs.add(new String[]{"book_id", seriesId});
        pairs.add(new String[]{"from", "detail_page_more_related"});
        pairs.add(new String[]{"scene", "10"});
        pairs.add(new String[]{"session_uuid", java.util.UUID.randomUUID().toString()});
        pairs.add(new String[]{"device_level", "3"});
        getBrowse(HgDevice.PATH_PLAN, pairs, false, new Sink() {
            @Override
            public void body(String text) {
                List<DramaDetail.RelatedWork> works;
                try {
                    works = DramaParser.parseRelatedWorks(text);
                } catch (Exception e) {
                    cb.onError(-2, "相关作品回包解析失败");
                    return;
                }
                List<String> missing = new ArrayList<>();
                for (DramaDetail.RelatedWork w : works) {
                    if (w.getEpisodeCount() <= 0) missing.add(w.getSeriesId());
                }
                if (missing.isEmpty()) {
                    cb.onSuccess(works);
                    return;
                }
                fetchDetails(missing, new HgCallback<Map<String, DramaDetail>>() {
                    @Override
                    public void onSuccess(Map<String, DramaDetail> map) {
                        List<DramaDetail.RelatedWork> merged = new ArrayList<>();
                        for (DramaDetail.RelatedWork w : works) {
                            DramaDetail d = map.get(w.getSeriesId());
                            merged.add(d == null ? w : w.withEpisodeInfo(
                                    d.getEpisodeCount(), d.getSeriesStatus()));
                        }
                        cb.onSuccess(merged);
                    }

                    @Override
                    public void onError(int code, String msg) {
                        cb.onSuccess(works);
                    }
                });
            }

            @Override
            public void fail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** 合集各季表。 */
    public static void fetchAlbum(String albumId, HgCallback<List<DramaItem>> cb) {
        postPlayer(HgDevice.PATH_ALBUM, albumBody(albumId),
                sinkOf(cb, DramaParser::parseAlbum));
    }

    /** 合集详情（头部＋集列表）。 */
    public static void fetchAlbumDetail(String albumId, HgCallback<DramaAlbumDetail> cb) {
        postPlayer(HgDevice.PATH_ALBUM, albumBody(albumId),
                sinkOf(cb, DramaParser::parseAlbumDetail));
    }

    // ---------- 频道 ----------

    /** 频道开页：requestTabType 开页请求值、responseTabType 回包要读的频道。 */
    public static void fetchChannel(int requestTabType, int responseTabType,
                                    HgCallback<DramaCellPage> cb) {
        getBrowse(HgDevice.PATH_BOOKMALL_TAB, HgRequests.discoverPairs(requestTabType), false,
                sinkOf(cb, text -> DramaParser.parseDiscoverTab(text, responseTabType)));
    }

    /** 频道续页。 */
    public static void fetchChannelMore(HgContinuation cont, HgCallback<DramaCellPage> cb) {
        getBrowse(HgDevice.PATH_BOOKMALL_CELL_CHANGE, HgRequests.cellChangePairs(cont), false,
                sinkOf(cb, DramaParser::parseCellChange));
    }

    // ---------- 官方筛选面（distribution 分类浏览） ----------

    /** 筛选维度键序（openhongguo ShortFilterBody 字面序，实拉验通）。 */
    private static final String[] FILTER_KEYS = {
            "category_dim_epoch", "online_time", "gender", "category_dim_role",
            "genre", "creation_status", "sort", "category_dim_theme"};

    /** 体裁（genre 取值）→ 筛选面场景：真人 default、漫剧/AI 各用自名。 */
    private static String filterScene(String genre) {
        if ("comic_series".equals(genre) || "ai_series".equals(genre)) return genre;
        return "default";
    }

    private static JsonObject filterPanelBody(String genre) {
        JsonObject body = new JsonObject();
        body.addProperty("req_scene", filterScene(genre));
        body.addProperty("need_selector_panel", false);
        body.addProperty("req_type", "only_panel");
        return body;
    }

    private static JsonObject filterContentBody(String genre, String dimKey, String dimValue,
                                                int offset, String sessionId,
                                                List<String> seenIds) {
        JsonObject body = new JsonObject();
        StringBuilder ids = new StringBuilder();
        if (seenIds != null) {
            for (String id : seenIds) {
                if (id == null || id.isEmpty()) continue;
                if (ids.length() > 0) ids.append(',');
                ids.append(id);
            }
        }
        body.addProperty("filter_ids", ids.toString());
        body.addProperty("req_scene", filterScene(genre));
        body.addProperty("offset", offset);
        body.addProperty("limit", 18);
        body.addProperty("need_selector_panel", false);
        JsonObject items = new JsonObject();
        for (String key : FILTER_KEYS) {
            JsonArray arr = new JsonArray();
            if ("genre".equals(key)) arr.add(genre);
            else if (key.equals(dimKey) && dimValue != null) arr.add(dimValue);
            items.add(key, arr);
        }
        body.add("select_items", items);
        body.addProperty("session_id", sessionId == null ? "" : sessionId);
        body.addProperty("req_type", "only_content");
        return body;
    }

    /**
     * 筛选面统一包覆：走 postPlayer 两面策略；服务端偶发静默节流会回
     * code=0 的空结果（探针实证同序重发即有数，账 hongguo-chip-fix
     * -20261030 §四），首发解析为空时经自家面补发一发再判空。
     */
    private static <T> void postFilter(JsonObject bodyObj,
                                       java.util.function.Function<String, T> parse,
                                       java.util.function.Predicate<T> isEmpty,
                                       HgCallback<T> cb) {
        postPlayer(HgDevice.PATH_DISTRIBUTION_FILTER, bodyObj, new Sink() {
            @Override
            public void body(String text) {
                T v;
                try {
                    v = parse.apply(text);
                } catch (Exception e) {
                    cb.onError(-2, "回包解析失败");
                    return;
                }
                if (v == null) {
                    cb.onError(-2, "回包解析失败");
                } else if (isEmpty.test(v)) {
                    ownPost(HgDevice.PATH_DISTRIBUTION_FILTER, bodyObj, sinkOf(cb, parse));
                } else {
                    cb.onSuccess(v);
                }
            }

            @Override
            public void fail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** 筛选面面板（维度表）：genre＝short_play 真人/comic_series 漫剧/ai_series AI剧。 */
    public static void fetchFilterPanel(String genre, HgCallback<List<DramaFilterRow>> cb) {
        postFilter(filterPanelBody(genre), DramaParser::parseFilterPanel,
                rows -> rows.isEmpty(), cb);
    }

    /**
     * 筛选面内容（单选一维一值）：offset/sessionId/seenIds 游标由调用方
     * 持有递进（filter_ids 排除已展示，页间零重叠为服务端语义）。
     */
    public static void fetchFilterList(String genre, String dimKey, String dimValue,
                                       int offset, String sessionId, List<String> seenIds,
                                       HgCallback<DramaCellPage> cb) {
        postFilter(filterContentBody(genre, dimKey, dimValue, offset, sessionId, seenIds),
                DramaParser::parseFilterList,
                page -> offset == 0 && page.getItems().isEmpty(), cb);
    }

    // ---------- 搜索 ----------

    /** 搜索结果页签（tabType：11 综合/29 剧集/30 视频/1 小说/27 用户）。 */
    public static void searchTab(String keyword, int offset, int tabType,
                                 HgCallback<DramaCellPage> cb) {
        getBrowse("/reading/bookapi/search/tab/v", HgRequests.searchPairs(keyword, offset, tabType), true,
                sinkOf(cb, text -> DramaParser.parseCellPage(text, tabType)));
    }

    /** 按名反查作者（作者 uid 兜底/搜索用户签共用）。 */
    public static void searchUsers(String keyword, HgCallback<List<DramaAuthorHit>> cb) {
        getBrowse("/reading/bookapi/search/tab/v", HgRequests.searchPairs(keyword, 0, 27), true,
                sinkOf(cb, DramaParser::parseUserTab));
    }

    /** 作者作品流（评论栈 user/profile 口，tab_type=11 作品签，cursor 翻页）。 */
    public static void fetchAuthorWorks(String userId, String cursor, HgCallback<DramaWork.Page> cb) {
        if (userId == null || userId.isEmpty()) {
            cb.onError(-1, "user_id 为空");
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("comment_source", 1506);
        payload.addProperty("count", 18);
        if (cursor != null && !cursor.isEmpty()) payload.addProperty("cursor", cursor);
        payload.addProperty("sort", "time_desc");
        payload.addProperty("tab_type", 11);
        payload.addProperty("target_user_id", userId);
        DramaCommentTransport.post("user/profile/" + userId, payload.toString(),
                new DramaCommentTransport.Sink() {
                    @Override
                    public void onText(String text) {
                        DramaWork.Page page;
                        try {
                            page = DramaParser.parseAuthorWorks(text);
                        } catch (Exception e) {
                            cb.onError(-2, "作品回包解析失败");
                            return;
                        }
                        if (page == null) cb.onError(-2, "作品回包解析失败");
                        else cb.onSuccess(page);
                    }

                    @Override
                    public void onFail(int code, String msg) {
                        cb.onError(code, msg);
                    }
                });
    }

    /** 作者剧集架（bookshelf/video/info 签名 GET，offset 翻页）。 */
    public static void fetchAuthorShelf(String userId, int offset, HgCallback<DramaWork.Page> cb) {
        if (userId == null || userId.isEmpty()) {
            cb.onError(-1, "user_id 为空");
            return;
        }
        List<String[]> pairs = new ArrayList<>(HgDevice.deviceQueryPairs());
        pairs.add(new String[]{"target_user_id", userId});
        pairs.add(new String[]{"count", "18"});
        pairs.add(new String[]{"offset", String.valueOf(Math.max(0, offset))});
        getBrowse("/reading/bookapi/bookshelf/video/info/v1/", pairs, false,
                sinkOf(cb, DramaParser::parseAuthorShelf));
    }

    /** 作者资料（作者页头部）。 */
    public static void fetchUserProfile(String userId, HgCallback<DramaUserProfile> cb) {
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[]{"user_id", userId});
        getBrowse("/reading/user/profile/get/", pairs, false,
                sinkOf(cb, DramaParser::parseUserProfile));
    }

}
