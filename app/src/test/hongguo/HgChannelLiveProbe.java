import com.fongmi.android.tv.hongguo.api.HgDevice;
import com.fongmi.android.tv.hongguo.api.HgRequests;
import com.fongmi.android.tv.hongguo.model.HgCellPage;
import com.fongmi.android.tv.hongguo.model.HgParser;
import com.fongmi.android.tv.hongguo.model.HgSeriesDetail;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;
import com.fongmi.android.tv.hongguo.model.HgVideoModel;
import com.fongmi.android.tv.hongguo.sign.HgSign;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 频道面实跑探针（不进 run.sh 离线套）：注册匿名设备后，用与 HgApiClient
 * .getSigned 逐字同路的签名 GET 实跑 bookmall/tab 三频道开页＋cell/change
 * 续页，原始回包存档 tabprobe-20261028/chan_*.json；同场做 signGet 签名内
 * 路径 A/B（真实 path 对写死 search/tab path）、关注页签空态核对，以及
 * E1 实证案例《空间囤货！我有四合院末世求生》全链定罪（搜索→详情→模型）。
 * 传输用 curl 子进程（助手 JVM 原始 TCP 被本机权限层拦，同 HgSignLiveProbe）。
 */
public class HgChannelLiveProbe {

    static final Path OUT_DIR = Paths.get(System.getProperty("user.home"),
            "workspace/hongguo/tabprobe-20261028");
    static final String E1_TITLE = "空间囤货！我有四合院末世求生";

    static final class HttpResult {
        int code;
        byte[] body;
        Map<String, String> cookies = new HashMap<>();
    }

    static HttpResult curl(String method, String url, Map<String, String> headers, byte[] body) throws Exception {
        Path dir = Files.createTempDirectory("hgchan");
        List<String> cmd = new ArrayList<>();
        cmd.add("curl");
        cmd.add("-s");
        cmd.add("-X");
        cmd.add(method);
        Path hdrFile = dir.resolve("headers.txt");
        cmd.add("-D");
        cmd.add(hdrFile.toString());
        cmd.add("-o");
        Path bodyFile = dir.resolve("resp.bin");
        cmd.add(bodyFile.toString());
        cmd.add("-w");
        cmd.add("%{http_code}");
        if (body != null) {
            Path reqFile = dir.resolve("req.bin");
            Files.write(reqFile, body);
            cmd.add("--data-binary");
            cmd.add("@" + reqFile);
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            cmd.add("-H");
            cmd.add(e.getKey() + ": " + e.getValue());
        }
        cmd.add(url);
        Process p = new ProcessBuilder(cmd).start();
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            in.transferTo(outBuf);
        }
        if (p.waitFor() != 0) throw new IllegalStateException("curl exit != 0");
        HttpResult r = new HttpResult();
        r.code = Integer.parseInt(outBuf.toString(StandardCharsets.UTF_8).trim());
        r.body = Files.readAllBytes(bodyFile);
        for (String line : Files.readString(hdrFile).split("\n")) {
            if (line.toLowerCase().startsWith("set-cookie:")) {
                String pair = line.substring(line.indexOf(':') + 1).trim();
                int semi = pair.indexOf(';');
                if (semi >= 0) pair = pair.substring(0, semi);
                int eq = pair.indexOf('=');
                if (eq > 0) r.cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return r;
    }

    static Map<String, String> signedHeaders(HgSign.Signed signed, long ticket) {
        Map<String, String> h = new HashMap<>();
        h.put("user-agent", HgDevice.userAgent());
        h.putAll(signed.headers);
        String guest = HgDevice.guestCookieHeader();
        if (guest != null) h.put("Cookie", guest);
        h.put("x-ss-dp", "8662");
        h.put("lc", "101");
        h.put("x-reading-request", HgDevice.readingRequestId(ticket));
        return h;
    }

    /** 与 HgApiClient.getSigned 同路：signGet(真实 path)。signPath 可覆盖（A/B 用）。 */
    static HttpResult signedGet(String path, List<String[]> pairs, String signPath) throws Exception {
        long ticket = System.currentTimeMillis();
        HgSign.Signed signed = HgSign.signGet(signPath == null ? path : signPath, pairs, ticket,
                HgDevice.profile().deviceId, HgDevice.VERSION_NAME, null);
        String url = HgDevice.API_HOST + path + "?" + signed.query;
        return curl("GET", url, signedHeaders(signed, ticket), null);
    }

    static HttpResult signedPost(String path, JsonObject bodyObj) throws Exception {
        String body = bodyObj.toString();
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        long ticket = System.currentTimeMillis();
        HgSign.Signed signed = HgSign.signPost(path, HgDevice.deviceQueryPairs(), ticket,
                HgDevice.profile().deviceId, HgDevice.VERSION_NAME, bodyBytes, null);
        Map<String, String> h = signedHeaders(signed, ticket);
        h.remove("Content-Type");
        String url = HgDevice.API_HOST + path + "?" + signed.query;
        return curl("POST", url, h, bodyBytes);
    }

    static void archive(String name, byte[] body) throws Exception {
        Files.createDirectories(OUT_DIR);
        Files.write(OUT_DIR.resolve(name), body);
    }

    static String text(HttpResult r) {
        return new String(r.body, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        // 1) 设备注册
        long now = System.currentTimeMillis();
        byte[] regBody = HgDevice.buildRegisterBody(now);
        HgDevice.Profile prof = HgDevice.profile();
        Map<String, String> rh = new HashMap<>();
        rh.put("user-agent", HgDevice.userAgent());
        rh.put("Content-Type", "application/octet-stream;tt-data=a");
        rh.put("log-encode-type", "gzip");
        rh.put("sdk-version", "2");
        rh.put("passport-sdk-version", "5051452");
        rh.put("x-vc-bdturing-sdk-version", "4.0.3.cn");
        rh.put("x-ss-dp", "8662");
        rh.put("X-SS-STUB", HgSign.contentMd5Hex(regBody));
        String regUrl = HgDevice.REGISTER_HOST + "/service/2/device_register/?req_id=" + prof.reqId + "&tt_data=a";
        HttpResult reg = curl("POST", regUrl, rh, regBody);
        String[] ids = HgDevice.parseRegister(text(reg));
        System.out.println("register http=" + reg.code + " bytes=" + reg.body.length
                + " ids=" + (ids == null ? "NULL" : "ok"));
        if (ids == null) return;
        HgDevice.saveRegistered(ids[0], ids[1]);
        HgDevice.applyGuestCookies(reg.cookies);

        // 2) signGet A/B：真实 path 签 vs 写死 search/tab path 签（同一开页参数）
        List<String[]> recPairs = HgRequests.discoverPairs(HgRequests.TAB_REQUEST_RECOMMEND);
        HttpResult a = signedGet(HgDevice.PATH_BOOKMALL_TAB, recPairs, null);
        System.out.println("AB-A real-path http=" + a.code + " bytes=" + a.body.length);
        HttpResult b = signedGet(HgDevice.PATH_BOOKMALL_TAB, HgRequests.discoverPairs(HgRequests.TAB_REQUEST_RECOMMEND),
                "/reading/bookapi/search/tab/v");
        System.out.println("AB-B legacy-path http=" + b.code + " bytes=" + b.body.length);
        String signPath = null;
        if (a.body.length == 0 && b.body.length > 0) {
            signPath = "/reading/bookapi/search/tab/v";
            System.out.println("AB VERDICT: legacy-path wins (server rejects real-path medusa)");
        } else {
            System.out.println("AB VERDICT: real-path ok");
        }

        // 3) 三频道开页＋续页（用获胜签法）
        int[][] channels = {
                {HgRequests.TAB_REQUEST_RECOMMEND, HgRequests.TAB_RESPONSE_RECOMMEND},
                {HgRequests.TAB_COMIC, HgRequests.TAB_COMIC},
                {HgRequests.TAB_LIVE, HgRequests.TAB_LIVE}};
        String[] names = {"recommend", "comic", "live"};
        List<HgSeriesItem> recommendItems = new ArrayList<>();
        for (int ci = 0; ci < channels.length; ci++) {
            HttpResult r = signedGet(HgDevice.PATH_BOOKMALL_TAB,
                    HgRequests.discoverPairs(channels[ci][0]), signPath);
            archive("chan_" + names[ci] + ".json", r.body);
            HgCellPage page = r.body.length == 0 ? null
                    : HgParser.parseDiscoverTab(text(r), channels[ci][1]);
            System.out.println("== chan " + names[ci] + " http=" + r.code + " bytes=" + r.body.length
                    + " items=" + (page == null ? -1 : page.getItems().size())
                    + " hasMore=" + (page == null ? "-" : page.isHasMore())
                    + " cont=" + (page == null || page.getContinuation() == null ? "null" : "yes"));
            if (page != null) {
                for (int i = 0; i < Math.min(5, page.getItems().size()); i++) {
                    HgSeriesItem it = page.getItems().get(i);
                    System.out.println("   - " + it.getTitle() + " sid=" + it.getSeriesId()
                            + " vid=" + it.getVid() + " eps=" + it.getEpisodeCount()
                            + " vertical=" + it.getVertical());
                }
                if (ci == 0) recommendItems.addAll(page.getItems());
                if (page.getContinuation() != null) {
                    HttpResult more = signedGet(HgDevice.PATH_BOOKMALL_CELL_CHANGE,
                            HgRequests.cellChangePairs(page.getContinuation()), signPath);
                    archive("chan_" + names[ci] + "_more.json", more.body);
                    HgCellPage mp = more.body.length == 0 ? null : HgParser.parseCellChange(text(more));
                    System.out.println("   more http=" + more.code + " bytes=" + more.body.length
                            + " items=" + (mp == null ? -1 : mp.getItems().size())
                            + " hasMore=" + (mp == null ? "-" : mp.isHasMore())
                            + " nextOffset=" + (mp == null ? "-" : mp.getNextOffset()));
                } else {
                    System.out.println("   more SKIPPED (no continuation)");
                }
            }
        }

        // 4) 关注页签空态：未登录书架口（App 同路：reading 宿主无签名 GET）
        Map<String, String> fh = new HashMap<>();
        fh.put("user-agent", HgRequests.UA);
        HttpResult shelf = curl("GET",
                "https://reading.snssdk.com/reading/bookapi/bookshelf/video/list/v1/?"
                        + HgRequests.freshQuery(), fh, null);
        System.out.println("== follow shelf http=" + shelf.code + " bytes=" + shelf.body.length
                + " body=" + (shelf.body.length <= 120 ? text(shelf) : text(shelf).substring(0, 120)));

        // 5) E1 案：搜索 → 详情 → 模型
        System.out.println("== E1 hunt: " + E1_TITLE);
        List<String[]> sp = HgDevice.deviceQueryPairs();
        sp.add(new String[]{"query", E1_TITLE});
        sp.add(new String[]{"tab_type", "11"});
        sp.add(new String[]{"count", "20"});
        sp.add(new String[]{"offset", "0"});
        sp.add(new String[]{"use_correct", "true"});
        sp.add(new String[]{"bookshelf_search_plan", "4"});
        sp.add(new String[]{"search_source", "1"});
        sp.add(new String[]{"tab_name", "feed"});
        sp.add(new String[]{"bookstore_tab", "16"});
        sp.add(new String[]{"user_is_login", "0"});
        HttpResult sr = signedGet("/reading/bookapi/search/tab/v", sp, signPath);
        archive("e1_search.json", sr.body);
        System.out.println("   search http=" + sr.code + " bytes=" + sr.body.length);
        HgSeriesItem found = null;
        if (sr.body.length > 0) {
            for (HgSeriesItem it : HgParser.parseCellPage(text(sr)).getItems()) {
                System.out.println("   hit: " + it.getTitle() + " sid=" + it.getSeriesId()
                        + " vid=" + it.getVid() + " vertical=" + it.getVertical());
                if (found == null && it.getTitle() != null && it.getTitle().contains("四合院")) found = it;
            }
        }
        // 频道 16 里直接找同名（池子换频道后是否还在）
        for (HgSeriesItem it : recommendItems) {
            if (it.getTitle() != null && it.getTitle().contains("四合院")) {
                System.out.println("   in channel16: sid=" + it.getSeriesId() + " vid=" + it.getVid()
                        + " vertical=" + it.getVertical());
                if (found == null) found = it;
            }
        }
        if (found == null) {
            System.out.println("   E1 target NOT FOUND in search/channel this run");
        } else {
            String sid = found.getSeriesId();
            HttpResult det = signedPost(HgDevice.PATH_SERIES, HgRequests.seriesBody(sid));
            archive("e1_detail.json", det.body);
            HgSeriesDetail detail = det.body.length == 0 ? null : HgParser.parseVideoDetail(text(det));
            System.out.println("   detail http=" + det.code + " bytes=" + det.body.length
                    + " eps=" + (detail == null ? -1 : detail.getEpisodes().size())
                    + " count=" + (detail == null ? -1 : detail.getEpisodeCount()));
            String firstVid = found.getVid();
            if (detail != null && !detail.getEpisodes().isEmpty()) {
                firstVid = detail.getEpisodes().get(0).getVideoId();
            }
            System.out.println("   firstVid=" + firstVid + " (clip vid from cell=" + found.getVid() + ")");
            probeModel("clipVid", found.getVid());
            if (firstVid != null && !firstVid.equals(found.getVid())) probeModel("detailEp1", firstVid);
            probeModel("seriesIdAsVid", sid);
            // video_id_type=1 变体（SeriesBody 的 biz 口径）打同一个 clip vid
            if (found.getVid() != null) {
                JsonObject biz = new JsonObject();
                biz.addProperty("need_all_video_definition", true);
                biz.addProperty("video_id_type", 1);
                biz.addProperty("video_platform", HgRequests.VIDEO_PLATFORM_FQ);
                JsonObject body = new JsonObject();
                body.add("biz_param", biz);
                body.addProperty("video_id", found.getVid());
                HttpResult vm = signedPost(HgDevice.PATH_PLAYBACK, body);
                HgVideoModel m = vm.body.length == 0 ? null
                        : HgParser.parseSingleVideoModel(text(vm), found.getVid());
                System.out.println("   model[type=1] http=" + vm.code + " bytes=" + vm.body.length
                        + " model=" + (m == null ? "null" : "entries=" + m.getEntries().size()));
            }
        }

        // 6) 王妈回归：已知能播的模型口仍通
        probeModel("wangma-ep1", "7694655440692448281");
    }

    static void probeModel(String label, String vid) throws Exception {
        if (vid == null) {
            System.out.println("   model[" + label + "] skipped (vid null)");
            return;
        }
        HttpResult vm = signedPost(HgDevice.PATH_PLAYBACK, HgRequests.playbackBody(vid));
        HgVideoModel m = vm.body.length == 0 ? null : HgParser.parseSingleVideoModel(text(vm), vid);
        StringBuilder defs = new StringBuilder();
        if (m != null) {
            for (com.fongmi.android.tv.hongguo.model.HgVideoEntry e : m.getEntries()) {
                if (defs.length() > 0) defs.append(',');
                defs.append(e.getDefinition()).append('/').append(e.getCodecType())
                        .append(e.hasEncryptMarkers() ? "/enc" : "");
            }
        }
        System.out.println("   model[" + label + "] vid=" + vid + " http=" + vm.code
                + " bytes=" + vm.body.length + " model=" + (m == null ? "null" : "entries=" + m.getEntries().size())
                + " defs=" + defs);
        if (m == null && vm.body.length > 0) {
            String t = text(vm);
            System.out.println("   raw head: " + t.substring(0, Math.min(300, t.length())));
        }
    }
}
