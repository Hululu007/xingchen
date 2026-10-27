package com.fongmi.android.tv.hongguo.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 红果播放地址（main_url）解密：逐字复刻 verify-one-episode-20261027.md 第八节第 4 条
 * （libvideodec.so 的 _getDecodedStr 逆向结论，本机已实测解出新鲜明文链并下载校验通过）。
 *
 * <pre>
 * blob = Base64Decode(加密串)，头 4 字节 a8 00 01 00，正文 = blob[4:]
 * d1 = SHA512(keyseed 32 字节)
 * d2 = SHA512(d1 || CONST64)
 * key = d2[0:16]，iv = d2[16:32]
 * 明文 = AES-128-CBC / PKCS7 解密(正文)
 * </pre>
 */
public final class HgUrlCrypto {

    /** .rodata 0x5220 处的 64 字节常量（第八节原文）。 */
    private static final byte[] CONST64 = HgHex.decode(
            "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524" +
            "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58");

    private HgUrlCrypto() {
    }

    /** 加密串是否带红果加密头（a8 00 01 00）；明文 http 链返回 false。 */
    public static boolean isEncryptedUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        if (url.startsWith("http://") || url.startsWith("https://")) return false;
        try {
            byte[] blob = Base64.getDecoder().decode(url);
            return blob.length > 4
                    && (blob[0] & 0xff) == 0xa8 && blob[1] == 0 && blob[2] == 1 && blob[3] == 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 解密一条加密 main_url。
     *
     * @param encryptedBase64 回包里的加密串（前缀 qAAB…）
     * @param keySeedBase64   video_info.data.key_seed（Base64，解码后 32 字节）
     * @return 明文 URL
     */
    public static String decryptUrl(String encryptedBase64, String keySeedBase64) throws Exception {
        byte[] seed = Base64.getDecoder().decode(keySeedBase64);
        return decryptUrl(encryptedBase64, seed);
    }

    public static String decryptUrl(String encryptedBase64, byte[] keySeed) throws Exception {
        byte[] blob = Base64.getDecoder().decode(encryptedBase64);
        if (blob.length <= 4 || (blob[0] & 0xff) != 0xa8 || blob[1] != 0 || blob[2] != 1 || blob[3] != 0) {
            throw new IllegalArgumentException("main_url 加密头不符（期望 a8 00 01 00）");
        }
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        byte[] d1 = sha.digest(keySeed);
        sha.reset();
        sha.update(d1);
        byte[] d2 = sha.digest(CONST64);
        byte[] key = Arrays.copyOfRange(d2, 0, 16);
        byte[] iv = Arrays.copyOfRange(d2, 16, 32);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] plain = cipher.doFinal(blob, 4, blob.length - 4);
        return new String(plain, StandardCharsets.UTF_8);
    }
}
