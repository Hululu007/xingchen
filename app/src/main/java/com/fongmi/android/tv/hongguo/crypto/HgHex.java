package com.fongmi.android.tv.hongguo.crypto;

/** 红果数据层内部十六进制小工具（无外部依赖，纯 JVM 可测）。 */
public final class HgHex {

    private HgHex() {
    }

    public static byte[] decode(String hex) {
        if (hex == null) return new byte[0];
        String s = hex.trim();
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("非法十六进制串");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static String encode(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
