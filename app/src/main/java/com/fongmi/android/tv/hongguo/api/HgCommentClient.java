package com.fongmi.android.tv.hongguo.api;

import com.fongmi.android.tv.api.FqCommentSigner;
import com.fongmi.android.tv.hongguo.model.HgComment;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 红果 fqnovel 面客户端（hongguo 包自有实现）：评论读三口＋视频点赞 do_action＋发弹幕/发单集评论 comment/add。
 *
 * <p>为什么不直接用 api/HongguoCommentApi：那份是海阔规则年代与 HongguoDanmakuApi
 * 同层共建的壳子抓取器，挂在点播播放链上、类与传输都是包内共用形态；原生刷剧面
 * 若直连它，播放页就依赖规则年代的类与它的包内共用传输（post 是包内可见、设备号
 * 与签名口径绑在那份实现上），后续任一侧改动都会互相牵连。故在本包内按同一份
 * 已实证协议（hongguo-comments-shell-design.md 双证锁定，请求体逐字段照那份在产
 * 实现）重写一份：签名器 FqCommentSigner 是树里共用的纯算法类，直接复用；
 * 设备号沿用同一 Prefers 键，与那份抓取器共用同一设备身份。</p>
 *
 * <p>异步口径同 HgApiClient：OkHttp 分发线程执行与解析，回调不在主线程。</p>
 */
public final class HgCommentClient {

    public static final String HOST = "https://api5-normal-sinfonlineb.fqnovel.com";
    private static final String UA = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static volatile String deviceId;
    private static volatile String iid;
    private volatile HgAuthProvider authProvider;

    public void setAuthProvider(HgAuthProvider provider) {
        this.authProvider = provider;
    }

    // ---------- 请求体（逐字段照在产 HongguoCommentApi 的已实证口径） ----------

    public static JsonObject episodeListPayload(String seriesId, String vid, String cursor) {
        JsonObject payload = new JsonObject();
        payload.addProperty("comment_source", 4);
        payload.addProperty("server_channel", 18);
        payload.addProperty("group_id", vid);
        payload.addProperty("group_type", 30);
        payload.addProperty("comment_type", 4);
        payload.addProperty("sort", 1);
        payload.addProperty("need_count", true);
        payload.addProperty("count", 20);
        payload.addProperty("cursor", cursor == null ? "" : cursor);
        payload.addProperty("aid", 8662);
        payload.addProperty("compliance_status", 0);
        JsonObject business = new JsonObject();
        business.addProperty("book_id", seriesId);
        business.addProperty("req_type", 0);
        payload.add("business_param", business);
        return payload;
    }

    public static JsonObject seriesListPayload(String seriesId, String cursor) {
        JsonObject payload = new JsonObject();
        JsonObject business = new JsonObject();
        business.addProperty("book_id", seriesId);
        business.addProperty("need_count", true);
        business.addProperty("real_level", 2);
        business.addProperty("req_type", 0);
        payload.add("business_param", business);
        payload.addProperty("comment_source", 1);
        payload.addProperty("comment_type", 2);
        payload.addProperty("count", 10);
        payload.addProperty("group_id", seriesId);
        payload.addProperty("group_type", 1);
        payload.addProperty("server_channel", 34);
        payload.addProperty("sort", 1);
        if (cursor != null && !cursor.isEmpty()) payload.addProperty("cursor", cursor);
        return payload;
    }

    public static JsonObject episodeRepliesPayload(String seriesId, String vid, String commentId, String cursor) {
        JsonObject payload = new JsonObject();
        payload.addProperty("aid", 8662);
        JsonObject business = new JsonObject();
        business.addProperty("book_id", seriesId);
        business.addProperty("need_count", false);
        payload.add("business_param", business);
        payload.addProperty("comment_id", commentId);
        payload.addProperty("comment_source", 504);
        payload.addProperty("comment_type", 4);
        payload.addProperty("compliance_status", 0);
        payload.addProperty("count", 10);
        payload.addProperty("cursor", cursor == null ? "" : cursor);
        payload.addProperty("group_id", vid);
        payload.addProperty("group_type", 30);
        payload.addProperty("server_channel", 18);
        return payload;
    }

    public static JsonObject seriesRepliesPayload(String seriesId, String commentId, String cursor) {
        JsonObject payload = new JsonObject();
        JsonObject business = new JsonObject();
        business.addProperty("book_id", seriesId);
        business.addProperty("need_count", cursor == null || cursor.isEmpty());
        business.addProperty("real_level", 2);
        payload.add("business_param", business);
        payload.addProperty("comment_id", commentId);
        payload.addProperty("comment_source", 501);
        payload.addProperty("comment_type", 2);
        payload.addProperty("count", 10);
        payload.addProperty("group_id", seriesId);
        payload.addProperty("group_type", 1);
        payload.addProperty("server_channel", 34);
        if (cursor != null && !cursor.isEmpty()) payload.addProperty("cursor", cursor);
        return payload;
    }

    // ---------- 评论读 ----------

    /** 单集评论一页。cursor 首页传空串，翻页传上一页 Page.nextCursor 原样回传。 */
    public void episodeComments(String seriesId, String vid, String cursor, HgCallback<HgComment.Page> cb) {
        if (!isId(seriesId) || !isId(vid)) {
            cb.onError(-1, "id 非法");
            return;
        }
        String cur = cursor == null ? "" : cursor;
        post("comment/list/" + vid, episodeListPayload(seriesId, vid, cur), 4, 18, new TextCallback() {
            @Override
            void onText(String text) {
                HgComment.Page page = HgCommentParser.parseList(text, seriesId, vid, cur, false);
                if (page == null) cb.onError(-2, "评论回包解析失败");
                else cb.onSuccess(page);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /**
     * 弹幕档请求体（挖掘账 danmaku-dig-20261028.md 定版）：与单集评论同端点，
     * 换弹幕档枚举（source 601／channel 1000／type 20）＋时间窗三件套
     * （start_offset_time＝播放位置毫秒、playlet_item_duration＝本集时长毫秒、
     * need_danmaku_guide_type 全档）；end_offset_time 不传、服务端切 30 秒窗。
     */
    public static JsonObject danmakuPayload(String seriesId, String vid, long startOffsetMs,
                                            long durationMs, String cursor) {
        JsonObject payload = new JsonObject();
        payload.addProperty("comment_source", 601);
        payload.addProperty("server_channel", 1000);
        payload.addProperty("group_id", vid);
        payload.addProperty("group_type", 30);
        payload.addProperty("comment_type", 20);
        payload.addProperty("sort", 1);
        payload.addProperty("count", 90);
        payload.addProperty("cursor", cursor == null ? "" : cursor);
        payload.addProperty("aid", 8662);
        payload.addProperty("compliance_status", 0);
        JsonObject business = new JsonObject();
        business.addProperty("book_id", seriesId);
        business.addProperty("start_offset_time", Math.max(0, startOffsetMs));
        if (durationMs > 0) business.addProperty("playlet_item_duration", durationMs);
        com.google.gson.JsonArray guides = new com.google.gson.JsonArray();
        guides.add(1);
        guides.add(3);
        guides.add(4);
        guides.add(2);
        business.add("need_danmaku_guide_type", guides);
        payload.add("business_param", business);
        return payload;
    }

    /** 弹幕一窗：startOffsetMs 为本窗起点（窗界由服务端定），cursor 续拉原样回传。 */
    public void episodeDanmaku(String seriesId, String vid, long startOffsetMs, long durationMs,
                               String cursor, HgCallback<com.fongmi.android.tv.hongguo.model.HgDanmaku.Window> cb) {
        if (!isId(seriesId) || !isId(vid)) {
            cb.onError(-1, "id 非法");
            return;
        }
        post("comment/list/" + vid, danmakuPayload(seriesId, vid, startOffsetMs, durationMs, cursor), 601, 1000, new TextCallback() {
            @Override
            void onText(String text) {
                com.fongmi.android.tv.hongguo.model.HgDanmaku.Window w = HgCommentParser.parseDanmakuWindow(text);
                if (w == null) cb.onError(-2, "弹幕回包解析失败");
                else cb.onSuccess(w);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /**
     * 发弹幕请求体（挖掘账 dig-comment-danmaku.md §2.4(a) 定版）：与发评论
     * 同端点（comment/add）、同模型（AddCommentRequest），只换弹幕档填值——
     * commit_source＝1500（NovelItemDanmakuAdd）、group_type＝30、
     * data_type＝20、comment_type 不填；business 带 book_id（剧 id）、
     * offset（播放位置毫秒）、ignore_urge_rule＝false 与 shark_param
     * （type＝short_play、aid＝"8662"，aid 在 shark 里是字符串）。
     */
    public static JsonObject danmakuPostPayload(String seriesId, String vid, String text, long offsetMs) {
        JsonObject payload = new JsonObject();
        payload.addProperty("commit_source", 1500);
        payload.addProperty("group_id", vid);
        payload.addProperty("group_type", 30);
        payload.addProperty("data_type", 20);
        payload.addProperty("text", text == null ? "" : text);
        payload.addProperty("aid", 8662);
        JsonObject business = new JsonObject();
        business.addProperty("book_id", seriesId);
        business.addProperty("offset", Math.max(0, offsetMs));
        business.addProperty("ignore_urge_rule", false);
        JsonObject shark = new JsonObject();
        shark.addProperty("type", "short_play");
        shark.addProperty("aid", "8662");
        business.add("shark_param", shark);
        payload.add("business_param", business);
        return payload;
    }

    /**
     * 发一条弹幕 POST /novel/commentapi/comment/add/v1/。回包判定照本文件
     * 既有写操作（do_action）口径：信封 code=0 才算受理（HgCommentParser
     * .writeResult）；解析失败与被拒在 onText 侧以 -2 分文案报，网络/HTTP
     * 失败走 onFail 原样透传 code——两路不混。post 的 source/channel 照
     * do_action 写操作惯例传 0/0（不带 Comment-Source/Server-Channel 头，
     * 见 doArticleAction 的 postRaw 调用）。
     */
    public void publishDanmaku(String seriesId, String vid, String text, long offsetMs,
                               HgCallback<Void> cb) {
        if (!isId(seriesId) || !isId(vid)) {
            cb.onError(-1, "id 非法");
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            cb.onError(-1, "弹幕为空");
            return;
        }
        post("comment/add", danmakuPostPayload(seriesId, vid, text, offsetMs), 0, 0, new TextCallback() {
            @Override
            void onText(String resp) {
                int r = HgCommentParser.writeResult(resp);
                if (r < 0) cb.onError(-2, "发弹幕回包解析失败");
                else if (r == 0) cb.onError(-2, "发弹幕被服务端拒绝");
                else cb.onSuccess(null);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /**
     * 发单集评论请求体（续挖定版 dig-search2-20261028.md §7，构造链逐环
     * 实证）：与发弹幕同端点（comment/add）同模型，换评论档填值——
     * commit_source＝3（NovelItemCommentAdd）、group_id＝vid、group_type
     * ＝30（SeriesVideo）、data_type＝4（NewItem）、comment_type 不填
     * （构造链全程无人赋值、映射器原样透传 null）；business 带 book_id
     * （剧 id）、offset（发表时播放进度毫秒）与 shark_param（与弹幕档
     * 同口径两键）。回复评论（commit_source 9）与剧评（12）是另两条链，
     * 不在此。
     */
    public static JsonObject commentPostPayload(String seriesId, String vid, String text, long offsetMs) {
        JsonObject payload = new JsonObject();
        payload.addProperty("commit_source", 3);
        payload.addProperty("group_id", vid);
        payload.addProperty("group_type", 30);
        payload.addProperty("data_type", 4);
        payload.addProperty("text", text == null ? "" : text);
        payload.addProperty("aid", 8662);
        JsonObject business = new JsonObject();
        business.addProperty("book_id", seriesId);
        business.addProperty("offset", Math.max(0, offsetMs));
        JsonObject shark = new JsonObject();
        shark.addProperty("type", "short_play");
        shark.addProperty("aid", "8662");
        business.add("shark_param", shark);
        payload.add("business_param", business);
        return payload;
    }

    /**
     * 发一条单集评论 POST /novel/commentapi/comment/add/v1/。回包判定与
     * 发弹幕同口径：信封 code=0 才算受理（writeResult 三态），解析失败与
     * 被拒以 -2 分文案报，网络/HTTP 失败走 onFail 原样透传。
     */
    public void publishComment(String seriesId, String vid, String text, long offsetMs,
                               HgCallback<Void> cb) {
        if (!isId(seriesId) || !isId(vid)) {
            cb.onError(-1, "id 非法");
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            cb.onError(-1, "评论为空");
            return;
        }
        post("comment/add", commentPostPayload(seriesId, vid, text, offsetMs), 0, 0, new TextCallback() {
            @Override
            void onText(String resp) {
                int r = HgCommentParser.writeResult(resp);
                if (r < 0) cb.onError(-2, "发评论回包解析失败");
                else if (r == 0) cb.onError(-2, "发评论被服务端拒绝");
                else cb.onSuccess(null);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    // ---------- 参与评分（详情页评分卡） ----------

    /** UgcCommentCommitSourceEnum.NovelPlayletCommentAdd=12（classes11.dex saas 枚举）。 */
    public static final int COMMIT_SOURCE_PLAYLET = 12;
    /** UgcCommentGroupTypeOutter.FakeBook=3（同上）。 */
    public static final int DATA_TYPE_FAKE_BOOK = 3;
    /** UgcNovelCommentType.UserActualComment=0（同上）。 */
    public static final int COMMENT_TYPE_USER_ACTUAL = 0;
    /** 信封 code：用户未登录（2026-10-29 实拉 add/update 两口同码同文案）。 */
    public static final int CODE_NOT_LOGIN = 103006;

    /**
     * 纯打分提交体（不带文字的剧评）：POST /novel/commentapi/comment/add/v1/。
     * 出处：classes2.dex p12/w.java 的 e() 逐字（官方评分卡确认钮链
     * VideoCommentListViewModel.U1 → 此法）——commit_source＝12
     * （NovelPlayletCommentAdd）、group_id＝剧 id、group_type＝1（Book）、
     * data_type＝3（FakeBook）、text 不填、comment_type＝0
     * （UserActualComment）、business{score＝十分制 0..10（半星步进，星数×2）、
     * book_id＝剧 id、shark_param、trans_param{watch_duration＝已看秒数}}。
     * shark_param 官方是运行时反作弊图＋comment_type 键，包内抠不到全表，
     * 照本文件已实证写口径落 type/aid 两键（与发弹幕/发评论同口径，真回包
     * 校准点）。门槛校验在端上（官方 CSSPlayletCommentListView 按
     * can_comment_threshold 与累计观看算剩余分钟），watch_duration 随体
     * 上送服务端。2026-10-29 本机实拉（匿名）：HTTP 200、code=103006
     * 「用户未登录」——形状被受理到业务层、只差登录（回包存
     * probe-score-20261029）。
     */
    public static JsonObject scorePayload(String seriesId, int score, long watchSec) {
        JsonObject payload = new JsonObject();
        payload.addProperty("commit_source", COMMIT_SOURCE_PLAYLET);
        payload.addProperty("group_id", seriesId);
        payload.addProperty("group_type", 1);
        payload.addProperty("data_type", DATA_TYPE_FAKE_BOOK);
        payload.addProperty("comment_type", COMMENT_TYPE_USER_ACTUAL);
        payload.addProperty("aid", 8662);
        JsonObject business = new JsonObject();
        business.addProperty("score", Math.max(0, Math.min(10, score)));
        business.addProperty("book_id", seriesId);
        JsonObject shark = new JsonObject();
        shark.addProperty("type", "short_play");
        shark.addProperty("aid", "8662");
        business.add("shark_param", shark);
        JsonObject trans = new JsonObject();
        trans.addProperty("watch_duration", String.valueOf(Math.max(0, watchSec)));
        business.add("trans_param", trans);
        payload.add("business_param", business);
        return payload;
    }

    /** 提交评分（首次）。成功＝信封 code=0；未登录回 103006 透传给页面拉登录。 */
    public void submitScore(String seriesId, int score, long watchSec, HgCallback<Void> cb) {
        if (!isId(seriesId)) {
            cb.onError(-1, "id 非法");
            return;
        }
        if (score < 1 || score > 10) {
            cb.onError(-1, "请先选择打分等级");
            return;
        }
        postWrite("comment/add", scorePayload(seriesId, score, watchSec), "评分", cb);
    }

    /**
     * 改分体：POST /novel/commentapi/comment/update/v1/。出处：classes2.dex
     * impl/comment/playlet/editor/q1.java 的 c() 逐行（编辑已发评论链）——
     * comment_id＝本人那条带分评论 id（从整剧评论回包 extra.user_comment
     * 取）、commit_source 沿原评论（纯打分评论即 12）、comment_type＝0、
     * group_id/book_id＝剧 id、group_type＝1、data_type 沿原评论（纯打分
     * 即 3 FakeBook）、business{score、book_id、shark_param、trans_param
     * {watch_duration}}。2026-10-29 本机实拉（匿名）：同 add 口径回
     * 103006「用户未登录」，形状受理、只差登录。
     */
    public static JsonObject scoreUpdatePayload(String commentId, String seriesId, int score, long watchSec) {
        JsonObject payload = new JsonObject();
        payload.addProperty("comment_id", commentId);
        payload.addProperty("commit_source", COMMIT_SOURCE_PLAYLET);
        payload.addProperty("comment_type", COMMENT_TYPE_USER_ACTUAL);
        payload.addProperty("group_id", seriesId);
        payload.addProperty("group_type", 1);
        payload.addProperty("data_type", DATA_TYPE_FAKE_BOOK);
        payload.addProperty("aid", 8662);
        JsonObject business = new JsonObject();
        business.addProperty("book_id", seriesId);
        business.addProperty("score", Math.max(0, Math.min(10, score)));
        JsonObject shark = new JsonObject();
        shark.addProperty("type", "short_play");
        shark.addProperty("aid", "8662");
        business.add("shark_param", shark);
        JsonObject trans = new JsonObject();
        trans.addProperty("watch_duration", String.valueOf(Math.max(0, watchSec)));
        business.add("trans_param", trans);
        payload.add("business_param", business);
        return payload;
    }

    /** 改分（已有本人带分评论时）。成功/失败口径同 submitScore。 */
    public void updateScore(String commentId, String seriesId, int score, long watchSec,
                            HgCallback<Void> cb) {
        if (!isId(seriesId) || commentId == null || commentId.isEmpty()) {
            cb.onError(-1, "id 非法");
            return;
        }
        if (score < 1 || score > 10) {
            cb.onError(-1, "请先选择打分等级");
            return;
        }
        postWrite("comment/update", scoreUpdatePayload(commentId, seriesId, score, watchSec), "改分", cb);
    }

    /** 写口共用判定：信封 code=0 受理；被拒把真实 code 透传（103006 页面拉登录）。 */
    private void postWrite(String path, JsonObject payload, String what, HgCallback<Void> cb) {
        post(path, payload, 0, 0, new TextCallback() {
            @Override
            void onText(String resp) {
                int r = HgCommentParser.writeResult(resp);
                if (r > 0) {
                    cb.onSuccess(null);
                    return;
                }
                if (r < 0) {
                    cb.onError(-2, what + "回包解析失败");
                    return;
                }
                int code = HgCommentParser.envelopeCode(resp);
                cb.onError(code != Integer.MIN_VALUE ? code : -2, what + "被服务端拒绝");
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /**
     * 不感兴趣 POST /novel/articleapi/do_action/v1/（体见
     * HgRequests.dislikeSeriesBody 出处注）。2026-10-29 本机实拉：匿名即
     * code=0 受理——此口不设登录门。被拒时透传信封 code。
     */
    public void dislikeSeries(String seriesId, String vid, HgCallback<Void> cb) {
        if (!isId(seriesId)) {
            cb.onError(-1, "id 非法");
            return;
        }
        postRaw("/novel/articleapi/do_action/v1/", HgRequests.dislikeSeriesBody(seriesId, vid), 0, 0, new TextCallback() {
            @Override
            void onText(String resp) {
                int r = HgCommentParser.writeResult(resp);
                if (r > 0) {
                    cb.onSuccess(null);
                    return;
                }
                if (r < 0) {
                    cb.onError(-2, "反馈回包解析失败");
                    return;
                }
                int code = HgCommentParser.envelopeCode(resp);
                cb.onError(code != Integer.MIN_VALUE ? code : -2, "反馈被服务端拒绝");
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** 整剧剧评一页＋评分卡（详情页用：同一回包的 extra 段带红果分/收藏数）。 */
    public static final class SeriesReviews {
        public final HgComment.Page page;
        public final com.fongmi.android.tv.hongguo.model.HgSeriesRating rating;

        public SeriesReviews(HgComment.Page page,
                             com.fongmi.android.tv.hongguo.model.HgSeriesRating rating) {
            this.page = page;
            this.rating = rating;
        }
    }

    public void seriesReviews(String seriesId, HgCallback<SeriesReviews> cb) {
        if (!isId(seriesId)) {
            cb.onError(-1, "id 非法");
            return;
        }
        post("comment/list/" + seriesId, seriesListPayload(seriesId, ""), 1, 34, new TextCallback() {
            @Override
            void onText(String text) {
                HgComment.Page page = HgCommentParser.parseList(text, seriesId, seriesId, "", true);
                if (page == null) cb.onError(-2, "剧评回包解析失败");
                else cb.onSuccess(new SeriesReviews(page, HgCommentParser.parseSeriesRating(text)));
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /**
     * 作者主页作品口一页（官方 CommentApiService.getUserProfileRxJava 定版，
     * 本机实拉三位作者验通——见 probe-author-20261029 存档）：POST
     * /novel/commentapi/user/profile/:target_user_id/v1/，体照官方
     * GetUserProfileRequest 序列化名逐字（comment_source=1506
     * VideoProfilePage、count=18、tab_type 由页签定：5 动态/11 剧作、
     * sub_tab_type 由子档定：全部不带/合集 13/剧评 121、sort 最新
     * time_desc/最热 smart_hot_desc、cursor 整串回传、tab_type=5 时带
     * extra_param{"is_video_tab_comment":"1"}）。此口官方不挂
     * Comment-Source/Server-Channel 头（hv3/v 组参无此二头），故走
     * source=0/channel=0 的 postRaw 同路。
     */
    public void authorProfileList(String uid, int tabType, int subTabType, String sort,
                                  String cursor, HgCallback<com.fongmi.android.tv.hongguo.model.HgAuthorWork.Page> cb) {
        if (!isId(uid)) {
            cb.onError(-1, "uid 非法");
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("comment_source", 1506);
        payload.addProperty("count", 18);
        if (cursor != null && !cursor.isEmpty()) payload.addProperty("cursor", cursor);
        if (tabType == 5) {
            JsonObject extra = new JsonObject();
            extra.addProperty("is_video_tab_comment", "1");
            payload.add("extra_param", extra);
        }
        payload.addProperty("sort", sort == null || sort.isEmpty() ? "time_desc" : sort);
        if (subTabType > 0) payload.addProperty("sub_tab_type", subTabType);
        payload.addProperty("tab_type", tabType);
        payload.addProperty("target_user_id", uid);
        post("user/profile/" + uid, payload, 0, 0, new TextCallback() {
            @Override
            void onText(String text) {
                com.fongmi.android.tv.hongguo.model.HgAuthorWork.Page page =
                        com.fongmi.android.tv.hongguo.model.HgParser.parseAuthorWorks(text);
                if (page == null) cb.onError(-2, "作品回包解析失败");
                else cb.onSuccess(page);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** 整剧剧评一页。 */
    public void seriesComments(String seriesId, String cursor, HgCallback<HgComment.Page> cb) {
        if (!isId(seriesId)) {
            cb.onError(-1, "id 非法");
            return;
        }
        String cur = cursor == null ? "" : cursor;
        post("comment/list/" + seriesId, seriesListPayload(seriesId, cur), 1, 34, new TextCallback() {
            @Override
            void onText(String text) {
                HgComment.Page page = HgCommentParser.parseList(text, seriesId, seriesId, cur, true);
                if (page == null) cb.onError(-2, "评论回包解析失败");
                else cb.onSuccess(page);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** 单集评论的回复一页。 */
    public void episodeReplies(String seriesId, String vid, String commentId, String cursor, HgCallback<HgComment.Page> cb) {
        if (!isId(seriesId) || !isId(vid) || commentId == null || commentId.isEmpty()) {
            cb.onError(-1, "id 非法");
            return;
        }
        String cur = cursor == null ? "" : cursor;
        post("reply/list/" + commentId, episodeRepliesPayload(seriesId, vid, commentId, cur), 504, 18, new TextCallback() {
            @Override
            void onText(String text) {
                HgComment.Page page = HgCommentParser.parseReplies(text, seriesId, vid, cur);
                if (page == null) cb.onError(-2, "回复回包解析失败");
                else cb.onSuccess(page);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    /** 剧评的回复一页。 */
    public void seriesReplies(String seriesId, String commentId, String cursor, HgCallback<HgComment.Page> cb) {
        if (!isId(seriesId) || commentId == null || commentId.isEmpty()) {
            cb.onError(-1, "id 非法");
            return;
        }
        String cur = cursor == null ? "" : cursor;
        post("reply/list/" + commentId, seriesRepliesPayload(seriesId, commentId, cur), 501, 34, new TextCallback() {
            @Override
            void onText(String text) {
                HgComment.Page page = HgCommentParser.parseReplies(text, seriesId, seriesId, cur);
                if (page == null) cb.onError(-2, "回复回包解析失败");
                else cb.onSuccess(page);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    // ---------- 视频点赞 ----------

    /**
     * 视频点赞/取消 POST /novel/articleapi/do_action/v1/（请求体见
     * HgRequests.doArticleActionBody 出处注）。成功回调带服务端新计数
     * （action_cnt_data[vid]，取不到为 -1）；code 非成功算失败。
     * 宿主与签名口径同 commentapi（同属 fqnovel saas ugc 面）——宿主未经
     * 样本单独定版，待真机校准（见四阶段回报未定项）。
     */
    public void doArticleAction(boolean like, String videoId, String seriesId, HgCallback<Long> cb) {
        JsonObject payload = HgRequests.doArticleActionBody(like, videoId, seriesId);
        postRaw("/novel/articleapi/do_action/v1/", payload, 0, 0, new TextCallback() {
            @Override
            void onText(String text) {
                Long count = HgCommentParser.parseActionCount(text, videoId);
                if (count == null) cb.onError(-2, "点赞被服务端拒绝");
                else cb.onSuccess(count);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    // ---------- 关注作者 ----------

    /** UgcActionType（saas/ugc/model <clinit> 定版）：Subscribe=25 / CancelSubscribe=26。 */
    public static final int ACTION_SUBSCRIBE = 25;
    public static final int ACTION_CANCEL_SUBSCRIBE = 26;
    /** UgcActionObjectType（同上）：User=1。 */
    public static final int OBJECT_USER = 1;

    /**
     * 关注作者主路请求体（官方 PugcFollowButton.n() 逐行实证，dig-mine-profile
     * §2.8）：CommentApiService.doAction 的 DoActionRequest——action_type＝
     * Subscribe(25)/CancelSubscribe(26)、object_type＝User(1)、object_id＝
     * 作者 uid、aid＝8662、business_param 只带 shark_param。端点
     * POST /novel/commentapi/comment/do_action/v1/（同服务接口类
     * UgcApiService 旁的 saas CommentApiService 注解逐字，jadx11）。
     * shark_param 官方传 NsCommunityApi.getRequestSharkParam() 的运行时
     * 反作弊图，全表在包内抠不到；本批照本文件已定版的写口径（发弹幕
     * danmakuPostPayload）落 type/aid 两键，待新版首发回包校准。
     */
    public static JsonObject followActionPayload(boolean follow, String authorUid) {
        JsonObject payload = new JsonObject();
        payload.addProperty("action_type", follow ? ACTION_SUBSCRIBE : ACTION_CANCEL_SUBSCRIBE);
        payload.addProperty("object_type", OBJECT_USER);
        payload.addProperty("object_id", authorUid);
        payload.addProperty("aid", 8662);
        JsonObject business = new JsonObject();
        JsonObject shark = new JsonObject();
        shark.addProperty("type", "short_play");
        shark.addProperty("aid", "8662");
        business.add("shark_param", shark);
        payload.add("business_param", business);
        return payload;
    }

    /** 备路承接（follow/ 端点在 bookapi 宿主，走 HgApiClient 的签名传输；
     * 由会话层装配时注入，避免本客户端反向依赖 HgApiClient）。 */
    public interface FollowFallback {
        void follow(boolean follow, String authorUid, HgCallback<Void> cb);
    }

    private volatile FollowFallback followFallback;

    public void setFollowFallback(FollowFallback fallback) {
        this.followFallback = fallback;
    }

    /**
     * 关注/取关作者：主路 commentapi do_action（与点赞同 plumbing），回包
     * 信封 code=0 才算受理（writeResult 三态口径，与官方 DoActionResponse
     * .code==Success 同义）；主路回包非 0（被拒）才转备路 follow/ 端点
     * （HgApiClient.followAuthor，经 {@link FollowFallback} 注入）；
     * 解析失败与网络失败不转备路、原样报错（不许拿备路掩盖主路的真实死因）。
     */
    public void doFollowAction(boolean follow, String authorUid, HgCallback<Void> cb) {
        if (!isId(authorUid)) {
            cb.onError(-1, "作者 id 非法");
            return;
        }
        post("comment/do_action", followActionPayload(follow, authorUid), 0, 0, new TextCallback() {
            @Override
            void onText(String resp) {
                int r = HgCommentParser.writeResult(resp);
                if (r > 0) {
                    cb.onSuccess(null);
                    return;
                }
                if (r < 0) {
                    cb.onError(-2, "关注回包解析失败");
                    return;
                }
                FollowFallback fb = followFallback;
                if (fb == null) {
                    cb.onError(-2, "关注被服务端拒绝");
                    return;
                }
                fb.follow(follow, authorUid, cb);
            }

            @Override
            void onFail(int code, String msg) {
                cb.onError(code, msg);
            }
        });
    }

    // ---------- 弹幕点赞 ----------

    /** UgcActionType（pbrpc 定版）：Agree=8 / CancelAgree=9（社区口「赞」）。 */
    public static final int ACTION_AGREE = 8;
    public static final int ACTION_CANCEL_AGREE = 9;
    /** UgcActionObjectType：Comment=2。 */
    public static final int OBJECT_COMMENT = 2;
    /** UgcCommentGroupTypeOutter：Danmaku=20（弹幕档的 comment_type）。 */
    public static final int COMMENT_TYPE_DANMAKU = 20;
    /** CommentApiERR.RepeatDiggError：官方 g() 把「重复点赞」也算成功。 */
    public static final int CODE_REPEAT_DIGG = 102498;

    /**
     * 弹幕点赞/取消请求体（完工账 danmaku-completion-20261029.md，官方包
     * 一手链：dx1.l.v → kw1.n0.z → r22.e.p → CommentApiService.doAction）：
     * DoActionRequest（k1 SerialName 实证）＝action_type Agree(8)／
     * CancelAgree(9)、object_type Comment(2)、object_id＝弹幕的
     * comment_id、comment_type＝20（弹幕档）、aid＝8662；business_param
     * .shark_param 由 r22.e.j() 装配：base（照本文件既有写口径落
     * type/aid 两键，运行时反作弊全图抠不到、同 follow 口径待校准）＋
     * target＝comment_id、target_text＝弹幕正文、novel_id＝series_id、
     * section_id＝vid、action_source＝"default_digg_source"（尾巴点赞的
     * 官方来源串；正文点赞浮层同走 l.u 同一来源口径）。
     * 端点与关注同：POST /novel/commentapi/comment/do_action/v1/，
     * 写操作头 0/0。
     */
    public static JsonObject danmakuDiggPayload(boolean digg, String commentId, String text,
                                                String seriesId, String videoId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("action_type", digg ? ACTION_AGREE : ACTION_CANCEL_AGREE);
        payload.addProperty("object_type", OBJECT_COMMENT);
        payload.addProperty("object_id", commentId);
        payload.addProperty("comment_type", COMMENT_TYPE_DANMAKU);
        payload.addProperty("aid", 8662);
        JsonObject business = new JsonObject();
        JsonObject shark = new JsonObject();
        shark.addProperty("type", "short_play");
        shark.addProperty("aid", "8662");
        shark.addProperty("target", commentId);
        shark.addProperty("target_text", text == null ? "" : text);
        shark.addProperty("novel_id", seriesId == null ? "" : seriesId);
        shark.addProperty("section_id", videoId == null ? "" : videoId);
        shark.addProperty("action_source", "default_digg_source");
        business.add("shark_param", shark);
        payload.add("business_param", business);
        return payload;
    }

    /**
     * 弹幕点赞/取消：受理判定＝信封 code 0（writeResult 三态）或
     * RepeatDiggError(102498)（官方把重复点赞算成功——本地乐观态可能
     * 与服务端不一致时不许把已赞报成失败）。回包不带新计数（官方从
     * 本地乐观计数对齐），计数由调用方本地维护。
     */
    public void diggDanmaku(boolean digg, String commentId, String text,
                            String seriesId, String videoId, HgCallback<Void> cb) {
        if (!isId(commentId)) {
            cb.onError(-1, "弹幕 id 非法");
            return;
        }
        post("comment/do_action", danmakuDiggPayload(digg, commentId, text, seriesId, videoId), 0, 0,
                new TextCallback() {
                    @Override
                    void onText(String resp) {
                        if (HgCommentParser.writeResult(resp) > 0
                                || HgCommentParser.envelopeCode(resp) == CODE_REPEAT_DIGG) {
                            cb.onSuccess(null);
                            return;
                        }
                        cb.onError(-2, "点赞被服务端拒绝");
                    }

                    @Override
                    void onFail(int code, String msg) {
                        cb.onError(code, msg);
                    }
                });
    }

    // ---------- 传输（照 HongguoCommentApi.post 的签名/设备口径） ----------

    private abstract static class TextCallback {
        abstract void onText(String text);

        abstract void onFail(int code, String msg);
    }

    private void post(String path, JsonObject payload, int source, int channel, TextCallback cb) {
        postRaw("/novel/commentapi/" + path + "/v1/", payload, source, channel, cb);
    }

    private void postRaw(String path, JsonObject payload, int source, int channel, TextCallback cb) {
        long now = System.currentTimeMillis();
        Map<String, String> query = new TreeMap<>();
        query.put("aid", "8662");
        query.put("app_name", "novelread");
        query.put("version_code", "73532");
        query.put("version_name", "7.3.5.32");
        query.put("manifest_version_code", "73532");
        query.put("update_version_code", "73532");
        query.put("channel", "update_64");
        query.put("device_platform", "android");
        query.put("os", "android");
        query.put("ssmix", "a");
        query.put("device_type", "25053RT47C");
        query.put("device_brand", "Redmi");
        query.put("language", "zh");
        query.put("os_api", "36");
        query.put("os_version", "16");
        query.put("resolution", "1280*2772");
        query.put("dpi", "520");
        query.put("ac", "wifi");
        query.put("device_id", deviceId());
        query.put("iid", iid());
        query.put("_rticket", String.valueOf(now));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(enc(e.getValue()));
        }
        String encoded = sb.toString();
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "application/json");
        headers.put("X-XS-From-Web", "0");
        headers.put("Sdk-Version", "2");
        if (source > 0) headers.put("Comment-Source", String.valueOf(source));
        if (channel > 0) headers.put("Server-Channel", String.valueOf(channel));
        headers.putAll(FqCommentSigner.headers(encoded));
        HgAuthProvider auth = authProvider;
        if (auth != null) {
            Map<String, String> extra = auth.headers();
            if (extra != null) headers.putAll(extra);
            String cookie = auth.cookie();
            if (cookie != null && !cookie.isEmpty()) headers.put("Cookie", cookie);
        }
        Call call = OkHttp.newCall(HOST + path + "?" + encoded, headers, RequestBody.create(JSON, payload.toString()));
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
                try (Response r = response) {
                    ResponseBody rb = r.body();
                    String text = rb == null ? "" : rb.string();
                    if (!r.isSuccessful()) cb.onFail(r.code(), "HTTP " + r.code());
                    else if (text.isEmpty()) cb.onFail(r.code(), "回包空体");
                    else cb.onText(text);
                } catch (Exception e) {
                    cb.onFail(-1, e.getMessage());
                }
            }
        });
    }

    static boolean isId(String s) {
        if (s == null || s.length() < 6 || s.length() > 30) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    // ---------- 设备号（与规则年代抓取器共用同一 Prefers 键＝同一设备身份） ----------

    private static String deviceId() {
        if (deviceId != null) return deviceId;
        synchronized (HgCommentClient.class) {
            if (deviceId == null) deviceId = loadOrCreate("fq_comment_device_id", "fq.comment.device_id");
            return deviceId;
        }
    }

    private static String iid() {
        if (iid != null) return iid;
        synchronized (HgCommentClient.class) {
            if (iid == null) iid = loadOrCreate("fq_comment_iid", "fq.comment.iid");
            return iid;
        }
    }

    private static String loadOrCreate(String key, String prop) {
        String fromProp = System.getProperty(prop);
        if (fromProp != null && isId(fromProp)) return fromProp;
        try {
            String saved = Prefers.getString(key);
            if (isId(saved)) return saved;
        } catch (Throwable ignored) {
        }
        String created = randomDeviceNumber();
        try {
            Prefers.put(key, created);
        } catch (Throwable ignored) {
        }
        return created;
    }

    private static String randomDeviceNumber() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(19);
        sb.append(7);
        for (int i = 0; i < 18; i++) sb.append(random.nextInt(10));
        return sb.toString();
    }
}
