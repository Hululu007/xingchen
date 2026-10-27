package com.fongmi.android.tv.hongguo.proxy;

import java.io.IOException;
import java.io.InputStream;

/**
 * 把一条 {@link HgCencStream} 的 [start,end] 明文区间包装成 InputStream，
 * 供本地服务器（NanoHTTPD）以固定长度响应出流。
 *
 * <p>内部只持一块 64KB 复用缓冲：每次填充只按当前区间向源取数＋解密，
 * 不预读后续内容——播放器停读／关连接即停工，不留整集落地。</p>
 */
public final class HgRangeInputStream extends InputStream {

    private static final int CHUNK = 64 * 1024;

    private final HgCencStream stream;
    private final long endExclusive;
    private long pos;
    private final byte[] chunk = new byte[CHUNK];
    private int chunkOff;
    private int chunkLen;
    private boolean eof;

    public HgRangeInputStream(HgCencStream stream, long start, long endExclusive) {
        this.stream = stream;
        this.pos = start;
        this.endExclusive = endExclusive;
    }

    private boolean fill() throws IOException {
        if (eof) return false;
        if (pos >= endExclusive) {
            eof = true;
            return false;
        }
        int want = (int) Math.min(CHUNK, endExclusive - pos);
        int n = stream.readClear(pos, chunk, 0, want);
        if (n < 0) {
            eof = true;
            return false;
        }
        chunkOff = 0;
        chunkLen = n;
        pos += n;
        return true;
    }

    @Override
    public int read() throws IOException {
        if (chunkOff >= chunkLen && !fill()) return -1;
        return chunk[chunkOff++] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (chunkOff >= chunkLen && !fill()) return -1;
        int n = Math.min(len, chunkLen - chunkOff);
        System.arraycopy(chunk, chunkOff, b, off, n);
        chunkOff += n;
        return n;
    }

    @Override
    public void close() {
        eof = true; // 流本体由注册表回收，这里不关（多条 Range 请求共用一条流）
    }
}
