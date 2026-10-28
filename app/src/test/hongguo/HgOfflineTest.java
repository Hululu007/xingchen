import com.fongmi.android.tv.hongguo.crypto.HgUrlCrypto;
import com.fongmi.android.tv.hongguo.crypto.SpadeKey;
import com.fongmi.android.tv.hongguo.model.HgParser;
import com.fongmi.android.tv.hongguo.model.HgVideoEntry;
import com.fongmi.android.tv.hongguo.model.HgVideoModel;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

public class HgOfflineTest {
    static int fail = 0;
    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (detail == null ? "" : " | " + detail));
        if (!ok) fail++;
    }

    public static void main(String[] args) throws Exception {
        String dir = "/home/hatch/workspace/hongguo/";
        String fplay = Files.readString(Paths.get(dir + "fplay_resp.json"));
        String multi = Files.readString(Paths.get(dir + "api_resp_multi_20261027.json"));
        String expectUrl = Files.readString(Paths.get(dir + "fresh_360p_url.txt")).trim();

        // 1) spade 自带示例
        SpadeKey.Result ex = SpadeKey.derive("k7wd9VaMBsF9iy3zfL03xniLMfJ7iAf2ersG6leiHtxikyqIiA==");
        check("spade 示例", ex.isOk() && "234c96c49a973c672b2cdfd6d9800eb9".equals(ex.key),
                "期望=234c96c49a973c672b2cdfd6d9800eb9 实际=" + ex.key + " err=" + ex.error);

        // 2) fplay 解析 + spade 全档 + URL 解密
        HgVideoModel fm = HgParser.parseFplay(fplay);
        check("fplay 解析非空", fm != null, null);
        check("fplay 档位数=5", fm != null && fm.getEntries().size() == 5,
                "实际=" + (fm == null ? -1 : fm.getEntries().size()));
        check("fplay 时长=176.567", fm != null && Math.abs(fm.getDurationSec() - 176.567) < 1e-9,
                "实际=" + (fm == null ? null : fm.getDurationSec()));
        check("fplay poster 非空", fm != null && fm.getPosterUrl() != null && fm.getPosterUrl().startsWith("http"),
                "实际=" + (fm == null ? null : fm.getPosterUrl()));
        check("fplay key_seed 非空", fm != null && fm.getKeySeed() != null, "实际=" + (fm == null ? null : fm.getKeySeed()));
        boolean allKid = true, allDek = true;
        if (fm != null) for (HgVideoEntry e : fm.getEntries()) {
            if (!"6aca2329476d5a232f8215710003ebeb".equals(e.getKid())) allKid = false;
            if (!"048c86766a0b2fd10a7af719b157ff19".equals(e.deriveDekHex())) allDek = false;
            System.out.println("  fplay 档 " + e.getDefinition() + " hash=" + e.getFileHash()
                    + " size=" + e.getSize() + " dek=" + e.deriveDekHex() + " enc=" + e.isUrlEncrypted());
        }
        check("fplay 全档 kid", allKid, null);
        check("fplay 全档 DEK=048c86766a0b2fd10a7af719b157ff19", allDek, null);

        HgVideoEntry e360 = fm == null ? null : fm.entryByDefinition("360p");
        check("fplay 360p file_hash=d0973b53dc14ffee5bd5380f431fee1f",
                e360 != null && "d0973b53dc14ffee5bd5380f431fee1f".equals(e360.getFileHash()),
                "实际=" + (e360 == null ? null : e360.getFileHash()));
        String plain = e360 == null ? null : HgUrlCrypto.decryptUrl(e360.getMainUrl(), fm.getKeySeed());
        check("URL 解密 360p 与 fresh_360p_url.txt 逐字全等", expectUrl.equals(plain),
                plain == null ? "解密为 null" : (expectUrl.equals(plain) ? "len=" + plain.length()
                        : "期望len=" + expectUrl.length() + " 实际len=" + plain.length() + " 实际头=" + plain.substring(0, Math.min(80, plain.length()))));
        // 其余档也应解出 http 链
        boolean allPlain = true;
        if (fm != null) for (HgVideoEntry e : fm.getEntries()) {
            String p = HgUrlCrypto.decryptUrl(e.getMainUrl(), fm.getKeySeed());
            if (p == null || !p.startsWith("https://")) allPlain = false;
        }
        check("fplay 全档 main_url 可解密为 https 链", allPlain, null);

        // 3) multi 解析 + spade 全档
        Map<String, HgVideoModel> mm = HgParser.parseMultiVideoModel(multi);
        check("multi 解析 1 集", mm.size() == 1, "实际=" + mm.size());
        HgVideoModel m0 = mm.get("7694897951721655320");
        check("multi 集 7694897951721655320 存在", m0 != null, null);
        if (m0 != null) {
            check("multi 档位数=5", m0.getEntries().size() == 5, "实际=" + m0.getEntries().size());
            check("multi 时长=176.567", Math.abs(m0.getDurationSec() - 176.567) < 1e-9, "实际=" + m0.getDurationSec());
            check("multi fallback_api 非空", m0.getFallbackApi() != null && m0.getFallbackApi().startsWith("https://vas-"),
                    "实际=" + m0.getFallbackApi());
            boolean mk = true, md = true, mh = true;
            for (HgVideoEntry e : m0.getEntries()) {
                if (!"6aca2329476d5a232f8215710003ebeb".equals(e.getKid())) mk = false;
                if (!"048c86766a0b2fd10a7af719b157ff19".equals(e.deriveDekHex())) md = false;
                if (e.getFileHash() == null || e.getFileHash().length() != 32) mh = false;
                System.out.println("  multi 档 " + e.getDefinition() + " hash=" + e.getFileHash()
                        + " codec=" + e.getCodecType() + " enc=" + e.isUrlEncrypted());
            }
            check("multi 全档 kid", mk, null);
            check("multi 全档 DEK", md, null);
            check("multi 全档 file_hash 齐", mh, null);
            HgVideoEntry m360 = m0.entryByDefinition("360p");
            check("multi 360p file_hash 与 fplay 同集一致",
                    m360 != null && "d0973b53dc14ffee5bd5380f431fee1f".equals(m360.getFileHash()),
                    "实际=" + (m360 == null ? null : m360.getFileHash()));
        }

        // 4) 请求构造形状（openhongguo 口径，定罪账 openhongguo-dig-20261028 §3/§4：
        //    旧 multi 体 platform=1024/content_type=1004 已废弃，取址三体逐字对齐）
        com.google.gson.JsonObject body = com.fongmi.android.tv.hongguo.api.HgRequests.playbackBody(
                "7694897951721655320");
        com.google.gson.JsonObject pbiz = body.getAsJsonObject("biz_param");
        check("playback 体 video_id",
                "7694897951721655320".equals(body.get("video_id").getAsString()), "实际=" + body);
        check("playback 体 biz 仅三字段且 platform=3",
                pbiz != null && pbiz.size() == 3 && pbiz.get("video_platform").getAsInt() == 3
                        && pbiz.get("video_id_type").getAsInt() == 0
                        && pbiz.get("need_all_video_definition").getAsBoolean(),
                "实际=" + body);
        check("playback 体无 content_type/mixed_video_id_map",
                !body.has("content_type") && !body.has("mixed_video_id_map"), "实际=" + body);
        com.google.gson.JsonObject sb = com.fongmi.android.tv.hongguo.api.HgRequests.seriesBody("7694653385668037657");
        com.google.gson.JsonObject sbiz = sb.getAsJsonObject("biz_param");
        check("series 体 series_id＋biz 口径（source=4/type=1/single_col）",
                "7694653385668037657".equals(sb.get("series_id").getAsString())
                        && sbiz.get("source").getAsInt() == 4 && sbiz.get("video_id_type").getAsInt() == 1
                        && "single_col".equals(sbiz.get("caller_scene").getAsString()),
                "实际=" + sb);
        com.google.gson.JsonObject bb = com.fongmi.android.tv.hongguo.api.HgRequests.seriesBatchBody(
                java.util.List.of("111", "222"));
        check("batch 体 series_id 逗号拼＋dr_scene=player＋source=7",
                "111,222".equals(bb.get("series_id").getAsString())
                        && "player".equals(bb.get("dr_scene").getAsString())
                        && bb.getAsJsonObject("biz_param").get("source").getAsInt() == 7,
                "实际=" + bb);
        // 5) 单集 video_model 解析（兜底路）：用存档 multi 回包的单集 wrap
        //    重塑成单集回包形状 {data:{video_model,video_width,video_height}}，
        //    数据是真的、只换外层包法，验新解析器吃得对不对。
        {
            com.google.gson.JsonObject mroot = com.google.gson.JsonParser.parseString(multi).getAsJsonObject();
            com.google.gson.JsonObject wrap = mroot.getAsJsonObject("data").getAsJsonObject("7694897951721655320");
            com.google.gson.JsonObject single = new com.google.gson.JsonObject();
            single.add("data", wrap);
            HgVideoModel sm = HgParser.parseSingleVideoModel(single.toString(), "7694897951721655320");
            check("单集解析非空且档位数=5", sm != null && sm.getEntries().size() == 5,
                    "实际=" + (sm == null ? null : sm.getEntries().size()));
            check("单集解析 fallback_api 非空", sm != null && sm.getFallbackApi() != null
                    && sm.getFallbackApi().startsWith("https://vas-"), "实际=" + (sm == null ? null : sm.getFallbackApi()));
            check("单集解析无 data 返回 null",
                    HgParser.parseSingleVideoModel("{\"code\":0}", "x") == null, null);
        }

        // 6) bookmall/tab 真回包（2026-10-27 带 /v1/ 段实拉）解析回归：
        //    刷剧页推荐兜底面就是它，解析必须抽得出剧条目。
        {
            String bookmall = Files.readString(Paths.get(dir + "bookmall_tab_resp.json"));
            com.fongmi.android.tv.hongguo.model.HgCellPage page = HgParser.parseCellPage(bookmall);
            check("bookmall code=0", page.getCode() == 0, "实际=" + page.getCode());
            check("bookmall 抽出剧条目>0", page.getItems() != null && !page.getItems().isEmpty(),
                    "实际=" + (page.getItems() == null ? -1 : page.getItems().size()));
            if (page.getItems() != null && !page.getItems().isEmpty()) {
                System.out.println("  bookmall 首条目 " + page.getItems().get(0).getSeriesId()
                        + " 《" + page.getItems().get(0).getTitle() + "》");
            }
            // category_schema 在真回包里是字符串化 JSON（验收 B1）：解不开 tags 恒空、
            // 系列剧款标签筹永不显——断言至少一条抽出标签且含「灾难」。
            boolean hasTagZainan = false;
            if (page.getItems() != null) {
                for (com.fongmi.android.tv.hongguo.model.HgSeriesItem it : page.getItems()) {
                    if (it.getTags() != null && it.getTags().contains("灾难")) hasTagZainan = true;
                }
            }
            check("bookmall tags 含「灾难」(category_schema 字符串化 JSON)", hasTagZainan,
                    "各条 tags=" + (page.getItems() == null ? null : page.getItems().stream()
                            .map(it -> String.valueOf(it.getTags())).toList()));
        }

        // 7) bookmall 频道面真回包（2026-10-28 签名探针实拉存档 tabprobe-20261028/）：
        //    parseDiscoverTab 三频道各抽到真条目＋续页凭证在；parseCellChange
        //    续页能追加且游标推进。条目数钉探针实测值（开页 推荐4/漫剧3/真人剧3、
        //    续页 推荐9/漫剧6/真人剧6；2026-10-28 逐项重对修订：video_data 条目
        //    的短片指针在 highlight.vid、单元格本体短片在 video_detail.video_list
        //    首条，两处此前漏接——条目曾全是剧头且推荐开页少一条本体短片）。
        {
            String probeDir = dir + "tabprobe-20261028/";
            String[][] chans = {{"chan_recommend.json", "16", "4", "9"}, {"chan_comic.json", "36", "3", "6"}, {"chan_live.json", "39", "3", "6"}};
            for (String[] c : chans) {
                String raw = Files.readString(Paths.get(probeDir + c[0]));
                com.fongmi.android.tv.hongguo.model.HgCellPage page =
                        HgParser.parseDiscoverTab(raw, Integer.parseInt(c[1]));
                check(c[0] + " 开页条目=" + c[2], page.getItems().size() == Integer.parseInt(c[2]),
                        "实际=" + page.getItems().size());
                long clips = page.getItems().stream().filter(it -> it.getVid() != null).count();
                check(c[0] + " 开页短片带 vid≥3", clips >= 3, "实际=" + clips);
                check(c[0] + " 续页凭证在", page.getContinuation() != null
                                && page.getContinuation().getCellId() != null
                                && page.getContinuation().getSessionId() != null,
                        "cont=" + page.getContinuation());
                boolean idsOk = page.getItems().stream()
                        .allMatch(it -> it.getSeriesId() != null && it.getTitle() != null);
                check(c[0] + " 条目 id/标题齐", idsOk, null);
                String moreRaw = Files.readString(Paths.get(probeDir + c[0].replace(".json", "_more.json")));
                com.fongmi.android.tv.hongguo.model.HgCellPage more = HgParser.parseCellChange(moreRaw);
                check(c[0] + " 续页条目=" + c[3], more.getItems().size() == Integer.parseInt(c[3]),
                        "实际=" + more.getItems().size());
                check(c[0] + " 续页 hasMore 且 nextOffset=6",
                        more.isHasMore() && "6".equals(more.getNextOffset()),
                        "hasMore=" + more.isHasMore() + " next=" + more.getNextOffset());
            }
            // 频道过滤：同一回包并存四 tab（45/36/39/16），但只有被请求的
            // 频道带 cells，其余是空壳（cell_data 缺失，存档实证）；拿推荐
            // 回包按 36 解析必须 0 条——读错 tab 就会串频道
            String recRaw = Files.readString(Paths.get(probeDir + "chan_recommend.json"));
            com.fongmi.android.tv.hongguo.model.HgCellPage as36 =
                    HgParser.parseDiscoverTab(recRaw, 36);
            check("频道过滤：推荐回包按 36 解析条目=0", as36.getItems().isEmpty(),
                    "实际=" + as36.getItems().size());
            // 评论/点赞计数：位置随频道变（推荐 video_data 顶层只有
            // digged_count；漫剧/真人剧在 video_detail.video_list[0] 两项
            // 齐全）——三频道开页里至少一条两项真计数都解析得到
            boolean counted = false;
            for (String[] c : chans) {
                String raw = Files.readString(Paths.get(probeDir + c[0]));
                com.fongmi.android.tv.hongguo.model.HgCellPage p =
                        HgParser.parseDiscoverTab(raw, Integer.parseInt(c[1]));
                for (com.fongmi.android.tv.hongguo.model.HgSeriesItem it : p.getItems()) {
                    if (it.getCommentCount() > 0 && it.getDiggCount() > 0) counted = true;
                }
            }
            check("频道条目带评论/点赞真计数", counted, null);
            // 作者声明：ai_usage_type=1 → 「内容由AI生成」（详情存档与频道
            // video_detail 两面实证）；此前解析从没读过、声明行是死代码
            String detailRaw = Files.readString(Paths.get(probeDir + "e1_detail.json"));
            com.fongmi.android.tv.hongguo.model.HgSeriesDetail det =
                    HgParser.parseVideoDetail(detailRaw);
            check("详情作者声明在", "内容由AI生成".equals(det.getStatement()),
                    "实际=" + det.getStatement());
            boolean feedStmt = false;
            for (com.fongmi.android.tv.hongguo.model.HgSeriesItem it :
                    HgParser.parseDiscoverTab(recRaw, 16).getItems()) {
                if (it.getStatement() != null) feedStmt = true;
            }
            check("频道条目作者声明在", feedStmt, null);
            // 多季系列：详情存档（空间囤货）带 is_multi_season＋related_album_id，
            // 此前解析不读、系列单永远弹不出（频道面只有专辑 id 没兄弟表，
            // 进剧时靠 fetchAlbum 现拉，见 HgApiClient.fetchAlbum）
            check("详情多季标记在", det.isMultiSeason(), null);
            check("详情专辑 id 在", "7699702143464262680".equals(det.getRelatedAlbumId()),
                    "实际=" + det.getRelatedAlbumId());
            // 专辑回包形状照 openhongguo ParseAlbum 的线上观察：乱序两季按
            // season_index 排、字段齐；已下架 code=101001 回空表
            String albumJson = "{\"code\":0,\"data\":{\"album_data\":{"
                    + "\"album_id_str\":\"7699702143464262680\",\"season_cnt\":2,"
                    + "\"series_list\":["
                    + "{\"series_id_str\":\"222\",\"series_title\":\"乙剧第二季\",\"season_index\":2,\"series_cover\":\"c2\",\"episode_cnt\":20,\"series_status\":1},"
                    + "{\"series_id_str\":\"111\",\"series_title\":\"乙剧第一季\",\"season_index\":1,\"series_cover\":\"c1\",\"episode_cnt\":10,\"series_status\":0}"
                    + "]}}}";
            java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem> seasons =
                    HgParser.parseAlbum(albumJson);
            check("专辑解析两季且按季序", seasons.size() == 2
                            && "111".equals(seasons.get(0).getSeriesId())
                            && "222".equals(seasons.get(1).getSeriesId())
                            && seasons.get(0).getEpisodeCount() == 10,
                    "实际=" + seasons.size());
            check("专辑已下架回空表", HgParser.parseAlbum("{\"code\":101001}").isEmpty(), null);
            // 季行副标题（U5 定版）：sub_title_list 对象数组取 content、
            // 字符串数组直取，两形都进 tags 供系列单副行前两条呈现
            String albumSubJson = "{\"code\":0,\"data\":{\"album_data\":{"
                    + "\"album_id_str\":\"999\",\"season_cnt\":2,"
                    + "\"series_list\":["
                    + "{\"series_id_str\":\"333\",\"series_title\":\"丙剧\",\"season_index\":1,"
                    + "\"sub_title_list\":[{\"content\":\"100.9万人预约\"},{\"content\":\"末世\"},{\"content\":\"重生\"}]},"
                    + "{\"series_id_str\":\"444\",\"series_title\":\"丁剧\",\"season_index\":2,"
                    + "\"series_sub_title_list\":[\"已完结\",\"悬疑\"]}"
                    + "]}}}";
            java.util.List<com.fongmi.android.tv.hongguo.model.HgSeriesItem> subSeasons =
                    HgParser.parseAlbum(albumSubJson);
            check("专辑副标题对象数组抽取", subSeasons.size() == 2
                            && subSeasons.get(0).getTags().size() == 3
                            && "100.9万人预约".equals(subSeasons.get(0).getTags().get(0))
                            && "末世".equals(subSeasons.get(0).getTags().get(1)),
                    "实际=" + (subSeasons.isEmpty() ? "空" : subSeasons.get(0).getTags().toString()));
            check("专辑副标题字符串数组抽取", subSeasons.size() == 2
                            && subSeasons.get(1).getTags().size() == 2
                            && "悬疑".equals(subSeasons.get(1).getTags().get(1)),
                    null);
            // 搜索联想形状照 openhongguo ParseSuggest＋ohg suggest 实跑：
            // query_result_v2 行 {name, keyword, word_type}，word_type=2 的
            // keyword 是剧 id 可直达；富行缺失时回落 query_result 纯词表
            String suggestJson = "{\"code\":0,\"data\":{\"query_result_v2\":["
                    + "{\"name\":\"王妈在顾家当保姆后全家只会搞笑了\",\"keyword\":\"7694653385668037657\",\"word_type\":2},"
                    + "{\"name\":\"王妈\",\"keyword\":\"王妈\",\"word_type\":5}"
                    + "]}}";
            java.util.List<com.fongmi.android.tv.hongguo.model.HgSuggestion> sugs =
                    HgParser.parseSuggest(suggestJson);
            check("联想解析两条", sugs.size() == 2, "实际=" + sugs.size());
            check("联想直达剧判定", sugs.size() == 2 && sugs.get(0).isSeriesLink()
                    && !sugs.get(1).isSeriesLink(), null);
            java.util.List<com.fongmi.android.tv.hongguo.model.HgSuggestion> plainSugs =
                    HgParser.parseSuggest("{\"code\":0,\"data\":{\"query_result\":[\"甲剧\",\"乙剧\"]}}");
            check("联想纯词表回落", plainSugs.size() == 2 && "甲剧".equals(plainSugs.get(0).getText()), null);
        }

        // 14) 弹幕窗解析（2026-10-28 匿名实拉三窗真回包，danmaku-dig-20261028.md）：
        // 首窗 [0,30000) 81 条、offset 全落在窗内、下一窗起点=30000、游标非空；
        // 次窗 [30000,60000) 87 条、offset 下界 ≥30000
        {
            String probeDir = dir + "tabprobe-20261028/";
            String w1raw = Files.readString(Paths.get(probeDir + "resp_full-guide-types.json"));
            com.fongmi.android.tv.hongguo.model.HgDanmaku.Window w1 =
                    com.fongmi.android.tv.hongguo.api.HgCommentParser.parseDanmakuWindow(w1raw);
            check("弹幕首窗解析非空", w1 != null, null);
            check("弹幕首窗 81 条", w1 != null && w1.items.size() == 81,
                    "实际=" + (w1 == null ? -1 : w1.items.size()));
            boolean inWindow = w1 != null;
            if (w1 != null) for (com.fongmi.android.tv.hongguo.model.HgDanmaku dm : w1.items) {
                if (dm.offsetTimeMs < 0 || dm.offsetTimeMs >= 30000 || dm.text == null || dm.text.isEmpty()) inWindow = false;
            }
            check("弹幕首窗 offset 全在 [0,30000) 且正文非空", inWindow, null);
            check("弹幕首窗下一窗起点=30000", w1 != null && w1.nextQueryTimeMs == 30000,
                    "实际=" + (w1 == null ? -1 : w1.nextQueryTimeMs));
            check("弹幕首窗游标非空", w1 != null && w1.cursor != null && w1.cursor.contains("danmaku_count"), null);
            String w2raw = Files.readString(Paths.get(probeDir + "resp_mid-window-30s.json"));
            com.fongmi.android.tv.hongguo.model.HgDanmaku.Window w2 =
                    com.fongmi.android.tv.hongguo.api.HgCommentParser.parseDanmakuWindow(w2raw);
            check("弹幕次窗解析非空", w2 != null, null);
            boolean w2ok = w2 != null && !w2.items.isEmpty();
            if (w2 != null) for (com.fongmi.android.tv.hongguo.model.HgDanmaku dm : w2.items) {
                if (dm.offsetTimeMs < 30000 || dm.offsetTimeMs >= 60000) w2ok = false;
            }
            check("弹幕次窗 offset 全在 [30000,60000)", w2ok,
                    "条数=" + (w2 == null ? -1 : w2.items.size()));
            com.google.gson.JsonObject dp = com.fongmi.android.tv.hongguo.api.HgCommentClient
                    .danmakuPayload("7697561772433099800", "7697606592786598936", 30000, 82000, "cur");
            check("弹幕档 payload 枚举与窗参", dp.get("comment_source").getAsInt() == 601
                            && dp.get("server_channel").getAsInt() == 1000
                            && dp.get("comment_type").getAsInt() == 20
                            && dp.get("count").getAsInt() == 90
                            && dp.getAsJsonObject("business_param").get("start_offset_time").getAsLong() == 30000
                            && dp.getAsJsonObject("business_param").get("playlet_item_duration").getAsLong() == 82000
                            && dp.getAsJsonObject("business_param").getAsJsonArray("need_danmaku_guide_type").size() == 4,
                    null);
        }

        // 15) 账号资料解析（account-info-dig-20261028.md 字段表造形样本——
        // 非真回包：真样本待新版首登抓一发对；本节只验解析口径：passport
        // 信封、screen_name/avatar_url 优先、media 回落、全空判 null）
        {
            String shape = "{\"message\":\"success\",\"data\":{"
                    + "\"screen_name\":\"测试昵称\",\"name\":\"备用名\","
                    + "\"avatar_url\":\"https://example.com/a.png\","
                    + "\"user_id\":\"12345\",\"sec_user_id\":\"MS4wLjABAAAA\"}}";
            com.fongmi.android.tv.hongguo.model.HgAccountProfile prof =
                    HgParser.parseAccountInfo(shape);
            check("资料解析 screen_name/avatar/user_id", prof != null
                            && "测试昵称".equals(prof.displayName)
                            && "https://example.com/a.png".equals(prof.avatarUrl)
                            && "12345".equals(prof.userId),
                    "实际=" + (prof == null ? null : prof.displayName));
            String mediaOnly = "{\"message\":\"success\",\"data\":{"
                    + "\"name\":\"只有名字\",\"media\":{\"avatar_url\":\"https://example.com/m.png\"}}}";
            com.fongmi.android.tv.hongguo.model.HgAccountProfile prof2 =
                    HgParser.parseAccountInfo(mediaOnly);
            check("资料解析 name/media 回落", prof2 != null
                            && "只有名字".equals(prof2.displayName)
                            && "https://example.com/m.png".equals(prof2.avatarUrl),
                    null);
            check("资料全空判 null",
                    HgParser.parseAccountInfo("{\"message\":\"success\",\"data\":{}}") == null
                            && HgParser.parseAccountInfo("{\"message\":\"error\"}") == null
                            && HgParser.parseAccountInfo("not json") == null,
                    null);
        }

        // 16) 搜索结果页签（e1_search 真回包）：search_tabs 服务端下发 7 页签，
        //     只收视频面三页签（11 综合/29 剧集/30 视频）且顺序照下发；
        //     综合条目仍抽得出（回归不破）
        {
            String probeDir = dir + "tabprobe-20261028/";
            String raw = Files.readString(Paths.get(probeDir + "e1_search.json"));
            com.fongmi.android.tv.hongguo.model.HgCellPage page = HgParser.parseCellPage(raw);
            java.util.List<com.fongmi.android.tv.hongguo.model.HgSearchTab> tabs = page.getTabs();
            check("搜索页签抽出 3 个视频面页签", tabs.size() == 3, "实际=" + tabs.size());
            check("搜索页签顺序与标题", tabs.size() == 3
                            && tabs.get(0).type == 11 && "综合".equals(tabs.get(0).title)
                            && tabs.get(1).type == 29 && "剧集".equals(tabs.get(1).title)
                            && tabs.get(2).type == 30 && "视频".equals(tabs.get(2).title),
                    "实际=" + tabs.size());
            check("搜索综合条目仍抽出>0", page.getItems() != null && !page.getItems().isEmpty(),
                    "实际=" + (page.getItems() == null ? -1 : page.getItems().size()));
        }

        // 17) 发弹幕请求体（dig-comment-danmaku.md §2.4(a) 定版）：与发评论
        //     同口（comment/add）换弹幕档——commit_source 1500、group_type 30、
        //     data_type 20、comment_type 缺省；business 三件（book_id/offset/
        //     ignore_urge_rule=false）＋shark 两件（type short_play、aid 字符串
        //     "8662"）。另验写回包三态判定（受理/被拒/解析失败）。
        {
            com.google.gson.JsonObject pp = com.fongmi.android.tv.hongguo.api.HgCommentClient
                    .danmakuPostPayload("7697561772433099800", "7697606592786598936", "前排", 12345);
            com.google.gson.JsonObject biz = pp.getAsJsonObject("business_param");
            com.google.gson.JsonObject shark = biz.getAsJsonObject("shark_param");
            check("发弹幕 payload 档位与归属", pp.get("commit_source").getAsInt() == 1500
                            && pp.get("group_type").getAsInt() == 30
                            && pp.get("data_type").getAsInt() == 20
                            && pp.get("aid").getAsInt() == 8662
                            && "7697606592786598936".equals(pp.get("group_id").getAsString())
                            && "前排".equals(pp.get("text").getAsString()),
                    null);
            check("发弹幕 payload comment_type 缺省", !pp.has("comment_type"), null);
            check("发弹幕 payload business 三件", "7697561772433099800".equals(biz.get("book_id").getAsString())
                            && biz.get("offset").getAsLong() == 12345
                            && !biz.get("ignore_urge_rule").getAsBoolean(),
                    null);
            check("发弹幕 payload shark 两件", "short_play".equals(shark.get("type").getAsString())
                            && "8662".equals(shark.get("aid").getAsString()),
                    null);
            check("写回包三态判定", com.fongmi.android.tv.hongguo.api.HgCommentParser
                            .writeResult("{\"code\":0,\"message\":\"success\"}") == 1
                            && com.fongmi.android.tv.hongguo.api.HgCommentParser
                            .writeResult("{\"code\":1001,\"message\":\"no\"}") == 0
                            && com.fongmi.android.tv.hongguo.api.HgCommentParser
                            .writeResult("not json") == -1
                            && com.fongmi.android.tv.hongguo.api.HgCommentParser
                            .writeResult("") == -1,
                    null);
        }

        // 18) 剧条文案（dig-seriessdk-20261028 §2 定版，官方 catalogview
        //     y/l 拼法）：前缀三档（选集/合集/电影，串 e4_/eeq/eer）；
        //     标题段——已完结「剧名 · 已完结 · 全N集」、未完结
        //     「剧名 · 全N集」、电影「剧名 · 共X分钟」（分钟＝秒/60 上取整）。
        //     旧「类目＋剧名＋全N集＋已完结」序作废：类目不进条、完结段在
        //     全N集之前。条目级验 fromSeries 产物（类目串不得再漏进条）。
        {
            check("剧条前缀三档", "选集".equals(com.fongmi.android.tv.hongguo.ui.HgFeedItem.catalogPrefix(false, false))
                            && "合集".equals(com.fongmi.android.tv.hongguo.ui.HgFeedItem.catalogPrefix(true, false))
                            && "电影".equals(com.fongmi.android.tv.hongguo.ui.HgFeedItem.catalogPrefix(false, true)),
                    null);
            check("剧条标题段·未完结", "十年恩断 · 全100集".equals(
                            com.fongmi.android.tv.hongguo.ui.HgFeedItem.catalogTitleText("十年恩断", false, 100, 0)),
                    null);
            check("剧条标题段·已完结", "十年恩断 · 已完结 · 全100集".equals(
                            com.fongmi.android.tv.hongguo.ui.HgFeedItem.catalogTitleText("十年恩断", true, 100, 0)),
                    null);
            check("剧条标题段·电影", "某片 · 共128分钟".equals(
                            com.fongmi.android.tv.hongguo.ui.HgFeedItem.catalogTitleText("某片", false, -1, 128)),
                    null);
            check("剧条电影分钟上取整", com.fongmi.android.tv.hongguo.ui.HgFeedItem.movieMinutes(60) == 1
                            && com.fongmi.android.tv.hongguo.ui.HgFeedItem.movieMinutes(61) == 2
                            && com.fongmi.android.tv.hongguo.ui.HgFeedItem.movieMinutes(176) == 3
                            && com.fongmi.android.tv.hongguo.ui.HgFeedItem.movieMinutes(0) == 0,
                    null);
            check("剧条标题段·集数未知不编全0集", "某剧 · 已完结".equals(
                            com.fongmi.android.tv.hongguo.ui.HgFeedItem.catalogTitleText("某剧", true, -1, 0))
                            && "某剧".equals(com.fongmi.android.tv.hongguo.ui.HgFeedItem
                            .catalogTitleText("某剧", false, -1, 0)),
                    null);
            com.fongmi.android.tv.hongguo.model.HgSeriesItem s =
                    com.fongmi.android.tv.hongguo.model.HgSeriesItem
                            .of("s1", "十年恩断", null, null, 100, false, false, null)
                            .withSeriesStatus(1);
            String bar = com.fongmi.android.tv.hongguo.ui.HgFeedItem.fromSeries(s, "漫剧").seriesBarText();
            check("剧条条目级·完结全串", "选集 十年恩断 · 已完结 · 全100集".equals(bar), "实际=" + bar);
            com.fongmi.android.tv.hongguo.model.HgSeriesItem s2 =
                    com.fongmi.android.tv.hongguo.model.HgSeriesItem
                            .of("s2", "在播剧", null, null, 24, false, false, null);
            String bar2 = com.fongmi.android.tv.hongguo.ui.HgFeedItem.fromSeries(s2, "真人剧").seriesBarText();
            check("剧条条目级·未完结全串", "选集 在播剧 · 全24集".equals(bar2), "实际=" + bar2);
        }

        // 19) 长按与五项面板纯逻辑（HgPanelLogic）：中间带判定（官方 F6
        //     居中带、带宽比 1/3）、倍速档表（他实拍六档＋标签＋回落）、
        //     清晰度档序（高→低＋纯数字 P 档名）、定时状态文案与延迟。
        {
            Class<?> L = com.fongmi.android.tv.hongguo.ui.HgPanelLogic.class;
            check("面板类可加载", L != null, null);
            // 中间带：W=900 时带＝[300,600]；界内/界外/横屏分工在 Activity
            // 侧（横屏不问带），这里只验带函数本身与无效宽回落带外
            check("中间带·带心在内", com.fongmi.android.tv.hongguo.ui.HgPanelLogic.inCenterBand(450, 900), null);
            check("中间带·左界在内", com.fongmi.android.tv.hongguo.ui.HgPanelLogic.inCenterBand(300, 900), null);
            check("中间带·右界在内", com.fongmi.android.tv.hongguo.ui.HgPanelLogic.inCenterBand(600, 900), null);
            check("中间带·带外左侧", !com.fongmi.android.tv.hongguo.ui.HgPanelLogic.inCenterBand(299, 900), null);
            check("中间带·带外右侧", !com.fongmi.android.tv.hongguo.ui.HgPanelLogic.inCenterBand(601, 900), null);
            check("中间带·无效宽判带外", !com.fongmi.android.tv.hongguo.ui.HgPanelLogic.inCenterBand(10, 0), null);
            // 倍速档表（他实拍 0.75/1/1.25/1.5/2/3，不是 dig 本地缺省表）
            float[] sp = com.fongmi.android.tv.hongguo.ui.HgPanelLogic.SPEEDS;
            check("倍速档表六档", sp.length == 6 && sp[0] == 0.75f && sp[1] == 1f && sp[2] == 1.25f
                    && sp[3] == 1.5f && sp[4] == 2f && sp[5] == 3f, null);
            check("倍速标签", "0.75x".equals(com.fongmi.android.tv.hongguo.ui.HgPanelLogic.speedLabel(0.75f))
                    && "1x".equals(com.fongmi.android.tv.hongguo.ui.HgPanelLogic.speedLabel(1f))
                    && "1.25x".equals(com.fongmi.android.tv.hongguo.ui.HgPanelLogic.speedLabel(1.25f))
                    && "1.5x".equals(com.fongmi.android.tv.hongguo.ui.HgPanelLogic.speedLabel(1.5f))
                    && "2x".equals(com.fongmi.android.tv.hongguo.ui.HgPanelLogic.speedLabel(2f))
                    && "3x".equals(com.fongmi.android.tv.hongguo.ui.HgPanelLogic.speedLabel(3f)), null);
            check("倍速下标与回落", com.fongmi.android.tv.hongguo.ui.HgPanelLogic.speedIndex(3f) == 5
                    && com.fongmi.android.tv.hongguo.ui.HgPanelLogic.speedIndex(0.75f) == 0
                    && com.fongmi.android.tv.hongguo.ui.HgPanelLogic.speedIndex(1.75f) == 1, null);
            check("长按快进临时档=2", com.fongmi.android.tv.hongguo.ui.HgPanelLogic.BOOST_SPEED == 2f, null);
            // 清晰度：档序高→低、未知档跟尾、档名纯数字 P
            java.util.List<String> defs = com.fongmi.android.tv.hongguo.ui.HgPanelLogic.orderedDefinitions(
                    java.util.Arrays.asList("360p", "1080p", "720p", "720p", "2160p"));
            check("清晰度档序高到低", defs.equals(java.util.Arrays.asList("1080p", "720p", "360p", "2160p")),
                    "实际=" + defs);
            check("清晰度档名", "720P".equals(com.fongmi.android.tv.hongguo.ui.HgPanelLogic.definitionLabel("720p"))
                    && "1080P".equals(com.fongmi.android.tv.hongguo.ui.HgPanelLogic.definitionLabel("1080p"))
                    && "".equals(com.fongmi.android.tv.hongguo.ui.HgPanelLogic.definitionLabel(null)), null);
            // 定时：状态文案四档＋延迟（播完本剧/关闭不计时）
            check("定时文案", "关闭".equals(com.fongmi.android.tv.hongguo.ui.HgPanelLogic.timerStateText(
                            com.fongmi.android.tv.hongguo.ui.HgPanelLogic.TIMER_OFF, 0))
                    && "播完本剧".equals(com.fongmi.android.tv.hongguo.ui.HgPanelLogic.timerStateText(
                            com.fongmi.android.tv.hongguo.ui.HgPanelLogic.TIMER_SERIES_END, 0))
                    && "30分钟".equals(com.fongmi.android.tv.hongguo.ui.HgPanelLogic.timerStateText(
                            com.fongmi.android.tv.hongguo.ui.HgPanelLogic.TIMER_FIXED, 30))
                    && "45分钟".equals(com.fongmi.android.tv.hongguo.ui.HgPanelLogic.timerStateText(
                            com.fongmi.android.tv.hongguo.ui.HgPanelLogic.TIMER_CUSTOM, 45)), null);
            check("定时延迟", com.fongmi.android.tv.hongguo.ui.HgPanelLogic.timerDelayMs(
                            com.fongmi.android.tv.hongguo.ui.HgPanelLogic.TIMER_FIXED, 30) == 30 * 60_000L
                    && com.fongmi.android.tv.hongguo.ui.HgPanelLogic.timerDelayMs(
                            com.fongmi.android.tv.hongguo.ui.HgPanelLogic.TIMER_CUSTOM, 45) == 45 * 60_000L
                    && com.fongmi.android.tv.hongguo.ui.HgPanelLogic.timerDelayMs(
                            com.fongmi.android.tv.hongguo.ui.HgPanelLogic.TIMER_SERIES_END, 0) == -1
                    && com.fongmi.android.tv.hongguo.ui.HgPanelLogic.timerDelayMs(
                            com.fongmi.android.tv.hongguo.ui.HgPanelLogic.TIMER_OFF, 0) == -1, null);
        }

        // 19) 关注作者（fix-author-page-20261028 账；dig-mine-profile §2.8／
        //     dig-mine2 §4 定版）：主路 do_action 体逐字段、备路 FollowRequest
        //     体逐字段、cell 抬升作者 uid（chan_recommend 真回包：陈景枫条目
        //     ugc_user_info.user_id＝731637651867048，jadx11 源码侧同值复核）。
        {
            com.google.gson.JsonObject fp = com.fongmi.android.tv.hongguo.api.HgCommentClient
                    .followActionPayload(true, "731637651867048");
            check("关注主路·关注型", fp.get("action_type").getAsInt() == 25
                            && fp.get("object_type").getAsInt() == 1
                            && "731637651867048".equals(fp.get("object_id").getAsString())
                            && fp.get("aid").getAsInt() == 8662,
                    fp.toString());
            com.google.gson.JsonObject fbp = fp.getAsJsonObject("business_param");
            com.google.gson.JsonObject fshark = fbp == null ? null : fbp.getAsJsonObject("shark_param");
            check("关注主路·shark两键", fshark != null
                            && "short_play".equals(fshark.get("type").getAsString())
                            && "8662".equals(fshark.get("aid").getAsString()),
                    String.valueOf(fshark));
            com.google.gson.JsonObject fp2 = com.fongmi.android.tv.hongguo.api.HgCommentClient
                    .followActionPayload(false, "731637651867048");
            check("关注主路·取关型", fp2.get("action_type").getAsInt() == 26, fp2.toString());
            com.google.gson.JsonObject rb = com.fongmi.android.tv.hongguo.api.HgRequests
                    .followRequestBody(true, "731637651867048");
            check("关注备路·四字段", rb.get("action_type").getAsInt() == 1
                            && "731637651867048".equals(rb.get("relative_id").getAsString())
                            && rb.get("relative_type").getAsInt() == 3
                            && !rb.has("user_id") && !rb.has("relative_id_list")
                            && !rb.has("is_cancelled") && !rb.has("follow_source"),
                    rb.toString());
            com.google.gson.JsonObject rb2 = com.fongmi.android.tv.hongguo.api.HgRequests
                    .followRequestBody(false, "731637651867048");
            check("关注备路·取关型", rb2.get("action_type").getAsInt() == 0, rb2.toString());
            com.fongmi.android.tv.hongguo.model.HgCellPage chanPage = HgParser.parseCellPage(
                    Files.readString(Paths.get(dir + "tabprobe-20261028/chan_recommend.json")));
            com.fongmi.android.tv.hongguo.model.HgSeriesItem chen = null;
            for (com.fongmi.android.tv.hongguo.model.HgSeriesItem it : chanPage.getItems()) {
                if ("陈景枫".equals(it.getAuthorName())) chen = it;
            }
            check("作者uid抬升·陈景枫", chen != null
                            && "731637651867048".equals(chen.getAuthorUid()),
                    chen == null ? "条目没找到" : "uid=" + chen.getAuthorUid());
        }

        System.out.println(fail == 0 ? "ALL PASS" : ("FAILURES=" + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
