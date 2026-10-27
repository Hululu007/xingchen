import com.fongmi.android.tv.hongguo.api.HgApiClient;
import com.fongmi.android.tv.hongguo.api.HgCommentClient;
import com.fongmi.android.tv.hongguo.api.HgCommentParser;
import com.fongmi.android.tv.hongguo.api.HgRequests;
import com.fongmi.android.tv.hongguo.api.HgPassportClient;
import com.fongmi.android.tv.hongguo.model.HgComment;
import com.fongmi.android.tv.hongguo.model.HgParser;
import com.fongmi.android.tv.hongguo.model.HgSeriesDetail;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 红果四阶段离线单测：互动请求体拼装（逐字段对样本实证值）＋评论回包解析
 * （用树里存档的真回包语料 app/src/test/resources/hongguo-*.json，与
 * HongguoCommentApiTest 同一份样本、同一组 id）。
 */
public class HgInteractionTest {

    static int fail = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (detail == null ? "" : " | " + detail));
        if (!ok) fail++;
    }

    private static final String SERIES = "7695727473111092286";
    private static final String VID = "7695729394496588862";

    public static void main(String[] args) throws Exception {
        // ---------- 追剧架更新体（出处：classes11 rpc 模型 + jadx9 rs4/o1 调用点） ----------
        JsonObject add = HgRequests.bookshelfVideoUpdateBody(SERIES, true, 123456789L);
        JsonObject item = add.getAsJsonArray("update_bookshelf_video_list").get(0).getAsJsonObject();
        check("追剧架加入体 book_id", SERIES.equals(item.get("book_id").getAsString()), item.toString());
        check("追剧架加入体 book_type=2(Watch)", item.get("book_type").getAsInt() == 2, null);
        check("追剧架加入体 op=0(AddShelf)", item.get("video_shelf_operate_type").getAsInt() == 0, null);
        check("追剧架加入体 modify_time 透传", item.get("modify_time").getAsLong() == 123456789L, null);
        check("追剧架加入体带 shark_extra", add.has("shark_extra")
                && "true".equals(add.getAsJsonObject("shark_extra").get("is_active_behavior").getAsString())
                && "0".equals(add.getAsJsonObject("shark_extra").get("inactive_type").getAsString()), add.toString());
        JsonObject del = HgRequests.bookshelfVideoUpdateBody(SERIES, false, 1L);
        JsonObject delItem = del.getAsJsonArray("update_bookshelf_video_list").get(0).getAsJsonObject();
        check("追剧架移出体 op=1(DelShelf) 且不带 shark_extra",
                delItem.get("video_shelf_operate_type").getAsInt() == 1 && !del.has("shark_extra"), del.toString());

        // ---------- 点赞体（出处：classes11 saas 模型 + classes6 h14/n0 调用点） ----------
        JsonObject like = HgRequests.doArticleActionBody(true, VID, SERIES);
        check("点赞体 action_type=3(Like)", like.get("action_type").getAsInt() == 3, like.toString());
        check("点赞体 object_id=集vid", VID.equals(like.get("object_id").getAsString()), null);
        check("点赞体 object_type=6(Item)", like.get("object_type").getAsInt() == 6, null);
        check("点赞体 category=1(Default)", like.get("action_category").getAsInt() == 1, null);
        check("点赞体 remark=like_click", "like_click".equals(like.get("action_reason_remark").getAsString()), null);
        check("点赞体 business video_id=剧id",
                SERIES.equals(like.getAsJsonObject("business_param").get("video_id").getAsString()), null);
        JsonObject unlike = HgRequests.doArticleActionBody(false, VID, SERIES);
        check("取消点赞 action_type=4(CancelLike)", unlike.get("action_type").getAsInt() == 4, null);

        // ---------- 评论请求体（照在产 HongguoCommentApi 已实证口径） ----------
        JsonObject ep = HgCommentClient.episodeListPayload(SERIES, VID, "");
        check("单集评论体 group=vid/type=30/comment_type=4/source=4/channel=18",
                VID.equals(ep.get("group_id").getAsString()) && ep.get("group_type").getAsInt() == 30
                        && ep.get("comment_type").getAsInt() == 4 && ep.get("comment_source").getAsInt() == 4
                        && ep.get("server_channel").getAsInt() == 18
                        && SERIES.equals(ep.getAsJsonObject("business_param").get("book_id").getAsString()),
                ep.toString());
        JsonObject sr = HgCommentClient.seriesListPayload(SERIES, "");
        check("剧评体首页不带 cursor 键", !sr.has("cursor") && sr.get("comment_type").getAsInt() == 2
                && sr.get("group_type").getAsInt() == 1, sr.toString());
        JsonObject sr2 = HgCommentClient.seriesListPayload(SERIES, "abc");
        check("剧评体翻页带 cursor", "abc".equals(sr2.get("cursor").getAsString()), null);

        // ---------- 评论解析（真回包语料） ----------
        String epList = readResource("hongguo-episode-list-p1.json");
        HgComment.Page page = HgCommentParser.parseList(epList, SERIES, VID, "", false);
        check("单集评论真语料解析非空", page != null && !page.items.isEmpty(),
                page == null ? "null" : "items=" + page.items.size());
        check("单集评论真语料 total=472", page != null && page.total == 472,
                page == null ? "null" : "total=" + page.total);
        check("单集评论真语料 hasMore 且游标是 offset=20 的 JSON",
                page != null && page.hasMore && HgCommentParser.validateJsonCursor(page.nextCursor) == 20,
                page == null ? "null" : page.nextCursor);
        boolean anyHttp = false, anyHttps = false, badAvatar = false;
        if (page != null) for (HgComment c : page.items) {
            if (c.avatar.startsWith("http://")) anyHttp = true;
            else if (c.avatar.startsWith("https://")) anyHttps = true;
            else if (!c.avatar.isEmpty()) badAvatar = true;
        }
        check("单集评论头像口径（http/https 放行、无异类）", anyHttp && anyHttps && !badAvatar, null);
        check("单集评论归属回验：换剧 id 整页作废",
                HgCommentParser.parseList(epList, "7695727473111092287", VID, "", false) == null, null);
        check("单集评论游标不前进整页作废",
                HgCommentParser.parseList(epList, SERIES, VID,
                        "{\"session_id\":\"x\",\"offset\":20}", false) == null, null);

        String replies = readResource("hongguo-series-replies-p1.json");
        HgComment.Page rp = HgCommentParser.parseReplies(replies, SERIES, SERIES, "");
        check("剧评回复真语料解析非空", rp != null && !rp.items.isEmpty(),
                rp == null ? "null" : "items=" + rp.items.size());

        // ---------- 点赞回包 / bookapi code 容忍 ----------
        Long cnt = HgCommentParser.parseActionCount(
                "{\"code\":0,\"data\":{\"action_cnt_data\":{\"" + VID + "\":128}}}", VID);
        check("点赞回包计数解析=128", cnt != null && cnt == 128L, String.valueOf(cnt));
        check("点赞回包 code 非 0 作废",
                HgCommentParser.parseActionCount("{\"code\":1001,\"data\":{}}", VID) == null, null);
        check("bookapi code 容忍：0/SUCCESS/缺失成、其它败",
                HgApiClient.isOkCode("{\"code\":0}") && HgApiClient.isOkCode("{\"code\":\"SUCCESS\"}")
                        && HgApiClient.isOkCode("{\"data\":{}}") && !HgApiClient.isOkCode("{\"code\":-135}")
                        && !HgApiClient.isOkCode("not json"), null);

        // ---------- 预约体（出处：classes25 BookApiService.searchUncoverSubscribeRx
        // + t70$a 序列化名 + classes7 ne4/r1.P 的 itemType 分档 + classes11 枚举值） ----------
        JsonObject sub = HgRequests.searchUncoverSubscribeBody(SERIES, true);
        check("预约体 item_id=剧id数值", sub.get("item_id").getAsLong() == Long.parseLong(SERIES), sub.toString());
        check("预约体 item_type=14(短剧)", sub.get("item_type").getAsInt() == 14, null);
        check("预约体 op_type=1(Subscribe)", sub.get("op_type").getAsInt() == 1, null);
        check("预约体 item_id_str=剧id串", SERIES.equals(sub.get("item_id_str").getAsString()), null);
        JsonObject unsub = HgRequests.searchUncoverSubscribeBody(SERIES, false);
        check("取消预约 op_type=2(CancelSubscribe)", unsub.get("op_type").getAsInt() == 2, unsub.toString());
        check("预约列表读参 tab_type=1(MySubscribed)",
                "1".equals(HgRequests.subscribeListParams().get("tab_type")), null);

        // ---------- 预约面解析（Feed.appointment：is_subscribed / due_start_text） ----------
        String cellJson = "{\"code\":0,\"data\":{\"cells\":[{\"series_id\":\"" + SERIES + "\","
                + "\"title\":\"待播剧\",\"episode_count\":0,"
                + "\"appointment\":{\"id\":\"a1\",\"is_subscribed\":true,"
                + "\"due_start_text\":\"8月20日上线\",\"due_start_time\":1755662400}}]}}";
        java.util.List<HgSeriesItem> cellItems = HgParser.parseCellPage(cellJson).getItems();
        check("cell 解析出带 appointment 的条目", cellItems.size() == 1 && cellItems.get(0).hasAppointment(),
                "items=" + cellItems.size());
        check("appointment.is_subscribed 进模型",
                cellItems.size() == 1 && cellItems.get(0).isAppointmentSubscribed(), null);
        check("appointment.due_start_text 进模型",
                cellItems.size() == 1 && "8月20日上线".equals(cellItems.get(0).getDueStartText()), null);
        String plainJson = "{\"code\":0,\"data\":{\"cells\":[{\"series_id\":\"" + SERIES
                + "\",\"title\":\"在播剧\"}]}}";
        java.util.List<HgSeriesItem> plainItems = HgParser.parseCellPage(plainJson).getItems();
        check("无 appointment 的条目 hasAppointment=false",
                plainItems.size() == 1 && !plainItems.get(0).hasAppointment(), null);

        HgSeriesDetail det = HgParser.parseVideoDetail("{\"data\":{\"series_id\":\"" + SERIES + "\","
                + "\"title\":\"待播剧\",\"appointment\":{\"is_subscribed\":false,"
                + "\"due_start_text\":\"即将上线\"}}}");
        check("详情 appointment 进模型",
                det.hasAppointment() && !det.isAppointmentSubscribed()
                        && "即将上线".equals(det.getDueStartText()), null);

        // ---------- 我的预约列表解析（data.subscribe_items 容忍抽取） ----------
        String listJson = "{\"code\":0,\"data\":{\"subscribe_items\":[{\"item\":{"
                + "\"book_id\":\"" + SERIES + "\",\"book_name\":\"预约剧甲\",\"cover\":\"http://x/c.jpg\"}}],"
                + "\"has_more\":false}}";
        java.util.List<HgSeriesItem> mine = HgParser.parseSubscribeList(listJson);
        check("预约列表抽出剧条目", mine.size() == 1 && SERIES.equals(mine.get(0).getSeriesId())
                && "预约剧甲".equals(mine.get(0).getTitle()), "items=" + mine.size());

        // ---------- passport 短信登录（出处：passport-login-dig.md 逐环实证） ----------
        check("xorHex 手机号向量（UTF-8 XOR 0x05 转 hex）",
                "34363d353534363d353535".equals(HgRequests.xorHex("13800138000")),
                HgRequests.xorHex("13800138000"));
        check("xorHex type=24 向量", "3731".equals(HgRequests.xorHex("24")), null);
        check("xorHex 验证码向量", "343736313033".equals(HgRequests.xorHex("123456")), null);
        java.util.Map<String, String> scForm = HgRequests.sendCodeForm("13800138000");
        check("发码表单 mobile=xor/type=xor(24)/mix_mode=1/check_register=1",
                "34363d353534363d353535".equals(scForm.get("mobile"))
                        && "3731".equals(scForm.get("type"))
                        && "1".equals(scForm.get("mix_mode"))
                        && "1".equals(scForm.get("check_register")), scForm.toString());
        java.util.Map<String, String> loginForm = HgRequests.smsLoginForm("13800138000", "123456");
        check("登录表单 mobile/code 均 xor、mix_mode=1",
                "34363d353534363d353535".equals(loginForm.get("mobile"))
                        && "343736313033".equals(loginForm.get("code"))
                        && "1".equals(loginForm.get("mix_mode")), loginForm.toString());
        check("passport 成功回包判错为 null",
                HgPassportClient.passportError("{\"message\":\"success\",\"data\":{\"session_key\":\"sk1\"}}") == null, null);
        check("passport 失败回包取 message",
                "验证码错误".equals(HgPassportClient.passportError(
                        "{\"error_code\":1105,\"message\":\"验证码错误\",\"data\":{}}")), null);
        check("登录 Cookie：session_key 组 sessionid",
                "sessionid=sk1".equals(HgPassportClient.loginCookie(
                        "{\"message\":\"success\",\"data\":{\"session_key\":\"sk1\",\"user_id\":42}}", null)), null);
        check("登录 Cookie：Set-Cookie 优先",
                "sessionid=abc; path=/".equals(HgPassportClient.loginCookie(
                        "{\"message\":\"success\",\"data\":{\"session_key\":\"sk1\"}}", "sessionid=abc; path=/")), null);
        check("登录 Cookie：失败回包为 null",
                HgPassportClient.loginCookie("{\"error_code\":1105,\"message\":\"验证码错误\"}", null) == null, null);

        System.out.println(fail == 0 ? "ALL PASS HgInteractionTest" : "FAILURES=" + fail);
        if (fail > 0) System.exit(1);
    }

    private static String readResource(String name) throws Exception {
        Path p = Paths.get("app/src/test/resources", name);
        if (!Files.exists(p)) p = Paths.get("/home/hatch/workspace/xingchen-full/app/src/test/resources", name);
        return Files.readString(p);
    }
}
