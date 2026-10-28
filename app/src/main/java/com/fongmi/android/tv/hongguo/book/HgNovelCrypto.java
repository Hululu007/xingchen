package com.fongmi.android.tv.hongguo.book;

import android.util.Base64;

import com.fongmi.android.tv.hongguo.api.HgDevice;
import com.github.catvod.utils.Prefers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 红果小说正文内容密钥（规格 dig-audio-novel-source-20261028 §2.2，本机实拉全链打通）：
 * 未注册时正文口只回 content="Invalid"（crypt_status=2），注册是硬门。
 * 注册体 content＝Base64(seed ‖ AES-128-CBC(x))，x＝LE64(device_id)‖LE64(install_id)，
 * 密钥是包内固定 hex、IV 是 seed 补 0 到 16 字符；服务端回 data.key，同固定密钥解出
 * 设备内容密钥 keyA（每设备稳定，注册一次缓存复用）。章节密文同格式：
 * Base64(IV16 ‖ 密文)，keyA 解密后按 compress_status 做 gzip 解压得 XHTML。
 */
public final class HgNovelCrypto {

    private static final String FIXED_KEY_HEX = "ac25c67ddd8f38c1b37a2348828e222e";
    private static final String K_KEY_A = "hg_book_key_a";
    private static final String K_KEY_VER = "hg_book_key_ver";

    private HgNovelCrypto() {
    }

    public static boolean hasKey() {
        return !keyA().isEmpty();
    }

    public static String keyA() {
        String v = Prefers.getString(K_KEY_A, "");
        return v == null ? "" : v;
    }

    public static long keyVer() {
        String v = Prefers.getString(K_KEY_VER, "");
        try {
            return v == null || v.isEmpty() ? 0L : Long.parseLong(v.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 注册内容密钥并缓存 keyA/keyver（同步，调用方在后台线程）。已注册直接返回。 */
    public static void ensureRegistered(HgBookClient client) throws Exception {
        if (hasKey()) return;
        HgDevice.Profile p = HgDevice.profile();
        long deviceId = parseLong(p.deviceId);
        long installId = parseLong(p.installId);
        String seed = UUID.randomUUID().toString().substring(0, 16);
        String content = buildRegisterContent(deviceId, installId, seed);
        com.google.gson.JsonObject data = client.registerKey(content);
        String keyB64 = data.has("key") ? data.get("key").getAsString() : "";
        long keyVer = data.has("keyver") ? data.get("keyver").getAsLong() : 0L;
        if (keyB64.isEmpty()) throw new Exception("内容密钥注册没回 key");
        String keyAHex = deriveKeyA(keyB64);
        Prefers.put(K_KEY_A, keyAHex);
        Prefers.put(K_KEY_VER, String.valueOf(keyVer));
    }

    /**
     * 注册体 content 构造（规格 §2.2，实测小端序通过、大端序被判 verify fail）：
     * Base64(seed.utf8 ‖ AES-128-CBC(x))，x＝LE64(device_id)‖LE64(install_id)，
     * 密钥为固定 hex，IV＝seed 补 '0' 至 16 字符的 UTF-8。
     */
    public static String buildRegisterContent(long deviceId, long installId, String seed)
            throws Exception {
        byte[] x = new byte[16];
        putLe64(x, 0, deviceId);
        putLe64(x, 8, installId);
        StringBuilder ivText = new StringBuilder(seed);
        while (ivText.length() < 16) ivText.append('0');
        byte[] iv = ivText.toString().getBytes(StandardCharsets.UTF_8);
        byte[] f = aesCbc(Cipher.ENCRYPT_MODE, hexDecode(FIXED_KEY_HEX), iv, x);
        byte[] seedBytes = seed.getBytes(StandardCharsets.UTF_8);
        byte[] packed = new byte[seedBytes.length + f.length];
        System.arraycopy(seedBytes, 0, packed, 0, seedBytes.length);
        System.arraycopy(f, 0, packed, seedBytes.length, f.length);
        return Base64.encodeToString(packed, Base64.NO_WRAP);
    }

    /** 服务端回的 data.key → 设备内容密钥 keyA（大写 HEX）：同固定密钥 CBC 解密，前 16 字节作 IV。 */
    public static String deriveKeyA(String keyB64) throws Exception {
        byte[] raw = Base64.decode(keyB64, Base64.DEFAULT);
        if (raw.length <= 16) throw new Exception("内容密钥注册回包太短");
        byte[] iv = new byte[16];
        System.arraycopy(raw, 0, iv, 0, 16);
        byte[] ct = new byte[raw.length - 16];
        System.arraycopy(raw, 16, ct, 0, ct.length);
        byte[] keyABytes = aesCbc(Cipher.DECRYPT_MODE, hexDecode(FIXED_KEY_HEX), iv, ct);
        return hexEncodeUpper(keyABytes);
    }

    /** 章节密文 → 明文字节（未做 gzip 解压，调用方按 compress_status 处理）。 */
    public static byte[] decryptContent(String base64Content) throws Exception {
        String keyA = keyA();
        if (keyA.isEmpty()) throw new Exception("内容密钥还没注册");
        byte[] raw = Base64.decode(base64Content, Base64.DEFAULT);
        if (raw.length <= 16) throw new Exception("章节密文太短");
        byte[] iv = new byte[16];
        System.arraycopy(raw, 0, iv, 0, 16);
        byte[] ct = new byte[raw.length - 16];
        System.arraycopy(raw, 16, ct, 0, ct.length);
        return aesCbc(Cipher.DECRYPT_MODE, hexDecode(keyA), iv, ct);
    }

    public static byte[] gunzip(byte[] data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data));
        byte[] buf = new byte[8192];
        int n;
        while ((n = gz.read(buf)) > 0) out.write(buf, 0, n);
        gz.close();
        return out.toByteArray();
    }

    private static byte[] aesCbc(int mode, byte[] key, byte[] iv, byte[] input) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(input);
    }

    private static void putLe64(byte[] out, int off, long v) {
        for (int i = 0; i < 8; i++) out[off + i] = (byte) (v >>> (8 * i));
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s == null ? "" : s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    static byte[] hexDecode(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    static String hexEncodeUpper(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02X", b & 0xFF));
        return sb.toString();
    }
}
