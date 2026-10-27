package com.fongmi.android.tv.hongguo.proxy;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地文件随机读源：离线验的上游替身，也可供已落盘的密文直接走代理解密。
 * 每次 readAt 都 seek 到绝对偏移，不做顺序缓存——读了多少、读了哪段都有计数，
 * 离线验靠它拿「只请求尾部小区间时没有整文件顺序读」的数字证据。
 */
public final class HgFileSource implements HgRandomSource {

    private final RandomAccessFile raf;
    private final long length;
    private final AtomicLong fetched = new AtomicLong();
    private final AtomicInteger calls = new AtomicInteger();

    public HgFileSource(File file) throws IOException {
        this.raf = new RandomAccessFile(file, "r");
        this.length = raf.length();
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public synchronized int readAt(long pos, byte[] buf, int off, int len) throws IOException {
        if (pos >= length) return -1;
        int want = (int) Math.min(len, length - pos);
        raf.seek(pos);
        int n = raf.read(buf, off, want);
        if (n > 0) {
            fetched.addAndGet(n);
            calls.incrementAndGet();
        }
        return n;
    }

    @Override
    public long fetchedBytes() {
        return fetched.get();
    }

    @Override
    public int fetchCalls() {
        return calls.get();
    }

    @Override
    public void close() {
        try {
            raf.close();
        } catch (IOException ignored) {
        }
    }
}
