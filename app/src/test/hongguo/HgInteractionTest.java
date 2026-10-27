import com.fongmi.android.tv.hongguo.api.HgApiClient;
import com.fongmi.android.tv.hongguo.api.HgCommentClient;
import com.fongmi.android.tv.hongguo.api.HgCommentParser;
import com.fongmi.android.tv.hongguo.api.HgRequests;
import com.fongmi.android.tv.hongguo.model.HgComment;
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

        System.out.println(fail == 0 ? "ALL PASS HgInteractionTest" : "FAILURES=" + fail);
        if (fail > 0) System.exit(1);
    }

    private static String readResource(String name) throws Exception {
        Path p = Paths.get("app/src/test/resources", name);
        if (!Files.exists(p)) p = Paths.get("/home/hatch/workspace/xingchen-full/app/src/test/resources", name);
        return Files.readString(p);
    }
}
