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
        }

        System.out.println(fail == 0 ? "ALL PASS" : ("FAILURES=" + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
