package com.fongmi.android.tv.hongguo.api;

import com.github.catvod.utils.Prefers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.fongmi.android.tv.hongguo.sign.HgSign;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 匿名设备身份与注册（移植 openhongguo internal/protocol/register.go、tt_encrypt.go、
 * request.go DeviceQueryPairs、sdk/device.go 身份派生）。
 *
 * <p>定罪依据 openhongguo-dig-20261028.md §3/§4：旧链每次请求随机编造 iid/device_id
 * 且无签名，回包恒 200 空体；正路是先 device_register 拿真 device_id/iid 落盘复用，
 * 之后所有取址请求带注册身份＋有序查询串＋签名。本类只管身份与注册报文，
 * 网络发送在 HgApiClient。</p>
 *
 * <p>身份持久化走 Prefers（键 hongguo_device_profile_v1），注册一次全局复用。</p>
 */
public final class HgDevice {

    public static final String PACKAGE = "com.phoenix.read";
    public static final String VERSION_NAME = "7.3.9.32";
    public static final String VERSION_CODE = "73932";
    public static final int AID = 8662;
    public static final String APP_NAME = "novelread";
    public static final String DEVICE_PLATFORM = "android";
    public static final String API_HOST = "https://api5-normal-sinfonlinea.fqnovel.com";
    public static final String REGISTER_HOST = "https://log5-applog-lq.fqnovel.com";
    public static final String PATH_PLAYBACK = "/novel/player/video_model/v1/";
    public static final String PATH_SERIES = "/novel/player/video_detail/v1/";
    public static final String PATH_ALBUM = "/novel/player/album_detail/v1/";
    public static final String PATH_SERIES_BATCH = "/novel/player/multi_video_detail/player/v1";
    /** bookmall 频道开页（尾段是字面 v 不是 v1，openhongguo app.go PathDiscover 实证）。 */
    public static final String PATH_BOOKMALL_TAB = "/reading/bookapi/bookmall/tab/v";
    /** bookmall 频道续页（openhongguo app.go PathCellChange 实证）。 */
    public static final String PATH_BOOKMALL_CELL_CHANGE = "/reading/bookapi/bookmall/cell/change/v";

    // 注册档案描述的设备（openhongguo sdk/model DefaultDevice，与 medusa 内的
    // 写死设备串是两台，勿混）：Xiaomi 23127PN0CC / Android 14。
    private static final String BRAND = "Xiaomi";
    private static final String MODEL = "23127PN0CC";
    private static final String OS_VERSION = "14";
    private static final int OS_API = 34;
    private static final String BUILD = "UKQ1.230804.001";
    private static final String RESOLUTION = "1200*2670";
    private static final int DPI = 460;
    private static final String CHANNEL = "xiaomi_8662_64";

    private static final String PREF_KEY = "hongguo_device_profile_v1";

    private HgDevice() {
    }

    /** 设备档案：本地派生标识＋注册回的真 id＋注册回包的游客 Cookie 材料。 */
    public static final class Profile {
        public String cdid;
        public String openUdid;
        public String clientUdid;
        public String oaid;
        public String reqId;
        public String deviceId;
        public String installId;
        /** 注册回包下发的 device_token（请求头 x-tt-dt 的真值来源，见 login-ticket-dig-20261029.md）。 */
        public String deviceToken = "";
        public String ttReq = "";
        public String storeRegion = "";
        public String storeRegionSrc = "";

        public boolean registered() {
            return nonZero(deviceId) && nonZero(installId);
        }

        private static boolean nonZero(String s) {
            return s != null && !s.isEmpty() && !"0".equals(s);
        }
    }

    private static Profile cached;

    /**
     * 档案代次：每次重置 +1。注册请求发出时记下代次，回包落盘前比对——
     * 期间档案被重置过，那一轮注册回的 id/游客 Cookie 属于旧档案，作废
     * 不许回填到新档案上（HgApiClient.doRegister 据此判定）。
     */
    private static int generation;

    public static synchronized int profileGeneration() {
        return generation;
    }

    /**
     * 重置设备档案：整份匿名身份作废——本地派生标识（cdid/openudid/
     * clientudid/oaid/req_id）重新派生，注册回的 device_id/install_id 与
     * 注册派生的游客 Cookie 材料（ttreq/store-region）随旧档案一并清掉，
     * 落盘后即未注册态；重新注册走 HgApiClient.ensureDevice 既有流程。
     *
     * <p>边界（死线）：这里只动匿名设备档案这一份落盘（PREF_KEY）。
     * 账号登录态是另一回事——passport 会话 Cookie 由壳子账号托管
     * （CloudAccountStore，见 HongguoLoginProvider）独立存储，本方法
     * 不读不写；换档案后已登录账号不掉线，只是后续请求改用新设备 id
     * 签名（签名时现读档案，天然跟随）。</p>
     */
    public static synchronized void resetProfile() {
        generation++;
        Profile p = generate();
        save(p);
        cached = p;
    }

    /** 取档案：没有就本地派生一份并落盘（device_id/install_id 待注册回填）。 */
    public static synchronized Profile profile() {
        if (cached != null) return cached;
        Profile p = load();
        if (p == null) {
            p = generate();
            save(p);
        }
        cached = p;
        return p;
    }

    public static synchronized boolean isRegistered() {
        return profile().registered();
    }

    /** 清内存缓存（落盘内容不动）；下次 profile() 从盘上重读。 */
    public static synchronized void invalidateCache() {
        cached = null;
    }

    /** 注册成功回填真 id（并落盘，device_token 不动）；旧调用口（测试探针）保留。 */
    public static synchronized void saveRegistered(String deviceId, String installId) {
        saveRegistered(deviceId, installId, null);
    }

    /** 注册成功回填真 id 与 device_token（并落盘）；token 为空不覆写已有值。 */
    public static synchronized void saveRegistered(String deviceId, String installId, String deviceToken) {
        Profile p = profile();
        p.deviceId = deviceId;
        p.installId = installId;
        if (deviceToken != null && !deviceToken.isEmpty()) p.deviceToken = deviceToken;
        save(p);
    }

    /**
     * 注册回填的代次原子版：复核代次与落盘在同一把类锁内完成——注册
     * 请求在途时档案被重置（resetProfile 同锁 +1 代次），本轮回的旧 id
     * 绝无可能在「先查代次、后落盘」的缝里写进新档案（扫查 P2）。
     * 代次不符返回 false 且什么都不写，调用方按新档案重新注册。
     * device_token 随同一发回包落盘（官方口径：每轮注册回包重写、以
     * 最近一次为准）；回包没带 token 时不覆写已有值，防坏回包清凭证。
     */
    public static synchronized boolean saveRegisteredIfGeneration(int gen, String deviceId, String installId, String deviceToken) {
        if (generation != gen) return false;
        Profile p = profile();
        p.deviceId = deviceId;
        p.installId = installId;
        if (deviceToken != null && !deviceToken.isEmpty()) p.deviceToken = deviceToken;
        save(p);
        return true;
    }

    /** 档案现存的 device_token（x-tt-dt 填值口）；没刷到过时为空串。 */
    public static String deviceToken() {
        return nz(profile().deviceToken);
    }

    /**
     * 存量档案补刷判定：已注册但 device_token 为空——这类档案是旧版
     * 注册的（旧 parseRegister 把回包 token 扔了），要走一发同身份刷新
     * 注册把 token 捡回来（HgApiClient.ensureDevice 成功路径后静默触发）。
     */
    public static synchronized boolean needsDeviceToken() {
        Profile p = profile();
        return p.registered() && (p.deviceToken == null || p.deviceToken.isEmpty());
    }

    /** 游客 Cookie 落盘的代次原子版（同 saveRegisteredIfGeneration 口径）。 */
    public static synchronized boolean applyGuestCookiesIfGeneration(int gen, Map<String, String> cookies) {
        if (generation != gen) return false;
        applyGuestCookies(cookies);
        return true;
    }

    /** 注册回包 Set-Cookie 里的游客三件（ttreq/store-region/store-region-src）落盘。 */
    public static synchronized void applyGuestCookies(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) return;
        Profile p = profile();
        boolean dirty = false;
        String v;
        if ((v = cookies.get("ttreq")) != null && !v.isEmpty()) { p.ttReq = v; dirty = true; }
        if ((v = cookies.get("store-region")) != null && !v.isEmpty()) { p.storeRegion = v; dirty = true; }
        if ((v = cookies.get("store-region-src")) != null && !v.isEmpty()) { p.storeRegionSrc = v; dirty = true; }
        if (dirty) save(p);
    }

    /** 游客 Cookie 头（ttreq 非空才有，对照 sdk guestCookie 口径）；无则 null。 */
    public static String guestCookieHeader() {
        Profile p = profile();
        if (p.ttReq == null || p.ttReq.isEmpty()) return null;
        return "passport_csrf_token=; passport_csrf_token_default=; store-region=" + nz(p.storeRegion)
                + "; store-region-src=" + nz(p.storeRegionSrc)
                + "; install_id=" + nz(p.installId) + "; ttreq=" + p.ttReq;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** User-Agent（对照 protocol.UserAgent：档案设备代入）。 */
    public static String userAgent() {
        return PACKAGE + "/" + VERSION_CODE + " (Linux; U; Android " + OS_VERSION + "; zh_CN; "
                + MODEL + "; Build/" + BUILD
                + "; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)";
    }

    /** x-reading-request 值：ticketMillis-randomInt31（对照 sdk anonymousHeaders）。 */
    public static String readingRequestId(long ticketMillis) {
        int r = new SecureRandom().nextInt(Integer.MAX_VALUE);
        return ticketMillis + "-" + Math.abs(r);
    }

    /**
     * 有序设备查询串（对照 DeviceQueryPairs，31 对、序即签名的一部分）。
     * ts 与 _rticket 由签名器在末尾追加，这里不带。
     */
    public static List<String[]> deviceQueryPairs() {
        Profile p = profile();
        List<String[]> pairs = new ArrayList<>(31);
        pairs.add(new String[]{"iid", nz(p.installId)});
        pairs.add(new String[]{"device_id", nz(p.deviceId)});
        pairs.add(new String[]{"ac", "wifi"});
        pairs.add(new String[]{"channel", CHANNEL});
        pairs.add(new String[]{"aid", String.valueOf(AID)});
        pairs.add(new String[]{"app_name", APP_NAME});
        pairs.add(new String[]{"cdid", nz(p.cdid)});
        pairs.add(new String[]{"version_code", VERSION_CODE});
        pairs.add(new String[]{"version_name", VERSION_NAME});
        pairs.add(new String[]{"device_platform", DEVICE_PLATFORM});
        pairs.add(new String[]{"os", "android"});
        pairs.add(new String[]{"ssmix", "a"});
        pairs.add(new String[]{"device_type", MODEL});
        pairs.add(new String[]{"device_brand", BRAND});
        pairs.add(new String[]{"language", "zh"});
        pairs.add(new String[]{"os_api", String.valueOf(OS_API)});
        pairs.add(new String[]{"os_version", OS_VERSION});
        pairs.add(new String[]{"manifest_version_code", VERSION_CODE});
        pairs.add(new String[]{"resolution", RESOLUTION});
        pairs.add(new String[]{"dpi", String.valueOf(DPI)});
        pairs.add(new String[]{"update_version_code", VERSION_CODE});
        pairs.add(new String[]{"host_abi", "arm64-v8a"});
        pairs.add(new String[]{"dragon_device_type", "phone"});
        pairs.add(new String[]{"pv_player", VERSION_CODE});
        pairs.add(new String[]{"compliance_status", "0"});
        pairs.add(new String[]{"need_personal_recommend", "1"});
        pairs.add(new String[]{"player_so_load", "1"});
        pairs.add(new String[]{"is_android_pad_screen", "0"});
        pairs.add(new String[]{"openudid", nz(p.openUdid)});
        return pairs;
    }

    // ---------- 注册报文（BuildRegisterBody：gzip 明文 → TTEncrypt） ----------

    public static byte[] buildRegisterBody(long generatedAtMs) {
        Profile p = profile();
        JsonObject header = new JsonObject();
        header.addProperty("display_name", "红果免费短剧");
        header.addProperty("update_version_code", 73932);
        header.addProperty("manifest_version_code", 73932);
        header.addProperty("app_version_minor", VERSION_NAME);
        header.addProperty("aid", AID);
        header.addProperty("channel", CHANNEL);
        header.addProperty("package", PACKAGE);
        header.addProperty("app_version", VERSION_NAME);
        header.addProperty("version_code", 73932);
        header.addProperty("os", "Android");
        header.addProperty("os_version", OS_VERSION);
        header.addProperty("os_api", OS_API);
        header.addProperty("device_model", MODEL);
        header.addProperty("device_brand", BRAND);
        header.addProperty("device_manufacturer", BRAND);
        header.addProperty("cpu_abi", "arm64-v8a");
        header.addProperty("language", "zh");
        header.addProperty("timezone", 8);
        header.addProperty("access", "wifi");
        header.addProperty("region", "CN");
        header.addProperty("tz_name", "Asia/Shanghai");
        header.addProperty("tz_offset", 28800);
        header.addProperty("cdid", nz(p.cdid));
        header.addProperty("openudid", nz(p.openUdid));
        header.addProperty("clientudid", nz(p.clientUdid));
        header.add("ipv6_list", new JsonArray());
        header.add("sim_serial_number", new JsonArray());
        header.add("oaid", bootstrapOaid(nz(p.oaid), generatedAtMs - 200));
        header.addProperty("oaid_may_support", true);
        header.addProperty("req_id", nz(p.reqId));
        header.addProperty("device_platform", DEVICE_PLATFORM);
        header.addProperty("guest_mode", 0);
        header.addProperty("is_system_app", 0);
        header.addProperty("sdk_flavor", "china");
        header.addProperty("device_id", "0");
        header.addProperty("install_id", "0");
        String plain = "{\"magic_tag\":\"ss_app_log\",\"header\":" + header.toString()
                + ",\"_gen_time\":" + generatedAtMs + "}";
        return ttEncrypt(gzip(plain.getBytes(StandardCharsets.UTF_8), generatedAtMs));
    }

    /** OAID 对象：键按字典序（Go map 序列化口径）。 */
    private static JsonObject bootstrapOaid(String id, long issuedAtMs) {
        JsonObject o = new JsonObject();
        o.addProperty("hw_id_version_code", "0");
        o.addProperty("id", id);
        o.addProperty("is_track_limited", "false");
        o.addProperty("query_times", "1");
        o.addProperty("req_id", id);
        o.addProperty("take_ms", "6");
        o.addProperty("time", String.valueOf(issuedAtMs));
        return o;
    }

    /** gzip：DefaultCompression、Header.ModTime=generatedAt、OS=255（对照 Go gzip 头）。 */
    private static byte[] gzip(byte[] plain, long mtimeMs) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(plain);
        deflater.finish();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            body.write(buf, 0, n);
        }
        deflater.end();
        byte[] deflated = body.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1f);
        out.write(0x8b);
        out.write(8);
        out.write(0);
        int mtime = (int) (mtimeMs / 1000);
        out.write(mtime & 0xFF);
        out.write((mtime >>> 8) & 0xFF);
        out.write((mtime >>> 16) & 0xFF);
        out.write((mtime >>> 24) & 0xFF);
        out.write(0);
        out.write(255);
        out.write(deflated, 0, deflated.length);
        long c = crc.getValue();
        out.write((int) (c & 0xFF));
        out.write((int) ((c >>> 8) & 0xFF));
        out.write((int) ((c >>> 16) & 0xFF));
        out.write((int) ((c >>> 24) & 0xFF));
        int size = plain.length;
        out.write(size & 0xFF);
        out.write((size >>> 8) & 0xFF);
        out.write((size >>> 16) & 0xFF);
        out.write((size >>> 24) & 0xFF);
        return out.toByteArray();
    }

    private static final String TT_PREFIX_HEX =
            "3176c9b6d71579d7e82179c9e7cbde787b54d541bad3b15f2e8fed92293fa25a";
    private static final String TT_CONSTANT_HEX =
            "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524" +
            "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58";

    /** TTEncrypt 信封（移植 tt_encrypt.go）：SHA512 链派生 key/iv，AES-128-CBC。 */
    static byte[] ttEncrypt(byte[] input) {
        try {
            byte[] prefix = HgSign.hexDecode(TT_PREFIX_HEX);
            byte[] constant = HgSign.hexDecode(TT_CONSTANT_HEX);
            MessageDigest sha = MessageDigest.getInstance("SHA-512");
            byte[] h1 = sha.digest(prefix);
            sha.reset();
            sha.update(h1);
            byte[] keyIv = sha.digest(constant);
            sha.reset();
            byte[] digest = sha.digest(input);
            byte[] plain = new byte[digest.length + input.length];
            System.arraycopy(digest, 0, plain, 0, digest.length);
            System.arraycopy(input, 0, plain, digest.length, input.length);
            int padding = 16 - (plain.length % 16);
            byte[] padded = new byte[plain.length + padding];
            System.arraycopy(plain, 0, padded, 0, plain.length);
            for (int i = plain.length; i < padded.length; i++) padded[i] = (byte) padding;
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(keyIv, 0, 16, "AES"),
                    new javax.crypto.spec.IvParameterSpec(keyIv, 16, 16));
            byte[] encrypted = cipher.doFinal(padded);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x74);
            out.write(0x63);
            out.write(0x05);
            out.write(0x10);
            out.write(0);
            out.write(0);
            out.write(prefix, 0, prefix.length);
            out.write(encrypted, 0, encrypted.length);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("TTEncrypt 失败", e);
        }
    }

    /**
     * 解析注册回包：{device_id_str, install_id_str, device_token}（字符串
     * 或数字都收），id 缺失/为 "0" 返回 null（对照 ParseRegister）；
     * device_token 缺失时第三元素为空串（落盘层以非空才覆写处置）。
     * device_token 就是请求头 x-tt-dt 的值：官方注册 SDK 从同一回包
     * 读出落盘、每请求带出（login-ticket-dig-20261029.md 第一节四环
     * 实证）；旧版只取两个 id 把它扔了，HgSign 因此恒发空 x-tt-dt。
     */
    public static String[] parseRegister(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String deviceId = flexString(root, "device_id_str");
            String installId = flexString(root, "install_id_str");
            if (!Profile.nonZero(deviceId) || !Profile.nonZero(installId)) return null;
            String token = flexString(root, "device_token");
            return new String[]{deviceId, installId, token == null ? "" : token};
        } catch (Exception e) {
            return null;
        }
    }

    private static String flexString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return null;
        return el.getAsString();
    }

    // ---------- 身份派生与持久化 ----------

    private static Profile generate() {
        byte[] raw = new byte[72];
        new SecureRandom().nextBytes(raw);
        Profile p = new Profile();
        p.cdid = uuid(raw, 0);
        p.openUdid = hexLower(raw, 16, 8);
        p.clientUdid = uuid(raw, 24);
        p.oaid = uuid(raw, 40);
        p.reqId = uuid(raw, 56);
        return p;
    }

    private static String uuid(byte[] raw, int off) {
        int[] id = new int[16];
        for (int i = 0; i < 16; i++) id[i] = raw[off + i] & 0xFF;
        id[6] = (id[6] & 0x0f) | 0x40;
        id[8] = (id[8] & 0x3f) | 0x80;
        StringBuilder sb = new StringBuilder(36);
        for (int i = 0; i < 16; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-');
            sb.append(Character.forDigit((id[i] >>> 4) & 15, 16));
            sb.append(Character.forDigit(id[i] & 15, 16));
        }
        return sb.toString();
    }

    private static String hexLower(byte[] raw, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            int v = raw[off + i] & 0xFF;
            sb.append(Character.forDigit((v >>> 4) & 15, 16));
            sb.append(Character.forDigit(v & 15, 16));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static Profile load() {
        try {
            String s = Prefers.getString(PREF_KEY);
            if (s == null || s.isEmpty()) return null;
            JsonObject o = JsonParser.parseString(s).getAsJsonObject();
            Profile p = new Profile();
            p.cdid = str(o, "cdid");
            p.openUdid = str(o, "openudid");
            p.clientUdid = str(o, "clientudid");
            p.oaid = str(o, "oaid");
            p.reqId = str(o, "req_id");
            p.deviceId = str(o, "device_id");
            p.installId = str(o, "install_id");
            p.deviceToken = str(o, "device_token");
            p.ttReq = str(o, "ttreq");
            p.storeRegion = str(o, "store_region");
            p.storeRegionSrc = str(o, "store_region_src");
            if (p.cdid == null || p.openUdid == null) return null;
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static void save(Profile p) {
        JsonObject o = new JsonObject();
        o.addProperty("cdid", nz(p.cdid));
        o.addProperty("openudid", nz(p.openUdid));
        o.addProperty("clientudid", nz(p.clientUdid));
        o.addProperty("oaid", nz(p.oaid));
        o.addProperty("req_id", nz(p.reqId));
        o.addProperty("device_id", nz(p.deviceId));
        o.addProperty("install_id", nz(p.installId));
        o.addProperty("device_token", nz(p.deviceToken));
        o.addProperty("ttreq", nz(p.ttReq));
        o.addProperty("store_region", nz(p.storeRegion));
        o.addProperty("store_region_src", nz(p.storeRegionSrc));
        Prefers.put(PREF_KEY, o.toString());
    }
}
