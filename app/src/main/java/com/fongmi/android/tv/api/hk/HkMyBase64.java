package com.fongmi.android.tv.api.hk;

import android.util.Base64;

import java.nio.charset.StandardCharsets;

/**
 * 8.83 com.example.hikerview.utils.encrypt.MyBase64 的等价实现
 *（原文本就是 android.util.Base64 的改名拷贝）。
 * JSEngine 把 myBase64 实例注入 Rhino（putProperty "_base64"），规则如
 * CryptoUtil.Data.parseBase64(s, _base64.NO_WRAP) 依赖其存在。
 * 常量与方法签名均与 8.83 原文一致，实现直接委托 android.util.Base64。
 */
public class HkMyBase64 {

    public final int DEFAULT = Base64.DEFAULT;
    public final int NO_PADDING = Base64.NO_PADDING;
    public final int NO_WRAP = Base64.NO_WRAP;
    public final int CRLF = Base64.CRLF;
    public final int URL_SAFE = Base64.URL_SAFE;
    public final int NO_CLOSE = Base64.NO_CLOSE;

    public byte[] decode(String str, int flags) {
        return Base64.decode(str, flags);
    }

    public byte[] decode(byte[] input, int flags) {
        return Base64.decode(input, flags);
    }

    public byte[] decode(byte[] input, int offset, int len, int flags) {
        return Base64.decode(input, offset, len, flags);
    }

    public String decodeToString(String str, int flags) {
        return new String(decode(str, flags), StandardCharsets.UTF_8);
    }

    public String decodeToString(byte[] input, int flags) {
        return new String(decode(input, flags), StandardCharsets.UTF_8);
    }

    public byte[] encode(byte[] input, int flags) {
        return Base64.encode(input, flags);
    }

    public byte[] encode(byte[] input, int offset, int len, int flags) {
        return Base64.encode(input, offset, len, flags);
    }

    public String encodeToString(byte[] input, int flags) {
        return Base64.encodeToString(input, flags);
    }

    public String encodeToString(byte[] input, int offset, int len, int flags) {
        return Base64.encodeToString(input, offset, len, flags);
    }
}
