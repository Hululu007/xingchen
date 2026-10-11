package com.fongmi.android.tv.api.hk;

import java.util.Locale;

/**
 * 8.83 com.example.hikerview.service.parser.RC4 的等价实现（标准 RC4）。
 * JSEngine 把 rc4 实例注入 Rhino（putProperty "rc4"），规则经 JS 调用其方法，
 * 方法签名与 8.83 原文一致：static dec/decode/enc，实例 encode。
 */
public class HkRc4 {

    /** 8.83 RC4.dec(String hexData, String key, String charset)：hex 解码后 RC4 解密。 */
    public static String dec(String hexData, String key, String charset) {
        if (hexData == null || key == null) return "";
        try {
            byte[] out = rc4Base(hexToBytes(hexData), initKey(key, charset));
            return new String(out, charset);
        } catch (Exception e) {
            return "";
        }
    }

    public static String decode(String data, String key) {
        return dec(data, key, "UTF-8");
    }

    public static String decode(String data, String key, String charset) {
        return dec(data, key, charset);
    }

    /** 8.83 RC4.enc(String data, String key, String charset)：RC4 加密后转大写 hex。 */
    public static String enc(String data, String key, String charset) {
        if (data == null || key == null) return "";
        try {
            byte[] out = rc4Base(data.getBytes(charset), initKey(key, charset));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString().toUpperCase(Locale.US);
        } catch (Exception e) {
            return "";
        }
    }

    /** 8.83 RC4.encode(String, String)：实例方法，供 Rhino 注入的 rc4 对象调用。 */
    public String encode(String data, String key) {
        return enc(data, key, "UTF-8");
    }

    public String encode(String data, String key, String charset) {
        return enc(data, key, charset);
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        byte[] raw = hex.getBytes();
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit((char) raw[i * 2], 16);
            int lo = Character.digit((char) raw[i * 2 + 1], 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** 8.83 RC4.initKey：KSA，key 按 charset 取字节，空 key 返回空数组。 */
    private static byte[] initKey(String key, String charset) {
        try {
            byte[] keyBytes = key.getBytes(charset);
            byte[] s = new byte[256];
            for (int i = 0; i < 256; i++) s[i] = (byte) i;
            if (keyBytes.length == 0) return new byte[0];
            int j = 0, k = 0;
            for (int i = 0; i < 256; i++) {
                j = (j + (keyBytes[k] & 0xFF) + (s[i] & 0xFF)) & 0xFF;
                byte t = s[i];
                s[i] = s[j];
                s[j] = t;
                k = (k + 1) % keyBytes.length;
            }
            return s;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /** 8.83 RC4.RC4Base：标准 PRGA，加解密同一函数。 */
    private static byte[] rc4Base(byte[] data, byte[] s) {
        byte[] out = new byte[data.length];
        int i = 0, j = 0;
        for (int n = 0; n < data.length; n++) {
            i = (i + 1) & 0xFF;
            j = (j + (s[i] & 0xFF)) & 0xFF;
            byte t = s[i];
            s[i] = s[j];
            s[j] = t;
            out[n] = (byte) (data[n] ^ s[((s[i] & 0xFF) + (t & 0xFF)) & 0xFF]);
        }
        return out;
    }
}
