import com.fongmi.android.tv.hongguo.api.HgDevice;
import com.fongmi.android.tv.hongguo.api.HgRequests;
import com.fongmi.android.tv.hongguo.model.HgParser;
import com.fongmi.android.tv.hongguo.model.HgSeriesDetail;
import com.fongmi.android.tv.hongguo.model.HgVideoModel;
import com.fongmi.android.tv.hongguo.sign.HgSign;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本机实跑探针（不进 run.sh 离线套）：移植后的 Java 注册＋签名＋取模型链
 * 从本机直发 fqnovel，目标＝王妈第 1 集 vid 7694655440692448281 的真
 * video_model（非空、5 档、DEK 与定罪账 898dca2b… 全等）。
 * 传输用 curl 子进程（走本机环境代理），因为助手 JVM 原始 TCP 被权限层拦
 * （run.sh HTTP 回环段同口径）。Prefers 为进程内桩，注册身份只活在本进程。
 */
public class HgSignLiveProbe {

    static int fail = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (detail == null ? "" : " | " + detail));
        if (!ok) fail++;
    }

    static final class HttpResult {
        int code;
        byte[] body;
        Map<String, String> cookies = new HashMap<>();
    }

    static HttpResult curl(String method, String url, Map<String, String> headers, byte[] body) throws Exception {
        Path dir = Files.createTempDirectory("hgprobe");
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
        int exit = p.waitFor();
        if (exit != 0) throw new IllegalStateException("curl exit=" + exit);
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

    public static void main(String[] args) throws Exception {
        String vid = "7694655440692448281";
        String seriesId = "7694653385668037657";

        // 1) 设备注册（TTEncrypt 信封）
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
        String regText = new String(reg.body, StandardCharsets.UTF_8);
        System.out.println("register http=" + reg.code + " bytes=" + reg.body.length);
        String[] ids = HgDevice.parseRegister(regText);
        check("注册回真 device_id/install_id", ids != null,
                ids == null ? regText.substring(0, Math.min(160, regText.length()))
                        : "device_id=" + ids[0] + " install_id=" + ids[1]);
        if (ids == null) {
            System.out.println("FAILURES=" + fail);
            System.exit(1);
        }
        HgDevice.saveRegistered(ids[0], ids[1]);
        HgDevice.applyGuestCookies(reg.cookies);
        System.out.println("register cookies=" + reg.cookies.keySet());

        // 2) 签名单集取模型
        String bodyStr = HgRequests.playbackBody(vid).toString();
        byte[] bodyBytes = bodyStr.getBytes(StandardCharsets.UTF_8);
        long ticket = System.currentTimeMillis();
        HgSign.Signed signed = HgSign.signPost(HgDevice.PATH_PLAYBACK, HgDevice.deviceQueryPairs(),
                ticket, HgDevice.profile().deviceId, HgDevice.VERSION_NAME, bodyBytes, null);
        Map<String, String> h = new HashMap<>();
        h.put("user-agent", HgDevice.userAgent());
        h.putAll(signed.headers);
        h.put("x-ss-dp", "8662");
        h.put("lc", "101");
        h.put("x-reading-request", HgDevice.readingRequestId(ticket));
        String guest = HgDevice.guestCookieHeader();
        if (guest != null) h.put("Cookie", guest);
        String url = HgDevice.API_HOST + HgDevice.PATH_PLAYBACK + "?" + signed.query;
        HttpResult res = curl("POST", url, h, bodyBytes);
        String text = new String(res.body, StandardCharsets.UTF_8);
        System.out.println("video_model http=" + res.code + " bytes=" + res.body.length);
        check("video_model 非空回包", res.code == 200 && res.body.length > 1000,
                "bytes=" + res.body.length);
        HgVideoModel m = res.body.length == 0 ? null : HgParser.parseSingleVideoModel(text, vid);
        check("模型可解析且 5 档", m != null && m.getEntries().size() == 5,
                "entries=" + (m == null ? -1 : m.getEntries().size()));
        check("SpadeKey 模型级 DEK 对等 898dca2b…",
                m != null && "898dca2b79065fd4e0e2afea35153be3".equals(m.deriveDekHex()),
                "dek=" + (m == null ? null : m.deriveDekHex()));
        check("模型受保护判定", m != null && m.isProtected(), null);

        // 3) 签名单剧详情（集表 86 集、首集 vid 对位）
        String dBodyStr = HgRequests.seriesBody(seriesId).toString();
        byte[] dBody = dBodyStr.getBytes(StandardCharsets.UTF_8);
        long ticket2 = System.currentTimeMillis();
        HgSign.Signed s2 = HgSign.signPost(HgDevice.PATH_SERIES, HgDevice.deviceQueryPairs(),
                ticket2, HgDevice.profile().deviceId, HgDevice.VERSION_NAME, dBody, null);
        Map<String, String> h2 = new HashMap<>();
        h2.put("user-agent", HgDevice.userAgent());
        h2.putAll(s2.headers);
        h2.put("x-ss-dp", "8662");
        h2.put("lc", "101");
        h2.put("x-reading-request", HgDevice.readingRequestId(ticket2));
        if (guest != null) h2.put("Cookie", guest);
        String url2 = HgDevice.API_HOST + HgDevice.PATH_SERIES + "?" + s2.query;
        HttpResult res2 = curl("POST", url2, h2, dBody);
        String text2 = new String(res2.body, StandardCharsets.UTF_8);
        System.out.println("video_detail http=" + res2.code + " bytes=" + res2.body.length);
        HgSeriesDetail d = res2.body.length == 0 ? null : HgParser.parseVideoDetail(text2);
        check("详情 86 集且首集 vid 对位",
                d != null && d.getEpisodes().size() == 86
                        && vid.equals(d.getEpisodes().get(0).getVideoId()),
                "episodes=" + (d == null ? -1 : d.getEpisodes().size()));

        System.out.println(fail == 0 ? "ALL PASS" : ("FAILURES=" + fail));
        if (fail > 0) System.exit(1);
    }
}
