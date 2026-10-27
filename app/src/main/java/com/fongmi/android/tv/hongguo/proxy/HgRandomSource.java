package com.fongmi.android.tv.hongguo.proxy;

import java.io.Closeable;
import java.io.IOException;

/**
 * 红果密流的随机读源：解密层只靠这一个口取数。
 *
 * <p>实现有三家：{@link HgHttpRangeSource}（真 CDN，OkHttp 带 Range 取数）、
 * {@link HgFileSource}（本地文件，离线验与本地密文用）。解密层不关心上游在哪，
 * 只按绝对偏移取字节——这是「moov 在尾时先取尾解析、请求尾部小区间时不顺序
 * 读全文件」能成立的前提。</p>
 */
public interface HgRandomSource extends Closeable {

    /** 上游总字节数；未知时实现应先探明（HTTP 走 Range 0-0 读 Content-Range）。 */
    long length() throws IOException;

    /**
     * 从绝对偏移 pos 起最多读 len 字节进 buf[off..]，返回实际读到的字节数；
     * 已到文件尾返回 -1。允许少读，调用方负责循环读满。
     */
    int readAt(long pos, byte[] buf, int off, int len) throws IOException;

    /** 累计从上游实际取到的字节数（离线验证据／线上诊断用）。 */
    long fetchedBytes();

    /** 累计取数次数。 */
    int fetchCalls();

    @Override
    void close();
}
