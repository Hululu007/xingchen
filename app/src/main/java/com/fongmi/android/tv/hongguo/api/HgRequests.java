package com.fongmi.android.tv.hongguo.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 红果请求构造（请求体部分）：取址链三体照 openhongguo internal/protocol/request.go
 * 的 PlaybackBody / SeriesBody / SeriesBatchBody 逐字口径（Go map 序列化＝键字典序，
 * 本类按同序插入 Gson 对象，签名覆盖 body MD5、发送字节与签名体一致）。
 *
 * <p>旧 multi 口径（mixed_video_id_map＋platform=1024＋content_type=1004）已在
 * openhongguo-dig-20261028.md 定罪废弃：无签名＋随机假指纹恒回 200 空体；
 * 取址链改走 HgDevice 注册身份＋HgSign 签名（见 HgApiClient.postSigned）。
 * freshQuery 仅剩登录/书架/订阅等非取址链老口在用，取址链禁用。</p>
 */
public final class HgRequests {

    public static final int VIDEO_PLATFORM_FQ = 3;

    public static final String UA =
            "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 2509FPN0BC; Build/UQ1A.240205.09211156;tt-ok/3.12.13.20)";

    private static final String QS_TAIL =
            "&ac=wifi&channel=update_64&aid=8662&app_name=novelread&version_code=73932" +
            "&version_name=7.3.9.32&device_platform=android&os=android&ssmix=a" +
            "&device_type=2509FPN0BC&device_brand=XIAOMI&language=zh&os_api=34&os_version=14" +
            "&manifest_version_code=73932&resolution=1080*1920&dpi=480&update_version_code=73932" +
            "&host_abi=arm64-v8a&dragon_device_type=phone&pv_player=73932&compliance_status=0" +
            "&need_personal_recommend=1&player_so_load=1&is_android_pad_screen=0" +
            "&rom_version=UQ1A.240205.09211156+release-keys";

    private static final SecureRandom RANDOM = new SecureRandom();

    private HgRequests() {
    }

    /** 每次请求一套全新指纹查询串（iid / device_id / _rticket / cdid + 固定尾）。 */
    public static String freshQuery() {
        return "iid=" + rndDigits(15) + "&device_id=" + rndDigits(15)
                + "&_rticket=" + System.currentTimeMillis() + QS_TAIL
                + "&cdid=" + UUID.randomUUID();
    }

    private static String rndDigits(int n) {
        StringBuilder sb = new StringBuilder(n);
        sb.append(1 + RANDOM.nextInt(9));
        for (int i = 1; i < n; i++) sb.append(RANDOM.nextInt(10));
        return sb.toString();
    }

    /** 单集 video_model 请求体（openhongguo PlaybackBody）：biz 仅三字段、platform=3。 */
    public static JsonObject playbackBody(String videoId) {
        JsonObject biz = new JsonObject();
        biz.addProperty("need_all_video_definition", true);
        biz.addProperty("video_id_type", 0);
        biz.addProperty("video_platform", VIDEO_PLATFORM_FQ);
        JsonObject body = new JsonObject();
        body.add("biz_param", biz);
        body.addProperty("video_id", videoId);
        return body;
    }

    /** 单集详情 video_detail 请求体（openhongguo SeriesBody）。 */
    public static JsonObject seriesBody(String seriesId) {
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

    /** 批量详情 multi_video_detail 请求体（openhongguo SeriesBatchBody，series_id 逗号拼）。 */
    public static JsonObject seriesBatchBody(Collection<String> seriesIds) {
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

    // ---------- bookmall 频道面（feed 三页签，openhongguo request.go 逐字口径） ----------

    /** 频道开页请求 tab_type：推荐走 -1（响应频道 16）；漫剧 36 / 真人剧 39 请求响应同值。 */
    public static final int TAB_REQUEST_RECOMMEND = -1;
    public static final int TAB_RESPONSE_RECOMMEND = 16;
    public static final int TAB_COMIC = 36;
    public static final int TAB_LIVE = 39;
    /** 短剧商城底栏（bottom_tab_type，三频道同值 7）。 */
    public static final String BOTTOM_TAB_SHORT_DRAMA = "7";
    public static final String BOTTOM_TAB_TYPE_LIST = "7,0,1,2,3,4";

    /**
     * 频道开页有序参数（openhongguo SignedDiscoverPairsIn 逐字）：
     * 设备公共参（31 对）＋ tab_type ＋ offset=0 ＋ client_req_type=3 ＋
     * bottom_tab_type ＋ bottom_tab_type_list。ts / _rticket 由签名器末尾追加。
     */
    public static java.util.List<String[]> discoverPairs(int requestTabType) {
        java.util.List<String[]> pairs = HgDevice.deviceQueryPairs();
        pairs.add(new String[]{"tab_type", String.valueOf(requestTabType)});
        pairs.add(new String[]{"offset", "0"});
        pairs.add(new String[]{"client_req_type", "3"});
        pairs.add(new String[]{"bottom_tab_type", BOTTOM_TAB_SHORT_DRAMA});
        pairs.add(new String[]{"bottom_tab_type_list", BOTTOM_TAB_TYPE_LIST});
        return pairs;
    }

    /**
     * 频道续页有序参数（openhongguo SignedCellChangePairs 逐字）：
     * 设备公共参 ＋ cell_id ＋ offset ＋ limit=20 ＋ change_type=0 ＋ tab_type
     * （响应频道值）＋ show_type ＋ client_req_type=2 ＋ session_id；
     * client_template 仅在开页回包带了才回传。
     */
    public static java.util.List<String[]> cellChangePairs(
            com.fongmi.android.tv.hongguo.model.HgContinuation cont) {
        java.util.List<String[]> pairs = HgDevice.deviceQueryPairs();
        pairs.add(new String[]{"cell_id", cont.getCellId()});
        pairs.add(new String[]{"offset", cont.getOffset()});
        pairs.add(new String[]{"limit", "20"});
        pairs.add(new String[]{"change_type", "0"});
        pairs.add(new String[]{"tab_type", cont.getTabType()});
        pairs.add(new String[]{"show_type", cont.getShowType()});
        pairs.add(new String[]{"client_req_type", "2"});
        pairs.add(new String[]{"session_id", cont.getSessionId()});
        if (cont.getClientTemplate() != null && !cont.getClientTemplate().isEmpty()) {
            pairs.add(new String[]{"client_template", cont.getClientTemplate()});
        }
        return pairs;
    }

    /**
     * 搜索结果有序参数（openhongguo SignedSearchPairs 逐字，tab=短剧 11）：
     * 设备公共参 ＋ query（参数名是 query 不是 keyword——旧 App 侧 keyword
     * 写法从未定版）＋ tab_type/count/offset ＋ use_correct ＋
     * bookshelf_search_plan ＋ search_source=1 ＋ tab_name=feed ＋
     * bookstore_tab=16 ＋ user_is_login=0。本机探针实证：此形签名 GET 回
     * 63,856 字节真结果；旧无签名＋假指纹＋keyword 形回 200 空体（与取模型
     * 口同一道签名门，2026-10-28 A/B 实证）。
     */
    public static java.util.List<String[]> searchPairs(String keyword) {
        java.util.List<String[]> pairs = HgDevice.deviceQueryPairs();
        pairs.add(new String[]{"query", keyword == null ? "" : keyword});
        pairs.add(new String[]{"tab_type", "11"});
        pairs.add(new String[]{"count", "20"});
        pairs.add(new String[]{"offset", "0"});
        pairs.add(new String[]{"use_correct", "true"});
        pairs.add(new String[]{"bookshelf_search_plan", "4"});
        pairs.add(new String[]{"search_source", "1"});
        pairs.add(new String[]{"tab_name", "feed"});
        pairs.add(new String[]{"bookstore_tab", "16"});
        pairs.add(new String[]{"user_is_login", "0"});
        return pairs;
    }

    // ---------- 四阶段互动（样本实证，逐字段出处见各方法注） ----------

    /** 追剧架操作：加入收藏/追剧（AddShelf=0）。 */
    public static final int SHELF_OP_ADD = 0;
    /** 追剧架操作：移出（DelShelf=1）。 */
    public static final int SHELF_OP_DEL = 1;
    /** ReadingBookType.Watch=2（短剧观看类）。 */
    public static final int BOOK_TYPE_WATCH = 2;

    /**
     * 追剧架更新体 POST /reading/bookapi/bookshelf/video/update。
     * 出处：官方样本 classes11.dex 的 rpc 模型 UpdateBookShelfVideoInfoRequest /
     * UpdateBookShelfVideo（@SerializedName 逐字段：update_bookshelf_video_list /
     * book_id / book_type / group_name / modify_time / video_shelf_operate_type /
     * shark_extra），枚举值 VideoShelfOperateType{AddShelf=0, DelShelf=1}、
     * ReadingBookType{Watch=2}；调用点 jadx9 rs4/o1.java 的 q0（FollowScene
     * 加入收藏/追剧：bookId/modifyTime/AddShelf/bookType＋sharkExtra 的
     * is_active_behavior/inactive_type）与 C1（删除走 DelShelf、不带 sharkExtra）。
     * 枚举在字节 rpc 序列化里按 value 出数（模型带 FieldType/value 形迹），
     * 这里出数值；名称形态待真机回包校准（见四阶段回报未定项）。
     */
    public static JsonObject bookshelfVideoUpdateBody(String seriesId, boolean add, long nowMs) {
        JsonObject item = new JsonObject();
        item.addProperty("book_id", seriesId);
        item.addProperty("book_type", BOOK_TYPE_WATCH);
        item.addProperty("group_name", "");
        item.addProperty("modify_time", nowMs);
        item.addProperty("video_shelf_operate_type", add ? SHELF_OP_ADD : SHELF_OP_DEL);
        com.google.gson.JsonArray list = new com.google.gson.JsonArray();
        list.add(item);
        JsonObject body = new JsonObject();
        body.add("update_bookshelf_video_list", list);
        if (add) {
            JsonObject shark = new JsonObject();
            shark.addProperty("is_active_behavior", "true");
            shark.addProperty("inactive_type", "0");
            body.add("shark_extra", shark);
        }
        return body;
    }

    /** UgcActionType.Like=3 / CancelLike=4（classes11.dex rpc 模型 UgcActionType）。 */
    public static final int ACTION_LIKE = 3;
    public static final int ACTION_CANCEL_LIKE = 4;
    /** UgcActionObjectType.Item=6（同上 UgcActionObjectType）。 */
    public static final int OBJECT_ITEM = 6;
    /** SaasUgcActionCategory.Default=1（同上）。 */
    public static final int ACTION_CATEGORY_DEFAULT = 1;

    /**
     * 视频点赞体 POST /novel/articleapi/do_action/v1/（dex 字符串 Retrofit 注解）。
     * 出处：classes11.dex 的 saas 模型 DoArticleActionRequest（@SerializedName：
     * action_type / object_id / object_type / action_category /
     * action_reason_remark / business_param{video_id}，aid 走 QUERY）；
     * 调用点 classes6.dex h14/n0.java 的 d()（VideoLikeUtil：like 时
     * actionType=Like、cancel 时 CancelLike，objectID＝集 vid，
     * objectType＝Item（Book 型内容才是 Book），actionCategory＝Default，
     * actionReasonRemark＝"like_click"，businessParam.videoID＝剧 id）。
     */
    public static JsonObject doArticleActionBody(boolean like, String videoId, String seriesId) {
        JsonObject body = new JsonObject();
        body.addProperty("action_type", like ? ACTION_LIKE : ACTION_CANCEL_LIKE);
        body.addProperty("object_id", videoId);
        body.addProperty("object_type", OBJECT_ITEM);
        body.addProperty("action_category", ACTION_CATEGORY_DEFAULT);
        body.addProperty("action_reason_remark", "like_click");
        JsonObject business = new JsonObject();
        business.addProperty("video_id", seriesId);
        body.add("business_param", business);
        return body;
    }

    // ---------- 预约（未上线剧的到货提醒式预约，样本实证） ----------

    /** 预约 item_type：短剧=14（见 subscribeBody 注的 P() 出处）；小说书=1。 */
    public static final int ITEM_TYPE_SHORT_DRAMA = 14;
    public static final int ITEM_TYPE_BOOK = 1;
    /** SubscribeOpType.Subscribe=1 / CancelSubscribe=2 / Dismiss=3（classes11.dex <clinit> 定版）。 */
    public static final int SUBSCRIBE_OP_SUBSCRIBE = 1;
    public static final int SUBSCRIBE_OP_CANCEL = 2;
    /** SubscribeListTabType.MySubscribed=1（classes11.dex <clinit> 定版）。 */
    public static final int SUBSCRIBE_TAB_MY_SUBSCRIBED = 1;

    /**
     * 预约/取消预约体 POST /reading/bookapi/search/uncover_subscribe。
     * 出处链（国内版 7.2.5.32 样本逐环实证）：
     * <ul>
     *   <li>端点：classes25.dex 的 KMP 读接口 BookApiService.Z＝
     *       searchUncoverSubscribeRx，方法体内 const-string
     *       "$POST /reading/bookapi/search/uncover_subscribe/v:version/"，
     *       SerializeType.JSON，回包 SearchUncoverSubscribeResponse
     *       {code, message, data{is_online, need_popup, need_popup_calendar,
     *       online_schema, popup_style, subscribe_data}}。</li>
     *   <li>请求模型：同 dex 的 t70＝SearchUncoverSubscribeRequest，其序列化器
     *       （t70$a）里的 serial 名逐字为 item_id / item_type / op_type /
     *       item_id_str / wish_list_all_del / item_id_list / shark_param；
     *       构造参序 (itemId:Long, itemType:Integer, opValue:Integer,
     *       itemIdStr:String, wishListAllDel:Integer, itemIdList:List,
     *       sharkParam:Map)。</li>
     *   <li>调用点：classes8.dex 的 SeriesSubscribeHelper（kmp/subscribe/t.e，
     *       日志名 requestSubscribe）组 t70 后调该接口；上层 classes7.dex 的
     *       ne4/r1.E 传入 itemType＝r1.P(item)：item 的类目字段 j==1007（短剧）
     *       时 14、否则 1。</li>
     *   <li>op_type 出数值：KMP 模型里存的是 SubscribeOpType.getValue() 整数
     *       （Subscribe=1、CancelSubscribe=2，classes11.dex 枚举 <clinit> 定版）。</li>
     * </ul>
     * shark_param 官方传一份反作弊公共参表（kmp/t.j()），本机抠不到内容，
     * 不编造——先不带；wish_list_all_del / item_id_list 单剧预约用不上，不带。
     * 真机回包若拒缺字段，再按回包补（见交付回报未定项）。
     */
    public static JsonObject searchUncoverSubscribeBody(String seriesId, boolean subscribe) {
        JsonObject body = new JsonObject();
        try {
            body.addProperty("item_id", Long.parseLong(seriesId));
        } catch (Exception e) {
            body.addProperty("item_id", seriesId);
        }
        body.addProperty("item_type", ITEM_TYPE_SHORT_DRAMA);
        body.addProperty("op_type", subscribe ? SUBSCRIBE_OP_SUBSCRIBE : SUBSCRIBE_OP_CANCEL);
        body.addProperty("item_id_str", seriesId);
        return body;
    }

    /**
     * 我的预约列表读参 GET /reading/user/subscribe/list。
     * 出处：classes11.dex rpc 模型 GetUserSubscribeListRequest 的字段名
     * （tab_type / limit / offset / version 等，蛇形键在该 dex 字符串池逐字验过）；
     * tab_type 取 MySubscribed=1。回包 data.subscribe_items[]（UserSubscribeData）。
     */
    public static java.util.Map<String, String> subscribeListParams() {
        java.util.Map<String, String> q = new java.util.HashMap<>();
        q.put("tab_type", String.valueOf(SUBSCRIBE_TAB_MY_SUBSCRIBED));
        q.put("limit", "50");
        q.put("offset", "0");
        return q;
    }

    // ---------- passport 短信登录（样本实证，挖掘账 passport-login-dig.md） ----------

    /**
     * passport 宿主：App 侧配置实现 com.dragon.read.base.w.host() 的 const-string
     * 定版 "security.snssdk.com"（注册点 app.launch.task.s3.k）；路径由
     * zy0/d.c 拼 https:// + host + path。不是 passport.snssdk.com。
     */
    public static final String PASSPORT_HOST = "https://security.snssdk.com";

    /**
     * 发码 type 的登录场景值：passport SDK 内 impl/c.t 的便捷方法硬编码 24；
     * impl/c.r 是 type 透传形态，龙族登录页最终传值未追到底（UI 不走 zy0/a
     * 门面）——先用 24，真机回包校准（见挖掘账未定版第 1 条）。
     */
    public static final int SEND_CODE_TYPE_SMS_LOGIN = 24;

    /**
     * passport 字段混淆 StringUtils.encryptWithXor（classes23.dex 本体逐指令）：
     * UTF-8 字节逐字节 XOR 0x05，再转十六进制串。
     */
    public static String xorHex(String s) {
        if (s == null || s.isEmpty()) return "";
        byte[] bytes;
        try {
            bytes = s.getBytes("UTF-8");
        } catch (Exception e) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = (b ^ 0x05) & 0xFF;
            sb.append(Character.forDigit(v >> 4, 16)).append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 发码表单 POST /passport/mobile/send_code/v1/（xz0/g.H 组参逐字段）：
     * mobile/type 走 xor 混淆、mix_mode=1、check_register=1（查注册态，
     * 登录场景官方口径）；captcha/shark_ticket 等风控回填字段被要求时才带，
     * 首次发码不带。公共参数由客户端另行拼到查询串（与官方网络层
     * NetworkParams.putCommonParams 并入同源一套）。
     */
    public static java.util.Map<String, String> sendCodeForm(String mobile) {
        java.util.Map<String, String> form = new LinkedHashMap<>();
        form.put("mobile", xorHex(mobile));
        form.put("type", xorHex(String.valueOf(SEND_CODE_TYPE_SMS_LOGIN)));
        form.put("mix_mode", "1");
        form.put("check_register", "1");
        return form;
    }

    /** 短信登录表单 POST /passport/mobile/sms_login/（xz0/d.H 组参逐字段）。 */
    public static java.util.Map<String, String> smsLoginForm(String mobile, String code) {
        java.util.Map<String, String> form = new LinkedHashMap<>();
        form.put("mobile", xorHex(mobile));
        form.put("code", xorHex(code));
        form.put("mix_mode", "1");
        return form;
    }

}
