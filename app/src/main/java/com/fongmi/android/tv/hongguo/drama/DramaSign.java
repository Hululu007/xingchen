package com.fongmi.android.tv.hongguo.drama;

import com.fongmi.android.tv.hongguo.sign.HgSign;
import com.fongmi.android.tv.hongguo.sign.HgSm3;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 短剧取数签名器（2026-10-30 从零重建）：呈现与签名形态照 hongguoTV
 * （drpys 22261ad 的 Kotlin 移植，GPL-3.0，见 ~/workspace/hongguoTV-ref
 * kotlin-tv/core/Signer.kt＋VendorConstants.kt）——与全网 drpys 人口
 * 同一份写死共享身份、同一套签名形态：
 *
 * <ul>
 *   <li>x-argus＝base64(LE32(ts))、x-ladon＝base64(BE32(ts))：时间戳占位，
 *       不发自家真算法长串（定根账 hongguo-1462-triage-20261030 件二附节
 *       嫌疑一：独一份画像）。</li>
 *   <li>x-gorgon/x-helios/x-medusa：真算法，组装器借签名前辈 HgSign
 *       （其 medusa 指纹面已按 drpys 冻结值逐值对齐，定根账同节核过）；
 *       medusa 的哈希 branch 只发 0——_rticket 自 now 起逐毫秒上调
 *       （≤127 次）逼支，与 hongguoTV Signer.sign 同法。</li>
 *   <li>不发 x-ss-dp / lc / x-reading-request 附加头；x-tt-dt 恒空；
 *       共享请求不带任何账号 Cookie（本类根本不组 Cookie）。</li>
 * </ul>
 *
 * 设备公共参 27 对逐字照 VendorConstants.device（iid/device_id 为全网
 * 共用的写死值）；搜索面把 version 段换 72232/7.2.2.32（hongguoTV
 * appSearch 同法）。
 */
public final class DramaSign {

    public static final String SHARED_DEVICE_ID = "1905892595378490";
    public static final String SHARED_IID = "1905892595382586";
    public static final String VERSION_NAME_PLAY = "7.1.3.32";
    public static final String VERSION_NAME_SEARCH = "7.2.2.32";

    public static final String VIDEO_UA = "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)";
    public static final String APP_UA = "com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)";

    private static final SecureRandom RANDOM = new SecureRandom();

    private DramaSign() {
    }

    /** 共享设备公共参（27 对，序照 VendorConstants.device）；searchFace 时换搜索面版本段。 */
    public static List<String[]> devicePairs(boolean searchFace) {
        String vc = searchFace ? "72232" : "71332";
        String vn = searchFace ? VERSION_NAME_SEARCH : VERSION_NAME_PLAY;
        List<String[]> p = new ArrayList<>(27);
        p.add(kv("iid", SHARED_IID));
        p.add(kv("device_id", SHARED_DEVICE_ID));
        p.add(kv("ac", "wifi"));
        p.add(kv("channel", "update_64"));
        p.add(kv("aid", "8662"));
        p.add(kv("app_name", "novelread"));
        p.add(kv("version_code", vc));
        p.add(kv("version_name", vn));
        p.add(kv("device_platform", "android"));
        p.add(kv("os", "android"));
        p.add(kv("ssmix", "a"));
        p.add(kv("device_type", "25053RT47C"));
        p.add(kv("device_brand", "Redmi"));
        p.add(kv("language", "zh"));
        p.add(kv("os_api", "36"));
        p.add(kv("os_version", "16"));
        p.add(kv("manifest_version_code", vc));
        p.add(kv("resolution", "1280*2772"));
        p.add(kv("dpi", "520"));
        p.add(kv("update_version_code", vc));
        p.add(kv("host_abi", "arm64-v8a"));
        p.add(kv("dragon_device_type", "phone"));
        p.add(kv("pv_player", vc));
        p.add(kv("compliance_status", "0"));
        p.add(kv("need_personal_recommend", "1"));
        p.add(kv("player_so_load", "1"));
        p.add(kv("is_android_pad_screen", "0"));
        return p;
    }

    private static String[] kv(String k, String v) {
        return new String[]{k, v};
    }

    /** 一次签名产物：完整 URL＋头组（＋原 body 由调用方持有）。 */
    public static final class Signed {
        public final String url;
        public final Map<String, String> headers;

        Signed(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    /**
     * 签名（hongguoTV Signer.sign 同构）：pairs 为不含 ts/_rticket 的
     * 有序公共参＋业务参；body 为 null 即 GET 形态。
     *
     * @param baseUrl 含 scheme://host/path 的完整基址（无 query）
     */
    public static Signed sign(String baseUrl, List<String[]> pairs, byte[] body, long nowMillis) {
        long ts = nowMillis / 1000;
        byte[] bodyMd5 = body == null ? new byte[16] : md5(body);
        String query = null;
        long ticket = nowMillis;
        for (int offset = 0; offset <= 127; offset++) {
            ticket = nowMillis + offset;
            List<String[]> withTicket = new ArrayList<>(pairs);
            withTicket.add(kv("ts", String.valueOf(ts)));
            withTicket.add(kv("_rticket", String.valueOf(ticket)));
            query = encodeQuery(withTicket);
            byte[] sum = HgSm3.sum(query.getBytes(StandardCharsets.UTF_8));
            if (HgSign.branchOf(sum, bodyMd5, le32((int) ts)) == 0) break;
            if (offset == 127) throw new IllegalStateException("无法生成请求签名");
        }
        String url = baseUrl + "?" + query;
        int khronos = (int) ts;
        int gorgonRandom = RANDOM.nextInt(65536);
        HgSign.Entropy ent = new HgSign.Entropy();
        ent.envLaunch = 100 + RANDOM.nextInt(21);
        ent.envPid = 10001 + RANDOM.nextInt(2000);
        ent.nowSecs = ts;
        ent.messageRandom = RANDOM.nextInt();
        ent.random = RANDOM.nextInt();
        ent.packedRandom = RANDOM.nextInt();
        ent.gorgonRandom = gorgonRandom;
        ent.heliosRandom = RANDOM.nextInt();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", body != null ? VIDEO_UA : APP_UA);
        h.put("Accept", "application/json; charset=utf-8,application/x-protobuf");
        h.put("x-ss-req-ticket", String.valueOf(ticket));
        h.put("x-tt-request-tag", "t=0;n=0");
        h.put("sdk-version", "2");
        h.put("passport-sdk-version", "50561");
        h.put("x-xs-from-web", "0");
        h.put("x-khronos", String.valueOf(ts));
        h.put("x-ladon", base64(be32((int) ts)));
        h.put("x-argus", base64(le32((int) ts)));
        h.put("x-gorgon", HgSign.xGorgon(query, body, body != null, khronos, gorgonRandom));
        h.put("x-helios", HgSign.assembleHelios(khronos, ent.heliosRandom));
        h.put("x-medusa", HgSign.assembleMedusa(url, body, body != null, khronos,
                SHARED_DEVICE_ID, body != null ? VERSION_NAME_PLAY : VERSION_NAME_SEARCH, ent));
        h.put("x-tt-dt", "");
        if (body != null) {
            h.put("Content-Type", "application/json; charset=UTF-8");
            h.put("x-vc-bdturing-sdk-version", "3.7.2.cn");
            h.put("x-ss-stub", HgSign.contentMd5Hex(body));
        }
        return new Signed(url, h);
    }

    static byte[] md5(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("MD5").digest(data);
        } catch (Exception e) {
            return new byte[16];
        }
    }

    // ---------- 纯函数工具（harness 可测） ----------

    /** 查询串编码（hongguoTV encode：URLEncoder 后 + → %20、%7E → ~）。 */
    public static String encodeQuery(List<String[]> pairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.size(); i++) {
            if (i > 0) sb.append('&');
            sb.append(encode(pairs.get(i)[0])).append('=').append(encode(pairs.get(i)[1]));
        }
        return sb.toString();
    }

    static String encode(String v) {
        if (v == null) return "";
        try {
            return URLEncoder.encode(v, "UTF-8").replace("+", "%20").replace("%7E", "~");
        } catch (UnsupportedEncodingException e) {
            return v;
        }
    }

    public static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    public static byte[] be32(int v) {
        return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** 标准 Base64（带填充），自带实现免依赖 Android/java.util 版本差。 */
    public static String base64(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xFF;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            sb.append(B64[b0 >> 2]);
            sb.append(B64[((b0 & 3) << 4) | (b1 >> 4)]);
            sb.append(i + 1 < data.length ? B64[((b1 & 15) << 2) | (b2 >> 6)] : '=');
            sb.append(i + 2 < data.length ? B64[b2 & 63] : '=');
        }
        return sb.toString();
    }
}
