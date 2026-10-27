package com.fongmi.android.tv.hongguo.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 红果请求构造：请求体与查询串，照 verify-one-episode-20261027.md 第八节第 1 条
 * （smali 摸全的官方构造）与 lib_hg_lib.js 的 hgApiOnce 口径。
 *
 * <p>要点：mixed_video_id_map 是 Map&lt;Short, List&lt;String&gt;&gt;，键为平台值
 * Mixed=1024；biz_param.video_platform 同为 1024；content_type=1004；
 * dr_scene 开时走 V2 请求体（biz_param / dr_scene / mixed_video_id_map /
 * NovelCommonParam，无 content_type 与 video_id）。</p>
 *
 * <p>设备指纹口径（海阔线实测）：同一指纹连发会被限速，每次请求生成全新指纹。</p>
 */
public final class HgRequests {

    public static final int VIDEO_PLATFORM_MIXED = 1024;
    public static final int VIDEO_PLATFORM_OUTER = 3;
    public static final int CONTENT_TYPE = 1004;

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

    /** GetVideoBizParam（第八节抄录字段，video_platform 由入参定）。 */
    public static JsonObject bizParam(int videoPlatform) {
        JsonObject b = new JsonObject();
        b.addProperty("detail_page_version", 0);
        b.addProperty("device_level", 3);
        b.addProperty("disable_digg_stat", false);
        b.addProperty("disable_video_relate_book", false);
        b.addProperty("from_video_id", "");
        b.addProperty("need_all_video_definition", true);
        b.addProperty("need_mp4_align", false);
        b.addProperty("use_os_player", false);
        b.addProperty("use_server_dns", true);
        b.addProperty("use_server_dns_scene", "cold_start_stage");
        b.addProperty("video_platform", videoPlatform);
        return b;
    }

    /**
     * NovelCommonParam：官方约 25 字段（设备/应用公共参数）。
     * 本类用有序表承载，默认带应用与设备身份字段；其余字段由上层按需 put，
     * 不写死猜测值。字段清单未在存档材料里逐字定版，见交付回报的未定项。
     */
    public static final class NovelCommonParam {
        private final LinkedHashMap<String, Object> fields = new LinkedHashMap<>();

        public NovelCommonParam() {
            fields.put("aid", 8662);
            fields.put("app_name", "novelread");
            fields.put("version_code", 73932);
            fields.put("version_name", "7.3.9.32");
            fields.put("device_platform", "android");
            fields.put("os", "android");
            fields.put("language", "zh");
            fields.put("channel", "update_64");
        }

        public NovelCommonParam put(String key, Object value) {
            fields.put(key, value);
            return this;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Number) o.addProperty(e.getKey(), (Number) v);
                else if (v instanceof Boolean) o.addProperty(e.getKey(), (Boolean) v);
                else if (v != null) o.addProperty(e.getKey(), String.valueOf(v));
            }
            return o;
        }
    }

    /** mixed_video_id_map：{ "1024": [集id...] }（键为 Short 平台值的字符串形态）。 */
    public static JsonObject mixedVideoIdMap(Collection<String> videoIds, int platform) {
        JsonArray ids = new JsonArray();
        for (String id : videoIds) ids.add(id);
        JsonObject map = new JsonObject();
        map.add(String.valueOf(platform), ids);
        return map;
    }

    /** 老口请求体（GetVideoModelRequest）：批量取模型。 */
    public static JsonObject multiVideoModelBody(Collection<String> videoIds, NovelCommonParam common) {
        JsonObject body = new JsonObject();
        body.add("biz_param", bizParam(VIDEO_PLATFORM_MIXED));
        body.add("mixed_video_id_map", mixedVideoIdMap(videoIds, VIDEO_PLATFORM_MIXED));
        if (common != null) body.add("NovelCommonParam", common.toJson());
        body.addProperty("content_type", CONTENT_TYPE);
        if (videoIds.size() == 1) body.addProperty("video_id", videoIds.iterator().next());
        return body;
    }

    /** V2 请求体（MGetVideoModelV2Request，dr_scene 开时）：无 content_type / video_id。 */
    public static JsonObject multiVideoModelV2Body(Collection<String> videoIds, String drScene, NovelCommonParam common) {
        JsonObject body = new JsonObject();
        body.add("biz_param", bizParam(VIDEO_PLATFORM_MIXED));
        body.addProperty("dr_scene", drScene == null || drScene.isEmpty() ? "default" : drScene);
        body.add("mixed_video_id_map", mixedVideoIdMap(videoIds, VIDEO_PLATFORM_MIXED));
        if (common != null) body.add("NovelCommonParam", common.toJson());
        return body;
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

    /** 单集 video_model 请求体（Outer=3，海阔线实证口径，作批量失败时的单集对照路）。 */
    public static JsonObject singleVideoModelBody(String videoId) {
        JsonObject body = new JsonObject();
        body.add("biz_param", bizParam(VIDEO_PLATFORM_OUTER));
        body.addProperty("content_type", CONTENT_TYPE);
        body.addProperty("video_id", videoId);
        return body;
    }
}
