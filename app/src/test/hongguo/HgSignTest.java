import com.fongmi.android.tv.hongguo.api.HgDevice;
import com.fongmi.android.tv.hongguo.api.HgRequests;
import com.fongmi.android.tv.hongguo.model.HgParser;
import com.fongmi.android.tv.hongguo.model.HgSeriesDetail;
import com.fongmi.android.tv.hongguo.model.HgVideoModel;
import com.fongmi.android.tv.hongguo.proxy.HgStreamSpec;
import com.fongmi.android.tv.hongguo.sign.HgSign;
import com.fongmi.android.tv.hongguo.sign.HgSm3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 签名移植离线断言（run.sh 驱动）：Go 参考向量（openhongguo sign_test.go）、
 * 签名确定性、注册持久化、借条 3 的模型级保护判定（未保护免钥匙）。
 * 真回包锚点：~/workspace/hongguo/openhongguo-probe/resp-004.json
 * （王妈第 1 集 video_model，钥匙须推出 898dca2b79065fd4e0e2afea35153be3）。
 */
public class HgSignTest {
    static int fail = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (detail == null ? "" : " | " + detail));
        if (!ok) fail++;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    public static void main(String[] args) throws Exception {
        // 1) Go 参考向量（sign_test.go 逐条）
        check("SM3(abc) 向量",
                "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0"
                        .equals(hex(HgSm3.sum("abc".getBytes(StandardCharsets.UTF_8)))), null);
        String query = "iid=7100000000000012&device_id=7100000000000011&ac=wifi&channel=update_64"
                + "&aid=8662&app_name=novelread&version_code=71332&version_name=7.1.3.32"
                + "&device_platform=android&os=android&ssmix=a&device_type=25053RT47C"
                + "&device_brand=Redmi&language=zh&os_api=36&os_version=16"
                + "&manifest_version_code=71332&resolution=1280*2772&dpi=520"
                + "&update_version_code=71332&host_abi=arm64-v8a&dragon_device_type=phone"
                + "&pv_player=71332&compliance_status=0&need_personal_recommend=1"
                + "&player_so_load=1&is_android_pad_screen=0"
                + "&ts=1700000000&_rticket=1700000000123";
        check("gorgon 向量",
                "84043412400154ed198c3230d5ff1364e71df6105a46126993c0".equals(
                        HgSign.xGorgon(query, "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                                true, 1700000000, 0x1234)), null);
        check("helios 向量",
                "k0TjeBWFFnRXfkeKlh3Hmx1jLjrfhgCasqsF4XvB1+0eB1Mw".equals(
                        HgSign.assembleHelios(1700000000, 2028160147)), null);
        byte[][] hs = HgSign.keyHash((int) 2378451149L);
        check("keyHash.hash 向量",
                "1b35023431b99a949ab7b70fc50a30216076492a6229d102a7a33fa3a1104170".equals(hex(hs[0])), null);
        check("keyHash.seed 向量", "b0dff9ff".equals(hex(hs[1])), null);
        check("xmxor 向量",
                "5c69c37694106e8cbdb2e074619a1dda6c5a5a65".equals(
                        hex(HgSign.xmxor("0123456789abcdefghij".getBytes(StandardCharsets.UTF_8), hs[0]))), null);
        HgSign.Entropy ent = new HgSign.Entropy();
        ent.envLaunch = 103;
        ent.envPid = 11691;
        ent.nowSecs = 1700000000L;
        ent.messageRandom = 1273073252;
        ent.random = (int) 2378451149L;
        ent.packedRandom = (int) 2253366658L;
        String medusa = HgSign.assembleMedusa(
                "https://api5-normal-sinfonlineb.fqnovel.com/x/v1?aid=8662&device_id=7100000000000011&ts=1700000000",
                "{\"a\":1}".getBytes(StandardCharsets.UTF_8), true, 1700000000,
                "7100000000000011", "7.1.3.32", ent);
        String medusa2 = HgSign.assembleMedusa(
                "https://api5-normal-sinfonlineb.fqnovel.com/x/v1?aid=8662&device_id=7100000000000011&ts=1700000000",
                "{\"a\":1}".getBytes(StandardCharsets.UTF_8), true, 1700000000,
                "7100000000000011", "7.1.3.32", ent);
        // medusa 整串 Go 向量退役（2026-10-29）：向量里的设备描述是抓样那台
        // Redmi 的冻结快照，deviceProto 已对齐注册档案＋时间戳取签名时刻，
        // 整串不再有固定值可对（login-medusa-align-20261029.md）。算法锚点
        // 靠上面的 keyHash/xmxor 向量；此处断言同输入确定性＋字段级对齐。
        check("medusa 同熵同刻确定性（整串两产一致）",
                medusa.equals(medusa2) && medusa.length() > 100,
                "实际长度=" + medusa.length());
        Map<Integer, Object> dp = decodeProto(HgSign.deviceProto("7100000000000011", 1700000000123L));
        // 指纹 f5 自 2026-10-29 根治起按档案种子派生（hongguo-profile-
        // rootfix-20261029.md）：断言=与档案派生口同值且非抓样冻结值。
        check("deviceProto 身份号=入参 deviceId 且指纹 f5=档案派生",
                "7100000000000011".equals(dp.get(4))
                        && HgDevice.fpToken5().equals(dp.get(5))
                        && !"Ai6svO3PyrwDOUSmO6ZcResxu".equals(dp.get(5)),
                "实际 f4=" + dp.get(4) + " f5=" + dp.get(5));
        // persona 自根治起随档案掷定（池内真机元组），不再钉死某一台：
        // 断言=与档案访问口逐项同值（哪台由档案说了算）。
        check("deviceProto 机型对齐注册档案（f30/32/33=HgDevice 机型）",
                HgDevice.deviceModel().equals(dp.get(30)) && HgDevice.deviceModel().equals(dp.get(32))
                        && HgDevice.deviceModel().equals(dp.get(33)),
                "实际 f30=" + dp.get(30));
        check("deviceProto 品牌/厂商对齐注册档案（f31/34/35/36=HgDevice 品牌）",
                HgDevice.deviceBrand().equals(dp.get(31)) && HgDevice.deviceBrand().equals(dp.get(34))
                        && HgDevice.deviceBrand().equals(dp.get(35)) && HgDevice.deviceBrand().equals(dp.get(36)),
                "实际 f31=" + dp.get(31));
        check("deviceProto 系统版本对齐注册档案（f22=HgDevice 系统版本）",
                HgDevice.deviceOsVersion().equals(dp.get(22)),
                "实际 f22=" + dp.get(22));
        check("deviceProto 时间戳=入参时刻（f25/26/27 同值、f28 +2ms）",
                Long.valueOf(1700000000123L).equals(dp.get(25))
                        && Long.valueOf(1700000000123L).equals(dp.get(26))
                        && Long.valueOf(1700000000123L).equals(dp.get(27))
                        && Long.valueOf(1700000000125L).equals(dp.get(28)),
                "实际 f25=" + dp.get(25) + " f28=" + dp.get(28));
        check("deviceProto 指纹浮点=档案派生（f16 与派生口同值、量级贴抓样）",
                Float.valueOf(HgDevice.fpSensor(0)).equals(dp.get(16))
                        && dp.get(16) instanceof Float
                        && (Float) dp.get(16) > 255.24993896484375f * 0.9f
                        && (Float) dp.get(16) < 255.24993896484375f * 1.1f,
                "实际 f16=" + dp.get(16));

        // 2) 签名确定性：同熵同刻同体 → 两次产出逐字一致；stub 与 body MD5 对齐；
        //    查询串尾 ts/_rticket 齐。
        List<String[]> pairs = HgDevice.deviceQueryPairs();
        byte[] body = HgRequests.playbackBody("7694655440692448281").toString()
                .getBytes(StandardCharsets.UTF_8);
        HgSign.Entropy fixed = new HgSign.Entropy();
        fixed.envLaunch = 100;
        fixed.envPid = 10001;
        fixed.nowSecs = 1700000000L;
        fixed.messageRandom = 1;
        fixed.random = 1;
        fixed.packedRandom = 1;
        fixed.gorgonRandom = 0x1234;
        fixed.heliosRandom = 1;
        HgSign.Signed s1 = HgSign.signPost(HgDevice.PATH_PLAYBACK, pairs, 1700000000123L,
                "7100000000000011", HgDevice.VERSION_NAME, body, fixed);
        HgSign.Signed s2 = HgSign.signPost(HgDevice.PATH_PLAYBACK, pairs, 1700000000123L,
                "7100000000000011", HgDevice.VERSION_NAME, body, fixed);
        check("签名确定性（query 逐字一致）", s1.query.equals(s2.query), null);
        check("签名确定性（头集逐字一致）", s1.headers.equals(s2.headers), null);
        check("签名头齐（gorgon/argus/helios/medusa/ladon/khronos/stub）",
                s1.headers.containsKey("x-gorgon") && s1.headers.containsKey("x-argus")
                        && s1.headers.containsKey("x-helios") && s1.headers.containsKey("x-medusa")
                        && s1.headers.containsKey("x-ladon") && s1.headers.containsKey("x-khronos")
                        && s1.headers.containsKey("x-ss-stub"), "实际=" + s1.headers.keySet());
        check("x-ss-stub = MD5(body) 大写",
                HgSign.contentMd5Hex(body).equals(s1.headers.get("x-ss-stub")),
                "实际=" + s1.headers.get("x-ss-stub"));
        // 注：_rticket 不能钉死 1700000000123——SM3 分支回路（对照 Go signRequest
        // 原码，branch==1 时 ticket 自增至多 32 次）会按 query 内容推进它，
        // 本向量实测推进 2–4 步。旧断言钉死原值与 Go 口径冲突（1.4.54 基线
        // 同样 FAIL），2026-10-28 改为回路不变式：ts 钉秒、_rticket 落在
        // [基准, 基准+32) 且与 ts 同秒。
        long rticket = -1;
        try {
            int at = s1.query.lastIndexOf("&_rticket=");
            rticket = Long.parseLong(s1.query.substring(at + "&_rticket=".length()));
        } catch (Exception ignored) {
        }
        check("查询串有序：iid 起头＋ts/_rticket 收尾（分支回路内）",
                s1.query.startsWith("iid=") && s1.query.contains("&device_id=")
                        && s1.query.contains("&ts=1700000000&_rticket=")
                        && rticket >= 1700000000123L && rticket < 1700000000123L + 32,
                "实际尾=" + s1.query.substring(Math.max(0, s1.query.length() - 60)));

        // 2b) GET 签名（feed 频道面用）：path 可传、确定性、无 body 时不带 x-ss-stub
        HgSign.Signed g1 = HgSign.signGet(HgDevice.PATH_BOOKMALL_TAB, pairs, 1700000000123L,
                "7100000000000011", HgDevice.VERSION_NAME, fixed);
        HgSign.Signed g2 = HgSign.signGet(HgDevice.PATH_BOOKMALL_TAB, pairs, 1700000000123L,
                "7100000000000011", HgDevice.VERSION_NAME, fixed);
        check("GET 签名确定性（query/头集逐字一致）",
                g1.query.equals(g2.query) && g1.headers.equals(g2.headers), null);
        check("GET 签名头齐且无 x-ss-stub",
                g1.headers.containsKey("x-gorgon") && g1.headers.containsKey("x-medusa")
                        && !g1.headers.containsKey("x-ss-stub"),
                "实际=" + g1.headers.keySet());

        // 2c) argus/ladon 身份（2026-10-29 路 A）：解开 signPost 的 x-argus/x-ladon
        //     断言红果身份；再解四参 argusLadon（评论链缺省）断言番茄身份逐字保留。
        //     解密锚在本测试内逆向实现（容器 AES＋Simon/ladon 逆轮），密钥常量照
        //     签名器原值钉死——签名器改密钥/身份而测试不跟随，解密或断言当场红。
        Map<Integer, Object> af = decryptArgus(s1.headers.get("x-argus"));
        check("argus 身份=红果（f4=8662/f7=版本名/f8=v04.06.04-ml-android/f9=67503104）",
                "8662".equals(af.get(4)) && HgDevice.VERSION_NAME.equals(af.get(7))
                        && "v04.06.04-ml-android".equals(af.get(8))
                        && Long.valueOf(67503104L).equals(af.get(9)),
                "实际 f4=" + af.get(4) + " f7=" + af.get(7) + " f8=" + af.get(8) + " f9=" + af.get(9));
        check("argus 无 f5 device_id、f6 许可常量保留（1611921764）",
                !af.containsKey(5) && "1611921764".equals(af.get(6)),
                "实际 f6=" + af.get(6) + " 含f5=" + af.containsKey(5));
        String ladonPlain = decryptLadon(s1.headers.get("x-ladon"));
        check("ladon 明文尾=红果 aid（…-1611921764-8662）",
                ladonPlain != null && ladonPlain.endsWith("-1611921764-8662"),
                "实际=" + ladonPlain);
        byte[] tn = new byte[11];
        for (int i = 0; i < tn.length; i++) tn[i] = (byte) (i + 1);
        String[] tal = com.fongmi.android.tv.api.FqCommentSigner.argusLadon("a=1&b=2", null, 1700000000L, tn);
        Map<Integer, Object> tf = decryptArgus(tal[0]);
        check("评论链 argus 缺省身份=番茄逐字保留（3019/6.8.1.32/v04.07.01-ml-android/135135744）",
                "3019".equals(tf.get(4)) && "6.8.1.32".equals(tf.get(7))
                        && "v04.07.01-ml-android".equals(tf.get(8))
                        && Long.valueOf(135135744L).equals(tf.get(9)),
                "实际 f4=" + tf.get(4) + " f7=" + tf.get(7) + " f8=" + tf.get(8) + " f9=" + tf.get(9));
        String tLadonPlain = decryptLadon(tal[1]);
        check("评论链 ladon 明文尾=番茄 aid（…-1611921764-3019）",
                tLadonPlain != null && tLadonPlain.endsWith("-1611921764-3019"),
                "实际=" + tLadonPlain);

        // 3) 注册持久化：档案派生 → 回填真 id → 清缓存重读仍在（Prefers 桩为进程内存储）。
        HgDevice.Profile p1 = HgDevice.profile();
        check("档案已派生（cdid/openudid 在位）",
                p1.cdid != null && p1.cdid.length() == 36 && p1.openUdid != null && p1.openUdid.length() == 16,
                "cdid=" + p1.cdid);
        check("档案带 persona 与指纹种子（根治：生成时掷定）",
                p1.model != null && !p1.model.isEmpty() && p1.osApi > 0
                        && p1.fpSeed != null && p1.fpSeed.length() == 64,
                "model=" + p1.model + " osApi=" + p1.osApi + " seedLen="
                        + (p1.fpSeed == null ? -1 : p1.fpSeed.length()));
        check("未注册时 registered=false", !p1.registered(), null);
        HgDevice.saveRegistered("4175318314922028", "4175318314123324");
        HgDevice.invalidateCache();
        HgDevice.Profile p2 = HgDevice.profile();
        check("注册持久化（重读后真 id 在位且 registered）",
                p2.registered() && "4175318314922028".equals(p2.deviceId)
                        && "4175318314123324".equals(p2.installId) && p1.cdid.equals(p2.cdid),
                "实际=" + p2.deviceId + "/" + p2.installId);
        check("persona 与指纹种子跨重读稳定（根治：落盘后终身不变）",
                p1.model.equals(p2.model) && p1.osApi == p2.osApi && p1.fpSeed.equals(p2.fpSeed)
                        && HgDevice.fpToken5().length() == 25,
                "model=" + p2.model + " seed=" + (p1.fpSeed.equals(p2.fpSeed) ? "same" : "DIFF"));
        check("有序查询串带真 id",
                HgDevice.deviceQueryPairs().get(0)[1].equals("4175318314123324")
                        && HgDevice.deviceQueryPairs().get(1)[1].equals("4175318314922028"), null);

        // 4) 注册报文形状：TTEncrypt 信封头 74 63 05 10 00 00＋密文 16 对齐；
        //    parseRegister 字符串/数字两收、"0" 判死。
        byte[] regBody = HgDevice.buildRegisterBody(1700000000000L);
        check("注册体信封头",
                regBody.length > 38 && (regBody[0] & 0xFF) == 0x74 && (regBody[1] & 0xFF) == 0x63
                        && regBody[2] == 0x05 && regBody[3] == 0x10 && regBody[4] == 0 && regBody[5] == 0
                        && (regBody.length - 38) % 16 == 0,
                "实际长度=" + regBody.length);
        String[] ids = HgDevice.parseRegister("{\"device_id_str\":\"4175318314922028\",\"install_id_str\":4175318314123324}");
        check("parseRegister 数字 install_id 可收",
                ids != null && "4175318314922028".equals(ids[0]) && "4175318314123324".equals(ids[1]), null);
        check("parseRegister id=0 判死",
                HgDevice.parseRegister("{\"device_id_str\":\"0\",\"install_id_str\":\"1\"}") == null, null);

        // 5) 借条 3（模型级保护判定）：真回包 resp-004 全档受保护、钥匙模型级推出且对等；
        //    未保护条目免钥匙造得出规格；受保护但钥匙坏 → 规格判死。
        String resp4 = Files.readString(Paths.get(
                "/home/hatch/workspace/hongguo/openhongguo-probe/resp-004.json"));
        HgVideoModel m = HgParser.parseSingleVideoModel(resp4, "7694655440692448281");
        check("resp-004 解析非空且 5 档", m != null && m.getEntries().size() == 5,
                "实际=" + (m == null ? -1 : m.getEntries().size()));
        check("resp-004 模型受保护", m != null && m.isProtected(), null);
        check("resp-004 模型级 DEK 与定罪账对等",
                m != null && "898dca2b79065fd4e0e2afea35153be3".equals(m.deriveDekHex()),
                "实际=" + (m == null ? null : m.deriveDekHex()));
        String plainJson = "{\"video_id\":\"v0\",\"video_duration\":10,\"video_list\":[{"
                + "\"video_meta\":{\"definition\":\"360p\",\"codec_type\":\"bytevc1\"},"
                + "\"main_url\":\"https://example.com/a.mp4\",\"size\":100}]}";
        com.google.gson.JsonObject vmObj = com.google.gson.JsonParser.parseString(plainJson).getAsJsonObject();
        HgVideoModel open = HgParser.parseVideoModel(vmObj, "x");
        check("未保护模型 isProtected=false 且无钥匙",
                open != null && !open.isProtected() && open.deriveDekHex() == null, null);
        HgStreamSpec openSpec = open == null ? null
                : HgStreamSpec.fromEntry("https://example.com/a.mp4", open.firstPlayableEntry(), null, "x");
        check("未保护条目免钥匙直喂（规格成立、dekHex=null）",
                openSpec != null && openSpec.dekHex == null, null);
        String badKeyJson = "{\"video_id\":\"v0\",\"video_duration\":10,\"video_list\":[{"
                + "\"video_meta\":{\"definition\":\"360p\",\"codec_type\":\"bytevc1\"},"
                + "\"main_url\":\"https://example.com/a.mp4\",\"size\":100,"
                + "\"encrypt_info\":{\"spade_a\":\"!!!bad!!!\",\"kid\":\"k\",\"encryption_method\":\"cenc-aes-ctr\"}}]}";
        com.google.gson.JsonObject badObj = com.google.gson.JsonParser.parseString(badKeyJson).getAsJsonObject();
        HgVideoModel bad = HgParser.parseVideoModel(badObj, "x");
        HgStreamSpec badSpec = bad == null ? null
                : HgStreamSpec.fromEntry("https://example.com/a.mp4", bad.firstPlayableEntry(), bad.deriveDekHex(), "x");
        check("受保护但钥匙推不出 → 规格判死（E4 口径）",
                bad != null && bad.isProtected() && badSpec == null, null);

        // 6) 详情回包（resp-003）：86 集、首集 vid 对位、批量解析同形。
        String resp3 = Files.readString(Paths.get(
                "/home/hatch/workspace/hongguo/openhongguo-probe/resp-003.json"));
        HgSeriesDetail d = HgParser.parseVideoDetail(resp3);
        check("resp-003 详情 86 集",
                d != null && d.getEpisodes().size() == 86,
                "实际=" + (d == null ? -1 : d.getEpisodes().size()));
        check("resp-003 首集 vid 与标题",
                d != null && !d.getEpisodes().isEmpty()
                        && "7694655440692448281".equals(d.getEpisodes().get(0).getVideoId())
                        && d.getTitle() != null && d.getTitle().contains("王妈"),
                "实际=" + (d == null ? null : d.getTitle()));
        com.google.gson.JsonObject root3 = com.google.gson.JsonParser.parseString(resp3).getAsJsonObject();
        com.google.gson.JsonObject batchWrap = new com.google.gson.JsonObject();
        com.google.gson.JsonObject dataMap = new com.google.gson.JsonObject();
        dataMap.add("7694653385668037657", root3.getAsJsonObject("data"));
        batchWrap.add("data", dataMap);
        Map<String, HgSeriesDetail> batch = HgParser.parseSeriesBatch(batchWrap.toString());
        check("批量详情解析同形（1 剧 86 集）",
                batch.size() == 1 && batch.values().iterator().next().getEpisodes().size() == 86,
                "实际=" + batch.size());

        System.out.println(fail == 0 ? "ALL PASS" : ("FAILURES=" + fail));
        if (fail > 0) System.exit(1);
    }

    /** 最小 proto 解码（SInt 的 varint 先 zigzag 解码→Long、LEN→String、fixed32→Float），只供字段级断言。 */
    static Map<Integer, Object> decodeProto(byte[] data) {
        Map<Integer, Object> out = new HashMap<>();
        int[] pos = {0};
        while (pos[0] < data.length) {
            long key = readVarint(data, pos);
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7);
            if (wire == 0) {
                long raw = readVarint(data, pos);
                out.put(field, (raw >>> 1) ^ -(raw & 1L));
            } else if (wire == 2) {
                int len = (int) readVarint(data, pos);
                out.put(field, new String(data, pos[0], len, StandardCharsets.UTF_8));
                pos[0] += len;
            } else if (wire == 5) {
                int bits = (data[pos[0]] & 0xFF) | ((data[pos[0] + 1] & 0xFF) << 8)
                        | ((data[pos[0] + 2] & 0xFF) << 16) | ((data[pos[0] + 3] & 0xFF) << 24);
                out.put(field, Float.intBitsToFloat(bits));
                pos[0] += 4;
            } else if (wire == 1) {
                pos[0] += 8;
            } else {
                break;
            }
        }
        return out;
    }

    // ---------- argus/ladon 解密锚（逆 FqCommentSigner 的容器与轮函数） ----------

    static final byte[] ARGUS_SECRET_T = unhexT("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c");
    static final byte[] ARGUS_MASK_T = unhexT("d04ffdffd04ffdff");
    static final long SIMON_SEED_T = 0x3dc94c3a046d678bL;

    static byte[] unhexT(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    static byte[] md5T(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("MD5").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static long wordT(byte[] b, int at) {
        return java.nio.ByteBuffer.wrap(b, at, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong();
    }

    static byte[] leT(long n) {
        return java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(n).array();
    }

    static byte[] concatT(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    /** 解 x-argus → 明文字段表（varint 取原值不 zigzag，LEN 取串）。 */
    static Map<Integer, Object> decryptArgus(String b64) throws Exception {
        byte[] all = java.util.Base64.getDecoder().decode(b64);
        byte[] ct = java.util.Arrays.copyOfRange(all, 2, all.length);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(md5T(java.util.Arrays.copyOfRange(ARGUS_SECRET_T, 0, 16)), "AES"),
                new javax.crypto.spec.IvParameterSpec(md5T(java.util.Arrays.copyOfRange(ARGUS_SECRET_T, 16, 32))));
        byte[] container = cipher.doFinal(ct);
        byte[] mid = java.util.Arrays.copyOfRange(container, 9, container.length - 2);
        byte[] joined = new byte[mid.length];
        for (int i = 0; i < mid.length; i++) joined[i] = mid[mid.length - 1 - i];
        byte[] scrambled = java.util.Arrays.copyOfRange(joined, 8, joined.length);
        byte[] enc = new byte[scrambled.length];
        for (int i = 0; i < enc.length; i++) enc[i] = (byte) ((scrambled[i] & 255) ^ (ARGUS_MASK_T[i % 8] & 255));
        byte[] derived = HgSm3.sum(concatT(ARGUS_SECRET_T, unhexT("3ccc567b"), ARGUS_SECRET_T));
        long[] keys = new long[72];
        for (int i = 0; i < 4; i++) keys[i] = wordT(derived, i * 8);
        for (int i = 4; i < 72; i++) {
            long x = Long.rotateRight(keys[i - 1], 3) ^ keys[i - 3];
            keys[i] = ~keys[i - 4] ^ x ^ Long.rotateRight(x, 1) ^ ((SIMON_SEED_T >>> ((i - 4) % 62)) & 1) ^ 3;
        }
        java.io.ByteArrayOutputStream plain = new java.io.ByteArrayOutputStream();
        for (int at = 0; at + 16 <= enc.length; at += 16) {
            long a = wordT(enc, at), b = wordT(enc, at + 8);
            for (int i = 71; i >= 0; i--) {
                long prevB = a;
                long prevA = b ^ (Long.rotateLeft(a, 1) & Long.rotateLeft(a, 8)) ^ Long.rotateLeft(a, 2) ^ keys[i];
                a = prevA;
                b = prevB;
            }
            byte[] la = leT(a), lb = leT(b);
            plain.write(la, 0, 8);
            plain.write(lb, 0, 8);
        }
        byte[] msg = plain.toByteArray();
        int padLen = msg[msg.length - 1] & 255;
        return parsePlainProto(java.util.Arrays.copyOf(msg, msg.length - padLen));
    }

    /** 解 x-ladon → 明文串（ts-许可-aid）。 */
    static String decryptLadon(String b64) {
        byte[] all = java.util.Base64.getDecoder().decode(b64);
        byte[] salt = java.util.Arrays.copyOfRange(all, 0, 4);
        byte[] ascii = hexStrT(md5T(concatT(salt, "8662".getBytes(StandardCharsets.UTF_8))))
                .getBytes(StandardCharsets.UTF_8);
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        queue.add(wordT(ascii, 16));
        queue.add(wordT(ascii, 24));
        long left = wordT(ascii, 0);
        long right = wordT(ascii, 8);
        long[] keys = new long[34];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = left;
            long next = (Long.rotateRight(right, 8) + left) ^ (long) i;
            queue.add(next);
            left = next ^ Long.rotateLeft(left, 3);
            right = queue.removeFirst();
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int at = 4; at + 16 <= all.length; at += 16) {
            long a = wordT(all, at), b = wordT(all, at + 8);
            for (int i = 33; i >= 0; i--) {
                long aOld = Long.rotateRight(a ^ b, 3);
                long bOld = Long.rotateLeft((b ^ keys[i]) - aOld, 8);
                a = aOld;
                b = bOld;
            }
            byte[] la = leT(a), lb = leT(b);
            out.write(la, 0, 8);
            out.write(lb, 0, 8);
        }
        byte[] msg = out.toByteArray();
        int padLen = msg[msg.length - 1] & 255;
        return new String(msg, 0, msg.length - padLen, StandardCharsets.UTF_8);
    }

    static String hexStrT(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return sb.toString();
    }

    /** 明文 proto 解析（varint 不 zigzag），只供 argus 字段断言。 */
    static Map<Integer, Object> parsePlainProto(byte[] data) {
        Map<Integer, Object> out = new HashMap<>();
        int[] pos = {0};
        while (pos[0] < data.length) {
            long key = readVarint(data, pos);
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7);
            if (wire == 0) {
                out.put(field, readVarint(data, pos));
            } else if (wire == 2) {
                int len = (int) readVarint(data, pos);
                out.put(field, new String(data, pos[0], len, StandardCharsets.UTF_8));
                pos[0] += len;
            } else if (wire == 5) {
                pos[0] += 4;
            } else if (wire == 1) {
                pos[0] += 8;
            } else {
                break;
            }
        }
        return out;
    }

    static long readVarint(byte[] data, int[] pos) {
        long v = 0;
        int shift = 0;
        while (true) {
            int b = data[pos[0]++] & 0xFF;
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
        }
    }
}