import com.fongmi.android.tv.hongguo.api.HgDevice;
import com.fongmi.android.tv.hongguo.api.HgRequests;
import com.fongmi.android.tv.hongguo.model.HgCellPage;
import com.fongmi.android.tv.hongguo.model.HgParser;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;
import com.fongmi.android.tv.hongguo.sign.HgSign;
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
 * 页签定罪探针（不进 run.sh 离线套）：逐字复现 App 现行 feed GET 口径
 * （reading.snssdk.com、无签名、freshQuery 假指纹、HgRequests.UA）抓四页签
 * 真回包存档，并用 HgParser.parseCellPage 按 App 同路解析计数。
 * 另对 plan / bookmall/tab 各连发两次，验「重开恒回首批」。
 * 回包存档目录：~/workspace/hongguo/tabprobe-20261028/
 */
public class HgTabLiveProbe {

    static final Path OUT_DIR = Paths.get(System.getProperty("user.home"),
            "workspace/hongguo/tabprobe-20261028");
    static final String HOST = "https://reading.snssdk.com";

    static final class HttpResult {
        int code;
        byte[] body;
        Map<String, String> cookies = new HashMap<>();
    }

    static HttpResult curl(String url, Map<String, String> headers) throws Exception {
        Path dir = Files.createTempDirectory("hgtab");
        List<String> cmd = new ArrayList<>();
        cmd.add("curl");
        cmd.add("-s");
        cmd.add("-D");
        cmd.add(dir.resolve("headers.txt").toString());
        cmd.add("-o");
        cmd.add(dir.resolve("resp.bin").toString());
        cmd.add("-w");
        cmd.add("%{http_code}");
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
        r.body = Files.readAllBytes(dir.resolve("resp.bin"));
        for (String line : Files.readString(dir.resolve("headers.txt")).split("\n")) {
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

    /** App 现行 get() 口径：path + "?" + freshQuery()，无签名头，UA=HgRequests.UA，无登录 Cookie。 */
    static HttpResult appGet(String path) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("user-agent", HgRequests.UA);
        return curl(HOST + path + "?" + HgRequests.freshQuery(), h);
    }

    static void probe(String name, HttpResult r) throws Exception {
        Files.createDirectories(OUT_DIR);
        Files.write(OUT_DIR.resolve(name + ".json"), r.body);
        String text = new String(r.body, StandardCharsets.UTF_8);
        System.out.println("== " + name + " http=" + r.code + " bytes=" + r.body.length);
        if (r.body.length == 0) {
            System.out.println("   BODY EMPTY -> App enqueue: onFail(回包空体) -> 加载失败");
            return;
        }
        if (r.code < 200 || r.code >= 300) {
            System.out.println("   HTTP FAIL -> App enqueue: onFail(" + r.code + ") -> 加载失败");
            System.out.println("   head: " + text.substring(0, Math.min(200, text.length())));
            return;
        }
        // 顶层形状
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            System.out.println("   top keys: " + root.keySet());
            System.out.println("   code=" + root.get("code") + " message=" + root.get("message"));
            JsonElement data = root.get("data");
            if (data != null && data.isJsonObject()) {
                System.out.println("   data keys: " + data.getAsJsonObject().keySet());
            } else {
                System.out.println("   data type: " + (data == null ? "null" : data.getClass().getSimpleName()));
            }
        } catch (Exception e) {
            System.out.println("   TOP-LEVEL JSON PARSE EXCEPTION: " + e);
        }
        // App 同路解析
        try {
            HgCellPage page = HgParser.parseCellPage(text);
            List<HgSeriesItem> items = page.getItems();
            System.out.println("   parseCellPage items=" + (items == null ? -1 : items.size()));
            if (items != null) {
                for (int i = 0; i < Math.min(8, items.size()); i++) {
                    HgSeriesItem it = items.get(i);
                    System.out.println("     [" + i + "] " + it.getSeriesId() + " | " + it.getTitle()
                            + " | vid=" + it.getVid() + " eps=" + it.getEpisodeCount());
                }
            }
            if (items == null || items.isEmpty()) {
                System.out.println("   -> App: onSuccess(空) -> 空态文案（不是加载失败）");
            }
        } catch (Exception e) {
            System.out.println("   parseCellPage EXCEPTION: " + e.getClass().getName() + ": " + e.getMessage());
            System.out.println("   -> App enqueue catch: onFail(-1) -> 加载失败");
        }
    }

    public static void main(String[] args) throws Exception {
        // 注册（与 HgSignLiveProbe 同路；本探针的 feed 请求本身不带注册身份，
        // 注册只为证明同机同出口签名链在位、排除出口问题）
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
        Path dir = Files.createTempDirectory("hgtabreg");
        List<String> cmd = new ArrayList<>();
        cmd.add("curl"); cmd.add("-s"); cmd.add("-X"); cmd.add("POST");
        cmd.add("-o"); cmd.add(dir.resolve("resp.bin").toString());
        cmd.add("-w"); cmd.add("%{http_code}");
        Path reqFile = dir.resolve("req.bin");
        Files.write(reqFile, regBody);
        cmd.add("--data-binary"); cmd.add("@" + reqFile);
        for (Map.Entry<String, String> e : rh.entrySet()) {
            cmd.add("-H"); cmd.add(e.getKey() + ": " + e.getValue());
        }
        cmd.add(regUrl);
        Process p = new ProcessBuilder(cmd).start();
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) { in.transferTo(outBuf); }
        p.waitFor();
        byte[] regResp = Files.readAllBytes(dir.resolve("resp.bin"));
        String[] ids = HgDevice.parseRegister(new String(regResp, StandardCharsets.UTF_8));
        System.out.println("register http=" + outBuf.toString(StandardCharsets.UTF_8).trim()
                + " ids=" + (ids == null ? "FAIL" : ids[0] + "/" + ids[1]));

        // 四页签 App 现行口径
        probe("plan_1", appGet("/reading/bookapi/plan/v1/"));
        probe("plan_2", appGet("/reading/bookapi/plan/v1/"));
        probe("bookmall_1", appGet("/reading/bookapi/bookmall/tab/v1/"));
        probe("bookmall_2", appGet("/reading/bookapi/bookmall/tab/v1/"));
        probe("front_1", appGet("/reading/bookapi/new_category/front/v1/"));
        probe("shelf_1", appGet("/reading/bookapi/bookshelf/video/list/v1/"));
        System.out.println("DONE");
    }
}
