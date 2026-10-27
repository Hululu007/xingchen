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

        // 4) 请求构造形状（第八节口径）
        com.google.gson.JsonObject body = com.fongmi.android.tv.hongguo.api.HgRequests.multiVideoModelBody(
                java.util.List.of("7694897951721655320", "7694897951721655321"),
                new com.fongmi.android.tv.hongguo.api.HgRequests.NovelCommonParam());
        com.google.gson.JsonObject mp = body.getAsJsonObject("mixed_video_id_map");
        check("请求体 mixed_video_id_map 键=1024 且含两集",
                mp != null && mp.has("1024") && mp.getAsJsonArray("1024").size() == 2,
                "实际=" + body);
        check("请求体 biz_param.video_platform=1024",
                body.getAsJsonObject("biz_param").get("video_platform").getAsInt() == 1024, null);
        check("请求体 content_type=1004", body.get("content_type").getAsInt() == 1004, null);
        com.google.gson.JsonObject v2 = com.fongmi.android.tv.hongguo.api.HgRequests.multiVideoModelV2Body(
                java.util.List.of("7694897951721655320"), "default",
                new com.fongmi.android.tv.hongguo.api.HgRequests.NovelCommonParam());
        check("V2 请求体带 dr_scene 且无 content_type/video_id",
                "default".equals(v2.get("dr_scene").getAsString()) && !v2.has("content_type") && !v2.has("video_id"),
                "实际=" + v2);
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

        System.out.println(fail == 0 ? "ALL PASS" : ("FAILURES=" + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
