import com.fongmi.android.tv.hongguo.book.HgBookClient;
import com.fongmi.android.tv.hongguo.book.HgNovelCrypto;
import com.fongmi.android.tv.legado.HgBookBridge;
import com.github.catvod.utils.Prefers;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 红果小说加密链离线断言：注册体构造的字节级自洽（小端打包→固定密钥可解回）、
 * keyA 派生与规格 §2.2 的 k() 格式互逆、章节密文解密＋gzip 往返、XHTML 转文本、
 * 书桥 bookUrl 编解码。服务端侧的真值已由规格的本机实拉定版，这里只验 Java 移植
 * 与规格构造是否逐字节一致。
 */
public class HgBookCryptoTest {
    static int fail = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (detail == null ? "" : " | " + detail));
        if (!ok) fail++;
    }

    private static final String FIXED = "ac25c67ddd8f38c1b37a2348828e222e";

    static byte[] hexDecode(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    static byte[] aes(int mode, byte[] key, byte[] iv, byte[] in) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(in);
    }

    static long le64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }

    public static void main(String[] args) throws Exception {
        // 1) 注册体构造：Base64 解开后 seed 前缀原样、密文以固定密钥＋seed IV 解回 x，
        //    x 的两段 LE64 与入参逐值相等（端序错了这里必然不等）。
        long deviceId = 3844551704897538L;
        long installId = 2844551704897539L;
        String seed = "12345678-90ab-cd";
        String content = HgNovelCrypto.buildRegisterContent(deviceId, installId, seed);
        byte[] packed = android.util.Base64.decode(content, android.util.Base64.DEFAULT);
        String seedBack = new String(packed, 0, 16, StandardCharsets.UTF_8);
        check("注册体 seed 前缀", seed.equals(seedBack), seedBack);
        byte[] ct = new byte[packed.length - 16];
        System.arraycopy(packed, 16, ct, 0, ct.length);
        byte[] x = aes(Cipher.DECRYPT_MODE, hexDecode(FIXED),
                seed.getBytes(StandardCharsets.UTF_8), ct);
        check("注册体 x 小端 device_id", le64(x, 0) == deviceId, String.valueOf(le64(x, 0)));
        check("注册体 x 小端 install_id", le64(x, 8) == installId, String.valueOf(le64(x, 8)));

        // 2) keyA 派生：按规格 k() 格式伪造服务端回包（IV16 ‖ 固定密钥加密的 keyA），
        //    deriveKeyA 须原样还原大写 HEX。
        byte[] keyA = hexDecode("00112233445566778899aabbccddeeff");
        byte[] iv = hexDecode("0f0e0d0c0b0a09080706050403020100");
        byte[] enc = aes(Cipher.ENCRYPT_MODE, hexDecode(FIXED), iv, keyA);
        byte[] pack2 = new byte[16 + enc.length];
        System.arraycopy(iv, 0, pack2, 0, 16);
        System.arraycopy(enc, 0, pack2, 16, enc.length);
        String keyB64 = android.util.Base64.encodeToString(pack2, android.util.Base64.NO_WRAP);
        String derived = HgNovelCrypto.deriveKeyA(keyB64);
        check("keyA 派生", "00112233445566778899AABBCCDDEEFF".equals(derived), derived);

        // 3) 章节解密往返：以 keyA 按同格式加密一段 gzip(XHTML)，decryptContent＋gunzip 还原。
        Prefers.put("hg_book_key_a", derived);
        String xhtml = "<article><h1 class=\"chapterTitle1\">第一章</h1>"
                + "<p idx=\"0\"><blk>你好，红果。</blk></p>"
                + "<p idx=\"1\"><blk>第二段 &amp; 结尾</blk></p></article>";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(bos);
        gz.write(xhtml.getBytes(StandardCharsets.UTF_8));
        gz.close();
        byte[] iv3 = hexDecode("000102030405060708090a0b0c0d0e0f");
        byte[] enc3 = aes(Cipher.ENCRYPT_MODE, keyA, iv3, bos.toByteArray());
        byte[] pack3 = new byte[16 + enc3.length];
        System.arraycopy(iv3, 0, pack3, 0, 16);
        System.arraycopy(enc3, 0, pack3, 16, enc3.length);
        String chapterB64 = android.util.Base64.encodeToString(pack3, android.util.Base64.NO_WRAP);
        byte[] plain = HgNovelCrypto.gunzip(HgNovelCrypto.decryptContent(chapterB64));
        check("章节解密＋gunzip", xhtml.equals(new String(plain, StandardCharsets.UTF_8)), null);

        // 4) XHTML 转纯文本：段落断行、标签剥离、实体解开。
        String text = HgBookClient.xhtmlToText(xhtml);
        check("XHTML 转文本", text.contains("你好，红果。")
                && text.contains("第二段 & 结尾")
                && !text.contains("<blk>"), text.replace("\n", "⏎"));

        // 5) 书桥 URL 编解码。
        String cu = HgBookBridge.chapterUrl("7596021152615517209", "7596021152615517210");
        check("chapterUrl 编解码", "7596021152615517209".equals(HgBookBridge.bookIdOf(cu))
                && "7596021152615517210".equals(HgBookBridge.itemIdOf(cu)), cu);

        // 6) 听书钥匙向量（dig-audio-cenc-key-20261028 本机真拉实证）：音频
        //    encryption_key 与短剧 spade_a 同套置乱，树内现成 SpadeKey 必须逐值推出。
        com.fongmi.android.tv.hongguo.crypto.SpadeKey.Result k1 =
                com.fongmi.android.tv.hongguo.crypto.SpadeKey.derive(
                        "krwc+0y0AvpPgjPSeJw05WSvNOFknCzVZ5Ir7memK+hVoB2MjA==");
        check("听书 TTS 第1章钥匙", "3199775be9374a74978f01204d3035d2".equals(k1.key)
                && "1".equals(k1.out2), String.valueOf(k1.key) + "/" + k1.out2);
        com.fongmi.android.tv.hongguo.crypto.SpadeKey.Result k2 =
                com.fongmi.android.tv.hongguo.crypto.SpadeKey.derive(
                        "krwc82G7L8JghRv4ULIA/X20Ms15hyn7VrAs+FeDBfxJtQGFhQ==");
        check("听书真人 第1章钥匙", "39f27b71df3387d38b338df4b0bc9f23".equals(k2.key)
                && "1".equals(k2.out2), String.valueOf(k2.key) + "/" + k2.out2);

        System.out.println(fail == 0 ? "ALL PASS" : ("FAILURES=" + fail));
        if (fail > 0) System.exit(1);
    }
}
